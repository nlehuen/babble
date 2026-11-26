package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/**
 * Type checking for {@code throw} and {@code try}/{@code catch}/{@code finally} expressions ({@code
 * BIP028}): throwable validation, catch-target resolution, catch-union/intersection validation, and
 * catch-clause subsumption checking.
 */
final class ExceptionTyper {
    private final TypeChecker checker;

    ExceptionTyper(TypeChecker checker) {
        this.checker = checker;
    }

    Type typeThrow(BabbleParser.ThrowExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type valueType = checker.synth(ctx.expression());
        if (valueType != Type.ERROR
                && valueType != Type.NOTHING
                && !isThrowableType(valueType)
                && !isUntypedCatchVar(ctx.expression())) {
            checker.warn("Type error : cannot throw " + valueType, ctx.expression());
        }
        return Type.NOTHING;
    }

    Type typeTry(BabbleParser.TryExpressionContext ctx, Type expected, TypeChecker.Role role) {
        TypeEnv beforeTry = checker.env;
        Type tryType = checker.type(ctx.tryBlock, expected, role);
        TypeEnv afterTry = checker.env;
        TypeEnv catchEntryEnv = beforeTry.evictAll(checker.narrowing.assignedLocals(ctx.tryBlock));

        boolean caughtAllThrowable = false;
        String caughtAllThrowableDisplay = null;
        boolean caughtAllNonError = false;
        List<Type> priorCaughtMembers = new ArrayList<>();

        List<Type> branchTypes = new ArrayList<>();
        List<ParserRuleContext> branchNodes = new ArrayList<>();
        List<TypeEnv> branchEnvs = new ArrayList<>();
        branchTypes.add(tryType);
        branchNodes.add(ctx.tryBlock);
        branchEnvs.add(afterTry);
        Set<ParserRuleContext> allCatchAssigned =
                Collections.newSetFromMap(new IdentityHashMap<>());

        for (BabbleParser.CatchClauseContext clause : ctx.catchClause()) {
            Type boundType;
            if (clause.type() == null) {
                if (caughtAllThrowable) {
                    checker.warn(
                            "Unreachable catch clause : catch-all is already caught by "
                                    + caughtAllThrowableDisplay,
                            clause);
                } else if (caughtAllNonError) {
                    checker.warn(
                            "Unreachable catch clause : catch-all is already caught by earlier catch",
                            clause);
                }
                caughtAllNonError = true;
                checker.catchTargetTypes.put(clause, null);
                boundType = Type.ANY;
            } else {
                Type target = resolveCatchTarget(clause);
                checker.catchTargetTypes.put(clause, target);
                boundType = target;
                if (target != Type.ERROR) {
                    List<Type> members = flattenCatchUnion(target);
                    // Check reachability against earlier catch clauses in source order.
                    int subsumedCount = 0;
                    String firstSubsumer = null;
                    List<Type> subsumedMembers = new ArrayList<>();
                    List<String> subsumerNames = new ArrayList<>();
                    for (Type m : members) {
                        String subsumer =
                                catchSubsumedBy(
                                        m,
                                        caughtAllThrowable,
                                        caughtAllThrowableDisplay,
                                        caughtAllNonError,
                                        priorCaughtMembers);
                        if (subsumer != null) {
                            subsumedCount++;
                            if (firstSubsumer == null) {
                                firstSubsumer = subsumer;
                            }
                            subsumedMembers.add(m);
                            subsumerNames.add(subsumer);
                        }
                    }
                    if (subsumedCount == members.size() && !members.isEmpty()) {
                        checker.warn(
                                "Unreachable catch clause : "
                                        + target
                                        + " is already caught by "
                                        + firstSubsumer,
                                clause);
                    } else {
                        for (int i = 0; i < subsumedMembers.size(); i++) {
                            checker.warn(
                                    "Unreachable catch type : "
                                            + subsumedMembers.get(i)
                                            + " is already caught by "
                                            + subsumerNames.get(i),
                                    clause.type());
                        }
                    }
                    for (Type m : members) {
                        if (m instanceof Type.JavaInstance ji && ji.type() == Throwable.class) {
                            caughtAllThrowable = true;
                            caughtAllThrowableDisplay = m.toString();
                        }
                        priorCaughtMembers.add(m);
                    }
                }
            }
            checker.declarationTypes.put(clause, boundType);
            checker.env = catchEntryEnv;
            Type catchType = checker.type(clause.catchBlock, expected, role);
            branchTypes.add(catchType);
            branchNodes.add(clause.catchBlock);
            branchEnvs.add(checker.env);
            allCatchAssigned.addAll(checker.narrowing.assignedLocals(clause.catchBlock));
        }

        Type finallyType = null;
        if (ctx.finallyBlock != null) {
            checker.env = catchEntryEnv.evictAll(allCatchAssigned);
            finallyType = checker.stmt(ctx.finallyBlock);
        }

        TypeEnv joinedEnv = null;
        for (int i = 0; i < branchTypes.size(); i++) {
            if (branchTypes.get(i) != Type.NOTHING) {
                joinedEnv =
                        joinedEnv == null
                                ? branchEnvs.get(i)
                                : TypeEnv.joinBranches(beforeTry, joinedEnv, branchEnvs.get(i));
            }
        }
        if (joinedEnv == null) {
            joinedEnv = beforeTry;
        }
        if (ctx.finallyBlock != null) {
            joinedEnv = joinedEnv.evictAll(checker.narrowing.assignedLocals(ctx.finallyBlock));
        }
        checker.env = joinedEnv;

        if (finallyType == Type.NOTHING) {
            return Type.NOTHING;
        }

        boolean used = role.isUsed();
        boolean allNothing = true;
        for (Type bt : branchTypes) {
            if (bt != Type.NOTHING) {
                allNothing = false;
                break;
            }
        }
        if (allNothing) {
            return Type.NOTHING;
        }
        if (expected != null && expected != Type.ERROR && used) {
            for (int i = 0; i < branchTypes.size(); i++) {
                if (branchTypes.get(i) != Type.NOTHING) {
                    checker.subsumes(branchNodes.get(i), branchTypes.get(i), expected);
                }
            }
            return expected;
        }
        Type joined = Type.NOTHING;
        for (Type bt : branchTypes) {
            if (bt == Type.NOTHING) {
                continue;
            }
            if (joined == Type.NOTHING) {
                joined = bt;
            } else {
                Type next = checker.algebra.join(joined, bt);
                if (next == null) {
                    if (!used) {
                        return Type.NULL;
                    }
                    if (!TypeChecker.isUnresolved(joined) && !TypeChecker.isUnresolved(bt)) {
                        checker.warn(
                                "Type error : the branches of try have different types : "
                                        + joined
                                        + " and "
                                        + bt,
                                ctx);
                    }
                    return Type.ERROR;
                }
                joined = next;
            }
        }
        if (joined != Type.NOTHING && joined != Type.ERROR) {
            for (int i = 0; i < branchTypes.size(); i++) {
                if (branchTypes.get(i) != Type.NOTHING) {
                    checker.coerce(branchNodes.get(i), branchTypes.get(i), joined);
                }
            }
        }
        return joined;
    }

    // ---------------------------------------------------------------- throw / catch type
    // validation (BIP028)

    private boolean isUntypedCatchVar(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object resolved =
                    checker.resolveName(sel, sel.ID().getText(), checker.occurrenceScope(sel));
            return resolved instanceof BabbleParser.CatchClauseContext cc && cc.type() == null;
        }
        return false;
    }

    static boolean isThrowableType(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return false;
        }
        if (type instanceof Type.Frozen f) {
            return isThrowableType(f.of());
        }
        if (type instanceof Type.TypeVar tv) {
            return tv.bound() != null && isThrowableType(tv.bound());
        }
        if (type instanceof Type.JavaInstance ji) {
            return Throwable.class.isAssignableFrom(ji.type());
        }
        if (type instanceof Type.Instance) {
            return true;
        }
        if (type instanceof Type.Union u) {
            if (u.members().isEmpty()) {
                return false;
            }
            for (Type m : u.members()) {
                if (!isThrowableType(m)) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Type.Intersection inter) {
            boolean hasThrowableOrNominal = false;
            for (Type m : inter.members()) {
                if (m == Type.NULL) {
                    return false;
                }
                if (isThrowableType(m)) {
                    hasThrowableOrNominal = true;
                }
            }
            return hasThrowableOrNominal;
        }
        return false;
    }

    private Type resolveCatchTarget(BabbleParser.CatchClauseContext clause) {
        ScopeNode scope = checker.scopeOf(clause);
        if (!validateCatchTypeAst(clause.type(), scope)) {
            return Type.ERROR;
        }
        Type target = checker.annotator.annotation(clause.type(), scope, true);
        if (target == Type.ERROR) {
            return Type.ERROR;
        }
        if (target.anyMatch(Type.Frozen.class::isInstance)) {
            checker.warn("Cannot catch 'frozen' type : " + target, clause.type());
            return Type.ERROR;
        }
        if (target.hasTypeVar()) {
            checker.warn("Cannot catch type parameter : " + target, clause.type());
            return Type.ERROR;
        }
        if (Type.containsNull(target)) {
            checker.warn("Cannot catch nullable type : " + target, clause.type());
            return Type.ERROR;
        }
        List<Type> members = flattenCatchUnion(target);
        for (Type m : members) {
            if (m instanceof Type.JavaInstance ji) {
                if (!Throwable.class.isAssignableFrom(ji.type())) {
                    checker.warn("Type error : " + m + " is not a Throwable", clause.type());
                    return Type.ERROR;
                }
            } else if (m instanceof Type.Instance) {
                // Valid Babble nominal exception (class/record/object/interface).
            } else if (m instanceof Type.Intersection inter) {
                if (!isValidCatchIntersection(inter)) {
                    checker.warn("Cannot catch non-exception type : " + m, clause.type());
                    return Type.ERROR;
                }
            } else {
                checker.warn("Cannot catch non-exception type : " + m, clause.type());
                return Type.ERROR;
            }
        }
        // Check redundant union members within this catch clause (e.g. Exception | IOException).
        for (int i = 0; i < members.size(); i++) {
            for (int j = 0; j < members.size(); j++) {
                if (i != j && isCatchSubtype(members.get(i), members.get(j))) {
                    checker.warn(
                            "Redundant catch type : "
                                    + members.get(i)
                                    + " is already a subtype of "
                                    + members.get(j),
                            clause.type());
                    break;
                }
            }
        }
        return target;
    }

    private boolean validateCatchTypeAst(BabbleParser.TypeContext typeCtx, ScopeNode scope) {
        for (BabbleParser.NullableTypeContext nt : typeCtx.nullableType()) {
            if (!validateCatchNullableAst(nt, scope)) {
                return false;
            }
        }
        return true;
    }

    private boolean validateCatchNullableAst(BabbleParser.NullableTypeContext nt, ScopeNode scope) {
        if (nt instanceof BabbleParser.ParenTypeContext pt) {
            if (pt.frozen != null) {
                Type inner = checker.annotator.annotation(pt.type(), scope);
                String label =
                        inner != Type.ERROR
                                ? Type.frozen(inner).toString()
                                : "frozen (" + pt.type().getText() + ")";
                checker.warn("Cannot catch 'frozen' type : " + label, pt);
                return false;
            }
            return validateCatchTypeAst(pt.type(), scope);
        }
        if (nt instanceof BabbleParser.NonParenTypeContext npt
                && npt.nonParenNullableType() instanceof BabbleParser.SimpleTypeContext simple) {
            if ("void".equals(simple.typeName().getText())) {
                checker.warn("Cannot catch non-exception type : void", simple);
                return false;
            }
            if (simple.frozen != null) {
                Type resolved = checker.annotator.nonParenAnnotation(simple, scope, false);
                String label =
                        resolved != Type.ERROR
                                ? resolved.toString()
                                : "frozen " + simple.typeName().getText();
                checker.warn("Cannot catch 'frozen' type : " + label, simple);
                return false;
            }
            if (simple.OF() != null
                    && !"array".equals(simple.typeName().getText())
                    && !checker.annotator.resolvesToArrayAlias(simple, scope)) {
                checker.warn(
                        "Cannot catch erased type arguments : use " + simple.typeName().getText(),
                        simple);
                return false;
            }
        }
        return true;
    }

    private static boolean isValidCatchIntersection(Type.Intersection inter) {
        boolean hasNominalOrThrowable = false;
        for (Type m : inter.members()) {
            if (m instanceof Type.Instance) {
                hasNominalOrThrowable = true;
            } else if (m instanceof Type.JavaInstance ji) {
                if (Throwable.class.isAssignableFrom(ji.type())) {
                    hasNominalOrThrowable = true;
                } else if (!ji.type().isInterface()) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return hasNominalOrThrowable;
    }

    static List<Type> flattenCatchUnion(Type target) {
        if (target instanceof Type.Union u) {
            List<Type> out = new ArrayList<>();
            for (Type m : u.members()) {
                out.addAll(flattenCatchUnion(m));
            }
            return out;
        }
        return List.of(target);
    }

    static boolean mayBeJavaError(Type type) {
        if (type instanceof Type.JavaInstance ji) {
            return Error.class.isAssignableFrom(ji.type())
                    || ji.type().isAssignableFrom(Error.class);
        }
        if (type instanceof Type.Union u) {
            for (Type m : u.members()) {
                if (mayBeJavaError(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isCatchSubtype(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR || a == Type.NOTHING) {
            return false;
        }
        if (a instanceof Type.Frozen f) {
            return isCatchSubtype(f.of(), b);
        }
        if (a instanceof Type.Union ua) {
            if (ua.members().isEmpty()) {
                return false;
            }
            for (Type m : ua.members()) {
                if (!isCatchSubtype(m, b)) {
                    return false;
                }
            }
            return true;
        }
        if (b instanceof Type.Union ub) {
            for (Type m : ub.members()) {
                if (isCatchSubtype(a, m)) {
                    return true;
                }
            }
            return false;
        }
        if (b instanceof Type.JavaInstance jb) {
            if (jb.type() == Throwable.class) {
                return a instanceof Type.JavaInstance ja
                                && Throwable.class.isAssignableFrom(ja.type())
                        || a instanceof Type.Instance
                        || a instanceof Type.Intersection;
            }
            if (a instanceof Type.JavaInstance ja) {
                return jb.type().isAssignableFrom(ja.type());
            }
            return false;
        }
        if (b instanceof Type.Instance ib) {
            if (a instanceof Type.Instance ia) {
                return checker.algebra.runtimeSubtype(ia, ib);
            }
            if (a instanceof Type.Intersection inter) {
                for (Type m : inter.members()) {
                    if (isCatchSubtype(m, ib)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (b instanceof Type.Intersection interB) {
            for (Type mb : interB.members()) {
                if (!isCatchSubtype(a, mb)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private String catchSubsumedBy(
            Type member,
            boolean caughtAllThrowable,
            String caughtAllThrowableDisplay,
            boolean caughtAllNonError,
            List<Type> priorMembers) {
        if (caughtAllThrowable) {
            return caughtAllThrowableDisplay;
        }
        if (caughtAllNonError && !mayBeJavaError(member)) {
            return "earlier catch";
        }
        for (Type prior : priorMembers) {
            if (isCatchSubtype(member, prior)) {
                return prior.toString();
            }
        }
        return null;
    }
}
