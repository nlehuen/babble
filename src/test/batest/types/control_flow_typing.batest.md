# Control-flow typing : `if`/`else` and `while` expressions, unused branches,

missing `else`, `int` -> `double` widening in branches/returns, union return inference,
and early `return`.
References : BIP008 (static typing, unions, nullables), TypeChecker

## Case 1

```babble
def pickIf = fn (flag : bool) : double -> (
    discard(if flag then ( 1 ) else ( "ok" ))
    def d : double = if flag then ( 1 ) else ( 2.5 )
    d
)
def unionIf = fn (flag : bool) -> ( if flag then ( 1 ) else ( "a" ) )
assert(pickIf(true) == 1.0 and pickIf(false) == 2.5)
assert(unionIf(true) == 1 and unionIf(false) == "a")
```

## Case 2

```babble
def runWhile = fn () : double? -> (
    def k = 0
    while k < 2 then ( k += 1 ; 99 )
    def wk = 0
    def wd : double? = while wk < 2 then ( wk += 1 ; 42 )
    wd
)
assert(runWhile() == 42.0)
```

## Case 3

```babble
def unionWhile = fn (k : int) -> ( while k < 2 then ( k += 1 ; 42 ) )
def w : double? = while false then ( 1 )
while false then ( 1 )
assert(unionWhile(0) == 42 and unionWhile(5) == null)
assert(w == null)
```

## Error : Type error : expected int, got null

```babble
def missElse = fn (flag : bool) : int -> ( if flag then ( 1 ) )
missElse(false)
```

```error
Type error : expected int, got null
```

## Error : Type error : expected int, got string

```babble
def badIfTyped = fn (flag : bool) : int -> ( if flag then ( 1 ) else ( "a" ) )
badIfTyped(false)
```

```error
Type error : expected int, got string
```

## Error : Type error : expected int, got null (2)

```babble
def badWhileExp = fn (k : int) : int -> ( while k < 2 then ( k += 1 ; 42 ) )
badWhileExp(0)
```

```error
Type error : expected int, got null
```

## Error : Type error : expected int, got null (3)

```babble
def w : int = while false then ( 1 )
```

```error
Type error : expected int, got null
```

## Case 8

```babble
def f = fn (b : bool) : double -> (
    if b then ( return 1 )
    2.5
)
def withEarlyRet = fn (n : int) -> (
    if n < 0 then ( return 0.5 )
    n
)
def f1 = fn (x : int) : double -> ( return x )
assert(f(true) == 1.0 and f(false) == 2.5)
assert(withEarlyRet(-1) == 0.5 and withEarlyRet(3) == 3.0)
assert(f1(3) == 3.0)
```

## Case 9

```babble
def r1 = fn () -> ( return null )
def r2 = fn () : any? -> ( return null )
assert(r1() == null and r2() == null)
```

## Case 10

```babble
def pick = fn (flag : bool) -> (
    if flag then ( return 1 ) else ( return "a" )
)
assert(pick(true) == 1 and pick(false) == "a")
```

## Case 11

```babble
def pick = fn (flag : bool, x : int) : double -> (
    x = 10
    if flag then ( return x ) else ( return 2.5 )
)
def apply of T = fn (a : T, f : fn () : T) : T -> ( f() )
assert(pick(true, 1) == 10.0)
assert(pick(false, 1) == 2.5)
assert(apply(2.5, fn () -> ( 3 )) == 3.0)
```
