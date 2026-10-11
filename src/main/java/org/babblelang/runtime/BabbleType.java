package org.babblelang.runtime;

import java.util.Arrays;

/**
 * A class or interface value. Both can have static members, declared in a
 * {@code static ( … )} block : they belong to the value, so two evaluations of the same
 * class expression have separate statics.
 */
public abstract class BabbleType implements HasMembers {
    protected final ClassShape shape;
    private final Object[] statics;

    protected BabbleType(ClassShape shape) {
        this.shape = shape;
        this.statics = new Object[shape.staticCount()];
        Arrays.fill(statics, Undefined.UNDEFINED);
    }

    public ClassShape shape() {
        return shape;
    }

    public String name() {
        return shape.name();
    }

    /** Runs the definition of a static member. */
    public Object initStatic(int index, Object value) {
        statics[index] = value;
        return value;
    }

    @Override
    public Object getMember(String name, boolean own) {
        int index = shape.staticIndex(name);
        if (index < 0) {
            throw Members.noSuchMember(name);
        }
        if (shape.isStaticPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        Object value = statics[index];
        if (value == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        if (shape.isStaticMethod(index)) {
            return new BoundMethod((BabbleFunction) value, this);
        }
        return value;
    }

    @Override
    public void setMember(String name, Object value, boolean own) {
        int index = shape.staticIndex(name);
        if (index < 0) {
            throw Members.noSuchMember(name);
        }
        if (shape.isStaticPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        if (shape.isStaticMethod(index)) {
            throw Members.cannotAssignMethod(name);
        }
        if (statics[index] == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        statics[index] = value;
    }
}
