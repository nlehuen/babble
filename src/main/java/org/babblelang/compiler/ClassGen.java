package org.babblelang.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.objectweb.asm.Opcodes.*;

/**
 * The JVM class generated for a class or object expression (compiler phase 2, extracted in BIP014-P0) :
 * it extends {@code BabbleInstance} with one field per member, plus a hidden field per variable
 * of the constructor that a nested function captures, holding its cell. Its methods are JVM
 * instance methods.
 */
final class ClassGen {
    private final CodeGenerator cg;
    final ParserRuleContext ctx;
    final String name;
    final ClassShape shape;
    final boolean arrayBacked;
    final String[] implementedInterfaces;
    final List<Class<?>> javaInterfaces;
    final ClassWriter writer;
    final List<Resolution.Variable> hiddenCells = new ArrayList<>();
    final ScopeAnalysis.PrimitiveKind[] memberKinds;
    final int[] primDefBits;
    final int primDefCount;
    final Map<String, String> superHelpers = new HashMap<>();

    ClassGen(CodeGenerator cg, ParserRuleContext ctx, String name, ClassShape shape, boolean arrayBacked,
             String[] implementedInterfaces, List<Class<?>> javaInterfaces) {
        this.cg = cg;
        this.ctx = ctx;
        this.name = name;
        this.shape = shape;
        this.arrayBacked = arrayBacked;
        this.implementedInterfaces = implementedInterfaces;
        this.javaInterfaces = javaInterfaces;
        this.writer = new ScriptClassWriter(cg.runtimeLoader, cg.className, cg.generatedClassParents, cg.generatedInterfaceParents);
        if (arrayBacked) {
            this.memberKinds = new ScopeAnalysis.PrimitiveKind[0];
            this.primDefBits = new int[0];
            this.primDefCount = 0;
            return;
        }
        for (Resolution.Variable variable : cg.resolution.declared(ctx)) {
            if (variable.isCell()) {
                hiddenCells.add(variable);
            }
        }
        int count = shape.memberCount();
        this.memberKinds = new ScopeAnalysis.PrimitiveKind[count];
        this.primDefBits = new int[count];
        Arrays.fill(this.memberKinds, ScopeAnalysis.PrimitiveKind.NONE);
        Arrays.fill(this.primDefBits, -1);
        if (ctx instanceof BabbleParser.ClassExpressionContext c && c.classParameters() != null) {
            for (int p = 0; p < shape.parameters().length; p++) {
                int idx = shape.parameterMember(p);
                if (idx >= 0 && !shape.isMethod(idx)) {
                    this.memberKinds[idx] = cg.scopes.declarationKind(c.classParameters().classParam(p));
                }
            }
        }
        BabbleParser.BlockContext body = ctx instanceof BabbleParser.ClassExpressionContext c
                ? c.classBlock : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
        int bits = 0;
        for (BabbleParser.ExpressionContext expr : body.expression()) {
            if (expr instanceof BabbleParser.DefExpressionContext def) {
                ScopeAnalysis.DefTarget dt = cg.scopes.defTarget(def);
                if (dt.kind() == ScopeAnalysis.DefKind.MEMBER) {
                    int idx = dt.index();
                    if (!shape.isMethod(idx)) {
                        ScopeAnalysis.PrimitiveKind pk = cg.scopes.declarationKind(def);
                        this.memberKinds[idx] = pk;
                        if (pk.isPrimitive()) {
                            this.primDefBits[idx] = bits++;
                        }
                    }
                }
            }
        }
        this.primDefCount = bits;
    }

    String methodName(String member) {
        return "m$" + member;
    }

    /** The literal of a method member of the class. */
    BabbleParser.FunctionLiteralContext methodLiteral(String member) {
        BabbleParser.BlockContext body = ctx instanceof BabbleParser.ClassExpressionContext c
                ? c.classBlock : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
        for (BabbleParser.ExpressionContext expression : body.expression()) {
            if (expression instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(member)
                    && def.value instanceof BabbleParser.FunctionLiteralContext literal) {
                return literal;
            }
        }
        throw new IllegalStateException("No method " + member + " in " + name);
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

    /** Fields, the constructor, and the member table ; the methods are generated as they come. */
    void begin() {
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, name, null, CodeGenerator.BABBLE_INSTANCE,
                implementedInterfaces.length == 0 ? null : implementedInterfaces);
        writer.visitSource(cg.sourceName, null);
        if (!javaInterfaces.isEmpty()) {
            MethodVisitor lookupMv = writer.visitMethod(ACC_PROTECTED | ACC_SYNTHETIC, "javaDefaultLookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
            lookupMv.visitCode();
            lookupMv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
            lookupMv.visitInsn(ARETURN);
            lookupMv.visitMaxs(0, 0);
            lookupMv.visitEnd();
        }
        if (arrayBacked) {
            MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + CodeGenerator.BABBLE_CLASS + ";L" + CodeGenerator.SCRIPT_RUNTIME + ";)V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitVarInsn(ALOAD, 2);
            init.visitInsn(ICONST_1);
            init.visitMethodInsn(INVOKESPECIAL, CodeGenerator.BABBLE_INSTANCE, "<init>", "(L" + CodeGenerator.BABBLE_CLASS + ";L" + CodeGenerator.SCRIPT_RUNTIME + ";Z)V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            return;
        }
        for (int i = 0; i < shape.memberCount(); i++) {
            writer.visitField(0, shape.member(i), CodeGenerator.jvmDescriptor(memberKinds[i]), null, null).visitEnd();
        }
        for (Resolution.Variable variable : hiddenCells) {
            writer.visitField(0, CodeGenerator.hiddenField(variable), "Ljava/lang/Object;", null, null).visitEnd();
        }
        int definedWords = (primDefCount + 31) / 32;
        for (int w = 0; w < definedWords; w++) {
            writer.visitField(0, "$defined$" + w, "I", null, null).visitEnd();
        }

        MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", "(L" + CodeGenerator.BABBLE_CLASS + ";L" + CodeGenerator.SCRIPT_RUNTIME + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitInsn(ICONST_0);
        init.visitMethodInsn(INVOKESPECIAL, CodeGenerator.BABBLE_INSTANCE, "<init>", "(L" + CodeGenerator.BABBLE_CLASS + ";L" + CodeGenerator.SCRIPT_RUNTIME + ";Z)V", false);
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!memberKinds[i].isPrimitive()) {
                init.visitVarInsn(ALOAD, 0);
                init.visitFieldInsn(GETSTATIC, CodeGenerator.RUNTIME + "Undefined", "UNDEFINED", "L" + CodeGenerator.RUNTIME + "Undefined;");
                init.visitFieldInsn(PUTFIELD, name, shape.member(i), "Ljava/lang/Object;");
            }
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        memberTable("field", "(I)Ljava/lang/Object;", false);
        memberTable("setField", "(ILjava/lang/Object;)V", true);
        if (primDefCount > 0) {
            isMemberDefinedMethod();
        }
    }

    void emitInterfaceBridges() {
        ScopeAnalysis.TypeHierarchyInfo hierarchy = cg.scopes.typeHierarchy(shape.id());
        if (hierarchy == null) {
            return;
        }
        Map<String, CodeGenerator.AncestorMethod> ancestors = cg.collectAncestorMethods(hierarchy);
        for (CodeGenerator.AncestorMethod anc : ancestors.values()) {
            String memberName = anc.memberName();
            ScopeAnalysis.FunctionSignature sig = anc.signature();
            int[] paramSlots = CodeGenerator.bridgeParamSlots(sig);
            int ifaceCount = sig.parameterKinds().length;
            MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_SYNTHETIC, CodeGenerator.bridgeMethodName(memberName), anc.descriptor(), null, null);
            m.visitCode();
            int ownIdx = shape.memberIndex(memberName);
            if (ownIdx >= 0 && !arrayBacked && shape.isMethod(ownIdx)) {
                BabbleParser.FunctionLiteralContext lit = methodLiteral(memberName);
                int litCount = cg.scopes.parameters(lit).length;
                m.visitVarInsn(ALOAD, 0);
                m.visitInsn(DUP);
                m.visitFieldInsn(GETFIELD, name, memberName, "Ljava/lang/Object;");
                m.visitLdcInsn(memberName);
                m.visitMethodInsn(INVOKESTATIC, CodeGenerator.CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                m.visitTypeInsn(CHECKCAST, CodeGenerator.COMPILED_METHOD);
                m.visitVarInsn(ALOAD, 1);
                if (cg.hasSpec(lit) && ifaceCount == litCount) {
                    List<BabbleParser.FnParamContext> decls = lit.fnParameters().fnParam();
                    for (int i = 0; i < ifaceCount; i++) {
                        ScopeAnalysis.PrimitiveKind fromK = sig.parameterKinds()[i];
                        ScopeAnalysis.PrimitiveKind toK = cg.scopes.declarationKind(decls.get(i));
                        CodeGenerator.loadKindOn(m, fromK, paramSlots[i]);
                        CodeGenerator.emitAdaptKind(m, fromK, toK);
                        if (toK == ScopeAnalysis.PrimitiveKind.NONE) {
                            CodeGenerator.emitWidenBoxed(m, cg.scopes.numericWideningRank(decls.get(i)));
                        }
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName) + "$spec", cg.methodSpecDescriptor(lit), false);
                    CodeGenerator.emitAdaptKind(m, cg.scopes.functionReturnKind(lit), sig.returnKind());
                    m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
                } else if (cg.isSpread(lit) && ifaceCount <= litCount) {
                    m.visitInsn(ACONST_NULL);
                    for (int i = 0; i < ifaceCount; i++) {
                        ScopeAnalysis.PrimitiveKind pk = sig.parameterKinds()[i];
                        CodeGenerator.loadKindOn(m, pk, paramSlots[i]);
                        CodeGenerator.boxOn(m, pk);
                    }
                    for (int i = ifaceCount; i < litCount; i++) {
                        m.visitFieldInsn(GETSTATIC, CodeGenerator.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                    }
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName), cg.methodDirectDescriptor(lit), false);
                    CodeGenerator.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                    m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
                } else {
                    String siteField = cg.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
                    CodeGenerator.packBridgeArgsArray(m, sig, paramSlots);
                    m.visitInsn(ACONST_NULL);
                    m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
                    m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(memberName) + (cg.isSpread(lit) ? "$call" : ""), CodeGenerator.METHOD_DESCRIPTOR, false);
                    CodeGenerator.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                    m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
                }
            } else if (ownIdx >= 0) {
                String siteField = cg.constantPool.syntheticCallSiteField(shape.name() + "." + memberName, ifaceCount);
                m.visitVarInsn(ALOAD, 0);
                m.visitLdcInsn(memberName);
                m.visitInsn(ICONST_0);
                m.visitMethodInsn(INVOKEVIRTUAL, CodeGenerator.BABBLE_INSTANCE, "getMember", "(Ljava/lang/String;Z)Ljava/lang/Object;", false);
                m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
                m.visitMethodInsn(INVOKESTATIC, CodeGenerator.RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CodeGenerator.CALL_SITE + ";)L" + CodeGenerator.CALLABLE + ";", false);
                m.visitVarInsn(ALOAD, 1);
                CodeGenerator.packBridgeArgsArray(m, sig, paramSlots);
                m.visitInsn(ACONST_NULL);
                m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
                m.visitMethodInsn(INVOKEINTERFACE, CodeGenerator.CALLABLE, "call",
                        "(L" + CodeGenerator.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CodeGenerator.CALL_SITE + ";)Ljava/lang/Object;", true);
                CodeGenerator.emitAdaptKind(m, ScopeAnalysis.PrimitiveKind.NONE, sig.returnKind());
                m.visitInsn(CodeGenerator.returnInsn(sig.returnKind()));
            } else {
                CodeGenerator.InheritedDefault def = cg.findInheritedDefault(hierarchy, memberName);
                String directParentJvm = def != null ? cg.allInterfaceJvmNames.get(def.directParentId()) : null;
                cg.emitInheritedDefaultInvoke(m, def, directParentJvm, memberName, anc.descriptor(), sig);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        if (!javaInterfaces.isEmpty()) {
            emitJavaInterfaceBridges(hierarchy, ancestors);
        }
    }

    private void emitJavaInterfaceBridges(ScopeAnalysis.TypeHierarchyInfo hierarchy,
                                          Map<String, CodeGenerator.AncestorMethod> babbleAncestors) {
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
        for (List<Method> candidates : grouped.values()) {
            candidates.sort(Comparator
                    .comparing(Method::isBridge)
                    .thenComparing(m -> m.getDeclaringClass().getName()));
            Method jm = candidates.get(0);
            String mName = jm.getName();
            int ownIdx = shape.memberIndex(mName);
            boolean ownMethod = ownIdx >= 0 && shape.isMethod(ownIdx);
            CodeGenerator.InheritedDefault inheritedBabbleDefault = ownIdx < 0 ? cg.findInheritedDefault(hierarchy, mName) : null;
            boolean hasBabbleImpl = ownMethod || inheritedBabbleDefault != null;
            if (!hasBabbleImpl) {
                boolean hasActiveJavaDefault = false;
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
                            hasActiveJavaDefault = true;
                            break;
                        }
                    }
                }
                if (hasActiveJavaDefault) {
                    continue;
                }
            }
            emitJavaMethodBridge(jm, ownMethod, inheritedBabbleDefault, babbleAncestors.get(mName));
        }
    }

    private void emitJavaMethodBridge(Method jm, boolean ownMethod,
                                      CodeGenerator.InheritedDefault inheritedBabbleDefault,
                                      CodeGenerator.AncestorMethod babbleAnc) {
        String mName = jm.getName();
        String jvmDesc = Type.getMethodDescriptor(jm);
        Class<?>[] paramTypes = jm.getParameterTypes();
        Class<?> retType = jm.getReturnType();
        int access = ACC_PUBLIC | ACC_SYNTHETIC | (jm.isBridge() ? ACC_BRIDGE : 0);
        MethodVisitor m = writer.visitMethod(access, mName, jvmDesc, null, null);
        m.visitCode();

        if (!ownMethod && inheritedBabbleDefault == null) {
            m.visitTypeInsn(NEW, CodeGenerator.RUNTIME + "BabbleException");
            m.visitInsn(DUP);
            ConstantPool.pushString(m, "No implementation of " + JavaTypes.signature(jm));
            m.visitMethodInsn(INVOKESPECIAL, CodeGenerator.RUNTIME + "BabbleException", "<init>", "(Ljava/lang/String;)V", false);
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
        m.visitMethodInsn(INVOKEVIRTUAL, CodeGenerator.BABBLE_INSTANCE, "runtime", "()L" + CodeGenerator.SCRIPT_RUNTIME + ";", false);
        m.visitVarInsn(ASTORE, rtSlot);
        String descText = shape.name() + "." + mName;

        if (ownMethod && !arrayBacked) {
            BabbleParser.FunctionLiteralContext lit = methodLiteral(mName);
            int litCount = cg.scopes.parameters(lit).length;
            m.visitVarInsn(ALOAD, 0);
            m.visitInsn(DUP);
            m.visitFieldInsn(GETFIELD, name, mName, "Ljava/lang/Object;");
            m.visitLdcInsn(mName);
            m.visitMethodInsn(INVOKESTATIC, CodeGenerator.CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            m.visitTypeInsn(CHECKCAST, CodeGenerator.COMPILED_METHOD);
            m.visitVarInsn(ALOAD, rtSlot);
            if (cg.hasSpec(lit) && paramTypes.length == litCount) {
                List<BabbleParser.FnParamContext> decls = lit.fnParameters().fnParam();
                for (int i = 0; i < paramTypes.length; i++) {
                    BabbleParser.FnParamContext decl = decls.get(i);
                    ScopeAnalysis.PrimitiveKind toK = cg.scopes.declarationKind(decl);
                    cg.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], toK, cg.scopes.numericWideningRank(decl), cg.scopes.checkedType(decl));
                }
                m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName) + "$spec", cg.methodSpecDescriptor(lit), false);
                cg.emitReturnToJava(m, cg.scopes.functionReturnKind(lit), retType, descText, rtSlot);
            } else if (cg.isSpread(lit) && paramTypes.length <= litCount) {
                m.visitInsn(ACONST_NULL);
                for (int i = 0; i < paramTypes.length; i++) {
                    cg.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ScopeAnalysis.PrimitiveKind.NONE, -1, null);
                }
                for (int i = paramTypes.length; i < litCount; i++) {
                    m.visitFieldInsn(GETSTATIC, CodeGenerator.ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
                m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName), cg.methodDirectDescriptor(lit), false);
                cg.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
            } else {
                String siteField = cg.constantPool.javaCallSiteField(mName, paramTypes.length);
                CodeGenerator.emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
                m.visitInsn(ACONST_NULL);
                m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
                m.visitMethodInsn(INVOKEVIRTUAL, name, methodName(mName) + (cg.isSpread(lit) ? "$call" : ""), CodeGenerator.METHOD_DESCRIPTOR, false);
                cg.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
            }
            m.visitMaxs(0, 0);
            m.visitEnd();
            return;
        }

        if (inheritedBabbleDefault != null && babbleAnc != null
                && babbleAnc.signature().parameterKinds().length == paramTypes.length) {
            ScopeAnalysis.FunctionSignature ancSig = babbleAnc.signature();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ALOAD, rtSlot);
            for (int i = 0; i < paramTypes.length; i++) {
                cg.emitLoadJavaBridgeArg(m, paramTypes[i], javaParamSlots[i], ancSig.parameterKinds()[i], -1, null);
            }
            m.visitMethodInsn(INVOKEVIRTUAL, name, CodeGenerator.bridgeMethodName(mName), babbleAnc.descriptor(), false);
            cg.emitReturnToJava(m, ancSig.returnKind(), retType, descText, rtSlot);
            m.visitMaxs(0, 0);
            m.visitEnd();
            return;
        }

        String siteField = cg.constantPool.javaCallSiteField(mName, paramTypes.length);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(mName);
        m.visitInsn(ICONST_0);
        m.visitMethodInsn(INVOKEVIRTUAL, CodeGenerator.BABBLE_INSTANCE, "getMember", "(Ljava/lang/String;Z)Ljava/lang/Object;", false);
        m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
        m.visitMethodInsn(INVOKESTATIC, CodeGenerator.RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CodeGenerator.CALL_SITE + ";)L" + CodeGenerator.CALLABLE + ";", false);
        m.visitVarInsn(ALOAD, rtSlot);
        CodeGenerator.emitPackJavaBridgeArgs(m, paramTypes, javaParamSlots);
        m.visitInsn(ACONST_NULL);
        m.visitFieldInsn(GETSTATIC, cg.className, siteField, "L" + CodeGenerator.CALL_SITE + ";");
        m.visitMethodInsn(INVOKEINTERFACE, CodeGenerator.CALLABLE, "call",
                "(L" + CodeGenerator.SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CodeGenerator.CALL_SITE + ";)Ljava/lang/Object;", true);
        cg.emitReturnToJava(m, ScopeAnalysis.PrimitiveKind.NONE, retType, descText, rtSlot);
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

    void emitCheckDefined(MethodVisitor m, int instanceLocal, int memberIndex, String memberName, ParserRuleContext at) {
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
        cg.line(at);
        m.visitLdcInsn(memberName);
        m.visitMethodInsn(INVOKESTATIC, CodeGenerator.RUNTIME + "Members", "usedBeforeDefinition", "(Ljava/lang/String;)L" + CodeGenerator.RUNTIME + "BabbleException;", false);
        m.visitInsn(ATHROW);
        m.visitLabel(ok);
    }

    private void isMemberDefinedMethod() {
        MethodVisitor m = writer.visitMethod(ACC_PROTECTED, "isMemberDefined", "(I)Z", null, null);
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

    /** {@code field(int)} or {@code setField(int, Object)} : a switch over the member fields. */
    private void memberTable(String method, String descriptor, boolean set) {
        MethodVisitor m = writer.visitMethod(ACC_PROTECTED, method, descriptor, null, null);
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
                String desc = CodeGenerator.jvmDescriptor(mk);
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
                        CodeGenerator.boxOn(m, mk);
                        m.visitInsn(ARETURN);
                        m.visitLabel(undef);
                        m.visitFieldInsn(GETSTATIC, CodeGenerator.RUNTIME + "Undefined", "UNDEFINED", "L" + CodeGenerator.RUNTIME + "Undefined;");
                        m.visitInsn(ARETURN);
                    } else {
                        m.visitVarInsn(ALOAD, 0);
                        m.visitFieldInsn(GETFIELD, name, shape.member(i), desc);
                        CodeGenerator.boxOn(m, mk);
                        m.visitInsn(ARETURN);
                    }
                }
            }
        }
        m.visitLabel(fallback);
        m.visitTypeInsn(NEW, "java/lang/IndexOutOfBoundsException");
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/IndexOutOfBoundsException", "<init>", "()V", false);
        m.visitInsn(ATHROW);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
