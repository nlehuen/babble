# Structured Java and Babble-name axioms, descendant contracts, and curated folding/nullability

References : BIP019, BIP030 (structured axioms, exact/notnull/immutable, descendant contracts,
Babble-name axioms, array-parameter overloads, curated foldable/idempotent, `fn ... : T!`, explicit `T!`)

```babble
;; babble module=axmod.ba
export axiom java.lang.StringBuilder: class (
    def append: fn mut nothrow (s: string!): java.lang.StringBuilder!
) static (
    def new: fn pure fresh ()
)
export axiom java.lang.Integer.bitCount: fn pure nothrow (i: int): int
export def countBits = fn nothrow (x: int): int -> (java.lang.Integer.bitCount(x))
```

## Case 1

```babble
def ax = import("axmod.ba")
def jl = import("java.lang")
axiom jl.Integer.highestOneBit: fn pure nothrow (i: int): int
def sb = java.lang.StringBuilder()
sb.append("ok")
assert(ax.countBits(7) == 3)
assert(jl.Integer.highestOneBit(10) == 8)
assert(sb.toString() == "ok")
```

## Case 2

```babble
axiom java.lang.String: class exact notnull immutable (
    def length: fn pure nothrow (): int
    def isEmpty: fn pure nothrow (): bool
) static (
    def valueOf: fn pure nothrow (data: (array of char)!): string!
)
def cs = (array of char).of(char(97), char(98))
def s = java.lang.String.valueOf(cs)
assert(s == "ab")
assert(s.length() == 2)
assert(not s.isEmpty())
```

## Case 3

```babble
axiom java.util.List of E: class (
    def size: fn pure nothrow (): int
    def get: fn (index: int): E
    def add: fn mut (shared e: E): bool
)
axiom java.lang.Object: class (
    def hashCode: fn pure nothrow (): int
)
axiom java.util.Map.Entry of (K, V): class immutable (
    def getKey: fn pure nothrow (): K
    def getValue: fn pure nothrow (): V
)
```

## Case 4

```babble
axiom java.util.Collections: class () static (
    def unmodifiableList: fn of T view (l: (list of T)!): (list of T)!
    def unmodifiableSet: fn of T view (s: (set of T)!): (set of T)!
    def unmodifiableSortedSet: fn of T view (s: (sortedset of T)!): (sortedset of T)!
)
axiom java.lang.Comparable of T: class (
    def compareTo: fn pure (o: T): int
)
```

## Case 5

```babble
axiom java.util.Collections: class () static (
    def unmodifiableMap: fn of (K, V) view (m: map of (K, V)!): map of (K, V)!
    def unmodifiableSortedMap: fn of (K, V) view (m: sortedmap of (K, V)!): sortedmap of (K, V)!
)
axiom java.lang.Runnable: class (
    def run: fn io (): void
)
axiom java.lang.Iterable of T: class (
    def forEach: fn (action: (fn (T): void)!): void
)
```

## Case 6

```babble
axiom java.util.Objects: class () static (
    def isNull: fn pure nothrow (obj: any?): bool
    def nonNull: fn pure nothrow (obj: (any)?): bool
    def equals: fn pure nothrow (a: any | null, b: string | int | null): bool
)
assert(java.util.Objects.isNull(null))
assert(java.util.Objects.nonNull("x"))
```

## Case 7

```babble
axiom java.util.Arrays: class () static (
    def toString: fn pure nothrow (a: (array of int)!): string!
    def deepToString: fn pure nothrow (a: array of ((array of string!)!)!): string!
    def copyOf: fn of T (original: (array of T)!, newLength: int): (array of T)!
)
def xs = (array of int).of(1, 2)
def ys = java.util.Arrays.copyOf(xs, 2)
assert(java.util.Arrays.toString(ys) == "[1, 2]")
```

## Case 8

```babble
axiom java.lang.String.valueOf: fn pure nothrow (b: byte?): string!
axiom java.lang.String.valueOf: fn pure nothrow (s: short?): string!
axiom java.lang.String.valueOf: fn pure nothrow (i: int?): string!
axiom java.lang.String.valueOf: fn pure nothrow (l: long?): string!
```

## Case 9

```babble
axiom java.lang.String.valueOf: fn pure nothrow (f: float?): string!
axiom java.lang.String.valueOf: fn pure nothrow (d: double?): string!
axiom java.lang.String.valueOf: fn pure nothrow (z: bool?): string!
axiom java.lang.String.valueOf: fn pure nothrow (c: char?): string!
```

## Case 10

```babble
axiom java.lang.String.valueOf: fn pure nothrow (b: byte): string!
axiom java.lang.String.valueOf: fn pure nothrow (s: short): string!
axiom java.lang.String.valueOf: fn pure nothrow (l: long): string!
axiom java.lang.String.valueOf: fn pure nothrow (f: float): string!
```

## Case 11

```babble
axiom java.lang.String.valueOf: fn pure nothrow (d: double): string!
axiom java.lang.String.valueOf: fn pure nothrow (z: bool): string!
axiom java.lang.String.valueOf: fn pure nothrow (c: char): string!
```

## Case 12

```babble
axiom java.lang.Math: class () static (
    def abs: fn pure nothrow idempotent foldable (a: int): int
    def min: fn pure nothrow commutative associative foldable (a: int, b: int): int
    def max: fn pure nothrow commutative associative foldable (a: int, b: int): int
)
def work = fn nothrow (x: int): int -> (
    def M = java.lang.Math
    M.min(M.abs(x), M.max(x, 10))
)
assert(work(-4) == 4)
```

## Case 13

```babble
axiom java.lang.Math: class () static (
    def floor: fn pure nothrow idempotent monotone foldable (a: double): double
    def cbrt: fn pure nothrow injective (a: double): double
    def random: fn volatile (): double
)
assert(int(java.lang.Math.floor(3.9)) == 3)
```

## Case 14

```babble
axiom java.util.ArrayList of E: class (
    def clone: fn shallow (): any!
    def subList: fn view (from: int, to: int): (list of E)!
) static (
    def new: fn pure fresh ()
)
def xs: list of int = java.util.ArrayList()
xs.add(1)
assert(xs.subList(0, 1).size() == 1)
```

## Error : Unknown class in axiom : no.such.MissingClass

```babble
axiom no.such.MissingClass: class ()
```

```error
Unknown class in axiom : no.such.MissingClass
```

## Error : Unknown class in axiom : no.such.MissingClass (2)

```babble
axiom no.such.MissingClass.foo: fn pure
```

```error
Unknown class in axiom : no.such.MissingClass
```

## Error : Duplicate axiom class modifier : exact

```babble
axiom java.lang.String: class exact exact ()
```

```error
Duplicate axiom class modifier : exact
```

## Error : Duplicate axiom class modifier : notnull

```babble
axiom java.lang.String: class notnull notnull ()
```

```error
Duplicate axiom class modifier : notnull
```

## Error : Duplicate axiom class modifier : immutable

```babble
axiom java.lang.String: class immutable immutable ()
```

```error
Duplicate axiom class modifier : immutable
```

## Error : Unknown axiom class modifier : bogus

```babble
axiom java.lang.String: class bogus ()
```

```error
Unknown axiom class modifier : bogus
```

## Error : Duplicate type parameter in axiom : K

```babble
axiom java.util.Map of (K, K): class ()
```

```error
Duplicate type parameter in axiom : K
```

## Error : Axiom member must be a function type : inner

```babble
axiom java.lang.String: class (
    def inner: class ()
)
```

```error
Axiom member must be a function type : inner
```

## Error : Constructor axiom new must be declared in static (...) : java.lang.St...

```babble
axiom java.lang.String: class (
    def new: fn pure ()
)
```

```error
Constructor axiom new must be declared in static (...) : java.lang.String
```

## Error : Empty axiom : java.lang.Math.abs

```babble
axiom java.lang.Math.abs: fn
```

```error
Empty axiom : java.lang.Math.abs
```

## Error : Unknown effect modifier : mystery

```babble
axiom java.lang.Math.abs: fn mystery
```

```error
Unknown effect modifier : mystery
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan...

```babble
axiom java.lang.Math.abs: fn io pure
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : pure cannot be combined with mut or volatile in axiom : java.lang.Mat...

```babble
axiom java.lang.Math.abs: fn pure volatile
```

```error
pure cannot be combined with mut or volatile in axiom : java.lang.Math.abs
```

## Error : Parameter cannot be both shared and unique

```babble
axiom java.util.List.add: fn mut (shared unique e: any?): bool
```

```error
Parameter cannot be both shared and unique
```

## Error : Duplicate axiom for class : java.lang.Math

```babble
axiom java.lang.Math: class ()
axiom java.lang.Math: class ()
```

```error
Duplicate axiom for class : java.lang.Math
```

## Error : Duplicate axiom for member : java.lang.Math.abs

```babble
axiom java.lang.Math.abs: fn pure (a: int): int
axiom java.lang.Math.abs: fn pure (a: int): int
```

```error
Duplicate axiom for member : java.lang.Math.abs
```

## Error : Missing type arguments : array

```babble
axiom java.util.Arrays.toString: fn pure (a: array!): string!
```

```error
Missing type arguments : array
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
axiom java.util.Arrays.toString: fn pure (a: array of (int, string!)!): string!
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Unknown type : NoSuchType

```babble
axiom java.util.Arrays.toString: fn pure (a: NoSuchType!): string!
```

```error
Unknown type : NoSuchType
```

## Case 34

```babble
def outer = fn (x: int): int -> (x + 1)
(
    axiom myPure: fn pure nothrow idempotent (int): int
    axiom myBin: fn commutative associative (a: int, b: int): int
    axiom myMono: fn monotone (x: double): double
    axiom myInj: fn injective (x: int): int
    axiom myVol: fn volatile (): int
    def ok = fn nothrow (v: int): int -> (outer(v))
    assert(ok(41) == 42)
)
assert(outer(1) == 2)
```

## Case 35

```babble
(
    axiom myIo: fn io (mut x: list of int, shared y: any?, unique z: any?, opt: string = ...)
    axiom myShared: fn shared (): unique any
    assert(true)
)
```

## Error : Axiom about a Babble name cannot be exported : foo

```babble
export axiom foo: fn (): int
```

```error
Axiom about a Babble name cannot be exported : foo
```

## Error : Axiom about a Babble name requires a signature : foo

```babble
axiom foo: fn pure
```

```error
Axiom about a Babble name requires a signature : foo
```

## Error : Java member word 'foldable' is not valid on a Babble name axiom : foo

```babble
axiom foo: fn foldable (x: int): int
```

```error
Java member word 'foldable' is not valid on a Babble name axiom : foo
```

## Error : Unknown effect word in axiom : unknownWord

```babble
axiom foo: fn unknownWord (x: int): int
```

```error
Unknown effect word in axiom : unknownWord
```

## Case 40

```babble
def Num = record (v: int) implements java.lang.Comparable of Num (
    def compareTo = fn (other: Num): int -> (this.v - other.v)
)
assert(Num(3).compareTo(Num(1)) > 0)
assert(Num(2).compareTo(Num(2)) == 0)
```

## Error : Function compareTo violates pure guarantee of axiom java.lang.Compara...

```babble
def Evil = class (def v: int) implements java.lang.Comparable of Evil (
    def compareTo = fn io (other: Evil): int -> (
        println("side")
        this.v - other.v
    )
)
```

```error
Function compareTo violates pure guarantee of axiom java.lang.Comparable.compareTo
```

## Error : Function compareTo violates pure guarantee of axiom java.lang.Compara... (2)

```babble
def MutEvil = class (def v: int) implements java.lang.Comparable of MutEvil (
    def compareTo = fn (mut other: MutEvil): int -> (
        other.v = 0
        this.v
    )
)
```

```error
Function compareTo violates pure guarantee of axiom java.lang.Comparable.compareTo
```

## Case 43

```babble
def empty: list of string = java.util.Collections.emptyList()
def it: java.util.Iterator of string = java.util.Collections.emptyIterator()
def pair: entry of (string, int) = java.util.Map.entry("k", 7)
assert(empty.size() == 0)
assert(not it.hasNext())
assert(pair.getKey() == "k")
assert(pair.getValue() == 7)
```

## Parameter n prevents -O1 from folding the loop; tests immutable record hoisting & idempotent abs

```babble
def Point = record (x: int, y: int) ()
def sumPair = fn nothrow (p: Point, tag: string, n: int): int -> (p.x + p.y + tag.length() + n)
def run = fn (n: int): int -> (
    def p = Point(3, 4)
    var acc = 0
    var i = 0
    while i < n then (
        acc += sumPair(p, "hi", 1) + java.lang.Math.abs(java.lang.Math.abs(-2))
        i += 1
    )
    acc
)
assert(run(3) == 36)
```

## Parameter n prevents -O1 from folding the loop; exercises HirPurity.allArgsPrimitiveOrImmutable false branch

```babble
def Cell = class (def v: int) ()
def readCell = fn nothrow (c: Cell): int -> (c.v)
def run = fn (n: int): int -> (
    def c = Cell(5)
    var acc = 0
    var i = 0
    while i < n then (
        acc += readCell(c)
        i += 1
    )
    acc
)
assert(run(3) == 15)
```

## Curated foldable (Math.sqrt) and idempotent (String.trim) under -O1

```babble
def f1 = java.lang.Math.sqrt(9.0)
def f2 = "  hi  ".trim()
def f3 = f2.trim()
assert(f1 == 3.0)
assert(f3 == "hi")
```

## Case 47

```babble
axiom java.util.Objects: class () static (
    def toString: fn pure nothrow (o: (byte)?): string!
    def toString: fn pure nothrow (o: (short)?): string!
    def toString: fn pure nothrow (o: (int)?): string!
    def toString: fn pure nothrow (o: (long)?): string!
)
```

## Case 48

```babble
axiom java.util.Objects: class () static (
    def toString: fn pure nothrow (o: (float)?): string!
    def toString: fn pure nothrow (o: (double)?): string!
    def toString: fn pure nothrow (o: (bool)?): string!
    def toString: fn pure nothrow (o: (char)?): string!
)
```

## Case 49

```babble
axiom java.util.Objects: class () static (
    def hashCode: fn pure nothrow (o: java.lang.CharSequence! & java.io.Serializable!): int
    def equals: fn pure nothrow (a: null, b: string! | int): bool
)
assert(not java.util.Objects.equals(null, "x"))
assert(java.util.Objects.hashCode("hi") != 0)
```

## Specificity : wildcard vs signatureless vs signatured on same class, and subclass over superclass

```babble
axiom java.lang.CharSequence: class (
    def *: fn volatile
    def length: fn pure
)
axiom java.lang.String: class (
    def *: fn pure
    def length: fn pure nothrow
    def length: fn pure nothrow (): int
)
def lenPure = fn nothrow (s: string): int -> (s.length())
assert(lenPure("hello") == 5)
```

## Static method with mut modifier applies mut to first parameter

```babble
axiom java.util.Collections.reverse: fn mut (l: (list of any?)!): void
def rev = fn (mut xs: list of int): int -> (
    java.util.Collections.reverse(xs)
    xs.get(0)
)
def l: list of int = java.util.ArrayList()
l.add(1)
l.add(2)
assert(rev(l) == 2)
```

## Error : Duplicate axiom for member : java.lang.Math.abs (2)

```babble
axiom java.lang.Math: class () static (
    def abs: fn pure (a: int): int
    def abs: fn pure nothrow (a: int): int
)
```

```error
Duplicate axiom for member : java.lang.Math.abs
```

## Error : Duplicate axiom for member : java.lang.Math.abs (3)

```babble
axiom java.lang.Math: class () static (
    def abs: fn pure (a: int): int
)
axiom java.lang.Math.abs: fn pure nothrow (a: int): int
```

```error
Duplicate axiom for member : java.lang.Math.abs
```

## Case 54

```babble
def IntCmp = class () implements java.util.Comparator of int (
    def compare = fn (a: int, b: int): int -> (a - b)
)
def Tag = record (id: int) (
    def toString = fn (): string -> ("Tag:" + this.id)
)
assert(IntCmp().compare(5, 2) > 0)
assert(Tag(3) == Tag(3))
assert(Tag(3).toString() == "Tag:3")
```

## Error : Function compare violates pure guarantee of axiom java.util.Comparato...

```babble
def BadCmp = class () implements java.util.Comparator of int (
    def compare = fn io (a: int, b: int): int -> (println(a) ; a - b)
)
```

```error
Function compare violates pure guarantee of axiom java.util.Comparator.compare
```

## Error : Function equals violates pure guarantee of axiom java.lang.Object.equals

```babble
def BadEq = class (def id: int) (
    def equals = fn io (o: any?): bool -> (println("e") ; false)
)
```

```stderr
Warning : equals has no effect on == : use a value class
```

```error
Function equals violates pure guarantee of axiom java.lang.Object.equals
```

## exact class axiom does not apply to subclasses (CharSequence exact vs String)

```babble
axiom java.lang.CharSequence: class exact (
    def length: fn volatile (): int
)
def getLen = fn (s: string): int -> (s.length())
assert(getLen("abcdefg") == 7)
```

## Signatureless static mut axiom maps mut to first parameter (AxiomData.toProfile)

```babble
axiom java.util.Collections.reverse: fn mut
def rev2 = fn (mut xs: list of int): int -> (
    java.util.Collections.reverse(xs)
    xs.get(0)
)
def l: list of int = java.util.ArrayList()
l.add(10)
l.add(20)
assert(rev2(l) == 20)
```

## Case 59

```babble
axiom java.lang.StringBuilder: class notnull (
    def toString: fn pure nothrow (): string!
) static (
    def new: fn pure fresh ()
    def new: fn pure fresh (str: string!)
)
def make = fn (): string -> (
    def a = java.lang.StringBuilder()
    def b = java.lang.StringBuilder("hi")
    a.toString() + b.toString()
)
assert(make() == "hi")
```

## Case 60

```babble
axiom java.awt.Point: class notnull ()
def px = fn (p: java.awt.Point): int -> (p.x)
assert(px(java.awt.Point(4, 9)) == 4)
```

## Specificity : signatured overload beats signatureless on same class, and subclass beats superclass

```babble
axiom java.lang.Object: class (
    def toString: fn volatile
)
axiom java.lang.String: class (
    def indexOf: fn pure
    def indexOf: fn pure nothrow (ch: int): int
    def toString: fn pure nothrow (): string!
)
def probe = fn nothrow (s: string): string -> (
    def i = s.indexOf(97)
    s.toString() + i
)
assert(probe("cat") == "cat1")
```

## Static and instance members of same name do not conflict; instance axiom ignores static call

```babble
axiom java.lang.String: class (
    def valueOf: fn volatile
) static (
    def valueOf: fn pure nothrow (i: int): string!
)
def fmtInt = fn nothrow (x: int): string -> (java.lang.String.valueOf(x))
assert(fmtInt(42) == "42")
```

## Case 63

```babble
axiom java.lang.Enum of E: class ()
axiom java.lang.String of X: class ()
axiom java.lang.StringBuilder: class (
    def toString: fn pure nothrow (): unique string!
) static (
    def new: fn pure unique ()
)
(
    axiom int: fn (x: any?): int
    def sb = java.lang.StringBuilder()
    assert(sb.toString().length() == 0)
    assert(int("7") == 7)
)
```

## Case 64

```babble
axiom java.util.Collections.reverse: fn (mut l: (list of any?)!): void
axiom java.util.List.add: fn mut (unique e: any?): bool
axiom java.util.Arrays: class () static (
    def toString: fn pure (a: (array of null)?): string!
    def equals: fn pure (a: (array of int)!, b: (array of int)?): bool
)
def a = (array of int).of(1, 2)
assert(java.util.Arrays.equals(a, a))
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (2)

```babble
axiom java.lang.Math.abs: fn io volatile
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (3)

```babble
axiom java.lang.Math.abs: fn io mut
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (4)

```babble
axiom java.lang.Math.abs: fn io shared
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (5)

```babble
axiom java.lang.Math.abs: fn io nothrow
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (6)

```babble
axiom java.lang.Math.abs: fn io idempotent
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (7)

```babble
axiom java.lang.Math.abs: fn io view
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (8)

```babble
axiom java.lang.Math.abs: fn io shallow
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (9)

```babble
axiom java.lang.Math.abs: fn io fresh
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : io cannot be combined with other effect modifiers in axiom : java.lan... (10)

```babble
axiom java.lang.Math.abs: fn io foldable
```

```error
io cannot be combined with other effect modifiers in axiom : java.lang.Math.abs
```

## Error : pure cannot be combined with mut or volatile in axiom : java.lang.Mat... (2)

```babble
axiom java.lang.Math.abs: fn pure mut
```

```error
pure cannot be combined with mut or volatile in axiom : java.lang.Math.abs
```

## Descendant contract specificity : class axiom (Object) vs interface axiom (CharSequence)

```babble
def jl = import("java.lang")
axiom jl.Object.toString: fn pure
axiom jl.CharSequence.toString: fn volatile
def S = class (def s: string) implements jl.CharSequence (
  def length = fn (): int -> (0)
  def charAt = fn (i: int): char -> (char(0))
  def subSequence = fn (a: int, b: int): jl.CharSequence -> (this)
  def toString = fn (): string -> (this.s)
)
assert(S("ok").toString() == "ok")
```

## Error : Function compareTo violates deterministic guarantee of axiom java.lan...

```babble
var ext = 0 ; ext = 1
def VolCmp = class (def v: int) implements java.lang.Comparable of VolCmp (
  def compareTo = fn volatile (other: VolCmp): int -> (this.v - other.v + ext)
)
```

```error
Function compareTo violates deterministic guarantee of axiom java.lang.Comparable.compareTo
```

## Error : Function compareTo violates borrow guarantee of axiom java.lang.Compa...

```babble
def box = (list of any)()
def SharedCmp = class (def v: int) implements java.lang.Comparable of SharedCmp (
  def compareTo = fn (shared other: SharedCmp): int -> (this.v - other.v)
)
```

```error
Function compareTo violates borrow guarantee of axiom java.lang.Comparable.compareTo
```

## Error : Function applyAsInt violates nothrow guarantee of axiom java.util.fun...

```babble
axiom java.util.function.IntUnaryOperator.applyAsInt: fn pure nothrow (x: int): int
def BadOp = class () implements java.util.function.IntUnaryOperator (
  def applyAsInt = fn (x: int): int -> (assert(x >= 0) ; x)
)
```

```error
Function applyAsInt violates nothrow guarantee of axiom java.util.function.IntUnaryOperator.applyAsInt
```

## Error : Function applyAsInt violates idempotent guarantee of axiom java.util....

```babble
axiom java.util.function.IntUnaryOperator.applyAsInt: fn pure idempotent (x: int): int
def BadIdem = class () implements java.util.function.IntUnaryOperator (
  def applyAsInt = fn (x: int): int -> (x + 1)
)
```

```error
Function applyAsInt violates idempotent guarantee of axiom java.util.function.IntUnaryOperator.applyAsInt
```

## Error : Function get violates unique guarantee of axiom java.util.function.Su...

```babble
axiom java.util.function.Supplier.get: fn pure (): unique any?
def BadSup = class () implements java.util.function.Supplier of any (
  def get = fn (): any -> (this)
)
```

```error
Function get violates unique guarantee of axiom java.util.function.Supplier.get
```
