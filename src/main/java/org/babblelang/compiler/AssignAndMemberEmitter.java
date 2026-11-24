package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bytecode emission for definitions, variable/field assignments, compound assignments,
 * member selection, and array creation/operations, extracted from {@link HirEmitter}
 * (BIP027-I2).
 */
final class AssignAndMemberEmitter {
    private final HirEmitter emitter;

    AssignAndMemberEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    // ---------------------------------------------------------------- defs & assignments

    private void defValueAs(HirExpr.Def d, PrimitiveKind desired) {
        MethodEmitContext c = ctx();
        if (d.value() != null) {
            emitter.emitExpr(d.value());
        } else {
            c.mv.visitInsn(ACONST_NULL);
            c.stackKind = PrimitiveKind.NONE;
        }
        emitter.widenStackRank(d.meta().wideningRank());
        emitter.convertStack(c.stackKind, desired);
    }

    void emitDef(HirExpr.Def d) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        LoweringTargets.DefTarget target = d.target();
        if (target.kind() == LoweringTargets.DefKind.REQUIRED) {
            mv.visitInsn(ACONST_NULL);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (d.var() != null) {
            PrimitiveKind vk = d.var().primitiveKind();
            defValueAs(d, vk);
            emitter.storeVariable(d.var(), true, vk, d.meta().line());
            return;
        }
        if (target.kind() == LoweringTargets.DefKind.MEMBER) {
            if (c.constructorClass == null) {
                throw new IllegalStateException("Unexpected MEMBER def outside compiled class : " + d.name());
            }
            int idx = target.index();
            PrimitiveKind mk = c.constructorClass.memberKinds[idx];
            if (mk.isPrimitive()) {
                int tmp = c.newLocal(mk);
                defValueAs(d, mk);
                storeKindOn(mv, mk, tmp);
                mv.visitVarInsn(ALOAD, c.typedThisLocal);
                loadKindOn(mv, mk, tmp);
                mv.visitFieldInsn(PUTFIELD, c.constructorClass.name, d.name(), jvmDescriptor(mk));
                c.constructorClass.emitMarkDefined(mv, c.typedThisLocal, idx);
                loadKindOn(mv, mk, tmp);
                c.stackKind = mk;
                return;
            }
            mv.visitVarInsn(ALOAD, c.typedThisLocal);
            defValueAs(d, PrimitiveKind.NONE);
            mv.visitInsn(DUP_X1);
            mv.visitFieldInsn(PUTFIELD, c.constructorClass.name, d.name(), "Ljava/lang/Object;");
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (target.kind() == LoweringTargets.DefKind.STATIC) {
            mv.visitVarInsn(ALOAD, c.staticTarget);
        } else {
            mv.visitVarInsn(ALOAD, c.nsLocal);
        }
        if (target.kind() == LoweringTargets.DefKind.LEXICAL) {
            mv.visitLdcInsn(d.name());
        } else {
            ConstantPool.pushInt(mv, target.index());
        }
        defValueAs(d, PrimitiveKind.NONE);
        switch (target.kind()) {
            case LEXICAL -> mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "initialize", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            case STATIC -> mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "BabbleType", "initStatic", "(ILjava/lang/Object;)Ljava/lang/Object;", false);
            default -> mv.visitMethodInsn(INVOKESTATIC, CLASSES, "setDefault", "(L" + NAMESPACE + ";ILjava/lang/Object;)Ljava/lang/Object;", false);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    /**
     * Checks whether a compound assignment of {@code targetKind} with {@code rhsKind} and
     * {@code op} can lower directly to primitive JVM arithmetic ({@code IADD/ISUB/IMUL} or
     * {@code DADD/DSUB/DMUL/DDIV}).
     */
    private static boolean canLowerPrimitiveCompound(PrimitiveKind targetKind, PrimitiveKind rhsKind, String op) {
        if (targetKind == PrimitiveKind.INT) {
            return rhsKind == PrimitiveKind.INT && !"/".equals(op);
        }
        if (targetKind == PrimitiveKind.DOUBLE) {
            return rhsKind == PrimitiveKind.INT || rhsKind == PrimitiveKind.DOUBLE;
        }
        return false;
    }

    /**
     * Given the current primitive value of {@code kind} already on top of the JVM stack,
     * saves it, evaluates {@code rhs} as {@code kind}, applies {@code op}, and leaves the
     * resulting {@code kind} on the stack.
     */
    private void emitPrimitiveCompoundArith(PrimitiveKind kind, String op, HirExpr rhsExpr) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int cur = c.newLocal(kind);
        storeKindOn(mv, kind, cur);
        emitter.emitAs(rhsExpr, kind);
        int rhs = c.newLocal(kind);
        storeKindOn(mv, kind, rhs);
        loadKindOn(mv, kind, cur);
        loadKindOn(mv, kind, rhs);
        int opcode = kind == PrimitiveKind.INT
                ? switch (op) {
                    case "+" -> IADD;
                    case "-" -> ISUB;
                    case "*" -> IMUL;
                    default -> throw new IllegalStateException("Unexpected int compound op: " + op);
                }
                : switch (op) {
                    case "+" -> DADD;
                    case "-" -> DSUB;
                    case "*" -> DMUL;
                    case "/" -> DDIV;
                    default -> throw new IllegalStateException("Unexpected double compound op: " + op);
                };
        mv.visitInsn(opcode);
        c.stackKind = kind;
    }

    void emitVarAssign(HirExpr.VarAssign a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        HirVar variable = a.var();
        PrimitiveKind vk = variable.primitiveKind();
        int ln = a.meta().line();
        if (a.operator() == null) {
            emitter.emitAndWiden(a.value());
            emitter.widenStackRank(a.meta().wideningRank());
            emitter.convertStack(c.stackKind, vk);
            emitter.storeVariable(variable, false, vk, ln);
            return;
        }
        String op = a.operator();
        if (canLowerPrimitiveCompound(vk, a.value().meta().kind(), op)) {
            emitter.loadVariable(variable, ln);
            emitPrimitiveCompoundArith(vk, op, a.value());
            emitter.storeVariable(variable, false, vk, ln);
            return;
        }
        int current = c.newLocal();
        emitter.loadVariable(variable, ln);
        emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        emitter.widenStackRank(a.meta().wideningRank());
        emitter.convertStack(c.stackKind, vk);
        emitter.storeVariable(variable, false, vk, ln);
    }

    void emitNameAssign(HirExpr.NameAssign a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = a.meta().line();
        if (a.operator() == null) {
            mv.visitVarInsn(ALOAD, c.nsLocal);
            mv.visitLdcInsn(a.name());
            emitter.emitAndWiden(a.value());
            emitter.widenStackRank(a.meta().wideningRank());
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            c.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        int current = c.newLocal();
        emitter.loadVarOrName(null, a.name(), ln);
        emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, a.operator(), a.targetText(), a.value(), a.valueText(), current);
        emitter.widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, c.nsLocal);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(a.name());
        mv.visitInsn(SWAP);
        c.line(ln);
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitThisFieldAssign(HirExpr.ThisFieldAssign a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = a.meta().line();
        ClassGen gen = a.classId() >= 0 ? emitter.classGen(a.classId()) : null;
        int idx = a.memberIndex();
        String name = a.memberName();
        if (a.operator() == null) {
            if (gen != null && idx >= 0) {
                PrimitiveKind mk = gen.memberKinds[idx];
                if (mk.isPrimitive()) {
                    int recv = c.newLocal();
                    loadThisAs(a.receiver(), gen);
                    mv.visitVarInsn(ASTORE, recv);
                    emitter.emitAndWiden(a.value());
                    emitter.widenStackRank(a.meta().wideningRank());
                    if (c.stackKind == PrimitiveKind.NONE && mk == PrimitiveKind.DOUBLE
                            && a.value().meta().kind() == PrimitiveKind.INT
                            && a.value().meta().wideningRank() < 0 && a.meta().wideningRank() < 0) {
                        unboxOn(mv, PrimitiveKind.INT);
                        mv.visitInsn(I2D);
                        c.stackKind = PrimitiveKind.DOUBLE;
                    } else {
                        emitter.convertStack(c.stackKind, mk);
                    }
                    int val = c.newLocal(mk);
                    storeKindOn(mv, mk, val);
                    gen.emitCheckDefined(mv, recv, idx, name, ln);
                    mv.visitVarInsn(ALOAD, recv);
                    loadKindOn(mv, mk, val);
                    mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                    loadKindOn(mv, mk, val);
                    c.stackKind = mk;
                    return;
                }
                loadThisAs(a.receiver(), gen);
                emitter.emitBoxed(a.value());
                mv.visitInsn(SWAP);
                mv.visitInsn(DUP);
                mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
                mv.visitLdcInsn(name);
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitInsn(POP);
                mv.visitInsn(SWAP);
                emitter.widenStackRank(a.meta().wideningRank());
                mv.visitInsn(DUP_X1);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
                c.stackKind = PrimitiveKind.NONE;
                return;
            }
            emitter.emitBoxed(a.receiver());
            emitter.emitAndWiden(a.value());
            emitter.widenStackRank(a.meta().wideningRank());
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            mv.visitLdcInsn(name);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }

        // Compound assignment on this.field
        String op = a.operator();
        int current = c.newLocal();
        if (gen != null && idx >= 0) {
            PrimitiveKind mk = gen.memberKinds[idx];
            if (canLowerPrimitiveCompound(mk, a.value().meta().kind(), op)) {
                int recv = c.newLocal();
                loadThisAs(a.receiver(), gen);
                mv.visitVarInsn(ASTORE, recv);
                gen.emitCheckDefined(mv, recv, idx, name, ln);
                mv.visitVarInsn(ALOAD, recv);
                mv.visitFieldInsn(GETFIELD, gen.name, name, jvmDescriptor(mk));
                emitPrimitiveCompoundArith(mk, op, a.value());
                int res = c.newLocal(mk);
                storeKindOn(mv, mk, res);
                mv.visitVarInsn(ALOAD, recv);
                loadKindOn(mv, mk, res);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                loadKindOn(mv, mk, res);
                c.stackKind = mk;
                return;
            }
            if (mk.isPrimitive()) {
                int recv = c.newLocal();
                loadThisAs(a.receiver(), gen);
                mv.visitVarInsn(ASTORE, recv);
                gen.emitCheckDefined(mv, recv, idx, name, ln);
                mv.visitVarInsn(ALOAD, recv);
                mv.visitFieldInsn(GETFIELD, gen.name, name, jvmDescriptor(mk));
                boxOn(mv, mk);
                mv.visitVarInsn(ASTORE, current);
                emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
                emitter.widenStackRank(a.meta().wideningRank());
                emitter.convertStack(c.stackKind, mk);
                int res = c.newLocal(mk);
                storeKindOn(mv, mk, res);
                mv.visitVarInsn(ALOAD, recv);
                loadKindOn(mv, mk, res);
                mv.visitFieldInsn(PUTFIELD, gen.name, name, jvmDescriptor(mk));
                loadKindOn(mv, mk, res);
                c.stackKind = mk;
                return;
            }
            loadThisAs(a.receiver(), gen);
            mv.visitFieldInsn(GETFIELD, gen.name, name, "Ljava/lang/Object;");
            mv.visitLdcInsn(name);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, current);
            emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
            emitter.widenStackRank(a.meta().wideningRank());
            loadThisAs(a.receiver(), gen);
            mv.visitInsn(SWAP);
            mv.visitInsn(DUP_X1);
            mv.visitFieldInsn(PUTFIELD, gen.name, name, "Ljava/lang/Object;");
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        int thiz = c.newLocal();
        emitter.emitBoxed(a.receiver());
        mv.visitVarInsn(ASTORE, thiz);
        mv.visitVarInsn(ALOAD, thiz);
        mv.visitLdcInsn(name);
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        emitter.widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, thiz);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(name);
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignOwn", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitJavaFieldAssignExpr(HirExpr.JavaFieldAssign a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = a.meta().line();
        LoweringTargets.JavaFieldTarget target = a.target();
        Field field = target.field();
        Class<?> fieldType = field.getType();
        String owner = Type.getInternalName(field.getDeclaringClass());
        String desc = Type.getDescriptor(fieldType);
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!a.pureStaticReceiver()) {
                emitter.emitCheckedJavaReceiver(a.receiver(), a.receiverText(), ln, false, OBJECT, "notAssignable", null);
                mv.visitInsn(POP);
            }
        } else {
            emitter.emitCheckedJavaReceiver(a.receiver(), a.receiverText(), ln, target.unwrapReceiver(), owner, "notAssignable", null);
            recvLocal = c.newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }

        if (a.operator() == null) {
            emitSimpleJavaFieldAssign(a, target, field, fieldType, owner, desc, recvLocal, ln);
            return;
        }

        // Compound assignment on Java field
        String op = a.operator();
        PrimitiveKind fieldKind = javaPrimitiveKind(fieldType);
        if (canLowerPrimitiveCompound(fieldKind, a.value().meta().kind(), op)) {
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            c.line(ln);
            mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), desc);
            emitPrimitiveCompoundArith(fieldKind, op, a.value());
            int res = c.newLocal(fieldKind);
            storeKindOn(mv, fieldKind, res);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            loadKindOn(mv, fieldKind, res);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            loadKindOn(mv, fieldKind, res);
            c.stackKind = fieldKind;
            return;
        }
        if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
        c.line(ln);
        mv.visitFieldInsn(target.isStatic() ? GETSTATIC : GETFIELD, owner, field.getName(), desc);
        emitter.emitJavaValueToBabbleStack(fieldType);
        emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
        int current = c.newLocal();
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, op, a.targetText(), a.value(), a.valueText(), current);
        emitter.widenStackRank(a.meta().wideningRank());
        int resObj = c.newLocal();
        mv.visitVarInsn(ASTORE, resObj);
        if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
        mv.visitVarInsn(ALOAD, resObj);
        if (fieldKind != PrimitiveKind.NONE) {
            unboxOn(mv, fieldKind);
        } else {
            mv.visitLdcInsn(Type.getType(fieldType));
            mv.visitVarInsn(ALOAD, c.rtLocal);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
            if (fieldType != Object.class) {
                mv.visitTypeInsn(CHECKCAST, Type.getInternalName(fieldType));
            }
        }
        mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
        mv.visitVarInsn(ALOAD, resObj);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitSimpleJavaFieldAssign(HirExpr.JavaFieldAssign a, LoweringTargets.JavaFieldTarget target,
                                           Field field, Class<?> fieldType, String owner, String desc,
                                           int recvLocal, int ln) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (fieldType == int.class || fieldType == boolean.class) {
            PrimitiveKind pk = fieldType == int.class ? PrimitiveKind.INT : PrimitiveKind.BOOL;
            emitter.emitAs(a.value(), pk);
            int val = c.newLocal(pk);
            mv.visitVarInsn(ISTORE, val);
            c.line(ln);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(ILOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(ILOAD, val);
            c.stackKind = pk;
            return;
        }
        if (fieldType == double.class) {
            emitter.emitAs(a.value(), PrimitiveKind.DOUBLE);
            int val = c.newLocal(PrimitiveKind.DOUBLE);
            mv.visitVarInsn(DSTORE, val);
            c.line(ln);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(DLOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(DLOAD, val);
            c.stackKind = PrimitiveKind.DOUBLE;
            return;
        }
        if (fieldType == long.class) {
            emitter.emitExpr(a.value());
            if (c.stackKind == PrimitiveKind.INT) {
                mv.visitInsn(I2L);
            } else {
                boxOn(mv, c.stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            }
            int val = c.newLocal(PrimitiveKind.LONG);
            mv.visitVarInsn(LSTORE, val);
            c.line(ln);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(LLOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(LLOAD, val);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (fieldType == float.class) {
            emitter.emitExpr(a.value());
            if (c.stackKind == PrimitiveKind.INT) {
                mv.visitInsn(I2F);
            } else {
                boxOn(mv, c.stackKind);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
            }
            int val = c.newLocal();
            mv.visitVarInsn(FSTORE, val);
            c.line(ln);
            if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
            mv.visitVarInsn(FLOAD, val);
            mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
            mv.visitVarInsn(FLOAD, val);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        PrimitiveKind valKind = a.value().meta().kind();
        emitter.emitBoxed(a.value());
        emitter.widenStackRank(a.meta().wideningRank());
        int babbleVal = c.newLocal();
        mv.visitVarInsn(ASTORE, babbleVal);
        c.line(ln);
        if (!target.isStatic()) mv.visitVarInsn(ALOAD, recvLocal);
        mv.visitVarInsn(ALOAD, babbleVal);
        if (!valKind.isPrimitive()) {
            mv.visitLdcInsn(Type.getType(fieldType));
            mv.visitVarInsn(ALOAD, c.rtLocal);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava", "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        }
        if (fieldType != Object.class) {
            mv.visitTypeInsn(CHECKCAST, Type.getInternalName(fieldType));
        }
        mv.visitFieldInsn(target.isStatic() ? PUTSTATIC : PUTFIELD, owner, field.getName(), desc);
        mv.visitVarInsn(ALOAD, babbleVal);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitDynamicFieldAssign(HirExpr.DynamicFieldAssign a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = a.meta().line();
        if (a.operator() == null) {
            emitter.emitBoxed(a.receiver());
            c.line(ln);
            ConstantPool.pushString(mv, a.receiverText());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            emitter.emitAndWiden(a.value());
            emitter.widenStackRank(a.meta().wideningRank());
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            mv.visitLdcInsn(a.memberName());
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        int scope = c.newLocal();
        int current = c.newLocal();
        emitter.emitBoxed(a.receiver());
        c.line(ln);
        ConstantPool.pushString(mv, a.receiverText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, scope);
        mv.visitVarInsn(ALOAD, scope);
        mv.visitLdcInsn(a.memberName());
        ConstantPool.pushString(mv, a.receiverText());
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, current);
        emitCompound(ln, a.operator(), a.targetText(), a.value(), a.valueText(), current);
        emitter.widenStackRank(a.meta().wideningRank());
        mv.visitVarInsn(ALOAD, scope);
        mv.visitInsn(SWAP);
        mv.visitLdcInsn(a.memberName());
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitCompound(int lineNumber, String operator, String targetText, HirExpr rhsExpr, String rhsText, int currentLocal) {
        MethodEmitContext c = ctx();
        int value = c.newLocal();
        emitter.emitBoxed(rhsExpr);
        c.mv.visitVarInsn(ASTORE, value);
        emitter.controlOps.emitDynamicArithmetic(lineNumber, operator, targetText, rhsText, currentLocal, value);
        c.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- member selection & arrays

    void emitThisFieldGet(HirExpr.ThisFieldGet g) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = g.meta().line();
        if (g.receiverText() != null) {
            ClassGen gen = g.classId() >= 0 ? emitter.classGen(g.classId()) : null;
            String targetJvm = g.externalClassJvmName() != null ? g.externalClassJvmName() : (gen != null ? gen.name : null);
            int idx = g.memberIndex();
            if (targetJvm != null && (g.externalClassJvmName() != null || (gen != null && idx >= 0))) {
                emitter.emitBoxed(g.receiver());
                Label ok = new Label();
                mv.visitInsn(DUP);
                mv.visitJumpInsn(IFNONNULL, ok);
                mv.visitInsn(POP);
                c.line(ln);
                ConstantPool.pushString(mv, g.receiverText());
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
                mv.visitInsn(ATHROW);
                mv.visitLabel(ok);
                mv.visitTypeInsn(CHECKCAST, targetJvm);
                PrimitiveKind mk = gen != null ? gen.memberKinds[idx] : g.fieldKind();
                if (mk.isPrimitive()) {
                    if (gen != null && !g.definitelyDefined() && gen.primDefBits[idx] >= 0) {
                        int recv = c.newLocal();
                        mv.visitVarInsn(ASTORE, recv);
                        gen.emitCheckDefined(mv, recv, idx, g.memberName(), ln);
                        mv.visitVarInsn(ALOAD, recv);
                    }
                    mv.visitFieldInsn(GETFIELD, targetJvm, g.memberName(), jvmDescriptor(mk));
                    c.stackKind = mk;
                    checkSelectorType(g.meta());
                    return;
                }
                mv.visitFieldInsn(GETFIELD, targetJvm, g.memberName(), "Ljava/lang/Object;");
                if (!g.definitelyDefined()) {
                    mv.visitLdcInsn(g.memberName());
                    c.line(ln);
                    mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                }
                c.stackKind = PrimitiveKind.NONE;
                checkSelectorType(g.meta());
                return;
            }
        }
        ClassGen gen = g.classId() >= 0 ? emitter.classGen(g.classId()) : null;
        int idx = g.memberIndex();
        if (gen != null && idx >= 0) {
            PrimitiveKind mk = gen.memberKinds[idx];
            loadThisAs(g.receiver(), gen);
            if (mk.isPrimitive()) {
                if (gen.primDefBits[idx] >= 0) {
                    int recv = c.newLocal();
                    mv.visitVarInsn(ASTORE, recv);
                    gen.emitCheckDefined(mv, recv, idx, g.memberName(), ln);
                    mv.visitVarInsn(ALOAD, recv);
                }
                mv.visitFieldInsn(GETFIELD, gen.name, g.memberName(), jvmDescriptor(mk));
                c.stackKind = mk;
                checkSelectorType(g.meta());
                return;
            }
            mv.visitFieldInsn(GETFIELD, gen.name, g.memberName(), "Ljava/lang/Object;");
            mv.visitLdcInsn(g.memberName());
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        } else {
            emitter.emitBoxed(g.receiver());
            mv.visitLdcInsn(g.memberName());
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "selectOwn", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        c.stackKind = PrimitiveKind.NONE;
        checkSelectorType(g.meta());
    }

    void emitJavaFieldGet(HirExpr.JavaFieldGet g) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = g.meta().line();
        LoweringTargets.JavaFieldTarget target = g.target();
        Field field = target.field();
        String owner = Type.getInternalName(field.getDeclaringClass());
        if (target.isStatic()) {
            if (!g.pureStaticReceiver()) {
                emitter.emitCheckedJavaReceiver(g.receiver(), g.receiverText(), ln, false, OBJECT, "notAScope", null);
                mv.visitInsn(POP);
            }
            c.line(ln);
            mv.visitFieldInsn(GETSTATIC, owner, field.getName(), Type.getDescriptor(field.getType()));
        } else {
            emitter.emitCheckedJavaReceiver(g.receiver(), g.receiverText(), ln, target.unwrapReceiver(), owner, "notAScope", null);
            c.line(ln);
            mv.visitFieldInsn(GETFIELD, owner, field.getName(), Type.getDescriptor(field.getType()));
        }
        emitter.emitJavaValueToBabbleStack(field.getType());
        checkSelectorType(g.meta());
    }

    void emitArrayCreate(HirExpr.ArrayCreate c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = c.meta().line();
        PrimitiveKind elemKind = c.elemKind();
        switch (c.kind()) {
            case SIZE -> {
                emitter.emitAs(c.args().get(0), PrimitiveKind.INT);
                mc.line(ln);
                emitNewArray(mv, elemKind, c.componentInternalName());
            }
            case OF -> {
                ConstantPool.pushInt(mv, c.args().size());
                mc.line(ln);
                emitNewArray(mv, elemKind, c.componentInternalName());
                int arrLocal = mc.newLocal();
                mv.visitVarInsn(ASTORE, arrLocal);
                for (int i = 0; i < c.args().size(); i++) {
                    mv.visitVarInsn(ALOAD, arrLocal);
                    ConstantPool.pushInt(mv, i);
                    if (elemKind.isPrimitive()) {
                        emitter.emitAs(c.args().get(i), elemKind);
                    } else {
                        emitter.emitBoxed(c.args().get(i));
                        if (c.componentIsJavaInterface() && c.componentClass() != null) {
                            mv.visitLdcInsn(Type.getType(c.componentClass()));
                            mv.visitVarInsn(ALOAD, mc.rtLocal);
                            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava",
                                    "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                        } else {
                            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        }
                    }
                    mc.line(ln);
                    mv.visitInsn(astoreInsn(elemKind));
                }
                mv.visitVarInsn(ALOAD, arrLocal);
            }
            case FROM -> {
                if (elemKind.isPrimitive()) {
                    pushPrimitiveClass(mv, elemKind);
                } else {
                    mv.visitLdcInsn(Type.getObjectType(c.componentInternalName()));
                }
                emitter.emitBoxed(c.args().get(0));
                mv.visitVarInsn(ALOAD, mc.rtLocal);
                mc.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "from",
                        "(Ljava/lang/Class;Ljava/lang/Object;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
            }
        }
        mc.stackKind = PrimitiveKind.NONE;
        emitter.calls.finishCallCheck(c.meta());
    }

    void emitArrayOp(HirExpr.ArrayOp op) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = op.meta().line();
        PrimitiveKind elemKind = op.elemKind();
        String castName = elemKind.isPrimitive() ? Type.getInternalName(op.arrayClass()) : "[Ljava/lang/Object;";
        switch (op.kind()) {
            case SIZE -> {
                emitter.emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                c.line(ln);
                mv.visitInsn(ARRAYLENGTH);
                c.stackKind = PrimitiveKind.INT;
            }
            case GET -> {
                emitter.emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                emitter.emitAs(op.args().get(0), PrimitiveKind.INT);
                c.line(ln);
                if (elemKind.isPrimitive()) {
                    mv.visitInsn(aloadInsn(elemKind));
                    c.stackKind = elemKind;
                } else {
                    mv.visitInsn(AALOAD);
                    mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    c.stackKind = PrimitiveKind.NONE;
                }
            }
            case SET -> {
                emitter.emitCheckedJavaReceiver(op.receiver(), op.receiverText(), ln, true, castName, "notAScope", null);
                emitter.emitAs(op.args().get(0), PrimitiveKind.INT);
                if (elemKind.isPrimitive()) {
                    emitter.emitAs(op.args().get(1), elemKind);
                } else {
                    emitter.emitBoxed(op.args().get(1));
                    if (op.componentClass() != null && op.componentClass().isInterface()) {
                        mv.visitLdcInsn(Type.getType(op.componentClass()));
                        mv.visitVarInsn(ALOAD, c.rtLocal);
                        mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJava",
                                "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                    } else {
                        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    }
                }
                c.line(ln);
                mv.visitInsn(astoreInsn(elemKind));
                mv.visitInsn(ACONST_NULL);
                c.stackKind = PrimitiveKind.NONE;
            }
            case EQUALS -> {
                emitter.emitBoxed(op.receiver());
                emitter.emitBoxed(op.args().get(0));
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepEquals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                c.stackKind = PrimitiveKind.BOOL;
            }
            case HASH_CODE -> {
                emitter.emitBoxed(op.receiver());
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepHashCode", "(Ljava/lang/Object;)I", false);
                c.stackKind = PrimitiveKind.INT;
            }
            case TO_STRING -> {
                emitter.emitBoxed(op.receiver());
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "deepToString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                c.stackKind = PrimitiveKind.NONE;
            }
        }
        emitter.calls.finishCallCheck(op.meta());
    }

    private static void emitNewArray(MethodVisitor mv, PrimitiveKind elemKind, String componentInternalName) {
        if (elemKind.isPrimitive()) {
            int code = switch (elemKind) {
                case BYTE -> T_BYTE;
                case SHORT -> T_SHORT;
                case INT -> T_INT;
                case LONG -> T_LONG;
                case FLOAT -> T_FLOAT;
                case DOUBLE -> T_DOUBLE;
                case BOOL -> T_BOOLEAN;
                case CHAR -> T_CHAR;
                case NONE -> throw new IllegalStateException();
            };
            mv.visitIntInsn(NEWARRAY, code);
        } else {
            mv.visitTypeInsn(ANEWARRAY, componentInternalName);
        }
    }

    private static void pushPrimitiveClass(MethodVisitor mv, PrimitiveKind elemKind) {
        String wrapper = switch (elemKind) {
            case BYTE -> "java/lang/Byte";
            case SHORT -> "java/lang/Short";
            case INT -> "java/lang/Integer";
            case LONG -> "java/lang/Long";
            case FLOAT -> "java/lang/Float";
            case DOUBLE -> "java/lang/Double";
            case BOOL -> "java/lang/Boolean";
            case CHAR -> "java/lang/Character";
            case NONE -> throw new IllegalStateException();
        };
        mv.visitFieldInsn(GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
    }

    private static int aloadInsn(PrimitiveKind elemKind) {
        return switch (elemKind) {
            case BYTE, BOOL -> BALOAD;
            case SHORT -> SALOAD;
            case INT -> IALOAD;
            case LONG -> LALOAD;
            case FLOAT -> FALOAD;
            case DOUBLE -> DALOAD;
            case CHAR -> CALOAD;
            case NONE -> AALOAD;
        };
    }

    private static int astoreInsn(PrimitiveKind elemKind) {
        return switch (elemKind) {
            case BYTE, BOOL -> BASTORE;
            case SHORT -> SASTORE;
            case INT -> IASTORE;
            case LONG -> LASTORE;
            case FLOAT -> FASTORE;
            case DOUBLE -> DASTORE;
            case CHAR -> CASTORE;
            case NONE -> AASTORE;
        };
    }

    void emitModuleExportGet(HirExpr.ModuleExportGet g) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitter.emitBoxed(g.receiver());
        mv.visitTypeInsn(CHECKCAST, RUNTIME + "Module");
        ConstantPool.pushInt(mv, g.target().index());
        c.line(g.meta().line());
        mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "Module", "exportAt", "(I)Ljava/lang/Object;", false);
        c.stackKind = PrimitiveKind.NONE;
        checkSelectorType(g.meta());
    }

    void emitDynamicSelect(HirExpr.DynamicSelect s) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = s.meta().line();
        if (s.safe()) {
            Label nonNull = new Label();
            Label end = new Label();
            emitter.emitBoxed(s.receiver());
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, nonNull);
            mv.visitInsn(POP);
            mv.visitInsn(ACONST_NULL);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(nonNull);
            selectReceiverMember(ln, s.receiverText(), s.memberName());
            if (s.meta().checkedType() != null) {
                c.line(ln);
                emitter.controlOps.checkType(s.meta().checkedType());
            }
            mv.visitLabel(end);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        emitter.emitBoxed(s.receiver());
        selectReceiverMember(ln, s.receiverText(), s.memberName());
        c.stackKind = PrimitiveKind.NONE;
        checkSelectorType(s.meta());
    }

    void checkSelectorType(ExprMeta meta) {
        MethodEmitContext c = ctx();
        String checked = meta.checkedType();
        if (checked != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            c.line(meta.line());
            emitter.controlOps.checkType(checked);
        }
    }

    void selectReceiverMember(int lineNumber, String text, String name) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        c.line(lineNumber);
        if (text.length() <= ConstantPool.MAX_STRING_CONSTANT) {
            mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, name, text);
        } else {
            mv.visitLdcInsn(name);
            ConstantPool.pushString(mv, text);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    void loadThisAs(HirExpr thisExpr, ClassGen gen) {
        MethodEmitContext c = ctx();
        emitter.emitBoxed(thisExpr);
        c.mv.visitTypeInsn(CHECKCAST, gen.name);
        c.stackKind = PrimitiveKind.NONE;
    }
}
