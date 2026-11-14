package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

import java.util.Set;

/**
 * What {@code import("file.ba")} returns : the exported names of a file, as read-only
 * members. A member reads the current value of the definition, so a module imported in a
 * cycle, before its definitions ran, fails with "Name used before definition" like any
 * other early use.
 */
public final class Module implements HasMembers {
    /** {@code import} takes a name with this extension for a Babble file, any other for a Java package. */
    public static final String EXTENSION = ".ba";

    private final String name;
    private final Namespace root;
    private final Set<String> exports;

    public Module(String name, Namespace root, String[] exports) {
        this.name = name;
        this.root = root;
        this.exports = Set.of(exports);
    }

    /** The path of the file, as it shows in error messages. */
    public String name() {
        return name;
    }

    public Set<String> exports() {
        return exports;
    }

    @Override
    public Object getMember(String member, boolean own) {
        if (!exports.contains(member)) {
            throw new BabbleException("No such export : " + member + " in " + name);
        }
        return root.lookup(member);
    }

    @Override
    public void setMember(String member, Object value, boolean own) {
        throw new BabbleException(exports.contains(member)
                ? "Cannot assign to an export : " + member
                : "No such export : " + member + " in " + name);
    }

    @Override
    public String toString() {
        return "module " + name;
    }
}
