package org.babblelang.tests.batest;

import org.babblelang.ir.IrSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates {@link BatestFile.IrExpectation} blocks against captured {@link IrSnapshot}s
 * ({@code BIP029-D4}, {@code BIP029-D8}, {@code BIP029-P2}).
 */
public final class IrAssertionChecker {

    public record IrMismatch(
            BatestFile.IrExpectation expectation,
            String actualCanonical,
            String message
    ) {
    }

    private IrAssertionChecker() {
    }

    /** Validates every non-blank line of a {@code check} IR block at parse time. */
    public static void validateCheckPredicates(String body, String sourceName, int firstBodyLine) {
        String[] lines = body.split("\n", -1);
        int predicateCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            predicateCount++;
            int lineNum = firstBodyLine + i;
            parseCheckLine(trimmed, sourceName, lineNum);
        }
        if (predicateCount == 0) {
            throw new IllegalArgumentException(sourceName + ":" + firstBodyLine
                    + ": 'check' IR block must contain at least one predicate (contains, !contains, count, or regex)");
        }
    }

    private record CheckPredicate(String op, int expectedCount, String arg, Pattern regex) {
    }

    private static CheckPredicate parseCheckLine(String line, String sourceName, int lineNum) {
        if (line.startsWith("contains ")) {
            String arg = line.substring("contains ".length());
            if (arg.isEmpty()) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum + ": 'contains' requires a substring");
            }
            return new CheckPredicate("contains", -1, arg, null);
        }
        if (line.startsWith("!contains ")) {
            String arg = line.substring("!contains ".length());
            if (arg.isEmpty()) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum + ": '!contains' requires a substring");
            }
            return new CheckPredicate("!contains", -1, arg, null);
        }
        if (line.startsWith("count ")) {
            String rest = line.substring("count ".length());
            int sp = rest.indexOf(' ');
            if (sp <= 0 || sp == rest.length() - 1) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum
                        + ": 'count' requires '<N> <substring>'");
            }
            int n;
            try {
                n = Integer.parseInt(rest.substring(0, sp));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum
                        + ": invalid count '" + rest.substring(0, sp) + "'");
            }
            if (n < 0) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum + ": count must be >= 0");
            }
            return new CheckPredicate("count", n, rest.substring(sp + 1), null);
        }
        if (line.startsWith("regex ")) {
            String pat = line.substring("regex ".length());
            if (pat.isEmpty()) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum + ": 'regex' requires a pattern");
            }
            try {
                return new CheckPredicate("regex", -1, pat, Pattern.compile(pat, Pattern.MULTILINE | Pattern.DOTALL));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(sourceName + ":" + lineNum
                        + ": invalid regex in 'check' block : " + e.getDescription());
            }
        }
        throw new IllegalArgumentException(sourceName + ":" + lineNum
                + ": unknown 'check' predicate '" + line + "' (expected contains, !contains, count <N>, or regex)");
    }

    /**
     * Checks all IR expectations applicable to {@code activeOptLevel}. Returns an empty list on success.
     */
    public static List<IrMismatch> checkAll(List<BatestFile.IrExpectation> expectations,
                                            List<IrSnapshot> capturedSnapshots,
                                            int activeOptLevel) {
        List<IrMismatch> mismatches = new ArrayList<>();
        for (BatestFile.IrExpectation exp : expectations) {
            if (!exp.appliesTo(activeOptLevel)) {
                continue;
            }
            IrSnapshot snap = findLatestSnapshot(capturedSnapshots, exp, activeOptLevel);
            if (snap == null) {
                mismatches.add(new IrMismatch(exp, null,
                        "No " + exp.tier().label().toUpperCase() + " snapshot captured for [" + exp.describe() + "]"));
                continue;
            }
            String actual;
            try {
                actual = snap.ir().printCanonical(exp.target()).stripTrailing();
            } catch (IllegalArgumentException e) {
                mismatches.add(new IrMismatch(exp, null,
                        "Target resolution failed for [" + exp.describe() + "] : " + e.getMessage()));
                continue;
            }
            String err = matchOne(exp, actual);
            if (err != null) {
                mismatches.add(new IrMismatch(exp, actual, err));
            }
        }
        return mismatches;
    }

    private static IrSnapshot findLatestSnapshot(List<IrSnapshot> snapshots, BatestFile.IrExpectation exp, int activeOptLevel) {
        IrSnapshot found = null;
        for (IrSnapshot s : snapshots) {
            if (s.tier() == exp.tier()
                    && s.optLevel() == activeOptLevel
                    && s.stage().equals(exp.stage())
                    && Objects.equals(s.moduleKey(), exp.modulePath())) {
                found = s;
            }
        }
        return found;
    }

    private static String matchOne(BatestFile.IrExpectation exp, String actual) {
        return switch (exp.matchMode()) {
            case EXACT -> {
                String expected = exp.expected().stripTrailing();
                if (expected.equals(actual)) {
                    yield null;
                }
                yield "IR mismatch for [" + exp.describe() + "]\n--- expected ---\n"
                        + expected + "\n--- actual ---\n" + actual;
            }
            case REGEX -> {
                Pattern pat = Pattern.compile(exp.expected(), Pattern.MULTILINE | Pattern.DOTALL);
                if (pat.matcher(actual).matches()) {
                    yield null;
                }
                yield "IR regex mismatch for [" + exp.describe() + "]\n--- pattern ---\n"
                        + exp.expected() + "\n--- actual ---\n" + actual;
            }
            case CHECK -> {
                List<String> failed = new ArrayList<>();
                String[] lines = exp.expected().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String trimmed = lines[i].trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    CheckPredicate p = parseCheckLine(trimmed, "<ir-check>", exp.bodyStartLine() + i);
                    switch (p.op) {
                        case "contains" -> {
                            if (!actual.contains(p.arg)) {
                                failed.add("expected to contain: " + p.arg);
                            }
                        }
                        case "!contains" -> {
                            if (actual.contains(p.arg)) {
                                failed.add("expected NOT to contain: " + p.arg);
                            }
                        }
                        case "count" -> {
                            int count = countOccurrences(actual, p.arg);
                            if (count != p.expectedCount) {
                                failed.add("expected count " + p.expectedCount + " of '" + p.arg + "', got " + count);
                            }
                        }
                        case "regex" -> {
                            if (!p.regex.matcher(actual).find()) {
                                failed.add("expected regex match: " + p.arg);
                            }
                        }
                    }
                }
                if (failed.isEmpty()) {
                    yield null;
                }
                yield "IR check failed for [" + exp.describe() + "] : " + String.join("; ", failed)
                        + "\n--- actual ---\n" + actual;
            }
        };
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
