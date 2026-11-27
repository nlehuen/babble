package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.CallMetadata;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.analysis.SyntaxText;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.objectweb.asm.Type;

/** Call, overload dispatch, operator-method, and direct-method lowering for {@link HirBuilder}. */
final class HirCallLowerer {
    private final HirBuilder builder;

    HirCallLowerer(HirBuilder builder) {
        this.builder = builder;
    }

    HirExpr lowerCall(BabbleParser.CallContext ctx) {
        EffectSet callFx = builder.scopes.effects().callEffects(ctx);
        ExprMeta meta = builder.metaOf(ctx, callFx);
        List<BabbleParser.CallParameterContext> params = ctx.callParameters().callParameter();
        String[] names = SyntaxText.callNames(ctx);
        LoweringTargets.ArgumentBinding binding = builder.scopes.calls().argumentBinding(ctx);
        LoweringTargets.SpreadAdapt[] spreadAdapts = builder.scopes.calls().callSpreadAdapts(ctx);

        // 0. Self tail call : a jump back to the start of the function (BIP024)
        if (builder.scopes.calls().isSelfTailCall(ctx)) {
            if (builder.currentFunction == null) {
                throw new IllegalStateException(
                        "Self tail call outside of a function at line " + ctx.getStart().getLine());
            }
            // The analysis never marks a call with spread arguments (BIP024-D4).
            return new HirExpr.SelfTailCall(
                    builder.functionIds.get(builder.currentFunction),
                    lowerCallArgs(params, null),
                    binding,
                    meta);
        }

        // 0b. Unshadowed built-in move(x) / freeze(expr) ownership markers (BIP014-I4)
        String ownershipCall = builder.scopes.lowering().builtinOwnershipCall(ctx);
        if (ownershipCall != null && params.size() == 1) {
            HirExpr arg = builder.lowerExpr(params.get(0).expression());
            if ("move".equals(ownershipCall)) {
                HirVar movedVar = arg instanceof HirExpr.VarRead vr ? vr.var() : null;
                return new HirExpr.Move(movedVar, arg, meta.withUnique(true));
            }
            return new HirExpr.Freeze(arg, meta.withFrozen(true));
        }

        // 0c. Direct fast compiled record constructor call (BIP014-I4)
        LoweringTargets.RecordNewTarget recordNew = builder.scopes.lowering().recordNewTarget(ctx);
        if (recordNew != null && binding != null) {
            Integer localClsId = builder.localClassIdsByShapeId.get(recordNew.classTypeId());
            String jvmName =
                    localClsId != null
                            ? builder.localClassJvmNamesById.get(recordNew.classTypeId())
                            : builder.allClassJvmNames.get(recordNew.classTypeId());
            if (jvmName != null) {
                return new HirExpr.RecordNew(
                        localClsId != null ? localClsId : -1,
                        recordNew.classTypeId(),
                        jvmName,
                        builder.lowerExpr(ctx.expression()),
                        recordNew.paramKinds(),
                        lowerPositionalArgs(params, spreadAdapts),
                        binding,
                        meta.withUnique(true));
            }
        }

        // 1. Safe call recv?.m(args)
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.safe != null) {
            return new HirExpr.SafeCall(
                    builder.lowerExpr(selector.expression()),
                    selector.expression().getText(),
                    ScopeAnalysis.selectorName(selector),
                    selector.getStart().getLine(),
                    builder.scopes.lowering().checkedType(selector),
                    lowerCallArgs(params, spreadAdapts),
                    binding,
                    (binding != null || names == null)
                            ? builder.scopes.calls().babbleCallSignature(ctx)
                            : null,
                    SyntaxText.callSite(ctx, builder.sourceName),
                    meta);
        }

        // 2. Array creation or array protocol operation (BIP020-I3)
        LoweringTargets.ArrayCreationTarget arrCreate =
                builder.scopes.lowering().arrayCreationTarget(ctx);
        if (arrCreate != null) {
            String compInternalName;
            boolean compIsJavaIface;
            if (arrCreate.elemKind().isPrimitive()) {
                compInternalName = null;
                compIsJavaIface = false;
            } else if (arrCreate.babbleTypeId() != null) {
                String base = builder.resolveBabbleJvmName(arrCreate.babbleTypeId());
                compInternalName =
                        arrCreate.nestedDims() > 0
                                ? "[".repeat(arrCreate.nestedDims()) + "L" + base + ";"
                                : base;
                compIsJavaIface = false;
            } else {
                Class<?> comp =
                        arrCreate.componentClass() != null
                                ? arrCreate.componentClass()
                                : Object.class;
                compInternalName = Type.getInternalName(comp);
                compIsJavaIface = comp.isInterface();
            }
            boolean babbleIsIface =
                    arrCreate.babbleTypeId() != null
                            && builder.allInterfaceJvmNames.containsKey(arrCreate.babbleTypeId());
            HirArrayElementType elemType =
                    HirArrayElementType.from(
                            arrCreate.elemKind(),
                            arrCreate.babbleTypeId(),
                            babbleIsIface,
                            arrCreate.nestedDims(),
                            arrCreate.componentClass());
            return new HirExpr.ArrayCreate(
                    arrCreate.kind(),
                    arrCreate.elemKind(),
                    elemType,
                    compInternalName,
                    compIsJavaIface,
                    arrCreate.componentClass(),
                    lowerPositionalArgs(params, spreadAdapts),
                    meta);
        }
        LoweringTargets.ArrayOpTarget arrOp = builder.scopes.lowering().arrayOpTarget(ctx);
        if (arrOp != null) {
            BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
            return new HirExpr.ArrayOp(
                    arrOp.kind(),
                    builder.lowerExpr(selector.expression()),
                    selector.expression().getText(),
                    arrOp.elemKind(),
                    HirArrayElementType.from(
                            arrOp.elemKind(), null, false, 0, arrOp.componentClass()),
                    arrOp.arrayClass(),
                    arrOp.componentClass(),
                    lowerPositionalArgs(params, spreadAdapts),
                    meta);
        }

        // 3. Core collection constructor
        BabbleParser.ExpressionContext directCallee =
                ctx.expression() instanceof BabbleParser.TypeApplyContext ta
                        ? ta.expression()
                        : ctx.expression();
        if (directCallee instanceof BabbleParser.CoreCollectionContext cc) {
            return new HirExpr.CoreCollectionCall(
                    cc.coreCollectionKeyword().getText(),
                    lowerCallArgs(params, spreadAdapts),
                    meta);
        }

        // 3b. Super call super.m(args)
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.SuperExpressionContext superExpr) {
            LoweringTargets.SuperTargetInfo info =
                    (binding != null || names == null)
                            ? builder.scopes.hierarchy().superTargetInfo(superExpr)
                            : null;
            int ownerClsId = -1;
            if (info != null && !info.ownerIsInterface()) {
                ParserRuleContext ownerClsCtx =
                        builder.findEnclosingOwnerClassCtx(superExpr, info.ownerTypeId());
                if (ownerClsCtx != null && builder.compiledClassJvmNames.containsKey(ownerClsCtx)) {
                    ownerClsId = builder.classIds.get(ownerClsCtx);
                }
            }
            return new HirExpr.SuperCall(
                    builder.hirVar(builder.resolution.variable(selector)),
                    ownerClsId,
                    ScopeAnalysis.selectorName(selector),
                    info,
                    builder.declLowerer.lowerTypeRef(
                            builder.scopes.hierarchy().superTarget(superExpr), superExpr),
                    lowerCallArgs(params, spreadAdapts),
                    binding,
                    SyntaxText.callSite(ctx, builder.sourceName),
                    meta);
        }

        // 4. Direct self-recursion via recurse(...)
        if (ctx.expression() instanceof BabbleParser.RecurseContext recurseCtx
                && (binding != null || names == null)
                && builder.currentFunction != null) {
            Resolution.Variable v = builder.resolution.variable(recurseCtx);
            if (v != null && v.method() == builder.currentFunction) {
                int count = builder.scopes.calls().parameters(builder.currentFunction).length;
                boolean spec = hasSpec(builder.currentFunction);
                boolean spread = isSpread(builder.currentFunction);
                boolean arityOk =
                        binding != null
                                ? binding.paramCount() == count
                                : ((spec && params.size() == count)
                                        || (spread && params.size() <= count));
                if (arityOk && (spec || spread)) {
                    return new HirExpr.SelfRecurseCall(
                            builder.functionIds.get(builder.currentFunction),
                            lowerPositionalArgs(params, spreadAdapts),
                            binding,
                            meta);
                }
            }
        }

        // 4b. Overload set call (resolved directly or via union split, BIP021-I5/I6)
        CallMetadata.OverloadDispatchTarget ovDispatch =
                builder.scopes.calls().overloadDispatch(ctx);
        if (ovDispatch != null) {
            if (!ovDispatch.isSplit()) {
                // Overloaded Babble interface method call -> INVOKEINTERFACE bm$<member>$<k>
                // (BIP021-D13).
                LoweringTargets.InterfaceCallTarget ovIfaceCall =
                        (binding != null || names == null)
                                ? builder.scopes.lowering().interfaceCallTarget(ctx)
                                : null;
                if (ovIfaceCall != null) {
                    String ifaceJvm =
                            builder.allInterfaceJvmNames.get(ovIfaceCall.interfaceTypeId());
                    LoweringTargets.FunctionSignature ifaceSig = ovIfaceCall.signature();
                    if (ifaceJvm != null
                            && ifaceSig != null
                            && (binding != null
                                    ? binding.paramCount() == ifaceSig.parameterKinds().length
                                    : params.size() == ifaceSig.parameterKinds().length)) {
                        BabbleParser.SelectorContext selector =
                                (BabbleParser.SelectorContext) ctx.expression();
                        return new HirExpr.InterfaceCall(
                                builder.lowerExpr(selector.expression()),
                                selector.expression().getText(),
                                selector.getStart().getLine(),
                                ovIfaceCall.interfaceTypeId(),
                                ovIfaceCall.memberName(),
                                ifaceSig,
                                lowerPositionalArgs(params, spreadAdapts),
                                binding,
                                meta);
                    }
                }
                CallMetadata.OverloadCallTarget target = ovDispatch.direct();
                BabbleParser.FunctionLiteralContext directFn = target.directTarget();
                int directOwnerCls =
                        directFn != null
                                ? builder.methodOwnerClassIds.getOrDefault(directFn, -1)
                                : -1;
                if (directOwnerCls >= 0
                        && ctx.expression() instanceof BabbleParser.SelectorContext sel
                        && sel.expression() != null) {
                    String recvText =
                            sel.expression() instanceof BabbleParser.ThisExpressionContext
                                    ? null
                                    : sel.expression().getText();
                    return new HirExpr.DirectThisMethodCall(
                            builder.lowerExpr(sel.expression()),
                            recvText,
                            directOwnerCls,
                            ScopeAnalysis.selectorName(sel),
                            target.altIndex(),
                            builder.functionIds.get(directFn),
                            lowerCallArgs(params, spreadAdapts),
                            target.binding(),
                            SyntaxText.callSite(ctx, builder.sourceName),
                            meta);
                }
                HirExpr selectedCallee =
                        new HirExpr.OverloadSelect(
                                builder.lowerExpr(ctx.expression()),
                                target.altIndex(),
                                target.signatureKey(),
                                target.exactSet(),
                                ExprMeta.of(ctx.getStart().getLine(), PrimitiveKind.NONE));
                if (directFn != null
                        && !isMemberMethod(directFn)
                        && target.binding() != null
                        && target.binding().paramCount()
                                == builder.scopes.calls().parameters(directFn).length
                        && (hasSpec(directFn) || isSpread(directFn))) {
                    return new HirExpr.DirectBabbleCall(
                            selectedCallee,
                            builder.functionIds.get(directFn),
                            lowerPositionalArgs(params, spreadAdapts),
                            target.binding(),
                            meta);
                }
                return new HirExpr.DynamicCall(
                        selectedCallee,
                        lowerCallArgs(params, spreadAdapts),
                        target.binding(),
                        target.signature(),
                        SyntaxText.callSite(ctx, builder.sourceName),
                        meta);
            }
            List<HirExpr.OverloadBranch> hirBranches =
                    new ArrayList<>(ovDispatch.splitBranches().size());
            for (CallMetadata.OverloadSplitBranch b : ovDispatch.splitBranches()) {
                CallMetadata.OverloadCallTarget bt = b.target();
                int directFnId = -1;
                if (bt.directTarget() != null
                        && !isMemberMethod(bt.directTarget())
                        && bt.binding() != null
                        && bt.binding().paramCount()
                                == builder.scopes.calls().parameters(bt.directTarget()).length
                        && (hasSpec(bt.directTarget()) || isSpread(bt.directTarget()))) {
                    directFnId = builder.functionIds.get(bt.directTarget());
                }
                hirBranches.add(
                        new HirExpr.OverloadBranch(
                                b.checks(),
                                directFnId,
                                bt.altIndex(),
                                bt.signatureKey(),
                                bt.exactSet(),
                                bt.signature(),
                                bt.binding(),
                                b.resultKind()));
            }
            return new HirExpr.OverloadDispatch(
                    builder.lowerExpr(ctx.expression()),
                    ctx.expression().getText(),
                    SyntaxText.callSite(ctx, builder.sourceName),
                    lowerPositionalArgs(params, null),
                    hirBranches,
                    meta);
        }

        // 5. Direct free Babble function call
        BabbleParser.FunctionLiteralContext targetFn =
                builder.scopes.calls().babbleCallFunction(ctx);
        if (targetFn != null
                && binding != null
                && binding.paramCount() == builder.scopes.calls().parameters(targetFn).length) {
            if (hasSpec(targetFn) || isSpread(targetFn)) {
                return new HirExpr.DirectBabbleCall(
                        builder.lowerExpr(ctx.expression()),
                        builder.functionIds.get(targetFn),
                        lowerPositionalArgs(params, spreadAdapts),
                        binding,
                        meta);
            }
        }

        // 5b. Direct cross-module exported Babble function call
        LoweringTargets.ExportedFunctionTarget extFn =
                builder.scopes.lowering().externalFunctionTarget(ctx);
        if (extFn != null
                && binding != null
                && binding.paramCount() == extFn.signature().parameterKinds().length
                && (extFn.signature().hasSpec() || extFn.signature().isSpread())) {
            return new HirExpr.DirectBabbleCall(
                    builder.lowerExpr(ctx.expression()),
                    -1,
                    extFn,
                    lowerPositionalArgs(params, spreadAdapts),
                    binding,
                    meta);
        }

        // 6. Direct this.m(...) on compiled class
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.ThisExpressionContext thisExpr) {
            ParserRuleContext clsCtx = builder.findThisCompiledClassCtx(thisExpr);
            if (clsCtx != null) {
                ClassShape shape = builder.scopes.hierarchy().shape(clsCtx);
                String member = ScopeAnalysis.selectorName(selector);
                int index = shape.memberIndex(member);
                if (index >= 0 && shape.isMethod(index)) {
                    BabbleParser.FunctionLiteralContext methodLit = methodLiteralOf(clsCtx, member);
                    if (methodLit != null) {
                        return new HirExpr.DirectThisMethodCall(
                                builder.lowerExpr(thisExpr),
                                builder.classIds.get(clsCtx),
                                member,
                                builder.functionIds.get(methodLit),
                                lowerCallArgs(params, spreadAdapts),
                                binding,
                                SyntaxText.callSite(ctx, builder.sourceName),
                                meta);
                    }
                }
            }
        }

        // 6b. Direct non-this method call recv.m(args) on compiled Babble class/record (BIP014-I4)
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() != null
                && selector.safe == null
                && builder.scopes.lowering().directMethodTarget(ctx) != null) {
            HirExpr direct =
                    tryLowerDirectMethod(
                            ctx,
                            selector.expression(),
                            selector.expression().getText(),
                            lowerCallArgs(params, spreadAdapts),
                            binding,
                            SyntaxText.callSite(ctx, builder.sourceName),
                            meta);
            if (direct != null) {
                return direct;
            }
        }

        // 7. Static Babble JVM interface method call
        LoweringTargets.InterfaceCallTarget ifaceCall =
                (binding != null || names == null)
                        ? builder.scopes.lowering().interfaceCallTarget(ctx)
                        : null;
        if (ifaceCall != null) {
            String ifaceJvm = builder.allInterfaceJvmNames.get(ifaceCall.interfaceTypeId());
            LoweringTargets.FunctionSignature sig = ifaceCall.signature();
            if (ifaceJvm != null
                    && sig != null
                    && (binding != null
                            ? binding.paramCount() == sig.parameterKinds().length
                            : params.size() == sig.parameterKinds().length)) {
                BabbleParser.SelectorContext selector =
                        (BabbleParser.SelectorContext) ctx.expression();
                return new HirExpr.InterfaceCall(
                        builder.lowerExpr(selector.expression()),
                        selector.expression().getText(),
                        selector.getStart().getLine(),
                        ifaceCall.interfaceTypeId(),
                        ifaceCall.memberName(),
                        sig,
                        lowerPositionalArgs(params, spreadAdapts),
                        binding,
                        meta);
            }
        }

        // 8. Direct Java constructor call
        LoweringTargets.JavaConstructorTarget javaCtor =
                builder.scopes.lowering().javaConstructorTarget(ctx);
        if (javaCtor != null) {
            return new HirExpr.JavaConstructorCall(
                    builder.lowerExpr(ctx.expression()),
                    ctx.expression().getText(),
                    builder.isPureStaticJavaChain(ctx.expression()),
                    javaCtor,
                    lowerPositionalArgs(params, spreadAdapts),
                    meta);
        }

        // 9. Direct Java method call
        LoweringTargets.JavaCallTarget javaCall = builder.scopes.lowering().javaCallTarget(ctx);
        if (javaCall != null) {
            BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
            BabbleParser.ExpressionContext recvExpr = selector.expression();
            return new HirExpr.JavaMethodCall(
                    builder.lowerExpr(recvExpr),
                    recvExpr.getText(),
                    selector.getStart().getLine(),
                    javaCall.isStatic() && builder.isPureStaticJavaChain(recvExpr),
                    javaCall,
                    lowerPositionalArgs(params, spreadAdapts),
                    meta);
        }

        // 10. General dynamic call (including typedCall/typedCallOpt indy when babbleSig != null)
        HirExpr.DynamicCall dc =
                new HirExpr.DynamicCall(
                        builder.lowerExpr(ctx.expression()),
                        lowerCallArgs(params, spreadAdapts),
                        binding,
                        (binding != null || names == null)
                                ? builder.scopes.calls().babbleCallSignature(ctx)
                                : null,
                        SyntaxText.callSite(ctx, builder.sourceName),
                        meta);
        if (builder.scopes.isNonCyclicStaticImport(ctx)) {
            builder.nonCyclicImports.add(dc);
        }
        return dc;
    }

    private HirExpr wrapSpreadAdapt(
            HirExpr expr, LoweringTargets.SpreadAdapt[] adapts, int index, int line) {
        if (adapts != null
                && index < adapts.length
                && adapts[index] != null
                && adapts[index].kind() != LoweringTargets.SpreadKind.AS_IS) {
            return new HirExpr.SpreadAdapt(
                    expr, adapts[index], ExprMeta.of(line, PrimitiveKind.NONE));
        }
        return expr;
    }

    private List<HirCallArg> lowerCallArgs(
            List<BabbleParser.CallParameterContext> params, LoweringTargets.SpreadAdapt[] adapts) {
        if (params.isEmpty()) {
            return List.of();
        }
        List<HirCallArg> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            BabbleParser.CallParameterContext p = params.get(i);
            HirExpr arg =
                    wrapSpreadAdapt(
                            builder.lowerExpr(p.expression()), adapts, i, p.getStart().getLine());
            result.add(new HirCallArg(ScopeAnalysis.callParamName(p), arg));
        }
        return result;
    }

    private List<HirExpr> lowerPositionalArgs(
            List<BabbleParser.CallParameterContext> params, LoweringTargets.SpreadAdapt[] adapts) {
        if (params.isEmpty()) {
            return List.of();
        }
        List<HirExpr> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            BabbleParser.CallParameterContext p = params.get(i);
            result.add(
                    wrapSpreadAdapt(
                            builder.lowerExpr(p.expression()), adapts, i, p.getStart().getLine()));
        }
        return result;
    }

    HirExpr lowerOperatorOverload(
            ParserRuleContext ctx,
            BabbleParser.ExpressionContext recvExpr,
            String recvText,
            String method,
            BabbleParser.ExpressionContext argExpr,
            String argText,
            CallMetadata.OverloadDispatchTarget ovDispatch) {
        int ln = ctx.getStart().getLine();
        CallSite site = SyntaxText.operatorSite(ctx, recvText, method, argText, builder.sourceName);
        ExprMeta meta = builder.metaOf(ctx);
        if (!ovDispatch.isSplit()) {
            CallMetadata.OverloadCallTarget target = ovDispatch.direct();
            BabbleParser.FunctionLiteralContext directFn = target.directTarget();
            int directOwnerCls =
                    directFn != null ? builder.methodOwnerClassIds.getOrDefault(directFn, -1) : -1;
            List<HirCallArg> callArgs =
                    argExpr != null
                            ? List.of(new HirCallArg(null, builder.lowerExpr(argExpr)))
                            : List.of();
            if (directOwnerCls >= 0) {
                String rt =
                        recvExpr instanceof BabbleParser.ThisExpressionContext ? null : recvText;
                return new HirExpr.DirectThisMethodCall(
                        builder.lowerExpr(recvExpr),
                        rt,
                        directOwnerCls,
                        method,
                        target.altIndex(),
                        builder.functionIds.get(directFn),
                        callArgs,
                        null,
                        site,
                        meta);
            }
            if (builder.scopes.lowering().directMethodTarget(ctx) != null) {
                HirExpr directOp =
                        tryLowerDirectMethod(ctx, recvExpr, recvText, callArgs, null, site, meta);
                if (directOp != null) {
                    return directOp;
                }
            }
            HirExpr memberSelect =
                    new HirExpr.DynamicSelect(
                            builder.lowerExpr(recvExpr),
                            recvText,
                            method,
                            false,
                            ExprMeta.of(ln, PrimitiveKind.NONE));
            HirExpr selected =
                    new HirExpr.OverloadSelect(
                            memberSelect,
                            target.altIndex(),
                            target.signatureKey(),
                            target.exactSet(),
                            ExprMeta.of(ln, PrimitiveKind.NONE));
            return new HirExpr.DynamicCall(
                    selected, callArgs, null, target.signature(), site, meta);
        }
        List<HirExpr.OverloadBranch> hirBranches =
                new ArrayList<>(ovDispatch.splitBranches().size());
        for (CallMetadata.OverloadSplitBranch b : ovDispatch.splitBranches()) {
            CallMetadata.OverloadCallTarget bt = b.target();
            hirBranches.add(
                    new HirExpr.OverloadBranch(
                            b.checks(),
                            -1,
                            bt.altIndex(),
                            bt.signatureKey(),
                            bt.exactSet(),
                            bt.signature(),
                            null,
                            b.resultKind()));
        }
        HirExpr memberSelect =
                new HirExpr.DynamicSelect(
                        builder.lowerExpr(recvExpr),
                        recvText,
                        method,
                        false,
                        ExprMeta.of(ln, PrimitiveKind.NONE));
        List<HirExpr> posArgs = argExpr != null ? List.of(builder.lowerExpr(argExpr)) : List.of();
        return new HirExpr.OverloadDispatch(
                memberSelect, recvText + "." + method, site, posArgs, hirBranches, meta);
    }

    HirExpr tryLowerDirectMethod(
            ParserRuleContext site,
            BabbleParser.ExpressionContext recvExpr,
            String recvText,
            List<HirCallArg> callArgs,
            LoweringTargets.ArgumentBinding binding,
            CallSite callSite,
            ExprMeta meta) {
        LoweringTargets.DirectMethodTarget target =
                builder.scopes.lowering().directMethodTarget(site);
        if (target == null) {
            return null;
        }
        String rt = recvExpr instanceof BabbleParser.ThisExpressionContext ? null : recvText;
        BabbleParser.FunctionLiteralContext directFn =
                builder.scopes.lowering().directMethodFunction(site);
        Integer localClsId = builder.localClassIdsByShapeId.get(target.classTypeId());
        if (localClsId != null && directFn != null && builder.functionIds.containsKey(directFn)) {
            return new HirExpr.DirectThisMethodCall(
                    builder.lowerExpr(recvExpr),
                    rt,
                    localClsId,
                    null,
                    target.classTypeId(),
                    target.memberName(),
                    target.overloadAltIndex(),
                    builder.functionIds.get(directFn),
                    null,
                    callArgs,
                    binding,
                    callSite,
                    meta);
        }
        String extJvm = builder.allClassJvmNames.get(target.classTypeId());
        if (extJvm != null && target.signature() != null && target.signature().isSpread()) {
            return new HirExpr.DirectThisMethodCall(
                    builder.lowerExpr(recvExpr),
                    rt,
                    -1,
                    extJvm,
                    target.classTypeId(),
                    target.memberName(),
                    target.overloadAltIndex(),
                    -1,
                    target.signature(),
                    callArgs,
                    binding,
                    callSite,
                    meta);
        }
        return null;
    }

    // ---------------------------------------------------------------- call helpers

    private boolean isSpread(BabbleParser.FunctionLiteralContext literal) {
        return builder.scopes.calls().parameters(literal).length <= HirFunction.MAX_SPREAD;
    }

    private boolean hasSpec(BabbleParser.FunctionLiteralContext literal) {
        if (!isSpread(literal)) {
            return false;
        }
        List<BabbleParser.FnParamContext> fnParams = literal.fnParameters().fnParam();
        int opt = 0;
        for (BabbleParser.FnParamContext decl : fnParams) {
            if (decl.defaultValue != null) {
                opt++;
            }
        }
        if (opt > 32 || (opt > 0 && fnParams.size() > 32)) {
            return false;
        }
        boolean hasPrimitive = builder.scopes.lowering().functionReturnKind(literal).isPrimitive();
        int slots = 3 + (opt > 0 ? 1 : 0);
        for (BabbleParser.FnParamContext decl : fnParams) {
            PrimitiveKind kind = builder.scopes.lowering().declarationKind(decl);
            if (kind.isPrimitive()) {
                hasPrimitive = true;
            }
            slots += kind.slots();
        }
        return hasPrimitive && slots <= 250;
    }

    private BabbleParser.FunctionLiteralContext methodLiteralOf(
            ParserRuleContext clsCtx, String member) {
        BabbleParser.BlockContext body =
                clsCtx instanceof BabbleParser.ClassExpressionContext c
                        ? c.classBlock
                        : ((BabbleParser.ObjectExpressionContext) clsCtx).createBlock;
        for (BabbleParser.ExpressionContext expression : body.expression()) {
            if (expression instanceof BabbleParser.DefExpressionContext def
                    && def.name.getText().equals(member)
                    && def.value instanceof BabbleParser.FunctionLiteralContext literal) {
                return literal;
            }
        }
        throw new IllegalStateException("No method " + member);
    }

    private boolean isMemberMethod(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                        && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        if (cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
            LoweringTargets.DefTarget target = builder.scopes.lowering().defTarget(def);
            return target != null && target.kind() != LoweringTargets.DefKind.LEXICAL;
        }
        return false;
    }
}
