package org.babblelang.runtime;

import java.util.Arrays;

/**
 * An instance of a Babble class : its class value, and its member values indexed through
 * the class's shape. The member set is fixed by the class.
 * <p>
 * The interpreter keeps the members in an array. Compiled classes (compiler phase 2)
 * extend this class with one JVM field per member, and override {@link #field} and
 * {@link #setField} ; their own code reaches the fields directly.
 */
public class BabbleInstance implements HasMembers {
    private static final CallSite TO_STRING = new CallSite("<toString>", -1, "toString", new String[0]);

    private final BabbleClass babbleClass;
    private final Object[] values;
    private final ScriptRuntime runtime;
    private Object face;

    public BabbleInstance(BabbleClass babbleClass, ScriptRuntime runtime) {
        this(babbleClass, runtime, true);
    }

    /**
     * @param array whether members are kept in an array ; a compiled class keeps them in
     *              fields, which its constructor sets to undefined
     */
    protected BabbleInstance(BabbleClass babbleClass, ScriptRuntime runtime, boolean array) {
        this.babbleClass = babbleClass;
        this.values = array ? new Object[babbleClass.shape().memberCount()] : null;
        this.runtime = runtime;
        if (array) {
            Arrays.fill(values, Undefined.UNDEFINED);
        }
    }

    /** The value of the member with that index, undefined until its def has run. */
    protected Object field(int index) {
        return values[index];
    }

    protected void setField(int index, Object value) {
        values[index] = value;
    }

    public BabbleClass babbleClass() {
        return babbleClass;
    }

    /** The runtime of the eval that created the instance, for calls coming from Java. */
    public ScriptRuntime runtime() {
        return runtime;
    }

    /** Runs the definition of a member : a {@code def} in the body, or a header member. */
    public Object initMember(int index, Object value) {
        setField(index, value);
        return value;
    }

    @Override
    public Object getMember(String name, boolean own) {
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index < 0) {
            BabbleFunction inherited = babbleClass.defaultMethod(name);
            if (inherited != null) {
                return new BoundMethod(inherited, this);
            }
            if (!babbleClass.javaDefaultMethods(name).isEmpty()) {
                return new JavaDefaultCall(this, babbleClass.javaDefaultMethods(name), babbleClass.name() + "." + name);
            }
            throw Members.noSuchMember(name);
        }
        if (shape.isPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        Object value = field(index);
        if (value == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        if (shape.isMethod(index)) {
            return new BoundMethod((BabbleFunction) value, this);
        }
        return value;
    }

    @Override
    public void setMember(String name, Object value, boolean own) {
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index < 0) {
            if (babbleClass.defaultMethod(name) != null || !babbleClass.javaDefaultMethods(name).isEmpty()) {
                throw Members.cannotAssignMethod(name);
            }
            throw Members.noSuchMember(name);
        }
        if (shape.isPrivate(index) && !own) {
            throw Members.isPrivate(name);
        }
        if (shape.isMethod(index)) {
            throw Members.cannotAssignMethod(name);
        }
        if (shape.isValue()) {
            throw Members.readOnly(name);
        }
        if (field(index) == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        setField(index, value);
    }

    /**
     * The method a name designates, defined by the class or inherited from an interface,
     * or null. Private methods are included : this is for the instance's own use.
     */
    BabbleFunction method(String name) {
        ClassShape shape = babbleClass.shape();
        int index = shape.memberIndex(name);
        if (index >= 0) {
            return shape.isMethod(index) && field(index) instanceof BabbleFunction function ? function : null;
        }
        return babbleClass.defaultMethod(name);
    }

    /**
     * What Java sees of this instance : itself, or when its class implements Java
     * interfaces, a proxy implementing them, always the same one.
     */
    public Object face() {
        if (babbleClass.javaInterfaces().length == 0) {
            return this;
        }
        if (face == null) {
            face = Faces.create(this);
        }
        return face;
    }

    @Override
    public boolean equals(Object o) {
        if (!babbleClass.shape().isValue()) {
            return this == o;
        }
        if (!(o instanceof BabbleInstance other) || other.babbleClass != babbleClass) {
            return false;
        }
        ClassShape shape = babbleClass.shape();
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!shape.isMethod(i) && !Operators.equal(field(i), other.field(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (!babbleClass.shape().isValue()) {
            return System.identityHashCode(this);
        }
        ClassShape shape = babbleClass.shape();
        int hash = 1;
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!shape.isMethod(i)) {
                hash = 31 * hash + Values.hash(field(i));
            }
        }
        return hash;
    }

    /**
     * A zero-parameter method {@code toString} when the class has one ; otherwise the
     * public fields of a value, or the class name and an identity hash.
     */
    @Override
    public String toString() {
        BabbleFunction toString = method("toString");
        if (toString != null && toString.minArity() == 0) {
            return String.valueOf(toString.invoke(this, runtime, Arguments.NONE, null, TO_STRING));
        }
        ClassShape shape = babbleClass.shape();
        if (!shape.isValue()) {
            return babbleClass.name() + "@" + Integer.toHexString(System.identityHashCode(this));
        }
        StringBuilder result = new StringBuilder(babbleClass.name()).append('(');
        boolean first = true;
        for (int i = 0; i < shape.memberCount(); i++) {
            if (!shape.isMethod(i) && !shape.isPrivate(i)) {
                if (!first) {
                    result.append(", ");
                }
                result.append(shape.member(i)).append(": ").append(field(i));
                first = false;
            }
        }
        return result.append(')').toString();
    }
}
