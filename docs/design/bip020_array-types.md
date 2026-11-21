# BIP020: Array types

| | |
|---|---|
| **BIP** | 020 |
| **Date** | 2026-11-21 |
| **Status** | Decided (`BIP020-D1`–`D10` and `BIP020-Q1`–`Q6` → `BIP020-D11`–`D16` decided, plus `BIP020-D17`–`D18` ; `BIP020-P1`–`P6` not started) |
| **Depends on** | static typing and Java arrays ([bip008_static-typing.md](bip008_static-typing.md) §5.1), core types, nullness and variance ([bip011_core-types.md](bip011_core-types.md)), `of` generics ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops over arrays and static factories ([bip013_iterators.md](bip013_iterators.md)), pure-by-default effects ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md)), axioms ([bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)) |
| **Supersedes** | the field-free `abstract class BabbleInstance` of `BIP018-D8` ([bip018_tracking-java-improvements.md](bip018_tracking-java-improvements.md)) : compiled classes extend no runtime class at all (§2.10) |
| **Scope** | A type for JVM arrays, `array of T`, that programs and axioms can write ; creating arrays (`(array of int)(100)`, `(array of int).of(1, 2, 3)`, `from:`) ; their elements, size, equality and printing ; their representation, nullness, variance and effects ; the type parameters of generic members (`def toArray of T = fn …`), in classes, interfaces and axioms ; and compiled Babble classes as plain JVM classes, the runtime base class becoming the interpreter's alone |

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
* **`array of T` replaces `JavaArray of T`** wherever types are shown (`:type`, `TypeDisplay`) and in diagnostics, which show `string[]` or `java.lang.String[]` today : `Type error : expected int, got array of string`, `No such name in array of string : nope`. `Type.JavaArray` becomes `Type.Array`, since arrays are no longer only Java's.

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
| `any`, `any?`, unions, function types, type variables | `Object[]` |

* **Element nullness is erased**, except for the primitives : `array of string` and `array of string?` are both `String[]`, but `array of int` (`int[]`) and `array of int?` (`Integer[]`) are different objects — the one place where `int` and `int?` differ in representation inside a container (`list of int` and `list of int?` are both a `List` of `Integer`).
* **Babble-defined element types differ between the modes** : compiled mode has a JVM class (or interface) per Babble class (or interface) and uses it, so `get` needs no cast and the JVM checks stores exactly ; the interpreter has one instance class for every Babble object, and keeps each such array's element class in a weak side table of `ArraySupport`, so that `set` checks stores as the JVM does in compiled mode and throws the same `ArrayStoreException` (§2.5). A copy made by Java (`java.util.Arrays.copyOf`) is not in the table, and its stores are checked only by the JVM in the interpreter.

### 2.3 Creating arrays (`BIP020-D2`, `D3`, `D6`, `D11`)

| Form | Result | JVM |
|---|---|---|
| `(array of int)(100)` | 100 elements, each the element type's default | `new int[100]` |
| `array(100)`, with an expected type (`def a: array of int = array(100)`) | the same, `T` from the expected type, as `list()` (BIP011 §5.2) | |
| `(array of int).of(1, 2, 3)` | the given elements | `new int[] {1, 2, 3}` |
| `array.of(1, 2, 3)` | the same, `T` inferred from the elements (`array of int`) | |
| `(array of int)(from: xs)` | a copy of an iterable : a core collection, any `java.lang.Iterable of T`, another `array of T` | |

* **`(array of T)(n)`** (`BIP020-D2`) is a length, as in Java's `new int[n]` ; a negative length throws `NegativeArraySizeException`. The default element is `0`, `0.0`, `false` or `'\0'` for the primitives and `null` for nullable types.
* **A non-null reference element type has no default** (`BIP020-Q1` → `BIP020-D11`) : `(array of string)(10)` would hold ten `null`s typed `string`, so it is rejected (`No default element : array of string has non-null elements`). There is no `fill:` nor `init:` argument : a program writes `(array of string?)(10)`, gives the elements with `.of(…)`, or builds a collection and copies it with `from:` — `(array of Point)(from: points)` is the way to a non-null array of computed elements.
* **`.of(…)`** (`BIP020-D3`) gives the elements, like `(list of int).of(…)` (BIP013 §3.2). Unlike `list.of`, whose list is unmodifiable, the array is an ordinary, mutable one : the JVM has no read-only arrays (`frozen`, §2.4, is the static way to get one).
* **`list(100)` and `array(100)` differ** — a one-element list, a hundred zeros. The length form follows Java, where arrays have a fixed length and no capacity ; `.of(…)` is the element form for both.
* **Element types must be reifiable** (`BIP020-D6`) : an array is created with its class, which a type variable doesn't give at run time (generics are erased). `(array of T)(n)`, `(array of T).of(x)` and `(array of T)(from: xs)` are rejected (`Generic array creation : array of T`), as are element types whose class depends on a type variable (`T?`, `array of T`, a union with a type variable) ; `array of (list of T)` is fine (`List[]`). Generic code that needs storage uses a `list of T`, or receives its array from a caller who knows the type.

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
* **Effects** (BIP016 vocabulary) :

  | Operation | Effects |
  |---|---|
  | `size()` | `pure nothrow` — an array's size never changes, so it is not a read of mutable state |
  | `get`, `==`, `equals`, `hashCode`, `toString` | pure, borrow the array(s) ; `get` may throw |
  | `set` | `mut` on `a`, `shared v`, shallow (touches no element) ; may throw |
  | constructors, `.of`, `from:` | pure, fresh `unique` result (so `freeze((array of int).of(1, 2, 3))` is a `frozen array of int`, on which `set` is rejected) |

### 2.5 Subtyping and variance (`BIP020-D8`, `D12`)

Arrays follow Java's covariance for reference elements, restricted to where the JVM's store check is exact (`BIP020-D8`) :

* **Primitive arrays are invariant** : `array of int` relates to no other array (`int[]` is none of `long[]`, `Integer[]`, `Object[]`).
* **Reference arrays are covariant** — `array of string` is an `array of any`, `array of Circle` an `array of Shape` (Circle implementing Shape) — on two conditions, which make a bad store fail with `ArrayStoreException` instead of polluting the array :
  * **same element nullness :** `array of string` is not an `array of string?` (nor of `any?`) — both are `String[]`, and storing `null` would succeed ;
  * **an exact source class :** the source's element type must have its own JVM class, so not one erased to `Object[]` (function types, unions, `any`) — storing anything would succeed. (`array of int?` is an `array of any` : `Integer[]` is exact.)
* **Where the array can't be written**, both conditions are lifted : at a non-`mut` borrowed parameter (the default for typed parameters since BIP016), and for `frozen array of T` (BIP007-D7), as BIP011-D8 does for collections — `array of string` passes for a borrowed `array of string?`.
* **Java parameters** (`BIP020-Q2` → `BIP020-D12`) : unannotated Java array parameters have nullable elements (`Arrays.toString(Object[])` takes `(array of any?)?`), so at a Java parameter the nullness condition is waived, as BIP011 already trusts Java with a `list of string` where Java sees `List<String>` — only the JVM-class part applies, and `java.util.Arrays.toString(parts)` compiles. Once an axiom (BIP019) or the bytecode analysis says Java writes the parameter (`mut`), the nullness condition applies there again.

### 2.6 Type variables (`BIP020-D6`, `D13`)

`array of T`, for a type variable `T`, is an `Object[]` to generic code (§2.2). A type variable therefore binds only elements stored as references (`BIP020-Q3` → `BIP020-D13`), as in Java, where a type variable is never a primitive ; generic code compiles to `AALOAD`/`AASTORE` :

```babble
def first of T = fn (a: array of T): T -> (a.get(0))
first(parts)                ;; T = string
first("abc".toCharArray())  ;; Type error : expected array of T, got array of char
```

The same holds for a Java method's type variables (`<T> T[] toArray(T[] a)` : `xs.toArray((array of int)(0))` is rejected, `xs.toArray((array of int?)(0))` gives an `array of int?`). Generic code over primitives uses `list of T`. Creation is §2.3's.

### 2.7 Java interoperability (`BIP020-D9`, `D15`)

* **From Java**, an array `E[]` maps to `array of E'` with BIP011's nullness (§6.2 there) : an unannotated `String[]` is `(array of string?)?`, a null-marked one `array of string`, `"a,b".split(",")` (overlay) `array of string`.
* **To Java**, an `array of T` passes as itself where its class fits the parameter (§2.5).
* **Varargs** (`BIP020-Q5` → `BIP020-D15`) are still matched on their array parameter and never expanded ; `.of` is the explicit spelling : `java.util.Arrays.asList(array.of("only"))`, `java.lang.String.format("%s=%d", (array of any).of("x", 1))`. Expansion, if wanted, is its own change.
* **Stores into arrays of Java interface types** convert a Babble object as passing it to a Java parameter does (`set` on an `array of java.lang.Runnable` stores the object's Java face in the interpreter, the object itself in compiled mode).
* **`is` and `as`** check the array's class (`INSTANCEOF [I`, `[Ljava/lang/String;`). Where the class doesn't tell the whole type, they warn `Unchecked cast`, as for `is list of string` : element nullness (`array of string` against `array of string?`), elements erased to `Object[]`, and **Babble-defined elements, checked as `Object[]` in both modes** — compiled mode deliberately doesn't use the stricter `INSTANCEOF [LPoint;`, which the interpreter couldn't match.

### 2.8 The type parameters of generic members (`BIP020-D4`, `D10`)

A member declares its own type parameters as any `def` does (`BIP012-D3`) : with `of` after its name, whether it has a value or only a type (`BIP020-D4`).

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

A call gives the type arguments by inference or explicitly, as for a function value (`(c.conv of string)("a")` works today).

**Conformance** (`BIP020-D10`) : an implementation or an override of a generic member, or a Babble class's method against an inherited axiom's contract (BIP019), must declare the same number of type parameters ; its type is compared with the declared one after renaming its type parameters to the declared ones, by position, then as any function type (parameters contravariant, result covariant, effects). Any mismatch in number — a non-generic implementation of a generic member, or the reverse — is reported by name : `Type parameters of conv : expected 1, got 0`. This fixes §1's example.

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

### 2.10 Compiled classes without a runtime base class (`BIP020-D18`)

Today every compiled Babble class extends `org.babblelang.runtime.BabbleInstance`, the interpreter's instance class, which gives the runtime a generic view of any Babble object : its class (`babbleClass()`), its members (`HasMembers`), its runtime, its Java face, and the equality, hash and string of records. `BIP018-D8` planned to make it a field-free abstract class. Instead, **no compiled class extends a runtime class** : compiled classes, records and objects are plain JVM classes, and compiled Babble interfaces plain JVM interfaces. The base class becomes the interpreter's alone (renamed `InterpretedInstance`), which nothing compiled extends.

Strict typing already makes most of the generic view unnecessary — member access on `any` is rejected — and the rest moves into each class :

* **`is` / `as` on a Babble type** compile to `INSTANCEOF` / `CHECKCAST` ;
* **equality, hash and string** of records, and `toString` of classes, are generated per class ;
* **the Java face** (a compiled class implements its Java interfaces directly, `RM003`) and the lookup for Java default methods are generated per class ;
* **the class value** (`classof`, diagnostics) comes from a static field of the class ;
* **what still goes through the generic view** — `Bootstrap`'s select caches, `Values.is`, `Types.check`, `Classes.*`, `Faces` — is rewritten against plain classes, or removed where static types make it dead.

It comes first (`BIP020-P1`), so that the array code (`Point[]`, §2.2) is written once, against plain compiled classes. It also clears the way for Valhalla `value` records (BIP018), which cannot extend an identity class.

---

## 3. Alternatives Considered

* **`int[]`** : brackets break Babble's single-bracket design (BIP012), and `[` is not a token.
* **`JavaArray of T`**, the name shown today : long, and arrays are a core type, not a Java curiosity.
* **`array(1, 2, 3)` for elements**, as `list(1, 2, 3)` : the length would then need a name (`array(length: 100)`). Java's meaning of a length was preferred, with `.of(…)` for elements.
* **A wrapper class** (a Babble `Array` holding a Java array) : a copy, or an unwrap, at every Java call, against BIP011's zero-wrapper rule.
* **Every Babble-defined element type as `Object[]`** in both modes : simpler and mode-independent, but every `get` needs a cast and the JVM can't check stores ; compiled `Point[]` was preferred, with a side table for the interpreter.
* **A common supertype of Babble objects** (`BabbleInstance`, or a marker interface `BabbleObject`) so that `is array of Point` checks something stricter than `Object[]` : rejected with §2.10.
* **`fill:` and `init:` arguments** for non-null elements : `fill:` puts the same object in every slot (the classic trap with `(array of (array of double))(n, fill: row)`), and `from:` already covers computed elements.
* **Strict invariance, as for collections (BIP011-D8)** : sound without runtime checks, but every `array of string` given to `array of any` would need a borrowed parameter ; Java's covariance was kept where the JVM checks it exactly.
* **Type variables over primitive arrays**, through `ArraySupport` with boxing : `first(chars)` would work, but generic arrays could then never reach a Java array parameter, and every access would box.
* **Type parameters on the function type** (`def toArray: fn of T (a: array of T): array of T`) : `def … of T` is how a `def` declares them already ; a function type with its own type parameters (a parameter that must itself be generic) is a separate feature.

---

## 4. Decisions (`BIP020-D1`–`D18`)

* **`BIP020-D1` — The type :** `array of T`, `array` a core type keyword (§2.1).
* **`BIP020-D2` — Length constructor :** `(array of T)(n)`, or `array(n)` with an expected type, makes an array of `n` default elements (§2.3).
* **`BIP020-D3` — Elements :** `(array of T).of(…)`, or `array.of(…)` with `T` inferred, makes a mutable array of the given elements (§2.3).
* **`BIP020-D4` — Generic members :** a member declares its own type parameters with `of` after its name, `def toArray of T = fn …`, `def toArray of T: fn …` without a value, in classes, interfaces and axioms alike (§2.8).
* **`BIP020-D5` — Representation :** plain JVM arrays of the element's JVM class ; Babble-defined elements use their compiled class or interface in compiled mode, and the interpreter's instance class plus a side table of element classes when interpreted (§2.2).
* **`BIP020-D6` — Reifiable creation :** no array is created of an element type whose class depends on a type variable (§2.3).
* **`BIP020-D7` — Members and effects :** `size()`, `get`, `set` and `for`, with the effects of §2.4.
* **`BIP020-D8` — Variance :** primitive arrays invariant ; reference arrays covariant where the JVM's store check is exact (same element nullness, an exact source class), and fully covariant where unwritable (§2.5).
* **`BIP020-D9` — Java and casts :** BIP011's nullness ; `is`/`as` by class, Babble-defined elements checked as `Object[]` in both modes, with `Unchecked cast` warnings (§2.7).
* **`BIP020-D10` — Conformance of generic members :** same number of type parameters, compared after renaming ; mismatches reported by name (§2.8).
* **`BIP020-Q1` → `BIP020-D11` — No default for non-null elements :** `(array of string)(n)` is rejected ; no `fill:` nor `init:` ; `from:` copies an iterable (§2.3).
* **`BIP020-Q2` → `BIP020-D12` — Java parameters :** the nullness condition of §2.5 is waived at Java parameters, unless an axiom or the analysis says Java writes them (§2.5).
* **`BIP020-Q3` → `BIP020-D13` — Type variables :** bind only elements stored as references (§2.6).
* **`BIP020-Q4` → `BIP020-D14` — Printing :** `[a, b]` in `print`, `println`, string `+`, the interactive mode and Babble calls of `toString()` (§2.4).
* **`BIP020-Q5` → `BIP020-D15` — Varargs :** not expanded ; `.of(…)` is the spelling (§2.7).
* **`BIP020-Q6` → `BIP020-D16` — Element access now :** `get`/`set` ship with the type ; `size()` replaces `length` (§2.4).
* **`BIP020-D17` — Equality by contents :** `==`, and Babble calls of `equals` and `hashCode`, compare arrays by contents ; Java still sees identity (§2.4).
* **`BIP020-D18` — No runtime base class for compiled classes :** compiled classes, records, objects and interfaces are plain JVM types ; the base class is the interpreter's alone, superseding `BIP018-D8`'s abstract class (§2.10).

---

## 5. Phases (`BIP020-P1`–`P6`)

| Phase | Deliverables |
|---|---|
| **`BIP020-P1` — Plain compiled classes** | §2.10 : `ClassGen` emits classes extending `Object` (records' equality, hash and string, `toString`, Java default lookups and the class value generated per class) ; `is`/`as` by `INSTANCEOF`/`CHECKCAST` ; `Bootstrap`, `Values`, `Types`, `Classes` and `Faces` rewritten without `instanceof BabbleInstance` on compiled objects ; `BabbleInstance` renamed `InterpretedInstance`, interpreter-only ; `RuntimeBoundaryTestCase` and argon unchanged. |
| **`BIP020-P2` — The type** | `ARRAY` in `Babble.g4` (`coreCollectionKeyword`) ; `array of T` in `TypeAnnotations` ; `Type.JavaArray` → `Type.Array` with the representation of §2.2 ; variance (§2.5) in `TypeAlgebra` ; `is`/`as` and `Unchecked cast` ; `TypeDisplay` and diagnostics ; the formatter (spacing as for `list of T`) ; `ParserGrammarTestCase` stays predicate-free. |
| **`BIP020-P3` — Creating and using** | `array` as a `CoreConstructor` with `(n)`, `from:` and `.of(…)` ; `size()`, `get`, `set`, with `length` removed and its uses migrated ; `ArraySupport` in the runtime, with the interpreter's side table of element classes ; both backends (`Interpreter`, `HirBuilder`/`HirEmitter` : `NEWARRAY`, `ANEWARRAY`, `ARRAYLENGTH`, `<X>ALOAD`, `<X>ASTORE`) ; equality, hash and printing by contents ; effects (§2.4). |
| **`BIP020-P4` — Generics** | the reifiability and reference-binding rules (§2.3, §2.6) in `TypeChecker` and `CallSolver`, for Babble and Java type variables ; conformance of generic members (§2.8) in `TypeAlgebra.functionRelates` ; `RM003` bridges for generic Java methods. |
| **`BIP020-P5` — Axioms** | once BIP019's Babble form lands : array parameters in its erasure mapping ; the overloads left to effects-only axioms or to inference get their signatures ; the `mut` exception of `BIP020-D12` reads them. |
| **`BIP020-P6` — Docs and migration** | `java.lang.reflect.Array` in tests and examples replaced where it stood for missing syntax ; README tour and checklists, AGENTS.md (architecture : compiled classes no longer extend `BabbleInstance`), BIP008 §5.1, BIP011 §6, BIP013 §4.2 and BIP018-D8 speak of `array of T` and plain compiled classes. |

## 6. Verification

* The suite in its three runs (interpreter, compiled `-O1`, compiled `-O0`) after each phase, `-Pbatest`, `-format -check`, and argon rendering `scene.ppm` byte for byte — after `BIP020-P1` especially, which changes every compiled class without changing behavior.
* A `src/test/babble/Arrays.ba` script (both modes, type-checked, format-checked) : every element type of §2.2's table, creation, `size`/`get`/`set`, `for`, `==` and printing (nested arrays, records holding arrays), `frozen`, Java round trips (`split`, `toCharArray`, `toArray`, `Arrays.sort`, varargs through `.of`).
* `DifferentialTestCase` snippets for the errors, identical in both modes : out-of-bounds, negative length, `ArrayStoreException` through covariance (a `Square` stored into an `array of Circle` seen as `array of Shape` — compiled by the JVM, interpreted by the side table), `Generic array creation`, a primitive array bound to a type variable, `set` on a `frozen array`, `(array of string)(n)`, `a.length`.
* `TypeCheckerTestCase` goldens : variance (§2.5, including the nullness and exactness conditions and the Java-parameter waiver), `Unchecked cast`, diagnostics naming `array of …`, generic member conformance (§1's example compiles, a non-generic implementation is reported) ; `FormatterTestCase` goldens for `array of …` types and constructors.
