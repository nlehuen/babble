package org.babblelang.compiler;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleParser;
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
 * The JVM interface generated for a Babble {@code interface} expression (RM003-P2, extracted in BIP014-P0).
 */
final class InterfaceGen {
    private final CodeGenerator cg;
    final BabbleParser.InterfaceExpressionContext ctx;
    final String name;
    final ClassShape shape;
    final ClassWriter writer;
    final Map<String, String> superHelpers = new HashMap<>();

    InterfaceGen(CodeGenerator cg, BabbleParser.InterfaceExpressionContext ctx, String name,
                 ClassShape shape, String[] extendedInterfaces) {
        this.cg = cg;
        this.ctx = ctx;
        this.name = name;
        this.shape = shape;
        this.writer = new ScriptClassWriter(cg.runtimeLoader, cg.className, cg.generatedClassParents, cg.generatedInterfaceParents);
        this.writer.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC,
                name, null, CodeGenerator.OBJECT, extendedInterfaces.length == 0 ? null : extendedInterfaces);
        this.writer.visitSource(cg.sourceName, null);
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
        String desc = CodeGenerator.bridgeDescriptor(sig);
        String key = directParentJvm + "." + memberName + desc;
        return superHelpers.computeIfAbsent(key, k -> {
            String helperName = "$super$" + cg.nextSuperHelper();
            MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, helperName, desc, null, null);
            m.visitCode();
            int[] paramSlots = CodeGenerator.bridgeParamSlots(sig);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < sig.parameterKinds().length; i++) {
                CodeGenerator.loadKindOn(m, sig.parameterKinds()[i], paramSlots[i]);
            }
            m.visitMethodInsn(INVOKESPECIAL, directParentJvm, CodeGenerator.bridgeMethodName(memberName), desc, true);
            m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
            m.visitMaxs(0, 0);
            m.visitEnd();
            return helperName;
        });
    }

    void emitBody() {
        ScopeAnalysis.TypeHierarchyInfo hierarchy = cg.scopes.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Set<String> emitted = new HashSet<>();
        for (ScopeAnalysis.MemberSpec spec : hierarchy.interfaceMembers().values()) {
            if (!spec.callable() || spec.declaredSignature() == null) {
                continue;
            }
            ScopeAnalysis.FunctionSignature sig = spec.declaredSignature();
            String mName = CodeGenerator.bridgeMethodName(spec.name());
            String desc = CodeGenerator.bridgeDescriptor(sig);
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

        Map<String, CodeGenerator.AncestorMethod> ancestors = cg.collectAncestorMethods(hierarchy);
        for (CodeGenerator.AncestorMethod anc : ancestors.values()) {
            String mName = CodeGenerator.bridgeMethodName(anc.memberName());
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
                        int[] paramSlots = CodeGenerator.bridgeParamSlots(anc.signature());
                        m.visitVarInsn(ALOAD, 0);
                        m.visitVarInsn(ALOAD, 1);
                        for (int i = 0; i < anc.signature().parameterKinds().length; i++) {
                            ScopeAnalysis.PrimitiveKind fromK = anc.signature().parameterKinds()[i];
                            ScopeAnalysis.PrimitiveKind toK = ownSig.parameterKinds()[i];
                            CodeGenerator.loadKindOn(m, fromK, paramSlots[i]);
                            CodeGenerator.emitAdaptKind(m, fromK, toK);
                        }
                        m.visitMethodInsn(INVOKEINTERFACE, name, mName, CodeGenerator.bridgeDescriptor(ownSig), true);
                        CodeGenerator.emitAdaptKind(m, ownSig.returnKind(), anc.signature().returnKind());
                        m.visitInsn(CodeGenerator.returnInsn(anc.signature().returnKind()));
                    } else {
                        emitOwnDefaultInvoke(m, shape.memberIndex(anc.memberName()), anc.memberName(),
                                defaultLiteral(anc.memberName()), anc.signature());
                    }
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            } else {
                CodeGenerator.InheritedDefault def = cg.findInheritedDefault(hierarchy, anc.memberName());
                if (def == null) {
                    continue;
                }
                String directParentJvm = cg.allInterfaceJvmNames.get(def.directParentId());
                if (directParentJvm == null) {
                    continue;
                }
                ScopeAnalysis.FunctionSignature provSig = def.providerSpec().callable() ? def.providerSpec().declaredSignature() : null;
                String provDesc = provSig != null ? CodeGenerator.bridgeDescriptor(provSig) : null;
                int parentsWithSig = cg.countDirectParentsWithMethod(hierarchy, anc.memberName(), anc.descriptor());
                if (parentsWithSig >= 2 || !anc.descriptor().equals(provDesc)) {
                    emitted.add(mName + anc.descriptor());
                    MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, mName, anc.descriptor(), null, null);
                    m.visitCode();
                    cg.emitInheritedDefaultInvoke(m, def, directParentJvm, anc.memberName(), anc.descriptor(), anc.signature());
                    m.visitMaxs(0, 0);
                    m.visitEnd();
                }
            }
        }
    }

    private void emitOwnDefaultInvoke(MethodVisitor m, int memberIndex, String memberName,
                                      BabbleParser.FunctionLiteralContext lit, ScopeAnalysis.FunctionSignature sig) {
        int[] paramSlots = CodeGenerator.bridgeParamSlots(sig);
        int ifaceCount = sig.parameterKinds().length;
        String fnMethod = lit != null ? cg.functionMethodNames.get(lit) : null;
        int litCount = lit != null ? cg.scopes.parameters(lit).length : -1;

        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(shape.id());
        ConstantPool.pushInt(m, memberIndex);
        m.visitMethodInsn(INVOKESTATIC, CodeGenerator.CLASSES, "interfaceDefault",
                "(Ljava/lang/Object;Ljava/lang/String;I)L" + CodeGenerator.RUNTIME + "BabbleFunction;", false);

        if (fnMethod != null && cg.hasSpec(lit) && ifaceCount == litCount) {
            List<BabbleParser.FnParamContext> decls = lit.fnParameters().fnParam();
            m.visitTypeInsn(CHECKCAST, CodeGenerator.COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            for (int i = 0; i < ifaceCount; i++) {
                ScopeAnalysis.PrimitiveKind fromK = sig.parameterKinds()[i];
                ScopeAnalysis.PrimitiveKind toK = cg.scopes.declarationKind(decls.get(i));
                CodeGenerator.loadKindOn(m, fromK, paramSlots[i]);
                CodeGenerator.emitAdaptKind(m, fromK, toK);
                if (toK == ScopeAnalysis.PrimitiveKind.NONE) {
                    CodeGenerator.emitWidenBoxed(m, cg.scopes.numericWideningRank(decls.get(i)));
                }
            }
            m.visitMethodInsn(INVOKESTATIC, cg.className, fnMethod + "$spec", cg.functionSpecDescriptor(lit), false);
            CodeGenerator.emitAdaptKind(m, cg.scopes.functionReturnKind(lit), sig.returnKind());
            m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
            return;
        }
        if (fnMethod != null && cg.isSpread(lit) && ifaceCount <= litCount) {
            m.visitTypeInsn(CHECKCAST, CodeGenerator.COMPILED_FUNCTION);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, 1);
            m.visitInsn(ACONST_NULL);
            for (int i = 0; i < ifaceCount; i++) {
                ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[i];
                CodeGenerator.loadKindOn(m, pk, paramSlots[i]);
                CodeGenerator.boxOn(m, pk);
            }
            for (int i = ifaceCount; i < litCount; i++) {
                m.visitFieldInsn(GETSTATIC, CodeGenerator.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            }
            m.visitMethodInsn(INVOKESTATIC, cg.className, fnMethod, cg.functionDirectDescriptor(lit), false);
            CodeGenerator.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
            m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
            return;
        }
        String siteField = cg.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
        m.visitTypeInsn(CHECKCAST, CodeGenerator.COMPILED_FUNCTION);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        CodeGenerator.packBridgeArgsArray(m, sig, paramSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, CodeGenerator.COMPILED_FUNCTION, "invoke",
                "(Ljava/lang/Object;L" + CodeGenerator.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CodeGenerator.CALL_SITE + ";)Ljava/lang/Object;", false);
        CodeGenerator.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
        m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
    }
}
