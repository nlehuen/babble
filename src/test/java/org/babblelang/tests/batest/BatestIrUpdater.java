package org.babblelang.tests.batest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, guarded IR-only golden updater for {@code -Dbatest.updateIr=true} ({@code
 * BIP029-D9}, {@code BIP029-I2}).
 *
 * <p>Rewrites <em>only</em> exact-golden {@code hir}/{@code mir} block bodies (never {@code
 * babble}, {@code stdout}, {@code stderr}, {@code error}, {@code regex}, or {@code check} blocks),
 * and only when behavioral assertions and bytecode verification passed.
 */
final class BatestIrUpdater {
    static final String PROPERTY = "batest.updateIr";

    private record Replacement(int bodyStartLine, int bodyEndLine, String newBody) {}

    private final Map<Path, List<Replacement>> pending = new ConcurrentHashMap<>();

    static boolean isEnabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    void record(Path filePath, BatestFile.IrExpectation expectation, String actualCanonical) {
        if (expectation.matchMode() != BatestFile.IrMatchMode.EXACT || actualCanonical == null) {
            return;
        }
        pending.computeIfAbsent(filePath, k -> new CopyOnWriteArrayList<>())
                .add(
                        new Replacement(
                                expectation.bodyStartLine(),
                                expectation.bodyEndLine(),
                                actualCanonical));
    }

    boolean hasUpdates() {
        return !pending.isEmpty();
    }

    /** Applies all recorded exact-golden IR block updates and returns a human-readable summary. */
    String flushAll() throws IOException {
        int fileCount = 0;
        int blockCount = 0;
        List<Path> sortedFiles = new ArrayList<>(pending.keySet());
        sortedFiles.sort(Comparator.naturalOrder());
        List<String> fileNames = new ArrayList<>();
        for (Path path : sortedFiles) {
            List<Replacement> reps = new ArrayList<>(pending.get(path));
            if (reps.isEmpty()) {
                continue;
            }
            applyToFile(path, reps);
            fileCount++;
            blockCount += reps.size();
            fileNames.add(path.toString());
        }
        pending.clear();
        return "Updated "
                + blockCount
                + " IR golden block(s) in "
                + fileCount
                + " file(s) ("
                + String.join(", ", fileNames)
                + ") : inspect git diff and re-run without -D"
                + PROPERTY
                + "=true";
    }

    static String applyToString(String original, List<Replacement> replacements) {
        String lineSep = original.contains("\r\n") ? "\r\n" : "\n";
        boolean trailingNewline = original.endsWith("\n");
        String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (trailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }

        // Apply bottom-up so earlier line indices remain valid (deduplicate identical spans from
        // multi-stage blocks).
        Map<Integer, Replacement> deduped = new java.util.LinkedHashMap<>();
        for (Replacement r : replacements) {
            deduped.putIfAbsent(r.bodyStartLine(), r);
        }
        List<Replacement> sorted = new ArrayList<>(deduped.values());
        sorted.sort(Comparator.comparingInt(Replacement::bodyStartLine).reversed());
        for (Replacement r : sorted) {
            int startIdx = r.bodyStartLine() - 1;
            int endIdx = r.bodyEndLine() - 1;
            lines.subList(startIdx, endIdx).clear();
            List<String> newLines =
                    r.newBody().isEmpty() ? List.of() : List.of(r.newBody().split("\n", -1));
            lines.addAll(startIdx, newLines);
        }
        String joined = String.join(lineSep, lines);
        return trailingNewline ? joined + lineSep : joined;
    }

    private static void applyToFile(Path path, List<Replacement> replacements) throws IOException {
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String updated = applyToString(original, replacements);
        Files.writeString(path, updated, StandardCharsets.UTF_8);
    }
}
