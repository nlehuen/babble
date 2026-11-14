# Design: function and method purity (`pure`, `idempotent`, and `impure`)

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-11-12 |
| **Depends on** | [bytecode-compiler.md](bytecode-compiler.md): block scope (§8.1), the shared runtime (§5), `invokedynamic` call sites (§18); [object-model.md](object-model.md): classes, methods, `this`, Java interop; [type-system.md](type-system.md): function types (T4) and runtime boundary checks (T9) |
| **Unblocks** | Effect sandboxing / security auditing, automatic parallelization (`map`, `forEach` / CRDT accumulation, raytracer scanlines), dataflow reordering, safe retries, memoization/caching, and compiler optimisations (common subexpression elimination, loop-invariant code motion) |

## 1. Summary

### 1.1 Motivation: security, parallel accumulation, and performance

Purity in Babble serves three complementary goals:

1. **Security (side-effect isolation and capability auditing).**
   We want a language-enforced guarantee that a piece of code is completely free of observable side effects: it cannot mutate the objects passed to it (nor `this`), cannot modify global or outer state, and cannot perform arbitrary OS system calls (file writes, network I/O, process execution, `System.exit`) through Java interop. Because `pure` functions can only call other `pure` or `trusted_pure` functions, auditing the security surface of a `pure` entry point reduces to auditing the small, explicit set of `trusted_pure` functions in the codebase (where benign system calls, such as reading a configuration file, are deliberately blessed).
2. **Performance (reorderable dataflow graphs and trivial parallelization).**
   Because a `pure` function has no side effects and leaves its arguments and `this` untouched, independent calls to `pure` functions have no hidden ordering constraints or data races. A `pure` computation can therefore be represented as a **dataflow graph of reorderable and parallelizable operations**:
   * **Trivial data parallelism:** Mapping a `pure (x: T) : U` function over a list, or evaluating `pure` pixel/ray shaders across an image grid, can be partitioned across worker threads without locks or defensive copying.
   * **Graph optimisations:** The compiler or optimizer can freely reorder independent pure calls, hoist loop-invariant pure calls (`while i < n then ( … pureFn(constArg) … )`), eliminate duplicate pure subexpressions (CSE), or memoize results.
3. **Convergent state updates and retry safety (`idempotent`).**
   Many practical operations *do* mutate shared state (such as adding keys to a set/CRDT, setting a monotonic latch, or calling `Files.createDirectories`), yet executing them multiple times or in any order relative to other idempotent methods on the same object yields the **exact same final state**. Tracking **`idempotent`** as a middle tier between `pure` and `impure` unlocks parallel accumulation (`parallelForEach`) and automatic retries for workflows where strict `pure` immutability would be too restrictive.

### 1.2 Overview

Babble models function and method purity as a **3-tier subtyping lattice**:

```
pure (A) : B   <:   idempotent (A) : B   <:   (A) : B   (impure default)
```

through `pure`, `trusted_pure`, `idempotent`, `trusted_idempotent`, and `impure` (with `value class` constructors/instance methods and standard value protocol methods `equals`, `hashCode`, `compareTo`, `toString` implicitly `pure` by default):

```
;; In a value class, the constructor and instance methods are implicitly pure:
def Vec3 = value class (x: double, y: double, z: double) (
    def dot = (other: Vec3) : double -> (
        this.x * other.x + this.y * other.y + this.z * other.z
    )
    def plus = (other: Vec3) : Vec3 -> (
        Vec3(this.x + other.x, this.y + other.y, this.z + other.z)
    )
    ;; A value class method can still opt out with `impure` when doing I/O:
    def dump = impure () -> ( println(this) )
)

;; Standalone functions and regular class methods opt into purity with `pure` or `idempotent`:
def clamp = pure (x: double, lo: double, hi: double) : double -> (
    if x < lo then ( lo ) else ( if x > hi then ( hi ) else ( x ) )
)

;; Blessing specific Java methods directly:
trusted_pure java.lang.Math.sqrt
trusted_pure java.nio.file.Files.readString
trusted_idempotent java.nio.file.Files.createDirectories

;; Composed idempotent function: calls pure helpers + trusted_idempotent ops
def ensureDirAndTrack = idempotent (seen: TagSet, dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    seen.addTag(dir)   ;; an idempotent method on TagSet
)

;; Or wrapping a multi-step sequence of impure operations in a trusted_pure Babble function:
def readTrimmedScene = trusted_pure (path: string) : string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(path)).trim()
)
```

* **`pure` guarantees no side effects and untouched inputs.** A function or method marked `pure` leaves all of its arguments, its receiver `this`, and all state outside its own activation untouched. It cannot mutate fields of `this`, parameters, or outer objects, cannot assign to outer variables (`outer = …`), and cannot read mutable outer state (variables, mutable `class` / `object` / static fields, or non-`final` Java static fields).
* **`idempotent` sits strictly between `pure` and `impure`.** An `idempotent` function can perform `pure` operations and call other `idempotent` / `trusted_idempotent` operations whose repeated execution and call ordering on the same target object(s) converge to the exact same state (like ACI/CRDT accumulators, monotonic latches, or `Files.createDirectories`). Because an `idempotent` call *can* mutate arguments or external state, `pure` functions **cannot** call `idempotent` functions—while `idempotent` functions **can** call `pure` functions (`pure <: idempotent <: impure`).
* **`value class`es and standard value methods are implicitly `pure` (and can opt out with `impure`).** Because a `value class` is an immutable value type, its constructor and instance methods are implicitly `pure` by default, as are `equals`, `hashCode`, `compareTo`, and `toString` on any Babble class or Java object. Any of those default-pure methods can explicitly opt out with `impure` (`def toString = impure () -> ( … )` or `impure java.net.URL.equals`), whereas `static (...)` blocks on a `value class` remain opt-in `pure`.
* **Viral call rule (including implicit operator calls and Java callbacks).** A `pure` function can only call `pure` / `trusted_pure` targets; an `idempotent` function can only call `pure` / `trusted_pure` / `idempotent` / `trusted_idempotent` targets (never `impure` ones). This covers explicit calls, pure/idempotent constructors, inline `object`/`static` initializers, callbacks passed to Java (`Faces`), and implicit value methods (`==`/`!=`, `<`/`<=`/`>`/`>=`, `"…" + obj`).
* **`trusted_pure` and `trusted_idempotent` are the explicit trust boundaries.** Both can be applied either:
  1. **Directly on a Java class or Java method** (`trusted_pure java.lang.Math.sqrt`, `trusted_idempotent java.nio.file.Files.createDirectories`, `export trusted_pure …`) **without writing a Babble wrapper function**.
  2. **On a Babble function or method literal** (`trusted_pure (params) -> ( body )` or `trusted_idempotent (params) -> ( body )`) to satisfy the `pure` or `idempotent` contract externally while encapsulating internal mutation, thread-safe CRDT updates, or trusted syscalls inside `body`.
* **Local variables and locally constructed objects stay mutable.** Both `pure` and `idempotent` functions can declare and reassign local variables (`def i = 0 ; while i < n then ( i = i + 1 )`), and can mutate fields `v.x = value` when `v` is provably a locally constructed object that never aliases a parameter or `this`.
* **Static + runtime enforcement in both modes.** As with `private` and `value class`, purity violations that can be seen statically are rejected during analysis (`BlockScopes` and `TypeChecks`), and dynamic call sites inside `pure` and `idempotent` functions check `Callable.purity(runtime)` at runtime (`invokedynamic` guards check it once at linkage time, with zero per-call overhead on fast paths).

### 1.3 Prior art in other languages

Babble's `pure` and `trusted_pure` design draws directly from several languages that combine imperative local control flow with checked purity and an explicit trust escape hatch:

| Language | Pure declaration | Argument / `this` protection | Local mutation | Trust escape hatch (`trusted_pure`) | Primary use cases |
|---|---|---|---|---|---|
| **Fortran (95–2023)** | `pure function` | All arguments of a `pure function` must be `intent(in)` (read-only); no global/module writes; no `save` variables; no external I/O. | Allowed (`do` loops, local variables). | None in standard Fortran (requires wrapping via `impure elemental` or C interop). | Parallel loops (`do concurrent`, `forall`) on multi-core CPUs and GPUs; expression reordering. |
| **Nim** | `func` (alias for `proc {.noSideEffect.}`) | With `--experimental:strictFuncs`, parameters (and references reachable from them) cannot be mutated unless marked `var`. Cannot access global state. | Allowed (`var` locals and `while`/`for` loops). | `{.cast(noSideEffect).}: …` block pragma. | Concurrency safety, deterministic evaluation, wrapping benign C/OS calls. |
| **D** | `pure` attribute (`pure int f(int x)`) | **Weakly pure** forbids global/static state and I/O; **strongly pure** additionally requires `const`/`immutable`/value parameters and `this`. | Allowed (local variables and calls to weakly pure helpers on local state). | `cast(pure …) &fn` function-pointer cast (`@trusted`). | CSE, memoization (`std.functional.memoize`), safe parallel execution, implicit conversion of return values to `immutable`. |
| **Flix (JVM)** | Default (`\ {}` empty effect vs `\ IO`) | Immutable data types; Java objects live in `IO` or region effects. All Java methods default to impure (`\ IO`). | Via scoped regions (`region rc { … }`). | `unchecked_cast(expr as _ \ {})` ("effect laundering"). | Bridging pure functional pipelines with JVM libraries (`java.lang.Math`, benign JDK methods). |
| **Koka** | `total` (`<>` empty effect row) | Parameters are immutable values; heap writes require the `st<h>` effect. | Local `var x := …` is encapsulated by the `st<h>` handler and discharged as `total` if it does not escape. | `unsafe-total(fn)` in `std/core/unsafe`. | Effect-safe programming with familiar imperative local loops. |
| **Haskell** | Default (any non-`IO` type `a -> b`) | All values immutable by default. | Via `Control.Monad.ST` (`runST`) for encapsulated local mutable variables/arrays. | `System.IO.Unsafe.unsafePerformIO` | Referential transparency, aggressive GHC rewrite rules, parallel strategies (`parMap`). |
| **Ada / SPARK** | `pragma Pure_Function` / `Global => null` | Function parameters are `in` mode (read-only); SPARK proves absence of side effects and aliasing. | Allowed (local variables inside the function). | `pragma Annotate (GNATprove, Intentional, …)` | High-assurance security/safety verification and compiler CSE. |
| **GCC / Clang C** | `__attribute__((const))` / `__attribute__((pure))` | Unchecked by the C compiler; UB if the function mutates visible state. | Allowed. | The attribute itself is unchecked (`trusted_pure` by nature). | Optimizer hints (CSE, dead-call elimination, loop-invariant code motion). |

**Where Babble sits in this design space:**
* Like **Fortran's `pure function`** and **Nim's `func` + `strictFuncs`**, Babble makes purity **opt-in** on functions and methods, keeps **local mutable variables** (`def i = 0 ; while …`) ergonomic, and **forbids mutating arguments or `this`** (equivalent to D's *strong purity* and Fortran's `intent(in)`).
* Like **Flix on the JVM**, Babble treats **all un-wrapped Java calls as impure by default** because the JVM verifier does not track side effects or OS system calls, and provides **`trusted_pure`** (analogous to Nim's `{.cast(noSideEffect).}`, Flix's `unchecked_cast(... as _ \ {})`, Koka's `unsafe-total`, and Haskell's `unsafePerformIO`) at the function boundary so that every trusted Java/OS call is declared in one auditable place.

### 1.4 Controlling side effects: `pure` vs. `const`/`mut`, monads, and effect systems

Languages control mutation and side effects along a spectrum—from **read-only access paths** (C++ `const`), through **ownership and aliasing** (Rust `mut` / `&T`), to **effect-typed functional systems** (Haskell `IO`/`ST` monads, Koka and Flix algebraic effects). Viewing Babble alongside both ends of that spectrum clarifies what `pure`, `trusted_pure`, and `value class` do—and intentionally do not—guarantee.

| Dimension | C++ `const` (`const T&`, `f() const`) | Rust `&T` vs `let mut` / `&mut T` | Haskell (`a -> b` vs `IO` / `ST`) | Algebraic Effects (Koka / Flix) | Babble `value class` + `pure` / `trusted_pure` |
|---|---|---|---|---|---|
| **What is constrained** | **Access path / view** (read-only reference or `this`) | **Ownership & aliasing** (shared `&T` vs exclusive `&mut T`) | **Default pure functions**; side effects live in `IO` / `ST` types | **Function signatures** carry fine-grained effect rows (`<io, st<h>>`) | **Split:** `value class` = immutable fields; `pure` = zero observable effects |
| **Protects `this`, args & aliases?** | **Partial** (shallow on pointers; non-`const` aliases can still mutate) | **Yes** (`&T` forbids `&mut T` aliases, except `Cell`/`RefCell`/`Mutex`) | **Yes** (all values immutable by default) | **Yes** (heap mutation requires `st<h>` / region effect) | **Yes** (`value class` fields read-only; `pure` forbids all non-fresh writes) |
| **Blocks globals & outer mutable state?** | **No** (can read/write globals and `static`s freely) | **No** (`&T` functions can mutate atomics, `Mutex`, `thread_local!`) | **Yes** outside `IO` (`IORef` stays inside `IO`) | **Yes** unless effect row includes heap/global effect | **Yes** (cannot write outer `def`s or read mutable outer `class`/`static`/Java state) |
| **Blocks OS syscalls & I/O?** | **No** (`std::cout`, files, network allowed in `const`) | **No** (`println!`, files, network allowed without `mut`) | **Yes** outside `IO` | **Yes** unless `io` / `IO` is in effect row | **Yes** (unblessed Java calls and OS syscalls rejected) |
| **Local mutation** | Allowed (`int i = 0;`, plus `mutable` fields) | **Allowed** (`let mut i = 0` stays private to caller) | Encapsulated via `Control.Monad.ST` (`runST`) | Scoped regions (`st<h>`, `region rc { … }`) | **Direct** (`def i = 0`, `while`, fresh local objects `b.w = w`) |
| **Higher-order composition** | `const` propagates on types; overloading/casting friction | Orthogonal to effects (`Fn` vs `FnMut` tracks capture mutation only) | Separate variants (`map` vs `mapM`) or explicit lifting (`pure . f`) | Effect-polymorphic (`map` preserves callback effect row `e`) | **Subtyping** (`pure (A) : B <: (A) : B`): one `map` fits both; `parMap` requires `pure` |
| **Evaluation & parallelization** | Strict; **not** safe to parallelize/reorder from `const` alone | Strict; data-race freedom (`Send`/`Sync`), **not** syscall/I/O purity | **Lazy**; referential transparency enables rewrite rules & `parMap` | Strict; empty effect row enables safe reordering/parallelism | **Strict**; `pure` guarantees no shared writes or syscalls, enabling parallel/reordered execution |
| **Escape hatch** | `const_cast`, `mutable` fields anywhere | `unsafe`, interior mutability (`Cell`, `RefCell`, `Mutex`) | `unsafePerformIO` (needs `NOINLINE` care under laziness) | `unsafe-total` (Koka) / `unchecked_cast(... as _ \ {})` (Flix) | `trusted_pure` (scoped to Babble function or Java class/method/`@Pure`) |

How Babble resolves the trade-offs between these models:

1. **Access qualifiers (`const` / `mut`) do not stop side effects; `pure` does:**
   C++ `const` and Rust `&T`/`&mut T` attach to **references**, not to side effects. A C++ `int f(const Foo& x) const` method can still mutate globals, follow non-`const` pointer members, lose to a mutable alias of `x`, or write to disk; Rust's XOR-mutability solves aliasing (`&T` cannot alias `&mut T`) and mirrors Babble's local mutation via private `let mut`, yet a Rust `fn render(doc: &Doc)` with zero `mut` arguments can still perform I/O or lock a `Mutex`. (Rust's `const fn` and C++ `constexpr` move toward effect control, primarily for compile-time evaluation.) Babble instead separates **data immutability** (`value class`) from **computation purity** (`pure`): a `pure` call protects `this`, parameters, aliases, outer mutable state, and OS/Java calls in one boundary.

2. **Opt-in binary `pure` + subtyping avoids Haskell's monad split and effect-row complexity:**
   Haskell makes purity the default and puts effects into monads (`IO`, `StateT`), forcing higher-order APIs to split (`map` vs `mapM`) or lift pure functions (`pure . f`); algebraic effect systems like Koka and Flix solve that with effect-polymorphic rows (`e`). Babble takes a lighter, impure-by-default route suited to optional static + dynamic typing: **one binary bit** (`pure` vs. impure) combined with function subtyping **`pure (A) : B <: (A) : B`** (§7.1). Existing code keeps working unchanged, a single `list.map(fn)` accepts both `pure` and impure callbacks without lifting or effect variables, parallel/sandbox APIs like `parMap` can require `pure (A) : B`, and dynamic calls verify `Callable.isPure(runtime)` cheaply at linkage time.

3. **Private local mutation without `ST` / `runST` boilerplate:**
   Like Rust's local `let mut` and Haskell's `Control.Monad.ST` (`runST`), Babble recognizes that mutating private local state is observationally pure. Instead of requiring monadic `do` blocks and rank-2 region types (`forall s. ST s a`), Babble lets `pure` functions use ordinary `def` reassignments, `while` loops, and field writes on freshly constructed local objects (`def b = Box(0); b.w = w`), rejecting writes only when the receiver can alias `this`, a parameter, or outer state (§4.1–§4.2). Returned values that must stay deeply immutable outside the call rely on `value class`.

4. **Strict evaluation + explicitly scoped trust boundaries (`trusted_pure`):**
   Unlike Haskell—where lazy evaluation makes `unsafePerformIO` sensitive to compiler inlining and evaluation order—Babble is **strict (call-by-value)**. Purity does not introduce laziness; it grants the runtime permission to reorder independent subexpressions or parallelize `pure` iterations. Rather than scattering C++ `mutable` fields, `const_cast`, or ad-hoc FFI `unsafePerformIO` wrappers through code, Babble places every trust exception at explicit, grep-able boundaries via **`trusted_pure`** on Babble functions, Java classes/methods (`trusted_pure java.lang.Math.sqrt`), or `@Pure` annotations (§5.2)—with the single requirement that any `trusted_pure` cache or Java helper remains thread-safe if invoked concurrently by `pure` callers.

### 1.5 Key design lessons from C++, Rust, Haskell, LISP, and imperative-pure languages

Distilling what worked (`+`) and what caused friction (`-`) in earlier languages directly shaped Babble's rules:

* **C++ (`const`, `mutable`, `constexpr`, `[[gnu::pure]]`):**
  * `(-)` **`const` is not purity** (shallow on pointers, bypassed by aliases/globals/I/O) and triggers `const` vs. non-`const` method duplication without subtyping.
  * `(-)` **Unchecked purity hints** (`[[gnu::pure]]` / `__attribute__((const))`) silently cause UB when wrong.
  * `(+)` **`constexpr`** showed developers readily adopt pure boundaries once ordinary local imperative loops are allowed.
* **Rust (`&T` / `&mut T` / `mut`, `const fn`):**
  * `(+)` **Alias control** (`&T` cannot alias `&mut T`) plugs the mutation leak left open by C++ `const`, and private `let mut` keeps local loops ergonomic.
  * `(-)` **Zero `mut` still permits I/O and interior mutability** (`Cell`/`RefCell`/`Mutex`/atomics), so ownership alone does not guarantee sandboxing or reordering; meanwhile tying purity to compile-time `const fn` limits runtime use.
* **Haskell (`IO` / `ST`, laziness, `unsafePerformIO`):**
  * `(+)` **`ST` / `runST`** proved encapsulated local mutation preserves referential transparency, enabling aggressive fusion and parallelism (`parMap`).
  * `(-)` **Incompatibility between pure and `IO` types** splits libraries (`map` vs `mapM`) and forces lifting/transformer stacks; lazy thunks also make `unsafePerformIO` ordering subtle without `{-# NOINLINE #-}`.
* **LISP / Scheme / Clojure (`set!`, persistent data, `transient`):**
  * `(+)` **Immutable-by-default data + locally scoped mutable builders** (Clojure's `transient`/`persistent!` on the JVM) delivers both safety and allocation efficiency—mirroring Babble's `value class` paired with fresh mutable local objects inside `pure` functions.
  * `(-)` **Purity by convention alone** (Common Lisp / Scheme) cannot support optimizer or sandbox guarantees, and **purely static analysis fails on dynamic dispatch**—hence Babble pairing static checks with a fast `Callable.isPure(runtime)` guard at linkage time.
* **Fortran / D / Nim / Koka / Flix:**
  * `(+)` **Opt-in imperative purity + explicit host escape hatches** (`trusted_pure`) capture functional optimization/security wins without forcing functional-only style.
  * `(-)` **Annotation noise ("attribute soup", e.g., D's `pure @safe nothrow`)** fatigues users unless smart defaults (implicit purity on `value class` methods), function subtyping, and direct host blessing (`trusted_pure java.lang.Math`) minimize ceremony.

### 1.6 Related effect models: volatility, determinism, capabilities, and monotonic lattices

Other languages and type systems split purity and idempotency along finer axes that help clarify what Babble includes in Phase 1 versus what it deliberately keeps simple:

| Model / System | Categories | Closest match in Babble | What it separates that Babble folds or defers |
|---|---|---|---|
| **PostgreSQL** (function volatility) & **GCC/Clang** (`__attribute__((const))` / `((pure))`) | `IMMUTABLE` ($\approx$ GCC `const`) / `STABLE` ($\approx$ GCC `pure`) / `VOLATILE` | `pure` / (`trusted_pure` read) / `impure` | Splits *no writes AND no external reads* (`IMMUTABLE`) from *no writes, reads snapshot/external state* (`STABLE`). |
| **Java Checker Framework** | `@SideEffectFree`, `@Deterministic`, `@Pure` (`@SideEffectFree` + `@Deterministic`) | `pure` ($\approx$ `@Pure`) vs. `impure` | Explicitly separates *no visible mutation* (`@SideEffectFree`, e.g. `System.currentTimeMillis()`, `Files.readString`) from *same result on identical inputs* (`@Deterministic`). |
| **D** (strong vs. weak purity) | `pure` with immutable params (*strong*) vs. `pure` with mutable params (*weak*) | `pure` (strong) vs. future `mut` helper params | Weak purity lets a `pure` helper mutate caller-supplied local/fresh arguments without touching globals, easing structured initialization. |
| **Pony** (reference capabilities) | `val`, `iso`, `box`, `ref`, `trn`, `tag` | `value class` ($\approx$ `val`), `pure` | Encodes immutability (`val`), read-only alias (`box`), and unique ownership (`iso`) in *reference types* so concurrency safety follows without function-level purity tags. |
| **Bloom / CALM**, **LVars / LVish**, **Datafun** | Monotonic lattice writes + explicit `freeze` / barrier | `idempotent` / `trusted_idempotent` + post-barrier read | Exact theoretical foundation for `idempotent`: parallel writes form an Associative, Commutative, Idempotent (ACI) join-semilattice; non-monotonic reads must wait behind a barrier. |

**Takeaways for Babble:**
1. **`idempotent` sits squarely in the CALM / LVars lineage:** Treating `idempotent` effects as ACI / monotonic joins explains both *why* parallel order does not matter and *why* branching on live mutable state (`if set.size() == 0 ...`) breaks commutativity unless deferred past a phase barrier.
2. **Keep Phase 1 to three tiers (`pure <: idempotent <: impure`):** Splitting `pure` into separate `readonly` (`@SideEffectFree`) and `deterministic` (`@Deterministic`) keywords, or adding `nothrow` / Pony capabilities, would recreate D's "attribute soup." Instead, Babble documents the exact contract boundaries in [§4.8](#48-boundary-clarifications-determinism-associative-reductions-exceptions-and-capabilities) and keeps `readonly` / `mut` params as compatible future refinements.

---

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| P1 | Where modifiers are written | **On function literals, function types, and Java class/method references; implicit on `value class` instance methods and standard value protocol methods.** `pure`, `trusted_pure`, `idempotent`, `trusted_idempotent`, and `impure` before `(params) -> ( body )` for literals; `pure (params) : R` and `idempotent (params) : R` for function types; `[export] (trusted_pure \| trusted_idempotent \| impure) <javaTarget>` for Java methods/classes (§3, §4.4, §5.2). |
| P2 | How arguments and `this` are kept untouched | **Inside `pure` (and checked `idempotent`) bodies, only locally constructed fresh objects (`v.x = …`) may have direct fields mutated.** Field assignments on `this`, parameters, outer objects, or any non-fresh receiver are forbidden; controlled ACI state mutation is encapsulated inside `trusted_idempotent` (§4.1, §4.6). |
| P3 | Local vs outer variables and fields | **Local `def`s inside `pure` / `idempotent` functions may be reassigned; outer mutable state may not be written or read.** Assigning outer `def`s, reading reassigned outer `def`s, reading non-`final` Java static fields (unless blessed), or reading/calling through any field of an outer mutable `class`, `object` singleton, `static` field, or `JavaObject`—even transitively through an outer `value class`—is forbidden (§4.2). |
| P4 | Calling rule, inline initializers, & implicit value methods | **3-tier transitive call check: `pure` calls only `pure`; `idempotent` calls `pure` or `idempotent`.** Applies to regular calls, inline `object`/`static` blocks, Java callbacks (`Faces`), and default-pure value methods (`equals`, `hashCode`, `compareTo`, `toString`), which are pure by default unless marked `impure` (§4.3, §4.4, §4.6, §5.2). |
| P5 | Java interop and OS calls | **Arbitrary Java methods/constructors are impure by default and annotated via `[export] trusted_pure` or `[export] trusted_idempotent`; default-pure value methods (`equals`, `hashCode`, `compareTo`, `toString`) can opt out via `[export] impure`.** Supported in Babble syntax, `@Pure` / `@Idempotent` / `@Impure` Java annotations, and `BabbleScriptEngine.trustPure(...)` / `trustIdempotent(...)` / `markImpure(...)` (§5.2). |
| P6 | Built-in natives | **`same`, `classof` and `assert` are pure; `print`, `println` and `import` are impure** (§5.1). |
| P7 | `value class`es and class constructors | **`value class` constructors and instance methods are implicitly `pure` by default** (instance methods may opt into `trusted_pure`, `idempotent`, `trusted_idempotent`, or `impure`; `static` blocks remain opt-in). A regular `class` constructor's purity equals the minimum purity of its parameter defaults and class body (§4.4). |
| P8 | Interfaces and overrides | **A `pure` interface member must be implemented/overridden by `pure`; an `idempotent` interface member must be implemented/overridden by `pure` or `idempotent`.** Covariance applies across all three tiers (`pure <: idempotent <: impure`) (§6). |
| P9 | Function types and subtyping | **`pure (A) : B <: idempotent (A) : B <: (A) : B`, not the reverse.** `trusted_pure` has static type `pure (A) : B`; `trusted_idempotent` has static type `idempotent (A) : B`; `impure` is a definition-site opt-out with static type `(A) : B` (§7). |
| P10 | Nested functions inside a `pure` / `idempotent` function | **Each function literal has its own purity mode.** Creating a less-pure closure inside a `pure` or `idempotent` function is allowed (provided it does not mutate the enclosing function's inputs or `this`), though the enclosing function cannot call it or pass it to Java via `Faces` at a stricter purity boundary (§4.7, §5.2). |
| P11 | `idempotent` contract (ACI + barrier) | **Checked `idempotent` functions enforce the `pure` mutation/read guards while allowing `idempotent` / `trusted_idempotent` calls.** Primitive idempotent-commutative & thread-safe operations (CRDT accumulators, monotonic latches, `Files.createDirectories`) are marked `trusted_idempotent`; readers observe the mutated target state only after a barrier (§4.6). |

---

## 3. Syntax and Grammar

### 3.1 Function literals, `value class` methods, `idempotent`, `trusted_*`, and `impure`

`pure`, `trusted_pure`, `idempotent`, `trusted_idempotent`, and `impure` appear immediately before a function literal's parameter list; on a `value class`, instance methods (and on any class, `equals`, `hashCode`, `compareTo`, and `toString`) are implicitly `pure` unless overridden with a modifier:

```
;; Standalone pure and idempotent functions
def clamp = pure (x: double, lo: double, hi: double) : double -> (
    if x < lo then ( lo ) else ( if x > hi then ( hi ) else ( x ) )
)

;; In a value class, instance methods (area, scale) are implicitly pure;
;; `impure` opts a method out when it performs I/O; `static` remains opt-in:
def Circle = value class (r: double) (
    def area = () : double -> ( 3.141592653589793 * this.r * this.r )
    def scale = (factor: double) : Circle -> ( Circle(this.r * factor) )
    def log = impure () -> ( println(this) )
) static (
    def unit = pure () : Circle -> ( this(1.0) )
)

;; Standard value methods (`toString`, `compareTo`, `equals`, `hashCode`) are pure by default
;; on regular classes too; mark `impure` explicitly when they have side effects:
def Instrumented = class (def name: string) (
    def toString = impure () : string -> (
        println("rendering " + this.name)
        this.name
    )
)

;; Blessing specific Java methods directly:
trusted_pure java.lang.Math.sin
export trusted_pure java.lang.Math.cos
def sqrt = trusted_pure java.lang.Math.sqrt
trusted_idempotent java.nio.file.Files.createDirectories
impure java.net.URL.equals

;; Checked idempotent function composing pure + trusted_idempotent steps:
def ensureDirAndRecord = idempotent (seen: TagSet, dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    seen.addTag(dir)
)

;; Trusted-idempotent Babble wrapper around an ACI / thread-safe mutation:
def TagSet = class (private def set = java.util.concurrent.ConcurrentHashMap.newKeySet()) (
    def addTag = trusted_idempotent (tag: string) -> (
        this.set.add(tag)
        null   ;; return null so repeated calls cannot leak "wasAdded" delta
    )
)

;; Trusted-pure Babble function around a multi-step Java / OS read
def readLines = trusted_pure (path: string) -> (
    java.nio.file.Files.readAllLines(java.nio.file.Path.of(path))
)
```

### 3.2 Function types and interface requirements

In type annotations (`type`), `pure` or `idempotent` prefixes a function type:

```
;; Higher-order pure function taking a pure callback
def integrate = pure (f: pure (x: double) : double, a: double, b: double) : double -> (
    (f(a) + f(b)) * (b - a) / 2.0
)

;; Parallel phase API requiring only an idempotent (not necessarily pure) callback
def retry3 = idempotent (step: idempotent () : bool) : bool -> ( ... )

;; Interface with pure and idempotent required/default members
def Accumulator = interface (
    def mergeTag : idempotent (tag: string)
    def size : () : int
)
```

`trusted_pure`, `trusted_idempotent`, and `impure` do **not** appear in type annotations (`trusted_pure is not allowed in a type annotation` / `trusted_idempotent is not allowed in a type annotation` / `impure is not allowed in a type annotation`): callers only distinguish the three contract levels `pure (A) : B`, `idempotent (A) : B`, and `(A) : B`.

### 3.3 Grammar additions (`Babble.g4`)

In `src/main/antlr4/org/babblelang/parser/Babble.g4`:

```antlr
expression:
          ...
          | purity=(PURE | TRUSTED_PURE | IDEMPOTENT | TRUSTED_IDEMPOTENT | IMPURE)?
                parametersDeclaration ( ':' type | ) '->' NL* functionBlock=block   # functionLiteral
          | EXPORT? purity=(TRUSTED_PURE | TRUSTED_IDEMPOTENT | IMPURE) target=expression   # purityExpression
          ...
          ;

type: ID ('.' ID)*                                                     # simpleType
    | purity=(PURE | IDEMPOTENT)? parametersDeclaration ( ':' type | ) # functionType
    ;

PURE: 'pure';
TRUSTED_PURE: 'trusted_pure';
IDEMPOTENT: 'idempotent';
TRUSTED_IDEMPOTENT: 'trusted_idempotent';
IMPURE: 'impure';
```

Why prefix the function literal (`pure` / `trusted_pure` / `idempotent` / `trusted_idempotent` / `impure (a, b) -> (…)`) and target expression (`[export] (trusted_pure | trusted_idempotent | impure) <javaTarget>`) rather than `def`:

1. **Anonymous functions and callbacks** (`list.map(pure (x) -> ( x + 1 ))`, `parallelForEach(list, idempotent (x) -> ( ... ))`) have no `def`, yet need purity modifiers.
2. **Methods** in Babble are `def`s whose value is a function literal, so putting the modifier on the function literal covers top-level functions, local functions, instance methods, `value class` opt-outs, default-pure value method opt-outs, interface default methods, and static methods with one rule.
3. **Direct Java method and class annotations** (`trusted_pure java.lang.Math.sqrt`, `trusted_idempotent java.nio.file.Files.createDirectories`, `impure java.net.URL.equals`) work both as standalone declarations and on the RHS of a `def`, evaluating to the `JavaMethod` or `JavaClass` itself without allocating a wrapper closure.
4. **Function types** (`def area : pure () : double`, `def merge : idempotent (x: string)`) use the exact same `pure` / `idempotent (…) : R` shape for required interface members and higher-order parameters.

---

## 4. Semantics of `pure`, `idempotent`, `trusted_*`, and `impure`

### 4.1 Untouched arguments, `this`, and local fresh objects (P2)

A `pure` function or method must leave all of its arguments and `this` untouched; a checked `idempotent` function likewise cannot perform arbitrary direct field overwrites on inputs or outer objects (see §4.6 for why controlled state updates live in `trusted_idempotent`). In Babble, objects can only be mutated through member assignment (`e.x = value`) or by calling a method that mutates them.

Inside a `pure` or `idempotent` function (and inside its parameter default expressions):

1. **Member assignment `e.x = value` is forbidden on `this`, parameters, outer objects, and any non-fresh receiver:**
   * If `e` is `this`: `Cannot modify this in a pure function : x` (or `Cannot modify this in an idempotent function : x`)
   * If `e` is a parameter: `Cannot modify parameter in a pure function : p.x` (or `… in an idempotent function : p.x`)
   * If `e` is a local `def` `v` declared inside the function that is **provably a locally constructed fresh object** (initialised from a pure `class` constructor `C(…)` or pure `object (…)` literal, and never reassigned to any non-fresh expression), direct field mutation `v.x = value` **is allowed** because `v` was allocated during this activation and cannot alias `this`, any parameter, or any outer object:
     ```
     def buildBox = pure (w: int, h: int) : Box -> (
         def b = Box(0, 0)   ;; fresh local instance of a pure constructor
         b.w = w             ;; OK: `b` is a locally constructed fresh object
         b.h = h
         b
     )
     ```
   * For any other receiver `e` (including a local variable that could alias a parameter/`this`, or a chained access `v.child.x = value`): `Cannot modify member in a pure function : x`
2. **Only callables of at least the enclosing purity level may be called (§4.3, §4.6).**

Because a `pure` function can only mutate direct fields of provably fresh local objects (`v.x = value`) and can only call other `pure` functions/methods, **by induction no object reachable from `this`, from any argument, or from any outer scope can ever be modified during a `pure` call**.

### 4.2 Lexical variables and outer object fields: local mutation vs outer state (P3)

Inside a `pure` or checked `idempotent` function `F`:

* **Local variables declared inside `F` may be read and reassigned freely.** A `def` whose scope is inside `F` (at the top level of `F`'s body, or in an `if` / `while` / block nested inside `F`) belongs to `F`'s activation alone:
  ```
  def factorial = pure (n: int) : int -> (
      def acc : int = 1
      def i : int = 2
      while i <= n then (
          acc = acc * i
          i = i + 1
      )
      acc
  )
  ```
* **Assigning to an outer variable is forbidden.** If `x = value` resolves to a `def` or parameter declared *outside* `F` (in an enclosing function, class body, static block, package, or file), the analysis rejects it:
  `Cannot assign outer variable in a pure function : x` (or `… in an idempotent function : x`)
* **Reading a mutable outer variable is forbidden.** `TypeChecks` already computes the set of `def`s that are reassigned anywhere (`reassigned`). If `F` reads an outer `def` that is in `reassigned` (or a plain constructor parameter that is reassigned), the analysis rejects it:
  `Cannot read mutable outer variable in a pure function : counter`
  Reading a never-reassigned outer `def` holding a primitive, string, function, class, interface, or `value class` instance (such as `def helper = …`, `def Vec3 = …`, `def ORIGIN = Vec3(0, 0, 0)`, or `def pi = 3.14159`) is allowed.
* **Reading fields: `this`, parameters, local objects, and outer `value class`es vs outer mutable state.**
  * Reading fields directly on `this` (`this.x`), on parameters of `F` (`arg.x`), on local objects constructed inside `F`, and immutable fields on outer `value class` instances (`ORIGIN.x`) is allowed.
  * Reading fields or calling methods through **any mutable state reachable from an outer scope** is forbidden (`Cannot read mutable outer state in a pure function : …`), including:
    1. Fields on an **outer mutable `class` instance** or **outer `object` singleton** (`globalCounter.count`, `AppState.retries`),
    2. **Mutable `static` fields** (`Counter.created`) or **non-`final` Java static fields** on unblessed Java classes (`SomeJava.mutableField`, whereas `public static final` constants like `Math.PI` are allowed),
    3. Fields on an **outer `JavaObject`**, and
    4. **Transitive mutable references reachable through an outer `value class`** (e.g., `outerHolder.box.x` or `outerHolder.box.peek()` when `Holder` is a `value class` holding a mutable `Box` `class` or `JavaObject`). Calling an `idempotent` method on an outer `TagSet` accumulator *is* allowed inside an `idempotent` function (that is the primary use case for `idempotent`), whereas reading arbitrary mutable fields on it mid-phase is blocked to preserve order independence (§4.6).

### 4.3 The viral call rule & inline initializers (P4)

Calling rules follow the 3-tier lattice `PURE (2) > IDEMPOTENT (1) > IMPURE (0)`. Inside a checked function `F` with required level `R` (`PURE` or `IDEMPOTENT`), every call `callee(args…)` (including `recv.m(args…)` and `super.m(args…)`), as well as every inline initializer evaluated during `F`, must have purity `>= R`:

* Inside a **`pure`** function (`R = PURE`): callees must be `PURE` (`pure` or `trusted_pure`). Calling an `idempotent`, `trusted_idempotent`, or `impure` target is rejected (`Cannot call idempotent function in a pure function : …` / `Cannot call impure function in a pure function : …`).
* Inside an **`idempotent`** function (`R = IDEMPOTENT`): callees may be `PURE` or `IDEMPOTENT` (`idempotent` or `trusted_idempotent`); calling an `impure` target is rejected (`Cannot call impure function in an idempotent function : …`).

Enforcement covers:

1. **Static check (`TypeChecks` and `TypeChecker`):**
   * Rejects calls whose resolved callee, constructor, built-in (`print`, `println`, `import`), or Java method/class falls below `R`.
   * If `TypeChecker` is enabled (`warn` or `error`) and a callable parameter/variable has an insufficient function type (e.g. `idempotent (int) : int` or `(int) : int` where `pure` is needed), it reports a type/purity error.
2. **Inline `object (...)`, `class ... static (...)`, and `interface ... static (...)` expressions:**
   * Evaluating an `object (...)` literal immediately executes its body, and evaluating `class ... static (...)` or `interface ... static (...)` immediately executes its `static` block. Any inline `object` body or `static` initializer evaluated inside a `PURE` or `IDEMPOTENT` scope is checked under that enclosing requirement `R`.
3. **Runtime check (`Calls.callWithPurity` / `Bootstrap`):**
   * When the callee or receiver is dynamically typed (`def apply = pure (fn, x) -> ( fn(x) )`), the runtime checks `callee.purity(runtime).atLeast(requiredPurity)` before invoking it. If it fails, it throws a `BabbleException` at the call's line.
4. **`recurse`:**
   * Inside a `pure` / `trusted_pure` or `idempotent` / `trusted_idempotent` function or method, `recurse` refers to that function or method itself (bound to `this` for a method), which satisfies `R`, so `recurse(…)` is always allowed.

### 4.4 `value class` purity, class constructors, `impure` opt-out, and standard value methods (P4, P7)

* **`value class` constructors and instance methods are implicitly `pure` (override via `idempotent` / `impure`):**
  Because a `value class` represents an immutable value:
  1. Its constructor (parameter defaults and class body) is required to be `PURE` (`BabbleClass.purity() == Purity.PURE`).
  2. Every **instance method** defined in a `value class` body is implicitly `PURE` by default. If a helper method on a `value class` calls an idempotent coordinator (`idempotent`), needs an explicit trust boundary (`trusted_pure` / `trusted_idempotent`), or performs I/O (`impure`, e.g. `def debugDump = impure () -> ( println(this) )`), it can override the default explicitly. Even an `impure` or `idempotent` method on a `value class` still cannot mutate `this` fields (`BabbleInstance.setMember` enforces read-only fields), and cannot be called from stricter callers.
  3. Its optional `static ( … )` block belongs to the class object (`Point`), not to value instances, so static methods and static fields on a `value class` remain opt-in `pure` / `idempotent`.
* **Constructor purity on regular `class`es:**
  A regular `class` expression's constructor gets `BabbleClass.purity()` computed as the **minimum** purity level across its parameter default expressions and top-level `classBlock` expressions (outside method bodies, which don't run during construction): `PURE` if all are pure, `IDEMPOTENT` if any is `idempotent` (and none `impure`), or `IMPURE` otherwise. Standard data classes automatically have `PURE` constructors with zero boilerplate.
* **`equals`, `hashCode`, `compareTo`, and `toString` are pure by default (opt-out via `impure`):**
  Babble treats the standard value protocol methods—`equals`, `hashCode`, `compareTo`, and `toString` (used both directly and implicitly by `==`, `!=`/`<>`, `<`, `<=`, `>`, `>=`, `"…" + obj`, and `value class` `equals`/`hashCode`/`toString`)—as **implicitly `pure` by default** on both Babble classes (`class` / `value class`) and `JavaObject`s:
  * On any Babble `class` or `value class`, defining `def toString = () -> ( … )`, `def compareTo = (…) -> ( … )`, `def equals = (…) -> ( … )`, or `def hashCode = () -> ( … )` without a modifier is checked as `PURE` automatically.
  * If one of these methods intentionally has side effects (for example, diagnostic logging inside `toString`, or a Java class like `java.net.URL.equals` that performs DNS resolution), it can be explicitly opted out with **`impure`** (or downgraded to `idempotent`):
    * In Babble: `def toString = impure () : string -> ( println("debug") ; this.name )`
    * On a Java method or class: `impure java.net.URL.equals` (or `@Impure` / `engine.markImpure(...)`, §5.2).
  * Inside a `PURE` (or `IDEMPOTENT`) function, calling an `impure`-marked `toString`, `compareTo`, `equals`, or `hashCode`—either directly or implicitly via `"…" + obj`, `a < b`, `a == b`, `a != b`, or a `value class` container's generated `toString`/`equals`/`hashCode`—is rejected both statically and at runtime.

### 4.5 `trusted_pure`, `trusted_idempotent`, and `impure` (P1, P5, P9)

`trusted_pure (params) -> ( body )` and `trusted_idempotent (params) -> ( body )` are **definition-site trust boundaries** whose external contracts match `pure` and `idempotent`, respectively:

* **External runtime contract (`fn.purity()`):** `trusted_pure` reports `Purity.PURE`; `trusted_idempotent` reports `Purity.IDEMPOTENT`.
* **External static type (`TypeChecker`):** `trusted_pure` has static type `pure (params) : R`; `trusted_idempotent` has static type `idempotent (params) : R`.
* **Inside `body` (and its parameter defaults), purity checks are suspended:** `body` may mutate `this`/argument state, call impure Java helpers, or issue bounded OS calls according to its tier contract.

Conversely, `impure (params) -> ( body )` is a **definition-site opt-out** for contexts where purity is the default (`value class` instance methods, `toString`, `compareTo`, `equals`, `hashCode`):

* `fn.purity()` returns `Purity.IMPURE`; its static type in `TypeChecker` is plain `(params) : R` (identical to an unannotated standalone function).
* It cannot be called from `pure` or `idempotent` functions.

**Intended use cases for `trusted_pure`:**
1. **Pure JDK maths and utilities:** Individual stateless methods such as `java.lang.Math.sqrt`, `java.lang.Math.sin`, `java.lang.Math.pow`, and `java.lang.String` methods (note: prefer blessing specific methods rather than all of `java.lang.Math`, because `Math.random()` mutates shared PRNG state and is non-deterministic).
2. **Acceptable OS / environment reads:** Reading an immutable input or configuration file (`java.nio.file.Files.readString`) or environment/system properties when policy allows it.
3. **Benign internal mutation / memoization:** Computing a result using a local mutable Java collection (`java.util.ArrayList`, `java.lang.StringBuilder`) that never escapes, or populating a thread-safe internal cache when the observable input-to-output mapping remains deterministic.

> **Responsibility:** Both `trusted_pure` and `trusted_idempotent` are explicit audit points, like `unsafe` in Rust. Callers and optimizers rely on the author upholding the contract (including thread safety when executed concurrently).

### 4.6 Idempotence and commutativity: `idempotent` and `trusted_idempotent` (P11)

While `pure` forbids *all* observable mutations of external objects or arguments, **`idempotent`** allows operations whose effects converge to the **exact same state** regardless of how many times they are called or in what order they execute relative to other `idempotent` calls on the same object—mathematically an **Associative, Commutative, and Idempotent (ACI) / join-semilattice** contract (similar to state-based CRDTs and Bloom's CALM theorem).

```
;; Pure <: Idempotent <: Impure
def collectUnique = pure (xs: int) : int -> ( xs * 2 )   ;; pure (safe in pure, idempotent, impure)
def recordSeen    = idempotent (tags: TagSet, n: int) -> (
    tags.addTag("v=" + collectUnique(n))                 ;; calls pure + trusted_idempotent
)
```

Why checked `idempotent` functions keep §4.1–§4.2's direct-write and mutable-read restrictions:
1. **Arbitrary field assignments are neither commutative nor read-independent:** Writing `arg.x = 5` followed by `arg.x = 7` produces a different final state than `arg.x = 7` followed by `arg.x = 5`, and branching on `if arg.size() == 0 then …` breaks commutativity with a concurrent `arg.add(...)`. Checked `idempotent` Babble functions therefore prohibit raw `arg.x = …`, `this.x = …`, outer writes, and reads of mutable outer/shared fields, restricting all state updates to `trusted_idempotent` primitives and compositions of `idempotent` / `pure` functions.
2. **Four contract rules for `trusted_idempotent` authors:**
   * **Repeat invariance:** $f(f(s)) = f(s)$ (e.g., `Files.createDirectories`, inserting into a set, setting a monotonic latch `flag = true`, `maxSeen = Math.max(maxSeen, v)`—unlike `Files.createDirectory`, which fails on the 2nd call, or `list.add`, which grows on repeat calls).
   * **Commutativity on shared targets:** $f(g(s)) = g(f(s))$ for any `idempotent` calls targeting the same object/state (so `set.add("a")` and `set.add("b")` commute, whereas `set.add("a")` and `set.remove("a")` do **not**!).
   * **No pre-mutation delta in return values:** Prefer returning `null` or the converged value rather than exposing whether state changed (e.g., Java's `Set.add` returns `true` on the 1st call and `false` on the 2nd—a `trusted_idempotent` wrapper should discard that `boolean` and return `null` so callers cannot branch on call count or thread arrival order).
   * **Thread-safe + phase-barrier reads:** When used in parallel execution (`parallelForEach`), `trusted_idempotent` methods must be thread-safe (`ConcurrentHashMap.newKeySet()`, `AtomicInteger.accumulateAndGet(..., Math::max)`), and readers (`tags.toList()`, `tags.contains(...)`) are ordinary `IMPURE` or post-barrier operations executed only after the parallel `idempotent` phase completes.

### 4.7 Nested functions and closures (P10)

Each function literal establishes its own checking mode (`IMPURE`, `PURE`, `IDEMPOTENT`, `TRUSTED_PURE`, or `TRUSTED_IDEMPOTENT`):

* A `pure` or `idempotent` function nested inside a less-pure function is checked at its own stricter level.
* A less-pure function literal (such as an `impure` or `idempotent` closure) defined inside a `pure` or `idempotent` function (`def makeLogger = pure (prefix: string) -> ( (msg: string) -> ( println(prefix + msg) ) )`):
  * Constructing and returning the closure has no side effects and is allowed.
  * However, a closure nested inside a `pure` function **still cannot mutate the enclosing `pure` function's parameters or `this`**, because the enclosing `pure` function guarantees that its parameters and `this` are never modified, even after it returns.
  * And the enclosing function cannot *call* that closure itself (or pass it to Java via `Faces`) unless the closure's purity is at least the enclosing function's level (§4.3, §5.2).

### 4.8 Boundary clarifications: determinism, associative reductions, exceptions, and capabilities

To avoid annotation bloat while keeping semantics crystal-clear for future optimizers and runtime policy:

1. **Determinism vs. external reads (`IMMUTABLE` vs. `STABLE` / `@SideEffectFree`):**
   Pure Babble code (`pure`) has no way to read mutable external state, so it is both **side-effect-free** and **deterministic** (assuming its inputs are value/immutable). When an author explicitly blesses a host reader via `trusted_pure` (e.g., `trusted_pure java.nio.file.Files.readString`), the author is asserting **PostgreSQL `IMMUTABLE`-style contract**: the read resource is constant for the scope of the pure computation, making memoization/CSE valid. Do **not** mark volatile clocks (`System.currentTimeMillis()`) or changing tables as `trusted_pure` unless future Babble adds a separate `readonly` (`STABLE` / `@SideEffectFree`) tier for "no writes, snapshot reads."
2. **Associative reductions vs. `idempotent`:**
   `idempotent` models **ACI (Associative, Commutative, Idempotent)** effects ($f(f(s)) = f(s)$ and $f \circ g = g \circ f$), which validates retry safety and order-independent `parallelForEach`. Parallel value **reductions** such as `+`, `*`, or list concatenation (`parallelReduce`) are associative without being idempotent ($2 + 2 \ne 2$), and are instead expressed as `pure` binary combiners `(T, T) : T` whose associativity is a property of the combinator rather than a mutating side effect.
3. **Exceptions and speculative code motion (`nothrow`):**
   `pure` and `idempotent` functions may still raise runtime errors (e.g., `assert`, division by zero, null dereference). Consequently:
   * Parallelizing already-reached iterations (`parallelMap`, `parallelForEach`) and reordering eager pure expressions is safe when error choice doesn't matter;
   * An optimizer **must not speculatively hoist** a `pure` call out of a conditional branch (`if x != 0 then pureFn(x)`) unless it separately proves the call cannot throw (Babble does not add a separate `nothrow` keyword).
4. **Future compatibility (`mut` helpers and object capabilities):**
   * **Weak purity / `mut` parameters:** If real codebases frequently need helper methods to populate a caller-owned mutable object (`builder.addDefaults()`), Babble can later add D-style `mut` parameter annotations without breaking `pure <: idempotent <: impure`.
   * **Explicit capabilities:** High-assurance sandboxes should prefer passing an explicit capability object whose read methods are marked `trusted_pure` (object-capability style) rather than granting ambient `trusted_pure` access to global static I/O classes.

---

## 5. Built-ins and Java Interop

### 5.1 Built-in natives (P6)

| Built-in | Purity | Reason |
|---|---|---|
| `same(a, b)` | **Pure** | Reference/value identity test; no side effects, arguments untouched. |
| `classof(v)` | **Pure** | Returns the class value of `v`; no side effects, argument untouched. |
| `assert(cond, msg?)` / `suppose` | **Pure** | Verifies an invariant (its argument expressions are themselves checked at the enclosing purity). |
| `print(…)` / `affiche` | **Impure** | Performs I/O on `STDOUT` or mutates the target `to:` `Appendable`. |
| `println(…)` / `afficheligne` | **Impure** | Performs I/O on `STDOUT` or mutates the target `to:` `Appendable`. |
| `import(name)` | **Impure** | Loads a Java package or executes a `.ba` module file (has top-level side effects). |

Note: A `pure` or `idempotent` function *can* use a Java class or Babble module that was imported at the top level of the file (`def Files = import("java.nio.file.Files")` at top level), provided any Java method it calls has been annotated with `trusted_pure` / `trusted_idempotent` (§5.2) or is wrapped in a `trusted_*` function.

### 5.2 Marking Java methods and classes directly without a Babble wrapper (P5)

By default, every call to a `JavaClass` (constructor or static method), `JavaMethod`, `BoundJavaMethod`, or `JavaObject` member is impure (`purity(runtime) == Purity.IMPURE`)—**with the sole exception of the standard value protocol (`equals`, `hashCode`, `toString`, `compareTo`)**, which is `PURE` by default unless explicitly opted out with `impure` (or `idempotent`).

To annotate existing Java methods and classes without writing Babble wrapper closures, Babble provides **three mechanisms**, plus **callback/proxy protection** via `Faces`:

#### 1. In-Babble `trusted_pure`, `trusted_idempotent`, and `impure` declarations

`trusted_pure <target>`, `trusted_idempotent <target>`, and `impure <target>` can be applied directly to a Java method (`JavaMethod`) or an entire Java class (`JavaClass`):

```
;; 1a. Mark individual Java methods (static or instance) as pure or idempotent:
trusted_pure java.lang.Math.sqrt
trusted_pure java.lang.Math.pow
trusted_pure java.lang.String.substring
trusted_pure java.nio.file.Path.of
trusted_pure java.nio.file.Files.readString
trusted_idempotent java.nio.file.Files.createDirectories

def hypotenuse = pure (a: double, b: double) : double -> (
    java.lang.Math.sqrt(a * a + b * b)       ;; OK: Math.sqrt was marked trusted_pure
)

def ensureParent = idempotent (path: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(path))
)

;; 1b. Or bind a short alias at the same time (returns its target):
def sqrt = trusted_pure java.lang.Math.sqrt
def createDirs = trusted_idempotent java.nio.file.Files.createDirectories

;; 1c. Or mark an entire side-effect-free Java class as pure (constructor + all public methods):
def BigDecimal = trusted_pure import("java.math.BigDecimal")

def tax = pure (amount: BigDecimal, rate: BigDecimal) : BigDecimal -> (
    amount.multiply(rate)                    ;; OK: all of BigDecimal is trusted_pure
)

;; 1d. Opt a default-pure value method (equals/hashCode/toString/compareTo) out as `impure`:
impure java.net.URL.equals                   ;; URL.equals performs DNS resolution
impure java.net.URL.hashCode
```

**Semantics of `[export] (trusted_pure | trusted_idempotent | impure) <target>`:**
* **On a Java method (`trusted_pure java.lang.Math.sqrt`, `trusted_idempotent java.nio.file.Files.createDirectories`, `impure java.net.URL.equals`):**
  * Registers all public overloads of that method name on the resolved `Class<?>` at `PURE`, `IDEMPOTENT`, or `IMPURE` in the current scope/runtime.
  * Works uniformly for **static calls**, **direct references**, **instance calls**, and **implicit value calls**, because `BoundJavaMethod` and operator checks delegate to `ScriptRuntime` purity registries.
  * Returns the `JavaMethod` value itself.
* **On a Java class (`trusted_pure java.math.BigDecimal`, `trusted_idempotent my.CrdtSet`, `impure java.net.URL`):**
  * Marks the `JavaClass` constructor and **all** public methods (static and instance) of that `Class<?>` with that purity tier in the current scope/runtime (specific methods can still override the class default).
  * Returns the `JavaClass` value itself.
* **Forbidden inside `pure` and `idempotent` functions:**
  * Purity registration expressions cannot appear *inside* a `pure` or checked `idempotent` function body (`Cannot use trusted_pure inside a pure function`, `Cannot use trusted_idempotent inside an idempotent function`, etc.); they must be declared at top level, module level, class level, or in an `impure` / `trusted_*` scope.
* **Module visibility and `export`:**
  * Scoped to the file/block by default; prefixing a top-level declaration with `export` re-exports those Java purity registrations when another file executes `import("...")`.
* **Per-runtime isolation:**
  * Stored in the active `ScriptRuntime` (never mutating the global `JavaClass.CACHE` singleton) so one `BabbleScriptEngine` never leaks permissions to another engine in the same JVM.

#### 2. Host-side Java `@org.babblelang.Pure`, `@org.babblelang.Idempotent`, and `@org.babblelang.Impure` annotations

When writing Java classes intended to be called from Babble, a Java author can annotate a class, constructor, or method with `@Pure`, `@Idempotent`, or `@Impure`:

```java
package my.host.api;
import org.babblelang.Pure;
import org.babblelang.Idempotent;

public final class Geo {
    @Pure
    public static double haversine(double lat1, double lon1, double lat2, double lon2) { ... }

    @Idempotent
    public static void ensureOutputDir(String dir) { ... }
}
```

`JavaClass` and `JavaMethod` inspect `@Pure`, `@Idempotent`, and `@Impure` via reflection, and `TypeChecks` / `TypeChecker` check them on resolved `Class<?>` and `Method` targets.

#### 3. Host-side `BabbleScriptEngine.trustPure(...)`, `trustIdempotent(...)`, and `markImpure(...)` configuration API

An application embedding `BabbleScriptEngine` can pre-register JDK or library targets before evaluating scripts:

```java
BabbleScriptEngine engine = new BabbleScriptEngine();
engine.trustPure(java.lang.Math.class, "sqrt", "sin", "cos", "pow", "atan2");
engine.trustPure(java.lang.String.class, "length", "substring", "trim", "indexOf");
engine.trustPure(java.nio.file.Files.class, "readString");
engine.trustIdempotent(java.nio.file.Files.class, "createDirectories");
engine.markImpure(java.net.URL.class, "equals", "hashCode");
```

These host-configured entries are visible to both static analysis (`AnalysisSession` / `TypeChecks` / `TypeChecker`) and the engine's `ScriptRuntime`.

#### 4. Java interfaces and callbacks passed to Java (`Faces`)

A Babble class implementing `java.lang.Comparable` can define `compareTo` without extra keywords (it is implicitly `pure`, or can opt out with `impure`):
```
def Money = value class (cents: int) implements java.lang.Comparable (
    def compareTo = (o: Money) : int -> ( this.cents - o.cents )
)
```
Conversely, when a `pure` Babble function passes a Babble `Callable` (converted via `Faces.function` to `java.util.function.Function`, `Predicate`, `Comparator`, etc.) or a Babble `BabbleInstance` implementing a Java interface (`Faces.create` / `FaceHandler`) to a blessed (`trusted_pure` / `@Pure`) Java method such as `list.stream().map(fn).toList()` or `Collections.sort(list, cmp)`:

* **Static check (`TypeChecker`):** Inside a `pure` function, passing a callable argument to a functional-interface Java parameter (or calling a `trusted_pure` generic higher-order Java method) requires the callable argument's static type to have `pure = true`.
* **Runtime check (`Faces.FunctionHandler` & `Faces.FaceHandler`):** When `Faces.function(callable, type, rt)` wraps a Babble `Callable` inside a `pure` execution (`inPure`), it verifies `callable.isPure(rt)` immediately (`Cannot call impure function in a pure function : …`); and when Java invokes a method on a `FaceHandler` proxy while `inPure` is active on the calling thread/frame, `FaceHandler` checks `function.isPure()` before dispatching—preventing impure Babble callbacks from smuggling side effects back through `trusted_pure` Java libraries.

### 5.3 Static analysis of Java bytecode to derive purity automatically (feasibility & limits)

An appealing follow-up question is whether Babble could analyze `.class` bytecode (for instance via ASM, which Babble already depends on) to automatically mark pure Java methods—assuming all `native` / FFI methods (`ACC_NATIVE`, JNI, Panama FFM) are treated as **impure**—instead of requiring `trusted_pure` or `@Pure` declarations.

#### Is it feasible?

**Yes.** JVM bytecode is compact, typed, and easy to inspect via `ClassLoader.getResourceAsStream(...)`. Academic and production analyzers (such as Sălcianu & Rinard's combined pointer/escape analysis, JPure, and Soot/FlowDroid purity checkers) already derive purity summaries over Java bytecode by building a conservative call graph and inspecting instructions (`PUTFIELD`, `PUTSTATIC`, `GETSTATIC`, `*ASTORE`, `MONITORENTER`, `INVOKESTATIC`/`INVOKEVIRTUAL`/`INVOKEINTERFACE`/`INVOKEDYNAMIC`, `ACC_NATIVE`, and `<clinit>` static initializers).

#### Would it be sound (correct)?

It **can be made sound** (never falsely marking an impure Java method as pure) *if* the analyzer conservatively classifies every construct it cannot statically bound as **impure**, especially these JVM-specific traps:

1. **`native` / FFI calls (`ACC_NATIVE`):** Treating every `native` method as impure immediately catches OS syscalls (`FileOutputStream`, `Socket`, `ProcessBuilder`, `System.exit`, `System.nanoTime`, `Random`, `Unsafe`, `MemorySegment`/FFM).
2. **Open virtual and interface dispatch (`INVOKEVIRTUAL`, `INVOKEINTERFACE`):** Calling `list.size()` or `charSeq.length()` on a non-`final` method or interface cannot be trusted statically unless *every* override visible to the JVM is pure **and** dynamic classloading cannot introduce an impure subclass later—so without closed-world assumptions or a runtime receiver check, open virtual calls must be treated as impure.
3. **Static class initialization (`<clinit>`):** First reference to a Java class runs its static initializer, which frequently mutates global registries or reads environment/files, so `<clinit>` must also be analyzed or assumed impure.
4. **Global mutable reads (`GETSTATIC`):** Reading non-`final` static fields—or calling getters on shared mutable singletons—breaks referential transparency and must be rejected just like writes.
5. **Reflection, `MethodHandle`s, and `invokedynamic`:** General reflection (`Class.forName`, `Method.invoke`) must be marked impure, while well-known compiler bootstrap methods (`LambdaMetafactory`, `StringConcatFactory`) need special-case handling.
6. **Thread synchronization (`MONITORENTER` / `synchronized`):** Acquiring monitors on `this`, parameters, or shared objects introduces global lock ordering and potential contention/deadlock.

#### Would it be precise (useful in practice)?

Here lies the main limitation: **under "all `native` calls are impure", a sound bytecode analyzer suffers severe false positives (poor precision)** unless paired with substantial extra machinery:

| Obstacle to precision | Concrete Java example | Effect on precision |
|---|---|---|
| **Transitive `native` primitives** | `System.arraycopy` (`native`), `Object.getClass()` (`native`), `Double.doubleToRawLongBits` (`native`) | Even `String.substring`, `String.concat`, `Arrays.copyOf`, `StringBuilder`, `ArrayList`, and `BigDecimal` call `System.arraycopy` transitively—so a zero-whitelist rule classifies almost the entire JDK standard library as **impure**. |
| **Internal local mutation** | `StringBuilder` appending inside `toString()`, temporary `byte[]`/`int[]` allocations (`BASTORE` / `PUTFIELD`) | A naive check banning all `PUTFIELD` and `*ASTORE` instructions flags nearly every non-trivial Java method as impure. Recovering precision requires **inter-procedural pointer + escape analysis** to prove mutations stay confined to freshly allocated objects that never escape to arguments, `this`, or statics. |
| **Benign lazy caches** | `String.hashCode()` caching `this.hash`, `BigDecimal` caching `stringCache` | Observationally pure JDK methods routinely mutate private cache fields (`PUTFIELD`) or shared lookup tables (`PUTSTATIC`), which any sound analyzer must reject without `@Pure`/`trusted_pure`. |
| **Open interface parameters** | Methods taking `CharSequence`, `List`, `Iterable`, or `Comparator` | Without effect-polymorphism or closed classes, any call to a parameter's interface method forces a sound static analyzer to mark the caller impure. |
| **Policy vs. semantics** | `Files.readString(path)` | No bytecode analyzer can decide that reading a file is "acceptable" (`trusted_pure`) while writing a file is not—that is a **security policy choice**, not a property of bytecode. |

#### Verdict for Babble

* **Not a replacement for `trusted_pure` in Phase 1:** Relying on explicit `trusted_pure`, `@Pure`, and `BabbleScriptEngine.trustPure(...)` is simpler, 100% predictable, faster at startup, avoids the soundness risk of complex pointer/escape analysis on security boundaries, and lets host applications make explicit policy decisions (such as blessing `Files.readString` or `System.arraycopy`).
* **Promising as a Phase 2 assistant or opt-in analyzer:** An ASM-based bytecode analyzer—combining fresh-allocation **escape analysis** with a small audited seed list for pure JVM `native` intrinsics (`System.arraycopy`, `Object.getClass`, `Float`/`Double` bit conversions)—is realistic later as either a **lint/suggestion tool** (proposing safe `trusted_pure` candidates to users) or a conservative **auto-blesser for closed, allocation-local JDK helpers**.

---

## 6. Interfaces, Defaults, and Conformance (P8)

Interfaces can specify `pure` or `idempotent` on both required members and default methods:

```
def Hittable = interface (
    def hit : pure (r: Ray, rayT: Interval) : HitRecord
    def boundingBox : pure () : AABB
    def hasBox = pure () : bool -> ( this.boundingBox() != null )
    def recordHitStats : idempotent (tag: string)
)
```

1. **Required members:**
   * `def hit : pure (…) : HitRecord` requires a `PURE` (`pure` / `trusted_pure`) implementation; `def recordHitStats : idempotent (tag: string)` requires an `IDEMPOTENT` or `PURE` implementation.
   * If a class `Sphere implements Hittable` provides a weaker implementation (e.g., `impure` or `idempotent` for `hit`, or `impure` for `recordHitStats`), `TypeChecks` rejects the class:
     `Sphere.hit is not pure and cannot implement pure Hittable.hit` / `Sphere.recordHitStats is not idempotent and cannot implement idempotent Hittable.recordHitStats`
2. **Default methods:**
   * A `pure` or `idempotent` default method (`hasBox` above) is checked at its declared tier. When it calls `this.m(…)`, `m` must declare at least that tier on the interface.
   * Overriding an interface default method cannot weaken its purity tier (`Sphere.hasBox is not pure and cannot override pure Hittable.hasBox`).
3. **Covariance of purity (`pure <: idempotent <: impure`):**
   * Stronger implementations are always safe where weaker ones are expected: `pure` can implement `idempotent` or unannotated (impure) interface requirements, and `idempotent` can implement unannotated (impure) interface requirements.

---

## 7. Type System Integration (`TypeChecker` and `Types`) (P9)

### 7.1 `Type.Function` representation

In `org.babblelang.analysis.Type`, `Type.Function(List<Parameter> parameters, Type result, Purity purity)` carries a `Purity` tier (`IMPURE(0)`, `IDEMPOTENT(1)`, `PURE(2)`):

* Both `pure (params) -> (…)` and `trusted_pure (params) -> (…)` have `purity = Purity.PURE`, displayed as `pure (a: int) : int`.
* Both `idempotent (params) -> (…)` and `trusted_idempotent (params) -> (…)` have `purity = Purity.IDEMPOTENT`, displayed as `idempotent (a: int) : int`.
* An unannotated `(params) -> (…)` and an explicitly opted-out `impure (params) -> (…)` both have `purity = Purity.IMPURE`, displayed as `(a: int) : int`.
* In a `value class` body (and for `equals`, `hashCode`, `compareTo`, `toString` on any class), an unannotated function literal defaults to `Purity.PURE`, whereas `idempotent`, `trusted_idempotent`, or `impure` overrides it explicitly.

### 7.2 Assignability (T4 extension)

When checking whether a function of type `actual` (`Type.Function`) is assignable to an expected function type `target` (`Type.Function`):

* Assignability succeeds iff `actual.purity.atLeast(target.purity)` (`PURE > IDEMPOTENT > IMPURE`):
  * `pure (A) : B` is assignable to `pure (A) : B`, `idempotent (A) : B`, and `(A) : B`.
  * `idempotent (A) : B` is assignable to `idempotent (A) : B` and `(A) : B`, **not** `pure (A) : B`.
  * `(A) : B` is only assignable to `(A) : B`.
* Passing an insufficiently pure function reports:
  `Type error : expected pure (int) : int, got idempotent (int) : int` (or `got (int) : int`).

### 7.3 Runtime boundary checks in `error` mode (`Types`)

In `babble.typecheck=error` mode (`type-system.md` §5), `Types` descriptors add `pure-fn|<display>|` and `idempotent-fn|<display>|` alongside `fn|<display>|`:

* `fn|(int) : int|` accepts any `Callable` (or `null`).
* `idempotent-fn|idempotent (int) : int|` accepts `null` or any `Callable` with `callable.purity().atLeast(Purity.IDEMPOTENT)`.
* `pure-fn|pure (int) : int|` accepts `null` or any `Callable` with `callable.purity() == Purity.PURE`. If a weaker callable crosses a stricter function-type boundary dynamically, `Types.check` throws:
  `Type error : expected pure (int) : int, got function` / `Type error : expected idempotent (int) : int, got function`

Even when `babble.typecheck` is `off` or `warn`, calling a dynamic function inside a `pure` or `idempotent` function is still checked at the call site itself (§8.2), so a `pure` or `idempotent` function can never execute a weaker callee in any mode.

---

## 8. Implementation Architecture

### 8.1 Static analysis (`BlockScopes` and `TypeChecks`)

Purity fits directly into the existing two-pass analysis that runs in both interpreted and compiled modes before execution:

1. **Pass 1 (`BlockScopes`):**
   * Defines two enums on `ScopeNode`:
     - `enum Purity { IMPURE, IDEMPOTENT, PURE }` (ordered capability level exposed by signatures and runtime callables, with `boolean atLeast(Purity other)`), and
     - `enum CheckMode { IMPURE, IDEMPOTENT, PURE, TRUSTED_IDEMPOTENT, TRUSTED_PURE }` (body-checking discipline inherited by nested block/`if`/`while` scopes inside a function, and reset on each nested `functionLiteral` to its own modifier or context default).
   * `CheckMode.requiredPurity()` returns `Purity.PURE` for `CheckMode.PURE`, `Purity.IDEMPOTENT` for `CheckMode.IDEMPOTENT`, and `null` (unchecked) for `IMPURE`, `TRUSTED_IDEMPOTENT`, and `TRUSTED_PURE`.
   * In a `value class` body (`ScopeNode.Kind.CLASS_BODY` with `type.isValue()`), the class body scope itself and any instance method without an explicit modifier default to `PURE` (`static` blocks remain `IMPURE` unless methods opt in or the `static` block is inside a checked scope).
   * In any `class` or `value class` body, an unannotated method named `equals`, `hashCode`, `compareTo`, or `toString` defaults to `PURE` unless explicitly given another modifier (`impure`, `idempotent`, `trusted_idempotent`, `trusted_pure`).
   * Rejects `trusted_pure`, `trusted_idempotent`, and `impure` inside `# functionType`, rejects `export (trusted_pure | trusted_idempotent | impure)` outside file top level, and rejects `purityExpression` inside checked (`PURE` or `IDEMPOTENT`) scopes.
   * Inside `CheckMode.PURE` and `CheckMode.IDEMPOTENT` (including inline `object (...)` bodies and `class`/`interface` `static` blocks evaluated inside them):
     * Rejects `this.x = val` immediately (`Cannot modify this in a pure function : x` / `Cannot modify this in an idempotent function : x`).
     * Records other `e.x = val` assignments (`PendingAccess` with `assignment = true`) for `TypeChecks` to verify fresh-local vs non-fresh receivers.
   * Records `CheckMode` and external `Purity` on each `FunctionLiteralContext` in `ScopeAnalysis` and on each `TypeNode.Member`.

2. **Pass 2 (`TypeChecks`):**
   * **Collecting `trusted_pure`, `trusted_idempotent`, and `impure` Java targets:**
     * Walks `PurityExpressionContext` (`[export] (trusted_pure | trusted_idempotent | impure) <target>`): resolves `<target>` to a `Class<?>` or `(Class<?>, String methodName)` pair and records its tier (`PURE`, `IDEMPOTENT`, or `IMPURE`) in `ScopeAnalysis` and `AnalysisSession` (plus module exports when prefixed with `export`).
   * **Fresh local object tracking (`v.x = val`):**
     * A local `def` declared inside a `PURE` or `IDEMPOTENT` function is marked *fresh-local* if its initialiser (and every reassignment to it) is a `PURE` `class` construction `C(…)` or `PURE` `object (…)` literal.
     * For `e.x = val` inside `PURE` or `IDEMPOTENT`: allows the assignment iff `e` resolves to a fresh-local `def` of that function; otherwise rejects parameter/member assignment.
   * **Outer variable, parameter, and outer field checks:** Inside `CheckMode.PURE` or `CheckMode.IDEMPOTENT`:
     * Rejects outer variable assignments, parameter reassignments, and reads of reassigned outer `def`s.
     * Rejects reading fields or calling impure methods through mutable outer state (mutable `class` / `object` singletons, mutable `static` fields, non-`final` Java static fields, `JavaObject`s, and mutable fields reached through outer `value class`es). Calling an `idempotent` / `trusted_idempotent` method on an outer object *is* allowed inside `CheckMode.IDEMPOTENT` (while reading its mutable data fields mid-phase remains forbidden).
   * **Constructor purity and inline initializers:** Computes `BabbleClass.purity()` as the minimum `Purity` across its parameter defaults and top-level `classBlock` expressions (requiring `Purity.PURE` for `value class`es). Enforces `requiredPurity` on inline `object (...)` bodies and `class`/`interface` `static` blocks nested inside `PURE` / `IDEMPOTENT` scopes.
   * **Interface conformance:** Verifies in `checkClass(TypeNode)` and `mergeDefaults` that every implementing/overriding method has `actual.atLeast(required)`.
   * **Static call, Java callback, and operator purity check:** Walks calls, Java functional-interface conversions (`Faces`), and operators (`+`, `==`, `!=`, `<`, `<=`, `>`, `>=`) inside `CheckMode.PURE` and `CheckMode.IDEMPOTENT`. Whenever the resolved target's `Purity` fails `targetPurity.atLeast(requiredPurity)`, throws `AnalysisException` naming the required and actual tiers.

### 8.2 Runtime and bytecode compiler (`Callable`, `Calls`, `Operators`, `Faces`, `CodeGenerator`, `Bootstrap`)

1. **`Callable.purity(ScriptRuntime)` and `ScriptRuntime` Java purity registry:**
   * `Callable` defines `default Purity purity() { return Purity.IMPURE; }` and `default Purity purity(ScriptRuntime runtime) { return purity(); }`, with convenience helpers `isPure(...)` and `isIdempotent(...)`.
   * `BabbleFunction`, `CompiledFunction`, and `BoundMethod` carry `final Purity purity` (`PURE`, `IDEMPOTENT`, or `IMPURE`).
   * `BabbleClass` carries `final Purity constructorPurity` and returns it from `purity()`.
   * `SameFunction`, `ClassofFunction`, and `AssertFunction` return `Purity.PURE`.
   * `ScriptRuntime` maintains tiered overrides (`Map<Class<?>, Purity> classPurity`, `Map<JavaMethod, Purity> methodPurity`) populated by `BabbleScriptEngine.trustPure(...)` / `trustIdempotent(...)` / `markImpure(...)`, by evaluating `[export] (trusted_pure | trusted_idempotent | impure) <target>`, and by `BabbleScriptEngine.importModule(...)`.
   * Resolution order in `JavaMethod.purity(ScriptRuntime)` / `JavaClass.purity(ScriptRuntime)`:
     1. Runtime method override (`methodPurity`), else
     2. `@Pure` / `@Idempotent` / `@Impure` annotation on the `Method`, else
     3. Runtime class override (`classPurity`), else
     4. `@Pure` / `@Idempotent` / `@Impure` annotation on the `Class<?>`, else
     5. `Purity.PURE` if the method is a standard value protocol method (`equals`, `hashCode`, `toString`, `compareTo`), else `Purity.IMPURE`.
   * `BoundJavaMethod.purity(ScriptRuntime)` delegates to `method.purity(runtime)`.
2. **Interpreter (`Interpreter`), `Operators`, `BabbleInstance.toString()`, and `Faces`:**
   * `visitPurityExpression`: evaluates `target`, registers `PURE`, `IDEMPOTENT`, or `IMPURE` in `runtime`, and returns `val`.
   * Tracks `Purity activeRequirement` (non-null `PURE` or `IDEMPOTENT` inside checked bodies, `null` inside `IMPURE` / `TRUSTED_*`, propagated via a thread/frame stack into Java `Faces` proxies).
   * In `visitCall` and binary operators (`+`, `==`, `!=`, `<`, `<=`, `>`, `>=`): when `activeRequirement != null`, calls `Calls.callWithPurity(callee, runtime, requiredPurity, args, names, site)` and enforces `requiredPurity` on `toString` (including recursively in `BabbleInstance.toString()`), `compareTo`, and `equals`.
   * In `Faces.function` / `Faces.FaceHandler`: when `activeRequirement != null`, checks `callable.purity(rt).atLeast(activeRequirement)` at wrap time and `function.purity().atLeast(activeRequirement)` when Java invokes a proxy callback.
3. **Bytecode compiler (`CodeGenerator` and `Bootstrap`):**
   * `visitPurityExpression`: compiles `target`, duplicates it on the operand stack, and invokes `runtime.setPurity(Object, Purity, boolean, int)`.
   * While compiling a `PURE` or `IDEMPOTENT` function body (not `IMPURE` or `TRUSTED_*`):
     * Direct `this.m(…)` calls (`INVOKEVIRTUAL` on the compiled class) are verified statically by `TypeChecks` and emit unchanged `INVOKEVIRTUAL`.
     * `invokedynamic` call sites encode the required `Purity` (`PURE` or `IDEMPOTENT`) in `Bootstrap`: when linking a fast path in the inline cache, `Bootstrap` checks `target.purity(runtime).atLeast(requiredPurity)` **once at linkage time** (zero per-call overhead); if `false`, it falls through to `Calls.callWithPurity(...)`, which throws at the call's line.

---

## 9. Examples

### 9.1 Pure raytracer math and geometry with direct `trusted_pure` Java methods

```
;; Bless JDK math and read-only file methods directly without wrapper functions:
trusted_pure java.lang.Math.sin
trusted_pure java.lang.Math.cos
trusted_pure java.nio.file.Path.of
trusted_pure java.nio.file.Files.readString

package math (
    ;; Pure Babble Newton-Raphson square root: provably side-effect-free
    export def sqrt = pure (x: double) : double -> (
        if x <= 0.0 then ( return 0.0 )
        def g : double = x
        def i : int = 0
        while i < 12 then (
            g = 0.5 * (g + x / g)
            i = i + 1
        )
        g
    )

    ;; Or export a direct alias to a Java method marked trusted_pure (zero wrapper overhead):
    export def atan2 = trusted_pure java.lang.Math.atan2
)

;; A pure function can call math.sqrt, math.atan2, java.lang.Math.sin, and Files.readString directly,
;; while println(...) or Files.writeString(...) remain rejected at compile time:
def computeFovFactor = pure (angleRad: double) : double -> (
    def s = java.lang.Math.sin(angleRad)
    math.sqrt(1.0 - s * s)
)

def loadScene = pure (path: string) : string -> (
    java.nio.file.Files.readString(java.nio.file.Path.of(path))
)
```

### 9.2 Catching accidental mutation of `this` or arguments while allowing local fresh objects

```
def Counter = class (def count: int = 0) (
    ;; Rejected at compile time: Cannot modify this in a pure function : count
    def badPeek = pure () : int -> (
        this.count = this.count + 1
        this.count
    )

    ;; Accepted: reads this.count without modifying this
    def peek = pure () : int -> (
        this.count
    )
)

def resetCounter = pure (c: Counter) : int -> (
    c.count = 0   ;; Rejected at compile time: Cannot modify parameter in a pure function : c.count
    c.peek()
)

def makeCounterWith = pure (initial: int) : Counter -> (
    def c = Counter(0)   ;; fresh local instance of a pure constructor
    c.count = initial    ;; Accepted: `c` is a locally constructed fresh object
    c
)
```

### 9.3 Parallelizable `map` requiring a `pure` callback

Because `fn` is typed as `pure (x: int) : int`, `parallelMap` knows that `fn(item)` cannot mutate shared state or perform side effects, so the invocations across elements are independent, reorderable, and safe to evaluate in parallel:

```
def parallelMap = trusted_pure (items: java.util.List, fn: pure (x: int) : int) : java.util.List -> (
    items.parallelStream().map(java.util.function.Function(fn)).toList()
)

def square = pure (x: int) : int -> ( x * x )
def noisy  = (x: int) : int -> ( println(x) ; x * x )

parallelMap(list, square)   ;; OK: square is pure, safe to run in parallel
parallelMap(list, noisy)    ;; Rejected: Type error : expected pure (int) : int, got (int) : int
```

### 9.4 Idempotent parallel accumulation and retry safety (`idempotent` / `trusted_idempotent`)

Because `fn` is typed as `idempotent (x: string)`, `parallelForEach` can evaluate `fn(item)` in parallel across worker threads (and safely retry failed elements) and join at a barrier before readers observe the accumulator:

```
trusted_idempotent java.nio.file.Files.createDirectories
trusted_pure java.nio.file.Path.of

def parallelForEach = trusted_idempotent (items: java.util.List, fn: idempotent (x: string)) -> (
    items.parallelStream().forEach(java.util.function.Consumer(fn))
    null
)

def DirCollector = class (private def dirs = java.util.concurrent.ConcurrentHashMap.newKeySet()) (
    def addDir = trusted_idempotent (dir: string) -> (
        this.dirs.add(dir)
        null   ;; discard Set.add boolean so repeated calls stay indistinguishable
    )
    def snapshot = () : java.util.List -> ( this.dirs.stream().toList() )  ;; post-barrier reader
)

def collector = DirCollector()
def prepare = idempotent (dir: string) -> (
    java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir))
    collector.addDir(dir)
)

parallelForEach(paths, prepare)   ;; OK: `prepare` is idempotent (pure (string) is also accepted)
collector.snapshot()              ;; Post-barrier read observes the converged set of directories
```

---

## 10. Implementation Plan

1. **Grammar (`Babble.g4`):** Add `PURE` (`'pure'`), `TRUSTED_PURE` (`'trusted_pure'`), `IDEMPOTENT` (`'idempotent'`), `TRUSTED_IDEMPOTENT` (`'trusted_idempotent'`), and `IMPURE` (`'impure'`) tokens; `purity=(PURE | TRUSTED_PURE | IDEMPOTENT | TRUSTED_IDEMPOTENT | IMPURE)?` on `# functionLiteral`; `EXPORT? purity=(TRUSTED_PURE | TRUSTED_IDEMPOTENT | IMPURE) target=expression` (`# purityExpression`); and `purity=(PURE | IDEMPOTENT)?` on `# functionType`.
2. **Analysis (`BlockScopes`, `TypeNode`, `ScopeAnalysis`, `ClassShape`):**
   * Track `Purity` (`IMPURE`, `IDEMPOTENT`, `PURE`) and `CheckMode` (`IMPURE`, `IDEMPOTENT`, `PURE`, `TRUSTED_IDEMPOTENT`, `TRUSTED_PURE`) on function literals, scopes, and members; default `value class` constructors/instance methods and `equals`/`hashCode`/`compareTo`/`toString` methods to `PURE` unless overridden (`static` blocks remain opt-in).
   * Reject `this.x = …`, parameter reassignment, and `purityExpression` declarations inside `PURE` and `IDEMPOTENT` scopes.
   * Encode member `Purity` and constructor `Purity` into `ClassShape`.
3. **Whole-file checks (`TypeChecks`):**
   * Record `[export] (trusted_pure | trusted_idempotent | impure)` Java classes and Java methods in `ScopeAnalysis` and `AnalysisSession`.
   * Track fresh local objects (`def v = C(...)`) inside `PURE` and `IDEMPOTENT` functions so `v.x = val` is allowed while parameter/outer/alias field mutations are rejected.
   * Reject outer variable assignments, mutable outer variable reads, non-`final` Java static field reads, and chained mutable field reads (including through outer `value class`es) inside `PURE` and `IDEMPOTENT` scopes, while allowing calls to `idempotent` / `trusted_idempotent` methods inside `IDEMPOTENT` scopes.
   * Compute constructor `Purity` for classes (requiring `Purity.PURE` for `value class`es) and enforce `activeRequirement` on inline `object`/`static` blocks inside `PURE` / `IDEMPOTENT` functions.
   * Check `implements` / override purity covariance (`pure <: idempotent <: impure`) on interfaces and classes.
   * Reject statically known weaker calls, Java callbacks (`Faces`), and value protocol calls (`toString` / `compareTo` / `equals` / `hashCode`) below the enclosing tier; update `src/test/babble/modules/vectors.ba` helper `square` to `pure`.
4. **Type checker (`TypeChecker` and `Types`):**
   * Add `Purity purity` to `Type.Function`, enforce `actual.purity.atLeast(target.purity)` (including callbacks passed to Java functional interfaces), treat `impure` literals as `(A) : B`, respect `@Pure`/`@Idempotent`/`@Impure` and script-level registrations, and add `pure-fn|…` and `idempotent-fn|…` runtime boundary descriptors in `error` mode.
5. **Runtime, Java interop & Compiler (`Callable`, `ScriptRuntime`, `Module`, `JavaClass`, `JavaMethod`, `BoundJavaMethod`, `@Pure`, `@Idempotent`, `@Impure`, `BabbleScriptEngine`, `Calls`, `Operators`, `BabbleInstance`, `Faces`, `Interpreter`, `CodeGenerator`, `Bootstrap`):**
   * Add `Callable.purity(ScriptRuntime)` (`isPure`, `isIdempotent`), `ScriptRuntime` tiered Java overrides, module re-export of `export (trusted_pure | trusted_idempotent | impure)`, `@Pure` / `@Idempotent` / `@Impure`, `BabbleScriptEngine.trustPure(...)` / `trustIdempotent(...)` / `markImpure(...)`, `Calls.callWithPurity(...)`, `activeRequirement` checks on `Operators` / `BabbleInstance.toString()` / `Faces`, and `Bootstrap.bootstrapCall(..., Purity)`.
6. **Tests (`BabblePurityTestCase` and `TypeCheckerTestCase`):**
   * Test `pure`, `trusted_pure`, `idempotent`, `trusted_idempotent`, `impure`, default-pure `equals`/`hashCode`/`compareTo`/`toString`, and implicit `value class` purity in both interpreted and compiled modes (`DifferentialTestCase`).
   * Test subtyping lattice `pure <: idempotent <: impure` (calling `pure` from `idempotent` succeeds; calling `idempotent` or `impure` from `pure` fails statically and dynamically; `idempotent` interface implementations and covariance).
   * Test rejection of `this.x = …`, `arg.x = …`, alias mutation `alias.x = …`, outer variable/field mutation and transitive outer mutable reads (`holder.box.x`, `object` singletons, non-`final` Java static fields), inline `object`/`static` blocks with side effects, weaker Java callbacks (`Faces`), `impure`-marked `toString`/`compareTo`/`equals`, `print`/`println`, and unblessed Java calls; test allowed mutation `v.x = …` on locally constructed fresh objects.
   * Test marking Java methods/classes directly (`trusted_pure java.lang.Math.sqrt`, `trusted_idempotent java.nio.file.Files.createDirectories`, `export trusted_*`, `impure java.net.URL.equals`, `trusted_pure java.math.BigDecimal`, `@Pure`, `@Idempotent`, `@Impure`, `engine.trustPure(...)` / `trustIdempotent(...)` / `markImpure(...)`), as well as `parallelMap` and `parallelForEach` examples.

### 10.1 Follow-up: Automatic Purity & Idempotence Inference for Unannotated Functions

As a follow-up phase, `TypeChecks` can compute a fixed-point **purity inference** over unannotated Babble functions (`(params) -> ( body )`):
* Any unannotated function whose body satisfies all `PURE` rules can be inferred as `PURE`, and any unannotated function that satisfies all `IDEMPOTENT` rules (e.g., calls only `pure` and `idempotent` functions with no direct non-fresh field writes or mutable outer reads) can be inferred as `IDEMPOTENT` automatically.
* Functions explicitly marked `impure` are never promoted to `PURE` or `IDEMPOTENT`—so callers can lock a function's contract to impure if desired.
* This allows small unannotated local helpers (such as `def square = (n) -> ( n * n )` or `def touch = (s, d) -> ( Files.createDirectories(Path.of(d)) ; s.addTag(d) )`) to work seamlessly inside `pure` / `idempotent` pipelines without ceremony, while keeping explicit `pure` / `idempotent` annotations as enforced compile-time contracts on public APIs and interfaces.
