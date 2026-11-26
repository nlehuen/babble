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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
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
 * Soundness, API/plumbing, {@code babblec} AOT cross-module inlining, error line-number fidelity,
 * and ASM bytecode inspection tests for the {@code BIP014} / {@code BIP027-I2} HIR optimizer
 * ({@code HirEGraphSimplifyPass}, {@code InlinePass}, {@code HirDcePass}, {@code HirLicmPass},
 * {@code ScalarReplacePass}, {@code FbipReusePass}). Canonical {@code -O0} vs. {@code -O1} HIR
 * goldens live in {@code src/test/batest/compiler/*.batest.md} ({@code BIP029}).
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

    // ------------------------------------------------------------------ O1 : Constant Folding Soundness

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
    public void testCrossModuleDirectCallsAndSmallExportInlining(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("math.ba"),
                "export def sq = fn (x : int) : int -> ( x * x )\n"
                        + "export def bigSq = fn (x : int) : int -> ( if x < 0 then ( return x * x ) ; x * x )\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.ba"),
                "def m = import(\"math.ba\")\n"
                        + "def calc = fn (n : int) : int -> ( m.sq(n) + 1 )\n"
                        + "def calcBig = fn (n : int) : int -> ( m.bigSq(n) + 1 )\n"
                        + "println(calc(7) + calcBig(7))\n",
                StandardCharsets.UTF_8);

        Path jarO0 = dir.resolve("app-o0.jar");
        Path jarO1 = dir.resolve("app-o1.jar");
        Babblec.compile(dir.resolve("main.ba"), dir, jarO0, new javax.script.SimpleScriptContext(), 0);
        Babblec.compile(dir.resolve("main.ba"), dir, jarO1, new javax.script.SimpleScriptContext(), 1);

        // Both jars must print 100.
        Assertions.assertEquals("100", runJar(jarO0));
        Assertions.assertEquals("100", runJar(jarO1));

        // At -O0, both calls emit direct INVOKESTATIC to math$ba's $spec entry points (no Bootstrap indy).
        List<String> o0Calls = findCallsInMethod(jarO0, "fn$0$calc$spec");
        Assertions.assertTrue(o0Calls.stream().anyMatch(c -> c.endsWith(".fn$0$sq$spec")),
                "m.sq(n) should lower to direct INVOKESTATIC at -O0, found calls: " + o0Calls);

        // At -O1, small m.sq(n) is inlined (no call to sq or Module.exportAt), while non-inlineable m.bigSq(n)
        // stays a direct INVOKESTATIC to fn$1$bigSq$spec.
        List<String> o1Calls = findCallsInMethod(jarO1, "fn$0$calc$spec");
        Assertions.assertTrue(o1Calls.stream().noneMatch(c -> c.contains("sq") || c.contains("exportAt") || c.contains("apply")),
                "m.sq(n) should be inlined cleanly at -O1, found calls: " + o1Calls);
        List<String> o1BigCalls = findCallsInMethod(jarO1, "fn$1$calcBig$spec");
        Assertions.assertTrue(o1BigCalls.stream().anyMatch(c -> c.endsWith(".fn$1$bigSq$spec")),
                "non-inlineable m.bigSq(n) should use direct INVOKESTATIC at -O1, found calls: " + o1BigCalls);

        // Regular BabbleScriptEngine (not just babblec) also inlines m.sq(n) and emits direct INVOKESTATIC for m.bigSq(n).
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 1);
        engine.put(BabbleScriptEngine.MODULE_SOURCE, org.babblelang.engine.FileModuleSource.rooted(dir));
        org.babblelang.compiler.CompiledBabbleScript compiled =
                (org.babblelang.compiler.CompiledBabbleScript) engine.compile(Files.readString(dir.resolve("main.ba")));
        byte[] mainBytes = compiled.getClassBytes();
        List<String> jitSqCalls = findCallsInClassBytes(mainBytes, "fn$0$calc$spec");
        Assertions.assertTrue(jitSqCalls.stream().noneMatch(c -> c.contains("sq") || c.contains("exportAt") || c.contains("apply")),
                "m.sq(n) should inline in BabbleScriptEngine -O1 too, found calls: " + jitSqCalls);
        List<String> jitBigCalls = findCallsInClassBytes(mainBytes, "fn$1$calcBig$spec");
        Assertions.assertTrue(jitBigCalls.stream().anyMatch(c -> c.endsWith(".fn$1$bigSq$spec")),
                "m.bigSq(n) should lower to direct INVOKESTATIC in BabbleScriptEngine -O1, found calls: " + jitBigCalls);
    }

    // ------------------------------------------------------------------ O4 : HirDcePass

    @Test
    public void testDceEliminatesScpTerminatingCallsAndPreservesNonTerminatingOnes() throws Exception {
        String src = ""
                + "def fib = fn (n : int) : int -> ( if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) ) )\n"
                + "def sumList = fn (xs : list of int) : int -> (\n"
                + "  var total = 0\n"
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
    public void testLicmDoesNotHoistImpureVolatileOrThrowingExpressions() throws Exception {
        // 1. Throwing expression inside a 0-iteration loop MUST NOT be hoisted!
        String zeroIterThrow = ""
                + "def f = fn io (n : int, d : int) : int -> (\n"
                + "  var i = 0\n"
                + "  var s = 0\n"
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
                + "var count = 0\n"
                + "def tick = fn io () : int -> ( count = count + 1 ; count )\n"
                + "def run = fn io (n : int) : int -> (\n"
                + "  var i = 0\n"
                + "  var s = 0\n"
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
                + "  var acc = 0\n"
                + "  var i = 0\n"
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
                + "  var acc = Vec3(0.0, 0.0, 0.0)\n"
                + "  def step = Vec3(1.0, 2.0, 3.0)\n"
                + "  var i = 0\n"
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
                + "  var i = 0\n"
                + "  var s = 0.0\n"
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
    public void testCrossModuleRecordInliningInBabblecAot(@TempDir Path dir) throws Exception {
        // babblec AOT cross-module record method inlining + scalar replacement
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
                + "  var i = 0\n"
                + "  var s = 0.0\n"
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
    public void testO2SsaMirPipelineAndBytecode() throws Exception {
        String code = ""
                + "def f = fn (flag : bool, x : double) : double -> (\n"
                + "  def k = if flag then (4) else (4)\n"
                + "  def a = java.lang.Math.abs(x)\n"
                + "  def b = java.lang.Math.abs(a)\n"
                + "  b + (k * 10 + 2)\n"
                + ")\n"
                + "f(true, -3.5)\n";
        List<String> mirStages = new ArrayList<>();
        org.babblelang.ir.IrObserver obs = new org.babblelang.ir.IrObserver() {
            @Override
            public void onSnapshot(org.babblelang.ir.IrSnapshot snap) {
                if (snap.tier() == org.babblelang.ir.IrTier.MIR) {
                    mirStages.add(snap.stage());
                }
            }

            @Override
            public boolean wants(org.babblelang.ir.IrTier tier, int optLevel, String stage) {
                return tier == org.babblelang.ir.IrTier.MIR;
            }
        };
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 2);
        engine.put(BabbleCompiler.IR_OBSERVER, obs);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(code);
        Assertions.assertTrue(mirStages.contains("before"), "Expected MIR before snapshot: " + mirStages);
        Assertions.assertTrue(mirStages.contains("after:SccpPass"), "Expected MIR after:SccpPass snapshot: " + mirStages);
        Assertions.assertTrue(mirStages.contains("final"), "Expected MIR final snapshot: " + mirStages);
        Object res = cs.eval();
        Assertions.assertEquals(45.5, ((Number) res).doubleValue(), 1e-9);
        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);
        List<String> calls = methodCalls(cn, "fn$0$f$spec");
        long absCalls = calls.stream().filter(c -> c.equals("java/lang/Math.abs")).count();
        Assertions.assertEquals(1, absCalls, "Expected -O2 GvnCsePass to collapse Math.abs(Math.abs(x)) to 1 call, got: " + calls);
    }

    @Test
    public void testO2BabblecAotJar(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("main.ba"), ""
                + "def Lim = record (k : int) ()\n"
                + "def sumTo = fn (lim : Lim, a : int, b : int) : int -> (\n"
                + "  var i = 0\n"
                + "  var acc = 0\n"
                + "  while i < a * b + lim.k then (\n"
                + "    acc += i\n"
                + "    i += 1\n"
                + "  )\n"
                + "  acc\n"
                + ")\n"
                + "println(sumTo(Lim(1), 2, 2))\n");
        Path jar = dir.resolve("app-o2.jar");
        Babblec.compile(dir.resolve("main.ba"), dir, jar, new javax.script.SimpleScriptContext(), 2);
        Assertions.assertEquals("10", runJar(jar));
    }

    @Test
    public void testO2TypedCallPrimitiveReturnNoBoxUnbox() throws Exception {
        String code = ""
                + "def dA = fn (x : double) : double -> ( x * 0.5 + 1.0 )\n"
                + "def dB = fn (x : double) : double -> ( x * 0.25 + 2.0 )\n"
                + "def iA = fn (x : int) : int -> ( x + 3 )\n"
                + "def iB = fn (x : int) : int -> ( x + 5 )\n"
                + "def bA = fn (x : double) : bool -> ( x > 1.0 )\n"
                + "def bB = fn (x : double) : bool -> ( x < 10.0 )\n"
                + "def run = fn (flag : bool, x : double, k : int) : double -> (\n"
                + "  def fd : fn (double) : double = if flag then (dA) else (dB)\n"
                + "  def fi : fn (int) : int = if flag then (iA) else (iB)\n"
                + "  def fb : fn (double) : bool = if flag then (bA) else (bB)\n"
                + "  def d = fd(x)\n"
                + "  def n = fi(k)\n"
                + "  if fb(d) then ( d + n ) else ( d - n )\n"
                + ")\n"
                + "run(true, 4.0, 2)\n";
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 2);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(code);
        Assertions.assertEquals(8.0, ((Number) cs.eval()).doubleValue(), 1e-9);

        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);
        MethodNode mn = cn.methods.stream().filter(m -> m.name.endsWith("$run$spec")).findFirst()
                .orElseThrow(() -> new AssertionError("Missing $run$spec method"));
        List<String> calls = new ArrayList<>();
        int typedCalls = 0;
        for (var insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                calls.add(mi.owner + "." + mi.name);
            } else if (insn instanceof InvokeDynamicInsnNode indy && "typedCall".equals(indy.name)) {
                typedCalls++;
            }
        }
        Assertions.assertEquals(3, typedCalls, "Expected 3 typedCall sites in $run$spec");
        Assertions.assertTrue(calls.stream().noneMatch(c ->
                        c.equals("java/lang/Double.valueOf")
                                || c.equals("java/lang/Integer.valueOf")
                                || c.equals("java/lang/Boolean.valueOf")
                                || c.startsWith("org/babblelang/runtime/JavaTypes.unbox")),
                "Expected zero box/unbox round-trips for primitive typedCall returns at -O2, got: " + calls);
    }

    @Test
    public void testO2CompactMirBytecodeConstantsAndDiscardPop() throws Exception {
        String code = ""
                + "def sink = fn io (x : int) : int -> ( x + 1 )\n"
                + "def kernel = fn io (x : double, n : int) : double -> (\n"
                + "  sink(n)\n"
                + "  if x <= 0.0 then ( return 0.0 )\n"
                + "  var g = if x > 1.0 then ( x * 0.5 ) else ( 1.0 )\n"
                + "  var i = 0\n"
                + "  while i < n then (\n"
                + "    g = 0.5 * (g + x / g)\n"
                + "    i += 1\n"
                + "  )\n"
                + "  g\n"
                + ")\n"
                + "kernel(9.0, 6)\n";
        BabbleScriptEngine o1Engine = newEngine();
        o1Engine.put(BabbleScriptEngine.MODE, "compile");
        o1Engine.put(BabbleCompiler.OPT_LEVEL, 1);
        CompiledBabbleScript cs1 = (CompiledBabbleScript) o1Engine.compile(code);

        BabbleScriptEngine o2Engine = newEngine();
        o2Engine.put(BabbleScriptEngine.MODE, "compile");
        o2Engine.put(BabbleCompiler.OPT_LEVEL, 2);
        CompiledBabbleScript cs2 = (CompiledBabbleScript) o2Engine.compile(code);

        Assertions.assertEquals(((Number) cs1.eval()).doubleValue(), ((Number) cs2.eval()).doubleValue(), 1e-12);

        ClassNode cn1 = new ClassNode();
        new ClassReader(cs1.getClassBytes()).accept(cn1, 0);
        MethodNode mn1 = cn1.methods.stream().filter(m -> m.name.endsWith("$kernel$spec")).findFirst().orElseThrow();

        ClassNode cn2 = new ClassNode();
        new ClassReader(cs2.getClassBytes()).accept(cn2, 0);
        MethodNode mn2 = cn2.methods.stream().filter(m -> m.name.endsWith("$kernel$spec")).findFirst().orElseThrow();

        boolean sawDconst0 = false;
        boolean sawDconst1 = false;
        boolean sawPop = false;
        int realInsns1 = 0;
        int realInsns2 = 0;
        for (var insn = mn1.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) realInsns1++;
        }
        for (var insn = mn2.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= 0) realInsns2++;
            if (op == Opcodes.DCONST_0) sawDconst0 = true;
            if (op == Opcodes.DCONST_1) sawDconst1 = true;
            if (op == Opcodes.POP) sawPop = true;
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d) {
                Assertions.assertFalse(Double.doubleToRawLongBits(d) == 0L || d == 1.0d,
                        "Expected DCONST_0/DCONST_1 instead of LDC for " + d);
            }
        }
        Assertions.assertTrue(sawDconst0 && sawDconst1, "Expected DCONST_0 and DCONST_1 in -O2 kernel");
        Assertions.assertTrue(sawPop, "Expected POP for discarded sink(n) result in -O2 kernel");
        Assertions.assertTrue(realInsns2 <= realInsns1,
                "Expected -O2 instruction count (" + realInsns2 + ") <= -O1 (" + realInsns1 + ")");
    }

    @Test
    public void testO2BoxUnboxConvertCancellation() throws Exception {
        String code = ""
                + "def unboxBox = fn (x : int) : int -> (\n"
                + "  def a : any = x\n"
                + "  (a as int) + 1\n"
                + ")\n"
                + "def boxUnbox = fn (x : int?) : any? -> (\n"
                + "  if x != null then (\n"
                + "    def y : int = x\n"
                + "    y\n"
                + "  ) else ( null )\n"
                + ")\n"
                + "unboxBox(41) + (boxUnbox(5) as int)\n";
        BabbleScriptEngine engine = newEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, 2);
        CompiledBabbleScript cs = (CompiledBabbleScript) engine.compile(code);
        Assertions.assertEquals(47, ((Number) cs.eval()).intValue());

        ClassNode cn = new ClassNode();
        new ClassReader(cs.getClassBytes()).accept(cn, 0);

        String unboxBoxMethod = cn.methods.stream()
                .map(m -> m.name)
                .filter(n -> n.endsWith("$unboxBox$spec"))
                .findFirst().orElseThrow();
        List<String> unboxBoxCalls = methodCalls(cn, unboxBoxMethod);
        Assertions.assertTrue(unboxBoxCalls.stream().noneMatch(c ->
                        c.equals("java/lang/Integer.valueOf")
                                || c.equals("org/babblelang/runtime/JavaTypes.unboxInt")
                                || c.equals("org/babblelang/runtime/Types.check")),
                "Expected unbox(box(a)) + CheckCast eliminated at -O2, got: " + unboxBoxCalls);

        String boxUnboxMethod = cn.methods.stream()
                .map(m -> m.name)
                .filter(n -> n.endsWith("$boxUnbox"))
                .findFirst().orElseThrow();
        List<String> boxUnboxCalls = methodCalls(cn, boxUnboxMethod);
        Assertions.assertTrue(boxUnboxCalls.stream().noneMatch(c ->
                        c.equals("java/lang/Integer.valueOf")
                                || c.equals("org/babblelang/runtime/JavaTypes.unboxInt")),
                "Expected box(unbox(narrow(a))) eliminated at -O2, got: " + boxUnboxCalls);
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
                if (!e.getName().endsWith(".class")
                        || (!e.getName().startsWith("babble/") && !e.getName().startsWith("org/babblelang/generated/"))) {
                    continue;
                }
                calls.addAll(findCallsInClassBytes(jf.getInputStream(e).readAllBytes(), methodName));
            }
        }
        return calls;
    }

    private static List<String> findCallsInClassBytes(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
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
        return calls;
    }
}
