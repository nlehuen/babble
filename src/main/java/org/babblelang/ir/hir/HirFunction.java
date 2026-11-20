package org.babblelang.ir.hir;

import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.ScopeAnalysis;

import java.util.List;

/**
 * Compiled function literal, class/object method, or interface default method in {@link HirModule}
 * (BIP014-P1).
 */
public record HirFunction(
        int id,
        String name,
        String jvmMethodName,
        int ownerClassId,
        String memberName,
        boolean namespaceScope,
        String[] declaredNames,
        List<HirVar> declaredVars,
        List<HirVar> capturedVars,
        List<HirParam> params,
        int requiredParams,
        ScopeAnalysis.PrimitiveKind returnKind,
        int bodyWideningRank,
        EffectSet effects,
        List<HirExpr> body,
        int line
) {
    public static final int MAX_SPREAD = 8;

    public HirFunction {
        declaredNames = declaredNames != null ? declaredNames.clone() : new String[0];
        declaredVars = List.copyOf(declaredVars);
        capturedVars = List.copyOf(capturedVars);
        params = List.copyOf(params);
        returnKind = returnKind != null ? returnKind : ScopeAnalysis.PrimitiveKind.NONE;
        effects = effects != null ? effects : EffectSet.EMPTY;
        body = List.copyOf(body);
    }

    public boolean isCompiledClassMethod() {
        return ownerClassId >= 0;
    }

    public String[] paramNames() {
        String[] names = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            names[i] = params.get(i).name();
        }
        return names;
    }

    public boolean isSpread() {
        return params.size() <= MAX_SPREAD;
    }

    public boolean hasSpec() {
        if (!isSpread()) {
            return false;
        }
        boolean hasPrimitive = returnKind.isPrimitive();
        int slots = 3;
        for (HirParam param : params) {
            if (param.kind().isPrimitive()) {
                hasPrimitive = true;
            }
            slots += param.kind().slots();
        }
        return hasPrimitive && slots <= 250;
    }

    public HirFunction withBody(List<HirExpr> newBody) {
        return new HirFunction(id, name, jvmMethodName, ownerClassId, memberName, namespaceScope,
                declaredNames, declaredVars, capturedVars, params, requiredParams, returnKind,
                bodyWideningRank, effects, newBody, line);
    }

    public HirFunction withBodyAndDeclaredVars(List<HirExpr> newBody, List<HirVar> newDeclaredVars) {
        return new HirFunction(id, name, jvmMethodName, ownerClassId, memberName, namespaceScope,
                declaredNames, newDeclaredVars, capturedVars, params, requiredParams, returnKind,
                bodyWideningRank, effects, newBody, line);
    }

    public HirFunction withParamsAndBody(List<HirParam> newParams, List<HirExpr> newBody) {
        return new HirFunction(id, name, jvmMethodName, ownerClassId, memberName, namespaceScope,
                declaredNames, declaredVars, capturedVars, newParams, requiredParams, returnKind,
                bodyWideningRank, effects, newBody, line);
    }
}
