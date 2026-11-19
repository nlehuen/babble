package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.effects.Associative;
import org.babblelang.effects.Borrow;
import org.babblelang.effects.Commutative;
import org.babblelang.effects.Deterministic;
import org.babblelang.effects.Effect;
import org.babblelang.effects.Hermetic;
import org.babblelang.effects.Idempotent;
import org.babblelang.effects.Impure;
import org.babblelang.effects.Injective;
import org.babblelang.effects.Monotone;
import org.babblelang.effects.Mut;
import org.babblelang.effects.Nothrow;
import org.babblelang.effects.Pure;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Tests for RM002 (BIP007-P1) : static effect inference & verification, mut / borrow & alias
 * tracking, static call-site exclusivity, BIP007-D1 flow-sensitive narrowing, frame-local
 * closures, bounded call-site specialization, compositional algebraic properties, and the
 * 7-tier Java host effect & axiom contract layer.
 */
public class EffectsTestCase {

    // ---------------------------------------------------------------- Java host fixtures

    public static class HostFixtures {
        public static int mutableStatic = 1;
        public static final int FINAL_STATIC = 42;

        @Pure
        @Deterministic
        @Nothrow
        public static int pureCalc(int x) {
            return x * 2 + 1;
        }

        @Pure
        @Deterministic
        @Nothrow
        @Idempotent
        public static double clampUnit(double x) {
            return Math.max(0.0, Math.min(1.0, x));
        }

        @Pure
        @Deterministic
        @Nothrow
        @Commutative
        @Associative
        public static int bitXor(int a, int b) {
            return a ^ b;
        }

        @Pure
        @Deterministic
        @Nothrow
        @Monotone
        public static double expStep(double x) {
            return Math.exp(x);
        }

        @org.babblelang.effects.NotNull
        @Pure
        @Deterministic
        @Nothrow
        @Injective
        public static String hexKey(int x) {
            return Integer.toHexString(x);
        }

        @Impure
        public static int annotatedImpure(int x) {
            return x;
        }

        @Hermetic
        @Deterministic
        @Nothrow
        public static void mutateParam(@Mut MutableHolder h, @Borrow MutableHolder src) {
            h.value = src.value;
        }

        @Pure
        @Deterministic
        @Nothrow
        public static int applyOp(int x, @Borrow IntUnaryOperator op) {
            return op.applyAsInt(x);
        }
    }

    @Pure
    @Deterministic
    public static class AnnotatedPureClass {
        public static int helper(int x) {
            return x + 10;
        }

        @Impure
        public static int escapeHatch(int x) {
            return x;
        }
    }

    public static class MutableHolder {
        public int value;

        @Pure
        @Deterministic
        @Nothrow
        public MutableHolder(int value) {
            this.value = value;
        }

        @Mut
        @Hermetic
        @Deterministic
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
        public UnassumedHelper() {
        }

        public static int compute(int x) {
            return x + 5;
        }
    }

    // ---------------------------------------------------------------- Helpers

    private static List<String> diagnostics(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file());
        return analysis.typeDiagnostics().stream().map(w -> w.line() + ":" + w.column() + " " + w.message()).toList();
    }

    private static void assertClean(String source) {
        List<String> diags = diagnostics(source);
        Assertions.assertEquals(List.of(), diags, source);
    }

    private static void assertHasError(String source, String substring) {
        List<String> diags = diagnostics(source);
        Assertions.assertFalse(diags.isEmpty(), "Expected error containing '" + substring + "' but got none for:\n" + source);
        Assertions.assertTrue(diags.stream().anyMatch(d -> d.contains(substring)),
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

    private static void assertEngineFails(BabbleScriptEngine engine, String source, String substring) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> eval(engine, source), source);
        Assertions.assertTrue(e.getMessage().contains(substring),
                "Expected '" + substring + "', got: " + e.getMessage());
    }

    // ---------------------------------------------------------------- 1. Inference & SCC Fixed-Point

    @Test
    public void testAutomaticInferenceAndSccFixedPoint() {
        // Unannotated arithmetic function infers pure deterministic nothrow:
        assertClean("""
                def inc = fn (x: int): int -> (x + 1)
                def f: fn pure deterministic nothrow (x: int): int = inc
                """);

        // Mutually recursive pure functions converge to pure deterministic nothrow:
        assertClean("""
                def even = fn (n: int): bool -> (if n == 0 then (true) else (odd(n - 1)))
                def odd = fn (n: int): bool -> (if n == 0 then (false) else (even(n - 1)))
                def check: fn pure deterministic nothrow (n: int): bool = even
                """);

        // Mutual recursion where one branch prints taints the entire SCC:
        assertHasError("""
                def a = fn (n: int): int -> (if n <= 0 then (0) else (b(n - 1)))
                def b = fn (n: int): int -> (println(n); a(n - 1))
                def bad: fn pure (n: int): int = a
                """, "Type error : expected pure");

        // Untyped function automatically infers `mut` on mutated parameters; typed function requires explicit `mut`!
        assertClean("""
                def Box = class (def x: int) ()
                def untypedSet = fn (b) -> (b.x = 1)
                def caller = fn (mut b: Box) -> (untypedSet(b))
                caller(Box(0))
                """);
        assertHasError("""
                def Box = class (def x: int) ()
                def untypedSet = fn (b) -> (b.x = 1)
                def badCaller = fn (b: Box) -> (untypedSet(b))
                badCaller(Box(0))
                """, "Cannot mutate parameter b");
        assertHasError("""
                def Box = class (def x: int) ()
                def typedSet = fn (b: Box) -> (b.x = 1)
                """, "Cannot mutate parameter b");
        assertHasError("""
                def Box = class (def x: int) ()
                def retTypedSet = fn (b): int -> (b.x = 1; 1)
                retTypedSet(Box(0))
                """, "Cannot mutate parameter b");
    }

    // ---------------------------------------------------------------- 2. Verified Constraints

    @Test
    public void testVerifiedConstraintsAndMutExclusivity() {
        // pure + mut mutual exclusion on function literals and function types:
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn pure (mut b: Box): int -> (b.x = 1; 1)
                """, "pure and mut are mutually exclusive");
        assertHasError("""
                def Box = class (def x: int) ()
                def f: fn pure (mut b: Box): int
                """, "pure and mut are mutually exclusive");
        assertHasError("""
                def C = class (def x: int) (
                    def f = fn pure mut (): int -> (this.x = 1; 1)
                )
                """, "pure and mut are mutually exclusive");

        // `pure` allows local `def` reassignments, while rejecting I/O, outer writes, and (in RM002) fresh-local object mutation:
        assertClean("""
                def sumTo = fn pure deterministic nothrow (n: int): int -> (
                    def acc: int = 0
                    def i: int = 1
                    while i <= n then (
                        acc += i
                        i += 1
                    )
                    acc
                )
                """);
        assertHasError("""
                def f = fn pure (x: int): int -> (println(x); x)
                """, "pure");
        assertHasError("""
                def g: int = 0
                def f = fn hermetic (x: int): int -> (g = x; x)
                """, "hermetic");
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn pure (): int -> (
                    def b = Box(1)
                    b.x = 2
                    b.x
                )
                """, "Cannot mutate object in pure function");

        // `deterministic` checks: reassigned outer def, mutable outer object, non-final Java static field, identity toString:
        assertHasError("""
                def g: int = 1
                g = 2
                def f = fn deterministic (x: int): int -> (x + g)
                """, "deterministic");
        assertHasError("""
                def Box = class (def x: int) ()
                def g = Box(1)
                def f = fn deterministic (): int -> (g.x)
                """, "deterministic");
        assertHasError("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn deterministic (): int -> (HF.mutableStatic)
                """, "deterministic");
        assertClean("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn deterministic (): int -> (HF.FINAL_STATIC)
                """);
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn deterministic (b: Box): string -> ("v=" + b)
                """, "deterministic");
        assertClean("""
                def Pt = value class (x: int, y: int) ()
                def f = fn pure deterministic nothrow (p: Pt): string -> ("p=" + p)
                """);

        // `nothrow` rejects `assert` and non-redundant `as` casts:
        assertHasError("""
                def f = fn nothrow (x: int): int -> (assert(x > 0); x)
                """, "nothrow");
        assertHasError("""
                def f = fn nothrow (x: any): int -> (x as int)
                """, "nothrow");

        // Constrained verified functions reject nested `axiom` / `impure` declarations:
        assertHasError("""
                def f = fn pure (): int -> (
                    axiom pure on java.lang.System.nanoTime
                    1
                )
                """, "not allowed inside a verified constrained function");
    }

    // ---------------------------------------------------------------- 3. Alias Tracking, Borrow Escape & Exclusivity

    @Test
    public void testAliasTrackingBorrowEscapeAndCallSiteExclusivity() {
        // Local alias and sub-reference bypass attempts (`def a = p; a.x = 1`, `p.child.x = 1`, `def s = this; s.x = 1`):
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (p: Box): int -> (
                    def alias = p
                    alias.x = 2
                    alias.x
                )
                """, "Cannot mutate parameter p");
        assertHasError("""
                def Leaf = class (def x: int) ()
                def Node = class (def child: Leaf) ()
                def f = fn (n: Node): int -> (
                    n.child.x = 9
                    n.child.x
                )
                """, "Cannot mutate parameter n");
        assertHasError("""
                def C = class (def x: int) (
                    def sneak = fn (): int -> (
                        def self = this
                        self.x = 5
                        self.x
                    )
                )
                """, "Cannot mutate this");
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (c: bool, mut a: Box, b: Box): int -> (
                    def pick = if c then (a) else (b)
                    pick.x = 1
                    pick.x
                )
                """, "Cannot mutate parameter b");

        // `borrow` escape checks (return, field store, outer store, escaping closure, non-borrow callee):
        assertHasError("""
                def Box = class (def x: int) ()
                def escReturn = fn (borrow b: Box): Box -> (b)
                """, "borrow parameter b escapes via return value");
        assertHasError("""
                def Box = class (def x: int) ()
                def sink: Box? = null
                def escGlobal = fn (borrow b: Box): int -> (sink = b; 1)
                """, "borrow parameter b escapes");
        assertHasError("""
                def Box = class (def x: int) ()
                def Holder = class (def ref: Box?) ()
                def escField = fn (mut h: Holder, borrow b: Box): int -> (h.ref = b; 1)
                """, "borrow parameter b escapes via field .ref");
        assertHasError("""
                def Box = class (def x: int) ()
                def escClosure = fn (borrow b: Box): fn (): Box -> (fn () -> (b))
                """, "borrow parameter b escapes via captured closure");

        // Returning deeply immutable primitive or value-class field from `borrow` parameter IS allowed!
        assertClean("""
                def Pt = value class (x: int, y: int) ()
                def Holder = class (def pt: Pt) ()
                def readPt = fn pure deterministic nothrow (borrow h: Holder): Pt -> (h.pt)
                """);

        // Static call-site `mut` exclusivity:
        assertHasError("""
                def Box = class (def x: int) ()
                def swap = fn hermetic (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def b = Box(1)
                swap(b, b)
                """, "Exclusivity error");
        assertHasError("""
                def Box = class (def x: int) ()
                def rw = fn hermetic (mut a: Box, b: Box): int -> (a.x = b.x + 1; a.x)
                def x = Box(1)
                def y = x
                rw(x, y)
                """, "Exclusivity error");
        assertHasError("""
                def Box = class (def x: int) (
                    def addFrom = fn mut (other: Box) -> (this.x += other.x)
                )
                def Wrap = class (def b: Box) ()
                def w = Wrap(Box(3))
                w.b.addFrom(w.b)
                """, "Exclusivity error");

        // Dynamic indexed expressions (`list.get(i)`, `list.get(j)`) and `string` arguments are permitted in RM002:
        assertClean("""
                def Box = class (def x: int) ()
                def swap = fn hermetic (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def xs: java.util.List(Box) = java.util.ArrayList()
                xs.add(Box(1))
                xs.add(Box(2))
                def a = xs.get(0)
                def b = xs.get(1)
                if a != null and b != null then (
                    swap(a, b)
                )
                """);
    }

    // ---------------------------------------------------------------- 4. BIP007-D1 Narrowing & Frame-Local Closures

    @Test
    public void testFlowSensitiveNarrowingAndFrameLocalClosures() {
        // BIP007-D1 : `!= null`, `== null`, `is T`, and short-circuit `and` / `or` preserve `nothrow`!
        assertClean("""
                def f1 = fn pure deterministic nothrow (x: int?): int -> (
                    if x != null then (x as int) else (0)
                )
                def f2 = fn pure deterministic nothrow (x: int?): int -> (
                    if x == null then (0) else (x as int)
                )
                def f3 = fn pure deterministic nothrow (x: int | string): int -> (
                    if x is int then (x as int) else (0)
                )
                def f4 = fn pure deterministic nothrow (x: int?): bool -> (
                    x != null and (x as int) > 0
                )
                def f5 = fn pure deterministic nothrow (x: int?): bool -> (
                    x == null or (x as int) > 0
                )
                """);

        // Un-narrowed nullable cast still breaks `nothrow`:
        assertHasError("""
                def bad = fn nothrow (x: int?): int -> (x as int)
                """, "nothrow");

        // Frame-local (`borrow`) helper closure updating enclosing function's local `def` preserves `pure deterministic nothrow`:
        assertClean("""
                def repeat = fn (n: int, borrow step: fn (i: int): int): int -> (
                    def i: int = 0
                    while i < n then (
                        step(i)
                        i += 1
                    )
                    n
                )
                def triangle = fn pure deterministic nothrow (n: int): int -> (
                    def total: int = 0
                    repeat(n, fn (k: int): int -> (
                        total += k
                        total
                    ))
                    total
                )
                """);
    }

    // ---------------------------------------------------------------- 5. Higher-Order Specialization & Subtyping

    @Test
    public void testHigherOrderSpecializationSubtypingAndInterfaces() {
        // Concrete unannotated higher-order function specializes for pure vs impure callbacks:
        assertClean("""
                def apply = fn (f: fn (x: int): int, v: int): int -> (f(v))
                def caller = fn pure deterministic nothrow (x: int): int -> (
                    apply(fn (n: int): int -> (n * 2), x)
                )
                """);
        assertHasError("""
                def apply = fn (f: fn (x: int): int, v: int): int -> (f(v))
                def badCaller = fn pure (x: int): int -> (
                    apply(fn (n: int): int -> (println(n); n), x)
                )
                """, "pure");

        // `impure` functions and abstract interface methods do NOT specialize across call sites:
        assertHasError("""
                def apply = fn impure (f: fn (x: int): int, v: int): int -> (f(v))
                def badCaller = fn pure (x: int): int -> (
                    apply(fn (n: int): int -> (n + 1), x)
                )
                """, "pure");
        assertHasError("""
                def Runner = interface (
                    def apply: fn (f: fn (x: int): int, v: int): int
                )
                def badCaller = fn pure (r: Runner, x: int): int -> (
                    r.apply(fn (n: int): int -> (n + 1), x)
                )
                """, "pure");

        // Function subtyping (effects, `mut`, `borrow`) and interface conformance:
        assertClean("""
                def Box = class (def x: int) ()
                def pFn = fn pure deterministic nothrow (borrow b: Box): int -> (b.x)
                def asHermetic: fn hermetic (b: Box): int = pFn
                def asMutPermitted: fn hermetic (mut b: Box): int = pFn
                def asBorrow: fn pure (borrow b: Box): int = pFn
                """);
        assertHasError("""
                def noisy = fn (x: int): int -> (println(x); x)
                def needPure: fn pure (x: int): int = noisy
                """, "Type error : expected pure");
        assertHasError("""
                def Box = class (def x: int) ()
                def mutFn = fn hermetic (mut b: Box): int -> (b.x = 1; 1)
                def noMutAllowed: fn hermetic (b: Box): int = mutFn
                """, "Type error");
        assertHasError("""
                def Box = class (def x: int) ()
                def sink: Box? = null
                def escaping = fn (b: Box): int -> (sink = b; 1)
                def needBorrow: fn (borrow b: Box): int = escaping
                """, "Type error");

        // Interface conformance checks effects and `mut`:
        assertClean("""
                def Calc = interface (
                    def eval: fn pure deterministic (x: int): int
                )
                def DoubleCalc = class () implements Calc (
                    def eval = fn (x: int): int -> (x * 2)
                )
                """);
        assertHasError("""
                def Calc = interface (
                    def eval: fn pure (x: int): int
                )
                def NoisyCalc = class () implements Calc (
                    def eval = fn (x: int): int -> (println(x); x)
                )
                """, "Type error : expected pure");
    }

    // ---------------------------------------------------------------- 6. Algebraic Properties, Value Classes & Inline Objects

    @Test
    public void testAlgebraicPropertiesValueClassesAndInlineObjects() {
        // Wrapping 32-bit `int` addition is NOT `monotone`; `double` addition and non-negative scaling ARE `monotone`!
        assertHasError("""
                def badMono = fn pure deterministic monotone (x: int): int -> (x + 1)
                """, "monotone");
        assertClean("""
                def step1 = fn pure deterministic nothrow monotone (x: double): double -> (x * 2.0 + 3.0)
                def step2 = fn pure deterministic nothrow monotone (x: double): double -> (step1(x) + 1.0)
                """);
        assertHasError("""
                def antiMono = fn pure deterministic monotone (x: double): double -> (x * -2.0)
                """, "monotone");
        assertHasError("""
                def negMono = fn pure deterministic monotone (x: double): double -> (-x)
                """, "monotone");

        // `int * k` is `injective` for odd `k` (mod 2^32 bijection) and rejected for even `k`:
        assertClean("""
                def injOdd = fn pure deterministic nothrow injective (x: int): int -> (x * 3 + 1)
                def injStr = fn pure deterministic nothrow injective (x: int): string -> ("k=" + x)
                def Wrap = value class (x: int) ()
                def injWrap = fn pure deterministic nothrow injective (x: int): Wrap -> (Wrap(x))
                """);
        assertHasError("""
                def badEven = fn pure deterministic injective (x: int): int -> (x * 2)
                """, "injective");

        // Single-op wrappers for `commutative`, `associative`, `idempotent` & signature applicability:
        assertClean("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def add = fn pure deterministic nothrow commutative associative (a: int, b: int): int -> (a + b)
                def xor = fn pure deterministic nothrow commutative associative (a: int, b: int): int -> (HF.bitXor(a, b))
                def clamp = fn pure deterministic nothrow idempotent (x: double): double -> (HF.clampUnit(x))
                """);
        assertHasError("""
                def sub = fn pure deterministic commutative (a: int, b: int): int -> (a - b)
                """, "commutative");
        assertHasError("""
                def I = interface (def badSig: fn commutative (x: int): int)
                """, "commutative without mut requires a pure binary (T, T) : R signature");
        assertHasError("""
                def I = interface (def badAssoc: fn associative (a: int, b: int): bool)
                """, "associative requires a pure binary (T, T) : T signature");
        assertHasError("""
                def I = interface (def badIdem: fn idempotent (x: int): string)
                """, "idempotent without mut requires a pure unary (T) : T signature");
        assertHasError("""
                def Box = class (def x: int) ()
                def I = interface (def badOrder: fn monotone (b: Box): Box)
                """, "monotone requires ordered parameter and return types");

        // `value class` constructor must be `pure deterministic`:
        assertHasError("""
                def BadVal = value class (x: int) (
                    println(x)
                )
                """, "value class");

        // Inline `object (...)` inside a `pure` function executes its body under `pure` constraints:
        assertHasError("""
                def f = fn pure (): any -> (
                    object (
                        println("side effect")
                    )
                )
                """, "pure");
    }

    // ---------------------------------------------------------------- 7. Host Annotations, Engine Config & Priority Order

    @Test
    public void testJavaAnnotationsEngineConfigPriorityAndOrderIndependence() throws Exception {
        // BIP007-D4 : `axiom` is order-independent within a file (usable above its declaration)!
        assertClean("""
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = fn pure deterministic (): int -> (UH.compute(10))
                axiom pure deterministic on import("org.babblelang.tests").EffectsTestCase.UnassumedHelper.compute
                """);

        // Runtime @Pure, @Mut, @Borrow, @Impure, class-level @Pure/@Deterministic, and Java SAM callback propagation:
        assertClean("""
                def tests = import("org.babblelang.tests")
                def HF = tests.EffectsTestCase.HostFixtures
                def APC = tests.EffectsTestCase.AnnotatedPureClass
                def f = fn pure deterministic nothrow (x: int): int -> (
                    HF.pureCalc(x) + HF.applyOp(x, fn (v: int): int -> (v + 1))
                )
                def g = fn pure deterministic (x: int): int -> (APC.helper(x))
                def m = fn hermetic deterministic nothrow (mut dst: tests.EffectsTestCase.MutableHolder, borrow src: tests.EffectsTestCase.MutableHolder) -> (
                    HF.mutateParam(dst, src)
                    dst.bump(1)
                )
                """);
        // Class-level @Pure @Deterministic (without @Nothrow) does NOT imply nothrow!
        assertHasError("""
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def badNothrow = fn nothrow (x: int): int -> (APC.helper(x))
                """, "nothrow");

        // Method-level @Impure overrides class-level @Pure, and impure Java SAM callback taints caller:
        assertHasError("""
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def bad = fn pure (x: int): int -> (APC.escapeHatch(x))
                """, "pure");
        assertHasError("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def badSam = fn pure (x: int): int -> (
                    HF.applyOp(x, fn (v: int): int -> (println(v); v))
                )
                """, "pure");

        // Value protocol defaults: `Object.hashCode()` is `pure` only (NOT `deterministic`),
        // whereas overridden `MutableHolder.hashCode()` is `pure deterministic`!
        assertHasError("""
                def bad = fn deterministic (o: java.lang.StringBuilder): int -> (o.hashCode())
                """, "deterministic");
        assertClean("""
                def tests = import("org.babblelang.tests")
                def ok = fn pure deterministic (h: tests.EffectsTestCase.MutableHolder): int -> (h.hashCode())
                """);

        // Engine-level configuration: `assumeEffects`, `assumeConstructorEffects`, `markImpure`, and `allowAxioms(false)`:
        BabbleScriptEngine engine = newEngine();
        engine.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        engine.assumeConstructorEffects(UnassumedHelper.class, Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        Assertions.assertEquals(15, eval(engine, """
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = fn pure deterministic nothrow (x: int): int -> (
                    UH()
                    UH.compute(x)
                )
                f(10)
                """));

        // §6.1 Priority order:
        // - Tier 1 (file method `impure`) > Tier 3 (`@Pure` method annotation)
        // - Tier 2 (`engine.markImpure` method) > Tier 3 (`@Pure` method) & Tier 4 (bare-class `axiom pure`)
        // - Tier 1 (file method `axiom pure`) > Tier 2 (`engine.markImpure` method) when `allowAxioms(true)`
        assertEngineFails(engine, """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                impure on import("org.babblelang.tests").EffectsTestCase.HostFixtures.pureCalc
                def f = fn pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """, "pure");

        engine.markImpure(HostFixtures.class, "pureCalc");
        assertEngineFails(engine, """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                axiom pure on import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = fn pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """, "pure");
        Assertions.assertEquals(3, eval(engine, """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                axiom pure on import("org.babblelang.tests").EffectsTestCase.HostFixtures.pureCalc
                def f = fn pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """));

        // `allowAxioms(false)` disables Babble `axiom` and `impure` while keeping engine assumptions & @Pure annotations active!
        BabbleScriptEngine locked = newEngine();
        locked.allowAxioms(false);
        Assertions.assertFalse(locked.allowsAxioms());
        locked.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        Assertions.assertEquals(25, eval(locked, """
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = fn pure deterministic nothrow (x: int): int -> (UH.compute(x))
                f(20)
                """));
        assertEngineFails(locked, """
                axiom pure on java.lang.Math
                1
                """, "axiom declarations are disabled on this engine");
        assertEngineFails(locked, """
                impure on java.lang.System.nanoTime
                1
                """, "impure declarations are disabled on this engine");
    }
}
