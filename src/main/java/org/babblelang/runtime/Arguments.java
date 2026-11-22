package org.babblelang.runtime;


import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single implementation of the argument binding rules, used by the interpreter and
 * by compiled function prologues.
 * <p>
 * Under {@code BIP021-D23}, a positional argument may follow named ones only while each
 * named argument before it is in its own positional slot. Passing a parameter both by
 * position and by name, or twice by name, is rejected as {@code Parameter given twice}.
 * Parameters at or after {@code keywordOnlyStart} are only bound by name when names are
 * present; calls already lowered positionally by the front end ({@code names == null},
 * {@code BIP021-D4}) pass every slot in parameter order with {@link #MISSING} for omitted
 * optional parameters.
 */
public final class Arguments {
    /** What {@link Binder#take} returns when no argument matches the parameter. */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "MISSING";
        }
    };

    /** Shared argument array for calls without arguments. */
    public static final Object[] NONE = new Object[0];

    private Arguments() {
    }

    public static Binder binder(String[] parameters, Object[] args, String[] names) {
        return new Binder(parameters, parameters.length, -1, args, names);
    }

    public static Binder binder(String[] parameters, int keywordOnlyStart, Object[] args, String[] names) {
        return new Binder(parameters, keywordOnlyStart, -1, args, names);
    }

    public static Binder binder(String[] parameters, int keywordOnlyStart, int restIndex, Object[] args, String[] names) {
        return new Binder(parameters, keywordOnlyStart, restIndex, args, names);
    }

    /** The error of a parameter without a default that no argument matched. */
    public static BabbleException missing(String parameter) {
        return new BabbleException("Missing parameter : " + parameter);
    }

    /**
     * Maps caller parameter-index missing bits ({@code bit i == 1} when parameter {@code i} was
     * omitted) into a callee {@code $spec}'s ordinal optional-parameter mask ({@code BIP021-D3}).
     */
    public static int remapMask(int missingParamBits, int targetOptionalParamBits, int incomingCount) {
        int mask = 0;
        int optBit = 0;
        for (int bits = targetOptionalParamBits; bits != 0; bits &= bits - 1) {
            int i = Integer.numberOfTrailingZeros(bits);
            if (i >= incomingCount || (missingParamBits & (1 << i)) != 0) {
                mask |= (1 << optBit);
            }
            optBit++;
        }
        return mask;
    }

    public static final class Binder {
        private final String[] parameters;
        private final int restIndex;
        private final int packedRestAt;
        private final Object[] args;
        private final String[] names;
        private final int[] matched;
        private final Iterable<?> restValue;
        private final BabbleException tooManyError;

        private Binder(String[] parameters, int keywordOnlyStart, int restIndex, Object[] args, String[] names) {
            this.parameters = parameters;
            this.restIndex = restIndex;
            int kwStart = Math.min(keywordOnlyStart, parameters.length);
            this.args = args;
            this.names = names;
            if (names == null) {
                this.matched = null;
                this.tooManyError = null;
                if (restIndex >= 0) {
                    int packed = -1;
                    for (int i = restIndex; i < args.length; i++) {
                        if (args[i] instanceof ArgumentsView.PackedRest) {
                            packed = i;
                            break;
                        }
                    }
                    this.packedRestAt = packed;
                    this.restValue = packed >= 0
                            ? Spreads.collectRest(args, restIndex, packed + 1)
                            : Spreads.collectRest(args, restIndex);
                } else {
                    this.packedRestAt = -1;
                    this.restValue = null;
                }
                return;
            }
            this.packedRestAt = -1;
            int[] map = new int[parameters.length];
            java.util.Arrays.fill(map, -1);
            List<String> paramList = java.util.Arrays.asList(parameters);
            List<Object> restItems = restIndex >= 0 ? new java.util.ArrayList<>() : null;
            String firstOutOfPos = null;
            int extra = 0;
            int posCount = 0;
            for (int j = 0; j < args.length; j++) {
                String name = names[j];
                if (name == null) {
                    posCount++;
                    if (firstOutOfPos != null) {
                        throw new BabbleException("Named argument out of position : " + firstOutOfPos);
                    }
                    if (restIndex >= 0 && j >= restIndex) {
                        restItems.add(args[j]);
                    } else if (j < kwStart) {
                        map[j] = j;
                    } else {
                        extra++;
                    }
                    continue;
                }
                int pIdx = paramList.indexOf(name);
                if (pIdx < 0 || pIdx == restIndex) {
                    throw new BabbleException("No such parameter : " + name);
                }
                if (map[pIdx] >= 0) {
                    throw new BabbleException("Parameter given twice : " + name);
                }
                map[pIdx] = j;
                if (firstOutOfPos == null && (pIdx != j || pIdx >= kwStart)) {
                    firstOutOfPos = name;
                }
            }
            this.matched = map;
            this.restValue = restItems != null ? Spreads.collectRest(restItems.toArray(), 0) : null;
            this.tooManyError = extra > 0 ? tooMany(Math.min(kwStart, parameters.length), posCount) : null;
        }

        /**
         * The argument for parameter {@code index} : by name, else by position, else
         * {@link Arguments#MISSING}.
         */
        public Object take(int index) {
            if (index == restIndex) {
                return restValue;
            }
            if (names == null) {
                if (restIndex >= 0 && index > restIndex) {
                    if (packedRestAt < 0) {
                        return MISSING;
                    }
                    int shifted = packedRestAt + (index - restIndex);
                    return shifted < args.length ? args[shifted] : MISSING;
                }
                return index < args.length ? args[index] : MISSING;
            }
            int found = matched[index];
            return found >= 0 ? args[found] : MISSING;
        }

        public BabbleException missing(int index) {
            return Arguments.missing(parameters[index]);
        }

        /**
         * Arguments the caller supplied that no declared parameter claimed.
         */
        public void rejectLeftovers() {
            if (names == null) {
                if (restIndex < 0 && args.length > parameters.length) {
                    throw tooMany(parameters.length, args.length);
                }
                return;
            }
            if (tooManyError != null) {
                throw tooManyError;
            }
        }

        private static BabbleException tooMany(int declared, int given) {
            return new BabbleException("Too many parameters : " + declared + " declared, " + given + " given");
        }
    }
}
