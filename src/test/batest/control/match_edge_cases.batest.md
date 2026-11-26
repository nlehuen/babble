# `match` edge cases : primitive arrays, typed ranges, relational, constant folding & SROA (`BIP038`)

References : `BIP038` §4.1–§4.4, §7.3–§7.5 (membership and slicing across all primitive array types, typed/stepped/float/string ranges, relational ordering, `-O1` constant-scrutinee folding, multi-site inlining, and `ScalarReplacePass` SROA on `match RecordNew`).

```babble
;; babble preamble
def Circle = record (r: int) ()
def Rect = record (w: int, h: int) ()
def Pair = record (a: int, b: int) ()
```

## Membership in byte, short, and long arrays

```babble
def ba = array of byte.of(1, 2)
def sa = array of short.of(10, 20)
def la = array of long.of(100L, 200L)
def m = fn volatile (a: byte, b: short, c: long): int -> (
    (match a case in ba then (1) else (0)) + (match b case in sa then (1) else (0)) + (match c case in la then (1) else (0))
)
assert(m(2, 10, 200L) == 3 and m(9, 99, 999L) == 0)
```

## Membership in float and double arrays

```babble
def fa = array of float.of(1.5f)
def da = array of double.of(3.5)
def m = fn volatile (d: float, e: double): int -> (
    (match d case in fa then (1) else (0)) + (match e case in da then (1) else (0))
)
assert(m(1.5f, 3.5) == 2 and m(9.0f, 9.0) == 0)
```

## Membership in bool, char, and string arrays

```babble
def qa = array of bool.of(true)
def ca = array of char.of('x')
def ta = array of string.of("hi")
def m = fn volatile (f: bool, g: char, h: string): int -> (
    (match f case in qa then (1) else (0)) + (match g case in ca then (1) else (0)) + (match h case in ta then (1) else (0))
)
assert(m(true, 'x', "hi") == 3 and m(false, 'z', "no") == 0)
```

## Fresh slices on byte, short, and long arrays

```babble
def sl = fn (ba: array of byte, sa: array of short, la: array of long): int -> (
    (match ba case is array(_, ...def t) then (t.size()) else (0)) +
    (match sa case is array(_, ...def t) then (t.size()) else (0)) +
    (match la case is array(_, ...def t) then (t.size()) else (0))
)
assert(sl(array of byte.of(1, 2), array of short.of(1, 2), array of long.of(1L, 2L)) == 3)
```

## Fresh slices on float, double, and bool arrays

```babble
def sl = fn (fa: array of float, da: array of double, qa: array of bool): int -> (
    (match fa case is array(_, ...def t) then (t.size()) else (0)) +
    (match da case is array(_, ...def t) then (t.size()) else (0)) +
    (match qa case is array(_, ...def t) then (t.size()) else (0))
)
assert(sl(array of float.of(1.0f, 2.0f), array of double.of(1.0, 2.0), array of bool.of(true, false)) == 3)
```

## Fresh slices on char and string arrays

```babble
def sl = fn (ca: array of char, ta: array of string): int -> (
    (match ca case is array(_, ...def t) then (t.size()) else (0)) +
    (match ta case is array(_, ...def t) then (t.size()) else (0))
)
assert(sl(array of char.of('a', 'b'), array of string.of("a", "b")) == 2)
```

## Long and float range patterns

```babble
def lRng = fn (x: long): int -> (match x case (0L..10L).step(2L) then (1) case 0L..<10L then (2) else (0))
def fRng = fn (x: float): int -> (match x case 0.0f..<1.0f then (1) case 1.0f..2.0f then (2) else (0))
assert(lRng(4L) == 1 and lRng(5L) == 2 and lRng(11L) == 0 and fRng(0.5f) == 1 and fRng(1.0f) == 2)
```

## Double range patterns

```babble
def dRng = fn (x: double): int -> (match x case 0.0..<1.0 then (1) case 1.0..2.0 then (2) else (0))
assert(dRng(0.5) == 1 and dRng(2.0) == 2 and dRng(-1.0) == 0)
```

## Char stepped and string range patterns

```babble
def cRng = fn (x: char): int -> (match x case ('a'..'g').step(2) then (1) case 'a'..<'g' then (2) else (0))
def sRng = fn (x: string): int -> (match x case "a"..<"m" then (1) case "m".."z" then (2) else (0))
assert(cRng('c') == 1 and cRng('b') == 2 and cRng('z') == 0)
assert(sRng("cat") == 1 and sRng("m") == 2 and sRng("zz") == 0)
```

## Dynamic membership patterns on any containers

```babble
def inAny = fn volatile (x: any, c: any): bool -> (match x case in c then (true) else (false))
assert(inAny(3, 1..5) and not inAny(9, 1..5))
assert(inAny(2, list(1, 2, 3)) and inAny("k", map(entry("k", 1))))
assert(inAny(7, array of int.of(7, 8)) and not inAny(9, array of int.of(7, 8)))
```

## Relational patterns across long and float

```babble
def relL = fn (x: long): int -> (match x case < 0L then (-1) case <= 10L then (1) case >= 100L then (3) case > 10L then (2) else (0))
def relF = fn (x: float): int -> (match x case < 0.0f then (-1) case >= 1.0f then (1) else (0))
assert(relL(-1L) == -1 and relL(5L) == 1 and relL(50L) == 2 and relL(100L) == 3)
assert(relF(-1.0f) == -1 and relF(0.5f) == 0 and relF(2.0f) == 1)
```

## Relational patterns across char and string

```babble
def relC = fn (c: char): int -> (match c case < 'm' then (-1) case > 'm' then (1) else (0))
def relS = fn (s: string): int -> (match s case < "m" then (-1) case > "m" then (1) else (0))
assert(relC('a') == -1 and relC('m') == 0 and relC('z') == 1)
assert(relS("a") == -1 and relS("m") == 0 and relS("z") == 1)
```

## Constant scrutinee folding for relational, stepped range, and OR

```babble
def fold1 = fn (): int -> (
    def a = match 5 case < 3 then (1) case != 5 then (2) case == 5 then (10) else (0)
    def b = match 6 case (0..10).step(2) then (20) else (0)
    def c = match 1.5 case 0.0..<1.0 then (1) case 1.0..2.0 then (30) else (0)
    a + b + c
)
assert(fold1() == 60)
```

## Constant scrutinee folding for char/string range, null, and else def

```babble
def fold2 = fn (): int -> (
    def d = match 'e' case ('a'..'g').step(2) then (40) else (0)
    def e = match "cat" case "a"..<"m" then (50) else (0)
    def f = match 9 case 1 | 2 then (1) else def r then (r + 1)
    def g = match (null as int?) case null then (1) else (0)
    d + e + f + g
)
assert(fold2() == 101)
```

## SROA on match RecordNew with skipped non-matching arms

```babble
def sroaSkip = fn (r: int): int -> (
    def v: Circle | Rect | list of int | int | null = Circle(r)
    match v
    case null then (0)
    case is int then (1)
    case is list(_) then (2)
    case is Rect(def w, def h) then (w + h)
    case is Circle(def rad) then (rad * 2)
    else (99)
)
assert(sroaSkip(5) == 10)
```

## SROA on match RecordNew with type-only arm and else fallback

```babble
def sroaType = fn (r: int): int -> (
    match Circle(r)
    case is Circle then (7)
)
def sroaElse = fn (r: int): int -> (
    def s: Circle | Rect = Circle(r)
    match s
    case is Rect(def w, def h) then (w + h)
    else (11)
)
assert(sroaType(5) == 7 and sroaElse(5) == 11)
```

## SROA preserves impure argument evaluation order in match RecordNew

```babble
var log = ""
def step = fn io (tag: string, v: int): int -> (
    log += tag
    v
)
def res = match Pair(step("a", 10), step("b", 20))
case is Pair(def x, _) then (x + 1)
assert(res == 11 and log == "ab")
```

## Multi-site inlining of small match helpers with bindings and rest slices

```babble
def tag = fn (v: int | string): int -> (
    match v
    case def n: int if n > 0 then (n * 2)
    case is int then (0)
    else def s then (s.length())
)
def tl = fn (xs: list of int): int -> (match xs case is list(_, ...def t) then (t.size()) else (0))
assert(tag(5) + tag("hello") + tag(-3) + tl(list(1, 2, 3)) + tl(list(9)) == 17)
```

## Non-switch OR patterns, nullable string switch, and byte switch

```babble
def orP = fn (x: int): int -> (match x case < 0 | > 10 then (1) else (0))
def sN = fn (s: string?): int -> (match s case null then (-1) case "a" then (1) case "b" then (2) case "c" then (3) else (0))
def bS = fn (b: byte): int -> (match b case 0 then (10) case 1 then (20) case 2 then (30) else (0))
assert(orP(-1) == 1 and orP(5) == 0 and sN(null) == -1 and sN("b") == 2 and bS(2) == 30)
```

## Dynamic value equality across long and double

```babble
def eL = fn (x: long, t: long): bool -> (match x case t then (true) else (false))
def eD = fn (x: double, t: double): bool -> (match x case t then (true) else (false))
assert(eL(5L, 5L) and not eL(5L, 6L) and eD(1.5, 1.5) and not eD(1.5, 2.5))
```

## Dynamic value equality across char and string

```babble
def eC = fn (x: char, t: char): bool -> (match x case t then (true) else (false))
def eS = fn (x: string, t: string): bool -> (match x case t then (true) else (false))
assert(eC('a', 'a') and not eC('a', 'b') and eS("a", "a") and not eS("a", "b"))
```

## Dynamic sequence and record destructuring on any with rest and binding

```babble
def f = fn (v: any): int -> (
    match v
    case def xs: list(def a, ...def t) then (xs.size() + t.size())
    case def s: array(def a, ...def t) then (match t case is array of int(def b) then (b) else (0))
    case def p: Circle(def r) then (r + p.r)
    else (0)
)
assert(f(list(1, 2, 3)) == 5 and f(array of int.of(4, 5)) == 5 and f(Circle(6)) == 12 and f("x") == 0)
```

## Dynamic stepped range pattern and union OR type pattern

```babble
def dynStep = fn (x: int, k: int): bool -> (match x case (0..10).step(k) then (true) else (false))
def orType = fn (v: Circle | Rect | int): int -> (
    match v
    case is Circle | is Rect then (1)
    case is int then (2)
)
assert(dynStep(6, 3) and not dynStep(5, 3))
assert(orType(Circle(1)) == 1 and orType(Rect(1, 2)) == 1 and orType(9) == 2)
```

## Non-primitive inequality, char relational, and dynamic bool match

```babble
def neS = fn (s: string): int -> (match s case != "ok" then (1) else (0))
def chR = fn (c: char): int -> (match c case < 'm' then (1) else (0))
def eqB = fn (a: bool, b: bool): int -> (match a case b then (1) else (0))
assert(neS("no") == 1 and neS("ok") == 0 and chR('a') == 1 and chR('z') == 0 and eqB(true, true) == 1)
```

## String constant switch on any

```babble
def sA = fn (v: any): int -> (match v case "a" then (1) case "b" then (2) case "c" then (3) else (0))
assert(sA("a") == 1 and sA("b") == 2 and sA("c") == 3 and sA(9) == 0)
```

## Interrupted int switch runs and all-throwing match

```babble
def m = fn (x: int | double): int -> (match x case 1 then (1) case 2 then (2) case 3.5 then (3) else (0))
def g = fn (x: int): int -> (match x case 1 then (1) case 2 then (2) case 3 if x > 0 then (3) else (0))
def d = fn (x: int): int -> (match x case 0 then (throw Circle(7)) else (throw Circle(9)))
assert(m(3.5) == 3 and g(3) == 3 and (try (d(0)) catch e: Circle then (e.r)) == 7)
```
