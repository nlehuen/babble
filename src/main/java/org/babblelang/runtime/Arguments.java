package org.babblelang.runtime;


import java.util.ArrayList;
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
        return new Binder(parameters, parameters.length, args, names);
    }

    public static Binder binder(String[] parameters, int keywordOnlyStart, Object[] args, String[] names) {
        return new Binder(parameters, keywordOnlyStart, args, names);
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
        for (int i = 0; i < 32; i++) {
            if ((targetOptionalParamBits & (1 << i)) != 0) {
                if (i >= incomingCount || (missingParamBits & (1 << i)) != 0) {
                    mask |= (1 << optBit);
                }
                optBit++;
            }
        }
        return mask;
    }

    public static final class Binder {
        private final String[] parameters;
        private final int keywordOnlyStart;
        private final Object[] args;
        private final String[] names;
        private final int[] matched;
        private final List<String> unknownNames;
        private final String outOfPosition;
        private final String duplicate;
        private final int extraPositional;
        private final int positionalCount;
        private final int distinctNameCount;

        private Binder(String[] parameters, int keywordOnlyStart, Object[] args, String[] names) {
            this.parameters = parameters;
            this.keywordOnlyStart = Math.min(keywordOnlyStart, parameters.length);
            this.args = args;
            this.names = names;
            if (names == null) {
                this.matched = null;
                this.unknownNames = null;
                this.outOfPosition = null;
                this.duplicate = null;
                this.extraPositional = 0;
                this.positionalCount = args.length;
                this.distinctNameCount = 0;
                return;
            }
            int[] map = new int[parameters.length];
            java.util.Arrays.fill(map, -1);
            List<String> unknown = null;
            Set<String> distinct = new LinkedHashSet<>();
            String firstOutOfPos = null;
            String outOfPosError = null;
            String dupError = null;
            int extra = 0;
            int posCount = 0;
            for (int j = 0; j < args.length; j++) {
                String name = names[j];
                if (name == null) {
                    posCount++;
                    if (firstOutOfPos != null) {
                        if (outOfPosError == null) {
                            outOfPosError = firstOutOfPos;
                        }
                    } else if (j < this.keywordOnlyStart) {
                        map[j] = j;
                    } else {
                        extra++;
                    }
                } else {
                    boolean added = distinct.add(name);
                    int pIdx = indexOf(parameters, name);
                    if (pIdx < 0) {
                        if (added) {
                            if (unknown == null) {
                                unknown = new ArrayList<>();
                            }
                            unknown.add(name);
                        }
                    } else {
                        if (map[pIdx] >= 0) {
                            if (dupError == null) {
                                dupError = name;
                            }
                        } else {
                            map[pIdx] = j;
                        }
                        if ((pIdx != j || pIdx >= this.keywordOnlyStart) && firstOutOfPos == null) {
                            firstOutOfPos = name;
                        }
                    }
                }
            }
            this.matched = map;
            this.unknownNames = unknown;
            this.outOfPosition = outOfPosError;
            this.duplicate = dupError;
            this.extraPositional = extra;
            this.positionalCount = posCount;
            this.distinctNameCount = distinct.size();
            if (unknown != null) {
                throw new BabbleException("No such parameter : " + String.join(", ", unknown));
            }
            if (outOfPosError != null) {
                throw new BabbleException("Named argument out of position : " + outOfPosError);
            }
            if (dupError != null) {
                throw new BabbleException("Parameter given twice : " + dupError);
            }
        }

        private static int indexOf(String[] parameters, String name) {
            for (int i = 0; i < parameters.length; i++) {
                if (name.equals(parameters[i])) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * The argument for parameter {@code index} : by name, else by position, else
         * {@link Arguments#MISSING}.
         */
        public Object take(int index) {
            if (names == null) {
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
                if (args.length > parameters.length) {
                    throw tooMany(parameters.length, args.length);
                }
                return;
            }
            if (unknownNames != null && !unknownNames.isEmpty()) {
                throw new BabbleException("No such parameter : " + String.join(", ", unknownNames));
            }
            if (outOfPosition != null) {
                throw new BabbleException("Named argument out of position : " + outOfPosition);
            }
            if (duplicate != null) {
                throw new BabbleException("Parameter given twice : " + duplicate);
            }
            if (extraPositional > 0) {
                int declared = keywordOnlyStart < parameters.length ? keywordOnlyStart : parameters.length;
                int given = keywordOnlyStart < parameters.length ? positionalCount : positionalCount + distinctNameCount;
                throw tooMany(declared, given);
            }
        }

        private static BabbleException tooMany(int declared, int given) {
            return new BabbleException("Too many parameters : " + declared + " declared, " + given + " given");
        }
    }
}
