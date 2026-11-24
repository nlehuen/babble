# Algebraic simplification : boolean/int/long identities, constant reassociation, idempotence, IEEE-754 & imp...

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

def tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
```

## Parameterized inputs prevent -O1 ConstFoldPass from folding away the algebraic identities

```babble
def checkBool = fn (b: bool) -> (
    assert((not (not b)) == b)
    assert((b == true) == b and (b != true) == (not b))
    assert((b == false) == (not b) and (b != false) == b)
    assert((true == b) == b and (false == b) == (not b))
    assert((true != b) == (not b) and (false != b) == b)
)
checkBool(true)
checkBool(false)
```

```hir
;; hir -O0 fn=checkBool
fn checkBool (b:bool) -> bool {pure unique}
  (call name(assert) (==:bool (block (not (block (not var(b))))) var(b)))
  (call name(assert) (and (==:bool (block (==:bool var(b) true:bool)) var(b)) (==:bool (block (<>:bool var(b) true:bool)) (block (not var(b))))))
  (call name(assert) (and (==:bool (block (==:bool var(b) false:bool)) (block (not var(b)))) (==:bool (block (<>:bool var(b) false:bool)) var(b))))
  (call name(assert) (and (==:bool (block (==:bool true:bool var(b))) var(b)) (==:bool (block (==:bool false:bool var(b))) (block (not var(b))))))
  (call name(assert) (and (==:bool (block (<>:bool true:bool var(b))) (block (not var(b)))) (==:bool (block (<>:bool false:bool var(b))) var(b))))
```

```hir
;; hir -O1 fn=checkBool
fn checkBool (b:bool) -> bool {pure unique}
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) true:bool)
```

## Case 2

```babble
def checkInt = fn (x: int, eAdd0: int, eAddC: int, eMul: int) -> (
    assert((x + 0) + (0 + x) == eAdd0)
    assert((5 + x) + ((x + 1) + 2) == eAddC)
    assert((x - 0) + (0 - x) + (x - x) == 0)
    assert((x * 1) + (1 * x) + (x * 0) + (0 * x) + (3 * x) + ((x * 2) * 3) == eMul)
)
checkInt(-5, -10, -2, -55)
checkInt(3, 6, 14, 33)
```

```hir
;; hir -O0 fn=checkInt
fn checkInt (x:int, eAdd0:int, eAddC:int, eMul:int) -> bool {pure unique}
  (call name(assert) (==:bool (+:int (block (+:int var(x) 0:int)) (block (+:int 0:int var(x)))) var(eAdd0)))
  (call name(assert) (==:bool (+:int (block (+:int 5:int var(x))) (block (+:int (block (+:int var(x) 1:int)) 2:int))) var(eAddC)))
  (call name(assert) (==:bool (+:int (+:int (block (-:int var(x) 0:int)) (block (-:int 0:int var(x)))) (block (-:int var(x) var(x)))) 0:int))
  (call name(assert) (==:bool (+:int (+:int (+:int (+:int (+:int (block (*:int var(x) 1:int)) (block (*:int 1:int var(x)))) (block (*:int var(x) 0:int))) (block (*:int 0:int var(x)))) (block (*:int 3:int var(x)))) (block (*:int (block (*:int var(x) 2:int)) 3:int))) var(eMul)))
```

```hir
;; hir -O1 fn=checkInt
fn checkInt (x:int, eAdd0:int, eAddC:int, eMul:int) -> bool {pure unique}
  (call name(assert) (==:bool (+:int var(x) var(x)) var(eAdd0)))
  (call name(assert) (==:bool (+:int (+:int var(x) var(x)) 8:int) var(eAddC)))
  (call name(assert) (==:bool (+:int var(x) (neg var(x))) 0:int))
  (call name(assert) (==:bool (+:int (+:int (+:int var(x) var(x)) (*:int var(x) 3:int)) (*:int var(x) 6:int)) var(eMul)))
```

## Case 3

```babble
def checkLong = fn (lx: long, eAdd0: long, eAddC: long, eMul: long) -> (
    assert((lx + 0L) + (0L + lx) == eAdd0)
    assert((5L + lx) + ((lx + 1L) + 2L) == eAddC)
    assert((lx - 0L) + (0L - lx) + (lx - lx) == 0L)
    assert((lx * 1L) + (1L * lx) + (lx * 0L) + (0L * lx) + (3L * lx) + ((lx * 2L) * 3L) == eMul)
)
checkLong(4L, 8L, 16L, 44L)
checkLong(-2L, -4L, 4L, -22L)
```

```hir
;; hir -O0 fn=checkLong
fn checkLong (lx:long, eAdd0:long, eAddC:long, eMul:long) -> bool {pure unique}
  (call name(assert) (==:bool (+:long (block (+:long var(lx) 0:long)) (block (+:long 0:long var(lx)))) var(eAdd0)))
  (call name(assert) (==:bool (+:long (block (+:long 5:long var(lx))) (block (+:long (block (+:long var(lx) 1:long)) 2:long))) var(eAddC)))
  (call name(assert) (==:bool (+:long (+:long (block (-:long var(lx) 0:long)) (block (-:long 0:long var(lx)))) (block (-:long var(lx) var(lx)))) 0:long))
  (call name(assert) (==:bool (+:long (+:long (+:long (+:long (+:long (block (*:long var(lx) 1:long)) (block (*:long 1:long var(lx)))) (block (*:long var(lx) 0:long))) (block (*:long 0:long var(lx)))) (block (*:long 3:long var(lx)))) (block (*:long (block (*:long var(lx) 2:long)) 3:long))) var(eMul)))
```

```hir
;; hir -O1 fn=checkLong
fn checkLong (lx:long, eAdd0:long, eAddC:long, eMul:long) -> bool {pure unique}
  (call name(assert) (==:bool (+:long var(lx) var(lx)) var(eAdd0)))
  (call name(assert) (==:bool (+:long (+:long var(lx) var(lx)) 8:long) var(eAddC)))
  (call name(assert) (==:bool (+:long var(lx) (neg var(lx))) 0:long))
  (call name(assert) (==:bool (+:long (+:long (+:long var(lx) var(lx)) (*:long var(lx) 3:long)) (*:long var(lx) 6:long)) var(eMul)))
```

## String reassociation and IEEE-754 preservation (NaN * 0.0 != 0.0, no float/double reassociation)

```babble
def checkStrIeee = fn (s: string, d: double, expStr: string, expNan: bool) -> (
    assert((s + "a") + "b" == expStr)
    assert(java.lang.Double.isNaN((d * 0.0) + 0.0) == expNan)
    if expNan then (assert(java.lang.Double.isNaN((d + 1.0) + 2.0)))
    else (assert((d + 1.0) + 2.0 == 3.0))
)
checkStrIeee("  ok ", java.lang.Double.NaN, "  ok ab", true)
checkStrIeee("hi", -0.0, "hiab", false)
```

```hir
;; hir -O0 fn=checkStrIeee
fn checkStrIeee (s:obj, d:double, expStr:obj, expNan:bool) -> bool {pure}
  (call name(assert) (==:bool (+:obj (block (+:obj var(s) "a")) "b") var(expStr)))
  (call name(assert) (==:bool (jcall Double.isNaN (+:double (block (*:double var(d) 0.0:double)) 0.0:double)) var(expNan)))
  (if var(expNan) (block (call name(assert) (jcall Double.isNaN (+:double (block (+:double var(d) 1.0:double)) 2.0:double)))) (block (call name(assert) (==:bool (+:double (block (+:double var(d) 1.0:double)) 2.0:double) 3.0:double))))
```

```hir
;; hir -O1 fn=checkStrIeee
fn checkStrIeee (s:obj, d:double, expStr:obj, expNan:bool) -> bool {pure}
  (call name(assert) (==:bool (+:obj var(s) "ab") var(expStr)))
  (call name(assert) (==:bool (jcall Double.isNaN (+:double (*:double var(d) 0.0:double) 0.0:double)) var(expNan)))
  (if var(expNan) (call name(assert) (jcall Double.isNaN (+:double (+:double var(d) 1.0:double) 2.0:double))) (call name(assert) (==:bool (+:double (+:double var(d) 1.0:double) 2.0:double) 3.0:double)))
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

## Impure operands must not be dropped by * 0 or reordered

```babble
def r = (bump() * 0) + (0 * bump()) + (1 + bump()) + (2 * bump())
assert(r == 12 and tick == 4)
```

```hir
;; hir -O0 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def r (+:int (+:int (+:int (block (*:int (call-fn bump) 0:int)) (block (*:int 0:int (call-fn bump)))) (block (+:int 1:int (call-fn bump)))) (block (*:int 2:int (call-fn bump)))))
  (call name(assert) (and (==:bool name(r) 12:int) (==:bool name(tick) 4:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def r (+:int (+:int (+:int (*:int (call-fn bump) 0:int) (*:int 0:int (call-fn bump))) (+:int 1:int (call-fn bump))) (*:int 2:int (call-fn bump))))
  (call name(assert) (and (==:bool name(r) 12:int) (==:bool name(tick) 4:int)))
```

## Non-constant inner RHS does not reassociate; non-matching idempotent calls stay intact

```babble
def checkNonReassoc = fn (x: int, y: int, s: string, t: string) -> (
    assert((x + y) + 2 == 7 and (x * y) * 3 == 18 and (s + t) + "!" == "ab!")
    assert(clamp(bigClamp(x)) == 2)
)
checkNonReassoc(2, 3, "a", "b")
```

```hir
;; hir -O0 fn=checkNonReassoc
fn checkNonReassoc (x:int, y:int, s:obj, t:obj) -> bool {pure unique}
  (call name(assert) (and (and (==:bool (+:int (block (+:int var(x) var(y))) 2:int) 7:int) (==:bool (*:int (block (*:int var(x) var(y))) 3:int) 18:int)) (==:bool (+:obj (block (+:obj var(s) var(t))) "!") "ab!")))
  (call name(assert) (==:bool (call-fn clamp (call-fn bigClamp var(x))) 2:int))
```

```hir
;; hir -O1 fn=checkNonReassoc
fn checkNonReassoc (x:int, y:int, s:obj, t:obj) -> bool {pure unique}
  (call name(assert) (and (and (==:bool (+:int (+:int var(x#1) var(y)) 2:int) 7:int) (==:bool (*:int (*:int var(x#1) var(y)) 3:int) 18:int)) (==:bool (+:obj (+:obj var(s) var(t)) "!") "ab!")))
  (call name(assert) (==:bool (block
        (def x#2
          (block
            (def r (if (<:bool var(x#1) 0:int) 0:int var(x#1)))
            var(r)))
        (if (<:bool var(x#2) 0:int) 0:int var(x#2))) 2:int))
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
  (def wd (jcall Math.abs (jcall Math.abs var(x))))
  (call name(assert) (and (==:bool var(wd) 5.0:double) (==:bool (call-fn lateClamp (call-fn lateClamp var(x))) 0:int)))
```

```hir
;; hir -O1 fn=checkGuard
fn checkGuard (x:int) -> bool {pure unique}
  (def wd (jcall Math.abs var(x#1)))
  (call name(assert) (and (==:bool var(wd) 5.0:double) (==:bool (block
          name(lateClamp)
          (def x#2
            (block
              name(lateClamp)
              (if (<:bool var(x#1) 0:int) 0:int var(x#1))))
          (if (<:bool var(x#2) 0:int) 0:int var(x#2))) 0:int)))
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

## Non-dropping arithmetic identities with impure operands (`e + 0`, `0 + e`, `e - 0`, `e * 1`, `1 * e`)

```babble
def r = (bump() + 0) + (0 + bump()) + (bump() - 0) + (bump() * 1) + (1 * bump())
assert(r == 15 and tick == 5)
```

```hir
;; hir -O0 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def r (+:int (+:int (+:int (+:int (block (+:int (call-fn bump) 0:int)) (block (+:int 0:int (call-fn bump)))) (block (-:int (call-fn bump) 0:int))) (block (*:int (call-fn bump) 1:int))) (block (*:int 1:int (call-fn bump)))))
  (call name(assert) (and (==:bool name(r) 15:int) (==:bool name(tick) 5:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def r (+:int (+:int (+:int (+:int (call-fn bump) (call-fn bump)) (call-fn bump)) (call-fn bump)) (call-fn bump)))
  (call name(assert) (and (==:bool name(r) 15:int) (==:bool name(tick) 5:int)))
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

## Canonical HIR golden : algebraic identities and integer reassociation (-O0 vs -O1)

```babble
def simplify = fn (x: int): int -> ( (x + 0) * 1 + (x - x) )
def reassoc = fn (x: int): int -> ( (x + 1) + 2 )
assert(simplify(7) == 7 and reassoc(5) == 8)
```

```hir
;; hir -O0 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  (+:int (*:int (block (+:int var(x) 0:int)) 1:int) (block (-:int var(x) var(x))))
```

```hir
;; hir -O1 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  var(x)
```

```hir
;; hir -O1 fn=reassoc
fn reassoc (x:int) -> int {nothrow injective unique}
  (+:int var(x) 3:int)
```

## Canonical HIR golden : idempotent collapse and stage=after:HirEGraphSimplify

```babble
def norm = fn (x: int): int -> (
    bigClamp(bigClamp(java.lang.Math.abs(java.lang.Math.abs(x))))
)
def clean = fn (s: string): string -> ( s.trim().trim() )
assert(norm(-5) == 5 and norm(3) == 3 and clean("  ok  ") == "ok")
```

```hir
;; hir -O0 fn=norm
fn norm (x:int) -> int {nothrow unique}
  (call-fn bigClamp (call-fn bigClamp (jcall Math.abs (jcall Math.abs var(x)))))
```

```hir
;; hir -O1 fn=norm stage=after:HirEGraphSimplify
fn norm (x:int) -> int {nothrow unique}
  (block
    (def x#2 (jcall Math.abs var(x#1)))
    (def r (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
    var(r))
```

```hir
;; hir -O1 fn=norm
fn norm (x:int) -> int {nothrow unique}
  (block
    (def x#2 (jcall Math.abs var(x#1)))
    (def r (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
    var(r))
```

```hir
;; hir -O0 fn=clean
fn clean (s:obj) -> obj {nothrow idempotent unique}
  (jcall String.trim (jcall String.trim var(s)))
```

```hir
;; hir -O1 fn=clean
fn clean (s:obj) -> obj {nothrow idempotent unique}
  (jcall String.trim var(s))
```

## Canonical HIR golden : strict IEEE-754 double preservation (-O0,-O1)

```babble
def add3 = fn (x: double): double -> ( x + 1.0 + 2.0 )
assert(add3(1.0) == 4.0 and java.lang.Double.isNaN(add3(java.lang.Double.NaN)))
```

```hir
;; hir -O0,-O1 fn=add3
fn add3 (x:double) -> double {nothrow monotone unique}
  (+:double (+:double var(x) 1.0:double) 2.0:double)
```

## Double integer negation and byte/short unary negation in E-graph

```babble
def dblNegI = fn (x: int): int -> ( -(-x) )
def dblNegL = fn (x: long): long -> ( -(-x) )
def negNarrow = fn (b: byte, s: short): int -> ( ((-b) + 0) + ((-s) + 0) )
assert(dblNegI(7) == 7 and dblNegL(-9L) == -9L and negNarrow(byte(3), short(4)) == -7)
```

```hir
;; hir -O0 fn=dblNegI
fn dblNegI (x:int) -> int {nothrow idempotent injective unique}
  (neg (block (neg var(x))))
```

```hir
;; hir -O1 fn=dblNegI
fn dblNegI (x:int) -> int {nothrow idempotent injective unique}
  var(x)
```

```hir
;; hir -O0 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (+:int (block (+:int (block (neg var(b))) 0:int)) (block (+:int (block (neg var(s))) 0:int)))
```

```hir
;; hir -O1 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (+:int (neg var(b)) (neg var(s)))
```

## E-graph rebuild of boolean EQ/NEQ and non-static String.trim receiver

```babble
def eqSimp = fn (a: bool, b: bool): bool -> ( (not (not a)) == b )
def neqSimp = fn (a: bool, b: bool): bool -> ( (not (not a)) != b )
def trimCat = fn (s: string): string -> ( ((s + "a") + "b").trim() )
assert(eqSimp(true, true) and neqSimp(true, false) and trimCat("  x") == "xab")
```

```hir
;; hir -O0 fn=eqSimp
fn eqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (==:bool (block (not (block (not var(a))))) var(b))
```

```hir
;; hir -O1 fn=eqSimp
fn eqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (==:bool var(a) var(b))
```

```hir
;; hir -O0 fn=neqSimp
fn neqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (<>:bool (block (not (block (not var(a))))) var(b))
```

```hir
;; hir -O1 fn=neqSimp
fn neqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (<>:bool var(a) var(b))
```

```hir
;; hir -O0 fn=trimCat
fn trimCat (s:obj) -> obj {nothrow unique}
  (jcall String.trim (block (+:obj (block (+:obj var(s) "a")) "b")))
```

```hir
;; hir -O1 fn=trimCat
fn trimCat (s:obj) -> obj {nothrow unique}
  (jcall String.trim (+:obj var(s) "ab"))
```

## Non-dropping identities on impure bool double negation and long arithmetic

```babble
def bumpBool = fn io (): bool -> (tick += 1 ; true)
def bumpOpt = fn io (): int? -> (tick += 1 ; 1)
def bumpL = fn io (): long -> (tick += 1 ; 2L)
def b1 = not (not bumpBool())
def b2 = not (not bumpOpt())
def b3 = not (-bump() == 0)
def lv = (bumpL() + 0L) + (0L + bumpL()) + (bumpL() - 0L) + (0L - bumpL()) + (bumpL() * 1L) + (1L * bumpL())
assert(b1 and b2 and b3 and lv == 8L and tick == 9)
```

```hir
;; hir -O0 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpBool (fn-ref bumpBool))
  (def bumpOpt (fn-ref bumpOpt))
  (def bumpL (fn-ref bumpL))
  (def b1 (not (block (not (call-fn bumpBool)))))
  (def b2 (not (block (not (call-fn bumpOpt)))))
  (def b3 (not (block (==:bool (neg (call-fn bump)) 0:int))))
  (def lv (+:long (+:long (+:long (+:long (+:long (block (+:long (call-fn bumpL) 0:long)) (block (+:long 0:long (call-fn bumpL)))) (block (-:long (call-fn bumpL) 0:long))) (block (-:long 0:long (call-fn bumpL)))) (block (*:long (call-fn bumpL) 1:long))) (block (*:long 1:long (call-fn bumpL)))))
  (call name(assert) (and (and (and (and name(b1) name(b2)) name(b3)) (==:bool name(lv) 8:long)) (==:bool name(tick) 9:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpBool (fn-ref bumpBool))
  (def bumpOpt (fn-ref bumpOpt))
  (def bumpL (fn-ref bumpL))
  (def b1 (call-fn bumpBool))
  (def b2 (not (not (call-fn bumpOpt))))
  (def b3 (<>:bool (neg (call-fn bump)) 0:int))
  (def lv (+:long (+:long (+:long (+:long (+:long (call-fn bumpL) (call-fn bumpL)) (call-fn bumpL)) (neg (call-fn bumpL))) (call-fn bumpL)) (call-fn bumpL)))
  (call name(assert) (and (and (and (and name(b1) name(b2)) name(b3)) (==:bool name(lv) 8:long)) (==:bool name(tick) 9:int)))
```

## Idempotent Java eligibility guards : axiom String.toUpperCase and String.toLowerCase with arg

```babble
axiom java.lang.String.toUpperCase: fn pure nothrow idempotent
axiom java.lang.String.toLowerCase: fn pure nothrow idempotent
def f = fn (s: string): bool -> (
    s.toUpperCase().toUpperCase() == "AB" and s.toLowerCase(java.util.Locale.ROOT) == "ab"
)
assert(f("Ab"))
```

```hir
;; hir -O0,-O1 fn=f
fn f (s:obj) -> bool {pure unique}
  (and (==:bool (jcall String.toUpperCase (jcall String.toUpperCase var(s))) "AB") (==:bool (jcall String.toLowerCase var(s) (get-jfield Locale.ROOT)) "ab"))
```

## Mismatched nested static and instance idempotent Java calls on impure args

```babble
axiom java.lang.String.toUpperCase: fn pure nothrow idempotent
def bumpD = fn io (): double -> (tick += 1 ; -2.5)
def bumpS = fn io (): string -> (tick += 1 ; "  ab ")
def f = fn io (): bool -> (
    java.lang.Math.abs(java.lang.Math.floor(bumpD())) == 3.0 and bumpS().toUpperCase().trim() == "AB"
)
assert(f() and tick == 2)
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> bool {io}
  (and (==:bool (jcall Math.abs (jcall Math.floor (call-fn bumpD))) 3.0:double) (==:bool (jcall String.trim (jcall String.toUpperCase (call-fn bumpS))) "AB"))
```

## Widened E-graph roots and children inside idempotent Babble calls

```babble
def bigClampD = fn axiom pure nothrow idempotent (v: double): double -> (
    def r = if v < 0.0 then (0.0) else (v) ; return r ; r
)
def f = fn (x: int, y: int): double -> (
    ((-x) + 1.0) + ((x + y) + 1.0) + (bigClamp(x) + 1.0) +
    bigClampD(x) + bigClampD(-x) + bigClampD(x + y)
)
assert(f(2, 3) == 15.0)
```

```hir
;; hir -O0 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (+:double (+:double (+:double (+:double (+:double (block (+:double (block (neg var(x))) 1.0:double)) (block (+:double (block (+:int var(x) var(y))) 1.0:double))) (block (+:double (call-fn bigClamp var(x)) 1.0:double))) (call-fn bigClampD var(x))) (call-fn bigClampD (neg var(x)))) (call-fn bigClampD (+:int var(x) var(y))))
```

```hir
;; hir -O1 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (+:double (+:double (+:double (+:double (+:double (+:double (neg var(x)) 1.0:double) (+:double (+:int var(x) var(y)) 1.0:double)) (+:double (block
              (def r#1 (if (<:bool var(x) 0:int) 0:int var(x)))
              var(r#1)) 1.0:double)) (block
          (def v#1 var(x))
          (def r#2 (if (<:bool var(v#1) 0.0:double) 0.0:double var(v#1)))
          var(r#2))) (block
        (def v#2 (neg var(x)))
        (def r#3 (if (<:bool var(v#2) 0.0:double) 0.0:double var(v#2)))
        var(r#3))) (block
      (def v#3 (+:int var(x) var(y)))
      (def r#4 (if (<:bool var(v#3) 0.0:double) 0.0:double var(v#3)))
      var(r#4)))
```

## Reflexive equality (a == a -> true, a != a -> false) on primitives (including NaN) and strings

```babble
def reflEq = fn (b: bool, x: int, d: double, f: float, s: string): bool -> (
    (b == b) and not (b != b) and ((x + 1) == (1 + x)) and not (x != x) and
    (d == d) and not (d != d) and (f == f) and not (f != f) and
    (s == s) and not (((s + "a") + "b") != (s + "ab"))
)
assert(reflEq(false, 7, java.lang.Double.NaN, java.lang.Float.NaN, "hi"))
```

```hir
;; hir -O0 fn=reflEq
fn reflEq (b:bool, x:int, d:double, f:float, s:obj) -> bool {nothrow unique}
  (and (and (and (and (and (and (and (and (and (block (==:bool var(b) var(b))) (not (block (<>:bool var(b) var(b))))) (block (==:bool (block (+:int var(x) 1:int)) (block (+:int 1:int var(x)))))) (not (block (<>:bool var(x) var(x))))) (block (==:bool var(d) var(d)))) (not (block (<>:bool var(d) var(d))))) (block (==:bool var(f) var(f)))) (not (block (<>:bool var(f) var(f))))) (block (==:bool var(s) var(s)))) (not (block (<>:bool (block (+:obj (block (+:obj var(s) "a")) "b")) (block (+:obj var(s) "ab"))))))
```

```hir
;; hir -O1 fn=reflEq
fn reflEq (b:bool, x:int, d:double, f:float, s:obj) -> bool {nothrow unique}
  true:bool
```

## Reflexive equality must not rewrite arbitrary any or impure operands

```babble
def checkNoRefl = fn io (a: any): bool -> (
    (a == a) and not (a != a) and not (bump() == bump()) and (bump() != bump())
)
assert(checkNoRefl(java.lang.Object()) and tick == 4)
```

```hir
;; hir -O0 fn=checkNoRefl
fn checkNoRefl (a:obj) -> bool {io}
  (and (and (and (block (==:bool var(a) var(a))) (not (block (<>:bool var(a) var(a))))) (not (block (==:bool (call-fn bump) (call-fn bump))))) (block (<>:bool (call-fn bump) (call-fn bump))))
```

```hir
;; hir -O1 fn=checkNoRefl
fn checkNoRefl (a:obj) -> bool {io}
  (and (and (and (==:bool var(a) var(a)) (==:bool var(a) var(a))) (<>:bool (call-fn bump) (call-fn bump))) (<>:bool (call-fn bump) (call-fn bump)))
```

## Negated comparison rewrites (not (a == b) -> a != b, etc.) and E-graph equivalence

```babble
def checkNegCmp = fn (x: int, y: int, s: string, t: string): bool -> (
    (not (x == y)) == (x != y) and (not (x != y)) == (x == y) and
    (not (x < y)) == (x >= y) and (not (x <= y)) == (x > y) and
    (not (x > y)) == (x <= y) and (not (x >= y)) == (x < y) and
    (not (s == t)) == (s != t) and (not (s != t)) == (s == t)
)
assert(checkNegCmp(2, 5, "a", "b") and checkNegCmp(3, 3, "a", "a"))
```

```hir
;; hir -O0 fn=checkNegCmp
fn checkNegCmp (x:int, y:int, s:obj, t:obj) -> bool {nothrow unique}
  (and (and (and (and (and (and (and (==:bool (block (not (block (==:bool var(x) var(y))))) (block (<>:bool var(x) var(y)))) (==:bool (block (not (block (<>:bool var(x) var(y))))) (block (==:bool var(x) var(y))))) (==:bool (block (not (block (<:bool var(x) var(y))))) (block (>=:bool var(x) var(y))))) (==:bool (block (not (block (<=:bool var(x) var(y))))) (block (>:bool var(x) var(y))))) (==:bool (block (not (block (>:bool var(x) var(y))))) (block (<=:bool var(x) var(y))))) (==:bool (block (not (block (>=:bool var(x) var(y))))) (block (<:bool var(x) var(y))))) (==:bool (block (not (block (==:bool var(s) var(t))))) (block (<>:bool var(s) var(t))))) (==:bool (block (not (block (<>:bool var(s) var(t))))) (block (==:bool var(s) var(t)))))
```

```hir
;; hir -O1 fn=checkNegCmp
fn checkNegCmp (x:int, y:int, s:obj, t:obj) -> bool {nothrow unique}
  true:bool
```

