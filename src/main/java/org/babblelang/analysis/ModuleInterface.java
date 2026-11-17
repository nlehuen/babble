package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.HashMap;
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
    private String name = "<module>";
    private TypeChecks checks;
    private TypeChecker checker;
    private Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects = Map.of();

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
