package org.babblelang.ir.hir;

import org.babblelang.analysis.PrimitiveKind;

import java.util.List;

/**
 * Structural and semantic invariant verifier for {@link HirModule} (BIP014-I1).
 *
 * <p>Runs automatically whenever {@code babble.verify=true} (including all Maven compiled test
 * suites) after HIR construction and after every optimization pass.
 */
public final class HirVerifier {
    private HirVerifier() {
    }

    public static void verify(HirModule module) {
        if (module == null) {
            throw new IllegalStateException("HirVerifier : null HirModule");
        }
        verifyStatements(module, module.topLevelStatements(), false, 0);
        for (HirFunction fn : module.functions().values()) {
            for (HirParam param : fn.params()) {
                if (param.defaultValue() != null) {
                    verifyExpr(module, param.defaultValue(), true, 0);
                }
            }
            verifyStatements(module, fn.body(), true, 0);
        }
        for (HirClass cls : module.classes().values()) {
            for (HirParam param : cls.params()) {
                if (param.defaultValue() != null) {
                    verifyExpr(module, param.defaultValue(), false, 0);
                }
            }
            verifyStatements(module, cls.body(), false, 0);
            if (cls.staticBlock() != null) {
                verifyStatements(module, cls.staticBlock().body(), false, 0);
            }
        }
        for (HirInterface iface : module.interfaces().values()) {
            verifyStatements(module, iface.body(), false, 0);
            if (iface.staticBlock() != null) {
                verifyStatements(module, iface.staticBlock().body(), false, 0);
            }
        }
    }

    private static void verifyStatements(HirModule module, List<HirExpr> stmts, boolean inFunction, int loopDepth) {
        for (HirExpr stmt : stmts) {
            verifyExpr(module, stmt, inFunction, loopDepth);
        }
    }

    private static void verifyExpr(HirModule module, HirExpr expr, boolean inFunction, int loopDepth) {
        if (expr == null) {
            throw new IllegalStateException("HirVerifier : null HirExpr");
        }
        ExprMeta meta = expr.meta();
        if (meta == null) {
            throw new IllegalStateException("HirVerifier : null ExprMeta on " + expr);
        }
        switch (expr) {
            case HirExpr.Const c -> verifyConst(c);
            case HirExpr.IntParseFallback f -> {
                if (f.text() == null) {
                    throw new IllegalStateException("HirVerifier : null IntParseFallback text");
                }
            }
            case HirExpr.CoreCollectionRef r -> {
                if (r.collectionKind() == null) {
                    throw new IllegalStateException("HirVerifier : null CoreCollectionRef kind");
                }
            }
            case HirExpr.CoreExceptionRef r -> {
            }
            case HirExpr.VarRead v -> {
                if (v.var() == null) {
                    throw new IllegalStateException("HirVerifier : null VarRead var");
                }
            }
            case HirExpr.NameRead n -> {
                if (n.name() == null) {
                    throw new IllegalStateException("HirVerifier : null NameRead name");
                }
            }
            case HirExpr.ThisRef t -> {
                if (t.enclosingClassId() >= 0 && module.clazz(t.enclosingClassId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown enclosingClassId " + t.enclosingClassId());
                }
            }
            case HirExpr.RecurseRef r -> {
            }
            case HirExpr.Def d -> {
                if (d.value() != null) {
                    verifyExpr(module, d.value(), inFunction, loopDepth);
                }
            }
            case HirExpr.VarAssign a -> {
                if (a.var() == null) {
                    throw new IllegalStateException("HirVerifier : null VarAssign var");
                }
                verifyExpr(module, a.value(), inFunction, loopDepth);
            }
            case HirExpr.NameAssign a -> verifyExpr(module, a.value(), inFunction, loopDepth);
            case HirExpr.ThisFieldAssign a -> {
                verifyExpr(module, a.receiver(), inFunction, loopDepth);
                verifyExpr(module, a.value(), inFunction, loopDepth);
                if (a.classId() >= 0 && module.clazz(a.classId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown classId " + a.classId());
                }
            }
            case HirExpr.JavaFieldAssign a -> {
                verifyExpr(module, a.receiver(), inFunction, loopDepth);
                verifyExpr(module, a.value(), inFunction, loopDepth);
            }
            case HirExpr.DynamicFieldAssign a -> {
                verifyExpr(module, a.receiver(), inFunction, loopDepth);
                verifyExpr(module, a.value(), inFunction, loopDepth);
            }
            case HirExpr.ThisFieldGet g -> {
                verifyExpr(module, g.receiver(), inFunction, loopDepth);
                if (g.classId() >= 0 && module.clazz(g.classId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown classId " + g.classId());
                }
            }
            case HirExpr.JavaFieldGet g -> verifyExpr(module, g.receiver(), inFunction, loopDepth);
            case HirExpr.ArrayCreate c -> verifyStatements(module, c.args(), inFunction, loopDepth);
            case HirExpr.ArrayOp op -> {
                verifyExpr(module, op.receiver(), inFunction, loopDepth);
                verifyStatements(module, op.args(), inFunction, loopDepth);
            }
            case HirExpr.ModuleExportGet g -> verifyExpr(module, g.receiver(), inFunction, loopDepth);
            case HirExpr.DynamicSelect s -> verifyExpr(module, s.receiver(), inFunction, loopDepth);
            case HirExpr.Unary u -> verifyExpr(module, u.operand(), inFunction, loopDepth);
            case HirExpr.Binary b -> {
                verifyExpr(module, b.left(), inFunction, loopDepth);
                verifyExpr(module, b.right(), inFunction, loopDepth);
            }
            case HirExpr.Range r -> {
                verifyExpr(module, r.start(), inFunction, loopDepth);
                verifyExpr(module, r.end(), inFunction, loopDepth);
            }
            case HirExpr.Logic l -> {
                verifyExpr(module, l.left(), inFunction, loopDepth);
                verifyExpr(module, l.right(), inFunction, loopDepth);
            }
            case HirExpr.Is i -> verifyExpr(module, i.left(), inFunction, loopDepth);
            case HirExpr.As a -> verifyExpr(module, a.left(), inFunction, loopDepth);
            case HirExpr.NonNull n -> verifyExpr(module, n.operand(), inFunction, loopDepth);
            case HirExpr.SpreadAdapt s -> verifyExpr(module, s.value(), inFunction, loopDepth);
            case HirExpr.JavaSamAdapt j -> verifyExpr(module, j.value(), inFunction, loopDepth);
            case HirExpr.Block b -> verifyStatements(module, b.statements(), inFunction, loopDepth);
            case HirExpr.If i -> {
                verifyExpr(module, i.cond(), inFunction, loopDepth);
                verifyExpr(module, i.thenBranch(), inFunction, loopDepth);
                if (i.elseBranch() != null) {
                    verifyExpr(module, i.elseBranch(), inFunction, loopDepth);
                }
            }
            case HirExpr.While w -> {
                verifyExpr(module, w.cond(), inFunction, loopDepth);
                verifyExpr(module, w.body(), inFunction, loopDepth + 1);
            }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    verifyExpr(module, f.countedRange().start(), inFunction, loopDepth);
                    verifyExpr(module, f.countedRange().end(), inFunction, loopDepth);
                    if (f.countedRange().step() != null) {
                        verifyExpr(module, f.countedRange().step(), inFunction, loopDepth);
                    }
                } else {
                    verifyExpr(module, f.iterable(), inFunction, loopDepth);
                }
                verifyStatements(module, f.body(), inFunction, loopDepth + 1);
            }
            case HirExpr.Try t -> {
                if (t.catches().isEmpty() && !t.hasFinally()) {
                    throw new IllegalStateException("HirVerifier : try without catch or finally at line " + meta.line());
                }
                verifyStatements(module, t.tryBody(), inFunction, loopDepth);
                for (HirExpr.HirCatch cc : t.catches()) {
                    verifyStatements(module, cc.body(), inFunction, loopDepth);
                }
                verifyStatements(module, t.finallyBody(), inFunction, loopDepth);
            }
            case HirExpr.Break b -> {
                if (loopDepth <= 0) {
                    throw new IllegalStateException("HirVerifier : break outside loop at line " + meta.line());
                }
                if (b.value() != null) {
                    verifyExpr(module, b.value(), inFunction, loopDepth);
                }
            }
            case HirExpr.Continue c -> {
                if (loopDepth <= 0) {
                    throw new IllegalStateException("HirVerifier : continue outside loop at line " + meta.line());
                }
            }
            case HirExpr.Return r -> {
                if (!inFunction) {
                    throw new IllegalStateException("HirVerifier : return outside function at line " + meta.line());
                }
                verifyExpr(module, r.value(), inFunction, loopDepth);
            }
            case HirExpr.Throw th -> {
                if (th.value() == null) {
                    throw new IllegalStateException("HirVerifier : null Throw value at line " + meta.line());
                }
                verifyExpr(module, th.value(), inFunction, loopDepth);
            }
            case HirExpr.CoreCollectionCall c -> verifyCallArgs(module, c.args(), inFunction, loopDepth);
            case HirExpr.SuperCall c -> verifyCallArgs(module, c.args(), inFunction, loopDepth);
            case HirExpr.SelfRecurseCall c -> {
                if (module.function(c.functionId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown SelfRecurseCall functionId " + c.functionId());
                }
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.SelfTailCall c -> {
                // HirEmitter checks that it is in the body of function functionId.
                if (!inFunction || module.function(c.functionId()) == null) {
                    throw new IllegalStateException("HirVerifier : SelfTailCall outside of its function #" + c.functionId()
                            + " at line " + meta.line());
                }
                verifyCallArgs(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.DirectBabbleCall c -> {
                if (c.externalTarget() == null && module.function(c.targetFunctionId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown DirectBabbleCall targetFunctionId " + c.targetFunctionId());
                }
                verifyExpr(module, c.callee(), inFunction, loopDepth);
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.DirectThisMethodCall c -> {
                if (c.externalClassJvmName() == null) {
                    if (module.clazz(c.classId()) == null) {
                        throw new IllegalStateException("HirVerifier : unknown DirectThisMethodCall classId " + c.classId());
                    }
                    if (module.function(c.targetFunctionId()) == null) {
                        throw new IllegalStateException("HirVerifier : unknown DirectThisMethodCall targetFunctionId " + c.targetFunctionId());
                    }
                } else if (c.externalSignature() == null) {
                    throw new IllegalStateException("HirVerifier : external DirectThisMethodCall missing signature for " + c.memberName());
                }
                verifyExpr(module, c.receiver(), inFunction, loopDepth);
                verifyCallArgs(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.InterfaceCall c -> {
                verifyExpr(module, c.receiver(), inFunction, loopDepth);
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.JavaConstructorCall c -> {
                verifyExpr(module, c.callee(), inFunction, loopDepth);
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.JavaMethodCall c -> {
                verifyExpr(module, c.receiver(), inFunction, loopDepth);
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.CoreMethodCall c -> {
                verifyExpr(module, c.receiver(), inFunction, loopDepth);
                verifyStatements(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.SafeCall c -> {
                verifyExpr(module, c.receiver(), inFunction, loopDepth);
                verifyCallArgs(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.DynamicCall c -> {
                verifyExpr(module, c.callee(), inFunction, loopDepth);
                verifyCallArgs(module, c.args(), inFunction, loopDepth);
            }
            case HirExpr.MakeOverloadSet m -> verifyStatements(module, m.operands(), inFunction, loopDepth);
            case HirExpr.OverloadSelect s -> verifyExpr(module, s.set(), inFunction, loopDepth);
            case HirExpr.OverloadDispatch d -> {
                verifyExpr(module, d.callee(), inFunction, loopDepth);
                verifyStatements(module, d.args(), inFunction, loopDepth);
                for (HirExpr.OverloadBranch b : d.branches()) {
                    if (b.directFunctionId() >= 0 && module.function(b.directFunctionId()) == null) {
                        throw new IllegalStateException("HirVerifier : unknown OverloadBranch directFunctionId " + b.directFunctionId());
                    }
                }
            }
            case HirExpr.FunctionRef f -> {
                if (module.function(f.functionId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown FunctionRef functionId " + f.functionId());
                }
            }
            case HirExpr.ClassExpr c -> {
                if (module.clazz(c.classId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown ClassExpr classId " + c.classId());
                }
            }
            case HirExpr.InterfaceExpr i -> {
                if (module.iface(i.interfaceId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown InterfaceExpr interfaceId " + i.interfaceId());
                }
            }
            case HirExpr.TypeApply t -> verifyExpr(module, t.inner(), inFunction, loopDepth);
            case HirExpr.RecordNew r -> {
                if (r.classId() >= 0 && module.clazz(r.classId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown RecordNew classId " + r.classId());
                }
                if (r.classJvmName() == null) {
                    throw new IllegalStateException("HirVerifier : null RecordNew classJvmName");
                }
                verifyExpr(module, r.classRef(), inFunction, loopDepth);
                verifyStatements(module, r.args(), inFunction, loopDepth);
            }
            case HirExpr.RecordReuse r -> {
                if (r.classId() >= 0 && module.clazz(r.classId()) == null) {
                    throw new IllegalStateException("HirVerifier : unknown RecordReuse classId " + r.classId());
                }
                verifyExpr(module, r.target(), inFunction, loopDepth);
                verifyStatements(module, r.args(), inFunction, loopDepth);
            }
            case HirExpr.Move m -> verifyExpr(module, m.operand(), inFunction, loopDepth);
            case HirExpr.Freeze f -> verifyExpr(module, f.operand(), inFunction, loopDepth);
        }
    }

    private static void verifyCallArgs(HirModule module, List<HirCallArg> args, boolean inFunction, int loopDepth) {
        for (HirCallArg arg : args) {
            verifyExpr(module, arg.value(), inFunction, loopDepth);
        }
    }

    private static void verifyConst(HirExpr.Const c) {
        Object v = c.value();
        PrimitiveKind k = c.meta().kind();
        if (v == null && k != PrimitiveKind.NONE) {
            throw new IllegalStateException("HirVerifier : null Const has primitive kind " + k);
        }
        if (v instanceof String && k != PrimitiveKind.NONE) {
            throw new IllegalStateException("HirVerifier : String Const has primitive kind " + k);
        }
    }
}
