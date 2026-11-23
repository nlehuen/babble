package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ANTLR syntax adapter for effect prefixes, parameter modifiers, {@code calling(...)} clauses,
 * and {@code axiom} / {@code io} declarations extracted from {@link EffectChecker} ({@code BIP027-I3}).
 */
final class EffectModifiers {
    enum ParseContext {
        FUNCTION_LITERAL,
        FUNCTION_TYPE,
        INTERFACE_METHOD,
        JAVA_TARGET
    }

    private final EffectChecker ec;

    EffectModifiers(EffectChecker ec) {
        this.ec = ec;
    }

    // ================================================================ 1. Parsing Modifiers

    boolean isCompletelyUntyped(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() != null || fn.type() != null || fn.uniqueReturn != null) {
            return false;
        }
        if (isExportOrInterfaceMethod(fn)) {
            return false;
        }
        List<BabbleParser.FnParamContext> params = fn.fnParameters().fnParam();
        for (BabbleParser.FnParamContext param : params) {
            if (param.type() != null || !ScopeAnalysis.paramModifiers(param).isEmpty()) {
                return false;
            }
        }
        // A named `def f = fn () -> (...)` has zero untyped parameters (it simply omits the void/null return type);
        // treat it as a normal typed function (pure by default per BIP016-D3), while anonymous inline lambdas and
        // functions with untyped parameters infer effects.
        if (params.isEmpty() && isNamedDefRhs(fn)) {
            return false;
        }
        return true;
    }

    static boolean isNamedDefRhs(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur;
    }

    boolean isInterfaceDefault(BabbleParser.FunctionLiteralContext fn) {
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        if (cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur) {
            LoweringTargets.DefTarget target = ec.pass.analysis.lowering().defTarget(def);
            return target != null && target.kind() == LoweringTargets.DefKind.DEFAULT;
        }
        return false;
    }

    boolean isExportOrInterfaceMethod(BabbleParser.FunctionLiteralContext fn) {
        if (isInterfaceDefault(fn)) {
            return true;
        }
        ParserRuleContext cur = fn;
        while ((cur.getParent() instanceof BabbleParser.BlockExpressionContext blk
                && blk.block().expression().size() == 1)
                || cur.getParent() instanceof BabbleParser.OverloadExpressionContext) {
            cur = cur.getParent();
        }
        return cur.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == cur
                && ec.checker.isExportedDef(def);
    }

    EffectChecker.ParsedPrefix parseFunctionPrefix(BabbleParser.FunctionLiteralContext ctx, boolean reportErrors) {
        if (!reportErrors) {
            EffectChecker.ParsedPrefix cached = ec.fnPrefixes.get(ctx);
            if (cached != null) {
                return cached;
            }
        }
        boolean untyped = isCompletelyUntyped(ctx);
        boolean hasThis = canReferenceThis(ec.checker.scopeOf(ctx));
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.FnParamContext param : ctx.fnParameters().fnParam()) {
            EffectChecker.ParamMods pm = parseParamMods(param, reportErrors);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.shared() || pm.unique();
        }
        if (untyped) {
            if (ec.checker.inFile(ctx)) {
                ec.fnPrefixes.put(ctx, EffectChecker.ParsedPrefix.EMPTY);
            }
            return EffectChecker.ParsedPrefix.EMPTY;
        }
        EffectChecker.ParsedPrefix parsed = parseWords(
                ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of(),
                ParseContext.FUNCTION_LITERAL, hasThis, hasMutParam, hasAnyParamMod,
                ctx.uniqueReturn != null, ctx, reportErrors);
        if (reportErrors) {
            if (parsed.axiom() && !ec.pass.hostEffects.allowAxioms()) {
                ec.checker.effectDiagnostic("axiom declarations are disabled on this engine", ctx);
            }
        }
        if (ec.checker.inFile(ctx)) {
            ec.fnPrefixes.put(ctx, parsed);
        }
        return parsed;
    }

    private static List<Token> effectPrefixTokens(BabbleParser.EffectPrefixContext prefix) {
        return prefix.effectWord().stream().map(ParserRuleContext::getStart).toList();
    }

    private static List<Token> effectClauseTokens(BabbleParser.EffectClauseContext clause) {
        List<Token> words = new ArrayList<>();
        for (int i = 0; i < clause.getChildCount(); i++) {
            ParseTree child = clause.getChild(i);
            if (child instanceof ParserRuleContext prc) {
                words.add(prc.getStart());
            } else if (child instanceof org.antlr.v4.runtime.tree.TerminalNode tn) {
                words.add(tn.getSymbol());
            }
        }
        return words;
    }

    EffectChecker.ParsedPrefix parseFunctionTypePrefix(BabbleParser.FunctionTypeContext ctx, boolean reportErrors) {
        return parseFunctionTypePrefix(ctx, false, reportErrors);
    }

    EffectChecker.ParsedPrefix parseFunctionTypePrefix(BabbleParser.FunctionTypeContext ctx, boolean interfaceMethod, boolean reportErrors) {
        boolean report = reportErrors && ec.validatedTypeTrees.add(ctx);
        List<Token> words = ctx.effectPrefix() != null ? effectPrefixTokens(ctx.effectPrefix()) : List.of();
        boolean hasMutParam = false;
        boolean hasAnyParamMod = false;
        for (BabbleParser.FnTypeParamContext param : ctx.fnTypeParameters().fnTypeParam()) {
            EffectChecker.ParamMods pm = parseParamMods(param, report);
            hasMutParam |= pm.mut();
            hasAnyParamMod |= pm.mut() || pm.shared() || pm.unique();
        }
        return parseWords(words,
                interfaceMethod ? ParseContext.INTERFACE_METHOD : ParseContext.FUNCTION_TYPE,
                interfaceMethod, hasMutParam, hasAnyParamMod, ctx.uniqueReturn != null, ctx, report);
    }

    EffectChecker.ParamMods parseParamMods(ParserRuleContext param, boolean reportErrors) {
        if (!reportErrors) {
            EffectChecker.ParamMods cached = ec.paramModsMap.get(param);
            if (cached != null) {
                return cached;
            }
        }
        boolean mut = false;
        boolean shared = false;
        boolean unique = false;
        for (BabbleParser.ParamModifierContext modCtx : ScopeAnalysis.paramModifiers(param)) {
            Token tok = modCtx.getStart();
            switch (tok.getText()) {
                case "mut" -> {
                    if (mut && reportErrors) {
                        ec.checker.effectDiagnostic("Duplicate parameter modifier : mut", modCtx);
                    }
                    mut = true;
                }
                case "shared" -> {
                    if (shared && reportErrors) {
                        ec.checker.effectDiagnostic("Duplicate parameter modifier : shared", modCtx);
                    }
                    shared = true;
                }
                case "unique" -> {
                    if (unique && reportErrors) {
                        ec.checker.effectDiagnostic("Duplicate parameter modifier : unique", modCtx);
                    }
                    unique = true;
                }
                default -> {
                    if (reportErrors) {
                        ec.checker.effectDiagnostic("Unknown parameter modifier : " + tok.getText(), modCtx);
                    }
                }
            }
        }
        if (reportErrors) {
            if (unique && shared) {
                ec.checker.effectDiagnostic("unique and shared are mutually exclusive", param);
            }
            if (unique && mut) {
                ec.checker.effectDiagnostic("unique and mut are mutually exclusive : consuming unique parameters already allow private mutation", param);
            }
        }
        if (unique) {
            shared = false;
            mut = false;
        }
        EffectChecker.ParamMods mods = (mut || shared || unique) ? new EffectChecker.ParamMods(mut, shared, unique) : EffectChecker.ParamMods.NONE;
        if (ec.checker.inFile(param)) {
            ec.paramModsMap.put(param, mods);
        }
        return mods;
    }

    private EffectChecker.ParsedPrefix parseWords(List<Token> tokens,
                                    ParseContext kind,
                                    boolean hasThis,
                                    boolean hasMutParam,
                                    boolean hasAnyParamMod,
                                    boolean hasUniqueReturnType,
                                    ParserRuleContext owner,
                                    boolean reportErrors) {
        boolean inFunctionType = kind == ParseContext.FUNCTION_TYPE || kind == ParseContext.INTERFACE_METHOD;
        boolean isJavaTarget = kind == ParseContext.JAVA_TARGET;
        EnumSet<Effect> explicitEffects = EnumSet.noneOf(Effect.class);
        boolean explicitPure = false;
        boolean explicitVolatile = false;
        boolean io = false;
        boolean mutThis = false;
        boolean explicitSharedThis = false;
        boolean uniqueReturn = hasUniqueReturnType;
        boolean axiom = false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token tok = tokens.get(i);
            String word = tok.getText();
            if (!seen.add(word) && reportErrors) {
                ec.checker.effectDiagnostic("Duplicate effect modifier : " + word, owner);
            }
            switch (word) {
                case "axiom" -> {
                    if (inFunctionType) {
                        if (reportErrors) {
                            ec.checker.effectDiagnostic("axiom is not allowed in a function type", owner);
                        }
                    } else if (i > 0 && reportErrors) {
                        ec.checker.effectDiagnostic("axiom must appear first before effect modifiers", owner);
                    }
                    axiom = true;
                }
                case "pure" -> explicitPure = true;
                case "volatile" -> explicitVolatile = true;
                case "io" -> io = true;
                case "nothrow" -> explicitEffects.add(Effect.NOTHROW);
                case "idempotent" -> explicitEffects.add(Effect.IDEMPOTENT);
                case "commutative" -> explicitEffects.add(Effect.COMMUTATIVE);
                case "associative" -> explicitEffects.add(Effect.ASSOCIATIVE);
                case "monotone" -> explicitEffects.add(Effect.MONOTONE);
                case "injective" -> explicitEffects.add(Effect.INJECTIVE);
                case "unique" -> uniqueReturn = true;
                case "mut" -> {
                    if (!hasThis && kind == ParseContext.FUNCTION_LITERAL && reportErrors) {
                        ec.checker.effectDiagnostic("mut on a function requires a receiver (this) : mark mutated parameters with mut", owner);
                    }
                    mutThis = true;
                }
                case "shared" -> {
                    if (!hasThis && kind == ParseContext.FUNCTION_LITERAL && reportErrors) {
                        ec.checker.effectDiagnostic("shared on a function requires a receiver (this) : mark escaping parameters with shared", owner);
                    }
                    explicitSharedThis = true;
                }
                default -> {
                    if (reportErrors) {
                        ec.checker.effectDiagnostic("Unknown effect modifier : " + word, owner);
                    }
                }
            }
        }
        if (reportErrors) {
            if (explicitPure && !axiom) {
                ec.checker.effectDiagnostic("pure is the default on functions : only use pure with axiom", owner);
            }
            if (axiom && io) {
                ec.checker.effectDiagnostic("axiom and io cannot be combined", owner);
            } else if (isJavaTarget && io && (explicitPure || explicitVolatile || !explicitEffects.isEmpty() || mutThis || explicitSharedThis || uniqueReturn)) {
                ec.checker.effectDiagnostic("io cannot be combined with effect modifiers on a Java target", owner);
            }
            if (io && explicitVolatile) {
                ec.checker.effectDiagnostic("io already implies volatile : use io alone", owner);
            }
            if (io && explicitPure) {
                ec.checker.effectDiagnostic("io and pure cannot be combined", owner);
            }
            if (explicitPure && explicitVolatile) {
                ec.checker.effectDiagnostic("pure and volatile cannot be combined", owner);
            }
            if (explicitPure && (mutThis || hasMutParam)) {
                ec.checker.effectDiagnostic("pure and mut are mutually exclusive : use mut (or volatile mut)", owner);
            }
            if (axiom && !explicitPure && !explicitVolatile && explicitEffects.isEmpty()
                    && !mutThis && !explicitSharedThis && !uniqueReturn && !hasAnyParamMod) {
                ec.checker.effectDiagnostic("axiom requires at least one effect modifier", owner);
            }
        }

        EnumSet<Effect> effects = EnumSet.copyOf(explicitEffects);
        boolean anyMut = mutThis || hasMutParam;
        boolean hasExplicitWords = !tokens.isEmpty() || hasUniqueReturnType;
        if (isJavaTarget) {
            if (io) {
                return new EffectChecker.ParsedPrefix(EnumSet.noneOf(Effect.class), false, false, false, false, false, true, false, true);
            }
            if (explicitPure && !anyMut) {
                effects.add(Effect.PURE);
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            } else if (explicitVolatile) {
                effects.add(Effect.HERMETIC);
                if (!anyMut) {
                    effects.add(Effect.PURE);
                }
            } else if (mutThis) {
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            }
            boolean borrowThis = hasThis && !explicitSharedThis;
            return new EffectChecker.ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn, axiom, false, explicitVolatile, true);
        }

        if (axiom) {
            if (explicitPure && !anyMut) {
                effects.add(Effect.PURE);
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            } else if (explicitVolatile) {
                effects.add(Effect.HERMETIC);
                if (!anyMut) {
                    effects.add(Effect.PURE);
                }
            } else if (anyMut) {
                effects.add(Effect.HERMETIC);
                effects.add(Effect.DETERMINISTIC);
            }
            boolean borrowThis = !explicitSharedThis;
            return new EffectChecker.ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn, true, false, explicitVolatile, true);
        }

        // Typed function literal, function type, or interface method: pure & deterministic & @BORROWED by default (BIP016-D3, D4)!
        if (io) {
            effects.remove(Effect.PURE);
            effects.remove(Effect.HERMETIC);
            effects.remove(Effect.DETERMINISTIC);
        } else if (explicitVolatile) {
            effects.add(Effect.HERMETIC);
            effects.remove(Effect.DETERMINISTIC);
            if (!anyMut) {
                effects.add(Effect.PURE);
            }
        } else {
            effects.add(Effect.HERMETIC);
            effects.add(Effect.DETERMINISTIC);
            if (!anyMut) {
                effects.add(Effect.PURE);
            }
        }
        boolean borrowThis = !explicitSharedThis;
        return new EffectChecker.ParsedPrefix(effects, mutThis, borrowThis, explicitSharedThis, uniqueReturn,
                false, io, !io && explicitVolatile, hasExplicitWords);
    }

    static boolean canReferenceThis(ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION && s.memberOf != null) {
                return !s.staticMethod;
            }
            if (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                return true;
            }
            if (s.kind == ScopeNode.Kind.STATIC_BODY) {
                return false;
            }
        }
        return false;
    }


    // ================================================================ 3. Pre-pass : Axiom & IO Collection

    void collectAxiomExpressions() {
        if (ec.pass.axiomExpressions.isEmpty()) {
            return;
        }
        AxiomData.Builder localBuilder = new AxiomData.Builder();
        AxiomData.Builder committedBuilder = new AxiomData.Builder();
        AxiomData.Builder exportedBuilder = new AxiomData.Builder();
        AxiomData.ErrorSink sink = (msg, at) -> ec.pendingAxiomDiags.add(new EffectChecker.PendingAxiomDiag(msg, at));

        for (BlockScopes.PendingAxiomExpr pending : ec.pass.axiomExpressions) {
            BabbleParser.AxiomExpressionContext ctx = pending.ctx();
            ScopeNode scope = pending.scope();
            if (!ec.pass.hostEffects.allowAxioms()) {
                sink.report("axiom declarations are disabled on this engine", ctx);
                continue;
            }
            AxiomData.ClassResolver resolver = path -> resolveAxiomClassPath(path, scope);
            AxiomData.ParsedAxiom parsed = AxiomData.parse(ctx, resolver, false, sink);
            if (parsed == null || !parsed.isJava()) {
                continue;
            }
            if (parsed.classAxiom() != null) {
                if (localBuilder.hasClassAxiom(parsed.classAxiom().targetClass())) {
                    sink.report("Duplicate axiom for class : " + parsed.classAxiom().targetClass().getName(), ctx);
                    continue;
                }
                boolean conflict = false;
                AxiomData.Builder selfCheck = new AxiomData.Builder();
                for (AxiomData.MemberAxiom m : parsed.classAxiom().members()) {
                    if (localBuilder.hasMemberConflict(m) || selfCheck.hasMemberConflict(m)) {
                        sink.report("Duplicate axiom for member : " + m.ownerClass().getName() + "." + m.name(), ctx);
                        conflict = true;
                    } else {
                        selfCheck.addMemberAxiom(m);
                    }
                }
                if (conflict) {
                    continue;
                }
                localBuilder.addClassAxiom(parsed.classAxiom());
                if (scope.kind == ScopeNode.Kind.FILE) {
                    committedBuilder.addClassAxiom(parsed.classAxiom());
                }
                if (ctx.EXPORT() != null) {
                    exportedBuilder.addClassAxiom(parsed.classAxiom());
                }
            } else if (parsed.memberAxiom() != null) {
                AxiomData.MemberAxiom m = parsed.memberAxiom();
                if (localBuilder.hasMemberConflict(m)) {
                    sink.report("Duplicate axiom for member : " + m.ownerClass().getName() + "." + m.name(), ctx);
                    continue;
                }
                localBuilder.addMemberAxiom(m);
                if (scope.kind == ScopeNode.Kind.FILE) {
                    committedBuilder.addMemberAxiom(m);
                }
                if (ctx.EXPORT() != null) {
                    exportedBuilder.addMemberAxiom(m);
                }
            }
        }
        AxiomData.AxiomTable fileTable = localBuilder.build();
        if (!fileTable.isEmpty()) {
            ec.localAxioms = ec.localAxioms.mergeOver(fileTable);
        }
        ec.committedAxioms = committedBuilder.build();
        ec.exportedAxioms = exportedBuilder.build();
    }

    private static Class<?> resolveAxiomClassPath(List<String> path, ScopeNode scope) {
        Class<?> direct = JavaTypeBridge.loadClass(String.join(".", path));
        if (direct != null) {
            return direct;
        }
        Object resolved = TypeChecker.resolveName(null, path.get(0), scope);
        if (resolved instanceof BabbleParser.DefExpressionContext def && def.value != null) {
            String pkg = importedJavaPackageName(def.value);
            if (pkg != null) {
                String joined = path.size() == 1 ? pkg : pkg + "." + String.join(".", path.subList(1, path.size()));
                return JavaTypeBridge.loadClass(joined);
            }
            List<String> selectorPath = staticSelectorPath(def.value);
            if (selectorPath != null) {
                List<String> full = new ArrayList<>(selectorPath);
                full.addAll(path.subList(1, path.size()));
                return JavaTypeBridge.loadClass(String.join(".", full));
            }
        }
        return null;
    }

    private static String importedJavaPackageName(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() == null
                && ("import".equals(sel.ID().getText()) || "importe".equals(sel.ID().getText()))
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext str) {
            String name = org.babblelang.parser.StringLiterals.decode(str);
            return name.endsWith(".ba") ? null : name;
        }
        return null;
    }

    private static List<String> staticSelectorPath(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                return List.of(sel.ID().getText());
            }
            List<String> prefix = staticSelectorPath(sel.expression());
            if (prefix == null) {
                return null;
            }
            List<String> out = new ArrayList<>(prefix);
            out.add(sel.memberName().getText());
            return out;
        }
        return null;
    }

    void collectDeclarations(BabbleParser.FileContext file) {
        // 0. Report any diagnostics discovered while parsing BIP019 axiomExpressions.
        for (EffectChecker.PendingAxiomDiag diag : ec.pendingAxiomDiags) {
            ec.checker.effectDiagnostic(diag.message(), diag.at());
        }

        // 1. Standalone effectDeclarations recorded by BlockScopes.
        for (BlockScopes.PendingEffectDecl pending : ec.pass.effectDeclarations) {
            BabbleParser.EffectDeclarationContext ctx = pending.ctx();
            ScopeNode scope = pending.scope();
            List<Token> words = effectClauseTokens(ctx.effectClause());
            boolean isCtor = ctx.ctorOpen != null;
            EffectChecker.ParsedPrefix prefix = parseTargetPrefix(words, isCtor, ctx);
            if (!ec.pass.hostEffects.allowAxioms()) {
                ec.checker.effectDiagnostic((prefix.io() ? "io" : "axiom") + " declarations are disabled on this engine", ctx);
                continue;
            }
            checkDeclarationPlacement(scope, prefix.io() ? "io" : "axiom", ctx);
            HostEffects.JavaTarget target = resolveEffectTarget(ctx.effectTarget(), isCtor, scope, ctx);
            if (target == null) {
                continue;
            }
            validateJavaTargetPrefix(target, prefix, ctx);
            HostEffects.OverrideEntry entry = toOverrideEntry(prefix);
            ec.localJavaEffects.put(target, entry);
            if (scope.kind == ScopeNode.Kind.FILE) {
                ec.committedJavaEffects.put(target, entry);
            }
            if (ctx.EXPORT() != null) {
                ec.exportedJavaEffects.put(target, entry);
            }
        }

        // 2. DefExpressions with effectClause (e.g. def mkdirs = axiom idempotent on Files.createDirectories).
        collectDefAxioms(file);
    }

    private void collectDefAxioms(ParseTree tree) {
        if (tree instanceof BabbleParser.DefExpressionContext def && def.effectClause() != null) {
            List<Token> words = effectClauseTokens(def.effectClause());
            EffectChecker.ParsedPrefix prefix = parseTargetPrefix(words, false, def);
            ec.defPrefixes.put(def, prefix);
            ScopeNode scope = ec.pass.definitionScopes.get(def);
            if (!ec.pass.hostEffects.allowAxioms()) {
                ec.checker.effectDiagnostic((prefix.io() ? "io" : "axiom") + " declarations are disabled on this engine", def);
            } else {
                if (scope != null) {
                    checkDeclarationPlacement(scope, prefix.io() ? "io" : "axiom", def);
                }
                Type valType = def.value != null ? ec.checker.rawTypeOf(def.value) : null;
                HostEffects.JavaTarget target = null;
                if (valType instanceof Type.JavaMethod jm) {
                    target = HostEffects.JavaTarget.ofMethod(jm.owner(), jm.name());
                } else if (valType instanceof Type.JavaClassValue jcv) {
                    target = HostEffects.JavaTarget.ofClass(jcv.type());
                } else if (valType != null && !(valType instanceof Type.Function) && valType != Type.ERROR) {
                    ec.checker.effectDiagnostic("Invalid " + (prefix.io() ? "io" : "axiom")
                            + " target : expected a Java class, Java method, or function", def);
                }
                if (target != null) {
                    validateJavaTargetPrefix(target, prefix, def);
                    HostEffects.OverrideEntry entry = toOverrideEntry(prefix);
                    ec.localJavaEffects.put(target, entry);
                    if (scope != null && scope.kind == ScopeNode.Kind.FILE) {
                        ec.committedJavaEffects.put(target, entry);
                    }
                    if (ec.checker.isExportedDef(def)) {
                        ec.exportedJavaEffects.put(target, entry);
                    }
                }
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectDefAxioms(tree.getChild(i));
        }
    }

    private EffectChecker.ParsedPrefix parseTargetPrefix(List<Token> words, boolean isCtor, ParserRuleContext ctx) {
        return parseWords(words, ParseContext.JAVA_TARGET, !isCtor, false, false, false, ctx, true);
    }

    private void checkDeclarationPlacement(ScopeNode scope, String keyword, ParserRuleContext ctx) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION && s.owner instanceof BabbleParser.FunctionLiteralContext fn) {
                EffectChecker.ParsedPrefix p = parseFunctionPrefix(fn, false);
                if (!p.axiom() && !p.io() && (p.effects().contains(Effect.PURE)
                        || p.effects().contains(Effect.HERMETIC)
                        || p.effects().contains(Effect.DETERMINISTIC))) {
                    ec.checker.effectDiagnostic(keyword + " declaration is not allowed inside a verified constrained function", ctx);
                    return;
                }
            }
        }
    }

    private HostEffects.JavaTarget resolveEffectTarget(BabbleParser.EffectTargetContext targetCtx,
                                                       boolean isCtor,
                                                       ScopeNode scope,
                                                       ParserRuleContext reportAt) {
        String first = ScopeAnalysis.effectTargetRoot(targetCtx);
        List<String> members = ScopeAnalysis.effectTargetMembers(targetCtx);
        Type current;
        if (targetCtx.STRING() != null) {
            if (!"import".equals(first) && !"importe".equals(first)) {
                ec.checker.effectDiagnostic("Invalid target : expected import(\"...\") or Java class path", reportAt);
                return null;
            }
            if (TypeChecks.resolveName(first, scope) != null) {
                ec.checker.effectDiagnostic("Invalid target : shadowed " + first, reportAt);
                return null;
            }
            String pkg = org.babblelang.parser.StringLiterals.decode(targetCtx.STRING().getSymbol());
            if (pkg.endsWith(".ba")) {
                ec.checker.effectDiagnostic("Java effect target cannot be a .ba module : " + pkg, reportAt);
                return null;
            }
            Class<?> directClass = ec.checker.findJavaClass(pkg);
            current = directClass != null ? new Type.JavaClassValue(directClass) : new Type.JavaPackage(pkg);
        } else {
            Object resolved = ec.checker.resolveNameForEffects(first, scope);
            if (resolved instanceof ParserRuleContext decl) {
                current = ec.checker.typeOfDeclarationForEffects(decl);
            } else {
                current = ec.checker.implicitTypeForEffects(first);
                if (current == null) {
                    ec.checker.effectDiagnostic("Unknown identifier in effect declaration : " + first, reportAt);
                    return null;
                }
            }
        }

        for (int idx = 0; idx < members.size(); idx++) {
            String step = members.get(idx);
            boolean last = (idx == members.size() - 1);
            if (current instanceof Type.JavaPackage jp) {
                String qualified = jp.name() + "." + step;
                Class<?> cls = ec.checker.findJavaClass(qualified);
                current = cls != null ? new Type.JavaClassValue(cls) : new Type.JavaPackage(qualified);
            } else if (current instanceof Type.JavaClassValue jcv) {
                Class<?> memberCls = ec.checker.findMemberJavaClass(jcv.type(), step);
                if (memberCls != null && (!last || isCtor)) {
                    current = new Type.JavaClassValue(memberCls);
                } else if (last && !isCtor) {
                    if (!HostEffects.hasMethodNamed(jcv.type(), step)) {
                        if (memberCls != null) {
                            return HostEffects.JavaTarget.ofClass(memberCls);
                        }
                        ec.checker.effectDiagnostic("No such Java method : " + step + " on " + jcv.type().getName(), reportAt);
                        return null;
                    }
                    return HostEffects.JavaTarget.ofMethod(jcv.type(), step);
                } else {
                    ec.checker.effectDiagnostic("Unknown Java member class : " + step + " on " + jcv.type().getName(), reportAt);
                    return null;
                }
            } else if (current instanceof Type.Module mod) {
                current = ec.checker.moduleMemberTypeForEffects(mod.module(), step);
                if (current == null) {
                    ec.checker.effectDiagnostic("Unknown export : " + step + " in " + mod.module().name(), reportAt);
                    return null;
                }
            } else {
                ec.checker.effectDiagnostic("Invalid effect declaration target : " + targetCtx.getText(), reportAt);
                return null;
            }
        }

        if (current instanceof Type.JavaClassValue jcv) {
            return isCtor
                    ? HostEffects.JavaTarget.ofConstructor(jcv.type())
                    : HostEffects.JavaTarget.ofClass(jcv.type());
        }
        if (current instanceof Type.JavaMethod jm && !isCtor) {
            return HostEffects.JavaTarget.ofMethod(jm.owner(), jm.name());
        }
        ec.checker.effectDiagnostic("Invalid effect declaration target : expected a Java class, constructor, or method", reportAt);
        return null;
    }

    private void validateJavaTargetPrefix(HostEffects.JavaTarget target, EffectChecker.ParsedPrefix prefix, ParserRuleContext at) {
        if (prefix.io()) {
            return;
        }
        if (target.kind() == HostEffects.TargetKind.CLASS && prefix.mutThis()) {
            ec.checker.effectDiagnostic("Bare class axiom cannot declare mut : declare mut on specific methods instead", at);
        }
        if (target.kind() == HostEffects.TargetKind.CONSTRUCTOR && prefix.mutThis()) {
            ec.checker.effectDiagnostic("Constructor axiom cannot declare mut", at);
        }
    }

    private static HostEffects.OverrideEntry toOverrideEntry(EffectChecker.ParsedPrefix prefix) {
        if (prefix.io()) {
            return HostEffects.OverrideEntry.ioEntry();
        }
        return new HostEffects.OverrideEntry(prefix.effects(), prefix.mutThis(), !prefix.explicitSharedThis(),
                prefix.uniqueReturn(), false, prefix.explicitVolatile());
    }


    void validateAllFunctionTypes(ParseTree tree) {
        if (tree instanceof BabbleParser.AxiomExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.FunctionTypeContext ft) {
            Type t = ec.checker.annotationTypeOf(ft);
            if (t instanceof Type.Function fn) {
                EffectChecker.ParsedPrefix prefix = parseFunctionTypePrefix(ft, fn.effects().borrowThis() || fn.effects().explicitSharedThis(), false);
                ec.algebraic.validateAlgebraicSignature(prefix.effects(), fn.parameters(), fn.result(),
                        prefix.mutThis(), false, ft);
                validateCallingClause(ft.effectPrefix(), fn.parameters());
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            validateAllFunctionTypes(tree.getChild(i));
        }
    }

    void validateCallingClause(BabbleParser.EffectPrefixContext effectPrefix,
                                       List<Type.Parameter> params) {
        if (effectPrefix == null || effectPrefix.callingClause() == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : effectPrefix.callingClause().ID()) {
            String name = id.getText();
            if (!seen.add(name)) {
                ec.checker.effectDiagnostic("Duplicate parameter in calling clause : " + name, effectPrefix.callingClause());
                continue;
            }
            Type.Parameter matched = null;
            for (Type.Parameter p : params) {
                if (name.equals(p.name())) {
                    matched = p;
                    break;
                }
            }
            if (matched == null) {
                // A callback of an enclosing function this one captures (BIP017-D5) : no borrow
                // requirement, since capturing it is what the clause is for.
                ParserRuleContext captured = TypeAnnotations.enclosingParameter(effectPrefix, name);
                if (captured == null) {
                    ec.checker.effectDiagnostic("Unknown parameter in calling clause : " + name, effectPrefix.callingClause());
                } else {
                    Type capturedType = capturedParameterType(captured);
                    if (capturedType != null && capturedType != Type.ERROR && !(capturedType instanceof Type.Function)) {
                        ec.checker.effectDiagnostic("Parameter in calling clause must be a function : " + name,
                                effectPrefix.callingClause());
                    }
                }
                continue;
            }
            if (matched.type() != Type.ERROR && !(matched.type() instanceof Type.Function)) {
                ec.checker.effectDiagnostic("Parameter in calling clause must be a function : " + name, effectPrefix.callingClause());
                continue;
            }
            if (!matched.borrow()) {
                ec.checker.effectDiagnostic("Parameter in calling clause must be borrowed : " + name, effectPrefix.callingClause());
            }
        }
    }

    /**
     * The parameters of a function literal with {@code borrow} as declared (no {@code shared} nor
     * {@code unique}) rather than as inferred, so that a callback of a {@code calling} clause that
     * escapes is reported once, as escaping, and not also as not borrowed.
     */
    List<Type.Parameter> declaredBorrows(BabbleParser.FunctionLiteralContext fn,
                                                 List<Type.Parameter> params) {
        List<BabbleParser.FnParamContext> decls = fn.fnParameters().fnParam();
        if (decls.size() != params.size()) {
            return params;
        }
        List<Type.Parameter> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            Type.Parameter p = params.get(i);
            EffectChecker.ParamMods declared = parseParamMods(decls.get(i), false);
            boolean borrow = !declared.shared() && !declared.unique();
            result.add(new Type.Parameter(p.name(), p.type(), p.optional(), p.keywordOnly(), p.mut(), borrow,
                    p.explicitShared(), p.unique()));
        }
        return result;
    }

    /** The declared type of a parameter of a function literal or of a function type. */
    Type capturedParameterType(ParserRuleContext param) {
        if (param instanceof BabbleParser.FnTypeParamContext typeParam
                && typeParam.getParent() instanceof BabbleParser.FnTypeParametersContext list
                && list.getParent() instanceof BabbleParser.FunctionTypeContext ft
                && ec.checker.annotationTypeOf(ft) instanceof Type.Function fn) {
            int index = list.fnTypeParam().indexOf(typeParam);
            return index >= 0 && index < fn.parameters().size() ? fn.parameters().get(index).type() : Type.ERROR;
        }
        return ec.checker.typeOfDeclarationForEffects(param);
    }

    /** Whether {@code fn}'s calling clause names {@code param}, a callback of an enclosing function (BIP017-D5). */
    static boolean capturesCallback(BabbleParser.FunctionLiteralContext fn, BabbleParser.FnParamContext param) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null || param.ID() == null) {
            return false;
        }
        String name = param.ID().getText();
        return inCallingClause(fn, name)
                && !fn.fnParameters().fnParam().contains(param)
                && TypeAnnotations.enclosingParameter(fn.effectPrefix(), name) == param;
    }

    /** Whether {@code fn}'s calling clause names a callback of an enclosing function (BIP017-D5). */
    static boolean capturesCallbacks(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return false;
        }
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            String name = id.getText();
            boolean own = fn.fnParameters().fnParam().stream()
                    .anyMatch(p -> p.ID() != null && name.equals(p.ID().getText()));
            if (!own && TypeAnnotations.enclosingParameter(fn.effectPrefix(), name) != null) {
                return true;
            }
        }
        return false;
    }

    static boolean inCallingClause(BabbleParser.FunctionLiteralContext fn, String name) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return false;
        }
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            if (name.equals(id.getText())) {
                return true;
            }
        }
        return false;
    }

    static Set<Integer> resolveForwardedParamIndices(BabbleParser.FunctionLiteralContext fn) {
        if (fn.effectPrefix() == null || fn.effectPrefix().callingClause() == null) {
            return Set.of();
        }
        List<BabbleParser.FnParamContext> fnParams = fn.fnParameters().fnParam();
        Set<Integer> indices = new java.util.LinkedHashSet<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : fn.effectPrefix().callingClause().ID()) {
            String name = id.getText();
            for (int i = 0; i < fnParams.size(); i++) {
                if (name.equals(fnParams.get(i).ID().getText())) {
                    indices.add(i);
                    break;
                }
            }
        }
        return indices.isEmpty() ? Set.of() : Set.copyOf(indices);
    }
}
