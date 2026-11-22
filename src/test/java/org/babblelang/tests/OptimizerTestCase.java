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
 * Soundness, golden-IR, and 3-way parity tests for the {@code BIP014} HIR Nanopass optimizer
 * ({@code ConstFoldPass}, {@code AlgebraicSimplifyPass}, {@code InlinePass}, {@code HirDcePass},
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
        HirModule out = PassManager.defaultFor(optLevel).optimize(hir, ctx, null);
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

    // ------------------------------------------------------------------ O1 : ConstFoldPass

    @Test
    public void testConstFoldArithmeticStringsBranchesAndWhitelistedJdk() throws Exception {
        String src = ""
                + "axiom pure nothrow on java.lang.Math.sqrt\n"
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

    // ------------------------------------------------------------------ O2 & O3 : AlgebraicSimplifyPass

    @Test
    public void testAlgebraicIdentitiesReassociationAndIdempotentCollapse() throws Exception {
        String src = ""
                + "axiom pure nothrow on java.lang.Math.abs\n"
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

    // ------------------------------------------------------------------ O6 : HirLicmPass

    @Test
    public void testLicmHoistsPureNothrowInvariantCallsOutOfWhileAndForLoops() throws Exception {
        String src = ""
                + "axiom pure nothrow on java.lang.Math.sin\n"
                + "axiom pure nothrow on java.lang.Math.cos\n"
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
