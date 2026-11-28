package org.babblelang.ir.egraph;

import java.util.Arrays;
import java.util.Objects;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Immutable pure-expression E-node in the shared equality saturation engine ({@code BIP027-D1},
 * anticipating Half A of {@code BIP014-I5}).
 *
 * <p>Zero ANTLR dependencies; shared by the HIR optimizer ({@code HirEGraphSimplifyPass}) and the
 * effect checker's algebraic law verifier ({@code AlgebraicVerifier}).
 */
public final class ENode {
    public enum Op {
        CONST,
        OPAQUE,
        NEG,
        NOT,
        ADD,
        SUB,
        MUL,
        DIV,
        FLOOR_DIV,
        MOD,
        EQ,
        NEQ,
        LT,
        LTE,
        GT,
        GTE,
        AND,
        OR,
        STR_ADD,
        ITE,
        CALL,
        PIPE_SRC,
        PIPE_RANGE,
        PIPE_MATERIALIZE,
        PIPE_FILTER,
        PIPE_MAP,
        PIPE_DISTINCT,
        PIPE_TAKE_WHILE,
        PIPE_GUARD_RANGE,
        PIPE_FOR_EACH,
        PIPE_FOLD,
        PIPE_FOLD_PAIRWISE;

        public boolean isPipelineOp() {
            return ordinal() >= PIPE_SRC.ordinal();
        }
    }

    private static final int[] EMPTY = new int[0];

    private final Op op;
    private final String symbol;
    private final Object literal;
    private final PrimitiveKind kind;
    private final int[] children;
    private final int hash;

    public ENode(Op op, String symbol, Object literal, PrimitiveKind kind, int[] children) {
        this.op = Objects.requireNonNull(op, "op");
        this.symbol = symbol;
        this.literal = literal;
        this.kind = kind != null ? kind : PrimitiveKind.NONE;
        this.children = (children == null || children.length == 0) ? EMPTY : children.clone();
        this.hash = computeHash(this.op, this.symbol, this.literal, this.kind, this.children);
    }

    private static int computeHash(
            Op op, String symbol, Object literal, PrimitiveKind kind, int[] children) {
        int h = op.hashCode();
        h = 31 * h + Objects.hashCode(symbol);
        h = 31 * h + literalHash(literal);
        h = 31 * h + kind.hashCode();
        h = 31 * h + Arrays.hashCode(children);
        return h;
    }

    private static int literalHash(Object v) {
        if (v instanceof Double d) {
            return Long.hashCode(Double.doubleToRawLongBits(d));
        }
        if (v instanceof Float f) {
            return Integer.hashCode(Float.floatToRawIntBits(f));
        }
        return Objects.hashCode(v);
    }

    private static boolean literalEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Double da && b instanceof Double db) {
            return Double.doubleToRawLongBits(da) == Double.doubleToRawLongBits(db);
        }
        if (a instanceof Float fa && b instanceof Float fb) {
            return Float.floatToRawIntBits(fa) == Float.floatToRawIntBits(fb);
        }
        return a.equals(b);
    }

    public static ENode constVal(Object literal, PrimitiveKind kind) {
        return new ENode(Op.CONST, null, literal, kind, EMPTY);
    }

    public static ENode opaque(String symbol, PrimitiveKind kind) {
        return new ENode(Op.OPAQUE, symbol, null, kind, EMPTY);
    }

    public static ENode unary(Op op, PrimitiveKind kind, int child) {
        return new ENode(op, null, null, kind, new int[] {child});
    }

    public static ENode binary(Op op, PrimitiveKind kind, int left, int right) {
        return new ENode(op, null, null, kind, new int[] {left, right});
    }

    public static ENode ite(PrimitiveKind kind, int cond, int thenBranch, int elseBranch) {
        return new ENode(Op.ITE, null, null, kind, new int[] {cond, thenBranch, elseBranch});
    }

    public static ENode call(String symbol, PrimitiveKind kind, int... args) {
        return new ENode(Op.CALL, symbol, null, kind, args);
    }

    public static ENode pipeSrc(String srcId, PrimitiveKind elemKind) {
        return new ENode(Op.PIPE_SRC, srcId, null, elemKind, EMPTY);
    }

    public static ENode pipeRange(String rangeSpec, PrimitiveKind elemKind, int lo, int hi) {
        return new ENode(Op.PIPE_RANGE, rangeSpec, null, elemKind, new int[] {lo, hi});
    }

    public static ENode pipeMaterialize(String tmpId, int stream) {
        return new ENode(Op.PIPE_MATERIALIZE, tmpId, null, PrimitiveKind.NONE, new int[] {stream});
    }

    public static ENode pipeFilter(boolean narrowed, int stream, int pred) {
        return new ENode(
                Op.PIPE_FILTER,
                narrowed ? "narrow" : "",
                null,
                PrimitiveKind.NONE,
                new int[] {stream, pred});
    }

    public static ENode pipeMap(String outBinder, PrimitiveKind outKind, int stream, int expr) {
        return new ENode(Op.PIPE_MAP, outBinder, null, outKind, new int[] {stream, expr});
    }

    public static ENode pipeDistinct(String setId, int stream) {
        return new ENode(Op.PIPE_DISTINCT, setId, null, PrimitiveKind.NONE, new int[] {stream});
    }

    public static ENode pipeTakeWhile(int stream, int pred) {
        return new ENode(
                Op.PIPE_TAKE_WHILE, "", null, PrimitiveKind.NONE, new int[] {stream, pred});
    }

    public static ENode pipeGuardRange(int rangeStream, int pred, int guardCond) {
        return new ENode(
                Op.PIPE_GUARD_RANGE,
                "",
                null,
                PrimitiveKind.NONE,
                new int[] {rangeStream, pred, guardCond});
    }

    public static ENode pipeForEach(String sinkId, int stream) {
        return new ENode(Op.PIPE_FOR_EACH, sinkId, null, PrimitiveKind.NONE, new int[] {stream});
    }

    public static ENode pipeFold(String foldSpec, PrimitiveKind accKind, int stream, int elemExpr) {
        return new ENode(Op.PIPE_FOLD, foldSpec, null, accKind, new int[] {stream, elemExpr});
    }

    public static ENode pipeFoldPairwise(
            String foldSpec, PrimitiveKind accKind, int stream, int elemExpr) {
        return new ENode(
                Op.PIPE_FOLD_PAIRWISE, foldSpec, null, accKind, new int[] {stream, elemExpr});
    }

    public Op op() {
        return op;
    }

    public String symbol() {
        return symbol;
    }

    public Object literal() {
        return literal;
    }

    public PrimitiveKind kind() {
        return kind;
    }

    public int arity() {
        return children.length;
    }

    public int child(int index) {
        return children[index];
    }

    public ENode withChildren(int[] newChildren) {
        if (Arrays.equals(this.children, newChildren)) {
            return this;
        }
        return new ENode(op, symbol, literal, kind, newChildren);
    }

    public boolean isConst() {
        return op == Op.CONST;
    }

    public boolean isIntegral() {
        return kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG;
    }

    public boolean isExactOrdered() {
        return kind == PrimitiveKind.BYTE
                || kind == PrimitiveKind.SHORT
                || kind == PrimitiveKind.INT
                || kind == PrimitiveKind.LONG
                || kind == PrimitiveKind.CHAR;
    }

    /**
     * True when {@code ==} / {@code !=} on this node's type is guaranteed reflexive: all 8 Babble
     * primitive kinds (Babble's {@code ==}/{@code !=} uses {@code Float.compare}/{@code
     * Double.compare} total order, so {@code NaN == NaN} is {@code true} and {@code NaN != NaN} is
     * {@code false}) and guaranteed {@code string} nodes ({@link Op#STR_ADD} or {@code String}
     * constants). Excludes arbitrary {@code any}/{@code Object} references whose {@code equals} may
     * throw or violate reflexivity.
     */
    public boolean isReflexiveEq() {
        return kind.isPrimitive()
                || op == Op.STR_ADD
                || (op == Op.CONST && literal instanceof String);
    }

    public boolean isFloating() {
        return kind == PrimitiveKind.FLOAT || kind == PrimitiveKind.DOUBLE;
    }

    public Long asIntegralLong() {
        if (op == Op.CONST && literal instanceof Number n && isIntegral()) {
            return kind == PrimitiveKind.LONG ? n.longValue() : (long) n.intValue();
        }
        return null;
    }

    public Boolean asBool() {
        if (op == Op.CONST && literal instanceof Boolean b && kind == PrimitiveKind.BOOL) {
            return b;
        }
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ENode other)) {
            return false;
        }
        return op == other.op
                && kind == other.kind
                && Objects.equals(symbol, other.symbol)
                && literalEquals(literal, other.literal)
                && Arrays.equals(children, other.children);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return switch (op) {
            case CONST -> String.valueOf(literal) + ":" + kind;
            case OPAQUE -> symbol + ":" + kind;
            case CALL -> symbol + Arrays.toString(children);
            default -> op.name() + ":" + kind + Arrays.toString(children);
        };
    }
}
