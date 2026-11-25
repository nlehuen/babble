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
                .map(m -> new WasmProgramLinker.ModuleInput(m.id().key(), m.id().displayName(), m.hir(), m.staticImportKeys()))
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

    // ---------------------------------------------------------------- BIP025-P2 Object model, arrays & modules

    @Test
    public void recordsImmutableFieldsOperatorsAndValueSemantics() throws Exception {
        String code = """
                def Vec2 = record (x: double, y: double) (
                    def plus = fn (other: Vec2): Vec2 -> (Vec2(this.x + other.x, this.y + other.y))
                    def minus = fn (other: Vec2): Vec2 -> (Vec2(this.x - other.x, this.y - other.y))
                    def times = fn (s: double): Vec2 -> (Vec2(this.x * s, this.y * s))
                    def neg = fn (): Vec2 -> (Vec2(-this.x, -this.y))
                    def dot = fn (other: Vec2): double -> (this.x * other.x + this.y * other.y)
                )
                def a = Vec2(1.0, 2.0)
                def b = Vec2(1.0, 2.0)
                def c = Vec2(3.0, 4.0)
                assert(a == b)
                assert(a != c)
                assert(not same(a, b))
                assert(same(a, a))
                assert(classof(a) == Vec2)
                assert(a is Vec2)
                assert(string(a) == "Vec2(x: 1.0, y: 2.0)")
                def sum = a + c
                assert(sum == Vec2(4.0, 6.0))
                assert((c - a) == Vec2(2.0, 2.0))
                assert((a * 3.0) == Vec2(3.0, 6.0))
                assert((-a) == Vec2(-1.0, -2.0))
                assert(a.dot(c) == 11.0)
                ;; Dynamic constructor via first-class class value
                def ctor: fn (double, double): Vec2 = Vec2
                def d = ctor(5.0, 6.0)
                assert(d == Vec2(5.0, 6.0))
                string(sum)
                """;
        WasmCompiler.CompileResult compiled = compile(code, CompilationTarget.WASM_BARE, 1);
        String wat = org.babblelang.wasm.WatPrinter.print(compiled.module());
        // BIP025-D11: record parameter fields must be strictly immutable (no `(mut f64)`)!
        Assertions.assertTrue(wat.contains("(field $f_x f64)") && wat.contains("(field $f_y f64)"),
                "Expected immutable f64 fields for record Vec2 in WAT");
        Assertions.assertFalse(wat.contains("(field $f_x (mut f64))"),
                "Record fields must NOT be mutable in WasmGC (BIP025-D11)");

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBabbleBare(compiled.wasmBytes());
        Assertions.assertTrue(out.ok(), "Records test failed : " + out.error());
        Assertions.assertEquals("\"Vec2(x: 4.0, y: 6.0)\"", out.resultJson());
    }

    @Test
    public void classesMutableFieldsStaticsObjectsBoundMethodsAndCaptures() throws Exception {
        String code = """
                def Counter = class (start: int = 0) (
                    def value = start
                    Counter.total += 1
                    def inc = fn mut (by: int = 1): int -> (
                        this.value += by
                        this.value
                    )
                ) static (
                    def total = 0
                    def bumpTotal = fn io (n: int): int -> (Counter.total += n; Counter.total)
                )
                def Singleton = object (
                    def tag = "only-one"
                    def greet = fn (who: string): string -> (this.tag + ":" + who)
                )
                def c1 = Counter(10)
                def c2 = Counter()
                assert(Counter.total == 2)
                assert(Counter.bumpTotal(5) == 7)
                assert(c1.inc() == 11)
                assert(c1.inc(4) == 15)
                assert(c2.inc() == 1)
                assert(c1 != c2)
                assert(c1 == c1)
                ;; Bound method passed as first-class function
                def f = c1.inc
                assert(f(5) == 20)
                assert(c1.value == 20)
                ;; Object singleton identity
                assert(same(Singleton, Singleton))
                assert(Singleton.greet("wasm") == "only-one:wasm")
                ;; Capturing class inside a function
                def makeBox = fn io (base: int): int -> (
                    def acc = base
                    def Adder = class (def step: int) (
                        def run = fn io (): int -> (acc += this.step; acc)
                    )
                    def a = Adder(3)
                    assert(a.run() == base + 3)
                    a.run()
                )
                assert(makeBox(10) == 16)
                c1.value
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Classes/objects test failed : " + out.error());
        Assertions.assertEquals("\"20\"", out.resultJson());
    }

    @Test
    public void interfacesDefaultsSuperBitsetIntersectionAndDevirtualization() throws Exception {
        String code = """
                def Named = interface (
                    def name: fn (): string
                    def label = fn (): string -> ("<" + this.name() + ">")
                )
                def Aged = interface (
                    def age: fn (): int
                )
                def SingleOnly = interface (
                    def ping: fn (): int
                )
                def Dog = record (n: string, a: int) implements Named, Aged, SingleOnly (
                    def name = fn (): string -> (this.n)
                    def age = fn (): int -> (this.a)
                    def ping = fn (): int -> (this.a * 10)
                    def label = fn (): string -> ("Dog:" + super(Named).label())
                )
                def Cat = record (n: string) implements Named (
                    def name = fn (): string -> (this.n)
                )
                def describe = fn (x: Named): string -> (x.label())
                def pingSingle = fn (s: SingleOnly): int -> (s.ping())
                def checkBoth = fn (x: any): bool -> (x is (Named & Aged))

                def d = Dog("Rex", 4)
                def c = Cat("Mittens")
                assert(describe(d) == "Dog:<Rex>")
                assert(describe(c) == "<Mittens>")
                assert(pingSingle(d) == 40)
                assert(d is Named)
                assert(d is Aged)
                assert(checkBoth(d))
                assert(c is Named)
                assert(not (c is Aged))
                assert(not checkBoth(c))
                assert(not checkBoth(42))
                def asNamed = (d as any) as Named
                assert(asNamed.name() == "Rex")
                def opt: Named? = null
                assert(opt?.name() == null)
                def opt2: Named? = c
                assert(opt2?.name() == "Mittens")
                describe(d)
                """;
        WasmCompiler.CompileResult compiled = compile(code, CompilationTarget.WASM_BARE, 1);
        String wat = org.babblelang.wasm.WatPrinter.print(compiled.module());
        // BIP025-D7: single-mask i64.and + i64.eq for intersection `x is (Named & Aged)`
        Assertions.assertTrue(wat.contains("i64.and") && wat.contains("i64.eq"),
                "Expected single-mask i64.and + i64.eq for interface intersection in WAT");

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBabbleBare(compiled.wasmBytes());
        Assertions.assertTrue(out.ok(), "Interfaces test failed : " + out.error());
        Assertions.assertEquals("\"Dog:<Rex>\"", out.resultJson());
    }

    @Test
    public void arraysPrimitiveRefDeepEqualityAndBoundsChecks() throws Exception {
        String code = """
                def a = (array of int)(4)
                assert(a.size() == 4)
                assert(a.get(0) == 0)
                a.set(0, 10)
                a.set(1, 20)
                a.set(2, 30)
                a.set(3, 40)
                def b = (array of int).of(10, 20, 30, 40)
                assert(a == b)
                assert(string(b) == "[10, 20, 30, 40]")

                def ds = (array of double).of(1.5, 2.5)
                assert(ds.get(0) + ds.get(1) == 4.0)

                def Point = record (x: int, y: int) ()
                def pts = (array of Point?)(2)
                pts.set(0, Point(1, 2))
                pts.set(1, Point(3, 4))
                assert(pts.get(1) == Point(3, 4))
                assert(string(pts) == "[Point(x: 1, y: 2), Point(x: 3, y: 4)]")

                def oobCaught = try (
                    a.get(99)
                    false
                ) catch e: exception then (
                    true
                )
                assert(oobCaught)

                def negCaught = try (
                    (array of int)(-1)
                    false
                ) catch e: exception then (
                    true
                )
                assert(negCaught)
                string(b)
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Arrays test failed : " + out.error());
        Assertions.assertEquals("\"[10, 20, 30, 40]\"", out.resultJson());
    }

    @Test
    public void nominalThrowCatchAndNominalOverloadDispatch() throws Exception {
        String code = """
                def DomainErr = interface (
                    def code: fn (): int
                )
                def NotFound = record (id: int) implements DomainErr (
                    def code = fn (): int -> (404)
                )
                def Denied = record (who: string) implements DomainErr (
                    def code = fn (): int -> (403)
                )
                def c1 = try (
                    throw NotFound(7)
                    0
                ) catch e: Denied then (
                    -1
                ) catch e: NotFound then (
                    e.id
                )
                assert(c1 == 7)

                def c2 = try (
                    throw Denied("guest")
                    0
                ) catch e: DomainErr then (
                    e.code()
                )
                assert(c2 == 403)

                def describe = overload (
                    fn (e: NotFound): string -> ("nf:" + e.id),
                    fn (e: Denied): string -> ("dn:" + e.who)
                )
                def handle = fn (x: NotFound | Denied): string -> (describe(x))
                assert(handle(NotFound(9)) == "nf:9")
                assert(handle(Denied("bob")) == "dn:bob")
                handle(NotFound(9))
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Nominal catch/overload test failed : " + out.error());
        Assertions.assertEquals("\"nf:9\"", out.resultJson());
    }

    @Test
    public void multiModuleStaticImportLinkingAndExecution(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        Files.writeString(dir.resolve("vec.ba"), """
                export def Vec2 = record (x: double, y: double) (
                    def plus = fn (o: Vec2): Vec2 -> (Vec2(this.x + o.x, this.y + o.y))
                    def lenSq = fn (): double -> (this.x * this.x + this.y * this.y)
                )
                export def ORIGIN = Vec2(0.0, 0.0)
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("shape.ba"), """
                def vec = import("vec.ba")
                export def Shape = interface (
                    def area: fn (): double
                )
                export def Box = record (size: vec.Vec2) implements Shape (
                    def area = fn (): double -> (this.size.x * this.size.y)
                )
                """, StandardCharsets.UTF_8);
        Path main = dir.resolve("main.ba");
        Files.writeString(main, """
                def vec = import("vec.ba")
                def shape = import("shape.ba")
                def v = vec.ORIGIN + vec.Vec2(3.0, 4.0)
                def s: shape.Shape = shape.Box(v)
                println("lenSq=", v.lenSq(), " area=", s.area())
                """, StandardCharsets.UTF_8);

        Path wasmOut = dir.resolve("app.wasm");
        Path mjsOut = dir.resolve("app.mjs");
        java.io.StringWriter errBuf = new java.io.StringWriter();
        int status = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", main.toString(), "-o", wasmOut.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()),
                new java.io.PrintWriter(errBuf), false);
        Assertions.assertEquals(0, status, "Multi-module compile failed : " + errBuf);

        Process p = new ProcessBuilder("node", mjsOut.toString()).redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "Multi-module node execution failed : " + stderr);
        Assertions.assertEquals("lenSq=25.0 area=12.0\n", stdout);
    }
}
