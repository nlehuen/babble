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
;; hir -O0,-O1 fn=lhsTry check
!contains $try
contains (+:int
```

```hir
;; hir -O0 fn=rhsTry check
contains (def $try0 10:int)
contains (def $try1
```

```hir
;; hir -O1 fn=rhsTry check
contains (def $try0 10:int)
contains (+:int var($try0) var($try1))
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

```hir
;; hir -O0 fn=logicTry check
!contains $try
contains (def a (and (block
contains (def b (and var(f) (block
```

```hir
;; hir -O1 fn=logicTry check
!contains $try
contains (def a (and (try
contains (def b (and var(f) (try
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

```hir
;; hir -O0,-O1 fn=bumpTop
fn bumpTop (s:obj) -> int {io}
  (set-name top += (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        5:int)))
  name(top)
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

```hir
;; hir -O0 fn=run check
contains (def $try0 var(pt))
contains (set-jfield x var($try0) var($try1))
contains (set-jfield x (block
```

```hir
;; hir -O1 fn=run check
contains (def $try0 var(pt))
contains (set-jfield x var($try0) var($try1))
contains (set-jfield x (try
```

## JavaFieldAssign : static pure receiver chain with try RHS

```babble
def run = fn io (s: string): int -> (
    def Demo = import("org.babblelang.tests").JavaClassDemo
    Demo.counter = try (parse(s)) catch : exception then (4)
)
assert(run("5") == 5 and run("bad") == 4)
```

```hir
;; hir -O0,-O1 fn=run
fn run (s:obj) -> int {io}
  (def Demo (select (call name(import) "org.babblelang.tests").JavaClassDemo))
  (block
    (def $try0
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (set-jfield counter var(Demo) var($try0)))
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

```hir
;; hir -O0 fn=run check
!contains $try
contains (set-field var(b).v (try
contains (set-field (block
```

```hir
;; hir -O1 fn=run check
!contains $try
contains (set-field var(b).v (try
contains (set-field (try
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
;; hir -O0 fn=run check
contains (def $try0 1:int)
contains (array-new OF var($try0) var($try1) 9:int)
contains (array-op SET var($try2) var($try3) var($try4))
contains (def v (array-op GET (block
```

```hir
;; hir -O1 fn=run check
contains (def $try0 1:int)
contains (array-new OF var($try0) var($try1) 9:int)
contains (array-op SET var($try2) var($try3) var($try4))
contains (def v (array-op GET (try
```

## CoreCollectionCall with try arg and trailing arg after try

```babble
def run = fn io (s: string): int -> (
    def xs = list(1, try (parse(s)) catch : exception then (5), 10)
    xs.get(0) + xs.get(1) + xs.get(2)
)
assert(run("4") == 15 and run("bad") == 16)
```

```hir
;; hir -O0,-O1 fn=run check
contains (def $try0 1:int)
contains (core-new list var($try0) var($try1) 10:int)
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

```hir
;; hir -O0 fn=run check
contains (def $try0 var(c))
contains (call-iface var($try0).calc var($try1) var($try2))
contains (def b (call-iface (block
```

```hir
;; hir -O1 fn=run check
contains (def $try0 var(c))
contains (call-iface var($try0).calc var($try1) var($try2))
contains (def b (call-iface (try
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

```hir
;; hir -O0,-O1 fn=nonT check
contains (def $try0 1:int)
contains (self-recurse nonT var($try2) var($try3))
```

```hir
;; hir -O0,-O1 fn=tailR check
contains (def $try0 (-:int var(n) 1:int))
contains (tail-call tailR var($try0) var($try1) var(s))
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

```hir
;; hir -O0 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (block
```

```hir
;; hir -O1 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (try
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

```hir
;; hir -O0 fn=run check
contains (jnew Point var($try0) var($try1))
contains (def $try2 (block (if var(f)
contains (jnew Point var($try3) var($try4))
```

```hir
;; hir -O1 fn=run check
contains (jnew Point var($try0) var($try1))
contains (def $try2 (if var(f)
contains (jnew Point var($try3) var($try4))
```

## JavaConstructorCall : dynamic callee containing try (lastTry < 0)

```babble
def run = fn io (f: bool): int -> (
    def p = (try (fail(f, java.awt.Point)) catch : exception then (java.awt.Point))(5, 6)
    p.x + p.y
)
assert(run(false) == 11 and run(true) == 11)
```

```hir
;; hir -O0,-O1 fn=run
fn run (f:bool) -> int {io}
  (def p (jnew Point 5:int 6:int))
  (+:int (get-jfield Point.x var(p)) (get-jfield Point.y var(p)))
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

```hir
;; hir -O0 fn=run check
contains (jcall Math.max var($try0) var($try1))
contains (jcall StringBuilder.append var($try2) var($try3))
contains (jcall StringBuilder.append (block
```

```hir
;; hir -O1 fn=run check
contains (jcall Math.max var($try0) var($try1))
contains (jcall StringBuilder.append var($try2) var($try3))
contains (jcall StringBuilder.append (try
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

```hir
;; hir -O0 fn=run check
!contains $try
contains (def a (call (block
contains (def b (safe-call (block
```

```hir
;; hir -O1 fn=run check
!contains $try
contains (def a (call (try
contains (def b (safe-call (try
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

```hir
;; hir -O0 fn=run check
contains (overload var($try0) var($try1))
contains (overload-dispatch var($try2) var($try3))
contains (def b (overload-dispatch (block
```

```hir
;; hir -O1 fn=run check
contains (overload var($try0) var($try1))
contains (overload-dispatch var($try2) var($try3))
contains (def b (overload-dispatch (try
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

```hir
;; hir -O0,-O1 fn=run check
contains (def $try1 var(b))
contains (call-fn addSh var($try1) var($try2))
contains (def $try3 var(sh))
contains (+:int var($try3) var($try4))
```

## Effective widening rank 3 (int->long) during try spilling

```babble
def run = fn io (n: int, s: string): long -> (
  n + (try (long(parse(s))) catch : exception then (1L))
)
assert(run(4, "5") == 9L and run(4, "x") == 5L)
```

```hir
;; hir -O0,-O1 fn=run check
contains (def $try0 var(n))
contains (+:long var($try0) var($try1))
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

```hir
;; hir -O0,-O1 fn=run check
contains (+:float var($try0) var($try1))
contains (+:double var($try2) var($try3))
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

```hir
;; hir -O0 fn=run check
!contains $try
contains (def i1 (if (block
```

```hir
;; hir -O1 fn=run check
!contains $try
contains (def i1 (if (try
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

```hir
;; hir -O0 fn=run check
!contains $try
contains (while (block
```

```hir
;; hir -O1 fn=run check
!contains $try
contains (while (try
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

```hir
;; hir -O0 fn=run check
contains (def $try0 var(e))
contains (def $try2
contains (record-new Pair 1:int 2:int)
```

```hir
;; hir -O1 fn=run check
contains (def $try0 var(e))
!contains record-new
contains (def $sr_p_0 1:int)
```
