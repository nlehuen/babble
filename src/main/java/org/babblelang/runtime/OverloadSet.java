package org.babblelang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime representation of a Babble {@code overload (...)} set (BIP021-D9, BIP021-D16).
 *
 * <p>Holds the flattened array of {@link Callable} alternatives along with per-alternative
 * signature metadata so compiled code can reach statically resolved alternatives via {@link
 * #member(int)}, calls through set types can select by signature via {@link #select(String)}, and
 * untyped/reflective calls can fall back to {@link #call(Object...)}.
 */
public final class OverloadSet implements Callable {

    private static final Map<Class<?>, String> JAVA_SCALAR_DESCS =
            Map.ofEntries(
                    Map.entry(byte.class, "byte|byte|"),
                    Map.entry(Byte.class, "byte|byte|"),
                    Map.entry(short.class, "short|short|"),
                    Map.entry(Short.class, "short|short|"),
                    Map.entry(int.class, "int|int|"),
                    Map.entry(Integer.class, "int|int|"),
                    Map.entry(long.class, "long|long|"),
                    Map.entry(Long.class, "long|long|"),
                    Map.entry(float.class, "float|float|"),
                    Map.entry(Float.class, "float|float|"),
                    Map.entry(double.class, "double|double|"),
                    Map.entry(Double.class, "double|double|"),
                    Map.entry(boolean.class, "bool|bool|"),
                    Map.entry(Boolean.class, "bool|bool|"),
                    Map.entry(char.class, "char|char|"),
                    Map.entry(Character.class, "char|char|"),
                    Map.entry(String.class, "string|string|"),
                    Map.entry(Object.class, ""));

    private static final Map<Class<?>, Integer> RUNTIME_NUMERIC_RANKS =
            Map.of(
                    Byte.class,
                    0,
                    Short.class,
                    1,
                    Integer.class,
                    2,
                    Long.class,
                    3,
                    Float.class,
                    4,
                    Double.class,
                    5);

    private static final Map<String, Integer> DESC_NUMERIC_RANKS =
            Map.of("byte", 0, "short", 1, "int", 2, "long", 3, "float", 4, "double", 5);

    /** Metadata for one alternative in an {@link OverloadSet}. */
    public static final class AltMeta {
        public final String signatureKey;
        public final String display;
        public final int minArity;
        public final int maxArity;
        public final int restIndex;
        public final String[] paramDescriptors;

        public AltMeta(
                String signatureKey,
                String display,
                int minArity,
                int maxArity,
                int restIndex,
                String[] paramDescriptors) {
            this.signatureKey = signatureKey;
            this.display = display;
            this.minArity = minArity;
            this.maxArity = maxArity;
            this.restIndex = restIndex;
            this.paramDescriptors = paramDescriptors;
        }
    }

    private final Callable[] alternatives;
    private final AltMeta[] metas;
    private final ConcurrentHashMap<String, Callable> selectionCache = new ConcurrentHashMap<>();

    private OverloadSet(Callable[] alternatives, AltMeta[] metas) {
        this.alternatives = alternatives;
        this.metas = metas;
    }

    /**
     * Builds an {@link OverloadSet} by flattening any nested {@link OverloadSet} operands and
     * pairing each alternative with its {@link AltMeta}.
     */
    public static OverloadSet create(
            Object[] operands,
            String[] sigKeys,
            String[] displays,
            int[] minArities,
            int[] maxArities,
            int[] restIndices,
            String[][] paramDescriptors) {
        AltMeta[] metas = new AltMeta[sigKeys.length];
        for (int i = 0; i < sigKeys.length; i++) {
            metas[i] =
                    new AltMeta(
                            sigKeys[i],
                            displays[i],
                            minArities[i],
                            maxArities[i],
                            restIndices[i],
                            paramDescriptors[i]);
        }
        return of(operands, metas);
    }

    /** Convenience overload used by the interpreter and tests when metadata is already bundled. */
    public static OverloadSet of(Object[] operands, AltMeta[] metas) {
        List<Callable> flatCallables = new ArrayList<>();
        List<AltMeta> flatMetas = new ArrayList<>();
        int metaIdx = 0;
        for (Object op : operands) {
            if (op instanceof OverloadSet nested) {
                for (int i = 0; i < nested.alternatives.length; i++) {
                    flatCallables.add(nested.alternatives[i]);
                    flatMetas.add(nested.metas[i]);
                    metaIdx++;
                }
            } else {
                flatCallables.add((Callable) op);
                flatMetas.add(metas[metaIdx++]);
            }
        }
        return new OverloadSet(
                flatCallables.toArray(Callable[]::new), flatMetas.toArray(AltMeta[]::new));
    }

    /** Indexed access to a statically resolved alternative (BIP021-D14, BIP021-D16). */
    public Callable member(int index) {
        return alternatives[index];
    }

    /**
     * Indexed access from an arbitrary callable target (returns {@code target} directly when a
     * single function satisfies an overload set type per BIP021-D12).
     */
    public static Callable memberOf(Object target, int index) {
        if (target instanceof OverloadSet set) {
            return set.member(index);
        }
        return (Callable) target;
    }

    /**
     * Signature-keyed selection from an arbitrary callable target (returns {@code target} directly
     * when a single function satisfies an overload set type per BIP021-D12).
     */
    public static Callable selectFrom(Object target, String signatureKey) {
        if (target instanceof OverloadSet set) {
            return set.select(signatureKey);
        }
        return (Callable) target;
    }

    /**
     * Returns a new {@link OverloadSet} whose {@link BabbleFunction} alternatives are bound to
     * {@code receiver}.
     */
    public OverloadSet bind(Object receiver) {
        Callable[] bound = new Callable[alternatives.length];
        for (int i = 0; i < alternatives.length; i++) {
            Callable alt = alternatives[i];
            bound[i] = alt instanceof BabbleFunction bf ? new BoundMethod(bf, receiver) : alt;
        }
        return new OverloadSet(bound, metas);
    }

    /**
     * Selects the most specific alternative matching a Java interface method's parameter types, or
     * {@code null} if none matches (used by {@link Faces} for interpreted Java interface proxies).
     */
    public Callable selectForJavaMethod(java.lang.reflect.Method jm) {
        Class<?>[] params = jm.getParameterTypes();
        String[] descs = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            descs[i] = javaClassToDescriptor(params[i]);
        }
        int best = -1;
        for (int i = 0; i < metas.length; i++) {
            AltMeta m = metas[i];
            boolean matches;
            if (jm.isVarArgs()
                    && m.restIndex == params.length - 1
                    && params[params.length - 1].isArray()) {
                String[] expanded = Arrays.copyOf(descs, params.length);
                expanded[params.length - 1] =
                        javaClassToDescriptor(params[params.length - 1].getComponentType());
                matches = acceptsDescriptors(m, expanded);
            } else {
                matches = acceptsDescriptors(m, descs);
            }
            if (matches && (best < 0 || isMoreSpecificMeta(m, metas[best], params.length))) {
                best = i;
            }
        }
        return best >= 0 ? alternatives[best] : null;
    }

    private static String javaClassToDescriptor(Class<?> cls) {
        String scalar = JAVA_SCALAR_DESCS.get(cls);
        return scalar != null ? scalar : "java|" + cls.getName() + "|" + cls.getName();
    }

    /**
     * Selects the runtime alternative matching {@code signatureKey} (exact match first, then
     * most-specific alternative accepting the target signature's parameter descriptors), cached per
     * signature key (BIP021-D12, BIP021-D16).
     */
    public Callable select(String signatureKey) {
        return selectionCache.computeIfAbsent(signatureKey, this::findBySignature);
    }

    private Callable findBySignature(String signatureKey) {
        // 1. Exact signature match.
        for (int i = 0; i < metas.length; i++) {
            if (signatureKey.equals(metas[i].signatureKey)) {
                return alternatives[i];
            }
        }
        // 2. Fallback: match by encoded parameter descriptors ("desc0;desc1;...#restIdx").
        int hash = signatureKey.lastIndexOf('#');
        String paramsPart = hash >= 0 ? signatureKey.substring(0, hash) : signatureKey;
        String[] targetDescs = paramsPart.isEmpty() ? new String[0] : paramsPart.split(";", -1);
        int best = -1;
        for (int i = 0; i < metas.length; i++) {
            AltMeta m = metas[i];
            if (acceptsDescriptors(m, targetDescs)
                    && (best < 0 || isMoreSpecificMeta(m, metas[best], targetDescs.length))) {
                best = i;
            }
        }
        if (best >= 0) {
            return alternatives[best];
        }
        throw new BabbleException(
                "No applicable overload for ("
                        + String.join(", ", targetDescs)
                        + ") : candidates are "
                        + candidatesSummary());
    }

    /**
     * Returns all candidate signature keys from {@code candidates} that accept {@code
     * signatureKey}, ordered from most specific to least specific (exact match first), for
     * closed-world Wasm {@code OverloadSelect} lowering ({@code BIP025-I5}).
     */
    public static List<String> matchingKeysBySpecificity(
            java.util.Collection<AltMeta> candidates, String signatureKey) {
        if (signatureKey == null) {
            return List.of();
        }
        int hash = signatureKey.lastIndexOf('#');
        String paramsPart = hash >= 0 ? signatureKey.substring(0, hash) : signatureKey;
        String[] targetDescs = paramsPart.isEmpty() ? new String[0] : paramsPart.split(";", -1);

        Map<String, AltMeta> unique = new java.util.LinkedHashMap<>();
        for (AltMeta m : candidates) {
            if (m != null && m.signatureKey != null) {
                unique.putIfAbsent(m.signatureKey, m);
            }
        }
        List<AltMeta> matching = new ArrayList<>();
        boolean hasExact = false;
        for (AltMeta m : unique.values()) {
            if (signatureKey.equals(m.signatureKey)) {
                hasExact = true;
            } else if (acceptsDescriptors(m, targetDescs)) {
                matching.add(m);
            }
        }
        List<String> ordered = new ArrayList<>(matching.size() + 1);
        if (hasExact) {
            ordered.add(signatureKey);
        }
        List<AltMeta> remaining = new ArrayList<>(matching);
        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            for (int i = 1; i < remaining.size(); i++) {
                if (isMoreSpecificMeta(
                        remaining.get(i), remaining.get(bestIdx), targetDescs.length)) {
                    bestIdx = i;
                }
            }
            ordered.add(remaining.remove(bestIdx).signatureKey);
        }
        return ordered;
    }

    private static boolean acceptsDescriptors(AltMeta m, String[] targetDescs) {
        int n = targetDescs.length;
        if (n < m.minArity || n > m.maxArity) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!descriptorAccepts(paramDescAt(m, i), targetDescs[i])) {
                return false;
            }
        }
        return true;
    }

    private static String paramDescAt(AltMeta m, int index) {
        int slot = (m.restIndex >= 0 && index >= m.restIndex) ? m.restIndex : index;
        return slot < m.paramDescriptors.length ? m.paramDescriptors[slot] : "";
    }

    /**
     * Dynamic fallback for untyped/reflective calls (BIP021-D16): resolves by runtime argument
     * classes under BIP021-D11.
     */
    @Override
    public Object call(ScriptRuntime runtime, Object[] arguments, String[] names, CallSite site) {
        int n = arguments.length;
        int best = -1;
        for (int i = 0; i < metas.length; i++) {
            AltMeta m = metas[i];
            if (n < m.minArity || n > m.maxArity || !matchesRuntimeArgs(m, arguments)) {
                continue;
            }
            if (best < 0 || isMoreSpecificMeta(m, metas[best], n)) {
                best = i;
            }
        }
        if (best < 0) {
            throw new BabbleException(
                    "No applicable overload for ("
                            + runtimeArgNames(arguments)
                            + ") : candidates are "
                            + candidatesSummary());
        }
        Callable target = alternatives[best];
        AltMeta meta = metas[best];
        if (meta.restIndex >= 0 && !isPackedRestCall(meta, arguments)) {
            Object[] packed = new Object[meta.restIndex + 1];
            System.arraycopy(arguments, 0, packed, 0, meta.restIndex);
            packed[meta.restIndex] =
                    Spreads.packedRest(
                            ArgumentsView.of(
                                    Arrays.copyOfRange(
                                            arguments, meta.restIndex, arguments.length)));
            return target.call(runtime, packed, null, site);
        }
        return target.call(runtime, arguments, names, site);
    }

    private static boolean isPackedRestCall(AltMeta m, Object[] arguments) {
        return m.restIndex >= 0
                && arguments.length == m.restIndex + 1
                && arguments[m.restIndex] instanceof ArgumentsView.PackedRest;
    }

    private static boolean matchesRuntimeArgs(AltMeta m, Object[] arguments) {
        int limit = isPackedRestCall(m, arguments) ? m.restIndex : arguments.length;
        for (int i = 0; i < limit; i++) {
            if (!matchesValue(arguments[i], paramDescAt(m, i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesValue(Object value, String desc) {
        if (desc.isEmpty()) {
            return true;
        }
        if (Types.matches(value, desc)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        int valRank = RUNTIME_NUMERIC_RANKS.getOrDefault(value.getClass(), -1);
        int targetRank = DESC_NUMERIC_RANKS.getOrDefault(descriptorTag(desc), -1);
        return valRank >= 0 && targetRank >= 0 && valRank <= targetRank;
    }

    private static String descriptorTag(String desc) {
        int bar = desc.indexOf('|');
        return bar >= 0 ? desc.substring(0, bar) : desc;
    }

    private static String descriptorPayload(String desc) {
        int first = desc.indexOf('|');
        int second = first >= 0 ? desc.indexOf('|', first + 1) : -1;
        return second >= 0 ? desc.substring(second + 1) : "";
    }

    private static boolean descriptorAccepts(String paramDesc, String argDesc) {
        if (paramDesc.isEmpty() || paramDesc.equals(argDesc)) {
            return true;
        }
        String pTag = descriptorTag(paramDesc);
        String aTag = descriptorTag(argDesc);
        if ("union".equals(aTag)) {
            return matchComponents(argDesc, paramDesc, true, true);
        }
        if ("union".equals(pTag)) {
            return matchComponents(paramDesc, argDesc, false, false);
        }
        if ("inter".equals(pTag)) {
            return matchComponents(paramDesc, argDesc, true, false);
        }
        if ("inter".equals(aTag)) {
            return matchComponents(argDesc, paramDesc, false, true);
        }
        if ("any".equals(pTag)) {
            return !"null".equals(aTag) && !argDesc.isEmpty();
        }
        int pr = DESC_NUMERIC_RANKS.getOrDefault(pTag, -1);
        int ar = DESC_NUMERIC_RANKS.getOrDefault(aTag, -1);
        if (pr >= 0 && ar >= 0) {
            return ar <= pr;
        }
        if ("java".equals(pTag)) {
            Class<?> pc = resolveClass(descriptorPayload(paramDesc));
            Class<?> ac =
                    "string".equals(aTag)
                            ? String.class
                            : ("java".equals(aTag)
                                    ? resolveClass(descriptorPayload(argDesc))
                                    : null);
            return pc != null && ac != null && pc.isAssignableFrom(ac);
        }
        return false;
    }

    private static boolean matchComponents(
            String compound, String other, boolean all, boolean compoundIsArg) {
        String payload = descriptorPayload(compound);
        for (String enc : payload.split(",", -1)) {
            String comp = Types.decodeComponent(enc);
            boolean ok =
                    compoundIsArg ? descriptorAccepts(other, comp) : descriptorAccepts(comp, other);
            if (ok != all) {
                return ok;
            }
        }
        return all;
    }

    private static boolean isMoreSpecificMeta(AltMeta a, AltMeta b, int arity) {
        boolean strictlyBetter = false;
        for (int i = 0; i < arity; i++) {
            String da = paramDescAt(a, i);
            String db = paramDescAt(b, i);
            if (!descriptorAccepts(db, da)) {
                return false;
            }
            if (!descriptorAccepts(da, db)) {
                strictlyBetter = true;
            }
        }
        return strictlyBetter;
    }

    private static Class<?> resolveClass(String desc) {
        try {
            return Class.forName(desc);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private String candidatesSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < metas.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(metas[i].display);
        }
        return sb.toString();
    }

    private static String runtimeArgNames(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : Types.describe(args[i]));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "overload (" + candidatesSummary() + ")";
    }
}
