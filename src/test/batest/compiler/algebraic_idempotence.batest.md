# Idempotent Babble and JDK Call Collapse

References : BIP014-I2, org.babblelang.opt.hir.AlgebraicSimplifyPass

```babble
;; babble preamble
def clamp = fn axiom pure nothrow idempotent (x: int): int -> (if x < 0 then (0) else (x))

;; Non-trailing return prevents InlinePass from inlining bigClamp in round 1 while staying pure/nothrow
def bigClamp = fn axiom pure nothrow idempotent (x: int): int -> (
    def r = if x < 0 then (0) else (x)
    return r
    r
)

var tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
```



## Idempotent Babble (inlined and non-inlined) and Math.abs calls

```babble
def checkIntIdem = fn (x: int, ec: int, ea: int) -> (
    def lc = bigClamp
    assert(clamp(clamp(x)) == ec and bigClamp(bigClamp(x)) == ec and lc(lc(x)) == ec)
    assert(java.lang.Math.abs(java.lang.Math.abs(x)) == ea)
)
checkIntIdem(-5, 0, 5)
checkIntIdem(3, 3, 3)
```

```hir
;; hir -O0 fn=checkIntIdem
fn checkIntIdem (x:int, ec:int, ea:int) -> bool {pure unique}
  (def lc name(bigClamp))
  (call name(assert) (and (and (==:bool (call-fn clamp (call-fn clamp var(x))) var(ec)) (==:bool (call-fn bigClamp (call-fn bigClamp var(x))) var(ec))) (==:bool (call var(lc) (call var(lc) var(x))) var(ec))))
  (call name(assert) (==:bool (jcall Math.abs (jcall Math.abs var(x))) var(ea)))
```

```hir
;; hir -O1 fn=checkIntIdem
fn checkIntIdem (x:int, ec:int, ea:int) -> bool {pure unique}
  (def lc name(bigClamp))
  (call name(assert) (and (and (==:bool (if (<:bool var(x) 0:int) 0:int var(x)) var(ec)) (==:bool (block
            (def r (if (<:bool var(x) 0:int) 0:int var(x)))
            var(r)) var(ec))) (==:bool (call var(lc) (call var(lc) var(x))) var(ec))))
  (call name(assert) (==:bool (jcall Math.abs var(x)) var(ea)))
```

```hir
;; hir -O2 fn=checkIntIdem
fn checkIntIdem (x:int, ec:int, ea:int) -> bool {pure unique}
  (def lc name(bigClamp))
  (block
    (def $cse0 (<:bool var(x) 0:int))
    (call name(assert) (and (and (==:bool (if var($cse0) 0:int var(x)) var(ec)) (==:bool (block
              (def r (if var($cse0) 0:int var(x)))
              var(r)) var(ec))) (==:bool (call var(lc) (call var(lc) var(x))) var(ec))))
    (block
      (def $cse1 (jcall Math.abs var(x)))
      (call name(assert) (==:bool var($cse1) var(ea)))))
```

## Idempotent Math.floor preserving NaN and IEEE-754 -0.0 (checked by raw bits since +0.0 == -0.0)

```babble
def checkFloor = fn (d: double) -> (
    def fl = java.lang.Math.floor(java.lang.Math.floor(d))
    assert(if java.lang.Double.isNaN(d) then (java.lang.Double.isNaN(fl))
        else (java.lang.Double.doubleToRawLongBits(fl) == java.lang.Double.doubleToRawLongBits(d)))
)
checkFloor(java.lang.Double.NaN)
checkFloor(-0.0)
```

```hir
;; hir -O0 fn=checkFloor
fn checkFloor (d:double) -> bool {pure unique}
  (def fl (jcall Math.floor (jcall Math.floor var(d))))
  (call name(assert) (if (jcall Double.isNaN var(d)) (block (jcall Double.isNaN var(fl))) (block (==:bool (jcall Double.doubleToRawLongBits var(fl)) (jcall Double.doubleToRawLongBits var(d))))))
```

```hir
;; hir -O1 fn=checkFloor
fn checkFloor (d:double) -> bool {pure unique}
  (def fl (jcall Math.floor var(d)))
  (call name(assert) (if (jcall Double.isNaN var(d)) (jcall Double.isNaN var(fl)) (==:bool (jcall Double.doubleToRawLongBits var(fl)) (jcall Double.doubleToRawLongBits var(d)))))
```

```hir
;; hir -O2 fn=checkFloor
fn checkFloor (d:double) -> bool {pure unique}
  (block
    (def $cse0 (jcall Math.floor var(d)))
    (call name(assert) (if (jcall Double.isNaN var(d)) (jcall Double.isNaN var($cse0)) (==:bool (jcall Double.doubleToRawLongBits var($cse0)) (jcall Double.doubleToRawLongBits var(d))))))
```

## Idempotent String.trim and Character.toLowerCase calls

```babble
axiom java.lang.Character.toLowerCase: fn pure nothrow idempotent
def checkStrIdem = fn (s: string, ch: char, es: string, ech: char) -> (
    assert(s.trim().trim() == es)
    assert(java.lang.Character.toLowerCase(java.lang.Character.toLowerCase(ch)) == ech)
)
checkStrIdem("  ok ", 'B', "ok", 'b')
checkStrIdem("hi", 'Z', "hi", 'z')
```

```hir
;; hir -O0 fn=checkStrIdem
fn checkStrIdem (s:obj, ch:char, es:obj, ech:char) -> bool {pure unique}
  (call name(assert) (==:bool (jcall String.trim (jcall String.trim var(s))) var(es)))
  (call name(assert) (==:bool (call (select (select (select name(java).lang).Character).toLowerCase) (call (select (select (select name(java).lang).Character).toLowerCase) var(ch))) var(ech)))
```

```hir
;; hir -O1 fn=checkStrIdem
fn checkStrIdem (s:obj, ch:char, es:obj, ech:char) -> bool {pure unique}
  (call name(assert) (==:bool (jcall String.trim var(s)) var(es)))
  (call name(assert) (==:bool (call (select (select (select name(java).lang).Character).toLowerCase) (call (select (select (select name(java).lang).Character).toLowerCase) var(ch))) var(ech)))
```

```hir
;; hir -O2 fn=checkStrIdem
fn checkStrIdem (s:obj, ch:char, es:obj, ech:char) -> bool {pure unique}
  (call name(assert) (==:bool (jcall String.trim var(s)) var(es)))
  (call name(assert) (==:bool (call (select (select (select name(java).lang).Character).toLowerCase) (call (select (select (select name(java).lang).Character).toLowerCase) var(ch))) var(ech)))
```

## Widened idempotent Java call (wideningRank >= 0) and non-prefix Babble idempotent call stay intact

```babble
tick = 1
def lateClamp = fn axiom pure nothrow idempotent (x: int): int -> (if x < 0 then (0) else (x))
def checkGuard = fn (x: int) -> (
    def wd: double = java.lang.Math.abs(java.lang.Math.abs(x))
    assert(wd == 5.0 and lateClamp(lateClamp(x)) == 0)
)
checkGuard(-5)
```

```hir
;; hir -O0 fn=checkGuard
fn checkGuard (x:int) -> bool {pure unique}
  (def wd (widen[double] (jcall Math.abs (jcall Math.abs var(x)))))
  (call name(assert) (and (==:bool var(wd) 5.0:double) (==:bool (call-fn lateClamp (call-fn lateClamp var(x))) 0:int)))
```

```hir
;; hir -O1 fn=checkGuard
fn checkGuard (x:int) -> bool {pure unique}
  (def wd (widen[double] (jcall Math.abs var(x#1))))
  (call name(assert) (and (==:bool var(wd) 5.0:double) (==:bool (block
          name(lateClamp)
          (def x#2
            (block
              name(lateClamp)
              (if (<:bool var(x#1) 0:int) 0:int var(x#1))))
          (if (<:bool var(x#2) 0:int) 0:int var(x#2))) 0:int)))
```

```hir
;; hir -O2 fn=checkGuard
fn checkGuard (x:int) -> bool {pure unique}
  (block
    (def $cse0 (jcall Math.abs var(x#1)))
    (def wd (widen[double] var($cse0)))
    (call name(assert) (and (==:bool var(wd) 5.0:double) (==:bool (block
            name(lateClamp)
            (def x#2
              (block
                name(lateClamp)
                (if (<:bool var(x#1) 0:int) 0:int var(x#1))))
            (if (<:bool var(x#2) 0:int) 0:int var(x#2))) 0:int))))
```

## Non-dropping idempotent collapse on impure arguments (`f(f(impure())) -> f(impure())`)

```babble
def bumpStr = fn io (): string -> (tick += 1 ; "  ok  ")
def a = bigClamp(bigClamp(bump() - 10))
def b = java.lang.Math.abs(java.lang.Math.abs(bump() - 10))
def s = bumpStr().trim().trim()
assert(a == 0 and b == 8 and s == "ok" and tick == 3)
```

```hir
;; hir -O0 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpStr (fn-ref bumpStr))
  (def a (call-fn bigClamp (call-fn bigClamp (-:int (call-fn bump) 10:int))))
  (def b (jcall Math.abs (jcall Math.abs (-:int (call-fn bump) 10:int))))
  (def s (jcall String.trim (jcall String.trim (call-fn bumpStr))))
  (call name(assert) (and (and (and (==:bool name(a) 0:int) (==:bool name(b) 8:int)) (==:bool name(s) "ok")) (==:bool name(tick) 3:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpStr (fn-ref bumpStr))
  (def a
    (block
      (def x (-:int (call-fn bump) 10:int))
      (def r (if (<:bool var(x) 0:int) 0:int var(x)))
      var(r)))
  (def b (jcall Math.abs (-:int (call-fn bump) 10:int)))
  (def s (jcall String.trim (call-fn bumpStr)))
  (call name(assert) (and (and (and (==:bool name(a) 0:int) (==:bool name(b) 8:int)) (==:bool name(s) "ok")) (==:bool name(tick) 3:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpStr (fn-ref bumpStr))
  (def a
    (block
      (def x (-:int (call-fn bump) 10:int))
      (def r (if (<:bool var(x) 0:int) 0:int var(x)))
      var(r)))
  (def b (jcall Math.abs (-:int (call-fn bump) 10:int)))
  (def s (jcall String.trim (call-fn bumpStr)))
  (call name(assert) (and (and (and (==:bool name(a) 0:int) (==:bool name(b) 8:int)) (==:bool name(s) "ok")) (==:bool name(tick) 3:int)))
```

## Idempotent collapse on complex deterministic expressions (HirPurity.isDeterministic)

```babble
def idT of T = fn (x: T): T -> (x)
def checkDet = fn (arr: array of int, s: string, flag: bool): int -> (
    def a = bigClamp(bigClamp(if flag and true then (arr.size() + s.length()) else (idT of int(1))))
    def b = java.lang.Math.abs(java.lang.Math.abs(int(arr.size() as double)))
    a + b
)
assert(checkDet((array of int).of(1, 2), "abc", true) == 7)
```

```hir
;; hir -O0 fn=checkDet
fn checkDet (arr:obj, s:obj, flag:bool) -> int {pure unique}
  (def a (call-fn bigClamp (call-fn bigClamp (if (and var(flag) true:bool) (block (+:int (array-op SIZE var(arr)) (jcall String.length var(s)))) (block (call (type-apply name(idT)) 1:int))))))
  (def b (jcall Math.abs (jcall Math.abs (call name(int) (as (array-op SIZE var(arr)) double|double|)))))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=checkDet
fn checkDet (arr:obj, s:obj, flag:bool) -> int {pure unique}
  (def a
    (block
      (def x (if (and var(flag) true:bool) (+:int (array-op SIZE var(arr)) (jcall String.length var(s))) (call (type-apply name(idT)) 1:int)))
      (def r (if (<:bool var(x) 0:int) 0:int var(x)))
      var(r)))
  (def b (jcall Math.abs (call name(int) (as (array-op SIZE var(arr)) double|double|))))
  (+:int var(a) var(b))
```

```hir
;; hir -O2 fn=checkDet
fn checkDet (arr:obj, s:obj, flag:bool) -> int {pure unique}
  (def a
    (block
      (def x (if (and var(flag) true:bool) (+:int (array-op SIZE var(arr)) (jcall String.length var(s))) (call (type-apply name(idT)) 1:int)))
      (def r (if (<:bool var(x) 0:int) 0:int var(x)))
      var(r)))
  (block
    (def $cse0 (array-op SIZE var(arr)))
    (def b (jcall Math.abs (call name(int) (as var($cse0) double|double|))))
    (+:int var(a) var(b)))
```
