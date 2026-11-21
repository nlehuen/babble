package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type annotations : what a {@code type} of the grammar denotes (builtins, nullables, unions,
 * function types, classes and interfaces with their type arguments, Java classes, and classes
 * reached through modules and packages), the type variables generic defs and classes declare,
 * and the annotation a def or parameter carries. Resolved once per walk and cached.
 */
final class TypeAnnotations {
    private final TypeChecker checker;
    private final Map<BabbleParser.TypeContext, Type> annotations = new HashMap<>();
    private final Map<BabbleParser.DefExpressionContext, List<Type.TypeVar>> defTypeVars = new IdentityHashMap<>();
    private final Map<BabbleParser.DefExpressionContext, Type.SymbolId> defSymbolIds = new IdentityHashMap<>();

    TypeAnnotations(TypeChecker checker) {
        this.checker = checker;
    }

    Map<BabbleParser.TypeContext, Type> annotations() {
        return annotations;
    }

    /** Forgets the resolved annotations (type variables keep their identity across walks). */
    void clear() {
        annotations.clear();
        aliases.clear();
    }

    /** The identity of the type variables a generic def declares, or null. */
    Type.SymbolId symbolOf(BabbleParser.DefExpressionContext def) {
        return defSymbolIds.get(def);
    }

    /** The annotation a def or parameter declares, or null. */
    Type declaredType(ParserRuleContext declaration) {
        if (!checker.isLocal(declaration)) {
            return null;
        }
        if (!checker.inFile(declaration) && checker.pass().session != null) {
            Type frozen = checker.pass().session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            Type declared = annotation(def.type(), checker.scopeOf(def));
            if (def.typeParameters() != null && declared instanceof Type.Function fn) {
                return new Type.Function(typeVarsOf(def), fn.parameters(), fn.result(), fn.effects(),
                        fn.forwardedEffectParams(), fn.capturedCallings());
            }
            return declared;
        }
        if (ScopeAnalysis.isParamDecl(declaration) && ScopeAnalysis.paramType(declaration) != null) {
            return annotation(ScopeAnalysis.paramType(declaration), checker.scopeOf(declaration));
        }
        return null;
    }

    List<Type.TypeVar> typeVarsOf(BabbleParser.DefExpressionContext def) {
        if (def == null || def.typeParameters() == null) {
            return List.of();
        }
        return defTypeVars.computeIfAbsent(def, d -> {
            Type.SymbolId id = defSymbolIds.computeIfAbsent(d, k -> new Type.SymbolId(k.name.getText()));
            List<org.antlr.v4.runtime.tree.TerminalNode> ids = d.typeParameters().ID();
            List<Type.TypeVar> vars = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                vars.add(new Type.TypeVar(id, ids.get(i).getText(), i));
            }
            return List.copyOf(vars);
        });
    }

    List<Type.TypeVar> typeVarsOf(TypeNode node) {
        if (node == null || node.typeParameters.isEmpty()) {
            return List.of();
        }
        if (!checker.isLocal(node.ctx)) {
            List<Type.TypeVar> imported = checker.imported().typeVars(node);
            return imported != null ? imported : List.of();
        }
        if (node.ctx.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == node.ctx) {
            return typeVarsOf(def);
        }
        return List.of();
    }

    Type.TypeVar resolveTypeVar(String name, ParserRuleContext at, ScopeNode scope) {
        if (at != null) {
            for (ParserRuleContext p = at; p != null; p = p.getParent()) {
                if (p.getParent() instanceof BabbleParser.DefExpressionContext def && def.typeParameters() != null
                        && (def.type() == p || p instanceof BabbleParser.TypeAliasExpressionContext && def.value == p)) {
                    for (Type.TypeVar tv : typeVarsOf(def)) {
                        if (tv.name().equals(name)) {
                            return tv;
                        }
                    }
                    break;
                }
            }
        }
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if ((s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) && s.type != null) {
                for (Type.TypeVar tv : typeVarsOf(s.type)) {
                    if (tv.name().equals(name)) {
                        return tv;
                    }
                }
            } else if (s.kind == ScopeNode.Kind.FUNCTION
                    && s.owner.getParent() instanceof BabbleParser.DefExpressionContext def
                    && def.value == s.owner && def.typeParameters() != null) {
                for (Type.TypeVar tv : typeVarsOf(def)) {
                    if (tv.name().equals(name)) {
                        return tv;
                    }
                }
            }
        }
        return null;
    }

    Type parentAnnotation(TypeNode node, int index) {
        if (node == null || index < 0) {
            return Type.ERROR;
        }
        if (!checker.isLocal(node.ctx)) {
            Type imported = checker.imported().parentType(node, index);
            return imported != null ? imported : Type.ERROR;
        }
        if (index >= node.parentTypes.size()) {
            return Type.ERROR;
        }
        BabbleParser.TypeContext parentType = node.parentTypes.get(index);
        return annotation(parentType, checker.scopeOf(parentType));
    }

    Type annotation(BabbleParser.TypeContext type, ScopeNode scope) {
        return annotation(type, scope, false);
    }

    Type annotation(BabbleParser.TypeContext type, ScopeNode scope, boolean allowRaw) {
        if (!allowRaw) {
            Type cached = annotations.get(type);
            if (cached != null) {
                return cached;
            }
        }
        // '&' binds tighter than '|' : the operands between two '|' form one intersection (BIP022-D2).
        List<Type> members = new ArrayList<>();
        List<BabbleParser.NullableTypeContext> group = new ArrayList<>();
        for (int i = 0; i < type.getChildCount(); i++) {
            ParseTree child = type.getChild(i);
            if (child instanceof BabbleParser.NullableTypeContext part) {
                group.add(part);
            } else if (child instanceof TerminalNode terminal && terminal.getSymbol().getType() == BabbleLexer.PIPE) {
                members.add(groupAnnotation(group, scope, allowRaw));
                group = new ArrayList<>();
            }
        }
        members.add(groupAnnotation(group, scope, allowRaw));
        if (members.contains(Type.ERROR)) {
            if (!allowRaw) {
                annotations.put(type, Type.ERROR);
            }
            return Type.ERROR;
        }
        Type result = Type.union(members);
        if (!allowRaw) {
            annotations.put(type, result);
        }
        return result;
    }

    /** One operand of a union : a single type, or the intersection of several (BIP022-D1). */
    private Type groupAnnotation(List<BabbleParser.NullableTypeContext> group, ScopeNode scope, boolean allowRaw) {
        List<Type> operands = new ArrayList<>(group.size());
        for (BabbleParser.NullableTypeContext part : group) {
            Type operand = nullableAnnotation(part, scope, allowRaw);
            if (operand == Type.ERROR) {
                return Type.ERROR;
            }
            operands.add(operand);
        }
        if (operands.size() == 1) {
            return operands.get(0);
        }
        String text = intersectionText(group);
        for (int i = 0; i < operands.size(); i++) {
            Type operand = operands.get(i);
            if (Type.containsNull(operand)) {
                checker.warn("Nullable inside an intersection : " + group.get(i).getText()
                        + " (write (" + text + ")?)", group.get(i));
                return Type.ERROR;
            }
            if (!isIntersectable(operand)) {
                checker.warn("Only interfaces intersect : " + text, group.get(i));
                return Type.ERROR;
            }
            if (operand instanceof Type.JavaInstance java && java.type() == Object.class) {
                operands.set(i, Type.ANY);
            }
        }
        return Type.intersection(operands);
    }

    private static String intersectionText(List<BabbleParser.NullableTypeContext> group) {
        StringBuilder text = new StringBuilder();
        for (BabbleParser.NullableTypeContext part : group) {
            if (!text.isEmpty()) {
                text.append(" & ");
            }
            text.append(part.getText().replace("?", ""));
        }
        return text.toString();
    }

    /** Interfaces (Babble or Java, generic or not), unions of them, and {@code any} / {@code Object}, which drop out. */
    private static boolean isIntersectable(Type type) {
        if (type == Type.ANY) {
            return true;
        }
        if (type instanceof Type.Instance instance) {
            return instance.node().isInterface();
        }
        if (type instanceof Type.JavaInstance java) {
            return java.type().isInterface() || java.type() == Object.class;
        }
        if (type instanceof Type.Intersection) {
            return true;
        }
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeAnnotations::isIntersectable);
        }
        return false;
    }

    private Type nullableAnnotation(BabbleParser.NullableTypeContext nullable, ScopeNode scope, boolean allowRaw) {
        if (nullable instanceof BabbleParser.NonParenTypeContext nonParen) {
            return nonParenAnnotation(nonParen.nonParenNullableType(), scope, allowRaw);
        }
        BabbleParser.ParenTypeContext paren = (BabbleParser.ParenTypeContext) nullable;
        Type base = annotation(paren.type(), scope, allowRaw);
        if (base == Type.ERROR) {
            return Type.ERROR;
        }
        if (paren.frozen != null) {
            base = Type.frozen(base);
        }
        return paren.question != null ? Type.nullable(base) : base;
    }

    Type nonParenAnnotation(BabbleParser.NonParenNullableTypeContext primary, ScopeNode scope, boolean allowRaw) {
        if (primary instanceof BabbleParser.FunctionTypeContext function) {
            boolean interfaceMethod = isDirectInterfaceMethodType(function, scope);
            EffectSet effs = checker.effectChecker != null
                    ? checker.effectChecker.parseFunctionTypePrefix(function, interfaceMethod, checker.checkingEffects()).toEffectSet()
                    : EffectSet.EMPTY;
            List<Type.Parameter> fnParams = functionTypeParameters(function.fnTypeParameters(), scope);
            java.util.Set<Integer> forwarded = resolveForwardedParams(function.effectPrefix(), fnParams);
            return new Type.Function(List.of(), fnParams,
                    function.type() != null ? annotation(function.type(), scope) : Type.NULLABLE_ANY,
                    effs, forwarded, resolveCapturedCallings(function.effectPrefix(), fnParams).keySet());
        }
        if (primary instanceof BabbleParser.NullTypeContext) {
            return Type.NULL;
        }
        BabbleParser.SimpleTypeContext simple = (BabbleParser.SimpleTypeContext) primary;
        Type result = simpleType(simple, scope, allowRaw);
        if (result == null) {
            checker.warn("Unknown type : " + simple.typeName().getText(), simple);
            return Type.ERROR;
        }
        if (result == Type.ERROR) {
            return Type.ERROR;
        }
        if (simple.question != null) {
            result = Type.nullable(result);
        }
        if (simple.frozen != null) {
            result = Type.frozen(result);
        }
        return result;
    }

    /**
     * The names of a {@code calling} clause that aren't parameters of its own function but of an
     * enclosing function literal or function type, mapped to that parameter (a {@code FnParamContext}
     * or a {@code FnTypeParamContext}, BIP017-D5) : the clause of a function type in the enclosing
     * function's result type, or of a function type or literal in its body, may name the callbacks
     * it captures. Not its parameter types : a callback can't forward a sibling.
     */
    static java.util.Map<String, ParserRuleContext> resolveCapturedCallings(
            BabbleParser.EffectPrefixContext prefix, List<Type.Parameter> ownParams) {
        if (prefix == null || prefix.callingClause() == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, ParserRuleContext> captured = new java.util.TreeMap<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : prefix.callingClause().ID()) {
            String name = id.getText();
            if (ownParams.stream().anyMatch(p -> name.equals(p.name()))) {
                continue;
            }
            ParserRuleContext param = enclosingParameter(prefix, name);
            if (param != null) {
                captured.put(name, param);
            }
        }
        return captured;
    }

    /**
     * The parameter {@code name} of the innermost function literal or function type enclosing
     * {@code prefix} that has one (see above), or null.
     */
    static ParserRuleContext enclosingParameter(BabbleParser.EffectPrefixContext prefix, String name) {
        org.antlr.v4.runtime.tree.ParseTree child = prefix;
        org.antlr.v4.runtime.tree.ParseTree parent = prefix.getParent();
        while (parent != null) {
            if (parent instanceof BabbleParser.FunctionLiteralContext enclosing && child != enclosing.effectPrefix()) {
                if (child == enclosing.fnParameters()) {
                    return null;
                }
                for (BabbleParser.FnParamContext param : enclosing.fnParameters().fnParam()) {
                    if (param.ID() != null && name.equals(param.ID().getText())) {
                        return param;
                    }
                }
            }
            if (parent instanceof BabbleParser.FunctionTypeContext enclosing && child != enclosing.effectPrefix()) {
                if (child == enclosing.fnTypeParameters()) {
                    return null;
                }
                for (BabbleParser.FnTypeParamContext param : enclosing.fnTypeParameters().fnTypeParam()) {
                    if (param.ID() != null && name.equals(param.ID().getText())) {
                        return param;
                    }
                }
            }
            child = parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** Resolves {@code calling f} / {@code calling (f, g)} parameter names to 0-based parameter indices (BIP017). */
    static java.util.Set<Integer> resolveForwardedParams(BabbleParser.EffectPrefixContext prefix,
                                                         List<Type.Parameter> params) {
        if (prefix == null || prefix.callingClause() == null) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> indices = new java.util.LinkedHashSet<>();
        for (org.antlr.v4.runtime.tree.TerminalNode id : prefix.callingClause().ID()) {
            String name = id.getText();
            for (int i = 0; i < params.size(); i++) {
                if (name.equals(params.get(i).name())) {
                    indices.add(i);
                    break;
                }
            }
        }
        return indices.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(indices);
    }

    /** Resolves {@code typeArguments} ({@code T} or {@code (T1, T2)}) in {@code expr of typeArguments}. */
    List<Type> resolveTypeArguments(BabbleParser.TypeArgumentsContext ta, ScopeNode scope) {
        if (ta.nonParenNullableType() != null) {
            return List.of(nonParenAnnotation(ta.nonParenNullableType(), scope, false));
        }
        List<Type> args = new ArrayList<>(ta.type().size());
        for (BabbleParser.TypeContext arg : ta.type()) {
            args.add(annotation(arg, scope, false));
        }
        return args;
    }

    /** Parser rule node for the i-th type argument in {@code typeArguments} (for error locations). */
    static ParserRuleContext typeArgumentNode(BabbleParser.TypeArgumentsContext ta, int index) {
        return ta.nonParenNullableType() != null ? ta.nonParenNullableType() : ta.type(index);
    }

    private static int simpleTypeArgCount(BabbleParser.SimpleTypeContext simple) {
        if (simple.OF() == null) {
            return 0;
        }
        return simple.nonParenNullableType() != null ? 1 : simple.type().size();
    }

    private static ParserRuleContext simpleTypeArgNode(BabbleParser.SimpleTypeContext simple, int index) {
        return simple.nonParenNullableType() != null ? simple.nonParenNullableType() : simple.type(index);
    }

    private static boolean isDirectInterfaceMethodType(BabbleParser.FunctionTypeContext fn, ScopeNode scope) {
        if (scope == null || scope.kind != ScopeNode.Kind.INTERFACE_BODY) {
            return false;
        }
        ParserRuleContext p = fn.getParent();
        if (p instanceof BabbleParser.NonParenTypeContext) {
            p = p.getParent();
        }
        if (p instanceof BabbleParser.TypeContext) {
            p = p.getParent();
        }
        return p instanceof BabbleParser.DefExpressionContext def && def.value == null;
    }

    private List<Type.Parameter> functionTypeParameters(BabbleParser.FnTypeParametersContext declaration, ScopeNode scope) {
        List<Type.Parameter> result = new ArrayList<>();
        int index = 0;
        for (BabbleParser.FnTypeParamContext parameter : declaration.fnTypeParam()) {
            Type type = annotation(parameter.type(), scope);
            EffectChecker.ParamMods pm = checker.effectChecker != null
                    ? checker.effectChecker.parseParamMods(parameter, checker.checkingEffects())
                    : EffectChecker.ParamMods.NONE;
            String name = parameter.ID() != null ? parameter.ID().getText() : "p" + index;
            boolean borrow = checker.effectChecker != null && !pm.shared() && !pm.unique();
            result.add(new Type.Parameter(name, type, false,
                    pm.mut(), borrow, pm.shared(), pm.unique()));
            index++;
        }
        return result;
    }

    /**
     * T1 : the built-in names, generic type parameters, a class or interface literal bound to
     * a def (possibly reached through packages or modules), or a Java class. Null when unknown.
     */
    private Type simpleType(BabbleParser.SimpleTypeContext type, ScopeNode scope, boolean allowRaw) {
        List<String> path = ScopeAnalysis.simpleTypePath(type);
        if (path.size() == 1) {
            Type.TypeVar tv = resolveTypeVar(path.get(0), type, scope);
            if (tv != null) {
                if (type.OF() != null) {
                    checker.warn("Wrong number of type arguments : expected 0, got " + simpleTypeArgCount(type), type);
                    return Type.ERROR;
                }
                return tv;
            }
        }
        Object resolved = TypeChecker.resolveName(null, path.get(0), scope);
        if (path.size() == 1 && resolved == null) {
            if ("array".equals(path.get(0))) {
                return instantiateArrayType(type, scope);
            }
            Class<?> coreCollection = switch (path.get(0)) {
                case "list" -> java.util.List.class;
                case "set" -> java.util.Set.class;
                case "sortedset" -> java.util.SortedSet.class;
                case "map" -> java.util.Map.class;
                case "sortedmap" -> java.util.SortedMap.class;
                case "entry" -> java.util.Map.Entry.class;
                default -> null;
            };
            if (coreCollection != null) {
                return instantiateJavaType(coreCollection, type, scope, allowRaw);
            }
            Type builtin = switch (path.get(0)) {
                case "byte" -> Type.BYTE;
                case "short" -> Type.SHORT;
                case "int" -> Type.INT;
                case "long" -> Type.LONG;
                case "float" -> Type.FLOAT;
                case "double" -> Type.DOUBLE;
                case "bool" -> Type.BOOL;
                case "char" -> Type.CHAR;
                case "string" -> Type.STRING;
                case "any" -> Type.ANY;
                case "void" -> Type.NULL;
                default -> null;
            };
            if (builtin == null) {
                return null;
            }
            if (type.OF() != null) {
                checker.warn("Wrong number of type arguments : expected 0, got " + simpleTypeArgCount(type), type);
                return Type.ERROR;
            }
            return builtin;
        }
        if (resolved instanceof BabbleParser.DefExpressionContext def) {
            if (path.size() == 1 && def.value instanceof BabbleParser.TypeAliasExpressionContext) {
                Type alias = aliasOf(def);
                return alias instanceof Type.TypeAlias typeAlias ? instantiateAlias(typeAlias, type, scope, allowRaw) : alias;
            }
            if (path.size() == 1) {
                if (!checker.isReassigned(def)) {
                    TypeNode node = classNode(def.value, checker.scopeOf(def), 0);
                    if (node != null) {
                        return instantiateBabbleType(node, type, scope, allowRaw);
                    }
                }
            } else {
                String imported = importedPackage(def.value);
                if (imported != null) {
                    Class<?> java = JavaTypeBridge.loadClass(imported + "." + String.join(".", path.subList(1, path.size())));
                    return java != null ? instantiateJavaType(java, type, scope, allowRaw) : null;
                }
            }
            if (fromModule(def.value, checker.scopeOf(def), 0)) {
                Type through = typeThroughModule(checker.typeOfDeclaration(def), path, type);
                if (through == null || through == Type.ERROR) {
                    return through;
                }
                if (through instanceof Type.Instance inst) {
                    return instantiateBabbleType(inst.node(), type, scope, allowRaw);
                }
                if (through instanceof Type.TypeAlias alias) {
                    return instantiateAlias(alias, type, scope, allowRaw);
                }
                return null;
            }
            return null;
        }
        if (resolved == null && (path.get(0).equals("java") || path.get(0).equals("javax"))) {
            Class<?> java = JavaTypeBridge.loadClass(String.join(".", path));
            return java != null ? instantiateJavaType(java, type, scope, allowRaw) : null;
        }
        return null;
    }

    private List<Type> resolveTypeArguments(BabbleParser.SimpleTypeContext type, ScopeNode scope) {
        if (type.OF() == null) {
            return null;
        }
        if (type.nonParenNullableType() != null) {
            Type resolved = nonParenAnnotation(type.nonParenNullableType(), scope, false);
            return resolved == Type.ERROR ? null : List.of(resolved);
        }
        List<Type> args = new ArrayList<>(type.type().size());
        for (BabbleParser.TypeContext arg : type.type()) {
            Type resolved = annotation(arg, scope, false);
            if (resolved == Type.ERROR) {
                return null;
            }
            args.add(resolved);
        }
        return args;
    }

    private static String sourceText(ParserRuleContext ctx) {
        return ctx.getStart().getInputStream().getText(
                org.antlr.v4.runtime.misc.Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex()));
    }

    private Type instantiateArrayType(BabbleParser.SimpleTypeContext simple, ScopeNode scope) {
        if (simple.OF() == null) {
            checker.warn("Missing type arguments : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != 1) {
            checker.warn("Wrong number of type arguments : expected 1, got " + args.size(), simple);
            return Type.ERROR;
        }
        return new Type.Array(args.get(0));
    }

    /** Whether {@code simple} resolves to a type alias whose target type (ignoring nullability) is an array. */
    boolean resolvesToArrayAlias(BabbleParser.SimpleTypeContext simple, ScopeNode scope) {
        List<String> path = ScopeAnalysis.simpleTypePath(simple);
        Object resolved = TypeChecker.resolveName(null, path.get(0), scope);
        if (!(resolved instanceof BabbleParser.DefExpressionContext def)) {
            return false;
        }
        Type body = null;
        if (path.size() == 1 && def.value instanceof BabbleParser.TypeAliasExpressionContext) {
            Type alias = aliasOf(def);
            if (alias instanceof Type.TypeAlias typeAlias) {
                body = typeAlias.aliased();
            }
        } else if (fromModule(def.value, checker.scopeOf(def), 0)) {
            Type through = typeThroughModule(checker.typeOfDeclaration(def), path, simple);
            if (through instanceof Type.TypeAlias typeAlias) {
                body = typeAlias.aliased();
            }
        }
        return body != null && Type.withoutNull(body) instanceof Type.Array;
    }

    private Type instantiateBabbleType(TypeNode node, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        int expected = node.typeParameters.size();
        if (simple.OF() == null) {
            if (expected == 0) {
                return new Type.Instance(node);
            }
            if (allowRaw) {
                return rawInstance(node);
            }
            checker.warn("Missing type arguments : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        if (allowRaw) {
            checker.warn("Cannot cast to parameterized type : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != expected) {
            checker.warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), simple);
            return Type.ERROR;
        }
        return new Type.Instance(node, args);
    }

    private Type instantiateJavaType(Class<?> java, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        int expected = JavaTypeBridge.typeParameterCount(java);
        if (simple.OF() == null) {
            if (expected == 0) {
                return JavaTypeBridge.fromJava(java);
            }
            if (allowRaw) {
                return JavaTypeBridge.rawJavaInstance(java);
            }
            checker.warn("Missing type arguments : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        if (allowRaw) {
            checker.warn("Cannot cast to parameterized type : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != expected) {
            checker.warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), simple);
            return Type.ERROR;
        }
        int badArg = JavaTypeBridge.checkJavaTypeArguments(java, args, checker.algebra());
        if (badArg >= 0) {
            checker.warn("Type error : type argument " + args.get(badArg) + " does not satisfy bound "
                    + JavaTypeBridge.typeParameterName(java, badArg) + " of " + java.getName(),
                    simpleTypeArgNode(simple, badArg));
            return Type.ERROR;
        }
        return new Type.JavaInstance(java, args);
    }

    static Type.Instance rawInstance(TypeNode node) {
        if (node.typeParameters.isEmpty()) {
            return new Type.Instance(node);
        }
        return new Type.Instance(node, Collections.nCopies(node.typeParameters.size(), Type.NULLABLE_ANY));
    }

    /**
     * Whether a value is {@code import("file.ba")}, or selected from a def of such a value :
     * {@code def shapes = import("shapes.ba") ; def Circle = shapes.Circle}.
     */
    private boolean fromModule(BabbleParser.ExpressionContext value, ScopeNode scope, int depth) {
        if (value == null || depth > 16) {
            return false;
        }
        if (value instanceof BabbleParser.CallContext call) {
            return call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                    && callee.ID().getText().equals("import")
                    && call.callParameters().callParameter().size() == 1
                    && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string
                    && org.babblelang.engine.impl.Interpreter.unescape(string.getText()).endsWith(".ba");
        }
        if (value instanceof BabbleParser.SelectorContext selector) {
            if (selector.expression() != null) {
                return fromModule(selector.expression(), scope, depth + 1);
            }
            return TypeChecker.resolveName(null, selector.ID().getText(), scope) instanceof BabbleParser.DefExpressionContext def
                    && fromModule(def.value, checker.scopeOf(def), depth + 1);
        }
        return false;
    }

    /**
     * A type named through a module : {@code shapes.Circle}, {@code lib.geo.Point}, or
     * {@code Circle} after {@code def Circle = shapes.Circle}. The class or interface the path
     * ends on, as its own file types it ; error when a file on the way can't be typed (a cycle) ;
     * null when the path names no class or interface.
     */
    private Type typeThroughModule(Type type, List<String> path, ParserRuleContext at) {
        for (int i = 1; i < path.size() && type != null && type != Type.ERROR; i++) {
            if (type instanceof Type.Module module) {
                if (module.module().summary() == null && module.module().exports().contains(path.get(i))) {
                    checker.warn("Missing type : " + path.get(i) + " (import cycle with " + module.module().name() + ")", at);
                    return Type.ERROR;
                }
                type = MemberTypes.exportType(module.module(), path.get(i));
            } else {
                return null;
            }
        }
        if (type == Type.ERROR) {
            return Type.ERROR;
        }
        if (type instanceof Type.TypeAlias alias) {
            return alias;
        }
        return type instanceof Type.ClassValue value ? new Type.Instance(value.node()) : null;
    }

    private final Map<BabbleParser.DefExpressionContext, Type> aliases = new IdentityHashMap<>();
    private final java.util.Set<BabbleParser.DefExpressionContext> resolvingAliases =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * What {@code def name = type T} stands for (BIP023) : a {@link Type.TypeAlias} of {@code T},
     * resolved where the def is, generic over the def's type parameters ; an error for an alias
     * that names itself.
     */
    Type aliasOf(BabbleParser.DefExpressionContext def) {
        Type cached = aliases.get(def);
        if (cached != null) {
            return cached;
        }
        BabbleParser.TypeAliasExpressionContext alias = (BabbleParser.TypeAliasExpressionContext) def.value;
        if (!resolvingAliases.add(def)) {
            checker.warn("Recursive type alias : " + def.name.getText(), alias.aliased);
            return Type.ERROR;
        }
        Type result;
        try {
            Type aliased = annotation(alias.aliased, checker.scopeOf(def));
            result = aliased == Type.ERROR ? Type.ERROR
                    : new Type.TypeAlias(def.name.getText(), typeVarsOf(def), aliased);
        } finally {
            resolvingAliases.remove(def);
        }
        aliases.put(def, result);
        return result;
    }

    /** An annotation naming an alias : its type, with the alias's type parameters bound to the annotation's arguments. */
    private Type instantiateAlias(Type.TypeAlias alias, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        int expected = alias.typeParameters().size();
        if (simple.OF() == null) {
            if (expected == 0) {
                return alias.aliased();
            }
            if (allowRaw) {
                Map<Type.TypeVar, Type> raw = new HashMap<>();
                for (Type.TypeVar tv : alias.typeParameters()) {
                    raw.put(tv, Type.NULLABLE_ANY);
                }
                return checker.algebra().substitute(alias.aliased(), raw);
            }
            checker.warn("Missing type arguments : " + sourceText(simple), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != expected) {
            checker.warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), simple);
            return Type.ERROR;
        }
        Map<Type.TypeVar, Type> bindings = new HashMap<>();
        for (int i = 0; i < expected; i++) {
            bindings.put(alias.typeParameters().get(i), args.get(i));
        }
        return checker.algebra().substitute(alias.aliased(), bindings);
    }

    private TypeNode classNode(BabbleParser.ExpressionContext value, ScopeNode scope, int depth) {
        if (value == null || depth > 16) {
            return null;
        }
        if (value instanceof BabbleParser.ClassExpressionContext || value instanceof BabbleParser.InterfaceExpressionContext) {
            return checker.pass().typeNodes.get(value);
        }
        if (value instanceof BabbleParser.SelectorContext selector && selector.expression() == null && scope != null) {
            Object resolved = TypeChecker.resolveName(null, selector.ID().getText(), scope);
            if (resolved instanceof BabbleParser.DefExpressionContext def && !checker.isReassigned(def) && def.value != null) {
                return classNode(def.value, checker.scopeOf(def), depth + 1);
            }
        }
        return null;
    }

    /** The literal argument of {@code import("…")}, or a subpackage selected from it, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.SelectorContext selector && selector.expression() != null) {
            String base = importedPackage(selector.expression());
            return base != null ? base + "." + ScopeAnalysis.selectorName(selector) : null;
        }
        if (value instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                && callee.ID().getText().equals("import")
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
            String name = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
            // A file name imports a Babble module, whose members are not known statically.
            return name.endsWith(".ba") ? null : name;
        }
        return null;
    }
}
