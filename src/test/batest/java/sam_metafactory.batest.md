# Cached LambdaMetafactory SAM adapters, FLAG_BRIDGES, primitive conversions, and Proxy fallbacks

References : BIP002 §13, RM003 (proxy-free Java SAM adapters), Faces

```babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
```

## Case 1

```babble
def log = ""
def r1 = java.lang.Runnable(fn () -> ( log += "a" ))
def r2 = java.lang.Runnable(fn () -> ( log += "b" ))
r1.run()
r2.run()
assert(log == "ab")
assert(not Demo.isProxy(r1))
assert(r1.getClass() == r2.getClass())
```

## Case 2

```babble
def IntOp = java.util.function.IntUnaryOperator
def f1 = IntOp(fn (x: int): int -> ( x + 2 ))
def f2 = IntOp(fn (x: int): int -> ( x * 3 ))
def cmp = java.util.Comparator(fn (a: int, b: int): int -> ( a - b ))
assert(not Demo.isProxy(f1))
assert(f1.getClass() == f2.getClass())
assert(f1.andThen(f2).applyAsInt(4) == 18)
assert(cmp.reversed().compare(2, 5) > 0)
```

## Generic SAM with erased Predicate superinterface bridge (altMetafactory FLAG_BRIDGES)

```babble
def sc = Demo.StringCriterion(fn (s: string): bool -> ( s == "ok!" or s == "ok?" ))
assert(not Demo.isProxy(sc))
assert(sc.test("ok!"))
assert(not sc.negate().test("ok!"))
assert(Demo.invokeErasedPredicate(sc, "ok?"))
assert(not Demo.invokeErasedPredicate(sc, "no"))
```

## Case 4

```babble
def cs = Demo.CovariantSupplier(fn (): string -> ( "bridged" ))
assert(not Demo.isProxy(cs))
assert(cs.get() == "bridged")
assert(Demo.invokeErasedSupplier(cs) == "bridged")
```

## Case 5

```babble
def nine = java.lang.Character.forDigit(9, 10)
def ch = Demo.CharSupplier(fn volatile (): any? -> ( nine ))
def by = Demo.ByteUnary(fn (b: any?): any? -> ( b ))
assert(not Demo.isProxy(ch))
assert(ch.getAsChar() == nine)
assert(by.apply(java.lang.Byte.valueOf("3")) == 3)
```

## Case 6

```babble
def sh = Demo.ShortUnary(fn io (s: any?): any? -> ( java.lang.Byte.valueOf("7") ))
def fl = Demo.FloatUnary(fn (f: any?): any? -> ( 5 ))
def lg = java.util.function.IntToLongFunction(fn (n: int): any? -> ( n + 10 ))
assert(sh.apply(java.lang.Short.valueOf("1")) == 7)
assert(fl.apply(java.lang.Float.parseFloat("1.5")) == 5.0)
assert(lg.applyAsLong(5) == 15)
```

## Case 7

```babble
def Adder = class (def base: int) (
    def add = fn (x: int): int -> ( this.base + x )
)
def boundOp = java.util.function.IntUnaryOperator(Adder(100).add)
assert(not Demo.isProxy(boundOp))
assert(boundOp.applyAsInt(23) == 123)
```

## Case 8

```babble
def Pair = record (a: int, b: int) ()
def mk: java.util.function.BiFunction of (int, int, Pair) = java.util.function.BiFunction(Pair)
assert(not Demo.isProxy(mk))
assert(mk.apply(3, 4) == Pair(3, 4))
def nested = java.util.function.Supplier(fn (): java.lang.Runnable -> ( fn () -> () ))
assert(not Demo.isProxy(nested.get()))
```

## Arity 16 is the maximum handled by LambdaMetafactory

```babble
def s16 = Demo.Arity16Sam(fn (
    a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int,
    i: int, j: int, k: int, l: int, m: int, n: int, o: int, p: int
): int -> ( a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p ))
assert(not Demo.isProxy(s16))
assert(s16.apply(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16) == 136)
```

## Arity > 16 falls back from LambdaMetafactory to Proxy

```babble
def s17 = Demo.Arity17Sam(fn (
    a: int, b: double, c: java.io.File, d: string, e: int, f: int, g: int, h: int,
    i: int, j: int, k: int, l: int, m: int, n: int, o: int, p: int, q: int
): double -> ( a + b + (if d == "abcd" then ( 4 ) else ( 0 )) + q ))
assert(Demo.isProxy(s17))
assert(s17.apply(2, 1.5, java.io.File("."), "abcd", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10) == 17.5)
```

## Case 11

```babble
def s17 = Demo.Arity17Sam(fn (
    a: int, b: double, c: java.io.File, d: string, e: int, f: int, g: int, h: int,
    i: int, j: int, k: int, l: int, m: int, n: int, o: int, p: int, q: int
): double -> ( 0.0 ))
def obj: any = s17
assert(s17.tag() == 17)
assert(java.util.Objects.toString(obj) == "function as Arity17Sam")
```

## Package-private SAM falls back from LambdaMetafactory to Proxy

```babble
assert(Demo.invokeHiddenSam(fn (x: int): int -> ( x * 3 ), 7) == 21)
```

## Error : Type error : expected int, got null

```babble
def op = Demo.BoxedIntUnaryOperator(fn (x: int): int -> ( x + 1 ))
assert(not Demo.isProxy(op))
Demo.invokeUnaryBoxed(op, Demo.boxedInt(false))
```

```error
Type error : expected int, got null
```

## Error : Type error : IntUnaryOperator.applyAsInt must return int, got String

```babble
def bad = java.util.function.IntUnaryOperator(fn (x: int): any? -> ( "wrong" ))
bad.applyAsInt(1)
```

```error
Type error : IntUnaryOperator.applyAsInt must return int, got String
```

## Error : StringCriterion.test takes 1 arguments, function takes 2

```babble
Demo.StringCriterion(fn (a: string, b: string): bool -> ( a == b ))
```

```error
StringCriterion.test takes 1 arguments, function takes 2
```
