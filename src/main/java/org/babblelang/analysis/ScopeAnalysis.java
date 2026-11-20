package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        BYTE,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        BOOL,
        CHAR;

        public boolean isPrimitive() {
            return this != NONE;
        }

        public int slots() {
            return (this == LONG || this == DOUBLE) ? 2 : 1;
        }

        static PrimitiveKind of(Type type) {
            if (type == Type.BYTE) {
                return BYTE;
            }
            if (type == Type.SHORT) {
                return SHORT;
            }
            if (type == Type.INT) {
                return INT;
            }
            if (type == Type.LONG) {
                return LONG;
            }
            if (type == Type.FLOAT) {
                return FLOAT;
            }
            if (type == Type.DOUBLE) {
                return DOUBLE;
            }
            if (type == Type.BOOL) {
                return BOOL;
            }
            if (type == Type.CHAR) {
                return CHAR;
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

    public record ModuleExportTarget(int index, String name, String moduleName) {
        public ModuleExportTarget(int index, String name) {
            this(index, name, null);
        }
    }

    public record FunctionSignature(PrimitiveKind[] parameterKinds, PrimitiveKind returnKind) {
    }

    public record ParentRef(String babbleTypeId, Class<?> javaClass) {
    }

    public record MemberSpec(String name, boolean hasDefault, boolean required, boolean callable, FunctionSignature declaredSignature) {
    }

    public record TypeHierarchyInfo(String id, ClassShape.Kind kind, String name, List<ParentRef> directParents,
                                    java.util.Set<String> allBabbleInterfaceIds, java.util.Set<Class<?>> allJavaInterfaces,
                                    Map<String, MemberSpec> interfaceMembers) {
    }

    public record SuperTargetInfo(String ownerTypeId, boolean ownerIsInterface,
                                  String directBabbleTypeId, Class<?> directJavaClass,
                                  String providerBabbleTypeId, Class<?> providerJavaClass,
                                  String memberName) {
    }

    public record IsTarget(String typeId, ClassShape.Kind kind) {
    }

    public record InterfaceCallTarget(String interfaceTypeId, String memberName, FunctionSignature signature, boolean guaranteedCallable) {
    }

    public enum ForKind {
        ARRAY,
        JAVA_ITERABLE,
        JAVA_ITERATOR,
        BABBLE_TO_JAVA_ITERABLE,
        BABBLE_ITERABLE,
        BABBLE_ITERATOR,
        DYNAMIC_UNION
    }

    public record ForLoopTarget(
            ForKind kind,
            Class<?> arrayClass,
            PrimitiveKind elemKind,
            String elemCheck,
            int elemWidenRank,
            InterfaceCallTarget iteratorInterfaceTarget,
            FunctionSignature hasNextSig,
            InterfaceCallTarget hasNextInterfaceTarget,
            FunctionSignature nextSig,
            InterfaceCallTarget nextInterfaceTarget
    ) {
    }

    private final ParseTreeProperty<ForLoopTarget> forLoopTargets = new ParseTreeProperty<>();

    private final ParseTreeProperty<String[]> declared = new ParseTreeProperty<>();
    private final ParseTreeProperty<String[]> parameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<Integer> requiredParameters = new ParseTreeProperty<>();
    private final ParseTreeProperty<ClassShape> shapes = new ParseTreeProperty<>();
    private final ParseTreeProperty<DefTarget> defTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<BabbleParser.TypeContext> superTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<SuperTargetInfo> superTargetInfos = new ParseTreeProperty<>();
    private final ParseTreeProperty<IsTarget> isTargets = new ParseTreeProperty<>();
    private final ParseTreeProperty<InterfaceCallTarget> interfaceCallTargets = new ParseTreeProperty<>();
    private final List<Warning> warnings = new ArrayList<>();
    private final List<Warning> typeDiagnostics = new ArrayList<>();
    private String resultType;
    private final ParseTreeProperty<String> checkedTypes = new ParseTreeProperty<>();
    private final ParseTreeProperty<String> isBuiltins = new ParseTreeProperty<>();
    private final java.util.Map<ParserRuleContext, Integer> numericWidenings = new java.util.IdentityHashMap<>();
    private final java.util.Map<ParserRuleContext, Object> resolvedLiterals = new java.util.IdentityHashMap<>();
    private final java.util.Set<String> exports = new java.util.LinkedHashSet<>();
    private final ParseTreeProperty<PrimitiveKind> expressionKinds = new ParseTreeProperty<>();
    private final java.util.Set<ParserRuleContext> expressionDeeplyImmutable = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Set<ParserRuleContext> expressionStrings = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
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
    private final Map<String, TypeHierarchyInfo> typeHierarchies = new java.util.LinkedHashMap<>();
    private AnalysisSession session;
    private List<ModuleInterface> importedModules = List.of();
    private Resolution resolution;
    private ModuleInterface moduleInterface;

    void setSession(AnalysisSession session) {
        this.session = session;
    }

    public AnalysisSession session() {
        return session;
    }

    void setImportedModules(java.util.Collection<ModuleInterface> modules) {
        this.importedModules = modules != null && !modules.isEmpty() ? List.copyOf(modules) : List.of();
    }

    public List<ModuleInterface> importedModules() {
        return importedModules;
    }

    void setTypeHierarchy(String id, TypeHierarchyInfo info) {
        if (id != null && info != null) {
            typeHierarchies.put(id, info);
        }
    }

    public Map<String, TypeHierarchyInfo> typeHierarchies() {
        return Map.copyOf(typeHierarchies);
    }

    public TypeHierarchyInfo typeHierarchy(String id) {
        if (id == null) {
            return null;
        }
        TypeHierarchyInfo local = typeHierarchies.get(id);
        if (local != null) {
            return local;
        }
        if (session != null) {
            TypeHierarchyInfo fromSession = session.typeHierarchy(id);
            if (fromSession != null) {
                return fromSession;
            }
        }
        if (!importedModules.isEmpty()) {
            java.util.Queue<ModuleInterface> queue = new java.util.ArrayDeque<>();
            java.util.Set<ModuleInterface> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (ModuleInterface mod : importedModules) {
                if (mod != null && visited.add(mod)) {
                    queue.add(mod);
                }
            }
            while (!queue.isEmpty()) {
                ModuleInterface mod = queue.poll();
                TypeHierarchyInfo fromModule = mod.typeHierarchy(id);
                if (fromModule != null) {
                    return fromModule;
                }
                for (ModuleInterface dep : mod.dependencies()) {
                    if (dep != null && visited.add(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }
        return null;
    }

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

    void setSuperTargetInfo(BabbleParser.SuperExpressionContext superExpression, SuperTargetInfo info) {
        superTargetInfos.put(superExpression, info);
    }

    void setIsTarget(BabbleParser.IsExpressionContext ctx, IsTarget target) {
        isTargets.put(ctx, target);
    }

    void setInterfaceCallTarget(BabbleParser.CallContext ctx, InterfaceCallTarget target) {
        interfaceCallTargets.put(ctx, target);
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

    void setForLoopTarget(BabbleParser.ForExpressionContext ctx, ForLoopTarget target) {
        forLoopTargets.put(ctx, target);
    }

    public ForLoopTarget forLoopTarget(BabbleParser.ForExpressionContext ctx) {
        return forLoopTargets.get(ctx);
    }

    void setIsBuiltin(BabbleParser.IsExpressionContext ctx, String type) {
        isBuiltins.put(ctx, type);
    }

    void widenNumeric(ParserRuleContext ctx, int targetRank) {
        if (targetRank >= 1 && targetRank <= 5) {
            numericWidenings.merge(ctx, targetRank, Math::max);
        }
    }

    void widenToDouble(ParserRuleContext ctx) {
        widenNumeric(ctx, 5);
    }

    void setResolvedLiteral(ParserRuleContext ctx, Object value) {
        if (value == null) {
            resolvedLiterals.remove(ctx);
        } else {
            resolvedLiterals.put(ctx, value);
        }
    }

    void setExpressionKind(ParserRuleContext ctx, PrimitiveKind kind) {
        if (kind != PrimitiveKind.NONE) {
            expressionKinds.put(ctx, kind);
        }
    }

    void markExpressionDeeplyImmutable(ParserRuleContext ctx) {
        expressionDeeplyImmutable.add(ctx);
    }

    void markExpressionString(ParserRuleContext ctx) {
        expressionStrings.add(ctx);
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

    /** Target numeric rank (1=short, 2=int, 3=long, 4=float, 5=double) if this construct widens numerically, else -1. */
    public int numericWideningRank(ParserRuleContext ctx) {
        Integer rank = numericWidenings.get(ctx);
        return rank != null ? rank : -1;
    }

    /** Whether the value produced by this construct must be converted to double. */
    public boolean widensToDouble(ParserRuleContext ctx) {
        return numericWideningRank(ctx) == 5;
    }

    /** Contextually typed or range-folded literal value, or null to use default literal parsing. */
    public Object resolvedLiteral(ParserRuleContext ctx) {
        return resolvedLiterals.get(ctx);
    }

    /** The primitive kind of an expression, or {@link PrimitiveKind#NONE} for references/boxed values. */
    public PrimitiveKind expressionKind(ParserRuleContext ctx) {
        PrimitiveKind kind = expressionKinds.get(ctx);
        return kind != null ? kind : PrimitiveKind.NONE;
    }

    /** Whether the converged static type of {@code ctx} is deeply immutable (scalars, null, frozen, deep record/entry). */
    public boolean isExpressionDeeplyImmutable(ParserRuleContext ctx) {
        return expressionDeeplyImmutable.contains(ctx);
    }

    /** Whether the converged static type of {@code ctx} is {@code string}. */
    public boolean isExpressionString(ParserRuleContext ctx) {
        return expressionStrings.contains(ctx);
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

    public SuperTargetInfo superTargetInfo(BabbleParser.SuperExpressionContext superExpression) {
        return superTargetInfos.get(superExpression);
    }

    public IsTarget isTarget(BabbleParser.IsExpressionContext ctx) {
        return isTargets.get(ctx);
    }

    public InterfaceCallTarget interfaceCallTarget(BabbleParser.CallContext ctx) {
        return interfaceCallTargets.get(ctx);
    }

    private final Map<ParserRuleContext, EffectSet> callableEffects = new HashMap<>();
    private final Map<ParserRuleContext, Type> callableTypes = new HashMap<>();
    private final Map<BabbleParser.CallContext, EffectSet> callEffects = new HashMap<>();

    void setCallableEffects(ParserRuleContext callable, EffectSet effects) {
        callableEffects.put(callable, effects);
    }

    /** The converged or declared effect summary of a function literal or class constructor. */
    public EffectSet callableEffects(ParserRuleContext callable) {
        return callableEffects.getOrDefault(callable, EffectSet.EMPTY);
    }

    void setCallableType(ParserRuleContext callable, Type type) {
        if (type != null) {
            callableTypes.put(callable, type);
        }
    }

    Type callableType(ParserRuleContext callable) {
        return callableTypes.get(callable);
    }

    void setCallEffects(BabbleParser.CallContext call, EffectSet effects) {
        callEffects.put(call, effects);
    }

    /** The resolved effect summary of a call site. */
    public EffectSet callEffects(BabbleParser.CallContext call) {
        return callEffects.getOrDefault(call, EffectSet.EMPTY);
    }

    private final Map<BabbleParser.NonNullAssertionContext, String> nonNullAssertionTargets = new HashMap<>();

    void setNonNullAssertionTarget(BabbleParser.NonNullAssertionContext ctx, String expectedType) {
        nonNullAssertionTargets.put(ctx, expectedType);
    }

    /** The non-null target type name for {@code e!}, or {@code null} if {@code e} is statically non-null. */
    public String nonNullAssertionTarget(BabbleParser.NonNullAssertionContext ctx) {
        return nonNullAssertionTargets.get(ctx);
    }

    public List<Warning> warnings() {
        return warnings;
    }

    /** Name selected by a qualified ({@code expr.memberName}) or unqualified ({@code ID}) selector. */
    public static String selectorName(BabbleParser.SelectorContext ctx) {
        return ctx.memberName() != null ? ctx.memberName().getText() : ctx.ID().getText();
    }

    /** Token of the name selected by a qualified or unqualified selector. */
    public static org.antlr.v4.runtime.Token selectorToken(BabbleParser.SelectorContext ctx) {
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

    /** Member name context of a qualified assignment ({@code expr.member = v}), or {@code null} for {@code x = v}. */
    public static BabbleParser.MemberNameContext assignMember(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? sel.memberName() : null;
    }

    /** Target name of a qualified ({@code expr.member = v}) or unqualified ({@code x = v}) assignment. */
    public static String assignName(BabbleParser.AssignExpressionContext ctx) {
        return ctx.lhs instanceof BabbleParser.SelectorContext sel ? selectorName(sel) : null;
    }

    /** Token of the target name of an assignment. */
    public static org.antlr.v4.runtime.Token assignToken(BabbleParser.AssignExpressionContext ctx) {
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

    /** True when a class parameter is marked {@code private}. */
    public static boolean paramIsPrivate(ParserRuleContext param) {
        return param instanceof BabbleParser.ClassParamContext cp && cp.PRIVATE() != null;
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

    /** Root segment of an {@code effectTarget} (either the import ID or the leading memberName). */
    public static String effectTargetRoot(BabbleParser.EffectTargetContext ctx) {
        return ctx.ID() != null ? ctx.ID().getText() : ctx.memberName(0).getText();
    }

    /** Member segments of an {@code effectTarget} (all segments after the root / import(...)). */
    public static List<String> effectTargetMembers(BabbleParser.EffectTargetContext ctx) {
        List<BabbleParser.MemberNameContext> all = ctx.memberName();
        int start = ctx.ID() != null ? 0 : 1;
        List<String> members = new ArrayList<>(Math.max(0, all.size() - start));
        for (int i = start; i < all.size(); i++) {
            members.add(all.get(i).getText());
        }
        return members;
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
