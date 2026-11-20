# BIP019: Refactoring Babble axioms

| | |
|---|---|
| **BIP** | 019 |
| **Date** | 2026-11-20 |
| **Status** | Decided (`BIP019-D1`–`D6`, `BIP019-Q1`–`Q7` → `BIP019-D7`–`D13`, and `BIP019-D14`–`D17` decided; `BIP019-P1`–`P3` implemented, `P4`–`P6` not started) |
| **Depends on** | function effects and `axiom` declarations ([bip007_effects.md](bip007_effects.md)), core types and the `java.base` nullness overlay ([bip011_core-types.md](bip011_core-types.md)), the HIR optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)) |
| **Scope** | Every assumption the interpreter, the compiler, the analysis and the optimizer make about specific Java or Babble functions and methods — effects, nullness, deep immutability, optimizer whitelists, built-in signatures — moves to one directory, `src/main/java/org/babblelang/axioms/`, as `.java` and `.ba` files ; one bytecode analyzer derives axioms from seeds, either while compiling or offline into the same directory |

---

## 1. Problem Statement

The analysis trusts facts about functions it cannot see into : that `String.length()` is pure, that `list.add(x)` only changes the list, that `Map.get` may return `null`, that `print` performs I/O. Trusting them is unavoidable ; what is wrong today is that they are **invisible**. They are private constants and special cases spread over six files, mixed with the mechanisms that use them :

| Where | What it assumes |
|---|---|
| `EffectChecker.standardProtocolDefault` (tier 6 of `resolveJavaMethodProfile`), `STRING_NOTHROW_METHODS`, `COLLECTION_READ_METHODS`, `COLLECTION_VIEW_METHODS`, `COLLECTION_MUT_METHODS`, `COLLECTION_MUT_BORROW_ARG_METHODS`, `isCoreCollectionClass` | `String` methods pure (a subset `nothrow`) ; `Map.Entry.getKey`/`getValue` pure, `setValue` mutating ; collection read, view and mutating methods, matched **by name** on any implementation of `Collection`, `Map`, `Iterable` or `Iterator` ; `equals`, `compareTo`, `hashCode` and `toString` pure on **any** class |
| `EffectChecker.resolveJavaConstructorProfile` (tier 6), the shallow-mutator rule in `applyJavaCall`, `evalFor` | `String` constructors pure, collection and entry constructors hermetic ; which mutators leave the elements alone ("shallow") ; iterating a core collection has no effect of its own |
| `BytecodeEffectAnalyzer.SEEDED_INTRINSICS` | about 160 methods of `Math`, `StrictMath`, `Double`, `Float`, `Integer`, `Long` and `String` pure |
| `JavaNullability` (`isOverlayNullMarked`, `overlayReturnNullness`, `overlayParameterNullness`, ~300 lines) | the nullness of `java.base` : what is null-marked, `Map.get` nullable, iterators non-null… |
| `TypeChecker.isDeeplyImmutable` | `Map.Entry` is deeply immutable |
| `opt/HirPurity` (`PURE_NOTHROW_TERMINATING_MATH`, `IDEMPOTENT_MATH`, `PURE_NOTHROW_TERMINATING_STRING`, `IDEMPOTENT_STRING`) | `Math` and `String` methods pure, `nothrow`, terminating, idempotent — and `ConstFoldPass` **invokes** them at compile time |
| `OperatorTypes` (`PURE_DET`, `*_CONVERSION`, `ASSERT_FUNCTION`, `SAME_FUNCTION`, … `implicitType`) | the types and effects of Babble's built-ins : `int(x)` and the other conversions, `assert`, `same`, `classof`, `print`… |

None of them is wrong for being an assumption. Some are wrong as stated, and nobody could tell. For instance, `equals` and `compareTo` are assumed pure on every class, so a sorted set's `add` counts as "shallow" — yet the set calls the elements' `compareTo`, which a Babble class may write with a `mut` parameter :

```babble
def Evil = class (def v: int) implements java.lang.Comparable of Evil (
    def compareTo = fn (mut other: Evil): int -> (other.v += 100; this.v - other.v)
)
def pureLooking = fn (shared e: Evil): int -> (
    def local: sortedset of Evil = sortedset()
    local.add(e)          ;; "shallow", so pure... but the set calls e.compareTo(…)
    local.add(Evil(5))
    e.v
)
def victim = Evil(1)
println(victim.v, " ", pureLooking(victim), " ", victim.v)    ;; 1 201 201
```

`pureLooking` carries no effect keyword and borrows `e`, yet mutates it. The hole is not in the effect checker's logic but in an assumption buried in it. This BIP makes every such assumption a line in a file one can read, review and change.

---

## 2. Design

### 2.1 One directory (`BIP019-D1`, `D2`)

```
src/main/java/org/babblelang/axioms/
  package-info.java, README.md     the format, the index of the files, the QUESTIONABLE list
  builtins.ba                      Babble's built-ins (the prelude)
  java/lang/                       axioms about the classes of java.lang : one stub per target class
    ObjectAxioms.java  ComparableAxioms.java  StringAxioms.java  MathAxioms.java  StrictMathAxioms.java
    DoubleAxioms.java  FloatAxioms.java  IntegerAxioms.java  LongAxioms.java  IterableAxioms.java
  java/util/                       axioms about the classes of java.util
    CollectionAxioms.java  ListAxioms.java  SetAxioms.java  SortedSetAxioms.java  MapAxioms.java
    SortedMapAxioms.java  MapEntryAxioms.java  IteratorAxioms.java  ArrayListAxioms.java  …
  java/util/function/ …            and so on, for whatever the nullness overlay covers
```

* **Nowhere else.** No list of assumptions about a Java or Babble function or method remains in the interpreter, the compiler, the analysis or the optimizer. Quantity and elegance don't matter ; being explicit and discoverable does. Later work prunes the axioms, through more expressive forms or smarter inference (§3).
* **One rule to find an axiom :** the stub for `java.util.List` is `org.babblelang.axioms.java.util.ListAxioms` ; a nested class takes its outer class's name (`Map.Entry` → `MapEntryAxioms`). Derived axioms (§3) sit next to it, as `ListDerivedAxioms`.
* **The build :** the `.java` stubs compile with the front end, since the directory is a source root ; the package is in the full jar, not in the runtime jar (the runtime boundary is unchanged). A resource entry in `pom.xml` copies the `.ba` files of `src/main/java` next to the classes. The loader lists the package on the class path, in a directory or in a jar : no hand-written index, and no build step (`BIP019-D7`).

### 2.2 Java axioms : stubs

A stub mirrors the signatures of its target and annotates each with what the analysis assumes about it. The collection stubs below were generated once from JDK 21's classes, so that they reproduce the former name lists exactly, overload by overload ; they are maintained by hand from then on :

```java
package org.babblelang.axioms.java.util;

/** QUESTIONABLE : they hold for every implementation, not only Babble's core collections (§2.6). */
@Axioms(value = java.util.Collection.class,
        allConstructors = @Profile(value = {HERMETIC, DETERMINISTIC}, receiver = Receiver.SHARED, terminating = false))
interface CollectionAxioms {
    @ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
            receiver = Receiver.SHARED)
    boolean add(java.lang.Object p0);

    @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    int size();

    @View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true)
    java.util.Iterator iterator();
    …                                  // every overload, with its exact signature
}
```

The stubs' own annotations live in `org.babblelang.axioms`, next to the stubs, not in the public `org.babblelang.effects`. In `BIP019-P1`, `@Profile` states each profile **literally**, field for field as the effect checker used it, so that the migration changes nothing ; friendlier forms built on the `org.babblelang.effects` vocabulary can replace it later. `@Axioms(allMethods = @Profile(…))` states the profile of every method of a target the stub doesn't list (all of `String`'s), `@Axioms(allConstructors = @Profile(…))` that of its constructors. The annotations, as they stand or are planned :

| Annotation | Meaning |
|---|---|
| `@Axioms(value, inherited, allMethods, allConstructors)` | this type states axioms about `value` (and, if `inherited`, its subtypes) |
| `@Profile` | the profile of a method or constructor, literally : effects, receiver, parameters, `nothrow`… |
| `@FreshResult` | the result is a new object aliasing no argument (`List.of`) |
| `@ShallowMut` | the mutator changes the receiver but not the objects it holds |
| `@View` | the result aliases the receiver |
| `@DeeplyImmutable` | on a stub type : instances are deeply immutable |
| `@Nullable`, `@NullMarked` | nullness, with JSpecify's semantics so that parametric nullness keeps working |
| `@Axioms(targetName)`, `@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked` | what a third-party annotation, named by its class name, means (§2.5) |
| `@Foldable` | the optimizer may call it at compile time |
| `@Terminating` | always terminates (what `HirPurity` assumes) |
| `@Derived(jdk, from, reasons)` | written by the analyzer (§3), never by hand |

Every overload is listed with its exact signature (`BIP019-D9`) : today's rules match methods by name, so the migration lists every public overload those rules reach, and `AxiomsTestCase` checks during the migration that none is missing. Constructors are mirrored as constructors on the stubs of concrete classes (`ArrayListAxioms`), or as methods tagged `@Constructor` where the stub is an interface.

### 2.3 Babble axioms : the prelude

`builtins.ba` declares Babble's built-ins in Babble. Those with an ordinary signature are **`axiom` declarations** (`BIP019-D8`) : a declaration with a type and no value, whose effects follow pure-by-default, and which the analysis trusts instead of checking :

```babble
axiom int: fn (value: any): int
axiom same: fn (a: any?, b: any?): bool
```

`axiom name: type` is a language form, valid anywhere : it states that a name exists with this type and these effects, and that its value comes from outside the program — the runtime's implicits for the built-ins, a host's bindings elsewhere. It applies to its scope and the scopes it encloses, declares nothing at runtime (its value as an expression is `null`), and has no `export` ; reading a name that nothing provides fails as `No such name`. Being a language change, it goes through every stage that sees the syntax : the grammar, the analysis passes, both backends and the formatter.

The special forms — `print` (any number of arguments, and `to:`), `move`, `freeze` and `discard` (ownership rules), `import` (modules), `STDOUT` and `args` — keep their typing in the type checker, as rules of the language rather than assumptions about functions (`BIP019-D17`). The prelude is analysed once, like a module, through `BuiltinAxioms`, and `OperatorTypes` gives a name it doesn't otherwise know the prelude's type ; without axioms (`BuiltinAxioms.EMPTY`) the built-ins are unknown.

### 2.4 The loader and the tiers

`org.babblelang.analysis.BuiltinAxioms` loads the directory once and is immutable afterwards. It answers `methodProfile`, `constructorProfile`, `returnNullness`, `parameterNullness`, `nullMarked`, `isDeeplyImmutable`, `isFoldable`, `isIdempotent` and `builtins` ; `BuiltinAxioms.EMPTY` knows nothing, for tests. The resolvers of `EffectChecker` keep their order, with tier 6 replaced (`BIP019-D5`) :

1. file, module or session `axiom` / `io` declarations on a method ;
2. engine overrides on a method (`assumeEffects`) ;
3. effect annotations on the Java method itself ;
4. file, session or engine declarations on a whole class ;
5. effect annotations on the Java class ;
6. **built-in axioms** — hand-written, then derived (`Axioms`) ;
7. bytecode analysis, when enabled (§3) ;
8. unconstrained.

### 2.5 What stays in code

Not every rule is an assumption about a function :

* **Language semantics :** operators on scalars, the `equals`, `hashCode` and `toString` that records generate, the iteration protocol itself, what `list()` and the other core collection constructors build ;
* **Mechanisms :** the tier order, how a stub is matched, how the bytecode analyzer works. The *meaning* of third-party nullness annotations (`lombok.NonNull`, `jakarta.annotation.Nullable`, `javax.annotation.CheckForNull`…) is an assumption too, and moves (`BIP019-D10`) : one stub per annotation, named after it (`org.babblelang.axioms.lombok.NonNullAxioms`), with `@Axioms(targetName = "lombok.NonNull")` — by name, since these classes are usually not on the class path — stating what it means (`@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked`).

### 2.6 QUESTIONABLE axioms

The migration reproduces today's assumptions exactly (`BIP019-D4`), including those that are known to be doubtful. Each of them is tagged `QUESTIONABLE` in its file and listed in the README, so that fixing one is a reviewable edit of one axiom file. The protocol methods come first (`BIP019-D11`), since they close §1's hole :

* **`equals`, `hashCode`, `compareTo` pure on every class** (`ObjectAxioms`, `ComparableAxioms`) — false for user classes (§1) ; the fix is to require Babble implementations of these protocol methods to be pure ;
* **collection methods by name on every implementation** (`CollectionAxioms`, `MapAxioms`, `IteratorAxioms`, `IterableAxioms`) — any `java.util.List` written by anyone is trusted ; the fix is to keep the shallow and hermetic axioms for Babble's core collections' classes only ;
* **`Map.Entry` deeply immutable** (`MapEntryAxioms`) — the entries of `entrySet()` support `setValue`.

---

## 3. The bytecode analyzer : deriving axioms from seeds

Most axioms about JDK methods follow from their bytecode once the few things bytecode cannot tell are given : natives, interface contracts, the methods of elements. The hand-written axioms become **seeds**, and one analyzer derives the rest.

### 3.1 One analyzer, two uses (`BIP019-D6`)

The same analyzer — `BytecodeEffectAnalyzer`, grown as below — runs in two places, with the same results :

* **while compiling**, as tier 7 (opt-in, as today), for host classes that have no axioms ;
* **offline**, as a generator that caches its results as derived axioms : `org.babblelang.axioms.tools.AxiomGenerator`, a development tool run on demand (like `bench.Benchmarks`), not part of the build. It reads the seeds and a file of the classes to cover, and writes `<Class>DerivedAxioms.java` stubs whose methods carry `@Derived(jdk = "21.0.x", from = {…}, reasons = {…})`. It never touches hand-written stubs : where it derives something stronger than a seed, or contradicts one, it only reports it, and a person decides whether to change the seed (`BIP019-D12`). The results are checked in and reviewed like any other change.

Derived axioms depend on the JDK's bytecode, so the generator runs on the baseline JDK (21, in the container AGENTS.md describes) and records the exact version. **Every CI build re-derives them** on its JDK and fails on any difference from the checked-in stubs (`BIP019-D13`), so drift — a JDK update, a changed seed, a change to the analyzer — shows at once ; locally, the same check runs in the JDK 21 container, and is skipped with a warning on any other JDK. A new baseline (BIP018's editions) means regenerating and reviewing the diff.

### 3.2 What the analyzer must learn

Today it is a conservative suggester : any field write clears `hermetic`, any open call gives up, almost nothing is `nothrow`. Deriving useful axioms needs :

* **a value lattice** on an ASM `Analyzer` : where a value comes from (`this`, parameter *i*, fresh, external, constant) and whether it may be null ;
* **mutation targets :** a write to `this` or to what it reaches makes the receiver `mut` ; to a parameter, that parameter ; to a fresh object, nothing ; to a static, not `hermetic` ;
* **escape :** a parameter stored in a field or a static is `shared`, one that is returned is returned through a borrow, any other is borrowed ;
* **return nullness :** always a `new` or a constant is `@NotNull` ;
* **shallowness :** no call reaches a stored element ;
* **calls through axioms :** a call applies the callee's axiom, seed or derived. An open (virtual or interface) call is resolved only through the axiom of the method it names, whose contract then binds every implementation — the old hidden assumption, now a named seed. Natives come only from seeds (`StrictMath.*`, `System.identityHashCode`, `System.nanoTime`…) ;
* **a fixed point** over the call graph, by strongly connected components, within a budget ; past the budget a method stays unconstrained, with the reason recorded.

### 3.3 What to expect

* **Good :** `Math` over `StrictMath`'s seeded natives, the bit operations of `Integer`, `Long` and `Double`, much of `String` (pure and deterministic, but not `nothrow` : bounds checks), and the concrete core collections — `ArrayList.add` comes out hermetic, mutating its receiver, with `shared e`.
* **Through seeds :** `HashMap.put` and `HashSet.add` rely on `Object.hashCode` and `equals`, `TreeMap.put` on `Comparable.compareTo` or `Comparator.compare` ; their derived axioms name these seeds, which is where §1's hole becomes a named, reviewable assumption.
* **Poor or impossible :** interfaces (`List.add` has no body : a seed, or the join over a closed set of implementations), `nothrow` (almost anything can throw), the termination of loops, `invokedynamic` beyond string concatenation and lambdas, reflection, method handles and `Unsafe`.

---

## 4. Settled Decisions (`BIP019-D1`–`D6`; `BIP019-Q1`–`Q7` → `BIP019-D7`–`D13`; `BIP019-D14`–`D17`)

* **`BIP019-D1` — One directory :** every assumption about a Java or Babble function or method lives in `src/main/java/org/babblelang/axioms/`, and nowhere else.
* **`BIP019-D2` — Layout :** Java axioms are stubs that mirror their target's package and name (`java.util.List` → `org.babblelang.axioms.java.util.ListAxioms`) ; Babble-level axioms are `.ba` files.
* **`BIP019-D3` — Everything moves :** effects, nullness, deep immutability, the optimizer's whitelists, and the built-ins' signatures and effects.
* **`BIP019-D4` — As-is first :** the migration reproduces today's assumptions exactly (same test results, same `scene.ppm`) ; doubtful ones are tagged `QUESTIONABLE` (§2.6) and fixed afterwards, each as an edit of its axiom file.
* **`BIP019-D5` — Precedence :** user `axiom` declarations, engine overrides and annotations on host classes keep precedence over the built-in axioms ; the bytecode analysis comes after them.
* **`BIP019-D6` — One analyzer :** the same bytecode analyzer derives axioms while compiling (opt-in) and offline (a generator caching them as `@Derived` stubs).
* **`BIP019-Q1` → `BIP019-D7` — Discovery :** the loader lists the axioms package on the class path, in a directory or a jar ; no index and no build step.
* **`BIP019-Q2` → `BIP019-D8` — `axiom name: type` :** a new language form, valid anywhere, declares a name with a type and no value, trusted by the analysis and provided from outside (the runtime's implicits for `builtins.ba`, a host's bindings elsewhere) ; it goes through the grammar, the analysis passes, both backends and the formatter.
* **`BIP019-Q3` → `BIP019-D9` — Overloads :** every overload is listed with its exact signature ; no annotation matches by name.
* **`BIP019-Q4` → `BIP019-D10` — Annotation names :** what third-party nullness annotations mean moves to the axioms too, one stub per annotation, matched by name.
* **`BIP019-Q5` → `BIP019-D11` — First fix :** after the migration, the protocol methods (`equals`, `hashCode`, `compareTo`, `Comparator.compare`) are fixed first : Babble implementations must be pure.
* **`BIP019-Q6` → `BIP019-D12` — Stronger derivations :** reported only ; hand-written axioms never change without a person deciding.
* **`BIP019-Q7` → `BIP019-D13` — Re-derivation :** every CI build re-derives the axioms and fails on any difference.
* **`BIP019-D14` — Keyword names :** `axiom name: type` accepts a core scalar keyword as its name, so that the conversions are declared like any built-in : `axiom int: fn (value: any): int`.
* **`BIP019-D15` — Optional parameters in function types :** a function type may mark a named parameter optional, `fn (c: bool, message?: any?): bool`, which a function whose parameter has a default satisfies ; `assert` is declared with it, and so may any function type.
* **`BIP019-D17` — Special forms :** `print`, `move`, `freeze`, `discard`, `import`, `STDOUT` and `args` are rules of the language, whose typing stays in the type checker ; the prelude declares the built-ins with an ordinary signature : the conversions, `assert` / `suppose`, `same` and `classof`.
* **`BIP019-D16` — Nearest surface form :** the built-ins are written with ordinary effect words (pure-by-default, `nothrow`, `commutative`, `io`) even where the former internal profiles differed in details the language cannot state (the conversions not borrowing `this`, `import`'s fully unconstrained effects) ; any difference a test, a golden or a diagnostic can see is reported, not hidden.

## 6. Phases (`BIP019-P1`–`P6`)

| Phase | Deliverables |
|---|---|
| **`BIP019-P1` — Mechanism and effects** | `Axioms`, the new annotations, the `pom.xml` resource entry, the package's `package-info.java` and `README.md` ; stubs for `Object`, `Comparable`, `String`, `Map.Entry`, `Collection`, `Map`, `Iterable`, `Iterator` and the concrete collections' constructors ; tier 6 of both resolvers, the shallow and view rules and `evalFor` read `Axioms` ; `standardProtocolDefault` and the `COLLECTION_*` and `STRING_*` sets are deleted. |
| **`BIP019-P2` — Optimizer and bytecode seeds** | `SEEDED_INTRINSICS` and `HirPurity`'s four lists become stubs for `Math`, `StrictMath`, `Double`, `Float`, `Integer`, `Long` and `String` (`@Foldable` marks exactly today's narrower folding subset) ; `ConstFoldPass` asks `Axioms`. |
| **`BIP019-P3` — Nullness and immutability** | `JavaNullability`'s overlay becomes `@NullMarked` stubs with `@Nullable` where it said nullable ; `Map.Entry`'s deep immutability becomes `@DeeplyImmutable`. ; the meaning of third-party nullness annotations becomes one stub per annotation (`@MeansNonNull`…), and `JavaNullability` loses its name lists. |
| **`BIP019-P4` — Built-ins** | optional parameters in function types (`BIP019-D15`) ; `axiom name: type`, its name a core scalar keyword too (`BIP019-D14`) (grammar, `BlockScopes`, `TypeChecker`, `Interpreter`, `HirBuilder`/`HirEmitter`, formatter, with `.ba`, `DifferentialTestCase`, `TypeCheckerTestCase` and `FormatterTestCase` tests) ; `builtins.ba` ; `OperatorTypes` reads the prelude ; diagnostics keep their wording. |
| **`BIP019-P5` — Enforcement and docs** | `AxiomsTestCase` (§7) ; `-format -check` covers the axioms ; AGENTS.md gets the rule "a new assumption goes in the axioms directory" ; README, BIP007 §4 and BIP011 §6.4, §8.2 point to the axioms. |
| **`BIP019-P6` — The analyzer and the generator** | §3 : the value lattice, calls through axioms, the fixed point ; `AxiomGenerator` and the first derived stubs, generated on JDK 21 ; the compile-time tier and the generator share the analyzer. ; CI re-derives the axioms on every build and fails on any difference (`BIP019-D13`). |

## 7. Verification

* After each phase : the full suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`) with **unchanged** expectations — the migration preserves behavior — plus `-Pbatest` where effects or nullness are covered, and argon compiled with `babblec` rendering `scene.ppm` byte for byte.
* `AxiomsTestCase` :
  1. every stub method matches a real executable (by name and erased parameters), every public overload the migrated by-name rules reached is listed, and every stub's package and name agree with its target — stale or mistyped axioms fail ;
  2. **with `BuiltinAxioms.EMPTY` the analysis knows nothing** : `String.length`, `List.add`, `Math.sqrt` and `Map.get` are unconstrained and of unspecified nullness, nothing is foldable, the built-ins are unknown — which proves that no assumption survives anywhere else ;
  3. the axioms' README lists every file.
* §1's example behaves as today until its `QUESTIONABLE` axiom is fixed, as its own change.
