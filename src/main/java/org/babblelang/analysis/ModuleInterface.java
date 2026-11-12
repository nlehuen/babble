package org.babblelang.analysis;

import org.babblelang.parser.BabbleParser;

import java.util.List;
import java.util.Set;

/**
 * What the analysis of a file knows of a file it imports statically : its exported names,
 * and the interfaces it exports, for {@code implements}, {@code extends} and
 * {@code super(I)} (docs/design/modules.md §8.1).
 * <p>
 * An interface is complete once the file's {@link TypeChecks} have run. Before that, which
 * a file importing it back in a cycle observes, only its exported names are known.
 */
public final class ModuleInterface {
    private final Set<String> exports;
    private String name = "<module>";
    private TypeChecks checks;
    private TypeChecker checker;

    ModuleInterface(BlockScopes pass) {
        this.exports = Set.of(pass.analysis.exports());
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

    void complete(TypeChecks checks) {
        this.checks = checks;
    }

    /** With the type checker on : the file's checker, once it ran, which types its exports. */
    void typed(TypeChecker checker) {
        this.checker = checker;
    }

    /** The file's type checker, or null : the checker is off, or the file is still being analysed (a cycle). */
    TypeChecker checker() {
        return checker;
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
