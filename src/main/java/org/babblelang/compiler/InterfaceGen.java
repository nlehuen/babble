package org.babblelang.compiler;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * ANTLR-free JVM interface generator for a {@link HirInterface} (RM003-P2, detached in BIP014-P1).
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
        this.writer = new ScriptClassWriter(emitter.runtimeLoader, emitter.className,
                emitter.generatedClassParents, emitter.generatedInterfaceParents);
        this.writer.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC,
                name, null, HirEmitter.OBJECT, extended.length == 0 ? null : extended);
        this.writer.visitSource(emitter.sourceName, null);
    }

    private HirFunction defaultFunction(String member) {
        Integer fnId = iface.defaultFunctionIds().get(member);
        return fnId != null ? emitter.module.function(fnId) : null;
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

    void emitBody() {
        ScopeAnalysis.TypeHierarchyInfo hierarchy = emitter.module.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Set<String> emitted = new HashSet<>();
        for (ScopeAnalysis.MemberSpec spec : hierarchy.interfaceMembers().values()) {
            if (!spec.callable() || spec.declaredSignature() == null) {
                continue;
            }
            ScopeAnalysis.FunctionSignature sig = spec.declaredSignature();
            String mName = HirEmitter.bridgeMethodName(spec.name());
            String desc = HirEmitter.bridgeDescriptor(sig);
            emitted.add(mName + desc);
            if (!spec.hasDefault()) {
                writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, mName, desc, null, null).visitEnd();
            } else {
                MethodVisitor m = writer.visitMethod(ACC_PUBLIC, mName, desc, null, null);
                m.visitCode();
                emitOwnDefaultInvoke(m, shape.memberIndex(spec.name()), spec.name(), defaultFunction(spec.name()), sig);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
        }

        Map<String, HirEmitter.AncestorMethod> ancestors = emitter.collectAncestorMethods(hierarchy);
        for (HirEmitter.AncestorMethod anc : ancestors.values()) {
            String mName = HirEmitter.bridgeMethodName(anc.memberName());
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
                        int[] paramSlots = HirEmitter.bridgeParamSlots(anc.signature());
                        m.visitVarInsn(ALOAD, 0);
                        m.visitVarInsn(ALOAD, 1);
                        for (int i = 0; i < anc.signature().parameterKinds().length; i++) {
                            HirEmitter.emitAdaptBridgeArg(m, anc.signature(), paramSlots, i, ownSig.parameterKinds()[i], -1);
                        }
                        if (ownSig.hasOptionalMask()) {
                            if (anc.signature().hasOptionalMask()) {
                                m.visitVarInsn(ILOAD, HirEmitter.bridgeMaskSlot(anc.signature()));
                            } else {
                                m.visitInsn(ICONST_0);
                            }
                        }
                        m.visitMethodInsn(INVOKEINTERFACE, name, mName, HirEmitter.bridgeDescriptor(ownSig), true);
                        HirEmitter.emitAdaptKind(m, ownSig.returnKind(), anc.signature().returnKind());
                        m.visitInsn(HirEmitter.returnInsn(anc.signature().returnKind()));
                    } else {
                        emitOwnDefaultInvoke(m, shape.memberIndex(anc.memberName()), anc.memberName(),
                                defaultFunction(anc.memberName()), anc.signature());
                    }
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            } else {
                HirEmitter.InheritedDefault def = emitter.findInheritedDefault(hierarchy, anc.memberName());
                if (def == null) {
                    continue;
                }
                String directParentJvm = emitter.allInterfaceJvmNames.get(def.directParentId());
                if (directParentJvm == null) {
                    continue;
                }
                ScopeAnalysis.FunctionSignature provSig = def.providerSpec().callable() ? def.providerSpec().declaredSignature() : null;
                String provDesc = provSig != null ? HirEmitter.bridgeDescriptor(provSig) : null;
                int parentsWithSig = emitter.countDirectParentsWithMethod(hierarchy, anc.memberName(), anc.descriptor());
                if (parentsWithSig >= 2 || !anc.descriptor().equals(provDesc)) {
                    emitted.add(mName + anc.descriptor());
                    MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, mName, anc.descriptor(), null, null);
                    m.visitCode();
                    emitter.emitInheritedDefaultInvoke(m, def, directParentJvm, anc.memberName(), anc.descriptor(), anc.signature());
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            }
        }
    }

    private void emitOwnDefaultInvoke(MethodVisitor m, int memberIndex, String memberName,
                                      HirFunction fn, ScopeAnalysis.FunctionSignature sig) {
        int[] paramSlots = HirEmitter.bridgeParamSlots(sig);
        int ifaceCount = sig.parameterKinds().length;
        int fnCount = fn != null ? fn.params().size() : -1;
        if (fn != null) {
            emitter.ensureFunctionScheduled(fn);
        }

        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(shape.id());
        ConstantPool.pushInt(m, memberIndex);
        m.visitMethodInsn(INVOKESTATIC, HirEmitter.CLASSES, "interfaceDefault",
                "(Ljava/lang/Object;Ljava/lang/String;I)L" + HirEmitter.RUNTIME + "BabbleFunction;", false);

        if (fn != null && !fn.isCompiledClassMethod() && fn.hasSpec()
                && ifaceCount <= fnCount && HirEmitter.allOptionalFrom(fn, ifaceCount)) {
            List<HirParam> params = fn.params();
            m.visitTypeInsn(CHECKCAST, HirEmitter.COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < ifaceCount; i++) {
                HirParam param = params.get(i);
                HirEmitter.emitAdaptBridgeArg(m, sig, paramSlots, i, param.kind(), param.wideningRank());
            }
            for (int i = ifaceCount; i < fnCount; i++) {
                HirEmitter.emitDefaultValueOn(m, params.get(i).kind());
            }
            HirEmitter.emitRemapBridgeMask(m, sig, fn, ifaceCount);
            m.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName() + "$spec",
                    HirEmitter.functionSpecDescriptor(fn), false);
            HirEmitter.emitAdaptKind(m, fn.returnKind(), sig.returnKind());
            m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
            return;
        }
        if (fn != null && !fn.isCompiledClassMethod() && fn.isSpread() && ifaceCount <= fnCount) {
            m.visitTypeInsn(CHECKCAST, HirEmitter.COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitInsn(ACONST_NULL);
            for (int i = 0; i < ifaceCount; i++) {
                HirEmitter.emitLoadBridgeArgBoxed(m, sig, paramSlots, i);
            }
            for (int i = ifaceCount; i < fnCount; i++) {
                m.visitFieldInsn(GETSTATIC, HirEmitter.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            }
            m.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName(),
                    HirEmitter.functionDirectDescriptor(fn), false);
            HirEmitter.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
            m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
            return;
        }
        String siteField = emitter.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
        m.visitTypeInsn(CHECKCAST, HirEmitter.COMPILED_FUNCTION);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        HirEmitter.packBridgeArgsArray(m, sig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + HirEmitter.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, HirEmitter.COMPILED_FUNCTION, "invoke",
                "(Ljava/lang/Object;L" + HirEmitter.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + HirEmitter.CALL_SITE + ";)Ljava/lang/Object;", false);
        HirEmitter.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
        m.visitInsn(HirEmitter.returnInsn(sig.returnKind()));
    }
}
