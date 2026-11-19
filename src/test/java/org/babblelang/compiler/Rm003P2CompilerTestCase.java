package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.MapModuleSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.SimpleBindings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bytecode and classloader verification tests for RM003 Phase 2: Babble interfaces
 * compiled to real JVM interfaces with {@code INVOKEINTERFACE}, {@code INSTANCEOF},
 * and {@code INVOKESPECIAL} lowering.
 */
public class Rm003P2CompilerTestCase {

    private static BabbleScriptEngine compilingEngine() {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        return engine;
    }

    private static CompiledBabbleScript compile(String source) throws Exception {
        return (CompiledBabbleScript) compilingEngine().compile(source);
    }

    private static void assertVerifies(CompiledBabbleScript script) {
        for (byte[] classFile : script.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(classFile), script.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
    }

    private record InsnSummary(List<String> invokeInterfaceTargets,
                               List<String> invokeSpecialTargets,
                               List<String> instanceofTargets,
                               List<String> staticCalls,
                               List<String> indyBootstraps) {
    }

    private static InsnSummary inspect(byte[] classBytes) {
        List<String> invokeInterfaceTargets = new ArrayList<>();
        List<String> invokeSpecialTargets = new ArrayList<>();
        List<String> instanceofTargets = new ArrayList<>();
        List<String> staticCalls = new ArrayList<>();
        List<String> indyBootstraps = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE) {
                            invokeInterfaceTargets.add(owner + "." + mName + mDesc);
                        } else if (opcode == Opcodes.INVOKESPECIAL && isInterface) {
                            invokeSpecialTargets.add(owner + "." + mName + mDesc);
                        } else if (opcode == Opcodes.INVOKESTATIC) {
                            staticCalls.add(owner + "." + mName);
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.INSTANCEOF) {
                            instanceofTargets.add(type);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc, Handle bsm, Object... bsmArgs) {
                        String arg0 = bsmArgs.length > 0 ? "(" + bsmArgs[0] + ")" : "";
                        indyBootstraps.add(bsm.getOwner() + "." + bsm.getName() + arg0);
                    }
                };
            }
        }, 0);
        return new InsnSummary(invokeInterfaceTargets, invokeSpecialTargets, instanceofTargets, staticCalls, indyBootstraps);
    }

    @Test
    public void testInterfaceDispatchInstanceofAndSuperOpcodes() throws Exception {
        String src = """
                def Shape = interface (
                    def area : fn () : double
                    def scaled = fn (factor : double) : double -> ( this.area() * factor )
                )
                def Extra = interface (
                    def tag = fn () : string -> ( "E" )
                )
                def Rect = class (w : double, h : double) implements Shape, Extra (
                    def area = fn () : double -> ( w * h )
                    def scaled = fn (factor : double) : double -> ( super.scaled(factor) + 1.0 )
                    def tag = fn () : string -> (
                        def thunk = fn () : string -> ( super(Extra).tag() + "!" )
                        thunk()
                    )
                )
                def r = Rect(3.0, 4.0)
                def s : Shape = r
                def ok = (s is Shape) and (s is Rect) and not ("x" is Shape)
                if ok then ( s.area() + s.scaled(2.0) ) else ( 0.0 )
                """;
        CompiledBabbleScript script = compile(src);
        assertVerifies(script);
        Object value = script.eval(new SimpleBindings());
        Assertions.assertEquals(37.0, (Double) value, 1e-9);

        // Verify generated JVM interface classes exist and are actual JVM interfaces.
        ClassLoader loader = script.getScriptClass().getClassLoader();
        Assertions.assertEquals(2, script.interfaceJvmNames().size());
        for (String ifaceInternal : script.interfaceJvmNames().values()) {
            Class<?> ifaceCls = loader.loadClass(ifaceInternal.replace('/', '.'));
            Assertions.assertTrue(ifaceCls.isInterface(), ifaceCls + " should be a JVM interface");
        }

        // Verify Rect's JVM class directly implements both JVM interfaces.
        Assertions.assertEquals(1, script.classJvmNames().size());
        String rectInternal = script.classJvmNames().values().iterator().next();
        Class<?> rectCls = loader.loadClass(rectInternal.replace('/', '.'));
        for (String ifaceInternal : script.interfaceJvmNames().values()) {
            Class<?> ifaceCls = loader.loadClass(ifaceInternal.replace('/', '.'));
            Assertions.assertTrue(ifaceCls.isAssignableFrom(rectCls), rectCls + " should implement " + ifaceCls);
        }

        // Inspect Script class: INVOKEINTERFACE on bm$area / bm$scaled (including this.area() inside Shape.scaled),
        // INSTANCEOF on Shape & Rect, and zero Bootstrap.select on area/scaled/tag, Values.is, or Supers.call.
        InsnSummary scriptInsns = inspect(script.getClasses().get(script.getScriptClass().getName().replace('.', '/')));
        Assertions.assertEquals(3, scriptInsns.invokeInterfaceTargets().stream().filter(t -> t.contains(".bm$area(")).count(),
                scriptInsns.invokeInterfaceTargets().toString());
        Assertions.assertTrue(scriptInsns.invokeInterfaceTargets().stream().anyMatch(t -> t.contains(".bm$scaled(")),
                scriptInsns.invokeInterfaceTargets().toString());
        Assertions.assertTrue(scriptInsns.instanceofTargets().contains(rectInternal),
                scriptInsns.instanceofTargets().toString());
        Assertions.assertFalse(scriptInsns.indyBootstraps().contains("org/babblelang/runtime/Bootstrap.select(area)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(scriptInsns.indyBootstraps().contains("org/babblelang/runtime/Bootstrap.select(scaled)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(scriptInsns.indyBootstraps().contains("org/babblelang/runtime/Bootstrap.select(tag)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(scriptInsns.staticCalls().contains("org/babblelang/runtime/Values.is"),
                scriptInsns.staticCalls().toString());
        Assertions.assertFalse(scriptInsns.staticCalls().contains("org/babblelang/runtime/Supers.call"),
                scriptInsns.staticCalls().toString());

        // Inspect Rect class: direct INVOKESPECIAL for super.scaled AND synthetic $super$ helper for nested thunk's super(Extra).tag.
        InsnSummary rectInsns = inspect(script.getClasses().get(rectInternal));
        Assertions.assertTrue(rectInsns.invokeSpecialTargets().stream().anyMatch(t -> t.contains(".bm$scaled(")),
                rectInsns.invokeSpecialTargets().toString());
        Assertions.assertTrue(rectInsns.invokeSpecialTargets().stream().anyMatch(t -> t.contains(".bm$tag(")),
                rectInsns.invokeSpecialTargets().toString());
        Assertions.assertFalse(rectInsns.staticCalls().contains("org/babblelang/runtime/Supers.call"),
                rectInsns.staticCalls().toString());
    }

    @Test
    public void testCrossModuleAndSessionShareJvmInterfaceClasses() throws Exception {
        BabbleScriptEngine engine = compilingEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, new MapModuleSource()
                .put("api.ba", """
                        export def Named = interface (
                            def name : fn () : string
                        )
                        export def DefaultNamed = class (v : string) implements Named (
                            def name = fn () : string -> ( v )
                        )
                        """)
                .put("impl.ba", """
                        def api = import("api.ba")
                        export def Custom = class (p : string) implements api.Named (
                            def name = fn () : string -> ( "C:" + p )
                        )
                        """));

        Object customInstance = engine.eval("""
                def api = import("api.ba")
                def impl = import("impl.ba")
                def c : api.Named = impl.Custom("ok")
                assert(c is api.Named)
                assert(c is impl.Custom)
                assert(not (c is api.DefaultNamed))
                assert(c.name() == "C:ok")
                c
                """);
        Assertions.assertNotNull(customInstance);
        Assertions.assertEquals(1, customInstance.getClass().getInterfaces().length);
        Assertions.assertTrue(customInstance.getClass().getInterfaces()[0].getName().contains("$I0$Named"));

        BabbleSession session = compilingEngine().newSession();
        session.eval("def Counter = interface ( def next : fn () : int )");
        Object c1 = session.eval("def Step = class (n : int) implements Counter ( def next = fn () : int -> ( n + 1 ) ) ; Step(4)");
        Object res = session.eval("def read = fn (c : Counter) : int -> ( c.next() ) ; fn (c1 : any) -> ( 0 ) ; read(Step(9))");
        Assertions.assertEquals(10, res);
        Assertions.assertEquals(1, c1.getClass().getInterfaces().length);
        Assertions.assertTrue(c1.getClass().getInterfaces()[0].getName().contains("$I0$Counter"));
    }
}
