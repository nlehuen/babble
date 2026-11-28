# Babble Axioms (`org.babblelang.axioms`)

Every assumption the analysis, compiler, interpreter, and optimizer make about specific Java classes, third-party nullability annotations, or Babble built-ins lives in this directory ([BIP019](../../../../../../docs/design/bip019_refactoring-axioms.md)).

## Format and Canonical Order

- **One file per Java package:** axioms about the classes of a package `a.b` live in `a/b/curated.ba`.
- **Babble prelude:** `builtins.ba` declares Babble's ordinary built-in functions (`int`, `assert`, `same`, `classof`, …).
- **Third-party annotation stubs:** one `.java` stub per annotation, declaring what the annotation means (`@MeansNonNull`, `@MeansNullable`, `@MeansNullMarked`, `@MeansNullUnmarked`).
- **Canonical order** (enforced by `AxiomsTestCase` and `babblefmt`):
  1. Top-level axioms are sorted by the target class's binary name (`java.util.AbstractMap$SimpleEntry` after `java.util.AbstractMap`), with a class's `class (...)` axiom before standalone member axioms for that class, and member axioms sorted by member name then parameter descriptors.
  2. Class words come in fixed order: `exact`, `notnull`, `immutable` (`BIP030` renamed `nullmarked` → `notnull`).
  3. Within `class (...)` and `static (...)`, members are sorted by name (`*` first, then `new`, then alphabetically), then by parameter Java erasures compared as JVM type descriptors.
  4. Effect words come in canonical order (`io`, `...`, `volatile`, `pure`, `mut`, `shared`, `nothrow`, `idempotent`, `commutative`, `associative`, `monotone`, `injective`, `view`, `shallow`, `fresh`, `foldable`, `unique`), where `...` keeps tier-7 bytecode-inferred effects while declaring return/parameter nullability (`BIP030`).
  5. Every concrete reference type in a Java axiom signature or return type explicitly states `!` (non-null) or `?` (nullable) (unless in a `| null` union); primitives take neither `!` (nullable boxed uses `?`), and type variables distinguish `T` (parametric), `T!` (non-null), and `T?` (nullable). The element nullness of an array return type is honored too : `(array of T?)!` (`Arrays.copyOf`, `Collection.toArray`, `Stream.toArray`, whose elements may be `null` even in a `class notnull`) types the result's elements nullable, `(array of java.lang.Throwable!)!` non-null (`BIP032` `J3`).
  6. Every `.ba` file is formatter-clean; comments sit directly above an axiom or member.

## Files

### Built-in Prelude
- `builtins.ba` — ordinary Babble built-ins (`assert`, `bool`, `byte`, `char`, `classof`, `double`, `float`, `int`, `long`, `same`, `short`, `string`, `suppose`)

### Curated Java Package Axioms
- `java/io/curated.ba` — `StringWriter`
- `java/lang/curated.ba` — protocol contracts (`Comparable`, `Iterable`, `Object`), `Class`, `String`, `StringBuilder`/`StringBuffer`, `CharSequence`, boxed wrappers, `Math`/`StrictMath`, `System`, `Runtime`, `ProcessBuilder`, `Thread`, `Throwable` and `Error` constructors (JFR's `jfrTracing` read, JDK 22+)
- `java/lang/reflect/curated.ba` — `Proxy`
- `java/math/curated.ba` — `BigInteger`
- `java/nio/curated.ba` — `CharBuffer`
- `java/time/curated.ba` — `LocalDate`
- `java/util/curated.ba` — core collections, maps, entries, iterators, `Comparator`, `Optional`, `Arrays`, `Collections`, `StringTokenizer`, and collection/entry constructors
- `java/util/function/curated.ba` — null-marked `java.util.function` functional interfaces
- `java/util/regex/curated.ba` — `Pattern`
- `java/util/stream/curated.ba` — `BaseStream`, `Stream`, primitive streams, `StreamSupport`
- `org/babblelang/runtime/curated.ba` — `Range`, `IntRange`, `LongRange`, `CharRange`, `DoubleRange`, `ComparableRange`

### Third-Party Nullness Annotation Stubs
- `android/annotation/NonNullAxioms.java`, `NullableAxioms.java`
- `androidx/annotation/NonNullAxioms.java`, `NullableAxioms.java`
- `edu/umd/cs/findbugs/annotations/NonNullAxioms.java`, `NullableAxioms.java`
- `io/micronaut/core/annotation/NonNullAxioms.java`, `NullableAxioms.java`
- `jakarta/annotation/NonnullAxioms.java`, `NullableAxioms.java`
- `javax/annotation/CheckForNullAxioms.java`, `NonnullAxioms.java`, `NullableAxioms.java`
- `lombok/NonNullAxioms.java`
- `org/babblelang/effects/NotNullAxioms.java`
- `org/jetbrains/annotations/NotNullAxioms.java`, `NullableAxioms.java`
- `org/jspecify/annotations/NonNullAxioms.java`, `NullableAxioms.java`, `NullMarkedAxioms.java`, `NullUnmarkedAxioms.java`
- `reactor/util/annotation/NonNullAxioms.java`, `NullableAxioms.java`

## `QUESTIONABLE` Axioms

- `java/lang/curated.ba` : `java.lang.Comparable` (`compareTo` assumed pure on every implementation)
- `java/lang/curated.ba` : `java.lang.Iterable` (`iterator` assumed pure shared nothrow view on every implementation)
- `java/lang/curated.ba` : `java.lang.Object.equals` (assumed pure on every implementation)
- `java/lang/curated.ba` : `java.lang.Object.hashCode` and `java.lang.Object.toString` (assumed pure volatile on every implementation)
- `java/util/curated.ba` : `java.util.Collection`, `java.util.List`, `java.util.Map`, `java.util.Iterator`, `java.util.ListIterator` (core collection/iterator effect contracts assumed for every implementation)
- `java/util/curated.ba` : `java.util.Comparator` (`compare` assumed pure on every implementation)
