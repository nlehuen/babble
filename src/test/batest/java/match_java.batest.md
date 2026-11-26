# Pattern matching on Java enums (`BIP038`)

References : `BIP038` §4.2, §7.4 (AOT-resilient Java `enum` switch-map dispatch, OR patterns, and `else`).

## Java enum switch-map dispatch and OR patterns

```babble
def D = java.time.DayOfWeek
def label = fn (d: java.time.DayOfWeek): string -> (
    match d
    case D.SATURDAY | D.SUNDAY then ("wknd")
    case D.MONDAY then ("mon")
    case D.TUESDAY then ("tue")
    case D.WEDNESDAY then ("wed")
    else ("other")
)
assert(label(D.MONDAY) == "mon")
assert(label(D.FRIDAY) == "other")
assert(label(D.SUNDAY) == "wknd")
```

## Nullable Java enum matching null and constants

```babble
def D = java.time.DayOfWeek
def name = fn (d: java.time.DayOfWeek?): string -> (
    match d
    case null then ("none")
    case D.MONDAY then ("mon")
    case D.TUESDAY then ("tue")
    case D.WEDNESDAY then ("wed")
    else ("rest")
)
assert(name(null) == "none")
assert(name(D.MONDAY) == "mon")
assert(name(D.FRIDAY) == "rest")
```

## Java record destructuring (positional and named)

```babble
def an = import("org.babblelang.analysis")
def T = type an.LoweringTargets.RangeTarget
def RT = an.LoweringTargets.RangeTarget
def PK = an.PrimitiveKind
def f = fn (r: T): string -> (
    match r
    case is T(PK.INT, false) then ("i")
    case is T(stringElement: true) then ("s")
    else ("o")
)
assert(f(RT(PK.INT, false)) == "i" and f(RT(PK.NONE, true)) == "s")
```

## Exhaustive Java enum without else

```babble
def AM = java.nio.file.AccessMode
def code = fn (m: java.nio.file.AccessMode): int -> (
    match m
    case AM.READ then (1)
    case AM.WRITE then (2)
    case AM.EXECUTE then (4)
)
assert(code(AM.READ) == 1 and code(AM.WRITE) == 2 and code(AM.EXECUTE) == 4)
```

## Error : non-exhaustive Java enum names missing constant

```babble
def AM = java.nio.file.AccessMode
def code = fn (m: java.nio.file.AccessMode): int -> (
    match m
    case AM.READ then (1)
    case AM.WRITE then (2)
)
```

```error
# error regex
.*Non-exhaustive match : missing AccessMode\.EXECUTE.*
```

## Exhaustive Java sealed hierarchy with Java records

```babble
def pkg = import("org.babblelang.tests")
def E = type pkg.EffectsTestCase.SealedExpr
def N = type pkg.EffectsTestCase.SealedNum
def A = type pkg.EffectsTestCase.SealedAdd
def eval = fn (e: E): int -> (
    match e
    case is N(def v) then (v)
    case is A(def l, def r) then (l + r)
)
assert(eval(pkg.EffectsTestCase.SealedNum(7)) == 7)
assert(eval(pkg.EffectsTestCase.SealedAdd(3, 9)) == 12)
```

## Error : non-exhaustive Java sealed hierarchy names missing subtype

```babble
def pkg = import("org.babblelang.tests")
def E = type pkg.EffectsTestCase.SealedExpr
def N = type pkg.EffectsTestCase.SealedNum
def eval = fn (e: E): int -> (
    match e
    case is N(def v) then (v)
)
```

```error
# error regex
.*Non-exhaustive match : missing SealedAdd.*
```

## Nullable Java enum switch and mixed enum values on any

```babble
def t = import("java.time")
def d = fn (x: t.DayOfWeek?): int -> (match x case null then (0) case t.DayOfWeek.MONDAY then (1) case t.DayOfWeek.TUESDAY then (2) case t.DayOfWeek.WEDNESDAY then (3) else (4))
def m = fn (x: any): int -> (match x case t.DayOfWeek.MONDAY then (1) case t.Month.JANUARY then (2) else (0))
assert(d(null) == 0 and d(t.DayOfWeek.TUESDAY) == 2 and m(t.Month.JANUARY) == 2)
```
