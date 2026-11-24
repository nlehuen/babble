package org.babblelang.format;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import javax.script.ScriptException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Babble's code formatter, behind the {@code babblefmt} command.
 * <p>
 * It keeps the line breaks of the source, and settles everything else : indentation (four
 * spaces a level, from the nesting of parentheses, plus one level for a line continuing an
 * expression), the spaces between tokens, blank lines (one at most, none at the edges of a
 * block), semicolons (only between two expressions of one line), trailing spaces, and the
 * alignment of comments ending consecutive lines. A block's opening parenthesis is brought
 * back on the line of what it belongs to, as are {@code else} and {@code static}.
 * <p>
 * It works on the tokens, comments included, and reads in the parse tree what each one is :
 * a call's parenthesis or a block's, a newline separating expressions or continuing one.
 * The result is parsed again, and must give the same tree.
 */
public final class Formatter {
    private static final int INDENT = 4;
    /** The least space between code and the comment ending its line. */
    private static final int BEFORE_COMMENT = 2;

    private static final int OPEN = BabbleLexer.LPAREN;
    private static final int CLOSE = BabbleLexer.RPAREN;

    /** What a newline does. The first is the default, for a text with no tree : only comments. */
    private static final byte SEPARATES = 0;
    /** Continues an expression : the next line is indented one level more. */
    private static final byte CONTINUES = 1;
    /** Continues a list between parentheses, which indent it already. */
    private static final byte IN_LIST = 2;
    /** Before a block's parenthesis, {@code else} or {@code static} : not kept. */
    private static final byte JOINS = 3;

    private final List<Token> tokens;
    private final byte[] newlines;
    /** The semicolons to keep : those between two expressions of one line. */
    private final boolean[] keptSemicolons;
    private final boolean[] callParentheses;
    /** The "-" of a negation, which sticks to its operand : {@code -x}. */
    private final boolean[] unaryMinus;
    /** The "..." / "?..." of a variadic parameter, which sticks to its type : {@code T...}. */
    private final boolean[] restEllipsis;
    /** The "..." of a spread call argument, which sticks to its operand : {@code ...e}. */
    private final boolean[] spreadEllipsis;
    /** For each "(", the token whose line gives the indentation of what it encloses. */
    private final int[] anchors;

    private final List<Line> lines = new ArrayList<>();
    private final Line[] lineOf;
    /** The indentation of the lines holding the anchors of the open parentheses. */
    private final Deque<Integer> open = new ArrayDeque<>();

    private static final class Line {
        final List<Token> items = new ArrayList<>();
        final boolean continuation;
        int indent;

        Line(boolean continuation) {
            this.continuation = continuation;
        }

        boolean blank() {
            return items.isEmpty();
        }

        /** The comment ending the line, after some code. */
        Token trailingComment() {
            Token last = items.get(items.size() - 1);
            return items.size() > 1 && last.getType() == BabbleLexer.COMMENT ? last : null;
        }
    }

    private Formatter(List<Token> tokens, BabbleParser.FileContext file) {
        this.tokens = tokens;
        newlines = new byte[tokens.size()];
        keptSemicolons = new boolean[tokens.size()];
        callParentheses = new boolean[tokens.size()];
        unaryMinus = new boolean[tokens.size()];
        restEllipsis = new boolean[tokens.size()];
        spreadEllipsis = new boolean[tokens.size()];
        anchors = new int[tokens.size()];
        lineOf = new Line[tokens.size()];
        if (file != null) {
            annotate(file);
        }
    }

    public static String format(String source) throws ScriptException {
        return format(source, "<input>");
    }

    /**
     * @param sourceName the name of the text in error messages
     * @throws ScriptException       on a lexical or syntax error : only a text that parses is formatted
     * @throws IllegalStateException if the result does not parse as the source does, which is a bug
     *                               of the formatter : the source is then best left as it is
     */
    public static String format(String source, String sourceName) throws ScriptException {
        Lexed lexed = lex(source);
        BabbleParser.FileContext file;
        try {
            file = BabbleScriptEngine.parseSource(source, sourceName);
        } catch (ScriptException e) {
            if (!lexed.valid || lexed.code) {
                throw e;
            }
            // Comments and nothing else : not a program, but something to format.
            file = null;
        }
        String result = new Formatter(lexed.tokens, file).run();
        check(source, file, result, sourceName);
        return result;
    }

    private record Lexed(List<Token> tokens, boolean valid, boolean code, int comments) {
    }

    /** All the tokens of a text, comments included. */
    private static Lexed lex(String source) {
        BabbleLexer lexer = new BabbleLexer(CharStreams.fromString(source));
        boolean[] failed = {false};
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                failed[0] = true;
            }
        });
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        boolean code = false;
        int comments = 0;
        for (Token token : stream.getTokens()) {
            if (isComment(token)) {
                comments++;
            } else {
                code |= token.getType() != BabbleLexer.NL && token.getType() != BabbleLexer.SEMICOLON && token.getType() != Token.EOF;
            }
        }
        return new Lexed(stream.getTokens(), !failed[0], code, comments);
    }

    private static boolean isComment(Token token) {
        return token.getType() == BabbleLexer.COMMENT || token.getType() == BabbleLexer.MULTILINECOMMENT;
    }

    // ---- What the tree says of the tokens

    private void annotate(ParseTree node) {
        if (node instanceof TerminalNode terminal) {
            Token token = terminal.getSymbol();
            int index = token.getTokenIndex();
            ParseTree parent = terminal.getParent();
            if (token.getType() == BabbleLexer.NL) {
                newlines[index] = newline(parent);
            } else if (token.getType() == BabbleLexer.MINUS) {
                unaryMinus[index] = parent instanceof BabbleParser.NegateContext;
            } else if (token.getType() == BabbleLexer.ELLIPSIS || token.getType() == BabbleLexer.Q_ELLIPSIS) {
                restEllipsis[index] = (parent instanceof BabbleParser.FnParamContext fp && fp.rest == token)
                        || (parent instanceof BabbleParser.FnTypeParamContext ftp && ftp.rest == token);
                spreadEllipsis[index] = parent instanceof BabbleParser.CallParameterContext cp && cp.spread == token;
            } else if (token.getType() == OPEN) {
                boolean isTypeApplyCall = parent instanceof BabbleParser.CallParametersContext
                        && parent.getParent() instanceof BabbleParser.CallContext call
                        && call.expression() instanceof BabbleParser.TypeApplyContext;
                callParentheses[index] = (parent instanceof BabbleParser.CallParametersContext && !isTypeApplyCall)
                        || parent instanceof BabbleParser.SuperExpressionContext;
                // What a block of an "if", a function, a class, a try/catch/finally... encloses is indented from
                // the line where that expression starts, wherever its parenthesis is.
                anchors[index] = parent instanceof BabbleParser.BlockContext block
                        && !(block.getParent() instanceof BabbleParser.BlockExpressionContext)
                        ? (block.getParent() instanceof BabbleParser.CatchClauseContext cc
                                ? cc.getParent().getStart().getTokenIndex()
                                : block.getParent().getStart().getTokenIndex())
                        : parent instanceof BabbleParser.AxiomTypeContext axType
                        ? axType.getParent().getStart().getTokenIndex()
                        : (parent instanceof BabbleParser.OverloadExpressionContext
                                || parent instanceof BabbleParser.OverloadTypeContext)
                        ? ((ParserRuleContext) parent).getStart().getTokenIndex() : index;
            }
            return;
        }
        if (node instanceof BabbleParser.SepContext sep) {
            ParserRuleContext parent = sep.getParent();
            int position = parent.children.indexOf(sep);
            boolean first = position == 0 || parent instanceof BabbleParser.BlockContext && position == 1;
            // Followed by the end of the file, by the block's ")", or at the end of axiomMembers.
            boolean last = parent instanceof BabbleParser.AxiomMembersContext
                    ? position == parent.getChildCount() - 1
                    : position == parent.getChildCount() - 2;
            if (sep.NL().isEmpty() && !first && !last) {
                keptSemicolons[((TerminalNode) sep.getChild(0)).getSymbol().getTokenIndex()] = true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            annotate(node.getChild(i));
        }
    }

    private static byte newline(ParseTree parent) {
        if (parent instanceof BabbleParser.SepContext) {
            return SEPARATES;
        }
        if (parent instanceof BabbleParser.ClassParametersContext || parent instanceof BabbleParser.FnParametersContext
                || parent instanceof BabbleParser.FnTypeParametersContext || parent instanceof BabbleParser.CallParametersContext
                || parent instanceof BabbleParser.SuperExpressionContext || parent instanceof BabbleParser.ParenTypeContext
                || parent instanceof BabbleParser.SimpleTypeContext
                || parent instanceof BabbleParser.TypeParametersContext || parent instanceof BabbleParser.TypeArgumentsContext
                || parent instanceof BabbleParser.CallingClauseContext
                || parent instanceof BabbleParser.OverloadExpressionContext
                || parent instanceof BabbleParser.OverloadTypeContext) {
            return IN_LIST;
        }
        // In these, a newline can only come before a block, "else", "static", "catch" or "finally".
        if (parent instanceof BabbleParser.IfExprContext
                || parent instanceof BabbleParser.WhileExpressionContext || parent instanceof BabbleParser.ForExpressionContext
                || parent instanceof BabbleParser.TryExpressionContext || parent instanceof BabbleParser.CatchClauseContext
                || parent instanceof BabbleParser.ClassExpressionContext
                || parent instanceof BabbleParser.InterfaceExpressionContext || parent instanceof BabbleParser.ObjectExpressionContext
                || parent instanceof BabbleParser.FunctionLiteralContext
                || parent instanceof BabbleParser.AxiomTypeContext) {
            return JOINS;
        }
        return CONTINUES;
    }

    /** Whether a comment stands between the tokens a newline separates : it then keeps its lines. */
    private boolean commentAround(int newline) {
        for (int step = -1; step <= 1; step += 2) {
            for (int i = newline + step; i >= 0 && i < tokens.size(); i += step) {
                Token token = tokens.get(i);
                if (isComment(token)) {
                    return true;
                }
                if (token.getType() != BabbleLexer.NL) {
                    break;
                }
            }
        }
        return false;
    }

    // ---- Lines

    private String run() {
        Line current = new Line(false);
        for (Token token : tokens) {
            int index = token.getTokenIndex();
            switch (token.getType()) {
                case Token.EOF -> {
                }
                case BabbleLexer.NL -> {
                    byte kind = newlines[index];
                    if (kind == JOINS) {
                        if (!commentAround(index)) {
                            continue;
                        }
                        kind = IN_LIST;
                    }
                    finish(current);
                    current = new Line(kind == CONTINUES);
                }
                case BabbleLexer.SEMICOLON -> {
                    if (keptSemicolons[index]) {
                        current.items.add(token);
                    }
                }
                default -> {
                    current.items.add(token);
                    lineOf[index] = current;
                }
            }
        }
        finish(current);
        return render();
    }

    /** Indents a line, from the parentheses open where it starts. */
    private void finish(Line line) {
        lines.add(line);
        if (line.blank()) {
            return;
        }
        int next = 0;
        // A line starting with ")" goes back to the line of what the parenthesis belongs to.
        while (next < line.items.size() && line.items.get(next).getType() == CLOSE) {
            line.indent = open.pop();
            next++;
        }
        if (next == 0) {
            line.indent = (open.isEmpty() ? 0 : open.peek() + 1) + (line.continuation ? 1 : 0);
        }
        for (; next < line.items.size(); next++) {
            Token token = line.items.get(next);
            if (token.getType() == OPEN) {
                open.push(lineOf[anchors[token.getTokenIndex()]].indent);
            } else if (token.getType() == CLOSE) {
                open.pop();
            }
        }
    }

    private String render() {
        List<Line> kept = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.blank() && !keepsBlank(kept, i)) {
                continue;
            }
            kept.add(line);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < kept.size(); ) {
            // Comments ending consecutive lines are aligned.
            int end = i;
            int width = 0;
            List<String> codes = new ArrayList<>();
            while (end < kept.size() && !kept.get(end).blank() && kept.get(end).trailingComment() != null) {
                String code = code(kept.get(end));
                if (code.indexOf('\n') >= 0) {
                    break;
                }
                codes.add(code);
                width = Math.max(width, code.length());
                end++;
            }
            if (end == i) {
                Line line = kept.get(i);
                if (!line.blank()) {
                    result.append(code(line));
                    if (line.trailingComment() != null) {
                        result.append(" ".repeat(BEFORE_COMMENT)).append(line.trailingComment().getText().stripTrailing());
                    }
                }
                result.append('\n');
                i++;
                continue;
            }
            for (; i < end; i++) {
                String code = codes.get(codes.size() - (end - i));
                result.append(code).append(" ".repeat(width - code.length() + BEFORE_COMMENT))
                        .append(kept.get(i).trailingComment().getText().stripTrailing()).append('\n');
            }
        }
        return result.toString();
    }

    /** One blank line at most, and none at the edges of the file, after a "(" or before a ")". */
    private boolean keepsBlank(List<Line> kept, int index) {
        if (kept.isEmpty()) {
            return false;
        }
        Line previous = kept.get(kept.size() - 1);
        if (previous.blank() || previous.items.get(previous.items.size() - 1).getType() == OPEN) {
            return false;
        }
        for (int i = index + 1; i < lines.size(); i++) {
            Line next = lines.get(i);
            if (!next.blank()) {
                return next.items.get(0).getType() != CLOSE;
            }
        }
        return false;
    }

    /** A line without the comment ending it. */
    private String code(Line line) {
        StringBuilder code = new StringBuilder(" ".repeat(line.indent * INDENT));
        int count = line.trailingComment() != null ? line.items.size() - 1 : line.items.size();
        for (int i = 0; i < count; i++) {
            Token token = line.items.get(i);
            if (i > 0 && space(line.items.get(i - 1), token)) {
                code.append(' ');
            }
            if (token.getType() == BabbleLexer.MULTILINECOMMENT) {
                code.append(i == 0 ? moved(token, line.indent * INDENT) : stripped(token.getText().split("\\R", -1), 0));
            } else if (token.getType() == BabbleLexer.MULTILINE_STRING
                    || token.getType() == BabbleLexer.RAW_MULTILINE_STRING) {
                code.append(formatMultilineString(token, (line.indent + 1) * INDENT));
            } else if (token.getType() == BabbleLexer.COMMENT) {
                code.append(token.getText().stripTrailing());
            } else {
                code.append(token.getText());
            }
        }
        return code.toString();
    }

    private boolean space(Token before, Token token) {
        if (isComment(before) || isComment(token)) {
            return true;
        }
        int type = token.getType();
        if (before.getType() == OPEN || type == CLOSE || type == BabbleLexer.COMMA || type == BabbleLexer.SEMICOLON
                || (type == BabbleLexer.COLON && before.getType() != BabbleLexer.CATCH && before.getType() != BabbleLexer.ELLIPSIS)
                || type == BabbleLexer.DOT || before.getType() == BabbleLexer.DOT
                || type == BabbleLexer.SAFE_DOT || before.getType() == BabbleLexer.SAFE_DOT
                || type == BabbleLexer.QUESTION || type == BabbleLexer.BANG
                || restEllipsis[token.getTokenIndex()] || spreadEllipsis[before.getTokenIndex()]
                || before.getType() == BabbleLexer.MINUS && unaryMinus[before.getTokenIndex()] && type != BabbleLexer.MINUS) {
            return false;
        }
        return type != OPEN || !callParentheses[token.getTokenIndex()];
    }

    private static String formatMultilineString(Token token, int targetIndent) {
        org.babblelang.parser.StringLiterals.MultilineLayout layout = org.babblelang.parser.StringLiterals.layout(token);
        List<String> lines = layout.lines();
        boolean[] blank = layout.blank();
        int minIndent = layout.minIndent();
        String indentPrefix = layout.extraIndentHasTabs()
                ? lines.get(lines.size() - 1).substring(0, minIndent)
                : " ".repeat(targetIndent);
        StringBuilder out = new StringBuilder(layout.openingDelimiter());
        for (int i = 0; i < lines.size(); i++) {
            out.append('\n');
            if (!blank[i]) {
                out.append(indentPrefix)
                        .append(org.babblelang.parser.StringLiterals.stripTrailingHorizontal(lines.get(i).substring(minIndent)));
            }
            if (i == lines.size() - 1) {
                out.append("\"\"\"");
            }
        }
        return out.toString();
    }

    /** A comment of several lines starting a line : its other lines move as its first one does. */
    private static String moved(Token comment, int column) {
        int start = comment.getStartIndex();
        int inLine = comment.getCharPositionInLine();
        String before = inLine == 0 ? "" : comment.getInputStream().getText(Interval.of(start - inLine, start - 1));
        return stripped(comment.getText().split("\\R", -1), column - width(before, before.length()));
    }

    private static String stripped(String[] commentLines, int shift) {
        StringBuilder result = new StringBuilder(commentLines[0].stripTrailing());
        for (int i = 1; i < commentLines.length; i++) {
            String line = commentLines[i].stripTrailing();
            result.append('\n');
            if (!line.isEmpty()) {
                int margin = 0;
                while (line.charAt(margin) == ' ' || line.charAt(margin) == '\t') {
                    margin++;
                }
                result.append(" ".repeat(Math.max(0, width(line, margin) + shift))).append(line, margin, line.length());
            }
        }
        return result.toString();
    }

    /** The width of the start of a line, where a tab counts for a level of indentation. */
    private static int width(String line, int length) {
        int width = 0;
        for (int i = 0; i < length; i++) {
            width += line.charAt(i) == '\t' ? INDENT : 1;
        }
        return width;
    }

    // ---- The result means what the source does

    private static void check(String source, BabbleParser.FileContext file, String result, String sourceName) {
        Lexed lexed = lex(result);
        boolean same = lexed.valid && lexed.comments == lex(source).comments;
        if (same && file == null) {
            same = !lexed.code;
        } else if (same) {
            try {
                same = shape(BabbleScriptEngine.parseSource(result, sourceName)).equals(shape(file));
            } catch (ScriptException e) {
                same = false;
            }
        }
        if (!same) {
            throw new IllegalStateException("babblefmt would change what " + sourceName + " means : this is a bug of babblefmt");
        }
    }

    /** A parse tree without what the formatter may change : newlines and semicolons. */
    private static String shape(ParseTree tree) {
        StringBuilder shape = new StringBuilder();
        shape(tree, shape);
        return shape.toString();
    }

    private static void shape(ParseTree node, StringBuilder shape) {
        if (node instanceof TerminalNode terminal) {
            int type = terminal.getSymbol().getType();
            if (type != BabbleLexer.NL && type != BabbleLexer.SEMICOLON) {
                String text = (type == BabbleLexer.MULTILINE_STRING || type == BabbleLexer.RAW_MULTILINE_STRING)
                        ? org.babblelang.parser.StringLiterals.decode(terminal.getSymbol())
                        : terminal.getText();
                shape.append(type).append(':').append(text).append('\u0000');
            }
        } else if (!(node instanceof BabbleParser.SepContext)) {
            shape.append('<').append(node.getClass().getSimpleName()).append('\u0000');
            for (int i = 0; i < node.getChildCount(); i++) {
                shape(node.getChild(i), shape);
            }
            shape.append('>');
        }
    }
}
