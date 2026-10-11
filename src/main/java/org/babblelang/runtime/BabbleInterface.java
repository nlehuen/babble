package org.babblelang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The value of an {@code interface} expression : required members, default methods, and
 * the interfaces it extends, Babble ones or Java ones.
 */
public final class BabbleInterface extends BabbleType {
    private final Object[] parents;
    private final BabbleFunction[] defaults;

    /**
     * @param parents the extended interfaces : BabbleInterface values or Java interface classes
     */
    public BabbleInterface(ClassShape shape, Object[] parents) {
        super(shape);
        this.parents = parents;
        this.defaults = new BabbleFunction[shape.memberCount()];
    }

    /** Runs the definition of a default method. */
    public Object setDefault(int index, Object function) {
        defaults[index] = (BabbleFunction) function;
        return function;
    }

    /**
     * The default method this interface provides for a name : its own, or one it inherits.
     * A member of its own, required or not, hides what its parents provide.
     */
    public BabbleFunction findDefault(String name) {
        int index = shape.memberIndex(name);
        if (index >= 0) {
            return defaults[index];
        }
        for (Object parent : parents) {
            if (parent instanceof BabbleInterface babble) {
                BabbleFunction function = babble.findDefault(name);
                if (function != null) {
                    return function;
                }
            }
        }
        return null;
    }

    /** All the defaults this interface provides, its own hiding inherited ones. */
    void collectDefaults(Map<String, BabbleFunction> result) {
        Map<String, BabbleFunction> inherited = new LinkedHashMap<>();
        for (Object parent : parents) {
            if (parent instanceof BabbleInterface babble) {
                babble.collectDefaults(inherited);
            }
        }
        for (Map.Entry<String, BabbleFunction> entry : inherited.entrySet()) {
            if (shape.memberIndex(entry.getKey()) < 0) {
                result.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        for (int i = 0; i < defaults.length; i++) {
            if (defaults[i] != null) {
                result.putIfAbsent(shape.member(i), defaults[i]);
            }
        }
    }

    void collectJavaInterfaces(Set<Class<?>> result) {
        for (Object parent : parents) {
            if (parent instanceof Class<?> java) {
                result.add(java);
            } else {
                ((BabbleInterface) parent).collectJavaInterfaces(result);
            }
        }
    }

    /** Whether this interface is {@code type} or extends it, directly or not. */
    public boolean extendsType(Object type) {
        if (type == this) {
            return true;
        }
        for (Object parent : parents) {
            if (parent == type
                    || parent instanceof BabbleInterface babble && babble.extendsType(type)
                    || parent instanceof Class<?> java && type instanceof Class<?> other && other.isAssignableFrom(java)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "interface " + name();
    }
}
