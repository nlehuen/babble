package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * ANTLR-free JVM bytecode emitter consuming a {@link HirModule} (BIP014-I1, BIP014-D8).
 *
 * <p>Coordinates module-level code generation and expression dispatch, delegating entry points to
 * {@link MethodEntryEmitter}, frame variables and cells to {@link FrameVarEmitter}, and expression
 * families to {@link AssignAndMemberEmitter}, {@link ControlAndOpEmitter}, {@link MatchEmitter},
 * {@link CallEmitter}, {@link BridgeHelpers}, {@link ClassGen}, {@link InterfaceGen}, and {@link
 * ConstantPool} using per-method state in {@link MethodEmitContext} (BIP027-I2).
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
    final ClassWriter classWriter;
    final MethodEntryEmitter entries;
    final FrameVarEmitter frameVars;
    final BridgeHelpers bridges;
    final AssignAndMemberEmitter assigns;
    final ControlAndOpEmitter controlOps;
    final MatchEmitter matchEmitter;
    final CallEmitter calls;

    private final Map<Integer, InterfaceGen> interfaceGens = new LinkedHashMap<>();
    private final Map<String, InterfaceGen> interfaceGensByTypeId = new LinkedHashMap<>();
    private final Map<Integer, ClassGen> classGens = new LinkedHashMap<>();

    private int superHelperCount = 0;

    /**
     * Whether the bodies of functions and methods read names through caching {@code Bootstrap.name}
     * sites (from {@code -O1}) : their namespace is the same closure from call to call, unlike
     * {@code run}'s, a static block's or a namespace-scoped function's.
     */
    boolean cacheNameReadsInBodies;

    HirEmitter(HirModule module, ClassLoader runtimeLoader) {
        this.module = module;
        this.className = module.className();
        this.sourceName = module.sourceName();
        this.runtimeLoader = runtimeLoader;
        this.constantPool = new ConstantPool(className, sourceName);
        this.allInterfaceJvmNames = module.allInterfaceJvmNames();
        this.allClassJvmNames = module.allClassJvmNames();
        this.classWriter =
                new ScriptClassWriter(
                        runtimeLoader, className, generatedClassParents, generatedInterfaceParents);
        this.entries = new MethodEntryEmitter(this);
        this.frameVars = new FrameVarEmitter(this);
        this.bridges = new BridgeHelpers(this);
        this.assigns = new AssignAndMemberEmitter(this);
        this.controlOps = new ControlAndOpEmitter(this);
        this.matchEmitter = new MatchEmitter(this);
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

    /**
     * Generates the script class, JVM interfaces, and compiled Babble classes from {@link #module}.
     */
    Map<String, byte[]> generate() {
        prepareTypes();
        classWriter.visit(
                V21,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                className,
                null,
                OBJECT,
                null);
        classWriter.visitSource(sourceName, null);

        generateRun();
        entries.drainPendingMethods();
        for (InterfaceGen iface : interfaceGens.values()) {
            iface.emitBody();
        }
        for (ClassGen gen : classGens.values()) {
            gen.emitInterfaceBridges();
            gen.emitFastRecordConstructor();
        }
        entries.drainPendingMethods();
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
            for (org.babblelang.ir.hir.HirConstructor ctor : cls.constructors()) {
                for (HirParam p : ctor.params()) {
                    scanRecordReuse(p.defaultValue(), reused);
                }
                for (HirExpr e : ctor.preDelegateBody()) {
                    scanRecordReuse(e, reused);
                }
                for (org.babblelang.ir.hir.HirCallArg a : ctor.delegateArgs()) {
                    scanRecordReuse(a.value(), reused);
                }
                for (HirExpr e : ctor.body()) {
                    scanRecordReuse(e, reused);
                }
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
        org.babblelang.opt.HirRewriter.rewriteChildren(
                expr,
                child -> {
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
        ctx.mv.visitFieldInsn(
                GETSTATIC,
                className,
                constantPool.booleanArrayField(module.topLevelDeclaredFinals()),
                "[Z");
        ctx.mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "declare", "([Ljava/lang/String;[Z)V", false);

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
            case HirExpr.ThisFieldInit a -> 1 + countNodes(a.value());
            case HirExpr.ThisFieldAssign a -> 1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.JavaFieldAssign a -> 1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.DynamicFieldAssign a ->
                    1 + countNodes(a.receiver()) + countNodes(a.value());
            case HirExpr.ThisFieldGet g -> 1 + countNodes(g.receiver());
            case HirExpr.JavaFieldGet g -> 1 + countNodes(g.receiver());
            case HirExpr.ArrayCreate c -> 1 + countList(c.args());
            case HirExpr.ArrayOp op -> 1 + countNodes(op.receiver()) + countList(op.args());
            case HirExpr.ModuleExportGet g -> 1 + countNodes(g.receiver());
            case HirExpr.DynamicSelect s -> 1 + countNodes(s.receiver());
            case HirExpr.Unary u -> 1 + countNodes(u.operand());
            case HirExpr.Binary b -> 1 + countNodes(b.left()) + countNodes(b.right());
            case HirExpr.Range r -> 1 + countNodes(r.start()) + countNodes(r.end());
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
            case HirExpr.If i ->
                    1
                            + countNodes(i.cond())
                            + countNodes(i.thenBranch())
                            + countNodes(i.elseBranch());
            case HirExpr.Match m -> {
                int n = 1 + countNodes(m.scrutinee());
                for (HirExpr.MatchArm arm : m.arms()) {
                    n += 1 + countNodes(arm.guard()) + countNodes(arm.body());
                }
                if (m.elseArm() != null) {
                    n += countNodes(m.elseArm().body());
                }
                yield n;
            }
            case HirExpr.While w -> 1 + countNodes(w.cond()) + countNodes(w.body());
            case HirExpr.For f ->
                    2
                            + (f.countedRange() != null
                                    ? countNodes(f.countedRange().start())
                                            + countNodes(f.countedRange().end())
                                            + countNodes(f.countedRange().step())
                                    : countNodes(f.iterable()))
                            + countList(f.body());
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
            case HirExpr.DirectThisMethodCall c ->
                    1 + countNodes(c.receiver()) + countCallArgs(c.args());
            case HirExpr.InterfaceCall c -> 1 + countNodes(c.receiver()) + countList(c.args());
            case HirExpr.JavaConstructorCall c -> 1 + countNodes(c.callee()) + countList(c.args());
            case HirExpr.JavaMethodCall c -> 1 + countNodes(c.receiver()) + countList(c.args());
            case HirExpr.CoreMethodCall c -> 1 + countNodes(c.receiver()) + countList(c.args());
            case HirExpr.SafeCall c -> 1 + countNodes(c.receiver()) + countCallArgs(c.args());
            case HirExpr.DynamicCall c -> 1 + countNodes(c.callee()) + countCallArgs(c.args());
            case HirExpr.MakeOverloadSet m -> 1 + countList(m.operands());
            case HirExpr.OverloadSelect s -> 1 + countNodes(s.set());
            case HirExpr.OverloadDispatch d ->
                    1 + countNodes(d.callee()) + countList(d.args()) + d.branches().size() * 2;
            case HirExpr.RecordNew r -> 1 + countNodes(r.classRef()) + countList(r.args());
            case HirExpr.RecordReuse r -> 1 + countNodes(r.target()) + countList(r.args());
            case HirExpr.Move m -> 1 + countNodes(m.operand());
            case HirExpr.Freeze f -> 1 + countNodes(f.operand());
            case HirExpr.Narrow n -> countNodes(n.operand());
            case HirExpr.Convert cv -> 1 + countNodes(cv.operand());
            case HirExpr.Widen w -> 1 + countNodes(w.operand());
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

    private void beginMethod(
            int ownerId,
            int access,
            String name,
            String descriptor,
            int firstFreeLocal,
            int rt,
            int ns) {
        beginMethod(
                classWriter, ownerId, access, name, descriptor, firstFreeLocal, rt, ns, List.of());
    }

    void beginMethod(
            ClassWriter writer,
            int ownerId,
            int access,
            String name,
            String descriptor,
            int firstFreeLocal,
            int rt,
            int ns,
            List<HirVar> captured) {
        MethodVisitor mv = writer.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        ctx.reset(mv, ownerId, firstFreeLocal, rt, ns, captured);
    }

    void getStringArray(String[] values) {
        ctx.mv.visitFieldInsn(
                GETSTATIC, className, constantPool.stringArrayField(values), "[Ljava/lang/String;");
    }

    // ---------------------------------------------------------------- entry-point & frame
    // forwarders

    void ensureFunctionScheduled(HirFunction fn) {
        entries.ensureFunctionScheduled(fn);
    }

    String compiledMethodField(HirFunction fn, ClassGen gen) {
        return entries.compiledMethodField(fn, gen);
    }

    String cachedMethodOverloadField(HirExpr.MakeOverloadSet mos, ClassGen gen) {
        return entries.cachedMethodOverloadField(mos, gen);
    }

    List<HirVar> declareVariables(List<HirVar> variables) {
        return frameVars.declareVariables(variables);
    }

    void initVariable(HirVar variable, PrimitiveKind vk) {
        frameVars.initVariable(variable, vk);
    }

    void loadVariable(HirVar variable, int lineNumber) {
        frameVars.loadVariable(variable, lineNumber);
    }

    void storeVariable(HirVar variable, boolean definition, PrimitiveKind vk, int lineNumber) {
        frameVars.storeVariable(variable, definition, vk, lineNumber, true);
    }

    void storeVariable(
            HirVar variable,
            boolean definition,
            PrimitiveKind vk,
            int lineNumber,
            boolean valueNeeded) {
        frameVars.storeVariable(variable, definition, vk, lineNumber, valueNeeded);
    }

    void loadName(String name, boolean finalAndDefined, int lineNumber) {
        frameVars.loadName(name, finalAndDefined, lineNumber);
    }

    void loadVarOrName(HirVar var, String name, int lineNumber) {
        frameVars.loadVarOrName(var, name, lineNumber);
    }

    void inFrameScope(Runnable body) {
        frameVars.inFrameScope(body);
    }

    void withNamespace(int namespace, Runnable body) {
        frameVars.withNamespace(namespace, body);
    }

    // ---------------------------------------------------------------- statements & expression
    // dispatch

    void statements(List<HirExpr> statements) {
        if (statements.isEmpty()) {
            ctx.mv.visitInsn(ACONST_NULL);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        int last = statements.size() - 1;
        for (int i = 0; i < last; i++) {
            emitStmt(statements.get(i));
        }
        emitAndWiden(statements.get(last));
    }

    /** Emits every statement in {@code statements} in void position (no value left on stack). */
    void statementsVoid(List<HirExpr> statements) {
        for (HirExpr stmt : statements) {
            emitStmt(stmt);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    /**
     * Emits {@code expr} in statement (void) position, omitting unused DUP/POP and loop result
     * slots.
     */
    void emitStmt(HirExpr expr) {
        ctx.stackKind = PrimitiveKind.NONE;
        switch (expr) {
            case HirExpr.Const ignored -> {}
            case HirExpr.Def d -> assigns.emitDef(d, false);
            case HirExpr.VarAssign a -> assigns.emitVarAssign(a, false);
            case HirExpr.ThisFieldInit a -> assigns.emitThisFieldInit(a, false);
            case HirExpr.ThisFieldAssign a -> assigns.emitThisFieldAssign(a, false);
            case HirExpr.JavaFieldAssign a -> assigns.emitJavaFieldAssignExpr(a, false);
            case HirExpr.While w -> controlOps.loops.emitWhile(w, false);
            case HirExpr.For f -> controlOps.loops.emitFor(f, false);
            case HirExpr.Block b -> controlOps.emitBlock(b, false);
            case HirExpr.If i -> controlOps.emitIf(i, false);
            case HirExpr.Move m -> emitStmt(m.operand());
            case HirExpr.Freeze f -> emitStmt(f.operand());
            case HirExpr.Narrow n -> emitStmt(n.operand());
            case HirExpr.Convert cv
                    when cv.targetKind() == PrimitiveKind.NONE && cv.meta().checkedType() == null ->
                    emitStmt(cv.operand());
            case HirExpr.Widen w
                    when w.operand().meta().kind().isPrimitive()
                            && w.meta().checkedType() == null ->
                    emitStmt(w.operand());
            default -> {
                emitExpr(expr);
                popOn(ctx.mv, ctx.stackKind);
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitExpr(HirExpr expr) {
        ctx.stackKind = PrimitiveKind.NONE;
        switch (expr) {
            case HirExpr.Const c -> emitConst(c);
            case HirExpr.IntParseFallback f -> emitIntParseFallback(f);
            case HirExpr.CoreCollectionRef r -> emitCoreCollectionRef(r);
            case HirExpr.CoreExceptionRef r -> emitCoreExceptionRef(r);
            case HirExpr.VarRead v -> loadVariable(v.var(), v.meta().line());
            case HirExpr.NameRead n ->
                    loadName(n.name(), n.finalBinding() && n.definitelyDefined(), n.meta().line());
            case HirExpr.ThisRef t -> loadVarOrName(t.var(), "this", t.meta().line());
            case HirExpr.RecurseRef r -> loadVarOrName(r.var(), "$recurse", r.meta().line());
            case HirExpr.Def d -> assigns.emitDef(d);
            case HirExpr.VarAssign a -> assigns.emitVarAssign(a);
            case HirExpr.NameAssign a -> assigns.emitNameAssign(a);
            case HirExpr.ThisFieldInit a -> assigns.emitThisFieldInit(a);
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
            case HirExpr.Range r -> controlOps.emitRange(r);
            case HirExpr.Logic l -> controlOps.emitLogic(l);
            case HirExpr.Is i -> controlOps.emitIs(i);
            case HirExpr.As a -> controlOps.emitAsExpr(a);
            case HirExpr.NonNull n -> controlOps.emitNonNull(n);
            case HirExpr.SpreadAdapt s -> calls.emitSpreadAdapt(s);
            case HirExpr.JavaSamAdapt j -> controlOps.emitJavaSamAdapt(j);
            case HirExpr.Block b -> controlOps.emitBlock(b);
            case HirExpr.If i -> controlOps.emitIf(i);
            case HirExpr.Match m -> matchEmitter.emitMatch(m);
            case HirExpr.While w -> controlOps.loops.emitWhile(w);
            case HirExpr.For f -> controlOps.loops.emitFor(f);
            case HirExpr.Try t -> controlOps.tryCatch.emitTry(t);
            case HirExpr.Break b -> controlOps.loops.emitBreak(b);
            case HirExpr.Continue c -> controlOps.loops.emitContinue();
            case HirExpr.Return r -> controlOps.tryCatch.emitReturn(r);
            case HirExpr.Throw th -> controlOps.tryCatch.emitThrow(th);
            case HirExpr.CoreCollectionCall c -> calls.emitCoreCollectionCall(c);
            case HirExpr.SuperCall c -> calls.emitSuperCall(c);
            case HirExpr.SelfRecurseCall c -> calls.emitSelfRecurseCall(c);
            case HirExpr.SelfTailCall c -> calls.emitSelfTailCall(c);
            case HirExpr.DirectBabbleCall c -> calls.emitDirectBabbleCall(c);
            case HirExpr.DirectThisMethodCall c -> calls.emitDirectThisMethodCall(c);
            case HirExpr.InterfaceCall c -> calls.emitInterfaceCall(c);
            case HirExpr.JavaConstructorCall c -> calls.emitJavaConstructorCall(c);
            case HirExpr.JavaMethodCall c -> calls.emitJavaMethodCall(c);
            case HirExpr.CoreMethodCall c ->
                    throw new IllegalStateException(
                            "CoreMethodCall is only emitted for non-JVM targets : "
                                    + c.coreKind()
                                    + "."
                                    + c.memberName());
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
            case HirExpr.Narrow n -> emitAndWiden(n.operand());
            case HirExpr.Convert cv -> emitAs(cv.operand(), cv.targetKind());
            case HirExpr.Widen w -> {
                emitAndWiden(w.operand());
                widenStackRank(w.wideningRank());
            }
        }
    }

    void emitAndWiden(HirExpr expr) {
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
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "toDouble",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
            }
        } else {
            boxOn(mv, ctx.stackKind);
            ConstantPool.pushInt(mv, rank);
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "widenNumeric",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;",
                    false);
            ctx.stackKind = PrimitiveKind.NONE;
        }
    }

    // ---------------------------------------------------------------- literals

    private void emitConst(HirExpr.Const c) {
        ctx.stackKind = JvmOpEmitter.pushConstant(ctx.mv, c.value());
    }

    private void emitIntParseFallback(HirExpr.IntParseFallback f) {
        ctx.line(f.meta().line());
        ctx.mv.visitLdcInsn(f.text());
        ctx.mv.visitMethodInsn(
                INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        ctx.stackKind = PrimitiveKind.INT;
    }

    private void emitCoreCollectionRef(HirExpr.CoreCollectionRef r) {
        ctx.line(r.meta().line());
        ctx.mv.visitLdcInsn(r.collectionKind());
        ctx.mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "CoreCollections",
                "constructor",
                "(Ljava/lang/String;)L" + RUNTIME + "Callable;",
                false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitCoreExceptionRef(HirExpr.CoreExceptionRef r) {
        ctx.line(r.meta().line());
        ctx.mv.visitLdcInsn(Type.getType(org.babblelang.runtime.BabbleException.class));
        ctx.mv.visitMethodInsn(
                INVOKESTATIC,
                "org/babblelang/engine/impl/natives/java/JavaClass",
                "of",
                "(Ljava/lang/Class;)Lorg/babblelang/engine/impl/natives/java/JavaClass;",
                false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- function / class / interface
    // values

    private void emitFunctionRef(HirExpr.FunctionRef ref) {
        MethodVisitor mv = ctx.mv;
        HirFunction fn = module.function(ref.functionId());
        if (fn.isCompiledClassMethod()) {
            ClassGen owner = classGens.get(fn.ownerClassId());
            mv.visitFieldInsn(
                    GETSTATIC,
                    className,
                    compiledMethodField(fn, owner),
                    "L" + COMPILED_METHOD + ";");
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
        mv.visitInvokeDynamicInsn(
                "invoke",
                "()L" + FUNCTION_BODY + ";",
                METAFACTORY,
                bodyType,
                new Handle(
                        H_INVOKESTATIC,
                        className,
                        spread ? method + "$call" : method,
                        BODY_DESCRIPTOR,
                        false),
                bodyType);
        if (spread) {
            mv.visitLdcInsn(
                    new Handle(
                            H_INVOKESTATIC,
                            className,
                            method,
                            functionDirectDescriptor(fn),
                            false));
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        if (spec) {
            mv.visitLdcInsn(
                    new Handle(
                            H_INVOKESTATIC,
                            className,
                            method + "$spec",
                            functionSpecDescriptor(fn),
                            false));
        } else if (restIndex >= 0) {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        frameVars.capturedCells(fn.capturedVars());
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
        String createDesc =
                restIndex >= 0
                        ? "(L"
                                + FUNCTION_BODY
                                + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L"
                                + NAMESPACE
                                + ";[Ljava/lang/Object;[Ljava/lang/String;IIILjava/lang/String;)L"
                                + COMPILED_FUNCTION
                                + ";"
                        : (hasOptMask
                                ? "(L"
                                        + FUNCTION_BODY
                                        + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L"
                                        + NAMESPACE
                                        + ";[Ljava/lang/Object;[Ljava/lang/String;IILjava/lang/String;)L"
                                        + COMPILED_FUNCTION
                                        + ";"
                                : (spec
                                        ? "(L"
                                                + FUNCTION_BODY
                                                + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;L"
                                                + NAMESPACE
                                                + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L"
                                                + COMPILED_FUNCTION
                                                + ";"
                                        : "(L"
                                                + FUNCTION_BODY
                                                + ";Ljava/lang/invoke/MethodHandle;L"
                                                + NAMESPACE
                                                + ";[Ljava/lang/Object;[Ljava/lang/String;ILjava/lang/String;)L"
                                                + COMPILED_FUNCTION
                                                + ";"));
        mv.visitMethodInsn(INVOKESTATIC, COMPILED_FUNCTION, "create", createDesc, false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitClassExpr(HirExpr.ClassExpr expr) {
        MethodVisitor mv = ctx.mv;
        HirClass cls = module.clazz(expr.classId());
        ClassGen gen = classGens.get(cls.id());
        boolean multiCtor = !cls.isObject() && cls.constructors().size() > 1;
        if (multiCtor) {
            classValueMultiCtorArguments(cls);
        } else {
            classValueArguments(cls);
        }
        emitTypeRefs(cls.implementedTypes());
        if (cls.isObject()) {
            if (gen != null) {
                emitInstanceFactory(gen);
            }
            mv.visitVarInsn(ALOAD, ctx.rtLocal);
            ctx.line(expr.meta().line());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    BABBLE_CLASS,
                    "object",
                    "(L"
                            + CLASS_SHAPE
                            + ";L"
                            + NAMESPACE
                            + ";[Ljava/lang/Object;L"
                            + CLASS_BODY
                            + ";[Ljava/lang/Object;"
                            + (gen != null ? "L" + INSTANCE_FACTORY + ";" : "")
                            + "L"
                            + SCRIPT_RUNTIME
                            + ";)Ljava/lang/Object;",
                    false);
        } else if (multiCtor) {
            if (gen != null && !gen.usesDirectCtorInit() && !gen.hasFinalHeaderFields()) {
                emitInstanceFactory(gen);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    CLASSES,
                    "newClass",
                    "(L"
                            + CLASS_SHAPE
                            + ";L"
                            + NAMESPACE
                            + ";[Ljava/lang/Object;[L"
                            + CLASS_BODY
                            + ";[Ljava/lang/Object;L"
                            + INSTANCE_FACTORY
                            + ";)L"
                            + BABBLE_CLASS
                            + ";",
                    false);
            emitStaticBlock(cls.staticBlock());
        } else {
            if (gen != null) {
                if (gen.usesDirectCtorInit() || gen.hasFinalHeaderFields()) {
                    mv.visitInsn(ACONST_NULL);
                } else {
                    emitInstanceFactory(gen);
                }
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        CLASSES,
                        "newClass",
                        "(L"
                                + CLASS_SHAPE
                                + ";L"
                                + NAMESPACE
                                + ";[Ljava/lang/Object;L"
                                + CLASS_BODY
                                + ";[Ljava/lang/Object;L"
                                + INSTANCE_FACTORY
                                + ";)L"
                                + BABBLE_CLASS
                                + ";",
                        false);
            } else {
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        CLASSES,
                        "newClass",
                        "(L"
                                + CLASS_SHAPE
                                + ";L"
                                + NAMESPACE
                                + ";[Ljava/lang/Object;L"
                                + CLASS_BODY
                                + ";[Ljava/lang/Object;)L"
                                + BABBLE_CLASS
                                + ";",
                        false);
            }
            emitStaticBlock(cls.staticBlock());
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void classValueArguments(HirClass cls) {
        MethodVisitor mv = ctx.mv;
        ClassShape shape = cls.shape();
        String[] methods = entries.scheduleClassConstructors(cls);
        String method = methods[0];

        mv.visitFieldInsn(
                GETSTATIC, className, constantPool.shapeField(shape), "L" + CLASS_SHAPE + ";");
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        frameVars.capturedCells(cls.capturedVars());
        Type bodyType = Type.getMethodType(CLASS_BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn(
                "construct",
                "()L" + CLASS_BODY + ";",
                METAFACTORY,
                bodyType,
                new Handle(H_INVOKESTATIC, className, method, CLASS_BODY_DESCRIPTOR, false),
                bodyType);
    }

    private void classValueMultiCtorArguments(HirClass cls) {
        MethodVisitor mv = ctx.mv;
        ClassShape shape = cls.shape();
        String[] methods = entries.scheduleClassConstructors(cls);

        mv.visitFieldInsn(
                GETSTATIC, className, constantPool.shapeField(shape), "L" + CLASS_SHAPE + ";");
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        frameVars.capturedCells(cls.capturedVars());
        ConstantPool.pushInt(mv, methods.length);
        mv.visitTypeInsn(ANEWARRAY, CLASS_BODY);
        Type bodyType = Type.getMethodType(CLASS_BODY_DESCRIPTOR);
        for (int k = 0; k < methods.length; k++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, k);
            mv.visitInvokeDynamicInsn(
                    "construct",
                    "()L" + CLASS_BODY + ";",
                    METAFACTORY,
                    bodyType,
                    new Handle(H_INVOKESTATIC, className, methods[k], CLASS_BODY_DESCRIPTOR, false),
                    bodyType);
            mv.visitInsn(AASTORE);
        }
    }

    private void emitInstanceFactory(ClassGen gen) {
        String descriptor = "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;";
        ctx.mv.visitInvokeDynamicInsn(
                "create",
                "()L" + INSTANCE_FACTORY + ";",
                METAFACTORY,
                Type.getMethodType(descriptor),
                new Handle(
                        H_NEWINVOKESPECIAL,
                        gen.name,
                        "<init>",
                        "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)V",
                        false),
                Type.getMethodType(
                        "(L" + BABBLE_CLASS + ";L" + SCRIPT_RUNTIME + ";)L" + gen.name + ";"));
    }

    private void emitInterfaceExpr(HirExpr.InterfaceExpr expr) {
        MethodVisitor mv = ctx.mv;
        HirInterface iface = module.iface(expr.interfaceId());
        mv.visitFieldInsn(
                GETSTATIC,
                className,
                constantPool.shapeField(iface.shape()),
                "L" + CLASS_SHAPE + ";");
        emitTypeRefs(iface.extendedTypes());
        mv.visitMethodInsn(
                INVOKESTATIC,
                CLASSES,
                "newInterface",
                "(L" + CLASS_SHAPE + ";[Ljava/lang/Object;)L" + BABBLE_INTERFACE + ";",
                false);
        mv.visitInsn(DUP);
        int ifaceLocal = ctx.newLocal();
        mv.visitVarInsn(ASTORE, ifaceLocal);
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        mv.visitVarInsn(ALOAD, ifaceLocal);
        mv.visitMethodInsn(
                INVOKESTATIC,
                CLASSES,
                "interfaceScope",
                "(L" + NAMESPACE + ";L" + BABBLE_INTERFACE + ";)L" + NAMESPACE + ";",
                false);
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    CLASSES,
                    "staticScope",
                    "(L" + NAMESPACE + ";L" + RUNTIME + "BabbleType;)L" + NAMESPACE + ";",
                    false);
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Members",
                    "select",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                    false);
            text.append('.').append(ids.get(i));
        }
        ConstantPool.pushString(mv, typeRef.text());
        mv.visitMethodInsn(
                INVOKESTATIC,
                CLASSES,
                "type",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                false);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- Java interop helpers

    void emitCheckedJavaReceiver(
            HirExpr recvExpr,
            String recvText,
            int lineAt,
            boolean unwrap,
            String internalTargetClass,
            String nullErrorFactory,
            Class<?> unwrapTargetClass) {
        MethodVisitor mv = ctx.mv;
        emitBoxed(recvExpr);
        if (unwrap) {
            if (unwrapTargetClass != null && unwrapTargetClass.isInterface()) {
                mv.visitLdcInsn(Type.getType(unwrapTargetClass));
                mv.visitVarInsn(ALOAD, ctx.rtLocal);
                ctx.line(lineAt);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "toJava",
                        "(Ljava/lang/Object;Ljava/lang/Class;L"
                                + SCRIPT_RUNTIME
                                + ";)Ljava/lang/Object;",
                        false);
            } else {
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
            }
        }
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        ctx.line(lineAt);
        ConstantPool.pushString(mv, recvText);
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Members",
                nullErrorFactory,
                "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;",
                false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        if (!OBJECT.equals(internalTargetClass)) {
            mv.visitTypeInsn(CHECKCAST, internalTargetClass);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    int emitJavaArgToLocal(HirExpr argExpr, Class<?> paramType, int lineAt) {
        MethodVisitor mv = ctx.mv;
        if (paramType == int.class
                || paramType == byte.class
                || paramType == short.class
                || paramType == char.class) {
            PrimitiveKind pk = javaPrimitiveKind(paramType);
            emitAs(argExpr, pk);
            int slot = ctx.newLocal(pk);
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
            emitAs(argExpr, PrimitiveKind.LONG);
            int slot = ctx.newLocal(PrimitiveKind.LONG);
            mv.visitVarInsn(LSTORE, slot);
            return slot;
        }
        if (paramType == float.class) {
            emitAs(argExpr, PrimitiveKind.FLOAT);
            int slot = ctx.newLocal(PrimitiveKind.FLOAT);
            mv.visitVarInsn(FSTORE, slot);
            return slot;
        }
        PrimitiveKind argKind = argExpr.meta().kind();
        emitBoxed(argExpr);
        if (!argKind.isPrimitive()) {
            mv.visitLdcInsn(Type.getType(paramType));
            mv.visitVarInsn(ALOAD, ctx.rtLocal);
            ctx.line(lineAt);
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "toJava",
                    "(Ljava/lang/Object;Ljava/lang/Class;L"
                            + SCRIPT_RUNTIME
                            + ";)Ljava/lang/Object;",
                    false);
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
        if (paramType == int.class
                || paramType == boolean.class
                || paramType == byte.class
                || paramType == short.class
                || paramType == char.class) {
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "fromJava",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }
}
