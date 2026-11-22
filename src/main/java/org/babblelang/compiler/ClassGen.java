package org.babblelang.compiler;

import org.babblelang.analysis.ScopeAnalysis;
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.objectweb.asm.Opcodes.*;

/**
 * ANTLR-free JVM class generator for a compiled {@link HirClass} (compiler phase 2, detached in BIP014-P1).
 */
final class ClassGen {
    private final HirEmitter emitter;
    final HirClass cls;
    final String name;
    final ClassShape shape;
    final String[] implementedInterfaces;
    final List<Class<?>> javaInterfaces;
    final ClassWriter writer;
    final List<HirVar> hiddenCells = new ArrayList<>();
    final ScopeAnalysis.PrimitiveKind[] memberKinds;
    final int[] primDefBits;
    final int primDefCount;
    final Map<String, String> superHelpers = new HashMap<>();

    ClassGen(HirEmitter emitter, HirClass cls) {
        this.emitter = emitter;
        this.cls = cls;
        this.name = cls.jvmName();
        this.shape = cls.shape();
        this.implementedInterfaces = cls.implementedJvmInterfaces().toArray(new String[0]);
        this.javaInterfaces = cls.javaInterfaces();
        this.writer = new ScriptClassWriter(emitter.runtimeLoader, emitter.className,
                emitter.generatedClassParents, emitter.generatedInterfaceParents);
        for (HirVar variable : cls.declaredVars()) {
            if (variable.cell()) {
                hiddenCells.add(variable);
            }
        }
        this.memberKinds = cls.memberKinds();
        this.primDefBits = cls.primDefBits();
        this.primDefCount = cls.primDefCount();
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

    String superHelper(String directParentJvm, String memberName, ScopeAnalysis.FunctionSignature sig) {
        String desc = HirEmitter.bridgeDescriptor(sig);
        String key = directParentJvm + "." + memberName + desc;
        return superHelpers.computeIfAbsent(key, k -> {
            String helperName = "$super$" + emitter.nextSuperHelper();
            MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, helperName, desc, null, null);
            m.visitCode();
            int[] paramSlots = HirEmitter.bridgeParamSlots(sig);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < sig.parameterKinds().length; i++) {
                HirEmitter.loadKindOn(m, sig.parameterKinds()[i], paramSlots[i]);
            }
            if (sig.hasOptionalMask()) {
                m.visitVarInsn(ILOAD, HirEmitter.bridgeMaskSlot(sig));
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, HirEmitter.bridgeMethodName(memberName), desc, true);
            m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
            m.visitMaxs(0, 0);
            m.visitEnd();
            return helperName;
        });
    }

    void begin() {
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, name, null, HirEmitter.OBJECT,
                implementedInterfaces.length == 0 ? null : implementedInterfaces);
        writer.visitSource(emitter.sourceName, null);
        writer.visitField(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC, "$babble$class", "L" + HirEmitter.BABBLE_CLASS + ";", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC, "$babble$rt", "L" + HirEmitter.SCRIPT_RUNTIME + ";", null, null).visitEnd();
        for (int i = 0; i < shape.memberCount(); i++) {
            writer.visitField(0, shape.member(i), HirEmitter.jvmDescriptor(memberKinds[i]), null, null).visitEnd();
        }
        for (HirVar variable : hiddenCells) {
            writer.visitField(0, variable.hiddenFieldName(), "Ljava/lang/Object;", null, null).visitEnd();
        }
        int definedWords = (primDefCount + 31) / 32;
        for (int w = 0; w < definedWords; w++) {
            writer.visitField(0, "$defined$" + w, "I", null, null).visitEnd();
        }

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + HirEmitter.BABBLE_CLASS + ";L" + HirEmitter.SCRIPT_RUNTIME + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, HirEmitter.OBJECT, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, name, "$babble$class", "L" + HirEmitter.BABBLE_CLASS + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, name, "$babble$rt", "L" + HirEmitter.SCRIPT_RUNTIME + ";");
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!memberKinds[i].isPrimitive()) {
                init.visitVarInsn(ALOAD, 0);
                init.visitFieldInsn(GETSTATIC, HirEmitter.RUNTIME + "Undefined", "UNDEFINED", "L" + HirEmitter.RUNTIME + "Undefined;");
                init.visitFieldInsn(PUTFIELD, name, shape.member(i), "Ljava/lang/Object;");
            }
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        if (!javaInterfaces.isEmpty()) {
            MethodVisitor lookupMv = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, "$babble$lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
            lookupMv.visitCode();
            lookupMv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
            lookupMv.visitInsn(ARETURN);
            lookupMv.visitMaxs(0, 0);
            lookupMv.visitEnd();
        }
        memberTable("$babble$field", "(I)Ljava/lang/Object;", false);
        memberTable("$babble$setField", "(ILjava/lang/Object;)V", true);
        if (primDefCount > 0) {
            isMemberDefinedMethod();
        }
        emitEqualsMethod();
        if (shape.isValue()) {
            emitRecordHashCode();
        }
        emitToStringMethod();
    }

    void emitInterfaceBridges() {
        ScopeAnalysis.TypeHierarchyInfo hierarchy = emitter.module.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Map<String, HirEmitter.AncestorMethod> ancestors = emitter.collectAncestorMethods(hierarchy);
        for (HirEmitter.AncestorMethod anc : ancestors.values()) {
            String memberName = anc.memberName();
            ScopeAnalysis.FunctionSignature sig = anc.signature();
            int[] paramSlots = HirEmitter.bridgeParamSlots(sig);
            int ifaceCount = sig.parameterKinds().length;
            MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC,
                    HirEmitter.bridgeMethodName(anc.bridgeKey()), anc.descriptor(), null, null);
            m.visitCode();
            int ownIdx = shape.memberIndex(memberName);
            boolean ownOverload = ownIdx >= 0 && shape.isMethod(ownIdx) && cls.overloadMethodFunctionIds().containsKey(memberName);
            if (ownIdx >= 0 && shape.isMethod(ownIdx)) {
                Integer altIdx = ownOverload ? hierarchy.babbleBridgeOverloads().get(anc.bridgeKey()) : null;
                HirFunction fn = ownOverload
                        ? (altIdx != null ? overloadMethodFunction(memberName, altIdx) : null)
                        : methodFunction(memberName);
                if (fn != null) {
                    int fnCount = fn.params().size();
                    String jvmMethod = fn.jvmMethodName();
                    m.visitVarInsn(ALOAD, 0);
                    m.visitInsn(DUP);
                    m.visitFieldInsn(GETFIELD, name, memberName, "Ljava/lang/Object;");
                    m.visitLdcInsn(memberName);
                    m.visitMethodInsn(INVOKESTATIC, HirEmitter.CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                    if (ownOverload) {
                        ConstantPool.pushInt(m, altIdx);
                        m.visitMethodInsn(INVOKESTATIC, HirEmitter.OVERLOAD_SET, "memberOf",
                                "(Ljava/lang/Object;I)L" + HirEmitter.CALLABLE + ";", false);
                    }
                    m.visitTypeInsn(CHECKCAST, HirEmitter.COMPILED_METHOD);
                    m.visitVarInsn(ALOAD, 1);
                    if (fn.hasSpec() && fn.restIndex() == sig.restIndex() && ifaceCount <= fnCount && HirEmitter.allOptionalFrom(fn, ifaceCount)) {
                        List<HirParam> params = fn.params();
                        for (int i = 0; i < ifaceCount; i++) {
                            HirParam param = params.get(i);
                            HirEmitter.emitAdaptBridgeArg(m, sig, paramSlots, i, param.kind(), param.wideningRank());
                        }
                        for (int i = ifaceCount; i < fnCount; i++) {
                            HirEmitter.emitDefaultValueOn(m, params.get(i).kind());
                        }
                        HirEmitter.emitRemapBridgeMask(m, sig, fn, ifaceCount);
                        m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod + "$spec", HirEmitter.methodSpecDescriptor(fn), false);
                        HirEmitter.emitAdaptKind(m, fn.returnKind(), sig.returnKind());
                        m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
                    } else if (fn.isSpread() && fn.restIndex() == sig.restIndex() && ifaceCount <= fnCount) {
                        m.visitInsn(ACONST_NULL);
                        for (int i = 0; i < ifaceCount; i++) {
                            HirEmitter.emitLoadBridgeArgBoxed(m, sig, paramSlots, i);
                        }
                        for (int i = ifaceCount; i < fnCount; i++) {
                            m.visitFieldInsn(GETSTATIC, HirEmitter.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                        }
                        m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod, HirEmitter.methodDirectDescriptor(fn), false);
                        HirEmitter.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
                    } else {
                        String siteField = emitter.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
                        HirEmitter.packBridgeArgsArray(m, sig, paramSlots);
                        if (sig.restIndex() >= 0 && sig.restIndex() < ifaceCount) {
                            m.visitInsn(DUP);
                            ConstantPool.pushInt(m, sig.restIndex());
                            m.visitInsn(DUP2);
                            m.visitInsn(AALOAD);
                            m.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                            m.visitMethodInsn(INVOKESTATIC, HirEmitter.SPREADS, "packedRest", "(Ljava/lang/Iterable;)Ljava/lang/Object;", false);
                            m.visitInsn(AASTORE);
                        }
                        m.visitInsn(ACONST_NULL);
                        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
                        m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod + (fn.isSpread() ? "$call" : ""), HirEmitter.METHOD_DESCRIPTOR, false);
                        HirEmitter.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                        m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
                    }
                } else {
                    emitBridgeDynamicFallback(m, memberName, altIdx, anc.signatureKey(), sig, paramSlots, ifaceCount);
                }
            } else if (ownIdx >= 0) {
                emitBridgeDynamicFallback(m, memberName, null, anc.signatureKey(), sig, paramSlots, ifaceCount);
            } else {
                HirEmitter.InheritedDefault def = emitter.findInheritedDefault(hierarchy, memberName);
                String directParentJvm = def != null ? emitter.allInterfaceJvmNames.get(def.directParentId()) : null;
                emitter.emitInheritedDefaultInvoke(m, def, directParentJvm, memberName, anc.bridgeKey(), anc.signatureKey(), anc.descriptor(), sig);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        if (!javaInterfaces.isEmpty()) {
            emitJavaInterfaceBridges(hierarchy, ancestors);
        }
    }

    private void emitBridgeDynamicFallback(MethodVisitor m, String memberName, Integer altIdx, String sigKey,
                                           ScopeAnalysis.FunctionSignature sig, int[] paramSlots, int ifaceCount) {
        String siteField = emitter.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(memberName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.BABBLE_OBJECTS, "getMember", "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;", false);
        if (altIdx != null) {
            ConstantPool.pushInt(m, altIdx);
            m.visitMethodInsn(INVOKESTATIC, HirEmitter.OVERLOAD_SET, "memberOf",
                    "(Ljava/lang/Object;I)L" + HirEmitter.CALLABLE + ";", false);
        } else if (sigKey != null) {
            m.visitLdcInsn(sigKey);
            m.visitMethodInsn(INVOKESTATIC, HirEmitter.OVERLOAD_SET, "selectFrom",
                    "(Ljava/lang/Object;Ljava/lang/String;)L" + HirEmitter.CALLABLE + ";", false);
        } else {
            m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
            m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + HirEmitter.CALL_SITE + ";)L" + HirEmitter.CALLABLE + ";", false);
        }
        m.visitVarInsn(ALOAD, 1);
        HirEmitter.packBridgeArgsArray(m, sig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, HirEmitter.CALLABLE, "call",
                "(L" + HirEmitter.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + HirEmitter.CALL_SITE + ";)Ljava/lang/Object;", true);
        HirEmitter.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
        m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
    }

    private void emitJavaInterfaceBridges(ScopeAnalysis.TypeHierarchyInfo hierarchy,
                                          Map<String, HirEmitter.AncestorMethod> babbleAncestors) {
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
            candidates.sort(Comparator
                    .comparing(Method::isBridge)
                    .thenComparing(m -> m.getDeclaringClass().getName()));
            Method jm = candidates.get(0);
            String mName = jm.getName();
            int ownIdx = shape.memberIndex(mName);
            boolean ownMethod = ownIdx >= 0 && shape.isMethod(ownIdx);
            boolean ownOverload = ownMethod && cls.overloadMethodFunctionIds().containsKey(mName);
            HirEmitter.InheritedDefault inheritedBabbleDefault = ownIdx < 0 ? emitter.findInheritedDefault(hierarchy, mName) : null;
            boolean hasBabbleImpl = ownMethod || inheritedBabbleDefault != null;
            boolean hasActiveJavaDefault = hasActiveJavaDefault(candidates);
            Integer overloadAltIdx = hierarchy != null ? hierarchy.javaBridgeOverloads().get(key) : null;
            if (!hasBabbleImpl) {
                if (hasActiveJavaDefault) {
                    continue;
                }
            } else if (hasActiveJavaDefault && ownOverload && overloadAltIdx == null) {
                continue;
            } else if (hasActiveJavaDefault && ownMethod && !ownOverload
                    && !acceptsJavaDefaultOverload(methodFunction(mName), jm.getParameterTypes())) {
                continue;
            } else if (hasActiveJavaDefault && !ownMethod && inheritedBabbleDefault != null
                    && inheritedBabbleDefault.providerSpec().isOverloaded() && overloadAltIdx == null) {
                continue;
            }
            emitJavaMethodBridge(jm, ownMethod, ownOverload, overloadAltIdx, inheritedBabbleDefault, babbleAncestors.get(mName));
        }
    }

    private static boolean hasActiveJavaDefault(List<Method> candidates) {
        for (Method cand : candidates) {
            if (cand.isDefault()) {
                boolean reabstracted = false;
                for (Method other : candidates) {
                    if (!other.isDefault()
                            && other.getDeclaringClass() != cand.getDeclaringClass()
                            && cand.getDeclaringClass().isAssignableFrom(other.getDeclaringClass())) {
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

    private void emitJavaMethodBridge(Method jm, boolean ownMethod, boolean ownOverload, Integer overloadAltIdx,
                                      HirEmitter.InheritedDefault inheritedBabbleDefault,
                                      HirEmitter.AncestorMethod babbleAnc) {
        String mName = jm.getName();
        String jvmDesc = Type.getMethodDescriptor(jm);
        Class<?>[] paramTypes = jm.getParameterTypes();
        Class<?> retType = jm.getReturnType();
        int access = ACC_PUBLIC | ACC_SYNTHETIC | (jm.isBridge() ? ACC_BRIDGE : 0);
        MethodVisitor m = writer.visitMethod(access, mName, jvmDesc, null, null);
        m.visitCode();

        if (!ownMethod && inheritedBabbleDefault == null) {
            m.visitTypeInsn(NEW, HirEmitter.RUNTIME + "BabbleException");
            m.visitInsn(DUP);
            ConstantPool.pushString(m, "No implementation of " + JavaTypes.signature(jm));
            m.visitMethodInsn(INVOKESPECIAL, HirEmitter.RUNTIME + "BabbleException", "<init>", "(Ljava/lang/String;)V", false);
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
        m.visitFieldInsn(GETFIELD, name, "$babble$rt", "L" + HirEmitter.SCRIPT_RUNTIME + ";");
        m.visitVarInsn(ASTORE, rtSlot);
        String descText = shape.name() + "." + mName;

        if (ownMethod) {
            HirFunction fn = ownOverload
                    ? (overloadAltIdx != null ? overloadMethodFunction(mName, overloadAltIdx) : null)
                    : methodFunction(mName);
            if (fn != null) {
                int fnCount = fn.params().size();
                String jvmMethod = fn.jvmMethodName();
                m.visitVarInsn(ALOAD, 0);
                m.visitInsn(DUP);
                m.visitFieldInsn(GETFIELD, name, mName, "Ljava/lang/Object;");
                m.visitLdcInsn(mName);
                m.visitMethodInsn(INVOKESTATIC, HirEmitter.CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                if (ownOverload) {
                    ConstantPool.pushInt(m, overloadAltIdx);
                    m.visitMethodInsn(INVOKESTATIC, HirEmitter.OVERLOAD_SET, "memberOf",
                            "(Ljava/lang/Object;I)L" + HirEmitter.CALLABLE + ";", false);
                }
                m.visitTypeInsn(CHECKCAST, HirEmitter.COMPILED_METHOD);
                m.visitVarInsn(ALOAD, rtSlot);
                if (fn.hasSpec() && fn.restIndex() < 0 && paramTypes.length <= fnCount && HirEmitter.allOptionalFrom(fn, paramTypes.length)) {
                    List<HirParam> params = fn.params();
                    for (int i = 0; i < paramTypes.length; i++) {
                        HirParam param = params.get(i);
                        HirEmitter.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], param.kind(), param.wideningRank(), param.checkedType());
                    }
                    for (int i = paramTypes.length; i < fnCount; i++) {
                        HirEmitter.emitDefaultValueOn(m, params.get(i).kind());
                    }
                    if (fn.hasOptionalMask()) {
                        ConstantPool.pushInt(m, org.babblelang.runtime.Arguments.remapMask(0, fn.optionalParamBits(), paramTypes.length));
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod + "$spec", HirEmitter.methodSpecDescriptor(fn), false);
                    HirEmitter.emitReturnToJava(m, fn.returnKind(), retType, descText, rtSlot);
                } else if (fn.isSpread() && fn.restIndex() < 0 && paramTypes.length <= fnCount) {
                    List<HirParam> params = fn.params();
                    m.visitInsn(ACONST_NULL);
                    for (int i = 0; i < paramTypes.length; i++) {
                        HirEmitter.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ScopeAnalysis.PrimitiveKind.NONE, -1, params.get(i).checkedType());
                    }
                    for (int i = paramTypes.length; i < fnCount; i++) {
                        m.visitFieldInsn(GETSTATIC, HirEmitter.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod, HirEmitter.methodDirectDescriptor(fn), false);
                    HirEmitter.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
                } else {
                    String siteField = emitter.constantPool.javaCallSiteField(mName, paramTypes.length);
                    HirEmitter.emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
                    if (jm.isVarArgs() && fn.restIndex() == paramTypes.length - 1 && paramTypes.length > 0) {
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
                        m.visitMethodInsn(INVOKESTATIC, HirEmitter.ARGUMENTS_VIEW, "fromArray",
                                "(Ljava/lang/Object;)L" + HirEmitter.ARGUMENTS_VIEW + ";", false);
                        m.visitMethodInsn(INVOKESTATIC, HirEmitter.SPREADS, "packedRest",
                                "(Ljava/lang/Iterable;)Ljava/lang/Object;", false);
                        m.visitInsn(AASTORE);
                        m.visitLabel(skipWrap);
                    }
                    m.visitInsn(ACONST_NULL);
                    m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
                    m.visitMethodInsn(INVOKEVIRTUAL, name, jvmMethod + (fn.isSpread() ? "$call" : ""), HirEmitter.METHOD_DESCRIPTOR, false);
                    HirEmitter.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
                }
                m.visitMaxs(0, 0);
                m.visitEnd();
                return;
            }
        }

        if (!ownMethod && inheritedBabbleDefault != null && babbleAnc != null
                && !inheritedBabbleDefault.providerSpec().isOverloaded()
                && babbleAnc.signature().parameterKinds().length == paramTypes.length) {
            ScopeAnalysis.FunctionSignature ancSig = babbleAnc.signature();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, rtSlot);
            for (int i = 0; i < paramTypes.length; i++) {
                HirEmitter.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ancSig.parameterKinds()[i], -1, null);
            }
            if (ancSig.hasOptionalMask()) {
                m.visitInsn(ICONST_0);
            }
            m.visitMethodInsn(INVOKEVIRTUAL, name, HirEmitter.bridgeMethodName(mName), babbleAnc.descriptor(), false);
            HirEmitter.emitReturnToJava(m, ancSig.returnKind(), retType, descText, rtSlot);
            m.visitMaxs(0, 0);
            m.visitEnd();
            return;
        }

        String siteField = emitter.constantPool.javaCallSiteField(mName, paramTypes.length);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(mName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.BABBLE_OBJECTS, "getMember", "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;", false);
        if (overloadAltIdx != null) {
            ConstantPool.pushInt(m, overloadAltIdx);
            m.visitMethodInsn(INVOKESTATIC, HirEmitter.OVERLOAD_SET, "memberOf",
                    "(Ljava/lang/Object;I)L" + HirEmitter.CALLABLE + ";", false);
        } else {
            m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
            m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + HirEmitter.CALL_SITE + ";)L" + HirEmitter.CALLABLE + ";", false);
        }
        m.visitVarInsn(ALOAD, rtSlot);
        HirEmitter.emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, HirEmitter.CALLABLE, "call",
                "(L" + HirEmitter.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + HirEmitter.CALL_SITE + ";)Ljava/lang/Object;", true);
        HirEmitter.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
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

    void emitCheckDefined(MethodVisitor m, int instanceLocal, int memberIndex, String memberName, int lineNumber) {
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
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Members", "usedBeforeDefinition", "(Ljava/lang/String;)L" + HirEmitter.RUNTIME + "BabbleException;", false);
        m.visitInsn(ATHROW);
        m.visitLabel(ok);
    }

    private void isMemberDefinedMethod() {
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, "$babble$isDefined", "(I)Z", null, null);
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

    private void memberTable(String method, String descriptor, boolean set) {
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, method, descriptor, null, null);
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
                String desc = HirEmitter.jvmDescriptor(mk);
                if (set) {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitVarInsn(ALOAD, 2);
                    switch (mk) {
                        case BYTE -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                        }
                        case SHORT -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Number");
                            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
                        }
                        case INT -> {
                            m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
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
                        HirEmitter.boxOn(m, mk);
                        m.visitInsn(ARETURN);
                        m.visitLabel(undef);
                        m.visitFieldInsn(GETSTATIC, HirEmitter.RUNTIME + "Undefined", "UNDEFINED", "L" + HirEmitter.RUNTIME + "Undefined;");
                        m.visitInsn(ARETURN);
                    } else {
                        m.visitVarInsn(ALOAD, 0);
                        m.visitFieldInsn(GETFIELD, name, shape.member(i), desc);
                        HirEmitter.boxOn(m, mk);
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
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Values", "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
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
        m.visitFieldInsn(GETFIELD, name, "$babble$class", "L" + HirEmitter.BABBLE_CLASS + ";");
        m.visitVarInsn(ALOAD, 2);
        m.visitFieldInsn(GETFIELD, name, "$babble$class", "L" + HirEmitter.BABBLE_CLASS + ";");
        m.visitJumpInsn(IF_ACMPNE, falseLabel);

        for (int i = 0; i < shape.memberCount(); i++) {
            if (shape.isMethod(i)) {
                continue;
            }
            if (primDefBits[i] >= 0) {
                m.visitVarInsn(ALOAD, 0);
                ConstantPool.pushInt(m, i);
                m.visitMethodInsn(INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitVarInsn(ALOAD, 2);
                ConstantPool.pushInt(m, i);
                m.visitMethodInsn(INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Operators", "equal", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                m.visitJumpInsn(IFEQ, falseLabel);
                continue;
            }
            String member = shape.member(i);
            ScopeAnalysis.PrimitiveKind mk = memberKinds[i];
            String desc = HirEmitter.jvmDescriptor(mk);
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
                    m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Operators", "equal", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
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
                m.visitMethodInsn(INVOKEVIRTUAL, name, "$babble$field", "(I)Ljava/lang/Object;", false);
                m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Values", "hash", "(Ljava/lang/Object;)I", false);
                m.visitInsn(IADD);
                continue;
            }
            ScopeAnalysis.PrimitiveKind mk = memberKinds[i];
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, name, shape.member(i), HirEmitter.jvmDescriptor(mk));
            switch (mk) {
                case BYTE, SHORT, INT -> {
                    m.visitInsn(I2L);
                    m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
                }
                case LONG -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
                case BOOL -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "hashCode", "(Z)I", false);
                case CHAR -> m.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "hashCode", "(C)I", false);
                case FLOAT -> {
                    m.visitInsn(F2D);
                    m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Values", "hash", "(D)I", false);
                }
                case DOUBLE -> m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Values", "hash", "(D)I", false);
                case NONE -> m.visitMethodInsn(INVOKESTATIC, HirEmitter.RUNTIME + "Values", "hash", "(Ljava/lang/Object;)I", false);
            }
            m.visitInsn(IADD);
        }
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private void emitToStringMethod() {
        MethodVisitor m = writer.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.BABBLE_OBJECTS, "toString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
