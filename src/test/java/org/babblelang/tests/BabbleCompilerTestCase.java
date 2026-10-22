package org.babblelang.tests;

import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.CompiledFunction;
import org.babblelang.runtime.ScriptRuntime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests of compiled mode itself : whatever the babble.mode property, these compile.
 */
public class BabbleCompilerTestCase {
    private static ScriptEngine compilingEngine() {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        return engine;
    }

    private static CompiledBabbleScript compile(String script) throws ScriptException {
        CompiledScript compiled = ((BabbleScriptEngine) compilingEngine()).compile(script);
        Assertions.assertInstanceOf(CompiledBabbleScript.class, compiled);
        return (CompiledBabbleScript) compiled;
    }

    private static Object run(String script) throws ScriptException {
        return compile(script).eval(new SimpleBindings());
    }

    private static List<String> methodNames(CompiledBabbleScript script) {
        List<String> names = new ArrayList<>();
        new ClassReader(script.getClassBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }

    private static void assertVerifies(CompiledBabbleScript script) {
        for (byte[] classFile : script.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(classFile), script.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
    }

    @Test
    public void testTheModeSelectsTheBackend() throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "interpret");
        Assertions.assertFalse(engine.compile("1") instanceof CompiledBabbleScript);
        engine.put(BabbleScriptEngine.MODE, "compile");
        Assertions.assertInstanceOf(CompiledBabbleScript.class, engine.compile("1"));
        engine.put(BabbleScriptEngine.MODE, "nonsense");
        Assertions.assertThrows(ScriptException.class, () -> engine.compile("1"));
    }

    @Test
    public void testTheSystemPropertySelectsTheDefaultBackend() throws Exception {
        // Surefire runs the whole suite a second time with -Dbabble.mode=compile.
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        boolean compiling = "compile".equals(System.getProperty(BabbleScriptEngine.MODE));
        Assertions.assertEquals(compiling, engine.compile("1") instanceof CompiledBabbleScript);
    }

    @Test
    public void testMethodsAreNamedAfterTheirDefinitions() throws Exception {
        CompiledBabbleScript script = compile("def add = (a, b) -> ( a + b ) ; def twice = (f) -> ( () -> ( f() + f() ) ) ; add(1, 2)");
        List<String> names = methodNames(script);
        Assertions.assertTrue(names.containsAll(List.of("run", "fn$0$add", "fn$1$twice", "fn$2", "<clinit>")), names.toString());
        Assertions.assertEquals(3, script.eval(new SimpleBindings()));
        assertVerifies(script);
    }

    private static List<String> methodNames(byte[] classFile) {
        List<String> names = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }

    private static List<String> fieldNames(byte[] classFile) {
        List<String> names = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                names.add(name);
                return null;
            }
        }, 0);
        return names;
    }

    @Test
    public void testClassesCompileToJvmClasses() throws Exception {
        CompiledBabbleScript script = compile("def Point = value class (x, y) ( def norm = () -> ( this.x + this.y ) ) static ( def o = this(0, 0) )\n"
                + "def S = interface ( def d = () -> ( 1 ) ) ; def o = object implements S () ; Point(1, 2).norm() + o.d()");
        Assertions.assertEquals(4, script.eval(new SimpleBindings()));
        assertVerifies(script);

        // The script class holds the constructors, and function literals that aren't methods.
        List<String> names = methodNames(script);
        for (String pattern : List.of("cls\\$\\d+\\$Point", "fn\\$\\d+\\$d", "cls\\$\\d+\\$object")) {
            Assertions.assertTrue(names.stream().anyMatch(name -> name.matches(pattern)), pattern + " in " + names);
        }
        // Each class expression has its own class, with a field per member and its methods.
        Map.Entry<String, byte[]> point = script.getClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith("$Point")).findFirst().orElseThrow();
        Assertions.assertEquals(List.of("x", "y", "norm"), fieldNames(point.getValue()));
        Assertions.assertTrue(methodNames(point.getValue()).containsAll(List.of("<init>", "field", "setField", "m$norm")), methodNames(point.getValue()).toString());
        Assertions.assertEquals(3, script.getClasses().size());
    }

    @Test
    public void testConstructorVariablesCapturedByMethodsAreHiddenFields() throws Exception {
        CompiledBabbleScript script = compile("def C = class (start) ( def next = () -> ( start = start + 1 ) ) ; def c = C(10) ; c.next() ; c.next()");
        Assertions.assertEquals(12, script.eval(new SimpleBindings()));
        byte[] c = script.getClasses().entrySet().stream().filter(e -> e.getKey().endsWith("$C")).findFirst().orElseThrow().getValue();
        Assertions.assertEquals(List.of("next", "$v$start"), fieldNames(c));
        assertVerifies(script);
    }

    @Test
    public void testStackTracesShowBabbleLinesAndFunctions() throws Exception {
        List<StackTraceElement> captured = new ArrayList<>();
        Callable capture = (ScriptRuntime rt, Object[] args, String[] names, CallSite site) -> {
            captured.addAll(Arrays.asList(new Throwable().getStackTrace()));
            return null;
        };
        ScriptEngine engine = compilingEngine();
        Bindings bindings = engine.createBindings();
        bindings.put("capture", capture);
        bindings.put(ScriptEngine.FILENAME, "trace.ba");
        engine.eval("def inner = () -> (\n  capture()\n)\ndef outer = () -> (\n  1\n  inner()\n)\nouter()", bindings);

        StackTraceElement inner = captured.stream().filter(e -> e.getMethodName().equals("fn$0$inner")).findFirst().orElseThrow();
        Assertions.assertEquals(2, inner.getLineNumber());
        Assertions.assertEquals("trace.ba", inner.getFileName());
        StackTraceElement outer = captured.stream().filter(e -> e.getMethodName().equals("fn$1$outer")).findFirst().orElseThrow();
        Assertions.assertEquals(6, outer.getLineNumber());
        StackTraceElement run = captured.stream().filter(e -> e.getMethodName().equals("run")).findFirst().orElseThrow();
        Assertions.assertEquals(8, run.getLineNumber());
    }

    @Test
    public void testTheFileNameIsReportedInErrors() {
        ScriptEngine engine = compilingEngine();
        Bindings bindings = engine.createBindings();
        bindings.put(ScriptEngine.FILENAME, "named.ba");
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> engine.eval("1\nnosuchname", bindings));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: No such name : nosuchname in named.ba at line number 2", e.getMessage());
    }

    @Test
    public void testACompiledScriptCanBeEvaluatedManyTimes() throws Exception {
        CompiledBabbleScript script = compile("def counter = () -> ( def c = 0 ; () -> ( c = c + 1 ) ) ; def c = counter() ; c() ; c() + start");
        Bindings first = new SimpleBindings();
        first.put("start", 10);
        Bindings second = new SimpleBindings();
        second.put("start", 20);
        Assertions.assertEquals(12, script.eval(first));
        Assertions.assertEquals(22, script.eval(second));
    }

    @Test
    public void testTrickyShapesVerify() throws Exception {
        String[] scripts = {
                "def g = (a, b) -> ( a + b ) ; def f = () -> ( g(1, return 2) ) ; f()",
                "def f = (n) -> ( def r = 0 ; while n > 0 then ( if n == 3 then ( return n ) ; r = r + n ; n = n - 1 ) ) ; f(5)",
                "def i = 0 ; while i < 3 then ( i = i + 1 )",
                "def a = 1 ; a == 0 and ( a = 2 ) ; a == 1 or ( a = 3 ) ; not a",
                "def f = (a, b = a + 1, c = () -> ( b )) -> ( a + c() ) ; f(1) + f(1, c: () -> ( 10 ))",
                "package p ( def x = 1 ; package q ( def y = x + 1 ) ) ; p.q.y",
                "def C = class (def v) ( def m = () -> ( this.v ) ) ; C(4).m()",
                "def f = () -> ( package p ( def v = ( return 2 ) ) ; 3 ) ; f()",
                "( def a = 1 ; ( def b = a + 1 ; ( def c = b + 1 ; c ) ) )",
        };
        Object[] expected = {2, 3, 3, false, 14, 2, 4, 2, 3};
        for (int i = 0; i < scripts.length; i++) {
            CompiledBabbleScript script = compile(scripts[i]);
            assertVerifies(script);
            Assertions.assertEquals(expected[i], script.eval(new SimpleBindings()), scripts[i]);
        }
    }

    @Test
    public void testLargeScriptsAreSplitIntoSeveralMethods() throws Exception {
        StringBuilder source = new StringBuilder("def total = 0\n");
        for (int i = 0; i < 5000; i++) {
            source.append("def v").append(i).append(" = ").append(i).append(" ; total = total + v").append(i).append("\n");
        }
        source.append("total\n");
        CompiledBabbleScript compiled = compile(source.toString());
        Assertions.assertTrue(methodNames(compiled).contains("run$1"), methodNames(compiled).toString());
        assertVerifies(compiled);
        Assertions.assertEquals(5000 * 4999 / 2, compiled.eval(new SimpleBindings()));
    }

    @Test
    public void testFunctionsAndMethodsHaveDirectEntryPoints() throws Exception {
        CompiledBabbleScript script = compile("def add = (a, b = 1) -> ( a + b ) ; def C = class () ( def m = (x) -> ( x ) ) ; add(1) + add(b: 2, a: 3) + C().m(4)");
        Assertions.assertEquals(11, script.eval(new SimpleBindings()));
        assertVerifies(script);
        // The direct entry point takes the arguments spread ; the $call adapter takes an array.
        Assertions.assertTrue(methodNames(script).containsAll(List.of("fn$0$add", "fn$0$add$call")), methodNames(script).toString());
        Map.Entry<String, byte[]> c = script.getClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith("$C")).findFirst().orElseThrow();
        Assertions.assertTrue(methodNames(c.getValue()).containsAll(List.of("m$m", "m$m$call")), methodNames(c.getValue()).toString());

        CompiledFunction add = (CompiledFunction) compile("def add = (a, b = 1) -> ( a + b ) ; add").eval(new SimpleBindings());
        Assertions.assertEquals(MethodType.methodType(Object.class, CompiledFunction.class, Object.class, ScriptRuntime.class,
                Arguments.Binder.class, Object.class, Object.class), add.direct().type());
    }

    @Test
    public void testFunctionsWithManyParametersKeepTheArrayConvention() throws Exception {
        // More parameters than a direct entry point takes (CodeGenerator.MAX_SPREAD), and
        // as many arguments at the call sites.
        int count = 210;
        StringBuilder parameters = new StringBuilder();
        StringBuilder sum = new StringBuilder("0");
        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < count; i++) {
            parameters.append(i > 0 ? ", " : "").append("p").append(i).append(" = 1");
            sum.append(" + p").append(i);
            arguments.append(i > 0 ? ", " : "").append(i);
        }
        String definitions = "def f = (" + parameters + ") -> ( " + sum + " )\n"
                + "def C = class () ( def m = (" + parameters + ") -> ( " + sum + " ) ; def n = () -> ( this.m(" + arguments + ") ) )\n";
        CompiledBabbleScript script = compile(definitions + "f() + f(" + arguments + ") + f(p3: 100) + C().m() + C().n()");
        assertVerifies(script);
        Assertions.assertTrue(methodNames(script).contains("fn$0$f") && !methodNames(script).contains("fn$0$f$call"), methodNames(script).toString());
        int total = count * (count - 1) / 2;
        Assertions.assertEquals(count + total + (count + 99) + count + total, script.eval(new SimpleBindings()));

        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run("def g = (a) -> ( a ) ; g(" + arguments + ")"));
        Assertions.assertTrue(e.getMessage().contains("Too many parameters : 1 declared, " + count + " given"), e.getMessage());
    }

    @Test
    public void testACallSiteSeesManyFunctions() throws Exception {
        // More function literals than a site caches, each called many times from the same site.
        StringBuilder source = new StringBuilder("def fs = java.util.ArrayList()\n");
        for (int i = 0; i < 10; i++) {
            source.append("fs.add((x, y = ").append(i).append(") -> ( x * y ))\n");
        }
        source.append("def total = 0 ; def i = 0 ; def k = 0\n"
                + "while i < 1000 then ( total = total + fs.get(k)(i) ; i = i + 1 ; k = if k == 9 then ( 0 ) else ( k + 1 ) ) ; total");
        long expected = 0;
        for (int i = 0; i < 1000; i++) {
            expected += (long) i * (i % 10);
        }
        CompiledBabbleScript script = compile(source.toString());
        Assertions.assertEquals((int) expected, script.eval(new SimpleBindings()));
        Assertions.assertEquals((int) expected, script.eval(new SimpleBindings()));
    }

    @Test
    public void testManyCallSitesAndLongTexts() throws Exception {
        StringBuilder source = new StringBuilder("def f = (a) -> ( a )\ndef n = 0\n");
        for (int i = 0; i < 3000; i++) {
            source.append("n = n + f(").append(i).append(")\n");
        }
        // Longer than a constant pool string can hold, as a literal and as a call argument's text.
        String longString = "y".repeat(70000);
        source.append("f(\"").append(longString).append("\")\n");
        source.append("n\n");
        CompiledBabbleScript compiled = compile(source.toString());
        assertVerifies(compiled);
        Assertions.assertEquals(3000 * 2999 / 2, compiled.eval(new SimpleBindings()));
        Assertions.assertEquals(longString, run("\"" + longString + "\""));
    }
}
