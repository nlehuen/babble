package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Backend-independent HIR lowering predicates and match switch-run analysis shared across {@code
 * ir.mir}, {@code opt}, and {@code compiler}.
 */
public final class HirLoweringRules {
    public static final int MIN_SWITCH_KEYS = 3;

    private HirLoweringRules() {}

    public static boolean canLowerComparison(
            HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk) {
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return lk.isNumeric() && rk.isNumeric();
    }

    public static boolean canLowerComparison(HirExpr.Binary b) {
        return canLowerComparison(b.op(), b.left().meta().kind(), b.right().meta().kind());
    }

    /**
     * Checks whether a compound assignment of {@code targetKind} with {@code rhsKind} and {@code
     * op} can lower directly to primitive arithmetic.
     */
    public static boolean canLowerPrimitiveCompound(
            PrimitiveKind targetKind, PrimitiveKind rhsKind, String op) {
        if (targetKind == PrimitiveKind.INT) {
            return (rhsKind == PrimitiveKind.INT
                            || rhsKind == PrimitiveKind.BYTE
                            || rhsKind == PrimitiveKind.SHORT)
                    && !"/".equals(op);
        }
        if (targetKind == PrimitiveKind.LONG && "//".equals(op)) {
            return rhsKind == PrimitiveKind.BYTE
                    || rhsKind == PrimitiveKind.SHORT
                    || rhsKind == PrimitiveKind.INT
                    || rhsKind == PrimitiveKind.LONG;
        }
        if (targetKind == PrimitiveKind.DOUBLE) {
            return (rhsKind == PrimitiveKind.INT || rhsKind == PrimitiveKind.DOUBLE)
                    && !"//".equals(op);
        }
        return false;
    }

    public static boolean isNonZeroIntegerConst(HirExpr expr) {
        if (expr instanceof HirExpr.Const c && c.value() instanceof Number n) {
            return n.longValue() != 0L;
        }
        if (expr instanceof HirExpr.Unary u
                && u.op() == HirExpr.UnaryOp.NEGATE
                && u.operand() instanceof HirExpr.Const c
                && c.value() instanceof Number n) {
            return n.longValue() != 0L;
        }
        return false;
    }

    public static HirExpr.BinaryOp compoundOp(String op) {
        return switch (op) {
            case "+" -> HirExpr.BinaryOp.ADD;
            case "-" -> HirExpr.BinaryOp.SUB;
            case "*" -> HirExpr.BinaryOp.MUL;
            case "/" -> HirExpr.BinaryOp.DIV;
            case "//" -> HirExpr.BinaryOp.FLOOR_DIV;
            default -> throw new IllegalStateException("Unexpected compound operator : " + op);
        };
    }

    // ---------------------------------------------------------------- match switch-run analysis

    public enum MatchRunKind {
        INT_OR_CHAR,
        ENUM,
        STRING
    }

    public record MatchConstantRun(
            MatchRunKind kind,
            int startInclusive,
            int endExclusive,
            Class<?> enumClass,
            boolean scrutineeGuaranteedString,
            int nullArmIndex,
            Map<Integer, Integer> intKeysToArm,
            Map<String, Integer> stringOrEnumKeysToArm) {}

    public static MatchConstantRun detectMatchConstantRun(
            List<HirExpr.MatchArm> arms,
            int start,
            PrimitiveKind scrutKind,
            boolean scrutIsString) {
        boolean canIntSwitch =
                scrutKind == PrimitiveKind.BYTE
                        || scrutKind == PrimitiveKind.SHORT
                        || scrutKind == PrimitiveKind.INT
                        || scrutKind == PrimitiveKind.CHAR;
        if (canIntSwitch) {
            MatchConstantRun intRun = scanIntRun(arms, start);
            if (intRun != null) {
                return intRun;
            }
        }
        if (scrutKind == PrimitiveKind.NONE) {
            MatchConstantRun enumRun = scanEnumRun(arms, start);
            if (enumRun != null) {
                return enumRun;
            }
            MatchConstantRun strRun = scanStringRun(arms, start, scrutIsString);
            if (strRun != null) {
                return strRun;
            }
        }
        return null;
    }

    public static List<HirPattern.Const> collectPureConsts(HirPattern p) {
        if (p instanceof HirPattern.Const c) {
            return List.of(c);
        }
        if (p instanceof HirPattern.Or o) {
            List<HirPattern.Const> out = new ArrayList<>(o.alternatives().size());
            for (HirPattern alt : o.alternatives()) {
                List<HirPattern.Const> sub = collectPureConsts(alt);
                if (sub == null) {
                    return null;
                }
                out.addAll(sub);
            }
            return out;
        }
        return null;
    }

    public static boolean isSwitchableIntConst(HirPattern.Const c) {
        Object v = c.value();
        return v instanceof Byte
                || v instanceof Short
                || v instanceof Integer
                || v instanceof Character;
    }

    public static int intConstValue(HirPattern.Const c) {
        Object v = c.value();
        if (v instanceof Character ch) {
            return ch;
        }
        return ((Number) v).intValue();
    }

    private static MatchConstantRun scanIntRun(List<HirExpr.MatchArm> arms, int start) {
        TreeMap<Integer, Integer> keys = new TreeMap<>();
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean allInt = true;
            for (HirPattern.Const c : consts) {
                if (!isSwitchableIntConst(c)) {
                    allInt = false;
                    break;
                }
            }
            if (!allInt) {
                break;
            }
            for (HirPattern.Const c : consts) {
                keys.putIfAbsent(intConstValue(c), end);
            }
            end++;
        }
        if (keys.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new MatchConstantRun(
                MatchRunKind.INT_OR_CHAR, start, end, null, false, -1, keys, Map.of());
    }

    private static MatchConstantRun scanEnumRun(List<HirExpr.MatchArm> arms, int start) {
        Class<?> enumClass = null;
        LinkedHashMap<String, Integer> constToArm = new LinkedHashMap<>();
        int nullArm = -1;
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean ok = true;
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    continue;
                }
                if (!c.isEnum()) {
                    ok = false;
                    break;
                }
                if (enumClass == null) {
                    enumClass = c.enumClass();
                } else if (enumClass != c.enumClass()) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                break;
            }
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    if (nullArm < 0) {
                        nullArm = end;
                    }
                } else {
                    constToArm.putIfAbsent(c.enumConstantName(), end);
                }
            }
            end++;
        }
        if (enumClass == null || constToArm.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new MatchConstantRun(
                MatchRunKind.ENUM, start, end, enumClass, false, nullArm, Map.of(), constToArm);
    }

    private static MatchConstantRun scanStringRun(
            List<HirExpr.MatchArm> arms, int start, boolean scrutIsString) {
        LinkedHashMap<String, Integer> strToArm = new LinkedHashMap<>();
        int nullArm = -1;
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean ok = true;
            for (HirPattern.Const c : consts) {
                if (!c.isNull() && !(c.value() instanceof String)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                break;
            }
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    if (nullArm < 0) {
                        nullArm = end;
                    }
                } else {
                    strToArm.putIfAbsent((String) c.value(), end);
                }
            }
            end++;
        }
        if (strToArm.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new MatchConstantRun(
                MatchRunKind.STRING, start, end, null, scrutIsString, nullArm, Map.of(), strToArm);
    }
}
