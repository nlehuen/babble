package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * ANTLR-free JVM class generator for a compiled {@link HirClass} (compiler phase 2, detached in
 * BIP014-I1).
 */
final class ClassGen {
    private final HirEmitter emitter;
    final HirClass cls;
    final String name;
    final ClassShape shape;
    final String[] implementedInterfaces;
    final List<Class<?>> javaInterfaces;
    final ClassWriter writer;
    final List<HirVar> hiddenVars = new ArrayList<>();
    final java.util.Set<Integer> hiddenVarIds = new java.util.HashSet<>();
    final PrimitiveKind[] memberKinds;
    final int[] primDefBits;
    final int primDefCount;
    final int[] finalHeaderMemberIndices;
    final boolean[] finalMemberFlags;
    final boolean directCtorInit;
    final String[] rootInitDescriptors;
    final int[] rootInitTagCount;
    final String initDescriptor;
    final Map<String, String> superHelpers = new HashMap<>();

    ClassGen(HirEmitter emitter, HirClass cls, boolean fbipReused) {
        this.emitter = emitter;
        this.cls = cls;
        this.name = cls.jvmName();
        this.shape = cls.shape();
        this.implementedInterfaces = cls.implementedJvmInterfaces().toArray(new String[0]);
        this.javaInterfaces = cls.javaInterfaces();
        this.writer =
                new ScriptClassWriter(
                        emitter.runtimeLoader,
                        emitter.className,
                        emitter.generatedClassParents,
                        emitter.generatedInterfaceParents);
        for (HirVar variable : cls.declaredVars()) {
            if (variable.isCaptured()) {
                hiddenVars.add(variable);
                hiddenVarIds.add(variable.id());
            }
        }
        this.memberKinds = cls.memberKinds();
        this.finalMemberFlags = new boolean[shape.memberCount()];
        boolean canDirectInit =
                !cls.isObject()
                        && !fbipReused
                        && !cls.fastRecord()
                        && cls.allDefFieldsFinalSafe()
                        && !cls.constructors().isEmpty()
                        && canUseFinalHeaderInit(cls);
        this.directCtorInit = canDirectInit;
        if (canDirectInit) {
            for (int i = 0; i < shape.memberCount(); i++) {
                if (!shape.isMutable(i) && !isStaticMethodMember(i)) {
                    finalMemberFlags[i] = true;
                }
            }
            this.finalHeaderMemberIndices = new int[0];
        } else if (!cls.isObject() && !fbipReused && canUseFinalHeaderInit(cls)) {
            List<Integer> finals = new ArrayList<>();
            for (HirParam p : cls.params()) {
                int idx = p.memberIndex();
                if (idx >= 0 && !shape.isMutable(idx)) {
                    finals.add(idx);
                    finalMemberFlags[idx] = true;
                }
            }
            this.finalHeaderMemberIndices = finals.stream().mapToInt(Integer::intValue).toArray();
        } else {
            this.finalHeaderMemberIndices = new int[0];
        }
        int[] rawPrimBits = cls.primDefBits();
        int[] adjPrimBits = new int[rawPrimBits.length];
        java.util.Arrays.fill(adjPrimBits, -1);
        int bitCursor = 0;
        for (int i = 0; i < rawPrimBits.length; i++) {
            if (rawPrimBits[i] >= 0 && !isFinalField(i)) {
                adjPrimBits[i] = bitCursor++;
            }
        }
        this.primDefBits = adjPrimBits;
        this.primDefCount = bitCursor;

        if (canDirectInit) {
            int nCtors = cls.constructors().size();
            this.rootInitDescriptors = new String[nCtors];
            this.rootInitTagCount = new int[nCtors];
            java.util.Set<String> usedDescs = new java.util.HashSet<>();
            for (int k = 0; k < nCtors; k++) {
                org.babblelang.ir.hir.HirConstructor ctor = cls.constructors().get(k);
                if (ctor.isDelegating()) {
                    continue;
                }
                StringBuilder d =
                        new StringBuilder("(L")
                                .append(BABBLE_CLASS)
                                .append(";L")
                                .append(SCRIPT_RUNTIME)
                                .append(';');
                for (HirParam p : ctor.params()) {
                    d.append(jvmDescriptor(p.kind()));
                }
                String cand = d.toString() + ")V";
                int tags = 0;
                while (!usedDescs.add(cand)) {
                    d.append('I');
                    tags++;
                    cand = d.toString() + ")V";
                }
                this.rootInitTagCount[k] = tags;
                this.rootInitDescriptors[k] = cand;
            }
        } else {
            this.rootInitDescriptors = new String[0];
            this.rootInitTagCount = new int[0];
        }
        StringBuilder initDesc =
                new StringBuilder("(L")
                        .append(BABBLE_CLASS)
                        .append(";L")
                        .append(SCRIPT_RUNTIME)
                        .append(';');
        for (int idx : finalHeaderMemberIndices) {
            initDesc.append(jvmDescriptor(memberKinds[idx]));
        }
        this.initDescriptor = initDesc.append(")V").toString();
    }

    boolean hasFinalHeaderFields() {
        return finalHeaderMemberIndices.length > 0;
    }

    boolean usesDirectCtorInit() {
        return directCtorInit;
    }

    String rootInitDescriptor(int ctorIndex) {
        return rootInitDescriptors[ctorIndex];
    }

    int rootInitTagCount(int ctorIndex) {
        return rootInitTagCount[ctorIndex];
    }

    boolean isFinalField(int memberIndex) {
        return memberIndex >= 0
                && memberIndex < finalMemberFlags.length
                && finalMemberFlags[memberIndex];
    }

    boolean hasHiddenField(HirVar variable) {
        return variable != null && hiddenVarIds.contains(variable.id());
    }

    /**
     * The member {@code def}s of a {@code fastRecord}, by member index : its methods, whose value
     * is the same per-class {@code CompiledMethod} or {@code OverloadSet} constant for every
     * instance and can never be assigned. They get no instance field : every reader loads the
     * script class's constant instead (see {@link #loadStaticMethodMember}).
     */
    private Map<Integer, org.babblelang.ir.hir.HirExpr.Def> staticMethodDefs;

    private Map<Integer, org.babblelang.ir.hir.HirExpr.Def> staticMethodDefs() {
        if (staticMethodDefs == null) {
            staticMethodDefs = new HashMap<>();
            if (cls.fastRecord()) {
                for (org.babblelang.ir.hir.HirExpr stmt : cls.body()) {
                    if (stmt instanceof org.babblelang.ir.hir.HirExpr.Def d
                            && d.target().kind() == LoweringTargets.DefKind.MEMBER
                            && shape.isMethod(d.target().index())
                            && (d.value() instanceof org.babblelang.ir.hir.HirExpr.FunctionRef
                                    || d.value()
                                            instanceof
                                            org.babblelang.ir.hir.HirExpr.MakeOverloadSet)) {
                        staticMethodDefs.put(d.target().index(), d);
                    }
                }
            }
        }
        return staticMethodDefs;
    }

    /**
     * Whether member {@code index} is a method kept in a per-class constant rather than an instance
     * field.
     */
    boolean isStaticMethodMember(int index) {
        return staticMethodDefs().containsKey(index);
    }

    /**
     * Pushes the per-class constant of a {@linkplain #isStaticMethodMember static method member}.
     */
    void loadStaticMethodMember(MethodVisitor m, int index) {
        org.babblelang.ir.hir.HirExpr.Def d = staticMethodDefs().get(index);
        if (d.value() instanceof org.babblelang.ir.hir.HirExpr.FunctionRef ref) {
            String field =
                    emitter.compiledMethodField(emitter.module.function(ref.functionId()), this);
            m.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + COMPILED_METHOD + ";");
        } else {
            String field =
                    emitter.cachedMethodOverloadField(
                            (org.babblelang.ir.hir.HirExpr.MakeOverloadSet) d.value(), this);
            m.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + OVERLOAD_SET + ";");
        }
    }

    /**
     * Pushes method member {@code index} of the instance on the stack, consuming it : the per-class
     * constant, or the instance's field.
     */
    void loadMethodMember(MethodVisitor m, int index) {
        if (isStaticMethodMember(index)) {
            m.visitInsn(POP);
            loadStaticMethodMember(m, index);
        } else {
            m.visitFieldInsn(GETFIELD, name, shape.member(index), "Ljava/lang/Object;");
        }
    }

    /**
     * The name of the accessor every compiled class has for each of its methods, whatever its
     * layout.
     */
    static String methodAccessor(String member) {
        return "$m$" + member;
    }

    private static boolean canUseFinalHeaderInit(HirClass cls) {
        for (HirParam p : cls.params()) {
            if (p.defaultValue() != null && referencesThis(p.defaultValue())) {
                return false;
            }
        }
        for (org.babblelang.ir.hir.HirConstructor ctor : cls.constructors()) {
            for (HirParam p : ctor.params()) {
                if (p.defaultValue() != null && referencesThis(p.defaultValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean referencesThis(org.babblelang.ir.hir.HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof org.babblelang.ir.hir.HirExpr.ThisRef
                || expr instanceof org.babblelang.ir.hir.HirExpr.SuperCall
                || expr instanceof org.babblelang.ir.hir.HirExpr.ThisFieldGet
                || expr instanceof org.babblelang.ir.hir.HirExpr.ThisFieldAssign) {
            return true;
        }
        boolean[] found = {false};
        org.babblelang.opt.HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && referencesThis(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    String methodName(String member) {
        return cls.methodName(member);
    }

    private HirFunction methodFunction(String member) {
        Integer fnId = cls.methodFunctionIds().get(member);
        if (fnId == null) {
            throw new IllegalStateException("No method " + member + " in " + name);
        }
        HirFunction fn = emitter.module.function(fnId);
        emitter.ensureFunctionScheduled(fn);
        return fn;
    }

    private HirFunction overloadMethodFunction(String member, int altIndex) {
        List<Integer> ids = cls.overloadMethodFunctionIds().get(member);
        if (ids == null || altIndex < 0 || altIndex >= ids.size()) {
            return null;
        }
        int fnId = ids.get(altIndex);
        if (fnId < 0) {
            return null;
        }
        HirFunction fn = emitter.module.function(fnId);
        emitter.ensureFunctionScheduled(fn);
        return fn;
    }

    String superHelper(
            String directParentJvm, String memberName, LoweringTargets.FunctionSignature sig) {
        return emitter.bridges.superHelper(
                writer, superHelpers, ACC_PUBLIC | ACC_SYNTHETIC, directParentJvm, memberName, sig);
    }

    void begin() {
        writer.visit(
                V21,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                name,
                null,
                OBJECT,
                implementedInterfaces.length == 0 ? null : implementedInterfaces);
        writer.visitSource(emitter.sourceName, null);
        writer.visitField(
                        ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
                        "$babble$class",
                        "L" + BABBLE_CLASS + ";",
                        null,
                        null)
                .visitEnd();
        writer.visitField(
                        ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
                        "$babble$rt",
                        "L" + SCRIPT_RUNTIME + ";",
                        null,
                        null)
                .visitEnd();
        for (int i = 0; i < shape.memberCount(); i++) {
            if (isStaticMethodMember(i)) {
                continue;
            }
            int access = isFinalField(i) ? (ACC_PUBLIC | ACC_FINAL) : ACC_PUBLIC;
            writer.visitField(access, shape.member(i), jvmDescriptor(memberKinds[i]), null, null)
                    .visitEnd();
        }
        for (HirVar variable : hiddenVars) {
            String desc =
                    variable.cell()
                            ? "Ljava/lang/Object;"
                            : jvmDescriptor(variable.primitiveKind());
            writer.visitField(0, variable.hiddenFieldName(), desc, null, null).visitEnd();
        }
        int definedWords = (primDefCount + 31) / 32;
        for (int w = 0; w < definedWords; w++) {
            writer.visitField(0, "$defined$" + w, "I", null, null).visitEnd();
        }

        if (!directCtorInit) {
            MethodVisitor init =
                    writer.visitMethod(ACC_PUBLIC, "<init>", initDescriptor, null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, name, "$babble$class", "L" + BABBLE_CLASS + ";");
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 2);
            init.visitFieldInsn(PUTFIELD, name, "$babble$rt", "L" + SCRIPT_RUNTIME + ";");
            int paramSlot = 3;
            for (int idx : finalHeaderMemberIndices) {
                PrimitiveKind mk = memberKinds[idx];
                init.visitVarInsn(ALOAD, 0);
                loadKindOn(init, mk, paramSlot);
                init.visitFieldInsn(PUTFIELD, name, shape.member(idx), jvmDescriptor(mk));
                paramSlot += mk.slots();
            }
            for (int i = 0; i < shape.memberCount(); i++) {
                if (!isFinalField(i) && !memberKinds[i].isPrimitive() && !isStaticMethodMember(i)) {
                    init.visitVarInsn(ALOAD, 0);
                    init.visitFieldInsn(
                            GETSTATIC,
                            RUNTIME + "Undefined",
                            "UNDEFINED",
                            "L" + RUNTIME + "Undefined;");
                    init.visitFieldInsn(PUTFIELD, name, shape.member(i), "Ljava/lang/Object;");
                }
            }
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }

        if (!javaInterfaces.isEmpty()) {
            MethodVisitor lookupMv =
                    writer.visitMethod(
                            ACC_PUBLIC | ACC_SYNTHETIC,
                            "$babble$lookup",
                            "()Ljava/lang/invoke/MethodHandles$Lookup;",
                            null,
                            null);
            lookupMv.visitCode();
            lookupMv.visitMethodInsn(
                    INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;",
                    false);
            lookupMv.visitInsn(ARETURN);
            lookupMv.visitMaxs(0, 0);
            lookupMv.visitEnd();
        }
        memberTable("$babble$field", "(I)Ljava/lang/Object;", false);
        memberTable("$babble$setField", "(ILjava/lang/Object;)V", true);
        emitMethodAccessors();
        if (primDefCount > 0) {
            isMemberDefinedMethod();
        }
        emitEqualsMethod();
        if (shape.isValue()) {
            emitRecordHashCode();
        }
        emitToStringMethod();
    }

    /**
     * Emits {@code public static <Name> $new(BabbleClass, ScriptRuntime, <typed params...>)} for
     * capture-free method-only {@code record}s ({@code BIP014-I4}), bypassing {@code Object[]}
     * boxing, {@code Binder}, and {@code Bootstrap.call}.
     */
    void emitFastRecordConstructor() {
        if (!cls.fastRecord()) {
            return;
        }
        List<HirParam> params = cls.params();
        PrimitiveKind[] pKinds = new PrimitiveKind[params.size()];
        for (int i = 0; i < params.size(); i++) {
            pKinds[i] = params.get(i).kind();
        }
        String desc = fastRecordFactoryDescriptor(name, pKinds);
        MethodVisitor m =
                writer.visitMethod(
                        ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, "$new", desc, null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, name);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        if (hasFinalHeaderFields()) {
            int slot = 2;
            for (int i = 0; i < params.size(); i++) {
                HirParam p = params.get(i);
                int memberIdx = p.memberIndex();
                PrimitiveKind mk = memberKinds[memberIdx];
                loadKindOn(m, p.kind(), slot);
                if (p.kind() != mk) {
                    if (p.kind().isPrimitive() && !mk.isPrimitive()) {
                        boxOn(m, p.kind());
                    } else if (!p.kind().isPrimitive() && mk.isPrimitive()) {
                        unboxOn(m, mk);
                    }
                }
                slot += p.kind().slots();
            }
            m.visitMethodInsn(INVOKESPECIAL, name, "<init>", initDescriptor, false);
        } else {
            m.visitMethodInsn(INVOKESPECIAL, name, "<init>", initDescriptor, false);
            int slot = 2;
            for (int i = 0; i < params.size(); i++) {
                HirParam p = params.get(i);
                int memberIdx = p.memberIndex();
                PrimitiveKind mk = memberKinds[memberIdx];
                m.visitInsn(DUP);
                loadKindOn(m, p.kind(), slot);
                if (p.kind() != mk) {
                    if (p.kind().isPrimitive() && !mk.isPrimitive()) {
                        boxOn(m, p.kind());
                    } else if (!p.kind().isPrimitive() && mk.isPrimitive()) {
                        unboxOn(m, mk);
                    }
                }
                m.visitFieldInsn(PUTFIELD, name, shape.member(memberIdx), jvmDescriptor(mk));
                slot += p.kind().slots();
            }
        }
        // The methods are per-class constants (isStaticMethodMember) : nothing to store.
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    void emitInterfaceBridges() {
        LoweringTargets.TypeHierarchyInfo hierarchy = emitter.module.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Map<String, BridgeHelpers.AncestorMethod> ancestors =
                emitter.bridges.collectAncestorMethods(hierarchy);
        for (BridgeHelpers.AncestorMethod anc : ancestors.values()) {
            String memberName = anc.memberName();
            LoweringTargets.FunctionSignature sig = anc.signature();
            int[] paramSlots = bridgeParamSlots(sig);
            int ifaceCount = sig.parameterKinds().length;
            MethodVisitor m =
                    writer.visitMethod(
                            ACC_PUBLIC | ACC_SYNTHETIC,
                            bridgeMethodName(anc.bridgeKey()),
                            anc.descriptor(),
                            null,
                            null);
            m.visitCode();
            int ownIdx = shape.memberIndex(memberName);
            boolean ownOverload =
                    ownIdx >= 0
                            && shape.isMethod(ownIdx)
                            && cls.overloadMethodFunctionIds().containsKey(memberName);
            if (ownIdx >= 0 && shape.isMethod(ownIdx)) {
                Integer altIdx =
                        ownOverload ? hierarchy.babbleBridgeOverloads().get(anc.bridgeKey()) : null;
                HirFunction fn =
                        ownOverload
                                ? (altIdx != null
                                        ? overloadMethodFunction(memberName, altIdx)
                                        : null)
                                : methodFunction(memberName);
                if (fn != null) {
                    int fnCount = fn.params().size();
                    String jvmMethod = fn.jvmMethodName();
                    m.visitVarInsn(ALOAD, 0);
                    if (isStaticMethodMember(ownIdx)) {
                        loadStaticMethodMember(m, ownIdx);
                    } else {
                        m.visitInsn(DUP);
                        m.visitFieldInsn(GETFIELD, name, memberName, "Ljava/lang/Object;");
                        m.visitLdcInsn(memberName);
                        m.visitMethodInsn(
                                INVOKESTATIC,
                                CLASSES,
                                "member",
                                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                                false);
                    }
                    if (ownOverload) {
                        ConstantPool.pushInt(m, altIdx);
                        m.visitMethodInsn(
                                INVOKESTATIC,
                                OVERLOAD_SET,
                                "memberOf",
                                "(Ljava/lang/Object;I)L" + CALLABLE + ";",
                                false);
                    }
                    m.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
                    m.visitVarInsn(ALOAD, 1);
                    if (fn.hasSpec()
                            && fn.restIndex() == sig.restIndex()
                            && ifaceCount <= fnCount
                            && allOptionalFrom(fn, ifaceCount)) {
                        List<HirParam> params = fn.params();
                        for (int i = 0; i < ifaceCount; i++) {
                            HirParam param = params.get(i);
                            emitAdaptBridgeArg(
                                    m, sig, paramSlots, i, param.kind(), param.wideningRank());
                        }
                        for (int i = ifaceCount; i < fnCount; i++) {
                            emitDefaultValueOn(m, params.get(i).kind());
                        }
                        emitRemapBridgeMask(m, sig, fn, ifaceCount);
                        m.visitMethodInsn(
                                INVOKEVIRTUAL,
                                name,
                                jvmMethod + "$spec",
                                methodSpecDescriptor(fn),
                                false);
                        emitAdaptKind(m, fn.returnKind(), sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    } else if (fn.isSpread()
                            && fn.restIndex() == sig.restIndex()
                            && ifaceCount <= fnCount) {
                        m.visitInsn(ACONST_NULL);
                        for (int i = 0; i < ifaceCount; i++) {
                            emitLoadBridgeArgBoxed(m, sig, paramSlots, i);
                        }
                        for (int i = ifaceCount; i < fnCount; i++) {
                            m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                        }
                        m.visitMethodInsn(
                                INVOKEVIRTUAL, name, jvmMethod, methodDirectDescriptor(fn), false);
                        emitAdaptKind(m, PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    } else {
                        String siteField =
                                emitter.constantPool.syntheticCallSiteField(
                                        shape.name() + "." + memberName, ifaceCount);
                        packBridgeArgsArray(m, sig, paramSlots);
                        if (sig.restIndex() >= 0 && sig.restIndex() < ifaceCount) {
                            m.visitInsn(DUP);
                            ConstantPool.pushInt(m, sig.restIndex());
                            m.visitInsn(DUP2);
                            m.visitInsn(AALOAD);
                            m.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                            m.visitMethodInsn(
                                    INVOKESTATIC,
                                    SPREADS,
                                    "packedRest",
                                    "(Ljava/lang/Iterable;)Ljava/lang/Object;",
                                    false);
                            m.visitInsn(AASTORE);
                        }
                        m.visitInsn(ACONST_NULL);
                        m.visitFieldInsn(
                                GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
                        m.visitMethodInsn(
                                INVOKEVIRTUAL,
                                name,
                                jvmMethod + (fn.isSpread() ? "$call" : ""),
                                METHOD_DESCRIPTOR,
                                false);
                        emitAdaptKind(m, PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(returnInsn(sig.returnKind()));
                    }
                } else {
                    emitBridgeDynamicFallback(
                            m, memberName, altIdx, anc.signatureKey(), sig, paramSlots, ifaceCount);
                }
            } else if (ownIdx >= 0) {
                emitBridgeDynamicFallback(
                        m, memberName, null, anc.signatureKey(), sig, paramSlots, ifaceCount);
            } else {
                BridgeHelpers.InheritedDefault def =
                        emitter.bridges.findInheritedDefault(hierarchy, memberName);
                String directParentJvm =
                        def != null ? emitter.allInterfaceJvmNames.get(def.directParentId()) : null;
                emitter.bridges.emitInheritedDefaultInvoke(
                        m,
                        def,
                        directParentJvm,
                        memberName,
                        anc.bridgeKey(),
                        anc.signatureKey(),
                        anc.descriptor(),
                        sig);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        if (!javaInterfaces.isEmpty()) {
            emitJavaInterfaceBridges(hierarchy, ancestors);
        }
    }

    private void emitBridgeDynamicFallback(
            MethodVisitor m,
            String memberName,
            Integer altIdx,
            String sigKey,
            LoweringTargets.FunctionSignature sig,
            int[] paramSlots,
            int ifaceCount) {
        String siteField =
                emitter.constantPool.syntheticCallSiteField(
                        shape.name() + "." + memberName, ifaceCount);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(memberName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(
                INVOKESTATIC,
                BABBLE_OBJECTS,
                "getMember",
                "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;",
                false);
        if (altIdx != null) {
            ConstantPool.pushInt(m, altIdx);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    OVERLOAD_SET,
                    "memberOf",
                    "(Ljava/lang/Object;I)L" + CALLABLE + ";",
                    false);
        } else if (sigKey != null) {
            m.visitLdcInsn(sigKey);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    OVERLOAD_SET,
                    "selectFrom",
                    "(Ljava/lang/Object;Ljava/lang/String;)L" + CALLABLE + ";",
                    false);
        } else {
            m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Calls",
                    "callable",
                    "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";",
                    false);
        }
        m.visitVarInsn(ALOAD, 1);
        packBridgeArgsArray(m, sig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
        m.visitMethodInsn(
                INVOKEINTERFACE,
                CALLABLE,
                "call",
                "(L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;",
                true);
        emitAdaptKind(m, PrimitiveKind.NONE, sig.returnKind());
        m.visitInsn(returnInsn(sig.returnKind()));
    }

    private void emitJavaInterfaceBridges(
            LoweringTargets.TypeHierarchyInfo hierarchy,
            Map<String, BridgeHelpers.AncestorMethod> babbleAncestors) {
        TreeMap<String, List<Method>> grouped = new TreeMap<>();
        for (Class<?> jc : javaInterfaces) {
            for (Method jm : jc.getMethods()) {
                if (Modifier.isStatic(jm.getModifiers()) || JavaTypes.isObjectMethod(jm)) {
                    continue;
                }
                String key = jm.getName() + "#" + Type.getMethodDescriptor(jm);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(jm);
            }
        }
        for (Map.Entry<String, List<Method>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            List<Method> candidates = entry.getValue();
            candidates.sort(
                    Comparator.comparing(Method::isBridge)
                            .thenComparing(m -> m.getDeclaringClass().getName()));
            Method jm = candidates.get(0);
            String mName = jm.getName();
            int ownIdx = shape.memberIndex(mName);
            boolean ownMethod = ownIdx >= 0 && shape.isMethod(ownIdx);
            boolean ownOverload = ownMethod && cls.overloadMethodFunctionIds().containsKey(mName);
            BridgeHelpers.InheritedDefault inheritedBabbleDefault =
                    ownIdx < 0 ? emitter.bridges.findInheritedDefault(hierarchy, mName) : null;
            boolean hasBabbleImpl = ownMethod || inheritedBabbleDefault != null;
            boolean hasActiveJavaDefault = hasActiveJavaDefault(candidates);
            Integer overloadAltIdx =
                    hierarchy != null ? hierarchy.javaBridgeOverloads().get(key) : null;
            if (!hasBabbleImpl) {
                if (hasActiveJavaDefault) {
                    continue;
                }
            } else if (hasActiveJavaDefault && ownOverload && overloadAltIdx == null) {
                continue;
            } else if (hasActiveJavaDefault
                    && ownMethod
                    && !ownOverload
                    && !acceptsJavaDefaultOverload(methodFunction(mName), jm.getParameterTypes())) {
                continue;
            } else if (hasActiveJavaDefault
                    && !ownMethod
                    && inheritedBabbleDefault != null
                    && inheritedBabbleDefault.providerSpec().isOverloaded()
                    && overloadAltIdx == null) {
                continue;
            }
            emitJavaMethodBridge(
                    jm,
                    ownMethod,
                    ownOverload,
                    overloadAltIdx,
                    inheritedBabbleDefault,
                    babbleAncestors.get(mName));
        }
    }

    private static boolean hasActiveJavaDefault(List<Method> candidates) {
        for (Method cand : candidates) {
            if (cand.isDefault()) {
                boolean reabstracted = false;
                for (Method other : candidates) {
                    if (!other.isDefault()
                            && other.getDeclaringClass() != cand.getDeclaringClass()
                            && cand.getDeclaringClass()
                                    .isAssignableFrom(other.getDeclaringClass())) {
                        reabstracted = true;
                        break;
                    }
                }
                if (!reabstracted) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean acceptsJavaDefaultOverload(HirFunction fn, Class<?>[] javaParams) {
        if (fn == null) {
            return true;
        }
        if (javaParams.length < fn.requiredParams() || javaParams.length > fn.params().size()) {
            return false;
        }
        for (int i = 0; i < javaParams.length; i++) {
            String checked = fn.params().get(i).checkedType();
            if (!org.babblelang.runtime.Types.canAcceptJavaParamClass(checked, javaParams[i])) {
                return false;
            }
        }
        return true;
    }

    private void emitJavaMethodBridge(
            Method jm,
            boolean ownMethod,
            boolean ownOverload,
            Integer overloadAltIdx,
            BridgeHelpers.InheritedDefault inheritedBabbleDefault,
            BridgeHelpers.AncestorMethod babbleAnc) {
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
            ConstantPool.pushString(m, "No implementation of " + JavaTypes.signature(jm));
            m.visitMethodInsn(
                    INVOKESPECIAL,
                    RUNTIME + "BabbleException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false);
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
        m.visitFieldInsn(GETFIELD, name, "$babble$rt", "L" + SCRIPT_RUNTIME + ";");
        m.visitVarInsn(ASTORE, rtSlot);
        String descText = shape.name() + "." + mName;

        if (ownMethod) {
            HirFunction fn =
                    ownOverload
                            ? (overloadAltIdx != null
                                    ? overloadMethodFunction(mName, overloadAltIdx)
                                    : null)
                            : methodFunction(mName);
            if (fn != null) {
                int fnCount = fn.params().size();
                String jvmMethod = fn.jvmMethodName();
                m.visitVarInsn(ALOAD, 0);
                int mIdx = shape.memberIndex(mName);
                if (isStaticMethodMember(mIdx)) {
                    loadStaticMethodMember(m, mIdx);
                } else {
                    m.visitInsn(DUP);
                    m.visitFieldInsn(GETFIELD, name, mName, "Ljava/lang/Object;");
                    m.visitLdcInsn(mName);
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            CLASSES,
                            "member",
                            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                            false);
                }
                if (ownOverload) {
                    ConstantPool.pushInt(m, overloadAltIdx);
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            OVERLOAD_SET,
                            "memberOf",
                            "(Ljava/lang/Object;I)L" + CALLABLE + ";",
                            false);
                }
                m.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
                m.visitVarInsn(ALOAD, rtSlot);
                if (fn.hasSpec()
                        && fn.restIndex() < 0
                        && paramTypes.length <= fnCount
                        && allOptionalFrom(fn, paramTypes.length)) {
                    List<HirParam> params = fn.params();
                    for (int i = 0; i < paramTypes.length; i++) {
                        HirParam param = params.get(i);
                        emitLoadJavaBridgeArg(
                                m,
                                paramTypes[i],
                                javaParamSlots[i],
                                param.kind(),
                                param.wideningRank(),
                                param.checkedType());
                    }
                    for (int i = paramTypes.length; i < fnCount; i++) {
                        emitDefaultValueOn(m, params.get(i).kind());
                    }
                    if (fn.hasOptionalMask()) {
                        ConstantPool.pushInt(
                                m,
                                org.babblelang.runtime.Arguments.remapMask(
                                        0, fn.optionalParamBits(), paramTypes.length));
                    }
                    m.visitMethodInsn(
                            INVOKEVIRTUAL,
                            name,
                            jvmMethod + "$spec",
                            methodSpecDescriptor(fn),
                            false);
                    emitReturnToJava(m, fn.returnKind(), retType, descText, rtSlot);
                } else if (fn.isSpread() && fn.restIndex() < 0 && paramTypes.length <= fnCount) {
                    List<HirParam> params = fn.params();
                    m.visitInsn(ACONST_NULL);
                    for (int i = 0; i < paramTypes.length; i++) {
                        emitLoadJavaBridgeArg(
                                m,
                                paramTypes[i],
                                javaParamSlots[i],
                                PrimitiveKind.NONE,
                                -1,
                                params.get(i).checkedType());
                    }
                    for (int i = paramTypes.length; i < fnCount; i++) {
                        m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                    }
                    m.visitMethodInsn(
                            INVOKEVIRTUAL, name, jvmMethod, methodDirectDescriptor(fn), false);
                    emitReturnToJava(m, PrimitiveKind.NONE, retType, descText, rtSlot);
                } else {
                    String siteField =
                            emitter.constantPool.javaCallSiteField(mName, paramTypes.length);
                    emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
                    if (jm.isVarArgs()
                            && fn.restIndex() == paramTypes.length - 1
                            && paramTypes.length > 0) {
                        int lastIdx = paramTypes.length - 1;
                        Label skipWrap = new Label();
                        m.visitInsn(DUP);
                        ConstantPool.pushInt(m, lastIdx);
                        m.visitInsn(AALOAD);
                        m.visitJumpInsn(IFNULL, skipWrap);
                        m.visitInsn(DUP);
                        ConstantPool.pushInt(m, lastIdx);
                        m.visitInsn(DUP2);
                        m.visitInsn(AALOAD);
                        m.visitMethodInsn(
                                INVOKESTATIC,
                                ARGUMENTS_VIEW,
                                "fromArray",
                                "(Ljava/lang/Object;)L" + ARGUMENTS_VIEW + ";",
                                false);
                        m.visitMethodInsn(
                                INVOKESTATIC,
                                SPREADS,
                                "packedRest",
                                "(Ljava/lang/Iterable;)Ljava/lang/Object;",
                                false);
                        m.visitInsn(AASTORE);
                        m.visitLabel(skipWrap);
                    }
                    m.visitInsn(ACONST_NULL);
                    m.visitFieldInsn(
                            GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
                    m.visitMethodInsn(
                            INVOKEVIRTUAL,
                            name,
                            jvmMethod + (fn.isSpread() ? "$call" : ""),
                            METHOD_DESCRIPTOR,
                            false);
                    emitReturnToJava(m, PrimitiveKind.NONE, retType, descText, rtSlot);
                }
                m.visitMaxs(0, 0);
                m.visitEnd();
                return;
            }
        }

        if (!ownMethod
                && inheritedBabbleDefault != null
                && babbleAnc != null
                && !inheritedBabbleDefault.providerSpec().isOverloaded()
                && babbleAnc.signature().parameterKinds().length == paramTypes.length) {
            LoweringTargets.FunctionSignature ancSig = babbleAnc.signature();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, rtSlot);
            for (int i = 0; i < paramTypes.length; i++) {
                emitLoadJavaBridgeArg(
                        m, paramTypes[i], javaParamSlots[i], ancSig.parameterKinds()[i], -1, null);
            }
            if (ancSig.hasOptionalMask()) {
                m.visitInsn(ICONST_0);
            }
            m.visitMethodInsn(
                    INVOKEVIRTUAL, name, bridgeMethodName(mName), babbleAnc.descriptor(), false);
            emitReturnToJava(m, ancSig.returnKind(), retType, descText, rtSlot);
            m.visitMaxs(0, 0);
            m.visitEnd();
            return;
        }

        String siteField = emitter.constantPool.javaCallSiteField(mName, paramTypes.length);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(mName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(
                INVOKESTATIC,
                BABBLE_OBJECTS,
                "getMember",
                "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;",
                false);
        if (overloadAltIdx != null) {
            ConstantPool.pushInt(m, overloadAltIdx);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    OVERLOAD_SET,
                    "memberOf",
                    "(Ljava/lang/Object;I)L" + CALLABLE + ";",
                    false);
        } else {
            m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
            m.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Calls",
                    "callable",
                    "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";",
                    false);
        }
        m.visitVarInsn(ALOAD, rtSlot);
        emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
        m.visitMethodInsn(
                INVOKEINTERFACE,
                CALLABLE,
                "call",
                "(L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;",
                true);
        emitReturnToJava(m, PrimitiveKind.NONE, retType, descText, rtSlot);
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
        ConstantPool.pushInt(m, mask);
        m.visitInsn(IOR);
        m.visitFieldInsn(PUTFIELD, name, definedField, "I");
    }

    void emitCheckDefined(
            MethodVisitor m,
            int instanceLocal,
            int memberIndex,
            String memberName,
            int lineNumber) {
        int bit = primDefBits[memberIndex];
        if (bit < 0) {
            return;
        }
        String definedField = "$defined$" + (bit / 32);
        int mask = 1 << (bit % 32);
        Label ok = new Label();
        m.visitVarInsn(ALOAD, instanceLocal);
        m.visitFieldInsn(GETFIELD, name, definedField, "I");
        ConstantPool.pushInt(m, mask);
        m.visitInsn(IAND);
        m.visitJumpInsn(IFNE, ok);
        emitter.line(lineNumber);
        m.visitLdcInsn(memberName);
        m.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Members",
                "usedBeforeDefinition",
                "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;",
                false);
        m.visitInsn(ATHROW);
        m.visitLabel(ok);
    }

    private void isMemberDefinedMethod() {
        MethodVisitor m =
                writer.visitMethod(
                        ACC_PUBLIC | ACC_SYNTHETIC, "$babble$isDefined", "(I)Z", null, null);
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
                    ConstantPool.pushInt(m, 1 << (bit % 32));
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

    /**
     * {@code $m$<name>()}, for each method member : its value, the per-class constant or the
     * instance's field. Other modules call methods through it, so that they do not depend on which
     * of the two the class keeps.
     */
    private void emitMethodAccessors() {
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!shape.isMethod(i)) {
                continue;
            }
            MethodVisitor m =
                    writer.visitMethod(
                            ACC_PUBLIC | ACC_SYNTHETIC,
                            methodAccessor(shape.member(i)),
                            "()Ljava/lang/Object;",
                            null,
                            null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            loadMethodMember(m, i);
            m.visitInsn(ARETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
    }

    private void memberTable(String method, String descriptor, boolean set) {
        MethodVisitor m =
                writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, method, descriptor, null, null);
        m.visitCode();
        Label fallback = new Label();
        if (shape.memberCount() > 0) {
            Label[] cases = new Label[shape.memberCount()];
            for (int i = 0; i < cases.length; i++) {
                cases[i] =
                        (set && (isFinalField(i) || isStaticMethodMember(i)))
                                ? fallback
                                : new Label();
            }
            m.visitVarInsn(ILOAD, 1);
            m.visitTableSwitchInsn(0, cases.length - 1, fallback, cases);
            for (int i = 0; i < cases.length; i++) {
                if (set && (isFinalField(i) || isStaticMethodMember(i))) {
                    continue;
                }
                m.visitLabel(cases[i]);
                if (!set && isStaticMethodMember(i)) {
                    loadStaticMethodMember(m, i);
                    m.visitInsn(ARETURN);
                    continue;
                }
                PrimitiveKind mk = memberKinds[i];
                String desc = jvmDescriptor(mk);
                if (set) {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitVarInsn(ALOAD, 2);
                    switch (mk) {
                        case BYTE -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            m.visitMethodInsn(
                                    INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                        }
                        case SHORT -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            m.visitMethodInsn(
                                    INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
                        }
                        case INT -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                            m.visitMethodInsn(
                                    INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                        }
                        case LONG -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            m.visitMethodInsn(
                                    INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
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
                                    INVOKEVIRTUAL,
                                    "java/lang/Boolean",
                                    "booleanValue",
                                    "()Z",
                                    false);
                        }
                        case CHAR -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Character");
                            m.visitMethodInsn(
                                    INVOKEVIRTUAL,
                                    "java/lang/Character",
                                    "charValue",
                                    "()C",
                                    false);
                        }
                        case NONE -> {}
                    }
                    m.visitFieldInsn(PUTFIELD, name, shape.member(i), desc);
                    emitMarkDefined(m, 0, i);
                    m.visitInsn(RETURN);
                } else {
                    if (primDefBits[i] >= 0) {
                        Label undef = new Label();
                        m.visitVarInsn(ALOAD, 0);
                        m.visitFieldInsn(GETFIELD, name, "$defined$" + (primDefBits[i] / 32), "I");
                        ConstantPool.pushInt(m, 1 << (primDefBits[i] % 32));
                        m.visitInsn(IAND);
                        m.visitJumpInsn(IFEQ, undef);
                        m.visitVarInsn(ALOAD, 0);
                        m.visitFieldInsn(GETFIELD, name, shape.member(i), desc);
                        boxOn(m, mk);
                        m.visitInsn(ARETURN);
                        m.visitLabel(undef);
                        m.visitFieldInsn(
                                GETSTATIC,
                                RUNTIME + "Undefined",
                                "UNDEFINED",
                                "L" + RUNTIME + "Undefined;");
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
        if (set) {
            m.visitInsn(RETURN);
        } else {
            m.visitInsn(ACONST_NULL);
            m.visitInsn(ARETURN);
        }
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void emitEqualsMethod() {
        MethodVisitor m =
                writer.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Values",
                "fromJava",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        m.visitVarInsn(ASTORE, 1);
        if (!shape.isValue()) {
            Label same = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitJumpInsn(IF_ACMPEQ, same);
            m.visitInsn(ICONST_0);
            m.visitInsn(IRETURN);
            m.visitLabel(same);
            m.visitInsn(ICONST_1);
            m.visitInsn(IRETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            return;
        }
        Label notSelf = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitJumpInsn(IF_ACMPNE, notSelf);
        m.visitInsn(ICONST_1);
        m.visitInsn(IRETURN);
        m.visitLabel(notSelf);

        Label falseLabel = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, name);
        m.visitJumpInsn(IFEQ, falseLabel);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, name);
        m.visitVarInsn(ASTORE, 2);

        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, name, "$babble$class", "L" + BABBLE_CLASS + ";");
        m.visitVarInsn(ALOAD, 2);
        m.visitFieldInsn(GETFIELD, name, "$babble$class", "L" + BABBLE_CLASS + ";");
        m.visitJumpInsn(IF_ACMPNE, falseLabel);

        for (int i = 0; i < shape.memberCount(); i++) {
            if (shape.isMethod(i)) {
                continue;
            }
            if (primDefBits[i] >= 0) {
                m.visitVarInsn(ALOAD, 0);
                ConstantPool.pushInt(m, i);
                m.visitMethodInsn(
                        INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitVarInsn(ALOAD, 2);
                ConstantPool.pushInt(m, i);
                m.visitMethodInsn(
                        INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitMethodInsn(
                        INVOKESTATIC,
                        RUNTIME + "Operators",
                        "equal",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false);
                m.visitJumpInsn(IFEQ, falseLabel);
                continue;
            }
            String member = shape.member(i);
            PrimitiveKind mk = memberKinds[i];
            String desc = jvmDescriptor(mk);
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, member, desc);
            m.visitVarInsn(ALOAD, 2);
            m.visitFieldInsn(GETFIELD, name, member, desc);
            switch (mk) {
                case BYTE, SHORT, INT, BOOL, CHAR -> m.visitJumpInsn(IF_ICMPNE, falseLabel);
                case LONG -> {
                    m.visitInsn(LCMP);
                    m.visitJumpInsn(IFNE, falseLabel);
                }
                case FLOAT -> {
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                    m.visitJumpInsn(IFNE, falseLabel);
                }
                case DOUBLE -> {
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                    m.visitJumpInsn(IFNE, falseLabel);
                }
                case NONE -> {
                    m.visitMethodInsn(
                            INVOKESTATIC,
                            RUNTIME + "Operators",
                            "equal",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                            false);
                    m.visitJumpInsn(IFEQ, falseLabel);
                }
            }
        }
        m.visitInsn(ICONST_1);
        m.visitInsn(IRETURN);

        m.visitLabel(falseLabel);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void emitRecordHashCode() {
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        m.visitCode();
        m.visitInsn(ICONST_1);
        for (int i = 0; i < shape.memberCount(); i++) {
            if (shape.isMethod(i)) {
                continue;
            }
            ConstantPool.pushInt(m, 31);
            m.visitInsn(IMUL);
            if (primDefBits[i] >= 0) {
                m.visitVarInsn(ALOAD, 0);
                ConstantPool.pushInt(m, i);
                m.visitMethodInsn(
                        INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitMethodInsn(
                        INVOKESTATIC, RUNTIME + "Values", "hash", "(Ljava/lang/Object;)I", false);
                m.visitInsn(IADD);
                continue;
            }
            PrimitiveKind mk = memberKinds[i];
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, shape.member(i), jvmDescriptor(mk));
            switch (mk) {
                case BYTE, SHORT, INT -> {
                    m.visitInsn(I2L);
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
                }
                case LONG ->
                        m.visitMethodInsn(
                                INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
                case BOOL ->
                        m.visitMethodInsn(
                                INVOKESTATIC, "java/lang/Boolean", "hashCode", "(Z)I", false);
                case CHAR ->
                        m.visitMethodInsn(
                                INVOKESTATIC, "java/lang/Character", "hashCode", "(C)I", false);
                case FLOAT -> {
                    m.visitInsn(F2D);
                    m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "hash", "(D)I", false);
                }
                case DOUBLE ->
                        m.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "hash", "(D)I", false);
                case NONE ->
                        m.visitMethodInsn(
                                INVOKESTATIC,
                                RUNTIME + "Values",
                                "hash",
                                "(Ljava/lang/Object;)I",
                                false);
            }
            m.visitInsn(IADD);
        }
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void emitToStringMethod() {
        MethodVisitor m =
                writer.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(
                INVOKESTATIC,
                BABBLE_OBJECTS,
                "toString",
                "(Ljava/lang/Object;)Ljava/lang/String;",
                false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
