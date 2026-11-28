package org.babblelang.compiler;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirRewriter;
import org.babblelang.runtime.Implicits;
import org.babblelang.runtime.Module;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * AOT jar post-processing for {@link Babblec} ({@code BIP006}) :
 *
 * <ol>
 *   <li>Generates a minimal {@code babble/$Implicits} root namespace factory binding only the
 *       implicits referenced by statically compiled modules.
 *   <li>Tree-shakes {@code org/babblelang/**} runtime classes via an ASM constant-pool and bytecode
 *       reachability walk seeded from compiled user classes and {@code Launcher}.
 *   <li>Strips {@code LocalVariableTable} / {@code LocalVariableTypeTable} attributes from bundled
 *       runtime classes (unless {@code -dbg} is passed) while preserving {@code LineNumberTable}
 *       and {@code SourceFile}.
 * </ol>
 */
final class RuntimeShaker {
    static final String AOT_IMPLICITS_INTERNAL = "babble/$Implicits";
    static final String AOT_IMPLICITS_BINARY = "babble.$Implicits";

    private static final String RUNTIME = "org/babblelang/runtime/";
    private static final String NATIVES = "org/babblelang/engine/impl/natives/";
    private static final String JAVA_NATIVES = NATIVES + "java/";
    private static final String NAMESPACE = RUNTIME + "Namespace";
    private static final String SLOT = RUNTIME + "Slot";
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z_$][\\w/$]*);");

    private RuntimeShaker() {}

    /**
     * Walks the final compiled {@link HirModule} to determine which root implicits are referenced
     * at runtime and whether any dynamic {@code import(expr)} or Java package import is present.
     */
    static CompiledBabbleScript.AotUsage analyzeAotUsage(HirModule hir) {
        boolean[] flags = new boolean[2]; // [0] = hasDynamicImport, [1] = hasJavaImport
        Set<String> used = new TreeSet<>();
        UnaryOperator<HirExpr> walk =
                new UnaryOperator<>() {
                    @Override
                    public HirExpr apply(HirExpr expr) {
                        if (expr == null) {
                            return null;
                        }
                        switch (expr) {
                            case HirExpr.DynamicCall dc -> {
                                if (dc.callee() instanceof HirExpr.NameRead nr
                                        && ("import".equals(nr.name())
                                                || "importe".equals(nr.name()))) {
                                    used.add(nr.name());
                                    if (dc.args().size() == 1
                                            && dc.args().get(0).name() == null
                                            && dc.args().get(0).value() instanceof HirExpr.Const c
                                            && c.value() instanceof String s) {
                                        if (!s.endsWith(Module.EXTENSION)) {
                                            flags[1] = true;
                                        }
                                    } else {
                                        flags[0] = true;
                                    }
                                    for (HirCallArg arg : dc.args()) {
                                        apply(arg.value());
                                    }
                                    return expr;
                                }
                            }
                            case HirExpr.NameRead nr -> {
                                String name = nr.name();
                                if ("import".equals(name) || "importe".equals(name)) {
                                    flags[0] = true;
                                    used.add(name);
                                } else if (Implicits.isImplicit(name)) {
                                    used.add(name);
                                    if ("java".equals(name) || "javax".equals(name)) {
                                        flags[1] = true;
                                    }
                                }
                                return expr;
                            }
                            case HirExpr.JavaConstructorCall c -> {
                                if (!c.pureStaticCallee() && c.callee() != null) {
                                    apply(c.callee());
                                }
                                for (HirExpr arg : c.args()) {
                                    apply(arg);
                                }
                                return expr;
                            }
                            case HirExpr.JavaMethodCall c -> {
                                if (!(c.target().isStatic() && c.pureStaticReceiver())) {
                                    apply(c.receiver());
                                }
                                for (HirExpr arg : c.args()) {
                                    apply(arg);
                                }
                                return expr;
                            }
                            case HirExpr.JavaFieldGet g -> {
                                if (!(g.target().isStatic() && g.pureStaticReceiver())) {
                                    apply(g.receiver());
                                }
                                return expr;
                            }
                            case HirExpr.JavaFieldAssign a -> {
                                if (!(a.target().isStatic() && a.pureStaticReceiver())) {
                                    apply(a.receiver());
                                }
                                apply(a.value());
                                return expr;
                            }
                            default -> {}
                        }
                        HirRewriter.rewriteChildren(expr, this);
                        return expr;
                    }
                };
        HirRewriter.rewriteModuleBodies(
                hir,
                stmts -> {
                    for (HirExpr s : stmts) {
                        walk.apply(s);
                    }
                    return stmts;
                });
        return new CompiledBabbleScript.AotUsage(flags[0], flags[1], used);
    }

    /**
     * Generates {@code babble/$Implicits.class} with {@code public static Namespace create()}
     * binding only the root implicits in {@code usedImplicits}.
     */
    static byte[] generateImplicitsClass(Set<String> usedImplicits, boolean hasJavaImport) {
        Set<String> sortedImplicits = new TreeSet<>(usedImplicits);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                V21,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                AOT_IMPLICITS_INTERNAL,
                null,
                "java/lang/Object",
                null);
        MethodVisitor mv =
                cw.visitMethod(
                        ACC_PUBLIC | ACC_STATIC, "create", "()L" + NAMESPACE + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, NAMESPACE);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, NAMESPACE, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 0);

        boolean needsJavaPkg =
                sortedImplicits.contains("java") || sortedImplicits.contains("javax");
        boolean needsImport =
                sortedImplicits.contains("import") || sortedImplicits.contains("importe");
        boolean useFullImport = hasJavaImport || needsJavaPkg;
        int importLocal = -1;
        if (needsImport || useFullImport) {
            importLocal = 1;
            String impCls =
                    useFullImport
                            ? JAVA_NATIVES + "ImportFunction"
                            : RUNTIME + "ModuleImportFunction";
            mv.visitTypeInsn(NEW, impCls);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, impCls, "<init>", "()V", false);
            mv.visitVarInsn(ASTORE, importLocal);
        }
        if (sortedImplicits.contains("java")) {
            emitDefineJavaPkg(mv, importLocal, "java");
        }
        if (sortedImplicits.contains("javax")) {
            emitDefineJavaPkg(mv, importLocal, "javax");
        }
        if (sortedImplicits.contains("import")) {
            emitDefineLocal(mv, "import", importLocal);
        }
        if (sortedImplicits.contains("importe")) {
            emitDefineLocal(mv, "importe", importLocal);
        }
        for (String name : sortedImplicits) {
            switch (name) {
                case "print", "affiche" -> emitDefinePrint(mv, name, false);
                case "println", "afficherc" -> emitDefinePrint(mv, name, true);
                case "assert", "suppose" -> emitDefineNoArg(mv, name, NATIVES + "AssertFunction");
                case "same" -> emitDefineNoArg(mv, name, NATIVES + "SameFunction");
                case "classof" -> emitDefineNoArg(mv, name, NATIVES + "ClassofFunction");
                case "move", "freeze" ->
                        emitDefineStringArg(mv, name, NATIVES + "IdentityFunction");
                case "discard" -> emitDefineNoArg(mv, name, NATIVES + "DiscardFunction");
                case "byte" -> emitDefineConvert(mv, name, "BYTE");
                case "short" -> emitDefineConvert(mv, name, "SHORT");
                case "int" -> emitDefineConvert(mv, name, "INT");
                case "long" -> emitDefineConvert(mv, name, "LONG");
                case "float" -> emitDefineConvert(mv, name, "FLOAT");
                case "double" -> emitDefineConvert(mv, name, "DOUBLE");
                case "bool" -> emitDefineConvert(mv, name, "BOOL");
                case "char" -> emitDefineConvert(mv, name, "CHAR");
                case "string" -> emitDefineConvert(mv, name, "STRING");
                default -> {}
            }
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitDefineJavaPkg(MethodVisitor mv, int importLocal, String pkg) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(pkg);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitVarInsn(ALOAD, importLocal);
        mv.visitLdcInsn(pkg);
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                JAVA_NATIVES + "ImportFunction",
                "getPackage",
                "(Ljava/lang/String;)L" + JAVA_NATIVES + "JavaPackage;",
                false);
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    private static void emitDefineLocal(MethodVisitor mv, String name, int local) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitVarInsn(ALOAD, local);
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    private static void emitDefinePrint(MethodVisitor mv, String name, boolean newline) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitTypeInsn(NEW, NATIVES + "PrintFunction");
        mv.visitInsn(DUP);
        mv.visitInsn(newline ? ICONST_1 : ICONST_0);
        mv.visitMethodInsn(INVOKESPECIAL, NATIVES + "PrintFunction", "<init>", "(Z)V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    private static void emitDefineNoArg(MethodVisitor mv, String name, String cls) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitTypeInsn(NEW, cls);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, cls, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    private static void emitDefineStringArg(MethodVisitor mv, String name, String cls) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitTypeInsn(NEW, cls);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESPECIAL, cls, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    private static void emitDefineConvert(MethodVisitor mv, String name, String field) {
        String cls = NATIVES + "ConvertFunction";
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, NAMESPACE, "define", "(Ljava/lang/String;Z)L" + SLOT + ";", false);
        mv.visitFieldInsn(GETSTATIC, cls, field, "L" + cls + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, SLOT, "set", "(Ljava/lang/Object;)V", false);
    }

    /**
     * Returns only the entries of {@code allRuntimeClasses} transitively reachable from {@code
     * programClasses} and {@code Launcher}.
     */
    static Map<String, byte[]> shake(
            Map<String, byte[]> programClasses, Map<String, byte[]> allRuntimeClasses) {
        Set<String> reachable = new HashSet<>();
        Deque<String> worklist = new ArrayDeque<>();

        for (String root :
                new String[] {
                    RUNTIME + "Launcher.class",
                    RUNTIME + "ProgramIndex.class",
                    RUNTIME + "ModuleCompiler.class"
                }) {
            if (allRuntimeClasses.containsKey(root) && reachable.add(root)) {
                worklist.add(root);
            }
        }
        for (byte[] bytes : programClasses.values()) {
            for (String ref : collectRefs(bytes)) {
                String key = ref + ".class";
                if (allRuntimeClasses.containsKey(key) && reachable.add(key)) {
                    worklist.add(key);
                }
            }
        }
        while (!worklist.isEmpty()) {
            String current = worklist.poll();
            byte[] bytes = allRuntimeClasses.get(current);
            if (bytes == null) {
                continue;
            }
            for (String ref : collectRefs(bytes)) {
                String key = ref + ".class";
                if (allRuntimeClasses.containsKey(key) && reachable.add(key)) {
                    worklist.add(key);
                }
            }
        }
        Map<String, byte[]> trimmed = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : allRuntimeClasses.entrySet()) {
            if (reachable.contains(e.getKey())) {
                trimmed.put(e.getKey(), e.getValue());
            }
        }
        return trimmed;
    }

    /**
     * Strips {@code LocalVariableTable} and {@code LocalVariableTypeTable} attributes from a
     * runtime {@code .class} file while preserving {@code LineNumberTable} and {@code SourceFile}.
     */
    static byte[] stripDebugLocals(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(
                new ClassVisitor(ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        MethodVisitor mv =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitLocalVariable(
                                    String n, String d, String s, Label start, Label end, int idx) {
                                // Drop LocalVariableTable / LocalVariableTypeTable entries.
                            }
                        };
                    }
                },
                0);
        return cw.toByteArray();
    }

    /** Collects all internal class names referenced by {@code classBytes}. */
    static Set<String> collectRefs(byte[] classBytes) {
        Set<String> refs = new HashSet<>();
        ClassReader cr = new ClassReader(classBytes);
        // 1. Scan constant pool entries (handles class constants, descriptors, signatures).
        char[] buf = new char[cr.getMaxStringLength()];
        int itemCount = cr.getItemCount();
        for (int i = 1; i < itemCount; i++) {
            int offset = cr.getItem(i);
            if (offset == 0) {
                continue;
            }
            int tag = cr.readByte(offset - 1);
            if (tag == 7) { // CONSTANT_Class (u2 name_index at offset)
                addInternalOrArray(cr.readUTF8(offset, buf), refs);
            } else if (tag == 12) { // CONSTANT_NameAndType (u2 name_index, u2 descriptor_index)
                addDesc(cr.readUTF8(offset + 2, buf), refs);
            } else if (tag == 16) { // CONSTANT_MethodType (u2 descriptor_index at offset)
                addDesc(cr.readUTF8(offset, buf), refs);
            }
        }
        // 2. Also walk ASM visitor for indy/condy BSM handles, nested ConstantDynamic args, etc.
        cr.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        addInternalOrArray(superName, refs);
                        if (interfaces != null) {
                            for (String itf : interfaces) {
                                addInternalOrArray(itf, refs);
                            }
                        }
                    }

                    @Override
                    public void visitPermittedSubclass(String permittedSubclass) {
                        addInternalOrArray(permittedSubclass, refs);
                    }

                    @Override
                    public FieldVisitor visitField(
                            int access, String name, String desc, String sig, Object value) {
                        addDesc(desc, refs);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] exceptions) {
                        addDesc(desc, refs);
                        if (exceptions != null) {
                            for (String ex : exceptions) {
                                addInternalOrArray(ex, refs);
                            }
                        }
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                addInternalOrArray(type, refs);
                            }

                            @Override
                            public void visitFieldInsn(
                                    int opcode, String owner, String n, String d) {
                                addInternalOrArray(owner, refs);
                                addDesc(d, refs);
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String n, String d, boolean itf) {
                                addInternalOrArray(owner, refs);
                                addDesc(d, refs);
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String n, String d, Handle bsm, Object... bsmArgs) {
                                addDesc(d, refs);
                                addHandle(bsm, refs);
                                for (Object arg : bsmArgs) {
                                    addBsmArg(arg, refs);
                                }
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                addBsmArg(value, refs);
                            }

                            @Override
                            public void visitTryCatchBlock(
                                    Label start, Label end, Label handler, String type) {
                                addInternalOrArray(type, refs);
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG);
        return refs;
    }

    private static void addHandle(Handle h, Set<String> refs) {
        if (h != null) {
            addInternalOrArray(h.getOwner(), refs);
            addDesc(h.getDesc(), refs);
        }
    }

    private static void addBsmArg(Object arg, Set<String> refs) {
        if (arg instanceof Type t) {
            addDesc(t.getDescriptor(), refs);
        } else if (arg instanceof Handle h) {
            addHandle(h, refs);
        } else if (arg instanceof ConstantDynamic cd) {
            addDesc(cd.getDescriptor(), refs);
            addHandle(cd.getBootstrapMethod(), refs);
            for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
                addBsmArg(cd.getBootstrapMethodArgument(i), refs);
            }
        }
    }

    private static void addInternalOrArray(String name, Set<String> refs) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (name.charAt(0) == '[') {
            addDesc(name, refs);
        } else {
            refs.add(name);
        }
    }

    private static void addDesc(String desc, Set<String> refs) {
        if (desc == null) {
            return;
        }
        Matcher m = DESCRIPTOR_CLASS.matcher(desc);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }
}
