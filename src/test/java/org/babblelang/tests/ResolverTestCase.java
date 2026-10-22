package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.Resolution;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Golden tests for the resolver (compiler phase 2) : for each use of a name, in the
 * order of the parse tree (an assignment {@code x=} before the uses in its value), how the
 * compiled code reaches it.
 * <ul>
 * <li>{@code LOCAL} : a JVM local of the method the use is compiled into ;</li>
 * <li>{@code CELL} : the same, captured by a nested method, so it lives in a cell ;</li>
 * <li>{@code CAPTURED} : a cell captured from an enclosing method ;</li>
 * <li>{@code DYNAMIC} : looked up at runtime in a namespace (file, package, globals).</li>
 * </ul>
 */
public class ResolverTestCase {
    private static List<String> bindings(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        BabbleParser.FileContext file = parser.file();
        Resolution resolution = BlockScopes.analyze(file).resolution();
        List<String> result = new ArrayList<>();
        collect(file, resolution, result);
        return result;
    }

    private static void collect(ParseTree tree, Resolution resolution, List<String> result) {
        String name = null;
        if (tree instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            name = selector.ID().getText();
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null) {
            name = assign.name.getText() + "=";
        } else if (tree instanceof BabbleParser.ThisExpressionContext) {
            name = "this";
        } else if (tree instanceof BabbleParser.RecurseContext) {
            name = "recurse";
        }
        if (name != null) {
            ParserRuleContext ctx = (ParserRuleContext) tree;
            result.add(name + " " + kind(ctx, resolution.variable(ctx)));
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collect(tree.getChild(i), resolution, result);
        }
    }

    private static String kind(ParserRuleContext use, Resolution.Variable variable) {
        if (variable == null) {
            return "DYNAMIC";
        }
        if (variable.method() != method(use)) {
            return "CAPTURED";
        }
        return variable.isCell() ? "CELL" : "LOCAL";
    }

    /** The method a use is compiled into : the innermost function literal, class or object body, or the file. */
    private static ParserRuleContext method(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext || p instanceof BabbleParser.ClassExpressionContext
                    && isInClassBody(ctx, (BabbleParser.ClassExpressionContext) p)
                    || p instanceof BabbleParser.ObjectExpressionContext || p instanceof BabbleParser.FileContext) {
                return p;
            }
        }
        throw new IllegalStateException();
    }

    /** A class's implements list and static block are evaluated where the class expression is. */
    private static boolean isInClassBody(ParserRuleContext ctx, BabbleParser.ClassExpressionContext c) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            if (p == c.classBlock || p == c.parametersDeclaration()) {
                return true;
            }
            if (p == c.staticBlock) {
                return false;
            }
        }
        return false;
    }

    @Test
    public void testFunctionLocalsAndGlobals() {
        Assertions.assertEquals(List.of("i LOCAL", "n LOCAL", "s= LOCAL", "s LOCAL", "i LOCAL", "i= LOCAL", "i LOCAL", "s LOCAL", "loop DYNAMIC", "println DYNAMIC"),
                bindings("def loop = (n) -> ( def i = 0 ; def s = 0 ; while i < n then ( s = s + i ; i = i + 1 ) ; s ) ; loop(3) ; println(1)"));
    }

    @Test
    public void testCapturedVariablesAreCells() {
        Assertions.assertEquals(List.of("c= CAPTURED", "c CAPTURED", "counter DYNAMIC"),
                bindings("def counter = () -> ( def c = 0 ; () -> ( c = c + 1 ) ) ; counter()"));
        // Through two levels : the middle function carries the cell.
        Assertions.assertEquals(List.of("a CAPTURED", "b LOCAL"),
                bindings("def f = (a) -> ( () -> ( (b) -> ( a + b ) ) )"));
    }

    @Test
    public void testThisAndRecurse() {
        // this in a method is its own ; a nested function captures it.
        Assertions.assertEquals(List.of("this LOCAL", "this CAPTURED"),
                bindings("def C = class () ( def v = 1 ; def m = () -> ( this.v ) ; def n = () -> ( () -> ( this.v ) ) )"));
        Assertions.assertEquals(List.of("n LOCAL", "n LOCAL", "recurse LOCAL", "n LOCAL", "recurse DYNAMIC"),
                bindings("def f = (n) -> ( if n < 2 then ( n ) else ( recurse(n - 1) ) ) ; recurse"));
    }

    @Test
    public void testConstructorParametersUsedByMethodsAreCells() {
        // Read by the constructor itself, the parameter is a cell there, since a method captures it.
        Assertions.assertEquals(List.of("start= CAPTURED", "start CAPTURED", "start CELL"),
                bindings("def C = class (start) ( def next = () -> ( start = start + 1 ) ; def first = start )"));
    }

    @Test
    public void testFileAndPackageNamesAreDynamic() {
        Assertions.assertEquals(List.of("x DYNAMIC", "x DYNAMIC", "y DYNAMIC"),
                bindings("def x = 1 ; package p ( def y = x ; def f = () -> ( x + y ) )"));
    }

    @Test
    public void testAPackageInAFunctionKeepsItsNamespace() {
        // The function keeps a namespace for the package to live in, and its names stay dynamic.
        Assertions.assertEquals(List.of("a DYNAMIC", "p DYNAMIC"),
                bindings("def f = () -> ( def a = 1 ; package p ( def b = a ) ; p )"));
    }

    @Test
    public void testParametersAreNotVisibleToEarlierDefaults() {
        Assertions.assertEquals(List.of("c DYNAMIC", "a LOCAL", "b LOCAL"),
                bindings("def f = (a, b = c, c = a) -> ( b )"));
    }
}
