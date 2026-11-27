package org.babblelang.compiler;

import static org.babblelang.compiler.HirEmitter.ARGS;
import static org.babblelang.compiler.HirEmitter.BINDER_LOCAL;
import static org.babblelang.compiler.HirEmitter.FIRST_PARAMETER;
import static org.babblelang.compiler.HirEmitter.FN_NS;
import static org.babblelang.compiler.HirEmitter.FN_RT;
import static org.babblelang.compiler.HirEmitter.INSTANCE;
import static org.babblelang.compiler.HirEmitter.NAMES;
import static org.babblelang.compiler.HirEmitter.SELF;
import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Emits JVM entry points for Babble functions, compiled class methods, specialized primitive entry
 * points, call adapters, and class constructor bodies on behalf of {@link HirEmitter}.
 */
final class MethodEntryEmitter {
    private final HirEmitter emitter;
    private final Queue<Runnable> pendingMethods = new ArrayDeque<>();
    private final Set<Integer> scheduledFunctions = new HashSet<>();
    private final Map<Integer, String> compiledMethodFields = new HashMap<>();
    private final Map<String, String> cachedMethodOverloads = new HashMap<>();
    private final Map<Integer, String[]> scheduledClassConstructors = new HashMap<>();
    private int classBodyCount = 0;

    MethodEntryEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    void drainPendingMethods() {
        while (!pendingMethods.isEmpty()) {
            pendingMethods.poll().run();
        }
    }

    String[] scheduleClassConstructors(HirClass cls) {
        String[] existing = scheduledClassConstructors.get(cls.id());
        if (existing != null) {
            return existing;
        }
        ClassGen gen = emitter.classGen(cls.id());
        List<org.babblelang.ir.hir.HirConstructor> ctors = cls.constructors();
        if (ctors.isEmpty()) {
            String method = "cls$" + (classBodyCount++) + "$" + cls.shape().name();
            pendingMethods.add(() -> generateClassBody(cls, null, method));
            String[] single = new String[] {method};
            scheduledClassConstructors.put(cls.id(), single);
            return single;
        }
        int baseIdx = classBodyCount++;
        String[] methods = new String[ctors.size()];
        for (int k = 0; k < ctors.size(); k++) {
            methods[k] =
                    ctors.size() == 1
                            ? "cls$" + baseIdx + "$" + cls.shape().name()
                            : "cls$" + baseIdx + "$" + cls.shape().name() + "$" + k;
        }
        scheduledClassConstructors.put(cls.id(), methods);
        for (int k = 0; k < ctors.size(); k++) {
            org.babblelang.ir.hir.HirConstructor ctor = ctors.get(k);
            String method = methods[k];
            if (ctor.isDelegating()) {
                String targetMethod = methods[ctor.delegateIndex()];
                pendingMethods.add(
                        () -> generateDelegatingWrapper(cls, gen, ctor, method, targetMethod));
            } else if (gen != null && gen.usesDirectCtorInit()) {
                pendingMethods.add(() -> generateRootJvmInit(cls, gen, ctor));
                pendingMethods.add(() -> generateDirectRootWrapper(cls, gen, ctor, method));
            } else {
                pendingMethods.add(() -> generateClassBody(cls, ctor, method));
            }
        }
        return methods;
    }

    void emitBabbleConstructorCall(HirExpr.DynamicCall c) {
        MethodEmitContext mc = emitter.ctx;
        MethodVisitor mv = mc.mv;
        String site = emitter.constantPool.callSiteField(c.callSite());
        emitter.emitBoxed(c.callee());
        mv.visitTypeInsn(CHECKCAST, BABBLE_CLASS);
        ConstantPool.pushInt(mv, c.constructorIndex());
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        if (c.binding() != null) {
            emitter.calls.emitBoundArgumentsArray(CallEmitter.callArgExprs(c.args()), c.binding());
            mv.visitInsn(ACONST_NULL);
        } else {
            emitter.calls.emitArgumentsArray(c.args());
            String[] names = callArgNames(c.args());
            if (names != null) {
                emitter.getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mc.line(c.meta().line());
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                BABBLE_CLASS,
                "constructWithIndex",
                "(IL"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;",
                false);
        mc.stackKind = PrimitiveKind.NONE;
        emitter.calls.finishCallCheck(c.meta());
    }

    void ensureFunctionScheduled(HirFunction fn) {
        if (fn.isCompiledClassMethod()) {
            ClassGen gen = emitter.classGen(fn.ownerClassId());
            if (gen != null) {
                compiledMethodField(fn, gen);
            }
            return;
        }
        if (scheduledFunctions.add(fn.id())) {
            String paramsField = emitter.constantPool.stringArrayField(fn.paramNames());
            pendingMethods.add(() -> generateFunction(fn, paramsField));
        }
    }

    private void generateFunction(HirFunction fn, String paramsField) {
        String method = fn.jvmMethodName();
        if (fn.isSpread()) {
            int count = fn.params().size();
            String direct = functionDirectDescriptor(fn);
            callAdapter(
                    emitter.classWriter,
                    ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    method + "$call",
                    BODY_DESCRIPTOR,
                    paramsField,
                    count,
                    fn.keywordOnlyStart(),
                    fn.restIndex(),
                    m -> m.visitMethodInsn(INVOKESTATIC, emitter.className, method, direct, false));
            emitter.beginMethod(
                    emitter.classWriter,
                    fn.id(),
                    ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    method,
                    direct,
                    FIRST_PARAMETER + count + 1,
                    FN_RT,
                    FIRST_PARAMETER + count,
                    fn.capturedVars());
            emitter.ctx.parameterBase = FIRST_PARAMETER;
        } else {
            emitter.beginMethod(
                    emitter.classWriter,
                    fn.id(),
                    ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    method,
                    BODY_DESCRIPTOR,
                    FN_NS + 1,
                    FN_RT,
                    FN_NS,
                    fn.capturedVars());
        }
        emitter.frameVars.loadCapturedCells(COMPILED_FUNCTION);
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
        emitter.beginMethod(
                emitter.classWriter,
                fn.id(),
                ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                method + "$spec",
                functionSpecDescriptor(fn),
                nsSlot + 1,
                FN_RT,
                nsSlot,
                fn.capturedVars());
        emitter.ctx.methodReturnKind = fn.returnKind();
        emitter.frameVars.loadCapturedCells(COMPILED_FUNCTION);
        functionBodySpec(fn, COMPILED_FUNCTION, paramSlots, maskSlot);
    }

    private void callAdapter(
            ClassWriter writer,
            int access,
            String name,
            String descriptor,
            String paramsField,
            int count,
            int kwStart,
            int restIndex,
            java.util.function.Consumer<MethodVisitor> callDirect) {
        MethodVisitor m = writer.visitMethod(access, name, descriptor, null, null);
        m.visitCode();
        int binder = FN_NS;
        m.visitFieldInsn(GETSTATIC, emitter.className, paramsField, "[Ljava/lang/String;");
        if (restIndex >= 0) {
            ConstantPool.pushInt(m, kwStart);
            ConstantPool.pushInt(m, restIndex);
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    ARGUMENTS,
                    "binder",
                    "([Ljava/lang/String;II[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";",
                    false);
        } else if (kwStart < count) {
            ConstantPool.pushInt(m, kwStart);
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    ARGUMENTS,
                    "binder",
                    "([Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";",
                    false);
        } else {
            m.visitVarInsn(ALOAD, ARGS);
            m.visitVarInsn(ALOAD, NAMES);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    ARGUMENTS,
                    "binder",
                    "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";",
                    false);
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
        String paramsField = emitter.constantPool.stringArrayField(fn.paramNames());
        int required = fn.requiredParams();
        if (scheduledFunctions.add(fn.id())) {
            pendingMethods.add(() -> generateMethod(fn, gen, paramsField));
        }
        String sam =
                "(Ljava/lang/Object;L"
                        + COMPILED_METHOD
                        + ";L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;";
        String instantiated =
                "(L"
                        + gen.name
                        + ";L"
                        + COMPILED_METHOD
                        + ";L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;";
        boolean spread = fn.isSpread();
        boolean spec = fn.hasSpec();
        boolean hasOptMask = spec && fn.hasOptionalMask();
        int optBits = hasOptMask ? fn.optionalParamBits() : 0;
        int restIndex = fn.restIndex();
        String method = fn.jvmMethodName();
        String field =
                emitter.constantPool.add(
                        "METHOD",
                        "L" + COMPILED_METHOD + ";",
                        32,
                        m -> {
                            m.visitTypeInsn(NEW, COMPILED_METHOD);
                            m.visitInsn(DUP);
                            m.visitInvokeDynamicInsn(
                                    "invoke",
                                    "()L" + METHOD_BODY + ";",
                                    METAFACTORY,
                                    Type.getMethodType(sam),
                                    new Handle(
                                            H_INVOKEVIRTUAL,
                                            gen.name,
                                            spread ? method + "$call" : method,
                                            METHOD_DESCRIPTOR,
                                            false),
                                    Type.getMethodType(instantiated));
                            if (spread) {
                                m.visitLdcInsn(
                                        new Handle(
                                                H_INVOKEVIRTUAL,
                                                gen.name,
                                                method,
                                                methodDirectDescriptor(fn),
                                                false));
                            } else {
                                m.visitInsn(ACONST_NULL);
                            }
                            if (spec) {
                                m.visitLdcInsn(
                                        new Handle(
                                                H_INVOKEVIRTUAL,
                                                gen.name,
                                                method + "$spec",
                                                methodSpecDescriptor(fn),
                                                false));
                            } else if (restIndex >= 0) {
                                m.visitInsn(ACONST_NULL);
                            }
                            m.visitFieldInsn(
                                    GETSTATIC,
                                    emitter.className,
                                    paramsField,
                                    "[Ljava/lang/String;");
                            ConstantPool.pushInt(m, required);
                            if (restIndex >= 0) {
                                ConstantPool.pushInt(m, optBits);
                                ConstantPool.pushInt(m, restIndex);
                            } else if (hasOptMask) {
                                ConstantPool.pushInt(m, optBits);
                            }
                            m.visitLdcInsn(member);
                            String initDesc =
                                    restIndex >= 0
                                            ? "(L"
                                                    + METHOD_BODY
                                                    + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;IIILjava/lang/String;)V"
                                            : (hasOptMask
                                                    ? "(L"
                                                            + METHOD_BODY
                                                            + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;IILjava/lang/String;)V"
                                                    : (spec
                                                            ? "(L"
                                                                    + METHOD_BODY
                                                                    + ";Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V"
                                                            : "(L"
                                                                    + METHOD_BODY
                                                                    + ";Ljava/lang/invoke/MethodHandle;[Ljava/lang/String;ILjava/lang/String;)V"));
                            m.visitMethodInsn(
                                    INVOKESPECIAL, COMPILED_METHOD, "<init>", initDesc, false);
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
            methodFields.add(compiledMethodField(emitter.module.function(ref.functionId()), gen));
        }
        String k = key.toString();
        String existing = cachedMethodOverloads.get(k);
        if (existing != null) {
            return existing;
        }
        String field =
                emitter.constantPool.add(
                        "OVERLOAD",
                        "L" + OVERLOAD_SET + ";",
                        40 + mos.metas().size() * 24,
                        m -> {
                            ConstantPool.pushInt(m, methodFields.size());
                            m.visitTypeInsn(ANEWARRAY, OBJECT);
                            for (int i = 0; i < methodFields.size(); i++) {
                                m.visitInsn(DUP);
                                ConstantPool.pushInt(m, i);
                                m.visitFieldInsn(
                                        GETSTATIC,
                                        emitter.className,
                                        methodFields.get(i),
                                        "L" + COMPILED_METHOD + ";");
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
            callAdapter(
                    gen.writer,
                    ACC_PUBLIC | ACC_SYNTHETIC,
                    method + "$call",
                    METHOD_DESCRIPTOR,
                    paramsField,
                    count,
                    fn.keywordOnlyStart(),
                    fn.restIndex(),
                    m -> m.visitMethodInsn(INVOKEVIRTUAL, gen.name, method, direct, false));
            emitter.beginMethod(
                    gen.writer,
                    fn.id(),
                    ACC_PUBLIC | ACC_SYNTHETIC,
                    method,
                    direct,
                    FIRST_PARAMETER + count + 1,
                    FN_RT,
                    FIRST_PARAMETER + count,
                    fn.capturedVars());
            emitter.ctx.parameterBase = FIRST_PARAMETER;
        } else {
            emitter.beginMethod(
                    gen.writer,
                    fn.id(),
                    ACC_PUBLIC | ACC_SYNTHETIC,
                    method,
                    METHOD_DESCRIPTOR,
                    FN_NS + 1,
                    FN_RT,
                    FN_NS,
                    fn.capturedVars());
        }
        emitter.ctx.selfLocal = 1;
        emitter.ctx.thizLocal = 0;
        emitter.ctx.methodClass = gen;
        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);
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
        emitter.beginMethod(
                gen.writer,
                fn.id(),
                ACC_PUBLIC | ACC_SYNTHETIC,
                method + "$spec",
                methodSpecDescriptor(fn),
                nsSlot + 1,
                FN_RT,
                nsSlot,
                fn.capturedVars());
        emitter.ctx.methodReturnKind = fn.returnKind();
        emitter.ctx.selfLocal = 1;
        emitter.ctx.thizLocal = 0;
        emitter.ctx.methodClass = gen;
        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);
        functionBodySpec(fn, COMPILED_METHOD, paramSlots, maskSlot);
    }

    private void loadClosure() {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        if (ctx.methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(
                    GETFIELD, ctx.methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
            mv.visitMethodInsn(
                    INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        } else {
            mv.visitVarInsn(ALOAD, SELF);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL, COMPILED_FUNCTION, "closure", "()L" + NAMESPACE + ";", false);
        }
    }

    private void loadRecurseTarget(String selfType) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        mv.visitVarInsn(ALOAD, ctx.selfLocal);
        mv.visitVarInsn(ALOAD, ctx.thizLocal);
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                selfType,
                "recurseTarget",
                "(Ljava/lang/Object;)L" + CALLABLE + ";",
                false);
    }

    private void initFrameLocals(HirFunction fn, String selfType) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        if (fn.namespaceScope()) {
            loadClosure();
            loadRecurseTarget(selfType);
            mv.visitVarInsn(ALOAD, ctx.thizLocal);
            emitter.getStringArray(fn.declaredNames());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    FRAMES,
                    "enter",
                    "(L"
                            + NAMESPACE
                            + ";L"
                            + CALLABLE
                            + ";Ljava/lang/Object;[Ljava/lang/String;)L"
                            + NAMESPACE
                            + ";",
                    false);
            mv.visitVarInsn(ASTORE, ctx.frameNs);
        } else {
            loadClosure();
            mv.visitVarInsn(ASTORE, ctx.frameNs);
            if (emitter.cacheNameReadsInBodies) {
                HirSlotAllocator.allocate(fn, ctx);
            }
            for (HirVar variable : emitter.declareVariables(fn.declaredVars())) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                    mv.visitVarInsn(ALOAD, ctx.thizLocal);
                    emitter.initVariable(variable, PrimitiveKind.NONE);
                } else if (variable.kind() == Resolution.Variable.Kind.RECURSE && variable.used()) {
                    loadRecurseTarget(selfType);
                    emitter.initVariable(variable, PrimitiveKind.NONE);
                }
            }
        }
    }

    private void functionBody(HirFunction fn, String paramsField, String selfType) {
        MethodEmitContext ctx = emitter.ctx;
        ctx.cacheNameReads = emitter.cacheNameReadsInBodies && !fn.namespaceScope();
        ctx.beginTailLoop(null, -1);
        initFrameLocals(fn, selfType);
        bindParameters(paramsField, fn.params(), fn.keywordOnlyStart(), fn.restIndex(), null);
        emitter.statements(fn.body());
        emitter.widenStackRank(fn.bodyWideningRank());
        emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
        ctx.mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void functionBodySpec(HirFunction fn, String selfType, int[] paramSlots, int maskSlot) {
        MethodEmitContext ctx = emitter.ctx;
        ctx.cacheNameReads = emitter.cacheNameReadsInBodies && !fn.namespaceScope();
        MethodVisitor mv = ctx.mv;
        ctx.beginTailLoop(paramSlots, maskSlot);
        initFrameLocals(fn, selfType);
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
                emitter.emitAs(param.defaultValue(), pk);
                if (pk == PrimitiveKind.NONE && param.wideningRank() >= 0) {
                    emitWidenBoxed(mv, param.wideningRank());
                }
                storeKindOn(mv, pk, paramSlots[i]);
                mv.visitLabel(haveValue);
                optBit++;
            }
            if (param.samTarget() != null && pk == PrimitiveKind.NONE) {
                loadKindOn(mv, pk, paramSlots[i]);
                emitter.controlOps.emitToJavaSamOnStack(param.samTarget(), param.line());
                storeKindOn(mv, pk, paramSlots[i]);
            }
            HirVar variable = param.var();
            if (variable != null) {
                loadKindOn(mv, pk, paramSlots[i]);
                if (variable.cell()) {
                    emitter.storeVariable(variable, true, pk, param.line());
                    popOn(mv, pk);
                } else {
                    emitter.initVariable(variable, pk);
                }
            } else {
                mv.visitVarInsn(ALOAD, ctx.frameNs);
                mv.visitLdcInsn(param.name());
                loadKindOn(mv, pk, paramSlots[i]);
                boxOn(mv, pk);
                mv.visitInsn(param.mutable() ? ICONST_0 : ICONST_1);
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        NAMESPACE,
                        "defineParameter",
                        "(Ljava/lang/String;Ljava/lang/Object;Z)V",
                        false);
            }
        }
        emitter.statements(fn.body());
        emitter.widenStackRank(fn.bodyWideningRank());
        emitter.convertStack(ctx.stackKind, ctx.methodReturnKind);
        mv.visitInsn(returnInsn(ctx.methodReturnKind));
        ctx.finish();
    }

    private void generateDirectRootWrapper(
            HirClass cls, ClassGen gen, org.babblelang.ir.hir.HirConstructor ctor, String method) {
        MethodEmitContext ctx = emitter.ctx;
        emitter.beginMethod(
                emitter.classWriter,
                ctor.ownerId(),
                ACC_STATIC | ACC_SYNTHETIC,
                method,
                CLASS_BODY_DESCRIPTOR,
                FN_NS + 1,
                FN_RT,
                FN_NS,
                cls.capturedVars());
        MethodVisitor mv = ctx.mv;
        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);
        mv.visitVarInsn(ALOAD, SELF);
        mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, FN_NS);

        List<HirVar> paramVars = new ArrayList<>();
        for (HirParam p : ctor.params()) {
            if (p.var() != null) {
                paramVars.add(p.var());
            }
        }
        if (!paramVars.isEmpty()) {
            emitter.declareVariables(paramVars);
        }

        int[] boundSlots = new int[ctor.params().size()];
        bindParameters(
                emitter.constantPool.stringArrayField(ctor.paramNames()),
                ctor.params(),
                ctor.keywordOnlyStart(),
                ctor.restIndex(),
                null,
                null,
                boundSlots);

        mv.visitTypeInsn(NEW, gen.name);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, SELF);
        mv.visitVarInsn(ALOAD, FN_RT);
        for (int i = 0; i < ctor.params().size(); i++) {
            loadKindOn(mv, ctor.params().get(i).kind(), boundSlots[i]);
        }
        for (int t = 0; t < gen.rootInitTagCount(ctor.index()); t++) {
            mv.visitInsn(ICONST_0);
        }
        mv.visitMethodInsn(
                INVOKESPECIAL, gen.name, "<init>", gen.rootInitDescriptor(ctor.index()), false);
        mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void generateRootJvmInit(
            HirClass cls, ClassGen gen, org.babblelang.ir.hir.HirConstructor ctor) {
        int[] paramSlots = new int[ctor.params().size()];
        int nextSlot = 3;
        for (int i = 0; i < ctor.params().size(); i++) {
            paramSlots[i] = nextSlot;
            nextSlot += ctor.params().get(i).kind().slots();
        }
        nextSlot += gen.rootInitTagCount(ctor.index());
        int nsSlot = nextSlot;

        emitter.beginMethod(
                gen.writer,
                ctor.ownerId(),
                ACC_PUBLIC,
                "<init>",
                gen.rootInitDescriptor(ctor.index()),
                nsSlot + 1,
                2,
                nsSlot,
                cls.capturedVars());
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        ctx.selfLocal = 1;
        ctx.thizLocal = 0;
        ctx.typedThisLocal = 0;
        ctx.constructorClass = gen;
        ctx.methodClass = null;

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, gen.name, "$babble$class", "L" + BABBLE_CLASS + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(PUTFIELD, gen.name, "$babble$rt", "L" + SCRIPT_RUNTIME + ";");
        for (int i = 0; i < gen.shape.memberCount(); i++) {
            if (!gen.isFinalField(i)
                    && !gen.memberKinds[i].isPrimitive()
                    && !gen.isStaticMethodMember(i)) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(
                        GETSTATIC,
                        RUNTIME + "Undefined",
                        "UNDEFINED",
                        "L" + RUNTIME + "Undefined;");
                mv.visitFieldInsn(PUTFIELD, gen.name, gen.shape.member(i), "Ljava/lang/Object;");
            }
        }

        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, nsSlot);

        List<HirVar> allDeclared = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (HirVar v : cls.declaredVars()) {
            if (seenIds.add(v.id())) {
                allDeclared.add(v);
            }
        }
        for (HirVar v : ctor.declaredVars()) {
            if (seenIds.add(v.id())) {
                allDeclared.add(v);
            }
        }
        for (HirVar variable : emitter.declareVariables(allDeclared)) {
            if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                mv.visitVarInsn(ALOAD, 0);
                emitter.initVariable(variable, PrimitiveKind.NONE);
            }
        }

        for (int i = 0; i < ctor.params().size(); i++) {
            HirParam param = ctor.params().get(i);
            int member = param.memberIndex();
            if (member >= 0) {
                PrimitiveKind mk = gen.memberKinds[member];
                mv.visitVarInsn(ALOAD, 0);
                loadKindOn(mv, param.kind(), paramSlots[i]);
                ctx.stackKind = param.kind();
                emitter.convertStack(param.kind(), mk);
                mv.visitFieldInsn(PUTFIELD, gen.name, gen.shape.member(member), jvmDescriptor(mk));
                ctx.stackKind = PrimitiveKind.NONE;
            } else if (param.var() != null) {
                HirVar variable = param.var();
                PrimitiveKind vk = variable.primitiveKind();
                loadKindOn(mv, param.kind(), paramSlots[i]);
                ctx.stackKind = param.kind();
                emitter.convertStack(param.kind(), vk);
                if (variable.cell()) {
                    emitter.storeVariable(variable, true, vk, param.line());
                    popOn(mv, vk);
                } else {
                    emitter.initVariable(variable, vk);
                    if (gen.hasHiddenField(variable)) {
                        emitter.frameVars.publishHidden(variable);
                    }
                }
                ctx.stackKind = PrimitiveKind.NONE;
            } else {
                mv.visitVarInsn(ALOAD, ctx.frameNs);
                mv.visitLdcInsn(param.name());
                loadKindOn(mv, param.kind(), paramSlots[i]);
                boxOn(mv, param.kind());
                mv.visitInsn(param.mutable() ? ICONST_0 : ICONST_1);
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        NAMESPACE,
                        "defineParameter",
                        "(Ljava/lang/String;Ljava/lang/Object;Z)V",
                        false);
            }
        }

        if (!cls.body().isEmpty()) {
            emitter.statements(cls.body());
            popOn(mv, ctx.stackKind);
        }
        if (!ctor.body().isEmpty()) {
            emitter.statements(ctor.body());
            popOn(mv, ctx.stackKind);
        }
        mv.visitInsn(RETURN);
        ctx.finish();
    }

    private void generateDelegatingWrapper(
            HirClass cls,
            ClassGen gen,
            org.babblelang.ir.hir.HirConstructor ctor,
            String method,
            String targetMethod) {
        MethodEmitContext ctx = emitter.ctx;
        emitter.beginMethod(
                emitter.classWriter,
                ctor.ownerId(),
                ACC_STATIC | ACC_SYNTHETIC,
                method,
                CLASS_BODY_DESCRIPTOR,
                FN_NS + 1,
                FN_RT,
                FN_NS,
                cls.capturedVars());
        MethodVisitor mv = ctx.mv;
        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);
        mv.visitVarInsn(ALOAD, SELF);
        mv.visitMethodInsn(INVOKEVIRTUAL, BABBLE_CLASS, "closure", "()L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, FN_NS);

        emitter.declareVariables(ctor.declaredVars());
        bindParameters(
                emitter.constantPool.stringArrayField(ctor.paramNames()),
                ctor.params(),
                ctor.keywordOnlyStart(),
                ctor.restIndex(),
                null,
                null,
                null);

        if (!ctor.preDelegateBody().isEmpty()) {
            emitter.statements(ctor.preDelegateBody());
            popOn(mv, ctx.stackKind);
        }

        mv.visitVarInsn(ALOAD, SELF);
        mv.visitVarInsn(ALOAD, INSTANCE);
        mv.visitVarInsn(ALOAD, FN_RT);
        if (ctor.delegateBinding() != null) {
            emitter.calls.emitBoundArgumentsArray(
                    CallEmitter.callArgExprs(ctor.delegateArgs()), ctor.delegateBinding());
            mv.visitInsn(ACONST_NULL);
        } else {
            emitter.calls.emitArgumentsArray(ctor.delegateArgs());
            String[] delNames = callArgNames(ctor.delegateArgs());
            if (delNames != null) {
                emitter.getStringArray(delNames);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitVarInsn(ALOAD, 5); // forward CallSite
        mv.visitMethodInsn(
                INVOKESTATIC, emitter.className, targetMethod, CLASS_BODY_DESCRIPTOR, false);

        if (ctor.body().isEmpty()) {
            mv.visitInsn(ARETURN);
            ctx.finish();
            return;
        }
        ctx.constructorClass = gen;
        ctx.typedThisLocal = ctx.newLocal();
        if (gen != null) {
            mv.visitTypeInsn(CHECKCAST, gen.name);
        }
        mv.visitVarInsn(ASTORE, ctx.typedThisLocal);
        for (HirVar variable : ctor.declaredVars()) {
            if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                emitter.initVariable(variable, PrimitiveKind.NONE);
            }
        }
        emitter.statements(ctor.body());
        popOn(mv, ctx.stackKind);
        mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
        mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void generateClassBody(
            HirClass cls, org.babblelang.ir.hir.HirConstructor ctor, String method) {
        MethodEmitContext ctx = emitter.ctx;
        ClassShape shape = cls.shape();
        int ownerId = ctor != null ? ctor.ownerId() : cls.id();
        List<HirParam> ctorParams = ctor != null ? ctor.params() : cls.params();
        String[] paramNames = ctor != null ? ctor.paramNames() : shape.parameters();
        int kwStart = ctor != null ? ctor.keywordOnlyStart() : shape.keywordOnlyStart();
        int restIndex = ctor != null ? ctor.restIndex() : -1;
        // Package-private so compiled sibling class/interface bodies in this script can adapt
        // cls$... via LambdaMetafactory.
        emitter.beginMethod(
                emitter.classWriter,
                ownerId,
                ACC_STATIC | ACC_SYNTHETIC,
                method,
                CLASS_BODY_DESCRIPTOR,
                FN_NS + 1,
                FN_RT,
                FN_NS,
                cls.capturedVars());
        MethodVisitor mv = ctx.mv;
        emitter.frameVars.loadCapturedCells(BABBLE_CLASS);

        ClassGen gen = emitter.classGen(cls.id());
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
        List<HirVar> allDeclared = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (HirVar v : cls.declaredVars()) {
            if (seenIds.add(v.id())) {
                allDeclared.add(v);
            }
        }
        if (ctor != null) {
            for (HirVar v : ctor.declaredVars()) {
                if (seenIds.add(v.id())) {
                    allDeclared.add(v);
                }
            }
        }
        for (HirVar variable : emitter.declareVariables(allDeclared)) {
            if (!deferredInit
                    && variable.kind() == Resolution.Variable.Kind.THIS
                    && variable.used()) {
                mv.visitVarInsn(ALOAD, INSTANCE);
                emitter.initVariable(variable, PrimitiveKind.NONE);
            }
        }

        Map<Integer, Integer> deferredMembers =
                deferredInit ? new java.util.LinkedHashMap<>() : null;
        bindParameters(
                emitter.constantPool.stringArrayField(paramNames),
                ctorParams,
                kwStart,
                restIndex,
                shape,
                deferredMembers,
                null);
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
            for (HirVar variable : allDeclared) {
                if (variable.kind() == Resolution.Variable.Kind.THIS && variable.used()) {
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    emitter.initVariable(variable, PrimitiveKind.NONE);
                } else if (variable.isCaptured()) {
                    emitter.frameVars.publishHidden(variable);
                }
            }
            for (Map.Entry<Integer, Integer> entry : deferredMembers.entrySet()) {
                int memberIdx = entry.getKey();
                if (!gen.isFinalField(memberIdx)) {
                    PrimitiveKind mk = gen.memberKinds[memberIdx];
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    loadKindOn(mv, mk, entry.getValue());
                    mv.visitFieldInsn(
                            PUTFIELD, gen.name, shape.member(memberIdx), jvmDescriptor(mk));
                }
            }
        }
        emitter.statements(cls.body());
        popOn(mv, ctx.stackKind);
        if (ctor != null && !ctor.body().isEmpty()) {
            emitter.statements(ctor.body());
            popOn(mv, ctx.stackKind);
        }
        mv.visitVarInsn(ALOAD, deferredInit ? ctx.typedThisLocal : INSTANCE);
        mv.visitInsn(ARETURN);
        ctx.finish();
    }

    private void bindParameters(
            String paramsField,
            List<HirParam> params,
            int kwStart,
            int restIndex,
            ClassShape shape) {
        bindParameters(paramsField, params, kwStart, restIndex, shape, null, null);
    }

    private void bindParameters(
            String paramsField,
            List<HirParam> params,
            int kwStart,
            int restIndex,
            ClassShape shape,
            Map<Integer, Integer> deferredMembers,
            int[] outParamSlots) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        boolean spread = ctx.parameterBase >= 0;
        int binder = spread ? BINDER_LOCAL : ctx.newLocal();
        int arrayValue = spread ? -1 : ctx.newLocal();
        if (!spread) {
            mv.visitFieldInsn(GETSTATIC, emitter.className, paramsField, "[Ljava/lang/String;");
            if (restIndex >= 0) {
                ConstantPool.pushInt(mv, kwStart);
                ConstantPool.pushInt(mv, restIndex);
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        ARGUMENTS,
                        "binder",
                        "([Ljava/lang/String;II[Ljava/lang/Object;[Ljava/lang/String;)L"
                                + BINDER
                                + ";",
                        false);
            } else if (kwStart < params.size()) {
                ConstantPool.pushInt(mv, kwStart);
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        ARGUMENTS,
                        "binder",
                        "([Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/String;)L"
                                + BINDER
                                + ";",
                        false);
            } else {
                mv.visitVarInsn(ALOAD, ARGS);
                mv.visitVarInsn(ALOAD, NAMES);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        ARGUMENTS,
                        "binder",
                        "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L"
                                + BINDER
                                + ";",
                        false);
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
                    emitter.emitBoxed(defaultValue);
                    ctx.mv.visitVarInsn(ASTORE, value);
                    ctx.mv.visitJumpInsn(GOTO, bind);
                });
            } else if (spread) {
                mv.visitLdcInsn(param.name());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        ARGUMENTS,
                        "missing",
                        "(Ljava/lang/String;)Lorg/babblelang/runtime/BabbleException;",
                        false);
                mv.visitInsn(ATHROW);
            } else {
                mv.visitVarInsn(ALOAD, binder);
                ConstantPool.pushInt(mv, i);
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        BINDER,
                        "missing",
                        "(I)Lorg/babblelang/runtime/BabbleException;",
                        false);
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
                emitter.controlOps.emitToJavaSamOnStack(param.samTarget(), param.line());
                mv.visitVarInsn(ASTORE, value);
            }
            if (outParamSlots != null) {
                PrimitiveKind pk = param.kind();
                mv.visitVarInsn(ALOAD, value);
                ctx.stackKind = PrimitiveKind.NONE;
                emitter.convertStack(PrimitiveKind.NONE, pk);
                int slot = ctx.newLocal(pk);
                storeKindOn(mv, pk, slot);
                outParamSlots[i] = slot;
                if (param.var() != null) {
                    HirVar variable = param.var();
                    PrimitiveKind vk = variable.primitiveKind();
                    loadKindOn(mv, pk, slot);
                    ctx.stackKind = pk;
                    emitter.convertStack(pk, vk);
                    if (variable.cell()) {
                        emitter.storeVariable(variable, true, vk, param.line());
                        popOn(mv, vk);
                    } else {
                        emitter.initVariable(variable, vk);
                    }
                }
                ctx.stackKind = PrimitiveKind.NONE;
                continue;
            }
            int member = shape != null ? param.memberIndex() : -1;
            if (member >= 0 && ctx.constructorClass != null) {
                PrimitiveKind mk = ctx.constructorClass.memberKinds[member];
                if (deferredMembers != null) {
                    mv.visitVarInsn(ALOAD, value);
                    ctx.stackKind = PrimitiveKind.NONE;
                    emitter.convertStack(PrimitiveKind.NONE, mk);
                    int mSlot = ctx.newLocal(mk);
                    storeKindOn(mv, mk, mSlot);
                    deferredMembers.put(member, mSlot);
                    ctx.stackKind = PrimitiveKind.NONE;
                } else {
                    mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
                    mv.visitVarInsn(ALOAD, value);
                    ctx.stackKind = PrimitiveKind.NONE;
                    emitter.convertStack(PrimitiveKind.NONE, mk);
                    mv.visitFieldInsn(
                            PUTFIELD,
                            ctx.constructorClass.name,
                            shape.member(member),
                            jvmDescriptor(mk));
                    ctx.stackKind = PrimitiveKind.NONE;
                }
            } else if (param.var() != null) {
                HirVar variable = param.var();
                PrimitiveKind vk = variable.primitiveKind();
                mv.visitVarInsn(ALOAD, value);
                ctx.stackKind = PrimitiveKind.NONE;
                emitter.convertStack(PrimitiveKind.NONE, vk);
                if (variable.cell()) {
                    emitter.storeVariable(variable, true, vk, param.line());
                    popOn(mv, vk);
                } else {
                    emitter.initVariable(variable, vk);
                }
            } else {
                mv.visitVarInsn(ALOAD, ctx.frameNs);
                mv.visitLdcInsn(param.name());
                mv.visitVarInsn(ALOAD, value);
                mv.visitInsn(param.mutable() ? ICONST_0 : ICONST_1);
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        NAMESPACE,
                        "defineParameter",
                        "(Ljava/lang/String;Ljava/lang/Object;Z)V",
                        false);
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

    static String[] callArgNames(List<org.babblelang.ir.hir.HirCallArg> parameters) {
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
}
