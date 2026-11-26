package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
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
    private static final EffectSet ARRAY_SIZE_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
    private static final EffectSet ARRAY_READ_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);
    private static final EffectSet ARRAY_PROTOCOL_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
    private static final EffectSet ARRAY_SET_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC), true, true, false, true);

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
        if (receiver instanceof Type.TypeVar tv) {
            if (tv.bound() != null && tv.bound() != Type.ERROR) {
                return member(tv.bound(), name, own, at, receiverText);
            }
            checker.warn("Not a scope : " + receiverText, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.Intersection intersection) {
            return intersectionMember(intersection, name, own, at, receiverText);
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
            if ("array".equals(cc.kind())) {
                if ("of".equals(name)) {
                    if (cc.typeArguments().size() > 1) {
                        return Type.ERROR;
                    }
                    return new Type.ArrayOfFactory(cc.typeArguments().isEmpty() ? null : cc.typeArguments().get(0));
                }
                checker.warn("No such name in " + receiver + " : " + name, at);
                return Type.ERROR;
            }
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
            if (instance.type() == org.babblelang.runtime.Range.class) {
                return rangeMember(instance, name, at);
            }
            return javaMember(instance.type(), instance.typeArguments(), name, false, at);
        }
        if (receiver == Type.STRING) {
            return javaMember(String.class, List.of(), name, false, at);
        }
        if (receiver instanceof Type.Array array) {
            if ("length".equals(name)) {
                checker.warn("No such name in " + array + " : length (use size())", at);
                return Type.ERROR;
            }
            switch (name) {
                case "size":
                    return new Type.Function(List.of(), Type.INT, ARRAY_SIZE_EFFECTS);
                case "get":
                    return new Type.Function(
                            List.of(new Type.Parameter("index", Type.INT, false, false, true, false)),
                            array.element(), ARRAY_READ_EFFECTS);
                case "set":
                    return new Type.Function(
                            List.of(new Type.Parameter("index", Type.INT, false, false, true, false),
                                    new Type.Parameter("value", array.element(), false, false, false, true)),
                            Type.NULL, ARRAY_SET_EFFECTS);
                case "equals":
                    return new Type.Function(
                            List.of(new Type.Parameter("other", OperatorTypes.ANY_OR_FROZEN, false, false, true, false)),
                            Type.BOOL, ARRAY_PROTOCOL_EFFECTS);
                case "hashCode":
                    return new Type.Function(List.of(), Type.INT, ARRAY_PROTOCOL_EFFECTS);
                case "toString":
                    return new Type.Function(List.of(), Type.STRING, ARRAY_PROTOCOL_EFFECTS);
                default:
                    break;
            }
            if (JavaTypeBridge.hasJavaMethod(Object.class, name)) {
                return new Type.JavaMethod(JavaTypeBridge.checkedArrayClass(array), List.of(), name);
            }
            checker.warn("No such name in " + array + " : " + name, at);
            return Type.ERROR;
        }
        // The 8 primitives, null, functions, any : memberless.
        checker.warn("Not a scope : " + receiverText, at);
        return Type.ERROR;
    }

    static final Set<String> RANGE_MEMBER_NAMES = Set.of(
            "start", "end", "inclusive", "isEmpty", "contains", "step", "reversed");

    private Type rangeMember(Type.JavaInstance range, String name, ParserRuleContext at) {
        Type elem = range.typeArguments().isEmpty() ? Type.ANY : range.typeArguments().get(0);
        boolean scalar = Type.isNumericScalar(elem) || elem == Type.CHAR;
        boolean discrete = Type.isDiscreteRangeElement(elem);
        boolean nothrowElem = scalar || elem == Type.STRING;
        EffectSet pureNothrow = OperatorTypes.PURE_DET_NOTHROW;
        EffectSet pureMayThrow = EffectSet.ofInferred(
                EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, false, false, true);
        EffectSet elemEffect = nothrowElem ? pureNothrow : pureMayThrow;
        switch (name) {
            case "start", "end":
                return new Type.Function(List.of(), elem, pureNothrow);
            case "inclusive":
                return new Type.Function(List.of(), Type.BOOL, pureNothrow);
            case "isEmpty":
                return new Type.Function(List.of(), Type.BOOL, elemEffect);
            case "contains":
                return new Type.Function(
                        List.of(new Type.Parameter("value", elem, false, false, true, false)),
                        Type.BOOL, elemEffect);
            case "step":
                if (!discrete) {
                    checker.warn("Not iterable : " + TypeDisplay.display(range) + " is not discrete", at);
                    return Type.ERROR;
                }
                Type stepParam = elem == Type.LONG ? Type.LONG : Type.INT;
                return new Type.Function(
                        List.of(new Type.Parameter("k", stepParam, false, false, true, false)),
                        range, pureMayThrow);
            case "reversed":
                if (!discrete) {
                    checker.warn("Not iterable : " + TypeDisplay.display(range) + " is not discrete", at);
                    return Type.ERROR;
                }
                return new Type.Function(List.of(), range, pureNothrow);
            case "iterator":
                if (!discrete) {
                    checker.warn("Not iterable : " + TypeDisplay.display(range) + " is not discrete", at);
                    return Type.ERROR;
                }
                return new Type.Function(List.of(), new Type.JavaInstance(java.util.Iterator.class, List.of(elem)), pureNothrow);
            case "equals":
                return new Type.Function(
                        List.of(new Type.Parameter("other", OperatorTypes.ANY_OR_FROZEN, false, false, true, false)),
                        Type.BOOL, elemEffect);
            case "hashCode":
                return new Type.Function(List.of(), Type.INT, elemEffect);
            case "toString":
                return new Type.Function(List.of(), Type.STRING, elemEffect);
            default:
                checker.warn("No such member : " + name, at);
                return Type.ERROR;
        }
    }

    /**
     * A member of {@code A & B} (BIP022-D4) : the member of the operands that declare it ; two
     * that declare it must agree, one type being a subtype of the other, which is then the
     * member's type. A union operand distributes first.
     */
    private Type intersectionMember(Type.Intersection intersection, String name, boolean own,
                                    ParserRuleContext at, String receiverText) {
        Type distributed = TypeAlgebra.distribute(intersection);
        if (distributed != intersection) {
            return member(distributed, name, own, at, receiverText);
        }
        Type found = null;
        Type foundSig = null;
        for (Type operand : intersection.members()) {
            if (!hasMember(operand, name)) {
                continue;
            }
            Type type = member(operand, name, own, at, receiverText);
            if (type == Type.ERROR) {
                return Type.ERROR;
            }
            Type typeSig = memberSignatureType(type);
            if (found == null) {
                found = type;
                foundSig = typeSig;
            } else if (!typeSig.equals(foundSig)) {
                if (checker.algebra().assignable(typeSig, foundSig)) {
                    if (!checker.algebra().assignable(foundSig, typeSig)) {
                        found = type;
                        foundSig = typeSig;
                    }
                } else if (!checker.algebra().assignable(foundSig, typeSig)) {
                    checker.warn("Conflicting member " + name + " in " + intersection, at);
                    return Type.ERROR;
                }
            }
        }
        if (found == null) {
            checker.warn("No such member : " + name, at);
            return Type.ERROR;
        }
        return found;
    }

    private Type memberSignatureType(Type type) {
        if (!(type instanceof Type.JavaMethod jm)) {
            return type;
        }
        List<java.lang.reflect.Method> methods = org.babblelang.runtime.JavaTypes.methods(jm.owner(), jm.name());
        if (methods.isEmpty()) {
            return type;
        }
        Map<java.lang.reflect.TypeVariable<?>, Type> classEnv = JavaTypeBridge.javaTypeEnv(jm.owner(), jm.typeArguments());
        List<Type.Function> fns = new ArrayList<>(methods.size());
        EffectChecker ec = checker.effectChecker();
        for (java.lang.reflect.Method m : methods) {
            java.lang.reflect.TypeVariable<?>[] mTps = m.getTypeParameters();
            Map<java.lang.reflect.TypeVariable<?>, Type> env = new HashMap<>(classEnv);
            List<Type.TypeVar> typeVars = new ArrayList<>(mTps.length);
            for (int i = 0; i < mTps.length; i++) {
                Type.TypeVar tv = new Type.TypeVar(null, mTps[i].getName(), i);
                typeVars.add(tv);
                env.put(mTps[i], tv);
            }
            for (int i = 0; i < mTps.length; i++) {
                Type bound = JavaTypeBridge.translateTypeVarBounds(mTps[i], env);
                if (bound != Type.NULLABLE_ANY) {
                    typeVars.get(i).setBound(bound);
                }
            }
            java.lang.reflect.Type[] gParams = m.getGenericParameterTypes();
            List<Type.Parameter> params = new ArrayList<>(gParams.length);
            for (int i = 0; i < gParams.length; i++) {
                Type pType = JavaTypeBridge.javaExecutableParamType(m, i, env);
                params.add(new Type.Parameter("p" + i, pType, false));
            }
            Type retType = JavaTypeBridge.javaMethodResult(m, env, checker.algebra());
            Type.Function fn = new Type.Function(typeVars, params, retType);
            fns.add(ec != null ? ec.javaResolver().applySamJavaEffects(m, fn) : fn);
        }
        return fns.size() == 1 ? fns.get(0) : new Type.Overloaded(fns);
    }

    /** Whether a receiver type has a member, without reporting anything. */
    boolean hasMember(Type operand, String name) {
        if (operand instanceof Type.Frozen f) {
            return hasMember(f.of(), name);
        }
        if (operand instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            return hasMember(tv.bound(), name);
        }
        if (operand instanceof Type.Instance instance) {
            return findMember(instance, name) != null || inheritedMember(instance, name) != null;
        }
        if (operand instanceof Type.ClassValue cv) {
            return cv.node().statics.containsKey(name);
        }
        if (operand instanceof Type.Module mod) {
            return mod.module().exports().contains(name);
        }
        if (operand == Type.STRING) {
            return JavaTypeBridge.hasJavaMethod(String.class, name);
        }
        if (operand instanceof Type.Array) {
            return "size".equals(name) || "get".equals(name) || "set".equals(name)
                    || "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)
                    || JavaTypeBridge.hasJavaMethod(Object.class, name);
        }
        if (operand instanceof Type.JavaInstance java) {
            if (java.type() == org.babblelang.runtime.Range.class) {
                return RANGE_MEMBER_NAMES.contains(name) || "iterator".equals(name)
                        || "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name);
            }
            if (JavaTypeBridge.hasJavaMethod(java.type(), name)) {
                return true;
            }
            try {
                java.type().getField(name);
                return true;
            } catch (NoSuchFieldException e) {
                return false;
            }
        }
        return false;
    }

    /** What {@code receiver.name = value} must assign : the member's declared or closed type, or null if inferred locally. */
    Type assignedMemberType(Type receiver, String name, boolean own, ParserRuleContext at) {
        while (receiver instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            receiver = tv.bound();
        }
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
            } else if (inheritedMember(instance, name) != null) {
                checker.warn("Cannot assign method : " + name, at);
            } else {
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
                || receiver instanceof Type.Array || TypeChecker.isUnresolved(receiver))) {
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
        if (member.isMethod()) {
            checker.warn("Cannot assign method : " + name, at);
            return null;
        }
        if (ownerNode != null && ownerNode.kind == org.babblelang.runtime.ClassShape.Kind.VALUE_CLASS
                && receiver instanceof Type.Instance) {
            checker.warn("Value members are read-only : " + name, at);
            return null;
        }
        if (!member.isMutable()) {
            checker.warn("Cannot assign to def member : " + name, at);
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
        while (receiver instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            receiver = tv.bound();
        }
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

    /** Checks each class, object, and extending interface against all ancestor Babble and Java interface member types. */
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
            if (!type.isInterface()) {
                checkJavaInterfaceOverloads(type, selfInst, defaults);
            }
        }
    }

    private void checkJavaInterfaceOverloads(TypeNode type, Type.Instance selfInst, Map<String, TypeChecks.Provider> defaults) {
        Set<Class<?>> javaIfaces = new java.util.LinkedHashSet<>();
        collectJavaInterfaces(type, javaIfaces, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (javaIfaces.isEmpty()) {
            return;
        }
        Set<String> checked = new java.util.HashSet<>();
        for (Class<?> javaIface : javaIfaces) {
            Type projected = checker.algebra().projectToJava(selfInst, javaIface);
            Map<java.lang.reflect.TypeVariable<?>, Type> javaEnv = projected instanceof Type.JavaInstance ji
                    ? JavaTypeBridge.javaTypeEnv(javaIface, ji.typeArguments())
                    : Map.of();
            for (java.lang.reflect.Method jm : org.babblelang.runtime.JavaTypes.abstractMethods(javaIface)) {
                String sigKey = jm.getName() + "#" + org.objectweb.asm.Type.getMethodDescriptor(jm);
                if (!checked.add(sigKey)) {
                    continue;
                }
                TypeNode.Member impl = type.members.get(jm.getName());
                Type implType = null;
                String ownerName = type.name;
                ParserRuleContext errorSite = type.ctx;
                if (impl != null && !impl.isPrivate()) {
                    implType = checker.memberType(type, impl);
                    if (impl.declaration != null) {
                        errorSite = impl.declaration;
                    }
                } else {
                    TypeChecks.Provider provider = defaults.get(jm.getName());
                    if (provider != null && provider.owner() instanceof TypeNode owner) {
                        TypeNode.Member defMember = owner.members.get(jm.getName());
                        if (defMember != null) {
                            Type projectedOwner = checker.algebra().projectToBabble(selfInst, owner);
                            Map<Type.TypeVar, Type> ownerEnv = projectedOwner instanceof Type.Instance proj
                                    ? checker.algebra().instanceEnv(proj) : Map.of();
                            implType = checker.algebra().substitute(checker.memberType(owner, defMember), ownerEnv);
                            ownerName = owner.name;
                        }
                    }
                }
                if (!(implType instanceof Type.Overloaded ov)) {
                    continue;
                }
                int paramCount = jm.getParameterCount();
                List<Type> javaParamTypes = new ArrayList<>(paramCount);
                List<Type> fallbackParamTypes = new ArrayList<>(paramCount);
                boolean hasFallbackDiff = false;
                for (int i = 0; i < paramCount; i++) {
                    Type pt = JavaTypeBridge.javaExecutableParamType(jm, i, javaEnv);
                    javaParamTypes.add(pt);
                    JavaNullability.Nullness nullness = JavaNullability.parameterNullness(jm, i);
                    if (nullness == JavaNullability.Nullness.UNSPECIFIED && Type.containsNull(pt)) {
                        fallbackParamTypes.add(Type.withoutNull(pt));
                        hasFallbackDiff = true;
                    } else {
                        fallbackParamTypes.add(pt);
                    }
                }
                int chosen = OverloadSolver.selectAlternativeForJavaMethod(
                        checker.algebra(), ov, jm, javaParamTypes, hasFallbackDiff ? fallbackParamTypes : null);
                if (chosen < 0) {
                    checker.warn(ownerName + "." + jm.getName() + " has no alternative for "
                            + OverloadSolver.javaMethodSignature(jm), errorSite);
                }
            }
        }
    }

    private static void collectJavaInterfaces(TypeNode node, Set<Class<?>> out, Set<TypeNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        for (Object parent : node.parents) {
            if (parent instanceof Class<?> jc) {
                out.add(jc);
            } else if (parent instanceof TypeNode parentNode) {
                collectJavaInterfaces(parentNode, out, seen);
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

    /** Resolves a non-null iterable/iterator type to its lowering protocol and element type (BIP013, BIP027-I1). */
    TypeChecker.ForIterableInfo resolveNonNullIterable(Type iterType) {
        boolean frozenIterable = iterType instanceof Type.Frozen;
        Type unwrapped = frozenIterable ? ((Type.Frozen) iterType).of() : iterType;
        if (unwrapped instanceof Type.TypeVar tv && tv.bound() != null && tv.bound() != Type.ERROR) {
            return resolveNonNullIterable(frozenIterable ? Type.frozen(tv.bound()) : tv.bound());
        }
        if (unwrapped instanceof Type.Intersection intersection) {
            for (Type member : intersection.members()) {
                TypeChecker.ForIterableInfo sub = resolveNonNullIterable(frozenIterable ? Type.frozen(member) : member);
                if (sub != null) {
                    return sub;
                }
            }
            return null;
        }
        if (unwrapped instanceof Type.Union union) {
            Type joinedElem = null;
            LoweringTargets.ForKind commonKind = null;
            boolean sameKind = true;
            for (Type member : union.members()) {
                Type effMember = frozenIterable ? Type.frozen(member) : member;
                TypeChecker.ForIterableInfo sub = resolveNonNullIterable(effMember);
                if (sub == null) {
                    return null;
                }
                joinedElem = joinedElem == null ? sub.elemType() : checker.algebra().join(joinedElem, sub.elemType());
                if (joinedElem == null) {
                    joinedElem = Type.NULLABLE_ANY;
                }
                if (commonKind == null) {
                    commonKind = sub.kind();
                } else if (commonKind != sub.kind()) {
                    sameKind = false;
                }
            }
            LoweringTargets.ForKind kind = (sameKind && (commonKind == LoweringTargets.ForKind.JAVA_ITERABLE
                    || commonKind == LoweringTargets.ForKind.JAVA_ITERATOR))
                    ? commonKind : LoweringTargets.ForKind.DYNAMIC_UNION;
            return new TypeChecker.ForIterableInfo(kind, null, joinedElem != null ? joinedElem : Type.NULLABLE_ANY,
                    null, null, null, null, null);
        }
        if (unwrapped instanceof Type.Array arr) {
            Type elem = frozenIterable ? Type.frozen(arr.element()) : arr.element();
            return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.ARRAY, JavaTypeBridge.checkedArrayClass(arr), elem,
                    null, null, null, null, null);
        }
        if (unwrapped instanceof Type.JavaInstance ji) {
            if (ji.type() == org.babblelang.runtime.Range.class) {
                Type elem = ji.typeArguments().isEmpty() ? Type.NULLABLE_ANY : ji.typeArguments().get(0);
                if (Type.isDiscreteRangeElement(elem)) {
                    return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.JAVA_ITERABLE, null,
                            frozenIterable ? Type.frozen(elem) : elem,
                            null, null, null, null, null);
                }
                return null;
            }
            if (java.lang.Iterable.class.isAssignableFrom(ji.type())) {
                Type elem = javaIteratorElement(ji, java.lang.Iterable.class, frozenIterable);
                return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.JAVA_ITERABLE, null, elem,
                        null, null, null, null, null);
            }
            if (java.util.Iterator.class.isAssignableFrom(ji.type())) {
                Type elem = javaIteratorElement(ji, java.util.Iterator.class, frozenIterable);
                return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.JAVA_ITERATOR, null, elem,
                        null, null, null, null, null);
            }
            return null;
        }
        if (unwrapped instanceof Type.Instance inst) {
            Type.Function iterFn = resolveBabbleZeroArgMethod(inst, "iterator");
            if (iterFn != null) {
                Type iterRes = iterFn.result();
                if (iterRes != null && !Type.containsNull(iterRes)) {
                    Type unwrappedIter = iterRes instanceof Type.Frozen f ? f.of() : iterRes;
                    if (unwrappedIter instanceof Type.JavaInstance ji && java.util.Iterator.class.isAssignableFrom(ji.type())) {
                        Type elem = javaIteratorElement(ji, java.util.Iterator.class, frozenIterable);
                        return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE, null, elem,
                                inst, null, iterFn, null, null);
                    }
                    if (unwrappedIter instanceof Type.Instance iterInst) {
                        Type.Function hasNextFn = resolveBabbleZeroArgMethod(iterInst, "hasNext");
                        Type.Function nextFn = resolveBabbleZeroArgMethod(iterInst, "next");
                        if (hasNextFn != null && nextFn != null && hasNextFn.result() == Type.BOOL) {
                            Type elem = frozenIterable ? Type.frozen(nextFn.result()) : nextFn.result();
                            return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.BABBLE_ITERABLE, null, elem,
                                    inst, iterInst, iterFn, hasNextFn, nextFn);
                        }
                    }
                }
            }
            Type.Function hasNextFn = resolveBabbleZeroArgMethod(inst, "hasNext");
            Type.Function nextFn = resolveBabbleZeroArgMethod(inst, "next");
            if (hasNextFn != null && nextFn != null && hasNextFn.result() == Type.BOOL) {
                Type elem = frozenIterable ? Type.frozen(nextFn.result()) : nextFn.result();
                return new TypeChecker.ForIterableInfo(LoweringTargets.ForKind.BABBLE_ITERATOR, null, elem,
                        null, inst, null, hasNextFn, nextFn);
            }
            return null;
        }
        return null;
    }

    private static Type javaIteratorElement(Type.JavaInstance ji, Class<?> rawInterface, boolean frozenIterable) {
        Map<java.lang.reflect.TypeVariable<?>, Type> envMap = JavaTypeBridge.javaTypeEnv(ji.type(), ji.typeArguments());
        Type elem = envMap.get(rawInterface.getTypeParameters()[0]);
        if (elem == null) {
            elem = Type.NULLABLE_ANY;
        }
        return frozenIterable ? Type.frozen(elem) : elem;
    }

    private Type.Function resolveBabbleZeroArgMethod(Type.Instance inst, String name) {
        ResolvedMember resolved = findMember(inst, name);
        if (resolved == null || resolved.member().isPrivate()) {
            return null;
        }
        Type raw = checker.memberType(resolved.owner(), resolved.member());
        if (raw == null || raw == Type.ERROR) {
            return null;
        }
        Type substituted = checker.algebra().substitute(raw, resolved.env());
        if (substituted instanceof Type.Function fn && fn.typeParameters().isEmpty()
                && fn.parameters().stream().allMatch(Type.Parameter::optional)) {
            return fn;
        }
        return null;
    }
}
