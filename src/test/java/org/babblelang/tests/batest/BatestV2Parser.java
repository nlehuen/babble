package org.babblelang.tests.batest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.IrTier;
import org.babblelang.runtime.LogicalPaths;

/**
 * Constrained Markdown-fenced parser for {@code .batest.md} (v2) specification files ({@code
 * BIP029-D1}–{@code D8}, {@code BIP029-I2}).
 *
 * <p>Recognizes only two constructs outside fenced blocks: {@code ## <Case Name>} headings and
 * fenced blocks ({@code ```<kind>}). Block options are provided on the first line inside the block
 * using a language comment prefix ({@code ;; <kind> <options>} for {@code babble}, {@code hir}, and
 * {@code mir}; {@code # <kind> <options>} for {@code stdout}, {@code stderr}, and {@code error}).
 * Everything else outside fences is free Markdown documentation and excluded from Babble
 * code-character budgets.
 */
final class BatestV2Parser {
    private static final Pattern CASE_HEADING = Pattern.compile("^##(?:\\s+(.*))?$");
    private static final Pattern FENCE_OPEN =
            Pattern.compile("^ {0,3}(`{3,})\\s*(\\S+)(?:\\s+(.*))?$");
    private static final Pattern FENCE_OPEN_NO_KIND = Pattern.compile("^ {0,3}(`{3,})\\s*$");
    private static final Pattern OPT_LEVEL_TOKEN = Pattern.compile("^-O[012](?:,-O[012])*$");

    private BatestV2Parser() {}

    static BatestFile parseString(String content, String sourceName) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && normalized.endsWith("\n") && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        if (lineCount > BatestParser.MAX_FILE_LINES) {
            throw new IllegalArgumentException(
                    "The file "
                            + sourceName
                            + " is too big with "
                            + lineCount
                            + " lines : maximum is "
                            + BatestParser.MAX_FILE_LINES
                            + " lines. Please split it into chunks that are < "
                            + BatestParser.MAX_FILE_LINES
                            + " lines. Please ensure test cases are logically grouped into chunks and name each chunk in a descriptive fashion (not foo_1.batest.md, foo_2.batest.md, etc.).");
        }

        String preamble = null;
        int preambleLine = -1;
        Map<String, String> fileModules = new LinkedHashMap<>();

        List<BatestFile.BatestCase> cases = new ArrayList<>();
        Set<String> seenCaseNames = new HashSet<>();
        CaseBuilder activeCase = null;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String line = lines[i];

            Matcher fenceOpen = FENCE_OPEN.matcher(line);
            if (fenceOpen.matches()) {
                int fenceLen = fenceOpen.group(1).length();
                String kind = fenceOpen.group(2);
                String rest = fenceOpen.group(3) != null ? fenceOpen.group(3).trim() : "";

                int bodyStartLine = lineNum + 1;
                List<String> bodyLines = new ArrayList<>();
                int closeLine = -1;
                for (int j = i + 1; j < lines.length; j++) {
                    if (isClosingFence(lines[j], fenceLen)) {
                        closeLine = j + 1;
                        i = j;
                        break;
                    }
                    bodyLines.add(lines[j]);
                }
                if (closeLine < 0) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": unclosed fenced block '" + kind + "'");
                }

                String commentTok = commentTokenFor(kind);
                if (commentTok == null) {
                    if (activeCase == null) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + lineNum
                                        + ": only 'babble' blocks with ';; babble preamble' or ';; babble module=<path>' are allowed before the first '##' case heading (got '"
                                        + kind
                                        + "')");
                    }
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + lineNum
                                    + ": unknown fenced block kind '"
                                    + kind
                                    + "' (expected babble, stdout, stderr, error, hir, or mir)");
                }
                if (!rest.isEmpty()) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + lineNum
                                    + ": options on the opening fence line ('"
                                    + kind
                                    + " "
                                    + rest
                                    + "') are not allowed : put them on the first line of the block as '"
                                    + commentTok
                                    + " "
                                    + kind
                                    + " "
                                    + rest
                                    + "'");
                }

                List<String> tokens = List.of();
                int optionsLine = lineNum;
                int effectiveBodyStartLine = bodyStartLine;
                List<String> effectiveBodyLines = bodyLines;
                if (!bodyLines.isEmpty()) {
                    String firstLine = bodyLines.get(0);
                    String directivePrefix = commentTok + " " + kind + " ";
                    if (firstLine.startsWith(directivePrefix)) {
                        String optionText = firstLine.substring(directivePrefix.length()).trim();
                        optionsLine = bodyStartLine;
                        tokens = tokenizeInfo(optionText, sourceName, optionsLine);
                        effectiveBodyLines = bodyLines.subList(1, bodyLines.size());
                        effectiveBodyStartLine = bodyStartLine + 1;
                    } else {
                        String wrongTok = ";;".equals(commentTok) ? "#" : ";;";
                        String wrongPrefix = wrongTok + " " + kind + " ";
                        if (firstLine.startsWith(wrongPrefix)
                                && (";;".equals(commentTok)
                                        || "regex"
                                                .equals(
                                                        firstLine
                                                                .substring(wrongPrefix.length())
                                                                .trim()))) {
                            throw new IllegalArgumentException(
                                    sourceName
                                            + ":"
                                            + bodyStartLine
                                            + ": wrong comment token '"
                                            + wrongTok
                                            + "' for '"
                                            + kind
                                            + "' block options : use '"
                                            + commentTok
                                            + " "
                                            + kind
                                            + " "
                                            + firstLine.substring(wrongPrefix.length()).trim()
                                            + "'");
                        }
                    }
                }

                String rawBody = String.join("\n", effectiveBodyLines);

                if (activeCase == null) {
                    if (!"babble".equals(kind)) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + lineNum
                                        + ": only 'babble' blocks with ';; babble preamble' or ';; babble module=<path>' are allowed before the first '##' case heading (got '"
                                        + kind
                                        + "')");
                    }
                    BabbleAttrs attrs = parseBabbleAttrs(tokens, sourceName, optionsLine, true);
                    String code = rawBody.strip();
                    if (attrs.preamble) {
                        if (preamble != null) {
                            throw new IllegalArgumentException(
                                    sourceName
                                            + ":"
                                            + lineNum
                                            + ": duplicate 'babble preamble' block (first declared at line "
                                            + preambleLine
                                            + ")");
                        }
                        preamble = code;
                        preambleLine = lineNum;
                        checkBudget(
                                sourceName,
                                lineNum,
                                "preamble",
                                code,
                                BatestParser.MAX_PREAMBLE_CODE_CHARS,
                                attrs.large);
                    } else if (attrs.modulePath != null) {
                        if (fileModules.containsKey(attrs.modulePath)) {
                            throw new IllegalArgumentException(
                                    sourceName
                                            + ":"
                                            + lineNum
                                            + ": duplicate virtual module '"
                                            + attrs.modulePath
                                            + "'");
                        }
                        checkBudget(
                                sourceName,
                                lineNum,
                                "virtual module '" + attrs.modulePath + "'",
                                code,
                                BatestParser.MAX_PREAMBLE_CODE_CHARS,
                                attrs.large);
                        fileModules.put(attrs.modulePath, code);
                    } else {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + lineNum
                                        + ": top-level 'babble' block before first '##' heading must start with ';; babble preamble' or ';; babble module=<path>'");
                    }
                } else {
                    handleCaseBlock(
                            activeCase,
                            kind,
                            tokens,
                            rawBody,
                            sourceName,
                            lineNum,
                            optionsLine,
                            effectiveBodyStartLine,
                            closeLine);
                }
                continue;
            }

            if (FENCE_OPEN_NO_KIND.matcher(line).matches()) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + lineNum
                                + ": fenced block is missing a kind (expected babble, stdout, stderr, error, hir, or mir)");
            }

            Matcher heading = CASE_HEADING.matcher(line);
            if (heading.matches()) {
                String title = heading.group(1) != null ? heading.group(1).trim() : "";
                if (title.isEmpty()) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": empty '##' case heading");
                }
                if (!seenCaseNames.add(title)) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": duplicate case name '" + title + "'");
                }
                if (activeCase != null) {
                    cases.add(activeCase.build(sourceName));
                }
                activeCase = new CaseBuilder(cases.size() + 1, lineNum, title);
            }
            // All other lines outside fences (#, ###, prose, blank lines) are free documentation.
        }

        if (activeCase != null) {
            cases.add(activeCase.build(sourceName));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    sourceName + ": expected at least one '##' test case");
        }
        return new BatestFile(
                sourceName,
                BatestFile.Format.V2,
                preamble != null ? preamble : "",
                Map.copyOf(fileModules),
                List.copyOf(cases));
    }

    private static String commentTokenFor(String kind) {
        return switch (kind) {
            case "babble", "hir", "mir", "wat" -> ";;";
            case "stdout", "stderr", "error" -> "#";
            default -> null;
        };
    }

    private static boolean isClosingFence(String line, int minBackticks) {
        int i = 0;
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        int ticks = 0;
        while (i < line.length() && line.charAt(i) == '`') {
            ticks++;
            i++;
        }
        if (ticks < minBackticks) {
            return false;
        }
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
            i++;
        }
        return true;
    }

    private static void handleCaseBlock(
            CaseBuilder c,
            String kind,
            List<String> tokens,
            String rawBody,
            String sourceName,
            int headerLine,
            int optionsLine,
            int bodyStartLine,
            int closeLine) {
        switch (kind) {
            case "babble" -> {
                BabbleAttrs attrs = parseBabbleAttrs(tokens, sourceName, optionsLine, false);
                String code = rawBody.strip();
                if (attrs.modulePath != null) {
                    if (c.caseModules.containsKey(attrs.modulePath)) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + headerLine
                                        + ": duplicate case virtual module '"
                                        + attrs.modulePath
                                        + "'");
                    }
                    checkBudget(
                            sourceName,
                            headerLine,
                            "virtual module '" + attrs.modulePath + "'",
                            code,
                            BatestParser.MAX_PREAMBLE_CODE_CHARS,
                            attrs.large);
                    c.caseModules.put(attrs.modulePath, code);
                } else if (attrs.session) {
                    if (!c.inputs.isEmpty() && !c.session) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + headerLine
                                        + ": cannot mix 'babble' and ';; babble session' blocks in the same case");
                    }
                    c.session = true;
                    if (attrs.large) {
                        c.allowLarge = true;
                    }
                    c.inputs.add(code);
                } else {
                    if (c.session) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + headerLine
                                        + ": cannot mix ';; babble session' and plain 'babble' blocks in the same case");
                    }
                    if (!c.inputs.isEmpty()) {
                        throw new IllegalArgumentException(
                                sourceName
                                        + ":"
                                        + headerLine
                                        + ": multiple 'babble' blocks in one case require ';; babble session'");
                    }
                    if (attrs.large) {
                        c.allowLarge = true;
                    }
                    c.inputs.add(code);
                }
            }
            case "stdout" -> {
                if (c.hasStdout) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + headerLine + ": duplicate 'stdout' block in case");
                }
                boolean regex = parseOutcomeFlags(tokens, kind, sourceName, optionsLine);
                String body = rawBody.strip();
                if (regex) {
                    validateRegex(body, sourceName, headerLine, "stdout");
                }
                c.hasStdout = true;
                c.expectedStdout = new BatestFile.TextExpectation(body, regex);
            }
            case "stderr" -> {
                if (c.hasStderr) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + headerLine + ": duplicate 'stderr' block in case");
                }
                boolean regex = parseOutcomeFlags(tokens, kind, sourceName, optionsLine);
                String body = rawBody.strip();
                if (regex) {
                    validateRegex(body, sourceName, headerLine, "stderr");
                }
                c.hasStderr = true;
                c.expectedStderr = new BatestFile.TextExpectation(body, regex);
            }
            case "error" -> {
                if (c.hasError) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + headerLine + ": duplicate 'error' block in case");
                }
                boolean regex = parseOutcomeFlags(tokens, kind, sourceName, optionsLine);
                String body = rawBody.strip();
                if (regex) {
                    validateRegex(body, sourceName, headerLine, "error");
                }
                c.hasError = true;
                c.expectedError = new BatestFile.TextExpectation(body, regex);
            }
            case "hir", "mir", "wat" -> {
                IrTier tier = IrTier.parse(kind);
                BatestFile.IrExpectation ir =
                        parseIrBlock(
                                tier,
                                tokens,
                                rawBody,
                                sourceName,
                                headerLine,
                                optionsLine,
                                bodyStartLine,
                                closeLine);
                for (int opt : ir.optLevels()) {
                    for (String st : ir.stages()) {
                        String dedupKey =
                                tier
                                        + "|O"
                                        + opt
                                        + "|"
                                        + st
                                        + "|"
                                        + ir.modulePath()
                                        + "|"
                                        + ir.target().format();
                        if (!c.seenIrKeys.add(dedupKey)) {
                            throw new IllegalArgumentException(
                                    sourceName
                                            + ":"
                                            + headerLine
                                            + ": duplicate IR block for "
                                            + ir.describe());
                        }
                    }
                }
                c.irExpectations.add(ir);
            }
            default ->
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + headerLine
                                    + ": unknown fenced block kind '"
                                    + kind
                                    + "' (expected babble, stdout, stderr, error, hir, mir, or wat)");
        }
    }

    private static boolean parseOutcomeFlags(
            List<String> tokens, String kind, String sourceName, int lineNum) {
        boolean regex = false;
        for (String tok : tokens) {
            if ("regex".equals(tok) && !regex) {
                regex = true;
            } else {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + lineNum
                                + ": unexpected attribute '"
                                + tok
                                + "' on '"
                                + kind
                                + "' block (only 'regex' is supported)");
            }
        }
        return regex;
    }

    private static BatestFile.IrExpectation parseIrBlock(
            IrTier tier,
            List<String> tokens,
            String rawBody,
            String sourceName,
            int headerLine,
            int optionsLine,
            int bodyStartLine,
            int closeLine) {
        Set<Integer> optLevels = null;
        IrTarget target = null;
        String stage = IrSnapshot.STAGE_FINAL;
        boolean sawStage = false;
        String modulePath = null;
        BatestFile.IrMatchMode matchMode = BatestFile.IrMatchMode.EXACT;
        boolean sawMatchMode = false;

        for (String tok : tokens) {
            if (OPT_LEVEL_TOKEN.matcher(tok).matches()) {
                if (optLevels != null) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + optionsLine
                                    + ": duplicate optimization level specifier '"
                                    + tok
                                    + "'");
                }
                optLevels = new LinkedHashSet<>();
                for (String part : tok.split(",")) {
                    optLevels.add(Integer.parseInt(part.substring(2)));
                }
            } else if ("regex".equals(tok)) {
                if (sawMatchMode) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + optionsLine
                                    + ": 'regex' and 'check' are mutually exclusive on IR blocks");
                }
                sawMatchMode = true;
                matchMode = BatestFile.IrMatchMode.REGEX;
            } else if ("check".equals(tok)) {
                if (sawMatchMode) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + optionsLine
                                    + ": 'regex' and 'check' are mutually exclusive on IR blocks");
                }
                sawMatchMode = true;
                matchMode = BatestFile.IrMatchMode.CHECK;
            } else if (tok.startsWith("stage=")) {
                if (sawStage) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + optionsLine + ": duplicate 'stage=' attribute");
                }
                sawStage = true;
                String rawStages = tok.substring("stage=".length());
                String[] parts = rawStages.split(",", -1);
                List<String> valid = new ArrayList<>(parts.length);
                Set<String> seenSt = new LinkedHashSet<>();
                for (String p : parts) {
                    try {
                        String validated = IrSnapshot.validateStage(p);
                        if (!seenSt.add(validated)) {
                            throw new IllegalArgumentException(
                                    "duplicate stage '" + validated + "' in '" + tok + "'");
                        }
                        valid.add(validated);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                sourceName + ":" + optionsLine + ": " + e.getMessage());
                    }
                }
                stage = String.join(",", valid);
            } else if (tok.startsWith("in=")) {
                if (modulePath != null) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + optionsLine + ": duplicate 'in=' attribute");
                }
                String rawPath = tok.substring("in=".length());
                modulePath = LogicalPaths.normalize(rawPath);
                if (modulePath == null) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + optionsLine
                                    + ": invalid module path in 'in="
                                    + rawPath
                                    + "'");
                }
            } else if (target == null && isTargetToken(tok)) {
                try {
                    target = IrTarget.parse(tok);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + optionsLine + ": " + e.getMessage());
                }
            } else {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + optionsLine
                                + ": unexpected token '"
                                + tok
                                + "' in '"
                                + tier.label()
                                + "' block options");
            }
        }
        if (optLevels == null || optLevels.isEmpty()) {
            throw new IllegalArgumentException(
                    sourceName
                            + ":"
                            + optionsLine
                            + ": '"
                            + tier.label()
                            + "' block requires an optimization level on its first line (e.g. ';; "
                            + tier.label()
                            + " -O1 fn=f')");
        }
        if (target == null) {
            throw new IllegalArgumentException(
                    sourceName
                            + ":"
                            + optionsLine
                            + ": '"
                            + tier.label()
                            + "' block requires a target (e.g. fn=f, fn=Type.m, record=Vec, top-level, or module)");
        }

        String body = rawBody.stripTrailing();
        if (body.startsWith("\n")) {
            body = body.strip();
        }
        if (matchMode == BatestFile.IrMatchMode.REGEX) {
            body = rawBody.strip();
            validateRegex(body, sourceName, headerLine, tier.label());
        } else if (matchMode == BatestFile.IrMatchMode.CHECK) {
            body = rawBody.strip();
            IrAssertionChecker.validateCheckPredicates(body, sourceName, bodyStartLine);
        }
        return new BatestFile.IrExpectation(
                tier,
                optLevels,
                target,
                stage,
                modulePath,
                matchMode,
                body,
                headerLine,
                bodyStartLine,
                closeLine);
    }

    private static boolean isTargetToken(String tok) {
        return "top-level".equals(tok)
                || "module".equals(tok)
                || tok.startsWith("fn=")
                || tok.startsWith("class=")
                || tok.startsWith("record=")
                || tok.startsWith("interface=");
    }

    private record BabbleAttrs(
            boolean preamble, boolean session, String modulePath, boolean large) {}

    private static BabbleAttrs parseBabbleAttrs(
            List<String> tokens, String sourceName, int lineNum, boolean fileLevel) {
        boolean preamble = false;
        boolean session = false;
        String modulePath = null;
        boolean large = false;
        for (String tok : tokens) {
            if ("preamble".equals(tok)) {
                if (!fileLevel) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + lineNum
                                    + ": 'preamble' is only valid before the first '##' case heading");
                }
                if (preamble || modulePath != null) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": conflicting 'babble' attributes");
                }
                preamble = true;
            } else if ("session".equals(tok)) {
                if (fileLevel) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + lineNum
                                    + ": 'session' is only valid inside a '##' test case");
                }
                if (session || modulePath != null) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": conflicting 'babble' attributes");
                }
                session = true;
            } else if (tok.startsWith("module=")) {
                if (modulePath != null || preamble || session) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": conflicting 'babble' attributes");
                }
                String rawPath = unquote(tok.substring("module=".length()));
                modulePath = LogicalPaths.normalize(rawPath);
                if (modulePath == null) {
                    throw new IllegalArgumentException(
                            sourceName + ":" + lineNum + ": invalid module path '" + rawPath + "'");
                }
            } else if (tok.startsWith("large=")) {
                String reason = unquote(tok.substring("large=".length())).trim();
                if (reason.isEmpty()) {
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + lineNum
                                    + ": 'large=' requires a non-empty reason (e.g. large=\"stress test\")");
                }
                large = true;
            } else if ("large".equals(tok)) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + lineNum
                                + ": 'large' requires a reason (use large=\"<reason>\")");
            } else {
                throw new IllegalArgumentException(
                        sourceName + ":" + lineNum + ": unknown 'babble' attribute '" + tok + "'");
            }
        }
        return new BabbleAttrs(preamble, session, modulePath, large);
    }

    private static List<String> tokenizeInfo(String info, String sourceName, int lineNum) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = info.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(info.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            StringBuilder tok = new StringBuilder();
            boolean inQuotes = false;
            while (i < n) {
                char c = info.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                    tok.append(c);
                    i++;
                } else if (!inQuotes && Character.isWhitespace(c)) {
                    break;
                } else {
                    tok.append(c);
                    i++;
                }
            }
            if (inQuotes) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + lineNum
                                + ": unclosed quoted attribute in block options");
            }
            tokens.add(tok.toString());
        }
        return tokens;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static void validateRegex(String pattern, String sourceName, int lineNum, String kind) {
        try {
            Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    sourceName
                            + ":"
                            + lineNum
                            + ": invalid regular expression in '"
                            + kind
                            + " regex' block : "
                            + e.getDescription());
        }
    }

    private static void checkBudget(
            String sourceName,
            int lineNum,
            String label,
            String code,
            int maxChars,
            boolean allowLarge) {
        int len = BatestParser.codeLength(code);
        if (!allowLarge && len >= maxChars) {
            throw new IllegalArgumentException(
                    sourceName
                            + ":"
                            + lineNum
                            + ": "
                            + label
                            + " exceeds "
                            + maxChars
                            + " code characters ("
                            + len
                            + " chars, comments excluded) : shrink it or add large=\"<reason>\"");
        }
    }

    private static final class CaseBuilder {
        final int index;
        final int line;
        final String name;
        final List<String> inputs = new ArrayList<>();
        final Map<String, String> caseModules = new LinkedHashMap<>();
        final List<BatestFile.IrExpectation> irExpectations = new ArrayList<>();
        final Set<String> seenIrKeys = new HashSet<>();
        boolean session;
        boolean allowLarge;
        boolean hasStdout;
        boolean hasStderr;
        boolean hasError;
        BatestFile.TextExpectation expectedStdout = BatestFile.TextExpectation.EMPTY;
        BatestFile.TextExpectation expectedStderr = BatestFile.TextExpectation.EMPTY;
        BatestFile.TextExpectation expectedError;

        CaseBuilder(int index, int line, String name) {
            this.index = index;
            this.line = line;
            this.name = name;
        }

        BatestFile.BatestCase build(String sourceName) {
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + line
                                + ": case '"
                                + name
                                + "' is missing a 'babble' code block");
            }
            if (session && !irExpectations.isEmpty()) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + irExpectations.get(0).headerLine()
                                + ": IR golden blocks are not supported on 'babble session' cases");
            }
            int len = BatestParser.codeLength(String.join("\n", inputs));
            if (!allowLarge && len >= BatestParser.MAX_CASE_CODE_CHARS) {
                throw new IllegalArgumentException(
                        sourceName
                                + ":"
                                + line
                                + ": case '"
                                + name
                                + "' exceeds "
                                + BatestParser.MAX_CASE_CODE_CHARS
                                + " code characters ("
                                + len
                                + " chars, comments excluded) : split it or add large=\"<reason>\"");
            }
            if ((sourceName.startsWith("compiler/") || sourceName.contains("/compiler/"))
                    && expectedError == null
                    && !session) {
                boolean hasHirO0 = false;
                boolean hasHirO1 = false;
                boolean hasMirO2Before = false;
                boolean hasMirO2Final = false;
                for (BatestFile.IrExpectation ir : irExpectations) {
                    if (ir.tier() == IrTier.HIR) {
                        if (ir.appliesTo(0)) hasHirO0 = true;
                        if (ir.appliesTo(1)) hasHirO1 = true;
                    } else if (ir.tier() == IrTier.MIR && ir.appliesTo(2)) {
                        if (ir.matchesStage("before")) hasMirO2Before = true;
                        if (ir.matchesStage("final")) hasMirO2Final = true;
                    }
                }
                if (!hasHirO0 || !hasHirO1 || !hasMirO2Before || !hasMirO2Final) {
                    List<String> missing = new ArrayList<>();
                    if (!hasHirO0) missing.add("hir -O0");
                    if (!hasHirO1) missing.add("hir -O1");
                    if (!hasMirO2Before) missing.add("mir -O2 stage=before");
                    if (!hasMirO2Final) missing.add("mir -O2 stage=final");
                    throw new IllegalArgumentException(
                            sourceName
                                    + ":"
                                    + line
                                    + ": compiler case '"
                                    + name
                                    + "' is missing required IR assertion(s) : "
                                    + String.join(", ", missing));
                }
            }
            String combinedSummary =
                    summarizeExpected(expectedStderr, expectedStdout, expectedError);
            return new BatestFile.BatestCase(
                    index,
                    line,
                    name,
                    BatestFile.Format.V2,
                    inputs,
                    session,
                    caseModules,
                    combinedSummary,
                    expectedStdout,
                    expectedStderr,
                    expectedError,
                    irExpectations);
        }

        private static String summarizeExpected(
                BatestFile.TextExpectation stderr,
                BatestFile.TextExpectation stdout,
                BatestFile.TextExpectation error) {
            StringBuilder sb = new StringBuilder();
            if (!stderr.text().isEmpty()) {
                sb.append(stderr.text());
            }
            if (!stdout.text().isEmpty()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(stdout.text());
            }
            if (error != null && !error.text().isEmpty()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(error.text());
            }
            return sb.toString();
        }
    }
}
