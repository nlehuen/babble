package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.List;

/**
 * What the analysis computes for a file : which constructs open a scope, the names each
 * scope defines, and the shapes of classes, objects and interfaces.
 */
public final class ScopeAnalysis {
    private static final String[] NONE = new String[0];

    /** What a {@code def} defines. */
    public enum DefKind {
        /** A lexical variable. */
        LEXICAL,
        /** A member of a class or object, initialised on the instance being constructed. */
        MEMBER,
        /** A static member of a class or interface value. */
        STATIC,
        /** A default method of an interface. */
        DEFAULT,
        /** A required member of an interface : nothing happens at runtime. */
        REQUIRED
    }

    public record DefTarget(DefKind kind, int index) {
    }

    /**
     * What the type checker does (docs/design/type-system.md) : nothing, report its
     * diagnostics as warnings, or fail on them and check declared types at runtime (T9).
     */
    public enum Typecheck {
        OFF, WARN, ERROR
    }

    /** A warning, at a line, and at a column (from 1) when it has one, else -1. */
    public record Warning(String message, int line, int column) {
    }

    private final ParseTreeProperty<String[]> declared = new ParseTreeProperty<>();
    private final ParseTreeProperty<String[]> parameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<Integer> requiredParameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<ClassShape> shapes = new ParseTreeProperty<>();
    private final ParseTreeProperty<DefTarget> defTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<BabbleParser.TypeContext> superTargets = new ParseTreeProperty<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final List<Warning> typeDiagnostics = new ArrayList<>();
    private final ParseTreeProperty<String> checkedTypes = new ParseTreeProperty<>();
    private final java.util.Set<String> exports = new java.util.LinkedHashSet<>();
    private Resolution resolution;
    private ModuleInterface moduleInterface;

    void setModuleInterface(ModuleInterface moduleInterface) {
        this.moduleInterface = moduleInterface;
    }

    /** What a file importing this one knows of it : its exports, and the interfaces among them. */
    public ModuleInterface moduleInterface() {
        return moduleInterface;
    }
    private BlockScopes pass;

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

    void setParameters(BabbleParser.FunctionLiteralContext function, String[] names, int required) {
        parameters.put(function, names);
        requiredParameters.put(function, required);
    }

    void setShape(ParserRuleContext type, ClassShape shape) {
        shapes.put(type, shape);
    }

    void setDefTarget(BabbleParser.DefExpressionContext def, DefTarget target) {
        defTargets.put(def, target);
    }

    void setSuperTarget(BabbleParser.SuperExpressionContext superExpression, BabbleParser.TypeContext type) {
        superTargets.put(superExpression, type);
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

    void warn(String message, int line) {
        warn(message, line, -1);
    }

    void warn(String message, int line, int column) {
        warnings.add(new Warning(message, line, column));
    }

    void typeDiagnostic(String message, int line, int column) {
        typeDiagnostics.add(new Warning(message, line, column));
    }

    void setCheckedType(ParserRuleContext ctx, String descriptor) {
        checkedTypes.put(ctx, descriptor);
    }

    /**
     * The type checker's diagnostics : warnings in {@code warn} mode, errors in
     * {@code error} mode.
     */
    public List<Warning> typeDiagnostics() {
        return typeDiagnostics;
    }

    /**
     * In {@code error} mode, the declared type to check at runtime (a {@code Types}
     * descriptor), or null for none : for a parameter declaration, the argument ; for a
     * function literal, its result ; for a def, its value ; for an unqualified assignment,
     * the value assigned.
     */
    public String checkedType(ParserRuleContext ctx) {
        return checkedTypes.get(ctx);
    }

    /**
     * The names defined in the scope that {@code owner} opens, which is the file, a
     * function literal, a package or a block. Parameters are not included. Empty for a
     * construct that opens no scope of its own, and for class and interface bodies,
     * whose defs are members rather than names.
     */
    public String[] declared(ParserRuleContext owner) {
        String[] names = declared.get(owner);
        return names != null ? names : NONE;
    }

    /**
     * Whether a block needs a namespace of its own : it is a scope, rather than the body
     * of a function, package, class or object, and it defines names. A block that defines
     * nothing behaves the same without one.
     */
    public boolean opensScope(BabbleParser.BlockContext block) {
        String[] names = declared.get(block);
        return names != null && names.length > 0;
    }

    public String[] parameters(BabbleParser.FunctionLiteralContext function) {
        return parameters.get(function);
    }

    /** The number of parameters without a default value. */
    public int requiredParameters(BabbleParser.FunctionLiteralContext function) {
        return requiredParameters.get(function);
    }

    /** The shape of a class, object or interface expression. */
    public ClassShape shape(ParserRuleContext type) {
        return shapes.get(type);
    }

    public DefTarget defTarget(BabbleParser.DefExpressionContext def) {
        DefTarget target = defTargets.get(def);
        return target != null ? target : new DefTarget(DefKind.LEXICAL, -1);
    }

    /**
     * The interface a {@code super} call goes to : its qualifier, or for an unqualified
     * call, the entry of the implements or extends list that provides the default.
     */
    public BabbleParser.TypeContext superTarget(BabbleParser.SuperExpressionContext superExpression) {
        return superTargets.get(superExpression);
    }

    public List<Warning> warnings() {
        return warnings;
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
        return definedName(function);
    }
}
