# Babble Axioms (`org.babblelang.axioms`)

Every assumption the analysis, compiler, interpreter, and optimizer make about specific Java classes, third-party nullability annotations, or Babble built-ins lives in this directory ([BIP019](../../../../../../docs/design/bip019_refactoring-axioms.md)).

## Format and Canonical Order

- **One file per Java package:** axioms about the classes of a package `a.b` live in `a/b/curated.ba`.
- **Babble prelude:** `builtins.ba` declares Babble's ordinary built-in functions (`int`, `assert`, `same`, `classof`, …).
- **Third-party annotation stubs:** one `.java` stub per annotation, declaring what the annotation means (`@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked`, `@MeansNullUnmarked`).
- **Canonical order** (enforced by `AxiomsTestCase` and `babblefmt`):
  1. Top-level axioms are sorted by the target class's binary name (`java.util.AbstractMap$SimpleEntry` after `java.util.AbstractMap`), with a class's `class (...)` axiom before standalone member axioms for that class, and member axioms sorted by member name then parameter descriptors.
  2. Class words come in fixed order: `exact`, `nullmarked`, `immutable`.
  3. Within `class (...)` and `static (...)`, members are sorted by name (`*` first, then `new`, then alphabetically), then by parameter Java erasures compared as JVM type descriptors.
  4. Effect words come in canonical order (`io`, `volatile`, `pure`, `mut`, `shared`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `view`, `shallow`, `fresh`, `foldable`, `unique`).
  5. Every `.ba` file is formatter-clean; comments sit directly above an axiom or member.

## Files

- `java/lang/curated.ba` — protocol contracts (`Comparable`, `Iterable`, `Object`) and `String`
- `java/util/curated.ba` — core collections, maps, entries, iterators, `Comparator`, and collection/entry constructors

## `QUESTIONABLE` Axioms

- `java/lang/curated.ba` : `java.lang.Comparable` (`compareTo` assumed pure on every implementation)
- `java/lang/curated.ba` : `java.lang.Iterable` (`iterator` assumed pure nothrow view on every implementation)
- `java/lang/curated.ba` : `java.lang.Object` (`hashCode` and `toString` assumed pure volatile on `Object` itself)
- `java/lang/curated.ba` : `java.lang.Object.equals` (assumed pure on every implementation)
- `java/util/curated.ba` : `java.util.Collection`, `java.util.List`, `java.util.Map`, `java.util.Iterator`, `java.util.ListIterator` (core collection/iterator effect contracts assumed for every implementation)
- `java/util/curated.ba` : `java.util.Comparator` (`compare` assumed pure on every implementation)
