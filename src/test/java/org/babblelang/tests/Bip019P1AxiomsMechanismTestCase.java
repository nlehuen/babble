package org.babblelang.tests;

import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.TypeCheckException;
import org.babblelang.format.Formatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Tests for {@code BIP019-P1} (mechanism and the form of axioms) and {@code BIP020-P5} (array
 * parameters in axiom signatures).
 */
public class Bip019P1AxiomsMechanismTestCase extends BabbleTestBase {

    @Test
    public void testAxiomExpressionEvaluatesToNullAtRuntime() throws Exception {
        String script = """
                def r: any? = (
                    axiom java.lang.Math.sqrt: fn nothrow foldable (a: double): double
                )
                r == null
                """;
        Assertions.assertEquals(true, interpret(script));
    }

    @Test
    public void testClassAndMemberAxiomsEffectsAndSpecificity() throws Exception {
        String axiomsSrc = """
                axiom java.lang.Comparable of T: class (
                    def compareTo: fn pure (other: T): int
                )
                axiom java.lang.Math: class (
                ) static (
                    def abs: fn nothrow idempotent foldable (a: double): double
                    def sqrt: fn nothrow foldable (a: double): double
                )
                axiom java.lang.String: class exact nullmarked immutable (
                    def *: fn pure
                    def trim: fn nothrow idempotent foldable (): string
                ) static (
                    def valueOf: fn (data: array of char): string
                )
                axiom java.util.Collection of E: class nullmarked (
                    def add: fn mut shared shallow (shared e: E): bool
                    def iterator: fn nothrow view (): java.util.Iterator of E
                    def size: fn nothrow (): int
                    def toArray: fn of T (a: array of T): array of T
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
        Assertions.assertTrue(custom.nullMarked(java.util.ArrayList.class)); // inherited from Collection
        Assertions.assertFalse(custom.isDeeplyImmutable(java.util.ArrayList.class));
        // Array parameter signature matches String.valueOf(char[]) and types as non-null string in a pure fn.
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setBuiltinAxioms(custom);
        String arrCall = """
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
        String axiomsSrc = """
                axiom java.util.AbstractList of E: class exact nullmarked immutable (
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
        String evil = """
                axiom java.lang.Comparable.compareTo: fn pure
                def Evil = class (def v: int) implements java.lang.Comparable of Evil (
                    def compareTo = fn (mut other: Evil): int -> (
                        other.v += 100
                        this.v - other.v
                    )
                )
                Evil(1).v
                """;
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException ex = Assertions.assertThrows(TypeCheckException.class, () -> engine.eval(evil));
        Assertions.assertTrue(ex.getMessage().contains("Function compareTo violates pure guarantee of axiom java.lang.Comparable.compareTo"),
                ex.getMessage());

        // A pure override satisfies the contract cleanly.
        String good = """
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
        String axiomsSrc = """
                axiom java.lang.Comparable.compareTo: fn pure
                """;
        BuiltinAxioms custom = BuiltinAxioms.fromSource(axiomsSrc, "comp.ba");
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setBuiltinAxioms(custom);

        // Local `fn io` axiom overrides the built-in `fn pure` contract on Comparable.compareTo.
        String script = """
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
        String script = """
                axiom java.util.Arrays: class (
                ) static (
                    def copyOf: fn nothrow fresh (original: array of int, newLength: int): array of int
                    def fill: fn (mut a: array of int, val: int): void
                )
                axiom java.lang.Integer: class (
                ) static (
                    def valueOf: fn nothrow (i: int): java.lang.Integer
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
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        TypeCheckException empty = Assertions.assertThrows(TypeCheckException.class,
                () -> engine.eval("axiom java.lang.Math.sqrt: fn"));
        Assertions.assertTrue(empty.getMessage().contains("Empty axiom"), empty.getMessage());

        TypeCheckException dup = Assertions.assertThrows(TypeCheckException.class,
                () -> engine.eval("""
                        axiom java.lang.Math.sqrt: fn nothrow (a: double): double
                        axiom java.lang.Math.sqrt: fn nothrow (a: double): double
                        """));
        Assertions.assertTrue(dup.getMessage().contains("Duplicate axiom for member"), dup.getMessage());
    }

    @Test
    public void testFormatterRoundTripForAxioms() throws Exception {
        String source = """
                ;; Class axiom with instance and static blocks
                axiom java.util.Collection of E: class nullmarked (
                    def add: fn mut shared shallow (shared e: E): bool
                    def iterator: fn nothrow view (): java.util.Iterator of E
                    def size: fn nothrow (): int
                    def toArray: fn of T (a: array of T): array of T
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
}
