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

*(Axiom files are added across `BIP019-P4`–`P7`.)*

## `QUESTIONABLE` Axioms

*(None yet; doubtful axioms kept during migration are tagged `;; QUESTIONABLE` and indexed here.)*
