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
import java.util.regex.PatternSyntaxException;

/**
 * Line-oriented parser for {@code .batest} files (BIP009-D1, BIP009-D3).
 *
 * <p>Enforces readability code-character budgets (excluding Babble comments):
 * preambles and virtual modules must be strictly under {@link #MAX_PREAMBLE_CODE_CHARS}
 * code characters and each test case must be strictly under {@link #MAX_CASE_CODE_CHARS}
 * code characters, unless overridden by a {@code ;; LARGE: <reason>} comment.
 */
public final class BatestParser {
    public static final int MAX_PREAMBLE_CODE_CHARS = 800;
    public static final int MAX_CASE_CODE_CHARS = 400;

    private static final Pattern CASE_OPEN = Pattern.compile("^\\s*\\{{5,}\\s*$");
    private static final Pattern CASE_CLOSE = Pattern.compile("^\\s*}{5,}\\s*$");
    private static final Pattern MODULE_HEADER = Pattern.compile("^\\s*={8,}\\s+(\\S+\\.ba)\\s+={8,}\\s*$");
    private static final Pattern MAIN_HEADER = Pattern.compile("^\\s*={8,}\\s*$");
    private static final Pattern SESSION_SPLIT = Pattern.compile("^\\s*-{7,}\\s*$");
    private static final Pattern LARGE_OVERRIDE = Pattern.compile("^\\s*;;\\s*LARGE\\s*:\\s*\\S.*$");
    private static final Pattern LARGE_MISSING_REASON = Pattern.compile("^\\s*;;\\s*LARGE(?:\\s*:\\s*)?$");

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
        if (sourceName != null && sourceName.endsWith(".batest.md")) {
            return BatestV2Parser.parseString(content, sourceName);
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        List<String> preambleLines = new ArrayList<>();
        boolean preambleLarge = false;
        Map<String, List<String>> moduleLines = new LinkedHashMap<>();
        Map<String, Integer> moduleStartLines = new LinkedHashMap<>();
        Map<String, Boolean> moduleLarge = new LinkedHashMap<>();
        String currentModule = null;

        List<BatestFile.BatestCase> cases = new ArrayList<>();
        State state = State.PREAMBLE;
        int caseOpenLine = -1;
        List<String> codeLines = new ArrayList<>();
        List<String> outcomeLines = new ArrayList<>();
        boolean caseLarge = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String line = lines[i];

            if (CASE_OPEN.matcher(line).matches()) {
                if (state == State.CODE) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": unexpected '{{{{{' before closing previous case opened at line " + caseOpenLine);
                }
                if (state == State.OUTCOME) {
                    cases.add(buildCase(sourceName, cases.size() + 1, caseOpenLine, codeLines, outcomeLines, caseLarge));
                    codeLines.clear();
                    outcomeLines.clear();
                }
                caseOpenLine = lineNum;
                caseLarge = false;
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
                        moduleStartLines.put(modulePath, lineNum);
                        moduleLarge.put(modulePath, false);
                    } else if (MAIN_HEADER.matcher(line).matches()) {
                        currentModule = null;
                    } else if (currentModule != null) {
                        if (checkLargeDirective(line, sourceName, lineNum)) {
                            moduleLarge.put(currentModule, true);
                        }
                        moduleLines.get(currentModule).add(line);
                    } else {
                        if (checkLargeDirective(line, sourceName, lineNum)) {
                            preambleLarge = true;
                        }
                        preambleLines.add(line);
                    }
                }
                case CODE -> {
                    if (checkLargeDirective(line, sourceName, lineNum)) {
                        caseLarge = true;
                    }
                    codeLines.add(line);
                }
                case OUTCOME -> outcomeLines.add(line);
            }
        }

        if (state == State.CODE) {
            throw new IllegalArgumentException(
                    sourceName + ":" + caseOpenLine + ": unclosed '{{{{{' (missing '}}}}}')");
        }
        if (state == State.OUTCOME) {
            cases.add(buildCase(sourceName, cases.size() + 1, caseOpenLine, codeLines, outcomeLines, caseLarge));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(sourceName + ": expected at least one test case");
        }

        String preamble = String.join("\n", preambleLines).strip();
        int preambleLen = codeLength(preamble);
        if (!preambleLarge && preambleLen >= MAX_PREAMBLE_CODE_CHARS) {
            throw new IllegalArgumentException(sourceName + ":1: preamble exceeds " + MAX_PREAMBLE_CODE_CHARS
                    + " code characters (" + preambleLen + " chars, comments excluded) : shrink it or add ';; LARGE: <reason>'");
        }

        Map<String, String> modules = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : moduleLines.entrySet()) {
            String modPath = entry.getKey();
            String modCode = String.join("\n", entry.getValue()).strip();
            int modLen = codeLength(modCode);
            if (!Boolean.TRUE.equals(moduleLarge.get(modPath)) && modLen >= MAX_PREAMBLE_CODE_CHARS) {
                throw new IllegalArgumentException(sourceName + ":" + moduleStartLines.get(modPath)
                        + ": virtual module '" + modPath + "' exceeds " + MAX_PREAMBLE_CODE_CHARS
                        + " code characters (" + modLen + " chars, comments excluded) : shrink it or add ';; LARGE: <reason>'");
            }
            modules.put(modPath, modCode);
        }
        return new BatestFile(sourceName, preamble, Map.copyOf(modules), List.copyOf(cases));
    }

    private static BatestFile.BatestCase buildCase(String sourceName, int index, int openLine,
                                                    List<String> codeLines, List<String> outcomeLines,
                                                    boolean allowLarge) {
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
        int caseLen = codeLength(String.join("\n", inputs));
        if (!allowLarge && caseLen >= MAX_CASE_CODE_CHARS) {
            throw new IllegalArgumentException(sourceName + ":" + openLine + ": case #" + index
                    + " exceeds " + MAX_CASE_CODE_CHARS + " code characters (" + caseLen
                    + " chars, comments excluded) : split it or add ';; LARGE: <reason>'");
        }
        String expected = String.join("\n", outcomeLines).strip();
        checkRegexLines(sourceName, openLine + codeLines.size() + 2, outcomeLines);
        return new BatestFile.BatestCase(index, openLine, List.copyOf(inputs), hasSplitter, expected);
    }

    /** Rejects an invalid regular expression in an expected line, naming its line ({@code firstLine} is the first outcome line's). */
    private static void checkRegexLines(String sourceName, int firstLine, List<String> outcomeLines) {
        for (int i = 0; i < outcomeLines.size(); i++) {
            String line = outcomeLines.get(i).strip();
            if (line.startsWith(BatestFile.BatestCase.REGEX_PREFIX)) {
                try {
                    Pattern.compile(line.substring(BatestFile.BatestCase.REGEX_PREFIX.length()));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(sourceName + ":" + (firstLine + i)
                            + ": invalid regular expression in expected outcome : " + e.getDescription());
                }
            }
        }
    }

    private static boolean checkLargeDirective(String line, String sourceName, int lineNum) {
        if (LARGE_OVERRIDE.matcher(line).matches()) {
            return true;
        }
        if (LARGE_MISSING_REASON.matcher(line).matches()) {
            throw new IllegalArgumentException(sourceName + ":" + lineNum
                    + ": ';; LARGE' requires a reason (use ';; LARGE: <reason>')");
        }
        return false;
    }

    /**
     * Returns the length of {@code source} after removing Babble line ({@code ;; ...})
     * and block ({@code ;;( ... ;;)}) comments and blank comment-only lines.
     */
    public static int codeLength(String source) {
        return stripComments(source).length();
    }

    /**
     * Strips Babble line and block comments while preserving string and char literals,
     * then drops lines that become blank and trims trailing line whitespace.
     */
    static String stripComments(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char sc = source.charAt(i);
                    out.append(sc);
                    i++;
                    if (sc == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (sc == '"') {
                        break;
                    }
                }
            } else if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char cc = source.charAt(i);
                    out.append(cc);
                    i++;
                    if (cc == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (cc == '\'' || cc == '\n') {
                        break;
                    }
                }
            } else if (c == ';' && i + 1 < n && source.charAt(i + 1) == ';') {
                if (i + 2 < n && source.charAt(i + 2) == '(') {
                    i += 3;
                    while (i < n) {
                        if (source.charAt(i) == ';' && i + 2 < n
                                && source.charAt(i + 1) == ';' && source.charAt(i + 2) == ')') {
                            i += 3;
                            break;
                        }
                        i++;
                    }
                } else {
                    i += 2;
                    while (i < n && source.charAt(i) != '\n') {
                        i++;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        String[] rawLines = out.toString().split("\n", -1);
        StringBuilder cleaned = new StringBuilder(out.length());
        for (String line : rawLines) {
            if (!line.isBlank()) {
                if (!cleaned.isEmpty()) {
                    cleaned.append('\n');
                }
                cleaned.append(line.stripTrailing());
            }
        }
        return cleaned.toString().strip();
    }
}
