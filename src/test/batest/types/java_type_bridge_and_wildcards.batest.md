# Java type bridge, wildcard bounds, generic Java call solving, and local nullability axioms

References : `BIP008` (Java interop & generics), `BIP022` (intersections & SAMs), `analysis.JavaTypeBridge`, `analysis.JavaCallSolver`, `analysis.JavaNullability`.

```babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
def BaseI = interface ( def ping: fn (): int )
def LeftI = interface extends BaseI ( def defM = fn (): int -> ( 1 ) )
def RightI = interface extends BaseI ()
def DiamondI = interface extends LeftI, RightI ()
def CloseDefI = interface extends java.io.Closeable ( def close = fn () -> ( null ) )
def CloseAbsI = interface extends java.io.Closeable ()
```

## Lambda rejected for Runnable intersected with diamond Babble interface

```babble
def f = fn io (shared r: java.lang.Runnable & DiamondI) -> ( r.run() )
f(fn () -> ( null ))
```

```error
Not a functional interface : java.lang.Runnable & DiamondI
```

## Lambda rejected for Runnable intersected with Java-extending abstract Babble interface

```babble
def f = fn io (shared r: java.lang.Runnable & CloseAbsI) -> ( r.run() )
f(fn () -> ( null ))
```

```error
Not a functional interface : java.lang.Runnable & CloseAbsI
```

## Lambda rejected for Runnable intersected with multi-method Java CharSequence

```babble
def h = fn io (shared r: java.lang.Runnable & java.lang.CharSequence) -> ( r.run() )
h(fn () -> ( null ))
```

```error
Not a functional interface : java.lang.Runnable & java.lang.CharSequence
```

## Lambda accepted for Runnable intersected with Babble interface providing Java default

```babble
def g = fn io (shared r: java.lang.Runnable & CloseDefI) -> ( r.run() )
g(fn () -> ( null ))
```

```error
Type error : expected java.lang.Runnable & CloseDefI, got () : null
```

## Non-public Java return type widens to closest accessible supertype

```babble
def f = fn io (shared x: java.lang.AbstractStringBuilder): int -> ( x.append("a") )
```

```error
Type error : expected int, got java.lang.Appendable
```

## Collections.sort rejects element failing Comparable super wildcard bound

```babble
java.util.Collections.sort(list(java.lang.Object()))
```

```error
Type error : type argument java.lang.Object does not satisfy bound T of java.util.Collections.sort
```

## Collection.addAll rejects incompatible extends wildcard argument

```babble
def dst = list of int ()
dst.addAll(list("a"))
```

```error
No applicable overload of java.util.List.addAll for (list(string))
```

## Collection.addAll rejects nullable collection argument

```babble
def dst = list of int ()
def src: (list of int)? = null
dst.addAll(src)
```

```error
No applicable overload of java.util.List.addAll for (list(int)?)
```

## Generic Java class hierarchy scan inspects public generic fields on ANTLR Pair

```babble
def misc = import("org.antlr.v4.runtime.misc")
def p: misc.Pair of (int, string) = misc.Pair(7, "ok")
assert(p.a == 7 and p.b == "ok")
```

## Overload set passed to generic Java Optional.map matches SAM arity

```babble
def ov = overload ( fn (): int -> ( 0 ), fn (s: string): int -> ( s.length() ) )
def r = java.util.Optional.of("abcd").map(ov).orElse(0)
assert(r == 4)
```

## Overload set with no matching SAM arity rejected by Optional.map

```babble
def ov = overload ( fn (): int -> ( 0 ), fn (a: string, b: string): int -> ( 1 ) )
java.util.Optional.of("hi").map(ov)
```

```error
No applicable overload of java.util.Optional.map for (overload (fn () : int, fn (a : string, b : string) : int))
```

## Wrong-arity lambda rejected by bounded Java SAM

```babble
Demo.runSerializable(fn (x: int) -> ( x ))
```

```error
No applicable overload of org.babblelang.tests.JavaClassDemo.runSerializable for ((x : int) : int)
```

## Lambda rejected by non-SAM Java generic bound

```babble
Demo.maxPair(fn (): int -> ( 1 ), fn (): int -> ( 2 ))
```

```error
No applicable overload of org.babblelang.tests.JavaClassDemo.maxPair for (() : int, () : int)
```

## Java overload with two distinct SAM types exercises multi-SAM expectation

```babble
def f = fn io (es: java.util.concurrent.ExecutorService): int -> ( es.submit(fn () -> ( 1 )) )
```

```error
Ambiguous call to java.util.concurrent.ExecutorService.submit for (() : int)
```

## Deferred untyped lambda inference on generic Java spread method and TreeSet comparator

```babble
def ts: java.util.TreeSet of string = java.util.TreeSet(fn (a: string, b: string): int -> ( b.compareTo(a) ))
ts.add("a") ; ts.add("z")
def out: (array of string)? = Demo.invokeArrayTransformer(fn (arr) -> ( arr ), ...list("x", "y"))
assert(ts.first() == "z" and out != null and out.size() == 2 and out.get(0) == "x")
```

## Generic Java SAM called with non-callable falls through to constructor lookup

```babble
java.util.function.Function(42)
```

```error
No applicable overload of java.util.function.Function for (java.lang.Integer) : candidates are
```

## Generic Java SAM rejects primitive element in array parameter

```babble
Demo.ArrayTransformer(fn (a: array of int): array of int -> ( a ))
```

```error
Missing type : cannot infer type arguments for org.babblelang.tests.JavaClassDemo.ArrayTransformer
```

## Inferred primitive type arg for array parameter rejected by solveJava

```babble
Demo.echoArrayWithSample(1, array.of(2))
```

```error
No applicable overload of org.babblelang.tests.JavaClassDemo.echoArrayWithSample for (int, array of int)
```

## Explicit primitive type argument on array Java method reports array error

```babble
(Demo.echoArrayWithSample of int)(1, array.of(2))
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Spreading callable list into Java SAM array and array into Java List

```babble
def n = 0
def fns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x * 3 ))
def r = Demo.vaRunAll(...fns)
def rs = list(fn io () -> ( n += 1 ))
def k = Demo.runSerArray(...rs)
def first = Demo.first(...array.of(10, 20))
assert(r == 6 and k == 1 and n == 1 and first == 10)
```

## Spreading sequence into non-sequence Java parameter reports spread error

```babble
java.lang.Math.abs(...list(1))
```

```error
Spread into a parameter that takes no sequence : arg0
```

## Spreading incompatible list into overloaded Java array method formats spread arg

```babble
java.lang.String.valueOf(...list(1, 2))
```

```error
No applicable overload of java.lang.String.valueOf for (...list(int))
```

## Spreading non-iterable into Java array method fails

```babble
Demo.readObjectArray(...42)
```

```error
No applicable overload of org.babblelang.tests.JavaClassDemo.readObjectArray for (...int)
```

## Spreading wrong element list into Java double varargs fails

```babble
Demo.vaSumDoubles(...list("a"))
```

```error
No applicable overload of org.babblelang.tests.JavaClassDemo.vaSumDoubles for (...list(string))
```

## Nullable byte, bool, and char arrays, frozen Java arg, and Cloneable array arg

```babble
def ab = (array of byte?).of(byte(1), null)
def abo = (array of bool?).of(true, null)
def ac = (array of char?).of('a', null)
def fArr: frozen array of int = freeze(array.of(1, 2))
def takeCl = fn (c: java.lang.Cloneable): bool -> ( c != null )
assert(ab.size() == 2 and abo.size() == 2 and ac.size() == 2 and Demo.describe(fArr) != "" and takeCl(array.of(1)))
```

## Assigning array.length suggests size()

```babble
def a = array.of(1)
a.length = 2
```

```error
No such field in array of int : length (use size())
```

## Assigning unknown field on array fails

```babble
def a = array.of(1)
a.foo = 2
```

```error
No such field in array of int : foo
```

## Assigning missing field on Java instance fails

```babble
def d = Demo()
d.missing = 1
```

```error
No such field in org.babblelang.tests.JavaClassDemo : missing
```

## Assigning instance field on Java class fails

```babble
Demo.count = 1
```

```error
Not a static field : org.babblelang.tests.JavaClassDemo.count
```

## Assigning untyped any to Java int field fails static type check

```babble
def d = Demo()
def v: any = 42
d.count = v
```

```error
Type error : expected int, got any
```

## Static final null Java field remains nullable without nullmarked

```babble
def sf: int? = Demo.STATIC_FINAL_NULL_INT
assert(sf == null)
```

## Local nullmarked axiom treats Java instance field as non-null

```babble
axiom org.babblelang.tests.JavaClassDemo: class nullmarked ()
def d = Demo()
d.instanceBoxedInt = 7
def v: int = d.instanceBoxedInt
assert(v == 7)
```

## Local axioms with array and varargs method and constructor exercise parameterElementNullness

```babble
axiom org.babblelang.tests.JavaClassDemo: class () static (
    def vaPick: fn io (xs: any...): string
)
axiom org.babblelang.tests.JavaClassDemo.VarArgsCtor: class () static (
    def new: fn io (prefix: string, values: int...)
)
def s = Demo.vaPick("a", "b")
def c = Demo.VarArgsCtor("p", 1, 2)
assert(s == "String...:2" and c.summary == "p:1,2")
```
