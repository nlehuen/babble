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

public class Rm003P4SamMetafactoryTestCase {

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
}
