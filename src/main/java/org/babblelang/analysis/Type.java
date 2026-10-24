package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A static type, as the type checker computes it (see docs/design/type-system.md).
 * <p>
 * Typing is gradual : {@link #DYNAMIC}, the type of everything the checker can't know, is
 * compatible with every type in both directions.
 */
sealed interface Type {
    /** The built-in types, and the special ones. */
    record Simple(String name, Class<?> javaClass) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    Simple DYNAMIC = new Simple("dynamic", null);
    /** The type of an expression that never yields a value : {@code return}. */
    Simple NOTHING = new Simple("nothing", null);
    Simple NULL = new Simple("null", null);
    Simple INT = new Simple("int", Integer.class);
    Simple DOUBLE = new Simple("double", Double.class);
    Simple BOOL = new Simple("bool", Boolean.class);
    Simple STRING = new Simple("string", String.class);

    /** A parameter of a function type : its name is part of the type, since calls bind by name. */
    record Parameter(String name, Type type, boolean optional) {
        @Override
        public String toString() {
            return name + (type == DYNAMIC ? "" : " : " + type) + (optional ? " = …" : "");
        }
    }

    record Function(List<Parameter> parameters, Type result) implements Type {
        @Override
        public String toString() {
            return parameters.stream().map(Parameter::toString).collect(Collectors.joining(", ", "(", ")"))
                    + (result == DYNAMIC ? "" : " : " + result);
        }
    }

    /** An instance of a class, object literal or interface : nominal. */
    record Instance(TypeNode node) implements Type {
        @Override
        public String toString() {
            return node.name;
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
    record JavaInstance(Class<?> type) implements Type {
        @Override
        public String toString() {
            return type.getName();
        }
    }

    /** The methods of a Java class with one name, selected through the class or an instance. */
    record JavaMethod(Class<?> owner, String name) implements Type {
        @Override
        public String toString() {
            return owner.getName() + "." + name;
        }
    }
}
