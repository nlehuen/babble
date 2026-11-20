package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.babblelang.engine.MapModuleSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.script.ScriptEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for RM003 Phase 1 compiler infrastructure: {@link CompositeClassLoader},
 * {@link CodeGenerator.ScriptClassWriter} graph LCA, and interface JVM name propagation.
 */
public class Rm003P1CompilerTestCase {

    private static byte[] emptyPublicClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class NamedLoader extends ClassLoader {
        private final Map<String, byte[]> defs;

        NamedLoader(ClassLoader parent, Map<String, byte[]> defs) {
            super(parent);
            this.defs = defs;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = defs.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    public void testCompositeClassLoaderFlatteningIdentityDeduplicationAndPrecedence() throws Exception {
        ClassLoader parent = new NamedLoader(Rm003P1CompilerTestCase.class.getClassLoader(),
                Map.of("p.Shared", emptyPublicClass("p/Shared"),
                        "p.ParentOnly", emptyPublicClass("p/ParentOnly")));

        ClassLoader innerParent = new NamedLoader(null, Map.of("p.FromInnerParent", emptyPublicClass("p/FromInnerParent")));
        ClassLoader firstDelegate = new NamedLoader(null,
                Map.of("p.Shared", emptyPublicClass("p/Shared"),
                        "p.Collision", emptyPublicClass("p/Collision"),
                        "p.OnlyFirst", emptyPublicClass("p/OnlyFirst")));
        ClassLoader secondDelegate = new NamedLoader(null,
                Map.of("p.Collision", emptyPublicClass("p/Collision"),
                        "p.OnlySecond", emptyPublicClass("p/OnlySecond")));

        CompositeClassLoader inner = new CompositeClassLoader(innerParent, List.of(firstDelegate));
        CompositeClassLoader outer = new CompositeClassLoader(parent,
                java.util.Arrays.asList(null, parent, inner, firstDelegate, secondDelegate));

        // Flattened in order: inner's delegates (firstDelegate), inner's distinct parent (innerParent),
        // then secondDelegate; null, outer's own parent, and duplicate firstDelegate are omitted.
        Assertions.assertEquals(List.of(firstDelegate, innerParent, secondDelegate), outer.delegates());

        // Parent-first AOT precedence beats delegate definitions of the same binary name.
        Class<?> shared = outer.loadClass("p.Shared");
        Assertions.assertSame(parent, shared.getClassLoader());
        Assertions.assertSame(parent, outer.loadClass("p.ParentOnly").getClassLoader());

        // Delegate order precedence (e.g. TCCL before dependency loaders).
        Assertions.assertSame(firstDelegate, outer.loadClass("p.Collision").getClassLoader());
        Assertions.assertSame(firstDelegate, outer.loadClass("p.OnlyFirst").getClassLoader());
        Assertions.assertSame(innerParent, outer.loadClass("p.FromInnerParent").getClassLoader());
        Assertions.assertSame(secondDelegate, outer.loadClass("p.OnlySecond").getClassLoader());
        Assertions.assertThrows(ClassNotFoundException.class, () -> outer.loadClass("p.DoesNotExist"));
    }

    @Test
    public void testScriptClassWriterGraphLcaMatrix() {
        String script = "gen/Script$1";
        String babbleInstance = "org/babblelang/runtime/BabbleInstance";
        Map<String, List<String>> genClasses = new java.util.HashMap<>();
        Map<String, List<String>> genIfaces = new java.util.HashMap<>();

        // Diamond of generated Babble interfaces + multiple-inheritance ambiguity
        genIfaces.put("gen/Base", List.of());
        genIfaces.put("gen/Left", List.of("gen/Base"));
        genIfaces.put("gen/Right", List.of("gen/Base"));
        genIfaces.put("gen/Leaf", List.of("gen/Left", "gen/Right"));
        genIfaces.put("gen/OtherBase", List.of());
        genIfaces.put("gen/Fork1", List.of("gen/Base", "gen/OtherBase"));
        genIfaces.put("gen/Fork2", List.of("gen/Base", "gen/OtherBase"));
        genIfaces.put("gen/Mid", List.of("gen/Left"));
        genIfaces.put("gen/Spec1", List.of("gen/Base", "gen/Left"));
        genIfaces.put("gen/Spec2", List.of("gen/Mid"));

        // Generated concrete classes sharing Babble and Java interfaces
        genClasses.put("gen/C1", List.of(babbleInstance, "gen/Left", "java/lang/Runnable"));
        genClasses.put("gen/C2", List.of(babbleInstance, "gen/Left"));
        genClasses.put("gen/Unrelated", List.of(babbleInstance));

        ScriptClassWriter writer = new ScriptClassWriter(
                Rm003P1CompilerTestCase.class.getClassLoader(), script, genClasses, genIfaces);

        // Diamond interfaces & direct subtype
        Assertions.assertEquals("gen/Base", writer.getCommonSuperClass("gen/Left", "gen/Right"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Leaf", "gen/Left"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Left", "gen/Leaf"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Spec1", "gen/Spec2"));

        // Ambiguous common superinterfaces collapse to Object
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("gen/Fork1", "gen/Fork2"));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("gen/Base", "gen/OtherBase"));

        // Two generated classes sharing an interface merge on their common superclass (BabbleInstance)
        Assertions.assertEquals(babbleInstance, writer.getCommonSuperClass("gen/C1", "gen/C2"));

        // Mixed class & interface: returns interface iff class () implements it, else Object
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/C1", "gen/Left"));
        Assertions.assertEquals("gen/Base", writer.getCommonSuperClass("gen/C1", "gen/Base"));
        Assertions.assertEquals("java/lang/Runnable", writer.getCommonSuperClass("gen/C1", "java/lang/Runnable"));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("gen/Unrelated", "gen/Left"));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("java/util/ArrayList", "java/util/Set"));

        // Java interfaces diamond & class chains
        Assertions.assertEquals("java/util/Collection", writer.getCommonSuperClass("java/util/List", "java/util/Set"));
        Assertions.assertEquals("java/lang/Number", writer.getCommonSuperClass("java/lang/Integer", "java/lang/Double"));

        // Script class & missing classes fall back cleanly to Object
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass(script, "java/lang/String"));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("java/lang/String", script));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("no/such/MissingClass", "java/lang/String"));
    }

    @Test
    public void testInterfaceJvmNamesAreDeterministicAndPropagateAcrossModulesAndRepl() throws Exception {
        String libSource = "export def A = interface ( def x : fn () : int )\n"
                + "export def inc = fn (v : int) : int -> ( v + 1 )\n"
                + "export def B = interface extends A ( def y = fn () : int -> ( this.x() ) )\n";

        MapModuleSource src1 = new MapModuleSource().put("pkg/lib.ba", libSource);
        BabbleScriptEngine engine1 = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine1.put(BabbleScriptEngine.MODE, "compile");
        engine1.put(BabbleScriptEngine.MODULE_SOURCE, src1);
        List<BabbleScriptEngine.ProgramModule> prog1 = engine1.compileProgram(src1, "pkg/lib.ba");

        MapModuleSource src2 = new MapModuleSource().put("pkg/lib.ba", libSource);
        BabbleScriptEngine engine2 = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine2.put(BabbleScriptEngine.MODE, "compile");
        engine2.put(BabbleScriptEngine.MODULE_SOURCE, src2);
        List<BabbleScriptEngine.ProgramModule> prog2 = engine2.compileProgram(src2, "pkg/lib.ba");

        Map<String, String> ifaces1 = prog1.get(0).script().interfaceJvmNames();
        Map<String, String> ifaces2 = prog2.get(0).script().interfaceJvmNames();
        Assertions.assertEquals(Set.of("pkg/lib.ba#A", "pkg/lib.ba#B"), ifaces1.keySet());
        Assertions.assertEquals("babble/pkg/lib$ba$I0$A", ifaces1.get("pkg/lib.ba#A"));
        Assertions.assertEquals("babble/pkg/lib$ba$I1$B", ifaces1.get("pkg/lib.ba#B"));
        Assertions.assertEquals(ifaces1, ifaces2);

        // Multi-hop module compilation + REPL session propagation must succeed cleanly without losing interface JVM names.
        String midSource = "def lib = import(\"lib.ba\")\nexport def C = interface extends lib.B ( def z : fn () : int )\n";
        MapModuleSource multiSrc = new MapModuleSource()
                .put("pkg/lib.ba", libSource)
                .put("pkg/mid.ba", midSource)
                .put("main.ba", "def mid = import(\"pkg/mid.ba\") ; def Impl = class () implements mid.C ( def x = fn () : int -> ( 2 ) ; def z = fn () : int -> ( 3 ) ) ; Impl().y() + Impl().z()");
        BabbleScriptEngine multiEngine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        multiEngine.put(BabbleScriptEngine.MODE, "compile");
        multiEngine.put(BabbleScriptEngine.MODULE_SOURCE, multiSrc);
        List<BabbleScriptEngine.ProgramModule> multiProg = multiEngine.compileProgram(multiSrc, "main.ba");
        Assertions.assertEquals(5, multiEngine.eval("def mid = import(\"pkg/mid.ba\") ; def Impl = class () implements mid.C ( def x = fn () : int -> ( 2 ) ; def z = fn () : int -> ( 3 ) ) ; Impl().y() + Impl().z()"));
        Assertions.assertTrue(multiProg.stream().anyMatch(m -> m.id().key().equals("pkg/lib.ba")));

        BabbleScriptEngine replEngine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        replEngine.put(BabbleScriptEngine.MODE, "compile");
        replEngine.put(ScriptEngine.FILENAME, "repl.ba");
        BabbleSession session = replEngine.newSession();
        session.eval("def RepI = interface ( def val = fn () : int -> ( 9 ) )");
        Assertions.assertEquals(9, session.eval("def RepC = class () implements RepI () ; RepC().val()"));
    }
}
