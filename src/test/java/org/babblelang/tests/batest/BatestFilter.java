package org.babblelang.tests.batest;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and matches {@code -Dbatest.filter=<selector>} expressions (BIP009 §5.1).
 * Supports substring matches ({@code generics}), globs ({@code objects/*.batest}),
 * case suffixes ({@code types/narrowing.batest#4}), line suffixes
 * ({@code types/narrowing.batest:42}), and comma-separated combinations.
 */
public final class BatestFilter {
    private static final Pattern CASE_OR_LINE_SUFFIX = Pattern.compile("^(.*?)(?:(#)(\\d+)|(:)(\\d+))$");

    private record Selector(String filePart, PathMatcher globMatcher, Integer caseIndex, Integer lineNumber) {
        boolean matchesFile(String relativePath) {
            if (filePart.isEmpty()) {
                return true;
            }
            if (globMatcher != null) {
                return globMatcher.matches(Path.of(relativePath));
            }
            return relativePath.contains(filePart);
        }

        boolean matchesCase(BatestFile file, BatestFile.BatestCase testCase) {
            if (!matchesFile(file.relativePath())) {
                return false;
            }
            if (caseIndex != null && testCase.index() != caseIndex) {
                return false;
            }
            if (lineNumber != null && testCase.line() != lineNumber) {
                return false;
            }
            return true;
        }
    }

    private final List<Selector> selectors;

    private BatestFilter(List<Selector> selectors) {
        this.selectors = selectors;
    }

    public static BatestFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new BatestFilter(List.of());
        }
        List<Selector> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim().replace('\\', '/');
            if (trimmed.isEmpty()) {
                continue;
            }
            String filePart = trimmed;
            Integer caseIndex = null;
            Integer lineNumber = null;
            Matcher m = CASE_OR_LINE_SUFFIX.matcher(trimmed);
            if (m.matches()) {
                filePart = m.group(1);
                if (m.group(2) != null) {
                    caseIndex = Integer.parseInt(m.group(3));
                } else {
                    lineNumber = Integer.parseInt(m.group(5));
                }
            }
            PathMatcher glob = isGlob(filePart)
                    ? FileSystems.getDefault().getPathMatcher("glob:" + filePart)
                    : null;
            list.add(new Selector(filePart, glob, caseIndex, lineNumber));
        }
        return new BatestFilter(list);
    }

    private static boolean isGlob(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0;
    }

    public boolean isActive() {
        return !selectors.isEmpty();
    }

    public boolean matchesFile(String relativePath) {
        if (selectors.isEmpty()) {
            return true;
        }
        for (Selector s : selectors) {
            if (s.matchesFile(relativePath)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(BatestFile file, BatestFile.BatestCase testCase) {
        if (selectors.isEmpty()) {
            return true;
        }
        for (Selector s : selectors) {
            if (s.matchesCase(file, testCase)) {
                return true;
            }
        }
        return false;
    }
}
