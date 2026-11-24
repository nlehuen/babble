# Generic inference, widening hints, Java SAMs/bounds, generic interface inheritance, and polymorphic values

References : BIP008 (generic inference, widening, unions), BIP021 (D20 polymorphic function values)
Note : core generic declarations, standalone `of`, covariance, and cast errors are in types/generics.batest

```babble
;; babble preamble
def id of T = fn (x: T): T -> ( x )
def Box of T = class (def v: T) ()
```

## Case 1

```babble
def intId: fn (x: int): int = id
def strId: fn (x: string): string = id
def s = java.util.stream.IntStream.range(1, 4).map(id).sum()
def o = java.util.Optional.of(5).map(id).get()
assert(intId(4) == 4)
assert(strId("ok") == "ok")
assert(s == 6)
assert(o == 5)
```

## Generic function stored as a polymorphic value (BIP021-D20)

```babble
def k = id
assert(k(4) == 4)
assert(k("ok") == "ok")
```

## Return-type and widening hints across primitives, unions, classes, and multi-param generics

```babble
def mkPair of (A, B) = fn (a: A, b: B): A -> ( a )
def d1: double = id(1)
def u1: int | double = id(2)
def b1: Box of double = Box(3)
def p: double = mkPair(2, "z")
assert(d1 == 1.0 and string(classof(d1)) == "java.lang.Double")
assert(u1 == 2)
assert(b1.v == 3.0 and string(classof(b1.v)) == "java.lang.Double")
assert(p == 2.0 and string(classof(p)) == "java.lang.Double")
```

## Deferred lambda parameter inference in Babble generic HOF and Java Optional.map

```babble
def apply of (A, B) = fn (x: A, f: fn (a: A): B): B -> ( f(x) )
def r = apply(3, fn (x) -> ( x * 2.5 ))
def opt: java.util.Optional of int = java.util.Optional.of(5)
def opt2 = opt.map(fn (x) -> ( x + 1 ))
assert(r == 7.5)
assert(opt2.get() == 6)
```

## Case 5

```babble
def lenOpt = java.util.Optional.of("abc").map(fn (s) -> ( java.lang.StringBuilder(s).length() )).get()
def func: java.util.function.Function of (string, int) = fn (s) -> ( java.lang.StringBuilder(s).length() )
assert(lenOpt == 3)
assert(func.apply("hello") == 5)
```

## Case 6

```babble
def xs: java.util.List of string = java.util.ArrayList()
xs.add("bb") ; xs.add("a") ; xs.add("ccc")
java.util.Collections.sort(xs, fn (a, b) -> ( java.lang.StringBuilder(a).length() - java.lang.StringBuilder(b).length() ))
xs.replaceAll(fn (s) -> ( s + ":" + java.lang.StringBuilder(s).length() ))
assert(xs.get(0) == "a:1" and xs.get(1) == "bb:2" and xs.get(2) == "ccc:3")
```

## Error : Missing type : cannot infer type arguments for java.util.function.Fun...

```babble
def func = java.util.function.Function(fn (s) -> ( s ))
```

```error
Missing type : cannot infer type arguments for java.util.function.Function
```

## Case 8

```babble
def Base of A = interface (
    def get: fn (): A
    def pair = fn io shared (): list of A -> ( list of A (this.get()) )
)
def Sub of B = interface extends Base of B ( def get2 = fn () -> ( this.get() ) )
def Impl of C = class (def v: C) implements Sub of C ( def get = fn () -> ( this.v ) )
def i: Sub of string = Impl("hi")
assert(i.get() == "hi" and i.get2() == "hi" and i.pair().get(0) == "hi")
```

## Error : Wrong number of type arguments : expected 0, got 1

```babble
def x: int of int = 1
```

```error
Wrong number of type arguments : expected 0, got 1
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
def b: Box of (int, string) = Box(1)
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Missing type arguments : java.util.List

```babble
def l: java.util.List = java.util.ArrayList()
```

```error
Missing type arguments : java.util.List
```

## Error : Wrong number of type arguments : expected 1, got 2 (2)

```babble
def l: java.util.List of (int, string) = java.util.ArrayList()
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Type error : type argument string does not satisfy bound E of java.ut...

```babble
def e: (java.util.EnumSet of string)? = null
```

```error
Type error : type argument string does not satisfy bound E of java.util.EnumSet
```

## Case 14

```babble
def ju = import("java.util")
def xs: ju.List of string = ju.ArrayList()
xs.add("ok")
assert(xs.get(0) == "ok")
```

## Error : Missing type : cannot infer type arguments for java.util.Optional.empty

```babble
def o = java.util.Optional.empty()
```

```error
Missing type : cannot infer type arguments for java.util.Optional.empty
```

## Error : Missing type : cannot infer type arguments for java.util.ArrayList

```babble
def l = java.util.ArrayList()
```

```error
Missing type : cannot infer type arguments for java.util.ArrayList
```

## Error : Missing type : f (recursive)

```babble
def f = fn (n: int) -> ( f(n - 1) )
```

```error
Missing type : f (recursive)
```

## Case 18

```babble
def f = fn (n: int): int -> ( if n <= 0 then ( 1 ) else ( n * recurse(n - 1) ) )
assert(f(4) == 24)
```

## Error : Type error : expected int, got string

```babble
def f = fn (flag: bool): int -> (
    if flag then ( return 1 )
    return "no"
)
```

```error
Type error : expected int, got string
```

## Error : Type error : expected int, got null

```babble
def f = fn (): int -> ( )
```

```error
Type error : expected int, got null
```

## Case 21

```babble
def negU = fn (flag: bool): int | double -> (
    def v: int | double = if flag then ( 3 ) else ( 2.5 )
    -v
)
def f1 = fn (x: int): int -> ( x + 1 )
def f2 = fn (x: int): double -> ( x * 0.5 )
def pick = fn (flag: bool) -> (
    def func = if flag then ( f1 ) else ( f2 )
    func(4)
)
assert(negU(true) == -3 and negU(false) == -2.5)
assert(pick(true) == 5 and pick(false) == 2.0)
```

## Error : f is not callable

```babble
def f: (fn (x: int): int)? = null
f(1)
```

```error
f is not callable
```

## Error : Type error : expected int, got double (x is double because of the ass...

```babble
def x = 1
def takeInt = fn (n: int) -> ( n )
takeInt(x)
x = 1.5
```

```error
Type error : expected int, got double (x is double because of the assignment)
```

## Error : Type error : expected int, got double (x is double because of the call)

```babble
def takeInt = fn (n: int) -> ( n )
def f = fn (x) -> (
    discard(takeInt(x))
    x
)
discard(f(1))
f(1.5)
```

```error
Type error : expected int, got double (x is double because of the call)
```

## Error : Type error : type argument Item does not satisfy bound T of java.util...

```babble
def Item = class (def x: int) ()
def xs: java.util.List of Item = java.util.ArrayList()
xs.add(Item(1))
println(java.util.Collections.max(xs))
```

```error
Type error : type argument Item does not satisfy bound T of java.util.Collections.max
```

## Generic union rollback (ConstraintStore.reset) and class-to-SAM assignability

```babble
def ValBox of T = record (v: T) ()
def Pair = record (v: int) ()
def wrap of T = fn (x: T): ValBox of T -> ( ValBox(x) )
def u: string | ValBox of int = wrap(42)
def func: java.util.function.Function of (int, Pair) = Pair
def uv = if u is ValBox then ( u.v ) else ( 0 )
def a = 1 ; a = 2.5
def b = null ; b = "ok"
assert(uv == 42 and func.apply(7).v == 7)
assert(a == 2.5 and b == "ok")
```
