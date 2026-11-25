package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
            pass.analysis.lowering().widenNumeric(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, Object> entry : checker.resolvedLiterals().entrySet()) {
            pass.analysis.lowering().setResolvedLiteral(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, String> entry : checker.callChecks().entrySet()) {
            pass.analysis.lowering().setCheckedType(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.lowering().setExpressionKind(entry.getKey(), PrimitiveKind.of(entry.getValue()));
                if (checker.isDeeplyImmutable(entry.getValue())) {
                    pass.analysis.lowering().markExpressionDeeplyImmutable(entry.getKey());
                }
                if (entry.getValue() == Type.STRING) {
                    pass.analysis.lowering().markExpressionString(entry.getKey());
                }
                if (entry.getValue() instanceof Type.Frozen) {
                    pass.analysis.lowering().markExpressionFrozen(entry.getKey());
                }
            }
            if (entry.getKey() instanceof BabbleParser.IsExpressionContext isCtx && checker.inFile(isCtx)) {
                Type isTarget = checker.isTargetType(isCtx);
                if (isTarget instanceof Type.Instance inst
                        && (inst.node().ctx == null || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx))) {
                    pass.analysis.lowering().setIsTarget(isCtx, new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
                } else if (isTarget instanceof Type.Array array) {
                    Class<?> checked = JavaTypeBridge.checkedArrayClass(array);
                    if (JavaTypeBridge.isBytecodeAccessible(checked)) {
                        pass.analysis.lowering().setIsTarget(isCtx, new LoweringTargets.IsTarget(null, null,
                                checked.getName().replace('.', '/')));
                    }
                } else if (isTarget instanceof Type.Intersection inter) {
                    List<LoweringTargets.IsTarget> targets = intersectionIsTargets(inter);
                    if (!targets.isEmpty()) {
                        pass.analysis.lowering().setIsIntersectionTargets(isCtx, targets);
                    }
                }
            }
            if (entry.getKey() instanceof BabbleParser.AsExpressionContext asCtx && checker.inFile(asCtx)) {
                if (entry.getValue() instanceof Type.Instance inst
                        && (inst.node().ctx == null || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx))) {
                    pass.analysis.lowering().setAsTarget(asCtx, new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
                } else if (entry.getValue() instanceof Type.Array array) {
                    Class<?> checked = JavaTypeBridge.checkedArrayClass(array);
                    if (JavaTypeBridge.isBytecodeAccessible(checked)) {
                        pass.analysis.lowering().setAsTarget(asCtx, new LoweringTargets.IsTarget(null, null,
                                checked.getName().replace('.', '/')));
                    }
                }
            }
        }
        for (ParserRuleContext decl : pass.definitionScopes.keySet()) {
            if (!checker.inFile(decl)) {
                continue;
            }
            if (decl instanceof BabbleParser.DefExpressionContext def) {
                Type t = checker.annotator().declaredType(def);
                if (t == null) {
                    LoweringTargets.DefKind defKind = pass.analysis.lowering().defTarget(def).kind();
                    t = (defKind == LoweringTargets.DefKind.MEMBER || defKind == LoweringTargets.DefKind.STATIC)
                            ? inferredMembers.get(def) : inferredDefs.get(def);
                }
                if (t != null) {
                    pass.analysis.lowering().setDeclarationKind(def, PrimitiveKind.of(t));
                }
            } else if (ScopeAnalysis.isParamDecl(decl)) {
                Type t = declarationTypes.get(decl);
                BabbleParser.TypeContext paramType = ScopeAnalysis.paramType(decl);
                if (t == null && paramType != null) {
                    t = checker.annotations().get(paramType);
                }
                if (t == null) {
                    t = inferredParams.get(decl);
                }
                if (t != null) {
                    pass.analysis.lowering().setDeclarationKind(decl, PrimitiveKind.of(t));
                }
            } else if (decl instanceof BabbleParser.ForExpressionContext forExpr) {
                Type t = declarationTypes.get(forExpr);
                if (t != null) {
                    pass.analysis.lowering().setDeclarationKind(forExpr, PrimitiveKind.of(t));
                }
            }
        }
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            if (entry.getKey() instanceof BabbleParser.ForExpressionContext forExpr && checker.inFile(forExpr)) {
                publishForLoopTarget(forExpr, declarationTypes, widened);
            } else if (entry.getKey() instanceof BabbleParser.ThrowExpressionContext throwExpr && checker.inFile(throwExpr)) {
                Type thrownType = types.get(throwExpr.expression());
                pass.analysis.lowering().setThrowTarget(throwExpr,
                        new LoweringTargets.ThrowTarget(isGuaranteedJavaThrowable(thrownType)));
            } else if (entry.getKey() instanceof BabbleParser.TryExpressionContext tryExpr && checker.inFile(tryExpr)) {
                for (BabbleParser.CatchClauseContext cc : tryExpr.catchClause()) {
                    pass.analysis.lowering().setCatchTarget(cc, buildCatchTarget(cc));
                }
            }
        }
        for (TypeNode typeNode : pass.types) {
            if (!checker.inFile(typeNode.ctx)) {
                continue;
            }
            for (TypeNode.Member member : typeNode.members.values()) {
                if (member.declaration == null || !checker.inFile(member.declaration) || member.hasFunction) {
                    continue;
                }
                Type t = checker.annotator().declaredType(member.declaration);
                if (t == null && ScopeAnalysis.isParamDecl(member.declaration)) {
                    t = declarationTypes.get(member.declaration);
                    if (t == null) {
                        t = inferredParams.get(member.declaration);
                    }
                } else if (t == null && member.declaration instanceof BabbleParser.DefExpressionContext def) {
                    t = inferredMembers.get(def);
                }
                if (t != null) {
                    pass.analysis.lowering().setDeclarationKind(member.declaration, PrimitiveKind.of(t));
                }
            }
        }
        for (Map.Entry<ParserRuleContext, TypeChecker.ResolvedOverloadCall> ovEntry : checker.resolvedOverloadCalls().entrySet()) {
            ParserRuleContext site = ovEntry.getKey();
            if (!(site instanceof BabbleParser.CallContext) && checker.inFile(site)) {
                publishOperatorOverloadDispatch(site, ovEntry.getValue());
            }
        }
        for (BabbleParser.FunctionLiteralContext fn : checker.functions()) {
            if (types.get(fn) instanceof Type.Function tf) {
                pass.analysis.lowering().setFunctionReturnKind(fn, PrimitiveKind.of(tf.result()));
                if (checker.inFile(fn) && fn.fnParameters() != null) {
                    List<BabbleParser.FnParamContext> paramDecls = fn.fnParameters().fnParam();
                    List<Type.Parameter> tParams = tf.parameters();
                    int n = Math.min(paramDecls.size(), tParams.size());
                    for (int i = 0; i < n; i++) {
                        Type.Parameter tp = tParams.get(i);
                        String recTypeId = null;
                        Type unwrapped = TypeAlgebra.unwrapFrozen(tp.type());
                        if (unwrapped instanceof Type.Instance inst
                                && inst.node().kind == ClassShape.Kind.VALUE_CLASS
                                && (inst.node().ctx == null || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx)
                                || checker.inFile(inst.node().ctx))) {
                            recTypeId = inst.node().id;
                        }
                        boolean borrow = tp.borrow() || (!tp.explicitShared() && !tp.unique());
                        pass.analysis.lowering().setParamOwnership(paramDecls.get(i),
                                new LoweringTargets.ParamOwnership(tp.mut(), borrow, tp.unique(), recTypeId));
                    }
                }
            }
        }
        for (Map.Entry<ParserRuleContext, Type> entry : types.entrySet()) {
            if (entry.getKey() instanceof BabbleParser.OverloadExpressionContext ov
                    && checker.inFile(ov) && entry.getValue() instanceof Type.Overloaded overloaded) {
                List<LoweringTargets.OverloadAltMetaInfo> metas = new ArrayList<>(overloaded.alternatives().size());
                for (Type.Function alt : overloaded.alternatives()) {
                    metas.add(buildOverloadAltMeta(alt));
                }
                pass.analysis.calls().setOverloadMetas(ov, metas);
            }
        }
        for (Map.Entry<ParserRuleContext, LoweringTargets.OverloadSelection> entry : checker.overloadSelections().entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.calls().setOverloadSelection(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<ParserRuleContext, LoweringTargets.JavaSamTarget> entry : checker.samTargets().entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.lowering().setJavaSamTarget(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<BabbleParser.ForExpressionContext, LoweringTargets.JavaSamTarget> entry : checker.forElemSamTargets().entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.lowering().setForElemSamTarget(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<BabbleParser.AsExpressionContext, LoweringTargets.JavaSamTarget> entry : checker.asSamTargets().entrySet()) {
            if (checker.inFile(entry.getKey())) {
                pass.analysis.lowering().setAsSamTarget(entry.getKey(), entry.getValue());
            }
        }
        publishDefinitelyDefined(pass, checker);
        publishClassLoweringFacts();
        deriveTargets(file);
        publishTypeHierarchies(pass, checker);
        if (pass.analysis.moduleInterface() != null) {
            pass.analysis.moduleInterface().setTypeHierarchies(pass.analysis.hierarchy().typeHierarchies());
        }
    }

    private static List<LoweringTargets.IsTarget> intersectionIsTargets(Type.Intersection inter) {
        List<LoweringTargets.IsTarget> targets = new ArrayList<>(inter.members().size());
        for (Type member : inter.members()) {
            if (member instanceof Type.Instance inst && inst.node().isInterface()
                    && (inst.node().ctx == null || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx))) {
                targets.add(new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
            } else if (member instanceof Type.JavaInstance ji && ji.type().isInterface()
                    && JavaTypeBridge.isBytecodeAccessible(ji.type())) {
                targets.add(new LoweringTargets.IsTarget(null, null, ji.type().getName().replace('.', '/')));
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

    /** Populates {@link TypeNode} lowering facts preserved across {@code detachAst()} for direct dispatch ({@code BIP014-I4}). */
    private void publishClassLoweringFacts() {
        Resolution resolution = pass.analysis.resolution();
        for (TypeNode node : pass.types) {
            if (node.isInterface() || node.ctx == null || !checker.inFile(node.ctx)) {
                continue;
            }
            for (TypeNode.Member member : node.members.values()) {
                if (!member.isMethod()) {
                    PrimitiveKind fk = member.declaration != null
                            ? pass.analysis.lowering().declarationKind(member.declaration)
                            : PrimitiveKind.NONE;
                    node.memberKinds.put(member.name, fk);
                    if (node.parameterMembers.contains(member.index)) {
                        node.definiteFields.add(member.name);
                    }
                } else if (!member.isOverload && member.function != null
                        && types.get(member.function) instanceof Type.Function tf
                        && tf.typeParameters().isEmpty()) {
                    LoweringTargets.FunctionSignature sig = uninstantiatedSignature(tf);
                    if (sig != null) {
                        node.methodSignatures.put(member.name, sig);
                    }
                } else if (member.isOverload && member.overloadFunctions != null && !member.overloadFunctions.isEmpty()) {
                    for (int ai = 0; ai < member.overloadFunctions.size(); ai++) {
                        BabbleParser.FunctionLiteralContext altFn = member.overloadFunctions.get(ai);
                        if (altFn != null && types.get(altFn) instanceof Type.Function tf && tf.typeParameters().isEmpty()) {
                            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(tf);
                            if (sig != null) {
                                node.overloadMethodSignatures.put(member.name + "$" + ai, sig);
                            }
                        }
                    }
                }
            }
            if (node.kind == ClassShape.Kind.VALUE_CLASS
                    && node.ctx instanceof BabbleParser.ClassExpressionContext clsCtx
                    && resolution != null
                    && resolution.captured(clsCtx).isEmpty()
                    && clsCtx.classParameters() != null) {
                List<BabbleParser.ClassParamContext> paramCtxs = clsCtx.classParameters().classParam();
                boolean noHiddenCells = true;
                for (Resolution.Variable v : resolution.declared(clsCtx)) {
                    if (v.isCaptured()) {
                        noHiddenCells = false;
                        break;
                    }
                }
                if (noHiddenCells && paramCtxs.size() <= 8 && paramCtxs.size() == node.parameters.size()) {
                    PrimitiveKind[] paramKinds = new PrimitiveKind[paramCtxs.size()];
                    int slots = 3; // this, BabbleClass, ScriptRuntime
                    boolean paramsOk = true;
                    for (int i = 0; i < paramCtxs.size(); i++) {
                        BabbleParser.ClassParamContext pc = paramCtxs.get(i);
                        if (pass.analysis.lowering().javaSamTarget(pc) != null) {
                            paramsOk = false;
                            break;
                        }
                        PrimitiveKind pk = pass.analysis.lowering().declarationKind(pc);
                        paramKinds[i] = pk;
                        slots += pk.slots();
                    }
                    boolean bodyOk = paramsOk && slots <= 200;
                    if (bodyOk && clsCtx.classBlock != null) {
                        for (BabbleParser.ExpressionContext expr : clsCtx.classBlock.expression()) {
                            if (!(expr instanceof BabbleParser.DefExpressionContext def)) {
                                bodyOk = false;
                                break;
                            }
                            LoweringTargets.DefTarget dt = pass.analysis.lowering().defTarget(def);
                            TypeNode.Member m = node.members.get(def.name.getText());
                            if (dt.kind() != LoweringTargets.DefKind.MEMBER || m == null || !m.isMethod()
                                    || !isPureMethodDefValue(def.value)) {
                                bodyOk = false;
                                break;
                            }
                        }
                    }
                    if (bodyOk) {
                        node.fastRecord = true;
                        node.fastRecordParamKinds = paramKinds;
                    }
                }
            }
        }
    }

    private static boolean isPureMethodDefValue(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.FunctionLiteralContext) {
            return true;
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext ov) {
            for (BabbleParser.ExpressionContext op : ov.expression()) {
                if (!(op instanceof BabbleParser.FunctionLiteralContext)) {
                    return false;
                }
            }
            return !ov.expression().isEmpty();
        }
        return false;
    }

    // ---------------------------------------------------------------- static dispatch targets (RM001, RM003)

    /** Derives the static target of every selection, Java field write, operator method, and call from the converged types. */
    private void deriveTargets(ParseTree tree) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            deriveSelection(selector);
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) != null
                && JavaTypeBridge.assignedField(types.get(ScopeAnalysis.assignReceiver(assign)), ScopeAnalysis.assignName(assign)) instanceof JavaTypeBridge.AssignedField field) {
            LoweringTargets.JavaFieldTarget target = field.target(types.get(assign));
            if (target != null) {
                pass.analysis.lowering().setJavaFieldTarget(assign, target);
            }
        } else if (tree instanceof BabbleParser.CallContext call && checker.inFile(call)) {
            deriveCall(call);
        } else if (tree instanceof BabbleParser.BinaryOpContext bin && checker.inFile(bin)
                && pass.analysis.calls().overloadDispatch(bin) == null) {
            deriveOperatorMethodCall(bin, bin.left, Operators.method(bin.op.getText()), 1);
        } else if (tree instanceof BabbleParser.NegateContext neg && checker.inFile(neg)
                && pass.analysis.calls().overloadDispatch(neg) == null) {
            deriveOperatorMethodCall(neg, neg.operand, "neg", 0);
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

    private void deriveOperatorMethodCall(ParserRuleContext site, BabbleParser.ExpressionContext recvExpr,
                                          String method, int expectedArity) {
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
        if (sig == null || sig.parameterKinds().length != expectedArity
                || sig.hasOptionalMask() || sig.restIndex() >= 0) {
            return;
        }
        pass.analysis.lowering().setDirectMethodTarget(site, new LoweringTargets.DirectMethodTarget(
                inst.node().id, method, -1, sig, inst.node().kind == ClassShape.Kind.VALUE_CLASS));
        if (member.function != null && checker.inFile(member.function)) {
            pass.analysis.lowering().setDirectMethodFunction(site, member.function);
        }
    }

    /** {@code receiver.name} : an indexed module export, a compiled Babble class/record field, or a Java field. */
    private void deriveSelection(BabbleParser.SelectorContext selector) {
        if (selector.expression() == null || selector.safe != null || !(types.get(selector.expression()) instanceof Type receiver)) {
            return;
        }
        String name = ScopeAnalysis.selectorName(selector);
        if (receiver instanceof Type.Module module) {
            int index = module.module().exportIndex(name);
            if (module.module().summary() != null && module.module().exports().contains(name) && index >= 0) {
                pass.analysis.lowering().setModuleExportTarget(selector, new LoweringTargets.ModuleExportTarget(index, name, module.module().name()));
            }
        } else if (receiver instanceof Type.ClassValue cv) {
            TypeNode.Member sm = cv.node().statics.get(name);
            if (sm != null && !sm.isPrivate() && !sm.isMutable()) {
                pass.analysis.lowering().markPublicStaticDefSelect(selector);
            }
        } else {
            LoweringTargets.JavaFieldTarget target = fieldReadTarget(receiver, name, checker.algebra());
            if (target != null) {
                pass.analysis.lowering().setJavaFieldTarget(selector, target);
                return;
            }
            if (!(selector.expression() instanceof BabbleParser.ThisExpressionContext)) {
                Type unwrapped = TypeAlgebra.unwrapFrozen(receiver);
                if (unwrapped instanceof Type.Instance inst && !inst.node().isInterface()) {
                    TypeNode.Member member = inst.node().members.get(name);
                    if (member != null && !member.isMethod()) {
                        PrimitiveKind fk = inst.node().memberKinds.getOrDefault(name, PrimitiveKind.NONE);
                        boolean immutableField = inst.node().kind == ClassShape.Kind.VALUE_CLASS || !member.isMutable();
                        boolean defDefined = inst.node().definiteFields.contains(name);
                        pass.analysis.lowering().setClassFieldTarget(selector, new LoweringTargets.ClassFieldTarget(
                                inst.node().id, name, member.index, fk, immutableField, defDefined));
                    }
                }
            }
        }
    }

    /** The static target of reading the Java field {@code receiver.name}, or null when it isn't one. */
    static LoweringTargets.JavaFieldTarget fieldReadTarget(Type receiver, String name, TypeAlgebra algebra) {
        JavaTypeBridge.JavaMember member;
        if (receiver instanceof Type.JavaClassValue value) {
            if (JavaTypeBridge.publicMemberClass(value.type(), name) != null) {
                return null;
            }
            member = JavaTypeBridge.javaMember(value.type(), List.of(), name, true, algebra);
        } else if (receiver instanceof Type.JavaInstance instance) {
            member = JavaTypeBridge.javaMember(instance.type(), instance.typeArguments(), name, false, algebra);
        } else if (receiver instanceof Type.Array array) {
            member = JavaTypeBridge.javaMember(JavaTypeBridge.checkedArrayClass(array), List.of(), name, false, algebra);
        } else if (receiver instanceof Type.Intersection inter) {
            for (Type m : inter.members()) {
                if (m instanceof Type.JavaInstance ji) {
                    JavaTypeBridge.JavaMember jm = JavaTypeBridge.javaMember(ji.type(), ji.typeArguments(), name, false, algebra);
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
        boolean safe = call.expression() instanceof BabbleParser.SelectorContext sel && sel.safe != null;
        if (!safe && arguments.size() == 1 && ScopeAnalysis.callParamName(arguments.get(0)) == null
                && call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() == null
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
        Method method = checker.callSolver().resolvedMethod(call);
        LoweringTargets.SpreadAdapt[] spreadAdapts = checker.callSolver().resolvedSpreadAdapts(call);
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
        LoweringTargets.ArgumentBinding binding = deriveArgumentBinding(callee, arguments, call, spreadAdapts);
        if (binding != null) {
            pass.analysis.calls().setArgumentBinding(call, binding);
        }
        if (!safe && callee instanceof Type.ClassValue cv && cv.node().fastRecord
                && binding != null && !binding.hasOmitted() && !binding.hasRest() && !binding.hasSpreads()
                && binding.paramCount() == cv.node().fastRecordParamKinds.length) {
            pass.analysis.lowering().setRecordNewTarget(call,
                    new LoweringTargets.RecordNewTarget(cv.node().id, cv.node().fastRecordParamKinds));
            return;
        }
        if (safe || !(callee instanceof Type.Function function)) {
            return;
        }
        if (function.typeParameters().isEmpty()) {
            deriveBabbleCall(function, arguments, binding, call);
            deriveDirectClassMethodCall(binding, call);
            deriveInterfaceCall(arguments, binding, call);
        } else if (types.get(call) != Type.ERROR) {
            deriveInterfaceCall(arguments, binding, call);
        }
    }

    private void deriveDirectClassMethodCall(LoweringTargets.ArgumentBinding binding, BabbleParser.CallContext call) {
        if (binding == null || !(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.safe != null || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext
                || sel.expression() instanceof BabbleParser.ThisExpressionContext) {
            return;
        }
        Type recv = TypeAlgebra.unwrapFrozen(types.get(sel.expression()));
        if (!(recv instanceof Type.Instance inst) || inst.node().isInterface()) {
            return;
        }
        String memberName = ScopeAnalysis.selectorName(sel);
        TypeNode.Member member = inst.node().members.get(memberName);
        if (member == null || !member.isMethod() || member.isOverload) {
            return;
        }
        LoweringTargets.FunctionSignature sig = inst.node().methodSignatures.get(memberName);
        if (sig == null || binding.paramCount() != sig.parameterKinds().length) {
            return;
        }
        pass.analysis.lowering().setDirectMethodTarget(call, new LoweringTargets.DirectMethodTarget(
                inst.node().id, memberName, -1, sig, inst.node().kind == ClassShape.Kind.VALUE_CLASS));
        if (member.function != null && checker.inFile(member.function)) {
            pass.analysis.lowering().setDirectMethodFunction(call, member.function);
        }
    }

    private void deriveOverloadCall(List<BabbleParser.CallParameterContext> arguments,
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
            LoweringTargets.ArgumentBinding binding = CallSolver.computeArgumentBinding(
                    alt.parameters(), arguments, restKind, spreadAdapts);
            if (binding != null) {
                pass.analysis.calls().setArgumentBinding(call, binding);
            }
            BabbleParser.FunctionLiteralContext directTarget = resolved.directTarget();
            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
            if (directTarget != null && checker.inFile(directTarget) && alt.typeParameters().isEmpty() && binding != null
                    && checker.scopeOf(directTarget).memberOf == null) {
                // Free local function alternative: record babbleCallFunction & babbleCallSignature for DirectBabbleCall.
                pass.analysis.calls().setBabbleCallFunction(call, directTarget);
                pass.analysis.calls().setBabbleCallSignature(call, sig);
            }
            // Overloaded Babble interface call -> INVOKEINTERFACE bm$<member>$<k> (BIP021-D13).
            if (binding != null && checker.inFile(call)
                    && call.expression() instanceof BabbleParser.SelectorContext sel
                    && sel.safe == null && sel.expression() != null
                    && !(sel.expression() instanceof BabbleParser.SuperExpressionContext)) {
                String memberName = ScopeAnalysis.selectorName(sel);
                Type recvType = types.get(sel.expression());
                FoundInterfaceMember found = findDeclaringInterfaceOnType(recvType, memberName);
                if (found != null) {
                    Type mt = checker.memberType(found.iface(), found.member());
                    if (mt instanceof Type.Overloaded ifaceOv) {
                        int ifaceAltIdx = (recvType instanceof Type.Instance ri && ri.node() == found.iface())
                                ? resolved.altIndex()
                                : OverloadSolver.selectAlternativeForFunction(checker.algebra(), ifaceOv, alt, false);
                        if (ifaceAltIdx >= 0 && ifaceAltIdx < ifaceOv.alternatives().size()) {
                            LoweringTargets.FunctionSignature ifaceSig = uninstantiatedSignature(ifaceOv.alternatives().get(ifaceAltIdx));
                            if (ifaceSig != null && binding.paramCount() == ifaceSig.parameterKinds().length) {
                                pass.analysis.lowering().setInterfaceCallTarget(call, new LoweringTargets.InterfaceCallTarget(
                                        found.iface().id, found.member().name + "$" + ifaceAltIdx, ifaceSig, true));
                            }
                        }
                    }
                }
            }
            CallMetadata.OverloadCallTarget target = new CallMetadata.OverloadCallTarget(
                    resolved.altIndex(),
                    OverloadSolver.signatureKey(alt),
                    resolved.exactSet(),
                    directTarget != null && checker.inFile(directTarget) ? directTarget : null,
                    sig,
                    binding);
            pass.analysis.calls().setOverloadDispatch(call, new CallMetadata.OverloadDispatchTarget(target, null, callResultKind));
            return;
        }

        List<CallMetadata.OverloadSplitBranch> branches = new ArrayList<>(resolved.splitBranches().size());
        for (TypeChecker.ResolvedSplitBranch rb : resolved.splitBranches()) {
            Type.Function alt = rb.altType();
            LoweringTargets.ArgumentBinding binding = CallSolver.computeArgumentBinding(
                    alt.parameters(), arguments, rb.restKind(), rb.spreadAdapts());
            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
            BabbleParser.FunctionLiteralContext directTarget = rb.directTarget() != null && checker.inFile(rb.directTarget())
                    ? rb.directTarget() : null;
            CallMetadata.OverloadCallTarget target = new CallMetadata.OverloadCallTarget(
                    rb.altIndex(),
                    OverloadSolver.signatureKey(alt),
                    rb.exactSet(),
                    directTarget,
                    sig,
                    binding);
            branches.add(new CallMetadata.OverloadSplitBranch(
                    rb.checks(), target, PrimitiveKind.of(rb.resultType())));
        }
        pass.analysis.calls().setOverloadDispatch(call, new CallMetadata.OverloadDispatchTarget(null, List.copyOf(branches), callResultKind));
    }

    private void publishOperatorOverloadDispatch(ParserRuleContext site, TypeChecker.ResolvedOverloadCall resolved) {
        PrimitiveKind callResultKind = PrimitiveKind.of(resolved.resultType());
        if (!resolved.isSplit()) {
            Type.Function alt = resolved.altType();
            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
            BabbleParser.FunctionLiteralContext directTarget = resolved.directTarget() != null && checker.inFile(resolved.directTarget())
                    ? resolved.directTarget() : null;
            CallMetadata.OverloadCallTarget target = new CallMetadata.OverloadCallTarget(
                    resolved.altIndex(),
                    OverloadSolver.signatureKey(alt),
                    resolved.exactSet(),
                    directTarget,
                    sig,
                    null);
            pass.analysis.calls().setOverloadDispatch(site, new CallMetadata.OverloadDispatchTarget(target, null, callResultKind));
            if (directTarget == null && site instanceof BabbleParser.BinaryOpContext bin) {
                String method = Operators.method(bin.op.getText());
                Type left = TypeAlgebra.unwrapFrozen(types.get(bin.left));
                if (method != null && left instanceof Type.Instance inst && !inst.node().isInterface()) {
                    LoweringTargets.FunctionSignature ovSig = inst.node().overloadMethodSignatures.get(method + "$" + resolved.altIndex());
                    if (ovSig != null && ovSig.parameterKinds().length == 1 && !ovSig.hasOptionalMask() && ovSig.restIndex() < 0) {
                        pass.analysis.lowering().setDirectMethodTarget(site, new LoweringTargets.DirectMethodTarget(
                                inst.node().id, method, resolved.altIndex(), ovSig, inst.node().kind == ClassShape.Kind.VALUE_CLASS));
                    }
                }
            }
            return;
        }
        List<CallMetadata.OverloadSplitBranch> branches = new ArrayList<>(resolved.splitBranches().size());
        for (TypeChecker.ResolvedSplitBranch rb : resolved.splitBranches()) {
            Type.Function alt = rb.altType();
            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
            BabbleParser.FunctionLiteralContext directTarget = rb.directTarget() != null && checker.inFile(rb.directTarget())
                    ? rb.directTarget() : null;
            CallMetadata.OverloadCallTarget target = new CallMetadata.OverloadCallTarget(
                    rb.altIndex(),
                    OverloadSolver.signatureKey(alt),
                    rb.exactSet(),
                    directTarget,
                    sig,
                    null);
            branches.add(new CallMetadata.OverloadSplitBranch(
                    rb.checks(), target, PrimitiveKind.of(rb.resultType())));
        }
        pass.analysis.calls().setOverloadDispatch(site, new CallMetadata.OverloadDispatchTarget(null, List.copyOf(branches), callResultKind));
    }

    private LoweringTargets.ArgumentBinding deriveArgumentBinding(Type callee,
                                                                List<BabbleParser.CallParameterContext> arguments,
                                                                BabbleParser.CallContext call,
                                                                LoweringTargets.SpreadAdapt[] spreadAdapts) {
        PrimitiveKind restKind = checker.callSolver().resolvedRestKind(call);
        if (callee instanceof Type.Function fn) {
            return CallSolver.computeArgumentBinding(fn.parameters(), arguments, restKind, spreadAdapts);
        }
        if (callee instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            List<Type.Parameter> params = checker.constructorParameters(cv.node());
            return params != null ? CallSolver.computeArgumentBinding(params, arguments, restKind, spreadAdapts) : null;
        }
        if (callee instanceof Type.Union union) {
            LoweringTargets.ArgumentBinding agreed = null;
            for (Type member : union.members()) {
                LoweringTargets.ArgumentBinding b = deriveArgumentBinding(member, arguments, call, spreadAdapts);
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

    private boolean deriveArrayCreation(List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext call) {
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
        Set<String> babbleConformIds = null;
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
                Set<String> conformIds = new LinkedHashSet<>();
                conformIds.add(inst.node().id);
                Set<Class<?>> ignoredJava = new LinkedHashSet<>();
                Set<TypeNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                visited.add(inst.node());
                collectAncestorInterfaces(inst.node(), conformIds, ignoredJava, visited);
                babbleConformIds = Collections.unmodifiableSet(conformIds);
                nestedDims = dims;
            } else {
                componentClass = JavaTypeBridge.checkedArrayClass(resultArr).getComponentType();
            }
        }

        pass.analysis.lowering().setArrayCreationTarget(call, new LoweringTargets.ArrayCreationTarget(
                kind, elemKind, componentClass, babbleTypeId, babbleDisplayName, babbleConformIds, nestedDims));
        return true;
    }

    private boolean deriveArrayOp(List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext call) {
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
        LoweringTargets.ArrayOpKind kind = switch (name) {
            case "size" -> arguments.isEmpty() ? LoweringTargets.ArrayOpKind.SIZE : null;
            case "get" -> arguments.size() == 1 ? LoweringTargets.ArrayOpKind.GET : null;
            case "set" -> arguments.size() == 2 ? LoweringTargets.ArrayOpKind.SET : null;
            case "equals" -> arguments.size() == 1 ? LoweringTargets.ArrayOpKind.EQUALS : null;
            case "hashCode" -> arguments.isEmpty() ? LoweringTargets.ArrayOpKind.HASH_CODE : null;
            case "toString" -> arguments.isEmpty() ? LoweringTargets.ArrayOpKind.TO_STRING : null;
            default -> null;
        };
        if (kind == null) {
            return false;
        }
        Class<?> arrCls = JavaTypeBridge.checkedArrayClass(arr);
        pass.analysis.lowering().setArrayOpTarget(call, new LoweringTargets.ArrayOpTarget(
                kind, PrimitiveKind.of(arr.element()), arrCls, arrCls.getComponentType()));
        return true;
    }

    /** Whether the arguments are all positional and statically applicable to the Java parameters, for a direct call. */
    private boolean staticallyApplicable(Class<?>[] paramTypes, boolean varArgs,
                                         List<BabbleParser.CallParameterContext> arguments) {
        if (!varArgs) {
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
        int fixed = paramTypes.length - 1;
        if (fixed < 0 || arguments.size() < fixed || !paramTypes[fixed].isArray()) {
            return false;
        }
        Class<?> comp = paramTypes[fixed].getComponentType();
        if (!JavaTypeBridge.isBytecodeAccessible(comp)) {
            return false;
        }
        for (int i = 0; i < fixed; i++) {
            Type argType = types.get(arguments.get(i).expression());
            if (ScopeAnalysis.callParamName(arguments.get(i)) != null
                    || !JavaTypeBridge.guaranteedApplicable(paramTypes[i], argType)
                    || !JavaTypeBridge.supportedStaticJavaArg(paramTypes[i], argType)) {
                return false;
            }
        }
        for (int i = fixed; i < arguments.size(); i++) {
            Type argType = types.get(arguments.get(i).expression());
            if (ScopeAnalysis.callParamName(arguments.get(i)) != null
                    || !JavaTypeBridge.guaranteedApplicable(comp, argType)
                    || !JavaTypeBridge.supportedStaticJavaArg(comp, argType)) {
                return false;
            }
        }
        return true;
    }

    private void deriveJavaConstructor(Constructor<?> ctor, boolean varArgs,
                                       List<BabbleParser.CallParameterContext> arguments,
                                       BabbleParser.CallContext call) {
        Class<?> java = ctor.getDeclaringClass();
        if (Modifier.isPublic(ctor.getModifiers()) && Modifier.isPublic(java.getModifiers())
                && JavaTypes.isAccessible(java) && staticallyApplicable(ctor.getParameterTypes(), varArgs, arguments)) {
            pass.analysis.lowering().setJavaConstructorTarget(call, new LoweringTargets.JavaConstructorTarget(ctor, varArgs));
        }
    }

    private void deriveJavaMethodCall(Method best, boolean varArgs,
                                      List<BabbleParser.CallParameterContext> arguments,
                                      BabbleParser.CallContext call) {
        if (!(call.expression() instanceof BabbleParser.SelectorContext sel)
                || sel.expression() == null
                || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        Type receiverType = types.get(sel.expression());
        while (receiverType instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            receiverType = tv.bound();
        }
        receiverType = TypeAlgebra.unwrapFrozen(receiverType);
        boolean isJavaLike = receiverType instanceof Type.JavaClassValue
                || receiverType instanceof Type.JavaInstance
                || receiverType instanceof Type.Array
                || receiverType == Type.STRING;
        if (!isJavaLike && receiverType instanceof Type.Intersection inter) {
            isJavaLike = inter.members().stream().anyMatch(m -> m instanceof Type.JavaInstance
                    || m instanceof Type.Instance inst && TypeAlgebra.implementsJava(inst.node(), best.getDeclaringClass()));
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
        pass.analysis.lowering().setJavaCallTarget(call, new LoweringTargets.JavaCallTarget(accessible, isStatic, unwrapReceiver, varArgs));
    }

    /** A call of a known Babble function : its literal, for a direct call, and its primitive signature ({@code RM001}, {@code BIP021-D3/D4}). */
    private void deriveBabbleCall(Type.Function function, List<BabbleParser.CallParameterContext> arguments,
                                  LoweringTargets.ArgumentBinding binding, BabbleParser.CallContext ctx) {
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
            PrimitiveKind kind = p.rest()
                    ? PrimitiveKind.NONE
                    : PrimitiveKind.of(p.type());
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
            BabbleParser.FunctionLiteralContext lit = checker.functionLiterals().get(function);
            if (lit != null && checker.inFile(lit) && checker.scopeOf(lit).memberOf == null) {
                BabbleParser.ExpressionContext calleeExpr = ctx.expression();
                while (calleeExpr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
                    calleeExpr = b.block().expression(0);
                }
                if (calleeExpr == lit) {
                    pass.analysis.calls().setBabbleCallFunction(ctx, lit);
                } else if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                    Object resolved = TypeChecker.resolveName(sel, sel.ID().getText(), checker.occurrenceScope(sel));
                    if (resolved instanceof BabbleParser.DefExpressionContext def
                            && checker.inFile(def) && !checker.isReassigned(def) && TypeChecker.unwrapFunctionLiteral(def.value) == lit) {
                        pass.analysis.calls().setBabbleCallFunction(ctx, lit);
                    }
                }
            }
            boolean canUseTypedIndy = hasPrimitive
                    && !binding.hasRest()
                    && optionalCount <= 32
                    && (!binding.hasOmitted() || paramCount <= 32);
            if (canUseTypedIndy) {
                pass.analysis.calls().setBabbleCallSignature(ctx, new LoweringTargets.FunctionSignature(paramKinds, returnKind, optionalParamBits));
            }
        }
    }

    /** A call of a member of a Babble interface : its {@code INVOKEINTERFACE} target ({@code RM003}, {@code BIP021-D3/D4}). */
    private void deriveInterfaceCall(List<BabbleParser.CallParameterContext> arguments,
                                     LoweringTargets.ArgumentBinding binding, BabbleParser.CallContext ctx) {
        if (binding == null || !checker.inFile(ctx) || !(ctx.expression() instanceof BabbleParser.SelectorContext sel)) {
            return;
        }
        if (sel.safe != null || sel.expression() == null || sel.expression() instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        String memberName = ScopeAnalysis.selectorName(sel);
        FoundInterfaceMember found = findDeclaringInterfaceOnType(types.get(sel.expression()), memberName);
        if (found == null) {
            return;
        }
        Type mt = checker.memberType(found.iface(), found.member());
        if (!(mt instanceof Type.Function uninstFn)) {
            return;
        }
        if (binding.paramCount() != uninstFn.parameters().size()) {
            return;
        }
        LoweringTargets.FunctionSignature sig = uninstantiatedSignature(uninstFn);
        if (sig == null) {
            return;
        }
        pass.analysis.lowering().setInterfaceCallTarget(ctx,
                new LoweringTargets.InterfaceCallTarget(found.iface().id, found.member().name, sig, true));
    }

    private record FoundInterfaceWithType(FoundInterfaceMember found, Type substitutedType) {
    }

    private FoundInterfaceMember findDeclaringInterfaceOnType(Type recv, String memberName) {
        FoundInterfaceWithType best = findBestInterfaceMember(recv, memberName);
        return best != null ? best.found() : null;
    }

    private FoundInterfaceWithType findBestInterfaceMember(Type recv, String memberName) {
        while (recv instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            recv = tv.bound();
        }
        recv = TypeAlgebra.unwrapFrozen(recv);
        if (recv instanceof Type.Instance recvInst && recvInst.node().isInterface()) {
            FoundInterfaceMember found = findDeclaringInterfaceMember(recvInst.node(), memberName);
            if (found == null) {
                return null;
            }
            Type projected = checker.algebra().projectToBabble(recvInst, found.iface());
            Map<Type.TypeVar, Type> env = projected instanceof Type.Instance p
                    ? checker.algebra().instanceEnv(p) : Map.of();
            Type subType = checker.algebra().substitute(checker.memberType(found.iface(), found.member()), env);
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
                        && checker.algebra().assignable(cand.substitutedType(), best.substitutedType())
                        && !checker.algebra().assignable(best.substitutedType(), cand.substitutedType())) {
                    best = cand;
                }
            }
            return best;
        }
        return null;
    }

    private void publishForLoopTarget(BabbleParser.ForExpressionContext forExpr,
                                      Map<ParserRuleContext, Type> declarationTypes,
                                      Map<ParserRuleContext, Integer> widened) {
        TypeChecker.ForIterableInfo info = checker.forIterableInfo(forExpr);
        if (info == null) {
            return;
        }
        Type loopVarType = declarationTypes.get(forExpr);
        PrimitiveKind elemKind = loopVarType != null
                ? PrimitiveKind.of(loopVarType) : PrimitiveKind.NONE;
        int elemWidenRank = widened.getOrDefault(forExpr, 0);
        String elemCheck = null;
        if (info.kind() == LoweringTargets.ForKind.JAVA_ITERABLE
                || info.kind() == LoweringTargets.ForKind.JAVA_ITERATOR
                || info.kind() == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE
                || info.kind() == LoweringTargets.ForKind.DYNAMIC_UNION
                || (info.kind() == LoweringTargets.ForKind.ARRAY && info.arrayClass() != null
                        && !info.arrayClass().getComponentType().isPrimitive())) {
            Type rawElem = info.elemType();
            if (rawElem != null && rawElem != Type.NULLABLE_ANY && rawElem != Type.ERROR) {
                elemCheck = descriptor(rawElem);
            }
        }
        LoweringTargets.InterfaceCallTarget iterIface = deriveZeroArgInterfaceTarget(info.babbleIterable(), "iterator");
        LoweringTargets.InterfaceCallTarget hasNextIface = deriveZeroArgInterfaceTarget(info.babbleIterator(), "hasNext");
        LoweringTargets.InterfaceCallTarget nextIface = deriveZeroArgInterfaceTarget(info.babbleIterator(), "next");
        LoweringTargets.FunctionSignature hasNextSig = info.hasNextFn() != null
                ? new LoweringTargets.FunctionSignature(new PrimitiveKind[0], PrimitiveKind.BOOL) : null;
        LoweringTargets.FunctionSignature nextSig = info.nextFn() != null
                ? new LoweringTargets.FunctionSignature(new PrimitiveKind[0],
                        PrimitiveKind.of(info.nextFn().result())) : null;
        pass.analysis.lowering().setForLoopTarget(forExpr, new LoweringTargets.ForLoopTarget(
                info.kind(), info.arrayClass(), elemKind, elemCheck, elemWidenRank,
                iterIface, hasNextSig, hasNextIface, nextSig, nextIface));
    }

    private LoweringTargets.InterfaceCallTarget deriveZeroArgInterfaceTarget(Type.Instance inst, String memberName) {
        if (inst == null || !inst.node().isInterface()) {
            return null;
        }
        FoundInterfaceMember found = findDeclaringInterfaceMember(inst.node(), memberName);
        if (found == null) {
            return null;
        }
        Type mt = checker.memberType(found.iface(), found.member());
        if (!(mt instanceof Type.Function uninstFn) || !uninstFn.parameters().isEmpty()) {
            return null;
        }
        LoweringTargets.FunctionSignature sig = uninstantiatedSignature(uninstFn);
        if (sig == null) {
            return null;
        }
        return new LoweringTargets.InterfaceCallTarget(found.iface().id, found.member().name, sig, true);
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
        if (tree instanceof ParserRuleContext parameter && ScopeAnalysis.isParamDecl(parameter)) {
            if (ScopeAnalysis.isParamRest(parameter)) {
                Type restType = declarationTypes.get(parameter);
                if (restType != null) {
                    pass.analysis.lowering().setCheckedType(parameter, descriptor(restType));
                }
                return;
            }
            BabbleParser.TypeContext paramType = ScopeAnalysis.paramType(parameter);
            Type type = paramType != null
                    ? checker.annotator().annotation(paramType, checker.scopeOf(parameter))
                    : declarationTypes.get(parameter);
            int targetRank = type != null ? TypeAlgebra.singleNumericRank(type) : -1;
            if (targetRank > 0) {
                widened.merge(parameter, targetRank, Math::max);
            }
            if (paramType != null || targetRank > 0) {
                String descriptor = descriptor(type);
                pass.analysis.lowering().setCheckedType(parameter, descriptor);
                if (parameter.getParent().getParent() instanceof BabbleParser.ClassExpressionContext cls
                        && (ScopeAnalysis.paramIsMember(parameter) || pass.typeNodes.get(cls).kind == ClassShape.Kind.VALUE_CLASS)) {
                    putType(pass.typeNodes.get(cls).memberTypes, ScopeAnalysis.paramId(parameter).getText(), descriptor);
                }
            }
            if (type != null && pass.analysis.lowering().javaSamTarget(parameter) == null) {
                LoweringTargets.JavaSamTarget samTarget = uniqueSamTargetForParam(type);
                if (samTarget != null) {
                    pass.analysis.lowering().setJavaSamTarget(parameter, samTarget);
                }
            }
        } else if (tree instanceof BabbleParser.FunctionLiteralContext function && function.type() != null) {
            pass.analysis.lowering().setCheckedType(function, descriptor(checker.annotator().annotation(function.type(), checker.scopeOf(function))));
        } else if (tree instanceof BabbleParser.DefExpressionContext def) {
            LoweringTargets.DefKind kind = pass.analysis.lowering().defTarget(def).kind();
            if (kind == LoweringTargets.DefKind.DEFAULT || kind == LoweringTargets.DefKind.REQUIRED) {
                return;
            }
            String descriptor = null;
            if (def.type() != null) {
                descriptor = descriptor(checker.annotator().annotation(def.type(), checker.scopeOf(def)));
            } else {
                Type inferred = (kind == LoweringTargets.DefKind.MEMBER || kind == LoweringTargets.DefKind.STATIC)
                        ? inferredMembers.get(def) : inferredDefs.get(def);
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
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) == null
                && ScopeAnalysis.assignName(assign) != null
                && checker.resolveName(assign, ScopeAnalysis.assignName(assign), checker.occurrenceScopeOf(assign)) instanceof ParserRuleContext declaration) {
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
        if (JavaTypeBridge.resolveJavaSam(type) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid.toTarget();
        }
        if (Type.withoutNull(type) instanceof Type.Union u) {
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function || mb instanceof Type.Overloaded || mb == Type.ANY) {
                    return null;
                }
                if (JavaTypeBridge.resolveJavaSam(member) instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
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
        if (type instanceof Type.Intersection intersection) {
            StringBuilder payload = new StringBuilder();
            for (Type member : intersection.members()) {
                String sub = descriptor(member);
                if (sub == null) {
                    return null;
                }
                if (!payload.isEmpty()) {
                    payload.append(',');
                }
                payload.append(org.babblelang.runtime.Types.encodeComponent(sub));
            }
            return "inter|" + intersection + "|" + payload;
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

    static PrimitiveKind primitiveKind(Type type) {
        return PrimitiveKind.of(type);
    }

    private static void publishTypeHierarchies(BlockScopes pass, TypeChecker checker) {
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && node.isInterface()) {
                pass.analysis.hierarchy().setTypeHierarchy(node.id, buildTypeHierarchyInfo(node, checker));
            }
        }
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && !node.isInterface()) {
                pass.analysis.hierarchy().setTypeHierarchy(node.id, buildTypeHierarchyInfo(node, checker));
            }
        }
    }

    private static LoweringTargets.TypeHierarchyInfo buildTypeHierarchyInfo(TypeNode node, TypeChecker checker) {
        Set<LoweringTargets.ParentRef> seenDirect = new LinkedHashSet<>();
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode tn) {
                seenDirect.add(new LoweringTargets.ParentRef(tn.id, null));
            } else if (parent instanceof Class<?> jc) {
                seenDirect.add(new LoweringTargets.ParentRef(null, jc));
            }
        }
        List<LoweringTargets.ParentRef> directParents = List.copyOf(seenDirect);

        Set<String> allBabbleInterfaceIds = new LinkedHashSet<>();
        Set<Class<?>> allJavaInterfaces = new LinkedHashSet<>();
        Set<TypeNode> visitedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        visitedNodes.add(node);
        collectAncestorInterfaces(node, allBabbleInterfaceIds, allJavaInterfaces, visitedNodes);

        Map<String, LoweringTargets.MemberSpec> interfaceMembers = new LinkedHashMap<>();
        Map<String, Integer> javaBridgeOverloads = new LinkedHashMap<>();
        Map<String, Integer> babbleBridgeOverloads = new LinkedHashMap<>();
        if (node.isInterface()) {
            for (TypeNode.Member member : node.members.values()) {
                boolean hasDefault = member.hasFunction;
                boolean required = !hasDefault;
                Type mt = checker.memberType(node, member);
                interfaceMembers.put(member.name, buildMemberSpec(member.name, hasDefault, required, mt));
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
            List<Type.TypeVar> vars = checker.annotator().typeVarsOf(node);
            Type.Instance selfInst = vars.isEmpty()
                    ? new Type.Instance(node)
                    : new Type.Instance(node, List.copyOf(vars));
            for (OwnerMember om : collected.values()) {
                TypeNode.Member member = om.member();
                TypeChecks.Provider provider = allInterfaceDefaults.get(member.name);
                boolean hasDefault = member.hasFunction || (provider != null && provider.owner() instanceof TypeNode);
                boolean required = !hasDefault;
                Type mt = checker.memberType(om.owner(), member);
                interfaceMembers.put(member.name, buildMemberSpec(member.name, hasDefault, required, mt));

                // Map each ancestor interface member alternative to this class's overloaded method alternative.
                TypeNode.Member ownImpl = node.members.get(member.name);
                if (ownImpl != null && !ownImpl.isPrivate() && checker.memberType(node, ownImpl) instanceof Type.Overloaded implOv) {
                    Type projected = checker.algebra().projectToBabble(selfInst, om.owner());
                    Map<Type.TypeVar, Type> ifaceEnv = projected instanceof Type.Instance proj
                            ? checker.algebra().instanceEnv(proj) : Map.of();
                    Type reqType = checker.algebra().substitute(mt, ifaceEnv);
                    if (reqType instanceof Type.Function reqFn) {
                        int chosen = OverloadSolver.selectAlternativeForFunction(checker.algebra(), implOv, reqFn, false);
                        if (chosen >= 0) {
                            babbleBridgeOverloads.put(member.name, chosen);
                        }
                    } else if (reqType instanceof Type.Overloaded reqOv) {
                        List<Type.Function> reqAlts = reqOv.alternatives();
                        for (int k = 0; k < reqAlts.size(); k++) {
                            int chosen = OverloadSolver.selectAlternativeForFunction(checker.algebra(), implOv, reqAlts.get(k), false);
                            if (chosen >= 0) {
                                babbleBridgeOverloads.put(member.name + "$" + k, chosen);
                            }
                        }
                    }
                }
            }
            for (Class<?> jc : allJavaInterfaces) {
                Type projected = checker.algebra().projectToJava(selfInst, jc);
                Map<java.lang.reflect.TypeVariable<?>, Type> javaEnv = projected instanceof Type.JavaInstance ji
                        ? JavaTypeBridge.javaTypeEnv(jc, ji.typeArguments()) : Map.of();
                for (Method jm : jc.getMethods()) {
                    if (Modifier.isStatic(jm.getModifiers()) || JavaTypes.isObjectMethod(jm)) {
                        continue;
                    }
                    String key = jm.getName() + "#" + org.objectweb.asm.Type.getMethodDescriptor(jm);
                    if (javaBridgeOverloads.containsKey(key)) {
                        continue;
                    }
                    Type implType = null;
                    TypeNode.Member ownImpl = node.members.get(jm.getName());
                    if (ownImpl != null && !ownImpl.isPrivate()) {
                        implType = checker.memberType(node, ownImpl);
                    } else {
                        TypeChecks.Provider provider = allInterfaceDefaults.get(jm.getName());
                        if (provider != null && provider.owner() instanceof TypeNode owner) {
                            TypeNode.Member defMember = owner.members.get(jm.getName());
                            if (defMember != null) {
                                Type projectedOwner = checker.algebra().projectToBabble(selfInst, owner);
                                Map<Type.TypeVar, Type> ownerEnv = projectedOwner instanceof Type.Instance proj
                                        ? checker.algebra().instanceEnv(proj) : Map.of();
                                implType = checker.algebra().substitute(checker.memberType(owner, defMember), ownerEnv);
                            }
                        }
                    }
                    if (!(implType instanceof Type.Overloaded ov)) {
                        continue;
                    }
                    int paramCount = jm.getParameterCount();
                    List<Type> javaParamTypes = new ArrayList<>(paramCount);
                    List<Type> fallbackParamTypes = new ArrayList<>(paramCount);
                    boolean hasFallbackDiff = false;
                    for (int i = 0; i < paramCount; i++) {
                        Type pt = JavaTypeBridge.javaExecutableParamType(jm, i, javaEnv);
                        javaParamTypes.add(pt);
                        if (JavaNullability.parameterNullness(jm, i) == JavaNullability.Nullness.UNSPECIFIED && Type.containsNull(pt)) {
                            fallbackParamTypes.add(Type.withoutNull(pt));
                            hasFallbackDiff = true;
                        } else {
                            fallbackParamTypes.add(pt);
                        }
                    }
                    int chosen = OverloadSolver.selectAlternativeForJavaMethod(
                            checker.algebra(), ov, jm, javaParamTypes, hasFallbackDiff ? fallbackParamTypes : null);
                    if (chosen >= 0) {
                        javaBridgeOverloads.put(key, chosen);
                    }
                }
            }
        }
        return new LoweringTargets.TypeHierarchyInfo(
                node.id,
                node.kind,
                node.name,
                directParents,
                Collections.unmodifiableSet(allBabbleInterfaceIds),
                Collections.unmodifiableSet(allJavaInterfaces),
                Collections.unmodifiableMap(interfaceMembers),
                Collections.unmodifiableMap(javaBridgeOverloads),
                Collections.unmodifiableMap(babbleBridgeOverloads));
    }

    private static LoweringTargets.MemberSpec buildMemberSpec(String name, boolean hasDefault, boolean required, Type mt) {
        if (mt instanceof Type.Function fn) {
            return new LoweringTargets.MemberSpec(name, hasDefault, required, true, uninstantiatedSignature(fn));
        }
        if (mt instanceof Type.Overloaded ov) {
            List<LoweringTargets.FunctionSignature> sigs = new ArrayList<>(ov.alternatives().size());
            List<String> keys = new ArrayList<>(ov.alternatives().size());
            for (Type.Function alt : ov.alternatives()) {
                LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
                if (sig == null) {
                    return new LoweringTargets.MemberSpec(name, hasDefault, required, false, null);
                }
                sigs.add(sig);
                keys.add(OverloadSolver.signatureKey(alt));
            }
            return new LoweringTargets.MemberSpec(name, hasDefault, required, false, null,
                    List.copyOf(sigs), List.copyOf(keys));
        }
        return new LoweringTargets.MemberSpec(name, hasDefault, required, false, null);
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

    static LoweringTargets.FunctionSignature uninstantiatedSignature(Type.Function fn) {
        if (fn == null) {
            return null;
        }
        List<Type.Parameter> params = fn.parameters();
        if (params.size() > 200) {
            return null;
        }
        PrimitiveKind[] paramKinds = new PrimitiveKind[params.size()];
        int slots = 2;
        int optionalParamBits = 0;
        for (int i = 0; i < params.size(); i++) {
            Type.Parameter p = params.get(i);
            Type pt = p.type();
            PrimitiveKind pk = (p.rest() || pt instanceof Type.TypeVar)
                    ? PrimitiveKind.NONE
                    : PrimitiveKind.of(pt);
            paramKinds[i] = pk;
            slots += pk.slots();
            if (p.optional()) {
                if (i >= 32) {
                    return null;
                }
                optionalParamBits |= (1 << i);
            }
        }
        if (optionalParamBits != 0) {
            slots += 1;
        }
        if (slots > 200) {
            return null;
        }
        Type rt = fn.result();
        PrimitiveKind rk = rt instanceof Type.TypeVar
                ? PrimitiveKind.NONE
                : PrimitiveKind.of(rt);
        return new LoweringTargets.FunctionSignature(paramKinds, rk, optionalParamBits, fn.restIndex());
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

    private LoweringTargets.CatchTarget buildCatchTarget(BabbleParser.CatchClauseContext cc) {
        if (cc.type() == null) {
            return new LoweringTargets.CatchTarget(
                    null,
                    List.of("java/lang/Exception"),
                    true,
                    true,
                    false,
                    false,
                    List.of());
        }
        Type target = checker.catchTargetType(cc);
        if (target == null || target == Type.ERROR) {
            return new LoweringTargets.CatchTarget(null, List.of(), false, false, false, false, List.of());
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
                if (inst.node().ctx == null || StaticRefs.isStaticTopLevelTypeExpr(inst.node().ctx)) {
                    babbleTargets.add(new LoweringTargets.IsTarget(inst.node().id, inst.node().kind));
                } else {
                    allBabbleStatic = false;
                }
            } else if (m instanceof Type.Intersection inter) {
                catchesBabble = true;
                allBabbleStatic = false;
                for (Type im : inter.members()) {
                    if (im instanceof Type.JavaInstance ji && Throwable.class.isAssignableFrom(ji.type())
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
