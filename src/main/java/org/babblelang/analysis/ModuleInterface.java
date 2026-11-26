package org.babblelang.analysis;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.parser.BabbleParser;

/**
 * What the analysis of a file knows of a file it imports statically : its exported names, and the
 * interfaces it exports, for {@code implements}, {@code extends} and {@code super(I)}
 * (docs/design/bip005_modules.md §8.1).
 *
 * <p>An interface is complete once the file's {@link TypeChecks} have run. Before that, which a
 * file importing it back in a cycle observes, only its exported names are known.
 */
public final class ModuleInterface {
    private final Set<String> exports;
    private final Map<String, Integer> exportIndices;
    private final Set<ModuleInterface> dependencies = new LinkedHashSet<>();
    private final String moduleKey;
    private String name = "<module>";
    private TypeChecks checks;
    private TypedModuleSummary summary;
    private volatile boolean hasDynamicImport;
    private volatile ClassLoader compiledClassLoader;
    private volatile Map<String, String> interfaceJvmNames = Map.of();
    private volatile Map<String, String> classJvmNames = Map.of();
    private volatile Map<String, LoweringTargets.ExportedFunctionTarget> exportedFunctions =
            Map.of();
    private volatile Map<String, LoweringTargets.TypeHierarchyInfo> typeHierarchies = Map.of();

    ModuleInterface(BlockScopes pass) {
        String[] ordered = pass.analysis.exports();
        this.exports = Set.of(ordered);
        Map<String, Integer> indices = new HashMap<>(ordered.length);
        for (int i = 0; i < ordered.length; i++) {
            indices.put(ordered[i], i);
        }
        this.exportIndices = Map.copyOf(indices);
        this.moduleKey = pass.moduleKey();
    }

    private ModuleInterface(String name, Map<String, Type> members) {
        this.name = name;
        this.moduleKey = name;
        this.exports = Set.copyOf(members.keySet());
        this.exportIndices =
                Map.of(); // -1 exportIndex so LoweringAnalyzer emits DynamicSelect, not
        // ModuleExportGet
        this.summary =
                new TypedModuleSummary(
                        Map.copyOf(members),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        AxiomData.AxiomTable.EMPTY,
                        null);
    }

    /**
     * Synthetic typed namespace (e.g. {@code wasm} / {@code wasm.<mod>} host imports, {@code
     * BIP025-D9}).
     */
    static ModuleInterface synthetic(String name, Map<String, Type> members) {
        return new ModuleInterface(name, members);
    }

    /** The file, as messages name it : the same name as the runtime module's. */
    public String name() {
        return name;
    }

    /** The canonical module key from {@link ModuleLoader#moduleKey()}, or null if unavailable. */
    public String moduleKey() {
        return moduleKey;
    }

    public void setName(String name) {
        this.name = name;
    }

    void markDynamicImport() {
        this.hasDynamicImport = true;
    }

    /**
     * Whether this module or any transitive static dependency uses a non-static {@code import}
     * reference.
     */
    public boolean transitivelyHasDynamicImport() {
        if (hasDynamicImport) {
            return true;
        }
        java.util.ArrayDeque<ModuleInterface> work = new java.util.ArrayDeque<>();
        Set<ModuleInterface> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        work.add(this);
        visited.add(this);
        while (!work.isEmpty()) {
            ModuleInterface cur = work.poll();
            if (cur.hasDynamicImport) {
                return true;
            }
            for (ModuleInterface dep : cur.dependencies()) {
                if (dep != null && visited.add(dep)) {
                    work.add(dep);
                }
            }
        }
        return false;
    }

    public Set<String> exports() {
        return exports;
    }

    /**
     * The declaration-order index of an export in {@link org.babblelang.runtime.Module#exportAt},
     * or -1.
     */
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
        setCompilation(classLoader, interfaceJvmNames, Map.of(), Map.of());
    }

    public void setCompilation(
            ClassLoader classLoader,
            Map<String, String> interfaceJvmNames,
            Map<String, String> classJvmNames) {
        setCompilation(classLoader, interfaceJvmNames, classJvmNames, Map.of());
    }

    public void setCompilation(
            ClassLoader classLoader,
            Map<String, String> interfaceJvmNames,
            Map<String, String> classJvmNames,
            Map<String, LoweringTargets.ExportedFunctionTarget> exportedFunctions) {
        this.compiledClassLoader = classLoader;
        this.interfaceJvmNames =
                interfaceJvmNames != null ? Map.copyOf(interfaceJvmNames) : Map.of();
        this.classJvmNames = classJvmNames != null ? Map.copyOf(classJvmNames) : Map.of();
        this.exportedFunctions =
                exportedFunctions != null ? Map.copyOf(exportedFunctions) : Map.of();
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

    public Map<String, LoweringTargets.ExportedFunctionTarget> exportedFunctions() {
        return exportedFunctions;
    }

    public LoweringTargets.ExportedFunctionTarget exportedFunction(String exportName) {
        return exportName != null ? exportedFunctions.get(exportName) : null;
    }

    void setTypeHierarchies(Map<String, LoweringTargets.TypeHierarchyInfo> hierarchies) {
        this.typeHierarchies = hierarchies != null ? Map.copyOf(hierarchies) : Map.of();
    }

    public Map<String, LoweringTargets.TypeHierarchyInfo> typeHierarchies() {
        return typeHierarchies;
    }

    public LoweringTargets.TypeHierarchyInfo typeHierarchy(String id) {
        return id != null ? typeHierarchies.get(id) : null;
    }

    void complete(TypeChecks checks) {
        this.checks = checks;
    }

    /** Publishes the file's AST-detached type and effect summary once analysis finishes. */
    void typed(TypedModuleSummary summary) {
        this.summary = summary != null ? summary : TypedModuleSummary.EMPTY;
    }

    /** The file's published summary, or null if the file is still being analysed (a cycle). */
    TypedModuleSummary summary() {
        return summary;
    }

    AxiomData.AxiomTable exportedAxioms() {
        return summary != null ? summary.exportedAxioms() : AxiomData.AxiomTable.EMPTY;
    }

    /**
     * Resolves {@code path[index…]}, a path through the file's exports, to an interface : a
     * TypeNode or a Java interface class.
     */
    Object resolveType(
            List<String> path, int index, BabbleParser.TypeContext type, boolean forInterface) {
        if (checks == null) {
            throw new AnalysisException(
                    "implements needs a statically known interface : "
                            + name
                            + " is still being analysed (an import cycle)",
                    type.getStart().getLine());
        }
        return checks.resolveExport(path, index, type, forInterface);
    }

    /**
     * The module an export refers to, when it is one statically : {@code export def m =
     * import("m.ba")}.
     */
    ModuleInterface exportedModule(String export, int depth) {
        return checks == null ? null : checks.exportedModule(export, depth);
    }
}
