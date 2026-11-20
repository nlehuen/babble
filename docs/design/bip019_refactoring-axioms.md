# BIP019: Refactoring Babble axioms

| | |
|---|---|
| **BIP** | 019 |
| **Date** | 2026-11-20 |
| **Status** | Decided ; no phase implemented. A prototype of the former ordering was reverted ; what it taught is recorded where it applies (§3.2, §3.3, §4.3). |
| **Depends on** | function effects and `axiom` declarations ([bip007_effects.md](bip007_effects.md)), core types and the `java.base` nullness overlay ([bip011_core-types.md](bip011_core-types.md)), the HIR optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)) |
| **Scope** | Every assumption the interpreter, the compiler, the analysis and the optimizer make about specific Java or Babble functions and methods — effects, nullness, deep immutability, optimizer whitelists, built-in signatures — moves to one directory, `src/main/java/org/babblelang/axioms/`, as a small set of curated `.ba` axioms (and `.java` stubs only for what Babble cannot state) ; everything else about Java classes is inferred from their bytecode by the compiler, the curated axioms overriding what it infers ; reads of mutable state become part of the effect model |

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

### 2.1 One directory

```
src/main/java/org/babblelang/axioms/
  package-info.java, README.md     the format, the index of the files, the QUESTIONABLE list
  builtins.ba                      Babble's built-ins (the prelude)
  java/lang/curated.ba             axioms about the classes of java.lang, written by hand
  java/util/curated.ba             axioms about the classes of java.util
  java/util/function/ …            and so on, for every package the axioms cover
  lombok/NonNullAxioms.java …      what third-party annotations mean : the only Java stubs (§2.5)
```

* **Nowhere else.** Every assumption about a Java or Babble function or method — effects, nullness, deep immutability, the optimizer's whitelists, the built-ins' signatures and effects — lives in this directory. No list of assumptions remains in the interpreter, the compiler, the analysis or the optimizer. Quantity and elegance don't matter ; being explicit and discoverable does. The set is meant to stay small : an axiom earns its place only where the bytecode analysis (§5) cannot tell, or tells something the language must not rely on ; later work prunes it further, through more expressive forms or smarter inference.
* **One rule to find an axiom :** the axioms about `java.util.List` and its members are in `java/util/curated.ba` (§3.4) ; what has no axiom is inferred.
* **The build :** the directory is a source root, so its few `.java` stubs compile with the front end ; the package is in the full jar, not in the runtime jar (the runtime boundary is unchanged). A resource entry in `pom.xml` copies the `.ba` files of `src/main/java` next to the classes. The loader lists the package on the class path, in a directory or in a jar : no hand-written index, and no build step.

### 2.2 Java axioms : curated, over inferred

What the analysis assumes about a Java method or constructor comes from one of two sources, the first winning :

* **Curated axioms**, written by hand in each package's `curated.ba`, in the form of §3 (`axiom java.util.Collection of E is class ( … )`). They state what bytecode cannot tell — natives (`StrictMath.sin`, `System.nanoTime`), interface contracts (`List.add` has no body), the protocol methods of elements (`equals`, `hashCode`, `compareTo`, `Comparator.compare`), what the optimizer may fold (`foldable`) — and **override** the inference wherever it is wrong, too weak, or must not be relied on.
* **Inference**, by the bytecode analyzer **in the compiler** (§5), for everything else, from the bytecode of the classes the compiling JVM loads, applying the curated axioms to the calls it meets. Nothing it infers is written to a file : no generated code enters the codebase ; the compiler spends the time instead, and caches the results in memory.

The former rules of §1 are not copied into axioms. A **simulation** — the former rules on one side, the curated axioms and the inference on the other, compared on every public method and constructor of `java.base` — reports each difference ; a difference is either accepted, as a review of the change it makes, or removed by a curated axiom. The rules of §1 are deleted once their simulation is clean. A curated axiom states each overload with its exact signature : nothing matches methods by name.

The **Java stubs** remain only for what Babble cannot state : what a third-party annotation means (§2.5).

### 2.3 Babble axioms : the prelude

`builtins.ba` declares Babble's built-ins in Babble, with the same form as every other axiom (§3) : `axiom <name> is <definition>`, the definition being the built-in's function type, effects and signature. The analysis trusts it instead of checking it :

```babble
axiom int is fn (value: any): int
axiom assert is fn (c: bool, message?: any?): bool
axiom same is fn nothrow commutative (a: any?, b: any?): bool
```

* **An axiom about a Babble name** states that the name exists with this signature and these effects, and that its value comes from outside the program — the runtime's implicits for the built-ins, a host's bindings elsewhere. It is valid anywhere, applies to its scope and the scopes it encloses, declares nothing at runtime (its value as an expression is `null`), and has no `export` ; reading a name that nothing provides fails as `No such name`. Nothing can be inferred about such a name, so its signature is required. Its name may be a core scalar keyword, so that the conversions are declared like any built-in (`axiom int is fn …`).
* **Optional parameters :** a function type may mark a named parameter optional, `fn (c: bool, message?: any?): bool`, which a function whose parameter has a default satisfies ; `assert` is declared with it, and so may any function type, in an axiom or not.
* **Special forms stay rules of the language :** `print` (any number of arguments, and `to:`), `move`, `freeze` and `discard` (ownership rules), `import` (modules), `STDOUT` and `args` keep their typing in the type checker. The prelude declares the built-ins with an ordinary signature : the conversions, `assert` / `suppose`, `same` and `classof`.
* **Nearest surface form :** the built-ins are written with ordinary effect words (pure-by-default, `nothrow`, `commutative`, `io`) even where the former internal profiles differed in details the language cannot state (the conversions not borrowing `this`, `import`'s fully unconstrained effects) ; any difference a test, a golden or a diagnostic can see is reported, not hidden.

The prelude is analysed once, like a module, through `BuiltinAxioms`, and `OperatorTypes` gives a name it doesn't otherwise know the prelude's type ; without axioms (`BuiltinAxioms.EMPTY`) the built-ins are unknown.

### 2.4 The loader and the tiers

`org.babblelang.analysis.BuiltinAxioms` loads the curated axioms and is immutable afterwards (per-package files are loaded on first use and cached, §3.4). It answers `methodProfile`, `constructorProfile`, `returnNullness`, `parameterNullness`, `nullMarked`, `isDeeplyImmutable`, `isFoldable`, `isIdempotent` and `builtins` ; `BuiltinAxioms.EMPTY` knows nothing, for tests. The resolvers of `EffectChecker` keep their order : what a program, an engine or a host class states keeps precedence over the curated axioms, which keep precedence over the bytecode analysis.

1. a program's, module's or session's own axioms about a method (§3.2) ;
2. engine overrides on a method (`assumeEffects`) ;
3. effect annotations on the Java method itself ;
4. file, session or engine declarations on a whole class ;
5. effect annotations on the Java class ;
6. **curated axioms** (`curated.ba`) ;
7. **bytecode analysis** (§5), now on by default ;
8. unconstrained.

### 2.5 What stays in code

Not every rule is an assumption about a function :

* **Language semantics :** operators on scalars, the `equals`, `hashCode` and `toString` that records generate, the iteration protocol itself, what `list()` and the other core collection constructors build ;
* **Mechanisms :** the tier order, how an axiom is matched, how the bytecode analyzer works.

The *meaning* of third-party nullness annotations (`lombok.NonNull`, `jakarta.annotation.Nullable`, `javax.annotation.CheckForNull`…) is an assumption too, and moves : one Java stub per annotation, named after it (`org.babblelang.axioms.lombok.NonNullAxioms`), with `@Axioms(targetName = "lombok.NonNull")` — by name, since these classes are usually not on the class path — stating what it means (`@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked`). These stubs disappear if the language learns to state it.

### 2.6 QUESTIONABLE axioms

Some former rules are known to be doubtful. The simulation (§2.2) shows where dropping one changes behavior ; where a doubtful rule is kept, it is a curated axiom tagged `QUESTIONABLE` and listed in the README, so that fixing it later is a reviewable edit of one axiom. The protocol methods come first, since they close §1's hole :

* **`equals`, `hashCode`, `compareTo`, `Comparator.compare` pure on every class** (`java/lang/curated.ba`, `java/util/curated.ba`) — false for user classes (§1) ; the fix is the axioms of §3.1 (`axiom java.lang.Object.hashCode is fn pure`…) applying to descendants as contracts (§3.2), so that a Babble implementation must be pure ;
* **collection methods on every implementation** (curated axioms for `Collection`, `Map`, `Iterator` and `Iterable`) — any `java.util.List` written by anyone is trusted ; the fix is to keep the shallow and hermetic axioms for Babble's core collections' classes only ;
* **`Map.Entry` deeply immutable** (`java/util/curated.ba`) — the entries of `entrySet()` support `setValue`.

---

## 3. The form of axioms

### 3.1 `axiom <target> is <definition>`

Every axiom has one form : a target, `is`, and what is assumed about it. The target is a function — a Babble name, or a member of a Java class — or a Java class :

```babble
;; a function : `fn`, its effects, then its signature, which may be left to inference
axiom foobar is fn pure (a: int): int
axiom java.lang.Object.hashCode is fn pure
axiom java.lang.Comparable.compareTo is fn pure
axiom java.lang.Math.sqrt is fn nothrow foldable (a: double): double

;; a class : its instance members, then its static members
axiom java.util.Collection of E is class (
    size is fn nothrow (): int
    contains is fn (o: any?): bool
    add is fn mut shared shallow (shared e: E): bool
    iterator is fn nothrow view (): java.util.Iterator of E
)
axiom java.util.ArrayList of E is class (
) static (
    new is fn shared
)
```

* **A function's definition** is a function type, `fn <effects>? <signature>?`, with effect words, a signature, or both :
  * **effects only** (`is fn pure`) : the function's effects, for every overload of it ; its signature — its parameters' modes and its nullness — is inferred. Only a Java member can omit its signature, since only bytecode can tell it ;
  * **a signature** (`fn (o: any?): bool`, with its parameter modes, optional parameters and nullness) : that overload (§3.3), fully ; without effect words it is pure by default, as any function type is (`pure`, which a function type otherwise never spells, may be written in an axiom).
* **A class's definition** is `class [class words] ( <members> ) [static ( <static members> )]` — for a Java interface too. Each member is `<name> is <definition>`, a function's definition as above : instance methods in the first part, static methods and constructors (`new`) in `static ( … )`. The class's type parameters (`of E`) are in scope in its members' signatures.
* **What is not stated is inferred :** a member a class's axiom doesn't mention, or a class nothing mentions, is left to the bytecode analysis (§5) ; leaving a member out is never an error. A member named `*` states what holds for every member the class's axiom doesn't list instead (`* is fn pure` : all of `String`'s methods), and `new is fn …` without a signature covers every constructor.

The same contents as Java annotations would take a `@Profile` of seven fields, two nullness annotations and a marker per method.

### 3.2 Syntax and rules

```antlr
axiomExpression: AXIOM target=axiomTarget (OF typeParameters)? IS axiomDefinition ;
axiomTarget: (ID | coreScalarKeyword) ('.' memberName)* ;
axiomDefinition: FN effectPrefix? (fnTypeParameters (':' uniqueReturn=UNIQUE? type)?)?   // a function type
               | CLASS classWord=ID* NL* '(' axiomMembers ')' (NL* STATIC NL* '(' axiomMembers ')')? ;
axiomMembers: sep? (axiomMember (sep axiomMember)* sep?)? ;
axiomMember: (memberName | '*') IS axiomDefinition ;                  // a class's members are functions
```

* **Parsing :** after `is`, `fn` starts a function's definition, `class` a class's ; after `fn`, effect words and a signature are each optional, but not both missing (the analysis reports `Empty axiom : …`). One token decides each choice, and the grammar stays predicate-free. BIP007's `axiom pure on X.m` starts with an effect keyword, never a name, so it stays parseable while deprecated.
* **Effect words :** the usual ones, plus `view`, `shallow`, `fresh` and `foldable` (§3.3), which are identifiers, not keywords : the effect checker accepts them only in an axiom, so the language reserves no new word.
* **Class words :** after `class` — `exact` (the axioms hold for the class itself, not for its descendants), `nullmarked` (unannotated types in the signatures of the classes the axiom applies to are non-null) and `immutable` (instances are deeply immutable when their type arguments are). The former overlay null-marks `Object`'s own signatures without its subtypes', and the subtypes of `Iterable` without `Iterable`'s members applying to them : in this form, `Object`'s members state their nullness one by one, and the `Iterable` case is a simulation difference to review.
* **Descendants :** an axiom about a class or an interface, or one of its members, applies by default to all its descendants, including their overrides of the member : `axiom java.lang.Object.hashCode is fn pure` speaks of every `hashCode`. The most specific axiom wins — an axiom about `java.lang.String.hashCode` overrides `Object`'s for `String` and its subclasses —, and any axiom wins over what is inferred for a descendant's override. For a Babble class that overrides the member (directly, or through a Java interface it implements), the inherited axiom is a **contract** : the effect checker checks its implementation against it, as it checks an interface method's declared effects, and reports, in the style of the other effect diagnostics, `Function compareTo violates pure guarantee of axiom java.lang.Comparable.compareTo`. That is how §1's hole closes : `Evil.compareTo`, mutating its parameter, no longer satisfies `java.lang.Comparable.compareTo`'s axiom.
* **One target, one place :** a member is stated once, either by an axiom about it (`axiom java.util.List.add is …`) or as a member of its class's axiom, not both.
* **Where :** an axiom may be written anywhere — in the axioms directory and in programs, whose axioms take precedence over the built-in ones. It declares nothing at runtime : both backends evaluate it to `null`.
* **`axiom … on` :** BIP007's `axiom pure on X.m` is the former spelling of `axiom X.m is fn pure`, and is deprecated in favor of it, with a warning.

### 3.3 Mapping to what the analysis assumes

| Babble | Assumption |
|---|---|
| `fn (…): T` (pure by default) | pure, hermetic, deterministic |
| `fn volatile …`, `fn io …` | non-deterministic ; performs I/O |
| `fn nothrow …` | never throws, and needs no call check |
| `fn mut …` | mutates its receiver |
| `fn shared …` | keeps its receiver |
| unmarked, `shared`, `unique`, `mut` parameter | borrowed, kept, consumed, mutated |
| `T`, `T?`, a type variable `E` | non-null, nullable, as nullable as its argument |
| `p?: T` | optional parameter |
| `idempotent`, `commutative`… | algebraic properties |
| `view`, `shallow`, `fresh`, `foldable` | the result aliases the receiver ; a mutator touching no element ; a fresh result ; may be called at compile time |

* **Overloads :** a signature's parameter types map to Java's erasures — `int` to `int`, `string` to `String`, a type variable to its bound (`Object`), `list of T` to `List`, a Java class to itself — and the axiom describes the overload with those parameter types. Where Babble's types don't pin one down (`int` against `Integer`, `char` against `int`), the parameter is written with the Java type itself (`e: java.lang.Integer`) ; methods taking arrays, which Babble types cannot name, are left to the analyzer or to a future form.
* **Nullness, where a signature is given, is always stated :** a signature never leaves a position's nullness unspecified (`T` or `T?`) ; without a signature, nullness is inferred.
* **One profile :** an axiom states one profile, which the effect checker uses and the bytecode analyzer applies to the calls it meets ; where the former rules disagreed (`String.valueOf`), the simulation reports it.
* **Exactness :** some of the former rules' details have no surface form — a receiver borrowed unless the method is static, `Object`'s "deterministic where overridden", constructors that may not terminate. The nearest surface form is used, and every difference a test, a golden or a diagnostic can see is reported. Reads are plain `fn`s, their determinism decided at the call site (§4).

### 3.4 Files and loading

* **One file per package :** the curated axioms about the classes of a Java package are in a single file, `curated.ba`, in that package's directory of the axioms (`java/util/curated.ba` holds `Collection`, `List`, `Map`… ; `java/lang/curated.ba` holds `Object`, `String`, `Math`…), as axioms about its classes and their members. `AxiomsTestCase` checks that an axiom in `java/util/curated.ba` is about a class of `java.util`.
* **Sorted :** a `curated.ba` has one canonical form, so that it reads the same whoever wrote it and diffs only where an axiom changed :
  * its axioms are sorted by the class's binary name (`java.util.AbstractMap$SimpleEntry` after `java.util.AbstractMap`), a class's axiom before the axioms about its members, those by member name ;
  * in a class's axiom, the class words come in a fixed order (`exact`, `nullmarked`, `immutable`), and the members of each part are sorted by name (`*`, then `new`, first), then by their parameters' Java erasures, compared as type descriptors ;
  * the effect words come in the order the formatter writes them ;
  * the file is formatter-clean, and comments are only allowed above an axiom or a member, which keeps them attached when sorting ;
  * `AxiomsTestCase` fails on a file out of order.
* **Loading :** a `.ba` axiom file is parsed and type-checked before use, as `builtins.ba` is. Since each package has its file, named by the package, a class's axioms are loaded on first use — its package's `curated.ba`, then those of its supertypes' packages — rather than all at start-up ; the results are cached per package.

---

## 4. Reads of mutable state

"Deterministic" has meant deterministic given the state of what a function can reach, assuming nothing mutates it concurrently : `fn (c: Counter): int -> (c.n)` and `fn (xs: list of int): int -> (xs.size())` are plain pure functions. With threads, another thread may mutate `c` or `xs` between two calls. So **a read is deterministic only when the object read is frozen or deeply immutable, or owned exclusively by the reader** (a `unique` value, an object the function created itself) ; reading a mutable object through a borrowed or shared reference is volatile. This changes the effect model of BIP007 and BIP016 for Babble's own functions too, not only for axioms.

### 4.1 The model

* **Where determinism is decided :** at the call site, where the caller knows what it passes. A function's summary records which of its receiver and parameters it reads mutable state from (as it records which it mutates, `mut`, or keeps, `shared`) ; a call is deterministic only if each object so read is frozen or deeply immutable, or owned exclusively by the caller ; otherwise the call is volatile. Reading a mutable field of an outer binding is volatile already.
* **Composition :** a read through the caller's own parameter or receiver is not decided at that call : it becomes a read by the caller, recorded in the caller's summary, and is decided at the caller's call sites. A call is volatile only where the provenance runs out — an object reached from an outer mutable binding, a global, or anything else neither frozen, deeply immutable, exclusively owned, nor the caller's own parameter or receiver.
* **Reads are inferred :** what a function reads is inferred and recorded in module summaries for importers, with no surface syntax. A function type whose body the analysis doesn't see — an interface method, a function value, a Java method — is assumed to read every parameter and its receiver that are not frozen or deeply immutable.
* **Results are tracked :** a deterministic callee cannot reach shared mutable state, so what it returns is fresh or derived from its arguments and receiver — for a Babble function whose body isn't known, a Java method or constructor, or a core collection constructor (a fresh collection whose elements come from its arguments) ; only a `volatile` or `io` callee returns something untracked.
* **In axioms :** a Java read is written as a plain `fn` (`size is fn nothrow (): int`) ; the call site then decides, as for a Babble function. The former collection reads, hermetic and deterministic but not pure, need no word of their own.

### 4.2 Consequences

Functions reading a mutable object they are given become volatile only where their callers pass something that is neither frozen, deeply immutable, exclusively owned, nor the caller's own parameter or receiver. Tests, examples and goldens that relied on such reads being deterministic change, each named in the commit that changes it.

### 4.3 What the prototype found

Summaries carry the parameters and whether the receiver a function reads (`readParams`, `readsThis`), inferred like `mutParams` and `mutThis` ; member reads of classes and objects (not records), Java instance fields, Java calls, Babble calls and `for` loops record what they read. Since the model already made reading a mutable outer binding, or a variable an escaping closure captured, non-deterministic, a deterministic function reaches mutable objects only through its parameters, its receiver and what it creates : its reads are recorded and composed up the calls, and become volatile where provenance ends. In practice :

* without tracking results, higher-order code (`f(g(x))`, `list.add(f(x))`) became volatile — hence the rule on results above ;
* a copy of a borrowed list (`list(from: xs)`) carries its elements' provenance, so keeping it lets them escape : argon's `BVHNode.fromList` keeps its argument's hittables, so that parameter must become `shared` ;
* nothing else in the suite, the examples or argon changed.

---

## 5. The bytecode analyzer : inferring in the compiler

Most of what the analysis needs about JDK methods follows from their bytecode once the few things bytecode cannot tell are given : natives, interface contracts, the methods of elements. Those are the curated axioms ; the compiler infers the rest.

### 5.1 In the compiler, on by default

The analyzer — `BytecodeEffectAnalyzer`, grown as below — is tier 7 of the resolvers, and becomes **on by default** for every Java class, the JDK's and the host's alike. It runs lazily, on the methods a program actually calls and those they reach, within a budget, and caches its results in memory for the life of the JVM (one cache per class loader). There is no offline generator and no generated file : the cost is compile time, paid once per method per JVM, rather than generated code in the repository.

It comes **early** in the phases : right after the mechanism, the form of axioms and the reads model it infers into, and before any former rule is migrated, so that the curated axioms only cover what it cannot tell (§2.2).

* **Curated axioms override it :** a member stated in `curated.ba` is never inferred ; the analyzer applies it to the calls it meets. A switch (`babble.axioms.report=true`) prints, for the classes a compilation touched, every curated axiom the analyzer would infer as is or stronger — candidates for removal, to keep the set small.
* **Dumping what is inferred :** for inspection and debugging, the compiler prints what it would assume about a class or a whole package, in the form of §3 so that it reads like `curated.ba` and a member can be pasted into it :
  * `java -jar babble.jar -axioms java.util.ArrayList` for one class, `-axioms java.util` for every public class of a package (`-O` and the axioms in effect apply ; exit status 2 for a name that is neither) ;
  * `-Dbabble.dumpAxioms=java.util.ArrayList,java.util.function` while compiling a program, which prints to standard error, alongside `babble.dumpIr`, what the compilation assumed about those classes ;
  * each member says where it comes from — `;; curated`, `;; inferred` followed by the reasons (the curated axioms it rests on, what it writes, what escapes, where the budget ran out), or `;; curated, inferred the same` for a curated axiom the analyzer would infer as is, a candidate for removal ;
  * the output is in the canonical order of §3.4, so two dumps diff cleanly — across JDKs, or before and after a change to the analyzer.
* **Results depend on the JDK :** the analyzer reads the bytecode of the compiling JVM's classes, so a JDK update may change what is inferred. The suite runs on the baseline JDK (21, CI) and on the maintainer's JDK ; an expectation that differs between them is pinned by a curated axiom, which also documents the dependency. What the compiler infers only shapes its checks and optimizations, so a program compiled with `babblec` does not change when run on another JDK.
* **Determinism :** for a given JDK and set of curated axioms, the inference is the same whatever the order in which methods are reached (a fixed point, §5.2), so diagnostics do not depend on the order of compilation.

### 5.2 What the analyzer must learn

Today it is a conservative suggester : any field write clears `hermetic`, any open call gives up, almost nothing is `nothrow`. Inferring useful results needs :

* **a value lattice** on an ASM `Analyzer` : where a value comes from (`this`, parameter *i*, fresh, external, constant) and whether it may be null ;
* **mutation targets :** a write to `this` or to what it reaches makes the receiver `mut` ; to a parameter, that parameter ; to a fresh object, nothing ; to a static, not `hermetic` ;
* **reads :** which of the receiver and parameters a method reads mutable state from (§4) ;
* **escape :** a parameter stored in a field or a static is `shared`, one that is returned is returned through a borrow, any other is borrowed ;
* **return nullness :** always a `new` or a constant is non-null ;
* **shallowness :** no call reaches a stored element ;
* **calls through axioms :** a call applies the callee's curated axiom, or what was inferred for it. An open (virtual or interface) call is resolved only through the axiom of the method it names, whose contract then binds every implementation — the old hidden assumption, now a named curated axiom. Natives come only from curated axioms (`StrictMath.*`, `System.identityHashCode`, `System.nanoTime`…) ;
* **a fixed point** over the call graph, by strongly connected components, within a budget ; past the budget a method stays unconstrained, with the reason recorded.

### 5.3 What to expect

* **Good :** `Math` over `StrictMath`'s curated natives, the bit operations of `Integer`, `Long` and `Double`, much of `String` (pure and deterministic, but not `nothrow` : bounds checks), and the concrete core collections — `ArrayList.add` comes out hermetic, mutating its receiver, with `shared e`.
* **Through curated axioms :** `HashMap.put` and `HashSet.add` rely on `Object.hashCode` and `equals`, `TreeMap.put` on `Comparable.compareTo` or `Comparator.compare` ; what is inferred for them rests on these curated axioms, which is where §1's hole becomes a named, reviewable assumption.
* **Poor or impossible :** interfaces (`List.add` has no body : a curated axiom, or the join over a closed set of implementations), `nothrow` (almost anything can throw), the termination of loops, `invokedynamic` beyond string concatenation and lambdas, reflection, method handles and `Unsafe`.

---

## 6. Phases (`BIP019-P1`–`P8`, in this order)

Until the phase that migrates a former rule, the rule stays in the code, consulted after the axioms : axioms grow while the rules shrink, and each phase leaves the suite green.

| Phase | Deliverables |
|---|---|
| **`BIP019-P1` — Mechanism and the form of axioms** | the axioms directory and `README.md`, the `pom.xml` resource entry ; `BuiltinAxioms` loading each package's `curated.ba` on first use, with a cache (§3.4), and `BuiltinAxioms.EMPTY` ; `axiom <target> is <definition>` about Java classes and their members, with signatures, effect, member and class words (§3 : grammar, analysis passes, both backends, formatter) ; axioms applying to descendants, the most specific winning, and checked as contracts on Babble overrides ; the canonical order ; tier 6 of both resolvers, nullness, deep immutability and the optimizer ask `BuiltinAxioms` first. |
| **`BIP019-P2` — Reads of mutable state** | §4 : summaries record the receiver and parameters a function reads mutable state from ; a call is deterministic only on frozen, deeply immutable or exclusively owned arguments ; results of deterministic callees are tracked ; tests, examples (argon's `fromList`) and goldens updated. The analyzer infers in this model. |
| **`BIP019-P3` — The analyzer in the compiler** | §5 : the value lattice, reads, calls through axioms, the fixed point, the budget and the in-memory cache ; tier 7 on by default, under the curated axioms ; `babble.axioms.report` ; dumping what is inferred for a class or a package (`-axioms`, `babble.dumpAxioms`), with `MainTestCase` and golden tests ; the simulation against the former rules (§2.2). |
| **`BIP019-P4` — Effects** | curated axioms in `java/lang/curated.ba` and `java/util/curated.ba` (protocol methods, interface contracts, `QUESTIONABLE` where a doubtful rule is kept), the rest inferred ; the simulation of the effect rules reviewed ; `standardProtocolDefault`, the `COLLECTION_*` and `STRING_*` sets, the constructor rules, the shallow and view rules and `evalFor`'s case deleted. |
| **`BIP019-P5` — Optimizer** | `SEEDED_INTRINSICS` becomes curated axioms for the natives (`StrictMath`…), the rest inferred ; `HirPurity`'s four lists become `foldable` and `idempotent` curated axioms ; `ConstFoldPass` asks `BuiltinAxioms`. |
| **`BIP019-P6` — Nullness and immutability** | `JavaNullability`'s overlay becomes `nullmarked` classes and the nullness of members' types, inferred where the analyzer can tell (a result always `new` or constant), curated elsewhere ; `Map.Entry`'s deep immutability becomes `immutable` ; the meaning of third-party nullness annotations becomes one Java stub per annotation, and `JavaNullability` loses its name lists. |
| **`BIP019-P7` — Built-ins** | optional parameters in function types ; axioms about Babble names (`axiom int is fn (value: any): int`), a core scalar keyword included ; `builtins.ba` ; `OperatorTypes` reads the prelude ; diagnostics keep their wording. |
| **`BIP019-P8` — Enforcement and docs** | `AxiomsTestCase` (§7) ; `-format -check` covers the axioms ; `axiom … on` deprecated in favor of `axiom X.m is fn …` ; AGENTS.md gets the rule "a new assumption goes in the axioms directory" ; README, BIP007 §4 and BIP011 §6.4, §8.2 point to the axioms. |

## 7. Verification

* After each phase : the full suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`), on JDK 21 and on the maintainer's JDK, plus `-Pbatest` where effects or nullness are covered, and argon compiled with `babblec` rendering `scene.ppm` byte for byte. Expectations change only where a phase says so (`BIP019-P2`'s reads model) or where a simulation difference was accepted, and the commit says which.
* The simulation (§2.2) : for each migrated rule, no difference with the former rules on `java.base` that was not reviewed and accepted.
* `AxiomsTestCase` :
  1. every curated axiom about a member matches a real executable (by name, and by erased parameters where it has a signature), no member is stated twice, the axioms of a package's `curated.ba` are about that package's classes, and the files are in canonical order — stale or mistyped axioms fail ;
  2. **with `BuiltinAxioms.EMPTY` and the bytecode analysis off, the analysis knows nothing** : `String.length`, `List.add`, `Math.sqrt` and `Map.get` are unconstrained and of unspecified nullness, nothing is foldable, the built-ins are unknown — which proves that no assumption survives anywhere else ; with the analysis on, what it infers rests on the curated axioms alone ;
  3. the axioms' README lists every file and every `QUESTIONABLE` axiom.
* Dumps : golden dumps of a few classes (`java.lang.Math`, `java.util.ArrayList`) on the baseline JDK, which also show in review what a change to the analyzer or to the curated axioms changes.
* Compile time : the benchmarks (`org.babblelang.bench.Benchmarks`) and the suite's duration, before and after `BIP019-P3`, show what the inference costs.
* §1's example behaves as today until its `QUESTIONABLE` axiom is fixed, as its own change.
