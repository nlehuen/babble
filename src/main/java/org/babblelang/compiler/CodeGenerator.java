package org.babblelang.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

/**
 * Generates the class of one script (phases 1 to 3 of the bytecode compiler design).
 * <p>
 * Names are resolved statically ({@link Resolution}, phase 2) : the variables of
 * functions, blocks and class bodies are JVM locals of the method they are compiled into,
 * or {@link org.babblelang.runtime.Cell}s when a nested function captures them, and a
 * function or class value carries the cells it captured. The file, packages and interface
 * bodies keep their runtime {@code Namespace}, as in phase 1, and so does any function or
 * class body that declares a package : names found there, or nowhere, are looked up in
 * the namespace held in a JVM local of the method being generated. The one invariant of
 * the generator is that <b>every expression leaves exactly one Object on the operand
 * stack</b>.
 * <ul>
 * <li>{@code static Object run(ScriptRuntime rt, Namespace ns)} runs the file. It is split
 * into {@code run$0}, {@code run$1}, ... when the file is large.</li>
 * <li>Each function literal becomes a static method taking its arguments spread, its
 * direct entry point, with an adapter taking them as an array, which LambdaMetafactory
 * turns into a {@code FunctionBody}.</li>
 * <li>Calls and member selections are invokedynamic sites, linked by
 * {@code runtime.Bootstrap} with an inline cache (phase 3).</li>
 * <li>The static descriptions of call sites, operator sites and parameter tables are
 * static fields, built once in {@code <clinit>}.</li>
 * </ul>
 * Package and object bodies are compiled inline, so a {@code return} inside one is a
 * plain {@code ARETURN} from the enclosing function.
 */
final class CodeGenerator extends BabbleBaseVisitor<Void> {
    private static final String OBJECT = "java/lang/Object";
    private static final String STRING = "java/lang/String";
    private static final String NAMESPACE = "org/babblelang/runtime/Namespace";
    private static final String RUNTIME = "org/babblelang/runtime/";
    private static final String SCRIPT_RUNTIME = RUNTIME + "ScriptRuntime";
    private static final String CALL_SITE = RUNTIME + "CallSite";
    private static final String OP_SITE = RUNTIME + "OpSite";
    private static final String CALLABLE = RUNTIME + "Callable";
    private static final String COMPILED_FUNCTION = RUNTIME + "CompiledFunction";
    private static final String FUNCTION_BODY = RUNTIME + "FunctionBody";
    private static final String ARGUMENTS = RUNTIME + "Arguments";
    private static final String BINDER = RUNTIME + "Arguments$Binder";
    private static final String CLASS_SHAPE = RUNTIME + "ClassShape";
    private static final String CLASS_BODY = RUNTIME + "ClassBody";
    private static final String BABBLE_CLASS = RUNTIME + "BabbleClass";
    private static final String BABBLE_INSTANCE = RUNTIME + "BabbleInstance";
    private static final String BABBLE_INTERFACE = RUNTIME + "BabbleInterface";
    private static final String CLASSES = RUNTIME + "Classes";
    private static final String CELL = RUNTIME + "Cell";
    private static final String INT_CELL = RUNTIME + "IntCell";
    private static final String DOUBLE_CELL = RUNTIME + "DoubleCell";
    private static final String BOOL_CELL = RUNTIME + "BoolCell";
    private static final String COMPILED_METHOD = RUNTIME + "CompiledMethod";
    private static final String METHOD_BODY = RUNTIME + "MethodBody";
    private static final String INSTANCE_FACTORY = RUNTIME + "InstanceFactory";
    private static final String FRAMES = RUNTIME + "Frames";
    private static final String TYPES = RUNTIME + "Types";
    private static final String TYPES_TYPE = RUNTIME + "Types$Type";
    private static final String BOOTSTRAP = RUNTIME + "Bootstrap";
    private static final String JAVA_TYPES = RUNTIME + "JavaTypes";
    private static final String VALUES = RUNTIME + "Values";
    private static final String JAVA_OBJECT = "org/babblelang/engine/impl/natives/java/JavaObject";

    static final String RUN_DESCRIPTOR = "(L" + SCRIPT_RUNTIME + ";L" + NAMESPACE + ";)Ljava/lang/Object;";
    private static final String BODY_DESCRIPTOR = "(L" + COMPILED_FUNCTION + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
    private static final String CLASS_BODY_DESCRIPTOR = "(L" + BABBLE_CLASS + ";L" + BABBLE_INSTANCE + ";L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)V";
    /** A method of a compiled class : an instance method, the receiver in local 0. */
    private static final String METHOD_DESCRIPTOR = "(L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
    /** Calls and member selections : invokedynamic sites with an inline cache (phase 3). */
    private static final Handle CALL_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "call",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle TYPED_CALL_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "typedCall",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle SELECT_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "select",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    // Function body locals : the FunctionBody parameters, then the call's namespace. A
    // class body has the same layout, with the class value and the instance first, and a
    // method of a compiled class too, with the receiver and the CompiledMethod first.
    private static final int SELF = 0;
    private static final int INSTANCE = 1;
    private static final int FN_RT = 2;
    private static final int ARGS = 3;
    private static final int NAMES = 4;
    private static final int FN_NS = 6;
    // The direct entry point of a function or method (phase 3) takes its arguments spread :
    // the binder of the call when it came through the array entry point, else null, then
    // one parameter per Babble parameter, then the call's namespace. Calls with more
    // arguments than this, and functions with more parameters, keep the array convention.
    private static final int BINDER_LOCAL = 3;
    private static final int FIRST_PARAMETER = 4;
    private static final int MAX_SPREAD = 200;

    // The top-level statements of a "run" method, counted in parse tree nodes outside of
    // function literals, are kept below this, which keeps the method well under 64 KB.
    private static final int MAX_NODES_PER_RUN_METHOD = 2000;
    // Constant pool strings are limited to 65535 bytes of modified UTF-8, at most three
    // per character ; longer strings are rebuilt from pieces.
    private static final int MAX_STRING_CONSTANT = 20000;
    // Estimated bytecode size of the constants built by one <clinit> helper.
    private static final int MAX_CONSTANT_BYTES_PER_HELPER = 30000;

    private final String className;
    private final String sourceName;
    private final ScopeAnalysis scopes;
    private final Resolution resolution;
    private final ClassWriter classWriter;
    private final ClassLoader runtimeLoader;
    // The JVM interfaces and classes generated for interface, class and object expressions.
    private final Map<BabbleParser.InterfaceExpressionContext, InterfaceGen> interfaceGens = new java.util.LinkedHashMap<>();
    private final Map<String, InterfaceGen> interfaceGensById = new java.util.LinkedHashMap<>();
    private final Map<ParserRuleContext, ClassGen> classGens = new java.util.LinkedHashMap<>();
    private final Map<ParserRuleContext, String> preassignedClassNames = new HashMap<>();
    private final java.util.Set<String> generatedClasses = new java.util.HashSet<>();
    private final Map<String, String> ownInterfaceJvmNames = new java.util.LinkedHashMap<>();
    private final Map<String, String> allInterfaceJvmNames = new HashMap<>();
    private final Map<String, String> ownClassJvmNames = new java.util.LinkedHashMap<>();
    private final Map<String, String> allClassJvmNames = new HashMap<>();
    private final Map<String, List<String>> generatedClassParents = new HashMap<>();
    private final Map<String, List<String>> generatedInterfaceParents = new HashMap<>();
    private int interfaceCount = 0;
    private int classCount = 0;
    private int superHelperCount = 0;
    private BabbleParser.FileContext currentFile;

    private final List<Constant> constants = new ArrayList<>();
    private final Map<List<String>, String> stringArrays = new HashMap<>();
    private final Map<String, String> typeFields = new HashMap<>();
    // Methods to generate once the current one is finished : function and class bodies.
    private final Deque<Runnable> pendingMethods = new ArrayDeque<>();
    private final Map<BabbleParser.FunctionLiteralContext, String> functionMethodNames = new HashMap<>();
    private final java.util.Set<BabbleParser.FunctionLiteralContext> scheduledFunctions = new java.util.HashSet<>();
    private final Map<ParserRuleContext, String> classBodyMethods = new HashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, String> compiledMethodFields = new HashMap<>();
    private int functionCount = 0;

    // State of the method being generated.
    private MethodVisitor mv;
    private int rtLocal;
    private int nsLocal;
    private int nextLocal;
    private int currentLine;
    private List<Runnable> deferred;
    // The function literal, class or object expression, or file whose method this is.
    private ParserRuleContext methodOwner;
    // The JVM local of each frame variable in scope.
    private Map<Resolution.Variable, Integer> slots;
    // Definite-initialization boolean slots for primitive local variables that may be read undefined.
    private Map<Resolution.Variable, Integer> definedSlots;
    // Representation of the value currently at the top of the operand stack.
    private ScopeAnalysis.PrimitiveKind stackKind;
    // Return representation of the method currently being compiled.
    private ScopeAnalysis.PrimitiveKind methodReturnKind;
    // The captured cells of the method, and the local holding their array.
    private List<Resolution.Variable> capturedVariables;
    private int capturedLocal;
    // The class or interface value a static block defines members of.
    private int staticTarget = -1;
    // Where the function value (or CompiledMethod) and the receiver are.
    private int selfLocal;
    private int thizLocal;
    // The compiled class whose method, or constructor, this is ; and the local holding
    // the instance under construction, typed as that class.
    private ClassGen methodClass;
    private ClassGen constructorClass;
    private int typedThisLocal;
    // The local of the call's namespace in a function, method or class body ; and the
    // local of its first parameter in a direct entry point, or -1 when the arguments come
    // as an array.
    private int frameNs;
    private int parameterBase;

    private record AncestorMethod(String memberName, String descriptor, ScopeAnalysis.FunctionSignature signature) {
    }

    private record InheritedDefault(String directParentId, String providerId, ScopeAnalysis.MemberSpec providerSpec) {
    }

    static String bridgeMethodName(String member) {
        return "bm$" + member;
    }

    private static String bridgeDescriptor(ScopeAnalysis.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder("(L").append(SCRIPT_RUNTIME).append(';');
        for (ScopeAnalysis.PrimitiveKind pk : sig.parameterKinds()) {
            sb.append(jvmDescriptor(pk));
        }
        sb.append(')').append(jvmDescriptor(sig.returnKind()));
        return sb.toString();
    }

    private static int[] bridgeParamSlots(ScopeAnalysis.FunctionSignature sig) {
        ScopeAnalysis.PrimitiveKind[] kinds = sig.parameterKinds();
        int[] slots = new int[kinds.length];
        int next = 2;
        for (int i = 0; i < kinds.length; i++) {
            slots[i] = next;
            next += kinds[i].slots();
        }
        return slots;
    }

    private static void loadKindOn(MethodVisitor m, ScopeAnalysis.PrimitiveKind kind, int slot) {
        int op = switch (kind) {
            case INT, BOOL -> ILOAD;
            case DOUBLE -> DLOAD;
            case NONE -> ALOAD;
        };
        m.visitVarInsn(op, slot);
    }

    private static void emitAdaptKind(MethodVisitor m, ScopeAnalysis.PrimitiveKind from, ScopeAnalysis.PrimitiveKind to) {
        if (from == to) {
            return;
        }
        if (to == ScopeAnalysis.PrimitiveKind.NONE) {
            boxOn(m, from);
            return;
        }
        if (from == ScopeAnalysis.PrimitiveKind.INT && to == ScopeAnalysis.PrimitiveKind.DOUBLE) {
            m.visitInsn(I2D);
            return;
        }
        if (from == ScopeAnalysis.PrimitiveKind.NONE) {
            switch (to) {
                case INT -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                }
                case DOUBLE -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                }
                case BOOL -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                }
                case NONE -> {
                }
            }
            return;
        }
        boxOn(m, from);
        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, to);
    }

    private static void packBridgeArgsArray(MethodVisitor m, ScopeAnalysis.FunctionSignature sig, int[] paramSlots) {
        ScopeAnalysis.PrimitiveKind[] kinds = sig.parameterKinds();
        if (kinds.length == 0) {
            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        pushInt(m, kinds.length);
        m.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < kinds.length; i++) {
            m.visitInsn(DUP);
            pushInt(m, i);
            loadKindOn(m, kinds[i], paramSlots[i]);
            boxOn(m, kinds[i]);
            m.visitInsn(AASTORE);
        }
    }

    private String syntheticCallSiteField(String calleeText, int arity) {
        String[] argTexts = new String[arity];
        Arrays.fill(argTexts, "");
        return addConstant("CS", "L" + CALL_SITE + ";", 24 + estimateSize(argTexts), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, sourceName);
            pushInt(m, 0);
            pushString(m, calleeText);
            pushStringArray(m, argTexts);
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    private String javaCallSiteField(String calleeText, int arity) {
        String[] argTexts = new String[arity];
        for (int i = 0; i < arity; i++) {
            argTexts[i] = "<argument " + i + ">";
        }
        return addConstant("CS", "L" + CALL_SITE + ";", 24 + estimateSize(argTexts), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, "<java>");
            pushInt(m, -1);
            pushString(m, calleeText);
            pushStringArray(m, argTexts);
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    private static void emitPushClassConstant(MethodVisitor m, Class<?> cls) {
        if (cls.isPrimitive()) {
            String wrapper = Type.getInternalName(JavaTypes.box(cls));
            m.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
        } else {
            m.visitLdcInsn(Type.getType(cls));
        }
    }

    private static void emitAdaptBoxedAndWiden(MethodVisitor m, ScopeAnalysis.PrimitiveKind targetKind, boolean widensToDouble) {
        if (targetKind == ScopeAnalysis.PrimitiveKind.NONE && widensToDouble) {
            m.visitMethodInsn(INVOKESTATIC, VALUES, "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, targetKind);
        }
    }

    private static void emitLoadJavaBridgeArg(MethodVisitor m, Class<?> pType, int slot,
                                              ScopeAnalysis.PrimitiveKind targetKind, boolean widensToDouble) {
        if (pType == int.class) {
            m.visitVarInsn(ILOAD, slot);
            if (targetKind == ScopeAnalysis.PrimitiveKind.DOUBLE) {
                m.visitInsn(I2D);
            } else if (targetKind == ScopeAnalysis.PrimitiveKind.NONE && widensToDouble) {
                m.visitInsn(I2D);
                boxOn(m, ScopeAnalysis.PrimitiveKind.DOUBLE);
            } else {
                emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.INT, targetKind);
            }
            return;
        }
        if (pType == double.class) {
            m.visitVarInsn(DLOAD, slot);
            emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.DOUBLE, targetKind);
            return;
        }
        if (pType == boolean.class) {
            m.visitVarInsn(ILOAD, slot);
            emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.BOOL, targetKind);
            return;
        }
        if (pType == byte.class) {
            m.visitVarInsn(ILOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        if (pType == short.class) {
            m.visitVarInsn(ILOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        if (pType == char.class) {
            m.visitVarInsn(ILOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        if (pType == long.class) {
            m.visitVarInsn(LLOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        if (pType == float.class) {
            m.visitVarInsn(FLOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        if (pType == Integer.class || pType == Double.class || pType == Boolean.class) {
            m.visitLdcInsn(Type.getType(pType));
            m.visitVarInsn(ALOAD, slot);
            m.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkBoxed", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
            emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
            return;
        }
        m.visitVarInsn(ALOAD, slot);
        if (pType != String.class) {
            m.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        emitAdaptBoxedAndWiden(m, targetKind, widensToDouble);
    }

    private static void emitPackJavaBridgeArgs(MethodVisitor m, Class<?>[] paramTypes, int[] javaParamSlots) {
        if (paramTypes.length == 0) {
            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        pushInt(m, paramTypes.length);
        m.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < paramTypes.length; i++) {
            m.visitInsn(DUP);
            pushInt(m, i);
            emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ScopeAnalysis.PrimitiveKind.NONE, false);
            m.visitInsn(AASTORE);
        }
    }

    private static void emitReturnToJava(MethodVisitor m, ScopeAnalysis.PrimitiveKind fromKind, Class<?> retType,
                                         String description, int rtSlot) {
        if (retType == void.class) {
            m.visitInsn(fromKind == ScopeAnalysis.PrimitiveKind.DOUBLE ? POP2 : POP);
            m.visitInsn(RETURN);
            return;
        }
        if (retType == int.class && fromKind == ScopeAnalysis.PrimitiveKind.INT) {
            m.visitInsn(IRETURN);
            return;
        }
        if (retType == double.class && (fromKind == ScopeAnalysis.PrimitiveKind.DOUBLE || fromKind == ScopeAnalysis.PrimitiveKind.INT)) {
            if (fromKind == ScopeAnalysis.PrimitiveKind.INT) {
                m.visitInsn(I2D);
            }
            m.visitInsn(DRETURN);
            return;
        }
        if (retType == boolean.class && fromKind == ScopeAnalysis.PrimitiveKind.BOOL) {
            m.visitInsn(IRETURN);
            return;
        }
        boxOn(m, fromKind);
        emitPushClassConstant(m, retType);
        pushString(m, description);
        m.visitVarInsn(ALOAD, rtSlot);
        m.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "convertResult",
                "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        if (retType == int.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            m.visitInsn(IRETURN);
        } else if (retType == double.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Double");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
            m.visitInsn(DRETURN);
        } else if (retType == boolean.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            m.visitInsn(IRETURN);
        } else if (retType == long.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Long");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
            m.visitInsn(LRETURN);
        } else if (retType == float.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Float");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
            m.visitInsn(FRETURN);
        } else if (retType == short.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Short");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
            m.visitInsn(IRETURN);
        } else if (retType == byte.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Byte");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
            m.visitInsn(IRETURN);
        } else if (retType == char.class) {
            m.visitTypeInsn(CHECKCAST, "java/lang/Character");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
            m.visitInsn(IRETURN);
        } else {
            if (retType != Object.class) {
                m.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
            }
            m.visitInsn(ARETURN);
        }
    }

    private static boolean conflictsWithBabbleInstanceMethod(Class<?> jc) {
        for (java.lang.reflect.Method jm : jc.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(jm.getModifiers()) || JavaTypes.isObjectMethod(jm)) {
                continue;
            }
            try {
                org.babblelang.runtime.BabbleInstance.class.getDeclaredMethod(jm.getName(), jm.getParameterTypes());
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static void collectImplementableJavaInterfaces(Class<?> iface, java.util.Set<Class<?>> out) {
        if (iface == null || !iface.isInterface() || !JavaTypes.isAccessible(iface)
                || conflictsWithBabbleInstanceMethod(iface) || !out.add(iface)) {
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            collectImplementableJavaInterfaces(parent, out);
        }
    }

    private InheritedDefault findInheritedDefault(ScopeAnalysis.TypeHierarchyInfo ownerHierarchy, String memberName) {
        if (ownerHierarchy == null) {
            return null;
        }
        for (ScopeAnalysis.ParentRef parent : ownerHierarchy.directParents()) {
            if (parent.babbleTypeId() != null) {
                InheritedDefault found = findDefaultInInterface(parent.babbleTypeId(), memberName, new java.util.HashSet<>());
                if (found != null) {
                    return new InheritedDefault(parent.babbleTypeId(), found.providerId(), found.providerSpec());
                }
            }
        }
        return null;
    }

    private InheritedDefault findDefaultInInterface(String ifaceId, String memberName, java.util.Set<String> visited) {
        if (ifaceId == null || !visited.add(ifaceId)) {
            return null;
        }
        ScopeAnalysis.TypeHierarchyInfo info = scopes.typeHierarchy(ifaceId);
        if (info == null) {
            return null;
        }
        ScopeAnalysis.MemberSpec own = info.interfaceMembers().get(memberName);
        if (own != null) {
            return own.hasDefault() ? new InheritedDefault(ifaceId, ifaceId, own) : null;
        }
        for (ScopeAnalysis.ParentRef parent : info.directParents()) {
            if (parent.babbleTypeId() != null) {
                InheritedDefault found = findDefaultInInterface(parent.babbleTypeId(), memberName, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean interfaceHasMethod(String ifaceId, String memberName, String descriptor) {
        if (ifaceId == null || !allInterfaceJvmNames.containsKey(ifaceId)) {
            return false;
        }
        ScopeAnalysis.TypeHierarchyInfo info = scopes.typeHierarchy(ifaceId);
        if (info == null) {
            return false;
        }
        ScopeAnalysis.MemberSpec own = info.interfaceMembers().get(memberName);
        if (own != null && own.callable() && own.declaredSignature() != null
                && bridgeDescriptor(own.declaredSignature()).equals(descriptor)) {
            return true;
        }
        for (String ancId : info.allBabbleInterfaceIds()) {
            if (!allInterfaceJvmNames.containsKey(ancId)) {
                continue;
            }
            ScopeAnalysis.TypeHierarchyInfo ancInfo = scopes.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            ScopeAnalysis.MemberSpec ancSpec = ancInfo.interfaceMembers().get(memberName);
            if (ancSpec != null && ancSpec.callable() && ancSpec.declaredSignature() != null
                    && bridgeDescriptor(ancSpec.declaredSignature()).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private int countDirectParentsWithMethod(ScopeAnalysis.TypeHierarchyInfo hierarchy, String memberName, String descriptor) {
        if (hierarchy == null) {
            return 0;
        }
        int count = 0;
        for (ScopeAnalysis.ParentRef parent : hierarchy.directParents()) {
            if (parent.babbleTypeId() != null && interfaceHasMethod(parent.babbleTypeId(), memberName, descriptor)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, AncestorMethod> collectAncestorMethods(ScopeAnalysis.TypeHierarchyInfo hierarchy) {
        Map<String, AncestorMethod> result = new java.util.LinkedHashMap<>();
        if (hierarchy == null) {
            return result;
        }
        for (String ancId : hierarchy.allBabbleInterfaceIds()) {
            if (!allInterfaceJvmNames.containsKey(ancId)) {
                continue;
            }
            ScopeAnalysis.TypeHierarchyInfo ancInfo = scopes.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            for (ScopeAnalysis.MemberSpec ancSpec : ancInfo.interfaceMembers().values()) {
                if (ancSpec.callable() && ancSpec.declaredSignature() != null) {
                    String desc = bridgeDescriptor(ancSpec.declaredSignature());
                    result.putIfAbsent(ancSpec.name() + "\0" + desc,
                            new AncestorMethod(ancSpec.name(), desc, ancSpec.declaredSignature()));
                }
            }
        }
        return result;
    }

    /**
     * The JVM interface generated for a Babble {@code interface} expression (RM003-P2).
     */
    private final class InterfaceGen {
        final BabbleParser.InterfaceExpressionContext ctx;
        final String name;
        final ClassShape shape;
        final ClassWriter writer;
        final Map<String, String> superHelpers = new HashMap<>();

        InterfaceGen(BabbleParser.InterfaceExpressionContext ctx, String name, ClassShape shape, String[] extendedInterfaces) {
            this.ctx = ctx;
            this.name = name;
            this.shape = shape;
            this.writer = new ScriptClassWriter(runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
            this.writer.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC,
                    name, null, OBJECT, extendedInterfaces.length == 0 ? null : extendedInterfaces);
            this.writer.visitSource(sourceName, null);
        }

        BabbleParser.FunctionLiteralContext defaultLiteral(String member) {
            for (BabbleParser.ExpressionContext expr : ctx.interfaceBlock.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(member)
                        && def.value instanceof BabbleParser.FunctionLiteralContext lit) {
                    return lit;
                }
            }
            return null;
        }

        String superHelper(String directParentJvm, String memberName, ScopeAnalysis.FunctionSignature sig) {
            String desc = bridgeDescriptor(sig);
            String key = directParentJvm + "." + memberName + desc;
            return superHelpers.computeIfAbsent(key, k -> {
                String helperName = "$super$" + (superHelperCount++);
                MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, helperName, desc, null, null);
                m.visitCode();
                int[] paramSlots = bridgeParamSlots(sig);
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                for (int i = 0; i < sig.parameterKinds().length; i++) {
                    loadKindOn(m, sig.parameterKinds()[i], paramSlots[i]);
                }
                m.visitMethodInsn(INVOKESPECIAL, directParentJvm, bridgeMethodName(memberName), desc, true);
                m.visitInsn(returnInsn(sig.returnKind()));
                m.visitMaxs(0, 0);
                m.visitEnd();
                return helperName;
            });
        }

        void emitBody() {
            ScopeAnalysis.TypeHierarchyInfo hierarchy = scopes.typeHierarchy(shape.id());
            if (hierarchy == null) {
                return;
            }
            java.util.Set<String> emitted = new java.util.HashSet<>();
            for (ScopeAnalysis.MemberSpec spec : hierarchy.interfaceMembers().values()) {
                if (!spec.callable() || spec.declaredSignature() == null) {
                    continue;
                }
                ScopeAnalysis.FunctionSignature sig = spec.declaredSignature();
                String mName = bridgeMethodName(spec.name());
                String desc = bridgeDescriptor(sig);
                emitted.add(mName + desc);
                if (!spec.hasDefault()) {
                    writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, mName, desc, null, null).visitEnd();
                } else {
                    MethodVisitor m = writer.visitMethod(ACC_PUBLIC, mName, desc, null, null);
                    m.visitCode();
                    emitOwnDefaultInvoke(m, shape.memberIndex(spec.name()), spec.name(), defaultLiteral(spec.name()), sig);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            }

            Map<String, AncestorMethod> ancestors = collectAncestorMethods(hierarchy);
            for (AncestorMethod anc : ancestors.values()) {
                String mName = bridgeMethodName(anc.memberName());
                if (emitted.contains(mName + anc.descriptor())) {
                    continue;
                }
                ScopeAnalysis.MemberSpec ownSpec = hierarchy.interfaceMembers().get(anc.memberName());
                if (ownSpec != null) {
                    emitted.add(mName + anc.descriptor());
                    if (!ownSpec.hasDefault()) {
                        writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC, mName, anc.descriptor(), null, null).visitEnd();
                    } else {
                        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, mName, anc.descriptor(), null, null);
                        m.visitCode();
                        ScopeAnalysis.FunctionSignature ownSig = ownSpec.callable() ? ownSpec.declaredSignature() : null;
                        if (ownSig != null && ownSig.parameterKinds().length == anc.signature().parameterKinds().length) {
                            int[] paramSlots = bridgeParamSlots(anc.signature());
                            m.visitVarInsn(ALOAD, 0);
                            m.visitVarInsn(ALOAD, 1);
                            for (int i = 0; i < anc.signature().parameterKinds().length; i++) {
                                ScopeAnalysis.PrimitiveKind fromK = anc.signature().parameterKinds()[i];
                                ScopeAnalysis.PrimitiveKind toK = ownSig.parameterKinds()[i];
                                loadKindOn(m, fromK, paramSlots[i]);
                                emitAdaptKind(m, fromK, toK);
                            }
                            m.visitMethodInsn(INVOKEINTERFACE, name, mName, bridgeDescriptor(ownSig), true);
                            emitAdaptKind(m, ownSig.returnKind(), anc.signature().returnKind());
                            m.visitInsn(returnInsn(anc.signature().returnKind()));
                        } else {
                            emitOwnDefaultInvoke(m, shape.memberIndex(anc.memberName()), anc.memberName(),
                                    defaultLiteral(anc.memberName()), anc.signature());
                        }
                        m.visitMaxs(0, 0);
                        m.visitEnd();
                    }
                } else {
                    InheritedDefault def = findInheritedDefault(hierarchy, anc.memberName());
                    if (def == null) {
                        continue;
                    }
                    String directParentJvm = allInterfaceJvmNames.get(def.directParentId());
                    if (directParentJvm == null) {
                        continue;
                    }
                    ScopeAnalysis.FunctionSignature provSig = def.providerSpec().callable() ? def.providerSpec().declaredSignature() : null;
                    String provDesc = provSig != null ? bridgeDescriptor(provSig) : null;
                    int parentsWithSig = countDirectParentsWithMethod(hierarchy, anc.memberName(), anc.descriptor());
                    if (parentsWithSig >= 2 || !anc.descriptor().equals(provDesc)) {
                        emitted.add(mName + anc.descriptor());
                        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, mName, anc.descriptor(), null, null);
                        m.visitCode();
                        emitInheritedDefaultInvoke(m, def, directParentJvm, anc.memberName(), anc.descriptor(), anc.signature());
                        m.visitMaxs(0, 0);
                        m.visitEnd();
                    }
                }
            }
        }

        private void emitOwnDefaultInvoke(MethodVisitor m, int memberIndex, String memberName,
                                          BabbleParser.FunctionLiteralContext lit, ScopeAnalysis.FunctionSignature sig) {
            int[] paramSlots = bridgeParamSlots(sig);
            int ifaceCount = sig.parameterKinds().length;
            String fnMethod = lit != null ? functionMethodNames.get(lit) : null;
            int litCount = lit != null ? scopes.parameters(lit).length : -1;

            m.visitVarInsn(ALOAD, 0);
            m.visitLdcInsn(shape.id());
            pushInt(m, memberIndex);
            m.visitMethodInsn(INVOKESTATIC, CLASSES, "interfaceDefault",
                    "(Ljava/lang/Object;Ljava/lang/String;I)L" + RUNTIME + "BabbleFunction;", false);

            if (fnMethod != null && hasSpec(lit) && ifaceCount == litCount) {
                List<BabbleParser.ParameterDeclarationContext> decls = lit.parametersDeclaration().parameterDeclaration();
                m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                for (int i = 0; i < ifaceCount; i++) {
                    ScopeAnalysis.PrimitiveKind fromK = sig.parameterKinds()[i];
                    ScopeAnalysis.PrimitiveKind toK = scopes.declarationKind(decls.get(i));
                    loadKindOn(m, fromK, paramSlots[i]);
                    emitAdaptKind(m, fromK, toK);
                    if (toK == ScopeAnalysis.PrimitiveKind.NONE && scopes.widensToDouble(decls.get(i))) {
                        m.visitMethodInsn(INVOKESTATIC, VALUES, "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    }
                }
                m.visitMethodInsn(INVOKESTATIC, className, fnMethod + "$spec", functionSpecDescriptor(lit), false);
                emitAdaptKind(m, scopes.functionReturnKind(lit), sig.returnKind());
                m.visitInsn(returnInsn(sig.returnKind()));
                return;
            }
            if (fnMethod != null && isSpread(lit) && ifaceCount <= litCount) {
                m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                m.visitInsn(ACONST_NULL);
                for (int i = 0; i < ifaceCount; i++) {
                    ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[i];
                    loadKindOn(m, pk, paramSlots[i]);
                    boxOn(m, pk);
                }
                for (int i = ifaceCount; i < litCount; i++) {
                    m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
                m.visitMethodInsn(INVOKESTATIC, className, fnMethod, functionDirectDescriptor(lit), false);
                emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                m.visitInsn(returnInsn(sig.returnKind()));
                return;
            }
            String siteField = syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
            m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            packBridgeArgsArray(m, sig, paramSlots);
            m.visitInsn(ACONST_NULL);
            m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(INVOKEVIRTUAL, COMPILED_FUNCTION, "invoke",
                    "(Ljava/lang/Object;L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", false);
            emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
            m.visitInsn(returnInsn(sig.returnKind()));
        }
    }

    private void emitInheritedDefaultInvoke(MethodVisitor m, InheritedDefault def, String directParentJvm,
                                            String memberName, String targetDesc, ScopeAnalysis.FunctionSignature targetSig) {
        int[] paramSlots = bridgeParamSlots(targetSig);
        int argCount = targetSig.parameterKinds().length;
        String mName = bridgeMethodName(memberName);
        if (directParentJvm != null && interfaceHasMethod(def.directParentId(), memberName, targetDesc)) {
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < argCount; i++) {
                loadKindOn(m, targetSig.parameterKinds()[i], paramSlots[i]);
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, mName, targetDesc, true);
            m.visitInsn(returnInsn(targetSig.returnKind()));
            return;
        }
        ScopeAnalysis.FunctionSignature provSig = def != null && def.providerSpec().callable() ? def.providerSpec().declaredSignature() : null;
        String provDesc = provSig != null ? bridgeDescriptor(provSig) : null;
        if (directParentJvm != null && provDesc != null
                && provSig.parameterKinds().length == argCount
                && interfaceHasMethod(def.directParentId(), memberName, provDesc)) {
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < argCount; i++) {
                ScopeAnalysis.PrimitiveKind fromK = targetSig.parameterKinds()[i];
                ScopeAnalysis.PrimitiveKind toK = provSig.parameterKinds()[i];
                loadKindOn(m, fromK, paramSlots[i]);
                emitAdaptKind(m, fromK, toK);
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, mName, provDesc, true);
            emitAdaptKind(m, provSig.returnKind(), targetSig.returnKind());
            m.visitInsn(returnInsn(targetSig.returnKind()));
            return;
        }
        String siteField = syntheticCallSiteField(memberName, argCount);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(memberName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "getMember", "(Ljava/lang/String;Z)Ljava/lang/Object;", false);
        m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
        m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        m.visitVarInsn(ALOAD, 1);
        packBridgeArgsArray(m, targetSig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, targetSig.returnKind());
        m.visitInsn(returnInsn(targetSig.returnKind()));
    }

    /**
     * The JVM class generated for a class or object expression (compiler phase 2) : it
     * extends BabbleInstance with one field per member, plus a hidden field per variable
     * of the constructor that a nested function captures, holding its cell. Its methods
     * are JVM instance methods.
     */
    private final class ClassGen {
        final ParserRuleContext ctx;
        final String name;
        final ClassShape shape;
        final boolean arrayBacked;
        final String[] implementedInterfaces;
        final List<Class<?>> javaInterfaces;
        final ClassWriter writer;
        final List<Resolution.Variable> hiddenCells = new ArrayList<>();
        final ScopeAnalysis.PrimitiveKind[] memberKinds;
        final int[] primDefBits;
        final int primDefCount;
        final Map<String, String> superHelpers = new HashMap<>();

        ClassGen(ParserRuleContext ctx, String name, ClassShape shape, boolean arrayBacked,
                 String[] implementedInterfaces, List<Class<?>> javaInterfaces) {
            this.ctx = ctx;
            this.name = name;
            this.shape = shape;
            this.arrayBacked = arrayBacked;
            this.implementedInterfaces = implementedInterfaces;
            this.javaInterfaces = javaInterfaces;
            this.writer = new ScriptClassWriter(runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
            if (arrayBacked) {
                this.memberKinds = new ScopeAnalysis.PrimitiveKind[0];
                this.primDefBits = new int[0];
                this.primDefCount = 0;
                return;
            }
            for (Resolution.Variable variable : resolution.declared(ctx)) {
                if (variable.isCell()) {
                    hiddenCells.add(variable);
                }
            }
            int count = shape.memberCount();
            this.memberKinds = new ScopeAnalysis.PrimitiveKind[count];
            this.primDefBits = new int[count];
            Arrays.fill(this.memberKinds, ScopeAnalysis.PrimitiveKind.NONE);
            Arrays.fill(this.primDefBits, -1);
            if (ctx instanceof BabbleParser.ClassExpressionContext c && c.parametersDeclaration() != null) {
                for (int p = 0; p < shape.parameters().length; p++) {
                    int idx = shape.parameterMember(p);
                    if (idx >= 0 && !shape.isMethod(idx)) {
                        this.memberKinds[idx] = scopes.declarationKind(c.parametersDeclaration().parameterDeclaration(p));
                    }
                }
            }
            BabbleParser.BlockContext body = ctx instanceof BabbleParser.ClassExpressionContext c
                    ? c.classBlock : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            int bits = 0;
            for (BabbleParser.ExpressionContext expr : body.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def) {
                    ScopeAnalysis.DefTarget dt = scopes.defTarget(def);
                    if (dt.kind() == ScopeAnalysis.DefKind.MEMBER) {
                        int idx = dt.index();
                        if (!shape.isMethod(idx)) {
                            ScopeAnalysis.PrimitiveKind pk = scopes.declarationKind(def);
                            this.memberKinds[idx] = pk;
                            if (pk.isPrimitive()) {
                                this.primDefBits[idx] = bits++;
                            }
                        }
                    }
                }
            }
            this.primDefCount = bits;
        }

        String methodName(String member) {
            return "m$" + member;
        }

        /** The literal of a method member of the class. */
        BabbleParser.FunctionLiteralContext methodLiteral(String member) {
            BabbleParser.BlockContext body = ctx instanceof BabbleParser.ClassExpressionContext c
                    ? c.classBlock : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            for (BabbleParser.ExpressionContext expression : body.expression()) {
                if (expression instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(member)
                        && def.value instanceof BabbleParser.FunctionLiteralContext literal) {
                    return literal;
                }
            }
            throw new IllegalStateException("No method " + member + " in " + name);
        }

        String superHelper(String directParentJvm, String memberName, ScopeAnalysis.FunctionSignature sig) {
            String desc = bridgeDescriptor(sig);
            String key = directParentJvm + "." + memberName + desc;
            return superHelpers.computeIfAbsent(key, k -> {
                String helperName = "$super$" + (superHelperCount++);
                MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, helperName, desc, null, null);
                m.visitCode();
                int[] paramSlots = bridgeParamSlots(sig);
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, 1);
                for (int i = 0; i < sig.parameterKinds().length; i++) {
                    loadKindOn(m, sig.parameterKinds()[i], paramSlots[i]);
                }
                m.visitMethodInsn(INVOKESPECIAL, directParentJvm, bridgeMethodName(memberName), desc, true);
                m.visitInsn(returnInsn(sig.returnKind()));
                m.visitMaxs(0, 0);
                m.visitEnd();
                return helperName;
            });
        }

        /** Fields, the constructor, and the member table ; the methods are generated as they come. */
        void begin() {
            writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, name, null, BABBLE_INSTANCE,
                    implementedInterfaces.length == 0 ? null : implementedInterfaces);
            writer.visitSource(sourceName, null);
            if (!javaInterfaces.isEmpty()) {
                MethodVisitor lookupMv = writer.visitMethod(ACC_PROTECTED | ACC_SYNTHETIC, "javaDefaultLookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
                lookupMv.visitCode();
                lookupMv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
                lookupMv.visitInsn(ARETURN);
                lookupMv.visitMaxs(0, 0);
                lookupMv.visitEnd();
            }
            if (arrayBacked) {
                MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V", null, null);
                init.visitCode();
                init.visitVarInsn(ALOAD, 0);
                init.visitVarInsn(ALOAD, 1);
                init.visitVarInsn(ALOAD, 2);
                init.visitInsn(ICONST_1);
                init.visitMethodInsn(INVOKESPECIAL, BABBLE_INSTANCE, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";Z)V", false);
                init.visitInsn(RETURN);
                init.visitMaxs(0, 0);
                init.visitEnd();
                return;
            }
            for (int i = 0; i < shape.memberCount(); i++) {
                writer.visitField(0, shape.member(i), jvmDescriptor(memberKinds[i]), null, null).visitEnd();
            }
            for (Resolution.Variable variable : hiddenCells) {
                writer.visitField(0, hiddenField(variable), "Ljava/lang/Object;", null, null).visitEnd();
            }
            int definedWords = (primDefCount + 31) / 32;
            for (int w = 0; w < definedWords; w++) {
                writer.visitField(0, "$defined$" + w, "I", null, null).visitEnd();
            }

            MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitVarInsn(ALOAD, 2);
            init.visitInsn(ICONST_0);
            init.visitMethodInsn(INVOKESPECIAL, BABBLE_INSTANCE, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";Z)V", false);
            for (int i = 0; i < shape.memberCount(); i++) {
                if (!memberKinds[i].isPrimitive()) {
                    init.visitVarInsn(ALOAD, 0);
                    init.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
                    init.visitFieldInsn(PUTFIELD, name, shape.member(i), "Ljava/lang/Object;");
                }
            }
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();

            memberTable("field", "(I)Ljava/lang/Object;", false);
            memberTable("setField", "(ILjava/lang/Object;)V", true);
            if (primDefCount > 0) {
                isMemberDefinedMethod();
            }
        }

        void emitInterfaceBridges() {
            ScopeAnalysis.TypeHierarchyInfo hierarchy = scopes.typeHierarchy(shape.id());
            if (hierarchy == null) {
                return;
            }
            Map<String, AncestorMethod> ancestors = collectAncestorMethods(hierarchy);
            for (AncestorMethod anc : ancestors.values()) {
                String memberName = anc.memberName();
                ScopeAnalysis.FunctionSignature sig = anc.signature();
                int[] paramSlots = bridgeParamSlots(sig);
                int ifaceCount = sig.parameterKinds().length;
                MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, bridgeMethodName(memberName), anc.descriptor(), null, null);
                m.visitCode();
                int ownIdx = shape.memberIndex(memberName);
                if (ownIdx >= 0 && !arrayBacked && shape.isMethod(ownIdx)) {
                    BabbleParser.FunctionLiteralContext lit = methodLiteral(memberName);
                    int litCount = scopes.parameters(lit).length;
                    m.visitVarInsn(ALOAD, 0);
                    m.visitInsn(DUP);
                    m.visitFieldInsn(GETFIELD, name, memberName, "Ljava/lang/Object;");
                    m.visitLdcInsn(memberName);
                    m.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                    m.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
                    m.visitVarInsn(ALOAD, 1);
                    if (hasSpec(lit) && ifaceCount == litCount) {
                        List<BabbleParser.ParameterDeclarationContext> decls = lit.parametersDeclaration().parameterDeclaration();
                        for (int i = 0; i < ifaceCount; i++) {
                            ScopeAnalysis.PrimitiveKind fromK = sig.parameterKinds()[i];
                            ScopeAnalysis.PrimitiveKind toK = scopes.declarationKind(decls.get(i));
                            loadKindOn(m, fromK, paramSlots[i]);
                            emitAdaptKind(m, fromK, toK);
                            if (toK == ScopeAnalysis.PrimitiveKind.NONE && scopes.widensToDouble(decls.get(i))) {
                                m.visitMethodInsn(INVOKESTATIC, VALUES, "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            }
                        }
                        m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName) + "$spec", methodSpecDescriptor(lit), false);
                        emitAdaptKind(m, scopes.functionReturnKind(lit), sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    } else if (isSpread(lit) && ifaceCount <= litCount) {
                        m.visitInsn(ACONST_NULL);
                        for (int i = 0; i < ifaceCount; i++) {
                            ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[i];
                            loadKindOn(m, pk, paramSlots[i]);
                            boxOn(m, pk);
                        }
                        for (int i = ifaceCount; i < litCount; i++) {
                            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                        }
                        m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName), methodDirectDescriptor(lit), false);
                        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    } else {
                        String siteField = syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
                        packBridgeArgsArray(m, sig, paramSlots);
                        m.visitInsn(ACONST_NULL);
                        m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
                        m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName) + (isSpread(lit) ? "$call" : ""), METHOD_DESCRIPTOR, false);
                        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    }
                } else if (ownIdx >= 0) {
                    String siteField = syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
                    m.visitVarInsn(ALOAD, 0);
                    m.visitLdcInsn(memberName);
                    m.visitInsn(ICONST_0);
                    m.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "getMember", "(Ljava/lang/String;Z)Ljava/lang/Object;", false);
                    m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
                    m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
                    m.visitVarInsn(ALOAD, 1);
                    packBridgeArgsArray(m, sig, paramSlots);
                    m.visitInsn(ACONST_NULL);
                    m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
                    m.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                            "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
                    emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                    m.visitInsn(returnInsn(sig.returnKind()));
                } else {
                    InheritedDefault def = findInheritedDefault(hierarchy, memberName);
                    String directParentJvm = def != null ? allInterfaceJvmNames.get(def.directParentId()) : null;
                    emitInheritedDefaultInvoke(m, def, directParentJvm, memberName, anc.descriptor(), sig);
                }
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
            if (!javaInterfaces.isEmpty()) {
                emitJavaInterfaceBridges(hierarchy, ancestors);
            }
        }

        private void emitJavaInterfaceBridges(ScopeAnalysis.TypeHierarchyInfo hierarchy,
                                              Map<String, AncestorMethod> babbleAncestors) {
            java.util.TreeMap<String, List<java.lang.reflect.Method>> grouped = new java.util.TreeMap<>();
            for (Class<?> jc : javaInterfaces) {
                for (java.lang.reflect.Method jm : jc.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(jm.getModifiers()) || JavaTypes.isObjectMethod(jm)) {
                        continue;
                    }
                    String key = jm.getName() + "#" + Type.getMethodDescriptor(jm);
                    grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(jm);
                }
            }
            for (List<java.lang.reflect.Method> candidates : grouped.values()) {
                candidates.sort(java.util.Comparator
                        .comparing(java.lang.reflect.Method::isBridge)
                        .thenComparing(m -> m.getDeclaringClass().getName()));
                java.lang.reflect.Method jm = candidates.get(0);
                String mName = jm.getName();
                int ownIdx = shape.memberIndex(mName);
                boolean ownMethod = ownIdx >= 0 && shape.isMethod(ownIdx);
                InheritedDefault inheritedBabbleDefault = ownIdx < 0 ? findInheritedDefault(hierarchy, mName) : null;
                boolean hasBabbleImpl = ownMethod || inheritedBabbleDefault != null;
                if (!hasBabbleImpl) {
                    boolean hasActiveJavaDefault = false;
                    for (java.lang.reflect.Method cand : candidates) {
                        if (cand.isDefault()) {
                            boolean reabstracted = false;
                            for (java.lang.reflect.Method other : candidates) {
                                if (!other.isDefault()
                                        && other.getDeclaringClass() != cand.getDeclaringClass()
                                        && cand.getDeclaringClass().isAssignableFrom(other.getDeclaringClass())) {
                                    reabstracted = true;
                                    break;
                                }
                            }
                            if (!reabstracted) {
                                hasActiveJavaDefault = true;
                                break;
                            }
                        }
                    }
                    if (hasActiveJavaDefault) {
                        continue;
                    }
                }
                emitJavaMethodBridge(jm, ownMethod, inheritedBabbleDefault, babbleAncestors.get(mName));
            }
        }

        private void emitJavaMethodBridge(java.lang.reflect.Method jm, boolean ownMethod,
                                          InheritedDefault inheritedBabbleDefault, AncestorMethod babbleAnc) {
            String mName = jm.getName();
            String jvmDesc = Type.getMethodDescriptor(jm);
            Class<?>[] paramTypes = jm.getParameterTypes();
            Class<?> retType = jm.getReturnType();
            int access = ACC_PUBLIC | ACC_SYNTHETIC | (jm.isBridge() ? ACC_BRIDGE : 0);
            MethodVisitor m = writer.visitMethod(access, mName, jvmDesc, null, null);
            m.visitCode();

            if (!ownMethod && inheritedBabbleDefault == null) {
                m.visitTypeInsn(NEW, RUNTIME + "BabbleException");
                m.visitInsn(DUP);
                pushString(m, "No implementation of " + JavaTypes.signature(jm));
                m.visitMethodInsn(INVOKESPECIAL, RUNTIME + "BabbleException", "<init>", "(Ljava/lang/String;)V", false);
                m.visitInsn(ATHROW);
                m.visitMaxs(0, 0);
                m.visitEnd();
                return;
            }

            int[] javaParamSlots = new int[paramTypes.length];
            int nextSlot = 1;
            for (int i = 0; i < paramTypes.length; i++) {
                javaParamSlots[i] = nextSlot;
                nextSlot += (paramTypes[i] == double.class || paramTypes[i] == long.class) ? 2 : 1;
            }
            int rtSlot = nextSlot;
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "runtime", "()L" + SCRIPT_RUNTIME + ";", false);
            m.visitVarInsn(ASTORE, rtSlot);
            String descText = shape.name() + "." + mName;

            if (ownMethod && !arrayBacked) {
                BabbleParser.FunctionLiteralContext lit = methodLiteral(mName);
                int litCount = scopes.parameters(lit).length;
                m.visitVarInsn(ALOAD, 0);
                m.visitInsn(DUP);
                m.visitFieldInsn(GETFIELD, name, mName, "Ljava/lang/Object;");
                m.visitLdcInsn(mName);
                m.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                m.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
                m.visitVarInsn(ALOAD, rtSlot);
                if (hasSpec(lit) && paramTypes.length == litCount) {
                    List<BabbleParser.ParameterDeclarationContext> decls = lit.parametersDeclaration().parameterDeclaration();
                    for (int i = 0; i < paramTypes.length; i++) {
                        BabbleParser.ParameterDeclarationContext decl = decls.get(i);
                        ScopeAnalysis.PrimitiveKind toK = scopes.declarationKind(decl);
                        emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], toK, scopes.widensToDouble(decl));
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName) + "$spec", methodSpecDescriptor(lit), false);
                    emitReturnToJava(m, scopes.functionReturnKind(lit), retType, descText, rtSlot);
                } else if (isSpread(lit) && paramTypes.length <= litCount) {
                    m.visitInsn(ACONST_NULL);
                    for (int i = 0; i < paramTypes.length; i++) {
                        emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ScopeAnalysis.PrimitiveKind.NONE, false);
                    }
                    for (int i = paramTypes.length; i < litCount; i++) {
                        m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName), methodDirectDescriptor(lit), false);
                    emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
                } else {
                    String siteField = javaCallSiteField(mName, paramTypes.length);
                    emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
                    m.visitInsn(ACONST_NULL);
                    m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName) + (isSpread(lit) ? "$call" : ""), METHOD_DESCRIPTOR, false);
                    emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
                }
                m.visitMaxs(0, 0);
                m.visitEnd();
                return;
            }

            if (inheritedBabbleDefault != null && babbleAnc != null
                    && babbleAnc.signature().parameterKinds().length == paramTypes.length) {
                ScopeAnalysis.FunctionSignature ancSig = babbleAnc.signature();
                m.visitVarInsn(ALOAD, 0);
                m.visitVarInsn(ALOAD, rtSlot);
                for (int i = 0; i < paramTypes.length; i++) {
                    emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ancSig.parameterKinds()[i], false);
                }
                m.visitMethodInsn(INVOKEVIRTUAL, name, bridgeMethodName(mName), babbleAnc.descriptor(), false);
                emitReturnToJava(m, ancSig.returnKind(), retType, descText, rtSlot);
                m.visitMaxs(0, 0);
                m.visitEnd();
                return;
            }

            String siteField = javaCallSiteField(mName, paramTypes.length);
            m.visitVarInsn(ALOAD, 0);
            m.visitLdcInsn(mName);
            m.visitInsn(ICONST_0);
            m.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "getMember", "(Ljava/lang/String;Z)Ljava/lang/Object;", false);
            m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
            m.visitVarInsn(ALOAD, rtSlot);
            emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
            m.visitInsn(ACONST_NULL);
            m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                    "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
            emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        void emitMarkDefined(MethodVisitor m, int instanceLocal, int memberIndex) {
            int bit = primDefBits[memberIndex];
            if (bit < 0) {
                return;
            }
            String definedField = "$defined$" + (bit / 32);
            int mask = 1 << (bit % 32);
            m.visitVarInsn(ALOAD, instanceLocal);
            m.visitInsn(DUP);
            m.visitFieldInsn(GETFIELD, name, definedField, "I");
            pushInt(m, mask);
            m.visitInsn(IOR);
            m.visitFieldInsn(PUTFIELD, name, definedField, "I");
        }

        void emitCheckDefined(MethodVisitor m, int instanceLocal, int memberIndex, String memberName, ParserRuleContext at) {
            int bit = primDefBits[memberIndex];
            if (bit < 0) {
                return;
            }
            String definedField = "$defined$" + (bit / 32);
            int mask = 1 << (bit % 32);
            Label ok = new Label();
            m.visitVarInsn(ALOAD, instanceLocal);
            m.visitFieldInsn(GETFIELD, name, definedField, "I");
            pushInt(m, mask);
            m.visitInsn(IAND);
            m.visitJumpInsn(IFNE, ok);
            line(at);
            m.visitLdcInsn(memberName);
            m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "usedBeforeDefinition", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
            m.visitInsn(ATHROW);
            m.visitLabel(ok);
        }

        private void isMemberDefinedMethod() {
            MethodVisitor m = writer.visitMethod(ACC_PROTECTED, "isMemberDefined", "(I)Z", null, null);
            m.visitCode();
            Label fallback = new Label();
            if (shape.memberCount() > 0) {
                Label[] cases = new Label[shape.memberCount()];
                for (int i = 0; i < cases.length; i++) {
                    cases[i] = new Label();
                }
                m.visitVarInsn(ILOAD, 1);
                m.visitTableSwitchInsn(0, cases.length - 1, fallback, cases);
                for (int i = 0; i < cases.length; i++) {
                    m.visitLabel(cases[i]);
                    int bit = primDefBits[i];
                    if (bit >= 0) {
                        Label undef = new Label();
                        m.visitVarInsn(ALOAD, 0);
                        m.visitFieldInsn(GETFIELD, name, "$defined$" + (bit / 32), "I");
                        pushInt(m, 1 << (bit % 32));
                        m.visitInsn(IAND);
                        m.visitJumpInsn(IFEQ, undef);
                        m.visitInsn(ICONST_1);
                        m.visitInsn(IRETURN);
                        m.visitLabel(undef);
                        m.visitInsn(ICONST_0);
                        m.visitInsn(IRETURN);
                    } else {
                        m.visitInsn(ICONST_1);
                        m.visitInsn(IRETURN);
                    }
                }
            }
            m.visitLabel(fallback);
            m.visitInsn(ICONST_1);
            m.visitInsn(IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }

        /** {@code field(int)} or {@code setField(int, Object)} : a switch over the member fields. */
        private void memberTable(String method, String descriptor, boolean set) {
            MethodVisitor m = writer.visitMethod(ACC_PROTECTED, method, descriptor, null, null);
            m.visitCode();
            Label fallback = new Label();
            if (shape.memberCount() > 0) {
                Label[] cases = new Label[shape.memberCount()];
                for (int i = 0; i < cases.length; i++) {
                    cases[i] = new Label();
                }
                m.visitVarInsn(ILOAD, 1);
                m.visitTableSwitchInsn(0, cases.length - 1, fallback, cases);
                for (int i = 0; i < cases.length; i++) {
                    m.visitLabel(cases[i]);
                    ScopeAnalysis.PrimitiveKind mk = memberKinds[i];
                    String desc = jvmDescriptor(mk);
                    if (set) {
                        m.visitVarInsn(ALOAD, 0);
                        m.visitVarInsn(ALOAD, 2);
                        switch (mk) {
                            case INT -> {
                                m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                            }
                            case DOUBLE -> {
                                m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                            }
                            case BOOL -> {
                                m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                            }
                            case NONE -> {
                            }
                        }
                        m.visitFieldInsn(PUTFIELD, name, shape.member(i), desc);
                        emitMarkDefined(m, 0, i);
                        m.visitInsn(RETURN);
                    } else {
                        if (primDefBits[i] >= 0) {
                            Label undef = new Label();
                            m.visitVarInsn(ALOAD, 0);
                            m.visitFieldInsn(GETFIELD, name, "$defined$" + (primDefBits[i] / 32), "I");
                            pushInt(m, 1 << (primDefBits[i] % 32));
                            m.visitInsn(IAND);
                            m.visitJumpInsn(IFEQ, undef);
                            m.visitVarInsn(ALOAD, 0);
                            m.visitFieldInsn(GETFIELD, name, shape.member(i), desc);
                            boxOn(m, mk);
                            m.visitInsn(ARETURN);
                            m.visitLabel(undef);
                            m.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
                            m.visitInsn(ARETURN);
                        } else {
                            m.visitVarInsn(ALOAD, 0);
                            m.visitFieldInsn(GETFIELD, name, shape.member(i), desc);
                            boxOn(m, mk);
                            m.visitInsn(ARETURN);
                        }
                    }
                }
            }
            m.visitLabel(fallback);
            m.visitTypeInsn(NEW, "java/lang/IndexOutOfBoundsException");
            m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/IndexOutOfBoundsException", "<init>", "()V", false);
            m.visitInsn(ATHROW);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }

    private static String hiddenField(Resolution.Variable variable) {
        return "$v$" + variable.name();
    }

    private record Constant(String field, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
    }


    CodeGenerator(String className, String sourceName, ScopeAnalysis scopes, ClassLoader runtimeLoader) {
        this(className, sourceName, scopes, runtimeLoader, Map.of(), Map.of());
    }

    CodeGenerator(String className, String sourceName, ScopeAnalysis scopes, ClassLoader runtimeLoader,
                  Map<String, String> knownInterfaceJvmNames) {
        this(className, sourceName, scopes, runtimeLoader, knownInterfaceJvmNames, Map.of());
    }

    CodeGenerator(String className, String sourceName, ScopeAnalysis scopes, ClassLoader runtimeLoader,
                  Map<String, String> knownInterfaceJvmNames, Map<String, String> knownClassJvmNames) {
        this.className = className;
        this.sourceName = sourceName;
        this.scopes = scopes;
        this.resolution = scopes.resolution();
        this.runtimeLoader = runtimeLoader;
        if (knownInterfaceJvmNames != null) {
            this.allInterfaceJvmNames.putAll(knownInterfaceJvmNames);
        }
        if (knownClassJvmNames != null) {
            this.allClassJvmNames.putAll(knownClassJvmNames);
        }
        this.classWriter = new ScriptClassWriter(runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
    }

    Map<String, String> interfaceJvmNames() {
        return java.util.Collections.unmodifiableMap(ownInterfaceJvmNames);
    }

    Map<String, String> classJvmNames() {
        return java.util.Collections.unmodifiableMap(ownClassJvmNames);
    }

    private boolean isStaticTopLevelTypeExpr(ParserRuleContext typeExpr) {
        ParserRuleContext cur = typeExpr;
        while (cur != null) {
            ParserRuleContext parent = cur.getParent();
            if (parent instanceof BabbleParser.FileContext) {
                return true;
            }
            if (parent instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
                cur = def;
            } else if (parent instanceof BabbleParser.BlockContext block
                    && block.getParent() instanceof BabbleParser.PackageExpressionContext pkg
                    && pkg.packageBlock == block) {
                cur = pkg;
            } else if (parent instanceof BabbleParser.BlockContext block
                    && block.getParent() instanceof BabbleParser.BlockExpressionContext blockExpr
                    && blockExpr.block() == block
                    && block.effectDeclaration().isEmpty()
                    && block.expression().size() == 1
                    && !scopes.opensScope(block)) {
                cur = blockExpr;
            } else {
                return false;
            }
        }
        return false;
    }

    private void registerTypes(BabbleParser.FileContext file) {
        Map<String, BabbleParser.InterfaceExpressionContext> localIfaces = new java.util.LinkedHashMap<>();
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
                ClassShape shape = scopes.shape(ctx);
                if (shape != null) {
                    String jvmName = className + "$I" + (interfaceCount++) + "$" + shape.name();
                    ownInterfaceJvmNames.put(shape.id(), jvmName);
                    allInterfaceJvmNames.put(shape.id(), jvmName);
                    localIfaces.put(shape.id(), ctx);
                }
                return super.visitInterfaceExpression(ctx);
            }

            @Override
            public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
                ClassShape shape = scopes.shape(ctx);
                if (shape != null && isStaticTopLevelTypeExpr(ctx)) {
                    String jvmName = className + "$C" + (classCount++) + "$" + shape.name();
                    ownClassJvmNames.put(shape.id(), jvmName);
                    allClassJvmNames.put(shape.id(), jvmName);
                    preassignedClassNames.put(ctx, jvmName);
                }
                return super.visitClassExpression(ctx);
            }
        }.visit(file);

        for (Map.Entry<String, String> entry : ownInterfaceJvmNames.entrySet()) {
            String ifaceId = entry.getKey();
            String jvmName = entry.getValue();
            List<String> extended = new ArrayList<>();
            ScopeAnalysis.TypeHierarchyInfo info = scopes.typeHierarchy(ifaceId);
            if (info != null) {
                for (ScopeAnalysis.ParentRef parent : info.directParents()) {
                    if (parent.babbleTypeId() != null) {
                        String parentJvm = allInterfaceJvmNames.get(parent.babbleTypeId());
                        if (parentJvm != null && !extended.contains(parentJvm)) {
                            extended.add(parentJvm);
                        }
                    } else if (parent.javaClass() != null) {
                        Class<?> jc = parent.javaClass();
                        if (jc.isInterface() && JavaTypes.isAccessible(jc) && !conflictsWithBabbleInstanceMethod(jc)) {
                            String javaInternal = Type.getInternalName(jc);
                            if (!extended.contains(javaInternal)) {
                                extended.add(javaInternal);
                            }
                        }
                    }
                }
            }
            generatedInterfaceParents.put(jvmName, List.copyOf(extended));
            BabbleParser.InterfaceExpressionContext ctx = localIfaces.get(ifaceId);
            if (ctx != null) {
                ClassShape shape = scopes.shape(ctx);
                InterfaceGen gen = new InterfaceGen(ctx, jvmName, shape, extended.toArray(new String[0]));
                interfaceGens.put(ctx, gen);
                interfaceGensById.put(ifaceId, gen);
            }
        }
    }

    /**
     * Generates the script class, then the JVM interfaces and classes of the file.
     *
     * @return the class files, by internal name, the script class first
     */
    Map<String, byte[]> generate(BabbleParser.FileContext file) {
        this.currentFile = file;
        registerTypes(file);
        classWriter.visit(V21, ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, className, null, OBJECT, null);
        classWriter.visitSource(sourceName, null);

        generateRun(file);
        while (!pendingMethods.isEmpty()) {
            pendingMethods.poll().run();
        }
        for (InterfaceGen iface : interfaceGens.values()) {
            iface.emitBody();
        }
        for (ClassGen gen : classGens.values()) {
            gen.emitInterfaceBridges();
        }
        generateConstants();

        classWriter.visitEnd();
        Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        classes.put(className, classWriter.toByteArray());
        for (InterfaceGen iface : interfaceGens.values()) {
            iface.writer.visitEnd();
            classes.put(iface.name, iface.writer.toByteArray());
        }
        for (ClassGen gen : classGens.values()) {
            gen.writer.visitEnd();
            classes.put(gen.name, gen.writer.toByteArray());
        }
        return classes;
    }

    private List<Class<?>> implementableJavaInterfaces(ClassShape shape) {
        if (shape == null) {
            return List.of();
        }
        ScopeAnalysis.TypeHierarchyInfo info = scopes.typeHierarchy(shape.id());
        if (info == null) {
            return List.of();
        }
        java.util.Set<Class<?>> javaIfaces = new java.util.LinkedHashSet<>();
        for (Class<?> jc : info.allJavaInterfaces()) {
            collectImplementableJavaInterfaces(jc, javaIfaces);
        }
        return List.copyOf(javaIfaces);
    }

    private List<String> implementedInterfaces(ClassShape shape, List<Class<?>> javaIfaces) {
        if (shape == null) {
            return List.of();
        }
        ScopeAnalysis.TypeHierarchyInfo info = scopes.typeHierarchy(shape.id());
        if (info == null) {
            return List.of();
        }
        List<String> implemented = new ArrayList<>();
        for (ScopeAnalysis.ParentRef parent : info.directParents()) {
            if (parent.babbleTypeId() != null) {
                String ifaceJvm = allInterfaceJvmNames.get(parent.babbleTypeId());
                if (ifaceJvm != null && !implemented.contains(ifaceJvm)) {
                    implemented.add(ifaceJvm);
                }
            }
        }
        for (Class<?> jc : javaIfaces) {
            String javaInternal = Type.getInternalName(jc);
            if (!implemented.contains(javaInternal)) {
                implemented.add(javaInternal);
            }
        }
        return implemented;
    }

    /** The compiled class of a class or object expression, or null when it keeps plain BabbleInstance. */
    private ClassGen classGen(ParserRuleContext ctx) {
        ClassShape shape = scopes.shape(ctx);
        boolean arrayBacked = resolution.isNamespaceScope(ctx);
        List<Class<?>> javaIfaces = implementableJavaInterfaces(shape);
        List<String> implemented = implementedInterfaces(shape, javaIfaces);
        if (arrayBacked && !preassignedClassNames.containsKey(ctx) && implemented.isEmpty()) {
            return null;
        }
        return classGens.computeIfAbsent(ctx, c -> {
            String preassigned = preassignedClassNames.get(c);
            String name = preassigned != null ? preassigned : className + "$C" + (classCount++) + "$" + shape.name();
            generatedClasses.add(name);
            List<String> parents = new ArrayList<>(1 + implemented.size());
            parents.add(BABBLE_INSTANCE);
            parents.addAll(implemented);
            generatedClassParents.put(name, List.copyOf(parents));
            ClassGen gen = new ClassGen(c, name, shape, arrayBacked, implemented.toArray(new String[0]), javaIfaces);
            gen.begin();
            return gen;
        });
    }

    // ---------------------------------------------------------------- methods

    private void beginMethod(ParserRuleContext owner, int access, String name, String descriptor, int firstFreeLocal, int rt, int ns) {
        beginMethod(classWriter, owner, access, name, descriptor, firstFreeLocal, rt, ns);
    }

    private void beginMethod(ClassWriter writer, ParserRuleContext owner, int access, String name, String descriptor, int firstFreeLocal, int rt, int ns) {
        mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        methodOwner = owner;
        rtLocal = rt;
        nsLocal = ns;
        frameNs = ns;
        parameterBase = -1;
        nextLocal = firstFreeLocal;
        currentLine = -1;
        deferred = new ArrayList<>();
        slots = new HashMap<>();
        definedSlots = new HashMap<>();
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        methodReturnKind = ScopeAnalysis.PrimitiveKind.NONE;
        capturedVariables = resolution.captured(owner);
        capturedLocal = -1;
        staticTarget = -1;
        selfLocal = SELF;
        thizLocal = 1;
        methodClass = null;
        constructorClass = null;
        typedThisLocal = -1;
    }

    /**
     * Loads the captured cells of the method, from the function or class value in local
     * 0, into a local of their own.
     */
    private void loadCapturedCells(String ownerType) {
        if (capturedVariables.isEmpty()) {
            return;
        }
        capturedLocal = newLocal();
        if (methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "babbleClass", "()L" + BABBLE_CLASS + ";", false);
        } else {
            mv.visitVarInsn(ALOAD, SELF);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, ownerType, "captured", "()[Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, capturedLocal);
    }

    private void endMethod() {
        // Code placed out of line, after the end of the method body.
        for (int i = 0; i < deferred.size(); i++) {
            deferred.get(i).run();
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    private int newLocal() {
        return nextLocal++;
    }

    private int newLocal(ScopeAnalysis.PrimitiveKind kind) {
        int slot = nextLocal;
        nextLocal += kind.slots();
        return slot;
    }

    private static String jvmDescriptor(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case INT -> "I";
            case DOUBLE -> "D";
            case BOOL -> "Z";
            case NONE -> "Ljava/lang/Object;";
        };
    }

    private static String cellClass(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case INT -> INT_CELL;
            case DOUBLE -> DOUBLE_CELL;
            case BOOL -> BOOL_CELL;
            case NONE -> CELL;
        };
    }

    private ScopeAnalysis.PrimitiveKind variableKind(Resolution.Variable variable) {
        ParserRuleContext decl = variable.declaration();
        return decl != null ? scopes.declarationKind(decl) : ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void loadLocal(ScopeAnalysis.PrimitiveKind kind, int slot) {
        int op = switch (kind) {
            case INT, BOOL -> ILOAD;
            case DOUBLE -> DLOAD;
            case NONE -> ALOAD;
        };
        mv.visitVarInsn(op, slot);
    }

    private void storeLocal(ScopeAnalysis.PrimitiveKind kind, int slot) {
        int op = switch (kind) {
            case INT, BOOL -> ISTORE;
            case DOUBLE -> DSTORE;
            case NONE -> ASTORE;
        };
        mv.visitVarInsn(op, slot);
    }

    private void dup(ScopeAnalysis.PrimitiveKind kind) {
        mv.visitInsn(kind == ScopeAnalysis.PrimitiveKind.DOUBLE ? DUP2 : DUP);
    }

    private void pop(ScopeAnalysis.PrimitiveKind kind) {
        mv.visitInsn(kind == ScopeAnalysis.PrimitiveKind.DOUBLE ? POP2 : POP);
    }

    private static int returnInsn(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case INT, BOOL -> IRETURN;
            case DOUBLE -> DRETURN;
            case NONE -> ARETURN;
        };
    }

    private void pushDefaultZero(ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case INT, BOOL -> mv.visitInsn(ICONST_0);
            case DOUBLE -> mv.visitInsn(DCONST_0);
            case NONE -> mv.visitInsn(ACONST_NULL);
        }
    }

    private void box(ScopeAnalysis.PrimitiveKind kind) {
        boxOn(mv, kind);
    }

    private static void boxOn(MethodVisitor m, ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case INT -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case DOUBLE -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            case BOOL -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case NONE -> {
            }
        }
    }

    private void unbox(ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case INT -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            }
            case DOUBLE -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
            }
            case BOOL -> {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            }
            case NONE -> {
            }
        }
    }

    private void convertStack(ScopeAnalysis.PrimitiveKind from, ScopeAnalysis.PrimitiveKind to) {
        if (from == to) {
            return;
        }
        if (to == ScopeAnalysis.PrimitiveKind.NONE) {
            box(from);
        } else if (from == ScopeAnalysis.PrimitiveKind.NONE) {
            unbox(to);
        } else if (from == ScopeAnalysis.PrimitiveKind.INT && to == ScopeAnalysis.PrimitiveKind.DOUBLE) {
            mv.visitInsn(I2D);
        } else {
            box(from);
            unbox(to);
        }
        stackKind = to;
    }

    private void widenStackToDouble() {
        if (stackKind == ScopeAnalysis.PrimitiveKind.INT) {
            mv.visitInsn(I2D);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
        } else if (stackKind == ScopeAnalysis.PrimitiveKind.NONE) {
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
    }

    private void emitExpr(ParserRuleContext ctx) {
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        visit(ctx);
    }

    private void emitAndWidenIfMarked(ParserRuleContext ctx) {
        emitExpr(ctx);
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
    }

    private void emitAs(ParserRuleContext ctx, ScopeAnalysis.PrimitiveKind desired) {
        emitAndWidenIfMarked(ctx);
        if (stackKind == ScopeAnalysis.PrimitiveKind.NONE && desired == ScopeAnalysis.PrimitiveKind.DOUBLE
                && scopes.expressionKind(ctx) == ScopeAnalysis.PrimitiveKind.INT
                && !scopes.widensToDouble(ctx)) {
            unbox(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(I2D);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }
        convertStack(stackKind, desired);
    }

    private void emitBoxed(ParserRuleContext ctx) {
        emitAs(ctx, ScopeAnalysis.PrimitiveKind.NONE);
    }

    private void generateRun(BabbleParser.FileContext file) {
        List<List<BabbleParser.ExpressionContext>> chunks = splitStatements(file.expression());

        beginMethod(file, ACC_STATIC, "run", RUN_DESCRIPTOR, 2, 0, 1);
        // The top-level definitions are declared before anything runs. That fails if a
        // binding has the same name, which is reported at the start of the file.
        line(file);
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(scopes.declared(file));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "declare", "([Ljava/lang/String;)V", false);

        if (chunks.size() == 1) {
            statements(chunks.get(0));
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitInsn(ARETURN);
            endMethod();
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, className, "run$" + i, RUN_DESCRIPTOR, false);
            mv.visitInsn(i == chunks.size() - 1 ? ARETURN : POP);
        }
        endMethod();

        for (int i = 0; i < chunks.size(); i++) {
            beginMethod(file, ACC_PRIVATE | ACC_STATIC, "run$" + i, RUN_DESCRIPTOR, 2, 0, 1);
            statements(chunks.get(i));
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitInsn(ARETURN);
            endMethod();
        }
    }

    /**
     * Groups the top-level statements so that each group compiles to a method of a
     * bounded size. Function literals are separate methods, so their bodies don't count.
     */
    private static List<List<BabbleParser.ExpressionContext>> splitStatements(List<BabbleParser.ExpressionContext> statements) {
        List<List<BabbleParser.ExpressionContext>> chunks = new ArrayList<>();
        List<BabbleParser.ExpressionContext> chunk = new ArrayList<>();
        int size = 0;
        for (BabbleParser.ExpressionContext statement : statements) {
            int statementSize = countNodes(statement);
            if (!chunk.isEmpty() && size + statementSize > MAX_NODES_PER_RUN_METHOD) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                size = 0;
            }
            chunk.add(statement);
            size += statementSize;
        }
        chunks.add(chunk);
        return chunks;
    }

    private static int countNodes(ParseTree tree) {
        if (tree instanceof BabbleParser.FunctionLiteralContext) {
            return 1;
        }
        int count = 1;
        for (int i = 0; i < tree.getChildCount(); i++) {
            count += countNodes(tree.getChild(i));
        }
        return count;
    }

    private String functionMethodName(BabbleParser.FunctionLiteralContext ctx) {
        return functionMethodNames.computeIfAbsent(ctx, c -> {
            String name = ScopeAnalysis.functionName(c);
            return "fn$" + (functionCount++) + (name != null ? "$" + name : "");
        });
    }

    /** Whether a function literal gets a direct entry point, its parameters spread. */
    private boolean isSpread(BabbleParser.FunctionLiteralContext literal) {
        return scopes.parameters(literal).length <= MAX_SPREAD;
    }

    /** Whether a function or method literal gets a typed primitive {@code $spec} entry point. */
    private boolean hasSpec(BabbleParser.FunctionLiteralContext literal) {
        if (!isSpread(literal)) {
            return false;
        }
        boolean hasPrimitive = scopes.functionReturnKind(literal).isPrimitive();
        int slots = 3;
        for (BabbleParser.ParameterDeclarationContext decl : literal.parametersDeclaration().parameterDeclaration()) {
            ScopeAnalysis.PrimitiveKind kind = scopes.declarationKind(decl);
            if (kind.isPrimitive()) {
                hasPrimitive = true;
            }
            slots += kind.slots();
        }
        return hasPrimitive && slots <= 250;
    }

    /** {@code (CompiledFunction self, Object thiz, ScriptRuntime rt, Arguments.Binder binder, Object p0, ...)} */
    private String functionDirectDescriptor(BabbleParser.FunctionLiteralContext literal) {
        return "(L" + COMPILED_FUNCTION + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME + ";L" + BINDER + ";"
                + "Ljava/lang/Object;".repeat(scopes.parameters(literal).length) + ")Ljava/lang/Object;";
    }

    /** {@code (CompiledFunction self, Object thiz, ScriptRuntime rt, P0, ...)<R>} */
    private String functionSpecDescriptor(BabbleParser.FunctionLiteralContext literal) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(COMPILED_FUNCTION).append(";Ljava/lang/Object;L").append(SCRIPT_RUNTIME).append(';');
        for (BabbleParser.ParameterDeclarationContext decl : literal.parametersDeclaration().parameterDeclaration()) {
            sb.append(jvmDescriptor(scopes.declarationKind(decl)));
        }
        sb.append(')').append(jvmDescriptor(scopes.functionReturnKind(literal)));
        return sb.toString();
    }

    /** {@code (CompiledMethod self, ScriptRuntime rt, Arguments.Binder binder, Object p0, ...)}, the receiver first. */
    private String methodDirectDescriptor(BabbleParser.FunctionLiteralContext literal) {
        return "(L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";L" + BINDER + ";"
                + "Ljava/lang/Object;".repeat(scopes.parameters(literal).length) + ")Ljava/lang/Object;";
    }

    /** {@code (CompiledMethod self, ScriptRuntime rt, P0, ...)<R>}, the receiver first. */
    private String methodSpecDescriptor(BabbleParser.FunctionLiteralContext literal) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(COMPILED_METHOD).append(";L").append(SCRIPT_RUNTIME).append(';');
        for (BabbleParser.ParameterDeclarationContext decl : literal.parametersDeclaration().parameterDeclaration()) {
            sb.append(jvmDescriptor(scopes.declarationKind(decl)));
        }
        sb.append(')').append(jvmDescriptor(scopes.functionReturnKind(literal)));
        return sb.toString();
    }

    private void generateFunction(BabbleParser.FunctionLiteralContext literal, String method, String paramsField) {
        if (isSpread(literal)) {
            int count = scopes.parameters(literal).length;
            String direct = functionDirectDescriptor(literal);
            callAdapter(classWriter, ACC_STATIC | ACC_SYNTHETIC, method + "$call", BODY_DESCRIPTOR, paramsField, count,
                    () -> mv.visitMethodInsn(INVOKESTATIC, className, method, direct, false));
            beginMethod(literal, ACC_STATIC | ACC_SYNTHETIC, method, direct, FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count);
            parameterBase = FIRST_PARAMETER;
        } else {
            beginMethod(literal, ACC_STATIC | ACC_SYNTHETIC, method, BODY_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS);
        }
        loadCapturedCells(COMPILED_FUNCTION);
        functionBody(literal, paramsField, COMPILED_FUNCTION);

        if (hasSpec(literal)) {
            generateFunctionSpec(literal, method);
        }
    }

    private void generateFunctionSpec(BabbleParser.FunctionLiteralContext literal, String method) {
        List<BabbleParser.ParameterDeclarationContext> decls = literal.parametersDeclaration().parameterDeclaration();
        int[] paramSlots = new int[decls.size()];
        int nextParam = 3;
        for (int i = 0; i < decls.size(); i++) {
            paramSlots[i] = nextParam;
            nextParam += scopes.declarationKind(decls.get(i)).slots();
        }
        int nsSlot = nextParam;
        beginMethod(literal, ACC_STATIC | ACC_SYNTHETIC, method + "$spec", functionSpecDescriptor(literal), nsSlot + 1, FN_RT, nsSlot);
        methodReturnKind = scopes.functionReturnKind(literal);
        loadCapturedCells(COMPILED_FUNCTION);
        functionBodySpec(literal, COMPILED_FUNCTION, paramSlots);
    }

    /**
     * The array entry point of a function or method : it binds the arguments by name and
     * position, then calls the direct entry point with them and the binder, which rejects
     * the leftovers once the defaults have run. Its first three parameters are those of the
     * direct entry point.
     */
    private void callAdapter(ClassWriter writer, int access, String name, String descriptor, String paramsField, int count, Runnable callDirect) {
        mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        // The first local past the parameters, where a function body keeps its namespace.
        int binder = FN_NS;
        mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        mv.visitVarInsn(ALOAD, ARGS);
        mv.visitVarInsn(ALOAD, NAMES);
        mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        mv.visitVarInsn(ASTORE, binder);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, FN_RT);
        mv.visitVarInsn(ALOAD, binder);
        for (int i = 0; i < count; i++) {
            mv.visitVarInsn(ALOAD, binder);
            pushInt(mv, i);
            mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "take", "(I)Ljava/lang/Object;", false);
        }
        callDirect.run();
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    /**
     * {@code m$name(CompiledMethod self, ScriptRuntime rt, Arguments.Binder binder, Object
     * p0, ...)} : a method of a compiled class, as a JVM instance method of it, with its
     * array entry point {@code m$name$call(CompiledMethod self, ScriptRuntime rt, Object[]
     * args, String[] names, CallSite site)}, as for a function. Its closure and captured
     * cells are the class value's, and the constructor's variables it uses are hidden
     * fields of the instance.
     */
    private void generateMethod(BabbleParser.FunctionLiteralContext literal, ClassGen gen, String paramsField) {
        String member = ((BabbleParser.DefExpressionContext) literal.getParent()).name.getText();
        String method = gen.methodName(member);
        if (isSpread(literal)) {
            int count = scopes.parameters(literal).length;
            String direct = methodDirectDescriptor(literal);
            callAdapter(gen.writer, ACC_SYNTHETIC, method + "$call", METHOD_DESCRIPTOR, paramsField, count,
                    () -> mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, method, direct, false));
            beginMethod(gen.writer, literal, ACC_SYNTHETIC, method, direct, FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count);
            parameterBase = FIRST_PARAMETER;
        } else {
            beginMethod(gen.writer, literal, ACC_SYNTHETIC, method, METHOD_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS);
        }
        selfLocal = 1;
        thizLocal = 0;
        methodClass = gen;
        capturedVariables = resolution.captured(gen.ctx);
        loadCapturedCells(BABBLE_CLASS);
        functionBody(literal, paramsField, COMPILED_METHOD);

        if (hasSpec(literal)) {
            generateMethodSpec(literal, gen, method);
        }
    }

    private void generateMethodSpec(BabbleParser.FunctionLiteralContext literal, ClassGen gen, String method) {
        List<BabbleParser.ParameterDeclarationContext> decls = literal.parametersDeclaration().parameterDeclaration();
        int[] paramSlots = new int[decls.size()];
        int nextParam = 3;
        for (int i = 0; i < decls.size(); i++) {
            paramSlots[i] = nextParam;
            nextParam += scopes.declarationKind(decls.get(i)).slots();
        }
        int nsSlot = nextParam;
        beginMethod(gen.writer, literal, ACC_SYNTHETIC, method + "$spec", methodSpecDescriptor(literal), nsSlot + 1, FN_RT, nsSlot);
        methodReturnKind = scopes.functionReturnKind(literal);
        selfLocal = 1;
        thizLocal = 0;
        methodClass = gen;
        capturedVariables = resolution.captured(gen.ctx);
        loadCapturedCells(BABBLE_CLASS);
        functionBodySpec(literal, COMPILED_METHOD, paramSlots);
    }

    /** Pushes the namespace a function or method closes over. */
    private void loadClosure() {
        if (methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "babbleClass", "()L" + BABBLE_CLASS + ";", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        } else {
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitMethodInsn(INVOKEVIRTUAL, COMPILED_FUNCTION, "closure", "()L" + NAMESPACE + ";", false);
        }
    }

    private void loadRecurseTarget(String selfType) {
        mv.visitVarInsn(ALOAD, selfLocal);
        mv.visitVarInsn(ALOAD, thizLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, selfType, "recurseTarget", "(Ljava/lang/Object;)L" + CALLABLE + ";", false);
    }

    private void initFrameLocals(BabbleParser.FunctionLiteralContext literal, String selfType) {
        if (resolution.isNamespaceScope(literal)) {
            // Phase 1 : the call's namespace defines recurse, this, the hoisted names and the parameters.
            loadClosure();
            loadRecurseTarget(selfType);
            mv.visitVarInsn(ALOAD, thizLocal);
            getStringArray(scopes.declared(literal));
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "enter", "(L" + NAMESPACE + ";L" + CALLABLE + ";Ljava/lang/Object;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
            mv.visitVarInsn(ASTORE, frameNs);
        } else {
            // The namespace is the closure's, for names resolved at runtime ; variables are locals.
            loadClosure();
            mv.visitVarInsn(ASTORE, frameNs);
            for (Resolution.Variable variable : declareVariables(literal)) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.isUsed()) {
                    mv.visitVarInsn(ALOAD, thizLocal);
                    initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                } else if (variable.kind() == Resolution.Variable.Kind.RECURSE && variable.isUsed()) {
                    loadRecurseTarget(selfType);
                    initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                }
            }
        }
    }

    private void functionBody(BabbleParser.FunctionLiteralContext literal, String paramsField, String selfType) {
        initFrameLocals(literal, selfType);
        bindParameters(paramsField, scopes.parameters(literal), literal.parametersDeclaration().parameterDeclaration(), null);

        List<BabbleParser.ExpressionContext> body = literal.functionBlock.expression();
        statements(body);
        if (scopes.widensToDouble(literal.functionBlock)) {
            widenStackToDouble();
        }
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitInsn(ARETURN);
        endMethod();
    }

    private void functionBodySpec(BabbleParser.FunctionLiteralContext literal, String selfType, int[] paramSlots) {
        initFrameLocals(literal, selfType);
        String[] parameters = scopes.parameters(literal);
        List<BabbleParser.ParameterDeclarationContext> decls = literal.parametersDeclaration().parameterDeclaration();
        for (int i = 0; i < parameters.length; i++) {
            BabbleParser.ParameterDeclarationContext decl = decls.get(i);
            ScopeAnalysis.PrimitiveKind pk = scopes.declarationKind(decl);
            Resolution.Variable variable = resolution.variable(decl);
            if (variable != null) {
                loadLocal(pk, paramSlots[i]);
                if (variable.isCell()) {
                    initVariable(variable, pk);
                } else {
                    initVariable(variable, pk);
                }
            } else {
                mv.visitVarInsn(ALOAD, frameNs);
                mv.visitLdcInsn(parameters[i]);
                loadLocal(pk, paramSlots[i]);
                box(pk);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
            }
        }
        List<BabbleParser.ExpressionContext> body = literal.functionBlock.expression();
        statements(body);
        if (scopes.widensToDouble(literal.functionBlock)) {
            widenStackToDouble();
        }
        convertStack(stackKind, methodReturnKind);
        mv.visitInsn(returnInsn(methodReturnKind));
        endMethod();
    }

    /**
     * {@code cls$N(BabbleClass class, BabbleInstance instance, ScriptRuntime rt, Object[] args,
     * String[] names, CallSite site)} : the constructor of a class, or of an object
     * literal's anonymous class. It binds the arguments, header members going straight into
     * the instance, then runs the body. Like a function prologue, the binding has no line
     * numbers, so its errors are reported at the line of the construction.
     */
    private void generateClassBody(ParserRuleContext ctx, String method, ClassShape shape, BabbleParser.BlockContext body,
                                   List<BabbleParser.ParameterDeclarationContext> declarations) {
        beginMethod(ctx, ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, method, CLASS_BODY_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS);
        loadCapturedCells(BABBLE_CLASS);

        if (resolution.isNamespaceScope(ctx)) {
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitVarInsn(ALOAD, INSTANCE);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "constructorScope", "(L" + BABBLE_CLASS + ";L" + BABBLE_INSTANCE + ";)L" + NAMESPACE + ";", false);
            mv.visitVarInsn(ASTORE, FN_NS);
        } else {
            constructorClass = classGens.get(ctx);
            if (constructorClass != null) {
                typedThisLocal = newLocal();
                mv.visitVarInsn(ALOAD, INSTANCE);
                mv.visitTypeInsn(CHECKCAST, constructorClass.name);
                mv.visitVarInsn(ASTORE, typedThisLocal);
            }
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
            mv.visitVarInsn(ASTORE, FN_NS);
            for (Resolution.Variable variable : declareVariables(ctx)) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.isUsed()) {
                    mv.visitVarInsn(ALOAD, INSTANCE);
                    initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                }
            }
        }

        bindParameters(stringArrayField(shape.parameters()), shape.parameters(), declarations, shape);

        statements(body.expression());
        pop(stackKind);
        mv.visitInsn(RETURN);
        endMethod();
    }

    /**
     * Binds the arguments of a call to parameters, through {@code Arguments.Binder}.
     * Default values are compiled out of line, after the body, so that their line numbers
     * don't cover the binding. With a class shape, header members are stored into the
     * instance instead of being defined as names.
     */
    private void bindParameters(String paramsField, String[] parameters, List<BabbleParser.ParameterDeclarationContext> declarations, ClassShape shape) {
        // In a direct entry point, the arguments are parameters, MISSING when not given.
        boolean spread = parameterBase >= 0;
        int binder = spread ? BINDER_LOCAL : newLocal();
        int arrayValue = spread ? -1 : newLocal();
        if (!spread) {
            mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
            mv.visitVarInsn(ALOAD, ARGS);
            mv.visitVarInsn(ALOAD, NAMES);
            mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
            mv.visitVarInsn(ASTORE, binder);
        }

        for (int i = 0; i < parameters.length; i++) {
            BabbleParser.ExpressionContext defaultValue = declarations.get(i).defaultValue;
            Label bind = new Label();
            int value = spread ? parameterBase + i : arrayValue;

            if (!spread) {
                mv.visitVarInsn(ALOAD, binder);
                pushInt(mv, i);
                mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "take", "(I)Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, value);
            }
            mv.visitVarInsn(ALOAD, value);
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            mv.visitJumpInsn(IF_ACMPNE, bind);
            if (defaultValue != null) {
                Label computeDefault = new Label();
                mv.visitJumpInsn(GOTO, computeDefault);
                deferred.add(() -> {
                    mv.visitLabel(computeDefault);
                    emitBoxed(defaultValue);
                    mv.visitVarInsn(ASTORE, value);
                    mv.visitJumpInsn(GOTO, bind);
                });
            } else if (spread) {
                mv.visitLdcInsn(parameters[i]);
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "missing", "(Ljava/lang/String;)Lorg/babblelang/runtime/BabbleException;", false);
                mv.visitInsn(ATHROW);
            } else {
                mv.visitVarInsn(ALOAD, binder);
                pushInt(mv, i);
                mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "missing", "(I)Lorg/babblelang/runtime/BabbleException;", false);
                mv.visitInsn(ATHROW);
            }
            mv.visitLabel(bind);
            if (scopes.widensToDouble(declarations.get(i))) {
                mv.visitVarInsn(ALOAD, value);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, value);
            }
            int member = shape != null ? shape.parameterMember(i) : -1;
            if (member >= 0 && constructorClass != null) {
                ScopeAnalysis.PrimitiveKind mk = constructorClass.memberKinds[member];
                mv.visitVarInsn(ALOAD, typedThisLocal);
                mv.visitVarInsn(ALOAD, value);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                convertStack(ScopeAnalysis.PrimitiveKind.NONE, mk);
                mv.visitFieldInsn(PUTFIELD, constructorClass.name, shape.member(member), jvmDescriptor(mk));
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            } else if (member >= 0) {
                mv.visitVarInsn(ALOAD, INSTANCE);
                pushInt(mv, member);
                mv.visitVarInsn(ALOAD, value);
                mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "initMember", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(POP);
            } else if (resolution.variable(declarations.get(i)) != null) {
                Resolution.Variable variable = resolution.variable(declarations.get(i));
                ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
                mv.visitVarInsn(ALOAD, value);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                convertStack(ScopeAnalysis.PrimitiveKind.NONE, vk);
                if (variable.isCell()) {
                    storeVariable(variable, true, vk, declarations.get(i));
                    pop(vk);
                } else {
                    initVariable(variable, vk);
                }
            } else {
                mv.visitVarInsn(ALOAD, frameNs);
                mv.visitLdcInsn(parameters[i]);
                mv.visitVarInsn(ALOAD, value);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
            }
        }
        // A direct call passes no more arguments than there are parameters, and no names.
        Label bound = new Label();
        if (spread) {
            mv.visitVarInsn(ALOAD, binder);
            mv.visitJumpInsn(IFNULL, bound);
        }
        mv.visitVarInsn(ALOAD, binder);
        mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "rejectLeftovers", "()V", false);
        mv.visitLabel(bound);
    }

    // ---------------------------------------------------------------- frame variables

    /**
     * Gives JVM locals to the variables a frame scope declares, and creates its hoisted
     * definitions as undefined, with the cells of captured parameters. Parameters, this and
     * recurse are initialised by the caller.
     */
    private List<Resolution.Variable> declareVariables(ParserRuleContext scope) {
        List<Resolution.Variable> variables = resolution.declared(scope);
        for (Resolution.Variable variable : variables) {
            ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
            boolean isCellOrObj = variable.isCell() || !vk.isPrimitive();
            int slot = newLocal(variable.isCell() ? ScopeAnalysis.PrimitiveKind.NONE : vk);
            slots.put(variable, slot);
            if (!variable.isCell() && vk.isPrimitive() && variable.mayBeUndefined()
                    && (variable.declaration() == null || !scopes.isDefinitelyDefined(variable.declaration()))) {
                int defSlot = newLocal(ScopeAnalysis.PrimitiveKind.BOOL);
                definedSlots.put(variable, defSlot);
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
            if (variable.kind() == Resolution.Variable.Kind.DEFINITION || variable.kind() == Resolution.Variable.Kind.PARAMETER && variable.isCell()) {
                if (variable.isCell()) {
                    String cc = cellClass(vk);
                    mv.visitMethodInsn(INVOKESTATIC, cc, "undefined", "()L" + cc + ";", false);
                    mv.visitVarInsn(ASTORE, slot);
                    publishCell(variable);
                } else if (vk.isPrimitive()) {
                    pushDefaultZero(vk);
                    storeLocal(vk, slot);
                } else {
                    mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
                    mv.visitVarInsn(ASTORE, slot);
                }
            }
        }
        return variables;
    }

    /**
     * A constructor variable captured by a nested function is also stored in a hidden
     * field of the instance, where the class's methods find it.
     */
    private void publishCell(Resolution.Variable variable) {
        if (constructorClass != null && variable.isCell() && variable.method() == constructorClass.ctx) {
            mv.visitVarInsn(ALOAD, typedThisLocal);
            mv.visitVarInsn(ALOAD, slots.get(variable));
            mv.visitFieldInsn(PUTFIELD, constructorClass.name, hiddenField(variable), "Ljava/lang/Object;");
        }
    }

    /** Stores the value on the stack (of kind {@code vk}), consumed, as the initial value of a variable. */
    private void initVariable(Resolution.Variable variable, ScopeAnalysis.PrimitiveKind vk) {
        if (variable.isCell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(INVOKESTATIC, cc, "of", "(" + jvmDescriptor(vk) + ")L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, slots.get(variable));
            publishCell(variable);
            return;
        }
        storeLocal(vk, slots.get(variable));
        Integer defSlot = definedSlots.get(variable);
        if (defSlot != null) {
            mv.visitInsn(ICONST_1);
            mv.visitVarInsn(ISTORE, defSlot);
        }
    }

    /** Pushes the cell of a captured variable : a local of this method, or one of the cells it captured. */
    private void loadCell(Resolution.Variable variable) {
        String cc = cellClass(variableKind(variable));
        if (variable.method() == methodOwner) {
            mv.visitVarInsn(ALOAD, slots.get(variable));
        } else if (methodClass != null && variable.method() == methodClass.ctx) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, methodClass.name, hiddenField(variable), "Ljava/lang/Object;");
            mv.visitTypeInsn(CHECKCAST, cc);
        } else {
            mv.visitVarInsn(ALOAD, capturedLocal);
            pushInt(mv, capturedVariables.indexOf(variable));
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, cc);
        }
    }

    private void loadVariable(Resolution.Variable variable, ParserRuleContext ctx) {
        ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
        if (variable.isCell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            loadCell(variable);
            if (variable.mayBeUndefined() && (variable.declaration() == null || !scopes.isDefinitelyDefined(variable.declaration()))) {
                line(ctx);
                mv.visitLdcInsn(variable.name());
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "read", "(Ljava/lang/String;)" + desc, false);
            } else {
                mv.visitFieldInsn(GETFIELD, cc, "value", desc);
            }
            stackKind = vk;
            return;
        }
        if (vk.isPrimitive()) {
            Integer defSlot = definedSlots.get(variable);
            if (defSlot != null) {
                mv.visitVarInsn(ILOAD, defSlot);
                mv.visitLdcInsn(variable.name());
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
            }
            loadLocal(vk, slots.get(variable));
            stackKind = vk;
            return;
        }
        mv.visitVarInsn(ALOAD, slots.get(variable));
        if (variable.mayBeUndefined() && (variable.declaration() == null || !scopes.isDefinitelyDefined(variable.declaration()))) {
            line(ctx);
            mv.visitLdcInsn(variable.name());
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "defined", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /**
     * Stores the value of kind {@code vk} on top of the stack into a variable, leaving it
     * there. A definition always succeeds ; an assignment requires the definition to have run.
     */
    private void storeVariable(Resolution.Variable variable, boolean definition, ScopeAnalysis.PrimitiveKind vk, ParserRuleContext ctx) {
        if (variable.isCell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            int tmp = newLocal(vk);
            storeLocal(vk, tmp);
            loadCell(variable);
            loadLocal(vk, tmp);
            if (definition) {
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "define", "(" + desc + ")" + desc, false);
            } else {
                mv.visitLdcInsn(variable.name());
                line(ctx);
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "assign", "(" + desc + "Ljava/lang/String;)" + desc, false);
            }
            stackKind = vk;
            return;
        }
        int slot = slots.get(variable);
        if (vk.isPrimitive()) {
            Integer defSlot = definedSlots.get(variable);
            if (defSlot != null) {
                if (definition) {
                    mv.visitInsn(ICONST_1);
                    mv.visitVarInsn(ISTORE, defSlot);
                } else {
                    mv.visitVarInsn(ILOAD, defSlot);
                    mv.visitLdcInsn(variable.name());
                    line(ctx);
                    mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
                }
            }
            dup(vk);
            storeLocal(vk, slot);
            stackKind = vk;
            return;
        }
        if (!definition && variable.mayBeUndefined() && (variable.declaration() == null || !scopes.isDefinitelyDefined(variable.declaration()))) {
            mv.visitVarInsn(ALOAD, slot);
            mv.visitLdcInsn(variable.name());
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkAssignable", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, slot);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /** Pushes the value of a name : a variable, or a lookup in the current namespace. */
    private void loadName(ParserRuleContext occurrence, String name) {
        Resolution.Variable variable = resolution.variable(occurrence);
        if (variable != null) {
            loadVariable(variable, occurrence);
            return;
        }
        line(occurrence);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /** Pushes the cells a function or class value captures, as an Object[]. */
    private void capturedCells(ParserRuleContext method) {
        List<Resolution.Variable> captured = resolution.captured(method);
        if (captured.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        pushInt(mv, captured.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < captured.size(); i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            loadCell(captured.get(i));
            mv.visitInsn(AASTORE);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Records the Babble line of the code about to be emitted, before anything that can
     * fail : the reported line of an error is that of the innermost script frame.
     */
    private void line(ParserRuleContext ctx) {
        int line = ctx.getStart().getLine();
        if (line != currentLine) {
            Label label = new Label();
            mv.visitLabel(label);
            mv.visitLineNumber(line, label);
            currentLine = line;
        }
    }

    /**
     * A sequence of statements, each followed by popping the previous value. An empty
     * sequence yields null.
     */
    private void statements(List<BabbleParser.ExpressionContext> statements) {
        if (statements.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        for (int i = 0; i < statements.size(); i++) {
            if (i > 0) {
                pop(stackKind);
            }
            line(statements.get(i));
            emitExpr(statements.get(i));
        }
    }

    /** Compiles {@code body} with {@code namespace} as the current namespace. */
    private void withNamespace(int namespace, Runnable body) {
        int outer = nsLocal;
        int savedNextLocal = nextLocal;
        nsLocal = namespace;
        try {
            body.run();
        } finally {
            nsLocal = outer;
            // The locals of an inner scope are dead past its end, so their slots are reused.
            nextLocal = savedNextLocal;
        }
    }

    private static void pushString(MethodVisitor mv, String value) {
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

    private static void pushStringArray(MethodVisitor mv, String[] values) {
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

    private static void pushInt(MethodVisitor mv, int value) {
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

    private static int estimateSize(String[] values) {
        int size = 8;
        for (String value : values) {
            size += 8 + (value == null ? 0 : 6 * (value.length() / MAX_STRING_CONSTANT));
        }
        return size;
    }

    // ---------------------------------------------------------------- constants

    private String addConstant(String prefix, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
        String field = prefix + constants.size();
        constants.add(new Constant(field, descriptor, estimatedSize, builder));
        return field;
    }

    /** Pushes a shared, immutable String[] constant. */
    private void getStringArray(String[] values) {
        mv.visitFieldInsn(GETSTATIC, className, stringArrayField(values), "[Ljava/lang/String;");
    }

    private String stringArrayField(String[] values) {
        return stringArrays.computeIfAbsent(Arrays.asList(values), key ->
                addConstant("NAMES", "[Ljava/lang/String;", estimateSize(values), m -> pushStringArray(m, values)));
    }

    private String opSiteField(BabbleParser.BinaryOpContext ctx) {
        return opSiteField(ctx.left.getText(), ctx.right.getText());
    }

    private String opSiteField(String left, String right) {
        return addConstant("OP", "L" + OP_SITE + ";", 16, m -> {
            m.visitTypeInsn(NEW, OP_SITE);
            m.visitInsn(DUP);
            pushString(m, left);
            pushString(m, right);
            m.visitMethodInsn(INVOKESPECIAL, OP_SITE, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        });
    }

    private String callSiteField(BabbleParser.CallContext ctx) {
        CallSite site = Interpreter.callSite(ctx, sourceName);
        return addConstant("CS", "L" + CALL_SITE + ";", 24 + estimateSize(site.argTexts()), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, site.sourceName());
            pushInt(m, site.line());
            pushString(m, site.calleeText());
            pushStringArray(m, site.argTexts());
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    /**
     * Declares a static field per constant, set in {@code <clinit>} by helper methods of
     * a bounded size, so that any number of constants fits. The JVM only lets
     * {@code <clinit>} itself set a final field, so the fields are not final ; nothing but
     * the helpers ever writes them.
     */
    private void generateConstants() {
        MethodVisitor clinit = classWriter.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        MethodVisitor helper = null;
        int size = 0;
        int helpers = 0;
        for (Constant constant : constants) {
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

    // ---------------------------------------------------------------- expressions

    @Override
    public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        line(ctx);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn(ctx.name.getText());
        getStringArray(scopes.declared(ctx));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "enterPackage", "(Ljava/lang/String;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(ctx.packageBlock.expression()));
        pop(stackKind);
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    // ---------------------------------------------------------------- classes

    private String shapeField(ClassShape shape) {
        String encoded = shape.encode();
        return addConstant("SHAPE", "L" + CLASS_SHAPE + ";", 16 + encoded.length() / MAX_STRING_CONSTANT * 6, m -> {
            pushString(m, encoded);
            m.visitMethodInsn(INVOKESTATIC, CLASS_SHAPE, "decode", "(Ljava/lang/String;)L" + CLASS_SHAPE + ";", false);
        });
    }

    /** Pushes the shape, the closure and the constructor of a class or object literal. */
    private void classValueArguments(ParserRuleContext ctx, BabbleParser.BlockContext body, List<BabbleParser.ParameterDeclarationContext> declarations) {
        ClassShape shape = scopes.shape(ctx);
        String name = shape.name();
        String method = "cls$" + (functionCount++) + "$" + name;
        pendingMethods.add(() -> generateClassBody(ctx, method, shape, body, declarations));

        mv.visitFieldInsn(GETSTATIC, className, shapeField(shape), "L" + CLASS_SHAPE + ";");
        mv.visitVarInsn(ALOAD, nsLocal);
        capturedCells(ctx);
        Type bodyType = Type.getMethodType(CLASS_BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("construct", "()L" + CLASS_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, method, CLASS_BODY_DESCRIPTOR, false), bodyType);
    }

    /** Pushes an Object[] of the entries of an implements or extends list. */
    private void types(BabbleParser.TypeListContext typeList) {
        List<BabbleParser.TypeContext> types = typeList != null ? typeList.type() : List.of();
        pushInt(mv, types.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < types.size(); i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            type(types.get(i), types.get(i));
            mv.visitInsn(AASTORE);
        }
    }

    /**
     * Pushes the value of a statically resolved type : a name, then members.
     *
     * @param occurrence what the resolution bound the first name of the type for
     */
    private void type(BabbleParser.TypeContext type, ParserRuleContext occurrence) {
        List<org.antlr.v4.runtime.tree.TerminalNode> ids = org.babblelang.analysis.ScopeAnalysis.simpleType(type).ID();
        line(type);
        loadName(occurrence, ids.get(0).getText());
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        StringBuilder text = new StringBuilder(ids.get(0).getText());
        for (int i = 1; i < ids.size(); i++) {
            mv.visitLdcInsn(ids.get(i).getText());
            pushString(mv, text.toString());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
            text.append('.').append(ids.get(i).getText());
        }
        pushString(mv, type.getText());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "type", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /**
     * Runs a static block, with {@code this} as the class or interface value on top of the
     * stack, which stays. Its defs are static members of that value.
     */
    private void staticBlock(BabbleParser.BlockContext block) {
        if (block == null) {
            return;
        }
        mv.visitInsn(DUP);
        int type = newLocal();
        mv.visitVarInsn(ASTORE, type);
        int outerTarget = staticTarget;
        staticTarget = type;
        if (resolution.isNamespaceScope(block)) {
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitVarInsn(ALOAD, type);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "staticScope", "(L" + NAMESPACE + ";L" + RUNTIME + "BabbleType;)L" + NAMESPACE + ";", false);
            int namespace = newLocal();
            mv.visitVarInsn(ASTORE, namespace);
            withNamespace(namespace, () -> statements(block.expression()));
        } else {
            inFrameScope(() -> {
                for (Resolution.Variable variable : declareVariables(block)) {
                    if (variable.kind() == Resolution.Variable.Kind.THIS && variable.isUsed()) {
                        mv.visitVarInsn(ALOAD, type);
                        initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                    }
                }
                statements(block.expression());
            });
        }
        staticTarget = outerTarget;
        pop(stackKind);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /** Compiles a frame scope inline : its variables' locals are reused past its end. */
    private void inFrameScope(Runnable body) {
        int savedNextLocal = nextLocal;
        Map<Resolution.Variable, Integer> savedSlots = new HashMap<>(slots);
        Map<Resolution.Variable, Integer> savedDefinedSlots = new HashMap<>(definedSlots);
        body.run();
        nextLocal = savedNextLocal;
        slots = savedSlots;
        definedSlots = savedDefinedSlots;
    }

    @Override
    public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
        List<BabbleParser.ParameterDeclarationContext> declarations = ctx.parametersDeclaration() != null
                ? ctx.parametersDeclaration().parameterDeclaration() : List.of();
        ClassGen gen = classGen(ctx);
        classValueArguments(ctx, ctx.classBlock, declarations);
        types(ctx.typeList());
        if (gen != null) {
            instanceFactory(gen);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                    "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;L" + INSTANCE_FACTORY + ";)L" + BABBLE_CLASS + ";", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                    "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;)L" + BABBLE_CLASS + ";", false);
        }
        staticBlock(ctx.staticBlock);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /** Pushes an InstanceFactory creating instances of a compiled class. */
    private void instanceFactory(ClassGen gen) {
        String descriptor = "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)L" + BABBLE_INSTANCE + ";";
        mv.visitInvokeDynamicInsn("create", "()L" + INSTANCE_FACTORY + ";", METAFACTORY,
                Type.getMethodType(descriptor),
                new Handle(H_NEWINVOKESPECIAL, gen.name, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V", false),
                Type.getMethodType("(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)L" + gen.name + ";"));
    }

    /** An object literal is the single instance of an anonymous class. */
    @Override
    public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        ClassGen gen = classGen(ctx);
        classValueArguments(ctx, ctx.createBlock, List.of());
        types(ctx.typeList());
        if (gen != null) {
            instanceFactory(gen);
        }
        mv.visitVarInsn(ALOAD, rtLocal);
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_CLASS, "object",
                "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;"
                        + (gen != null ? "L" + INSTANCE_FACTORY + ";" : "") + "L" + SCRIPT_RUNTIME + ";)L" + BABBLE_INSTANCE + ";", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
        mv.visitFieldInsn(GETSTATIC, className, shapeField(scopes.shape(ctx)), "L" + CLASS_SHAPE + ";");
        types(ctx.typeList());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newInterface", "(L" + CLASS_SHAPE + ";[Ljava/lang/Object;)L" + BABBLE_INTERFACE + ";", false);
        mv.visitInsn(DUP);
        int iface = newLocal();
        mv.visitVarInsn(ASTORE, iface);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitVarInsn(ALOAD, iface);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "interfaceScope", "(L" + NAMESPACE + ";L" + BABBLE_INTERFACE + ";)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(ctx.interfaceBlock.expression()));
        pop(stackKind);
        staticBlock(ctx.staticBlock);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitThisExpression(BabbleParser.ThisExpressionContext ctx) {
        loadName(ctx, "this");
        return null;
    }

    @Override
    public Void visitIsExpression(BabbleParser.IsExpressionContext ctx) {
        emitBoxed(ctx.left);
        String builtin = scopes.isBuiltin(ctx);
        if (builtin != null) {
            mv.visitLdcInsn(builtin);
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "isBuiltin", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        ScopeAnalysis.IsTarget isTarget = scopes.isTarget(ctx);
        if (isTarget != null) {
            String targetJvm = isTarget.kind() == ClassShape.Kind.INTERFACE
                    ? allInterfaceJvmNames.get(isTarget.typeId())
                    : allClassJvmNames.get(isTarget.typeId());
            if (targetJvm != null) {
                if (!isDefinitelyEvaluatedStaticTypeRef(ctx.right, ctx)) {
                    emitBoxed(ctx.right);
                    mv.visitInsn(POP);
                }
                line(ctx);
                mv.visitTypeInsn(INSTANCEOF, targetJvm);
                stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
                return null;
            }
        }
        emitBoxed(ctx.right);
        pushString(mv, ctx.right.getText());
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "is", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    private boolean isDefinitelyEvaluatedStaticTypeRef(BabbleParser.ExpressionContext rhs, ParserRuleContext useSite) {
        BabbleParser.SelectorContext root = rootStaticSelector(rhs);
        if (root == null) {
            return false;
        }
        if (resolution.variable(root) != null) {
            return false;
        }
        if (currentFile == null) {
            return false;
        }
        ParserRuleContext topStmt = topLevelStatementOf(useSite);
        if (topStmt == null) {
            return false;
        }
        int useStart = topStmt.getStart().getStartIndex();
        String rootName = root.ID().getText();
        for (BabbleParser.ExpressionContext stmt : currentFile.expression()) {
            if (stmt instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(rootName)) {
                if (def.getStop().getStopIndex() >= useStart) {
                    return false;
                }
            } else if (stmt instanceof BabbleParser.PackageExpressionContext pkg && pkg.name.getText().equals(rootName)) {
                if (pkg.getStop().getStopIndex() >= useStart) {
                    return false;
                }
            }
        }
        return true;
    }

    private BabbleParser.SelectorContext rootStaticSelector(BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext cur = expr;
        while (true) {
            while (cur instanceof BabbleParser.BlockExpressionContext b
                    && b.block().effectDeclaration().isEmpty()
                    && b.block().expression().size() == 1
                    && resolution.declared(b.block()).isEmpty()
                    && !scopes.opensScope(b.block())) {
                cur = b.block().expression(0);
            }
            if (!(cur instanceof BabbleParser.SelectorContext sel) || sel.safe != null) {
                return null;
            }
            if (sel.expression() == null) {
                return sel;
            }
            cur = sel.expression();
        }
    }

    private ParserRuleContext topLevelStatementOf(ParserRuleContext ctx) {
        for (ParserRuleContext cur = ctx; cur != null; cur = cur.getParent()) {
            if (cur.getParent() == currentFile) {
                return cur;
            }
        }
        return null;
    }

    @Override
    public Void visitAsExpression(BabbleParser.AsExpressionContext ctx) {
        String target = scopes.checkedType(ctx);
        if (target == null) {
            emitAndWidenIfMarked(ctx.left);
            return null;
        }
        emitBoxed(ctx.left);
        line(ctx);
        checkType(target);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
        // The analysis only lets super appear as the callee of a call.
        throw new IllegalStateException("super must be called directly");
    }

    private ClassGen findEnclosingOwnerClassGen(ParserRuleContext at, String ownerTypeId) {
        for (ParserRuleContext p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassShape shape = scopes.shape(p);
                if (shape != null && shape.id().equals(ownerTypeId)) {
                    return classGen(p);
                }
            }
        }
        return null;
    }

    /** {@code super.m(args)} : the default method the analysis resolved, called on {@code this}. */
    private void superCall(BabbleParser.CallContext ctx, BabbleParser.SelectorContext selector, BabbleParser.SuperExpressionContext superExpression) {
        String[] names = Interpreter.callNames(ctx);
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        ScopeAnalysis.SuperTargetInfo info = names == null ? scopes.superTargetInfo(superExpression) : null;
        if (info != null && info.directBabbleTypeId() != null && info.providerBabbleTypeId() != null) {
            String directJvm = allInterfaceJvmNames.get(info.directBabbleTypeId());
            ScopeAnalysis.TypeHierarchyInfo provHierarchy = scopes.typeHierarchy(info.providerBabbleTypeId());
            ScopeAnalysis.MemberSpec provSpec = provHierarchy != null
                    ? provHierarchy.interfaceMembers().get(info.memberName()) : null;
            ScopeAnalysis.FunctionSignature sig = provSpec != null && provSpec.callable()
                    ? provSpec.declaredSignature() : null;
            if (directJvm != null && sig != null && parameters.size() == sig.parameterKinds().length) {
                String desc = bridgeDescriptor(sig);
                if (interfaceHasMethod(info.directBabbleTypeId(), info.memberName(), desc)) {
                    if (!info.ownerIsInterface() && methodClass != null && !methodClass.arrayBacked
                            && methodClass.shape.id().equals(info.ownerTypeId())) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        for (int i = 0; i < parameters.size(); i++) {
                            emitAs(parameters.get(i).expression(), sig.parameterKinds()[i]);
                        }
                        line(ctx);
                        mv.visitMethodInsn(INVOKESPECIAL, directJvm, bridgeMethodName(info.memberName()), desc, true);
                        stackKind = sig.returnKind();
                        return;
                    }
                    if (info.ownerIsInterface()) {
                        InterfaceGen ownerIface = interfaceGensById.get(info.ownerTypeId());
                        if (ownerIface != null) {
                            String helper = ownerIface.superHelper(directJvm, info.memberName(), sig);
                            loadName(selector, "this");
                            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerIface.name);
                            mv.visitVarInsn(ALOAD, rtLocal);
                            for (int i = 0; i < parameters.size(); i++) {
                                emitAs(parameters.get(i).expression(), sig.parameterKinds()[i]);
                            }
                            line(ctx);
                            mv.visitMethodInsn(INVOKEINTERFACE, ownerIface.name, helper, desc, true);
                            stackKind = sig.returnKind();
                            return;
                        }
                    } else {
                        ClassGen ownerCls = findEnclosingOwnerClassGen(superExpression, info.ownerTypeId());
                        if (ownerCls != null) {
                            String helper = ownerCls.superHelper(directJvm, info.memberName(), sig);
                            loadName(selector, "this");
                            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerCls.name);
                            mv.visitVarInsn(ALOAD, rtLocal);
                            for (int i = 0; i < parameters.size(); i++) {
                                emitAs(parameters.get(i).expression(), sig.parameterKinds()[i]);
                            }
                            line(ctx);
                            mv.visitMethodInsn(INVOKEVIRTUAL, ownerCls.name, helper, desc, false);
                            stackKind = sig.returnKind();
                            return;
                        }
                    }
                }
            }
        }

        String site = callSiteField(ctx);
        loadName(selector, "this");
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        type(scopes.superTarget(superExpression), superExpression);
        mv.visitLdcInsn(selector.ID().getText());
        mv.visitVarInsn(ALOAD, rtLocal);
        arguments(parameters);
        if (names != null) {
            getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Supers", "call", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;L" + SCRIPT_RUNTIME
                + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void arguments(List<BabbleParser.CallParameterContext> parameters) {
        if (parameters.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        pushInt(mv, parameters.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < parameters.size(); i++) {
            mv.visitInsn(DUP);
            pushInt(mv, i);
            emitBoxed(parameters.get(i).expression());
            mv.visitInsn(AASTORE);
        }
    }

    @Override
    public Void visitIfExpression(BabbleParser.IfExpressionContext ctx) {
        ScopeAnalysis.PrimitiveKind target = scopes.expressionKind(ctx);
        Label otherwise = new Label();
        Label end = new Label();
        emitCondition(ctx.test, false, otherwise);
        emitAs(ctx.thenBlock, target);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(otherwise);
        if (ctx.elseBlock != null) {
            emitAs(ctx.elseBlock, target);
        } else if (ctx.elseIf != null) {
            emitAs(ctx.elseIf, target);
        } else {
            mv.visitInsn(ACONST_NULL);
            convertStack(ScopeAnalysis.PrimitiveKind.NONE, target);
        }
        mv.visitLabel(end);
        stackKind = target;
        return null;
    }

    @Override
    public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        ScopeAnalysis.DefTarget target = scopes.defTarget(ctx);
        if (target.kind() == ScopeAnalysis.DefKind.REQUIRED) {
            // A required interface member : a declaration only.
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        Resolution.Variable variable = resolution.variable(ctx);
        if (variable != null) {
            ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
            defValueAs(ctx, vk);
            storeVariable(variable, true, vk, ctx);
            return null;
        }
        if (target.kind() == ScopeAnalysis.DefKind.MEMBER && constructorClass != null) {
            int idx = target.index();
            ScopeAnalysis.PrimitiveKind mk = constructorClass.memberKinds[idx];
            if (mk.isPrimitive()) {
                int tmp = newLocal(mk);
                defValueAs(ctx, mk);
                storeLocal(mk, tmp);
                mv.visitVarInsn(ALOAD, typedThisLocal);
                loadLocal(mk, tmp);
                mv.visitFieldInsn(PUTFIELD, constructorClass.name, ctx.name.getText(), jvmDescriptor(mk));
                constructorClass.emitMarkDefined(mv, typedThisLocal, idx);
                loadLocal(mk, tmp);
                stackKind = mk;
                return null;
            }
            mv.visitVarInsn(ALOAD, typedThisLocal);
            defValueAs(ctx, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitInsn(DUP_X1);
            mv.visitFieldInsn(PUTFIELD, constructorClass.name, ctx.name.getText(), "Ljava/lang/Object;");
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        switch (target.kind()) {
            case MEMBER -> mv.visitVarInsn(ALOAD, INSTANCE);
            case STATIC -> mv.visitVarInsn(ALOAD, staticTarget);
            default -> mv.visitVarInsn(ALOAD, nsLocal);
        }
        if (target.kind() == ScopeAnalysis.DefKind.LEXICAL) {
            mv.visitLdcInsn(ctx.name.getText());
        } else {
            pushInt(mv, target.index());
        }
        defValueAs(ctx, ScopeAnalysis.PrimitiveKind.NONE);
        switch (target.kind()) {
            case LEXICAL -> mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "initialize", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            case MEMBER -> mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_INSTANCE, "initMember", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
            case STATIC -> mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "BabbleType", "initStatic", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
            default -> mv.visitMethodInsn(INVOKESTATIC, CLASSES, "setDefault", "(L" + NAMESPACE + ";ILjava/lang/Object;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    private void defValueAs(BabbleParser.DefExpressionContext ctx, ScopeAnalysis.PrimitiveKind desired) {
        if (ctx.value != null) {
            emitExpr(ctx.value);
        } else {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        convertStack(stackKind, desired);
    }

    /**
     * Checks the value on top of the stack against a cast target type ({@code as}) : it
     * stays there, an int widened for a double.
     */
    private void checkType(String descriptor) {
        String field = typeFields.computeIfAbsent(descriptor, d -> addConstant("TYPE", "L" + TYPES_TYPE + ";", 16, m -> {
            pushString(m, d);
            m.visitMethodInsn(INVOKESTATIC, TYPES, "parse", "(Ljava/lang/String;)L" + TYPES_TYPE + ";", false);
        }));
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + TYPES_TYPE + ";");
        mv.visitMethodInsn(INVOKESTATIC, TYPES, "check", "(Ljava/lang/Object;L" + TYPES_TYPE + ";)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /**
     * The result of a loop is the value of its last iteration, or null. It is kept in a
     * local rather than on the operand stack : HotSpot only compiles a running loop
     * (on-stack replacement) when the stack is empty at its head, and a function that runs
     * one long loop is otherwise left in the bytecode interpreter.
     */
    @Override
    public Void visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        ScopeAnalysis.PrimitiveKind bodyKind = scopes.widensToDouble(ctx.whileBlock)
                ? ScopeAnalysis.PrimitiveKind.DOUBLE : scopes.expressionKind(ctx.whileBlock);
        Label head = new Label();
        Label end = new Label();
        if (!bodyKind.isPrimitive()) {
            int result = newLocal();
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, result);
            mv.visitLabel(head);
            emitCondition(ctx.test, false, end);
            emitBoxed(ctx.whileBlock);
            mv.visitVarInsn(ASTORE, result);
            mv.visitJumpInsn(GOTO, head);
            mv.visitLabel(end);
            mv.visitVarInsn(ALOAD, result);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        int ran = newLocal(ScopeAnalysis.PrimitiveKind.BOOL);
        int result = newLocal(bodyKind);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, ran);
        pushDefaultZero(bodyKind);
        storeLocal(bodyKind, result);
        mv.visitLabel(head);
        emitCondition(ctx.test, false, end);
        emitAs(ctx.whileBlock, bodyKind);
        storeLocal(bodyKind, result);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, ran);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        Label pushNull = new Label();
        Label done = new Label();
        mv.visitVarInsn(ILOAD, ran);
        mv.visitJumpInsn(IFEQ, pushNull);
        loadLocal(bodyKind, result);
        box(bodyKind);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(pushNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitLabel(done);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        emitAndWidenIfMarked(ctx.expression());
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        convertStack(stackKind, methodReturnKind);
        mv.visitInsn(returnInsn(methodReturnKind));
        // Dead code, so that the code around a return in the middle of an expression can
        // be generated as if it were any other expression. ASM replaces it.
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
        emitExpr(ctx.block());
        if (scopes.widensToDouble(ctx.block())) {
            widenStackToDouble();
        }
        return null;
    }

    /**
     * A block that defines names switches to a child namespace with them declared. A
     * fresh one is created each time the block runs, on every iteration of a loop.
     */
    @Override
    public Void visitBlock(BabbleParser.BlockContext ctx) {
        if (!resolution.declared(ctx).isEmpty()) {
            // A frame block : its variables are locals, created afresh each time it runs.
            inFrameScope(() -> {
                declareVariables(ctx);
                statements(ctx.expression());
            });
            return null;
        }
        if (!scopes.opensScope(ctx)) {
            statements(ctx.expression());
            return null;
        }
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(scopes.declared(ctx));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(ctx.expression()));
        return null;
    }

    @Override
    public Void visitSelector(BabbleParser.SelectorContext ctx) {
        String name = ctx.ID().getText();
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression == null) {
            loadName(ctx, name);
            return null;
        }
        if (ctx.safe != null) {
            Label nonNull = new Label();
            Label end = new Label();
            emitBoxed(expression);
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, nonNull);
            mv.visitInsn(POP);
            mv.visitInsn(ACONST_NULL);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(nonNull);
            selectReceiverMember(ctx, expression, name);
            String checked = scopes.checkedType(ctx);
            if (checked != null) {
                line(ctx);
                checkType(checked);
            }
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        Class<?> arrayClass = scopes.arrayLengthClass(ctx);
        if (arrayClass != null) {
            emitCheckedJavaReceiver(expression, ctx, true, Type.getInternalName(arrayClass), "notAScope");
            mv.visitInsn(ARRAYLENGTH);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            checkSelectorType(ctx);
            return null;
        }
        ScopeAnalysis.JavaFieldTarget javaField = scopes.javaFieldTarget(ctx);
        if (javaField != null) {
            java.lang.reflect.Field field = javaField.field();
            String owner = Type.getInternalName(field.getDeclaringClass());
            if (javaField.isStatic()) {
                if (!isPureStaticJavaChain(expression)) {
                    emitCheckedJavaReceiver(expression, ctx, false, OBJECT, "notAScope");
                    mv.visitInsn(POP);
                }
                line(ctx);
                mv.visitFieldInsn(GETSTATIC, owner, field.getName(), Type.getDescriptor(field.getType()));
            } else {
                emitCheckedJavaReceiver(expression, ctx, javaField.unwrapReceiver(), owner, "notAScope");
                line(ctx);
                mv.visitFieldInsn(GETFIELD, owner, field.getName(), Type.getDescriptor(field.getType()));
            }
            emitJavaValueToBabbleStack(field.getType());
            checkSelectorType(ctx);
            return null;
        }
        ScopeAnalysis.ModuleExportTarget exportTarget = scopes.moduleExportTarget(ctx);
        if (exportTarget != null) {
            emitBoxed(expression);
            mv.visitTypeInsn(CHECKCAST, RUNTIME + "Module");
            pushInt(mv, exportTarget.index());
            line(ctx);
            mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "Module", "exportAt", "(I)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            checkSelectorType(ctx);
            return null;
        }
        if (expression instanceof BabbleParser.ThisExpressionContext) {
            ClassGen gen = thisClassAt(expression);
            int idx = fieldIndex(gen, name);
            if (idx >= 0) {
                ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
                loadThisAs(expression, gen);
                if (mk.isPrimitive()) {
                    if (gen.primDefBits[idx] >= 0) {
                        int recv = newLocal();
                        mv.visitVarInsn(ASTORE, recv);
                        gen.emitCheckDefined(mv, recv, idx, name, ctx);
                        mv.visitVarInsn(ALOAD, recv);
                    }
                    mv.visitFieldInsn(GETFIELD, gen.name, name, jvmDescriptor(mk));
                    stackKind = mk;
                    checkSelectorType(ctx);
                    return null;
                }
                // A field of this compiled class : read directly.
                mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
                mv.visitLdcInsn(name);
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            } else {
                emitBoxed(expression);
                mv.visitLdcInsn(name);
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            }
        } else {
            emitBoxed(expression);
            selectReceiverMember(ctx, expression, name);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        checkSelectorType(ctx);
        return null;
    }

    private void checkSelectorType(BabbleParser.SelectorContext ctx) {
        String checked = scopes.checkedType(ctx);
        if (checked != null) {
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(ctx);
            checkType(checked);
        }
    }

    private void selectReceiverMember(BabbleParser.SelectorContext ctx, BabbleParser.ExpressionContext expression, String name) {
        line(ctx);
        String text = expression.getText();
        if (text.length() <= MAX_STRING_CONSTANT) {
            // An invokedynamic site, cached by receiver (Bootstrap.select).
            mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, name, text);
        } else {
            mv.visitLdcInsn(name);
            pushString(mv, text);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    @Override
    public Void visitCall(BabbleParser.CallContext ctx) {
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector && selector.safe != null) {
            Label nonNull = new Label();
            Label end = new Label();
            BabbleParser.ExpressionContext receiver = selector.expression();
            emitBoxed(receiver);
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, nonNull);
            mv.visitInsn(POP);
            mv.visitInsn(ACONST_NULL);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(nonNull);
            selectReceiverMember(selector, receiver, selector.ID().getText());
            String selectorChecked = scopes.checkedType(selector);
            if (selectorChecked != null) {
                line(selector);
                checkType(selectorChecked);
            }
            emitCallWithCallee(ctx, callSiteField(ctx), Interpreter.callNames(ctx), ctx.callParameters().callParameter());
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            String checked = scopes.checkedType(ctx);
            if (checked != null) {
                line(ctx);
                checkType(checked);
            }
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        emitCall(ctx);
        String checked = scopes.checkedType(ctx);
        if (checked != null) {
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(ctx);
            checkType(checked);
        }
        return null;
    }

    private void emitCall(BabbleParser.CallContext ctx) {
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.SuperExpressionContext superExpression) {
            superCall(ctx, selector, superExpression);
            return;
        }
        String[] names = Interpreter.callNames(ctx);
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();

        // Direct self-recursion via `recurse(...)`
        if (ctx.expression() instanceof BabbleParser.RecurseContext recurseCtx && names == null
                && methodOwner instanceof BabbleParser.FunctionLiteralContext fnLit) {
            Resolution.Variable v = resolution.variable(recurseCtx);
            if (v != null && v.method() == methodOwner) {
                List<BabbleParser.ParameterDeclarationContext> decls = fnLit.parametersDeclaration().parameterDeclaration();
                int count = decls.size();
                if (hasSpec(fnLit) && parameters.size() == count) {
                    if (methodClass != null) {
                        String mName = ((BabbleParser.DefExpressionContext) fnLit.getParent()).name.getText();
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, selfLocal);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        for (int i = 0; i < count; i++) {
                            emitAs(parameters.get(i).expression(), scopes.declarationKind(decls.get(i)));
                        }
                        line(ctx);
                        mv.visitMethodInsn(INVOKEVIRTUAL, methodClass.name, methodClass.methodName(mName) + "$spec",
                                methodSpecDescriptor(fnLit), false);
                    } else {
                        mv.visitVarInsn(ALOAD, selfLocal);
                        mv.visitVarInsn(ALOAD, thizLocal);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        for (int i = 0; i < count; i++) {
                            emitAs(parameters.get(i).expression(), scopes.declarationKind(decls.get(i)));
                        }
                        line(ctx);
                        mv.visitMethodInsn(INVOKESTATIC, className, functionMethodName(fnLit) + "$spec",
                                functionSpecDescriptor(fnLit), false);
                    }
                    stackKind = scopes.functionReturnKind(fnLit);
                    return;
                }
                if (isSpread(fnLit) && parameters.size() <= count) {
                    if (methodClass != null) {
                        String mName = ((BabbleParser.DefExpressionContext) fnLit.getParent()).name.getText();
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, selfLocal);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        mv.visitInsn(ACONST_NULL);
                        for (BabbleParser.CallParameterContext parameter : parameters) {
                            emitBoxed(parameter.expression());
                        }
                        for (int i = parameters.size(); i < count; i++) {
                            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                        }
                        line(ctx);
                        mv.visitMethodInsn(INVOKEVIRTUAL, methodClass.name, methodClass.methodName(mName),
                                methodDirectDescriptor(fnLit), false);
                    } else {
                        mv.visitVarInsn(ALOAD, selfLocal);
                        mv.visitVarInsn(ALOAD, thizLocal);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        mv.visitInsn(ACONST_NULL);
                        for (BabbleParser.CallParameterContext parameter : parameters) {
                            emitBoxed(parameter.expression());
                        }
                        for (int i = parameters.size(); i < count; i++) {
                            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                        }
                        line(ctx);
                        mv.visitMethodInsn(INVOKESTATIC, className, functionMethodName(fnLit),
                                functionDirectDescriptor(fnLit), false);
                    }
                    stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                    return;
                }
            }
        }

        // Direct free Babble function call resolved statically to a FunctionLiteralContext
        BabbleParser.FunctionLiteralContext targetFn = scopes.babbleCallFunction(ctx);
        if (targetFn != null && names == null && parameters.size() == scopes.parameters(targetFn).length) {
            List<BabbleParser.ParameterDeclarationContext> decls = targetFn.parametersDeclaration().parameterDeclaration();
            boolean spec = hasSpec(targetFn);
            if (spec || isSpread(targetFn)) {
                emitBoxed(ctx.expression());
                mv.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
                mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNBOUND", "L" + RUNTIME + "Undefined;");
                mv.visitVarInsn(ALOAD, rtLocal);
                String fnMethod = functionMethodName(targetFn);
                if (spec) {
                    for (int i = 0; i < parameters.size(); i++) {
                        emitAs(parameters.get(i).expression(), scopes.declarationKind(decls.get(i)));
                    }
                    line(ctx);
                    mv.visitMethodInsn(INVOKESTATIC, className, fnMethod + "$spec", functionSpecDescriptor(targetFn), false);
                    stackKind = scopes.functionReturnKind(targetFn);
                } else {
                    mv.visitInsn(ACONST_NULL);
                    for (BabbleParser.CallParameterContext parameter : parameters) {
                        emitBoxed(parameter.expression());
                    }
                    line(ctx);
                    mv.visitMethodInsn(INVOKESTATIC, className, fnMethod, functionDirectDescriptor(targetFn), false);
                    stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                }
                return;
            }
        }

        String site = callSiteField(ctx);

        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.ThisExpressionContext thisExpression) {
            ClassGen gen = thisClassAt(thisExpression);
            String member = selector.ID().getText();
            int index = gen != null ? gen.shape.memberIndex(member) : -1;
            if (index >= 0 && gen.shape.isMethod(index)) {
                // A method of this compiled class : a direct call of its JVM method, once its def has run.
                BabbleParser.FunctionLiteralContext literal = gen.methodLiteral(member);
                List<BabbleParser.ParameterDeclarationContext> decls = literal.parametersDeclaration().parameterDeclaration();
                loadThisAs(thisExpression, gen);
                mv.visitInsn(DUP);
                mv.visitFieldInsn(GETFIELD, gen.name, member, "Ljava/lang/Object;");
                mv.visitLdcInsn(member);
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
                mv.visitVarInsn(ALOAD, rtLocal);
                int count = scopes.parameters(literal).length;
                if (hasSpec(literal) && names == null && parameters.size() == count) {
                    for (int i = 0; i < count; i++) {
                        emitAs(parameters.get(i).expression(), scopes.declarationKind(decls.get(i)));
                    }
                    line(ctx);
                    mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, gen.methodName(member) + "$spec",
                            methodSpecDescriptor(literal), false);
                    stackKind = scopes.functionReturnKind(literal);
                    return;
                }
                if (isSpread(literal) && names == null && parameters.size() <= count) {
                    // By position, with no more arguments than parameters : the direct entry point.
                    mv.visitInsn(ACONST_NULL);
                    for (BabbleParser.CallParameterContext parameter : parameters) {
                        emitBoxed(parameter.expression());
                    }
                    for (int i = parameters.size(); i < count; i++) {
                        mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                    }
                    line(ctx);
                    mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, gen.methodName(member), methodDirectDescriptor(literal), false);
                    stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                    return;
                }
                arguments(parameters);
                if (names != null) {
                    getStringArray(names);
                } else {
                    mv.visitInsn(ACONST_NULL);
                }
                mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
                line(ctx);
                mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, gen.methodName(member) + (isSpread(literal) ? "$call" : ""), METHOD_DESCRIPTOR, false);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
        }

        ScopeAnalysis.InterfaceCallTarget ifaceCall = names == null ? scopes.interfaceCallTarget(ctx) : null;
        if (ifaceCall != null) {
            String ifaceJvm = allInterfaceJvmNames.get(ifaceCall.interfaceTypeId());
            ScopeAnalysis.FunctionSignature sig = ifaceCall.signature();
            if (ifaceJvm != null && sig != null && parameters.size() == sig.parameterKinds().length) {
                emitInterfaceMethodCall(ctx, (BabbleParser.SelectorContext) ctx.expression(), ifaceJvm, ifaceCall.memberName(), sig, parameters);
                return;
            }
        }

        ScopeAnalysis.JavaConstructorTarget javaCtor = scopes.javaConstructorTarget(ctx);
        if (javaCtor != null) {
            emitJavaConstructorCall(ctx, javaCtor, parameters);
            return;
        }
        ScopeAnalysis.JavaCallTarget javaCall = scopes.javaCallTarget(ctx);
        if (javaCall != null) {
            emitJavaMethodCall(ctx, javaCall, parameters);
            return;
        }

        emitBoxed(ctx.expression());
        emitCallWithCallee(ctx, site, names, parameters);
    }

    private void emitInterfaceMethodCall(BabbleParser.CallContext ctx, BabbleParser.SelectorContext selector,
                                         String ifaceJvm, String memberName, ScopeAnalysis.FunctionSignature sig,
                                         List<BabbleParser.CallParameterContext> parameters) {
        BabbleParser.ExpressionContext recvExpr = selector.expression();
        emitBoxed(recvExpr);
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        line(selector);
        pushString(mv, recvExpr.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        mv.visitTypeInsn(CHECKCAST, ifaceJvm);
        mv.visitVarInsn(ALOAD, rtLocal);
        for (int i = 0; i < parameters.size(); i++) {
            emitAs(parameters.get(i).expression(), sig.parameterKinds()[i]);
        }
        line(ctx);
        mv.visitMethodInsn(INVOKEINTERFACE, ifaceJvm, bridgeMethodName(memberName), bridgeDescriptor(sig), true);
        stackKind = sig.returnKind();
    }

    private void emitJavaConstructorCall(BabbleParser.CallContext ctx, ScopeAnalysis.JavaConstructorTarget target,
                                         List<BabbleParser.CallParameterContext> parameters) {
        if (!isPureStaticJavaChain(ctx.expression())) {
            emitCheckedJavaReceiver(ctx.expression(), ctx, false, OBJECT, "notAScope");
            mv.visitInsn(POP);
        }
        java.lang.reflect.Constructor<?> ctor = target.constructor();
        Class<?>[] paramTypes = ctor.getParameterTypes();
        int[] argLocals = new int[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            argLocals[i] = emitJavaArgToLocal(parameters.get(i).expression(), paramTypes[i], ctx);
        }
        String internalClass = Type.getInternalName(ctor.getDeclaringClass());
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
        line(ctx);
        mv.visitTypeInsn(NEW, internalClass);
        mv.visitInsn(DUP);
        for (int i = 0; i < paramTypes.length; i++) {
            loadJavaLocal(paramTypes[i], argLocals[i]);
        }
        mv.visitMethodInsn(INVOKESPECIAL, internalClass, "<init>", Type.getConstructorDescriptor(ctor), false);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_OBJECT, "of", "(Ljava/lang/Object;)L" + JAVA_OBJECT + ";", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(catchHandler);
        int caught = newLocal();
        mv.visitVarInsn(ASTORE, caught);
        line(ctx);
        mv.visitVarInsn(ALOAD, caught);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "wrapInvocationException", "(Ljava/lang/Throwable;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(done);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitJavaMethodCall(BabbleParser.CallContext ctx, ScopeAnalysis.JavaCallTarget target,
                                    List<BabbleParser.CallParameterContext> parameters) {
        BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
        BabbleParser.ExpressionContext receiverExpr = selector.expression();
        java.lang.reflect.Method method = target.method();
        String owner = Type.getInternalName(method.getDeclaringClass());
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!isPureStaticJavaChain(receiverExpr)) {
                emitCheckedJavaReceiver(receiverExpr, selector, false, OBJECT, "notAScope");
                mv.visitInsn(POP);
            }
        } else {
            emitCheckedJavaReceiver(receiverExpr, selector, target.unwrapReceiver(), owner, "notAScope", method.getDeclaringClass());
            recvLocal = newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        int[] argLocals = new int[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            argLocals[i] = emitJavaArgToLocal(parameters.get(i).expression(), paramTypes[i], ctx);
        }
        boolean itf = method.getDeclaringClass().isInterface();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
        line(ctx);
        if (!target.isStatic()) {
            mv.visitVarInsn(ALOAD, recvLocal);
        }
        for (int i = 0; i < paramTypes.length; i++) {
            loadJavaLocal(paramTypes[i], argLocals[i]);
        }
        mv.visitMethodInsn(target.isStatic() ? INVOKESTATIC : (itf ? INVOKEINTERFACE : INVOKEVIRTUAL),
                owner, method.getName(), Type.getMethodDescriptor(method), itf);
        emitJavaValueToBabbleStack(method.getReturnType());
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(catchHandler);
        int caught = newLocal();
        mv.visitVarInsn(ASTORE, caught);
        line(ctx);
        mv.visitVarInsn(ALOAD, caught);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "wrapInvocationException", "(Ljava/lang/Throwable;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(done);
    }

    private boolean isPureStaticJavaChain(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1
                && resolution.declared(b.block()).isEmpty()
                && !scopes.opensScope(b.block())) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.safe != null) {
                return false;
            }
            if (sel.expression() != null) {
                return isPureStaticJavaChain(sel.expression());
            }
            String name = sel.ID().getText();
            if (("java".equals(name) || "javax".equals(name)) && resolution.variable(sel) == null) {
                return true;
            }
            Resolution.Variable v = resolution.variable(sel);
            return v != null && v.declaration() != null && scopes.isDefinitelyDefined(v.declaration());
        }
        return false;
    }

    private void emitCheckedJavaReceiver(BabbleParser.ExpressionContext recvExpr, ParserRuleContext lineAt,
                                         boolean unwrap, String internalTargetClass, String nullErrorFactory) {
        emitCheckedJavaReceiver(recvExpr, lineAt, unwrap, internalTargetClass, nullErrorFactory, null);
    }

    private void emitCheckedJavaReceiver(BabbleParser.ExpressionContext recvExpr, ParserRuleContext lineAt,
                                         boolean unwrap, String internalTargetClass, String nullErrorFactory,
                                         Class<?> unwrapTargetClass) {
        emitBoxed(recvExpr);
        if (unwrap) {
            if (unwrapTargetClass != null && unwrapTargetClass.isInterface()) {
                mv.visitLdcInsn(Type.getType(unwrapTargetClass));
                mv.visitVarInsn(ALOAD, rtLocal);
                line(lineAt);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava",
                        "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
        }
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        line(lineAt);
        pushString(mv, recvExpr.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", nullErrorFactory, "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        if (!OBJECT.equals(internalTargetClass)) {
            mv.visitTypeInsn(CHECKCAST, internalTargetClass);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private int emitJavaArgToLocal(BabbleParser.ExpressionContext argExpr, Class<?> paramType, ParserRuleContext lineAt) {
        if (paramType == int.class) {
            emitAs(argExpr, ScopeAnalysis.PrimitiveKind.INT);
            int slot = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, slot);
            return slot;
        }
        if (paramType == boolean.class) {
            emitAs(argExpr, ScopeAnalysis.PrimitiveKind.BOOL);
            int slot = newLocal(ScopeAnalysis.PrimitiveKind.BOOL);
            mv.visitVarInsn(ISTORE, slot);
            return slot;
        }
        if (paramType == double.class) {
            emitAs(argExpr, ScopeAnalysis.PrimitiveKind.DOUBLE);
            int slot = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, slot);
            return slot;
        }
        if (paramType == long.class) {
            emitAs(argExpr, ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(I2L);
            int slot = nextLocal;
            nextLocal += 2;
            mv.visitVarInsn(LSTORE, slot);
            return slot;
        }
        if (paramType == float.class) {
            emitAs(argExpr, ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(I2F);
            int slot = newLocal();
            mv.visitVarInsn(FSTORE, slot);
            return slot;
        }
        ScopeAnalysis.PrimitiveKind argKind = scopes.expressionKind(argExpr);
        emitBoxed(argExpr);
        if (!argKind.isPrimitive()) {
            mv.visitLdcInsn(Type.getType(paramType));
            mv.visitVarInsn(ALOAD, rtLocal);
            line(lineAt);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        }
        if (paramType != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(paramType));
        }
        int slot = newLocal();
        mv.visitVarInsn(ASTORE, slot);
        return slot;
    }

    private void loadJavaLocal(Class<?> paramType, int slot) {
        if (paramType == int.class || paramType == boolean.class) {
            mv.visitVarInsn(ILOAD, slot);
        } else if (paramType == double.class) {
            mv.visitVarInsn(DLOAD, slot);
        } else if (paramType == long.class) {
            mv.visitVarInsn(LLOAD, slot);
        } else if (paramType == float.class) {
            mv.visitVarInsn(FLOAD, slot);
        } else {
            mv.visitVarInsn(ALOAD, slot);
        }
    }

    private void emitJavaValueToBabbleStack(Class<?> type) {
        if (type == void.class) {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == int.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
        } else if (type == double.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
        } else if (type == boolean.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        } else if (type == byte.class) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == short.class) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == char.class) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == long.class) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == float.class) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (type == Integer.class) {
            mv.visitLdcInsn(Type.getType("Ljava/lang/Integer;"));
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkBoxed", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
            unbox(ScopeAnalysis.PrimitiveKind.INT);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
        } else if (type == Double.class) {
            mv.visitLdcInsn(Type.getType("Ljava/lang/Double;"));
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkBoxed", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
            unbox(ScopeAnalysis.PrimitiveKind.DOUBLE);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
        } else if (type == Boolean.class) {
            mv.visitLdcInsn(Type.getType("Ljava/lang/Boolean;"));
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkBoxed", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
            unbox(ScopeAnalysis.PrimitiveKind.BOOL);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        } else {
            if (type == Object.class || type.isInterface()) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
    }

    private void emitCallWithCallee(BabbleParser.CallContext ctx, String site, String[] names,
                                    List<BabbleParser.CallParameterContext> parameters) {
        // Checked before the arguments are evaluated.
        line(ctx);
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);

        mv.visitVarInsn(ALOAD, rtLocal);
        ScopeAnalysis.FunctionSignature sig = names == null ? scopes.babbleCallSignature(ctx) : null;
        if (sig != null && parameters.size() == sig.parameterKinds().length) {
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            StringBuilder desc = new StringBuilder("(L").append(CALLABLE).append(";L").append(SCRIPT_RUNTIME)
                    .append(";L").append(CALL_SITE).append(';');
            for (int i = 0; i < parameters.size(); i++) {
                ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[i];
                emitAs(parameters.get(i).expression(), pk);
                desc.append(jvmDescriptor(pk));
            }
            desc.append(')').append(jvmDescriptor(sig.returnKind()));
            line(ctx);
            mv.visitInvokeDynamicInsn("typedCall", desc.toString(), TYPED_CALL_BOOTSTRAP);
            stackKind = sig.returnKind();
            return;
        }
        if (parameters.size() <= MAX_SPREAD) {
            // An invokedynamic site, the arguments spread (Bootstrap.call).
            if (names != null) {
                getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            for (BabbleParser.CallParameterContext parameter : parameters) {
                emitBoxed(parameter.expression());
            }
            line(ctx);
            mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                    + "Ljava/lang/Object;".repeat(parameters.size()) + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        arguments(parameters);
        if (names != null) {
            getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(ctx);
        mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    @Override
    public Void visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        Label isTrue = new Label();
        Label end = new Label();
        emitCondition(ctx.expression(), false, isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        return null;
    }

    private void emitCondition(BabbleParser.ExpressionContext expr, boolean branchIfTrue, Label target) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1
                && resolution.declared(b.block()).isEmpty()
                && !scopes.opensScope(b.block())) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.BooleanNotContext not) {
            emitCondition(not.expression(), !branchIfTrue, target);
            return;
        }
        if (expr instanceof BabbleParser.BooleanOpContext boolOp) {
            boolean or = boolOp.op.getType() == BabbleLexer.OR;
            if (or == branchIfTrue) {
                emitCondition(boolOp.left, branchIfTrue, target);
                emitCondition(boolOp.right, branchIfTrue, target);
            } else {
                Label fallThrough = new Label();
                emitCondition(boolOp.left, !branchIfTrue, fallThrough);
                emitCondition(boolOp.right, branchIfTrue, target);
                mv.visitLabel(fallThrough);
            }
            return;
        }
        if (expr instanceof BabbleParser.BinaryOpContext binOp
                && isComparison(binOp.op.getType()) && canLowerComparison(binOp)) {
            emitComparison(binOp, branchIfTrue, target);
            return;
        }
        emitExpr(expr);
        switch (stackKind) {
            case BOOL, INT -> mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            case DOUBLE -> {
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case NONE -> {
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
        }
    }

    private static boolean isComparison(int opType) {
        return switch (opType) {
            case BabbleLexer.LT, BabbleLexer.LTE, BabbleLexer.EQ, BabbleLexer.NEQ, BabbleLexer.GTE, BabbleLexer.GT -> true;
            default -> false;
        };
    }

    private boolean canLowerComparison(BabbleParser.BinaryOpContext ctx) {
        ScopeAnalysis.PrimitiveKind lk = scopes.expressionKind(ctx.left);
        ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.right);
        if (lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL) {
            return ctx.op.getType() == BabbleLexer.EQ || ctx.op.getType() == BabbleLexer.NEQ;
        }
        return (lk == ScopeAnalysis.PrimitiveKind.INT || lk == ScopeAnalysis.PrimitiveKind.DOUBLE)
                && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE);
    }

    private void emitComparison(BabbleParser.BinaryOpContext ctx, boolean branchIfTrue, Label target) {
        ScopeAnalysis.PrimitiveKind lk = scopes.expressionKind(ctx.left);
        ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.right);
        int opType = ctx.op.getType();
        if (lk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT
                || lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL) {
            emitAs(ctx.left, lk);
            emitAs(ctx.right, rk);
            int jump = switch (opType) {
                case BabbleLexer.LT -> branchIfTrue ? IF_ICMPLT : IF_ICMPGE;
                case BabbleLexer.LTE -> branchIfTrue ? IF_ICMPLE : IF_ICMPGT;
                case BabbleLexer.EQ -> branchIfTrue ? IF_ICMPEQ : IF_ICMPNE;
                case BabbleLexer.NEQ -> branchIfTrue ? IF_ICMPNE : IF_ICMPEQ;
                case BabbleLexer.GTE -> branchIfTrue ? IF_ICMPGE : IF_ICMPLT;
                case BabbleLexer.GT -> branchIfTrue ? IF_ICMPGT : IF_ICMPLE;
                default -> throw new IllegalStateException();
            };
            mv.visitJumpInsn(jump, target);
            return;
        }
        emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.DOUBLE);
        emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.DOUBLE);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
        int jump = switch (opType) {
            case BabbleLexer.LT -> branchIfTrue ? IFLT : IFGE;
            case BabbleLexer.LTE -> branchIfTrue ? IFLE : IFGT;
            case BabbleLexer.EQ -> branchIfTrue ? IFEQ : IFNE;
            case BabbleLexer.NEQ -> branchIfTrue ? IFNE : IFEQ;
            case BabbleLexer.GTE -> branchIfTrue ? IFGE : IFLT;
            case BabbleLexer.GT -> branchIfTrue ? IFGT : IFLE;
            default -> throw new IllegalStateException();
        };
        mv.visitJumpInsn(jump, target);
    }

    private Integer evalIntConstant(BabbleParser.ExpressionContext expr) {
        if (scopes.expressionKind(expr) != ScopeAnalysis.PrimitiveKind.INT) {
            return null;
        }
        if (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1
                && resolution.declared(b.block()).isEmpty()
                && !scopes.opensScope(b.block())) {
            return evalIntConstant(b.block().expression(0));
        }
        if (expr instanceof BabbleParser.IntegerContext lit) {
            try {
                return Integer.parseInt(lit.getText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.NegateContext neg) {
            if (neg.operand instanceof BabbleParser.IntegerContext lit) {
                try {
                    return Integer.parseInt("-" + lit.getText());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            Integer val = evalIntConstant(neg.operand);
            return val != null ? -val : null;
        }
        if (expr instanceof BabbleParser.BinaryOpContext bin
                && scopes.expressionKind(bin.left) == ScopeAnalysis.PrimitiveKind.INT
                && scopes.expressionKind(bin.right) == ScopeAnalysis.PrimitiveKind.INT) {
            Integer l = evalIntConstant(bin.left);
            Integer r = evalIntConstant(bin.right);
            if (l != null && r != null) {
                return switch (bin.op.getType()) {
                    case BabbleLexer.PLUS -> l + r;
                    case BabbleLexer.MINUS -> l - r;
                    case BabbleLexer.MUL -> l * r;
                    default -> null;
                };
            }
        }
        return null;
    }

    @Override
    public Void visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        int opType = ctx.op.getType();
        if (isComparison(opType) && canLowerComparison(ctx)) {
            Label isTrue = new Label();
            Label end = new Label();
            emitComparison(ctx, true, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            return null;
        }

        ScopeAnalysis.PrimitiveKind resultKind = scopes.expressionKind(ctx);
        ScopeAnalysis.PrimitiveKind lk = scopes.expressionKind(ctx.left);
        ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.right);
        if (resultKind == ScopeAnalysis.PrimitiveKind.INT
                && lk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT
                && (opType == BabbleLexer.PLUS || opType == BabbleLexer.MINUS || opType == BabbleLexer.MUL)) {
            Integer lc = evalIntConstant(ctx.left);
            Integer rc = evalIntConstant(ctx.right);
            if (lc != null && rc != null) {
                int val = switch (opType) {
                    case BabbleLexer.PLUS -> lc + rc;
                    case BabbleLexer.MINUS -> lc - rc;
                    case BabbleLexer.MUL -> lc * rc;
                    default -> throw new IllegalStateException();
                };
                pushInt(mv, val);
                stackKind = ScopeAnalysis.PrimitiveKind.INT;
                return null;
            }
            if (opType == BabbleLexer.PLUS) {
                if (rc != null && rc == 0) {
                    emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.INT);
                    return null;
                }
                if (lc != null && lc == 0) {
                    emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.INT);
                    return null;
                }
            } else if (opType == BabbleLexer.MINUS) {
                if (rc != null && rc == 0) {
                    emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.INT);
                    return null;
                }
                if (lc != null && lc == 0) {
                    emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitInsn(INEG);
                    stackKind = ScopeAnalysis.PrimitiveKind.INT;
                    return null;
                }
            } else if (opType == BabbleLexer.MUL) {
                if (rc != null && rc == 1) {
                    emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.INT);
                    return null;
                }
                if (lc != null && lc == 1) {
                    emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.INT);
                    return null;
                }
                if (rc != null && rc == 0) {
                    emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitInsn(POP);
                    mv.visitInsn(ICONST_0);
                    stackKind = ScopeAnalysis.PrimitiveKind.INT;
                    return null;
                }
                if (lc != null && lc == 0) {
                    emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitInsn(POP);
                    mv.visitInsn(ICONST_0);
                    stackKind = ScopeAnalysis.PrimitiveKind.INT;
                    return null;
                }
            }
            emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.INT);
            emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(switch (opType) {
                case BabbleLexer.PLUS -> IADD;
                case BabbleLexer.MINUS -> ISUB;
                case BabbleLexer.MUL -> IMUL;
                default -> throw new IllegalStateException();
            });
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return null;
        }

        if (resultKind == ScopeAnalysis.PrimitiveKind.DOUBLE
                && (lk == ScopeAnalysis.PrimitiveKind.INT || lk == ScopeAnalysis.PrimitiveKind.DOUBLE)
                && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)
                && (opType == BabbleLexer.PLUS || opType == BabbleLexer.MINUS || opType == BabbleLexer.MUL || opType == BabbleLexer.DIV)) {
            emitAs(ctx.left, ScopeAnalysis.PrimitiveKind.DOUBLE);
            emitAs(ctx.right, ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitInsn(switch (opType) {
                case BabbleLexer.PLUS -> DADD;
                case BabbleLexer.MINUS -> DSUB;
                case BabbleLexer.MUL -> DMUL;
                case BabbleLexer.DIV -> DDIV;
                default -> throw new IllegalStateException();
            });
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return null;
        }

        String method = switch (opType) {
            case BabbleLexer.MUL -> "mul";
            case BabbleLexer.DIV -> "div";
            case BabbleLexer.PLUS -> "add";
            case BabbleLexer.MINUS -> "sub";
            case BabbleLexer.LT -> "lt";
            case BabbleLexer.LTE -> "le";
            case BabbleLexer.EQ -> "eq";
            case BabbleLexer.NEQ -> "ne";
            case BabbleLexer.GTE -> "ge";
            case BabbleLexer.GT -> "gt";
            default -> throw new IllegalStateException("Bad op : " + ctx.op.getText());
        };
        String operatorMethod = Operators.method(ctx.op.getText());
        if (operatorMethod == null) {
            emitBoxed(ctx.left);
            emitBoxed(ctx.right);
            mv.visitFieldInsn(GETSTATIC, className, opSiteField(ctx), "L" + OP_SITE + ";");
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        int left = newLocal();
        int right = newLocal();
        emitBoxed(ctx.left);
        mv.visitVarInsn(ASTORE, left);
        emitBoxed(ctx.right);
        mv.visitVarInsn(ASTORE, right);
        arithmetic(ctx, ctx.op.getText(), ctx.left.getText(), ctx.right.getText(), left, right);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /**
     * Arithmetic on two operands already in their locals, given the operator and their source
     * texts : on a Babble instance, its operator method ; on anything else, Operators.
     */
    private void arithmetic(ParserRuleContext ctx, String operator, String leftText, String rightText, int left, int right) {
        String method = switch (operator) {
            case "*" -> "mul";
            case "/" -> "div";
            case "+" -> "add";
            case "-" -> "sub";
            default -> throw new IllegalStateException("Bad op : " + operator);
        };
        Label numbers = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, left);
        mv.visitTypeInsn(INSTANCEOF, RUNTIME + "BabbleInstance");
        mv.visitJumpInsn(IFEQ, numbers);
        operatorCall(ctx, leftText, Operators.method(operator), rightText, left, right);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(numbers);
        mv.visitVarInsn(ALOAD, left);
        mv.visitVarInsn(ALOAD, right);
        mv.visitFieldInsn(GETSTATIC, className, opSiteField(leftText, rightText), "L" + OP_SITE + ";");
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /**
     * {@code x op= v}, with the target's current value already in its local : evaluates
     * {@code v}, then leaves {@code x op v} on the stack.
     */
    private void compound(BabbleParser.AssignExpressionContext ctx, String operator, int current) {
        int value = newLocal();
        emitBoxed(ctx.value);
        mv.visitVarInsn(ASTORE, value);
        arithmetic(ctx, operator, Interpreter.targetText(ctx), ctx.value.getText(), current, value);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    /**
     * {@code and} and {@code or} short-circuit, and yield a boolean.
     */
    @Override
    public Void visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        Label isTrue = new Label();
        Label end = new Label();
        emitCondition(ctx, true, isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        return null;
    }

    @Override
    public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        ClassGen owner = methodClassOf(ctx);
        if (owner != null) {
            mv.visitFieldInsn(GETSTATIC, className, compiledMethod(ctx, owner), "L" + COMPILED_METHOD + ";");
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        String name = ScopeAnalysis.functionName(ctx);
        String method = functionMethodName(ctx);
        String paramsField = stringArrayField(scopes.parameters(ctx));
        if (scheduledFunctions.add(ctx)) {
            pendingMethods.add(() -> generateFunction(ctx, method, paramsField));
        }

        boolean spread = isSpread(ctx);
        boolean spec = hasSpec(ctx);
        Type bodyType = Type.getMethodType(BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("invoke", "()L" + FUNCTION_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, spread ? method + "$call" : method, BODY_DESCRIPTOR, false), bodyType);
        if (spread) {
            mv.visitLdcInsn(new Handle(H_INVOKESTATIC, className, method, functionDirectDescriptor(ctx), false));
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        if (spec) {
            mv.visitLdcInsn(new Handle(H_INVOKESTATIC, className, method + "$spec", functionSpecDescriptor(ctx), false));
        }
        mv.visitVarInsn(ALOAD, nsLocal);
        capturedCells(ctx);
        mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        pushInt(mv, scopes.requiredParameters(ctx));
        if (name != null) {
            mv.visitLdcInsn(name);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        String createDesc = spec
                ? "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L" + COMPILED_FUNCTION + ";"
                : "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L" + COMPILED_FUNCTION + ";";
        mv.visitMethodInsn(INVOKESTATIC, COMPILED_FUNCTION, "create", createDesc, false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /** The compiled class a function literal is a method of, or null. */
    private ClassGen methodClassOf(BabbleParser.FunctionLiteralContext literal) {
        if (!(literal.getParent() instanceof BabbleParser.DefExpressionContext def) || def.value != literal
                || scopes.defTarget(def).kind() != ScopeAnalysis.DefKind.MEMBER) {
            return null;
        }
        for (ParserRuleContext p = def.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassGen gen = classGens.get(p);
                return gen != null && !gen.arrayBacked ? gen : null;
            }
        }
        return null;
    }

    /**
     * The CompiledMethod of a method literal, a constant : one per method, shared by all
     * instances, and the JVM method behind it, generated with the class.
     */
    private String compiledMethod(BabbleParser.FunctionLiteralContext literal, ClassGen gen) {
        String existing = compiledMethodFields.get(literal);
        if (existing != null) {
            return existing;
        }
        String member = ((BabbleParser.DefExpressionContext) literal.getParent()).name.getText();
        String paramsField = stringArrayField(scopes.parameters(literal));
        int required = scopes.requiredParameters(literal);
        pendingMethods.add(() -> generateMethod(literal, gen, paramsField));
        String sam = "(Ljava/lang/Object;L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
        String instantiated = "(L" + gen.name + ";L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
        boolean spread = isSpread(literal);
        boolean spec = hasSpec(literal);
        String method = gen.methodName(member);
        String field = addConstant("METHOD", "L" + COMPILED_METHOD + ";", 32, m -> {
            m.visitTypeInsn(NEW, COMPILED_METHOD);
            m.visitInsn(DUP);
            m.visitInvokeDynamicInsn("invoke", "()L" + METHOD_BODY + ";", METAFACTORY, Type.getMethodType(sam),
                    new Handle(H_INVOKEVIRTUAL, gen.name, spread ? method + "$call" : method, METHOD_DESCRIPTOR, false), Type.getMethodType(instantiated));
            if (spread) {
                m.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, gen.name, method, methodDirectDescriptor(literal), false));
            } else {
                m.visitInsn(ACONST_NULL);
            }
            if (spec) {
                m.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, gen.name, method + "$spec", methodSpecDescriptor(literal), false));
            }
            m.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
            pushInt(m, required);
            m.visitLdcInsn(member);
            String initDesc = spec
                    ? "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V"
                    : "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V";
            m.visitMethodInsn(INVOKESPECIAL, COMPILED_METHOD, "<init>", initDesc, false);
        });
        compiledMethodFields.put(literal, field);
        return field;
    }

    /**
     * The compiled class {@code this} is an instance of at a {@code this} expression : that
     * of the class or object body it is in, unless it is in a static block or an interface.
     */
    private ClassGen thisClassAt(ParserRuleContext thisExpression) {
        ParserRuleContext child = thisExpression;
        for (ParserRuleContext p = thisExpression.getParent(); p != null; child = p, p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext c) {
                if (child == c.classBlock || child == c.parametersDeclaration()) {
                    ClassGen gen = classGens.get(c);
                    return gen != null && !gen.arrayBacked ? gen : null;
                }
                return null;
            }
            if (p instanceof BabbleParser.ObjectExpressionContext o) {
                if (child == o.createBlock) {
                    ClassGen gen = classGens.get(o);
                    return gen != null && !gen.arrayBacked ? gen : null;
                }
                return null;
            }
            if (p instanceof BabbleParser.InterfaceExpressionContext) {
                return null;
            }
        }
        return null;
    }

    /** The index of a field member of {@code this}'s compiled class, or -1 to go through Members. */
    private int fieldIndex(ClassGen gen, String member) {
        if (gen == null) {
            return -1;
        }
        int index = gen.shape.memberIndex(member);
        return index >= 0 && !gen.shape.isMethod(index) ? index : -1;
    }

    private void loadThisAs(BabbleParser.ExpressionContext thisExpression, ClassGen gen) {
        emitBoxed(thisExpression);
        mv.visitTypeInsn(CHECKCAST, gen.name);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    @Override
    public Void visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        String operator = Operators.compound(ctx.op.getText());
        if (operator != null) {
            compoundAssignment(ctx, operator);
            return null;
        }
        String name = ctx.name.getText();
        if (ctx.namespace == null) {
            Resolution.Variable variable = resolution.variable(ctx);
            if (variable != null) {
                ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
                emitAndWidenIfMarked(ctx.value);
                if (scopes.widensToDouble(ctx)) {
                    widenStackToDouble();
                }
                convertStack(stackKind, vk);
                storeVariable(variable, false, vk, ctx);
                return null;
            }
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitLdcInsn(name);
            emitExpr(ctx.value);
            if (scopes.widensToDouble(ctx.value) || scopes.widensToDouble(ctx)) {
                widenStackToDouble();
            }
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(ctx);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        if (ctx.namespace instanceof BabbleParser.ThisExpressionContext) {
            ClassGen gen = thisClassAt(ctx.namespace);
            int idx = fieldIndex(gen, name);
            if (idx >= 0 && !gen.shape.isValue()) {
                ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
                if (mk.isPrimitive()) {
                    int recv = newLocal();
                    loadThisAs(ctx.namespace, gen);
                    mv.visitVarInsn(ASTORE, recv);
                    emitAndWidenIfMarked(ctx.value);
                    if (scopes.widensToDouble(ctx)) {
                        widenStackToDouble();
                    }
                    if (stackKind == ScopeAnalysis.PrimitiveKind.NONE && mk == ScopeAnalysis.PrimitiveKind.DOUBLE
                            && scopes.expressionKind(ctx.value) == ScopeAnalysis.PrimitiveKind.INT
                            && !scopes.widensToDouble(ctx.value) && !scopes.widensToDouble(ctx)) {
                        unbox(ScopeAnalysis.PrimitiveKind.INT);
                        mv.visitInsn(I2D);
                        stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
                    } else {
                        convertStack(stackKind, mk);
                    }
                    int val = newLocal(mk);
                    storeLocal(mk, val);
                    gen.emitCheckDefined(mv, recv, idx, name, ctx);
                    mv.visitVarInsn(ALOAD, recv);
                    loadLocal(mk, val);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                    loadLocal(mk, val);
                    stackKind = mk;
                    return null;
                }
                // A field of this compiled class : checked defined, then written directly.
                loadThisAs(ctx.namespace, gen);
                emitBoxed(ctx.value);
                mv.visitInsn(SWAP);
                mv.visitInsn(DUP);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
                mv.visitLdcInsn(name);
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitInsn(POP);
                mv.visitInsn(SWAP);
                if (scopes.widensToDouble(ctx)) {
                    widenStackToDouble();
                }
                mv.visitInsn(DUP_X1);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return null;
            }
            emitBoxed(ctx.namespace);
            emitExpr(ctx.value);
            if (scopes.widensToDouble(ctx.value) || scopes.widensToDouble(ctx)) {
                widenStackToDouble();
            }
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitLdcInsn(name);
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return null;
        }
        ScopeAnalysis.JavaFieldTarget javaField = scopes.javaFieldTarget(ctx);
        if (javaField != null) {
            emitJavaFieldAssign(ctx, javaField);
            return null;
        }
        // The target is checked before the value is evaluated.
        emitBoxed(ctx.namespace);
        line(ctx);
        pushString(mv, ctx.namespace.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        emitExpr(ctx.value);
        if (scopes.widensToDouble(ctx.value) || scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitLdcInsn(name);
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /**
     * {@code x op= v} : as {@code x = x op v}, reading the target before evaluating {@code v},
     * with the receiver of {@code obj.x op= v} evaluated once. The value assigned is left on
     * the stack.
     */
    private void compoundAssignment(BabbleParser.AssignExpressionContext ctx, String operator) {
        String name = ctx.name.getText();
        if (ctx.namespace == null) {
            Resolution.Variable variable = resolution.variable(ctx);
            if (variable != null) {
                ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
                ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.value);
                if (vk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(operator)) {
                    loadVariable(variable, ctx);
                    int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                    storeLocal(ScopeAnalysis.PrimitiveKind.INT, cur);
                    emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.INT);
                    int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                    storeLocal(ScopeAnalysis.PrimitiveKind.INT, rhs);
                    loadLocal(ScopeAnalysis.PrimitiveKind.INT, cur);
                    loadLocal(ScopeAnalysis.PrimitiveKind.INT, rhs);
                    mv.visitInsn(switch (operator) {
                        case "+" -> IADD;
                        case "-" -> ISUB;
                        case "*" -> IMUL;
                        default -> throw new IllegalStateException();
                    });
                    storeVariable(variable, false, ScopeAnalysis.PrimitiveKind.INT, ctx);
                    return;
                }
                if (vk == ScopeAnalysis.PrimitiveKind.DOUBLE
                        && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)) {
                    loadVariable(variable, ctx);
                    int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                    storeLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, cur);
                    emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.DOUBLE);
                    int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                    storeLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, rhs);
                    loadLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, cur);
                    loadLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, rhs);
                    mv.visitInsn(switch (operator) {
                        case "+" -> DADD;
                        case "-" -> DSUB;
                        case "*" -> DMUL;
                        case "/" -> DDIV;
                        default -> throw new IllegalStateException();
                    });
                    storeVariable(variable, false, ScopeAnalysis.PrimitiveKind.DOUBLE, ctx);
                    return;
                }
            }
            int current = newLocal();
            loadName(ctx, name);
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitVarInsn(ASTORE, current);
            compound(ctx, operator, current);
            if (scopes.widensToDouble(ctx)) {
                widenStackToDouble();
            }
            if (variable != null) {
                ScopeAnalysis.PrimitiveKind vk = variableKind(variable);
                convertStack(stackKind, vk);
                storeVariable(variable, false, vk, ctx);
                return;
            }
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitInsn(SWAP);
            mv.visitLdcInsn(name);
            mv.visitInsn(SWAP);
            line(ctx);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int current = newLocal();
        if (ctx.namespace instanceof BabbleParser.ThisExpressionContext) {
            ClassGen gen = thisClassAt(ctx.namespace);
            int idx = fieldIndex(gen, name);
            if (idx >= 0 && !gen.shape.isValue()) {
                ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
                ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.value);
                if (mk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(operator)) {
                    int recv = newLocal();
                    loadThisAs(ctx.namespace, gen);
                    mv.visitVarInsn(ASTORE, recv);
                    gen.emitCheckDefined(mv, recv, idx, name, ctx);
                    mv.visitVarInsn(ALOAD, recv);
                    mv.visitFieldInsn(GETFIELD, gen.name, name, "I");
                    int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitVarInsn(ISTORE, cur);
                    emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.INT);
                    int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitVarInsn(ISTORE, rhs);
                    mv.visitVarInsn(ILOAD, cur);
                    mv.visitVarInsn(ILOAD, rhs);
                    mv.visitInsn(switch (operator) {
                        case "+" -> IADD;
                        case "-" -> ISUB;
                        case "*" -> IMUL;
                        default -> throw new IllegalStateException();
                    });
                    int res = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                    mv.visitVarInsn(ISTORE, res);
                    mv.visitVarInsn(ALOAD, recv);
                    mv.visitVarInsn(ILOAD, res);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, "I");
                    mv.visitVarInsn(ILOAD, res);
                    stackKind = ScopeAnalysis.PrimitiveKind.INT;
                    return;
                }
                if (mk == ScopeAnalysis.PrimitiveKind.DOUBLE
                        && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)) {
                    int recv = newLocal();
                    loadThisAs(ctx.namespace, gen);
                    mv.visitVarInsn(ASTORE, recv);
                    gen.emitCheckDefined(mv, recv, idx, name, ctx);
                    mv.visitVarInsn(ALOAD, recv);
                    mv.visitFieldInsn(GETFIELD, gen.name, name, "D");
                    int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                    mv.visitVarInsn(DSTORE, cur);
                    emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.DOUBLE);
                    int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                    mv.visitVarInsn(DSTORE, rhs);
                    mv.visitVarInsn(DLOAD, cur);
                    mv.visitVarInsn(DLOAD, rhs);
                    mv.visitInsn(switch (operator) {
                        case "+" -> DADD;
                        case "-" -> DSUB;
                        case "*" -> DMUL;
                        case "/" -> DDIV;
                        default -> throw new IllegalStateException();
                    });
                    int res = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                    mv.visitVarInsn(DSTORE, res);
                    mv.visitVarInsn(ALOAD, recv);
                    mv.visitVarInsn(DLOAD, res);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, "D");
                    mv.visitVarInsn(DLOAD, res);
                    stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
                    return;
                }
                if (mk.isPrimitive()) {
                    int recv = newLocal();
                    loadThisAs(ctx.namespace, gen);
                    mv.visitVarInsn(ASTORE, recv);
                    gen.emitCheckDefined(mv, recv, idx, name, ctx);
                    mv.visitVarInsn(ALOAD, recv);
                    mv.visitFieldInsn(GETFIELD, gen.name, name, jvmDescriptor(mk));
                    box(mk);
                    mv.visitVarInsn(ASTORE, current);
                    compound(ctx, operator, current);
                    if (scopes.widensToDouble(ctx)) {
                        widenStackToDouble();
                    }
                    convertStack(stackKind, mk);
                    int res = newLocal(mk);
                    storeLocal(mk, res);
                    mv.visitVarInsn(ALOAD, recv);
                    loadLocal(mk, res);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                    loadLocal(mk, res);
                    stackKind = mk;
                    return;
                }
                // A field of this compiled class : read, checked defined, then written directly.
                loadThisAs(ctx.namespace, gen);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
                mv.visitLdcInsn(name);
                line(ctx);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, current);
                compound(ctx, operator, current);
                if (scopes.widensToDouble(ctx)) {
                    widenStackToDouble();
                }
                loadThisAs(ctx.namespace, gen);
                mv.visitInsn(SWAP);
                mv.visitInsn(DUP_X1);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
            int thiz = newLocal();
            emitBoxed(ctx.namespace);
            mv.visitVarInsn(ASTORE, thiz);
            mv.visitVarInsn(ALOAD, thiz);
            mv.visitLdcInsn(name);
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, current);
            compound(ctx, operator, current);
            if (scopes.widensToDouble(ctx)) {
                widenStackToDouble();
            }
            mv.visitVarInsn(ALOAD, thiz);
            mv.visitInsn(SWAP);
            mv.visitLdcInsn(name);
            line(ctx);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        ScopeAnalysis.JavaFieldTarget javaField = scopes.javaFieldTarget(ctx);
        if (javaField != null) {
            emitJavaFieldCompoundAssign(ctx, javaField, operator);
            return;
        }
        // The target is checked, then read, before the value is evaluated.
        int scope = newLocal();
        emitBoxed(ctx.namespace);
        line(ctx);
        pushString(mv, ctx.namespace.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, scope);
        mv.visitVarInsn(ALOAD, scope);
        mv.visitLdcInsn(name);
        pushString(mv, ctx.namespace.getText());
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, current);
        compound(ctx, operator, current);
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        mv.visitVarInsn(ALOAD, scope);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(name);
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitJavaFieldAssign(BabbleParser.AssignExpressionContext ctx, ScopeAnalysis.JavaFieldTarget target) {
        java.lang.reflect.Field field = target.field();
        Class<?> fieldType = field.getType();
        String owner = Type.getInternalName(field.getDeclaringClass());
        String desc = Type.getDescriptor(fieldType);
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!isPureStaticJavaChain(ctx.namespace)) {
                emitCheckedJavaReceiver(ctx.namespace, ctx, false, OBJECT, "notAssignable");
                mv.visitInsn(POP);
            }
        } else {
            emitCheckedJavaReceiver(ctx.namespace, ctx, target.unwrapReceiver(), owner, "notAssignable");
            recvLocal = newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }
        if (fieldType == int.class || fieldType == boolean.class) {
            ScopeAnalysis.PrimitiveKind pk = fieldType == int.class
                    ? ScopeAnalysis.PrimitiveKind.INT : ScopeAnalysis.PrimitiveKind.BOOL;
            emitAs(ctx.value, pk);
            int val = newLocal(pk);
            mv.visitVarInsn(ISTORE, val);
            line(ctx);
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            mv.visitVarInsn(ILOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(ILOAD, val);
            stackKind = pk;
            return;
        }
        if (fieldType == double.class) {
            emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.DOUBLE);
            int val = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, val);
            line(ctx);
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            mv.visitVarInsn(DLOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(DLOAD, val);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }
        if (fieldType == long.class || fieldType == float.class) {
            emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.INT);
            int val = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, val);
            line(ctx);
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            mv.visitVarInsn(ILOAD, val);
            mv.visitInsn(fieldType == long.class ? I2L : I2F);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(ILOAD, val);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return;
        }
        ScopeAnalysis.PrimitiveKind valKind = scopes.expressionKind(ctx.value);
        emitBoxed(ctx.value);
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        int babbleVal = newLocal();
        mv.visitVarInsn(ASTORE, babbleVal);
        line(ctx);
        if (!target.isStatic()) {
            mv.visitVarInsn(ALOAD, recvLocal);
        }
        mv.visitVarInsn(ALOAD, babbleVal);
        if (!valKind.isPrimitive()) {
            mv.visitLdcInsn(Type.getType(fieldType));
            mv.visitVarInsn(ALOAD, rtLocal);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        }
        if (fieldType != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(fieldType));
        }
        mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
        mv.visitVarInsn(ALOAD, babbleVal);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitJavaFieldCompoundAssign(BabbleParser.AssignExpressionContext ctx,
                                             ScopeAnalysis.JavaFieldTarget target, String operator) {
        java.lang.reflect.Field field = target.field();
        Class<?> fieldType = field.getType();
        String owner = Type.getInternalName(field.getDeclaringClass());
        String desc = Type.getDescriptor(fieldType);
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!isPureStaticJavaChain(ctx.namespace)) {
                emitCheckedJavaReceiver(ctx.namespace, ctx, false, OBJECT, "notAssignable");
                mv.visitInsn(POP);
            }
        } else {
            emitCheckedJavaReceiver(ctx.namespace, ctx, target.unwrapReceiver(), owner, "notAssignable");
            recvLocal = newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }
        ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx.value);
        if (fieldType == int.class && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(operator)) {
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            line(ctx);
            mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), "I");
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, cur);
            emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.INT);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, rhs);
            mv.visitVarInsn(ILOAD, cur);
            mv.visitVarInsn(ILOAD, rhs);
            mv.visitInsn(switch (operator) {
                case "+" -> IADD;
                case "-" -> ISUB;
                case "*" -> IMUL;
                default -> throw new IllegalStateException();
            });
            int res = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, res);
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            mv.visitVarInsn(ILOAD, res);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), "I");
            mv.visitVarInsn(ILOAD, res);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return;
        }
        if (fieldType == double.class
                && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)) {
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            line(ctx);
            mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), "D");
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, cur);
            emitAs(ctx.value, ScopeAnalysis.PrimitiveKind.DOUBLE);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, rhs);
            mv.visitVarInsn(DLOAD, cur);
            mv.visitVarInsn(DLOAD, rhs);
            mv.visitInsn(switch (operator) {
                case "+" -> DADD;
                case "-" -> DSUB;
                case "*" -> DMUL;
                case "/" -> DDIV;
                default -> throw new IllegalStateException();
            });
            int res = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, res);
            if (!target.isStatic()) {
                mv.visitVarInsn(ALOAD, recvLocal);
            }
            mv.visitVarInsn(DLOAD, res);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), "D");
            mv.visitVarInsn(DLOAD, res);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }
        if (!target.isStatic()) {
            mv.visitVarInsn(ALOAD, recvLocal);
        }
        line(ctx);
        mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), desc);
        emitJavaValueToBabbleStack(fieldType);
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        int current = newLocal();
        mv.visitVarInsn(ASTORE, current);
        compound(ctx, operator, current);
        if (scopes.widensToDouble(ctx)) {
            widenStackToDouble();
        }
        int resObj = newLocal();
        mv.visitVarInsn(ASTORE, resObj);
        if (!target.isStatic()) {
            mv.visitVarInsn(ALOAD, recvLocal);
        }
        mv.visitVarInsn(ALOAD, resObj);
        if (fieldType == int.class) {
            unbox(ScopeAnalysis.PrimitiveKind.INT);
        } else if (fieldType == boolean.class) {
            unbox(ScopeAnalysis.PrimitiveKind.BOOL);
        } else if (fieldType == double.class) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
        } else if (fieldType == long.class) {
            unbox(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(I2L);
        } else if (fieldType == float.class) {
            unbox(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(I2F);
        } else {
            mv.visitLdcInsn(Type.getType(fieldType));
            mv.visitVarInsn(ALOAD, rtLocal);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
            if (fieldType != Object.class) {
                mv.visitTypeInsn(CHECKCAST, Type.getInternalName(fieldType));
            }
        }
        mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
        mv.visitVarInsn(ALOAD, resObj);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    @Override
    public Void visitNull(BabbleParser.NullContext ctx) {
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    @Override
    public Void visitBoolean(BabbleParser.BooleanContext ctx) {
        mv.visitInsn(Boolean.parseBoolean(ctx.getText()) ? ICONST_1 : ICONST_0);
        stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        return null;
    }

    @Override
    public Void visitRecurse(BabbleParser.RecurseContext ctx) {
        loadName(ctx, "$recurse");
        return null;
    }

    @Override
    public Void visitNegate(BabbleParser.NegateContext ctx) {
        if (ctx.operand instanceof BabbleParser.IntegerContext literal) {
            // A literal : "-2147483648" is an int, though "2147483648" isn't.
            integer(ctx, "-" + literal.getText());
            return null;
        }
        ScopeAnalysis.PrimitiveKind ok = scopes.expressionKind(ctx.operand);
        ScopeAnalysis.PrimitiveKind rk = scopes.expressionKind(ctx);
        if (rk == ScopeAnalysis.PrimitiveKind.INT && ok == ScopeAnalysis.PrimitiveKind.INT) {
            emitAs(ctx.operand, ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(INEG);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return null;
        }
        if (rk == ScopeAnalysis.PrimitiveKind.DOUBLE
                && (ok == ScopeAnalysis.PrimitiveKind.DOUBLE || ok == ScopeAnalysis.PrimitiveKind.INT)) {
            emitAs(ctx.operand, ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitInsn(DNEG);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return null;
        }
        int operand = newLocal();
        emitBoxed(ctx.operand);
        mv.visitVarInsn(ASTORE, operand);
        Label number = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, operand);
        mv.visitTypeInsn(INSTANCEOF, RUNTIME + "BabbleInstance");
        mv.visitJumpInsn(IFEQ, number);
        operatorCall(ctx, ctx.operand.getText(), Operators.NEGATE, null, operand, -1);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(number);
        mv.visitVarInsn(ALOAD, operand);
        mv.visitLdcInsn(ctx.operand.getText());
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", "negate", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /**
     * An operator on a Babble instance : {@code a + b} compiles as {@code a.plus(b)} does,
     * through the same select and call sites and their fast paths, with the operands already
     * evaluated in their locals ({@code argument} -1 for {@code -a}, {@code a.neg()}).
     */
    private void operatorCall(ParserRuleContext ctx, String receiver, String method,
                              String argumentText, int receiverLocal, int argument) {
        CallSite site = Interpreter.operatorSite(ctx, receiver, method, argumentText, sourceName);
        String field = addConstant("CS", "L" + CALL_SITE + ";", 24 + estimateSize(site.argTexts()), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, site.sourceName());
            pushInt(m, site.line());
            pushString(m, site.calleeText());
            pushStringArray(m, site.argTexts());
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
        mv.visitVarInsn(ALOAD, receiverLocal);
        line(ctx);
        mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, method, receiver);
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        mv.visitVarInsn(ALOAD, rtLocal);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CALL_SITE + ";");
        if (argument >= 0) {
            mv.visitVarInsn(ALOAD, argument);
        }
        line(ctx);
        mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                + (argument >= 0 ? "Ljava/lang/Object;" : "") + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    @Override
    public Void visitInteger(BabbleParser.IntegerContext ctx) {
        integer(ctx, ctx.getText());
        return null;
    }

    private void integer(ParserRuleContext ctx, String text) {
        try {
            pushInt(mv, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            // Out of range : fail at runtime, with the same exception as the interpreter.
            line(ctx);
            mv.visitLdcInsn(text);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.INT;
    }

    @Override
    public Void visitDouble(BabbleParser.DoubleContext ctx) {
        mv.visitLdcInsn(Double.parseDouble(ctx.getText()));
        stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
        return null;
    }

    @Override
    public Void visitString(BabbleParser.StringContext ctx) {
        pushString(mv, Interpreter.unescape(ctx.STRING().getText()));
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        return null;
    }

    /**
     * {@code COMPUTE_FRAMES} merges types with {@link #getCommonSuperClass}, which by
     * default loads classes through ASM's own class loader. This answers from both the
     * runtime/classpath classes and the generated class/interface metadata, and never tries
     * to load a class currently being generated.
     */
    static final class ScriptClassWriter extends ClassWriter {
        private final ClassLoader runtimeLoader;
        private final String scriptClass;
        private final Map<String, List<String>> generatedClassParents;
        private final Map<String, List<String>> generatedInterfaceParents;

        ScriptClassWriter(ClassLoader runtimeLoader, String scriptClass,
                          Map<String, List<String>> generatedClassParents,
                          Map<String, List<String>> generatedInterfaceParents) {
            super(COMPUTE_FRAMES);
            this.runtimeLoader = runtimeLoader;
            this.scriptClass = scriptClass;
            this.generatedClassParents = generatedClassParents;
            this.generatedInterfaceParents = generatedInterfaceParents;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return runtimeLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(scriptClass) || type2.equals(scriptClass)) {
                return OBJECT;
            }
            if (type1.equals(type2)) {
                return type1;
            }
            if (!exists(type1) || !exists(type2)) {
                return OBJECT;
            }
            if (isAssignable(type1, type2)) {
                return type1;
            }
            if (isAssignable(type2, type1)) {
                return type2;
            }
            boolean iface1 = isInterfaceType(type1);
            boolean iface2 = isInterfaceType(type2);
            if (iface1 && iface2) {
                return commonSuperInterface(type1, type2);
            }
            if (iface1 || iface2) {
                // One is an interface and the other is a class; neither is assignable to the other.
                return OBJECT;
            }
            String cur = type1;
            while (cur != null) {
                if (isAssignable(cur, type2)) {
                    return cur;
                }
                cur = superclassOf(cur);
            }
            return OBJECT;
        }

        private boolean isGenerated(String internalName) {
            return generatedClassParents.containsKey(internalName)
                    || generatedInterfaceParents.containsKey(internalName);
        }

        private boolean exists(String internalName) {
            return isGenerated(internalName) || tryLoad(internalName) != null;
        }

        private boolean isInterfaceType(String internalName) {
            if (generatedInterfaceParents.containsKey(internalName)) {
                return true;
            }
            if (generatedClassParents.containsKey(internalName)) {
                return false;
            }
            Class<?> cls = tryLoad(internalName);
            return cls != null && cls.isInterface();
        }

        private Class<?> tryLoad(String internalName) {
            try {
                return Class.forName(internalName.replace('/', '.'), false, runtimeLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        private List<String> directParents(String internalName) {
            List<String> genClass = generatedClassParents.get(internalName);
            if (genClass != null) {
                return genClass;
            }
            List<String> genIface = generatedInterfaceParents.get(internalName);
            if (genIface != null) {
                return genIface;
            }
            Class<?> cls = tryLoad(internalName);
            if (cls == null) {
                return List.of();
            }
            List<String> parents = new ArrayList<>();
            if (cls.getSuperclass() != null) {
                parents.add(Type.getInternalName(cls.getSuperclass()));
            }
            for (Class<?> iface : cls.getInterfaces()) {
                parents.add(Type.getInternalName(iface));
            }
            return parents;
        }

        private String superclassOf(String internalName) {
            List<String> genClass = generatedClassParents.get(internalName);
            if (genClass != null) {
                for (String parent : genClass) {
                    if (!isInterfaceType(parent)) {
                        return parent;
                    }
                }
                return OBJECT;
            }
            if (generatedInterfaceParents.containsKey(internalName)) {
                return null;
            }
            Class<?> cls = tryLoad(internalName);
            if (cls == null || cls.getSuperclass() == null) {
                return null;
            }
            return Type.getInternalName(cls.getSuperclass());
        }

        private boolean isAssignable(String superType, String subType) {
            if (superType.equals(subType)) {
                return true;
            }
            if (OBJECT.equals(superType)) {
                return exists(subType);
            }
            boolean superGen = isGenerated(superType);
            if (!superGen && !isGenerated(subType)) {
                Class<?> sup = tryLoad(superType);
                Class<?> sub = tryLoad(subType);
                return sup != null && sub != null && sup.isAssignableFrom(sub);
            }
            Class<?> supCls = superGen ? null : tryLoad(superType);
            java.util.Queue<String> queue = new ArrayDeque<>();
            java.util.Set<String> visited = new java.util.HashSet<>();
            visited.add(subType);
            queue.add(subType);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (cur.equals(superType)) {
                    return true;
                }
                boolean curGen = isGenerated(cur);
                if (!curGen) {
                    if (!superGen) {
                        Class<?> curCls = tryLoad(cur);
                        if (supCls != null && curCls != null && supCls.isAssignableFrom(curCls)) {
                            return true;
                        }
                    }
                    continue;
                }
                for (String parent : directParents(cur)) {
                    if (parent != null && visited.add(parent)) {
                        queue.add(parent);
                    }
                }
            }
            return false;
        }

        private String commonSuperInterface(String type1, String type2) {
            java.util.Set<String> ancestors1 = ancestorInterfaces(type1);
            java.util.Set<String> ancestors2 = ancestorInterfaces(type2);
            List<String> common = new ArrayList<>();
            for (String candidate : ancestors1) {
                if (ancestors2.contains(candidate)) {
                    common.add(candidate);
                }
            }
            if (common.isEmpty()) {
                return OBJECT;
            }
            List<String> mostSpecific = new ArrayList<>();
            for (String candidate : common) {
                boolean shadowed = false;
                for (String other : common) {
                    if (!other.equals(candidate) && isAssignable(candidate, other)) {
                        shadowed = true;
                        break;
                    }
                }
                if (!shadowed) {
                    mostSpecific.add(candidate);
                }
            }
            return mostSpecific.size() == 1 ? mostSpecific.get(0) : OBJECT;
        }

        private java.util.Set<String> ancestorInterfaces(String root) {
            java.util.Set<String> result = new java.util.LinkedHashSet<>();
            java.util.Queue<String> queue = new ArrayDeque<>();
            result.add(root);
            queue.add(root);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (String parent : directParents(cur)) {
                    if (parent != null && !OBJECT.equals(parent) && isInterfaceType(parent) && result.add(parent)) {
                        queue.add(parent);
                    }
                }
            }
            return result;
        }
    }
}
