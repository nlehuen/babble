package org.babblelang.ir.hir;

import org.babblelang.analysis.PrimitiveKind;

/**
 * Target-neutral element type descriptor for {@link HirExpr.ArrayCreate} and {@link HirExpr.ArrayOp}
 * ({@code BIP025-D3}).
 *
 * <p>Replaces direct {@code Class<?>} dependencies for non-JVM backends while preserving exact
 * runtime element type information for covariant {@code $RefArray} checks ({@code BIP020-D8}).
 */
public sealed interface HirArrayElementType permits
        HirArrayElementType.Primitive,
        HirArrayElementType.StringType,
        HirArrayElementType.BabbleType,
        HirArrayElementType.Array,
        HirArrayElementType.CoreCollection,
        HirArrayElementType.CoreException,
        HirArrayElementType.Any,
        HirArrayElementType.JavaClass {

    record Primitive(PrimitiveKind kind) implements HirArrayElementType {
    }

    record StringType() implements HirArrayElementType {
    }

    record BabbleType(String typeId, boolean isInterface) implements HirArrayElementType {
    }

    record Array(HirArrayElementType element) implements HirArrayElementType {
    }

    record CoreCollection(String kind) implements HirArrayElementType {
    }

    record CoreException() implements HirArrayElementType {
    }

    record Any() implements HirArrayElementType {
    }

    record JavaClass(Class<?> cls) implements HirArrayElementType {
    }

    static HirArrayElementType from(PrimitiveKind elemKind, String babbleTypeId, boolean babbleIsInterface,
                                    int nestedDims, Class<?> componentClass) {
        if (elemKind != null && elemKind.isPrimitive()) {
            return new Primitive(elemKind);
        }
        HirArrayElementType base;
        if (babbleTypeId != null) {
            base = new BabbleType(babbleTypeId, babbleIsInterface);
        } else if (componentClass == null || componentClass == Object.class) {
            base = new Any();
        } else if (componentClass == String.class) {
            base = new StringType();
        } else if (componentClass == org.babblelang.runtime.BabbleException.class) {
            base = new CoreException();
        } else if (componentClass.isArray()) {
            base = fromJavaClass(componentClass);
        } else {
            String core = coreCollectionKind(componentClass);
            base = core != null ? new CoreCollection(core) : new JavaClass(componentClass);
        }
        for (int i = 0; i < nestedDims; i++) {
            base = new Array(base);
        }
        return base;
    }

    static HirArrayElementType fromJavaClass(Class<?> cls) {
        if (cls == null || cls == Object.class) {
            return new Any();
        }
        if (cls.isPrimitive()) {
            if (cls == byte.class) return new Primitive(PrimitiveKind.BYTE);
            if (cls == short.class) return new Primitive(PrimitiveKind.SHORT);
            if (cls == int.class) return new Primitive(PrimitiveKind.INT);
            if (cls == long.class) return new Primitive(PrimitiveKind.LONG);
            if (cls == float.class) return new Primitive(PrimitiveKind.FLOAT);
            if (cls == double.class) return new Primitive(PrimitiveKind.DOUBLE);
            if (cls == boolean.class) return new Primitive(PrimitiveKind.BOOL);
            if (cls == char.class) return new Primitive(PrimitiveKind.CHAR);
        }
        if (cls == String.class) {
            return new StringType();
        }
        if (cls == org.babblelang.runtime.BabbleException.class) {
            return new CoreException();
        }
        if (cls.isArray()) {
            return new Array(fromJavaClass(cls.getComponentType()));
        }
        String core = coreCollectionKind(cls);
        if (core != null) {
            return new CoreCollection(core);
        }
        return new JavaClass(cls);
    }

    private static String coreCollectionKind(Class<?> cls) {
        if (cls == java.util.List.class || cls == java.util.ArrayList.class) return "list";
        if (cls == java.util.SortedSet.class || cls == java.util.TreeSet.class) return "sortedset";
        if (cls == java.util.Set.class || cls == java.util.LinkedHashSet.class) return "set";
        if (cls == java.util.SortedMap.class || cls == java.util.TreeMap.class) return "sortedmap";
        if (cls == java.util.Map.class || cls == java.util.LinkedHashMap.class) return "map";
        if (cls == java.util.Map.Entry.class) return "entry";
        return null;
    }
}
