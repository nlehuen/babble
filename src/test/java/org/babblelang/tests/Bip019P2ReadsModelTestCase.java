package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code BIP019-I2} (reads of mutable state, {@code
 * docs/design/bip019_refactoring-axioms.md} §4).
 */
public class Bip019P2ReadsModelTestCase extends BabbleTestBase {

    @Test
    public void testReadOfMutableParamComposesAndStaysDeterministicOnOwnedOrFrozen()
            throws Exception {
        String script =
                """
                def Counter = class (def n: int) (
                    def get = fn (): int -> (this.n)
                )
                def readCounter = fn (c: Counter): int -> (c.n)
                def readViaHelper = fn (c: Counter): int -> (readCounter(c) + c.get())
                def onLocal = fn (): int -> (
                    def c = Counter(21)
                    readViaHelper(c)
                )
                def onFrozen = fn (c: frozen Counter): int -> (readCounter(c))
                def f = freeze(Counter(10))
                onLocal() + onFrozen(f)
                """;
        Assertions.assertEquals(52, interpret(script));
    }

    @Test
    public void testReadOfMutableGlobalBecomesVolatile() throws Exception {
        String bad =
                """
                def Counter = class (def n: int) ()
                def globalC = Counter(5)
                def readCounter = fn (c: Counter): int -> (c.n)
                def f = fn (): int -> (readCounter(globalC))
                f()
                """;
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException ex =
                Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(bad));
        Assertions.assertTrue(
                ex.getMessage().contains("violates deterministic guarantee"), ex.getMessage());

        String good =
                """
                def Counter = class (def n: int) ()
                def globalC = Counter(5)
                def readCounter = fn (c: Counter): int -> (c.n)
                def f = fn volatile (): int -> (readCounter(globalC))
                f()
                """;
        Assertions.assertEquals(5, interpret(good));
    }

    @Test
    public void testRecordReadIsAlwaysDeterministic() throws Exception {
        String script =
                """
                def Point = record (x: int, y: int) ()
                def origin = Point(3, 4)
                def sum = fn (): int -> (origin.x + origin.y)
                sum()
                """;
        Assertions.assertEquals(7, interpret(script));
    }

    @Test
    public void testTrackedResultsKeepHigherOrderCompositionDeterministic() throws Exception {
        String script =
                """
                def Box = class (def v: int) ()
                def apply = fn (f: fn (b: Box): Box, b: Box): int -> (
                    f(b).v
                )
                def wrap = fn (b: Box): Box -> (Box(b.v + 1))
                def run = fn (): int -> (
                    def b = Box(41)
                    apply(wrap, b)
                )
                run()
                """;
        Assertions.assertEquals(42, interpret(script));
    }

    @Test
    public void testListFromCarriesElementProvenance() {
        String bad =
                """
                def Item = class (def v: int) ()
                def Holder = class (shared items: list of Item) ()
                def leak = fn (xs: list of Item): Holder -> (
                    def copy = list(from: xs)
                    Holder(copy)
                )
                """;
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException ex =
                Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(bad));
        Assertions.assertTrue(
                ex.getMessage().contains("mark parameter as shared xs"), ex.getMessage());
    }

    @Test
    public void testForLoopOverMutableCollectionComposesReads() throws Exception {
        String script =
                """
                def sumList = fn (xs: list of int): int -> (
                    var total = 0
                    for x in xs then (
                        total += x
                    )
                    total
                )
                def run = fn (): int -> (
                    def xs = list(1, 2, 3, 4)
                    sumList(xs)
                )
                run()
                """;
        Assertions.assertEquals(10, interpret(script));
    }
}
