package org.babblelang.compiler;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
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
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * ANTLR-free JVM bytecode emitter consuming a {@link HirModule} (BIP014-I1, BIP014-D8).
 *
 * <p>Coordinates module-level code generation, function/method/class/interface entry points,
 * frame variables and cells, and delegates expression families to {@link AssignAndMemberEmitter},
 * {@link ControlAndOpEmitter}, {@link CallEmitter}, {@link BridgeHelpers}, {@link ClassGen},
 * {@link InterfaceGen}, and {@link ConstantPool} using per-method state in
 * {@link MethodEmitContext} (BIP027-I2).
 */
final class HirEmitter {
    static final int SELF = 0;
    static final int INSTANCE = 1;
    static final int FN_RT = 2;
    static final int ARGS = 3;
    static final int NAMES = 4;
    static final int FN_NS = 6;
    static final int BINDER_LOCAL = 3;
    static final int FIRST_PARAMETER = 4;

    private static final int MAX_NODES_PER_RUN_METHOD = 500;

    final HirModule module;
    final String className;
    final String sourceName;
    final ClassLoader runtimeLoader;
    final ConstantPool constantPool;
    final Map<String, String> allInterfaceJvmNames;
    final Map<String, String> allClassJvmNames;
    final Map<String, List<String>> generatedClassParents = new HashMap<>();
    final Map<String, List<String>> generatedInterfaceParents = new HashMap<>();

    final MethodEmitContext ctx = new MethodEmitContext();
    final BridgeHelpers bridges;
    final AssignAndMemberEmitter assigns;
    final ControlAndOpEmitter controlOps;
    final CallEmitter calls;

    private final ClassWriter classWriter;
    private final org.babblelang.ir.mir.MirModule mirModule;
    private final Map<Integer, InterfaceGen> interfaceGens = new LinkedHashMap<>();
    private final Map<String, InterfaceGen> interfaceGensByTypeId = new LinkedHashMap<>();
    private final Map<Integer, ClassGen> classGens = new LinkedHashMap<>();
    private final Queue<Runnable> pendingMethods = new ArrayDeque<>();
    private final Set<Integer> scheduledFunctions = new HashSet<>();
    private final Map<Integer, String> compiledMethodFields = new HashMap<>();
    private final Map<String, String> cachedMethodOverloads = new HashMap<>();

    private int classBodyCount = 0;
    private int superHelperCount = 0;

    HirEmitter(HirModule module, ClassLoader runtimeLoader) {
        this(module, null, runtimeLoader);
    }

    HirEmitter(HirModule module, org.babblelang.ir.mir.MirModule mirModule, ClassLoader runtimeLoader) {
        this.module = module;
        this.mirModule = mirModule;
        this.className = module.className();
        this.sourceName = module.sourceName();
        this.runtimeLoader = runtimeLoader;
        this.constantPool = new ConstantPool(className, sourceName);
        this.allInterfaceJvmNames = module.allInterfaceJvmNames();
        this.allClassJvmNames = module.allClassJvmNames();
        this.classWriter = new ScriptClassWriter(runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
        this.bridges = new BridgeHelpers(this);
        this.assigns = new AssignAndMemberEmitter(this);
        this.controlOps = new ControlAndOpEmitter(this);
        this.calls = new CallEmitter(this);
    }

    int nextSuperHelper() {
        return superHelperCount++;
    }

    ClassGen classGen(int classId) {
        return classGens.get(classId);
    }

    InterfaceGen interfaceGenByTypeId(String typeId) {
        return interfaceGensByTypeId.get(typeId);
    }

    void line(int lineNumber) {
        ctx.line(lineNumber);
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
            gen.emitFastRecordConstructor();
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
        java.util.Set<Integer> fbipReusedClassIds = collectFbipReusedClassIds(module);
        for (HirClass cls : module.classes().values()) {
            if (!cls.hasCompiledClass()) {
                continue;
            }
            List<String> parents = new ArrayList<>(1 + cls.implementedJvmInterfaces().size());
            parents.add(OBJECT);
            parents.addAll(cls.implementedJvmInterfaces());
            generatedClassParents.put(cls.jvmName(), List.copyOf(parents));
            ClassGen gen = new ClassGen(this, cls, fbipReusedClassIds.contains(cls.id()));
            gen.begin();
            classGens.put(cls.id(), gen);
        }
    }

    private static java.util.Set<Integer> collectFbipReusedClassIds(HirModule module) {
        java.util.Set<Integer> reused = new java.util.HashSet<>();
        for (HirExpr e : module.topLevelStatements()) {
            scanRecordReuse(e, reused);
        }
        for (HirFunction fn : module.functions().values()) {
            for (HirParam p : fn.params()) {
                scanRecordReuse(p.defaultValue(), reused);
            }
            for (HirExpr e : fn.body()) {
                scanRecordReuse(e, reused);
            }
        }
        for (HirClass cls : module.classes().values()) {
            for (HirParam p : cls.params()) {
                scanRecordReuse(p.defaultValue(), reused);
            }
            for (HirExpr e : cls.body()) {
                scanRecordReuse(e, reused);
            }
            if (cls.staticBlock() != null) {
                for (HirExpr e : cls.staticBlock().body()) {
                    scanRecordReuse(e, reused);
                }
            }
        }
        return reused;
    }

    private static void scanRecordReuse(HirExpr expr, java.util.Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.RecordReuse rr && rr.classId() >= 0) {
            out.add(rr.classId());
        }
        org.babblelang.opt.HirRewriter.rewriteChildren(expr, child -> {
            scanRecordReuse(child, out);
            return child;
        });
    }

    // ---------------------------------------------------------------- run / chunking

    private void generateRun() {
        List<List<HirExpr>> chunks = splitStatements(module.topLevelStatements());

        beginMethod(0, ACC_STATIC, "run", RUN_DESCRIPTOR, 2, 0, 1);
        ctx.line(module.startLine());
        ctx.mv.visitVarInsn(ALOAD, ctx.nsLocal);
        getStringArray(module.topLevelDeclaredNames());
        ctx.mv.visitFieldInsn(GETSTATIC, className, constantPool.booleanArrayField(module.topLevelDeclaredFinals()), "[Z");
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "declare", "([Ljava/lang/String;[Z)V", false);

        if (chunks.size() == 1) {
            statements(chunks.get(0));
            convertStack(ctx.stackKind, PrimitiveKind.NONE);
            ctx.mv.visitInsn(ARETURN);
            ctx.finish();
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            ctx.mv.visitVarInsn(ALOAD, 0);
            ctx.mv.visitVarInsn(ALOAD, 1);
            ctx.mv.visitMethodInsn(INVOKESTATIC, className, "run$" + i, RUN_DESCRIPTOR, false);
            ctx.mv.visitInsn(i == chunks.size() - 1 ? ARETURN : POP);
        }
        ctx.finish();

        for (int i = 0; i < chunks.size(); i++) {
            beginMethod(0, ACC_PRIVATE | ACC_STATIC, "run$" + i, RUN_DESCRIPTOR, 2, 0, 1);
            statements(chunks.get(i));
            convertStack(ctx.stackKind, PrimitiveKind.NONE);
            ctx.mv.visitInsn(ARETURN);
            ctx.finish();
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
            case HirExpr.CoreExceptionRef r -> 1;
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
            case HirExpr.JavaSamAdapt j -> 1 + countNodes(j.value());
            case HirExpr.TypeApply t -> 1 + countNodes(t.inner());
            case HirExpr.Break b -> 1 + countNodes(b.value());
            case HirExpr.Return r -> 1 + countNodes(r.value());
            case HirExpr.Throw th -> 1 + countNodes(th.value());
            case HirExpr.Block b -> 1 + countList(b.statements());
            case HirExpr.If i -> 1 + countNodes(i.cond()) + countNodes(i.thenBranch()) + countNodes(i.elseBranch());
            case HirExpr.While w -> 1 + countNodes(w.cond()) + countNodes(w.body());
            case HirExpr.For f -> 2 + countNodes(f.iterable()) + countList(f.body());
            case HirExpr.Try t -> {
                int sum = 3 + countList(t.tryBody());
                for (HirExpr.HirCatch cc : t.catches()) {
                    sum += 2 + countList(cc.body());
                }
                if (t.hasFinally()) {
                    sum += (2 + t.catches().size()) * Math.max(1, countList(t.finallyBody()));
                }
                yield sum;
            }
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
            case HirExpr.RecordNew r -> 1 + countNodes(r.classRef()) + countList(r.args());
            case HirExpr.RecordReuse r -> 1 + countNodes(r.target()) + countList(r.args());
            case HirExpr.Move m -> 1 + countNodes(m.operand());
            case HirExpr.Freeze f -> 1 + countNodes(f.operand());
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
        MethodVisitor mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        ctx.reset(mv, ownerId, firstFreeLocal, rt, ns, captured);
    }

    void getStringArray(String[] values) {
        ctx.mv.visitFieldInsn(GETSTATIC, className, constantPool.stringArrayField(values), "[Ljava/lang/String;");
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
                    m -> m.visitMethodInsn(INVOKESTATIC, className, method, direct, false));
            beginMethod(classWriter, fn.id(), ACC_STATIC | ACC_SYNTHETIC, method, direct,
                    FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count, fn.capturedVars());
            ctx.parameterBase = FIRST_PARAMETER;
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
        ctx.methodReturnKind = fn.returnKind();
        loadCapturedCells(COMPILED_FUNCTION);
        functionBodySpec(fn, COMPILED_FUNCTION, paramSlots, maskSlot);
    }

    private void callAdapter(ClassWriter writer, int access, String name, String descriptor, String paramsField,
                             int count, int kwStart, int restIndex, java.util.function.Consumer<MethodVisitor> callDirect) {
        MethodVisitor m = writer.visitMethod(access, name, descriptor, null, null);
        m.visitCode();
        int binder = FN_NS;
        m.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        if (restIndex >= 0) {
            ConstantPool.pushInt(m, kwStart);
            ConstantPool.pushInt(m, restIndex);
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;II[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        } else if (kwStart < count) {
            ConstantPool.pushInt(m, kwStart);
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        } else {
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        }
        m.visitVarInsn(ASTORE, binder);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, FN_RT);
        m.visitVarInsn(ALOAD, binder);
        for (int i = 0; i < count; i++) {
            m.visitVarInsn(ALOAD, binder);
            ConstantPool.pushInt(m, i);
            m.visitMethodInsn(INVOKEVIRTUAL, BINDER, "take", "(I)Ljava/lang/Object;", false);
        }
        callDirect.accept(m);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    String compiledMethodField(HirFunction fn, ClassGen gen) {
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

    String cachedMethodOverloadField(HirExpr.MakeOverloadSet mos, ClassGen gen) {
        StringBuilder key = new StringBuilder(gen.name);
        List<String> methodFields = new ArrayList<>(mos.operands().size());
        for (HirExpr op : mos.operands()) {
            HirExpr.FunctionRef ref = (HirExpr.FunctionRef) op;
            key.append('#').append(ref.functionId());
            methodFields.add(compiledMethodField(module.function(ref.functionId()), gen));
        }
        String k = key.toString();
        String existing = cachedMethodOverloads.get(k);
        if (existing != null) {
            return existing;
        }
        String field = constantPool.add("OVERLOAD", "L" + OVERLOAD_SET + ";", 40 + mos.metas().size() * 24, m -> {
            ConstantPool.pushInt(m, methodFields.size());
            m.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int i = 0; i < methodFields.size(); i++) {
                m.visitInsn(DUP);
                ConstantPool.pushInt(m, i);
                m.visitFieldInsn(GETSTATIC, className, methodFields.get(i), "L" + COMPILED_METHOD + ";");
                m.visitInsn(AASTORE);
            }
            CallEmitter.emitOverloadSetMetaAndCreate(m, mos.metas());
        });
        cachedMethodOverloads.put(k, field);
        return field;
    }

    private void generateMethod(HirFunction fn, ClassGen gen, String paramsField) {
        String method = fn.jvmMethodName();
        if (fn.isSpread()) {
            int count = fn.params().size();
            String direct = methodDirectDescriptor(fn);
            callAdapter(gen.writer, ACC_PUBLIC | ACC_SYNTHETIC, method + "$call", METHOD_DESCRIPTOR, paramsField, count, fn.keywordOnlyStart(), fn.restIndex(),
                    m -> m.visitMethodInsn(INVOKEVIRTUAL, gen.name, method, direct, false));
            beginMethod(gen.writer, fn.id(), ACC_PUBLIC | ACC_SYNTHETIC, method, direct,
                    FIRST_PARAMETER + count + 1, FN_RT, FIRST_PARAMETER + count, fn.capturedVars());
            ctx.parameterBase = FIRST_PARAMETER;
        } else {
            beginMethod(gen.writer, fn.id(), ACC_PUBLIC | ACC_SYNTHETIC, method, METHOD_DESCRIPTOR,
                    FN_NS + 1, FN_RT, FN_NS, fn.capturedVars());
        }
        ctx.selfLocal = 1;
        ctx.thizLocal = 0;
        ctx.methodClass = gen;
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
        beginMethod(gen.writer, fn.id(), ACC_PUBLIC | ACC_SYNTHETIC, method + "$spec",
                methodSpecDescriptor(fn), nsSlot + 1, FN_RT, nsSlot, fn.capturedVars());
        ctx.methodReturnKind = fn.returnKind();
        ctx.selfLocal = 1;
        ctx.thizLocal = 0;
        ctx.methodClass = gen;
        loadCapturedCells(BABBLE_CLASS);
        functionBodySpec(fn, COMPILED_METHOD, paramSlots, maskSlot);
    }

    private void loadClosure() {
        MethodVisitor mv = ctx.mv;
        if (ctx.methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, ctx.methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
            mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        } else {
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitMethodInsn(INVOKEVIRTUAL, COMPILED_FUNCTION, "closure", "()L" + NAMESPACE + ";", false);
        }
    }

    private void loadRecurseTarget(String selfType) {
        MethodVisitor mv = ctx.mv;
        mv.visitVarInsn(ALOAD, ctx.selfLocal);
        mv.visitVarInsn(ALOAD, ctx.thizLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, selfType, "recurseTarget", "(Ljava/lang/Object;)L" + CALLABLE + ";", false);
    }

    private void initFrameLocals(HirFunction fn, String selfType, org.babblelang.ir.mir.MirFunction mirFn) {
        MethodVisitor mv = ctx.mv;
        if (fn.namespaceScope()) {
            loadClosure();
            loadRecurseTarget(selfType);
            mv.visitVarInsn(ALOAD, ctx.thizLocal);
            getStringArray(fn.declaredNames());
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "enter", "(L" + NAMESPACE + ";L" + CALLABLE + ";Ljava/lang/Object;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
            mv.visitVarInsn(ASTORE, ctx.frameNs);
        } else {
            loadClosure();
            mv.visitVarInsn(ASTORE, ctx.frameNs);
            List<HirVar> toDeclare = fn.declaredVars();
            if (mirFn != null) {
                Set<Integer> needed = new HashSet<>();
                for (HirVar pv : mirFn.paramVars()) {
                    needed.add(pv.id());
                }
                for (HirVar pv : mirFn.pinnedVars()) {
                    needed.add(pv.id());
                }
                for (HirParam p : fn.params()) {
                    if (p.var() != null) {
                        needed.add(p.var().id());
                    }
                }
                List<HirVar> filtered = new ArrayList<>(toDeclare.size());
                for (HirVar v : toDeclare) {
                    if (needed.contains(v.id())
                            || v.kind() == Resolution.Variable.Kind.THIS
                            || v.kind() == Resolution.Variable.Kind.RECURSE) {
                        filtered.add(v);
                    }
                }
                toDeclare = filtered;
            }
            for (HirVar variable : declareVariables(toDeclare)) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                    mv.visitVarInsn(ALOAD, ctx.thizLocal);
                    initVariable(variable, PrimitiveKind.NONE);
                } else if (variable.kind() == Resolution.Variable.Kind.RECURSE && variable.used()) {
                    loadRecurseTarget(selfType);
                    initVariable(variable, PrimitiveKind.NONE);
                }
            }
        }
    }

    private void functionBody(HirFunction fn, String paramsField, String selfType) {
        ctx.beginTailLoop(null, -1);
        org.babblelang.ir.mir.MirFunction mirFn = mirModule != null ? mirModule.function(fn.id()) : null;
        initFrameLocals(fn, selfType, mirFn);
        bindParameters(paramsField, fn.params(), fn.keywordOnlyStart(), fn.restIndex(), null);
        if (mirFn != null) {
            new MirEmitter(this, mirFn, fn, PrimitiveKind.NONE).emit();
            ctx.finish();
            return;
        }
        statements(fn.body());
        widenStackRank(fn.bodyWideningRank());
        convertStack(ctx.stackKind, PrimitiveKind.NONE);
        ctx.mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void functionBodySpec(HirFunction fn, String selfType, int[] paramSlots, int maskSlot) {
        MethodVisitor mv = ctx.mv;
        ctx.beginTailLoop(paramSlots, maskSlot);
        org.babblelang.ir.mir.MirFunction mirFn = mirModule != null ? mirModule.function(fn.id()) : null;
        initFrameLocals(fn, selfType, mirFn);
        List<HirParam> params = fn.params();
        int optBit = 0;
        for (int i = 0; i < params.size(); i++) {
            HirParam param = params.get(i);
            PrimitiveKind pk = param.kind();
            if (param.defaultValue() != null) {
                Label haveValue = new Label();
                mv.visitVarInsn(ILOAD, maskSlot);
                ConstantPool.pushInt(mv, 1 << optBit);
                mv.visitInsn(IAND);
                mv.visitJumpInsn(IFEQ, haveValue);
                emitAs(param.defaultValue(), pk);
                if (pk == PrimitiveKind.NONE && param.wideningRank() >= 0) {
                    emitWidenBoxed(mv, param.wideningRank());
                }
                storeKindOn(mv, pk, paramSlots[i]);
                mv.visitLabel(haveValue);
                optBit++;
            }
            if (param.samTarget() != null && pk == PrimitiveKind.NONE) {
                loadKindOn(mv, pk, paramSlots[i]);
                controlOps.emitToJavaSamOnStack(param.samTarget(), param.line());
                storeKindOn(mv, pk, paramSlots[i]);
            }
            HirVar variable = param.var();
            if (variable != null) {
                loadKindOn(mv, pk, paramSlots[i]);
                if (variable.cell()) {
                    storeVariable(variable, true, pk, param.line());
                    popOn(mv, pk);
                } else {
                    initVariable(variable, pk);
                }
            } else {
                mv.visitVarInsn(ALOAD, ctx.frameNs);
                mv.visitLdcInsn(param.name());
                loadKindOn(mv, pk, paramSlots[i]);
                boxOn(mv, pk);
                mv.visitInsn(param.mutable() ? ICONST_0 : ICONST_1);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;Z)V", false);
            }
        }
        if (mirFn != null) {
            new MirEmitter(this, mirFn, fn, ctx.methodReturnKind).emit();
            ctx.finish();
            return;
        }
        statements(fn.body());
        widenStackRank(fn.bodyWideningRank());
        convertStack(ctx.stackKind, ctx.methodReturnKind);
        mv.visitInsn(returnInsn(ctx.methodReturnKind));
        ctx.finish();
    }

    private void generateClassBody(HirClass cls, String method) {
        ClassShape shape = cls.shape();
        // Package-private so compiled sibling class/interface bodies in this script can adapt cls$... via LambdaMetafactory.
        beginMethod(classWriter, cls.id(), ACC_STATIC | ACC_SYNTHETIC, method,
                CLASS_BODY_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS, cls.capturedVars());
        MethodVisitor mv = ctx.mv;
        loadCapturedCells(BABBLE_CLASS);

        ClassGen gen = classGens.get(cls.id());
        ctx.constructorClass = gen;
        boolean deferredInit = gen != null && gen.hasFinalHeaderFields();
        if (gen != null && !deferredInit) {
            ctx.typedThisLocal = ctx.newLocal();
            mv.visitVarInsn(ALOAD, INSTANCE);
            mv.visitTypeInsn(CHECKCAST, gen.name);
            mv.visitVarInsn(ASTORE, ctx.typedThisLocal);
        } else {
            ctx.typedThisLocal = -1;
        }
        mv.visitVarInsn(ALOAD, SELF);
        mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, FN_NS);
        for (HirVar variable : declareVariables(cls.declaredVars())) {
            if (!deferredInit && variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                mv.visitVarInsn(ALOAD, INSTANCE);
                initVariable(variable, PrimitiveKind.NONE);
            }
        }

        Map<Integer, Integer> deferredMembers = deferredInit ? new java.util.LinkedHashMap<>() : null;
        bindParameters(constantPool.stringArrayField(shape.parameters()), cls.params(), shape.keywordOnlyStart(), -1, shape, deferredMembers);
        if (deferredInit) {
            ctx.typedThisLocal = ctx.newLocal();
            mv.visitTypeInsn(NEW, gen.name);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitVarInsn(ALOAD, FN_RT);
            for (int memberIdx : gen.finalHeaderMemberIndices) {
                loadKindOn(mv, gen.memberKinds[memberIdx], deferredMembers.get(memberIdx));
            }
            mv.visitMethodInsn(INVOKESPECIAL, gen.name, "<init>", gen.initDescriptor, false);
            mv.visitVarInsn(ASTORE, ctx.typedThisLocal);
            for (HirVar variable : cls.declaredVars()) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    initVariable(variable, PrimitiveKind.NONE);
                } else if (variable.isCaptured()) {
                    publishHidden(variable);
                }
            }
            for (Map.Entry<Integer, Integer> entry : deferredMembers.entrySet()) {
                int memberIdx = entry.getKey();
                if (!gen.isFinalField(memberIdx)) {
                    PrimitiveKind mk = gen.memberKinds[memberIdx];
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    loadKindOn(mv, mk, entry.getValue());
                    mv.visitFieldInsn(PUTFIELD, gen.name, shape.member(memberIdx), jvmDescriptor(mk));
                }
            }
        }
        statements(cls.body());
        popOn(mv, ctx.stackKind);
        mv.visitVarInsn(ALOAD, deferredInit ? ctx.typedThisLocal : INSTANCE);
        mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void bindParameters(String paramsField, List<HirParam> params, int kwStart, int restIndex, ClassShape shape) {
        bindParameters(paramsField, params, kwStart, restIndex, shape, null);
    }

    private void bindParameters(String paramsField, List<HirParam> params, int kwStart, int restIndex, ClassShape shape,
                                Map<Integer, Integer> deferredMembers) {
        MethodVisitor mv = ctx.mv;
        boolean spread = ctx.parameterBase >= 0;
        int binder = spread ? BINDER_LOCAL : ctx.newLocal();
        int arrayValue = spread ? -1 : ctx.newLocal();
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
            int value = spread ? ctx.parameterBase + i : arrayValue;

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
                ctx.deferred.add(() -> {
                    ctx.mv.visitLabel(computeDefault);
                    emitBoxed(defaultValue);
                    ctx.mv.visitVarInsn(ASTORE, value);
                    ctx.mv.visitJumpInsn(GOTO, bind);
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
            if (param.samTarget() != null) {
                mv.visitVarInsn(ALOAD, value);
                controlOps.emitToJavaSamOnStack(param.samTarget(), param.line());
                mv.visitVarInsn(ASTORE, value);
            }
            int member = shape != null ? param.memberIndex() : -1;
            if (member >= 0 && ctx.constructorClass != null) {
                PrimitiveKind mk = ctx.constructorClass.memberKinds[member];
                if (deferredMembers != null) {
                    mv.visitVarInsn(ALOAD, value);
                    ctx.stackKind = PrimitiveKind.NONE;
                    convertStack(PrimitiveKind.NONE, mk);
                    int mSlot = ctx.newLocal(mk);
                    storeKindOn(mv, mk, mSlot);
                    deferredMembers.put(member, mSlot);
                    ctx.stackKind = PrimitiveKind.NONE;
                } else {
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    mv.visitVarInsn(ALOAD, value);
                    ctx.stackKind = PrimitiveKind.NONE;
                    convertStack(PrimitiveKind.NONE, mk);
                    mv.visitFieldInsn(PUTFIELD, ctx.constructorClass.name, shape.member(member), jvmDescriptor(mk));
                    ctx.stackKind = PrimitiveKind.NONE;
                }
            } else if (param.var() != null) {
                HirVar variable = param.var();
                PrimitiveKind vk = variable.primitiveKind();
                mv.visitVarInsn(ALOAD, value);
                ctx.stackKind = PrimitiveKind.NONE;
                convertStack(PrimitiveKind.NONE, vk);
                if (variable.cell()) {
                    storeVariable(variable, true, vk, param.line());
                    popOn(mv, vk);
                } else {
                    initVariable(variable, vk);
                }
            } else {
                mv.visitVarInsn(ALOAD, ctx.frameNs);
                mv.visitLdcInsn(param.name());
                mv.visitVarInsn(ALOAD, value);
                mv.visitInsn(param.mutable() ? ICONST_0 : ICONST_1);
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;Z)V", false);
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
        if (ctx.capturedVariables.isEmpty()) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        ctx.capturedLocal = ctx.newLocal();
        if (ctx.methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, ctx.methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
        } else {
            mv.visitVarInsn(ALOAD, SELF);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, ownerType, "captured", "()[Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, ctx.capturedLocal);
    }

    List<HirVar> declareVariables(List<HirVar> variables) {
        MethodVisitor mv = ctx.mv;
        for (HirVar variable : variables) {
            PrimitiveKind vk = variable.primitiveKind();
            int slot = ctx.newLocal(variable.cell() ? PrimitiveKind.NONE : vk);
            ctx.slots.put(variable.id(), slot);
            if (!variable.cell() && vk.isPrimitive() && variable.mayBeUndefined() && !variable.definitelyDefined()) {
                int defSlot = ctx.newLocal(PrimitiveKind.BOOL);
                ctx.definedSlots.put(variable.id(), defSlot);
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
            if (variable.kind() == Resolution.Variable.Kind.DEFINITION
                    || (variable.kind() == Resolution.Variable.Kind.PARAMETER && variable.cell())) {
                if (variable.cell()) {
                    String cc = cellClass(vk);
                    mv.visitMethodInsn(INVOKESTATIC, cc, "undefined", "()L" + cc + ";", false);
                    mv.visitVarInsn(ASTORE, slot);
                    publishHidden(variable);
                } else if (vk.isPrimitive()) {
                    emitDefaultValueOn(mv, vk);
                    storeKindOn(mv, vk, slot);
                } else {
                    mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
                    mv.visitVarInsn(ASTORE, slot);
                }
            }
        }
        return variables;
    }

    private void publishHidden(HirVar variable) {
        if (ctx.constructorClass != null && ctx.typedThisLocal >= 0 && variable.isCaptured()
                && ctx.constructorClass.hasHiddenField(variable)) {
            PrimitiveKind vk = variable.cell() ? PrimitiveKind.NONE : variable.primitiveKind();
            ctx.mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
            loadKindOn(ctx.mv, vk, ctx.slots.get(variable.id()));
            ctx.mv.visitFieldInsn(PUTFIELD, ctx.constructorClass.name, variable.hiddenFieldName(), jvmDescriptor(vk));
        }
    }

    void initVariable(HirVar variable, PrimitiveKind vk) {
        MethodVisitor mv = ctx.mv;
        if (variable.cell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(INVOKESTATIC, cc, "of", "(" + jvmDescriptor(vk) + ")L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, ctx.slots.get(variable.id()));
            publishHidden(variable);
            return;
        }
        storeKindOn(mv, vk, ctx.slots.get(variable.id()));
        Integer defSlot = ctx.definedSlots.get(variable.id());
        if (defSlot != null) {
            mv.visitInsn(ICONST_1);
            mv.visitVarInsn(ISTORE, defSlot);
        }
        if (variable.capturedByValue()) {
            publishHidden(variable);
        }
    }

    private void loadCell(HirVar variable) {
        MethodVisitor mv = ctx.mv;
        String cc = cellClass(variable.primitiveKind());
        if (variable.ownerMethodId() == ctx.methodOwnerId) {
            mv.visitVarInsn(ALOAD, ctx.slots.get(variable.id()));
        } else if (ctx.methodClass != null && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, ctx.methodClass.name, variable.hiddenFieldName(), "Ljava/lang/Object;");
            mv.visitTypeInsn(CHECKCAST, cc);
        } else {
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, cc);
        }
    }

    void loadVariable(HirVar variable, int lineNumber) {
        MethodVisitor mv = ctx.mv;
        PrimitiveKind vk = variable.primitiveKind();
        if (variable.cell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            loadCell(variable);
            if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
                ctx.line(lineNumber);
                mv.visitLdcInsn(variable.name());
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "read", "(Ljava/lang/String;)" + desc, false);
            } else {
                mv.visitFieldInsn(GETFIELD, cc, "value", desc);
            }
            ctx.stackKind = vk;
            return;
        }
        if (variable.capturedByValue() && variable.ownerMethodId() != ctx.methodOwnerId) {
            if (ctx.methodClass != null && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, ctx.methodClass.name, variable.hiddenFieldName(), jvmDescriptor(vk));
                ctx.stackKind = vk;
                return;
            }
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
            if (vk.isPrimitive()) {
                unboxOn(mv, vk);
            }
            ctx.stackKind = vk;
            return;
        }
        if (vk.isPrimitive()) {
            Integer defSlot = ctx.definedSlots.get(variable.id());
            if (defSlot != null) {
                mv.visitVarInsn(ILOAD, defSlot);
                mv.visitLdcInsn(variable.name());
                ctx.line(lineNumber);
                mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
            }
            loadKindOn(mv, vk, ctx.slots.get(variable.id()));
            ctx.stackKind = vk;
            return;
        }
        mv.visitVarInsn(ALOAD, ctx.slots.get(variable.id()));
        if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
            ctx.line(lineNumber);
            mv.visitLdcInsn(variable.name());
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "defined", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void storeVariable(HirVar variable, boolean definition, PrimitiveKind vk, int lineNumber) {
        MethodVisitor mv = ctx.mv;
        if (variable.cell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            int tmp = ctx.newLocal(vk);
            storeKindOn(mv, vk, tmp);
            loadCell(variable);
            loadKindOn(mv, vk, tmp);
            if (definition) {
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "define", "(" + desc + ")" + desc, false);
            } else {
                mv.visitLdcInsn(variable.name());
                ctx.line(lineNumber);
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "assign", "(" + desc + "Ljava/lang/String;)" + desc, false);
            }
            ctx.stackKind = vk;
            return;
        }
        int slot = ctx.slots.get(variable.id());
        if (vk.isPrimitive()) {
            Integer defSlot = ctx.definedSlots.get(variable.id());
            if (defSlot != null) {
                if (definition) {
                    mv.visitInsn(ICONST_1);
                    mv.visitVarInsn(ISTORE, defSlot);
                } else {
                    mv.visitVarInsn(ILOAD, defSlot);
                    mv.visitLdcInsn(variable.name());
                    ctx.line(lineNumber);
                    mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
                }
            }
            dupOn(mv, vk);
            storeKindOn(mv, vk, slot);
            if (definition && variable.capturedByValue()) {
                publishHidden(variable);
            }
            ctx.stackKind = vk;
            return;
        }
        if (!definition && variable.mayBeUndefined() && !variable.definitelyDefined()) {
            mv.visitVarInsn(ALOAD, slot);
            mv.visitLdcInsn(variable.name());
            ctx.line(lineNumber);
            mv.visitMethodInsn(INVOKESTATIC, FRAMES, "checkAssignable", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, slot);
        if (definition && variable.capturedByValue()) {
            publishHidden(variable);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void loadVarOrName(HirVar var, String name, int lineNumber) {
        if (var != null) {
            loadVariable(var, lineNumber);
            return;
        }
        MethodVisitor mv = ctx.mv;
        ctx.line(lineNumber);
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        if (ctx.cacheNameReads) {
            mv.visitInvokeDynamicInsn("name", "(L" + NAMESPACE + ";)Ljava/lang/Object;", NAME_BOOTSTRAP, name);
        } else {
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void capturedCells(List<HirVar> captured) {
        MethodVisitor mv = ctx.mv;
        if (captured.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, captured.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < captured.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            HirVar variable = captured.get(i);
            if (variable.cell()) {
                loadCell(variable);
            } else {
                loadCapturedValueBoxed(variable);
            }
            mv.visitInsn(AASTORE);
        }
    }

    private void loadCapturedValueBoxed(HirVar variable) {
        MethodVisitor mv = ctx.mv;
        PrimitiveKind vk = variable.primitiveKind();
        if (variable.ownerMethodId() == ctx.methodOwnerId) {
            loadKindOn(mv, vk, ctx.slots.get(variable.id()));
            if (vk.isPrimitive()) {
                boxOn(mv, vk);
            }
        } else if (ctx.methodClass != null && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, ctx.methodClass.name, variable.hiddenFieldName(), jvmDescriptor(vk));
            if (vk.isPrimitive()) {
                boxOn(mv, vk);
            }
        } else {
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
        }
    }

    void inFrameScope(Runnable body) {
        int savedNextLocal = ctx.nextLocal;
        Map<Integer, Integer> savedSlots = new HashMap<>(ctx.slots);
        Map<Integer, Integer> savedDefinedSlots = new HashMap<>(ctx.definedSlots);
        body.run();
        ctx.nextLocal = savedNextLocal;
        ctx.slots = savedSlots;
        ctx.definedSlots = savedDefinedSlots;
    }

    void withNamespace(int namespace, Runnable body) {
        int outer = ctx.nsLocal;
        int savedNextLocal = ctx.nextLocal;
        ctx.nsLocal = namespace;
        try {
            body.run();
        } finally {
            ctx.nsLocal = outer;
            ctx.nextLocal = savedNextLocal;
        }
    }

    // ---------------------------------------------------------------- statements & expression dispatch

    void statements(List<HirExpr> statements) {
        if (statements.isEmpty()) {
            ctx.mv.visitInsn(ACONST_NULL);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        int last = statements.size() - 1;
        for (int i = 0; i < last; i++) {
            emitExpr(statements.get(i));
            popOn(ctx.mv, ctx.stackKind);
        }
        emitAndWiden(statements.get(last));
    }

    void emitExpr(HirExpr expr) {
        if (ctx.mirOverrides != null) {
            MethodEmitContext.MirOperand op = ctx.mirOverrides.get(expr);
            if (op != null) {
                loadMirOperand(op);
                return;
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
        switch (expr) {
            case HirExpr.Const c -> emitConst(c);
            case HirExpr.IntParseFallback f -> emitIntParseFallback(f);
            case HirExpr.CoreCollectionRef r -> emitCoreCollectionRef(r);
            case HirExpr.CoreExceptionRef r -> emitCoreExceptionRef(r);
            case HirExpr.VarRead v -> loadVariable(v.var(), v.meta().line());
            case HirExpr.NameRead n -> loadVarOrName(null, n.name(), n.meta().line());
            case HirExpr.ThisRef t -> loadVarOrName(t.var(), "this", t.meta().line());
            case HirExpr.RecurseRef r -> loadVarOrName(r.var(), "$recurse", r.meta().line());
            case HirExpr.Def d -> assigns.emitDef(d);
            case HirExpr.VarAssign a -> assigns.emitVarAssign(a);
            case HirExpr.NameAssign a -> assigns.emitNameAssign(a);
            case HirExpr.ThisFieldAssign a -> assigns.emitThisFieldAssign(a);
            case HirExpr.JavaFieldAssign a -> assigns.emitJavaFieldAssignExpr(a);
            case HirExpr.DynamicFieldAssign a -> assigns.emitDynamicFieldAssign(a);
            case HirExpr.ThisFieldGet g -> assigns.emitThisFieldGet(g);
            case HirExpr.JavaFieldGet g -> assigns.emitJavaFieldGet(g);
            case HirExpr.ArrayCreate c -> assigns.emitArrayCreate(c);
            case HirExpr.ArrayOp op -> assigns.emitArrayOp(op);
            case HirExpr.ModuleExportGet g -> assigns.emitModuleExportGet(g);
            case HirExpr.DynamicSelect s -> assigns.emitDynamicSelect(s);
            case HirExpr.Unary u -> controlOps.emitUnary(u);
            case HirExpr.Binary b -> controlOps.emitBinary(b);
            case HirExpr.Logic l -> controlOps.emitLogic(l);
            case HirExpr.Is i -> controlOps.emitIs(i);
            case HirExpr.As a -> controlOps.emitAsExpr(a);
            case HirExpr.NonNull n -> controlOps.emitNonNull(n);
            case HirExpr.SpreadAdapt s -> calls.emitSpreadAdapt(s);
            case HirExpr.JavaSamAdapt j -> controlOps.emitJavaSamAdapt(j);
            case HirExpr.Block b -> controlOps.emitBlock(b);
            case HirExpr.If i -> controlOps.emitIf(i);
            case HirExpr.While w -> controlOps.emitWhile(w);
            case HirExpr.For f -> controlOps.emitFor(f);
            case HirExpr.Try t -> controlOps.emitTry(t);
            case HirExpr.Break b -> controlOps.emitBreak(b);
            case HirExpr.Continue c -> controlOps.emitContinue();
            case HirExpr.Return r -> controlOps.emitReturn(r);
            case HirExpr.Throw th -> controlOps.emitThrow(th);
            case HirExpr.CoreCollectionCall c -> calls.emitCoreCollectionCall(c);
            case HirExpr.SuperCall c -> calls.emitSuperCall(c);
            case HirExpr.SelfRecurseCall c -> calls.emitSelfRecurseCall(c);
            case HirExpr.SelfTailCall c -> calls.emitSelfTailCall(c);
            case HirExpr.DirectBabbleCall c -> calls.emitDirectBabbleCall(c);
            case HirExpr.DirectThisMethodCall c -> calls.emitDirectThisMethodCall(c);
            case HirExpr.InterfaceCall c -> calls.emitInterfaceCall(c);
            case HirExpr.JavaConstructorCall c -> calls.emitJavaConstructorCall(c);
            case HirExpr.JavaMethodCall c -> calls.emitJavaMethodCall(c);
            case HirExpr.SafeCall c -> calls.emitSafeCall(c);
            case HirExpr.DynamicCall c -> calls.emitDynamicCall(c);
            case HirExpr.MakeOverloadSet m -> calls.emitMakeOverloadSet(m);
            case HirExpr.OverloadSelect s -> calls.emitOverloadSelect(s);
            case HirExpr.OverloadDispatch d -> calls.emitOverloadDispatch(d);
            case HirExpr.FunctionRef f -> emitFunctionRef(f);
            case HirExpr.ClassExpr c -> emitClassExpr(c);
            case HirExpr.InterfaceExpr i -> emitInterfaceExpr(i);
            case HirExpr.TypeApply t -> emitBoxed(t.inner());
            case HirExpr.RecordNew r -> calls.emitRecordNew(r);
            case HirExpr.RecordReuse r -> calls.emitRecordReuse(r);
            case HirExpr.Move m -> emitAndWiden(m.operand());
            case HirExpr.Freeze f -> emitAndWiden(f.operand());
        }
    }

    private void loadMirOperand(MethodEmitContext.MirOperand op) {
        if (op.isStacked()) {
            // Already on the operand stack, left there by the immediately preceding MIR instruction.
        } else if (op.isConstant()) {
            MirEmitter.pushConstant(ctx.mv, op.constant());
        } else {
            loadKindOn(ctx.mv, op.kind(), op.slot());
        }
        ctx.stackKind = op.kind();
    }

    void emitAndWiden(HirExpr expr) {
        if (ctx.mirOverrides != null) {
            MethodEmitContext.MirOperand op = ctx.mirOverrides.get(expr);
            if (op != null) {
                loadMirOperand(op);
                return;
            }
        }
        emitExpr(expr);
        widenStackRank(expr.meta().wideningRank());
    }

    void emitAs(HirExpr expr, PrimitiveKind desired) {
        emitAndWiden(expr);
        if (ctx.stackKind == PrimitiveKind.NONE && desired.isPrimitive()) {
            PrimitiveKind exprKind = expr.meta().kind();
            if (exprKind.isPrimitive() && expr.meta().wideningRank() < 0 && exprKind != desired) {
                unboxOn(ctx.mv, exprKind);
                ctx.stackKind = exprKind;
                convertStack(ctx.stackKind, desired);
                return;
            }
        }
        convertStack(ctx.stackKind, desired);
    }

    void emitBoxed(HirExpr expr) {
        emitAs(expr, PrimitiveKind.NONE);
    }

    void convertStack(PrimitiveKind from, PrimitiveKind to) {
        if (from == to) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        if (to == PrimitiveKind.NONE) {
            boxOn(mv, from);
        } else if (from == PrimitiveKind.NONE) {
            unboxOn(mv, to);
        } else if (!emitPrimitiveWidening(mv, from, to)) {
            boxOn(mv, from);
            unboxOn(mv, to);
        }
        ctx.stackKind = to;
    }

    void widenStackRank(int rank) {
        if (rank < 0) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        PrimitiveKind target = targetKindForRank(rank);
        if (ctx.stackKind.isPrimitive() && target != PrimitiveKind.NONE) {
            convertStack(ctx.stackKind, target);
            return;
        }
        if (rank == 5) {
            if (emitPrimitiveWidening(mv, ctx.stackKind, PrimitiveKind.DOUBLE)) {
                ctx.stackKind = PrimitiveKind.DOUBLE;
            } else if (ctx.stackKind == PrimitiveKind.NONE) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "toDouble", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
        } else {
            boxOn(mv, ctx.stackKind);
            ConstantPool.pushInt(mv, rank);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "widenNumeric", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            ctx.stackKind = PrimitiveKind.NONE;
        }
    }

    // ---------------------------------------------------------------- literals

    private void emitConst(HirExpr.Const c) {
        MethodVisitor mv = ctx.mv;
        Object v = c.value();
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
            ctx.stackKind = PrimitiveKind.NONE;
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
            ctx.stackKind = PrimitiveKind.BOOL;
        } else if (v instanceof Byte b) {
            ConstantPool.pushInt(mv, b);
            ctx.stackKind = PrimitiveKind.BYTE;
        } else if (v instanceof Short s) {
            ConstantPool.pushInt(mv, s);
            ctx.stackKind = PrimitiveKind.SHORT;
        } else if (v instanceof Integer i) {
            ConstantPool.pushInt(mv, i);
            ctx.stackKind = PrimitiveKind.INT;
        } else if (v instanceof Long l) {
            mv.visitLdcInsn(l);
            ctx.stackKind = PrimitiveKind.LONG;
        } else if (v instanceof Float f) {
            mv.visitLdcInsn(f);
            ctx.stackKind = PrimitiveKind.FLOAT;
        } else if (v instanceof Double d) {
            mv.visitLdcInsn(d);
            ctx.stackKind = PrimitiveKind.DOUBLE;
        } else if (v instanceof Character ch) {
            ConstantPool.pushInt(mv, ch);
            ctx.stackKind = PrimitiveKind.CHAR;
        } else if (v instanceof String s) {
            ConstantPool.pushString(mv, s);
            ctx.stackKind = PrimitiveKind.NONE;
        } else {
            throw new IllegalStateException("Unsupported HIR constant : " + v.getClass());
        }
    }

    private void emitIntParseFallback(HirExpr.IntParseFallback f) {
        ctx.line(f.meta().line());
        ctx.mv.visitLdcInsn(f.text());
        ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        ctx.stackKind = PrimitiveKind.INT;
    }

    private void emitCoreCollectionRef(HirExpr.CoreCollectionRef r) {
        ctx.line(r.meta().line());
        ctx.mv.visitLdcInsn(r.collectionKind());
        ctx.mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "CoreCollections", "constructor",
                "(Ljava/lang/String;)L" + RUNTIME + "Callable;", false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitCoreExceptionRef(HirExpr.CoreExceptionRef r) {
        ctx.line(r.meta().line());
        ctx.mv.visitLdcInsn(Type.getType(org.babblelang.runtime.BabbleException.class));
        ctx.mv.visitMethodInsn(INVOKESTATIC, "org/babblelang/engine/impl/natives/java/JavaClass", "of",
                "(Ljava/lang/Class;)Lorg/babblelang/engine/impl/natives/java/JavaClass;", false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- function / class / interface values

    private void emitFunctionRef(HirExpr.FunctionRef ref) {
        MethodVisitor mv = ctx.mv;
        HirFunction fn = module.function(ref.functionId());
        if (fn.isCompiledClassMethod()) {
            ClassGen owner = classGens.get(fn.ownerClassId());
            mv.visitFieldInsn(GETSTATIC, className, compiledMethodField(fn, owner), "L" + COMPILED_METHOD + ";");
            ctx.stackKind = PrimitiveKind.NONE;
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
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
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
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitClassExpr(HirExpr.ClassExpr expr) {
        MethodVisitor mv = ctx.mv;
        HirClass cls = module.clazz(expr.classId());
        ClassGen gen = classGens.get(cls.id());
        classValueArguments(cls);
        emitTypeRefs(cls.implementedTypes());
        if (cls.isObject()) {
            if (gen != null) {
                emitInstanceFactory(gen);
            }
            mv.visitVarInsn(ALOAD, ctx.rtLocal);
            ctx.line(expr.meta().line());
            mv.visitMethodInsn(INVOKESTATIC, BABBLE_CLASS, "object",
                    "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;"
                            + (gen != null ? "L" + INSTANCE_FACTORY + ";" : "") + "L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        } else {
            if (gen != null) {
                if (gen.hasFinalHeaderFields()) {
                    mv.visitInsn(ACONST_NULL);
                } else {
                    emitInstanceFactory(gen);
                }
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                        "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;L" + INSTANCE_FACTORY + ";)L" + BABBLE_CLASS + ";", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newClass",
                        "(L" + CLASS_SHAPE + ";L" + NAMESPACE + ";[Ljava/lang/Object;L" + CLASS_BODY + ";[Ljava/lang/Object;)L" + BABBLE_CLASS + ";", false);
            }
            emitStaticBlock(cls.staticBlock());
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void classValueArguments(HirClass cls) {
        MethodVisitor mv = ctx.mv;
        ClassShape shape = cls.shape();
        String method = "cls$" + (classBodyCount++) + "$" + shape.name();
        pendingMethods.add(() -> generateClassBody(cls, method));

        mv.visitFieldInsn(GETSTATIC, className, constantPool.shapeField(shape), "L" + CLASS_SHAPE + ";");
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        capturedCells(cls.capturedVars());
        Type bodyType = Type.getMethodType(CLASS_BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("construct", "()L" + CLASS_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, method, CLASS_BODY_DESCRIPTOR, false), bodyType);
    }

    private void emitInstanceFactory(ClassGen gen) {
        String descriptor = "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;";
        ctx.mv.visitInvokeDynamicInsn("create", "()L" + INSTANCE_FACTORY + ";", METAFACTORY,
                Type.getMethodType(descriptor),
                new Handle(H_NEWINVOKESPECIAL, gen.name, "<init>", "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V", false),
                Type.getMethodType("(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)L" + gen.name + ";"));
    }

    private void emitInterfaceExpr(HirExpr.InterfaceExpr expr) {
        MethodVisitor mv = ctx.mv;
        HirInterface iface = module.iface(expr.interfaceId());
        mv.visitFieldInsn(GETSTATIC, className, constantPool.shapeField(iface.shape()), "L" + CLASS_SHAPE + ";");
        emitTypeRefs(iface.extendedTypes());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "newInterface", "(L" + CLASS_SHAPE + ";[Ljava/lang/Object;)L" + BABBLE_INTERFACE + ";", false);
        mv.visitInsn(DUP);
        int ifaceLocal = ctx.newLocal();
        mv.visitVarInsn(ASTORE, ifaceLocal);
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        mv.visitVarInsn(ALOAD, ifaceLocal);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "interfaceScope", "(L" + NAMESPACE + ";L" + BABBLE_INTERFACE + ";)L" + NAMESPACE + ";", false);
        int namespace = ctx.newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(iface.body()));
        popOn(mv, ctx.stackKind);
        emitStaticBlock(iface.staticBlock());
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitStaticBlock(HirStaticBlock block) {
        if (block == null) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        mv.visitInsn(DUP);
        int type = ctx.newLocal();
        mv.visitVarInsn(ASTORE, type);
        int outerTarget = ctx.staticTarget;
        ctx.staticTarget = type;
        if (block.namespaceScope()) {
            mv.visitVarInsn(ALOAD, ctx.nsLocal);
            mv.visitVarInsn(ALOAD, type);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "staticScope", "(L" + NAMESPACE + ";L" + RUNTIME + "BabbleType;)L" + NAMESPACE + ";", false);
            int namespace = ctx.newLocal();
            mv.visitVarInsn(ASTORE, namespace);
            withNamespace(namespace, () -> statements(block.body()));
        } else {
            inFrameScope(() -> {
                for (HirVar variable : declareVariables(block.declaredVars())) {
                    if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                        mv.visitVarInsn(ALOAD, type);
                        initVariable(variable, PrimitiveKind.NONE);
                    }
                }
                statements(block.body());
            });
        }
        ctx.staticTarget = outerTarget;
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitTypeRefs(List<HirTypeRef> types) {
        MethodVisitor mv = ctx.mv;
        ConstantPool.pushInt(mv, types.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < types.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitTypeRef(types.get(i));
            mv.visitInsn(AASTORE);
        }
    }

    void emitTypeRef(HirTypeRef typeRef) {
        MethodVisitor mv = ctx.mv;
        List<String> ids = typeRef.path();
        ctx.line(typeRef.line());
        loadVarOrName(typeRef.rootVar(), ids.get(0), typeRef.line());
        convertStack(ctx.stackKind, PrimitiveKind.NONE);
        StringBuilder text = new StringBuilder(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            mv.visitLdcInsn(ids.get(i));
            ConstantPool.pushString(mv, text.toString());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
            text.append('.').append(ids.get(i));
        }
        ConstantPool.pushString(mv, typeRef.text());
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "type", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- Java interop helpers

    void emitCheckedJavaReceiver(HirExpr recvExpr, String recvText, int lineAt,
                                 boolean unwrap, String internalTargetClass, String nullErrorFactory,
                                 Class<?> unwrapTargetClass) {
        MethodVisitor mv = ctx.mv;
        emitBoxed(recvExpr);
        if (unwrap) {
            if (unwrapTargetClass != null && unwrapTargetClass.isInterface()) {
                mv.visitLdcInsn(Type.getType(unwrapTargetClass));
                mv.visitVarInsn(ALOAD, ctx.rtLocal);
                ctx.line(lineAt);
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
        ctx.line(lineAt);
        ConstantPool.pushString(mv, recvText);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", nullErrorFactory, "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        if (!OBJECT.equals(internalTargetClass)) {
            mv.visitTypeInsn(CHECKCAST, internalTargetClass);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    int emitJavaArgToLocal(HirExpr argExpr, Class<?> paramType, int lineAt) {
        MethodVisitor mv = ctx.mv;
        if (paramType == int.class) {
            emitAs(argExpr, PrimitiveKind.INT);
            int slot = ctx.newLocal(PrimitiveKind.INT);
            mv.visitVarInsn(ISTORE, slot);
            return slot;
        }
        if (paramType == boolean.class) {
            emitAs(argExpr, PrimitiveKind.BOOL);
            int slot = ctx.newLocal(PrimitiveKind.BOOL);
            mv.visitVarInsn(ISTORE, slot);
            return slot;
        }
        if (paramType == double.class) {
            emitAs(argExpr, PrimitiveKind.DOUBLE);
            int slot = ctx.newLocal(PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, slot);
            return slot;
        }
        if (paramType == long.class) {
            emitExpr(argExpr);
            if (ctx.stackKind == PrimitiveKind.INT) {
                mv.visitInsn(I2L);
            } else {
                boxOn(mv, ctx.stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            }
            int slot = ctx.newLocal(PrimitiveKind.LONG);
            mv.visitVarInsn(LSTORE, slot);
            return slot;
        }
        if (paramType == float.class) {
            emitExpr(argExpr);
            if (ctx.stackKind == PrimitiveKind.INT) {
                mv.visitInsn(I2F);
            } else {
                boxOn(mv, ctx.stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
            }
            int slot = ctx.newLocal();
            mv.visitVarInsn(FSTORE, slot);
            return slot;
        }
        PrimitiveKind argKind = argExpr.meta().kind();
        emitBoxed(argExpr);
        if (!argKind.isPrimitive()) {
            mv.visitLdcInsn(Type.getType(paramType));
            mv.visitVarInsn(ALOAD, ctx.rtLocal);
            ctx.line(lineAt);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        }
        if (paramType != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(paramType));
        }
        int slot = ctx.newLocal();
        mv.visitVarInsn(ASTORE, slot);
        return slot;
    }

    void loadJavaLocal(Class<?> paramType, int slot) {
        MethodVisitor mv = ctx.mv;
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

    void emitJavaValueToBabbleStack(Class<?> type) {
        MethodVisitor mv = ctx.mv;
        if (type == void.class) {
            mv.visitInsn(ACONST_NULL);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        PrimitiveKind pk = javaPrimitiveKind(type);
        if (pk != PrimitiveKind.NONE) {
            ctx.stackKind = pk;
            return;
        }
        if (type == Object.class || type.isInterface()) {
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }
}
