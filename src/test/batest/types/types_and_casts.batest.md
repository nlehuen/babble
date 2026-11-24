# Casts, redundant-cast warnings, flow-sensitive narrowing, callable assignments, and array/Java field errors

References : BIP008 (casts, narrowing, callable types), BIP016 (effects), BIP020 (array members)

```babble preamble
def I1 = interface ( def a : fn () : int )
def I2 = interface ( def b : fn () : string )
def C = class (def x : int) implements I1 (
    def a = fn () : int -> ( this.x )
)
def C2 = class () implements I1 (
    def a = fn () : int -> ( 0 )
)
def D = class () ()
def pickI1 = fn (flag : bool) : I1 -> ( if flag then ( C(4) ) else ( C2() ) )
```

## Case 1

```babble
def c = C(4)
def red1 = c as C
def red2 = 3 as int
assert(red1.x == 4)
assert(red2 == 3)
```

```stderr
Warning : Redundant cast : C
Warning : Redundant cast : int
```

## Case 2

```babble
def i1 = pickI1(true)
def cBack = i1 as C
assert(cBack.x == 4)
def stack = java.lang.Thread.currentThread().getStackTrace()
assert(stack.size() > 0)
```

## Cross-interface cast I1 -> I2? is statically allowed, then fails at runtime on C

```babble
def i1 = pickI1(true)
def bad = i1 as I2?
```

```error
Type error : expected I2?, got C
```

## Error : Type error : expected I1, got D

```babble
def d = D()
def bad = d as I1
```

```error
Type error : expected I1, got D
```

## Error : Type error : expected int, got string

```babble
def bad = "x" as int
```

```error
Type error : expected int, got string
```

## Error : Type error : expected int, got Box

```babble
def Box of T = class (def v : T) ()
def mix = fn (flag : bool) -> ( if flag then ( Box(1) ) else ( 2 ) )
def bad = mix(true) as int
```

```error
Type error : expected int, got Box
```

## Error : Type error : expected string, got int

```babble
def mix = fn (flag : bool) -> ( if flag then ( 1 ) else ( "s" ) )
def bad = mix(true) as string
```

```error
Type error : expected string, got int
```

## Case 8

```babble
def classify = fn (x : any?) : string -> (
    if x is double then ( "d:" + (x + 1.0) )
    else if x is bool then ( "b:" + (not x) )
    else if x is any then ( "a" )
    else ( "null" )
)
assert(classify(2.5) == "d:3.5")
assert(classify(false) == "b:true")
assert(classify("hi") == "a")
assert(classify(null) == "null")
```

## Case 9

```babble
def checkGuards = fn (flag : bool) : int -> (
    def y : int | string = 10
    def ok1 = if (y is int) and (y > 5) then ( y + 1 ) else ( 0 )
    def z : int? = if flag then ( 7 ) else ( null )
    def early = fn () : int -> (
        if z == null or not flag then ( return -1 )
        z + 2
    )
    ok1 * 100 + early()
)
assert(checkGuards(true) == 1109)
assert(checkGuards(false) == 1099)
```

## Case 10

```babble
def checkReassign = fn (flag : bool) : bool -> (
    def w : int | string = 1
    def killedIf = if w is int then ( w = "s" ; 1 ) else ( 0 )
    def k : int | string = 3
    if flag then ( k = "z" )
    killedIf == 1 and (k is string)
)
assert(checkReassign(true))
assert(not checkReassign(false))
```

## Case 11

```babble
def checkClosure = fn () : int | string -> (
    def u = 2
    def closureKill = fn () : int | string -> ( u )
    if u is int then (
        def f = fn () -> ( u = 9 )
        f()
    )
    closureKill()
)
assert(checkClosure() == 9)
```

## Default for 'a' binds outer b, skipping the later parameter b in the same list

```babble
def b = 10
def f = fn (a : int = b, b : int = 1) : int -> ( a + b )
assert(f() == 11)
```

## Case 13

```babble
def Box = class (def v : int) ()
def apply = fn (x : int, func : fn (a : int) : Box) : int -> ( func(x).v )
def mk : fn (a : int) : Box = Box
assert(apply(5, Box) == 5)
assert(mk(7).v == 7)
```

## Error : Type error : expected (v : int) : Box, got class Box

```babble
def Box = class (def v : int) ( def log = ( println(this.v) ; 1 ) )
def bad : fn (v : int) : Box = Box
```

```error
Type error : expected (v : int) : Box, got class Box
```

## Error : Type error : expected () : int, got io () : int

```babble
def p = fn io () : int -> ( println(1) ; 1 )
def f : fn () : int = p
```

```error
Type error : expected () : int, got io () : int
```

## Error : Type error : expected nothrow () : int, got io () : int

```babble
def imp = fn io () : int -> ( println(1) ; 1 )
def bad : fn nothrow () : int = imp
```

```error
Type error : expected nothrow () : int, got io () : int
```

## Error : Type error : expected () : any?, got java.lang.System.gc

```babble
def bad : fn () = java.lang.System.gc
```

```error
Type error : expected () : any?, got java.lang.System.gc
```

## Error : Type error : expected (x : int) : any?, got io (values : (any | froze...

```babble
def bad : fn (x : int) = print
```

```error
Type error : expected (x : int) : any?, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Case 19

```babble
def Self = class () ( def v = this )
def c = Self()
assert(c.v == c)
```

## Error : No such field in array of java.lang.StackTraceElement? : length (use ...

```babble
def stack = java.lang.Thread.currentThread().getStackTrace()
stack.length = 5
```

```error
No such field in array of java.lang.StackTraceElement? : length (use size())
```

## Error : No such name in array of java.lang.StackTraceElement? : missing

```babble
def stack = java.lang.Thread.currentThread().getStackTrace()
def x = stack.missing
```

```error
No such name in array of java.lang.StackTraceElement? : missing
```

## Error : Not a static field : java.awt.Point.x

```babble
def x = java.awt.Point.x
```

```error
Not a static field : java.awt.Point.x
```

## Error : Type error : expected string?, got (x : int) : int

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("a"))
st.sval = fn (x : int) -> ( x )
```

```error
Type error : expected string?, got (x : int) : int
```

## Case 24

```babble
def r : fn () = fn () -> ( )
def holder = java.lang.Thread(r)
assert(holder != null)
```
