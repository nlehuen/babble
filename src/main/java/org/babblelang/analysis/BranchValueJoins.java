package org.babblelang.analysis;

import java.util.List;
import java.util.Objects;

/**
 * Joins the values a branching expression yields ({@code if} / {@code else}, {@code match} arms,
 * {@code try} and {@code catch} results, loop {@code break} values) for ownership tracking.
 *
 * <p>{@link Reachability.Origin#join} keeps a {@code uniqueOwner} only when every branch has the
 * same one, so {@code if c then (a) else (list(2))} would otherwise look like a fresh, unowned
 * {@code unique} value although it may be the live local {@code a} itself : binding or consuming it
 * would then never demote {@code a} ({@code BIP032} round 8). Like {@code def alias = a}, a branch
 * value whose owner the join drops aliases that owner, which is demoted, and the joined value stops
 * being {@code unique}. Returned as is, the join is {@code unique} again : the owners it may alias
 * are the returning frame's own, which die with it, exactly as for {@code return a}.
 */
final class BranchValueJoins {

    private BranchValueJoins() {}

    /** Pairwise form of {@link #settle(EffectBodyWalker, List, Reachability.Origin)}. */
    static Reachability.Origin join(
            EffectBodyWalker w, Reachability.Origin a, Reachability.Origin b) {
        return settle(w, List.of(a, b), a.join(b));
    }

    /**
     * Demotes the owners of the {@code parts} that {@code joined} (their join, computed by the
     * caller) no longer records, and returns {@code joined} without uniqueness if any was. Call it
     * once the construct's branch environments are merged into {@code w.env}.
     */
    static Reachability.Origin settle(
            EffectBodyWalker w, List<Reachability.Origin> parts, Reachability.Origin joined) {
        boolean dropped = false;
        for (Reachability.Origin part : parts) {
            if (part != null
                    && part.owned()
                    && part.uniqueOwner != null
                    && !Objects.equals(part.uniqueOwner, joined.uniqueOwner)) {
                w.demoteUniqueOwner(part.uniqueOwner, Reachability.Origin.IMMUTABLE);
                dropped = true;
            }
        }
        if (!dropped || !joined.owned()) {
            return joined;
        }
        Reachability.Origin demoted = joined.demoteAlias(Reachability.Origin.IMMUTABLE);
        if (joined.unique) {
            w.uniqueBranchJoins.put(demoted, joined);
        }
        return demoted;
    }

    /**
     * The origin a function returning {@code value} returns : the {@code unique} join {@link
     * #settle} demoted when {@code value} is exactly that demoted join, else {@code value}.
     */
    static Reachability.Origin atReturn(EffectBodyWalker w, Reachability.Origin value) {
        Reachability.Origin unique = w.uniqueBranchJoins.get(value);
        return unique != null ? unique : value;
    }
}
