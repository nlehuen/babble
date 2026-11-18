package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the analysis of a file knows of a file it imports statically : its exported names,
 * and the interfaces it exports, for {@code implements}, {@code extends} and
 * {@code super(I)} (docs/design/bip005_modules.md §8.1).
 * <p>
 * An interface is complete once the file's {@link TypeChecks} have run. Before that, which
 * a file importing it back in a cycle observes, only its exported names are known.
 */
public final class ModuleInterface {
    private final Set<String> exports;
    private final Map<String, Integer> exportIndices;
    private final Set<ModuleInterface> dependencies = new LinkedHashSet<>();
    private String name = "<module>";
    private TypeChecks checks;
    private TypeChecker checker;
    private Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects = Map.of();
    private volatile ClassLoader compiledClassLoader;
    private volatile Map<String, String> interfaceJvmNames = Map.of();
    private volatile Map<String, String> classJvmNames = Map.of();
    private volatile Map<String, ScopeAnalysis.TypeHierarchyInfo> typeHierarchies = Map.of();

    ModuleInterface(BlockScopes pass) {
        String[] ordered = pass.analysis.exports();
        this.exports = Set.of(ordered);
        Map<String, Integer> indices = new HashMap<>(ordered.length);
        for (int i = 0; i < ordered.length; i++) {
            indices.put(ordered[i], i);
        }
        this.exportIndices = Map.copyOf(indices);
    }

    /** The file, as messages name it : the same name as the runtime module's. */
    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<String> exports() {
        return exports;
    }

    /** The declaration-order index of an export in {@link org.babblelang.runtime.Module#exportAt}, or -1. */
    public int exportIndex(String name) {
        return exportIndices.getOrDefault(name, -1);
    }

    void addDependency(ModuleInterface dependency) {
        if (dependency != null && dependency != this) {
            synchronized (dependencies) {
                dependencies.add(dependency);
            }
        }
    }

    public Set<ModuleInterface> dependencies() {
        synchronized (dependencies) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        }
    }

    public void setCompilation(ClassLoader classLoader, Map<String, String> interfaceJvmNames) {
        setCompilation(classLoader, interfaceJvmNames, Map.of());
    }

    public void setCompilation(ClassLoader classLoader, Map<String, String> interfaceJvmNames, Map<String, String> classJvmNames) {
        this.compiledClassLoader = classLoader;
        this.interfaceJvmNames = interfaceJvmNames != null ? Map.copyOf(interfaceJvmNames) : Map.of();
        this.classJvmNames = classJvmNames != null ? Map.copyOf(classJvmNames) : Map.of();
    }

    public ClassLoader compiledClassLoader() {
        return compiledClassLoader;
    }

    public Map<String, String> interfaceJvmNames() {
        return interfaceJvmNames;
    }

    public Map<String, String> classJvmNames() {
        return classJvmNames;
    }

    void setTypeHierarchies(Map<String, ScopeAnalysis.TypeHierarchyInfo> hierarchies) {
        this.typeHierarchies = hierarchies != null ? Map.copyOf(hierarchies) : Map.of();
    }

    public Map<String, ScopeAnalysis.TypeHierarchyInfo> typeHierarchies() {
        return typeHierarchies;
    }

    public ScopeAnalysis.TypeHierarchyInfo typeHierarchy(String id) {
        return id != null ? typeHierarchies.get(id) : null;
    }

    void complete(TypeChecks checks) {
        this.checks = checks;
    }

    /** The file's checker, once it ran, which types its exports. */
    void typed(TypeChecker checker) {
        this.checker = checker;
        this.exportedJavaEffects = checker.exportedJavaEffects();
    }

    /** The file's type checker, or null if the file is still being analysed (a cycle). */
    TypeChecker checker() {
        return checker;
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects() {
        return exportedJavaEffects;
    }

    /**
     * Resolves {@code path[index…]}, a path through the file's exports, to an interface : a
     * TypeNode or a Java interface class.
     */
    Object resolveType(List<String> path, int index, BabbleParser.TypeContext type, boolean forInterface) {
        if (checks == null) {
            throw new AnalysisException("implements needs a statically known interface : " + name
                    + " is still being analysed (an import cycle)", type.getStart().getLine());
        }
        return checks.resolveExport(path, index, type, forInterface);
    }

    /** The module an export refers to, when it is one statically : {@code export def m = import("m.ba")}. */
    ModuleInterface exportedModule(String export, int depth) {
        return checks == null ? null : checks.exportedModule(export, depth);
    }
}
