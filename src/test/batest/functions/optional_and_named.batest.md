# Sound optional parameters (= ...), static named argument binding, keyword-only (...), and $spec mask fallback

References : BIP021 (D2/D3/D4/D22/D23, §2.2)

```babble module=m1.ba
export def OptIface = interface (
    def calc : fn (a : int, b : int = ..., ..., scale : int = ...) : int
)
export def OptImpl = class () implements OptIface (
    def calc = fn (x : int, y : int = 3, ..., factor : int = 2) : int -> ( (x + y) * factor )
)
export def helper = fn (a : int, ..., b : int = 10) : int -> ( a + b )
```

## Error : Type error : expected (x : int, y : int = ...) : int, got (x : int, y...

```babble
def f : fn (x : int, y : int = ...) : int = fn (x : int, y : int) : int -> ( x + y )
println(f(1))
```

```error
Type error : expected (x : int, y : int = ...) : int, got (x : int, y : int) : int
```

## Error : Type error : expected (x : int, y : int = ...) : int, got (x : int, y... (2)

```babble
def I = interface (
    def m : fn (x : int, y : int = ...) : int
)
def C = class () implements I (
    def m = fn (x : int, y : int) : int -> ( x + y )
)
```

```error
Type error : expected (x : int, y : int = ...) : int, got (x : int, y : int) : int
```

## Renamed parameter on function value binds by static type (BIP021 §2.2 (d))

```babble
def f : fn (x : int, y : int = ...) : int = fn (a : int, b : int = 10) : int -> ( a * 100 + b )
assert(f(x: 3) == 310)
assert(f(y: 7, x: 3) == 307)
assert(f(3, y: 7) == 307)
```

## Renamed parameter on interface method binds by static type (BIP021 §2.2 (e))

```babble
def I = interface (
    def m : fn (x : int, y : int = ...) : int
)
def C = class () implements I (
    def m = fn (a : int, b : int = 5) : int -> ( a * 10 + b )
)
def i : I = C()
assert(i.m(x: 2) == 25)
assert(i.m(y: 9, x: 2) == 29)
```

## Error : Required parameter after an optional one : b

```babble
def f = fn (a : int = 1, b : int) : int -> ( a + b )
```

```error
Required parameter after an optional one : b
```

## Error : Required parameter after an optional one : b (2)

```babble
def C = class (a : int = 1, b : int) ()
```

```error
Required parameter after an optional one : b
```

## Error : Required parameter after an optional one : b (3)

```babble
def f : fn (a : int = ..., b : int) : int = fn (a : int = 1, b : int = 2) : int -> ( a + b )
```

```error
Required parameter after an optional one : b
```

## Error : Named argument out of position : b

```babble
def f = fn (a : int, b : int, c : int) : int -> ( a + b + c )
f(b: 2, 1, 3)
```

```error
Named argument out of position : b
```

## Positional argument after in-position named argument is allowed (BIP021-D23)

```babble
def f = fn (a : int, b : int, c : int) : int -> ( a * 100 + b * 10 + c )
assert(f(a: 1, 2, 3) == 123)
assert(f(1, b: 2, 3) == 123)
```

## Error : Parameter given twice : a

```babble
def f = fn (a : int, b : int) : int -> ( a + b )
f(1, a: 2)
```

```error
Parameter given twice : a
```

## Error : Parameter given twice : a (2)

```babble
def f = fn (a : int, b : int) : int -> ( a + b )
f(a: 1, a: 2)
```

```error
Parameter given twice : a
```

## Error : Too many parameters : 1 declared, 2 given

```babble
def f = fn (a : int, ..., b : int) : int -> ( a + b )
f(1, 2)
```

```error
Too many parameters : 1 declared, 2 given
```

## Error : Type error : expected (a : int, b : int) : int, got (a : int, ..., b ...

```babble
def f : fn (a : int, b : int) : int = fn (a : int, ..., b : int) : int -> ( a + b )
```

```error
Type error : expected (a : int, b : int) : int, got (a : int, ..., b : int) : int
```

## Positional callee satisfies keyword-only function type

```babble
def f : fn (a : int, ..., b : int = ...) : int = fn (x : int, y : int = 20) : int -> ( x + y )
assert(f(3) == 23)
assert(f(3, b: 4) == 7)
assert(f(b: 5, a: 6) == 11)
```

## Evaluation order preserved left-to-right when named arguments are reordered

```babble
def sb = java.lang.StringBuilder()
def log = fn io (s : string, v : int) : int -> ( sb.append(s) ; v )
def calc = fn (a : int, b : int, c : int = 0) : int -> ( a * 100 + b * 10 + c )
def r = calc(c: log("c", 3), b: log("b", 2), a: log("a", 1))
assert(sb.toString() == "cba")
assert(r == 123)
```

## Case 16

```babble
def m = import("m1.ba")
def obj : m.OptIface = m.OptImpl()
assert(obj.calc(4) == 14)
assert(obj.calc(4, 5) == 18)
assert(obj.calc(scale: 10, a: 2) == 50)
assert(obj.calc(b: 6, a: 1, scale: 3) == 21)
assert(m.helper(5) == 15)
assert(m.helper(5, b: 7) == 12)
```

## REPL session persistence of optional and keyword-only signatures

```babble session
def f = fn (a : int, b : int = 10, ..., c : int = 100) : int -> ( a + b + c )
```

```babble session
assert(f(1) == 111)
assert(f(1, 2) == 103)
assert(f(1, c: 200) == 211)
assert(f(c: 300, a: 5) == 315)
```

## 33 optional parameters (exceeds 32-bit $spec mask, exercises boxed fallback)

```babble
def big = fn (
    a = 1, b = 1, c = 1, d = 1, e = 1, f = 1, g = 1, h = 1, i = 1, j = 1, k = 1,
    l = 1, m = 1, n = 1, o = 1, p = 1, q = 1, r = 1, s = 1, t = 1, u = 1, v = 1,
    w = 1, x = 1, y = 1, z = 1, A = 1, B = 1, C = 1, D = 1, E = 1, F = 1, G = 1
) -> (
    a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p+q+r+s+t+u+v+w+x+y+z+A+B+C+D+E+F+G
)
assert(big() == 33)
assert(big(G: 20, a: 10) == 61)
```

## Interface with optional parameters implemented by class with extra optional parameters (remapMask)

```babble
def Adder = interface (
    def add : fn (a : int, b : int = ...) : int
)
def Plus = class () implements Adder (
    def add = fn (a : int, b : int = 10, c : int = 100) : int -> ( a + b + c )
)
def run = fn (x : Adder) -> (
    assert(x.add(1) == 111)
    assert(x.add(1, 2) == 103)
    assert(x.add(b: 5, a: 2) == 107)
)
run(Plus())
```

## Indirect function calls with optional parameters (monomorphic & polymorphic call sites)

```babble
def f1 = fn (a : int, b : int = 10) : int -> ( a + b )
def f2 = fn (a : int, b : int = 20, c : int = 100) : int -> ( a + b + c )
def invoke = fn (f : fn (a : int, b : int = ...) : int) : string -> (
    "" + f(1) + ":" + f(1, 2)
)
assert(invoke(f1) == "11:3")
assert(invoke(f2) == "121:103")
```

## assert with explicit null message

```babble
assert(false, null)
```

```error
Assertion failed : false
```

## Class and record with keyword-only parameters (ClassShape keywordOnlyStart encode/decode)

```babble
def Box = class (def a : int, ..., def b : int = 10, def c : string = "ok") ()
def Pt = record (x : int, ..., y : int = 5) ()
def b1 = Box(1)
def b2 = Box(2, c: "hi", b: 20)
assert(b1.a == 1 and b1.b == 10 and b1.c == "ok")
assert(b2.a == 2 and b2.b == 20 and b2.c == "hi")
assert(string(Pt(3)) == "Pt(x: 3, y: 5)")
assert(string(Pt(3, y: 9)) == "Pt(x: 3, y: 9)")
```

## Dependent default parameter evaluation with positional and named calls

```babble
def fmt = fn (a : int, b : int = 10, c : int = a + b) : string -> (
    "" + a + ":" + b + ":" + c
)
assert(fmt(1) == "1:10:11")
assert(fmt(1, 2) == "1:2:3")
assert(fmt(1, 2, 3) == "1:2:3")
assert(fmt(c: 99, a: 2) == "2:10:99")
assert(fmt(5, c: 20) == "5:10:20")
```

## Function with defaulted parameters passed through a higher-order function parameter

```babble
def invoke = fn (func : fn (x : int, y : int, z : int) : string) : string -> (
    func(10, 20, 30)
)
def fmt = fn (a : int = 1, b : int = 2, c : int = 3) : string -> ( "" + a + "-" + b + "-" + c )
assert(invoke(fmt) == "10-20-30")
```

## Unknown named argument reports no such parameter

```babble
def f = fn (a : int, b : int = 1) : int -> ( a + b )
print(f(1, z: 2))
```

```error
No such parameter : z
```

## Omitting required parameter when using named argument reports missing parameter

```babble
def f = fn (a : int, b : int, c : int = 5) : int -> ( a + b + c )
print(f(a: 1))
```

```error
Missing parameter : b
```

## Function with defaulted trailing parameter satisfies shorter function type

```babble
def f = fn (a : int, b : int = a * 2) : int -> ( a + b )
def indirect : fn (a : int) : int = f
assert(indirect(4) == 12)
```

## Two-required-parameter function rejected for one-parameter function type

```babble
def f = fn (a : int, b : int) : int -> ( a + b )
def indirect : fn (a : int) : int = f
```

```error
Type error : expected (a : int) : int, got (a : int, b : int) : int
```

