package org.babblelang.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

/**
 * {@code COMPUTE_FRAMES} merges types with {@link #getCommonSuperClass}, which by default loads
 * classes through ASM's own class loader. This answers from both the runtime/classpath classes and
 * the generated class/interface metadata, and never tries to load a class currently being
 * generated.
 */
final class ScriptClassWriter extends ClassWriter {
    private static final String OBJECT = "java/lang/Object";

    private final ClassLoader runtimeLoader;
    private final String scriptClass;
    private final Map<String, List<String>> generatedClassParents;
    private final Map<String, List<String>> generatedInterfaceParents;

    ScriptClassWriter(
            ClassLoader runtimeLoader,
            String scriptClass,
            Map<String, List<String>> generatedClassParents,
            Map<String, List<String>> generatedInterfaceParents) {
        super(COMPUTE_FRAMES);
        this.runtimeLoader = runtimeLoader;
        this.scriptClass = scriptClass;
        this.generatedClassParents = generatedClassParents;
        this.generatedInterfaceParents = generatedInterfaceParents;
    }

    @Override
    protected ClassLoader getClassLoader() {
        return runtimeLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(scriptClass) || type2.equals(scriptClass)) {
            return OBJECT;
        }
        if (type1.equals(type2)) {
            return type1;
        }
        if (!exists(type1) || !exists(type2)) {
            return OBJECT;
        }
        if (isAssignable(type1, type2)) {
            return type1;
        }
        if (isAssignable(type2, type1)) {
            return type2;
        }
        boolean iface1 = isInterfaceType(type1);
        boolean iface2 = isInterfaceType(type2);
        if (iface1 && iface2) {
            return commonSuperInterface(type1, type2);
        }
        if (iface1 || iface2) {
            // One is an interface and the other is a class; neither is assignable to the other.
            return OBJECT;
        }
        String cur = type1;
        while (cur != null) {
            if (isAssignable(cur, type2)) {
                return cur;
            }
            cur = superclassOf(cur);
        }
        return OBJECT;
    }

    private boolean isGenerated(String internalName) {
        return generatedClassParents.containsKey(internalName)
                || generatedInterfaceParents.containsKey(internalName);
    }

    private boolean exists(String internalName) {
        return isGenerated(internalName) || tryLoad(internalName) != null;
    }

    private boolean isInterfaceType(String internalName) {
        if (generatedInterfaceParents.containsKey(internalName)) {
            return true;
        }
        if (generatedClassParents.containsKey(internalName)) {
            return false;
        }
        Class<?> cls = tryLoad(internalName);
        return cls != null && cls.isInterface();
    }

    private Class<?> tryLoad(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, runtimeLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private List<String> directParents(String internalName) {
        List<String> genClass = generatedClassParents.get(internalName);
        if (genClass != null) {
            return genClass;
        }
        List<String> genIface = generatedInterfaceParents.get(internalName);
        if (genIface != null) {
            return genIface;
        }
        Class<?> cls = tryLoad(internalName);
        if (cls == null) {
            return List.of();
        }
        List<String> parents = new ArrayList<>();
        if (cls.getSuperclass() != null) {
            parents.add(Type.getInternalName(cls.getSuperclass()));
        }
        for (Class<?> iface : cls.getInterfaces()) {
            parents.add(Type.getInternalName(iface));
        }
        return parents;
    }

    private String superclassOf(String internalName) {
        List<String> genClass = generatedClassParents.get(internalName);
        if (genClass != null) {
            for (String parent : genClass) {
                if (!isInterfaceType(parent)) {
                    return parent;
                }
            }
            return OBJECT;
        }
        if (generatedInterfaceParents.containsKey(internalName)) {
            return null;
        }
        Class<?> cls = tryLoad(internalName);
        if (cls == null || cls.getSuperclass() == null) {
            return null;
        }
        return Type.getInternalName(cls.getSuperclass());
    }

    private boolean isAssignable(String superType, String subType) {
        if (superType.equals(subType)) {
            return true;
        }
        if (OBJECT.equals(superType)) {
            return exists(subType);
        }
        boolean superGen = isGenerated(superType);
        if (!superGen && !isGenerated(subType)) {
            Class<?> sup = tryLoad(superType);
            Class<?> sub = tryLoad(subType);
            return sup != null && sub != null && sup.isAssignableFrom(sub);
        }
        Class<?> supCls = superGen ? null : tryLoad(superType);
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(subType);
        queue.add(subType);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(superType)) {
                return true;
            }
            boolean curGen = isGenerated(cur);
            if (!curGen) {
                if (!superGen) {
                    Class<?> curCls = tryLoad(cur);
                    if (supCls != null && curCls != null && supCls.isAssignableFrom(curCls)) {
                        return true;
                    }
                }
                continue;
            }
            for (String parent : directParents(cur)) {
                if (parent != null && visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return false;
    }

    private String commonSuperInterface(String type1, String type2) {
        Set<String> ancestors1 = ancestorInterfaces(type1);
        Set<String> ancestors2 = ancestorInterfaces(type2);
        List<String> common = new ArrayList<>();
        for (String candidate : ancestors1) {
            if (ancestors2.contains(candidate)) {
                common.add(candidate);
            }
        }
        if (common.isEmpty()) {
            return OBJECT;
        }
        List<String> mostSpecific = new ArrayList<>();
        for (String candidate : common) {
            boolean shadowed = false;
            for (String other : common) {
                if (!other.equals(candidate) && isAssignable(candidate, other)) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) {
                mostSpecific.add(candidate);
            }
        }
        return mostSpecific.size() == 1 ? mostSpecific.get(0) : OBJECT;
    }

    private Set<String> ancestorInterfaces(String root) {
        Set<String> result = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        result.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String parent : directParents(cur)) {
                if (parent != null
                        && !OBJECT.equals(parent)
                        && isInterfaceType(parent)
                        && result.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return result;
    }
}
