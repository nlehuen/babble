package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Flow-sensitive narrowing ({@code BIP010} §4.4) : the {@link TypeEnv.Refinement} a test
 * implies when it holds and when it fails ({@code is}, {@code == null}, {@code != null},
 * truthiness, {@code not}, {@code and}, {@code or}), which declarations can be narrowed, and
 * the locals a loop assigns (evicted before its test).
 */
final class Narrowing {
    private final TypeChecker checker;
    private final Map<BabbleParser.WhileExpressionContext, Set<ParserRuleContext>> assignedWhileLocals = new IdentityHashMap<>();

    Narrowing(TypeChecker checker) {
        this.checker = checker;
    }

    Set<ParserRuleContext> assignedLocals(BabbleParser.WhileExpressionContext ctx) {
        return assignedWhileLocals.computeIfAbsent(ctx, node -> {
            Set<ParserRuleContext> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
            collectAssignedLocals(node, assigned);
            return Collections.unmodifiableSet(assigned);
        });
    }

    private void collectAssignedLocals(ParseTree tree, Set<ParserRuleContext> assigned) {
        if (tree instanceof BabbleParser.FunctionLiteralContext
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null) {
            ScopeNode scope = checker.occurrenceScope(assign);
            if (scope != null && TypeChecker.resolveName(assign, assign.name.getText(), scope) instanceof ParserRuleContext decl) {
                assigned.add(decl);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectAssignedLocals(tree.getChild(i), assigned);
        }
    }

    Type narrowedType(ParserRuleContext declaration) {
        if (checker.capturedReassigned().contains(declaration)) {
            return null;
        }
        return checker.env().lookup(declaration);
    }

    Type preferMoreSpecific(Type a, Type b) {
        return checker.algebra().runtimeSubtype(a, b) && !checker.algebra().runtimeSubtype(b, a) ? a : b;
    }

    TypeEnv.Refinement extractRefinement(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            return extractRefinement(block.block().expression(0));
        }
        if (expr instanceof BabbleParser.BooleanNotContext notCtx) {
            return extractRefinement(notCtx.expression()).invert();
        }
        if (expr instanceof BabbleParser.BooleanOpContext opCtx) {
            TypeEnv.Refinement left = extractRefinement(opCtx.left);
            TypeEnv.Refinement right = extractRefinement(opCtx.right);
            return opCtx.op.getType() == BabbleLexer.AND
                    ? left.and(right, this::preferMoreSpecific)
                    : left.or(right, this::preferMoreSpecific);
        }
        if (expr instanceof BabbleParser.BinaryOpContext opCtx
                && (opCtx.op.getType() == BabbleLexer.EQ || opCtx.op.getType() == BabbleLexer.NEQ)) {
            BabbleParser.ExpressionContext other = null;
            if (isNullLiteral(opCtx.right)) {
                other = opCtx.left;
            } else if (isNullLiteral(opCtx.left)) {
                other = opCtx.right;
            }
            if (other != null) {
                ParserRuleContext decl = narrowableDeclaration(other);
                if (decl != null) {
                    Type current = checker.synth(other);
                    if (!TypeChecker.isUnresolved(current) && Type.containsNull(current)) {
                        Type nonNull = Type.withoutNull(current);
                        Map<ParserRuleContext, Type> whenNotNull = nonNull != Type.NOTHING ? Map.of(decl, nonNull) : Map.of();
                        TypeEnv.Refinement eqNull = new TypeEnv.Refinement(Map.of(decl, Type.NULL), whenNotNull);
                        return opCtx.op.getType() == BabbleLexer.EQ ? eqNull : eqNull.invert();
                    }
                }
            }
            return TypeEnv.Refinement.EMPTY;
        }
        if (expr instanceof BabbleParser.IsExpressionContext isCtx) {
            ParserRuleContext decl = narrowableDeclaration(isCtx.left);
            if (decl == null) {
                return TypeEnv.Refinement.EMPTY;
            }
            Type target = isBuiltinTarget(isCtx);
            if (target == null) {
                target = instanceTypeOfTypeValue(checker.synth(isCtx.right, TypeChecker.Role.TYPE_TEST_RIGHT));
            }
            Type current = checker.synth(isCtx.left);
            if (target == null || target == Type.ERROR
                    || (!TypeChecker.isUnresolved(current) && checker.algebra().runtimeSubtype(current, target))) {
                return TypeEnv.Refinement.EMPTY;
            }
            Type narrowedTrue = !TypeChecker.isUnresolved(current) ? checker.algebra().narrowTarget(current, target) : target;
            Type subtracted = !TypeChecker.isUnresolved(current) ? checker.algebra().subtractType(current, target) : Type.NOTHING;
            Map<ParserRuleContext, Type> whenFalse = (subtracted != Type.NOTHING && !subtracted.equals(current))
                    ? Map.of(decl, subtracted) : Map.of();
            return new TypeEnv.Refinement(Map.of(decl, narrowedTrue), whenFalse);
        }
        ParserRuleContext decl = narrowableDeclaration(expr);
        if (decl != null) {
            Type current = checker.synth(expr);
            if (!TypeChecker.isUnresolved(current) && Type.containsNull(current)) {
                Type nonNull = Type.withoutNull(current);
                if (nonNull != Type.NOTHING) {
                    return new TypeEnv.Refinement(Map.of(decl, nonNull), Map.of());
                }
            }
        }
        return TypeEnv.Refinement.EMPTY;
    }

    private static boolean isNullLiteral(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.NullContext;
    }

    /** The type {@code x is T} tests when T is a core type name ({@code x is int}), else null. */
    Type isBuiltinTarget(BabbleParser.IsExpressionContext ctx) {
        if (ctx.right instanceof BabbleParser.CoreTypeNameContext core) {
            if (Type.collection(core.getText()) != null) {
                return CoreCollections.rawType(core.getText());
            }
            return core.getText().equals("any") ? Type.ANY : Type.scalar(core.getText());
        }
        return null;
    }

    ParserRuleContext narrowableDeclaration(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            Object resolved = TypeChecker.resolveName(selector, selector.ID().getText(), checker.occurrenceScope(selector));
            if (resolved instanceof BabbleParser.DefExpressionContext
                    || resolved instanceof BabbleParser.ParameterDeclarationContext) {
                ParserRuleContext decl = (ParserRuleContext) resolved;
                if (!checker.capturedReassigned().contains(decl)
                        && (!checker.isReassigned(decl) || Objects.equals(TypeChecker.callableScope(checker.occurrenceScope(selector)), TypeChecker.callableScope(checker.scopeOf(decl))))) {
                    return decl;
                }
            }
        }
        return null;
    }

    Type instanceTypeOfTypeValue(Type typeValue) {
        if (typeValue instanceof Type.ClassValue cv) {
            return checker.annotator().rawInstance(cv.node());
        }
        if (typeValue instanceof Type.JavaClassValue jcv) {
            return JavaTypeBridge.rawJavaInstance(jcv.type());
        }
        return null;
    }
}
