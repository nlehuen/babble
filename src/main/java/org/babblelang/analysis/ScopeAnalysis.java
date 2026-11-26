package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Root analysis record for a file : declared scope names, exports, diagnostics, session/module
 * links, and four focused sub-views ({@link #lowering()}, {@link #calls()}, {@link #hierarchy()},
 * and {@link #effects()}) (BIP027-D3).
 */
public final class ScopeAnalysis {
    private static final String[] NONE = new String[0];
    private static final boolean[] NO_FINALS = new boolean[0];

    /** A warning or diagnostic, at a line, and at a column (from 1) when it has one, else -1. */
    public record Warning(String message, int line, int column) {}

    private final ParseTreeProperty<String[]> declared = new ParseTreeProperty<>();
    private final ParseTreeProperty<boolean[]> declaredFinals = new ParseTreeProperty<>();
    private final Set<String> exports = new LinkedHashSet<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final List<Warning> typeDiagnostics = new ArrayList<>();
    private String resultType;
    private AnalysisSession session;
    private List<ModuleInterface> importedModules = List.of();
    private Resolution resolution;
    private ModuleInterface moduleInterface;
    private BlockScopes pass;

    private final LoweringMetadata lowering = new LoweringMetadata();
    private final CallMetadata calls = new CallMetadata();
    private final HierarchyMetadata hierarchy = new HierarchyMetadata(this);
    private final EffectMetadata effects = new EffectMetadata();

    public LoweringMetadata lowering() {
        return lowering;
    }

    public CallMetadata calls() {
        return calls;
    }

    public HierarchyMetadata hierarchy() {
        return hierarchy;
    }

    public EffectMetadata effects() {
        return effects;
    }

    void setSession(AnalysisSession session) {
        this.session = session;
    }

    public AnalysisSession session() {
        return session;
    }

    void setImportedModules(Collection<ModuleInterface> modules) {
        this.importedModules =
                modules != null && !modules.isEmpty() ? List.copyOf(modules) : List.of();
    }

    public List<ModuleInterface> importedModules() {
        return importedModules;
    }

    void setModuleInterface(ModuleInterface moduleInterface) {
        this.moduleInterface = moduleInterface;
    }

    /** What a file importing this one knows of it : its exports, and the interfaces among them. */
    public ModuleInterface moduleInterface() {
        return moduleInterface;
    }

    void setPass(BlockScopes pass) {
        this.pass = pass;
    }

    /** The pass that produced the analysis, for a session to remember. */
    BlockScopes pass() {
        return pass;
    }

    void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    /** What each name refers to, for the compiler (phase 2). */
    public Resolution resolution() {
        return resolution;
    }

    void addExport(String name) {
        exports.add(name);
    }

    boolean exportsName(String name) {
        return exports.contains(name);
    }

    /** The names the file exports, in source order : what a module importing it can read. */
    public String[] exports() {
        return exports.toArray(NONE);
    }

    void setDeclared(ParserRuleContext owner, String[] names, boolean[] finals) {
        declared.put(owner, names);
        declaredFinals.put(owner, finals);
    }

    /**
     * The names defined in the scope that {@code owner} opens, which is the file, a function
     * literal or a block. Parameters are not included. Empty for a construct that opens no scope of
     * its own, and for class and interface bodies, whose defs are members rather than names.
     */
    public String[] declared(ParserRuleContext owner) {
        String[] names = declared.get(owner);
        return names != null ? names : NONE;
    }

    /**
     * Parallel finality flags for {@link #declared(ParserRuleContext)} ({@code true} for {@code
     * def}, {@code false} for {@code var}).
     */
    public boolean[] declaredFinals(ParserRuleContext owner) {
        boolean[] finals = declaredFinals.get(owner);
        return finals != null ? finals : NO_FINALS;
    }

    /**
     * Whether a block needs a namespace of its own : it is a scope, rather than the body of a
     * function, class or object, and it defines names. A block that defines nothing behaves the
     * same without one.
     */
    public boolean opensScope(BabbleParser.BlockContext block) {
        String[] names = declared.get(block);
        return names != null && names.length > 0;
    }

    void warn(String message, int line) {
        warn(message, line, -1);
    }

    void warn(String message, int line, int column) {
        warnings.add(new Warning(message, line, column));
    }

    public List<Warning> warnings() {
        return warnings;
    }

    void typeDiagnostic(String message, int line, int column) {
        typeDiagnostics.add(new Warning(message, line, column));
    }

    /** The type checker's diagnostics, reported as errors before execution. */
    public List<Warning> typeDiagnostics() {
        return typeDiagnostics;
    }

    void setResultType(String type) {
        resultType = type;
    }

    /**
     * The static type of the file's last statement, as type diagnostics show it, or null for an
     * empty file : what {@code :type} shows in the interactive mode (docs/design/bip004_repl.md
     * §9).
     */
    public String resultType() {
        return resultType;
    }

    /**
     * Name selected by a qualified ({@code expr.memberName}) or unqualified ({@code ID}) selector.
     */
    public static String selectorName(BabbleParser.SelectorContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getText() : ctx.ID().getText();
    }

    /** Token of the name selected by a qualified or unqualified selector. */
    public static Token selectorToken(BabbleParser.SelectorContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getStart() : ctx.ID().getSymbol();
    }

    /**
     * True when the LHS of an assignment is a valid target ({@code x} or {@code expr.member}, not
     * {@code expr?.member}).
     */
    public static boolean isValidAssignTarget(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel && sel.safe == null;
    }

    /**
     * Receiver expression of a qualified assignment ({@code expr.member = v}), or {@code null} for
     * {@code x = v}.
     */
    public static BabbleParser.ExpressionContext assignReceiver(
            BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? sel.expression() : null;
    }

    /**
     * Target name of a qualified ({@code expr.member = v}) or unqualified ({@code x = v})
     * assignment.
     */
    public static String assignName(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? selectorName(sel) : null;
    }

    /** Token of the target name of an assignment. */
    public static Token assignToken(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel
                ? selectorToken(sel)
                : ctx.lhs.getStart();
    }

    /** True when {@code tree} is a parameter declaration in a class or function header. */
    public static boolean isParamDecl(ParseTree tree) {
        return tree instanceof BabbleParser.ClassParamContext
                || tree instanceof BabbleParser.FnParamContext;
    }

    /** Parameter declarations of a class expression (empty if omitted). */
    public static List<? extends ParserRuleContext> paramsOf(
            BabbleParser.ClassExpressionContext ctx) {
        return ctx.classParameters() == null ? List.of() : ctx.classParameters().classParam();
    }

    /** Parameter declarations of a function literal. */
    public static List<? extends ParserRuleContext> paramsOf(
            BabbleParser.FunctionLiteralContext ctx) {
        return ctx.fnParameters().fnParam();
    }

    /** Index of the variadic rest parameter in a function parameter list, or {@code -1}. */
    public static int restParamIndex(BabbleParser.FnParametersContext ctx) {
        List<BabbleParser.FnParamContext> params = ctx.fnParam();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).rest != null) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the variadic rest parameter in a function-type parameter list, or {@code -1}. */
    public static int restParamIndex(BabbleParser.FnTypeParametersContext ctx) {
        List<BabbleParser.FnTypeParamContext> params = ctx.fnTypeParam();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).rest != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the first keyword-only parameter in a function parameter list (or total count if
     * none).
     */
    public static int keywordOnlyStart(BabbleParser.FnParametersContext ctx) {
        int rest = restParamIndex(ctx);
        if (rest >= 0) {
            return rest + 1;
        }
        int total = ctx.fnParam().size();
        return ctx.kwSep == null ? total : total - ctx.kwParam.size();
    }

    /**
     * Index of the first keyword-only parameter in a class parameter list (or total count if none).
     */
    public static int keywordOnlyStart(BabbleParser.ClassParametersContext ctx) {
        if (ctx == null) {
            return 0;
        }
        int total = ctx.classParam().size();
        return ctx.kwSep == null ? total : total - ctx.kwParam.size();
    }

    /**
     * Index of the first keyword-only parameter in a function-type parameter list (or total count
     * if none).
     */
    public static int keywordOnlyStart(BabbleParser.FnTypeParametersContext ctx) {
        int rest = restParamIndex(ctx);
        if (rest >= 0) {
            return rest + 1;
        }
        int total = ctx.fnTypeParam().size();
        return ctx.kwSep == null ? total : total - ctx.kwParam.size();
    }

    /** Whether a function or function-type parameter is variadic ({@code T...} / {@code T?...}). */
    public static boolean isParamRest(ParserRuleContext param) {
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.rest != null;
        }
        if (param instanceof BabbleParser.FnTypeParamContext ftp) {
            return ftp.rest != null;
        }
        return false;
    }

    /**
     * Whether a variadic parameter's rest token is {@code ?...} (making the element type nullable).
     */
    public static boolean isParamRestNullable(ParserRuleContext param) {
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.rest != null && fp.rest.getType() == BabbleLexer.Q_ELLIPSIS;
        }
        if (param instanceof BabbleParser.FnTypeParamContext ftp) {
            return ftp.rest != null && ftp.rest.getType() == BabbleLexer.Q_ELLIPSIS;
        }
        return false;
    }

    /** Whether a call argument is a spread ({@code ...e}). */
    public static boolean isSpread(BabbleParser.CallParameterContext arg) {
        return arg != null && arg.spread != null;
    }

    /**
     * Identifier terminals of a {@code typeParameters} clause (either bare {@code ID} or
     * parenthesized {@code typeParam} list).
     */
    public static List<TerminalNode> typeParamIds(BabbleParser.TypeParametersContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        if (ctx.ID() != null) {
            return List.of(ctx.ID());
        }
        List<BabbleParser.TypeParamContext> params = ctx.typeParam();
        List<TerminalNode> ids = new ArrayList<>(params.size());
        for (BabbleParser.TypeParamContext tp : params) {
            ids.add(tp.ID());
        }
        return ids;
    }

    /**
     * Optional upper-bound type context of the {@code index}-th parameter in {@code
     * typeParameters}, or null.
     */
    public static BabbleParser.TypeContext typeParamBound(
            BabbleParser.TypeParametersContext ctx, int index) {
        if (ctx == null || ctx.ID() != null) {
            return null;
        }
        List<BabbleParser.TypeParamContext> params = ctx.typeParam();
        return index >= 0 && index < params.size() ? params.get(index).type() : null;
    }

    /** Identifier terminal of a class, function, or function-type parameter. */
    public static TerminalNode paramId(ParserRuleContext param) {
        if (param instanceof BabbleParser.ClassParamContext cp) {
            return cp.ID();
        }
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.ID();
        }
        if (param instanceof BabbleParser.FnTypeParamContext ftp) {
            return ftp.ID();
        }
        throw new IllegalArgumentException("Not a parameter context: " + param);
    }

    /** Optional type annotation of a class, function, or function-type parameter. */
    public static BabbleParser.TypeContext paramType(ParserRuleContext param) {
        if (param instanceof BabbleParser.ClassParamContext cp) {
            return cp.type();
        }
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.type();
        }
        if (param instanceof BabbleParser.FnTypeParamContext ftp) {
            return ftp.type();
        }
        throw new IllegalArgumentException("Not a parameter context: " + param);
    }

    /**
     * Optional default value expression of a class or function parameter (null for function-type
     * parameters).
     */
    public static BabbleParser.ExpressionContext paramDefault(ParserRuleContext param) {
        if (param instanceof BabbleParser.ClassParamContext cp) {
            return cp.defaultValue;
        }
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.defaultValue;
        }
        return null;
    }

    /** Effect/ownership modifiers on a class, function, or function-type parameter. */
    public static List<BabbleParser.ParamModifierContext> paramModifiers(ParserRuleContext param) {
        if (param instanceof BabbleParser.ClassParamContext cp) {
            return cp.paramModifier();
        }
        if (param instanceof BabbleParser.FnParamContext fp) {
            return fp.paramModifier();
        }
        if (param instanceof BabbleParser.FnTypeParamContext ftp) {
            return ftp.paramModifier();
        }
        throw new IllegalArgumentException("Not a parameter context: " + param);
    }

    /** True when a class parameter is marked {@code def}. */
    public static boolean paramIsDef(ParserRuleContext param) {
        return param instanceof BabbleParser.ClassParamContext cp && cp.DEF() != null;
    }

    /** True when a class parameter declares a member field ({@code def} or {@code var}). */
    public static boolean paramIsMember(ParserRuleContext param) {
        return param instanceof BabbleParser.ClassParamContext cp
                && (cp.DEF() != null || cp.VAR() != null);
    }

    /**
     * True when a declaration ({@code defExpression}, {@code classParam}, or {@code fnParam}) is
     * marked {@code var}.
     */
    public static boolean isVar(ParseTree tree) {
        if (tree instanceof BabbleParser.DefExpressionContext def) {
            return def.VAR() != null;
        }
        if (tree instanceof BabbleParser.ClassParamContext cp) {
            return cp.VAR() != null;
        }
        if (tree instanceof BabbleParser.FnParamContext fp) {
            return fp.VAR() != null;
        }
        return false;
    }

    /** Named argument label on a call parameter, or {@code null} if positional. */
    public static String callParamName(BabbleParser.CallParameterContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getText() : null;
    }

    /**
     * Path segments of a {@code simpleType} ({@code coreTypeKeyword} or {@code ID ('.'
     * memberName)*}).
     */
    public static List<String> simpleTypePath(BabbleParser.SimpleTypeContext ctx) {
        BabbleParser.TypeNameContext name = ctx.typeName();
        if (name.coreTypeKeyword() != null) {
            return List.of(name.coreTypeKeyword().getText());
        }
        List<String> path = new ArrayList<>();
        path.add(name.ID().getText());
        for (BabbleParser.MemberNameContext m : name.memberName()) {
            path.add(m.getText());
        }
        return path;
    }

    /**
     * The name a function literal or a class expression is defined with, for display, or null if it
     * is anonymous.
     */
    public static String definedName(ParserRuleContext expression) {
        if (expression.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == expression) {
            return def.name.getText();
        }
        return null;
    }

    public static String functionName(BabbleParser.FunctionLiteralContext function) {
        ParserRuleContext cur = function;
        while (cur.getParent() instanceof BabbleParser.OverloadExpressionContext ov) {
            cur = ov;
        }
        return definedName(cur);
    }

    /**
     * Unwraps a type that names a single nominal type (with optional grouping parentheses), or
     * returns {@code null} for unions, nullable types, function types, or {@code null}.
     */
    public static BabbleParser.SimpleTypeContext simpleType(BabbleParser.TypeContext type) {
        while (type != null) {
            if (type.nullableType().size() != 1) {
                return null;
            }
            BabbleParser.NullableTypeContext nullable = type.nullableType(0);
            if (nullable instanceof BabbleParser.NonParenTypeContext nonParen) {
                if (nonParen.nonParenNullableType()
                        instanceof BabbleParser.SimpleTypeContext simple) {
                    return simple.nullMod != null
                                    && simple.nullMod.getType() == BabbleLexer.QUESTION
                            ? null
                            : simple;
                }
                return null;
            }
            if (nullable instanceof BabbleParser.ParenTypeContext paren) {
                if (paren.nullMod != null && paren.nullMod.getType() == BabbleLexer.QUESTION) {
                    return null;
                }
                type = paren.type();
            } else {
                return null;
            }
        }
        return null;
    }

    /**
     * Resolves {@code name} at {@code occurrence} to determine whether the binding is immutable
     * ({@code def} / prelude implicit / parameter) for {@code BIP034-I3} HIR/MIR purity.
     */
    public boolean isFinalName(ParserRuleContext occurrence, String name) {
        if (pass == null) {
            return org.babblelang.runtime.Implicits.isImplicit(name);
        }
        ScopeNode scope = enclosingScopeOf(occurrence);
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext decl = s.names.get(name);
            if (decl != null) {
                return !isVar(decl);
            }
        }
        return org.babblelang.runtime.Implicits.isImplicit(name);
    }

    /**
     * Whether {@code name} at {@code occurrence} is guaranteed initialized before the current file
     * executes (either a prelude implicit or a binding from a cleanly completed earlier REPL
     * input).
     */
    public boolean isDefinitelyDefinedFromSessionOrPrelude(
            ParserRuleContext occurrence, String name) {
        if (pass == null) {
            return org.babblelang.runtime.Implicits.isImplicit(name);
        }
        ScopeNode scope = enclosingScopeOf(occurrence);
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext decl = s.names.get(name);
            if (decl != null) {
                if (s == pass.fileScope) {
                    return false;
                }
                return s.kind == ScopeNode.Kind.FILE && session != null && session.isCleanScope(s);
            }
        }
        return org.babblelang.runtime.Implicits.isImplicit(name);
    }

    /**
     * Whether {@code name} at {@code occurrence} resolves directly to the current file's top-level
     * scope.
     */
    public boolean resolvesToFileScope(ParserRuleContext occurrence, String name) {
        if (pass == null) {
            return false;
        }
        ScopeNode scope = enclosingScopeOf(occurrence);
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext decl = s.names.get(name);
            if (decl != null) {
                return s == pass.fileScope;
            }
        }
        return false;
    }

    boolean isNonCyclicModule(ModuleInterface imported) {
        return imported != null
                && imported.summary() != null
                && !transitivelyDependsOn(imported, moduleInterface);
    }

    boolean isNonCyclicNonDynamicTarget(ModuleInterface imported) {
        return isNonCyclicModule(imported) && !imported.transitivelyHasDynamicImport();
    }

    /**
     * Whether {@code call} is a static {@code import("...")} that cannot transitively depend on
     * (and therefore cannot invoke or observe callables of) the current module ({@code BIP034-D6}).
     */
    public boolean isNonCyclicStaticImport(BabbleParser.CallContext call) {
        if (pass == null || call == null) {
            return false;
        }
        ModuleInterface imported = pass.modules.get(call);
        if (imported != null) {
            return !transitivelyDependsOn(imported, moduleInterface);
        }
        // Static Java package import `import("java...")`: no Babble module code executes.
        if (call.expression() instanceof BabbleParser.SelectorContext sel
                && sel.expression() == null
                && "import".equals(selectorName(sel))
                && call.callParameters().callParameter().size() == 1) {
            BabbleParser.CallParameterContext arg = call.callParameters().callParameter(0);
            if (arg.memberName() == null
                    && arg.spread == null
                    && arg.expression() instanceof BabbleParser.StringContext string
                    && resolveLexicalDecl(sel, "import") == null) {
                // Only treat as Java package import when it did NOT resolve to a Babble module.
                String lit = org.babblelang.parser.StringLiterals.decode(string);
                return !lit.endsWith(".ba");
            }
        }
        return false;
    }

    /**
     * Whether {@code sel} selects a public static {@code def} member on an imported (non-cyclic) or
     * same-file Babble type (e.g. {@code Interval.empty}, {@code BIP034-D6}).
     */
    public boolean isPublicStaticDefSelect(BabbleParser.SelectorContext sel) {
        return sel != null && lowering.isPublicStaticDefSelect(sel);
    }

    private ParserRuleContext resolveLexicalDecl(ParserRuleContext occurrence, String name) {
        for (ScopeNode s = enclosingScopeOf(occurrence); s != null; s = s.parent) {
            ParserRuleContext decl = s.names.get(name);
            if (decl != null) {
                return decl;
            }
        }
        return null;
    }

    private ScopeNode enclosingScopeOf(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            ScopeNode node = pass.scopeNodes.get(p);
            if (node != null) {
                return node;
            }
        }
        return pass.fileScope;
    }

    private static boolean transitivelyDependsOn(ModuleInterface start, ModuleInterface target) {
        if (start == null || target == null) {
            return false;
        }
        if (start == target) {
            return true;
        }
        java.util.ArrayDeque<ModuleInterface> work = new java.util.ArrayDeque<>();
        Set<ModuleInterface> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        work.add(start);
        visited.add(start);
        while (!work.isEmpty()) {
            ModuleInterface cur = work.poll();
            for (ModuleInterface dep : cur.dependencies()) {
                if (dep == target) {
                    return true;
                }
                if (visited.add(dep)) {
                    work.add(dep);
                }
            }
        }
        return false;
    }
}
