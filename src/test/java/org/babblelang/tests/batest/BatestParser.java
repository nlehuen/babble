package org.babblelang.tests.batest;

import org.babblelang.runtime.LogicalPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-oriented parser for {@code .batest} files (BIP009-D1, BIP009-D3).
 */
public final class BatestParser {
    private static final Pattern CASE_OPEN = Pattern.compile("^\\s*\\{{5,}\\s*$");
    private static final Pattern CASE_CLOSE = Pattern.compile("^\\s*}{5,}\\s*$");
    private static final Pattern MODULE_HEADER = Pattern.compile("^\\s*={8,}\\s+(\\S+\\.ba)\\s+={8,}\\s*$");
    private static final Pattern MAIN_HEADER = Pattern.compile("^\\s*={8,}\\s*$");
    private static final Pattern SESSION_SPLIT = Pattern.compile("^\\s*-{7,}\\s*$");

    private enum State {
        PREAMBLE,
        CODE,
        OUTCOME
    }

    private BatestParser() {
    }

    public static BatestFile parse(Path path, String relativePath) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return parseString(content, relativePath);
    }

    public static BatestFile parseString(String content, String sourceName) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        List<String> preambleLines = new ArrayList<>();
        Map<String, List<String>> moduleLines = new LinkedHashMap<>();
        String currentModule = null;

        List<BatestFile.BatestCase> cases = new ArrayList<>();
        State state = State.PREAMBLE;
        int caseOpenLine = -1;
        List<String> codeLines = new ArrayList<>();
        List<String> outcomeLines = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String line = lines[i];

            if (CASE_OPEN.matcher(line).matches()) {
                if (state == State.CODE) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": unexpected '{{{{{' before closing previous case opened at line " + caseOpenLine);
                }
                if (state == State.OUTCOME) {
                    cases.add(buildCase(cases.size() + 1, caseOpenLine, codeLines, outcomeLines));
                    codeLines.clear();
                    outcomeLines.clear();
                }
                caseOpenLine = lineNum;
                state = State.CODE;
                continue;
            }

            if (CASE_CLOSE.matcher(line).matches()) {
                if (state == State.PREAMBLE) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": unexpected '}}}}}' before any '{{{{{'");
                }
                if (state == State.OUTCOME) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": unexpected '}}}}}' inside expected outcome block");
                }
                state = State.OUTCOME;
                continue;
            }

            switch (state) {
                case PREAMBLE -> {
                    Matcher mod = MODULE_HEADER.matcher(line);
                    if (mod.matches()) {
                        String rawPath = mod.group(1);
                        String modulePath = LogicalPaths.normalize(rawPath);
                        if (modulePath == null) {
                            throw new IllegalArgumentException(
                                    sourceName + ":" + lineNum + ": invalid module path '" + rawPath + "'");
                        }
                        if (moduleLines.containsKey(modulePath)) {
                            throw new IllegalArgumentException(
                                    sourceName + ":" + lineNum + ": duplicate virtual module '" + modulePath + "'");
                        }
                        currentModule = modulePath;
                        moduleLines.put(modulePath, new ArrayList<>());
                    } else if (MAIN_HEADER.matcher(line).matches()) {
                        currentModule = null;
                    } else if (currentModule != null) {
                        moduleLines.get(currentModule).add(line);
                    } else {
                        preambleLines.add(line);
                    }
                }
                case CODE -> codeLines.add(line);
                case OUTCOME -> outcomeLines.add(line);
            }
        }

        if (state == State.CODE) {
            throw new IllegalArgumentException(
                    sourceName + ":" + caseOpenLine + ": unclosed '{{{{{' (missing '}}}}}')");
        }
        if (state == State.OUTCOME) {
            cases.add(buildCase(cases.size() + 1, caseOpenLine, codeLines, outcomeLines));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(sourceName + ": expected at least one test case");
        }

        String preamble = String.join("\n", preambleLines).strip();
        Map<String, String> modules = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : moduleLines.entrySet()) {
            modules.put(entry.getKey(), String.join("\n", entry.getValue()).strip());
        }
        return new BatestFile(sourceName, preamble, Map.copyOf(modules), List.copyOf(cases));
    }

    private static BatestFile.BatestCase buildCase(int index, int openLine,
                                                    List<String> codeLines, List<String> outcomeLines) {
        List<String> inputs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean hasSplitter = false;
        for (String line : codeLines) {
            if (SESSION_SPLIT.matcher(line).matches()) {
                hasSplitter = true;
                inputs.add(String.join("\n", current).strip());
                current.clear();
            } else {
                current.add(line);
            }
        }
        inputs.add(String.join("\n", current).strip());
        String expected = String.join("\n", outcomeLines).strip();
        return new BatestFile.BatestCase(index, openLine, List.copyOf(inputs), hasSplitter, expected);
    }
}
