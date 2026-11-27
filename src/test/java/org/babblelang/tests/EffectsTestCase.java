package org.babblelang.tests;

import java.io.StringWriter;
import java.util.List;
import java.util.function.IntUnaryOperator;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.effects.Associative;
import org.babblelang.effects.Commutative;
import org.babblelang.effects.Effect;
import org.babblelang.effects.IO;
import org.babblelang.effects.Idempotent;
import org.babblelang.effects.Injective;
import org.babblelang.effects.Monotone;
import org.babblelang.effects.Mut;
import org.babblelang.effects.Nothrow;
import org.babblelang.effects.Pure;
import org.babblelang.effects.Shared;
import org.babblelang.effects.Volatile;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for RM002 / BIP016 : static effect inference & verification, pure-by-default & volatile/io,
 * mut / shared & default @BORROWED escape tracking, static call-site exclusivity, BIP007-D1
 * flow-sensitive narrowing, frame-local closures, bounded call-site specialization, compositional
 * algebraic properties, and the 7-tier Java host effect & axiom contract layer.
 */
public class EffectsTestCase {

    // ---------------------------------------------------------------- Java host fixtures

    public sealed interface SealedExpr permits SealedNum, SealedAdd {}

    public record SealedNum(int value) implements SealedExpr {}

    public record SealedAdd(int left, int right) implements SealedExpr {}

    public static class HostFixtures {
        public static int mutableStatic = 1;
        public static final int FINAL_STATIC = 42;

        @Pure
        @Nothrow
        public static int pureCalc(int x) {
            return x * 2 + 1;
        }

        @Volatile
        @Nothrow
        public static int volatileRead(int x) {
            return x + mutableStatic;
        }

        @Pure
        @Nothrow
        @Idempotent
        public static double clampUnit(double x) {
            return Math.max(0.0, Math.min(1.0, x));
        }

        @Pure
        @Nothrow
        @Commutative
        @Associative
        public static int bitXor(int a, int b) {
            return a ^ b;
        }

        @Pure
        @Nothrow
        @Monotone
        public static double expStep(double x) {
            return Math.exp(x);
        }

        @org.babblelang.effects.NotNull
        @Pure
        @Nothrow
        @Injective
        public static String hexKey(int x) {
            return Integer.toHexString(x);
        }

        @IO
        public static int annotatedIO(int x) {
            return x;
        }

        @Nothrow
        public static void mutateParam(@Mut MutableHolder h, MutableHolder src) {
            h.value = src.value;
        }

        @org.babblelang.effects.NotNull
        @Pure
        @Nothrow
        public static MutableHolder echoShared(@Shared MutableHolder h) {
            return h;
        }

        @Pure
        @Nothrow
        public static int applyOp(int x, IntUnaryOperator op) {
            return op.applyAsInt(x);
        }
    }

    @Pure
    public static class AnnotatedPureClass {
        public static int helper(int x) {
            return x + 10;
        }

        @IO
        public static int escapeHatch(int x) {
            return x;
        }
    }

    public static class MutableHolder {
        public int value;

        @Pure
        @Nothrow
        public MutableHolder(int value) {
            this.value = value;
        }

        @Mut
        @Nothrow
        public void bump(int delta) {
            this.value += delta;
        }

        @Override
        public String toString() {
            return "Holder(" + value + ")";
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }
    }

    public static class UnassumedHelper {
        public UnassumedHelper() {}

        public static int compute(int x) {
            return x + 5;
        }
    }

    // ---------------------------------------------------------------- Helpers

    private static List<String> diagnostics(String source) {
        BabbleParser parser =
                new BabbleParser(
                        new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file());
        return analysis.typeDiagnostics().stream()
                .map(w -> w.line() + ":" + w.column() + " " + w.message())
                .toList();
    }

    private static void assertClean(String source) {
        List<String> diags = diagnostics(source);
        Assertions.assertEquals(List.of(), diags, source);
    }

    private static void assertHasError(String source, String substring) {
        List<String> diags = diagnostics(source);
        Assertions.assertFalse(
                diags.isEmpty(),
                "Expected error containing '" + substring + "' but got none for:\n" + source);
        Assertions.assertTrue(
                diags.stream().anyMatch(d -> d.contains(substring)),
                "Expected error containing '" + substring + "', got: " + diags);
    }

    private static BabbleScriptEngine newEngine() {
        return (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
    }

    private static Object eval(BabbleScriptEngine engine, String source) throws ScriptException {
        ScriptContext ctx = new SimpleScriptContext();
        ctx.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        ctx.setWriter(new StringWriter());
        ctx.setErrorWriter(new StringWriter());
        return engine.eval(source, ctx);
    }

    private static void assertEngineFails(
            BabbleScriptEngine engine, String source, String substring) {
        ScriptException e =
                Assertions.assertThrows(ScriptException.class, () -> eval(engine, source), source);
        Assertions.assertTrue(
                e.getMessage().contains(substring),
                "Expected '" + substring + "', got: " + e.getMessage());
    }

    // ---------------------------------------------------------------- 1. Inference & SCC
    // Fixed-Point

    @Test
    public void testAutomaticInferenceAndSccFixedPoint() {
        // Pure-by-default arithmetic function infers nothrow:
        assertClean(
                """
                def inc = fn (x: int): int -> (x + 1)
                def f: fn nothrow (x: int): int = inc
                """);

        // Mutually recursive pure functions converge to nothrow:
        assertClean(
                """
                def even = fn (n: int): bool -> (if n == 0 then (true) else (odd(n - 1)))
                def odd = fn (n: int): bool -> (if n == 0 then (false) else (even(n - 1)))
                def check: fn nothrow (n: int): bool = even
                """);

        // Mutual recursion where one branch prints fails pure-by-default check, and `fn io` cannot
        // assign to pure `fn`:
        assertHasError(
                """
                def a = fn (n: int): int -> (if n <= 0 then (0) else (b(n - 1)))
                def b = fn (n: int): int -> (println(n); a(n - 1))
                """,
                "violates pure guarantee");
        assertHasError(
                """
                def a = fn io (n: int): int -> (if n <= 0 then (0) else (b(n - 1)))
                def b = fn io (n: int): int -> (println(n); a(n - 1))
                def bad: fn (n: int): int = a
                """,
                "Type error");

        // Untyped inline lambda infers `mut`, `io`, `volatile`, and `shared`; typed function
        // requires explicit modifiers!
        assertClean(
                """
                def Box = class (var x: int) ()
                def untypedSet = fn (b) -> (b.x = 1)
                def caller = fn (mut b: Box) -> (untypedSet(b))
                caller(Box(0))
                """);
        assertClean(
                """
                def logIt = fn (x) -> (println(x); x)
                def run = fn io (): int -> (logIt(42))
                """);
        assertClean(
                """
                var g: int = 1
                g = 2
                def readG = fn (x) -> (g + x)
                def run = fn volatile (): int -> (readG(0))
                """);
        assertHasError(
                """
                def Box = class (var x: int) ()
                def untypedSet = fn (b) -> (b.x = 1)
                def badCaller = fn (b: Box) -> (untypedSet(b))
                badCaller(Box(0))
                """,
                "Cannot mutate parameter b");
        assertHasError(
                """
                def Box = class (var x: int) ()
                def typedSet = fn (b: Box) -> (b.x = 1)
                """,
                "Cannot mutate parameter b");
        assertHasError(
                """
                def Box = class (var x: int) ()
                def retTypedSet = fn (b): int -> (b.x = 1; 1)
                retTypedSet(Box(0))
                """,
                "Cannot mutate parameter b");
    }

    // ---------------------------------------------------------------- 2. Verified Constraints

    @Test
    public void testVerifiedConstraintsAndMutExclusivity() {
        // BIP016: pure-by-default allows `mut` params & `fn mut` methods (hermetic + deterministic
        // + explicit mutation)!
        assertClean(
                """
                def Box = class (var x: int) (
                    def bump = fn mut (): int -> (this.x = this.x + 1; this.x)
                )
                def setBox = fn (mut b: Box): int -> (b.x = 1; b.bump())
                """);

        // Pure-by-default allows local `var` reassignments and fresh-local object mutation, while
        // rejecting I/O & outer writes:
        assertClean(
                """
                def sumTo = fn nothrow (n: int): int -> (
                    var acc: int = 0
                    var i: int = 1
                    while i <= n then (
                        acc += i
                        i += 1
                    )
                    acc
                )
                """);
        assertHasError(
                """
                def f = fn (x: int): int -> (println(x); x)
                """,
                "violates pure guarantee");
        assertHasError(
                """
                var g: int = 0
                def f = fn (x: int): int -> (g = x; x)
                """,
                "violates pure guarantee");
        assertHasError(
                """
                var g: int = 0
                def f = fn volatile (x: int): int -> (g = x; x)
                """,
                "volatile");
        assertClean(
                """
                var g: int = 0
                def f = fn io (x: int): int -> (g = x; println(g); x)
                """);
        assertClean(
                """
                def Box = class (var x: int) ()
                def f = fn (): int -> (
                    def b = Box(1)
                    b.x = 2
                    b.x
                )
                """);
        assertHasError(
                """
                def Box = class (var x: int) ()
                def g = Box(1)
                def f = fn (): int -> (
                    g.x = 2
                    g.x
                )
                """,
                "Cannot mutate external object in pure function");

        // Determinism checks (pure-by-default rejects volatile reads; `fn volatile` allows them):
        assertHasError(
                """
                var g: int = 1
                g = 2
                def f = fn (x: int): int -> (x + g)
                """,
                "volatile");
        assertClean(
                """
                var g: int = 1
                g = 2
                def f = fn volatile (x: int): int -> (x + g)
                """);
        assertHasError(
                """
                def Box = class (var x: int) ()
                def g = Box(1)
                def f = fn (): int -> (g.x)
                """,
                "volatile");
        assertClean(
                """
                def Box = class (var x: int) ()
                def g = Box(1)
                def f = fn volatile (): int -> (g.x)
                """);
        assertHasError(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn (): int -> (HF.mutableStatic)
                """,
                "volatile");
        assertClean(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn volatile (): int -> (HF.mutableStatic)
                """);
        assertClean(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn (): int -> (HF.FINAL_STATIC)
                """);
        assertHasError(
                """
                def Box = class (def x: int) ()
                def f = fn (b: Box): string -> ("v=" + b)
                """,
                "volatile");
        assertClean(
                """
                def Box = class (def x: int) ()
                def f = fn volatile (b: Box): string -> ("v=" + b)
                """);
        assertClean(
                """
                def Pt = record (x: int, y: int) ()
                def f = fn nothrow (p: Pt): string -> ("p=" + p)
                """);

        // `nothrow` rejects `assert` and non-redundant `as` casts:
        assertHasError(
                """
                def f = fn nothrow (x: int): int -> (assert(x > 0); x)
                """,
                "nothrow");
        assertHasError(
                """
                def f = fn nothrow (x: any): int -> (x as int)
                """,
                "nothrow");

        // Verified pure functions reject nested `axiom` declarations:
        assertHasError(
                """
                def f = fn (): int -> (
                    axiom java.lang.System.nanoTime: fn pure
                    1
                )
                """,
                "not allowed inside a verified constrained function");
    }

    // ---------------------------------------------------------------- 3. Alias Tracking, Borrow
    // Escape & Exclusivity

    @Test
    public void testAliasTrackingBorrowEscapeAndCallSiteExclusivity() {
        // Local alias and sub-reference bypass attempts (`def a = p; a.x = 1`, `p.child.x = 1`,
        // `def s = this; s.x = 1`):
        assertHasError(
                """
                def Box = class (var x: int) ()
                def f = fn (p: Box): int -> (
                    def alias = p
                    alias.x = 2
                    alias.x
                )
                """,
                "Cannot mutate parameter p");
        assertHasError(
                """
                def Leaf = class (var x: int) ()
                def Node = class (def child: Leaf) ()
                def f = fn (n: Node): int -> (
                    n.child.x = 9
                    n.child.x
                )
                """,
                "Cannot mutate parameter n");
        assertHasError(
                """
                def C = class (var x: int) (
                    def sneak = fn (): int -> (
                        def self = this
                        self.x = 5
                        self.x
                    )
                )
                """,
                "Cannot mutate this");
        assertHasError(
                """
                def Box = class (var x: int) ()
                def f = fn (c: bool, mut a: Box, b: Box): int -> (
                    def pick = if c then (a) else (b)
                    pick.x = 1
                    pick.x
                )
                """,
                "Cannot mutate parameter b");

        // BIP016-D10: returning a @BORROWED parameter or `this` without storing is allowed (e.g.
        // `id`, `min`, getters),
        // while storing into globals, other objects' fields, or escaping closures requires
        // `shared`!
        assertClean(
                """
                def Box = class (def x: int) (
                    def self = fn (): Box -> (this)
                )
                def id of T = fn (x: T): T -> (x)
                def readViaId = fn (b: Box): int -> (id(b).x + b.self().x)
                """);
        assertHasError(
                """
                def Box = class (def x: int) ()
                var sink: Box? = null
                def escGlobal = fn io (b: Box): int -> (sink = b; 1)
                """,
                "Borrowed parameter b escapes");
        assertHasError(
                """
                def Box = class (def x: int) ()
                def Holder = class (var ref: Box?) ()
                def escField = fn (mut h: Holder, b: Box): int -> (h.ref = b; 1)
                """,
                "Borrowed parameter b escapes via field .ref");
        assertClean(
                """
                def Box = class (def x: int) ()
                def Holder = class (var ref: Box?) ()
                def storeField = fn (mut h: Holder, shared b: Box): int -> (h.ref = b; 1)
                """);
        // A closure capturing b aliases it : returning it is return-through-borrow, storing it
        // escapes (BIP017-D6).
        assertClean(
                """
                def Box = class (def x: int) ()
                def escClosure = fn (b: Box): fn volatile (): Box -> (fn () -> (b))
                """);
        assertHasError(
                """
                def Box = class (def x: int) ()
                def Holder = class (var ref: any?) ()
                def stashClosure = fn (mut h: Holder, b: Box): int -> (h.ref = fn () -> (b); 1)
                """,
                "Borrowed parameter b escapes via field .ref");
        assertHasError(
                """
                def Holder = class (var ref: any?) ()
                def Box = class (def x: int) (
                    def leakThis = fn (mut h: Holder) -> (h.ref = this)
                )
                """,
                "Borrowed this escapes via field .ref");
        assertClean(
                """
                def Holder = class (var ref: any?) ()
                def Box = class (def x: int) (
                    def storeThis = fn shared (mut h: Holder) -> (h.ref = this)
                )
                """);

        // Returning deeply immutable primitive or value-class field from default `@BORROWED`
        // parameter IS allowed!
        assertClean(
                """
                def Pt = record (x: int, y: int) ()
                def Holder = class (def pt: Pt) ()
                def readPt = fn nothrow (h: Holder): Pt -> (h.pt)
                """);

        // Static call-site `mut` exclusivity:
        assertHasError(
                """
                def Box = class (var x: int) ()
                def swap = fn (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def b = Box(1)
                swap(b, b)
                """,
                "Exclusivity error");
        assertHasError(
                """
                def Box = class (var x: int) ()
                def rw = fn (mut a: Box, b: Box): int -> (a.x = b.x + 1; a.x)
                def x = Box(1)
                def y = x
                rw(x, y)
                """,
                "Exclusivity error");
        assertHasError(
                """
                def Box = class (var x: int) (
                    def addFrom = fn mut (other: Box) -> (this.x += other.x)
                )
                def Wrap = class (def b: Box) ()
                def w = Wrap(Box(3))
                w.b.addFrom(w.b)
                """,
                "Exclusivity error");

        // Dynamic indexed expressions (`list.get(i)`, `list.get(j)`) and `string` arguments are
        // permitted:
        assertClean(
                """
                def Box = class (var x: int) ()
                def swap = fn (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def xs: java.util.List of Box = java.util.ArrayList()
                xs.add(Box(1))
                xs.add(Box(2))
                def a = xs.get(0)
                def b = xs.get(1)
                if a != null and b != null then (
                    swap(a, b)
                )
                """);
    }

    // ---------------------------------------------------------------- 4. BIP007-D1 Narrowing &
    // Frame-Local Closures

    @Test
    public void testFlowSensitiveNarrowingAndFrameLocalClosures() {
        // BIP007-D1 : `!= null`, `== null`, `is T`, and short-circuit `and` / `or` preserve
        // `nothrow`!
        assertClean(
                """
                def f1 = fn nothrow (x: int?): int -> (
                    if x != null then (x as int) else (0)
                )
                def f2 = fn nothrow (x: int?): int -> (
                    if x == null then (0) else (x as int)
                )
                def f3 = fn nothrow (x: int | string): int -> (
                    if x is int then (x as int) else (0)
                )
                def f4 = fn nothrow (x: int?): bool -> (
                    x != null and (x as int) > 0
                )
                def f5 = fn nothrow (x: int?): bool -> (
                    x == null or (x as int) > 0
                )
                """);

        // Un-narrowed nullable cast still breaks `nothrow`:
        assertHasError(
                """
                def bad = fn nothrow (x: int?): int -> (x as int)
                """,
                "nothrow");

        // Frame-local (default `@BORROWED`) helper closure updating enclosing function's local
        // `var` preserves pure `nothrow`:
        assertClean(
                """
                def repeat = fn nothrow calling step (n: int, step: fn (i: int): int): int -> (
                    var i: int = 0
                    while i < n then (
                        step(i)
                        i += 1
                    )
                    n
                )
                def triangle = fn nothrow (n: int): int -> (
                    var total: int = 0
                    repeat(n, fn nothrow (k: int): int -> (
                        total += k
                        total
                    ))
                    total
                )
                """);
    }

    // ---------------------------------------------------------------- 5. Higher-Order Forwarding
    // (`fn calling f`) & Subtyping

    @Test
    public void testHigherOrderSpecializationSubtypingAndInterfaces() {
        // BIP017-D1/D2: `fn calling f` forwards callback effects cleanly for pure, `nothrow`,
        // `volatile`, and `io` callbacks!
        assertClean(
                """
                def apply = fn nothrow calling f (f: fn (x: int): int, v: int): int -> (f(v))
                def caller = fn nothrow (x: int): int -> (
                    apply(fn nothrow (n: int): int -> (n * 2), x)
                )
                """);
        assertHasError(
                """
                def apply = fn calling f (f: fn (x: int): int, v: int): int -> (f(v))
                def badCaller = fn (x: int): int -> (
                    apply(fn io (n: int): int -> (println(n); n), x)
                )
                """,
                "violates pure guarantee");

        // BIP017-D4: `fn io` HOFs are no longer secretly specialized intra-file!
        assertHasError(
                """
                def apply = fn io (f: fn io (x: int): int, v: int): int -> (f(v))
                def badCaller = fn (x: int): int -> (
                    apply(fn (n: int): int -> (n * 2), x)
                )
                """,
                "violates pure guarantee");

        // Concrete `fn io` HOF that performs its own I/O does NOT specialize to pure!
        assertHasError(
                """
                def noisyApply = fn io (f: fn io (x: int): int, v: int): int -> (println(v); f(v))
                def badCaller = fn (x: int): int -> (
                    noisyApply(fn (n: int): int -> (n + 1), x)
                )
                """,
                "violates pure guarantee");

        // Abstract interface `fn io` methods do NOT specialize across call sites:
        assertHasError(
                """
                def Runner = interface (
                    def apply: fn io (f: fn io (x: int): int, v: int): int
                )
                def badCaller = fn (r: Runner, x: int): int -> (
                    r.apply(fn (n: int): int -> (n + 1), x)
                )
                """,
                "violates pure guarantee");

        // BIP017-D3: Abstract interface `fn calling f` methods DO forward effects across call
        // sites!
        assertClean(
                """
                def Runner = interface (
                    def apply: fn calling f (f: fn (x: int): int, v: int): int
                )
                def pureCaller = fn (r: Runner, x: int): int -> (
                    r.apply(fn (n: int): int -> (n + 1), x)
                )
                def ioCaller = fn io (r: Runner, x: int): int -> (
                    r.apply(fn io (n: int): int -> (println(n); n + 1), x)
                )
                """);
        assertHasError(
                """
                def Runner = interface (
                    def apply: fn calling f (f: fn (x: int): int, v: int): int
                )
                def badCaller = fn (r: Runner, x: int): int -> (
                    r.apply(fn io (n: int): int -> (println(n); n + 1), x)
                )
                """,
                "violates pure guarantee");

        // Function subtyping hierarchy: `fn nothrow <: fn <: fn volatile <: fn io`, `@CONST <:
        // mut`, `@BORROWED <: shared`:
        assertClean(
                """
                def Box = class (def x: int) ()
                def pFn = fn nothrow (b: Box): int -> (b.x)
                def asPure: fn (b: Box): int = pFn
                def asVolatile: fn volatile (b: Box): int = pFn
                def asIo: fn io (b: Box): int = pFn
                def asMutPermitted: fn (mut b: Box): int = pFn
                def asSharedPermitted: fn (shared b: Box): int = pFn
                """);
        assertHasError(
                """
                def noisy = fn io (x: int): int -> (println(x); x)
                def needPure: fn (x: int): int = noisy
                """,
                "Type error");
        assertHasError(
                """
                var g: int = 1
                g = 2
                def vol = fn volatile (x: int): int -> (x + g)
                def needPure: fn (x: int): int = vol
                """,
                "Type error");
        assertHasError(
                """
                def Box = class (var x: int) ()
                def mutFn = fn (mut b: Box): int -> (b.x = 1; 1)
                def noMutAllowed: fn (b: Box): int = mutFn
                """,
                "Type error");
        assertHasError(
                """
                def Box = class (def x: int) ()
                var sink: Box? = null
                def escaping = fn io (shared b: Box): int -> (sink = b; 1)
                def needBorrow: fn io (b: Box): int = escaping
                """,
                "Type error");

        // Interface conformance checks pure/volatile/io and `mut`:
        assertClean(
                """
                def Calc = interface (
                    def eval: fn (x: int): int
                )
                def DoubleCalc = class () implements Calc (
                    def eval = fn (x: int): int -> (x * 2)
                )
                """);
        assertHasError(
                """
                def Calc = interface (
                    def eval: fn (x: int): int
                )
                def NoisyCalc = class () implements Calc (
                    def eval = fn io (x: int): int -> (println(x); x)
                )
                """,
                "Type error");
    }

    // ---------------------------------------------------------------- 6. Algebraic Properties,
    // Value Classes & Inline Objects

    @Test
    public void testAlgebraicPropertiesValueClassesAndInlineObjects() {
        // Wrapping 32-bit `int` addition is NOT `monotone`; `double` addition and non-negative
        // scaling ARE `monotone`!
        assertHasError(
                """
                def badMono = fn monotone (x: int): int -> (x + 1)
                """,
                "monotone");
        assertClean(
                """
                def step1 = fn nothrow monotone (x: double): double -> (x * 2.0 + 3.0)
                def step2 = fn nothrow monotone (x: double): double -> (step1(x) + 1.0)
                """);
        assertHasError(
                """
                def antiMono = fn monotone (x: double): double -> (x * -2.0)
                """,
                "monotone");
        assertHasError(
                """
                def negMono = fn monotone (x: double): double -> (-x)
                """,
                "monotone");

        // `int * k` is `injective` for odd `k` (mod 2^32 bijection) and rejected for even `k`:
        assertClean(
                """
                def injOdd = fn nothrow injective (x: int): int -> (x * 3 + 1)
                def injStr = fn nothrow injective (x: int): string -> ("k=" + x)
                def Wrap = record (x: int) ()
                def injWrap = fn nothrow injective (x: int): Wrap -> (Wrap(x))
                """);
        assertHasError(
                """
                def badEven = fn injective (x: int): int -> (x * 2)
                """,
                "injective");

        // Single-op wrappers for `commutative`, `associative`, `idempotent` & signature
        // applicability:
        assertClean(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def add = fn nothrow commutative associative (a: int, b: int): int -> (a + b)
                def xor = fn nothrow commutative associative (a: int, b: int): int -> (HF.bitXor(a, b))
                def clamp = fn nothrow idempotent (x: double): double -> (HF.clampUnit(x))
                """);
        assertHasError(
                """
                def sub = fn commutative (a: int, b: int): int -> (a - b)
                """,
                "commutative");
        assertHasError(
                """
                def I = interface (def badSig: fn commutative (x: int): int)
                """,
                "commutative without mut requires a pure binary (T, T) : R signature");
        assertHasError(
                """
                def I = interface (def badAssoc: fn associative (a: int, b: int): bool)
                """,
                "associative requires a pure binary (T, T) : T signature");
        assertHasError(
                """
                def I = interface (def badIdem: fn idempotent (x: int): string)
                """,
                "idempotent without mut requires a pure unary (T) : T signature");
        assertHasError(
                """
                def Box = class (def x: int) ()
                def I = interface (def badOrder: fn monotone (b: Box): Box)
                """,
                "monotone requires ordered parameter and return types");

        // `record` (value class) constructor must be pure & deterministic:
        assertHasError(
                """
                def BadVal = record (x: int) (
                    init (
                        println(x)
                    )
                )
                """,
                "value class");

        // Inline `object (...)` inside a pure function executes its body under pure constraints:
        assertHasError(
                """
                def f = fn (): any -> (
                    object (
                        init (
                            println("side effect")
                        )
                    )
                )
                """,
                "violates pure guarantee");
    }

    // ---------------------------------------------------------------- 7. Host Annotations, Engine
    // Config & Priority Order

    @Test
    public void testJavaAnnotationsEngineConfigPriorityAndOrderIndependence() throws Exception {
        // BIP007-D4 : `axiom` is order-independent within a file (usable above its declaration)!
        assertClean(
                """
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = fn (): int -> (UH.compute(10))
                axiom org.babblelang.tests.EffectsTestCase.UnassumedHelper.compute: fn pure
                """);

        // Runtime @Pure, @Volatile, @Mut, @Shared, @IO, class-level @Pure, and Java SAM callback
        // propagation:
        assertClean(
                """
                def tests = import("org.babblelang.tests")
                def HF = tests.EffectsTestCase.HostFixtures
                def APC = tests.EffectsTestCase.AnnotatedPureClass
                def f = fn nothrow (x: int): int -> (
                    HF.pureCalc(x) + HF.applyOp(x, fn (v: int): int -> (v + 1))
                )
                def v = fn volatile nothrow (x: int): int -> (HF.volatileRead(x))
                def g = fn (x: int): int -> (APC.helper(x))
                def m = fn nothrow (mut dst: tests.EffectsTestCase.MutableHolder, src: tests.EffectsTestCase.MutableHolder) -> (
                    HF.mutateParam(dst, src)
                    dst.bump(1)
                )
                def e = fn nothrow (shared h: tests.EffectsTestCase.MutableHolder): tests.EffectsTestCase.MutableHolder -> (
                    HF.echoShared(h)
                )
                """);
        // @Volatile method rejected in pure `fn`!
        assertHasError(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def badVol = fn (x: int): int -> (HF.volatileRead(x))
                """,
                "volatile");
        // @Shared Java parameter rejects borrowed Babble parameter!
        assertHasError(
                """
                def tests = import("org.babblelang.tests")
                def HF = tests.EffectsTestCase.HostFixtures
                def badBorrow = fn (h: tests.EffectsTestCase.MutableHolder): tests.EffectsTestCase.MutableHolder -> (
                    HF.echoShared(h)
                )
                """,
                "Borrowed parameter h escapes");
        // Class-level @Pure (without @Nothrow) does NOT imply nothrow!
        assertHasError(
                """
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def badNothrow = fn nothrow (x: int): int -> (APC.helper(x))
                """,
                "nothrow");

        // Method-level @IO overrides class-level @Pure, and `io` Java SAM callback taints caller:
        assertHasError(
                """
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def bad = fn (x: int): int -> (APC.escapeHatch(x))
                """,
                "violates pure guarantee");
        assertHasError(
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def badSam = fn (x: int): int -> (
                    HF.applyOp(x, fn io (v: int): int -> (println(v); v))
                )
                """,
                "violates pure guarantee");

        // Value protocol defaults: `Object.hashCode()` is `volatile` (NOT deterministic),
        // whereas overridden `MutableHolder.hashCode()` is pure!
        assertHasError(
                """
                def bad = fn (o: java.lang.StringBuilder): int -> (o.hashCode())
                """,
                "volatile");
        assertClean(
                """
                def okVol = fn volatile (o: java.lang.StringBuilder): int -> (o.hashCode())
                """);
        assertClean(
                """
                def tests = import("org.babblelang.tests")
                def ok = fn (h: tests.EffectsTestCase.MutableHolder): int -> (h.hashCode())
                """);

        // Engine-level configuration: `assumeEffects`, `assumeConstructorEffects`, `markIO`, and
        // `allowAxioms(false)`:
        BabbleScriptEngine engine = newEngine();
        engine.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.NOTHROW);
        engine.assumeConstructorEffects(UnassumedHelper.class, Effect.PURE, Effect.NOTHROW);
        Assertions.assertEquals(
                15,
                eval(
                        engine,
                        """
                        def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                        def f = fn nothrow (x: int): int -> (
                            discard(UH())
                            UH.compute(x)
                        )
                        f(10)
                        """));

        // §6.1 Priority order:
        // - Tier 1 (file method `axiom ...: fn io`) > Tier 3 (`@Pure` method annotation)
        // - Tier 2 (`engine.markIO` method) > Tier 3 (`@Pure` method) & Tier 4
        // (`engine.assumeEffects` class)
        // - Tier 1 (file method `axiom ...: fn pure`) > Tier 2 (`engine.markIO` method) when
        // `allowAxioms(true)`
        assertEngineFails(
                engine,
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                axiom org.babblelang.tests.EffectsTestCase.HostFixtures.pureCalc: fn io
                def f = fn (x: int): int -> (HF.pureCalc(x))
                f(1)
                """,
                "violates pure guarantee");

        engine.markIO(HostFixtures.class, "pureCalc");
        engine.assumeEffects(HostFixtures.class, Effect.PURE);
        assertEngineFails(
                engine,
                """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn (x: int): int -> (HF.pureCalc(x))
                f(1)
                """,
                "violates pure guarantee");
        Assertions.assertEquals(
                3,
                eval(
                        engine,
                        """
                        def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                        axiom org.babblelang.tests.EffectsTestCase.HostFixtures.pureCalc: fn pure
                        def f = fn (x: int): int -> (HF.pureCalc(x))
                        f(1)
                        """));

        // `allowAxioms(false)` disables Babble `axiom` declarations while keeping engine
        // assumptions & @Pure annotations active!
        BabbleScriptEngine locked = newEngine();
        locked.allowAxioms(false);
        Assertions.assertFalse(locked.allowsAxioms());
        locked.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.NOTHROW);
        Assertions.assertEquals(
                25,
                eval(
                        locked,
                        """
                        def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                        def f = fn nothrow (x: int): int -> (UH.compute(x))
                        f(20)
                        """));
        assertEngineFails(
                locked,
                """
                axiom java.lang.Math: class () static (def *: fn pure)
                1
                """,
                "axiom declarations are disabled on this engine");
        assertEngineFails(
                locked,
                """
                axiom java.lang.System.nanoTime: fn io
                1
                """,
                "axiom declarations are disabled on this engine");
    }

    @Test
    public void testCrossModuleHigherOrderWithEffects(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Files.writeString(
                tempDir.resolve("hof_lib.ba"),
                """
                export def forEachInt = fn calling f (xs: list of int, f: fn (x: int): any?) -> (
                    for x in xs then (
                        f(x)
                    )
                )
                export def mapList of (T, R) = fn calling f (shared xs: list of T, f: fn (x: T): R): list of R -> (
                    def out = list of R ()
                    for x in xs then (
                        out.add(f(x))
                    )
                    out
                )
                export def Acc = class () (
                    var total: int = 0
                    def append = fn mut (x: int) -> (
                        this.total += x
                    )
                )
                """);

        BabbleScriptEngine engine = newEngine();
        engine.put(
                BabbleScriptEngine.MODULE_SOURCE,
                org.babblelang.engine.FileModuleSource.rooted(tempDir));

        // Cross-module pure caller with frame-local closure, bound mutating method, and generic
        // HOF:
        Object result =
                eval(
                        engine,
                        """
                        def lib = import("hof_lib.ba")
                        def runPure = fn (shared xs: list of int): int -> (
                            var sum: int = 0
                            lib.forEachInt(xs, fn (x: int) -> (
                                sum += x
                            ))
                            def acc = lib.Acc()
                            lib.forEachInt(xs, acc.append)
                            def doubled = lib.mapList(xs, fn (x: int): int -> (x * 2))
                            var dsum: int = 0
                            for d in doubled then (
                                dsum += d
                            )
                            sum + acc.total + dsum
                        )
                        runPure((list of int).of(1, 2, 3))
                        """);
        Assertions.assertEquals(24, result);

        // Cross-module `io` callback inside a pure caller must still fail:
        assertEngineFails(
                engine,
                """
                def lib = import("hof_lib.ba")
                def badPure = fn (xs: list of int) -> (
                    lib.forEachInt(xs, fn io (x: int) -> (println(x)))
                )
                badPure((list of int).of(1))
                """,
                "violates pure guarantee");
    }

    @Test
    public void testBip034PerFieldImmutabilityAndStaticPrivateVar() {
        // Reading a regular class's `def` field vs `var` field, plus `private var` static vs `def`
        // static:
        assertClean(
                """
                def Range = class (private var cur: int, private def stop: int) (
                    def getStop = fn (): int -> ( this.stop )
                    def hasNext = fn (): bool -> ( this.cur < this.stop )
                ) static (
                    def defaultStop = 10
                    private var created = 0
                    def getDefault = fn (): int -> ( this.defaultStop )
                    def getCreated = fn volatile (): int -> ( this.created )
                    def bumpCreated = fn io (): int -> ( this.created += 1 ; this.created )
                )
                def r = Range(0, Range.getDefault())
                assert(r.getStop() == 10 and r.hasNext())
                """);
        assertHasError(
                """
                def C = class () () static (
                    private var s = 1
                    def bad = fn (): int -> ( this.s )
                )
                """,
                "violates deterministic guarantee");
    }

    @Test
    public void testBip038MatchEffectsOwnershipAndTermination() {
        // 1. Recursive ADT evaluator and recursive integer match are certified @TERMINATING (pure
        // nothrow)
        String adtSrc =
                """
                def Num = record (n: int) ()
                def Add = record (l: Num | Add, r: Num | Add) ()
                def eval = fn (e: Num | Add): int -> (
                    match e
                    case is Num(def n) then (n)
                    case is Add(def l, def r) then (recurse(l) + recurse(r))
                )
                def sumDown = fn (n: int, acc: int): int -> (
                    match n
                    case <= 0 then (acc)
                    else (recurse(n - 1, acc + n))
                )
                assert(eval(Add(Num(2), Num(3))) == 5 and sumDown(10, 0) == 55)
                """;
        BabbleParser adtParser =
                new BabbleParser(
                        new CommonTokenStream(new BabbleLexer(CharStreams.fromString(adtSrc))));
        adtParser.setErrorHandler(new BailErrorStrategy());
        BabbleParser.FileContext adtFile = adtParser.file();
        ScopeAnalysis adtAnalysis = BlockScopes.analyze(adtFile);
        Assertions.assertTrue(
                adtAnalysis.typeDiagnostics().isEmpty(),
                () -> adtAnalysis.typeDiagnostics().toString());
        BabbleParser.FunctionLiteralContext evalFn =
                (BabbleParser.FunctionLiteralContext)
                        ((BabbleParser.DefExpressionContext) adtFile.expression(2)).value;
        BabbleParser.FunctionLiteralContext sumDownFn =
                (BabbleParser.FunctionLiteralContext)
                        ((BabbleParser.DefExpressionContext) adtFile.expression(3)).value;
        Assertions.assertTrue(
                adtAnalysis.effects().callableEffects(evalFn).terminating(),
                "Recursive ADT match eval should be certified @TERMINATING");
        Assertions.assertTrue(
                adtAnalysis.effects().callableEffects(evalFn).isPure()
                        && adtAnalysis.effects().callableEffects(evalFn).isNothrow(),
                "Recursive ADT match eval should be pure nothrow");
        Assertions.assertTrue(
                adtAnalysis.effects().callableEffects(sumDownFn).terminating(),
                "Recursive integer match sumDown should be certified @TERMINATING");

        // 2. Discarded pure match emits Unused pure expression diagnostic
        assertHasError(
                """
                def f = fn (b: bool): int -> (
                    match b
                    case true then (1)
                    case false then (2)
                    0
                )
                """,
                "Unused pure expression");

        // 3. Borrowing `match x` keeps `x` live, while `match move(x)` consumes `x` and transfers
        // @UNIQUE
        assertClean(
                """
                def take = fn (unique xs: list of int): int -> ( xs.size() )
                def okBorrow = fn (): int -> (
                    def xs = list(1, 2)
                    def h = match xs case is list(def a, ...) then (a) else (0)
                    h + take(move(xs))
                )
                def okConsume = fn (b: bool): int -> (
                    def xs = list(1, 2, 3)
                    match move(xs)
                    case def ys if b then (take(move(ys)))
                    else def zs then (take(move(zs)))
                )
                assert(okBorrow() == 3 and okConsume(true) == 3 and okConsume(false) == 3)
                """);
        assertHasError(
                """
                def take = fn (unique xs: list of int): int -> ( xs.size() )
                def badAfterMove = fn (): int -> (
                    def xs = list(1, 2)
                    def n = match move(xs) case def ys if true then (ys.size()) else (0)
                    n + take(move(xs))
                )
                """,
                "Use of moved variable xs");

        // 4. Exhaustive match over external Java enum without `else` is NOT `nothrow` (runtime
        // defense-in-depth)
        assertHasError(
                """
                def AM = java.nio.file.AccessMode
                def code = fn nothrow (m: java.nio.file.AccessMode): int -> (
                    match m
                    case AM.READ then (1)
                    case AM.WRITE then (2)
                    case AM.EXECUTE then (4)
                )
                """,
                "violates nothrow guarantee");
    }
}
