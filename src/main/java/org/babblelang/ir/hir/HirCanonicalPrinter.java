package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.IrCanonicalSupport;
import org.babblelang.ir.IrTarget;

/**
 * Canonical, preamble-independent, human-readable printer for {@link HirModule} ({@code BIP029-I1},
 * {@code BIP029-D5}, {@code BIP029-D7}).
 *
 * <p>Unlike {@link HirPrinter#print(HirModule)}, which prints raw module-global IDs and JVM-mangled
 * names on single lines for low-level debugging, {@code HirCanonicalPrinter}:
 *
 * <ul>
 *   <li>Scopes output to a specific {@link IrTarget} (function, method, class/record/interface,
 *       top-level, or full module).
 *   <li>Normalizes variable IDs locally per target: unique user names omit {@code #id} ({@code
 *       var(x)}), shadowed locals receive local {@code #1}/{@code #2} suffixes, and
 *       global-ID-suffixed synthetic temps ({@code $licm17}, {@code $try24}, {@code $sr_p12}) are
 *       renumbered locally from {@code 0}.
 *   <li>Resolves numeric function, class, and interface references to symbolic names and strips
 *       generated {@code Script$N$} prefixes from type descriptors.
 *   <li>Pretty-prints compound control flow ({@code block}, {@code if}, {@code while}, {@code for},
 *       {@code try}/{@code catch}/{@code finally}) with deterministic 2-space indentation.
 * </ul>
 */
public final class HirCanonicalPrinter {
    private static final Pattern SYNTH_TEMP =
            Pattern.compile("^(\\$[a-zA-Z][a-zA-Z_]*[a-zA-Z])(\\d+)$");

    private HirCanonicalPrinter() {}

    public static String print(HirModule module, IrTarget target) {
        Map<String, String> typeNames = buildTypeNameMap(module);
        StringBuilder sb = new StringBuilder();
        switch (target) {
            case IrTarget.Fn fnTarget -> {
                HirFunction fn = IrCanonicalSupport.resolveFunction(module, fnTarget.name(), "HIR");
                printFunction(sb, module, fn, typeNames, 0);
            }
            case IrTarget.Method methodTarget -> {
                List<HirFunction> methods =
                        IrCanonicalSupport.resolveMethods(
                                module, methodTarget.ownerName(), methodTarget.methodName(), "HIR");
                for (int i = 0; i < methods.size(); i++) {
                    if (i > 0) {
                        sb.append('\n');
                    }
                    printFunction(sb, module, methods.get(i), typeNames, 0);
                }
            }
            case IrTarget.TypeDef typeTarget -> printTypeDef(sb, module, typeTarget, typeNames, 0);
            case IrTarget.TopLevel ignored -> printTopLevel(sb, module, typeNames, 0);
            case IrTarget.Module ignored -> printModule(sb, module, typeNames);
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------ structural printers

    private static void printModule(
            StringBuilder sb, HirModule module, Map<String, String> typeNames) {
        sb.append("module ").append(IrCanonicalSupport.moduleLabel(module)).append('\n');
        if (!module.topLevelStatements().isEmpty()) {
            printTopLevel(sb, module, typeNames, 1);
            sb.append('\n');
        }
        Set<Integer> ownedFns = collectOwnedFunctionIds(module);
        for (HirFunction fn : module.functions().values()) {
            if (!ownedFns.contains(fn.id())) {
                printFunction(sb, module, fn, typeNames, 1);
                sb.append('\n');
            }
        }
        for (HirClass cls : module.classes().values()) {
            printClass(sb, module, cls, typeNames, 1);
            sb.append('\n');
        }
        for (HirInterface iface : module.interfaces().values()) {
            printInterface(sb, module, iface, typeNames, 1);
            sb.append('\n');
        }
    }

    private static void printTopLevel(
            StringBuilder sb, HirModule module, Map<String, String> typeNames, int indent) {
        ScopeNormalizer norm = new ScopeNormalizer(module, typeNames);
        norm.scanList(module.topLevelStatements());
        norm.finalizeNames();
        indent(sb, indent);
        sb.append("top-level:");
        if (module.topLevelStatements().isEmpty()) {
            sb.append(" (empty)");
            return;
        }
        sb.append('\n');
        for (int i = 0; i < module.topLevelStatements().size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            indent(sb, indent + 1);
            printExpr(sb, module.topLevelStatements().get(i), norm, indent + 1);
        }
    }

    private static void printTypeDef(
            StringBuilder sb,
            HirModule module,
            IrTarget.TypeDef target,
            Map<String, String> typeNames,
            int indent) {
        switch (target.kind()) {
            case CLASS -> {
                HirClass cls = IrCanonicalSupport.findClassByName(module, target.name(), "HIR");
                if (cls == null || (cls.shape().isValue() && !cls.isObject())) {
                    throw new IllegalArgumentException(
                            "No HIR class named '"
                                    + target.name()
                                    + "' (available: "
                                    + IrCanonicalSupport.availableTargets(module)
                                    + ")");
                }
                printClass(sb, module, cls, typeNames, indent);
            }
            case RECORD -> {
                HirClass cls = IrCanonicalSupport.findClassByName(module, target.name(), "HIR");
                if (cls == null || !cls.shape().isValue() || cls.isObject()) {
                    throw new IllegalArgumentException(
                            "No HIR record named '"
                                    + target.name()
                                    + "' (available: "
                                    + IrCanonicalSupport.availableTargets(module)
                                    + ")");
                }
                printClass(sb, module, cls, typeNames, indent);
            }
            case INTERFACE -> {
                HirInterface iface =
                        IrCanonicalSupport.findInterfaceByName(module, target.name(), "HIR");
                if (iface == null) {
                    throw new IllegalArgumentException(
                            "No HIR interface named '"
                                    + target.name()
                                    + "' (available: "
                                    + IrCanonicalSupport.availableTargets(module)
                                    + ")");
                }
                printInterface(sb, module, iface, typeNames, indent);
            }
        }
    }

    private static void printClass(
            StringBuilder sb,
            HirModule module,
            HirClass cls,
            Map<String, String> typeNames,
            int indent) {
        ScopeNormalizer norm = new ScopeNormalizer(module, typeNames);
        norm.scanVars(cls.declaredVars());
        norm.scanParams(cls.params());
        norm.scanList(cls.body());
        if (cls.staticBlock() != null) {
            norm.scanList(cls.staticBlock().body());
        }
        norm.finalizeNames();

        indent(sb, indent);
        sb.append(typeKindLabel(cls)).append(' ').append(cls.shape().name());
        if (!cls.params().isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < cls.params().size(); i++) {
                if (i > 0) sb.append(", ");
                HirParam p = cls.params().get(i);
                sb.append(p.name()).append(':').append(kindTag(p.kind()));
                if (p.defaultValue() != null) {
                    sb.append('=');
                    printExpr(sb, p.defaultValue(), norm, indent + 1);
                }
            }
            sb.append(')');
        }
        for (HirExpr stmt : cls.body()) {
            sb.append('\n');
            indent(sb, indent + 1);
            printExpr(sb, stmt, norm, indent + 1);
        }
        if (cls.staticBlock() != null && !cls.staticBlock().body().isEmpty()) {
            sb.append('\n');
            indent(sb, indent + 1);
            sb.append("static:");
            for (HirExpr stmt : cls.staticBlock().body()) {
                sb.append('\n');
                indent(sb, indent + 2);
                printExpr(sb, stmt, norm, indent + 2);
            }
        }
        for (HirFunction method : ownedMethodsOf(module, cls)) {
            sb.append('\n');
            printFunction(sb, module, method, typeNames, indent + 1);
        }
    }

    private static void printInterface(
            StringBuilder sb,
            HirModule module,
            HirInterface iface,
            Map<String, String> typeNames,
            int indent) {
        ScopeNormalizer norm = new ScopeNormalizer(module, typeNames);
        norm.scanList(iface.body());
        if (iface.staticBlock() != null) {
            norm.scanList(iface.staticBlock().body());
        }
        norm.finalizeNames();

        indent(sb, indent);
        sb.append("interface ").append(iface.shape().name());
        for (HirExpr stmt : iface.body()) {
            sb.append('\n');
            indent(sb, indent + 1);
            printExpr(sb, stmt, norm, indent + 1);
        }
        for (HirFunction method : ownedDefaultMethodsOf(module, iface)) {
            sb.append('\n');
            printFunction(sb, module, method, typeNames, indent + 1);
        }
    }

    private static void printFunction(
            StringBuilder sb,
            HirModule module,
            HirFunction fn,
            Map<String, String> typeNames,
            int indent) {
        ScopeNormalizer norm = new ScopeNormalizer(module, typeNames);
        norm.scanVars(fn.declaredVars());
        norm.scanParams(fn.params());
        norm.scanList(fn.body());
        norm.finalizeNames();

        indent(sb, indent);
        sb.append("fn ").append(displayFunctionHeaderName(module, fn)).append(" (");
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) sb.append(", ");
            HirParam p = fn.params().get(i);
            sb.append(p.name()).append(':').append(kindTag(p.kind()));
            if (p.defaultValue() != null) {
                sb.append('=');
                printExpr(sb, p.defaultValue(), norm, indent + 1);
            }
        }
        sb.append(") -> ").append(kindTag(fn.returnKind()));
        String fx = fn.effects().toString();
        if (!fx.isEmpty()) {
            sb.append(" {").append(fx).append('}');
        }
        for (HirExpr stmt : fn.body()) {
            sb.append('\n');
            indent(sb, indent + 1);
            printExpr(sb, stmt, norm, indent + 1);
        }
    }

    private static String displayFunctionHeaderName(HirModule module, HirFunction fn) {
        if (fn.ownerClassId() >= 0) {
            HirClass owner = module.clazz(fn.ownerClassId());
            String member = fn.memberName() != null ? fn.memberName() : fn.name();
            if (owner != null && member != null) {
                return owner.shape().name() + "." + member;
            }
        }
        return fn.name() != null && !fn.name().isEmpty() ? fn.name() : "<anon>";
    }

    private static List<HirFunction> ownedMethodsOf(HirModule module, HirClass cls) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.addAll(cls.methodFunctionIds().values());
        for (List<Integer> ovs : cls.overloadMethodFunctionIds().values()) {
            ids.addAll(ovs);
        }
        for (HirFunction fn : module.functions().values()) {
            if (fn.ownerClassId() == cls.id()) {
                ids.add(fn.id());
            }
        }
        List<HirFunction> result = new ArrayList<>();
        for (int id : ids) {
            HirFunction fn = module.function(id);
            if (fn != null) {
                result.add(fn);
            }
        }
        return result;
    }

    private static List<HirFunction> ownedDefaultMethodsOf(HirModule module, HirInterface iface) {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.addAll(iface.defaultFunctionIds().values());
        for (List<Integer> ovs : iface.defaultOverloadFunctionIds().values()) {
            ids.addAll(ovs);
        }
        List<HirFunction> result = new ArrayList<>();
        for (int id : ids) {
            HirFunction fn = module.function(id);
            if (fn != null) {
                result.add(fn);
            }
        }
        return result;
    }

    private static Set<Integer> collectOwnedFunctionIds(HirModule module) {
        Set<Integer> owned = new LinkedHashSet<>();
        for (HirClass cls : module.classes().values()) {
            for (HirFunction fn : ownedMethodsOf(module, cls)) {
                owned.add(fn.id());
            }
        }
        for (HirInterface iface : module.interfaces().values()) {
            for (HirFunction fn : ownedDefaultMethodsOf(module, iface)) {
                owned.add(fn.id());
            }
        }
        return owned;
    }

    // ------------------------------------------------------------------ expression printer

    private static void printExpr(
            StringBuilder sb, HirExpr expr, ScopeNormalizer norm, int indent) {
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
            case HirExpr.IntParseFallback f ->
                    sb.append("int-parse(\"").append(escape(f.text())).append("\")");
            case HirExpr.CoreCollectionRef r ->
                    sb.append("core-col(").append(r.collectionKind()).append(')');
            case HirExpr.CoreExceptionRef ignored -> sb.append("core-exception");
            case HirExpr.VarRead v -> sb.append("var(").append(norm.varName(v.var())).append(')');
            case HirExpr.NameRead n -> sb.append("name(").append(n.name()).append(')');
            case HirExpr.ThisRef ignored -> sb.append("this");
            case HirExpr.RecurseRef ignored -> sb.append("recurse");
            case HirExpr.Def d -> {
                sb.append("(def ").append(d.var() != null ? norm.varName(d.var()) : d.name());
                if (d.value() != null) {
                    if (isMultiline(d.value())) {
                        sb.append('\n');
                        indent(sb, indent + 1);
                        printExpr(sb, d.value(), norm, indent + 1);
                    } else {
                        sb.append(' ');
                        printExpr(sb, d.value(), norm, indent + 1);
                    }
                }
                sb.append(')');
            }
            case HirExpr.VarAssign a -> {
                sb.append("(set ").append(norm.varName(a.var()));
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                if (isMultiline(a.value())) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    printExpr(sb, a.value(), norm, indent + 1);
                } else {
                    sb.append(' ');
                    printExpr(sb, a.value(), norm, indent + 1);
                }
                sb.append(')');
            }
            case HirExpr.NameAssign a -> {
                sb.append("(set-name ").append(a.name());
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                sb.append(' ');
                printExpr(sb, a.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.ThisFieldAssign a -> {
                sb.append("(set-this-field ").append(a.memberName());
                if (a.operator() != null) sb.append(' ').append(a.operator()).append('=');
                sb.append(' ');
                printExpr(sb, a.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.JavaFieldAssign a -> {
                sb.append("(set-jfield ").append(a.target().field().getName()).append(' ');
                printExpr(sb, a.receiver(), norm, indent + 1);
                sb.append(' ');
                printExpr(sb, a.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.DynamicFieldAssign a -> {
                sb.append("(set-field ");
                printExpr(sb, a.receiver(), norm, indent + 1);
                sb.append('.').append(a.memberName()).append(' ');
                printExpr(sb, a.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.ThisFieldGet g ->
                    sb.append("(get-this-field ").append(g.memberName()).append(')');
            case HirExpr.JavaFieldGet g -> {
                sb.append("(get-jfield ")
                        .append(g.target().field().getDeclaringClass().getSimpleName())
                        .append('.')
                        .append(g.target().field().getName());
                if (!g.pureStaticReceiver()) {
                    sb.append(' ');
                    printExpr(sb, g.receiver(), norm, indent + 1);
                }
                sb.append(')');
            }
            case HirExpr.ArrayCreate c -> {
                sb.append("(array-new ").append(c.kind());
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.ArrayOp op -> {
                sb.append("(array-op ").append(op.kind()).append(' ');
                printExpr(sb, op.receiver(), norm, indent + 1);
                printArgs(sb, op.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.ModuleExportGet g -> {
                sb.append("(export ");
                printExpr(sb, g.receiver(), norm, indent + 1);
                sb.append('.').append(g.target().name()).append(')');
            }
            case HirExpr.DynamicSelect s -> {
                sb.append(s.safe() ? "(safe-select " : "(select ");
                printExpr(sb, s.receiver(), norm, indent + 1);
                sb.append('.').append(s.memberName()).append(')');
            }
            case HirExpr.Unary u -> {
                sb.append('(').append(u.op() == HirExpr.UnaryOp.NEGATE ? "neg " : "not ");
                printExpr(sb, u.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Binary b -> {
                sb.append('(')
                        .append(b.op().symbol())
                        .append(':')
                        .append(kindTag(b.meta().kind()))
                        .append(' ');
                printExpr(sb, b.left(), norm, indent + 1);
                sb.append(' ');
                printExpr(sb, b.right(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Range r -> {
                sb.append("(range ").append(r.inclusive() ? ".. " : "..< ");
                printExpr(sb, r.start(), norm, indent + 1);
                sb.append(' ');
                printExpr(sb, r.end(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Logic l -> {
                sb.append('(').append(l.op() == HirExpr.LogicOp.AND ? "and " : "or ");
                printExpr(sb, l.left(), norm, indent + 1);
                sb.append(' ');
                printExpr(sb, l.right(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Is i -> {
                sb.append("(is ");
                printExpr(sb, i.left(), norm, indent + 1);
                sb.append(' ');
                if (i.builtin() != null) {
                    sb.append(i.builtin());
                } else if (!i.intersectionTargets().isEmpty()) {
                    for (int k = 0; k < i.intersectionTargets().size(); k++) {
                        if (k > 0) sb.append('&');
                        LoweringTargets.IsTarget t = i.intersectionTargets().get(k);
                        sb.append(
                                norm.normalizeType(
                                        t.typeId() != null ? t.typeId() : t.jvmInternalName()));
                    }
                } else {
                    sb.append(
                            norm.normalizeType(
                                    i.target() != null ? i.target().typeId() : i.descriptor()));
                }
                sb.append(')');
            }
            case HirExpr.As a -> {
                sb.append("(as ");
                printExpr(sb, a.left(), norm, indent + 1);
                sb.append(' ').append(norm.normalizeType(a.targetDescriptor())).append(')');
            }
            case HirExpr.NonNull n -> {
                sb.append("(nonnull ");
                printExpr(sb, n.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.SpreadAdapt s -> {
                sb.append("(spread-adapt ").append(s.adapt().kind()).append(' ');
                printExpr(sb, s.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.JavaSamAdapt j -> {
                sb.append("(java-sam ").append(j.target().samInterface().getSimpleName());
                for (Class<?> m : j.target().markers()) {
                    sb.append('&').append(m.getSimpleName());
                }
                sb.append(' ');
                printExpr(sb, j.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Block b -> {
                if (b.statements().isEmpty()) {
                    sb.append("(block)");
                } else if (b.statements().size() == 1 && !isMultiline(b.statements().get(0))) {
                    sb.append("(block ");
                    printExpr(sb, b.statements().get(0), norm, indent + 1);
                    sb.append(')');
                } else {
                    sb.append("(block");
                    for (HirExpr s : b.statements()) {
                        sb.append('\n');
                        indent(sb, indent + 1);
                        printExpr(sb, s, norm, indent + 1);
                    }
                    sb.append(')');
                }
            }
            case HirExpr.If i -> {
                sb.append("(if ");
                printExpr(sb, i.cond(), norm, indent + 1);
                if (isMultiline(i.thenBranch())
                        || (i.elseBranch() != null && isMultiline(i.elseBranch()))) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    printExpr(sb, i.thenBranch(), norm, indent + 1);
                    if (i.elseBranch() != null) {
                        sb.append('\n');
                        indent(sb, indent + 1);
                        printExpr(sb, i.elseBranch(), norm, indent + 1);
                    }
                } else {
                    sb.append(' ');
                    printExpr(sb, i.thenBranch(), norm, indent + 1);
                    if (i.elseBranch() != null) {
                        sb.append(' ');
                        printExpr(sb, i.elseBranch(), norm, indent + 1);
                    }
                }
                sb.append(')');
            }
            case HirExpr.Match m -> {
                sb.append("(match ");
                printExpr(sb, m.scrutinee(), norm, indent + 1);
                for (HirExpr.MatchArm arm : m.arms()) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    sb.append("(case ");
                    printPattern(sb, arm.pattern(), norm, indent + 2);
                    if (arm.guard() != null) {
                        sb.append(" if ");
                        printExpr(sb, arm.guard(), norm, indent + 2);
                    }
                    sb.append(' ');
                    printExpr(sb, arm.body(), norm, indent + 2);
                    sb.append(')');
                }
                if (m.elseArm() != null) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    sb.append("(else");
                    if (m.elseArm().var() != null) {
                        sb.append(' ').append(norm.varName(m.elseArm().var()));
                    }
                    sb.append(' ');
                    printExpr(sb, m.elseArm().body(), norm, indent + 2);
                    sb.append(')');
                }
                sb.append(')');
            }
            case HirExpr.While w -> {
                sb.append("(while ");
                printExpr(sb, w.cond(), norm, indent + 1);
                sb.append('\n');
                indent(sb, indent + 1);
                printExpr(sb, w.body(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    HirExpr.CountedRangeLoop cr = f.countedRange();
                    sb.append("(for-range ");
                    sb.append(f.loopVar() != null ? norm.varName(f.loopVar()) : "_");
                    sb.append(cr.inclusive() ? " .. " : " ..< ");
                    printExpr(sb, cr.start(), norm, indent + 1);
                    sb.append(' ');
                    printExpr(sb, cr.end(), norm, indent + 1);
                    if (cr.step() != null) {
                        sb.append(" step ");
                        printExpr(sb, cr.step(), norm, indent + 1);
                    }
                    if (cr.descending()) {
                        sb.append(" reversed");
                    }
                } else {
                    sb.append("(for ");
                    sb.append(f.loopVar() != null ? norm.varName(f.loopVar()) : "_").append(" in ");
                    printExpr(sb, f.iterable(), norm, indent + 1);
                }
                sb.append('\n');
                indent(sb, indent + 1);
                sb.append("(block");
                for (HirExpr s : f.body()) {
                    sb.append('\n');
                    indent(sb, indent + 2);
                    printExpr(sb, s, norm, indent + 2);
                }
                sb.append("))");
            }
            case HirExpr.Try t -> {
                sb.append("(try");
                sb.append('\n');
                indent(sb, indent + 1);
                sb.append("(block");
                for (HirExpr s : t.tryBody()) {
                    sb.append('\n');
                    indent(sb, indent + 2);
                    printExpr(sb, s, norm, indent + 2);
                }
                sb.append(')');
                for (HirExpr.HirCatch cc : t.catches()) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    sb.append("(catch ");
                    sb.append(cc.var() != null ? norm.varName(cc.var()) : "_");
                    if (cc.target() != null && cc.target().typeDescriptor() != null) {
                        sb.append(':').append(norm.normalizeType(cc.target().typeDescriptor()));
                    }
                    for (HirExpr s : cc.body()) {
                        sb.append('\n');
                        indent(sb, indent + 2);
                        printExpr(sb, s, norm, indent + 2);
                    }
                    sb.append(')');
                }
                if (t.hasFinally()) {
                    sb.append('\n');
                    indent(sb, indent + 1);
                    sb.append("(finally");
                    for (HirExpr s : t.finallyBody()) {
                        sb.append('\n');
                        indent(sb, indent + 2);
                        printExpr(sb, s, norm, indent + 2);
                    }
                    sb.append(')');
                }
                sb.append(')');
            }
            case HirExpr.Break b -> {
                sb.append("(break");
                if (b.value() != null) {
                    sb.append(' ');
                    printExpr(sb, b.value(), norm, indent + 1);
                }
                sb.append(')');
            }
            case HirExpr.Continue ignored -> sb.append("(continue)");
            case HirExpr.Return r -> {
                sb.append("(return ");
                printExpr(sb, r.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Throw th -> {
                sb.append("(throw ");
                printExpr(sb, th.value(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.CoreCollectionCall c -> {
                sb.append("(core-new ").append(c.collectionKind());
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.SuperCall c -> {
                sb.append("(super.").append(c.memberName());
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.SelfRecurseCall c -> {
                sb.append("(self-recurse ").append(norm.fnRef(c.functionId()));
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.SelfTailCall c -> {
                sb.append("(tail-call ").append(norm.fnRef(c.functionId()));
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.DirectBabbleCall c -> {
                String targetName =
                        c.externalTarget() != null
                                ? c.externalTarget().moduleName()
                                        + "#"
                                        + c.externalTarget().exportName()
                                : norm.fnRef(c.targetFunctionId());
                sb.append("(call-fn ").append(targetName);
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.DirectThisMethodCall c -> {
                if (c.receiver() != null) {
                    sb.append("(call-method ");
                    printExpr(sb, c.receiver(), norm, indent + 1);
                    sb.append('.').append(c.memberName());
                } else {
                    sb.append("(call-this.").append(c.memberName());
                }
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.InterfaceCall c -> {
                sb.append("(call-iface ");
                printExpr(sb, c.receiver(), norm, indent + 1);
                sb.append('.').append(c.memberName());
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.JavaConstructorCall c -> {
                sb.append("(jnew ")
                        .append(c.target().constructor().getDeclaringClass().getSimpleName());
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.JavaMethodCall c -> {
                sb.append("(jcall ")
                        .append(c.target().method().getDeclaringClass().getSimpleName())
                        .append('.')
                        .append(c.target().method().getName());
                if (!c.target().isStatic()) {
                    sb.append(' ');
                    printExpr(sb, c.receiver(), norm, indent + 1);
                }
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.CoreMethodCall c -> {
                sb.append("(core-call ")
                        .append(c.coreKind())
                        .append('.')
                        .append(c.memberName())
                        .append(' ');
                printExpr(sb, c.receiver(), norm, indent + 1);
                printArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.SafeCall c -> {
                sb.append("(safe-call ");
                printExpr(sb, c.receiver(), norm, indent + 1);
                sb.append("?.").append(c.memberName());
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.DynamicCall c -> {
                sb.append("(call ");
                printExpr(sb, c.callee(), norm, indent + 1);
                printCallArgs(sb, c.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.MakeOverloadSet m -> {
                sb.append("(overload");
                printArgs(sb, m.operands(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.OverloadSelect s -> {
                sb.append("(overload-select #").append(s.index()).append(' ');
                printExpr(sb, s.set(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.OverloadDispatch d -> {
                sb.append("(overload-dispatch ");
                printExpr(sb, d.callee(), norm, indent + 1);
                printArgs(sb, d.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.FunctionRef f ->
                    sb.append("(fn-ref ").append(norm.fnRef(f.functionId())).append(')');
            case HirExpr.ClassExpr c ->
                    sb.append("(class-ref ").append(norm.classRef(c.classId())).append(')');
            case HirExpr.InterfaceExpr i ->
                    sb.append("(iface-ref ").append(norm.ifaceRef(i.interfaceId())).append(')');
            case HirExpr.TypeApply t -> {
                sb.append("(type-apply ");
                printExpr(sb, t.inner(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.RecordNew r -> {
                sb.append("(record-new ").append(norm.recordName(r.classId(), r.classTypeId()));
                printArgs(sb, r.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.RecordReuse r -> {
                sb.append("(record-reuse ");
                printExpr(sb, r.target(), norm, indent + 1);
                sb.append(' ').append(norm.recordName(r.classId(), r.classTypeId()));
                printArgs(sb, r.args(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Move m -> {
                sb.append("(move ");
                printExpr(sb, m.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Freeze f -> {
                sb.append("(freeze ");
                printExpr(sb, f.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Narrow n -> {
                if (n.narrowKind() == HirExpr.NarrowKind.NON_NULL) {
                    sb.append("(narrow.nonnull ");
                } else {
                    sb.append("(narrow.is[")
                            .append(norm.normalizeType(n.targetDesc()))
                            .append("] ");
                }
                printExpr(sb, n.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Convert cv -> {
                if (cv.targetKind() == PrimitiveKind.NONE) {
                    sb.append("(box ");
                } else if (cv.operand().meta().kind() == PrimitiveKind.NONE) {
                    sb.append("(unbox[").append(kindTag(cv.targetKind())).append("] ");
                } else {
                    sb.append("(convert[").append(kindTag(cv.targetKind())).append("] ");
                }
                printExpr(sb, cv.operand(), norm, indent + 1);
                sb.append(')');
            }
            case HirExpr.Widen w -> {
                PrimitiveKind target = PrimitiveKind.ofNumericRank(w.wideningRank());
                sb.append("(widen[")
                        .append(target != PrimitiveKind.NONE ? kindTag(target) : w.wideningRank())
                        .append("] ");
                printExpr(sb, w.operand(), norm, indent + 1);
                sb.append(')');
            }
        }
    }

    private static boolean isMultiline(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        return switch (expr) {
            case HirExpr.Block b ->
                    b.statements().size() > 1
                            || (b.statements().size() == 1 && isMultiline(b.statements().get(0)));
            case HirExpr.While ignored -> true;
            case HirExpr.For ignored -> true;
            case HirExpr.Try ignored -> true;
            case HirExpr.Match ignored -> true;
            case HirExpr.If i -> isMultiline(i.thenBranch()) || isMultiline(i.elseBranch());
            default -> false;
        };
    }

    private static void printPattern(
            StringBuilder sb, HirPattern pattern, ScopeNormalizer norm, int indent) {
        switch (pattern) {
            case HirPattern.Const c -> {
                if (c.isEnum()) {
                    sb.append(c.enumClass().getSimpleName())
                            .append('.')
                            .append(c.enumConstantName());
                } else if (c.value() == null) {
                    sb.append("null");
                } else if (c.value() instanceof String s) {
                    sb.append('"').append(escape(s)).append('"');
                } else if (c.value() instanceof Character ch) {
                    sb.append('\'').append(escape(String.valueOf(ch))).append('\'');
                } else {
                    sb.append(c.value()).append(':').append(kindTag(c.kind()));
                }
            }
            case HirPattern.Wildcard ignored -> sb.append('_');
            case HirPattern.ValueExpr v -> printExpr(sb, v.expr(), norm, indent);
            case HirPattern.TypeTest t -> {
                sb.append("(is ");
                if (t.builtin() != null) {
                    sb.append(t.builtin());
                } else if (t.target() != null && t.target().typeId() != null) {
                    sb.append(norm.normalizeType(t.target().typeId()));
                } else if (t.descriptor() != null) {
                    sb.append(norm.normalizeType(t.descriptor()));
                } else {
                    sb.append("?");
                }
                sb.append(')');
            }
            case HirPattern.Bind b -> {
                sb.append("(bind ").append(b.var() != null ? norm.varName(b.var()) : "_");
                if (b.inner() != null) {
                    sb.append(' ');
                    printPattern(sb, b.inner(), norm, indent);
                }
                sb.append(')');
            }
            case HirPattern.Or o -> {
                sb.append("(or");
                for (HirPattern alt : o.alternatives()) {
                    sb.append(' ');
                    printPattern(sb, alt, norm, indent);
                }
                sb.append(')');
            }
            case HirPattern.Relational r -> {
                sb.append('(').append(r.op().symbol()).append(' ');
                printExpr(sb, r.rhs(), norm, indent);
                sb.append(')');
            }
            case HirPattern.RangeTest r -> {
                sb.append(r.inclusive() ? "(range-pat .. " : "(range-pat ..< ");
                printExpr(sb, r.lo(), norm, indent);
                sb.append(' ');
                printExpr(sb, r.hi(), norm, indent);
                if (r.step() != null) {
                    sb.append(" step ");
                    printExpr(sb, r.step(), norm, indent);
                }
                sb.append(')');
            }
            case HirPattern.Membership m -> {
                sb.append("(in ");
                printExpr(sb, m.container(), norm, indent);
                sb.append(')');
            }
            case HirPattern.RecordDestructure rd -> {
                sb.append("(record-pat ");
                if (rd.isJavaRecord()) {
                    sb.append(rd.javaRecordClass().getSimpleName());
                } else {
                    sb.append(norm.normalizeType(rd.babbleTypeId()));
                }
                for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                    sb.append(' ').append(rd.fieldNames().get(i)).append('=');
                    printPattern(sb, rd.fieldPatterns().get(i), norm, indent);
                }
                sb.append(')');
            }
            case HirPattern.SequenceDestructure sd -> {
                sb.append(sd.isArray() ? "(array-pat" : "(list-pat");
                for (HirPattern ep : sd.elemPatterns()) {
                    sb.append(' ');
                    printPattern(sb, ep, norm, indent);
                }
                if (sd.hasRest()) {
                    sb.append(" ...");
                    if (sd.restVar() != null) {
                        sb.append(norm.varName(sd.restVar()));
                    }
                }
                sb.append(')');
            }
        }
    }

    private static void printArgs(
            StringBuilder sb, List<HirExpr> args, ScopeNormalizer norm, int indent) {
        for (HirExpr arg : args) {
            sb.append(' ');
            printExpr(sb, arg, norm, indent);
        }
    }

    private static void printCallArgs(
            StringBuilder sb, List<HirCallArg> args, ScopeNormalizer norm, int indent) {
        for (HirCallArg arg : args) {
            sb.append(' ');
            if (arg.name() != null) {
                sb.append(arg.name()).append('=');
            }
            printExpr(sb, arg.value(), norm, indent);
        }
    }

    // ------------------------------------------------------------------ local identifier
    // normalizer

    private static final class ScopeNormalizer {
        private final HirModule module;
        private final Map<String, String> typeNames;
        private final LinkedHashMap<Integer, String> seenVars = new LinkedHashMap<>();
        private final Map<Integer, String> resolvedVars = new HashMap<>();
        private final LinkedHashSet<Integer> seenFnRefs = new LinkedHashSet<>();
        private final Map<Integer, String> resolvedFnRefs = new HashMap<>();

        ScopeNormalizer(HirModule module, Map<String, String> typeNames) {
            this.module = module;
            this.typeNames = typeNames;
        }

        void scanVars(List<HirVar> vars) {
            for (HirVar v : vars) {
                recordVar(v);
            }
        }

        void scanParams(List<HirParam> params) {
            for (HirParam p : params) {
                if (p.defaultValue() != null) {
                    scanExpr(p.defaultValue());
                }
            }
        }

        void scanList(List<HirExpr> exprs) {
            for (HirExpr e : exprs) {
                scanExpr(e);
            }
        }

        private void recordVar(HirVar v) {
            if (v != null && !seenVars.containsKey(v.id())) {
                seenVars.put(v.id(), v.name() != null ? v.name() : "$v");
            }
        }

        private void scanCallArgs(List<HirCallArg> args) {
            for (HirCallArg a : args) {
                scanExpr(a.value());
            }
        }

        private void scanPattern(HirPattern pattern) {
            if (pattern == null) {
                return;
            }
            switch (pattern) {
                case HirPattern.Const ignored -> {}
                case HirPattern.Wildcard ignored -> {}
                case HirPattern.TypeTest ignored -> {}
                case HirPattern.ValueExpr v -> scanExpr(v.expr());
                case HirPattern.Bind b -> {
                    recordVar(b.var());
                    scanPattern(b.inner());
                }
                case HirPattern.Or o -> {
                    for (HirPattern alt : o.alternatives()) {
                        scanPattern(alt);
                    }
                }
                case HirPattern.Relational r -> scanExpr(r.rhs());
                case HirPattern.RangeTest r -> {
                    scanExpr(r.lo());
                    scanExpr(r.hi());
                    scanExpr(r.step());
                }
                case HirPattern.Membership m -> scanExpr(m.container());
                case HirPattern.RecordDestructure rd -> {
                    for (HirPattern fp : rd.fieldPatterns()) {
                        scanPattern(fp);
                    }
                }
                case HirPattern.SequenceDestructure sd -> {
                    for (HirPattern ep : sd.elemPatterns()) {
                        scanPattern(ep);
                    }
                    recordVar(sd.restVar());
                }
            }
        }

        private void scanExpr(HirExpr expr) {
            if (expr == null) {
                return;
            }
            switch (expr) {
                case HirExpr.Const ignored -> {}
                case HirExpr.IntParseFallback ignored -> {}
                case HirExpr.CoreCollectionRef ignored -> {}
                case HirExpr.CoreExceptionRef ignored -> {}
                case HirExpr.VarRead v -> recordVar(v.var());
                case HirExpr.NameRead ignored -> {}
                case HirExpr.ThisRef ignored -> {}
                case HirExpr.RecurseRef ignored -> {}
                case HirExpr.Def d -> {
                    recordVar(d.var());
                    scanExpr(d.value());
                }
                case HirExpr.VarAssign a -> {
                    recordVar(a.var());
                    scanExpr(a.value());
                }
                case HirExpr.NameAssign a -> scanExpr(a.value());
                case HirExpr.ThisFieldAssign a -> scanExpr(a.value());
                case HirExpr.JavaFieldAssign a -> {
                    scanExpr(a.receiver());
                    scanExpr(a.value());
                }
                case HirExpr.DynamicFieldAssign a -> {
                    scanExpr(a.receiver());
                    scanExpr(a.value());
                }
                case HirExpr.ThisFieldGet ignored -> {}
                case HirExpr.JavaFieldGet g -> {
                    if (!g.pureStaticReceiver()) scanExpr(g.receiver());
                }
                case HirExpr.ArrayCreate c -> scanList(c.args());
                case HirExpr.ArrayOp op -> {
                    scanExpr(op.receiver());
                    scanList(op.args());
                }
                case HirExpr.ModuleExportGet g -> scanExpr(g.receiver());
                case HirExpr.DynamicSelect s -> scanExpr(s.receiver());
                case HirExpr.Unary u -> scanExpr(u.operand());
                case HirExpr.Binary b -> {
                    scanExpr(b.left());
                    scanExpr(b.right());
                }
                case HirExpr.Range r -> {
                    scanExpr(r.start());
                    scanExpr(r.end());
                }
                case HirExpr.Logic l -> {
                    scanExpr(l.left());
                    scanExpr(l.right());
                }
                case HirExpr.Is i -> scanExpr(i.left());
                case HirExpr.As a -> scanExpr(a.left());
                case HirExpr.NonNull n -> scanExpr(n.operand());
                case HirExpr.SpreadAdapt s -> scanExpr(s.value());
                case HirExpr.JavaSamAdapt j -> scanExpr(j.value());
                case HirExpr.Block b -> {
                    scanVars(b.declaredVars());
                    scanList(b.statements());
                }
                case HirExpr.If i -> {
                    scanExpr(i.cond());
                    scanExpr(i.thenBranch());
                    scanExpr(i.elseBranch());
                }
                case HirExpr.Match m -> {
                    recordVar(m.scrutineeVar());
                    scanExpr(m.scrutinee());
                    for (HirExpr.MatchArm arm : m.arms()) {
                        scanVars(arm.boundVars());
                        scanPattern(arm.pattern());
                        scanExpr(arm.guard());
                        scanExpr(arm.body());
                    }
                    if (m.elseArm() != null) {
                        recordVar(m.elseArm().var());
                        scanExpr(m.elseArm().body());
                    }
                }
                case HirExpr.While w -> {
                    scanExpr(w.cond());
                    scanExpr(w.body());
                }
                case HirExpr.For f -> {
                    recordVar(f.loopVar());
                    if (f.countedRange() != null) {
                        scanExpr(f.countedRange().start());
                        scanExpr(f.countedRange().end());
                        scanExpr(f.countedRange().step());
                    } else {
                        scanExpr(f.iterable());
                    }
                    scanList(f.body());
                }
                case HirExpr.Try t -> {
                    scanList(t.tryBody());
                    for (HirExpr.HirCatch cc : t.catches()) {
                        recordVar(cc.var());
                        scanList(cc.body());
                    }
                    if (t.hasFinally()) {
                        scanList(t.finallyBody());
                    }
                }
                case HirExpr.Break b -> scanExpr(b.value());
                case HirExpr.Continue ignored -> {}
                case HirExpr.Return r -> scanExpr(r.value());
                case HirExpr.Throw th -> scanExpr(th.value());
                case HirExpr.CoreCollectionCall c -> scanCallArgs(c.args());
                case HirExpr.SuperCall c -> scanCallArgs(c.args());
                case HirExpr.SelfRecurseCall c -> {
                    seenFnRefs.add(c.functionId());
                    scanList(c.args());
                }
                case HirExpr.SelfTailCall c -> {
                    seenFnRefs.add(c.functionId());
                    scanCallArgs(c.args());
                }
                case HirExpr.DirectBabbleCall c -> {
                    seenFnRefs.add(c.targetFunctionId());
                    scanList(c.args());
                }
                case HirExpr.DirectThisMethodCall c -> {
                    if (c.receiver() != null) scanExpr(c.receiver());
                    scanCallArgs(c.args());
                }
                case HirExpr.InterfaceCall c -> {
                    scanExpr(c.receiver());
                    scanList(c.args());
                }
                case HirExpr.JavaConstructorCall c -> scanList(c.args());
                case HirExpr.JavaMethodCall c -> {
                    if (!c.target().isStatic()) scanExpr(c.receiver());
                    scanList(c.args());
                }
                case HirExpr.CoreMethodCall c -> {
                    scanExpr(c.receiver());
                    scanList(c.args());
                }
                case HirExpr.SafeCall c -> {
                    scanExpr(c.receiver());
                    scanCallArgs(c.args());
                }
                case HirExpr.DynamicCall c -> {
                    scanExpr(c.callee());
                    scanCallArgs(c.args());
                }
                case HirExpr.MakeOverloadSet m -> scanList(m.operands());
                case HirExpr.OverloadSelect s -> scanExpr(s.set());
                case HirExpr.OverloadDispatch d -> {
                    scanExpr(d.callee());
                    scanList(d.args());
                }
                case HirExpr.FunctionRef f -> seenFnRefs.add(f.functionId());
                case HirExpr.ClassExpr ignored -> {}
                case HirExpr.InterfaceExpr ignored -> {}
                case HirExpr.TypeApply t -> scanExpr(t.inner());
                case HirExpr.RecordNew r -> scanList(r.args());
                case HirExpr.RecordReuse r -> {
                    scanExpr(r.target());
                    scanList(r.args());
                }
                case HirExpr.Move m -> {
                    recordVar(m.var());
                    scanExpr(m.operand());
                }
                case HirExpr.Freeze f -> scanExpr(f.operand());
                case HirExpr.Narrow n -> scanExpr(n.operand());
                case HirExpr.Convert cv -> scanExpr(cv.operand());
                case HirExpr.Widen w -> scanExpr(w.operand());
            }
        }

        void finalizeNames() {
            // 1. Synthetic global-ID-suffixed temps ($licm17 -> $licm0) vs user variables
            Map<String, List<Integer>> synthByStem = new LinkedHashMap<>();
            Map<String, List<Integer>> userByName = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : seenVars.entrySet()) {
                int id = e.getKey();
                String name = e.getValue();
                var m = SYNTH_TEMP.matcher(name);
                if (m.matches()) {
                    synthByStem.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(id);
                } else {
                    userByName.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
                }
            }
            for (Map.Entry<String, List<Integer>> e : synthByStem.entrySet()) {
                String stem = e.getKey();
                List<Integer> ids = e.getValue();
                for (int i = 0; i < ids.size(); i++) {
                    resolvedVars.put(ids.get(i), stem + i);
                }
            }
            for (Map.Entry<String, List<Integer>> e : userByName.entrySet()) {
                String name = e.getKey();
                List<Integer> ids = e.getValue();
                if (ids.size() == 1) {
                    resolvedVars.put(ids.get(0), name);
                } else {
                    for (int i = 0; i < ids.size(); i++) {
                        resolvedVars.put(ids.get(i), name + "#" + (i + 1));
                    }
                }
            }

            // 2. Referenced function names (symbolic; anonymous locally numbered <anon#0>, ...)
            Map<String, List<Integer>> fnsByBase = new LinkedHashMap<>();
            int anonIdx = 0;
            for (int fnId : seenFnRefs) {
                HirFunction fn = module.function(fnId);
                if (fn == null) {
                    resolvedFnRefs.put(fnId, "#" + fnId);
                    continue;
                }
                String base = symbolicFnBase(fn);
                if (base == null) {
                    resolvedFnRefs.put(fnId, "<anon#" + (anonIdx++) + ">");
                } else {
                    fnsByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(fnId);
                }
            }
            for (Map.Entry<String, List<Integer>> e : fnsByBase.entrySet()) {
                List<Integer> ids = e.getValue();
                if (ids.size() == 1) {
                    resolvedFnRefs.put(ids.get(0), e.getKey());
                } else {
                    for (int i = 0; i < ids.size(); i++) {
                        resolvedFnRefs.put(ids.get(i), e.getKey() + "#" + (i + 1));
                    }
                }
            }
        }

        private String symbolicFnBase(HirFunction fn) {
            if (fn.ownerClassId() >= 0) {
                HirClass owner = module.clazz(fn.ownerClassId());
                String member = fn.memberName() != null ? fn.memberName() : fn.name();
                if (owner != null && member != null) {
                    return owner.shape().name() + "." + member;
                }
            }
            if (fn.name() != null && !fn.name().isEmpty()) {
                return fn.name();
            }
            return null;
        }

        String varName(HirVar v) {
            if (v == null) {
                return "_";
            }
            return resolvedVars.getOrDefault(v.id(), v.name() != null ? v.name() : "$v");
        }

        String fnRef(int fnId) {
            return resolvedFnRefs.getOrDefault(fnId, "#" + fnId);
        }

        String classRef(int classId) {
            HirClass cls = module.clazz(classId);
            return cls != null ? cls.shape().name() : "#" + classId;
        }

        String ifaceRef(int ifaceId) {
            HirInterface iface = module.iface(ifaceId);
            return iface != null ? iface.shape().name() : "#" + ifaceId;
        }

        String recordName(int classId, String classTypeId) {
            if (classId >= 0) {
                HirClass cls = module.clazz(classId);
                if (cls != null) {
                    return cls.shape().name();
                }
            }
            return normalizeType(classTypeId);
        }

        String normalizeType(String raw) {
            if (raw == null) {
                return "any";
            }
            String direct = typeNames.get(raw);
            if (direct != null) {
                return direct;
            }
            String cleaned = IrCanonicalSupport.SCRIPT_PREFIX.matcher(raw).replaceAll("");
            cleaned = IrCanonicalSupport.SCRIPT_SELF.matcher(cleaned).replaceAll("<script>");
            cleaned = IrCanonicalSupport.BABBLE_TYPE_ID.matcher(cleaned).replaceAll("$1");
            return cleaned;
        }
    }

    private static Map<String, String> buildTypeNameMap(HirModule module) {
        Map<String, String> map = new HashMap<>();
        for (HirClass cls : module.classes().values()) {
            String simple = cls.shape().name();
            if (cls.shape().id() != null) {
                map.put(cls.shape().id(), simple);
            }
            if (cls.jvmName() != null) {
                map.put(cls.jvmName(), simple);
            }
        }
        for (HirInterface iface : module.interfaces().values()) {
            String simple = iface.shape().name();
            if (iface.shape().id() != null) {
                map.put(iface.shape().id(), simple);
            }
            if (iface.jvmName() != null) {
                map.put(iface.jvmName(), simple);
            }
        }
        for (Map.Entry<String, String> e : module.allClassJvmNames().entrySet()) {
            if (!map.containsKey(e.getKey())) {
                String simple = IrCanonicalSupport.shortJvmType(e.getValue());
                map.put(e.getKey(), simple);
                map.put(e.getValue(), simple);
            }
        }
        for (Map.Entry<String, String> e : module.allInterfaceJvmNames().entrySet()) {
            if (!map.containsKey(e.getKey())) {
                String simple = IrCanonicalSupport.shortJvmType(e.getValue());
                map.put(e.getKey(), simple);
                map.put(e.getValue(), simple);
            }
        }
        return map;
    }

    private static String typeKindLabel(HirClass cls) {
        return IrCanonicalSupport.typeKindLabel(cls);
    }

    private static void indent(StringBuilder sb, int level) {
        IrCanonicalSupport.indent(sb, level);
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
        return IrCanonicalSupport.escapeString(s);
    }
}
