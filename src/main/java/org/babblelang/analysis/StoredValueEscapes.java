package org.babblelang.analysis;

import java.util.HashSet;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Propagates an escape to the frame locals a stored value reaches.
 *
 * <p>A local absorbed into a fresh local container ({@code ls.add(a)}, {@code box.v = a}, an
 * iterator over it) loses its uniqueness but keeps no trace of where it went : when the container
 * later escapes into a parameter, {@code this} or external state ({@code sink.add(ls)}), the local
 * would still look like an unaliased frame local, and returning it would hand callers a fresh
 * {@code unique} value that the escaped container still reaches ({@code BIP032} round 10). The
 * container's alias paths name the locals it absorbed, so each of them inherits the target's
 * provenance, exactly as if it had been stored there directly.
 */
final class StoredValueEscapes {

    private StoredValueEscapes() {}

    /**
     * Taints every frame local {@code value} reaches with {@code target}'s provenance, when {@code
     * value} is stored into {@code target} (a field, or the receiver of a call that may keep its
     * argument) whose provenance is a parameter, {@code this} or external state.
     */
    static void taintReachableLocals(
            EffectBodyWalker w, Reachability.Origin value, Reachability.Origin target) {
        if (value == null
                || value == Reachability.Origin.IMMUTABLE
                || target == null
                || target == Reachability.Origin.IMMUTABLE
                || (target.params.isEmpty() && !target.aliasesThis && !target.external)) {
            return;
        }
        Set<Object> roots = new HashSet<>(value.directKeys);
        for (Reachability.PathKey pk : value.allReachablePaths()) {
            roots.add(pk.root());
        }
        for (Object root : roots) {
            if (root instanceof ParserRuleContext decl) {
                Reachability.Origin cur = w.env.get(decl);
                // Only confined frame locals would otherwise pass for fresh : parameters and
                // locals already carrying parameter, `this` or external provenance keep theirs.
                if (cur != null
                        && cur != Reachability.Origin.IMMUTABLE
                        && cur != Reachability.Origin.MOVED
                        && !w.paramIndex.containsKey(decl)
                        && cur.params.isEmpty()
                        && !cur.aliasesThis
                        && !cur.external) {
                    w.env.put(decl, cur.demoteAlias(target));
                }
            }
        }
    }
}
