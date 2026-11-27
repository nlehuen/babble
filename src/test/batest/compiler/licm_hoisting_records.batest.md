# LICM Hoisting : Record Fields, As-Widening, and Frozen Arrays

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



## Class-based LICM hoisting : As widening, Block, and dead cell-bump branch

```babble
def run = fn io (n: int, b: byte, f: bool): double -> (
  var a = 0.0 ; var i = 0 ; var c = 1 ; def bump = fn io (): int -> (c += 1 ; c)
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
      (if (and var(f) false:bool) (box (block (set a += (call name(double) (call-fn bump))))))
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
      (if (and var(f) false:bool) (box (set a += (call name(double) (call-fn bump)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=run
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
      (if (and var(f) false:bool) (box (set a += (call name(double) (call-fn bump)))))
      (set i += 1:int)))
  var(a)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 1..3 (short/int/long)

```babble
def RV = record (a: byte, i: int) (
  def run = fn (n: int): long -> (
    var r = 0L ; var k = 0
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
      (def hs (widen[short] (call-fn bigByte (get-this-field a))))
      (def hi (widen[int] (call-fn bigByte (get-this-field a))))
      (def hl (widen[long] (call-fn bigInt (get-this-field i))))
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
        (def hs (widen[short] var($licm0)))
        (def hi (widen[int] var($licm1)))
        (def hl (widen[long] var($licm2)))
        (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
        (set k += 1:int))))
  var(r)
```

```hir
;; hir -O2 fn=RV.run
fn RV.run (n:int) -> long {nothrow unique}
  (def r 0:long)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field a))
    (def $licm1 (get-this-field i))
    (while (<:bool var(k) var(n))
      (block
        (def hs (widen[short] var($licm0)))
        (def hi (widen[int] var($licm0)))
        (def hl (widen[long] (block
              (def x var($licm1))
              var(x))))
        (set r += (+:long (+:long (call name(long) var(hs)) (call name(long) var(hi))) var(hl)))
        (set k += 1:int))))
  var(r)
```

## Record-based LICM : ThisFieldGet + DirectBabbleCall across effectiveKind 4..5 (float/double)

```babble
def RV = record (i: int, fl: float) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
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
      (def hf (widen[float] (call-fn bigInt (get-this-field i))))
      (def hd (widen[double] (call-fn bigFloat (get-this-field fl))))
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
        (def hf (widen[float] var($licm0)))
        (def hd (widen[double] var($licm1)))
        (set r += (+:double (call name(double) var(hf)) var(hd)))
        (set k += 1:int))))
  var(r)
```

```hir
;; hir -O2 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field i))
    (def $licm1 (get-this-field fl))
    (while (<:bool var(k) var(n))
      (block
        (def hf (widen[float] (block
              (def x#1 var($licm0))
              var(x#1))))
        (def hd (widen[double] (block
              (def x#2 var($licm1))
              var(x#2))))
        (set r += (+:double (call name(double) var(hf)) var(hd)))
        (set k += 1:int))))
  var(r)
```

## Record-based LICM : while loop with fnDyn and Logic + frozen array.size

```babble
def RV = record (d: double, f: bool, xs: frozen array of int) (
  def run = fn (n: int): double -> (
    var a = 0.0 ; var k = 0 ; def fnD = fn (v: double): double -> (java.lang.Math.sin(v))
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
  (block
    (def $licm0 (get-this-field d))
    (def $licm1 (get-this-field f))
    (def $licm2 (get-this-field xs))
    (while (<:bool var(k) var(n))
      (block
        (set a += (+:double (block
              (def v var($licm0))
              (jcall Math.sin var(v))) (if (and var($licm1) (>:bool (array-op SIZE var($licm2)) 0:int)) 1.0:double 2.0:double)))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=RV.run
fn RV.run (n:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field d))
    (def $licm1 (get-this-field f))
    (def $licm2 (get-this-field xs))
    (while (<:bool var(k) var(n))
      (block
        (set a += (+:double (block
              (def v var($licm0))
              (jcall Math.sin var(v))) (if (and var($licm1) (>:bool (array-op SIZE var($licm2)) 0:int)) 1.0:double 2.0:double)))
        (set k += 1:int))))
  var(a)
```

## Record-based LICM : for loop over frozen array hoisting Math.sin(this.d)

```babble
def RV = record (d: double, arr: frozen array of int) (
  def run = fn (): double -> (
    var a = 0.0
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

```hir
;; hir -O2 fn=RV.run
fn RV.run () -> double {nothrow unique}
  (def a 0.0:double)
  (block
    (def $licm0 (jcall Math.sin (get-this-field d)))
    (for e in (get-this-field arr)
      (block
        (set a += (+:double var($licm0) (call name(double) var(e)))))))
  var(a)
```
