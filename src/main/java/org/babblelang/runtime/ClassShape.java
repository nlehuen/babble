package org.babblelang.runtime;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The static description of a class, object or interface expression, computed by the analysis : its
 * display name, its members and their kinds, its constructor parameters, and its static members.
 * Every class value created by the same expression shares it.
 *
 * <p>It is encoded as a string so that compiled code can hold it as a single constant.
 *
 * <p>Its id names the expression for nominal type checks (T9) : instances of every class value an
 * expression creates have that type. Members and statics declared with a type carry its descriptor
 * ({@link Types}), which an assignment checks.
 */
public final class ClassShape {
    public enum Kind {
        CLASS,
        VALUE_CLASS,
        OBJECT,
        INTERFACE
    }

    /**
     * Static description of one constructor ({@code BIP040}) : primary header constructor or
     * explicit/secondary {@code init} constructor.
     */
    public record ConstructorShape(
            boolean isPrimary,
            boolean isPrivate,
            String[] parameters,
            int[] parameterMembers,
            int requiredParameters,
            int keywordOnlyStart,
            int restIndex,
            String[] paramTypes) {
        public ConstructorShape {
            parameters = parameters != null ? parameters : new String[0];
            parameterMembers = parameterMembers != null ? parameterMembers : new int[0];
            paramTypes = paramTypes != null ? paramTypes : new String[parameters.length];
        }
    }

    /** A member initialised by a function literal : a method, or an interface default. */
    public static final int METHOD = 1;

    public static final int PRIVATE = 2;

    /** A member declared with {@code var} rather than {@code def} ({@code BIP034}). */
    public static final int MUTABLE = 4;

    private final Kind kind;
    private final String name;
    private final String[] members;
    private final int[] flags;
    private final String[] parameters;
    private final int[] parameterMembers;
    private final int requiredParameters;
    private final int keywordOnlyStart;
    private final String[] statics;
    private final int[] staticFlags;
    private final String id;
    private final String[] memberTypes;
    private final String[] staticTypes;
    private final ConstructorShape[] constructors;
    private final Map<String, Integer> memberIndex = new HashMap<>();
    private final Map<String, Integer> staticIndex = new HashMap<>();

    /**
     * @param parameterMembers for each constructor parameter, the index of the member it
     *     initialises, or -1 for a plain parameter
     */
    public ClassShape(
            Kind kind,
            String name,
            String[] members,
            int[] flags,
            String[] parameters,
            int[] parameterMembers,
            int requiredParameters,
            String[] statics,
            int[] staticFlags) {
        this(
                kind,
                name,
                members,
                flags,
                parameters,
                parameterMembers,
                requiredParameters,
                parameters.length,
                statics,
                staticFlags,
                "",
                new String[members.length],
                new String[statics.length]);
    }

    /**
     * @param id a name for the expression, unique within the JVM
     * @param memberTypes for each member, the descriptor of its declared type, or null
     * @param staticTypes the same for statics
     */
    public ClassShape(
            Kind kind,
            String name,
            String[] members,
            int[] flags,
            String[] parameters,
            int[] parameterMembers,
            int requiredParameters,
            String[] statics,
            int[] staticFlags,
            String id,
            String[] memberTypes,
            String[] staticTypes) {
        this(
                kind,
                name,
                members,
                flags,
                parameters,
                parameterMembers,
                requiredParameters,
                parameters.length,
                statics,
                staticFlags,
                id,
                memberTypes,
                staticTypes);
    }

    public ClassShape(
            Kind kind,
            String name,
            String[] members,
            int[] flags,
            String[] parameters,
            int[] parameterMembers,
            int requiredParameters,
            int keywordOnlyStart,
            String[] statics,
            int[] staticFlags,
            String id,
            String[] memberTypes,
            String[] staticTypes) {
        this(
                kind,
                name,
                members,
                flags,
                parameters,
                parameterMembers,
                requiredParameters,
                keywordOnlyStart,
                statics,
                staticFlags,
                id,
                memberTypes,
                staticTypes,
                null);
    }

    public ClassShape(
            Kind kind,
            String name,
            String[] members,
            int[] flags,
            String[] parameters,
            int[] parameterMembers,
            int requiredParameters,
            int keywordOnlyStart,
            String[] statics,
            int[] staticFlags,
            String id,
            String[] memberTypes,
            String[] staticTypes,
            ConstructorShape[] constructors) {
        this.id = id;
        this.memberTypes = memberTypes;
        this.staticTypes = staticTypes;
        this.kind = kind;
        this.name = name;
        this.members = members;
        this.flags = flags;
        this.parameters = parameters;
        this.parameterMembers = parameterMembers;
        this.requiredParameters = requiredParameters;
        this.keywordOnlyStart = keywordOnlyStart;
        this.statics = statics;
        this.staticFlags = staticFlags;
        this.constructors =
                constructors != null && constructors.length > 0
                        ? constructors
                        : new ConstructorShape[] {
                            new ConstructorShape(
                                    true,
                                    false,
                                    parameters,
                                    parameterMembers,
                                    requiredParameters,
                                    keywordOnlyStart,
                                    -1,
                                    new String[parameters.length])
                        };
        for (int i = 0; i < members.length; i++) {
            memberIndex.put(members[i], i);
        }
        for (int i = 0; i < statics.length; i++) {
            staticIndex.put(statics[i], i);
        }
    }

    public ConstructorShape[] constructors() {
        return constructors;
    }

    public int constructorCount() {
        return constructors.length;
    }

    public ConstructorShape constructor(int index) {
        return constructors[index];
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

    public boolean isMutable(int index) {
        return (flags[index] & MUTABLE) != 0;
    }

    public String[] parameters() {
        return parameters;
    }

    public int parameterMember(int parameter) {
        return parameterMembers[parameter];
    }

    /**
     * Whether member {@code memberIndex} is initialized directly by a constructor header parameter.
     */
    public boolean isHeaderParameterMember(int memberIndex) {
        if (memberIndex < 0) {
            return false;
        }
        for (int pm : parameterMembers) {
            if (pm == memberIndex) {
                return true;
            }
        }
        return false;
    }

    public int requiredParameters() {
        return requiredParameters;
    }

    public int keywordOnlyStart() {
        return keywordOnlyStart;
    }

    public int staticCount() {
        return statics.length;
    }

    public String staticMember(int index) {
        return statics[index];
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

    public boolean isStaticMutable(int index) {
        return (staticFlags[index] & MUTABLE) != 0;
    }

    public String id() {
        return id;
    }

    /** The descriptor of a member's declared type, checked when it is assigned, or null. */
    public String memberType(int index) {
        return memberTypes[index];
    }

    public String staticType(int index) {
        return staticTypes[index];
    }

    // ------------------------------------------------------------------ encoding

    /**
     * {@code KIND;name;required;m:f,m:f;p:i,p:i;s:f,s:f;id;t,t;t,t[;kwStart]}, names being Babble
     * identifiers, and type descriptors URL-encoded, empty for none.
     */
    public String encode() {
        String base =
                kind
                        + ";"
                        + name
                        + ";"
                        + requiredParameters
                        + ";"
                        + pairs(members, flags)
                        + ";"
                        + pairs(parameters, parameterMembers)
                        + ";"
                        + pairs(statics, staticFlags)
                        + ";"
                        + id
                        + ";"
                        + typeList(memberTypes)
                        + ";"
                        + typeList(staticTypes);
        boolean nonTrivialCtors =
                constructors.length > 1
                        || (constructors.length == 1
                                && (!constructors[0].isPrimary()
                                        || constructors[0].isPrivate()
                                        || constructors[0].restIndex() >= 0
                                        || hasNonNull(constructors[0].paramTypes())));
        if (!nonTrivialCtors) {
            return keywordOnlyStart < parameters.length ? base + ";" + keywordOnlyStart : base;
        }
        return base + ";" + keywordOnlyStart + ";" + encodeConstructors(constructors);
    }

    private static boolean hasNonNull(String[] arr) {
        for (String s : arr) {
            if (s != null) {
                return true;
            }
        }
        return false;
    }

    private static String encodeConstructors(ConstructorShape[] ctors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctors.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            ConstructorShape c = ctors[i];
            int flags = (c.isPrimary() ? 1 : 0) | (c.isPrivate() ? 2 : 0);
            sb.append(flags)
                    .append('~')
                    .append(c.requiredParameters())
                    .append('~')
                    .append(c.keywordOnlyStart())
                    .append('~')
                    .append(c.restIndex())
                    .append('~')
                    .append(pairs(c.parameters(), c.parameterMembers()))
                    .append('~')
                    .append(typeList(c.paramTypes()));
        }
        return sb.toString();
    }

    private static ConstructorShape[] decodeConstructors(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        String[] entries = encoded.split("\\|", -1);
        ConstructorShape[] result = new ConstructorShape[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] f = entries[i].split("~", -1);
            int flags = Integer.parseInt(f[0]);
            int req = Integer.parseInt(f[1]);
            int kw = Integer.parseInt(f[2]);
            int rest = Integer.parseInt(f[3]);
            List<String> pNames = new ArrayList<>();
            List<Integer> pMembers = new ArrayList<>();
            unpair(f[4], pNames, pMembers);
            String[] pTypes = decodeTypes(f[5], pNames.size());
            result[i] =
                    new ConstructorShape(
                            (flags & 1) != 0,
                            (flags & 2) != 0,
                            pNames.toArray(new String[0]),
                            ints(pMembers),
                            req,
                            kw,
                            rest,
                            pTypes);
        }
        return result;
    }

    private static String typeList(String[] types) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            if (types[i] != null) {
                result.append(URLEncoder.encode(types[i], StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static String[] decodeTypes(String encoded, int count) {
        String[] result = new String[count];
        String[] parts = encoded.split(",", -1);
        for (int i = 0; i < count && i < parts.length; i++) {
            result[i] =
                    parts[i].isEmpty() ? null : URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
        }
        return result;
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
        int kwStart = parts.length > 9 ? Integer.parseInt(parts[9]) : parameters.size();
        ConstructorShape[] ctors = parts.length > 10 ? decodeConstructors(parts[10]) : null;
        return new ClassShape(
                Kind.valueOf(parts[0]),
                parts[1],
                members.toArray(new String[0]),
                ints(flags),
                parameters.toArray(new String[0]),
                ints(parameterMembers),
                Integer.parseInt(parts[2]),
                kwStart,
                statics.toArray(new String[0]),
                ints(staticFlags),
                parts[6],
                decodeTypes(parts[7], members.size()),
                decodeTypes(parts[8], statics.size()),
                ctors);
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
