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
 * class or interface, or module — a top-level class or interface expression, through
 * defs that are never reassigned and module exports. {@code LoweringAnalyzer} turns
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
            String name = ScopeAnalysis.selectorName(sel);
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
                return null;
            }
            Object recv = resolve(sel.expression(), depth + 1);
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

    static boolean isStaticTopLevelTypeExpr(ParserRuleContext typeExpr) {
        ParserRuleContext cur = typeExpr;
        while (cur != null) {
            ParserRuleContext parent = cur.getParent();
            if (parent instanceof BabbleParser.FileContext) {
                return true;
            }
            if (parent instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
                cur = def;
            } else {
                return false;
            }
        }
        return false;
    }

    Object freeze(Object ref) {
        if (ref instanceof TypeNode || ref instanceof ModuleInterface) {
            return ref;
        }
        return null;
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
