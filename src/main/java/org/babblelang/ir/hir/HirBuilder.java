package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.analysis.CallMetadata;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.analysis.SyntaxText;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.parser.StringLiterals;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.Operators;

/**
 * Translates an ANTLR4 {@link BabbleParser.FileContext} plus its {@link ScopeAnalysis} and {@link
 * Resolution} side tables into a completely ANTLR-free {@link HirModule} (BIP014-I1, BIP014-D8).
 *
 * <p>Coordinates {@link HirDeclLowerer}, {@link HirCallLowerer}, and {@link HirMatchLowerer} as the
 * sole AST-to-HIR bridge; all downstream optimization passes and bytecode emitters consume {@link
 * HirModule} only.
 */
public final class HirBuilder {
    final String className;
    final String sourceName;
    final ScopeAnalysis scopes;
    final Resolution resolution;
    final HirDeclLowerer declLowerer;
    final HirCallLowerer callLowerer;
    final HirMatchLowerer matchLowerer;

    final Map<String, String> allInterfaceJvmNames = new LinkedHashMap<>();
    final Map<String, String> allClassJvmNames = new LinkedHashMap<>();
    final Map<String, String> ownInterfaceJvmNames = new LinkedHashMap<>();
    final Map<String, String> ownClassJvmNames = new LinkedHashMap<>();
    final Map<String, String> localClassJvmNamesById = new LinkedHashMap<>();
    final Map<String, Integer> localClassIdsByShapeId = new LinkedHashMap<>();

    final Map<BabbleParser.InterfaceExpressionContext, Integer> interfaceIds =
            new IdentityHashMap<>();
    final Map<ParserRuleContext, Integer> classIds = new IdentityHashMap<>();
    final Map<BabbleParser.FunctionLiteralContext, Integer> functionIds = new IdentityHashMap<>();
    final Map<ParserRuleContext, String> preassignedClassNames = new IdentityHashMap<>();
    final Map<ParserRuleContext, String> compiledClassJvmNames = new IdentityHashMap<>();
    final Map<ParserRuleContext, List<Class<?>>> classJavaInterfaces = new IdentityHashMap<>();
    final Map<ParserRuleContext, List<String>> classImplementedJvm = new IdentityHashMap<>();
    final Map<BabbleParser.FunctionLiteralContext, Integer> methodOwnerClassIds =
            new IdentityHashMap<>();
    final Map<BabbleParser.FunctionLiteralContext, String> methodMemberNames =
            new IdentityHashMap<>();
    final Map<BabbleParser.FunctionLiteralContext, String> functionJvmNames =
            new IdentityHashMap<>();
    private final Map<Resolution.Variable, HirVar> vars = new IdentityHashMap<>();

    final List<BabbleParser.InterfaceExpressionContext> orderedInterfaces = new ArrayList<>();
    final List<ParserRuleContext> orderedClasses = new ArrayList<>();
    final List<BabbleParser.FunctionLiteralContext> orderedFunctions = new ArrayList<>();
    final Set<HirExpr> fileScopeNameReads =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<HirExpr> nonCyclicImports =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<HirExpr> publicStaticDefSelects =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    int nextOwnerId = 1;
    int nextVarId = 1;
    int interfaceCount = 0;
    int classCount = 0;
    int functionCount = 0;

    BabbleParser.FunctionLiteralContext currentFunction;

    private HirBuilder(
            String className,
            String sourceName,
            ScopeAnalysis scopes,
            Map<String, String> knownInterfaceJvmNames,
            Map<String, String> knownClassJvmNames) {
        this.className = className;
        this.sourceName = sourceName;
        this.scopes = scopes;
        this.resolution = scopes.resolution();
        this.declLowerer = new HirDeclLowerer(this);
        this.callLowerer = new HirCallLowerer(this);
        this.matchLowerer = new HirMatchLowerer(this);
        if (knownInterfaceJvmNames != null) {
            this.allInterfaceJvmNames.putAll(knownInterfaceJvmNames);
        }
        if (knownClassJvmNames != null) {
            this.allClassJvmNames.putAll(knownClassJvmNames);
        }
    }

    public static HirModule build(
            String className,
            String sourceName,
            BabbleParser.FileContext file,
            ScopeAnalysis scopes,
            Map<String, String> knownInterfaceJvmNames,
            Map<String, String> knownClassJvmNames) {
        return new HirBuilder(
                        className, sourceName, scopes, knownInterfaceJvmNames, knownClassJvmNames)
                .run(file);
    }

    private HirModule run(BabbleParser.FileContext file) {
        // Pass 1: discover all interfaces, classes/objects, and function literals and assign stable
        // IDs & JVM names.
        declLowerer.discoverAll(file);

        // Pass 2: lower top-level statements, functions, classes, and interfaces to ANTLR-free HIR.
        List<HirExpr> topLevel = new ArrayList<>(file.expression().size());
        for (BabbleParser.ExpressionContext expr : file.expression()) {
            topLevel.add(lowerExpr(expr));
        }

        Map<Integer, HirFunction> hirFunctions = new LinkedHashMap<>();
        for (BabbleParser.FunctionLiteralContext fnCtx : orderedFunctions) {
            HirFunction fn = declLowerer.lowerFunction(fnCtx);
            hirFunctions.put(fn.id(), fn);
        }

        Map<Integer, HirClass> hirClasses = new LinkedHashMap<>();
        for (ParserRuleContext clsCtx : orderedClasses) {
            HirClass cls = declLowerer.lowerClass(clsCtx);
            hirClasses.put(cls.id(), cls);
        }

        Map<Integer, HirInterface> hirInterfaces = new LinkedHashMap<>();
        for (BabbleParser.InterfaceExpressionContext ifaceCtx : orderedInterfaces) {
            HirInterface iface = declLowerer.lowerInterface(ifaceCtx);
            hirInterfaces.put(iface.id(), iface);
        }

        // Pass 3: BIP034-D6 inductive non-escaping header & self-class definite-init analysis.
        HirNameInitAnalysis.annotate(
                topLevel,
                hirFunctions,
                hirClasses,
                hirInterfaces,
                fileScopeNameReads,
                nonCyclicImports,
                publicStaticDefSelects);

        String[] exports = scopes.exports();
        String[] topDeclared = scopes.declared(file);
        boolean[] topFinals = scopes.declaredFinals(file);
        Map<String, LoweringTargets.ExportedFunctionTarget> ownExportedFunctions =
                declLowerer.collectOwnExportedFunctions(
                        topLevel, hirFunctions, exports, topDeclared, topFinals);

        return new HirModule(
                className,
                sourceName,
                file.getStart().getLine(),
                exports,
                topDeclared,
                topFinals,
                topLevel,
                hirFunctions,
                hirClasses,
                hirInterfaces,
                declLowerer.collectAllTypeHierarchies(),
                allInterfaceJvmNames,
                allClassJvmNames,
                ownInterfaceJvmNames,
                ownClassJvmNames,
                ownExportedFunctions,
                nextVarId,
                nextOwnerId,
                scopes.lowering().wasmImports());
    }

    int ownerIdOf(ParserRuleContext owner) {
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

    HirVar hirVar(Resolution.Variable v) {
        if (v == null) {
            return null;
        }
        return vars.computeIfAbsent(
                v,
                rv -> {
                    ParserRuleContext decl = rv.declaration();
                    PrimitiveKind pk =
                            decl != null
                                    ? scopes.lowering().declarationKind(decl)
                                    : PrimitiveKind.NONE;
                    boolean defDef = decl != null && scopes.lowering().isDefinitelyDefined(decl);
                    boolean mutable = decl != null && ScopeAnalysis.isVar(decl);
                    return new HirVar(
                            nextVarId++,
                            rv.name(),
                            rv.kind(),
                            ownerIdOf(rv.method()),
                            pk,
                            rv.isCell(),
                            rv.isUsed(),
                            rv.mayBeUndefined(),
                            defDef,
                            mutable,
                            rv.isCapturedByValue());
                });
    }

    List<HirVar> hirVars(List<Resolution.Variable> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<HirVar> result = new ArrayList<>(list.size());
        for (Resolution.Variable v : list) {
            result.add(hirVar(v));
        }
        return result;
    }

    // ---------------------------------------------------------------- Pass 2: expressions

    ExprMeta metaOf(ParserRuleContext ctx) {
        return metaOf(ctx, EffectSet.EMPTY);
    }

    ExprMeta metaOf(ParserRuleContext ctx, EffectSet effects) {
        PrimitiveKind kind = scopes.lowering().expressionKind(ctx);
        boolean imm = kind.isPrimitive() || scopes.lowering().isExpressionDeeplyImmutable(ctx);
        boolean str = scopes.lowering().isExpressionString(ctx);
        boolean frozen = scopes.lowering().isExpressionFrozen(ctx);
        boolean unique = scopes.lowering().isExpressionUnique(ctx);
        return new ExprMeta(
                ctx.getStart().getLine(),
                kind,
                scopes.lowering().numericWideningRank(ctx),
                scopes.lowering().checkedType(ctx),
                effects,
                imm,
                str,
                frozen,
                unique);
    }

    HirExpr lowerExpr(BabbleParser.ExpressionContext ctx) {
        HirExpr expr = lowerExprCore(ctx);
        LoweringTargets.OverloadSelection sel = scopes.calls().overloadSelection(ctx);
        if (sel != null) {
            expr =
                    new HirExpr.OverloadSelect(
                            expr,
                            sel.index(),
                            sel.signatureKey(),
                            sel.exactSet(),
                            ExprMeta.of(ctx.getStart().getLine(), PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.DefExpressionContext
                || ctx instanceof BabbleParser.AssignExpressionContext
                || ctx instanceof BabbleParser.ReturnExpressionContext) {
            return expr;
        }
        return wrapJavaSam(expr, ctx);
    }

    HirExpr wrapJavaSam(HirExpr expr, ParserRuleContext ctx) {
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
            return new HirExpr.Const(
                    Boolean.parseBoolean(b.getText()),
                    new ExprMeta(
                            b.getStart().getLine(),
                            PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(b),
                            scopes.lowering().checkedType(b),
                            EffectSet.EMPTY,
                            true,
                            false));
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
            return new HirExpr.Const(
                    Long.parseLong(text.substring(0, text.length() - 1)),
                    new ExprMeta(
                            l.getStart().getLine(),
                            PrimitiveKind.LONG,
                            scopes.lowering().numericWideningRank(l),
                            scopes.lowering().checkedType(l),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.FloatLiteralContext f) {
            HirExpr resolved = lowerResolvedLiteral(f);
            if (resolved != null) return resolved;
            String text = f.getText();
            return new HirExpr.Const(
                    Float.parseFloat(text.substring(0, text.length() - 1)),
                    new ExprMeta(
                            f.getStart().getLine(),
                            PrimitiveKind.FLOAT,
                            scopes.lowering().numericWideningRank(f),
                            scopes.lowering().checkedType(f),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.DoubleContext d) {
            HirExpr resolved = lowerResolvedLiteral(d);
            if (resolved != null) return resolved;
            return new HirExpr.Const(
                    Double.parseDouble(d.getText()),
                    new ExprMeta(
                            d.getStart().getLine(),
                            PrimitiveKind.DOUBLE,
                            scopes.lowering().numericWideningRank(d),
                            scopes.lowering().checkedType(d),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.CharLiteralContext c) {
            return new HirExpr.Const(
                    StringLiterals.decodeChar(c.CHAR_LIT().getText()),
                    new ExprMeta(
                            c.getStart().getLine(),
                            PrimitiveKind.CHAR,
                            scopes.lowering().numericWideningRank(c),
                            scopes.lowering().checkedType(c),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.StringContext s) {
            return new HirExpr.Const(
                    org.babblelang.parser.StringLiterals.decode(s),
                    new ExprMeta(
                            s.getStart().getLine(),
                            PrimitiveKind.NONE,
                            scopes.lowering().numericWideningRank(s),
                            scopes.lowering().checkedType(s),
                            EffectSet.EMPTY,
                            true,
                            true));
        }
        if (ctx instanceof BabbleParser.NegateContext neg) {
            HirExpr resolved = lowerResolvedLiteral(neg);
            if (resolved != null) return resolved;
            if (neg.operand instanceof BabbleParser.IntegerContext lit) {
                return lowerIntLiteral(neg, "-" + lit.getText());
            }
            CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(neg);
            if (ovDispatch != null) {
                return callLowerer.lowerOperatorOverload(
                        neg,
                        neg.operand,
                        neg.operand.getText(),
                        Operators.NEGATE,
                        null,
                        null,
                        ovDispatch);
            }
            if (scopes.lowering().directMethodTarget(neg) != null) {
                HirExpr directOp =
                        callLowerer.tryLowerDirectMethod(
                                neg,
                                neg.operand,
                                neg.operand.getText(),
                                List.of(),
                                null,
                                SyntaxText.operatorSite(
                                        neg,
                                        neg.operand.getText(),
                                        Operators.NEGATE,
                                        null,
                                        sourceName),
                                metaOf(neg));
                if (directOp != null) {
                    return directOp;
                }
            }
            return new HirExpr.Unary(
                    HirExpr.UnaryOp.NEGATE,
                    lowerExpr(neg.operand),
                    neg.operand.getText(),
                    metaOf(neg));
        }
        if (ctx instanceof BabbleParser.BooleanNotContext not) {
            return new HirExpr.Unary(
                    HirExpr.UnaryOp.NOT,
                    lowerExpr(not.expression()),
                    not.expression().getText(),
                    new ExprMeta(
                            not.getStart().getLine(),
                            PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(not),
                            scopes.lowering().checkedType(not),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.BinaryOpContext bin) {
            CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(bin);
            if (ovDispatch != null) {
                return callLowerer.lowerOperatorOverload(
                        bin,
                        bin.left,
                        bin.left.getText(),
                        Operators.method(bin.op.getText()),
                        bin.right,
                        bin.right.getText(),
                        ovDispatch);
            }
            if (scopes.lowering().directMethodTarget(bin) != null) {
                String opMethod = Operators.method(bin.op.getText());
                if (opMethod != null) {
                    HirExpr directOp =
                            callLowerer.tryLowerDirectMethod(
                                    bin,
                                    bin.left,
                                    bin.left.getText(),
                                    List.of(new HirCallArg(null, lowerExpr(bin.right))),
                                    null,
                                    SyntaxText.operatorSite(
                                            bin,
                                            bin.left.getText(),
                                            opMethod,
                                            bin.right.getText(),
                                            sourceName),
                                    metaOf(bin));
                    if (directOp != null) {
                        return directOp;
                    }
                }
            }
            HirExpr.BinaryOp op =
                    switch (bin.op.getType()) {
                        case BabbleLexer.PLUS -> HirExpr.BinaryOp.ADD;
                        case BabbleLexer.MINUS -> HirExpr.BinaryOp.SUB;
                        case BabbleLexer.MUL -> HirExpr.BinaryOp.MUL;
                        case BabbleLexer.DIV -> HirExpr.BinaryOp.DIV;
                        case BabbleLexer.FLOOR_DIV -> HirExpr.BinaryOp.FLOOR_DIV;
                        case BabbleLexer.MOD -> HirExpr.BinaryOp.MOD;
                        case BabbleLexer.LT -> HirExpr.BinaryOp.LT;
                        case BabbleLexer.LTE -> HirExpr.BinaryOp.LTE;
                        case BabbleLexer.EQ -> HirExpr.BinaryOp.EQ;
                        case BabbleLexer.NEQ -> HirExpr.BinaryOp.NEQ;
                        case BabbleLexer.GTE -> HirExpr.BinaryOp.GTE;
                        case BabbleLexer.GT -> HirExpr.BinaryOp.GT;
                        default -> throw new IllegalStateException("Bad op : " + bin.op.getText());
                    };
            return new HirExpr.Binary(
                    op,
                    lowerExpr(bin.left),
                    lowerExpr(bin.right),
                    bin.left.getText(),
                    bin.right.getText(),
                    metaOf(bin));
        }
        if (ctx instanceof BabbleParser.RangeExpressionContext range) {
            boolean inclusive = range.op.getType() == BabbleLexer.RANGE;
            LoweringTargets.RangeTarget rt = scopes.lowering().rangeTarget(range);
            PrimitiveKind elemKind = rt != null ? rt.elemKind() : PrimitiveKind.NONE;
            boolean stringElem = rt != null && rt.stringElement();
            return new HirExpr.Range(
                    lowerExpr(range.left),
                    lowerExpr(range.right),
                    inclusive,
                    elemKind,
                    stringElem,
                    metaOf(range));
        }
        if (ctx instanceof BabbleParser.BooleanOpContext boolOp) {
            HirExpr.LogicOp op =
                    boolOp.op.getType() == BabbleLexer.OR
                            ? HirExpr.LogicOp.OR
                            : HirExpr.LogicOp.AND;
            return new HirExpr.Logic(
                    op,
                    lowerExpr(boolOp.left),
                    lowerExpr(boolOp.right),
                    scopes.lowering().isLogicRightNarrowed(boolOp),
                    new ExprMeta(
                            boolOp.getStart().getLine(),
                            PrimitiveKind.BOOL,
                            scopes.lowering().numericWideningRank(boolOp),
                            scopes.lowering().checkedType(boolOp),
                            EffectSet.EMPTY,
                            true,
                            false));
        }
        if (ctx instanceof BabbleParser.ThisExpressionContext thisCtx) {
            return new HirExpr.ThisRef(
                    hirVar(resolution.variable(thisCtx)),
                    thisCompiledClassIdAt(thisCtx),
                    metaOf(thisCtx));
        }
        if (ctx instanceof BabbleParser.RecurseContext rec) {
            return new HirExpr.RecurseRef(hirVar(resolution.variable(rec)), metaOf(rec));
        }
        if (ctx instanceof BabbleParser.CoreScalarRefContext ref) {
            Resolution.Variable v = resolution.variable(ref);
            if (v != null) {
                return new HirExpr.VarRead(hirVar(v), metaOf(ref));
            }
            return makeNameRead(ref, ref.coreScalarKeyword().getText());
        }
        if (ctx instanceof BabbleParser.CoreCollectionContext col) {
            return new HirExpr.CoreCollectionRef(
                    col.coreCollectionKeyword().getText(), metaOf(col));
        }
        if (ctx instanceof BabbleParser.CoreExceptionRefContext exc) {
            return new HirExpr.CoreExceptionRef(metaOf(exc));
        }
        if (ctx instanceof BabbleParser.TypeApplyContext ta) {
            return new HirExpr.TypeApply(lowerExpr(ta.expression()), metaOf(ta));
        }
        if (ctx instanceof BabbleParser.NonNullAssertionContext nn) {
            return new HirExpr.NonNull(
                    lowerExpr(nn.expression()),
                    scopes.lowering().nonNullAssertionTarget(nn),
                    metaOf(nn));
        }
        if (ctx instanceof BabbleParser.IsExpressionContext isCtx) {
            return new HirExpr.Is(
                    lowerExpr(isCtx.left),
                    scopes.lowering().isBuiltin(isCtx),
                    scopes.lowering().isTarget(isCtx),
                    scopes.lowering().isIntersectionTargets(isCtx),
                    scopes.lowering().checkedType(isCtx),
                    metaOf(isCtx));
        }
        if (ctx instanceof BabbleParser.TypeAliasExpressionContext alias) {
            // A type alias (BIP023) only stands for a type : its def's value, which nothing can
            // read, is null.
            return new HirExpr.Const(null, metaOf(alias).withKind(PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.AxiomExpressionContext axiom) {
            // An axiom (BIP019) is a compile-time assumption : it declares nothing at runtime and
            // evaluates to null.
            return new HirExpr.Const(null, metaOf(axiom).withKind(PrimitiveKind.NONE));
        }
        if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
            return new HirExpr.As(
                    lowerExpr(asCtx.left),
                    scopes.lowering().asTarget(asCtx),
                    scopes.lowering().asSamTarget(asCtx),
                    scopes.lowering().checkedType(asCtx),
                    metaOf(asCtx));
        }
        if (ctx instanceof BabbleParser.BlockExpressionContext blkExpr) {
            return lowerBlock(blkExpr.block(), blkExpr);
        }
        if (ctx instanceof BabbleParser.IfExpressionContext ifWrap) {
            return lowerIfExpr(ifWrap.ifExpr(), ifWrap);
        }
        if (ctx instanceof BabbleParser.MatchExpressionContext m) {
            return matchLowerer.lowerMatch(m);
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
            LoweringTargets.ForLoopTarget target = scopes.lowering().forLoopTarget(f);
            org.babblelang.analysis.LoweringMetadata.CountedRangeSource crs =
                    scopes.lowering().countedRangeSource(f);
            if (target != null
                    && target.kind() == LoweringTargets.ForKind.COUNTED_RANGE
                    && crs != null) {
                HirExpr.CountedRangeLoop cr =
                        new HirExpr.CountedRangeLoop(
                                lowerExpr(crs.start()),
                                lowerExpr(crs.end()),
                                crs.step() != null ? lowerExpr(crs.step()) : null,
                                crs.inclusive(),
                                crs.descending(),
                                crs.elemKind());
                return new HirExpr.For(
                        null,
                        target,
                        hirVar(resolution.variable(f)),
                        hirVars(resolution.declared(f.forBlock)),
                        body,
                        scopes.lowering().numericWideningRank(f.forBlock),
                        scopes.lowering().forElemSamTarget(f),
                        cr,
                        metaOf(f, scopes.effects().forEffects(f)));
            }
            return new HirExpr.For(
                    lowerExpr(f.iterable),
                    target,
                    hirVar(resolution.variable(f)),
                    hirVars(resolution.declared(f.forBlock)),
                    body,
                    scopes.lowering().numericWideningRank(f.forBlock),
                    scopes.lowering().forElemSamTarget(f),
                    metaOf(f, scopes.effects().forEffects(f)));
        }
        if (ctx instanceof BabbleParser.TryExpressionContext t) {
            return lowerTryExpr(t);
        }
        if (ctx instanceof BabbleParser.BreakExpressionContext b) {
            return new HirExpr.Break(
                    b.expression() != null ? lowerExpr(b.expression()) : null, metaOf(b));
        }
        if (ctx instanceof BabbleParser.ContinueExpressionContext c) {
            return new HirExpr.Continue(metaOf(c));
        }
        if (ctx instanceof BabbleParser.ReturnExpressionContext r) {
            return new HirExpr.Return(wrapJavaSam(lowerExpr(r.expression()), r), metaOf(r));
        }
        if (ctx instanceof BabbleParser.ThrowExpressionContext th) {
            LoweringTargets.ThrowTarget target = scopes.lowering().throwTarget(th);
            return new HirExpr.Throw(
                    lowerExpr(th.expression()),
                    target != null && target.isJavaThrowable(),
                    metaOf(th));
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
                    metaOf(def));
        }
        if (ctx instanceof BabbleParser.SelectorContext sel) {
            return lowerSelector(sel);
        }
        if (ctx instanceof BabbleParser.AssignExpressionContext assign) {
            return lowerAssign(assign);
        }
        if (ctx instanceof BabbleParser.CallContext call) {
            return callLowerer.lowerCall(call);
        }
        if (ctx instanceof BabbleParser.FunctionLiteralContext fn) {
            return new HirExpr.FunctionRef(
                    functionIds.get(fn), metaOf(fn, scopes.effects().callableEffects(fn)));
        }
        if (ctx instanceof BabbleParser.OverloadExpressionContext ov) {
            List<HirExpr> operands = new ArrayList<>(ov.expression().size());
            for (BabbleParser.ExpressionContext op : ov.expression()) {
                operands.add(lowerExpr(op));
            }
            List<LoweringTargets.OverloadAltMetaInfo> metas = scopes.calls().overloadMetas(ov);
            return new HirExpr.MakeOverloadSet(
                    operands, metas != null ? metas : List.of(), metaOf(ov));
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
        throw new IllegalStateException(
                "Unsupported expression in HirBuilder : " + ctx.getClass().getSimpleName());
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
        return new HirExpr.Const(
                resolved,
                new ExprMeta(
                        ctx.getStart().getLine(),
                        pk,
                        scopes.lowering().numericWideningRank(ctx),
                        scopes.lowering().checkedType(ctx),
                        EffectSet.EMPTY,
                        true,
                        false));
    }

    private HirExpr lowerIntLiteral(ParserRuleContext ctx, String text) {
        ExprMeta meta =
                new ExprMeta(
                        ctx.getStart().getLine(),
                        PrimitiveKind.INT,
                        scopes.lowering().numericWideningRank(ctx),
                        scopes.lowering().checkedType(ctx),
                        EffectSet.EMPTY,
                        true,
                        false);
        try {
            return new HirExpr.Const(Integer.parseInt(text), meta);
        } catch (NumberFormatException e) {
            return new HirExpr.IntParseFallback(text, meta);
        }
    }

    HirExpr lowerBlock(BabbleParser.BlockContext block, BabbleParser.BlockExpressionContext outer) {
        List<HirExpr> stmts = new ArrayList<>(block.expression().size());
        for (BabbleParser.ExpressionContext e : block.expression()) {
            stmts.add(lowerExpr(e));
        }
        List<HirVar> declVars = hirVars(resolution.declared(block));
        boolean opensNs = declVars.isEmpty() && scopes.opensScope(block);
        String[] declNames = opensNs ? scopes.declared(block) : new String[0];
        if (outer != null) {
            return new HirExpr.Block(
                    stmts,
                    opensNs,
                    declNames,
                    declVars,
                    scopes.lowering().numericWideningRank(block),
                    metaOf(outer));
        }
        return wrapJavaSam(
                new HirExpr.Block(stmts, opensNs, declNames, declVars, -1, metaOf(block)), block);
    }

    private HirExpr lowerTryExpr(BabbleParser.TryExpressionContext t) {
        List<HirExpr> tryBody = new ArrayList<>(t.tryBlock.expression().size());
        for (BabbleParser.ExpressionContext e : t.tryBlock.expression()) {
            tryBody.add(lowerExpr(e));
        }
        if (!tryBody.isEmpty()) {
            int last = tryBody.size() - 1;
            tryBody.set(last, wrapJavaSam(tryBody.get(last), t.tryBlock));
        }
        List<HirVar> tryDeclVars = hirVars(resolution.declared(t.tryBlock));
        boolean tryOpensNs = tryDeclVars.isEmpty() && scopes.opensScope(t.tryBlock);
        String[] tryDeclNames = tryOpensNs ? scopes.declared(t.tryBlock) : new String[0];
        int tryWiden = scopes.lowering().numericWideningRank(t.tryBlock);

        List<HirExpr.HirCatch> catches = new ArrayList<>(t.catchClause().size());
        for (BabbleParser.CatchClauseContext cc : t.catchClause()) {
            List<HirExpr> catchBody = new ArrayList<>(cc.catchBlock.expression().size());
            for (BabbleParser.ExpressionContext e : cc.catchBlock.expression()) {
                catchBody.add(lowerExpr(e));
            }
            if (!catchBody.isEmpty()) {
                int last = catchBody.size() - 1;
                catchBody.set(last, wrapJavaSam(catchBody.get(last), cc.catchBlock));
            }
            List<HirVar> catchDeclVars = hirVars(resolution.declared(cc.catchBlock));
            boolean catchOpensNs = catchDeclVars.isEmpty() && scopes.opensScope(cc.catchBlock);
            String[] catchDeclNames = catchOpensNs ? scopes.declared(cc.catchBlock) : new String[0];
            HirVar catchVar = cc.name != null ? hirVar(resolution.variable(cc)) : null;
            LoweringTargets.CatchTarget target = scopes.lowering().catchTarget(cc);
            int catchWiden = scopes.lowering().numericWideningRank(cc.catchBlock);
            catches.add(
                    new HirExpr.HirCatch(
                            catchVar,
                            target,
                            catchBody,
                            catchDeclVars,
                            catchOpensNs,
                            catchDeclNames,
                            catchWiden,
                            cc.getStart().getLine()));
        }

        boolean hasFinally = t.finallyBlock != null;
        List<HirExpr> finallyBody = List.of();
        List<HirVar> finallyDeclVars = List.of();
        boolean finallyOpensNs = false;
        String[] finallyDeclNames = new String[0];
        boolean finallyNothrow = true;
        if (hasFinally) {
            finallyBody = new ArrayList<>(t.finallyBlock.expression().size());
            for (BabbleParser.ExpressionContext e : t.finallyBlock.expression()) {
                finallyBody.add(lowerExpr(e));
            }
            finallyDeclVars = hirVars(resolution.declared(t.finallyBlock));
            finallyOpensNs = finallyDeclVars.isEmpty() && scopes.opensScope(t.finallyBlock);
            finallyDeclNames = finallyOpensNs ? scopes.declared(t.finallyBlock) : new String[0];
            finallyNothrow = scopes.effects().isTryFinallyNothrow(t);
        }

        return new HirExpr.Try(
                tryBody,
                tryDeclVars,
                tryOpensNs,
                tryDeclNames,
                tryWiden,
                catches,
                finallyBody,
                finallyDeclVars,
                finallyOpensNs,
                finallyDeclNames,
                finallyNothrow,
                hasFinally,
                metaOf(t));
    }

    private HirExpr lowerIfExpr(
            BabbleParser.IfExprContext ctx, BabbleParser.IfExpressionContext outer) {
        HirExpr cond = lowerExpr(ctx.test);
        HirExpr thenBr = lowerBlock(ctx.thenBlock, null);
        HirExpr elseBr = null;
        if (ctx.elseBlock != null) {
            elseBr = lowerBlock(ctx.elseBlock, null);
        } else if (ctx.elseIf != null) {
            elseBr = lowerIfExpr(ctx.elseIf, null);
        }
        // Notice: CodeGenerator.visitIfExpr uses scopes.lowering().expressionKind(ctx) and caller
        // widens outer (ifWrap) or ctx.
        ParserRuleContext metaCtx = outer != null ? outer : ctx;
        int widening = scopes.lowering().numericWideningRank(metaCtx);
        if (widening < 0 && outer != null) {
            widening = scopes.lowering().numericWideningRank(ctx);
        }
        ExprMeta meta =
                new ExprMeta(
                        ctx.getStart().getLine(),
                        scopes.lowering().expressionKind(ctx),
                        widening,
                        scopes.lowering().checkedType(metaCtx),
                        EffectSet.EMPTY,
                        scopes.lowering().expressionKind(ctx).isPrimitive()
                                || scopes.lowering().isExpressionDeeplyImmutable(metaCtx),
                        scopes.lowering().isExpressionString(metaCtx));
        HirExpr.If ifNode = new HirExpr.If(cond, thenBr, elseBr, meta);
        return outer == null ? wrapJavaSam(ifNode, ctx) : ifNode;
    }

    private HirExpr.NameRead makeNameRead(ParserRuleContext ctx, String name) {
        boolean isFinal = scopes.isFinalName(ctx, name);
        boolean defDefined = scopes.isDefinitelyDefinedFromSessionOrPrelude(ctx, name);
        HirExpr.NameRead nr = new HirExpr.NameRead(name, isFinal, defDefined, metaOf(ctx));
        if (!defDefined && scopes.resolvesToFileScope(ctx, name)) {
            fileScopeNameReads.add(nr);
        }
        return nr;
    }

    private HirExpr lowerSelector(BabbleParser.SelectorContext ctx) {
        String name = ScopeAnalysis.selectorName(ctx);
        BabbleParser.ExpressionContext receiver = ctx.expression();
        if (receiver == null) {
            Resolution.Variable v = resolution.variable(ctx);
            if (v != null) {
                return new HirExpr.VarRead(hirVar(v), metaOf(ctx));
            }
            return makeNameRead(ctx, name);
        }
        if (ctx.safe != null) {
            return new HirExpr.DynamicSelect(
                    lowerExpr(receiver), receiver.getText(), name, true, metaOf(ctx));
        }
        LoweringTargets.JavaFieldTarget javaField = scopes.lowering().javaFieldTarget(ctx);
        if (javaField != null) {
            return new HirExpr.JavaFieldGet(
                    lowerExpr(receiver),
                    receiver.getText(),
                    javaField.isStatic() && isPureStaticJavaChain(receiver),
                    javaField,
                    metaOf(ctx));
        }
        LoweringTargets.ModuleExportTarget exportTarget = scopes.lowering().moduleExportTarget(ctx);
        if (exportTarget != null) {
            return new HirExpr.ModuleExportGet(lowerExpr(receiver), exportTarget, metaOf(ctx));
        }
        if (receiver instanceof BabbleParser.ThisExpressionContext thisExpr) {
            int clsId = thisCompiledClassIdAt(thisExpr);
            int fieldIdx = compiledFieldIndex(thisExpr, name);
            boolean immutableField = false;
            boolean defDefined = false;
            if (clsId >= 0 && fieldIdx >= 0) {
                ParserRuleContext clsCtx = findThisCompiledClassCtx(thisExpr);
                ClassShape shape = clsCtx != null ? scopes.hierarchy().shape(clsCtx) : null;
                if (shape != null) {
                    immutableField = shape.isValue() || !shape.isMutable(fieldIdx);
                    defDefined = shape.isHeaderParameterMember(fieldIdx);
                }
            }
            return new HirExpr.ThisFieldGet(
                    lowerExpr(receiver),
                    null,
                    clsId,
                    null,
                    fieldIdx,
                    name,
                    PrimitiveKind.NONE,
                    immutableField,
                    defDefined,
                    metaOf(ctx));
        }
        LoweringTargets.ClassFieldTarget classField = scopes.lowering().classFieldTarget(ctx);
        if (classField != null) {
            Integer localClsId = localClassIdsByShapeId.get(classField.classTypeId());
            if (localClsId != null) {
                return new HirExpr.ThisFieldGet(
                        lowerExpr(receiver),
                        receiver.getText(),
                        localClsId,
                        null,
                        classField.memberIndex(),
                        classField.memberName(),
                        classField.fieldKind(),
                        classField.immutableField(),
                        classField.definitelyDefined(),
                        metaOf(ctx));
            }
            String extJvm = allClassJvmNames.get(classField.classTypeId());
            if (extJvm != null && classField.definitelyDefined()) {
                return new HirExpr.ThisFieldGet(
                        lowerExpr(receiver),
                        receiver.getText(),
                        -1,
                        extJvm,
                        classField.memberIndex(),
                        classField.memberName(),
                        classField.fieldKind(),
                        classField.immutableField(),
                        true,
                        metaOf(ctx));
            }
        }
        HirExpr.DynamicSelect ds =
                new HirExpr.DynamicSelect(
                        lowerExpr(receiver), receiver.getText(), name, false, metaOf(ctx));
        if (scopes.isPublicStaticDefSelect(ctx)) {
            publicStaticDefSelects.add(ds);
        }
        return ds;
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
                return new HirExpr.VarAssign(
                        hirVar(v), operator, value, targetText, valueText, meta);
            }
            return new HirExpr.NameAssign(name, operator, value, targetText, valueText, meta);
        }
        if (recv instanceof BabbleParser.ThisExpressionContext thisExpr) {
            int clsId = thisCompiledClassIdAt(thisExpr);
            int fieldIdx = compiledFieldIndex(thisExpr, name);
            // Value classes / records are read-only; keep fallback to Members.assignOwn just like
            // CodeGenerator.
            if (clsId >= 0) {
                ParserRuleContext clsCtx = findThisCompiledClassCtx(thisExpr);
                if (clsCtx != null && scopes.hierarchy().shape(clsCtx).isValue()) {
                    fieldIdx = -1;
                }
            }
            return new HirExpr.ThisFieldAssign(
                    lowerExpr(recv),
                    clsId,
                    fieldIdx,
                    name,
                    operator,
                    value,
                    targetText,
                    valueText,
                    meta);
        }
        LoweringTargets.JavaFieldTarget javaField = scopes.lowering().javaFieldTarget(ctx);
        if (javaField != null) {
            return new HirExpr.JavaFieldAssign(
                    lowerExpr(recv),
                    recv.getText(),
                    javaField.isStatic() && isPureStaticJavaChain(recv),
                    javaField,
                    operator,
                    value,
                    targetText,
                    valueText,
                    meta);
        }
        return new HirExpr.DynamicFieldAssign(
                lowerExpr(recv),
                recv.getText(),
                name,
                operator,
                value,
                targetText,
                valueText,
                meta);
    }

    // ---------------------------------------------------------------- helpers

    ParserRuleContext findEnclosingClassCtx(ParserRuleContext at) {
        for (ParserRuleContext p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext
                    || p instanceof BabbleParser.ObjectExpressionContext) {
                return p;
            }
        }
        return null;
    }

    ParserRuleContext findEnclosingOwnerClassCtx(ParserRuleContext at, String ownerTypeId) {
        for (ParserRuleContext p = at.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.ClassExpressionContext
                    || p instanceof BabbleParser.ObjectExpressionContext) {
                ClassShape shape = scopes.hierarchy().shape(p);
                if (shape != null && shape.id().equals(ownerTypeId)) {
                    return p;
                }
            }
        }
        return null;
    }

    ParserRuleContext findThisCompiledClassCtx(ParserRuleContext thisExpression) {
        ParserRuleContext child = thisExpression;
        for (ParserRuleContext p = thisExpression.getParent();
                p != null;
                child = p, p = p.getParent()) {
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

    boolean isPureStaticJavaChain(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1
                && resolution.declared(b.block()).isEmpty()
                && !scopes.opensScope(b.block())) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.CoreExceptionRefContext) {
            return true;
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
            return v != null
                    && v.declaration() != null
                    && scopes.lowering().isDefinitelyDefined(v.declaration());
        }
        return false;
    }

    String resolveBabbleJvmName(String typeId) {
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
}
