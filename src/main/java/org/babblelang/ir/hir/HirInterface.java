package org.babblelang.ir.hir;

import org.babblelang.runtime.ClassShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled {@code interface} declaration in {@link HirModule} (BIP014-I1).
 */
public record HirInterface(
        int id,
        ClassShape shape,
        String jvmName,
        List<String> extendedJvmInterfaces,
        Map<String, Integer> defaultFunctionIds,
        Map<String, List<Integer>> defaultOverloadFunctionIds,
        List<HirTypeRef> extendedTypes,
        List<HirExpr> body,
        HirStaticBlock staticBlock,
        int line
) {
    public HirInterface {
        extendedJvmInterfaces = List.copyOf(extendedJvmInterfaces);
        defaultFunctionIds = Collections.unmodifiableMap(new LinkedHashMap<>(defaultFunctionIds));
        Map<String, List<Integer>> ovCopy = new LinkedHashMap<>();
        if (defaultOverloadFunctionIds != null) {
            defaultOverloadFunctionIds.forEach((k, v) -> ovCopy.put(k, List.copyOf(v)));
        }
        defaultOverloadFunctionIds = Collections.unmodifiableMap(ovCopy);
        extendedTypes = List.copyOf(extendedTypes);
        body = List.copyOf(body);
    }

    public HirInterface withBodyAndStatic(List<HirExpr> newBody, HirStaticBlock newStatic) {
        return new HirInterface(id, shape, jvmName, extendedJvmInterfaces, defaultFunctionIds,
                defaultOverloadFunctionIds, extendedTypes, newBody, newStatic, line);
    }
}
