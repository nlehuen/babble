package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Member resolution : the type of {@code receiver.name} read or assigned, on instances (with
 * their type arguments and inherited defaults), classes, modules, and Java classes,
 * objects and arrays, reporting what the runtime would refuse ({@code No such member},
 * {@code is private}, {@code Not a scope}).
 */
final class MemberTypes {
    private final TypeChecker checker;

    MemberTypes(TypeChecker checker) {
        this.checker = checker;
    }

    record ResolvedMember(TypeNode owner, TypeNode.Member member, Map<Type.TypeVar, Type> env) {
    }

    /** {@code receiver.name}, read. */
    Type member(Type receiver, String name, boolean own, ParserRuleContext at, BabbleParser.ExpressionContext receiverExpression) {
        return member(receiver, name, own, at, receiverExpression.getText());
    }

    /** {@code receiver.name}, read, given the receiver's source text. */
    Type member(Type receiver, String name, boolean own, ParserRuleContext at, String receiverText) {
        if (TypeChecker.isUnresolved(receiver)) {
            return Type.ERROR;
        }
        if (receiver instanceof Type.Union union) {
            if (Type.containsNull(union)) {
                checker.warn("Not a scope : " + receiverText, at);
                return Type.ERROR;
            }
            List<Type> memberTypes = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                Type resolved = member(member, name, own, at, receiverText);
                if (resolved == Type.ERROR) {
                    return Type.ERROR;
                }
                memberTypes.add(resolved);
            }
            return Type.union(memberTypes);
        }
        if (receiver instanceof Type.Frozen frozen) {
            Type inner = member(frozen.of(), name, own, at, receiverText);
            if (inner == Type.ERROR || inner instanceof Type.Function || inner instanceof Type.JavaMethod) {
                return inner;
            }
            return Type.frozen(inner);
        }
        if (receiver instanceof Type.Instance instance) {
            ResolvedMember resolved = findMember(instance, name);
            if (resolved != null) {
                if (resolved.member().isPrivate() && !own) {
                    checker.warn(name + " is private", at);
                    return Type.ERROR;
                }
                return checker.algebra().substitute(checker.memberType(resolved.owner(), resolved.member()), resolved.env());
            }
            Type inherited = inheritedMember(instance, name);
            if (inherited != null) {
                return inherited;
            }
            checker.warn("No such member : " + name, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.ClassValue value) {
            TypeNode.Member member = value.node().statics.get(name);
            if (member != null) {
                if (member.isPrivate() && !own) {
                    checker.warn(name + " is private", at);
                    return Type.ERROR;
                }
                return checker.memberType(value.node(), member);
            }
            checker.warn("No such member : " + name, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.Module module) {
            if (module.module().summary() == null && module.module().exports().contains(name)) {
                checker.warn("Missing type : " + name + " (import cycle with " + module.module().name() + ")", at);
                return Type.ERROR;
            }
            // A missing export is TypeChecks' to report.
            Type export = exportType(module.module(), name);
            return export != null ? export : Type.ERROR;
        }
        if (receiver instanceof Type.JavaPackage pkg) {
            String qualified = pkg.name() + "." + name;
            Class<?> java = JavaTypeBridge.loadTopLevel(qualified);
            return java != null ? new Type.JavaClassValue(java) : new Type.JavaPackage(qualified);
        }
        if (receiver instanceof Type.CoreCollection cc) {
            Class<?> javaClass = switch (cc.kind()) {
                case "list" -> java.util.List.class;
                case "set" -> java.util.Set.class;
                case "sortedset" -> java.util.SortedSet.class;
                case "map" -> java.util.Map.class;
                case "sortedmap" -> java.util.SortedMap.class;
                case "entry" -> java.util.Map.Entry.class;
                default -> null;
            };
            if (javaClass != null) {
                Type member = javaMember(javaClass, List.of(), name, true, at);
                if (member instanceof Type.JavaMethod jm && !cc.typeArguments().isEmpty()) {
                    return new Type.JavaMethod(jm.owner(), jm.typeArguments(), jm.name(), cc.typeArguments());
                }
                return member;
            }
        }
        if (receiver instanceof Type.JavaClassValue value) {
            Class<?> memberClass = JavaTypeBridge.publicMemberClass(value.type(), name);
            if (memberClass != null) {
                return new Type.JavaClassValue(memberClass);
            }
            Type member = javaMember(value.type(), List.of(), name, true, at);
            if (member instanceof Type.JavaMethod jm && !value.typeArguments().isEmpty()) {
                return new Type.JavaMethod(jm.owner(), jm.typeArguments(), jm.name(), value.typeArguments());
            }
            return member;
        }
        if (receiver instanceof Type.JavaInstance instance) {
            return javaMember(instance.type(), instance.typeArguments(), name, false, at);
        }
        if (receiver == Type.STRING) {
            return javaMember(String.class, List.of(), name, false, at);
        }
        if (receiver instanceof Type.JavaArray array) {
            if ("length".equals(name)) {
                return Type.INT;
            }
            return javaMember(array.javaClass(), List.of(), name, false, at);
        }
        // The 8 primitives, null, functions, any : memberless.
        checker.warn("Not a scope : " + receiverText, at);
        return Type.ERROR;
    }

    /** What {@code receiver.name = value} must assign : the member's declared or closed type, or null if inferred locally. */
    Type assignedMemberType(Type receiver, String name, boolean own, ParserRuleContext at) {
        if (receiver instanceof Type.Frozen) {
            checker.warn("Cannot assign to field of frozen value", at);
            return null;
        }
        TypeNode ownerNode = null;
        TypeNode.Member member = null;
        Map<Type.TypeVar, Type> env = Map.of();
        Type.Instance receiverInstance = null;
        if (receiver instanceof Type.Instance instance) {
            receiverInstance = instance;
            ResolvedMember resolved = findMember(instance, name);
            if (resolved != null) {
                ownerNode = resolved.owner();
                member = resolved.member();
                env = resolved.env();
            } else if (inheritedMember(instance, name) == null) {
                checker.warn("No such member : " + name, at);
            }
        } else if (receiver instanceof Type.ClassValue value) {
            ownerNode = value.node();
            member = value.node().statics.get(name);
            if (member == null) {
                checker.warn("No such member : " + name, at);
            }
        } else if (!(receiver instanceof Type.Module || receiver instanceof Type.JavaPackage
                || receiver instanceof Type.JavaClassValue || receiver instanceof Type.JavaInstance
                || receiver instanceof Type.JavaArray || TypeChecker.isUnresolved(receiver))) {
            String receiverText = at instanceof BabbleParser.AssignExpressionContext assign
                    ? ScopeAnalysis.assignReceiver(assign).getText() : name;
            checker.warn("Not a scope : " + receiverText, at);
        }
        if (member == null) {
            return null;
        }
        if (member.isPrivate() && !own) {
            checker.warn(name + " is private", at);
            return null;
        }
        if (!checker.isLocal(member.declaration) || (!checker.inFile(member.declaration) && checker.pass().session != null)) {
            Type closed = checker.memberType(ownerNode, member);
            return closed == Type.ERROR ? null : checker.algebra().substitute(closed, env);
        }
        if (ScopeAnalysis.isParamDecl(member.declaration)) {
            Type type = checker.typeOfDeclaration(member.declaration);
            return type == Type.ERROR ? null : checker.algebra().substitute(type, env);
        }
        if (member.hasFunction) {
            return checker.algebra().substitute(checker.memberType(ownerNode, member), env);
        }
        Type declared = checker.annotator().declaredType(member.declaration);
        if (declared != null) {
            return checker.algebra().substitute(declared, env);
        }
        if (receiverInstance != null && !receiverInstance.node().typeParameters.isEmpty() && !own) {
            Type closed = checker.memberType(ownerNode, member);
            if (closed != Type.ERROR && closed.hasTypeVar()) {
                return checker.algebra().substitute(closed, env);
            }
        }
        return null;
    }

    static TypeNode.Member assignedBabbleMember(Type receiver, String name, boolean own) {
        TypeNode.Member member = null;
        if (receiver instanceof Type.Instance instance) {
            member = findMember(instance.node(), name);
        } else if (receiver instanceof Type.ClassValue value) {
            member = value.node().statics.get(name);
        }
        return member != null && (!member.isPrivate() || own) ? member : null;
    }

    /** A member of a class or object, or for an interface, of it or the interfaces it extends. */
    static TypeNode.Member findMember(TypeNode node, String name) {
        TypeNode.Member member = node.members.get(name);
        if (member != null || !node.isInterface()) {
            return member;
        }
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                member = findMember(parentNode, name);
                if (member != null) {
                    return member;
                }
            }
        }
        return null;
    }

    ResolvedMember findMember(Type.Instance instance, String name) {
        TypeNode node = instance.node();
        Map<Type.TypeVar, Type> env = checker.algebra().instanceEnv(instance);
        TypeNode.Member member = node.members.get(name);
        if (member != null || !node.isInterface()) {
            return member != null ? new ResolvedMember(node, member, env) : null;
        }
        for (int i = 0; i < node.parents.size(); i++) {
            Type parent = checker.algebra().substitute(checker.annotator().parentAnnotation(node, i), env);
            if (parent instanceof Type.Instance parentInst) {
                ResolvedMember found = findMember(parentInst, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * A member the class or interface gets from what it implements or extends : a Babble
     * default, or any method of a Java interface. Null when there is none.
     */
    Type inheritedMember(Type.Instance instance, String name) {
        TypeNode node = instance.node();
        Map<Type.TypeVar, Type> env = checker.algebra().instanceEnv(instance);
        for (int i = 0; i < node.parents.size(); i++) {
            Type parent = checker.algebra().substitute(checker.annotator().parentAnnotation(node, i), env);
            if (parent instanceof Type.Instance parentInst) {
                TypeNode.Member member = parentInst.node().members.get(name);
                if (member != null && (member.hasFunction || node.isInterface())) {
                    return checker.algebra().substitute(checker.memberType(parentInst.node(), member), checker.algebra().instanceEnv(parentInst));
                }
                Type inherited = inheritedMember(parentInst, name);
                if (inherited != null) {
                    return inherited;
                }
            } else if (parent instanceof Type.JavaInstance javaParent
                    && JavaTypeBridge.hasJavaMethod(javaParent.type(), name)) {
                return new Type.JavaMethod(javaParent.type(), javaParent.typeArguments(), name);
            }
        }
        return null;
    }

    /**
     * The type of a module's export : from its published {@link TypedModuleSummary}. Null when the
     * module doesn't export the name ; error when its file can't be typed (a cycle).
     */
    static Type exportType(ModuleInterface module, String name) {
        if (!module.exports().contains(name)) {
            return null;
        }
        TypedModuleSummary summary = module.summary();
        if (summary == null) {
            return Type.ERROR;
        }
        Type t = summary.exportType(name);
        return t != null ? t : Type.ERROR;
    }

    /** A method or field of a Java class or object, through {@link JavaTypeBridge#javaMember}. */
    Type javaMember(Class<?> java, List<Type> typeArgs, String name, boolean onClass, ParserRuleContext at) {
        JavaTypeBridge.JavaMember found = JavaTypeBridge.javaMember(java, typeArgs, name, onClass, checker.algebra());
        if (found.error() != null) {
            checker.warn(found.error(), at);
            return Type.ERROR;
        }
        if (found.checked()) {
            String desc = LoweringAnalyzer.descriptor(found.type());
            if (desc != null) {
                checker.callChecks().put(at, desc);
            }
        }
        return found.type();
    }

    static TypeNode.Member targetMember(Type receiver, String name) {
        if (receiver instanceof Type.Instance instance) {
            return findMember(instance.node(), name);
        }
        if (receiver instanceof Type.ClassValue value) {
            return value.node().statics.get(name);
        }
        return null;
    }

    /** Checks each class, object, and extending interface against all ancestor Babble interface member types. */
    void checkInterfaces() {
        for (TypeNode type : checker.pass().types) {
            if (type.parents.isEmpty()) {
                continue;
            }
            List<Type.TypeVar> vars = checker.annotator().typeVarsOf(type);
            Type.Instance selfInst = vars.isEmpty()
                    ? new Type.Instance(type)
                    : new Type.Instance(type, List.copyOf(vars));
            Map<String, TypeChecks.Provider> defaults = inheritedDefaults(type);
            Set<TypeNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object parent : type.parents) {
                if (parent instanceof TypeNode iface) {
                    checkInterfaceConformance(type, selfInst, iface, defaults, seen);
                }
            }
        }
    }

    private static Map<String, TypeChecks.Provider> inheritedDefaults(TypeNode type) {
        Map<String, TypeChecks.Provider> result = new LinkedHashMap<>();
        for (Object parent : type.parents) {
            if (parent instanceof TypeNode iface && iface.defaults != null) {
                for (TypeChecks.Provider provider : iface.defaults.values()) {
                    if (!type.members.containsKey(provider.name())) {
                        result.putIfAbsent(provider.name(), provider);
                    }
                }
            }
        }
        return result;
    }

    private void checkInterfaceConformance(TypeNode type, Type.Instance selfInst, TypeNode iface, Map<String, TypeChecks.Provider> defaults, Set<TypeNode> seen) {
        if (!seen.add(iface)) {
            return;
        }
        Type projectedIface = checker.algebra().projectToBabble(selfInst, iface);
        Map<Type.TypeVar, Type> ifaceEnv = projectedIface instanceof Type.Instance proj ? checker.algebra().instanceEnv(proj) : Map.of();
        for (TypeNode.Member req : iface.members.values()) {
            if (req.declaration != null && !req.hasFunction && checker.annotator().declaredType(req.declaration) == null) {
                continue;
            }
            Type required = checker.algebra().substitute(checker.memberType(iface, req), ifaceEnv);
            if (TypeChecker.isUnresolved(required)) {
                continue;
            }
            TypeNode.Member impl = type.members.get(req.name);
            if (impl != null) {
                if (impl.isPrivate()) {
                    continue;
                }
                Type actual = checker.memberType(type, impl);
                if (!TypeChecker.isUnresolved(actual) && !checker.algebra().subtype(actual, required)) {
                    checker.reportTypeMismatch(actual, required, impl.declaration);
                }
            } else {
                TypeChecks.Provider provider = defaults.get(req.name);
                if (provider != null && provider.owner() instanceof TypeNode owner
                        && (!type.isInterface() || owner != iface)) {
                    TypeNode.Member defaultMember = owner.members.get(req.name);
                    if (defaultMember != null) {
                        Type projectedOwner = checker.algebra().projectToBabble(selfInst, owner);
                        Map<Type.TypeVar, Type> ownerEnv = projectedOwner instanceof Type.Instance proj ? checker.algebra().instanceEnv(proj) : Map.of();
                        Type actual = checker.algebra().substitute(checker.memberType(owner, defaultMember), ownerEnv);
                        if (!TypeChecker.isUnresolved(actual) && !checker.algebra().subtype(actual, required)) {
                            checker.reportTypeMismatch(actual, required, type.ctx);
                        }
                    }
                }
            }
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode parentIface) {
                checkInterfaceConformance(type, selfInst, parentIface, defaults, seen);
            }
        }
    }
}
