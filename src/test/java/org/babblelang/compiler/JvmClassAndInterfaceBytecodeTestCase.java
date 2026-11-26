package org.babblelang.compiler;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import javax.script.SimpleBindings;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.runtime.BabbleObjects;
import org.babblelang.runtime.HasMembers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * JVM bytecode, ASM opcode, and reflection verification tests for compiled plain classes, Babble
 * JVM interfaces, direct Java interface implementation without {@link Proxy} wrappers, and
 * deterministic cross-module/session JVM interface naming.
 */
public class JvmClassAndInterfaceBytecodeTestCase {

    private static BabbleScriptEngine compilingEngine() {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        return engine;
    }

    private static CompiledBabbleScript compile(String source) throws Exception {
        return (CompiledBabbleScript) compilingEngine().compile(source);
    }

    private static CompiledBabbleScript compile(String source, String opt) throws Exception {
        BabbleScriptEngine engine = compilingEngine();
        engine.put("babble.opt", opt);
        return (CompiledBabbleScript) engine.compile(source);
    }

    private static void assertVerifies(CompiledBabbleScript script) {
        for (byte[] classFile : script.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(
                    new ClassReader(classFile),
                    script.getScriptClass().getClassLoader(),
                    false,
                    new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
    }

    private record InsnSummary(
            List<String> invokeInterfaceTargets,
            List<String> invokeSpecialTargets,
            List<String> instanceofTargets,
            List<String> checkcastTargets,
            List<String> staticCalls,
            List<String> indyBootstraps) {}

    private static InsnSummary inspect(byte[] classBytes) {
        List<String> invokeInterfaceTargets = new ArrayList<>();
        List<String> invokeSpecialTargets = new ArrayList<>();
        List<String> instanceofTargets = new ArrayList<>();
        List<String> checkcastTargets = new ArrayList<>();
        List<String> staticCalls = new ArrayList<>();
        List<String> indyBootstraps = new ArrayList<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
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
                                        } else if (opcode == Opcodes.CHECKCAST) {
                                            checkcastTargets.add(type);
                                        }
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String indyName,
                                            String indyDesc,
                                            Handle bsm,
                                            Object... bsmArgs) {
                                        String arg0 =
                                                bsmArgs.length > 0 ? "(" + bsmArgs[0] + ")" : "";
                                        indyBootstraps.add(
                                                bsm.getOwner() + "." + bsm.getName() + arg0);
                                    }
                                };
                            }
                        },
                        0);
        return new InsnSummary(
                invokeInterfaceTargets,
                invokeSpecialTargets,
                instanceofTargets,
                checkcastTargets,
                staticCalls,
                indyBootstraps);
    }

    @Test
    public void testPlainCompiledClassesHaveObjectSuperclassAndNoRuntimeInterfaces()
            throws Exception {
        CompiledBabbleScript script =
                compile(
                        """
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
                        """,
                        "1");
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
    public void testStaticAsCastEmitsInstanceofAndCheckcast() throws Exception {
        String source =
                """
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

        List<String> instanceofTargets = new ArrayList<>();
        List<String> checkcastTargets = new ArrayList<>();
        for (byte[] bytes : script.getClasses().values()) {
            InsnSummary insns = inspect(bytes);
            instanceofTargets.addAll(insns.instanceofTargets());
            checkcastTargets.addAll(insns.checkcastTargets());
        }
        String boxJvm = script.classJvmNames().values().iterator().next();
        String namedJvm = script.interfaceJvmNames().values().iterator().next();
        Assertions.assertTrue(
                instanceofTargets.contains(boxJvm),
                "Expected INSTANCEOF " + boxJvm + " in " + instanceofTargets);
        Assertions.assertTrue(
                checkcastTargets.contains(boxJvm),
                "Expected CHECKCAST " + boxJvm + " in " + checkcastTargets);
        Assertions.assertTrue(
                instanceofTargets.contains(namedJvm),
                "Expected INSTANCEOF " + namedJvm + " in " + instanceofTargets);
        Assertions.assertTrue(
                checkcastTargets.contains(namedJvm),
                "Expected CHECKCAST " + namedJvm + " in " + checkcastTargets);
    }

    @Test
    public void testInterfaceJvmNamesAreDeterministic() throws Exception {
        String libSource =
                """
                export def A = interface ( def x : fn () : int )
                export def inc = fn (v : int) : int -> ( v + 1 )
                export def B = interface extends A ( def y = fn () : int -> ( this.x() ) )
                """;

        MapModuleSource src1 = new MapModuleSource().put("pkg/lib.ba", libSource);
        BabbleScriptEngine engine1 = compilingEngine();
        engine1.put(BabbleScriptEngine.MODULE_SOURCE, src1);
        List<BabbleScriptEngine.ProgramModule> prog1 = engine1.compileProgram(src1, "pkg/lib.ba");

        MapModuleSource src2 = new MapModuleSource().put("pkg/lib.ba", libSource);
        BabbleScriptEngine engine2 = compilingEngine();
        engine2.put(BabbleScriptEngine.MODULE_SOURCE, src2);
        List<BabbleScriptEngine.ProgramModule> prog2 = engine2.compileProgram(src2, "pkg/lib.ba");

        Map<String, String> ifaces1 = prog1.get(0).script().interfaceJvmNames();
        Map<String, String> ifaces2 = prog2.get(0).script().interfaceJvmNames();
        Assertions.assertEquals(Set.of("pkg/lib.ba#A", "pkg/lib.ba#B"), ifaces1.keySet());
        Assertions.assertEquals("babble/pkg/lib$ba$I0$A", ifaces1.get("pkg/lib.ba#A"));
        Assertions.assertEquals("babble/pkg/lib$ba$I1$B", ifaces1.get("pkg/lib.ba#B"));
        Assertions.assertEquals(ifaces1, ifaces2);
    }

    @Test
    public void testInterfaceDispatchInstanceofAndSuperOpcodes() throws Exception {
        String src =
                """
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
            Assertions.assertTrue(
                    ifaceCls.isAssignableFrom(rectCls), rectCls + " should implement " + ifaceCls);
        }

        // Inspect Script class: INVOKEINTERFACE on bm$area / bm$scaled (including this.area()
        // inside Shape.scaled),
        // INSTANCEOF on Shape & Rect, and zero Bootstrap.select on area/scaled/tag, Values.is, or
        // Supers.call.
        InsnSummary scriptInsns =
                inspect(
                        script.getClasses()
                                .get(script.getScriptClass().getName().replace('.', '/')));
        Assertions.assertEquals(
                3,
                scriptInsns.invokeInterfaceTargets().stream()
                        .filter(t -> t.contains(".bm$area("))
                        .count(),
                scriptInsns.invokeInterfaceTargets().toString());
        Assertions.assertTrue(
                scriptInsns.invokeInterfaceTargets().stream()
                        .anyMatch(t -> t.contains(".bm$scaled(")),
                scriptInsns.invokeInterfaceTargets().toString());
        Assertions.assertTrue(
                scriptInsns.instanceofTargets().contains(rectInternal),
                scriptInsns.instanceofTargets().toString());
        Assertions.assertFalse(
                scriptInsns
                        .indyBootstraps()
                        .contains("org/babblelang/runtime/Bootstrap.select(area)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(
                scriptInsns
                        .indyBootstraps()
                        .contains("org/babblelang/runtime/Bootstrap.select(scaled)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(
                scriptInsns
                        .indyBootstraps()
                        .contains("org/babblelang/runtime/Bootstrap.select(tag)"),
                scriptInsns.indyBootstraps().toString());
        Assertions.assertFalse(
                scriptInsns.staticCalls().contains("org/babblelang/runtime/Values.is"),
                scriptInsns.staticCalls().toString());
        Assertions.assertFalse(
                scriptInsns.staticCalls().contains("org/babblelang/runtime/Supers.call"),
                scriptInsns.staticCalls().toString());

        // Inspect Rect class: direct INVOKESPECIAL for super.scaled AND synthetic $super$ helper
        // for nested thunk's super(Extra).tag.
        InsnSummary rectInsns = inspect(script.getClasses().get(rectInternal));
        Assertions.assertTrue(
                rectInsns.invokeSpecialTargets().stream().anyMatch(t -> t.contains(".bm$scaled(")),
                rectInsns.invokeSpecialTargets().toString());
        Assertions.assertTrue(
                rectInsns.invokeSpecialTargets().stream().anyMatch(t -> t.contains(".bm$tag(")),
                rectInsns.invokeSpecialTargets().toString());
        Assertions.assertFalse(
                rectInsns.staticCalls().contains("org/babblelang/runtime/Supers.call"),
                rectInsns.staticCalls().toString());
    }

    @Test
    public void testCrossModuleAndSessionShareJvmInterfaceClasses() throws Exception {
        BabbleScriptEngine engine = compilingEngine();
        engine.put(
                BabbleScriptEngine.MODULE_SOURCE,
                new MapModuleSource()
                        .put(
                                "api.ba",
                                """
                                export def Named = interface (
                                    def name : fn () : string
                                )
                                export def DefaultNamed = class (v : string) implements Named (
                                    def name = fn () : string -> ( v )
                                )
                                """)
                        .put(
                                "impl.ba",
                                """
                                def api = import("api.ba")
                                export def Custom = class (p : string) implements api.Named (
                                    def name = fn () : string -> ( "C:" + p )
                                )
                                """));

        Object customInstance =
                engine.eval(
                        """
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
        Assertions.assertTrue(
                customInstance.getClass().getInterfaces()[0].getName().contains("$I0$Named"));

        BabbleSession session = compilingEngine().newSession();
        session.eval("def Counter = interface ( def next : fn () : int )");
        Object c1 =
                session.eval(
                        "def Step = class (n : int) implements Counter ( def next = fn () : int -> ( n + 1 ) ) ; Step(4)");
        Object res =
                session.eval("def read = fn (c : Counter) : int -> ( c.next() ) ; read(Step(9))");
        Assertions.assertEquals(10, res);
        Assertions.assertEquals(1, c1.getClass().getInterfaces().length);
        Assertions.assertTrue(c1.getClass().getInterfaces()[0].getName().contains("$I0$Counter"));
    }

    @Test
    public void testCompiledInstancesImplementJavaInterfacesDirectlyWithoutProxy()
            throws Exception {
        CompiledBabbleScript script =
                compile(
                        """
                        def Ordered = interface extends java.lang.Comparable of Ordered (
                            def key : fn () : int
                            def compareTo = fn (o : Ordered) : int -> ( this.key() - o.key() )
                        )
                        def Node = class (def k : int) implements Ordered, java.util.function.IntUnaryOperator, java.util.Iterator of int (
                            private var used = false
                            def key = fn () : int -> ( this.k )
                            def applyAsInt = fn (x : int) : int -> ( x + this.k )
                            def hasNext = fn () : bool -> ( not this.used )
                            def next = fn mut () : int -> ( this.used = true ; this.k )
                        )
                        Node(7)
                        """);
        assertVerifies(script);

        Object inst = script.eval(new SimpleBindings());
        Assertions.assertTrue(BabbleObjects.isCompiledInstance(inst));
        Assertions.assertSame(Object.class, inst.getClass().getSuperclass());

        // Zero Proxy allocation: face() returns the compiled instance itself.
        Assertions.assertSame(inst, BabbleObjects.face(inst));
        Assertions.assertFalse(Proxy.isProxyClass(inst.getClass()));
        Assertions.assertInstanceOf(Comparable.class, inst);
        Assertions.assertInstanceOf(IntUnaryOperator.class, inst);
        Assertions.assertInstanceOf(Iterator.class, inst);

        // Generated Babble JVM interface directly extends java.lang.Comparable.
        ClassLoader loader = script.getScriptClass().getClassLoader();
        String orderedInternal = script.interfaceJvmNames().values().iterator().next();
        Class<?> orderedCls = loader.loadClass(orderedInternal.replace('/', '.'));
        Assertions.assertTrue(orderedCls.isInterface());
        Assertions.assertTrue(Comparable.class.isAssignableFrom(orderedCls));
        Assertions.assertTrue(orderedCls.isInstance(inst));

        // Direct Java interface calls work on the instance without reflection or Proxy.
        Assertions.assertEquals(17, ((IntUnaryOperator) inst).applyAsInt(10));
        Iterator<?> it = (Iterator<?>) inst;
        Assertions.assertTrue(it.hasNext());
        Assertions.assertEquals(7, it.next());
        Assertions.assertFalse(it.hasNext());
    }
}
