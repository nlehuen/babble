package org.babblelang.ir.hir;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.runtime.ClassShape;

/**
 * Compiled {@code class}, {@code record}, or {@code object} declaration in {@link HirModule}
 * (BIP014-I1).
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
        PrimitiveKind[] memberKinds,
        int[] primDefBits,
        int primDefCount,
        Map<String, Integer> methodFunctionIds,
        Map<String, List<Integer>> overloadMethodFunctionIds,
        List<HirTypeRef> implementedTypes,
        List<HirExpr> body,
        List<HirConstructor> constructors,
        boolean allDefFieldsFinalSafe,
        HirStaticBlock staticBlock,
        boolean fastRecord,
        int line) {
    public HirClass {
        implementedJvmInterfaces = List.copyOf(implementedJvmInterfaces);
        javaInterfaces = List.copyOf(javaInterfaces);
        declaredVars = List.copyOf(declaredVars);
        capturedVars = List.copyOf(capturedVars);
        params = List.copyOf(params);
        memberKinds = memberKinds != null ? memberKinds.clone() : new PrimitiveKind[0];
        primDefBits = primDefBits != null ? primDefBits.clone() : new int[0];
        methodFunctionIds = Collections.unmodifiableMap(new LinkedHashMap<>(methodFunctionIds));
        Map<String, List<Integer>> ovCopy = new LinkedHashMap<>();
        if (overloadMethodFunctionIds != null) {
            overloadMethodFunctionIds.forEach((k, v) -> ovCopy.put(k, List.copyOf(v)));
        }
        overloadMethodFunctionIds = Collections.unmodifiableMap(ovCopy);
        implementedTypes = List.copyOf(implementedTypes);
        body = List.copyOf(body);
        constructors = constructors != null ? List.copyOf(constructors) : List.of();
    }

    /** Whether any constructor has an explicit {@code init} block or multiple overloads. */
    public boolean hasExplicitConstructors() {
        if (constructors.size() > 1) {
            return true;
        }
        if (constructors.size() == 1) {
            HirConstructor c = constructors.get(0);
            return !c.isPrimary() || !c.body().isEmpty() || c.isDelegating();
        }
        return false;
    }

    /** Stable target-neutral Babble type identifier ({@code BIP025-D3}). */
    public String typeId() {
        return shape != null ? shape.id() : null;
    }

    /** Whether this class/object emits a dedicated plain JVM class. */
    public boolean hasCompiledClass() {
        return jvmName != null;
    }

    public String methodName(String member) {
        return "m$" + member;
    }

    public HirClass withBodyAndStatic(
            List<HirParam> newParams, List<HirExpr> newBody, HirStaticBlock newStatic) {
        return withBodyAndStatic(newParams, newBody, constructors, newStatic);
    }

    public HirClass withBodyAndStatic(
            List<HirParam> newParams,
            List<HirExpr> newBody,
            List<HirConstructor> newConstructors,
            HirStaticBlock newStatic) {
        return new HirClass(
                id,
                isObject,
                shape,
                jvmName,
                implementedJvmInterfaces,
                javaInterfaces,
                declaredVars,
                capturedVars,
                newParams,
                memberKinds,
                primDefBits,
                primDefCount,
                methodFunctionIds,
                overloadMethodFunctionIds,
                implementedTypes,
                newBody,
                newConstructors,
                allDefFieldsFinalSafe,
                newStatic,
                fastRecord,
                line);
    }
}
