package org.babblelang.ir;

import java.util.Objects;

/**
 * Target slice selector for canonical IR dumping ({@code BIP029-I1}, {@code BIP029-D7}).
 *
 * <p>Syntax used in {@code .batest.md} headers and CLI tools:
 * <ul>
 *   <li>{@code fn=<name>} — named non-method function</li>
 *   <li>{@code fn=<Type>.<method>} — method owned by a class, record, object, or interface</li>
 *   <li>{@code class=<name>} / {@code record=<name>} / {@code interface=<name>} — type declaration and owned methods</li>
 *   <li>{@code top-level} — script top-level statements</li>
 *   <li>{@code module} — entire normalized module</li>
 * </ul>
 */
public sealed interface IrTarget permits
        IrTarget.Fn,
        IrTarget.Method,
        IrTarget.TypeDef,
        IrTarget.TopLevel,
        IrTarget.Module {

    enum TypeKind {
        CLASS("class"),
        RECORD("record"),
        INTERFACE("interface");

        private final String label;

        TypeKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    record Fn(String name) implements IrTarget {
        public Fn {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Function target name cannot be empty");
            }
        }

        @Override
        public String format() {
            return "fn=" + name;
        }
    }

    record Method(String ownerName, String methodName) implements IrTarget {
        public Method {
            Objects.requireNonNull(ownerName, "ownerName");
            Objects.requireNonNull(methodName, "methodName");
            if (ownerName.isEmpty() || methodName.isEmpty()) {
                throw new IllegalArgumentException("Method target requires non-empty owner and method names");
            }
        }

        @Override
        public String format() {
            return "fn=" + ownerName + "." + methodName;
        }
    }

    record TypeDef(TypeKind kind, String name) implements IrTarget {
        public TypeDef {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Type target name cannot be empty");
            }
        }

        @Override
        public String format() {
            return kind.label() + "=" + name;
        }
    }

    record TopLevel() implements IrTarget {
        @Override
        public String format() {
            return "top-level";
        }
    }

    record Module() implements IrTarget {
        @Override
        public String format() {
            return "module";
        }
    }

    String format();

    /** Parses a target token such as {@code fn=norm}, {@code fn=Vec3.dot}, {@code record=Vec3}, {@code top-level}, or {@code module}. */
    static IrTarget parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Missing IR target");
        }
        if ("top-level".equals(token)) {
            return new TopLevel();
        }
        if ("module".equals(token)) {
            return new Module();
        }
        int eq = token.indexOf('=');
        if (eq <= 0 || eq == token.length() - 1) {
            throw new IllegalArgumentException("Invalid IR target : " + token
                    + " (expected fn=<name>, fn=<Type>.<method>, class=<name>, record=<name>, interface=<name>, top-level, or module)");
        }
        String key = token.substring(0, eq);
        String val = token.substring(eq + 1);
        return switch (key) {
            case "fn" -> {
                int dot = val.indexOf('.');
                if (dot >= 0) {
                    if (dot == 0 || dot == val.length() - 1 || val.indexOf('.', dot + 1) >= 0) {
                        throw new IllegalArgumentException("Invalid method target : " + token);
                    }
                    yield new Method(val.substring(0, dot), val.substring(dot + 1));
                }
                yield new Fn(val);
            }
            case "class" -> new TypeDef(TypeKind.CLASS, val);
            case "record" -> new TypeDef(TypeKind.RECORD, val);
            case "interface" -> new TypeDef(TypeKind.INTERFACE, val);
            default -> throw new IllegalArgumentException("Unknown IR target kind : " + token);
        };
    }
}
