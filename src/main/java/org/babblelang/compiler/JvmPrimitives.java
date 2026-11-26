package org.babblelang.compiler;

import static org.objectweb.asm.Opcodes.*;

import java.lang.invoke.LambdaMetafactory;
import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.JavaTypes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Centralized JVM internal names, invokedynamic bootstrap handles, primitive/descriptor mappings,
 * box/unbox/widening/adaptation opcodes, and interface/Java bridge helpers shared across {@link
 * HirEmitter}, {@link ClassGen}, {@link InterfaceGen}, and the specialized emitters (BIP027-I2).
 */
final class JvmPrimitives {
    private JvmPrimitives() {}

    static final String OBJECT = "java/lang/Object";
    static final String STRING = "java/lang/String";
    static final String RUNTIME = "org/babblelang/runtime/";
    static final String NAMESPACE = RUNTIME + "Namespace";
    static final String SCRIPT_RUNTIME = RUNTIME + "ScriptRuntime";
    static final String CALL_SITE = RUNTIME + "CallSite";
    static final String OP_SITE = RUNTIME + "OpSite";
    static final String CALLABLE = RUNTIME + "Callable";
    static final String COMPILED_FUNCTION = RUNTIME + "CompiledFunction";
    static final String FUNCTION_BODY = RUNTIME + "FunctionBody";
    static final String ARGUMENTS = RUNTIME + "Arguments";
    static final String BINDER = RUNTIME + "Arguments$Binder";
    static final String ARGUMENTS_VIEW = RUNTIME + "ArgumentsView";
    static final String SPREADS = RUNTIME + "Spreads";
    static final String OVERLOAD_SET = RUNTIME + "OverloadSet";
    static final String BABBLE_EXCEPTION = RUNTIME + "BabbleException";
    static final String FRAMES = RUNTIME + "Frames";
    static final String CELL = RUNTIME + "Cell";
    static final String BYTE_CELL = RUNTIME + "ByteCell";
    static final String SHORT_CELL = RUNTIME + "ShortCell";
    static final String INT_CELL = RUNTIME + "IntCell";
    static final String LONG_CELL = RUNTIME + "LongCell";
    static final String FLOAT_CELL = RUNTIME + "FloatCell";
    static final String DOUBLE_CELL = RUNTIME + "DoubleCell";
    static final String BOOL_CELL = RUNTIME + "BoolCell";
    static final String CHAR_CELL = RUNTIME + "CharCell";
    static final String CLASS_SHAPE = RUNTIME + "ClassShape";
    static final String BABBLE_CLASS = RUNTIME + "BabbleClass";
    static final String BABBLE_INTERFACE = RUNTIME + "BabbleInterface";
    static final String BABBLE_OBJECTS = RUNTIME + "BabbleObjects";
    static final String CLASS_BODY = RUNTIME + "ClassBody";
    static final String CLASSES = RUNTIME + "Classes";
    static final String INSTANCE_FACTORY = RUNTIME + "InstanceFactory";
    static final String COMPILED_METHOD = RUNTIME + "CompiledMethod";
    static final String METHOD_BODY = RUNTIME + "MethodBody";
    static final String TYPES = RUNTIME + "Types";
    static final String TYPES_TYPE = RUNTIME + "Types$Type";
    static final String VALUES = RUNTIME + "Values";
    static final String JAVA_TYPES = RUNTIME + "JavaTypes";
    static final String JAVA_OBJECT = "org/babblelang/engine/impl/natives/java/JavaObject";

    static final String RUN_DESCRIPTOR =
            "(L" + SCRIPT_RUNTIME + ";L" + NAMESPACE + ";)Ljava/lang/Object;";
    static final String BODY_DESCRIPTOR =
            "(L"
                    + COMPILED_FUNCTION
                    + ";Ljava/lang/Object;L"
                    + SCRIPT_RUNTIME
                    + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                    + CALL_SITE
                    + ";)Ljava/lang/Object;";
    static final String CLASS_BODY_DESCRIPTOR =
            "(L"
                    + BABBLE_CLASS
                    + ";Ljava/lang/Object;L"
                    + SCRIPT_RUNTIME
                    + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                    + CALL_SITE
                    + ";)Ljava/lang/Object;";
    static final String METHOD_DESCRIPTOR =
            "(L"
                    + COMPILED_METHOD
                    + ";L"
                    + SCRIPT_RUNTIME
                    + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                    + CALL_SITE
                    + ";)Ljava/lang/Object;";

    static final Handle METAFACTORY =
            new Handle(
                    H_INVOKESTATIC,
                    Type.getInternalName(LambdaMetafactory.class),
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false);

    static final String BOOTSTRAP = RUNTIME + "Bootstrap";
    static final Handle SELECT_BOOTSTRAP =
            new Handle(
                    H_INVOKESTATIC,
                    BOOTSTRAP,
                    "select",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                    false);
    static final Handle NAME_BOOTSTRAP =
            new Handle(
                    H_INVOKESTATIC,
                    BOOTSTRAP,
                    "name",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                    false);
    static final Handle CALL_BOOTSTRAP =
            new Handle(
                    H_INVOKESTATIC,
                    BOOTSTRAP,
                    "call",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false);
    static final Handle TYPED_CALL_BOOTSTRAP =
            new Handle(
                    H_INVOKESTATIC,
                    BOOTSTRAP,
                    "typedCall",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false);
    static final Handle TYPED_CALL_OPT_BOOTSTRAP =
            new Handle(
                    H_INVOKESTATIC,
                    BOOTSTRAP,
                    "typedCallOpt",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;",
                    false);

    // ---------------------------------------------------------------- descriptors

    static String jvmDescriptor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case BOOL -> "Z";
            case CHAR -> "C";
            case NONE -> "Ljava/lang/Object;";
        };
    }

    static String functionDirectDescriptor(HirFunction fn) {
        return "(L"
                + COMPILED_FUNCTION
                + ";Ljava/lang/Object;L"
                + SCRIPT_RUNTIME
                + ";L"
                + BINDER
                + ";"
                + "Ljava/lang/Object;".repeat(fn.params().size())
                + ")Ljava/lang/Object;";
    }

    static String functionSpecDescriptor(HirFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L")
                .append(COMPILED_FUNCTION)
                .append(";Ljava/lang/Object;L")
                .append(SCRIPT_RUNTIME)
                .append(';');
        for (HirParam param : fn.params()) {
            sb.append(jvmDescriptor(param.kind()));
        }
        if (fn.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(fn.returnKind()));
        return sb.toString();
    }

    static String functionSpecDescriptor(LoweringTargets.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L")
                .append(COMPILED_FUNCTION)
                .append(";Ljava/lang/Object;L")
                .append(SCRIPT_RUNTIME)
                .append(';');
        for (PrimitiveKind pk : sig.parameterKinds()) {
            sb.append(jvmDescriptor(pk));
        }
        if (sig.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(sig.returnKind()));
        return sb.toString();
    }

    static String functionDirectDescriptor(LoweringTargets.FunctionSignature sig) {
        return "(L"
                + COMPILED_FUNCTION
                + ";Ljava/lang/Object;L"
                + SCRIPT_RUNTIME
                + ";L"
                + BINDER
                + ";"
                + "Ljava/lang/Object;".repeat(sig.parameterKinds().length)
                + ")Ljava/lang/Object;";
    }

    static String methodDirectDescriptor(HirFunction fn) {
        return "(L"
                + COMPILED_METHOD
                + ";L"
                + SCRIPT_RUNTIME
                + ";L"
                + BINDER
                + ";"
                + "Ljava/lang/Object;".repeat(fn.params().size())
                + ")Ljava/lang/Object;";
    }

    static String methodSpecDescriptor(HirFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(COMPILED_METHOD).append(";L").append(SCRIPT_RUNTIME).append(';');
        for (HirParam param : fn.params()) {
            sb.append(jvmDescriptor(param.kind()));
        }
        if (fn.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(fn.returnKind()));
        return sb.toString();
    }

    static String methodSpecDescriptor(LoweringTargets.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(COMPILED_METHOD).append(";L").append(SCRIPT_RUNTIME).append(';');
        for (PrimitiveKind pk : sig.parameterKinds()) {
            sb.append(jvmDescriptor(pk));
        }
        if (sig.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(sig.returnKind()));
        return sb.toString();
    }

    static String methodDirectDescriptor(LoweringTargets.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L")
                .append(COMPILED_METHOD)
                .append(";L")
                .append(SCRIPT_RUNTIME)
                .append(";L")
                .append(BINDER)
                .append(';');
        for (int i = 0; i < sig.parameterKinds().length; i++) {
            sb.append("Ljava/lang/Object;");
        }
        sb.append(")Ljava/lang/Object;");
        return sb.toString();
    }

    static String fastRecordFactoryDescriptor(String jvmClassName, PrimitiveKind[] paramKinds) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(BABBLE_CLASS).append(";L").append(SCRIPT_RUNTIME).append(';');
        for (PrimitiveKind pk : paramKinds) {
            sb.append(jvmDescriptor(pk));
        }
        sb.append(")L").append(jvmClassName).append(';');
        return sb.toString();
    }

    static String cellClass(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> BYTE_CELL;
            case SHORT -> SHORT_CELL;
            case INT -> INT_CELL;
            case LONG -> LONG_CELL;
            case FLOAT -> FLOAT_CELL;
            case DOUBLE -> DOUBLE_CELL;
            case BOOL -> BOOL_CELL;
            case CHAR -> CHAR_CELL;
            case NONE -> CELL;
        };
    }

    // ---------------------------------------------------------------- stack & local opcodes

    static void loadKindOn(MethodVisitor m, PrimitiveKind kind, int slot) {
        int op =
                switch (kind) {
                    case BYTE, SHORT, INT, BOOL, CHAR -> ILOAD;
                    case LONG -> LLOAD;
                    case FLOAT -> FLOAD;
                    case DOUBLE -> DLOAD;
                    case NONE -> ALOAD;
                };
        m.visitVarInsn(op, slot);
    }

    static void storeKindOn(MethodVisitor m, PrimitiveKind kind, int slot) {
        int op =
                switch (kind) {
                    case BYTE, SHORT, INT, BOOL, CHAR -> ISTORE;
                    case LONG -> LSTORE;
                    case FLOAT -> FSTORE;
                    case DOUBLE -> DSTORE;
                    case NONE -> ASTORE;
                };
        m.visitVarInsn(op, slot);
    }

    static void dupOn(MethodVisitor m, PrimitiveKind kind) {
        m.visitInsn(kind.slots() == 2 ? DUP2 : DUP);
    }

    static void popOn(MethodVisitor m, PrimitiveKind kind) {
        m.visitInsn(kind.slots() == 2 ? POP2 : POP);
    }

    static int returnInsn(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> IRETURN;
            case LONG -> LRETURN;
            case FLOAT -> FRETURN;
            case DOUBLE -> DRETURN;
            case NONE -> ARETURN;
        };
    }

    static void emitDefaultValueOn(MethodVisitor m, PrimitiveKind kind) {
        switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> m.visitInsn(ICONST_0);
            case LONG -> m.visitInsn(LCONST_0);
            case FLOAT -> m.visitInsn(FCONST_0);
            case DOUBLE -> m.visitInsn(DCONST_0);
            case NONE -> m.visitInsn(ACONST_NULL);
        }
    }

    static void boxOn(MethodVisitor m, PrimitiveKind kind) {
        switch (kind) {
            case BYTE ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Byte",
                            "valueOf",
                            "(B)Ljava/lang/Byte;",
                            false);
            case SHORT ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Short",
                            "valueOf",
                            "(S)Ljava/lang/Short;",
                            false);
            case INT ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Integer",
                            "valueOf",
                            "(I)Ljava/lang/Integer;",
                            false);
            case LONG ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Long",
                            "valueOf",
                            "(J)Ljava/lang/Long;",
                            false);
            case FLOAT ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Float",
                            "valueOf",
                            "(F)Ljava/lang/Float;",
                            false);
            case DOUBLE ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Double",
                            "valueOf",
                            "(D)Ljava/lang/Double;",
                            false);
            case BOOL ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Boolean",
                            "valueOf",
                            "(Z)Ljava/lang/Boolean;",
                            false);
            case CHAR ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            "java/lang/Character",
                            "valueOf",
                            "(C)Ljava/lang/Character;",
                            false);
            case NONE -> {}
        }
    }

    static void unboxOn(MethodVisitor m, PrimitiveKind kind) {
        switch (kind) {
            case BYTE ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxByte", "(Ljava/lang/Object;)B", false);
            case SHORT ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxShort", "(Ljava/lang/Object;)S", false);
            case INT ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxInt", "(Ljava/lang/Object;)I", false);
            case LONG ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxLong", "(Ljava/lang/Object;)J", false);
            case FLOAT ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxFloat", "(Ljava/lang/Object;)F", false);
            case DOUBLE ->
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            JAVA_TYPES,
                            "unboxDouble",
                            "(Ljava/lang/Object;)D",
                            false);
            case BOOL ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxBool", "(Ljava/lang/Object;)Z", false);
            case CHAR ->
                    m.visitMethodInsn(
                            INVOKESTATIC, JAVA_TYPES, "unboxChar", "(Ljava/lang/Object;)C", false);
            case NONE -> {}
        }
    }

    static PrimitiveKind targetKindForRank(int rank) {
        return switch (rank) {
            case 1 -> PrimitiveKind.SHORT;
            case 2 -> PrimitiveKind.INT;
            case 3 -> PrimitiveKind.LONG;
            case 4 -> PrimitiveKind.FLOAT;
            case 5 -> PrimitiveKind.DOUBLE;
            default -> PrimitiveKind.NONE;
        };
    }

    static boolean isNumericKind(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    static int numericOrder(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> 0;
            case SHORT -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
        };
    }

    static boolean emitPrimitiveWidening(MethodVisitor m, PrimitiveKind from, PrimitiveKind to) {
        if (from == to) {
            return true;
        }
        if (from == PrimitiveKind.CHAR && isNumericKind(to) && numericOrder(to) >= 2) {
            return emitPrimitiveWidening(m, PrimitiveKind.INT, to);
        }
        int f = numericOrder(from);
        int t = numericOrder(to);
        if (f < 0 || t < 0 || f > t) {
            return false;
        }
        if (f <= 2) {
            switch (to) {
                case BYTE, SHORT, INT -> {}
                case LONG -> m.visitInsn(I2L);
                case FLOAT -> m.visitInsn(I2F);
                case DOUBLE -> m.visitInsn(I2D);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (from == PrimitiveKind.LONG) {
            switch (to) {
                case FLOAT -> m.visitInsn(L2F);
                case DOUBLE -> m.visitInsn(L2D);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (from == PrimitiveKind.FLOAT && to == PrimitiveKind.DOUBLE) {
            m.visitInsn(F2D);
            return true;
        }
        return false;
    }

    static void emitAdaptKind(MethodVisitor m, PrimitiveKind from, PrimitiveKind to) {
        if (from == to) {
            return;
        }
        if (to == PrimitiveKind.NONE) {
            boxOn(m, from);
            return;
        }
        if (emitPrimitiveWidening(m, from, to)) {
            return;
        }
        if (from == PrimitiveKind.NONE) {
            switch (to) {
                case BYTE -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                }
                case SHORT -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
                }
                case INT -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                }
                case LONG -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                }
                case FLOAT -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                }
                case DOUBLE -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                }
                case BOOL -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                }
                case CHAR -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Character");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                }
                case NONE -> {}
            }
            return;
        }
        boxOn(m, from);
        emitAdaptKind(m, PrimitiveKind.NONE, to);
    }

    static void emitWidenBoxed(MethodVisitor m, int widenRank) {
        if (widenRank == 5) {
            m.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "toDouble",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
        } else if (widenRank >= 0) {
            ConstantPool.pushInt(m, widenRank);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "widenNumeric",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;",
                    false);
        }
    }

    static PrimitiveKind javaPrimitiveKind(Class<?> cls) {
        if (cls == byte.class) return PrimitiveKind.BYTE;
        if (cls == short.class) return PrimitiveKind.SHORT;
        if (cls == int.class) return PrimitiveKind.INT;
        if (cls == long.class) return PrimitiveKind.LONG;
        if (cls == float.class) return PrimitiveKind.FLOAT;
        if (cls == double.class) return PrimitiveKind.DOUBLE;
        if (cls == boolean.class) return PrimitiveKind.BOOL;
        if (cls == char.class) return PrimitiveKind.CHAR;
        return PrimitiveKind.NONE;
    }

    static void emitPushClassConstant(MethodVisitor m, Class<?> cls) {
        if (cls.isPrimitive()) {
            String wrapper = Type.getInternalName(JavaTypes.box(cls));
            m.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
        } else {
            m.visitLdcInsn(Type.getType(cls));
        }
    }

    // ---------------------------------------------------------------- bridge descriptors &
    // marshaling

    static String bridgeMethodName(String member) {
        return "bm$" + member;
    }

    static String bridgeDescriptor(LoweringTargets.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder("(L").append(SCRIPT_RUNTIME).append(';');
        for (PrimitiveKind pk : sig.parameterKinds()) {
            sb.append(jvmDescriptor(pk));
        }
        if (sig.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(sig.returnKind()));
        return sb.toString();
    }

    static int[] bridgeParamSlots(LoweringTargets.FunctionSignature sig) {
        PrimitiveKind[] kinds = sig.parameterKinds();
        int[] paramSlots = new int[kinds.length];
        int next = 2;
        for (int i = 0; i < kinds.length; i++) {
            paramSlots[i] = next;
            next += kinds[i].slots();
        }
        return paramSlots;
    }

    static int bridgeMaskSlot(LoweringTargets.FunctionSignature sig) {
        if (!sig.hasOptionalMask()) {
            return -1;
        }
        int next = 2;
        for (PrimitiveKind pk : sig.parameterKinds()) {
            next += pk.slots();
        }
        return next;
    }

    static boolean allOptionalFrom(HirFunction fn, int startIndex) {
        List<HirParam> params = fn.params();
        for (int i = startIndex; i < params.size(); i++) {
            if (params.get(i).defaultValue() == null) {
                return false;
            }
        }
        return true;
    }

    static void emitLoadBridgeArgBoxed(
            MethodVisitor m, LoweringTargets.FunctionSignature sig, int[] paramSlots, int index) {
        PrimitiveKind pk = sig.parameterKinds()[index];
        if (!sig.isOptional(index)) {
            loadKindOn(m, pk, paramSlots[index]);
            boxOn(m, pk);
            return;
        }
        int maskSlot = bridgeMaskSlot(sig);
        Label present = new Label();
        Label done = new Label();
        m.visitVarInsn(ILOAD, maskSlot);
        ConstantPool.pushInt(m, 1 << index);
        m.visitInsn(IAND);
        m.visitJumpInsn(IFEQ, present);
        m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
        m.visitJumpInsn(GOTO, done);
        m.visitLabel(present);
        loadKindOn(m, pk, paramSlots[index]);
        boxOn(m, pk);
        m.visitLabel(done);
    }

    static void emitRemapBridgeMask(
            MethodVisitor m,
            LoweringTargets.FunctionSignature sig,
            HirFunction fn,
            int ifaceCount) {
        if (!fn.hasOptionalMask()) {
            return;
        }
        if (sig.hasOptionalMask()) {
            m.visitVarInsn(ILOAD, bridgeMaskSlot(sig));
            ConstantPool.pushInt(m, fn.optionalParamBits());
            ConstantPool.pushInt(m, ifaceCount);
            m.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "remapMask", "(III)I", false);
        } else {
            ConstantPool.pushInt(m, Arguments.remapMask(0, fn.optionalParamBits(), ifaceCount));
        }
    }

    static void emitAdaptBridgeArg(
            MethodVisitor m,
            LoweringTargets.FunctionSignature sig,
            int[] paramSlots,
            int index,
            PrimitiveKind toK,
            int widenRank) {
        PrimitiveKind fromK = sig.parameterKinds()[index];
        boolean needsNullGuard =
                sig.isOptional(index)
                        && fromK == PrimitiveKind.NONE
                        && (toK != PrimitiveKind.NONE || widenRank >= 0);
        if (!needsNullGuard) {
            loadKindOn(m, fromK, paramSlots[index]);
            emitAdaptKind(m, fromK, toK);
            if (toK == PrimitiveKind.NONE && widenRank >= 0) {
                emitWidenBoxed(m, widenRank);
            }
            return;
        }
        int maskSlot = bridgeMaskSlot(sig);
        Label present = new Label();
        Label done = new Label();
        m.visitVarInsn(ILOAD, maskSlot);
        ConstantPool.pushInt(m, 1 << index);
        m.visitInsn(IAND);
        m.visitJumpInsn(IFEQ, present);
        emitDefaultValueOn(m, toK);
        m.visitJumpInsn(GOTO, done);
        m.visitLabel(present);
        loadKindOn(m, fromK, paramSlots[index]);
        emitAdaptKind(m, fromK, toK);
        if (toK == PrimitiveKind.NONE && widenRank >= 0) {
            emitWidenBoxed(m, widenRank);
        }
        m.visitLabel(done);
    }

    static void packBridgeArgsArray(
            MethodVisitor m, LoweringTargets.FunctionSignature sig, int[] paramSlots) {
        PrimitiveKind[] kinds = sig.parameterKinds();
        if (kinds.length == 0) {
            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(m, kinds.length);
        m.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < kinds.length; i++) {
            m.visitInsn(DUP);
            ConstantPool.pushInt(m, i);
            emitLoadBridgeArgBoxed(m, sig, paramSlots, i);
            m.visitInsn(AASTORE);
        }
    }

    private static void emitLoadPrimitiveBridgeArg(
            MethodVisitor m,
            PrimitiveKind fromKind,
            int slot,
            PrimitiveKind targetKind,
            int widenRank) {
        loadKindOn(m, fromKind, slot);
        if (targetKind == PrimitiveKind.NONE && widenRank >= 0) {
            boxOn(m, fromKind);
            emitWidenBoxed(m, widenRank);
        } else {
            emitAdaptKind(m, fromKind, targetKind);
        }
    }

    static void emitLoadJavaBridgeArg(
            MethodVisitor m,
            Class<?> pType,
            int slot,
            PrimitiveKind targetKind,
            int widenRank,
            String checkedDesc) {
        PrimitiveKind pk = javaPrimitiveKind(pType);
        if (pk != PrimitiveKind.NONE) {
            emitLoadPrimitiveBridgeArg(m, pk, slot, targetKind, widenRank);
            return;
        }
        m.visitVarInsn(ALOAD, slot);
        if (pType != String.class
                && pType != Byte.class
                && pType != Short.class
                && pType != Integer.class
                && pType != Long.class
                && pType != Float.class
                && pType != Double.class
                && pType != Boolean.class
                && pType != Character.class) {
            m.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "fromJava",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
        }
        if (checkedDesc != null) {
            m.visitLdcInsn(checkedDesc);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    TYPES,
                    "check",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                    false);
        }
        if (targetKind == PrimitiveKind.NONE && widenRank >= 0) {
            emitWidenBoxed(m, widenRank);
        } else {
            emitAdaptKind(m, PrimitiveKind.NONE, targetKind);
        }
    }

    static void emitPackJavaBridgeArgs(
            MethodVisitor m, Class<?>[] paramTypes, int[] javaParamSlots) {
        if (paramTypes.length == 0) {
            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(m, paramTypes.length);
        m.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < paramTypes.length; i++) {
            m.visitInsn(DUP);
            ConstantPool.pushInt(m, i);
            emitLoadJavaBridgeArg(
                    m, paramTypes[i], javaParamSlots[i], PrimitiveKind.NONE, -1, null);
            m.visitInsn(AASTORE);
        }
    }

    static void emitReturnToJava(
            MethodVisitor m,
            PrimitiveKind fromKind,
            Class<?> retType,
            String description,
            int rtSlot) {
        if (retType == void.class) {
            m.visitInsn(fromKind.slots() == 2 ? POP2 : POP);
            m.visitInsn(RETURN);
            return;
        }
        PrimitiveKind retKind = javaPrimitiveKind(retType);
        if (retKind.isPrimitive() && fromKind.isPrimitive()) {
            if (fromKind == retKind
                    || (isNumericKind(fromKind)
                            && isNumericKind(retKind)
                            && emitPrimitiveWidening(m, fromKind, retKind))) {
                m.visitInsn(returnInsn(retKind));
                return;
            }
        }
        boxOn(m, fromKind);
        emitPushClassConstant(m, retType);
        ConstantPool.pushString(m, description);
        m.visitVarInsn(ALOAD, rtSlot);
        m.visitMethodInsn(
                INVOKESTATIC,
                JAVA_TYPES,
                "convertResult",
                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;L"
                        + SCRIPT_RUNTIME
                        + ";)Ljava/lang/Object;",
                false);
        if (retKind.isPrimitive()) {
            unboxWrapperAndReturn(m, retKind);
        } else {
            if (retType != Object.class) {
                m.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
            }
            m.visitInsn(ARETURN);
        }
    }

    private static void unboxWrapperAndReturn(MethodVisitor m, PrimitiveKind retKind) {
        switch (retKind) {
            case INT -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                m.visitInsn(IRETURN);
            }
            case DOUBLE -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Double");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                m.visitInsn(DRETURN);
            }
            case BOOL -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                m.visitInsn(IRETURN);
            }
            case LONG -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Long");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                m.visitInsn(LRETURN);
            }
            case FLOAT -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Float");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                m.visitInsn(FRETURN);
            }
            case SHORT -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Short");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                m.visitInsn(IRETURN);
            }
            case BYTE -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                m.visitInsn(IRETURN);
            }
            case CHAR -> {
                m.visitTypeInsn(CHECKCAST, "java/lang/Character");
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                m.visitInsn(IRETURN);
            }
            case NONE -> m.visitInsn(ARETURN);
        }
    }
}
