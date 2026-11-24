package org.babblelang.tests.batest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point and shared code-budget utilities for {@code .batest.md} (v2) specification files
 * ({@code BIP029-D1}–{@code D10}, retiring legacy v1 {@code .batest}).
 *
 * <p>Enforces readability code-character budgets (excluding Babble comments and outside-fence
 * Markdown prose): preambles and virtual modules must be strictly under
 * {@link #MAX_PREAMBLE_CODE_CHARS} code characters and each test case must be strictly under
 * {@link #MAX_CASE_CODE_CHARS} code characters, unless overridden by {@code large="<reason>"}.
 */
public final class BatestParser {
    public static final int MAX_PREAMBLE_CODE_CHARS = 800;
    public static final int MAX_CASE_CODE_CHARS = 400;

    private BatestParser() {
    }

    public static BatestFile parse(Path path, String relativePath) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return parseString(content, relativePath);
    }

    public static BatestFile parseString(String content, String sourceName) {
        return BatestV2Parser.parseString(content, sourceName);
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
