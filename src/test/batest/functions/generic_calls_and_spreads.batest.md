# Generic call solving, explicit type application, spreads, and operator calls

References : `BIP008` (generics), `BIP012` (`of` type application), `BIP023` (varargs & spread), `analysis.CallSolver`.

```babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
def Base = interface ( def tag: fn (): int )
def Sub = record (n: int) implements Base ( def tag = fn (): int -> ( this.n ) )
def Empty of T = class () ( def zero = fn (): int -> ( 0 ) )
def Bounded of (T: Base) = class (def v: T) ()
def id of T = fn (x: T): T -> ( x )
def apply of (A, B) = fn (f: fn (A): B, x: A): B -> ( f(x) )
```

## Calling union containing non-callable reports not callable

```babble
def u: (fn (): int) | int = 1
u()
```

```error
u is not callable
```

## Calling union of callables fails when one member rejects argument

```babble
def u: (fn (int): int) | (fn (string): int) = fn (x: int): int -> ( x )
u(1)
```

```error
Type error : expected string, got int
```

## Zero-param generic class infers type from expected type

```babble
def e: Empty of int = Empty()
assert(e.zero() == 0)
```

## Explicitly parameterized zero-param class rejects extra argument

```babble
(Empty of int)(1)
```

```error
Too many parameters : 0 declared, 1 given
```

## Generic Java SAM called with explicit type arguments

```babble
def f = (java.util.function.Function of (int, string))(fn (x) -> ( "v=" + x ))
def p = (java.util.function.Predicate of string)(fn (s) -> ( s.length() > 1 ))
assert(f.apply(7) == "v=7" and p.test("hi") and not p.test("a"))
```

## Babble class explicit type argument fails bound

```babble
(Bounded of int)(1)
```

```error
Type error : type argument int does not satisfy bound Base
```

## Java class explicit type application wrong arity

```babble
(java.util.ArrayList of (int, string))()
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Java class explicit type argument fails Java bound

```babble
(java.util.EnumMap of (string, int))()
```

```error
Type error : type argument string does not satisfy bound K of java.util.EnumMap
```

## Java class explicit primitive type argument rejected for array interface

```babble
def bad = Demo.ArrayTransformer of int
```

```error
Type error : type argument int cannot be a primitive in array of E
```

## Applying type arguments twice to Java method fails

```babble
def a = (java.util.Collections.emptyList of int) of string
```

```error
(java.util.Collections.emptyListofint) is not generic
```

## Applying type arguments to non-generic Java method fails

```babble
def b = ("abc".length) of int
```

```error
("abc".length) is not generic
```

## Applying type arguments to scalar fails

```babble
def x = 1
def y = x of int
```

```error
x is not generic
```

## Explicitly parameterized Java method rejects incompatible argument

```babble
(java.util.Collections.max of int)("bad")
```

```error
No applicable overload of java.util.Collections.max for (string)
```

## Unapplied generic function instantiates to Java SAM target

```babble
def jf: java.util.function.Function of (int, int) = id
assert(jf.apply(9) == 9)
```

## Unapplied generic function assigned to non-function reports mismatch

```babble
def x: string = id
```

```error
Type error : expected string, got fn of T (x : T) : T
```

## Unapplied generic function with uninferable type parameter fails

```babble
def keep of (T, U) = fn (x: T): T -> ( x )
def f: fn (int): int = keep
```

```error
Missing type : cannot infer type arguments for keep
```

## Unapplied generic function fails bound check on instantiation

```babble
def bId of (T: Base) = fn (x: T): T -> ( x )
def f: fn (int): int = bId
```

```error
Type error : type argument int does not satisfy bound Base
```

## HOF returning nullable capturing closure instantiates captured callings through union

```babble
def maybeWrap = fn (f: fn (): int, ok: bool) -> ( if ok then ( fn () -> ( f() ) ) else ( null ) )
def g = maybeWrap(fn (): int -> ( 42 ), true)
def h = maybeWrap(fn (): int -> ( 1 ), false)
assert(g != null and g() == 42 and h == null)
```

## Spreading frozen array into non-frozen shared Iterable

```babble
def takeIt = fn (shared xs: java.lang.Iterable of int): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
def fa: frozen array of int = freeze(array.of(4, 5))
assert(takeIt(...fa) == 9)
```

## Spreading callable list into SAM array and SAM rest parameters

```babble
def runArr = fn io (shared ops: array of java.util.function.IntUnaryOperator): int -> ( ops.get(0).applyAsInt(3) )
def runRest = fn io (shared ops: java.util.function.IntUnaryOperator...): int -> ( def v = 2 ; for o in ops then ( v = o.applyAsInt(v) ) ; v )
def fns = list(fn (x: int): int -> ( x * 2 ))
assert(runArr(...fns) == 6 and runRest(...fns) == 4)
```

## Spreading non-iterable into sequence parameter reports error

```babble
def f = fn (xs: list of int): int -> ( xs.size() )
f(...42)
```

```error
Type error : expected iterable or array, got int
```

## Spreading array into Set falls back to subsumes error

```babble
def f = fn (xs: set of int): int -> ( xs.size() )
f(...array.of(1, 2))
```

```error
Type error : expected set(int), got array of int
```

## Spreading incompatible element list into array parameter fails

```babble
def f = fn (xs: array of int): int -> ( xs.size() )
f(...list("a"))
```

```error
Type error : expected int elements, got string
```

## Non-callable plus field reports not callable

```babble
def Bad = class () ( def plus = 1 )
def b = Bad()
b + 2
```

```error
b.plus is not callable
```

## Generic operator plus promotes SAM bound

```babble
def Runner = class () ( def plus of (T: java.lang.Runnable) = fn io (shared r: T): int -> ( r.run() ; 1 ) )
def n = 0
def c = Runner()
assert((c + fn io () -> ( n += 1 )) == 1 and n == 1)
```

## Generic operator plus enforces type bound

```babble
def BoxOp = class () ( def plus of (T: Base) = fn (x: T): int -> ( x.tag() ) )
BoxOp() + 1
```

```error
Type error : type argument int does not satisfy bound Base
```

## Generic operator plus rejects primitive array substitution

```babble
def ArrOp = class () ( def plus of T = fn (x: T): array of T -> ( array.of() ) )
ArrOp() + 1
```

```error
Generic array creation : array of T
```

## Generic unary neg rejects uninferable return type

```babble
def NegOp = class () ( def neg of T = fn (): T -> ( throw exception("x") ) )
def c = NegOp()
-c
```

```error
Missing type : cannot infer type arguments for c.neg
```

## Double widening allows unannotated supplier lambda and nullable int

```babble
def make of T = fn (f: fn (): T): T -> ( f() )
def d1: double = make(fn () -> ( 1 ))
def mi: int? = 2
def d2: double? = id(mi)
assert(d1 == 1.0 and d2 == 2.0)
```

## Double widening refuses annotated int supplier lambda inside invariant container

```babble
def Box of T = record (v: T) ()
def make of T = fn (f: fn (): T): Box of T -> ( Box(f()) )
def d: Box of double = make(fn (): int -> ( 1 ))
```

```error
Type error : expected Box(double), got Box(int)
```

## Parenthesized generic selector and Java SAM parameter defer typing until other args solve

```babble
def runSam of A = fn io (shared f: java.util.function.Function of (A, int), shared x: A): int -> ( f.apply(x) )
assert(apply((id), 42) == 42 and runSam(fn (s) -> ( s.length() ), "hello") == 5)
```

## Unresolvable deferred lambda falls through to second typing pass

```babble
def sink of A = fn (f: fn (A): int): int -> ( 0 )
sink(fn (x) -> ( x ))
```

```error
Missing type : cannot infer type arguments for sink
```

## Generic call partial solve reports first failed argument

```babble
def pair of (A, B) = fn (a: A, xs: list of B): A -> ( a )
pair(1, 2)
```

```error
Type error : expected list(B), got int
```

## Generic spread constraints infer element type from array and list

```babble
def countList of T = fn (xs: list of T): int -> ( xs.size() )
def countArr of T = fn (xs: array of T): int -> ( xs.size() )
assert(countList(...array.of(1, 2)) == 2 and countArr(...list("a", "b", "c")) == 3)
```

## Generic rest spread rejects non-iterable

```babble
def all of T = fn (xs: T...): int -> ( 0 )
all(...42)
```

```error
Type error : expected T, got int
```
