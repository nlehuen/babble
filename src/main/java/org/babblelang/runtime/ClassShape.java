package org.babblelang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The static description of a class, object or interface expression, computed by the
 * analysis : its display name, its members and their kinds, its constructor parameters,
 * and its static members. Every class value created by the same expression shares it.
 * <p>
 * It is encoded as a string so that compiled code can hold it as a single constant.
 */
public final class ClassShape {
    public enum Kind {
        CLASS, VALUE_CLASS, OBJECT, INTERFACE
    }

    /** A member initialised by a function literal : a method, or an interface default. */
    public static final int METHOD = 1;
    public static final int PRIVATE = 2;

    private final Kind kind;
    private final String name;
    private final String[] members;
    private final int[] flags;
    private final String[] parameters;
    private final int[] parameterMembers;
    private final int requiredParameters;
    private final String[] statics;
    private final int[] staticFlags;
    private final Map<String, Integer> memberIndex = new HashMap<>();
    private final Map<String, Integer> staticIndex = new HashMap<>();

    /**
     * @param parameterMembers for each constructor parameter, the index of the member it
     *                         initialises, or -1 for a plain parameter
     */
    public ClassShape(Kind kind, String name, String[] members, int[] flags, String[] parameters, int[] parameterMembers,
                      int requiredParameters, String[] statics, int[] staticFlags) {
        this.kind = kind;
        this.name = name;
        this.members = members;
        this.flags = flags;
        this.parameters = parameters;
        this.parameterMembers = parameterMembers;
        this.requiredParameters = requiredParameters;
        this.statics = statics;
        this.staticFlags = staticFlags;
        for (int i = 0; i < members.length; i++) {
            memberIndex.put(members[i], i);
        }
        for (int i = 0; i < statics.length; i++) {
            staticIndex.put(statics[i], i);
        }
    }

    public Kind kind() {
        return kind;
    }

    public boolean isValue() {
        return kind == Kind.VALUE_CLASS;
    }

    public String name() {
        return name;
    }

    public int memberCount() {
        return members.length;
    }

    public String member(int index) {
        return members[index];
    }

    /** The index of a member, or -1. */
    public int memberIndex(String member) {
        return memberIndex.getOrDefault(member, -1);
    }

    public boolean isMethod(int index) {
        return (flags[index] & METHOD) != 0;
    }

    public boolean isPrivate(int index) {
        return (flags[index] & PRIVATE) != 0;
    }

    public String[] parameters() {
        return parameters;
    }

    public int parameterMember(int parameter) {
        return parameterMembers[parameter];
    }

    public int requiredParameters() {
        return requiredParameters;
    }

    public int staticCount() {
        return statics.length;
    }

    public int staticIndex(String member) {
        return staticIndex.getOrDefault(member, -1);
    }

    public boolean isStaticMethod(int index) {
        return (staticFlags[index] & METHOD) != 0;
    }

    public boolean isStaticPrivate(int index) {
        return (staticFlags[index] & PRIVATE) != 0;
    }

    // ------------------------------------------------------------------ encoding

    /** {@code KIND;name;required;m:f,m:f;p:i,p:i;s:f,s:f}, names being Babble identifiers. */
    public String encode() {
        return kind + ";" + name + ";" + requiredParameters + ";" + pairs(members, flags) + ";" + pairs(parameters, parameterMembers)
                + ";" + pairs(statics, staticFlags);
    }

    private static String pairs(String[] names, int[] values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(names[i]).append(':').append(values[i]);
        }
        return result.toString();
    }

    public static ClassShape decode(String encoded) {
        String[] parts = encoded.split(";", -1);
        List<String> members = new ArrayList<>();
        List<Integer> flags = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        List<Integer> parameterMembers = new ArrayList<>();
        List<String> statics = new ArrayList<>();
        List<Integer> staticFlags = new ArrayList<>();
        unpair(parts[3], members, flags);
        unpair(parts[4], parameters, parameterMembers);
        unpair(parts[5], statics, staticFlags);
        return new ClassShape(Kind.valueOf(parts[0]), parts[1], members.toArray(new String[0]), ints(flags),
                parameters.toArray(new String[0]), ints(parameterMembers), Integer.parseInt(parts[2]),
                statics.toArray(new String[0]), ints(staticFlags));
    }

    private static void unpair(String encoded, List<String> names, List<Integer> values) {
        if (encoded.isEmpty()) {
            return;
        }
        for (String pair : encoded.split(",")) {
            int colon = pair.indexOf(':');
            names.add(pair.substring(0, colon));
            values.add(Integer.parseInt(pair.substring(colon + 1)));
        }
    }

    private static int[] ints(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public String toString() {
        return encode();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassShape other && encode().equals(other.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(members) * 31 + name.hashCode();
    }
}
