package org.babblelang.analysis;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.CharRange;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.DoubleRange;
import org.babblelang.runtime.IntRange;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.LongRange;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.Range;

/**
 * Static dispatch target derivation ({@code RM001}, {@code RM003}, {@code BIP021}, {@code BIP026})
 * for {@link LoweringAnalyzer}.
 *
 * <p>Uses {@link TypeHierarchyIndex} for O(1) cached interface hierarchy queries and a pre-resolved
 * static {@link RangeOps} table to avoid per-call reflection on range methods.
 */
final class DispatchTargetDeriver {
    private static final Method RANGE_INCLUSIVE = lookupMethod(Range.class, "inclusive");
    private static final Method RANGE_IS_EMPTY = lookupMethod(Range.class, "isEmpty");
    private static final Method OBJECT_EQUALS = lookupMethod(Object.class, "equals", Object.class);
    private static final Method OBJECT_HASH_CODE = lookupMethod(Object.class, "hashCode");
    private static final Method OBJECT_TO_STRING = lookupMethod(Object.class, "toString");

    private enum RangeOps {
        INT(IntRange.class, "Int", int.class, int.class, true),
        SUB_INT(IntRange.class, "Int", int.class, int.class, false),
        LONG(LongRange.class, "Long", long.class, long.class, true),
        CHAR(CharRange.class, "Char", char.class, int.class, true),
        DOUBLE(DoubleRange.class, "Double", double.class, null, true),
        FLOAT(DoubleRange.class, "Double", double.class, null, false),
        GENERIC(Range.class, "", Object.class, null, false);

        final Method start;
        final Method end;
        final Method contains;
        final Method step;
        final Method reversed;
        final Method iterator;

        RangeOps(
                Class<?> cls,
                String suffix,
                Class<?> containsParam,
                Class<?> stepParam,
                boolean exactBounds) {
            this.start =
                    exactBounds
                            ? lookupMethod(cls, "start" + suffix)
                            : lookupMethod(Range.class, "start");
            this.end =
                    exactBounds
                            ? lookupMethod(cls, "end" + suffix)
                            : lookupMethod(Range.class, "end");
            this.contains = lookupMethod(cls, "contains" + suffix, containsParam);
            this.step = stepParam != null ? lookupMethod(cls, "step", stepParam) : null;
            this.reversed = stepParam != null ? lookupMethod(cls, "reversed") : null;
            this.iterator = stepParam != null ? lookupMethod(cls, "iterator") : null;
        }

        static RangeOps of(Type elem) {
            if (elem == Type.INT) {
                return INT;
            }
            if (elem == Type.BYTE || elem == Type.SHORT) {
                return SUB_INT;
            }
            if (elem == Type.LONG) {
                return LONG;
            }
            if (elem == Type.CHAR) {
                return CHAR;
            }
            if (elem == Type.DOUBLE) {
                return DOUBLE;
            }
            if (elem == Type.FLOAT) {
                return FLOAT;
            }
            return GENERIC;
        }
    }

    private static Method lookupMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final BlockScopes pass;
    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> types;
    private final TypeHierarchyIndex hierarchy;

    DispatchTargetDeriver(BlockScopes pass, TypeChecker checker, TypeHierarchyIndex hierarchy) {
        this.pass = pass;
        this.checker = checker;
        this.types = checker.types();
        this.hierarchy = hierarchy;
    }

    /**
     * Derives the static target of every selection, Java field write, operator method, and call
     * from the converged types.
     */
    void deriveTargets(ParseTree tree) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            deriveSelection(selector);
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign
                && ScopeAnalysis.assignReceiver(assign) != null
                && JavaTypeBridge.assignedField(
                                types.get(ScopeAnalysis.assignReceiver(assign)),
                                ScopeAnalysis.assignName(assign))
                        instanceof JavaTypeBridge.AssignedField field) {
            LoweringTargets.JavaFieldTarget target = field.target(types.get(assign));
            if (target != null) {
                pass.analysis.lowering().setJavaFieldTarget(assign, target);
            }
        } else if (tree instanceof BabbleParser.CallContext call && checker.inFile(call)) {
            deriveCall(call);
        } else if (tree instanceof BabbleParser.BinaryOpContext bin
                && checker.inFile(bin)
                && pass.analysis.calls().overloadDispatch(bin) == null) {
            recordDirectClassMethod(bin, bin.left, Operators.method(bin.op.getText()), 1, true);
        } else if (tree instanceof BabbleParser.NegateContext neg
                && checker.inFile(neg)
                && pass.analysis.calls().overloadDispatch(neg) == null) {
            recordDirectClassMethod(neg, neg.operand, "neg", 0, true);
        }
        if (tree instanceof BabbleParser.AssignExpressionContext assign) {
            BabbleParser.ExpressionContext receiver = ScopeAnalysis.assignReceiver(assign);
            if (receiver != null) {
                deriveTargets(receiver);
            }
            if (assign.value != null) {
                deriveTargets(assign.value);
            }
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            deriveTargets(tree.getChild(i));
        }
    }

    private void recordDirectClassMethod(
            ParserRuleContext site,
            BabbleParser.ExpressionContext recvExpr,
            String method,
            int expectedArity,
            boolean requireFixedPositional) {
        if (method == null || recvExpr == null) {
            return;
        }
        Type left = TypeAlgebra.unwrapFrozen(types.get(recvExpr));
        if (!(left instanceof Type.Instance inst) || inst.node().isInterface()) {
            return;
        }
        TypeNode.Member member = inst.node().members.get(method);
        if (member == null || !member.isMethod() || member.isOverload) {
            return;
        }
        LoweringTargets.FunctionSignature sig = inst.node().methodSignatures.get(method);
        if (sig == null
                || sig.parameterKinds().length != expectedArity
                || (requireFixedPositional && (sig.hasOptionalMask() || sig.restIndex() >= 0))) {
            return;
        }
        pass.analysis
                .lowering()
                .setDirectMethodTarget(
                        site,
                        new LoweringTargets.DirectMethodTarget(
                                inst.node().id,
                                method,
                                -1,
                                sig,
                                inst.node().kind == ClassShape.Kind.VALUE_CLASS));
        if (member.function != null && checker.inFile(member.function)) {
            pass.analysis.lowering().setDirectMethodFunction(site, member.function);
        }
    }

    /**
     * {@code receiver.name} : an indexed module export, a compiled Babble class/record field, or a
     * Java field.
     */
    private void deriveSelection(BabbleParser.SelectorContext selector) {
        if (selector.expression() == null
                || selector.safe != null
                || !(types.get(selector.expression()) instanceof Type receiver)) {
            return;
        }
        String name = ScopeAnalysis.selectorName(selector);
        if (receiver instanceof Type.Module module) {
            ModuleInterface targetMod = module.module();
            int index = targetMod.exportIndex(name);
            if (targetMod.summary() != null && targetMod.exports().contains(name) && index >= 0) {
                pass.analysis
                        .lowering()
                        .setModuleExportTarget(
                                selector,
                                new LoweringTargets.ModuleExportTarget(
                                        index,
                                        name,
                                        targetMod.name(),
                                        targetMod.moduleKey(),
                                        pass.analysis.isNonCyclicNonDynamicTarget(targetMod)));
            }
        } else if (receiver instanceof Type.ClassValue cv) {
            TypeNode.Member sm = cv.node().statics.get(name);
            if (sm != null && !sm.isPrivate() && !sm.isMutable()) {
                pass.analysis.lowering().markPublicStaticDefSelect(selector);
            }
        } else {
            LoweringTargets.JavaFieldTarget target =
                    fieldReadTarget(receiver, name, checker.algebra());
            if (target != null) {
                pass.analysis.lowering().setJavaFieldTarget(selector, target);
                return;
            }
            if (!(selector.expression() instanceof BabbleParser.ThisExpressionContext)) {
                Type unwrapped = TypeAlgebra.unwrapFrozen(receiver);
                if (unwrapped instanceof Type.Instance inst && !inst.node().isInterface()) {
                    TypeNode.Member member = inst.node().members.get(name);
                    if (member != null && !member.isMethod()) {
                        PrimitiveKind fk =
                                inst.node().memberKinds.getOrDefault(name, PrimitiveKind.NONE);
                        boolean immutableField =
                                inst.node().kind == ClassShape.Kind.VALUE_CLASS
                                        || !member.isMutable();
                        boolean defDefined = inst.node().definiteFields.contains(name);
                        pass.analysis
                                .lowering()
                                .setClassFieldTarget(
                                        selector,
                                        new LoweringTargets.ClassFieldTarget(
                                                inst.node().id,
                                                name,
                                                member.index,
                                                fk,
                                                immutableField,
                                                defDefined));
                    }
                }
            }
        }
    }

    /**
     * The static target of reading the Java field {@code receiver.name}, or null when it isn't one.
     */
    static LoweringTargets.JavaFieldTarget fieldReadTarget(
            Type receiver, String name, TypeAlgebra algebra) {
        JavaTypeBridge.JavaMember member;
        if (receiver instanceof Type.JavaClassValue value) {
            if (JavaTypeBridge.publicMemberClass(value.type(), name) != null) {
                return null;
            }
            member = JavaTypeBridge.javaMember(value.type(), List.of(), name, true, algebra);
        } else if (receiver instanceof Type.JavaInstance instance) {
            member =
                    JavaTypeBridge.javaMember(
                            instance.type(), instance.typeArguments(), name, false, algebra);
        } else if (receiver instanceof Type.Array array) {
            member =
                    JavaTypeBridge.javaMember(
                            JavaTypeBridge.checkedArrayClass(array),
                            List.of(),
                            name,
                            false,
                            algebra);
        } else if (receiver instanceof Type.Intersection inter) {
            for (Type m : inter.members()) {
                if (m instanceof Type.JavaInstance ji) {
                    JavaTypeBridge.JavaMember jm =
                            JavaTypeBridge.javaMember(
                                    ji.type(), ji.typeArguments(), name, false, algebra);
                    if (jm.error() == null && jm.fieldTarget() != null) {
                        return jm.fieldTarget();
                    }
                }
            }
            return null;
        } else {
            return null;
        }
        return member.error() == null ? member.fieldTarget() : null;
    }

    private void deriveCall(BabbleParser.CallContext call) {
        List<BabbleParser.CallParameterContext> arguments = call.callParameters().callParameter();
        boolean safe =
                call.expression() instanceof BabbleParser.SelectorContext sel && sel.safe != null;
        if (!safe
                && arguments.size() == 1
                && ScopeAnalysis.callParamName(arguments.get(0)) == null
                && call.expression() instanceof BabbleParser.SelectorContext sel
                && sel.expression() == null
                && pass.analysis.lowering().checkedType(call) == null) {
            String nm = ScopeAnalysis.selectorName(sel);
            if (("move".equals(nm) || "freeze".equals(nm))
                    && TypeChecker.resolveName(sel, nm, checker.occurrenceScopeOf(sel)) == null) {
                pass.analysis.lowering().setBuiltinOwnershipCall(call, nm);
                return;
            }
        }
        if (!safe && deriveArrayCreation(arguments, call)) {
            return;
        }
        if (!safe && deriveArrayOp(arguments, call)) {
            return;
        }
        if (!safe && deriveRangeCall(arguments, call)) {
            return;
        }
        Method method = checker.callSolver().resolvedMethod(call);
        LoweringTargets.SpreadAdapt[] spreadAdapts =
                checker.callSolver().resolvedSpreadAdapts(call);
        if (spreadAdapts != null) {
            pass.analysis.calls().setCallSpreadAdapts(call, spreadAdapts);
        }
        boolean javaVarArgs = checker.callSolver().isResolvedVarArgs(call);
        if (method != null && !safe) {
            deriveJavaMethodCall(method, javaVarArgs, arguments, call);
        }
        Constructor<?> constructor = checker.callSolver().resolvedConstructor(call);
        if (constructor != null && !safe) {
            deriveJavaConstructor(constructor, javaVarArgs, arguments, call);
        }
        if (pass.modules.containsKey(call)) {
            return;
        }
        Type rawCallee = types.get(call.expression());
        Type callee = safe && rawCallee != null ? Type.withoutNull(rawCallee) : rawCallee;
        if (!safe && callee instanceof Type.Overloaded) {
            deriveOverloadCall(arguments, spreadAdapts, call);
            return;
        }
        LoweringTargets.ArgumentBinding binding =
                deriveArgumentBinding(callee, arguments, call, spreadAdapts);
        if (binding != null) {
            pass.analysis.calls().setArgumentBinding(call, binding);
        }
        if (!safe
                && callee instanceof Type.ClassValue cv
                && cv.node().fastRecord
                && pass.analysis.calls().constructorIndex(call) == 0
                && binding != null
                && !binding.hasOmitted()
                && !binding.hasRest()
                && !binding.hasSpreads()
                && binding.paramCount() == cv.node().fastRecordParamKinds.length) {
            pass.analysis
                    .lowering()
                    .setRecordNewTarget(
                            call,
                            new LoweringTargets.RecordNewTarget(
                                    cv.node().id, cv.node().fastRecordParamKinds));
            return;
        }
        if (safe || !(callee instanceof Type.Function function)) {
            return;
        }
        if (function.typeParameters().isEmpty()) {
            deriveBabbleCall(function, binding, call);
            deriveDirectClassMethodCall(binding, call);
            deriveInterfaceCall(binding, call);
        } else if (types.get(call) != Type.ERROR) {
            deriveInterfaceCall(binding, call);
        }
    }

    private void deriveDirectClassMethodCall(
            LoweringTargets.ArgumentBinding binding, BabbleParser.CallContext call) {
        if (binding == null
                || !(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.safe != null
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext
                || sel.expression() instanceof BabbleParser.ThisExpressionContext) {
            return;
        }
        recordDirectClassMethod(
                call,
                sel.expression(),
                ScopeAnalysis.selectorName(sel),
                binding.paramCount(),
                false);
    }

    private void deriveOverloadCall(
            List<BabbleParser.CallParameterContext> arguments,
            LoweringTargets.SpreadAdapt[] spreadAdapts,
            BabbleParser.CallContext call) {
        TypeChecker.ResolvedOverloadCall resolved = checker.resolvedOverloadCalls().get(call);
        if (resolved == null) {
            return;
        }
        PrimitiveKind callResultKind = PrimitiveKind.of(resolved.resultType());
        if (!resolved.isSplit()) {
            Type.Function alt = resolved.altType();
            PrimitiveKind restKind = checker.callSolver().resolvedRestKind(call);
            LoweringTargets.ArgumentBinding binding =
                    CallSolver.computeArgumentBinding(
                            alt.parameters(), arguments, restKind, spreadAdapts);
            if (binding != null) {
                pass.analysis.calls().setArgumentBinding(call, binding);
            }
            CallMetadata.OverloadCallTarget target =
                    buildOverloadTarget(
                            resolved.altIndex(),
                            alt,
                            resolved.exactSet(),
                            resolved.directTarget(),
                            binding);
            if (target.directTarget() != null
                    && alt.typeParameters().isEmpty()
                    && binding != null
                    && checker.scopeOf(target.directTarget()).memberOf == null) {
                pass.analysis.calls().setBabbleCallFunction(call, target.directTarget());
                pass.analysis.calls().setBabbleCallSignature(call, target.signature());
            }
            deriveOverloadedInterfaceCallTarget(call, resolved, alt, binding);
            pass.analysis
                    .calls()
                    .setOverloadDispatch(
                            call,
                            new CallMetadata.OverloadDispatchTarget(target, null, callResultKind));
            return;
        }
        List<CallMetadata.OverloadSplitBranch> branches = buildSplitBranches(resolved, arguments);
        pass.analysis
                .calls()
                .setOverloadDispatch(
                        call,
                        new CallMetadata.OverloadDispatchTarget(null, branches, callResultKind));
    }

    private void deriveOverloadedInterfaceCallTarget(
            BabbleParser.CallContext call,
            TypeChecker.ResolvedOverloadCall resolved,
            Type.Function alt,
            LoweringTargets.ArgumentBinding binding) {
        if (binding == null
                || !checker.inFile(call)
                || !(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.safe != null
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        String memberName = ScopeAnalysis.selectorName(sel);
        Type recvType = types.get(sel.expression());
        TypeHierarchyIndex.OwnerMember found = findDeclaringInterfaceOnType(recvType, memberName);
        if (found == null) {
            return;
        }
        Type mt = checker.memberType(found.owner(), found.member());
        if (!(mt instanceof Type.Overloaded ifaceOv)) {
            return;
        }
        int ifaceAltIdx =
                (recvType instanceof Type.Instance ri && ri.node() == found.owner())
                        ? resolved.altIndex()
                        : OverloadSolver.selectAlternativeForFunction(
                                checker.algebra(), ifaceOv, alt, false);
        if (ifaceAltIdx < 0 || ifaceAltIdx >= ifaceOv.alternatives().size()) {
            return;
        }
        LoweringTargets.FunctionSignature ifaceSig =
                TypeHierarchyIndex.uninstantiatedSignature(ifaceOv.alternatives().get(ifaceAltIdx));
        if (ifaceSig != null && binding.paramCount() == ifaceSig.parameterKinds().length) {
            pass.analysis
                    .lowering()
                    .setInterfaceCallTarget(
                            call,
                            new LoweringTargets.InterfaceCallTarget(
                                    found.owner().id,
                                    found.member().name + "$" + ifaceAltIdx,
                                    ifaceSig,
                                    true));
        }
    }

    void publishOperatorOverloadDispatch(
            ParserRuleContext site, TypeChecker.ResolvedOverloadCall resolved) {
        PrimitiveKind callResultKind = PrimitiveKind.of(resolved.resultType());
        if (!resolved.isSplit()) {
            CallMetadata.OverloadCallTarget target =
                    buildOverloadTarget(
                            resolved.altIndex(),
                            resolved.altType(),
                            resolved.exactSet(),
                            resolved.directTarget(),
                            null);
            pass.analysis
                    .calls()
                    .setOverloadDispatch(
                            site,
                            new CallMetadata.OverloadDispatchTarget(target, null, callResultKind));
            if (target.directTarget() == null && site instanceof BabbleParser.BinaryOpContext bin) {
                String method = Operators.method(bin.op.getText());
                Type left = TypeAlgebra.unwrapFrozen(types.get(bin.left));
                if (method != null
                        && left instanceof Type.Instance inst
                        && !inst.node().isInterface()) {
                    LoweringTargets.FunctionSignature ovSig =
                            inst.node()
                                    .overloadMethodSignatures
                                    .get(method + "$" + resolved.altIndex());
                    if (ovSig != null
                            && ovSig.parameterKinds().length == 1
                            && !ovSig.hasOptionalMask()
                            && ovSig.restIndex() < 0) {
                        pass.analysis
                                .lowering()
                                .setDirectMethodTarget(
                                        site,
                                        new LoweringTargets.DirectMethodTarget(
                                                inst.node().id,
                                                method,
                                                resolved.altIndex(),
                                                ovSig,
                                                inst.node().kind == ClassShape.Kind.VALUE_CLASS));
                    }
                }
            }
            return;
        }
        List<CallMetadata.OverloadSplitBranch> branches = buildSplitBranches(resolved, null);
        pass.analysis
                .calls()
                .setOverloadDispatch(
                        site,
                        new CallMetadata.OverloadDispatchTarget(null, branches, callResultKind));
    }

    private CallMetadata.OverloadCallTarget buildOverloadTarget(
            int altIndex,
            Type.Function alt,
            boolean exactSet,
            BabbleParser.FunctionLiteralContext rawDirectTarget,
            LoweringTargets.ArgumentBinding binding) {
        BabbleParser.FunctionLiteralContext directTarget =
                rawDirectTarget != null && checker.inFile(rawDirectTarget) ? rawDirectTarget : null;
        return new CallMetadata.OverloadCallTarget(
                altIndex,
                OverloadSolver.signatureKey(alt),
                exactSet,
                directTarget,
                TypeHierarchyIndex.uninstantiatedSignature(alt),
                binding);
    }

    private List<CallMetadata.OverloadSplitBranch> buildSplitBranches(
            TypeChecker.ResolvedOverloadCall resolved,
            List<BabbleParser.CallParameterContext> arguments) {
        List<CallMetadata.OverloadSplitBranch> branches =
                new ArrayList<>(resolved.splitBranches().size());
        for (TypeChecker.ResolvedSplitBranch rb : resolved.splitBranches()) {
            Type.Function alt = rb.altType();
            LoweringTargets.ArgumentBinding binding =
                    arguments != null
                            ? CallSolver.computeArgumentBinding(
                                    alt.parameters(), arguments, rb.restKind(), rb.spreadAdapts())
                            : null;
            CallMetadata.OverloadCallTarget target =
                    buildOverloadTarget(
                            rb.altIndex(), alt, rb.exactSet(), rb.directTarget(), binding);
            branches.add(
                    new CallMetadata.OverloadSplitBranch(
                            rb.checks(), target, PrimitiveKind.of(rb.resultType())));
        }
        return List.copyOf(branches);
    }

    private LoweringTargets.ArgumentBinding deriveArgumentBinding(
            Type callee,
            List<BabbleParser.CallParameterContext> arguments,
            BabbleParser.CallContext call,
            LoweringTargets.SpreadAdapt[] spreadAdapts) {
        PrimitiveKind restKind = checker.callSolver().resolvedRestKind(call);
        if (callee instanceof Type.Function fn) {
            return CallSolver.computeArgumentBinding(
                    fn.parameters(), arguments, restKind, spreadAdapts);
        }
        if (callee instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            int ctorIdx = pass.analysis.calls().constructorIndex(call);
            List<Type.Parameter> params = checker.constructorParameters(cv.node(), ctorIdx);
            return params != null
                    ? CallSolver.computeArgumentBinding(params, arguments, restKind, spreadAdapts)
                    : null;
        }
        if (pass.analysis.calls().isDelegatingThisCall(call)) {
            ScopeNode ts = BlockScopes.thisScope(checker.occurrenceScopeOf(call));
            if (ts != null && ts.type != null) {
                int ctorIdx = pass.analysis.calls().constructorIndex(call);
                List<Type.Parameter> params = checker.constructorParameters(ts.type, ctorIdx);
                return params != null
                        ? CallSolver.computeArgumentBinding(
                                params, arguments, restKind, spreadAdapts)
                        : null;
            }
        }
        if (callee instanceof Type.Union union) {
            LoweringTargets.ArgumentBinding agreed = null;
            for (Type member : union.members()) {
                LoweringTargets.ArgumentBinding b =
                        deriveArgumentBinding(member, arguments, call, spreadAdapts);
                if (b == null) {
                    return null;
                }
                if (agreed == null) {
                    agreed = b;
                } else if (!java.util.Arrays.equals(agreed.paramToArg(), b.paramToArg())
                        || agreed.restParam() != b.restParam()) {
                    return null;
                }
            }
            return agreed;
        }
        return null;
    }

    private boolean deriveArrayCreation(
            List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext call) {
        if (!(types.get(call) instanceof Type.Array resultArr)) {
            return false;
        }
        Type calleeType = types.get(call.expression());
        LoweringTargets.ArrayCreationKind kind;
        if (calleeType instanceof Type.CoreCollection cc && "array".equals(cc.kind())) {
            if (arguments.size() != 1) {
                return false;
            }
            String name0 = ScopeAnalysis.callParamName(arguments.get(0));
            if ("from".equals(name0)) {
                kind = LoweringTargets.ArrayCreationKind.FROM;
            } else if (name0 == null) {
                kind = LoweringTargets.ArrayCreationKind.SIZE;
            } else {
                return false;
            }
        } else if (calleeType instanceof Type.ArrayOfFactory) {
            for (BabbleParser.CallParameterContext arg : arguments) {
                if (ScopeAnalysis.callParamName(arg) != null) {
                    return false;
                }
            }
            kind = LoweringTargets.ArrayCreationKind.OF;
        } else {
            return false;
        }

        PrimitiveKind elemKind = PrimitiveKind.of(resultArr.element());
        Class<?> componentClass = null;
        String babbleTypeId = null;
        String babbleDisplayName = null;
        java.util.Set<String> babbleConformIds = null;
        int nestedDims = 0;

        if (elemKind.isPrimitive()) {
            componentClass = JavaTypeBridge.checkedArrayClass(resultArr).getComponentType();
        } else {
            Type cur = TypeAlgebra.unwrapFrozen(Type.withoutNull(resultArr.element()));
            int dims = 0;
            while (cur instanceof Type.Array nestedArr) {
                dims++;
                cur = TypeAlgebra.unwrapFrozen(Type.withoutNull(nestedArr.element()));
            }
            if (cur instanceof Type.Instance inst) {
                babbleTypeId = inst.node().id;
                babbleDisplayName = inst.node().name;
                babbleConformIds = hierarchy.conformIds(inst.node());
                nestedDims = dims;
            } else {
                componentClass = JavaTypeBridge.checkedArrayClass(resultArr).getComponentType();
            }
        }

        pass.analysis
                .lowering()
                .setArrayCreationTarget(
                        call,
                        new LoweringTargets.ArrayCreationTarget(
                                kind,
                                elemKind,
                                componentClass,
                                babbleTypeId,
                                babbleDisplayName,
                                babbleConformIds,
                                nestedDims));
        return true;
    }

    private boolean deriveArrayOp(
            List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext call) {
        if (!(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return false;
        }
        Type rawRecv = types.get(sel.expression());
        Type unwrapped = rawRecv != null ? TypeAlgebra.unwrapFrozen(rawRecv) : null;
        if (!(unwrapped instanceof Type.Array arr)) {
            return false;
        }
        for (BabbleParser.CallParameterContext arg : arguments) {
            if (ScopeAnalysis.callParamName(arg) != null) {
                return false;
            }
        }
        String name = ScopeAnalysis.selectorName(sel);
        LoweringTargets.ArrayOpKind kind =
                switch (name) {
                    case "size" -> arguments.isEmpty() ? LoweringTargets.ArrayOpKind.SIZE : null;
                    case "get" -> arguments.size() == 1 ? LoweringTargets.ArrayOpKind.GET : null;
                    case "set" -> arguments.size() == 2 ? LoweringTargets.ArrayOpKind.SET : null;
                    case "equals" ->
                            arguments.size() == 1 ? LoweringTargets.ArrayOpKind.EQUALS : null;
                    case "hashCode" ->
                            arguments.isEmpty() ? LoweringTargets.ArrayOpKind.HASH_CODE : null;
                    case "toString" ->
                            arguments.isEmpty() ? LoweringTargets.ArrayOpKind.TO_STRING : null;
                    default -> null;
                };
        if (kind == null) {
            return false;
        }
        Class<?> arrCls = JavaTypeBridge.checkedArrayClass(arr);
        pass.analysis
                .lowering()
                .setArrayOpTarget(
                        call,
                        new LoweringTargets.ArrayOpTarget(
                                kind,
                                PrimitiveKind.of(arr.element()),
                                arrCls,
                                arrCls.getComponentType()));
        return true;
    }

    private boolean isApplicableJavaArg(Class<?> paramType, BabbleParser.CallParameterContext arg) {
        Type argType = types.get(arg.expression());
        return ScopeAnalysis.callParamName(arg) == null
                && JavaTypeBridge.guaranteedApplicable(paramType, argType)
                && JavaTypeBridge.supportedStaticJavaArg(paramType, argType);
    }

    /**
     * Whether the arguments are all positional and statically applicable to the Java parameters,
     * for a direct call.
     */
    private boolean staticallyApplicable(
            Class<?>[] paramTypes,
            boolean varArgs,
            List<BabbleParser.CallParameterContext> arguments) {
        if (!varArgs) {
            if (paramTypes.length != arguments.size()) {
                return false;
            }
            for (int i = 0; i < paramTypes.length; i++) {
                if (!isApplicableJavaArg(paramTypes[i], arguments.get(i))) {
                    return false;
                }
            }
            return true;
        }
        int fixed = paramTypes.length - 1;
        if (fixed < 0 || arguments.size() < fixed || !paramTypes[fixed].isArray()) {
            return false;
        }
        Class<?> comp = paramTypes[fixed].getComponentType();
        if (!JavaTypeBridge.isBytecodeAccessible(comp)) {
            return false;
        }
        for (int i = 0; i < fixed; i++) {
            if (!isApplicableJavaArg(paramTypes[i], arguments.get(i))) {
                return false;
            }
        }
        for (int i = fixed; i < arguments.size(); i++) {
            if (!isApplicableJavaArg(comp, arguments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void deriveJavaConstructor(
            Constructor<?> ctor,
            boolean varArgs,
            List<BabbleParser.CallParameterContext> arguments,
            BabbleParser.CallContext call) {
        Class<?> java = ctor.getDeclaringClass();
        if (Modifier.isPublic(ctor.getModifiers())
                && Modifier.isPublic(java.getModifiers())
                && JavaTypes.isAccessible(java)
                && staticallyApplicable(ctor.getParameterTypes(), varArgs, arguments)) {
            pass.analysis
                    .lowering()
                    .setJavaConstructorTarget(
                            call, new LoweringTargets.JavaConstructorTarget(ctor, varArgs));
        }
    }

    private void deriveJavaMethodCall(
            Method best,
            boolean varArgs,
            List<BabbleParser.CallParameterContext> arguments,
            BabbleParser.CallContext call) {
        if (!(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        Type receiverType = types.get(sel.expression());
        while (receiverType instanceof Type.TypeVar tv
                && tv.bound() != null
                && tv.bound() != Type.ERROR) {
            receiverType = tv.bound();
        }
        receiverType = TypeAlgebra.unwrapFrozen(receiverType);
        boolean isJavaLike =
                receiverType instanceof Type.JavaClassValue
                        || receiverType instanceof Type.JavaInstance
                        || receiverType instanceof Type.Array
                        || receiverType == Type.STRING;
        if (!isJavaLike && receiverType instanceof Type.Intersection inter) {
            isJavaLike =
                    inter.members().stream()
                            .anyMatch(m ->
                                    m instanceof Type.JavaInstance
                                            || m instanceof Type.Instance inst
                                                    && TypeAlgebra.implementsJava(
                                                            inst.node(), best.getDeclaringClass()));
        } else if (!isJavaLike && receiverType instanceof Type.Instance inst) {
            isJavaLike = TypeAlgebra.implementsJava(inst.node(), best.getDeclaringClass());
        }
        if (!isJavaLike) {
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
                || !staticallyApplicable(accessible.getParameterTypes(), varArgs, arguments)) {
            return;
        }
        boolean unwrapReceiver = !isStatic && receiverType != Type.STRING;
        pass.analysis
                .lowering()
                .setJavaCallTarget(
                        call,
                        new LoweringTargets.JavaCallTarget(
                                accessible, isStatic, unwrapReceiver, varArgs));
    }

    /**
     * A call of a known Babble function : its literal, for a direct call, and its primitive
     * signature ({@code RM001}, {@code BIP021-D3/D4}).
     */
    private void deriveBabbleCall(
            Type.Function function,
            LoweringTargets.ArgumentBinding binding,
            BabbleParser.CallContext ctx) {
        if (binding == null) {
            return;
        }
        List<Type.Parameter> params = function.parameters();
        int paramCount = params.size();
        PrimitiveKind[] paramKinds = new PrimitiveKind[paramCount];
        boolean hasPrimitive = false;
        int slots = 0;
        int optionalParamBits = 0;
        int optionalCount = 0;
        for (int i = 0; i < paramCount; i++) {
            Type.Parameter p = params.get(i);
            PrimitiveKind kind = p.rest() ? PrimitiveKind.NONE : PrimitiveKind.of(p.type());
            paramKinds[i] = kind;
            slots += kind.slots();
            if (kind.isPrimitive()) {
                hasPrimitive = true;
            }
            if (p.optional()) {
                optionalCount++;
                if (i < 32) {
                    optionalParamBits |= (1 << i);
                }
            }
        }
        PrimitiveKind returnKind = PrimitiveKind.of(function.result());
        if (returnKind.isPrimitive()) {
            hasPrimitive = true;
        }
        if (slots <= 200) {
            BabbleParser.ExpressionContext calleeExpr = ctx.expression();
            while (calleeExpr instanceof BabbleParser.BlockExpressionContext b
                    && b.block().expression().size() == 1) {
                calleeExpr = b.block().expression(0);
            }
            BabbleParser.FunctionLiteralContext lit = checker.functionLiterals().get(function);
            if (lit != null && checker.inFile(lit) && checker.scopeOf(lit).memberOf == null) {
                if (calleeExpr == lit) {
                    pass.analysis.calls().setBabbleCallFunction(ctx, lit);
                } else if (calleeExpr instanceof BabbleParser.SelectorContext sel
                        && sel.expression() == null) {
                    Object resolved =
                            TypeChecker.resolveName(
                                    sel, sel.ID().getText(), checker.occurrenceScope(sel));
                    if (resolved instanceof BabbleParser.DefExpressionContext def
                            && checker.inFile(def)
                            && !checker.isReassigned(def)
                            && TypeChecker.unwrapFunctionLiteral(def.value) == lit) {
                        pass.analysis.calls().setBabbleCallFunction(ctx, lit);
                    }
                }
            } else if (calleeExpr instanceof BabbleParser.SelectorContext sel
                    && sel.safe == null
                    && sel.expression() != null
                    && types.get(sel.expression()) instanceof Type.Module modType) {
                ModuleInterface targetMod = modType.module();
                if (pass.analysis.isNonCyclicModule(targetMod)) {
                    String exportName = ScopeAnalysis.selectorName(sel);
                    LoweringTargets.ExportedFunctionTarget ext =
                            targetMod.exportedFunction(exportName);
                    if (ext != null
                            && binding.paramCount() == ext.signature().parameterKinds().length
                            && (ext.signature().hasSpec() || ext.signature().isSpread())) {
                        pass.analysis.lowering().setExternalFunctionTarget(ctx, ext);
                    }
                }
            }
            boolean canUseTypedIndy =
                    hasPrimitive
                            && !binding.hasRest()
                            && optionalCount <= 32
                            && (!binding.hasOmitted() || paramCount <= 32);
            if (canUseTypedIndy) {
                pass.analysis
                        .calls()
                        .setBabbleCallSignature(
                                ctx,
                                new LoweringTargets.FunctionSignature(
                                        paramKinds, returnKind, optionalParamBits));
            }
        }
    }

    /**
     * A call of a member of a Babble interface : its {@code INVOKEINTERFACE} target ({@code RM003},
     * {@code BIP021-D3/D4}).
     */
    private void deriveInterfaceCall(
            LoweringTargets.ArgumentBinding binding, BabbleParser.CallContext ctx) {
        if (binding == null
                || !checker.inFile(ctx)
                || !(ctx.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.safe != null
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        String memberName = ScopeAnalysis.selectorName(sel);
        TypeHierarchyIndex.OwnerMember found =
                findDeclaringInterfaceOnType(types.get(sel.expression()), memberName);
        LoweringTargets.InterfaceCallTarget target =
                buildInterfaceCallTarget(found, binding.paramCount());
        if (target != null) {
            pass.analysis.lowering().setInterfaceCallTarget(ctx, target);
        }
    }

    private record FoundInterfaceWithType(
            TypeHierarchyIndex.OwnerMember found, Type substitutedType) {}

    private TypeHierarchyIndex.OwnerMember findDeclaringInterfaceOnType(
            Type recv, String memberName) {
        FoundInterfaceWithType best = findBestInterfaceMember(recv, memberName);
        return best != null ? best.found() : null;
    }

    private FoundInterfaceWithType findBestInterfaceMember(Type recv, String memberName) {
        while (recv instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            recv = tv.bound();
        }
        recv = TypeAlgebra.unwrapFrozen(recv);
        if (recv instanceof Type.Instance recvInst && recvInst.node().isInterface()) {
            TypeHierarchyIndex.OwnerMember found =
                    hierarchy.findDeclaringInterfaceMember(recvInst.node(), memberName);
            if (found == null) {
                return null;
            }
            Type subType = hierarchy.substitutedMemberType(recvInst, found.owner(), found.member());
            return new FoundInterfaceWithType(found, subType);
        }
        if (recv instanceof Type.Intersection inter) {
            FoundInterfaceWithType best = null;
            for (Type member : inter.members()) {
                FoundInterfaceWithType cand = findBestInterfaceMember(member, memberName);
                if (cand == null) {
                    continue;
                }
                if (best == null) {
                    best = cand;
                } else if (!cand.substitutedType().equals(best.substitutedType())
                        && checker.algebra()
                                .assignable(cand.substitutedType(), best.substitutedType())
                        && !checker.algebra()
                                .assignable(best.substitutedType(), cand.substitutedType())) {
                    best = cand;
                }
            }
            return best;
        }
        return null;
    }

    private boolean deriveRangeCall(
            List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext call) {
        if (!(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.expression() == null
                || sel.safe != null) {
            return false;
        }
        Type elem = Type.rangeElement(types.get(sel.expression()));
        if (elem == null) {
            return false;
        }
        for (BabbleParser.CallParameterContext p : arguments) {
            if (p.spread != null) {
                return false;
            }
        }
        Method target =
                resolveRangeJavaMethod(elem, ScopeAnalysis.selectorName(sel), arguments.size());
        if (target == null) {
            return false;
        }
        pass.analysis
                .lowering()
                .setJavaCallTarget(call, new LoweringTargets.JavaCallTarget(target, false, false));
        return true;
    }

    private static Method resolveRangeJavaMethod(Type elem, String name, int argCount) {
        RangeOps ops = RangeOps.of(elem);
        return switch (name) {
            case "start" -> argCount == 0 ? ops.start : null;
            case "end" -> argCount == 0 ? ops.end : null;
            case "inclusive" -> argCount == 0 ? RANGE_INCLUSIVE : null;
            case "isEmpty" -> argCount == 0 ? RANGE_IS_EMPTY : null;
            case "contains" -> argCount == 1 ? ops.contains : null;
            case "step" -> argCount == 1 ? ops.step : null;
            case "reversed" -> argCount == 0 ? ops.reversed : null;
            case "iterator" -> argCount == 0 ? ops.iterator : null;
            case "equals" -> argCount == 1 ? OBJECT_EQUALS : null;
            case "hashCode" -> argCount == 0 ? OBJECT_HASH_CODE : null;
            case "toString" -> argCount == 0 ? OBJECT_TO_STRING : null;
            default -> null;
        };
    }

    LoweringTargets.InterfaceCallTarget deriveZeroArgInterfaceTarget(
            Type.Instance inst, String memberName) {
        if (inst == null || !inst.node().isInterface()) {
            return null;
        }
        return buildInterfaceCallTarget(
                hierarchy.findDeclaringInterfaceMember(inst.node(), memberName), 0);
    }

    private LoweringTargets.InterfaceCallTarget buildInterfaceCallTarget(
            TypeHierarchyIndex.OwnerMember found, int expectedArity) {
        if (found == null) {
            return null;
        }
        Type mt = checker.memberType(found.owner(), found.member());
        if (!(mt instanceof Type.Function uninstFn)
                || uninstFn.parameters().size() != expectedArity) {
            return null;
        }
        LoweringTargets.FunctionSignature sig =
                TypeHierarchyIndex.uninstantiatedSignature(uninstFn);
        if (sig == null) {
            return null;
        }
        return new LoweringTargets.InterfaceCallTarget(
                found.owner().id, found.member().name, sig, true);
    }
}
