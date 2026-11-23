package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Class, interface, {@code super}, and type-hierarchy metadata sub-view of {@link ScopeAnalysis} (BIP027-D3).
 */
public final class HierarchyMetadata {
    private final ScopeAnalysis owner;
    private final ParseTreeProperty<ClassShape> shapes = new ParseTreeProperty<>();
    private final ParseTreeProperty<BabbleParser.TypeContext> superTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<LoweringTargets.SuperTargetInfo> superTargetInfos = new ParseTreeProperty<>();
    private final Map<String, LoweringTargets.TypeHierarchyInfo> typeHierarchies = new LinkedHashMap<>();

    HierarchyMetadata(ScopeAnalysis owner) {
        this.owner = owner;
    }

    void setShape(ParserRuleContext type, ClassShape shape) {
        shapes.put(type, shape);
    }

    /** The shape of a class, object or interface expression. */
    public ClassShape shape(ParserRuleContext type) {
        return shapes.get(type);
    }

    void setSuperTarget(BabbleParser.SuperExpressionContext superExpression, BabbleParser.TypeContext type) {
        superTargets.put(superExpression, type);
    }

    /**
     * The interface a {@code super} call goes to : its qualifier, or for an unqualified
     * call, the entry of the implements or extends list that provides the default.
     */
    public BabbleParser.TypeContext superTarget(BabbleParser.SuperExpressionContext superExpression) {
        return superTargets.get(superExpression);
    }

    void setSuperTargetInfo(BabbleParser.SuperExpressionContext superExpression, LoweringTargets.SuperTargetInfo info) {
        superTargetInfos.put(superExpression, info);
    }

    public LoweringTargets.SuperTargetInfo superTargetInfo(BabbleParser.SuperExpressionContext superExpression) {
        return superTargetInfos.get(superExpression);
    }

    void setTypeHierarchy(String id, LoweringTargets.TypeHierarchyInfo info) {
        if (id != null && info != null) {
            typeHierarchies.put(id, info);
        }
    }

    public Map<String, LoweringTargets.TypeHierarchyInfo> typeHierarchies() {
        return Map.copyOf(typeHierarchies);
    }

    public LoweringTargets.TypeHierarchyInfo typeHierarchy(String id) {
        if (id == null) {
            return null;
        }
        LoweringTargets.TypeHierarchyInfo local = typeHierarchies.get(id);
        if (local != null) {
            return local;
        }
        AnalysisSession session = owner.session();
        if (session != null) {
            LoweringTargets.TypeHierarchyInfo fromSession = session.typeHierarchy(id);
            if (fromSession != null) {
                return fromSession;
            }
        }
        List<ModuleInterface> importedModules = owner.importedModules();
        if (!importedModules.isEmpty()) {
            Queue<ModuleInterface> queue = new ArrayDeque<>();
            Set<ModuleInterface> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ModuleInterface mod : importedModules) {
                if (mod != null && visited.add(mod)) {
                    queue.add(mod);
                }
            }
            while (!queue.isEmpty()) {
                ModuleInterface mod = queue.poll();
                LoweringTargets.TypeHierarchyInfo fromModule = mod.typeHierarchy(id);
                if (fromModule != null) {
                    return fromModule;
                }
                for (ModuleInterface dep : mod.dependencies()) {
                    if (dep != null && visited.add(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }
        return null;
    }
}
