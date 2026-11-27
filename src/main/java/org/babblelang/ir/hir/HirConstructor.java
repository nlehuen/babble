package org.babblelang.ir.hir;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;

/**
 * One compiled constructor of an {@link HirClass} ({@code BIP040-D10}) : either the primary header
 * constructor (with optional parameterless {@code init (...)}) or an explicit/secondary
 * parameterized {@code init (params) -> (...)} constructor.
 *
 * <p>When {@code delegateIndex >= 0}, {@code preDelegateBody} holds the pre-delegation local
 * statements before {@code this(...)}, {@code delegateArgs}/{@code delegateBinding} hold the
 * delegated constructor call arguments, and {@code body} holds the post-delegation statements. When
 * {@code delegateIndex < 0} (root constructor), {@code preDelegateBody} and {@code delegateArgs}
 * are empty and {@code body} holds the {@code init} block statements.
 */
public record HirConstructor(
        int index,
        int ownerId,
        boolean isPrimary,
        boolean isPrivate,
        List<HirVar> declaredVars,
        List<HirParam> params,
        int requiredParams,
        int keywordOnlyStart,
        int restIndex,
        int delegateIndex,
        List<HirExpr> preDelegateBody,
        List<HirCallArg> delegateArgs,
        LoweringTargets.ArgumentBinding delegateBinding,
        List<HirExpr> body,
        boolean allDefFieldsFinalSafe,
        int line) {
    public HirConstructor {
        declaredVars = List.copyOf(declaredVars);
        params = List.copyOf(params);
        preDelegateBody = List.copyOf(preDelegateBody);
        delegateArgs = List.copyOf(delegateArgs);
        body = List.copyOf(body);
    }

    public boolean isDelegating() {
        return delegateIndex >= 0;
    }

    public String[] paramNames() {
        String[] names = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            names[i] = params.get(i).name();
        }
        return names;
    }

    public HirConstructor withBodies(
            List<HirParam> newParams,
            List<HirExpr> newPreDelegate,
            List<HirCallArg> newDelegateArgs,
            List<HirExpr> newBody) {
        return new HirConstructor(
                index,
                ownerId,
                isPrimary,
                isPrivate,
                declaredVars,
                newParams,
                requiredParams,
                keywordOnlyStart,
                restIndex,
                delegateIndex,
                newPreDelegate,
                newDelegateArgs,
                delegateBinding,
                newBody,
                allDefFieldsFinalSafe,
                line);
    }
}
