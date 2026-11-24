# Java interface default methods, Babble interface inheritance/super, and SAM adapter Object/arity checks

References : BIP002 (interfaces & super), BIP001 (JavaDefaultCall)
Targets : Faces, JavaDefaultCall, Supers, Values.arity

```babble
;; babble preamble
def EvenPred = class () implements java.util.function.IntPredicate (
    def test = fn (x : int) : bool -> ( int(x / 2) * 2 == x )
    def testInverted = fn io shared (x : int) : bool -> ( super(java.util.function.IntPredicate).negate().test(x) )
)

def Base = interface (
    def req : fn () : int
    def tag = fn () : string -> ( "base" )
)
def Mid = interface extends Base (
    def midTag = fn () : string -> ( super(Base).tag() + "+mid" )
)
def Impl = class (def v : int) implements Mid (
    def req = fn () : int -> ( this.v )
    def full = fn () : string -> ( super(Mid).midTag() )
)
def IntOp = type java.util.function.IntUnaryOperator
```

## Case 1

```babble
def p = EvenPred()
def neg = p.negate()
assert(p.test(4))
assert(not neg.test(4))
assert(neg.test(3))
assert(not p.testInverted(4))
```

## Case 2

```babble
def p = EvenPred()
def pred : java.util.function.IntPredicate = p
def predObj : any = pred
assert(java.util.Objects.equals(predObj, p))
assert(java.util.Objects.hashCode(predObj) == java.util.Objects.hashCode(p))
assert(java.util.Objects.toString(predObj) == java.util.Objects.toString(p))
```

## Case 3

```babble
def sam = java.util.function.IntUnaryOperator(fn (x : int) : int -> ( x + 10 ))
def samObj : any = sam
assert(java.util.Objects.equals(samObj, samObj))
assert(java.util.Objects.hashCode(samObj) == java.util.Objects.hashCode(samObj))
assert(java.util.Objects.toString(samObj) != "")
assert(not java.lang.reflect.Proxy.isProxyClass(sam.getClass()))
```

## Case 4

```babble
def x = Impl(7)
assert(x.tag() == "base")
assert(x.midTag() == "base+mid")
assert(x.full() == "base+mid")
```

## Case 5

```babble
def x = Impl(7)
assert(x is Base and x is Mid)
assert(string(Base) == "interface Base")
assert(string(Impl) == "class Impl")
```

## Error : I.m has no default

```babble
def I = interface (
    def m : fn () : int
)
def C = class () implements I (
    def m = fn () : int -> ( super(I).m() )
)
C().m()
```

```error
I.m has no default
```

## Case 7

```babble
def p1 = EvenPred()
def pred1 : java.util.function.IntPredicate = p1
assert(java.util.Objects.equals(pred1, p1))
assert(not java.util.Objects.equals(pred1, EvenPred()))
```

## Case 8

```babble
def fn1 = java.util.function.IntUnaryOperator(fn (x : int) : int -> ( x + 1 ))
def fn2 = java.util.function.IntUnaryOperator(fn (x : int) : int -> ( x * 2 ))
assert(java.util.Objects.equals(fn1, fn1))
assert(not java.util.Objects.equals(fn1, fn2))
assert(fn1.andThen(fn2).applyAsInt(3) == 8)
```

## Error : IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2

```babble
def add2 = fn (a : int, b : int) : int -> ( a + b )
def p = java.util.function.IntUnaryOperator(add2)
```

```error
IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2
```

## Error : IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2 (2)

```babble
def Box = class (def a : int, def b : int) (
    def m = fn (x : int, y : int) : int -> ( x + y )
)
def bm = Box(1, 2).m
def p = java.util.function.IntUnaryOperator(bm)
```

```error
IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2
```

## Error : IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2 (3)

```babble
def Pair = class (def a : int, def b : int) ()
def p = java.util.function.IntUnaryOperator(Pair)
```

```error
IntUnaryOperator.applyAsInt takes 1 arguments, function takes 2
```

## Overriding a Java interface default method with a matching Babble method

```babble
def CustomOp = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x : int) : int -> ( x + 1 )
    def andThen = fn (after : IntOp) : IntOp -> ( after )
)
def op : IntOp = CustomOp()
def dbl = java.util.function.IntUnaryOperator(fn (x : int) : int -> ( x * 2 ))
assert(op.andThen(dbl).applyAsInt(5) == 10)
```

## Overriding a Java interface default method with a trailing rest parameter

```babble
def RestOp = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x : int) : int -> ( x + 1 )
    def andThen = fn (after : IntOp, extra : any...) : IntOp -> ( after )
)
def op : IntOp = RestOp()
def dbl = java.util.function.IntUnaryOperator(fn (x : int) : int -> ( x * 2 ))
assert(op.andThen(dbl).applyAsInt(5) == 10)
```
