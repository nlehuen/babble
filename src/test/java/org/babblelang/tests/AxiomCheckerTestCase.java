package org.babblelang.tests;

import org.babblelang.analysis.AxiomChecker;
import org.babblelang.effects.Effect;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

/**
 * Tests for RM007 (BIP007-I3) : {@code babble.checkAxioms} property-based axiom fuzzing
 * ({@link AxiomChecker}).
 */
public class AxiomCheckerTestCase {

    private static BabbleScriptEngine newEngine(String mode) {
        BabbleScriptEngine engine = (BabbleScriptEngine) new BabbleScriptEngineFactory().getScriptEngine();
        engine.put("babble.mode", mode);
        return engine;
    }

    @Test
    public void testValidAxiomsPassAcrossScalarsCollectionsAndRecords() throws Exception {
        String valid = """
                def Pt = record (x: int, y: int) ()
                def add = fn axiom pure nothrow commutative associative (a: int, b: int): int -> ( a + b )
                def norm = fn axiom pure nothrow idempotent (s: string): string -> ( s.trim() )
                def mono = fn axiom pure nothrow monotone injective (x: int): int -> ( x * 2 + 1 )
                def sumPt = fn axiom pure nothrow (p: Pt): int -> ( p.x + p.y )
                def listSize = fn axiom pure nothrow (xs: list of int): int -> ( xs.size() )
                def mapSize = fn axiom pure nothrow (m: map of (string, int)): int -> ( m.size() )
                def entryKey = fn axiom pure nothrow (e: entry of (string, int)): string -> ( e.getKey() )
                def allScalars = fn axiom pure nothrow (
                    b: byte, s: short, i: int, l: long, f: float, d: double, ok: bool, c: char, str: string
                ): int -> ( i )
                """;
        for (String mode : new String[]{"interpret", "compile"}) {
            BabbleScriptEngine engine = newEngine(mode);
            AxiomChecker.Report report = engine.checkAxioms(valid);
            Assertions.assertTrue(report.ok(), mode + " failures: " + report.failures());
            Assertions.assertEquals(8, report.checkedFunctions(), mode);
            Assertions.assertTrue(report.checkedProperties() >= 18, mode + " props=" + report.checkedProperties());
            Assertions.assertEquals(0, report.skipped().size(), mode + " skipped=" + report.skipped());

            // Also verify `babble.checkAxioms=true` engine attribute succeeds seamlessly
            engine.put(BabbleScriptEngine.CHECK_AXIOMS, true);
            engine.eval(valid);
        }
    }

    @Test
    public void testInvalidAxiomsCaughtWithCounterexamples() throws Exception {
        // Non-commutative subtraction declared `axiom commutative`
        BabbleScriptEngine engine = newEngine("compile");
        AxiomChecker.Report rComm = engine.checkAxioms("""
                def badSub = fn axiom pure nothrow commutative (a: int, b: int): int -> ( a - b )
                """);
        Assertions.assertFalse(rComm.ok());
        Assertions.assertEquals(Effect.COMMUTATIVE, rComm.failures().get(0).axiom());

        // Non-associative subtraction declared `axiom associative`
        AxiomChecker.Report rAssoc = engine.checkAxioms("""
                def badAssoc = fn axiom pure nothrow associative (a: int, b: int): int -> ( a - b )
                """);
        Assertions.assertFalse(rAssoc.ok());
        Assertions.assertEquals(Effect.ASSOCIATIVE, rAssoc.failures().get(0).axiom());

        // Non-idempotent increment declared `axiom idempotent`
        AxiomChecker.Report rIdem = engine.checkAxioms("""
                def badIdem = fn axiom pure nothrow idempotent (x: int): int -> ( x + 1 )
                """);
        Assertions.assertFalse(rIdem.ok());
        Assertions.assertEquals(Effect.IDEMPOTENT, rIdem.failures().get(0).axiom());

        // Non-monotone negation declared `axiom monotone`
        AxiomChecker.Report rMono = engine.checkAxioms("""
                def badMono = fn axiom pure nothrow monotone (x: int): int -> ( -x )
                """);
        Assertions.assertFalse(rMono.ok());
        Assertions.assertEquals(Effect.MONOTONE, rMono.failures().get(0).axiom());

        // Non-injective square declared `axiom injective`
        AxiomChecker.Report rInj = engine.checkAxioms("""
                def badInj = fn axiom pure nothrow injective (x: int): int -> ( x * x )
                """);
        Assertions.assertFalse(rInj.ok());
        Assertions.assertEquals(Effect.INJECTIVE, rInj.failures().get(0).axiom());

        // Non-deterministic counter declared `axiom deterministic`
        AxiomChecker.Report rDet = engine.checkAxioms("""
                var counter = 0
                def badDet = fn axiom pure (x: int): int -> (
                    counter += 1
                    x + counter
                )
                """);
        Assertions.assertFalse(rDet.ok());
        Assertions.assertEquals(Effect.DETERMINISTIC, rDet.failures().get(0).axiom());

        // Throwing function declared `axiom nothrow`
        AxiomChecker.Report rNothrow = engine.checkAxioms("""
                def badNothrow = fn axiom pure nothrow (x: int): int -> (
                    assert(x > 1000)
                    x
                )
                """);
        Assertions.assertFalse(rNothrow.ok());
        Assertions.assertEquals(Effect.NOTHROW, rNothrow.failures().get(0).axiom());

        // `babble.checkAxioms=true` engine attribute fails `eval(...)` on axiom violation!
        engine.put(BabbleScriptEngine.CHECK_AXIOMS, true);
        ScriptException ex = Assertions.assertThrows(ScriptException.class, () -> engine.eval("""
                def bad = fn axiom pure nothrow commutative (a: int, b: int): int -> ( a - b )
                """));
        Assertions.assertTrue(ex.getMessage().contains("Axiom violation in bad (commutative)"), ex.getMessage());
    }

    @Test
    public void testUnsupportedTypesSkipAndCustomGeneratorWorks() throws Exception {
        BabbleScriptEngine engine = newEngine("interpret");
        String script = """
                def Box = class (def x: int) ()
                def f = fn axiom pure nothrow (b: Box): int -> ( b.x )
                """;
        AxiomChecker.Report skippedReport = engine.checkAxioms(script);
        Assertions.assertTrue(skippedReport.ok());
        Assertions.assertEquals(0, skippedReport.checkedFunctions());
        Assertions.assertEquals(1, skippedReport.skipped().size());
        Assertions.assertTrue(skippedReport.skipped().get(0).reason().contains("Box"));

        // Registering a custom generator for a Java type works too
        AxiomChecker custom = new AxiomChecker().registerGenerator("java.lang.StringBuilder",
                rng -> new StringBuilder("s" + rng.nextInt(10)));
        AxiomChecker.Report customReport = engine.checkAxioms("""
                def sbLen = fn axiom pure nothrow (sb: java.lang.StringBuilder): int -> ( sb.length() )
                """, custom);
        Assertions.assertTrue(customReport.ok());
        Assertions.assertEquals(1, customReport.checkedFunctions());
    }
}
