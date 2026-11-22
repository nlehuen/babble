package org.babblelang.compiler;

import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

/**
 * Manages the static constant fields ({@code CallSite}, {@code OpSite}, {@code ClassShape},
 * {@code Types.Type}, parameter-name arrays, and {@code CompiledMethod} holders) emitted on a
 * generated script class and initialized in chunked {@code <clinit>} helper methods (BIP014-I0).
 */
final class ConstantPool {
    private static final String STRING = "java/lang/String";
    private static final String RUNTIME = "org/babblelang/runtime/";
    private static final String CALL_SITE = RUNTIME + "CallSite";
    private static final String OP_SITE = RUNTIME + "OpSite";
    private static final String CLASS_SHAPE = RUNTIME + "ClassShape";
    private static final String TYPES = RUNTIME + "Types";
    private static final String TYPES_TYPE = RUNTIME + "Types$Type";

    // Constant pool strings are limited to 65535 bytes of modified UTF-8, at most three
    // per character ; longer strings are rebuilt from pieces.
    static final int MAX_STRING_CONSTANT = 20000;
    // Estimated bytecode size of the constants built by one <clinit> helper.
    private static final int MAX_CONSTANT_BYTES_PER_HELPER = 30000;

    private record Entry(String field, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
    }

    private final String className;
    private final String sourceName;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<List<String>, String> stringArrays = new HashMap<>();
    private final Map<List<Class<?>>, String> classArrays = new HashMap<>();
    private final Map<String, String> typeFields = new HashMap<>();

    ConstantPool(String className, String sourceName) {
        this.className = className;
        this.sourceName = sourceName;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    String add(String prefix, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
        String field = prefix + entries.size();
        entries.add(new Entry(field, descriptor, estimatedSize, builder));
        return field;
    }

    String stringArrayField(String[] values) {
        return stringArrays.computeIfAbsent(Arrays.asList(values), key ->
                add("NAMES", "[Ljava/lang/String;", estimateSize(values), m -> pushStringArray(m, values)));
    }

    String classArrayField(Class<?>[] classes) {
        return classArrays.computeIfAbsent(Arrays.asList(classes), key ->
                add("CLASSES", "[Ljava/lang/Class;", 8 + classes.length * 8, m -> pushClassArray(m, classes)));
    }

    private static void pushClassArray(MethodVisitor mv, Class<?>[] classes) {
        pushInt(mv, classes.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        for (int i = 0; i < classes.length; i++) {
            if (classes[i] != null) {
                mv.visitInsn(DUP);
                pushInt(mv, i);
                mv.visitLdcInsn(org.objectweb.asm.Type.getType(classes[i]));
                mv.visitInsn(AASTORE);
            }
        }
    }

    String opSiteField(String left, String right) {
        return add("OP", "L" + OP_SITE + ";", 16, m -> {
            m.visitTypeInsn(NEW, OP_SITE);
            m.visitInsn(DUP);
            pushString(m, left);
            pushString(m, right);
            m.visitMethodInsn(INVOKESPECIAL, OP_SITE, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        });
    }

    String callSiteField(CallSite site) {
        return add("CS", "L" + CALL_SITE + ";", 24 + estimateSize(site.argTexts()), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, site.sourceName());
            pushInt(m, site.line());
            pushString(m, site.calleeText());
            pushStringArray(m, site.argTexts());
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    String syntheticCallSiteField(String calleeText, int arity) {
        String[] argTexts = new String[arity];
        Arrays.fill(argTexts, "");
        return add("CS", "L" + CALL_SITE + ";", 24 + estimateSize(argTexts), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, sourceName);
            pushInt(m, 0);
            pushString(m, calleeText);
            pushStringArray(m, argTexts);
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    String javaCallSiteField(String calleeText, int arity) {
        String[] argTexts = new String[arity];
        for (int i = 0; i < arity; i++) {
            argTexts[i] = "<argument " + i + ">";
        }
        return add("CS", "L" + CALL_SITE + ";", 24 + estimateSize(argTexts), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, "<java>");
            pushInt(m, -1);
            pushString(m, calleeText);
            pushStringArray(m, argTexts);
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    String shapeField(ClassShape shape) {
        String encoded = shape.encode();
        return add("SHAPE", "L" + CLASS_SHAPE + ";", 16 + encoded.length() / MAX_STRING_CONSTANT * 6, m -> {
            pushString(m, encoded);
            m.visitMethodInsn(INVOKESTATIC, CLASS_SHAPE, "decode", "(Ljava/lang/String;)L" + CLASS_SHAPE + ";", false);
        });
    }

    String typeField(String descriptor) {
        return typeFields.computeIfAbsent(descriptor, d -> add("TYPE", "L" + TYPES_TYPE + ";", 16, m -> {
            pushString(m, d);
            m.visitMethodInsn(INVOKESTATIC, TYPES, "parse", "(Ljava/lang/String;)L" + TYPES_TYPE + ";", false);
        }));
    }

    /**
     * Declares a static field per constant, set in {@code <clinit>} by helper methods of
     * a bounded size, so that any number of constants fits. The JVM only lets
     * {@code <clinit>} itself set a final field, so the fields are not final ; nothing but
     * the helpers ever writes them.
     */
    void emitClinit(ClassWriter classWriter) {
        MethodVisitor clinit = classWriter.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        MethodVisitor helper = null;
        int size = 0;
        int helpers = 0;
        for (Entry constant : entries) {
            classWriter.visitField(ACC_STATIC | ACC_SYNTHETIC, constant.field(), constant.descriptor(), null, null).visitEnd();
            if (helper != null && size + constant.estimatedSize() > MAX_CONSTANT_BYTES_PER_HELPER) {
                endHelper(helper);
                helper = null;
            }
            if (helper == null) {
                String name = "constants$" + (helpers++);
                clinit.visitMethodInsn(INVOKESTATIC, className, name, "()V", false);
                helper = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "()V", null, null);
                helper.visitCode();
                size = 0;
            }
            constant.builder().accept(helper);
            helper.visitFieldInsn(PUTSTATIC, className, constant.field(), constant.descriptor());
            size += constant.estimatedSize();
        }
        if (helper != null) {
            endHelper(helper);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
    }

    private static void endHelper(MethodVisitor helper) {
        helper.visitInsn(RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
    }

    static void pushString(MethodVisitor mv, String value) {
        if (value.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(value);
            return;
        }
        mv.visitLdcInsn(value.substring(0, MAX_STRING_CONSTANT));
        for (int start = MAX_STRING_CONSTANT; start < value.length(); start += MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(value.substring(start, Math.min(value.length(), start + MAX_STRING_CONSTANT)));
            mv.visitMethodInsn(INVOKEVIRTUAL, STRING, "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        }
    }

    static void pushStringArray(MethodVisitor mv, String[] values) {
        pushInt(mv, values.length);
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                mv.visitInsn(DUP);
                pushInt(mv, i);
                pushString(mv, values[i]);
                mv.visitInsn(AASTORE);
            }
        }
    }

    static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    static int estimateSize(String[] values) {
        int size = 8;
        for (String value : values) {
            size += 8 + (value == null ? 0 : 6 * (value.length() / MAX_STRING_CONSTANT));
        }
        return size;
    }
}
