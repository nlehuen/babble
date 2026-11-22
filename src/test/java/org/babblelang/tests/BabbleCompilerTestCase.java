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
        // Surefire runs the suite with -Dbabble.mode=interpret, then with -Dbabble.mode=compile.
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        boolean compiling = !"interpret".equals(System.getProperty(BabbleScriptEngine.MODE));
        Assertions.assertEquals(compiling, engine.compile("1") instanceof CompiledBabbleScript);
    }

    @Test
    public void testScriptsAreCompiledByDefault() throws Exception {
        String previous = System.getProperty(BabbleScriptEngine.MODE);
        System.clearProperty(BabbleScriptEngine.MODE);
        try {
            BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            Assertions.assertEquals(BabbleScriptEngine.Mode.COMPILE, engine.getMode());
            Assertions.assertInstanceOf(CompiledBabbleScript.class, engine.compile("1"));
            engine.put(BabbleScriptEngine.MODE, "interpret");
            Assertions.assertFalse(engine.compile("1") instanceof CompiledBabbleScript);
        } finally {
            if (previous != null) {
                System.setProperty(BabbleScriptEngine.MODE, previous);
            }
        }
    }

    @Test
    public void testMethodsAreNamedAfterTheirDefinitions() throws Exception {
        CompiledBabbleScript script = compile("def add = fn (a : int, b : int) -> ( a + b ) ; def twice = fn (shared f : fn () : int) -> ( fn () -> ( f() + f() ) ) ; add(1, 2)");
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
        CompiledBabbleScript script = compile("def Point = record (x : int, y : int) ( def norm = fn () -> ( this.x + this.y ) ) static ( def o = this(0, 0) )\n"
                + "def S = interface ( def d = fn () -> ( 1 ) ) ; def o = object implements S () ; Point(1, 2).norm() + o.d()");
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
        Assertions.assertEquals(List.of("$babble$class", "$babble$rt", "x", "y", "norm"), fieldNames(point.getValue()));
        Assertions.assertTrue(methodNames(point.getValue()).containsAll(List.of("<init>", "$babble$field", "$babble$setField", "m$norm")), methodNames(point.getValue()).toString());
        Assertions.assertEquals(4, script.getClasses().size());
    }

    @Test
    public void testConstructorVariablesCapturedByMethodsAreHiddenFields() throws Exception {
        CompiledBabbleScript script = compile("def C = class (start : int) ( def next = fn io () -> ( start = start + 1 ) ) ; def c = C(10) ; c.next() ; c.next()");
        Assertions.assertEquals(12, script.eval(new SimpleBindings()));
        byte[] c = script.getClasses().entrySet().stream().filter(e -> e.getKey().endsWith("$C")).findFirst().orElseThrow().getValue();
        Assertions.assertEquals(List.of("$babble$class", "$babble$rt", "next", "$v$start"), fieldNames(c));
        assertVerifies(script);
    }

    @Test
    public void testStackTracesShowBabbleLinesAndFunctions() throws Exception {
        List<StackTraceElement> captured = new ArrayList<>();
        Runnable capture = () -> captured.addAll(Arrays.asList(new Throwable().getStackTrace()));
        ScriptEngine engine = compilingEngine();
        Bindings bindings = engine.createBindings();
        bindings.put("capture", capture);
        bindings.put(ScriptEngine.FILENAME, "trace.ba");
        engine.eval("def inner = fn io () -> (\n  (capture as java.lang.Runnable).run()\n)\ndef outer = fn io () -> (\n  discard(1)\n  inner()\n)\nouter()", bindings);

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
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: No such name : nosuchname in named.ba at line number 2", e.getMessage());
    }

    @Test
    public void testACompiledScriptCanBeEvaluatedManyTimes() throws Exception {
        CompiledBabbleScript script = compile("def counter = fn () -> ( def c = 0 ; fn () -> ( c = c + 1 ) ) ; def c = counter() ; c() ; c() + (start as int)");
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
                "def g = fn (a : int, b : int) -> ( a + b ) ; def f = fn () : int -> ( g(1, return 2) ) ; f()",
                "def f = fn (n : int) : int -> ( def r = 0 ; while n > 0 then ( if n == 3 then ( return n ) ; r = r + n ; n = n - 1 ) ; r ) ; f(5)",
                "def i = 0 ; while i < 3 then ( i = i + 1 ) ; null",
                "def a = 1 ; a == 0 and ( a = 2 ; true ) ; a == 1 or ( a = 3 ; true ) ; not (a == 1)",
                "def f = fn (a : int, b = a + 1, c : fn () : int = fn () -> ( b )) -> ( a + c() ) ; f(1) + f(1, c: fn () -> ( 10 ))",
                "def C = class (def v : int) ( def m = fn () -> ( this.v ) ) ; C(4).m()",
                "( def a = 1 ; ( def b = a + 1 ; ( def c = b + 1 ; c ) ) )",
        };
        Object[] expected = {2, 3, null, false, 14, 4, 3};
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
        CompiledBabbleScript script = compile("def add = fn (a : int, b = 1) -> ( a + b ) ; def C = class () ( def m = fn (x : int) -> ( x ) ) ; add(1) + add(b: 2, a: 3) + C().m(4)");
        Assertions.assertEquals(11, script.eval(new SimpleBindings()));
        assertVerifies(script);
        // The direct entry point takes the arguments spread ; the $call adapter takes an array.
        Assertions.assertTrue(methodNames(script).containsAll(List.of("fn$0$add", "fn$0$add$call")), methodNames(script).toString());
        Map.Entry<String, byte[]> c = script.getClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith("$C")).findFirst().orElseThrow();
        Assertions.assertTrue(methodNames(c.getValue()).containsAll(List.of("m$m", "m$m$call")), methodNames(c.getValue()).toString());

        CompiledFunction add = (CompiledFunction) compile("def add = fn (a : int, b = 1) -> ( a + b ) ; add").eval(new SimpleBindings());
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
        String definitions = "def f = fn (" + parameters + ") -> ( " + sum + " )\n"
                + "def C = class () ( def m = fn (" + parameters + ") -> ( " + sum + " ) ; def n = fn () -> ( this.m(" + arguments + ") ) )\n";
        CompiledBabbleScript script = compile(definitions + "f() + f(" + arguments + ") + f(p3: 100) + C().m() + C().n()");
        assertVerifies(script);
        Assertions.assertTrue(methodNames(script).contains("fn$0$f") && !methodNames(script).contains("fn$0$f$call"), methodNames(script).toString());
        int total = count * (count - 1) / 2;
        Assertions.assertEquals(count + total + (count + 99) + count + total, script.eval(new SimpleBindings()));

        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run("def g = fn (a : int) -> ( a ) ; g(" + arguments + ")"));
        Assertions.assertTrue(e.getMessage().contains("Too many parameters : 1 declared, " + count + " given"), e.getMessage());
    }

    @Test
    public void testACallSiteSeesManyFunctions() throws Exception {
        // More function literals than a site caches, each called many times from the same site.
        StringBuilder source = new StringBuilder("def Box = class (def f : fn (x : int) : int) ()\ndef fs : java.util.List of Box = java.util.ArrayList()\n");
        for (int i = 0; i < 10; i++) {
            source.append("fs.add(Box(fn (x : int, y : int = ").append(i).append(") -> ( x * y )))\n");
        }
        source.append("def total = 0 ; def i = 0 ; def k = 0\n"
                + "while i < 1000 then ( total = total + (fs.get(k) as Box).f(i) ; i = i + 1 ; k = if k == 9 then ( 0 ) else ( k + 1 ) ) ; total");
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
        StringBuilder source = new StringBuilder("def f = fn (a : any) : any -> ( a )\ndef n = 0\n");
        for (int i = 0; i < 3000; i++) {
            source.append("n = n + (f(").append(i).append(") as int)\n");
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

    // ---------------------------------------------------------------- stable names (M5a)

    @Test
    public void moduleClassesAreNamedAfterTheirKeys() {
        Assertions.assertEquals("babble/lib/vec3$ba", org.babblelang.compiler.BabbleCompiler.className("lib/vec3.ba"));
        Assertions.assertEquals("babble/tmp/a_b/v1$2/x$ba", org.babblelang.compiler.BabbleCompiler.className("file:/tmp/a b/v1.2/x.ba"));
        Assertions.assertEquals("babble/lib/x-y$ba", org.babblelang.compiler.BabbleCompiler.className("classpath:lib/x-y.ba"));
    }

    private static ScriptEngine moduleEngine(org.babblelang.engine.MapModuleSource source) {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleScriptEngine.MODULE_SOURCE, source);
        engine.put(ScriptEngine.FILENAME, "main.ba");
        return engine;
    }

    private static final String VEC3 = "export def Vec3 = record (x : double, y : double) (\n"
            + "    def plus = fn (o : Vec3) : Vec3 -> ( Vec3(this.x + o.x, this.y + o.y) )\n"
            + ")\n"
            + "export def zero = Vec3(0.0, 0.0)\n";

    @Test
    public void aModulesClassesCarryItsName() throws Exception {
        org.babblelang.engine.MapModuleSource source = new org.babblelang.engine.MapModuleSource().put("lib/vec3.ba", VEC3);
        Object vec = moduleEngine(source).eval("import(\"lib/vec3.ba\").zero");
        String name = vec.getClass().getName();
        Assertions.assertTrue(name.startsWith("babble.lib.vec3$ba$") && name.endsWith("$Vec3"), name);
    }

    @Test
    public void aModuleCompilesToTheSameClassesEveryTime() throws Exception {
        java.nio.file.Path first = java.nio.file.Files.createTempDirectory("babble-dump");
        java.nio.file.Path second = java.nio.file.Files.createTempDirectory("babble-dump");
        String previous = System.getProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES);
        try {
            for (java.nio.file.Path dump : List.of(first, second)) {
                System.setProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES, dump.toString());
                // A new engine and source each time : nothing is shared but the module's text.
                moduleEngine(new org.babblelang.engine.MapModuleSource().put("lib/vec3.ba", VEC3)).eval("import(\"lib/vec3.ba\").zero");
            }
        } finally {
            if (previous == null) {
                System.clearProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES);
            } else {
                System.setProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES, previous);
            }
        }
        java.nio.file.Path module = java.nio.file.Path.of("babble", "lib");
        List<String> classes;
        try (var files = java.nio.file.Files.list(first.resolve(module))) {
            classes = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        Assertions.assertTrue(classes.contains("vec3$ba.class"), classes.toString());
        Assertions.assertTrue(classes.size() >= 2, classes.toString());
        for (String file : classes) {
            Assertions.assertArrayEquals(java.nio.file.Files.readAllBytes(first.resolve(module).resolve(file)),
                    java.nio.file.Files.readAllBytes(second.resolve(module).resolve(file)), file);
        }
    }

    private static List<String> typesCalls(CompiledBabbleScript script) {
        List<String> calls = new ArrayList<>();
        for (byte[] classFile : script.getClasses().values()) {
            new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                            if ("org/babblelang/runtime/Types".equals(owner)) {
                                calls.add(name + "->" + methodName);
                            }
                        }
                    };
                }
            }, 0);
        }
        return calls;
    }

    @Test
    public void testP5OmitsRedundantRuntimeTypeChecksAndKeepsAsCasts() throws Exception {
        CompiledBabbleScript checked = compile(
                "def Box = class (def x : double = 1) (\n"
                        + "  def putVal = fn mut (v : double = 2) : double -> (\n"
                        + "    this.x = v\n"
                        + "    this.x += 1\n"
                        + "    if v > 0.0 then ( return 1 )\n"
                        + "    2\n"
                        + "  )\n"
                        + ")\n"
                        + "def d : double = 1\n"
                        + "d = 2\n"
                        + "d += 3\n"
                        + "def f = fn (a : int, b : double = 4) : double -> ( a + b )\n"
                        + "def b = Box()\n"
                        + "d + f(1) + b.putVal(3) + b.putVal() + b.x");
        assertVerifies(checked);
        Assertions.assertEquals(List.of(), typesCalls(checked));
        for (byte[] classFile : checked.getClasses().values()) {
            Assertions.assertTrue(fieldNames(classFile).stream().noneMatch(n -> n.startsWith("TYPE")),
                    fieldNames(classFile).toString());
        }
        Assertions.assertEquals(15.0, checked.eval(new SimpleBindings()));

        CompiledBabbleScript cast = compile("def id = fn (x : any) : any -> ( x ) ; id(7) as int");
        assertVerifies(cast);
        Assertions.assertTrue(typesCalls(cast).stream().anyMatch(c -> c.endsWith("->check")), typesCalls(cast).toString());
        Assertions.assertTrue(fieldNames(cast.getClassBytes()).stream().anyMatch(n -> n.startsWith("TYPE")),
                fieldNames(cast.getClassBytes()).toString());
        Assertions.assertEquals(7, cast.eval(new SimpleBindings()));
    }

    @Test
    public void testP6aUnionsAndNullablesCompileCleanly() throws Exception {
        CompiledBabbleScript verified = compile(
                "def f = fn (x : int | string?, flag : bool) : double? -> (\n"
                        + "  if x is int then ( x ) else if flag then ( 2.5 ) else ( null )\n"
                        + ")\n"
                        + "def cb : (fn (n : int) : int)? = null\n"
                        + "def r1 = f(3, false)\n"
                        + "def r2 = f(\"a\", true)\n"
                        + "def r3 = f(null, false)\n"
                        + "if cb != null then ( cb(1) )\n"
                        + "\"\" + classof(r1) + \":\" + r1 + \":\" + r2 + \":\" + r3");
        assertVerifies(verified);
        Assertions.assertEquals(List.of(), typesCalls(verified));
        Assertions.assertEquals("java.lang.Double:3.0:2.5:null", verified.eval(new SimpleBindings()));

        CompiledBabbleScript checked = compile(
                "def id = fn (v : any?) : any? -> ( v )\n"
                        + "def n = id(5) as int?\n"
                        + "def u = id(\"hi\") as (int | string)\n"
                        + "\"\" + n + \":\" + u");
        assertVerifies(checked);
        Assertions.assertTrue(typesCalls(checked).stream().anyMatch(c -> c.endsWith("->check")), typesCalls(checked).toString());
        Assertions.assertEquals("5:hi", checked.eval(new SimpleBindings()));
    }

    @Test
    public void testP6bGenericsCompileCleanly() throws Exception {
        CompiledBabbleScript babbleGenerics = compile(
                "def Box of T = class (def v : T) (\n"
                        + "  def mapBox of U = fn (f : fn (x : T) : U) : Box of U -> ( Box(f(this.v)) )\n"
                        + ")\n"
                        + "def id of T = fn (x : T) : T -> ( x )\n"
                        + "def db : Box of double = Box(3)\n"
                        + "def sb : Box of string = db.mapBox(fn (d) -> ( \"\" + id(d) ))\n"
                        + "\"\" + classof(db.v) + \":\" + sb.v");
        assertVerifies(babbleGenerics);
        Assertions.assertEquals(List.of(), typesCalls(babbleGenerics));
        Assertions.assertEquals("java.lang.Double:3.0", babbleGenerics.eval(new SimpleBindings()));

        CompiledBabbleScript javaBoundary = compile(
                "def l : java.util.List of int = java.util.List.of(42)\n"
                        + "l.get(0)");
        assertVerifies(javaBoundary);
        Assertions.assertTrue(typesCalls(javaBoundary).stream().anyMatch(c -> c.endsWith("->check")), typesCalls(javaBoundary).toString());
        Assertions.assertEquals(42, javaBoundary.eval(new SimpleBindings()));
    }

    @Test
    public void testSafeNavigationCompilesCleanly() throws Exception {
        CompiledBabbleScript safe = compile(
                "def B = class (def v : int) ()\n"
                        + "def A = class (def next : A? = null, def x : int = 7) (\n"
                        + "  def f = fn () : B -> ( B(this.x) )\n"
                        + "  def getInt = fn () : int -> ( this.x )\n"
                        + ")\n"
                        + "def mk = fn (flag : bool) : A? -> ( if flag then ( A(A(null, 5), 3) ) else ( null ) )\n"
                        + "def a1 = mk(true)\n"
                        + "def a0 = mk(false)\n"
                        + "def b1 : B? = a1?.f()\n"
                        + "def b0 : B? = a0?.f()\n"
                        + "def chained : int? = a1?.next?.f()?.v\n"
                        + "def d1 : double? = a1?.getInt()\n"
                        + "def d0 : double? = a0?.getInt()\n"
                        + "\"\" + b1?.v + \":\" + b0?.v + \":\" + chained + \":\" + classof(d1) + \":\" + d1 + \":\" + d0");
        assertVerifies(safe);
        Assertions.assertEquals(List.of(), typesCalls(safe));
        Assertions.assertEquals("3:null:5:java.lang.Double:3.0:null", safe.eval(new SimpleBindings()));
    }

    // ---------------------------------------------------------------- RM001 : typed lowering & static dispatch

    private static List<String> fieldEntries(byte[] classFile) {
        List<String> fields = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                fields.add(name + ":" + descriptor);
                return null;
            }
        }, 0);
        return fields;
    }

    private static List<String> methodDescriptors(byte[] classFile) {
        List<String> methods = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                methods.add(name + ":" + descriptor);
                return null;
            }
        }, 0);
        return methods;
    }

    private static List<String> methodInstructions(byte[] classFile, String methodPrefix) {
        List<String> ops = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.startsWith(methodPrefix)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.IADD) ops.add("IADD");
                        else if (opcode == Opcodes.IMUL) ops.add("IMUL");
                        else if (opcode == Opcodes.ARRAYLENGTH) ops.add("ARRAYLENGTH");
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC
                                || opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                            ops.add("FIELD:" + owner + "." + fieldName + ":" + fieldDescriptor);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        ops.add("CALL:" + owner + "." + methodName);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc, org.objectweb.asm.Handle bsm, Object... args) {
                        ops.add("INDY:" + bsm.getOwner() + "." + bsm.getName() + ":" + indyName);
                    }
                };
            }
        }, 0);
        return ops;
    }

    @Test
    public void testRm001UnboxedPrimitivesSpecEntryPointsAndFields() throws Exception {
        CompiledBabbleScript script = compile(
                "def Point = record (x : int, y : int) (\n"
                        + "  def norm = fn () : int -> ( this.x * this.x + this.y * this.y )\n"
                        + ")\n"
                        + "def Early = class () (\n"
                        + "  def readBefore = fn () : int -> ( this.late )\n"
                        + "  def late : int = 7\n"
                        + ")\n"
                        + "def Lazy = class (early : bool) (\n"
                        + "  def a : int = if early then ( this.b ) else ( 0 )\n"
                        + "  def b : int = 5\n"
                        + ")\n"
                        + "def fib = fn (n : int) : int -> ( if n < 2 then ( n ) else ( recurse(n - 1) + recurse(n - 2) ) )\n"
                        + "def loop = fn (n : int) : int -> ( def i = 0 ; def s = 0 ; while i < n then ( s += i ; i += 1 ) ; s )\n"
                        + "def counter = fn () -> ( def c = 0 ; fn io () : int -> ( c = c + 1 ) )\n"
                        + "def widenJoin = fn (c : bool) : double -> ( if c then ( 1 ) else ( 2.5 ) )\n"
                        + "def foldCheck = fn (n : int) : int -> (\n"
                        + "  def touched = 0\n"
                        + "  def inc = fn () : int -> ( touched = touched + 1 ; 99 )\n"
                        + "  def z = inc() * 0\n"
                        + "  def folded = 1 + 2 * 3\n"
                        + "  touched * 100 + z + folded + n\n"
                        + ")\n"
                        + "def pureFold = fn () : int -> ( 1 + 2 * 3 )\n"
                        + "def c = counter()\n"
                        + "\"\" + Point(3, 4).norm() + \":\" + Early().readBefore() + \":\" + Lazy(false).b + \":\" + fib(6) + \":\" + loop(5) + \":\" + (c() + c()) + \":\" + widenJoin(true) + \":\" + foldCheck(4) + \":\" + pureFold() + \":\" + classof(1) + \":\" + classof(1.5) + \":\" + classof(true)");
        assertVerifies(script);
        Assertions.assertEquals(
                "25:7:5:8:10:3:1.0:111:7:java.lang.Integer:java.lang.Double:java.lang.Boolean",
                script.eval(new SimpleBindings()));

        byte[] mainBytes = script.getClassBytes();
        List<String> mainMethods = methodDescriptors(mainBytes);
        Assertions.assertTrue(mainMethods.stream().anyMatch(m -> m.endsWith("$fib$spec:(Lorg/babblelang/runtime/CompiledFunction;Ljava/lang/Object;Lorg/babblelang/runtime/ScriptRuntime;I)I")),
                mainMethods.toString());
        Assertions.assertTrue(mainMethods.stream().anyMatch(m -> m.endsWith("$spec:(Lorg/babblelang/runtime/CompiledFunction;Ljava/lang/Object;Lorg/babblelang/runtime/ScriptRuntime;)I")),
                mainMethods.toString());

        for (String fn : List.of("fn$3$fib$spec", "fn$4$loop$spec")) {
            List<String> ops = methodInstructions(mainBytes, fn);
            Assertions.assertTrue(ops.stream().noneMatch(o -> o.contains("org/babblelang/runtime/Operators")
                    || o.contains("org/babblelang/runtime/Truth")
                    || o.contains("org/babblelang/runtime/JavaLinker")
                    || o.startsWith("INDY:org/babblelang/runtime/Bootstrap.")), fn + ": " + ops);
        }
        List<String> foldOps = methodInstructions(mainBytes, "fn$8$pureFold$spec");
        if (org.babblelang.compiler.BabbleCompiler.resolveOptLevel(null) >= 1) {
            Assertions.assertFalse(foldOps.contains("IADD") || foldOps.contains("IMUL"), foldOps.toString());
        }

        byte[] pointBytes = script.getClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith("$Point")).findFirst().orElseThrow().getValue();
        Assertions.assertEquals(List.of("$babble$class:Lorg/babblelang/runtime/BabbleClass;", "$babble$rt:Lorg/babblelang/runtime/ScriptRuntime;", "x:I", "y:I", "norm:Ljava/lang/Object;"), fieldEntries(pointBytes));
        List<String> normOps = methodInstructions(pointBytes, "m$norm$spec");
        Assertions.assertTrue(normOps.contains("IMUL") && normOps.contains("IADD"), normOps.toString());
        Assertions.assertTrue(normOps.stream().noneMatch(o -> o.contains("org/babblelang/runtime/Operators")
                || o.startsWith("INDY:")), normOps.toString());

        byte[] lazyBytes = script.getClasses().entrySet().stream()
                .filter(e -> e.getKey().endsWith("$Lazy")).findFirst().orElseThrow().getValue();
        Assertions.assertTrue(fieldEntries(lazyBytes).contains("$defined$0:I"), fieldEntries(lazyBytes).toString());
        Assertions.assertTrue(methodDescriptors(lazyBytes).contains("$babble$isDefined:(I)Z"), methodDescriptors(lazyBytes).toString());

        CompiledBabbleScript earlyFail = compile(
                "def Bad = class (early : bool) ( def a : int = if early then ( this.b ) else ( 0 ) ; def b : int = 5 ) ; Bad(true)");
        assertVerifies(earlyFail);
        ScriptException err = Assertions.assertThrows(ScriptException.class, () -> earlyFail.eval(new SimpleBindings()));
        Assertions.assertTrue(err.getMessage().contains("Member used before definition : b"), err.getMessage());
    }

    @Test
    public void testRm001StaticJavaDispatchAndIndexedModuleExports() throws Exception {
        CompiledBabbleScript typedJava = compile(
                "def work = fn io (shared sb : java.lang.StringBuilder, items : java.util.List of int, xs) : double -> (\n"
                        + "  sb.append(\"a\")\n"
                        + "  sb.length() + items.size() + xs.size() + java.lang.Math.sqrt(items.size() + 8)\n"
                        + ")\n"
                        + "def l : java.util.List of int = java.util.ArrayList() ; l.add(10)\n"
                        + "def arr = java.util.regex.Pattern.compile(\",\").split(\"x,y\")\n"
                        + "work(java.lang.StringBuilder(\"ok\"), l, arr)");
        assertVerifies(typedJava);
        Assertions.assertEquals(9.0, typedJava.eval(new SimpleBindings()));
        List<String> workOps = methodInstructions(typedJava.getClassBytes(), "fn$0$work$spec");
        Assertions.assertTrue(workOps.contains("CALL:java/lang/StringBuilder.append"), workOps.toString());
        Assertions.assertTrue(workOps.contains("CALL:java/lang/StringBuilder.length"), workOps.toString());
        Assertions.assertTrue(workOps.contains("CALL:java/util/List.size"), workOps.toString());
        Assertions.assertTrue(workOps.contains("ARRAYLENGTH"), workOps.toString());
        Assertions.assertTrue(workOps.contains("CALL:java/lang/Math.sqrt"), workOps.toString());
        Assertions.assertTrue(workOps.stream().noneMatch(o -> o.contains("org/babblelang/runtime/JavaLinker")
                || o.startsWith("INDY:org/babblelang/runtime/Bootstrap.")), workOps.toString());

        Exception nullErr = Assertions.assertThrows(Exception.class,
                () -> compile("java.lang.Class.forName(\"java.lang.String\").getEnumConstants().size()"));
        Assertions.assertTrue(nullErr.getMessage().contains("Not a scope"), nullErr.getMessage());

        CompiledBabbleScript retInJavaArg = compile(
                "def g = fn () : int -> ( java.lang.Math.max(1, return 42) ) ; g()");
        assertVerifies(retInJavaArg);
        Assertions.assertEquals(42, retInJavaArg.eval(new SimpleBindings()));

        String modSource = "export def scale : int = 3\n"
                + "export def Vec = record (x : double, y : double) (\n"
                + "  def dot = fn (o : Vec) : double -> ( this.x * o.x + this.y * o.y )\n"
                + ")\n"
                + "export def addInts = fn (a : int, b : int) : int -> ( a + b )\n";
        String mainSource = "def m = import(\"lib/vec.ba\")\n"
                + "def p = m.Vec(1.0, 2.0)\n"
                + "m.scale + m.addInts(4, 5) + p.dot(m.Vec(3.0, 4.0))\n";

        java.nio.file.Path first = java.nio.file.Files.createTempDirectory("babble-rm001-a");
        java.nio.file.Path second = java.nio.file.Files.createTempDirectory("babble-rm001-b");
        String previous = System.getProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES);
        try {
            Object res = null;
            for (java.nio.file.Path dump : List.of(first, second)) {
                System.setProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES, dump.toString());
                org.babblelang.engine.MapModuleSource src = new org.babblelang.engine.MapModuleSource().put("lib/vec.ba", modSource);
                res = moduleEngine(src).eval(mainSource);
            }
            Assertions.assertEquals(23.0, res);
            byte[] dumpedMain = java.nio.file.Files.readAllBytes(first.resolve("babble/lib/vec$ba.class"));
            List<String> dotOps = methodInstructions(
                    java.nio.file.Files.list(first.resolve("babble/lib"))
                            .filter(p -> p.getFileName().toString().contains("$Vec.class"))
                            .findFirst().map(p -> {
                                try {
                                    return java.nio.file.Files.readAllBytes(p);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            }).orElseThrow(),
                    "m$dot$spec");
            Assertions.assertTrue(dotOps.stream().noneMatch(o -> o.contains("org/babblelang/runtime/Operators")), dotOps.toString());
            Assertions.assertArrayEquals(dumpedMain, java.nio.file.Files.readAllBytes(second.resolve("babble/lib/vec$ba.class")));
        } finally {
            if (previous == null) {
                System.clearProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES);
            } else {
                System.setProperty(org.babblelang.compiler.BabbleCompiler.DUMP_CLASSES, previous);
            }
        }
    }

    @Test
    public void testBip021P1OptionalSpecMaskAndStaticNamedBinding() throws Exception {
        CompiledBabbleScript script = compile(
                "def add = fn (a : int, b : int = 10, ..., c : int = 100) : int -> ( a + b + c )\n"
                        + "def indirect : fn (x : int, y : int = ..., ..., z : int = ...) : int = add\n"
                        + "def directCalls = fn () : int -> ( add(1) + add(1, 2) + add(c: 200, a: 3) )\n"
                        + "def indirectCalls = fn () : int -> ( indirect(1) + indirect(z: 200, x: 3) )\n"
                        + "directCalls() + indirectCalls()");
        assertVerifies(script);
        Assertions.assertEquals(111 + 103 + 213 + 111 + 213, script.eval(new SimpleBindings()));

        // Direct calls with optional & reordered named args invoke fn$0$add$spec directly without indy.
        List<String> directOps = methodInstructions(script.getClassBytes(), "fn$1$directCalls$spec");
        Assertions.assertTrue(directOps.stream().anyMatch(o -> o.endsWith(".fn$0$add$spec")), directOps.toString());
        Assertions.assertTrue(directOps.stream().noneMatch(o -> o.startsWith("INDY:")), directOps.toString());

        // Indirect calls through function value with omitted optional args use typedCallOpt indy.
        List<String> indirectOps = methodInstructions(script.getClassBytes(), "fn$2$indirectCalls$spec");
        Assertions.assertTrue(indirectOps.stream().anyMatch(o -> o.contains("Bootstrap.typedCallOpt")), indirectOps.toString());
    }

    @Test
    public void testBip021P2JavaVarargsDirectArrayEmission() throws Exception {
        CompiledBabbleScript script = compile(
                "def run = fn io (a : int, b : int) : string -> (\n"
                        + "  def s = java.util.stream.IntStream.of(a, b, 3).sum()\n"
                        + "  java.lang.String.format(\"%d:%s\", s, \"ok\")\n"
                        + ")\n"
                        + "run(10, 20)");
        assertVerifies(script);
        Assertions.assertEquals("33:ok", script.eval(new SimpleBindings()));

        List<String> ops = methodInstructions(script.getClassBytes(), "fn$0$run$spec");
        Assertions.assertTrue(ops.contains("CALL:java/util/stream/IntStream.of"), ops.toString());
        Assertions.assertTrue(ops.contains("CALL:java/lang/String.format"), ops.toString());
        Assertions.assertTrue(ops.stream().noneMatch(o -> o.startsWith("INDY:") || o.contains("Overloads")), ops.toString());
    }

    @Test
    public void testBip021P3BoundedTypeVarInterfaceLowering() throws Exception {
        CompiledBabbleScript script = compile(
                "def biggest = fn of (T : java.lang.Comparable of T) (a : T, b : T) : T -> (\n"
                        + "  if a.compareTo(b) > 0 then ( a ) else ( b )\n"
                        + ")\n"
                        + "biggest(3, 7)");
        assertVerifies(script);
        Assertions.assertEquals(7, script.eval(new SimpleBindings()));

        List<String> ops = methodInstructions(script.getClassBytes(), "fn$0$biggest");
        Assertions.assertTrue(ops.contains("CALL:java/lang/Comparable.compareTo"), ops.toString());
        Assertions.assertTrue(ops.stream().noneMatch(o -> o.startsWith("INDY:") || o.contains("JavaLinker")), ops.toString());
    }
}
