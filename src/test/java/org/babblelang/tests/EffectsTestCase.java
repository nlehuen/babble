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
                def inc = (x: int): int -> (x + 1)
                def f: pure deterministic nothrow (x: int): int = inc
                """);

        // Mutually recursive pure functions converge to pure deterministic nothrow:
        assertClean("""
                def even = (n: int): bool -> (if n == 0 then (true) else (odd(n - 1)))
                def odd = (n: int): bool -> (if n == 0 then (false) else (even(n - 1)))
                def check: pure deterministic nothrow (n: int): bool = even
                """);

        // Mutual recursion where one branch prints taints the entire SCC:
        assertHasError("""
                def a = (n: int): int -> (if n <= 0 then (0) else (b(n - 1)))
                def b = (n: int): int -> (println(n); a(n - 1))
                def bad: pure (n: int): int = a
                """, "Type error : expected pure");

        // Untyped function automatically infers `mut` on mutated parameters; typed function requires explicit `mut`!
        assertClean("""
                def Box = class (def x: int) ()
                def untypedSet = (b) -> (b.x = 1)
                def caller = (mut b: Box) -> (untypedSet(b))
                caller(Box(0))
                """);
        assertHasError("""
                def Box = class (def x: int) ()
                def untypedSet = (b) -> (b.x = 1)
                def badCaller = (b: Box) -> (untypedSet(b))
                badCaller(Box(0))
                """, "Cannot mutate parameter b");
        assertHasError("""
                def Box = class (def x: int) ()
                def typedSet = (b: Box) -> (b.x = 1)
                """, "Cannot mutate parameter b");
        assertHasError("""
                def Box = class (def x: int) ()
                def retTypedSet = (b): int -> (b.x = 1; 1)
                retTypedSet(Box(0))
                """, "Cannot mutate parameter b");
    }

    // ---------------------------------------------------------------- 2. Verified Constraints

    @Test
    public void testVerifiedConstraintsAndMutExclusivity() {
        // pure + mut mutual exclusion on function literals and function types:
        assertHasError("""
                def Box = class (def x: int) ()
                def f = pure (mut b: Box): int -> (b.x = 1; 1)
                """, "pure and mut are mutually exclusive");
        assertHasError("""
                def Box = class (def x: int) ()
                def f: pure (mut b: Box): int
                """, "pure and mut are mutually exclusive");
        assertHasError("""
                def C = class (def x: int) (
                    def f = pure mut (): int -> (this.x = 1; 1)
                )
                """, "pure and mut are mutually exclusive");

        // `pure` allows local `def` reassignments, while rejecting I/O, outer writes, and (in RM002) fresh-local object mutation:
        assertClean("""
                def sumTo = pure deterministic nothrow (n: int): int -> (
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
                def f = pure (x: int): int -> (println(x); x)
                """, "pure");
        assertHasError("""
                def g: int = 0
                def f = hermetic (x: int): int -> (g = x; x)
                """, "hermetic");
        assertHasError("""
                def Box = class (def x: int) ()
                def f = pure (): int -> (
                    def b = Box(1)
                    b.x = 2
                    b.x
                )
                """, "Cannot mutate object in pure function");

        // `deterministic` checks: reassigned outer def, mutable outer object, non-final Java static field, identity toString:
        assertHasError("""
                def g: int = 1
                g = 2
                def f = deterministic (x: int): int -> (x + g)
                """, "deterministic");
        assertHasError("""
                def Box = class (def x: int) ()
                def g = Box(1)
                def f = deterministic (): int -> (g.x)
                """, "deterministic");
        assertHasError("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = deterministic (): int -> (HF.mutableStatic)
                """, "deterministic");
        assertClean("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = deterministic (): int -> (HF.FINAL_STATIC)
                """);
        assertHasError("""
                def Box = class (def x: int) ()
                def f = deterministic (b: Box): string -> ("v=" + b)
                """, "deterministic");
        assertClean("""
                def Pt = value class (x: int, y: int) ()
                def f = pure deterministic nothrow (p: Pt): string -> ("p=" + p)
                """);

        // `nothrow` rejects `assert` and non-redundant `as` casts:
        assertHasError("""
                def f = nothrow (x: int): int -> (assert(x > 0); x)
                """, "nothrow");
        assertHasError("""
                def f = nothrow (x: any): int -> (x as int)
                """, "nothrow");

        // Constrained verified functions reject nested `axiom` / `impure` declarations:
        assertHasError("""
                def f = pure (): int -> (
                    axiom pure java.lang.System.nanoTime
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
                def f = (p: Box): int -> (
                    def alias = p
                    alias.x = 2
                    alias.x
                )
                """, "Cannot mutate parameter p");
        assertHasError("""
                def Leaf = class (def x: int) ()
                def Node = class (def child: Leaf) ()
                def f = (n: Node): int -> (
                    n.child.x = 9
                    n.child.x
                )
                """, "Cannot mutate parameter n");
        assertHasError("""
                def C = class (def x: int) (
                    def sneak = (): int -> (
                        def self = this
                        self.x = 5
                        self.x
                    )
                )
                """, "Cannot mutate this");
        assertHasError("""
                def Box = class (def x: int) ()
                def f = (c: bool, mut a: Box, b: Box): int -> (
                    def pick = if c then (a) else (b)
                    pick.x = 1
                    pick.x
                )
                """, "Cannot mutate parameter b");

        // `borrow` escape checks (return, field store, outer store, escaping closure, non-borrow callee):
        assertHasError("""
                def Box = class (def x: int) ()
                def escReturn = (borrow b: Box): Box -> (b)
                """, "borrow parameter b escapes via return value");
        assertHasError("""
                def Box = class (def x: int) ()
                def sink: Box? = null
                def escGlobal = (borrow b: Box): int -> (sink = b; 1)
                """, "borrow parameter b escapes");
        assertHasError("""
                def Box = class (def x: int) ()
                def Holder = class (def ref: Box?) ()
                def escField = (mut h: Holder, borrow b: Box): int -> (h.ref = b; 1)
                """, "borrow parameter b escapes via field .ref");
        assertHasError("""
                def Box = class (def x: int) ()
                def escClosure = (borrow b: Box): (): Box -> (() -> (b))
                """, "borrow parameter b escapes via captured closure");

        // Returning deeply immutable primitive or value-class field from `borrow` parameter IS allowed!
        assertClean("""
                def Pt = value class (x: int, y: int) ()
                def Holder = class (def pt: Pt) ()
                def readPt = pure deterministic nothrow (borrow h: Holder): Pt -> (h.pt)
                """);

        // Static call-site `mut` exclusivity:
        assertHasError("""
                def Box = class (def x: int) ()
                def swap = hermetic (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def b = Box(1)
                swap(b, b)
                """, "Exclusivity error");
        assertHasError("""
                def Box = class (def x: int) ()
                def rw = hermetic (mut a: Box, b: Box): int -> (a.x = b.x + 1; a.x)
                def x = Box(1)
                def y = x
                rw(x, y)
                """, "Exclusivity error");
        assertHasError("""
                def Box = class (def x: int) (
                    def addFrom = mut (other: Box) -> (this.x += other.x)
                )
                def Wrap = class (def b: Box) ()
                def w = Wrap(Box(3))
                w.b.addFrom(w.b)
                """, "Exclusivity error");

        // Dynamic indexed expressions (`list.get(i)`, `list.get(j)`) and `string` arguments are permitted in RM002:
        assertClean("""
                def Box = class (def x: int) ()
                def swap = hermetic (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def list: java.util.List(Box) = java.util.ArrayList()
                list.add(Box(1))
                list.add(Box(2))
                def a = list.get(0)
                def b = list.get(1)
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
                def f1 = pure deterministic nothrow (x: int?): int -> (
                    if x != null then (x as int) else (0)
                )
                def f2 = pure deterministic nothrow (x: int?): int -> (
                    if x == null then (0) else (x as int)
                )
                def f3 = pure deterministic nothrow (x: int | string): int -> (
                    if x is int then (x as int) else (0)
                )
                def f4 = pure deterministic nothrow (x: int?): bool -> (
                    x != null and (x as int) > 0
                )
                def f5 = pure deterministic nothrow (x: int?): bool -> (
                    x == null or (x as int) > 0
                )
                """);

        // Un-narrowed nullable cast still breaks `nothrow`:
        assertHasError("""
                def bad = nothrow (x: int?): int -> (x as int)
                """, "nothrow");

        // Frame-local (`borrow`) helper closure updating enclosing function's local `def` preserves `pure deterministic nothrow`:
        assertClean("""
                def repeat = (n: int, borrow step: (i: int): int): int -> (
                    def i: int = 0
                    while i < n then (
                        step(i)
                        i += 1
                    )
                    n
                )
                def triangle = pure deterministic nothrow (n: int): int -> (
                    def total: int = 0
                    repeat(n, (k: int): int -> (
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
                def apply = (f: (x: int): int, v: int): int -> (f(v))
                def caller = pure deterministic nothrow (x: int): int -> (
                    apply((n: int): int -> (n * 2), x)
                )
                """);
        assertHasError("""
                def apply = (f: (x: int): int, v: int): int -> (f(v))
                def badCaller = pure (x: int): int -> (
                    apply((n: int): int -> (println(n); n), x)
                )
                """, "pure");

        // `impure` functions and abstract interface methods do NOT specialize across call sites:
        assertHasError("""
                def apply = impure (f: (x: int): int, v: int): int -> (f(v))
                def badCaller = pure (x: int): int -> (
                    apply((n: int): int -> (n + 1), x)
                )
                """, "pure");
        assertHasError("""
                def Runner = interface (
                    def apply: (f: (x: int): int, v: int): int
                )
                def badCaller = pure (r: Runner, x: int): int -> (
                    r.apply((n: int): int -> (n + 1), x)
                )
                """, "pure");

        // Function subtyping (effects, `mut`, `borrow`) and interface conformance:
        assertClean("""
                def Box = class (def x: int) ()
                def pFn = pure deterministic nothrow (borrow b: Box): int -> (b.x)
                def asHermetic: hermetic (b: Box): int = pFn
                def asMutPermitted: hermetic (mut b: Box): int = pFn
                def asBorrow: pure (borrow b: Box): int = pFn
                """);
        assertHasError("""
                def noisy = (x: int): int -> (println(x); x)
                def needPure: pure (x: int): int = noisy
                """, "Type error : expected pure");
        assertHasError("""
                def Box = class (def x: int) ()
                def mutFn = hermetic (mut b: Box): int -> (b.x = 1; 1)
                def noMutAllowed: hermetic (b: Box): int = mutFn
                """, "Type error");
        assertHasError("""
                def Box = class (def x: int) ()
                def sink: Box? = null
                def escaping = (b: Box): int -> (sink = b; 1)
                def needBorrow: (borrow b: Box): int = escaping
                """, "Type error");

        // Interface conformance checks effects and `mut`:
        assertClean("""
                def Calc = interface (
                    def eval: pure deterministic (x: int): int
                )
                def DoubleCalc = class () implements Calc (
                    def eval = (x: int): int -> (x * 2)
                )
                """);
        assertHasError("""
                def Calc = interface (
                    def eval: pure (x: int): int
                )
                def NoisyCalc = class () implements Calc (
                    def eval = (x: int): int -> (println(x); x)
                )
                """, "Type error : expected pure");
    }

    // ---------------------------------------------------------------- 6. Algebraic Properties, Value Classes & Inline Objects

    @Test
    public void testAlgebraicPropertiesValueClassesAndInlineObjects() {
        // Wrapping 32-bit `int` addition is NOT `monotone`; `double` addition and non-negative scaling ARE `monotone`!
        assertHasError("""
                def badMono = pure deterministic monotone (x: int): int -> (x + 1)
                """, "monotone");
        assertClean("""
                def step1 = pure deterministic nothrow monotone (x: double): double -> (x * 2.0 + 3.0)
                def step2 = pure deterministic nothrow monotone (x: double): double -> (step1(x) + 1.0)
                """);
        assertHasError("""
                def antiMono = pure deterministic monotone (x: double): double -> (x * -2.0)
                """, "monotone");
        assertHasError("""
                def negMono = pure deterministic monotone (x: double): double -> (-x)
                """, "monotone");

        // `int * k` is `injective` for odd `k` (mod 2^32 bijection) and rejected for even `k`:
        assertClean("""
                def injOdd = pure deterministic nothrow injective (x: int): int -> (x * 3 + 1)
                def injStr = pure deterministic nothrow injective (x: int): string -> ("k=" + x)
                def Wrap = value class (x: int) ()
                def injWrap = pure deterministic nothrow injective (x: int): Wrap -> (Wrap(x))
                """);
        assertHasError("""
                def badEven = pure deterministic injective (x: int): int -> (x * 2)
                """, "injective");

        // Single-op wrappers for `commutative`, `associative`, `idempotent` & signature applicability:
        assertClean("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def add = pure deterministic nothrow commutative associative (a: int, b: int): int -> (a + b)
                def xor = pure deterministic nothrow commutative associative (a: int, b: int): int -> (HF.bitXor(a, b))
                def clamp = pure deterministic nothrow idempotent (x: double): double -> (HF.clampUnit(x))
                """);
        assertHasError("""
                def sub = pure deterministic commutative (a: int, b: int): int -> (a - b)
                """, "commutative");
        assertHasError("""
                def I = interface (def badSig: commutative (x: int): int)
                """, "commutative without mut requires a pure binary (T, T) : R signature");
        assertHasError("""
                def I = interface (def badAssoc: associative (a: int, b: int): bool)
                """, "associative requires a pure binary (T, T) : T signature");
        assertHasError("""
                def I = interface (def badIdem: idempotent (x: int): string)
                """, "idempotent without mut requires a pure unary (T) : T signature");
        assertHasError("""
                def Box = class (def x: int) ()
                def I = interface (def badOrder: monotone (b: Box): Box)
                """, "monotone requires ordered parameter and return types");

        // `value class` constructor must be `pure deterministic`:
        assertHasError("""
                def BadVal = value class (x: int) (
                    println(x)
                )
                """, "value class");

        // Inline `object (...)` inside a `pure` function executes its body under `pure` constraints:
        assertHasError("""
                def f = pure (): any -> (
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
                def f = pure deterministic (): int -> (UH.compute(10))
                axiom pure deterministic import("org.babblelang.tests").EffectsTestCase.UnassumedHelper.compute
                """);

        // Runtime @Pure, @Mut, @Borrow, @Impure, class-level @Pure/@Deterministic, and Java SAM callback propagation:
        assertClean("""
                def tests = import("org.babblelang.tests")
                def HF = tests.EffectsTestCase.HostFixtures
                def APC = tests.EffectsTestCase.AnnotatedPureClass
                def f = pure deterministic nothrow (x: int): int -> (
                    HF.pureCalc(x) + HF.applyOp(x, (v: int): int -> (v + 1))
                )
                def g = pure deterministic (x: int): int -> (APC.helper(x))
                def m = hermetic deterministic nothrow (mut dst: tests.EffectsTestCase.MutableHolder, borrow src: tests.EffectsTestCase.MutableHolder) -> (
                    HF.mutateParam(dst, src)
                    dst.bump(1)
                )
                """);
        // Class-level @Pure @Deterministic (without @Nothrow) does NOT imply nothrow!
        assertHasError("""
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def badNothrow = nothrow (x: int): int -> (APC.helper(x))
                """, "nothrow");

        // Method-level @Impure overrides class-level @Pure, and impure Java SAM callback taints caller:
        assertHasError("""
                def APC = import("org.babblelang.tests").EffectsTestCase.AnnotatedPureClass
                def bad = pure (x: int): int -> (APC.escapeHatch(x))
                """, "pure");
        assertHasError("""
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def badSam = pure (x: int): int -> (
                    HF.applyOp(x, (v: int): int -> (println(v); v))
                )
                """, "pure");

        // Value protocol defaults: `Object.hashCode()` is `pure` only (NOT `deterministic`),
        // whereas overridden `MutableHolder.hashCode()` is `pure deterministic`!
        assertHasError("""
                def bad = deterministic (o: java.lang.StringBuilder): int -> (o.hashCode())
                """, "deterministic");
        assertClean("""
                def tests = import("org.babblelang.tests")
                def ok = pure deterministic (h: tests.EffectsTestCase.MutableHolder): int -> (h.hashCode())
                """);

        // Engine-level configuration: `assumeEffects`, `assumeConstructorEffects`, `markImpure`, and `allowAxioms(false)`:
        BabbleScriptEngine engine = newEngine();
        engine.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        engine.assumeConstructorEffects(UnassumedHelper.class, Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        Assertions.assertEquals(15, eval(engine, """
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = pure deterministic nothrow (x: int): int -> (
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
                impure import("org.babblelang.tests").EffectsTestCase.HostFixtures.pureCalc
                def f = pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """, "pure");

        engine.markImpure(HostFixtures.class, "pureCalc");
        assertEngineFails(engine, """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                axiom pure import("org.babblelang.tests").EffectsTestCase.HostFixtures
                def f = pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """, "pure");
        Assertions.assertEquals(3, eval(engine, """
                def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
                axiom pure import("org.babblelang.tests").EffectsTestCase.HostFixtures.pureCalc
                def f = pure (x: int): int -> (HF.pureCalc(x))
                f(1)
                """));

        // `allowAxioms(false)` disables Babble `axiom` and `impure` while keeping engine assumptions & @Pure annotations active!
        BabbleScriptEngine locked = newEngine();
        locked.allowAxioms(false);
        Assertions.assertFalse(locked.allowsAxioms());
        locked.assumeEffects(UnassumedHelper.class, "compute", Effect.PURE, Effect.DETERMINISTIC, Effect.NOTHROW);
        Assertions.assertEquals(25, eval(locked, """
                def UH = import("org.babblelang.tests").EffectsTestCase.UnassumedHelper
                def f = pure deterministic nothrow (x: int): int -> (UH.compute(x))
                f(20)
                """));
        assertEngineFails(locked, """
                axiom pure java.lang.Math
                1
                """, "axiom declarations are disabled on this engine");
        assertEngineFails(locked, """
                impure java.lang.System.nanoTime
                1
                """, "impure declarations are disabled on this engine");
    }
}
