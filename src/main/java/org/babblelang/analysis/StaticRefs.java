package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static singletons ({@code RM003}) : what an expression names when it can only ever be one
 * class or interface, package, or module — a top-level class or interface expression, through
 * defs that are never reassigned, packages, and module exports. {@code LoweringAnalyzer} turns
 * {@code x is C} into {@code INSTANCEOF} with them ; module and session summaries keep them
 * detached from the parse tree ({@link #freeze}).
 */
final class StaticRefs {
    private final BlockScopes pass;
    private final TypeChecker checker;

    StaticRefs(BlockScopes pass, TypeChecker checker) {
        this.pass = pass;
        this.checker = checker;
    }

    TypeNode singletonTypeNode(BabbleParser.ExpressionContext expr) {
        Object ref = resolve(expr, 0);
        return ref instanceof TypeNode tn ? tn : null;
    }

    Object resolve(BabbleParser.ExpressionContext expr, int depth) {
        if (expr == null || depth > 16) {
            return null;
        }
        BabbleParser.ExpressionContext current = expr;
        while (current instanceof BabbleParser.BlockExpressionContext blockExpr
                && blockExpr.block().effectDeclaration().isEmpty()
                && blockExpr.block().expression().size() == 1
                && !pass.analysis.opensScope(blockExpr.block())) {
            current = blockExpr.block().expression(0);
        }
        if (current instanceof BabbleParser.ClassExpressionContext || current instanceof BabbleParser.InterfaceExpressionContext) {
            if (!isStaticTopLevelTypeExpr(current)) {
                return null;
            }
            return pass.typeNodes.get(current);
        }
        if (current instanceof BabbleParser.CallContext call) {
            return pass.modules.get(call);
        }
        if (current instanceof BabbleParser.SelectorContext sel) {
            if (sel.safe != null) {
                return null;
            }
            String name = sel.ID().getText();
            if (sel.expression() == null) {
                Object resolved = TypeChecker.resolveName(sel, name, checker.occurrenceScopeOf(sel));
                if (resolved instanceof BabbleParser.DefExpressionContext def) {
                    if (checker.isReassigned(def)) {
                        return null;
                    }
                    if (!checker.inFile(def) && pass.session != null) {
                        return pass.session.staticSingletons.get(def);
                    }
                    return resolve(def.value, depth + 1);
                }
                if (resolved instanceof List<?> rawList) {
                    return staticPackages(rawList);
                }
                return null;
            }
            Object recv = resolve(sel.expression(), depth + 1);
            if (recv instanceof List<?> pkgList) {
                List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
                for (Object item : pkgList) {
                    if (!(item instanceof BabbleParser.PackageExpressionContext pkg)) {
                        continue;
                    }
                    for (BabbleParser.ExpressionContext stmt : pkg.packageBlock.expression()) {
                        if (stmt instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(name)) {
                            if (checker.isReassigned(def)) {
                                return null;
                            }
                            if (!checker.inFile(def) && pass.session != null) {
                                return pass.session.staticSingletons.get(def);
                            }
                            return resolve(def.value, depth + 1);
                        }
                        if (stmt instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(name)) {
                            nested.add(inner);
                        }
                    }
                }
                return nested.isEmpty() ? null : staticPackages(nested);
            }
            if (recv instanceof TypedModuleSummary.StaticPackageMap pkgMap) {
                return pkgMap.members().get(name);
            }
            if (recv instanceof ModuleInterface mod) {
                TypedModuleSummary summary = mod.summary();
                if (summary == null || !mod.exports().contains(name)) {
                    return null;
                }
                return summary.staticSingleton(name);
            }
        }
        return null;
    }

    static List<BabbleParser.PackageExpressionContext> staticPackages(List<?> rawList) {
        if (rawList.isEmpty()) {
            return null;
        }
        List<BabbleParser.PackageExpressionContext> result = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (!(item instanceof BabbleParser.PackageExpressionContext pkg) || !isStaticTopLevelPackage(pkg)) {
                return null;
            }
            result.add(pkg);
        }
        return result;
    }

    private static boolean isStaticTopLevelPackage(BabbleParser.PackageExpressionContext pkg) {
        ParserRuleContext cur = pkg;
        while (cur != null) {
            ParserRuleContext parent = cur.getParent();
            if (parent instanceof BabbleParser.FileContext) {
                return true;
            }
            if (parent instanceof BabbleParser.BlockContext block
                    && block.getParent() instanceof BabbleParser.PackageExpressionContext enclosingPkg
                    && enclosingPkg.packageBlock == block) {
                cur = enclosingPkg;
            } else {
                return false;
            }
        }
        return false;
    }

    private static boolean isStaticTopLevelTypeExpr(ParserRuleContext typeExpr) {
        ParserRuleContext cur = typeExpr;
        while (cur != null) {
            ParserRuleContext parent = cur.getParent();
            if (parent instanceof BabbleParser.FileContext) {
                return true;
            }
            if (parent instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
                cur = def;
            } else if (parent instanceof BabbleParser.BlockContext block
                    && block.getParent() instanceof BabbleParser.PackageExpressionContext pkg
                    && pkg.packageBlock == block) {
                cur = pkg;
            } else {
                return false;
            }
        }
        return false;
    }

    Object freeze(Object ref) {
        if (ref instanceof TypeNode || ref instanceof ModuleInterface || ref instanceof TypedModuleSummary.StaticPackageMap) {
            return ref;
        }
        if (ref instanceof List<?> pkgList) {
            List<BabbleParser.PackageExpressionContext> list = new ArrayList<>(pkgList.size());
            for (Object item : pkgList) {
                if (item instanceof BabbleParser.PackageExpressionContext p) {
                    list.add(p);
                }
            }
            return list.isEmpty() ? null : freezePackages(list);
        }
        return null;
    }

    TypedModuleSummary.StaticPackageMap freezePackages(List<BabbleParser.PackageExpressionContext> list) {
        Map<String, Object> members = new LinkedHashMap<>();
        Map<String, List<BabbleParser.PackageExpressionContext>> nested = new LinkedHashMap<>();
        for (BabbleParser.PackageExpressionContext pkg : list) {
            for (BabbleParser.ExpressionContext stmt : pkg.packageBlock.expression()) {
                if (stmt instanceof BabbleParser.DefExpressionContext def && !checker.isReassigned(def)) {
                    Object ref = freeze(resolve(def.value, 0));
                    if (ref != null) {
                        members.put(def.name.getText(), ref);
                    }
                } else if (stmt instanceof BabbleParser.PackageExpressionContext inner && isStaticTopLevelPackage(inner)) {
                    nested.computeIfAbsent(inner.name.getText(), k -> new ArrayList<>()).add(inner);
                }
            }
        }
        for (Map.Entry<String, List<BabbleParser.PackageExpressionContext>> entry : nested.entrySet()) {
            members.put(entry.getKey(), freezePackages(entry.getValue()));
        }
        return new TypedModuleSummary.StaticPackageMap(Map.copyOf(members));
    }

    /** The static singleton each top-level def of this input names, detached from the parse tree, for the session. */
    Map<ParserRuleContext, Object> frozenSingletons() {
        Map<ParserRuleContext, Object> frozen = new HashMap<>();
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (checker.inFile(decl) && decl instanceof BabbleParser.DefExpressionContext def && !checker.isReassigned(def)) {
                Object ref = freeze(resolve(def.value, 0));
                if (ref != null) {
                    frozen.put(def, ref);
                }
            }
        }
        return frozen;
    }
}
