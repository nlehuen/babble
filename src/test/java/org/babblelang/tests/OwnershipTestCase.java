package org.babblelang.tests;

import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.effects.Pure;
import org.babblelang.effects.Unique;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for RM007 (BIP007-I2) : deep ownership ({@code unique}, {@code move}, {@code freeze},
 * {@code frozen} / {@code Type.Frozen}, {@code @MOVED}, {@code @Unique}, {@code @TERMINATING},
 * unused {@code pure nothrow} diagnostics + {@code discard}, and {@code BIP007-D5}–{@code D9}).
 */
public class OwnershipTestCase {

    public static final class UniqueFactory {
        @Pure
        @Unique
        public static List<Integer> freshList() {
            return new ArrayList<>();
        }

        public static List<Integer> unannotatedList() {
            return new ArrayList<>();
        }
    }

    private record Analyzed(BabbleParser.FileContext file, ScopeAnalysis analysis) {}

    private static Analyzed analyzeFull(String source) {
        try {
            BabbleParser.FileContext file = BabbleScriptEngine.parseSource(source, "<input>");
            return new Analyzed(file, BlockScopes.analyze(file));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ScopeAnalysis analyze(String source) {
        return analyzeFull(source).analysis();
    }

    private static void assertClean(String source) {
        ScopeAnalysis analysis = analyze(source);
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics(),
                "Expected clean analysis for:\n" + source + "\nbut got: " + analysis.typeDiagnostics());
    }

    private static void assertHasError(String source, String expectedSubstring) {
        ScopeAnalysis analysis = analyze(source);
        boolean found = analysis.typeDiagnostics().stream()
                .anyMatch(w -> w.message().contains(expectedSubstring));
        Assertions.assertTrue(found,
                "Expected diagnostic containing '" + expectedSubstring + "' for:\n" + source
                        + "\nbut got: " + analysis.typeDiagnostics());
    }

    private static Object evalBothModes(String source) throws Exception {
        BabbleScriptEngineFactory factory = new BabbleScriptEngineFactory();
        BabbleScriptEngine interp = (BabbleScriptEngine) factory.getScriptEngine();
        Object rInterp = interp.eval(source);
        BabbleScriptEngine comp = (BabbleScriptEngine) factory.getScriptEngine();
        comp.put("babble.mode", "compile");
        Object rComp = comp.eval(source);
        Assertions.assertEquals(rInterp, rComp, "Interpret vs Compile parity mismatch for:\n" + source);
        return rInterp;
    }

    private static BabbleParser.FunctionLiteralContext findFn(BabbleParser.FileContext file, String name) {
        return findFnInTree(file, name);
    }

    private static BabbleParser.FunctionLiteralContext findFnInTree(org.antlr.v4.runtime.tree.ParseTree tree, String name) {
        if (tree instanceof BabbleParser.DefExpressionContext def
                && def.name != null && name.equals(def.name.getText())
                && def.value instanceof BabbleParser.FunctionLiteralContext fn) {
            return fn;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            BabbleParser.FunctionLiteralContext found = findFnInTree(tree.getChild(i), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    public void testFrozenNarrowingPreservesFrozenOnRecord() throws Exception {
        // User-requested mandatory negative case: narrowing `frozen any` via `is MyRecord` MUST NOT lose `frozen`!
        assertHasError("""
                def MyRecord = record (a: int) ()
                def a: frozen any = MyRecord(5)
                def b: MyRecord? = if a is MyRecord then ( a ) else ( null )
                """, "Type error : expected MyRecord?, got frozen MyRecord");

        // User-requested mandatory positive case: narrowing `frozen any` via `is MyRecord` produces `frozen MyRecord`!
        String ok = """
                def MyRecord = record (a: int) ()
                def a: frozen any = MyRecord(5)
                def c: frozen MyRecord? = if a is MyRecord then ( a ) else ( null )
                if c != null then ( c.a ) else ( 0 )
                """;
        assertClean(ok);
        Assertions.assertEquals(5, evalBothModes(ok));

        // Direct `frozen MyRecord` cannot unwrap back to plain `MyRecord` either!
        assertHasError("""
                def MyRecord = record (a: int) ()
                def a: frozen MyRecord = MyRecord(5)
                def b: MyRecord = a
                """, "Type error : expected MyRecord, got frozen MyRecord");

        // `is frozen T` is rejected statically (BIP007-D7)
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (x: any): bool -> ( x is frozen Box )
                """, "frozen");
    }

    @Test
    public void testFrozenCovarianceAndTransitiveImmutability() throws Exception {
        // Covariance on frozen generic Babble classes and core collections (BIP007-D7)
        String cov = """
                def Animal = interface ( def sound: fn (): string )
                def Dog = class (def name: string) implements Animal (
                    def sound = fn (): string -> ( "woof:" + this.name )
                )
                def Holder of T = class (def item: T) ()
                def makeFrozenDogs = fn (): frozen list of Dog -> (
                    def xs = list(Dog("rex"))
                    freeze(xs)
                )
                def dogs: frozen list of Dog = makeFrozenDogs()
                def animals: frozen list of Animal = dogs
                def hDog: frozen Holder of Dog = freeze(Holder(Dog("fido")))
                def hAnim: frozen Holder of Animal = hDog
                animals.get(0).sound() + "|" + hAnim.item.sound()
                """;
        assertClean(cov);
        Assertions.assertEquals("woof:rex|woof:fido", evalBothModes(cov));

        // Field write on frozen value is rejected
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (b: frozen Box) -> ( b.x = 2 )
                """, "frozen");

        // Transitive field read: field of frozen Outer is frozen Inner (cannot mutate inner.v!)
        assertHasError("""
                def Inner = class (var v: int) ()
                def Outer = class (def inner: Inner) ()
                def f = fn (o: frozen Outer) -> (
                    def i = o.inner
                    i.v = 9
                )
                """, "frozen");

        // Calling mut method on frozen value is rejected
        assertHasError("""
                def Box = class (var x: int) (
                    def update = fn mut (v: int) -> ( this.x = v )
                )
                def f = fn (b: frozen Box) -> ( b.update(3) )
                """, "Cannot call mut method on frozen value");
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (xs: frozen list of int) -> ( xs.add(1) )
                """, "Cannot call mut method add on frozen value");

        // Built-ins `same`, `classof`, `discard` accept `frozen` (BIP007-D9)
        String builtins = """
                def Box = class (def x: int) ()
                def f = fn (): bool -> (
                    def b = freeze(Box(1))
                    discard(classof(b))
                    discard(b)
                    same(b, b)
                )
                f()
                """;
        assertClean(builtins);
        Assertions.assertEquals(true, evalBothModes(builtins));
    }

    @Test
    public void testUniqueLocalsMoveFreezeAndDefiniteUnassignment() throws Exception {
        // Fresh local mutation inside pure function + inferred unique return + @TERMINATING (bip007 §5.2)
        String stats = """
                def Stats = class (var count: int = 0, var total: int = 0) (
                    def add = fn mut nothrow (v: int) -> (
                        this.count = this.count + 1
                        this.total = this.total + v
                    )
                )
                def buildStats = fn (xs: list of int) -> (
                    def s = Stats(0, 0)
                    s.add(xs.size())
                    s
                )
                def out = buildStats(list(10, 20, 30))
                out.count + out.total
                """;
        Analyzed analyzed = analyzeFull(stats);
        ScopeAnalysis analysis = analyzed.analysis();
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics());
        BabbleParser.FunctionLiteralContext fn = findFn(analyzed.file(), "buildStats");
        EffectSet eff = analysis.effects().callableEffects(fn);
        Assertions.assertTrue(eff.isPure(), "buildStats should infer pure");
        Assertions.assertTrue(eff.isDeterministic(), "buildStats should infer deterministic");
        Assertions.assertTrue(eff.isNothrow(), "buildStats should infer nothrow");
        Assertions.assertTrue(eff.uniqueResult(), "buildStats should infer uniqueResult");
        Assertions.assertTrue(eff.terminating(), "buildStats should infer @TERMINATING");
        Assertions.assertEquals(4, evalBothModes(stats));

        // Explicit `move(x)` + use-after-move rejected
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (): int -> (
                    def b = Box(1)
                    def c = move(b)
                    b.x + c.x
                )
                """, "Use of moved variable b");

        // `freeze(x)` consumes `x` -> use-after-freeze rejected
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (): int -> (
                    def b = Box(1)
                    def fb = freeze(b)
                    b.x + fb.x
                )
                """, "Use of moved variable b");

        // Move on one branch of `if` makes variable @MOVED after merge until reassigned!
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (flag: bool): int -> (
                    def b = Box(1)
                    if flag then (
                        def c = move(b)
                        discard(c)
                    )
                    b.x
                )
                """, "Use of moved variable b");

        // Reassigning after move restores usability!
        String reassign = """
                def Box = class (var x: int) ()
                def f = fn (flag: bool): int -> (
                    var b = Box(1)
                    var sum = 0
                    if flag then (
                        def c = move(b)
                        sum += c.x
                        b = Box(10)
                    )
                    b.x = b.x + sum
                    b.x
                )
                f(true)
                """;
        assertClean(reassign);
        Assertions.assertEquals(11, evalBothModes(reassign));

        // Aliasing `def alias = b` without `move` demotes both to @EXTERNAL
        assertHasError("""
                def Box = class (var x: int) ()
                def f = fn (): int -> (
                    def b = Box(1)
                    def alias = b
                    b.x = 2
                    alias.x
                )
                """, "Cannot mutate external object in pure function");

        // Freezing a non-unique parameter is rejected
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (b: Box): frozen Box -> ( freeze(b) )
                """, "Cannot freeze expression");
    }

    @Test
    public void testUniqueParamsReturnsCoreCollectionsAndJavaAnnotation() throws Exception {
        // Consuming `unique` param vs default `@BORROWED` param
        String uniqueParams = """
                def Box = class (var x: int) ()
                def consume = fn (unique b: Box): unique Box -> (
                    b.x = b.x + 1
                    b
                )
                def peek = fn (b: Box): int -> ( b.x )
                def run = fn (): int -> (
                    def b = Box(10)
                    def v = peek(b)
                    def b2 = consume(b)
                    v + b2.x
                )
                run()
                """;
        assertClean(uniqueParams);
        Assertions.assertEquals(21, evalBothModes(uniqueParams));

        // Consuming `unique` param moves caller's variable -> use after call rejected!
        assertHasError("""
                def Box = class (def x: int) ()
                def consume = fn (unique b: Box): int -> ( b.x )
                def run = fn (): int -> (
                    def b = Box(10)
                    def v = consume(b)
                    v + b.x
                )
                """, "Use of moved variable b");

        // `mut` param enforces exclusivity (aliasing two args rejected)
        assertHasError("""
                def Box = class (var x: int) ()
                def both = fn (mut a: Box, b: Box): int -> ( a.x = a.x + b.x; a.x )
                def run = fn (): int -> (
                    def b = Box(10)
                    both(b, b)
                )
                """, "Exclusivity error");

        // `: unique T` rejects returning non-unique param
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (b: Box): unique Box -> ( b )
                """, "declared unique return violates unique guarantee");

        // RM005 core collection `unique` constructors & mutators (BIP007-D6)
        String collections = """
                def Box = class (def x: int) ()
                def build = fn (): frozen list of Box -> (
                    def xs = list(Box(1))
                    def b = Box(2)
                    xs.add(move(b))
                    freeze(xs)
                )
                def copyInts = fn (src: list of int): frozen list of int -> (
                    def cp = list(from: src)
                    cp.add(99)
                    freeze(cp)
                )
                def bs = build()
                def ints = copyInts(list(1, 2))
                bs.size() * 10 + ints.size()
                """;
        assertClean(collections);
        Assertions.assertEquals(23, evalBothModes(collections));

        // `list(from: boxes)` where elements are mutable `Box` is NOT `unique` -> cannot freeze!
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (src: list of Box): frozen list of Box -> (
                    def cp = list(from: src)
                    freeze(cp)
                )
                """, "Cannot freeze expression");

        // Storing a shared param into a unique list demotes the list -> cannot freeze!
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (shared s: Box): frozen list of Box -> (
                    def xs = list(Box(1))
                    xs.add(s)
                    freeze(xs)
                )
                """, "Cannot freeze expression");

        // `@Unique` Java method annotation produces a `unique` list usable in pure `fn` & `freeze`!
        String javaUnique = """
                def UF = import("org.babblelang.tests").OwnershipTestCase.UniqueFactory
                def make = fn (): frozen java.util.List of int? -> (
                    def xs = UF.freshList()!
                    xs.add(42)
                    freeze(xs)
                )
                make().get(0)
                """;
        assertClean(javaUnique);
        Assertions.assertEquals(42, evalBothModes(javaUnique));

        // `axiom ...: fn pure unique` also produces a `unique` list!
        String axiomUnique = """
                def UF = import("org.babblelang.tests").OwnershipTestCase.UniqueFactory
                axiom org.babblelang.tests.OwnershipTestCase.UniqueFactory.unannotatedList: fn pure unique
                def make = fn (): frozen java.util.List of int? -> (
                    def xs = UF.unannotatedList()!
                    xs.add(7)
                    freeze(xs)
                )
                make().get(0)
                """;
        assertClean(axiomUnique);
        Assertions.assertEquals(7, evalBothModes(axiomUnique));
    }

    @Test
    public void testBip016FrozenSubtypingAndIoOrthogonality() throws Exception {
        // BIP016-D5 / §4.1: `frozen T <: @CONST @BORROWED T` — frozen values pass directly to default read-only borrowed params
        String frozenBorrow = """
                def Box = class (def x: int) ()
                def read = fn (b: Box): int -> ( b.x * 2 )
                def run = fn (): int -> (
                    def fb = freeze(Box(21))
                    read(fb)
                )
                run()
                """;
        assertClean(frozenBorrow);
        Assertions.assertEquals(42, evalBothModes(frozenBorrow));

        // `frozen T` CANNOT be passed to `shared`, `mut`, or `unique` parameters!
        assertHasError("""
                def Box = class (def x: int) ()
                def keep = fn (shared b: Box): Box -> ( b )
                def run = fn (): Box -> ( keep(freeze(Box(1))) )
                """, "Type error : expected Box, got frozen Box");
        assertHasError("""
                def Box = class (def x: int) ()
                def inc = fn (mut b: Box) -> ( b.x = b.x + 1 )
                def run = fn () -> ( inc(freeze(Box(1))) )
                """, "Type error : expected Box, got frozen Box");
        assertHasError("""
                def Box = class (def x: int) ()
                def take = fn (unique b: Box): int -> ( b.x )
                def run = fn (): int -> ( take(freeze(Box(1))) )
                """, "Type error : expected Box, got frozen Box");

        // BIP016-D5: `io` is orthogonal to `unique`, `@BORROWED`, and `frozen`
        String ioOrtho = """
                def Box = class (var x: int) ()
                def logAndFreeze = fn io (): frozen Box -> (
                    def b = Box(7)
                    println(b.x)
                    b.x = 8
                    def fb = freeze(b)
                    println(fb.x)
                    fb
                )
                logAndFreeze().x
                """;
        assertClean(ioOrtho);
        Assertions.assertEquals(8, evalBothModes(ioOrtho));

        // `io` still enforces `@BORROWED` store-escape on default parameters!
        assertHasError("""
                def Box = class (def x: int) ()
                var sink: Box? = null
                def bad = fn io (b: Box) -> ( println(b.x); sink = b )
                """, "Borrowed parameter b escapes");

        // `unique` is mutually exclusive with `shared` and `mut`
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (unique shared b: Box): int -> ( b.x )
                """, "mutually exclusive");
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (unique mut b: Box): int -> ( b.x )
                """, "mutually exclusive");
    }

    @Test
    public void testBip016D10ReturnThroughBorrowIdMinAndSoundness() throws Exception {
        // BIP016-D10: `id` and `min` require ZERO `shared` keywords and do NOT virally infect callers!
        String idAndMin = """
                def Box = class (var x: int) ()
                def id of T = fn (x: T): T -> ( x )
                def min of T = fn (a: T, b: T, cmp: fn (x: T, y: T): int): T -> (
                    if cmp(a, b) < 0 then ( a ) else ( b )
                )
                def cmpBox = fn (a: Box, b: Box): int -> ( a.x - b.x )
                def smallestX = fn (a: Box, b: Box): int -> (
                    min(id(a), id(b), cmpBox).x
                )
                def bumpSmallest = fn (mut a: Box, mut b: Box): int -> (
                    def m = min(a, b, cmpBox)
                    m.x = m.x + 10
                    m.x
                )
                def runFrozen = fn (): int -> (
                    def f1 = freeze(Box(7))
                    def f2 = freeze(Box(3))
                    def fm: frozen Box = min(id(f1), id(f2), cmpBox)
                    fm.x
                )
                def a = Box(5)
                def b = Box(2)
                smallestX(a, b) * 100 + bumpSmallest(a, b) * 10 + runFrozen()
                """;
        assertClean(idAndMin);
        Assertions.assertEquals(2 * 100 + 12 * 10 + 3, evalBothModes(idAndMin));

        // Soundness #1: mutating returned reference from borrowed params is rejected!
        assertHasError("""
                def Box = class (var x: int) ()
                def id of T = fn (x: T): T -> ( x )
                def bad = fn (b: Box) -> ( id(b).x = 99 )
                """, "Cannot mutate parameter b");

        // Soundness #2: storing returned reference from borrowed params into global/field is rejected!
        assertHasError("""
                def Box = class (def x: int) ()
                def id of T = fn (x: T): T -> ( x )
                var sink: Box? = null
                def bad = fn io (b: Box) -> ( sink = id(b) )
                """, "Borrowed parameter b escapes");

        // Soundness #3 (Hole 1): `def alias = id(u)` demotes `u` from @UNIQUE so `freeze(u)` is rejected!
        assertHasError("""
                def Box = class (var x: int) ()
                def id of T = fn (x: T): T -> ( x )
                def exploit = fn (): frozen Box -> (
                    def u = Box(1)
                    def alias = id(u)
                    def fu = freeze(u)
                    alias.x = 99
                    fu
                )
                """, "Cannot freeze expression");

        // Soundness #4 (Hole 1): `def alias = id(u); swap(u, alias)` is rejected by exclusivity!
        assertHasError("""
                def Box = class (var x: int) ()
                def id of T = fn (x: T): T -> ( x )
                def swap = fn (mut a: Box, mut b: Box) -> (
                    def t = a.x
                    a.x = b.x
                    b.x = t
                )
                def bad = fn () -> (
                    def u = Box(1)
                    def alias = id(u)
                    swap(u, alias)
                )
                """, "Exclusivity error");

        // Soundness #5 (Hole 2): `unwrap(fb)` and `id<Box>(fb)` CANNOT unfreeze `frozen Box` into mutable `Box`!
        assertHasError("""
                def Box = class (var x: int) ()
                def unwrap = fn (b: Box): Box -> ( b )
                def exploit = fn (): int -> (
                    def fb: frozen Box = freeze(Box(1))
                    def m: Box = unwrap(fb)
                    m.x = 99
                    fb.x
                )
                """, "Type error : expected Box, got frozen Box");
        assertHasError("""
                def Box = class (def x: int) ()
                def id of T = fn (x: T): T -> ( x )
                def exploit = fn (): Box -> (
                    def fb: frozen Box = freeze(Box(1))
                    id of Box (fb)
                )
                """, "Type error : expected Box, got frozen Box");
        assertHasError("""
                def Box = class (def x: int) ()
                def Mapper = interface ( def transform: fn (b: Box): Box )
                def bad = fn (m: Mapper, fb: frozen Box): Box -> ( m.transform(fb) )
                """, "Type error : expected Box, got frozen Box");

        // Positive counterpart: `clone` (inferred `uniqueResult`) and `: unique Box` interface ACCEPT `frozen Box`!
        String cloneFrozen = """
                def Box = class (def x: int) ()
                def clone = fn (b: Box): Box -> ( Box(b.x + 1) )
                def Cloner = interface ( def clone: fn (b: Box): unique Box )
                def BoxCloner = class () implements Cloner (
                    def clone = fn (b: Box): unique Box -> ( Box(b.x + 2) )
                )
                def useCloner = fn (c: Cloner, fb: frozen Box): Box -> ( c.clone(fb) )
                def fb = freeze(Box(10))
                clone(fb).x + useCloner(BoxCloner(), fb).x
                """;
        assertClean(cloneFrozen);
        Assertions.assertEquals(23, evalBothModes(cloneFrozen));
    }

    @Test
    public void testBip016D9StatelessObjectsAndLocalContainerPrecision() throws Exception {
        // BIP016-D9 Fix #1: Stateless object singletons are deeply immutable (no `volatile` needed)
        String stateless = """
                def Hit = interface ( def score: fn (): int )
                def EmptyHit = object implements Hit (
                    def score = fn (): int -> ( 0 )
                )
                def pick = fn (flag: bool): Hit -> (
                    if flag then ( EmptyHit ) else ( EmptyHit )
                )
                pick(true).score()
                """;
        assertClean(stateless);
        Assertions.assertEquals(0, evalBothModes(stateless));

        // Stateful object singleton (has a mutable field) still requires `volatile`!
        assertHasError("""
                def Stateful = object (
                    var count: int = 1
                )
                def bad = fn (): int -> ( Stateful.count )
                """, "volatile");

        // BIP016-D9 Fix #2 + D10: Pure local container holding borrowed elements stays pure & deterministic with 0 `mut`/`shared`!
        String localContainer = """
                def Box = class (def x: int) ()
                def Bag = class (
                    var first: Box? = null,
                    var count: int = 0
                ) (
                    def add = fn mut (shared b: Box) -> (
                        if this.first == null then ( this.first = b )
                        this.count = this.count + 1
                    )
                )
                def pickFirst = fn (a: Box, b: Box): Box -> (
                    def bag = Bag()
                    bag.add(a)
                    bag.add(b)
                    bag.first!
                )
                pickFirst(Box(9), Box(10)).x
                """;
        assertClean(localContainer);
        Assertions.assertEquals(9, evalBothModes(localContainer));

        // Soundness check #1: Local container that absorbed shared elements is NOT unique -> cannot freeze!
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (shared a: Box): frozen list of Box -> (
                    def xs = list(a)
                    freeze(xs)
                )
                """, "Cannot freeze expression");

        // Soundness check #2: Mutating an element read back from a local container is rejected in pure fn!
        assertHasError("""
                def Box = class (var x: int) ()
                def Bag = class (var first: Box? = null) (
                    def add = fn mut (shared b: Box) -> ( this.first = b )
                )
                def bad = fn (a: Box): int -> (
                    def bag = Bag()
                    bag.add(a)
                    bag.first!.x = 99
                    bag.first!.x
                )
                """, "Cannot mutate parameter a");

        // Soundness check #3: Calling a deep-mutating method (`deepMutThis`) on a local container holding `a` is rejected!
        assertHasError("""
                def Box = class (var x: int) ()
                def Holder = class (def inner: Box) (
                    def poke = fn mut () -> ( this.inner.x = 99 )
                )
                def bad = fn (a: Box): int -> (
                    def h = Holder(a)
                    h.poke()
                    a.x
                )
                """, "Cannot mutate parameter a");
    }

    @Test
    public void testTerminatingAndUnusedPureExpressions() throws Exception {
        Analyzed analyzed = analyzeFull("""
                def Node = record (v: int, next: Node?) ()
                def straight = fn (x: int): int -> ( x + 1 )
                def loopy = fn (n: int): int -> (
                    var i = 0
                    while i < n then ( i += 1 )
                    i
                )
                def rec = fn (n: int): int -> (
                    if n <= 0 then ( 0 ) else ( recurse(n - 1) )
                )
                def fib = fn (n: int): int -> (
                    if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) )
                )
                def gcdSub = fn (a: int, b: int): int -> (
                    if a <= 0 or b <= 0 then ( a + b )
                    else if a > b then ( gcdSub(a - b, b) )
                    else ( gcdSub(a, b - a) )
                )
                def log2 = fn (n: int): int -> (
                    if n <= 1 then ( 0 ) else ( 1 + log2(int(n / 2)) )
                )
                def len = fn (xs: Node?): int -> (
                    if xs == null then ( 0 ) else ( 1 + len(xs.next) )
                )
                def sumList = fn (xs: list of int): int -> (
                    var s = 0
                    for x in xs then ( s += x )
                    s
                )
                def mutFor = fn io (mut xs: list of int): int -> (
                    var s = 0
                    for x in xs then ( xs.add(x) ; s += x )
                    s
                )
                def evenGuarded = fn (n: int): bool -> ( if n <= 0 then ( true ) else ( oddGuarded(n - 1) ) )
                def oddGuarded = fn (n: int): bool -> ( if n <= 0 then ( false ) else ( evenGuarded(n - 1) ) )
                def even = fn (n: int): bool -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )
                def odd = fn (n: int): bool -> ( if n == 0 then ( false ) else ( even(n - 1) ) )
                def callsLoopy = fn (n: int): int -> ( loopy(n) )
                """);
        ScopeAnalysis analysis = analyzed.analysis();
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics());
        BabbleParser.FileContext file = analyzed.file();
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "straight")).terminating());
        Assertions.assertFalse(analysis.effects().callableEffects(findFn(file, "loopy")).terminating());
        // BIP027-I6 : polynomial SCP certifies guarded integer & acyclic record recursion and curated finite for-loops!
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "rec")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "fib")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "gcdSub")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "log2")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "len")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "sumList")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "evenGuarded")).terminating());
        Assertions.assertTrue(analysis.effects().callableEffects(findFn(file, "oddGuarded")).terminating());
        Assertions.assertFalse(analysis.effects().callableEffects(findFn(file, "mutFor")).terminating());
        // Unguarded `n == 0` diverges on negative `n`, so SCP stays conservatively false:
        Assertions.assertFalse(analysis.effects().callableEffects(findFn(file, "even")).terminating());
        Assertions.assertFalse(analysis.effects().callableEffects(findFn(file, "odd")).terminating());
        Assertions.assertFalse(analysis.effects().callableEffects(findFn(file, "callsLoopy")).terminating());

        // Unused pure nothrow @TERMINATING expression rejected; discard(...) accepted (BIP007-D8, BIP027-D8)
        assertHasError("""
                def f = fn (s: string): string -> (
                    s.trim()
                    s
                )
                """, "Unused pure expression");
        assertHasError("""
                def fib = fn (n: int): int -> ( if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) ) )
                def f = fn (n: int): int -> (
                    fib(n)
                    n
                )
                """, "Unused pure expression");
        assertHasError("""
                def sumList = fn (xs: list of int): int -> (
                    var s = 0
                    for x in xs then ( s += x )
                    s
                )
                def f = fn (xs: list of int): int -> (
                    sumList(xs)
                    0
                )
                """, "Unused pure expression");
        String withDiscard = """
                def f = fn nothrow (s: string): string -> (
                    discard(s.trim())
                    s
                )
                f("hi")
                """;
        assertClean(withDiscard);
        Assertions.assertEquals("hi", evalBothModes(withDiscard));

        // `assert` is NOT nothrow so it remains valid in statement position!
        assertClean("""
                def f = fn (x: int): int -> (
                    assert(x > 0)
                    x
                )
                """);
    }

    @Test
    public void testSpreadAndCallReturnOwnershipSoundness() throws Exception {
        // TO_LIST_COPY / TO_ARRAY spread of mutable elements must demote uniqueOwner on escape/return.
        assertHasError("""
                def MutA = class (var v: int) ( def inc = fn mut () -> ( this.v += 1 ) )
                def idM = fn (xs: list of MutA): list of MutA -> ( xs )
                def exploit2 = fn io (): frozen array of MutA -> (
                    def ma = array.of(MutA(10))
                    def l = idM(...ma)
                    def fma = freeze(ma)
                    l.get(0).inc()
                    fma
                )
                """, "Cannot freeze expression");
        assertHasError("""
                def MutA = class (var v: int) ( def inc = fn mut () -> ( this.v += 1 ) )
                var sink: (list of MutA)? = null
                def leak = fn io (shared xs: list of MutA) -> ( sink = xs )
                def exploit3 = fn io (): frozen array of MutA -> (
                    def ma = array.of(MutA(10))
                    leak(...ma)
                    freeze(ma)
                )
                """, "Cannot freeze expression");

        // Positive counterparts: deeply immutable copied spreads and union-split spreads still pass!
        String ok = """
                def idL = fn (xs: list of int): list of int -> ( xs )
                def takeU = fn (unique xs: list of int): int -> ( xs.set(0, 77); xs.get(0) )
                def Shape = interface ( def area: fn (): int )
                def Named = interface ( def name: fn (): string )
                def Box = class (v: int) implements Shape, Named (
                    def area = fn (): int -> ( v )
                    def name = fn (): string -> ( "box" )
                )
                def fSplit = overload (
                    fn (s: Shape, xs: list of int): int -> ( s.area() + xs.size() ),
                    fn (n: Named, unique xs: list of int): int -> ( n.name().length() + xs.size() ),
                    fn (sn: Shape & Named, unique xs: list of int): int -> ( sn.area() + sn.name().length() + xs.size() )
                )
                def fa: frozen array of int = freeze(array.of(10, 20))
                def run = fn (xs: int...): int -> (
                    def l = idL(...xs)
                    l.add(99)
                    def u: Shape | Named = Box(10)
                    l.size() * 100 + takeU(...fa) + fa.get(0) + fSplit(u, ...fa)
                )
                run(1, 2)
                """;
        assertClean(ok);
        Assertions.assertEquals(300 + 77 + 10 + 12, evalBothModes(ok));
    }
}
