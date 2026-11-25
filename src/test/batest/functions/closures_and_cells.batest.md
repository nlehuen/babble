# Closures, mutable captures, and cell specializations

References : BIP001 (closures), IntCell / DoubleCell / BoolCell / Cell, pushDefaultZero

## Mutable captures across all 4 cell specializations with independent instances

```babble
def makeCounter = fn () -> (
    var i = 0 ; var d = 0.5 ; var b = false ; var s = "x"
    fn io () : string -> (
        i += 1 ; d += 1.25 ; b = not b ; s += "y"
        "" + i + ":" + d + ":" + b + ":" + s
    )
)
def c1 = makeCounter() ; def c2 = makeCounter()
assert(c1() == "1:1.75:true:xy")
assert(c1() == "2:3.0:false:xyy")
assert(c2() == "1:1.75:true:xy")
```

## Shared mutable capture between two sibling closures and enclosing block

```babble
def sharedCapture = fn () -> (
    var count = 10
    def inc = fn (k : int) : int -> ( count += k ; count )
    def read = fn () : int -> ( count )
    assert(inc(5) == 15)
    assert(read() == 15)
    count += 1
    assert(read() == 16)
)
sharedCapture()
```

## Multi-level capture where inner closure reads outer variable unused in middle closure

```babble
def outer = fn (x : int) -> (
    def mid = fn () -> ( fn () : int -> ( x + 100 ) )
    mid()
)
assert(outer(7)() == 107)
```

## Widening a captured int variable to double uses DoubleCell instead of IntCell

```babble
def widenCapture = fn io () -> (
    var v = 1
    def read = fn () -> ( v )
    v = 2.5
    assert(read() == 2.5)
    assert("" + classof(read()) == "java.lang.Double")
)
widenCapture()
```

## Forward capture read before definition : BoolCell

```babble
def f = fn () : bool -> ( b )
f()
def b = true
```

```error
Name used before definition : b
```

## Forward capture read before definition : DoubleCell

```babble
def f = fn () : double -> ( d )
f()
def d = 1.5
```

```error
Name used before definition : d
```

## Forward capture read before definition : reference Cell

```babble
def f = fn () : string -> ( s )
f()
def s = "hi"
```

```error
Name used before definition : s
```

## Forward capture assign before definition : BoolCell

```babble
def f = fn io () -> ( b = false )
f()
var b = true
```

```error
Name used before definition : b
```

## Forward capture assign before definition : DoubleCell

```babble
def f = fn io () -> ( d = 2.5 )
f()
var d = 1.5
```

```error
Name used before definition : d
```

## Forward capture assign before definition : IntCell

```babble
def f = fn io () -> ( x = 2 )
f()
var x = 1
```

```error
Name used before definition : x
```

## Uninitialized non-captured double and ref locals hoisted to scope block (pushDefaultZero)

```babble
def cond = false
if cond then ( def x = 1.0 ) else ( )
def f = fn () : double -> ( if cond then ( y ) else ( 0.0 ) )
assert(f() == 0.0)
def y = 3.5
```
