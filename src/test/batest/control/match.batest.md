# Expression-oriented `match` : values, constants, types, ranges, membership, and guards

References : `BIP038` (pattern matching core: values, constants, jump-table runs, type tests, OR-patterns, relational, `BIP031` ranges, `in` membership, `if` guards, `else` / `else def`, narrowing, tail calls, closures, and static/runtime diagnostics).

```babble
;; babble preamble
def Animal = interface (
    def sound: fn (): string
)

def Named = interface (
    def name: fn (): string
)

def Dog = record (n: string) implements Animal, Named (
    def sound = fn (): string -> ("woof")
    def name = fn (): string -> (this.n)
)

def Cat = record (n: string) implements Animal (
    def sound = fn (): string -> ("meow")
)
```

## Integer dense switch and else

```babble
def classify = fn (n: int): string -> (
    match n
    case 0 then ("zero")
    case 1 then ("one")
    case 2 then ("two")
    case 3 then ("three")
    case 4 then ("four")
    else ("other")
)
assert(classify(0) == "zero")
assert(classify(2) == "two")
assert(classify(4) == "four")
assert(classify(-1) == "other")
assert(classify(99) == "other")
```

## Integer sparse lookup switch with OR patterns

```babble
def http = fn (code: int): string -> (
    match code
    case 200 | 201 | 204 then ("ok")
    case 301 | 302 then ("redir")
    case 404 then ("miss")
    case 500 | 502 | 503 then ("err")
    else def c then ("c:" + c)
)
assert(http(201) == "ok" and http(302) == "redir")
assert(http(404) == "miss" and http(503) == "err" and http(418) == "c:418")
```

## Char switch and OR patterns

```babble
def kind = fn (c: char): string -> (
    match c
    case 'a' | 'e' | 'i' | 'o' | 'u' then ("vowel")
    case '0'..'9' then ("digit")
    case ' ' | '\t' | '\n' then ("space")
    else ("other")
)
assert(kind('e') == "vowel")
assert(kind('7') == "digit")
assert(kind('\n') == "space")
assert(kind('z') == "other")
```

## String hash-switch with OR patterns

```babble
def role = fn (s: string): int -> (
    match s
    case "admin" | "root" then (100)
    case "mod" then (50)
    case "user" then (10)
    case "guest" then (1)
    else (0)
)
assert(role("root") == 100)
assert(role("mod") == 50)
assert(role("user") == 10)
assert(role("guest") == 1)
assert(role("other") == 0)
```

## String hash collision bucket disambiguation

```babble
def collide = fn (s: string): string -> (
    match s
    case "Aa" then ("Aa")
    case "BB" then ("BB")
    case "Cc" then ("Cc")
    case "DD" then ("DD")
    else ("none")
)
assert(collide("Aa") == "Aa")
assert(collide("BB") == "BB")
assert(collide("Cc") == "Cc")
assert(collide("DD") == "DD")
assert(collide("EE") == "none")
```

## Boolean and null value patterns

```babble
def bStr = fn (b: bool): string -> (match b case true then ("T") case false then ("F"))
def nStr = fn (x: int?): string -> (match x case null then ("nil") case 0 then ("zero") else def n then ("n=" + n))
assert(bStr(true) == "T" and bStr(false) == "F")
assert(nStr(null) == "nil" and nStr(0) == "zero" and nStr(7) == "n=7")
```

## Double and long value patterns

```babble
def dStr = fn (d: double): string -> (match d case 0.0 then ("z") case 1.5 then ("h") else ("o"))
def lStr = fn (n: long): string -> (match n case 0L then ("z") case 10L then ("t") else ("o"))
assert(dStr(0.0) == "z" and dStr(1.5) == "h" and dStr(2.5) == "o")
assert(lStr(0L) == "z" and lStr(10L) == "t" and lStr(5L) == "o")
```

## Scrutinee evaluated exactly once

```babble
var calls = 0
def get = fn io (v: int): int -> (
    calls += 1
    v
)
def res = match get(2)
case 0 then ("a")
case 1 then ("b")
case 2 then ("c")
else ("d")
assert(res == "c")
assert(calls == 1)
```

## Runtime value expressions in patterns

```babble
def matchTarget = fn (x: int, target: int): string -> (
    match x
    case target then ("exact")
    case target + 1 then ("next")
    case target - 1 then ("prev")
    else ("far")
)
assert(matchTarget(10, 10) == "exact")
assert(matchTarget(11, 10) == "next")
assert(matchTarget(9, 10) == "prev")
assert(matchTarget(20, 10) == "far")
```

## Type tests, typed bindings, and subtraction narrowing

```babble
def describe = fn (v: int | string | null): string -> (
    match v
    case null then ("none")
    case def n: int then ("int:" + (n + 1))
    case def s: string then ("str:" + s.length())
)
assert(describe(null) == "none")
assert(describe(41) == "int:42")
assert(describe("babble") == "str:6")
```

## Scrutinee variable narrowing across arms and else

```babble
def f = fn (v: int | string | null): string -> (
    match v
    case null then ("nil")
    case is int then ("n=" + (v + 1))
    else ("s=" + v.length())
)
assert(f(null) == "nil")
assert(f(9) == "n=10")
assert(f("hi") == "s=2")
```

## Nominal interface, intersection, and grouped union type patterns

```babble
def tag = fn (a: Animal): string -> (
    match a
    case def d: Animal & Named then (d.name() + ":" + d.sound())
    case is Cat then ("cat:" + a.sound())
    else ("unknown")
)
assert(tag(Dog("Rex")) == "Rex:woof")
assert(tag(Cat("Milo")) == "cat:meow")
```

## Relational patterns and null-safe ordering

```babble
def bucket = fn (x: int?): string -> (
    match x
    case < 0 then ("neg")
    case == 0 then ("zero")
    case <= 10 then ("small")
    case >= 100 then ("huge")
    case > 10 then ("mid")
    else ("null")
)
assert(bucket(-5) == "neg")
assert(bucket(0) == "zero")
assert(bucket(10) == "small")
assert(bucket(100) == "huge")
assert(bucket(50) == "mid")
assert(bucket(null) == "null")
```

## Relational not-equal and double ordering

```babble
def nz = fn (x: int): string -> (match x case != 0 then ("nz") else ("z"))
def dbl = fn (d: double): string -> (
    match d
    case < 0.0 then ("neg")
    case <= 10.0 then ("mid")
    else ("hi")
)
assert(nz(3) == "nz" and nz(0) == "z")
assert(dbl(-1.0) == "neg" and dbl(0.0) == "mid" and dbl(20.0) == "hi")
```

## Bare BIP031 range patterns (inclusive, half-open, stepped)

```babble
def grade = fn (n: int): string -> (
    match n
    case (0..10).step(2) then ("even-low")
    case 0..<10 then ("odd-low")
    case 10..20 then ("mid")
    else ("out")
)
assert(grade(0) == "even-low")
assert(grade(4) == "even-low")
assert(grade(10) == "even-low")
assert(grade(3) == "odd-low")
assert(grade(15) == "mid")
assert(grade(21) == "out")
```

## Range equality when scrutinee is a range

```babble
def r = 1..5
def res = match r
case 1..5 then ("same-incl")
case 1..<5 then ("excl")
else ("other")
assert(res == "same-incl")
```

## Membership patterns with in (list, set, map)

```babble
def nums = list(10, 20, 30)
def tags = set(1, 2)
def tbl = map(entry(7, "seven"))
def check = fn volatile (x: int): string -> (
    match x
    case in tags then ("set")
    case in tbl then ("map")
    case in nums then ("list")
    else ("miss")
)
assert(check(2) == "set" and check(7) == "map" and check(20) == "list" and check(99) == "miss")
```

## Membership patterns with in (range and array)

```babble
def arr = array of int.of(100, 200)
def rng = 50..60
def check = fn volatile (x: int): string -> (
    match x
    case in rng then ("range")
    case in arr then ("array")
    else ("miss")
)
assert(check(55) == "range" and check(200) == "array" and check(99) == "miss")
```

## Guards, guard fallthrough, and guard-to-body narrowing

```babble
def f = fn (v: int | string?): string -> (
    match v
    case def n: int if n > 10 then ("big:" + n)
    case def n: int if n > 0 then ("pos:" + n)
    case def x if x != null and x is string then ("str:" + x.length())
    else ("other")
)
assert(f(25) == "big:25")
assert(f(3) == "pos:3")
assert(f(-1) == "other")
assert(f("ok") == "str:2")
assert(f(null) == "other")
```

## Numeric widening across arms and expression result

```babble
def toNum = fn (k: int): double -> (
    match k
    case 1 then (1)
    case 2 then (2.5)
    else (0)
)
assert(toNum(1) == 1.0)
assert(toNum(2) == 2.5)
assert(toNum(9) == 0.0)
```

## Closure capturing pattern variable and else binding

```babble
def makeAdder = fn (v: int | string): fn (): string -> (
    match v
    case def n: int then (fn (): string -> ("n=" + (n + 1)))
    else def s then (fn (): string -> ("s=" + s))
)
assert(makeAdder(5)() == "n=6")
assert(makeAdder("hi")() == "s=hi")
```

## Self tail-call recursion through match arms

```babble
def sumDown = fn (n: int, acc: int): int -> (
    match n
    case <= 0 then (acc)
    else (recurse(n - 1, acc + n))
)
assert(sumDown(50000, 0) == 1250025000)
```

## Statement position match with break and return

```babble
def findFirst = fn (xs: list of int): int -> (
    var res = -1
    for x in xs then (
        match x
        case < 0 then (return -99)
        case 0 then (continue)
        case > 10 then (
            res = x
            break
        )
        else (null)
    )
    res
)
assert(findFirst(list(0, 3, 15, 20)) == 15)
assert(findFirst(list(0, -1, 15)) == -99)
```

## Error : top-level wildcard rejected in favor of else

```babble
def x = 1
match x
case 1 then ("one")
case _ then ("other")
```

```error
# error regex
.*Wildcard _ is not allowed as a top-level case.*
```

## Error : unguarded top-level binding rejected in favor of else def

```babble
def x = 1
match x
case 1 then ("one")
case def y then ("other")
```

```error
# error regex
.*Unconditional binding in case : use else def y then.*
```

## Error : binding inside OR pattern rejected

```babble
def x: int | string = 1
match x
case def a: int | def b: string then ("bad")
else ("ok")
```

```error
# error regex
.*Pattern binding not allowed in OR-pattern : a.*
```

## Error : assigning to pattern variable rejected

```babble
def x = 5
match x
case def n: int if n > 0 then (
    n = 10
    n
)
else (0)
```

```error
# error regex
.*Cannot assign to pattern variable : n.*
```

## Error : ambiguous range pattern on any scrutinee

```babble
def x: any = 5
match x
case 1..10 then ("hit")
else ("miss")
```

```error
# error regex
.*Ambiguous range pattern.*
```
