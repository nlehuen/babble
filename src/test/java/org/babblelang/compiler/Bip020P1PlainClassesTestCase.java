package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.runtime.BabbleObjects;
import org.babblelang.runtime.HasMembers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for BIP020-I1 : compiled classes without a runtime base class.
 */
public class Bip020P1PlainClassesTestCase {

    private static CompiledBabbleScript compile(String source, String opt) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put("babble.opt", opt);
        return (CompiledBabbleScript) engine.compile(source);
    }

    private static Object eval(String mode, String opt, String source) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (opt != null) {
            engine.put("babble.opt", opt);
        }
        return engine.eval(source);
    }

    private static ScriptException evalThrowsRuntimeError(String mode, String opt, String source) {
        ScriptException se = Assertions.assertThrows(ScriptException.class, () -> eval(mode, opt, source));
        Assertions.assertFalse(se instanceof TypeCheckException, "Expected runtime error, got compile-time TypeCheckException: " + se.getMessage());
        Assertions.assertTrue(se.getMessage().contains("BabbleException: Type error :"), "Unexpected message: " + se.getMessage());
        return se;
    }

    private static void assertVerifies(CompiledBabbleScript script) {
        for (byte[] classFile : script.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(classFile), script.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
    }

    private static void assertAllModesEqual(String source, Object expected) throws Exception {
        Assertions.assertEquals(expected, eval("interpret", null, source), "interpret: " + source);
        Assertions.assertEquals(expected, eval("compile", "1", source), "compile -O1: " + source);
        Assertions.assertEquals(expected, eval("compile", "0", source), "compile -O0: " + source);
    }

    @Test
    public void testPlainCompiledClassRecordAndObjectHaveObjectSuperclassAndNoRuntimeInterfaces() throws Exception {
        CompiledBabbleScript script = compile("""
                def Plain = class (def x : int) ()
                def Rec = record (x : int) ()
                def Obj = object (def x = 42)
                def Iface = interface (def value : fn () : int)
                def WithIface = class (def x : int) implements Iface (
                    def value = fn () : int -> ( this.x )
                )
                def results = (list of any)()
                results.add(Plain(1))
                results.add(Rec(2))
                results.add(Obj)
                results.add(WithIface(3))
                results
                """, "1");
        assertVerifies(script);

        @SuppressWarnings("unchecked")
        List<Object> results = (List<Object>) script.eval(new SimpleBindings());
        Assertions.assertEquals(4, results.size());

        Object plain = results.get(0);
        Object rec = results.get(1);
        Object obj = results.get(2);
        Object withIface = results.get(3);

        for (Object inst : results) {
            Assertions.assertTrue(BabbleObjects.isCompiledInstance(inst));
            Assertions.assertTrue(BabbleObjects.isInstance(inst));
            Assertions.assertSame(Object.class, inst.getClass().getSuperclass());
            Assertions.assertFalse(inst instanceof HasMembers);
        }

        Assertions.assertEquals(0, plain.getClass().getInterfaces().length);
        Assertions.assertEquals(0, rec.getClass().getInterfaces().length);
        Assertions.assertEquals(0, obj.getClass().getInterfaces().length);
        Assertions.assertEquals(1, withIface.getClass().getInterfaces().length);
        Assertions.assertTrue(withIface.getClass().getInterfaces()[0].isInterface());
    }

    @Test
    public void testCompiledRecordEqualsHashCodeAndToStringParity() throws Exception {
        String source = """
                def AllPrimitives = record (
                    b : byte,
                    s : short,
                    i : int,
                    l : long,
                    f : float,
                    d : double,
                    flag : bool,
                    c : char,
                    name : string
                ) (
                    private def hidden = 99
                    def sum = fn () : int -> ( this.i + this.hidden )
                )
                def nan = 0.0 / 0.0
                def fnan = 0.0f / 0.0f
                def a = AllPrimitives(byte(1), short(2), 3, 4L, fnan, nan, true, 'z', "ok")
                def b = AllPrimitives(byte(1), short(2), 3, 4L, fnan, nan, true, 'z', "ok")
                def negZero = AllPrimitives(byte(1), short(2), 3, 4L, 0.0f, -0.0, true, 'z', "ok")
                def posZero = AllPrimitives(byte(1), short(2), 3, 4L, 0.0f, 0.0, true, 'z', "ok")

                def Box = record (n : any) ()
                def bx1 = Box(1)
                def bx2 = Box(1.0)

                def mk = fn (offset : int) -> (
                    record (x : int) (
                        def tag = fn () : int -> ( this.x + offset )
                    )
                )
                def R1 = mk(1)
                def R2 = mk(2)

                def Named = record (x : int) (
                    def toString = fn () : string -> ( "Named<" + this.x + ">" )
                )

                assert(a == b)
                assert(java.util.Objects.equals(a, b))
                assert(java.util.Objects.hashCode(a) == java.util.Objects.hashCode(b))
                assert(negZero != posZero)
                assert(not java.util.Objects.equals(negZero, posZero))
                assert(bx1 == bx2)
                assert(java.util.Objects.equals(bx1, bx2))
                assert(java.util.Objects.hashCode(bx1) == java.util.Objects.hashCode(bx2))
                assert(R1(5) != R2(5))
                assert(not java.util.Objects.equals(R1(5), R2(5)))
                assert(("" + a) == "AllPrimitives(b: 1, s: 2, i: 3, l: 4, f: NaN, d: NaN, flag: true, c: z, name: ok)")
                assert(java.util.Objects.toString(a) == "AllPrimitives(b: 1, s: 2, i: 3, l: 4, f: NaN, d: NaN, flag: true, c: z, name: ok)")
                assert(("" + Named(7)) == "Named<7>")
                assert(java.util.Objects.toString(Named(7)) == "Named<7>")
                a.sum() + R1(5).tag() + R2(5).tag()
                """;
        assertAllModesEqual(source, 102 + 6 + 7);
    }

    @Test
    public void testStaticAsCastEmitsInstanceofAndCheckcastWithParity() throws Exception {
        String source = """
                def Named = interface (
                    def label : fn () : string
                )
                def Box = class (def s : string) implements Named (
                    def label = fn () : string -> ( this.s )
                )
                def castBox = fn (x : any) : Box -> ( x as Box )
                def castNamed = fn (x : any) : Named -> ( x as Named )
                def value : any = Box("hi")
                castBox(value).s + ":" + castNamed(value).label()
                """;
        CompiledBabbleScript script = compile(source, "1");
        assertVerifies(script);
        assertAllModesEqual(source, "hi:hi");

        List<String> instanceofTargets = new ArrayList<>();
        List<String> checkcastTargets = new ArrayList<>();
        for (byte[] bytes : script.getClasses().values()) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.INSTANCEOF) {
                                instanceofTargets.add(type);
                            } else if (opcode == Opcodes.CHECKCAST) {
                                checkcastTargets.add(type);
                            }
                        }
                    };
                }
            }, 0);
        }
        String boxJvm = script.classJvmNames().values().iterator().next();
        String namedJvm = script.interfaceJvmNames().values().iterator().next();
        Assertions.assertTrue(instanceofTargets.contains(boxJvm), "Expected INSTANCEOF " + boxJvm + " in " + instanceofTargets);
        Assertions.assertTrue(checkcastTargets.contains(boxJvm), "Expected CHECKCAST " + boxJvm + " in " + checkcastTargets);
        Assertions.assertTrue(instanceofTargets.contains(namedJvm), "Expected INSTANCEOF " + namedJvm + " in " + instanceofTargets);
        Assertions.assertTrue(checkcastTargets.contains(namedJvm), "Expected CHECKCAST " + namedJvm + " in " + checkcastTargets);

        // Mismatch and null runtime errors must match identically between interpreter and compiler.
        String mismatch = """
                def Box = class (def s : string) ()
                def castBox = fn (x : any) : Box -> ( x as Box )
                castBox("not a box")
                """;
        ScriptException interpMismatch = evalThrowsRuntimeError("interpret", null, mismatch);
        ScriptException compMismatchO1 = evalThrowsRuntimeError("compile", "1", mismatch);
        ScriptException compMismatchO0 = evalThrowsRuntimeError("compile", "0", mismatch);
        Assertions.assertEquals(interpMismatch.getMessage(), compMismatchO1.getMessage());
        Assertions.assertEquals(interpMismatch.getMessage(), compMismatchO0.getMessage());

        String nullCast = """
                def Box = class (def s : string) ()
                def castBox = fn (x : any?) : Box -> ( x as Box )
                castBox(null)
                """;
        ScriptException interpNull = evalThrowsRuntimeError("interpret", null, nullCast);
        ScriptException compNullO1 = evalThrowsRuntimeError("compile", "1", nullCast);
        ScriptException compNullO0 = evalThrowsRuntimeError("compile", "0", nullCast);
        Assertions.assertEquals(interpNull.getMessage(), compNullO1.getMessage());
        Assertions.assertEquals(interpNull.getMessage(), compNullO0.getMessage());
    }

    @Test
    public void testIntersectionDispatchWorksOnPlainCompiledClasses() throws Exception {
        String source = """
                def Named = interface ( def name : fn () : string )
                def Sized = interface ( def size : fn () : int )
                def Item = class (def n : string, def s : int) implements Named, Sized (
                    def name = fn () : string -> ( this.n )
                    def size = fn () : int -> ( this.s )
                )
                def describe = fn (x : Named & Sized) : string -> ( x.name() + "#" + x.size() )
                describe(Item("widget", 4))
                """;
        assertAllModesEqual(source, "widget#4");
    }
}
