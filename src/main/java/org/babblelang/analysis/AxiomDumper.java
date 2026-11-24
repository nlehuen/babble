package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Formats and dumps what the compiler assumes (curated or inferred) about a Java class or every
 * public class of a Java package in canonical §3.4 {@code curated.ba} syntax
 * ({@code docs/design/bip019_refactoring-axioms.md} §5.1).
 */
public final class AxiomDumper {

    private AxiomDumper() {
    }

    /**
     * Resolves {@code target} as either a single Java class or a Java package name, returning
     * the sorted list of classes (by binary name), or an empty list if {@code target} is neither.
     */
    public static List<Class<?>> resolveTarget(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        String trimmed = target.trim();
        Class<?> single = JavaTypeBridge.loadClass(trimmed);
        if (single != null) {
            return List.of(single);
        }
        return listPublicPackageClasses(trimmed);
    }

    /** Dumps axioms for {@code target} (class or package) to {@code out}. Returns {@code false} if unknown. */
    public static boolean dumpTarget(String target, BuiltinAxioms axioms, PrintWriter out) {
        List<Class<?>> classes = resolveTarget(target);
        if (classes.isEmpty()) {
            return false;
        }
        BuiltinAxioms effective = axioms != null ? axioms : BuiltinAxioms.standard();
        for (int i = 0; i < classes.size(); i++) {
            if (i > 0) {
                out.println();
            }
            out.print(dumpClass(classes.get(i), effective));
        }
        out.flush();
        return true;
    }

    /** Dumps axioms for a comma-separated list of classes/packages (for {@code -Dbabble.dumpAxioms=...}). */
    public static void dumpTargets(String spec, BuiltinAxioms axioms, PrintWriter err) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        boolean first = true;
        for (String part : spec.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            List<Class<?>> classes = resolveTarget(t);
            for (Class<?> cls : classes) {
                if (!first) err.println();
                first = false;
                err.print(dumpClass(cls, axioms != null ? axioms : BuiltinAxioms.standard()));
            }
        }
        err.flush();
    }

    /** Renders what Babble assumes about {@code cls} in canonical §3.4 {@code curated.ba} syntax. */
    public static String dumpClass(Class<?> cls, BuiltinAxioms axioms) {
        BuiltinAxioms effective = axioms != null ? axioms : BuiltinAxioms.standard();
        StringBuilder sb = new StringBuilder();
        sb.append("axiom ").append(cls.getName().replace('$', '.'));
        TypeVariable<?>[] typeParams = cls.getTypeParameters();
        if (typeParams.length == 1) {
            sb.append(" of ").append(typeParams[0].getName());
        } else if (typeParams.length > 1) {
            sb.append(" of (");
            for (int i = 0; i < typeParams.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeParams[i].getName());
            }
            sb.append(")");
        }
        sb.append(": class");
        if (effective.nullMarked(cls)) {
            sb.append(" notnull");
        }
        if (effective.isDeeplyImmutable(cls)) {
            sb.append(" immutable");
        }
        sb.append(" (\n");

        List<Method> instanceMethods = new ArrayList<>();
        List<Method> staticMethods = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers())) {
                staticMethods.add(m);
            } else {
                instanceMethods.add(m);
            }
        }
        List<Constructor<?>> ctors = new ArrayList<>();
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) && !c.isSynthetic()) {
                ctors.add(c);
            }
        }

        Comparator<Executable> execOrder = (a, b) -> {
            String na = a instanceof Constructor<?> ? "new" : a.getName();
            String nb = b instanceof Constructor<?> ? "new" : b.getName();
            List<String> pa = paramDescriptors(a);
            List<String> pb = paramDescriptors(b);
            return compareMemberOrder(na, pa, nb, pb);
        };
        instanceMethods.sort(execOrder);
        ctors.sort(execOrder);
        staticMethods.sort(execOrder);

        for (Method m : instanceMethods) {
            appendMember(sb, cls, m, effective);
        }
        sb.append(")");

        if (!ctors.isEmpty() || !staticMethods.isEmpty()) {
            sb.append(" static (\n");
            for (Constructor<?> c : ctors) {
                appendMember(sb, cls, c, effective);
            }
            for (Method m : staticMethods) {
                appendMember(sb, cls, m, effective);
            }
            sb.append(")");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static void appendMember(StringBuilder sb, Class<?> cls, Executable exec, BuiltinAxioms axioms) {
        boolean isCtor = exec instanceof Constructor<?>;
        String name = isCtor ? "new" : exec.getName();
        AxiomData.MemberAxiom curated = isCtor
                ? axioms.constructorAxiom(cls, (Constructor<?>) exec)
                : axioms.methodAxiom(cls, name, (Method) exec);

        BytecodeEffectAnalyzer.Result inferred = BytecodeEffectAnalyzer.analyzeRaw(exec, axioms);
        String comment;
        EffectSet eff;
        boolean mutRecv;
        boolean borrowRecv;
        boolean[] mutParams;
        boolean[] borrowParams;
        boolean view;
        boolean shallow;
        boolean fresh;
        boolean foldable;
        JavaNullability.Nullness[] paramNulls;
        JavaNullability.Nullness retNull;

        if (curated != null && curated.inferredEffects()) {
            boolean same = BytecodeEffectAnalyzer.isAtLeastAsStrongAs(inferred, curated, exec);
            if (same) {
                comment = ";; curated, inferred the same";
            } else if (inferred.reasons().isEmpty()) {
                comment = ";; curated nullness, inferred effects";
            } else {
                comment = ";; curated nullness, inferred effects (" + String.join(", ", inferred.reasons()) + ")";
            }
            EffectChecker.JavaCallProfile prof = inferred.toProfile(exec);
            eff = prof.effects();
            mutRecv = prof.mutReceiver();
            borrowRecv = prof.borrowReceiver();
            mutParams = prof.mutParams();
            borrowParams = prof.borrowParams();
            view = prof.view();
            shallow = prof.shallow();
            fresh = prof.fresh();
            foldable = false;
            paramNulls = curated.hasSignature() ? curated.paramNullness() : null;
            retNull = curated.returnNullness() != null
                    ? curated.returnNullness()
                    : (inferred.returnNonNull() ? JavaNullability.Nullness.NON_NULL : JavaNullability.Nullness.NULLABLE);
        } else if (curated != null) {
            boolean same = BytecodeEffectAnalyzer.isAtLeastAsStrongAs(inferred, curated, exec);
            comment = same ? ";; curated, inferred the same" : ";; curated";
            EffectChecker.JavaCallProfile prof = curated.toProfile(exec);
            eff = prof.effects();
            mutRecv = prof.mutReceiver();
            borrowRecv = prof.borrowReceiver();
            mutParams = prof.mutParams();
            borrowParams = prof.borrowParams();
            view = prof.view();
            shallow = prof.shallow();
            fresh = prof.fresh();
            foldable = curated.foldable();
            paramNulls = curated.hasSignature() ? curated.paramNullness() : null;
            retNull = curated.returnNullness() != null
                    ? curated.returnNullness()
                    : (inferred.returnNonNull() ? JavaNullability.Nullness.NON_NULL : JavaNullability.Nullness.NULLABLE);
        } else {
            if (inferred.reasons().isEmpty()) {
                comment = ";; inferred";
            } else {
                comment = ";; inferred (" + String.join(", ", inferred.reasons()) + ")";
            }
            EffectChecker.JavaCallProfile prof = inferred.toProfile(exec);
            eff = prof.effects();
            mutRecv = prof.mutReceiver();
            borrowRecv = prof.borrowReceiver();
            mutParams = prof.mutParams();
            borrowParams = prof.borrowParams();
            view = prof.view();
            shallow = prof.shallow();
            fresh = prof.fresh();
            foldable = false;
            paramNulls = null;
            retNull = inferred.returnNonNull() ? JavaNullability.Nullness.NON_NULL : JavaNullability.Nullness.NULLABLE;
        }

        sb.append("    ").append(comment).append("\n");
        sb.append("    def ").append(name).append(": fn");

        // Effect & member words in canonical order (§3.4)
        List<String> words = new ArrayList<>();
        if (eff.isIO() || eff.isUnconstrained()) {
            words.add("io");
        } else {
            if (eff.isVolatile()) {
                words.add("volatile");
            }
            if (mutRecv) {
                words.add("mut");
            }
            boolean isStatic = isCtor || Modifier.isStatic(exec.getModifiers());
            if (!isStatic && !borrowRecv) {
                words.add("shared");
            }
            if (eff.isNothrow()) {
                words.add("nothrow");
            }
            for (Effect e : List.of(Effect.IDEMPOTENT, Effect.COMMUTATIVE, Effect.ASSOCIATIVE, Effect.MONOTONE, Effect.INJECTIVE)) {
                if (eff.has(e)) {
                    words.add(e.name().toLowerCase());
                }
            }
            if (view) words.add("view");
            if (shallow) words.add("shallow");
            if (fresh) words.add("fresh");
            if (foldable) words.add("foldable");
        }
        for (String w : words) {
            sb.append(' ').append(w);
        }

        // Signature
        sb.append(" (");
        Class<?>[] pTypes = exec.getParameterTypes();
        for (int i = 0; i < pTypes.length; i++) {
            if (i > 0) sb.append(", ");
            boolean pMut = i < mutParams.length && mutParams[i];
            boolean pBorrow = i < borrowParams.length && borrowParams[i];
            if (pMut) sb.append("mut ");
            if (!eff.isIO() && !eff.isUnconstrained() && !pBorrow && !pTypes[i].isPrimitive()) {
                sb.append("shared ");
            }
            sb.append("p").append(i).append(": ");
            JavaNullability.Nullness pn = (paramNulls != null && i < paramNulls.length) ? paramNulls[i] : null;
            sb.append(formatJavaType(pTypes[i], pn));
        }
        sb.append(")");
        if (!isCtor) {
            Class<?> rt = ((Method) exec).getReturnType();
            sb.append(": ").append(formatJavaType(rt, retNull));
        }
        sb.append("\n");
    }

    private static String formatJavaType(Class<?> cls, JavaNullability.Nullness nullness) {
        if (cls == void.class) return "void";
        if (cls == byte.class) return "byte";
        if (cls == short.class) return "short";
        if (cls == int.class) return "int";
        if (cls == long.class) return "long";
        if (cls == float.class) return "float";
        if (cls == double.class) return "double";
        if (cls == boolean.class) return "bool";
        if (cls == char.class) return "char";
        boolean nullable = nullness == JavaNullability.Nullness.NULLABLE || nullness == JavaNullability.Nullness.UNSPECIFIED || nullness == null;
        if (cls.isArray()) {
            String inner = "array of " + formatJavaType(cls.getComponentType(), JavaNullability.Nullness.NULLABLE);
            return nullable ? "(" + inner + ")?" : "(" + inner + ")!";
        }
        String base;
        if (cls == String.class) {
            base = "string";
        } else if (cls == Object.class) {
            base = "any";
        } else {
            base = cls.getName().replace('$', '.');
            int tpCount = cls.getTypeParameters().length;
            if (tpCount == 1) {
                base = base + " of any?";
            } else if (tpCount > 1) {
                base = base + " of (" + String.join(", ", Collections.nCopies(tpCount, "any?")) + ")";
            }
        }
        if (nullable) {
            return base.contains(" of ") ? "(" + base + ")?" : base + "?";
        }
        return base.contains(" of ") ? "(" + base + ")!" : base + "!";
    }

    private static List<String> paramDescriptors(Executable exec) {
        Class<?>[] pts = exec.getParameterTypes();
        List<String> out = new ArrayList<>(pts.length);
        for (Class<?> pt : pts) {
            out.add(Type.getDescriptor(pt));
        }
        return out;
    }

    /** Discovers all public classes in {@code packageName} on the JDK jrt filesystem and classpath. */
    public static List<Class<?>> listPublicPackageClasses(String packageName) {
        String pathRel = packageName.replace('.', '/');
        Set<String> classNames = new TreeSet<>();
        // 1. Scan jrt:/ modules (java.base and all JDK modules).
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modulesDir = jrt.getPath("/modules");
            if (Files.isDirectory(modulesDir)) {
                try (Stream<Path> mods = Files.list(modulesDir)) {
                    for (Path mod : mods.toList()) {
                        Path pkgDir = mod.resolve(pathRel);
                        if (Files.isDirectory(pkgDir)) {
                            try (Stream<Path> files = Files.list(pkgDir)) {
                                for (Path f : files.toList()) {
                                    String fn = f.getFileName().toString();
                                    if (fn.endsWith(".class") && !fn.equals("package-info.class") && !fn.equals("module-info.class")) {
                                        classNames.add(packageName + "." + fn.substring(0, fn.length() - 6));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. Scan classpath directories & jars.
        try {
            ClassLoader cl = AxiomDumper.class.getClassLoader();
            Enumeration<URL> urls = cl.getResources(pathRel);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        try (Stream<Path> files = Files.list(dir)) {
                            for (Path f : files.toList()) {
                                String fn = f.getFileName().toString();
                                if (fn.endsWith(".class") && !fn.equals("package-info.class") && !fn.equals("module-info.class")) {
                                    classNames.add(packageName + "." + fn.substring(0, fn.length() - 6));
                                }
                            }
                        }
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    JarURLConnection conn = (JarURLConnection) url.openConnection();
                    try (JarFile jar = conn.getJarFile()) {
                        String prefix = pathRel + "/";
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(prefix) && name.endsWith(".class")) {
                                String tail = name.substring(prefix.length());
                                if (!tail.contains("/") && !tail.equals("package-info.class") && !tail.equals("module-info.class")) {
                                    classNames.add(packageName + "." + tail.substring(0, tail.length() - 6));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        List<Class<?>> result = new ArrayList<>();
        for (String binary : classNames) {
            try {
                Class<?> c = Class.forName(binary, false, AxiomDumper.class.getClassLoader());
                if (Modifier.isPublic(c.getModifiers())) {
                    result.add(c);
                }
            } catch (Throwable ignored) {
            }
        }
        result.sort(Comparator.comparing(Class::getName));
        return result;
    }

    // ================================================================ Canonical Order & Curated Validation (§3.4, §7)

    /** Checks that an axiom source obeys the BIP019 §3.4 canonical order; returns violations (empty if valid). */
    public static List<String> checkCanonicalOrder(String source, String sourceName) {
        return checkCanonicalOrder(source, sourceName, null);
    }

    /** Checks canonical order and (if non-null) that every class belongs to {@code expectedPackage}. */
    public static List<String> checkCanonicalOrder(String source, String sourceName, String expectedPackage) {
        try {
            BabbleParser.FileContext file = BabbleScriptEngine.parseSource(source, sourceName);
            return checkCanonicalOrder(file, expectedPackage);
        } catch (javax.script.ScriptException e) {
            return List.of("Failed to parse " + sourceName + " : " + e.getMessage());
        }
    }

    private static List<String> checkCanonicalOrder(BabbleParser.FileContext file, String expectedPackage) {
        List<String> violations = new ArrayList<>();
        String prevClassBinary = null;
        boolean prevWasClassAxiom = false;
        String prevMemberName = null;
        List<String> prevMemberParams = null;

        for (BabbleParser.ExpressionContext expr : file.expression()) {
            if (!(expr instanceof BabbleParser.AxiomExpressionContext ax)) {
                violations.add("Non-axiom expression in curated.ba at line " + expr.getStart().getLine());
                continue;
            }
            List<String> path = AxiomData.targetPathOf(ax.target);
            boolean isClass = ax.axiomType().CLASS() != null;
            List<String> clsPath = isClass ? path : path.subList(0, path.size() - 1);
            Class<?> cls = JavaTypeBridge.loadClass(String.join(".", clsPath));
            if (cls == null) {
                violations.add("Unknown class " + String.join(".", clsPath) + " at line " + ax.getStart().getLine());
                continue;
            }
            if (expectedPackage != null && !expectedPackage.equals(cls.getPackageName())) {
                violations.add("Class " + cls.getName() + " does not belong to package " + expectedPackage);
            }
            String binary = cls.getName();
            String memberName = isClass ? null : path.get(path.size() - 1);
            List<String> memberParams = List.of();
            if (!isClass) {
                List<String> errs = new ArrayList<>();
                AxiomData.ParsedAxiom parsed = AxiomData.parse(ax, null, true, (m, at) -> errs.add(m));
                if (parsed != null && parsed.memberAxiom() != null) {
                    memberParams = parsed.memberAxiom().paramDescriptors();
                }
            }

            if (prevClassBinary != null) {
                int cmp = binary.compareTo(prevClassBinary);
                if (cmp < 0) {
                    violations.add("Axiom for " + binary + " must appear before " + prevClassBinary);
                } else if (cmp == 0) {
                    if (isClass && !prevWasClassAxiom) {
                        violations.add("Class axiom for " + binary + " must appear before its member axioms");
                    } else if (!isClass && !prevWasClassAxiom) {
                        int mCmp = compareMemberOrder(memberName, memberParams, prevMemberName, prevMemberParams);
                        if (mCmp < 0) {
                            violations.add("Member axiom " + binary + "." + memberName + " is out of order after " + prevMemberName);
                        }
                    }
                }
            }
            prevClassBinary = binary;
            prevWasClassAxiom = isClass;
            prevMemberName = memberName;
            prevMemberParams = memberParams;

            if (isClass) {
                checkClassWordOrder(ax.axiomType(), binary, violations);
                List<BabbleParser.AxiomMembersContext> sections = ax.axiomType().axiomMembers();
                Map<String, String> classTypeVars = AxiomData.buildClassTypeVars(ax.typeParameters(), cls, (m, at) -> {});
                for (BabbleParser.AxiomMembersContext sec : sections) {
                    checkMemberSectionOrder(sec, cls, classTypeVars, violations);
                }
            } else {
                checkEffectWordOrder(ax.axiomType(), binary + "." + memberName, violations);
            }
        }
        return violations;
    }

    private static void checkClassWordOrder(BabbleParser.AxiomTypeContext typeCtx, String binary, List<String> violations) {
        int prevRank = -1;
        for (org.antlr.v4.runtime.Token w : typeCtx.classWords) {
            int rank = AxiomData.CLASS_WORD_ORDER.indexOf(w.getText());
            if (rank < 0) {
                violations.add("Unknown class word '" + w.getText() + "' on " + binary);
            } else if (rank <= prevRank) {
                violations.add("Class word '" + w.getText() + "' out of canonical order on " + binary);
            } else {
                prevRank = rank;
            }
        }
    }

    private static void checkEffectWordOrder(BabbleParser.AxiomTypeContext typeCtx, String label, List<String> violations) {
        BabbleParser.AxiomEffectPrefixContext prefix = typeCtx.axiomEffectPrefix();
        if (prefix == null) {
            return;
        }
        int prevRank = -1;
        for (BabbleParser.AxiomEffectWordContext w : prefix.axiomEffectWord()) {
            int rank = AxiomData.EFFECT_WORD_ORDER.indexOf(w.getText());
            if (rank < 0) {
                violations.add("Unknown effect word '" + w.getText() + "' on " + label);
            } else if (rank <= prevRank) {
                violations.add("Effect word '" + w.getText() + "' out of canonical order on " + label);
            } else {
                prevRank = rank;
            }
        }
    }

    private static void checkMemberSectionOrder(BabbleParser.AxiomMembersContext section,
                                                Class<?> cls,
                                                Map<String, String> classTypeVars,
                                                List<String> violations) {
        String prevName = null;
        List<String> prevParams = null;
        boolean prevHasSig = false;
        for (BabbleParser.AxiomMemberContext mCtx : section.axiomMember()) {
            String name = mCtx.memberName() != null ? mCtx.memberName().getText() : "*";
            checkEffectWordOrder(mCtx.axiomType(), cls.getName() + "." + name, violations);
            AxiomData.MemberAxiom parsed = AxiomData.parseFnAxiom(mCtx.axiomType(), cls, name,
                    AxiomData.MemberKind.ANY, false, classTypeVars, null, true, (m, at) -> {});
            boolean hasSig = parsed != null && parsed.hasSignature();
            List<String> params = parsed != null ? parsed.paramDescriptors() : List.of();
            if (prevName != null) {
                int cmp = compareMemberOrder(name, params, prevName, prevParams);
                if (cmp < 0 || (cmp == 0 && !hasSig && prevHasSig)) {
                    violations.add("Member " + cls.getName() + "." + name + params + " out of canonical order after " + prevName + prevParams);
                }
            }
            prevName = name;
            prevParams = params;
            prevHasSig = hasSig;
        }
    }

    static int compareMemberOrder(String nameA, List<String> paramsA, String nameB, List<String> paramsB) {
        int rA = memberNameRank(nameA);
        int rB = memberNameRank(nameB);
        if (rA != rB) {
            return Integer.compare(rA, rB);
        }
        int nameCmp = nameA.compareTo(nameB);
        if (nameCmp != 0) {
            return nameCmp;
        }
        int min = Math.min(paramsA.size(), paramsB.size());
        for (int i = 0; i < min; i++) {
            int c = paramsA.get(i).compareTo(paramsB.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(paramsA.size(), paramsB.size());
    }

    private static int memberNameRank(String name) {
        if ("*".equals(name)) return 0;
        if ("new".equals(name)) return 1;
        return 2;
    }

    /**
     * Validates every curated member axiom in {@code packageName}'s {@code curated.ba} against the
     * actual public Java methods and constructors of its target class ({@code BIP019} §7).
     */
    public static List<String> validateCuratedPackage(BuiltinAxioms axioms, String packageName) {
        AxiomData.AxiomTable table = axioms.loadPackage(packageName);
        List<String> errors = new ArrayList<>();
        for (Map.Entry<Class<?>, List<AxiomData.MemberAxiom>> entry : table.membersByOwner().entrySet()) {
            Class<?> cls = entry.getKey();
            for (AxiomData.MemberAxiom m : entry.getValue()) {
                if ("*".equals(m.name())) {
                    continue;
                }
                if (m.isConstructor()) {
                    boolean matched = false;
                    for (Constructor<?> c : cls.getConstructors()) {
                        if (m.matches(c)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        errors.add("Curated constructor axiom matches no public constructor : "
                                + cls.getName() + ".new" + (m.hasSignature() ? m.paramDescriptors() : ""));
                    }
                } else {
                    List<Method> matchedMethods = new ArrayList<>();
                    for (Method jm : cls.getMethods()) {
                        if (m.matches(jm)) {
                            matchedMethods.add(jm);
                        }
                    }
                    if (matchedMethods.isEmpty() && cls.isInterface()) {
                        for (Method om : Object.class.getMethods()) {
                            if (m.matches(om)) {
                                matchedMethods.add(om);
                            }
                        }
                    }
                    if (matchedMethods.isEmpty()) {
                        errors.add("Curated method axiom matches no public method : "
                                + cls.getName() + "." + m.name() + (m.hasSignature() ? m.paramDescriptors() : ""));
                    } else if (!m.hasSignature() && m.returnDescriptor() != null) {
                        for (Method jm : matchedMethods) {
                            if (jm.isBridge() || jm.isSynthetic()) {
                                continue;
                            }
                            String actualRet = Type.getDescriptor(jm.getReturnType());
                            if (!m.returnDescriptor().equals(actualRet)) {
                                errors.add("Curated return-only axiom return type mismatch on "
                                        + cls.getName() + "." + m.name() + Type.getMethodDescriptor(jm)
                                        + " : expected " + m.returnDescriptor() + ", actual " + actualRet);
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }

    /**
     * Lists all Java package names that have a {@code curated.ba} resource under
     * {@code org/babblelang/axioms/} on the classpath (directory or jar, §2.1).
     */
    public static Set<String> discoverCuratedPackages() throws IOException {
        Set<String> packages = new TreeSet<>();
        ClassLoader cl = BuiltinAxioms.class.getClassLoader();
        Enumeration<URL> roots = cl.getResources(BuiltinAxioms.AXIOMS_RESOURCE_ROOT);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if ("file".equals(url.getProtocol())) {
                try {
                    Path dir = Path.of(url.toURI());
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(p -> p.getFileName().toString().equals("curated.ba")).forEach(p -> {
                            Path rel = dir.relativize(p.getParent());
                            String pkg = rel.toString().replace('\\', '/').replace('/', '.');
                            packages.add(pkg);
                        });
                    }
                } catch (Exception e) {
                    throw new IOException("Failed to scan axioms directory " + url, e);
                }
            } else if ("jar".equals(url.getProtocol())) {
                JarURLConnection conn = (JarURLConnection) url.openConnection();
                try (JarFile jar = conn.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(BuiltinAxioms.AXIOMS_RESOURCE_ROOT) && name.endsWith("/curated.ba")) {
                            String rel = name.substring(BuiltinAxioms.AXIOMS_RESOURCE_ROOT.length(),
                                    name.length() - "/curated.ba".length());
                            packages.add(rel.replace('/', '.'));
                        }
                    }
                }
            }
        }
        return java.util.Collections.unmodifiableSet(packages);
    }

    private static final class Collections {
        static <T> List<T> nCopies(int n, T val) {
            return java.util.Collections.nCopies(n, val);
        }
    }
}
