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
        int keywordOnlyStart,
        int restIndex,
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

    public int optionalCount() {
        int count = 0;
        for (HirParam p : params) {
            if (p.defaultValue() != null) {
                count++;
            }
        }
        return count;
    }

    /** Bitmask of parameter indices (for {@code i < 32}) that have a default value. */
    public int optionalParamBits() {
        int bits = 0;
        for (int i = 0; i < params.size() && i < 32; i++) {
            if (params.get(i).defaultValue() != null) {
                bits |= (1 << i);
            }
        }
        return bits;
    }

    public boolean hasOptionalMask() {
        int opt = optionalCount();
        return opt > 0 && opt <= 32 && params.size() <= 32;
    }

    public boolean isSpread() {
        return params.size() <= MAX_SPREAD;
    }

    public boolean hasSpec() {
        if (!isSpread()) {
            return false;
        }
        int opt = optionalCount();
        if (opt > 32 || (opt > 0 && params.size() > 32)) {
            return false;
        }
        boolean hasPrimitive = returnKind.isPrimitive();
        int slots = 3 + (opt > 0 ? 1 : 0);
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
                declaredNames, declaredVars, capturedVars, params, requiredParams, keywordOnlyStart, restIndex, returnKind,
                bodyWideningRank, effects, newBody, line);
    }

    public HirFunction withBodyAndDeclaredVars(List<HirExpr> newBody, List<HirVar> newDeclaredVars) {
        return new HirFunction(id, name, jvmMethodName, ownerClassId, memberName, namespaceScope,
                declaredNames, newDeclaredVars, capturedVars, params, requiredParams, keywordOnlyStart, restIndex, returnKind,
                bodyWideningRank, effects, newBody, line);
    }

    public HirFunction withParamsAndBody(List<HirParam> newParams, List<HirExpr> newBody) {
        return new HirFunction(id, name, jvmMethodName, ownerClassId, memberName, namespaceScope,
                declaredNames, declaredVars, capturedVars, newParams, requiredParams, keywordOnlyStart, restIndex, returnKind,
                bodyWideningRank, effects, newBody, line);
    }
}
