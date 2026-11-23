package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Root analysis record for a file : declared scope names, exports, diagnostics, session/module
 * links, and four focused sub-views ({@link #lowering()}, {@link #calls()}, {@link #hierarchy()},
 * and {@link #effects()}) (BIP027-D3).
 */
public final class ScopeAnalysis {
    private static final String[] NONE = new String[0];

    /** A warning or diagnostic, at a line, and at a column (from 1) when it has one, else -1. */
    public record Warning(String message, int line, int column) {
    }

    private final ParseTreeProperty<String[]> declared = new ParseTreeProperty<>();
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
        this.importedModules = modules != null && !modules.isEmpty() ? List.copyOf(modules) : List.of();
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

    void setDeclared(ParserRuleContext owner, String[] names) {
        declared.put(owner, names);
    }

    /**
     * The names defined in the scope that {@code owner} opens, which is the file, a
     * function literal or a block. Parameters are not included. Empty for a
     * construct that opens no scope of its own, and for class and interface bodies,
     * whose defs are members rather than names.
     */
    public String[] declared(ParserRuleContext owner) {
        String[] names = declared.get(owner);
        return names != null ? names : NONE;
    }

    /**
     * Whether a block needs a namespace of its own : it is a scope, rather than the body
     * of a function, class or object, and it defines names. A block that defines
     * nothing behaves the same without one.
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
     * The static type of the file's last statement, as type diagnostics show it, or null
     * for an empty file : what {@code :type} shows in the interactive mode
     * (docs/design/bip004_repl.md §9).
     */
    public String resultType() {
        return resultType;
    }

    /** Name selected by a qualified ({@code expr.memberName}) or unqualified ({@code ID}) selector. */
    public static String selectorName(BabbleParser.SelectorContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getText() : ctx.ID().getText();
    }

    /** Token of the name selected by a qualified or unqualified selector. */
    public static Token selectorToken(BabbleParser.SelectorContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getStart() : ctx.ID().getSymbol();
    }

    /** True when the LHS of an assignment is a valid target ({@code x} or {@code expr.member}, not {@code expr?.member}). */
    public static boolean isValidAssignTarget(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel && sel.safe == null;
    }

    /** Receiver expression of a qualified assignment ({@code expr.member = v}), or {@code null} for {@code x = v}. */
    public static BabbleParser.ExpressionContext assignReceiver(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? sel.expression() : null;
    }

    /** Target name of a qualified ({@code expr.member = v}) or unqualified ({@code x = v}) assignment. */
    public static String assignName(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? selectorName(sel) : null;
    }

    /** Token of the target name of an assignment. */
    public static Token assignToken(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? selectorToken(sel) : ctx.lhs.getStart();
    }

    /** True when {@code tree} is a parameter declaration in a class or function header. */
    public static boolean isParamDecl(ParseTree tree) {
        return tree instanceof BabbleParser.ClassParamContext || tree instanceof BabbleParser.FnParamContext;
    }

    /** Parameter declarations of a class expression (empty if omitted). */
    public static List<? extends ParserRuleContext> paramsOf(BabbleParser.ClassExpressionContext ctx) {
        return ctx.classParameters() == null ? List.of() : ctx.classParameters().classParam();
    }

    /** Parameter declarations of a function literal. */
    public static List<? extends ParserRuleContext> paramsOf(BabbleParser.FunctionLiteralContext ctx) {
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

    /** Index of the first keyword-only parameter in a function parameter list (or total count if none). */
    public static int keywordOnlyStart(BabbleParser.FnParametersContext ctx) {
        int rest = restParamIndex(ctx);
        if (rest >= 0) {
            return rest + 1;
        }
        int total = ctx.fnParam().size();
        return ctx.kwSep == null ? total : total - ctx.kwParam.size();
    }

    /** Index of the first keyword-only parameter in a class parameter list (or total count if none). */
    public static int keywordOnlyStart(BabbleParser.ClassParametersContext ctx) {
        if (ctx == null) {
            return 0;
        }
        int total = ctx.classParam().size();
        return ctx.kwSep == null ? total : total - ctx.kwParam.size();
    }

    /** Index of the first keyword-only parameter in a function-type parameter list (or total count if none). */
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

    /** Whether a variadic parameter's rest token is {@code ?...} (making the element type nullable). */
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

    /** Identifier terminals of a {@code typeParameters} clause (either bare {@code ID} or parenthesized {@code typeParam} list). */
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

    /** Optional upper-bound type context of the {@code index}-th parameter in {@code typeParameters}, or null. */
    public static BabbleParser.TypeContext typeParamBound(BabbleParser.TypeParametersContext ctx, int index) {
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

    /** Optional default value expression of a class or function parameter (null for function-type parameters). */
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

    /** Named argument label on a call parameter, or {@code null} if positional. */
    public static String callParamName(BabbleParser.CallParameterContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getText() : null;
    }

    /** Path segments of a {@code simpleType} ({@code coreTypeKeyword} or {@code ID ('.' memberName)*}). */
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
     * The name a function literal or a class expression is defined with, for display, or
     * null if it is anonymous.
     */
    public static String definedName(ParserRuleContext expression) {
        if (expression.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == expression) {
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
     * Unwraps a type that names a single nominal type (with optional grouping parentheses),
     * or returns {@code null} for unions, nullable types, function types, or {@code null}.
     */
    public static BabbleParser.SimpleTypeContext simpleType(BabbleParser.TypeContext type) {
        while (type != null) {
            if (type.nullableType().size() != 1) {
                return null;
            }
            BabbleParser.NullableTypeContext nullable = type.nullableType(0);
            if (nullable instanceof BabbleParser.NonParenTypeContext nonParen) {
                if (nonParen.nonParenNullableType() instanceof BabbleParser.SimpleTypeContext simple) {
                    return simple.question != null ? null : simple;
                }
                return null;
            }
            if (nullable instanceof BabbleParser.ParenTypeContext paren) {
                if (paren.question != null) {
                    return null;
                }
                type = paren.type();
            } else {
                return null;
            }
        }
        return null;
    }
}
