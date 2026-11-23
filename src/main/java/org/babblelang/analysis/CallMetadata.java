package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Call, parameter, overload, and tail-call metadata sub-view of {@link ScopeAnalysis} (BIP027-D3).
 */
public final class CallMetadata {
    /** One statically resolved alternative of an overload set call (BIP021-D11, BIP021-D16). */
    public record OverloadCallTarget(int altIndex, String signatureKey, boolean exactSet,
                                     BabbleParser.FunctionLiteralContext directTarget,
                                     LoweringTargets.FunctionSignature signature,
                                     LoweringTargets.ArgumentBinding binding) {
    }

    /** One branch in a union-split overload dispatch, ordered most-specific-first. */
    public record OverloadSplitBranch(List<LoweringTargets.OverloadSplitCheck> checks, OverloadCallTarget target,
                                      PrimitiveKind resultKind) {
    }

    /** Call-site lowering target for calls to an overload set (resolved directly or via union split). */
    public record OverloadDispatchTarget(OverloadCallTarget direct, List<OverloadSplitBranch> splitBranches,
                                         PrimitiveKind resultKind) {
        public boolean isSplit() {
            return splitBranches != null && !splitBranches.isEmpty();
        }
    }

    private final ParseTreeProperty<String[]> parameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<Integer> requiredParameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<Integer> keywordOnlyStarts = new ParseTreeProperty<>();
    private final ParseTreeProperty<Integer> restIndices = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.ArgumentBinding> argumentBindings = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.SpreadAdapt[]> callSpreadAdapts = new ParseTreeProperty<>();
    private final ParseTreeProperty<BabbleParser.FunctionLiteralContext> babbleCallFunctions = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.FunctionSignature> babbleCallSignatures = new ParseTreeProperty<>();
    private final Map<BabbleParser.OverloadExpressionContext, List<LoweringTargets.OverloadAltMetaInfo>> overloadMetas = new IdentityHashMap<>();
    private final Map<ParserRuleContext, LoweringTargets.OverloadSelection> overloadSelections = new IdentityHashMap<>();
    private final Map<ParserRuleContext, OverloadDispatchTarget> overloadDispatches = new IdentityHashMap<>();
    private final Set<BabbleParser.CallContext> selfTailCalls = Collections.newSetFromMap(new IdentityHashMap<>());

    CallMetadata() {
    }

    void setParameters(BabbleParser.FunctionLiteralContext function, String[] names, int required) {
        setParameters(function, names, required, names.length, -1);
    }

    void setParameters(BabbleParser.FunctionLiteralContext function, String[] names, int required, int keywordOnlyStart) {
        setParameters(function, names, required, keywordOnlyStart, -1);
    }

    void setParameters(BabbleParser.FunctionLiteralContext function, String[] names, int required, int keywordOnlyStart, int restIndex) {
        parameters.put(function, names);
        requiredParameters.put(function, required);
        keywordOnlyStarts.put(function, keywordOnlyStart);
        restIndices.put(function, restIndex);
    }

    public String[] parameters(BabbleParser.FunctionLiteralContext function) {
        return parameters.get(function);
    }

    /** The number of parameters without a default value. */
    public int requiredParameters(BabbleParser.FunctionLiteralContext function) {
        return requiredParameters.get(function);
    }

    /** The index of the first keyword-only parameter (after {@code ...} or rest), or {@code parameters.length}. */
    public int keywordOnlyStart(BabbleParser.FunctionLiteralContext function) {
        Integer start = keywordOnlyStarts.get(function);
        return start != null ? start : parameters(function).length;
    }

    /** The index of the variadic rest parameter, or {@code -1} if none. */
    public int restIndex(BabbleParser.FunctionLiteralContext function) {
        Integer idx = restIndices.get(function);
        return idx != null ? idx : -1;
    }

    void setArgumentBinding(BabbleParser.CallContext call, LoweringTargets.ArgumentBinding binding) {
        argumentBindings.put(call, binding);
    }

    /** Static argument binding for a Babble call ({@code BIP021-D4}), or {@code null}. */
    public LoweringTargets.ArgumentBinding argumentBinding(BabbleParser.CallContext call) {
        return argumentBindings.get(call);
    }

    void setCallSpreadAdapts(BabbleParser.CallContext call, LoweringTargets.SpreadAdapt[] adapts) {
        callSpreadAdapts.put(call, adapts);
    }

    /** Spread adaptations for a Java/non-Babble call ({@code BIP021-D7}), or {@code null}. */
    public LoweringTargets.SpreadAdapt[] callSpreadAdapts(BabbleParser.CallContext call) {
        return callSpreadAdapts.get(call);
    }

    void setBabbleCallFunction(ParserRuleContext ctx, BabbleParser.FunctionLiteralContext function) {
        babbleCallFunctions.put(ctx, function);
    }

    public BabbleParser.FunctionLiteralContext babbleCallFunction(ParserRuleContext ctx) {
        return babbleCallFunctions.get(ctx);
    }

    void setBabbleCallSignature(ParserRuleContext ctx, LoweringTargets.FunctionSignature signature) {
        babbleCallSignatures.put(ctx, signature);
    }

    public LoweringTargets.FunctionSignature babbleCallSignature(ParserRuleContext ctx) {
        return babbleCallSignatures.get(ctx);
    }

    void setOverloadMetas(BabbleParser.OverloadExpressionContext ctx, List<LoweringTargets.OverloadAltMetaInfo> metas) {
        overloadMetas.put(ctx, List.copyOf(metas));
    }

    public List<LoweringTargets.OverloadAltMetaInfo> overloadMetas(BabbleParser.OverloadExpressionContext ctx) {
        return overloadMetas.get(ctx);
    }

    void setOverloadSelection(ParserRuleContext ctx, LoweringTargets.OverloadSelection selection) {
        if (selection == null) {
            overloadSelections.remove(ctx);
        } else {
            overloadSelections.put(ctx, selection);
        }
    }

    public LoweringTargets.OverloadSelection overloadSelection(ParserRuleContext ctx) {
        return overloadSelections.get(ctx);
    }

    void setOverloadDispatch(ParserRuleContext ctx, OverloadDispatchTarget target) {
        overloadDispatches.put(ctx, target);
    }

    public OverloadDispatchTarget overloadDispatch(ParserRuleContext ctx) {
        return overloadDispatches.get(ctx);
    }

    void setSelfTailCall(BabbleParser.CallContext call) {
        selfTailCalls.add(call);
    }

    /**
     * Whether a call is a self tail call (docs/design/bip024_tail-calls.md) : both backends
     * run it as a jump back to the start of the calling function instead of a nested call.
     */
    public boolean isSelfTailCall(BabbleParser.CallContext call) {
        return selfTailCalls.contains(call);
    }
}
