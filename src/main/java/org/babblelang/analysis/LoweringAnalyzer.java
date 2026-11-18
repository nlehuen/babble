package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-convergence lowering pass ({@code BIP010-D5}) that runs once when
 * {@link ScopeAnalysis#typeDiagnostics()} is empty to publish {@code RM001}
 * primitive kinds, definite-initialization flags, and static dispatch targets,
 * {@code RM003} JVM interface hierarchies, interface call targets, and {@code is}
 * singleton targets, and {@code T9} runtime check descriptors.
 */
final class LoweringAnalyzer {
    private LoweringAnalyzer() {
    }

    static void publish(
            BlockScopes pass,
            BabbleParser.FileContext file,
            TypeChecker checker,
            Map<ParserRuleContext, Type> types,
            Map<ParserRuleContext, Type> declarationTypes,
            Map<BabbleParser.TypeContext, Type> annotations,
            Map<ParserRuleContext, Type> inferredDefs,
            Map<ParserRuleContext, Type> inferredMembers,
            Map<ParserRuleContext, Type> inferredParams,
            Set<BabbleParser.FunctionLiteralContext> functions,
            Set<ParserRuleContext> widened,
            Map<ParserRuleContext, String> callChecks,
            Map<BabbleParser.CallContext, ScopeAnalysis.JavaCallTarget> javaCallTargets,
            Map<BabbleParser.CallContext, ScopeAnalysis.JavaConstructorTarget> javaConstructorTargets,
            Map<ParserRuleContext, ScopeAnalysis.JavaFieldTarget> javaFieldTargets,
            Map<BabbleParser.SelectorContext, Class<?>> arrayLengthSelectors,
            Map<BabbleParser.SelectorContext, ScopeAnalysis.ModuleExportTarget> moduleExportTargets,
            Map<BabbleParser.CallContext, BabbleParser.FunctionLiteralContext> babbleCallFunctions,
            Map<BabbleParser.CallContext, ScopeAnalysis.FunctionSignature> babbleCallSignatures,
            Map<BabbleParser.CallContext, ScopeAnalysis.InterfaceCallTarget> interfaceCallTargets) {
        if (!pass.analysis.typeDiagnostics().isEmpty()) {
            return;
        }
        recordChecksTree(file, pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        for (ParserRuleContext ctx : widened) {
            pass.analysis.widenToDouble(ctx);
        }
        for (Map.Entry<ParserRuleContext, String> entry : callChecks.entrySet()) {
            pass.analysis.setCheckedType(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.setExpressionKind(entry.getKey(), ScopeAnalysis.PrimitiveKind.of(entry.getValue()));
            }
            if (entry.getKey() instanceof BabbleParser.IsExpressionContext isCtx && checker.inFile(isCtx)) {
                if (checker.isBuiltinTarget(isCtx) == null) {
                    Type rightType = types.get(isCtx.right);
                    if (rightType != null && !TypeChecker.isUnresolved(rightType) && rightType instanceof Type.ClassValue cv) {
                        if (checker.staticSingletonTypeNode(isCtx.right) == cv.node()) {
                            pass.analysis.setIsTarget(isCtx, new ScopeAnalysis.IsTarget(cv.node().id, cv.node().kind));
                        }
                    }
                }
            }
        }
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (!checker.inFile(decl)) {
                continue;
            }
            if (decl instanceof BabbleParser.DefExpressionContext def) {
                Type t = checker.declaredType(def);
                if (t == null) {
                    ScopeAnalysis.DefKind defKind = pass.analysis.defTarget(def).kind();
                    t = (defKind == ScopeAnalysis.DefKind.MEMBER || defKind == ScopeAnalysis.DefKind.STATIC)
                            ? inferredMembers.get(def) : inferredDefs.get(def);
                }
                if (t != null) {
                    pass.analysis.setDeclarationKind(def, ScopeAnalysis.PrimitiveKind.of(t));
                }
            } else if (decl instanceof BabbleParser.ParameterDeclarationContext param) {
                Type t = declarationTypes.get(param);
                if (t == null && param.type() != null) {
                    t = annotations.get(param.type());
                }
                if (t == null) {
                    t = inferredParams.get(param);
                }
                if (t != null) {
                    pass.analysis.setDeclarationKind(param, ScopeAnalysis.PrimitiveKind.of(t));
                }
            }
        }
        for (TypeNode typeNode : pass.types) {
            if (!checker.inFile(typeNode.ctx)) {
                continue;
            }
            for (TypeNode.Member member : typeNode.members.values()) {
                if (member.declaration == null || !checker.inFile(member.declaration) || member.function != null) {
                    continue;
                }
                Type t = checker.declaredType(member.declaration);
                if (t == null && member.declaration instanceof BabbleParser.ParameterDeclarationContext param) {
                    t = declarationTypes.get(param);
                    if (t == null) {
                        t = inferredParams.get(param);
                    }
                } else if (t == null && member.declaration instanceof BabbleParser.DefExpressionContext def) {
                    t = inferredMembers.get(def);
                }
                if (t != null) {
                    pass.analysis.setDeclarationKind(member.declaration, ScopeAnalysis.PrimitiveKind.of(t));
                }
            }
        }
        for (BabbleParser.FunctionLiteralContext fn : functions) {
            if (types.get(fn) instanceof Type.Function tf) {
                pass.analysis.setFunctionReturnKind(fn, ScopeAnalysis.PrimitiveKind.of(tf.result()));
            }
        }
        publishDefinitelyDefined(pass, checker);
        javaCallTargets.forEach(pass.analysis::setJavaCallTarget);
        javaConstructorTargets.forEach(pass.analysis::setJavaConstructorTarget);
        javaFieldTargets.forEach(pass.analysis::setJavaFieldTarget);
        arrayLengthSelectors.forEach(pass.analysis::setArrayLengthClass);
        moduleExportTargets.forEach(pass.analysis::setModuleExportTarget);
        babbleCallFunctions.forEach(pass.analysis::setBabbleCallFunction);
        babbleCallSignatures.forEach(pass.analysis::setBabbleCallSignature);
        interfaceCallTargets.forEach(pass.analysis::setInterfaceCallTarget);
        publishTypeHierarchies(pass, checker);
        if (pass.analysis.moduleInterface() != null) {
            pass.analysis.moduleInterface().setTypeHierarchies(pass.analysis.typeHierarchies());
        }
    }

    private static void publishDefinitelyDefined(BlockScopes pass, TypeChecker checker) {
        Resolution resolution = pass.analysis.resolution();
        if (resolution == null) {
            return;
        }
        Set<Resolution.Variable> unsafe = new HashSet<>();
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            Resolution.Variable v = resolution.variable(occ.ctx());
            if (v != null && v.kind() == Resolution.Variable.Kind.DEFINITION
                    && v.declaration() instanceof BabbleParser.DefExpressionContext def
                    && occ.ctx() != def
                    && occ.ctx().getStart().getStartIndex() <= def.getStop().getStopIndex()) {
                unsafe.add(v);
            }
        }
        for (Map.Entry<ParserRuleContext, ScopeNode> entry : pass.scopeNodes.entrySet()) {
            ParserRuleContext method = entry.getKey();
            if (method instanceof BabbleParser.FunctionLiteralContext
                    || method instanceof BabbleParser.ClassExpressionContext
                    || method instanceof BabbleParser.ObjectExpressionContext) {
                for (Resolution.Variable cap : resolution.captured(method)) {
                    if (cap.kind() == Resolution.Variable.Kind.DEFINITION
                            && cap.declaration() instanceof BabbleParser.DefExpressionContext def
                            && method.getStart().getStartIndex() <= def.getStop().getStopIndex()) {
                        unsafe.add(cap);
                    }
                }
            }
        }
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (decl instanceof BabbleParser.DefExpressionContext def && checker.inFile(def)) {
                Resolution.Variable v = resolution.variable(def);
                if (v != null && !unsafe.contains(v)) {
                    pass.analysis.markDefinitelyDefined(def);
                }
            }
        }
    }

    private static void recordChecksTree(
            ParseTree tree,
            BlockScopes pass,
            TypeChecker checker,
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> inferredDefs,
            Map<ParserRuleContext, Type> inferredMembers,
            Set<ParserRuleContext> widened) {
        if (tree instanceof BabbleParser.TypeContext || tree instanceof BabbleParser.TypeListContext
                || tree instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        recordCheck(tree, pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        for (int i = 0; i < tree.getChildCount(); i++) {
            recordChecksTree(tree.getChild(i), pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        }
    }

    /**
     * Records the declared type each boundary checks at runtime : parameters of functions
     * and class headers, results of functions, defs, and unqualified assignments to
     * annotated names. Members and statics also carry theirs in the class shape, for
     * assignments through a receiver.
     */
    private static void recordCheck(
            ParseTree tree,
            BlockScopes pass,
            TypeChecker checker,
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> inferredDefs,
            Map<ParserRuleContext, Type> inferredMembers,
            Set<ParserRuleContext> widened) {
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter) {
            Type type = parameter.type() != null
                    ? checker.annotation(parameter.type(), checker.scopeOf(parameter))
                    : declarationTypes.get(parameter);
            if (type != null && TypeChecker.containsType(type, Type.DOUBLE) && !TypeChecker.containsType(type, Type.INT)) {
                widened.add(parameter);
            }
            if (parameter.type() != null || (type != null && TypeChecker.containsType(type, Type.DOUBLE) && !TypeChecker.containsType(type, Type.INT))) {
                String descriptor = descriptor(type);
                pass.analysis.setCheckedType(parameter, descriptor);
                if (parameter.getParent().getParent() instanceof BabbleParser.ClassExpressionContext cls
                        && (parameter.DEF() != null || pass.typeNodes.get(cls).kind == ClassShape.Kind.VALUE_CLASS)) {
                    putType(pass.typeNodes.get(cls).memberTypes, parameter.ID().getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.FunctionLiteralContext function && function.type() != null) {
            pass.analysis.setCheckedType(function, descriptor(checker.annotation(function.type(), checker.scopeOf(function))));
        } else if (tree instanceof BabbleParser.DefExpressionContext def) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
            if (kind == ScopeAnalysis.DefKind.DEFAULT || kind == ScopeAnalysis.DefKind.REQUIRED) {
                return;
            }
            String descriptor = null;
            if (def.type() != null) {
                descriptor = descriptor(checker.annotation(def.type(), checker.scopeOf(def)));
            } else {
                Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                        ? inferredMembers.get(def) : inferredDefs.get(def);
                if (inferred != null && TypeChecker.containsType(inferred, Type.DOUBLE) && !TypeChecker.containsType(inferred, Type.INT)) {
                    descriptor = descriptor(inferred);
                }
            }
            if (descriptor != null) {
                pass.analysis.setCheckedType(def, descriptor);
                if (kind == ScopeAnalysis.DefKind.MEMBER) {
                    putType(checker.scopeOf(def).type.memberTypes, def.name.getText(), descriptor);
                } else if (kind == ScopeAnalysis.DefKind.STATIC) {
                    putType(checker.scopeOf(def).type.staticTypes, def.name.getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null
                && checker.resolveName(assign, assign.name.getText(), checker.occurrenceScopeOf(assign)) instanceof ParserRuleContext declaration) {
            Type declared = checker.declaredType(declaration);
            if (declared != null) {
                pass.analysis.setCheckedType(assign, descriptor(declared));
            } else {
                Type inferred = checker.typeOfDeclaration(declaration);
                if (inferred != null && TypeChecker.containsType(inferred, Type.DOUBLE) && !TypeChecker.containsType(inferred, Type.INT)) {
                    pass.analysis.setCheckedType(assign, descriptor(inferred));
                }
            }
        }
    }

    private static void putType(Map<String, String> types, String name, String descriptor) {
        if (descriptor != null) {
            types.put(name, descriptor);
        }
    }

    /** How the runtime checks a type ({@code Types}), or null when it needs no check. */
    static String descriptor(Type type) {
        if (type == Type.INT || type == Type.DOUBLE || type == Type.BOOL || type == Type.STRING
                || type == Type.NULL || type == Type.ANY) {
            return type + "|" + type + "|";
        }
        if (type == Type.NULLABLE_ANY || type instanceof Type.TypeVar) {
            return null;
        }
        if (type instanceof Type.Union union) {
            StringBuilder payload = new StringBuilder();
            for (Type member : union.members()) {
                String sub = descriptor(member);
                if (sub == null) {
                    return null;
                }
                if (!payload.isEmpty()) {
                    payload.append(',');
                }
                payload.append(org.babblelang.runtime.Types.encodeComponent(sub));
            }
            return "union|" + union + "|" + payload;
        }
        if (type instanceof Type.Function) {
            return "fn|" + type + "|";
        }
        if (type instanceof Type.Instance instance) {
            return "babble|" + instance.node().name + "|" + instance.node().id;
        }
        if (type instanceof Type.JavaInstance java) {
            return "java|" + java.type().getName() + "|" + java.type().getName();
        }
        if (type instanceof Type.JavaArray array) {
            return "java|" + array + "|" + array.javaClass().getName();
        }
        return null;
    }

    private static void publishTypeHierarchies(BlockScopes pass, TypeChecker checker) {
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && node.isInterface()) {
                pass.analysis.setTypeHierarchy(node.id, buildTypeHierarchyInfo(node, checker));
            }
        }
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && !node.isInterface()) {
                pass.analysis.setTypeHierarchy(node.id, buildTypeHierarchyInfo(node, checker));
            }
        }
    }

    private static ScopeAnalysis.TypeHierarchyInfo buildTypeHierarchyInfo(TypeNode node, TypeChecker checker) {
        Set<ScopeAnalysis.ParentRef> seenDirect = new LinkedHashSet<>();
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode tn) {
                seenDirect.add(new ScopeAnalysis.ParentRef(tn.id, null));
            } else if (parent instanceof Class<?> jc) {
                seenDirect.add(new ScopeAnalysis.ParentRef(null, jc));
            }
        }
        List<ScopeAnalysis.ParentRef> directParents = List.copyOf(seenDirect);

        Set<String> allBabbleInterfaceIds = new LinkedHashSet<>();
        Set<Class<?>> allJavaInterfaces = new LinkedHashSet<>();
        Set<TypeNode> visitedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        visitedNodes.add(node);
        collectAncestorInterfaces(node, allBabbleInterfaceIds, allJavaInterfaces, visitedNodes);

        Map<String, ScopeAnalysis.MemberSpec> interfaceMembers = new LinkedHashMap<>();
        if (node.isInterface()) {
            for (TypeNode.Member member : node.members.values()) {
                boolean hasDefault = member.hasFunction;
                boolean required = !hasDefault;
                Type mt = checker.memberType(node, member);
                boolean callable = mt instanceof Type.Function;
                ScopeAnalysis.FunctionSignature sig = callable ? uninstantiatedSignature((Type.Function) mt) : null;
                interfaceMembers.put(member.name,
                        new ScopeAnalysis.MemberSpec(member.name, hasDefault, required, callable, sig));
            }
        } else {
            Map<String, OwnerMember> collected = new LinkedHashMap<>();
            Set<TypeNode> visitedIfaces = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object parent : node.parents) {
                if (parent instanceof TypeNode iface) {
                    collectInterfaceMembers(iface, collected, visitedIfaces);
                }
            }
            Map<String, TypeChecks.Provider> allInterfaceDefaults = new LinkedHashMap<>();
            for (Object parent : node.parents) {
                if (parent instanceof TypeNode iface && iface.defaults != null) {
                    for (TypeChecks.Provider provider : iface.defaults.values()) {
                        allInterfaceDefaults.putIfAbsent(provider.name(), provider);
                    }
                }
            }
            for (OwnerMember om : collected.values()) {
                TypeNode.Member member = om.member();
                TypeChecks.Provider provider = allInterfaceDefaults.get(member.name);
                boolean hasDefault = member.hasFunction || (provider != null && provider.owner() instanceof TypeNode);
                boolean required = !hasDefault;
                Type mt = checker.memberType(om.owner(), member);
                boolean callable = mt instanceof Type.Function;
                ScopeAnalysis.FunctionSignature sig = callable ? uninstantiatedSignature((Type.Function) mt) : null;
                interfaceMembers.put(member.name,
                        new ScopeAnalysis.MemberSpec(member.name, hasDefault, required, callable, sig));
            }
        }
        return new ScopeAnalysis.TypeHierarchyInfo(
                node.id,
                node.kind,
                node.name,
                directParents,
                Collections.unmodifiableSet(allBabbleInterfaceIds),
                Collections.unmodifiableSet(allJavaInterfaces),
                Collections.unmodifiableMap(interfaceMembers));
    }

    private static void collectAncestorInterfaces(TypeNode node, Set<String> babbleIds, Set<Class<?>> javaIfaces,
                                                  Set<TypeNode> visited) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                if (visited.add(parentNode)) {
                    babbleIds.add(parentNode.id);
                    collectAncestorInterfaces(parentNode, babbleIds, javaIfaces, visited);
                }
            } else if (parent instanceof Class<?> javaIface) {
                collectJavaInterfaces(javaIface, javaIfaces);
            }
        }
    }

    private static void collectJavaInterfaces(Class<?> javaIface, Set<Class<?>> out) {
        if (javaIface == null || !javaIface.isInterface() || !out.add(javaIface)) {
            return;
        }
        for (Class<?> parent : javaIface.getInterfaces()) {
            collectJavaInterfaces(parent, out);
        }
    }

    private record OwnerMember(TypeNode owner, TypeNode.Member member) {
    }

    private static void collectInterfaceMembers(TypeNode iface, Map<String, OwnerMember> out, Set<TypeNode> visited) {
        if (!visited.add(iface)) {
            return;
        }
        for (TypeNode.Member member : iface.members.values()) {
            out.putIfAbsent(member.name, new OwnerMember(iface, member));
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode parentIface) {
                collectInterfaceMembers(parentIface, out, visited);
            }
        }
    }

    static ScopeAnalysis.FunctionSignature uninstantiatedSignature(Type.Function fn) {
        if (fn == null) {
            return null;
        }
        List<Type.Parameter> params = fn.parameters();
        if (params.size() > 200) {
            return null;
        }
        ScopeAnalysis.PrimitiveKind[] paramKinds = new ScopeAnalysis.PrimitiveKind[params.size()];
        int slots = 2;
        for (int i = 0; i < params.size(); i++) {
            Type pt = params.get(i).type();
            ScopeAnalysis.PrimitiveKind pk = pt instanceof Type.TypeVar
                    ? ScopeAnalysis.PrimitiveKind.NONE
                    : ScopeAnalysis.PrimitiveKind.of(pt);
            paramKinds[i] = pk;
            slots += pk.slots();
        }
        if (slots > 200) {
            return null;
        }
        Type rt = fn.result();
        ScopeAnalysis.PrimitiveKind rk = rt instanceof Type.TypeVar
                ? ScopeAnalysis.PrimitiveKind.NONE
                : ScopeAnalysis.PrimitiveKind.of(rt);
        return new ScopeAnalysis.FunctionSignature(paramKinds, rk);
    }
}
