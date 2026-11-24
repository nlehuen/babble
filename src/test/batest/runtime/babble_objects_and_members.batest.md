# Babble object runtime : BabbleObjects, BabbleClass, BabbleInterface, Members, and JavaDefaultCall

References : `BIP001` (object model & JVM interfaces), `BabbleObjects`, `BabbleClass`, `BabbleInterface`, `InterpretedInstance`, `Members`, `JavaDefaultCall`.

```babble preamble
def dyn = fn (v: any?): any? -> ( v )
def coerce of T = fn io (shared x: any?): T -> (
    def l: java.util.List of T = java.util.ArrayList()
    def m = java.lang.Class.forName("java.util.List").getMethod("add", java.lang.Class.forName("java.lang.Object"))!
    m.invoke(l, x)
    l.get(0)
)
def C1 = class (def d: int) implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x: int): int -> ( x + this.d )
)
def C2 = class (def d: int) implements java.util.function.IntUnaryOperator, java.io.Serializable (
    def applyAsInt = fn (x: int): int -> ( x * this.d )
)
```

## Reconstructing an object literal class fails with object literals error

```babble
def o = object ( def x = 1 )
coerce of (fn (): any?)(classof(o))()
```

```error
object literals cannot be constructed again
```

## Detached Java default method via union receiver invokes JavaDefaultCall

```babble
def getAndThen = fn (o: C1 | C2) -> ( o.andThen )
def dbl = java.util.function.IntUnaryOperator(fn (x: int): int -> ( x * 2 ))
def f1 = getAndThen(C1(1))
def f2 = getAndThen(C2(3))
assert(f1(dbl).applyAsInt(4) == 10 and f2(dbl).applyAsInt(4) == 24)
```

## Record toString omits private fields and equals distinguishes different record classes

```babble
def R1 = record (x: int, private p: int = 9) ()
def R2 = record (x: int) ()
assert("" + R1(1) == "R1(x: 1)" and dyn(R1(1)) != dyn(R2(1)))
```

## Polymorphic union field read checks primitive definedness across classes

```babble
def A = class (def x: int) ()
def B = class (def x: int) ()
def readX = fn (o: A | B): int -> ( o.x )
assert(readX(A(10)) == 10 and readX(B(20)) == 20)
```

## Mutable class fields update cleanly and non-value class toString formats class name

```babble
def Box = class (def x: int) ()
def b = Box(1)
b.x = 42
assert(b.x == 42 and ("" + b).startsWith("Box@"))
```

## Polymorphic union field read detects primitive member used before definition

```babble
def Other = class (def x: int) ()
def readX = fn (o: Bad | Other): int -> ( o.x )
def Bad = class () (
    def y = readX(this)
    def x: int = 5
)
assert(readX(Other(1)) == 1)
Bad()
```

```error
Member used before definition : x
```

## Babble interface default lookup traverses multiple parent interfaces

```babble
def I1 = interface ( def tag = fn (): string -> ( "i1" ) )
def I2 = interface ( def other = fn (): int -> ( 2 ) )
def I3 = interface extends I2, I1 ()
def Impl = class () implements I3 (
    def tag = fn (): string -> ( super.tag() + "!" )
)
assert(Impl().tag() == "i1!" and Impl().other() == 2)
```

## Polymorphic member select across Babble class and Java StringBuilder

```babble
def Box = class (def n: int) ( def toString = fn (): string -> ( "B" + this.n ) )
def toStr = fn io (shared o: Box | java.lang.StringBuilder): string -> ( o.toString() )
assert(toStr(Box(1)) == "B1" and toStr(java.lang.StringBuilder("hi")) == "hi")
```

## Dynamic named constructor rejects missing required class parameter

```babble
def Pair = class (def a: int, def b: int = 2) ()
def DynB = type (fn (b: int = ..., z: int = ...): Pair) | (fn (z: int = ..., b: int = ...): Pair)
def mk: DynB = coerce of (fn (b: int = ..., z: int = ...): Pair)(Pair)
mk(b: 5)
```

```error
Missing parameter : a
```
