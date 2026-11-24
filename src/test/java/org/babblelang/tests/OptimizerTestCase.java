package org.babblelang.tests;

import org.antlr.v4.runtime.CharStreams;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.compiler.Babblec;
import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.ir.hir.HirBuilder;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPrinter;
import org.babblelang.ir.hir.HirVerifier;
import org.babblelang.opt.PassContext;
import org.babblelang.opt.PassManager;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.CompiledScript;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Soundness, golden-IR, and 3-way parity tests for the {@code BIP014} / {@code BIP027-I2} HIR
 * optimizer ({@code HirEGraphSimplifyPass}, {@code InlinePass}, {@code HirDcePass},
 * {@code HirLicmPass}, and {@code babblec} AOT cross-module inlining).
 */
public class OptimizerTestCase {

    private static BabbleScriptEngine newEngine() {
        return (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
    }

    private static HirModule optimizeHir(String source, int optLevel) throws Exception {
        var lexer = new org.babblelang.parser.BabbleLexer(CharStreams.fromString(source, "<test>"));
        var parser = new BabbleParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
        BabbleParser.FileContext file = parser.file();
        Assertions.assertEquals(0, parser.getNumberOfSyntaxErrors(), "Syntax error in test snippet");
        ScopeAnalysis scopes = BlockScopes.analyze(file);
        HirModule hir = HirBuilder.build("org/babblelang/generated/OptTest", "<test>", file, scopes, Map.of(), Map.of());
        HirVerifier.verify(hir);
        PassContext ctx = new PassContext(optLevel, true, null, false, Map.of());
        HirModule out = PassManager.defaultFor(optLevel).optimize(hir, ctx);
        HirVerifier.verify(out);
        return out;
    }

    private static Object evalInMode(String source, String mode, int optLevel) throws Exception {
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if ("compile".equals(mode)) {
            engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        }
        CompiledScript script = engine.compile(source);
        if (script instanceof CompiledBabbleScript cbs) {
            for (byte[] bytes : cbs.getClasses().values()) {
                StringWriter sw = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(bytes), cbs.getScriptClass().getClassLoader(), false, new PrintWriter(sw));
                Assertions.assertEquals("", sw.toString(), "Bytecode verification failed:\n" + sw);
            }
        }
        return script.eval(new SimpleBindings());
    }

    private static Object assertThreeWayEqual(String source) throws Exception {
        Object interp = evalInMode(source, "interpret", 0);
        Object o0 = evalInMode(source, "compile", 0);
        Object o1 = evalInMode(source, "compile", 1);
        Assertions.assertEquals(interp, o0, "interpret vs -O0 mismatch for:\n" + source);
        Assertions.assertEquals(interp, o1, "interpret vs -O1 mismatch for:\n" + source);
        return o1;
    }

    // ------------------------------------------------------------------ O1 : Constant Folding (HirEGraphSimplifyPass)

    @Test
    public void testConstFoldArithmeticStringsBranchesAndWhitelistedJdk() throws Exception {
        String src = ""
                + "axiom java.lang.Math.sqrt: fn pure nothrow\n"
                + "def arith = fn () : int -> ( def k = 10 ; k * k + 2 * 3 )\n"
                + "def str = fn () : string -> ( \"  hi \" + \"there  \" )\n"
                + "def trimmed = fn () : string -> ( \"  babble  \".trim() )\n"
                + "def root = fn () : double -> ( java.lang.Math.sqrt(9.0) )\n"
                + "def branch = fn () : int -> ( if 2 < 3 then ( 42 ) else ( 99 ) )\n"
                + "def deadLoop = fn () : int -> ( def x = 7 ; while false then ( x = 99 ) ; x )\n"
                + "\"\" + arith() + \":\" + str() + \":\" + trimmed() + \":\" + root() + \":\" + branch() + \":\" + deadLoop()";

        Assertions.assertEquals("106:  hi there  :babble:3.0:42:7", assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        Assertions.assertTrue(ir.contains("106"), ir);
        Assertions.assertTrue(ir.contains("\"babble\""), ir);
        Assertions.assertTrue(ir.contains("3.0"), ir);
        Assertions.assertFalse(ir.contains("Math.sqrt"), "Math.sqrt(9.0) should fold:\n" + ir);
        Assertions.assertFalse(ir.contains("(while "), "while false should fold away:\n" + ir);
    }

    @Test
    public void testConstFoldDoesNotFoldDivisionByZero() throws Exception {
        String src = "def boom = fn io () : int -> (\n  java.lang.Math.floorDiv(1, 0)\n)\nboom()";
        ScriptException interp = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "interpret", 0));
        ScriptException o0 = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "compile", 0));
        ScriptException o1 = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "compile", 1));
        Assertions.assertEquals(interp.getLineNumber(), o0.getLineNumber());
        Assertions.assertEquals(interp.getLineNumber(), o1.getLineNumber());
        Assertions.assertEquals(2, o1.getLineNumber());
    }

    // ------------------------------------------------------------------ O2 & O3 : E-Graph Algebraic Simplify (HirEGraphSimplifyPass)

    @Test
    public void testAlgebraicIdentitiesReassociationAndIdempotentCollapse() throws Exception {
        String src = ""
                + "axiom java.lang.Math.abs: fn pure nothrow\n"
                + "def clamp = fn axiom pure nothrow idempotent (x : int) : int -> ( if x < 0 then ( 0 ) else ( x ) )\n"
                + "def f = fn (x : int, b : bool, s : string) : string -> (\n"
                + "  def a = (x + 0) * 1 + (x * 0) + (x - x)\n"
                + "  def c = (x + 1) + 2\n"
                + "  def d = not (not b)\n"
                + "  def e = clamp(clamp(x))\n"
                + "  def g = java.lang.Math.abs(java.lang.Math.abs(x))\n"
                + "  def t = s.trim().trim()\n"
                + "  \"\" + a + \":\" + c + \":\" + d + \":\" + e + \":\" + g + \":\" + t\n"
                + ")\n"
                + "f(-5, true, \"  ok  \")";

        Assertions.assertEquals("-5:-2:true:0:5:ok", assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        int fStart = ir.indexOf("[fn$1$f]");
        Assertions.assertTrue(fStart >= 0, ir);
        String fIr = ir.substring(fStart);
        // Only a single Math.abs and a single String.trim call should remain inside f.
        int firstAbs = fIr.indexOf("Math.abs");
        Assertions.assertTrue(firstAbs >= 0, fIr);
        Assertions.assertEquals(-1, fIr.indexOf("Math.abs", firstAbs + 1), "Math.abs(Math.abs(x)) should collapse:\n" + fIr);

        int firstTrim = fIr.indexOf("String.trim");
        Assertions.assertTrue(firstTrim >= 0, fIr);
        Assertions.assertEquals(-1, fIr.indexOf("String.trim", firstTrim + 1), "s.trim().trim() should collapse:\n" + fIr);
    }

    @Test
    public void testAlgebraicStrictlyPreservesIeee754FloatingPointSemantics() throws Exception {
        String src = ""
                + "def nan = java.lang.Double.NaN\n"
                + "def inf = java.lang.Double.POSITIVE_INFINITY\n"
                + "def negZero = -0.0\n"
                + "def f = fn io (x : double) : string -> (\n"
                + "  def m0 = x * 0.0\n"
                + "  def p0 = x + 0.0\n"
                + "  def sub = x - x\n"
                + "  def re = (x + 1.0) + 2.0\n"
                + "  \"\" + java.lang.Double.isNaN(m0) + \":\" + java.lang.Double.doubleToLongBits(p0) + \":\" + java.lang.Double.isNaN(sub) + \":\" + re\n"
                + ")\n"
                + "f(nan) + \"|\" + f(negZero) + \"|\" + f(inf)";

        Assertions.assertEquals(
                "true:9221120237041090560:true:NaN|false:0:false:3.0|true:9218868437227405312:true:Infinity",
                assertThreeWayEqual(src));

        // Verify double reassociation did NOT collapse (x + 1.0) + 2.0 into x + 3.0.
        String ir = HirPrinter.print(optimizeHir(src, 1));
        Assertions.assertFalse(ir.contains("3.0:double"), "IEEE-754 double reassociation must be forbidden:\n" + ir);
    }

    // ------------------------------------------------------------------ O8 : InlinePass

    @Test
    public void testBip024SelfTailCallsAreLoweredAtEveryLevelAndNeverInlined() throws Exception {
        String src = ""
                + "def count = fn (n : int, acc : int) : int -> ( if n == 0 then ( acc ) else ( recurse(n - 1, acc + 1) ) )\n"
                + "def caller = fn (n : int) : int -> ( count(n, 0) )\n"
                + "caller(1000000)";

        Assertions.assertEquals(1000000, assertThreeWayEqual(src));

        for (int level : new int[] {0, 1}) {
            String ir = HirPrinter.print(optimizeHir(src, level));
            int countStart = ir.indexOf("[fn$0$count]");
            int callerStart = ir.indexOf("[fn$1$caller]");
            Assertions.assertTrue(countStart >= 0 && callerStart > countStart, ir);
            // A self tail call is part of the language (BIP024-D2) : lowered at -O0 already.
            Assertions.assertTrue(ir.substring(countStart, callerStart).contains("(tail-call #1 "), "-O" + level + ":\n" + ir);
            // Its jump only makes sense in its own method : the small count is never inlined into caller.
            Assertions.assertTrue(ir.substring(callerStart).contains("(call-fn #1 "), "-O" + level + ":\n" + ir);
        }
    }

    @Test
    public void testIntraModuleInliningAndConstFoldFixedPoint() throws Exception {
        String src = ""
                + "def sq = fn (x : int) : int -> ( x * x )\n"
                + "def sumSq = fn (a : int, b : int) : int -> ( sq(a) + sq(b) )\n"
                + "def constFolded = fn () : int -> ( sq(5) )\n"
                + "\"\" + sumSq(3, 4) + \":\" + constFolded()";

        Assertions.assertEquals("25:25", assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        // Inside sumSq and constFolded, direct calls to sq should be inlined away.
        int sumSqStart = ir.indexOf("[fn$1$sumSq]");
        int constStart = ir.indexOf("[fn$2$constFolded]");
        Assertions.assertTrue(sumSqStart >= 0 && constStart > sumSqStart, ir);
        String sumSqIr = ir.substring(sumSqStart, constStart);
        Assertions.assertFalse(sumSqIr.contains("(call-fn "), "sq(a) + sq(b) should inline:\n" + sumSqIr);

        String constIr = ir.substring(constStart);
        Assertions.assertFalse(constIr.contains("(call-fn "), "sq(5) should inline & fold:\n" + constIr);
        Assertions.assertTrue(constIr.contains("25:int"), "sq(5) should fold to 25:\n" + constIr);
    }

    @Test
    public void testInlinedErrorPreservesCalleeLineNumber() throws Exception {
        // BIP014-D5: throwing inside an inlined helper must report the helper's exact source line (line 2).
        String src = ""
                + "def toInt = fn (v : any?) : int -> (\n"
                + "  v as int\n"
                + ")\n"
                + "def caller = fn (v : any?) : int -> (\n"
                + "  toInt(v)\n"
                + ")\n"
                + "caller(null)";

        ScriptException interp = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "interpret", 0));
        ScriptException o0 = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "compile", 0));
        ScriptException o1 = Assertions.assertThrows(ScriptException.class, () -> evalInMode(src, "compile", 1));
        Assertions.assertEquals(2, interp.getLineNumber());
        Assertions.assertEquals(2, o0.getLineNumber());
        Assertions.assertEquals(2, o1.getLineNumber());
    }

    @Test
    public void testRecursiveFunctionIsNotInfinitelyInlined() throws Exception {
        String src = ""
                + "def fib = fn (n : int) : int -> ( if n < 2 then ( n ) else ( recurse(n - 1) + recurse(n - 2) ) )\n"
                + "fib(10)";
        Assertions.assertEquals(55, assertThreeWayEqual(src));
    }

    @Test
    public void testCrossModuleSmallExportInliningOnlyInBabblecAot(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("math.ba"),
                "export def sq = fn (x : int) : int -> ( x * x )\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.ba"),
                "def m = import(\"math.ba\")\n"
                        + "def calc = fn (n : int) : int -> ( m.sq(n) + 1 )\n"
                        + "println(calc(7))\n",
                StandardCharsets.UTF_8);

        Path jarO0 = dir.resolve("app-o0.jar");
        Path jarO1 = dir.resolve("app-o1.jar");
        Babblec.compile(dir.resolve("main.ba"), dir, jarO0, new javax.script.SimpleScriptContext(), 0);
        Babblec.compile(dir.resolve("main.ba"), dir, jarO1, new javax.script.SimpleScriptContext(), 1);

        // Both jars must print 50.
        Assertions.assertEquals("50", runJar(jarO0));
        Assertions.assertEquals("50", runJar(jarO1));

        // Inspect main class bytecode in jarO1: calc's body should inline m.sq(n) (no invoke call to sq).
        List<String> o1Calls = findCallsInMethod(jarO1, "fn$0$calc$spec");
        Assertions.assertTrue(o1Calls.stream().noneMatch(c -> c.contains("sq") || c.contains("apply")),
                "m.sq(n) should be inlined in AOT -O1 jar, found calls: " + o1Calls);
    }

    // ------------------------------------------------------------------ O4 : HirDcePass

    @Test
    public void testDceEliminatesUnreachableAndUnusedPureStatementsButPreservesSideEffects() throws Exception {
        String src = ""
                + "def log = java.lang.StringBuilder()\n"
                + "def side = fn io () : int -> ( log.append(\"X\") ; 1 )\n"
                + "def f = fn io (x : int) : int -> (\n"
                + "  def unused = x * 2 + 1\n"
                + "  discard(x + 99)\n"
                + "  side()\n"
                + "  return x + 10\n"
                + "  side()\n"
                + ")\n"
                + "def r = f(5)\n"
                + "\"\" + r + \":\" + log.toString()";

        Assertions.assertEquals("15:X", assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        int fStart = ir.indexOf("[fn$1$f]");
        Assertions.assertTrue(fStart >= 0, ir);
        String fIr = ir.substring(fStart);
        Assertions.assertFalse(fIr.contains("unused"), "Unused pure local def should be removed:\n" + fIr);
        Assertions.assertFalse(fIr.contains("99:int"), "Pure discard expression should be removed:\n" + fIr);
        // Only ONE call to side() ((call-fn #1)) should remain (the unreachable one after return must be gone).
        int firstSide = fIr.indexOf("(call-fn #1)");
        Assertions.assertTrue(firstSide >= 0, fIr);
        Assertions.assertEquals(-1, fIr.indexOf("(call-fn #1)", firstSide + 1),
                "Unreachable side() after return should be removed:\n" + fIr);
    }

    @Test
    public void testDceEliminatesScpTerminatingCallsAndPreservesNonTerminatingOnes() throws Exception {
        String src = ""
                + "def fib = fn (n : int) : int -> ( if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) ) )\n"
                + "def sumList = fn (xs : list of int) : int -> (\n"
                + "  def total = 0\n"
                + "  for x in xs then ( total += x )\n"
                + "  total\n"
                + ")\n"
                + "def unguarded = fn (n : int) : int -> ( if n == 0 then ( 0 ) else ( unguarded(n - 1) ) )\n"
                + "def caller = fn (n : int, xs : list of int) : int -> (\n"
                + "  def deadFib = fib(n)\n"
                + "  def deadSum = sumList(xs)\n"
                + "  def maybeInf = unguarded(0)\n"
                + "  n + 1\n"
                + ")\n"
                + "caller(6, list.of(1, 2, 3))";

        Assertions.assertEquals(7, assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        int callerStart = ir.indexOf("[fn$3$caller]");
        Assertions.assertTrue(callerStart >= 0, ir);
        String callerIr = ir.substring(callerStart);
        Assertions.assertFalse(callerIr.contains("deadFib"), "SCP-certified fib(n) dead local should be eliminated:\n" + callerIr);
        Assertions.assertFalse(callerIr.contains("deadSum"), "Finite-collection sumList(xs) dead local should be eliminated:\n" + callerIr);
        Assertions.assertTrue(callerIr.contains("maybeInf"), "Non-terminating unguarded(0) dead local must be preserved:\n" + callerIr);
    }

    // ------------------------------------------------------------------ O6 : HirLicmPass

    @Test
    public void testLicmHoistsPureNothrowInvariantCallsOutOfWhileAndForLoops() throws Exception {
        String src = ""
                + "axiom java.lang.Math.sin: fn pure nothrow\n"
                + "axiom java.lang.Math.cos: fn pure nothrow\n"
                + "def heavy = fn (x : double) : double -> ( java.lang.Math.sin(x) * java.lang.Math.cos(x) )\n"
                + "def loopWhile = fn (n : int, a : double) : double -> (\n"
                + "  def i = 0\n"
                + "  def acc = 0.0\n"
                + "  while i < n then (\n"
                + "    acc = acc + heavy(a)\n"
                + "    i += 1\n"
                + "  )\n"
                + "  acc\n"
                + ")\n"
                + "def loopFor = fn (xs : list of int, a : double) : double -> (\n"
                + "  def acc = 0.0\n"
                + "  for x in xs then (\n"
                + "    acc = acc + heavy(a) + double(x)\n"
                + "  )\n"
                + "  acc\n"
                + ")\n"
                + "\"\" + java.lang.Math.round(loopWhile(10, 0.5) * 1000.0) + \":\" + java.lang.Math.round(loopFor(list.of(1, 2, 3), 0.5) * 1000.0)";

        assertThreeWayEqual(src);

        String ir = HirPrinter.print(optimizeHir(src, 1));
        int lw = ir.indexOf("[fn$1$loopWhile]");
        int lf = ir.indexOf("[fn$2$loopFor]");
        Assertions.assertTrue(lw >= 0 && lf > lw, ir);
        Assertions.assertTrue(ir.substring(lw, lf).contains("$licm"),
                "Loop-invariant heavy(a) should be hoisted into $licm preheader in while loop:\n" + ir.substring(lw, lf));
        Assertions.assertTrue(ir.substring(lf).contains("$licm"),
                "Loop-invariant heavy(a) should be hoisted into $licm preheader in for loop:\n" + ir.substring(lf));
    }

    @Test
    public void testLicmDoesNotHoistImpureVolatileOrThrowingExpressions() throws Exception {
        // 1. Throwing expression inside a 0-iteration loop MUST NOT be hoisted!
        String zeroIterThrow = ""
                + "def f = fn io (n : int, d : int) : int -> (\n"
                + "  def i = 0\n"
                + "  def s = 0\n"
                + "  while i < n then (\n"
                + "    s = s + java.lang.Math.floorDiv(10, d)\n"
                + "    i += 1\n"
                + "  )\n"
                + "  s\n"
                + ")\n"
                + "f(0, 0)";
        Assertions.assertEquals(0, assertThreeWayEqual(zeroIterThrow));

        // 2. Impure / mutating counter inside a loop MUST execute N times.
        String impureLoop = ""
                + "def count = 0\n"
                + "def tick = fn io () : int -> ( count = count + 1 ; count )\n"
                + "def run = fn io (n : int) : int -> (\n"
                + "  def i = 0\n"
                + "  def s = 0\n"
                + "  while i < n then (\n"
                + "    s = s + tick()\n"
                + "    i += 1\n"
                + "  )\n"
                + "  s\n"
                + ")\n"
                + "run(5)";
        Assertions.assertEquals(15, assertThreeWayEqual(impureLoop));
    }

    // ------------------------------------------------------------------ Dump IR & Plumbing

    @Test
    @ResourceLock(Resources.GLOBAL)
    public void testDumpIrEmitsPreAndPostHir() throws Exception {
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        engine.put(BabbleCompiler.DUMP_IR, "hir");

        PrintStream origErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CompiledScript cs = engine.compile("def x = 1 + 2 ; x");
            Assertions.assertEquals(3, cs.eval(new SimpleBindings()));
        } finally {
            System.setErr(origErr);
        }
        String errText = captured.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(errText.contains("hir-before"), errText);
        Assertions.assertTrue(errText.contains("hir-after"), errText);
    }

    @Test
    public void testInlineResolvedOverloadAlternative() throws Exception {
        String source = "def f = overload (\n"
                + "  fn (x : int) : int -> ( x * 2 + 1 ),\n"
                + "  fn (s : string) : int -> ( s.length() )\n"
                + ")\n"
                + "def calc = fn (n : int) : int -> ( f(n) )\n"
                + "calc(20)";
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledScript cs = engine.compile(source);
        Assertions.assertEquals(41, cs.eval(new SimpleBindings()));
        byte[] bytes = ((org.babblelang.compiler.CompiledBabbleScript) cs).getClassBytes();
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode calc = cn.methods.stream().filter(m -> m.name.equals("fn$2$calc$spec")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (var insn = calc.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                calls.add(mi.owner + "." + mi.name);
            }
        }
        Assertions.assertTrue(calls.stream().noneMatch(c -> c.endsWith("$spec")),
                "Expected resolved overload alternative to be inlined into calc$spec, got calls: " + calls);
    }

    @Test
    public void testBip028TryCatchOptimizerPasses() throws Exception {
        // 1. -O1 eliminates dead catch on a pure nothrow try body (zero exception_table entries)
        String deadCatch = "def f = fn (x : int) : int -> ( try ( x + 1 ) catch then ( 99 ) )\n"
                + "f(41)";
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledScript cs = engine.compile(deadCatch);
        Assertions.assertEquals(42, cs.eval(new SimpleBindings()));
        ClassNode cn = new ClassNode();
        new ClassReader(((org.babblelang.compiler.CompiledBabbleScript) cs).getClassBytes()).accept(cn, 0);
        MethodNode fMethod = cn.methods.stream().filter(m -> m.name.equals("fn$0$f$spec")).findFirst().orElseThrow();
        Assertions.assertTrue(fMethod.tryCatchBlocks.isEmpty(),
                "Expected -O1 HirDcePass to eliminate dead catch on pure nothrow try, got " + fMethod.tryCatchBlocks);

        // 2. Dead code after throw is eliminated and LICM does not hoist a throwing expression out of try
        String licmAndThrow = "def g = fn (n : int) : int -> (\n"
                + "  def acc = 0\n"
                + "  def i = 0\n"
                + "  while i < n then (\n"
                + "    acc += try (\n"
                + "      if i == 0 then ( throw exception(\"skip\") ; 999 ) else ( 10 )\n"
                + "    ) catch : exception then ( 1 )\n"
                + "    i += 1\n"
                + "  )\n"
                + "  acc\n"
                + ")\n"
                + "g(3)";
        CompiledScript cs2 = engine.compile(licmAndThrow);
        Assertions.assertEquals(21, cs2.eval(new SimpleBindings()));

        // 3. Helper inside try IS inlined; function containing try is NOT inlined into caller
        String inlineTry = "def inc = fn (x : int) : int -> ( x + 1 )\n"
                + "def wrapped = fn (x : int) : int -> ( try ( inc(x) ) catch then ( 0 ) )\n"
                + "def caller = fn (x : int) : int -> ( wrapped(x) )\n"
                + "caller(5)";
        CompiledScript cs3 = engine.compile(inlineTry);
        Assertions.assertEquals(6, cs3.eval(new SimpleBindings()));
        ClassNode cn3 = new ClassNode();
        new ClassReader(((org.babblelang.compiler.CompiledBabbleScript) cs3).getClassBytes()).accept(cn3, 0);
        MethodNode wrapped = cn3.methods.stream().filter(m -> m.name.equals("fn$1$wrapped$spec")).findFirst().orElseThrow();
        List<String> wrappedCalls = new ArrayList<>();
        for (var insn = wrapped.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                wrappedCalls.add(mi.owner + "." + mi.name);
            }
        }
        Assertions.assertTrue(wrappedCalls.stream().noneMatch(c -> c.contains("inc$spec")),
                "Expected helper inc to be inlined into try body, got: " + wrappedCalls);
    }

    // ------------------------------------------------------------------ BIP014-I4 : Record SROA, $unboxed borrow, FBIP & record LICM

    @Test
    public void testRecordScalarReplacementZeroAllocationsInLoop() throws Exception {
        String src = ""
                + "def Vec3 = record (x : double, y : double, z : double) (\n"
                + "  def plus = fn (o : Vec3) : Vec3 -> ( Vec3(this.x + o.x, this.y + o.y, this.z + o.z) )\n"
                + "  def dot = fn (o : Vec3) : double -> ( this.x * o.x + this.y * o.y + this.z * o.z )\n"
                + "  def swapXY = fn () : Vec3 -> ( Vec3(this.y, this.x, this.z) )\n"
                + ")\n"
                + "def run = fn (n : int) : double -> (\n"
                + "  def acc = Vec3(0.0, 0.0, 0.0)\n"
                + "  def step = Vec3(1.0, 2.0, 3.0)\n"
                + "  def i = 0\n"
                + "  while i < n then (\n"
                + "    acc = (acc + step).swapXY()\n"
                + "    i = i + 1\n"
                + "  )\n"
                + "  acc.dot(step)\n"
                + ")\n"
                + "run(10)";

        Assertions.assertEquals(135.0, (Double) assertThreeWayEqual(src), 1e-9);

        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(src);
        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);
        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("fn$0$run$spec")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        List<String> news = new ArrayList<>();
        for (var insn = run.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                calls.add(mi.owner + "." + mi.name);
            } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode ti && ti.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                news.add(ti.desc);
            }
        }
        Assertions.assertTrue(calls.stream().noneMatch(c -> c.contains("Vec3") || c.contains(".$new") || c.contains(".m$")),
                "Expected zero Vec3 allocations or method calls in scalar-replaced loop, got: " + calls);
        Assertions.assertTrue(news.isEmpty(), "Expected zero NEW allocations in scalar-replaced loop, got: " + news);
    }

    @Test
    public void testUnboxedBorrowRecordEntryPoint() throws Exception {
        // Large enough (> 35 HIR nodes) that InlinePass will not inline heavyDot, so ScalarReplacePass
        // synthesizes heavyDot$unboxed taking 6 primitive doubles directly.
        String src = ""
                + "def Vec3 = record (x : double, y : double, z : double) ()\n"
                + "def heavyDot = fn (a : Vec3, b : Vec3) : double -> (\n"
                + "  def d1 = a.x * b.x + a.y * b.y + a.z * b.z\n"
                + "  def d2 = (a.x + 1.0) * (b.x - 0.5) + (a.y + 2.0) * (b.y - 0.25) + (a.z + 3.0) * (b.z - 0.125)\n"
                + "  def d3 = (a.x - a.y) * (b.x - b.y) + (a.y - a.z) * (b.y - b.z) + (a.z - a.x) * (b.z - b.x)\n"
                + "  d1 + d2 + d3\n"
                + ")\n"
                + "def run = fn (n : int) : double -> (\n"
                + "  def a = Vec3(1.0, 2.0, 3.0)\n"
                + "  def b = Vec3(0.5, 1.5, 2.5)\n"
                + "  def i = 0\n"
                + "  def s = 0.0\n"
                + "  while i < n then (\n"
                + "    s = s + heavyDot(a, b)\n"
                + "    i = i + 1\n"
                + "  )\n"
                + "  s\n"
                + ")\n"
                + "run(5)";

        Object expected = assertThreeWayEqual(src);
        Assertions.assertInstanceOf(Double.class, expected);

        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(src);
        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);
        MethodNode unboxed = cn.methods.stream()
                .filter(m -> m.name.endsWith("$unboxed$spec"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected $unboxed$spec method, got: "
                        + cn.methods.stream().map(m -> m.name + m.desc).toList()));
        Assertions.assertTrue(unboxed.desc.endsWith("DDDDDD)D"), "Expected 6 unboxed doubles, got: " + unboxed.desc);

        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("fn$1$run$spec")).findFirst().orElseThrow();
        List<String> runCalls = new ArrayList<>();
        for (var insn = run.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                runCalls.add(mi.owner + "." + mi.name);
            }
        }
        Assertions.assertTrue(runCalls.stream().anyMatch(c -> c.endsWith("$unboxed$spec")),
                "Expected run to call $unboxed$spec, got: " + runCalls);
        Assertions.assertTrue(runCalls.stream().noneMatch(c -> c.endsWith(".$new")),
                "Expected zero Vec3.$new allocations in run, got: " + runCalls);
    }

    @Test
    public void testFbipCollectionAndMutableRecordReuse() throws Exception {
        String src = ""
                + "def Holder = record (items : list of int, count : int) ()\n"
                + "def Vec2 = record (x : int, y : int) ()\n"
                + "def reuseList = fn (unique xs : list of int) : list of int -> (\n"
                + "  def ys = list(from: move(xs))\n"
                + "  ys.add(99)\n"
                + "  ys\n"
                + ")\n"
                + "def reuseSet = fn (unique s : set of int) : set of int -> (\n"
                + "  def t = set(from: move(s))\n"
                + "  t.add(7)\n"
                + "  t\n"
                + ")\n"
                + "def reuseMap = fn (unique m : map of (string, int)) : map of (string, int) -> (\n"
                + "  def n = map(from: move(m))\n"
                + "  n.put(\"b\", 2)\n"
                + "  n\n"
                + ")\n"
                + "def bumpHolder = fn (unique h : Holder) : Holder -> (\n"
                + "  Holder(h.items, h.count + 1)\n"
                + ")\n"
                + "def bumpVec = fn (unique v : Vec2) : Vec2 -> (\n"
                + "  Vec2(v.x + 1, v.y)\n"
                + ")\n"
                + "def driver = fn () : string -> (\n"
                + "  def a = list(1, 2)\n"
                + "  def a2 = reuseList(move(a))\n"
                + "  def s0 = set(3)\n"
                + "  def s2 = reuseSet(move(s0))\n"
                + "  def m0 = map(entry(\"a\", 1))\n"
                + "  def m2 = reuseMap(move(m0))\n"
                + "  def h0 = Holder(list(10), 1)\n"
                + "  def h1 = bumpHolder(move(h0))\n"
                + "  def v0 = Vec2(5, 6)\n"
                + "  def v1 = bumpVec(v0)\n"
                + "  \"\" + a2.size() + \":\" + s2.size() + \":\" + m2.size() + \":\" + h1.count + \":\" + v0.x + \":\" + v1.x\n"
                + ")\n"
                + "driver()";

        Assertions.assertEquals("3:2:2:2:5:6", assertThreeWayEqual(src));

        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(src);
        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);

        List<String> listCalls = methodCalls(cn, "fn$0$reuseList");
        Assertions.assertTrue(listCalls.contains("org/babblelang/runtime/CoreCollections.listReuseOrCopy"),
                "Expected listReuseOrCopy in reuseList, got: " + listCalls);
        List<String> setCalls = methodCalls(cn, "fn$1$reuseSet");
        Assertions.assertTrue(setCalls.contains("org/babblelang/runtime/CoreCollections.setReuseOrCopy"),
                "Expected setReuseOrCopy in reuseSet, got: " + setCalls);
        List<String> mapCalls = methodCalls(cn, "fn$2$reuseMap");
        Assertions.assertTrue(mapCalls.contains("org/babblelang/runtime/CoreCollections.mapReuseOrCopy"),
                "Expected mapReuseOrCopy in reuseMap, got: " + mapCalls);
        List<String> holderCalls = methodCalls(cn, "fn$3$bumpHolder");
        Assertions.assertTrue(holderCalls.stream().noneMatch(c -> c.endsWith(".$new")),
                "Expected in-place RecordReuse (no $new) in bumpHolder, got: " + holderCalls);
    }

    @Test
    public void testBorrowLambdaDevirtualizationAndCrossModuleRecordInlining(@TempDir Path dir) throws Exception {
        // 1. Higher-order borrow lambda devirtualization + inlining
        String hofSrc = ""
                + "def applyTwice = fn (x : int, f : fn (int) : int) : int -> ( f(f(x)) )\n"
                + "def run = fn (x : int) : int -> ( applyTwice(x, fn (v : int) : int -> ( v + 3 )) )\n"
                + "run(10)";
        Assertions.assertEquals(16, assertThreeWayEqual(hofSrc));
        String hofIr = HirPrinter.print(optimizeHir(hofSrc, 1));
        int runStart = hofIr.indexOf("[fn$1$run]");
        Assertions.assertTrue(runStart >= 0, hofIr);
        String runIr = hofIr.substring(runStart);
        Assertions.assertFalse(runIr.contains("(call "), "Expected borrow lambda to be devirtualized & inlined:\n" + runIr);

        // 2. babblec AOT cross-module record method inlining + scalar replacement
        Files.writeString(dir.resolve("vec.ba"), ""
                + "export def Vec3 = record (x : double, y : double, z : double) (\n"
                + "  def plus = fn (o : Vec3) : Vec3 -> ( Vec3(this.x + o.x, this.y + o.y, this.z + o.z) )\n"
                + "  def dot = fn (o : Vec3) : double -> ( this.x * o.x + this.y * o.y + this.z * o.z )\n"
                + ")\n");
        Files.writeString(dir.resolve("main.ba"), ""
                + "def v = import(\"vec.ba\")\n"
                + "def calc = fn (n : int) : double -> (\n"
                + "  def a = v.Vec3(1.0, 2.0, 3.0)\n"
                + "  def b = v.Vec3(0.5, -0.25, 0.125)\n"
                + "  def i = 0\n"
                + "  def s = 0.0\n"
                + "  while i < n then (\n"
                + "    def c = a + b\n"
                + "    s = s + c.dot(a)\n"
                + "    i = i + 1\n"
                + "  )\n"
                + "  s\n"
                + ")\n"
                + "println(calc(4))\n");
        Path jar = dir.resolve("app.jar");
        Babblec.compile(dir.resolve("main.ba"), dir, jar, new javax.script.SimpleScriptContext(), 1);
        Assertions.assertEquals("57.5", runJar(jar));
        List<String> calls = findCallsInMethod(jar, "fn$0$calc$spec");
        Assertions.assertTrue(calls.stream().noneMatch(c -> c.endsWith(".$new") || c.contains(".m$plus") || c.contains(".m$dot")),
                "Expected cross-module Vec3 methods to inline & scalar-replace in AOT jar, got: " + calls);
    }

    @Test
    public void testRecordLicmAndPureInterfaceDce() throws Exception {
        String src = ""
                + "def Metric = interface ( def score = fn nothrow () : int -> ( 0 ) )\n"
                + "def Box = record (v : int) implements Metric (\n"
                + "  def score = fn nothrow () : int -> ( this.v * 2 )\n"
                + ")\n"
                + "def useIface = fn (m : frozen Metric, b : Box, n : int) : int -> (\n"
                + "  def unused = m.score()\n"
                + "  def i = 0\n"
                + "  def s = 0\n"
                + "  while i < n then (\n"
                + "    s = s + m.score() + b.v + i\n"
                + "    i = i + 1\n"
                + "  )\n"
                + "  s\n"
                + ")\n"
                + "useIface(Box(5), Box(3), 4)";
        Assertions.assertEquals(58, assertThreeWayEqual(src));

        String ir = HirPrinter.print(optimizeHir(src, 1));
        int useStart = ir.indexOf("[fn$1$useIface]");
        Assertions.assertTrue(useStart >= 0, ir);
        String useIr = ir.substring(useStart);
        // Dead pure nothrow @TERMINATING interface call `unused` is removed, and the loop's
        // invariant `b.v` record field read is hoisted out of the while loop.
        Assertions.assertFalse(useIr.contains("unused"), "Dead pure interface call should be eliminated:\n" + useIr);
        Assertions.assertTrue(useIr.contains("$licm"), "Loop-invariant record field load should be hoisted:\n" + useIr);
    }

    private static List<String> methodCalls(ClassNode cn, String methodName) {
        MethodNode mn = cn.methods.stream().filter(m -> m.name.equals(methodName)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing method " + methodName + " in "
                        + cn.methods.stream().map(m -> m.name).toList()));
        List<String> calls = new ArrayList<>();
        for (var insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                calls.add(mi.owner + "." + mi.name);
            }
        }
        return calls;
    }

    // ------------------------------------------------------------------ Helpers

    private static String runJar(Path jar) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Standard error stays apart from the output: a JVM reports JAVA_TOOL_OPTIONS / _JAVA_OPTIONS
        // there ("Picked up ..."), which must not end up in the program's output.
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-jar", jar.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = builder.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String error = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "Jar exited with " + exit + ":\n" + output + "\n" + error);
        return output;
    }

    private static List<String> findCallsInMethod(Path jar, String methodName) throws Exception {
        List<String> calls = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class") || !e.getName().startsWith("org/babblelang/generated/")) {
                    continue;
                }
                byte[] bytes = jf.getInputStream(e).readAllBytes();
                ClassNode cn = new ClassNode();
                new ClassReader(bytes).accept(cn, 0);
                for (MethodNode mn : cn.methods) {
                    if (mn.name.equals(methodName)) {
                        for (var insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                            if (insn instanceof MethodInsnNode mi) {
                                calls.add(mi.owner + "." + mi.name);
                            }
                        }
                    }
                }
            }
        }
        return calls;
    }
}
