package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * ANTLR-free algebraic signature validation, bounded E-graph equational law verification
 * ({@code idempotent}, {@code commutative}, {@code associative}), and partial-derivative
 * sign &amp; bijectivity analysis ({@code monotone}, {@code injective})
 * ({@code BIP027-I6}, {@code BIP027-D1}).
 *
 * <p>Reuses {@link EGraph} and {@link AlgebraicRules#verifierRules()} directly so the
 * optimizer and effect verifier share one single source of truth for integral, boolean,
 * string, order-lattice, and strict IEEE-754 floating-point algebraic laws.
 */
final class AlgebraicVerifier {
    static final int VERIFIER_MAX_ITERS = 8;
    static final int VERIFIER_MAX_NODES = 256;

    private AlgebraicVerifier() {
    }

    // ---------------------------------------------------------------- pure & mut algebraic terms (ANTLR-free)

    /** Normalized ANTLR-free pure expression term over a function's parameters. */
    sealed interface Term permits Param, Const, Opaque, Unary, Binary, Call, Ite, Cast, Ctor {
        Type type();

        PrimitiveKind kind();
    }

    record Param(int index, Type type, PrimitiveKind kind) implements Term {
    }

    record Const(Object value, Type type, PrimitiveKind kind) implements Term {
    }

    record Opaque(String id, Type type, PrimitiveKind kind) implements Term {
    }

    record Unary(ENode.Op op, Term operand, Type type, PrimitiveKind kind) implements Term {
    }

    record Binary(ENode.Op op, Term left, Term right, Type type, PrimitiveKind kind) implements Term {
    }

    record Call(String symbol, List<Term> args, EffectSet calleeEffects, EGraph.CallProps props,
                Type type, PrimitiveKind kind) implements Term {
    }

    record Ite(Term cond, Term thenBranch, Term elseBranch, Type type, PrimitiveKind kind) implements Term {
    }

    record Cast(Term operand, Type srcType, Type type, PrimitiveKind kind) implements Term {
    }

    record Ctor(String symbol, List<Term> args, boolean injectiveValueCtor, Type type) implements Term {
        @Override
        public PrimitiveKind kind() {
            return PrimitiveKind.NONE;
        }
    }

    /** Normalized ANTLR-free representation of a mutating method/function body. */
    sealed interface MutBody permits ForwardMutCall, FieldTransition {
    }

    record ForwardMutCall(EffectSet calleeEffects) implements MutBody {
    }

    /**
     * Single-field state transition {@code target.field = nextState(params..., oldField)} where
     * {@code stateParamIndex == paramCount} represents {@code oldField}, and {@code targetParamIndex}
     * is {@code -1} for {@code this} or the index of the mutated parameter.
     */
    record FieldTransition(int stateParamIndex, int targetParamIndex, int paramCount,
                           List<PrimitiveKind> paramKinds, PrimitiveKind stateKind,
                           Term nextState) implements MutBody {
    }

    // ---------------------------------------------------------------- signature validation

    static List<String> validateAlgebraicSignature(Set<Effect> effects, List<Type.Parameter> params, Type result,
                                                   boolean mutThis, boolean axiomatic,
                                                   BiPredicate<Type, Type> sameDomain) {
        if (effects.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        boolean anyMutParam = params.stream().anyMatch(Type.Parameter::mut);
        boolean hasMut = mutThis || anyMutParam;
        boolean isPure = effects.contains(Effect.PURE);

        if (effects.contains(Effect.ASSOCIATIVE)) {
            if (!isPure || hasMut || params.size() != 2
                    || result == Type.NULL || result == Type.NOTHING
                    || !sameDomain.test(params.get(0).type(), params.get(1).type())
                    || !sameDomain.test(params.get(0).type(), result)) {
                errors.add("associative requires a pure binary (T, T) : T signature");
            }
        }
        if (effects.contains(Effect.COMMUTATIVE)) {
            if (!hasMut) {
                if (!isPure || params.size() != 2 || !sameDomain.test(params.get(0).type(), params.get(1).type())) {
                    errors.add("commutative without mut requires a pure binary (T, T) : R signature");
                }
            } else if (mutThis ? params.isEmpty() : params.size() < 2) {
                errors.add("mut commutative requires a value parameter on the mutated target");
            }
        }
        if (effects.contains(Effect.IDEMPOTENT)) {
            if (!hasMut && !(axiomatic && !isPure)) {
                if (!isPure || params.size() != 1 || result == Type.NULL || !sameDomain.test(params.get(0).type(), result)) {
                    errors.add("idempotent without mut requires a pure unary (T) : T signature or an effectful axiom");
                }
            }
        }
        if (effects.contains(Effect.MONOTONE)) {
            if (hasMut || params.isEmpty() || !isOrderedType(result)
                    || params.stream().noneMatch(p -> isOrderedType(p.type()))) {
                errors.add("monotone requires ordered parameter and return types without mut");
            }
        }
        if (effects.contains(Effect.INJECTIVE)) {
            if (result == Type.NULL || result == Type.NOTHING || hasMut || params.isEmpty()) {
                errors.add("injective requires at least one parameter, no mut, and a non-null return type");
            }
        }
        return errors;
    }

    static boolean isOrderedType(Type t) {
        if (t == Type.ERROR) {
            return true;
        }
        if (t instanceof Type.Frozen f) {
            return isOrderedType(f.of());
        }
        if (Type.isNumericScalar(t) || t == Type.CHAR || t == Type.BOOL || t == Type.STRING) {
            return true;
        }
        if (t instanceof Type.JavaInstance ji) {
            return Comparable.class.isAssignableFrom(ji.type());
        }
        return false;
    }

    static PrimitiveKind primitiveKindOf(Type type) {
        if (type == null) {
            return PrimitiveKind.NONE;
        }
        if (type instanceof Type.Frozen f) {
            type = f.of();
        }
        if (type == Type.BYTE) return PrimitiveKind.BYTE;
        if (type == Type.SHORT) return PrimitiveKind.SHORT;
        if (type == Type.INT) return PrimitiveKind.INT;
        if (type == Type.LONG) return PrimitiveKind.LONG;
        if (type == Type.FLOAT) return PrimitiveKind.FLOAT;
        if (type == Type.DOUBLE) return PrimitiveKind.DOUBLE;
        if (type == Type.BOOL) return PrimitiveKind.BOOL;
        if (type == Type.CHAR) return PrimitiveKind.CHAR;
        return PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- pure verification (E-graph + RelSignDomain)

    static void verifyPureProperties(Term body, List<Type.Parameter> params, Type result,
                                     BiPredicate<Type, Type> sameDomain, EnumSet<Effect> out) {
        if (body == null || params.isEmpty()) {
            return;
        }
        // 1. Order & bijectivity via partial-derivative sign domain across all parameter positions.
        if (isMonotone(body, params, result)) {
            out.add(Effect.MONOTONE);
        }
        if (isInjective(body, params, result)) {
            out.add(Effect.INJECTIVE);
        }
        // 2. Binary equational laws via bounded E-graph equivalence saturation.
        if (params.size() == 2 && sameDomain.test(params.get(0).type(), params.get(1).type())) {
            PrimitiveKind pk = primitiveKindOf(params.get(0).type());
            if (checkCommutative(body, pk)) {
                out.add(Effect.COMMUTATIVE);
            }
            if (sameDomain.test(params.get(0).type(), result)
                    && result != Type.NULL && result != Type.NOTHING
                    && checkAssociative(body, pk)) {
                out.add(Effect.ASSOCIATIVE);
            }
        }
        // 3. Unary idempotence via bounded E-graph equivalence saturation.
        if (params.size() == 1 && sameDomain.test(params.get(0).type(), result) && result != Type.NULL) {
            PrimitiveKind pk = primitiveKindOf(params.get(0).type());
            if (checkIdempotent(body, pk)) {
                out.add(Effect.IDEMPOTENT);
            }
        }
    }

    static void verifyMutProperties(MutBody body, EnumSet<Effect> out) {
        if (body == null) {
            return;
        }
        if (body instanceof ForwardMutCall fwd) {
            EffectSet effs = fwd.calleeEffects();
            if (effs != null) {
                if (effs.has(Effect.IDEMPOTENT)) {
                    out.add(Effect.IDEMPOTENT);
                }
                if (effs.has(Effect.COMMUTATIVE)) {
                    out.add(Effect.COMMUTATIVE);
                }
            }
            return;
        }
        FieldTransition ft = (FieldTransition) body;
        if (checkMutIdempotent(ft)) {
            out.add(Effect.IDEMPOTENT);
        }
        if (checkMutCommutative(ft)) {
            out.add(Effect.COMMUTATIVE);
        }
    }

    // ---------------------------------------------------------------- bounded E-graph equivalence checks

    static boolean checkCommutative(Term body, PrimitiveKind argKind) {
        EGraph g = new EGraph();
        int a = g.add(ENode.opaque("$a", argKind));
        int b = g.add(ENode.opaque("$b", argKind));
        int ab = lower(body, new int[]{a, b}, g);
        int ba = lower(body, new int[]{b, a}, g);
        g.saturate(AlgebraicRules.verifierRules(), VERIFIER_MAX_ITERS, VERIFIER_MAX_NODES);
        return g.areEquivalent(ab, ba);
    }

    static boolean checkAssociative(Term body, PrimitiveKind argKind) {
        EGraph g = new EGraph();
        int a = g.add(ENode.opaque("$a", argKind));
        int b = g.add(ENode.opaque("$b", argKind));
        int c = g.add(ENode.opaque("$c", argKind));
        int ab = lower(body, new int[]{a, b}, g);
        int lhs = lower(body, new int[]{ab, c}, g);
        int bc = lower(body, new int[]{b, c}, g);
        int rhs = lower(body, new int[]{a, bc}, g);
        g.saturate(AlgebraicRules.verifierRules(), VERIFIER_MAX_ITERS, VERIFIER_MAX_NODES);
        return g.areEquivalent(lhs, rhs);
    }

    static boolean checkIdempotent(Term body, PrimitiveKind argKind) {
        EGraph g = new EGraph();
        int a = g.add(ENode.opaque("$a", argKind));
        int fa = lower(body, new int[]{a}, g);
        int ffa = lower(body, new int[]{fa}, g);
        g.saturate(AlgebraicRules.verifierRules(), VERIFIER_MAX_ITERS, VERIFIER_MAX_NODES);
        return g.areEquivalent(fa, ffa);
    }

    private static boolean checkMutIdempotent(FieldTransition ft) {
        EGraph g = new EGraph();
        int[] env1 = new int[ft.paramCount() + 1];
        for (int i = 0; i < ft.paramCount(); i++) {
            env1[i] = g.add(ENode.opaque("$p" + i, ft.paramKinds().get(i)));
        }
        int s0 = g.add(ENode.opaque("$s0", ft.stateKind()));
        env1[ft.stateParamIndex()] = s0;
        int s1 = lower(ft.nextState(), env1, g);
        int[] env2 = env1.clone();
        env2[ft.stateParamIndex()] = s1;
        int s2 = lower(ft.nextState(), env2, g);
        g.saturate(AlgebraicRules.verifierRules(), VERIFIER_MAX_ITERS, VERIFIER_MAX_NODES);
        return g.areEquivalent(s1, s2);
    }

    private static boolean checkMutCommutative(FieldTransition ft) {
        int valueParams = ft.targetParamIndex() >= 0 ? ft.paramCount() - 1 : ft.paramCount();
        if (valueParams < 1) {
            return false;
        }
        EGraph g = new EGraph();
        int s0 = g.add(ENode.opaque("$s0", ft.stateKind()));
        int[] xEnv = new int[ft.paramCount() + 1];
        int[] yEnv = new int[ft.paramCount() + 1];
        for (int i = 0; i < ft.paramCount(); i++) {
            PrimitiveKind pk = ft.paramKinds().get(i);
            if (i == ft.targetParamIndex()) {
                int tgt = g.add(ENode.opaque("$tgt", pk));
                xEnv[i] = tgt;
                yEnv[i] = tgt;
            } else {
                xEnv[i] = g.add(ENode.opaque("$x" + i, pk));
                yEnv[i] = g.add(ENode.opaque("$y" + i, pk));
            }
        }
        xEnv[ft.stateParamIndex()] = s0;
        int sx = lower(ft.nextState(), xEnv, g);
        yEnv[ft.stateParamIndex()] = sx;
        int sxy = lower(ft.nextState(), yEnv, g);

        yEnv[ft.stateParamIndex()] = s0;
        int sy = lower(ft.nextState(), yEnv, g);
        xEnv[ft.stateParamIndex()] = sy;
        int syx = lower(ft.nextState(), xEnv, g);

        g.saturate(AlgebraicRules.verifierRules(), VERIFIER_MAX_ITERS, VERIFIER_MAX_NODES);
        return g.areEquivalent(sxy, syx);
    }

    private static int lower(Term term, int[] paramIds, EGraph g) {
        return switch (term) {
            case Param p -> paramIds[p.index()];
            case Const c -> g.add(ENode.constVal(c.value(), c.kind()));
            case Opaque o -> g.add(ENode.opaque(o.id(), o.kind()));
            case Unary u -> g.add(ENode.unary(u.op(), u.kind(), lower(u.operand(), paramIds, g)));
            case Binary b -> g.add(ENode.binary(b.op(), b.kind(), lower(b.left(), paramIds, g), lower(b.right(), paramIds, g)));
            case Ite ite -> g.add(ENode.ite(ite.kind(),
                    lower(ite.cond(), paramIds, g),
                    lower(ite.thenBranch(), paramIds, g),
                    lower(ite.elseBranch(), paramIds, g)));
            case Cast c -> {
                int inner = lower(c.operand(), paramIds, g);
                yield c.srcType().equals(c.type()) ? inner : g.add(ENode.call("$cast:" + c.kind(), c.kind(), inner));
            }
            case Call c -> {
                if (c.props() != null && !c.props().equals(EGraph.CallProps.NONE)) {
                    g.registerCallProps(c.symbol(), c.props());
                }
                int[] kids = new int[c.args().size()];
                for (int i = 0; i < kids.length; i++) {
                    kids[i] = lower(c.args().get(i), paramIds, g);
                }
                yield g.add(ENode.call(c.symbol(), c.kind(), kids));
            }
            case Ctor c -> {
                int[] kids = new int[c.args().size()];
                for (int i = 0; i < kids.length; i++) {
                    kids[i] = lower(c.args().get(i), paramIds, g);
                }
                yield g.add(ENode.call(c.symbol(), PrimitiveKind.NONE, kids));
            }
        };
    }

    // ---------------------------------------------------------------- partial-derivative sign & bijectivity domain

    /** Abstract partial-derivative sign lattice ({@code BIP027} §3.5). */
    enum Sign {
        BOT,
        CONST,
        INC,
        DEC,
        TOP;

        Sign negate() {
            return switch (this) {
                case INC -> DEC;
                case DEC -> INC;
                default -> this;
            };
        }

        static Sign join(Sign a, Sign b) {
            if (a == BOT) return b;
            if (b == BOT) return a;
            if (a == b) return a;
            if (a == CONST) return b;
            if (b == CONST) return a;
            return TOP;
        }

        static Sign add(Sign a, Sign b) {
            if (a == CONST) return b;
            if (b == CONST) return a;
            if (a == b && (a == INC || a == DEC)) return a;
            return TOP;
        }
    }

    record RelVal(Sign sign, boolean injective) {
        static final RelVal CONST = new RelVal(Sign.CONST, false);
        static final RelVal TOP = new RelVal(Sign.TOP, false);
    }

    static boolean isMonotone(Term body, List<Type.Parameter> params, Type result) {
        if (params.isEmpty() || !isOrderedType(result)) {
            return false;
        }
        boolean anyInc = false;
        for (int i = 0; i < params.size(); i++) {
            RelVal rv = evalRel(body, i);
            if (rv.sign() == Sign.CONST) {
                continue;
            }
            if (rv.sign() == Sign.INC && isOrderedType(params.get(i).type())) {
                anyInc = true;
                continue;
            }
            return false;
        }
        return anyInc;
    }

    static boolean isInjective(Term body, List<Type.Parameter> params, Type result) {
        if (params.isEmpty() || result == Type.NULL || result == Type.NOTHING) {
            return false;
        }
        int n = params.size();
        if (n == 1) {
            return evalRel(body, 0).injective();
        }
        // Multi-parameter triangular recovery across a record constructor's fields.
        if (body instanceof Ctor ctor && ctor.injectiveValueCtor() && ctor.args().size() >= n) {
            BitSet recovered = new BitSet(n);
            boolean progress = true;
            while (progress && recovered.cardinality() < n) {
                progress = false;
                for (Term arg : ctor.args()) {
                    int unknownParam = -1;
                    boolean multipleUnknown = false;
                    for (int p = 0; p < n; p++) {
                        if (recovered.get(p)) {
                            continue;
                        }
                        if (evalRel(arg, p).sign() != Sign.CONST) {
                            if (unknownParam >= 0) {
                                multipleUnknown = true;
                                break;
                            }
                            unknownParam = p;
                        }
                    }
                    if (!multipleUnknown && unknownParam >= 0 && evalRel(arg, unknownParam).injective()) {
                        recovered.set(unknownParam);
                        progress = true;
                    }
                }
            }
            return recovered.cardinality() == n;
        }
        return false;
    }

    static RelVal evalRel(Term term, int targetParam) {
        return switch (term) {
            case Param p -> p.index() == targetParam
                    ? new RelVal(isOrderedType(p.type()) ? Sign.INC : Sign.TOP, true)
                    : RelVal.CONST;
            case Const c -> RelVal.CONST;
            case Opaque o -> RelVal.CONST;
            case Unary u -> evalUnaryRel(u, targetParam);
            case Binary b -> evalBinaryRel(b, targetParam);
            case Cast c -> evalCastRel(c, targetParam);
            case Ite ite -> evalIteRel(ite, targetParam);
            case Call c -> evalCallRel(c, targetParam);
            case Ctor c -> evalCtorRel(c, targetParam);
        };
    }

    private static RelVal evalUnaryRel(Unary u, int targetParam) {
        RelVal sub = evalRel(u.operand(), targetParam);
        if (sub.sign() == Sign.CONST) {
            return RelVal.CONST;
        }
        if (u.op() == ENode.Op.NEG) {
            if (u.kind() == PrimitiveKind.INT || u.kind() == PrimitiveKind.LONG) {
                // Wrapping negation is an involution (injective), NOT monotone.
                return new RelVal(Sign.TOP, sub.injective());
            }
            if (u.kind() == PrimitiveKind.FLOAT || u.kind() == PrimitiveKind.DOUBLE) {
                return new RelVal(sub.sign().negate(), sub.injective());
            }
        }
        if (u.op() == ENode.Op.NOT && u.kind() == PrimitiveKind.BOOL) {
            return new RelVal(sub.sign().negate(), sub.injective());
        }
        return RelVal.TOP;
    }

    private static RelVal evalBinaryRel(Binary b, int targetParam) {
        RelVal l = evalRel(b.left(), targetParam);
        RelVal r = evalRel(b.right(), targetParam);
        if (l.sign() == Sign.CONST && r.sign() == Sign.CONST) {
            return RelVal.CONST;
        }
        if (b.kind() == PrimitiveKind.INT || b.kind() == PrimitiveKind.LONG) {
            // Wrapping 32/64-bit integer arithmetic is never monotone.
            if (l.sign() != Sign.CONST && r.sign() == Sign.CONST) {
                boolean inj = switch (b.op()) {
                    case ADD, SUB -> l.injective();
                    case MUL -> l.injective() && isOddIntegralConst(b.right());
                    default -> false;
                };
                return new RelVal(Sign.TOP, inj);
            }
            if (l.sign() == Sign.CONST && r.sign() != Sign.CONST) {
                boolean inj = switch (b.op()) {
                    case ADD, SUB -> r.injective();
                    case MUL -> r.injective() && isOddIntegralConst(b.left());
                    default -> false;
                };
                return new RelVal(Sign.TOP, inj);
            }
            return RelVal.TOP;
        }
        if (b.kind() == PrimitiveKind.DOUBLE || b.kind() == PrimitiveKind.FLOAT) {
            if (b.op() == ENode.Op.ADD) {
                return new RelVal(Sign.add(l.sign(), r.sign()), false);
            }
            if (b.op() == ENode.Op.SUB) {
                return new RelVal(Sign.add(l.sign(), r.sign().negate()), false);
            }
            if (b.op() == ENode.Op.MUL) {
                if (l.sign() != Sign.CONST && r.sign() == Sign.CONST) {
                    Double k = evalConstDouble(b.right());
                    return new RelVal(k == null ? Sign.TOP : (k >= 0.0 ? l.sign() : l.sign().negate()), false);
                }
                if (l.sign() == Sign.CONST && r.sign() != Sign.CONST) {
                    Double k = evalConstDouble(b.left());
                    return new RelVal(k == null ? Sign.TOP : (k >= 0.0 ? r.sign() : r.sign().negate()), false);
                }
            }
            if (b.op() == ENode.Op.DIV && l.sign() != Sign.CONST && r.sign() == Sign.CONST) {
                Double k = evalConstDouble(b.right());
                if (k != null && k != 0.0) {
                    return new RelVal(k > 0.0 ? l.sign() : l.sign().negate(), false);
                }
            }
            return RelVal.TOP;
        }
        if (b.op() == ENode.Op.STR_ADD) {
            if (l.sign() != Sign.CONST && r.sign() == Sign.CONST) {
                return new RelVal(Sign.TOP, l.injective() && isLosslessToStringType(b.left().type()));
            }
            if (l.sign() == Sign.CONST && r.sign() != Sign.CONST) {
                return new RelVal(Sign.TOP, r.injective() && isLosslessToStringType(b.right().type()));
            }
        }
        return RelVal.TOP;
    }

    private static RelVal evalCastRel(Cast c, int targetParam) {
        RelVal sub = evalRel(c.operand(), targetParam);
        if (sub.sign() == Sign.CONST) {
            return RelVal.CONST;
        }
        Type src = c.srcType();
        Type dst = c.type();
        if (src.equals(dst)) {
            return sub;
        }
        if (isMonotoneNumericWidening(src, dst)) {
            boolean lossless = isLosslessWidening(src, dst);
            return new RelVal(sub.sign(), lossless && sub.injective());
        }
        return RelVal.TOP;
    }

    private static RelVal evalIteRel(Ite ite, int targetParam) {
        RelVal c = evalRel(ite.cond(), targetParam);
        RelVal t = evalRel(ite.thenBranch(), targetParam);
        RelVal e = evalRel(ite.elseBranch(), targetParam);
        if (c.sign() == Sign.CONST) {
            return new RelVal(Sign.join(t.sign(), e.sign()), t.injective() && e.injective());
        }
        // Order-preserving min/max patterns: `if a < b then (a) else (b)` etc.
        if (isOrderedType(ite.type()) && isMinMaxIte(ite)) {
            return new RelVal(Sign.join(t.sign(), e.sign()), false);
        }
        return RelVal.TOP;
    }

    private static boolean isMinMaxIte(Ite ite) {
        if (!(ite.cond() instanceof Binary cmp)) {
            return false;
        }
        if (cmp.op() != ENode.Op.LT && cmp.op() != ENode.Op.LTE
                && cmp.op() != ENode.Op.GT && cmp.op() != ENode.Op.GTE) {
            return false;
        }
        return (cmp.left().equals(ite.thenBranch()) && cmp.right().equals(ite.elseBranch()))
                || (cmp.left().equals(ite.elseBranch()) && cmp.right().equals(ite.thenBranch()));
    }

    private static RelVal evalCallRel(Call call, int targetParam) {
        List<Term> args = call.args();
        if (args.isEmpty()) {
            return RelVal.CONST;
        }
        RelVal[] argRels = new RelVal[args.size()];
        int nonConstCount = 0;
        int singleNonConst = -1;
        for (int i = 0; i < args.size(); i++) {
            argRels[i] = evalRel(args.get(i), targetParam);
            if (argRels[i].sign() != Sign.CONST) {
                nonConstCount++;
                singleNonConst = i;
            }
        }
        if (nonConstCount == 0) {
            return RelVal.CONST;
        }
        EffectSet fx = call.calleeEffects();
        if (fx == null || !fx.isPure() || !fx.isDeterministic()) {
            return RelVal.TOP;
        }
        Sign monoSign = Sign.TOP;
        if (fx.has(Effect.MONOTONE)) {
            Sign combined = Sign.CONST;
            for (RelVal ar : argRels) {
                combined = Sign.join(combined, ar.sign());
            }
            if (combined == Sign.INC || combined == Sign.DEC) {
                monoSign = combined;
            }
        }
        boolean inj = fx.has(Effect.INJECTIVE) && nonConstCount == 1
                && singleNonConst == 0 && argRels[0].injective();
        return new RelVal(monoSign, inj);
    }

    private static RelVal evalCtorRel(Ctor ctor, int targetParam) {
        boolean anyDepends = false;
        boolean anyInj = false;
        for (Term arg : ctor.args()) {
            RelVal ar = evalRel(arg, targetParam);
            if (ar.sign() != Sign.CONST) {
                anyDepends = true;
                if (ctor.injectiveValueCtor() && ar.injective()) {
                    anyInj = true;
                }
            }
        }
        if (!anyDepends) {
            return RelVal.CONST;
        }
        return new RelVal(Sign.TOP, anyInj);
    }

    private static boolean isLosslessToStringType(Type type) {
        return type == Type.STRING || type == Type.BYTE || type == Type.SHORT
                || type == Type.INT || type == Type.LONG || type == Type.CHAR || type == Type.BOOL;
    }

    private static boolean isMonotoneNumericWidening(Type src, Type dst) {
        if (src == Type.CHAR) {
            return Type.isNumericScalar(dst) && Type.numericRank(dst) >= 2;
        }
        return Type.isNumericScalar(src) && Type.isNumericScalar(dst) && Type.numericRank(src) <= Type.numericRank(dst);
    }

    private static boolean isLosslessWidening(Type src, Type dst) {
        if (src == Type.CHAR) {
            return Type.isNumericScalar(dst) && Type.numericRank(dst) >= 2;
        }
        if (!Type.isNumericScalar(src) || !Type.isNumericScalar(dst)) {
            return false;
        }
        int sr = Type.numericRank(src);
        int dr = Type.numericRank(dst);
        return sr < dr && !(sr == 2 && dr == 4) && !(sr == 3 && dr >= 4);
    }

    private static boolean isOddIntegralConst(Term term) {
        Number n = evalConstNumber(term);
        return (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long)
                && (n.longValue() & 1L) != 0L;
    }

    private static Double evalConstDouble(Term term) {
        Number n = evalConstNumber(term);
        if (n == null) {
            return null;
        }
        double d = n.doubleValue();
        return Double.isFinite(d) ? d : null;
    }

    private static Number evalConstNumber(Term term) {
        if (term instanceof Const c && c.value() instanceof Number n) {
            return n;
        }
        if (term instanceof Cast c && isMonotoneNumericWidening(c.srcType(), c.type())) {
            return evalConstNumber(c.operand());
        }
        EGraph g = new EGraph();
        int id = lowerConst(term, g);
        if (id < 0) {
            return null;
        }
        ENode c = g.constantOf(id);
        return c != null && c.literal() instanceof Number n ? n : null;
    }

    private static int lowerConst(Term term, EGraph g) {
        if (term instanceof Const c && c.value() instanceof Number) {
            return g.add(ENode.constVal(c.value(), c.kind()));
        }
        if (term instanceof Cast c && isMonotoneNumericWidening(c.srcType(), c.type())) {
            Number n = evalConstNumber(c.operand());
            return n != null ? g.add(ENode.constVal(n, c.kind())) : -1;
        }
        if (term instanceof Unary u && u.op() == ENode.Op.NEG) {
            int sub = lowerConst(u.operand(), g);
            return sub >= 0 ? g.add(ENode.unary(ENode.Op.NEG, u.kind(), sub)) : -1;
        }
        if (term instanceof Binary b) {
            int l = lowerConst(b.left(), g);
            int r = lowerConst(b.right(), g);
            return l >= 0 && r >= 0 ? g.add(ENode.binary(b.op(), b.kind(), l, r)) : -1;
        }
        return -1;
    }
}
