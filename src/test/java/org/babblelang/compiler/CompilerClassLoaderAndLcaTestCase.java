package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.Implicits;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for compiler classloader and type-hierarchy internals:
 * {@link CompositeClassLoader} flattening and precedence, {@link ScriptClassWriter#getCommonSuperClass}
 * graph LCA, and child-{@link ClassLoader} SAM {@link Proxy} fallback.
 */
public class CompilerClassLoaderAndLcaTestCase {

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

    private static final class FacesProbeLoader extends ClassLoader {
        FacesProbeLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineChildSam() {
            String internalName = "org/babblelang/compiler/dynamic/ChildIntOp";
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                    internalName, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                    "apply", "(I)I", null, null);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass("org.babblelang.compiler.dynamic.ChildIntOp", bytes, 0, bytes.length);
        }

        Class<?> defineChildCaller(Class<?> sam) {
            String samInternal = sam.getName().replace('.', '/');
            String internalName = "org/babblelang/compiler/dynamic/ChildCaller";
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                    internalName, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "call", "(L" + samInternal + ";I)I", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, samInternal, "apply", "(I)I", true);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass("org.babblelang.compiler.dynamic.ChildCaller", bytes, 0, bytes.length);
        }
    }

    @Test
    public void testCompositeClassLoaderFlatteningIdentityDeduplicationAndPrecedence() throws Exception {
        ClassLoader parent = new NamedLoader(CompilerClassLoaderAndLcaTestCase.class.getClassLoader(),
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
                Arrays.asList(null, parent, inner, firstDelegate, secondDelegate));

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
        String object = "java/lang/Object";
        Map<String, List<String>> genClasses = new HashMap<>();
        Map<String, List<String>> genIfaces = new HashMap<>();

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

        // Generated plain concrete classes sharing Babble and Java interfaces (superclass java/lang/Object)
        genClasses.put("gen/C1", List.of(object, "gen/Left", "java/lang/Runnable"));
        genClasses.put("gen/C2", List.of(object, "gen/Left"));
        genClasses.put("gen/Unrelated", List.of(object));
        genClasses.put("gen/NumSub1", List.of("java/lang/Number"));
        genClasses.put("gen/NumSub2", List.of("java/lang/Number"));

        ScriptClassWriter writer = new ScriptClassWriter(
                CompilerClassLoaderAndLcaTestCase.class.getClassLoader(), script, genClasses, genIfaces);

        // Diamond interfaces & direct subtype
        Assertions.assertEquals("gen/Base", writer.getCommonSuperClass("gen/Left", "gen/Right"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Leaf", "gen/Left"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Left", "gen/Leaf"));
        Assertions.assertEquals("gen/Left", writer.getCommonSuperClass("gen/Spec1", "gen/Spec2"));

        // Ambiguous common superinterfaces collapse to Object
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("gen/Fork1", "gen/Fork2"));
        Assertions.assertEquals("java/lang/Object", writer.getCommonSuperClass("gen/Base", "gen/OtherBase"));

        // Plain compiled classes have java/lang/Object as their common superclass; classes sharing Number merge on Number
        Assertions.assertEquals(object, writer.getCommonSuperClass("gen/C1", "gen/C2"));
        Assertions.assertEquals("java/lang/Number", writer.getCommonSuperClass("gen/NumSub1", "gen/NumSub2"));

        // Mixed class & interface: returns interface iff class implements it, else Object
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
    public void testChildClassLoaderSamFallsBackToProxyCleanly() throws Exception {
        // Generate a public functional interface + helper class in an isolated child ClassLoader
        // invisible to Faces.class.getClassLoader(), proving Faces falls back to Proxy cleanly.
        ClassLoader parent = FacesProbeLoader.class.getClassLoader();
        FacesProbeLoader childLoader = new FacesProbeLoader(parent);
        Class<?> childSam = childLoader.defineChildSam();
        Class<?> childCaller = childLoader.defineChildCaller(childSam);
        Method runIt = childCaller.getMethod("call", childSam, int.class);

        Object callable = BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval("fn (x : int) : int -> ( x + 40 )");
        ScriptRuntime rt = new ScriptRuntime(Implicits.create(), new PrintWriter(Writer.nullWriter()), null);
        Object adapted = Values.toJava(callable, childSam, rt);

        Assertions.assertTrue(childSam.isInstance(adapted));
        Assertions.assertTrue(Proxy.isProxyClass(adapted.getClass()),
                "SAM loaded in child ClassLoader invisible to Faces must fall back to Proxy");
        Assertions.assertEquals(42, runIt.invoke(null, adapted, 2));
    }
}
