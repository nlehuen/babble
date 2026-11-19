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
 * Tests for RM007 (BIP007-P2) : deep ownership ({@code unique}, {@code move}, {@code freeze},
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
                def Animal = interface ( def sound: fn borrow (): string )
                def Dog = class (def name: string) implements Animal (
                    def sound = fn pure borrow (): string -> ( "woof:" + this.name )
                )
                def Holder of T = class (def item: T) ()
                def makeFrozenDogs = fn pure (): frozen list of Dog -> (
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
                def Inner = class (def v: int) ()
                def Outer = class (def inner: Inner) ()
                def f = fn (o: frozen Outer) -> (
                    def i = o.inner
                    i.v = 9
                )
                """, "frozen");

        // Calling mut or non-borrow method on frozen value is rejected
        assertHasError("""
                def Box = class (def x: int) (
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
                def f = fn pure (): bool -> (
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
                def Stats = class (def count: int = 0, def total: int = 0) (
                    def add = fn hermetic mut deterministic nothrow (v: int) -> (
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
        EffectSet eff = analysis.callableEffects(fn);
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
                def Box = class (def x: int) ()
                def f = fn pure (flag: bool): int -> (
                    def b = Box(1)
                    def sum = 0
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
                def Box = class (def x: int) ()
                def f = fn pure (): int -> (
                    def b = Box(1)
                    def alias = b
                    b.x = 2
                    alias.x
                )
                """, "Cannot mutate object in pure function");

        // Freezing a non-unique parameter is rejected
        assertHasError("""
                def Box = class (def x: int) ()
                def f = fn (b: Box): frozen Box -> ( freeze(b) )
                """, "Cannot freeze expression");
    }

    @Test
    public void testUniqueParamsReturnsCoreCollectionsAndJavaAnnotation() throws Exception {
        // Consuming `unique` param vs `unique borrow` param
        String uniqueParams = """
                def Box = class (def x: int) ()
                def consume = fn pure (unique b: Box): unique Box -> (
                    b.x = b.x + 1
                    b
                )
                def peek = fn pure (unique borrow b: Box): int -> ( b.x )
                def run = fn pure (): int -> (
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
                def consume = fn pure (unique b: Box): int -> ( b.x )
                def run = fn pure (): int -> (
                    def b = Box(10)
                    def v = consume(b)
                    v + b.x
                )
                """, "Use of moved variable b");

        // `unique borrow` enforces exclusivity (aliasing two args rejected)
        assertHasError("""
                def Box = class (def x: int) ()
                def both = fn pure (unique borrow a: Box, borrow b: Box): int -> ( a.x + b.x )
                def run = fn pure (): int -> (
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
                def build = fn pure (): frozen list of Box -> (
                    def xs = list(Box(1))
                    def b = Box(2)
                    xs.add(move(b))
                    freeze(xs)
                )
                def copyInts = fn pure (borrow src: list of int): frozen list of int -> (
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
                def bad = fn (borrow src: list of Box): frozen list of Box -> (
                    def cp = list(from: src)
                    freeze(cp)
                )
                """, "Cannot freeze expression");

        // Storing a shared param into a unique list demotes the list -> cannot freeze!
        assertHasError("""
                def Box = class (def x: int) ()
                def bad = fn (shared: Box): frozen list of Box -> (
                    def xs = list(Box(1))
                    xs.add(shared)
                    freeze(xs)
                )
                """, "Cannot freeze expression");

        // `@Unique` Java method annotation produces a `unique` list usable in `pure` & `freeze`!
        String javaUnique = """
                def UF = import("org.babblelang.tests").OwnershipTestCase.UniqueFactory
                def make = fn pure (): frozen java.util.List of int? -> (
                    def xs = UF.freshList()!
                    xs.add(42)
                    freeze(xs)
                )
                make().get(0)
                """;
        assertClean(javaUnique);
        Assertions.assertEquals(42, evalBothModes(javaUnique));

        // `axiom unique on ...` also produces a `unique` list!
        String axiomUnique = """
                def UF = import("org.babblelang.tests").OwnershipTestCase.UniqueFactory
                axiom pure deterministic unique on UF.unannotatedList
                def make = fn pure (): frozen java.util.List of int? -> (
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
    public void testTerminatingAndUnusedPureExpressions() throws Exception {
        Analyzed analyzed = analyzeFull("""
                def straight = fn (x: int): int -> ( x + 1 )
                def loopy = fn (n: int): int -> (
                    def i = 0
                    while i < n then ( i += 1 )
                    i
                )
                def rec = fn (n: int): int -> (
                    if n <= 0 then ( 0 ) else ( recurse(n - 1) )
                )
                def even = fn (n: int): bool -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )
                def odd = fn (n: int): bool -> ( if n == 0 then ( false ) else ( even(n - 1) ) )
                def callsLoopy = fn (n: int): int -> ( loopy(n) )
                """);
        ScopeAnalysis analysis = analyzed.analysis();
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics());
        BabbleParser.FileContext file = analyzed.file();
        Assertions.assertTrue(analysis.callableEffects(findFn(file, "straight")).terminating());
        Assertions.assertFalse(analysis.callableEffects(findFn(file, "loopy")).terminating());
        Assertions.assertFalse(analysis.callableEffects(findFn(file, "rec")).terminating());
        Assertions.assertFalse(analysis.callableEffects(findFn(file, "even")).terminating());
        Assertions.assertFalse(analysis.callableEffects(findFn(file, "odd")).terminating());
        Assertions.assertFalse(analysis.callableEffects(findFn(file, "callsLoopy")).terminating());

        // Unused pure nothrow expression rejected; discard(...) accepted (BIP007-D8)
        assertHasError("""
                def f = fn (s: string): string -> (
                    s.trim()
                    s
                )
                """, "Unused pure expression");
        String withDiscard = """
                def f = fn pure deterministic nothrow (s: string): string -> (
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
}
