package org.babblelang.ir.hir;

import org.babblelang.analysis.CallMetadata;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.analysis.SyntaxText;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.parser.StringLiterals;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates an ANTLR4 {@link BabbleParser.FileContext} plus its {@link ScopeAnalysis} and
 * {@link Resolution} side tables into a completely ANTLR-free {@link HirModule} (BIP014-I1, BIP014-D8).
 *
 * <p>This is the single boundary where {@code ParserRuleContext} is inspected by the compiler
 * backend; all downstream optimization passes and bytecode emitters consume {@link HirModule} only.
 */
public final class HirBuilder {
    private final String className;
    private final String sourceName;
    private final ScopeAnalysis scopes;
    private final Resolution resolution;

    private final Map<String, String> allInterfaceJvmNames = new LinkedHashMap<>();
    private final Map<String, String> allClassJvmNames = new LinkedHashMap<>();
    private final Map<String, String> ownInterfaceJvmNames = new LinkedHashMap<>();
    private final Map<String, String> ownClassJvmNames = new LinkedHashMap<>();
    private final Map<String, String> localClassJvmNamesById = new LinkedHashMap<>();

    private final Map<BabbleParser.InterfaceExpressionContext, Integer> interfaceIds = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Integer> classIds = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, Integer> functionIds = new IdentityHashMap<>();
    private final Map<ParserRuleContext, String> preassignedClassNames = new IdentityHashMap<>();
    private final Map<ParserRuleContext, String> compiledClassJvmNames = new IdentityHashMap<>();
    private final Map<ParserRuleContext, List<Class<?>>> classJavaInterfaces = new IdentityHashMap<>();
    private final Map<ParserRuleContext, List<String>> classImplementedJvm = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, Integer> methodOwnerClassIds = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, String> methodMemberNames = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, String> functionJvmNames = new IdentityHashMap<>();
    private final Map<Resolution.Variable, HirVar> vars = new IdentityHashMap<>();

    private final List<BabbleParser.InterfaceExpressionContext> orderedInterfaces = new ArrayList<>();
    private final List<ParserRuleContext> orderedClasses = new ArrayList<>();
    private final List<BabbleParser.FunctionLiteralContext> orderedFunctions = new ArrayList<>();

    private int nextOwnerId = 1;
    private int nextVarId = 1;
    private int interfaceCount = 0;
    private int classCount = 0;
    private int functionCount = 0;

    private BabbleParser.FunctionLiteralContext currentFunction;

    private HirBuilder(String className, String sourceName, ScopeAnalysis scopes,
                       Map<String, String> knownInterfaceJvmNames, Map<String, String> knownClassJvmNames) {
        this.className = className;
        this.sourceName = sourceName;
        this.scopes = scopes;
        this.resolution = scopes.resolution();
        if (knownInterfaceJvmNames != null) {
            this.allInterfaceJvmNames.putAll(knownInterfaceJvmNames);
        }
        if (knownClassJvmNames != null) {
            this.allClassJvmNames.putAll(knownClassJvmNames);
        }
    }

    public static HirModule build(String className, String sourceName, BabbleParser.FileContext file,
                                  ScopeAnalysis scopes, Map<String, String> knownInterfaceJvmNames,
                                  Map<String, String> knownClassJvmNames) {
        return new HirBuilder(className, sourceName, scopes, knownInterfaceJvmNames, knownClassJvmNames).run(file);
    }

    private HirModule run(BabbleParser.FileContext file) {
        // Pass 1: discover all interfaces, classes/objects, and function literals and assign stable IDs & JVM names.
        discoverAll(file);

        // Pass 2: lower top-level statements, functions, classes, and interfaces to ANTLR-free HIR.
        List<HirExpr> topLevel = new ArrayList<>(file.expression().size());
        for (BabbleParser.ExpressionContext expr : file.expression()) {
            topLevel.add(lowerExpr(expr));
        }

        Map<Integer, HirFunction> hirFunctions = new LinkedHashMap<>();
        for (BabbleParser.FunctionLiteralContext fnCtx : orderedFunctions) {
            HirFunction fn = lowerFunction(fnCtx);
            hirFunctions.put(fn.id(), fn);
        }

        Map<Integer, HirClass> hirClasses = new LinkedHashMap<>();
        for (ParserRuleContext clsCtx : orderedClasses) {
            HirClass cls = lowerClass(clsCtx);
            hirClasses.put(cls.id(), cls);
        }

        Map<Integer, HirInterface> hirInterfaces = new LinkedHashMap<>();
        for (BabbleParser.InterfaceExpressionContext ifaceCtx : orderedInterfaces) {
            HirInterface iface = lowerInterface(ifaceCtx);
            hirInterfaces.put(iface.id(), iface);
        }

        return new HirModule(
                className,
                sourceName,
                file.getStart().getLine(),
                scopes.exports(),
                scopes.declared(file),
                topLevel,
                hirFunctions,
                hirClasses,
                hirInterfaces,
                collectAllTypeHierarchies(),
                allInterfaceJvmNames,
                allClassJvmNames,
                ownInterfaceJvmNames,
                ownClassJvmNames,
                nextVarId,
                nextOwnerId
        );
    }

    private Map<String, LoweringTargets.TypeHierarchyInfo> collectAllTypeHierarchies() {
        Map<String, LoweringTargets.TypeHierarchyInfo> result = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (Map.Entry<String, LoweringTargets.TypeHierarchyInfo> e : scopes.hierarchy().typeHierarchies().entrySet()) {
            result.put(e.getKey(), e.getValue());
            queue.add(e.getKey());
        }
        for (String id : allInterfaceJvmNames.keySet()) {
            if (!result.containsKey(id)) {
                LoweringTargets.TypeHierarchyInfo info = scopes.hierarchy().typeHierarchy(id);
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
                    LoweringTargets.TypeHierarchyInfo pinfo = scopes.hierarchy().typeHierarchy(pid);
                    if (pinfo != null) {
                        result.put(pid, pinfo);
                        queue.add(pid);
                    }
                }
            }
            for (String ancId : info.allBabbleInterfaceIds()) {
                if (ancId != null && !result.containsKey(ancId)) {
                    LoweringTargets.TypeHierarchyInfo ainfo = scopes.hierarchy().typeHierarchy(ancId);
                    if (ainfo != null) {
                        result.put(ancId, ainfo);
                        queue.add(ancId);
                    }
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- Pass 1: discovery

    private void discoverAll(BabbleParser.FileContext file) {
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
                int id = nextOwnerId++;
                interfaceIds.put(ctx, id);
                orderedInterfaces.add(ctx);
                ClassShape shape = scopes.hierarchy().shape(ctx);
                if (shape != null) {
                    String jvmName = className + "$I" + (interfaceCount++) + "$" + shape.name();
                    ownInterfaceJvmNames.put(shape.id(), jvmName);
                    allInterfaceJvmNames.put(shape.id(), jvmName);
                }
                return super.visitInterfaceExpression(ctx);
            }

            @Override
            public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
                int id = nextOwnerId++;
                classIds.put(ctx, id);
                orderedClasses.add(ctx);
                ClassShape shape = scopes.hierarchy().shape(ctx);
                if (shape != null && isStaticTopLevelTypeExpr(ctx)) {
                    String jvmName = className + "$C" + (classCount++) + "$" + shape.name();
                    ownClassJvmNames.put(shape.id(), jvmName);
                    allClassJvmNames.put(shape.id(), jvmName);
                    preassignedClassNames.put(ctx, jvmName);
                }
                return super.visitClassExpression(ctx);
            }

            @Override
            public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
                int id = nextOwnerId++;
                classIds.put(ctx, id);
                orderedClasses.add(ctx);
                return super.visitObjectExpression(ctx);
            }

            @Override
            public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
                int id = nextOwnerId++;
                functionIds.put(ctx, id);
                orderedFunctions.add(ctx);
                return super.visitFunctionLiteral(ctx);
            }
        }.visit(file);

        // Compute compiled JVM names and implemented interfaces for every class/object.
        for (ParserRuleContext clsCtx : orderedClasses) {
            ClassShape shape = scopes.hierarchy().shape(clsCtx);
            List<Class<?>> javaIfaces = implementableJavaInterfaces(shape);
            List<String> implemented = implementedInterfaces(shape, javaIfaces);
            classJavaInterfaces.put(clsCtx, javaIfaces);
            classImplementedJvm.put(clsCtx, implemented);
            String preassigned = preassignedClassNames.get(clsCtx);
            String name = preassigned != null ? preassigned : className + "$C" + (classCount++) + "$" + shape.name();
            compiledClassJvmNames.put(clsCtx, name);
            if (shape != null) {
                localClassJvmNamesById.put(shape.id(), name);
            }
        }

        // Assign JVM method names & owning compiled class for every function literal.
        Map<String, Integer> overloadMemberCounters = new LinkedHashMap<>();
        for (BabbleParser.FunctionLiteralContext fnCtx : orderedFunctions) {
            int ownerCls = findCompiledMethodOwnerClassId(fnCtx);
            methodOwnerClassIds.put(fnCtx, ownerCls);
            if (ownerCls >= 0) {
                BabbleParser.DefExpressionContext def = owningMemberDef(fnCtx);
                String member = def.name.getText();
                methodMemberNames.put(fnCtx, member);
                if (def.value instanceof BabbleParser.OverloadExpressionContext) {
                    int idx = overloadMemberCounters.merge(ownerCls + ":" + member, 1, Integer::sum) - 1;
                    functionJvmNames.put(fnCtx, "m$" + member + "$" + idx);
                } else {
                    functionJvmNames.put(fnCtx, "m$" + member);
                }
            } else {
                String name = ScopeAnalysis.functionName(fnCtx);
                functionJvmNames.put(fnCtx, "fn$" + (functionCount++) + (name != null ? "$" + name : ""));
            }
        }
    }

    private static BabbleParser.DefExpressionContext owningMemberDef(BabbleParser.FunctionLiteralContext literal) {
        ParserRuleContext cur = literal;
        while (cur.getParent() instanceof BabbleParser.OverloadExpressionContext ov) {
            cur = ov;
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur ? def : null;
    }

    private void collectOverloadFunctionIds(BabbleParser.OverloadExpressionContext ov, List<Integer> out) {
        for (BabbleParser.ExpressionContext op : ov.expression()) {
            if (op instanceof BabbleParser.FunctionLiteralContext lit) {
                out.add(functionIds.get(lit));
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
                    && !scopes.opensScope(block)) {
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
        LoweringTargets.DefTarget dt = scopes.lowering().defTarget(def);
        if (dt.kind() != LoweringTargets.DefKind.MEMBER) {
            return -1;
        }
        for (ParserRuleContext p = def.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassShape shape = scopes.hierarchy().shape(p);
                if (shape != null && !shape.isMethod(dt.index())) {
                    return -1;
                }
                if (compiledClassJvmNames.containsKey(p)) {
                    return classIds.get(p);
                }
                return -1;
            }
        }
        return -1;
    }

    private int ownerIdOf(ParserRuleContext owner) {
        if (owner == null || owner instanceof BabbleParser.FileContext) {
            return 0;
        }
        Integer fnId = functionIds.get(owner);
        if (fnId != null) {
            return fnId;
        }
        Integer clsId = classIds.get(owner);
        if (clsId != null) {
            return clsId;
        }
        Integer ifaceId = interfaceIds.get(owner);
        if (ifaceId != null) {
            return ifaceId;
        }
        return 0;
    }

    private HirVar hirVar(Resolution.Variable v) {
        if (v == null) {
            return null;
        }
        return vars.computeIfAbsent(v, rv -> {
            ParserRuleContext decl = rv.declaration();
            PrimitiveKind pk = decl != null ? scopes.lowering().declarationKind(decl) : PrimitiveKind.NONE;
            boolean defDef = decl != null && scopes.lowering().isDefinitelyDefined(decl);
            return new HirVar(
                    nextVarId++,
                    rv.name(),
                    rv.kind(),
                    ownerIdOf(rv.method()),
                    pk,
                    rv.isCell(),
                    rv.isUsed(),
                    rv.mayBeUndefined(),
                    defDef
            );
        });
    }

    private List<HirVar> hirVars(List<Resolution.Variable> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<HirVar> result = new ArrayList<>(list.size());
        for (Resolution.Variable v : list) {
            result.add(hirVar(v));
        }
        return result;
    }

    // ---------------------------------------------------------------- Pass 2: declarations

    private HirFunction lowerFunction(BabbleParser.FunctionLiteralContext literal) {
        BabbleParser.FunctionLiteralContext savedFn = currentFunction;
        currentFunction = literal;
        try {
            int id = functionIds.get(literal);
            int ownerCls = methodOwnerClassIds.get(literal);
            String memberName = methodMemberNames.get(literal);
            String[] paramNames = scopes.calls().parameters(literal);
            List<BabbleParser.FnParamContext> paramCtxs = literal.fnParameters().fnParam();
            List<HirParam> params = new ArrayList<>(paramNames.length);
            for (int i = 0; i < paramNames.length; i++) {
                BabbleParser.FnParamContext p = paramCtxs.get(i);
                BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(p);
                params.add(new HirParam(
                        paramNames[i],
                        hirVar(resolution.variable(p)),
                        scopes.lowering().declarationKind(p),
                        scopes.lowering().numericWideningRank(p),
                        scopes.lowering().checkedType(p),
                        -1,
                        defVal != null ? lowerExpr(defVal) : null,
                        scopes.lowering().javaSamTarget(p),
                        p.getStart().getLine()
                ));
            }
            List<HirExpr> body = new ArrayList<>(literal.functionBlock.expression().size());
            for (BabbleParser.ExpressionContext e : literal.functionBlock.expression()) {
                body.add(lowerExpr(e));
            }
            if (!body.isEmpty()) {
                int last = body.size() - 1;
                body.set(last, wrapJavaSam(body.get(last), literal.functionBlock));
            }
            // Note: for a compiled class method, capturedVars are the enclosing class's captured vars!
            List<HirVar> captured;
            if (ownerCls >= 0) {
                ParserRuleContext ownerClsCtx = findEnclosingClassCtx(literal);
                captured = hirVars(resolution.captured(ownerClsCtx));
            } else {
                captured = hirVars(resolution.captured(literal));
            }
            return new HirFunction(
                    id,
                    ScopeAnalysis.functionName(literal),
                    functionJvmNames.get(literal),
                    ownerCls,
                    memberName,
                    resolution.isNamespaceScope(literal),
                    scopes.declared(literal),
                    hirVars(resolution.declared(literal)),
                    captured,
                    params,
                    scopes.calls().requiredParameters(literal),
                    scopes.calls().keywordOnlyStart(literal),
                    scopes.calls().restIndex(literal),
                    scopes.lowering().functionReturnKind(literal),
                    scopes.lowering().numericWideningRank(literal.functionBlock),
                    scopes.effects().callableEffects(literal),
                    body,
                    literal.getStart().getLine()
            );
        } finally {
            currentFunction = savedFn;
        }
    }

    private HirClass lowerClass(ParserRuleContext ctx) {
        BabbleParser.FunctionLiteralContext savedFn = currentFunction;
        currentFunction = null;
        try {
            int id = classIds.get(ctx);
            boolean isObject = ctx instanceof BabbleParser.ObjectExpressionContext;
            ClassShape shape = scopes.hierarchy().shape(ctx);
            String jvmName = compiledClassJvmNames.get(ctx);
            List<String> implementedJvm = classImplementedJvm.get(ctx);
            List<Class<?>> javaIfaces = classJavaInterfaces.get(ctx);

            List<? extends ParserRuleContext> paramDecls = ctx instanceof BabbleParser.ClassExpressionContext c && c.classParameters() != null
                    ? c.classParameters().classParam() : List.of();
            BabbleParser.BlockContext bodyBlock = ctx instanceof BabbleParser.ClassExpressionContext c
                    ? c.classBlock : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            BabbleParser.TypeListContext typeList = ctx instanceof BabbleParser.ClassExpressionContext c
                    ? c.typeList() : ((BabbleParser.ObjectExpressionContext) ctx).typeList();
            BabbleParser.BlockContext staticBlk = ctx instanceof BabbleParser.ClassExpressionContext c
                    ? c.staticBlock : null;

            int count = shape.memberCount();
            PrimitiveKind[] memberKinds = new PrimitiveKind[count];
            int[] primDefBits = new int[count];
            Arrays.fill(memberKinds, PrimitiveKind.NONE);
            Arrays.fill(primDefBits, -1);
            if (ctx instanceof BabbleParser.ClassExpressionContext c && c.classParameters() != null) {
                for (int p = 0; p < shape.parameters().length; p++) {
                    int idx = shape.parameterMember(p);
                    if (idx >= 0 && !shape.isMethod(idx)) {
                        memberKinds[idx] = scopes.lowering().declarationKind(c.classParameters().classParam(p));
                    }
                }
            }
            int bits = 0;
            for (BabbleParser.ExpressionContext expr : bodyBlock.expression()) {
                if (expr instanceof BabbleParser.DefExpressionContext def) {
                    LoweringTargets.DefTarget dt = scopes.lowering().defTarget(def);
                    if (dt.kind() == LoweringTargets.DefKind.MEMBER) {
                        int idx = dt.index();
                        if (!shape.isMethod(idx)) {
                            PrimitiveKind pk = scopes.lowering().declarationKind(def);
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
                    LoweringTargets.DefTarget dt = scopes.lowering().defTarget(def);
                    if (dt.kind() != LoweringTargets.DefKind.MEMBER || !shape.isMethod(dt.index())) {
                        continue;
                    }
                    if (def.value instanceof BabbleParser.FunctionLiteralContext lit) {
                        methodFnIds.put(def.name.getText(), functionIds.get(lit));
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
                params.add(new HirParam(
                        paramNames[i],
                        hirVar(resolution.variable(p)),
                        scopes.lowering().declarationKind(p),
                        scopes.lowering().numericWideningRank(p),
                        scopes.lowering().checkedType(p),
                        shape.parameterMember(i),
                        defVal != null ? lowerExpr(defVal) : null,
                        scopes.lowering().javaSamTarget(p),
                        p.getStart().getLine()
                ));
            }

            List<HirExpr> body = new ArrayList<>(bodyBlock.expression().size());
            for (BabbleParser.ExpressionContext e : bodyBlock.expression()) {
                body.add(lowerExpr(e));
            }

            return new HirClass(
                    id,
                    isObject,
                    shape,
                    jvmName,
                    implementedJvm,
                    javaIfaces,
                    hirVars(resolution.declared(ctx)),
                    hirVars(resolution.captured(ctx)),
                    params,
                    memberKinds,
                    primDefBits,
                    primDefCount,
                    methodFnIds,
                    overloadMethodFnIds,
                    lowerTypeList(typeList),
                    body,
                    lowerStaticBlock(staticBlk),
                    ctx.getStart().getLine()
            );
        } finally {
            currentFunction = savedFn;
        }
    }

    private HirInterface lowerInterface(BabbleParser.InterfaceExpressionContext ctx) {
        BabbleParser.FunctionLiteralContext savedFn = currentFunction;
        currentFunction = null;
        try {
            int id = interfaceIds.get(ctx);
            ClassShape shape = scopes.hierarchy().shape(ctx);
            String jvmName = ownInterfaceJvmNames.get(shape.id());
            List<String> extendedJvm = new ArrayList<>();
            LoweringTargets.TypeHierarchyInfo info = scopes.hierarchy().typeHierarchy(shape.id());
            if (info != null) {
                for (LoweringTargets.ParentRef parent : info.directParents()) {
                    if (parent.babbleTypeId() != null) {
                        String parentJvm = allInterfaceJvmNames.get(parent.babbleTypeId());
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
                        defaultFnIds.put(def.name.getText(), functionIds.get(lit));
                    } else if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                        List<Integer> ids = new ArrayList<>();
                        collectOverloadFunctionIds(ov, ids);
                        defaultOverloadFnIds.put(def.name.getText(), ids);
                    }
                }
            }
            List<HirExpr> body = new ArrayList<>(ctx.interfaceBlock.expression().size());
            for (BabbleParser.ExpressionContext e : ctx.interfaceBlock.expression()) {
                body.add(lowerExpr(e));
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
                    ctx.getStart().getLine()
            );
        } finally {
            currentFunction = savedFn;
        }
    }

    private HirStaticBlock lowerStaticBlock(BabbleParser.BlockContext block) {
        if (block == null) {
            return null;
        }
        List<HirExpr> stmts = new ArrayList<>(block.expression().size());
        for (BabbleParser.ExpressionContext e : block.expression()) {
            stmts.add(lowerExpr(e));
        }
        return new HirStaticBlock(
                resolution.isNamespaceScope(block),
                hirVars(resolution.declared(block)),
                stmts
        );
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

    private HirTypeRef lowerTypeRef(BabbleParser.TypeContext type, ParserRuleContext occurrence) {
        List<String> path = ScopeAnalysis.simpleTypePath(ScopeAnalysis.simpleType(type));
        return new HirTypeRef(type.getStart().getLine(), type.getText(), path, hirVar(resolution.variable(occurrence)));
    }

    // ---------------------------------------------------------------- Pass 2: expressions

    private ExprMeta metaOf(ParserRuleContext ctx) {
        return metaOf(ctx, EffectSet.EMPTY);
    }

    private ExprMeta metaOf(ParserRuleContext ctx, EffectSet effects) {
        PrimitiveKind kind = scopes.lowering().expressionKind(ctx);
        boolean imm = kind.isPrimitive() || scopes.lowering().isExpressionDeeplyImmutable(ctx);
        boolean str = scopes.lowering().isExpressionString(ctx);
        return new ExprMeta(
                ctx.getStart().getLine(),
                kind,
                scopes.lowering().numericWideningRank(ctx),
                scopes.lowering().checkedType(ctx),
                effects,
                imm,
                str
        );
    }

    private HirExpr lowerExpr(BabbleParser.ExpressionContext ctx) {
        HirExpr expr = lowerExprCore(ctx);
        LoweringTargets.OverloadSelection sel = scopes.calls().overloadSelection(ctx);
        if (sel != null) {
            expr = new HirExpr.OverloadSelect(expr, sel.index(), sel.signatureKey(), sel.exactSet(),
                    ExprMeta.of(ctx.getStart().getLine(), PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.DefExpressionContext
                || ctx instanceof BabbleParser.AssignExpressionContext
                || ctx instanceof BabbleParser.ReturnExpressionContext) {
            return expr;
        }
        return wrapJavaSam(expr, ctx);
    }

    private HirExpr wrapJavaSam(HirExpr expr, ParserRuleContext ctx) {
        if (expr == null) {
            return null;
        }
        LoweringTargets.JavaSamTarget sam = scopes.lowering().javaSamTarget(ctx);
        if (sam == null) {
            return expr;
        }
        int ln = expr.meta().line() > 0 ? expr.meta().line() : ctx.getStart().getLine();
        return new HirExpr.JavaSamAdapt(expr, sam, ExprMeta.of(ln, PrimitiveKind.NONE));
    }

    private HirExpr lowerExprCore(BabbleParser.ExpressionContext ctx) {
        if (ctx instanceof BabbleParser.NullContext) {
            return new HirExpr.Const(null, metaOf(ctx).withKind(PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.BooleanContext b) {
            return new HirExpr.Const(Boolean.parseBoolean(b.getText()),
                    new ExprMeta(b.getStart().getLine(), PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(b), scopes.lowering().checkedType(b), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.IntegerContext i) {
            HirExpr resolved = lowerResolvedLiteral(i);
            if (resolved != null) return resolved;
            return lowerIntLiteral(i, i.getText());
        }
        if (ctx instanceof BabbleParser.LongLiteralContext l) {
            HirExpr resolved = lowerResolvedLiteral(l);
            if (resolved != null) return resolved;
            String text = l.getText();
            return new HirExpr.Const(Long.parseLong(text.substring(0, text.length() - 1)),
                    new ExprMeta(l.getStart().getLine(), PrimitiveKind.LONG,
                            scopes.lowering().numericWideningRank(l), scopes.lowering().checkedType(l), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.FloatLiteralContext f) {
            HirExpr resolved = lowerResolvedLiteral(f);
            if (resolved != null) return resolved;
            String text = f.getText();
            return new HirExpr.Const(Float.parseFloat(text.substring(0, text.length() - 1)),
                    new ExprMeta(f.getStart().getLine(), PrimitiveKind.FLOAT,
                            scopes.lowering().numericWideningRank(f), scopes.lowering().checkedType(f), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.DoubleContext d) {
            HirExpr resolved = lowerResolvedLiteral(d);
            if (resolved != null) return resolved;
            return new HirExpr.Const(Double.parseDouble(d.getText()),
                    new ExprMeta(d.getStart().getLine(), PrimitiveKind.DOUBLE,
                            scopes.lowering().numericWideningRank(d), scopes.lowering().checkedType(d), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.CharLiteralContext c) {
            return new HirExpr.Const(StringLiterals.decodeChar(c.CHAR_LIT().getText()),
                    new ExprMeta(c.getStart().getLine(), PrimitiveKind.CHAR,
                            scopes.lowering().numericWideningRank(c), scopes.lowering().checkedType(c), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.StringContext s) {
            return new HirExpr.Const(org.babblelang.parser.StringLiterals.decode(s),
                    new ExprMeta(s.getStart().getLine(), PrimitiveKind.NONE,
                            scopes.lowering().numericWideningRank(s), scopes.lowering().checkedType(s), EffectSet.EMPTY, true, true));
        }
        if (ctx instanceof BabbleParser.NegateContext neg) {
            HirExpr resolved = lowerResolvedLiteral(neg);
            if (resolved != null) return resolved;
            if (neg.operand instanceof BabbleParser.IntegerContext lit) {
                return lowerIntLiteral(neg, "-" + lit.getText());
            }
            CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(neg);
            if (ovDispatch != null) {
                return lowerOperatorOverload(neg, neg.operand, neg.operand.getText(), Operators.NEGATE, null, null, ovDispatch);
            }
            return new HirExpr.Unary(HirExpr.UnaryOp.NEGATE, lowerExpr(neg.operand), neg.operand.getText(), metaOf(neg));
        }
        if (ctx instanceof BabbleParser.BooleanNotContext not) {
            return new HirExpr.Unary(HirExpr.UnaryOp.NOT, lowerExpr(not.expression()), not.expression().getText(),
                    new ExprMeta(not.getStart().getLine(), PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(not), scopes.lowering().checkedType(not), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.BinaryOpContext bin) {
            CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(bin);
            if (ovDispatch != null) {
                return lowerOperatorOverload(bin, bin.left, bin.left.getText(), Operators.method(bin.op.getText()),
                        bin.right, bin.right.getText(), ovDispatch);
            }
            HirExpr.BinaryOp op = switch (bin.op.getType()) {
                case BabbleLexer.PLUS -> HirExpr.BinaryOp.ADD;
                case BabbleLexer.MINUS -> HirExpr.BinaryOp.SUB;
                case BabbleLexer.MUL -> HirExpr.BinaryOp.MUL;
                case BabbleLexer.DIV -> HirExpr.BinaryOp.DIV;
                case BabbleLexer.LT -> HirExpr.BinaryOp.LT;
                case BabbleLexer.LTE -> HirExpr.BinaryOp.LTE;
                case BabbleLexer.EQ -> HirExpr.BinaryOp.EQ;
                case BabbleLexer.NEQ -> HirExpr.BinaryOp.NEQ;
                case BabbleLexer.GTE -> HirExpr.BinaryOp.GTE;
                case BabbleLexer.GT -> HirExpr.BinaryOp.GT;
                default -> throw new IllegalStateException("Bad op : " + bin.op.getText());
            };
            return new HirExpr.Binary(op, lowerExpr(bin.left), lowerExpr(bin.right),
                    bin.left.getText(), bin.right.getText(), metaOf(bin));
        }
        if (ctx instanceof BabbleParser.BooleanOpContext boolOp) {
            HirExpr.LogicOp op = boolOp.op.getType() == BabbleLexer.OR ? HirExpr.LogicOp.OR : HirExpr.LogicOp.AND;
            return new HirExpr.Logic(op, lowerExpr(boolOp.left), lowerExpr(boolOp.right),
                    new ExprMeta(boolOp.getStart().getLine(), PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(boolOp), scopes.lowering().checkedType(boolOp), EffectSet.EMPTY, true, false));
        }
        if (ctx instanceof BabbleParser.ThisExpressionContext thisCtx) {
            return new HirExpr.ThisRef(hirVar(resolution.variable(thisCtx)), thisCompiledClassIdAt(thisCtx), metaOf(thisCtx));
        }
        if (ctx instanceof BabbleParser.RecurseContext rec) {
            return new HirExpr.RecurseRef(hirVar(resolution.variable(rec)), metaOf(rec));
        }
        if (ctx instanceof BabbleParser.CoreScalarRefContext ref) {
            Resolution.Variable v = resolution.variable(ref);
            if (v != null) {
                return new HirExpr.VarRead(hirVar(v), metaOf(ref));
            }
            return new HirExpr.NameRead(ref.coreScalarKeyword().getText(), metaOf(ref));
        }
        if (ctx instanceof BabbleParser.CoreCollectionContext col) {
            return new HirExpr.CoreCollectionRef(col.coreCollectionKeyword().getText(), metaOf(col));
        }
        if (ctx instanceof BabbleParser.TypeApplyContext ta) {
            return new HirExpr.TypeApply(lowerExpr(ta.expression()), metaOf(ta));
        }
        if (ctx instanceof BabbleParser.NonNullAssertionContext nn) {
            return new HirExpr.NonNull(lowerExpr(nn.expression()), scopes.lowering().nonNullAssertionTarget(nn), metaOf(nn));
        }
        if (ctx instanceof BabbleParser.IsExpressionContext isCtx) {
            return new HirExpr.Is(lowerExpr(isCtx.left), scopes.lowering().isBuiltin(isCtx), scopes.lowering().isTarget(isCtx),
                    scopes.lowering().isIntersectionTargets(isCtx), scopes.lowering().checkedType(isCtx), metaOf(isCtx));
        }
        if (ctx instanceof BabbleParser.TypeAliasExpressionContext alias) {
            // A type alias (BIP023) only stands for a type : its def's value, which nothing can read, is null.
            return new HirExpr.Const(null, metaOf(alias).withKind(PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.AxiomExpressionContext axiom) {
            // An axiom (BIP019) is a compile-time assumption : it declares nothing at runtime and evaluates to null.
            return new HirExpr.Const(null, metaOf(axiom).withKind(PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
            return new HirExpr.As(lowerExpr(asCtx.left), scopes.lowering().asTarget(asCtx), scopes.lowering().asSamTarget(asCtx),
                    scopes.lowering().checkedType(asCtx), metaOf(asCtx));
        }
        if (ctx instanceof BabbleParser.BlockExpressionContext blkExpr) {
            return lowerBlock(blkExpr.block(), blkExpr);
        }
        if (ctx instanceof BabbleParser.IfExpressionContext ifWrap) {
            return lowerIfExpr(ifWrap.ifExpr(), ifWrap);
        }
        if (ctx instanceof BabbleParser.WhileExpressionContext w) {
            return new HirExpr.While(lowerExpr(w.test), lowerBlock(w.whileBlock, null), metaOf(w));
        }
        if (ctx instanceof BabbleParser.ForExpressionContext f) {
            List<HirExpr> body = new ArrayList<>(f.forBlock.expression().size());
            for (BabbleParser.ExpressionContext e : f.forBlock.expression()) {
                body.add(lowerExpr(e));
            }
            if (!body.isEmpty()) {
                int last = body.size() - 1;
                body.set(last, wrapJavaSam(body.get(last), f.forBlock));
            }
            return new HirExpr.For(
                    lowerExpr(f.iterable),
                    scopes.lowering().forLoopTarget(f),
                    hirVar(resolution.variable(f)),
                    hirVars(resolution.declared(f.forBlock)),
                    body,
                    scopes.lowering().numericWideningRank(f.forBlock),
                    scopes.lowering().forElemSamTarget(f),
                    metaOf(f, scopes.effects().forEffects(f))
            );
        }
        if (ctx instanceof BabbleParser.BreakExpressionContext b) {
            return new HirExpr.Break(b.expression() != null ? lowerExpr(b.expression()) : null, metaOf(b));
        }
        if (ctx instanceof BabbleParser.ContinueExpressionContext c) {
            return new HirExpr.Continue(metaOf(c));
        }
        if (ctx instanceof BabbleParser.ReturnExpressionContext r) {
            return new HirExpr.Return(wrapJavaSam(lowerExpr(r.expression()), r), metaOf(r));
        }
        if (ctx instanceof BabbleParser.ForeverExpressionContext f) {
            // An acknowledgement for the analysis only (BIP024) : the expression itself.
            return lowerExpr(f.expression());
        }
        if (ctx instanceof BabbleParser.DefExpressionContext def) {
            return new HirExpr.Def(
                    def.name.getText(),
                    scopes.lowering().defTarget(def),
                    hirVar(resolution.variable(def)),
                    def.value != null ? wrapJavaSam(lowerExpr(def.value), def) : null,
                    metaOf(def)
            );
        }
        if (ctx instanceof BabbleParser.SelectorContext sel) {
            return lowerSelector(sel);
        }
        if (ctx instanceof BabbleParser.AssignExpressionContext assign) {
            return lowerAssign(assign);
        }
        if (ctx instanceof BabbleParser.CallContext call) {
            return lowerCall(call);
        }
        if (ctx instanceof BabbleParser.FunctionLiteralContext fn) {
            return new HirExpr.FunctionRef(functionIds.get(fn), metaOf(fn, scopes.effects().callableEffects(fn)));
        }
        if (ctx instanceof BabbleParser.OverloadExpressionContext ov) {
            List<HirExpr> operands = new ArrayList<>(ov.expression().size());
            for (BabbleParser.ExpressionContext op : ov.expression()) {
                operands.add(lowerExpr(op));
            }
            List<LoweringTargets.OverloadAltMetaInfo> metas = scopes.calls().overloadMetas(ov);
            return new HirExpr.MakeOverloadSet(operands, metas != null ? metas : List.of(), metaOf(ov));
        }
        if (ctx instanceof BabbleParser.ClassExpressionContext cls) {
            return new HirExpr.ClassExpr(classIds.get(cls), metaOf(cls));
        }
        if (ctx instanceof BabbleParser.ObjectExpressionContext obj) {
            return new HirExpr.ClassExpr(classIds.get(obj), metaOf(obj));
        }
        if (ctx instanceof BabbleParser.InterfaceExpressionContext iface) {
            return new HirExpr.InterfaceExpr(interfaceIds.get(iface), metaOf(iface));
        }
        if (ctx instanceof BabbleParser.SuperExpressionContext) {
            throw new IllegalStateException("super must be called directly");
        }
        throw new IllegalStateException("Unsupported expression in HirBuilder : " + ctx.getClass().getSimpleName());
    }

    private HirExpr lowerResolvedLiteral(ParserRuleContext ctx) {
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
        if (resolved == null) {
            return null;
        }
        PrimitiveKind pk;
        if (resolved instanceof Byte) pk = PrimitiveKind.BYTE;
        else if (resolved instanceof Short) pk = PrimitiveKind.SHORT;
        else if (resolved instanceof Integer) pk = PrimitiveKind.INT;
        else if (resolved instanceof Long) pk = PrimitiveKind.LONG;
        else if (resolved instanceof Float) pk = PrimitiveKind.FLOAT;
        else if (resolved instanceof Double) pk = PrimitiveKind.DOUBLE;
        else return null;
        return new HirExpr.Const(resolved,
                new ExprMeta(ctx.getStart().getLine(), pk, scopes.lowering().numericWideningRank(ctx),
                        scopes.lowering().checkedType(ctx), EffectSet.EMPTY, true, false));
    }

    private HirExpr lowerIntLiteral(ParserRuleContext ctx, String text) {
        ExprMeta meta = new ExprMeta(ctx.getStart().getLine(), PrimitiveKind.INT,
                scopes.lowering().numericWideningRank(ctx), scopes.lowering().checkedType(ctx), EffectSet.EMPTY, true, false);
        try {
            return new HirExpr.Const(Integer.parseInt(text), meta);
        } catch (NumberFormatException e) {
            return new HirExpr.IntParseFallback(text, meta);
        }
    }

    private HirExpr lowerBlock(BabbleParser.BlockContext block, BabbleParser.BlockExpressionContext outer) {
        List<HirExpr> stmts = new ArrayList<>(block.expression().size());
        for (BabbleParser.ExpressionContext e : block.expression()) {
            stmts.add(lowerExpr(e));
        }
        List<HirVar> declVars = hirVars(resolution.declared(block));
        boolean opensNs = declVars.isEmpty() && scopes.opensScope(block);
        String[] declNames = opensNs ? scopes.declared(block) : new String[0];
        if (outer != null) {
            return new HirExpr.Block(stmts, opensNs, declNames, declVars,
                    scopes.lowering().numericWideningRank(block), metaOf(outer));
        }
        return wrapJavaSam(new HirExpr.Block(stmts, opensNs, declNames, declVars, -1, metaOf(block)), block);
    }

    private HirExpr lowerIfExpr(BabbleParser.IfExprContext ctx, BabbleParser.IfExpressionContext outer) {
        HirExpr cond = lowerExpr(ctx.test);
        HirExpr thenBr = lowerBlock(ctx.thenBlock, null);
        HirExpr elseBr = null;
        if (ctx.elseBlock != null) {
            elseBr = lowerBlock(ctx.elseBlock, null);
        } else if (ctx.elseIf != null) {
            elseBr = lowerIfExpr(ctx.elseIf, null);
        }
        // Notice: CodeGenerator.visitIfExpr uses scopes.lowering().expressionKind(ctx) and caller widens outer (ifWrap) or ctx.
        ParserRuleContext metaCtx = outer != null ? outer : ctx;
        int widening = scopes.lowering().numericWideningRank(metaCtx);
        if (widening < 0 && outer != null) {
            widening = scopes.lowering().numericWideningRank(ctx);
        }
        ExprMeta meta = new ExprMeta(
                ctx.getStart().getLine(),
                scopes.lowering().expressionKind(ctx),
                widening,
                scopes.lowering().checkedType(metaCtx),
                EffectSet.EMPTY,
                scopes.lowering().expressionKind(ctx).isPrimitive() || scopes.lowering().isExpressionDeeplyImmutable(metaCtx),
                scopes.lowering().isExpressionString(metaCtx)
        );
        HirExpr.If ifNode = new HirExpr.If(cond, thenBr, elseBr, meta);
        return outer == null ? wrapJavaSam(ifNode, ctx) : ifNode;
    }

    private HirExpr lowerSelector(BabbleParser.SelectorContext ctx) {
        String name = ScopeAnalysis.selectorName(ctx);
        BabbleParser.ExpressionContext receiver = ctx.expression();
        if (receiver == null) {
            Resolution.Variable v = resolution.variable(ctx);
            if (v != null) {
                return new HirExpr.VarRead(hirVar(v), metaOf(ctx));
            }
            return new HirExpr.NameRead(name, metaOf(ctx));
        }
        if (ctx.safe != null) {
            return new HirExpr.DynamicSelect(lowerExpr(receiver), receiver.getText(), name, true, metaOf(ctx));
        }
        LoweringTargets.JavaFieldTarget javaField = scopes.lowering().javaFieldTarget(ctx);
        if (javaField != null) {
            return new HirExpr.JavaFieldGet(lowerExpr(receiver), receiver.getText(),
                    javaField.isStatic() && isPureStaticJavaChain(receiver), javaField, metaOf(ctx));
        }
        LoweringTargets.ModuleExportTarget exportTarget = scopes.lowering().moduleExportTarget(ctx);
        if (exportTarget != null) {
            return new HirExpr.ModuleExportGet(lowerExpr(receiver), exportTarget, metaOf(ctx));
        }
        if (receiver instanceof BabbleParser.ThisExpressionContext thisExpr) {
            int clsId = thisCompiledClassIdAt(thisExpr);
            int fieldIdx = compiledFieldIndex(thisExpr, name);
            return new HirExpr.ThisFieldGet(lowerExpr(receiver), clsId, fieldIdx, name, metaOf(ctx));
        }
        return new HirExpr.DynamicSelect(lowerExpr(receiver), receiver.getText(), name, false, metaOf(ctx));
    }

    private HirExpr lowerAssign(BabbleParser.AssignExpressionContext ctx) {
        String operator = Operators.compound(ctx.op.getText());
        String name = ScopeAnalysis.assignName(ctx);
        BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(ctx);
        String targetText = SyntaxText.targetText(ctx);
        String valueText = ctx.value.getText();
        HirExpr value = wrapJavaSam(lowerExpr(ctx.value), ctx);
        ExprMeta meta = metaOf(ctx);

        if (recv == null) {
            Resolution.Variable v = resolution.variable(ctx);
            if (v != null) {
                return new HirExpr.VarAssign(hirVar(v), operator, value, targetText, valueText, meta);
            }
            return new HirExpr.NameAssign(name, operator, value, targetText, valueText, meta);
        }
        if (recv instanceof BabbleParser.ThisExpressionContext thisExpr) {
            int clsId = thisCompiledClassIdAt(thisExpr);
            int fieldIdx = compiledFieldIndex(thisExpr, name);
            // Value classes / records are read-only; keep fallback to Members.assignOwn just like CodeGenerator.
            if (clsId >= 0) {
                ParserRuleContext clsCtx = findThisCompiledClassCtx(thisExpr);
                if (clsCtx != null && scopes.hierarchy().shape(clsCtx).isValue()) {
                    fieldIdx = -1;
                }
            }
            return new HirExpr.ThisFieldAssign(lowerExpr(recv), clsId, fieldIdx, name, operator, value, targetText, valueText, meta);
        }
        LoweringTargets.JavaFieldTarget javaField = scopes.lowering().javaFieldTarget(ctx);
        if (javaField != null) {
            return new HirExpr.JavaFieldAssign(lowerExpr(recv), recv.getText(),
                    javaField.isStatic() && isPureStaticJavaChain(recv), javaField, operator, value, targetText, valueText, meta);
        }
        return new HirExpr.DynamicFieldAssign(lowerExpr(recv), recv.getText(), name, operator, value, targetText, valueText, meta);
    }

    private HirExpr lowerCall(BabbleParser.CallContext ctx) {
        EffectSet callFx = scopes.effects().callEffects(ctx);
        ExprMeta meta = metaOf(ctx, callFx);
        List<BabbleParser.CallParameterContext> params = ctx.callParameters().callParameter();
        String[] names = SyntaxText.callNames(ctx);
        LoweringTargets.ArgumentBinding binding = scopes.calls().argumentBinding(ctx);
        LoweringTargets.SpreadAdapt[] spreadAdapts = scopes.calls().callSpreadAdapts(ctx);

        // 0. Self tail call : a jump back to the start of the function (BIP024)
        if (scopes.calls().isSelfTailCall(ctx)) {
            if (currentFunction == null) {
                throw new IllegalStateException("Self tail call outside of a function at line " + ctx.getStart().getLine());
            }
            // The analysis never marks a call with spread arguments (BIP024-D4).
            return new HirExpr.SelfTailCall(functionIds.get(currentFunction), lowerCallArgs(params, null), binding, meta);
        }

        // 1. Safe call recv?.m(args)
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector && selector.safe != null) {
            return new HirExpr.SafeCall(
                    lowerExpr(selector.expression()),
                    selector.expression().getText(),
                    ScopeAnalysis.selectorName(selector),
                    selector.getStart().getLine(),
                    scopes.lowering().checkedType(selector),
                    lowerCallArgs(params, spreadAdapts),
                    binding,
                    (binding != null || names == null) ? scopes.calls().babbleCallSignature(ctx) : null,
                    SyntaxText.callSite(ctx, sourceName),
                    meta
            );
        }

        // 2. Array creation or array protocol operation (BIP020-I3)
        LoweringTargets.ArrayCreationTarget arrCreate = scopes.lowering().arrayCreationTarget(ctx);
        if (arrCreate != null) {
            String compInternalName;
            boolean compIsJavaIface;
            if (arrCreate.elemKind().isPrimitive()) {
                compInternalName = null;
                compIsJavaIface = false;
            } else if (arrCreate.babbleTypeId() != null) {
                String base = resolveBabbleJvmName(arrCreate.babbleTypeId());
                compInternalName = arrCreate.nestedDims() > 0
                        ? "[".repeat(arrCreate.nestedDims()) + "L" + base + ";"
                        : base;
                compIsJavaIface = false;
            } else {
                Class<?> comp = arrCreate.componentClass() != null ? arrCreate.componentClass() : Object.class;
                compInternalName = Type.getInternalName(comp);
                compIsJavaIface = comp.isInterface();
            }
            return new HirExpr.ArrayCreate(
                    arrCreate.kind(),
                    arrCreate.elemKind(),
                    compInternalName,
                    compIsJavaIface,
                    arrCreate.componentClass(),
                    lowerPositionalArgs(params, spreadAdapts),
                    meta
            );
        }
        LoweringTargets.ArrayOpTarget arrOp = scopes.lowering().arrayOpTarget(ctx);
        if (arrOp != null) {
            BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
            return new HirExpr.ArrayOp(
                    arrOp.kind(),
                    lowerExpr(selector.expression()),
                    selector.expression().getText(),
                    arrOp.elemKind(),
                    arrOp.arrayClass(),
                    arrOp.componentClass(),
                    lowerPositionalArgs(params, spreadAdapts),
                    meta
            );
        }

        // 3. Core collection constructor
        BabbleParser.ExpressionContext directCallee = ctx.expression() instanceof BabbleParser.TypeApplyContext ta
                ? ta.expression() : ctx.expression();
        if (directCallee instanceof BabbleParser.CoreCollectionContext cc) {
            return new HirExpr.CoreCollectionCall(cc.coreCollectionKeyword().getText(), lowerCallArgs(params, spreadAdapts), meta);
        }

        // 3. Super call super.m(args)
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.SuperExpressionContext superExpr) {
            LoweringTargets.SuperTargetInfo info = (binding != null || names == null) ? scopes.hierarchy().superTargetInfo(superExpr) : null;
            int ownerClsId = -1;
            if (info != null && !info.ownerIsInterface()) {
                ParserRuleContext ownerClsCtx = findEnclosingOwnerClassCtx(superExpr, info.ownerTypeId());
                if (ownerClsCtx != null && compiledClassJvmNames.containsKey(ownerClsCtx)) {
                    ownerClsId = classIds.get(ownerClsCtx);
                }
            }
            return new HirExpr.SuperCall(
                    hirVar(resolution.variable(selector)),
                    ownerClsId,
                    ScopeAnalysis.selectorName(selector),
                    info,
                    lowerTypeRef(scopes.hierarchy().superTarget(superExpr), superExpr),
                    lowerCallArgs(params, spreadAdapts),
                    binding,
                    SyntaxText.callSite(ctx, sourceName),
                    meta
            );
        }

        // 4. Direct self-recursion via recurse(...)
        if (ctx.expression() instanceof BabbleParser.RecurseContext recurseCtx && (binding != null || names == null) && currentFunction != null) {
            Resolution.Variable v = resolution.variable(recurseCtx);
            if (v != null && v.method() == currentFunction) {
                int count = scopes.calls().parameters(currentFunction).length;
                boolean spec = hasSpec(currentFunction);
                boolean spread = isSpread(currentFunction);
                boolean arityOk = binding != null
                        ? binding.paramCount() == count
                        : ((spec && params.size() == count) || (spread && params.size() <= count));
                if (arityOk && (spec || spread)) {
                    return new HirExpr.SelfRecurseCall(functionIds.get(currentFunction), lowerPositionalArgs(params, spreadAdapts), binding, meta);
                }
            }
        }

        // 4b. Overload set call (resolved directly or via union split, BIP021-I5/I6)
        CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(ctx);
        if (ovDispatch != null) {
            if (!ovDispatch.isSplit()) {
                // Overloaded Babble interface method call -> INVOKEINTERFACE bm$<member>$<k> (BIP021-D13).
                LoweringTargets.InterfaceCallTarget ovIfaceCall = (binding != null || names == null) ? scopes.lowering().interfaceCallTarget(ctx) : null;
                if (ovIfaceCall != null) {
                    String ifaceJvm = allInterfaceJvmNames.get(ovIfaceCall.interfaceTypeId());
                    LoweringTargets.FunctionSignature ifaceSig = ovIfaceCall.signature();
                    if (ifaceJvm != null && ifaceSig != null
                            && (binding != null ? binding.paramCount() == ifaceSig.parameterKinds().length : params.size() == ifaceSig.parameterKinds().length)) {
                        BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
                        return new HirExpr.InterfaceCall(
                                lowerExpr(selector.expression()),
                                selector.expression().getText(),
                                selector.getStart().getLine(),
                                ovIfaceCall.interfaceTypeId(),
                                ovIfaceCall.memberName(),
                                ifaceSig,
                                lowerPositionalArgs(params, spreadAdapts),
                                binding,
                                meta
                        );
                    }
                }
                CallMetadata.OverloadCallTarget target = ovDispatch.direct();
                BabbleParser.FunctionLiteralContext directFn = target.directTarget();
                int directOwnerCls = directFn != null ? methodOwnerClassIds.getOrDefault(directFn, -1) : -1;
                if (directOwnerCls >= 0 && ctx.expression() instanceof BabbleParser.SelectorContext sel
                        && sel.expression() != null) {
                    String recvText = sel.expression() instanceof BabbleParser.ThisExpressionContext ? null : sel.expression().getText();
                    return new HirExpr.DirectThisMethodCall(
                            lowerExpr(sel.expression()),
                            recvText,
                            directOwnerCls,
                            ScopeAnalysis.selectorName(sel),
                            target.altIndex(),
                            functionIds.get(directFn),
                            lowerCallArgs(params, spreadAdapts),
                            target.binding(),
                            SyntaxText.callSite(ctx, sourceName),
                            meta
                    );
                }
                HirExpr selectedCallee = new HirExpr.OverloadSelect(
                        lowerExpr(ctx.expression()),
                        target.altIndex(),
                        target.signatureKey(),
                        target.exactSet(),
                        ExprMeta.of(ctx.getStart().getLine(), PrimitiveKind.NONE));
                if (directFn != null && !isMemberMethod(directFn) && target.binding() != null
                        && target.binding().paramCount() == scopes.calls().parameters(directFn).length
                        && (hasSpec(directFn) || isSpread(directFn))) {
                    return new HirExpr.DirectBabbleCall(
                            selectedCallee,
                            functionIds.get(directFn),
                            lowerPositionalArgs(params, spreadAdapts),
                            target.binding(),
                            meta);
                }
                return new HirExpr.DynamicCall(
                        selectedCallee,
                        lowerCallArgs(params, spreadAdapts),
                        target.binding(),
                        target.signature(),
                        SyntaxText.callSite(ctx, sourceName),
                        meta);
            }
            List<HirExpr.OverloadBranch> hirBranches = new ArrayList<>(ovDispatch.splitBranches().size());
            for (CallMetadata.OverloadSplitBranch b : ovDispatch.splitBranches()) {
                CallMetadata.OverloadCallTarget bt = b.target();
                int directFnId = -1;
                if (bt.directTarget() != null && !isMemberMethod(bt.directTarget())
                        && bt.binding() != null
                        && bt.binding().paramCount() == scopes.calls().parameters(bt.directTarget()).length
                        && (hasSpec(bt.directTarget()) || isSpread(bt.directTarget()))) {
                    directFnId = functionIds.get(bt.directTarget());
                }
                hirBranches.add(new HirExpr.OverloadBranch(
                        b.checks(), directFnId, bt.altIndex(), bt.signatureKey(), bt.exactSet(),
                        bt.signature(), bt.binding(), b.resultKind()));
            }
            return new HirExpr.OverloadDispatch(
                    lowerExpr(ctx.expression()),
                    ctx.expression().getText(),
                    SyntaxText.callSite(ctx, sourceName),
                    lowerPositionalArgs(params, null),
                    hirBranches,
                    meta);
        }

        // 5. Direct free Babble function call
        BabbleParser.FunctionLiteralContext targetFn = scopes.calls().babbleCallFunction(ctx);
        if (targetFn != null && binding != null && binding.paramCount() == scopes.calls().parameters(targetFn).length) {
            if (hasSpec(targetFn) || isSpread(targetFn)) {
                return new HirExpr.DirectBabbleCall(lowerExpr(ctx.expression()), functionIds.get(targetFn),
                        lowerPositionalArgs(params, spreadAdapts), binding, meta);
            }
        }

        // 6. Direct this.m(...) on compiled class
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.ThisExpressionContext thisExpr) {
            ParserRuleContext clsCtx = findThisCompiledClassCtx(thisExpr);
            if (clsCtx != null) {
                ClassShape shape = scopes.hierarchy().shape(clsCtx);
                String member = ScopeAnalysis.selectorName(selector);
                int index = shape.memberIndex(member);
                if (index >= 0 && shape.isMethod(index)) {
                    BabbleParser.FunctionLiteralContext methodLit = methodLiteralOf(clsCtx, member);
                    if (methodLit != null) {
                        return new HirExpr.DirectThisMethodCall(
                                lowerExpr(thisExpr),
                                classIds.get(clsCtx),
                                member,
                                functionIds.get(methodLit),
                                lowerCallArgs(params, spreadAdapts),
                                binding,
                                SyntaxText.callSite(ctx, sourceName),
                                meta
                        );
                    }
                }
            }
        }

        // 7. Static Babble JVM interface method call
        LoweringTargets.InterfaceCallTarget ifaceCall = (binding != null || names == null) ? scopes.lowering().interfaceCallTarget(ctx) : null;
        if (ifaceCall != null) {
            String ifaceJvm = allInterfaceJvmNames.get(ifaceCall.interfaceTypeId());
            LoweringTargets.FunctionSignature sig = ifaceCall.signature();
            if (ifaceJvm != null && sig != null
                    && (binding != null ? binding.paramCount() == sig.parameterKinds().length : params.size() == sig.parameterKinds().length)) {
                BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
                return new HirExpr.InterfaceCall(
                        lowerExpr(selector.expression()),
                        selector.expression().getText(),
                        selector.getStart().getLine(),
                        ifaceCall.interfaceTypeId(),
                        ifaceCall.memberName(),
                        sig,
                        lowerPositionalArgs(params, spreadAdapts),
                        binding,
                        meta
                );
            }
        }

        // 8. Direct Java constructor call
        LoweringTargets.JavaConstructorTarget javaCtor = scopes.lowering().javaConstructorTarget(ctx);
        if (javaCtor != null) {
            return new HirExpr.JavaConstructorCall(
                    lowerExpr(ctx.expression()),
                    ctx.expression().getText(),
                    isPureStaticJavaChain(ctx.expression()),
                    javaCtor,
                    lowerPositionalArgs(params, spreadAdapts),
                    meta
            );
        }

        // 9. Direct Java method call
        LoweringTargets.JavaCallTarget javaCall = scopes.lowering().javaCallTarget(ctx);
        if (javaCall != null) {
            BabbleParser.SelectorContext selector = (BabbleParser.SelectorContext) ctx.expression();
            BabbleParser.ExpressionContext recvExpr = selector.expression();
            return new HirExpr.JavaMethodCall(
                    lowerExpr(recvExpr),
                    recvExpr.getText(),
                    selector.getStart().getLine(),
                    javaCall.isStatic() && isPureStaticJavaChain(recvExpr),
                    javaCall,
                    lowerPositionalArgs(params, spreadAdapts),
                    meta
            );
        }

        // 10. General dynamic call (including typedCall/typedCallOpt indy when babbleSig != null)
        return new HirExpr.DynamicCall(
                lowerExpr(ctx.expression()),
                lowerCallArgs(params, spreadAdapts),
                binding,
                (binding != null || names == null) ? scopes.calls().babbleCallSignature(ctx) : null,
                SyntaxText.callSite(ctx, sourceName),
                meta
        );
    }

    private HirExpr wrapSpreadAdapt(HirExpr expr, LoweringTargets.SpreadAdapt[] adapts, int index, int line) {
        if (adapts != null && index < adapts.length && adapts[index] != null
                && adapts[index].kind() != LoweringTargets.SpreadKind.AS_IS) {
            return new HirExpr.SpreadAdapt(expr, adapts[index],
                    ExprMeta.of(line, PrimitiveKind.NONE));
        }
        return expr;
    }

    private List<HirCallArg> lowerCallArgs(List<BabbleParser.CallParameterContext> params,
                                           LoweringTargets.SpreadAdapt[] adapts) {
        if (params.isEmpty()) {
            return List.of();
        }
        List<HirCallArg> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            BabbleParser.CallParameterContext p = params.get(i);
            HirExpr arg = wrapSpreadAdapt(lowerExpr(p.expression()), adapts, i, p.getStart().getLine());
            result.add(new HirCallArg(ScopeAnalysis.callParamName(p), arg));
        }
        return result;
    }

    private List<HirExpr> lowerPositionalArgs(List<BabbleParser.CallParameterContext> params,
                                              LoweringTargets.SpreadAdapt[] adapts) {
        if (params.isEmpty()) {
            return List.of();
        }
        List<HirExpr> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            BabbleParser.CallParameterContext p = params.get(i);
            result.add(wrapSpreadAdapt(lowerExpr(p.expression()), adapts, i, p.getStart().getLine()));
        }
        return result;
    }

    private HirExpr lowerOperatorOverload(ParserRuleContext ctx, BabbleParser.ExpressionContext recvExpr,
                                          String recvText, String method,
                                          BabbleParser.ExpressionContext argExpr, String argText,
                                          CallMetadata.OverloadDispatchTarget ovDispatch) {
        int ln = ctx.getStart().getLine();
        CallSite site = SyntaxText.operatorSite(ctx, recvText, method, argText, sourceName);
        ExprMeta meta = metaOf(ctx);
        if (!ovDispatch.isSplit()) {
            CallMetadata.OverloadCallTarget target = ovDispatch.direct();
            BabbleParser.FunctionLiteralContext directFn = target.directTarget();
            int directOwnerCls = directFn != null ? methodOwnerClassIds.getOrDefault(directFn, -1) : -1;
            List<HirCallArg> callArgs = argExpr != null
                    ? List.of(new HirCallArg(null, lowerExpr(argExpr)))
                    : List.of();
            if (directOwnerCls >= 0) {
                String rt = recvExpr instanceof BabbleParser.ThisExpressionContext ? null : recvText;
                return new HirExpr.DirectThisMethodCall(
                        lowerExpr(recvExpr),
                        rt,
                        directOwnerCls,
                        method,
                        target.altIndex(),
                        functionIds.get(directFn),
                        callArgs,
                        null,
                        site,
                        meta
                );
            }
            HirExpr memberSelect = new HirExpr.DynamicSelect(
                    lowerExpr(recvExpr), recvText, method, false,
                    ExprMeta.of(ln, PrimitiveKind.NONE));
            HirExpr selected = new HirExpr.OverloadSelect(
                    memberSelect, target.altIndex(), target.signatureKey(), target.exactSet(),
                    ExprMeta.of(ln, PrimitiveKind.NONE));
            return new HirExpr.DynamicCall(selected, callArgs, null, target.signature(), site, meta);
        }
        List<HirExpr.OverloadBranch> hirBranches = new ArrayList<>(ovDispatch.splitBranches().size());
        for (CallMetadata.OverloadSplitBranch b : ovDispatch.splitBranches()) {
            CallMetadata.OverloadCallTarget bt = b.target();
            hirBranches.add(new HirExpr.OverloadBranch(
                    b.checks(), -1, bt.altIndex(), bt.signatureKey(), bt.exactSet(),
                    bt.signature(), null, b.resultKind()));
        }
        HirExpr memberSelect = new HirExpr.DynamicSelect(
                lowerExpr(recvExpr), recvText, method, false,
                ExprMeta.of(ln, PrimitiveKind.NONE));
        List<HirExpr> posArgs = argExpr != null ? List.of(lowerExpr(argExpr)) : List.of();
        return new HirExpr.OverloadDispatch(memberSelect, recvText + "." + method, site, posArgs, hirBranches, meta);
    }

    // ---------------------------------------------------------------- helpers

    private boolean isSpread(BabbleParser.FunctionLiteralContext literal) {
        return scopes.calls().parameters(literal).length <= HirFunction.MAX_SPREAD;
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
        boolean hasPrimitive = scopes.lowering().functionReturnKind(literal).isPrimitive();
        int slots = 3 + (opt > 0 ? 1 : 0);
        for (BabbleParser.FnParamContext decl : fnParams) {
            PrimitiveKind kind = scopes.lowering().declarationKind(decl);
            if (kind.isPrimitive()) {
                hasPrimitive = true;
            }
            slots += kind.slots();
        }
        return hasPrimitive && slots <= 250;
    }

    private ParserRuleContext findEnclosingClassCtx(ParserRuleContext at) {
        for (ParserRuleContext p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext || p instanceof BabbleParser.ObjectExpressionContext) {
                return p;
            }
        }
        return null;
    }

    private ParserRuleContext findEnclosingOwnerClassCtx(ParserRuleContext at, String ownerTypeId) {
        for (ParserRuleContext p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassShape shape = scopes.hierarchy().shape(p);
                if (shape != null && shape.id().equals(ownerTypeId)) {
                    return p;
                }
            }
        }
        return null;
    }

    private ParserRuleContext findThisCompiledClassCtx(ParserRuleContext thisExpression) {
        ParserRuleContext child = thisExpression;
        for (ParserRuleContext p = thisExpression.getParent(); p != null; child = p, p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext c) {
                if (child == c.classBlock || child == c.classParameters()) {
                    return compiledClassJvmNames.containsKey(c) ? c : null;
                }
                return null;
            }
            if (p instanceof BabbleParser.ObjectExpressionContext o) {
                if (child == o.createBlock) {
                    return compiledClassJvmNames.containsKey(o) ? o : null;
                }
                return null;
            }
            if (p instanceof BabbleParser.InterfaceExpressionContext) {
                return null;
            }
        }
        return null;
    }

    private int thisCompiledClassIdAt(ParserRuleContext thisExpression) {
        ParserRuleContext cls = findThisCompiledClassCtx(thisExpression);
        return cls != null ? classIds.get(cls) : -1;
    }

    private int compiledFieldIndex(ParserRuleContext thisExpression, String member) {
        ParserRuleContext cls = findThisCompiledClassCtx(thisExpression);
        if (cls == null) {
            return -1;
        }
        ClassShape shape = scopes.hierarchy().shape(cls);
        int index = shape.memberIndex(member);
        return index >= 0 && !shape.isMethod(index) ? index : -1;
    }

    private BabbleParser.FunctionLiteralContext methodLiteralOf(ParserRuleContext clsCtx, String member) {
        BabbleParser.BlockContext body = clsCtx instanceof BabbleParser.ClassExpressionContext c
                ? c.classBlock : ((BabbleParser.ObjectExpressionContext) clsCtx).createBlock;
        for (BabbleParser.ExpressionContext expression : body.expression()) {
            if (expression instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(member)
                    && def.value instanceof BabbleParser.FunctionLiteralContext literal) {
                return literal;
            }
        }
        throw new IllegalStateException("No method " + member);
    }

    private boolean isPureStaticJavaChain(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1
                && resolution.declared(b.block()).isEmpty()
                && !scopes.opensScope(b.block())) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.safe != null) {
                return false;
            }
            if (sel.expression() != null) {
                return isPureStaticJavaChain(sel.expression());
            }
            String name = sel.ID().getText();
            if (("java".equals(name) || "javax".equals(name)) && resolution.variable(sel) == null) {
                return true;
            }
            Resolution.Variable v = resolution.variable(sel);
            return v != null && v.declaration() != null && scopes.lowering().isDefinitelyDefined(v.declaration());
        }
        return false;
    }

    private String resolveBabbleJvmName(String typeId) {
        String iface = allInterfaceJvmNames.get(typeId);
        if (iface != null) {
            return iface;
        }
        String cls = allClassJvmNames.get(typeId);
        if (cls != null) {
            return cls;
        }
        String local = localClassJvmNamesById.get(typeId);
        if (local != null) {
            return local;
        }
        return "java/lang/Object";
    }

    private List<Class<?>> implementableJavaInterfaces(ClassShape shape) {
        if (shape == null) {
            return List.of();
        }
        LoweringTargets.TypeHierarchyInfo info = scopes.hierarchy().typeHierarchy(shape.id());
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
        LoweringTargets.TypeHierarchyInfo info = scopes.hierarchy().typeHierarchy(shape.id());
        if (info == null) {
            return List.of();
        }
        List<String> implemented = new ArrayList<>();
        for (LoweringTargets.ParentRef parent : info.directParents()) {
            if (parent.babbleTypeId() != null) {
                String ifaceJvm = allInterfaceJvmNames.get(parent.babbleTypeId());
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
        if (iface == null || !iface.isInterface() || !JavaTypes.isAccessible(iface)
                || !out.add(iface)) {
            return;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            collectImplementableJavaInterfaces(parent, out);
        }
    }

    private boolean isMemberMethod(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        if (cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
            LoweringTargets.DefTarget target = scopes.lowering().defTarget(def);
            return target != null && target.kind() != LoweringTargets.DefKind.LEXICAL;
        }
        return false;
    }
}
