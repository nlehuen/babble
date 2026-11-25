package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.ir.hir.HirArrayElementType;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks {@link HirModule} programs for WebAssembly target portability ({@code BIP025-I0},
 * {@code BIP025-D3}, {@code BIP025-D6}, {@code BIP025-D8}, {@code BIP025-D12}).
 *
 * <p>Rejects JVM-only constructs ({@code java.*}/{@code javax.*} calls, Java interface
 * implementations, dynamic {@code import(expr)}, and non-portable JDK methods) with clean
 * {@code Error : Not supported on target wasm : ...} diagnostics, and provides {@link #isPortable}
 * for automatic 4-mode {@code .batest.md} classification.
 */
public final class WasmPortabilityChecker {
    private static final Set<String> PORTABLE_STRING_METHODS = Set.of(
            "length", "isEmpty", "isBlank", "charAt", "substring", "indexOf", "lastIndexOf",
            "startsWith", "endsWith", "contains", "replace", "trim", "strip",
            "toLowerCase", "toUpperCase", "equals", "equalsIgnoreCase", "compareTo",
            "hashCode", "toString", "concat", "repeat", "split", "toCharArray"
    );

    private static final Set<String> PORTABLE_COLLECTION_METHODS = Set.of(
            "size", "isEmpty", "clear", "contains", "containsKey", "containsValue",
            "add", "addAll", "remove", "get", "getFirst", "getLast", "set", "indexOf", "lastIndexOf",
            "subList", "put", "putAll", "getOrDefault", "keySet", "values", "entrySet", "iterator",
            "first", "last", "firstKey", "lastKey", "getKey", "getValue", "setValue",
            "equals", "hashCode", "toString", "of", "copyOf"
    );

    private static final Set<String> PORTABLE_ITERATOR_METHODS = Set.of(
            "hasNext", "next", "iterator"
    );

    private static final Set<String> PORTABLE_EXCEPTION_METHODS = Set.of(
            "getMessage", "getCause", "getSuppressed", "addSuppressed", "toString"
    );

    private static final Set<String> PORTABLE_APPENDABLE_METHODS = Set.of(
            "append"
    );

    private static final Set<String> WASM_BARE_FORBIDDEN_NAMES = Set.of(
            "print", "println", "affiche", "afficherc", "STDOUT", "STDERR", "STDIN", "args", "wasm"
    );

    public record Diagnostic(int line, String detail) {
        public String format() {
            return "Error : Not supported on target wasm : " + detail
                    + (line > 0 ? " (line " + line + ")" : "");
        }
    }

    private WasmPortabilityChecker() {
    }

    /** Returns every portability violation found across the given modules for {@code target}. */
    public static List<Diagnostic> check(List<HirModule> modules, CompilationTarget target) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (HirModule mod : modules) {
            checkModule(mod, target, diagnostics);
        }
        return diagnostics;
    }

    public static List<Diagnostic> check(HirModule module, CompilationTarget target) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        checkModule(module, target, diagnostics);
        return diagnostics;
    }

    public static boolean isPortable(List<HirModule> modules, CompilationTarget target) {
        return check(modules, target).isEmpty();
    }

    public static boolean isPortable(HirModule module, CompilationTarget target) {
        return check(module, target).isEmpty();
    }

    /**
     * Classifies a JVM {@link LoweringTargets.JavaCallTarget} into a Portable Core kind
     * ({@code "string"}, {@code "list"}, {@code "set"}, {@code "sortedset"}, {@code "map"},
     * {@code "sortedmap"}, {@code "entry"}, {@code "iterable"}, {@code "iterator"},
     * {@code "exception"}, {@code "appendable"}), or returns {@code null} if non-portable.
     */
    public static String portableCoreKind(LoweringTargets.JavaCallTarget target) {
        if (target == null || target.method() == null) {
            return null;
        }
        Method m = target.method();
        Class<?> owner = m.getDeclaringClass();
        String name = m.getName();
        if (owner == String.class) {
            return PORTABLE_STRING_METHODS.contains(name) ? "string" : null;
        }
        if (owner == java.util.List.class || owner == java.util.ArrayList.class || owner == java.util.Collection.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "list" : null;
        }
        if (owner == java.util.SortedSet.class || owner == java.util.TreeSet.class || owner == java.util.NavigableSet.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "sortedset" : null;
        }
        if (owner == java.util.Set.class || owner == java.util.LinkedHashSet.class || owner == java.util.HashSet.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "set" : null;
        }
        if (owner == java.util.SortedMap.class || owner == java.util.TreeMap.class || owner == java.util.NavigableMap.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "sortedmap" : null;
        }
        if (owner == java.util.Map.class || owner == java.util.LinkedHashMap.class || owner == java.util.HashMap.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "map" : null;
        }
        if (owner == java.util.Map.Entry.class || owner == java.util.AbstractMap.SimpleImmutableEntry.class) {
            return PORTABLE_COLLECTION_METHODS.contains(name) ? "entry" : null;
        }
        if (owner == Iterable.class) {
            return PORTABLE_ITERATOR_METHODS.contains(name) ? "iterable" : null;
        }
        if (owner == java.util.Iterator.class) {
            return PORTABLE_ITERATOR_METHODS.contains(name) ? "iterator" : null;
        }
        if (owner == org.babblelang.runtime.BabbleException.class || owner == Throwable.class) {
            return PORTABLE_EXCEPTION_METHODS.contains(name) ? "exception" : null;
        }
        if (owner == Appendable.class || owner == java.io.Writer.class || owner == java.io.PrintWriter.class) {
            return PORTABLE_APPENDABLE_METHODS.contains(name) ? "appendable" : null;
        }
        if (owner == Object.class && (name.equals("equals") || name.equals("hashCode") || name.equals("toString"))) {
            return "any";
        }
        return null;
    }

    private static void checkModule(HirModule mod, CompilationTarget target, List<Diagnostic> out) {
        if (target.isWasmBare() && !mod.wasmImports().isEmpty()) {
            for (LoweringTargets.WasmImportSpec spec : mod.wasmImports()) {
                out.add(new Diagnostic(0, "wasm." + spec.module() + "." + spec.name() + " (disallowed on wasm-bare)"));
            }
        }
        checkList(mod.topLevelStatements(), target, out);
        for (HirFunction fn : mod.functions().values()) {
            for (HirParam p : fn.params()) {
                if (p.defaultValue() != null) {
                    checkExpr(p.defaultValue(), target, out);
                }
            }
            checkList(fn.body(), target, out);
        }
        for (HirClass cls : mod.classes().values()) {
            if (!cls.javaInterfaces().isEmpty()) {
                for (Class<?> jf : cls.javaInterfaces()) {
                    if (jf != Appendable.class) {
                        out.add(new Diagnostic(cls.line(), jf.getName()));
                    }
                }
            }
            for (HirParam p : cls.params()) {
                if (p.defaultValue() != null) {
                    checkExpr(p.defaultValue(), target, out);
                }
            }
            checkList(cls.body(), target, out);
            if (cls.staticBlock() != null) {
                checkList(cls.staticBlock().body(), target, out);
            }
        }
        for (HirInterface iface : mod.interfaces().values()) {
            checkList(iface.body(), target, out);
            if (iface.staticBlock() != null) {
                checkList(iface.staticBlock().body(), target, out);
            }
        }
    }

    private static void checkList(List<HirExpr> list, CompilationTarget target, List<Diagnostic> out) {
        for (HirExpr e : list) {
            checkExpr(e, target, out);
        }
    }

    private static void checkCallArgs(List<HirCallArg> args, CompilationTarget target, List<Diagnostic> out) {
        for (HirCallArg a : args) {
            checkExpr(a.value(), target, out);
        }
    }

    private static void checkExpr(HirExpr expr, CompilationTarget target, List<Diagnostic> out) {
        if (expr == null) return;
        int line = expr.meta().line();
        switch (expr) {
            case HirExpr.NameRead nr -> {
                if ("java".equals(nr.name()) || "javax".equals(nr.name()) || "jdk".equals(nr.name())) {
                    out.add(new Diagnostic(line, nr.name() + ".*"));
                } else if (target.isWasmBare() && WASM_BARE_FORBIDDEN_NAMES.contains(nr.name())) {
                    out.add(new Diagnostic(line, nr.name() + " (disallowed on wasm-bare)"));
                }
            }
            case HirExpr.JavaFieldGet g -> {
                out.add(new Diagnostic(line, g.target().field().getDeclaringClass().getName() + "." + g.target().field().getName()));
                checkExpr(g.receiver(), target, out);
            }
            case HirExpr.JavaFieldAssign a -> {
                out.add(new Diagnostic(line, a.target().field().getDeclaringClass().getName() + "." + a.target().field().getName()));
                checkExpr(a.receiver(), target, out);
                checkExpr(a.value(), target, out);
            }
            case HirExpr.JavaConstructorCall c -> {
                if (c.target().constructor().getDeclaringClass() != org.babblelang.runtime.BabbleException.class) {
                    out.add(new Diagnostic(line, c.target().constructor().getDeclaringClass().getName()));
                }
                checkList(c.args(), target, out);
            }
            case HirExpr.JavaMethodCall c -> {
                if (portableCoreKind(c.target()) == null) {
                    out.add(new Diagnostic(line, c.target().method().getDeclaringClass().getName() + "." + c.target().method().getName()));
                }
                if (!c.pureStaticReceiver()) {
                    checkExpr(c.receiver(), target, out);
                }
                checkList(c.args(), target, out);
            }
            case HirExpr.JavaSamAdapt a -> {
                out.add(new Diagnostic(line, a.target().samInterface().getName()));
                checkExpr(a.value(), target, out);
            }
            case HirExpr.ArrayCreate c -> {
                if (containsNonPortableJava(c.elementType())) {
                    out.add(new Diagnostic(line, "array of " + c.componentClass().getName()));
                }
                checkList(c.args(), target, out);
            }
            case HirExpr.ArrayOp op -> {
                if (containsNonPortableJava(op.elementType())) {
                    out.add(new Diagnostic(line, "array of " + op.componentClass().getName()));
                }
                checkExpr(op.receiver(), target, out);
                checkList(op.args(), target, out);
            }
            case HirExpr.Is is -> {
                checkDescriptorPortable(is.descriptor(), line, out);
                checkExpr(is.left(), target, out);
            }
            case HirExpr.As as -> {
                if (as.samTarget() != null) {
                    out.add(new Diagnostic(line, as.samTarget().samInterface().getName()));
                }
                checkDescriptorPortable(as.targetDescriptor(), line, out);
                checkExpr(as.left(), target, out);
            }
            case HirExpr.DynamicCall c -> {
                if (c.callee() instanceof HirExpr.NameRead nr && ("import".equals(nr.name()) || "importe".equals(nr.name()))) {
                    if (c.args().size() != 1 || !(c.args().get(0).value() instanceof HirExpr.Const cst && cst.value() instanceof String path)) {
                        out.add(new Diagnostic(line, "dynamic import"));
                    } else if (!path.endsWith(".ba")) {
                        out.add(new Diagnostic(line, "Java package import : " + path));
                    }
                } else {
                    checkExpr(c.callee(), target, out);
                }
                checkCallArgs(c.args(), target, out);
            }
            case HirExpr.Try t -> {
                checkList(t.tryBody(), target, out);
                for (HirExpr.HirCatch hc : t.catches()) {
                    if (hc.target() != null && hc.target().jvmCatchTypes() != null) {
                        for (String jvmType : hc.target().jvmCatchTypes()) {
                            if (jvmType != null
                                    && !jvmType.equals("org/babblelang/runtime/BabbleException")
                                    && !jvmType.equals("org/babblelang/runtime/ThrownException")
                                    && !jvmType.equals("java/lang/Throwable")) {
                                out.add(new Diagnostic(hc.line(), jvmType.replace('/', '.')));
                            }
                        }
                    }
                    checkList(hc.body(), target, out);
                }
                checkList(t.finallyBody(), target, out);
            }
            case HirExpr.Const c -> {}
            case HirExpr.IntParseFallback f -> {}
            case HirExpr.CoreCollectionRef r -> {}
            case HirExpr.CoreExceptionRef r -> {}
            case HirExpr.VarRead v -> {}
            case HirExpr.ThisRef t -> {}
            case HirExpr.RecurseRef r -> {}
            case HirExpr.FunctionRef f -> {}
            case HirExpr.ClassExpr c -> {}
            case HirExpr.InterfaceExpr i -> {}
            case HirExpr.Continue c -> {}
            case HirExpr.Def d -> checkExpr(d.value(), target, out);
            case HirExpr.VarAssign a -> checkExpr(a.value(), target, out);
            case HirExpr.NameAssign a -> checkExpr(a.value(), target, out);
            case HirExpr.ThisFieldAssign a -> { checkExpr(a.receiver(), target, out); checkExpr(a.value(), target, out); }
            case HirExpr.DynamicFieldAssign a -> { checkExpr(a.receiver(), target, out); checkExpr(a.value(), target, out); }
            case HirExpr.ThisFieldGet g -> checkExpr(g.receiver(), target, out);
            case HirExpr.ModuleExportGet g -> checkExpr(g.receiver(), target, out);
            case HirExpr.DynamicSelect s -> checkExpr(s.receiver(), target, out);
            case HirExpr.Unary u -> checkExpr(u.operand(), target, out);
            case HirExpr.Binary b -> { checkExpr(b.left(), target, out); checkExpr(b.right(), target, out); }
            case HirExpr.Logic l -> { checkExpr(l.left(), target, out); checkExpr(l.right(), target, out); }
            case HirExpr.NonNull n -> checkExpr(n.operand(), target, out);
            case HirExpr.SpreadAdapt s -> {
                if (s.adapt() != null && s.adapt().elementSamTarget() != null) {
                    out.add(new Diagnostic(line, s.adapt().elementSamTarget().samInterface().getName()));
                }
                checkExpr(s.value(), target, out);
            }
            case HirExpr.Block b -> checkList(b.statements(), target, out);
            case HirExpr.If i -> { checkExpr(i.cond(), target, out); checkExpr(i.thenBranch(), target, out); checkExpr(i.elseBranch(), target, out); }
            case HirExpr.While w -> { checkExpr(w.cond(), target, out); checkExpr(w.body(), target, out); }
            case HirExpr.For f -> { checkExpr(f.iterable(), target, out); checkList(f.body(), target, out); }
            case HirExpr.Break b -> checkExpr(b.value(), target, out);
            case HirExpr.Return r -> checkExpr(r.value(), target, out);
            case HirExpr.Throw t -> checkExpr(t.value(), target, out);
            case HirExpr.CoreCollectionCall c -> checkCallArgs(c.args(), target, out);
            case HirExpr.CoreMethodCall c -> { checkExpr(c.receiver(), target, out); checkList(c.args(), target, out); }
            case HirExpr.SuperCall c -> {
                if (c.targetInfo() != null && c.targetInfo().directJavaClass() != null) {
                    out.add(new Diagnostic(line, c.targetInfo().directJavaClass().getName() + "." + c.memberName()));
                } else if (c.targetInfo() != null && c.targetInfo().providerJavaClass() != null) {
                    out.add(new Diagnostic(line, c.targetInfo().providerJavaClass().getName() + "." + c.memberName()));
                }
                checkCallArgs(c.args(), target, out);
            }
            case HirExpr.SelfRecurseCall c -> checkList(c.args(), target, out);
            case HirExpr.SelfTailCall c -> checkCallArgs(c.args(), target, out);
            case HirExpr.DirectBabbleCall c -> { checkExpr(c.callee(), target, out); checkList(c.args(), target, out); }
            case HirExpr.DirectThisMethodCall c -> { checkExpr(c.receiver(), target, out); checkCallArgs(c.args(), target, out); }
            case HirExpr.InterfaceCall c -> { checkExpr(c.receiver(), target, out); checkList(c.args(), target, out); }
            case HirExpr.SafeCall c -> { checkExpr(c.receiver(), target, out); checkCallArgs(c.args(), target, out); }
            case HirExpr.MakeOverloadSet m -> checkList(m.operands(), target, out);
            case HirExpr.OverloadSelect s -> checkExpr(s.set(), target, out);
            case HirExpr.OverloadDispatch d -> { checkExpr(d.callee(), target, out); checkList(d.args(), target, out); }
            case HirExpr.TypeApply t -> checkExpr(t.inner(), target, out);
            case HirExpr.RecordNew r -> { checkExpr(r.classRef(), target, out); checkList(r.args(), target, out); }
            case HirExpr.RecordReuse r -> { checkExpr(r.target(), target, out); checkList(r.args(), target, out); }
            case HirExpr.Move m -> checkExpr(m.operand(), target, out);
            case HirExpr.Freeze f -> checkExpr(f.operand(), target, out);
        }
    }

    private static void checkDescriptorPortable(String descriptor, int line, List<Diagnostic> out) {
        if (descriptor == null || descriptor.isEmpty()) {
            return;
        }
        checkDescNodePortable(WasmTypeDesc.parse(descriptor), line, out);
    }

    private static void checkDescNodePortable(WasmTypeDesc desc, int line, List<Diagnostic> out) {
        if (desc == null) {
            return;
        }
        if ("java".equals(desc.kind())) {
            String fqcn = desc.payload();
            if (!isPortableJavaTypeName(fqcn)) {
                out.add(new Diagnostic(line, fqcn));
            }
        } else {
            for (WasmTypeDesc m : desc.members()) {
                checkDescNodePortable(m, line, out);
            }
        }
    }

    private static boolean isPortableJavaTypeName(String fqcn) {
        if (fqcn.startsWith("[")) {
            String elem = fqcn;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.length() == 1) return true;
            if (elem.startsWith("L") && elem.endsWith(";")) {
                return isPortableJavaTypeName(elem.substring(1, elem.length() - 1));
            }
            return false;
        }
        return fqcn.equals("java.lang.String")
                || fqcn.equals("java.lang.Object")
                || fqcn.equals("java.lang.Iterable")
                || fqcn.equals("java.lang.Appendable")
                || fqcn.equals("java.util.Iterator")
                || fqcn.equals("java.util.List")
                || fqcn.equals("java.util.Set")
                || fqcn.equals("java.util.SortedSet")
                || fqcn.equals("java.util.Map")
                || fqcn.equals("java.util.SortedMap")
                || fqcn.equals("java.util.Map$Entry")
                || fqcn.equals("org.babblelang.runtime.BabbleException");
    }

    private static boolean containsNonPortableJava(HirArrayElementType type) {
        if (type instanceof HirArrayElementType.Array a) {
            return containsNonPortableJava(a.element());
        }
        return type instanceof HirArrayElementType.JavaClass jc && jc.cls() != Appendable.class;
    }
}
