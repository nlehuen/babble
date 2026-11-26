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
            "equals", "hashCode", "toString", "of", "copyOf", "ofEntries", "entry"
    );

    private static final Set<String> PORTABLE_CHAR_SEQUENCE_METHODS = Set.of(
            "length", "isEmpty", "charAt", "toString"
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

    private static final Set<String> PORTABLE_RANGE_METHODS = Set.of(
            "start", "end", "startInt", "endInt", "startLong", "endLong", "startChar", "endChar",
            "startDouble", "endDouble", "inclusive", "isEmpty",
            "contains", "containsInt", "containsLong", "containsChar", "containsDouble",
            "step", "reversed", "iterator", "equals", "hashCode", "toString"
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
        java.util.Map<String, LoweringTargets.WasmImportSpec> seenWasmImports = new java.util.LinkedHashMap<>();
        for (HirModule mod : modules) {
            checkModule(mod, target, seenWasmImports, diagnostics);
        }
        return diagnostics;
    }

    public static List<Diagnostic> check(HirModule module, CompilationTarget target) {
        return check(List.of(module), target);
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
        int arity = m.getParameterCount();
        if (owner == String.class) {
            if (!PORTABLE_STRING_METHODS.contains(name)) return null;
            if ("split".equals(name) && arity != 1 && arity != 2) return null;
            if ("replace".equals(name) && arity != 2) return null;
            return "string";
        }
        if (owner == CharSequence.class) {
            return PORTABLE_CHAR_SEQUENCE_METHODS.contains(name) ? "string" : null;
        }
        if (owner == java.util.List.class || owner == java.util.ArrayList.class || owner == java.util.Collection.class) {
            if (!PORTABLE_COLLECTION_METHODS.contains(name)) return null;
            if ("addAll".equals(name) && arity != 1) return null;
            return "list";
        }
        if (owner == java.util.SortedSet.class || owner == java.util.TreeSet.class || owner == java.util.NavigableSet.class) {
            if (!PORTABLE_COLLECTION_METHODS.contains(name)) return null;
            if ("addAll".equals(name) && arity != 1) return null;
            return "sortedset";
        }
        if (owner == java.util.Set.class || owner == java.util.LinkedHashSet.class || owner == java.util.HashSet.class) {
            if (!PORTABLE_COLLECTION_METHODS.contains(name)) return null;
            if ("addAll".equals(name) && arity != 1) return null;
            return "set";
        }
        if (owner == java.util.SortedMap.class || owner == java.util.TreeMap.class || owner == java.util.NavigableMap.class) {
            if (!PORTABLE_COLLECTION_METHODS.contains(name)) return null;
            if ("remove".equals(name) && arity != 1) return null;
            return "sortedmap";
        }
        if (owner == java.util.Map.class || owner == java.util.LinkedHashMap.class || owner == java.util.HashMap.class) {
            if (!PORTABLE_COLLECTION_METHODS.contains(name)) return null;
            if ("remove".equals(name) && arity != 1) return null;
            return "map";
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
            return PORTABLE_APPENDABLE_METHODS.contains(name) && arity == 1 ? "appendable" : null;
        }
        if (owner == org.babblelang.runtime.Range.class
                || owner == org.babblelang.runtime.IntRange.class
                || owner == org.babblelang.runtime.LongRange.class
                || owner == org.babblelang.runtime.CharRange.class
                || owner == org.babblelang.runtime.DoubleRange.class
                || owner == org.babblelang.runtime.ComparableRange.class) {
            return PORTABLE_RANGE_METHODS.contains(name) ? "range" : null;
        }
        if (owner == Object.class && (name.equals("equals") || name.equals("hashCode") || name.equals("toString"))) {
            return "any";
        }
        return null;
    }

    private static void checkModule(HirModule mod, CompilationTarget target,
                                    java.util.Map<String, LoweringTargets.WasmImportSpec> seenWasmImports,
                                    List<Diagnostic> out) {
        for (LoweringTargets.WasmImportSpec spec : mod.wasmImports()) {
            String key = spec.module() + "." + spec.name();
            if (target.isWasmBare()) {
                out.add(new Diagnostic(spec.line(), "wasm." + key + " (disallowed on wasm-bare)"));
                continue;
            }
            LoweringTargets.WasmImportSpec prev = seenWasmImports.putIfAbsent(key, spec);
            if (prev != null && !prev.sameSignature(spec)) {
                out.add(new Diagnostic(spec.line(), "conflicting wasm import signature : wasm." + key));
            }
            boolean badAbi = false;
            for (int i = 0; i < spec.paramKinds().length; i++) {
                if (spec.paramKinds()[i] == org.babblelang.analysis.PrimitiveKind.NONE && !spec.paramIsString()[i]) {
                    badAbi = true;
                    break;
                }
            }
            if (!badAbi && !spec.returnIsVoid()
                    && spec.returnKind() == org.babblelang.analysis.PrimitiveKind.NONE && !spec.returnIsString()) {
                badAbi = true;
            }
            if (badAbi) {
                out.add(new Diagnostic(spec.line(), "unsupported wasm import ABI type : wasm." + key));
            }
        }
        Set<String> topLevelDefined = new java.util.HashSet<>();
        for (HirExpr stmt : mod.topLevelStatements()) {
            if (stmt instanceof HirExpr.Def d && d.var() == null) {
                topLevelDefined.add(d.name());
            }
        }
        checkList(mod.topLevelStatements(), target, topLevelDefined, out);
        for (HirFunction fn : mod.functions().values()) {
            for (HirParam p : fn.params()) {
                if (p.defaultValue() != null) {
                    checkExpr(p.defaultValue(), target, topLevelDefined, out);
                }
            }
            checkList(fn.body(), target, topLevelDefined, out);
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
                    checkExpr(p.defaultValue(), target, topLevelDefined, out);
                }
            }
            checkList(cls.body(), target, topLevelDefined, out);
            if (cls.staticBlock() != null) {
                checkList(cls.staticBlock().body(), target, topLevelDefined, out);
            }
        }
        for (HirInterface iface : mod.interfaces().values()) {
            for (String jvmIface : iface.extendedJvmInterfaces()) {
                if (jvmIface != null
                        && (jvmIface.startsWith("java/") || jvmIface.startsWith("javax/") || jvmIface.startsWith("jdk/"))
                        && !"java/lang/Appendable".equals(jvmIface)) {
                    out.add(new Diagnostic(iface.line(), jvmIface.replace('/', '.')));
                }
            }
            checkList(iface.body(), target, topLevelDefined, out);
            if (iface.staticBlock() != null) {
                checkList(iface.staticBlock().body(), target, topLevelDefined, out);
            }
        }
    }

    private static void checkList(List<HirExpr> list, CompilationTarget target, Set<String> topLevelDefined, List<Diagnostic> out) {
        for (HirExpr e : list) {
            checkExpr(e, target, topLevelDefined, out);
        }
    }

    private static void checkCallArgs(List<HirCallArg> args, CompilationTarget target, Set<String> topLevelDefined, List<Diagnostic> out) {
        for (HirCallArg a : args) {
            checkExpr(a.value(), target, topLevelDefined, out);
        }
    }

    private static void checkExpr(HirExpr expr, CompilationTarget target, Set<String> topLevelDefined, List<Diagnostic> out) {
        if (expr == null) return;
        int line = expr.meta().line();
        switch (expr) {
            case HirExpr.NameRead nr -> {
                if ("java".equals(nr.name()) || "javax".equals(nr.name()) || "jdk".equals(nr.name())) {
                    out.add(new Diagnostic(line, nr.name() + ".*"));
                } else if ("STDIN".equals(nr.name()) && !topLevelDefined.contains("STDIN")) {
                    out.add(new Diagnostic(line, "STDIN"));
                } else if (("import".equals(nr.name()) || "importe".equals(nr.name())) && !topLevelDefined.contains(nr.name())) {
                    out.add(new Diagnostic(line, "dynamic import"));
                } else if (target.isWasmBare() && WASM_BARE_FORBIDDEN_NAMES.contains(nr.name())
                        && !topLevelDefined.contains(nr.name())) {
                    out.add(new Diagnostic(line, nr.name() + " (disallowed on wasm-bare)"));
                }
            }
            case HirExpr.JavaFieldGet g -> {
                out.add(new Diagnostic(line, g.target().field().getDeclaringClass().getName() + "." + g.target().field().getName()));
                checkExpr(g.receiver(), target, topLevelDefined, out);
            }
            case HirExpr.JavaFieldAssign a -> {
                out.add(new Diagnostic(line, a.target().field().getDeclaringClass().getName() + "." + a.target().field().getName()));
                checkExpr(a.receiver(), target, topLevelDefined, out);
                checkExpr(a.value(), target, topLevelDefined, out);
            }
            case HirExpr.JavaConstructorCall c -> {
                if (c.target().constructor().getDeclaringClass() != org.babblelang.runtime.BabbleException.class) {
                    out.add(new Diagnostic(line, c.target().constructor().getDeclaringClass().getName()));
                }
                if (!c.pureStaticCallee()) {
                    checkExpr(c.callee(), target, topLevelDefined, out);
                }
                checkList(c.args(), target, topLevelDefined, out);
            }
            case HirExpr.JavaMethodCall c -> {
                if (portableCoreKind(c.target()) == null) {
                    out.add(new Diagnostic(line, c.target().method().getDeclaringClass().getName() + "." + c.target().method().getName()));
                }
                if (!c.pureStaticReceiver()) {
                    checkExpr(c.receiver(), target, topLevelDefined, out);
                }
                checkList(c.args(), target, topLevelDefined, out);
            }
            case HirExpr.JavaSamAdapt a -> {
                out.add(new Diagnostic(line, a.target().samInterface().getName()));
                checkExpr(a.value(), target, topLevelDefined, out);
            }
            case HirExpr.ArrayCreate c -> {
                if (containsNonPortableJava(c.elementType())) {
                    out.add(new Diagnostic(line, "array of " + c.componentClass().getName()));
                }
                checkList(c.args(), target, topLevelDefined, out);
            }
            case HirExpr.ArrayOp op -> {
                if (containsNonPortableJava(op.elementType())) {
                    out.add(new Diagnostic(line, "array of " + op.componentClass().getName()));
                }
                checkExpr(op.receiver(), target, topLevelDefined, out);
                checkList(op.args(), target, topLevelDefined, out);
            }
            case HirExpr.Is is -> {
                checkDescriptorPortable(is.descriptor(), line, out);
                checkExpr(is.left(), target, topLevelDefined, out);
            }
            case HirExpr.As as -> {
                if (as.samTarget() != null) {
                    out.add(new Diagnostic(line, as.samTarget().samInterface().getName()));
                }
                checkDescriptorPortable(as.targetDescriptor(), line, out);
                checkExpr(as.left(), target, topLevelDefined, out);
            }
            case HirExpr.DynamicCall c -> {
                if (c.callee() instanceof HirExpr.NameRead nr && ("import".equals(nr.name()) || "importe".equals(nr.name()))) {
                    if (c.args().size() != 1 || !(c.args().get(0).value() instanceof HirExpr.Const cst && cst.value() instanceof String path)) {
                        out.add(new Diagnostic(line, "dynamic import"));
                    } else if (!path.endsWith(".ba")) {
                        out.add(new Diagnostic(line, "Java package import : " + path));
                    }
                } else {
                    checkExpr(c.callee(), target, topLevelDefined, out);
                }
                checkCallArgs(c.args(), target, topLevelDefined, out);
            }
            case HirExpr.Try t -> {
                checkList(t.tryBody(), target, topLevelDefined, out);
                for (HirExpr.HirCatch hc : t.catches()) {
                    if (hc.target() != null) {
                        if (hc.target().typeDescriptor() != null && !hc.target().typeDescriptor().isEmpty()) {
                            checkDescriptorPortable(hc.target().typeDescriptor(), hc.line(), out);
                        }
                        if (hc.target().jvmCatchTypes() != null) {
                            for (String jvmType : hc.target().jvmCatchTypes()) {
                                if (jvmType != null
                                        && !jvmType.equals("org/babblelang/runtime/ThrownException")
                                        && !isPortableJavaTypeName(jvmType.replace('/', '.'), false)) {
                                    out.add(new Diagnostic(hc.line(), jvmType.replace('/', '.')));
                                }
                            }
                        }
                    }
                    checkList(hc.body(), target, topLevelDefined, out);
                }
                checkList(t.finallyBody(), target, topLevelDefined, out);
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
            case HirExpr.Def d -> checkExpr(d.value(), target, topLevelDefined, out);
            case HirExpr.VarAssign a -> checkExpr(a.value(), target, topLevelDefined, out);
            case HirExpr.NameAssign a -> checkExpr(a.value(), target, topLevelDefined, out);
            case HirExpr.ThisFieldAssign a -> { checkExpr(a.receiver(), target, topLevelDefined, out); checkExpr(a.value(), target, topLevelDefined, out); }
            case HirExpr.DynamicFieldAssign a -> { checkExpr(a.receiver(), target, topLevelDefined, out); checkExpr(a.value(), target, topLevelDefined, out); }
            case HirExpr.ThisFieldGet g -> checkExpr(g.receiver(), target, topLevelDefined, out);
            case HirExpr.ModuleExportGet g -> checkExpr(g.receiver(), target, topLevelDefined, out);
            case HirExpr.DynamicSelect s -> checkExpr(s.receiver(), target, topLevelDefined, out);
            case HirExpr.Unary u -> checkExpr(u.operand(), target, topLevelDefined, out);
            case HirExpr.Binary b -> { checkExpr(b.left(), target, topLevelDefined, out); checkExpr(b.right(), target, topLevelDefined, out); }
            case HirExpr.Range r -> {
                if (!r.elemKind().isPrimitive() && !r.stringElement()) {
                    out.add(new Diagnostic(line, "range of Comparable"));
                }
                checkExpr(r.start(), target, topLevelDefined, out);
                checkExpr(r.end(), target, topLevelDefined, out);
            }
            case HirExpr.Logic l -> { checkExpr(l.left(), target, topLevelDefined, out); checkExpr(l.right(), target, topLevelDefined, out); }
            case HirExpr.NonNull n -> checkExpr(n.operand(), target, topLevelDefined, out);
            case HirExpr.SpreadAdapt s -> {
                if (s.adapt() != null && s.adapt().elementSamTarget() != null) {
                    out.add(new Diagnostic(line, s.adapt().elementSamTarget().samInterface().getName()));
                }
                checkExpr(s.value(), target, topLevelDefined, out);
            }
            case HirExpr.Block b -> checkList(b.statements(), target, topLevelDefined, out);
            case HirExpr.If i -> { checkExpr(i.cond(), target, topLevelDefined, out); checkExpr(i.thenBranch(), target, topLevelDefined, out); checkExpr(i.elseBranch(), target, topLevelDefined, out); }
            case HirExpr.While w -> { checkExpr(w.cond(), target, topLevelDefined, out); checkExpr(w.body(), target, topLevelDefined, out); }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    checkExpr(f.countedRange().start(), target, topLevelDefined, out);
                    checkExpr(f.countedRange().end(), target, topLevelDefined, out);
                    checkExpr(f.countedRange().step(), target, topLevelDefined, out);
                } else {
                    checkExpr(f.iterable(), target, topLevelDefined, out);
                }
                checkList(f.body(), target, topLevelDefined, out);
            }
            case HirExpr.Break b -> checkExpr(b.value(), target, topLevelDefined, out);
            case HirExpr.Return r -> checkExpr(r.value(), target, topLevelDefined, out);
            case HirExpr.Throw t -> checkExpr(t.value(), target, topLevelDefined, out);
            case HirExpr.CoreCollectionCall c -> checkCallArgs(c.args(), target, topLevelDefined, out);
            case HirExpr.CoreMethodCall c -> { checkExpr(c.receiver(), target, topLevelDefined, out); checkList(c.args(), target, topLevelDefined, out); }
            case HirExpr.SuperCall c -> {
                if (c.targetInfo() != null && c.targetInfo().directJavaClass() != null) {
                    out.add(new Diagnostic(line, c.targetInfo().directJavaClass().getName() + "." + c.memberName()));
                } else if (c.targetInfo() != null && c.targetInfo().providerJavaClass() != null) {
                    out.add(new Diagnostic(line, c.targetInfo().providerJavaClass().getName() + "." + c.memberName()));
                }
                checkCallArgs(c.args(), target, topLevelDefined, out);
            }
            case HirExpr.SelfRecurseCall c -> checkList(c.args(), target, topLevelDefined, out);
            case HirExpr.SelfTailCall c -> checkCallArgs(c.args(), target, topLevelDefined, out);
            case HirExpr.DirectBabbleCall c -> { checkExpr(c.callee(), target, topLevelDefined, out); checkList(c.args(), target, topLevelDefined, out); }
            case HirExpr.DirectThisMethodCall c -> { checkExpr(c.receiver(), target, topLevelDefined, out); checkCallArgs(c.args(), target, topLevelDefined, out); }
            case HirExpr.InterfaceCall c -> { checkExpr(c.receiver(), target, topLevelDefined, out); checkList(c.args(), target, topLevelDefined, out); }
            case HirExpr.SafeCall c -> { checkExpr(c.receiver(), target, topLevelDefined, out); checkCallArgs(c.args(), target, topLevelDefined, out); }
            case HirExpr.MakeOverloadSet m -> checkList(m.operands(), target, topLevelDefined, out);
            case HirExpr.OverloadSelect s -> checkExpr(s.set(), target, topLevelDefined, out);
            case HirExpr.OverloadDispatch d -> { checkExpr(d.callee(), target, topLevelDefined, out); checkList(d.args(), target, topLevelDefined, out); }
            case HirExpr.TypeApply t -> checkExpr(t.inner(), target, topLevelDefined, out);
            case HirExpr.RecordNew r -> { checkExpr(r.classRef(), target, topLevelDefined, out); checkList(r.args(), target, topLevelDefined, out); }
            case HirExpr.RecordReuse r -> { checkExpr(r.target(), target, topLevelDefined, out); checkList(r.args(), target, topLevelDefined, out); }
            case HirExpr.Move m -> checkExpr(m.operand(), target, topLevelDefined, out);
            case HirExpr.Freeze f -> checkExpr(f.operand(), target, topLevelDefined, out);
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
            if (!isPortableJavaTypeName(fqcn, false)) {
                out.add(new Diagnostic(line, fqcn));
            }
        } else {
            for (WasmTypeDesc m : desc.members()) {
                checkDescNodePortable(m, line, out);
            }
        }
    }

    private static boolean isPortableJavaTypeName(String fqcn, boolean insideArray) {
        if (fqcn.startsWith("[")) {
            String elem = fqcn;
            while (elem.startsWith("[")) elem = elem.substring(1);
            if (elem.length() == 1) return true;
            if (elem.startsWith("L") && elem.endsWith(";")) {
                return isPortableJavaTypeName(elem.substring(1, elem.length() - 1), true);
            }
            return false;
        }
        if (insideArray && (fqcn.equals("java.lang.Byte") || fqcn.equals("java.lang.Short")
                || fqcn.equals("java.lang.Integer") || fqcn.equals("java.lang.Long")
                || fqcn.equals("java.lang.Float") || fqcn.equals("java.lang.Double")
                || fqcn.equals("java.lang.Boolean") || fqcn.equals("java.lang.Character"))) {
            return true;
        }
        return fqcn.equals("java.lang.String")
                || fqcn.equals("java.lang.CharSequence")
                || fqcn.equals("java.lang.Object")
                || fqcn.equals("java.lang.Iterable")
                || fqcn.equals("java.lang.Appendable")
                || fqcn.equals("java.io.Writer")
                || fqcn.equals("java.io.PrintWriter")
                || fqcn.equals("java.util.Collection")
                || fqcn.equals("java.util.Iterator")
                || fqcn.equals("java.util.List")
                || fqcn.equals("java.util.Set")
                || fqcn.equals("java.util.SortedSet")
                || fqcn.equals("java.util.Map")
                || fqcn.equals("java.util.SortedMap")
                || fqcn.equals("java.util.Map$Entry")
                || fqcn.equals("java.lang.Throwable")
                || fqcn.equals("java.lang.Exception")
                || fqcn.equals("java.lang.RuntimeException")
                || fqcn.equals("org.babblelang.runtime.BabbleException")
                || fqcn.equals("org.babblelang.runtime.Range")
                || fqcn.equals("org.babblelang.runtime.IntRange")
                || fqcn.equals("org.babblelang.runtime.LongRange")
                || fqcn.equals("org.babblelang.runtime.CharRange")
                || fqcn.equals("org.babblelang.runtime.DoubleRange")
                || fqcn.equals("org.babblelang.runtime.ComparableRange");
    }

    private static boolean containsNonPortableJava(HirArrayElementType type) {
        if (type instanceof HirArrayElementType.Array a) {
            return containsNonPortableJava(a.element());
        }
        return type instanceof HirArrayElementType.JavaClass jc && jc.cls() != Appendable.class;
    }
}
