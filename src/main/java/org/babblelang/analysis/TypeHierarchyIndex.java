package org.babblelang.analysis;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;

/**
 * Memoized interface-DAG closure index and class/interface hierarchy lowerer for {@link
 * LoweringAnalyzer}.
 *
 * <p>Because {@link TypeNode#parents} forms a finite DAG (acyclic after {@link TypeChecks}),
 * bottom-up memoized closure merging in {@link LinkedHashSet}/{@link LinkedHashMap} yields the
 * exact same ancestor order and first-declaring interface member map as repeated pre-order DFS, in
 * O(1) amortized lookup time per query.
 */
final class TypeHierarchyIndex {
    record OwnerMember(TypeNode owner, TypeNode.Member member) {}

    record Closure(
            Set<String> ancestorBabbleIds,
            Set<Class<?>> ancestorJavaInterfaces,
            Map<String, OwnerMember> interfaceMembers,
            Map<String, OwnerMember> inheritedInterfaceMembers) {
        static final Closure EMPTY = new Closure(Set.of(), Set.of(), Map.of(), Map.of());
    }

    private final BlockScopes pass;
    private final TypeChecker checker;
    private final Map<ParserRuleContext, Type> types;
    private final Map<TypeNode, Closure> closures = new IdentityHashMap<>();
    private final Map<TypeNode, Set<String>> conformIdsCache = new IdentityHashMap<>();

    TypeHierarchyIndex(BlockScopes pass, TypeChecker checker) {
        this.pass = pass;
        this.checker = checker;
        this.types = checker.types();
    }

    Closure closureOf(TypeNode node) {
        Closure cached = closures.get(node);
        if (cached != null) {
            return cached;
        }
        return computeClosure(node, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Closure computeClosure(TypeNode node, Set<TypeNode> active) {
        Closure cached = closures.get(node);
        if (cached != null) {
            return cached;
        }
        if (!active.add(node)) {
            return Closure.EMPTY;
        }
        Set<String> babbleIds = new LinkedHashSet<>();
        Set<Class<?>> javaIfaces = new LinkedHashSet<>();
        Map<String, OwnerMember> inherited = new LinkedHashMap<>();
        boolean hitCycle = false;
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                if (active.contains(parentNode)) {
                    hitCycle = true;
                    continue;
                }
                Closure pc = computeClosure(parentNode, active);
                babbleIds.add(parentNode.id);
                babbleIds.addAll(pc.ancestorBabbleIds());
                javaIfaces.addAll(pc.ancestorJavaInterfaces());
                if (parentNode.isInterface()) {
                    for (Map.Entry<String, OwnerMember> e : pc.interfaceMembers().entrySet()) {
                        inherited.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
            } else if (parent instanceof Class<?> javaIface) {
                collectJavaInterfaces(javaIface, javaIfaces);
            }
        }
        Map<String, OwnerMember> ifaceMembers;
        if (node.isInterface()) {
            Map<String, OwnerMember> combined = new LinkedHashMap<>();
            for (TypeNode.Member member : node.members.values()) {
                combined.put(member.name, new OwnerMember(node, member));
            }
            for (Map.Entry<String, OwnerMember> e : inherited.entrySet()) {
                combined.putIfAbsent(e.getKey(), e.getValue());
            }
            ifaceMembers = Collections.unmodifiableMap(combined);
        } else {
            ifaceMembers = Map.of();
        }
        active.remove(node);
        Closure result =
                new Closure(
                        Collections.unmodifiableSet(babbleIds),
                        Collections.unmodifiableSet(javaIfaces),
                        ifaceMembers,
                        Collections.unmodifiableMap(inherited));
        if (!hitCycle) {
            closures.put(node, result);
        }
        return result;
    }

    /** Returns {@code node.id} union all ancestor Babble interface IDs in DFS order. */
    Set<String> conformIds(TypeNode node) {
        Set<String> cached = conformIdsCache.get(node);
        if (cached != null) {
            return cached;
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(node.id);
        ids.addAll(closureOf(node).ancestorBabbleIds());
        Set<String> frozen = Collections.unmodifiableSet(ids);
        conformIdsCache.put(node, frozen);
        return frozen;
    }

    /**
     * O(1) cached lookup of the first interface in {@code iface}'s hierarchy declaring {@code
     * name}.
     */
    OwnerMember findDeclaringInterfaceMember(TypeNode iface, String name) {
        if (iface == null || !iface.isInterface()) {
            return null;
        }
        return closureOf(iface).interfaceMembers().get(name);
    }

    /** Projects {@code receiver} to {@code owner} and substitutes {@code member}'s type. */
    Type substitutedMemberType(Type.Instance receiver, TypeNode owner, TypeNode.Member member) {
        Type projected = checker.algebra().projectToBabble(receiver, owner);
        Map<Type.TypeVar, Type> env =
                projected instanceof Type.Instance proj
                        ? checker.algebra().instanceEnv(proj)
                        : Map.of();
        return checker.algebra().substitute(checker.memberType(owner, member), env);
    }

    /**
     * Populates {@link TypeNode} lowering facts preserved across {@code detachAst()} for direct
     * dispatch ({@code BIP014-I4}).
     */
    void publishClassLoweringFacts() {
        Resolution resolution = pass.analysis.resolution();
        for (TypeNode node : pass.types) {
            if (node.isInterface() || node.ctx == null || !checker.inFile(node.ctx)) {
                continue;
            }
            for (TypeNode.Member member : node.members.values()) {
                if (!member.isMethod()) {
                    PrimitiveKind fk =
                            member.declaration != null
                                    ? pass.analysis.lowering().declarationKind(member.declaration)
                                    : PrimitiveKind.NONE;
                    node.memberKinds.put(member.name, fk);
                    if (node.parameterMembers.contains(member.index)
                            || (!member.isMutable()
                                    && pass.analysis
                                            .lowering()
                                            .isAllDefFieldsFinalSafe(node.ctx))) {
                        node.definiteFields.add(member.name);
                    }
                } else if (!member.isOverload
                        && member.function != null
                        && types.get(member.function) instanceof Type.Function tf
                        && tf.typeParameters().isEmpty()) {
                    LoweringTargets.FunctionSignature sig = uninstantiatedSignature(tf);
                    if (sig != null) {
                        node.methodSignatures.put(member.name, sig);
                    }
                } else if (member.isOverload
                        && member.overloadFunctions != null
                        && !member.overloadFunctions.isEmpty()) {
                    for (int ai = 0; ai < member.overloadFunctions.size(); ai++) {
                        BabbleParser.FunctionLiteralContext altFn =
                                member.overloadFunctions.get(ai);
                        if (altFn != null
                                && types.get(altFn) instanceof Type.Function tf
                                && tf.typeParameters().isEmpty()) {
                            LoweringTargets.FunctionSignature sig = uninstantiatedSignature(tf);
                            if (sig != null) {
                                node.overloadMethodSignatures.put(member.name + "$" + ai, sig);
                            }
                        }
                    }
                }
            }
            detectFastRecord(node, resolution);
        }
    }

    private void detectFastRecord(TypeNode node, Resolution resolution) {
        if (node.kind != ClassShape.Kind.VALUE_CLASS
                || !(node.ctx instanceof BabbleParser.ClassExpressionContext clsCtx)
                || resolution == null
                || !resolution.captured(clsCtx).isEmpty()
                || clsCtx.classParameters() == null) {
            return;
        }
        List<BabbleParser.ClassParamContext> paramCtxs = clsCtx.classParameters().classParam();
        for (Resolution.Variable v : resolution.declared(clsCtx)) {
            if (v.isCaptured()) {
                return;
            }
        }
        if (paramCtxs.size() > 8 || paramCtxs.size() != node.parameters.size()) {
            return;
        }
        PrimitiveKind[] paramKinds = new PrimitiveKind[paramCtxs.size()];
        int slots = 3; // this, BabbleClass, ScriptRuntime
        for (int i = 0; i < paramCtxs.size(); i++) {
            BabbleParser.ClassParamContext pc = paramCtxs.get(i);
            if (pass.analysis.lowering().javaSamTarget(pc) != null) {
                return;
            }
            PrimitiveKind pk = pass.analysis.lowering().declarationKind(pc);
            paramKinds[i] = pk;
            slots += pk.slots();
        }
        if (slots > 200) {
            return;
        }
        if (clsCtx.classBlock != null) {
            for (BabbleParser.ExpressionContext expr : clsCtx.classBlock.expression()) {
                if (!(expr instanceof BabbleParser.DefExpressionContext def)) {
                    return;
                }
                LoweringTargets.DefTarget dt = pass.analysis.lowering().defTarget(def);
                TypeNode.Member m = node.members.get(def.name.getText());
                if (dt.kind() != LoweringTargets.DefKind.MEMBER
                        || m == null
                        || !m.isMethod()
                        || !isPureMethodDefValue(def.value)) {
                    return;
                }
            }
        }
        node.fastRecord = true;
        node.fastRecordParamKinds = paramKinds;
    }

    private static boolean isPureMethodDefValue(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.FunctionLiteralContext) {
            return true;
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext ov) {
            for (BabbleParser.ExpressionContext op : ov.expression()) {
                if (!(op instanceof BabbleParser.FunctionLiteralContext)) {
                    return false;
                }
            }
            return !ov.expression().isEmpty();
        }
        return false;
    }

    void publishTypeHierarchies() {
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && node.isInterface()) {
                pass.analysis.hierarchy().setTypeHierarchy(node.id, buildTypeHierarchyInfo(node));
            }
        }
        for (TypeNode node : pass.types) {
            if (checker.inFile(node.ctx) && !node.isInterface()) {
                pass.analysis.hierarchy().setTypeHierarchy(node.id, buildTypeHierarchyInfo(node));
            }
        }
    }

    private LoweringTargets.TypeHierarchyInfo buildTypeHierarchyInfo(TypeNode node) {
        Set<LoweringTargets.ParentRef> seenDirect = new LinkedHashSet<>();
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode tn) {
                seenDirect.add(new LoweringTargets.ParentRef(tn.id, null));
            } else if (parent instanceof Class<?> jc) {
                seenDirect.add(new LoweringTargets.ParentRef(null, jc));
            }
        }
        List<LoweringTargets.ParentRef> directParents = List.copyOf(seenDirect);
        Closure closure = closureOf(node);

        Map<String, LoweringTargets.MemberSpec> interfaceMembers = new LinkedHashMap<>();
        Map<String, Integer> javaBridgeOverloads = new LinkedHashMap<>();
        Map<String, Integer> babbleBridgeOverloads = new LinkedHashMap<>();
        if (node.isInterface()) {
            for (TypeNode.Member member : node.members.values()) {
                boolean hasDefault = member.hasFunction;
                Type mt = checker.memberType(node, member);
                interfaceMembers.put(
                        member.name, buildMemberSpec(member.name, hasDefault, !hasDefault, mt));
            }
        } else {
            Map<String, TypeChecks.Provider> allInterfaceDefaults = collectInterfaceDefaults(node);
            List<Type.TypeVar> vars = checker.annotator().typeVarsOf(node);
            Type.Instance selfInst =
                    vars.isEmpty()
                            ? new Type.Instance(node)
                            : new Type.Instance(node, List.copyOf(vars));
            for (OwnerMember om : closure.inheritedInterfaceMembers().values()) {
                TypeNode.Member member = om.member();
                TypeChecks.Provider provider = allInterfaceDefaults.get(member.name);
                boolean hasDefault =
                        member.hasFunction
                                || (provider != null && provider.owner() instanceof TypeNode);
                Type mt = checker.memberType(om.owner(), member);
                interfaceMembers.put(
                        member.name, buildMemberSpec(member.name, hasDefault, !hasDefault, mt));
                recordBabbleBridgeOverloads(
                        node, selfInst, om.owner(), member, babbleBridgeOverloads);
            }
            recordJavaBridgeOverloads(
                    node,
                    selfInst,
                    closure.ancestorJavaInterfaces(),
                    allInterfaceDefaults,
                    javaBridgeOverloads);
        }
        return new LoweringTargets.TypeHierarchyInfo(
                node.id,
                node.kind,
                node.name,
                directParents,
                closure.ancestorBabbleIds(),
                closure.ancestorJavaInterfaces(),
                Collections.unmodifiableMap(interfaceMembers),
                Collections.unmodifiableMap(javaBridgeOverloads),
                Collections.unmodifiableMap(babbleBridgeOverloads));
    }

    private static Map<String, TypeChecks.Provider> collectInterfaceDefaults(TypeNode node) {
        Map<String, TypeChecks.Provider> allInterfaceDefaults = new LinkedHashMap<>();
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode iface && iface.defaults != null) {
                for (TypeChecks.Provider provider : iface.defaults.values()) {
                    allInterfaceDefaults.putIfAbsent(provider.name(), provider);
                }
            }
        }
        return allInterfaceDefaults;
    }

    private void recordBabbleBridgeOverloads(
            TypeNode node,
            Type.Instance selfInst,
            TypeNode owner,
            TypeNode.Member member,
            Map<String, Integer> babbleBridgeOverloads) {
        TypeNode.Member ownImpl = node.members.get(member.name);
        if (ownImpl == null
                || ownImpl.isPrivate()
                || !(checker.memberType(node, ownImpl) instanceof Type.Overloaded implOv)) {
            return;
        }
        Type reqType = substitutedMemberType(selfInst, owner, member);
        if (reqType instanceof Type.Function reqFn) {
            int chosen =
                    OverloadSolver.selectAlternativeForFunction(
                            checker.algebra(), implOv, reqFn, false);
            if (chosen >= 0) {
                babbleBridgeOverloads.put(member.name, chosen);
            }
        } else if (reqType instanceof Type.Overloaded reqOv) {
            List<Type.Function> reqAlts = reqOv.alternatives();
            for (int k = 0; k < reqAlts.size(); k++) {
                int chosen =
                        OverloadSolver.selectAlternativeForFunction(
                                checker.algebra(), implOv, reqAlts.get(k), false);
                if (chosen >= 0) {
                    babbleBridgeOverloads.put(member.name + "$" + k, chosen);
                }
            }
        }
    }

    private void recordJavaBridgeOverloads(
            TypeNode node,
            Type.Instance selfInst,
            Set<Class<?>> allJavaInterfaces,
            Map<String, TypeChecks.Provider> allInterfaceDefaults,
            Map<String, Integer> javaBridgeOverloads) {
        for (Class<?> jc : allJavaInterfaces) {
            Type projected = checker.algebra().projectToJava(selfInst, jc);
            Map<java.lang.reflect.TypeVariable<?>, Type> javaEnv =
                    projected instanceof Type.JavaInstance ji
                            ? JavaTypeBridge.javaTypeEnv(jc, ji.typeArguments())
                            : Map.of();
            for (Method jm : jc.getMethods()) {
                if (Modifier.isStatic(jm.getModifiers()) || JavaTypes.isObjectMethod(jm)) {
                    continue;
                }
                String key = jm.getName() + "#" + org.objectweb.asm.Type.getMethodDescriptor(jm);
                if (javaBridgeOverloads.containsKey(key)) {
                    continue;
                }
                Type implType = resolveImplType(node, selfInst, jm.getName(), allInterfaceDefaults);
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
                    if (JavaNullability.parameterNullness(jm, i)
                                    == JavaNullability.Nullness.UNSPECIFIED
                            && Type.containsNull(pt)) {
                        fallbackParamTypes.add(Type.withoutNull(pt));
                        hasFallbackDiff = true;
                    } else {
                        fallbackParamTypes.add(pt);
                    }
                }
                int chosen =
                        OverloadSolver.selectAlternativeForJavaMethod(
                                checker.algebra(),
                                ov,
                                jm,
                                javaParamTypes,
                                hasFallbackDiff ? fallbackParamTypes : null);
                if (chosen >= 0) {
                    javaBridgeOverloads.put(key, chosen);
                }
            }
        }
    }

    private Type resolveImplType(
            TypeNode node,
            Type.Instance selfInst,
            String memberName,
            Map<String, TypeChecks.Provider> allInterfaceDefaults) {
        TypeNode.Member ownImpl = node.members.get(memberName);
        if (ownImpl != null && !ownImpl.isPrivate()) {
            return checker.memberType(node, ownImpl);
        }
        TypeChecks.Provider provider = allInterfaceDefaults.get(memberName);
        if (provider != null && provider.owner() instanceof TypeNode owner) {
            TypeNode.Member defMember = owner.members.get(memberName);
            if (defMember != null) {
                return substitutedMemberType(selfInst, owner, defMember);
            }
        }
        return null;
    }

    private static LoweringTargets.MemberSpec buildMemberSpec(
            String name, boolean hasDefault, boolean required, Type mt) {
        if (mt instanceof Type.Function fn) {
            return new LoweringTargets.MemberSpec(
                    name, hasDefault, required, true, uninstantiatedSignature(fn));
        }
        if (mt instanceof Type.Overloaded ov) {
            List<LoweringTargets.FunctionSignature> sigs =
                    new ArrayList<>(ov.alternatives().size());
            List<String> keys = new ArrayList<>(ov.alternatives().size());
            for (Type.Function alt : ov.alternatives()) {
                LoweringTargets.FunctionSignature sig = uninstantiatedSignature(alt);
                if (sig == null) {
                    return new LoweringTargets.MemberSpec(name, hasDefault, required, false, null);
                }
                sigs.add(sig);
                keys.add(OverloadSolver.signatureKey(alt));
            }
            return new LoweringTargets.MemberSpec(
                    name, hasDefault, required, false, null, List.copyOf(sigs), List.copyOf(keys));
        }
        return new LoweringTargets.MemberSpec(name, hasDefault, required, false, null);
    }

    private static void collectJavaInterfaces(Class<?> javaIface, Set<Class<?>> out) {
        if (javaIface == null || !javaIface.isInterface() || !out.add(javaIface)) {
            return;
        }
        for (Class<?> parent : javaIface.getInterfaces()) {
            collectJavaInterfaces(parent, out);
        }
    }

    static LoweringTargets.FunctionSignature uninstantiatedSignature(Type.Function fn) {
        if (fn == null) {
            return null;
        }
        List<Type.Parameter> params = fn.parameters();
        if (params.size() > 200) {
            return null;
        }
        PrimitiveKind[] paramKinds = new PrimitiveKind[params.size()];
        int slots = 2;
        int optionalParamBits = 0;
        for (int i = 0; i < params.size(); i++) {
            Type.Parameter p = params.get(i);
            Type pt = p.type();
            PrimitiveKind pk =
                    (p.rest() || pt instanceof Type.TypeVar)
                            ? PrimitiveKind.NONE
                            : PrimitiveKind.of(pt);
            paramKinds[i] = pk;
            slots += pk.slots();
            if (p.optional()) {
                if (i >= 32) {
                    return null;
                }
                optionalParamBits |= (1 << i);
            }
        }
        if (optionalParamBits != 0) {
            slots += 1;
        }
        if (slots > 200) {
            return null;
        }
        Type rt = fn.result();
        PrimitiveKind rk = rt instanceof Type.TypeVar ? PrimitiveKind.NONE : PrimitiveKind.of(rt);
        return new LoweringTargets.FunctionSignature(
                paramKinds, rk, optionalParamBits, fn.restIndex());
    }
}
