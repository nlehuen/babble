# BIP020: Array types

| | |
|---|---|
| **BIP** | 020 |
| **Date** | 2026-11-21 |
| **Status** | Draft (`BIP020-D1`–`D4` decided, `BIP020-D5`–`D10` proposed, `BIP020-Q1`–`Q6` open ; `BIP020-P1`–`P5` not started) |
| **Depends on** | static typing and Java arrays ([bip008_static-typing.md](bip008_static-typing.md) §5.1), core types, nullness and variance ([bip011_core-types.md](bip011_core-types.md)), `of` generics ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops over arrays and static factories ([bip013_iterators.md](bip013_iterators.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)), axioms ([bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)) |
| **Scope** | A type for JVM arrays, `array of T`, that programs and axioms can write ; creating arrays (`(array of int)(100)`, `(array of int).of(1, 2, 3)`) ; reading and writing their elements ; their representation, nullness, variance and effects ; and the type parameters of generic members (`def toArray of T = fn …`), in classes, interfaces and axioms |

---

## 1. Problem Statement

Babble programs already hold arrays : Java returns them (`"a,b".split(",")`, `"abc".toCharArray()`, `list.toArray()`), `for` loops iterate them (BIP013), `length` reads their length, and the analysis types them (`Type.JavaArray`, BIP008 §5.1). But the language has **no name for them**, so :

* **No annotation can mention one.** `def parts: ??? = s.split(",")` has nothing to write ; neither has a parameter, a field, a return type, an `is` or an `as`. The interactive mode's `:type "a,b".split(",")` answers `JavaArray of string`, a type that cannot be written back.
* **No program can make one.** The only way is reflection, which gives up the types :

  ```babble
  def Array = java.lang.reflect.Array
  def arr = Array.newInstance(java.lang.Integer.TYPE, 3)    ;; any?
  Array.setInt(arr, 0, 10)
  println(Array.getInt(arr, 0))
  ```

* **No element can be read or written** except through `java.lang.reflect.Array.get(a, i)`, which is `any?`.
* **Java varargs need an array** (Babble matches them on their declared array parameter, never expands them), so `java.util.Arrays.asList("only")` is rejected, and a program must find a Java method that happens to return the right array (`java.util.List.of("x").toArray()`).
* **Axioms cannot describe overloads taking arrays.** An axiom (BIP019) tells overloads apart by their parameters' Java erasures ; `String.valueOf(char[])`, `String.format(String, Object...)` or `Arrays.sort(int[])` have none Babble can write, so such an overload can only get an effects-only axiom (`fn pure`), for every overload of the name at once, or be left to inference.
* **Arrays print as `[Ljava.lang.String;@2ea6137`.**

A second gap shows up in the same places — `<T> T[] toArray(T[] a)`, `<T> List<T> asList(T... a)` : **generic methods' own type parameters.** Babble already declares them on a `def` (`BIP012-D3`), including members (`def mapBox of U = fn …` in a class, `src/test/babble/Generics.ba`), but nothing says how an axiom's member declares them, and implementing a generic interface member fails today, with a diagnostic whose two types are identical :

```babble
def Conv = interface (
    def conv of T: fn (shared x: T): list of T
)
def C = class () implements Conv (
    def conv of T = fn (shared x: T): list of T -> (list(x))
)
;; Type error : expected (T) (shared x : T) : list(T), got (T) (shared x : T) : list(T)
```

(`TypeAlgebra.functionRelates` refuses any function type with type parameters.)

---

## 2. Design

### 2.1 The type `array of T` (`BIP020-D1`)

```babble
def parts: array of string = "a,b".split(",")
def counts: array of int = (array of int)(100)
def grid: array of (array of double) = …
def maybe: (array of int)? = null
def sum = fn (xs: array of int): int -> (…)
```

* **Syntax :** `array` is a core type keyword, like `list` (`BIP011-D11`), and takes one type argument with `of`. It joins `coreCollectionKeyword` in `Babble.g4`, which puts it everywhere `list` is — in types, as an expression (`array.of(…)`, `(array of int)(…)`), and exempted after `.` in `memberName`, so `buffer.array()` (`java.nio.ByteBuffer`) keeps working. One token, no new rule, no predicate.
* **Nullness** reads as it does for `list` : `array of int?` holds nullable elements, `(array of int)?` is a nullable array, `array of ((array of int)?)` an array of nullable arrays.
* **`array` is reserved** from then on ; no identifier of the repository is named `array`.
* **`array of T` replaces `JavaArray of T`** wherever types are shown (`:type`, `TypeDisplay`) and in diagnostics, which show `string[]` or `java.lang.String[]` today : `Type error : expected int, got array of string`, `No such name in array of string : nope`, `Cannot assign final field : array of string.length`. `Type.JavaArray` becomes `Type.Array`, since arrays are no longer only Java's.

### 2.2 Representation (`BIP020-D5`)

An `array of T` is a plain JVM array, in both modes : no wrapper, the same object Java returns or receives (as core collections are the `java.util` interfaces, BIP011). Its class is the array of the **erasure** of `T` :

| Element type | JVM array |
|---|---|
| the eight primitives `byte`..`char` | `byte[]`..`char[]` |
| their nullable forms `int?`… | `Integer[]`… |
| `string`, `string?` | `String[]` |
| a Java class or interface `C`, `C?` | `C[]` |
| `list of …`, `set of …`, `map of …`, `entry of …` | `List[]`, `Set[]`, `Map[]`, `Map.Entry[]` |
| `array of U` | the array of `U`'s array (`array of (array of int)` is `int[][]`) |
| `any`, `any?`, unions, function types, Babble classes, records, interfaces and objects, type variables | `Object[]` |

* **Element nullness is erased**, except for the primitives : `array of string` and `array of string?` are both `String[]`, but `array of int` (`int[]`) and `array of int?` (`Integer[]`) are different objects. That is the one place where `int` and `int?` differ in representation inside a container (`list of int` and `list of int?` are both a `List` of `Integer`), and it drives variance (§2.5).
* **Babble-defined element types erase to `Object[]`** in both modes, although the compiler has a JVM class for a Babble class : an array made in one mode must have the same class in the other, and the interpreter has none.

### 2.3 Creating arrays (`BIP020-D2`, `D3`, `D6`)

| Form | Result | JVM |
|---|---|---|
| `(array of int)(100)` | 100 elements, each the element type's default | `new int[100]` |
| `array(100)`, with an expected type (`def a: array of int = array(100)`) | the same, `T` from the expected type, as `list()` (BIP011 §5.2) | |
| `(array of int).of(1, 2, 3)` | the given elements | `new int[] {1, 2, 3}` |
| `array.of(1, 2, 3)` | the same, `T` inferred from the elements (`array of int`) | |
| `(array of int)(from: xs)` | a copy of an iterable (a core collection, any `java.lang.Iterable of T`, another `array of T`) | |
| `(array of string)(10, fill: "")`, `(array of Point)(10, init: fn (i: int): Point -> (…))` | see `BIP020-Q1` | |

* **`(array of T)(n)`** (`BIP020-D2`) is a length, as in Java's `new int[n]` ; a negative length throws `NegativeArraySizeException`. The default element is `0`, `0.0`, `false` or `'\0'` for the primitives and `null` for nullable types. **A non-null reference element type has no default** : `(array of string)(10)` would hold ten `null`s typed `string`, so it is rejected (`BIP020-Q1` says what to write instead).
* **`.of(…)`** (`BIP020-D3`) gives the elements, like `(list of int).of(…)` (BIP013 §3.2). Unlike `list.of`, whose list is unmodifiable, the array is an ordinary, mutable one : the JVM has no read-only arrays (`frozen`, §2.6, is the static way to get one).
* **`list(100)` and `array(100)` differ** — a one-element list, a hundred zeros. The length form follows Java, where arrays have a fixed length and no capacity ; `.of(…)` is the element form for both.
* **Element types must be reifiable** (`BIP020-D6`) : an array is created with its class, which a type variable doesn't give at run time (generics are erased). `(array of T)(n)`, `(array of T).of(x)` and `(array of T)(from: xs)` are rejected (`Generic array creation : array of T`), as are element types whose erasure depends on a type variable (`T?`, `array of T`, a union with a type variable) ; `array of (list of T)` is fine (`List[]`). Generic code that needs storage uses a `list of T`, or receives its array from a caller who knows the type.

### 2.4 Members (`BIP020-D7`)

| Member | Type | Compiled to |
|---|---|---|
| `a.length` | `int`, read-only (as today) | `ARRAYLENGTH` |
| `a.get(i)` | `T` | `<X>ALOAD` |
| `a.set(i, v)` | `null` ; `v: T` | `<X>ASTORE` |
| `for x in a then (…)` | as today (BIP013, tier 2) | indexed loop |

* `get` and `set` are named as `list`'s are, so an indexing syntax (BIP008 §5.1) can later lower to the same members for arrays and lists alike, without semantics of its own. A bad index throws `ArrayIndexOutOfBoundsException`, an incompatible store (§2.5) `ArrayStoreException`.
* The interpreter reads and writes through one runtime helper (`org.babblelang.runtime.ArraySupport`, `java.base` only, in the runtime jar) switching on the array's class ; the compiler emits the typed instruction, the element type being always known (a type variable's array is an `Object[]`, §2.6).
* **`==` is identity**, as `equals` is for JVM arrays ; `java.util.Arrays.equals(a, b)` compares contents. Arrays as map keys or set elements are compared by identity too.
* **Effects** (BIP016 vocabulary) :

  | Operation | Effects |
  |---|---|
  | `length` | `pure nothrow` — an array's length never changes, so it is not a read of mutable state |
  | `get` | pure, borrows `a` ; may throw |
  | `set` | `mut` on `a`, `shared v`, shallow (touches no element) ; may throw |
  | constructors, `.of` | pure, fresh `unique` result (so `freeze((array of int).of(1, 2, 3))` is a `frozen array of int`) ; `init:` forwards its callback's effects (`calling init`, BIP017) |

### 2.5 Subtyping and variance (`BIP020-D8`)

Arrays are mutable containers, so they follow BIP011-D8 — with one more condition, since the JVM checks an array's class :

* **Same representation first.** `array of S` and `array of T` are related only if their element types relate *without changing representation* : never `array of int` and `array of int?`, nor `array of int` and `array of long` or `array of any` (an `int[]` is none of `Integer[]`, `long[]`, `Object[]`).
* **Invariant** in ordinary assignments : `def ys: array of any = parts` is rejected, as for `list of any`.
* **Covariant where the array can't be written** : at a non-`mut` borrowed parameter (the default for typed parameters since BIP016), and for `frozen array of T` (BIP007-D7) — `array of string` passes for `array of string?` or `array of any?`.
* **Java parameters** keep Java's covariance of reference arrays today (`TypeAlgebra` checks `isAssignableFrom`) ; `BIP020-Q2` decides whether they move to the rule above.

### 2.6 Type variables (`BIP020-D6`, `BIP020-Q3`)

`array of T`, for a type variable `T`, is an `Object[]` at run time (§2.2). Only an instantiation with the same representation is sound, so `T` may only be bound to a type whose array is a reference array :

```babble
def first of T = fn (a: array of T): T -> (a.get(0))
first(parts)                ;; T = string
first("abc".toCharArray())  ;; Type error : expected array of T, got array of char
```

The same holds for a Java method's type variables (`<T> T[] toArray(T[] a)` : `xs.toArray((array of int)(0))` is rejected, `xs.toArray((array of int?)(0))` gives an `array of int?`). This is the rule Java has, where a type variable is never a primitive ; `BIP020-Q3` weighs it against generic code over primitive arrays.

### 2.7 Java interoperability (`BIP020-D9`)

* **From Java**, an array `E[]` maps to `array of E'` with BIP011's nullness (§6.2 there) : an unannotated `String[]` is `(array of string?)?`, a null-marked one `array of string`, `"a,b".split(",")` (overlay) `array of string`.
* **To Java**, an `array of T` passes as itself where its class fits the parameter (§2.5).
* **Varargs** are still matched on their array parameter and not expanded ; `.of` is the explicit spelling : `java.util.Arrays.asList(array.of("only"))`, `java.lang.String.format("%s=%d", (array of any).of("x", 1))` (`BIP020-Q5`).
* **Stores into arrays of Java interface types** convert a Babble object as passing it to a Java parameter does (`set` on an `array of java.lang.Runnable` stores the object's Java face in the interpreter, the object itself in compiled mode).
* **`is` and `as`** check the array's class (`INSTANCEOF [I`, `[Ljava/lang/String;`). Where the class doesn't tell the whole type — element nullness (`array of string` against `array of string?`), Babble-defined or `any` elements (`Object[]`) — they warn `Unchecked cast`, as for `is list of string`.
* **Printing** : `BIP020-Q4`.

### 2.8 The type parameters of generic members (`BIP020-D4`, `BIP020-D10`)

A member declares its own type parameters as any `def` does (`BIP012-D3`) : with `of` after its name, whether it has a value or only a type.

```babble
;; a class's method : already valid
def toArray of T = fn (a: array of T): array of T -> (…)

;; an interface's abstract member : already valid
def toArray of T: fn (a: array of T): array of T

;; a member of a class's axiom (BIP019) : a def with a type and no value, so the same
axiom java.util.Collection of E: class (
    def toArray of T: fn (a: array of T): array of T
)
```

(`BIP020-D4`.) A call gives the type arguments by inference or explicitly, as for a function value (`(c.conv of string)("a")` works today).

**Conformance** (`BIP020-D10`) : an implementation or an override of a generic member, or a Babble class's method against an inherited axiom's contract (BIP019), must declare the same number of type parameters ; its type is compared with the declared one after renaming its type parameters to the declared ones, by position, then as any function type (parameters contravariant, result covariant, effects). A non-generic implementation of a generic member, or the reverse, is reported by name : `Type parameters of conv : expected 1, got 0`. This fixes §1's example.

**Bridges** (`RM003`) : a Babble class implementing a Java interface's generic method gets its bridge at the erased signature, `toArray([Ljava/lang/Object;)[Ljava/lang/Object;` for `Collection.toArray(T[])` — which, with array types, a Babble class can now implement at all.

### 2.9 Axioms (`BIP019`)

With array types, an axiom (BIP019) names every overload by its parameters. An `array of T` parameter stands for the Java array of `T`'s erasure (§2.2), and a type variable's array for its bound's (`Object[]`) :

```babble
axiom java.lang.String: class (
) static (
    def valueOf: fn (data: array of char): string                       ;; valueOf(char[])
    def valueOf: fn (data: array of char, offset: int, count: int): string
    def format: fn (format: string, args: array of any?): string          ;; format(String, Object...)
)
axiom java.util.Arrays: class (
) static (
    def sort: fn (mut a: array of int)                                    ;; sort(int[])
    def sort: fn (mut a: array of any?)                                   ;; sort(Object[])
    def asList of T: fn (shared a: array of T): list of T                 ;; asList(T...)
)
```

BIP019's provision that "methods taking arrays, which Babble types cannot name, are left to the analyzer or to a future form" is this form ; its text points here once both land.

---

## 3. Alternatives Considered

* **`int[]`** : brackets break Babble's single-bracket design (BIP012), and `[` is not a token.
* **`JavaArray of T`**, the name shown today : long, and arrays are a core type, not a Java curiosity.
* **`array(1, 2, 3)` for elements**, as `list(1, 2, 3)` : the length would then need a name (`array(length: 100)`). Java's meaning of a length was preferred, with `.of(…)` for elements.
* **A wrapper class** (a Babble `Array` holding a Java array) : a copy, or an unwrap, at every Java call, against BIP011's zero-wrapper rule.
* **Type parameters on the function type** (`def toArray: fn of T (a: array of T): array of T`) : `def … of T` is how a `def` declares them already ; a function type with its own type parameters (a parameter that must itself be generic) is a separate feature.

---

## 4. Open Questions (`BIP020-Q1`–`Q6`)

* **`BIP020-Q1` — Non-null reference elements.** `(array of string)(10)` has no default to fill with. Options : (a) reject it, and let programs write `(array of string?)(10)` or `.of(…)` ; (b) a `fill:` value, `(array of string)(10, fill: "")`, as `java.util.Arrays.fill` — but every slot holds the same object, the classic trap with mutable elements (`(array of (array of double))(n, fill: row)`) ; (c) an `init:` function of the index, `(array of Point)(10, init: fn (i: int): Point -> (Point(i, 0)))`, as `java.util.Arrays.setAll`, effects forwarded with `calling init`. *Proposed :* (a) and (c) ; `fill:` adds a trap for little gain over `init: fn (i: int): string -> ("")`.
* **`BIP020-Q2` — Java parameters.** Keep Java's covariance of reference arrays at Java parameters (an `array of string` passes for `Object[]`) — safe at run time, since the JVM checks every store and throws `ArrayStoreException`, but a store Babble types accept can fail — or apply §2.5 there too, where a parameter is covariant only if the axioms or the bytecode analysis say Java doesn't write it ? *Proposed :* apply §2.5, with Java parameters of unknown effects kept covariant until the axioms cover them, so that `java.util.Arrays.toString(parts)` keeps compiling.
* **`BIP020-Q3` — Type variables over primitive arrays.** §2.6 binds a type variable only to reference-array elements, as Java does : simple, fast (`AALOAD`), and sound at Java boundaries. The alternative lets `first("abc".toCharArray())` bind `T = char`, `array of T` being then any array, read through `ArraySupport` with boxing, and never passed to a Java array parameter. *Proposed :* the Java rule ; `list of T` serves generic code over primitives.
* **`BIP020-Q4` — Printing.** `println(parts)` prints `[Ljava.lang.String;@2ea6137`, and so do the interactive mode and string concatenation. Render arrays as lists are (`[a, b]`, through `java.util.Arrays.deepToString`) in `print`, `println`, `+` on strings and the interactive mode ? `toString()` called explicitly stays Java's. *Proposed :* yes.
* **`BIP020-Q5` — Varargs expansion.** With arrays easy to make, keep varargs unexpanded (`asList(array.of("only"))`) or expand trailing arguments into the varargs array, as Java does (`asList("only")`) ? Expansion brings back the ambiguities Java resolves in phases (an `array of any?` passed to `Object...`). *Proposed :* keep them unexpanded here ; expansion, if wanted, is its own change.
* **`BIP020-Q6` — `get` and `set` before an indexing syntax.** Ship `get`/`set` now (§2.4), or wait for the indexing syntax BIP008 §5.1 anticipates ? *Proposed :* now : arrays are unusable without them, and the indexing syntax lowers to them anyway.

---

## 5. Decisions (`BIP020-D1`–`D10`)

Decided :

* **`BIP020-D1` — The type :** `array of T`, `array` a core type keyword (§2.1).
* **`BIP020-D2` — Length constructor :** `(array of T)(n)`, or `array(n)` with an expected type, makes an array of `n` default elements (§2.3).
* **`BIP020-D3` — Elements :** `(array of T).of(…)`, or `array.of(…)` with `T` inferred, makes an array of the given elements (§2.3).
* **`BIP020-D4` — Generic members :** a member declares its own type parameters with `of` after its name, `def toArray of T = fn …`, `def toArray of T: fn …` without a value, in classes, interfaces and axioms alike (§2.8).

Proposed :

* **`BIP020-D5` — Representation :** plain JVM arrays of the element type's erasure, the same in both modes (§2.2).
* **`BIP020-D6` — Reifiable creation, reference type variables :** no array is created of an element type whose erasure depends on a type variable ; a type variable binds only reference-array elements (§2.3, §2.6).
* **`BIP020-D7` — Members :** `length`, `get`, `set` and `for`, with the effects of §2.4.
* **`BIP020-D8` — Variance :** same representation, invariant, covariant where unwritable (§2.5).
* **`BIP020-D9` — Java :** BIP011's nullness, no varargs expansion, `is`/`as` by class with `Unchecked cast` warnings (§2.7).
* **`BIP020-D10` — Conformance of generic members :** same number of type parameters, compared after renaming (§2.8).

---

## 6. Phases (`BIP020-P1`–`P5`)

| Phase | Deliverables |
|---|---|
| **`BIP020-P1` — The type** | `ARRAY` in `Babble.g4` (`coreCollectionKeyword`) ; `array of T` in `TypeAnnotations` ; `Type.JavaArray` → `Type.Array` with the representation of §2.2 ; variance (§2.5) in `TypeAlgebra` ; `is`/`as` and `Unchecked cast` ; `TypeDisplay` and diagnostics ; the formatter (spacing as for `list of T`) ; `ParserGrammarTestCase` stays predicate-free. |
| **`BIP020-P2` — Creating and using** | `array` as a `CoreConstructor` with `(n)`, `from:`, `.of(…)` and `BIP020-Q1`'s answer ; `get`/`set` ; `ArraySupport` in the runtime ; both backends (`Interpreter`, `HirBuilder`/`HirEmitter` : `NEWARRAY`, `ANEWARRAY`, `<X>ALOAD`, `<X>ASTORE`) ; effects (§2.4) ; printing per `BIP020-Q4`. |
| **`BIP020-P3` — Generics** | the reifiability and reference-binding rules (§2.3, §2.6) in `TypeChecker` and `CallSolver`, for Babble and Java type variables ; conformance of generic members (§2.8) in `TypeAlgebra.functionRelates` ; `RM003` bridges for generic Java methods. |
| **`BIP020-P4` — Axioms** | once BIP019's Babble form lands : array parameters in its erasure mapping ; the overloads left to effects-only axioms or to inference get their signatures. |
| **`BIP020-P5` — Docs and migration** | `java.lang.reflect.Array` in tests and examples replaced where it stood for missing syntax ; README tour and checklists, AGENTS.md, BIP008 §5.1, BIP011 §6 and BIP013 §4.2 speak of `array of T`. |

## 7. Verification

* The suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`) after each phase, `-Pbatest`, `-format -check`, and argon rendering `scene.ppm` byte for byte.
* A `src/test/babble/Arrays.ba` script (both modes, type-checked, format-checked) : every element type of §2.2's table, creation, `get`/`set`, `for`, `frozen`, Java round trips (`split`, `toCharArray`, `toArray`, `Arrays.sort`, varargs through `.of`).
* `DifferentialTestCase` snippets for the errors : out-of-bounds, negative length, `ArrayStoreException` through a covariant Java parameter, `Generic array creation`, a primitive array bound to a type variable, `set` on a `frozen array`, `(array of string)(n)`.
* `TypeCheckerTestCase` goldens : variance (§2.5), `Unchecked cast`, diagnostics naming `array of …`, generic member conformance (§1's example compiles, a non-generic implementation is reported) ; `FormatterTestCase` goldens for `array of …` types and constructors.
