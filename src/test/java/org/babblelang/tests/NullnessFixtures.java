package org.babblelang.tests;

import org.babblelang.effects.NotNull;
import org.babblelang.effects.Nullable;

/** Java methods and fields of each nullness, for BIP011-D6 and D7. */
public class NullnessFixtures {
    /** Unannotated : nullable in Babble. */
    public static String plain(boolean nothing) {
        return nothing ? null : "plain";
    }

    /** Promises non-null, but lies : the boundary check catches it. */
    public static @NotNull String promised(boolean lie) {
        return lie ? null : "promised";
    }

    public static @Nullable Integer maybeCount(boolean nothing) {
        return nothing ? null : 3;
    }

    /** Non-null boxed : an int. */
    public static @NotNull Integer count() {
        return 7;
    }

    public static @NotNull String label = "label";

    public static String unlabelled = "unlabelled";

    /** Everything in it is non-null, unless annotated otherwise. */
    @org.jspecify.annotations.NullMarked
    public static class Marked {
        public static String name(boolean lie) {
            return lie ? null : "marked";
        }

        public static @org.jspecify.annotations.Nullable String optional() {
            return null;
        }
    }
}
