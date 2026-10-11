package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The second pass of the analysis, which needs the whole file : it resolves the
 * {@code implements} and {@code extends} lists statically, checks that classes implement
 * their interfaces completely and without conflicting defaults, resolves {@code super}
 * calls, and reports private members and value-class members reached in ways the rules
 * forbid, where the receiver's class is known statically.
 */
final class TypeChecks {
    /** A default method : a Babble one, or a Java one. Two providers are the same default when owner and name match. */
    record Provider(Object owner, String name, BabbleParser.FunctionLiteralContext function) {
        String ownerName() {
            return TypeChecks.name(owner);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Provider other && other.owner == owner && other.name.equals(name);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + name.hashCode();
        }
    }

    /** A member an implementing class has to provide. */
    private record Requirement(Object owner, String name, Method java) {
    }

    private final BlockScopes pass;
    private final ScopeAnalysis analysis;
    private final Set<ParserRuleContext> reassigned = new HashSet<>();

    TypeChecks(BlockScopes pass) {
        this.pass = pass;
        this.analysis = pass.analysis;
    }

    void run() {
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            ParserRuleContext definition = resolveName(assignment.name(), assignment.scope());
            if (definition != null) {
                reassigned.add(definition);
            }
        }
        for (TypeNode type : pass.types) {
            resolveParents(type);
        }
        for (TypeNode type : pass.types) {
            if (type.isInterface()) {
                defaults(type);
                checkJavaArity(type, type.defaults, Map.of());
            }
        }
        for (TypeNode type : pass.types) {
            if (!type.isInterface()) {
                checkClass(type);
            }
        }
        for (BlockScopes.PendingSuper pending : pass.supers) {
            resolveSuper(pending);
        }
        for (BlockScopes.PendingAccess access : pass.accesses) {
            checkAccess(access);
        }
        for (TypeNode type : pass.types) {
            analysis.setShape(type.ctx, type.shape());
        }
    }

    static String name(Object owner) {
        return owner instanceof TypeNode type ? type.name : ((Class<?>) owner).getName();
    }

    // ---------------------------------------------------------------- name resolution

    /** The definition a name refers to from a scope : a def or a parameter, or null. */
    private static ParserRuleContext resolveName(String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext definition = s.names.get(name);
            if (definition != null) {
                return definition;
            }
            if (s.packages.containsKey(name)) {
                return null;
            }
        }
        return null;
    }

    private static List<BabbleParser.PackageExpressionContext> resolvePackage(String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.names.containsKey(name)) {
                return null;
            }
            List<BabbleParser.PackageExpressionContext> packages = s.packages.get(name);
            if (packages != null) {
                return packages;
            }
        }
        return null;
    }

    /**
     * Resolves an entry of an {@code implements} or {@code extends} list, or a {@code super}
     * qualifier : a def initialised with an interface literal and never reassigned, possibly
     * reached through packages, or a Java interface.
     */
    private Object resolveType(BabbleParser.TypeContext type, ScopeNode scope, boolean forInterface) {
        if (!(type instanceof BabbleParser.SimpleTypeContext simple)) {
            throw notStatic(type);
        }
        List<String> path = simple.ID().stream().map(Object::toString).toList();
        ParserRuleContext definition = resolveName(path.get(0), scope);
        if (definition != null) {
            if (definition instanceof BabbleParser.DefExpressionContext def && !reassigned.contains(def)) {
                if (path.size() == 1 && def.value instanceof BabbleParser.InterfaceExpressionContext iface) {
                    return pass.typeNodes.get(iface);
                }
                String imported = importedPackage(def.value);
                if (imported != null) {
                    return loadJava(imported + "." + String.join(".", path.subList(1, path.size())), type, forInterface);
                }
            }
            throw notStatic(type);
        }
        List<BabbleParser.PackageExpressionContext> packages = resolvePackage(path.get(0), scope);
        if (packages != null) {
            return resolveInPackages(packages, path, 1, type);
        }
        if (path.get(0).equals("java") || path.get(0).equals("javax")) {
            return loadJava(String.join(".", path), type, forInterface);
        }
        throw notStatic(type);
    }

    private Object resolveInPackages(List<BabbleParser.PackageExpressionContext> packages, List<String> path, int index,
                                     BabbleParser.TypeContext type) {
        if (index >= path.size()) {
            throw notStatic(type);
        }
        String name = path.get(index);
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext pkg : packages) {
            for (BabbleParser.ExpressionContext statement : pkg.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(name)) {
                    if (index == path.size() - 1 && def.value instanceof BabbleParser.InterfaceExpressionContext iface && !reassigned.contains(def)) {
                        return pass.typeNodes.get(iface);
                    }
                    throw notStatic(type);
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(name)) {
                    nested.add(inner);
                }
            }
        }
        if (nested.isEmpty()) {
            throw notStatic(type);
        }
        return resolveInPackages(nested, path, index + 1, type);
    }

    /** The literal argument of {@code import("…")}, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                && callee.ID().getText().equals("import")
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
            return org.babblelang.engine.impl.Interpreter.unescape(string.getText());
        }
        return null;
    }

    private static Class<?> loadJava(String name, BabbleParser.TypeContext type, boolean forInterface) {
        ClassLoader loader = TypeChecks.class.getClassLoader();
        String candidate = name;
        while (true) {
            try {
                Class<?> java = Class.forName(candidate, false, loader);
                if (!java.isInterface()) {
                    throw new AnalysisException(forInterface
                            ? "Babble interfaces can only extend Java interfaces"
                            : "Babble classes can only implement Java interfaces", type.getStart().getLine());
                }
                return java;
            } catch (ClassNotFoundException e) {
                // Nested classes : java.util.Map.Entry is java.util.Map$Entry.
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) {
                    throw notStatic(type);
                }
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }

    private static AnalysisException notStatic(BabbleParser.TypeContext type) {
        return new AnalysisException("implements needs a statically known interface", type.getStart().getLine());
    }

    private void resolveParents(TypeNode type) {
        for (BabbleParser.TypeContext parentType : type.parentTypes) {
            Object parent = resolveType(parentType, type.scope, type.isInterface());
            if (parent instanceof TypeNode node && !node.isInterface()) {
                throw notStatic(parentType);
            }
            type.parents.add(parent);
        }
    }

    // ---------------------------------------------------------------- defaults and requirements

    /** The defaults an interface provides, its own hiding inherited ones. */
    private Map<String, Provider> defaults(TypeNode iface) {
        if (iface.defaults != null) {
            return iface.defaults;
        }
        if (iface.resolving) {
            throw new AnalysisException("Interface extends itself : " + iface.name, iface.line());
        }
        iface.resolving = true;
        Map<String, Provider> result = mergeDefaults(iface, iface.parents);
        for (TypeNode.Member member : iface.members.values()) {
            if (member.function != null) {
                result.put(member.name, new Provider(iface, member.name, member.function));
            }
        }
        iface.resolving = false;
        iface.defaults = result;
        return result;
    }

    private Map<String, Provider> defaultsOf(Object parent) {
        if (parent instanceof TypeNode node) {
            return defaults(node);
        }
        Map<String, Provider> result = new LinkedHashMap<>();
        for (Method method : ((Class<?>) parent).getMethods()) {
            if (method.isDefault()) {
                result.putIfAbsent(method.getName(), new Provider(method.getDeclaringClass(), method.getName(), null));
            }
        }
        return result;
    }

    /**
     * The defaults inherited from {@code parents} that {@code type} doesn't hide with a
     * member of its own. Two different defaults for one name are a conflict.
     */
    private Map<String, Provider> mergeDefaults(TypeNode type, List<Object> parents) {
        Map<String, Provider> result = new LinkedHashMap<>();
        Map<String, Set<String>> conflicts = new LinkedHashMap<>();
        for (Object parent : parents) {
            for (Provider provider : defaultsOf(parent).values()) {
                TypeNode.Member own = type.members.get(provider.name());
                if (own != null) {
                    if (own.isPrivate()) {
                        throw new AnalysisException(own.name + " is private but " + provider.ownerName() + " provides a public " + own.name,
                                own.declaration.getStart().getLine());
                    }
                    continue;
                }
                Provider existing = result.putIfAbsent(provider.name(), provider);
                if (existing != null && !existing.equals(provider)) {
                    Set<String> owners = conflicts.computeIfAbsent(provider.name(), n -> new LinkedHashSet<>());
                    owners.add(existing.ownerName());
                    owners.add(provider.ownerName());
                }
            }
        }
        for (Map.Entry<String, Set<String>> conflict : conflicts.entrySet()) {
            throw new AnalysisException("Conflicting defaults for " + conflict.getKey() + " : " + String.join(", ", conflict.getValue()), type.line());
        }
        return result;
    }

    /** Every member an implementation of {@code parent} has to provide, transitively. */
    private void requirements(Object parent, List<Requirement> result, Set<Object> seen) {
        if (!seen.add(parent)) {
            return;
        }
        if (parent instanceof TypeNode node) {
            for (TypeNode.Member member : node.members.values()) {
                if (member.function == null) {
                    result.add(new Requirement(node, member.name, null));
                }
            }
            for (Object grandParent : node.parents) {
                requirements(grandParent, result, seen);
            }
        } else {
            for (Method method : JavaTypes.abstractMethods((Class<?>) parent)) {
                result.add(new Requirement(method.getDeclaringClass(), method.getName(), method));
            }
        }
    }

    private static void javaInterfaces(Object parent, Set<Class<?>> result) {
        if (parent instanceof TypeNode node) {
            for (Object grandParent : node.parents) {
                javaInterfaces(grandParent, result);
            }
        } else {
            result.add((Class<?>) parent);
        }
    }

    /** Whether a parent provides a member of that name at all, required or default. */
    private boolean declares(Object parent, String name) {
        List<Requirement> requirements = new ArrayList<>();
        requirements(parent, requirements, new HashSet<>());
        return requirements.stream().anyMatch(r -> r.name().equals(name));
    }

    private void checkClass(TypeNode type) {
        Map<String, Provider> defaults = mergeDefaults(type, type.parents);

        List<Requirement> requirements = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object parent : type.parents) {
            requirements(parent, requirements, seen);
        }
        for (Requirement requirement : requirements) {
            String required = name(requirement.owner()) + "." + requirement.name();
            TypeNode.Member member = type.members.get(requirement.name());
            if (member != null) {
                if (member.isPrivate()) {
                    throw new AnalysisException(type.name + "." + member.name + " is private and cannot implement " + required,
                            member.declaration.getStart().getLine());
                }
                // A Java method needs a Babble method : a field holding a function doesn't count.
                if (requirement.java() == null || member.isMethod()) {
                    continue;
                }
            } else if (defaults.containsKey(requirement.name())) {
                continue;
            }
            throw new AnalysisException(type.name + " does not implement " + required, type.line());
        }

        Map<String, BabbleParser.FunctionLiteralContext> methods = new LinkedHashMap<>();
        for (TypeNode.Member member : type.members.values()) {
            if (member.function != null) {
                methods.put(member.name, member.function);
            }
        }
        checkJavaArity(type, defaults, methods);
    }

    /**
     * The Babble method that answers a Java method must accept every overload's arity :
     * its required parameters can't outnumber the shortest's, and its parameters can't be
     * fewer than the longest's.
     */
    private void checkJavaArity(TypeNode type, Map<String, Provider> defaults, Map<String, BabbleParser.FunctionLiteralContext> methods) {
        Set<Class<?>> java = new LinkedHashSet<>();
        for (Object parent : type.parents) {
            javaInterfaces(parent, java);
        }
        for (Class<?> javaInterface : java) {
            for (Method method : JavaTypes.abstractMethods(javaInterface)) {
                BabbleParser.FunctionLiteralContext function = methods.get(method.getName());
                String owner = type.name;
                if (function == null) {
                    Provider provider = defaults.get(method.getName());
                    if (provider == null || provider.function() == null) {
                        continue;
                    }
                    function = provider.function();
                    owner = provider.ownerName();
                }
                int max = analysis.parameters(function).length;
                int min = analysis.requiredParameters(function);
                int passed = method.getParameterCount();
                if (passed < min || passed > max) {
                    String takes = min == max ? String.valueOf(min) : min + " to " + max;
                    throw new AnalysisException(owner + "." + method.getName() + " takes " + takes + " arguments, but "
                            + JavaTypes.signature(method) + " passes " + passed, function.getStart().getLine());
                }
            }
        }
    }

    // ---------------------------------------------------------------- super

    private void resolveSuper(BlockScopes.PendingSuper pending) {
        TypeNode owner = pending.owner();
        String member = pending.member();
        int line = pending.ctx().getStart().getLine();
        BabbleParser.TypeContext qualifier = pending.ctx().qualifier;
        String relation = owner.isInterface() ? "extended" : "implemented";

        if (qualifier != null) {
            Object target = resolveType(qualifier, pending.scope(), owner.isInterface());
            int index = indexOf(owner.parents, target);
            if (index < 0) {
                throw new AnalysisException(name(target) + " is not " + relation + " directly by " + owner.name, line);
            }
            if (!defaultsOf(target).containsKey(member)) {
                throw noDefault(target, member, line);
            }
            analysis.setSuperTarget(pending.ctx(), qualifier);
            return;
        }

        Map<Provider, Integer> providers = new LinkedHashMap<>();
        for (int i = 0; i < owner.parents.size(); i++) {
            Provider provider = defaultsOf(owner.parents.get(i)).get(member);
            if (provider != null) {
                providers.putIfAbsent(provider, i);
            }
        }
        if (providers.isEmpty()) {
            for (Object parent : owner.parents) {
                if (declares(parent, member)) {
                    throw noDefault(parent, member, line);
                }
            }
            throw new AnalysisException("No default " + member + " to call", line);
        }
        if (providers.size() > 1) {
            List<String> names = new ArrayList<>();
            for (int index : providers.values()) {
                names.add(owner.parentTypes.get(index).getText());
            }
            throw new AnalysisException("Ambiguous super." + member + " : " + String.join(", ", names)
                    + ". Write super(" + names.get(0) + ")." + member + "()", line);
        }
        analysis.setSuperTarget(pending.ctx(), owner.parentTypes.get(providers.values().iterator().next()));
    }

    private AnalysisException noDefault(Object parent, String member, int line) {
        if (declares(parent, member)) {
            return new AnalysisException(name(parent) + "." + member + " has no default", line);
        }
        return new AnalysisException("No default " + member + " to call", line);
    }

    private static int indexOf(List<Object> parents, Object target) {
        for (int i = 0; i < parents.size(); i++) {
            if (parents.get(i) == target || Objects.equals(parents.get(i), target) && target instanceof Class<?>) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- member access

    /**
     * Private members can only be reached as {@code this.x}, and members of a value can't be
     * assigned. Where the receiver's class is statically known, that is reported now ;
     * otherwise the runtime reports it with the same message.
     */
    private void checkAccess(BlockScopes.PendingAccess access) {
        int line = access.ctx().getStart().getLine();
        if (access.receiver() instanceof BabbleParser.ThisExpressionContext) {
            ScopeNode thisScope = BlockScopes.thisScope(access.scope());
            if (thisScope.kind == ScopeNode.Kind.INTERFACE_BODY && !isInterfaceMember(thisScope.type, access.member())) {
                throw new AnalysisException(access.member() + " is not a member of " + thisScope.type.name, line);
            }
            if (access.assignment() && thisScope.kind == ScopeNode.Kind.CLASS_BODY && thisScope.type.kind == ClassShape.Kind.VALUE_CLASS) {
                throw new AnalysisException("Value members are read-only : " + access.member(), line);
            }
            return;
        }
        TypeNode type = staticClassOf(access.receiver(), access.scope(), 0);
        if (type == null) {
            return;
        }
        TypeNode.Member member = type.members.get(access.member());
        if (member == null) {
            return;
        }
        if (member.isPrivate()) {
            throw new AnalysisException(access.member() + " is private", line);
        }
        if (access.assignment() && type.kind == ClassShape.Kind.VALUE_CLASS) {
            throw new AnalysisException("Value members are read-only : " + access.member(), line);
        }
    }

    /** What {@code this.x} can reach in a default method : the interface's own members, inherited ones included. */
    private boolean isInterfaceMember(TypeNode iface, String member) {
        if (iface.members.containsKey(member)) {
            return true;
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode node ? isInterfaceMember(node, member) : hasJavaMethod((Class<?>) parent, member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasJavaMethod(Class<?> java, String name) {
        for (Method method : java.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The class of an expression's value when it is statically known : an object literal,
     * a construction through a name bound to a class literal, or a name bound to one of
     * these, none of them reassigned.
     */
    private TypeNode staticClassOf(BabbleParser.ExpressionContext expression, ScopeNode scope, int depth) {
        if (depth > 8) {
            return null;
        }
        if (expression instanceof BabbleParser.ObjectExpressionContext) {
            return pass.typeNodes.get(expression);
        }
        if (expression instanceof BabbleParser.CallContext call) {
            BabbleParser.ExpressionContext callee = call.expression();
            if (callee instanceof BabbleParser.ClassExpressionContext) {
                return pass.typeNodes.get(callee);
            }
            BabbleParser.DefExpressionContext def = definitionOf(callee, scope);
            if (def != null && def.value instanceof BabbleParser.ClassExpressionContext) {
                return pass.typeNodes.get(def.value);
            }
            return null;
        }
        BabbleParser.DefExpressionContext def = definitionOf(expression, scope);
        if (def != null && def.value != null) {
            return staticClassOf(def.value, pass.definitionScopes.get(def), depth + 1);
        }
        return null;
    }

    /** The lexical def a plain name refers to, if it is never reassigned. */
    private BabbleParser.DefExpressionContext definitionOf(BabbleParser.ExpressionContext expression, ScopeNode scope) {
        if (expression instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            ParserRuleContext definition = resolveName(selector.ID().getText(), scope);
            if (definition instanceof BabbleParser.DefExpressionContext def && !reassigned.contains(def)) {
                return def;
            }
        }
        return null;
    }
}
