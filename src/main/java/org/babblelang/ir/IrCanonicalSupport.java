package org.babblelang.ir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;

/**
 * Shared {@link IrTarget} resolution and canonical text-normalization helpers used by both {@code
 * HirCanonicalPrinter} and {@code MirCanonicalPrinter}.
 */
public final class IrCanonicalSupport {
    public static final Pattern SCRIPT_PREFIX = Pattern.compile("(?:babble/)?Script\\$\\d+\\$");
    public static final Pattern SCRIPT_SELF = Pattern.compile("(?:babble/)?Script\\$\\d+");
    public static final Pattern BABBLE_TYPE_ID =
            Pattern.compile("(babble(?:\\||%7C)[^|,%]+)(?:\\||%7C)T\\d+");

    private IrCanonicalSupport() {}

    // ------------------------------------------------------------------ target resolution

    public static HirFunction resolveFunction(HirModule module, String name, String tierLabel) {
        List<HirFunction> matches = new ArrayList<>();
        for (HirFunction fn : module.functions().values()) {
            if (fn.ownerClassId() < 0 && name.equals(fn.name())) {
                matches.add(fn);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "No "
                            + tierLabel
                            + " function named '"
                            + name
                            + "' (available: "
                            + availableTargets(module)
                            + ")");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous "
                            + tierLabel
                            + " function '"
                            + name
                            + "' ("
                            + matches.size()
                            + " matches; use a unique name in the case)");
        }
        return matches.get(0);
    }

    public static List<HirFunction> resolveMethods(
            HirModule module, String ownerName, String methodName, String tierLabel) {
        HirClass cls = findClassByName(module, ownerName, tierLabel);
        if (cls != null) {
            Set<Integer> ids = new LinkedHashSet<>();
            Integer single = cls.methodFunctionIds().get(methodName);
            if (single != null) {
                ids.add(single);
            }
            List<Integer> ovs = cls.overloadMethodFunctionIds().get(methodName);
            if (ovs != null) {
                ids.addAll(ovs);
            }
            for (HirFunction fn : module.functions().values()) {
                if (fn.ownerClassId() == cls.id()
                        && (methodName.equals(fn.memberName()) || methodName.equals(fn.name()))) {
                    ids.add(fn.id());
                }
            }
            List<HirFunction> result = new ArrayList<>();
            for (int id : ids) {
                HirFunction fn = module.function(id);
                if (fn != null) {
                    result.add(fn);
                }
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException(
                        "No "
                                + tierLabel
                                + " method '"
                                + ownerName
                                + "."
                                + methodName
                                + "' on "
                                + typeKindLabel(cls)
                                + " '"
                                + ownerName
                                + "'");
            }
            return result;
        }
        HirInterface iface = findInterfaceByName(module, ownerName, tierLabel);
        if (iface != null) {
            Set<Integer> ids = new LinkedHashSet<>();
            Integer single = iface.defaultFunctionIds().get(methodName);
            if (single != null) {
                ids.add(single);
            }
            List<Integer> ovs = iface.defaultOverloadFunctionIds().get(methodName);
            if (ovs != null) {
                ids.addAll(ovs);
            }
            List<HirFunction> result = new ArrayList<>();
            for (int id : ids) {
                HirFunction fn = module.function(id);
                if (fn != null) {
                    result.add(fn);
                }
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException(
                        "No "
                                + tierLabel
                                + " default method '"
                                + ownerName
                                + "."
                                + methodName
                                + "' on interface '"
                                + ownerName
                                + "'");
            }
            return result;
        }
        throw new IllegalArgumentException(
                "No "
                        + tierLabel
                        + " type named '"
                        + ownerName
                        + "' for method '"
                        + methodName
                        + "' (available: "
                        + availableTargets(module)
                        + ")");
    }

    public static HirClass findClassByName(HirModule module, String name, String tierLabel) {
        List<HirClass> matches = new ArrayList<>();
        for (HirClass cls : module.classes().values()) {
            if (name.equals(cls.shape().name())) {
                matches.add(cls);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous "
                            + tierLabel
                            + " class/record '"
                            + name
                            + "' ("
                            + matches.size()
                            + " matches)");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    public static HirInterface findInterfaceByName(
            HirModule module, String name, String tierLabel) {
        List<HirInterface> matches = new ArrayList<>();
        for (HirInterface iface : module.interfaces().values()) {
            if (name.equals(iface.shape().name())) {
                matches.add(iface);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Ambiguous "
                            + tierLabel
                            + " interface '"
                            + name
                            + "' ("
                            + matches.size()
                            + " matches)");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    public static String availableTargets(HirModule module) {
        List<String> names = new ArrayList<>();
        for (HirFunction fn : module.functions().values()) {
            if (fn.ownerClassId() < 0 && fn.name() != null && !fn.name().isEmpty()) {
                names.add("fn=" + fn.name());
            }
        }
        for (HirClass cls : module.classes().values()) {
            names.add(typeKindLabel(cls) + "=" + cls.shape().name());
            for (String m : cls.methodFunctionIds().keySet()) {
                names.add("fn=" + cls.shape().name() + "." + m);
            }
        }
        for (HirInterface iface : module.interfaces().values()) {
            names.add("interface=" + iface.shape().name());
        }
        return names.isEmpty() ? "<none>" : String.join(", ", names);
    }

    // ------------------------------------------------------------------ formatting & sanitization

    public static String moduleLabel(HirModule module) {
        String label = module.sourceName();
        if (label == null
                || label.isBlank()
                || "<unknown>".equals(label)
                || "<script>".equals(label)
                || "<input>".equals(label)
                || SCRIPT_SELF.matcher(label).matches()) {
            return "<main>";
        }
        return label;
    }

    public static String typeKindLabel(HirClass cls) {
        if (cls.isObject()) {
            return "object";
        }
        return cls.shape().isValue() ? "record" : "class";
    }

    public static String shortJvmType(String jvmType) {
        if (jvmType == null) {
            return "*";
        }
        String cleaned = SCRIPT_PREFIX.matcher(jvmType).replaceAll("");
        int slash = cleaned.lastIndexOf('/');
        return slash >= 0 ? cleaned.substring(slash + 1) : cleaned;
    }

    public static String sanitizeDescriptor(String desc) {
        if (desc == null) {
            return "any";
        }
        String s = SCRIPT_PREFIX.matcher(desc).replaceAll("");
        return BABBLE_TYPE_ID.matcher(s).replaceAll("$1");
    }

    public static String escapeString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }
}
