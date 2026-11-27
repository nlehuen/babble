package org.babblelang.analysis;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/**
 * Exception-handling ({@code throw} / {@code try} / {@code catch} / {@code finally}) effect, {@code
 * nothrow}-restoration, and local-catch provenance analysis extracted from {@link EffectBodyWalker}
 * ({@code BIP028-D7}–{@code D8}, {@code BIP032}).
 */
final class TryEffectAnalyzer {
    private TryEffectAnalyzer() {}

    static Reachability.Origin evalThrow(
            EffectBodyWalker w, BabbleParser.ThrowExpressionContext ctx) {
        w.stmtHasSideEffect = true;
        Reachability.Origin orig = w.eval(ctx.expression());
        Type exprType = w.effectiveType(ctx.expression());
        boolean locallyCaught = false;
        if (exprType != null && exprType != Type.ERROR && exprType != Type.NOTHING) {
            for (EffectBodyWalker.TryFrame frame : w.activeTryStack) {
                for (int i = 0; i < frame.catches.size(); i++) {
                    BabbleParser.CatchClauseContext cc = frame.catches.get(i);
                    Type catchType = w.ec.checker.catchTargetType(cc);
                    boolean matches;
                    if (cc.type() == null) {
                        matches = !TypeChecker.mayBeJavaError(exprType);
                    } else if (catchType != null && catchType != Type.ERROR) {
                        matches = w.ec.checker.isCatchSubtype(exprType, catchType);
                    } else {
                        matches = false;
                    }
                    if (matches) {
                        frame.hadLocalThrow = true;
                        w.deepestThrowEscapeLevel =
                                Math.min(w.deepestThrowEscapeLevel, frame.level);
                        if (orig != Reachability.Origin.IMMUTABLE) {
                            Reachability.Origin routed = orig.flattenElements();
                            if (orig.unique && orig.uniqueOwner != null) {
                                w.demoteUniqueOwner(
                                        orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                                routed = routed.demoteAlias(Reachability.Origin.IMMUTABLE);
                            }
                            frame.caughtOrigins[i] =
                                    frame.caughtOrigins[i] == null
                                            ? routed
                                            : frame.caughtOrigins[i].join(routed);
                        }
                        locallyCaught = true;
                        break;
                    }
                }
                if (locallyCaught) {
                    break;
                }
                frame.hadEscapingThrow = true;
                if (TypeChecker.mayBeJavaError(exprType)) {
                    frame.hadEscapingJavaError = true;
                }
            }
        }
        if (!locallyCaught) {
            w.nothrow = false;
            if (exprType != null
                    && exprType != Type.ERROR
                    && exprType != Type.NOTHING
                    && TypeChecker.mayBeJavaError(exprType)) {
                w.throwsJavaError = true;
            }
            w.deepestThrowEscapeLevel = -1;
            if (orig != Reachability.Origin.IMMUTABLE) {
                if (orig.unique && orig.uniqueOwner != null) {
                    w.demoteUniqueOwner(orig.uniqueOwner, Reachability.Origin.IMMUTABLE);
                }
                w.markStoredOrigin(orig, "throw", ctx);
            }
        }
        return Reachability.Origin.IMMUTABLE;
    }

    static Reachability.Origin evalTry(EffectBodyWalker w, BabbleParser.TryExpressionContext ctx) {
        Map<ParserRuleContext, Reachability.Origin> preEnv = new IdentityHashMap<>(w.env);
        List<BabbleParser.CatchClauseContext> catches = ctx.catchClause();
        int outerFloor = w.activeTryStack.size();
        EffectBodyWalker.TryFrame frame =
                new EffectBodyWalker.TryFrame(outerFloor, catches, preEnv);

        boolean outerNothrow = w.nothrow;
        boolean outerThrowsJavaError = w.throwsJavaError;
        boolean outerHermetic = w.hermetic;
        boolean outerDeterministic = w.deterministic;
        int outerEscape = w.deepestThrowEscapeLevel;
        w.nothrow = true;
        w.throwsJavaError = false;
        w.hermetic = true;
        w.deterministic = true;
        w.deepestThrowEscapeLevel = Integer.MAX_VALUE;
        w.activeTryStack.push(frame);
        Reachability.Origin tryOrig;
        try {
            tryOrig = w.evalBlock(ctx.tryBlock, false);
        } finally {
            w.activeTryStack.pop();
        }
        int tryEscape = w.deepestThrowEscapeLevel;
        boolean tryThrowsJavaError = w.throwsJavaError;
        boolean tryHermetic = w.hermetic;
        boolean tryDeterministic = w.deterministic;
        w.hermetic = outerHermetic && tryHermetic;
        w.deterministic = outerDeterministic && tryDeterministic;
        Map<ParserRuleContext, Reachability.Origin> afterTry = w.env;
        frame.recordUnwindEnv(afterTry, w);
        boolean tryHadNonLocalThrow = !w.nothrow || frame.hadEscapingThrow;
        boolean tryNothrow =
                w.nothrow
                        && !frame.hadLocalThrow
                        && !frame.hadEscapingThrow
                        && tryEscape >= outerFloor;

        if (tryNothrow
                && !catches.isEmpty()
                && w.emitDiagnostics
                && w.forwardedParams.isEmpty()
                && w.pseudoForwarded.isEmpty()
                && !w.hasPriorDiagnostic(ctx)) {
            BabbleParser.CatchClauseContext firstCatch = catches.get(0);
            String warnMsg = "try block cannot throw; catch clause is unreachable";
            String warnKey =
                    firstCatch.getStart().getLine()
                            + ":"
                            + firstCatch.getStart().getCharPositionInLine()
                            + ":WARN:"
                            + warnMsg;
            if (w.reportedMessages.add(warnKey)) {
                w.ec.checker.effectWarning(warnMsg, firstCatch);
            }
        }

        Map<ParserRuleContext, Reachability.Origin> catchEntryEnv = frame.unwindEnv;
        Map<ParserRuleContext, Reachability.Origin> finallyEntryEnv =
                w.mergeEnvs(catchEntryEnv, afterTry);
        Type tryType = w.ec.checker.rawTypeOf(ctx.tryBlock);
        Map<ParserRuleContext, Reachability.Origin> normalExitEnv =
                tryType != Type.NOTHING ? afterTry : null;
        Reachability.Origin resultOrig =
                tryType != Type.NOTHING ? tryOrig : Reachability.Origin.IMMUTABLE;

        boolean catchesAllNonError = false;
        boolean catchesAllErrors = false;
        boolean allCatchesNothrow = true;
        boolean anyCatchThrowsJavaError = false;
        int propagatedEscape = (tryEscape < outerFloor) ? tryEscape : Integer.MAX_VALUE;

        for (int i = 0; i < catches.size(); i++) {
            BabbleParser.CatchClauseContext cc = catches.get(i);
            Type catchType = w.ec.checker.catchTargetType(cc);
            if (cc.type() == null || catchesJavaThrowable(catchType)) {
                catchesAllNonError = true;
            }
            if (catchesJavaThrowable(catchType) || catchesJavaError(catchType)) {
                catchesAllErrors = true;
            }
            w.env = new IdentityHashMap<>(catchEntryEnv);
            Type boundType = w.ec.checker.typeOfDeclarationForEffects(cc);
            Reachability.Origin eOrig;
            if (boundType != null && w.ec.javaResolver.isDeeplyImmutable(boundType)) {
                eOrig = Reachability.Origin.IMMUTABLE;
            } else {
                Reachability.Origin localRouted = frame.caughtOrigins[i];
                if (tryHadNonLocalThrow || localRouted == null) {
                    Reachability.Origin nonLocal =
                            (!tryHermetic || !tryDeterministic)
                                    ? Reachability.Origin.ofExternal(cc, false)
                                    : new Reachability.Origin(
                                            Reachability.Origin.EMPTY_BITS,
                                            false,
                                            false,
                                            java.util.Set.of(cc),
                                            new Reachability.PathKey(cc, List.of()));
                    eOrig =
                            localRouted == null
                                    ? nonLocal
                                    : w.withLocalAliasKey(
                                            localRouted.join(nonLocal), cc, boundType);
                } else {
                    eOrig = w.withLocalAliasKey(localRouted, cc, boundType);
                }
            }
            w.env.put(cc, eOrig);
            w.nothrow = true;
            w.throwsJavaError = false;
            w.deepestThrowEscapeLevel = Integer.MAX_VALUE;
            Reachability.Origin catchOrig = w.evalBlock(cc.catchBlock, false);
            int catchEscape = w.deepestThrowEscapeLevel;
            allCatchesNothrow &= w.nothrow && catchEscape >= outerFloor;
            anyCatchThrowsJavaError |= w.throwsJavaError;
            if (catchEscape < outerFloor) {
                propagatedEscape = Math.min(propagatedEscape, catchEscape);
            }
            w.env.remove(cc);
            Map<ParserRuleContext, Reachability.Origin> afterCatch = w.env;
            finallyEntryEnv = w.mergeEnvs(finallyEntryEnv, afterCatch);
            Type catchBlockType = w.ec.checker.rawTypeOf(cc.catchBlock);
            if (catchBlockType != Type.NOTHING) {
                normalExitEnv =
                        normalExitEnv == null ? afterCatch : w.mergeEnvs(normalExitEnv, afterCatch);
                resultOrig =
                        resultOrig == Reachability.Origin.IMMUTABLE
                                ? catchOrig
                                : resultOrig.join(catchOrig);
            }
        }

        boolean finallyNothrow = true;
        boolean finallyThrowsJavaError = false;
        if (ctx.finallyBlock != null) {
            w.env = new IdentityHashMap<>(finallyEntryEnv);
            w.nothrow = true;
            w.throwsJavaError = false;
            w.deepestThrowEscapeLevel = Integer.MAX_VALUE;
            w.evalBlock(ctx.finallyBlock, false, true);
            int finEscape = w.deepestThrowEscapeLevel;
            finallyNothrow = w.nothrow && finEscape >= outerFloor;
            finallyThrowsJavaError = w.throwsJavaError;
            if (finEscape < outerFloor) {
                propagatedEscape = Math.min(propagatedEscape, finEscape);
            }
            w.ec.pass.analysis.effects().setTryFinallyNothrow(ctx, finallyNothrow);
        } else {
            w.env = normalExitEnv != null ? normalExitEnv : preEnv;
        }

        // Untyped catch excludes java.lang.Error (BIP028-D4), so any escaping throw or call that
        // may throw java.lang.Error prevents nothrow restoration unless Error/Throwable is caught.
        boolean tryUncaughtByThis =
                frame.hadEscapingThrow || (tryThrowsJavaError && !catchesAllErrors);
        boolean tryPropagatesError =
                frame.hadEscapingJavaError || (tryThrowsJavaError && !catchesAllErrors);
        boolean tryExprNothrow =
                tryNothrow
                        ? finallyNothrow
                        : (catchesAllNonError
                                && !tryUncaughtByThis
                                && allCatchesNothrow
                                && finallyNothrow);
        w.nothrow = outerNothrow && tryExprNothrow;
        w.throwsJavaError =
                outerThrowsJavaError
                        || tryPropagatesError
                        || anyCatchThrowsJavaError
                        || finallyThrowsJavaError;
        w.deepestThrowEscapeLevel = Math.min(outerEscape, propagatedEscape);

        return resultOrig;
    }

    private static boolean catchesJavaThrowable(Type catchType) {
        if (catchType == null || catchType == Type.ERROR) {
            return false;
        }
        for (Type m : TypeChecker.flattenCatchUnion(catchType)) {
            if (m instanceof Type.JavaInstance ji && ji.type() == Throwable.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean catchesJavaError(Type catchType) {
        if (catchType == null || catchType == Type.ERROR) {
            return false;
        }
        for (Type m : TypeChecker.flattenCatchUnion(catchType)) {
            if (m instanceof Type.JavaInstance ji && ji.type() == Error.class) {
                return true;
            }
        }
        return false;
    }
}
