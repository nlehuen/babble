package org.babblelang.tests;

import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(script.getClassBytes()), BabbleCompilerTestCase.class.getClassLoader(), false, new PrintWriter(problems));
        Assertions.assertEquals("", problems.toString());
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
                "def mk = () -> ( def m = () -> ( this.v ) ; object ( def v = 4 ) ) ; mk().m()",
                "def f = () -> ( object ( def v = ( return 2 ) ) ; 3 ) ; f()",
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
