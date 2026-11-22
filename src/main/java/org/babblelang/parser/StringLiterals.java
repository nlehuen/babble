package org.babblelang.parser;

import org.antlr.v4.runtime.Token;

import java.util.Arrays;
import java.util.List;

/**
 * Compile-time validation, JEP 378 dedenting, escape translation, and formatter layout helper for
 * Babble string and character literals ({@code BIP026-D1}–{@code D3}).
 */
public final class StringLiterals {
    private StringLiterals() {
    }

    /**
     * A lexical failure detected when validating a multiline string token (closed or unterminated).
     */
    public record LexicalError(String message, int line, int charPositionInLine, boolean atEnd) {
        public String formattedMessage() {
            return "Lexical error at line " + line + ":" + charPositionInLine + " : " + message;
        }
    }

    /**
     * Parsed line structure of a valid multiline string literal for {@code Formatter}.
     */
    public record MultilineLayout(
            String openingDelimiter,
            List<String> lines,
            boolean[] blank,
            int minIndent,
            boolean extraIndentHasTabs
    ) {
    }

    public static String decode(BabbleParser.StringContext ctx) {
        return decode(ctx.getStart());
    }

    public static String decode(Token token) {
        String text = token.getText();
        return switch (token.getType()) {
            case BabbleLexer.STRING -> unescape(text);
            case BabbleLexer.RAW_STRING -> text.substring(2, text.length() - 1);
            case BabbleLexer.MULTILINE_STRING -> decodeMultiline(text, false);
            case BabbleLexer.RAW_MULTILINE_STRING -> decodeMultiline(text, true);
            default -> throw new IllegalArgumentException("Not a string literal token : " + text);
        };
    }

    /**
     * Decodes a quoted single-line {@code "..."} or {@code '...'} literal.
     */
    public static String unescape(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        return translateEscapes(body, false);
    }

    /**
     * Decodes a quoted single-line {@code '...'} character literal.
     */
    public static char decodeChar(String literal) {
        return unescape(literal).charAt(0);
    }

    /**
     * Validates a closed {@code MULTILINE_STRING} or {@code RAW_MULTILINE_STRING} token, returning
     * {@code null} if valid or a {@link LexicalError} with {@code atEnd = false} otherwise.
     */
    public static LexicalError validateMultiline(Token token, boolean raw) {
        String text = normalizeNewlines(token.getText());
        int openLen = raw ? 4 : 3;
        int firstNl = text.indexOf('\n', openLen);
        if (firstNl < 0 || !isHorizontalWhitespaceOnly(text, openLen, firstNl)) {
            return new LexicalError("multiline string opening \"\"\" must be followed by a newline",
                    token.getLine(), token.getCharPositionInLine(), false);
        }
        String body = text.substring(firstNl + 1, text.length() - 3);
        String[] lines = body.split("\n", -1);
        boolean[] blank = new boolean[lines.length];
        int minIndent = computeMinIndent(lines, blank);
        if (minIndent < 0) {
            return new LexicalError("mixed tabs and spaces in multiline string indentation",
                    token.getLine(), token.getCharPositionInLine(), false);
        }
        if (!raw) {
            String dedented = dedent(lines, blank, minIndent);
            String escapeError = findEscapeError(dedented, true, false);
            if (escapeError != null) {
                return new LexicalError(escapeError, token.getLine(), token.getCharPositionInLine(), false);
            }
        }
        return null;
    }

    /**
     * Validates an {@code UNTERMINATED_MULTILINE_STRING} or {@code UNTERMINATED_RAW_MULTILINE_STRING}
     * token. Always returns a non-null {@link LexicalError}; {@code atEnd} is {@code true} only when
     * the prefix typed so far is lexically valid and awaits more input (used by {@code BabbleSession.classify}).
     */
    public static LexicalError validateUnterminated(Token token, boolean raw) {
        String text = normalizeNewlines(token.getText());
        int openLen = raw ? 4 : 3;
        int firstNl = text.indexOf('\n', openLen);
        if (firstNl < 0) {
            boolean validOpeningSoFar = isHorizontalWhitespaceOnly(text, openLen, text.length());
            if (validOpeningSoFar) {
                return new LexicalError("unterminated multiline string",
                        token.getLine(), token.getCharPositionInLine(), true);
            }
            return new LexicalError("multiline string opening \"\"\" must be followed by a newline",
                    token.getLine(), token.getCharPositionInLine(), false);
        }
        if (!isHorizontalWhitespaceOnly(text, openLen, firstNl)) {
            return new LexicalError("multiline string opening \"\"\" must be followed by a newline",
                    token.getLine(), token.getCharPositionInLine(), false);
        }
        String body = text.substring(firstNl + 1);
        String[] lines = body.split("\n", -1);
        boolean hasSpace = false;
        boolean hasTab = false;
        for (String line : lines) {
            if (isHorizontalWhitespaceOnly(line, 0, line.length())) {
                continue;
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    hasSpace = true;
                } else if (c == '\t') {
                    hasTab = true;
                } else {
                    break;
                }
            }
            if (hasSpace && hasTab) {
                return new LexicalError("mixed tabs and spaces in multiline string indentation",
                        token.getLine(), token.getCharPositionInLine(), false);
            }
        }
        if (!raw) {
            StringBuilder normalizedCompleted = new StringBuilder(body.length());
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    normalizedCompleted.append('\n');
                }
                if (i < lines.length - 1) {
                    normalizedCompleted.append(stripTrailingHorizontal(lines[i]));
                } else {
                    String strippedLast = stripTrailingHorizontal(lines[i]);
                    normalizedCompleted.append(endsWithOddBackslashes(strippedLast) ? strippedLast : lines[i]);
                }
            }
            String escapeError = findEscapeError(normalizedCompleted.toString(), true, true);
            if (escapeError != null) {
                return new LexicalError(escapeError, token.getLine(), token.getCharPositionInLine(), false);
            }
        }
        return new LexicalError("unterminated multiline string",
                token.getLine(), token.getCharPositionInLine(), true);
    }

    /**
     * Computes the indentation layout of a valid {@code MULTILINE_STRING} or
     * {@code RAW_MULTILINE_STRING} token for {@code Formatter}.
     */
    public static MultilineLayout layout(Token token) {
        boolean raw = token.getType() == BabbleLexer.RAW_MULTILINE_STRING;
        String openingDelimiter = raw ? "r\"\"\"" : "\"\"\"";
        String text = normalizeNewlines(token.getText());
        int firstNl = text.indexOf('\n', openingDelimiter.length());
        if (firstNl < 0 || !isHorizontalWhitespaceOnly(text, openingDelimiter.length(), firstNl)) {
            throw new IllegalArgumentException("Invalid multiline string opening : " + text);
        }
        String body = text.substring(firstNl + 1, text.length() - 3);
        String[] lines = body.split("\n", -1);
        boolean[] blank = new boolean[lines.length];
        int minIndent = computeMinIndent(lines, blank);
        if (minIndent < 0) {
            throw new IllegalArgumentException("Mixed tabs and spaces in multiline string indentation");
        }
        boolean extraIndentHasTabs = false;
        for (int i = 0; i < lines.length; i++) {
            if (!isHorizontalWhitespaceOnly(lines[i], 0, lines[i].length())
                    && lines[i].length() > minIndent
                    && lines[i].charAt(minIndent) == '\t') {
                extraIndentHasTabs = true;
                break;
            }
        }
        return new MultilineLayout(openingDelimiter, Arrays.asList(lines), blank, minIndent, extraIndentHasTabs);
    }

    private static boolean endsWithOddBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    public static String stripTrailingHorizontal(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return end == line.length() ? line : line.substring(0, end);
    }

    private static String decodeMultiline(String tokenText, boolean raw) {
        String text = normalizeNewlines(tokenText);
        int openLen = raw ? 4 : 3;
        int firstNl = text.indexOf('\n', openLen);
        if (firstNl < 0 || !isHorizontalWhitespaceOnly(text, openLen, firstNl)) {
            throw new IllegalArgumentException("Multiline string opening \"\"\" must be followed by a newline");
        }
        String body = text.substring(firstNl + 1, text.length() - 3);
        String[] lines = body.split("\n", -1);
        boolean[] blank = new boolean[lines.length];
        int minIndent = computeMinIndent(lines, blank);
        if (minIndent < 0) {
            throw new IllegalArgumentException("Mixed tabs and spaces in multiline string indentation");
        }
        String dedented = dedent(lines, blank, minIndent);
        return raw ? dedented : translateEscapes(dedented, true);
    }

    private static int computeMinIndent(String[] lines, boolean[] blank) {
        int minIndent = Integer.MAX_VALUE;
        boolean hasSpace = false;
        boolean hasTab = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Closing line (last) always participates even if it contains only horizontal whitespace.
            if (i < lines.length - 1 && isHorizontalWhitespaceOnly(line, 0, line.length())) {
                blank[i] = true;
                continue;
            }
            int lead = 0;
            while (lead < line.length()) {
                char c = line.charAt(lead);
                if (c == ' ') {
                    hasSpace = true;
                    lead++;
                } else if (c == '\t') {
                    hasTab = true;
                    lead++;
                } else {
                    break;
                }
            }
            if (hasSpace && hasTab) {
                return -1;
            }
            if (lead < minIndent) {
                minIndent = lead;
            }
        }
        return minIndent == Integer.MAX_VALUE ? 0 : minIndent;
    }

    private static String dedent(String[] lines, boolean[] blank, int minIndent) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (!blank[i]) {
                out.append(stripTrailingHorizontal(lines[i].substring(minIndent)));
            }
        }
        return out.toString();
    }

    private static String findEscapeError(String body, boolean multiline, boolean allowUnfinishedAtEof) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != '\\') {
                continue;
            }
            if (i + 1 >= body.length()) {
                if (allowUnfinishedAtEof) {
                    return null;
                }
                return "unknown escape sequence : \\";
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'b', 't', 'n', 'f', 'r', '"', '\'', '\\', '0' -> {
                }
                case 's', '\n' -> {
                    if (!multiline) {
                        return "unknown escape sequence : \\" + (next == '\n' ? "newline" : next);
                    }
                }
                case 'u' -> {
                    int available = body.length() - 1 - i;
                    int check = Math.min(4, available);
                    for (int k = 1; k <= check; k++) {
                        if (!isHexDigit(body.charAt(i + k))) {
                            return "unknown escape sequence : \\u" + unicodeSnippet(body, i + 1);
                        }
                    }
                    if (available < 4) {
                        if (allowUnfinishedAtEof) {
                            return null;
                        }
                        return "unknown escape sequence : \\u" + unicodeSnippet(body, i + 1);
                    }
                    i += 4;
                }
                default -> {
                    return "unknown escape sequence : \\" + next;
                }
            }
        }
        return null;
    }

    private static String translateEscapes(String body, boolean multiline) {
        if (body.indexOf('\\') < 0) {
            return body;
        }
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= body.length()) {
                throw new IllegalArgumentException("Unknown escape sequence : \\");
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'b' -> out.append('\b');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'f' -> out.append('\f');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case '\\' -> out.append('\\');
                case '0' -> out.append('\0');
                case 's' -> {
                    if (!multiline) {
                        throw new IllegalArgumentException("Unknown escape sequence : \\s");
                    }
                    out.append(' ');
                }
                case '\n' -> {
                    if (!multiline) {
                        throw new IllegalArgumentException("Unknown escape sequence : \\newline");
                    }
                    // Line continuation suppresses the newline.
                }
                case 'u' -> {
                    if (i + 4 >= body.length()) {
                        throw new IllegalArgumentException("Unknown escape sequence : \\u" + unicodeSnippet(body, i + 1));
                    }
                    for (int k = 1; k <= 4; k++) {
                        if (!isHexDigit(body.charAt(i + k))) {
                            throw new IllegalArgumentException("Unknown escape sequence : \\u" + unicodeSnippet(body, i + 1));
                        }
                    }
                    out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Unknown escape sequence : \\" + next);
            }
        }
        return out.toString();
    }

    private static String unicodeSnippet(String s, int start) {
        int end = Math.min(s.length(), start + 4);
        StringBuilder sb = new StringBuilder(4);
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    private static boolean isHorizontalWhitespaceOnly(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String normalizeNewlines(String s) {
        if (s.indexOf('\r') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                out.append('\n');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
