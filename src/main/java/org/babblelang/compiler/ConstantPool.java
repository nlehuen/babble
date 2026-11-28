package org.babblelang.compiler;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Manages constant materialization for a generated script class and its compiled member classes:
 * data constants ({@code CallSite}, {@code OpSite}, {@code ClassShape}, {@code Types.Type}, arrays,
 * enum switch maps) are emitted lazily via {@code CONSTANT_Dynamic} ({@code condy}) with automatic
 * {@code <clinit>} fallback for oversized strings, while {@code CompiledMethod} and {@code
 * OverloadSet} holders use chunked {@code <clinit>} helpers (BIP014-I0, BIP001).
 */
final class ConstantPool {
    private static final String STRING = "java/lang/String";
    private static final String RUNTIME = "org/babblelang/runtime/";
    private static final String BABBLE_CONSTANTS = RUNTIME + "BabbleConstants";
    private static final String CALL_SITE = RUNTIME + "CallSite";
    private static final String OP_SITE = RUNTIME + "OpSite";
    private static final String CLASS_SHAPE = RUNTIME + "ClassShape";
    private static final String TYPES = RUNTIME + "Types";
    private static final String TYPES_TYPE = RUNTIME + "Types$Type";
    private static final String MATCH_SUPPORT = RUNTIME + "MatchSupport";

    private static final Handle STRING_ARRAY_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    BABBLE_CONSTANTS,
                    "stringArray",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)[Ljava/lang/String;",
                    false);
    private static final Handle BOOLEAN_ARRAY_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    BABBLE_CONSTANTS,
                    "booleanArray",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)[Z",
                    false);
    private static final Handle CLASS_ARRAY_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    BABBLE_CONSTANTS,
                    "classArray",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;[Ljava/lang/Class;)[Ljava/lang/Class;",
                    false);
    private static final Handle CALL_SITE_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    CALL_SITE,
                    "constant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)L"
                            + CALL_SITE
                            + ";",
                    false);
    private static final Handle OP_SITE_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    OP_SITE,
                    "constant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)L"
                            + OP_SITE
                            + ";",
                    false);
    private static final Handle CLASS_SHAPE_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    CLASS_SHAPE,
                    "constant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)L"
                            + CLASS_SHAPE
                            + ";",
                    false);
    private static final Handle TYPE_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    TYPES,
                    "constant",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)L"
                            + TYPES_TYPE
                            + ";",
                    false);
    private static final Handle ENUM_SWITCH_MAP_BSM =
            new Handle(
                    H_INVOKESTATIC,
                    MATCH_SUPPORT,
                    "constantEnumSwitchMap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)[I",
                    false);

    // Constant pool strings are limited to 65535 bytes of modified UTF-8, at most three
    // per character ; longer strings are rebuilt from pieces.
    static final int MAX_STRING_CONSTANT = 20000;
    // Estimated bytecode size of the constants built by one <clinit> helper.
    private static final int MAX_CONSTANT_BYTES_PER_HELPER = 30000;

    private record Entry(
            String field, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {}

    private final String className;
    private final String sourceName;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<List<String>, String> stringArrays = new HashMap<>();

    ConstantPool(String className, String sourceName) {
        this.className = className;
        this.sourceName = sourceName;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    String add(
            String prefix, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
        String field = prefix + entries.size();
        entries.add(new Entry(field, descriptor, estimatedSize, builder));
        return field;
    }

    /** Encodes a {@code String[]} losslessly for {@code BabbleConstants.unpackStrings}. */
    static String packStrings(String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null) {
                sb.append('n');
            } else {
                sb.append(v.length()).append(':').append(v);
            }
        }
        return sb.toString();
    }

    void emitStringArray(MethodVisitor mv, String[] values) {
        if (values == null || values.length == 0) {
            mv.visitFieldInsn(GETSTATIC, BABBLE_CONSTANTS, "EMPTY_STRINGS", "[Ljava/lang/String;");
            return;
        }
        String packed = packStrings(values);
        if (packed.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(
                    new ConstantDynamic("_", "[Ljava/lang/String;", STRING_ARRAY_BSM, packed));
            return;
        }
        String field = stringArrayField(values);
        mv.visitFieldInsn(GETSTATIC, className, field, "[Ljava/lang/String;");
    }

    void emitBooleanArray(MethodVisitor mv, boolean[] values) {
        if (values == null || values.length == 0) {
            mv.visitFieldInsn(GETSTATIC, BABBLE_CONSTANTS, "EMPTY_BOOLEANS", "[Z");
            return;
        }
        StringBuilder bits = new StringBuilder(values.length);
        for (boolean b : values) {
            bits.append(b ? '1' : '0');
        }
        mv.visitLdcInsn(new ConstantDynamic("_", "[Z", BOOLEAN_ARRAY_BSM, bits.toString()));
    }

    void emitClassArray(MethodVisitor mv, Class<?>[] classes) {
        if (classes == null || classes.length == 0) {
            mv.visitFieldInsn(GETSTATIC, BABBLE_CONSTANTS, "EMPTY_CLASSES", "[Ljava/lang/Class;");
            return;
        }
        Object[] bsmArgs = new Object[classes.length];
        for (int i = 0; i < classes.length; i++) {
            bsmArgs[i] = Type.getType(classes[i]);
        }
        mv.visitLdcInsn(new ConstantDynamic("_", "[Ljava/lang/Class;", CLASS_ARRAY_BSM, bsmArgs));
    }

    void emitOpSite(MethodVisitor mv, String left, String right) {
        if (left.length() <= MAX_STRING_CONSTANT && right.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(
                    new ConstantDynamic("_", "L" + OP_SITE + ";", OP_SITE_BSM, left, right));
            return;
        }
        String field =
                add(
                        "OP",
                        "L" + OP_SITE + ";",
                        16,
                        m -> {
                            m.visitTypeInsn(NEW, OP_SITE);
                            m.visitInsn(DUP);
                            pushString(m, left);
                            pushString(m, right);
                            m.visitMethodInsn(
                                    INVOKESPECIAL,
                                    OP_SITE,
                                    "<init>",
                                    "(Ljava/lang/String;Ljava/lang/String;)V",
                                    false);
                        });
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + OP_SITE + ";");
    }

    void emitCallSite(MethodVisitor mv, CallSite site, boolean keepArgTexts) {
        String src = site.sourceName() != null ? site.sourceName() : "";
        String callee = site.calleeText() != null ? site.calleeText() : "";
        String packedArgs = keepArgTexts ? packStrings(site.argTexts()) : "";
        if (src.length() <= MAX_STRING_CONSTANT
                && callee.length() <= MAX_STRING_CONSTANT
                && packedArgs.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(
                    new ConstantDynamic(
                            "_",
                            "L" + CALL_SITE + ";",
                            CALL_SITE_BSM,
                            src,
                            site.line(),
                            callee,
                            packedArgs));
            return;
        }
        String[] argTexts = keepArgTexts ? site.argTexts() : new String[0];
        String field =
                add(
                        "CS",
                        "L" + CALL_SITE + ";",
                        24 + estimateSize(argTexts),
                        m -> {
                            m.visitTypeInsn(NEW, CALL_SITE);
                            m.visitInsn(DUP);
                            pushString(m, src);
                            pushInt(m, site.line());
                            pushString(m, callee);
                            pushStringArray(m, argTexts);
                            m.visitMethodInsn(
                                    INVOKESPECIAL,
                                    CALL_SITE,
                                    "<init>",
                                    "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V",
                                    false);
                        });
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CALL_SITE + ";");
    }

    void emitSyntheticCallSite(MethodVisitor mv, String calleeText, int arity) {
        String packed = arity <= 0 ? "" : "0:".repeat(arity);
        String src = sourceName != null ? sourceName : "";
        String callee = calleeText != null ? calleeText : "";
        mv.visitLdcInsn(
                new ConstantDynamic(
                        "_", "L" + CALL_SITE + ";", CALL_SITE_BSM, src, 0, callee, packed));
    }

    void emitJavaCallSite(MethodVisitor mv, String calleeText, int arity) {
        String[] argTexts = new String[arity];
        for (int i = 0; i < arity; i++) {
            argTexts[i] = "<argument " + i + ">";
        }
        mv.visitLdcInsn(
                new ConstantDynamic(
                        "_",
                        "L" + CALL_SITE + ";",
                        CALL_SITE_BSM,
                        "<java>",
                        -1,
                        calleeText != null ? calleeText : "",
                        packStrings(argTexts)));
    }

    void emitClassShape(MethodVisitor mv, ClassShape shape) {
        String encoded = shape.encode();
        if (encoded.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(
                    new ConstantDynamic("_", "L" + CLASS_SHAPE + ";", CLASS_SHAPE_BSM, encoded));
            return;
        }
        String field =
                add(
                        "SHAPE",
                        "L" + CLASS_SHAPE + ";",
                        16 + encoded.length() / MAX_STRING_CONSTANT * 6,
                        m -> {
                            pushString(m, encoded);
                            m.visitMethodInsn(
                                    INVOKESTATIC,
                                    CLASS_SHAPE,
                                    "decode",
                                    "(Ljava/lang/String;)L" + CLASS_SHAPE + ";",
                                    false);
                        });
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CLASS_SHAPE + ";");
    }

    void emitType(MethodVisitor mv, String descriptor) {
        mv.visitLdcInsn(new ConstantDynamic("_", "L" + TYPES_TYPE + ";", TYPE_BSM, descriptor));
    }

    void emitEnumSwitchMap(MethodVisitor mv, Class<?> enumClass, String[] constantNames) {
        mv.visitLdcInsn(
                new ConstantDynamic(
                        "_",
                        "[I",
                        ENUM_SWITCH_MAP_BSM,
                        Type.getType(enumClass),
                        packStrings(constantNames)));
    }

    private String stringArrayField(String[] values) {
        return stringArrays.computeIfAbsent(
                Arrays.asList(values),
                key ->
                        add(
                                "NAMES",
                                "[Ljava/lang/String;",
                                estimateSize(values),
                                m -> pushStringArray(m, values)));
    }

    /**
     * Declares a static field per remaining non-{@code condy} constant, set in {@code <clinit>} by
     * helper methods of a bounded size. Emits no {@code <clinit>} when {@link #entries} is empty.
     */
    void emitClinit(ClassWriter classWriter) {
        if (entries.isEmpty()) {
            return;
        }
        MethodVisitor clinit = classWriter.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        MethodVisitor helper = null;
        int size = 0;
        int helpers = 0;
        for (Entry constant : entries) {
            classWriter
                    .visitField(
                            ACC_STATIC | ACC_SYNTHETIC,
                            constant.field(),
                            constant.descriptor(),
                            null,
                            null)
                    .visitEnd();
            if (helper != null && size + constant.estimatedSize() > MAX_CONSTANT_BYTES_PER_HELPER) {
                endHelper(helper);
                helper = null;
            }
            if (helper == null) {
                String name = "constants$" + (helpers++);
                clinit.visitMethodInsn(INVOKESTATIC, className, name, "()V", false);
                helper =
                        classWriter.visitMethod(
                                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "()V", null, null);
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
        for (int start = MAX_STRING_CONSTANT;
                start < value.length();
                start += MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(
                    value.substring(start, Math.min(value.length(), start + MAX_STRING_CONSTANT)));
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    STRING,
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false);
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
