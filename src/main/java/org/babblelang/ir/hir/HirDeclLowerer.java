package org.babblelang.ir.hir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.ModuleInterface;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.objectweb.asm.Type;

/**
 * Pass 1 discovery, Pass 2 declaration lowering (functions, classes, objects, interfaces, static
 * blocks, type references), and module export/hierarchy collection for {@link HirBuilder}.
 */
final class HirDeclLowerer {
    private final HirBuilder builder;

    HirDeclLowerer(HirBuilder builder) {
        this.builder = builder;
    }

    // ---------------------------------------------------------------- Pass 1: discovery

    void discoverAll(BabbleParser.FileContext file) {
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
                int id = builder.nextOwnerId++;
                builder.interfaceIds.put(ctx, id);
                builder.orderedInterfaces.add(ctx);
                ClassShape shape = builder.scopes.hierarchy().shape(ctx);
                if (shape != null) {
                    String jvmName =
                            builder.className
                                    + "$I"
                                    + (builder.interfaceCount++)
                                    + "$"
                                    + shape.name();
                    builder.ownInterfaceJvmNames.put(shape.id(), jvmName);
                    builder.allInterfaceJvmNames.put(shape.id(), jvmName);
                }
                return super.visitInterfaceExpression(ctx);
            }

            @Override
            public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
                int id = builder.nextOwnerId++;
                builder.classIds.put(ctx, id);
                builder.orderedClasses.add(ctx);
                ClassShape shape = builder.scopes.hierarchy().shape(ctx);
                if (shape != null && isStaticTopLevelTypeExpr(ctx)) {
                    String jvmName =
                            builder.className + "$C" + (builder.classCount++) + "$" + shape.name();
                    builder.ownClassJvmNames.put(shape.id(), jvmName);
                    builder.allClassJvmNames.put(shape.id(), jvmName);
                    builder.preassignedClassNames.put(ctx, jvmName);
                }
                return super.visitClassExpression(ctx);
            }

            @Override
            public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
                int id = builder.nextOwnerId++;
                builder.classIds.put(ctx, id);
                builder.orderedClasses.add(ctx);
                return super.visitObjectExpression(ctx);
            }

            @Override
            public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
                int id = builder.nextOwnerId++;
                builder.functionIds.put(ctx, id);
                builder.orderedFunctions.add(ctx);
                return super.visitFunctionLiteral(ctx);
            }

            @Override
            public Void visitInitExpression(BabbleParser.InitExpressionContext ctx) {
                if (ctx.fnParameters() != null) {
                    builder.initIds.put(ctx, builder.nextOwnerId++);
                }
                return super.visitInitExpression(ctx);
            }
        }.visit(file);

        // Compute compiled JVM names and implemented interfaces for every class/object.
        for (ParserRuleContext clsCtx : builder.orderedClasses) {
            ClassShape shape = builder.scopes.hierarchy().shape(clsCtx);
            List<Class<?>> javaIfaces = implementableJavaInterfaces(shape);
            List<String> implemented = implementedInterfaces(shape, javaIfaces);
            builder.classJavaInterfaces.put(clsCtx, javaIfaces);
            builder.classImplementedJvm.put(clsCtx, implemented);
            String preassigned = builder.preassignedClassNames.get(clsCtx);
            String name =
                    preassigned != null
                            ? preassigned
                            : builder.className
                                    + "$C"
                                    + (builder.classCount++)
                                    + "$"
                                    + shape.name();
            builder.compiledClassJvmNames.put(clsCtx, name);
            if (shape != null) {
                builder.localClassJvmNamesById.put(shape.id(), name);
                builder.localClassIdsByShapeId.put(shape.id(), builder.classIds.get(clsCtx));
            }
        }

        // Assign JVM method names & owning compiled class for every function literal.
        Map<String, Integer> overloadMemberCounters = new LinkedHashMap<>();
        for (BabbleParser.FunctionLiteralContext fnCtx : builder.orderedFunctions) {
            int ownerCls = findCompiledMethodOwnerClassId(fnCtx);
            builder.methodOwnerClassIds.put(fnCtx, ownerCls);
            if (ownerCls >= 0) {
                BabbleParser.DefExpressionContext def = owningMemberDef(fnCtx);
                String member = def.name.getText();
                builder.methodMemberNames.put(fnCtx, member);
                if (def.value instanceof BabbleParser.OverloadExpressionContext) {
                    int idx =
                            overloadMemberCounters.merge(ownerCls + ":" + member, 1, Integer::sum)
                                    - 1;
                    builder.functionJvmNames.put(fnCtx, "m$" + member + "$" + idx);
                } else {
                    builder.functionJvmNames.put(fnCtx, "m$" + member);
                }
            } else {
                String name = ScopeAnalysis.functionName(fnCtx);
                builder.functionJvmNames.put(
                        fnCtx,
                        "fn$" + (builder.functionCount++) + (name != null ? "$" + name : ""));
            }
        }
    }

    private static BabbleParser.DefExpressionContext owningMemberDef(
            BabbleParser.FunctionLiteralContext literal) {
        ParserRuleContext cur = literal;
        while (cur.getParent() instanceof BabbleParser.OverloadExpressionContext ov) {
            cur = ov;
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur
                ? def
                : null;
    }

    private void collectOverloadFunctionIds(
            BabbleParser.OverloadExpressionContext ov, List<Integer> out) {
        for (BabbleParser.ExpressionContext op : ov.expression()) {
            if (op instanceof BabbleParser.FunctionLiteralContext lit) {
                out.add(builder.functionIds.get(lit));
            } else if (op instanceof BabbleParser.OverloadExpressionContext nested) {
                collectOverloadFunctionIds(nested, out);
            } else {
                out.add(-1);
            }
        }
    }

    private boolean isStaticTopLevelTypeExpr(ParserRuleContext typeExpr) {
        ParserRuleContext cur = typeExpr;
        while (cur != null) {
            ParserRuleContext parent = cur.getParent();
            if (parent instanceof BabbleParser.FileContext) {
                return true;
            }
            if (parent instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
                cur = def;
            } else if (parent instanceof BabbleParser.BlockContext block
                    && block.getParent() instanceof BabbleParser.BlockExpressionContext blockExpr
                    && blockExpr.block() == block
                    && block.expression().size() == 1
                    && !builder.scopes.opensScope(block)) {
                cur = blockExpr;
            } else {
                return false;
            }
        }
        return false;
    }

    private int findCompiledMethodOwnerClassId(BabbleParser.FunctionLiteralContext literal) {
        BabbleParser.DefExpressionContext def = owningMemberDef(literal);
        if (def == null) {
            return -1;
        }
        LoweringTargets.DefTarget dt = builder.scopes.lowering().defTarget(def);
        if (dt.kind() != LoweringTargets.DefKind.MEMBER) {
            return -1;
        }
        for (ParserRuleContext p = def.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext
                    || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassShape shape = builder.scopes.hierarchy().shape(p);
                if (shape != null && !shape.isMethod(dt.index())) {
                    return -1;
                }
                if (builder.compiledClassJvmNames.containsKey(p)) {
                    return builder.classIds.get(p);
                }
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Pass 2: declarations

    HirFunction lowerFunction(BabbleParser.FunctionLiteralContext literal) {
        BabbleParser.FunctionLiteralContext savedFn = builder.currentFunction;
        builder.currentFunction = literal;
        try {
            int id = builder.functionIds.get(literal);
            int ownerCls = builder.methodOwnerClassIds.get(literal);
            String memberName = builder.methodMemberNames.get(literal);
            String[] paramNames = builder.scopes.calls().parameters(literal);
            List<BabbleParser.FnParamContext> paramCtxs = literal.fnParameters().fnParam();
            List<HirParam> params = new ArrayList<>(paramNames.length);
            for (int i = 0; i < paramNames.length; i++) {
                BabbleParser.FnParamContext p = paramCtxs.get(i);
                BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(p);
                params.add(
                        new HirParam(
                                paramNames[i],
                                builder.hirVar(builder.resolution.variable(p)),
                                builder.scopes.lowering().declarationKind(p),
                                builder.scopes.lowering().numericWideningRank(p),
                                builder.scopes.lowering().checkedType(p),
                                -1,
                                defVal != null ? builder.lowerExpr(defVal) : null,
                                builder.scopes.lowering().javaSamTarget(p),
                                builder.scopes.lowering().paramOwnership(p),
                                ScopeAnalysis.isVar(p),
                                p.getStart().getLine()));
            }
            List<HirExpr> body = new ArrayList<>(literal.functionBlock.expression().size());
            for (BabbleParser.ExpressionContext e : literal.functionBlock.expression()) {
                body.add(builder.lowerExpr(e));
            }
            if (!body.isEmpty()) {
                int last = body.size() - 1;
                body.set(last, builder.wrapJavaSam(body.get(last), literal.functionBlock));
            }
            // Note: for a compiled class method, capturedVars are the enclosing class's captured
            // vars!
            List<HirVar> captured;
            if (ownerCls >= 0) {
                ParserRuleContext ownerClsCtx = builder.findEnclosingClassCtx(literal);
                captured = builder.hirVars(builder.resolution.captured(ownerClsCtx));
            } else {
                captured = builder.hirVars(builder.resolution.captured(literal));
            }
            return new HirFunction(
                    id,
                    ScopeAnalysis.functionName(literal),
                    builder.functionJvmNames.get(literal),
                    ownerCls,
                    memberName,
                    builder.resolution.isNamespaceScope(literal),
                    builder.scopes.declared(literal),
                    builder.hirVars(builder.resolution.declared(literal)),
                    captured,
                    params,
                    builder.scopes.calls().requiredParameters(literal),
                    builder.scopes.calls().keywordOnlyStart(literal),
                    builder.scopes.calls().restIndex(literal),
                    builder.scopes.lowering().functionReturnKind(literal),
                    builder.scopes.lowering().numericWideningRank(literal.functionBlock),
                    builder.scopes.effects().callableEffects(literal),
                    body,
                    literal.getStart().getLine());
        } finally {
            builder.currentFunction = savedFn;
        }
    }

    HirClass lowerClass(ParserRuleContext ctx) {
        BabbleParser.FunctionLiteralContext savedFn = builder.currentFunction;
        builder.currentFunction = null;
        try {
            int id = builder.classIds.get(ctx);
            boolean isObject = ctx instanceof BabbleParser.ObjectExpressionContext;
            ClassShape shape = builder.scopes.hierarchy().shape(ctx);
            String jvmName = builder.compiledClassJvmNames.get(ctx);
            List<String> implementedJvm = builder.classImplementedJvm.get(ctx);
            List<Class<?>> javaIfaces = builder.classJavaInterfaces.get(ctx);

            List<? extends ParserRuleContext> paramDecls =
                    ctx instanceof BabbleParser.ClassExpressionContext c
                                    && c.classParameters() != null
                            ? c.classParameters().classParam()
                            : List.of();
            BabbleParser.BlockContext bodyBlock =
                    ctx instanceof BabbleParser.ClassExpressionContext c
                            ? c.classBlock
                            : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            BabbleParser.TypeListContext typeList =
                    ctx instanceof BabbleParser.ClassExpressionContext c
                            ? c.typeList()
                            : ((BabbleParser.ObjectExpressionContext) ctx).typeList();
            BabbleParser.BlockContext staticBlk =
                    ctx instanceof BabbleParser.ClassExpressionContext c ? c.staticBlock : null;

            int count = shape.memberCount();
            PrimitiveKind[] memberKinds = new PrimitiveKind[count];
            int[] primDefBits = new int[count];
            Arrays.fill(memberKinds, PrimitiveKind.NONE);
            Arrays.fill(primDefBits, -1);
            if (ctx instanceof BabbleParser.ClassExpressionContext c
                    && c.classParameters() != null) {
                for (int p = 0; p < shape.parameters().length; p++) {
                    int idx = shape.parameterMember(p);
                    if (idx >= 0 && !shape.isMethod(idx)) {
                        memberKinds[idx] =
                                builder.scopes
                                        .lowering()
                                        .declarationKind(c.classParameters().classParam(p));
                    }
                }
            }
            int bits = 0;
            for (BabbleParser.ExpressionContext expr : bodyBlock.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def) {
                    LoweringTargets.DefTarget dt = builder.scopes.lowering().defTarget(def);
                    if (dt.kind() == LoweringTargets.DefKind.MEMBER) {
                        int idx = dt.index();
                        if (!shape.isMethod(idx)) {
                            PrimitiveKind pk = builder.scopes.lowering().declarationKind(def);
                            memberKinds[idx] = pk;
                            if (pk.isPrimitive()) {
                                primDefBits[idx] = bits++;
                            }
                        }
                    }
                }
            }
            int primDefCount = bits;

            Map<String, Integer> methodFnIds = new LinkedHashMap<>();
            Map<String, List<Integer>> overloadMethodFnIds = new LinkedHashMap<>();
            for (BabbleParser.ExpressionContext expr : bodyBlock.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def) {
                    LoweringTargets.DefTarget dt = builder.scopes.lowering().defTarget(def);
                    if (dt.kind() != LoweringTargets.DefKind.MEMBER
                            || !shape.isMethod(dt.index())) {
                        continue;
                    }
                    if (def.value instanceof BabbleParser.FunctionLiteralContext lit) {
                        methodFnIds.put(def.name.getText(), builder.functionIds.get(lit));
                    } else if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                        List<Integer> ids = new ArrayList<>();
                        collectOverloadFunctionIds(ov, ids);
                        overloadMethodFnIds.put(def.name.getText(), ids);
                    }
                }
            }

            List<HirParam> params = new ArrayList<>(paramDecls.size());
            String[] paramNames = shape.parameters();
            for (int i = 0; i < paramDecls.size(); i++) {
                ParserRuleContext p = paramDecls.get(i);
                BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(p);
                params.add(
                        new HirParam(
                                paramNames[i],
                                builder.hirVar(builder.resolution.variable(p)),
                                builder.scopes.lowering().declarationKind(p),
                                builder.scopes.lowering().numericWideningRank(p),
                                builder.scopes.lowering().checkedType(p),
                                shape.parameterMember(i),
                                defVal != null ? builder.lowerExpr(defVal) : null,
                                builder.scopes.lowering().javaSamTarget(p),
                                p.getStart().getLine()));
            }

            List<HirExpr> body = new ArrayList<>(bodyBlock.expression().size());
            BabbleParser.InitExpressionContext primaryInit = null;
            List<BabbleParser.InitExpressionContext> explicitInits = new ArrayList<>();
            for (BabbleParser.ExpressionContext e : bodyBlock.expression()) {
                if (e instanceof BabbleParser.InitExpressionContext initCtx) {
                    if (initCtx.fnParameters() == null) {
                        primaryInit = initCtx;
                    } else {
                        explicitInits.add(initCtx);
                    }
                    continue;
                }
                if (e instanceof BabbleParser.DefExpressionContext def
                        && def.value == null
                        && builder.scopes.lowering().defTarget(def).kind()
                                == LoweringTargets.DefKind.MEMBER) {
                    continue;
                }
                body.add(builder.lowerExpr(e));
            }

            boolean allDefFinalSafe =
                    !isObject && builder.scopes.lowering().isAllDefFieldsFinalSafe(ctx);
            List<HirConstructor> constructors =
                    lowerConstructors(
                            ctx,
                            id,
                            isObject,
                            shape,
                            params,
                            primaryInit,
                            explicitInits,
                            allDefFinalSafe);

            List<HirVar> declared = builder.hirVars(builder.resolution.declared(ctx));
            List<HirVar> captured = builder.hirVars(builder.resolution.captured(ctx));
            boolean fastRecord =
                    primaryInit == null
                            && explicitInits.isEmpty()
                            && isFastRecordClass(shape, declared, captured, params, body);

            return new HirClass(
                    id,
                    isObject,
                    shape,
                    jvmName,
                    implementedJvm,
                    javaIfaces,
                    declared,
                    captured,
                    params,
                    memberKinds,
                    primDefBits,
                    primDefCount,
                    methodFnIds,
                    overloadMethodFnIds,
                    lowerTypeList(typeList),
                    body,
                    constructors,
                    allDefFinalSafe,
                    lowerStaticBlock(staticBlk),
                    fastRecord,
                    ctx.getStart().getLine());
        } finally {
            builder.currentFunction = savedFn;
        }
    }

    private List<HirConstructor> lowerConstructors(
            ParserRuleContext ctx,
            int classId,
            boolean isObject,
            ClassShape shape,
            List<HirParam> primaryParams,
            BabbleParser.InitExpressionContext primaryInit,
            List<BabbleParser.InitExpressionContext> explicitInits,
            boolean allDefFinalSafe) {
        if (isObject) {
            List<HirVar> initVars = initDeclaredVars(primaryInit);
            List<HirExpr> initBody =
                    primaryInit != null
                            ? lowerExprList(primaryInit.initBlock.expression())
                            : List.of();
            return List.of(
                    new HirConstructor(
                            0,
                            classId,
                            true,
                            false,
                            initVars,
                            List.of(),
                            0,
                            0,
                            -1,
                            -1,
                            List.of(),
                            List.of(),
                            null,
                            initBody,
                            false,
                            primaryInit != null
                                    ? primaryInit.getStart().getLine()
                                    : ctx.getStart().getLine()));
        }
        int ctorCount = shape.constructorCount();
        List<HirConstructor> result = new ArrayList<>(ctorCount);
        int explicitCursor = 0;
        for (int k = 0; k < ctorCount; k++) {
            ClassShape.ConstructorShape cs = shape.constructor(k);
            if (cs.isPrimary()) {
                List<HirVar> initVars = initDeclaredVars(primaryInit);
                List<HirExpr> initBody =
                        primaryInit != null
                                ? lowerExprList(primaryInit.initBlock.expression())
                                : List.of();
                result.add(
                        new HirConstructor(
                                k,
                                classId,
                                true,
                                cs.isPrivate(),
                                initVars,
                                primaryParams,
                                cs.requiredParameters(),
                                cs.keywordOnlyStart(),
                                cs.restIndex(),
                                -1,
                                List.of(),
                                List.of(),
                                null,
                                initBody,
                                allDefFinalSafe,
                                primaryInit != null
                                        ? primaryInit.getStart().getLine()
                                        : ctx.getStart().getLine()));
            } else {
                BabbleParser.InitExpressionContext initCtx = explicitInits.get(explicitCursor++);
                int initOwnerId = builder.initIds.getOrDefault(initCtx, classId);
                List<BabbleParser.FnParamContext> fnParams = initCtx.fnParameters().fnParam();
                List<HirParam> ctorParams = new ArrayList<>(fnParams.size());
                String[] paramNames = cs.parameters();
                for (int i = 0; i < fnParams.size(); i++) {
                    BabbleParser.FnParamContext p = fnParams.get(i);
                    BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(p);
                    ctorParams.add(
                            new HirParam(
                                    paramNames[i],
                                    builder.hirVar(builder.resolution.variable(p)),
                                    builder.scopes.lowering().declarationKind(p),
                                    builder.scopes.lowering().numericWideningRank(p),
                                    builder.scopes.lowering().checkedType(p),
                                    -1,
                                    defVal != null ? builder.lowerExpr(defVal) : null,
                                    builder.scopes.lowering().javaSamTarget(p),
                                    builder.scopes.lowering().paramOwnership(p),
                                    ScopeAnalysis.isVar(p),
                                    p.getStart().getLine()));
                }
                List<BabbleParser.ExpressionContext> exprs = initCtx.initBlock.expression();
                int delPos = -1;
                BabbleParser.CallContext delCall = null;
                for (int i = 0; i < exprs.size(); i++) {
                    if (exprs.get(i) instanceof BabbleParser.CallContext cc
                            && builder.scopes.calls().isDelegatingThisCall(cc)) {
                        delPos = i;
                        delCall = cc;
                        break;
                    }
                }
                int delegateIndex = -1;
                List<HirExpr> preDelegate = List.of();
                List<HirCallArg> delegateArgs = List.of();
                LoweringTargets.ArgumentBinding delegateBinding = null;
                List<HirExpr> initBody;
                if (delCall != null) {
                    delegateIndex = builder.scopes.calls().constructorIndex(delCall);
                    preDelegate = lowerExprList(exprs.subList(0, delPos));
                    delegateArgs =
                            builder.callLowerer.lowerCallArgs(
                                    delCall.callParameters().callParameter(),
                                    builder.scopes.calls().callSpreadAdapts(delCall));
                    delegateBinding = builder.scopes.calls().argumentBinding(delCall);
                    initBody = lowerExprList(exprs.subList(delPos + 1, exprs.size()));
                } else {
                    initBody = lowerExprList(exprs);
                }
                result.add(
                        new HirConstructor(
                                k,
                                initOwnerId,
                                false,
                                cs.isPrivate(),
                                initDeclaredVars(initCtx),
                                ctorParams,
                                cs.requiredParameters(),
                                cs.keywordOnlyStart(),
                                cs.restIndex(),
                                delegateIndex,
                                preDelegate,
                                delegateArgs,
                                delegateBinding,
                                initBody,
                                allDefFinalSafe,
                                initCtx.getStart().getLine()));
            }
        }
        return result;
    }

    private List<HirVar> initDeclaredVars(BabbleParser.InitExpressionContext initCtx) {
        if (initCtx == null) {
            return List.of();
        }
        List<HirVar> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (HirVar v : builder.hirVars(builder.resolution.declared(initCtx))) {
            if (seen.add(v.id())) {
                out.add(v);
            }
        }
        if (initCtx.initBlock != null) {
            for (HirVar v : builder.hirVars(builder.resolution.declared(initCtx.initBlock))) {
                if (seen.add(v.id())) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private List<HirExpr> lowerExprList(List<BabbleParser.ExpressionContext> exprs) {
        if (exprs.isEmpty()) {
            return List.of();
        }
        List<HirExpr> out = new ArrayList<>(exprs.size());
        for (BabbleParser.ExpressionContext e : exprs) {
            out.add(builder.lowerExpr(e));
        }
        return out;
    }

    private static boolean isFastRecordClass(
            ClassShape shape,
            List<HirVar> declared,
            List<HirVar> captured,
            List<HirParam> params,
            List<HirExpr> body) {
        if (shape == null || !shape.isValue() || !captured.isEmpty() || params.size() > 8) {
            return false;
        }
        for (HirVar v : declared) {
            if (v.isCaptured()) {
                return false;
            }
        }
        int slots = 3;
        for (HirParam p : params) {
            if (p.samTarget() != null) {
                return false;
            }
            slots += p.kind().slots();
        }
        if (slots > 200) {
            return false;
        }
        for (HirExpr stmt : body) {
            if (!(stmt instanceof HirExpr.Def d)
                    || d.target().kind() != LoweringTargets.DefKind.MEMBER
                    || !shape.isMethod(d.target().index())) {
                return false;
            }
            if (d.value() instanceof HirExpr.FunctionRef) {
                continue;
            }
            if (d.value() instanceof HirExpr.MakeOverloadSet mos && !mos.operands().isEmpty()) {
                for (HirExpr op : mos.operands()) {
                    if (!(op instanceof HirExpr.FunctionRef)) {
                        return false;
                    }
                }
                continue;
            }
            return false;
        }
        return true;
    }

    HirInterface lowerInterface(BabbleParser.InterfaceExpressionContext ctx) {
        BabbleParser.FunctionLiteralContext savedFn = builder.currentFunction;
        builder.currentFunction = null;
        try {
            int id = builder.interfaceIds.get(ctx);
            ClassShape shape = builder.scopes.hierarchy().shape(ctx);
            String jvmName = builder.ownInterfaceJvmNames.get(shape.id());
            List<String> extendedJvm = new ArrayList<>();
            LoweringTargets.TypeHierarchyInfo info =
                    builder.scopes.hierarchy().typeHierarchy(shape.id());
            if (info != null) {
                for (LoweringTargets.ParentRef parent : info.directParents()) {
                    if (parent.babbleTypeId() != null) {
                        String parentJvm = builder.allInterfaceJvmNames.get(parent.babbleTypeId());
                        if (parentJvm != null && !extendedJvm.contains(parentJvm)) {
                            extendedJvm.add(parentJvm);
                        }
                    } else if (parent.javaClass() != null) {
                        Class<?> jc = parent.javaClass();
                        if (jc.isInterface() && JavaTypes.isAccessible(jc)) {
                            String javaInternal = Type.getInternalName(jc);
                            if (!extendedJvm.contains(javaInternal)) {
                                extendedJvm.add(javaInternal);
                            }
                        }
                    }
                }
            }
            Map<String, Integer> defaultFnIds = new LinkedHashMap<>();
            Map<String, List<Integer>> defaultOverloadFnIds = new LinkedHashMap<>();
            for (BabbleParser.ExpressionContext expr : ctx.interfaceBlock.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def) {
                    if (def.value instanceof BabbleParser.FunctionLiteralContext lit) {
                        defaultFnIds.put(def.name.getText(), builder.functionIds.get(lit));
                    } else if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                        List<Integer> ids = new ArrayList<>();
                        collectOverloadFunctionIds(ov, ids);
                        defaultOverloadFnIds.put(def.name.getText(), ids);
                    }
                }
            }
            List<HirExpr> body = new ArrayList<>(ctx.interfaceBlock.expression().size());
            for (BabbleParser.ExpressionContext e : ctx.interfaceBlock.expression()) {
                body.add(builder.lowerExpr(e));
            }
            return new HirInterface(
                    id,
                    shape,
                    jvmName,
                    extendedJvm,
                    defaultFnIds,
                    defaultOverloadFnIds,
                    lowerTypeList(ctx.typeList()),
                    body,
                    lowerStaticBlock(ctx.staticBlock),
                    ctx.getStart().getLine());
        } finally {
            builder.currentFunction = savedFn;
        }
    }

    private HirStaticBlock lowerStaticBlock(BabbleParser.BlockContext block) {
        if (block == null) {
            return null;
        }
        List<HirExpr> stmts = new ArrayList<>(block.expression().size());
        for (BabbleParser.ExpressionContext e : block.expression()) {
            stmts.add(builder.lowerExpr(e));
        }
        return new HirStaticBlock(
                builder.resolution.isNamespaceScope(block),
                builder.hirVars(builder.resolution.declared(block)),
                stmts);
    }

    private List<HirTypeRef> lowerTypeList(BabbleParser.TypeListContext typeList) {
        if (typeList == null || typeList.type().isEmpty()) {
            return List.of();
        }
        List<HirTypeRef> result = new ArrayList<>(typeList.type().size());
        for (BabbleParser.TypeContext t : typeList.type()) {
            result.add(lowerTypeRef(t, t));
        }
        return result;
    }

    HirTypeRef lowerTypeRef(BabbleParser.TypeContext type, ParserRuleContext occurrence) {
        List<String> path = ScopeAnalysis.simpleTypePath(ScopeAnalysis.simpleType(type));
        return new HirTypeRef(
                type.getStart().getLine(),
                type.getText(),
                path,
                builder.hirVar(builder.resolution.variable(occurrence)));
    }

    // ---------------------------------------------------------------- exports & type hierarchies

    Map<String, LoweringTargets.ExportedFunctionTarget> collectOwnExportedFunctions(
            List<HirExpr> topLevel,
            Map<Integer, HirFunction> hirFunctions,
            String[] exports,
            String[] topDeclared,
            boolean[] topFinals) {
        if (exports == null || exports.length == 0) {
            return Map.of();
        }
        Set<String> exportSet = Set.of(exports);
        Set<String> immutableTopDefs = new LinkedHashSet<>();
        for (int i = 0; i < topDeclared.length && i < topFinals.length; i++) {
            if (topFinals[i]) {
                immutableTopDefs.add(topDeclared[i]);
            }
        }
        Map<String, ModuleInterface> importedByKey = new LinkedHashMap<>();
        ArrayDeque<ModuleInterface> queue = new ArrayDeque<>();
        for (ModuleInterface mod : builder.scopes.importedModules()) {
            if (mod != null) {
                queue.add(mod);
            }
        }
        while (!queue.isEmpty()) {
            ModuleInterface mod = queue.poll();
            String k = mod.moduleKey() != null ? mod.moduleKey() : mod.name();
            if (k != null && !importedByKey.containsKey(k)) {
                importedByKey.put(k, mod);
                for (ModuleInterface dep : mod.dependencies()) {
                    if (dep != null) {
                        queue.add(dep);
                    }
                }
            }
        }
        String selfKey =
                builder.scopes.moduleInterface() != null
                                && builder.scopes.moduleInterface().moduleKey() != null
                        ? builder.scopes.moduleInterface().moduleKey()
                        : builder.sourceName;

        // Pass 1: direct FunctionRef defs and cross-module ModuleExportGet re-exports.
        Map<String, LoweringTargets.ExportedFunctionTarget> defTargets = new LinkedHashMap<>();
        Map<String, String> localAliases = new LinkedHashMap<>();
        for (HirExpr stmt : topLevel) {
            HirExpr u = unwrapTransparent(stmt);
            if (!(u instanceof HirExpr.Def d)
                    || d.var() != null
                    || d.target().kind() != LoweringTargets.DefKind.LEXICAL
                    || !immutableTopDefs.contains(d.name())) {
                continue;
            }
            HirExpr val = unwrapTransparent(d.value());
            if (val instanceof HirExpr.FunctionRef ref) {
                HirFunction fn = hirFunctions.get(ref.functionId());
                if (fn != null && fn.capturedVars().isEmpty() && (fn.hasSpec() || fn.isSpread())) {
                    defTargets.put(d.name(), buildExportedFunctionTarget(selfKey, d.name(), fn));
                }
            } else if (val instanceof HirExpr.ModuleExportGet meg) {
                String depKey =
                        meg.target().moduleKey() != null
                                ? meg.target().moduleKey()
                                : meg.target().moduleName();
                ModuleInterface depMod = depKey != null ? importedByKey.get(depKey) : null;
                if (depMod != null) {
                    LoweringTargets.ExportedFunctionTarget reexp =
                            depMod.exportedFunction(meg.target().name());
                    if (reexp != null) {
                        defTargets.put(d.name(), reexp);
                    }
                }
            } else if (val instanceof HirExpr.NameRead nr && immutableTopDefs.contains(nr.name())) {
                localAliases.put(d.name(), nr.name());
            }
        }
        // Pass 2: resolve local immutable alias chains (`def h = fn ...; export def sq = h`).
        for (Map.Entry<String, String> e : localAliases.entrySet()) {
            String name = e.getKey();
            String cur = e.getValue();
            int steps = 0;
            while (cur != null
                    && !defTargets.containsKey(cur)
                    && localAliases.containsKey(cur)
                    && steps++ < 16) {
                cur = localAliases.get(cur);
            }
            LoweringTargets.ExportedFunctionTarget target =
                    cur != null ? defTargets.get(cur) : null;
            if (target != null) {
                if (selfKey.equals(target.moduleKey())
                        && builder.className.equals(target.ownerJvmName())) {
                    // Local alias to an unexported local helper: publish under this export's own
                    // name.
                    defTargets.put(
                            name,
                            new LoweringTargets.ExportedFunctionTarget(
                                    selfKey,
                                    builder.sourceName,
                                    name,
                                    target.ownerJvmName(),
                                    target.jvmMethodName(),
                                    target.signature(),
                                    target.hasMutParam(),
                                    target.effects()));
                } else {
                    defTargets.put(name, target);
                }
            }
        }
        Map<String, LoweringTargets.ExportedFunctionTarget> result = new LinkedHashMap<>();
        for (String exp : exportSet) {
            LoweringTargets.ExportedFunctionTarget t = defTargets.get(exp);
            if (t != null) {
                result.put(exp, t);
            }
        }
        return result;
    }

    private LoweringTargets.ExportedFunctionTarget buildExportedFunctionTarget(
            String selfKey, String exportName, HirFunction fn) {
        PrimitiveKind[] paramKinds = new PrimitiveKind[fn.params().size()];
        boolean hasMutParam = false;
        for (int i = 0; i < fn.params().size(); i++) {
            HirParam p = fn.params().get(i);
            paramKinds[i] = p.kind();
            if (p.isMut()) {
                hasMutParam = true;
            }
        }
        LoweringTargets.FunctionSignature sig =
                new LoweringTargets.FunctionSignature(
                        paramKinds, fn.returnKind(), fn.optionalParamBits(), fn.restIndex());
        return new LoweringTargets.ExportedFunctionTarget(
                selfKey,
                builder.sourceName,
                exportName,
                builder.className,
                fn.jvmMethodName(),
                sig,
                hasMutParam,
                fn.effects());
    }

    private static HirExpr unwrapTransparent(HirExpr expr) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        return expr;
    }

    Map<String, LoweringTargets.TypeHierarchyInfo> collectAllTypeHierarchies() {
        Map<String, LoweringTargets.TypeHierarchyInfo> result = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, LoweringTargets.TypeHierarchyInfo> e :
                builder.scopes.hierarchy().typeHierarchies().entrySet()) {
            result.put(e.getKey(), e.getValue());
            queue.add(e.getKey());
        }
        for (String id : builder.allInterfaceJvmNames.keySet()) {
            if (!result.containsKey(id)) {
                LoweringTargets.TypeHierarchyInfo info =
                        builder.scopes.hierarchy().typeHierarchy(id);
                if (info != null) {
                    result.put(id, info);
                    queue.add(id);
                }
            }
        }
        while (!queue.isEmpty()) {
            LoweringTargets.TypeHierarchyInfo info = result.get(queue.poll());
            if (info == null) continue;
            for (LoweringTargets.ParentRef p : info.directParents()) {
                String pid = p.babbleTypeId();
                if (pid != null && !result.containsKey(pid)) {
                    LoweringTargets.TypeHierarchyInfo pinfo =
                            builder.scopes.hierarchy().typeHierarchy(pid);
                    if (pinfo != null) {
                        result.put(pid, pinfo);
                        queue.add(pid);
                    }
                }
            }
            for (String ancId : info.allBabbleInterfaceIds()) {
                if (ancId != null && !result.containsKey(ancId)) {
                    LoweringTargets.TypeHierarchyInfo ainfo =
                            builder.scopes.hierarchy().typeHierarchy(ancId);
                    if (ainfo != null) {
                        result.put(ancId, ainfo);
                        queue.add(ancId);
                    }
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- interface helpers

    private List<Class<?>> implementableJavaInterfaces(ClassShape shape) {
        if (shape == null) {
            return List.of();
        }
        LoweringTargets.TypeHierarchyInfo info =
                builder.scopes.hierarchy().typeHierarchy(shape.id());
        if (info == null) {
            return List.of();
        }
        Set<Class<?>> javaIfaces = new LinkedHashSet<>();
        for (Class<?> jc : info.allJavaInterfaces()) {
            collectImplementableJavaInterfaces(jc, javaIfaces);
        }
        return List.copyOf(javaIfaces);
    }

    private List<String> implementedInterfaces(ClassShape shape, List<Class<?>> javaIfaces) {
        if (shape == null) {
            return List.of();
        }
        LoweringTargets.TypeHierarchyInfo info =
                builder.scopes.hierarchy().typeHierarchy(shape.id());
        if (info == null) {
            return List.of();
        }
        List<String> implemented = new ArrayList<>();
        for (LoweringTargets.ParentRef parent : info.directParents()) {
            if (parent.babbleTypeId() != null) {
                String ifaceJvm = builder.allInterfaceJvmNames.get(parent.babbleTypeId());
                if (ifaceJvm != null && !implemented.contains(ifaceJvm)) {
                    implemented.add(ifaceJvm);
                }
            }
        }
        for (Class<?> jc : javaIfaces) {
            String javaInternal = Type.getInternalName(jc);
            if (!implemented.contains(javaInternal)) {
                implemented.add(javaInternal);
            }
        }
        return implemented;
    }

    private static void collectImplementableJavaInterfaces(Class<?> iface, Set<Class<?>> out) {
        if (iface == null
                || !iface.isInterface()
                || !JavaTypes.isAccessible(iface)
                || !out.add(iface)) {
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            collectImplementableJavaInterfaces(parent, out);
        }
    }
}
