package org.babblelang.tests;

import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.ArraySupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for BIP020-I3 : creating arrays ({@code (array of T)(n)}, {@code array(n)},
 * {@code .of(...)}, {@code from:}), {@code size()}/{@code get}/{@code set}, {@code .length}
 * removal, content equality/hashing/printing, {@code ArrayStoreException} parity, and effects.
 */
public class Bip020P3ArrayOpsTestCase {

    private static List<String> diagnosticsOf(String source) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        StringWriter err = new StringWriter();
        engine.getContext().setErrorWriter(err);
        try {
            engine.compile(source);
        } catch (ScriptException ignored) {
        }
        String raw = err.toString().strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        return List.of(raw.split("\\R"));
    }

    private static void assertNoErrors(String source) {
        List<String> diags = diagnosticsOf(source);
        List<String> errors = diags.stream().filter(d -> d.startsWith("Error :")).toList();
        Assertions.assertEquals(List.of(), errors, "Unexpected errors for:\n" + source);
    }

    private static void assertDiagnostics(String source, String... expectedSubstrings) {
        List<String> diags = diagnosticsOf(source);
        Assertions.assertEquals(expectedSubstrings.length, diags.size(),
                "Expected " + expectedSubstrings.length + " diagnostics, got " + diags + " for:\n" + source);
        for (int i = 0; i < expectedSubstrings.length; i++) {
            Assertions.assertTrue(diags.get(i).contains(expectedSubstrings[i]),
                    "Diagnostic #" + i + " mismatch: expected '" + expectedSubstrings[i] + "', got '" + diags.get(i) + "'");
        }
    }

    private static void assertRejected(String source, String expectedSubstring) {
        List<String> diags = diagnosticsOf(source);
        Assertions.assertFalse(diags.isEmpty(), "Expected error containing '" + expectedSubstring + "', got none for:\n" + source);
        Assertions.assertTrue(diags.stream().anyMatch(d -> d.contains(expectedSubstring)),
                "Expected error containing '" + expectedSubstring + "', got " + diags + " for:\n" + source);
    }

    private record ModeRun(Object value, String output, String error) {
    }

    private static ModeRun runMode(String mode, String opt, String source) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (opt != null) {
            engine.put("babble.opt", opt);
        }
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        StringWriter out = new StringWriter();
        context.setWriter(out);
        try {
            Object value = engine.eval(source, context);
            return new ModeRun(value, out.toString(), null);
        } catch (ScriptException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new ModeRun(null, out.toString(), ArraySupport.formatThrowable(cause));
        }
    }

    private static void assertAllModesEqual(String source, Object expected) {
        ModeRun interp = runMode("interpret", null, source);
        ModeRun compO1 = runMode("compile", "1", source);
        ModeRun compO0 = runMode("compile", "0", source);
        Assertions.assertNull(interp.error(), "interpret error: " + interp.error());
        Assertions.assertNull(compO1.error(), "compile -O1 error: " + compO1.error());
        Assertions.assertNull(compO0.error(), "compile -O0 error: " + compO0.error());
        Assertions.assertEquals(expected, interp.value(), "interpret value");
        Assertions.assertEquals(expected, compO1.value(), "compile -O1 value");
        Assertions.assertEquals(expected, compO0.value(), "compile -O0 value");
        Assertions.assertEquals(interp.output(), compO1.output(), "stdout parity O1");
        Assertions.assertEquals(interp.output(), compO0.output(), "stdout parity O0");
    }

    private static void assertAllModesThrow(String source, String expectedErrorSubstring) {
        ModeRun interp = runMode("interpret", null, source);
        ModeRun compO1 = runMode("compile", "1", source);
        ModeRun compO0 = runMode("compile", "0", source);
        Assertions.assertNotNull(interp.error(), "expected interpret error");
        Assertions.assertNotNull(compO1.error(), "expected compile -O1 error");
        Assertions.assertNotNull(compO0.error(), "expected compile -O0 error");
        Assertions.assertTrue(interp.error().contains(expectedErrorSubstring), "interpret got: " + interp.error());
        Assertions.assertTrue(compO1.error().contains(expectedErrorSubstring), "compile -O1 got: " + compO1.error());
        Assertions.assertTrue(compO0.error().contains(expectedErrorSubstring), "compile -O0 got: " + compO0.error());
        Assertions.assertEquals(interp.error(), compO1.error(), "error parity interpret vs O1");
        Assertions.assertEquals(interp.error(), compO0.error(), "error parity interpret vs O0");
    }

    private static List<Integer> collectOpcodes(byte[] classBytes) {
        List<Integer> opcodes = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        opcodes.add(opcode);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        opcodes.add(opcode);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        opcodes.add(opcode);
                    }
                };
            }
        }, 0);
        return opcodes;
    }

    @Test
    public void testArrayCreationAllFormsAndBytecode() throws Exception {
        String source = """
                def Point = record (x: int) ()
                def Shape = interface ( def area: fn (): int )
                def Circle = class (def r: int) implements Shape ( def area = fn (): int -> ( this.r * this.r ) )
                def Square = class (def s: int) implements Shape ( def area = fn (): int -> ( this.s * this.s ) )
                def Ints = type array of int

                ;; 1. (array of T)(n) across all 8 primitives + nullable refs
                def ab = (array of byte)(2)
                def ash = (array of short)(2)
                def ai = (array of int)(3)
                def al = (array of long)(2)
                def af = (array of float)(2)
                def ad = (array of double)(2)
                def abo = (array of bool)(2)
                def ach = (array of char)(2)
                def astr = (array of string?)(2)
                def apt = (array of Point?)(2)

                assert(ab.get(0) == 0 and ash.get(0) == 0 and ai.get(2) == 0 and al.get(1) == 0L)
                assert(af.get(0) == 0.0f and ad.get(0) == 0.0 and abo.get(0) == false and ach.get(0) == '\\0')
                assert(astr.get(0) == null and apt.get(1) == null)

                ;; 2. array(n) with expected type and transparent alias
                def expInts: array of int = array(4)
                def aliasInts: Ints = array(3)
                def optDoubles: (array of double)? = array(2)
                assert(expInts.size() == 4 and aliasInts.size() == 3 and optDoubles!.size() == 2)

                ;; 3. .of(...) explicit, inferred, expected, widened, empty
                def of1 = (array of int).of(10, 20, 30)
                def of2 = array.of(1, 2.5)
                def of3: array of double = array.of(3, 4)
                def of4 = array.of("a", null)
                def of5: array of Shape = array.of(Circle(2), Square(3))
                def ofEmpty = (array of string).of()
                def ofEmptyExp: array of Point = array.of()
                assert(of1.size() == 3 and of1.get(1) == 20)
                assert(of2 is array of double and of2.get(0) == 1.0 and of2.get(1) == 2.5)
                assert(of3.get(0) == 3.0 and of4.get(0) == "a" and of4.get(1) == null)
                assert(of5.get(0).area() + of5.get(1).area() == 13)
                assert(ofEmpty.size() == 0 and ofEmptyExp.size() == 0)

                ;; 4. from: list, set, array, frozen array, numeric widening, nested arrays, local classes
                def fromList = (array of Point)(from: list(Point(7), Point(8)))
                def fromSet = array(from: set("x", "y"))
                def fromArr = (array of double)(from: of1)
                def fromBoxedWiden = (array of double?)(from: list(1, 2))
                def frz = freeze(array.of(5, 6))
                def fromFrz = array(from: frz)
                def nested = (array of (array of Point)).of(fromList)
                def mkLocal = fn (): int -> (
                    def LocalBox = class (def v: int) ()
                    def la = (array of LocalBox).of(LocalBox(9))
                    la.get(0).v
                )
                assert(fromList.get(1).x == 8 and fromSet.size() == 2)
                assert(fromArr.get(2) == 30.0 and fromBoxedWiden.get(1) == 2.0 and fromFrz.get(0) == 5)
                assert(nested.get(0).get(0).x == 7 and mkLocal() == 9)

                ;; 5. Generic reifiable array of (list of T)
                def mkLists of T = fn (): array of (list of T) -> ( (array of (list of T)).of() )
                assert((mkLists of int)().size() == 0)
                "ok"
                """;
        assertNoErrors(source);
        assertAllModesEqual(source, "ok");

        BabbleScriptEngine compileEngine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        compileEngine.put(BabbleScriptEngine.MODE, "compile");
        CompiledBabbleScript compiled = (CompiledBabbleScript) compileEngine.compile(source);
        List<Integer> allOps = new ArrayList<>();
        for (byte[] bytes : compiled.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), compiled.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
            allOps.addAll(collectOpcodes(bytes));
        }
        Assertions.assertTrue(allOps.contains(Opcodes.NEWARRAY), "Expected NEWARRAY");
        Assertions.assertTrue(allOps.contains(Opcodes.ANEWARRAY), "Expected ANEWARRAY");
        Assertions.assertTrue(allOps.contains(Opcodes.ARRAYLENGTH), "Expected ARRAYLENGTH");
        Assertions.assertTrue(allOps.contains(Opcodes.IALOAD), "Expected IALOAD");
        Assertions.assertTrue(allOps.contains(Opcodes.IASTORE), "Expected IASTORE");
        Assertions.assertTrue(allOps.contains(Opcodes.DALOAD), "Expected DALOAD");
        Assertions.assertTrue(allOps.contains(Opcodes.DASTORE), "Expected DASTORE");
        Assertions.assertTrue(allOps.contains(Opcodes.BALOAD), "Expected BALOAD");
        Assertions.assertTrue(allOps.contains(Opcodes.CALOAD), "Expected CALOAD");
        Assertions.assertTrue(allOps.contains(Opcodes.AALOAD), "Expected AALOAD");
        Assertions.assertTrue(allOps.contains(Opcodes.AASTORE), "Expected AASTORE");
    }

    @Test
    public void testStaticRejectionsAndLengthRemoval() {
        assertDiagnostics("""
                def Point = class () ()
                def a1 = (array of string)(10)
                def a2 = (array of Point)(5)
                def a3 = (array of any)(2)
                def a4 = array(10)
                def a5 = array.of()
                def a6 = (array of int)()
                def a7 = (array of int)(1, 2)
                def a8 = (array of int)(fill: 0)
                def a9 = (array of int)(capacity: 5)
                def a10 = array.of(x: 1)
                def ok = array.of(1, 2)
                def badLen = ok.length
                ok.length = 3
                def badStatic = (array of int).nope
                """,
                "No default element : array of string has non-null elements",
                "No default element : array of Point has non-null elements",
                "No default element : array of any has non-null elements",
                "Missing type : cannot infer type arguments for array",
                "Missing type : cannot infer type arguments for array",
                "Missing parameter : size",
                "Too many parameters : 1 declared, 2 given",
                "No such parameter : fill",
                "No such parameter : capacity",
                "No such parameter : x",
                "No such name in array of int : length (use size())",
                "No such field in array of int : length (use size())",
                "No such name in array(int) : nope");

        assertDiagnostics("""
                def bad1 of T = fn (x: T) -> ( (array of T)(2) )
                def bad2 of T = fn (shared x: T) -> ( (array of T).of(x) )
                def bad3 of T = fn () -> ( (array of T?)(2) )
                def bad4 of T = fn () -> ( (array of (array of T?))(2) )
                def bad5 of T = fn (xs: list of T) -> ( (array of T)(from: xs) )
                def bad6 of T = fn (shared x: T) -> ( (array of (T | string)).of(x) )
                """,
                "Generic array creation : array of T",
                "Generic array creation : array of T",
                "Generic array creation : array of T?",
                "Generic array creation : array of (array of T?)",
                "Generic array creation : array of T",
                "Generic array creation : array of (T | string)");
    }

    @Test
    public void testGetSetJavaInterfacesAndRuntimeExceptionParity() {
        String source = """
                def log = list of string()
                def Task = class (def label: string) implements java.lang.Runnable (
                    def run = fn io () -> ( log.add(this.label) ; () )
                )
                def tasks = (array of java.lang.Runnable).of(Task("one"), fn io () -> ( log.add("two") ; () ))
                def res: null = tasks.set(1, fn io () -> ( log.add("replaced") ; () ))
                assert(res == null)
                tasks.get(index: 0).run()
                tasks.get(1).run()
                tasks.set(value: Task("zero"), index: 0)
                tasks.get(0).run()

                ;; Bound method values & safe navigation
                def nums = array.of(10, 20, 30)
                def getter = nums.get
                def sizer = nums.size
                def maybeNums: (array of int)? = null
                assert(getter(2) == 30 and sizer() == 3)
                assert(maybeNums?.size() == null and maybeNums?.get(0) == null)

                ;; Nested covariant Babble array store that succeeds (Circle[] into Shape[][])
                def Shape = interface ( def area: fn (): int )
                def Circle = class (def r: int) implements Shape ( def area = fn (): int -> ( this.r ) )
                def outer: array of (array of Shape) = (array of (array of Shape)).of((array of Shape).of(Circle(1)))
                outer.set(0, (array of Circle).of(Circle(5)))
                "" + log + ":" + outer.get(0).get(0).area()
                """;
        assertNoErrors(source);
        assertAllModesEqual(source, "[one, replaced, zero]:5");

        assertAllModesThrow("(array of int)(-3)", "java.lang.NegativeArraySizeException");
        assertAllModesThrow("def a = array.of(1, 2) ; a.get(5)", "java.lang.ArrayIndexOutOfBoundsException");
        assertAllModesThrow("def a = array.of(1, 2) ; a.set(-1, 9)", "java.lang.ArrayIndexOutOfBoundsException");

        // Exact runtime ArrayStoreException parity: Java String[], Babble Circle[], nested Circle[][]
        assertAllModesThrow("""
                def s = array.of("a", "b")
                def anyArr: array of any = s
                anyArr.set(0, 123)
                """, "java.lang.ArrayStoreException: java.lang.Integer");

        assertAllModesThrow("""
                def Shape = interface ( def m: fn (): int )
                def Circle = class () implements Shape ( def m = fn (): int -> ( 1 ) )
                def Square = class () implements Shape ( def m = fn (): int -> ( 2 ) )
                def cs = (array of Circle).of(Circle())
                def sh: array of Shape = cs
                sh.set(0, Square())
                """, "java.lang.ArrayStoreException: Square");

        assertAllModesThrow("""
                def Shape = interface ( def m: fn (): int )
                def Circle = class () implements Shape ( def m = fn (): int -> ( 1 ) )
                def Square = class () implements Shape ( def m = fn (): int -> ( 2 ) )
                def grid = (array of (array of Circle)).of((array of Circle).of(Circle()))
                def anyGrid: array of any = grid
                anyGrid.set(0, (array of Square).of())
                """, "java.lang.ArrayStoreException: array of Square");
    }

    @Test
    public void testContentEqualityHashCodeToStringPrintingRecordsAndCycles() {
        String source = """
                def Packet = record (tag: string, data: array of int) ()
                def a = array.of(1, 2, 3)
                def b = array.of(1, 2, 3)
                def c = array.of(1, 2, 4)
                assert(a == b and not (a != b) and a != c)
                assert(a.equals(b) and not a.equals(c) and a.hashCode() == b.hashCode())
                assert(not same(a, b) and same(a, a))

                ;; Numeric cross-type equality in Object[], NaN and signed zeros
                def o1 = (array of any).of(1, "x")
                def o2 = (array of any).of(1.0, "x")
                assert(o1 == o2 and o1.hashCode() == o2.hashCode())
                def nanArr1 = array.of(0.0 / 0.0)
                def nanArr2 = array.of(0.0 / 0.0)
                assert(nanArr1 == nanArr2 and nanArr1.hashCode() == nanArr2.hashCode())
                def pz = array.of(0.0)
                def nz = array.of(-0.0)
                assert(pz != nz)

                ;; Records holding arrays compare, hash, and format by contents
                def p1 = Packet("ok", array.of(7, 8))
                def p2 = Packet("ok", array.of(7, 8))
                assert(p1 == p2 and set(p1, p2).size() == 1)
                assert(java.util.Objects.hashCode(p1 as java.lang.Object) == java.util.Objects.hashCode(p2 as java.lang.Object))
                assert("" + p1 == "Packet(tag: ok, data: [7, 8])")

                ;; Printing, string +, nested arrays, and cycle guard [...]
                def nested = array.of(array.of(1, 2), array.of(3))
                def cyc = (array of any?)(1)
                cyc.set(0, cyc)
                println(a, "|", nested.toString(), "|", "cyc=" + cyc, "|", string(b))

                ;; Java identity preserved in Java sets/maps and String.valueOf
                def s = set(a, b)
                def jStr = java.lang.String.valueOf(a as java.lang.Object)
                assert(s.size() == 2 and jStr.startsWith("[I@"))
                "done"
                """;
        assertNoErrors(source);
        ModeRun interp = runMode("interpret", null, source);
        assertAllModesEqual(source, "done");
        Assertions.assertEquals("[1, 2, 3]|[[1, 2], [3]]|cyc=[[...]]|[1, 2, 3]\n", interp.output());
    }

    @Test
    public void testArrayEffectsProvenanceAndFrozen() {
        String validEffects = """
                def Box = class (def v: int) ()
                def outer = array.of(10, 20, 30)
                def frzOuter = freeze(array.of(10, 20, 30))

                ;; size() is pure deterministic nothrow even on captured non-reassigned outer array
                def outerSize: fn nothrow (): int = fn nothrow (): int -> ( outer.size() )
                def paramGet: fn (a: array of int): int = fn (a: array of int): int -> ( a.get(0) )
                def frzGet: fn (): int = fn (): int -> ( frzOuter.get(0) )
                def mutSet: fn (mut a: array of int) = fn (mut a: array of int) -> ( a.set(0, 99) ; () )
                def outerReadVol: fn volatile (): int = fn volatile (): int -> ( outer.get(0) )
                def outerEqVol: fn volatile (): bool = fn volatile (): bool -> ( outer == frzOuter )
                def outerLoopVol: fn volatile (): int = fn volatile (): int -> (
                    var sum = 0
                    for x in outer then ( sum += x )
                    sum
                )
                def mkUnique: fn (): unique array of Box = fn (): unique array of Box -> ( array.of(Box(1)) )
                def frzBoxes = freeze(mkUnique())
                def b: frozen Box = frzBoxes.get(0)
                outerSize() + paramGet(outer) + frzGet() + b.v
                """;
        assertNoErrors(validEffects);
        assertAllModesEqual(validEffects, 3 + 10 + 10 + 1);

        assertRejected("def outer = array.of(1, 2)\ndef badRead: fn (): int = fn (): int -> ( outer.get(0) )", "volatile");
        assertRejected("def outer = array.of(1, 2)\ndef frz = freeze(array.of(1, 2))\ndef badEq: fn (): bool = fn (): bool -> ( outer == frz )", "volatile");
        assertRejected("def outer = array.of(1, 2)\ndef badStr: fn (): string = fn (): string -> ( \"\" + outer )", "volatile");
        assertRejected("def outer = array.of(1, 2)\ndef badFor: fn (): int = fn (): int -> ( var s = 0 ; for x in outer then ( s += x ) ; s )", "volatile");
        assertRejected("def badMutParam = fn (a: array of int) -> ( a.set(0, 5) )", "mut");
        assertRejected("def frz = freeze(array.of(1, 2))\ndef badFrzSet = fn () -> ( frz.set(0, 5) )", "Cannot call mut method set on frozen value");
        assertRejected("def Box = class (def v: int) ()\ndef sharedBox = Box(1)\ndef badUnique: fn (): unique array of Box = fn (): unique array of Box -> ( array.of(sharedBox) )", "unique");
        assertRejected("def Box = class (def v: int) ()\ndef badEscape = fn (b: Box) -> ( array.of(b) )", "Borrowed parameter b escapes");
    }
}
