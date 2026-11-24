# Control-flow, exception, member-field, and array bytecode lowering

References : BIP001, BIP011, BIP020, BIP028 ; targets `ControlAndOpEmitter`, `AssignAndMemberEmitter`, `HirExpr$Block`

```babble
;; babble module=ext_rec.ba
export def Pt = record (x: int, y: int) ()
```

```babble
;; babble preamble
def TopErrA = class (def code: int) ()
def TopErrB = class (def code: int) ()
```

## Non-transparent block expressions in conditions (multi-statement and local-def blocks)

```babble
def check = fn (x: int): int -> (
    def a = if ( def t = x; t > 0 ) then ( 1 ) else ( 0 )
    def b = if ( def t = true ) then ( 2 ) else ( 0 )
    def c = if ( discard(1); x == 0 ) then ( 4 ) else ( 0 )
    a + b + c
)
assert(check(3) == 3 and check(0) == 6)
```

## Unary minus, arithmetic binary, and dynamic string comparison directly in conditions

```babble
def check = fn (x: int, y: int, s1: string, s2: string): int -> (
    def r = 0
    if -x then ( r += 1 )
    if x + y then ( r += 2 )
    if s1 == s2 then ( r += 4 )
    r
)
assert(check(2, -2, "a", "a") == 5 and check(0, 1, "a", "b") == 2)
```

## Negated long, float, and double truthiness exercises branchIfTrue in emitCondition

```babble
def check = fn (l0: long, l1: long, f0: float, f1: float, d0: double, d1: double): int -> (
    def r = 0
    if not l0 then ( r += 1 )
    if not l1 then ( r += 2 )
    if not f0 then ( r += 4 )
    if not f1 then ( r += 8 )
    if not d0 then ( r += 16 )
    if not d1 then ( r += 32 )
    r
)
assert(check(0L, 5L, 0.0f, 1.5f, 0.0, 2.5) == 21)
```

## Char compared with any exercises canLowerComparison non-char right operand

```babble
def eq = fn (c: char, a: any): bool -> ( c == a )
assert(eq('a', 'a') and not eq('a', "a"))
```

## Runtime is any? lowers with null descriptor to constant true

```babble
def check = fn (x: int?): bool -> ( x is any? )
assert(check(1) and check(null))
```

## Redundant cast to any? lowers with null targetDescriptor and no SAM target

```babble
def x: int = 7
assert((x as any?) == 7)
```

```stderr
Warning : Redundant cast : any?
```

## Empty finally block and early return inside try-catch without finally

```babble
def f = fn (flag: bool): int -> (
    try (
        if flag then ( return 10 )
        20
    ) catch : java.lang.RuntimeException then ( 30 )
)
def g = try ( 42 ) finally ()
assert(f(true) == 10 and f(false) == 20 and g == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

## Multiple consecutive top-level Babble catch clauses and nameless catch with local def

```babble
def run = fn (k: int): int -> (
    try (
        if k == 1 then ( throw TopErrA(11) )
        else if k == 2 then ( throw TopErrB(22) )
        else ( throw java.io.IOException("x") )
    ) catch a: TopErrA then ( a.code )
      catch b: TopErrB then ( b.code )
      catch : java.io.IOException then ( def r = 33; r )
)
assert(run(1) == 11 and run(2) == 22 and run(3) == 33)
```

## Function-local Babble exception catch falls back to Types.matches descriptor check

```babble
def run = fn (flag: bool): string -> (
    def LocalA = class (def msg: string) ()
    def LocalB = class (def msg: string) ()
    try (
        if flag then ( throw LocalA("a") ) else ( throw LocalB("b") )
    ) catch e: LocalA then ( e.msg )
      catch e: LocalB then ( e.msg )
)
assert(run(true) == "a" and run(false) == "b")
```

## Java boolean, long-from-int, and float-from-int instance field assignments

```babble
def dpi = java.sql.DriverPropertyInfo("k", "v")
dpi.required = true
def n: int = 42
def ev = java.awt.Event(null, 0, null)
ev.when = n
def pf = java.awt.geom.Point2D.Float()
pf.x = n
assert(dpi.required and ev.when == 42L and pf.x == 42.0f)
```

## Static Java field non-pure receiver and fallback compound assign with byte RHS

```babble
def getS = fn io () -> ( import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers )
def before = getS().mutableCounter
getS().mutableCounter += byte(0)
def loc: int = 10
loc += byte(5)
assert(getS().mutableCounter == before and loc == 15)
```

## External module primitive record field get, array.toString(), and Java interface array set

```babble
def m = import("ext_rec.ba")
def p = m.Pt(3, 4)
assert(p.x == 3 and p.y == 4)
assert((array of int).of(1, 2, 3).toString() == "[1, 2, 3]")
def ops = (array of java.util.function.IntUnaryOperator).of(fn (x: int): int -> ( x + 1 ))
ops.set(0, fn (x: int): int -> ( x * 2 ))
assert(ops.get(0).applyAsInt(5) == 10)
```
