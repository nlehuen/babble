# BIP020: Array types

| | |
|---|---|
| **BIP** | 020 |
| **Date** | 2026-11-21 |
| **Status** | Partially implemented (`BIP020-D1`–`D18` decided, resolving `BIP020-Q1`–`Q6` ; `BIP020-D4` and `BIP020-D10` subsumed by `BIP021-D18` and `BIP021-D20` ; `BIP020-P1`–`P4` and `P6` implemented, `BIP020-P5` awaiting `BIP019` and `BIP021-P3`) |
| **Depends on** | static typing and Java arrays ([bip008_static-typing.md](bip008_static-typing.md) §5.1, §11), core types, nullness and variance ([bip011_core-types.md](bip011_core-types.md)), `of` generics ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops over arrays and static factories ([bip013_iterators.md](bip013_iterators.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)), axioms ([bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)), optional, variable, generic and overloaded arguments ([bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md)), intersection types ([bip022_intersection-types.md](bip022_intersection-types.md)), type aliases ([bip023_type-aliases.md](bip023_type-aliases.md)) |
| **Supersedes** | the field-free `abstract class BabbleInstance` of `BIP018-D8` ([bip018_tracking-java-improvements.md](bip018_tracking-java-improvements.md)) : compiled classes extend no runtime class at all (§2.10) |
| **Scope** | A type for JVM arrays, `array of T`, that programs and axioms can write ; creating arrays (`(array of int)(100)`, `(array of int).of(1, 2, 3)`, `from:`) ; their elements, size, equality and printing ; their representation, nullness, variance and effects ; reifiable array creation, reference-only type variables and `RM003` array bridges for generic Java methods (generic-member syntax, bounds and polymorphic conformance live in `BIP021`) ; and compiled Babble classes as plain JVM classes, the runtime base class becoming the interpreter's alone |

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
* **Java varargs need an array** today (Babble matches them on their declared array parameter, never expands them), so `java.util.Arrays.asList("only")` is rejected, and a program must find a Java method that happens to return the right array (`java.util.List.of("x").toArray()`). `BIP021-P2` adds `javac`-style varargs expansion (`BIP021-D8`), while passing an explicit array directly (phase 1) or spreading into an array parameter (`BIP021-D7`) still needs typed arrays.
* **Axioms cannot describe overloads taking arrays.** An axiom (BIP019) tells overloads apart by their parameters' Java erasures ; `String.valueOf(char[])`, `String.format(String, Object...)` or `Arrays.sort(int[])` have none Babble can write, so such an overload can only get an effects-only axiom (`fn pure`), for every overload of the name at once, or be left to inference.
* **Arrays print as `[Ljava.lang.String;@2ea6137`.**

A second gap surfaced alongside `<T> T[] toArray(T[] a)` and `<T> List<T> asList(T... a)` : **generic methods' own type parameters.** Babble already declares them on a `def` (`BIP012-D3`), including members (`def mapBox of U = fn …` in a class, `src/test/babble/Generics.ba`), while nothing said how an axiom's member declares them and implementing a generic interface member fails today with a diagnostic whose two types are identical (`TypeAlgebra.functionRelates` refuses any function type with type parameters) :

```babble
def Conv = interface (
    def conv of T: fn (shared x: T): list of T
)
def C = class () implements Conv (
    def conv of T = fn (shared x: T): list of T -> (list(x))
)
;; Type error : expected (T) (shared x : T) : list(T), got (T) (shared x : T) : list(T)
```

That general gap is now addressed by [BIP021](bip021_optional-variadic-overloaded-arguments.md) (`fn of T (…)` polymorphic function types, bounds `of (T: B)`, and implementable generic interface methods, `BIP021-D18`–`D20`, adopted by `BIP019` for axiom members), leaving BIP020 focused on array types, their interaction with generics and `RM003` Java array bridges, and plain compiled classes.

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
* **`array of T` replaces `JavaArray of T`** wherever types are shown (`:type`, `TypeDisplay`) and in diagnostics, which show `string[]` or `java.lang.String[]` today : `Type error : expected int, got array of string`, `No such name in array of string : nope`. `Type.JavaArray` becomes `Type.Array`, since arrays are no longer only Java's.
* **Type aliases (`BIP023`) :** aliases are transparent everywhere in types (`def Ints = type array of int`, `def Matrix of T = type array of (array of T)`, `def a: Ints = array(10)` via expected type, `a is Ints` / `a is Ints?`), while `(Ints)(10)` is rejected because an alias is not a value (`BIP023-D6`).
* **Intersections (`BIP022`) :** `array of T` is not an interface, so it cannot be an operand of `&` (`Only interfaces intersect`, `BIP022-D1`) ; an array value is still assignable to the Java interfaces JVM arrays implement (`java.lang.Cloneable`, `java.io.Serializable`, and `java.lang.Cloneable & java.io.Serializable`).

### 2.2 Representation (`BIP020-D5`)

An `array of T` is a plain JVM array : no wrapper, the same object Java returns or receives (as core collections are the `java.util` interfaces, BIP011). Its class is the array of `T`'s JVM class :

| Element type | JVM array |
|---|---|
| the eight primitives `byte`..`char` | `byte[]`..`char[]` |
| their nullable forms `int?`… | `Integer[]`… |
| `string`, `string?` | `String[]` |
| a Java class or interface `C`, `C?` | `C[]` |
| `list of …`, `set of …`, `map of …`, `entry of …` | `List[]`, `Set[]`, `Map[]`, `Map.Entry[]` |
| `array of U` | the array of `U`'s array (`array of (array of int)` is `int[][]`) |
| a Babble class, record, object or interface `P` | compiled : `P`'s JVM class or interface (`Point[]`, `Shape[]`) ; interpreted : the interpreter's instance class (§2.10) |
| intersections (`A & B`, `BIP022`), overload sets (`BIP021`), `any`, `any?`, unions, function types, type variables | `Object[]` |

* **Element nullness is erased**, except for the primitives : `array of string` and `array of string?` are both `String[]`, but `array of int` (`int[]`) and `array of int?` (`Integer[]`) are different objects — the one place where `int` and `int?` differ in representation inside a container (`list of int` and `list of int?` are both a `List` of `Integer`).
* **Intersections erase to `Object[]`**, not to the first operand (`BIP022-D6`) : `A & B` and `B & A` are the same type (`BIP022-D2`), so `array of (A & B)` and `array of (B & A)` must have the same JVM array class, and `A[]` would not reject storing an `A` that does not implement `B` (`BIP020-D8`).
* **Babble-defined element types differ between the modes** : compiled mode has a JVM class (or interface) per Babble class (or interface) and uses it, so `get` needs no cast and the JVM checks stores exactly ; the interpreter has one instance class for every Babble object, and keeps each such array's element class in a weak side table of `ArraySupport`, so that `set` checks stores as the JVM does in compiled mode and throws the same `ArrayStoreException` (§2.5). A copy made by Java (`java.util.Arrays.copyOf`) is not in the table, and its stores are checked only by the JVM in the interpreter.

### 2.3 Creating arrays (`BIP020-D2`, `D3`, `D6`, `D11`)

| Form | Result | JVM |
|---|---|---|
| `(array of int)(100)` | 100 elements, each the element type's default | `new int[100]` |
| `array(100)`, with an expected type (`def a: array of int = array(100)`) | the same, `T` from the expected type (seeing through transparent `BIP023` aliases), as `list()` (BIP011 §5.2) | |
| `(array of int).of(1, 2, 3)` | the given elements | `new int[] {1, 2, 3}` |
| `array.of(1, 2, 3)` | the same, `T` inferred from the elements (`array of int`) | |
| `(array of int)(from: xs)` | a copy of an iterable : a core collection, any `java.lang.Iterable of T`, another `array of T` | |

* **`(array of T)(n)`** (`BIP020-D2`) is a length, as in Java's `new int[n]` ; a negative length throws `NegativeArraySizeException`. The default element is `0`, `0.0`, `false` or `'\0'` for the primitives and `null` for nullable types.
* **A non-null reference element type has no default** (`BIP020-Q1` → `BIP020-D11`) : `(array of string)(10)` would hold ten `null`s typed `string`, so it is rejected (`No default element : array of string has non-null elements`). There is no `fill:` nor `init:` argument : a program writes `(array of string?)(10)`, gives the elements with `.of(…)`, or builds a collection and copies it with `from:` — `(array of Point)(from: points)` is the way to a non-null array of computed elements.
* **`.of(…)`** (`BIP020-D3`) gives the elements, like `(list of int).of(…)` (BIP013 §3.2). Unlike `list.of`, whose list is unmodifiable, the array is an ordinary, mutable one : the JVM has no read-only arrays (`frozen`, §2.4, is the static way to get one).
* **`list(100)` and `array(100)` differ** — a one-element list, a hundred zeros. The length form follows Java, where arrays have a fixed length and no capacity ; `.of(…)` is the element form for both. Once `BIP021-P4` lands, `...e` spread (`BIP021-D7`) also adapts any iterable into an `array of T` parameter directly (and views an `array of T` as a rest `T...`, `Iterable of T`, or borrowed `list of T` without copying).
* **Element types must be reifiable** (`BIP020-D6`) : an array is created with its class, which a type variable doesn't give at run time (generics are erased) — even when bounded (`of (T: B)`, `BIP021-D19`). `(array of T)(n)`, `(array of T).of(x)` and `(array of T)(from: xs)` are rejected (`Generic array creation : array of T`), as are element types whose class depends on a type variable (`T?`, `array of T`, a union or intersection with a type variable) ; `array of (list of T)` is fine (`List[]`). Generic code that needs storage uses a `list of T`, or receives its array from a caller who knows the type.

### 2.4 Members, equality and printing (`BIP020-D7`, `D14`, `D16`, `D17`)

| Member | Type | Compiled to |
|---|---|---|
| `a.size()` | `int` | `ARRAYLENGTH` |
| `a.get(i)` | `T` | `<X>ALOAD` |
| `a.set(i, v)` | `null` ; `v: T` | `<X>ASTORE` |
| `for x in a then (…)` | as today (BIP013, tier 2) | indexed loop |

* **Named as `list`'s** (`BIP020-Q6` → `BIP020-D16`), so an indexing syntax (BIP008 §5.1) can later lower to the same members for arrays and lists alike. They ship now, since arrays are unusable without them. **`size()` replaces the JVM's `length`**, which disappears from the language : existing uses are migrated, and `a.length` reports `No such name in array of string : length (use size())`.
* A bad index throws `ArrayIndexOutOfBoundsException`, an incompatible store (§2.5) `ArrayStoreException`.
* The interpreter reads and writes through one runtime helper (`org.babblelang.runtime.ArraySupport`, `java.base` only, in the runtime jar) switching on the array's class ; the compiler emits the typed instruction, the element class being always known (a type variable's array is an `Object[]`, §2.6).
* **Equality is by contents** (`BIP020-D17`), as for lists : `a == b` (and `!=`) compares elements with `Arrays.deepEquals` semantics, through Babble's own `==` on each element (so `NaN` and signed zeros compare as Babble's `==` does on doubles), and Babble calls of `a.equals(b)` and `a.hashCode()` on an array are intercepted the same way (`deepEquals`, `deepHashCode`). A record holding an array compares it by contents. **Java still sees identity** : arrays as keys of a `map` or elements of a `set`, and any `equals` Java calls itself, compare them by identity, as the JVM does.
* **Printing** (`BIP020-Q4` → `BIP020-D14`) : arrays render as lists do, `[a, b]`, nested arrays too (`Arrays.deepToString`), in `print`, `println`, string `+`, the interactive mode, and Babble calls of `a.toString()`, which are intercepted. A `toString` Java calls itself (`String.valueOf(Object)`) stays Java's.
* **Effects** (BIP016 vocabulary and `BIP019`'s read-of-mutable-state model) :

  | Operation | Effects |
  |---|---|
  | `size()` | `pure nothrow` — an array's size is fixed at creation, so it is never a read of mutable state |
  | `get`, `==`, `equals`, `hashCode`, `toString`, `for` | pure, borrow the array(s), and read the array's mutable elements (deterministic on `frozen` or exclusively owned arrays and volatile where provenance runs out on a shared mutable array) ; `get` may throw |
  | `set` | `mut` on `a`, `shared v`, shallow (touches no element) ; may throw |
  | constructors, `.of`, `from:` | pure, fresh `unique` result (so `freeze((array of int).of(1, 2, 3))` is a `frozen array of int`, on which `set` is rejected ; `from:` reads its source iterable) |

### 2.5 Subtyping and variance (`BIP020-D8`, `D12`)

Arrays follow Java's covariance for reference elements, restricted to where the JVM's store check is exact (`BIP020-D8`) :

* **Primitive arrays are invariant** : `array of int` relates to no other array (`int[]` is none of `long[]`, `Integer[]`, `Object[]`).
* **JVM array assignability always holds** : `array of S` is a subtype of `array of T` only when `S`'s JVM array class (§2.2) is assignable to `T`'s (`targetClass.isAssignableFrom(sourceClass)`). So `array of (Shape & Named)` (`Object[]`) and `array of (Circle | Square)` (`Object[]`) cannot pass for `array of Shape` (`Shape[]`), even when borrowed or `frozen` (use `(array of Shape)(from: xs)` to copy) — while they *do* pass for a borrowed `array of any?` (`Object[]`), and `array of Circle` (`Circle[]`, where `Circle` implements `Shape` and `Named`) is exact-store covariant to `array of (Shape & Named)` (`Object[]`).
* **Reference arrays are covariant** — `array of string` is an `array of any`, `array of Circle` an `array of Shape` (`Circle` implementing `Shape`) — on two further conditions, which make a bad store fail with `ArrayStoreException` instead of polluting the array :
  * **same element nullness :** `array of string` is not an `array of string?` (nor of `any?`) — both are `String[]`, and storing `null` would succeed ;
  * **an exact source class :** the source's element type must have its own JVM class, so not one erased to `Object[]` (intersections, overload sets, unions, function types, `any`, except reflexively when `S` and `T` are the same type) — storing anything would succeed. (`array of int?` is an `array of any` : `Integer[]` is exact.)
* **Where the array can't be written**, the nullness and exact-source-class conditions are lifted (keeping JVM array assignability) : at a non-`mut` borrowed parameter (the default for typed parameters since BIP016), and for `frozen array of T` (BIP007-D7), as BIP011-D8 does for collections — `array of string` passes for a borrowed `array of string?`.
* **Java parameters** (`BIP020-Q2` → `BIP020-D12`) : unannotated Java array parameters have nullable elements (`Arrays.toString(Object[])` takes `(array of any?)?`), so at a Java parameter the nullness condition is waived, as BIP011 already trusts Java with a `list of string` where Java sees `List<String>` — only JVM array assignability and the exact-source-class condition apply, and `java.util.Arrays.toString(parts)` compiles. Once an axiom (BIP019) or the bytecode analysis says Java writes the parameter (`mut`), the nullness condition applies there again.

### 2.6 Type variables (`BIP020-D6`, `D13`)

`array of T`, for a type variable `T`, is an `Object[]` to generic code (§2.2). A type variable therefore binds only elements stored as references (`BIP020-Q3` → `BIP020-D13`), as in Java, where a type variable is never a primitive ; generic code compiles to `AALOAD`/`AASTORE` :

```babble
def first of T = fn (a: array of T): T -> (a.get(0))
first(parts)                ;; T = string
first("abc".toCharArray())  ;; Type error : expected array of T, got array of char
```

* **Bounded type variables (`BIP021-D19`, `BIP022-D8`) :** even though core scalars satisfy bounds through their boxed Java classes (`int` satisfies `java.lang.Comparable of int`), `array of int` (`int[]`) is still rejected for `array of T` (use `array of int?`, whose JVM class is `Integer[]`). Calling a member of `T`'s bound on an element read from `array of T` compiles to `AALOAD` followed by `CHECKCAST` to the bound (`BIP021-D19`, `BIP022-D6`). Creation (§2.3) stays rejected even when `T` is bounded.
* **Java generic methods :** `<T> T[] toArray(T[] a)` follows the same rule (`xs.toArray((array of int)(0))` is rejected, `xs.toArray((array of int?)(0))` gives an `array of int?`). Generic code over primitives uses `list of T`.

### 2.7 Java interoperability, casts and `BIP021` (`BIP020-D9`, `D15`)

* **From Java**, an array `E[]` maps to `array of E'` with BIP011's nullness (§6.2 there) : an unannotated `String[]` is `(array of string?)?`, a null-marked one `array of string`, `"a,b".split(",")` (overlay) `array of string`.
* **To Java**, an `array of T` passes as itself where its class fits the parameter (§2.5).
* **Varargs** (`BIP020-Q5` → `BIP020-D15`) : `BIP020` itself does not expand varargs — `.of(…)` is the explicit spelling (`java.util.Arrays.asList(array.of("only"))`, `java.lang.String.format("%s=%d", (array of any).of("x", 1))`). Java varargs expansion (`BIP021-D8`, a third `javac`-style phase keeping phase-1 explicit arrays unchanged) and `...e` spread (`BIP021-D7`) are specified in `BIP021` (`BIP021-P2`, `BIP021-P4`).
* **Overload sets (`BIP021-D10`, `D11`) :** primitive arrays overlap statically only themselves (and `any`/`any?`) ; reference arrays overlap when their element types overlap under §2.5 (two unrelated interface-array parameters `array of Shape` and `array of Named` overlap via any class implementing both and have no single array meet). For runtime union splits (`BIP021-D11`), arrays of Babble-defined elements are not runtime-disjoint because `is` checks `Object[]` (`BIP020-D9`).
* **Stores into arrays of Java interface types** convert a Babble object as passing it to a Java parameter does (`set` on an `array of java.lang.Runnable` stores the object's Java face in the interpreter, the object itself in compiled mode).
* **`is` and `as`** (`BIP020-D9`, `BIP008` §11) check the array's class (`INSTANCEOF [I`, `[Ljava/lang/String;`). Where the class doesn't tell the whole type, they warn `Unchecked cast`, as for `is list of string` : element nullness (`array of string` against `array of string?`), elements erased to `Object[]` (intersections, overload sets, unions, function types, `any`), and **Babble-defined elements, checked as `Object[]` in both modes** — compiled mode deliberately doesn't use the stricter `INSTANCEOF [LPoint;`, which the interpreter couldn't match. Because `T?` is `T | null` (`BIP008` §11), `null is (array of int)?` is `true`, `null as (array of int)?` is `null`, and `(array of string)? as (array of int)?` is accepted statically (both share `null`), while `array of string? as array of int` is rejected statically.

### 2.8 Generic members and Java array bridges (`BIP020-D4`, `D10` → `BIP021-D18`, `D20`)

* **Syntax (`BIP020-D4` → `BIP021-D18`, `D20`) :** a member declares its own type parameters with `of` after its name (`def toArray of T = fn (a: array of T): array of T -> (…)` or without a value `def toArray of T: fn (a: array of T): array of T`), which remain valid as shorthand for `BIP021`'s polymorphic function types (`def toArray: fn of T (a: array of T): array of T`, used by `BIP019` for axiom members).
* **Conformance (`BIP020-D10` → `BIP021-D20`) :** implementing or overriding a generic interface member (fixing §1's `Conv` example), checking its type-parameter count and bounds after renaming, and calling generic methods as values are specified in `BIP021-D20` (`BIP021-P3`).
* **Bridges (`RM003`) :** a Babble class implementing a Java interface's generic method gets its bridge at the erased signature, `toArray([Ljava/lang/Object;)[Ljava/lang/Object;` for `Collection.toArray(T[])` — which, with array types, a Babble class can now implement at all.

### 2.9 Axioms (`BIP019`)

With array types and `BIP021`'s polymorphic function types, an axiom (BIP019) names every overload by its parameters. An `array of T` parameter stands for the Java array of `T`'s erasure (§2.2), and `array of T` for a type variable `T` stands for the array of `T`'s bound's Java erasure (`Object[]` when unbounded, `B[]` for `of (T: B)`, and the leftmost bound's erasure for a Java multi-bound intersection per JLS §4.6) :

```babble
axiom java.lang.String: class (
) static (
    def valueOf: fn (data: array of char): string                       ;; valueOf(char[])
    def valueOf: fn (data: array of char, offset: int, count: int): string
    def format: fn (format: string, args: array of any?): string          ;; format(String, Object...)
)
axiom java.util.Collection of E: class (
    def toArray: fn (): array of any?
    def toArray: fn of T (a: array of T): array of T
)
axiom java.util.Arrays: class (
) static (
    def sort: fn (mut a: array of int)                                    ;; sort(int[])
    def sort: fn (mut a: array of any?)                                   ;; sort(Object[])
    def asList: fn of T (shared a: array of T): list of T                 ;; asList(T...)
)
```

`BIP019` (`bip019-doc`) already points to `BIP020` for array parameters and to `BIP021` for polymorphic function types (`fn of T (…)`) and optional parameters (`= ...`).

### 2.10 Compiled classes without a runtime base class (`BIP020-D18`)

Today every compiled Babble class extends `org.babblelang.runtime.BabbleInstance`, the interpreter's instance class, which gives the runtime a generic view of any Babble object : its class (`babbleClass()`), its members (`HasMembers`), its runtime, its Java face, and the equality, hash and string of records. `BIP018-D8` planned to make it a field-free abstract class. Instead, **no compiled class extends a runtime class** : compiled classes, records and objects are plain JVM classes, and compiled Babble interfaces plain JVM interfaces. The base class becomes the interpreter's alone (renamed `InterpretedInstance`), which nothing compiled extends.

Strict typing already makes most of the generic view unnecessary — member access on `any` is rejected — and the rest moves into each class :

* **`is` / `as` on a Babble type** compile to `INSTANCEOF` / `CHECKCAST` ;
* **equality, hash and string** of records, and `toString` of classes, are generated per class ;
* **the Java face** (a compiled class implements its Java interfaces directly, `RM003`) and the lookup for Java default methods are generated per class ;
* **the class value** (`classof`, diagnostics) comes from a static field of the class ;
* **what still goes through the generic view** — `Bootstrap`'s select caches (used by `BIP022-P1` for member calls on intersection receivers until `BIP022-P2` adds static dispatch, as well as by unions and bound method references), `Values.is`, `Types.check` (including `Types.IntersectionType`), `Classes.*`, `Faces`, and `Members` — is rewritten against plain compiled classes via class metadata (`ClassValue` / the static `BabbleClass` field), or removed where static types make it dead.

It comes first (`BIP020-P1`), so that the array code (`Point[]`, §2.2) is written once, against plain compiled classes. It also clears the way for Valhalla `value` records (BIP018), which cannot extend an identity class.

---

## 3. Alternatives Considered

* **`int[]`** : brackets break Babble's single-bracket design (BIP012), and `[` is not a token.
* **`JavaArray of T`**, the name shown today : long, and arrays are a core type, not a Java curiosity.
* **`array(1, 2, 3)` for elements**, as `list(1, 2, 3)` : the length would then need a name (`array(length: 100)`). Java's meaning of a length was preferred, with `.of(…)` for elements.
* **A wrapper class** (a Babble `Array` holding a Java array) : a copy, or an unwrap, at every Java call, against BIP011's zero-wrapper rule.
* **Every Babble-defined element type as `Object[]`** in both modes : simpler and mode-independent, but every `get` needs a cast and the JVM can't check stores ; compiled `Point[]` was preferred, with a side table for the interpreter.
* **Erasing `array of (A & B)` to the intersection's first operand `A[]` (`BIP022-D6`)** : `A & B` and `B & A` are the same type (`BIP022-D2`), so their arrays must have the same JVM class, and `A[]` would not reject storing an `A` that does not implement `B` (`BIP020-D8`).
* **A common supertype of Babble objects** (`BabbleInstance`, or a marker interface `BabbleObject`) so that `is array of Point` checks something stricter than `Object[]` : rejected with §2.10.
* **`fill:` and `init:` arguments** for non-null elements : `fill:` puts the same object in every slot (the classic trap with `(array of (array of double))(n, fill: row)`), and `from:` already covers computed elements.
* **Strict invariance, as for collections (BIP011-D8)** : sound without runtime checks, but every `array of string` given to `array of any` would need a borrowed parameter ; Java's covariance was kept where the JVM checks it exactly.
* **Type variables over primitive arrays**, through `ArraySupport` with boxing : `first(chars)` would work, but generic arrays could then never reach a Java array parameter, and every access would box.
* **Keeping generic-member type parameters only on `def`** : `BIP021-D20` adopted `fn of T (…)` polymorphic function types (which `BIP019` uses in axiom members), while `def toArray of T = fn …` stays valid as shorthand (`BIP021-D18`).

---

## 4. Decisions (`BIP020-D1`–`D18`)

* **`BIP020-D1` — The type :** `array of T`, `array` a core type keyword ; aliases (`BIP023`) are transparent, and `array of T` is not an interface for `&` (`BIP022`) (§2.1).
* **`BIP020-D2` — Length constructor :** `(array of T)(n)`, or `array(n)` with an expected type (seeing through `BIP023` aliases), makes an array of `n` default elements (§2.3).
* **`BIP020-D3` — Elements :** `(array of T).of(…)`, or `array.of(…)` with `T` inferred, makes a mutable array of the given elements (§2.3).
* **`BIP020-D4` — Generic members *(subsumed by `BIP021-D18`, `D20`)* :** `def toArray of T = fn …` and `def toArray of T: fn …` remain valid shorthand for `BIP021`'s polymorphic function types (`def toArray: fn of T (…)`), which `BIP019` axioms use (§2.8).
* **`BIP020-D5` — Representation :** plain JVM arrays of the element's JVM class ; Babble-defined elements use their compiled class or interface in compiled mode, and the interpreter's instance class plus a side table of element classes when interpreted ; intersections (`BIP022`) and overload sets (`BIP021`) erase to `Object[]` (§2.2).
* **`BIP020-D6` — Reifiable creation :** no array is created of an element type whose class depends on a type variable, bounded or not (§2.3).
* **`BIP020-D7` — Members and effects :** `size()`, `get`, `set` and `for`, with the effects of §2.4 aligned with `BIP019`'s read-of-mutable-state model.
* **`BIP020-D8` — Variance :** primitive arrays invariant ; reference arrays covariant where JVM array assignability holds and the JVM's store check is exact (same element nullness, an exact source class — excluding intersections and overload sets), and covariant where unwritable subject to JVM array assignability (§2.5).
* **`BIP020-D9` — Java and casts :** BIP011's nullness ; `is`/`as` by class, Babble-defined elements checked as `Object[]` in both modes, with `Unchecked cast` warnings, and `BIP008` §11's nullable `null` rules (§2.7).
* **`BIP020-D10` — Conformance of generic members *(superseded by `BIP021-D20`)* :** moved to `BIP021-P3`, leaving `RM003` bridges for generic Java interface array methods in `BIP020-P4` (§2.8).
* **`BIP020-Q1` → `BIP020-D11` — No default for non-null elements :** `(array of string)(n)` is rejected ; no `fill:` nor `init:` ; `from:` copies an iterable (§2.3).
* **`BIP020-Q2` → `BIP020-D12` — Java parameters :** the nullness condition of §2.5 is waived at Java parameters, unless an axiom or the analysis says Java writes them (§2.5).
* **`BIP020-Q3` → `BIP020-D13` — Type variables :** bind only elements stored as references, even when bounded (`BIP021-D19`, `BIP022-D8`) (§2.6).
* **`BIP020-Q4` → `BIP020-D14` — Printing :** `[a, b]` in `print`, `println`, string `+`, the interactive mode and Babble calls of `toString()` (§2.4).
* **`BIP020-Q5` → `BIP020-D15` — Varargs :** `BIP020` does not expand varargs (`.of(…)` passes an explicit array) ; expansion (`BIP021-D8`) and `...e` spread (`BIP021-D7`) belong to `BIP021` (§2.7).
* **`BIP020-Q6` → `BIP020-D16` — Element access now :** `get`/`set` ship with the type ; `size()` replaces `length` (§2.4).
* **`BIP020-D17` — Equality by contents :** `==`, and Babble calls of `equals` and `hashCode`, compare arrays by contents ; Java still sees identity (§2.4).
* **`BIP020-D18` — No runtime base class for compiled classes :** compiled classes, records, objects and interfaces are plain JVM types ; the base class is the interpreter's alone, superseding `BIP018-D8`'s abstract class (§2.10).

---

## 5. Phases (`BIP020-P1`–`P6`)

| Phase | Deliverables |
|---|---|
| **`BIP020-P1` — Plain compiled classes** | §2.10 : `ClassGen` emits classes extending `Object` (records' equality, hash and string, `toString`, Java default lookups and the class value generated per class) ; `is`/`as` by `INSTANCEOF`/`CHECKCAST` ; `Bootstrap`/`Members` (including dynamic member dispatch on `BIP022-P1` intersection receivers until `BIP022-P2` adds static dispatch), `Values`, `Types` (including `Types.IntersectionType`), `Classes` and `Faces` rewritten using class metadata without `instanceof BabbleInstance` on compiled objects ; `BabbleInstance` renamed `InterpretedInstance`, interpreter-only ; `RuntimeBoundaryTestCase` and argon unchanged. |
| **`BIP020-P2` — The type** | `ARRAY` in `Babble.g4` (`coreCollectionKeyword`) ; `array of T` in `TypeAnnotations` (including transparent `BIP023` type aliases) ; `Type.JavaArray` → `Type.Array` with the representation of §2.2 (`BIP022` intersections erasing to `Object[]`) ; JVM array assignability and variance (§2.5) in `TypeAlgebra` ; `is`/`as` (`BIP008` §11 nullable rules) and `Unchecked cast` ; `TypeDisplay` and diagnostics ; the formatter (spacing as for `list of T`) ; `ParserGrammarTestCase` stays predicate-free. |
| **`BIP020-P3` — Creating and using** | `array` as a `CoreConstructor` with `(n)` (expected type seeing through `BIP023` aliases), `from:` and `.of(…)` ; `size()`, `get`, `set`, with `length` removed and its uses migrated ; `ArraySupport` in the runtime, with the interpreter's side table of element classes ; both backends (`Interpreter`, `HirBuilder`/`HirEmitter` : `NEWARRAY`, `ANEWARRAY`, `ARRAYLENGTH`, `<X>ALOAD`, `<X>ASTORE`) ; equality, hash and printing by contents ; effects (§2.4, aligned with `BIP019` reads of mutable state). |
| **`BIP020-P4` — Generics** | the reifiability and reference-binding rules (§2.3, §2.6, including bounded type variables `BIP021-D19`/`BIP022-D8`) in `TypeChecker` and `CallSolver`, for Babble and Java type variables ; `RM003` bridges for generic Java array methods (polymorphic member conformance lives in `BIP021-P3`). |
| **`BIP020-P5` — Axioms** | once `BIP019`'s Babble form (and `BIP021-P3` for `fn of T` in generic method axioms) lands : array parameters and bounded type-variable array erasure in its erasure mapping ; the overloads left to effects-only axioms or to inference get their signatures ; the `mut` exception of `BIP020-D12` reads them. |
| **`BIP020-P6` — Docs and migration** | `java.lang.reflect.Array` in tests and examples replaced where it stood for missing syntax ; README tour and checklists, `AGENTS.md` (architecture : compiled classes no longer extend `BabbleInstance`), `BIP008` §5.1, `BIP011` §6, `BIP013` §4.2, `BIP018-D8` and `BIP021` (`JavaArray of T` → `array of T`) speak of `array of T` and plain compiled classes. |

## 6. Verification

* The suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`, including `Intersections.ba`, `TypeAliases.ba`, `types/intersections.batest`, `types/type_aliases.batest`, `types/unions_and_nullables.batest`) after each phase, `-Pbatest`, `-format -check`, and argon rendering `scene.ppm` byte for byte — after `BIP020-P1` especially, which changes every compiled class (and `BIP022-P1` intersection/dynamic paths) without changing behavior.
* A `src/test/babble/Arrays.ba` script (both modes, type-checked, format-checked) : every element type of §2.2's table (including intersections `array of (Shape & Named)` and aliases `def Ints = type array of int`), creation (`(array of int)(n)`, expected-type `array(n)` through aliases, `.of(…)`, `from:`), `size`/`get`/`set`, `for`, `==` and printing (nested arrays, records holding arrays), `frozen`, Java round trips (`split`, `toCharArray`, `toArray`, `Arrays.sort`, varargs through `.of`).
* `DifferentialTestCase` snippets for the errors, identical in both modes : out-of-bounds, negative length, `ArrayStoreException` through covariance (a `Square` stored into an `array of Circle` seen as `array of Shape` — compiled by the JVM, interpreted by the side table), `Generic array creation`, a primitive array bound to a type variable, `set` on a `frozen array`, `(array of string)(n)`, `(Ints)(10)` (`A type alias is not a value`), `a.length`.
* `TypeCheckerTestCase` goldens : variance (§2.5, including the nullness and exactness conditions, JVM array assignability for `array of (Shape & Named)` / unions, and the Java-parameter waiver), `Unchecked cast`, `BIP008` §11 nullable-array `is`/`as`, diagnostics naming `array of …`, and `RM003` bridges for generic Java array methods ; `FormatterTestCase` goldens for `array of …` types and constructors.

## 7. Implementation Notes (`BIP020-P1`–`P4`, `P6`)

* **`BIP020-P1` (`ClassGen`, `InterpretedInstance`, `BabbleObjects`) :** compiled classes, records and objects extend `java.lang.Object` directly, generating `$babble$classStatic`, `$babble$class`, `$babble$rt`, `$babble$javaDefaultLookup()` and per-class `equals`/`hashCode`/`toString`. `BabbleInstance` is renamed `InterpretedInstance` (interpreter-only), while `BabbleObjects` caches compiled-class reflection metadata via `ClassValue`.
* **`BIP020-P2` (`Babble.g4`, `Type.Array`, `TypeAlgebra`, `TypeAnnotations`) :** `ARRAY` added to `coreCollectionKeyword` (0 predicates, pure `SLL`) ; `Type.JavaArray` renamed `Type.Array` with §2.2 JVM representation (`BIP022` intersections erasing to `Object[]`, transparent `BIP023` aliases) and §2.5 exact-store covariance + Java parameter nullness waiver (`BIP020-D8`, `D12`).
* **`BIP020-P3` (`ArraySupport`, `CallSolver`, `EffectChecker`, `HirBuilder`/`HirEmitter`, `Interpreter`) :** `(array of T)(n)`, expected-type `array(n)` (seeing through `BIP023` aliases, rejecting non-null reference defaults), `.of(…)`, `from:` ; `size()`, `get`, `set` (`null` result), with `.length` removed ; `ArraySupport` content `deepEquals`/`deepHashCode`/`deepToString` (cycle guard `[...]`, `NaN == NaN`, `0.0 != -0.0`) and `WeakHashMap` side table for interpreted Babble-element store checks ; direct `NEWARRAY`/`ANEWARRAY`/`ARRAYLENGTH`/`<X>ALOAD`/`<X>ASTORE` emission.
* **`BIP020-P4` (`TypeAlgebra`, `JavaTypeBridge`, `TypeAnnotations`, `CallSolver`, `ClassGen`, `Faces`) :** reference-only type-variable binding (`BIP020-D13`) enforced across Babble and Java generic functions, classes, interfaces, SAMs, and `of` type applications ; `RM003` bridges for generic Java interface array methods (`([Ljava/lang/Object;)[Ljava/lang/Object;`) preserve overloaded Java `default` methods whose parameter types do not match the Babble implementation (`Collection.toArray(IntFunction)`).
* **`BIP020-P5` (`AxiomData`, `java/lang/curated.ba`, `java/util/curated.ba`, `java/nio/curated.ba`, `java/lang/reflect/curated.ba`) :** landed with `BIP019` — `array of T` erasure (`[I`, `[Ljava/lang/Object;`, etc.) and `fn of T` generic method signatures are supported in axiom declarations (`String.formatted`/`format`, `Arrays.asList`/`copyOf`/`copyOfRange`/`stream`/`toString`, `CharBuffer.wrap`, `Proxy.getProxyClass`/`newProxyInstance`, `Thread.getStackTrace`).
