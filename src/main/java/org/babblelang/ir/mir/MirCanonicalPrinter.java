package org.babblelang.ir.mir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.ir.IrCanonicalSupport;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;

/**
 * Canonical, deterministic, preamble-independent printer for {@link MirModule} ({@code BIP014-I3}
 * §6.5, {@code BIP029-I1} §4.3).
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>Reachable basic blocks are numbered {@code bb0..bbN} in deterministic Reverse Post-Order.
 *   <li>SSA values are numbered {@code v0..vN} locally per function in definition order.
 *   <li>Numeric function/class IDs and {@code Script$N$} prefixes are replaced by symbolic names.
 *   <li>Non-lowered functions and top-level scripts emit explicit {@code ; [not-lowered: <reason>]}
 *       markers so fallbacks are never silent.
 * </ul>
 */
public final class MirCanonicalPrinter {
    private MirCanonicalPrinter() {}

    public static String print(MirModule mirModule, IrTarget target) {
        HirModule hir = mirModule.hir();
        StringBuilder sb = new StringBuilder();
        switch (target) {
            case IrTarget.Fn fnTarget -> {
                HirFunction hirFn = IrCanonicalSupport.resolveFunction(hir, fnTarget.name(), "MIR");
                printFunctionTarget(sb, mirModule, hirFn, 0);
            }
            case IrTarget.Method methodTarget -> {
                List<HirFunction> methods =
                        IrCanonicalSupport.resolveMethods(
                                hir, methodTarget.ownerName(), methodTarget.methodName(), "MIR");
                for (int i = 0; i < methods.size(); i++) {
                    if (i > 0) sb.append('\n');
                    printFunctionTarget(sb, mirModule, methods.get(i), 0);
                }
            }
            case IrTarget.TypeDef typeTarget -> printTypeDef(sb, mirModule, typeTarget, 0);
            case IrTarget.TopLevel ignored -> sb.append("top-level ; [not-lowered: top-level]");
            case IrTarget.Module ignored -> printModule(sb, mirModule);
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------ module & typedef printers

    private static void printModule(StringBuilder sb, MirModule mirModule) {
        HirModule hir = mirModule.hir();
        sb.append("module ").append(IrCanonicalSupport.moduleLabel(hir)).append('\n');
        for (HirFunction fn : hir.functions().values()) {
            printFunctionTarget(sb, mirModule, fn, 1);
            sb.append('\n');
        }
    }

    private static void printTypeDef(
            StringBuilder sb, MirModule mirModule, IrTarget.TypeDef target, int indent) {
        HirModule hir = mirModule.hir();
        HirClass cls = IrCanonicalSupport.findClassByName(hir, target.name(), "MIR");
        if (cls != null) {
            IrCanonicalSupport.indent(sb, indent);
            String kind = IrCanonicalSupport.typeKindLabel(cls);
            sb.append(kind).append(' ').append(cls.shape().name()).append(":\n");
            boolean any = false;
            Set<Integer> ids = new LinkedHashSet<>(cls.methodFunctionIds().values());
            for (List<Integer> list : cls.overloadMethodFunctionIds().values()) {
                ids.addAll(list);
            }
            for (int id : ids) {
                HirFunction fn = hir.function(id);
                if (fn != null) {
                    if (any) sb.append('\n');
                    printFunctionTarget(sb, mirModule, fn, indent + 1);
                    any = true;
                }
            }
            if (!any) {
                IrCanonicalSupport.indent(sb, indent + 1);
                sb.append("(no methods)");
            }
            return;
        }
        HirInterface iface = IrCanonicalSupport.findInterfaceByName(hir, target.name(), "MIR");
        if (iface != null) {
            IrCanonicalSupport.indent(sb, indent);
            sb.append("interface ").append(iface.shape().name()).append(":\n");
            boolean any = false;
            Set<Integer> ids = new LinkedHashSet<>(iface.defaultFunctionIds().values());
            for (List<Integer> list : iface.defaultOverloadFunctionIds().values()) {
                ids.addAll(list);
            }
            for (int id : ids) {
                HirFunction fn = hir.function(id);
                if (fn != null) {
                    if (any) sb.append('\n');
                    printFunctionTarget(sb, mirModule, fn, indent + 1);
                    any = true;
                }
            }
            if (!any) {
                IrCanonicalSupport.indent(sb, indent + 1);
                sb.append("(no default methods)");
            }
            return;
        }
        throw new IllegalArgumentException("No MIR type named '" + target.name() + "'");
    }

    // ------------------------------------------------------------------ function printer

    private static void printFunctionTarget(
            StringBuilder sb, MirModule mirModule, HirFunction hirFn, int indent) {
        MirFunction fn = mirModule.function(hirFn.id());
        String displayName = formatFnName(mirModule.hir(), hirFn);
        if (fn == null) {
            String reason = mirModule.fallbackReason(hirFn.id());
            indent(sb, indent);
            sb.append("fn ")
                    .append(displayName)
                    .append(" ; [not-lowered: ")
                    .append(reason != null ? reason : "unknown")
                    .append(']');
            return;
        }
        printFunction(sb, mirModule.hir(), fn, displayName, indent);
    }

    public static String printFunction(HirModule hir, MirFunction fn) {
        HirFunction hirFn = hir.function(fn.hirFunctionId());
        String displayName =
                hirFn != null
                        ? formatFnName(hir, hirFn)
                        : (fn.name() != null ? fn.name() : "<anon>");
        StringBuilder sb = new StringBuilder();
        printFunction(sb, hir, fn, displayName, 0);
        return sb.toString().stripTrailing();
    }

    private static void printFunction(
            StringBuilder sb, HirModule hir, MirFunction fn, String displayName, int indent) {
        MirCfg cfg = MirCfg.analyze(fn);
        List<MirBlock> rpo = cfg.rpo();

        // Assign canonical bb0..bbN and v0..vN numbers deterministically.
        Map<Integer, String> blockNames = new HashMap<>(rpo.size() * 2);
        for (int i = 0; i < rpo.size(); i++) {
            blockNames.put(rpo.get(i).id(), "bb" + i);
        }
        Map<Integer, String> valNames = new HashMap<>();
        int[] valCounter = {0};
        for (MirVal p : fn.params()) {
            if (p != null) {
                valNames.put(p.id(), "v" + (valCounter[0]++));
            }
        }
        for (MirBlock b : rpo) {
            for (MirVal bp : b.params()) {
                valNames.put(bp.id(), "v" + (valCounter[0]++));
            }
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    valNames.put(inst.result().id(), "v" + (valCounter[0]++));
                }
            }
        }

        // Signature header.
        indent(sb, indent);
        sb.append("fn ").append(displayName).append('(');
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) sb.append(", ");
            MirVal p = fn.params().get(i);
            sb.append(valNames.get(p.id())).append(": ").append(MirVal.kindLabel(p.kind()));
            MirEffect.AliasClass alias = fn.valAliases().get(p.id());
            if (alias instanceof MirEffect.AliasClass.UniqueParam) {
                sb.append(" [unique]");
            }
        }
        sb.append(") -> ").append(MirVal.kindLabel(fn.returnKind())).append(':');

        // Basic blocks in RPO.
        for (MirBlock b : rpo) {
            sb.append('\n');
            indent(sb, indent + 1);
            sb.append(blockNames.get(b.id()));
            if (!b.params().isEmpty()) {
                sb.append('(');
                for (int i = 0; i < b.params().size(); i++) {
                    if (i > 0) sb.append(", ");
                    MirVal bp = b.params().get(i);
                    sb.append(valNames.get(bp.id()))
                            .append(": ")
                            .append(MirVal.kindLabel(bp.kind()));
                }
                sb.append(')');
            }
            if (b.handlerEntry()) {
                sb.append(" [handler]");
            }
            if (!b.handlers().isEmpty()) {
                sb.append(" [unwind: ");
                for (int i = 0; i < b.handlers().size(); i++) {
                    if (i > 0) sb.append(", ");
                    MirHandler h = b.handlers().get(i);
                    sb.append(shortJvmType(h.catchJvmType()))
                            .append(" -> ")
                            .append(blockNames.getOrDefault(h.handlerBlockId(), "bb?"));
                }
                sb.append(']');
            }
            sb.append(':');

            for (MirInst inst : b.insts()) {
                sb.append('\n');
                indent(sb, indent + 2);
                printInst(sb, hir, inst, valNames);
            }
            sb.append('\n');
            indent(sb, indent + 2);
            printTerminator(sb, hir, b.terminator(), blockNames, valNames);
        }
    }

    private static void printInst(
            StringBuilder sb, HirModule hir, MirInst inst, Map<Integer, String> vals) {
        if (inst.result() != null) {
            MirVal r = inst.result();
            sb.append(val(vals, r)).append(": ").append(MirVal.kindLabel(r.kind())).append(" = ");
        }
        switch (inst) {
            case MirInst.Const c -> sb.append("const ").append(formatConst(c.value()));
            case MirInst.Copy c -> sb.append("copy ").append(val(vals, c.source()));
            case MirInst.Unary u -> {
                String op = u.op() == HirExpr.UnaryOp.NEGATE ? "neg" : "not";
                sb.append(op)
                        .append(' ')
                        .append(val(vals, u.operand()))
                        .append(" {")
                        .append(u.effect().canonicalTag())
                        .append('}');
            }
            case MirInst.Binary b -> {
                String op =
                        b.stringConcat()
                                ? "strcat"
                                : (b.op() == HirExpr.BinaryOp.FLOOR_DIV
                                        ? "floordiv"
                                        : b.op().name().toLowerCase(Locale.ROOT));
                sb.append(op)
                        .append(' ')
                        .append(val(vals, b.left()))
                        .append(", ")
                        .append(val(vals, b.right()))
                        .append(" {")
                        .append(b.effect().canonicalTag())
                        .append('}');
            }
            case MirInst.Widen w ->
                    sb.append("widen#")
                            .append(w.wideningRank())
                            .append(' ')
                            .append(val(vals, w.operand()));
            case MirInst.Convert c ->
                    sb.append("convert.")
                            .append(MirVal.kindLabel(c.targetKind()))
                            .append(' ')
                            .append(val(vals, c.operand()));
            case MirInst.Narrow n ->
                    sb.append("narrow.")
                            .append(
                                    n.narrowKind() == MirInst.NarrowKind.NON_NULL
                                            ? "nonnull"
                                            : "is")
                            .append(' ')
                            .append(val(vals, n.operand()))
                            .append(" <")
                            .append(sanitizeDescriptor(n.targetDesc()))
                            .append('>');
            case MirInst.CheckCast c -> {
                sb.append("checkcast ").append(val(vals, c.operand()));
                if (c.descriptor() != null) {
                    sb.append(" <").append(sanitizeDescriptor(c.descriptor())).append('>');
                } else if (c.samTarget() != null) {
                    sb.append(" <sam:")
                            .append(c.samTarget().samInterface().getSimpleName())
                            .append('>');
                }
            }
            case MirInst.NonNull n -> sb.append("nonnull ").append(val(vals, n.operand()));
            case MirInst.IsCheck i -> {
                sb.append("is ").append(val(vals, i.operand())).append(" <");
                if (i.builtin() != null) {
                    sb.append(i.builtin());
                } else if (i.descriptor() != null) {
                    sb.append(sanitizeDescriptor(i.descriptor()));
                } else {
                    sb.append("any");
                }
                sb.append('>');
            }
            case MirInst.LoadField lf -> {
                sb.append("load_field ")
                        .append(val(vals, lf.receiver()))
                        .append('.')
                        .append(lf.memberName())
                        .append(" {")
                        .append(lf.effect().canonicalTag())
                        .append('}');
            }
            case MirInst.StoreField sf -> {
                sb.append("store_field ")
                        .append(val(vals, sf.receiver()))
                        .append('.')
                        .append(sf.memberName())
                        .append(", ")
                        .append(val(vals, sf.value()))
                        .append(" {")
                        .append(sf.effect().canonicalTag())
                        .append('}');
            }
            case MirInst.LoadJavaField lj -> {
                Field f = lj.target().field();
                sb.append("load_jfield ");
                if (lj.receiver() != null) {
                    sb.append(val(vals, lj.receiver())).append('.');
                } else {
                    sb.append(f.getDeclaringClass().getSimpleName()).append('.');
                }
                sb.append(f.getName()).append(" {").append(lj.effect().canonicalTag()).append('}');
            }
            case MirInst.StoreJavaField sj -> {
                Field f = sj.target().field();
                sb.append("store_jfield ");
                if (sj.receiver() != null) {
                    sb.append(val(vals, sj.receiver())).append('.');
                } else {
                    sb.append(f.getDeclaringClass().getSimpleName()).append('.');
                }
                sb.append(f.getName())
                        .append(", ")
                        .append(val(vals, sj.value()))
                        .append(" {")
                        .append(sj.effect().canonicalTag())
                        .append('}');
            }
            case MirInst.ArrayGet ag ->
                    sb.append("array_get ")
                            .append(val(vals, ag.array()))
                            .append('[')
                            .append(val(vals, ag.index()))
                            .append("] {")
                            .append(ag.effect().canonicalTag())
                            .append('}');
            case MirInst.ArraySet as ->
                    sb.append("array_set ")
                            .append(val(vals, as.array()))
                            .append('[')
                            .append(val(vals, as.index()))
                            .append("] = ")
                            .append(val(vals, as.value()))
                            .append(" {")
                            .append(as.effect().canonicalTag())
                            .append('}');
            case MirInst.ArrayLen al ->
                    sb.append("array_len ")
                            .append(val(vals, al.array()))
                            .append(" {")
                            .append(al.effect().canonicalTag())
                            .append('}');
            case MirInst.CallBabble cb -> {
                String name;
                if (cb.externalTarget() != null) {
                    name =
                            cb.externalTarget().moduleName()
                                    + "#"
                                    + cb.externalTarget().exportName();
                } else {
                    HirFunction tgt = hir.function(cb.targetFunctionId());
                    name = tgt != null ? formatFnName(hir, tgt) : ("fn#" + cb.targetFunctionId());
                }
                sb.append(cb.selfRecurse() ? "call_self " : "call ").append(name).append('(');
                appendVals(sb, cb.args(), vals);
                sb.append(") {").append(cb.effect().canonicalTag()).append('}');
            }
            case MirInst.CallMethod cm -> {
                sb.append("call_method ")
                        .append(val(vals, cm.receiver()))
                        .append('.')
                        .append(cm.memberName())
                        .append('(');
                appendVals(sb, cm.args(), vals);
                sb.append(") {").append(cm.effect().canonicalTag()).append('}');
            }
            case MirInst.CallInterface ci -> {
                sb.append("call_iface ")
                        .append(val(vals, ci.receiver()))
                        .append('.')
                        .append(ci.memberName())
                        .append('(');
                appendVals(sb, ci.args(), vals);
                sb.append(") {").append(ci.effect().canonicalTag()).append('}');
            }
            case MirInst.CallJava cj -> {
                Method m = cj.target().method();
                sb.append("call_java ");
                if (cj.receiver() != null) {
                    sb.append(val(vals, cj.receiver())).append('.');
                } else {
                    sb.append(m.getDeclaringClass().getSimpleName()).append('.');
                }
                sb.append(m.getName()).append('(');
                appendVals(sb, cj.args(), vals);
                sb.append(") {").append(cj.effect().canonicalTag()).append('}');
            }
            case MirInst.CallJavaCtor cc -> {
                Constructor<?> ctor = cc.target().constructor();
                sb.append("new_java ").append(ctor.getDeclaringClass().getSimpleName()).append('(');
                appendVals(sb, cc.args(), vals);
                sb.append(") {").append(cc.effect().canonicalTag()).append('}');
            }
            case MirInst.RecordNew rn -> {
                HirClass cls = rn.classId() >= 0 ? hir.clazz(rn.classId()) : null;
                String clsName = cls != null ? cls.shape().name() : shortJvmType(rn.classJvmName());
                sb.append("record_new ")
                        .append(clsName)
                        .append('[')
                        .append(val(vals, rn.classRef()))
                        .append("](");
                appendVals(sb, rn.args(), vals);
                sb.append(") {").append(rn.effect().canonicalTag()).append('}');
            }
            case MirInst.RecordReuse rr -> {
                HirClass cls = rr.classId() >= 0 ? hir.clazz(rr.classId()) : null;
                String clsName = cls != null ? cls.shape().name() : shortJvmType(rr.classJvmName());
                sb.append("record_reuse ")
                        .append(val(vals, rr.target()))
                        .append(" as ")
                        .append(clsName)
                        .append('(');
                appendVals(sb, rr.args(), vals);
                sb.append(") {").append(rr.effect().canonicalTag()).append('}');
            }
            case MirInst.RangeNew rn ->
                    sb.append("range_new ")
                            .append(rn.inclusive() ? ".." : "..<")
                            .append(' ')
                            .append(val(vals, rn.start()))
                            .append(", ")
                            .append(val(vals, rn.end()))
                            .append(" {")
                            .append(rn.effect().canonicalTag())
                            .append('}');
            case MirInst.Move m -> sb.append("move ").append(val(vals, m.operand()));
            case MirInst.Freeze f -> sb.append("freeze ").append(val(vals, f.operand()));
            case MirInst.SlotDecl sd -> sb.append("slot_decl ").append(sd.var().name());
            case MirInst.SlotRead sr ->
                    sb.append("slot_read ")
                            .append(sr.var().name())
                            .append(" {")
                            .append(sr.effect().canonicalTag())
                            .append('}');
            case MirInst.SlotWrite sw ->
                    sb.append(sw.opKind() == MirInst.SlotOpKind.INIT ? "slot_init " : "slot_write ")
                            .append(sw.var().name())
                            .append(", ")
                            .append(val(vals, sw.value()))
                            .append(" {")
                            .append(sw.effect().canonicalTag())
                            .append('}');
            case MirInst.ScopeEnter se ->
                    sb.append("scope_enter [")
                            .append(String.join(", ", se.declaredNames()))
                            .append(']');
            case MirInst.ScopeExit sx ->
                    sb.append("scope_exit ").append(val(vals, sx.prevNamespace()));
            case MirInst.ForIterInit fi ->
                    sb.append("for_init.")
                            .append(fi.target().kind().name().toLowerCase(Locale.ROOT))
                            .append(' ')
                            .append(val(vals, fi.iterable()));
            case MirInst.ForIterHasNext fh -> {
                sb.append("for_has_next ").append(val(vals, fh.iterator()));
                if (fh.index() != null) {
                    sb.append(", ")
                            .append(val(vals, fh.index()))
                            .append(", ")
                            .append(val(vals, fh.length()));
                }
            }
            case MirInst.ForIterNext fn -> {
                sb.append("for_next ").append(val(vals, fn.iterator()));
                if (fn.index() != null) {
                    sb.append(", ").append(val(vals, fn.index()));
                }
            }
            case MirInst.UnwrapThrown ut ->
                    sb.append("unwrap_thrown ").append(val(vals, ut.thrownExn()));
            case MirInst.CatchMatch cm ->
                    sb.append("catch_match ")
                            .append(val(vals, cm.value()))
                            .append(" <")
                            .append(formatCatchTarget(cm.target()))
                            .append('>');
            case MirInst.AddSuppressed as ->
                    sb.append("add_suppressed ")
                            .append(val(vals, as.primary()))
                            .append(", ")
                            .append(val(vals, as.secondary()));
            case MirInst.NameRead nr ->
                    sb.append("name_read ")
                            .append(nr.name())
                            .append(" {")
                            .append(nr.effect().canonicalTag())
                            .append('}');
            case MirInst.LoadExport le ->
                    sb.append("export_get ")
                            .append(val(vals, le.module()))
                            .append('.')
                            .append(le.target().name())
                            .append(" {")
                            .append(le.effect().canonicalTag())
                            .append('}');
            case MirInst.NameAssign na -> {
                sb.append("name_assign ").append(na.name());
                if (na.operator() != null) {
                    sb.append(' ').append(na.operator()).append("= ").append(val(vals, na.value()));
                } else {
                    sb.append(", ").append(val(vals, na.value()));
                }
                sb.append(" {").append(na.effect().canonicalTag()).append('}');
            }
            case MirInst.OpaqueExpr op -> {
                sb.append("opaque.").append(op.template().getClass().getSimpleName()).append('(');
                formatOpaqueOperands(sb, hir, op, vals);
                sb.append(") {").append(op.effect().canonicalTag()).append('}');
            }
        }
    }

    private static void formatOpaqueOperands(
            StringBuilder sb, HirModule hir, MirInst.OpaqueExpr op, Map<Integer, String> vals) {
        List<MirVal> ops = op.operands();
        switch (op.template()) {
            case HirExpr.ModuleExportGet mg -> {
                if (!ops.isEmpty()) {
                    sb.append(val(vals, ops.get(0))).append('.');
                }
                sb.append(mg.target() != null ? mg.target().name() : "?");
            }
            case HirExpr.DynamicSelect ds -> {
                if (!ops.isEmpty()) {
                    sb.append(val(vals, ops.get(0))).append(ds.safe() ? "?." : ".");
                }
                sb.append(ds.memberName());
            }
            case HirExpr.DynamicFieldAssign dfa -> {
                if (!ops.isEmpty()) {
                    sb.append(val(vals, ops.get(0))).append('.');
                }
                sb.append(dfa.memberName());
                if (dfa.operator() != null) {
                    sb.append(' ').append(dfa.operator()).append('=');
                }
                if (ops.size() > 1) {
                    sb.append(", ").append(val(vals, ops.get(1)));
                }
            }
            case HirExpr.SafeCall sc -> {
                if (!ops.isEmpty()) {
                    sb.append(val(vals, ops.get(0))).append("?.");
                }
                sb.append(sc.memberName());
                for (int i = 1; i < ops.size(); i++) {
                    sb.append(", ").append(val(vals, ops.get(i)));
                }
            }
            case HirExpr.SuperCall sc -> {
                sb.append(sc.memberName());
                for (MirVal v : ops) {
                    sb.append(", ").append(val(vals, v));
                }
            }
            case HirExpr.CoreCollectionRef cc -> sb.append(cc.collectionKind());
            case HirExpr.CoreCollectionCall cc -> {
                sb.append(cc.collectionKind());
                for (MirVal v : ops) {
                    sb.append(", ").append(val(vals, v));
                }
            }
            case HirExpr.FunctionRef fr -> {
                HirFunction target = hir.function(fr.functionId());
                sb.append(target != null && target.name() != null ? target.name() : "<anon>");
            }
            case HirExpr.ClassExpr ce -> {
                HirClass cls = hir.clazz(ce.classId());
                sb.append(
                        cls != null && cls.shape().name() != null ? cls.shape().name() : "<class>");
            }
            case HirExpr.InterfaceExpr ie -> {
                var iface = hir.iface(ie.interfaceId());
                sb.append(
                        iface != null && iface.shape().name() != null
                                ? iface.shape().name()
                                : "<interface>");
            }
            default -> appendVals(sb, ops, vals);
        }
    }

    private static void printTerminator(
            StringBuilder sb,
            HirModule hir,
            MirTerminator term,
            Map<Integer, String> blocks,
            Map<Integer, String> vals) {
        switch (term) {
            case MirTerminator.Br br -> {
                sb.append("br ").append(blocks.getOrDefault(br.targetBlock(), "bb?"));
                appendEdgeArgs(sb, br.args(), vals);
            }
            case MirTerminator.BrIf bi -> {
                sb.append("br_if ")
                        .append(val(vals, bi.cond()))
                        .append(", ")
                        .append(blocks.getOrDefault(bi.thenBlock(), "bb?"));
                appendEdgeArgs(sb, bi.thenArgs(), vals);
                sb.append(", ").append(blocks.getOrDefault(bi.elseBlock(), "bb?"));
                appendEdgeArgs(sb, bi.elseArgs(), vals);
            }
            case MirTerminator.Switch sw -> {
                sb.append("switch ").append(val(vals, sw.key())).append(" [");
                for (int i = 0; i < sw.keys().length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(sw.keys()[i])
                            .append(" -> ")
                            .append(blocks.getOrDefault(sw.targetBlocks()[i], "bb?"));
                    appendEdgeArgs(sb, sw.targetArgs().get(i), vals);
                }
                sb.append("] default -> ").append(blocks.getOrDefault(sw.defaultBlock(), "bb?"));
                appendEdgeArgs(sb, sw.defaultArgs(), vals);
            }
            case MirTerminator.Ret ret -> sb.append("ret ").append(val(vals, ret.value()));
            case MirTerminator.Throw th ->
                    sb.append(th.isJavaThrowable() ? "rethrow " : "throw ")
                            .append(val(vals, th.value()));
            case MirTerminator.TailCall tc -> {
                HirFunction tgt = hir.function(tc.functionId());
                String name = tgt != null ? formatFnName(hir, tgt) : ("fn#" + tc.functionId());
                sb.append("tail_call ").append(name).append('(');
                appendVals(sb, tc.args(), vals);
                sb.append(')');
            }
            case MirTerminator.Unreachable ignored -> sb.append("unreachable");
        }
    }

    private static void appendEdgeArgs(
            StringBuilder sb, List<MirVal> args, Map<Integer, String> vals) {
        if (args.isEmpty()) {
            return;
        }
        sb.append('(');
        appendVals(sb, args, vals);
        sb.append(')');
    }

    private static void appendVals(StringBuilder sb, List<MirVal> list, Map<Integer, String> vals) {
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(val(vals, list.get(i)));
        }
    }

    private static String val(Map<Integer, String> vals, MirVal v) {
        return vals.getOrDefault(v.id(), "v?" + v.id());
    }

    private static String formatFnName(HirModule hir, HirFunction fn) {
        if (fn.ownerClassId() >= 0) {
            HirClass cls = hir.clazz(fn.ownerClassId());
            String owner = cls != null ? cls.shape().name() : ("class#" + fn.ownerClassId());
            String member = fn.memberName() != null ? fn.memberName() : fn.name();
            return owner + "." + (member != null ? member : "<anon>");
        }
        return fn.name() != null && !fn.name().isEmpty() ? fn.name() : "<anon>";
    }

    private static String formatConst(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "\"" + IrCanonicalSupport.escapeString(s) + "\"";
        if (v instanceof Character ch)
            return "'" + IrCanonicalSupport.escapeString(String.valueOf(ch)) + "'";
        if (v instanceof Long l) return l + "L";
        if (v instanceof Float f) return f + "f";
        return String.valueOf(v);
    }

    private static String shortJvmType(String jvmType) {
        return IrCanonicalSupport.shortJvmType(jvmType);
    }

    private static String sanitizeDescriptor(String desc) {
        return IrCanonicalSupport.sanitizeDescriptor(desc);
    }

    private static String formatCatchTarget(LoweringTargets.CatchTarget ct) {
        if (ct.typeDescriptor() != null) {
            return sanitizeDescriptor(ct.typeDescriptor());
        }
        if (ct.babbleTargets() != null && !ct.babbleTargets().isEmpty()) {
            List<String> names = new ArrayList<>(ct.babbleTargets().size());
            for (LoweringTargets.IsTarget t : ct.babbleTargets()) {
                names.add(
                        sanitizeDescriptor(
                                t.typeId() != null
                                        ? t.typeId()
                                        : shortJvmType(t.jvmInternalName())));
            }
            return String.join("|", names);
        }
        return "babble";
    }

    private static void indent(StringBuilder sb, int level) {
        IrCanonicalSupport.indent(sb, level);
    }
}
