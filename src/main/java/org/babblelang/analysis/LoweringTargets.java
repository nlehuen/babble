package org.babblelang.analysis;

import org.babblelang.runtime.ClassShape;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ANTLR-free lowering, signature, binding, and hierarchy value types produced by static analysis
 * and consumed by {@code ir.hir}, {@code opt}, and {@code compiler} without importing {@link ScopeAnalysis}
 * (BIP027-D3).
 */
public final class LoweringTargets {
    private LoweringTargets() {
    }

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

    public record JavaCallTarget(java.lang.reflect.Method method, boolean isStatic, boolean unwrapReceiver, boolean varArgs) {
        public JavaCallTarget(java.lang.reflect.Method method, boolean isStatic, boolean unwrapReceiver) {
            this(method, isStatic, unwrapReceiver, false);
        }
    }

    public record JavaConstructorTarget(java.lang.reflect.Constructor<?> constructor, boolean varArgs) {
        public JavaConstructorTarget(java.lang.reflect.Constructor<?> constructor) {
            this(constructor, false);
        }
    }

    public record JavaFieldTarget(java.lang.reflect.Field field, boolean isStatic, boolean unwrapReceiver) {
    }

    public record ModuleExportTarget(int index, String name, String moduleName) {
        public ModuleExportTarget(int index, String name) {
            this(index, name, null);
        }
    }

    public record FunctionSignature(PrimitiveKind[] parameterKinds, PrimitiveKind returnKind, int optionalParamBits, int restIndex) {
        public FunctionSignature(PrimitiveKind[] parameterKinds, PrimitiveKind returnKind) {
            this(parameterKinds, returnKind, 0, -1);
        }

        public FunctionSignature(PrimitiveKind[] parameterKinds, PrimitiveKind returnKind, int optionalParamBits) {
            this(parameterKinds, returnKind, optionalParamBits, -1);
        }

        public int optionalCount() {
            return Integer.bitCount(optionalParamBits);
        }

        public boolean hasOptionalMask() {
            return optionalParamBits != 0;
        }

        public boolean isOptional(int index) {
            return index < 32 && (optionalParamBits & (1 << index)) != 0;
        }

        public boolean isSpread() {
            return parameterKinds.length <= 8;
        }

        public boolean hasSpec() {
            if (!isSpread()) {
                return false;
            }
            int opt = optionalCount();
            if (opt > 32 || (opt > 0 && parameterKinds.length > 32)) {
                return false;
            }
            boolean hasPrimitive = returnKind.isPrimitive();
            int slots = 3 + (opt > 0 ? 1 : 0);
            for (PrimitiveKind pk : parameterKinds) {
                if (pk.isPrimitive()) {
                    hasPrimitive = true;
                }
                slots += pk.slots();
            }
            return hasPrimitive && slots <= 250;
        }
    }

    /** How a spread call argument ({@code ...e}) is adapted to its target parameter ({@code BIP021-D7}). */
    public enum SpreadKind {
        NONE,
        AS_IS,
        TO_ITERABLE,
        TO_LIST_BORROWED,
        TO_LIST_COPY,
        TO_ARRAY,
        TO_SAM
    }

    /** Per-argument spread adaptation metadata ({@code BIP021-D7}, {@code BIP022-D7}). */
    public record SpreadAdapt(SpreadKind kind, Class<?> arrayComponentType, JavaSamTarget elementSamTarget) {
        public static final SpreadAdapt NONE = new SpreadAdapt(SpreadKind.NONE, null, null);
        public static final SpreadAdapt AS_IS = new SpreadAdapt(SpreadKind.AS_IS, null, null);
        public static final SpreadAdapt TO_ITERABLE = new SpreadAdapt(SpreadKind.TO_ITERABLE, null, null);
        public static final SpreadAdapt TO_LIST_BORROWED = new SpreadAdapt(SpreadKind.TO_LIST_BORROWED, null, null);
        public static final SpreadAdapt TO_LIST_COPY = new SpreadAdapt(SpreadKind.TO_LIST_COPY, null, null);

        public SpreadAdapt(SpreadKind kind, Class<?> arrayComponentType) {
            this(kind, arrayComponentType, null);
        }

        public static SpreadAdapt toArray(Class<?> componentType) {
            return new SpreadAdapt(SpreadKind.TO_ARRAY, componentType, null);
        }

        public static SpreadAdapt toArray(Class<?> componentType, JavaSamTarget elementSamTarget) {
            return new SpreadAdapt(SpreadKind.TO_ARRAY, componentType, elementSamTarget);
        }

        public static SpreadAdapt toList(boolean borrowed, JavaSamTarget elementSamTarget) {
            if (elementSamTarget == null) {
                return borrowed ? TO_LIST_BORROWED : TO_LIST_COPY;
            }
            return new SpreadAdapt(borrowed ? SpreadKind.TO_LIST_BORROWED : SpreadKind.TO_LIST_COPY, null, elementSamTarget);
        }

        public static SpreadAdapt toIterable(JavaSamTarget elementSamTarget) {
            return elementSamTarget == null ? TO_ITERABLE
                    : new SpreadAdapt(SpreadKind.TO_ITERABLE, null, elementSamTarget);
        }

        public static SpreadAdapt toSam(JavaSamTarget elementSamTarget) {
            return elementSamTarget == null ? NONE
                    : new SpreadAdapt(SpreadKind.TO_SAM, null, elementSamTarget);
        }
    }

    /**
     * Static argument binding for a Babble call ({@code BIP021-D4}, {@code BIP021-D5}, {@code BIP021-D7}).
     * {@code paramToArg[p]} is the source call-argument index {@code 0..argCount-1} supplying
     * parameter {@code p}, or {@code -1} if parameter {@code p} is omitted (or is the rest parameter).
     * {@code missingParamBits} has bit {@code p} set when {@code p < 32 && paramToArg[p] < 0 && p != restParam}.
     */
    public record ArgumentBinding(int[] paramToArg, int argCount, boolean reordered, int missingParamBits,
                                  int restParam, int[] restArgs, boolean[] restArgIsSpread,
                                  PrimitiveKind restElementKind, SpreadAdapt[] argAdapts) {
        private static final int[] NO_INTS = new int[0];
        private static final boolean[] NO_BOOLS = new boolean[0];

        public ArgumentBinding {
            restArgs = restArgs != null ? restArgs : NO_INTS;
            restArgIsSpread = restArgIsSpread != null ? restArgIsSpread : NO_BOOLS;
            restElementKind = restElementKind != null ? restElementKind : PrimitiveKind.NONE;
        }

        public ArgumentBinding(int[] paramToArg, int argCount, boolean reordered, int missingParamBits) {
            this(paramToArg, argCount, reordered, missingParamBits, -1, NO_INTS, NO_BOOLS, PrimitiveKind.NONE, null);
        }

        public boolean hasRest() {
            return restParam >= 0;
        }

        public boolean hasSpreads() {
            if (argAdapts != null) {
                for (SpreadAdapt a : argAdapts) {
                    if (a != null && a.kind() != SpreadKind.NONE) {
                        return true;
                    }
                }
            }
            for (boolean s : restArgIsSpread) {
                if (s) {
                    return true;
                }
            }
            return false;
        }

        public SpreadAdapt adaptForArg(int argIndex) {
            return argAdapts != null && argIndex >= 0 && argIndex < argAdapts.length && argAdapts[argIndex] != null
                    ? argAdapts[argIndex] : SpreadAdapt.NONE;
        }

        public boolean hasOmitted() {
            for (int p = 0; p < paramToArg.length; p++) {
                if (p != restParam && paramToArg[p] < 0) {
                    return true;
                }
            }
            return false;
        }

        public int paramCount() {
            return paramToArg.length;
        }

        public boolean isIdentityPositional() {
            return !reordered && !hasOmitted() && !hasRest() && !hasSpreads() && paramToArg.length == argCount;
        }

        /** Computes the callee's {@code $spec} optional-ordinal mask from {@code calleeOptionalParamBits}. */
        public int specMaskFor(int calleeOptionalParamBits, int calleeParamCount) {
            int mask = 0;
            int optBit = 0;
            for (int p = 0; p < calleeParamCount; p++) {
                if ((calleeOptionalParamBits & (1 << p)) != 0) {
                    if (p >= paramToArg.length || (p != restParam && paramToArg[p] < 0)) {
                        mask |= (1 << optBit);
                    }
                    optBit++;
                }
            }
            return mask;
        }
    }

    public record ParentRef(String babbleTypeId, Class<?> javaClass) {
    }

    public record MemberSpec(String name, boolean hasDefault, boolean required, boolean callable, FunctionSignature declaredSignature,
                             List<FunctionSignature> overloadSignatures, List<String> overloadSignatureKeys) {
        public MemberSpec(String name, boolean hasDefault, boolean required, boolean callable, FunctionSignature declaredSignature) {
            this(name, hasDefault, required, callable, declaredSignature, List.of(), List.of());
        }

        public boolean isOverloaded() {
            return overloadSignatures != null && !overloadSignatures.isEmpty();
        }
    }

    public record TypeHierarchyInfo(String id, ClassShape.Kind kind, String name, List<ParentRef> directParents,
                                    Set<String> allBabbleInterfaceIds, Set<Class<?>> allJavaInterfaces,
                                    Map<String, MemberSpec> interfaceMembers,
                                    Map<String, Integer> javaBridgeOverloads,
                                    Map<String, Integer> babbleBridgeOverloads) {
        public TypeHierarchyInfo(String id, ClassShape.Kind kind, String name, List<ParentRef> directParents,
                                 Set<String> allBabbleInterfaceIds, Set<Class<?>> allJavaInterfaces,
                                 Map<String, MemberSpec> interfaceMembers) {
            this(id, kind, name, directParents, allBabbleInterfaceIds, allJavaInterfaces, interfaceMembers, Map.of(), Map.of());
        }
    }

    public record SuperTargetInfo(String ownerTypeId, boolean ownerIsInterface,
                                  String directBabbleTypeId, Class<?> directJavaClass,
                                  String providerBabbleTypeId, Class<?> providerJavaClass,
                                  String memberName) {
    }

    public record IsTarget(String typeId, ClassShape.Kind kind, String jvmInternalName) {
        public IsTarget(String typeId, ClassShape.Kind kind) {
            this(typeId, kind, null);
        }
    }

    /** Java SAM interface (plus optional marker interfaces) for eager callable conversion ({@code BIP022-D7}). */
    public record JavaSamTarget(Class<?> samInterface, Class<?>[] markers) {
        private static final Class<?>[] NO_MARKERS = new Class<?>[0];

        public JavaSamTarget {
            markers = markers == null || markers.length == 0 ? NO_MARKERS : markers.clone();
        }

        public JavaSamTarget(Class<?> samInterface) {
            this(samInterface, NO_MARKERS);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof JavaSamTarget other
                    && samInterface == other.samInterface
                    && Arrays.equals(markers, other.markers);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(samInterface) + Arrays.hashCode(markers);
        }
    }

    public record InterfaceCallTarget(String interfaceTypeId, String memberName, FunctionSignature signature, boolean guaranteedCallable) {
    }

    /** Per-alternative runtime metadata for {@code overload (...)} construction (BIP021-D16). */
    public record OverloadAltMetaInfo(String signatureKey, String display, int minArity, int maxArity,
                                      int restIndex, String[] paramDescriptors) {
    }

    /** Coercion from an overload set expression to a single alternative (BIP021-D12). */
    public record OverloadSelection(int index, String signatureKey, boolean exactSet) {
    }

    /** One runtime type check at a union-split argument position (BIP021-D11 #4). */
    public record OverloadSplitCheck(int argIndex, List<String> descriptors, boolean isNull) {
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

    public enum ArrayCreationKind {
        SIZE,
        OF,
        FROM
    }

    public record ArrayCreationTarget(
            ArrayCreationKind kind,
            PrimitiveKind elemKind,
            Class<?> componentClass,
            String babbleTypeId,
            String babbleDisplayName,
            Set<String> babbleConformIds,
            int nestedDims
    ) {
    }

    public enum ArrayOpKind {
        SIZE,
        GET,
        SET,
        EQUALS,
        HASH_CODE,
        TO_STRING
    }

    public record ArrayOpTarget(
            ArrayOpKind kind,
            PrimitiveKind elemKind,
            Class<?> arrayClass,
            Class<?> componentClass
    ) {
    }

    /** Static lowering info for {@code throw expr} ({@code BIP028-D2}). */
    public record ThrowTarget(boolean isJavaThrowable) {
    }

    /** Static lowering info for a {@code catch} clause ({@code BIP028-D2}, {@code BIP028-D4}). */
    public record CatchTarget(
            String typeDescriptor,
            List<String> jvmCatchTypes,
            boolean catchesBabble,
            boolean catchesNonErrorAny,
            boolean catchesJavaThrowable,
            boolean skipsThrownException,
            List<IsTarget> babbleTargets
    ) {
    }

    /** Direct compiled Babble class/record field read target ({@code BIP014-I4}, {@code BIP034-P3}). */
    public record ClassFieldTarget(
            String classTypeId,
            String memberName,
            int memberIndex,
            PrimitiveKind fieldKind,
            boolean immutableField,
            boolean definitelyDefined
    ) {
        public ClassFieldTarget {
            fieldKind = fieldKind != null ? fieldKind : PrimitiveKind.NONE;
        }
    }

    /** Direct fast constructor target for a capture-free method-only compiled {@code record} ({@code BIP014-I4}). */
    public record RecordNewTarget(
            String classTypeId,
            PrimitiveKind[] paramKinds
    ) {
        public RecordNewTarget {
            paramKinds = paramKinds != null ? paramKinds.clone() : new PrimitiveKind[0];
        }
    }

    /** Direct compiled Babble class/record method or operator call target ({@code BIP014-I4}). */
    public record DirectMethodTarget(
            String classTypeId,
            String memberName,
            int overloadAltIndex,
            FunctionSignature signature,
            boolean recordValue
    ) {
    }

    /** Converged ownership and static record-type metadata of a function parameter ({@code BIP014-I4}). */
    public record ParamOwnership(
            boolean mut,
            boolean borrow,
            boolean unique,
            String recordTypeId
    ) {
        public static final ParamOwnership DEFAULT = new ParamOwnership(false, true, false, null);
    }
}

