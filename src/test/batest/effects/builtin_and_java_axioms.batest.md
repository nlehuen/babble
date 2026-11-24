# Builtin and Java axioms : annotations, SAM effects, hierarchy specificity, and modal kinds

References : `BIP007` (effects & Java axioms), `BIP027` (`JavaEffectResolver`, `AxiomData`, `BuiltinAxioms`, `EffectModifiers`, `ModalKindTable`).

```babble
;; babble module=mod_tostring.ba
export def ExtTag = class (def label: string) (
    def toString = fn nothrow (): string -> ( this.label )
)
```

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def HF = tests.EffectsTestCase.HostFixtures
def APC = tests.EffectsTestCase.AnnotatedPureClass
def MH = tests.EffectsTestCase.MutableHolder
def MHT = type tests.EffectsTestCase.MutableHolder
def UH = tests.EffectsTestCase.UnassumedHelper
def UF = tests.OwnershipTestCase.UniqueFactory
def Box = class (def v: int) ()
def Holder = class (def x: any?) ()
def Node = record (v: int, next: Node?) ()
```

## Volatile Java annotation allowed in volatile nothrow function

```babble
def readVol = fn volatile nothrow (x: int): int -> ( HF.volatileRead(x) )
assert(readVol(5) == readVol(5))
```

## Volatile Java annotation rejected in pure function

```babble
def badVol = fn (x: int): int -> ( HF.volatileRead(x) )
```

```error
Function badVol violates deterministic guarantee : mark function as volatile (or io)
```

## Shared Java parameter annotation accepts shared parameter

```babble
def keepShared = fn nothrow (shared h: MHT): MHT -> ( HF.echoShared(h) )
assert(keepShared(MH(7)).value == 7)
```

## Shared Java parameter annotation rejects borrowed parameter

```babble
def badBorrow = fn (h: MHT): MHT -> ( HF.echoShared(h) )
```

```error
# error regex
Borrowed parameter h escapes via .* : mark parameter as shared h
```

## Unique Java method annotation returns unique freezable list

```babble
def makeFrozen = fn (): frozen java.util.List of int? -> ( freeze(UF.freshList()) )
def other = UF.unannotatedList()
assert(makeFrozen().size() == 0 and other.size() == 0)
```

## Class-level Pure annotation applies to default constructor and bytecode analysis handles unassumed helper

```babble
def useFixtures = fn (): int -> (
    def a = APC()
    def u = UH()
    assert(a != null and u != null)
    UH.compute(4)
)
assert(useFixtures() == 9)
```

## Java SAM axiom with mut parameter enables mutating Consumer lambda

```babble
axiom java.util.function.Consumer.accept: fn (mut x: any!): void
def c: java.util.function.Consumer of Box = fn (mut b: Box) -> ( b.v += 1 )
def b = Box(1)
c.accept(b)
assert(b.v == 2)
```

## Java SAM axiom with unique parameter enables consuming Consumer lambda

```babble
axiom java.util.function.Consumer.accept: fn (unique x: any!): void
def c: java.util.function.Consumer of Box = fn (unique b: Box) -> ( discard(freeze(move(b))) )
c.accept(Box(3))
assert(true)
```

## Java SAM axioms with shared and pure parameters plus unconstrained Runnable

```babble
axiom java.util.function.Consumer.accept: fn (shared x: any!): void
def c1: java.util.function.Consumer of Box = fn (shared b: Box) -> ( discard(b) )
def r: java.lang.Runnable = fn () -> ( discard(1) )
c1.accept(Box(1)) ; r.run()
assert(true)
```

## Java SAM axiom with pure borrow parameter checks borrowed Consumer lambda

```babble
axiom java.util.function.Consumer.accept: fn pure (x: any!): void
def c: java.util.function.Consumer of Box = fn (b: Box) -> ( assert(b.v >= 0) )
c.accept(Box(2))
```

## Java comparison profiles for concrete Comparable and erased Comparable

```babble
def cmpConcrete = fn (a: java.math.BigInteger, b: java.math.BigInteger): bool -> ( a < b )
def cmpErased = fn (a: java.lang.Comparable of java.math.BigInteger, b: java.lang.Comparable of java.math.BigInteger): bool -> ( a < b )
assert(cmpConcrete(java.math.BigInteger.ONE, java.math.BigInteger.TWO))
assert(cmpErased(java.math.BigInteger.ONE, java.math.BigInteger.TWO))
```

## toString profiles for recursive records, imported module class, and non-function toString field

```babble
def m = import("mod_tostring.ba")
def FieldTs = class (def toString: string) ()
def fmtAll = fn volatile (n: Node, e: m.ExtTag, f: FieldTs): string -> ( "" + n + "|" + e + "|" + f )
def s = fmtAll(Node(1, Node(2, null)), m.ExtTag("hi"), FieldTs("x"))
assert(s.contains("hi") and s.contains("Node"))
```

## Collection factory methods from java.util.Collections

```babble
def C = java.util.Collections
def checkAll = fn io (): int -> (
    C.emptyList of int ().size() + C.emptySet of int ().size() + C.emptyMap of (string, int) ().size() +
    C.singletonList(1).size() + C.singleton(1).size() + C.singletonMap("a", 1).size() +
    C.nCopies(2, 1).size()
)
assert(checkAll() == 5)
```

## Class, JavaClass, and function equality profiles in pure function

```babble
def checkEq = fn (): bool -> (
    def id = fn (x: int): int -> ( x )
    (Box == Box) and (java.lang.String == java.lang.String) and (id == id)
)
assert(checkEq())
```

## Local immutable class axiom allows storing borrowed Java parameter

```babble
axiom java.util.Locale: class immutable ()
def keep = fn (mut h: Holder, loc: java.util.Locale) -> ( h.x = loc )
def h = Holder(null)
keep(h, java.util.Locale.ROOT)
assert(h.x != null)
```

## Varargs Java constructor nullness without local constructor axiom

```babble
def pb = java.lang.ProcessBuilder("a", "b")
assert(pb.command().size() == 2)
```

## Varargs Java constructor nullness with local constructor axiom

```babble
axiom java.lang.ProcessBuilder.new: fn (command: (array of string!)!)
def pb = java.lang.ProcessBuilder("a", "b")
assert(pb.command().size() == 2)
```

## Axiom class path resolution via selector alias, nested class, and importe

```babble
def M = java.lang.Math
axiom M.abs: fn pure nothrow (a: int): int
def MapCls = java.util.Map
axiom MapCls.Entry: class immutable ()
def jl = importe("java.lang")
axiom jl.Math.min: fn pure nothrow (a: int, b: int): int
def f = fn nothrow (): int -> ( java.lang.Math.abs(-3) + java.lang.Math.min(2, 5) )
assert(f() == 5)
```

## Axiom class path resolution rejects non-selector expression

```babble
def n = 1 + 2
axiom n.foo: fn pure
```

```error
Unknown class in axiom : n
```

## Axiom class path resolution rejects selector on non-selector receiver

```babble
def s = Box(1).v
axiom s.foo: fn pure
```

```error
Unknown class in axiom : s
```

## Axiom class path resolution rejects Babble module import

```babble
def bm = import("mod_tostring.ba")
axiom bm.ExtTag.toString: fn pure
```

```error
Unknown class in axiom : bm.ExtTag
```

## Axiom class path resolution rejects non-import call binding

```babble
def g = java.lang.String.valueOf(1)
axiom g.foo: fn pure
```

```error
Unknown class in axiom : g
```

## Axiom hierarchy specificity : concrete class beats unrelated interface

```babble
axiom java.util.AbstractCollection.size: fn pure
axiom java.util.List.size: fn io
def f = fn (): int -> ( java.util.ArrayList of int ().size() )
assert(f() == 0)
```

## Axiom hierarchy specificity : unrelated interfaces resolved by intra-class rank

```babble
axiom java.io.Serializable: class ( def *: fn io )
axiom java.lang.CharSequence: class ( def length: fn pure )
def f = fn (s: string): int -> ( s.length() )
assert(f("abc") == 3)
```

## Axiom hierarchy specificity : equal-rank unrelated interfaces preserve deterministic winner

```babble
axiom java.lang.Comparable: class ( def *: fn io )
axiom java.lang.CharSequence: class ( def *: fn pure )
def f = fn io (s: string): int -> ( s.length() )
assert(f("ab") == 2)
```

## Axiom hierarchy specificity : subtype interface beats supertype interface

```babble
axiom java.util.List.size: fn pure
axiom java.util.Collection.size: fn io
def f = fn (): int -> ( java.util.ArrayList of int ().size() )
assert(f() == 0)
```

## Static zero-arg mut, volatile mut, fresh axioms and multi-rank constructor axioms

```babble
axiom java.lang.System.nanoTime: fn mut
axiom java.lang.System.currentTimeMillis: fn volatile mut
axiom java.lang.String.valueOf: fn mut fresh (i: int): string!
axiom java.lang.StringBuilder: class () static (
    def *: fn io
    def new: fn pure
    def new: fn pure nothrow (s: string!)
)
def sb = java.lang.StringBuilder("ok")
assert(sb.toString() == "ok" and java.lang.String.valueOf(3) == "3")
```

## Axiom type erasure for entry, exception, and nullable or non-nullable unions

```babble
axiom java.util.Objects.nonNull: fn pure (e: (entry of (string!, int))!): bool
axiom java.lang.Throwable.initCause: fn mut (e: exception!): java.lang.Throwable!
axiom java.lang.String.valueOf: fn pure (o: int | string?): string!
axiom java.lang.String.copyValueOf: fn pure (o: int | string!): string!
assert(java.util.Objects.nonNull(entry("a", 1)))
```

## Axiom type erasure for Enum parameterized bound, extra type var, and unique param

```babble
axiom java.lang.Enum of E: class ( def compareTo: fn pure (o: E): int )
axiom java.lang.String of X: class ()
axiom java.util.List.add: fn mut (unique e: any!): bool
assert(true)
```

## Axiom member conflict rules allow distinct signatures and reject duplicate instance/any

```babble
axiom java.lang.Math.abs: fn pure nothrow (a: int): int
axiom java.lang.Math.abs: fn pure nothrow (a: double): double
assert(java.lang.Math.abs(-2) == 2 and java.lang.Math.abs(-1.5) == 1.5)
```

## Axiom member conflict rejects instance class axiom followed by standalone any axiom

```babble
axiom java.lang.String: class ( def length: fn pure )
axiom java.lang.String.length: fn pure
```

```error
Duplicate axiom for member : java.lang.String.length
```

## Axiom member conflict rejects standalone any axiom followed by instance class axiom

```babble
axiom java.lang.String.length: fn pure
axiom java.lang.String: class ( def length: fn pure )
```

```error
Duplicate axiom for member : java.lang.String.length
```

## Axiom member conflict rejects duplicate member inside single class axiom

```babble
axiom java.lang.Math: class () static (
    def abs: fn pure (a: int): int
    def abs: fn pure (a: int): int
)
```

```error
Duplicate axiom for member : java.lang.Math.abs
```

## Babble-name axioms support mut receiver, mut param, unique word, nullable rest, and io shared

```babble
def run = fn (): int -> (
    axiom a: fn mut (x: int): int
    axiom b: fn (mut x: list of int): int
    axiom c: fn unique (): any
    axiom d: fn (xs: int?...): int
    axiom e: fn io shared (): int
    1
)
assert(run() == 1)
```

## ModalKindTable rejects escaping Map.Entry parameter

```babble
def keep = fn (mut h: Holder, e: java.util.Map.Entry of (any?, any?)) -> ( h.x = e )
```

```error
Borrowed parameter e escapes via field .x : mark parameter as shared e
```

## ModalKindTable allows record with method and mutually recursive immutable records to cross

```babble
def RecM = record (v: int) ( def get = fn (): int -> ( this.v ) )
def RecA = record (v: int, b: RecB?) ()
def RecB = record (v: int, a: RecA?) ()
def keep = fn (mut h: Holder, r: RecM, a: RecA) -> ( h.x = r ; h.x = a )
def h = Holder(null)
keep(h, RecM(1), RecA(2, RecB(3, null)))
assert(h.x != null)
```

## ModalKindTable rejects mutually recursive records containing mutable field

```babble
def MutA = record (b: MutB?) ()
def MutB = record (a: MutA?, box: Box) ()
def keep = fn (mut h: Holder, a: MutA) -> ( h.x = a )
```

```error
Borrowed parameter a escapes via field .x : mark parameter as shared a
```

## ModalKindTable allows stateless method-only object to cross

```babble
def Stateless = object ( def tag = fn (): int -> ( 1 ) )
def keep = fn (mut h: Holder, s) -> ( h.x = s )
def h = Holder(null)
keep(h, Stateless)
assert(h.x != null)
```

## ModalKindTable rejects stateful object with field and method escaping borrowed param

```babble
def Stateful = object ( def tag = fn (): int -> ( this.n ) ; def n = 1 )
def keep = fn (mut h: Holder, s) -> ( h.x = s )
def outer = fn (mut h: Holder) -> ( keep(h, Stateful) )
```

```error
Borrowed parameter s escapes via field .x : mark parameter as shared s
```

## ModalKindTable rejects generic record with uninstantiated type variable

```babble
def Pair2 of (A, B) = record (a: A, b: B) ()
def keep of T = fn (mut h: Holder, p: Pair2 of (T, int)) -> ( h.x = p )
```

```error
Borrowed parameter p escapes via field .x : mark parameter as shared p
```
