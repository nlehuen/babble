package org.babblelang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime representation of a Babble {@code overload (...)} set (BIP021-D9, BIP021-D16).
 *
 * <p>Holds the flattened array of {@link Callable} alternatives along with per-alternative
 * signature metadata so compiled code can reach statically resolved alternatives via
 * {@link #member(int)}, calls through set types can select by signature via {@link #select(String)},
 * and untyped/reflective calls can fall back to {@link #call(Object...)}.</p>
 */
public final class OverloadSet implements Callable {

    /** Metadata for one alternative in an {@link OverloadSet}. */
    public static final class AltMeta {
        public final String signatureKey;
        public final String display;
        public final int minArity;
        public final int maxArity;
        public final int restIndex;
        public final String[] paramDescriptors;

        public AltMeta(String signatureKey, String display, int minArity, int maxArity,
                       int restIndex, String[] paramDescriptors) {
            this.signatureKey = signatureKey;
            this.display = display;
            this.minArity = minArity;
            this.maxArity = maxArity;
            this.restIndex = restIndex;
            this.paramDescriptors = paramDescriptors != null ? paramDescriptors : new String[0];
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
    public static OverloadSet create(Object[] operands, String[] sigKeys, String[] displays,
                                     int[] minArities, int[] maxArities, int[] restIndices,
                                     String[][] paramDescriptors) {
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
            } else if (op instanceof Callable c) {
                flatCallables.add(c);
                String sig = metaIdx < sigKeys.length ? sigKeys[metaIdx] : "";
                String disp = metaIdx < displays.length ? displays[metaIdx] : "fn (...)";
                int min = metaIdx < minArities.length ? minArities[metaIdx] : 0;
                int max = metaIdx < maxArities.length ? maxArities[metaIdx] : 0;
                int rest = metaIdx < restIndices.length ? restIndices[metaIdx] : -1;
                String[] descs = metaIdx < paramDescriptors.length ? paramDescriptors[metaIdx] : new String[0];
                flatMetas.add(new AltMeta(sig, disp, min, max, rest, descs));
                metaIdx++;
            } else {
                throw new BabbleException("Not a function : " + op);
            }
        }
        return new OverloadSet(
                flatCallables.toArray(Callable[]::new),
                flatMetas.toArray(AltMeta[]::new));
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
            } else if (op instanceof Callable c) {
                flatCallables.add(c);
                AltMeta m = metaIdx < metas.length && metas[metaIdx] != null
                        ? metas[metaIdx]
                        : new AltMeta("", "fn (...)", 0, Integer.MAX_VALUE, -1, new String[0]);
                flatMetas.add(m);
                metaIdx++;
            } else {
                throw new BabbleException("Not a function : " + op);
            }
        }
        return new OverloadSet(
                flatCallables.toArray(Callable[]::new),
                flatMetas.toArray(AltMeta[]::new));
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
        if (target instanceof Callable c) {
            return c;
        }
        throw new BabbleException("Not a function : " + target);
    }

    /**
     * Signature-keyed selection from an arbitrary callable target (returns {@code target} directly
     * when a single function satisfies an overload set type per BIP021-D12).
     */
    public static Callable selectFrom(Object target, String signatureKey) {
        if (target instanceof OverloadSet set) {
            return set.select(signatureKey);
        }
        if (target instanceof Callable c) {
            return c;
        }
        throw new BabbleException("Not a function : " + target);
    }

    /** Number of flattened alternatives in this set. */
    public int size() {
        return alternatives.length;
    }

    /**
     * Selects the runtime alternative matching {@code signatureKey} (exact match first, then
     * most-specific alternative accepting the target signature's parameter descriptors), cached
     * per signature key (BIP021-D12, BIP021-D16).
     */
    public Callable select(String signatureKey) {
        Callable cached = selectionCache.get(signatureKey);
        if (cached != null) {
            return cached;
        }
        Callable chosen = findBySignature(signatureKey);
        selectionCache.putIfAbsent(signatureKey, chosen);
        return chosen;
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
            if (!acceptsDescriptors(m, targetDescs)) {
                continue;
            }
            if (best < 0 || isMoreSpecificMeta(m, metas[best], targetDescs.length)) {
                best = i;
            }
        }
        if (best >= 0) {
            return alternatives[best];
        }
        throw new BabbleException("No applicable overload for (" + String.join(", ", targetDescs)
                + ") : candidates are " + candidatesSummary());
    }

    private static boolean acceptsDescriptors(AltMeta m, String[] targetDescs) {
        int n = targetDescs.length;
        if (n < m.minArity || n > m.maxArity) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            String paramDesc = paramDescAt(m, i);
            if (!descriptorAccepts(paramDesc, targetDescs[i])) {
                return false;
            }
        }
        return true;
    }

    private static String paramDescAt(AltMeta m, int index) {
        if (m.restIndex >= 0 && index >= m.restIndex) {
            return m.restIndex < m.paramDescriptors.length ? m.paramDescriptors[m.restIndex] : "";
        }
        return index < m.paramDescriptors.length ? m.paramDescriptors[index] : "";
    }

    /**
     * Dynamic fallback for untyped/reflective calls (BIP021-D16): resolves by runtime argument
     * classes under BIP021-D11.
     */
    @Override
    public Object call(ScriptRuntime runtime, Object[] arguments, String[] names, CallSite site) {
        int n = arguments != null ? arguments.length : 0;
        int best = -1;
        boolean ambiguous = false;
        for (int i = 0; i < metas.length; i++) {
            AltMeta m = metas[i];
            if (n < m.minArity || n > m.maxArity) {
                continue;
            }
            if (!matchesRuntimeArgs(m, arguments)) {
                continue;
            }
            if (best < 0) {
                best = i;
            } else {
                boolean iBetter = isMoreSpecificMeta(m, metas[best], n);
                boolean bestBetter = isMoreSpecificMeta(metas[best], m, n);
                if (iBetter && !bestBetter) {
                    best = i;
                    ambiguous = false;
                } else if (!bestBetter) {
                    ambiguous = true;
                }
            }
        }
        if (best < 0) {
            throw new BabbleException("No applicable overload for (" + runtimeArgNames(arguments)
                    + ") : candidates are " + candidatesSummary());
        }
        if (ambiguous) {
            throw new BabbleException("Ambiguous call for (" + runtimeArgNames(arguments)
                    + ") : candidates are " + candidatesSummary());
        }
        Callable target = alternatives[best];
        AltMeta meta = metas[best];
        if (meta.restIndex >= 0) {
            Object[] packed = packRestArgs(arguments, meta.restIndex);
            return target.call(runtime, packed, null, site);
        }
        return target.call(runtime, arguments, names, site);
    }

    private static Object[] packRestArgs(Object[] arguments, int restIndex) {
        Object[] packed = new Object[restIndex + 1];
        System.arraycopy(arguments, 0, packed, 0, restIndex);
        Object[] tail = Arrays.copyOfRange(arguments, restIndex, arguments.length);
        packed[restIndex] = Spreads.packedRest(ArgumentsView.of(tail));
        return packed;
    }

    private static boolean matchesRuntimeArgs(AltMeta m, Object[] arguments) {
        for (int i = 0; i < arguments.length; i++) {
            String desc = paramDescAt(m, i);
            if (!matchesValue(arguments[i], desc)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesValue(Object value, String desc) {
        if (desc == null || desc.isEmpty() || "any?".equals(desc)) {
            return true;
        }
        if ("any|any|".equals(desc) || "any".equals(desc)) {
            return value != null;
        }
        if ("null|null|".equals(desc) || "null".equals(desc)) {
            return value == null;
        }
        if (Types.matches(value, desc)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        // Numeric widening: byte < short < int < long < float < double
        int valRank = runtimeNumericRank(value);
        int targetRank = descriptorNumericRank(desc);
        return valRank >= 0 && targetRank >= 0 && valRank <= targetRank;
    }

    private static int runtimeNumericRank(Object value) {
        if (value instanceof Byte) return 0;
        if (value instanceof Short) return 1;
        if (value instanceof Integer) return 2;
        if (value instanceof Long) return 3;
        if (value instanceof Float) return 4;
        if (value instanceof Double) return 5;
        return -1;
    }

    private static String descriptorTag(String desc) {
        if (desc == null || desc.isEmpty()) {
            return "";
        }
        int bar = desc.indexOf('|');
        return bar >= 0 ? desc.substring(0, bar) : desc;
    }

    private static String descriptorPayload(String desc) {
        if (desc == null) {
            return "";
        }
        int first = desc.indexOf('|');
        if (first < 0) {
            return desc;
        }
        int second = desc.indexOf('|', first + 1);
        return second >= 0 ? desc.substring(second + 1) : "";
    }

    private static int descriptorNumericRank(String desc) {
        return switch (descriptorTag(desc)) {
            case "byte" -> 0;
            case "short" -> 1;
            case "int" -> 2;
            case "long" -> 3;
            case "float" -> 4;
            case "double" -> 5;
            default -> -1;
        };
    }

    private static boolean descriptorAccepts(String paramDesc, String argDesc) {
        if (paramDesc == null || paramDesc.isEmpty() || "any?".equals(paramDesc)) {
            return true;
        }
        if (paramDesc.equals(argDesc)) {
            return true;
        }
        String pTag = descriptorTag(paramDesc);
        String aTag = descriptorTag(argDesc);
        if ("any".equals(pTag)) {
            return !"null".equals(aTag) && !argDesc.isEmpty();
        }
        int pr = descriptorNumericRank(paramDesc);
        int ar = descriptorNumericRank(argDesc);
        if (pr >= 0 && ar >= 0) {
            return ar <= pr;
        }
        if ("java".equals(pTag) && "java".equals(aTag)) {
            Class<?> pc = resolveClass(descriptorPayload(paramDesc));
            Class<?> ac = resolveClass(descriptorPayload(argDesc));
            return pc != null && ac != null && pc.isAssignableFrom(ac);
        }
        if ("java".equals(pTag) && "string".equals(aTag)) {
            Class<?> pc = resolveClass(descriptorPayload(paramDesc));
            return pc != null && pc.isAssignableFrom(String.class);
        }
        return false;
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
        return switch (desc) {
            case "string" -> String.class;
            case "bool" -> Boolean.class;
            case "char" -> Character.class;
            case "list" -> java.util.List.class;
            case "set" -> java.util.Set.class;
            case "map" -> java.util.Map.class;
            default -> {
                try {
                    yield Class.forName(desc);
                } catch (ClassNotFoundException e) {
                    yield null;
                }
            }
        };
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
        if (args == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : Types.describe(args[i]));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("overload (");
        for (int i = 0; i < metas.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(metas[i].display);
        }
        sb.append(')');
        return sb.toString();
    }
}
