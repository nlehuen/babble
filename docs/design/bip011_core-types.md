# BIP011: Babble core types

| | |
|---|---|
| **BIP** | 011 |
| **Date** | 2026-11-18 |
| **Status** | Draft (`BIP011-D1`–`D13` decided) |
| **Depends on** | Bytecode compiler, `RM001` primitive lowering & `RM003` JVM/Java interface bridges ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md)), object model, Java interop & `RM003` `LambdaMetafactory` SAM adapters ([bip002_object-model.md](bip002_object-model.md)), function effects & `RM002` ([bip007_effects.md](bip007_effects.md)), mandatory static typing, nullables & generics ([bip008_static-typing.md](bip008_static-typing.md)), `.batest` coverage harness ([bip009_test-coverage.md](bip009_test-coverage.md)), type-checker architecture ([bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)) |
| **Scope** | Core scalar types (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`), core collection & entry types (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`), Babble-to-Java nullability alignment (`@NotNull`/`@Nullable`/JSpecify, mapped parametric-nullness `java.base` contracts, postfix `e!`, outbound & `RM003` inbound defensive boundary checks), collection invariance & `borrow` covariance, universal call-site type arguments `C(T)(...)`, `Babble.g4` & `Formatter`, `BIP007` built-in effect profiles, and 8-primitive `RM001`/`RM003` bytecode lowering |

---

## 1. Summary

This proposal establishes a complete set of **Babble core types** that map **transparently and with zero wrapper allocation** to standard JVM primitives, `java.lang.String`, and JDK `java.util` collection interfaces, while soundly reconciling Babble's strict static nullability (`T` vs `T?`) with Java's soft nullability model:

1. **Complete scalar roster (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`).**
   Every JVM primitive `p` maps bijectively to non-nullable Babble `p`, while its nullable counterpart `p?` maps bijectively to the boxed wrapper `java.lang.P` (`int` $\leftrightarrow$ `int`, `int?` $\leftrightarrow$ `java.lang.Integer`, `bool` $\leftrightarrow$ `boolean`, `bool?` $\leftrightarrow$ `java.lang.Boolean`, `double` $\leftrightarrow$ `double`, `double?` $\leftrightarrow$ `java.lang.Double`, and likewise for `byte`, `short`, `long`, `float`, `char`). `string` is strictly non-null `java.lang.String` (`string?` is nullable `String`) and directly exposes `java.lang.String`'s instance methods (`"abc".toUpperCase()`, `s.trim()`), while all 8 primitives remain memberless values lowered to unboxed JVM slots, closure `*Cell`s, class fields, and `$spec` entry points (`RM001`).
2. **Core collection & entry types (`list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`).**
   Core collection types *are* the standard JDK interfaces (`java.util.List<T>`, `Set<T>`, `SortedSet<T>`, `Map<K, V>`, `SortedMap<K, V>`, `Map.Entry<K, V>`), paired with deterministic default constructors (`ArrayList`, `LinkedHashSet`, `TreeSet`, `LinkedHashMap`, `TreeMap`, `AbstractMap.SimpleImmutableEntry`) supporting empty `()`, variadic elements/entries, `from:`, `capacity:`, and `comparator:`. Because no wrapper object is introduced, explicit Java implementations (`java.util.LinkedList(int)()`, `java.util.concurrent.ConcurrentHashMap(string, int)()`) satisfy `list(int)` and `map(string, int)` directly.
3. **Universal call-site type application `C(T)(...)` and inferred `C(...)`.**
   Explicit type arguments `list(int)()`, `Box(int?)(null)`, `id(double)(1)` and omitted inferred type arguments `def xs: list(int) = list()`, `list(1, 2, 3)` work uniformly across core collections, Java generic classes, and Babble generic classes/functions.
4. **Sound Java nullability alignment & defensive soft-`@NotNull` checks.**
   In Babble source annotations, every type written without `?` denotes a strictly non-null value: writing `java.lang.Integer` in Babble means `@NotNull java.lang.Integer` (alias of `int`), whereas `java.lang.Integer?` means `@Nullable java.lang.Integer` (alias of `int?`). Across the Java reflection boundary, unannotated Java primitives map to non-null `T` (`int.class` $\to$ `int`), while unannotated Java references map conservatively to nullable `T?` (`Integer.class` $\to$ `int?`, `String.class` $\to$ `string?`, `Object.class` $\to$ `any?`)—avoiding Kotlin's unsound `T!` platform types. Ergonomics are preserved via **mapped parametric-nullness signatures** for core and common `java.base` APIs (`list(T).get(i): T` vs `map(K, V).get(k): V?`, `String.trim(): string`, `Object.toString(): string`), recognition of JSpecify / `@NotNull` / `@Nullable` bytecode annotations, and a postfix non-null assertion `e!`. Because Java nullability annotations and erased generics are *soft* on the JVM side, Babble emits fast, single-branch **defensive runtime checks** (`JavaTypes.checkNotNull`) whenever a soft Java reference enters a non-null Babble type `T`, failing immediately at the boundary (`Type error : expected <T>, got null`) rather than corrupting internal invariants or crashing during `RM001` unboxing.
5. **Mutable generic invariance + `BIP007` `borrow` covariance & built-in effects.**
   Mutable collections and mutable generic classes become **invariant** (`list(int)` is not assignable to `list(int?)`, closing `BIP008-P6b`'s heap-pollution hole), while immutable `value class`es, `entry(K, V)`, and read-only non-`mut` `borrow` collection parameters remain safely covariant. Core types ship with built-in `BIP007` effect profiles (`pure deterministic` for scalars/`string`/`entry`; `hermetic deterministic` constructors, `hermetic borrow` readers, and `hermetic mut` mutators for collections).

---

## 2. Motivation, Current Gaps & Goals

### 2.1 Four Concrete Friction Points in Current Babble

Following `BIP008` (mandatory static typing, unions, nullables, generics), `RM001` (unboxed `int`/`double`/`bool` lowering), `RM002` (`BIP007-P1` static effects), and `RM003` (compiled Babble JVM interfaces, direct Java-interface `implements` bridges, and cached `LambdaMetafactory` SAM adapters), four day-to-day friction points and soundness gaps remain at the boundary between Babble and the JDK:

1. **Incomplete scalar types and broken wrapper nullability.**
   * Babble currently has only four built-in scalar types: `int`, `double`, `bool`, and `string`. Common JVM types—`long` (timestamps, file sizes, seeds, counters), `float` (GPU/SIMD/ray-tracer math, audio), `byte` (I/O buffers, image channels), `short`, and `char` (lexers, `String.charAt`)—have no literals, no built-in conversions, and no `RM001` unboxed lowering.
   * Worse, `Operators.java` currently treats any `Number` other than `Integer` via `doubleValue()`, so adding two Java `Long` values (`Long.valueOf(1) + Long.valueOf(2)`) silently produces a `double` (`3.0`), losing 64-bit integer precision above $2^{53}$.
   * Meanwhile, `TypeChecker.fromJava` currently maps both `int.class` and `java.lang.Integer.class` to non-null `Type.INT`, and `JavaTypes.checkBoxed` unconditionally throws `Type error : expected int, got null` whenever a Java method declared to return `Integer`, `Double`, or `Boolean` returns `null`—making standard nullable Java APIs (`Map<String, Integer>.get(k)`, `Integer.getInteger(...)`) impossible to call when they legitimately return `null` unless routed through `Object` (`any?`).
2. **Opaque `string` values.**
   At runtime, a Babble `string` *is* a `java.lang.String`. Yet `Members.isJavaObject` (`!(v instanceof String)`) and `TypeChecker.member` explicitly forbid member selection on `string`, rejecting `"hello".length()`, `"hello".substring(0, 2)`, and `"hello".trim()` with `Not a scope : string` and forcing boilerplate `java.lang.String.valueOf(...)` or helper gymnastics.
3. **Verbose collection construction and the `BIP008-P6b` mutable covariance hole.**
   * Creating a list or map today requires importing concrete JDK classes (`def ArrayList = import("java.util.ArrayList")`) and writing `def xs: ArrayList(int) = ArrayList()` because `Babble.g4`'s `callParameters` rule parses only value expressions, making explicit type instantiation `ArrayList(int)()` a syntax error whenever the type argument uses `?`, `|`, or function syntax (`ArrayList(int?)()`).
   * In `BIP008-P6b`, parameterized types were made uniformly covariant (`covariantAssignable`), allowing pure Babble code with zero casts and zero Java helpers to corrupt non-null collections:
     ```babble
     def ArrayList = import("java.util.ArrayList")
     def xs: ArrayList(int) = ArrayList()
     xs.add(1)
     def ys: ArrayList(int?) = xs   ;; Compiles today under BIP008-P6b!
     ys.add(null)                   ;; Inserts null into ArrayList(int)!
     ```
4. **The Java nullability crux: strict Babble `T` vs soft Java `@NotNull`.**
   In Babble (`BIP008`), nullability is an explicit, sound property of the type lattice: `T` never inhabits `null`, `T?` is `T | null`, and internal `BIP003-D9` runtime checks were eliminated so `RM001` can unbox primitives and `RM002` (`BIP007-D1`) can prove `nothrow`. In Java, however, every reference descriptor (`Ljava/lang/Integer;`, `Ljava/lang/String;`, `Ljava/util/List;`, erased `T`) admits `null` in bytecode, and `@NotNull` / `@NonNull` annotations are **soft**—unenforced by the JVM verifier and easily violated by unannotated callers, unchecked casts, or partially initialized fields. Aligning Babble's core types transparently with Java types therefore requires an explicit, principled contract governing:
   * what Java class names mean in Babble annotations vs Java reflection signatures,
   * how generic JDK collection methods (`List.get` vs `Map.get`) express parametric vs inherently nullable returns, and
   * where minimal defensive runtime checks must stand guard at the Java boundary.

### 2.2 Goals and Non-Goals

**Goals:**

* **True zero-cost JVM identity:** Every core type is represented at runtime by the exact JVM primitive, boxed wrapper, `java.lang.String`, or `java.util.*` interface expected by Java libraries—zero adapter wrappers, zero copy-on-boundary marshaling, and zero additions outside `java.base` (`BIP006` §3).
* **Deterministic defaults with open Java polymorphism:** Calling `list(...)`, `set(...)`, `sortedset(...)`, `map(...)`, or `sortedmap(...)` instantiates a predictable, deterministic `java.util` class (`LinkedHashSet` / `LinkedHashMap` for insertion-order determinism aligned with `BIP007`), while variables typed as `list(T)` or `map(K, V)` seamlessly accept any `java.util.List<T>` or `java.util.Map<K, V>` implementation.
* **Complete soundness across the nullability & generics boundary:** Eliminate platform-type (`T!`) ambiguity and mutable collection covariance holes, while keeping day-to-day collection and `String` code cast-free through parametric-nullness JDK signatures, `@NotNull`/`@Nullable`/JSpecify recognition, flow narrowing, `?.`, and postfix `e!`.
* **Full `RM001` & `RM002` integration:** Extend unboxed primitive locals, closure cells, class fields, and `$spec` entry points to all 8 non-nullable JVM primitives, and equip core types with built-in `BIP007` effect profiles so `pure` and `hermetic` code works out of the box.

**Non-Goals:**

* **Bracket indexing (`xs[i]`) and collection bracket literals (`[1, 2]`):** Tracked separately (`BIP008` §5.1) and orthogonal to `list(...)` / `map(...)` constructor calls.
* **Unsigned integer types (`ubyte`, `uint`, `ulong`) or arbitrary-precision numbers (`BigInteger`, `BigDecimal`):** Standard `java.math.BigInteger` / `BigDecimal` and `Integer.compareUnsigned` remain directly accessible via Java interop or future `std/` modules (`BIP005-D5`).
* **Shadowing read-only vs mutable collection interface hierarchies:** Unlike Kotlin's `kotlin.collections.List` vs `MutableList`, Babble uses `BIP007`'s `borrow` and `mut` effect modifiers directly on `list(T)` (§3.2, §5.4).

---

## 3. State of the Art : Nullability & Core Types Across JVM Languages

Every statically typed language hosted on the JVM faces the same structural tension: **the JVM distinguishes `int` from `java.lang.Integer` at the bytecode descriptor level, erases generic type arguments to `Object`, leaves `java.base` largely unannotated in classfiles, and treats `@NotNull` annotations as unverified metadata.** Surveying how existing JVM languages and specifications tackled these constraints clarifies the exact trade-offs available to Babble.

### 3.1 Comparative Matrix

| Dimension | **Kotlin (1.0+ & K2 + JSpecify)** | **Scala 3 (`-Yexplicit-nulls`)** | **Ceylon (`1.0–1.3`)** | **JSpecify `1.0` (Java Ecosystem)** | **Babble (`BIP011`)** |
|---|---|---|---|---|---|
| **Primitives vs Wrappers** | Unified in source: `Int` $\to$ `int`, `Int?` / generic `List<Int>` $\to$ `java.lang.Integer`. (`java.lang.Integer` in Kotlin source is deprecated/mapped to `Int`.) | `Int` $\to$ `int`; `Int \| Null` boxes to `Object`/`Integer` (separate `java.lang.Integer` class exists for interop). | `Integer` (64-bit `long`) and `Float` (`double`) only; narrower Java primitives require interop bridges. | Separate `int` vs `@Nullable Integer` / `@NonNull Integer` (`TYPE_USE`). | Unified for all 8 JVM primitives: `int` $\leftrightarrow$ `int`, `int?` $\leftrightarrow$ `Integer`; in Babble annotations `java.lang.Integer` == `int` (`@NotNull Integer`) and `java.lang.Integer?` == `int?`. |
| **Core Collection Mapping** | Compiler-magic **shadow interfaces** (`kotlin.collections.List<out T>` vs `MutableList<T>`), erasing to `java.util.List` via synthetic stub/intrinsic translation. | Uses `scala.collection.immutable.*` (distinct runtime classes requiring `jdk.CollectionConverters` copies for Java interop). | Distinct `ceylon.language.List` hierarchy requiring adapter wrappers around `java.util.List`. | Direct `java.util.List<T>`, `Set<T>`, `Map<K, V>`. | **Direct zero-cost alias** to `java.util.List<T>`, `Set<T>`, `SortedSet<T>`, `Map<K, V>`, `SortedMap<K, V>`, `Map.Entry<K, V>` with deterministic default constructors. |
| **Unannotated Java References** | **Platform types (`T!`)**: treated simultaneously as `T` and `T?` with zero static error; inserts hidden check on assignment to `val x: T`. Unsound if `T!` flows through inference (`val x = javaCall()`). | **Flexible types (`T \| UncheckedNull`, printed `T?` with soft rules)**: allows member selection without null check unless narrowed; still permits unsound NPEs across unannotated chains. | Treated conservatively as optional `T?` (`T \| Null`) unless annotated or checked via `exists` / `assert`. | Null-unspecified (`T*`) outside `@NullMarked`; non-null by default inside `@NullMarked` scopes. | **Strict conservative `T?`** for unannotated Java references (`String` $\to$ `string?`, `Integer` $\to$ `int?`, `Object` $\to$ `any?`) — **no `T!` platform types**. |
| **Parametric Nullness on Collections** | Supported on mapped `kotlin.collections`: `List<T>.get(i): T`, whereas `Map<K, V>.get(k): V?`. | Limited on raw `java.util` (historically returned flexible `E!`, requiring manual `.nn` or converters). | Native in `ceylon.language`, awkward on raw `java.util.Map.get`. | First-class: `<T extends @Nullable Object>` allows `List.get: T` vs `Map.get: @Nullable V`. | **Built-in JDK Nullability Overlay** with parametric nullness: `list(T).get(i): T` (`int` or `int?`), `map(K, V).get(k): V?`, `getOrDefault(k, d): V`. |
| **Soft `@NotNull` Boundary Defense** | **Defensive runtime intrinsics**: emits `Intrinsics.checkNotNullExpressionValue` on `@NotNull` Java returns and `checkNotNullParameter` at public entry points. | Trusts `@NotNull` / `.nn` casting erasably; NPE can surface later inside Scala expressions if Java lied. | Runtime type/null checks generated at Java interop bridges. | Compile-time checker warnings only (ErrorProne / NullAway); **zero JVM runtime enforcement**. | **Defensive runtime boundary checks** (`JavaTypes.checkNotNull`) at Java $\to$ Babble non-null returns/fields and Java $\to$ Babble callback parameters (`Faces`). |
| **Mutable Collection Variance** | Split interfaces: `List<out E>` (covariant) vs `MutableList<E>` (invariant). Note: `MutableList` typed as `List` can still be mutated by original holder (`aliasing` not prevented). | `immutable.List[+A]` (covariant) vs `mutable.Buffer[A]` (invariant). | `List<out Element>` (read-only interface) vs mutable builders. | Invariant `List<E>`; use-site wildcards `List<? extends E>` / `List<? super E>`. | **Invariant `list(T)`** on mutable bindings + **read-only covariance on `BIP007` `borrow` (non-`mut`) parameters** (plus Java `? extends T`). |
| **Postfix Non-Null Assertion** | `e!!` (throws `NullPointerException`). | `e.nn` extension method (throws `NullPointerException`). | `assert (exists x)` statement. | `Objects.requireNonNull(e)`. | **`e!`** (throws normalized `BabbleException("Type error : expected <T>, got null")`). |

### 3.2 Key Architectural Takeaways for Babble

1. **Why Babble rejects `T!` platform types (Kotlin) and `UncheckedNull` flexible types (Scala 3).**
   Kotlin introduced platform types (`T!`) in 2012 because neither JSpecify nor flow-sensitive union types existed in the Java ecosystem, and forcing `!!` on every `String.trim()` or `List.get(0)` call would have been intolerable. In hindsight, `T!` is Kotlin's single biggest source of production `NullPointerException`s: an unannotated `val s = map.get(k)` infers `String!` and silently bypasses null checking until it crashes deep inside pure Kotlin code. Babble already made the deliberate choice in `BIP008` to abolish `dynamic` so that static types can drive **unboxed `RM001` primitive lowering** and **verified `RM002` `nothrow` effect guarantees**. Admitting `T!` would break both invariants: unboxing an `Integer!` that holds `null` triggers a raw JVM `NullPointerException`, and calling a method on `String!` inside a `nothrow` function would silently throw. Instead, Babble gets Kotlin's ergonomics *without* `T!` by combining:
   * **mapped parametric-nullness signatures** for `string`, core collections, and everyday `java.base` APIs (§6.4),
   * **JSpecify / `@NotNull` / `@Nullable` bytecode annotation reading** (§6.3), and
   * concise **`?.`**, flow-sensitive narrowing, and **postfix `e!`** for genuinely unannotated third-party Java calls (§6.5).
2. **Why Babble adopts Kotlin's defensive boundary checks for soft `@NotNull` contracts.**
   As JSpecify itself documents, Java nullability annotations are *cooperative static contracts*, not JVM verifier guarantees: legacy Java callers, reflection, serialization, or raw `List` casts can still hand `null` across a `@NotNull` return or generic `TypeVariable` return. Kotlin solved this decisively with `Intrinsics.checkNotNullExpressionValue` (a single `if (v == null) throw ...` branch that HotSpot folds into implicit null checks or predicts at >99.99% accuracy). Adopting the same boundary check in Babble (`JavaTypes.checkNotNull`) guarantees **fail-fast blame accuracy** at the exact offending Java call site and preserves **zero internal null checks** inside Babble code (§6.6).
3. **Why Babble maps directly to `java.util.*` + `BIP007` `borrow` instead of shadow interfaces.**
   Ceylon's and Scala's custom collection classes require $O(N)$ conversion or adapter wrappers at every Java API call, violating Babble's transparent-interop goal and `BIP006`'s tiny `java.base` runtime boundary. Kotlin avoided runtime wrappers only by building deep compiler intrinsic translation tables (`JvmBuiltIns`, synthetic `removeAt` vs `remove(int)` bytecode rewriting, fake `kotlin.collections.*` symbols). Because Babble already has static receiver/parameter mutation tracking (`BIP007` `mut` and `borrow`), Babble does not need a fictitious `ReadOnlyList` interface: `list(T)` *is* `java.util.List<T>`, mutation requires `mut` in effect-checked code, and `borrow xs: list(any)` provides safe read-only covariance *with stronger aliasing guarantees than Kotlin's `List<out T>`* (§5.4).

---

## 4. Core Scalar Types

### 4.1 Complete Scalar Roster & Transparent JVM Mapping (`BIP011-D2`)

Babble provides **nine reserved core scalar types**—all eight JVM primitive types plus `string`:

| Babble Non-Null Type | Babble Nullable Type | Equivalent Babble Java-Name Alias | JVM Unboxed Descriptor (`RM001`) | JVM Boxed / Reference Class (`classof`) | Range / Representation |
|---|---|---|---|---|---|
| `byte` | `byte?` | `java.lang.Byte` / `java.lang.Byte?` | `B` (`byte`, `I` on stack) | `java.lang.Byte` | $-128 \dots 127$ (8-bit signed two's complement) |
| `short` | `short?` | `java.lang.Short` / `java.lang.Short?` | `S` (`short`, `I` on stack) | `java.lang.Short` | $-32{,}768 \dots 32{,}767$ (16-bit signed) |
| `int` | `int?` | `java.lang.Integer` / `java.lang.Integer?` | `I` (`int`) | `java.lang.Integer` | $-2^{31} \dots 2^{31}-1$ (32-bit signed) |
| `long` | `long?` | `java.lang.Long` / `java.lang.Long?` | `J` (`long`, 2 JVM slots) | `java.lang.Long` | $-2^{63} \dots 2^{63}-1$ (64-bit signed) |
| `float` | `float?` | `java.lang.Float` / `java.lang.Float?` | `F` (`float`) | `java.lang.Float` | IEEE 754 binary32 |
| `double` | `double?` | `java.lang.Double` / `java.lang.Double?` | `D` (`double`, 2 JVM slots) | `java.lang.Double` | IEEE 754 binary64 |
| `bool` | `bool?` | `java.lang.Boolean` / `java.lang.Boolean?` | `Z` (`boolean`, `I` on stack) | `java.lang.Boolean` | `true` or `false` |
| `char` | `char?` | `java.lang.Character` / `java.lang.Character?` | `C` (`char`, `I` on stack) | `java.lang.Character` | `\u0000` $\dots$ `\uFFFF` ($0 \dots 65{,}535$ UTF-16 code unit) |
| `string` | `string?` | `java.lang.String` / `java.lang.String?` | `Ljava/lang/String;` | `java.lang.String` | Immutable UTF-16 sequence (`java.lang.String`) |

**Key invariants:**

* **Unified type identity:** In Babble type annotations, `java.lang.Integer` resolves to the *exact same* `Type.INT` as `int`, and `java.lang.Integer?` resolves to `int?` (`Type.union(Type.INT, Type.NULL)`). Likewise for all eight wrapper classes and `java.lang.String` (`Type.STRING`). Diagnostics always print the canonical Babble name (`int`, `int?`, `long`, `string`, etc.).
* **Generic type arguments box transparently:** Because JVM generics erase to `Object`, `list(int)` holds non-null `java.lang.Integer` elements at runtime while statically enforcing non-null `int` at every read and write; `list(int?)` holds nullable `java.lang.Integer` elements and types reads/writes as `int?`.
* **`classof` parity (`BIP002` §4.11):** Calling `classof(x)` on any non-null scalar returns the `JavaClass` wrapper of its boxed/reference JVM class (`classof(1L) == java.lang.Long`, `classof('a') == java.lang.Character`, `classof(1.5f) == java.lang.Float`), identical in both `interpret` and `compile` modes.

### 4.2 Scalar Literals & Contextual Literal Typing (`BIP011-D3`)

| Literal Form | Examples | Synthesized Default Type | Contextual Checking (`check(lit, expected)`) |
|---|---|---|---|
| Unsuffixed integer (`INT`) | `0`, `42`, `-128`, `-2147483648` | `int` (error `Out of int range : <lit>` if outside $[-2^{31}, 2^{31}-1]$ and no wider context) | Checks directly as `byte` if in $[-128, 127]$ (else `Out of byte range : <lit>`), `short` if in $[-32768, 32767]$ (else `Out of short range : <lit>`), `long` if in $[-2^{63}, 2^{63}-1]$ (even when outside `int` range, e.g. `def x: long = 3000000000`), `float`, or `double` (and their `?` forms). |
| Long integer (`LONG`) | `0L`, `42l`, `-9223372036854775808L` | `long` (error `Out of long range : <lit>` if outside $[-2^{63}, 2^{63}-1]$) | Widens to `float`, `double`, `long?`, `float?`, `double?`; never narrows implicitly to `int`/`short`/`byte`. |
| Float (`FLOAT_LIT`) | `1.5f`, `0.0F`, `.5f`, `1e-3f`, `42f` | `float` (error `Out of float range : <lit>` if finite decimal overflows to `Infinity`) | Widens to `double`, `float?`, `double?`. |
| Double (`DOUBLE_LIT`) | `1.5`, `.5`, `1e10`, `1.5d`, `1.5D` | `double` (error `Out of double range : <lit>` on finite overflow) | Unsuffixed decimal literals (`1.5`) also check directly against `float` / `float?` when finite-in-range (`def f: float = 1.5`), whereas explicit `1.5d`/`1.5D` stays `double`. |
| Character (`CHAR`) | `'a'`, `'\n'`, `'\t'`, `'\r'`, `'\b'`, `'\f'`, `'\0'`, `'\''`, `'\\'`, `'\u03BB'` | `char` | Checks as `char` or `char?` (not implicitly numeric; see §4.4). |
| Boolean (`BOOLEAN`) | `true`, `false` | `bool` | Checks as `bool` or `bool?`. |
| String (`STRING`) | `"hello"`, `"a\nb\u0041"` | `string` | Checks as `string`, `string?`, `CharSequence`, `Comparable(string)`, `Serializable`, `any`, `any?`. |

Negated integer/long minimum bounds (`-128` for `byte`, `-32768` for `short`, `-2147483648` for `int`, `-9223372036854775808L` for `long`) fold unary `-` into the literal during parsing/checking just as `-2147483648` already does today (`CLAUDE.md` §Architecture). `STRING` literals gain standard `\t`, `\r`, `\b`, `\f`, `\0`, and `\uXXXX` escapes alongside `CHAR` literals for lexical uniformity.

### 4.3 Built-in Scalar Conversion Functions (`BIP011-D3`)

`Implicits` provides nine pure, deterministic built-in conversion callables—one for each core scalar type—generalizing today's `ConvertFunction` (`int(x)` and `double(x)`):

| Function | Accepted Input Types (`x`) | Semantics & Failure Modes |
|---|---|---|
| `byte(x)` | Any numeric (`byte`..`double`), `char`, `string` | Truncates `float`/`double` toward zero; rejects `NaN`/`Infinity` or out-of-$[-128, 127]$ values (and `char` code units $> 127$) with `Out of byte range : <x>`; parses trimmed decimal `string` via `Byte.parseByte` (`Not a number : <x>` on malformed syntax, `Out of byte range : <x>` if valid integer outside `byte`). |
| `short(x)` | Any numeric, `char`, `string` | Same rules for $[-32768, 32767]$ (`Out of short range : <x>`, `Not a number : <x>`). |
| `int(x)` | Any numeric, `char`, `string` | Same rules for $[-2^{31}, 2^{31}-1]$; `int('A') == 65` (UTF-16 code unit widening, never fails). Note: converting `long` outside `int` range throws `Out of int range : <x>`. |
| `long(x)` | Any numeric, `char`, `string` | Truncates `float`/`double` toward zero (rejecting `NaN`, `Infinity`, or values outside $[-2^{63}, 2^{63}-1]$ with `Out of long range : <x>`); `long('A') == 65L`; parses `string` via `Long.parseLong`. |
| `float(x)` | Any numeric, `string` | Converts numeric via IEEE 754 `floatValue()`; parses `string` via `Float.parseFloat` (`Not a number : <x>` on invalid syntax). |
| `double(x)` | Any numeric, `string` | Converts numeric via `doubleValue()`; parses `string` via `Double.parseDouble` (`Not a number : <x>` on invalid syntax). |
| `bool(x)` | `bool`, `string` | Identity on `bool`; parses `"true"` $\to$ `true` and `"false"` $\to$ `false` (case-sensitive; any other string throws `Not a boolean : <x>`). |
| `char(x)` | `char`, integral (`byte`, `short`, `int`, `long`), `string` | Identity on `char`; converts integral code unit in $[0, 65535]$ to `char` (else `Out of char range : <x>`); extracts the single character of a length-1 `string` (else `Expected single-character string : <x>`). |
| `string(x)` | `any?` | Formats any Babble or Java value using `Values.toString(x)` (`"null"` on `null`, decimal without trailing `.0` drift, `value class` structural string, `Object.toString()` with non-null fallback), always returning non-null `string`. |

Passing a nullable union `T?` to `byte(x)`..`char(x)` or `bool(x)` is a static `Type error : expected ..., got ...` (the caller narrows first or uses `x?.let` / `x!`), whereas `string(x)` accepts `any?` directly.

### 4.4 Arithmetic Promotion, Widening Lattice & Non-Numeric `char` (`BIP011-D4`)

1. **Implicit Numeric Widening Chain (Extending `BIP008-D7`).**
   Through `BIP010` §4.2's single `subsumes(site, actual, expected)` judgement, non-nullable numeric types widen implicitly along the strict total order:
   $$\texttt{byte} \prec \texttt{short} \prec \texttt{int} \prec \texttt{long} \prec \texttt{float} \prec \texttt{double}$$
   (and $T \prec U?$ whenever $T \preceq U$). Reassignment and branch joins (`TypeAlgebra.join`, `BIP010` §4.1) compute the least upper bound along the same finite height-6 chain (`if c then 1 else 2L` has type `long`; `if c then 1L else 1.5f` has type `float`), recording the exact source coercion (`NumericCoercion`: `I2L`, `I2F`, `I2D`, `L2F`, `L2D`, `F2D`) on the coerced AST node in `ScopeAnalysis`.
   * *Note on precision:* Following JLS §5.1.2, `long` $\to$ `float` and `int`/`long` $\to$ `double` are valid implicit widenings. Narrowing conversions (`int` $\to$ `short`, `long` $\to$ `int`, `double` $\to$ `float`) are **never** implicit (except range-checked literals in §4.2) and require explicit `short(x)`, `int(x)`, `float(x)` conversion calls.
2. **Binary Arithmetic (`+`, `-`, `*`) — JLS §5.6.2 Binary Numeric Promotion.**
   For numeric operands $A, B \in \{\texttt{byte}, \texttt{short}, \texttt{int}, \texttt{long}, \texttt{float}, \texttt{double}\}$:
   * If either operand is `double` $\implies$ both convert to `double`, result is `double` (`DADD`/`DSUB`/`DMUL`).
   * Else if either operand is `float` $\implies$ both convert to `float`, result is `float` (`FADD`/`FSUB`/`FMUL`).
   * Else if either operand is `long` $\implies$ both convert to `long`, result is `long` (`LADD`/`LSUB`/`LMUL`, fixing today's `Operators` bug where `Long + Long` returned `Double`).
   * Else (`byte`, `short`, `int`) $\implies$ both promote to `int`, result is `int` (`IADD`/`ISUB`/`IMUL`). Promoting `byte + byte` and `short + short` to `int` matches JVM hardware instructions directly and avoids silent 8-bit/16-bit overflow surprises.
3. **Floating-Point Division (`/`).**
   Babble preserves its clean division rule (`1 / 2 == 0.5`): `/` always performs floating-point division rather than truncating integer division.
   * When neither operand is `double` or `long` and at least one operand is `float` (`float / float`, `float / int`, `int / float`, `short / float`, `byte / float`), both operands promote to `float` and `/` yields `float` (`FDIV`), allowing 32-bit `float` graphics/numeric kernels to stay in single precision without `double` promotion.
   * For all other numeric pairs (`int / int`, `long / long`, `long / float`, `double / ...`), both operands convert to `double` and `/` yields `double` (`DDIV`).
4. **Unary Negation (`-x`) & Compound Assignment (`+=`, `-=`, `*=`, `/=`).**
   * Unary `-x` yields `double` on `double`, `float` on `float`, `long` on `long`, and `int` on `byte`/`short`/`int`.
   * Compound assignment `x op= y` continues to mean `x = x op y` (`CLAUDE.md` §Object model) **without Java's hidden narrowing cast**: `def i: long = 1 ; i += 2` succeeds (`long + int` $\to$ `long`), whereas `def b: byte = 1 ; b += 2` fails statically (`Type error : expected byte, got int`) unless written `b = byte(b + 2)`, preventing silent wraparound truncation.
5. **Relational & Equality Operators (`<`, `<=`, `==`, `!=`, `<>`, `>=`, `>`).**
   * Numeric types compare after binary numeric promotion (`Float.compare` / `Double.compare` parity for signed zero and `NaN`, `LCMP` for `long`, `IF_ICMP*` for `byte`/`short`/`int`).
   * Equality `==` / `!=` between disjoint scalar families (e.g. `int` vs `string`, `char` vs `int`) remains rejected statically when disjoint or evaluates consistently with `Values.equals` across unions (`1 == 1L` and `1 == 1.0` both evaluate to `true`, consistent with today's `1 == 1.0` numeric value equality in `Values.equals` and `TypeChecker`).
6. **`char` Is Strictly Non-Numeric.**
   Unlike Java—where `'a' + 1` silently produces the `int` `98` and `new StringBuilder('a')` famously calls the `int capacity` constructor (`StringBuilder(97)`) instead of appending `'a'`—Babble treats `char` as a **character scalar, not a number**:
   * `char` does **not** widen to `int`/`long`/`float`/`double`, so `StringBuilder('a')` reports a clean static overload error instead of allocating a 97-character empty buffer!
   * `+`, `-`, `*`, `/` reject `char` (`Not a number : char`).
   * `<`, `<=`, `==`, `!=`, `>=`, `>` compare `char` against `char` by UTF-16 code unit order (`'a' < 'z'`).
   * Explicit conversions `int(c)` and `char(n)` make code-unit arithmetic unambiguous (`char(int('a') + 1) == 'b'`).

### 4.5 `string` Transparency & Memberless Primitives (`BIP011-D5`)

* **`string` == `@NotNull java.lang.String`.** Removing the `!(v instanceof String)` exclusion in `Members.isJavaObject` and the `Type.STRING` short-circuit in `TypeChecker.member` exposes all public instance methods of `java.lang.String` directly on `string` values (`"hello".length()`, `"hello".charAt(0)`, `"  hi  ".trim()`, `"a,b".split(",")`, `s?.startsWith("pre")`), dispatched via `RM001`'s direct `INVOKEVIRTUAL java/lang/String.*` path in compiled mode with zero boxing or `JavaObject` wrapping. `string` also subtypes `java.lang.CharSequence`, `java.lang.Comparable(string)`, and `java.io.Serializable` directly.
* **All 8 primitives remain memberless.** Selecting a member on `byte`, `short`, `int`, `long`, `float`, `double`, `bool`, or `char` (`42.toString()`, `(1L).hashCode()`) continues to fail statically and at runtime with `Not a scope : <type>`. Static utility methods on wrapper classes (`java.lang.Integer.bitCount(x)`, `java.lang.Character.isAlphabetic(int(c))`, `java.lang.Math.fma(a, b, c)`) remain directly callable.

---

## 5. Core Collection & Entry Types

### 5.1 Roster, Interfaces & Deterministic Default Implementations (`BIP011-D1`)

Babble defines **five core collection types** and **one immutable map-entry type**:

| Babble Type | Underlying JVM Interface | Default Constructor Instantiation | Rationale for Default Concrete Class |
|---|---|---|---|
| `list(T)` | `java.util.List<T>` | `new java.util.ArrayList<T>(...)` | Cache-friendly contiguous array backing; $O(1)$ indexed `get`/`set` and amortized $O(1)$ `add`. |
| `set(T)` | `java.util.Set<T>` | `new java.util.LinkedHashSet<T>(...)` | $O(1)$ membership with **deterministic insertion-order iteration** (preventing hash-seed iteration nondeterminism and satisfying `BIP007` `deterministic` contracts). |
| `sortedset(T)` | `java.util.SortedSet<T>` | `new java.util.TreeSet<T>(...)` | Red-black tree maintaining $O(\log N)$ sorted element order (`Comparable` or explicit `Comparator`). |
| `map(K, V)` | `java.util.Map<K, V>` | `new java.util.LinkedHashMap<K, V>(...)` | $O(1)$ lookup/update with **deterministic insertion-order iteration** over `keySet()`/`values()`/`entrySet()`. |
| `sortedmap(K, V)` | `java.util.SortedMap<K, V>` | `new java.util.TreeMap<K, V>(...)` | Red-black tree maintaining $O(\log N)$ sorted key order (`Comparable` or explicit `Comparator`). |
| `entry(K, V)` | `java.util.Map.Entry<K, V>` | `new java.util.AbstractMap.SimpleImmutableEntry<K, V>(k, v)` | Immutable key-value pair implementing `Map.Entry.equals`/`hashCode` and **accepting nullable `K?` and `V?`** (unlike `java.util.Map.entry(k, v)`, which throws `NullPointerException` on `null` keys or values). |

**Transparent Java type equivalence and subtyping:**

* In Babble's type system, `list(T)` **is** `Type.JavaInstance(java.util.List.class, List.of(T))` (printed canonically as `list(T)`). Writing `java.util.List(int)` in a Babble type annotation produces the exact same type `list(int)`.
* Standard JDK inheritance holds automatically via `JavaTypeBridge`:
  * `ArrayList(T) <: list(T) <: java.util.Collection(T) <: java.lang.Iterable(T)`
  * `LinkedList(T) <: list(T)` and `LinkedList(T) <: java.util.Deque(T)`
  * `sortedset(T) <: set(T) <: java.util.Collection(T) <: java.lang.Iterable(T)`
  * `TreeSet(T) <: java.util.NavigableSet(T) <: sortedset(T)`
  * `sortedmap(K, V) <: map(K, V)`
  * `ConcurrentHashMap(K, V) <: java.util.concurrent.ConcurrentMap(K, V) <: map(K, V)`
* Consequently, callers choose freely between the ergonomic default constructor and any specialized JDK or Babble implementation (with zero `Proxy` allocation in compiled mode thanks to `RM003-P3` direct `implements` bridges):
  ```babble
  def xs: list(int) = list(1, 2, 3)                                       ;; java.util.ArrayList
  def ys: list(int) = java.util.LinkedList(int)()                         ;; java.util.LinkedList
  def fastSet: set(string) = java.util.HashSet(string)()                  ;; opt into unordered HashSet
  def cache: map(string, int) = java.util.concurrent.ConcurrentHashMap()  ;; inferred K=string, V=int
  ```

### 5.2 Universal Call-Site Type Application `C(T)(...)` and Inferred `C(...)` (`BIP011-D9`)

Both explicit call-site type arguments and bidirectional type inference are supported **uniformly** across core collection constructors, Java generic constructors, Babble generic classes, and Babble generic functions:

```babble
;; 1. Explicit call-site type arguments C(T)(...)
def a = list(int)()                            ;; empty list(int)
def b = list(int?)(1, null, 3)                 ;; list(int?)
def c = map(string, list(int))()               ;; map(string, list(int))
def d = java.util.ArrayDeque(string)()         ;; Java generic constructor with explicit T
def e = Box(int | string)("hi")                ;; Babble generic class with explicit T
def f = id(double)(1)                          ;; Babble generic function with explicit T (widens 1 -> 1.0)

;; 2. Omitted type arguments C(...) — inferred from expected type or value arguments
def g: list(int) = list()                      ;; T = int from LHS annotation
def h = list(1, 2, 3)                          ;; T = int from variadic arguments
def i = list(1, 2.5)                           ;; T = double (numeric join 1 -> 1.0)
def j = set("a", null)                         ;; T = string? (nullable join)
def k = map(entry("x", 1), entry("y", 2))      ;; K = string, V = int
```

* **Static resolution in `CallSolver` (`BIP010` §4.3):**
  * When explicit `typeArguments` are supplied (`C(T1, T2)(args...)`), `CallSolver` checks arity (`Wrong number of type arguments : expected 1, got 2`), verifies any Java type-parameter upper bounds against `[T1, T2]`, substitutes $\sigma = [P_i \mapsto T_i]$ up front, and checks `args...` directly against $\sigma(\text{params})$.
  * When `typeArguments` are omitted (`C(args...)`), `CallSolver` solves `typeParams` from `args...` and any contextual expected type (`check` mode).
  * If a type parameter remains unconstrained after Phase 2 (e.g. `def xs = list()` with no annotation and no element arguments—note that unlike local `def x = null`, an unannotated `def xs = list()` creates a concrete heap object whose element invariant must be fixed at construction), `TypeChecker` reports `Missing type argument : T` at the constructor call.

### 5.3 Collection Constructor & Factory Signatures (`BIP011-D10`)

Core collection constructors are first-class runtime and static factories supporting four clean invocation shapes—empty, variadic initial elements/entries, named copy/sizing options, and comparators:

| Core Factory | Supported Call Forms | Runtime Lowering (JDK 21 `java.base`) |
|---|---|---|
| **`list(T)`** | `list()`<br>`list(e1, e2, ...)` *(1+ positional `T`)*<br>`list(capacity: int)`<br>`list(from: Iterable(T))` | `new ArrayList<>()`<br>`ArrayList` pre-sized to `args.length` + `Collections.addAll`<br>`new ArrayList<>(capacity)` *(rejects `< 0` with `IllegalArgumentException`)*<br>`ArrayList` populated from `Iterable` (`Collection` fast-path `new ArrayList<>(col)`) |
| **`set(T)`** | `set()`<br>`set(e1, e2, ...)` *(1+ positional `T`)*<br>`set(capacity: int)`<br>`set(from: Iterable(T))` | `new LinkedHashSet<>()`<br>`LinkedHashSet.newLinkedHashSet(args.length)` + elements<br>`LinkedHashSet.newLinkedHashSet(capacity)` *(exact expected element count in JDK 21!)*<br>`LinkedHashSet` populated from `Iterable` |
| **`sortedset(T)`** | `sortedset()`<br>`sortedset(e1, e2, ...)` *(1+ positional `T`)*<br>`sortedset(comparator: Comparator(T))`<br>`sortedset(from: Iterable(T), comparator: Comparator(T)? = null)` | `new TreeSet<>()` *(requires `T` naturally comparable; see below)*<br>`new TreeSet<>()` + elements<br>`new TreeSet<>(comparator)` *(Babble `(a: T, b: T): int` lambda converts via `RM003-P4` `LambdaMetafactory` SAM!)*<br>`TreeSet` with optional `comparator` populated from `Iterable` |
| **`map(K, V)`** | `map()`<br>`map(entry(k1, v1), ...)` *(1+ positional `entry(K, V)`)*<br>`map(capacity: int)`<br>`map(from: map(K, V))` | `new LinkedHashMap<>()`<br>`LinkedHashMap.newLinkedHashMap(args.length)` + `put(e.getKey(), e.getValue())`<br>`LinkedHashMap.newLinkedHashMap(capacity)`<br>`new LinkedHashMap<>(from)` |
| **`sortedmap(K, V)`** | `sortedmap()`<br>`sortedmap(entry(k1, v1), ...)` *(1+ positional `entry(K, V)`)*<br>`sortedmap(comparator: Comparator(K))`<br>`sortedmap(from: map(K, V), comparator: Comparator(K)? = null)` | `new TreeMap<>()` *(requires `K` naturally comparable; see below)*<br>`new TreeMap<>()` + `put`<br>`new TreeMap<>(comparator)` *(accepts Babble `(a: K, b: K): int` lambda via `RM003-P4` `LambdaMetafactory` SAM!)*<br>`TreeMap` with optional `comparator` populated from `from` |
| **`entry(K, V)`** | `entry(key: K, value: V)` | `new AbstractMap.SimpleImmutableEntry<>(key, value)` |

**Static natural-ordering check on `sortedset` and `sortedmap`:**
When `comparator:` is omitted (or statically `null`), `TypeChecker` verifies at compile time that the element type `T` (for `sortedset`) or key type `K` (for `sortedmap`) is **non-nullable** and **naturally comparable**:
* Satisfied by all 8 non-nullable core scalar primitives (`byte`..`char`, since their boxed wrappers implement `Comparable`), `string`, any Java class/interface implementing `java.lang.Comparable`, and any Babble class/value class implementing `java.lang.Comparable` (which `RM003-P3` compiles to a direct `implements java.lang.Comparable` JVM bridge so `TreeSet`/`TreeMap` invoke `compareTo` with zero `Proxy` wrapper).
* Rejected statically (`Type error : T is not Comparable (pass comparator: ...)`): nullable element/key types `int?` or `string?` (because `TreeSet` / `TreeMap` natural ordering throws `NullPointerException` on `null` keys!) and unordered classes. When sorting nullable keys is desired, passing `comparator: (a: int?, b: int?): int -> ...` (or `java.util.Comparator.nullsFirst(...)`) succeeds cleanly. Notice that **nullable values `V?` in `sortedmap(string, int?)` are always allowed**, since `TreeMap` orders only keys `K` and freely stores `null` values.

### 5.4 Collection Variance : Invariance on Mutable Types + `borrow` Read-Only Covariance (`BIP011-D8`)

To close the `BIP008-P6b` heap-pollution soundness hole (§2.1) without sacrificing read-only helper ergonomics or introducing Kotlin-style duplicate collection interfaces:

1. **Invariant Mutable Parameterized Types.**
   Core collections (`list(T)`, `set(T)`, `sortedset(T)`, `map(K, V)`, `sortedmap(K, V)`), mutable Java generic classes/interfaces (`ArrayList(T)`, `Set(T)`, `Optional` excluded if immutable—see below), and non-`value` Babble generic classes (`class (def x: T) (...)`) are **invariant** in their type arguments during ordinary subtyping and assignment:
   ```babble
   def xs: list(int) = list(1, 2)
   def ys: list(int?) = xs          ;; Compile error — Type error : expected list(int?), got list(int)
   def zs: list(any) = xs           ;; Compile error — Type error : expected list(any), got list(int)
   ```
2. **Covariant Immutable Parameterized Types.**
   Type arguments remain **covariant** (without representation-changing numeric coercions; i.e. `int <: int?` and `int <: any`, *not* `int <: double` inside a container since `List<Integer>` cannot contain `Double` bits without copying) on provably immutable type constructors:
   * Babble `value class` generics (`value class (x: T) (...)`: `Pair(int, string) <: Pair(any, any?)`).
   * Immutable `entry(K, V)` (`entry(string, int) <: entry(any, int?)`; `Map.Entry.setValue` is omitted/deprecated-unsupported on `entry` in the mapped signature table, §6.4).
   * Known immutable `java.base` value wrappers (`java.util.Optional(T)`).
   * Explicit Java use-site wildcards returned/consumed by Java APIs (`? extends T` covariant, `? super T` contravariant, already supported in `JavaTypeBridge`).
3. **Read-Only `borrow` Parameter Covariance (`BIP007` Integration).**
   When a Babble callable parameter is annotated **`borrow` without `mut`** (`(borrow xs: list(any?)): int -> ( xs.size() )`), `RM002`'s `EffectChecker` already statically guarantees two strong properties (`BIP007` §5):
   * `xs` is **never mutated** (`mut` methods like `add`, `set`, `remove`, `clear` are forbidden on `xs` and any sub-reference derived from `xs`), and
   * `xs` **never escapes** into a field, return value, or heap closure outliving the call.
   Therefore, at call sites targeting a non-`mut` `borrow` parameter of collection/generic interface type `C(U1, ...)`, `TypeAlgebra.relate` safely relaxes invariance to **representation-preserving covariance** (`T_i <:_{\text{rep}} U_i$, i.e. reference/union/nullability subtyping without `int` $\to$ `double` representation change):
   ```babble
   def countNonNull = (borrow xs: list(any?)): int -> (
       def n = 0
       def i = 0
       while i < xs.size() then (
           if xs.get(i) != null then ( n += 1 )
           i += 1
       )
       n
   )

   def nums = list(10, 20, 30)      ;; list(int)
   countNonNull(nums)               ;; OK! borrow non-mut parameter accepts list(int) as list(any?)
   ```
   * Note why this is strictly sounder than Kotlin's `List<out T>`: in Kotlin, `val m: MutableList<Int> = mutableListOf(1); val r: List<Any?> = m` lets `r` escape into a long-lived field while `m` continues mutating it; in Babble, `borrow` is scoped to the call frame and pairs with `RM002`'s static `mut` exclusivity! (Similarly, `from: Iterable(T)` on `list(from: ...)` / `set(from: ...)` uses `? extends T` / `borrow` covariance so `list(int?)(from: nums)` copies `list(int)` into a fresh mutable `list(int?)` cleanly.)

---

## 6. Aligning Babble Nullability with Java's Soft Type System

### 6.1 The Conceptual Model : Babble Source Annotations vs Java Bytecode

The heart of `BIP011` is a crystal-clear distinction between **what a type name means when written in Babble** versus **what an unannotated descriptor means when read from Java bytecode**:

1. **In Babble source annotations, every type without `?` is strictly `@NotNull`.**
   Babble has one universal rule across core types, Babble classes, and Java classes: `null` is *never* a member of `T` unless `?` (or `| null`) is written (`BIP008` §11).
   * `int` and `java.lang.Integer` both mean **non-null 32-bit integer** (conceptually `@NotNull java.lang.Integer` when boxed, or JVM `int` when unboxed).
   * `int?` and `java.lang.Integer?` both mean **nullable 32-bit integer** (`@Nullable java.lang.Integer`).
   * `string` and `java.lang.String` both mean `@NotNull java.lang.String`; `string?` and `java.lang.String?` mean `@Nullable java.lang.String`.
   * `list(int)` and `java.util.List(java.lang.Integer)` both mean `@NotNull java.util.List<@NotNull java.lang.Integer>`.
   * `java.io.File` means `@NotNull java.io.File`; `java.io.File?` means `@Nullable java.io.File`.
2. **In Java reflection (`java.lang.reflect`), unannotated references are nullable (`T?`), and `@NotNull` is soft.**
   When `JavaTypeBridge` (`BIP010` §4.6) inspects a compiled Java classfile via reflection:
   * Only JVM **primitive descriptors** (`int.class`, `long.class`, `boolean.class`, ...) are enforced non-null by the JVM verifier.
   * Unannotated **reference descriptors** (`Integer.class`, `String.class`, `Object.class`, `File.class`, `List<String>`, unannotated type variables `T`) may legitimately carry `null` at runtime (`Map.get`, `BufferedReader.readLine`, `File.getParentFile`, `Integer.getInteger`).
   * Even when a Java method or type argument carries `@NotNull` / `@NonNull`, **that annotation is soft on the Java side**: the JVM bytecode verifier does not check it, so buggy or uninstrumented Java code can still return `null`.

### 6.2 Reflection Translation Rules (`JavaTypeBridge`, `BIP011-D6`)

When translating a Java reflective type (`AnnotatedType` + erased `Class<?>` / generic `java.lang.reflect.Type`) that is *not* overridden by the built-in JDK Nullability Overlay (§6.4):

| Java Reflective Descriptor | Nullability Context | Resulting Babble `Type` |
|---|---|---|
| Primitive `byte`, `short`, `int`, `long`, `float`, `double`, `boolean`, `char` | Hardware non-null | `byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char` |
| `void` return | Method return | `null` (`Type.NULL`, matching existing Babble `void` return value `null`) |
| Wrapper `java.lang.Integer` (and `Byte`..`Character`) | Unannotated or `@Nullable` | `int?` (`byte?`..`char?`) — **replaces today's unsound `Type.INT` mapping!** |
| Wrapper `java.lang.Integer` (and `Byte`..`Character`) | `@NotNull` / `@NullMarked` (without `@Nullable`) | `int` (`byte`..`char`) + **defensive boundary check (§6.6)** |
| `java.lang.String` | Unannotated or `@Nullable` | `string?` |
| `java.lang.String` | `@NotNull` / `@NullMarked` | `string` + **defensive boundary check (§6.6)** |
| `java.lang.Object` | Unannotated or `@Nullable` | `any?` |
| `java.lang.Object` | `@NotNull` / `@NullMarked` | `any` + **defensive boundary check (§6.6)** |
| Reference `C` / `List<E>` / `T[]` | Unannotated or `@Nullable` | `C?` / `list(E')?` / `JavaArray(E')?` (where `E'` recursively translates `E`, so unannotated `List<String>` $\to$ `list(string?)?` and `int[]` $\to$ `JavaArray(int)?`) |
| Reference `C` / `List<E>` / `T[]` | `@NotNull` / `@NullMarked` | `C` / `list(E')` / `JavaArray(E')` + **defensive boundary check (§6.6)** |
| Java constructor `new C(...)` | JVM `NEW` instruction | Always non-null `C` (zero check needed) |
| `static final` constant field on Java class | Compile/load-time inspectable | Non-null `T` when its live constant value is non-null (`Math.PI`, `File.separator: string`, `Boolean.TRUE: bool`), else `null` |
| Java `TypeVariable` `T` | Substituted with Babble type arg `A` (`@Nullable T` $\to$ `A?`, `@NotNull T` $\to$ `withoutNull(A)`, parametric `T` $\to$ `A` in `@NullMarked`/mapped APIs, unannotated legacy `T` return $\to$ `A?`) | Preserves parametric nullness where declared, conservative `A?` on unannotated legacy generics |

**Call-site parameter convenience on unannotated Java methods:**
Because an unannotated Java parameter `void foo(String s)` translates to `s: string?`, **both non-null `string` and nullable `string?` arguments are accepted** (`string <: string?`)! Where a Java method parameter is explicitly annotated `@NotNull String s` (or marked non-null in the JDK overlay, e.g. `String.startsWith(prefix: string)`), `TypeChecker` rejects `string?` at compile time (`Type error : expected string, got string?`), catching `NullPointerException`s before execution!

### 6.3 Recognized Bytecode Nullability Annotations (`BIP011-D7`)

`JavaTypeBridge` inspects both Java 8+ `TYPE_USE` annotations (`Executable.getAnnotatedReturnType()`, `getAnnotatedParameterTypes()`, `Field.getAnnotatedType()`, `AnnotatedParameterizedType.getAnnotatedActualTypeArguments()`) and declaration annotations (`Method`, `Parameter`, `Field`, plus enclosing `Class` / `Package` `@NullMarked`), matching annotation type names by string (so Babble requires **zero third-party annotation jars** on its classpath, preserving `BIP006`'s `java.base`-only boundary):

* **Non-null annotations:**
  * `org.babblelang.effects.NotNull` *(optional convenience in `org.babblelang.effects`)*
  * `org.jspecify.annotations.NonNull` (plus scope-default `org.jspecify.annotations.NullMarked` when not overridden by `NullUnmarked` or `@Nullable`)
  * `org.jetbrains.annotations.NotNull`
  * `jakarta.annotation.Nonnull`
  * `javax.annotation.Nonnull` *(when `when()` is absent or `When.ALWAYS`)*
  * `lombok.NonNull`, `android.annotation.NonNull`, `androidx.annotation.NonNull`, `edu.umd.cs.findbugs.annotations.NonNull`, `io.micronaut.core.annotation.NonNull`, `reactor.util.annotation.NonNull`
* **Nullable annotations:**
  * `org.jspecify.annotations.Nullable`
  * `org.jetbrains.annotations.Nullable`
  * `jakarta.annotation.Nullable`, `javax.annotation.Nullable`, `javax.annotation.CheckForNull`
  * `android.annotation.Nullable`, `androidx.annotation.Nullable`, `edu.umd.cs.findbugs.annotations.Nullable`, `io.micronaut.core.annotation.Nullable`, `reactor.util.annotation.Nullable`

### 6.4 Built-In Parametric-Nullness Overlay for Core & `java.base` APIs (`BIP011-D7`)

Because JDK 21's `java.base` classfiles do not yet ship JSpecify annotations, `JavaTypeBridge` contains a compact, declarative **JDK Nullability Overlay** giving exact parametric-nullness signatures to Babble's core types and everyday `java.base` classes:

#### Core Collections & Iteration (`java.util`, `java.lang`)

| Owner | Exact Babble Signature in Overlay | Notes on Parametric Nullness |
|---|---|---|
| `Iterable(T)` | `iterator(): Iterator(T)` | Non-null iterator yielding `T` (`int` for `list(int)`, `int?` for `list(int?)`). |
| `Iterator(T)` | `hasNext(): bool`, `next(): T`, `remove(): null` | `next()` has parametric return `T` (checked at boundary if `T` is non-null). |
| `Collection(T)` | `size(): int`, `isEmpty(): bool`, `contains(o: any?): bool`, `add(e: T): bool`, `remove(o: any?): bool`, `addAll(c: Collection(? extends T)): bool`, `removeAll(c: Collection(any?)): bool`, `retainAll(c: Collection(any?)): bool`, `clear(): null`, `toArray(): JavaArray(any?)` | `add(e: T)` rejects `null` statically on `list(int)` (`expected int, got null`), accepts `null` on `list(int?)`! |
| `list(T)` (`List`) | `get(index: int): T`, `getFirst(): T`, `getLast(): T`, `set(index: int, element: T): T`, `add(index: int, element: T): null`, `addFirst(e: T): null`, `addLast(e: T): null`, `remove(index: int): T`, `removeFirst(): T`, `removeLast(): T`, `indexOf(o: any?): int`, `lastIndexOf(o: any?): int`, `subList(from: int, to: int): list(T)`, `reversed(): list(T)`<br>*Static:* `List.of(e: T...): list(T)`, `List.copyOf(c: Collection(? extends T)): list(T)` *(with `T <: any` non-null!)* | `xs.get(0)` returns **`int`** on `list(int)` (unboxed directly under `RM001`!) and **`int?`** on `list(int?)`. `List.of(null)` is rejected at compile time because `List.of` forbids `null` elements! |
| `set(T)` (`Set`) | Inherits `Collection(T)`<br>*Static:* `Set.of(e: T...): set(T)`, `Set.copyOf(...): set(T)` *(with `T <: any` non-null!)* | |
| `sortedset(T)` (`SortedSet` / `NavigableSet`) | `first(): T`, `last(): T`, `headSet(toElement: T): sortedset(T)`, `tailSet(fromElement: T): sortedset(T)`, `subSet(fromElement: T, toElement: T): sortedset(T)`, `comparator(): Comparator(? super T)?`, `reversed(): sortedset(T)` *(plus `floor`/`ceiling`/`lower`/`higher(e: T): T?` on `NavigableSet`)* | `first()`/`last()` return `T` (throw `NoSuchElementException` if empty); `NavigableSet.floor` etc. return `T?`. |
| `map(K, V)` (`Map`) | `size(): int`, `isEmpty(): bool`, `containsKey(key: any?): bool`, `containsValue(value: any?): bool`, **`get(key: any?): V?`**, **`getOrDefault(key: any?, defaultValue: V): V`**, **`put(key: K, value: V): V?`**, **`putIfAbsent(key: K, value: V): V?`**, **`remove(key: any?): V?`**, `remove(key: any?, value: any?): bool`, `replace(key: K, value: V): V?`, `putAll(m: map(? extends K, ? extends V)): null`, `clear(): null`, `keySet(): set(K)`, `values(): Collection(V)`, `entrySet(): set(entry(K, V))`<br>*Static:* `Map.of(...): map(K, V)`, `Map.entry(k: K, v: V): entry(K, V)` *(with `K <: any`, `V <: any` non-null!)* | **`m.get(k)` always returns `V?`** (even on `map(string, int)` $\to$ `int?`), because a missing key returns `null`! Conversely, **`m.getOrDefault(k, 0)` returns `V` (`int`)** when `V` is non-null `int`! |
| `sortedmap(K, V)` (`SortedMap` / `NavigableMap`) | `firstKey(): K`, `lastKey(): K`, `headMap(toKey: K): sortedmap(K, V)`, `tailMap(fromKey: K): sortedmap(K, V)`, `subMap(fromKey: K, toKey: K): sortedmap(K, V)`, `comparator(): Comparator(? super K)?`, `reversed(): sortedmap(K, V)` *(plus `floorEntry`/`ceilingEntry` $\to$ `entry(K, V)?` on `NavigableMap`)* | |
| `entry(K, V)` (`Map.Entry`) | `getKey(): K`, `getValue(): V` | Parametric `K` and `V`. |

#### Core `java.lang`, `java.util`, and `java.io`/`java.nio` Essentials

* **`java.lang.Object`:** `toString(): string`, `equals(other: any?): bool`, `hashCode(): int`, `getClass(): Class(...)`.
* **`string` (`java.lang.String`):** `length(): int`, `isEmpty(): bool`, `isBlank(): bool`, `charAt(index: int): char`, `codePointAt(index: int): int`, `substring(...) : string`, `subSequence(...) : CharSequence`, `trim(): string`, `strip(): string`, `stripLeading(): string`, `stripTrailing(): string`, `stripIndent(): string`, `toLowerCase(...): string`, `toUpperCase(...): string`, `replace(...): string`, `replaceAll(regex: string, repl: string): string`, `replaceFirst(regex: string, repl: string): string`, `concat(str: string): string`, `repeat(count: int): string`, `intern(): string`, `formatted(args: any?...): string`, `startsWith(...): bool`, `endsWith(suffix: string): bool`, `contains(s: CharSequence): bool`, `matches(regex: string): bool`, `indexOf(...): int`, `lastIndexOf(...): int`, `compareTo(another: string): int`, `compareToIgnoreCase(str: string): int`, `equalsIgnoreCase(another: string?): bool`, `split(...): JavaArray(string)`, `toCharArray(): JavaArray(char)`, `getBytes(...): JavaArray(byte)`, `lines(): Stream(string)`; static `String.valueOf(...): string`, `String.format(...): string`, `String.join(...): string`.
* **`java.lang.StringBuilder`:** constructors `StringBuilder()`, `StringBuilder(capacity: int)`, `StringBuilder(str: string)` $\to$ `StringBuilder`; `append(...) : StringBuilder`, `insert(...) : StringBuilder`, `delete(...) : StringBuilder`, `reverse(): StringBuilder`, `toString(): string`, `length(): int`, `charAt(i: int): char`.
* **Boxed wrappers (`Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `Boolean`, `Character`):** `valueOf(...)` $\to$ non-null primitive (`int`, `long`, ...), `parseInt`/`parseLong`/... `(s: string)` $\to$ primitive, `toString(...)` $\to$ `string`, `compare`/`compareUnsigned` $\to$ `int`; `Integer.getInteger(...)` / `Long.getLong(...)` remain `int?` / `long?` (since missing system properties return `null`!).
* **`java.util.Optional(T)`:** `Optional.of(value: T): Optional(T)` (`T <: any`), `Optional.ofNullable(value: T?): Optional(T)`, `Optional.empty(): Optional(T)`, `isPresent(): bool`, `isEmpty(): bool`, `get(): T`, `orElse(other: T): T`, `orElseNull` / `orElse(null): T?`.
* **`java.lang.System`:** `lineSeparator(): string`, `getenv(name: string): string?`, `getProperty(key: string): string?`, `getProperty(key: string, def: string): string`.

### 6.5 Postfix Non-Null Assertion Operator (`e!`, `BIP011-D7`)

When interacting with an unannotated Java API whose return value the programmer knows is non-null—or after an invariant invisible to flow narrowing (`m.containsKey(k)` followed by `m.get(k)`)—writing `e as string` or `e as list(string)` repeats the entire target type. `BIP011` adds a concise **postfix non-null assertion `e!`**:

```babble
def counts = map(entry("a", 1), entry("b", 2))
def a: int = counts.get("a")!                ;; int? -> int (unboxed!)

def home = java.lang.System.getenv("HOME")!  ;; string? -> string
println(home.toUpperCase())
```

* **Static typing (`synth(e!)`):**
  * Let $T = \text{synth}(e)$.
  * If $T = \texttt{null}$ (`Type.NULL`), emit compile-time error `Type error : expected any, got null` at `e` (asserting `null!` always fails).
  * If $T$ does not admit `null` (`!admitsNull(T)`, e.g. `42!`, `"abc"!`), emit warning `Redundant non-null assertion : <T>` (mirroring `Redundant cast : <T>` in `TypeChecker`) and yield $T$.
  * Otherwise, yield $T_{\neq \text{null}} = \text{subtractType}(T, \texttt{null})$ (`int?` $\to$ `int`, `int | string | null` $\to$ `int | string`, `any?` $\to$ `any`).
* **Runtime semantics & diagnostics:**
  * Evaluates `e` once to value `v`.
  * If `v == null`, throws `BabbleException("Type error : expected " + nonNullType + ", got null")`—using Babble's clean, leak-free `Type error` message format (`BIP009`), never an uncaught JVM `NullPointerException`.
  * Under `RM001`, when $T_{\neq \text{null}}$ is a core primitive (`int?` $\to$ `int`), `CodeGenerator` checks `JavaTypes.checkNotNull(v, "int")` and unboxes directly to `I` on the JVM operand stack (`((Integer) v).intValue()`).
* **Effect semantics (`BIP007`):** `e!` has effect `pure deterministic` (and `nothrow` iff `e` is already statically non-null; on a nullable operand `e!` can throw `BabbleException`, matching `e as T`).

### 6.6 Defensive Runtime Boundary Checks (`JavaTypes.checkNotNull`, `BIP011-D7`)

Because `@NotNull` annotations, the JDK Nullability Overlay (when raw Java code pollutes a `List<Integer>` via unchecked casts), and erased `TypeVariable` returns are **soft** on the JVM side, Babble enforces a **single, airtight invariant at the Java boundary**:

> **No reference returning from Java into a non-nullable Babble type `T` (`!admitsNull(T)`) may proceed past the call or field-read site without being guaranteed non-null.**

Concretely, `LoweringAnalyzer` (`BIP010` §4.6), `Interpreter`/`JavaMethod`/`JavaClass`/`JavaLinker`, and `CodeGenerator` apply four exact cases at Java $\to$ Babble return and field-read sites:

| JVM Return / Field Kind | Static Babble Target Type `T` | Runtime Boundary Check Emitted | Cost |
|---|---|---|---|
| JVM primitive (`B`, `S`, `I`, `J`, `F`, `D`, `Z`, `C`) | Non-null `byte`..`char` (or widened/boxed) | **None** — hardware unboxed slot cannot be `null`. | `0` instructions |
| Java constructor (`NEW C`) | Non-null `C` / `list(T)` / ... | **None** — JVM `NEW` never yields `null`. | `0` instructions |
| JVM reference (`Object`, `Integer`, `String`, `List`, ...) | Nullable `T?` (`int?`, `string?`, `list(int)?`, `any?`) | **None** (unless generic `TypeVariable` needs an erased class check `v == null \|\| v instanceof C`, which permits `null`) — **replaces today's `JavaTypes.checkBoxed` crash on nullable `Integer`/`Double`/`Boolean`!** | `0` instructions |
| JVM reference (`@NotNull Integer`, `@NotNull String`, mapped `list(int).get(i): int`, `Object.toString(): string`, `@NotNull` generic `T`) | Non-null `T` (`int`, `string`, `list(int)`, `File`, `any`, ...) | **`JavaTypes.checkNotNull(v, "<T>")`** (plus `Types.check` class check when `T` comes from an erased `TypeVariable`/`WildcardType`, e.g. `list(int).get(i)` checks `v instanceof Integer`, which simultaneously rejects `null` and raw-cast heap pollution in a **single `instanceof` instruction**!). | 1 predicted branch / `instanceof` (~1 cycle) |

Look how clean that optimization is on generic collections:
* On `xs.get(i)` where `xs: list(int)`, the underlying JVM method is `List.get(int) -> Object` (an erased `TypeVariable` `E` instantiated to non-null `int`). Checking `if (!(v instanceof Integer)) throw JavaTypes.unexpectedType("int", v); return ((Integer) v).intValue();` in `CodeGenerator` handles **both** rogue `null`s (`null instanceof Integer` is `false`!) and raw-Java heap pollution (`((List) xs).add("bad")`) in **one `INSTANCEOF java/lang/Integer` check** before `INVOKEVIRTUAL Integer.intValue()I`!
* On `s.trim()` where `s: string`, the JVM return descriptor is already `Ljava/lang/String;` (verified by the JVM classloader), so only `JavaTypes.checkNotNull(v, "string")` (`IFNONNULL`) runs—no `instanceof` needed!
* **Inbound Java $\to$ Babble & callback return alignment with `RM003-P3` and `RM003-P4`:**
  Now that `RM003` compiles Java interface implementations directly onto generated Babble JVM classes (`CodeGenerator.emitJavaMethodBridge` / `emitLoadJavaBridgeArg` / `emitReturnToJava`) and adapts `0..16`-argument SAMs via cached `LambdaMetafactory` (`Faces.SamInvoker`), `BIP011` aligns all three inbound paths (`emitJavaMethodBridge`, `Faces.SamInvoker`, and `Faces` `Proxy` fallbacks) plus callback return conversion (`JavaTypes.convertResult`):
  1. **Validate against the *Babble* parameter's type, not just erased `Method.getParameterTypes()`:**
     * Today, `CodeGenerator.emitLoadJavaBridgeArg` and `JavaTypes.checkParameter` unconditionally reject `null` on `Integer.class`/`Double.class`/`Boolean.class` (`checkBoxed`), while letting `null` pass unchecked on `String.class` and erased `Object.class` (so passing `null` from raw Java into `Comparator(int)`'s `(Object, Object)I` `$spec` bridge triggers a raw JVM `NullPointerException` at `Integer.intValue()`!).
     * Under `BIP011`, when a Babble method/lambda declares a **nullable** parameter (`x: int?`, `s: string?`) implementing an unannotated Java method (`Integer`, `String`, `Object`), `null` is **accepted** (retiring the hardcoded `checkBoxed` call on `Integer`/`Double`/`Boolean` parameters).
     * Conversely, when the Babble parameter is **non-nullable** (`x: int`, `s: string`, `w: Weight`), `emitLoadJavaBridgeArg` emits the Babble parameter's check (`INSTANCEOF` + unbox before `PrimitiveKind` adaptation, or `Types.check` / `JavaTypes.checkNotNull` on non-null references), and `BabbleFunction` / `CompiledFunction` / `BabbleClass` carry per-parameter `Types` descriptors enforced by `Faces.SamInvoker` and `Proxy` fallbacks (alongside external `BabbleInstance.setMember`).
  2. **Remove `Character` $\to$ numeric widening in `JavaTypes.convertResult` (`JavaTypes.WIDENINGS`):**
     * `JavaTypes.WIDENINGS` currently widens `Character.class` to `Integer`/`Long`/`Float`/`Double` when returning from a Babble SAM or Java-interface bridge (`emitReturnToJava` / `SamInvoker`). To match `BIP011-D4` (`char` is strictly non-numeric), `Character.class` is removed from `JavaTypes.WIDENINGS` so returning a `char` where Java expects `int` fails cleanly with `Type error : ... must return int, got Character`.
* **Inside pure Babble code**, zero null checks or type checks are emitted anywhere (`BIP008`).

---

## 7. Grammar, Lexical Rules, Formatter & Runtime Reflection

### 7.1 Reserved Core Type Keywords & `.` Selector Disambiguation (`BIP011-D11`)

To guarantee that core types can **never** be shadowed or accidentally redefined—eliminating confusing bugs like `def list = ...` breaking `list(int)` annotations downstream in the same scope or REPL session—all **17 core type names** are reserved keywords:

* **Scalar keywords (9):** `byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`
* **Collection & entry keywords (6):** `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry`
* **Top & unit/void type keywords (2):** `any`, `void` *(note: `null` is already keyword `NULL`)*

**Member-access compatibility with standard JDK methods (`BIP011-D11`):**
Standard Java APIs heavily use `set`, `map`, and `list` as method names (`java.util.List.set(index, elem)`, `java.util.stream.Stream.map(fn)`, `java.util.Optional.map(fn)`, `java.io.File.list()`, `java.util.concurrent.atomic.AtomicInteger.set(v)`). Therefore, `Babble.g4` distinguishes **binding identifiers (`ID`)** from **selector member names (`memberName`)**:

```antlr
coreCollectionKeyword
    : LIST | SET | SORTEDSET | MAP | SORTEDMAP | ENTRY
    ;

coreScalarKeyword
    : BYTE | SHORT | INT_KW | LONG_KW | FLOAT_KW | DOUBLE_KW | BOOL_KW | CHAR_KW | STRING_KW
    ;

coreTypeKeyword
    : coreScalarKeyword | coreCollectionKeyword | ANY | VOID
    ;

memberName
    : ID | coreTypeKeyword
    ;
```

* **Forbidden (`ID` required):** `def` names (`def list = 1` $\to$ syntax error), parameter names (`(map: int) -> ...` $\to$ syntax error), type parameter names (`def f(int) = ...` $\to$ syntax error), `package` names, and unqualified variable assignments.
* **Allowed (`memberName` accepted):**
  * Member selection after `.` and `?.`: `xs.set(0, 42)`, `stream.map((x) -> x + 1)`, `opt.map(fn)`, `dir.list()`.
  * Qualified member assignment: `obj.set = ...` (Java fields named after keywords, if any).
  * Named call arguments (`callParameter: (memberName ':')? expression`): preserves compatibility if a Java/host callable exposes a named parameter, while Babble declarations themselves cannot declare parameters with core-type names.
  * `effectTarget` member segments (`axiom hermetic mut java("java.util").List.set`).

### 7.2 Grammar for `C(T)(...)`, Postfix `e!`, and New Literals (`Babble.g4`)

1. **Core factory calls, scalar conversions, and generic type-application calls:**
   * Core collection/entry constructors and scalar conversion calls have dedicated, unambiguous expression alternatives because their head tokens are lexer keywords:
     ```antlr
     | callee=coreCollectionKeyword typeArguments? callParameters   # coreCollectionCall
     | callee=coreScalarKeyword callParameters                      # coreConversionCall
     ```
   * For general Babble and Java generic callables with explicit type arguments (`Box(int?)(null)`, `java.util.LinkedList(int)()`, `mod.Vec(float)(1.0f, 2.0f)`), `Babble.g4` adds a semantic/syntactic lookahead alternative ahead of ordinary `# call`:
     ```antlr
     | expression {isTypeApplicationCall()}? typeArguments callParameters   # typeApplyCall
     | expression callParameters                                            # call
     ```
     where:
     * Neither `typeArguments` nor `callParameters` may be preceded by a newline `NL` (matching Babble's existing call-juxtaposition rule so parenthesized blocks on the next line never attach as calls).
     * `isTypeApplicationCall()` scans the balanced `(...)` immediately ahead and verifies it is syntactically a valid `typeArguments` list directly followed (with zero `NL`) by `(` starting `callParameters`.
     * *Curried value call disambiguation (`f(a)(b)` vs `f(T)(b)`):* If the inner `(...)` contains an unmistakable type construct (`?`, `|`, `:`, `->`, `null`, or a `coreTypeKeyword` like `int` or `list(int)`), it can *only* be `# typeApplyCall`. If the inner `(...)` consists solely of bare `ID` / `ID.ID` names (`f(X)(y)`), `# typeApplyCall` parses it and `TypeChecker` checks `f`'s type: if `f` is a generic class/JavaClass/generic function (`!typeParams.isEmpty()`), `X` resolves in the type namespace as a type argument; if `f` is a non-generic callable returning another callable (curried function `def f = (x: int) -> (y: int) -> x + y`), `TypeChecker` falls back cleanly to evaluating `X` as a value expression! Thus existing curried calls `add(1)(2)` and `add(a)(b)` remain 100% valid.
2. **Postfix non-null assertion (`# nonNullAssertion`):**
   Placed in the tightest postfix group alongside `# selector` and `# call`, with **no `NL*` before `'!'`**:
   ```antlr
   | expression NL* ('.' | safe='?.') memberName                  # selector
   | expression '!'                                               # nonNullAssertion
   | callee=coreCollectionKeyword typeArguments? callParameters   # coreCollectionCall
   | callee=coreScalarKeyword callParameters                      # coreConversionCall
   | expression {isTypeApplicationCall()}? typeArguments callParameters # typeApplyCall
   | expression callParameters                                    # call
   ```
   Because ANTLR4 uses longest-match lexing, `x != y` lexes `!=` as `NEQ`, whereas `x!`, `x!.foo()`, `m.get(k)! + 1`, and `f()!(x)` lex `'!'` (`BANG`) and bind tightly left-to-right (`(m.get(k)!) + 1`, `(x!).foo()`).
3. **New literal tokens:**
   ```antlr
   LONG_LIT:   [0-9]+ [lL];
   INT:        [0-9]+;
   FLOAT_LIT:  ([0-9]* '.' [0-9]+ ([eE] [+-]? [0-9]+)? | [0-9]+ ([eE] [+-]? [0-9]+)) [fF]
             | [0-9]+ [fF];
   DOUBLE_LIT: [0-9]* '.' [0-9]+ ([eE] [+-]? [0-9]+)? [dD]?
             | [0-9]+ [eE] [+-]? [0-9]+ [dD]?
             | [0-9]+ [dD];
   CHAR_LIT:   '\'' ( ~['\\\r\n] | '\\' ( [btnfr"'\\0] | 'u' [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) ) '\'';
   ```
   *(Note: lowercase `[eE]` and signed exponents `1e-5` also generalize today's uppercase-only `('E' [0-9]+)?` in `Babble.g4` line 221!)*

### 7.3 `Formatter` Rules

* **Core collection & generic type-application calls:** No space before `(` in either `(T)` or `(...)`: `list(int)(1, 2, 3)`, `map(string, int)(entry("a", 1))`, `Box(int?)(null)`. Multi-line element lists indent `+1` level inside `(...)`, identical to ordinary function calls.
* **Postfix `e!`:** Attached directly to its operand with no space (`x!`, `m.get("k")!.trim()`).
* **Literals:** Token text (`42L`, `1.5f`, `'a'`) is preserved verbatim, matching `INT` and `STRING`.

### 7.4 `is`, `as`, and Narrowing on Core Types

* **`x is <coreType>`:**
  `isExpression` accepts bare core type keywords on the right-hand side (`x is byte`, `x is short`, `x is int`, `x is long`, `x is float`, `x is double`, `x is bool`, `x is char`, `x is string`, `x is list`, `x is set`, `x is sortedset`, `x is map`, `x is sortedmap`, `x is entry`, `x is any`):
  * At runtime, `x is int` checks `x instanceof Integer` (or unboxed static truth when statically `int`), `x is list` checks `x instanceof java.util.List`, `x is map` checks `x instanceof java.util.Map`, `x is entry` checks `x instanceof java.util.Map.Entry`, and `x is any` checks `x != null`.
  * Parameterized `is` tests (`x is list(int)`) are **rejected at compile time** (`Cannot test erased type arguments with 'is' : use 'is list'`) unless `x`'s static type already fixes the type arguments (e.g. `x: list(int) | string` narrowed by `x is list` refines `x` to **`list(int)`** via `TypeEnv` union elimination, whereas `x: any?` narrowed by `x is list` refines `x` to `list(any?)`!).
* **`x as <type>`:**
  * Supports all scalar types, unions, nullables, and collection types.
  * Consistent with `BIP008-D7`, `as` performs runtime type verification, **not** representation conversion: `(1 as any) as int` succeeds, `(1 as any) as double` fails at runtime (`Type error : expected double, got int`; use `double(x)` to convert numeric representations).
  * Casting `any` or `list(any?)` to an invariant parameterized collection `list(int)` checks `x instanceof java.util.List` at runtime and emits a compile-time warning `Unchecked cast : list(int)` because element types are erased on the JVM (if a polluted element is later read via `xs.get(i)`, §6.6's single `instanceof Integer` boundary check still catches it immediately at read time!).

---

## 8. Compiler Lowering (`RM001` & `RM003` Extension) & `BIP007` Effect Profiles

### 8.1 Full 8-Primitive Unboxed Lowering (`BIP011-D13`)

`BIP011` extends `RM001` and `RM003`'s unboxed primitive pipeline (`ScopeAnalysis.PrimitiveKind`, closure cells, compiled class fields, `$spec` entry points, `bm$<name>` Babble JVM interface bridges, Java-interface `implements` bridges, and `Bootstrap` inline caches) from `{INT, DOUBLE, BOOL}` to **all eight non-nullable JVM primitives**:

| `PrimitiveKind` | JVM Local / Stack Verification Type | JVM Field & `$spec` Descriptor | Local Load / Store / Return | Closure Cell Class (`org.babblelang.runtime`) |
|---|---|---|---|---|
| `BYTE` | `INTEGER` (`1` slot, sign-extended via `I2B` on narrow) | `B` | `ILOAD` / `ISTORE` / `IRETURN` | `ByteCell` (`byte value; boolean set`) |
| `SHORT` | `INTEGER` (`1` slot, sign-extended via `I2S` on narrow) | `S` | `ILOAD` / `ISTORE` / `IRETURN` | `ShortCell` (`short value; boolean set`) |
| `INT` | `INTEGER` (`1` slot) | `I` | `ILOAD` / `ISTORE` / `IRETURN` | `IntCell` *(existing)* |
| `LONG` | `LONG` (`2` slots!) | `J` | `LLOAD` / `LSTORE` / `LRETURN` | `LongCell` (`long value; boolean set`) |
| `FLOAT` | `FLOAT` (`1` slot) | `F` | `FLOAD` / `FSTORE` / `FRETURN` | `FloatCell` (`float value; boolean set`) |
| `DOUBLE` | `DOUBLE` (`2` slots!) | `D` | `DLOAD` / `DSTORE` / `DRETURN` | `DoubleCell` *(existing)* |
| `BOOL` | `INTEGER` (`1` slot, `0`/`1`) | `Z` | `ILOAD` / `ISTORE` / `IRETURN` | `BoolCell` *(existing)* |
| `CHAR` | `INTEGER` (`1` slot, zero-extended via `I2C` on narrow) | `C` | `ILOAD` / `ISTORE` / `IRETURN` | `CharCell` (`char value; boolean set`) |

* **Nullable `T?` lowering:** `byte?`, `short?`, `int?`, `long?`, `float?`, `double?`, `bool?`, and `char?` have `PrimitiveKind.NONE` and lower to `java/lang/Object` (holding `null` or the corresponding `java.lang.*` wrapper), boxed via `Byte.valueOf`..`Character.valueOf` when widened from non-null `T` and unboxed via `byteValue()`..`charValue()` once narrowed or asserted non-null (`e!`).
* **2-slot local & stack accounting:** `PrimitiveKind.slots()` returns `2` for both `DOUBLE` and `LONG` (and `1` for the other six), keeping `CodeGenerator` local-slot indices (`bridgeParamSlots`, `emitReturnToJava` `POP2` on `void` bridges, etc.) and ASM `CheckClassAdapter` stack frames exact.
* **Compiled class fields (`Script$N$k$Name`):** Unboxed `B`, `S`, `I`, `J`, `F`, `D`, `Z`, `C` instance fields share the existing `$defined` bitmask guard when forward member reads are possible, and `Bootstrap.SelectCache` handles all 8 primitive field descriptors across REPL/module classloaders.
* **`RM003-P2` Babble JVM interface bridges & `RM003-P3` Java interface bridges:**
  * `bridgeDescriptor`, `loadKindOn`, `emitAdaptKind` (including unboxed numeric widenings `I2L`, `I2F`, `I2D`, `L2F`, `L2D`, `F2D`), `packBridgeArgsArray`, and default-method captured `*Cell` support on compiled Babble JVM interfaces (`bm$<name>`) generalize directly over all 8 `PrimitiveKind`s.
  * `emitLoadJavaBridgeArg` and `emitReturnToJava` on compiled Java-interface `implements` bridges replace today's `Byte.valueOf`..`Float.valueOf` boxing fallbacks with direct unboxed `ILOAD`/`LLOAD`/`FLOAD`/`DLOAD` and `IRETURN`/`LRETURN`/`FRETURN`/`DRETURN` fast paths across all 8 JVM primitives.

### 8.2 Built-In `BIP007` Effect Profiles (`BIP011-D12`)

Today `EffectChecker` (`RM002`) treats `java.util.List` / `Map` calls as `impure` unless the user writes file-level `axiom` declarations. `BIP011` seeds `HostEffects` / `EffectChecker` with built-in baseline contracts for all core types (overridable only if tightened or compatible, never requiring boilerplate `axiom`s):

| Target Family | Built-In `BIP007` Effect Profile | Receiver / Parameter Modifiers |
|---|---|---|
| Scalar operators (`+`, `-`, `*`, `/`, comparisons) & literals | `pure deterministic nothrow` *(division by `0` in `int/int` produces `Infinity`/`NaN` in `double`, which does not throw!)* | — |
| Scalar conversions `byte(x)`..`char(x)`, `bool(x)` & postfix `e!` | `pure deterministic` *(not `nothrow` when input can fail range/parse/null check; `int(b: byte)` / `long(i: int)` / `double(i: int)` are `pure deterministic nothrow`!)* | — |
| `string(x)` | `pure deterministic` (`nothrow` on primitives, `string`, and `value class`es whose `toString` is `nothrow`; inherits `x.toString()` effect on general objects) | `borrow x` |
| `string` (`java.lang.String`) methods | `pure deterministic` (`nothrow` on total methods like `length()`, `isEmpty()`, `isBlank()`, `trim()`, `strip()`, `toLowerCase()`, `toUpperCase()`, `startsWith(s)`, `endsWith(s)`, `equals(o)`, `hashCode()`; non-`nothrow` on index/regex methods like `charAt`, `substring`, `split`) | Immutable non-`mut` `borrow` receiver |
| `entry(k, v)` & `entry.getKey()` / `entry.getValue()` | `pure deterministic nothrow` *(when `K` and `V` are immutable/scalars; `hermetic deterministic nothrow` otherwise)* | `borrow` receiver |
| Core collection constructors (`list(...)`, `set(...)`, `sortedset(...)`, `map(...)`, `sortedmap(...)`) | `hermetic deterministic` (`nothrow` on `list()`, `set()`, `map()`, `list(e1, ...)`; `capacity:` can throw on `< 0`; `set`/`map` with elements inherits `equals`/`hashCode` effects, which are `pure deterministic nothrow` on scalars/`string`/`value class`!) | `borrow` on `from:` input |
| Collection read methods (`size`, `isEmpty`, `get`, `getOrDefault`, `contains`, `containsKey`, `containsValue`, `indexOf`, `first`, `last`, `firstKey`, `lastKey`) | `hermetic deterministic` (`size` and `isEmpty` are also `nothrow`!) | **`borrow`** (non-`mut`) receiver & `borrow` argument |
| Collection view/iterator methods (`iterator`, `subList`, `keySet`, `values`, `entrySet`, `headSet`, `tailSet`, `subSet`, `headMap`, `tailMap`, `subMap`) | `hermetic deterministic` | Borrows receiver (tracked as `@PARAM`/`@THIS` derived sub-reference in `EffectChecker` so mutating `xs.subList(0, 1)` or `m.keySet()` attributes `mut` back to `xs` / `m`!) |
| Collection mutators (`add`, `addAll`, `set`, `put`, `putIfAbsent`, `putAll`, `remove`, `removeAll`, `retainAll`, `replace`, `clear`) | `hermetic deterministic` | **`mut`** receiver |

Notice the immediate payoff:
* A `hermetic` function can allocate local `list`/`set`/`map` instances (`unique` local mutation in `pure` functions connects seamlessly when `BIP007-P2` lands!), read `borrow` collection parameters, and return deterministic results with **zero `axiom` boilerplate** (because `set` and `map` default to `LinkedHashSet` and `LinkedHashMap`, even iterating over `set`/`map` preserves `deterministic`!).

---

## 9. Open Questions & Decisions (`BIP011-Q1`–`BIP011-Q13` → `BIP011-D1`–`BIP011-D13`)

* **`BIP011-Q1` → `BIP011-D1` (Collection core types & default concrete classes):** *Which collection types belong in Babble's core roster, and which concrete `java.util` classes back their constructors?*
  **Decided (`BIP011-D1`):** Ship 5 core collection types plus `entry`: `list(T)` $\leftrightarrow$ `List<T>` (`ArrayList`), `set(T)` $\leftrightarrow$ `Set<T>` (`LinkedHashSet` for deterministic insertion order), `sortedset(T)` $\leftrightarrow$ `SortedSet<T>` (`TreeSet`), `map(K, V)` $\leftrightarrow$ `Map<K, V>` (`LinkedHashMap` for deterministic insertion order), `sortedmap(K, V)` $\leftrightarrow$ `SortedMap<K, V>` (`TreeMap`), and `entry(K, V)` $\leftrightarrow$ `Map.Entry<K, V>` (`AbstractMap.SimpleImmutableEntry`, supporting nullable `K?`/`V?`). Drop separate `orderedset`/`orderedmap` names since `set` and `map` already preserve insertion order by default (§5.1).
* **`BIP011-Q2` → `BIP011-D2` (Scalar core types roster):** *Should Babble stick to `int`/`double`/`bool`/`string` or cover all 8 JVM primitives?*
  **Decided (`BIP011-D2`):** Cover all 8 JVM primitives plus `string` (`byte`, `short`, `int`, `long`, `float`, `double`, `bool`, `char`, `string`), where `p` maps to JVM primitive `p` (`@NotNull` wrapper in generics) and `p?` maps to nullable boxed wrapper `java.lang.P` (§4.1).
* **`BIP011-Q3` → `BIP011-D3` (Scalar literals & conversions):** *How are narrower/wider scalars written and converted?*
  **Decided (`BIP011-D3`):** Add `42L`/`42l` (`long`), `1.5f`/`1.5F` (`float`), and `'a'` (`char`) literals; support range-checked contextual typing of integer/decimal literals for `byte`, `short`, `long`, and `float`; and provide all 9 built-in conversion functions `byte(x)`..`string(x)` in `Implicits` (§4.2, §4.3).
* **`BIP011-Q4` → `BIP011-D4` (Arithmetic promotion, widening & `char`):** *How do the 6 numeric types interact in operators and assignments, and is `char` numeric?*
  **Decided (`BIP011-D4`):** Extend implicit numeric widening to `byte -> short -> int -> long -> float -> double`; follow JLS binary numeric promotion (`byte`/`short` promote to `int`, then `long -> float -> double`) for `+`, `-`, `*`; keep `/` floating-point (`float` for single-precision operands without `long`/`double`, else `double`); disallow implicit narrowing in compound `op=`; and keep `char` strictly **non-numeric** (ordered/equatable, explicit `int(c)`/`char(n)`) (§4.4).
* **`BIP011-Q5` → `BIP011-D5` (`string` vs primitive members):** *Should `string` expose `java.lang.String` methods directly, and should primitives expose wrapper methods?*
  **Decided (`BIP011-D5`):** `string` directly exposes `java.lang.String` instance methods (`"abc".toUpperCase()`, `s.length()`); all 8 primitives remain memberless (`Not a scope : <type>`) (§4.5).
* **`BIP011-Q6` → `BIP011-D6` (Babble Java-name annotations vs Java reflection nullability):** *What does `java.lang.Integer` or `java.lang.String` mean in Babble code vs in a reflected Java method signature?*
  **Decided (`BIP011-D6`):** In Babble source annotations, every type without `?` is strictly non-null (`java.lang.Integer` == `int`, conceptually `@NotNull Integer`; `java.lang.Integer?` == `int?`; `java.lang.String` == `string`). In Java reflection, primitive descriptors map to non-null `T` (`int.class` $\to$ `int`), constructors map to non-null `C`, and unannotated reference descriptors map conservatively to nullable `T?` (`Integer.class` $\to$ `int?`, `String.class` $\to$ `string?`, `Object.class` $\to$ `any?`) (§6.1, §6.2).
* **`BIP011-Q7` → `BIP011-D7` (Ergonomics & soft-`@NotNull` defense across the Java boundary):** *How do we avoid `T!` platform-type unsoundness without drowning users in casts, and how do we guard against soft Java `@NotNull` violations?*
  **Decided (`BIP011-D7`):** Reject `T!` platform types; recognize JSpecify / `@NotNull` / `@Nullable` annotations; ship a built-in parametric-nullness overlay for core & common `java.base` APIs (`list(T).get(i): T` vs `map(K, V).get(k): V?`); add postfix `e!` non-null assertion (`Type error : expected <T>, got null`); and emit fast Kotlin-style defensive `JavaTypes.checkNotNull` (or combined `instanceof` on erased non-null `TypeVariable` returns, plus Babble-parameter-driven inbound checks on `RM003` Java-interface bridges & `Faces.SamInvoker`) at every soft Java $\leftrightarrow$ Babble non-null boundary (§6.3–§6.6).
* **`BIP011-Q8` → `BIP011-D8` (Collection variance):** *Should `list(int)` be assignable to `list(int?)` or `list(any)`?*
  **Decided (`BIP011-D8`):** Make mutable collections and mutable generic classes **invariant** (closing `BIP008-P6b`'s heap-pollution hole), while preserving covariance on immutable `value class`es, `entry(K, V)`, `Optional(T)`, Java `? extends T` wildcards, and non-`mut` `borrow` collection parameters (§5.4).
* **`BIP011-Q9` → `BIP011-D9` (Explicit vs inferred call-site type arguments):** *Should callers write `list(int)()` or `list()`?*
  **Decided (`BIP011-D9`):** Support **both** explicit call-site type arguments `C(T)(...)` and inferred `C(...)` universally across core collections, Java generic classes, Babble generic classes, and Babble generic functions (§5.2, §7.2).
* **`BIP011-Q10` → `BIP011-D10` (Collection constructor arguments):** *What arguments do `list`, `set`, `sortedset`, `map`, `sortedmap`, and `entry` accept?*
  **Decided (`BIP011-D10`):** Support empty `()`, variadic initial elements/entries (`list(1, 2, 3)`, `map(entry("a", 1))`), and named options (`from:`, `capacity:` via JDK 21 `newLinkedHashSet`/`newLinkedHashMap`, and `comparator:` on `sortedset`/`sortedmap` with static `Comparable` verification when `comparator:` is omitted) (§5.3).
* **`BIP011-Q11` → `BIP011-D11` (Keyword reservation vs JDK `.set`/`.map`/`.list` methods):** *How do reserved core type names avoid breaking calls to `List.set`, `Stream.map`, and `File.list`?*
  **Decided (`BIP011-D11`):** Reserve all 17 core type keywords in declaration, unqualified expression, and type positions (forbidding shadowing via `def`, parameter, type-parameter, or `package`), while allowing `memberName: ID | coreTypeKeyword` after `.` and `?.` (and in `effectTarget` / named-arg labels) so all Java methods remain directly callable (§7.1).
* **`BIP011-Q12` → `BIP011-D12` (`BIP007` effect integration):** *Should core types carry built-in `BIP007` effect contracts?*
  **Decided (`BIP011-D12`):** Yes—ship built-in `HostEffects` / `EffectChecker` profiles (`pure deterministic` for scalars/`string`/`entry`, `hermetic deterministic` constructors, `hermetic borrow` readers, `hermetic mut` mutators, plus sub-reference alias tracking on collection views) (§8.2).
* **`BIP011-Q13` → `BIP011-D13` (`RM001` & `RM003` bytecode unboxing for new primitives):** *Should `CodeGenerator` unbox all 8 primitives or box the 5 new ones?*
  **Decided (`BIP011-D13`):** Extend `ScopeAnalysis.PrimitiveKind`, runtime `*Cell` classes, compiled class fields, `$spec` entry points, `RM003-P2` `bm$<name>` Babble JVM interface bridges, `RM003-P3` Java-interface `implements` bridges, and `Bootstrap` caches to all 8 non-nullable primitives (`BYTE`, `SHORT`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `CHAR`) in a dedicated compiler phase (`BIP011-P6`), while nullable `T?` lowers to boxed wrappers (§8.1).

---

## 10. Implementation Plan, Existing Test Migration & Verification

### 10.1 Sequencing with `BIP010` and Follow-On Proposals

`BIP011` is designed to land cleanly **after `BIP010`** (`docs/design/bip010_type-checker-simplification.md`) on top of the completed `RM001`–`RM003` baseline, taking direct advantage of `BIP010`'s modular front-end collaborators (`TypeAlgebra`, `subsumes`, `CallSolver`, `JavaTypeBridge`, and `LoweringAnalyzer`) and `RM003`'s proxy-free JVM/Java interface bridges and `LambdaMetafactory` SAM adapters, ahead of `BIP007-P2`–`P3` (`unique`/`move`/`freeze` ownership & effect-driven bytecode optimizations), indexing syntax `xs[i]` (`BIP008` §5.1) and `for` loops over `Iterable(T)`, and `std/` higher-order collection combinators (`BIP005-D5`, which no longer need collection wrapper classes!).

### 10.2 Phased Delivery Plan (`BIP011-P1`–`BIP011-P6`)

Every phase keeps `mvn -B package` (`interpret` + `compile` with `CheckClassAdapter`), `BatestTestCase` (`BIP009`), `RuntimeBoundaryTestCase`, `FormatterTestCase`, and `examples/raytracer-argon/scene.ppm` green:

| Phase | Deliverables |
|---|---|
| **`BIP011-P1`: Lexical, Grammar, `Formatter` & Postfix `e!`** | Reserve core type keywords in `Babble.g4` with `memberName` selector rule (`BIP011-D11`); add `LONG_LIT`, `FLOAT_LIT`, `CHAR_LIT`, extended escapes, `# nonNullAssertion` (`e!`), `# coreCollectionCall`, `# coreConversionCall`, and `# typeApplyCall` (`C(T)(...)`); update `Formatter`; migrate existing `.ba`/`.batest`/JUnit snippets that bind local variables named `list`, `set`, `map`, or `char` (audit below). Implement `e!` in `TypeChecker`, `Interpreter`, and `CodeGenerator`. |
| **`BIP011-P2`: Full 9-Scalar Type System, Promotions, Conversions & Transparent `string`** | Add `Type.BYTE`, `SHORT`, `LONG`, `FLOAT`, `CHAR` (plus `java.lang.*` annotation aliases); implement literal range checking & contextual typing (§4.2), all 9 `ConvertFunction` built-ins (§4.3), JLS numeric promotion & `byte -> ... -> double` `subsumes` widening (§4.4, fixing `Operators` `Long`/`Float` math; initially boxing the 5 new primitives in `CodeGenerator` until `P6`), non-numeric `char` (including removing `Character.class` from `JavaTypes.WIDENINGS`), and direct `java.lang.String` method dispatch on `string` (§4.5). |
| **`BIP011-P3`: Java Nullability Alignment, JDK Parametric Overlay & Defensive Checks** | Update `JavaTypeBridge` (`BIP011-D6`, `D7`): map unannotated Java wrapper/reference returns & fields to `T?` (`Integer` $\to$ `int?`, `String` $\to$ `string?`, `Object` $\to$ `any?`), add JSpecify/`@NotNull`/`@Nullable` annotation scanning, add the `java.base` parametric-nullness overlay (§6.4), replace outbound `JavaTypes.checkBoxed` on nullable wrappers with `JavaTypes.checkNotNull` + single-`instanceof` generic non-null checks, align inbound `RM003-P3` `emitLoadJavaBridgeArg` and `RM003-P4` `Faces.SamInvoker` parameter checks with Babble callable parameter types (§6.6), and update affected Java-interop `.batest` expectations. |
| **`BIP011-P4`: Core Collections (`list`..`entry`), Universal `C(T)(...)` & Variance** | Implement `list`, `set`, `sortedset`, `map`, `sortedmap`, `entry` types and runtime/compiled constructors (`()`, variadic, `from:`, `capacity:`, `comparator:` + static `Comparable` check, §5.1–§5.3); wire universal `C(T)(...)` and inferred `C(...)` in `CallSolver`; enforce mutable collection/generic invariance + `value class`/`entry`/`borrow` read-only covariance (`BIP011-D8`, §5.4) and `Unchecked cast` warnings; add `is list`..`is entry` support (§7.4). |
| **`BIP011-P5`: Built-In `BIP007` Core Effect Profiles** | Register built-in `HostEffects` / `EffectChecker` contracts for scalars, `string`, `entry`, and core collection constructors, `borrow` readers, view sub-references, and `mut` mutators (§8.2); add `.batest` and `EffectsTestCase` suites verifying `pure` and `hermetic` core-type usage without `axiom`s. |
| **`BIP011-P6`: 8-Primitive `RM001`/`RM003` Unboxed Bytecode Lowering & Coverage Closure** | Extend `ScopeAnalysis.PrimitiveKind`, `ByteCell`/`ShortCell`/`LongCell`/`FloatCell`/`CharCell`, compiled class fields, `$spec` entry points, `RM003-P2` `bm$<name>` Babble JVM interface bridges, `RM003-P3` Java-interface `implements` bridges (`emitLoadJavaBridgeArg`/`emitReturnToJava`), and `Bootstrap` inline caches to all 8 non-nullable primitives (§8.1); run `mvn -q verify -Pbatest` (`BIP009`) across dedicated scalar, collection, variance, and Java-nullability `.batest` suites and update `README.md`, `CLAUDE.md`, and `docs/design/README.md`. |

### 10.3 Repository Migration Impact Audit

A static scan of `src/test/` and `examples/` (including `RM003`'s new test suites) identifies three bounded test adjustments required during `BIP011-P1`–`P4`:

1. **Reserved keyword identifier renames (`BIP011-P1`):** A handful of test snippets declare local variables or Babble test helper methods named `def list = ...`, `def set = ...`, `def map = ...`, or `Box.map` (`src/test/babble/Generics.ba`, `src/test/batest/compiler/java_implements_bridges.batest`, `DifferentialTestCase.java`, `TypeCheckerTestCase.java`, and selected `.batest` files). Selector calls to Java `xs.set(...)`, `stream.map(...)`, and `file.list()` require **zero changes** (`BIP011-D11`); local Babble variable declarations rename cleanly to `xs`, `items`, `dict`, or `transform`.
2. **Mutable generic covariance tests (`BIP011-P4`):** Test cases in `src/test/babble/Generics.ba` and `src/test/batest/types/generics.batest` that previously asserted `ArrayList(int)` was assignable to `ArrayList(any)` or `Box(int)` to `Box(any)` on mutable classes update to test **invariance errors** on mutable bindings alongside **valid covariance** on `value class`es and `(borrow xs: list(any))` parameters.
3. **Unannotated Java `Integer`/`Double`/`Boolean`/`String` nullability (`BIP011-P3`):** Tests in `src/test/batest/java/java_runtime_edges.batest`, `src/test/batest/java/sam_metafactory.batest` (`BoxedIntUnaryOperator`), `linker_and_defaults.batest`, and `shared_runtime_edges.batest` that tested `JavaTypes.checkBoxed` / `checkParameter` throwing when an unannotated `Integer` return or SAM parameter carried `null` migrate: unannotated `Integer` returns/parameters now type cleanly as `int?`, while `@NotNull`-annotated Java helpers and generic SAMs (`UnaryOperator(int)`) test the new `JavaTypes.checkNotNull` and Babble-parameter-driven inbound boundary errors (`Type error : expected int, got null`).
