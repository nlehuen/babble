package org.babblelang.tests;

import java.lang.reflect.Method;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.format.Formatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code BIP019-I1} (mechanism and the form of axioms) and {@code BIP020-I5} (array
 * parameters in axiom signatures).
 */
public class Bip019P1AxiomsMechanismTestCase extends BabbleTestBase {

    @Test
    public void testAxiomExpressionEvaluatesToNullAtRuntime() throws Exception {
        String script =
                """
                def r: any? = (
                    axiom java.lang.Math.sqrt: fn nothrow foldable (a: double): double
                )
                r == null
                """;
        Assertions.assertEquals(true, interpret(script));
    }

    @Test
    public void testClassAndMemberAxiomsEffectsAndSpecificity() throws Exception {
        String axiomsSrc =
                """
                axiom java.lang.Comparable of T: class (
                    def compareTo: fn pure (other: T): int
                )
                axiom java.lang.Math: class (
                ) static (
                    def abs: fn nothrow idempotent foldable (a: double): double
                    def sqrt: fn nothrow foldable (a: double): double
                )
                axiom java.lang.String: class exact notnull immutable (
                    def *: fn pure
                    def trim: fn nothrow idempotent foldable (): string!
                ) static (
                    def valueOf: fn (data: (array of char)!): string!
                )
                axiom java.util.Collection of E: class notnull (
                    def add: fn mut shared shallow (shared e: E): bool
                    def iterator: fn nothrow view (): (java.util.Iterator of E)!
                    def size: fn nothrow (): int
                    def toArray: fn of T (a: (array of T)!): (array of T)!
                )
                """;
        BuiltinAxioms custom = BuiltinAxioms.fromSource(axiomsSrc, "test/curated.ba");
        Method sqrt = Math.class.getMethod("sqrt", double.class);
        Method absDouble = Math.class.getMethod("abs", double.class);
        Method absInt = Math.class.getMethod("abs", int.class);
        Method trim = String.class.getMethod("trim");
        Method valueOfChars = String.class.getMethod("valueOf", char[].class);
        Method valueOfInt = String.class.getMethod("valueOf", int.class);

        Assertions.assertTrue(custom.isFoldable(sqrt));
        Assertions.assertTrue(custom.isFoldable(absDouble));
        Assertions.assertFalse(custom.isFoldable(absInt));
        Assertions.assertTrue(custom.isIdempotent(absDouble));
        Assertions.assertFalse(custom.isIdempotent(absInt));
        Assertions.assertTrue(custom.isFoldable(trim));
        Assertions.assertTrue(custom.isIdempotent(trim));
        Assertions.assertTrue(custom.nullMarked(String.class));
        Assertions.assertTrue(custom.isDeeplyImmutable(String.class));
        Assertions.assertTrue(
                custom.nullMarked(java.util.ArrayList.class)); // inherited from Collection
        Assertions.assertFalse(custom.isDeeplyImmutable(java.util.ArrayList.class));
        // Array parameter signature matches String.valueOf(char[]) and types as non-null string in
        // a pure fn.
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setBuiltinAxioms(custom);
        String arrCall =
                """
                def f = fn (): string -> (
                    def cs = (array of char).of('h', 'i')
                    java.lang.String.valueOf(cs)
                )
                f()
                """;
        Assertions.assertEquals("hi", engine.eval(arrCall));
    }

    @Test
    public void testExactClassAxiomDoesNotApplyToSubclasses() throws Exception {
        String axiomsSrc =
                """
                axiom java.util.AbstractList of E: class exact notnull immutable (
                    def size: fn nothrow (): int
                )
                """;
        BuiltinAxioms custom = BuiltinAxioms.fromSource(axiomsSrc, "exact.ba");
        Assertions.assertTrue(custom.nullMarked(java.util.AbstractList.class));
        Assertions.assertTrue(custom.isDeeplyImmutable(java.util.AbstractList.class));
        Assertions.assertFalse(custom.nullMarked(java.util.ArrayList.class));
        Assertions.assertFalse(custom.isDeeplyImmutable(java.util.ArrayList.class));
    }

    @Test
    public void testBabbleOverrideContractCheckedAgainstInheritedAxiom() throws Exception {
        String evil =
                """
                axiom java.lang.Comparable.compareTo: fn pure
                def Evil = class (def v: int) implements java.lang.Comparable of Evil (
                    def compareTo = fn (mut other: Evil): int -> (
                        other.v += 100
                        this.v - other.v
                    )
                )
                Evil(1).v
                """;
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException ex =
                Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(evil));
        Assertions.assertTrue(
                ex.getMessage()
                        .contains(
                                "Function compareTo violates pure guarantee of axiom java.lang.Comparable.compareTo"),
                ex.getMessage());

        // A pure override satisfies the contract cleanly.
        String good =
                """
                axiom java.lang.Comparable.compareTo: fn pure
                def Good = class (def v: int) implements java.lang.Comparable of Good (
                    def compareTo = fn (other: Good): int -> (this.v - other.v)
                )
                Good(10).compareTo(Good(3))
                """;
        Assertions.assertEquals(7, interpret(good));
    }

    @Test
    public void testLocalAxiomTakesPrecedenceOverBuiltinAxioms() throws Exception {
        String axiomsSrc =
                """
                axiom java.lang.Comparable.compareTo: fn pure
                """;
        BuiltinAxioms custom = BuiltinAxioms.fromSource(axiomsSrc, "comp.ba");
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setBuiltinAxioms(custom);

        // Local `fn io` axiom overrides the built-in `fn pure` contract on Comparable.compareTo.
        String script =
                """
                axiom java.lang.Comparable.compareTo: fn io
                def Noisy = class (def v: int) implements java.lang.Comparable of Noisy (
                    def compareTo = fn io (o: Noisy): int -> (
                        this.v - o.v
                    )
                )
                Noisy(5).compareTo(Noisy(2))
                """;
        Assertions.assertEquals(3, engine.eval(script));
    }

    @Test
    public void testArrayAndBoxedOverloadDisambiguationInAxioms() throws Exception {
        String script =
                """
                axiom java.util.Arrays: class (
                ) static (
                    def copyOf: fn nothrow fresh (original: (array of int)!, newLength: int): (array of int)!
                    def fill: fn (mut a: (array of int)!, val: int): void
                )
                axiom java.lang.Integer: class (
                ) static (
                    def valueOf: fn nothrow (i: int): java.lang.Integer!
                )
                def f = fn (): int -> (
                    def a = (array of int).of(1, 2, 3)
                    def b = java.util.Arrays.copyOf(a, 3)
                    java.util.Arrays.fill(b, 7)
                    b.get(1)
                )
                f()
                """;
        Assertions.assertEquals(7, interpret(script));
    }

    @Test
    public void testEmptyAndDuplicateAxiomDiagnostics() {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException empty =
                Assertions.assertThrows(
                        TypeCheckException.class,
                        () -> engine.eval("axiom java.lang.Math.sqrt: fn"));
        Assertions.assertTrue(empty.getMessage().contains("Empty axiom"), empty.getMessage());

        TypeCheckException dup =
                Assertions.assertThrows(
                        TypeCheckException.class,
                        () ->
                                engine.eval(
                                        """
                                        axiom java.lang.Math.sqrt: fn nothrow (a: double): double
                                        axiom java.lang.Math.sqrt: fn nothrow (a: double): double
                                        """));
        Assertions.assertTrue(
                dup.getMessage().contains("Duplicate axiom for member"), dup.getMessage());
    }

    @Test
    public void testFormatterRoundTripForAxioms() throws Exception {
        String source =
                """
                ;; Class axiom with instance and static blocks
                axiom java.util.Collection of E: class notnull (
                    def add: fn mut shared shallow (shared e: E): bool
                    def iterator: fn nothrow view (): (java.util.Iterator of E)!
                    def size: fn nothrow (): int
                    def toArray: fn of T (a: (array of T)!): (array of T)!
                ) static (
                    def new: fn shared
                )

                axiom java.lang.Object.hashCode: fn pure
                axiom java.lang.Math.sqrt: fn nothrow foldable (a: double): double
                """;
        String formatted = Formatter.format(source, "test.ba");
        Assertions.assertEquals(source, formatted);
        Assertions.assertEquals(formatted, Formatter.format(formatted, "test.ba"));
    }

    @Test
    public void testEmptyBuiltinAxiomsInstance() {
        BuiltinAxioms empty = BuiltinAxioms.EMPTY;
        Assertions.assertTrue(empty.isEmpty());
        Assertions.assertFalse(empty.nullMarked(String.class));
        Assertions.assertFalse(empty.isDeeplyImmutable(java.util.Map.Entry.class));
        Assertions.assertTrue(empty.builtins().isEmpty());
    }

    @Test
    public void testBabbleNameAxiomsAndBuiltinsPrelude() throws Exception {
        // 1. Standard builtins.ba provides int, assert, same, classof, etc.
        BuiltinAxioms std = BuiltinAxioms.standard();
        for (String name :
                java.util.List.of(
                        "assert", "suppose", "same", "classof", "byte", "short", "int", "long",
                        "float", "double", "bool", "char", "string")) {
            Assertions.assertTrue(std.builtins().containsKey(name), "Missing built-in : " + name);
        }

        // 2. With BuiltinAxioms.EMPTY, ordinary built-ins are unknown (not callable).
        BabbleScriptEngine emptyEngine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        emptyEngine.setBuiltinAxioms(BuiltinAxioms.EMPTY);
        emptyEngine.enableBytecodeEffectAnalysis(false);
        Assertions.assertThrows(TypeCheckException.class, () -> emptyEngine.eval("int(3.5)"));
        Assertions.assertThrows(TypeCheckException.class, () -> emptyEngine.eval("assert(true)"));
        Assertions.assertThrows(TypeCheckException.class, () -> emptyEngine.eval("same(1, 1)"));
        Assertions.assertThrows(TypeCheckException.class, () -> emptyEngine.eval("classof(1)"));

        // 3. A Babble-name axiom types a host binding, works inside blocks, and evaluates to null.
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        javax.script.Bindings bindings = engine.createBindings();
        bindings.put("hostAdd", engine.eval("fn (a: int, b: int): int -> (a + b)"));
        String script =
                """
                def run = fn (): int -> (
                    def r: any? = axiom hostAdd: fn nothrow (a: int, b: int): int
                    assert(r == null)
                    hostAdd(19, 23)
                )
                run()
                """;
        Assertions.assertEquals(42, engine.eval(script, bindings));

        // 4. Reading a Babble-name axiom whose binding is missing fails at runtime with No such
        // name.
        javax.script.ScriptException missing =
                Assertions.assertThrows(
                        javax.script.ScriptException.class,
                        () -> engine.eval("axiom ghostFn: fn (a: int): int\nghostFn(1)"));
        Assertions.assertTrue(
                missing.getMessage().contains("No such name : ghostFn"), missing.getMessage());

        // 5. Babble-name axioms cannot be exported and require a signature.
        TypeCheckException noSig =
                Assertions.assertThrows(
                        TypeCheckException.class, () -> engine.eval("axiom ghostFn: fn pure"));
        Assertions.assertTrue(
                noSig.getMessage().contains("requires a signature"), noSig.getMessage());
        TypeCheckException exported =
                Assertions.assertThrows(
                        TypeCheckException.class,
                        () -> engine.eval("export axiom ghostFn: fn (a: int): int"));
        Assertions.assertTrue(
                exported.getMessage().contains("cannot be exported"), exported.getMessage());
    }

    @Test
    public void testHostBindingSeedingAndValueAxiomsAcrossModes() throws Exception {
        for (String[] cfg :
                new String[][] {
                    {"interpret", null},
                    {"compile", "0"},
                    {"compile", "1"},
                    {"compile", "2"}
                }) {
            BabbleScriptEngine engine =
                    (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            engine.put(BabbleScriptEngine.MODE, cfg[0]);
            if (cfg[1] != null) {
                engine.put(
                        org.babblelang.compiler.BabbleCompiler.OPT_LEVEL, Integer.parseInt(cfg[1]));
            }

            // 1. eval() seeds ENGINE_SCOPE keys as read-only `any`, while undeclared names fail
            // statically.
            javax.script.Bindings evalBindings = engine.createBindings();
            evalBindings.put("name", "world");
            Assertions.assertEquals(
                    "hello world", engine.eval("\"hello \" + (name as string)", evalBindings));
            TypeCheckException assignHost =
                    Assertions.assertThrows(
                            TypeCheckException.class,
                            () -> engine.eval("name = \"other\"", evalBindings));
            Assertions.assertTrue(
                    assignHost.getMessage().contains("Cannot assign to host binding : name"),
                    assignHost.getMessage());
            TypeCheckException unknownInEval =
                    Assertions.assertThrows(
                            TypeCheckException.class, () -> engine.eval("typo", evalBindings));
            Assertions.assertTrue(
                    unknownInEval.getMessage().contains("No such name : typo"),
                    unknownInEval.getMessage());

            // 2. compile() does NOT seed ENGINE_SCOPE keys; external bindings must be declared
            // with `axiom`.
            engine.put("ambient", "ignoredByCompile");
            TypeCheckException unseededCompile =
                    Assertions.assertThrows(
                            TypeCheckException.class, () -> engine.compile("ambient"));
            Assertions.assertTrue(
                    unseededCompile.getMessage().contains("No such name : ambient"),
                    unseededCompile.getMessage());

            // 3. Value axioms give CompiledScript typed host bindings with runtime boundary checks.
            javax.script.CompiledScript compiled =
                    engine.compile(
                            "axiom start: int ; axiom tag: string? ; axiom scale: double ; axiom raw: any?\n"
                                    + "def t = tag ; def s = if t != null then ( t ) else ( \"none\" )\n"
                                    + "s + \":\" + (start + 1) + \":\" + (scale + 0.5) + \":\" + (raw == null)");
            javax.script.Bindings ok = new javax.script.SimpleBindings();
            ok.put("start", 41);
            ok.put("tag", "hi");
            ok.put("scale", 2); // int widens to double at boundary
            ok.put("raw", null);
            Assertions.assertEquals("hi:42:2.5:true", compiled.eval(ok));

            javax.script.Bindings withNullTag = new javax.script.SimpleBindings();
            withNullTag.put("start", 9);
            withNullTag.put("tag", null);
            withNullTag.put("scale", 1.5);
            withNullTag.put("raw", "present");
            Assertions.assertEquals("none:10:2.0:false", compiled.eval(withNullTag));

            // Wrong runtime type on value axiom fails with Type error (not ClassCastException).
            javax.script.Bindings badType = new javax.script.SimpleBindings();
            badType.put("start", "oops");
            badType.put("tag", "hi");
            badType.put("scale", 1.0);
            badType.put("raw", null);
            javax.script.ScriptException typeErr =
                    Assertions.assertThrows(
                            javax.script.ScriptException.class, () -> compiled.eval(badType));
            Assertions.assertTrue(
                    typeErr.getMessage().contains("Type error : expected int, got string"),
                    typeErr.getMessage());

            // Null for non-nullable primitive value axiom fails with Type error.
            javax.script.Bindings nullInt = new javax.script.SimpleBindings();
            nullInt.put("start", null);
            nullInt.put("tag", "hi");
            nullInt.put("scale", 1.0);
            nullInt.put("raw", null);
            javax.script.ScriptException nullErr =
                    Assertions.assertThrows(
                            javax.script.ScriptException.class, () -> compiled.eval(nullInt));
            Assertions.assertTrue(
                    nullErr.getMessage().contains("Type error : expected int, got null"),
                    nullErr.getMessage());

            // Missing binding fails at runtime with No such name.
            javax.script.ScriptException missingErr =
                    Assertions.assertThrows(
                            javax.script.ScriptException.class,
                            () -> compiled.eval(new javax.script.SimpleBindings()));
            Assertions.assertTrue(
                    missingErr.getMessage().contains("No such name : tag"),
                    missingErr.getMessage());

            // 4. Modules do not inherit caller ENGINE_SCOPE implicitly, but see host bindings
            // declared via `axiom`.
            org.babblelang.engine.MapModuleSource source =
                    new org.babblelang.engine.MapModuleSource();
            source.put("implicit_mod.ba", "export def read = fn volatile () -> ( hostVal )");
            source.put(
                    "axiom_mod.ba",
                    "axiom hostVal: int\nexport def read = fn (): int -> ( hostVal + 1 )");
            engine.put(BabbleScriptEngine.MODULE_SOURCE, source);

            javax.script.Bindings modBindings = engine.createBindings();
            modBindings.put("hostVal", 10);
            modBindings.put(javax.script.ScriptEngine.FILENAME, "main.ba");
            javax.script.ScriptException modErr =
                    Assertions.assertThrows(
                            javax.script.ScriptException.class,
                            () -> engine.eval("import(\"implicit_mod.ba\").read()", modBindings));
            Assertions.assertTrue(
                    modErr.getMessage().contains("No such name : hostVal"), modErr.getMessage());
            Assertions.assertEquals(
                    11, engine.eval("import(\"axiom_mod.ba\").read()", modBindings));
        }
    }
}
