package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowering metadata sub-view of {@link ScopeAnalysis} (BIP027-D3): primitive kinds,
 * definite initialization, numeric widenings, runtime check descriptors, and direct
 * Java/Babble/array/for-loop lowering targets.
 */
public final class LoweringMetadata {
    private final ParseTreeProperty<LoweringTargets.ForLoopTarget> forLoopTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.DefTarget> defTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.IsTarget> isTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<List<LoweringTargets.IsTarget>> isIntersectionTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.IsTarget> asTargets = new ParseTreeProperty<>();
    private final Map<ParserRuleContext, LoweringTargets.JavaSamTarget> javaSamTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.ForExpressionContext, LoweringTargets.JavaSamTarget> forElemSamTargets = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.JavaSamTarget> asSamTargets = new IdentityHashMap<>();
    private final ParseTreeProperty<LoweringTargets.InterfaceCallTarget> interfaceCallTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<String> checkedTypes = new ParseTreeProperty<>();
    private final ParseTreeProperty<String> isBuiltins = new ParseTreeProperty<>();
    private final Map<ParserRuleContext, Integer> numericWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Object> resolvedLiterals = new IdentityHashMap<>();
    private final ParseTreeProperty<PrimitiveKind> expressionKinds = new ParseTreeProperty<>();
    private final Set<ParserRuleContext> expressionDeeplyImmutable = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> expressionStrings = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ParseTreeProperty<PrimitiveKind> declarationKinds = new ParseTreeProperty<>();
    private final ParseTreeProperty<PrimitiveKind> functionReturnKinds = new ParseTreeProperty<>();
    private final Set<ParserRuleContext> definitelyDefined = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ParseTreeProperty<LoweringTargets.JavaCallTarget> javaCallTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.JavaConstructorTarget> javaConstructorTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.JavaFieldTarget> javaFieldTargets = new ParseTreeProperty<>();
    private final Map<ParserRuleContext, LoweringTargets.ArrayCreationTarget> arrayCreationTargets = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.ArrayOpTarget> arrayOpTargets = new IdentityHashMap<>();
    private final ParseTreeProperty<LoweringTargets.ModuleExportTarget> moduleExportTargets = new ParseTreeProperty<>();
    private final Map<BabbleParser.NonNullAssertionContext, String> nonNullAssertionTargets = new HashMap<>();
    private final Map<BabbleParser.ThrowExpressionContext, LoweringTargets.ThrowTarget> throwTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.CatchClauseContext, LoweringTargets.CatchTarget> catchTargets = new IdentityHashMap<>();

    LoweringMetadata() {
    }

    void setDefTarget(BabbleParser.DefExpressionContext def, LoweringTargets.DefTarget target) {
        defTargets.put(def, target);
    }

    public LoweringTargets.DefTarget defTarget(BabbleParser.DefExpressionContext def) {
        LoweringTargets.DefTarget target = defTargets.get(def);
        return target != null ? target : new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1);
    }

    void setIsTarget(BabbleParser.IsExpressionContext ctx, LoweringTargets.IsTarget target) {
        isTargets.put(ctx, target);
    }

    public LoweringTargets.IsTarget isTarget(BabbleParser.IsExpressionContext ctx) {
        return isTargets.get(ctx);
    }

    void setIsIntersectionTargets(BabbleParser.IsExpressionContext ctx, List<LoweringTargets.IsTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            isIntersectionTargets.removeFrom(ctx);
        } else {
            isIntersectionTargets.put(ctx, List.copyOf(targets));
        }
    }

    public List<LoweringTargets.IsTarget> isIntersectionTargets(BabbleParser.IsExpressionContext ctx) {
        List<LoweringTargets.IsTarget> targets = isIntersectionTargets.get(ctx);
        return targets != null ? targets : List.of();
    }

    void setAsTarget(BabbleParser.AsExpressionContext ctx, LoweringTargets.IsTarget target) {
        asTargets.put(ctx, target);
    }

    public LoweringTargets.IsTarget asTarget(BabbleParser.AsExpressionContext ctx) {
        return asTargets.get(ctx);
    }

    void setJavaSamTarget(ParserRuleContext ctx, LoweringTargets.JavaSamTarget target) {
        if (target == null) {
            javaSamTargets.remove(ctx);
        } else {
            javaSamTargets.put(ctx, target);
        }
    }

    public LoweringTargets.JavaSamTarget javaSamTarget(ParserRuleContext ctx) {
        return javaSamTargets.get(ctx);
    }

    void setForElemSamTarget(BabbleParser.ForExpressionContext ctx, LoweringTargets.JavaSamTarget target) {
        if (target == null) {
            forElemSamTargets.remove(ctx);
        } else {
            forElemSamTargets.put(ctx, target);
        }
    }

    public LoweringTargets.JavaSamTarget forElemSamTarget(BabbleParser.ForExpressionContext ctx) {
        return forElemSamTargets.get(ctx);
    }

    void setAsSamTarget(ParserRuleContext ctx, LoweringTargets.JavaSamTarget target) {
        if (target == null) {
            asSamTargets.remove(ctx);
        } else {
            asSamTargets.put(ctx, target);
        }
    }

    public LoweringTargets.JavaSamTarget asSamTarget(ParserRuleContext ctx) {
        return asSamTargets.get(ctx);
    }

    void setInterfaceCallTarget(BabbleParser.CallContext ctx, LoweringTargets.InterfaceCallTarget target) {
        interfaceCallTargets.put(ctx, target);
    }

    public LoweringTargets.InterfaceCallTarget interfaceCallTarget(BabbleParser.CallContext ctx) {
        return interfaceCallTargets.get(ctx);
    }

    void setCheckedType(ParserRuleContext ctx, String descriptor) {
        checkedTypes.put(ctx, descriptor);
    }

    /**
     * The target type to check at runtime (a {@code Types} descriptor) for an {@code as}
     * cast, or null when no cast check applies.
     */
    public String checkedType(ParserRuleContext ctx) {
        return checkedTypes.get(ctx);
    }

    void setForLoopTarget(BabbleParser.ForExpressionContext ctx, LoweringTargets.ForLoopTarget target) {
        forLoopTargets.put(ctx, target);
    }

    public LoweringTargets.ForLoopTarget forLoopTarget(BabbleParser.ForExpressionContext ctx) {
        return forLoopTargets.get(ctx);
    }

    void setIsBuiltin(BabbleParser.IsExpressionContext ctx, String type) {
        isBuiltins.put(ctx, type);
    }

    /** The built-in type name a {@code value is T} test checks, or null for a normal type expression. */
    public String isBuiltin(BabbleParser.IsExpressionContext ctx) {
        return isBuiltins.get(ctx);
    }

    void widenNumeric(ParserRuleContext ctx, int targetRank) {
        if (targetRank >= 1 && targetRank <= 5) {
            numericWidenings.merge(ctx, targetRank, Math::max);
        }
    }

    /** Target numeric rank (1=short, 2=int, 3=long, 4=float, 5=double) if this construct widens numerically, else -1. */
    public int numericWideningRank(ParserRuleContext ctx) {
        Integer rank = numericWidenings.get(ctx);
        return rank != null ? rank : -1;
    }

    void setResolvedLiteral(ParserRuleContext ctx, Object value) {
        if (value == null) {
            resolvedLiterals.remove(ctx);
        } else {
            resolvedLiterals.put(ctx, value);
        }
    }

    /** Contextually typed or range-folded literal value, or null to use default literal parsing. */
    public Object resolvedLiteral(ParserRuleContext ctx) {
        return resolvedLiterals.get(ctx);
    }

    void setExpressionKind(ParserRuleContext ctx, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            expressionKinds.put(ctx, kind);
        }
    }

    /** The primitive kind of an expression, or {@link PrimitiveKind#NONE} for references/boxed values. */
    public PrimitiveKind expressionKind(ParserRuleContext ctx) {
        PrimitiveKind kind = expressionKinds.get(ctx);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    void markExpressionDeeplyImmutable(ParserRuleContext ctx) {
        expressionDeeplyImmutable.add(ctx);
    }

    /** Whether the converged static type of {@code ctx} is deeply immutable (scalars, null, frozen, deep record/entry). */
    public boolean isExpressionDeeplyImmutable(ParserRuleContext ctx) {
        return expressionDeeplyImmutable.contains(ctx);
    }

    void markExpressionString(ParserRuleContext ctx) {
        expressionStrings.add(ctx);
    }

    /** Whether the converged static type of {@code ctx} is {@code string}. */
    public boolean isExpressionString(ParserRuleContext ctx) {
        return expressionStrings.contains(ctx);
    }

    void setDeclarationKind(ParserRuleContext decl, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            declarationKinds.put(decl, kind);
        }
    }

    /** The storage primitive kind of a declaration, or {@link PrimitiveKind#NONE}. */
    public PrimitiveKind declarationKind(ParserRuleContext decl) {
        PrimitiveKind kind = declarationKinds.get(decl);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    void setFunctionReturnKind(BabbleParser.FunctionLiteralContext function, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            functionReturnKinds.put(function, kind);
        }
    }

    /** The return primitive kind of a function literal, or {@link PrimitiveKind#NONE}. */
    public PrimitiveKind functionReturnKind(BabbleParser.FunctionLiteralContext function) {
        PrimitiveKind kind = functionReturnKinds.get(function);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    void markDefinitelyDefined(ParserRuleContext decl) {
        definitelyDefined.add(decl);
    }

    /** Whether a {@code def} is provably initialized before any read or closure capture can observe it. */
    public boolean isDefinitelyDefined(ParserRuleContext decl) {
        return definitelyDefined.contains(decl);
    }

    void setJavaCallTarget(ParserRuleContext ctx, LoweringTargets.JavaCallTarget target) {
        javaCallTargets.put(ctx, target);
    }

    public LoweringTargets.JavaCallTarget javaCallTarget(ParserRuleContext ctx) {
        return javaCallTargets.get(ctx);
    }

    void setJavaConstructorTarget(ParserRuleContext ctx, LoweringTargets.JavaConstructorTarget target) {
        javaConstructorTargets.put(ctx, target);
    }

    public LoweringTargets.JavaConstructorTarget javaConstructorTarget(ParserRuleContext ctx) {
        return javaConstructorTargets.get(ctx);
    }

    void setJavaFieldTarget(ParserRuleContext ctx, LoweringTargets.JavaFieldTarget target) {
        javaFieldTargets.put(ctx, target);
    }

    public LoweringTargets.JavaFieldTarget javaFieldTarget(ParserRuleContext ctx) {
        return javaFieldTargets.get(ctx);
    }

    void setArrayCreationTarget(ParserRuleContext ctx, LoweringTargets.ArrayCreationTarget target) {
        arrayCreationTargets.put(ctx, target);
    }

    public LoweringTargets.ArrayCreationTarget arrayCreationTarget(ParserRuleContext ctx) {
        return arrayCreationTargets.get(ctx);
    }

    void setArrayOpTarget(ParserRuleContext ctx, LoweringTargets.ArrayOpTarget target) {
        arrayOpTargets.put(ctx, target);
    }

    public LoweringTargets.ArrayOpTarget arrayOpTarget(ParserRuleContext ctx) {
        return arrayOpTargets.get(ctx);
    }

    void setModuleExportTarget(ParserRuleContext ctx, LoweringTargets.ModuleExportTarget target) {
        moduleExportTargets.put(ctx, target);
    }

    public LoweringTargets.ModuleExportTarget moduleExportTarget(ParserRuleContext ctx) {
        return moduleExportTargets.get(ctx);
    }

    void setNonNullAssertionTarget(BabbleParser.NonNullAssertionContext ctx, String expectedType) {
        nonNullAssertionTargets.put(ctx, expectedType);
    }

    /** The non-null target type name for {@code e!}, or {@code null} if {@code e} is statically non-null. */
    public String nonNullAssertionTarget(BabbleParser.NonNullAssertionContext ctx) {
        return nonNullAssertionTargets.get(ctx);
    }

    void setThrowTarget(BabbleParser.ThrowExpressionContext ctx, LoweringTargets.ThrowTarget target) {
        throwTargets.put(ctx, target);
    }

    public LoweringTargets.ThrowTarget throwTarget(BabbleParser.ThrowExpressionContext ctx) {
        return throwTargets.get(ctx);
    }

    void setCatchTarget(BabbleParser.CatchClauseContext ctx, LoweringTargets.CatchTarget target) {
        catchTargets.put(ctx, target);
    }

    public LoweringTargets.CatchTarget catchTarget(BabbleParser.CatchClauseContext ctx) {
        return catchTargets.get(ctx);
    }
}
