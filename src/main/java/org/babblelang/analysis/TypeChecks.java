package org.babblelang.analysis;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;

/**
 * The second pass of the analysis, which needs the whole file : it resolves the {@code implements}
 * and {@code extends} lists statically, checks that classes implement their interfaces completely
 * and without conflicting defaults, resolves {@code super} calls, and reports private members and
 * value-class members reached in ways the rules forbid, where the receiver's class is known
 * statically.
 */
final class TypeChecks {
    /**
     * A default method : a Babble one, or a Java one. Two providers are the same default when owner
     * and name match.
     */
    record Provider(
            Object owner,
            String name,
            BabbleParser.FunctionLiteralContext function,
            boolean hasFunction,
            int minParams,
            int maxParams,
            int line,
            boolean isOverload) {
        Provider(Object owner, String name, BabbleParser.FunctionLiteralContext function) {
            this(
                    owner,
                    name,
                    function,
                    function != null,
                    0,
                    0,
                    function != null ? function.getStart().getLine() : 0,
                    false);
        }

        Provider(
                Object owner,
                String name,
                BabbleParser.FunctionLiteralContext function,
                boolean hasFunction,
                int minParams,
                int maxParams,
                int line) {
            this(owner, name, function, hasFunction, minParams, maxParams, line, false);
        }

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
    private record Requirement(Object owner, String name, Method java) {}

    private final BlockScopes pass;
    private final ScopeAnalysis analysis;

    TypeChecks(BlockScopes pass) {
        this.pass = pass;
        this.analysis = pass.analysis;
    }

    void run() {
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            ParserRuleContext definition = resolveName(assignment.name(), assignment.scope());
            if (definition instanceof BabbleParser.ForExpressionContext) {
                throw new AnalysisException(
                        "Cannot assign to loop variable : " + assignment.name(),
                        assignment.ctx().lhs.getStart().getLine());
            }
            if (definition instanceof BabbleParser.CatchClauseContext) {
                throw new AnalysisException(
                        "Cannot assign to catch parameter : " + assignment.name(),
                        assignment.ctx().lhs.getStart().getLine());
            }
            if (definition instanceof BabbleParser.BindingPatternContext
                    || definition instanceof BabbleParser.RestPatternContext
                    || definition instanceof BabbleParser.ElseClauseContext) {
                throw new AnalysisException(
                        "Cannot assign to pattern variable : " + assignment.name(),
                        assignment.ctx().lhs.getStart().getLine());
            }
            if (definition instanceof BabbleParser.DefExpressionContext def && def.VAR() == null) {
                throw new AnalysisException(
                        "Cannot assign to def : " + assignment.name(),
                        assignment.ctx().lhs.getStart().getLine());
            }
            if (ScopeAnalysis.isParamDecl(definition) && !ScopeAnalysis.isVar(definition)) {
                throw new AnalysisException(
                        "Cannot assign to parameter : " + assignment.name(),
                        assignment.ctx().lhs.getStart().getLine());
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
            analysis.hierarchy().setShape(type.ctx, type.shape());
        }
    }

    static String name(Object owner) {
        return owner instanceof TypeNode type ? type.name : ((Class<?>) owner).getName();
    }

    // ---------------------------------------------------------------- name resolution

    /** The definition a name refers to from a scope : a def or a parameter, or null. */
    static ParserRuleContext resolveName(String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext definition = s.names.get(name);
            if (definition != null) {
                return definition;
            }
        }
        return null;
    }

    /**
     * Resolves an entry of an {@code implements} or {@code extends} list, or a {@code super}
     * qualifier : a def initialised with an interface literal and never reassigned, or a Java
     * interface.
     */
    private Object resolveType(
            BabbleParser.TypeContext type, ScopeNode scope, boolean forInterface) {
        BabbleParser.SimpleTypeContext simple = ScopeAnalysis.simpleType(type);
        if (simple == null) {
            throw notStatic(type);
        }
        List<String> path = ScopeAnalysis.simpleTypePath(simple);
        ParserRuleContext definition = resolveName(path.get(0), scope);
        if (definition != null) {
            return resolveDefinition(definition, path, 1, type, forInterface, 0);
        }
        if (path.get(0).equals("java") || path.get(0).equals("javax")) {
            return loadJava(String.join(".", path), type, forInterface);
        }
        throw notStatic(type);
    }

    /**
     * The interface {@code path[index…]} names inside what a definition holds : the definition
     * itself when the path ends there, else a member of a Java package or of a module it holds.
     */
    private Object resolveDefinition(
            ParserRuleContext definition,
            List<String> path,
            int index,
            BabbleParser.TypeContext type,
            boolean forInterface,
            int depth) {
        if (!(definition instanceof BabbleParser.DefExpressionContext def)
                || ScopeAnalysis.isVar(def)
                || def.value == null
                || depth > 16) {
            throw notStatic(type);
        }
        BabbleParser.ExpressionContext value = def.value;
        if (index == path.size()
                && value instanceof BabbleParser.InterfaceExpressionContext iface) {
            return pass.typeNodes.get(iface);
        }
        String imported = importedPackage(value);
        if (imported != null && index < path.size()) {
            return loadJava(
                    imported + "." + String.join(".", path.subList(index, path.size())),
                    type,
                    forInterface);
        }
        ScopeNode scope = pass.definitionScopes.get(def);
        // def Shape = shapes.Shape, or import("shapes.ba").Shape : an export of a module.
        if (value instanceof BabbleParser.SelectorContext selector
                && selector.expression() != null) {
            ModuleInterface module = moduleOf(selector.expression(), scope, depth + 1);
            if (module != null) {
                List<String> rest = new ArrayList<>();
                rest.add(ScopeAnalysis.selectorName(selector));
                rest.addAll(path.subList(index, path.size()));
                return module.resolveType(rest, 0, type, forInterface);
            }
        }
        // def Shape = Other, an immutable alias.
        if (value instanceof BabbleParser.SelectorContext alias && alias.expression() == null) {
            ParserRuleContext target = resolveName(alias.ID().getText(), scope);
            if (target != null) {
                return resolveDefinition(target, path, index, type, forInterface, depth + 1);
            }
        }
        ModuleInterface module = moduleOf(value, scope, depth + 1);
        if (module != null && index < path.size()) {
            return module.resolveType(path, index, type, forInterface);
        }
        throw notStatic(type);
    }

    /**
     * For a file imported statically : the interface {@code path[index…]} names among its exports.
     * Only exported names are reachable.
     */
    Object resolveExport(
            List<String> path, int index, BabbleParser.TypeContext type, boolean forInterface) {
        String name = path.get(index);
        if (!analysis.exportsName(name)) {
            throw notStatic(type);
        }
        ParserRuleContext definition = pass.fileScope.names.get(name);
        if (definition != null) {
            return resolveDefinition(definition, path, index + 1, type, forInterface, 0);
        }
        throw notStatic(type);
    }

    /**
     * The module an expression holds, when it is known statically : a static import, a {@code def}
     * holding one, or an export of a module that is one.
     */
    private ModuleInterface moduleOf(
            BabbleParser.ExpressionContext expression, ScopeNode scope, int depth) {
        if (depth > 16 || expression == null) {
            return null;
        }
        if (expression instanceof BabbleParser.CallContext call) {
            return pass.modules.get(call);
        }
        if (expression instanceof BabbleParser.SelectorContext selector) {
            if (selector.expression() != null) {
                ModuleInterface module = moduleOf(selector.expression(), scope, depth + 1);
                return module != null
                        ? module.exportedModule(ScopeAnalysis.selectorName(selector), depth + 1)
                        : null;
            }
            BabbleParser.DefExpressionContext def = definitionOf(selector, scope);
            if (def != null) {
                return moduleOf(def.value, pass.definitionScopes.get(def), depth + 1);
            }
        }
        return null;
    }

    /** For a file imported statically : the module one of its exports holds, or null. */
    ModuleInterface exportedModule(String export, int depth) {
        if (!analysis.exportsName(export)) {
            return null;
        }
        ParserRuleContext definition = pass.fileScope.names.get(export);
        if (definition instanceof BabbleParser.DefExpressionContext def
                && !ScopeAnalysis.isVar(def)) {
            return moduleOf(def.value, pass.definitionScopes.get(def), depth + 1);
        }
        return null;
    }

    /** The literal argument of {@code import("…")}, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext callee
                && callee.expression() == null
                && callee.ID().getText().equals("import")
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression()
                        instanceof BabbleParser.StringContext string) {
            String name = org.babblelang.parser.StringLiterals.decode(string);
            // A file name imports a Babble module, whose members are not known statically.
            return name.endsWith(".ba") ? null : name;
        }
        return null;
    }

    private static Class<?> loadJava(
            String name, BabbleParser.TypeContext type, boolean forInterface) {
        ClassLoader loader = TypeChecks.class.getClassLoader();
        String candidate = name;
        while (true) {
            try {
                Class<?> java = Class.forName(candidate, false, loader);
                if (!java.isInterface()) {
                    throw new AnalysisException(
                            forInterface
                                    ? "Babble interfaces can only extend Java interfaces"
                                    : "Babble classes can only implement Java interfaces",
                            type.getStart().getLine());
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
        return new AnalysisException(
                "implements needs a statically known interface", type.getStart().getLine());
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
                int max = analysis.calls().parameters(member.function).length;
                int min = analysis.calls().requiredParameters(member.function);
                result.put(
                        member.name,
                        new Provider(
                                iface,
                                member.name,
                                member.function,
                                true,
                                min,
                                max,
                                member.function.getStart().getLine()));
            } else if (member.isOverload) {
                result.put(
                        member.name,
                        new Provider(
                                iface,
                                member.name,
                                null,
                                true,
                                0,
                                Integer.MAX_VALUE,
                                member.declaration.getStart().getLine(),
                                true));
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
                result.putIfAbsent(
                        method.getName(),
                        new Provider(method.getDeclaringClass(), method.getName(), null));
            }
        }
        return result;
    }

    /**
     * The defaults inherited from {@code parents} that {@code type} doesn't hide with a member of
     * its own. Two different defaults for one name are a conflict.
     */
    private Map<String, Provider> mergeDefaults(TypeNode type, List<Object> parents) {
        Map<String, Provider> result = new LinkedHashMap<>();
        Map<String, Set<String>> conflicts = new LinkedHashMap<>();
        for (Object parent : parents) {
            for (Provider provider : defaultsOf(parent).values()) {
                TypeNode.Member own = type.members.get(provider.name());
                if (own != null) {
                    if (own.isPrivate()) {
                        throw new AnalysisException(
                                own.name
                                        + " is private but "
                                        + provider.ownerName()
                                        + " provides a public "
                                        + own.name,
                                own.declaration.getStart().getLine());
                    }
                    continue;
                }
                Provider existing = result.putIfAbsent(provider.name(), provider);
                if (existing != null && !existing.equals(provider)) {
                    Set<String> owners =
                            conflicts.computeIfAbsent(provider.name(), n -> new LinkedHashSet<>());
                    owners.add(existing.ownerName());
                    owners.add(provider.ownerName());
                }
            }
        }
        for (Map.Entry<String, Set<String>> conflict : conflicts.entrySet()) {
            throw new AnalysisException(
                    "Conflicting defaults for "
                            + conflict.getKey()
                            + " : "
                            + String.join(", ", conflict.getValue()),
                    type.line());
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
                if (!member.hasFunction) {
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
                    throw new AnalysisException(
                            type.name
                                    + "."
                                    + member.name
                                    + " is private and cannot implement "
                                    + required,
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
        Set<String> overloadedMethods = new HashSet<>();
        for (TypeNode.Member member : type.members.values()) {
            if (member.function != null) {
                methods.put(member.name, member.function);
            } else if (member.isOverload) {
                overloadedMethods.add(member.name);
            }
        }
        checkJavaArity(type, defaults, methods, overloadedMethods);
    }

    /**
     * The Babble method that answers a Java method must accept every overload's arity : its
     * required parameters can't outnumber the shortest's, and its parameters can't be fewer than
     * the longest's. Overloaded Babble methods are checked with full static types in {@link
     * MemberTypes} (BIP021-D13).
     */
    private void checkJavaArity(
            TypeNode type,
            Map<String, Provider> defaults,
            Map<String, BabbleParser.FunctionLiteralContext> methods) {
        checkJavaArity(type, defaults, methods, Set.of());
    }

    private void checkJavaArity(
            TypeNode type,
            Map<String, Provider> defaults,
            Map<String, BabbleParser.FunctionLiteralContext> methods,
            Set<String> overloadedMethods) {
        Set<Class<?>> java = new LinkedHashSet<>();
        for (Object parent : type.parents) {
            javaInterfaces(parent, java);
        }
        for (Class<?> javaInterface : java) {
            for (Method method : JavaTypes.abstractMethods(javaInterface)) {
                if (overloadedMethods.contains(method.getName())) {
                    continue;
                }
                BabbleParser.FunctionLiteralContext function = methods.get(method.getName());
                String owner = type.name;
                int max;
                int min;
                int line;
                if (function == null) {
                    Provider provider = defaults.get(method.getName());
                    if (provider == null || !provider.hasFunction() || provider.isOverload()) {
                        continue;
                    }
                    owner = provider.ownerName();
                    max = provider.maxParams();
                    min = provider.minParams();
                    line = provider.line();
                } else {
                    max =
                            analysis.calls().restIndex(function) >= 0
                                    ? Integer.MAX_VALUE
                                    : analysis.calls().parameters(function).length;
                    min = analysis.calls().requiredParameters(function);
                    line = function.getStart().getLine();
                }
                int passed = method.getParameterCount();
                if (passed < min || passed > max) {
                    String takes =
                            max == Integer.MAX_VALUE
                                    ? "at least " + min
                                    : (min == max ? String.valueOf(min) : min + " to " + max);
                    throw new AnalysisException(
                            owner
                                    + "."
                                    + method.getName()
                                    + " takes "
                                    + takes
                                    + " arguments, but "
                                    + JavaTypes.signature(method)
                                    + " passes "
                                    + passed,
                            line);
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
                throw new AnalysisException(
                        name(target) + " is not " + relation + " directly by " + owner.name, line);
            }
            Provider provider = defaultsOf(target).get(member);
            if (provider == null) {
                throw noDefault(target, member, line);
            }
            pass.superProviders.put(pending.ctx(), provider);
            analysis.hierarchy().setSuperTarget(pending.ctx(), qualifier);
            analysis.hierarchy()
                    .setSuperTargetInfo(
                            pending.ctx(), superTargetInfo(owner, target, provider, member));
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
            throw new AnalysisException(
                    "Ambiguous super."
                            + member
                            + " : "
                            + String.join(", ", names)
                            + ". Write super("
                            + names.get(0)
                            + ")."
                            + member
                            + "()",
                    line);
        }
        Map.Entry<Provider, Integer> chosen = providers.entrySet().iterator().next();
        pass.superProviders.put(pending.ctx(), chosen.getKey());
        analysis.hierarchy()
                .setSuperTarget(pending.ctx(), owner.parentTypes.get(chosen.getValue()));
        analysis.hierarchy()
                .setSuperTargetInfo(
                        pending.ctx(),
                        superTargetInfo(
                                owner,
                                owner.parents.get(chosen.getValue()),
                                chosen.getKey(),
                                member));
    }

    private static LoweringTargets.SuperTargetInfo superTargetInfo(
            TypeNode owner, Object direct, Provider provider, String memberName) {
        return new LoweringTargets.SuperTargetInfo(
                owner.id,
                owner.isInterface(),
                direct instanceof TypeNode tn ? tn.id : null,
                direct instanceof Class<?> jc ? jc : null,
                provider.owner() instanceof TypeNode tn ? tn.id : null,
                provider.owner() instanceof Class<?> jc ? jc : null,
                memberName);
    }

    private AnalysisException noDefault(Object parent, String member, int line) {
        if (declares(parent, member)) {
            return new AnalysisException(name(parent) + "." + member + " has no default", line);
        }
        return new AnalysisException("No default " + member + " to call", line);
    }

    private static int indexOf(List<Object> parents, Object target) {
        for (int i = 0; i < parents.size(); i++) {
            if (parents.get(i) == target
                    || Objects.equals(parents.get(i), target) && target instanceof Class<?>) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- member access

    /**
     * Private members can only be reached as {@code this.x}, and members of a value can't be
     * assigned. Where the receiver's class is statically known, that is reported now ; otherwise
     * the runtime reports it with the same message.
     */
    private void checkAccess(BlockScopes.PendingAccess access) {
        int line = access.ctx().getStart().getLine();
        if (access.receiver() instanceof BabbleParser.ThisExpressionContext) {
            ScopeNode thisScope = BlockScopes.thisScope(access.scope());
            if (thisScope.kind == ScopeNode.Kind.INTERFACE_BODY) {
                if (!isInterfaceMember(thisScope.type, access.member())) {
                    throw new AnalysisException(
                            access.member() + " is not a member of " + thisScope.type.name, line);
                }
                if (access.assignment()) {
                    TypeNode.Member member =
                            MemberTypes.findMember(thisScope.type, access.member());
                    if (member == null || member.isMethod()) {
                        throw new AnalysisException(
                                "Cannot assign method : " + access.member(), line);
                    }
                    throw new AnalysisException(
                            "Cannot assign to def member : " + access.member(), line);
                }
            } else if (access.assignment() && thisScope.kind == ScopeNode.Kind.CLASS_BODY) {
                TypeNode.Member member = thisScope.type.members.get(access.member());
                if (member != null && member.isMethod()) {
                    throw new AnalysisException("Cannot assign method : " + access.member(), line);
                }
                if (thisScope.type.kind == ClassShape.Kind.VALUE_CLASS) {
                    throw new AnalysisException(
                            "Value members are read-only : " + access.member(), line);
                }
                if (member != null && !member.isMutable()) {
                    throw new AnalysisException(
                            "Cannot assign to def member : " + access.member(), line);
                }
            } else if (access.assignment() && thisScope.kind == ScopeNode.Kind.STATIC_BODY) {
                TypeNode.Member member = thisScope.type.statics.get(access.member());
                if (member != null) {
                    if (member.isMethod()) {
                        throw new AnalysisException(
                                "Cannot assign method : " + access.member(), line);
                    }
                    if (!member.isMutable()) {
                        throw new AnalysisException(
                                "Cannot assign to def member : " + access.member(), line);
                    }
                }
            }
            return;
        }
        ModuleInterface module = moduleOf(access.receiver(), access.scope(), 0);
        if (module != null) {
            if (access.ctx() instanceof BabbleParser.SelectorContext selector
                    && selector.safe != null) {
                return;
            }
            checkExport(access, module);
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
        if (access.assignment()) {
            if (member.isMethod()) {
                throw new AnalysisException("Cannot assign method : " + access.member(), line);
            }
            if (type.kind == ClassShape.Kind.VALUE_CLASS) {
                throw new AnalysisException(
                        "Value members are read-only : " + access.member(), line);
            }
            if (!member.isMutable()) {
                throw new AnalysisException(
                        "Cannot assign to def member : " + access.member(), line);
            }
        }
    }

    /**
     * {@code m.x} on a module known statically : a missing export, or an assignment to one, is
     * bound to fail at runtime. Like the type checker's bound-to-fail diagnostics, it is an error
     * (docs/design/bip005_modules.md §8.1).
     */
    private void checkExport(BlockScopes.PendingAccess access, ModuleInterface module) {
        String member = access.member();
        String message;
        if (!module.exports().contains(member)) {
            message = "No such export : " + member + " in " + module.name();
        } else if (access.assignment()) {
            message = "Cannot assign to an export : " + member;
        } else {
            return;
        }
        org.antlr.v4.runtime.Token token =
                access.ctx() instanceof BabbleParser.SelectorContext selector
                        ? ScopeAnalysis.selectorToken(selector)
                        : ScopeAnalysis.assignToken(
                                (BabbleParser.AssignExpressionContext) access.ctx());
        analysis.typeDiagnostic(message, token.getLine(), token.getCharPositionInLine() + 1);
    }

    /**
     * What {@code this.x} can reach in a default method : the interface's own members, inherited
     * ones included.
     */
    private boolean isInterfaceMember(TypeNode iface, String member) {
        if (iface.members.containsKey(member)) {
            return true;
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode node
                    ? isInterfaceMember(node, member)
                    : hasJavaMethod((Class<?>) parent, member)) {
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
     * The class of an expression's value when it is statically known : an object literal, a
     * construction through a name bound to a class literal, or a name bound to one of these, none
     * of them mutable.
     */
    private TypeNode staticClassOf(
            BabbleParser.ExpressionContext expression, ScopeNode scope, int depth) {
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

    /** The immutable lexical {@code def} a plain name refers to, or null. */
    private BabbleParser.DefExpressionContext definitionOf(
            BabbleParser.ExpressionContext expression, ScopeNode scope) {
        if (expression instanceof BabbleParser.SelectorContext selector
                && selector.expression() == null) {
            ParserRuleContext definition = resolveName(selector.ID().getText(), scope);
            if (definition instanceof BabbleParser.DefExpressionContext def
                    && !ScopeAnalysis.isVar(def)) {
                return def;
            }
        }
        return null;
    }
}
