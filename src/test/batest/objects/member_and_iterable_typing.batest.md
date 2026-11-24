# Member lookup, field assignment, interface conformance, and iterable resolution

References : `BIP008` (typing & object model), `BIP022` (intersections), `analysis.MemberTypes`.

```babble preamble
def tests = import("org.babblelang.tests")
def HasVal = interface ( def x: int )
def SubHasVal = interface extends HasVal ()
def Holder = class (def x: int, private def secret: int) implements SubHasVal ()
def GenBox of T = class (init: T) ( def val = init )
def CntIter = class (private def i: int) (
    def hasNext = fn (): bool -> ( this.i < 2 )
    def next = fn mut (): int -> ( this.i += 1 ; this.i )
)
def JavaIterWrap = class (private def xs: list of int) (
    def iterator = fn (): java.util.Iterator of int -> ( this.xs.iterator() )
)
```

## Array toString() and Object.getClass() resolve through MemberTypes

```babble
def a = array.of(1, 2)
assert(a.toString() == "[1, 2]" and a.getClass().isArray())
```

## Unbounded type variable is not a scope

```babble
def f of T = fn (x: T) -> ( x.foo )
```

```error
Not a scope : x
```

## Reading private Babble member from outside fails

```babble
def h = Holder(1, 2)
h.secret
```

```error
secret is private
```

## Unknown property on array keyword fails

```babble
array.foo
```

```error
No such name in array : foo
```

## Intersection of List and Collection resolves overloaded generic toArray

```babble
def run = fn io (shared xs: java.util.List of string & java.util.Collection of string): int -> ( xs.toArray().size() )
assert(run(list("a", "b")) == 2)
```

## Intersection with Java interface public constant field resolves via getField

```babble
def readMagic = fn (x: java.io.ObjectStreamConstants & java.lang.Runnable): short -> ( x.STREAM_MAGIC )
assert(readMagic != null)
```

## Assigning field inherited through sub-interface and bounded TypeVar

```babble
def setSub = fn (mut s: SubHasVal, v: int) -> ( s.x = v )
def setBound of (T: HasVal) = fn (mut t: T, v: int) -> ( t.x = v )
def h = Holder(1, 9)
setSub(h, 10) ; assert(h.x == 10)
setBound(h, 20) ; assert(h.x == 20)
```

## Assigning unannotated inferred field of generic class from outside checks substituted type

```babble
def b = GenBox(1)
b.val = 5
assert(b.val == 5)
```

## Assigning mismatched type to unannotated generic class field fails

```babble
def b = GenBox(1)
b.val = "no"
```

```error
Type error : expected int, got string
```

## Assigning missing member on class implementing interface fails

```babble
def h = Holder(1, 2)
h.nope = 3
```

```error
No such member : nope
```

## Assigning member on scalar reports Not a scope

```babble
def n = 1
n.foo = 2
```

```error
Not a scope : n
```

## Overloaded method implementing two Java interfaces with same abstract signature deduplicates check

```babble
def RunWrap = interface extends java.lang.Runnable ()
def Both = class (def n: int) implements RunWrap, tests.JavaClassDemo.SameSigRunnable (
    def run = overload ( fn mut (): void -> ( this.n += 1 ; () ), fn mut (k: int): void -> ( this.n += k ; () ) )
)
def b = Both(0) ; b.run() ; b.run(4)
assert(b.n == 5)
```

## Babble interface provides overloaded default method for Java interface

```babble
def RunDef = interface extends java.lang.Runnable (
    def run = overload ( fn (): null -> ( null ), fn (k: int): null -> ( null ) )
)
def Impl = class () implements RunDef ()
def r: java.lang.Runnable = Impl() ; r.run()
assert(r != null)
```

## Inherited default method with wrong signature fails interface conformance

```babble
def Need = interface ( def m: fn (): int )
def BadDef = interface ( def m = fn (): string -> ( "no" ) )
def C = class () implements Need, BadDef ()
```

```error
Type error : expected () : int, got () : string
```

## For loop over bounded TypeVar and frozen TypeVar

```babble
def sumT of (T: java.lang.Iterable of int) = fn (xs: T): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
def sumF of (T: java.lang.Iterable of int) = fn (xs: frozen T): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
assert(sumT(list(1, 2, 3)) == 6 and sumF(freeze(list(4, 5))) == 9)
```

## For loop over intersection finds iterable second member mutable and frozen

```babble
def sumI = fn (xs: java.io.Serializable & java.util.List of int): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
def sumFI = fn (xs: frozen (java.io.Serializable & java.util.List of int)): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
assert(sumI(java.util.ArrayList(list(2, 3))) == 5 and sumFI(freeze(java.util.ArrayList(list(4, 5)))) == 9)
```

## For loop over non-iterable intersection reports error

```babble
def bad = fn (x: java.lang.Runnable & java.io.Serializable) -> ( for v in x then ( null ) )
```

```error
Not iterable : java.lang.Runnable & java.io.Serializable
```

## For loop over union of Java iterables and union of Java iterators

```babble
def sumJI = fn (u: list of int | set of int): int -> ( def s = 0 ; for x in u then ( s += x ) ; s )
def sumIt = fn (mut u: java.util.Iterator of int | java.util.ListIterator of int): int -> ( def s = 0 ; for x in u then ( s += x ) ; s )
assert(sumJI(list(1, 2)) == 3 and sumIt(list(3, 4).iterator()) == 7)
```

## For loop over frozen union mixing list and array

```babble
def sumMix = fn (u: frozen (list of int | array of int)): int -> ( def s = 0 ; for x in u then ( s += x ) ; s )
assert(sumMix(freeze(array.of(5, 6))) == 11)
```

## For loop over Babble-to-Java iterator wrapper, direct Babble iterator, and Java Iterator

```babble
def s1 = 0 ; for x in JavaIterWrap(list(10, 20)) then ( s1 += x )
def s2 = 0 ; for x in CntIter(0) then ( s2 += x )
def it = list(1, 2).iterator()
def c = 0 ; for x in it then ( c += 1 )
assert(s1 == 30 and s2 == 3 and c == 2)
```

## For loop rejects Babble class with private iterator

```babble
def Priv = class () ( private def iterator = fn (): CntIter -> ( CntIter(0) ) )
for x in Priv() then ( null )
```

```error
Not iterable : Priv
```

## For loop rejects Babble class with non-bool hasNext

```babble
def BadNext = class () ( def hasNext = fn (): int -> ( 0 ) ; def next = fn (): int -> ( 1 ) )
for x in BadNext() then ( null )
```

```error
Not iterable : BadNext
```
