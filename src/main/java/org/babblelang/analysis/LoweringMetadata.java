package org.babblelang.analysis;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;

/**
 * Lowering metadata sub-view of {@link ScopeAnalysis} (BIP027-D3): primitive kinds, definite
 * initialization, numeric widenings, runtime check descriptors, and direct
 * Java/Babble/array/for-loop lowering targets.
 */
public final class LoweringMetadata {
    private final ParseTreeProperty<LoweringTargets.ForLoopTarget> forLoopTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.DefTarget> defTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.IsTarget> isTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<List<LoweringTargets.IsTarget>> isIntersectionTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.IsTarget> asTargets = new ParseTreeProperty<>();
    private final Map<ParserRuleContext, LoweringTargets.JavaSamTarget> javaSamTargets =
            new IdentityHashMap<>();
    private final Map<BabbleParser.ForExpressionContext, LoweringTargets.JavaSamTarget>
            forElemSamTargets = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.JavaSamTarget> asSamTargets =
            new IdentityHashMap<>();
    private final ParseTreeProperty<LoweringTargets.InterfaceCallTarget> interfaceCallTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<String> checkedTypes = new ParseTreeProperty<>();
    private final ParseTreeProperty<String> isBuiltins = new ParseTreeProperty<>();
    private final Map<ParserRuleContext, Integer> numericWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Object> resolvedLiterals = new IdentityHashMap<>();
    private final ParseTreeProperty<PrimitiveKind> expressionKinds = new ParseTreeProperty<>();
    private final Set<ParserRuleContext> expressionDeeplyImmutable =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> expressionStrings =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ParseTreeProperty<PrimitiveKind> declarationKinds = new ParseTreeProperty<>();
    private final ParseTreeProperty<PrimitiveKind> functionReturnKinds = new ParseTreeProperty<>();
    private final Set<ParserRuleContext> definitelyDefined =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ParseTreeProperty<LoweringTargets.JavaCallTarget> javaCallTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.JavaConstructorTarget> javaConstructorTargets =
            new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.JavaFieldTarget> javaFieldTargets =
            new ParseTreeProperty<>();
    private final Map<ParserRuleContext, LoweringTargets.ArrayCreationTarget> arrayCreationTargets =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.ArrayOpTarget> arrayOpTargets =
            new IdentityHashMap<>();
    private final ParseTreeProperty<LoweringTargets.ModuleExportTarget> moduleExportTargets =
            new ParseTreeProperty<>();
    private final Map<BabbleParser.NonNullAssertionContext, String> nonNullAssertionTargets =
            new HashMap<>();
    private final Map<BabbleParser.ThrowExpressionContext, LoweringTargets.ThrowTarget>
            throwTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.CatchClauseContext, LoweringTargets.CatchTarget> catchTargets =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.ClassFieldTarget> classFieldTargets =
            new IdentityHashMap<>();
    private final Map<BabbleParser.CallContext, LoweringTargets.RecordNewTarget> recordNewTargets =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.DirectMethodTarget> directMethodTargets =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, BabbleParser.FunctionLiteralContext>
            directMethodFunctions = new IdentityHashMap<>();
    private final Map<BabbleParser.CallContext, LoweringTargets.ExportedFunctionTarget>
            externalFunctionTargets = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.ParamOwnership> paramOwnerships =
            new IdentityHashMap<>();
    private final Set<ParserRuleContext> expressionFrozen =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> expressionUnique =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.BooleanOpContext> logicRightNarrowed =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.CallContext, String> builtinOwnershipCalls =
            new IdentityHashMap<>();
    private final Set<BabbleParser.SelectorContext> publicStaticDefSelects =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.RangeExpressionContext, LoweringTargets.RangeTarget>
            rangeTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.ForExpressionContext, CountedRangeSource> countedRangeSources =
            new IdentityHashMap<>();
    private final Map<BabbleParser.MatchExpressionContext, Boolean> matchExhaustive =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.PatternConst> patternConsts =
            new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.PatternTypeTarget> patternTypeTargets =
            new IdentityHashMap<>();
    private final Map<BabbleParser.ValuePatternContext, RangePatternSource> rangePatternSources =
            new IdentityHashMap<>();
    private final Map<BabbleParser.MembershipPatternContext, LoweringTargets.MembershipTarget>
            membershipTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.RelationalPatternContext, PrimitiveKind>
            relationalScrutineeKinds = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.RecordDestructureTarget>
            recordDestructureTargets = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.SequenceDestructureTarget>
            sequenceDestructureTargets = new IdentityHashMap<>();

    /**
     * AST-level range-membership pattern decomposition for {@code case lo..hi} / {@code
     * (lo..<hi).step(k)} ({@code BIP038}).
     */
    public record RangePatternSource(
            BabbleParser.ExpressionContext lo,
            BabbleParser.ExpressionContext hi,
            BabbleParser.ExpressionContext step,
            boolean inclusive,
            PrimitiveKind elemKind,
            boolean stringElement) {}

    /**
     * AST-level counted-range decomposition computed once by {@link LoweringAnalyzer} and consumed
     * by {@code HirBuilder}.
     */
    public record CountedRangeSource(
            BabbleParser.ExpressionContext start,
            BabbleParser.ExpressionContext end,
            BabbleParser.ExpressionContext step,
            boolean inclusive,
            boolean descending,
            PrimitiveKind elemKind) {}

    LoweringMetadata() {}

    void setRangeTarget(
            BabbleParser.RangeExpressionContext ctx, LoweringTargets.RangeTarget target) {
        rangeTargets.put(ctx, target);
    }

    public LoweringTargets.RangeTarget rangeTarget(BabbleParser.RangeExpressionContext ctx) {
        return rangeTargets.get(ctx);
    }

    void setCountedRangeSource(BabbleParser.ForExpressionContext ctx, CountedRangeSource source) {
        countedRangeSources.put(ctx, source);
    }

    public CountedRangeSource countedRangeSource(BabbleParser.ForExpressionContext ctx) {
        return countedRangeSources.get(ctx);
    }

    void setMatchExhaustive(BabbleParser.MatchExpressionContext ctx, boolean exhaustive) {
        matchExhaustive.put(ctx, exhaustive);
    }

    public boolean isMatchExhaustive(BabbleParser.MatchExpressionContext ctx) {
        return Boolean.TRUE.equals(matchExhaustive.get(ctx));
    }

    void setPatternConst(ParserRuleContext ctx, LoweringTargets.PatternConst constant) {
        if (constant != null) {
            patternConsts.put(ctx, constant);
        }
    }

    public LoweringTargets.PatternConst patternConst(ParserRuleContext ctx) {
        return patternConsts.get(ctx);
    }

    void setPatternTypeTarget(ParserRuleContext ctx, LoweringTargets.PatternTypeTarget target) {
        if (target != null) {
            patternTypeTargets.put(ctx, target);
        }
    }

    public LoweringTargets.PatternTypeTarget patternTypeTarget(ParserRuleContext ctx) {
        return patternTypeTargets.get(ctx);
    }

    void setRangePatternSource(BabbleParser.ValuePatternContext ctx, RangePatternSource source) {
        if (source != null) {
            rangePatternSources.put(ctx, source);
        }
    }

    public RangePatternSource rangePatternSource(BabbleParser.ValuePatternContext ctx) {
        return rangePatternSources.get(ctx);
    }

    void setMembershipTarget(
            BabbleParser.MembershipPatternContext ctx, LoweringTargets.MembershipTarget target) {
        if (target != null) {
            membershipTargets.put(ctx, target);
        }
    }

    public LoweringTargets.MembershipTarget membershipTarget(
            BabbleParser.MembershipPatternContext ctx) {
        return membershipTargets.get(ctx);
    }

    void setRelationalScrutineeKind(BabbleParser.RelationalPatternContext ctx, PrimitiveKind kind) {
        if (kind != null && kind != PrimitiveKind.NONE) {
            relationalScrutineeKinds.put(ctx, kind);
        }
    }

    public PrimitiveKind relationalScrutineeKind(BabbleParser.RelationalPatternContext ctx) {
        PrimitiveKind kind = relationalScrutineeKinds.get(ctx);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    void setRecordDestructureTarget(
            ParserRuleContext ctx, LoweringTargets.RecordDestructureTarget target) {
        if (target != null) {
            recordDestructureTargets.put(ctx, target);
        }
    }

    public LoweringTargets.RecordDestructureTarget recordDestructureTarget(ParserRuleContext ctx) {
        return recordDestructureTargets.get(ctx);
    }

    void setSequenceDestructureTarget(
            ParserRuleContext ctx, LoweringTargets.SequenceDestructureTarget target) {
        if (target != null) {
            sequenceDestructureTargets.put(ctx, target);
        }
    }

    public LoweringTargets.SequenceDestructureTarget sequenceDestructureTarget(
            ParserRuleContext ctx) {
        return sequenceDestructureTargets.get(ctx);
    }

    void setDefTarget(BabbleParser.DefExpressionContext def, LoweringTargets.DefTarget target) {
        defTargets.put(def, target);
    }

    public LoweringTargets.DefTarget defTarget(BabbleParser.DefExpressionContext def) {
        LoweringTargets.DefTarget target = defTargets.get(def);
        return target != null
                ? target
                : new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1);
    }

    void setIsTarget(BabbleParser.IsExpressionContext ctx, LoweringTargets.IsTarget target) {
        isTargets.put(ctx, target);
    }

    public LoweringTargets.IsTarget isTarget(BabbleParser.IsExpressionContext ctx) {
        return isTargets.get(ctx);
    }

    void setIsIntersectionTargets(
            BabbleParser.IsExpressionContext ctx, List<LoweringTargets.IsTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            isIntersectionTargets.removeFrom(ctx);
        } else {
            isIntersectionTargets.put(ctx, List.copyOf(targets));
        }
    }

    public List<LoweringTargets.IsTarget> isIntersectionTargets(
            BabbleParser.IsExpressionContext ctx) {
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

    void setForElemSamTarget(
            BabbleParser.ForExpressionContext ctx, LoweringTargets.JavaSamTarget target) {
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

    void setInterfaceCallTarget(
            BabbleParser.CallContext ctx, LoweringTargets.InterfaceCallTarget target) {
        interfaceCallTargets.put(ctx, target);
    }

    public LoweringTargets.InterfaceCallTarget interfaceCallTarget(BabbleParser.CallContext ctx) {
        return interfaceCallTargets.get(ctx);
    }

    void setCheckedType(ParserRuleContext ctx, String descriptor) {
        checkedTypes.put(ctx, descriptor);
    }

    /**
     * The target type to check at runtime (a {@code Types} descriptor) for an {@code as} cast, or
     * null when no cast check applies.
     */
    public String checkedType(ParserRuleContext ctx) {
        return checkedTypes.get(ctx);
    }

    void setForLoopTarget(
            BabbleParser.ForExpressionContext ctx, LoweringTargets.ForLoopTarget target) {
        forLoopTargets.put(ctx, target);
    }

    public LoweringTargets.ForLoopTarget forLoopTarget(BabbleParser.ForExpressionContext ctx) {
        return forLoopTargets.get(ctx);
    }

    void setIsBuiltin(BabbleParser.IsExpressionContext ctx, String type) {
        isBuiltins.put(ctx, type);
    }

    /**
     * The built-in type name a {@code value is T} test checks, or null for a normal type
     * expression.
     */
    public String isBuiltin(BabbleParser.IsExpressionContext ctx) {
        return isBuiltins.get(ctx);
    }

    void widenNumeric(ParserRuleContext ctx, int targetRank) {
        if (targetRank >= 1 && targetRank <= 5) {
            numericWidenings.merge(ctx, targetRank, Math::max);
        }
    }

    /**
     * Target numeric rank (1=short, 2=int, 3=long, 4=float, 5=double) if this construct widens
     * numerically, else -1.
     */
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

    /**
     * The primitive kind of an expression, or {@link PrimitiveKind#NONE} for references/boxed
     * values.
     */
    public PrimitiveKind expressionKind(ParserRuleContext ctx) {
        PrimitiveKind kind = expressionKinds.get(ctx);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    void markExpressionDeeplyImmutable(ParserRuleContext ctx) {
        expressionDeeplyImmutable.add(ctx);
    }

    /**
     * Whether the converged static type of {@code ctx} is deeply immutable (scalars, null, frozen,
     * deep record/entry).
     */
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

    /**
     * Whether a {@code def} is provably initialized before any read or closure capture can observe
     * it.
     */
    public boolean isDefinitelyDefined(ParserRuleContext decl) {
        return definitelyDefined.contains(decl);
    }

    void setJavaCallTarget(ParserRuleContext ctx, LoweringTargets.JavaCallTarget target) {
        javaCallTargets.put(ctx, target);
    }

    public LoweringTargets.JavaCallTarget javaCallTarget(ParserRuleContext ctx) {
        return javaCallTargets.get(ctx);
    }

    void setJavaConstructorTarget(
            ParserRuleContext ctx, LoweringTargets.JavaConstructorTarget target) {
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

    /**
     * The non-null target type name for {@code e!}, or {@code null} if {@code e} is statically
     * non-null.
     */
    public String nonNullAssertionTarget(BabbleParser.NonNullAssertionContext ctx) {
        return nonNullAssertionTargets.get(ctx);
    }

    void setThrowTarget(
            BabbleParser.ThrowExpressionContext ctx, LoweringTargets.ThrowTarget target) {
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

    void setClassFieldTarget(ParserRuleContext ctx, LoweringTargets.ClassFieldTarget target) {
        classFieldTargets.put(ctx, target);
    }

    public LoweringTargets.ClassFieldTarget classFieldTarget(ParserRuleContext ctx) {
        return classFieldTargets.get(ctx);
    }

    void setRecordNewTarget(BabbleParser.CallContext ctx, LoweringTargets.RecordNewTarget target) {
        recordNewTargets.put(ctx, target);
    }

    public LoweringTargets.RecordNewTarget recordNewTarget(BabbleParser.CallContext ctx) {
        return recordNewTargets.get(ctx);
    }

    void setDirectMethodTarget(ParserRuleContext ctx, LoweringTargets.DirectMethodTarget target) {
        directMethodTargets.put(ctx, target);
    }

    public LoweringTargets.DirectMethodTarget directMethodTarget(ParserRuleContext ctx) {
        return directMethodTargets.get(ctx);
    }

    void setDirectMethodFunction(ParserRuleContext ctx, BabbleParser.FunctionLiteralContext fn) {
        directMethodFunctions.put(ctx, fn);
    }

    public BabbleParser.FunctionLiteralContext directMethodFunction(ParserRuleContext ctx) {
        return directMethodFunctions.get(ctx);
    }

    void setExternalFunctionTarget(
            BabbleParser.CallContext ctx, LoweringTargets.ExportedFunctionTarget target) {
        externalFunctionTargets.put(ctx, target);
    }

    public LoweringTargets.ExportedFunctionTarget externalFunctionTarget(
            BabbleParser.CallContext ctx) {
        return externalFunctionTargets.get(ctx);
    }

    void setParamOwnership(ParserRuleContext decl, LoweringTargets.ParamOwnership ownership) {
        paramOwnerships.put(decl, ownership);
    }

    public LoweringTargets.ParamOwnership paramOwnership(ParserRuleContext decl) {
        return paramOwnerships.getOrDefault(decl, LoweringTargets.ParamOwnership.DEFAULT);
    }

    void markExpressionFrozen(ParserRuleContext ctx) {
        expressionFrozen.add(ctx);
    }

    public boolean isExpressionFrozen(ParserRuleContext ctx) {
        return expressionFrozen.contains(ctx);
    }

    void markExpressionUnique(ParserRuleContext ctx) {
        expressionUnique.add(ctx);
    }

    public boolean isExpressionUnique(ParserRuleContext ctx) {
        return expressionUnique.contains(ctx);
    }

    void markLogicRightNarrowed(BabbleParser.BooleanOpContext ctx) {
        logicRightNarrowed.add(ctx);
    }

    public boolean isLogicRightNarrowed(BabbleParser.BooleanOpContext ctx) {
        return logicRightNarrowed.contains(ctx);
    }

    void setBuiltinOwnershipCall(BabbleParser.CallContext ctx, String kind) {
        builtinOwnershipCalls.put(ctx, kind);
    }

    private final Map<String, LoweringTargets.WasmImportSpec> wasmImports =
            new java.util.LinkedHashMap<>();

    void addWasmImport(LoweringTargets.WasmImportSpec spec) {
        wasmImports.put(spec.module() + "." + spec.name(), spec);
    }

    public LoweringTargets.WasmImportSpec wasmImport(String module, String name) {
        return wasmImports.get(module + "." + name);
    }

    public List<LoweringTargets.WasmImportSpec> wasmImports() {
        return List.copyOf(wasmImports.values());
    }

    /**
     * {@code "move"} or {@code "freeze"} when {@code ctx} calls the unshadowed built-in, else null.
     */
    public String builtinOwnershipCall(BabbleParser.CallContext ctx) {
        return builtinOwnershipCalls.get(ctx);
    }

    void markPublicStaticDefSelect(BabbleParser.SelectorContext ctx) {
        publicStaticDefSelects.add(ctx);
    }

    public boolean isPublicStaticDefSelect(BabbleParser.SelectorContext ctx) {
        return publicStaticDefSelects.contains(ctx);
    }
}
