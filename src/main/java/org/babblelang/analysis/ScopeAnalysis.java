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

    /** A warning, at a line, and at a column (from 1) when it has one, else -1. */
    public record Warning(String message, int line, int column) {
    }

    public enum PrimitiveKind {
        NONE,
        INT,
        DOUBLE,
        BOOL;

        public boolean isPrimitive() {
            return this != NONE;
        }

        public int slots() {
            return this == DOUBLE ? 2 : 1;
        }

        static PrimitiveKind of(Type type) {
            if (type == Type.INT) {
                return INT;
            }
            if (type == Type.DOUBLE) {
                return DOUBLE;
            }
            if (type == Type.BOOL) {
                return BOOL;
            }
            return NONE;
        }
    }

    public record JavaCallTarget(java.lang.reflect.Method method, boolean isStatic, boolean unwrapReceiver) {
    }

    public record JavaConstructorTarget(java.lang.reflect.Constructor<?> constructor) {
    }

    public record JavaFieldTarget(java.lang.reflect.Field field, boolean isStatic, boolean unwrapReceiver) {
    }

    public record ModuleExportTarget(int index, String name) {
    }

    public record FunctionSignature(PrimitiveKind[] parameterKinds, PrimitiveKind returnKind) {
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
    private final ParseTreeProperty<String> isBuiltins = new ParseTreeProperty<>();
    private final java.util.Set<ParserRuleContext> widensToDouble = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<String> exports = new java.util.LinkedHashSet<>();
    private final ParseTreeProperty<PrimitiveKind> expressionKinds = new ParseTreeProperty<>();
    private final ParseTreeProperty<PrimitiveKind> declarationKinds = new ParseTreeProperty<>();
    private final ParseTreeProperty<PrimitiveKind> functionReturnKinds = new ParseTreeProperty<>();
    private final java.util.Set<ParserRuleContext> definitelyDefined = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final ParseTreeProperty<JavaCallTarget> javaCallTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<JavaConstructorTarget> javaConstructorTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<JavaFieldTarget> javaFieldTargets = new ParseTreeProperty<>();
    private final java.util.Map<ParserRuleContext, Class<?>> arrayLengthSelectors = new java.util.IdentityHashMap<>();
    private final ParseTreeProperty<ModuleExportTarget> moduleExportTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<BabbleParser.FunctionLiteralContext> babbleCallFunctions = new ParseTreeProperty<>();
    private final ParseTreeProperty<FunctionSignature> babbleCallSignatures = new ParseTreeProperty<>();
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

    void setIsBuiltin(BabbleParser.IsExpressionContext ctx, String type) {
        isBuiltins.put(ctx, type);
    }

    void widenToDouble(ParserRuleContext ctx) {
        widensToDouble.add(ctx);
    }

    void setExpressionKind(ParserRuleContext ctx, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            expressionKinds.put(ctx, kind);
        }
    }

    void setDeclarationKind(ParserRuleContext decl, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            declarationKinds.put(decl, kind);
        }
    }

    void setFunctionReturnKind(BabbleParser.FunctionLiteralContext function, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            functionReturnKinds.put(function, kind);
        }
    }

    void markDefinitelyDefined(ParserRuleContext decl) {
        definitelyDefined.add(decl);
    }

    void setJavaCallTarget(ParserRuleContext ctx, JavaCallTarget target) {
        javaCallTargets.put(ctx, target);
    }

    void setJavaConstructorTarget(ParserRuleContext ctx, JavaConstructorTarget target) {
        javaConstructorTargets.put(ctx, target);
    }

    void setJavaFieldTarget(ParserRuleContext ctx, JavaFieldTarget target) {
        javaFieldTargets.put(ctx, target);
    }

    void setArrayLengthClass(ParserRuleContext ctx, Class<?> arrayClass) {
        arrayLengthSelectors.put(ctx, arrayClass);
    }

    void setModuleExportTarget(ParserRuleContext ctx, ModuleExportTarget target) {
        moduleExportTargets.put(ctx, target);
    }

    void setBabbleCallFunction(ParserRuleContext ctx, BabbleParser.FunctionLiteralContext function) {
        babbleCallFunctions.put(ctx, function);
    }

    void setBabbleCallSignature(ParserRuleContext ctx, FunctionSignature signature) {
        babbleCallSignatures.put(ctx, signature);
    }

    /**
     * The type checker's diagnostics, reported as errors before execution.
     */
    public List<Warning> typeDiagnostics() {
        return typeDiagnostics;
    }

    /**
     * The target type to check at runtime (a {@code Types} descriptor) for an {@code as}
     * cast, or null when no cast check applies.
     */
    public String checkedType(ParserRuleContext ctx) {
        return checkedTypes.get(ctx);
    }

    /** The built-in type name a {@code value is T} test checks, or null for a normal type expression. */
    public String isBuiltin(BabbleParser.IsExpressionContext ctx) {
        return isBuiltins.get(ctx);
    }

    /** Whether the value produced by this construct must be converted from int to double. */
    public boolean widensToDouble(ParserRuleContext ctx) {
        return widensToDouble.contains(ctx);
    }

    /** The primitive kind of an expression, or {@link PrimitiveKind#NONE} for references/boxed values. */
    public PrimitiveKind expressionKind(ParserRuleContext ctx) {
        PrimitiveKind kind = expressionKinds.get(ctx);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    /** The storage primitive kind of a declaration, or {@link PrimitiveKind#NONE}. */
    public PrimitiveKind declarationKind(ParserRuleContext decl) {
        PrimitiveKind kind = declarationKinds.get(decl);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    /** The return primitive kind of a function literal, or {@link PrimitiveKind#NONE}. */
    public PrimitiveKind functionReturnKind(BabbleParser.FunctionLiteralContext function) {
        PrimitiveKind kind = functionReturnKinds.get(function);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    /** Whether a {@code def} is provably initialized before any read or closure capture can observe it. */
    public boolean isDefinitelyDefined(ParserRuleContext decl) {
        return definitelyDefined.contains(decl);
    }

    public JavaCallTarget javaCallTarget(ParserRuleContext ctx) {
        return javaCallTargets.get(ctx);
    }

    public JavaConstructorTarget javaConstructorTarget(ParserRuleContext ctx) {
        return javaConstructorTargets.get(ctx);
    }

    public JavaFieldTarget javaFieldTarget(ParserRuleContext ctx) {
        return javaFieldTargets.get(ctx);
    }

    public boolean isArrayLength(ParserRuleContext ctx) {
        return arrayLengthSelectors.containsKey(ctx);
    }

    public Class<?> arrayLengthClass(ParserRuleContext ctx) {
        return arrayLengthSelectors.get(ctx);
    }

    public ModuleExportTarget moduleExportTarget(ParserRuleContext ctx) {
        return moduleExportTargets.get(ctx);
    }

    public BabbleParser.FunctionLiteralContext babbleCallFunction(ParserRuleContext ctx) {
        return babbleCallFunctions.get(ctx);
    }

    public FunctionSignature babbleCallSignature(ParserRuleContext ctx) {
        return babbleCallSignatures.get(ctx);
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
            if (nullable.question != null) {
                return null;
            }
            BabbleParser.PrimaryTypeContext primary = nullable.primaryType();
            if (primary instanceof BabbleParser.SimpleTypeContext simple) {
                return simple;
            }
            if (primary instanceof BabbleParser.ParenTypeContext paren) {
                type = paren.type();
            } else {
                return null;
            }
        }
        return null;
    }
}
