package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

/**
 * Post-convergence lowering pass ({@code BIP010-D5}) that runs once when {@link
 * ScopeAnalysis#typeDiagnostics()} is empty to publish {@code RM001} primitive kinds,
 * definite-initialization flags, and static dispatch targets, {@code RM003} JVM interface
 * hierarchies, interface call targets, and {@code is} singleton targets, and {@code T9} runtime
 * check descriptors.
 */
final class LoweringAnalyzer {
    private final BlockScopes pass;
    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> types;
    private final TypeHierarchyIndex hierarchy;
    private final DispatchTargetDeriver dispatch;

    private LoweringAnalyzer(BlockScopes pass, TypeChecker checker) {
        this.pass = pass;
        this.checker = checker;
        this.types = checker.types();
        this.hierarchy = new TypeHierarchyIndex(pass, checker);
        this.dispatch = new DispatchTargetDeriver(pass, checker, hierarchy);
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
        LoweringMetadata lowering = pass.analysis.lowering();
        CallMetadata calls = pass.analysis.calls();

        recordChecksTree(
                file, pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        widened.forEach(lowering::widenNumeric);
        checker.resolvedLiterals().forEach(lowering::setResolvedLiteral);
        checker.callChecks().forEach(lowering::setCheckedType);

        publishExpressionMetadata(declarationTypes, widened);
        publishDeclarationKinds(declarationTypes, inferredDefs, inferredMembers, inferredParams);
        publishMemberDeclarationKinds(declarationTypes, inferredMembers, inferredParams);
        publishMatchTargets();
        for (Map.Entry<ParserRuleContext, TypeChecker.ResolvedOverloadCall> ovEntry :
                checker.resolvedOverloadCalls().entrySet()) {
            ParserRuleContext site = ovEntry.getKey();
            if (!(site instanceof BabbleParser.CallContext) && checker.inFile(site)) {
                dispatch.publishOperatorOverloadDispatch(site, ovEntry.getValue());
            }
        }
        publishFunctionMetadata();
        publishInFile(checker.overloadSelections(), calls::setOverloadSelection);
        publishInFile(checker.samTargets(), lowering::setJavaSamTarget);
        publishInFile(checker.forElemSamTargets(), lowering::setForElemSamTarget);
        publishInFile(checker.asSamTargets(), lowering::setAsSamTarget);

        publishDefinitelyDefined(pass, checker);
        hierarchy.publishClassLoweringFacts();
        dispatch.deriveTargets(file);
        hierarchy.publishTypeHierarchies();
        if (pass.analysis.moduleInterface() != null) {
            pass.analysis
                    .moduleInterface()
                    .setTypeHierarchies(pass.analysis.hierarchy().typeHierarchies());
        }
    }

    private <K extends ParserRuleContext, V> void publishInFile(
            Map<K, V> source, BiConsumer<K, V> sink) {
        for (Map.Entry<K, V> entry : source.entrySet()) {
            if (checker.inFile(entry.getKey())) {
                sink.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Single fused pass over converged expression types using pattern-matching dispatch. */
    private void publishExpressionMetadata(
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Integer> widened) {
        LoweringMetadata lowering = pass.analysis.lowering();
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            ParserRuleContext ctx = entry.getKey();
            if (!checker.inFile(ctx)) {
                continue;
            }
            Type type = entry.getValue();
            lowering.setExpressionKind(ctx, PrimitiveKind.of(type));
            if (checker.isDeeplyImmutable(type)) {
                lowering.markExpressionDeeplyImmutable(ctx);
            }
            if (type == Type.STRING) {
                lowering.markExpressionString(ctx);
            }
            if (type instanceof Type.Frozen) {
                lowering.markExpressionFrozen(ctx);
            }
            switch (ctx) {
                case BabbleParser.IsExpressionContext isCtx -> {
                    Type isTarget = checker.isTargetType(isCtx);
                    LoweringTargets.IsTarget single = staticIsTarget(isTarget, false);
                    if (single != null) {
                        lowering.setIsTarget(isCtx, single);
                    } else if (isTarget instanceof Type.Intersection inter) {
                        List<LoweringTargets.IsTarget> targets = intersectionIsTargets(inter);
                        if (!targets.isEmpty()) {
                            lowering.setIsIntersectionTargets(isCtx, targets);
                        }
                    }
                }
                case BabbleParser.AsExpressionContext asCtx -> {
                    LoweringTargets.IsTarget asTarget = staticIsTarget(type, false);
                    if (asTarget != null) {
                        lowering.setAsTarget(asCtx, asTarget);
                    }
                }
                case BabbleParser.RangeExpressionContext rangeExpr -> {
                    Type elem = Type.rangeElement(type);
                    if (elem != null) {
                        lowering.setRangeTarget(
                                rangeExpr,
                                new LoweringTargets.RangeTarget(
                                        PrimitiveKind.of(elem), elem == Type.STRING));
                    }
                }
                case BabbleParser.ForExpressionContext forExpr ->
                        publishForLoopTarget(forExpr, declarationTypes, widened);
                case BabbleParser.ThrowExpressionContext throwExpr -> {
                    Type thrownType = types.get(throwExpr.expression());
                    lowering.setThrowTarget(
                            throwExpr,
                            new LoweringTargets.ThrowTarget(isGuaranteedJavaThrowable(thrownType)));
                }
                case BabbleParser.TryExpressionContext tryExpr -> {
                    for (BabbleParser.CatchClauseContext cc : tryExpr.catchClause()) {
                        lowering.setCatchTarget(cc, buildCatchTarget(cc));
                    }
                }
                case BabbleParser.OverloadExpressionContext ov -> {
                    if (type instanceof Type.Overloaded overloaded) {
                        List<LoweringTargets.OverloadAltMetaInfo> metas =
                                new ArrayList<>(overloaded.alternatives().size());
                        for (Type.Function alt : overloaded.alternatives()) {
                            metas.add(buildOverloadAltMeta(alt));
                        }
                        pass.analysis.calls().setOverloadMetas(ov, metas);
                    }
                }
                default -> {}
            }
        }
    }

    private void publishDeclarationKinds(
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> inferredDefs,
            Map<ParserRuleContext, Type> inferredMembers,
            Map<ParserRuleContext, Type> inferredParams) {
        LoweringMetadata lowering = pass.analysis.lowering();
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (!checker.inFile(decl)) {
                continue;
            }
            Type t = null;
            if (decl instanceof BabbleParser.DefExpressionContext def) {
                t = checker.annotator().declaredType(def);
                if (t == null) {
                    LoweringTargets.DefKind defKind = lowering.defTarget(def).kind();
                    t =
                            (defKind == LoweringTargets.DefKind.MEMBER
                                            || defKind == LoweringTargets.DefKind.STATIC)
                                    ? inferredMembers.get(def)
                                    : inferredDefs.get(def);
                }
            } else if (ScopeAnalysis.isParamDecl(decl)) {
                t = declarationTypes.get(decl);
                BabbleParser.TypeContext paramType = ScopeAnalysis.paramType(decl);
                if (t == null && paramType != null) {
                    t = checker.annotations().get(paramType);
                }
                if (t == null) {
                    t = inferredParams.get(decl);
                }
            } else if (decl instanceof BabbleParser.ForExpressionContext
                    || decl instanceof BabbleParser.BindingPatternContext
                    || decl instanceof BabbleParser.RestPatternContext
                    || decl instanceof BabbleParser.ElseClauseContext) {
                t = declarationTypes.get(decl);
            }
            if (t != null) {
                lowering.setDeclarationKind(decl, PrimitiveKind.of(t));
            }
        }
    }

    private void publishMemberDeclarationKinds(
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> inferredMembers,
            Map<ParserRuleContext, Type> inferredParams) {
        LoweringMetadata lowering = pass.analysis.lowering();
        for (TypeNode typeNode : pass.types) {
            if (!checker.inFile(typeNode.ctx)) {
                continue;
            }
            for (TypeNode.Member member : typeNode.members.values()) {
                if (member.declaration == null
                        || !checker.inFile(member.declaration)
                        || member.hasFunction) {
                    continue;
                }
                Type t = checker.annotator().declaredType(member.declaration);
                if (t == null && ScopeAnalysis.isParamDecl(member.declaration)) {
                    t = declarationTypes.get(member.declaration);
                    if (t == null) {
                        t = inferredParams.get(member.declaration);
                    }
                } else if (t == null
                        && member.declaration instanceof BabbleParser.DefExpressionContext def) {
                    t = inferredMembers.get(def);
                }
                if (t != null) {
                    lowering.setDeclarationKind(member.declaration, PrimitiveKind.of(t));
                }
            }
        }
    }

    private void publishFunctionMetadata() {
        LoweringMetadata lowering = pass.analysis.lowering();
        for (BabbleParser.FunctionLiteralContext fn : checker.functions()) {
            if (!(types.get(fn) instanceof Type.Function tf)) {
                continue;
            }
            lowering.setFunctionReturnKind(fn, PrimitiveKind.of(tf.result()));
            if (!checker.inFile(fn) || fn.fnParameters() == null) {
                continue;
            }
            List<BabbleParser.FnParamContext> paramDecls = fn.fnParameters().fnParam();
            List<Type.Parameter> tParams = tf.parameters();
            int n = Math.min(paramDecls.size(), tParams.size());
            for (int i = 0; i < n; i++) {
                Type.Parameter tp = tParams.get(i);
                String recTypeId = null;
                Type unwrapped = TypeAlgebra.unwrapFrozen(tp.type());
                if (unwrapped instanceof Type.Instance inst
                        && inst.node().kind == ClassShape.Kind.VALUE_CLASS
                        && (inst.node().ctx == null
                                || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx)
                                || checker.inFile(inst.node().ctx))) {
                    recTypeId = inst.node().id;
                }
                boolean borrow = tp.borrow() || (!tp.explicitShared() && !tp.unique());
                lowering.setParamOwnership(
                        paramDecls.get(i),
                        new LoweringTargets.ParamOwnership(
                                tp.mut(), borrow, tp.unique(), recTypeId));
            }
        }
    }

    private static LoweringTargets.IsTarget staticIsTarget(
            Type target, boolean includeJavaInstance) {
        if (target instanceof Type.Instance inst
                && (inst.node().ctx == null
                        || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx))) {
            return new LoweringTargets.IsTarget(inst.node().id, inst.node().kind);
        }
        if (includeJavaInstance
                && target instanceof Type.JavaInstance ji
                && JavaTypeBridge.isBytecodeAccessible(ji.type())) {
            return new LoweringTargets.IsTarget(null, null, ji.type().getName().replace('.', '/'));
        }
        if (target instanceof Type.Array array) {
            Class<?> checked = JavaTypeBridge.checkedArrayClass(array);
            if (JavaTypeBridge.isBytecodeAccessible(checked)) {
                return new LoweringTargets.IsTarget(
                        null, null, checked.getName().replace('.', '/'));
            }
        }
        return null;
    }

    private static List<LoweringTargets.IsTarget> intersectionIsTargets(Type.Intersection inter) {
        List<LoweringTargets.IsTarget> targets = new ArrayList<>(inter.members().size());
        for (Type member : inter.members()) {
            if (member instanceof Type.Instance inst
                    && inst.node().isInterface()
                    && (inst.node().ctx == null
                            || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx))) {
                targets.add(new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
            } else if (member instanceof Type.JavaInstance ji
                    && ji.type().isInterface()
                    && JavaTypeBridge.isBytecodeAccessible(ji.type())) {
                targets.add(
                        new LoweringTargets.IsTarget(
                                null, null, ji.type().getName().replace('.', '/')));
            } else {
                return List.of();
            }
        }
        return targets;
    }

    private static LoweringTargets.OverloadAltMetaInfo buildOverloadAltMeta(Type.Function alt) {
        int minArity = 0;
        for (Type.Parameter p : alt.parameters()) {
            if (!p.optional() && !p.rest()) {
                minArity++;
            }
        }
        int restIdx = alt.restIndex();
        int maxArity = restIdx >= 0 ? Integer.MAX_VALUE : alt.parameters().size();
        return new LoweringTargets.OverloadAltMetaInfo(
                OverloadSolver.signatureKey(alt),
                OverloadSolver.formatAlternative(alt),
                minArity,
                maxArity,
                restIdx,
                OverloadSolver.paramDescriptors(alt));
    }

    /**
     * The static target of reading the Java field {@code receiver.name}, or null when it isn't one.
     */
    static LoweringTargets.JavaFieldTarget fieldReadTarget(
            Type receiver, String name, TypeAlgebra algebra) {
        return DispatchTargetDeriver.fieldReadTarget(receiver, name, algebra);
    }

    private void publishForLoopTarget(
            BabbleParser.ForExpressionContext forExpr,
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Integer> widened) {
        TypeChecker.ForIterableInfo info = checker.forIterableInfo(forExpr);
        if (info == null) {
            return;
        }
        Type loopVarType = declarationTypes.get(forExpr);
        PrimitiveKind elemKind =
                loopVarType != null ? PrimitiveKind.of(loopVarType) : PrimitiveKind.NONE;
        int elemWidenRank = widened.getOrDefault(forExpr, 0);
        LoweringMetadata.CountedRangeSource counted =
                info.kind() == LoweringTargets.ForKind.JAVA_ITERABLE
                        ? matchCountedRange(forExpr.iterable)
                        : null;
        if (counted != null) {
            pass.analysis.lowering().setCountedRangeSource(forExpr, counted);
            pass.analysis
                    .lowering()
                    .setForLoopTarget(
                            forExpr,
                            new LoweringTargets.ForLoopTarget(
                                    LoweringTargets.ForKind.COUNTED_RANGE,
                                    null,
                                    elemKind,
                                    null,
                                    elemWidenRank,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
            return;
        }
        String elemCheck = null;
        if (info.kind() == LoweringTargets.ForKind.JAVA_ITERABLE
                || info.kind() == LoweringTargets.ForKind.JAVA_ITERATOR
                || info.kind() == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE
                || info.kind() == LoweringTargets.ForKind.DYNAMIC_UNION
                || (info.kind() == LoweringTargets.ForKind.ARRAY
                        && info.arrayClass() != null
                        && !info.arrayClass().getComponentType().isPrimitive())) {
            Type rawElem = info.elemType();
            if (rawElem != null && rawElem != Type.NULLABLE_ANY && rawElem != Type.ERROR) {
                elemCheck = descriptor(rawElem);
            }
        }
        LoweringTargets.InterfaceCallTarget iterIface =
                dispatch.deriveZeroArgInterfaceTarget(info.babbleIterable(), "iterator");
        LoweringTargets.InterfaceCallTarget hasNextIface =
                dispatch.deriveZeroArgInterfaceTarget(info.babbleIterator(), "hasNext");
        LoweringTargets.InterfaceCallTarget nextIface =
                dispatch.deriveZeroArgInterfaceTarget(info.babbleIterator(), "next");
        LoweringTargets.FunctionSignature hasNextSig =
                info.hasNextFn() != null
                        ? new LoweringTargets.FunctionSignature(
                                new PrimitiveKind[0], PrimitiveKind.BOOL)
                        : null;
        LoweringTargets.FunctionSignature nextSig =
                info.nextFn() != null
                        ? new LoweringTargets.FunctionSignature(
                                new PrimitiveKind[0], PrimitiveKind.of(info.nextFn().result()))
                        : null;
        pass.analysis
                .lowering()
                .setForLoopTarget(
                        forExpr,
                        new LoweringTargets.ForLoopTarget(
                                info.kind(),
                                info.arrayClass(),
                                elemKind,
                                elemCheck,
                                elemWidenRank,
                                iterIface,
                                hasNextSig,
                                hasNextIface,
                                nextSig,
                                nextIface));
    }

    private LoweringMetadata.CountedRangeSource matchCountedRange(
            BabbleParser.ExpressionContext expr) {
        boolean descending = false;
        BabbleParser.ExpressionContext stepExpr = null;
        BabbleParser.ExpressionContext cur = expr;
        while (true) {
            while (cur instanceof BabbleParser.BlockExpressionContext b
                    && b.block().expression().size() == 1) {
                cur = b.block().expression(0);
            }
            if (cur instanceof BabbleParser.CallContext call
                    && call.expression() instanceof BabbleParser.SelectorContext sel
                    && sel.expression() != null
                    && sel.safe == null) {
                String name = ScopeAnalysis.selectorName(sel);
                List<BabbleParser.CallParameterContext> args =
                        call.callParameters().callParameter();
                if ("reversed".equals(name) && args.isEmpty()) {
                    descending = !descending;
                    cur = sel.expression();
                    continue;
                }
                if ("step".equals(name)
                        && stepExpr == null
                        && args.size() == 1
                        && args.get(0).spread == null) {
                    stepExpr = args.get(0).expression();
                    cur = sel.expression();
                    continue;
                }
            }
            break;
        }
        if (!(cur instanceof BabbleParser.RangeExpressionContext range)) {
            return null;
        }
        Type elem = Type.rangeElement(types.get(range));
        if (elem == null || !Type.isDiscreteRangeElement(elem)) {
            return null;
        }
        boolean inclusive = range.op.getType() == BabbleParser.RANGE;
        return new LoweringMetadata.CountedRangeSource(
                range.left, range.right, stepExpr, inclusive, descending, PrimitiveKind.of(elem));
    }

    private static void publishDefinitelyDefined(BlockScopes pass, TypeChecker checker) {
        Resolution resolution = pass.analysis.resolution();
        if (resolution == null) {
            return;
        }
        Set<Resolution.Variable> unsafe = new HashSet<>();
        for (BlockScopes.Occurrence occ : pass.occurrences) {
            Resolution.Variable v = resolution.variable(occ.ctx());
            if (v != null
                    && v.kind() == Resolution.Variable.Kind.DEFINITION
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
                    pass.analysis.lowering().markDefinitelyDefined(def);
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
        if (tree instanceof BabbleParser.TypeContext
                || tree instanceof BabbleParser.TypeListContext
                || tree instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        recordCheck(tree, pass, checker, declarationTypes, inferredDefs, inferredMembers, widened);
        for (int i = 0; i < tree.getChildCount(); i++) {
            recordChecksTree(
                    tree.getChild(i),
                    pass,
                    checker,
                    declarationTypes,
                    inferredDefs,
                    inferredMembers,
                    widened);
        }
    }

    /**
     * Records the declared type each boundary checks at runtime : parameters of functions and class
     * headers, results of functions, defs, and unqualified assignments to annotated names. Members
     * and statics also carry theirs in the class shape, for assignments through a receiver.
     */
    private static void recordCheck(
            ParseTree tree,
            BlockScopes pass,
            TypeChecker checker,
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> inferredDefs,
            Map<ParserRuleContext, Type> inferredMembers,
            Map<ParserRuleContext, Integer> widened) {
        if (tree instanceof ParserRuleContext parameter && ScopeAnalysis.isParamDecl(parameter)) {
            if (ScopeAnalysis.isParamRest(parameter)) {
                Type restType = declarationTypes.get(parameter);
                if (restType != null) {
                    pass.analysis.lowering().setCheckedType(parameter, descriptor(restType));
                }
                return;
            }
            BabbleParser.TypeContext paramType = ScopeAnalysis.paramType(parameter);
            Type type =
                    paramType != null
                            ? checker.annotator().annotation(paramType, checker.scopeOf(parameter))
                            : declarationTypes.get(parameter);
            int targetRank = type != null ? TypeAlgebra.singleNumericRank(type) : -1;
            if (targetRank > 0) {
                widened.merge(parameter, targetRank, Math::max);
            }
            if (paramType != null || targetRank > 0) {
                String descriptor = descriptor(type);
                pass.analysis.lowering().setCheckedType(parameter, descriptor);
                if (parameter.getParent().getParent()
                        instanceof BabbleParser.ClassExpressionContext cls) {
                    TypeNode clsNode = pass.typeNodes.get(cls);
                    if (ScopeAnalysis.paramIsMember(parameter)
                            || clsNode.kind == ClassShape.Kind.VALUE_CLASS) {
                        putType(
                                clsNode.memberTypes,
                                ScopeAnalysis.paramId(parameter).getText(),
                                descriptor);
                    }
                    if (!clsNode.constructors.isEmpty() && clsNode.constructors.get(0).isPrimary) {
                        int pIdx = cls.classParameters().classParam().indexOf(parameter);
                        TypeNode.Constructor primary = clsNode.constructors.get(0);
                        if (pIdx >= 0 && pIdx < primary.paramTypes.length) {
                            primary.paramTypes[pIdx] = descriptor;
                        }
                    }
                } else if (parameter.getParent().getParent()
                        instanceof BabbleParser.InitExpressionContext initCtx) {
                    ScopeNode initScope = checker.scopeOf(initCtx);
                    if (initScope != null && initScope.type != null) {
                        int pIdx = initCtx.fnParameters().fnParam().indexOf(parameter);
                        for (TypeNode.Constructor ctor : initScope.type.constructors) {
                            if (ctor.initBlock == initCtx
                                    && pIdx >= 0
                                    && pIdx < ctor.paramTypes.length) {
                                ctor.paramTypes[pIdx] = descriptor;
                                break;
                            }
                        }
                    }
                }
            }
            if (type != null && pass.analysis.lowering().javaSamTarget(parameter) == null) {
                LoweringTargets.JavaSamTarget samTarget = uniqueSamTargetForParam(type);
                if (samTarget != null) {
                    pass.analysis.lowering().setJavaSamTarget(parameter, samTarget);
                }
            }
        } else if (tree instanceof BabbleParser.FunctionLiteralContext function
                && function.type() != null) {
            pass.analysis
                    .lowering()
                    .setCheckedType(
                            function,
                            descriptor(
                                    checker.annotator()
                                            .annotation(
                                                    function.type(), checker.scopeOf(function))));
        } else if (tree instanceof BabbleParser.DefExpressionContext def) {
            LoweringTargets.DefKind kind = pass.analysis.lowering().defTarget(def).kind();
            if (kind == LoweringTargets.DefKind.DEFAULT
                    || kind == LoweringTargets.DefKind.REQUIRED) {
                return;
            }
            String descriptor = null;
            if (def.type() != null) {
                descriptor =
                        descriptor(
                                checker.annotator().annotation(def.type(), checker.scopeOf(def)));
            } else {
                Type inferred =
                        (kind == LoweringTargets.DefKind.MEMBER
                                        || kind == LoweringTargets.DefKind.STATIC)
                                ? inferredMembers.get(def)
                                : inferredDefs.get(def);
                if (inferred != null && TypeAlgebra.singleNumericRank(inferred) > 0) {
                    descriptor = descriptor(inferred);
                }
            }
            if (descriptor != null) {
                pass.analysis.lowering().setCheckedType(def, descriptor);
                if (kind == LoweringTargets.DefKind.MEMBER) {
                    putType(checker.scopeOf(def).type.memberTypes, def.name.getText(), descriptor);
                } else if (kind == LoweringTargets.DefKind.STATIC) {
                    putType(checker.scopeOf(def).type.staticTypes, def.name.getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign
                && ScopeAnalysis.assignReceiver(assign) == null
                && ScopeAnalysis.assignName(assign) != null
                && checker.resolveName(
                                assign,
                                ScopeAnalysis.assignName(assign),
                                checker.occurrenceScopeOf(assign))
                        instanceof ParserRuleContext declaration) {
            Type declared = checker.annotator().declaredType(declaration);
            if (declared != null) {
                pass.analysis.lowering().setCheckedType(assign, descriptor(declared));
            } else {
                Type inferred = checker.typeOfDeclaration(declaration);
                if (inferred != null && TypeAlgebra.singleNumericRank(inferred) > 0) {
                    pass.analysis.lowering().setCheckedType(assign, descriptor(inferred));
                }
            }
        }
    }

    private static LoweringTargets.JavaSamTarget uniqueSamTargetForParam(Type type) {
        if (JavaTypeBridge.resolveJavaSam(type)
                instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid.toTarget();
        }
        if (Type.withoutNull(type) instanceof Type.Union u) {
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function
                        || mb instanceof Type.Overloaded
                        || mb == Type.ANY) {
                    return null;
                }
                if (JavaTypeBridge.resolveJavaSam(member)
                        instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
                    if (match != null && !match.equals(v)) {
                        return null;
                    }
                    match = v;
                }
            }
            return match != null ? match.toTarget() : null;
        }
        return null;
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
        if (type instanceof Type.Frozen frozen) {
            return descriptor(frozen.of());
        }
        if (type instanceof Type.Union union) {
            return encodeCompositeDescriptor("union", union, union.members());
        }
        if (type instanceof Type.Intersection intersection) {
            return encodeCompositeDescriptor("inter", intersection, intersection.members());
        }
        if (type instanceof Type.Function || type instanceof Type.Overloaded) {
            return "fn|" + type + "|";
        }
        if (type instanceof Type.Instance instance) {
            return "babble|" + instance.node().name + "|" + instance.node().id;
        }
        if (type instanceof Type.JavaInstance java) {
            return "java|" + java.type().getName() + "|" + java.type().getName();
        }
        if (type instanceof Type.Array array) {
            return "java|" + array + "|" + JavaTypeBridge.checkedArrayClass(array).getName();
        }
        return null;
    }

    private static String encodeCompositeDescriptor(
            String tag, Type composite, List<Type> members) {
        StringBuilder payload = new StringBuilder();
        for (Type member : members) {
            String sub = descriptor(member);
            if (sub == null) {
                return null;
            }
            if (!payload.isEmpty()) {
                payload.append(',');
            }
            payload.append(org.babblelang.runtime.Types.encodeComponent(sub));
        }
        return tag + "|" + composite + "|" + payload;
    }

    static PrimitiveKind primitiveKind(Type type) {
        return PrimitiveKind.of(type);
    }

    private static boolean isGuaranteedJavaThrowable(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return false;
        }
        if (type instanceof Type.Frozen f) {
            return isGuaranteedJavaThrowable(f.of());
        }
        if (type instanceof Type.TypeVar tv) {
            return tv.bound() != null && isGuaranteedJavaThrowable(tv.bound());
        }
        if (type instanceof Type.JavaInstance ji) {
            return Throwable.class.isAssignableFrom(ji.type());
        }
        if (type instanceof Type.Union u) {
            if (u.members().isEmpty()) {
                return false;
            }
            for (Type m : u.members()) {
                if (!isGuaranteedJavaThrowable(m)) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Type.Intersection inter) {
            for (Type m : inter.members()) {
                if (isGuaranteedJavaThrowable(m)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private void publishMatchTargets() {
        MatchTyper mt = checker.matchTyper();
        LoweringMetadata lowering = pass.analysis.lowering();
        publishInFile(
                mt.matchExhaustive(),
                (k, v) -> lowering.setMatchExhaustive(k, Boolean.TRUE.equals(v)));
        publishInFile(mt.patternConsts(), lowering::setPatternConst);
        publishInFile(mt.rangePatterns(), lowering::setRangePatternSource);
        publishInFile(mt.membershipTargets(), lowering::setMembershipTarget);
        publishInFile(mt.relationalKinds(), lowering::setRelationalScrutineeKind);
        publishInFile(
                mt.patternTargetTypes(),
                (owner, target) -> {
                    if (target != null && target != Type.ERROR) {
                        lowering.setPatternTypeTarget(
                                owner, buildPatternTypeTarget(owner, target, mt));
                    }
                });
        publishInFile(
                mt.recordDestructures(),
                (owner, info) -> {
                    LoweringTargets.PatternTypeTarget typeTest =
                            buildPatternTypeTarget(owner, info.targetType(), mt);
                    PrimitiveKind[] fieldKinds = new PrimitiveKind[info.fieldNames().size()];
                    if (!info.isJavaRecord()) {
                        TypeNode node = info.babbleNode();
                        for (int i = 0; i < fieldKinds.length; i++) {
                            String fn = info.fieldNames().get(i);
                            PrimitiveKind mk = node.memberKinds.get(fn);
                            if (mk == null) {
                                TypeNode.Member member = node.members.get(fn);
                                if (member != null && member.declaration != null) {
                                    mk = lowering.declarationKind(member.declaration);
                                }
                            }
                            fieldKinds[i] = mk != null ? mk : PrimitiveKind.NONE;
                        }
                    } else {
                        for (int i = 0; i < fieldKinds.length; i++) {
                            fieldKinds[i] =
                                    PrimitiveKind.of(
                                            JavaTypeBridge.fromJava(
                                                    info.javaAccessors().get(i).getReturnType()));
                        }
                    }
                    lowering.setRecordDestructureTarget(
                            owner,
                            new LoweringTargets.RecordDestructureTarget(
                                    info.isJavaRecord(),
                                    info.babbleNode() != null ? info.babbleNode().id : null,
                                    info.javaRecordClass(),
                                    info.fieldNames().toArray(new String[0]),
                                    info.babbleMemberIndices(),
                                    fieldKinds,
                                    info.javaAccessors().toArray(new java.lang.reflect.Method[0]),
                                    typeTest));
                });
        publishInFile(
                mt.sequenceDestructures(),
                (owner, info) -> {
                    LoweringTargets.PatternTypeTarget typeTest =
                            info.targetType() != null
                                    ? buildPatternTypeTarget(owner, info.targetType(), mt)
                                    : null;
                    PrimitiveKind elemKind =
                            (info.isArray()
                                            && info.arrayClass() != null
                                            && info.arrayClass().getComponentType().isPrimitive())
                                    ? PrimitiveKind.of(
                                            JavaTypeBridge.fromJava(
                                                    info.arrayClass().getComponentType()))
                                    : PrimitiveKind.NONE;
                    lowering.setSequenceDestructureTarget(
                            owner,
                            new LoweringTargets.SequenceDestructureTarget(
                                    info.isArray(),
                                    info.arrayClass(),
                                    elemKind,
                                    info.fixedCount(),
                                    info.hasRest(),
                                    typeTest));
                });
    }

    private LoweringTargets.PatternTypeTarget buildPatternTypeTarget(
            ParserRuleContext owner, Type target, MatchTyper mt) {
        String builtin = mt.patternBuiltins().get(owner);
        LoweringTargets.IsTarget isTarget = staticIsTarget(target, true);
        List<LoweringTargets.IsTarget> intersectionTargets =
                (isTarget == null && target instanceof Type.Intersection inter)
                        ? intersectionIsTargets(inter)
                        : List.of();
        return new LoweringTargets.PatternTypeTarget(
                builtin, isTarget, intersectionTargets, descriptor(target));
    }

    private LoweringTargets.CatchTarget buildCatchTarget(BabbleParser.CatchClauseContext cc) {
        if (cc.type() == null) {
            return new LoweringTargets.CatchTarget(
                    null, List.of("java/lang/Exception"), true, true, false, false, List.of());
        }
        Type target = checker.catchTargetType(cc);
        if (target == null || target == Type.ERROR) {
            return new LoweringTargets.CatchTarget(
                    null, List.of(), false, false, false, false, List.of());
        }
        String desc = descriptor(target);
        List<String> jvmCatchTypes = new ArrayList<>();
        List<LoweringTargets.IsTarget> babbleTargets = new ArrayList<>();
        boolean catchesBabble = false;
        boolean catchesJavaThrowable = false;
        boolean skipsThrownException = false;
        boolean allBabbleStatic = true;
        for (Type m : TypeChecker.flattenCatchUnion(target)) {
            if (m instanceof Type.JavaInstance ji) {
                Class<?> cls = ji.type();
                if (JavaTypeBridge.isBytecodeAccessible(cls)) {
                    jvmCatchTypes.add(cls.getName().replace('.', '/'));
                } else {
                    jvmCatchTypes.add("java/lang/Throwable");
                }
                if (cls == Throwable.class || cls == org.babblelang.runtime.ThrownException.class) {
                    catchesJavaThrowable = true;
                } else if (cls.isAssignableFrom(org.babblelang.runtime.ThrownException.class)) {
                    skipsThrownException = true;
                }
            } else if (m instanceof Type.Instance inst) {
                catchesBabble = true;
                if (inst.node().ctx == null
                        || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx)) {
                    babbleTargets.add(
                            new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
                } else {
                    allBabbleStatic = false;
                }
            } else if (m instanceof Type.Intersection inter) {
                catchesBabble = true;
                allBabbleStatic = false;
                for (Type im : inter.members()) {
                    if (im instanceof Type.JavaInstance ji
                            && Throwable.class.isAssignableFrom(ji.type())
                            && JavaTypeBridge.isBytecodeAccessible(ji.type())) {
                        jvmCatchTypes.add(ji.type().getName().replace('.', '/'));
                    }
                }
            }
        }
        if (!allBabbleStatic) {
            babbleTargets.clear();
        }
        return new LoweringTargets.CatchTarget(
                desc,
                List.copyOf(jvmCatchTypes),
                catchesBabble,
                false,
                catchesJavaThrowable,
                skipsThrownException,
                List.copyOf(babbleTargets));
    }
}
