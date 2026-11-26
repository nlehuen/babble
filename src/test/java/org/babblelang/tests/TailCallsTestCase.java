package org.babblelang.tests;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Golden tests of the self tail calls the analysis marks (docs/design/bip024_tail-calls.md, {@code
 * BIP024-D1}–{@code D4}) : the text of each marked call, in the order of the parse tree. Both
 * backends optimize exactly these.
 */
public class TailCallsTestCase {
    private static List<String> tailCalls(String source) {
        BabbleParser parser =
                new BabbleParser(
                        new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        BabbleParser.FileContext file = parser.file();
        ScopeAnalysis analysis = BlockScopes.analyze(file);
        Assertions.assertEquals(
                List.of(),
                analysis.typeDiagnostics().stream().map(Object::toString).toList(),
                source);
        List<String> result = new ArrayList<>();
        collect(file, analysis, result);
        return result;
    }

    private static void collect(ParseTree tree, ScopeAnalysis analysis, List<String> result) {
        if (tree instanceof BabbleParser.CallContext call
                && analysis.calls().isSelfTailCall(call)) {
            result.add(call.getText());
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collect(tree.getChild(i), analysis, result);
        }
    }

    @Test
    public void testTailPositions() {
        Assertions.assertEquals(
                List.of(
                        "recurse(n-1)",
                        "recurse(n-2)",
                        "recurse(n-3)",
                        "recurse(n-4)",
                        "recurse(n-5)",
                        "recurse(n-6)"),
                tailCalls(
                        "def f = fn io (n : int) : int -> (\n"
                                + "  if n > 9 then ( return recurse(n - 1) )\n"
                                + "  while n > 8 then ( return recurse(n - 2) )\n"
                                + "  for x in list.of(1) then ( if n > 7 then ( return recurse(n - 3) ) )\n"
                                + "  if n > 6 then ( (recurse(n - 4)) ) else if n > 5 then ( forever recurse(n - 5) ) else if n > 0 then ( recurse(n - 6) ) else ( 0 )\n"
                                + ")"));
    }

    @Test
    public void testNotTailPositions() {
        Assertions.assertEquals(
                List.of(),
                tailCalls(
                        "def f = fn (n : int) : int -> (\n"
                                + "  assert(n > -9)\n"
                                + "  def y = if n > 9 then ( recurse(n - 1) ) else ( 0 )\n"
                                + "  recurse(n - 2)\n"
                                + "  def z = if n > 8 then ( return recurse(n - 3) ) else ( 1 )\n"
                                + "  if n > 7 then ( 1 + recurse(n - 4) ) else ( recurse(n - 5) as int )\n"
                                + ")\n"
                                + "def g = fn (n : int) : bool -> ( n > 0 and g(n - 1) )\n"
                                + "def h = fn (n : int) : int -> ( while n > 0 then ( break h(n - 1) ) ; 0 )"));
    }

    @Test
    public void testSelfCallsOnly() {
        Assertions.assertEquals(
                List.of("byName(n-1)", "this.walk(n-1)", "recurse(n-1)"),
                tailCalls(
                        "def byName = fn (n : int) : int -> ( if n > 0 then ( byName(n - 1) ) else ( 0 ) )\n"
                                // Mutable var : not statically known to be the function itself.
                                + "var moved = fn volatile (n : int) : int -> ( if n > 0 then ( moved(n - 1) ) else ( 0 ) )\n"
                                + "moved = fn (n : int) : int -> ( 0 )\n"
                                + "def outer = fn (n : int) : int -> ( def inner = fn (m : int) : int -> ( outer(m) ) ; inner(n) )\n"
                                + "def W = class () (\n"
                                + "  def walk = fn (n : int) : int -> ( if n > 0 then ( this.walk(n - 1) ) else ( this.other(n) ) )\n"
                                + "  def other = fn (n : int) : int -> ( n )\n"
                                + ")\n"
                                + "def I = interface ( def m = fn (n : int) : int -> ( if n > 0 then ( this.m(n - 1) ) else ( 0 ) ) )\n"
                                + "def k = fn (n : int) : int -> ( if n > 0 then ( recurse(n - 1) ) else ( 0 ) )"));
    }

    @Test
    public void testRestParametersAndSpreadArguments() {
        // BIP021 rest parameters and spread arguments are not optimized (BIP024-D4).
        Assertions.assertEquals(
                List.of(),
                tailCalls(
                        "def r = fn (n : int, xs : int...) : int -> ( if n > 0 then ( recurse(n - 1, ...xs) ) else ( 0 ) )\n"
                                + "def s = fn (n : int, xs : int...) : int -> ( if n > 0 then ( s(n - 1) ) else ( 0 ) )"));
    }

    @Test
    public void testWidenedSiblingBranch() {
        // The int branch widens to double ; the call's value, already the function's double, goes
        // unchanged.
        Assertions.assertEquals(
                List.of("recurse(n-1)"),
                tailCalls(
                        "def f = fn (n : int) : double -> ( if n == 0 then ( 1 ) else ( recurse(n - 1) ) )"));
    }
}
