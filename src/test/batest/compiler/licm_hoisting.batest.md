# Loop-invariant code motion (LICM) hoisting across loops, classes, records, and widening ranks

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
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

```hir
;; hir -O0 fn=run
fn run (n:int, v:obj, xs:obj, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def c (if (and var(f) (block (>:bool (array-op SIZE var(xs)) 0:int))) (block 1.5:double) (block 2.5:double)))
      (set a += (+:double (+:double (+:double (jcall Math.hypot (get-this-field x) (get-this-field y)) (block (neg (get-this-field y)))) var(c)) (block
            (def t (*:double (get-this-field x) 2.0:double))
            var(t))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, v:obj, xs:obj, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (+:double (jcall Math.hypot (get-this-field x) (get-this-field y)) (neg (get-this-field y))))
    (def $licm1 (*:double (get-this-field x) 2.0:double))
    (while (<:bool var(i) var(n))
      (block
        (def c (if (and var(f) (>:bool (array-op SIZE var(xs)) 0:int)) 1.5:double 2.5:double))
        (set a += (+:double (+:double var($licm0) var(c)) (block
              (def t var($licm1))
              var(t))))
        (set i += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (v:obj, xs:obj) -> double {nothrow unique}
  (def a 0.0:double)
  (for e in var(xs)
    (block
      (set a += (+:double (+:double (jcall Math.sin (get-this-field y)) (call name(double) var(e))) (block (if (<:bool var(e) 0:int) (block (neg (get-this-field x))) (block 0.0:double)))))))
  (+:double var(a) (call-method var(v).len))
```

```hir
;; hir -O1 fn=run
fn run (v:obj, xs:obj) -> double {nothrow unique}
  (def a 0.0:double)
  (block
    (def $licm0 (jcall Math.sin (get-this-field y)))
    (def $licm1 (neg (get-this-field x)))
    (for e in var(xs)
      (block
        (set a += (+:double (+:double var($licm0) (call name(double) var(e))) (if (<:bool var(e) 0:int) var($licm1) 0.0:double))))))
  (+:double var(a) (jcall Math.hypot (get-this-field x) (get-this-field y)))
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

```hir
;; hir -O0,-O1 top-level
top-level:
  (def bigByte (fn-ref bigByte))
  (def bigInt (fn-ref bigInt))
  (def bigFloat (fn-ref bigFloat))
  (def bigSink (fn-ref bigSink))
  (def bigImpl (fn-ref bigImpl))
  (def nonPureThrow (fn-ref nonPureThrow))
  (def tickCount 0:int)
  (def tick (fn-ref tick))
  (def unsafeDiv 0:int)
  (def j 0:int)
  (while (<:bool name(j) 0:int)
    (block
      (set-name unsafeDiv += (jcall Math.floorDiv 10:int name(j)))
      (set-name j += 1:int)))
  (def ticks 0:int)
  (def k 0:int)
  (while (<:bool name(k) 3:int)
    (block
      (set-name ticks += (call-fn tick))
      (set-name k += 1:int)))
  (call name(assert) (and (and (==:bool name(unsafeDiv) 0:int) (==:bool name(ticks) 6:int)) (==:bool name(tickCount) 3:int)))
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

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int, b:byte) -> double {pure unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def l1 var(b))
      (def l2 (+:int (call name(short) 4:int) (call name(int) var(b))))
      (def l3 (+:int (call name(int) 5:int) 1:int))
      (def l4 (+:float 1.5:float 2.5:float))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) var(l4))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int, b:byte) -> double {pure unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def l1 var(b))
      (def l2 (+:int (call name(short) 4:int) (call name(int) var(b))))
      (def l3 (+:int (call name(int) 5:int) 1:int))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) 4.0:float)))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (def fv (fn-ref fv))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (+:double (/:double (jcall Math.sin (get-this-field x)) 2.0:double) (block (neg (jcall Math.toRadians (get-this-field x))))) (call-fn fv (get-this-field x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (def fv (fn-ref fv))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (+:double (/:double (jcall Math.sin (get-this-field x)) 2.0:double) (neg (jcall Math.toRadians (get-this-field x)))) (block
            (def z (get-this-field x))
            (jcall Math.sin var(z)))))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=Box.run
fn Box.run (n:int, f:bool, xs:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (def raw 1:int)
  (while (<:bool var(i) var(n))
    (block
      (def l6 (if (or var(f) (block (and (not var(f)) (block (>:bool (array-op SIZE var(xs)) 0:int))))) (block 1:int) (block 2:int)))
      (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim (get-this-field nm)))) (block (if (==:bool var(i) 0:int) (block (+:int (block (as var(raw) int|int|)) 0:int)) (block 0:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=Box.run
fn Box.run (n:int, f:bool, xs:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (def raw 1:int)
  (while (<:bool var(i) var(n))
    (block
      (def l6 (if (or var(f) (and (not var(f)) (>:bool (array-op SIZE var(xs)) 0:int))) 1:int 2:int))
      (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim (get-this-field nm)))) (if (==:bool var(i) 0:int) (as var(raw) int|int|) 0:int)))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (n:int, b:byte, f:bool) -> double {io}
  (def a 0.0:double)
  (def i 0:int)
  (def c 1:int)
  (def bump (fn-ref bump))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (block (as (call name(byte) 2:int) double|double|)) (block
            (def t (+:int (call name(int) var(b)) 1:int))
            (call name(double) var(t)))))
      (if (and var(f) false:bool) (block (set a += (call name(double) (call-fn bump)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, b:byte, f:bool) -> double {io}
  (def a 0.0:double)
  (def i 0:int)
  (def c 1:int)
  (def bump (fn-ref bump))
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (as (call name(byte) 2:int) double|double|) (block
            (def t (+:int (call name(int) var(b)) 1:int))
            (call name(double) var(t)))))
      (if (and var(f) false:bool) (set a += (call name(double) (call-fn bump))))
      (set i += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> long {nothrow unique}
  (def r 0:long)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (def hs (call-fn bigByte (get-this-field a)))
      (def hi (call-fn bigByte (get-this-field a)))
      (def hl (call-fn bigInt (get-this-field i)))
      (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> long {nothrow unique}
  (def r 0:long)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def x#1 (get-this-field a))
        var(x#1)))
    (def $licm1
      (block
        (def x#2 (get-this-field a))
        var(x#2)))
    (def $licm2
      (block
        (def x#3 (get-this-field i))
        var(x#3)))
    (while (<:bool var(k) var(n))
      (block
        (def hs var($licm0))
        (def hi var($licm1))
        (def hl var($licm2))
        (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
        (set k += 1:int))))
  var(r)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (def hf (call-fn bigInt (get-this-field i)))
      (def hd (call-fn bigFloat (get-this-field fl)))
      (set r += (+:double (call name(double) var(hf)) var(hd)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def x#1 (get-this-field i))
        var(x#1)))
    (def $licm1
      (block
        (def x#2 (get-this-field fl))
        var(x#2)))
    (while (<:bool var(k) var(n))
      (block
        (def hf var($licm0))
        (def hd var($licm1))
        (set r += (+:double (call name(double) var(hf)) var(hd)))
        (set k += 1:int))))
  var(r)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def k 0:int)
  (def fnD (fn-ref fnD))
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:double (call-fn fnD (get-this-field d)) (block (if (and (get-this-field f) (block (>:bool (array-op SIZE (get-this-field xs)) 0:int))) (block 1.0:double) (block 2.0:double)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def k 0:int)
  (def fnD (fn-ref fnD))
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:double (block
            (def v (get-this-field d))
            (jcall Math.sin var(v))) (if (and (get-this-field f) (>:bool (array-op SIZE (get-this-field xs)) 0:int)) 1.0:double 2.0:double)))
      (set k += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run () -> double {nothrow unique}
  (def a 0.0:double)
  (for e in (get-this-field arr)
    (block
      (set a += (+:double (jcall Math.sin (get-this-field d)) (call name(double) var(e))))))
  var(a)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run () -> double {nothrow unique}
  (def a 0.0:double)
  (block
    (def $licm0 (jcall Math.sin (get-this-field d)))
    (for e in (get-this-field arr)
      (block
        (set a += (+:double var($licm0) (call name(double) var(e)))))))
  var(a)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim (block (+:obj (+:obj "  " (get-this-field s)) "  "))))) (block (as (get-this-field a) double|double|))) (/:double (block (+:double (get-this-field d) 1.0:double)) 2.0:double)))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (+:obj (+:obj "  " (get-this-field s)) "  "))
    (def $licm1 (/:double (+:double (get-this-field d) 1.0:double) 2.0:double))
    (while (<:bool var(k) var(n))
      (block
        (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim var($licm0)))) (as (get-this-field a) double|double|)) var($licm1)))
        (set k += 1:int))))
  var(r)
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

```hir
;; hir -O0 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set r += (call-fn bigSink (+:double (block (as (get-this-field a) double|double|)) (block (if (or (and (get-this-field f) (block (>:bool (get-this-field i) 0:int))) (block (not (get-this-field f)))) (block 1.0:double) (block 2.0:double))))))
      (set k += 1:int)))
  var(r)
```

```hir
;; hir -O1 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set r +=
        (block
          (def x (+:double (as (get-this-field a) double|double|) (if (or (and (get-this-field f) (>:bool (get-this-field i) 0:int)) (not (get-this-field f))) 1.0:double 2.0:double)))
          var(x)))
      (set k += 1:int)))
  var(r)
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

```hir
;; hir -O0 fn=run
fn run (d:double, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def loc name(bigSink))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (+:double (+:double (call-fn bigImpl var(d)) (call-fn bigSink (+:double (block (neg var(d))) (block (if var(f) (block 1.0:double) (block 2.0:double)))))) (call-fn bigOpt (if var(f) (block var(d))))) (call var(loc) var(d))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (d:double, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def loc name(bigSink))
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def s (jcall Math.sin var(d)))
        var(s)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (+:double var($licm0) (block
                  (def x#1 (+:double (neg var(d)) (if var(f) 1.0:double 2.0:double)))
                  var(x#1))) (block
                (def x#2 (if var(f) var(d)))
                (def r (if (==:bool var(x#2) null) 0.0:double 1.0:double))
                var(r))) (call var(loc) var(d))))
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (b:byte) -> int {nothrow unique}
  (def a 0:int)
  (def mf true:bool)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (call-fn tS var(mf) (call-fn bigByte var(b))) (call-fn tI var(mf) (call-fn bigByte var(b)))))
      (set mf (not var(mf)))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (b:byte) -> int {nothrow unique}
  (def a 0:int)
  (def mf true:bool)
  (def k 0:int)
  (block
    (def $licm0 (block var(b)))
    (def $licm1 (block var(b)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:int (block
              (def r#1 (if var(mf) (call name(int) var($licm0)) 0:int))
              var(r#1)) (block
              (def r#2 (if var(mf) var($licm1) 0:int))
              var(r#2))))
        (set mf (not var(mf)))
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (i:int) -> long {nothrow unique}
  (def a 0:long)
  (def mf true:bool)
  (def ml 1:long)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:long (call-fn tL var(mf) (call-fn bigInt var(i))) (block (+:long var(ml) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set ml += 1:long)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (i:int) -> long {nothrow unique}
  (def a 0:long)
  (def mf true:bool)
  (def ml 1:long)
  (def k 0:int)
  (block
    (def $licm0 (block var(i)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:long (block
              (def r (if var(mf) var($licm0) 0:long))
              var(r)) (+:long var(ml) var($licm1))))
        (set mf (not var(mf)))
        (set ml += 1:long)
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (i:int) -> float {nothrow unique}
  (def a 0.0:float)
  (def mf true:bool)
  (def mfl 1.0:float)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:float (call-fn tF var(mf) (call-fn bigInt var(i))) (block (+:float var(mfl) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set mfl += 1.0:float)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (i:int) -> float {nothrow unique}
  (def a 0.0:float)
  (def mf true:bool)
  (def mfl 1.0:float)
  (def k 0:int)
  (block
    (def $licm0 (block var(i)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:float (block
              (def r (if var(mf) var($licm0) 0.0:float))
              var(r)) (+:float var(mfl) var($licm1))))
        (set mf (not var(mf)))
        (set mfl += 1.0:float)
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (fl:float, i:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def mf true:bool)
  (def md 1.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (call-fn tD var(mf) (call-fn bigFloat var(fl))) (block (+:double var(md) (call-fn bigInt var(i))))))
      (set mf (not var(mf)))
      (set md += 1.0:double)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (fl:float, i:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def mf true:bool)
  (def md 1.0:double)
  (def k 0:int)
  (block
    (def $licm0 (block var(fl)))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (block
              (def r (if var(mf) var($licm0) 0.0:double))
              var(r)) (+:double var(md) var($licm1))))
        (set mf (not var(mf)))
        (set md += 1.0:double)
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (ro:int) -> int {io}
  (def a 0:int)
  (def pt (jnew Point 1:int 2:int))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (jcall Integer.bitCount var(ro)) (block (if (and (jcall Objects.nonNull "ab") (jcall Objects.nonNull var(pt))) (block 1:int) (block 0:int)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (ro:int) -> int {io}
  (def a 0:int)
  (def pt (jnew Point 1:int 2:int))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (jcall Integer.bitCount var(ro)) (if (and (jcall Objects.nonNull "ab") (jcall Objects.nonNull var(pt))) 1:int 0:int)))
      (set k += 1:int)))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run (ro:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def ml 1.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (+:double (call name(double) (call-fn bigInt (jcall String.length "ok"))) (jcall Math.sin var(ml))) (call-fn nonPureThrow var(ro))))
      (set ml += 0.5:double)
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (ro:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def ml 1.0:double)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def q (/:double 10:int var(ro)))
        (call name(double) var(q))))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (call name(double) 2:int) (jcall Math.sin var(ml))) var($licm0)))
        (set ml += 0.5:double)
        (set k += 1:int))))
  var(a)
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

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
  (def bump (fn-ref bump))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (call-fn bigInt var(cap)))
      (if false:bool (block (set a += (call-fn bump))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
  (def bump (fn-ref bump))
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a +=
        (block
          (def x var(cap))
          var(x)))
      (set k += 1:int)))
  var(a)
```
