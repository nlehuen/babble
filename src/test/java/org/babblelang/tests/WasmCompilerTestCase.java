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
 * End-to-end tests for the WebAssembly 3.0 backend ({@code BIP025-I1}): scalars, control flow,
 * closures, {@code BIP024} tail calls, {@code BIP021} optional/named/overloaded calls,
 * {@code BIP028} exceptions &amp; 2-copy {@code finally}, and {@code -target wasm-js|wasm-bare} CLI.
 */
public class WasmCompilerTestCase {

    private static WasmCompiler.CompileResult compileRaw(String source, CompilationTarget target, int optLevel, boolean debugNames) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        List<BabbleScriptEngine.HirProgramModule> mods = engine.compileSourceToHir(source, "test.ba", target);
        List<WasmProgramLinker.ModuleInput> inputs = mods.stream()
                .map(m -> new WasmProgramLinker.ModuleInput(m.id().key(), m.id().displayName(), m.hir(), m.staticImportKeys()))
                .toList();
        boolean dumpWat = WasmCompiler.Options.defaults(target).dumpWat();
        WasmCompiler.Options opts = new WasmCompiler.Options(target, true, debugNames, dumpWat, false);
        return WasmCompiler.compile(inputs, opts, "test.wasm");
    }

    private static WasmCompiler.CompileResult compile(String source, CompilationTarget target, int optLevel) throws Exception {
        WasmCompiler.CompileResult res = compileRaw(source, target, optLevel, true);
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

    // ---------------------------------------------------------------- BIP025-I2 Object model, arrays & modules

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

    // ---------------------------------------------------------------- BIP025-I3 Strings, Collections, For, Appendable & Argon

    @Test
    public void stringsPortableApiParity() throws Exception {
        String code = """
                def s = "  Hello, Babble Wasm!  "
                assert(s.length() == 23)
                assert(not s.isEmpty())
                assert("".isEmpty())
                assert("   \\t\\n ".isBlank())
                assert(not s.isBlank())
                def t = s.trim()
                assert(t == "Hello, Babble Wasm!")
                assert(s.strip() == t)
                assert(t.charAt(0) == 'H')
                assert(t.substring(7) == "Babble Wasm!")
                assert(t.substring(7, 13) == "Babble")
                assert(t.indexOf("Babble") == 7)
                assert(t.indexOf(int('a')) == 8)
                assert(t.indexOf("xyz") == -1)
                assert(t.lastIndexOf(int('a')) == 15)
                assert(t.lastIndexOf("a") == 15)
                assert(t.startsWith("Hello"))
                assert(t.startsWith("Babble", 7))
                assert(t.endsWith("Wasm!"))
                assert(t.contains("Babble"))
                assert(t.replace('a', 'A') == "Hello, BAbble WAsm!")
                assert(t.replace("Wasm", "WASM3") == "Hello, Babble WASM3!")
                assert(t.toLowerCase() == "hello, babble wasm!")
                assert(t.toUpperCase() == "HELLO, BABBLE WASM!")
                assert("AbC".equalsIgnoreCase("abc"))
                assert("abc".compareTo("abd") < 0)
                assert("abc".compareTo("abc") == 0)
                assert("ab".concat("cd") == "abcd")
                assert("ha".repeat(3) == "hahaha")
                def parts = "a,b,c".split(",")
                assert(parts.size() == 3 and parts.get(0) == "a" and parts.get(2) == "c")
                def chars = "Hi".toCharArray()
                assert(chars.size() == 2 and chars.get(0) == 'H' and chars.get(1) == 'i')
                assert("hello".hashCode() == 99162322)
                t.substring(7, 13)
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Strings test failed : " + out.error());
        Assertions.assertEquals("\"Babble\"", out.resultJson());
    }

    @Test
    public void coreCollectionsListSetSortedSetMapSortedMapEntry() throws Exception {
        String code = """
                def xs = list(1, 2, 3)
                assert(xs is list and xs.size() == 3 and not xs.isEmpty())
                xs.add(4)
                xs.add(1, 10)
                assert(xs == list(1, 10, 2, 3, 4))
                assert(xs.get(1) == 10)
                assert(xs.set(1, 20) == 10)
                assert(xs.indexOf(20) == 1)
                assert(xs.contains(3) and not xs.contains(99))
                assert(xs.remove(1) == 20)
                assert(xs == list(1, 2, 3, 4))
                assert(xs.subList(1, 3) == list(2, 3))
                def copy = list(from: xs)
                assert(copy == xs and not same(copy, xs))
                def arrFromList: array of int = array(from: xs)
                assert(arrFromList == (array of int).of(1, 2, 3, 4))

                def s = set("b", "a", "b", "c")
                assert(s is set and not (s is sortedset))
                assert(s.size() == 3 and s.contains("a"))
                assert(s == set("c", "a", "b"))
                assert(string(s) == "[b, a, c]")
                assert(s.remove("a") and not s.contains("a"))

                def ss = sortedset(30, 10, 20, 10)
                assert(ss is set and ss is sortedset)
                assert(ss.size() == 3)
                assert(ss.first() == 10 and ss.last() == 30)
                assert(string(ss) == "[10, 20, 30]")

                def e = entry("k", 42)
                assert(e is entry and e.getKey() == "k" and e.getValue() == 42)
                assert(string(e) == "k=42")

                def m = map(entry("b", 2), entry("a", 1))
                assert(m is map and not (m is sortedmap))
                assert(m.size() == 2 and m.get("a") == 1 and m.get("z") == null)
                assert(m.getOrDefault("z", 99) == 99)
                assert(m.containsKey("b") and m.containsValue(2))
                assert(m.put("a", 10) == 1 and m.get("a") == 10)
                assert(m.keySet() == set("a", "b"))
                assert(m.values() == list(2, 10))
                assert(m == map(entry("a", 10), entry("b", 2)))
                assert(m.remove("b") == 2 and m.size() == 1)

                def sm = sortedmap(entry("z", 1), entry("a", 2), entry("m", 3))
                assert(sm is map and sm is sortedmap)
                assert(sm.firstKey() == "a" and sm.lastKey() == "z")
                assert(string(sm) == "{a=2, m=3, z=1}")
                string(sm)
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Collections test failed : " + out.error());
        Assertions.assertEquals("\"{a=2, m=3, z=1}\"", out.resultJson());
    }

    @Test
    public void forLoopsArraysCollectionsCustomIterablesAndBreakValue() throws Exception {
        String code = """
                def arrSum = 0
                for x in (array of int).of(10, 20, 30, 40) then (
                    if x == 20 then (continue)
                    arrSum += x
                )
                assert(arrSum == 80)

                def dSum = 0.0
                for d in (array of double).of(1.5, 2.5, 3.0) then (
                    dSum += d
                )
                assert(dSum == 7.0)

                def listSum = 0
                for v in list(1, 2, 3, 4, 5) then (
                    listSum += v
                )
                assert(listSum == 15)

                def found = for v in list(3, 7, 11, 15) then (
                    if v > 10 then (break v * 2)
                )
                assert(found == 22)

                ;; Custom Babble iterable (like Argon's Range)
                def RangeCursor = class (private def cur: int, private def stop: int) (
                    def hasNext = fn (): bool -> (this.cur < this.stop)
                    def next = fn mut (): int -> (def v = this.cur; this.cur += 1; v)
                )
                def Range = record (start: int, stop: int) (
                    def iterator = fn (): RangeCursor -> (RangeCursor(this.start, this.stop))
                )
                def rSum = 0
                for i in Range(1, 6) then (
                    rSum += i
                )
                assert(rSum == 15)

                ;; Closure capturing loop variable gets a fresh cell per iteration
                def fns: list of (fn (): int) = list()
                for i in Range(0, 3) then (
                    fns.add(fn (): int -> (i * 10))
                )
                def f0 = fns.get(0)
                def f2 = fns.get(2)
                assert(f0() == 0 and f2() == 20)
                (found as int) + rSum
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "For loops test failed : " + out.error());
        Assertions.assertEquals("\"37\"", out.resultJson());
    }

    @Test
    public void portableAppendableCustomOutputSink() throws Exception {
        String code = """
                def Sink = class () implements java.lang.Appendable (
                    def out = ""
                    def append = overload (
                        fn io mut (csq: java.lang.CharSequence?): java.lang.Appendable -> (
                            this.out += string(csq)
                            this
                        ),
                        fn io mut (csq: java.lang.CharSequence?, start: int, end: int): java.lang.Appendable -> (
                            this.out += string(csq).substring(start, end)
                            this
                        ),
                        fn io mut (c: char): java.lang.Appendable -> (
                            this.out += string(c)
                            this
                        )
                    )
                )
                def sink = Sink()
                def writeTo = fn io (out: java.lang.Appendable = STDOUT) -> (
                    println("line1:", 42, to: out)
                    print("line2", to: out)
                )
                writeTo(out: sink)
                assert(sink.out == "line1:42\\nline2")
                sink.out
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_JS, 1);
        Assertions.assertTrue(out.ok(), "Portable Appendable sink test failed : " + out.error());
        Assertions.assertEquals("\"line1:42\\nline2\"", out.resultJson());
        Assertions.assertEquals("", out.stdout(), "Custom sink must not write to STDOUT");
    }

    @Test
    public void argonRayTracerMiniSceneMatchesJvmByteForByte(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        Path argonRoot = Path.of("examples/raytracer-argon").toAbsolutePath().normalize();
        Path miniScript = dir.resolve("argon_mini.ba");
        String libPath = argonRoot.resolve("lib/raytracer.ba").toString().replace("\\", "/");
        // Small 16x9 render exercising all 15 unmodified Argon modules (Vec3, Ray, BVH, Sphere, Materials, Camera, Range, STDOUT)
        String miniCode = """
                def raytracer = import("%s")
                def rng: raytracer.Rng = raytracer.Rng(2026)
                def worldList: raytracer.HittableList = raytracer.Scenes.finalScene(rng: rng, gridRadius: 2)
                def world: raytracer.Hittable = raytracer.BVHNode.fromList(worldList)
                def camera: raytracer.Camera = raytracer.Camera(
                    aspectRatio: 16.0 / 9.0,
                    imageWidth: 16,
                    samplesPerPixel: 2,
                    maxDepth: 6,
                    vfov: 20.0,
                    lookfrom: raytracer.Vec3(13.0, 2.0, 3.0),
                    lookat: raytracer.Vec3(0.0, 0.0, 0.0),
                    vup: raytracer.Vec3(0.0, 1.0, 0.0),
                    defocusAngle: 0.6,
                    focusDist: 10.0
                )
                camera.render(world, rng)
                """.formatted(libPath);
        Files.writeString(miniScript, miniCode, StandardCharsets.UTF_8);

        // 1. Run on JVM
        java.io.StringWriter jvmOut = new java.io.StringWriter();
        java.io.StringWriter jvmErr = new java.io.StringWriter();
        int jvmStatus = org.babblelang.Main.run(
                new String[]{miniScript.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(jvmOut), new java.io.PrintWriter(jvmErr), false);
        Assertions.assertEquals(0, jvmStatus, "JVM Argon mini render failed : " + jvmErr);

        // 2. Compile to WebAssembly (wasm-js) and run in V8
        Path wasmOut = dir.resolve("argon_mini.wasm");
        Path mjsOut = dir.resolve("argon_mini.mjs");
        java.io.StringWriter compileErr = new java.io.StringWriter();
        int cmpStatus = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", miniScript.toString(), "-o", wasmOut.toString(), "-root", "/"},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()),
                new java.io.PrintWriter(compileErr), false);
        Assertions.assertEquals(0, cmpStatus, "Wasm Argon mini compile failed : " + compileErr);

        Process p = new ProcessBuilder("node", mjsOut.toString()).redirectErrorStream(false).start();
        String wasmStdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String wasmStderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "V8 Argon mini render failed : " + wasmStderr);

        // 3. Exact byte-for-byte PPM parity!
        Assertions.assertTrue(wasmStdout.startsWith("P3\n16 9\n255\n"), "Expected PPM header");
        Assertions.assertEquals(jvmOut.toString(), wasmStdout, "Argon mini PPM must match JVM byte-for-byte");
    }

    // ---------------------------------------------------------------- BIP025-I4 Host Imports, CLI Args, Debug Names & Canvas Demo

    @Test
    public void wasmHostImportAxiomsWithEffectsAndStrings() throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        String code = """
                axiom wasm.host.add: fn pure nothrow (a: int, b: int): int
                axiom wasm.host.greet: fn pure nothrow (who: string): string
                axiom wasm.host.log: fn io nothrow (msg: string): null

                def pureCalc = fn nothrow (x: int): int -> (
                    wasm.host.add(x, 10)
                )
                def pureGreet = fn nothrow (name: string): string -> (
                    wasm.host.greet(name)
                )
                def sum = pureCalc(32)
                def msg = pureGreet("Wasm")
                wasm.host.log(msg + "=" + sum)
                msg + ":" + sum
                """;
        WasmCompiler.CompileResult compiled = compile(code, CompilationTarget.WASM_JS, 1);
        String wat = org.babblelang.wasm.WatPrinter.print(compiled.module());
        Assertions.assertTrue(wat.contains("(import \"host\" \"add\""), "Expected host.add import in WAT");
        Assertions.assertTrue(wat.contains("(import \"host\" \"greet\""), "Expected host.greet import in WAT");
        Assertions.assertTrue(wat.contains("(import \"host\" \"log\""), "Expected host.log import in WAT");

        String hostJs = """
                {
                  host: {
                    add: (a, b) => a + b,
                    greet: (s) => "Hello " + s,
                    log: (s) => stdoutChunks.push("[LOG]" + s + "\\n"),
                  }
                }
                """;
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runJs(compiled.wasmBytes(), compiled.jsGlue(), List.of(), hostJs);
        Assertions.assertTrue(out.ok(), "Host import execution failed : " + out.error());
        Assertions.assertEquals("[LOG]Hello Wasm=42\n", out.stdout());
        Assertions.assertEquals("\"Hello Wasm:42\"", out.resultJson());

        // Effect enforcement : pure (default) function calling io wasm axiom must be rejected by TypeChecker
        String badEffectCode = """
                axiom wasm.host.log: fn io nothrow (msg: string): null
                def badPure = fn (s: string) -> (
                    wasm.host.log(s)
                )
                badPure("oops")
                """;
        Assertions.assertThrows(Exception.class, () -> compile(badEffectCode, CompilationTarget.WASM_JS, 1),
                "Calling io wasm axiom from pure function must fail effect checking");

        // Portability enforcement : wasm-bare must reject axiom wasm.* host imports
        WasmCompiler.CompileResult bareRes = compileRaw(code, CompilationTarget.WASM_BARE, 1, false);
        Assertions.assertFalse(bareRes.ok(), "Expected wasm-bare to reject axiom wasm.*");
        Assertions.assertTrue(bareRes.portabilityErrors().stream().anyMatch(d -> d.detail().contains("wasm.host.add")),
                "Expected wasm.host.add diagnostic : " + bareRes.portabilityErrors());
    }

    @Test
    public void wasmJsCliArgsPassing(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        Path script = dir.resolve("echo_args.ba");
        Files.writeString(script, """
                println("count=", args.size())
                for a in args then (
                    println("arg=", a)
                )
                """, StandardCharsets.UTF_8);
        Path wasmOut = dir.resolve("echo_args.wasm");
        Path mjsOut = dir.resolve("echo_args.mjs");
        java.io.StringWriter errBuf = new java.io.StringWriter();
        int status = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", script.toString(), "-o", wasmOut.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()),
                new java.io.PrintWriter(errBuf), false);
        Assertions.assertEquals(0, status, "Compile failed : " + errBuf);

        Process p = new ProcessBuilder("node", mjsOut.toString(), "alpha", "beta", "gamma")
                .redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "Node CLI execution failed : " + stderr);
        Assertions.assertEquals("count=3\narg=alpha\narg=beta\narg=gamma\n", stdout);
    }

    @Test
    public void wasmDebugNameSectionContainsLocalsTypesGlobalsAndFields() throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        String code = """
                def Point = record (px: double, py: double) (
                    def sum = fn (): double -> (
                        def res: double = this.px + this.py
                        res
                    )
                )
                Point(1.5, 2.5).sum()
                """;
        WasmCompiler.CompileResult compiled = compileRaw(code, CompilationTarget.WASM_BARE, 1, true);
        Assertions.assertTrue(compiled.ok(), "Portability errors : " + compiled.portabilityErrors());

        // Locate custom section 0 named "name" and inspect subsections 0, 1, 2, 4, 7, 10
        byte[] bytes = compiled.wasmBytes();
        java.util.Set<Integer> nameSubsections = new java.util.HashSet<>();
        int pos = 8; // skip \0asm + version
        while (pos < bytes.length) {
            int secId = bytes[pos++] & 0xFF;
            int[] lenPair = readU32(bytes, pos);
            int secLen = lenPair[0];
            pos = lenPair[1];
            int secEnd = pos + secLen;
            if (secId == 0) {
                int[] nameLenPair = readU32(bytes, pos);
                int nameLen = nameLenPair[0];
                int nameStart = nameLenPair[1];
                String secName = new String(bytes, nameStart, nameLen, StandardCharsets.UTF_8);
                if ("name".equals(secName)) {
                    int subPos = nameStart + nameLen;
                    while (subPos < secEnd) {
                        int subId = bytes[subPos++] & 0xFF;
                        int[] subLenPair = readU32(bytes, subPos);
                        nameSubsections.add(subId);
                        subPos = subLenPair[1] + subLenPair[0];
                    }
                }
            }
            pos = secEnd;
        }
        Assertions.assertTrue(nameSubsections.containsAll(List.of(0, 1, 2, 4, 7, 10)),
                "Expected module(0), func(1), local(2), type(4), global(7), field(10) subsections, got : " + nameSubsections);

        // Ensure V8 validates and runs the debug-named binary cleanly
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBabbleBare(bytes);
        Assertions.assertTrue(out.ok(), "Debug-named Wasm failed in V8 : " + out.error());
        Assertions.assertEquals("\"4.0\"", out.resultJson());
    }

    private static int[] readU32(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = data[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{result, pos};
            }
            shift += 7;
        }
    }

    @Test
    public void argonCanvasBrowserDemoCompilesAndRendersWithMockCanvas(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        Path canvasScript = Path.of("examples/raytracer-argon/wasm_canvas.ba").toAbsolutePath().normalize();
        Path wasmOut = dir.resolve("wasm_canvas.wasm");
        Path mjsOut = dir.resolve("wasm_canvas.mjs");
        java.io.StringWriter compileErr = new java.io.StringWriter();
        int status = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", canvasScript.toString(), "-o", wasmOut.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()),
                new java.io.PrintWriter(compileErr), false);
        Assertions.assertEquals(0, status, "Argon canvas demo compile failed : " + compileErr);

        byte[] wasmBytes = Files.readAllBytes(wasmOut);
        String mjsSource = Files.readString(mjsOut, StandardCharsets.UTF_8);
        String mockCanvasJs = """
                (() => {
                  let pixels = 0;
                  let rows = 0;
                  let badRgb = 0;
                  return {
                    canvas: {
                      setPixel: (x, y, r, g, b) => {
                        pixels++;
                        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) badRgb++;
                      },
                      present: (row) => {
                        rows++;
                        if (row === 8) stdoutChunks.push(`pixels=${pixels},rows=${rows},bad=${badRgb}\\n`);
                      },
                    }
                  };
                })()
                """;
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runJs(
                wasmBytes, mjsSource, List.of("16", "2", "4", "1"), mockCanvasJs);
        Assertions.assertTrue(out.ok(), "Argon canvas demo execution failed : " + out.error());
        Assertions.assertEquals("\"16x9\"", out.resultJson());
        Assertions.assertEquals("pixels=144,rows=9,bad=0\n", out.stdout());
    }

    // ---------------------------------------------------------------- BIP025 Review Regression Tests

    @Test
    public void finallyTwoCopyWithLocalCaptureOnUnwind() throws Exception {
        String code = """
                def log: list of int = list()
                def run = fn io (boom: bool): int -> (
                    try (
                        if boom then (throw exception("kaboom"))
                        10
                    ) finally (
                        def x = 5
                        def bump = fn io (): int -> (x += 1; x)
                        log.add(bump())
                        log.add(bump())
                    )
                )
                assert(run(false) == 10)
                def caught = false
                try (
                    run(true)
                ) catch e then (
                    caught = (string(e).contains("kaboom"))
                )
                assert(caught)
                assert(log == list(6, 7, 6, 7))
                log.size()
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Finally two-copy capture test failed : " + out.error());
        Assertions.assertEquals("\"4\"", out.resultJson());
    }

    @Test
    public void arrayTypeChecksBoxedNullableAndSetReturnsNull() throws Exception {
        String code = """
                def ia = (array of int).of(1, 2, 3)
                assert(ia is (array of int))
                assert(not (ia is (array of double)))
                assert(not (ia is (array of any)))
                assert(not (ia is java.lang.Iterable))
                assert(ia.set(1, 99) == null)
                assert(ia.get(1) == 99)

                def anyArr: any = ia
                assert((anyArr as (array of int)).get(1) == 99)

                def sa = (array of string).of("a", "b")
                assert(sa is (array of string))
                assert(sa is (array of any))
                assert(sa.set(0, "z") == null)
                assert(sa.get(0) == "z")

                def nia = (array of int?)(2)
                nia.set(0, 7)
                nia.set(1, null)
                assert(nia is (array of int?))
                assert(not (nia is (array of int)))
                assert(nia.get(0) == 7 and nia.get(1) == null)

                def nested = (array of (array of int)).of(ia)
                assert(nested is (array of (array of int)))
                assert(nested.get(0).get(1) == 99)

                ;; Covariant RefArray store check rejects wrong runtime element type
                def badStoreFailed = false
                try (
                    def cov: array of any = sa
                    cov.set(0, 123)
                ) catch e then (
                    badStoreFailed = true
                )
                assert(badStoreFailed)
                nia.get(0)
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Array type check & set test failed : " + out.error());
        Assertions.assertEquals("\"7\"", out.resultJson());
    }

    @Test
    public void userReplaceSafeCallNamedArgsAndOptDefaultClosureCapture() throws Exception {
        String code = """
                def Box = class (def v: int) (
                    def replace = fn mut (a: int, b: int): int -> (this.v = a + b; this.v)
                    def combine = fn (a: int, b: int): int -> (a * 10 + b)
                )
                def b: Box? = Box(1)
                assert(b?.replace(3, 4) == 7)
                assert(b?.combine(b: 2, a: 5) == 52)
                def nb: Box? = null
                assert(nb?.combine(b: 2, a: 5) == null)

                def st: (set of int)? = set(1, 2, 3)
                assert(st?.size() == 3)
                def mp: (map of (string, int))? = map(entry("a", 1))
                assert(mp?.size() == 1 and mp?.isEmpty() == false)

                ;; Optional-default closure shares the parameter capture cell
                def mk = fn volatile (x: int = 1, reader: fn volatile (): int = fn volatile (): int -> (x)): int -> (
                    x = x + 10
                    reader()
                )
                assert(mk(5) == 15)
                assert(mk() == 11)

                ;; Same for class constructor parameter
                def Holder = class (x: int = 2, def getter: fn volatile (): int = fn volatile (): int -> (x)) (
                    x = x + 20
                    def val: int = x
                )
                def h = Holder(3)
                assert(h.val == 23 and h.getter() == 23)
                h.getter()
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "User replace / safe call / opt-default capture failed : " + out.error());
        Assertions.assertEquals("\"23\"", out.resultJson());
    }

    @Test
    public void dynamicOverloadSetAndOverloadedOperators() throws Exception {
        String code = """
                def ov = overload (
                    fn (x: int): string -> ("int:" + x),
                    fn (x: string): string -> ("str:" + x),
                    fn (x: int, y: int): string -> ("pair:" + (x + y))
                )
                def callOne = fn (f: fn (x: int): string, v: int): string -> (f(v))
                assert(callOne(ov, 42) == "int:42")
                def u1: int | string = 42
                def u2: int | string = "hi"
                assert(ov(u1) == "int:42")
                assert(ov(u2) == "str:hi")
                assert(ov(3, 4) == "pair:7")

                def Vec = record (x: int) (
                    def plus = overload (
                        fn (o: Vec): Vec -> (Vec(this.x + o.x)),
                        fn (n: int): Vec -> (Vec(this.x + n))
                    )
                )
                def v1 = Vec(10) + Vec(5)
                def v2 = v1 + 3
                assert(v2.x == 18)
                v2.x
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Dynamic overload & operator test failed : " + out.error());
        Assertions.assertEquals("\"18\"", out.resultJson());
    }

    @Test
    public void forwardReferenceRejectedAtRuntime() throws Exception {
        String code = """
                def bad = fn (): int -> (later())
                def caught = false
                try (
                    bad()
                ) catch e then (
                    caught = true
                )
                def later = fn (): int -> (99)
                assert(caught)
                assert(later() == 99)
                later()
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Forward reference test failed : " + out.error());
        Assertions.assertEquals("\"99\"", out.resultJson());
    }

    @Test
    public void stringSplitAndCollectionSafetyInvariants() throws Exception {
        String code = """
                ;; String.split parity: empty delimiter, limit == 0, limit > 0, limit < 0
                assert("ab".split("") == (array of string).of("a", "b"))
                assert("a,b,,".split(",") == (array of string).of("a", "b"))
                assert("a,b,,".split(",", -1) == (array of string).of("a", "b", "", ""))
                assert("a,b,c".split(",", 2) == (array of string).of("a", "b,c"))
                assert("".split(",") == (array of string).of(""))

                ;; Self-referential collection cycle formatting
                def cyc: list of any = list()
                def holder = list(cyc)
                cyc.add(holder.get(0))
                assert(string(cyc) == "[(this Collection)]")

                def cm: map of (string, any) = map()
                def mholder = list(cm)
                cm.put("self", mholder.get(0))
                assert(string(cm) == "{self=(this Map)}")

                ;; Array format recovers depth after element toString throws
                def Boom = class () (
                    def toString = fn (): string -> (throw exception("boomFmt"))
                )
                def badArr = (array of any).of(Boom())
                def threw = false
                try (
                    string(badArr)
                ) catch e then (
                    threw = (string(e).contains("boomFmt"))
                )
                assert(threw)
                assert(string((array of int).of(1, 2)) == "[1, 2]")

                ;; list.remove(byte(0)) removes by index
                def xs = list(10, 20, 30)
                assert(xs.remove(byte(0)) == 10)
                assert(xs == list(20, 30))

                ;; keySet.add throws, entry.setValue updates backing map
                def m = map(entry("k", 1))
                def ks = m.keySet()
                def ksThrew = false
                try (
                    ks.add("z")
                ) catch e then (
                    ksThrew = true
                )
                assert(ksThrew)
                for e in m.entrySet() then (
                    def prev = e.setValue(42)
                    assert(prev == 1)
                )
                assert(m.get("k") == 42)

                ;; set(from: m.keySet()) must not reuse/corrupt the backing map keySet view
                def ksCopy = set(from: m.keySet())
                ksCopy.add("other")
                assert(m.size() == 1 and ksCopy.size() == 2)

                ;; sortedset natural ordering from list
                def asc = (sortedset of int)(from: list(3, 1, 2))
                assert(string(asc) == "[1, 2, 3]")

                ;; subList reuse must not corrupt parent list
                def base = list(1, 2, 3, 4)
                def sub = base.subList(1, 3)
                def copied = list(from: sub)
                copied.set(0, 99)
                assert(base == list(1, 2, 3, 4))

                ;; STDOUT.append returns Appendable and writes output
                STDOUT.append("ok")
                m.get("k")
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_JS, 1);
        Assertions.assertTrue(out.ok(), "String split & collection invariants test failed : " + out.error());
        Assertions.assertEquals("\"42\"", out.resultJson());
        Assertions.assertEquals("ok", out.stdout());
    }

    @Test
    public void unionAsNumericWideningAndBareScientificDouble() throws Exception {
        String code = """
                def x: any = 42
                def u = x as (string | double)
                assert(u is double and u == 42.0)

                def big = 1.0e20
                def bigStr = string(big)
                assert(bigStr.contains("E"))
                assert(string(1.0e-5).contains("E-"))
                bigStr
                """;
        NodeWasmRunner.RunOutcome out = runWasm(code, CompilationTarget.WASM_BARE, 1);
        Assertions.assertTrue(out.ok(), "Union widening & bare scientific double failed : " + out.error());
        Assertions.assertTrue(out.resultJson().contains("E"), "Expected scientific notation, got : " + out.resultJson());
    }

    @Test
    public void jsGlueFormatFloatParseDoubleAndImportMerging(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable(), "Node.js not available");
        String glue = org.babblelang.wasm.WasmJsGlueGen.generate("my \"odd\"\nfile.wasm");
        Path mjs = dir.resolve("glue_check.mjs");
        Files.writeString(mjs, glue, StandardCharsets.UTF_8);

        String checkScript = """
                import { formatJavaFloat, parseJavaDouble, createBabbleImports } from '%s';
                if (formatJavaFloat(1.401298464324817e-45) !== "1.4E-45") throw new Error("Float.MIN_VALUE mismatch: " + formatJavaFloat(1.401298464324817e-45));
                if (formatJavaFloat(2.802596928649634e-45) !== "2.8E-45") throw new Error("2*MIN_VALUE mismatch: " + formatJavaFloat(2.802596928649634e-45));
                const [v1, ok1] = parseJavaDouble("\\u00A01.5");
                if (ok1 !== 0) throw new Error("NBSP should be rejected, got ok=" + ok1 + " v=" + v1);
                const [v2, ok2] = parseJavaDouble(" \\t1.5\\n ");
                if (ok2 !== 1 || v2 !== 1.5) throw new Error("ASCII whitespace should parse, got " + ok2 + "," + v2);
                const imps = createBabbleImports({ imports: { "babble:env": { extra_hook: () => 7 } } });
                if (typeof imps["babble:env"].write_stdout !== "function" || imps["babble:env"].extra_hook() !== 7) {
                  throw new Error("babble:env merge failed");
                }
                console.log("GLUE_OK");
                """.formatted(mjs.toUri().toString());
        Process p = new ProcessBuilder("node", "--input-type=module", "-e", checkScript)
                .redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        Assertions.assertEquals(0, exit, "JS glue check failed : " + stderr);
        Assertions.assertEquals("GLUE_OK\n", stdout);
    }

    @Test
    public void babblecPortabilityErrorHasSingleErrorPrefix(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad_port.ba");
        Files.writeString(bad, "def f = fn io () -> (java.io.File(\"x\"))\nf()\n", StandardCharsets.UTF_8);
        Path outWasm = dir.resolve("bad_port.wasm");
        java.io.StringWriter errBuf = new java.io.StringWriter();
        int status = org.babblelang.Main.run(
                new String[]{"-compile", "-target", "wasm-js", bad.toString(), "-o", outWasm.toString()},
                new java.io.BufferedReader(new java.io.StringReader("")),
                new java.io.PrintWriter(new java.io.StringWriter()),
                new java.io.PrintWriter(errBuf), false);
        Assertions.assertEquals(1, status);
        String err = errBuf.toString();
        Assertions.assertTrue(err.contains("Error : "), "Expected Error prefix : " + err);
        Assertions.assertFalse(err.contains("Error : Error : "), "Duplicate Error prefix : " + err);
    }
}
