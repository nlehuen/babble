package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A static type, as the type checker computes it (see docs/design/bip008_static-typing.md).
 */
sealed interface Type {
    /** The built-in types, and the special ones. */
    record Simple(String name, Class<?> javaClass) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Internal sentinel for an expression or declaration whose type failed to resolve (never a language type). */
    Simple ERROR = new Simple("<error>", null);
    /** The sound non-null top type : assignable from every non-null type, usable only once narrowed or cast. */
    Simple ANY = new Simple("any", Object.class);
    /** The type of an expression that never yields a value : {@code return}. */
    Simple NOTHING = new Simple("nothing", null);
    Simple NULL = new Simple("null", null);
    Simple INT = new Simple("int", Integer.class);
    Simple DOUBLE = new Simple("double", Double.class);
    Simple BOOL = new Simple("bool", Boolean.class);
    Simple STRING = new Simple("string", String.class);

    /** A union of distinct non-union, non-nothing, non-error types (with {@code null}, if present, last). */
    record Union(List<Type> members) implements Type {
        @Override
        public String toString() {
            if (members.size() == 2 && members.get(1) == NULL) {
                Type base = members.get(0);
                return (base instanceof Function ? "(" + base + ")" : base.toString()) + "?";
            }
            return members.stream()
                    .map(m -> m instanceof Function ? "(" + m + ")" : m.toString())
                    .collect(Collectors.joining(" | "));
        }
    }

    /** The universal nullable top type {@code any?}. */
    Type NULLABLE_ANY = new Union(List.of(ANY, NULL));

    /** A Java array, with {@code length} and assignable to matching Java array parameters. */
    record JavaArray(Class<?> javaClass, Type element) implements Type {
        @Override
        public String toString() {
            return element + "[]";
        }
    }

    /** A Babble generic type parameter declared on a class, interface or function def. */
    record TypeVar(BabbleParser.DefExpressionContext owner, String name, int index) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A parameter of a function type : its name is part of the type, since calls bind by name. */
    record Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow, boolean explicitBorrow) {
        Parameter(String name, Type type, boolean optional) {
            this(name, type, optional, false, false, false);
        }

        Parameter(String name, Type type, boolean optional, boolean mut, boolean borrow) {
            this(name, type, optional, mut, borrow, borrow);
        }

        Parameter withType(Type newType) {
            return new Parameter(name, newType, optional, mut, borrow, explicitBorrow);
        }

        Parameter withModifiers(boolean newMut, boolean newBorrow) {
            return new Parameter(name, type, optional, newMut, newBorrow || explicitBorrow, explicitBorrow);
        }

        String format(boolean showBorrow) {
            return (mut ? "mut " : "")
                    + ((showBorrow ? borrow : explicitBorrow) ? "borrow " : "")
                    + name
                    + (type == ERROR ? "" : " : " + type)
                    + (optional ? " = …" : "");
        }

        @Override
        public String toString() {
            return format(false);
        }
    }

    record Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result, EffectSet effects) implements Type {
        public Function {
            effects = effects != null ? effects : EffectSet.EMPTY;
        }

        Function(List<Parameter> parameters, Type result) {
            this(List.of(), parameters, result, EffectSet.EMPTY);
        }

        Function(List<Parameter> parameters, Type result, EffectSet effects) {
            this(List.of(), parameters, result, effects);
        }

        Function(List<TypeVar> typeParameters, List<Parameter> parameters, Type result) {
            this(typeParameters, parameters, result, EffectSet.EMPTY);
        }

        Function withEffects(EffectSet newEffects, List<Parameter> newParameters) {
            return new Function(typeParameters, newParameters, result, newEffects);
        }

        /** Formats this function type highlighting guarantees relevant to {@code expected}. */
        String describeAgainst(Function expected) {
            java.util.EnumSet<org.babblelang.effects.Effect> shown = java.util.EnumSet.copyOf(effects.declared());
            for (org.babblelang.effects.Effect req : expected.effects().effects()) {
                if (effects.has(req)) {
                    shown.add(req);
                }
            }
            EffectSet displaySet = new EffectSet(
                    shown, shown,
                    effects.mutThis(),
                    effects.explicitBorrowThis() || (expected.effects().borrowThis() && effects.borrowThis()),
                    effects.explicitBorrowThis() || (expected.effects().borrowThis() && effects.borrowThis()),
                    false, false);
            String tp = typeParameters.isEmpty() ? ""
                    : typeParameters.stream().map(TypeVar::name).collect(Collectors.joining(", ", "(", ") "));
            StringBuilder params = new StringBuilder("(");
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    params.append(", ");
                }
                boolean showBorrow = i < expected.parameters().size() && expected.parameters().get(i).borrow();
                params.append(parameters.get(i).format(showBorrow));
            }
            params.append(")");
            return displaySet.formatDeclaredPrefix() + tp + params + (result == ERROR ? "" : " : " + result);
        }

        @Override
        public String toString() {
            String tp = typeParameters.isEmpty() ? ""
                    : typeParameters.stream().map(TypeVar::name).collect(Collectors.joining(", ", "(", ") "));
            return effects.formatDeclaredPrefix() + tp
                    + parameters.stream().map(Parameter::toString).collect(Collectors.joining(", ", "(", ")"))
                    + (result == ERROR ? "" : " : " + result);
        }
    }

    /** An instance of a class, object literal or interface : nominal. */
    record Instance(TypeNode node, List<Type> typeArguments) implements Type {
        Instance(TypeNode node) {
            this(node, List.of());
        }

        @Override
        public String toString() {
            return typeArguments.isEmpty() ? node.name
                    : node.name + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** The value of a class or interface expression : its constructor and statics. */
    record ClassValue(TypeNode node) implements Type {
        @Override
        public String toString() {
            return (node.isInterface() ? "interface " : "class ") + node.name;
        }
    }

    /** A package : structural, its members are its definitions. */
    record Package(String name, List<BabbleParser.PackageExpressionContext> packages) implements Type {
        @Override
        public String toString() {
            return "package " + name;
        }
    }

    /** A module imported statically : its exports are its members (docs/design/bip005_modules.md §8.2). */
    record Module(ModuleInterface module) implements Type {
        @Override
        public String toString() {
            return "module " + module.name();
        }
    }

    record JavaPackage(String name) implements Type {
        @Override
        public String toString() {
            return "package " + name;
        }
    }

    /** A Java class as a value : its constructors, static methods and member classes. */
    record JavaClassValue(Class<?> type) implements Type {
        @Override
        public String toString() {
            return "class " + type.getName();
        }
    }

    /** An instance of a Java class, as a constructor returns it. */
    record JavaInstance(Class<?> type, List<Type> typeArguments) implements Type {
        JavaInstance(Class<?> type) {
            this(type, List.of());
        }

        @Override
        public String toString() {
            return typeArguments.isEmpty() ? type.getName()
                    : type.getName() + typeArguments.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** The methods of a Java class with one name, selected through the class or an instance. */
    record JavaMethod(Class<?> owner, List<Type> typeArguments, String name) implements Type {
        JavaMethod(Class<?> owner, String name) {
            this(owner, List.of(), name);
        }

        @Override
        public String toString() {
            return owner.getName() + "." + name;
        }
    }

    /** {@code print}, {@code println}, {@code affiche}, {@code afficherc} : any number of {@code any?}, optional {@code to : Appendable}. */
    record Print() implements Type {
        @Override
        public String toString() {
            return "print";
        }
    }

    Print PRINT = new Print();

    static Type nullable(Type type) {
        return union(type, NULL);
    }

    static Type union(Type... types) {
        return union(Arrays.asList(types));
    }

    static Type union(Collection<? extends Type> types) {
        Set<Type> nonNull = new LinkedHashSet<>();
        boolean hasNull = false;
        boolean hasAny = false;
        for (Type t : types) {
            if (t == ERROR) {
                return ERROR;
            }
            if (t instanceof Union u) {
                for (Type member : u.members()) {
                    if (member == ERROR) {
                        return ERROR;
                    }
                    if (member == NULL) {
                        hasNull = true;
                    } else if (member == ANY) {
                        hasAny = true;
                    } else if (member != NOTHING) {
                        nonNull.add(member);
                    }
                }
            } else if (t == NULL) {
                hasNull = true;
            } else if (t == ANY) {
                hasAny = true;
            } else if (t != NOTHING) {
                nonNull.add(t);
            }
        }
        if (hasAny) {
            return hasNull ? NULLABLE_ANY : ANY;
        }
        if (nonNull.isEmpty()) {
            return hasNull ? NULL : NOTHING;
        }
        if (!hasNull && nonNull.size() == 1) {
            return nonNull.iterator().next();
        }
        List<Type> members = new ArrayList<>(nonNull.size() + (hasNull ? 1 : 0));
        members.addAll(nonNull);
        if (hasNull) {
            members.add(NULL);
        }
        return new Union(List.copyOf(members));
    }

    static boolean containsNull(Type type) {
        return type == NULL || (type instanceof Union union && union.members().contains(NULL));
    }

    static Type withoutNull(Type type) {
        if (type == NULL) {
            return NOTHING;
        }
        if (type instanceof Union union) {
            List<Type> filtered = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                if (member != NULL) {
                    filtered.add(member);
                }
            }
            return union(filtered);
        }
        return type;
    }

    static List<Type> membersOf(Type type) {
        return type instanceof Union union ? union.members() : List.of(type);
    }
}
