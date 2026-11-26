package org.babblelang.runtime;

import java.util.Set;

/**
 * What {@code import("file.ba")} returns : the exported names of a file, as read-only members. A
 * member reads the current value of the definition, so a module imported in a cycle, before its
 * definitions ran, fails with "Name used before definition" like any other early use.
 */
public final class Module implements HasMembers {
    /**
     * {@code import} takes a name with this extension for a Babble file, any other for a Java
     * package.
     */
    public static final String EXTENSION = ".ba";

    private final String name;
    private final Namespace root;
    private final String[] exportNames;
    private final Slot<Object>[] exportSlots;
    private final Set<String> exports;

    @SuppressWarnings("unchecked")
    public Module(String name, Namespace root, String[] exports) {
        this.name = name;
        this.root = root;
        this.exportNames = exports.clone();
        this.exportSlots = (Slot<Object>[]) new Slot<?>[exports.length];
        this.exports = Set.of(exports);
    }

    /** The path of the file, as it shows in error messages. */
    public String name() {
        return name;
    }

    public Set<String> exports() {
        return exports;
    }

    public int exportIndex(String member) {
        for (int i = 0; i < exportNames.length; i++) {
            if (exportNames[i].equals(member)) {
                return i;
            }
        }
        return -1;
    }

    public Object exportAt(int index) {
        if (index < 0 || index >= exportNames.length) {
            throw new BabbleException("No such export index : " + index + " in " + name);
        }
        Slot<Object> slot = exportSlots[index];
        if (slot == null) {
            slot = root.get(exportNames[index]);
            exportSlots[index] = slot;
        }
        return slot.get();
    }

    public boolean isExportFinal(int index) {
        if (index < 0 || index >= exportNames.length) {
            return false;
        }
        Slot<Object> slot = exportSlots[index];
        if (slot == null) {
            slot = root.get(exportNames[index]);
            exportSlots[index] = slot;
        }
        return slot != null && slot.isFinal();
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
        throw new BabbleException(
                exports.contains(member)
                        ? "Cannot assign to an export : " + member
                        : "No such export : " + member + " in " + name);
    }

    @Override
    public String toString() {
        return "module " + name;
    }
}
