# Function calls, first-class functions, arity checks, call-site inference, and TypedCallCache

References : BIP001 (calls), BIP008 (P7 call-site inference), BIP021 (optional params), TypedCallCache

```babble
;; babble preamble
def dyn = fn (v : any) : any -> ( v )
def callIntOpt = fn (func : fn (x : int) : int?, v : int) : int? -> ( func(v) )
```

## Case 1

```babble
def applyTwice = fn (f : fn (n : int) : int, x : int) : int -> ( f(f(x)) )
def inc = fn (n : int) : int -> ( n + 1 )
def immediate = (fn (x : int) : int -> ( x * 3 ))(7)
assert(applyTwice(inc, 10) == 12)
assert(immediate == 21)
```

## Explicit output test : void-returning println evaluates to null

```babble
def ping = fn io () : any? -> ( println("pong") )
def r = ping()
assert(r == null)
```

```stdout
pong
```

## Error : Missing parameter : b

```babble
def add = fn (a : int, b : int) : int -> ( a + b )
print(add(1))
```

```error
Missing parameter : b
```

## Error : Too many parameters : 1 declared, 2 given

```babble
def id = fn (a : int) : int -> ( a )
print(id(1, 2))
```

```error
Too many parameters : 1 declared, 2 given
```

## Error : 42 is not callable

```babble
print(42())
```

```error
42 is not callable
```

## Error : func is not callable

```babble
def callAny = fn (func : any) -> ( func(1) )
print(callAny(dyn(42)))
```

```error
func is not callable
```

## BIP008-I7 call-site parameter inference widens int call to double

```babble
def mul2 = fn (x) -> ( x * 2 )
assert(mul2(3) == 6.0)
assert("" + classof(mul2(3)) == "java.lang.Double")
assert(mul2(1.5) == 3.0)
```

## Error : Not a number : x

```babble
def mul2 = fn (x) -> ( x * 2 )
print(mul2("bad"))
```

```error
Not a number : x
```

## Error : Type error : expected int, got string

```babble
def add1 = fn (x) -> ( x + 1 )
assert(add1(2) == 3)
print(add1("bad"))
```

```error
Type error : expected int, got string
```

## Non-primitive ($direct-only, hasSpec == false) optional params and recursive default

```babble
def join = fn (a : string, b : string = "!") : string -> ( a + b )
def repeat = fn (s : string, n : int = 2) : string -> ( if n <= 0 then ( "" ) else ( s + recurse(s, n - 1) ) )
assert(join("hi") == "hi!" and join("yo", "?") == "yo?" and repeat("a") == "aa")
```

## Non-primitive ($direct-only) recursion with Java reference type

```babble
def noPrimRec = fn io (s : string) : string -> (
    if java.lang.StringBuilder(s).length() >= 3 then ( s ) else ( recurse(s + "x") )
)
assert(noPrimRec("z") == "zxx")
```

## TypedCallCache compatibleSpec return/param mismatch and megamorphic (>4 callees) at one call site

```babble
def f0 = fn (x: int): int -> ( x + 1 )
def f1 = fn (x: int?): int? -> ( if x == null then ( 0 ) else ( x + 2 ) )
def f2 = fn (x: int): int? -> ( x + 3 )
def f3 = fn (x: int): int? -> ( x + 4 )
def f4 = fn (x: int): int? -> ( x + 5 )
def f5 = fn (x: int): int? -> ( x + 6 )
var t = 0
for f in (list of fn (int): int?).of(f0, f1, f2, f3, f4, f5, f0) then ( t += callIntOpt(f, 1)! )
assert(t == 29)
```

## Function value string representation and contravariant nullable parameter widening

```babble
def f0 = fn (x : int) : int -> ( x + 1 )
def widenParam = fn (func : fn (x : int) : int, v : int) : int -> ( func(v) )
def gNullable = fn (x : int?) : int -> ( if x == null then ( 0 ) else ( x + 10 ) )
assert("" + f0 == "function f0")
assert(widenParam(gNullable, 5) == 15)
```

## Dynamic bound method calls through TypedCallCache (IS_BOUND_METHOD / incompatible spec)

```babble
def C = class (def base : int) (
    def fInt = fn (x : int) : int -> ( this.base + x )
    def fOpt = fn (x : int, d : int = 5) : int -> ( this.base + x + d )
    def fStr = fn (s : string) : string -> ( s + this.base )
)
def c = C(10)
def dF = c.fInt ; def dO = c.fOpt ; def dS = c.fStr
def box : fn (int) : int? = dF
assert(dF(2) == 12 and dO(3) == 18 and dS("v=") == "v=10" and box(4) == 14)
```

## Bound closure (IS_BOUND_FUNCTION) through TypedCallCache with exact and widened return types

```babble
def fOuter = fn (a : int) -> (
    def inner = fn (x : int) : int -> ( a + x )
    inner
)
def g1 = fOuter(10)
def g2 : fn (x : int) : int? = fOuter(20)
assert(callIntOpt(g1, 1) == 11)
assert(callIntOpt(g2, 2) == 22)
```

## Error : Missing parameter : c

```babble
assert()
```

```error
Missing parameter : c
```

## Error : Too many parameters : 2 declared, 3 given

```babble
assert(true, "a", "b")
```

```error
Too many parameters : 2 declared, 3 given
```

## Error : Type error : expected bool, got any

```babble
def v : any = 123
assert(v)
```

```error
Type error : expected bool, got any
```

## Error : Missing parameter : b (2)

```babble
print(same(1))
```

```error
Missing parameter : b
```
