package org.babblelang.compiler;

import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirStaticBlock;
import org.babblelang.ir.hir.HirTypeRef;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * ANTLR-free JVM bytecode emitter consuming a {@link HirModule} (BIP014-P1, BIP014-D8).
 *
 * <p>Emits the script class, compiled plain classes ({@link ClassGen}),
 * JVM interfaces ({@link InterfaceGen}), and chunked constant-pool initialization
 * ({@link ConstantPool}) without referencing {@code org.antlr} or {@code BabbleParser}.
 */
final class HirEmitter {
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

    private static final String RUN_DESCRIPTOR = "(L" + SCRIPT_RUNTIME + ";L" + NAMESPACE + ";)Ljava/lang/Object;";
    private static final String BODY_DESCRIPTOR = "(L" + COMPILED_FUNCTION + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
    private static final String CLASS_BODY_DESCRIPTOR = "(L" + BABBLE_CLASS + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)V";
    static final String METHOD_DESCRIPTOR = "(L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";

    private static final int SELF = 0;
    private static final int INSTANCE = 1;
    private static final int FN_RT = 2;
    private static final int ARGS = 3;
    private static final int NAMES = 4;
    private static final int FN_NS = 6;
    private static final int BINDER_LOCAL = 3;
    private static final int FIRST_PARAMETER = 4;

    private static final int MAX_NODES_PER_RUN_METHOD = 500;

    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, Type.getInternalName(LambdaMetafactory.class), "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);

    private static final String BOOTSTRAP = RUNTIME + "Bootstrap";
    private static final Handle SELECT_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "select",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle CALL_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "call",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle TYPED_CALL_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "typedCall",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);
    private static final Handle TYPED_CALL_OPT_BOOTSTRAP = new Handle(H_INVOKESTATIC, BOOTSTRAP, "typedCallOpt",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;",
            false);

    final HirModule module;
    final String className;
    final String sourceName;
    final ClassLoader runtimeLoader;
    final ConstantPool constantPool;
    final Map<String, String> allInterfaceJvmNames;
    final Map<String, String> allClassJvmNames;
    final Map<String, List<String>> generatedClassParents = new HashMap<>();
    final Map<String, List<String>> generatedInterfaceParents = new HashMap<>();

    private final ClassWriter classWriter;
    private final Map<Integer, InterfaceGen> interfaceGens = new LinkedHashMap<>();
    private final Map<String, InterfaceGen> interfaceGensByTypeId = new LinkedHashMap<>();
    private final Map<Integer, ClassGen> classGens = new LinkedHashMap<>();
    private final Queue<Runnable> pendingMethods = new ArrayDeque<>();
    private final Set<Integer> scheduledFunctions = new HashSet<>();
    private final Map<Integer, String> compiledMethodFields = new HashMap<>();

    private int classBodyCount = 0;
    private int superHelperCount = 0;

    // Per-method emission state
    private MethodVisitor mv;
    private int methodOwnerId;
    private int rtLocal;
    private int nsLocal;
    private int nextLocal;
    private int currentLine;
    private List<Runnable> deferred;
    private Map<Integer, Integer> slots;
    private Map<Integer, Integer> definedSlots;
    private ScopeAnalysis.PrimitiveKind stackKind;
    private ScopeAnalysis.PrimitiveKind methodReturnKind;
    private List<HirVar> capturedVariables;
    private int capturedLocal;
    private int staticTarget = -1;
    private int selfLocal;
    private int thizLocal;
    private ClassGen methodClass;
    private ClassGen constructorClass;
    /** The loop head of the function body being emitted, for its self tail calls (BIP024). */
    private TailLoop tailLoop;
    private int typedThisLocal;
    private int frameNs;
    private int parameterBase;

    private record LoopTarget(Label breakLabel, Label continueLabel, int resultSlot) {
    }

    private final Deque<LoopTarget> loopTargets = new ArrayDeque<>();

    record AncestorMethod(String memberName, String bridgeKey, int overloadIndex, String signatureKey,
                          String descriptor, ScopeAnalysis.FunctionSignature signature) {
    }

    record InheritedDefault(String directParentId, String providerId, ScopeAnalysis.MemberSpec providerSpec) {
    }

    HirEmitter(HirModule module, ClassLoader runtimeLoader) {
        this.module = module;
        this.className = module.className();
        this.sourceName = module.sourceName();
        this.runtimeLoader = runtimeLoader;
        this.constantPool = new ConstantPool(className, sourceName);
        this.allInterfaceJvmNames = module.allInterfaceJvmNames();
        this.allClassJvmNames = module.allClassJvmNames();
        this.classWriter = new ScriptClassWriter(runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
    }

    int nextSuperHelper() {
        return superHelperCount++;
    }

    /** Generates the script class, JVM interfaces, and compiled Babble classes from {@link #module}. */
    Map<String, byte[]> generate() {
        prepareTypes();
        classWriter.visit(V21, ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, className, null, OBJECT, null);
        classWriter.visitSource(sourceName, null);

        generateRun();
        drainPendingMethods();
        for (InterfaceGen iface : interfaceGens.values()) {
            iface.emitBody();
        }
        for (ClassGen gen : classGens.values()) {
            gen.emitInterfaceBridges();
        }
        drainPendingMethods();
        constantPool.emitClinit(classWriter);

        classWriter.visitEnd();
        Map<String, byte[]> classes = new LinkedHashMap<>();
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

    private void drainPendingMethods() {
        while (!pendingMethods.isEmpty()) {
            pendingMethods.poll().run();
        }
    }

    private void prepareTypes() {
        for (HirInterface iface : module.interfaces().values()) {
            generatedInterfaceParents.put(iface.jvmName(), iface.extendedJvmInterfaces());
            InterfaceGen gen = new InterfaceGen(this, iface);
            interfaceGens.put(iface.id(), gen);
            interfaceGensByTypeId.put(iface.shape().id(), gen);
        }
        for (HirClass cls : module.classes().values()) {
            if (!cls.hasCompiledClass()) {
                continue;
            }
            List<String> parents = new ArrayList<>(1 + cls.implementedJvmInterfaces().size());
            parents.add(OBJECT);
            parents.addAll(cls.implementedJvmInterfaces());
            generatedClassParents.put(cls.jvmName(), List.copyOf(parents));
            ClassGen gen = new ClassGen(this, cls);
            gen.begin();
            classGens.put(cls.id(), gen);
        }
    }

    // ---------------------------------------------------------------- run / chunking

    private void generateRun() {
        List<List<HirExpr>> chunks = splitStatements(module.topLevelStatements());

        beginMethod(0, ACC_STATIC, "run", RUN_DESCRIPTOR, 2, 0, 1);
        line(module.startLine());
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(module.topLevelDeclaredNames());
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
            beginMethod(0, ACC_PRIVATE | ACC_STATIC, "run$" + i, RUN_DESCRIPTOR, 2, 0, 1);
            statements(chunks.get(i));
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitInsn(ARETURN);
            endMethod();
        }
    }

    private static List<List<HirExpr>> splitStatements(List<HirExpr> statements) {
        List<List<HirExpr>> chunks = new ArrayList<>();
        List<HirExpr> chunk = new ArrayList<>();
        int size = 0;
        for (HirExpr statement : statements) {
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

    private static int countNodes(HirExpr expr) {
        if (expr == null) {
            return 0;
        }
        return switch (expr) {
            case HirExpr.Const c -> 1;
            case HirExpr.IntParseFallback f -> 1;
            case HirExpr.CoreCollectionRef r -> 1;
            case HirExpr.VarRead v -> 1;
            case HirExpr.NameRead n -> 1;
            case HirExpr.ThisRef t -> 1;
            case HirExpr.RecurseRef r -> 1;
            case HirExpr.Continue c -> 1;
            case HirExpr.FunctionRef f -> 1;
            case HirExpr.ClassExpr c -> 2;
            case HirExpr.InterfaceExpr i -> 2;
            case HirExpr.Def d -> 1 + countNodes(d.value());
            case HirExpr.VarAssign a -> 1 + countNodes(a.value());
            case HirExpr.NameAssign a -> 1 + countNodes(a.value());
            case HirExpr.ThisFieldAssign a -> 1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.JavaFieldAssign a -> 1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.DynamicFieldAssign a -> 1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.ThisFieldGet g -> 1 + countNodes(g.receiver());
            case HirExpr.JavaFieldGet g -> 1 + countNodes(g.receiver());
            case HirExpr.ArrayCreate c -> 1 + countList(c.args());
            case HirExpr.ArrayOp op -> 1 + countNodes(op.receiver()) + countList(op.args());
            case HirExpr.ModuleExportGet g -> 1 + countNodes(g.receiver());
            case HirExpr.DynamicSelect s -> 1 + countNodes(s.receiver());
            case HirExpr.Unary u -> 1 + countNodes(u.operand());
            case HirExpr.Binary b -> 1 + countNodes(b.left()) + countNodes(b.right());
            case HirExpr.Logic l -> 1 + countNodes(l.left()) + countNodes(l.right());
            case HirExpr.Is i -> 1 + countNodes(i.left());
            case HirExpr.As a -> 1 + countNodes(a.left());
            case HirExpr.NonNull n -> 1 + countNodes(n.operand());
            case HirExpr.SpreadAdapt s -> 1 + countNodes(s.value());
            case HirExpr.TypeApply t -> 1 + countNodes(t.inner());
            case HirExpr.Break b -> 1 + countNodes(b.value());
            case HirExpr.Return r -> 1 + countNodes(r.value());
            case HirExpr.Block b -> 1 + countList(b.statements());
            case HirExpr.If i -> 1 + countNodes(i.cond()) + countNodes(i.thenBranch()) + countNodes(i.elseBranch());
            case HirExpr.While w -> 1 + countNodes(w.cond()) + countNodes(w.body());
            case HirExpr.For f -> 2 + countNodes(f.iterable()) + countList(f.body());
            case HirExpr.CoreCollectionCall c -> 1 + countCallArgs(c.args());
            case HirExpr.SuperCall c -> 1 + countCallArgs(c.args());
            case HirExpr.SelfRecurseCall c -> 1 + countList(c.args());
            case HirExpr.SelfTailCall c -> 1 + countCallArgs(c.args());
            case HirExpr.DirectBabbleCall c -> 1 + countNodes(c.callee()) + countList(c.args());
            case HirExpr.DirectThisMethodCall c -> 1 + countNodes(c.receiver()) + countCallArgs(c.args());
            case HirExpr.InterfaceCall c -> 1 + countNodes(c.receiver()) + countList(c.args());
            case HirExpr.JavaConstructorCall c -> 1 + countNodes(c.callee()) + countList(c.args());
            case HirExpr.JavaMethodCall c -> 1 + countNodes(c.receiver()) + countList(c.args());
            case HirExpr.SafeCall c -> 1 + countNodes(c.receiver()) + countCallArgs(c.args());
            case HirExpr.DynamicCall c -> 1 + countNodes(c.callee()) + countCallArgs(c.args());
            case HirExpr.MakeOverloadSet m -> 1 + countList(m.operands());
            case HirExpr.OverloadSelect s -> 1 + countNodes(s.set());
            case HirExpr.OverloadDispatch d -> 1 + countNodes(d.callee()) + countList(d.args()) + d.branches().size() * 2;
        };
    }

    private static int countList(List<HirExpr> list) {
        int sum = 0;
        for (HirExpr e : list) {
            sum += countNodes(e);
        }
        return sum;
    }

    private static int countCallArgs(List<HirCallArg> list) {
        int sum = 0;
        for (HirCallArg a : list) {
            sum += countNodes(a.value());
        }
        return sum;
    }

    // ---------------------------------------------------------------- method lifecycle

    private void beginMethod(int ownerId, int access, String name, String descriptor, int firstFreeLocal, int rt, int ns) {
        beginMethod(classWriter, ownerId, access, name, descriptor, firstFreeLocal, rt, ns, List.of());
    }

    private void beginMethod(ClassWriter writer, int ownerId, int access, String name, String descriptor,
                             int firstFreeLocal, int rt, int ns, List<HirVar> captured) {
        mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        methodOwnerId = ownerId;
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
        capturedVariables = captured;
        capturedLocal = -1;
        staticTarget = -1;
        selfLocal = SELF;
        thizLocal = 1;
        methodClass = null;
        constructorClass = null;
        typedThisLocal = -1;
        tailLoop = null;
    }

    private void endMethod() {
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

    void line(int lineNumber) {
        if (lineNumber > 0 && lineNumber != currentLine) {
            Label label = new Label();
            mv.visitLabel(label);
            mv.visitLineNumber(lineNumber, label);
            currentLine = lineNumber;
        }
    }

    private void getStringArray(String[] values) {
        mv.visitFieldInsn(GETSTATIC, className, constantPool.stringArrayField(values), "[Ljava/lang/String;");
    }

    // ---------------------------------------------------------------- functions & methods

    void ensureFunctionScheduled(HirFunction fn) {
        if (fn.isCompiledClassMethod()) {
            ClassGen gen = classGens.get(fn.ownerClassId());
            if (gen != null) {
                compiledMethodField(fn, gen);
            }
            return;
        }
        if (scheduledFunctions.add(fn.id())) {
            String paramsField = constantPool.stringArrayField(fn.paramNames());
            pendingMethods.add(() -> generateFunction(fn, paramsField));
        }
    }

    private void generateFunction(HirFunction fn, String paramsField) {
        String method = fn.jvmMethodName();
        if (fn.isSpread()) {
            int count = fn.params().size();
            String direct = functionDirectDescriptor(fn);
            callAdapter(classWriter, ACC_STATIC | ACC_SYNTHETIC, method + "$call", BODY_DESCRIPTOR, paramsField, count, fn.keywordOnlyStart(), fn.restIndex(),
                    () -> mv.visitMethodInsn(INVOKESTATIC, className, method, direct, false));
            beginMethod(classWriter, fn.id(), ACC_STATIC | ACC_SYNTHETIC, method, direct,
                    FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count, fn.capturedVars());
            parameterBase = FIRST_PARAMETER;
        } else {
            beginMethod(classWriter, fn.id(), ACC_STATIC | ACC_SYNTHETIC, method, BODY_DESCRIPTOR,
                    FN_NS + 1, FN_RT, FN_NS, fn.capturedVars());
        }
        loadCapturedCells(COMPILED_FUNCTION);
        functionBody(fn, paramsField, COMPILED_FUNCTION);

        if (fn.hasSpec()) {
            generateFunctionSpec(fn, method);
        }
    }

    private void generateFunctionSpec(HirFunction fn, String method) {
        int[] paramSlots = new int[fn.params().size()];
        int nextParam = 3;
        for (int i = 0; i < fn.params().size(); i++) {
            paramSlots[i] = nextParam;
            nextParam += fn.params().get(i).kind().slots();
        }
        int maskSlot = fn.hasOptionalMask() ? nextParam++ : -1;
        int nsSlot = nextParam;
        beginMethod(classWriter, fn.id(), ACC_STATIC | ACC_SYNTHETIC, method + "$spec",
                functionSpecDescriptor(fn), nsSlot + 1, FN_RT, nsSlot, fn.capturedVars());
        methodReturnKind = fn.returnKind();
        loadCapturedCells(COMPILED_FUNCTION);
        functionBodySpec(fn, COMPILED_FUNCTION, paramSlots, maskSlot);
    }

    private void callAdapter(ClassWriter writer, int access, String name, String descriptor, String paramsField, int count, int kwStart, int restIndex, Runnable callDirect) {
        mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        int binder = FN_NS;
        mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        if (restIndex >= 0) {
            ConstantPool.pushInt(mv, kwStart);
            ConstantPool.pushInt(mv, restIndex);
            mv.visitVarInsn(ALOAD, ARGS);
            mv.visitVarInsn(ALOAD, NAMES);
            mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;II[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        } else if (kwStart < count) {
            ConstantPool.pushInt(mv, kwStart);
            mv.visitVarInsn(ALOAD, ARGS);
            mv.visitVarInsn(ALOAD, NAMES);
            mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        } else {
            mv.visitVarInsn(ALOAD, ARGS);
            mv.visitVarInsn(ALOAD, NAMES);
            mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        }
        mv.visitVarInsn(ASTORE, binder);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, FN_RT);
        mv.visitVarInsn(ALOAD, binder);
        for (int i = 0; i < count; i++) {
            mv.visitVarInsn(ALOAD, binder);
            ConstantPool.pushInt(mv, i);
            mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "take", "(I)Ljava/lang/Object;", false);
        }
        callDirect.run();
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    private String compiledMethodField(HirFunction fn, ClassGen gen) {
        String existing = compiledMethodFields.get(fn.id());
        if (existing != null) {
            return existing;
        }
        String member = fn.memberName();
        String paramsField = constantPool.stringArrayField(fn.paramNames());
        int required = fn.requiredParams();
        if (scheduledFunctions.add(fn.id())) {
            pendingMethods.add(() -> generateMethod(fn, gen, paramsField));
        }
        String sam = "(Ljava/lang/Object;L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
        String instantiated = "(L" + gen.name + ";L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
        boolean spread = fn.isSpread();
        boolean spec = fn.hasSpec();
        boolean hasOptMask = spec && fn.hasOptionalMask();
        int optBits = hasOptMask ? fn.optionalParamBits() : 0;
        int restIndex = fn.restIndex();
        String method = fn.jvmMethodName();
        String field = constantPool.add("METHOD", "L" + COMPILED_METHOD + ";", 32, m -> {
            m.visitTypeInsn(NEW, COMPILED_METHOD);
            m.visitInsn(DUP);
            m.visitInvokeDynamicInsn("invoke", "()L" + METHOD_BODY + ";", METAFACTORY, Type.getMethodType(sam),
                    new Handle(H_INVOKEVIRTUAL, gen.name, spread ? method + "$call" : method, METHOD_DESCRIPTOR, false), Type.getMethodType(instantiated));
            if (spread) {
                m.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, gen.name, method, methodDirectDescriptor(fn), false));
            } else {
                m.visitInsn(ACONST_NULL);
            }
            if (spec) {
                m.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, gen.name, method + "$spec", methodSpecDescriptor(fn), false));
            } else if (restIndex >= 0) {
                m.visitInsn(ACONST_NULL);
            }
            m.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
            ConstantPool.pushInt(m, required);
            if (restIndex >= 0) {
                ConstantPool.pushInt(m, optBits);
                ConstantPool.pushInt(m, restIndex);
            } else if (hasOptMask) {
                ConstantPool.pushInt(m, optBits);
            }
            m.visitLdcInsn(member);
            String initDesc = restIndex >= 0
                    ? "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;IIILjava/lang/String;)V"
                    : (hasOptMask
                            ? "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;IILjava/lang/String;)V"
                            : (spec
                                    ? "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V"
                                    : "(L" + METHOD_BODY + ";Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V"));
            m.visitMethodInsn(INVOKESPECIAL, COMPILED_METHOD, "<init>", initDesc, false);
        });
        compiledMethodFields.put(fn.id(), field);
        return field;
    }

    private void generateMethod(HirFunction fn, ClassGen gen, String paramsField) {
        String method = fn.jvmMethodName();
        if (fn.isSpread()) {
            int count = fn.params().size();
            String direct = methodDirectDescriptor(fn);
            callAdapter(gen.writer, ACC_SYNTHETIC, method + "$call", METHOD_DESCRIPTOR, paramsField, count, fn.keywordOnlyStart(), fn.restIndex(),
                    () -> mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, method, direct, false));
            beginMethod(gen.writer, fn.id(), ACC_SYNTHETIC, method, direct,
                    FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count, fn.capturedVars());
            parameterBase = FIRST_PARAMETER;
        } else {
            beginMethod(gen.writer, fn.id(), ACC_SYNTHETIC, method, METHOD_DESCRIPTOR,
                    FN_NS + 1, FN_RT, FN_NS, fn.capturedVars());
        }
        selfLocal = 1;
        thizLocal = 0;
        methodClass = gen;
        loadCapturedCells(BABBLE_CLASS);
        functionBody(fn, paramsField, COMPILED_METHOD);

        if (fn.hasSpec()) {
            generateMethodSpec(fn, gen, method);
        }
    }

    private void generateMethodSpec(HirFunction fn, ClassGen gen, String method) {
        int[] paramSlots = new int[fn.params().size()];
        int nextParam = 3;
        for (int i = 0; i < fn.params().size(); i++) {
            paramSlots[i] = nextParam;
            nextParam += fn.params().get(i).kind().slots();
        }
        int maskSlot = fn.hasOptionalMask() ? nextParam++ : -1;
        int nsSlot = nextParam;
        beginMethod(gen.writer, fn.id(), ACC_SYNTHETIC, method + "$spec",
                methodSpecDescriptor(fn), nsSlot + 1, FN_RT, nsSlot, fn.capturedVars());
        methodReturnKind = fn.returnKind();
        selfLocal = 1;
        thizLocal = 0;
        methodClass = gen;
        loadCapturedCells(BABBLE_CLASS);
        functionBodySpec(fn, COMPILED_METHOD, paramSlots, maskSlot);
    }

    private void loadClosure() {
        if (methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
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

    private void initFrameLocals(HirFunction fn, String selfType) {
        if (fn.namespaceScope()) {
            loadClosure();
            loadRecurseTarget(selfType);
            mv.visitVarInsn(ALOAD, thizLocal);
            getStringArray(fn.declaredNames());
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "enter", "(L" + NAMESPACE + ";L" + CALLABLE + ";Ljava/lang/Object;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
            mv.visitVarInsn(ASTORE, frameNs);
        } else {
            loadClosure();
            mv.visitVarInsn(ASTORE, frameNs);
            for (HirVar variable : declareVariables(fn.declaredVars())) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                    mv.visitVarInsn(ALOAD, thizLocal);
                    initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                } else if (variable.kind() == Resolution.Variable.Kind.RECURSE && variable.used()) {
                    loadRecurseTarget(selfType);
                    initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                }
            }
        }
    }

    private void functionBody(HirFunction fn, String paramsField, String selfType) {
        beginTailLoop(null, -1);
        initFrameLocals(fn, selfType);
        bindParameters(paramsField, fn.params(), fn.keywordOnlyStart(), fn.restIndex(), null);
        statements(fn.body());
        widenStackRank(fn.bodyWideningRank());
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitInsn(ARETURN);
        endMethod();
    }

    private void functionBodySpec(HirFunction fn, String selfType, int[] paramSlots, int maskSlot) {
        beginTailLoop(paramSlots, maskSlot);
        initFrameLocals(fn, selfType);
        List<HirParam> params = fn.params();
        int optBit = 0;
        for (int i = 0; i < params.size(); i++) {
            HirParam param = params.get(i);
            ScopeAnalysis.PrimitiveKind pk = param.kind();
            if (param.defaultValue() != null) {
                Label haveValue = new Label();
                mv.visitVarInsn(ILOAD, maskSlot);
                ConstantPool.pushInt(mv, 1 << optBit);
                mv.visitInsn(IAND);
                mv.visitJumpInsn(IFEQ, haveValue);
                emitAs(param.defaultValue(), pk);
                if (pk == ScopeAnalysis.PrimitiveKind.NONE && param.wideningRank() >= 0) {
                    emitWidenBoxed(mv, param.wideningRank());
                }
                storeLocal(pk, paramSlots[i]);
                mv.visitLabel(haveValue);
                optBit++;
            }
            HirVar variable = param.var();
            if (variable != null) {
                loadLocal(pk, paramSlots[i]);
                if (variable.cell()) {
                    storeVariable(variable, true, pk, param.line());
                    pop(pk);
                } else {
                    initVariable(variable, pk);
                }
            } else {
                mv.visitVarInsn(ALOAD, frameNs);
                mv.visitLdcInsn(param.name());
                loadLocal(pk, paramSlots[i]);
                box(pk);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
            }
        }
        statements(fn.body());
        widenStackRank(fn.bodyWideningRank());
        convertStack(stackKind, methodReturnKind);
        mv.visitInsn(returnInsn(methodReturnKind));
        endMethod();
    }

    private void generateClassBody(HirClass cls, String method) {
        ClassShape shape = cls.shape();
        // Package-private so compiled sibling class/interface bodies in this script can adapt cls$... via LambdaMetafactory.
        beginMethod(classWriter, cls.id(), ACC_STATIC | ACC_SYNTHETIC, method,
                CLASS_BODY_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS, cls.capturedVars());
        loadCapturedCells(BABBLE_CLASS);

        constructorClass = classGens.get(cls.id());
        if (constructorClass != null) {
            typedThisLocal = newLocal();
            mv.visitVarInsn(ALOAD, INSTANCE);
            mv.visitTypeInsn(CHECKCAST, constructorClass.name);
            mv.visitVarInsn(ASTORE, typedThisLocal);
        }
        mv.visitVarInsn(ALOAD, SELF);
        mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, FN_NS);
        for (HirVar variable : declareVariables(cls.declaredVars())) {
            if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                mv.visitVarInsn(ALOAD, INSTANCE);
                initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
            }
        }

        bindParameters(constantPool.stringArrayField(shape.parameters()), cls.params(), shape.keywordOnlyStart(), -1, shape);
        statements(cls.body());
        pop(stackKind);
        mv.visitInsn(RETURN);
        endMethod();
    }

    private void bindParameters(String paramsField, List<HirParam> params, int kwStart, int restIndex, ClassShape shape) {
        boolean spread = parameterBase >= 0;
        int binder = spread ? BINDER_LOCAL : newLocal();
        int arrayValue = spread ? -1 : newLocal();
        if (!spread) {
            mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
            if (restIndex >= 0) {
                ConstantPool.pushInt(mv, kwStart);
                ConstantPool.pushInt(mv, restIndex);
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;II[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
            } else if (kwStart < params.size()) {
                ConstantPool.pushInt(mv, kwStart);
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
            } else {
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
            }
            mv.visitVarInsn(ASTORE, binder);
        }

        for (int i = 0; i < params.size(); i++) {
            HirParam param = params.get(i);
            HirExpr defaultValue = param.defaultValue();
            Label bind = new Label();
            int value = spread ? parameterBase + i : arrayValue;

            if (!spread) {
                mv.visitVarInsn(ALOAD, binder);
                ConstantPool.pushInt(mv, i);
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
                mv.visitLdcInsn(param.name());
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "missing", "(Ljava/lang/String;)Lorg/babblelang/runtime/BabbleException;", false);
                mv.visitInsn(ATHROW);
            } else {
                mv.visitVarInsn(ALOAD, binder);
                ConstantPool.pushInt(mv, i);
                mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "missing", "(I)Lorg/babblelang/runtime/BabbleException;", false);
                mv.visitInsn(ATHROW);
            }
            mv.visitLabel(bind);
            if (param.wideningRank() >= 0) {
                mv.visitVarInsn(ALOAD, value);
                emitWidenBoxed(mv, param.wideningRank());
                mv.visitVarInsn(ASTORE, value);
            }
            int member = shape != null ? param.memberIndex() : -1;
            if (member >= 0 && constructorClass != null) {
                ScopeAnalysis.PrimitiveKind mk = constructorClass.memberKinds[member];
                mv.visitVarInsn(ALOAD, typedThisLocal);
                mv.visitVarInsn(ALOAD, value);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                convertStack(ScopeAnalysis.PrimitiveKind.NONE, mk);
                mv.visitFieldInsn(PUTFIELD, constructorClass.name, shape.member(member), jvmDescriptor(mk));
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            } else if (param.var() != null) {
                HirVar variable = param.var();
                ScopeAnalysis.PrimitiveKind vk = variable.primitiveKind();
                mv.visitVarInsn(ALOAD, value);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                convertStack(ScopeAnalysis.PrimitiveKind.NONE, vk);
                if (variable.cell()) {
                    storeVariable(variable, true, vk, param.line());
                    pop(vk);
                } else {
                    initVariable(variable, vk);
                }
            } else {
                mv.visitVarInsn(ALOAD, frameNs);
                mv.visitLdcInsn(param.name());
                mv.visitVarInsn(ALOAD, value);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
            }
        }
        Label bound = new Label();
        if (spread) {
            mv.visitVarInsn(ALOAD, binder);
            mv.visitJumpInsn(IFNULL, bound);
        }
        mv.visitVarInsn(ALOAD, binder);
        mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "rejectLeftovers", "()V", false);
        mv.visitLabel(bound);
    }

    // ---------------------------------------------------------------- frame variables & cells

    private void loadCapturedCells(String ownerType) {
        if (capturedVariables.isEmpty()) {
            return;
        }
        capturedLocal = newLocal();
        if (methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
        } else {
            mv.visitVarInsn(ALOAD, SELF);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, ownerType, "captured", "()[Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, capturedLocal);
    }

    private List<HirVar> declareVariables(List<HirVar> variables) {
        for (HirVar variable : variables) {
            ScopeAnalysis.PrimitiveKind vk = variable.primitiveKind();
            int slot = newLocal(variable.cell() ? ScopeAnalysis.PrimitiveKind.NONE : vk);
            slots.put(variable.id(), slot);
            if (!variable.cell() && vk.isPrimitive() && variable.mayBeUndefined() && !variable.definitelyDefined()) {
                int defSlot = newLocal(ScopeAnalysis.PrimitiveKind.BOOL);
                definedSlots.put(variable.id(), defSlot);
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
            if (variable.kind() == Resolution.Variable.Kind.DEFINITION
                    || (variable.kind() == Resolution.Variable.Kind.PARAMETER && variable.cell())) {
                if (variable.cell()) {
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

    private void publishCell(HirVar variable) {
        if (constructorClass != null && variable.cell() && variable.ownerMethodId() == constructorClass.cls.id()) {
            mv.visitVarInsn(ALOAD, typedThisLocal);
            mv.visitVarInsn(ALOAD, slots.get(variable.id()));
            mv.visitFieldInsn(PUTFIELD, constructorClass.name, variable.hiddenFieldName(), "Ljava/lang/Object;");
        }
    }

    private void initVariable(HirVar variable, ScopeAnalysis.PrimitiveKind vk) {
        if (variable.cell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(INVOKESTATIC, cc, "of", "(" + jvmDescriptor(vk) + ")L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, slots.get(variable.id()));
            publishCell(variable);
            return;
        }
        storeLocal(vk, slots.get(variable.id()));
        Integer defSlot = definedSlots.get(variable.id());
        if (defSlot != null) {
            mv.visitInsn(ICONST_1);
            mv.visitVarInsn(ISTORE, defSlot);
        }
    }

    private void loadCell(HirVar variable) {
        String cc = cellClass(variable.primitiveKind());
        if (variable.ownerMethodId() == methodOwnerId) {
            mv.visitVarInsn(ALOAD, slots.get(variable.id()));
        } else if (methodClass != null && variable.ownerMethodId() == methodClass.cls.id()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, methodClass.name, variable.hiddenFieldName(), "Ljava/lang/Object;");
            mv.visitTypeInsn(CHECKCAST, cc);
        } else {
            int idx = capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, cc);
        }
    }

    private void loadVariable(HirVar variable, int lineNumber) {
        ScopeAnalysis.PrimitiveKind vk = variable.primitiveKind();
        if (variable.cell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            loadCell(variable);
            if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
                line(lineNumber);
                mv.visitLdcInsn(variable.name());
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "read", "(Ljava/lang/String;)" + desc, false);
            } else {
                mv.visitFieldInsn(GETFIELD, cc, "value", desc);
            }
            stackKind = vk;
            return;
        }
        if (vk.isPrimitive()) {
            Integer defSlot = definedSlots.get(variable.id());
            if (defSlot != null) {
                mv.visitVarInsn(ILOAD, defSlot);
                mv.visitLdcInsn(variable.name());
                line(lineNumber);
                mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
            }
            loadLocal(vk, slots.get(variable.id()));
            stackKind = vk;
            return;
        }
        mv.visitVarInsn(ALOAD, slots.get(variable.id()));
        if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
            line(lineNumber);
            mv.visitLdcInsn(variable.name());
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "defined", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void storeVariable(HirVar variable, boolean definition, ScopeAnalysis.PrimitiveKind vk, int lineNumber) {
        if (variable.cell()) {
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
                line(lineNumber);
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "assign", "(" + desc + "Ljava/lang/String;)" + desc, false);
            }
            stackKind = vk;
            return;
        }
        int slot = slots.get(variable.id());
        if (vk.isPrimitive()) {
            Integer defSlot = definedSlots.get(variable.id());
            if (defSlot != null) {
                if (definition) {
                    mv.visitInsn(ICONST_1);
                    mv.visitVarInsn(ISTORE, defSlot);
                } else {
                    mv.visitVarInsn(ILOAD, defSlot);
                    mv.visitLdcInsn(variable.name());
                    line(lineNumber);
                    mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
                }
            }
            dup(vk);
            storeLocal(vk, slot);
            stackKind = vk;
            return;
        }
        if (!definition && variable.mayBeUndefined() && !variable.definitelyDefined()) {
            mv.visitVarInsn(ALOAD, slot);
            mv.visitLdcInsn(variable.name());
            line(lineNumber);
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkAssignable", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, slot);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void loadVarOrName(HirVar var, String name, int lineNumber) {
        if (var != null) {
            loadVariable(var, lineNumber);
            return;
        }
        line(lineNumber);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void capturedCells(List<HirVar> captured) {
        if (captured.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, captured.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < captured.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            loadCell(captured.get(i));
            mv.visitInsn(AASTORE);
        }
    }

    private void inFrameScope(Runnable body) {
        int savedNextLocal = nextLocal;
        Map<Integer, Integer> savedSlots = new HashMap<>(slots);
        Map<Integer, Integer> savedDefinedSlots = new HashMap<>(definedSlots);
        body.run();
        nextLocal = savedNextLocal;
        slots = savedSlots;
        definedSlots = savedDefinedSlots;
    }

    private void withNamespace(int namespace, Runnable body) {
        int outer = nsLocal;
        int savedNextLocal = nextLocal;
        nsLocal = namespace;
        try {
            body.run();
        } finally {
            nsLocal = outer;
            nextLocal = savedNextLocal;
        }
    }

    // ---------------------------------------------------------------- statements & expression dispatch

    private void statements(List<HirExpr> statements) {
        if (statements.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int last = statements.size() - 1;
        for (int i = 0; i < last; i++) {
            emitExpr(statements.get(i));
            pop(stackKind);
        }
        emitAndWiden(statements.get(last));
    }

    private void emitExpr(HirExpr expr) {
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        switch (expr) {
            case HirExpr.Const c -> emitConst(c);
            case HirExpr.IntParseFallback f -> emitIntParseFallback(f);
            case HirExpr.CoreCollectionRef r -> emitCoreCollectionRef(r);
            case HirExpr.VarRead v -> loadVariable(v.var(), v.meta().line());
            case HirExpr.NameRead n -> loadVarOrName(null, n.name(), n.meta().line());
            case HirExpr.ThisRef t -> loadVarOrName(t.var(), "this", t.meta().line());
            case HirExpr.RecurseRef r -> loadVarOrName(r.var(), "$recurse", r.meta().line());
            case HirExpr.Def d -> emitDef(d);
            case HirExpr.VarAssign a -> emitVarAssign(a);
            case HirExpr.NameAssign a -> emitNameAssign(a);
            case HirExpr.ThisFieldAssign a -> emitThisFieldAssign(a);
            case HirExpr.JavaFieldAssign a -> emitJavaFieldAssignExpr(a);
            case HirExpr.DynamicFieldAssign a -> emitDynamicFieldAssign(a);
            case HirExpr.ThisFieldGet g -> emitThisFieldGet(g);
            case HirExpr.JavaFieldGet g -> emitJavaFieldGet(g);
            case HirExpr.ArrayCreate c -> emitArrayCreate(c);
            case HirExpr.ArrayOp op -> emitArrayOp(op);
            case HirExpr.ModuleExportGet g -> emitModuleExportGet(g);
            case HirExpr.DynamicSelect s -> emitDynamicSelect(s);
            case HirExpr.Unary u -> emitUnary(u);
            case HirExpr.Binary b -> emitBinary(b);
            case HirExpr.Logic l -> emitLogic(l);
            case HirExpr.Is i -> emitIs(i);
            case HirExpr.As a -> emitAsExpr(a);
            case HirExpr.NonNull n -> emitNonNull(n);
            case HirExpr.SpreadAdapt s -> emitSpreadAdapt(s);
            case HirExpr.Block b -> emitBlock(b);
            case HirExpr.If i -> emitIf(i);
            case HirExpr.While w -> emitWhile(w);
            case HirExpr.For f -> emitFor(f);
            case HirExpr.Break b -> emitBreak(b);
            case HirExpr.Continue c -> emitContinue();
            case HirExpr.Return r -> emitReturn(r);
            case HirExpr.CoreCollectionCall c -> emitCoreCollectionCall(c);
            case HirExpr.SuperCall c -> emitSuperCall(c);
            case HirExpr.SelfRecurseCall c -> emitSelfRecurseCall(c);
            case HirExpr.SelfTailCall c -> emitSelfTailCall(c);
            case HirExpr.DirectBabbleCall c -> emitDirectBabbleCall(c);
            case HirExpr.DirectThisMethodCall c -> emitDirectThisMethodCall(c);
            case HirExpr.InterfaceCall c -> emitInterfaceCall(c);
            case HirExpr.JavaConstructorCall c -> emitJavaConstructorCall(c);
            case HirExpr.JavaMethodCall c -> emitJavaMethodCall(c);
            case HirExpr.SafeCall c -> emitSafeCall(c);
            case HirExpr.DynamicCall c -> emitDynamicCall(c);
            case HirExpr.MakeOverloadSet m -> emitMakeOverloadSet(m);
            case HirExpr.OverloadSelect s -> emitOverloadSelect(s);
            case HirExpr.OverloadDispatch d -> emitOverloadDispatch(d);
            case HirExpr.FunctionRef f -> emitFunctionRef(f);
            case HirExpr.ClassExpr c -> emitClassExpr(c);
            case HirExpr.InterfaceExpr i -> emitInterfaceExpr(i);
            case HirExpr.TypeApply t -> emitBoxed(t.inner());
        }
    }

    private void emitAndWiden(HirExpr expr) {
        emitExpr(expr);
        widenStackRank(expr.meta().wideningRank());
    }

    private void emitAs(HirExpr expr, ScopeAnalysis.PrimitiveKind desired) {
        emitAndWiden(expr);
        if (stackKind == ScopeAnalysis.PrimitiveKind.NONE && desired.isPrimitive()) {
            ScopeAnalysis.PrimitiveKind exprKind = expr.meta().kind();
            if (exprKind.isPrimitive() && expr.meta().wideningRank() < 0 && exprKind != desired) {
                unbox(exprKind);
                stackKind = exprKind;
                convertStack(stackKind, desired);
                return;
            }
        }
        convertStack(stackKind, desired);
    }

    private void emitBoxed(HirExpr expr) {
        emitAs(expr, ScopeAnalysis.PrimitiveKind.NONE);
    }

    // ---------------------------------------------------------------- literals

    private void emitConst(HirExpr.Const c) {
        Object v = c.value();
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
        } else if (v instanceof Byte b) {
            ConstantPool.pushInt(mv, b);
            stackKind = ScopeAnalysis.PrimitiveKind.BYTE;
        } else if (v instanceof Short s) {
            ConstantPool.pushInt(mv, s);
            stackKind = ScopeAnalysis.PrimitiveKind.SHORT;
        } else if (v instanceof Integer i) {
            ConstantPool.pushInt(mv, i);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
        } else if (v instanceof Long l) {
            mv.visitLdcInsn(l);
            stackKind = ScopeAnalysis.PrimitiveKind.LONG;
        } else if (v instanceof Float f) {
            mv.visitLdcInsn(f);
            stackKind = ScopeAnalysis.PrimitiveKind.FLOAT;
        } else if (v instanceof Double d) {
            mv.visitLdcInsn(d);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
        } else if (v instanceof Character ch) {
            ConstantPool.pushInt(mv, ch);
            stackKind = ScopeAnalysis.PrimitiveKind.CHAR;
        } else if (v instanceof String s) {
            ConstantPool.pushString(mv, s);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        } else {
            throw new IllegalStateException("Unsupported HIR constant : " + v.getClass());
        }
    }

    private void emitIntParseFallback(HirExpr.IntParseFallback f) {
        line(f.meta().line());
        mv.visitLdcInsn(f.text());
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        stackKind = ScopeAnalysis.PrimitiveKind.INT;
    }

    private void emitCoreCollectionRef(HirExpr.CoreCollectionRef r) {
        line(r.meta().line());
        mv.visitLdcInsn(r.collectionKind());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "CoreCollections", "constructor",
                "(Ljava/lang/String;)L" + RUNTIME + "Callable;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- defs & assignments

    private void defValueAs(HirExpr.Def d, ScopeAnalysis.PrimitiveKind desired) {
        if (d.value() != null) {
            emitExpr(d.value());
        } else {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
        widenStackRank(d.meta().wideningRank());
        convertStack(stackKind, desired);
    }

    private void emitDef(HirExpr.Def d) {
        ScopeAnalysis.DefTarget target = d.target();
        if (target.kind() == ScopeAnalysis.DefKind.REQUIRED) {
            mv.visitInsn(ACONST_NULL);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        if (d.var() != null) {
            ScopeAnalysis.PrimitiveKind vk = d.var().primitiveKind();
            defValueAs(d, vk);
            storeVariable(d.var(), true, vk, d.meta().line());
            return;
        }
        if (target.kind() == ScopeAnalysis.DefKind.MEMBER) {
            if (constructorClass == null) {
                throw new IllegalStateException("Unexpected MEMBER def outside compiled class : " + d.name());
            }
            int idx = target.index();
            ScopeAnalysis.PrimitiveKind mk = constructorClass.memberKinds[idx];
            if (mk.isPrimitive()) {
                int tmp = newLocal(mk);
                defValueAs(d, mk);
                storeLocal(mk, tmp);
                mv.visitVarInsn(ALOAD, typedThisLocal);
                loadLocal(mk, tmp);
                mv.visitFieldInsn(PUTFIELD, constructorClass.name, d.name(), jvmDescriptor(mk));
                constructorClass.emitMarkDefined(mv, typedThisLocal, idx);
                loadLocal(mk, tmp);
                stackKind = mk;
                return;
            }
            mv.visitVarInsn(ALOAD, typedThisLocal);
            defValueAs(d, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitInsn(DUP_X1);
            mv.visitFieldInsn(PUTFIELD, constructorClass.name, d.name(), "Ljava/lang/Object;");
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        if (target.kind() == ScopeAnalysis.DefKind.STATIC) {
            mv.visitVarInsn(ALOAD, staticTarget);
        } else {
            mv.visitVarInsn(ALOAD, nsLocal);
        }
        if (target.kind() == ScopeAnalysis.DefKind.LEXICAL) {
            mv.visitLdcInsn(d.name());
        } else {
            ConstantPool.pushInt(mv, target.index());
        }
        defValueAs(d, ScopeAnalysis.PrimitiveKind.NONE);
        switch (target.kind()) {
            case LEXICAL -> mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "initialize", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            case STATIC -> mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "BabbleType", "initStatic", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
            default -> mv.visitMethodInsn(INVOKESTATIC, CLASSES, "setDefault", "(L" + NAMESPACE + ";ILjava/lang/Object;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitVarAssign(HirExpr.VarAssign a) {
        HirVar variable = a.var();
        ScopeAnalysis.PrimitiveKind vk = variable.primitiveKind();
        int ln = a.meta().line();
        if (a.operator() == null) {
            emitAndWiden(a.value());
            widenStackRank(a.meta().wideningRank());
            convertStack(stackKind, vk);
            storeVariable(variable, false, vk, ln);
            return;
        }
        String op = a.operator();
        ScopeAnalysis.PrimitiveKind rk = a.value().meta().kind();
        if (vk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(op)) {
            loadVariable(variable, ln);
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            storeLocal(ScopeAnalysis.PrimitiveKind.INT, cur);
            emitAs(a.value(), ScopeAnalysis.PrimitiveKind.INT);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            storeLocal(ScopeAnalysis.PrimitiveKind.INT, rhs);
            loadLocal(ScopeAnalysis.PrimitiveKind.INT, cur);
            loadLocal(ScopeAnalysis.PrimitiveKind.INT, rhs);
            mv.visitInsn(switch (op) {
                case "+" -> IADD;
                case "-" -> ISUB;
                case "*" -> IMUL;
                default -> throw new IllegalStateException();
            });
            storeVariable(variable, false, ScopeAnalysis.PrimitiveKind.INT, ln);
            return;
        }
        if (vk == ScopeAnalysis.PrimitiveKind.DOUBLE
                && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)) {
            loadVariable(variable, ln);
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            storeLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, cur);
            emitAs(a.value(), ScopeAnalysis.PrimitiveKind.DOUBLE);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            storeLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, rhs);
            loadLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, cur);
            loadLocal(ScopeAnalysis.PrimitiveKind.DOUBLE, rhs);
            mv.visitInsn(switch (op) {
                case "+" -> DADD;
                case "-" -> DSUB;
                case "*" -> DMUL;
                case "/" -> DDIV;
                default -> throw new IllegalStateException();
            });
            storeVariable(variable, false, ScopeAnalysis.PrimitiveKind.DOUBLE, ln);
            return;
        }
        int current = newLocal();
        loadVariable(variable, ln);
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        widenStackRank(a.meta().wideningRank());
        convertStack(stackKind, vk);
        storeVariable(variable, false, vk, ln);
    }

    private void emitNameAssign(HirExpr.NameAssign a) {
        int ln = a.meta().line();
        if (a.operator() == null) {
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitLdcInsn(a.name());
            emitAndWiden(a.value());
            widenStackRank(a.meta().wideningRank());
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int current = newLocal();
        loadVarOrName(null, a.name(), ln);
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, a.operator(), a.targetText(), a.value(), a.valueText(), current);
        widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(a.name());
        mv.visitInsn(SWAP);
        line(ln);
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitThisFieldAssign(HirExpr.ThisFieldAssign a) {
        int ln = a.meta().line();
        ClassGen gen = a.classId() >= 0 ? classGens.get(a.classId()) : null;
        int idx = a.memberIndex();
        String name = a.memberName();
        if (a.operator() == null) {
            if (gen != null && idx >= 0) {
                ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
                if (mk.isPrimitive()) {
                    int recv = newLocal();
                    loadThisAs(a.receiver(), gen);
                    mv.visitVarInsn(ASTORE, recv);
                    emitAndWiden(a.value());
                    widenStackRank(a.meta().wideningRank());
                    if (stackKind == ScopeAnalysis.PrimitiveKind.NONE && mk == ScopeAnalysis.PrimitiveKind.DOUBLE
                            && a.value().meta().kind() == ScopeAnalysis.PrimitiveKind.INT
                            && a.value().meta().wideningRank() < 0 && a.meta().wideningRank() < 0) {
                        unbox(ScopeAnalysis.PrimitiveKind.INT);
                        mv.visitInsn(I2D);
                        stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
                    } else {
                        convertStack(stackKind, mk);
                    }
                    int val = newLocal(mk);
                    storeLocal(mk, val);
                    gen.emitCheckDefined(mv, recv, idx, name, ln);
                    mv.visitVarInsn(ALOAD, recv);
                    loadLocal(mk, val);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                    loadLocal(mk, val);
                    stackKind = mk;
                    return;
                }
                loadThisAs(a.receiver(), gen);
                emitBoxed(a.value());
                mv.visitInsn(SWAP);
                mv.visitInsn(DUP);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
                mv.visitLdcInsn(name);
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitInsn(POP);
                mv.visitInsn(SWAP);
                widenStackRank(a.meta().wideningRank());
                mv.visitInsn(DUP_X1);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
            emitBoxed(a.receiver());
            emitAndWiden(a.value());
            widenStackRank(a.meta().wideningRank());
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitLdcInsn(name);
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }

        // Compound assignment on this.field
        String op = a.operator();
        int current = newLocal();
        if (gen != null && idx >= 0) {
            ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
            ScopeAnalysis.PrimitiveKind rk = a.value().meta().kind();
            if (mk == ScopeAnalysis.PrimitiveKind.INT && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(op)) {
                int recv = newLocal();
                loadThisAs(a.receiver(), gen);
                mv.visitVarInsn(ASTORE, recv);
                gen.emitCheckDefined(mv, recv, idx, name, ln);
                mv.visitVarInsn(ALOAD, recv);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "I");
                int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                mv.visitVarInsn(ISTORE, cur);
                emitAs(a.value(), ScopeAnalysis.PrimitiveKind.INT);
                int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
                mv.visitVarInsn(ISTORE, rhs);
                mv.visitVarInsn(ILOAD, cur);
                mv.visitVarInsn(ILOAD, rhs);
                mv.visitInsn(switch (op) {
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
                loadThisAs(a.receiver(), gen);
                mv.visitVarInsn(ASTORE, recv);
                gen.emitCheckDefined(mv, recv, idx, name, ln);
                mv.visitVarInsn(ALOAD, recv);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "D");
                int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                mv.visitVarInsn(DSTORE, cur);
                emitAs(a.value(), ScopeAnalysis.PrimitiveKind.DOUBLE);
                int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                mv.visitVarInsn(DSTORE, rhs);
                mv.visitVarInsn(DLOAD, cur);
                mv.visitVarInsn(DLOAD, rhs);
                mv.visitInsn(switch (op) {
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
                loadThisAs(a.receiver(), gen);
                mv.visitVarInsn(ASTORE, recv);
                gen.emitCheckDefined(mv, recv, idx, name, ln);
                mv.visitVarInsn(ALOAD, recv);
                mv.visitFieldInsn(GETFIELD, gen.name, name, jvmDescriptor(mk));
                box(mk);
                mv.visitVarInsn(ASTORE, current);
                emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
                widenStackRank(a.meta().wideningRank());
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
            loadThisAs(a.receiver(), gen);
            mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
            mv.visitLdcInsn(name);
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, current);
            emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
            widenStackRank(a.meta().wideningRank());
            loadThisAs(a.receiver(), gen);
            mv.visitInsn(SWAP);
            mv.visitInsn(DUP_X1);
            mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int thiz = newLocal();
        emitBoxed(a.receiver());
        mv.visitVarInsn(ASTORE, thiz);
        mv.visitVarInsn(ALOAD, thiz);
        mv.visitLdcInsn(name);
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, thiz);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(name);
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitJavaFieldAssignExpr(HirExpr.JavaFieldAssign a) {
        int ln = a.meta().line();
        ScopeAnalysis.JavaFieldTarget target = a.target();
        Field field = target.field();
        Class<?> fieldType = field.getType();
        String owner = Type.getInternalName(field.getDeclaringClass());
        String desc = Type.getDescriptor(fieldType);
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!a.pureStaticReceiver()) {
                emitCheckedJavaReceiver(a.receiver(), a.receiverText(), ln, false, OBJECT, "notAssignable", null);
                mv.visitInsn(POP);
            }
        } else {
            emitCheckedJavaReceiver(a.receiver(), a.receiverText(), ln, target.unwrapReceiver(), owner, "notAssignable", null);
            recvLocal = newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }

        if (a.operator() == null) {
            if (fieldType == int.class || fieldType == boolean.class) {
                ScopeAnalysis.PrimitiveKind pk = fieldType == int.class
                        ? ScopeAnalysis.PrimitiveKind.INT : ScopeAnalysis.PrimitiveKind.BOOL;
                emitAs(a.value(), pk);
                int val = newLocal(pk);
                mv.visitVarInsn(ISTORE, val);
                line(ln);
                if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
                mv.visitVarInsn(ILOAD, val);
                mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
                mv.visitVarInsn(ILOAD, val);
                stackKind = pk;
                return;
            }
            if (fieldType == double.class) {
                emitAs(a.value(), ScopeAnalysis.PrimitiveKind.DOUBLE);
                int val = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
                mv.visitVarInsn(DSTORE, val);
                line(ln);
                if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
                mv.visitVarInsn(DLOAD, val);
                mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
                mv.visitVarInsn(DLOAD, val);
                stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
                return;
            }
            if (fieldType == long.class) {
                emitExpr(a.value());
                if (stackKind == ScopeAnalysis.PrimitiveKind.INT) {
                    mv.visitInsn(I2L);
                } else {
                    box(stackKind);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                }
                int val = newLocal(ScopeAnalysis.PrimitiveKind.LONG);
                mv.visitVarInsn(LSTORE, val);
                line(ln);
                if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
                mv.visitVarInsn(LLOAD, val);
                mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
                mv.visitVarInsn(LLOAD, val);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
            if (fieldType == float.class) {
                emitExpr(a.value());
                if (stackKind == ScopeAnalysis.PrimitiveKind.INT) {
                    mv.visitInsn(I2F);
                } else {
                    box(stackKind);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                }
                int val = newLocal();
                mv.visitVarInsn(FSTORE, val);
                line(ln);
                if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
                mv.visitVarInsn(FLOAD, val);
                mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
                mv.visitVarInsn(FLOAD, val);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
            ScopeAnalysis.PrimitiveKind valKind = a.value().meta().kind();
            emitBoxed(a.value());
            widenStackRank(a.meta().wideningRank());
            int babbleVal = newLocal();
            mv.visitVarInsn(ASTORE, babbleVal);
            line(ln);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
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
            return;
        }

        // Compound assignment on Java field
        String op = a.operator();
        ScopeAnalysis.PrimitiveKind rk = a.value().meta().kind();
        if (fieldType == int.class && rk == ScopeAnalysis.PrimitiveKind.INT && !"/".equals(op)) {
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            line(ln);
            mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), "I");
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, cur);
            emitAs(a.value(), ScopeAnalysis.PrimitiveKind.INT);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, rhs);
            mv.visitVarInsn(ILOAD, cur);
            mv.visitVarInsn(ILOAD, rhs);
            mv.visitInsn(switch (op) {
                case "+" -> IADD;
                case "-" -> ISUB;
                case "*" -> IMUL;
                default -> throw new IllegalStateException();
            });
            int res = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, res);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(ILOAD, res);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), "I");
            mv.visitVarInsn(ILOAD, res);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return;
        }
        if (fieldType == double.class && (rk == ScopeAnalysis.PrimitiveKind.INT || rk == ScopeAnalysis.PrimitiveKind.DOUBLE)) {
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            line(ln);
            mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), "D");
            int cur = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, cur);
            emitAs(a.value(), ScopeAnalysis.PrimitiveKind.DOUBLE);
            int rhs = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, rhs);
            mv.visitVarInsn(DLOAD, cur);
            mv.visitVarInsn(DLOAD, rhs);
            mv.visitInsn(switch (op) {
                case "+" -> DADD;
                case "-" -> DSUB;
                case "*" -> DMUL;
                case "/" -> DDIV;
                default -> throw new IllegalStateException();
            });
            int res = newLocal(ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, res);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(DLOAD, res);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), "D");
            mv.visitVarInsn(DLOAD, res);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }
        if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
        line(ln);
        mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), desc);
        emitJavaValueToBabbleStack(fieldType);
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        int current = newLocal();
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        widenStackRank(a.meta().wideningRank());
        int resObj = newLocal();
        mv.visitVarInsn(ASTORE, resObj);
        if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
        mv.visitVarInsn(ALOAD, resObj);
        ScopeAnalysis.PrimitiveKind fieldKind = javaPrimitiveKind(fieldType);
        if (fieldKind != ScopeAnalysis.PrimitiveKind.NONE) {
            unbox(fieldKind);
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

    private void emitDynamicFieldAssign(HirExpr.DynamicFieldAssign a) {
        int ln = a.meta().line();
        if (a.operator() == null) {
            emitBoxed(a.receiver());
            line(ln);
            ConstantPool.pushString(mv, a.receiverText());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            emitAndWiden(a.value());
            widenStackRank(a.meta().wideningRank());
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitLdcInsn(a.memberName());
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int scope = newLocal();
        int current = newLocal();
        emitBoxed(a.receiver());
        line(ln);
        ConstantPool.pushString(mv, a.receiverText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, scope);
        mv.visitVarInsn(ALOAD, scope);
        mv.visitLdcInsn(a.memberName());
        ConstantPool.pushString(mv, a.receiverText());
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, a.operator(), a.targetText(), a.value(), a.valueText(), current);
        widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, scope);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(a.memberName());
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitCompound(int lineNumber, String operator, String targetText, HirExpr rhsExpr, String rhsText, int currentLocal) {
        int value = newLocal();
        emitBoxed(rhsExpr);
        mv.visitVarInsn(ASTORE, value);
        emitDynamicArithmetic(lineNumber, operator, targetText, rhsText, currentLocal, value);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- member selection

    private void emitThisFieldGet(HirExpr.ThisFieldGet g) {
        int ln = g.meta().line();
        ClassGen gen = g.classId() >= 0 ? classGens.get(g.classId()) : null;
        int idx = g.memberIndex();
        if (gen != null && idx >= 0) {
            ScopeAnalysis.PrimitiveKind mk = gen.memberKinds[idx];
            loadThisAs(g.receiver(), gen);
            if (mk.isPrimitive()) {
                if (gen.primDefBits[idx] >= 0) {
                    int recv = newLocal();
                    mv.visitVarInsn(ASTORE, recv);
                    gen.emitCheckDefined(mv, recv, idx, g.memberName(), ln);
                    mv.visitVarInsn(ALOAD, recv);
                }
                mv.visitFieldInsn(GETFIELD, gen.name, g.memberName(), jvmDescriptor(mk));
                stackKind = mk;
                checkSelectorType(g.meta());
                return;
            }
            mv.visitFieldInsn(GETFIELD, gen.name, g.memberName(), "Ljava/lang/Object;");
            mv.visitLdcInsn(g.memberName());
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        } else {
            emitBoxed(g.receiver());
            mv.visitLdcInsn(g.memberName());
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        checkSelectorType(g.meta());
    }

    private void emitJavaFieldGet(HirExpr.JavaFieldGet g) {
        int ln = g.meta().line();
        ScopeAnalysis.JavaFieldTarget target = g.target();
        Field field = target.field();
        String owner = Type.getInternalName(field.getDeclaringClass());
        if (target.isStatic()) {
            if (!g.pureStaticReceiver()) {
                emitCheckedJavaReceiver(g.receiver(), g.receiverText(), ln, false, OBJECT, "notAScope", null);
                mv.visitInsn(POP);
            }
            line(ln);
            mv.visitFieldInsn(GETSTATIC, owner, field.getName(), Type.getDescriptor(field.getType()));
        } else {
            emitCheckedJavaReceiver(g.receiver(), g.receiverText(), ln, target.unwrapReceiver(), owner, "notAScope", null);
            line(ln);
            mv.visitFieldInsn(GETFIELD, owner, field.getName(), Type.getDescriptor(field.getType()));
        }
        emitJavaValueToBabbleStack(field.getType());
        checkSelectorType(g.meta());
    }

    private void emitArrayCreate(HirExpr.ArrayCreate c) {
        int ln = c.meta().line();
        ScopeAnalysis.PrimitiveKind elemKind = c.elemKind();
        switch (c.kind()) {
            case SIZE -> {
                emitAs(c.args().get(0), ScopeAnalysis.PrimitiveKind.INT);
                line(ln);
                emitNewArray(elemKind, c.componentInternalName());
            }
            case OF -> {
                ConstantPool.pushInt(mv, c.args().size());
                line(ln);
                emitNewArray(elemKind, c.componentInternalName());
                int arrLocal = newLocal();
                mv.visitVarInsn(ASTORE, arrLocal);
                for (int i = 0; i < c.args().size(); i++) {
                    mv.visitVarInsn(ALOAD, arrLocal);
                    ConstantPool.pushInt(mv, i);
                    if (elemKind.isPrimitive()) {
                        emitAs(c.args().get(i), elemKind);
                    } else {
                        emitBoxed(c.args().get(i));
                        if (c.componentIsJavaInterface() && c.componentClass() != null) {
                            mv.visitLdcInsn(Type.getType(c.componentClass()));
                            mv.visitVarInsn(ALOAD, rtLocal);
                            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava",
                                    "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                        } else {
                            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        }
                    }
                    line(ln);
                    mv.visitInsn(astoreInsn(elemKind));
                }
                mv.visitVarInsn(ALOAD, arrLocal);
            }
            case FROM -> {
                if (elemKind.isPrimitive()) {
                    pushPrimitiveClass(elemKind);
                } else {
                    mv.visitLdcInsn(Type.getObjectType(c.componentInternalName()));
                }
                emitBoxed(c.args().get(0));
                mv.visitVarInsn(ALOAD, rtLocal);
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "from",
                        "(Ljava/lang/Class;Ljava/lang/Object;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
            }
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitArrayOp(HirExpr.ArrayOp op) {
        int ln = op.meta().line();
        ScopeAnalysis.PrimitiveKind elemKind = op.elemKind();
        String castName = elemKind.isPrimitive() ? Type.getInternalName(op.arrayClass()) : "[Ljava/lang/Object;";
        switch (op.kind()) {
            case SIZE -> {
                emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                line(ln);
                mv.visitInsn(ARRAYLENGTH);
                stackKind = ScopeAnalysis.PrimitiveKind.INT;
            }
            case GET -> {
                emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                emitAs(op.args().get(0), ScopeAnalysis.PrimitiveKind.INT);
                line(ln);
                if (elemKind.isPrimitive()) {
                    mv.visitInsn(aloadInsn(elemKind));
                    stackKind = elemKind;
                } else {
                    mv.visitInsn(AALOAD);
                    mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                }
            }
            case SET -> {
                emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                emitAs(op.args().get(0), ScopeAnalysis.PrimitiveKind.INT);
                if (elemKind.isPrimitive()) {
                    emitAs(op.args().get(1), elemKind);
                } else {
                    emitBoxed(op.args().get(1));
                    if (op.componentClass() != null && op.componentClass().isInterface()) {
                        mv.visitLdcInsn(Type.getType(op.componentClass()));
                        mv.visitVarInsn(ALOAD, rtLocal);
                        mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava",
                                "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                    } else {
                        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    }
                }
                line(ln);
                mv.visitInsn(astoreInsn(elemKind));
                mv.visitInsn(ACONST_NULL);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            }
            case EQUALS -> {
                emitBoxed(op.receiver());
                emitBoxed(op.args().get(0));
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepEquals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            }
            case HASH_CODE -> {
                emitBoxed(op.receiver());
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepHashCode", "(Ljava/lang/Object;)I", false);
                stackKind = ScopeAnalysis.PrimitiveKind.INT;
            }
            case TO_STRING -> {
                emitBoxed(op.receiver());
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepToString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            }
        }
        finishCallCheck(op.meta());
    }

    private void emitNewArray(ScopeAnalysis.PrimitiveKind elemKind, String componentInternalName) {
        if (elemKind.isPrimitive()) {
            int code = switch (elemKind) {
                case BYTE -> T_BYTE;
                case SHORT -> T_SHORT;
                case INT -> T_INT;
                case LONG -> T_LONG;
                case FLOAT -> T_FLOAT;
                case DOUBLE -> T_DOUBLE;
                case BOOL -> T_BOOLEAN;
                case CHAR -> T_CHAR;
                case NONE -> throw new IllegalStateException();
            };
            mv.visitIntInsn(NEWARRAY, code);
        } else {
            mv.visitTypeInsn(ANEWARRAY, componentInternalName);
        }
    }

    private void pushPrimitiveClass(ScopeAnalysis.PrimitiveKind elemKind) {
        String wrapper = switch (elemKind) {
            case BYTE -> "java/lang/Byte";
            case SHORT -> "java/lang/Short";
            case INT -> "java/lang/Integer";
            case LONG -> "java/lang/Long";
            case FLOAT -> "java/lang/Float";
            case DOUBLE -> "java/lang/Double";
            case BOOL -> "java/lang/Boolean";
            case CHAR -> "java/lang/Character";
            case NONE -> throw new IllegalStateException();
        };
        mv.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
    }

    private static int aloadInsn(ScopeAnalysis.PrimitiveKind elemKind) {
        return switch (elemKind) {
            case BYTE, BOOL -> BALOAD;
            case SHORT -> SALOAD;
            case INT -> IALOAD;
            case LONG -> LALOAD;
            case FLOAT -> FALOAD;
            case DOUBLE -> DALOAD;
            case CHAR -> CALOAD;
            case NONE -> AALOAD;
        };
    }

    private static int astoreInsn(ScopeAnalysis.PrimitiveKind elemKind) {
        return switch (elemKind) {
            case BYTE, BOOL -> BASTORE;
            case SHORT -> SASTORE;
            case INT -> IASTORE;
            case LONG -> LASTORE;
            case FLOAT -> FASTORE;
            case DOUBLE -> DASTORE;
            case CHAR -> CASTORE;
            case NONE -> AASTORE;
        };
    }

    private void emitModuleExportGet(HirExpr.ModuleExportGet g) {
        emitBoxed(g.receiver());
        mv.visitTypeInsn(CHECKCAST, RUNTIME + "Module");
        ConstantPool.pushInt(mv, g.target().index());
        line(g.meta().line());
        mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "Module", "exportAt", "(I)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        checkSelectorType(g.meta());
    }

    private void emitDynamicSelect(HirExpr.DynamicSelect s) {
        int ln = s.meta().line();
        if (s.safe()) {
            Label nonNull = new Label();
            Label end = new Label();
            emitBoxed(s.receiver());
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, nonNull);
            mv.visitInsn(POP);
            mv.visitInsn(ACONST_NULL);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(nonNull);
            selectReceiverMember(ln, s.receiverText(), s.memberName());
            if (s.meta().checkedType() != null) {
                line(ln);
                checkType(s.meta().checkedType());
            }
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        emitBoxed(s.receiver());
        selectReceiverMember(ln, s.receiverText(), s.memberName());
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        checkSelectorType(s.meta());
    }

    private void checkSelectorType(org.babblelang.ir.hir.ExprMeta meta) {
        String checked = meta.checkedType();
        if (checked != null) {
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(meta.line());
            checkType(checked);
        }
    }

    private void selectReceiverMember(int lineNumber, String text, String name) {
        line(lineNumber);
        if (text.length() <= ConstantPool.MAX_STRING_CONSTANT) {
            mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, name, text);
        } else {
            mv.visitLdcInsn(name);
            ConstantPool.pushString(mv, text);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void loadThisAs(HirExpr thisExpr, ClassGen gen) {
        emitBoxed(thisExpr);
        mv.visitTypeInsn(CHECKCAST, gen.name);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- operators & conditions

    private void emitUnary(HirExpr.Unary u) {
        if (u.op() == HirExpr.UnaryOp.NOT) {
            Label isTrue = new Label();
            Label end = new Label();
            emitCondition(u.operand(), false, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            return;
        }
        ScopeAnalysis.PrimitiveKind ok = u.operand().meta().kind();
        ScopeAnalysis.PrimitiveKind rk = u.meta().kind();
        if (rk == ScopeAnalysis.PrimitiveKind.INT && isNumericKind(ok) && numericOrder(ok) <= 2) {
            emitAs(u.operand(), ScopeAnalysis.PrimitiveKind.INT);
            mv.visitInsn(INEG);
            stackKind = ScopeAnalysis.PrimitiveKind.INT;
            return;
        }
        if (rk == ScopeAnalysis.PrimitiveKind.LONG && isNumericKind(ok) && numericOrder(ok) <= 3) {
            emitAs(u.operand(), ScopeAnalysis.PrimitiveKind.LONG);
            mv.visitInsn(LNEG);
            stackKind = ScopeAnalysis.PrimitiveKind.LONG;
            return;
        }
        if (rk == ScopeAnalysis.PrimitiveKind.FLOAT && isNumericKind(ok) && numericOrder(ok) <= 4) {
            emitAs(u.operand(), ScopeAnalysis.PrimitiveKind.FLOAT);
            mv.visitInsn(FNEG);
            stackKind = ScopeAnalysis.PrimitiveKind.FLOAT;
            return;
        }
        if (rk == ScopeAnalysis.PrimitiveKind.DOUBLE && isNumericKind(ok)) {
            emitAs(u.operand(), ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitInsn(DNEG);
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }
        int operand = newLocal();
        emitBoxed(u.operand());
        mv.visitVarInsn(ASTORE, operand);
        Label number = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, operand);
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, number);
        emitOperatorCall(u.meta().line(), u.operandText(), Operators.NEGATE, null, operand, -1);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(number);
        mv.visitVarInsn(ALOAD, operand);
        mv.visitLdcInsn(u.operandText());
        line(u.meta().line());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", "negate", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitBinary(HirExpr.Binary b) {
        if (b.op().isComparison() && canLowerComparison(b)) {
            Label isTrue = new Label();
            Label end = new Label();
            emitComparison(b, true, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            return;
        }

        ScopeAnalysis.PrimitiveKind resultKind = b.meta().kind();
        ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
        ScopeAnalysis.PrimitiveKind rk = b.right().meta().kind();
        if (isNumericKind(lk) && isNumericKind(rk)
                && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL)) {
            if (resultKind == ScopeAnalysis.PrimitiveKind.INT) {
                emitAs(b.left(), ScopeAnalysis.PrimitiveKind.INT);
                emitAs(b.right(), ScopeAnalysis.PrimitiveKind.INT);
                mv.visitInsn(switch (b.op()) {
                    case ADD -> IADD;
                    case SUB -> ISUB;
                    case MUL -> IMUL;
                    default -> throw new IllegalStateException();
                });
                stackKind = ScopeAnalysis.PrimitiveKind.INT;
                return;
            }
            if (resultKind == ScopeAnalysis.PrimitiveKind.LONG) {
                emitAs(b.left(), ScopeAnalysis.PrimitiveKind.LONG);
                emitAs(b.right(), ScopeAnalysis.PrimitiveKind.LONG);
                mv.visitInsn(switch (b.op()) {
                    case ADD -> LADD;
                    case SUB -> LSUB;
                    case MUL -> LMUL;
                    default -> throw new IllegalStateException();
                });
                stackKind = ScopeAnalysis.PrimitiveKind.LONG;
                return;
            }
        }

        if (resultKind == ScopeAnalysis.PrimitiveKind.FLOAT && isNumericKind(lk) && isNumericKind(rk) && b.op().isArithmetic()) {
            emitAs(b.left(), ScopeAnalysis.PrimitiveKind.FLOAT);
            emitAs(b.right(), ScopeAnalysis.PrimitiveKind.FLOAT);
            mv.visitInsn(switch (b.op()) {
                case ADD -> FADD;
                case SUB -> FSUB;
                case MUL -> FMUL;
                case DIV -> FDIV;
                default -> throw new IllegalStateException();
            });
            stackKind = ScopeAnalysis.PrimitiveKind.FLOAT;
            return;
        }

        if (resultKind == ScopeAnalysis.PrimitiveKind.DOUBLE && isNumericKind(lk) && isNumericKind(rk) && b.op().isArithmetic()) {
            emitAs(b.left(), ScopeAnalysis.PrimitiveKind.DOUBLE);
            emitAs(b.right(), ScopeAnalysis.PrimitiveKind.DOUBLE);
            mv.visitInsn(switch (b.op()) {
                case ADD -> DADD;
                case SUB -> DSUB;
                case MUL -> DMUL;
                case DIV -> DDIV;
                default -> throw new IllegalStateException();
            });
            stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            return;
        }

        String method = switch (b.op()) {
            case MUL -> "mul";
            case DIV -> "div";
            case ADD -> "add";
            case SUB -> "sub";
            case LT -> "lt";
            case LTE -> "le";
            case EQ -> "eq";
            case NEQ -> "ne";
            case GTE -> "ge";
            case GT -> "gt";
        };
        String operatorMethod = Operators.method(b.op().symbol());
        if (operatorMethod == null) {
            emitBoxed(b.left());
            emitBoxed(b.right());
            mv.visitFieldInsn(GETSTATIC, className, constantPool.opSiteField(b.leftText(), b.rightText()), "L" + OP_SITE + ";");
            line(b.meta().line());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        int left = newLocal();
        int right = newLocal();
        emitBoxed(b.left());
        mv.visitVarInsn(ASTORE, left);
        emitBoxed(b.right());
        mv.visitVarInsn(ASTORE, right);
        emitDynamicArithmetic(b.meta().line(), b.op().symbol(), b.leftText(), b.rightText(), left, right);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitDynamicArithmetic(int lineNumber, String operator, String leftText, String rightText, int left, int right) {
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
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, numbers);
        emitOperatorCall(lineNumber, leftText, Operators.method(operator), rightText, left, right);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(numbers);
        mv.visitVarInsn(ALOAD, left);
        mv.visitVarInsn(ALOAD, right);
        mv.visitFieldInsn(GETSTATIC, className, constantPool.opSiteField(leftText, rightText), "L" + OP_SITE + ";");
        line(lineNumber);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitOperatorCall(int lineNumber, String receiver, String method, String argumentText, int receiverLocal, int argument) {
        String[] argTexts = argumentText == null ? new String[0] : new String[]{argumentText};
        CallSite site = new CallSite(sourceName, lineNumber, receiver + "." + method, argTexts);
        String field = constantPool.callSiteField(site);
        mv.visitVarInsn(ALOAD, receiverLocal);
        line(lineNumber);
        mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, method, receiver);
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        mv.visitVarInsn(ALOAD, rtLocal);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + CALL_SITE + ";");
        if (argument >= 0) {
            mv.visitVarInsn(ALOAD, argument);
        }
        line(lineNumber);
        mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                + (argument >= 0 ? "Ljava/lang/Object;" : "") + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitLogic(HirExpr.Logic l) {
        Label isTrue = new Label();
        Label end = new Label();
        emitCondition(l, true, isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
    }

    private void emitCondition(HirExpr expr, boolean branchIfTrue, Label target) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        if (expr instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
            emitCondition(u.operand(), !branchIfTrue, target);
            return;
        }
        if (expr instanceof HirExpr.Logic l) {
            boolean or = l.op() == HirExpr.LogicOp.OR;
            if (or == branchIfTrue) {
                emitCondition(l.left(), branchIfTrue, target);
                emitCondition(l.right(), branchIfTrue, target);
            } else {
                Label fallThrough = new Label();
                emitCondition(l.left(), !branchIfTrue, fallThrough);
                emitCondition(l.right(), branchIfTrue, target);
                mv.visitLabel(fallThrough);
            }
            return;
        }
        if (expr instanceof HirExpr.Binary b && b.op().isComparison() && canLowerComparison(b)) {
            emitComparison(b, branchIfTrue, target);
            return;
        }
        emitExpr(expr);
        switch (stackKind) {
            case BOOL, BYTE, SHORT, INT, CHAR -> mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            case LONG -> {
                mv.visitInsn(LCONST_0);
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case FLOAT -> {
                mv.visitInsn(FCONST_0);
                mv.visitInsn(FCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
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

    private static boolean canLowerComparison(HirExpr.Binary b) {
        ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
        ScopeAnalysis.PrimitiveKind rk = b.right().meta().kind();
        if (lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL) {
            return b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ;
        }
        if (lk == ScopeAnalysis.PrimitiveKind.CHAR && rk == ScopeAnalysis.PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    private void emitComparison(HirExpr.Binary b, boolean branchIfTrue, Label target) {
        ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
        ScopeAnalysis.PrimitiveKind rk = b.right().meta().kind();
        if ((lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL)
                || (lk == ScopeAnalysis.PrimitiveKind.CHAR && rk == ScopeAnalysis.PrimitiveKind.CHAR)) {
            emitAs(b.left(), lk);
            emitAs(b.right(), rk);
            mv.visitJumpInsn(icmpJump(b.op(), branchIfTrue), target);
            return;
        }
        ScopeAnalysis.PrimitiveKind common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
        if (numericOrder(common) <= 2) {
            emitAs(b.left(), common);
            emitAs(b.right(), common);
            mv.visitJumpInsn(icmpJump(b.op(), branchIfTrue), target);
            return;
        }
        emitAs(b.left(), common);
        emitAs(b.right(), common);
        if (common == ScopeAnalysis.PrimitiveKind.LONG) {
            mv.visitInsn(LCMP);
        } else if (common == ScopeAnalysis.PrimitiveKind.FLOAT) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
        }
        int jump = switch (b.op()) {
            case LT -> branchIfTrue ? IFLT : IFGE;
            case LTE -> branchIfTrue ? IFLE : IFGT;
            case EQ -> branchIfTrue ? IFEQ : IFNE;
            case NEQ -> branchIfTrue ? IFNE : IFEQ;
            case GTE -> branchIfTrue ? IFGE : IFLT;
            case GT -> branchIfTrue ? IFGT : IFLE;
            default -> throw new IllegalStateException();
        };
        mv.visitJumpInsn(jump, target);
    }

    private static int icmpJump(HirExpr.BinaryOp op, boolean branchIfTrue) {
        return switch (op) {
            case LT -> branchIfTrue ? IF_ICMPLT : IF_ICMPGE;
            case LTE -> branchIfTrue ? IF_ICMPLE : IF_ICMPGT;
            case EQ -> branchIfTrue ? IF_ICMPEQ : IF_ICMPNE;
            case NEQ -> branchIfTrue ? IF_ICMPNE : IF_ICMPEQ;
            case GTE -> branchIfTrue ? IF_ICMPGE : IF_ICMPLT;
            case GT -> branchIfTrue ? IF_ICMPGT : IF_ICMPLE;
            default -> throw new IllegalStateException();
        };
    }

    // ---------------------------------------------------------------- type operations

    private void emitIs(HirExpr.Is i) {
        int ln = i.meta().line();
        emitBoxed(i.left());
        if (i.builtin() != null) {
            mv.visitLdcInsn(i.builtin());
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "isBuiltin", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        String targetJvm = resolveIsTargetJvm(i.target());
        if (targetJvm != null) {
            if (i.target().jvmInternalName() != null) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            line(ln);
            mv.visitTypeInsn(INSTANCEOF, targetJvm);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            return;
        }
        if (i.descriptor() == null) {
            mv.visitInsn(POP);
            mv.visitInsn(ICONST_1);
            stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            return;
        }
        ConstantPool.pushString(mv, i.descriptor());
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        stackKind = ScopeAnalysis.PrimitiveKind.BOOL;
    }

    private String resolveIsTargetJvm(ScopeAnalysis.IsTarget target) {
        if (target == null) {
            return null;
        }
        if (target.jvmInternalName() != null) {
            return target.jvmInternalName();
        }
        return target.kind() == ClassShape.Kind.INTERFACE
                ? allInterfaceJvmNames.get(target.typeId())
                : allClassJvmNames.get(target.typeId());
    }

    private void emitAsExpr(HirExpr.As a) {
        if (a.targetDescriptor() == null) {
            emitAndWiden(a.left());
            return;
        }
        emitBoxed(a.left());
        String targetJvm = resolveIsTargetJvm(a.target());
        if (targetJvm != null) {
            Label slow = new Label();
            Label done = new Label();
            mv.visitInsn(DUP);
            mv.visitTypeInsn(INSTANCEOF, targetJvm);
            mv.visitJumpInsn(IFEQ, slow);
            mv.visitTypeInsn(CHECKCAST, targetJvm);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(slow);
            line(a.meta().line());
            checkType(a.targetDescriptor());
            mv.visitLabel(done);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        line(a.meta().line());
        checkType(a.targetDescriptor());
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitNonNull(HirExpr.NonNull n) {
        if (n.expectedType() == null) {
            emitExpr(n.operand());
            return;
        }
        emitBoxed(n.operand());
        mv.visitLdcInsn(n.expectedType());
        line(n.meta().line());
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        ScopeAnalysis.PrimitiveKind targetKind = n.meta().kind();
        if (targetKind.isPrimitive()) {
            unbox(targetKind);
            stackKind = targetKind;
        } else {
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
    }

    private void checkType(String descriptor) {
        String field = constantPool.typeField(descriptor);
        mv.visitFieldInsn(GETSTATIC, className, field, "L" + TYPES_TYPE + ";");
        mv.visitMethodInsn(INVOKESTATIC, TYPES, "check", "(Ljava/lang/Object;L" + TYPES_TYPE + ";)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- control flow

    private void emitBlock(HirExpr.Block b) {
        if (!b.declaredVars().isEmpty()) {
            inFrameScope(() -> {
                declareVariables(b.declaredVars());
                statements(b.statements());
            });
        } else if (b.opensNamespaceScope()) {
            mv.visitVarInsn(ALOAD, nsLocal);
            getStringArray(b.declaredNames());
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
            int namespace = newLocal();
            mv.visitVarInsn(ASTORE, namespace);
            withNamespace(namespace, () -> statements(b.statements()));
        } else {
            statements(b.statements());
        }
        widenStackRank(b.innerWideningRank());
    }

    private void emitIf(HirExpr.If i) {
        ScopeAnalysis.PrimitiveKind target = i.meta().kind();
        Label otherwise = new Label();
        Label end = new Label();
        emitCondition(i.cond(), false, otherwise);
        emitAs(i.thenBranch(), target);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(otherwise);
        if (i.elseBranch() != null) {
            emitAs(i.elseBranch(), target);
        } else {
            mv.visitInsn(ACONST_NULL);
            convertStack(ScopeAnalysis.PrimitiveKind.NONE, target);
        }
        mv.visitLabel(end);
        stackKind = target;
    }

    private void emitWhile(HirExpr.While w) {
        Label head = new Label();
        Label end = new Label();
        int result = newLocal();
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, result);
        mv.visitLabel(head);
        emitCondition(w.cond(), false, end);
        loopTargets.push(new LoopTarget(end, head, result));
        try {
            emitBoxed(w.body());
        } finally {
            loopTargets.pop();
        }
        mv.visitVarInsn(ASTORE, result);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitFor(HirExpr.For f) {
        int ln = f.meta().line();
        ScopeAnalysis.ForLoopTarget target = f.target();
        ScopeAnalysis.ForKind kind = target != null ? target.kind() : ScopeAnalysis.ForKind.DYNAMIC_UNION;
        Label head = new Label();
        Label end = new Label();
        int result = newLocal();
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, result);

        if (kind == ScopeAnalysis.ForKind.ARRAY && target.arrayClass() != null) {
            Class<?> arrClass = target.arrayClass();
            Class<?> comp = arrClass.getComponentType();
            String arrDesc = Type.getDescriptor(arrClass);
            int arrLocal = newLocal();
            int lenLocal = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            int idxLocal = newLocal(ScopeAnalysis.PrimitiveKind.INT);
            Label step = new Label();

            emitBoxed(f.iterable());
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, arrDesc);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ASTORE, arrLocal);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitVarInsn(ISTORE, lenLocal);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, idxLocal);

            mv.visitLabel(head);
            mv.visitVarInsn(ILOAD, idxLocal);
            mv.visitVarInsn(ILOAD, lenLocal);
            mv.visitJumpInsn(IF_ICMPGE, end);

            mv.visitVarInsn(ALOAD, arrLocal);
            mv.visitVarInsn(ILOAD, idxLocal);
            ScopeAnalysis.PrimitiveKind elemStackKind;
            if (comp == int.class) {
                mv.visitInsn(IALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.INT;
            } else if (comp == long.class) {
                mv.visitInsn(LALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.LONG;
            } else if (comp == double.class) {
                mv.visitInsn(DALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            } else if (comp == float.class) {
                mv.visitInsn(FALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.FLOAT;
            } else if (comp == byte.class) {
                mv.visitInsn(BALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.BYTE;
            } else if (comp == short.class) {
                mv.visitInsn(SALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.SHORT;
            } else if (comp == char.class) {
                mv.visitInsn(CALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.CHAR;
            } else if (comp == boolean.class) {
                mv.visitInsn(BALOAD);
                elemStackKind = ScopeAnalysis.PrimitiveKind.BOOL;
            } else {
                mv.visitInsn(AALOAD);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                elemStackKind = ScopeAnalysis.PrimitiveKind.NONE;
            }
            emitForIterationBody(f, result, end, step, elemStackKind);
            mv.visitLabel(step);
            mv.visitIincInsn(idxLocal, 1);
            mv.visitJumpInsn(GOTO, head);
            mv.visitLabel(end);
            mv.visitVarInsn(ALOAD, result);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }

        if (kind == ScopeAnalysis.ForKind.JAVA_ITERABLE || kind == ScopeAnalysis.ForKind.JAVA_ITERATOR
                || kind == ScopeAnalysis.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            int itLocal = newLocal();
            if (kind == ScopeAnalysis.ForKind.BABBLE_TO_JAVA_ITERABLE) {
                int recvLocal = newLocal();
                emitBoxed(f.iterable());
                mv.visitVarInsn(ASTORE, recvLocal);
                emitZeroArgBabbleCall(recvLocal, "iterator", null, ln);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
            } else {
                emitBoxed(f.iterable());
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                if (kind == ScopeAnalysis.ForKind.JAVA_ITERABLE) {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                    line(ln);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
                } else {
                    mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                }
            }
            mv.visitVarInsn(ASTORE, itLocal);

            mv.visitLabel(head);
            mv.visitVarInsn(ALOAD, itLocal);
            line(ln);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
            mv.visitJumpInsn(IFEQ, end);
            mv.visitVarInsn(ALOAD, itLocal);
            line(ln);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            emitForIterationBody(f, result, end, head, ScopeAnalysis.PrimitiveKind.NONE);
            mv.visitJumpInsn(GOTO, head);
            mv.visitLabel(end);
            mv.visitVarInsn(ALOAD, result);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }

        if (kind == ScopeAnalysis.ForKind.BABBLE_ITERABLE || kind == ScopeAnalysis.ForKind.BABBLE_ITERATOR) {
            int iterLocal = newLocal();
            emitBoxed(f.iterable());
            mv.visitVarInsn(ASTORE, iterLocal);
            if (kind == ScopeAnalysis.ForKind.BABBLE_ITERABLE) {
                emitZeroArgBabbleCall(iterLocal, "iterator", null, ln);
                convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, iterLocal);
            }
            mv.visitLabel(head);
            emitZeroArgBabbleCall(iterLocal, "hasNext", target != null ? target.hasNextSig() : null, ln);
            if (stackKind == ScopeAnalysis.PrimitiveKind.BOOL) {
                mv.visitJumpInsn(IFEQ, end);
            } else {
                convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, end);
            }
            emitZeroArgBabbleCall(iterLocal, "next", target != null ? target.nextSig() : null, ln);
            emitForIterationBody(f, result, end, head, stackKind);
            mv.visitJumpInsn(GOTO, head);
            mv.visitLabel(end);
            mv.visitVarInsn(ALOAD, result);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }

        // DYNAMIC_UNION
        int rawLocal = newLocal();
        int idxLocal = newLocal(ScopeAnalysis.PrimitiveKind.INT);
        int lenLocal = newLocal(ScopeAnalysis.PrimitiveKind.INT);
        int itLocal = newLocal();
        Label arrLoop = new Label();
        Label arrStep = new Label();
        Label notArr = new Label();
        Label notIter = new Label();
        Label iterLoop = new Label();

        emitBoxed(f.iterable());
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, rawLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isArray", "()Z", false);
        mv.visitJumpInsn(IFEQ, notArr);

        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Array", "getLength", "(Ljava/lang/Object;)I", false);
        mv.visitVarInsn(ISTORE, lenLocal);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, idxLocal);
        mv.visitLabel(arrLoop);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitVarInsn(ILOAD, lenLocal);
        mv.visitJumpInsn(IF_ICMPGE, end);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Array", "get", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, arrStep, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitLabel(arrStep);
        mv.visitIincInsn(idxLocal, 1);
        mv.visitJumpInsn(GOTO, arrLoop);

        mv.visitLabel(notArr);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(INSTANCEOF, "java/util/Iterator");
        mv.visitJumpInsn(IFEQ, notIter);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
        mv.visitVarInsn(ASTORE, itLocal);
        mv.visitJumpInsn(GOTO, iterLoop);

        mv.visitLabel(notIter);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, itLocal);

        mv.visitLabel(iterLoop);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, iterLoop, ScopeAnalysis.PrimitiveKind.NONE);
        mv.visitJumpInsn(GOTO, iterLoop);

        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitForIterationBody(HirExpr.For f, int resultSlot, Label breakLabel, Label continueLabel,
                                      ScopeAnalysis.PrimitiveKind elemStackKind) {
        ScopeAnalysis.ForLoopTarget target = f.target();
        stackKind = elemStackKind;
        if (target != null && target.elemCheck() != null) {
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(f.meta().line());
            checkType(target.elemCheck());
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
        widenStackRank(f.meta().wideningRank());
        ScopeAnalysis.PrimitiveKind varKind = target != null ? target.elemKind() : ScopeAnalysis.PrimitiveKind.NONE;
        convertStack(stackKind, varKind);
        int elemTmp = newLocal(varKind);
        storeLocal(varKind, elemTmp);

        loopTargets.push(new LoopTarget(breakLabel, continueLabel, resultSlot));
        try {
            inFrameScope(() -> {
                declareVariables(f.bodyDeclaredVars());
                if (f.loopVar() != null) {
                    loadLocal(varKind, elemTmp);
                    initVariable(f.loopVar(), varKind);
                }
                statements(f.body());
                widenStackRank(f.bodyWideningRank());
                convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, resultSlot);
            });
        } finally {
            loopTargets.pop();
        }
    }

    private void emitZeroArgBabbleCall(int receiverLocal, String memberName,
                                       ScopeAnalysis.FunctionSignature sig, int lineNumber) {
        String site = constantPool.callSiteField(new CallSite(sourceName, lineNumber, memberName, new String[0]));
        mv.visitVarInsn(ALOAD, receiverLocal);
        line(lineNumber);
        mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, memberName, memberName);
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        mv.visitVarInsn(ALOAD, rtLocal);
        if (sig != null && sig.returnKind().isPrimitive()) {
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            String desc = "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";L" + CALL_SITE + ";)" + jvmDescriptor(sig.returnKind());
            line(lineNumber);
            mv.visitInvokeDynamicInsn("typedCall", desc, TYPED_CALL_BOOTSTRAP);
            stackKind = sig.returnKind();
        } else {
            mv.visitInsn(ACONST_NULL);
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            line(lineNumber);
            mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", CALL_BOOTSTRAP);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
    }

    private void emitBreak(HirExpr.Break b) {
        LoopTarget loop = loopTargets.peek();
        if (b.value() != null) {
            emitAndWiden(b.value());
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ASTORE, loop.resultSlot());
        mv.visitJumpInsn(GOTO, loop.breakLabel());
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitContinue() {
        LoopTarget loop = loopTargets.peek();
        mv.visitJumpInsn(GOTO, loop.continueLabel());
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitReturn(HirExpr.Return r) {
        emitAndWiden(r.value());
        widenStackRank(r.meta().wideningRank());
        convertStack(stackKind, methodReturnKind);
        mv.visitInsn(returnInsn(methodReturnKind));
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- calls

    private void finishCallCheck(org.babblelang.ir.hir.ExprMeta meta) {
        String checked = meta.checkedType();
        if (checked != null) {
            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
            line(meta.line());
            checkType(checked);
        }
    }

    private void emitCoreCollectionCall(HirExpr.CoreCollectionCall c) {
        String kind = c.collectionKind();
        List<HirCallArg> params = c.args();
        int count = params.size();
        String n0 = count > 0 ? params.get(0).name() : null;
        String n1 = count > 1 ? params.get(1).name() : null;
        String coreCollections = RUNTIME + "CoreCollections";
        line(c.meta().line());
        if ("entry".equals(kind)) {
            if (count == 2) {
                int keyIdx = "value".equals(n0) && "key".equals(n1) ? 1 : 0;
                int valIdx = 1 - keyIdx;
                emitBoxed(params.get(keyIdx).value());
                emitBoxed(params.get(valIdx).value());
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "entry", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map$Entry;", false);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                finishCallCheck(c.meta());
                return;
            }
        } else if (count == 0) {
            String method = switch (kind) {
                case "list" -> "listEmpty";
                case "set" -> "setEmpty";
                case "sortedset" -> "sortedSetEmpty";
                case "map" -> "mapEmpty";
                case "sortedmap" -> "sortedMapEmpty";
                default -> throw new IllegalStateException("Unknown core collection : " + kind);
            };
            String desc = switch (kind) {
                case "list" -> "()Ljava/util/List;";
                case "set" -> "()Ljava/util/Set;";
                case "sortedset" -> "()Ljava/util/SortedSet;";
                case "map" -> "()Ljava/util/Map;";
                case "sortedmap" -> "()Ljava/util/SortedMap;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "capacity".equals(n0)) {
            emitAs(params.get(0).value(), ScopeAnalysis.PrimitiveKind.INT);
            String method = switch (kind) {
                case "list" -> "listCapacity";
                case "set" -> "setCapacity";
                case "map" -> "mapCapacity";
                default -> throw new IllegalStateException("capacity not supported for " + kind);
            };
            String desc = switch (kind) {
                case "list" -> "(I)Ljava/util/List;";
                case "set" -> "(I)Ljava/util/Set;";
                case "map" -> "(I)Ljava/util/Map;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "comparator".equals(n0)) {
            emitBoxed(params.get(0).value());
            mv.visitVarInsn(ALOAD, rtLocal);
            String method = "sortedset".equals(kind) ? "sortedSetComparator" : "sortedMapComparator";
            String desc = "sortedset".equals(kind)
                    ? "(Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;"
                    : "(Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;";
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "from".equals(n0)) {
            emitBoxed(params.get(0).value());
            if ("map".equals(kind) || "sortedmap".equals(kind)) {
                mv.visitTypeInsn(CHECKCAST, "java/util/Map");
            } else {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
            }
            switch (kind) {
                case "list" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "listFrom", "(Ljava/lang/Iterable;)Ljava/util/List;", false);
                case "set" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "setFrom", "(Ljava/lang/Iterable;)Ljava/util/Set;", false);
                case "map" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "mapFrom", "(Ljava/util/Map;)Ljava/util/Map;", false);
                case "sortedset" -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ALOAD, rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedSetFrom",
                            "(Ljava/lang/Iterable;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;", false);
                }
                case "sortedmap" -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ALOAD, rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedMapFrom",
                            "(Ljava/util/Map;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;", false);
                }
                default -> throw new IllegalStateException();
            }
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 2 && (("from".equals(n0) && "comparator".equals(n1)) || ("comparator".equals(n0) && "from".equals(n1)))) {
            int fromIdx = "from".equals(n0) ? 0 : 1;
            int compIdx = 1 - fromIdx;
            emitBoxed(params.get(fromIdx).value());
            if ("sortedmap".equals(kind)) {
                mv.visitTypeInsn(CHECKCAST, "java/util/Map");
            } else {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
            }
            emitBoxed(params.get(compIdx).value());
            mv.visitVarInsn(ALOAD, rtLocal);
            if ("sortedset".equals(kind)) {
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedSetFrom",
                        "(Ljava/lang/Iterable;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedMapFrom",
                        "(Ljava/util/Map;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;", false);
            }
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        if (n0 == null && !"entry".equals(kind)) {
            emitArgumentsArray(params);
            String method = switch (kind) {
                case "list" -> "listOf";
                case "set" -> "setOf";
                case "sortedset" -> "sortedSetOf";
                case "map" -> "mapOf";
                case "sortedmap" -> "sortedMapOf";
                default -> throw new IllegalStateException();
            };
            String desc = switch (kind) {
                case "list" -> "([Ljava/lang/Object;)Ljava/util/List;";
                case "set" -> "([Ljava/lang/Object;)Ljava/util/Set;";
                case "sortedset" -> "([Ljava/lang/Object;)Ljava/util/SortedSet;";
                case "map" -> "([Ljava/lang/Object;)Ljava/util/Map;";
                case "sortedmap" -> "([Ljava/lang/Object;)Ljava/util/SortedMap;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        mv.visitLdcInsn(kind);
        emitArgumentsArray(params);
        String[] names = callArgNames(params);
        if (names != null) {
            getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ALOAD, rtLocal);
        mv.visitMethodInsn(INVOKESTATIC, coreCollections, "construct",
                "(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;L" + RUNTIME + "ScriptRuntime;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitSuperCall(HirExpr.SuperCall c) {
        int ln = c.meta().line();
        List<HirCallArg> args = c.args();
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        String[] names = binding != null ? null : callArgNames(args);
        ScopeAnalysis.SuperTargetInfo info = names == null ? c.targetInfo() : null;
        if (info != null && info.directBabbleTypeId() != null && info.providerBabbleTypeId() != null) {
            String directJvm = allInterfaceJvmNames.get(info.directBabbleTypeId());
            ScopeAnalysis.TypeHierarchyInfo provHierarchy = module.typeHierarchy(info.providerBabbleTypeId());
            ScopeAnalysis.MemberSpec provSpec = provHierarchy != null ? provHierarchy.interfaceMembers().get(info.memberName()) : null;
            ScopeAnalysis.FunctionSignature sig = provSpec != null && provSpec.callable() ? provSpec.declaredSignature() : null;
            int expectedParams = sig != null ? sig.parameterKinds().length : -1;
            boolean arityOk = sig != null && (binding != null ? binding.paramCount() == expectedParams : args.size() == expectedParams);
            if (directJvm != null && arityOk) {
                String desc = bridgeDescriptor(sig);
                if (interfaceHasMethod(info.directBabbleTypeId(), info.memberName(), desc)) {
                    List<HirExpr> argExprs = callArgExprs(args);
                    if (!info.ownerIsInterface() && methodClass != null
                            && methodClass.shape.id().equals(info.ownerTypeId())) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, rtLocal);
                        emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                        if (sig.hasOptionalMask()) {
                            ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                        }
                        line(ln);
                        mv.visitMethodInsn(INVOKESPECIAL, directJvm, bridgeMethodName(info.memberName()), desc, true);
                        stackKind = sig.returnKind();
                        finishCallCheck(c.meta());
                        return;
                    }
                    if (info.ownerIsInterface()) {
                        InterfaceGen ownerIface = interfaceGensByTypeId.get(info.ownerTypeId());
                        if (ownerIface != null) {
                            String helper = ownerIface.superHelper(directJvm, info.memberName(), sig);
                            loadVarOrName(c.thisVar(), "this", ln);
                            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerIface.name);
                            mv.visitVarInsn(ALOAD, rtLocal);
                            emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                            if (sig.hasOptionalMask()) {
                                ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                            }
                            line(ln);
                            mv.visitMethodInsn(INVOKEINTERFACE, ownerIface.name, helper, desc, true);
                            stackKind = sig.returnKind();
                            finishCallCheck(c.meta());
                            return;
                        }
                    } else if (c.ownerClassId() >= 0) {
                        ClassGen ownerCls = classGens.get(c.ownerClassId());
                        if (ownerCls != null) {
                            String helper = ownerCls.superHelper(directJvm, info.memberName(), sig);
                            loadVarOrName(c.thisVar(), "this", ln);
                            convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerCls.name);
                            mv.visitVarInsn(ALOAD, rtLocal);
                            emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                            if (sig.hasOptionalMask()) {
                                ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                            }
                            line(ln);
                            mv.visitMethodInsn(INVOKEVIRTUAL, ownerCls.name, helper, desc, false);
                            stackKind = sig.returnKind();
                            finishCallCheck(c.meta());
                            return;
                        }
                    }
                }
            }
        }

        String site = constantPool.callSiteField(c.callSite());
        loadVarOrName(c.thisVar(), "this", ln);
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        emitTypeRef(c.superTypeRef());
        mv.visitLdcInsn(c.memberName());
        mv.visitVarInsn(ALOAD, rtLocal);
        if (binding != null) {
            emitBoundArgumentsArray(callArgExprs(args), binding);
            mv.visitInsn(ACONST_NULL);
        } else {
            emitArgumentsArray(args);
            if (names != null) {
                getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Supers", "call", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;L" + SCRIPT_RUNTIME
                + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    /**
     * Where a self tail call of the method's function jumps (docs/design/bip024_tail-calls.md) : before
     * the frame is entered, so that each activation gets a fresh frame, declarations, cells, default
     * values and parameter bindings. {@code paramSlots} are the parameters of a {@code $spec} method,
     * null for the direct entry point (spread parameters, or the arguments array and names).
     */
    private record TailLoop(Label head, int[] paramSlots, int maskSlot) {
    }

    private void beginTailLoop(int[] paramSlots, int maskSlot) {
        tailLoop = new TailLoop(new Label(), paramSlots, maskSlot);
        mv.visitLabel(tailLoop.head());
    }

    /**
     * A self tail call : the arguments are evaluated as for a call, then stored back into the method's
     * own parameters, and the method starts again. The analysis only marks calls in statement position
     * (BIP024-D3), so the operand stack is empty at the jump, as at a loop head.
     */
    private void emitSelfTailCall(HirExpr.SelfTailCall c) {
        HirFunction fn = module.function(c.functionId());
        TailLoop loop = tailLoop;
        if (loop == null || methodOwnerId != fn.id()) {
            throw new IllegalStateException("Self tail call of function #" + fn.id() + " outside of its body at line " + c.meta().line());
        }
        List<HirExpr> args = callArgExprs(c.args());
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        int count = fn.params().size();
        if (loop.paramSlots() != null) {
            ScopeAnalysis.PrimitiveKind[] kinds = paramKindsOf(fn);
            emitBoundTypedArgs(args, binding, kinds);
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                mv.visitVarInsn(ISTORE, loop.maskSlot());
            }
            for (int i = count - 1; i >= 0; i--) {
                storeLocal(kinds[i], loop.paramSlots()[i]);
            }
        } else if (fn.isSpread()) {
            emitBoundBoxedArgs(args, binding, count);
            for (int i = count - 1; i >= 0; i--) {
                mv.visitVarInsn(ASTORE, FIRST_PARAMETER + i);
            }
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, BINDER_LOCAL);
        } else {
            if (binding != null) {
                emitBoundArgumentsArray(args, binding);
            } else {
                emitArgumentsArray(c.args());
            }
            mv.visitVarInsn(ASTORE, ARGS);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, NAMES);
        }
        line(c.meta().line());
        mv.visitJumpInsn(GOTO, loop.head());
        mv.visitInsn(ACONST_NULL);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitSelfRecurseCall(HirExpr.SelfRecurseCall c) {
        HirFunction fn = module.function(c.functionId());
        ensureFunctionScheduled(fn);
        int count = fn.params().size();
        int ln = c.meta().line();
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        if (fn.hasSpec() && (binding != null ? binding.paramCount() == count : c.args().size() == count)) {
            ScopeAnalysis.PrimitiveKind[] kinds = paramKindsOf(fn);
            if (methodClass != null) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, selfLocal);
                mv.visitVarInsn(ALOAD, rtLocal);
                emitBoundTypedArgs(c.args(), binding, kinds);
                if (fn.hasOptionalMask()) {
                    ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                }
                line(ln);
                mv.visitMethodInsn(INVOKEVIRTUAL, methodClass.name, methodClass.methodName(fn.memberName()) + "$spec",
                        methodSpecDescriptor(fn), false);
            } else {
                mv.visitVarInsn(ALOAD, selfLocal);
                mv.visitVarInsn(ALOAD, thizLocal);
                mv.visitVarInsn(ALOAD, rtLocal);
                emitBoundTypedArgs(c.args(), binding, kinds);
                if (fn.hasOptionalMask()) {
                    ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                }
                line(ln);
                mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName() + "$spec",
                        functionSpecDescriptor(fn), false);
            }
            stackKind = fn.returnKind();
            finishCallCheck(c.meta());
            return;
        }
        if (methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, selfLocal);
            mv.visitVarInsn(ALOAD, rtLocal);
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, methodClass.name, methodClass.methodName(fn.memberName()),
                    methodDirectDescriptor(fn), false);
        } else {
            mv.visitVarInsn(ALOAD, selfLocal);
            mv.visitVarInsn(ALOAD, thizLocal);
            mv.visitVarInsn(ALOAD, rtLocal);
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitDirectBabbleCall(HirExpr.DirectBabbleCall c) {
        HirFunction fn = module.function(c.targetFunctionId());
        ensureFunctionScheduled(fn);
        int count = fn.params().size();
        int ln = c.meta().line();
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        emitBoxed(c.callee());
        mv.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
        mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNBOUND", "L" + RUNTIME + "Undefined;");
        mv.visitVarInsn(ALOAD, rtLocal);
        if (fn.hasSpec()) {
            emitBoundTypedArgs(c.args(), binding, paramKindsOf(fn));
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
            }
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName() + "$spec", functionSpecDescriptor(fn), false);
            stackKind = fn.returnKind();
        } else {
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            line(ln);
            mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
        finishCallCheck(c.meta());
    }

    private void emitDirectThisMethodCall(HirExpr.DirectThisMethodCall c) {
        ClassGen gen = classGens.get(c.classId());
        HirFunction fn = module.function(c.targetFunctionId());
        ensureFunctionScheduled(fn);
        int ln = c.meta().line();
        List<HirCallArg> args = c.args();
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        String[] names = binding != null ? null : callArgNames(args);
        if (c.receiverText() != null) {
            emitBoxed(c.receiver());
            Label ok = new Label();
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, ok);
            mv.visitInsn(POP);
            line(ln);
            ConstantPool.pushString(mv, c.receiverText());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(ok);
            mv.visitTypeInsn(CHECKCAST, gen.name);
        } else {
            loadThisAs(c.receiver(), gen);
        }
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, gen.name, c.memberName(), "Ljava/lang/Object;");
        mv.visitLdcInsn(c.memberName());
        line(ln);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        if (c.overloadAltIndex() >= 0) {
            ConstantPool.pushInt(mv, c.overloadAltIndex());
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "memberOf", "(Ljava/lang/Object;I)L" + CALLABLE + ";", false);
        }
        mv.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
        mv.visitVarInsn(ALOAD, rtLocal);
        String jvmMethod = fn.jvmMethodName();
        int count = fn.params().size();
        if (fn.hasSpec() && (binding != null ? binding.paramCount() == count : (names == null && args.size() == count))) {
            emitBoundTypedArgs(callArgExprs(args), binding, paramKindsOf(fn));
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
            }
            line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod + "$spec", methodSpecDescriptor(fn), false);
            stackKind = fn.returnKind();
            finishCallCheck(c.meta());
            return;
        }
        if (fn.isSpread() && (binding != null ? binding.paramCount() == count : (names == null && args.size() <= count))) {
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(callArgExprs(args), binding, count);
            line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod, methodDirectDescriptor(fn), false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        String site = constantPool.callSiteField(c.callSite());
        if (binding != null) {
            emitBoundArgumentsArray(callArgExprs(args), binding);
            mv.visitInsn(ACONST_NULL);
        } else {
            emitArgumentsArray(args);
            if (names != null) {
                getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(ln);
        mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod + (fn.isSpread() ? "$call" : ""), METHOD_DESCRIPTOR, false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitInterfaceCall(HirExpr.InterfaceCall c) {
        String ifaceJvm = allInterfaceJvmNames.get(c.interfaceTypeId());
        ScopeAnalysis.FunctionSignature sig = c.signature();
        ScopeAnalysis.ArgumentBinding binding = c.binding();
        emitBoxed(c.receiver());
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        line(c.selectorLine());
        ConstantPool.pushString(mv, c.receiverText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        mv.visitTypeInsn(CHECKCAST, ifaceJvm);
        mv.visitVarInsn(ALOAD, rtLocal);
        emitBoundTypedArgs(c.args(), binding, sig.parameterKinds());
        if (sig.hasOptionalMask()) {
            ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
        }
        line(c.meta().line());
        mv.visitMethodInsn(INVOKEINTERFACE, ifaceJvm, bridgeMethodName(c.memberName()), bridgeDescriptor(sig), true);
        stackKind = sig.returnKind();
        finishCallCheck(c.meta());
    }

    private int[] emitJavaArgsToLocals(List<HirExpr> args, Class<?>[] paramTypes, boolean varArgs, int ln) {
        int[] argLocals = new int[paramTypes.length];
        if (!varArgs) {
            for (int i = 0; i < paramTypes.length; i++) {
                argLocals[i] = emitJavaArgToLocal(args.get(i), paramTypes[i], ln);
            }
            return argLocals;
        }
        int fixed = paramTypes.length - 1;
        for (int i = 0; i < fixed; i++) {
            argLocals[i] = emitJavaArgToLocal(args.get(i), paramTypes[i], ln);
        }
        Class<?> comp = paramTypes[fixed].getComponentType();
        int varCount = args.size() - fixed;
        int[] varLocals = new int[varCount];
        for (int j = 0; j < varCount; j++) {
            varLocals[j] = emitJavaArgToLocal(args.get(fixed + j), comp, ln);
        }
        ConstantPool.pushInt(mv, varCount);
        if (comp.isPrimitive()) {
            mv.visitIntInsn(NEWARRAY, javaPrimitiveArrayType(comp));
        } else {
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(comp));
        }
        int storeOp = javaArrayStoreOpcode(comp);
        for (int j = 0; j < varCount; j++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, j);
            loadJavaLocal(comp, varLocals[j]);
            mv.visitInsn(storeOp);
        }
        int arrLocal = newLocal();
        mv.visitVarInsn(ASTORE, arrLocal);
        argLocals[fixed] = arrLocal;
        return argLocals;
    }

    private static int javaPrimitiveArrayType(Class<?> comp) {
        if (comp == boolean.class) return T_BOOLEAN;
        if (comp == char.class) return T_CHAR;
        if (comp == float.class) return T_FLOAT;
        if (comp == double.class) return T_DOUBLE;
        if (comp == byte.class) return T_BYTE;
        if (comp == short.class) return T_SHORT;
        if (comp == int.class) return T_INT;
        if (comp == long.class) return T_LONG;
        throw new IllegalStateException("Unexpected primitive array component: " + comp);
    }

    private static int javaArrayStoreOpcode(Class<?> comp) {
        if (comp == boolean.class || comp == byte.class) return BASTORE;
        if (comp == char.class) return CASTORE;
        if (comp == short.class) return SASTORE;
        if (comp == int.class) return IASTORE;
        if (comp == long.class) return LASTORE;
        if (comp == float.class) return FASTORE;
        if (comp == double.class) return DASTORE;
        return AASTORE;
    }

    private void emitJavaConstructorCall(HirExpr.JavaConstructorCall c) {
        int ln = c.meta().line();
        if (!c.pureStaticCallee()) {
            emitCheckedJavaReceiver(c.callee(), c.calleeText(), ln, false, OBJECT, "notAScope", null);
            mv.visitInsn(POP);
        }
        Constructor<?> ctor = c.target().constructor();
        Class<?>[] paramTypes = ctor.getParameterTypes();
        int[] argLocals = emitJavaArgsToLocals(c.args(), paramTypes, c.target().varArgs(), ln);
        String internalClass = Type.getInternalName(ctor.getDeclaringClass());
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
        line(ln);
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
        line(ln);
        mv.visitVarInsn(ALOAD, caught);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "wrapInvocationException", "(Ljava/lang/Throwable;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(done);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitJavaMethodCall(HirExpr.JavaMethodCall c) {
        int ln = c.meta().line();
        ScopeAnalysis.JavaCallTarget target = c.target();
        Method method = target.method();
        String owner = Type.getInternalName(method.getDeclaringClass());
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!c.pureStaticReceiver()) {
                emitCheckedJavaReceiver(c.receiver(), c.receiverText(), c.selectorLine(), false, OBJECT, "notAScope", null);
                mv.visitInsn(POP);
            }
        } else {
            emitCheckedJavaReceiver(c.receiver(), c.receiverText(), c.selectorLine(), target.unwrapReceiver(), owner, "notAScope", method.getDeclaringClass());
            recvLocal = newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        int[] argLocals = emitJavaArgsToLocals(c.args(), paramTypes, target.varArgs(), ln);
        boolean itf = method.getDeclaringClass().isInterface();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label done = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
        line(ln);
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
        line(ln);
        mv.visitVarInsn(ALOAD, caught);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "wrapInvocationException", "(Ljava/lang/Throwable;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(done);
        finishCallCheck(c.meta());
    }

    private void emitSafeCall(HirExpr.SafeCall c) {
        Label nonNull = new Label();
        Label end = new Label();
        emitBoxed(c.receiver());
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, nonNull);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(nonNull);
        selectReceiverMember(c.selectorLine(), c.receiverText(), c.memberName());
        if (c.selectorCheckedType() != null) {
            line(c.selectorLine());
            checkType(c.selectorCheckedType());
        }
        emitCallWithCallee(c.meta().line(), constantPool.callSiteField(c.callSite()),
                c.binding() != null ? null : callArgNames(c.args()), c.args(), c.binding(), c.babbleSig());
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        if (c.meta().checkedType() != null) {
            line(c.meta().line());
            checkType(c.meta().checkedType());
        }
        mv.visitLabel(end);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitDynamicCall(HirExpr.DynamicCall c) {
        emitBoxed(c.callee());
        emitCallWithCallee(c.meta().line(), constantPool.callSiteField(c.callSite()),
                c.binding() != null ? null : callArgNames(c.args()), c.args(), c.binding(), c.babbleSig());
        finishCallCheck(c.meta());
    }

    private void emitCallWithCallee(int lineNumber, String site, String[] names,
                                    List<HirCallArg> parameters, ScopeAnalysis.ArgumentBinding binding,
                                    ScopeAnalysis.FunctionSignature sig) {
        line(lineNumber);
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);

        mv.visitVarInsn(ALOAD, rtLocal);
        if (sig != null && names == null
                && (binding != null ? binding.paramCount() == sig.parameterKinds().length : parameters.size() == sig.parameterKinds().length)) {
            int missingBits = binding != null ? binding.missingParamBits() : 0;
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            StringBuilder desc = new StringBuilder("(L").append(CALLABLE).append(";L").append(SCRIPT_RUNTIME)
                    .append(";L").append(CALL_SITE).append(';');
            for (ScopeAnalysis.PrimitiveKind pk : sig.parameterKinds()) {
                desc.append(jvmDescriptor(pk));
            }
            desc.append(')').append(jvmDescriptor(sig.returnKind()));
            emitBoundTypedArgs(callArgExprs(parameters), binding, sig.parameterKinds());
            line(lineNumber);
            if (missingBits != 0) {
                mv.visitInvokeDynamicInsn("typedCallOpt", desc.toString(), TYPED_CALL_OPT_BOOTSTRAP, missingBits);
            } else {
                mv.visitInvokeDynamicInsn("typedCall", desc.toString(), TYPED_CALL_BOOTSTRAP);
            }
            stackKind = sig.returnKind();
            return;
        }
        if (binding != null) {
            int effective = effectiveParamCount(binding);
            if (effective <= HirFunction.MAX_SPREAD) {
                mv.visitInsn(ACONST_NULL);
                mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
                emitBoundBoxedArgs(callArgExprs(parameters), binding, effective, true);
                line(lineNumber);
                mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                        + "Ljava/lang/Object;".repeat(effective) + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
                stackKind = ScopeAnalysis.PrimitiveKind.NONE;
                return;
            }
            emitBoundArgumentsArray(callArgExprs(parameters), binding);
            mv.visitInsn(ACONST_NULL);
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            line(lineNumber);
            mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        if (parameters.size() <= HirFunction.MAX_SPREAD) {
            if (names != null) {
                getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
            for (HirCallArg parameter : parameters) {
                emitBoxed(parameter.value());
            }
            line(lineNumber);
            mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                    + "Ljava/lang/Object;".repeat(parameters.size()) + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        emitArgumentsArray(parameters);
        if (names != null) {
            getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(lineNumber);
        mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private static List<HirExpr> callArgExprs(List<HirCallArg> args) {
        if (args.isEmpty()) {
            return List.of();
        }
        List<HirExpr> out = new ArrayList<>(args.size());
        for (HirCallArg a : args) {
            out.add(a.value());
        }
        return out;
    }

    private static ScopeAnalysis.PrimitiveKind[] paramKindsOf(HirFunction fn) {
        List<HirParam> params = fn.params();
        ScopeAnalysis.PrimitiveKind[] kinds = new ScopeAnalysis.PrimitiveKind[params.size()];
        for (int i = 0; i < params.size(); i++) {
            kinds[i] = params.get(i).kind();
        }
        return kinds;
    }

    private static int effectiveParamCount(ScopeAnalysis.ArgumentBinding binding) {
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        int eff = map.length;
        while (eff > 0 && eff - 1 != restParam && map[eff - 1] < 0) {
            eff--;
        }
        return eff;
    }

    static void emitDefaultValueOn(MethodVisitor m, ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> m.visitInsn(ICONST_0);
            case LONG -> m.visitInsn(LCONST_0);
            case FLOAT -> m.visitInsn(FCONST_0);
            case DOUBLE -> m.visitInsn(DCONST_0);
            case NONE -> m.visitInsn(ACONST_NULL);
        }
    }

    private static boolean anyTrue(boolean[] flags) {
        if (flags == null) {
            return false;
        }
        for (boolean f : flags) {
            if (f) {
                return true;
            }
        }
        return false;
    }

    private void emitBoundTypedArgs(List<HirExpr> sourceArgs, ScopeAnalysis.ArgumentBinding binding,
                                    ScopeAnalysis.PrimitiveKind[] targetKinds) {
        if (binding == null || (!binding.reordered() && !binding.hasRest())) {
            for (int p = 0; p < targetKinds.length; p++) {
                boolean supplied = binding != null
                        ? (p < binding.paramToArg().length && binding.paramToArg()[p] >= 0)
                        : (p < sourceArgs.size());
                if (supplied) {
                    emitAs(sourceArgs.get(p), targetKinds[p]);
                } else {
                    emitDefaultValueOn(mv, targetKinds[p]);
                }
            }
            return;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        ScopeAnalysis.PrimitiveKind[] argKinds = new ScopeAnalysis.PrimitiveKind[sourceArgs.size()];
        java.util.Arrays.fill(argKinds, ScopeAnalysis.PrimitiveKind.NONE);
        for (int p = 0; p < targetKinds.length && p < map.length; p++) {
            if (p != restParam && map[p] >= 0 && map[p] < argKinds.length) {
                argKinds[map[p]] = targetKinds[p];
            }
        }
        if (restParam >= 0 && !anyTrue(binding.restArgIsSpread()) && binding.restElementKind().isPrimitive()) {
            for (int a : binding.restArgs()) {
                argKinds[a] = binding.restElementKind();
            }
        }
        int[] tempLocals = new int[sourceArgs.size()];
        for (int a = 0; a < sourceArgs.size(); a++) {
            emitAs(sourceArgs.get(a), argKinds[a]);
            tempLocals[a] = newLocal(argKinds[a]);
            storeLocal(argKinds[a], tempLocals[a]);
        }
        for (int p = 0; p < targetKinds.length; p++) {
            if (p == restParam) {
                emitPackedRestFromTemps(binding, tempLocals, argKinds, false);
                continue;
            }
            int a = p < map.length ? map[p] : -1;
            if (a >= 0) {
                loadLocal(targetKinds[p], tempLocals[a]);
            } else {
                emitDefaultValueOn(mv, targetKinds[p]);
            }
        }
    }

    private void emitBoundBoxedArgs(List<HirExpr> sourceArgs, ScopeAnalysis.ArgumentBinding binding, int count) {
        emitBoundBoxedArgs(sourceArgs, binding, count, false);
    }

    private void emitBoundBoxedArgs(List<HirExpr> sourceArgs, ScopeAnalysis.ArgumentBinding binding, int count, boolean wrapPackedRest) {
        if (binding == null || (!binding.reordered() && !binding.hasRest())) {
            for (int p = 0; p < count; p++) {
                boolean supplied = binding != null
                        ? (p < binding.paramToArg().length && binding.paramToArg()[p] >= 0)
                        : (p < sourceArgs.size());
                if (supplied) {
                    emitBoxed(sourceArgs.get(p));
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            return;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        ScopeAnalysis.PrimitiveKind[] argKinds = new ScopeAnalysis.PrimitiveKind[sourceArgs.size()];
        java.util.Arrays.fill(argKinds, ScopeAnalysis.PrimitiveKind.NONE);
        if (restParam >= 0 && !anyTrue(binding.restArgIsSpread()) && binding.restElementKind().isPrimitive()) {
            for (int a : binding.restArgs()) {
                argKinds[a] = binding.restElementKind();
            }
        }
        int[] tempLocals = new int[sourceArgs.size()];
        for (int a = 0; a < sourceArgs.size(); a++) {
            emitAs(sourceArgs.get(a), argKinds[a]);
            tempLocals[a] = newLocal(argKinds[a]);
            storeLocal(argKinds[a], tempLocals[a]);
        }
        for (int p = 0; p < count; p++) {
            if (p == restParam) {
                emitPackedRestFromTemps(binding, tempLocals, argKinds, wrapPackedRest);
                continue;
            }
            int a = p < map.length ? map[p] : -1;
            if (a >= 0) {
                mv.visitVarInsn(ALOAD, tempLocals[a]);
            } else {
                mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            }
        }
    }

    private void emitBoundArgumentsArray(List<HirExpr> sourceArgs, ScopeAnalysis.ArgumentBinding binding) {
        int effective = effectiveParamCount(binding);
        if (effective == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        if (!binding.reordered() && !binding.hasRest()) {
            ConstantPool.pushInt(mv, effective);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int p = 0; p < effective; p++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, p);
                emitBoxed(sourceArgs.get(p));
                mv.visitInsn(AASTORE);
            }
            return;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        ScopeAnalysis.PrimitiveKind[] argKinds = new ScopeAnalysis.PrimitiveKind[sourceArgs.size()];
        java.util.Arrays.fill(argKinds, ScopeAnalysis.PrimitiveKind.NONE);
        if (restParam >= 0 && !anyTrue(binding.restArgIsSpread()) && binding.restElementKind().isPrimitive()) {
            for (int a : binding.restArgs()) {
                argKinds[a] = binding.restElementKind();
            }
        }
        int[] tempLocals = new int[sourceArgs.size()];
        for (int a = 0; a < sourceArgs.size(); a++) {
            emitAs(sourceArgs.get(a), argKinds[a]);
            tempLocals[a] = newLocal(argKinds[a]);
            storeLocal(argKinds[a], tempLocals[a]);
        }
        ConstantPool.pushInt(mv, effective);
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int p = 0; p < effective; p++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, p);
            if (p == restParam) {
                emitPackedRestFromTemps(binding, tempLocals, argKinds, true);
            } else {
                int a = map[p];
                if (a >= 0) {
                    mv.visitVarInsn(ALOAD, tempLocals[a]);
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            mv.visitInsn(AASTORE);
        }
    }

    private void emitPackedRestFromTemps(ScopeAnalysis.ArgumentBinding binding, int[] tempLocals,
                                         ScopeAnalysis.PrimitiveKind[] argKinds, boolean wrapPackedRest) {
        int[] restArgs = binding.restArgs();
        boolean[] isSpread = binding.restArgIsSpread();
        int n = restArgs.length;
        if (n == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS_VIEW, "EMPTY", "L" + ARGUMENTS_VIEW + ";");
        } else if (n == 1 && isSpread[0]) {
            mv.visitVarInsn(ALOAD, tempLocals[restArgs[0]]);
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable", "(Ljava/lang/Object;)Ljava/lang/Iterable;", false);
        } else if (!anyTrue(isSpread)) {
            ScopeAnalysis.PrimitiveKind rk = binding.restElementKind();
            if (rk.isPrimitive()) {
                ConstantPool.pushInt(mv, n);
                mv.visitIntInsn(NEWARRAY, primArrayNewArrayType(rk));
                for (int k = 0; k < n; k++) {
                    mv.visitInsn(DUP);
                    ConstantPool.pushInt(mv, k);
                    loadLocal(rk, tempLocals[restArgs[k]]);
                    mv.visitInsn(primArrayStoreInsn(rk));
                }
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS_VIEW, primViewFactory(rk),
                        "(" + primArrayDesc(rk) + ")L" + ARGUMENTS_VIEW + ";", false);
            } else {
                ConstantPool.pushInt(mv, n);
                mv.visitTypeInsn(ANEWARRAY, OBJECT);
                for (int k = 0; k < n; k++) {
                    mv.visitInsn(DUP);
                    ConstantPool.pushInt(mv, k);
                    loadLocal(argKinds[restArgs[k]], tempLocals[restArgs[k]]);
                    box(argKinds[restArgs[k]]);
                    mv.visitInsn(AASTORE);
                }
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS_VIEW, "of",
                        "([Ljava/lang/Object;)L" + ARGUMENTS_VIEW + ";", false);
            }
        } else {
            ConstantPool.pushInt(mv, n);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int k = 0; k < n; k++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, k);
                loadLocal(argKinds[restArgs[k]], tempLocals[restArgs[k]]);
                box(argKinds[restArgs[k]]);
                mv.visitInsn(AASTORE);
            }
            ConstantPool.pushInt(mv, n);
            mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
            for (int k = 0; k < n; k++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, k);
                mv.visitInsn(isSpread[k] ? ICONST_1 : ICONST_0);
                mv.visitInsn(BASTORE);
            }
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "packRest",
                    "([Ljava/lang/Object;[Z)Ljava/lang/Iterable;", false);
        }
        if (wrapPackedRest) {
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "packedRest",
                    "(Ljava/lang/Iterable;)Ljava/lang/Object;", false);
        }
    }

    private static int primArrayNewArrayType(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> T_BYTE;
            case SHORT -> T_SHORT;
            case INT -> T_INT;
            case LONG -> T_LONG;
            case FLOAT -> T_FLOAT;
            case DOUBLE -> T_DOUBLE;
            case BOOL -> T_BOOLEAN;
            case CHAR -> T_CHAR;
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static int primArrayStoreInsn(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, BOOL -> BASTORE;
            case SHORT -> SASTORE;
            case INT -> IASTORE;
            case LONG -> LASTORE;
            case FLOAT -> FASTORE;
            case DOUBLE -> DASTORE;
            case CHAR -> CASTORE;
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String primArrayDesc(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "[B";
            case SHORT -> "[S";
            case INT -> "[I";
            case LONG -> "[J";
            case FLOAT -> "[F";
            case DOUBLE -> "[D";
            case BOOL -> "[Z";
            case CHAR -> "[C";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String primViewFactory(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "ofBytes";
            case SHORT -> "ofShorts";
            case INT -> "ofInts";
            case LONG -> "ofLongs";
            case FLOAT -> "ofFloats";
            case DOUBLE -> "ofDoubles";
            case BOOL -> "ofBooleans";
            case CHAR -> "ofChars";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private void emitSpreadAdapt(HirExpr.SpreadAdapt expr) {
        emitBoxed(expr.value());
        ScopeAnalysis.SpreadAdapt adapt = expr.adapt();
        switch (adapt.kind()) {
            case NONE, AS_IS -> {
            }
            case TO_ITERABLE -> mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable",
                    "(Ljava/lang/Object;)Ljava/lang/Iterable;", false);
            case TO_LIST_BORROWED -> {
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList", "(Ljava/lang/Object;Z)Ljava/util/List;", false);
            }
            case TO_LIST_COPY -> {
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList", "(Ljava/lang/Object;Z)Ljava/util/List;", false);
            }
            case TO_ARRAY -> {
                pushClassConstant(mv, adapt.arrayComponentType());
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArray",
                        "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
            }
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitSpreadAdaptOnStack(ScopeAnalysis.SpreadAdapt adapt) {
        if (adapt == null) {
            return;
        }
        switch (adapt.kind()) {
            case NONE, AS_IS -> {
            }
            case TO_ITERABLE -> mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable",
                    "(Ljava/lang/Object;)Ljava/lang/Iterable;", false);
            case TO_LIST_BORROWED -> {
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList", "(Ljava/lang/Object;Z)Ljava/util/List;", false);
            }
            case TO_LIST_COPY -> {
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList", "(Ljava/lang/Object;Z)Ljava/util/List;", false);
            }
            case TO_ARRAY -> {
                pushClassConstant(mv, adapt.arrayComponentType());
                mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArray",
                        "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
            }
        }
    }

    private void emitMakeOverloadSet(HirExpr.MakeOverloadSet m) {
        List<HirExpr> ops = m.operands();
        List<ScopeAnalysis.OverloadAltMetaInfo> metas = m.metas();
        // 1. Object[] operands
        ConstantPool.pushInt(mv, ops.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < ops.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitBoxed(ops.get(i));
            mv.visitInsn(AASTORE);
        }
        // 2. String[] sigKeys
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            mv.visitLdcInsn(metas.get(i).signatureKey());
            mv.visitInsn(AASTORE);
        }
        // 3. String[] displays
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            mv.visitLdcInsn(metas.get(i).display());
            mv.visitInsn(AASTORE);
        }
        // 4. int[] minArities
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).minArity());
            mv.visitInsn(IASTORE);
        }
        // 5. int[] maxArities
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).maxArity());
            mv.visitInsn(IASTORE);
        }
        // 6. int[] restIndices
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).restIndex());
            mv.visitInsn(IASTORE);
        }
        // 7. String[][] paramDescriptors
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, "[L" + STRING + ";");
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            String[] descs = metas.get(i).paramDescriptors();
            ConstantPool.pushInt(mv, descs.length);
            mv.visitTypeInsn(ANEWARRAY, STRING);
            for (int j = 0; j < descs.length; j++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, j);
                mv.visitLdcInsn(descs[j] != null ? descs[j] : "");
                mv.visitInsn(AASTORE);
            }
            mv.visitInsn(AASTORE);
        }
        line(m.meta().line());
        mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "create",
                "([Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/String;[I[I[I[[Ljava/lang/String;)L" + OVERLOAD_SET + ";",
                false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitOverloadSelect(HirExpr.OverloadSelect s) {
        emitBoxed(s.set());
        emitOverloadSelectOnStack(s.index(), s.signatureKey(), s.exactSet());
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitOverloadSelectOnStack(int index, String signatureKey, boolean exactSet) {
        if (exactSet) {
            ConstantPool.pushInt(mv, index);
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "memberOf",
                    "(Ljava/lang/Object;I)L" + CALLABLE + ";", false);
        } else {
            mv.visitLdcInsn(signatureKey);
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "selectFrom",
                    "(Ljava/lang/Object;Ljava/lang/String;)L" + CALLABLE + ";", false);
        }
    }

    private void emitOverloadDispatch(HirExpr.OverloadDispatch d) {
        int savedNextLocal = nextLocal;
        int ln = d.meta().line();
        int calleeLocal = nextLocal++;
        emitBoxed(d.callee());
        mv.visitVarInsn(ASTORE, calleeLocal);

        List<HirExpr> args = d.args();
        int[] argLocals = new int[args.size()];
        ScopeAnalysis.PrimitiveKind[] argKinds = new ScopeAnalysis.PrimitiveKind[args.size()];
        for (int i = 0; i < args.size(); i++) {
            argLocals[i] = nextLocal++;
            argKinds[i] = ScopeAnalysis.PrimitiveKind.NONE;
            emitBoxed(args.get(i));
            mv.visitVarInsn(ASTORE, argLocals[i]);
        }

        ScopeAnalysis.PrimitiveKind joinKind = d.meta().kind();
        Label endLabel = new Label();
        String siteField = constantPool.callSiteField(d.callSite());

        for (HirExpr.OverloadBranch branch : d.branches()) {
            Label nextBranch = new Label();
            for (ScopeAnalysis.OverloadSplitCheck chk : branch.checks()) {
                int slot = argLocals[chk.argIndex()];
                if (chk.isNull()) {
                    mv.visitVarInsn(ALOAD, slot);
                    mv.visitJumpInsn(IFNONNULL, nextBranch);
                } else {
                    mv.visitVarInsn(ALOAD, slot);
                    mv.visitJumpInsn(IFNULL, nextBranch);
                    for (String desc : chk.descriptors()) {
                        mv.visitVarInsn(ALOAD, slot);
                        mv.visitLdcInsn(desc);
                        mv.visitMethodInsn(INVOKESTATIC, TYPES, "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                        mv.visitJumpInsn(IFEQ, nextBranch);
                    }
                }
            }

            ScopeAnalysis.ArgumentBinding binding = branch.binding();
            if (branch.directFunctionId() >= 0) {
                HirFunction fn = module.function(branch.directFunctionId());
                ensureFunctionScheduled(fn);
                int count = fn.params().size();
                mv.visitVarInsn(ALOAD, calleeLocal);
                emitOverloadSelectOnStack(branch.altIndex(), branch.signatureKey(), branch.exactSet());
                mv.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
                mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNBOUND", "L" + RUNTIME + "Undefined;");
                mv.visitVarInsn(ALOAD, rtLocal);
                if (fn.hasSpec()) {
                    ScopeAnalysis.PrimitiveKind[] paramKinds = paramKindsOf(fn);
                    for (int p = 0; p < count; p++) {
                        if (binding != null && p == binding.restParam()) {
                            emitPackedRestFromTemps(binding, argLocals, argKinds, false);
                        } else {
                            int a = binding != null ? binding.paramToArg()[p] : p;
                            if (a >= 0) {
                                mv.visitVarInsn(ALOAD, argLocals[a]);
                                if (binding != null) {
                                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                                }
                                if (paramKinds[p].isPrimitive()) {
                                    unbox(paramKinds[p]);
                                }
                            } else {
                                emitDefaultValueOn(mv, paramKinds[p]);
                            }
                        }
                    }
                    if (fn.hasOptionalMask()) {
                        ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                    }
                    line(ln);
                    mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName() + "$spec", functionSpecDescriptor(fn), false);
                    convertStack(fn.returnKind(), joinKind);
                } else {
                    mv.visitInsn(ACONST_NULL);
                    for (int p = 0; p < count; p++) {
                        if (binding != null && p == binding.restParam()) {
                            emitPackedRestFromTemps(binding, argLocals, argKinds, false);
                        } else {
                            int a = binding != null ? binding.paramToArg()[p] : p;
                            if (a >= 0) {
                                mv.visitVarInsn(ALOAD, argLocals[a]);
                                if (binding != null) {
                                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                                }
                            } else {
                                mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                            }
                        }
                    }
                    line(ln);
                    mv.visitMethodInsn(INVOKESTATIC, className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
                    convertStack(ScopeAnalysis.PrimitiveKind.NONE, joinKind);
                }
            } else {
                mv.visitVarInsn(ALOAD, calleeLocal);
                emitOverloadSelectOnStack(branch.altIndex(), branch.signatureKey(), branch.exactSet());
                mv.visitVarInsn(ALOAD, rtLocal);
                emitBoundArgsArrayFromTemps(binding, argLocals, argKinds);
                mv.visitInsn(ACONST_NULL);
                mv.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
                line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                        "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
                convertStack(ScopeAnalysis.PrimitiveKind.NONE, joinKind);
            }
            mv.visitJumpInsn(GOTO, endLabel);
            mv.visitLabel(nextBranch);
        }

        // Unreachable when static split is exhaustive; keeps JVM stack frames valid.
        mv.visitTypeInsn(NEW, BABBLE_EXCEPTION);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("No applicable overload of " + d.calleeText());
        mv.visitMethodInsn(INVOKESPECIAL, BABBLE_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(endLabel);
        nextLocal = savedNextLocal;
        stackKind = joinKind;
        finishCallCheck(d.meta());
    }

    private void emitBoundArgsArrayFromTemps(ScopeAnalysis.ArgumentBinding binding, int[] argLocals,
                                             ScopeAnalysis.PrimitiveKind[] argKinds) {
        if (binding == null) {
            ConstantPool.pushInt(mv, argLocals.length);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int i = 0; i < argLocals.length; i++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, i);
                mv.visitVarInsn(ALOAD, argLocals[i]);
                mv.visitInsn(AASTORE);
            }
            return;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        int effective = map.length;
        while (effective > 0 && effective - 1 != restParam && map[effective - 1] < 0) {
            effective--;
        }
        if (effective == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, effective);
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int p = 0; p < effective; p++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, p);
            if (p == restParam) {
                emitPackedRestFromTemps(binding, argLocals, argKinds, true);
            } else {
                int a = map[p];
                if (a >= 0) {
                    mv.visitVarInsn(ALOAD, argLocals[a]);
                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            mv.visitInsn(AASTORE);
        }
    }

    private static void pushClassConstant(MethodVisitor m, Class<?> cls) {
        if (cls == byte.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Byte", "TYPE", "Ljava/lang/Class;");
        } else if (cls == short.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Short", "TYPE", "Ljava/lang/Class;");
        } else if (cls == int.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
        } else if (cls == long.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
        } else if (cls == float.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;");
        } else if (cls == double.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Double", "TYPE", "Ljava/lang/Class;");
        } else if (cls == boolean.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
        } else if (cls == char.class) {
            m.visitFieldInsn(GETSTATIC, "java/lang/Character", "TYPE", "Ljava/lang/Class;");
        } else {
            m.visitLdcInsn(Type.getType(cls != null ? cls : Object.class));
        }
    }

    private void emitArgumentsArray(List<HirCallArg> parameters) {
        if (parameters.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, parameters.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < parameters.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitBoxed(parameters.get(i).value());
            mv.visitInsn(AASTORE);
        }
    }

    private static String[] callArgNames(List<HirCallArg> parameters) {
        String[] names = null;
        for (int i = 0; i < parameters.size(); i++) {
            String name = parameters.get(i).name();
            if (name != null) {
                if (names == null) {
                    names = new String[parameters.size()];
                }
                names[i] = name;
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- function / class / interface values

    private void emitFunctionRef(HirExpr.FunctionRef ref) {
        HirFunction fn = module.function(ref.functionId());
        if (fn.isCompiledClassMethod()) {
            ClassGen owner = classGens.get(fn.ownerClassId());
            mv.visitFieldInsn(GETSTATIC, className, compiledMethodField(fn, owner), "L" + COMPILED_METHOD + ";");
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
            return;
        }
        ensureFunctionScheduled(fn);
        String method = fn.jvmMethodName();
        String paramsField = constantPool.stringArrayField(fn.paramNames());
        boolean spread = fn.isSpread();
        boolean spec = fn.hasSpec();
        boolean hasOptMask = spec && fn.hasOptionalMask();
        int restIndex = fn.restIndex();
        Type bodyType = Type.getMethodType(BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("invoke", "()L" + FUNCTION_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, spread ? method + "$call" : method, BODY_DESCRIPTOR, false), bodyType);
        if (spread) {
            mv.visitLdcInsn(new Handle(H_INVOKESTATIC, className, method, functionDirectDescriptor(fn), false));
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        if (spec) {
            mv.visitLdcInsn(new Handle(H_INVOKESTATIC, className, method + "$spec", functionSpecDescriptor(fn), false));
        } else if (restIndex >= 0) {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ALOAD, nsLocal);
        capturedCells(fn.capturedVars());
        mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        ConstantPool.pushInt(mv, fn.requiredParams());
        if (restIndex >= 0) {
            ConstantPool.pushInt(mv, hasOptMask ? fn.optionalParamBits() : 0);
            ConstantPool.pushInt(mv, restIndex);
        } else if (hasOptMask) {
            ConstantPool.pushInt(mv, fn.optionalParamBits());
        }
        if (fn.name() != null) {
            mv.visitLdcInsn(fn.name());
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        String createDesc = restIndex >= 0
                ? "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;IIILjava/lang/String;)L" + COMPILED_FUNCTION + ";"
                : (hasOptMask
                        ? "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;IILjava/lang/String;)L" + COMPILED_FUNCTION + ";"
                        : (spec
                                ? "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L" + COMPILED_FUNCTION + ";"
                                : "(L" + FUNCTION_BODY + ";Ljava/lang/invoke/MethodHandle;L" + NAMESPACE + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L" + COMPILED_FUNCTION + ";"));
        mv.visitMethodInsn(INVOKESTATIC, COMPILED_FUNCTION, "create", createDesc, false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitClassExpr(HirExpr.ClassExpr expr) {
        HirClass cls = module.clazz(expr.classId());
        ClassGen gen = classGens.get(cls.id());
        classValueArguments(cls);
        emitTypeRefs(cls.implementedTypes());
        if (cls.isObject()) {
            if (gen != null) {
                emitInstanceFactory(gen);
            }
            mv.visitVarInsn(ALOAD, rtLocal);
            line(expr.meta().line());
            mv.visitMethodInsn(INVOKESTATIC, BABBLE_CLASS, "object",
                    "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;"
                            + (gen != null ? "L" + INSTANCE_FACTORY + ";" : "") + "L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        } else {
            if (gen != null) {
                emitInstanceFactory(gen);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                        "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;L" + INSTANCE_FACTORY + ";)L" + BABBLE_CLASS + ";", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                        "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;)L" + BABBLE_CLASS + ";", false);
            }
            emitStaticBlock(cls.staticBlock());
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void classValueArguments(HirClass cls) {
        ClassShape shape = cls.shape();
        String method = "cls$" + (classBodyCount++) + "$" + shape.name();
        pendingMethods.add(() -> generateClassBody(cls, method));

        mv.visitFieldInsn(GETSTATIC, className, constantPool.shapeField(shape), "L" + CLASS_SHAPE + ";");
        mv.visitVarInsn(ALOAD, nsLocal);
        capturedCells(cls.capturedVars());
        Type bodyType = Type.getMethodType(CLASS_BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("construct", "()L" + CLASS_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, method, CLASS_BODY_DESCRIPTOR, false), bodyType);
    }

    private void emitInstanceFactory(ClassGen gen) {
        String descriptor = "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;";
        mv.visitInvokeDynamicInsn("create", "()L" + INSTANCE_FACTORY + ";", METAFACTORY,
                Type.getMethodType(descriptor),
                new Handle(H_NEWINVOKESPECIAL, gen.name, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V", false),
                Type.getMethodType("(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)L" + gen.name + ";"));
    }

    private void emitInterfaceExpr(HirExpr.InterfaceExpr expr) {
        HirInterface iface = module.iface(expr.interfaceId());
        mv.visitFieldInsn(GETSTATIC, className, constantPool.shapeField(iface.shape()), "L" + CLASS_SHAPE + ";");
        emitTypeRefs(iface.extendedTypes());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newInterface", "(L" + CLASS_SHAPE + ";[Ljava/lang/Object;)L" + BABBLE_INTERFACE + ";", false);
        mv.visitInsn(DUP);
        int ifaceLocal = newLocal();
        mv.visitVarInsn(ASTORE, ifaceLocal);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitVarInsn(ALOAD, ifaceLocal);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "interfaceScope", "(L" + NAMESPACE + ";L" + BABBLE_INTERFACE + ";)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(iface.body()));
        pop(stackKind);
        emitStaticBlock(iface.staticBlock());
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitStaticBlock(HirStaticBlock block) {
        if (block == null) {
            return;
        }
        mv.visitInsn(DUP);
        int type = newLocal();
        mv.visitVarInsn(ASTORE, type);
        int outerTarget = staticTarget;
        staticTarget = type;
        if (block.namespaceScope()) {
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitVarInsn(ALOAD, type);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "staticScope", "(L" + NAMESPACE + ";L" + RUNTIME + "BabbleType;)L" + NAMESPACE + ";", false);
            int namespace = newLocal();
            mv.visitVarInsn(ASTORE, namespace);
            withNamespace(namespace, () -> statements(block.body()));
        } else {
            inFrameScope(() -> {
                for (HirVar variable : declareVariables(block.declaredVars())) {
                    if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                        mv.visitVarInsn(ALOAD, type);
                        initVariable(variable, ScopeAnalysis.PrimitiveKind.NONE);
                    }
                }
                statements(block.body());
            });
        }
        staticTarget = outerTarget;
        pop(stackKind);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private void emitTypeRefs(List<HirTypeRef> types) {
        ConstantPool.pushInt(mv, types.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < types.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitTypeRef(types.get(i));
            mv.visitInsn(AASTORE);
        }
    }

    private void emitTypeRef(HirTypeRef typeRef) {
        List<String> ids = typeRef.path();
        line(typeRef.line());
        loadVarOrName(typeRef.rootVar(), ids.get(0), typeRef.line());
        convertStack(stackKind, ScopeAnalysis.PrimitiveKind.NONE);
        StringBuilder text = new StringBuilder(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            mv.visitLdcInsn(ids.get(i));
            ConstantPool.pushString(mv, text.toString());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
            text.append('.').append(ids.get(i));
        }
        ConstantPool.pushString(mv, typeRef.text());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "type", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- Java interop helpers

    private void emitCheckedJavaReceiver(HirExpr recvExpr, String recvText, int lineAt,
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
        ConstantPool.pushString(mv, recvText);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", nullErrorFactory, "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        if (!OBJECT.equals(internalTargetClass)) {
            mv.visitTypeInsn(CHECKCAST, internalTargetClass);
        }
        stackKind = ScopeAnalysis.PrimitiveKind.NONE;
    }

    private int emitJavaArgToLocal(HirExpr argExpr, Class<?> paramType, int lineAt) {
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
            emitExpr(argExpr);
            if (stackKind == ScopeAnalysis.PrimitiveKind.INT) {
                mv.visitInsn(I2L);
            } else {
                box(stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            }
            int slot = newLocal(ScopeAnalysis.PrimitiveKind.LONG);
            mv.visitVarInsn(LSTORE, slot);
            return slot;
        }
        if (paramType == float.class) {
            emitExpr(argExpr);
            if (stackKind == ScopeAnalysis.PrimitiveKind.INT) {
                mv.visitInsn(I2F);
            } else {
                box(stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
            }
            int slot = newLocal();
            mv.visitVarInsn(FSTORE, slot);
            return slot;
        }
        ScopeAnalysis.PrimitiveKind argKind = argExpr.meta().kind();
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
            stackKind = ScopeAnalysis.PrimitiveKind.BYTE;
        } else if (type == short.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.SHORT;
        } else if (type == char.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.CHAR;
        } else if (type == long.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.LONG;
        } else if (type == float.class) {
            stackKind = ScopeAnalysis.PrimitiveKind.FLOAT;
        } else {
            if (type == Object.class || type.isInterface()) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
    }

    // ---------------------------------------------------------------- primitive / descriptor / bridge helpers

    static String jvmDescriptor(ScopeAnalysis.PrimitiveKind kind) {
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
        return "(L" + COMPILED_FUNCTION + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME + ";L" + BINDER + ";"
                + "Ljava/lang/Object;".repeat(fn.params().size()) + ")Ljava/lang/Object;";
    }

    static String functionSpecDescriptor(HirFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("(L").append(COMPILED_FUNCTION).append(";Ljava/lang/Object;L").append(SCRIPT_RUNTIME).append(';');
        for (HirParam param : fn.params()) {
            sb.append(jvmDescriptor(param.kind()));
        }
        if (fn.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(fn.returnKind()));
        return sb.toString();
    }

    static String methodDirectDescriptor(HirFunction fn) {
        return "(L" + COMPILED_METHOD + ";L" + SCRIPT_RUNTIME + ";L" + BINDER + ";"
                + "Ljava/lang/Object;".repeat(fn.params().size()) + ")Ljava/lang/Object;";
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

    private static String cellClass(ScopeAnalysis.PrimitiveKind kind) {
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

    private void loadLocal(ScopeAnalysis.PrimitiveKind kind, int slot) {
        loadKindOn(mv, kind, slot);
    }

    private void storeLocal(ScopeAnalysis.PrimitiveKind kind, int slot) {
        int op = switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> ISTORE;
            case LONG -> LSTORE;
            case FLOAT -> FSTORE;
            case DOUBLE -> DSTORE;
            case NONE -> ASTORE;
        };
        mv.visitVarInsn(op, slot);
    }

    private void dup(ScopeAnalysis.PrimitiveKind kind) {
        mv.visitInsn(kind.slots() == 2 ? DUP2 : DUP);
    }

    private void pop(ScopeAnalysis.PrimitiveKind kind) {
        mv.visitInsn(kind.slots() == 2 ? POP2 : POP);
    }

    static int returnInsn(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> IRETURN;
            case LONG -> LRETURN;
            case FLOAT -> FRETURN;
            case DOUBLE -> DRETURN;
            case NONE -> ARETURN;
        };
    }

    private void pushDefaultZero(ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> mv.visitInsn(ICONST_0);
            case LONG -> mv.visitInsn(LCONST_0);
            case FLOAT -> mv.visitInsn(FCONST_0);
            case DOUBLE -> mv.visitInsn(DCONST_0);
            case NONE -> mv.visitInsn(ACONST_NULL);
        }
    }

    private void box(ScopeAnalysis.PrimitiveKind kind) {
        boxOn(mv, kind);
    }

    static void boxOn(MethodVisitor m, ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case BYTE -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            case SHORT -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            case INT -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case LONG -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            case FLOAT -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            case DOUBLE -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            case BOOL -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case CHAR -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            case NONE -> {
            }
        }
    }

    private void unbox(ScopeAnalysis.PrimitiveKind kind) {
        switch (kind) {
            case BYTE -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxByte", "(Ljava/lang/Object;)B", false);
            case SHORT -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxShort", "(Ljava/lang/Object;)S", false);
            case INT -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxInt", "(Ljava/lang/Object;)I", false);
            case LONG -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxLong", "(Ljava/lang/Object;)J", false);
            case FLOAT -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxFloat", "(Ljava/lang/Object;)F", false);
            case DOUBLE -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxDouble", "(Ljava/lang/Object;)D", false);
            case BOOL -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxBool", "(Ljava/lang/Object;)Z", false);
            case CHAR -> mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "unboxChar", "(Ljava/lang/Object;)C", false);
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
        } else if (!emitPrimitiveWidening(mv, from, to)) {
            box(from);
            unbox(to);
        }
        stackKind = to;
    }

    private static ScopeAnalysis.PrimitiveKind targetKindForRank(int rank) {
        return switch (rank) {
            case 1 -> ScopeAnalysis.PrimitiveKind.SHORT;
            case 2 -> ScopeAnalysis.PrimitiveKind.INT;
            case 3 -> ScopeAnalysis.PrimitiveKind.LONG;
            case 4 -> ScopeAnalysis.PrimitiveKind.FLOAT;
            case 5 -> ScopeAnalysis.PrimitiveKind.DOUBLE;
            default -> ScopeAnalysis.PrimitiveKind.NONE;
        };
    }

    private void widenStackRank(int rank) {
        if (rank < 0) {
            return;
        }
        ScopeAnalysis.PrimitiveKind target = targetKindForRank(rank);
        if (stackKind.isPrimitive() && target != ScopeAnalysis.PrimitiveKind.NONE) {
            convertStack(stackKind, target);
            return;
        }
        if (rank == 5) {
            if (emitPrimitiveWidening(mv, stackKind, ScopeAnalysis.PrimitiveKind.DOUBLE)) {
                stackKind = ScopeAnalysis.PrimitiveKind.DOUBLE;
            } else if (stackKind == ScopeAnalysis.PrimitiveKind.NONE) {
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
        } else {
            box(stackKind);
            ConstantPool.pushInt(mv, rank);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "widenNumeric", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            stackKind = ScopeAnalysis.PrimitiveKind.NONE;
        }
    }

    static String bridgeMethodName(String member) {
        return "bm$" + member;
    }

    static String bridgeDescriptor(ScopeAnalysis.FunctionSignature sig) {
        StringBuilder sb = new StringBuilder("(L").append(SCRIPT_RUNTIME).append(';');
        for (ScopeAnalysis.PrimitiveKind pk : sig.parameterKinds()) {
            sb.append(jvmDescriptor(pk));
        }
        if (sig.hasOptionalMask()) {
            sb.append('I');
        }
        sb.append(')').append(jvmDescriptor(sig.returnKind()));
        return sb.toString();
    }

    static int[] bridgeParamSlots(ScopeAnalysis.FunctionSignature sig) {
        ScopeAnalysis.PrimitiveKind[] kinds = sig.parameterKinds();
        int[] paramSlots = new int[kinds.length];
        int next = 2;
        for (int i = 0; i < kinds.length; i++) {
            paramSlots[i] = next;
            next += kinds[i].slots();
        }
        return paramSlots;
    }

    static int bridgeMaskSlot(ScopeAnalysis.FunctionSignature sig) {
        if (!sig.hasOptionalMask()) {
            return -1;
        }
        int next = 2;
        for (ScopeAnalysis.PrimitiveKind pk : sig.parameterKinds()) {
            next += pk.slots();
        }
        return next;
    }

    static void emitLoadBridgeArgBoxed(MethodVisitor m, ScopeAnalysis.FunctionSignature sig, int[] paramSlots, int index) {
        ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[index];
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

    static void emitRemapBridgeMask(MethodVisitor m, ScopeAnalysis.FunctionSignature sig, HirFunction fn, int ifaceCount) {
        if (!fn.hasOptionalMask()) {
            return;
        }
        if (sig.hasOptionalMask()) {
            m.visitVarInsn(ILOAD, bridgeMaskSlot(sig));
            ConstantPool.pushInt(m, fn.optionalParamBits());
            ConstantPool.pushInt(m, ifaceCount);
            m.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "remapMask", "(III)I", false);
        } else {
            ConstantPool.pushInt(m, org.babblelang.runtime.Arguments.remapMask(0, fn.optionalParamBits(), ifaceCount));
        }
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

    static void emitAdaptBridgeArg(MethodVisitor m, ScopeAnalysis.FunctionSignature sig, int[] paramSlots,
                                   int index, ScopeAnalysis.PrimitiveKind toK, int widenRank) {
        ScopeAnalysis.PrimitiveKind fromK = sig.parameterKinds()[index];
        boolean needsNullGuard = sig.isOptional(index)
                && fromK == ScopeAnalysis.PrimitiveKind.NONE
                && (toK != ScopeAnalysis.PrimitiveKind.NONE || widenRank >= 0);
        if (!needsNullGuard) {
            loadKindOn(m, fromK, paramSlots[index]);
            emitAdaptKind(m, fromK, toK);
            if (toK == ScopeAnalysis.PrimitiveKind.NONE && widenRank >= 0) {
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
        if (toK == ScopeAnalysis.PrimitiveKind.NONE && widenRank >= 0) {
            emitWidenBoxed(m, widenRank);
        }
        m.visitLabel(done);
    }

    static void loadKindOn(MethodVisitor m, ScopeAnalysis.PrimitiveKind kind, int slot) {
        int op = switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> ILOAD;
            case LONG -> LLOAD;
            case FLOAT -> FLOAD;
            case DOUBLE -> DLOAD;
            case NONE -> ALOAD;
        };
        m.visitVarInsn(op, slot);
    }

    private static boolean isNumericKind(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private static int numericOrder(ScopeAnalysis.PrimitiveKind kind) {
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

    private static boolean emitPrimitiveWidening(MethodVisitor m, ScopeAnalysis.PrimitiveKind from, ScopeAnalysis.PrimitiveKind to) {
        if (from == to) {
            return true;
        }
        if (from == ScopeAnalysis.PrimitiveKind.CHAR && isNumericKind(to) && numericOrder(to) >= 2) {
            return emitPrimitiveWidening(m, ScopeAnalysis.PrimitiveKind.INT, to);
        }
        int f = numericOrder(from);
        int t = numericOrder(to);
        if (f < 0 || t < 0 || f > t) {
            return false;
        }
        if (f <= 2) {
            switch (to) {
                case BYTE, SHORT, INT -> {
                }
                case LONG -> m.visitInsn(I2L);
                case FLOAT -> m.visitInsn(I2F);
                case DOUBLE -> m.visitInsn(I2D);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (from == ScopeAnalysis.PrimitiveKind.LONG) {
            switch (to) {
                case FLOAT -> m.visitInsn(L2F);
                case DOUBLE -> m.visitInsn(L2D);
                default -> {
                    return false;
                }
            }
            return true;
        }
        if (from == ScopeAnalysis.PrimitiveKind.FLOAT && to == ScopeAnalysis.PrimitiveKind.DOUBLE) {
            m.visitInsn(F2D);
            return true;
        }
        return false;
    }

    static void emitAdaptKind(MethodVisitor m, ScopeAnalysis.PrimitiveKind from, ScopeAnalysis.PrimitiveKind to) {
        if (from == to) {
            return;
        }
        if (to == ScopeAnalysis.PrimitiveKind.NONE) {
            boxOn(m, from);
            return;
        }
        if (emitPrimitiveWidening(m, from, to)) {
            return;
        }
        if (from == ScopeAnalysis.PrimitiveKind.NONE) {
            switch (to) {
                case BYTE -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                }
                case SHORT -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
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
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                }
                case DOUBLE -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                }
                case BOOL -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                }
                case CHAR -> {
                    m.visitTypeInsn(CHECKCAST, "java/lang/Character");
                    m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                }
                case NONE -> {
                }
            }
            return;
        }
        boxOn(m, from);
        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, to);
    }

    static void packBridgeArgsArray(MethodVisitor m, ScopeAnalysis.FunctionSignature sig, int[] paramSlots) {
        ScopeAnalysis.PrimitiveKind[] kinds = sig.parameterKinds();
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

    private static void emitPushClassConstant(MethodVisitor m, Class<?> cls) {
        if (cls.isPrimitive()) {
            String wrapper = Type.getInternalName(JavaTypes.box(cls));
            m.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
        } else {
            m.visitLdcInsn(Type.getType(cls));
        }
    }

    static void emitWidenBoxed(MethodVisitor m, int widenRank) {
        if (widenRank == 5) {
            m.visitMethodInsn(INVOKESTATIC, VALUES, "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else if (widenRank >= 0) {
            ConstantPool.pushInt(m, widenRank);
            m.visitMethodInsn(INVOKESTATIC, VALUES, "widenNumeric", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
        }
    }

    private static void emitLoadPrimitiveBridgeArg(MethodVisitor m, ScopeAnalysis.PrimitiveKind fromKind, int slot,
                                                   ScopeAnalysis.PrimitiveKind targetKind, int widenRank) {
        loadKindOn(m, fromKind, slot);
        if (targetKind == ScopeAnalysis.PrimitiveKind.NONE && widenRank >= 0) {
            boxOn(m, fromKind);
            emitWidenBoxed(m, widenRank);
        } else {
            emitAdaptKind(m, fromKind, targetKind);
        }
    }

    static void emitLoadJavaBridgeArg(MethodVisitor m, Class<?> pType, int slot,
                                      ScopeAnalysis.PrimitiveKind targetKind, int widenRank, String checkedDesc) {
        if (pType == byte.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.BYTE, slot, targetKind, widenRank);
            return;
        }
        if (pType == short.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.SHORT, slot, targetKind, widenRank);
            return;
        }
        if (pType == int.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.INT, slot, targetKind, widenRank);
            return;
        }
        if (pType == long.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.LONG, slot, targetKind, widenRank);
            return;
        }
        if (pType == float.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.FLOAT, slot, targetKind, widenRank);
            return;
        }
        if (pType == double.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.DOUBLE, slot, targetKind, widenRank);
            return;
        }
        if (pType == boolean.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.BOOL, slot, targetKind, widenRank);
            return;
        }
        if (pType == char.class) {
            emitLoadPrimitiveBridgeArg(m, ScopeAnalysis.PrimitiveKind.CHAR, slot, targetKind, widenRank);
            return;
        }
        m.visitVarInsn(ALOAD, slot);
        if (pType != String.class && pType != Byte.class && pType != Short.class
                && pType != Integer.class && pType != Long.class && pType != Float.class
                && pType != Double.class && pType != Boolean.class && pType != Character.class) {
            m.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        if (checkedDesc != null) {
            m.visitLdcInsn(checkedDesc);
            m.visitMethodInsn(INVOKESTATIC, TYPES, "check", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        if (targetKind == ScopeAnalysis.PrimitiveKind.NONE && widenRank >= 0) {
            emitWidenBoxed(m, widenRank);
        } else {
            emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, targetKind);
        }
    }

    static void emitPackJavaBridgeArgs(MethodVisitor m, Class<?>[] paramTypes, int[] javaParamSlots) {
        if (paramTypes.length == 0) {
            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(m, paramTypes.length);
        m.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < paramTypes.length; i++) {
            m.visitInsn(DUP);
            ConstantPool.pushInt(m, i);
            emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ScopeAnalysis.PrimitiveKind.NONE, -1, null);
            m.visitInsn(AASTORE);
        }
    }

    private static ScopeAnalysis.PrimitiveKind javaPrimitiveKind(Class<?> cls) {
        if (cls == byte.class) return ScopeAnalysis.PrimitiveKind.BYTE;
        if (cls == short.class) return ScopeAnalysis.PrimitiveKind.SHORT;
        if (cls == int.class) return ScopeAnalysis.PrimitiveKind.INT;
        if (cls == long.class) return ScopeAnalysis.PrimitiveKind.LONG;
        if (cls == float.class) return ScopeAnalysis.PrimitiveKind.FLOAT;
        if (cls == double.class) return ScopeAnalysis.PrimitiveKind.DOUBLE;
        if (cls == boolean.class) return ScopeAnalysis.PrimitiveKind.BOOL;
        if (cls == char.class) return ScopeAnalysis.PrimitiveKind.CHAR;
        return ScopeAnalysis.PrimitiveKind.NONE;
    }

    static void emitReturnToJava(MethodVisitor m, ScopeAnalysis.PrimitiveKind fromKind, Class<?> retType,
                                 String description, int rtSlot) {
        if (retType == void.class) {
            m.visitInsn(fromKind.slots() == 2 ? POP2 : POP);
            m.visitInsn(RETURN);
            return;
        }
        ScopeAnalysis.PrimitiveKind retKind = javaPrimitiveKind(retType);
        if (retKind.isPrimitive() && fromKind.isPrimitive()) {
            if (fromKind == retKind || (isNumericKind(fromKind) && isNumericKind(retKind) && emitPrimitiveWidening(m, fromKind, retKind))) {
                m.visitInsn(returnInsn(retKind));
                return;
            }
        }
        boxOn(m, fromKind);
        emitPushClassConstant(m, retType);
        ConstantPool.pushString(m, description);
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

    // ---------------------------------------------------------------- interface hierarchy helpers

    InheritedDefault findInheritedDefault(ScopeAnalysis.TypeHierarchyInfo ownerHierarchy, String memberName) {
        if (ownerHierarchy == null) {
            return null;
        }
        for (ScopeAnalysis.ParentRef parent : ownerHierarchy.directParents()) {
            if (parent.babbleTypeId() != null) {
                InheritedDefault found = findDefaultInInterface(parent.babbleTypeId(), memberName, new HashSet<>());
                if (found != null) {
                    return new InheritedDefault(parent.babbleTypeId(), found.providerId(), found.providerSpec());
                }
            }
        }
        return null;
    }

    private InheritedDefault findDefaultInInterface(String ifaceId, String memberName, Set<String> visited) {
        if (ifaceId == null || !visited.add(ifaceId)) {
            return null;
        }
        ScopeAnalysis.TypeHierarchyInfo info = module.typeHierarchy(ifaceId);
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

    private boolean specHasMethod(ScopeAnalysis.MemberSpec spec, String bridgeKey, String descriptor) {
        if (spec == null) {
            return false;
        }
        if (spec.name().equals(bridgeKey) && spec.callable() && spec.declaredSignature() != null
                && bridgeDescriptor(spec.declaredSignature()).equals(descriptor)) {
            return true;
        }
        if (spec.isOverloaded() && bridgeKey.startsWith(spec.name() + "$")) {
            try {
                int idx = Integer.parseInt(bridgeKey.substring(spec.name().length() + 1));
                if (idx >= 0 && idx < spec.overloadSignatures().size()
                        && bridgeDescriptor(spec.overloadSignatures().get(idx)).equals(descriptor)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private boolean interfaceHasMethod(String ifaceId, String bridgeKey, String descriptor) {
        if (ifaceId == null || !allInterfaceJvmNames.containsKey(ifaceId)) {
            return false;
        }
        ScopeAnalysis.TypeHierarchyInfo info = module.typeHierarchy(ifaceId);
        if (info == null) {
            return false;
        }
        String memberName = bridgeKey;
        int dollar = bridgeKey.indexOf('$');
        if (dollar >= 0) {
            memberName = bridgeKey.substring(0, dollar);
        }
        if (specHasMethod(info.interfaceMembers().get(memberName), bridgeKey, descriptor)) {
            return true;
        }
        for (String ancId : info.allBabbleInterfaceIds()) {
            if (!allInterfaceJvmNames.containsKey(ancId)) {
                continue;
            }
            ScopeAnalysis.TypeHierarchyInfo ancInfo = module.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            if (specHasMethod(ancInfo.interfaceMembers().get(memberName), bridgeKey, descriptor)) {
                return true;
            }
        }
        return false;
    }

    int countDirectParentsWithMethod(ScopeAnalysis.TypeHierarchyInfo hierarchy, String bridgeKey, String descriptor) {
        if (hierarchy == null) {
            return 0;
        }
        int count = 0;
        for (ScopeAnalysis.ParentRef parent : hierarchy.directParents()) {
            if (parent.babbleTypeId() != null && interfaceHasMethod(parent.babbleTypeId(), bridgeKey, descriptor)) {
                count++;
            }
        }
        return count;
    }

    Map<String, AncestorMethod> collectAncestorMethods(ScopeAnalysis.TypeHierarchyInfo hierarchy) {
        Map<String, AncestorMethod> result = new LinkedHashMap<>();
        if (hierarchy == null) {
            return result;
        }
        for (String ancId : hierarchy.allBabbleInterfaceIds()) {
            if (!allInterfaceJvmNames.containsKey(ancId)) {
                continue;
            }
            ScopeAnalysis.TypeHierarchyInfo ancInfo = module.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            for (ScopeAnalysis.MemberSpec ancSpec : ancInfo.interfaceMembers().values()) {
                if (ancSpec.callable() && ancSpec.declaredSignature() != null) {
                    String desc = bridgeDescriptor(ancSpec.declaredSignature());
                    result.putIfAbsent(ancSpec.name() + "\0" + desc,
                            new AncestorMethod(ancSpec.name(), ancSpec.name(), -1, null, desc, ancSpec.declaredSignature()));
                } else if (ancSpec.isOverloaded()) {
                    List<ScopeAnalysis.FunctionSignature> sigs = ancSpec.overloadSignatures();
                    List<String> keys = ancSpec.overloadSignatureKeys();
                    for (int k = 0; k < sigs.size(); k++) {
                        ScopeAnalysis.FunctionSignature sig = sigs.get(k);
                        String bKey = ancSpec.name() + "$" + k;
                        String desc = bridgeDescriptor(sig);
                        String sigKey = k < keys.size() ? keys.get(k) : null;
                        result.putIfAbsent(bKey + "\0" + desc,
                                new AncestorMethod(ancSpec.name(), bKey, k, sigKey, desc, sig));
                    }
                }
            }
        }
        return result;
    }

    void emitInheritedDefaultInvoke(MethodVisitor m, InheritedDefault def, String directParentJvm,
                                    String memberName, String bridgeKey, String signatureKey,
                                    String targetDesc, ScopeAnalysis.FunctionSignature targetSig) {
        int[] paramSlots = bridgeParamSlots(targetSig);
        int argCount = targetSig.parameterKinds().length;
        String mName = bridgeMethodName(bridgeKey);
        if (directParentJvm != null && interfaceHasMethod(def.directParentId(), bridgeKey, targetDesc)) {
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < argCount; i++) {
                loadKindOn(m, targetSig.parameterKinds()[i], paramSlots[i]);
            }
            if (targetSig.hasOptionalMask()) {
                m.visitVarInsn(ILOAD, bridgeMaskSlot(targetSig));
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
                emitAdaptBridgeArg(m, targetSig, paramSlots, i, provSig.parameterKinds()[i], -1);
            }
            if (provSig.hasOptionalMask()) {
                if (targetSig.hasOptionalMask()) {
                    m.visitVarInsn(ILOAD, bridgeMaskSlot(targetSig));
                } else {
                    m.visitInsn(ICONST_0);
                }
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, bridgeMethodName(memberName), provDesc, true);
            emitAdaptKind(m, provSig.returnKind(), targetSig.returnKind());
            m.visitInsn(returnInsn(targetSig.returnKind()));
            return;
        }
        String siteField = constantPool.syntheticCallSiteField(memberName, argCount);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(memberName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECTS, "getMember", "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;", false);
        if (signatureKey != null) {
            m.visitLdcInsn(signatureKey);
            m.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "selectFrom", "(Ljava/lang/Object;Ljava/lang/String;)L" + CALLABLE + ";", false);
        } else {
            m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        }
        m.visitVarInsn(ALOAD, 1);
        packBridgeArgsArray(m, targetSig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, className, siteField, "L" + CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, targetSig.returnKind());
        m.visitInsn(returnInsn(targetSig.returnKind()));
    }
}
