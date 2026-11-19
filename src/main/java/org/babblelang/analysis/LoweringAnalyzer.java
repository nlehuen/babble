package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private final BlockScopes pass;
    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> types;
    private final StaticRefs refs;

    private LoweringAnalyzer(BlockScopes pass, TypeChecker checker) {
        this.pass = pass;
        this.checker = checker;
        this.types = checker.types();
        this.refs = new StaticRefs(pass, checker);
    }

    /** Publishes the lowering facts of a file that type-checked cleanly ({@code BIP010-D5}). */
    static void publish(BlockScopes pass, BabbleParser.FileContext file, TypeChecker checker) {
        if (pass.analysis.typeDiagnostics().isEmpty()) {
            new LoweringAnalyzer(pass, checker).run(file);
        }
    }

    private void run(BabbleParser.FileContext file) {
        Map<ParserRuleContext, Type> declarationTypes = checker.declarationTypes();
        Map<ParserRuleContext, Type> inferredDefs = checker.inferredDefs();
        Map<ParserRuleContext, Type> inferredMembers = checker.inferredMembers();
        Map<ParserRuleContext, Type> inferredParams = checker.inferredParams();
        Map<ParserRuleContext, Integer> widened = checker.widened();
        recordChecksTree(file, pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        for (Map.Entry<ParserRuleContext, Integer> entry : widened.entrySet()) {
            pass.analysis.widenNumeric(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, Object> entry : checker.resolvedLiterals().entrySet()) {
            pass.analysis.setResolvedLiteral(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, String> entry : checker.callChecks().entrySet()) {
            pass.analysis.setCheckedType(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.setExpressionKind(entry.getKey(), ScopeAnalysis.PrimitiveKind.of(entry.getValue()));
            }
            if (entry.getKey() instanceof BabbleParser.IsExpressionContext isCtx && checker.inFile(isCtx)
                    && checker.narrowing().isBuiltinTarget(isCtx) == null
                    && types.get(isCtx.right) instanceof Type.ClassValue cv
                    && refs.singletonTypeNode(isCtx.right) == cv.node()) {
                pass.analysis.setIsTarget(isCtx, new ScopeAnalysis.IsTarget(cv.node().id, cv.node().kind));
            }
        }
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (!checker.inFile(decl)) {
                continue;
            }
            if (decl instanceof BabbleParser.DefExpressionContext def) {
                Type t = checker.annotator().declaredType(def);
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
                    t = checker.annotations().get(param.type());
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
                Type t = checker.annotator().declaredType(member.declaration);
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
        for (BabbleParser.FunctionLiteralContext fn : checker.functions()) {
            if (types.get(fn) instanceof Type.Function tf) {
                pass.analysis.setFunctionReturnKind(fn, ScopeAnalysis.PrimitiveKind.of(tf.result()));
            }
        }
        publishDefinitelyDefined(pass, checker);
        deriveTargets(file);
        publishTypeHierarchies(pass, checker);
        if (pass.analysis.moduleInterface() != null) {
            pass.analysis.moduleInterface().setTypeHierarchies(pass.analysis.typeHierarchies());
        }
    }

    // ---------------------------------------------------------------- static dispatch targets (RM001, RM003)

    /** Derives the static target of every selection, Java field write, and call from the converged types. */
    private void deriveTargets(ParseTree tree) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            deriveSelection(selector);
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace != null
                && JavaTypeBridge.assignedField(types.get(assign.namespace), ScopeAnalysis.assignName(assign)) instanceof JavaTypeBridge.AssignedField field) {
            ScopeAnalysis.JavaFieldTarget target = field.target(types.get(assign));
            if (target != null) {
                pass.analysis.setJavaFieldTarget(assign, target);
            }
        } else if (tree instanceof BabbleParser.CallContext call && checker.inFile(call)) {
            deriveCall(call);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            deriveTargets(tree.getChild(i));
        }
    }

    /** {@code receiver.name} : an indexed module export, an array's length, or a Java field. */
    private void deriveSelection(BabbleParser.SelectorContext selector) {
        if (selector.expression() == null || selector.safe != null || !(types.get(selector.expression()) instanceof Type receiver)) {
            return;
        }
        String name = ScopeAnalysis.selectorName(selector);
        if (receiver instanceof Type.Module module) {
            int index = module.module().exportIndex(name);
            if (module.module().summary() != null && module.module().exports().contains(name) && index >= 0) {
                pass.analysis.setModuleExportTarget(selector, new ScopeAnalysis.ModuleExportTarget(index, name));
            }
        } else if (receiver instanceof Type.JavaArray array && "length".equals(name)) {
            pass.analysis.setArrayLengthClass(selector, array.javaClass());
        } else {
            ScopeAnalysis.JavaFieldTarget target = fieldReadTarget(receiver, name, checker.algebra());
            if (target != null) {
                pass.analysis.setJavaFieldTarget(selector, target);
            }
        }
    }

    /** The static target of reading the Java field {@code receiver.name}, or null when it isn't one. */
    static ScopeAnalysis.JavaFieldTarget fieldReadTarget(Type receiver, String name, TypeAlgebra algebra) {
        JavaTypeBridge.JavaMember member;
        if (receiver instanceof Type.JavaClassValue value) {
            if (JavaTypeBridge.publicMemberClass(value.type(), name) != null) {
                return null;
            }
            member = JavaTypeBridge.javaMember(value.type(), List.of(), name, true, algebra);
        } else if (receiver instanceof Type.JavaInstance instance) {
            member = JavaTypeBridge.javaMember(instance.type(), instance.typeArguments(), name, false, algebra);
        } else if (receiver instanceof Type.JavaArray array && !"length".equals(name)) {
            member = JavaTypeBridge.javaMember(array.javaClass(), List.of(), name, false, algebra);
        } else {
            return null;
        }
        return member.error() == null ? member.fieldTarget() : null;
    }

    private void deriveCall(BabbleParser.CallContext call) {
        List<BabbleParser.CallParameterContext> arguments = call.callParameters().callParameter();
        boolean safe = call.expression() instanceof BabbleParser.SelectorContext sel && sel.safe != null;
        Method method = checker.callSolver().resolvedMethod(call);
        if (method != null && !safe) {
            deriveJavaMethodCall(method, arguments, call);
        }
        Constructor<?> constructor = checker.callSolver().resolvedConstructor(call);
        if (constructor != null && !safe) {
            deriveJavaConstructor(constructor, arguments, call);
        }
        if (safe || pass.modules.containsKey(call) || !(types.get(call.expression()) instanceof Type.Function function)) {
            return;
        }
        if (function.typeParameters().isEmpty()) {
            deriveBabbleCall(function, arguments, call);
            deriveInterfaceCall(arguments, call);
        } else if (types.get(call) != Type.ERROR) {
            deriveInterfaceCall(arguments, call);
        }
    }

    /** Whether the arguments are all positional and statically applicable to the Java parameters, for a direct call. */
    private boolean staticallyApplicable(Class<?>[] paramTypes, List<BabbleParser.CallParameterContext> arguments) {
        if (paramTypes.length != arguments.size()) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Type argType = types.get(arguments.get(i).expression());
            if (ScopeAnalysis.callParamName(arguments.get(i)) != null
                    || !JavaTypeBridge.guaranteedApplicable(paramTypes[i], argType)
                    || !JavaTypeBridge.supportedStaticJavaArg(paramTypes[i], argType)) {
                return false;
            }
        }
        return true;
    }

    private void deriveJavaConstructor(Constructor<?> ctor, List<BabbleParser.CallParameterContext> arguments,
                                       BabbleParser.CallContext call) {
        Class<?> java = ctor.getDeclaringClass();
        if (Modifier.isPublic(ctor.getModifiers()) && Modifier.isPublic(java.getModifiers())
                && JavaTypes.isAccessible(java) && staticallyApplicable(ctor.getParameterTypes(), arguments)) {
            pass.analysis.setJavaConstructorTarget(call, new ScopeAnalysis.JavaConstructorTarget(ctor));
        }
    }

    private void deriveJavaMethodCall(Method best, List<BabbleParser.CallParameterContext> arguments,
                                      BabbleParser.CallContext call) {
        if (!(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        Type receiverType = types.get(sel.expression());
        if (!(receiverType instanceof Type.JavaClassValue
                || receiverType instanceof Type.JavaInstance
                || receiverType instanceof Type.JavaArray
                || receiverType == Type.STRING)) {
            return;
        }
        Method accessible = JavaTypes.accessible(best);
        if (accessible == null
                || !Modifier.isPublic(accessible.getModifiers())
                || !Modifier.isPublic(accessible.getDeclaringClass().getModifiers())
                || !JavaTypes.isAccessible(accessible.getDeclaringClass())
                || !JavaTypeBridge.isBytecodeAccessible(accessible.getReturnType())) {
            return;
        }
        boolean isStatic = Modifier.isStatic(accessible.getModifiers());
        if (receiverType instanceof Type.JavaClassValue && !isStatic
                || !staticallyApplicable(accessible.getParameterTypes(), arguments)) {
            return;
        }
        boolean unwrapReceiver = !isStatic && receiverType != Type.STRING;
        pass.analysis.setJavaCallTarget(call, new ScopeAnalysis.JavaCallTarget(accessible, isStatic, unwrapReceiver));
    }

    /** A call of a known Babble function : its literal, for a direct call, and its primitive signature ({@code RM001}). */
    private void deriveBabbleCall(Type.Function function, List<BabbleParser.CallParameterContext> arguments,
                                  BabbleParser.CallContext ctx) {
        if (arguments.size() != function.parameters().size()) {
            return;
        }
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (ScopeAnalysis.callParamName(arg) != null) {
                return;
            }
        }
        ScopeAnalysis.PrimitiveKind[] paramKinds = new ScopeAnalysis.PrimitiveKind[arguments.size()];
        boolean hasPrimitive = false;
        int slots = 0;
        for (int i = 0; i < arguments.size(); i++) {
            ScopeAnalysis.PrimitiveKind kind = ScopeAnalysis.PrimitiveKind.of(function.parameters().get(i).type());
            paramKinds[i] = kind;
            slots += kind.slots();
            if (kind.isPrimitive()) {
                hasPrimitive = true;
            }
        }
        ScopeAnalysis.PrimitiveKind returnKind = ScopeAnalysis.PrimitiveKind.of(function.result());
        if (returnKind.isPrimitive()) {
            hasPrimitive = true;
        }
        if (slots <= 200) {
            BabbleParser.FunctionLiteralContext lit = checker.functionLiterals().get(function);
            if (lit != null && checker.inFile(lit) && checker.scopeOf(lit).memberOf == null) {
                BabbleParser.ExpressionContext calleeExpr = ctx.expression();
                while (calleeExpr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
                    calleeExpr = b.block().expression(0);
                }
                if (calleeExpr == lit) {
                    pass.analysis.setBabbleCallFunction(ctx, lit);
                } else if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                    Object resolved = TypeChecker.resolveName(sel, sel.ID().getText(), checker.occurrenceScope(sel));
                    if (resolved instanceof BabbleParser.DefExpressionContext def
                            && checker.inFile(def) && !checker.isReassigned(def) && TypeChecker.unwrapFunctionLiteral(def.value) == lit) {
                        pass.analysis.setBabbleCallFunction(ctx, lit);
                    }
                }
            }
            if (hasPrimitive) {
                pass.analysis.setBabbleCallSignature(ctx, new ScopeAnalysis.FunctionSignature(paramKinds, returnKind));
            }
        }
    }

    /** A call of a member of a Babble interface : its {@code INVOKEINTERFACE} target ({@code RM003}). */
    private void deriveInterfaceCall(List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        if (!checker.inFile(ctx) || !(ctx.expression() instanceof BabbleParser.SelectorContext sel)) {
            return;
        }
        if (sel.safe != null || sel.expression() == null || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        if (!(types.get(sel.expression()) instanceof Type.Instance recvInst) || !recvInst.node().isInterface()) {
            return;
        }
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (ScopeAnalysis.callParamName(arg) != null) {
                return;
            }
        }
        FoundInterfaceMember found = findDeclaringInterfaceMember(recvInst.node(), ScopeAnalysis.selectorName(sel));
        if (found == null) {
            return;
        }
        Type mt = checker.memberType(found.iface(), found.member());
        if (!(mt instanceof Type.Function uninstFn)) {
            return;
        }
        if (arguments.size() != uninstFn.parameters().size()) {
            return;
        }
        ScopeAnalysis.FunctionSignature sig = uninstantiatedSignature(uninstFn);
        if (sig == null) {
            return;
        }
        pass.analysis.setInterfaceCallTarget(ctx,
                new ScopeAnalysis.InterfaceCallTarget(found.iface().id, found.member().name, sig, true));
    }

    private record FoundInterfaceMember(TypeNode iface, TypeNode.Member member) {
    }

    private static FoundInterfaceMember findDeclaringInterfaceMember(TypeNode iface, String name) {
        return findDeclaringInterfaceMember(iface, name, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static FoundInterfaceMember findDeclaringInterfaceMember(TypeNode iface, String name, Set<TypeNode> visited) {
        if (iface == null || !iface.isInterface() || !visited.add(iface)) {
            return null;
        }
        TypeNode.Member member = iface.members.get(name);
        if (member != null) {
            return new FoundInterfaceMember(iface, member);
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode parentNode) {
                FoundInterfaceMember found = findDeclaringInterfaceMember(parentNode, name, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
            Map<ParserRuleContext, Integer> widened) {
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
            Map<ParserRuleContext, Integer> widened) {
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter) {
            Type type = parameter.type() != null
                    ? checker.annotator().annotation(parameter.type(), checker.scopeOf(parameter))
                    : declarationTypes.get(parameter);
            int targetRank = type != null ? TypeAlgebra.singleNumericRank(type) : -1;
            if (targetRank > 0) {
                widened.merge(parameter, targetRank, Math::max);
            }
            if (parameter.type() != null || targetRank > 0) {
                String descriptor = descriptor(type);
                pass.analysis.setCheckedType(parameter, descriptor);
                if (parameter.getParent().getParent() instanceof BabbleParser.ClassExpressionContext cls
                        && (parameter.DEF() != null || pass.typeNodes.get(cls).kind == ClassShape.Kind.VALUE_CLASS)) {
                    putType(pass.typeNodes.get(cls).memberTypes, parameter.ID().getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.FunctionLiteralContext function && function.type() != null) {
            pass.analysis.setCheckedType(function, descriptor(checker.annotator().annotation(function.type(), checker.scopeOf(function))));
        } else if (tree instanceof BabbleParser.DefExpressionContext def) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
            if (kind == ScopeAnalysis.DefKind.DEFAULT || kind == ScopeAnalysis.DefKind.REQUIRED) {
                return;
            }
            String descriptor = null;
            if (def.type() != null) {
                descriptor = descriptor(checker.annotator().annotation(def.type(), checker.scopeOf(def)));
            } else {
                Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                        ? inferredMembers.get(def) : inferredDefs.get(def);
                if (inferred != null && TypeAlgebra.singleNumericRank(inferred) > 0) {
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
            Type declared = checker.annotator().declaredType(declaration);
            if (declared != null) {
                pass.analysis.setCheckedType(assign, descriptor(declared));
            } else {
                Type inferred = checker.typeOfDeclaration(declaration);
                if (inferred != null && TypeAlgebra.singleNumericRank(inferred) > 0) {
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
        if (Type.isCoreScalar(type) || type == Type.NULL || type == Type.ANY) {
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
