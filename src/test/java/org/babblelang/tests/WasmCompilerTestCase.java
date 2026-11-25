package org.babblelang.tests;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.wasm.WasmCompiler;
import org.babblelang.wasm.WasmProgramLinker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end tests for the WebAssembly 3.0 backend ({@code BIP025-P1}): scalars, control flow,
 * closures, {@code BIP024} tail calls, {@code BIP021} optional/named/overloaded calls,
 * {@code BIP028} exceptions &amp; 2-copy {@code finally}, and {@code -target wasm-js|wasm-bare} CLI.
 */
public class WasmCompilerTestCase {

    private static WasmCompiler.CompileResult compile(String source, CompilationTarget target, int optLevel) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        List<BabbleScriptEngine.HirProgramModule> mods = engine.compileSourceToHir(source, "test.ba", target);
        List<WasmProgramLinker.ModuleInput> inputs = mods.stream()
                .map(m -> new WasmProgramLinker.ModuleInput(m.id().key(), m.id().displayName(), m.hir()))
                .toList();
        boolean dumpWat = WasmCompiler.Options.defaults(target).dumpWat();
        WasmCompiler.Options opts = new WasmCompiler.Options(target, true, true, dumpWat, false);
        WasmCompiler.CompileResult res = WasmCompiler.compile(inputs, opts, "test.wasm");
        Assertions.assertTrue(res.ok(), "Portability errors : " + res.portabilityErrors());
        return res;
    }

    private static NodeWasmRunner.RunOutcome runWasm(String source, CompilationTarget target, int optLevel) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        WasmCompiler.CompileResult res = compile(source, target, optLevel);
        if (target == CompilationTarget.WASM_BARE) {
            return NodeWasmRunner.runBabbleBare(res.wasmBytes());
        }
        return NodeWasmRunner.runJs(res.wasmBytes(), res.jsGlue(), List.of(), null);
    }

    @Test
    public void scalarsAndConversionsOnBareAndJs() throws Exception {
        String code = """
                def b = byte(120)
                def s = short(1000)
                def i = 42
                def l = 3000000000L
                def f = 1.5f
                def d = 2.25
                def c = 'A'
                def ok = true
                assert(b + s == 1120)
                assert(i * 2 == 84)
                assert(l + 1L == 3000000001L)
                assert(f + 0.5f == 2.0f)
                assert(d / 0.5 == 4.5)
                assert(int(c) == 65)
                assert(char(66) == 'B')
                assert(int(" -123 ") == -123)
                assert(long("9223372036854775807") == 9223372036854775807L)
                assert(bool("true") and not bool("false"))
                assert(string(123) == "123")
                assert(ok and 1 == 1.0)
                def nan = 0.0 / 0.0
                assert(nan == nan)
                assert(-0.0 < 0.0)
                "ok:" + (i + 1)
                """;
        for (CompilationTarget t : List.of(CompilationTarget.WASM_BARE, CompilationTarget.WASM_JS)) {
            NodeWasmRunner.RunOutcome out = runWasm(code, t, 1);
            Assertions.assertTrue(out.ok(), t + " failed : " + out.error());
            Assertions.assertEquals("\"ok:43\"", out.resultJson(), "Target : " + t);
        }
    }

    @Test
    public void controlFlowLoopsBreakValueAndShortCircuit() throws Exception {
        String code = """
                def sum = 0
                def i = 0
                while i < 10 then (
                    i += 1
                    if i == 5 then (continue)
                    sum += i
                )
                assert(sum == 50)

                def found = while true then (
                    if sum > 40 then (break sum * 2)
                )
                assert(found == 100)

                def calls = 0
                def bump = fn io (): int -> (calls += 1; calls)
                assert((false and bump() > 0) == false)
                assert(calls == 0)
                assert((true or bump() > 0) == true)
                assert(calls == 0)
                assert((0 or 7) == true)
                assert((3 and 0) == false)
                found
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Failed : " + out.error());
        Assertions.assertEquals("\"100\"", out.resultJson());
    }

    @Test
    public void closuresCaptureCellsAndHigherOrderFunctions() throws Exception {
        String code = """
                def makeCounter = fn (start: int): fn io (): int -> (
                    def n = start
                    fn io (): int -> (
                        n += 1
                        n
                    )
                )
                def c1 = makeCounter(10)
                def c2 = makeCounter(100)
                assert(c1() == 11)
                assert(c1() == 12)
                assert(c2() == 101)
                assert(c1() == 13)

                def applyTwice = fn (f: fn (int): int, x: int): int -> (f(f(x)))
                def add3 = fn (x: int): int -> (x + 3)
                assert(applyTwice(add3, 10) == 16)
                c1()
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Failed : " + out.error());
        Assertions.assertEquals("\"14\"", out.resultJson());
    }

    @Test
    public void tailCallRecursion500kDeepUsesReturnCall() throws Exception {
        String code = """
                export def sumTo = fn (n: long, acc: long): long -> (
                    if n <= 0L then (acc) else (recurse(n - 1L, acc + n))
                )
                sumTo(500000L, 0L)
                """;
        WasmCompiler.CompileResult compiled = compile(code, CompilationTarget.WASM_BARE, 1);
        String wat = org.babblelang.wasm.WatPrinter.print(compiled.module());
        Assertions.assertTrue(wat.contains("return_call"), "Expected return_call in WAT for BIP024 tail call");

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBabbleBare(compiled.wasmBytes());
        Assertions.assertTrue(out.ok(), "500k tail recursion failed : " + out.error());
        Assertions.assertEquals("\"125000250000\"", out.resultJson());
    }

    @Test
    public void optionalNamedReorderedAndOverloadedCalls() throws Exception {
        String code = """
                def sub = fn (a: int, b: int = 10, c: int = a + b): int -> (a * 100 + b * 10 + c)
                assert(sub(1) == 100 + 100 + 11)
                assert(sub(1, 2) == 123)
                assert(sub(b: 3, a: 2) == 235)
                assert(sub(c: 9, a: 4) == 509)

                def log = ""
                def rec = fn io (tag: string, v: int): int -> (log += tag; v)
                assert(sub(b: rec("B", 2), a: rec("A", 1)) == 123)
                assert(log == "BA")

                def fmt = overload (
                    fn (x: int): string -> ("int:" + x),
                    fn (s: string): string -> ("str:" + s)
                )
                def dispatch = fn (v: int | string): string -> (fmt(v))
                assert(dispatch(7) == "int:7")
                assert(dispatch("hi") == "str:hi")
                "ok"
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Failed : " + out.error());
        Assertions.assertEquals("\"ok\"", out.resultJson());
    }

    @Test
    public void exceptionsCatchFinallySuppressionAndControlTransfer() throws Exception {
        // 1. try / catch / finally
        NodeWasmRunner.RunOutcome o1 = runWasm("""
                def trace = ""
                def r1 = try (
                    trace += "T"
                    throw exception("boom")
                    "never"
                ) catch e: exception then (
                    trace += "C:" + e.getMessage()
                    "caught"
                ) finally (
                    trace += "F"
                )
                assert(r1 == "caught")
                assert(trace == "TC:boomF")
                trace
                """, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(o1.ok(), "Subtest 1 (try/catch/finally) failed : " + o1.error());

        // 2. Return intercepted by finally
        NodeWasmRunner.RunOutcome o2 = runWasm("""
                def finRan = false
                def withRet = fn io (): int -> (
                    try (
                        return 42
                    ) finally (
                        finRan = true
                    )
                )
                assert(withRet() == 42)
                assert(finRan)
                42
                """, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(o2.ok(), "Subtest 2 (return in try/finally) failed : " + o2.error());

        // 3. Break intercepted by finally
        NodeWasmRunner.RunOutcome o3 = runWasm("""
                def brkFin = 0
                def brkVal = while true then (
                    try (
                        break 77
                    ) finally (
                        brkFin += 1
                    )
                )
                assert(brkVal == 77)
                assert(brkFin == 1)
                brkVal
                """, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(o3.ok(), "Subtest 3 (break in try/finally) failed : " + o3.error());

        // 4. Exception with cause
        NodeWasmRunner.RunOutcome o4 = runWasm("""
                def root = exception("root-cause")
                def caused = try (
                    throw exception("wrapper", root)
                ) catch e: exception then (
                    "" + e.getMessage() + ":" + (e.getCause() as exception).getMessage()
                )
                assert(caused == "wrapper:root-cause")
                caused
                """, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(o4.ok(), "Subtest 4 (exception cause) failed : " + o4.error());

        // 5. BIP028-D6: throw in finally attaches suppressed and rethrows primary!
        NodeWasmRunner.RunOutcome o5 = runWasm("""
                def suppMsg = try (
                    try (
                        throw exception("primary")
                    ) finally (
                        throw exception("secondary")
                    )
                ) catch e: exception then (
                    e.getMessage()
                )
                assert(suppMsg == "primary")
                suppMsg
                """, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(o5.ok(), "Subtest 5 (finally suppression) failed : " + o5.error());
        Assertions.assertEquals("\"primary\"", o5.resultJson());
    }

    @Test
    public void wasmJsStdoutAndDoubleFormattingParity() throws Exception {
        String code = """
                println("hello ", 42, " ", 1.0, " ", -0.0, " ", 10000000.0, " ", 0.0001)
                print("done")
                assert(double("3.5") == 3.5)
                assert(float("1.25") == 1.25f)
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_JS, 1);
        Assertions.assertTrue(out.ok(), "Failed : " + out.error());
        Assertions.assertEquals("hello 42 1.0 -0.0 1.0E7 1.0E-4\ndone", out.stdout());
    }

    @Test
    public void cliCompileTargetWasmJsAndBareDeterministic(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        Path script = dir.resolve("hello.ba");
        Files.writeString(script, """
                println("from-cli:", 21 * 2)
                """, StandardCharsets.UTF_8);

        Path wasmOut = dir.resolve("hello.wasm");
        Path mjsOut = dir.resolve("hello.mjs");
        java.io.StringWriter outBuf = new java.io.StringWriter();
        java.io.StringWriter errBuf = new java.io.StringWriter();
        int status = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", script.toString(), "-o", wasmOut.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(outBuf), new java.io.PrintWriter(errBuf), false);
        Assertions.assertEquals(0, status, "CLI compile failed : " + errBuf);
        Assertions.assertTrue(Files.isRegularFile(wasmOut));
        Assertions.assertTrue(Files.isRegularFile(mjsOut));

        // Deterministic rebuild check
        byte[] firstBytes = Files.readAllBytes(wasmOut);
        int status2 = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", script.toString(), "-o", wasmOut.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()), new java.io.PrintWriter(new java.io.StringWriter()), false);
        Assertions.assertEquals(0, status2);
        Assertions.assertArrayEquals(firstBytes, Files.readAllBytes(wasmOut), "Wasm build must be deterministic");

        // Execute generated hello.mjs directly with `node`
        Process p = new ProcessBuilder("node", mjsOut.toString()).redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "node hello.mjs failed : " + stderr);
        Assertions.assertEquals("from-cli:42\n", stdout);
    }
}
