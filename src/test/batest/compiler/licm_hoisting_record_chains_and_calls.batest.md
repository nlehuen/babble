# LICM Hoisting : Record Chains and Non-Inlineable Calls

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



## Record-based LICM : String chain, As, and DIV

```babble
def RV = record (a: byte, d: double, s: string) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
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
    (def $licm1 (get-this-field a))
    (def $licm2 (/:double (+:double (get-this-field d) 1.0:double) 2.0:double))
    (while (<:bool var(k) var(n))
      (block
        (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim var($licm0)))) (as var($licm1) double|double|)) var($licm2)))
        (set k += 1:int))))
  var(r)
```

```hir
;; hir -O2 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (+:obj (+:obj "  " (get-this-field s)) "  "))
    (def $licm1 (get-this-field a))
    (def $licm2 (/:double (+:double (get-this-field d) 1.0:double) 2.0:double))
    (while (<:bool var(k) var(n))
      (block
        (set r += (+:double (+:double (call name(double) (jcall String.length (jcall String.trim var($licm0)))) (as var($licm1) double|double|)) var($licm2)))
        (set k += 1:int))))
  var(r)
```

## Record-based LICM : bigSink over As + Logic

```babble
def RV = record (a: byte, i: int, f: bool) (
  def run = fn (n: int): double -> (
    var r = 0.0 ; var k = 0
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
  (block
    (def $licm0 (get-this-field a))
    (def $licm1 (get-this-field f))
    (def $licm2 (>:bool (get-this-field i) 0:int))
    (def $licm3 (not (get-this-field f)))
    (while (<:bool var(k) var(n))
      (block
        (set r +=
          (block
            (def x (+:double (as var($licm0) double|double|) (if (or var($licm3) (and var($licm1) var($licm2))) 1.0:double 2.0:double)))
            var(x)))
        (set k += 1:int))))
  var(r)
```

```hir
;; hir -O2 fn=RV.run
fn RV.run (n:int) -> double {pure unique}
  (def r 0.0:double)
  (def k 0:int)
  (block
    (def $licm0 (get-this-field f))
    (def $licm1 (get-this-field a))
    (def $licm2 (>:bool (get-this-field i) 0:int))
    (while (<:bool var(k) var(n))
      (block
        (set r +=
          (block
            (def x (+:double (as var($licm1) double|double|) (if (or (and var($licm0) var($licm2)) (not var($licm0))) 1.0:double 2.0:double)))
            var(x)))
        (set k += 1:int))))
  var(r)
```

## Non-inlineable DirectBabbleCall hoisting : implicit return, optional param, and local fn alias

```babble
def bigOpt = fn nothrow (x: double?): double -> (def r = if x == null then (0.0) else (1.0) ; return r ; r)
def run = fn (d: double, f: bool): double -> (
  var a = 0.0 ; def loc = bigSink ; var k = 0
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
      (set a += (+:double (+:double (+:double (call-fn bigImpl var(d)) (call-fn bigSink (+:double (block (neg var(d))) (block (if var(f) (block 1.0:double) (block 2.0:double)))))) (call-fn bigOpt (if var(f) (box (block var(d)))))) (call var(loc) var(d))))
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
                (def x#2 (if var(f) (box var(d))))
                (def r (if (==:bool var(x#2) null) 0.0:double 1.0:double))
                var(r))) (call var(loc) var(d))))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (d:double, f:bool) -> double {nothrow unique}
  (def a 0.0:double)
  (def loc name(bigSink))
  (def k 0:int)
  (block
    (def $licm0 (jcall Math.sin var(d)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (+:double var($licm0) (block
                  (def x#1 (+:double (neg var(d)) (if var(f) 1.0:double 2.0:double)))
                  var(x#1))) (block
                (def x#2 (if var(f) (box var(d))))
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
  var a = 0 ; var mf = true ; var k = 0
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
      (set a += (+:int (call-fn tS var(mf) (widen[short] (call-fn bigByte var(b)))) (call-fn tI var(mf) (widen[int] (call-fn bigByte var(b))))))
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
    (def $licm0 var(b))
    (def $licm1 var(b))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:int (block
              (def v#1 (widen[short] var($licm0)))
              (def r#1 (if var(mf) (call name(int) var(v#1)) 0:int))
              var(r#1)) (block
              (def v#2 (widen[int] var($licm1)))
              (def r#2 (if var(mf) var(v#2) 0:int))
              var(r#2))))
        (set mf (not var(mf)))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (b:byte) -> int {nothrow unique}
  (def a 0:int)
  (def mf true:bool)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (block
            (def v#1 (widen[short] var(b)))
            (def r#1 (if var(mf) (call name(int) var(v#1)) 0:int))
            var(r#1)) (block
            (def v#2 (widen[int] var(b)))
            (def r#2 (if var(mf) var(v#2) 0:int))
            var(r#2))))
      (set mf (not var(mf)))
      (set k += 1:int)))
  var(a)
```

## Hoisting widened non-inlineable calls passed to multi-arg functions (long) and mixed with mutated long

```babble
def tL = fn nothrow (m: bool, v: long): long -> (def r = if m then (v) else (0L) ; return r ; r)
def run = fn (i: int): long -> (
  var a = 0L ; var mf = true ; var ml = 1L ; var k = 0
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
      (set a += (+:long (call-fn tL var(mf) (widen[long] (call-fn bigInt var(i)))) (block (+:long var(ml) (convert[long] (call-fn bigInt var(i)))))))
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
    (def $licm0 var(i))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:long (block
              (def v (widen[long] var($licm0)))
              (def r (if var(mf) var(v) 0:long))
              var(r)) (+:long var(ml) (convert[long] var($licm1)))))
        (set mf (not var(mf)))
        (set ml += 1:long)
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (i:int) -> long {nothrow unique}
  (def a 0:long)
  (def mf true:bool)
  (def ml 1:long)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:long (block
            (def v (widen[long] var(i)))
            (def r (if var(mf) var(v) 0:long))
            var(r)) (+:long var(ml) (convert[long] var(i)))))
      (set mf (not var(mf)))
      (set ml += 1:long)
      (set k += 1:int)))
  var(a)
```
