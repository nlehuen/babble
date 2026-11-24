# Uninitialized local, captured cell, and class member access checks

References : BIP001, BIP002 ; targets Frames.defined / checkDefined / checkAssignable, IntCell / DoubleCell / BoolCell, Bootstrap.requireMemberDefined, SelectCache

```babble preamble
def B = class (readNow : bool) (
    def readFn = fn (b : B) : int -> ( b.x )
    def x : int = if readNow then ( this.readFn(this) ) else ( 9 )
)
def readB = fn (b : B) : int -> ( b.x )

def safeRead = fn (flag : bool) : int -> (
    def a = if flag then ( x ) else ( 0 )
    def x : int = 7
    a + x
)
```

## Member used before definition in compiled class constructor

```babble
def Box = class () (
    def peek = fn () : int -> ( this.late )
    def a = this.peek()
    def late : int = 5
)
Box()
```

```error
Member used before definition : late
```

## Case 2

```babble
def ok = B(false)
assert(readB(ok) == 9)
assert(ok.readFn(ok) == 9)
```

## Warm SelectCache on B.x before triggering requireMemberDefined during construction

```babble
def ok = B(false)
assert(ok.readFn(ok) == 9)
B(true)
```

```error
Member used before definition : x
```

## SelectCache requireMemberDefined on union primitive field on 2nd call (fast path)

```babble
def UB = class (def late : int = 7) ()
def UA = class (def a : int = 1) (
    def peek = fn (u : UA | UB) : int -> ( u.late )
    def ok = this.peek(UB())
    def v = this.peek(if this.a == 1 then ( this ) else ( UB() ))
    def late : int = 5
)
UA()
```

```error
Member used before definition : late
```

## Reading captured BoolCell before definition

```babble
def f = fn () : fn () : bool -> (
    def r = fn () : bool -> ( b )
    def g = r()
    def b = true
    fn () : bool -> ( g )
)
f()
```

```error
Name used before definition : b
```

## Assigning captured BoolCell before definition

```babble
def f = fn () : bool -> (
    def w = fn () : bool -> ( b = false )
    w()
    def b = true
    b
)
f()
```

```error
Name used before definition : b
```

## Reading captured DoubleCell before definition

```babble
def f = fn () : fn () : double -> (
    def r = fn () : double -> ( d )
    def g = r()
    def d = 1.5
    fn () : double -> ( g )
)
f()
```

```error
Name used before definition : d
```

## Assigning captured DoubleCell before definition

```babble
def f = fn () : double -> (
    def w = fn () : double -> ( d = 2.5 )
    w()
    def d = 1.5
    d
)
f()
```

```error
Name used before definition : d
```

## Assigning captured IntCell before definition

```babble
def f = fn () : int -> (
    def w = fn () : int -> ( i = 9 )
    w()
    def i = 1
    i
)
f()
```

```error
Name used before definition : i
```

## Reading uncaptured reference local before definition

```babble
def readRef = fn () : string -> (
    def x = s
    def s = "hi"
    x
)
readRef()
```

```error
Name used before definition : s
```

## Assigning uncaptured reference local before definition

```babble
def assignRef = fn () : string -> (
    s = "ok"
    def s = "hi"
    s
)
assignRef()
```

```error
Name used before definition : s
```

## Case 12

```babble
assert(safeRead(false) == 7)
```

## Uncaptured int local read before definition

```babble
safeRead(true)
```

```error
Name used before definition : x
```

## Uncaptured double local read before definition

```babble
def badDouble = fn (flag : bool) : double -> (
    def a = if flag then ( x ) else ( 0.0 )
    def x : double = 2.5
    a + x
)
badDouble(true)
```

```error
Name used before definition : x
```

## Uncaptured bool local read before definition

```babble
def badBool = fn (flag : bool) : bool -> (
    def a = if flag then ( x ) else ( false )
    def x : bool = true
    a or x
)
badBool(true)
```

```error
Name used before definition : x
```

## SelectCache requireMemberDefined passes on warmed union primitive field

```babble
def UB = class (def late: int = 7) ()
def UA = class (early: bool) (
    def peek = fn (u: UA | UB): int -> ( u.late )
    def v = if early then ( this.peek(this) ) else ( 0 )
    def late: int = 5
)
def a = UA(false)
assert(a.peek(a) == 5 and a.peek(a) == 5)
```

## SelectCache requireMemberDefined throws on warmed union primitive field during construction

```babble
def UB = class (def late: int = 7) ()
def UA = class (early: bool) (
    def peek = fn (u: UA | UB): int -> ( u.late )
    def v = if early then ( this.peek(this) ) else ( 0 )
    def late: int = 5
)
def warm = UA(false)
assert(warm.peek(warm) == 5)
UA(true)
```

```error
Member used before definition : late
```
