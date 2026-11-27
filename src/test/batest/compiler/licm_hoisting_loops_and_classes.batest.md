# LICM Hoisting : Loops and Class Field Invariance

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
  var a = 0.0 ; var i = 0
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

```hir
;; hir -O2 fn=run
fn run (n:int, v:obj, xs:obj, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field y))
    (def $licm1 (get-this-field x))
    (block
      (def $licm2 (+:double (jcall Math.hypot var($licm1) var($licm0)) (neg var($licm0))))
      (while (<:bool var(i) var(n))
        (block
          (def c (if (and var(f) (>:bool (array-op SIZE var(xs)) 0:int)) 1.5:double 2.5:double))
          (set a += (+:double (+:double var($licm2) var(c)) (block
                (def t (*:double var($licm1) 2.0:double))
                var(t))))
          (set i += 1:int)))))
  var(a)
```

## For loop hoisting : pure calls, record method, and invariant negation

```babble
def Vec = record (x: double, y: double) (
  def len = fn nothrow (): double -> (java.lang.Math.hypot(this.x, this.y))
)
def run = fn (v: Vec, xs: array of int): double -> (
  var a = 0.0
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

```hir
;; hir -O2 fn=run
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
var tickCount = 0
def tick = fn io (): int -> (tickCount += 1 ; tickCount)
var unsafeDiv = 0 ; var j = 0
while j < 0 then (unsafeDiv += java.lang.Math.floorDiv(10, j) ; j += 1)
var ticks = 0 ; var k = 0
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

```hir
;; hir -O2 top-level
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
    var a = 0.0 ; var i = 0
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
      (def l1 (widen[short] var(b)))
      (def l2 (+:int (convert[int] (call name(short) 4:int)) (call name(int) var(b))))
      (def l3 (widen[long] (+:int (call name(int) 5:int) 1:int)))
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
      (def l1 (widen[short] var(b)))
      (def l2 (+:int (convert[int] (call name(short) 4:int)) (call name(int) var(b))))
      (def l3 (widen[long] (+:int (call name(int) 5:int) 1:int)))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) 4.0:float)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=Box.run
fn Box.run (n:int, b:byte) -> double {pure unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def l1 (widen[short] var(b)))
      (def l2 (+:int (convert[int] (call name(short) 4:int)) (call name(int) var(b))))
      (def l3 (widen[long] (+:int (call name(int) 5:int) 1:int)))
      (set a += (+:double (+:double (+:double (call name(double) var(l1)) (call name(double) var(l2))) (call name(double) var(l3))) (call name(double) 4.0:float)))
      (set i += 1:int)))
  var(a)
```

## Class-based LICM hoisting : Unary/Binary/DIV, ThisRef, and fnVar

```babble
def Box = class (def x: double) (
  def run = fn (n: int): double -> (
    var a = 0.0 ; var i = 0 ; def fv = fn (z: double): double -> (java.lang.Math.sin(z))
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
  (block
    (def $licm0 (+:double (/:double (jcall Math.sin (get-this-field x)) 2.0:double) (neg (jcall Math.toRadians (get-this-field x)))))
    (def $licm1 (get-this-field x))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:double var($licm0) (block
              (def z var($licm1))
              (jcall Math.sin var(z)))))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=Box.run
fn Box.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field x))
    (block
      (def $licm1 (jcall Math.sin var($licm0)))
      (def $licm2 (neg (jcall Math.toRadians var($licm0))))
      (block
        (def $licm3 (+:double (+:double (/:double var($licm1) 2.0:double) var($licm2)) var($licm1)))
        (while (<:bool var(i) var(n))
          (block
            (set a += var($licm3))
            (set i += 1:int))))))
  var(a)
```

## Class-based LICM hoisting : Logic + array.size, String.trim/length, and As

```babble
def Box = class (def nm: string) (
  def run = fn (n: int, f: bool, xs: array of int): int -> (
    var a = 0 ; var i = 0 ; def raw: any = 1
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
  (def raw (box 1:int))
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
  (def raw (box 1:int))
  (block
    (def $licm0 (get-this-field nm))
    (while (<:bool var(i) var(n))
      (block
        (def l6 (if (or var(f) (and (not var(f)) (>:bool (array-op SIZE var(xs)) 0:int))) 1:int 2:int))
        (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim var($licm0)))) (if (==:bool var(i) 0:int) (as var(raw) int|int|) 0:int)))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=Box.run
fn Box.run (n:int, f:bool, xs:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field nm))
    (while (<:bool var(i) var(n))
      (block
        (def l6 (if (or var(f) (and (not var(f)) (>:bool (array-op SIZE var(xs)) 0:int))) 1:int 2:int))
        (set a += (+:int (+:int var(l6) (jcall String.length (jcall String.trim var($licm0)))) (if (==:bool var(i) 0:int) 1:int 0:int)))
        (set i += 1:int))))
  var(a)
```
