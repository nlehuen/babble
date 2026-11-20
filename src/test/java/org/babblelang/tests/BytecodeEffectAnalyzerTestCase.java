package org.babblelang.tests;

import org.babblelang.analysis.BytecodeEffectAnalyzer;
import org.babblelang.analysis.EffectSet;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Tests for RM007 (BIP007-P3) : opt-in ASM {@link BytecodeEffectAnalyzer}.
 */
public class BytecodeEffectAnalyzerTestCase {

    public static final class SampleHelpers {
        public static final int CONST_OFFSET = 10;
        public static int mutableCounter = 0;

        public static int pureAdd(int a, int b) {
            return a + b + CONST_OFFSET;
        }

        public static double trigHypot(double a, double b) {
            return Math.sqrt(a * a + b * b);
        }

        public static int intDiv(int a, int b) {
            return a / b;
        }

        public static int readsMutableStatic(int x) {
            return x + mutableCounter;
        }

        public static int writesMutableStatic(int x) {
            mutableCounter = x;
            return x;
        }

        public static synchronized int syncAdd(int a, int b) {
            return a + b;
        }

        public static int openDispatch(Object o) {
            return o.hashCode();
        }

        public static int loopSum(int n) {
            int s = 0;
            for (int i = 0; i < n; i++) {
                s += i;
            }
            return s;
        }

        public static int callsHelper(int a, int b) {
            return pureAdd(a, b) * 2;
        }
    }

    @Test
    public void testBytecodeAnalysisClassifiesClosedHelpers() throws Exception {
        Method pureAdd = SampleHelpers.class.getMethod("pureAdd", int.class, int.class);
        BytecodeEffectAnalyzer.Result rAdd = BytecodeEffectAnalyzer.analyze(pureAdd);
        Assertions.assertTrue(rAdd.effects().isPure());
        Assertions.assertTrue(rAdd.effects().isDeterministic());
        Assertions.assertTrue(rAdd.effects().isNothrow());
        Assertions.assertTrue(rAdd.effects().terminating());
        String suggestion = BytecodeEffectAnalyzer.suggestAxiom(pureAdd);
        Assertions.assertNotNull(suggestion);
        Assertions.assertTrue(suggestion.startsWith("axiom "), suggestion);
        Assertions.assertTrue(suggestion.contains("pure"), suggestion);
        Assertions.assertFalse(suggestion.contains("deterministic"), suggestion);
        Assertions.assertTrue(suggestion.contains("nothrow"), suggestion);

        // Seeded Math.sqrt intrinsic + transitive pure helper call
        EffectSet rTrig = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("trigHypot", double.class, double.class)).effects();
        Assertions.assertTrue(rTrig.isPure());
        Assertions.assertTrue(rTrig.isDeterministic());
        Assertions.assertTrue(rTrig.isNothrow());

        EffectSet rTrans = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("callsHelper", int.class, int.class)).effects();
        Assertions.assertTrue(rTrans.isPure());
        Assertions.assertTrue(rTrans.isDeterministic());
        Assertions.assertTrue(rTrans.isNothrow());

        // Integer division drops nothrow but stays pure deterministic
        EffectSet rDiv = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("intDiv", int.class, int.class)).effects();
        Assertions.assertTrue(rDiv.isPure());
        Assertions.assertTrue(rDiv.isDeterministic());
        Assertions.assertFalse(rDiv.isNothrow());

        // Reading non-final static drops deterministic
        EffectSet rRead = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("readsMutableStatic", int.class)).effects();
        Assertions.assertTrue(rRead.isPure());
        Assertions.assertFalse(rRead.isDeterministic());

        // Writing static field drops pure, hermetic, deterministic
        EffectSet rWrite = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("writesMutableStatic", int.class)).effects();
        Assertions.assertFalse(rWrite.isPure());
        Assertions.assertFalse(rWrite.isHermetic());
        Assertions.assertFalse(rWrite.isDeterministic());

        // Synchronized drops pure/deterministic/nothrow
        EffectSet rSync = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("syncAdd", int.class, int.class)).effects();
        Assertions.assertFalse(rSync.isPure());
        Assertions.assertFalse(rSync.isDeterministic());

        // Open virtual dispatch drops pure/deterministic/nothrow
        BytecodeEffectAnalyzer.Result rOpen = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("openDispatch", Object.class));
        Assertions.assertFalse(rOpen.effects().isPure());
        Assertions.assertTrue(rOpen.reasons().stream().anyMatch(s -> s.contains("open dispatch")));

        // Loop drops @TERMINATING while staying pure deterministic nothrow
        EffectSet rLoop = BytecodeEffectAnalyzer.analyze(SampleHelpers.class.getMethod("loopSum", int.class)).effects();
        Assertions.assertTrue(rLoop.isPure());
        Assertions.assertTrue(rLoop.isDeterministic());
        Assertions.assertTrue(rLoop.isNothrow());
        Assertions.assertFalse(rLoop.terminating());
    }

    @Test
    public void testOptInEngineIntegration() throws Exception {
        String script = """
                def H = import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers
                def f = fn nothrow (a: int, b: int): int -> ( H.pureAdd(a, b) )
                f(3, 4)
                """;
        BabbleScriptEngineFactory factory = new BabbleScriptEngineFactory();

        // Off by default -> rejected because H.pureAdd is unannotated!
        BabbleScriptEngine defaultEngine = (BabbleScriptEngine) factory.getScriptEngine();
        Assertions.assertFalse(defaultEngine.isBytecodeEffectAnalysisEnabled());
        Assertions.assertThrows(Exception.class, () -> defaultEngine.eval(script));

        // Opt-in enabled -> succeeds in both interpret and compile modes!
        BabbleScriptEngine optIn = (BabbleScriptEngine) factory.getScriptEngine();
        optIn.enableBytecodeEffectAnalysis(true);
        Assertions.assertEquals(17, optIn.eval(script));
        optIn.put("babble.mode", "compile");
        Assertions.assertEquals(17, optIn.eval(script));
    }
}
