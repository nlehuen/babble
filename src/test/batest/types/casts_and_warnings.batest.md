# 'as' casts : valid runtime casts, nullable casts, and compile-time/runtime cast errors

References : BIP008 (as casts, nullable casts)

```babble
;; babble preamble
def dyn = fn (v : any) : any -> ( v )
def dynNull = fn (v : any?) : any? -> ( v )
def C = class (def x : int) ()
```

## Case 1

```babble
def c = dyn(C(11)) as C
def s = dyn("ok") as string
def n = dyn(42) as int
def d = dyn(2.5) as double
assert(c.x == 11)
assert(s == "ok")
assert(n == 42)
assert(d == 2.5)
```

## Case 2

```babble
assert((dynNull(null) as string?) == null)
```

## Error : Type error : expected string, got null

```babble
def s = dynNull(null) as string
```

```error
Type error : expected string, got null
```

## Error : Type error : expected int, got string

```babble
def n = dyn("abc") as int
```

```error
Type error : expected int, got string
```

## Error : Type error : expected int, got string (2)

```babble
def n = "abc" as int
```

```error
Type error : expected int, got string
```

## Array covariance, Cloneable/Serializable array casts, and nested array assignability

```babble
def a1: array of string = (array of string).of("a")
def a2: array of any = a1
def cl: java.lang.Cloneable = a1
def ser: java.io.Serializable = a1
def nested: array of (array of string) = (array of (array of string)).of(a1)
def nestedAny: array of (array of any) = nested
assert(a2.size() == 1 and (cl as array of string).get(0) == "a" and (ser is array of string) and nestedAny.size() == 1)
```

```stderr
Warning : Unchecked cast : array of string
Warning : Unchecked cast : array of string
```

## Incompatible primitive array cast is statically rejected

```babble
def a = (array of int).of(1)
def bad = a as array of double
```

```error
Type error : expected array of double, got array of int
```

## Frozen casts : frozen-to-frozen (interface -> class) and deeply-immutable record to frozen

```babble
def I = interface (def get = fn (): int -> (0))
def Impl = class (def n: int) implements I (def get = fn (): int -> (this.n))
def R = record (n: int) ()
def fi: frozen I = freeze(Impl(5))
def fImpl = fi as frozen Impl
def fr: frozen R = R(7) as frozen R
assert(fImpl.n == 5 and fr.n == 7)
```

```stderr
Warning : Redundant cast : frozen R
```

## Babble/Java interface overlap checks and disjoint final Java class checks

```babble
def IA = interface (def a = fn (): int -> (1))
def IB = interface (def b = fn (): int -> (2))
def Impl = class () implements IA, IB ()
def check = fn (x: IA, s: string): bool -> ((x is IB) and (x is java.io.Serializable) == false and (s is java.lang.CharSequence))
assert(check(Impl(), "ok"))
```

## Reassignment merging two compatible function types (`TypeAlgebra.mergeFunctions`)

```babble
var f = fn (x: int): int -> (x + 1)
f = fn (x: int): int -> (x * 2)
var g: fn (int): any = fn (x: int): int -> (x)
g = fn (x: int): string -> ("v" + x)
assert(f(3) == 6 and g(4) == "v4")
```

## Incompatible cast from frozen mutable class to mutable class is rejected statically

```babble
def fc: frozen C = freeze(C(1))
def bad = fc as C
```

```error
Type error : expected C, got frozen C
```
