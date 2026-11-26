package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode emission for exception handling ({@code try}/{@code catch}/{@code finally}, {@code
 * throw}), finally-unwinding, and {@code return}, extracted from {@link ControlAndOpEmitter}.
 */
final class TryCatchEmitter {
    private final HirEmitter emitter;
    private final ControlAndOpEmitter controlOps;

    TryCatchEmitter(HirEmitter emitter, ControlAndOpEmitter controlOps) {
        this.emitter = emitter;
        this.controlOps = controlOps;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    void emitReturn(HirExpr.Return r) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitter.emitAndWiden(r.value());
        emitter.widenStackRank(r.meta().wideningRank());
        emitter.convertStack(c.stackKind, c.methodReturnKind);
        if (!c.activeFinallies.isEmpty()) {
            int retTmp = c.newLocal(c.methodReturnKind);
            storeKindOn(mv, c.methodReturnKind, retTmp);
            List<MethodEmitContext.ActiveFinally> popped = unwindFinallies(0);
            loadKindOn(mv, c.methodReturnKind, retTmp);
            mv.visitInsn(returnInsn(c.methodReturnKind));
            restoreFinallies(popped);
        } else {
            mv.visitInsn(returnInsn(c.methodReturnKind));
        }
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitThrow(HirExpr.Throw th) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitter.emitBoxed(th.value());
        c.line(th.meta().line());
        if (th.isJavaThrowable()) {
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "unwrap",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
        } else {
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Throwables",
                    "toThrowable",
                    "(Ljava/lang/Object;)Ljava/lang/Throwable;",
                    false);
        }
        mv.visitInsn(ATHROW);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    List<MethodEmitContext.ActiveFinally> unwindFinallies(int minLoopDepth) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (c.activeFinallies.isEmpty()) {
            return List.of();
        }
        List<MethodEmitContext.ActiveFinally> popped = new ArrayList<>();
        while (!c.activeFinallies.isEmpty()
                && c.activeFinallies.peek().loopDepthAtEntry >= minLoopDepth) {
            MethodEmitContext.ActiveFinally af = c.activeFinallies.pop();
            popped.add(af);
            af.splitBefore(mv);
            if (af.hasFinally) {
                emitTryFinallyBlock(af.tryNode);
            }
        }
        return popped;
    }

    void restoreFinallies(List<MethodEmitContext.ActiveFinally> popped) {
        if (popped.isEmpty()) {
            return;
        }
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        for (int i = popped.size() - 1; i >= 0; i--) {
            MethodEmitContext.ActiveFinally af = popped.get(i);
            c.activeFinallies.push(af);
            af.resumeAfter(mv);
        }
    }

    private void emitScopedBody(
            List<HirExpr> stmts, List<HirVar> declVars, boolean opensNs, String[] declNames) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (!declVars.isEmpty()) {
            emitter.inFrameScope(() -> {
                emitter.declareVariables(declVars);
                emitter.statements(stmts);
            });
        } else if (opensNs) {
            mv.visitVarInsn(ALOAD, c.nsLocal);
            emitter.getStringArray(declNames);
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    NAMESPACE,
                    "child",
                    "([Ljava/lang/String;)L" + NAMESPACE + ";",
                    false);
            int ns = c.newLocal();
            mv.visitVarInsn(ASTORE, ns);
            emitter.withNamespace(ns, () -> emitter.statements(stmts));
        } else {
            emitter.statements(stmts);
        }
    }

    private void emitTryFinallyBlock(HirExpr.Try t) {
        if (!t.hasFinally() || t.finallyBody().isEmpty()) {
            return;
        }
        MethodEmitContext c = ctx();
        emitScopedBody(
                t.finallyBody(),
                t.finallyDeclaredVars(),
                t.finallyOpensNamespaceScope(),
                t.finallyDeclaredNames());
        popOn(c.mv, c.stackKind);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitTry(HirExpr.Try t) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind targetKind = t.meta().kind();
        int resultLocal = c.newLocal(targetKind);
        boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
        List<HirExpr.HirCatch> catches = t.catches();

        Label tryEnd = new Label();
        Label finallyAbnormal = hasNonEmptyFinally ? new Label() : null;

        // 1. Emit tryBody inside an ActiveFinally range (even without finally, so inner
        // return/break
        //    running an outer finally splits this try's catch ranges cleanly!).
        Label tryStart = new Label();
        mv.visitLabel(tryStart);
        MethodEmitContext.ActiveFinally tryRange =
                new MethodEmitContext.ActiveFinally(
                        t, c.loopTargets.size(), tryStart, hasNonEmptyFinally);
        c.activeFinallies.push(tryRange);
        try {
            emitScopedBody(
                    t.tryBody(),
                    t.tryDeclaredVars(),
                    t.tryOpensNamespaceScope(),
                    t.tryDeclaredNames());
            emitter.widenStackRank(t.tryWideningRank());
            emitter.convertStack(c.stackKind, targetKind);
            storeKindOn(mv, targetKind, resultLocal);
        } finally {
            c.activeFinallies.pop();
        }
        tryRange.close(mv);
        if (hasNonEmptyFinally) {
            emitTryFinallyBlock(t);
        }
        mv.visitJumpInsn(GOTO, tryEnd);

        // 2. Determine whether we need a ThrownException dispatcher ahead of Java handlers.
        boolean needsThrownDispatcher = false;
        for (HirExpr.HirCatch cc : catches) {
            LoweringTargets.CatchTarget ct = cc.target();
            if (ct != null
                    && (ct.catchesBabble()
                            || ct.catchesNonErrorAny()
                            || ct.catchesJavaThrowable()
                            || ct.skipsThrownException())) {
                needsThrownDispatcher = true;
                break;
            }
        }

        Label[] catchLabels = new Label[catches.size()];
        for (int i = 0; i < catches.size(); i++) {
            catchLabels[i] = new Label();
        }
        Label thrownExHandler = needsThrownDispatcher ? new Label() : null;

        // Register catch handlers for tryBody segments (innermost tries already registered theirs).
        if (needsThrownDispatcher) {
            for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                mv.visitTryCatchBlock(
                        tryRange.rangeStarts.get(seg),
                        tryRange.rangeEnds.get(seg),
                        thrownExHandler,
                        RUNTIME + "ThrownException");
            }
        }
        for (int i = 0; i < catches.size(); i++) {
            LoweringTargets.CatchTarget ct = catches.get(i).target();
            if (ct != null) {
                for (String jvmType : ct.jvmCatchTypes()) {
                    for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                        mv.visitTryCatchBlock(
                                tryRange.rangeStarts.get(seg),
                                tryRange.rangeEnds.get(seg),
                                catchLabels[i],
                                jvmType);
                    }
                }
            }
        }

        // 3. Emit ThrownException dispatcher (preserves exact source order across Java & Babble
        // catches).
        if (needsThrownDispatcher) {
            mv.visitLabel(thrownExHandler);
            // Stack: [ThrownException]
            int teLocal = c.newLocal();
            mv.visitVarInsn(ASTORE, teLocal);
            int valLocal = -1;
            boolean guaranteedHandled = false;
            for (int i = 0; i < catches.size(); i++) {
                LoweringTargets.CatchTarget ct = catches.get(i).target();
                if (ct == null) {
                    continue;
                }
                if (ct.catchesJavaThrowable()) {
                    mv.visitVarInsn(ALOAD, teLocal);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesNonErrorAny()) {
                    mv.visitVarInsn(ALOAD, teLocal);
                    mv.visitMethodInsn(
                            INVOKEVIRTUAL,
                            RUNTIME + "ThrownException",
                            "value",
                            "()Ljava/lang/Object;",
                            false);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesBabble()) {
                    if (valLocal < 0) {
                        valLocal = c.newLocal();
                        mv.visitVarInsn(ALOAD, teLocal);
                        mv.visitMethodInsn(
                                INVOKEVIRTUAL,
                                RUNTIME + "ThrownException",
                                "value",
                                "()Ljava/lang/Object;",
                                false);
                        mv.visitVarInsn(ASTORE, valLocal);
                    }
                    Label nextClause = new Label();
                    Label matchedClause = new Label();
                    List<String> directJvms = resolveBabbleCatchJvms(ct.babbleTargets());
                    if (!directJvms.isEmpty()) {
                        for (String jvm : directJvms) {
                            mv.visitVarInsn(ALOAD, valLocal);
                            mv.visitTypeInsn(INSTANCEOF, jvm);
                            mv.visitJumpInsn(IFNE, matchedClause);
                        }
                        mv.visitJumpInsn(GOTO, nextClause);
                    } else if (ct.typeDescriptor() != null) {
                        mv.visitVarInsn(ALOAD, valLocal);
                        ConstantPool.pushString(mv, ct.typeDescriptor());
                        mv.visitMethodInsn(
                                INVOKESTATIC,
                                RUNTIME + "Types",
                                "matches",
                                "(Ljava/lang/Object;Ljava/lang/String;)Z",
                                false);
                        mv.visitJumpInsn(IFNE, matchedClause);
                        mv.visitJumpInsn(GOTO, nextClause);
                    } else {
                        mv.visitJumpInsn(GOTO, nextClause);
                    }
                    mv.visitLabel(matchedClause);
                    mv.visitVarInsn(ALOAD, valLocal);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    mv.visitLabel(nextClause);
                }
            }
            if (!guaranteedHandled) {
                // No catch clause matched the ThrownException: run finally (if any) or rethrow.
                mv.visitVarInsn(ALOAD, teLocal);
                if (hasNonEmptyFinally) {
                    mv.visitJumpInsn(GOTO, finallyAbnormal);
                } else {
                    mv.visitInsn(ATHROW);
                }
            }
        }

        // 4. Emit catch clauses (each covered by finallyAbnormal if hasNonEmptyFinally).
        List<MethodEmitContext.ActiveFinally> catchRanges = new ArrayList<>();
        for (int i = 0; i < catches.size(); i++) {
            HirExpr.HirCatch cc = catches.get(i);
            mv.visitLabel(catchLabels[i]);
            // Stack: [Object boundValue]
            int boundTmp = -1;
            if (cc.var() != null) {
                boundTmp = c.newLocal();
                mv.visitVarInsn(ASTORE, boundTmp);
            } else {
                mv.visitInsn(POP);
            }
            Label catchSegStart = new Label();
            mv.visitLabel(catchSegStart);
            MethodEmitContext.ActiveFinally catchRange =
                    hasNonEmptyFinally
                            ? new MethodEmitContext.ActiveFinally(
                                    t, c.loopTargets.size(), catchSegStart, true)
                            : null;
            if (catchRange != null) {
                c.activeFinallies.push(catchRange);
            }
            try {
                final int boundSlot = boundTmp;
                if (!cc.declaredVars().isEmpty()) {
                    emitter.inFrameScope(() -> {
                        emitter.declareVariables(cc.declaredVars());
                        if (cc.var() != null) {
                            mv.visitVarInsn(ALOAD, boundSlot);
                            emitter.initVariable(cc.var(), PrimitiveKind.NONE);
                        }
                        emitter.statements(cc.body());
                    });
                } else if (cc.opensNamespaceScope()) {
                    mv.visitVarInsn(ALOAD, c.nsLocal);
                    emitter.getStringArray(cc.declaredNames());
                    mv.visitMethodInsn(
                            INVOKEVIRTUAL,
                            NAMESPACE,
                            "child",
                            "([Ljava/lang/String;)L" + NAMESPACE + ";",
                            false);
                    int ns = c.newLocal();
                    mv.visitVarInsn(ASTORE, ns);
                    emitter.withNamespace(ns, () -> emitter.statements(cc.body()));
                } else {
                    emitter.statements(cc.body());
                }
                emitter.widenStackRank(cc.wideningRank());
                emitter.convertStack(c.stackKind, targetKind);
                storeKindOn(mv, targetKind, resultLocal);
            } finally {
                if (catchRange != null) {
                    c.activeFinallies.pop();
                    catchRange.close(mv);
                    catchRanges.add(catchRange);
                }
            }
            if (hasNonEmptyFinally) {
                emitTryFinallyBlock(t);
            }
            mv.visitJumpInsn(GOTO, tryEnd);
        }

        // 5. Emit abnormal finally handler with automatic addSuppressed when !finallyNothrow
        // (BIP028-D6).
        if (hasNonEmptyFinally) {
            for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                mv.visitTryCatchBlock(
                        tryRange.rangeStarts.get(seg),
                        tryRange.rangeEnds.get(seg),
                        finallyAbnormal,
                        null);
            }
            for (MethodEmitContext.ActiveFinally cr : catchRanges) {
                for (int seg = 0; seg < cr.rangeStarts.size(); seg++) {
                    mv.visitTryCatchBlock(
                            cr.rangeStarts.get(seg), cr.rangeEnds.get(seg), finallyAbnormal, null);
                }
            }
            mv.visitLabel(finallyAbnormal);
            // Stack: [Throwable primary]
            int primaryLocal = c.newLocal();
            mv.visitVarInsn(ASTORE, primaryLocal);
            if (t.finallyNothrow()) {
                emitTryFinallyBlock(t);
            } else {
                Label finStart = new Label();
                Label finEnd = new Label();
                Label suppressHandler = new Label();
                Label rethrowLabel = new Label();
                mv.visitLabel(finStart);
                emitTryFinallyBlock(t);
                mv.visitLabel(finEnd);
                mv.visitJumpInsn(GOTO, rethrowLabel);

                mv.visitLabel(suppressHandler);
                // Stack: [Throwable secondary]
                mv.visitVarInsn(ALOAD, primaryLocal);
                mv.visitInsn(SWAP);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        RUNTIME + "Throwables",
                        "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V",
                        false);
                mv.visitLabel(rethrowLabel);
                mv.visitTryCatchBlock(finStart, finEnd, suppressHandler, "java/lang/Throwable");
            }
            mv.visitVarInsn(ALOAD, primaryLocal);
            mv.visitInsn(ATHROW);
        }

        // 6. Merge point: load result.
        mv.visitLabel(tryEnd);
        loadKindOn(mv, targetKind, resultLocal);
        c.stackKind = targetKind;
    }

    List<String> resolveBabbleCatchJvms(List<LoweringTargets.IsTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<String> jvms = new ArrayList<>(targets.size());
        for (LoweringTargets.IsTarget target : targets) {
            String jvm = controlOps.resolveIsTargetJvm(target);
            if (jvm == null) {
                return List.of();
            }
            jvms.add(jvm);
        }
        return jvms;
    }
}
