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

        // Open virtual dispatch without curated axiom drops pure/deterministic/nothrow;
        // with curated Object.hashCode it resolves through the curated axiom to pure volatile (BIP019 §5.2).
        BytecodeEffectAnalyzer.Result rOpenEmpty = BytecodeEffectAnalyzer.analyze(
                SampleHelpers.class.getMethod("openDispatch", Object.class),
                org.babblelang.analysis.BuiltinAxioms.EMPTY);
        Assertions.assertFalse(rOpenEmpty.effects().isPure());
        Assertions.assertTrue(rOpenEmpty.reasons().stream().anyMatch(s -> s.contains("open dispatch")));
        BytecodeEffectAnalyzer.Result rOpenCurated = BytecodeEffectAnalyzer.analyze(
                SampleHelpers.class.getMethod("openDispatch", Object.class),
                org.babblelang.analysis.BuiltinAxioms.standard());
        Assertions.assertTrue(rOpenCurated.effects().isPure());
        Assertions.assertFalse(rOpenCurated.effects().isDeterministic());

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

        // On by default (BIP019 §5.1) -> succeeds automatically!
        BabbleScriptEngine defaultEngine = (BabbleScriptEngine) factory.getScriptEngine();
        Assertions.assertTrue(defaultEngine.isBytecodeEffectAnalysisEnabled());
        Assertions.assertEquals(17, defaultEngine.eval(script));
        defaultEngine.put("babble.mode", "compile");
        Assertions.assertEquals(17, defaultEngine.eval(script));

        // Explicitly disabled -> rejected because H.pureAdd is unannotated!
        BabbleScriptEngine disabled = (BabbleScriptEngine) factory.getScriptEngine();
        disabled.enableBytecodeEffectAnalysis(false);
        Assertions.assertFalse(disabled.isBytecodeEffectAnalysisEnabled());
        Assertions.assertThrows(Exception.class, () -> disabled.eval(script));
    }

    public static final class StatefulBox {
        public int value;
        public Object ref;

        public StatefulBox(int v) {
            this.value = v;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int v) {
            this.value = v;
        }

        public void storeRef(Object o) {
            this.ref = o;
        }

        public StatefulBox freshCopy() {
            return new StatefulBox(this.value);
        }
    }

    @Test
    public void testValueLatticeInfersMutBorrowReadsAndNullness() throws Exception {
        BytecodeEffectAnalyzer.Result getRes = BytecodeEffectAnalyzer.analyze(StatefulBox.class.getMethod("getValue"));
        Assertions.assertTrue(getRes.effects().isPure());
        Assertions.assertTrue(getRes.effects().isDeterministic());
        Assertions.assertTrue(getRes.effects().isNothrow());
        Assertions.assertTrue(getRes.readsReceiver());
        Assertions.assertFalse(getRes.mutReceiver());
        Assertions.assertTrue(getRes.borrowReceiver());

        BytecodeEffectAnalyzer.Result setRes = BytecodeEffectAnalyzer.analyze(StatefulBox.class.getMethod("setValue", int.class));
        Assertions.assertTrue(setRes.effects().isHermetic());
        Assertions.assertFalse(setRes.effects().isPure());
        Assertions.assertTrue(setRes.mutReceiver());
        Assertions.assertTrue(setRes.borrowReceiver());

        BytecodeEffectAnalyzer.Result storeRes = BytecodeEffectAnalyzer.analyze(StatefulBox.class.getMethod("storeRef", Object.class));
        Assertions.assertTrue(storeRes.effects().isHermetic());
        Assertions.assertTrue(storeRes.mutReceiver());
        Assertions.assertFalse(storeRes.borrowParams()[0]); // param stored in field -> shared!

        BytecodeEffectAnalyzer.Result copyRes = BytecodeEffectAnalyzer.analyze(StatefulBox.class.getMethod("freshCopy"));
        Assertions.assertTrue(copyRes.effects().isPure());
        Assertions.assertTrue(copyRes.returnNonNull());
        Assertions.assertTrue(copyRes.fresh());
        Assertions.assertTrue(copyRes.readsReceiver());
    }

    @Test
    public void testAxiomDumperProducesCanonicalOutput() {
        String dump = org.babblelang.analysis.AxiomDumper.dumpClass(StatefulBox.class, org.babblelang.analysis.BuiltinAxioms.EMPTY);
        Assertions.assertTrue(dump.contains("axiom org.babblelang.tests.BytecodeEffectAnalyzerTestCase.StatefulBox: class ("), dump);
        Assertions.assertTrue(dump.contains("def getValue: fn nothrow (): int"), dump);
        Assertions.assertTrue(dump.contains("def setValue: fn mut nothrow shallow (p0: int): void"), dump);
        Assertions.assertTrue(dump.contains("def storeRef: fn mut nothrow shallow (shared p0: any?): void"), dump);
        Assertions.assertTrue(dump.contains("def freshCopy: fn nothrow fresh ():"), dump);
        // Output parses back cleanly and obeys §3.4 canonical order:
        Assertions.assertEquals(java.util.List.of(), org.babblelang.analysis.BuiltinAxioms.checkCanonicalOrder(dump, "StatefulBox"));
    }

    @Test
    public void testGoldenDumpsForMathAndArrayList() throws Exception {
        String mathDump = org.babblelang.analysis.AxiomDumper.dumpClass(Math.class, org.babblelang.analysis.BuiltinAxioms.standard());
        Assertions.assertTrue(mathDump.startsWith("axiom java.lang.Math: class (\n) static (\n"), mathDump);
        Assertions.assertTrue(mathDump.contains("def abs: fn nothrow idempotent foldable (p0: double): double"), mathDump);
        Assertions.assertTrue(mathDump.contains("def sqrt: fn nothrow foldable (p0: double): double"), mathDump);
        Assertions.assertTrue(mathDump.contains("def addExact: fn (p0: int, p1: int): int"), mathDump);
        Assertions.assertTrue(mathDump.contains("def ceilDiv: fn (p0: int, p1: int): int"), mathDump);
        Assertions.assertEquals(java.util.List.of(), org.babblelang.analysis.BuiltinAxioms.checkCanonicalOrder(mathDump, "Math"));

        String listDump = org.babblelang.analysis.AxiomDumper.dumpClass(java.util.ArrayList.class, org.babblelang.analysis.BuiltinAxioms.standard());
        Assertions.assertTrue(listDump.startsWith("axiom java.util.ArrayList of E: class nullmarked (\n"), listDump);
        Assertions.assertTrue(listDump.contains("def clear: fn mut shared shallow (): void"), listDump);
        Assertions.assertTrue(listDump.contains("def isEmpty: fn nothrow (): bool"), listDump);
        Assertions.assertTrue(listDump.contains("def iterator: fn shared nothrow view (): java.util.Iterator of any?"), listDump);
        Assertions.assertEquals(java.util.List.of(), org.babblelang.analysis.BuiltinAxioms.checkCanonicalOrder(listDump, "ArrayList"));
    }

    @Test
    public void testDumpAxiomsAndReportSwitches() throws Exception {
        String oldDump = System.getProperty("babble.dumpAxioms");
        String oldReport = System.getProperty("babble.axioms.report");
        java.io.PrintStream oldErr = System.err;
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        try {
            System.setProperty("babble.dumpAxioms", "org.babblelang.tests.BytecodeEffectAnalyzerTestCase$StatefulBox");
            System.setProperty("babble.axioms.report", "true");
            System.setErr(new java.io.PrintStream(errBytes));

            org.babblelang.analysis.BuiltinAxioms custom = org.babblelang.analysis.BuiltinAxioms.fromSource(
                    "axiom org.babblelang.tests.BytecodeEffectAnalyzerTestCase.StatefulBox: class (\n"
                            + "    def getValue: fn (): int\n"
                            + ")\n", "test.ba");
            BabbleScriptEngine engine = (BabbleScriptEngine) new BabbleScriptEngineFactory().getScriptEngine();
            engine.setBuiltinAxioms(custom);
            engine.put("babble.mode", "compile");
            Object res = engine.eval("""
                    def B = import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.StatefulBox
                    def f = fn (): int -> ( B(42).getValue() )
                    f()
                    """);
            Assertions.assertEquals(42, res);
            String err = errBytes.toString();
            Assertions.assertTrue(err.contains("axiom org.babblelang.tests.BytecodeEffectAnalyzerTestCase.StatefulBox: class"), err);
            Assertions.assertTrue(err.contains("Axiom report :"), err);
        } finally {
            System.setErr(oldErr);
            if (oldDump == null) System.clearProperty("babble.dumpAxioms"); else System.setProperty("babble.dumpAxioms", oldDump);
            if (oldReport == null) System.clearProperty("babble.axioms.report"); else System.setProperty("babble.axioms.report", oldReport);
        }
    }

    @Test
    public void testJavaBaseSimulationCompletesDeterministically() throws Exception {
        // BIP019 §2.2 / §5.1: analyzing core java.base classes with BytecodeEffectAnalyzer is deterministic,
        // bounded, and never throws across repeated runs in different orders.
        java.util.List<Class<?>> classes = java.util.List.of(
                Math.class, StrictMath.class, Integer.class, Long.class, Double.class, Float.class,
                String.class, java.util.Objects.class, java.util.ArrayList.class, java.util.HashMap.class);
        java.util.Map<String, String> firstPass = new java.util.LinkedHashMap<>();
        for (Class<?> c : classes) {
            firstPass.put(c.getName(), org.babblelang.analysis.AxiomDumper.dumpClass(c, org.babblelang.analysis.BuiltinAxioms.standard()));
        }
        // Reverse order must produce byte-for-byte identical dumps (determinism §5.1).
        for (int i = classes.size() - 1; i >= 0; i--) {
            Class<?> c = classes.get(i);
            String second = org.babblelang.analysis.AxiomDumper.dumpClass(c, org.babblelang.analysis.BuiltinAxioms.standard());
            Assertions.assertEquals(firstPass.get(c.getName()), second, "Non-deterministic dump for " + c.getName());
        }
    }
}
