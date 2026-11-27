package org.babblelang.compiler;

import static org.babblelang.compiler.HirEmitter.SELF;
import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.MethodVisitor;

/**
 * Emits frame-local variables, captured closure cells, hidden instance-capture fields, lexical
 * namespace lookups, and block/namespace scopes on behalf of {@link HirEmitter}.
 */
final class FrameVarEmitter {
    private final HirEmitter emitter;

    /** Top-level {@code def C = class or record}, name to class id : see {@link #loadName}. */
    private Map<String, Integer> selfClassBindings;

    FrameVarEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    void loadCapturedCells(String ownerType) {
        MethodEmitContext ctx = emitter.ctx;
        if (ctx.capturedVariables.isEmpty()) {
            return;
        }
        MethodVisitor mv = ctx.mv;
        ctx.capturedLocal = ctx.newLocal();
        if (ctx.methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(
                    GETFIELD, ctx.methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
        } else {
            mv.visitVarInsn(ALOAD, SELF);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, ownerType, "captured", "()[Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, ctx.capturedLocal);
    }

    List<HirVar> declareVariables(List<HirVar> variables) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        for (HirVar variable : variables) {
            PrimitiveKind vk = variable.primitiveKind();
            int slot = ctx.newLocal(variable.cell() ? PrimitiveKind.NONE : vk);
            ctx.slots.put(variable.id(), slot);
            if (!variable.cell()
                    && vk.isPrimitive()
                    && variable.mayBeUndefined()
                    && !variable.definitelyDefined()) {
                int defSlot = ctx.newLocal(PrimitiveKind.BOOL);
                ctx.definedSlots.put(variable.id(), defSlot);
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
            if (variable.kind() == Resolution.Variable.Kind.DEFINITION
                    || (variable.kind() == Resolution.Variable.Kind.PARAMETER && variable.cell())) {
                if (variable.cell()) {
                    String cc = cellClass(vk);
                    mv.visitMethodInsn(INVOKESTATIC, cc, "undefined", "()L" + cc + ";", false);
                    mv.visitVarInsn(ASTORE, slot);
                    publishHidden(variable);
                } else if (vk.isPrimitive()) {
                    emitDefaultValueOn(mv, vk);
                    storeKindOn(mv, vk, slot);
                } else {
                    mv.visitFieldInsn(
                            GETSTATIC,
                            RUNTIME + "Undefined",
                            "UNDEFINED",
                            "L" + RUNTIME + "Undefined;");
                    mv.visitVarInsn(ASTORE, slot);
                }
            }
        }
        return variables;
    }

    void publishHidden(HirVar variable) {
        MethodEmitContext ctx = emitter.ctx;
        if (ctx.constructorClass != null
                && ctx.typedThisLocal >= 0
                && variable.isCaptured()
                && ctx.constructorClass.hasHiddenField(variable)) {
            PrimitiveKind vk = variable.cell() ? PrimitiveKind.NONE : variable.primitiveKind();
            ctx.mv.visitVarInsn(ALOAD, ctx.typedThisLocal);
            loadKindOn(ctx.mv, vk, ctx.slots.get(variable.id()));
            ctx.mv.visitFieldInsn(
                    PUTFIELD,
                    ctx.constructorClass.name,
                    variable.hiddenFieldName(),
                    jvmDescriptor(vk));
        }
    }

    void initVariable(HirVar variable, PrimitiveKind vk) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        if (variable.cell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(
                    INVOKESTATIC, cc, "of", "(" + jvmDescriptor(vk) + ")L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, ctx.slots.get(variable.id()));
            publishHidden(variable);
            return;
        }
        storeKindOn(mv, vk, ctx.slots.get(variable.id()));
        Integer defSlot = ctx.definedSlots.get(variable.id());
        if (defSlot != null) {
            mv.visitInsn(ICONST_1);
            mv.visitVarInsn(ISTORE, defSlot);
        }
        if (variable.capturedByValue()) {
            publishHidden(variable);
        }
    }

    private void loadCell(HirVar variable) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        String cc = cellClass(variable.primitiveKind());
        if (variable.ownerMethodId() == ctx.methodOwnerId) {
            mv.visitVarInsn(ALOAD, ctx.slots.get(variable.id()));
        } else if (ctx.methodClass != null
                && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(
                    GETFIELD,
                    ctx.methodClass.name,
                    variable.hiddenFieldName(),
                    "Ljava/lang/Object;");
            mv.visitTypeInsn(CHECKCAST, cc);
        } else {
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, cc);
        }
    }

    void loadVariable(HirVar variable, int lineNumber) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        PrimitiveKind vk = variable.primitiveKind();
        if (variable.cell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            loadCell(variable);
            if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
                ctx.line(lineNumber);
                mv.visitLdcInsn(variable.name());
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "read", "(Ljava/lang/String;)" + desc, false);
            } else {
                mv.visitFieldInsn(GETFIELD, cc, "value", desc);
            }
            ctx.stackKind = vk;
            return;
        }
        if (variable.capturedByValue() && variable.ownerMethodId() != ctx.methodOwnerId) {
            if (ctx.methodClass != null && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(
                        GETFIELD,
                        ctx.methodClass.name,
                        variable.hiddenFieldName(),
                        jvmDescriptor(vk));
                ctx.stackKind = vk;
                return;
            }
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
            if (vk.isPrimitive()) {
                unboxOn(mv, vk);
            }
            ctx.stackKind = vk;
            return;
        }
        if (vk.isPrimitive()) {
            Integer defSlot = ctx.definedSlots.get(variable.id());
            if (defSlot != null) {
                mv.visitVarInsn(ILOAD, defSlot);
                mv.visitLdcInsn(variable.name());
                ctx.line(lineNumber);
                mv.visitMethodInsn(
                        INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
            }
            loadKindOn(mv, vk, ctx.slots.get(variable.id()));
            ctx.stackKind = vk;
            return;
        }
        mv.visitVarInsn(ALOAD, ctx.slots.get(variable.id()));
        if (variable.mayBeUndefined() && !variable.definitelyDefined()) {
            ctx.line(lineNumber);
            mv.visitLdcInsn(variable.name());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    FRAMES,
                    "defined",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                    false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void storeVariable(HirVar variable, boolean definition, PrimitiveKind vk, int lineNumber) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        if (variable.cell()) {
            String cc = cellClass(vk);
            String desc = jvmDescriptor(vk);
            int tmp = ctx.newLocal(vk);
            storeKindOn(mv, vk, tmp);
            loadCell(variable);
            loadKindOn(mv, vk, tmp);
            if (definition) {
                mv.visitMethodInsn(INVOKEVIRTUAL, cc, "define", "(" + desc + ")" + desc, false);
            } else {
                mv.visitLdcInsn(variable.name());
                ctx.line(lineNumber);
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        cc,
                        "assign",
                        "(" + desc + "Ljava/lang/String;)" + desc,
                        false);
            }
            ctx.stackKind = vk;
            return;
        }
        int slot = ctx.slots.get(variable.id());
        if (vk.isPrimitive()) {
            Integer defSlot = ctx.definedSlots.get(variable.id());
            if (defSlot != null) {
                if (definition) {
                    mv.visitInsn(ICONST_1);
                    mv.visitVarInsn(ISTORE, defSlot);
                } else {
                    mv.visitVarInsn(ILOAD, defSlot);
                    mv.visitLdcInsn(variable.name());
                    ctx.line(lineNumber);
                    mv.visitMethodInsn(
                            INVOKESTATIC, FRAMES, "checkDefined", "(ZLjava/lang/String;)V", false);
                }
            }
            dupOn(mv, vk);
            storeKindOn(mv, vk, slot);
            if (definition && variable.capturedByValue()) {
                publishHidden(variable);
            }
            ctx.stackKind = vk;
            return;
        }
        if (!definition && variable.mayBeUndefined() && !variable.definitelyDefined()) {
            mv.visitVarInsn(ALOAD, slot);
            mv.visitLdcInsn(variable.name());
            ctx.line(lineNumber);
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    FRAMES,
                    "checkAssignable",
                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                    false);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, slot);
        if (definition && variable.capturedByValue()) {
            publishHidden(variable);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private Map<String, Integer> selfClassBindings() {
        if (selfClassBindings == null) {
            selfClassBindings = new HashMap<>();
            for (HirExpr stmt : emitter.module.topLevelStatements()) {
                if (stmt instanceof HirExpr.Def d
                        && d.var() == null
                        && d.target().kind()
                                == org.babblelang.analysis.LoweringTargets.DefKind.LEXICAL) {
                    HirExpr value = d.value();
                    while (value instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
                        value = b.statements().get(0);
                    }
                    if (value instanceof HirExpr.ClassExpr ce
                            && !emitter.module.clazz(ce.classId()).isObject()) {
                        selfClassBindings.put(d.name(), ce.classId());
                    }
                }
            }
        }
        return selfClassBindings;
    }

    /**
     * A name read. Inside a method of class {@code C}, a read of the top-level {@code def C} that
     * binds it, final and definitely defined, is the receiver's class : classes cannot be extended,
     * so {@code this.$babble$class} is that very class value, without a lookup. A read that could
     * run before the binding (from a static block) is not definitely defined, and keeps the lookup
     * and its error.
     */
    void loadName(String name, boolean finalAndDefined, int lineNumber) {
        MethodEmitContext ctx = emitter.ctx;
        if (finalAndDefined
                && ctx.methodClass != null
                && Integer.valueOf(ctx.methodClass.cls.id())
                        .equals(selfClassBindings().get(name))) {
            ctx.mv.visitVarInsn(ALOAD, ctx.thizLocal);
            ctx.mv.visitFieldInsn(
                    GETFIELD, ctx.methodClass.name, "$babble$class", "L" + BABBLE_CLASS + ";");
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        loadVarOrName(null, name, lineNumber);
    }

    void loadVarOrName(HirVar var, String name, int lineNumber) {
        if (var != null) {
            loadVariable(var, lineNumber);
            return;
        }
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        ctx.line(lineNumber);
        mv.visitVarInsn(ALOAD, ctx.nsLocal);
        if (ctx.cacheNameReads) {
            mv.visitInvokeDynamicInsn(
                    "name", "(L" + NAMESPACE + ";)Ljava/lang/Object;", NAME_BOOTSTRAP, name);
        } else {
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    NAMESPACE,
                    "lookup",
                    "(Ljava/lang/String;)Ljava/lang/Object;",
                    false);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void capturedCells(List<HirVar> captured) {
        MethodVisitor mv = emitter.ctx.mv;
        if (captured.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, captured.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < captured.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            HirVar variable = captured.get(i);
            if (variable.cell()) {
                loadCell(variable);
            } else {
                loadCapturedValueBoxed(variable);
            }
            mv.visitInsn(AASTORE);
        }
    }

    private void loadCapturedValueBoxed(HirVar variable) {
        MethodEmitContext ctx = emitter.ctx;
        MethodVisitor mv = ctx.mv;
        PrimitiveKind vk = variable.primitiveKind();
        if (variable.ownerMethodId() == ctx.methodOwnerId) {
            loadKindOn(mv, vk, ctx.slots.get(variable.id()));
            if (vk.isPrimitive()) {
                boxOn(mv, vk);
            }
        } else if (ctx.methodClass != null
                && variable.ownerMethodId() == ctx.methodClass.cls.id()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(
                    GETFIELD, ctx.methodClass.name, variable.hiddenFieldName(), jvmDescriptor(vk));
            if (vk.isPrimitive()) {
                boxOn(mv, vk);
            }
        } else {
            int idx = ctx.capturedVariables.indexOf(variable);
            mv.visitVarInsn(ALOAD, ctx.capturedLocal);
            ConstantPool.pushInt(mv, idx);
            mv.visitInsn(AALOAD);
        }
    }

    void inFrameScope(Runnable body) {
        MethodEmitContext ctx = emitter.ctx;
        int savedNextLocal = ctx.nextLocal;
        Map<Integer, Integer> savedSlots = new HashMap<>(ctx.slots);
        Map<Integer, Integer> savedDefinedSlots = new HashMap<>(ctx.definedSlots);
        body.run();
        ctx.nextLocal = savedNextLocal;
        ctx.slots = savedSlots;
        ctx.definedSlots = savedDefinedSlots;
    }

    void withNamespace(int namespace, Runnable body) {
        MethodEmitContext ctx = emitter.ctx;
        int outer = ctx.nsLocal;
        int savedNextLocal = ctx.nextLocal;
        ctx.nsLocal = namespace;
        try {
            body.run();
        } finally {
            ctx.nsLocal = outer;
            ctx.nextLocal = savedNextLocal;
        }
    }
}
