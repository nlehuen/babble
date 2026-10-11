package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single implementation of the argument binding rules, used by the interpreter and
 * by compiled function prologues.
 * <p>
 * Each argument is keyed by its name if it has one, and by its position otherwise. A
 * named argument still uses up a position : in {@code f(1, a: 2)} the 1 is position 0
 * and nothing is position 1. Each parameter takes, in order, the argument with its name,
 * else the positional argument at its index, else its default value. Arguments that no
 * parameter claimed are rejected.
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
        return new Binder(parameters, args, names);
    }

    public static final class Binder {
        private final String[] parameters;
        private final Object[] args;
        private final String[] names;
        private final boolean[] consumed;

        private Binder(String[] parameters, Object[] args, String[] names) {
            this.parameters = parameters;
            this.args = args;
            this.names = names;
            this.consumed = names == null ? null : new boolean[args.length];
        }

        /**
         * The argument for parameter {@code index} : by name, else by position, else
         * {@link Arguments#MISSING}.
         */
        public Object take(int index) {
            if (names == null) {
                return index < args.length ? args[index] : MISSING;
            }
            String name = parameters[index];
            Object value = MISSING;
            // When a name is given twice, the last value wins and both are used up.
            for (int i = 0; i < args.length; i++) {
                if (name.equals(names[i])) {
                    value = args[i];
                    consumed[i] = true;
                }
            }
            if (value == MISSING && index < args.length && names[index] == null) {
                value = args[index];
                consumed[index] = true;
            }
            return value;
        }

        public BabbleException missing(int index) {
            return new BabbleException("Missing parameter : " + parameters[index]);
        }

        /**
         * Arguments the caller supplied that no declared parameter claimed. A leftover
         * name is almost always a typo, and a leftover position means the call passed
         * more arguments than the function takes ; either way, silently dropping them
         * hides the mistake.
         */
        public void rejectLeftovers() {
            if (names == null) {
                if (args.length > parameters.length) {
                    throw tooMany(args.length);
                }
                return;
            }
            Set<String> distinctNames = new LinkedHashSet<>();
            List<String> unknownNames = new ArrayList<>();
            int positional = 0;
            int extraPositional = 0;
            for (int i = 0; i < args.length; i++) {
                String name = names[i];
                if (name == null) {
                    positional++;
                    if (!consumed[i]) {
                        extraPositional++;
                    }
                } else if (distinctNames.add(name) && !consumed[i]) {
                    unknownNames.add(name);
                }
            }
            if (!unknownNames.isEmpty()) {
                throw new BabbleException("No such parameter : " + String.join(", ", unknownNames));
            }
            if (extraPositional > 0) {
                throw tooMany(positional + distinctNames.size());
            }
        }

        private BabbleException tooMany(int given) {
            return new BabbleException("Too many parameters : " + parameters.length + " declared, " + given + " given");
        }
    }
}
