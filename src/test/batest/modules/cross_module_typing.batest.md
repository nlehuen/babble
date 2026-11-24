# Cross-module typing : exports, interfaces, super default calls, nested module imports

(LogicalPaths leading slash), unannotated/generic exports, imported Java packages,
import cycles, and duplicate nominal class IDs across modules.
References : BIP005, BIP008, TypeChecker, TypeChecks

```babble
;; babble module=ec_mod.ba
export def exportedNum = 42
export def exportedPair = java.util.AbstractMap.SimpleEntry("k", 7)
```

```babble
;; babble module=mod_tc_infer.ba
export def inc = fn (x: int) -> ( x + 1 )
export def Box of T = class (def v: T) ()
export def id of T = fn (x: T): T -> ( x )
export def optDef = fn (x = 7) -> ( x )
export def lang = import("java.lang")
```

```babble
;; babble module=cyc_a.ba
def b = import("cyc_b.ba")
export def x = b.y + 1
```

```babble
;; babble module=cyc_b.ba
def a = import("cyc_a.ba")
export def y = a.x + 1
```

```babble
;; babble module=ce_mod_a.ba
export def Dup = class (def k: int) ()
export def ExportCls = class (def n: int) (
    private def priv = fn (a: int) -> ( a + this.n )
    def pub = fn (a: int): int -> ( this.priv(a) )
)
export def x = 10
```

```babble
;; babble module=ce_mod_b.ba
export def Dup = class (def k: int) ()
export def y = 20
```

```babble
;; babble module=fe_mod_b.ba
export def IB = interface (
    def id: fn (): int
)
export def DupB = class (def v: int) ()
```

```babble
;; babble module=fe_mod_a.ba
export def sub = import("fe_mod_b.ba")
export def IA = interface (
    def get: fn (): int
)
```

```babble
;; babble module=tc_exp.ba
export def IExp = interface (
    def ping: fn (): int
    def defm = fn (): int -> ( 9 )
)
export def InnerC = class () ()
export def INested = interface (
    def pong: fn (): int
)
```

```babble
;; babble preamble
def dyn = fn (v: any?): any? -> ( v )
```

## Case 1

```babble
def m = import("tc_exp.ba")
def ILocal = interface ( def val: fn (): int )
def Impl = class () implements m.IExp, ILocal, m.INested (
    def ping = fn (): int -> ( 1 )
    def val = fn (): int -> ( 2 )
    def pong = fn (): int -> ( 3 )
)
def im = Impl()
assert(im.ping() == 1 and im.val() == 2 and im.pong() == 3)
assert(im is m.IExp and im is ILocal)
```

## Case 2

```babble
def m = import("tc_exp.ba")
def Impl = class () implements m.IExp (
    def ping = fn (): int -> ( 1 )
    def callSuper = fn (): int -> ( super(m.IExp).defm() )
)
assert(Impl().callSuper() == 9)
```

## Error : IExp.ping has no default

```babble
def m = import("tc_exp.ba")
def Bad = class () implements m.IExp (
    def ping = fn (): int -> ( super(m.IExp).ping() )
)
```

```error
IExp.ping has no default
```

## Error : Type error : expected int, got module ce_mod_a.ba

```babble
def m = import("ce_mod_a.ba")
def e3: int = m
```

```error
Type error : expected int, got module ce_mod_a.ba
```

## Error : Not nullable : m

```babble
def m = import("ce_mod_a.ba")
m?.x
```

```error
Not nullable : m
```

## Error : Cannot assign to an export : x

```babble
def m = import("ce_mod_a.ba")
m.x = 20
```

```error
Cannot assign to an export : x
```

## Error : No such export : missingExport in ce_mod_a.ba

```babble
def m = import("ce_mod_a.ba")
m.missingExport
```

```error
No such export : missingExport in ce_mod_a.ba
```

## Case 8

```babble
def m = import("ce_mod_a.ba")
def ec = m.ExportCls(5)
def out = 0
def r = object implements java.lang.Runnable (
    def run = fn io () -> ( out = ec.pub(3) )
)
r.run()
assert(out == 8)
```

## Error : Type error : expected Dup, got Dup (expected ce_mod_a.ba#Dup, got ce_...

```babble
def m1 = import("ce_mod_a.ba")
def m2 = import("ce_mod_b.ba")
def a: any = m2.Dup(3)
def bad: m1.Dup = a as m1.Dup
```

```error
Type error : expected Dup, got Dup (expected ce_mod_a.ba#Dup, got ce_mod_b.ba#Dup)
```

## Error : Missing type : x (import cycle with cyc_a.ba)

```babble
def a = import("cyc_a.ba")
a.x
```

```error
Missing type : x (import cycle with cyc_a.ba)
```

## Error : Type error : expected Dup, got Dup (expected ce_mod_b.ba#Dup, got ce_...

```babble
def m1 = import("ce_mod_a.ba")
def m2 = import("ce_mod_b.ba")
dyn(m1.Dup(1)) as m2.Dup
```

```error
Type error : expected Dup, got Dup (expected ce_mod_b.ba#Dup, got ce_mod_a.ba#Dup)
```

## Case 12

```babble
def m = import("mod_tc_infer.ba")
def b: m.Box of int = m.Box(9)
def f: fn (v: int): int = m.id
assert(m.inc(4) == 5)
assert(m.optDef() == 7)
assert(m.id("ok") == "ok")
assert(b.v == 9)
assert(f(8) == 8)
```

## Case 13

```babble
def lang = import("java.lang")
def MyRun = class (def mut ran: bool) implements lang.Runnable (
    def run = fn mut () -> ( this.ran = true )
)
def r = MyRun(false)
r.run()
def sb: lang.StringBuilder = lang.StringBuilder("hi")
def it: java.util.Iterator of int = java.util.Collections.emptyIterator()
assert(r.ran and sb.toString() == "hi" and not it.hasNext())
```

## Case 14

```babble
def m = import("ec_mod.ba")
assert(m.exportedNum == 42)
assert(m.exportedPair.getValue() == 7)
```

## Case 15

```babble
def I = interface ( def f: fn (): int )
def ma = import("/fe_mod_a.ba")
def C2 = class (def n: int) implements I, ma.sub.IB, java.lang.Runnable (
    def f = fn (): int -> ( this.n )
    def id = fn (): int -> ( this.n * 2 )
    def run = fn () -> ( () )
)
def c2 = C2(4)
c2.run()
assert(c2.f() == 4)
assert(c2.id() == 8)
```
