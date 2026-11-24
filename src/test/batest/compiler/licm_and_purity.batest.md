# Loop-invariant code motion (LICM) and HIR purity / modified-variable analysis

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble preamble
def bigByte = fn nothrow (x: byte): byte -> (return x ; x)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigFloat = fn nothrow (x: float): float -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
def bigImpl = fn (x: double): double -> (def s = java.lang.Math.sin(x) ; return s ; s)
def nonPureThrow = fn (x: int): double -> (def q = 10 / x ; return double(q) ; double(q))
```

## While loop hoisting : record fields, Math.hypot, unary negate, logic + array.size, if, and block

```babble
def Vec = record (x: double, y: double) ()
def run = fn (n: int, v: Vec, xs: array of int, f: bool): double -> (
  def a = 0.0 ; def i = 0
  while i < n then (
    def c = if f and (xs.size() > 0) then (1.5) else (2.5)
    a += java.lang.Math.hypot(v.x, v.y) + (-v.y) + c + (def t = v.x * 2.0 ; t) ; i += 1
  )
  a
)
assert(run(3, Vec(3.0, 4.0), array.of(1, 2), true) == 25.5)
```

## For loop hoisting : pure calls, record method, and invariant negation

```babble
def Vec = record (x: double, y: double) (
  def len = fn nothrow (): double -> (java.lang.Math.hypot(this.x, this.y))
)
def run = fn (v: Vec, xs: array of int): double -> (
  def a = 0.0
  for e in xs then (a += java.lang.Math.sin(v.y) + double(e) + (if e < 0 then (-v.x) else (0.0)))
  a + v.len()
)
assert(java.lang.Math.round(run(Vec(3.0, 4.0), array.of(1, 2)) * 100.0) == 649L)
```

## Throwing and impure calls must not be hoisted out of loops

```babble
def tickCount = 0
def tick = fn io (): int -> (tickCount += 1 ; tickCount)
def unsafeDiv = 0 ; def j = 0
while j < 0 then (unsafeDiv += java.lang.Math.floorDiv(10, j) ; j += 1)
def ticks = 0 ; def k = 0
while k < 3 then (ticks += tick() ; k += 1)
assert(unsafeDiv == 0 and ticks == 6 and tickCount == 3)
```

## Class-based LICM hoisting across widening ranks 1..4 (short, int, long, float)

```babble
def Box = class (def x: double) (
  def run = fn (n: int, b: byte): double -> (
    def a = 0.0 ; def i = 0
    while i < n then (
      def l1: short = b ; def l2: int = short(4) + int(b)
      def l3: long = int(5) + 1 ; def l4: float = 1.5f + 2.5f
      a += double(l1) + double(l2) + double(l3) + double(l4) ; i += 1
    )
    a
  )
)
assert(Box(1.0).run(2, byte(2)) == 36.0)
```

## Class-based LICM hoisting : Unary/Binary/DIV, ThisRef, and fnVar

```babble
def Box = class (def x: double) (
  def run = fn (n: int): double -> (
    def a = 0.0 ; def i = 0 ; def fv = fn (z: double): double -> (java.lang.Math.sin(z))
    while i < n then (
      a += java.lang.Math.sin(this.x) / 2.0 + (-java.lang.Math.toRadians(this.x)) + fv(this.x) ; i += 1
    )
    a
  )
)
assert(java.lang.Math.round(Box(1.0).run(2) * 100.0) == 249L)
```

## Class-based LICM hoisting : Logic + array.size, String.trim/length, and As

```babble
def Box = class (def nm: string) (
  def run = fn (n: int, f: bool, xs: array of int): int -> (
    def a = 0 ; def i = 0 ; def raw: any = 1
    while i < n then (
      def l6 = if f or (not f and (xs.size() > 0)) then (1) else (2)
      a += l6 + this.nm.trim().length() + (if i == 0 then ((raw as int) + 0) else (0)) ; i += 1
    )
    a
  )
)
assert(Box("  hi  ").run(2, true, array.of(7)) == 7)
```

## Class-based LICM hoisting : As widening, Block, and dead cell-bump branch

```babble
def run = fn io (n: int, b: byte, f: bool): double -> (
  def a = 0.0 ; def i = 0 ; def c = 1 ; def bump = fn io (): int -> (c += 1 ; c)
  while i < n then (
    a += (byte(2) as double) + (def t = int(b) + 1 ; double(t))
    if f and false then (a += double(bump())) ; i += 1
  )
  a
)
assert(run(2, byte(2), true) == 10.0)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 1..3 (short/int/long)

```babble
def RV = record (a: byte, i: int) (
  def run = fn (n: int): long -> (
    def r = 0L ; def k = 0
    while k < n then (
      def hs: short = bigByte(this.a) ; def hi: int = bigByte(this.a) ; def hl: long = bigInt(this.i)
      r += long(hs) + long(hi) + hl ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 3).run(2) == 14L)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 4..5 (float/double)

```babble
def RV = record (i: int, fl: float) (
  def run = fn (n: int): double -> (
    def r = 0.0 ; def k = 0
    while k < n then (
      def hf: float = bigInt(this.i) ; def hd: double = bigFloat(this.fl)
      r += double(hf) + hd ; k += 1
    )
    r
  )
)
assert(RV(3, 1.5f).run(2) == 9.0)
```

## Record-based LICM : while loop with fnDyn and Logic + frozen array.size

```babble
def RV = record (d: double, f: bool, xs: frozen array of int) (
  def run = fn (n: int): double -> (
    def a = 0.0 ; def k = 0 ; def fnD = fn (v: double): double -> (java.lang.Math.sin(v))
    while k < n then (a += fnD(this.d) + (if this.f and (this.xs.size() > 0) then (1.0) else (2.0)) ; k += 1) ; a
  )
)
assert(java.lang.Math.round(RV(1.0, true, freeze(array.of(5))).run(2) * 100.0) == 368L)
```

## Record-based LICM : for loop over frozen array hoisting Math.sin(this.d)

```babble
def RV = record (d: double, arr: frozen array of int) (
  def run = fn (): double -> (
    def a = 0.0
    for e in this.arr then (a += java.lang.Math.sin(this.d) + double(e))
    a
  )
)
assert(java.lang.Math.round(RV(1.0, freeze(array.of(5, 6))).run() * 100.0) == 1268L)
```

## Record-based LICM : String chain, As, and DIV

```babble
def RV = record (a: byte, d: double, s: string) (
  def run = fn (n: int): double -> (
    def r = 0.0 ; def k = 0
    while k < n then (
      r += double(("  " + this.s + "  ").trim().length()) + (this.a as double) + (this.d + 1.0) / 2.0 ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 1.0, "hi").run(2) == 10.0)
```

## Record-based LICM : bigSink over As + Logic

```babble
def RV = record (a: byte, i: int, f: bool) (
  def run = fn (n: int): double -> (
    def r = 0.0 ; def k = 0
    while k < n then (
      r += bigSink((this.a as double) + (if this.f and (this.i > 0) or (not this.f) then (1.0) else (2.0))) ; k += 1
    )
    r
  )
)
assert(RV(byte(2), 3, true).run(2) == 6.0)
```

## Non-inlineable DirectBabbleCall hoisting : implicit return, optional param, and local fn alias

```babble
def bigOpt = fn nothrow (x: double?): double -> (def r = if x == null then (0.0) else (1.0) ; return r ; r)
def run = fn (d: double, f: bool): double -> (
  def a = 0.0 ; def loc = bigSink ; def k = 0
  while k < 2 then (a += bigImpl(d) + bigSink((-d) + (if f then (1.0) else (2.0))) + bigOpt(if f then (d)) + loc(d) ; k += 1) ; a
)
assert(java.lang.Math.round(run(1.0, true) * 100.0) == 568L)
```

## Hoisting widened non-inlineable calls passed to multi-arg functions (short/int)

```babble
def tS = fn nothrow (m: bool, v: short): int -> (def r = if m then (int(v)) else (0) ; return r ; r)
def tI = fn nothrow (m: bool, v: int): int -> (def r = if m then (v) else (0) ; return r ; r)
def run = fn (b: byte): int -> (
  def a = 0 ; def mf = true ; def k = 0
  while k < 2 then (a += tS(mf, bigByte(b)) + tI(mf, bigByte(b)) ; mf = not mf ; k += 1) ; a
)
assert(run(byte(2)) == 4)
```

## Hoisting widened non-inlineable calls passed to multi-arg functions (long) and mixed with mutated long

```babble
def tL = fn nothrow (m: bool, v: long): long -> (def r = if m then (v) else (0L) ; return r ; r)
def run = fn (i: int): long -> (
  def a = 0L ; def mf = true ; def ml = 1L ; def k = 0
  while k < 2 then (a += tL(mf, bigInt(i)) + (ml + bigInt(i)) ; mf = not mf ; ml += 1L ; k += 1) ; a
)
assert(run(3) == 12L)
```

## Hoisting widened non-inlineable float calls mixed with mutated float

```babble
def tF = fn nothrow (m: bool, v: float): float -> (def r = if m then (v) else (0.0f) ; return r ; r)
def run = fn (i: int): float -> (
  def a = 0.0f ; def mf = true ; def mfl = 1.0f ; def k = 0
  while k < 2 then (a += tF(mf, bigInt(i)) + (mfl + bigInt(i)) ; mf = not mf ; mfl += 1.0f ; k += 1) ; a
)
assert(run(3) == 12.0f)
```

## Hoisting widened non-inlineable double calls mixed with mutated double

```babble
def tD = fn nothrow (m: bool, v: double): double -> (def r = if m then (v) else (0.0) ; return r ; r)
def run = fn (fl: float, i: int): double -> (
  def a = 0.0 ; def mf = true ; def md = 1.0 ; def k = 0
  while k < 2 then (a += tD(mf, bigFloat(fl)) + (md + bigInt(i)) ; mf = not mf ; md += 1.0 ; k += 1) ; a
)
assert(run(1.5f, 3) == 10.5)
```

## Pure Java axioms hoisted (primitive/string args) vs mutable Point arg rejected by allArgsPrimitiveOrImmutable

```babble
axiom java.lang.Integer.bitCount: fn pure nothrow
axiom java.util.Objects.nonNull: fn pure nothrow
def run = fn io (ro: int): int -> (
  def a = 0 ; def pt = java.awt.Point(1, 2) ; def k = 0
  while k < 2 then (
    a += java.lang.Integer.bitCount(ro) + (if java.util.Objects.nonNull("ab") and java.util.Objects.nonNull(pt) then (1) else (0)) ; k += 1
  ) ; a
)
assert(run(4) == 4)
```

## Hoisted pure DirectBabbleCall alongside non-hoistable mutated Math.sin and throwing call

```babble
def run = fn (ro: int): double -> (
  def a = 0.0 ; def ml = 1.0 ; def k = 0
  while k < 2 then (
    a += double(bigInt("ok".length())) + java.lang.Math.sin(ml) + nonPureThrow(ro) ; ml += 0.5 ; k += 1
  ) ; a
)
assert(java.lang.Math.round(run(4) * 100.0) == 1084L)
```

## Captured variable mutated by closure blocks LICM on bigInt(cap)

```babble
def run = fn io (): int -> (
  def a = 0 ; def cap = 2 ; def bump = fn io (): int -> (cap += 1 ; cap) ; def k = 0
  while k < 2 then (
    a += bigInt(cap)
    if false then (a += bump())
    k += 1
  ) ; a
)
assert(run() == 4)
```

## HirPurity.collectModifiedVars : array.set, field/Java writes, and nested for loop

```babble
def Sub = class (def f: int) ()
def run = fn io (): int -> (
  def a = 0 ; def s = Sub(4) ; def pt = java.awt.Point(2, 3) ; def i = 0
  while i < 2 then (
    def a1 = (array of int)(2) ; a1.set(0, i + 1) ; s.f = 5 ; pt.x = 7
    for v in a1 then (a += v)
    a += pt.x + s.f ; i += 1
  ) ; a
)
assert(run() == 27)
```

## HirPurity.collectModifiedVars : super, DirectThisMethodCall, and InterfaceCall inside while

```babble
def I = interface (def g = fn (x: int): int -> (x + 1))
def S = class (def f: int) implements I (
  def m = fn (x: int): int -> (this.f + x)
  def s = fn (x: int): int -> (def a = 0 ; def i = 0 ; while i < 2 then (a += super.g(x) + this.m(x) ; i += 1) ; a)
)
def r = fn (v: I): int -> (def a = 0 ; def i = 0 ; while i < 2 then (a += v.g(1) ; i += 1) ; a)
assert(S(5).s(1) == 16 and r(S(5)) == 4)
```

## HirPurity.collectModifiedVars : is, as, and safe-nav (?.) inside while

```babble
def S = class (def f: int) (def m = fn (x: int): int -> (this.f + x))
def run = fn (s: S, o: S?, d: any): int -> (
  def a = 0 ; def i = 0
  while i < 2 then (a += (d as S).m(1) + (if (s is S) and (o?.m(1) != null) then (1) else (0)) ; i += 1) ; a
)
assert(run(S(5), S(5), S(5)) == 14)
```

## HirPurity.collectModifiedVars : top-level/this writes, !, generic TypeApply

```babble
def topM = 0 ; topM = 1
def bigId of T = fn nothrow (x: T): T -> (def f = fn (): int -> (0) ; discard(f) ; x)
def MC = class (def mf: int) (
  def run = fn mut io (n: int): int -> (
    def i = 0 ; def s = 0
    while i < n then (
      topM += 1 ; this.mf += 1 ; def os: string? = "ab"
      s += this.mf + os!.length() + (bigId of int)(4) ; i += 1
    ) ; s + topM
  )
)
assert(MC(1).run(2) == 20)
```

## HirPurity.collectModifiedVars : nested fn, class, interface, StringBuilder, list, and is check inside while

```babble
def run = fn io (): int -> (
  def i = 0 ; def s = 0
  while i < 2 then (
    def sb = java.lang.StringBuilder("x") ; def fnL = fn (z: int): int -> (z + 1)
    def ClsL = class (def v: int) () ; def IfcL = interface () ; def av: any = sb
    s += sb.length() + list(1, 2).size() + fnL(1) + ClsL(3).v + (if av is IfcL then (1) else (0)) ; i += 1
  ) ; s
)
assert(run() == 16)
```

## HirPurity.collectModifiedVars : SelfRecurseCall and nested while inside a while loop

```babble
def recWhile = fn (n: int): int -> (
  def i = 0 ; def s = 0
  while i < 1 then (
    def j = 0 ; while j < 2 then (j += 1)
    if n > 0 then (s += recurse(n - 1) + 1) ; s += j ; i += 1
  )
  s
)
assert(recWhile(2) == 8)
```

## HirLicmPass.checkOperandsInvariant & HirPurity.isDeterministic : Logic, Unary.NOT, Block, and ArrayOp.SIZE

```babble
def bigBool = fn nothrow (b: bool): int -> (def r = if b then (1) else (0) ; return r ; r)
def run = fn (f: bool, i: int, arr: frozen array of int): int -> (
  def a = 0 ; def k = 0
  while k < 2 then (
    a += bigBool(f and (i > 0)) + bigBool(not f) + bigInt((discard(i + 1) ; i + 2)) + bigInt(arr.size()) ; k += 1
  ) ; a
)
assert(run(true, 3, freeze(array.of(1, 2))) == 16)
```

## HirLicmPass.checkOperandsInvariant : Block with declaredVars, As (checked & unchecked), NameRead, and Const...

```babble
def topRO = 4
def run = fn (b: byte, raw: any): double -> (
  def a = 0.0 ; def k = 0
  while k < 2 then (
    a += bigSink(b as double) + double(bigInt(topRO) + bigInt((def t = int(b) ; t)) + bigInt(raw as int) + "  hi  ".trim().length()) ; k += 1
  ) ; a
)
assert(run(byte(2), 3) == 26.0)
```

## HirLicmPass : local non-capturing fn (DirectBabbleCall with VarRead callee) and non-value class ThisFieldGet

```babble
def C = class (def v: int) (
  def run = fn (n: int): int -> (
    def loc = fn nothrow (x: int): int -> (return x + 1 ; x)
    def a = 0 ; def k = 0
    while k < n then (a += loc(n) + this.v ; k += 1) ; a
  )
)
assert(C(3).run(2) == 12)
```

## LICM / HirPurity : loop-invariant as, TypeApply, array.size(), and pure overloaded helper

```babble
def id of T = fn (x: T): int -> (1)
def ov = overload (fn (a: int): int -> (a + 1), fn (a: int, b: int): int -> (a + b))
def run = fn (x: any, arr: array of int): int -> (
  def i = 0 ; def acc = 0
  while i < 4 then (acc += (x as int) + (id of int)(x as int) + arr.size() + ov(2) + ov(1, 2) ; i += 1) ; acc
)
assert(run(5, array.of(10, 20, 30)) == 60)
```

## LICM in for-loop (Logic, DIV, Block) and DCE of unused MakeOverloadSet

```babble
def run = fn (a: int, b: int, flag: bool, xs: list of int): double -> (
  def unusedOv = overload (fn (n: int): int -> (n + 1), fn (s: string): int -> (s.length()))
  def acc = 0.0
  for e in xs then (if flag and (a > 0) then (acc += (a / b) + (a + b) + e))
  acc
)
assert(run(10, 2, true, list(1, 2, 3)) == 57.0)
```

## HirPurity.isDeterministic : loop-invariant non-static String.length, If, FunctionRef, CoreCollectionRef

```babble
def useRef = fn nothrow (f: fn nothrow (int): int, c: any): int -> (return f(1) ; 0)
def run = fn (s: string, flag: bool, n: int): int -> (
  def i = 0 ; def acc = 0
  while i < n then (
    acc += s.length() + (if flag then (1) else (2)) + useRef(fn nothrow (x: int): int -> (x + 1), list)
    i += 1
  )
  acc
)
assert(run("abc", true, 3) == 18)
```

## HirEGraphSimplifyPass : byte/short constant widening and char/bool comparisons

```babble
def run = fn (p: byte): bool -> (
  def s: short = if true then (byte(3)) else (p)
  def c1 = 'a' < 'b' and 'a' <= 'a' and 'b' > 'a' and 'b' >= 'b' and 'a' != 'b'
  def b1 = (true == true) and (true != false) and not (false == true)
  s == short(3) and c1 and b1
)
assert(run(byte(1)))
```
