package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirModule;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Shared interface hierarchy inspection, inherited-default invocation, and {@code $super$N}
 * helper generation for {@link ClassGen} and {@link InterfaceGen} (BIP027-I2).
 */
final class BridgeHelpers {
    record AncestorMethod(String memberName, String bridgeKey, int overloadIndex, String signatureKey,
                          String descriptor, LoweringTargets.FunctionSignature signature) {
    }

    record InheritedDefault(String directParentId, String providerId, LoweringTargets.MemberSpec providerSpec) {
    }

    private final HirEmitter emitter;
    private final HirModule module;
    private final Map<String, String> allInterfaceJvmNames;

    BridgeHelpers(HirEmitter emitter) {
        this.emitter = emitter;
        this.module = emitter.module;
        this.allInterfaceJvmNames = emitter.allInterfaceJvmNames;
    }

    /**
     * Emits (or retrieves from {@code cache}) a synthetic {@code $super$N} method on
     * {@code writer} that forwards directly via {@code INVOKESPECIAL} to
     * {@code directParentJvm.bm$<memberName>}. Shared by {@link ClassGen} and {@link InterfaceGen}.
     */
    String superHelper(ClassWriter writer, Map<String, String> cache, int access,
                       String directParentJvm, String memberName, LoweringTargets.FunctionSignature sig) {
        String desc = JvmPrimitives.bridgeDescriptor(sig);
        String key = directParentJvm + "." + memberName + desc;
        return cache.computeIfAbsent(key, k -> {
            String helperName = "$super$" + emitter.nextSuperHelper();
            MethodVisitor m = writer.visitMethod(access, helperName, desc, null, null);
            m.visitCode();
            int[] paramSlots = JvmPrimitives.bridgeParamSlots(sig);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < sig.parameterKinds().length; i++) {
                JvmPrimitives.loadKindOn(m, sig.parameterKinds()[i], paramSlots[i]);
            }
            if (sig.hasOptionalMask()) {
                m.visitVarInsn(ILOAD, JvmPrimitives.bridgeMaskSlot(sig));
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, JvmPrimitives.bridgeMethodName(memberName), desc, true);
            m.visitInsn(JvmPrimitives.returnInsn(sig.returnKind()));
            m.visitMaxs(0, 0);
            m.visitEnd();
            return helperName;
        });
    }

    InheritedDefault findInheritedDefault(LoweringTargets.TypeHierarchyInfo ownerHierarchy, String memberName) {
        if (ownerHierarchy == null) {
            return null;
        }
        for (LoweringTargets.ParentRef parent : ownerHierarchy.directParents()) {
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
        LoweringTargets.TypeHierarchyInfo info = module.typeHierarchy(ifaceId);
        if (info == null) {
            return null;
        }
        LoweringTargets.MemberSpec own = info.interfaceMembers().get(memberName);
        if (own != null) {
            return own.hasDefault() ? new InheritedDefault(ifaceId, ifaceId, own) : null;
        }
        for (LoweringTargets.ParentRef parent : info.directParents()) {
            if (parent.babbleTypeId() != null) {
                InheritedDefault found = findDefaultInInterface(parent.babbleTypeId(), memberName, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean specHasMethod(LoweringTargets.MemberSpec spec, String bridgeKey, String descriptor) {
        if (spec == null) {
            return false;
        }
        if (spec.name().equals(bridgeKey) && spec.callable() && spec.declaredSignature() != null
                && JvmPrimitives.bridgeDescriptor(spec.declaredSignature()).equals(descriptor)) {
            return true;
        }
        if (spec.isOverloaded() && bridgeKey.startsWith(spec.name() + "$")) {
            try {
                int idx = Integer.parseInt(bridgeKey.substring(spec.name().length() + 1));
                if (idx >= 0 && idx < spec.overloadSignatures().size()
                        && JvmPrimitives.bridgeDescriptor(spec.overloadSignatures().get(idx)).equals(descriptor)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    boolean interfaceHasMethod(String ifaceId, String bridgeKey, String descriptor) {
        if (ifaceId == null || !allInterfaceJvmNames.containsKey(ifaceId)) {
            return false;
        }
        LoweringTargets.TypeHierarchyInfo info = module.typeHierarchy(ifaceId);
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
            LoweringTargets.TypeHierarchyInfo ancInfo = module.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            if (specHasMethod(ancInfo.interfaceMembers().get(memberName), bridgeKey, descriptor)) {
                return true;
            }
        }
        return false;
    }

    int countDirectParentsWithMethod(LoweringTargets.TypeHierarchyInfo hierarchy, String bridgeKey, String descriptor) {
        if (hierarchy == null) {
            return 0;
        }
        int count = 0;
        for (LoweringTargets.ParentRef parent : hierarchy.directParents()) {
            if (parent.babbleTypeId() != null && interfaceHasMethod(parent.babbleTypeId(), bridgeKey, descriptor)) {
                count++;
            }
        }
        return count;
    }

    Map<String, AncestorMethod> collectAncestorMethods(LoweringTargets.TypeHierarchyInfo hierarchy) {
        Map<String, AncestorMethod> result = new LinkedHashMap<>();
        if (hierarchy == null) {
            return result;
        }
        for (String ancId : hierarchy.allBabbleInterfaceIds()) {
            if (!allInterfaceJvmNames.containsKey(ancId)) {
                continue;
            }
            LoweringTargets.TypeHierarchyInfo ancInfo = module.typeHierarchy(ancId);
            if (ancInfo == null) {
                continue;
            }
            for (LoweringTargets.MemberSpec ancSpec : ancInfo.interfaceMembers().values()) {
                if (ancSpec.callable() && ancSpec.declaredSignature() != null) {
                    String desc = JvmPrimitives.bridgeDescriptor(ancSpec.declaredSignature());
                    result.putIfAbsent(ancSpec.name() + "\0" + desc,
                            new AncestorMethod(ancSpec.name(), ancSpec.name(), -1, null, desc, ancSpec.declaredSignature()));
                } else if (ancSpec.isOverloaded()) {
                    List<LoweringTargets.FunctionSignature> sigs = ancSpec.overloadSignatures();
                    List<String> keys = ancSpec.overloadSignatureKeys();
                    for (int k = 0; k < sigs.size(); k++) {
                        LoweringTargets.FunctionSignature sig = sigs.get(k);
                        String bKey = ancSpec.name() + "$" + k;
                        String desc = JvmPrimitives.bridgeDescriptor(sig);
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
                                    String targetDesc, LoweringTargets.FunctionSignature targetSig) {
        int[] paramSlots = JvmPrimitives.bridgeParamSlots(targetSig);
        int argCount = targetSig.parameterKinds().length;
        String mName = JvmPrimitives.bridgeMethodName(bridgeKey);
        if (directParentJvm != null && interfaceHasMethod(def.directParentId(), bridgeKey, targetDesc)) {
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < argCount; i++) {
                JvmPrimitives.loadKindOn(m, targetSig.parameterKinds()[i], paramSlots[i]);
            }
            if (targetSig.hasOptionalMask()) {
                m.visitVarInsn(ILOAD, JvmPrimitives.bridgeMaskSlot(targetSig));
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, mName, targetDesc, true);
            m.visitInsn(JvmPrimitives.returnInsn(targetSig.returnKind()));
            return;
        }
        LoweringTargets.FunctionSignature provSig = def != null && def.providerSpec().callable() ? def.providerSpec().declaredSignature() : null;
        String provDesc = provSig != null ? JvmPrimitives.bridgeDescriptor(provSig) : null;
        if (directParentJvm != null && provDesc != null
                && provSig.parameterKinds().length == argCount
                && interfaceHasMethod(def.directParentId(), memberName, provDesc)) {
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < argCount; i++) {
                JvmPrimitives.emitAdaptBridgeArg(m, targetSig, paramSlots, i, provSig.parameterKinds()[i], -1);
            }
            if (provSig.hasOptionalMask()) {
                if (targetSig.hasOptionalMask()) {
                    m.visitVarInsn(ILOAD, JvmPrimitives.bridgeMaskSlot(targetSig));
                } else {
                    m.visitInsn(ICONST_0);
                }
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, JvmPrimitives.bridgeMethodName(memberName), provDesc, true);
            JvmPrimitives.emitAdaptKind(m, provSig.returnKind(), targetSig.returnKind());
            m.visitInsn(JvmPrimitives.returnInsn(targetSig.returnKind()));
            return;
        }
        String siteField = emitter.constantPool.syntheticCallSiteField(memberName, argCount);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(memberName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKESTATIC, JvmPrimitives.BABBLE_OBJECTS, "getMember", "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;", false);
        if (signatureKey != null) {
            m.visitLdcInsn(signatureKey);
            m.visitMethodInsn(INVOKESTATIC, JvmPrimitives.OVERLOAD_SET, "selectFrom", "(Ljava/lang/Object;Ljava/lang/String;)L" + JvmPrimitives.CALLABLE + ";", false);
        } else {
            m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + JvmPrimitives.CALL_SITE + ";");
            m.visitMethodInsn(INVOKESTATIC, JvmPrimitives.RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + JvmPrimitives.CALL_SITE + ";)L" + JvmPrimitives.CALLABLE + ";", false);
        }
        m.visitVarInsn(ALOAD, 1);
        JvmPrimitives.packBridgeArgsArray(m, targetSig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + JvmPrimitives.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, JvmPrimitives.CALLABLE, "call",
                "(L" + JvmPrimitives.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + JvmPrimitives.CALL_SITE + ";)Ljava/lang/Object;", true);
        JvmPrimitives.emitAdaptKind(m, PrimitiveKind.NONE, targetSig.returnKind());
        m.visitInsn(JvmPrimitives.returnInsn(targetSig.returnKind()));
    }
}
