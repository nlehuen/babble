# HIR try-expression normalization : stack spilling across call, assign, array, and widening shapes

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def addIO = fn io (a: int, b: int): int -> (a + b)
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
def Box = class (def v: int) (
    def addIO = fn io (a: int, b: int): int -> (this.v + a + b)
    def bumpCompound = fn mut io (s: string): int -> (
        this.v += try (parse(s)) catch : exception then (5)
        this.v
    )
)
def Calc = interface (def calc = fn io (a: int, b: int): int -> (a + b))
def SubCalc = class () implements Calc (
    def calc = fn io (a: int, b: int): int -> (
        super.calc(a, try (parse("" + b)) catch : exception then (0)) + 1
    )
)
```

## Binary LHS try vs RHS try spilling

```babble
def lhsTry = fn io (s: string): int -> (
    (try (parse(s)) catch : exception then (10)) + 1
)
def rhsTry = fn io (s: string): int -> (
    10 + (try (parse(s)) catch : exception then (2))
)
assert(lhsTry("4") == 5 and lhsTry("x") == 11 and rhsTry("3") == 13 and rhsTry("x") == 12)
```

```hir
;; hir -O1 fn=rhsTry check
contains (def $try0
```

## Logic short-circuit containsTry on left and right operands

```babble
def logicTry = fn io (f: bool, s: string): bool -> (
    def a = (try (parse(s) > 0) catch : exception then (false)) and f
    def b = f and (try (parse(s) > 0) catch : exception then (false))
    a and b
)
assert(logicTry(true, "1") and not logicTry(true, "x") and not logicTry(false, "x"))
```

## Compound NameAssign and ThisFieldAssign with try RHS

```babble
def top = 10
def bumpTop = fn io (s: string): int -> (
    top += try (parse(s)) catch : exception then (5)
    top
)
def b = Box(10)
assert(bumpTop("3") == 13 and bumpTop("x") == 18)
assert(b.bumpCompound("4") == 14 and b.bumpCompound("x") == 19)
```

## JavaFieldAssign : instance simple, compound, and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def pt = java.awt.Point(1, 2)
    pt.x = try (parse(s)) catch : exception then (7)
    pt.y += try (parse(s)) catch : exception then (3)
    (try (fail(f, pt)) catch : exception then (pt)).x = 9
    pt.x + pt.y
)
assert(run("5", false) == 16 and run("bad", true) == 14)
```

## JavaFieldAssign : static pure receiver chain with try RHS

```babble
def run = fn io (s: string): int -> (
    def Demo = import("org.babblelang.tests").JavaClassDemo
    Demo.counter = try (parse(s)) catch : exception then (4)
)
assert(run("5") == 5 and run("bad") == 4)
```

## DynamicFieldAssign with compound operator and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    b.v += try (parse(s)) catch : exception then (2)
    (try (fail(f, b)) catch : exception then (b)).v = b.v + 1
    b.v
)
assert(run("5", false) == 16 and run("x", true) == 13)
```

## ArrayCreate and ArrayOp with try in args, trailing arg, and try in receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def a = (array of int).of(1, try (parse(s)) catch : exception then (2), 9)
    a.set(0, try (parse(s)) catch : exception then (4))
    def v = (try (fail(f, a)) catch : exception then (a)).get(0)
    v + a.get(1) + a.get(2)
)
assert(run("3", false) == 15 and run("x", true) == 15)
```

```hir
;; hir -O1 fn=run check
contains (def $try0
```

## CoreCollectionCall with try arg and trailing arg after try

```babble
def run = fn io (s: string): int -> (
    def xs = list(1, try (parse(s)) catch : exception then (5), 10)
    xs.get(0) + xs.get(1) + xs.get(2)
)
assert(run("4") == 15 and run("bad") == 16)
```

## SuperCall and InterfaceCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def c: Calc = SubCalc()
    def a = c.calc(1, try (parse(s)) catch : exception then (2))
    def b = (try (fail(f, c)) catch : exception then (c)).calc(3, 4)
    a + b
)
assert(run("5", false) == 15 and run("bad", true) == 12)
```

## SelfRecurseCall (non-tail) and SelfTailCall with try arguments

```babble
def nonT = fn io (n: int, s: string): int -> (
  if n <= 0 then (0)
  else (1 + recurse(n - 1, try (fail(s == "x", s)) catch : exception then ("1")))
)
def tailR = fn io (n: int, a: int, s: string): int -> (
  if n <= 0 then (a)
  else (tailR(n - 1, a + try (parse(s)) catch : exception then (2), s))
)
assert(nonT(3, "x") == 3 and tailR(3, 0, "4") == 12 and tailR(3, 0, "b") == 6)
```

## DirectBabbleCall and DirectThisMethodCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    def r1 = addIO(1, try (parse(s)) catch : exception then (2))
    def r2 = b.addIO(1, try (parse(s)) catch : exception then (3))
    def r3 = (try (fail(f, b)) catch : exception then (b)).addIO(2, 3)
    r1 + r2 + r3
)
assert(run("5", false) == 37 and run("bad", true) == 32)
```

## JavaConstructorCall : pure static callee and dynamic callee with try arg

```babble
def run = fn io (s: string, f: bool): int -> (
    def p1 = java.awt.Point(1, try (parse(s)) catch : exception then (2))
    def p2 = (if f then (java.awt.Point) else (java.awt.Point))(3, try (parse(s)) catch : exception then (4))
    p1.y + p2.y
)
assert(run("8", false) == 16 and run("bad", true) == 6)
```

## JavaConstructorCall : dynamic callee containing try (lastTry < 0)

```babble
def run = fn io (f: bool): int -> (
    def p = (try (fail(f, java.awt.Point)) catch : exception then (java.awt.Point))(5, 6)
    p.x + p.y
)
assert(run(false) == 11 and run(true) == 11)
```

## JavaMethodCall : static method, instance method with try arg, and receiver try

```babble
def run = fn io (s: string, f: bool): string -> (
    def m = java.lang.Math.max(1, try (parse(s)) catch : exception then (2))
    def sb = java.lang.StringBuilder()
    sb.append(try (parse(s)) catch : exception then (9))
    (try (fail(f, sb)) catch : exception then (sb)).append(":" + m)
    sb.toString()
)
assert(run("5", false) == "5:5" and run("bad", true) == "9:2")
```

## DynamicCall and SafeCall with try in callee or receiver only

```babble
def getOpt = fn io (f: bool): string? -> (if f then (throw exception("e")) else ("abc"))
def run = fn io (f: bool): int -> (
    def g = fn (x: int): int -> (x + 1)
    def a = (try (fail(f, g)) catch : exception then (g))(4)
    def b = (try (getOpt(f)) catch : exception then (null))?.length()
    a + (if b != null then (b) else (0))
)
assert(run(false) == 8 and run(true) == 5)
```

## MakeOverloadSet and OverloadDispatch with try in operands, args, and callee

```babble
def fi = fn io (x: int): int -> (x + 1)
def fs = fn io (s: string): int -> (s.length())
def run = fn io (f: bool, u: int | string): int -> (
  def ov = overload(fi, try (fail(f, fs)) catch : exception then (fs))
  def a = ov(try (fail(f, u)) catch : exception then ("ab"))
  def b = (try (fail(f, ov)) catch : exception then (ov))(u)
  a + b
)
assert(run(false, 4) == 10 and run(true, "xyz") == 5)
```

## Effective widening ranks 1..2 (byte->short, short->int) during try spilling

```babble
def addSh = fn io (a: short, b: short): int -> (a + b)
def run = fn io (b: byte, sh: short, s: string): int -> (
  def r1 = addSh(b, try (short(parse(s))) catch : exception then (short(1)))
  def r2 = sh + (try (parse(s)) catch : exception then (1))
  r1 + r2
)
assert(run(byte(2), short(3), "5") == 15 and run(byte(2), short(3), "x") == 7)
```

## Effective widening rank 3 (int->long) during try spilling

```babble
def run = fn io (n: int, s: string): long -> (
  n + (try (long(parse(s))) catch : exception then (1L))
)
assert(run(4, "5") == 9L and run(4, "x") == 5L)
```

## Effective widening ranks 4..5 (int->float, int->double) during try spilling

```babble
def run = fn io (n: int, s: string): double -> (
    def r4 = n + (try (float(parse(s))) catch : exception then (1.0f))
    def r5 = n + (try (double(parse(s))) catch : exception then (1.0))
    double(r4) + r5
)
assert(run(4, "5") == 18.0 and run(4, "x") == 10.0)
```

## Control-flow containsTry : If (cond, then, else)

```babble
def run = fn io (s: string, f: bool): int -> (
    def i1 = if (try (parse(s) > 0) catch : exception then (false)) then (1) else (2)
    def i2 = if f then (try (parse(s)) catch : exception then (3)) else (try (parse(s) + 1) catch : exception then (4))
    i1 + i2
)
assert(run("2", false) == 4 and run("bad", true) == 5)
```

## Control-flow containsTry : While (cond, body)

```babble
def run = fn io (s: string): int -> (
    def w = 0 ; while (try (parse(s) > w) catch : exception then (false)) then (w += 1)
    def wb = 0 ; while wb < 1 then (wb += try (parse(s)) catch : exception then (1))
    w + wb
)
assert(run("2") == 4 and run("bad") == 1)
```

## Control-flow containsTry : For (iterable, body) and RecordNew classRef

```babble
def Pair = record (a: int, b: int) ()
def run = fn io (s: string, f: bool): int -> (
    def fs = 0
    for e in (try (fail(f, list(1, 2))) catch : exception then (list(3))) then (
        fs += e + (try (parse(s)) catch : exception then (0))
    )
    def p = (try (fail(f, Pair)) catch : exception then (Pair))(1, 2)
    fs + p.a + p.b
)
assert(run("2", false) == 10 and run("bad", true) == 6)
```
