# LICM Hoisting : Widened Calls, Java Axioms, and Captures

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



## Hoisting widened non-inlineable float calls mixed with mutated float

```babble
def tF = fn nothrow (m: bool, v: float): float -> (def r = if m then (v) else (0.0f) ; return r ; r)
def run = fn (i: int): float -> (
  var a = 0.0f ; var mf = true ; var mfl = 1.0f ; var k = 0
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
      (set a += (+:float (call-fn tF var(mf) (widen[float] (call-fn bigInt var(i)))) (block (+:float var(mfl) (convert[float] (call-fn bigInt var(i)))))))
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
    (def $licm0 var(i))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:float (block
              (def v (widen[float] var($licm0)))
              (def r (if var(mf) var(v) 0.0:float))
              var(r)) (+:float var(mfl) (convert[float] var($licm1)))))
        (set mf (not var(mf)))
        (set mfl += 1.0:float)
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (i:int) -> float {nothrow unique}
  (def a 0.0:float)
  (def mf true:bool)
  (def mfl 1.0:float)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:float (block
            (def v (widen[float] var(i)))
            (def r (if var(mf) var(v) 0.0:float))
            var(r)) (+:float var(mfl) (convert[float] var(i)))))
      (set mf (not var(mf)))
      (set mfl += 1.0:float)
      (set k += 1:int)))
  var(a)
```

## Hoisting widened non-inlineable double calls mixed with mutated double

```babble
def tD = fn nothrow (m: bool, v: double): double -> (def r = if m then (v) else (0.0) ; return r ; r)
def run = fn (fl: float, i: int): double -> (
  var a = 0.0 ; var mf = true ; var md = 1.0 ; var k = 0
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
      (set a += (+:double (call-fn tD var(mf) (widen[double] (call-fn bigFloat var(fl)))) (block (+:double var(md) (convert[double] (call-fn bigInt var(i)))))))
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
    (def $licm0 var(fl))
    (def $licm1 var(i))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (block
              (def v (widen[double] var($licm0)))
              (def r (if var(mf) var(v) 0.0:double))
              var(r)) (+:double var(md) (convert[double] var($licm1)))))
        (set mf (not var(mf)))
        (set md += 1.0:double)
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (fl:float, i:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def mf true:bool)
  (def md 1.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (block
            (def v (widen[double] var(fl)))
            (def r (if var(mf) var(v) 0.0:double))
            var(r)) (+:double var(md) (convert[double] var(i)))))
      (set mf (not var(mf)))
      (set md += 1.0:double)
      (set k += 1:int)))
  var(a)
```

## Pure Java axioms hoisted (primitive/string args) vs mutable Point arg rejected by allArgsPrimitiveOrImmutable

```babble
axiom java.lang.Integer.bitCount: fn pure nothrow
axiom java.util.Objects.nonNull: fn pure nothrow
def run = fn io (ro: int): int -> (
  var a = 0 ; def pt = java.awt.Point(1, 2) ; var k = 0
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

```hir
;; hir -O2 fn=run
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
  var a = 0.0 ; var ml = 1.0 ; var k = 0
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
        (def q (/:double 10.0:double (convert[double] var(ro))))
        (call name(double) var(q))))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (+:double (call name(double) 2:int) (jcall Math.sin var(ml))) var($licm0)))
        (set ml += 0.5:double)
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (ro:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def ml 1.0:double)
  (def k 0:int)
  (block
    (def $licm0 (/:double 10.0:double (convert[double] var(ro))))
    (while (<:bool var(k) 2:int)
      (block
        (def $cse0 name(double))
        (set a += (+:double (+:double (call var($cse0) 2:int) (jcall Math.sin var(ml))) (call var($cse0) var($licm0))))
        (set ml += 0.5:double)
        (set k += 1:int))))
  var(a)
```

## Captured variable mutated by closure blocks LICM on bigInt(cap)

```babble
def run = fn io (): int -> (
  var a = 0 ; var cap = 2 ; def bump = fn io (): int -> (cap += 1 ; cap) ; var k = 0
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
      (if false:bool (box (block (set a += (call-fn bump)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
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

```hir
;; hir -O2 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def cap 2:int)
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
