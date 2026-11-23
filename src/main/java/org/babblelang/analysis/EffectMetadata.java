package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Effect summary sub-view of {@link ScopeAnalysis} (BIP027-D3).
 */
public final class EffectMetadata {
    private final Map<ParserRuleContext, EffectSet> callableEffects = new HashMap<>();
    private final Map<ParserRuleContext, Type> callableTypes = new HashMap<>();
    private final Map<BabbleParser.CallContext, EffectSet> callEffects = new HashMap<>();

    EffectMetadata() {
    }

    void setCallableEffects(ParserRuleContext callable, EffectSet effects) {
        callableEffects.put(callable, effects);
    }

    /** The converged or declared effect summary of a function literal or class constructor. */
    public EffectSet callableEffects(ParserRuleContext callable) {
        return callableEffects.getOrDefault(callable, EffectSet.EMPTY);
    }

    void setCallableType(ParserRuleContext callable, Type type) {
        if (type != null) {
            callableTypes.put(callable, type);
        }
    }

    Type callableType(ParserRuleContext callable) {
        return callableTypes.get(callable);
    }

    void setCallEffects(BabbleParser.CallContext call, EffectSet effects) {
        callEffects.put(call, effects);
    }

    /** The resolved effect summary of a call site. */
    public EffectSet callEffects(BabbleParser.CallContext call) {
        return callEffects.getOrDefault(call, EffectSet.EMPTY);
    }
}
