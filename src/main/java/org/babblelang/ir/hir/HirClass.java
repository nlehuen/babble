package org.babblelang.ir.hir;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.runtime.ClassShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled {@code class}, {@code record}, or {@code object} declaration in {@link HirModule}
 * (BIP014-P1).
 */
public record HirClass(
        int id,
        boolean isObject,
        ClassShape shape,
        String jvmName,
        List<String> implementedJvmInterfaces,
        List<Class<?>> javaInterfaces,
        List<HirVar> declaredVars,
        List<HirVar> capturedVars,
        List<HirParam> params,
        ScopeAnalysis.PrimitiveKind[] memberKinds,
        int[] primDefBits,
        int primDefCount,
        Map<String, Integer> methodFunctionIds,
        Map<String, List<Integer>> overloadMethodFunctionIds,
        List<HirTypeRef> implementedTypes,
        List<HirExpr> body,
        HirStaticBlock staticBlock,
        int line
) {
    public HirClass {
        implementedJvmInterfaces = List.copyOf(implementedJvmInterfaces);
        javaInterfaces = List.copyOf(javaInterfaces);
        declaredVars = List.copyOf(declaredVars);
        capturedVars = List.copyOf(capturedVars);
        params = List.copyOf(params);
        memberKinds = memberKinds != null ? memberKinds.clone() : new ScopeAnalysis.PrimitiveKind[0];
        primDefBits = primDefBits != null ? primDefBits.clone() : new int[0];
        methodFunctionIds = Collections.unmodifiableMap(new LinkedHashMap<>(methodFunctionIds));
        Map<String, List<Integer>> ovCopy = new LinkedHashMap<>();
        if (overloadMethodFunctionIds != null) {
            overloadMethodFunctionIds.forEach((k, v) -> ovCopy.put(k, List.copyOf(v)));
        }
        overloadMethodFunctionIds = Collections.unmodifiableMap(ovCopy);
        implementedTypes = List.copyOf(implementedTypes);
        body = List.copyOf(body);
    }

    /** Whether this class/object emits a dedicated plain JVM class. */
    public boolean hasCompiledClass() {
        return jvmName != null;
    }

    public String methodName(String member) {
        return "m$" + member;
    }

    public HirClass withBodyAndStatic(List<HirParam> newParams, List<HirExpr> newBody, HirStaticBlock newStatic) {
        return new HirClass(id, isObject, shape, jvmName, implementedJvmInterfaces, javaInterfaces,
                declaredVars, capturedVars, newParams, memberKinds, primDefBits, primDefCount, methodFunctionIds,
                overloadMethodFunctionIds, implementedTypes, newBody, newStatic, line);
    }
}
