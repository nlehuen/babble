package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * ANTLR-free JVM interface generator for a {@link HirInterface} (RM003-P2, detached in BIP014-I1).
 */
final class InterfaceGen {
    private final HirEmitter emitter;
    final HirInterface iface;
    final String name;
    final ClassShape shape;
    final ClassWriter writer;
    final Map<String, String> superHelpers = new HashMap<>();

    InterfaceGen(HirEmitter emitter, HirInterface iface) {
        this.emitter = emitter;
        this.iface = iface;
        this.name = iface.jvmName();
        this.shape = iface.shape();
        String[] extended = iface.extendedJvmInterfaces().toArray(new String[0]);
        this.writer =
                new ScriptClassWriter(
                        emitter.runtimeLoader,
                        emitter.className,
                        emitter.generatedClassParents,
                        emitter.generatedInterfaceParents);
        this.writer.visit(
                V21,
                ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC,
                name,
                null,
                OBJECT,
                extended.length == 0 ? null : extended);
        this.writer.visitSource(emitter.sourceName, null);
    }

    private HirFunction defaultFunction(String member) {
        Integer fnId = iface.defaultFunctionIds().get(member);
        return fnId != null ? emitter.module.function(fnId) : null;
    }

    private HirFunction defaultOverloadFunction(String member, int altIndex) {
        List<Integer> fnIds = iface.defaultOverloadFunctionIds().get(member);
        if (fnIds == null || altIndex < 0 || altIndex >= fnIds.size()) {
            return null;
        }
        int fnId = fnIds.get(altIndex);
        return fnId >= 0 ? emitter.module.function(fnId) : null;
    }

    String superHelper(
            String directParentJvm, String memberName, LoweringTargets.FunctionSignature sig) {
        return emitter.bridges.superHelper(
                writer, superHelpers, ACC_PUBLIC | ACC_SYNTHETIC, directParentJvm, memberName, sig);
    }

    void emitBody() {
        LoweringTargets.TypeHierarchyInfo hierarchy = emitter.module.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Set<String> emitted = new HashSet<>();
        for (LoweringTargets.MemberSpec spec : hierarchy.interfaceMembers().values()) {
            if (spec.callable() && spec.declaredSignature() != null) {
                LoweringTargets.FunctionSignature sig = spec.declaredSignature();
                String mName = bridgeMethodName(spec.name());
                String desc = bridgeDescriptor(sig);
                emitted.add(mName + desc);
                if (!spec.hasDefault()) {
                    writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, mName, desc, null, null)
                            .visitEnd();
                } else {
                    MethodVisitor m = writer.visitMethod(ACC_PUBLIC, mName, desc, null, null);
                    m.visitCode();
                    emitOwnDefaultInvoke(
                            m,
                            shape.memberIndex(spec.name()),
                            spec.name(),
                            -1,
                            defaultFunction(spec.name()),
                            sig);
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            } else if (spec.isOverloaded()) {
                List<LoweringTargets.FunctionSignature> sigs = spec.overloadSignatures();
                for (int k = 0; k < sigs.size(); k++) {
                    LoweringTargets.FunctionSignature sig = sigs.get(k);
                    String mName = bridgeMethodName(spec.name() + "$" + k);
                    String desc = bridgeDescriptor(sig);
                    emitted.add(mName + desc);
                    if (!spec.hasDefault()) {
                        writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, mName, desc, null, null)
                                .visitEnd();
                    } else {
                        MethodVisitor m = writer.visitMethod(ACC_PUBLIC, mName, desc, null, null);
                        m.visitCode();
                        emitOwnDefaultInvoke(
                                m,
                                shape.memberIndex(spec.name()),
                                spec.name(),
                                k,
                                defaultOverloadFunction(spec.name(), k),
                                sig);
                        m.visitMaxs(0, 0);
                        m.visitEnd();
                    }
                }
            }
        }

        Map<String, BridgeHelpers.AncestorMethod> ancestors =
                emitter.bridges.collectAncestorMethods(hierarchy);
        for (BridgeHelpers.AncestorMethod anc : ancestors.values()) {
            String mName = bridgeMethodName(anc.bridgeKey());
            if (emitted.contains(mName + anc.descriptor())) {
                continue;
            }
            LoweringTargets.MemberSpec ownSpec = hierarchy.interfaceMembers().get(anc.memberName());
            if (ownSpec != null) {
                emitted.add(mName + anc.descriptor());
                if (!ownSpec.hasDefault()) {
                    writer.visitMethod(
                                    ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                                    mName,
                                    anc.descriptor(),
                                    null,
                                    null)
                            .visitEnd();
                } else if (ownSpec.isOverloaded()) {
                    MethodVisitor m =
                            writer.visitMethod(
                                    ACC_PUBLIC | ACC_SYNTHETIC,
                                    mName,
                                    anc.descriptor(),
                                    null,
                                    null);
                    m.visitCode();
                    int altIdx =
                            anc.overloadIndex() >= 0
                                            && anc.overloadIndex()
                                                    < ownSpec.overloadSignatures().size()
                                    ? anc.overloadIndex()
                                    : 0;
                    emitOwnDefaultInvoke(
                            m,
                            shape.memberIndex(anc.memberName()),
                            anc.memberName(),
                            altIdx,
                            defaultOverloadFunction(anc.memberName(), altIdx),
                            anc.signature());
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                } else {
                    MethodVisitor m =
                            writer.visitMethod(
                                    ACC_PUBLIC | ACC_SYNTHETIC,
                                    mName,
                                    anc.descriptor(),
                                    null,
                                    null);
                    m.visitCode();
                    LoweringTargets.FunctionSignature ownSig =
                            ownSpec.callable() ? ownSpec.declaredSignature() : null;
                    if (ownSig != null
                            && ownSig.parameterKinds().length
                                    == anc.signature().parameterKinds().length) {
                        int[] paramSlots = bridgeParamSlots(anc.signature());
                        m.visitVarInsn(ALOAD, 0);
                        m.visitVarInsn(ALOAD, 1);
                        for (int i = 0; i < anc.signature().parameterKinds().length; i++) {
                            emitAdaptBridgeArg(
                                    m,
                                    anc.signature(),
                                    paramSlots,
                                    i,
                                    ownSig.parameterKinds()[i],
                                    -1);
                        }
                        if (ownSig.hasOptionalMask()) {
                            if (anc.signature().hasOptionalMask()) {
                                m.visitVarInsn(ILOAD, bridgeMaskSlot(anc.signature()));
                            } else {
                                m.visitInsn(ICONST_0);
                            }
                        }
                        m.visitMethodInsn(
                                INVOKEINTERFACE,
                                name,
                                bridgeMethodName(anc.memberName()),
                                bridgeDescriptor(ownSig),
                                true);
                        emitAdaptKind(m, ownSig.returnKind(), anc.signature().returnKind());
                        m.visitInsn(returnInsn(anc.signature().returnKind()));
                    } else {
                        emitOwnDefaultInvoke(
                                m,
                                shape.memberIndex(anc.memberName()),
                                anc.memberName(),
                                -1,
                                defaultFunction(anc.memberName()),
                                anc.signature());
                    }
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            } else {
                BridgeHelpers.InheritedDefault def =
                        emitter.bridges.findInheritedDefault(hierarchy, anc.memberName());
                if (def == null) {
                    continue;
                }
                String directParentJvm = emitter.allInterfaceJvmNames.get(def.directParentId());
                if (directParentJvm == null) {
                    continue;
                }
                LoweringTargets.FunctionSignature provSig =
                        def.providerSpec().callable()
                                ? def.providerSpec().declaredSignature()
                                : null;
                if (provSig == null
                        && def.providerSpec().isOverloaded()
                        && anc.overloadIndex() >= 0
                        && anc.overloadIndex() < def.providerSpec().overloadSignatures().size()) {
                    provSig = def.providerSpec().overloadSignatures().get(anc.overloadIndex());
                }
                String provDesc = provSig != null ? bridgeDescriptor(provSig) : null;
                int parentsWithSig =
                        emitter.bridges.countDirectParentsWithMethod(
                                hierarchy, anc.bridgeKey(), anc.descriptor());
                if (parentsWithSig >= 2 || !anc.descriptor().equals(provDesc)) {
                    emitted.add(mName + anc.descriptor());
                    MethodVisitor m =
                            writer.visitMethod(
                                    ACC_PUBLIC | ACC_SYNTHETIC,
                                    mName,
                                    anc.descriptor(),
                                    null,
                                    null);
                    m.visitCode();
                    emitter.bridges.emitInheritedDefaultInvoke(
                            m,
                            def,
                            directParentJvm,
                            anc.memberName(),
                            anc.bridgeKey(),
                            anc.signatureKey(),
                            anc.descriptor(),
                            anc.signature());
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            }
        }
    }

    private void emitOwnDefaultInvoke(
            MethodVisitor m,
            int memberIndex,
            String memberName,
            int overloadAltIndex,
            HirFunction fn,
            LoweringTargets.FunctionSignature sig) {
        int[] paramSlots = bridgeParamSlots(sig);
        int ifaceCount = sig.parameterKinds().length;
        int fnCount = fn != null ? fn.params().size() : -1;
        if (fn != null) {
            emitter.ensureFunctionScheduled(fn);
        }

        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(shape.id());
        ConstantPool.pushInt(m, memberIndex);
        if (overloadAltIndex >= 0) {
            ConstantPool.pushInt(m, overloadAltIndex);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    CLASSES,
                    "interfaceDefaultAlt",
                    "(Ljava/lang/Object;Ljava/lang/String;II)L" + RUNTIME + "BabbleFunction;",
                    false);
        } else {
            m.visitMethodInsn(
                    INVOKESTATIC,
                    CLASSES,
                    "interfaceDefault",
                    "(Ljava/lang/Object;Ljava/lang/String;I)L" + RUNTIME + "BabbleFunction;",
                    false);
        }

        if (fn != null
                && !fn.isCompiledClassMethod()
                && fn.hasSpec()
                && ifaceCount <= fnCount
                && allOptionalFrom(fn, ifaceCount)) {
            List<HirParam> params = fn.params();
            m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < ifaceCount; i++) {
                HirParam param = params.get(i);
                emitAdaptBridgeArg(m, sig, paramSlots, i, param.kind(), param.wideningRank());
            }
            for (int i = ifaceCount; i < fnCount; i++) {
                emitDefaultValueOn(m, params.get(i).kind());
            }
            emitRemapBridgeMask(m, sig, fn, ifaceCount);
            m.visitMethodInsn(
                    INVOKESTATIC,
                    emitter.className,
                    fn.jvmMethodName() + "$spec",
                    functionSpecDescriptor(fn),
                    false);
            emitAdaptKind(m, fn.returnKind(), sig.returnKind());
            m.visitInsn(returnInsn(sig.returnKind()));
            return;
        }
        if (fn != null && !fn.isCompiledClassMethod() && fn.isSpread() && ifaceCount <= fnCount) {
            m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitInsn(ACONST_NULL);
            for (int i = 0; i < ifaceCount; i++) {
                emitLoadBridgeArgBoxed(m, sig, paramSlots, i);
            }
            for (int i = ifaceCount; i < fnCount; i++) {
                m.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            }
            m.visitMethodInsn(
                    INVOKESTATIC,
                    emitter.className,
                    fn.jvmMethodName(),
                    functionDirectDescriptor(fn),
                    false);
            emitAdaptKind(m, PrimitiveKind.NONE, sig.returnKind());
            m.visitInsn(returnInsn(sig.returnKind()));
            return;
        }
        m.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        packBridgeArgsArray(m, sig, paramSlots);
        m.visitInsn(ACONST_NULL);
        emitter.constantPool.emitSyntheticCallSite(m, shape.name() + "." + memberName, ifaceCount);
        m.visitMethodInsn(
                INVOKEVIRTUAL,
                COMPILED_FUNCTION,
                "invoke",
                "(Ljava/lang/Object;L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/Object;[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";)Ljava/lang/Object;",
                false);
        emitAdaptKind(m, PrimitiveKind.NONE, sig.returnKind());
        m.visitInsn(returnInsn(sig.returnKind()));
    }
}
