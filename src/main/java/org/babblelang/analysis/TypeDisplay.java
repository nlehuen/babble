package org.babblelang.analysis;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Types as Babble source writes them (docs/design/bip012_grammar-improvements.md) : {@code list of int},
 * {@code map of (string, int)}, {@code fn io (n : int) : string}, {@code (list of int)?}. What
 * {@code :type} shows in the interactive mode (docs/design/bip004_repl.md §9). Diagnostics keep
 * {@link Type#toString()}, which their wording relies on.
 */
final class TypeDisplay {
    private TypeDisplay() {
    }

    static String display(Type type) {
        return switch (type) {
            case Type.Union union -> union(union);
            case Type.Intersection intersection -> intersection.members().stream()
                    .map(m -> m instanceof Type.Function || m instanceof Type.Union ? "(" + display(m) + ")" : display(m))
                    .collect(Collectors.joining(" & "));
            case Type.Frozen frozen -> "frozen " + (simpleOrGeneric(frozen.of()) ? display(frozen.of()) : "(" + display(frozen.of()) + ")");
            case Type.JavaArray array -> "JavaArray" + of(List.of(array.element()));
            case Type.Function function -> function(function);
            case Type.Instance instance -> instance.node().name + of(instance.typeArguments());
            case Type.ClassValue value -> (value.node().isInterface() ? "interface " : "class ") + value.node().name + of(value.typeArguments());
            case Type.CoreCollection collection -> collection.kind() + of(collection.typeArguments());
            case Type.JavaInstance instance -> javaName(instance) + of(instance.typeArguments());
            case Type.JavaClassValue value -> "class " + value.type().getName() + of(value.typeArguments());
            default -> type.toString();
        };
    }

    private static String union(Type.Union union) {
        List<Type> members = union.members();
        if (members.size() == 2 && members.get(1) == Type.NULL) {
            Type base = members.get(0);
            return (simple(base) ? display(base) : "(" + display(base) + ")") + "?";
        }
        return members.stream()
                .map(m -> m instanceof Type.Function || m instanceof Type.Union ? "(" + display(m) + ")" : display(m))
                .collect(Collectors.joining(" | "));
    }

    private static String function(Type.Function function) {
        StringBuilder text = new StringBuilder("fn ");
        String prefix = function.effects().formatDeclaredPrefix().strip();
        if (!prefix.isEmpty()) {
            text.append(prefix).append(' ');
        }
        {
            List<String> names = function.callingNames();
            if (!names.isEmpty()) {
                text.append("calling ").append(names.size() == 1 ? names.get(0) : "(" + String.join(", ", names) + ")").append(' ');
            }
        }
        if (!function.typeParameters().isEmpty()) {
            text.append("of ").append(function.typeParameters().size() == 1 ? function.typeParameters().get(0).name()
                    : function.typeParameters().stream().map(Type.TypeVar::name).collect(Collectors.joining(", ", "(", ")"))).append(' ');
        }
        text.append(function.parameters().stream().map(TypeDisplay::parameter).collect(Collectors.joining(", ", "(", ")")));
        if (function.result() != Type.ERROR) {
            text.append(" : ").append(function.effects().explicitUniqueResult() ? "unique " : "").append(display(function.result()));
        }
        return text.toString();
    }

    private static String parameter(Type.Parameter parameter) {
        return (parameter.unique() ? "unique " : "")
                + (parameter.mut() ? "mut " : "")
                + (!parameter.unique() && parameter.explicitShared() ? "shared " : "")
                + parameter.name()
                + (parameter.type() == Type.ERROR ? "" : " : " + display(parameter.type()))
                + (parameter.optional() ? " = …" : "");
    }

    /** {@code of int}, {@code of (string, int)}, or nothing without type arguments. */
    private static String of(List<Type> arguments) {
        if (arguments.isEmpty()) {
            return "";
        }
        if (arguments.size() == 1 && simple(arguments.get(0))) {
            return " of " + display(arguments.get(0));
        }
        return " of " + arguments.stream().map(TypeDisplay::display).collect(Collectors.joining(", ", "(", ")"));
    }

    /** A type that reads as one word, or one word and a {@code ?} : it needs no parentheses. */
    private static boolean simple(Type type) {
        return switch (type) {
            case Type.Simple s -> true;
            case Type.TypeVar v -> true;
            case Type.Instance i -> i.typeArguments().isEmpty();
            case Type.CoreCollection c -> c.typeArguments().isEmpty();
            case Type.JavaInstance j -> j.typeArguments().isEmpty();
            case Type.Union u -> u.members().size() == 2 && u.members().get(1) == Type.NULL && simple(u.members().get(0));
            default -> false;
        };
    }

    /** What {@code frozen} applies to without parentheses : a type name, with or without {@code of}. */
    private static boolean simpleOrGeneric(Type type) {
        return type instanceof Type.Instance || type instanceof Type.CoreCollection || type instanceof Type.JavaInstance
                || type instanceof Type.Simple || type instanceof Type.TypeVar;
    }

    private static String javaName(Type.JavaInstance instance) {
        String display = instance.toString();
        int arguments = display.indexOf('(');
        return arguments < 0 ? display : display.substring(0, arguments);
    }
}
