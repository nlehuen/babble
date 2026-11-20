package org.babblelang.ir.hir;

import org.babblelang.analysis.ScopeAnalysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained, ANTLR-free High-Level Typed IR ({@code HIR}) of a compiled Babble module or script
 * (BIP014-P1, BIP014-D8).
 */
public record HirModule(
        String className,
        String sourceName,
        int startLine,
        String[] exports,
        String[] topLevelDeclaredNames,
        List<HirExpr> topLevelStatements,
        Map<Integer, HirFunction> functions,
        Map<Integer, HirClass> classes,
        Map<Integer, HirInterface> interfaces,
        Map<String, ScopeAnalysis.TypeHierarchyInfo> typeHierarchies,
        Map<String, String> allInterfaceJvmNames,
        Map<String, String> allClassJvmNames,
        Map<String, String> ownInterfaceJvmNames,
        Map<String, String> ownClassJvmNames,
        int nextVarId,
        int nextOwnerId
) {
    public HirModule {
        exports = exports != null ? exports.clone() : new String[0];
        topLevelDeclaredNames = topLevelDeclaredNames != null ? topLevelDeclaredNames.clone() : new String[0];
        topLevelStatements = List.copyOf(topLevelStatements);
        functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        interfaces = Collections.unmodifiableMap(new LinkedHashMap<>(interfaces));
        typeHierarchies = Collections.unmodifiableMap(new LinkedHashMap<>(typeHierarchies));
        allInterfaceJvmNames = Collections.unmodifiableMap(new LinkedHashMap<>(allInterfaceJvmNames));
        allClassJvmNames = Collections.unmodifiableMap(new LinkedHashMap<>(allClassJvmNames));
        ownInterfaceJvmNames = Collections.unmodifiableMap(new LinkedHashMap<>(ownInterfaceJvmNames));
        ownClassJvmNames = Collections.unmodifiableMap(new LinkedHashMap<>(ownClassJvmNames));
    }

    public HirFunction function(int id) {
        return functions.get(id);
    }

    public HirClass clazz(int id) {
        return classes.get(id);
    }

    public HirInterface iface(int id) {
        return interfaces.get(id);
    }

    public ScopeAnalysis.TypeHierarchyInfo typeHierarchy(String id) {
        return typeHierarchies.get(id);
    }

    public HirModule withUpdates(
            List<HirExpr> newTopLevelStatements,
            Map<Integer, HirFunction> newFunctions,
            Map<Integer, HirClass> newClasses,
            Map<Integer, HirInterface> newInterfaces,
            int newNextVarId,
            int newNextOwnerId
    ) {
        return new HirModule(className, sourceName, startLine, exports, topLevelDeclaredNames,
                newTopLevelStatements, newFunctions, newClasses, newInterfaces, typeHierarchies,
                allInterfaceJvmNames, allClassJvmNames, ownInterfaceJvmNames, ownClassJvmNames,
                newNextVarId, newNextOwnerId);
    }
}
