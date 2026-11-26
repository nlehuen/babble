package org.babblelang.ir.hir;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Sealed hierarchy of target-neutral HIR patterns for {@link HirExpr.Match} ({@code BIP038-D10}).
 * Every node is an immutable Java 21 {@code record} detached from ANTLR4.
 */
public sealed interface HirPattern
        permits HirPattern.Const,
                HirPattern.ValueExpr,
                HirPattern.TypeTest,
                HirPattern.Bind,
                HirPattern.Wildcard,
                HirPattern.Or,
                HirPattern.Relational,
                HirPattern.RangeTest,
                HirPattern.Membership,
                HirPattern.RecordDestructure,
                HirPattern.SequenceDestructure {

    int line();

    /**
     * Compile-time constant: {@code null}, scalar literal, {@code String}, or Java {@code enum}
     * constant.
     */
    record Const(
            Object value, Class<?> enumClass, String enumConstantName, PrimitiveKind kind, int line)
            implements HirPattern {
        public Const {
            kind = kind != null ? kind : PrimitiveKind.NONE;
        }

        public boolean isEnum() {
            return enumClass != null && enumConstantName != null;
        }

        public boolean isNull() {
            return value == null && enumClass == null;
        }
    }

    /** Runtime expression value pattern compared via Babble {@code ==}. */
    record ValueExpr(HirExpr expr, int line) implements HirPattern {}

    /** Runtime type check ({@code is T}, single or intersection chain). */
    record TypeTest(
            String builtin,
            LoweringTargets.IsTarget target,
            List<LoweringTargets.IsTarget> intersectionTargets,
            String descriptor,
            int line)
            implements HirPattern {
        public TypeTest {
            intersectionTargets =
                    intersectionTargets == null ? List.of() : List.copyOf(intersectionTargets);
        }
    }

    /** Pattern binding {@code def x} with optional inner pattern (e.g. {@link TypeTest}). */
    record Bind(HirVar var, HirPattern inner, int line) implements HirPattern {}

    /** Nested wildcard {@code _}. */
    record Wildcard(int line) implements HirPattern {}

    /** Binding-free OR-pattern {@code p1 | p2 | ...}. */
    record Or(List<HirPattern> alternatives, int line) implements HirPattern {
        public Or {
            alternatives = List.copyOf(alternatives);
        }
    }

    /**
     * Relational pattern ({@code <}, {@code <=}, {@code >}, {@code >=}, {@code ==}, {@code !=}).
     */
    record Relational(HirExpr.BinaryOp op, HirExpr rhs, PrimitiveKind scrutineeKind, int line)
            implements HirPattern {
        public Relational {
            scrutineeKind = scrutineeKind != null ? scrutineeKind : PrimitiveKind.NONE;
        }
    }

    /**
     * Zero-allocation {@code BIP031} range-membership pattern ({@code lo..hi}, {@code lo..<hi},
     * optional {@code .step(k)}).
     */
    record RangeTest(
            HirExpr lo,
            HirExpr hi,
            HirExpr step,
            boolean inclusive,
            PrimitiveKind elemKind,
            boolean stringElement,
            int line)
            implements HirPattern {
        public RangeTest {
            elemKind = elemKind != null ? elemKind : PrimitiveKind.NONE;
        }
    }

    /**
     * Membership pattern {@code in container} ({@code Range}, {@code Collection}, {@code Map}, or
     * {@code array}).
     */
    record Membership(
            HirExpr container,
            LoweringTargets.MembershipKind kind,
            PrimitiveKind elemKind,
            int line)
            implements HirPattern {
        public Membership {
            kind = kind != null ? kind : LoweringTargets.MembershipKind.DYNAMIC;
            elemKind = elemKind != null ? elemKind : PrimitiveKind.NONE;
        }
    }

    /** Babble or Java {@code record} destructuring pattern ({@code BIP038-D6}). */
    record RecordDestructure(
            TypeTest typeTest,
            boolean isJavaRecord,
            int babbleClassId,
            String babbleTypeId,
            String babbleJvmName,
            Class<?> javaRecordClass,
            List<String> fieldNames,
            int[] babbleMemberIndices,
            List<PrimitiveKind> fieldKinds,
            List<java.lang.reflect.Method> javaAccessors,
            List<HirPattern> fieldPatterns,
            int line)
            implements HirPattern {
        public RecordDestructure {
            fieldNames = List.copyOf(fieldNames);
            babbleMemberIndices =
                    babbleMemberIndices != null ? babbleMemberIndices.clone() : new int[0];
            fieldKinds = List.copyOf(fieldKinds);
            javaAccessors = javaAccessors != null ? List.copyOf(javaAccessors) : List.of();
            fieldPatterns = List.copyOf(fieldPatterns);
        }
    }

    /** Sequence destructuring pattern for {@code list} and {@code array} ({@code BIP038-D7}). */
    record SequenceDestructure(
            TypeTest typeTest,
            boolean isArray,
            Class<?> arrayClass,
            PrimitiveKind elemKind,
            List<HirPattern> elemPatterns,
            boolean hasRest,
            HirVar restVar,
            int line)
            implements HirPattern {
        public SequenceDestructure {
            elemKind = elemKind != null ? elemKind : PrimitiveKind.NONE;
            elemPatterns = List.copyOf(elemPatterns);
        }
    }
}
