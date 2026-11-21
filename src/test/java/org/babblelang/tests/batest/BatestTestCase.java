package org.babblelang.tests.batest;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Discovers and executes all {@code src/test/batest/**&#47;*.batest} files in JUnit 5 (BIP009 §5.1).
 * Automatically runs in both {@code interpret} and {@code compile} Surefire executions.
 */
public class BatestTestCase {
    public static final Path BATEST_ROOT = Path.of("src/test/batest");

    @TestFactory
    public Stream<DynamicNode> batestSuite() throws IOException {
        String filterRaw = System.getProperty("batest.filter");
        BatestFilter filter = BatestFilter.parse(filterRaw);
        String recordDir = System.getProperty("batest.record");

        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        String mode = engine.getMode().name().toLowerCase(Locale.ROOT);
        if ("compile".equals(mode) && "0".equals(System.getProperty("babble.opt"))) {
            mode = "compile-O0";
        }
        Path recordFile = prepareRecordFile(recordDir, mode);

        if (!Files.isDirectory(BATEST_ROOT)) {
            if (filter.isActive()) {
                Assertions.fail("-Dbatest.filter matched no files (directory missing: " + BATEST_ROOT + "): " + filterRaw);
            }
            return Stream.empty();
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(BATEST_ROOT)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".batest"))
                    .sorted(Comparator.comparing(p -> BATEST_ROOT.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }

        List<DynamicNode> containers = new ArrayList<>();
        int matchedCases = 0;

        for (Path path : files) {
            String rel = BATEST_ROOT.relativize(path).toString().replace('\\', '/');
            if (!filter.matchesFile(rel)) {
                continue;
            }
            BatestFile batestFile = BatestParser.parse(path, rel);
            List<DynamicTest> caseTests = new ArrayList<>();
            for (BatestFile.BatestCase testCase : batestFile.cases()) {
                if (!filter.matches(batestFile, testCase)) {
                    continue;
                }
                matchedCases++;
                String testName = "case #" + testCase.index() + " (line " + testCase.line() + ")";
                caseTests.add(DynamicTest.dynamicTest(testName, path.toUri(),
                        () -> executeCase(batestFile, testCase, recordFile)));
            }
            if (!caseTests.isEmpty()) {
                containers.add(DynamicContainer.dynamicContainer(rel, path.toUri(), caseTests.stream()));
            }
        }

        if (filter.isActive() && matchedCases == 0) {
            Assertions.fail("-Dbatest.filter matched zero .batest cases: " + filterRaw);
        }
        return containers.stream();
    }

    private static Path prepareRecordFile(String recordDir, String mode) throws IOException {
        if (recordDir == null || recordDir.isBlank()) {
            return null;
        }
        Path dir = Path.of(recordDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("outcomes-" + mode + ".jsonl");
        Files.writeString(file, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
    }

    private static void executeCase(BatestFile file, BatestFile.BatestCase testCase, Path recordFile) {
        BatestRunner.CaseOutcome outcome = BatestRunner.run(file, testCase);
        boolean passed = outcome.kind() != BatestRunner.Kind.INTERNAL_CRASH
                && testCase.expected().equals(outcome.text());
        if (recordFile != null) {
            appendRecord(recordFile, file, testCase, outcome, passed);
        }
        if (outcome.kind() == BatestRunner.Kind.INTERNAL_CRASH) {
            Assertions.fail("INTERNAL_CRASH in " + file.relativePath() + ":" + testCase.line()
                    + " (case #" + testCase.index() + ")\n" + stackTrace(outcome.crash()), outcome.crash());
        }
        Assertions.assertEquals(testCase.expected(), outcome.text(),
                file.relativePath() + ":" + testCase.line() + " (case #" + testCase.index()
                        + ", kind=" + outcome.kind() + ")");
    }

    private static synchronized void appendRecord(Path recordFile, BatestFile file, BatestFile.BatestCase testCase,
                                                  BatestRunner.CaseOutcome outcome, boolean passed) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"file\":\"").append(escapeJson(file.relativePath())).append("\",");
        json.append("\"index\":").append(testCase.index()).append(',');
        json.append("\"line\":").append(testCase.line()).append(',');
        json.append("\"expected\":\"").append(escapeJson(testCase.expected())).append("\",");
        json.append("\"kind\":\"").append(outcome.kind().name()).append("\",");
        json.append("\"text\":\"").append(escapeJson(outcome.text())).append("\",");
        json.append("\"passed\":").append(passed);
        if (outcome.crash() != null) {
            json.append(",\"crash\":\"").append(escapeJson(stackTrace(outcome.crash()))).append('"');
        }
        json.append("}\n");
        try {
            Files.writeString(recordFile, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write batest outcome record to " + recordFile, e);
        }
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
