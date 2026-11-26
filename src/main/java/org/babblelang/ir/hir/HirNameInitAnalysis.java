package org.babblelang.ir.hir;

import org.babblelang.analysis.LoweringTargets;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Inductive non-escaping module header and self-class definite-initialization analysis
 * ({@code BIP034-D6}, §6.2).
 *
 * <p>Runs over HIR right before {@link HirBuilder} constructs {@link HirModule},
 * upgrading {@link HirExpr.NameRead#definitelyDefined()} from {@code false} to {@code true}
 * wherever static execution order guarantees the target top-level binding is already
 * initialized (preserving sequential TDZ semantics for forward references and active
 * {@code static} blocks).
 */
final class HirNameInitAnalysis {
    private HirNameInitAnalysis() {
    }

    static void annotate(
            List<HirExpr> topLevel,
            Map<Integer, HirFunction> functions,
            Map<Integer, HirClass> classes,
            Map<Integer, HirInterface> interfaces,
            Set<HirExpr> fileScopeNameReads,
            Set<HirExpr> nonCyclicImports,
            Set<HirExpr> publicStaticDefSelects) {
        if (fileScopeNameReads.isEmpty()) {
            return;
        }
        Analyzer a = new Analyzer(classes, interfaces, fileScopeNameReads, nonCyclicImports, publicStaticDefSelects);
        a.run(topLevel, functions, classes, interfaces);
    }

    private static final class Analyzer {
        private final Map<Integer, HirClass> classes;
        private final Map<Integer, HirInterface> interfaces;
        private final Set<HirExpr> fileScopeNameReads;
        private final Set<HirExpr> nonCyclicImports;
        private final Set<HirExpr> publicStaticDefSelects;

        Analyzer(Map<Integer, HirClass> classes,
                 Map<Integer, HirInterface> interfaces,
                 Set<HirExpr> fileScopeNameReads,
                 Set<HirExpr> nonCyclicImports,
                 Set<HirExpr> publicStaticDefSelects) {
            this.classes = classes;
            this.interfaces = interfaces;
            this.fileScopeNameReads = fileScopeNameReads;
            this.nonCyclicImports = nonCyclicImports;
            this.publicStaticDefSelects = publicStaticDefSelects;
        }

        void run(List<HirExpr> topLevel,
                 Map<Integer, HirFunction> functions,
                 Map<Integer, HirClass> classesMap,
                 Map<Integer, HirInterface> interfacesMap) {
            // 1. Inductive non-escaping module header (BIP034-D6 §6.2 item 1).
            Set<String> headerInitialized = new HashSet<>();
            for (HirExpr stmt : topLevel) {
                HirExpr unwrapped = unwrapTransparent(stmt);
                if (unwrapped instanceof HirExpr.Const) {
                    continue;
                }
                if (unwrapped instanceof HirExpr.Def d && d.var() == null
                        && d.target().kind() == LoweringTargets.DefKind.LEXICAL) {
                    if (isNonEscapingHeaderExpr(d.value(), headerInitialized, false)) {
                        headerInitialized.add(d.name());
                        continue;
                    }
                }
                break;
            }

            // 2. Sequential top-level order and per-class self-initialization (BIP034-D6 §6.2 item 3).
            Map<Integer, Set<String>> methodGuaranteedDefs = new HashMap<>();
            Map<Integer, Set<String>> classCtorGuaranteedDefs = new HashMap<>();
            Map<Integer, Set<String>> classStaticGuaranteedDefs = new HashMap<>();
            Map<Integer, Set<String>> ifaceStaticGuaranteedDefs = new HashMap<>();
            Set<String> precedingTopDefs = new HashSet<>();

            for (int i = 0; i < topLevel.size(); i++) {
                HirExpr stmt = topLevel.get(i);
                Set<String> beforeThisStmt = Set.copyOf(precedingTopDefs);
                // Rewrite top-level statement i using only bindings guaranteed before statement i.
                if (!beforeThisStmt.isEmpty()) {
                    HirExpr rewritten = rewriteTopStmt(stmt, beforeThisStmt);
                    if (rewritten != stmt) {
                        topLevel.set(i, rewritten);
                    }
                }
                HirExpr unwrapped = unwrapTransparent(stmt);
                if (unwrapped instanceof HirExpr.Def d && d.var() == null
                        && d.target().kind() == LoweringTargets.DefKind.LEXICAL) {
                    HirExpr val = unwrapTransparent(d.value());
                    if (val instanceof HirExpr.ClassExpr ce) {
                        HirClass cls = classesMap.get(ce.classId());
                        if (cls != null) {
                            classStaticGuaranteedDefs.put(ce.classId(), beforeThisStmt);
                            Set<String> ctorDefs = new HashSet<>(headerInitialized);
                            ctorDefs.addAll(beforeThisStmt);
                            if (!cls.isObject() && cls.staticBlock() == null) {
                                ctorDefs.add(d.name());
                            }
                            classCtorGuaranteedDefs.put(ce.classId(), ctorDefs);
                            if (!cls.isObject()
                                    && isNonEscapingStaticBlock(cls.staticBlock(), beforeThisStmt)
                                    && isNonEscapingClassBody(cls, ctorDefs)) {
                                Set<String> selfDefs = new HashSet<>(headerInitialized);
                                selfDefs.addAll(beforeThisStmt);
                                selfDefs.add(d.name());
                                for (Integer fnId : cls.methodFunctionIds().values()) {
                                    methodGuaranteedDefs.put(fnId, selfDefs);
                                }
                                for (List<Integer> ovIds : cls.overloadMethodFunctionIds().values()) {
                                    for (Integer fnId : ovIds) {
                                        methodGuaranteedDefs.put(fnId, selfDefs);
                                    }
                                }
                            }
                        }
                    } else if (val instanceof HirExpr.InterfaceExpr ie) {
                        ifaceStaticGuaranteedDefs.put(ie.interfaceId(), beforeThisStmt);
                    }
                    precedingTopDefs.add(d.name());
                }
            }

            // 3. Annotate all functions (bodies + parameter default values).
            for (Map.Entry<Integer, HirFunction> entry : new ArrayList<>(functions.entrySet())) {
                HirFunction fn = entry.getValue();
                Set<String> guaranteed = methodGuaranteedDefs.getOrDefault(fn.id(), headerInitialized);
                if (guaranteed.isEmpty()) {
                    continue;
                }
                List<HirParam> newParams = rewriteParams(fn.params(), guaranteed);
                List<HirExpr> newBody = rewriteList(fn.body(), guaranteed);
                if (newParams != fn.params() || newBody != fn.body()) {
                    functions.put(fn.id(), fn.withParamsAndBody(newParams, newBody));
                }
            }

            // 4. Annotate all classes (param defaults, ctor bodies, static blocks).
            for (Map.Entry<Integer, HirClass> entry : new ArrayList<>(classesMap.entrySet())) {
                HirClass cls = entry.getValue();
                Set<String> ctorDefs = classCtorGuaranteedDefs.getOrDefault(cls.id(), headerInitialized);
                Set<String> staticDefs = classStaticGuaranteedDefs.getOrDefault(cls.id(), Set.of());
                List<HirParam> newParams = ctorDefs.isEmpty() ? cls.params() : rewriteParams(cls.params(), ctorDefs);
                List<HirExpr> newBody = ctorDefs.isEmpty() ? cls.body() : rewriteList(cls.body(), ctorDefs);
                HirStaticBlock newStatic = cls.staticBlock();
                if (newStatic != null && !staticDefs.isEmpty()) {
                    List<HirExpr> sb = rewriteList(newStatic.body(), staticDefs);
                    if (sb != newStatic.body()) {
                        newStatic = newStatic.withBody(sb);
                    }
                }
                if (newParams != cls.params() || newBody != cls.body() || newStatic != cls.staticBlock()) {
                    classesMap.put(cls.id(), cls.withBodyAndStatic(newParams, newBody, newStatic));
                }
            }

            // 5. Annotate all interfaces (static blocks).
            for (Map.Entry<Integer, HirInterface> entry : new ArrayList<>(interfacesMap.entrySet())) {
                HirInterface iface = entry.getValue();
                Set<String> staticDefs = ifaceStaticGuaranteedDefs.getOrDefault(iface.id(), Set.of());
                HirStaticBlock newStatic = iface.staticBlock();
                if (newStatic != null && !staticDefs.isEmpty()) {
                    List<HirExpr> sb = rewriteList(newStatic.body(), staticDefs);
                    if (sb != newStatic.body()) {
                        interfacesMap.put(iface.id(), iface.withBodyAndStatic(iface.body(), newStatic.withBody(sb)));
                    }
                }
            }
        }

        private HirExpr rewriteTopStmt(HirExpr stmt, Set<String> guaranteed) {
            return rewriteExpr(stmt, guaranteed);
        }

        private boolean isNonEscapingStaticBlock(HirStaticBlock sb, Set<String> knownDefs) {
            if (sb == null) {
                return true;
            }
            for (HirExpr stmt : sb.body()) {
                HirExpr u = unwrapTransparent(stmt);
                if (u instanceof HirExpr.Const) {
                    continue;
                }
                if (u instanceof HirExpr.Def d) {
                    if (!isNonEscapingHeaderExpr(d.value(), knownDefs, true)) {
                        return false;
                    }
                    continue;
                }
                return false;
            }
            return true;
        }

        private boolean isNonEscapingClassBody(HirClass cls, Set<String> knownDefs) {
            for (HirParam p : cls.params()) {
                if (p.defaultValue() != null && !isNonEscapingHeaderExpr(p.defaultValue(), knownDefs, false)) {
                    return false;
                }
            }
            for (HirExpr stmt : cls.body()) {
                HirExpr u = unwrapTransparent(stmt);
                if (u instanceof HirExpr.Const) {
                    continue;
                }
                if (u instanceof HirExpr.Def d) {
                    if (!isNonEscapingHeaderExpr(d.value(), knownDefs, false)) {
                        return false;
                    }
                    continue;
                }
                return false;
            }
            return true;
        }

        private boolean isNonEscapingHeaderExpr(HirExpr expr, Set<String> knownDefs, boolean inStaticBlock) {
            if (expr == null) {
                return true;
            }
            if (nonCyclicImports.contains(expr)) {
                return true;
            }
            return switch (expr) {
                case HirExpr.Const ignored -> true;
                case HirExpr.FunctionRef ignored -> true;
                case HirExpr.CoreCollectionRef ignored -> true;
                case HirExpr.CoreExceptionRef ignored -> true;
                case HirExpr.ThisRef ignored -> inStaticBlock;
                case HirExpr.MakeOverloadSet m -> {
                    for (HirExpr op : m.operands()) {
                        if (!isNonEscapingHeaderExpr(op, knownDefs, inStaticBlock)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case HirExpr.NameRead nr -> nr.definitelyDefined()
                        || (fileScopeNameReads.contains(nr) && knownDefs.contains(nr.name()));
                case HirExpr.ModuleExportGet meg -> isNonEscapingHeaderExpr(meg.receiver(), knownDefs, inStaticBlock);
                case HirExpr.DynamicSelect ds -> !ds.safe()
                        && publicStaticDefSelects.contains(ds)
                        && isNonEscapingHeaderExpr(ds.receiver(), knownDefs, inStaticBlock);
                case HirExpr.JavaFieldGet jf -> jf.target().isStatic()
                        && Modifier.isFinal(jf.target().field().getModifiers())
                        && (jf.pureStaticReceiver() || isNonEscapingHeaderExpr(jf.receiver(), knownDefs, inStaticBlock));
                case HirExpr.Unary u -> u.meta().kind().isPrimitive()
                        && isNonEscapingHeaderExpr(u.operand(), knownDefs, inStaticBlock);
                case HirExpr.Binary b -> (b.meta().kind().isPrimitive()
                        || (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType()))
                        && isNonEscapingHeaderExpr(b.left(), knownDefs, inStaticBlock)
                        && isNonEscapingHeaderExpr(b.right(), knownDefs, inStaticBlock);
                case HirExpr.Logic l -> isNonEscapingHeaderExpr(l.left(), knownDefs, inStaticBlock)
                        && isNonEscapingHeaderExpr(l.right(), knownDefs, inStaticBlock);
                case HirExpr.As a -> a.samTarget() == null
                        && isNonEscapingHeaderExpr(a.left(), knownDefs, inStaticBlock);
                case HirExpr.Is i -> isNonEscapingHeaderExpr(i.left(), knownDefs, inStaticBlock);
                case HirExpr.NonNull n -> isNonEscapingHeaderExpr(n.operand(), knownDefs, inStaticBlock);
                case HirExpr.TypeApply t -> isNonEscapingHeaderExpr(t.inner(), knownDefs, inStaticBlock);
                case HirExpr.Block b -> {
                    if (!b.declaredVars().isEmpty() || b.opensNamespaceScope()) {
                        yield false;
                    }
                    for (HirExpr s : b.statements()) {
                        if (!isNonEscapingHeaderExpr(s, knownDefs, inStaticBlock)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case HirExpr.RecordNew r -> {
                    if (!isNonEscapingHeaderExpr(r.classRef(), knownDefs, inStaticBlock)) {
                        yield false;
                    }
                    for (HirExpr arg : r.args()) {
                        if (!isNonEscapingHeaderExpr(arg, knownDefs, inStaticBlock)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                case HirExpr.ClassExpr ce -> {
                    HirClass cls = classes.get(ce.classId());
                    yield cls != null && !cls.isObject() && isNonEscapingStaticBlock(cls.staticBlock(), knownDefs);
                }
                case HirExpr.InterfaceExpr ie -> {
                    HirInterface iface = interfaces.get(ie.interfaceId());
                    yield iface != null && isNonEscapingStaticBlock(iface.staticBlock(), knownDefs);
                }
                default -> false;
            };
        }

        private static HirExpr unwrapTransparent(HirExpr expr) {
            while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
                expr = b.statements().get(0);
            }
            return expr;
        }

        // ------------------------------------------------------------------ HIR NameRead rewriter

        private List<HirParam> rewriteParams(List<HirParam> params, Set<String> guaranteed) {
            List<HirParam> out = null;
            for (int i = 0; i < params.size(); i++) {
                HirParam p = params.get(i);
                if (p.defaultValue() != null) {
                    HirExpr nv = rewriteExpr(p.defaultValue(), guaranteed);
                    if (nv != p.defaultValue()) {
                        if (out == null) {
                            out = new ArrayList<>(params);
                        }
                        out.set(i, p.withDefaultValue(nv));
                    }
                }
            }
            return out != null ? out : params;
        }

        private List<HirExpr> rewriteList(List<HirExpr> list, Set<String> guaranteed) {
            List<HirExpr> out = null;
            for (int i = 0; i < list.size(); i++) {
                HirExpr orig = list.get(i);
                HirExpr nw = rewriteExpr(orig, guaranteed);
                if (nw != orig) {
                    if (out == null) {
                        out = new ArrayList<>(list);
                    }
                    out.set(i, nw);
                }
            }
            return out != null ? out : list;
        }

        private List<HirCallArg> rewriteArgs(List<HirCallArg> args, Set<String> guaranteed) {
            List<HirCallArg> out = null;
            for (int i = 0; i < args.size(); i++) {
                HirCallArg a = args.get(i);
                HirExpr nv = rewriteExpr(a.value(), guaranteed);
                if (nv != a.value()) {
                    if (out == null) {
                        out = new ArrayList<>(args);
                    }
                    out.set(i, a.withValue(nv));
                }
            }
            return out != null ? out : args;
        }

        private HirExpr rewriteExpr(HirExpr expr, Set<String> guaranteed) {
            if (expr == null) {
                return null;
            }
            UnaryOperator<HirExpr> fn = e -> rewriteExpr(e, guaranteed);
            return switch (expr) {
                case HirExpr.NameRead nr -> (!nr.definitelyDefined()
                        && fileScopeNameReads.contains(nr)
                        && guaranteed.contains(nr.name()))
                        ? nr.asDefinitelyDefined() : nr;
                case HirExpr.Const c -> c;
                case HirExpr.IntParseFallback i -> i;
                case HirExpr.CoreCollectionRef r -> r;
                case HirExpr.CoreExceptionRef r -> r;
                case HirExpr.VarRead v -> v;
                case HirExpr.ThisRef t -> t;
                case HirExpr.RecurseRef r -> r;
                case HirExpr.Continue c -> c;
                case HirExpr.FunctionRef f -> f;
                case HirExpr.ClassExpr c -> c;
                case HirExpr.InterfaceExpr i -> i;
                case HirExpr.Def d -> {
                    HirExpr nv = d.value() != null ? fn.apply(d.value()) : null;
                    yield nv == d.value() ? d : new HirExpr.Def(d.name(), d.target(), d.var(), nv, d.meta());
                }
                case HirExpr.VarAssign a -> {
                    HirExpr nv = fn.apply(a.value());
                    yield nv == a.value() ? a : new HirExpr.VarAssign(a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
                }
                case HirExpr.NameAssign a -> {
                    HirExpr nv = fn.apply(a.value());
                    yield nv == a.value() ? a : new HirExpr.NameAssign(a.name(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
                }
                case HirExpr.ThisFieldAssign a -> {
                    HirExpr nr = fn.apply(a.receiver());
                    HirExpr nv = fn.apply(a.value());
                    yield (nr == a.receiver() && nv == a.value()) ? a
                            : new HirExpr.ThisFieldAssign(nr, a.classId(), a.memberIndex(), a.memberName(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
                }
                case HirExpr.JavaFieldAssign a -> {
                    HirExpr nr = fn.apply(a.receiver());
                    HirExpr nv = fn.apply(a.value());
                    yield (nr == a.receiver() && nv == a.value()) ? a
                            : new HirExpr.JavaFieldAssign(nr, a.receiverText(), a.pureStaticReceiver(), a.target(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
                }
                case HirExpr.DynamicFieldAssign a -> {
                    HirExpr nr = fn.apply(a.receiver());
                    HirExpr nv = fn.apply(a.value());
                    yield (nr == a.receiver() && nv == a.value()) ? a
                            : new HirExpr.DynamicFieldAssign(nr, a.receiverText(), a.memberName(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
                }
                case HirExpr.ThisFieldGet g -> {
                    HirExpr nr = fn.apply(g.receiver());
                    yield nr == g.receiver() ? g : new HirExpr.ThisFieldGet(
                            nr, g.receiverText(), g.classId(), g.externalClassJvmName(),
                            g.memberIndex(), g.memberName(), g.fieldKind(), g.immutableField(), g.definitelyDefined(), g.meta());
                }
                case HirExpr.JavaFieldGet g -> {
                    HirExpr nr = fn.apply(g.receiver());
                    yield nr == g.receiver() ? g : new HirExpr.JavaFieldGet(nr, g.receiverText(), g.pureStaticReceiver(), g.target(), g.meta());
                }
                case HirExpr.ArrayCreate c -> {
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield na == c.args() ? c : new HirExpr.ArrayCreate(c.kind(), c.elemKind(), c.elementType(), c.componentInternalName(), c.componentIsJavaInterface(), c.componentClass(), na, c.meta());
                }
                case HirExpr.ArrayOp op -> {
                    HirExpr nr = fn.apply(op.receiver());
                    List<HirExpr> na = rewriteList(op.args(), guaranteed);
                    yield (nr == op.receiver() && na == op.args()) ? op
                            : new HirExpr.ArrayOp(op.kind(), nr, op.receiverText(), op.elemKind(), op.elementType(), op.arrayClass(), op.componentClass(), na, op.meta());
                }
                case HirExpr.ModuleExportGet g -> {
                    HirExpr nr = fn.apply(g.receiver());
                    yield nr == g.receiver() ? g : new HirExpr.ModuleExportGet(nr, g.target(), g.meta());
                }
                case HirExpr.DynamicSelect s -> {
                    HirExpr nr = fn.apply(s.receiver());
                    yield nr == s.receiver() ? s : new HirExpr.DynamicSelect(nr, s.receiverText(), s.memberName(), s.safe(), s.meta());
                }
                case HirExpr.Unary u -> {
                    HirExpr no = fn.apply(u.operand());
                    yield no == u.operand() ? u : new HirExpr.Unary(u.op(), no, u.operandText(), u.meta());
                }
                case HirExpr.Binary b -> {
                    HirExpr nl = fn.apply(b.left());
                    HirExpr nr = fn.apply(b.right());
                    yield (nl == b.left() && nr == b.right()) ? b
                            : new HirExpr.Binary(b.op(), nl, nr, b.leftText(), b.rightText(), b.meta());
                }
                case HirExpr.Range r -> {
                    HirExpr ns = fn.apply(r.start());
                    HirExpr ne = fn.apply(r.end());
                    yield (ns == r.start() && ne == r.end()) ? r
                            : new HirExpr.Range(ns, ne, r.inclusive(), r.elemKind(), r.stringElement(), r.meta());
                }
                case HirExpr.Logic l -> {
                    HirExpr nl = fn.apply(l.left());
                    HirExpr nr = fn.apply(l.right());
                    yield (nl == l.left() && nr == l.right()) ? l : new HirExpr.Logic(l.op(), nl, nr, l.meta());
                }
                case HirExpr.Is i -> {
                    HirExpr nl = fn.apply(i.left());
                    yield nl == i.left() ? i : new HirExpr.Is(nl, i.builtin(), i.target(), i.intersectionTargets(), i.descriptor(), i.meta());
                }
                case HirExpr.As a -> {
                    HirExpr nl = fn.apply(a.left());
                    yield nl == a.left() ? a : new HirExpr.As(nl, a.target(), a.samTarget(), a.targetDescriptor(), a.meta());
                }
                case HirExpr.NonNull n -> {
                    HirExpr no = fn.apply(n.operand());
                    yield no == n.operand() ? n : new HirExpr.NonNull(no, n.expectedType(), n.meta());
                }
                case HirExpr.SpreadAdapt s -> {
                    HirExpr nv = fn.apply(s.value());
                    yield nv == s.value() ? s : new HirExpr.SpreadAdapt(nv, s.adapt(), s.meta());
                }
                case HirExpr.JavaSamAdapt j -> {
                    HirExpr nv = fn.apply(j.value());
                    yield nv == j.value() ? j : new HirExpr.JavaSamAdapt(nv, j.target(), j.meta());
                }
                case HirExpr.TypeApply t -> {
                    HirExpr ni = fn.apply(t.inner());
                    yield ni == t.inner() ? t : new HirExpr.TypeApply(ni, t.meta());
                }
                case HirExpr.Block b -> {
                    List<HirExpr> ns = rewriteList(b.statements(), guaranteed);
                    yield ns == b.statements() ? b
                            : new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(), b.innerWideningRank(), b.meta());
                }
                case HirExpr.If i -> {
                    HirExpr nc = fn.apply(i.cond());
                    HirExpr nt = fn.apply(i.thenBranch());
                    HirExpr ne = i.elseBranch() != null ? fn.apply(i.elseBranch()) : null;
                    yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch()) ? i
                            : new HirExpr.If(nc, nt, ne, i.meta());
                }
                case HirExpr.While w -> {
                    HirExpr nc = fn.apply(w.cond());
                    HirExpr nb = fn.apply(w.body());
                    yield (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
                }
                case HirExpr.For f -> {
                    HirExpr ni = f.iterable() != null ? fn.apply(f.iterable()) : null;
                    HirExpr.CountedRangeLoop cr = f.countedRange();
                    HirExpr.CountedRangeLoop ncr = cr;
                    if (cr != null) {
                        HirExpr ns = fn.apply(cr.start());
                        HirExpr ne = fn.apply(cr.end());
                        HirExpr nk = cr.step() != null ? fn.apply(cr.step()) : null;
                        if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                            ncr = new HirExpr.CountedRangeLoop(ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                        }
                    }
                    List<HirExpr> nb = rewriteList(f.body(), guaranteed);
                    yield (ni == f.iterable() && ncr == cr && nb == f.body()) ? f
                            : new HirExpr.For(ni, f.target(), f.loopVar(), f.bodyDeclaredVars(), nb,
                            f.bodyWideningRank(), f.elemSamTarget(), ncr, f.meta());
                }
                case HirExpr.Try t -> {
                    List<HirExpr> ntb = rewriteList(t.tryBody(), guaranteed);
                    List<HirExpr.HirCatch> ncs = null;
                    for (int idx = 0; idx < t.catches().size(); idx++) {
                        HirExpr.HirCatch cc = t.catches().get(idx);
                        List<HirExpr> ncb = rewriteList(cc.body(), guaranteed);
                        if (ncb != cc.body()) {
                            if (ncs == null) {
                                ncs = new ArrayList<>(t.catches());
                            }
                            ncs.set(idx, new HirExpr.HirCatch(cc.var(), cc.target(), ncb, cc.declaredVars(),
                                    cc.opensNamespaceScope(), cc.declaredNames(), cc.wideningRank(), cc.line()));
                        }
                    }
                    List<HirExpr> nfb = rewriteList(t.finallyBody(), guaranteed);
                    yield (ntb == t.tryBody() && ncs == null && nfb == t.finallyBody()) ? t
                            : new HirExpr.Try(ntb, t.tryDeclaredVars(), t.tryOpensNamespaceScope(), t.tryDeclaredNames(),
                            t.tryWideningRank(), ncs != null ? ncs : t.catches(), nfb,
                            t.finallyDeclaredVars(), t.finallyOpensNamespaceScope(), t.finallyDeclaredNames(),
                            t.finallyNothrow(), t.hasFinally(), t.meta());
                }
                case HirExpr.Break b -> {
                    HirExpr nv = b.value() != null ? fn.apply(b.value()) : null;
                    yield nv == b.value() ? b : new HirExpr.Break(nv, b.meta());
                }
                case HirExpr.Return r -> {
                    HirExpr nv = fn.apply(r.value());
                    yield nv == r.value() ? r : new HirExpr.Return(nv, r.meta());
                }
                case HirExpr.Throw th -> {
                    HirExpr nv = fn.apply(th.value());
                    yield nv == th.value() ? th : new HirExpr.Throw(nv, th.isJavaThrowable(), th.meta());
                }
                case HirExpr.CoreCollectionCall c -> {
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield na == c.args() ? c : new HirExpr.CoreCollectionCall(c.collectionKind(), na, c.meta());
                }
                case HirExpr.CoreMethodCall c -> {
                    HirExpr nr = fn.apply(c.receiver());
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield (nr == c.receiver() && na == c.args()) ? c
                            : new HirExpr.CoreMethodCall(nr, c.receiverText(), c.selectorLine(), c.coreKind(), c.memberName(), na, c.meta());
                }
                case HirExpr.SuperCall c -> {
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield na == c.args() ? c
                            : new HirExpr.SuperCall(c.thisVar(), c.ownerClassId(), c.memberName(), c.targetInfo(), c.superTypeRef(), na, c.binding(), c.callSite(), c.meta());
                }
                case HirExpr.SelfRecurseCall c -> {
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield na == c.args() ? c : new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta());
                }
                case HirExpr.SelfTailCall c -> {
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield na == c.args() ? c : new HirExpr.SelfTailCall(c.functionId(), na, c.binding(), c.meta());
                }
                case HirExpr.DirectBabbleCall c -> {
                    HirExpr nc = fn.apply(c.callee());
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield (nc == c.callee() && na == c.args()) ? c
                            : new HirExpr.DirectBabbleCall(nc, c.targetFunctionId(), c.externalTarget(), na, c.binding(), c.meta());
                }
                case HirExpr.DirectThisMethodCall c -> {
                    HirExpr nr = fn.apply(c.receiver());
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield (nr == c.receiver() && na == c.args()) ? c
                            : new HirExpr.DirectThisMethodCall(nr, c.receiverText(), c.classId(), c.externalClassJvmName(),
                            c.classTypeId(), c.memberName(), c.overloadAltIndex(), c.targetFunctionId(),
                            c.externalSignature(), na, c.binding(), c.callSite(), c.meta());
                }
                case HirExpr.InterfaceCall c -> {
                    HirExpr nr = fn.apply(c.receiver());
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield (nr == c.receiver() && na == c.args()) ? c
                            : new HirExpr.InterfaceCall(nr, c.receiverText(), c.selectorLine(), c.interfaceTypeId(), c.memberName(), c.signature(), na, c.binding(), c.meta());
                }
                case HirExpr.JavaConstructorCall c -> {
                    HirExpr nc = fn.apply(c.callee());
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield (nc == c.callee() && na == c.args()) ? c
                            : new HirExpr.JavaConstructorCall(nc, c.calleeText(), c.pureStaticCallee(), c.target(), na, c.meta());
                }
                case HirExpr.JavaMethodCall c -> {
                    HirExpr nr = fn.apply(c.receiver());
                    List<HirExpr> na = rewriteList(c.args(), guaranteed);
                    yield (nr == c.receiver() && na == c.args()) ? c
                            : new HirExpr.JavaMethodCall(nr, c.receiverText(), c.selectorLine(), c.pureStaticReceiver(), c.target(), na, c.meta());
                }
                case HirExpr.SafeCall c -> {
                    HirExpr nr = fn.apply(c.receiver());
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield (nr == c.receiver() && na == c.args()) ? c
                            : new HirExpr.SafeCall(nr, c.receiverText(), c.memberName(), c.selectorLine(), c.selectorCheckedType(), na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
                }
                case HirExpr.DynamicCall c -> {
                    HirExpr nc = fn.apply(c.callee());
                    List<HirCallArg> na = rewriteArgs(c.args(), guaranteed);
                    yield (nc == c.callee() && na == c.args()) ? c
                            : new HirExpr.DynamicCall(nc, na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
                }
                case HirExpr.MakeOverloadSet m -> {
                    List<HirExpr> no = rewriteList(m.operands(), guaranteed);
                    yield no == m.operands() ? m : new HirExpr.MakeOverloadSet(no, m.metas(), m.meta());
                }
                case HirExpr.OverloadSelect s -> {
                    HirExpr ns = fn.apply(s.set());
                    yield ns == s.set() ? s : new HirExpr.OverloadSelect(ns, s.index(), s.signatureKey(), s.exactSet(), s.meta());
                }
                case HirExpr.OverloadDispatch d -> {
                    HirExpr nc = fn.apply(d.callee());
                    List<HirExpr> na = rewriteList(d.args(), guaranteed);
                    yield (nc == d.callee() && na == d.args()) ? d
                            : new HirExpr.OverloadDispatch(nc, d.calleeText(), d.callSite(), na, d.branches(), d.meta());
                }
                case HirExpr.RecordNew r -> {
                    HirExpr ncr = fn.apply(r.classRef());
                    List<HirExpr> na = rewriteList(r.args(), guaranteed);
                    yield (ncr == r.classRef() && na == r.args()) ? r
                            : new HirExpr.RecordNew(r.classId(), r.classTypeId(), r.classJvmName(), ncr,
                            r.classRefIsInstance(), r.paramKinds(), na, r.binding(), r.meta());
                }
                case HirExpr.RecordReuse r -> {
                    HirExpr nt = fn.apply(r.target());
                    List<HirExpr> na = rewriteList(r.args(), guaranteed);
                    yield (nt == r.target() && na == r.args()) ? r
                            : new HirExpr.RecordReuse(nt, r.classId(), r.classTypeId(), r.classJvmName(), r.paramKinds(), na, r.meta());
                }
                case HirExpr.Move m -> {
                    HirExpr no = fn.apply(m.operand());
                    HirVar nv = no instanceof HirExpr.VarRead vr ? vr.var() : m.var();
                    yield (no == m.operand() && nv == m.var()) ? m : new HirExpr.Move(nv, no, m.meta());
                }
                case HirExpr.Freeze f -> {
                    HirExpr no = fn.apply(f.operand());
                    yield no == f.operand() ? f : new HirExpr.Freeze(no, f.meta());
                }
            };
        }
    }
}
