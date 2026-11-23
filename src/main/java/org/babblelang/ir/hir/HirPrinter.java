package org.babblelang.ir.hir;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;

import java.util.List;

/**
 * Human-readable deterministic textual printer for {@link HirModule} (BIP014-I1).
 *
 * <p>Used by {@code -Dbabble.dumpIr} and golden IR assertions in {@code OptimizerTestCase}.
 */
public final class HirPrinter {
    private HirPrinter() {
    }

    public static String print(HirModule module) {
        StringBuilder sb = new StringBuilder();
        sb.append("module ").append(module.className()).append(" (").append(module.sourceName()).append(")\n");
        if (!module.topLevelStatements().isEmpty()) {
            sb.append("  top-level:\n");
            for (HirExpr stmt : module.topLevelStatements()) {
                sb.append("    ");
                printExpr(sb, stmt, 2);
                sb.append('\n');
            }
        }
        for (HirFunction fn : module.functions().values()) {
            sb.append("  fn #").append(fn.id()).append(' ')
                    .append(fn.name() != null ? fn.name() : "<anon>")
                    .append(" [").append(fn.jvmMethodName()).append("] (");
            for (int i = 0; i < fn.params().size(); i++) {
                if (i > 0) sb.append(", ");
                HirParam p = fn.params().get(i);
                sb.append(p.name()).append(':').append(kindTag(p.kind()));
                if (p.defaultValue() != null) {
                    sb.append('=');
                    printExpr(sb, p.defaultValue(), 2);
                }
            }
            sb.append(") -> ").append(kindTag(fn.returnKind()));
            String fx = fn.effects().toString();
            if (!fx.isEmpty()) {
                sb.append(" {").append(fx).append('}');
            }
            sb.append('\n');
            for (HirExpr stmt : fn.body()) {
                sb.append("    ");
                printExpr(sb, stmt, 2);
                sb.append('\n');
            }
        }
        for (HirClass cls : module.classes().values()) {
            sb.append("  ").append(cls.isObject() ? "object" : (cls.shape().isValue() ? "record" : "class"))
                    .append(" #").append(cls.id()).append(' ').append(cls.shape().name());
            if (cls.jvmName() != null) {
                sb.append(" [").append(cls.jvmName()).append(']');
            }
            sb.append('\n');
            for (HirExpr stmt : cls.body()) {
                sb.append("    ");
                printExpr(sb, stmt, 2);
                sb.append('\n');
            }
        }
        for (HirInterface iface : module.interfaces().values()) {
            sb.append("  interface #").append(iface.id()).append(' ').append(iface.shape().name())
                    .append(" [").append(iface.jvmName()).append("]\n");
            for (HirExpr stmt : iface.body()) {
                sb.append("    ");
                printExpr(sb, stmt, 2);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static String printExpr(HirExpr expr) {
        StringBuilder sb = new StringBuilder();
        printExpr(sb, expr, 0);
        return sb.toString();
    }

    private static void printExpr(StringBuilder sb, HirExpr expr, int depth) {
        if (expr == null) {
            sb.append("null");
            return;
        }
        switch (expr) {
            case HirExpr.Const c -> {
                if (c.value() == null) {
                    sb.append("null");
                } else if (c.value() instanceof String s) {
                    sb.append('"').append(escape(s)).append('"');
                } else if (c.value() instanceof Character ch) {
                    sb.append('\'').append(escape(String.valueOf(ch))).append('\'');
                } else {
                    sb.append(c.value()).append(':').append(kindTag(c.meta().kind()));
                }
            }
            case HirExpr.IntParseFallback f -> sb.append("int-parse(\"").append(f.text()).append("\")");
            case HirExpr.CoreCollectionRef r -> sb.append("core-col(").append(r.collectionKind()).append(')');
            case HirExpr.VarRead v -> sb.append("var(").append(v.var().name()).append('#').append(v.var().id()).append(')');
            case HirExpr.NameRead n -> sb.append("name(").append(n.name()).append(')');
            case HirExpr.ThisRef t -> sb.append("this");
            case HirExpr.RecurseRef r -> sb.append("recurse");
            case HirExpr.Def d -> {
                sb.append("(def ").append(d.name());
                if (d.var() != null) {
                    sb.append('#').append(d.var().id());
                }
                if (d.value() != null) {
                    sb.append(' ');
                    printExpr(sb, d.value(), depth + 1);
                }
                sb.append(')');
            }
            case HirExpr.VarAssign a -> {
                sb.append("(set ").append(a.var().name()).append('#').append(a.var().id());
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                sb.append(' ');
                printExpr(sb, a.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.NameAssign a -> {
                sb.append("(set-name ").append(a.name());
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                sb.append(' ');
                printExpr(sb, a.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.ThisFieldAssign a -> {
                sb.append("(set-this-field ").append(a.memberName());
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                sb.append(' ');
                printExpr(sb, a.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.JavaFieldAssign a -> {
                sb.append("(set-jfield ").append(a.target().field().getName()).append(' ');
                printExpr(sb, a.receiver(), depth + 1);
                sb.append(' ');
                printExpr(sb, a.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.DynamicFieldAssign a -> {
                sb.append("(set-field ");
                printExpr(sb, a.receiver(), depth + 1);
                sb.append('.').append(a.memberName()).append(' ');
                printExpr(sb, a.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.ThisFieldGet g -> sb.append("(get-this-field ").append(g.memberName()).append(')');
            case HirExpr.JavaFieldGet g -> {
                sb.append("(get-jfield ").append(g.target().field().getDeclaringClass().getSimpleName())
                        .append('.').append(g.target().field().getName());
                if (!g.pureStaticReceiver()) {
                    sb.append(' ');
                    printExpr(sb, g.receiver(), depth + 1);
                }
                sb.append(')');
            }
            case HirExpr.ArrayCreate c -> {
                sb.append("(array-new ").append(c.kind());
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.ArrayOp op -> {
                sb.append("(array-op ").append(op.kind()).append(' ');
                printExpr(sb, op.receiver(), depth + 1);
                printArgs(sb, op.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.ModuleExportGet g -> {
                sb.append("(export ");
                printExpr(sb, g.receiver(), depth + 1);
                sb.append('.').append(g.target().name()).append(')');
            }
            case HirExpr.DynamicSelect s -> {
                sb.append(s.safe() ? "(safe-select " : "(select ");
                printExpr(sb, s.receiver(), depth + 1);
                sb.append('.').append(s.memberName()).append(')');
            }
            case HirExpr.Unary u -> {
                sb.append('(').append(u.op() == HirExpr.UnaryOp.NEGATE ? "neg " : "not ");
                printExpr(sb, u.operand(), depth + 1);
                sb.append(')');
            }
            case HirExpr.Binary b -> {
                sb.append('(').append(b.op().symbol()).append(':').append(kindTag(b.meta().kind())).append(' ');
                printExpr(sb, b.left(), depth + 1);
                sb.append(' ');
                printExpr(sb, b.right(), depth + 1);
                sb.append(')');
            }
            case HirExpr.Logic l -> {
                sb.append('(').append(l.op() == HirExpr.LogicOp.AND ? "and " : "or ");
                printExpr(sb, l.left(), depth + 1);
                sb.append(' ');
                printExpr(sb, l.right(), depth + 1);
                sb.append(')');
            }
            case HirExpr.Is i -> {
                sb.append("(is ");
                printExpr(sb, i.left(), depth + 1);
                sb.append(' ');
                if (i.builtin() != null) {
                    sb.append(i.builtin());
                } else if (!i.intersectionTargets().isEmpty()) {
                    for (int k = 0; k < i.intersectionTargets().size(); k++) {
                        if (k > 0) sb.append('&');
                        LoweringTargets.IsTarget t = i.intersectionTargets().get(k);
                        sb.append(t.jvmInternalName() != null ? t.jvmInternalName() : t.typeId());
                    }
                } else {
                    sb.append(i.target() != null ? i.target().typeId() : i.descriptor());
                }
                sb.append(')');
            }
            case HirExpr.As a -> {
                sb.append("(as ");
                printExpr(sb, a.left(), depth + 1);
                sb.append(' ').append(a.targetDescriptor()).append(')');
            }
            case HirExpr.NonNull n -> {
                sb.append("(nonnull ");
                printExpr(sb, n.operand(), depth + 1);
                sb.append(')');
            }
            case HirExpr.SpreadAdapt s -> {
                sb.append("(spread-adapt ").append(s.adapt().kind()).append(' ');
                printExpr(sb, s.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.JavaSamAdapt j -> {
                sb.append("(java-sam ").append(j.target().samInterface().getSimpleName());
                for (Class<?> m : j.target().markers()) {
                    sb.append('&').append(m.getSimpleName());
                }
                sb.append(' ');
                printExpr(sb, j.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.Block b -> {
                sb.append("(block");
                for (HirExpr s : b.statements()) {
                    sb.append(' ');
                    printExpr(sb, s, depth + 1);
                }
                sb.append(')');
            }
            case HirExpr.If i -> {
                sb.append("(if ");
                printExpr(sb, i.cond(), depth + 1);
                sb.append(' ');
                printExpr(sb, i.thenBranch(), depth + 1);
                if (i.elseBranch() != null) {
                    sb.append(' ');
                    printExpr(sb, i.elseBranch(), depth + 1);
                }
                sb.append(')');
            }
            case HirExpr.While w -> {
                sb.append("(while ");
                printExpr(sb, w.cond(), depth + 1);
                sb.append(' ');
                printExpr(sb, w.body(), depth + 1);
                sb.append(')');
            }
            case HirExpr.For f -> {
                sb.append("(for ");
                sb.append(f.loopVar() != null ? f.loopVar().name() + "#" + f.loopVar().id() : "_").append(" in ");
                printExpr(sb, f.iterable(), depth + 1);
                sb.append(" (block");
                for (HirExpr s : f.body()) {
                    sb.append(' ');
                    printExpr(sb, s, depth + 1);
                }
                sb.append("))");
            }
            case HirExpr.Break b -> {
                sb.append("(break");
                if (b.value() != null) {
                    sb.append(' ');
                    printExpr(sb, b.value(), depth + 1);
                }
                sb.append(')');
            }
            case HirExpr.Continue c -> sb.append("(continue)");
            case HirExpr.Return r -> {
                sb.append("(return ");
                printExpr(sb, r.value(), depth + 1);
                sb.append(')');
            }
            case HirExpr.CoreCollectionCall c -> {
                sb.append("(core-new ").append(c.collectionKind());
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.SuperCall c -> {
                sb.append("(super.").append(c.memberName());
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.SelfRecurseCall c -> {
                sb.append("(self-recurse #").append(c.functionId());
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.SelfTailCall c -> {
                sb.append("(tail-call #").append(c.functionId());
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.DirectBabbleCall c -> {
                sb.append("(call-fn #").append(c.targetFunctionId());
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.DirectThisMethodCall c -> {
                sb.append("(call-this.").append(c.memberName()).append(" #").append(c.targetFunctionId());
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.InterfaceCall c -> {
                sb.append("(call-iface ");
                printExpr(sb, c.receiver(), depth + 1);
                sb.append('.').append(c.memberName());
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.JavaConstructorCall c -> {
                sb.append("(jnew ").append(c.target().constructor().getDeclaringClass().getSimpleName());
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.JavaMethodCall c -> {
                sb.append("(jcall ").append(c.target().method().getDeclaringClass().getSimpleName())
                        .append('.').append(c.target().method().getName());
                if (!c.target().isStatic()) {
                    sb.append(' ');
                    printExpr(sb, c.receiver(), depth + 1);
                }
                printArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.SafeCall c -> {
                sb.append("(safe-call ");
                printExpr(sb, c.receiver(), depth + 1);
                sb.append("?.").append(c.memberName());
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.DynamicCall c -> {
                sb.append("(call ");
                printExpr(sb, c.callee(), depth + 1);
                printCallArgs(sb, c.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.MakeOverloadSet m -> {
                sb.append("(overload");
                printArgs(sb, m.operands(), depth + 1);
                sb.append(')');
            }
            case HirExpr.OverloadSelect s -> {
                sb.append("(overload-select #").append(s.index()).append(' ');
                printExpr(sb, s.set(), depth + 1);
                sb.append(')');
            }
            case HirExpr.OverloadDispatch d -> {
                sb.append("(overload-dispatch ");
                printExpr(sb, d.callee(), depth + 1);
                printArgs(sb, d.args(), depth + 1);
                sb.append(')');
            }
            case HirExpr.FunctionRef f -> sb.append("(fn-ref #").append(f.functionId()).append(')');
            case HirExpr.ClassExpr c -> sb.append("(class-ref #").append(c.classId()).append(')');
            case HirExpr.InterfaceExpr i -> sb.append("(iface-ref #").append(i.interfaceId()).append(')');
            case HirExpr.TypeApply t -> {
                sb.append("(type-apply ");
                printExpr(sb, t.inner(), depth + 1);
                sb.append(')');
            }
        }
    }

    private static void printArgs(StringBuilder sb, List<HirExpr> args, int depth) {
        for (HirExpr arg : args) {
            sb.append(' ');
            printExpr(sb, arg, depth);
        }
    }

    private static void printCallArgs(StringBuilder sb, List<HirCallArg> args, int depth) {
        for (HirCallArg arg : args) {
            sb.append(' ');
            if (arg.name() != null) {
                sb.append(arg.name()).append('=');
            }
            printExpr(sb, arg.value(), depth);
        }
    }

    private static String kindTag(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "byte";
            case SHORT -> "short";
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BOOL -> "bool";
            case CHAR -> "char";
            case NONE -> "obj";
        };
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\"", "\\\"");
    }
}
