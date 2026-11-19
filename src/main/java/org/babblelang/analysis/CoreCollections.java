package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The core collection factories (BIP011-D1, D9, D10) : {@code list(...)}, {@code set(...)},
 * {@code sortedset(...)}, {@code map(...)}, {@code sortedmap(...)} and {@code entry(k, v)},
 * typed as calls of their own. Each takes its elements (or entries), or one of the options
 * {@code capacity:}, {@code from:} and, for the sorted ones, {@code comparator:} ; its type
 * arguments come from explicit ones ({@code list(int)()}), the expected type, then the
 * arguments.
 */
final class CoreCollections {
    private final TypeChecker checker;

    CoreCollections(TypeChecker checker) {
        this.checker = checker;
    }

    /** The options each factory takes by name. */
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "list", Set.of("capacity", "from"),
            "set", Set.of("capacity", "from"),
            "sortedset", Set.of("comparator", "from"),
            "map", Set.of("capacity", "from"),
            "sortedmap", Set.of("comparator", "from"),
            "entry", Set.of("key", "value"));

    /** Whether an expression names a core collection, as the callee of its factory. */
    static String factoryName(BabbleParser.ExpressionContext callee) {
        if (callee instanceof BabbleParser.CoreTypeNameContext core && Type.collection(core.getText()) != null) {
            return core.getText();
        }
        return null;
    }

    /**
     * The type of a factory call : {@code explicit} the type its explicit type arguments make
     * ({@code list(int)()}), or null.
     */
    Type call(String name, Type.JavaInstance explicit, BabbleParser.CallContext ctx, Type expected) {
        Class<?> collection = Type.collection(name);
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        List<BabbleParser.ExpressionContext> positional = new ArrayList<>();
        BabbleParser.ExpressionContext capacity = null;
        BabbleParser.ExpressionContext from = null;
        BabbleParser.ExpressionContext comparator = null;
        BabbleParser.ExpressionContext key = null;
        BabbleParser.ExpressionContext value = null;
        for (BabbleParser.CallParameterContext argument : arguments) {
            if (argument.ID() == null) {
                positional.add(argument.expression());
                continue;
            }
            String option = argument.ID().getText();
            if (!OPTIONS.get(name).contains(option)) {
                checker.warn("No such parameter : " + option, argument);
                return Type.ERROR;
            }
            switch (option) {
                case "capacity" -> capacity = argument.expression();
                case "from" -> from = argument.expression();
                case "comparator" -> comparator = argument.expression();
                case "key" -> key = argument.expression();
                default -> value = argument.expression();
            }
        }
        if (name.equals("entry")) {
            return entry(explicit, positional, key, value, ctx, expected);
        }
        boolean keyed = collection.getTypeParameters().length == 2;
        int options = (capacity != null ? 1 : 0) + (from != null ? 1 : 0) + (comparator != null ? 1 : 0);
        if (!positional.isEmpty() && options > 0 || capacity != null && options > 1) {
            BabbleParser.ExpressionContext extra = capacity != null ? capacity : from != null ? from : comparator;
            checker.warn("Too many parameters : " + name + "(...) takes elements, or options", extra);
            return Type.ERROR;
        }
        List<Type> typeArguments = typeArguments(explicit, expected, collection);
        // Elements, or entries, are checked against known type arguments, else joined into them.
        Type elementPattern = keyed ? null : typeArguments != null ? typeArguments.get(0) : null;
        Type entryPattern = keyed && typeArguments != null ? new Type.JavaInstance(java.util.Map.Entry.class, typeArguments) : null;
        Type joined = null;
        List<Type> actuals = new ArrayList<>();
        for (BabbleParser.ExpressionContext element : positional) {
            Type pattern = keyed ? entryPattern : elementPattern;
            Type actual = pattern != null ? checker.check(element, pattern) : checker.synth(element);
            actuals.add(actual);
            if (pattern != null) {
                checker.subsumes(element, actual, pattern);
            } else if (!TypeChecker.isUnresolved(actual)) {
                joined = joined == null ? actual : checker.algebra().join(joined, actual);
            }
        }
        if (typeArguments == null && joined != null && !keyed) {
            // list(1, 2.5) : the int widens to the double the elements join to.
            for (int i = 0; i < positional.size(); i++) {
                checker.coerce(positional.get(i), actuals.get(i), joined);
            }
        }
        if (typeArguments == null && joined != null) {
            if (keyed) {
                Type projected = checker.algebra().projectToJava(joined, java.util.Map.Entry.class);
                if (projected instanceof Type.JavaInstance entryType && entryType.typeArguments().size() == 2) {
                    typeArguments = entryType.typeArguments();
                } else {
                    checker.warn("Type error : expected entry(any?, any?), got " + joined, positional.get(0));
                    return Type.ERROR;
                }
            } else {
                typeArguments = List.of(joined);
            }
        }
        if (from != null) {
            Class<?> source = keyed ? java.util.Map.class : java.lang.Iterable.class;
            Type fromType = checker.synth(from);
            if (TypeChecker.isUnresolved(fromType)) {
                return Type.ERROR;
            }
            Type projected = checker.algebra().projectToJava(Type.withoutNull(fromType), source);
            if (!(projected instanceof Type.JavaInstance sourceType) || Type.containsNull(fromType)
                    || sourceType.typeArguments().size() != source.getTypeParameters().length) {
                checker.warn("Type error : expected " + (keyed ? "map" : "java.lang.Iterable") + ", got " + fromType, from);
                return Type.ERROR;
            }
            if (typeArguments == null) {
                typeArguments = sourceType.typeArguments();
            } else {
                // Copied, not shared : what the source holds must be what the collection holds,
                // without conversion (a list(int) copies into a list(int?), not a list(double)).
                for (int i = 0; i < typeArguments.size(); i++) {
                    if (!checker.algebra().relate(sourceType.typeArguments().get(i), typeArguments.get(i),
                            TypeAlgebra.RelateMode.SUBTYPE, null)) {
                        checker.warn("Type error : expected " + new Type.JavaInstance(collection, typeArguments)
                                + ", got " + fromType, from);
                        return Type.ERROR;
                    }
                }
            }
        }
        if (capacity != null) {
            checker.subsumes(capacity, checker.check(capacity, Type.INT), Type.INT);
        }
        if (typeArguments == null && comparator != null) {
            // sortedset(comparator: (a: string, b: string): int -> ...) : what it compares.
            Type compared = comparedType(checker.synth(comparator));
            if (compared != null) {
                typeArguments = keyed ? null : List.of(compared);
            }
        }
        if (typeArguments == null) {
            checker.warn("Missing type : cannot infer type arguments for " + name, ctx);
            return Type.ERROR;
        }
        if (collection == java.util.SortedSet.class || collection == java.util.SortedMap.class) {
            Type ordered = typeArguments.get(0);
            if (comparator != null) {
                Type comparatorType = new Type.JavaInstance(java.util.Comparator.class, List.of(ordered));
                checker.subsumes(comparator, checker.check(comparator, comparatorType), comparatorType);
            } else if (!naturallyOrdered(ordered)) {
                checker.warn("Type error : " + ordered + " is not Comparable (pass comparator: ...)", ctx);
                return Type.ERROR;
            }
        }
        return new Type.JavaInstance(collection, List.copyOf(typeArguments));
    }

    /** What a comparator compares : its function's first parameter, or its {@code Comparator}'s type argument. */
    private Type comparedType(Type comparator) {
        if (comparator instanceof Type.Function fn && fn.parameters().size() == 2 && fn.typeParameters().isEmpty()) {
            return fn.parameters().get(0).type();
        }
        Type projected = checker.algebra().projectToJava(comparator, java.util.Comparator.class);
        if (projected instanceof Type.JavaInstance ji && ji.typeArguments().size() == 1) {
            return ji.typeArguments().get(0);
        }
        return null;
    }

    /** {@code entry(k, v)} or {@code entry(key: k, value: v)} : nullable keys and values allowed. */
    private Type entry(Type.JavaInstance explicit, List<BabbleParser.ExpressionContext> positional,
                       BabbleParser.ExpressionContext key, BabbleParser.ExpressionContext value,
                       BabbleParser.CallContext ctx, Type expected) {
        if (positional.size() == 2 && key == null && value == null) {
            key = positional.get(0);
            value = positional.get(1);
        } else if (!positional.isEmpty() || key == null || value == null) {
            checker.warn("Missing parameter : entry(key, value)", ctx);
            return Type.ERROR;
        }
        List<Type> typeArguments = typeArguments(explicit, expected, java.util.Map.Entry.class);
        if (typeArguments != null) {
            checker.subsumes(key, checker.check(key, typeArguments.get(0)), typeArguments.get(0));
            checker.subsumes(value, checker.check(value, typeArguments.get(1)), typeArguments.get(1));
            return new Type.JavaInstance(java.util.Map.Entry.class, typeArguments);
        }
        Type keyType = checker.synth(key);
        Type valueType = checker.synth(value);
        if (TypeChecker.isUnresolved(keyType) || TypeChecker.isUnresolved(valueType)) {
            return Type.ERROR;
        }
        return new Type.JavaInstance(java.util.Map.Entry.class, List.of(keyType, valueType));
    }

    /** The type arguments explicit ones or the expected type give, or null. */
    private List<Type> typeArguments(Type.JavaInstance explicit, Type expected, Class<?> collection) {
        if (explicit != null) {
            return explicit.typeArguments();
        }
        if (expected != null && expected != Type.ERROR) {
            for (Type member : Type.membersOf(Type.withoutNull(expected))) {
                if (member instanceof Type.JavaInstance ji && ji.type() == collection
                        && ji.typeArguments().size() == collection.getTypeParameters().length
                        && ji.typeArguments().stream().noneMatch(t -> t.hasTypeVar())) {
                    return ji.typeArguments();
                }
            }
        }
        return null;
    }

    /**
     * Whether a sorted collection can order values of that type without a comparator : non-null,
     * and Comparable (a scalar, a string, a Java class or a Babble class implementing it).
     */
    private boolean naturallyOrdered(Type type) {
        if (Type.containsNull(type)) {
            return false;
        }
        if (type instanceof Type.Simple simple && simple.javaClass() != null) {
            return Comparable.class.isAssignableFrom(simple.javaClass());
        }
        if (type instanceof Type.JavaInstance ji) {
            return Comparable.class.isAssignableFrom(ji.type());
        }
        if (type instanceof Type.Instance instance) {
            return TypeAlgebra.implementsJava(instance.node(), Comparable.class);
        }
        return false;
    }

    /** The type a factory name stands for in an {@code is} test : {@code x is list}. */
    static Type rawType(String name) {
        return JavaTypeBridge.rawJavaInstance(Type.collection(name));
    }
}
