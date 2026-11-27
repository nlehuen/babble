package org.babblelang.tests;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.List;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Tests for BIP020-I4 : generic array reference-binding and reifiability rules (§2.3, §2.6) across
 * Babble and Java type variables, plus RM003 bridges for generic Java interface array methods
 * (§2.8).
 */
public class Bip020P4ArrayGenericsTestCase {

    private static List<String> diagnosticsOf(String source) {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
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

    private static void assertDiagnostics(String source, String... expectedSubstrings) {
        List<String> diags = diagnosticsOf(source);
        Assertions.assertEquals(
                expectedSubstrings.length,
                diags.size(),
                "Expected "
                        + expectedSubstrings.length
                        + " diagnostics, got "
                        + diags
                        + " for:\n"
                        + source);
        for (int i = 0; i < expectedSubstrings.length; i++) {
            Assertions.assertTrue(
                    diags.get(i).contains(expectedSubstrings[i]),
                    "Diagnostic #"
                            + i
                            + " mismatch: expected '"
                            + expectedSubstrings[i]
                            + "', got '"
                            + diags.get(i)
                            + "'");
        }
    }

    private static String evalOut(String mode, String opt, String source) throws Exception {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (opt != null) {
            engine.put("babble.opt", opt);
        }
        if ("compile".equals(mode)) {
            CompiledBabbleScript compiled = (CompiledBabbleScript) engine.compile(source);
            for (byte[] bytes : compiled.getClasses().values()) {
                CheckClassAdapter.verify(
                        new ClassReader(bytes),
                        compiled.getClass().getClassLoader(),
                        false,
                        new PrintWriter(new StringWriter()));
            }
        }
        ScriptContext ctx = new SimpleScriptContext();
        ctx.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        StringWriter out = new StringWriter();
        ctx.setWriter(out);
        engine.eval(source, ctx);
        return out.toString();
    }

    private static Object evalResult(String mode, String opt, String source) throws Exception {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (opt != null) {
            engine.put("babble.opt", opt);
        }
        return engine.eval(source);
    }

    private static void assertAllModesOut(String source, String expectedOut) throws Exception {
        String interp = evalOut("interpret", null, source);
        String compO0 = evalOut("compile", "0", source);
        String compO1 = evalOut("compile", "1", source);
        Assertions.assertEquals(expectedOut, interp, "interpret output");
        Assertions.assertEquals(expectedOut, compO0, "compile -O0 output");
        Assertions.assertEquals(expectedOut, compO1, "compile -O1 output");
    }

    private static String evalRuntimeError(String mode, String opt, String source) {
        ScriptException se =
                Assertions.assertThrows(ScriptException.class, () -> evalOut(mode, opt, source));
        return se.getMessage();
    }

    private static void assertSameRuntimeError(String source, String expectedSubstring) {
        String interp = evalRuntimeError("interpret", null, source);
        String compO0 = evalRuntimeError("compile", "0", source);
        String compO1 = evalRuntimeError("compile", "1", source);
        Assertions.assertTrue(interp.contains(expectedSubstring), "interpret: " + interp);
        Assertions.assertTrue(compO0.contains(expectedSubstring), "compile -O0: " + compO0);
        Assertions.assertTrue(compO1.contains(expectedSubstring), "compile -O1: " + compO1);
    }

    @Test
    public void testGenericBabbleFunctionsOverReferenceArrays() throws Exception {
        String script =
                """
                def Point = record (x: int, y: int) ()
                def Shape = interface (def area: fn (): int)
                def Named = interface (def name: fn (): string)
                def Box = class (def w: int, def label: string) implements Shape, Named (
                    def area = fn (): int -> (this.w * this.w)
                    def name = fn (): string -> (this.label)
                )

                def first of T = fn io (a: array of T): T -> (a.get(0))
                def swap of T = fn io (mut shared a: array of T): array of T -> (
                    def tmp = a.get(0)
                    a.set(0, a.get(1))
                    a.set(1, tmp)
                    a
                )
                def headNullable of T = fn io (a: array of T?): T? -> (a.get(0))
                def firstListElem of T = fn io (a: array of (list of T)): T -> (a.get(0).get(0))
                def nullArr of T = fn (): (array of T)? -> (null)

                def parts = "alpha,beta".split(",")
                println(first(parts))
                println(swap((array of string).of("a", "b")))

                def boxedInts = (array of int?).of(10, null, 30)
                println(first(boxedInts))
                println(headNullable(boxedInts))

                def pts = array.of(Point(1, 2), Point(3, 4))
                println(first(pts))
                println(swap(pts))

                def rows = array.of(array.of(7, 8), array.of(9))
                println(first(rows))

                def inters: array of (Shape & Named) = (array of (Shape & Named)).of(Box(3, "three"), Box(4, "four"))
                def b0 = first(inters)
                println(b0.name(), ":", b0.area())

                def listArr = (array of (list of int)).of(list(42, 43))
                println(firstListElem(listArr))

                def hinted: (array of string)? = nullArr()
                println(hinted)

                def fnSpec: fn io (a: array of string): string = first
                println(fnSpec(parts))
                """;
        assertAllModesOut(
                script,
                String.join(
                                "\n",
                                "alpha",
                                "[b, a]",
                                "10",
                                "10",
                                "Point(x: 1, y: 2)",
                                "[Point(x: 3, y: 4), Point(x: 1, y: 2)]",
                                "[7, 8]",
                                "three:9",
                                "42",
                                "null",
                                "alpha")
                        + "\n");
    }

    @Test
    public void testReferenceBindingRuleRejectsPrimitiveScalarsEverywhere() {
        String badDirectCall =
                """
                def first of T = fn io (a: array of T): T -> (a.get(0))
                first("abc".toCharArray())
                """;
        assertDiagnostics(badDirectCall, "Type error : expected array of T, got array of char");

        String badMultiParam =
                """
                def fill of T = fn (shared x: T, mut a: array of T): T -> (
                    a.set(0, x)
                    x
                )
                fill(1, array.of(1, 2))
                """;
        assertDiagnostics(badMultiParam, "Type error : expected array of T, got array of int");

        String badNullArrayWithPrimArg =
                """
                def pick of T = fn (x: T, a: (array of T)?): T -> (x)
                pick(1, null)
                """;
        assertDiagnostics(
                badNullArrayWithPrimArg,
                "Type error : type argument int cannot be a primitive in array of T");

        String badTypeApply =
                """
                def first of T = fn io (a: array of T): T -> (a.get(0))
                def f = first of int
                """;
        assertDiagnostics(
                badTypeApply, "Type error : type argument int cannot be a primitive in array of T");

        String badFnValueInstantiation =
                """
                def first of T = fn io (a: array of T): T -> (a.get(0))
                def f: fn io (a: array of int): int = first
                """;
        assertDiagnostics(
                badFnValueInstantiation,
                "Type error : expected io (a : array of int) : int, got fn of T io (a : array of T) : T");

        String badSneakyUnionCollapse =
                """
                def sneaky of (T, U) = fn io (a: array of (T | U), x: T, y: U): T | U -> (a.get(0))
                sneaky(array.of(1, 2), 1, 2)
                """;
        assertDiagnostics(
                badSneakyUnionCollapse, "Type error : expected array of (T | U), got array of int");

        String badFrozenElem =
                """
                def headFrozen of T = fn io (a: array of (frozen T), x: T): frozen T -> (a.get(0))
                headFrozen(array.of(1, 2), 1)
                """;
        assertDiagnostics(
                badFrozenElem, "Type error : expected array of (frozen T), got array of int");

        String badGenericClass =
                """
                def Buffer of T = class () (
                    def data: (array of T)? = null
                )
                def b1: (Buffer of int)? = null
                def b2 = (Buffer of char)()
                """;
        assertDiagnostics(
                badGenericClass,
                "Type error : type argument int cannot be a primitive in array of T",
                "Type error : type argument char cannot be a primitive in array of T");

        String badGenericCtorInference =
                """
                def Holder of T = class (def seed: T) (
                    def items: (array of T)? = null
                )
                def h = Holder(42)
                """;
        assertDiagnostics(
                badGenericCtorInference,
                "Type error : type argument int cannot be a primitive in array of T");

        String badAliasFunctionVsDirectAlias =
                """
                def Arr of T = type array of T
                def ok: Arr of int = array(3)
                def firstAlias of T = fn io (a: Arr of T): T -> (a.get(0))
                firstAlias(ok)
                """;
        assertDiagnostics(
                badAliasFunctionVsDirectAlias,
                "Type error : expected array of T, got array of int");

        String badIsAndAsTypeVar =
                """
                def checkArr of T = fn (x: any): bool -> (x is array of T)
                def castArr of T = fn (x: any): array of T -> (x as array of T)
                """;
        assertDiagnostics(
                badIsAndAsTypeVar,
                "Cannot test type parameter with 'is' : array of T",
                "Cannot cast to type parameter : array of T");
    }

    @Test
    public void testJavaGenericArrayMethodsAndRejections() throws Exception {
        String okJavaGenerics =
                """
                def demo = import("org.babblelang.tests").JavaClassDemo
                def xs = list(10, 20, 30)
                def boxed: array of int? = xs.toArray((array of int?)(0))
                println(boxed)

                def ss = list("u", "v")
                def sArr: array of string? = ss.toArray((array of string?).of())
                println(sArr)

                def copied = java.util.Arrays.copyOf(sArr, 3)
                println(copied)

                def asL = java.util.Arrays.asList(array.of("m", "n"))
                println(asL.size(), ":", asL.get(1))

                println(demo.echoArrayWithSample("fallback", array.of("picked")))
                println(demo.echoArrayWithSample("fallback", null))
                """;
        assertAllModesOut(
                okJavaGenerics,
                String.join(
                                "\n",
                                "[10, 20, 30]",
                                "[u, v]",
                                "[u, v, null]",
                                "2:n",
                                "picked",
                                "fallback")
                        + "\n");

        String badToArrayPrim =
                """
                def xs = list(1, 2, 3)
                xs.toArray((array of int)(0))
                """;
        assertDiagnostics(
                badToArrayPrim,
                "No applicable overload of java.util.List.toArray for (array of int)");

        String badToArrayTypeApply =
                """
                def xs = list(1, 2, 3)
                def f = xs.toArray of int
                """;
        assertDiagnostics(
                badToArrayTypeApply,
                "Type error : type argument int cannot be a primitive in array of T");

        String badJavaGenericSamplePrim =
                """
                def demo = import("org.babblelang.tests").JavaClassDemo
                demo.echoArrayWithSample(1, null)
                """;
        assertDiagnostics(
                badJavaGenericSamplePrim,
                "Type error : type argument int cannot be a primitive in array of T");

        String badJavaIfacePrim =
                """
                def tests = import("org.babblelang.tests")
                def x: (tests.JavaClassDemo.ArrayTransformer of int)? = null
                """;
        assertDiagnostics(
                badJavaIfacePrim,
                "Type error : type argument int cannot be a primitive in array of E");
    }

    @Test
    public void testRm003JavaGenericArrayInterfaceBridgesAndDefaults() throws Exception {
        String script =
                """
                def tests = import("org.babblelang.tests")
                def demo = tests.JavaClassDemo
                def Point = record (x: int, y: int) ()

                def Upper = class () implements tests.JavaClassDemo.ArrayTransformer of string (
                    def transform = fn io (a: array of string): array of string -> (
                        array.of(a.get(0).toUpperCase(), a.get(1).toUpperCase())
                    )
                )

                def PointRev = class () implements tests.JavaClassDemo.ArrayTransformer of Point (
                    def transform = fn io (a: array of Point): array of Point -> (
                        array.of(a.get(1), a.get(0))
                    )
                )

                def GenericRev of T = class () implements tests.JavaClassDemo.ArrayTransformer of T (
                    def transform = fn io (mut shared a: array of T): array of T -> (
                        def tmp = a.get(0)
                        a.set(0, a.get(1))
                        a.set(1, tmp)
                        a
                    )
                )

                def Bag = class (def a: string, def b: string) implements tests.JavaClassDemo.ArrayExportable of string (
                    def toArray of T = fn io (mut shared out: array of T): array of T -> (
                        java.lang.System.arraycopy(array.of(this.a, this.b), 0, out, 0, 2)
                        out
                    )
                )

                def u = Upper()
                println(demo.invokeArrayTransformer(u, array.of("hi", "there")))

                def pr = PointRev()
                println(demo.invokeArrayTransformer(pr, array.of(Point(1, 2), Point(3, 4))))

                def gr = (GenericRev of int?)()
                println(demo.invokeArrayTransformer(gr, (array of int?).of(9, 1)))

                def bag = Bag("first", "second")
                def bagIface: tests.JavaClassDemo.ArrayExportable of string = bag
                println(demo.invokeExportableStrings(bag))
                println(bagIface.toArray())
                """;
        assertAllModesOut(
                script,
                String.join(
                                "\n",
                                "[HI, THERE]",
                                "[Point(x: 3, y: 4), Point(x: 1, y: 2)]",
                                "[1, 9]",
                                "[first, second]",
                                "2")
                        + "\n");

        // Verify compiled Upper is a direct non-Proxy ArrayTransformer implementation.
        Object compiledUpper =
                evalResult(
                        "compile",
                        "1",
                        """
                        def tests = import("org.babblelang.tests")
                        def Upper = class () implements tests.JavaClassDemo.ArrayTransformer of string (
                            def transform = fn io (a: array of string): array of string -> (a)
                        )
                        Upper()
                        """);
        Assertions.assertInstanceOf(JavaClassDemo.ArrayTransformer.class, compiledUpper);
        Assertions.assertFalse(Proxy.isProxyClass(compiledUpper.getClass()));

        // Raw Java passing wrong array type (Integer[] into ArrayTransformer<String>) fails
        // identically in all modes.
        String rawBadArg =
                """
                def tests = import("org.babblelang.tests")
                def Upper = class () implements tests.JavaClassDemo.ArrayTransformer of string (
                    def transform = fn io (a: array of string): array of string -> (a)
                )
                tests.JavaClassDemo.invokeArrayTransformerRaw(Upper(), (array of int?).of(1, 2))
                """;
        assertSameRuntimeError(
                rawBadArg, "Type error : expected array of string, got array of int?");
    }
}
