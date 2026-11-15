# Review of `purity.md`: gaps, inconsistencies, bugs and loose threads

This is a review of [`purity.md`](purity.md) (function effects and algebraic properties). Its claims were checked against `Babble.g4`, `runtime/Operators.java`, `runtime/BabbleInstance.java` and `analysis/Type.java` as they stand today. Nothing was run; the findings come from reading.

Summary: the model has several soundness holes, a number of its examples fail under its own rules, and it assumes compiler infrastructure that does not exist yet. The soundness items (§1) and the phasing (§3.9, §3.10) are the places to start.

Section references such as "§5.3.1" point into `purity.md`.

---

## 1. Soundness bugs

> **Status:** addressed in `purity.md` after this review. In short: result reuse needs a deeply immutable result, and deeply immutable arguments for memoization and folding (1.1, 1.6); wrapping `int` arithmetic is no longer `monotone`, and the §8.3 example computes in `double` (1.2); declared types are now always enforced statically and at runtime (`babble.typecheck` modes removed), and `nothrow` at a call site is conditional on statically conforming arguments (1.3); plain `mut` promises no `noalias`, which now comes only from `unique mut` (1.4); `unique` means deep ownership, and `freeze` yields a purely static `frozen T` type that cannot erase to `dynamic` (1.5); identity-derived `hashCode`/`toString` are `pure` but not `deterministic` (1.7); ownership is tracked for uncaptured locals only (1.8). The findings below are kept as written, against the original text. In Sections 2 to 5, items §2.5, §3.4 (bullet 3), §3.8 (partially), §3.9, §3.10 (bullet 3), §4 (partially), §5.1 (dynamic callees), §5.2 (`ScriptRuntime` & `ModuleInterface` storage), and §5.5 are also resolved as noted inline; the remaining items are still open.

### 1.1 CSE and memoization of functions returning fresh mutable objects

`buildIndex` (§4.1) is `pure deterministic` with a `unique HashMap` return. §5.3.1 collapses two occurrences of `f(a, b)` into one call when `f` is `pure deterministic`. Applied to `buildIndex`, that aliases two graphs that were each promised to be `unique`: a mutation of one shows up in the other, and `same` tells them apart from the unoptimized program.

CSE and memoization need an immutable or value result, or a rule that excludes `unique` returns.

### 1.2 `monotone` and `injective` on wrapping ints

§4.6 calls `int +` and `int *` (for `k > 0`) `monotone`, and §8.3 axiomatizes `raw * 10 + 5` as `monotone injective`. `Operators` keeps Java's wrap-around on `Integer` arithmetic, so:

- `x + k` is not monotone (it wraps at `Integer.MAX_VALUE`);
- `x * 10` is not monotone, and not injective either (10 is even, so the map is not one-to-one mod 2³²).

The range-pruning and equality push-down rewrites of §5.3.3 built on these are wrong.

### 1.3 `nothrow` inferred from unenforced types

`square = (x: int) : int -> ( x * x )` is "inferred `pure deterministic nothrow threadsafe`" (§4.1). Declared types are only checked at runtime in `babble.typecheck=error`. In `off` and `warn`, `square("a")` throws from `Operators.mul`. Any `nothrow` inference that relies on a parameter annotation is unsound outside `error` mode.

### 1.4 The exclusivity check is shallow

§8.5 declares `Vec = class (def data: java.util.ArrayList)`. Two distinct `Vec`s can share one `data` list, so a `same(a, b)` check at the call site (§4.2.4) does not deliver the `noalias` guarantee that §5.3.2 relies on. Aliasing through captured variables or globals (the callee reads a global that refers to its `mut` argument) is not covered either.

### 1.5 `freeze` is not deep

`buildLookup` (§8.5) puts elements of the parameter `entries` into `m`. The frozen graph therefore still shares mutable objects with the caller, and `entries` escapes into the return value. Further:

- Nothing says how frozen-ness is enforced at runtime against `dynamic` code or Java methods.
- `FROZEN` is a local binding state (§5.2) with no type-level form, so it is lost across fields, calls and top-level `def`s. `def readOnlyLookup = freeze(...)` is a file-scope namespace variable, and the lambda capturing it has no way to know it is frozen.

### 1.6 `deterministic` over mutable arguments

The definition (§3.2, Axis 2) only mentions "identical pre-call states of any `mut` arguments/`this`". Yet `peek` reads `this.count` and `previewNext` reads a mutable `Counter` through a non-`mut` parameter. If equality of inputs means identity, memoization keyed on arguments is unsound; if it means state, the definition has to say so, and the cache key has to include that state. The same ambiguity undermines "thread-compatible" reads when another thread holds a writer.

### 1.7 Value-protocol defaults are not deterministic

§4.3.2 and §6 default `equals`, `hashCode`, `compareTo` and `toString` to `pure deterministic` on every Babble and Java class. A non-value `BabbleInstance.hashCode()` is `System.identityHashCode`, and so are `Object.hashCode()` and the default `Object.toString()`. Those differ between runs, so compile-time folding of anything that reaches them is wrong.

### 1.8 `move` on non-locals

§8.5 moves the top-level `def a`. A top-level definition is a namespace variable visible to closures, other functions and later REPL inputs. Definite-unassignment cannot cover that. `move` needs to be restricted to locals that are not captured, or the doc needs to say what a moved namespace variable is at runtime.

---

## 2. Contradictions with the current language and runtime

### 2.1 Division never throws

`Operators.div` is always double division: `1 / 0` is `Infinity`. The `a / b` "DivisionByZero" motivation is false today in §1.1, §3.2 (`nothrow`), §4.6, §5.1 and the comment on `sqrtApprox` in §8.1. Either integer division is a planned change (and should be stated as one), or these examples need another failing operation.

### 2.2 `==` never calls a Babble `equals`

`Operators.equal` uses `Object.equals`, which for a `BabbleInstance` is identity, or structural equality for a value class. A user-defined `equals` method is never consulted, a user `hashCode` is unused, and `<` needs a Java `Comparable` of the same class. §4.3.2 and the "delegates to `equals` (checked for custom classes)" row in §4.6 describe a protocol that does not exist. Only `toString` is real (`string + x` reaches `BabbleInstance.toString()`).

### 2.3 Missing constructs

The doc uses things the language does not have: `throw`, `|>`, `List(3, 1, 2)`, a `my.` root package (only `java` and `javax` are registered), and `JavaClass.CACHE`.

### 2.4 No AST or IR

§5.3 and §7.3 attach metadata to "AST / IR nodes" and to `OptimizerBase` passes. There is only the ANTLR parse tree, and the engine never runs the optimizers. SIMD vectorization and register promotion (§5.3.2) are not things a JVM-bytecode emitter does; they are HotSpot's business, and HotSpot cannot see Babble's `noalias` facts.

### 2.5 Which pass, which mode

> **Status:** resolved. `babble.typecheck=off|warn|error` has been removed (`TypeChecker` now runs unconditionally, fails compilation with `TypeCheckException` on any diagnostic, and always enforces declared types at runtime via `Types.check`), and `purity.md` now places effect inference, ownership tracking, and verification uniformly in `TypeChecker`.

Inference was originally split across `BlockScopes` + `TypeChecks` (§5.1 title), `TypeChecks` (§5.2) and `TypeChecker` (§7.1), which differed in an important way before mandatory type checking:

- `TypeChecker` only ran in `warn`/`error`, and a checker crash became a warning. Optimizations could not rest on it.
- Violations were specified as hard `AnalysisException`s (§3.1), which was the `TypeChecks` style.
- With `typecheck=off` there were no types to decide whether a constructor was `FRESH`, which callee a call reached, or whether a value class was deeply immutable, yet §7.2 promised enforcement in `off`.

---

## 3. Internal inconsistencies

### 3.1 `pure` and reads

§3.2 says `pure` performs no I/O, files included. `axiom pure Files.readString` is the flagship example (§3.2, §6.1, §8.1). §4.2.6 then forbids reading mutable outer state in checked `pure`, which contradicts design lesson 1 (`pure` is about writes, `deterministic` about reads).

### 3.2 `idempotent` has two meanings

One keyword covers `f(f(x)) = f(x)` and retry-safety (`f; f ≡ f`). No rule says which applies to a given function; under the retry reading every `pure` function is trivially idempotent. The definition also asks for "the same result and the same state", which excludes the doc's own `set.add` (it returns `true`, then `false`).

### 3.3 The effect form of `commutative`

It is defined for `mut` methods on a target (§3.2, Axis 3), and §1.2 requires "`mut` (on target)" for parallel accumulation. But `ensureAndTrack` and the `f` parameter of `parallelForEach` (§8.4) have neither `mut` nor a target. More generally there is no way to express mutation of captured variables. `AtomicInteger.addAndGet` also returns different values depending on order, so "commutes" must be restricted to the post-state.

### 3.4 Syntax order

> **Status:** bullet 3 resolved (§3.2 defines `unique noescape` as an exclusive borrow, whereas plain `unique` consumes); bullets 1 and 2 are still open.

- Parameters: the grammar says `MUT? NOESCAPE? UNIQUE?` (§4.1); the examples write `unique mut xs` (§3.2, §4.1, §8.5).
- Returns: §3.2 writes `unique (...) : Index`; the grammar and every other example put `unique` after the `:`.
- `noescape unique` on one parameter (§3.4) is allowed by the grammar but contradictory: a `unique` parameter hands over ownership, so the callee may store it.

### 3.5 The parallel map contract

`pure threadsafe` in §1.2 and §5.3.6; `pure deterministic threadsafe` in §4.1 and §8.4.

### 3.6 Absent `mut` changes meaning

In an unconstrained type `(x: List)`, the parameter may be mutated. Adding any modifier, for example `nothrow (x: List)`, flips the absence of `mut` to "may not be mutated" (§3.4). Adding an unrelated guarantee to a type silently changes another.

### 3.7 `threadsafe` and recursion

It is derived "after the SCC converges" from callees that are "already `threadsafe`" (§5.1.4). A recursive function is its own callee, so it can never get the property. `threadsafe` needs to be part of the optimistic fixed point.

### 3.8 The ownership state lattice

> **Status:** partially addressed (§5.2 separates `@FRESH` and `@UNIQUE` and removes the "four states" count); control-flow merge joins and sub-reference states (`p.child`, `xs.get(0)`) are still open.

§5.2 announces four states and lists six names (`FRESH`/`UNIQUE`, `PARAM(i)`/`THIS`, `FROZEN`, `MOVED`/`EXTERNAL`), with `MOVED` and `EXTERNAL` lumped although unrelated; the fifth bullet is not a state. No join is defined at control-flow merges, and there is no state for values derived from a parameter (`p.child`, `xs.get(0)`), which is where "mutating a non-`mut` parameter through an alias" actually happens.

### 3.9 Three representations of mutated parameters

> **Status:** resolved. `Type.Parameter.mut` on `Type.Function` (§7.1) is now the sole representation; the `EffectSet` invariant was moved to `Type.Function` and the §7.3 runtime mask was removed along with runtime effect descriptors.

`Type.Parameter.mut` (§7.1), an `EffectSet` invariant that mentions `mutParams` although the set does not contain it (§7.1), and a `BitSet`/`long` mask (§7.3).

### 3.10 Phasing

> **Status:** bullet 3 partially addressed (`freeze`, `Faces`, `Bootstrap`, and `Types.check` runtime effect machinery have been removed); bullets 1 and 2 are still open.

- Phase 1 "verifies", but verification is defined as `inferred.satisfies(declared)` (§5.1.5) and inference is Phase 2. Without inference every unannotated callee is unconstrained, so Phase 1 verified code needs annotations everywhere: the "attribute soup" the doc sets out to avoid.
- `terminating` / `total` is required for dead-code elimination, LICM and constant folding, but it is never inferred, not in the grammar keyword list, and absent from the roadmap (§3.2 says "Phase 2", §9.2 does not mention it).
- Phase 1 also includes `unique`/`move`/`freeze`, `Faces` checks and `Bootstrap` enforcement, which is most of the design.

### 3.11 Smaller ones

- §5.3.1: "or if `commutative` matches `f(b, a)`" reads as an alternative to `pure deterministic`; it is an additional condition.
- §1.2: LICM needs `nothrow` and termination whenever the loop may run zero times, not only "if guarded".
- §1.2: the predicate push-down example `filter(x -> p(f(x))) |> map(f)` evaluates `f` twice; it is only a win after `p ∘ f` is simplified.
- §8.4: `parallelForEach = axiom (xs, f) -> ...` uses `axiom` with no modifier, which the function-literal grammar allows and the declaration grammar forbids.
- Inferred extras on verified functions (§3.1): can a caller's verification rely on them? If so, callers depend on accidental properties; if not, the "inferred quietly" set is optimizer-only and the doc should say so. `impure` is all-or-nothing, with no way to hide one property.

---

## 4. Examples that fail under the doc's own rules

> **Status:** partially addressed (`§8.5` now declares `axiom mut deterministic java.util.HashMap.put`, `axiom pure deterministic java.util.HashMap.get`, and `: int` on the `readOnlyLookup` lambda; `encodeScore` in `§8.3` now uses `double`, where `injective` is not inferred and needs `axiom`). The remaining example issues below are still open.

| Example | Problem |
|---|---|
| `buildIndex` (§4.1), `buildLookup` (§8.5) | `java.util.HashMap()` has no pure-constructor axiom, so `m` is not `FRESH`. `axiom mut java.util.HashMap.put` lacks `deterministic`, so `pure deterministic` verification fails. |
| `readOnlyLookup.get(q)` (§8.5) | `HashMap.get` has no axiom, so the lambda cannot be `pure deterministic threadsafe`; it also returns an object, not the `int` the parameter type asks for. |
| `buildStats` (§5.2) | Said to be "inferred", from an explicitly annotated signature; `xs.size()` is an unconstrained Java call. |
| `sumInt` (§8.4), `encodeScore` (§8.3) | Use `axiom` where §3.3 says single-op wrappers and compositions are inferred. |
| `Path.of` (§8.1) | Axiomatized `nothrow`; it throws `InvalidPathException`. |
| `hypot` (§4.1) | Calls `Math.sqrt` before the axiom for it is declared. |
| `sqrtApprox` (§8.1) | The comment says `nothrow` is not inferred because of the division; see §2.1. |

---

## 5. Gaps and loose threads

### 5.1 No effect polymorphism

> **Status:** dynamic and reassigned-`def` callee transfer rule resolved (§5.1.4 drops `pure`, `deterministic`, `nothrow`, and `threadsafe`, and §3.4 disallows `dynamic` coercion into effect-constrained function types); higher-order effect polymorphism is still open.

`apply = (f, x) -> ( f(x) )`, and any Java higher-order function, cannot say "pure if the callback is". The doc dismisses Koka-style rows (§2) but offers nothing in their place, so every combinator is either an `axiom` or unconstrained. The transfer rules (§5.1.4) also do not say what an unknown or `dynamic` callee does to `pure` and `deterministic` (only `nothrow` is covered), nor what happens for calls through reassigned `def`s.

### 5.2 Axiom scoping and timing

> **Status:** `ScriptRuntime` and `ModuleInterface` storage resolved (§6.1–§6.2 resolve axioms purely at compile time in `TypeChecker` across `ScopeAnalysis`, `ModuleInterface` for `export axiom`, `AnalysisSession`, and `BabbleScriptEngine`, with zero runtime state). Declaration order, overloads, instance-method targets, and how `export axiom` binds in `ModuleInterface` remain open.

- Axioms were "stored in the active `ScriptRuntime`" (§6.1) but consumed by static analysis at `compile()`, before a runtime exists. `engine.assumeEffects` (§6.2) was engine-level, a third scope.
- Unspecified: declaration order (axiom after use), REPL sessions, overloads (`Math.max` has four, `Files.readString` two), and instance-method targets reached through a class (`java.util.HashMap.put`).
- `export axiom ... java.lang.Math.sin` exports no name, and modules export names.
- Modules were compiled at import time, after the importer was analysed, and typed as `dynamic` (now addressed for static `import("...ba")` via M2/M3 `ModuleInterface` and compile-time `export axiom` resolution, though dynamic `import(expr)` and import cycles still type as `dynamic`).

### 5.3 The security claim

The executive summary promises to "secure computation", but any script can write `axiom pure` on anything. The audit flag (§3.1) is mentioned; no policy mechanism (who may declare axioms, how a host forbids them) is specified.

### 5.4 Backward compatibility

Enforced-pure value-class constructors and default-pure value-class methods (§4.3.1) turn existing value classes that print into errors. That contradicts the non-goal in §1.3 that unannotated scripts keep working. Whether "default" means verified (error) or inferred (silent) is not stated.

### 5.5 Runtime enforcement

> **Status:** resolved. All runtime effect enforcement and descriptors have been removed from `purity.md` (`Faces` thread/frame context, `Interpreter` and `BabbleInstance.toString()` active-effect tracking, `Bootstrap` linkage checks, `Calls.callWithEffects`, and `Types.check` `fx|` descriptors). Calling an untyped `dynamic` value drops `pure`/`deterministic`/`nothrow`/`threadsafe` statically in `TypeChecker` (§5.1.4), and effect-constrained `Type.Function` slots reject `dynamic` coercion at compile time (§3.4, §6.3, §7.2, §7.3), leaving `babble-runtime.jar` with zero effect code.

- Propagating active requirements "via thread/frame context" (§6.3, §7.3.3) conflicted with "zero steady-state per-call overhead" (§7.3.2), and the context was lost on `parallelStream` worker threads, the very case §6.3 used.
- Linkage-time checks covered the four guarded fast paths; megamorphic sites that went generic needed a check per call.
- What `Calls.callWithEffects` did on a mismatch (which error, which wording) was undefined.

### 5.6 Local closures

A closure that reassigns a local of its enclosing function writes an "outer def" (§4.2.6), so it is not `pure`, so a `pure` function cannot call it. The fresh-local rule (§4.2.2) has no counterpart for local helper closures.

### 5.7 Applicability of algebraic properties

Nothing checks that `associative` is on a `(T, T) : T` function, `commutative` on at least two parameters of the same type ("symmetric parameters" is undefined for three or more), or `monotone` on ordered types.

### 5.8 Operator table

§4.6 omits `-`, mixed int/double arithmetic, `string + non-string` (which calls `toString`, so it is not intrinsically pure), member selection (which can fail), `is`, `not`, `and`/`or`.

### 5.9 Grammar

The new contextual keywords have no ambiguity analysis: `pure (x)` (call of a function named `pure`, or a modifier before a block or parameter list), `move x` (juxtaposition), and modifiers in type position against `simpleType: ID ('.' ID)*`. The rule in `CLAUDE.md` that no newline may separate an expression from a following `(` exists because of exactly this kind of ambiguity.

### 5.10 Drafting leftovers

- The last bullet of §3.1 mode 3 ("If a property *is* still partially verifiable...") says nothing.
- "Unlike the old purity draft" (§3.2) refers to a document that is not in the repository.
- §5.4 does not say where argument generators for property-based checks come from.
