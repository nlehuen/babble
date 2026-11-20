# BIP019: Refactoring Babble axioms

| | |
|---|---|
| **BIP** | 019 |
| **Date** | 2026-11-20 |
| **Status** | Decided (`BIP019-D1`–`D6`, `BIP019-Q1`–`Q7` → `BIP019-D7`–`D13`, and `BIP019-D14`–`D20`, `BIP019-Q8`–`Q15` → `BIP019-D21`–`D28`, `BIP019-D29`–`D31` decided, `BIP019-D2` and `D4` amended by `D30`; no phase implemented ; a prototype of `BIP019-P1`–`P6` and part of `P7` was reverted, and what it taught is recorded here) |
| **Depends on** | function effects and `axiom` declarations ([bip007_effects.md](bip007_effects.md)), core types and the `java.base` nullness overlay ([bip011_core-types.md](bip011_core-types.md)), the HIR optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)) |
| **Scope** | Every assumption the interpreter, the compiler, the analysis and the optimizer make about specific Java or Babble functions and methods — effects, nullness, deep immutability, optimizer whitelists, built-in signatures — moves to one directory, `src/main/java/org/babblelang/axioms/`, as `.ba` files (and `.java` stubs only for what Babble cannot state) ; one bytecode analyzer derives every axiom it can from hand-written seeds, either while compiling or offline into the same directory |

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
  java/lang/curated.ba             axioms about the classes of java.lang, written by hand : the seeds
  java/lang/inferred.ba            axioms about the classes of java.lang, written by the analyzer only
  java/util/curated.ba, java/util/inferred.ba
  java/util/function/ …            and so on, for every package the axioms cover
  lombok/NonNullAxioms.java …      what third-party annotations mean : the only Java stubs (§2.5)
```

* **Nowhere else.** No list of assumptions about a Java or Babble function or method remains in the interpreter, the compiler, the analysis or the optimizer. Quantity and elegance don't matter ; being explicit and discoverable does. Later work prunes the axioms, through more expressive forms or smarter inference (§3).
* **One rule to find an axiom :** the axioms about `java.util.List` are its block in `java/util/curated.ba` or `java/util/inferred.ba` (§5.4), never both for one member.
* **The build :** the directory is a source root, so its few `.java` stubs compile with the front end ; the package is in the full jar, not in the runtime jar (the runtime boundary is unchanged). A resource entry in `pom.xml` copies the `.ba` files of `src/main/java` next to the classes. The loader lists the package on the class path, in a directory or in a jar : no hand-written index, and no build step (`BIP019-D7`).

### 2.2 Java axioms : seeds and derivations (`BIP019-D30`)

Axioms about Java classes are written in Babble, in the class form of §5 (`axiom java.util.Collection of E ( … )`), from the start ; there is no intermediate generation of Java stubs. They come from two places, never both for one member :

* **Seeds, written by hand** in each package's `curated.ba` : what bytecode cannot tell — natives (`StrictMath.sin`, `System.nanoTime`), interface contracts (`List.add` has no body), the protocol methods of elements (`equals`, `hashCode`, `compareTo`, `Comparator.compare`), what the optimizer may fold (`foldable`), and corrections of a derivation ;
* **Derivations, written by the analyzer** (§3) into each package's `inferred.ba` : everything else, from the bytecode of the JDK and the seeds. **Nothing that can be inferred is written by hand or by any other tool.**

The former rules of §1 are not copied into axioms. A **simulation** — the former rules on one side, the seeds and derivations on the other, compared on every public method and constructor of `java.base` — reports each difference ; a difference is either accepted, as a review of the change it makes, or removed by a seed. The rules of §1 are deleted once their simulation is clean. Every overload is stated with its exact signature (`BIP019-D9`).

The **Java stubs** remain only for what Babble cannot state : what a third-party annotation means (§2.5), `@Axioms(targetName = "lombok.NonNull")` with `@MeansNonNull`, `@MeansNullable` or `@MeansNullMarked`.

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
6. **built-in axioms** — the seeds of `curated.ba`, then the derivations of `inferred.ba` (`BuiltinAxioms`) ;
7. bytecode analysis, when enabled (§3) ;
8. unconstrained.

### 2.5 What stays in code

Not every rule is an assumption about a function :

* **Language semantics :** operators on scalars, the `equals`, `hashCode` and `toString` that records generate, the iteration protocol itself, what `list()` and the other core collection constructors build ;
* **Mechanisms :** the tier order, how an axiom is matched, how the bytecode analyzer works. The *meaning* of third-party nullness annotations (`lombok.NonNull`, `jakarta.annotation.Nullable`, `javax.annotation.CheckForNull`…) is an assumption too, and moves (`BIP019-D10`) : one stub per annotation, named after it (`org.babblelang.axioms.lombok.NonNullAxioms`), with `@Axioms(targetName = "lombok.NonNull")` — by name, since these classes are usually not on the class path — stating what it means (`@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked`).

### 2.6 QUESTIONABLE axioms

The migration is checked against the former rules by the simulation (`BIP019-D4`, `D30`), including those that are known to be doubtful : where a doubtful rule is kept, it is a seed tagged `QUESTIONABLE` in `curated.ba` and listed in the README, so that fixing one is a reviewable edit of one axiom. The protocol methods come first (`BIP019-D11`), since they close §1's hole :

* **`equals`, `hashCode`, `compareTo` pure on every class** (seeds of `java/lang/curated.ba`) — false for user classes (§1) ; the fix is to require Babble implementations of these protocol methods to be pure ;
* **collection methods by name on every implementation** (seeds of `java/util/curated.ba` for `Collection`, `Map`, `Iterator` and `Iterable`) — any `java.util.List` written by anyone is trusted ; the fix is to keep the shallow and hermetic axioms for Babble's core collections' classes only ;
* **`Map.Entry` deeply immutable** (`java/util/curated.ba`) — the entries of `entrySet()` support `setValue`.

---

## 3. The bytecode analyzer : deriving axioms from seeds

Most axioms about JDK methods follow from their bytecode once the few things bytecode cannot tell are given : natives, interface contracts, the methods of elements. The hand-written axioms become **seeds**, and one analyzer derives the rest.

### 3.1 One analyzer, two uses (`BIP019-D6`)

The same analyzer — `BytecodeEffectAnalyzer`, grown as below — runs in two places, with the same results. It comes early (`BIP019-D31`) : before any Java axiom is migrated, so that no axiom it could infer is ever written another way.

* **while compiling**, as tier 7 (opt-in, as today), for host classes that have no axioms ;
* **offline**, as a generator that caches its results as derived axioms : `org.babblelang.axioms.tools.AxiomGenerator`, a development tool run on demand (like `bench.Benchmarks`), not part of the build. It reads the seeds and a file of the classes to cover, and writes each package's **`inferred.ba`** (§5.4, `BIP019-D20`), in the class form of §5, each member under a comment saying what it was derived from and why (the seeds used, the reasons). It **skips every member name** that the package's hand-written `curated.ba` already states, whatever the overloads : hand-written axioms are never overridden nor duplicated. Where it would derive something stronger than a hand-written axiom, or contradicting one, it only reports it, and a person decides whether to change the axiom (`BIP019-D12`). The results are checked in and reviewed like any other change.

Derived axioms depend on the JDK's bytecode, so the generator runs on the baseline JDK (21, in the container AGENTS.md describes) and records the exact version. The file records the JDK it was derived on. **Every CI build re-derives them** on its JDK and fails on any difference from the checked-in `inferred.ba` files, compared as text (`BIP019-D13`), so drift — a JDK update, a changed seed, a change to the analyzer — shows at once ; locally, the same check runs in the JDK 21 container, and is skipped with a warning on any other JDK. A new baseline (BIP018's editions) means regenerating and reviewing the diff.

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

## 4. Settled Decisions (`BIP019-D1`–`D6`; `BIP019-Q1`–`Q7` → `BIP019-D7`–`D13`; `BIP019-D14`–`D20`, `BIP019-Q8`–`Q15` → `BIP019-D21`–`D28`; `BIP019-D29`–`D31`)

* **`BIP019-D1` — One directory :** every assumption about a Java or Babble function or method lives in `src/main/java/org/babblelang/axioms/`, and nowhere else.
* **`BIP019-D2` — Layout (amended by `BIP019-D30`) :** axioms are `.ba` files : the built-ins' prelude, and per Java package a `curated.ba` and an `inferred.ba` ; Java stubs, mirroring their target's package and name, remain only for what Babble cannot state (the meaning of third-party annotations).
* **`BIP019-D3` — Everything moves :** effects, nullness, deep immutability, the optimizer's whitelists, and the built-ins' signatures and effects.
* **`BIP019-D4` — As-is first (amended by `BIP019-D30`) :** the migration is checked against the former rules by a simulation on every public method and constructor of `java.base`, rather than reproduced by copying them ; each difference is reported and either accepted or removed by a seed ; doubtful rules that are kept are seeds tagged `QUESTIONABLE` (§2.6), fixed afterwards, each as an edit of its axiom.
* **`BIP019-D5` — Precedence :** user `axiom` declarations, engine overrides and annotations on host classes keep precedence over the built-in axioms ; the bytecode analysis comes after them.
* **`BIP019-D6` — One analyzer :** the same bytecode analyzer derives axioms while compiling (opt-in) and offline (a generator caching them in each package's `inferred.ba`, `BIP019-D20`).
* **`BIP019-Q1` → `BIP019-D7` — Discovery :** the loader lists the axioms package on the class path, in a directory or a jar ; no index and no build step.
* **`BIP019-Q2` → `BIP019-D8` — `axiom name: type` :** a new language form, valid anywhere, declares a name with a type and no value, trusted by the analysis and provided from outside (the runtime's implicits for `builtins.ba`, a host's bindings elsewhere) ; it goes through the grammar, the analysis passes, both backends and the formatter.
* **`BIP019-Q3` → `BIP019-D9` — Overloads :** every overload is listed with its exact signature ; no annotation matches by name.
* **`BIP019-Q4` → `BIP019-D10` — Annotation names :** what third-party nullness annotations mean moves to the axioms too, one stub per annotation, matched by name.
* **`BIP019-Q5` → `BIP019-D11` — First fix :** after the migration, the protocol methods (`equals`, `hashCode`, `compareTo`, `Comparator.compare`) are fixed first : Babble implementations must be pure.
* **`BIP019-Q6` → `BIP019-D12` — Stronger derivations :** reported only ; hand-written axioms never change without a person deciding.
* **`BIP019-Q7` → `BIP019-D13` — Re-derivation :** every CI build re-derives the axioms and fails on any difference.
* **`BIP019-D14` — Keyword names :** `axiom name: type` accepts a core scalar keyword as its name, so that the conversions are declared like any built-in : `axiom int: fn (value: any): int`.
* **`BIP019-D15` — Optional parameters in function types :** a function type may mark a named parameter optional, `fn (c: bool, message?: any?): bool`, which a function whose parameter has a default satisfies ; `assert` is declared with it, and so may any function type.
* **`BIP019-D18` — One `.ba` file per package :** Java axioms written in Babble (`BIP019-P7`, §5) live in a single `curated.ba` per Java package, in that package's directory of the axioms, one `axiom Class ( … )` block per class.
* **`BIP019-D19` — Sorted and deterministic :** `curated.ba` and `inferred.ba` have one canonical form — blocks sorted by class, members by name then by their parameters' erasures, class and effect words in a fixed order, formatter-clean — which `AxiomsTestCase` checks and the generator produces byte for byte from the same seeds and JDK.
* **`BIP019-D20` — Written by hand, or inferred :** each package has a `curated.ba`, written by hand, and an `inferred.ba`, written only by the inference tool, which skips every member name `curated.ba` states ; a member is stated in one file only, and correcting an inferred axiom means writing it in `curated.ba`.
* **`BIP019-Q8` → `BIP019-D21` — Where :** the class form may be written anywhere : in the axioms directory and in programs, whose axioms take precedence over the built-in ones, as their `axiom … on` declarations do today.
* **`BIP019-Q9` → `BIP019-D22` — Nullness always stated :** an axiom never leaves a position's nullness unspecified ; where the former rules said nothing, the generator writes what the analysis would assume anyway (nullable for an unannotated Java type, non-null under `nullmarked`).
* **`BIP019-Q10` → `BIP019-D23` — Java types in parameters :** where Babble types can't tell overloads apart, the member's parameter is written with the Java type (`e: java.lang.Integer`) ; elsewhere Babble types, mapped to their erasures, name the overload.
* **`BIP019-Q11` → `BIP019-D24` — `*` members :** `*: fn …` states the profile of every method the block doesn't list, `new *: fn …` that of every constructor ; they come first in a block (`BIP019-D19`).
* **`BIP019-Q12` → `BIP019-D25` — One profile :** a member states one profile, which the effect checker uses and the bytecode analyzer takes as its seed ; the former disagreements (`String.valueOf`) are resolved when migrating, each reported.
* **`BIP019-Q13` → `BIP019-D26` — The class form subsumes `axiom … on` :** `axiom pure on X.m` becomes shorthand for a member covering every overload of `m`, and is deprecated in favor of the class form, with a warning.
* **`BIP019-Q14` → `BIP019-D27` — Reads of mutable state are volatile :** a read is deterministic only when the object read is frozen or deeply immutable, or owned exclusively by the reader (a `unique` value, an object the function created itself) ; reading a mutable object through a borrowed or shared reference is volatile, since another thread may mutate it between two reads. This changes the effect model of BIP007 and BIP016 for Babble's own functions too, not only the axioms (§5.6, `BIP019-P6`).
* **`BIP019-Q15` → `BIP019-D28` — Reads are inferred :** what a function reads is inferred and recorded in module summaries, with no surface syntax ; a declared function type whose body the analysis doesn't see is assumed to read every parameter and its receiver that are not frozen or deeply immutable.
* **`BIP019-D29` — Deterministic results are tracked :** a deterministic callee cannot reach shared mutable state, so what it returns is fresh or derived from its arguments and receiver — for a Babble function whose body isn't known, a Java method, or a core collection constructor (a fresh collection whose elements come from its arguments) ; only a `volatile` or `io` callee returns something untracked. Without it, a prototype found higher-order code (`f(g(x))`, `list.add(f(x))`) volatile.
* **`BIP019-D30` — Nothing inferred without the analyzer :** an axiom that bytecode can derive is written only by the analyzer, into `inferred.ba` ; `curated.ba` holds only seeds — what the analyzer cannot derive — and corrections. The former rules are never copied into axioms, by hand or by a one-off generator ; Java axioms are written in Babble from the start.
* **`BIP019-D31` — The analyzer early :** the unified analyzer and its generator (§3) come right after the mechanism, the class form and the reads model it derives into, and before any axiom about a Java class is migrated.
* **`BIP019-D17` — Special forms :** `print`, `move`, `freeze`, `discard`, `import`, `STDOUT` and `args` are rules of the language, whose typing stays in the type checker ; the prelude declares the built-ins with an ordinary signature : the conversions, `assert` / `suppose`, `same` and `classof`.
* **`BIP019-D16` — Nearest surface form :** the built-ins are written with ordinary effect words (pure-by-default, `nothrow`, `commutative`, `io`) even where the former internal profiles differed in details the language cannot state (the conversions not borrowing `this`, `import`'s fully unconstrained effects) ; any difference a test, a golden or a diagnostic can see is reported, not hidden.

## 5. Java axioms written in Babble (`BIP019-P7`)

### 5.1 Motivation

Java stubs, as the reverted prototype wrote them, are explicit but verbose : a profile is a long annotation, nullness another set of annotations, and every method repeats it all. Babble's own function types already carry most of what they state — effects, what a function does to its receiver and parameters, nullness, optional parameters — so the same axioms can be written in Babble. Part of `Collection`'s axioms, as Java stubs (as the reverted prototype wrote them) and in Babble :

```java
@ShallowMut @Profile(value = {HERMETIC, DETERMINISTIC}, mutThis = true, borrowThis = false, mutReceiver = true,
        receiver = Receiver.SHARED)
@Parametric java.lang.Object add(@Parametric java.lang.Object p0);

@View @Profile(value = {HERMETIC, DETERMINISTIC, NOTHROW}, borrowedParameters = true, nothrow = true) @NonNull
java.lang.Object iterator();
```

```babble
;; java/util/curated.ba : every axiom about the classes of java.util, Collection's among them
axiom java.util.Collection of E (
    size: fn nothrow (): int
    contains: fn (o: any?): bool
    add: fn mut shallow (shared e: E): bool
    iterator: fn nothrow view (): java.util.Iterator of E
)
```

### 5.2 Syntax

A new form, `axiom` followed by a Java class (and its type parameters), and a block of members, each a name and a Babble function type :

```antlr
axiomClass: AXIOM target=qualifiedName (OF typeParameters)? classWord* NL* '(' sep? (axiomMember sep?)* ')' ;
axiomMember: (memberName | NEW) ':' type ;
```

* **Telling it apart :** after `axiom`, a qualified name followed by `of`, a class word or `(` is a class ; a name followed by `:` is an `axiom` declaration (§2.3) ; an effect word is an effect declaration (`axiom pure on …`). The grammar stays predicate-free.
* **Members :** a method by its name (any name : `getClass` is no longer a problem, as it was for Java stubs), a constructor as `new`. Overloads are separate members of the same name, told apart by their parameter types (§5.3).
* **Class words :** `exact` (the axioms don't hold for subtypes, the opposite of `inherited`), `nullmarked` (unannotated type arguments in the class's signatures are non-null) and `immutable` (instances are deeply immutable when their type arguments are).

### 5.3 Mapping to what the analysis assumes

| Babble | Assumption | As a Java stub would state it |
|---|---|---|
| `fn (…)` (pure by default) | pure, hermetic, deterministic | `@Profile({PURE, HERMETIC, DETERMINISTIC})` |
| `fn volatile`, `fn io` | non-deterministic ; performs I/O | effects without `DETERMINISTIC` ; unconstrained |
| `fn nothrow` | never throws, and needs no call check | `NOTHROW` and `nothrow = true` |
| `fn mut` | mutates its receiver | `mutThis`, `mutReceiver` |
| `fn shared` | keeps its receiver | `receiver = SHARED` |
| unmarked, `shared`, `unique`, `mut` parameter | borrowed, kept, consumed, mutated | `borrowedParameters`, `@Shared`… |
| `T`, `T?`, a type variable `E` | non-null, nullable, as nullable as its argument | `@NonNull`, `@Nullable`, `@Parametric` |
| `p?: T` | optional parameter (`BIP019-D15`) | — |
| `idempotent`, `commutative`… | algebraic properties | `@Idempotent`… |
| **new :** `view`, `shallow`, `fresh`, `foldable` | the result aliases the receiver ; a mutator touching no element ; a fresh result ; may be called at compile time | `@View`, `@ShallowMut`, `@FreshResult`, `@Foldable` |

* **Overloads :** a member's parameter types map to Java's erasures — `int` to `int`, `string` to `String`, a type variable to its bound (`Object`), `list of T` to `List`, a Java class to itself — and the member describes the overload with those parameter types. Where Babble's types don't pin one down (`int` against `Integer`, `char` against `int`, arrays), the parameter is written with the Java type itself (`e: java.lang.Integer`, `BIP019-D23`).
* **Exactness :** some of the former rules' profile details have no surface form — a receiver borrowed unless the method is static, `Object`'s "deterministic where overridden". As for the built-ins (`BIP019-D16`), the nearest surface form is used and every difference a test, a golden or a diagnostic can see is reported. Reads are plain `fn`s, their determinism decided at the call site (`BIP019-D27`, §5.6) ; a member states one profile for the checker and the analyzer (`BIP019-D25`), and the QUESTIONABLE disagreements are resolved, not reproduced.

### 5.4 Coexistence, migration and loading

* **One file per package (`BIP019-D18`) :** the hand-written axioms about the classes of a Java package are in a single `.ba` file, `curated.ba`, in that package's directory of the axioms (`java/util/curated.ba` holds `Collection`, `List`, `Map`… ; `java/lang/curated.ba` holds `Object`, `String`, `Math`…), one `axiom Class ( … )` block per class. `AxiomsTestCase` checks that a block in `java/util/curated.ba` is about a class of `java.util`.
* **Written by hand, or inferred (`BIP019-D20`) :** next to it, the package's **`inferred.ba`** holds the axioms the inference tool derives (§3.1), and only those ; nobody edits it by hand. The tool skips every member name the package's `curated.ba` states (all its overloads), so a member is stated in one file only ; to correct an inferred axiom, one writes it in `curated.ba`, and the next inference drops it from `inferred.ba`. Both files hold `axiom Class ( … )` blocks, and a class may have a block in each.
* **One source per member :** a member's axioms come from its package's `curated.ba` or its `inferred.ba`, never both ; `AxiomsTestCase` checks it.
* **Sorted and deterministic (`BIP019-D19`) :** `curated.ba` and `inferred.ba` have one canonical form, so that a file reads the same whoever wrote it and diffs only where an axiom changed :
  * its blocks are sorted by the class's binary name (`java.util.AbstractMap$SimpleEntry` after `java.util.AbstractMap`) ;
  * in a block, the class words come in a fixed order (`exact`, `nullmarked`, `immutable`), and the members are sorted by name (`new` first), then by their parameters' Java erasures, compared as type descriptors ;
  * within a function type, the effect words come in the order the formatter writes them ;
  * the file is formatter-clean, and comments are only allowed above a block or a member, which keeps them attached when sorting ;
  * `AxiomsTestCase` fails on a file out of order ; the inference tool writes the canonical form, and the same `curated.ba` and seeds on the same JDK produce the same `inferred.ba` byte for byte (re-deriving in CI, `BIP019-D13`, compares them as text).
* **Migration (`BIP019-D30`) :** the former rules are not copied : the analyzer derives what it can into `inferred.ba`, the seeds it needs are written by hand into `curated.ba`, and the simulation (§2.2) reports every difference with the former rules, each accepted or removed by a seed. Java stubs exist only for the meaning of third-party annotations, and disappear as the language learns to state it.
* **Loading :** a `.ba` axiom file must be parsed and type-checked before use, as `builtins.ba` is. Since each package has its two files, named by the package, a class's axioms can be loaded on first use — its package's `curated.ba` and `inferred.ba`, then those of its supertypes' packages — rather than all at start-up ; the results are cached per package.

### 5.5 Decisions (`BIP019-Q8`–`Q14` → `BIP019-D21`–`D27`)

The questions §5 raised are settled (§4) : the class form is allowed anywhere (`BIP019-D21`) ; nullness is always stated (`BIP019-D22`) ; ambiguous overloads use Java types in their parameters (`BIP019-D23`) ; `*: fn …` and `new *: fn …` state class-wide profiles (`BIP019-D24`) ; a member states one profile for the effect checker and the analyzer alike (`BIP019-D25`) ; `axiom … on X.m` becomes shorthand for a class-form member and is deprecated (`BIP019-D26`) ; and reads of mutable state are volatile (`BIP019-D27`, §5.6).

### 5.6 Reads of mutable state (`BIP019-D27`, `BIP019-P6`)

Today, "deterministic" means deterministic given the state of what a function can reach, assuming nothing mutates it concurrently : `fn (c: Counter): int -> (c.n)` and `fn (xs: list of int): int -> (xs.size())` are plain pure functions. With threads, another thread may mutate `c` or `xs` between two calls, so `BIP019-D27` makes such reads volatile, unless the object read cannot be mutated by anyone else.

* **Where determinism is decided :** at the call site, where the caller knows what it passes. A function's summary records which of its receiver and parameters it reads mutable state from (as it records which it mutates, `mut`, or keeps, `shared`) ; a call is deterministic only if each object so read is frozen or deeply immutable, or owned exclusively by the caller (a `unique` value, a fresh local) ; otherwise the call is volatile. Reading a mutable field of an outer binding is volatile already.
* **In axioms :** a Java read is written as a plain `fn` (`size: fn nothrow (): int`) ; the call site then decides, as for a Babble function. Today's collection reads, hermetic and deterministic but not pure, need no word of their own.
* **Consequences :** functions reading a mutable object they are given become volatile unless their callers pass frozen, deeply immutable or exclusively owned values. Argon's rendering reads its world and materials, which are built once and then only read ; freezing them after construction keeps it deterministic. Tests, examples and goldens that assume such reads deterministic change.
* **Composition :** a read through the caller's own parameter or receiver is not decided at that call : it becomes a read by the caller, recorded in the caller's summary, and is decided at the caller's call sites. A call is volatile only where the provenance runs out — an object reached from an outer mutable binding, a global, or anything else neither frozen, deeply immutable, exclusively owned, nor the caller's own parameter or receiver.
* **Declared types (`BIP019-Q15` → `BIP019-D28`) :** what a function reads is inferred, and recorded in module summaries for importers ; there is no surface syntax for it. A function type whose body the analysis doesn't see — an interface method, a function value, a Java method — is assumed to read every parameter and its receiver that are not frozen or deeply immutable.
* **Results (`BIP019-D29`) :** a deterministic callee's result is fresh or derived from its arguments and receiver ; only `volatile` and `io` callees return something untracked.
* **What a prototype found (reverted) :** summaries carry `readParams` and `readsThis`, inferred like `mutParams` and `mutThis` and carried by module summaries ; member reads of classes and objects (not records), Java instance fields, Java calls, Babble calls and `for` loops record what they read. Since the model already made reading a mutable outer binding, or a variable an escaping closure captured, non-deterministic, a deterministic function reaches mutable objects only through its parameters, its receiver, and what it creates : its reads are recorded and composed up the calls, and become volatile where provenance ends. Tracking provenance more precisely found one real escape : argon's `BVHNode.fromList` keeps its argument's hittables, so that parameter must become `shared`. Without `BIP019-D29`, higher-order code (`f(g(x))`, `list.add(f(x))`) became volatile.

## 6. Phases (`BIP019-P1`–`P8`, in this order, `BIP019-D31`)

Until the phase that migrates a former rule, the rule stays in the code, consulted after the axioms : axioms grow while the rules shrink, and each phase leaves the suite green.

| Phase | Deliverables |
|---|---|
| **`BIP019-P1` — Mechanism and the class form** | the axioms directory and `README.md`, the `pom.xml` resource entry ; `BuiltinAxioms` loading each package's `curated.ba` and `inferred.ba` on first use, with a cache (§5.4), and `BuiltinAxioms.EMPTY` ; the class form `axiom Class ( … )` with its member and class words (§5.2, §5.3 : grammar, analysis passes, both backends, formatter) ; the canonical order (`BIP019-D19`) ; tier 6 of both resolvers, nullness, deep immutability and the optimizer ask `BuiltinAxioms` first. |
| **`BIP019-P2` — Reads of mutable state** | §5.6 (`BIP019-D27`–`D29`) : summaries record the receiver and parameters a function reads mutable state from ; a call is deterministic only on frozen, deeply immutable or exclusively owned arguments ; tests, examples and goldens updated. The analyzer derives axioms in this model. |
| **`BIP019-P3` — The analyzer and the generator** | §3 : the value lattice, calls through axioms, the fixed point ; one analyzer for the compile-time tier and for `AxiomGenerator`, which writes each package's `inferred.ba` on JDK 21, skipping the member names of its `curated.ba` ; CI re-derives the `inferred.ba` files and fails on any difference (`BIP019-D13`) ; the simulation against the former rules (§2.2). |
| **`BIP019-P4` — Effects** | seeds in `java/lang/curated.ba` and `java/util/curated.ba` (protocol methods, interface contracts, `QUESTIONABLE` where a doubtful rule is kept) and their derivations ; the simulation of the effect rules reviewed ; `standardProtocolDefault`, the `COLLECTION_*` and `STRING_*` sets, the constructor rules, the shallow and view rules and `evalFor`'s case deleted. |
| **`BIP019-P5` — Optimizer and bytecode seeds** | `SEEDED_INTRINSICS` becomes seeds for the natives (`StrictMath`…) and derivations for the rest ; `HirPurity`'s four lists become `foldable` and `idempotent` seeds ; `ConstFoldPass` asks `BuiltinAxioms`. |
| **`BIP019-P6` — Nullness and immutability** | `JavaNullability`'s overlay becomes `nullmarked` classes and the nullness of members' types, derived where the analyzer can tell (a result always `new` or constant), seeds elsewhere ; `Map.Entry`'s deep immutability becomes `immutable` ; the meaning of third-party nullness annotations becomes one Java stub per annotation, and `JavaNullability` loses its name lists. |
| **`BIP019-P7` — Built-ins** | optional parameters in function types (`BIP019-D15`) ; `axiom name: type`, its name a core scalar keyword too (`BIP019-D14`) ; `builtins.ba` ; `OperatorTypes` reads the prelude ; diagnostics keep their wording. |
| **`BIP019-P8` — Enforcement and docs** | `AxiomsTestCase` (§7) ; `-format -check` covers the axioms ; `axiom … on` deprecated in favor of the class form (`BIP019-D26`) ; AGENTS.md gets the rule "a new assumption goes in the axioms directory" ; README, BIP007 §4 and BIP011 §6.4, §8.2 point to the axioms. |

## 7. Verification

* After each phase : the full suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`), plus `-Pbatest` where effects or nullness are covered, and argon compiled with `babblec` rendering `scene.ppm` byte for byte. Expectations change only where a phase says so (`BIP019-P2`'s reads model) or where a simulation difference was accepted, and the commit says which.
* The simulation (§2.2) : for each migrated rule, no difference with the former rules on `java.base` that was not reviewed and accepted.
* `AxiomsTestCase` :
  1. every member of an axiom matches a real executable (by name and erased parameters), a member is stated in `curated.ba` or in `inferred.ba` but not both, the blocks of a package's files are about that package's classes, and the files are in canonical order (`BIP019-D19`) — stale or mistyped axioms fail ;
  2. **with `BuiltinAxioms.EMPTY` the analysis knows nothing** : `String.length`, `List.add`, `Math.sqrt` and `Map.get` are unconstrained and of unspecified nullness, nothing is foldable, the built-ins are unknown — which proves that no assumption survives anywhere else ;
  3. the axioms' README lists every file and every `QUESTIONABLE` seed.
* CI re-derives every `inferred.ba` and finds no difference (`BIP019-D13`).
* §1's example behaves as today until its `QUESTIONABLE` seed is fixed, as its own change.
