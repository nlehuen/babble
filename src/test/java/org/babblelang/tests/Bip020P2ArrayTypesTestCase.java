package org.babblelang.tests;

import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
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
 * Tests for BIP020-P2 : the type {@code array of T}, JVM representation, aliases,
 * intersections, variance, Java parameter waiver, {@code is}/{@code as} and warnings.
 */
public class Bip020P2ArrayTypesTestCase {

    private static List<String> diagnosticsOf(String source) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext ctx = new SimpleScriptContext();
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

    private static Object eval(String mode, String opt, String source) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (opt != null) {
            engine.put("babble.opt", opt);
        }
        return engine.eval(source);
    }

    private static void assertAllModesEqual(String source, Object expected) throws Exception {
        Assertions.assertEquals(expected, eval("interpret", null, source), "interpret: " + source);
        Assertions.assertEquals(expected, eval("compile", "1", source), "compile -O1: " + source);
        Assertions.assertEquals(expected, eval("compile", "0", source), "compile -O0: " + source);
    }

    @Test
    public void testSyntaxNullnessAliasesAndByteBufferArrayMemberName() throws Exception {
        String valid = """
                def parts: array of string = java.util.regex.Pattern.compile(",").split("a,b")
                def chars: array of char = "abc".toCharArray()
                def ints: array of int? = import("org.babblelang.tests").JavaClassDemo.boxedIntArray()
                def maybeInts: (array of int)? = null
                def grid: array of (array of int) = import("org.babblelang.tests").JavaClassDemo.jaggedIntArray()
                def buf = java.nio.ByteBuffer.allocate(4)!
                def backing: array of byte = buf.array()!
                def Strings = type array of string
                def Matrix of T = type array of (array of T)
                def aliasParts: Strings = parts
                def checkMatrix = fn (m: Matrix of int): int -> ( m.length )
                aliasParts.length + chars.length + ints.length + grid.length + backing.length + checkMatrix(grid)
                """;
        assertNoErrors(valid);
        assertAllModesEqual(valid, 2 + 3 + 3 + 2 + 4 + 2);

        assertDiagnostics("""
                def raw: array = null
                def tooMany: array of (int, string) = null
                def Ints = type array of int
                def badCtor = Ints(10)
                """,
                "Missing type arguments : array",
                "Wrong number of type arguments : expected 1, got 2",
                "A type alias is not a value : Ints");
    }

    @Test
    public void testIntersectionsWithArraysAndJavaInterfaces() throws Exception {
        String assignInterfaces = """
                def Shape = interface ( def area : fn () : int )
                def Named = interface ( def name : fn () : string )
                def Circle = class () implements Shape, Named (
                    def area = fn () : int -> ( 1 )
                    def name = fn () : string -> ( "c" )
                )
                def parts: array of string = java.util.regex.Pattern.compile(",").split("a,b")
                def chars: array of char = "abc".toCharArray()
                def both: java.lang.Cloneable & java.io.Serializable = parts
                def bothPrim: java.lang.Cloneable & java.io.Serializable = chars
                def acceptShapeNamed = fn (mut xs: array of (Shape & Named)): int -> ( xs.length )
                def acceptAnyBorrow = fn (xs: array of any?): int -> ( xs.length )
                def takeAny = fn (x: any): bool -> ( x is java.lang.Cloneable & java.io.Serializable )
                def testAlias = fn (xs: array of Circle, sn: array of (Shape & Named)): int -> (
                    acceptAnyBorrow(sn)
                )
                assert(both != null and bothPrim != null)
                assert(takeAny(parts) and takeAny(chars))
                parts.length + chars.length
                """;
        assertNoErrors(assignInterfaces);
        assertAllModesEqual(assignInterfaces, 5);

        assertDiagnostics("""
                def Shape = interface ( def area : fn () : int )
                def bad: (array of int) & Shape = null
                def bad2: array of int & Shape = null
                """,
                "Only interfaces intersect",
                "Only interfaces intersect");
    }

    @Test
    public void testArrayVarianceAndJavaParameterWaiver() throws Exception {
        // Valid covariance cases: exact-store ref covariance, borrow/frozen nullness/exactness relaxation, Java non-mut waiver.
        String valid = """
                def Shape = interface ( def area : fn () : int )
                def Circle = class () implements Shape ( def area = fn () : int -> ( 1 ) )
                def Demo = import("org.babblelang.tests").JavaClassDemo
                def parts: array of string = java.util.regex.Pattern.compile(",").split("a,b")
                def boxed: array of int? = Demo.boxedIntArray()

                def readStrings = fn (xs: array of string?): int -> ( xs.length )
                def readAnys = fn (xs: array of any?): int -> ( xs.length )
                def writeAnyNullable = fn (mut xs: array of any?): int -> ( xs.length )
                def writeStringsNullable = fn (mut xs: array of string?): int -> ( xs.length )
                def toStringOut = java.util.Arrays.toString(parts)

                readStrings(parts) + readAnys(parts) + writeAnyNullable(boxed) + Demo.readObjectArray(parts) + toStringOut.length()
                """;
        assertNoErrors(valid);
        assertAllModesEqual(valid, 2 + 2 + 3 + 2 + "[a, b]".length());

        // Rejected variance cases under BIP020-D8 and BIP020-D12:
        assertDiagnostics("""
                def Shape = interface ( def area : fn () : int )
                def Named = interface ( def name : fn () : string )
                def Square = class () implements Shape ( def area = fn () : int -> ( 2 ) )
                def Demo = import("org.babblelang.tests").JavaClassDemo
                def chars: array of char = "abc".toCharArray()
                def parts: array of string = java.util.regex.Pattern.compile(",").split("a,b")
                def boxed: array of int? = Demo.boxedIntArray()

                def borrowLongs = fn (xs: array of long) -> ( xs.length )
                def borrowBoxedChars = fn (xs: array of char?) -> ( xs.length )
                def borrowAnys = fn (xs: array of any) -> ( xs.length )
                def writeStringsNull = fn (mut xs: array of string?) -> ( xs.length )
                def writeAnyNull = fn (mut xs: array of any?) -> ( xs.length )
                def borrowShapes = fn (xs: array of Shape) -> ( xs.length )
                def writeAny = fn (mut xs: array of any) -> ( xs.length )

                borrowLongs(chars)
                borrowBoxedChars(chars)
                borrowAnys(chars)
                writeStringsNull(parts)
                writeAnyNull(parts)
                def takeUnion = fn (xs: array of (Square | Shape)) -> ( borrowShapes(xs) )
                def takeInter = fn (xs: array of (Shape & Named)) -> ( borrowShapes(xs) )
                def copyAny = fn (mut src: array of any) -> ( writeAnyNull(src) )
                Demo.mutateObjectArray(parts)
                writeAny(boxed)
                """,
                "Type error : expected array of long, got array of char",
                "Type error : expected array of char?, got array of char",
                "Type error : expected array of any, got array of char",
                "Type error : expected array of string?, got array of string",
                "Type error : expected array of any?, got array of string",
                "Type error : expected array of Shape, got array of (Square | Shape)",
                "Type error : expected array of Shape, got array of (Shape & Named)",
                "Type error : expected array of any?, got array of any",
                "No applicable overload of org.babblelang.tests.JavaClassDemo.mutateObjectArray",
                "Type error : expected array of any, got array of int?");
    }

    @Test
    public void testMutableGenericVarianceWithJavaAndBabble() {
        // Primitive array invariant even against borrowed int[] -> long[] directly
        assertDiagnostics("""
                def borrowInts = fn (xs: array of int) -> ( xs.length )
                def chars: array of char = "abc".toCharArray()
                borrowInts(chars)
                """, "Type error : expected array of int, got array of char");

        // Mutable vs borrowed exact-source-class for Circle[] -> (Shape & Named)[]
        assertNoErrors("""
                def Shape = interface ( def area : fn () : int )
                def Named = interface ( def name : fn () : string )
                def Circle = class () implements Shape, Named (
                    def area = fn () : int -> ( 1 )
                    def name = fn () : string -> ( "c" )
                )
                def takeInter = fn (mut xs: array of (Shape & Named)) -> ( xs.length )
                def checkCircle = fn (mut cs: array of Circle) -> ( takeInter(cs) )
                """);

        // @Mut Object[] allows string?[] (nullness matches) even though Object[] is Java
        assertNoErrors("""
                def Demo = import("org.babblelang.tests").JavaClassDemo
                def test = fn (mut xs: array of string?) -> ( Demo.mutateObjectArray(xs) )
                """);
    }

    @Test
    public void testIsAsNullableRulesUncheckedWarningsAndBytecode() throws Exception {
        // No warning on exact JVM array checks: primitive arrays, nullable primitives, string?, any?, nested nullable primitive rows
        assertDiagnostics("""
                def chars: array of char = "abc".toCharArray()
                def dyn: any? = chars
                def b1 = dyn is array of char
                def b2 = dyn is array of int?
                def b3 = dyn is array of string?
                def b4 = dyn is array of any?
                def b5 = dyn is array of ((array of int)?)
                def c1 = (dyn as any) as array of string?
                def c2 = (dyn as any) as array of any?
                def c3 = null is (array of int)?
                def c4 = null as (array of int)?
                """);

        // Unchecked cast warnings when JVM class doesn't verify nullness, nested elements, or Babble element types
        assertDiagnostics("""
                def Point = record (x: int) ()
                def dyn: any = java.util.regex.Pattern.compile(",").split("a,b")
                def u1 = dyn is array of string
                def u2 = dyn as array of string
                def u3 = dyn is array of any
                def u4 = dyn as array of Point
                def u5 = dyn is array of Point
                def u6 = dyn is array of (array of int)
                def u7 = dyn as (array of Point)?
                def maybeStr: (array of string)? = java.util.regex.Pattern.compile(",").split("a,b")
                def okNull = maybeStr as (array of int)?
                """,
                "Unchecked cast : array of string",
                "Unchecked cast : array of string",
                "Unchecked cast : array of any",
                "Unchecked cast : array of Point",
                "Unchecked cast : array of Point",
                "Unchecked cast : array of (array of int)",
                "Unchecked cast : (array of Point)?");

        // Static rejections for disjoint array casts (BIP008 §11 & BIP020-D9)
        assertDiagnostics("""
                def Point = class () ()
                def Shape = interface ( def m : fn () : int )
                def f = fn (xs: array of string?, ps: array of Point) -> (
                    def a = xs as array of int
                    def b = xs as array of int?
                    def c = ps as array of Shape
                    1
                )
                """,
                "Type error : expected array of int, got array of string?",
                "Type error : expected array of int?, got array of string?",
                "Type error : expected array of Shape, got array of Point");

        // Runtime execution parity & bytecode verification (`INSTANCEOF [C`, `[Ljava/lang/String;`, `[Ljava/lang/Object;`)
        String runtimeProgram = """
                def Point = record (x: int) ()
                def Ints = type array of int
                def Matrix of T = type array of (array of T)
                def parts: any? = java.util.regex.Pattern.compile(",").split("a,b")
                def chars: any? = "abc".toCharArray()
                def raw: any? = java.util.List.of(Point(1), Point(2)).toArray()
                def grid: any? = import("org.babblelang.tests").JavaClassDemo.jaggedIntArray()
                def maybeNull: (array of string)? = null

                assert(chars is array of char)
                assert(not (chars is Ints))
                assert(not (chars is Ints?))
                assert(not (chars is array of string?))
                assert(not (chars is array of int))
                assert(parts is array of string?)
                assert((parts as array of string).length == 2)
                assert(raw is array of Point)
                assert(raw is array of any?)
                assert((raw as array of Point).length == 2)
                assert(grid is Matrix of int)
                assert(null is Ints?)
                assert(not (null is Ints))
                assert((null as Ints?) == null)
                assert((maybeNull as (array of int)?) == null)
                true
                """;
        BabbleScriptEngine compileEngine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        compileEngine.put(BabbleScriptEngine.MODE, "compile");
        CompiledBabbleScript compiled = (CompiledBabbleScript) compileEngine.compile(runtimeProgram);
        for (byte[] bytes : compiled.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(bytes), compiled.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
        List<String> instanceofTypes = new ArrayList<>();
        List<String> checkcastTypes = new ArrayList<>();
        for (byte[] bytes : compiled.getClasses().values()) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.INSTANCEOF) {
                                instanceofTypes.add(type);
                            } else if (opcode == Opcodes.CHECKCAST) {
                                checkcastTypes.add(type);
                            }
                        }
                    };
                }
            }, 0);
        }
        Assertions.assertTrue(instanceofTypes.contains("[C"), instanceofTypes.toString());
        Assertions.assertTrue(instanceofTypes.contains("[Ljava/lang/String;"), instanceofTypes.toString());
        Assertions.assertTrue(instanceofTypes.contains("[Ljava/lang/Object;"), instanceofTypes.toString());
        Assertions.assertTrue(instanceofTypes.contains("[[I"), instanceofTypes.toString());
        Assertions.assertTrue(checkcastTypes.contains("[Ljava/lang/String;"), checkcastTypes.toString());
        Assertions.assertTrue(checkcastTypes.contains("[Ljava/lang/Object;"), checkcastTypes.toString());

        assertAllModesEqual(runtimeProgram, true);

        // Runtime array cast failure message parity (interpreter vs compiler)
        String castFail = """
                def xs: (array of string)? = java.util.regex.Pattern.compile(",").split("a,b")
                xs as (array of int)?
                """;
        ScriptException interpErr = Assertions.assertThrows(ScriptException.class, () -> eval("interpret", null, castFail));
        ScriptException compErr = Assertions.assertThrows(ScriptException.class, () -> eval("compile", "1", castFail));
        Assertions.assertFalse(interpErr instanceof TypeCheckException);
        Assertions.assertTrue(interpErr.getMessage().contains("Type error : expected (array of int)?, got array of string"),
                interpErr.getMessage());
        Assertions.assertEquals(interpErr.getMessage(), compErr.getMessage());
    }
}
