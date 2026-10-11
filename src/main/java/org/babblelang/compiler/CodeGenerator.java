package org.babblelang.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.CallSite;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

/**
 * Generates the class of one script (phase 1 of the bytecode compiler design).
 * <p>
 * The generated class keeps the interpreter's data model : every name lives in a
 * {@code Namespace}, held in a JVM local of the method being generated. The one
 * invariant of the generator is that <b>every expression leaves exactly one Object on
 * the operand stack</b>.
 * <ul>
 * <li>{@code static Object run(ScriptRuntime rt, Namespace ns)} runs the file. It is split
 * into {@code run$0}, {@code run$1}, ... when the file is large.</li>
 * <li>Each function literal becomes a {@code private static} method, adapted to
 * {@code FunctionBody} with LambdaMetafactory.</li>
 * <li>The static descriptions of call sites, operator sites and parameter tables are
 * static fields, built once in {@code <clinit>}.</li>
 * </ul>
 * Package and object bodies are compiled inline, so a {@code return} inside one is a
 * plain {@code ARETURN} from the enclosing function.
 */
final class CodeGenerator extends BabbleBaseVisitor<Void> {
    private static final String OBJECT = "java/lang/Object";
    private static final String STRING = "java/lang/String";
    private static final String NAMESPACE = "org/babblelang/engine/impl/Namespace";
    private static final String BABBLE_OBJECT = "org/babblelang/engine/impl/BabbleObject";
    private static final String RUNTIME = "org/babblelang/runtime/";
    private static final String SCRIPT_RUNTIME = RUNTIME + "ScriptRuntime";
    private static final String CALL_SITE = RUNTIME + "CallSite";
    private static final String OP_SITE = RUNTIME + "OpSite";
    private static final String CALLABLE = RUNTIME + "Callable";
    private static final String COMPILED_FUNCTION = RUNTIME + "CompiledFunction";
    private static final String FUNCTION_BODY = RUNTIME + "FunctionBody";
    private static final String ARGUMENTS = RUNTIME + "Arguments";
    private static final String BINDER = RUNTIME + "Arguments$Binder";
    private static final String SCOPE = "org/babblelang/engine/impl/Scope";

    static final String RUN_DESCRIPTOR = "(L" + SCRIPT_RUNTIME + ";L" + NAMESPACE + ";)Ljava/lang/Object;";
    private static final String BODY_DESCRIPTOR = "(L" + COMPILED_FUNCTION + ";Ljava/lang/Object;L" + SCRIPT_RUNTIME
            + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;";
    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    // Function body locals : the FunctionBody parameters, then the call's namespace.
    private static final int SELF = 0;
    private static final int THIZ = 1;
    private static final int FN_RT = 2;
    private static final int ARGS = 3;
    private static final int NAMES = 4;
    private static final int FN_NS = 6;

    // The top-level statements of a "run" method, counted in parse tree nodes outside of
    // function literals, are kept below this, which keeps the method well under 64 KB.
    private static final int MAX_NODES_PER_RUN_METHOD = 2000;
    // Constant pool strings are limited to 65535 bytes of modified UTF-8, at most three
    // per character ; longer strings are rebuilt from pieces.
    private static final int MAX_STRING_CONSTANT = 20000;
    // Estimated bytecode size of the constants built by one <clinit> helper.
    private static final int MAX_CONSTANT_BYTES_PER_HELPER = 30000;

    private final String className;
    private final String sourceName;
    private final ScopeAnalysis scopes;
    private final ClassWriter classWriter;

    private final List<Constant> constants = new ArrayList<>();
    private final Map<List<String>, String> stringArrays = new HashMap<>();
    private final Deque<PendingFunction> pendingFunctions = new ArrayDeque<>();
    private int functionCount = 0;

    // State of the method being generated.
    private MethodVisitor mv;
    private int rtLocal;
    private int nsLocal;
    private int nextLocal;
    private int currentLine;
    private List<Runnable> deferred;

    private record Constant(String field, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
    }

    private record PendingFunction(BabbleParser.FunctionLiteralContext literal, String method, String paramsField) {
    }

    CodeGenerator(String className, String sourceName, ScopeAnalysis scopes, ClassLoader runtimeLoader) {
        this.className = className;
        this.sourceName = sourceName;
        this.scopes = scopes;
        this.classWriter = new ScriptClassWriter(className, runtimeLoader);
    }

    byte[] generate(BabbleParser.FileContext file) {
        classWriter.visit(V21, ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC, className, null, OBJECT, null);
        classWriter.visitSource(sourceName, null);

        generateRun(file);
        while (!pendingFunctions.isEmpty()) {
            generateFunction(pendingFunctions.poll());
        }
        generateConstants();

        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    // ---------------------------------------------------------------- methods

    private void beginMethod(int access, String name, String descriptor, int firstFreeLocal, int rt, int ns) {
        mv = classWriter.visitMethod(access, name, descriptor, null, null);
        mv.visitCode();
        rtLocal = rt;
        nsLocal = ns;
        nextLocal = firstFreeLocal;
        currentLine = -1;
        deferred = new ArrayList<>();
    }

    private void endMethod() {
        // Code placed out of line, after the end of the method body.
        for (int i = 0; i < deferred.size(); i++) {
            deferred.get(i).run();
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    private int newLocal() {
        return nextLocal++;
    }

    private void generateRun(BabbleParser.FileContext file) {
        List<List<BabbleParser.ExpressionContext>> chunks = splitStatements(file.expression());

        beginMethod(ACC_STATIC, "run", RUN_DESCRIPTOR, 2, 0, 1);
        // The top-level definitions are declared before anything runs. That fails if a
        // binding has the same name, which is reported at the start of the file.
        line(file);
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(scopes.declared(file));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "declare", "([Ljava/lang/String;)V", false);

        if (chunks.size() == 1) {
            statements(chunks.get(0));
            mv.visitInsn(ARETURN);
            endMethod();
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, className, "run$" + i, RUN_DESCRIPTOR, false);
            mv.visitInsn(i == chunks.size() - 1 ? ARETURN : POP);
        }
        endMethod();

        for (int i = 0; i < chunks.size(); i++) {
            beginMethod(ACC_PRIVATE | ACC_STATIC, "run$" + i, RUN_DESCRIPTOR, 2, 0, 1);
            statements(chunks.get(i));
            mv.visitInsn(ARETURN);
            endMethod();
        }
    }

    /**
     * Groups the top-level statements so that each group compiles to a method of a
     * bounded size. Function literals are separate methods, so their bodies don't count.
     */
    private static List<List<BabbleParser.ExpressionContext>> splitStatements(List<BabbleParser.ExpressionContext> statements) {
        List<List<BabbleParser.ExpressionContext>> chunks = new ArrayList<>();
        List<BabbleParser.ExpressionContext> chunk = new ArrayList<>();
        int size = 0;
        for (BabbleParser.ExpressionContext statement : statements) {
            int statementSize = countNodes(statement);
            if (!chunk.isEmpty() && size + statementSize > MAX_NODES_PER_RUN_METHOD) {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                size = 0;
            }
            chunk.add(statement);
            size += statementSize;
        }
        chunks.add(chunk);
        return chunks;
    }

    private static int countNodes(ParseTree tree) {
        if (tree instanceof BabbleParser.FunctionLiteralContext) {
            return 1;
        }
        int count = 1;
        for (int i = 0; i < tree.getChildCount(); i++) {
            count += countNodes(tree.getChild(i));
        }
        return count;
    }

    /**
     * {@code fn$N(CompiledFunction self, Object thiz, ScriptRuntime rt, Object[] args,
     * String[] names, CallSite site)}. The prologue creates the call's namespace and binds
     * the parameters. It has no line numbers, so an error while binding is reported at
     * the line of the call, as the interpreter does. Default values are compiled out of
     * line, after the body, so that their line numbers don't cover the prologue either.
     */
    private void generateFunction(PendingFunction function) {
        BabbleParser.FunctionLiteralContext literal = function.literal();
        beginMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, function.method(), BODY_DESCRIPTOR, FN_NS + 1, FN_RT, FN_NS);

        mv.visitVarInsn(ALOAD, SELF);
        mv.visitVarInsn(ALOAD, THIZ);
        getStringArray(scopes.declared(literal));
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPILED_FUNCTION, "prologue", "(Ljava/lang/Object;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
        mv.visitVarInsn(ASTORE, FN_NS);

        String[] parameters = scopes.parameters(literal);
        int binder = newLocal();
        int value = newLocal();
        mv.visitFieldInsn(GETSTATIC, className, function.paramsField(), "[Ljava/lang/String;");
        mv.visitVarInsn(ALOAD, ARGS);
        mv.visitVarInsn(ALOAD, NAMES);
        mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS, "binder", "([Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;)L" + BINDER + ";", false);
        mv.visitVarInsn(ASTORE, binder);

        List<BabbleParser.ParameterDeclarationContext> declarations = literal.parametersDeclaration().parameterDeclaration();
        for (int i = 0; i < parameters.length; i++) {
            BabbleParser.ExpressionContext defaultValue = declarations.get(i).defaultValue;
            Label bind = new Label();

            mv.visitVarInsn(ALOAD, binder);
            pushInt(mv, i);
            mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "take", "(I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, value);
            mv.visitVarInsn(ALOAD, value);
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            mv.visitJumpInsn(IF_ACMPNE, bind);
            if (defaultValue != null) {
                Label computeDefault = new Label();
                mv.visitJumpInsn(GOTO, computeDefault);
                deferred.add(() -> {
                    mv.visitLabel(computeDefault);
                    visit(defaultValue);
                    mv.visitVarInsn(ASTORE, value);
                    mv.visitJumpInsn(GOTO, bind);
                });
            } else {
                mv.visitVarInsn(ALOAD, binder);
                pushInt(mv, i);
                mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "missing", "(I)Lorg/babblelang/engine/BabbleException;", false);
                mv.visitInsn(ATHROW);
            }
            mv.visitLabel(bind);
            mv.visitVarInsn(ALOAD, FN_NS);
            mv.visitLdcInsn(parameters[i]);
            mv.visitVarInsn(ALOAD, value);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "defineParameter", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
        }
        mv.visitVarInsn(ALOAD, binder);
        mv.visitMethodInsn(INVOKEVIRTUAL, BINDER, "rejectLeftovers", "()V", false);

        statements(literal.functionBlock.expression());
        mv.visitInsn(ARETURN);
        endMethod();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Records the Babble line of the code about to be emitted, before anything that can
     * fail : the reported line of an error is that of the innermost script frame.
     */
    private void line(ParserRuleContext ctx) {
        int line = ctx.getStart().getLine();
        if (line != currentLine) {
            Label label = new Label();
            mv.visitLabel(label);
            mv.visitLineNumber(line, label);
            currentLine = line;
        }
    }

    /**
     * A sequence of statements, each followed by a POP except the last one. An empty
     * sequence yields null.
     */
    private void statements(List<BabbleParser.ExpressionContext> statements) {
        if (statements.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            return;
        }
        for (int i = 0; i < statements.size(); i++) {
            if (i > 0) {
                mv.visitInsn(POP);
            }
            line(statements.get(i));
            visit(statements.get(i));
        }
    }

    /** Compiles {@code body} with {@code namespace} as the current namespace. */
    private void withNamespace(int namespace, Runnable body) {
        int outer = nsLocal;
        int savedNextLocal = nextLocal;
        nsLocal = namespace;
        try {
            body.run();
        } finally {
            nsLocal = outer;
            // The locals of an inner scope are dead past its end, so their slots are reused.
            nextLocal = savedNextLocal;
        }
    }

    private static void pushString(MethodVisitor mv, String value) {
        if (value.length() <= MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(value);
            return;
        }
        mv.visitLdcInsn(value.substring(0, MAX_STRING_CONSTANT));
        for (int start = MAX_STRING_CONSTANT; start < value.length(); start += MAX_STRING_CONSTANT) {
            mv.visitLdcInsn(value.substring(start, Math.min(value.length(), start + MAX_STRING_CONSTANT)));
            mv.visitMethodInsn(INVOKEVIRTUAL, STRING, "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        }
    }

    private static void pushStringArray(MethodVisitor mv, String[] values) {
        pushInt(mv, values.length);
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                mv.visitInsn(DUP);
                pushInt(mv, i);
                pushString(mv, values[i]);
                mv.visitInsn(AASTORE);
            }
        }
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static int estimateSize(String[] values) {
        int size = 8;
        for (String value : values) {
            size += 8 + (value == null ? 0 : 6 * (value.length() / MAX_STRING_CONSTANT));
        }
        return size;
    }

    // ---------------------------------------------------------------- constants

    private String addConstant(String prefix, String descriptor, int estimatedSize, Consumer<MethodVisitor> builder) {
        String field = prefix + constants.size();
        constants.add(new Constant(field, descriptor, estimatedSize, builder));
        return field;
    }

    /** Pushes a shared, immutable String[] constant. */
    private void getStringArray(String[] values) {
        mv.visitFieldInsn(GETSTATIC, className, stringArrayField(values), "[Ljava/lang/String;");
    }

    private String stringArrayField(String[] values) {
        return stringArrays.computeIfAbsent(Arrays.asList(values), key ->
                addConstant("NAMES", "[Ljava/lang/String;", estimateSize(values), m -> pushStringArray(m, values)));
    }

    private String opSiteField(BabbleParser.BinaryOpContext ctx) {
        String left = ctx.left.getText();
        String right = ctx.right.getText();
        return addConstant("OP", "L" + OP_SITE + ";", 16, m -> {
            m.visitTypeInsn(NEW, OP_SITE);
            m.visitInsn(DUP);
            pushString(m, left);
            pushString(m, right);
            m.visitMethodInsn(INVOKESPECIAL, OP_SITE, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        });
    }

    private String callSiteField(BabbleParser.CallContext ctx) {
        CallSite site = Interpreter.callSite(ctx, sourceName);
        return addConstant("CS", "L" + CALL_SITE + ";", 24 + estimateSize(site.argTexts()), m -> {
            m.visitTypeInsn(NEW, CALL_SITE);
            m.visitInsn(DUP);
            pushString(m, site.sourceName());
            pushInt(m, site.line());
            pushString(m, site.calleeText());
            pushStringArray(m, site.argTexts());
            m.visitMethodInsn(INVOKESPECIAL, CALL_SITE, "<init>", "(Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;)V", false);
        });
    }

    /**
     * Declares a static field per constant, set in {@code <clinit>} by helper methods of
     * a bounded size, so that any number of constants fits. The JVM only lets
     * {@code <clinit>} itself set a final field, so the fields are not final ; nothing but
     * the helpers ever writes them.
     */
    private void generateConstants() {
        MethodVisitor clinit = classWriter.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        MethodVisitor helper = null;
        int size = 0;
        int helpers = 0;
        for (Constant constant : constants) {
            classWriter.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, constant.field(), constant.descriptor(), null, null).visitEnd();
            if (helper != null && size + constant.estimatedSize() > MAX_CONSTANT_BYTES_PER_HELPER) {
                endHelper(helper);
                helper = null;
            }
            if (helper == null) {
                String name = "constants$" + (helpers++);
                clinit.visitMethodInsn(INVOKESTATIC, className, name, "()V", false);
                helper = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "()V", null, null);
                helper.visitCode();
                size = 0;
            }
            constant.builder().accept(helper);
            helper.visitFieldInsn(PUTSTATIC, className, constant.field(), constant.descriptor());
            size += constant.estimatedSize();
        }
        if (helper != null) {
            endHelper(helper);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
    }

    private static void endHelper(MethodVisitor helper) {
        helper.visitInsn(RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
    }

    // ---------------------------------------------------------------- expressions

    @Override
    public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        line(ctx);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn(ctx.name.getText());
        getStringArray(scopes.declared(ctx));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "enterPackage", "(Ljava/lang/String;[Ljava/lang/String;)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(ctx.packageBlock.expression()));
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        return null;
    }

    @Override
    public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(scopes.declared(ctx));
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECT, "create", "(L" + NAMESPACE + ";[Ljava/lang/String;)L" + BABBLE_OBJECT + ";", false);
        int object = newLocal();
        mv.visitVarInsn(ASTORE, object);
        withNamespace(object, () -> statements(ctx.createBlock.expression()));
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, object);
        return null;
    }

    @Override
    public Void visitIfExpression(BabbleParser.IfExpressionContext ctx) {
        Label otherwise = new Label();
        Label end = new Label();
        visit(ctx.test);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, otherwise);
        visit(ctx.thenBlock);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(otherwise);
        if (ctx.elseBlock != null) {
            visit(ctx.elseBlock);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitLabel(end);
        return null;
    }

    @Override
    public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn(ctx.name.getText());
        if (ctx.value != null) {
            visit(ctx.value);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "initialize", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
        return null;
    }

    /**
     * The result of a loop is the value of its last iteration, or null. It stays on the
     * stack across iterations : the test leaves nothing, and each iteration replaces it.
     */
    @Override
    public Void visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        Label head = new Label();
        Label end = new Label();
        mv.visitInsn(ACONST_NULL);
        mv.visitLabel(head);
        visit(ctx.test);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitInsn(POP);
        visit(ctx.whileBlock);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        return null;
    }

    @Override
    public Void visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        visit(ctx.expression());
        mv.visitInsn(ARETURN);
        // Dead code, so that the code around a return in the middle of an expression can
        // be generated as if it were any other expression. ASM replaces it.
        mv.visitInsn(ACONST_NULL);
        return null;
    }

    @Override
    public Void visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
        return visit(ctx.block());
    }

    /**
     * A block that defines names switches to a child namespace with them declared. A
     * fresh one is created each time the block runs, on every iteration of a loop.
     */
    @Override
    public Void visitBlock(BabbleParser.BlockContext ctx) {
        if (!scopes.opensScope(ctx)) {
            statements(ctx.expression());
            return null;
        }
        mv.visitVarInsn(ALOAD, nsLocal);
        getStringArray(scopes.declared(ctx));
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
        int namespace = newLocal();
        mv.visitVarInsn(ASTORE, namespace);
        withNamespace(namespace, () -> statements(ctx.expression()));
        return null;
    }

    @Override
    public Void visitSelector(BabbleParser.SelectorContext ctx) {
        String name = ctx.ID().getText();
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression == null) {
            line(ctx);
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false);
            return null;
        }
        visit(expression);
        line(ctx);
        mv.visitLdcInsn(name);
        pushString(mv, expression.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        return null;
    }

    @Override
    public Void visitCall(BabbleParser.CallContext ctx) {
        String site = callSiteField(ctx);
        String[] names = Interpreter.callNames(ctx);
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();

        visit(ctx.expression());
        // Checked before the arguments are evaluated.
        line(ctx);
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);

        mv.visitVarInsn(ALOAD, rtLocal);
        if (parameters.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
        } else {
            pushInt(mv, parameters.size());
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int i = 0; i < parameters.size(); i++) {
                mv.visitInsn(DUP);
                pushInt(mv, i);
                visit(parameters.get(i).expression());
                mv.visitInsn(AASTORE);
            }
        }
        if (names != null) {
            getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(GETSTATIC, className, site, "L" + CALL_SITE + ";");
        line(ctx);
        mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        return null;
    }

    @Override
    public Void visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        Label isFalse = new Label();
        Label end = new Label();
        visit(ctx.expression());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, isFalse);
        mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isFalse);
        mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        mv.visitLabel(end);
        return null;
    }

    @Override
    public Void visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        String method = switch (ctx.op.getType()) {
            case BabbleLexer.MUL -> "mul";
            case BabbleLexer.DIV -> "div";
            case BabbleLexer.PLUS -> "add";
            case BabbleLexer.MINUS -> "sub";
            case BabbleLexer.LT -> "lt";
            case BabbleLexer.LTE -> "le";
            case BabbleLexer.EQ -> "eq";
            case BabbleLexer.NEQ -> "ne";
            case BabbleLexer.GTE -> "ge";
            case BabbleLexer.GT -> "gt";
            default -> throw new IllegalStateException("Bad op : " + ctx.op.getText());
        };
        visit(ctx.left);
        visit(ctx.right);
        mv.visitFieldInsn(GETSTATIC, className, opSiteField(ctx), "L" + OP_SITE + ";");
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
        return null;
    }

    /**
     * {@code and} and {@code or} short-circuit, and always yield a Boolean.
     */
    @Override
    public Void visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        boolean or = ctx.op.getType() == BabbleLexer.OR;
        Label shortCircuit = new Label();
        Label end = new Label();
        // For "or", a true operand decides the result ; for "and", a false one.
        int decides = or ? IFNE : IFEQ;
        visit(ctx.left);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(decides, shortCircuit);
        visit(ctx.right);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(decides, shortCircuit);
        mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", or ? "FALSE" : "TRUE", "Ljava/lang/Boolean;");
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(shortCircuit);
        mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", or ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
        mv.visitLabel(end);
        return null;
    }

    @Override
    public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        String name = ScopeAnalysis.functionName(ctx);
        String method = "fn$" + (functionCount++) + (name != null ? "$" + name : "");
        String paramsField = stringArrayField(scopes.parameters(ctx));
        pendingFunctions.add(new PendingFunction(ctx, method, paramsField));

        Type bodyType = Type.getMethodType(BODY_DESCRIPTOR);
        mv.visitInvokeDynamicInsn("invoke", "()L" + FUNCTION_BODY + ";", METAFACTORY,
                bodyType, new Handle(H_INVOKESTATIC, className, method, BODY_DESCRIPTOR, false), bodyType);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitFieldInsn(GETSTATIC, className, paramsField, "[Ljava/lang/String;");
        if (name != null) {
            mv.visitLdcInsn(name);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitMethodInsn(INVOKESTATIC, COMPILED_FUNCTION, "create",
                "(L" + FUNCTION_BODY + ";L" + NAMESPACE + ";[Ljava/lang/String;Ljava/lang/String;)L" + COMPILED_FUNCTION + ";", false);
        return null;
    }

    @Override
    public Void visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        String name = ctx.name.getText();
        if (ctx.namespace == null) {
            mv.visitVarInsn(ALOAD, nsLocal);
            mv.visitLdcInsn(name);
            visit(ctx.value);
            line(ctx);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "assign", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
            return null;
        }
        // The target is checked before the value is evaluated.
        visit(ctx.namespace);
        line(ctx);
        pushString(mv, ctx.namespace.getText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assignable", "(Ljava/lang/Object;Ljava/lang/String;)L" + SCOPE + ";", false);
        visit(ctx.value);
        mv.visitLdcInsn(name);
        line(ctx);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "assign", "(L" + SCOPE + ";Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        return null;
    }

    @Override
    public Void visitNull(BabbleParser.NullContext ctx) {
        mv.visitInsn(ACONST_NULL);
        return null;
    }

    @Override
    public Void visitBoolean(BabbleParser.BooleanContext ctx) {
        mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", Boolean.parseBoolean(ctx.getText()) ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
        return null;
    }

    @Override
    public Void visitRecurse(BabbleParser.RecurseContext ctx) {
        line(ctx);
        mv.visitVarInsn(ALOAD, nsLocal);
        mv.visitLdcInsn("$recurse");
        mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "lookup", "(Ljava/lang/String;)Ljava/lang/Object;", false);
        return null;
    }

    @Override
    public Void visitInteger(BabbleParser.IntegerContext ctx) {
        String text = ctx.getText();
        try {
            pushInt(mv, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            // Out of range : fail at runtime, with the same exception as the interpreter.
            line(ctx);
            mv.visitLdcInsn(text);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        }
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        return null;
    }

    @Override
    public Void visitDouble(BabbleParser.DoubleContext ctx) {
        mv.visitLdcInsn(Double.parseDouble(ctx.getText()));
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        return null;
    }

    @Override
    public Void visitString(BabbleParser.StringContext ctx) {
        pushString(mv, Interpreter.unescape(ctx.STRING().getText()));
        return null;
    }

    /**
     * {@code COMPUTE_FRAMES} merges types with {@link #getCommonSuperClass}, which by
     * default loads classes through ASM's own class loader. This answers from the runtime
     * classes instead, and never tries to load the class being generated.
     */
    private static final class ScriptClassWriter extends ClassWriter {
        private final String className;
        private final ClassLoader runtimeLoader;

        ScriptClassWriter(String className, ClassLoader runtimeLoader) {
            super(COMPUTE_FRAMES);
            this.className = className;
            this.runtimeLoader = runtimeLoader;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return runtimeLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(className) || type2.equals(className)) {
                return OBJECT;
            }
            try {
                Class<?> class1 = Class.forName(type1.replace('/', '.'), false, runtimeLoader);
                Class<?> class2 = Class.forName(type2.replace('/', '.'), false, runtimeLoader);
                if (class1.isAssignableFrom(class2)) {
                    return type1;
                }
                if (class2.isAssignableFrom(class1)) {
                    return type2;
                }
                if (class1.isInterface() || class2.isInterface()) {
                    return OBJECT;
                }
                do {
                    class1 = class1.getSuperclass();
                } while (!class1.isAssignableFrom(class2));
                return Type.getInternalName(class1);
            } catch (ClassNotFoundException e) {
                return OBJECT;
            }
        }
    }
}
