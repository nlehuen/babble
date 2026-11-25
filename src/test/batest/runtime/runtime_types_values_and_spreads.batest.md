# Runtime types, values, spreads, ArgumentsView, operators, and built-in checks

References : `BIP001` (runtime helpers), `BIP008` (runtime type checks), `Types`, `Values`, `Spreads`, `ArgumentsView`, `Operators`.

```babble
;; babble preamble
def dyn = fn (v: any?): any? -> ( v )
def coerce of T = fn io (shared x: any?): T -> (
    def l: java.util.List of T = java.util.ArrayList()
    def m = java.lang.Class.forName("java.util.List").getMethod("add", java.lang.Class.forName("java.lang.Object"))!
    m.invoke(l, x)
    l.get(0)
)
def Tag = class (def id: int) ()
def tests = import("org.babblelang.tests")
def demo = tests.JavaClassDemo
def countRest = fn (xs: any?...): int -> ( var n = 0 ; for _ in xs then ( n += 1 ) ; n )
def iterRest = fn (xs: int...): java.util.Iterator of int -> ( xs.iterator() )
def sumRest = fn (xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def A = type java.lang.annotation.Annotation
```

## Java default overload matching across any? and any parameters

```babble
def R1 = class () implements java.util.random.RandomGenerator (
    def nextLong = fn (): long -> ( 1L )
    def nextInt = fn (x: any?): int -> ( 0 )
    def nextDouble = fn (x: any): double -> ( 0.0 )
)
assert(R1().nextLong() == 1L)
```

## Java default overload matching across null and mismatched union parameters

```babble
def R2 = class () implements java.util.random.RandomGenerator (
    def nextLong = fn (): long -> ( 2L )
    def nextInt = fn (x: null): int -> ( 0 )
    def nextDouble = fn (x: bool | string): double -> ( 0.0 )
)
assert(R2().nextLong() == 2L)
```

## Java default overload matching across matching union and intersection parameters on primitives

```babble
def R3 = class () implements java.util.random.RandomGenerator (
    def nextLong = fn (): long -> ( 3L )
    def nextInt = fn (x: int | string): int -> ( 0 )
    def nextDouble = fn (x: java.lang.Runnable & java.io.Serializable): double -> ( 0.0 )
)
assert(R3().nextLong() == 3L)
```

## Java default overload matching across null and intersection parameters on interface

```babble
def U1 = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (v: int): int -> ( v )
    def andThen = fn (x: null): java.util.function.IntUnaryOperator -> ( this )
    def compose = fn (x: java.lang.Runnable & java.io.Serializable): java.util.function.IntUnaryOperator -> ( this )
)
assert(U1().applyAsInt(1) == 1)
```

## Java default overload matching across builtin exact, widening, and mismatch parameters

```babble
def R4 = class () implements java.util.random.RandomGenerator (
    def nextLong = fn (): long -> ( 4L )
    def nextInt = fn (x: int): int -> ( x )
    def nextFloat = fn (x: double): float -> ( 0.0f )
    def nextDouble = fn (x: int): double -> ( 0.0 )
)
assert(R4().nextLong() == 4L)
```

## Java default overload matching across function type and nominal type on interface

```babble
def U2 = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (v: int): int -> ( v )
    def andThen = fn (x: fn (): int): java.util.function.IntUnaryOperator -> ( this )
    def compose = fn (x: Tag): java.util.function.IntUnaryOperator -> ( this )
)
assert(U2().applyAsInt(2) == 2)
```

## Java default overload matching across nominal type vs primitive and array parameters

```babble
def RN = class () implements java.util.random.RandomGenerator (
    def nextLong = fn (): long -> ( 5L )
    def nextInt = fn (x: Tag): int -> ( 0 )
    def nextBytes = fn (x: Tag) -> ( () )
)
assert(RN().nextLong() == 5L)
```

## Java default overload matching across nominal type vs concrete Class

```babble
def e = (array of A).of()
def C = class () implements java.lang.reflect.AnnotatedElement (
    def getAnnotation = fn (c: java.lang.Class of any?): A? -> ( null )
    def getAnnotations = fn io (): array of A -> ( e )
    def getDeclaredAnnotations = fn io (): array of A -> ( e )
    def isAnnotationPresent = fn (x: Tag): bool -> ( false )
)
assert(C().getAnnotations().size() == 0)
```

## Java default overload matching across String vs concrete Class on AnnotatedElement

```babble
def e = (array of A).of()
def C = class () implements java.lang.reflect.AnnotatedElement (
    def getAnnotation = fn (c: java.lang.Class of any?): A? -> ( null )
    def getAnnotations = fn io (): array of A -> ( e )
    def getDeclaredAnnotations = fn io (): array of A -> ( e )
    def isAnnotationPresent = fn (x: java.lang.String): bool -> ( false )
)
assert(C().getAnnotations().size() == 0)
```

## Java default overload matching across JavaClassType class and interface pairs

```babble
def U3 = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (v: int): int -> ( v )
    def andThen = fn (x: java.lang.String): java.util.function.IntUnaryOperator -> ( this )
    def compose = fn (x: java.lang.Runnable): java.util.function.IntUnaryOperator -> ( this )
)
assert(U3().applyAsInt(3) == 3)
```

## Java default overload matching across JavaClassType subtype UnaryOperator vs Function

```babble
def BF = class () implements java.util.function.BiFunction of (string, string, string) (
    def apply = fn (a: string, b: string): string -> ( a + b )
    def andThen = fn (x: java.util.function.UnaryOperator of string): java.util.function.BiFunction of (string, string, string) -> ( this )
)
assert(BF().apply("a", "b") == "ab")
```

## Runtime null cast to Java class fails with Type error

```babble
dyn(null) as java.awt.Point
```

```error
Type error : expected java.awt.Point, got null
```

## Runtime union conversion with multiple widening numeric targets and any? is check

```babble
def x: float | double = dyn(1) as float | double
assert(x == 1.0 and dyn(null) is any? and dyn(1) is int | string)
```

## Compiled Babble class array component described as array of any

```babble
dyn((array of Tag).of(Tag(1))) as string
```

```error
Type error : expected string, got array of any
```

## Values same on booleans, toDouble on union, and isBabbleCallable on overload and object class

```babble
def w = fn (x: int | string): double | string -> ( x )
def ov = overload ( fn (x: int): int -> ( x + 1 ), fn (x: string): int -> ( 0 ) )
def oc = classof(object ( def k = 1 ))
def b1: any = true ; def b2: any = false
assert(same(b1, b1) and not same(b1, b2))
assert(w(1) == 1.0 and w("a") == "a" and java.util.Objects.toString(ov) != null and java.util.Objects.toString(oc) != null)
```

## Values toJava SAM adaptation via array set and OverloadSet arity

```babble
def ra = (array of java.lang.Runnable?)(1)
var ran = 0
coerce of (fn io (i: int, v: any?): null)(ra.set)(0, fn io () -> ( ran += 1 ))
ra.get(0)!.run()
def ov = overload ( fn (x: int): int -> ( x + 2 ), fn (x: string): int -> ( 0 ) )
assert(ran == 1 and java.util.function.IntUnaryOperator(ov).applyAsInt(3) == 5)
```

## Values toJava rejects Babble function for non-SAM CharSequence array element

```babble
def ca = (array of java.lang.CharSequence?)(1)
var caught = false
try ( coerce of (fn io (i: int, v: any?): null)(ca.set)(0, fn () -> ( () )) )
catch e: java.lang.ArrayStoreException then ( caught = true )
assert(caught)
```

## Values fromJava preserves non-FaceHandler JDK Proxy

```babble
def rc = java.lang.Class.forName("java.lang.Runnable")
def pr = java.lang.reflect.Proxy.newProxyInstance(rc.getClassLoader(), (array of java.lang.Class of any?).of(rc), fn (p, m, a) -> ( null ))
assert(same(list(pr).get(0), pr))
```

## Values toJavaSamByName with and without marker interfaces

```babble
assert(demo.invokeHiddenSam(fn (x: int): int -> ( x + 1 ), 5) == 6)
assert(demo.invokeHiddenSerializable(fn (x: int): int -> ( x + 2 ), 5) == 7)
```

## Values toJavaSamByName passes null through to Java method

```babble
def nf: (fn (x: int): int)? = null
demo.invokeHiddenSam(nf, 5)
```

```error
java.lang.NullPointerException
```

## Spreads asArray across Babble Iterable, Set, and rest ArgumentsView

```babble
def MyIter = class () implements java.lang.Iterable of int (
    def iterator = fn (): java.util.Iterator of int -> ( list(4, 5).iterator() )
)
def toIntArr = fn (xs: array of int): int -> ( xs.size() )
def viaRest = fn (xs: int...): int -> ( toIntArr(...xs) )
assert(toIntArr(...MyIter()) == 2 and toIntArr(...set(1, 2, 3)) == 3 and viaRest(7, 8) == 2)
```

## Spreads asArray widens boxed ints and preserves null in nullable double array

```babble
def toDblArr = fn (xs: array of double?): double? -> ( xs.get(1) )
assert(toDblArr(...list(null, 3)) == 3.0)
```

## Spreads adaptSamElem with Babble instance and lambda in SAM and marker array

```babble
def MyRun = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> ( () ) )
def items = list of (MyRun | (fn (): null))(MyRun(), fn () -> ( () ))
assert(demo.runSerArray(...items) == 2)
```

## Spreads collectRest skips MISSING slots for single and multiple supplied args

```babble
def g = coerce of (fn (a: int = ..., b: int = ..., c: int = ...): int)(sumRest)
assert(g(b: 5) == 5 and g(b: 5, c: 6) == 11)
```

## ArgumentsView empty and non-empty spreads for object, byte, short, and int arrays

```babble
assert(countRest(...(array of string?)(0)) == 0 and countRest(...(array of string).of("a")) == 1)
assert(countRest(...(array of byte)(0)) == 0 and countRest(...(array of byte).of(byte(1))) == 1)
assert(countRest(...(array of short)(0)) == 0 and countRest(...(array of short).of(short(1))) == 1)
assert(countRest(...(array of int)(0)) == 0 and countRest(...(array of int).of(1)) == 1)
```

## ArgumentsView empty and non-empty spreads for long, float, and double arrays

```babble
assert(countRest(...(array of long)(0)) == 0 and countRest(...(array of long).of(1L)) == 1)
assert(countRest(...(array of float)(0)) == 0 and countRest(...(array of float).of(1.0f)) == 1)
assert(countRest(...(array of double)(0)) == 0 and countRest(...(array of double).of(1.0)) == 1)
```

## ArgumentsView empty and non-empty spreads for bool and char arrays

```babble
assert(countRest(...(array of bool)(0)) == 0 and countRest(...(array of bool).of(true)) == 1)
assert(countRest(...(array of char)(0)) == 0 and countRest(...(array of char).of('a')) == 1)
```

## ArgumentsView iterator throws NoSuchElementException past end

```babble
def it = iterRest(1)
assert(it.next() == 1)
it.next()
```

```error
java.util.NoSuchElementException
```

## ChainedIterable iterator throws NoSuchElementException past end

```babble
def it = iterRest(...(array of int)(0), 1)
assert(it.hasNext() and it.next() == 1 and not it.hasNext())
it.next()
```

```error
java.util.NoSuchElementException
```

## Operators mixed long, float, int, double union arithmetic and array-vs-string equality

```babble
def op = fn (a: byte | short | int | long | float | double, b: byte | short | int | long | float | double): double -> ( double((a + b) - (a - b) + (a * b) / b) )
assert(op(4L, 2) == 8.0 and op(4L, 2L) == 8.0 and op(4.0f, 2L) == 8.0)
assert(op(2, 4.0f) == 10.0 and op(4.0, 2.0f) == 8.0 and dyn((array of int).of(1)) != dyn("x"))
```

## Operators compare rejects mismatched Comparable string vs Integer

```babble
def a = coerce of (java.lang.Comparable of any)("hi")
def b = coerce of (java.lang.Comparable of any)(java.lang.Integer.valueOf(1))
a < b
```

```error
Not comparable : a and b
```

## Operators compare rejects mismatched Comparable Integer vs string

```babble
def a = coerce of (java.lang.Comparable of any)(java.lang.Integer.valueOf(1))
def b = coerce of (java.lang.Comparable of any)("hi")
a < b
```

```error
Not comparable : a and b
```

## Assert accepts explicit null message when condition holds

```babble
def a2 = coerce of (fn (a: any?, b: any?): any?)(assert)
assert(a2(true, null) == true)
```

## Assert rejects zero arguments

```babble
coerce of (fn (): any?)(assert)()
```

```error
assert() called without parameters
```

## Assert rejects three arguments

```babble
coerce of (fn (a: any?, b: any?, c: any?): any?)(assert)(true, "m", 1)
```

```error
assert() called with too many parameters
```

## Assert rejects non-boolean condition at runtime

```babble
coerce of (fn (a: any?): any?)(assert)(123)
```

```error
Expression 123 : asserts don't rely on truth values, please make sure that the test has a boolean result
```

## Classof rejects zero arguments at runtime

```babble
coerce of (fn (): any?)(classof)()
```

```error
classof() takes 1 argument, 0 given
```

## Discard rejects zero arguments at runtime

```babble
coerce of (fn (): any?)(discard)()
```

```error
discard() takes 1 argument, 0 given
```

## Move rejects zero arguments at runtime

```babble
coerce of (fn (): any?)(move)()
```

```error
move() takes 1 argument, 0 given
```

## Same rejects zero arguments at runtime

```babble
coerce of (fn (): any?)(same)()
```

```error
same() takes 2 arguments, 0 given
```

## Import rejects zero arguments at runtime

```babble
coerce of (fn io (): any?)(import)()
```

```error
import expects one name : a Java package, or a .ba file
```

## Import rejects non-string argument at runtime

```babble
coerce of (fn io (x: any?): any?)(import)(123)
```

```error
import expects one name : a Java package, or a .ba file
```

## Rethrowing same exception in finally skips self-suppression

```babble
def ex = java.lang.IllegalStateException("same")
try ( throw ex ) finally ( throw ex )
```

```error
java.lang.IllegalStateException : same
```
