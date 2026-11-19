package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
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
                return new Type.Function(typeVarsOf(def), fn.parameters(), fn.result(), fn.effects());
            }
            return declared;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            return annotation(parameter.type(), checker.scopeOf(parameter));
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
                if (p.getParent() instanceof BabbleParser.DefExpressionContext def && def.type() == p && def.typeParameters() != null) {
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
        List<BabbleParser.NullableTypeContext> parts = type.nullableType();
        List<Type> members = new ArrayList<>(parts.size());
        for (BabbleParser.NullableTypeContext part : parts) {
            Type member = nullableAnnotation(part, scope, allowRaw);
            if (member == Type.ERROR) {
                if (!allowRaw) {
                    annotations.put(type, Type.ERROR);
                }
                return Type.ERROR;
            }
            members.add(member);
        }
        Type result = Type.union(members);
        if (!allowRaw) {
            annotations.put(type, result);
        }
        return result;
    }

    private Type nullableAnnotation(BabbleParser.NullableTypeContext nullable, ScopeNode scope, boolean allowRaw) {
        Type base = primaryAnnotation(nullable.primaryType(), scope, allowRaw);
        if (base == Type.ERROR) {
            return Type.ERROR;
        }
        return nullable.question != null ? Type.nullable(base) : base;
    }

    Type primaryAnnotation(BabbleParser.PrimaryTypeContext primary, ScopeNode scope, boolean allowRaw) {
        if (primary instanceof BabbleParser.FunctionTypeContext function) {
            EffectSet effs = checker.effectChecker != null
                    ? checker.effectChecker.parseFunctionTypePrefix(function, checker.checkingEffects()).toEffectSet()
                    : EffectSet.EMPTY;
            return new Type.Function(functionTypeParameters(function.parametersDeclaration(), scope),
                    function.type() != null ? annotation(function.type(), scope) : Type.NULLABLE_ANY,
                    effs);
        }
        if (primary instanceof BabbleParser.NullTypeContext) {
            return Type.NULL;
        }
        if (primary instanceof BabbleParser.ParenTypeContext paren) {
            return annotation(paren.type(), scope, allowRaw);
        }
        if (primary instanceof BabbleParser.CoreTypeContext core) {
            return coreType(core, scope, allowRaw);
        }
        BabbleParser.SimpleTypeContext simple = (BabbleParser.SimpleTypeContext) primary;
        Type result = simpleType(simple, scope, allowRaw);
        if (result == null) {
            checker.warn("Unknown type : " + simple.getText(), simple);
            return Type.ERROR;
        }
        return result;
    }

    private List<Type.Parameter> functionTypeParameters(BabbleParser.ParametersDeclarationContext declaration, ScopeNode scope) {
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : declaration.parameterDeclaration()) {
            Type type;
            if (parameter.type() != null) {
                type = annotation(parameter.type(), scope);
            } else {
                checker.warn("Missing type : " + parameter.ID().getText(), parameter);
                type = Type.ERROR;
            }
            EffectChecker.ParamMods pm = checker.effectChecker != null
                    ? checker.effectChecker.parseParamMods(parameter, checker.checkingEffects())
                    : EffectChecker.ParamMods.NONE;
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null,
                    pm.mut(), pm.borrow(), pm.borrow()));
        }
        return result;
    }

    /** A core type name : {@code int}, {@code string}, {@code any}, {@code void} (the type of null), {@code list(int)}... */
    private Type coreType(BabbleParser.CoreTypeContext core, ScopeNode scope, boolean allowRaw) {
        String name = core.coreTypeKeyword().getText();
        Class<?> collection = Type.collection(name);
        if (collection != null) {
            if (core.typeArguments() == null) {
                if (allowRaw) {
                    return JavaTypeBridge.rawJavaInstance(collection);
                }
                checker.warn("Missing type arguments : " + name, core);
                return Type.ERROR;
            }
            int expected = collection.getTypeParameters().length;
            List<Type> args = new ArrayList<>();
            for (BabbleParser.TypeContext argument : core.typeArguments().type()) {
                Type type = annotation(argument, scope);
                if (type == Type.ERROR) {
                    return Type.ERROR;
                }
                args.add(type);
            }
            if (args.size() != expected) {
                checker.warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), core);
                return Type.ERROR;
            }
            if (allowRaw) {
                // A cast checks the class only : its type arguments are erased (BIP011, §7.4).
                checker.analysisWarn("Unchecked cast : " + new Type.JavaInstance(collection, args), core);
            }
            return new Type.JavaInstance(collection, List.copyOf(args));
        }
        Type builtin = switch (name) {
            case "any" -> Type.ANY;
            case "void" -> Type.NULL;
            default -> Type.scalar(name);
        };
        if (builtin == null) {
            checker.warn("Unknown type : " + core.getText(), core);
            return Type.ERROR;
        }
        if (core.typeArguments() != null) {
            checker.warn("Wrong number of type arguments : expected 0, got " + core.typeArguments().type().size(), core);
            return Type.ERROR;
        }
        return builtin;
    }

    /**
     * T1 : generic type parameters, a class or interface literal bound to
     * a def (possibly reached through packages or modules), or a Java class. Null when unknown.
     */
    private Type simpleType(BabbleParser.SimpleTypeContext type, ScopeNode scope, boolean allowRaw) {
        List<String> path = type.ID().stream().map(Object::toString).toList();
        if (path.size() == 1) {
            Type.TypeVar tv = resolveTypeVar(path.get(0), type, scope);
            if (tv != null) {
                if (type.typeArguments() != null) {
                    checker.warn("Wrong number of type arguments : expected 0, got " + type.typeArguments().type().size(), type);
                    return Type.ERROR;
                }
                return tv;
            }
        }
        Object resolved = TypeChecker.resolveName(null, path.get(0), scope);
        if (path.size() == 1 && resolved == null) {
            return null;
        }
        if (resolved instanceof BabbleParser.DefExpressionContext def) {
            if (path.size() == 1) {
                TypeNode node = classNode(def.value);
                if (node != null) {
                    return instantiateBabbleType(node, type, scope, allowRaw);
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
                return null;
            }
            return null;
        }
        if (resolved instanceof List<?> packages) {
            @SuppressWarnings("unchecked")
            List<BabbleParser.PackageExpressionContext> list = (List<BabbleParser.PackageExpressionContext>) packages;
            TypeNode node = nodeInPackages(list, path, 1);
            return node != null ? instantiateBabbleType(node, type, scope, allowRaw) : null;
        }
        if (resolved == null && (path.get(0).equals("java") || path.get(0).equals("javax"))) {
            Class<?> java = JavaTypeBridge.loadClass(String.join(".", path));
            return java != null ? instantiateJavaType(java, type, scope, allowRaw) : null;
        }
        return null;
    }

    private List<Type> resolveTypeArguments(BabbleParser.SimpleTypeContext type, ScopeNode scope) {
        if (type.typeArguments() == null) {
            return null;
        }
        List<Type> args = new ArrayList<>(type.typeArguments().type().size());
        for (BabbleParser.TypeContext arg : type.typeArguments().type()) {
            Type resolved = annotation(arg, scope, false);
            if (resolved == Type.ERROR) {
                return null;
            }
            args.add(resolved);
        }
        return args;
    }

    private Type instantiateBabbleType(TypeNode node, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        int expected = node.typeParameters.size();
        BabbleParser.TypeArgumentsContext argCtx = simple.typeArguments();
        if (argCtx == null) {
            if (expected == 0) {
                return new Type.Instance(node);
            }
            if (allowRaw) {
                return rawInstance(node);
            }
            checker.warn("Missing type arguments : " + simple.getText(), simple);
            return Type.ERROR;
        }
        if (allowRaw) {
            checker.warn("Cannot cast to parameterized type : " + simple.getText(), simple);
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
        BabbleParser.TypeArgumentsContext argCtx = simple.typeArguments();
        if (argCtx == null) {
            if (expected == 0) {
                return JavaTypeBridge.fromJava(java);
            }
            if (allowRaw) {
                return JavaTypeBridge.rawJavaInstance(java);
            }
            checker.warn("Missing type arguments : " + simple.getText(), simple);
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
        if (allowRaw) {
            // A cast checks the class only : its type arguments are erased (BIP011, §7.4).
            checker.analysisWarn("Unchecked cast : " + new Type.JavaInstance(java, args), simple);
        }
        int badArg = JavaTypeBridge.checkJavaTypeArguments(java, args, checker.algebra());
        if (badArg >= 0) {
            checker.warn("Type error : type argument " + args.get(badArg) + " does not satisfy bound "
                    + JavaTypeBridge.typeParameterName(java, badArg) + " of " + java.getName(),
                    simple.typeArguments().type(badArg));
            return Type.ERROR;
        }
        return new Type.JavaInstance(java, args);
    }

    static Type.Instance rawInstance(TypeNode node) {
        if (node.typeParameters.isEmpty()) {
            return new Type.Instance(node);
        }
        return new Type.Instance(node, Collections.nCopies(node.typeParameters.size(), Type.RAW_ANY));
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
            } else if (type instanceof Type.Package pkg) {
                type = checker.members().packageMember(pkg, path.get(i));
            } else {
                return null;
            }
        }
        if (type == Type.ERROR) {
            return Type.ERROR;
        }
        return type instanceof Type.ClassValue value ? new Type.Instance(value.node()) : null;
    }

    private TypeNode classNode(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.ClassExpressionContext || value instanceof BabbleParser.InterfaceExpressionContext) {
            return checker.pass().typeNodes.get(value);
        }
        return null;
    }

    private TypeNode nodeInPackages(List<BabbleParser.PackageExpressionContext> packages, List<String> path, int index) {
        if (index >= path.size()) {
            return null;
        }
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext pkg : packages) {
            for (BabbleParser.ExpressionContext statement : pkg.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(path.get(index))) {
                    return index == path.size() - 1 ? classNode(def.value) : null;
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(path.get(index))) {
                    nested.add(inner);
                }
            }
        }
        return nested.isEmpty() ? null : nodeInPackages(nested, path, index + 1);
    }

    /** The literal argument of {@code import("…")}, or a subpackage selected from it, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.SelectorContext selector && selector.expression() != null) {
            String base = importedPackage(selector.expression());
            return base != null ? base + "." + selector.ID().getText() : null;
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
