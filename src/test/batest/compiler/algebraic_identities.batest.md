# Algebraic Identities, Reassociation, and IEEE-754 Preservation

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

```hir
;; hir -O2 fn=checkBool
fn checkBool (b:bool) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (call var($cse0) true:bool)
    (call var($cse0) true:bool)
    (call var($cse0) true:bool)
    (call var($cse0) true:bool)
    (call var($cse0) true:bool))
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

```hir
;; hir -O2 fn=checkInt
fn checkInt (x:int, eAdd0:int, eAddC:int, eMul:int) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (def $cse1 (+:int var(x) var(x)))
    (call var($cse0) (==:bool var($cse1) var(eAdd0)))
    (call var($cse0) (==:bool (+:int var($cse1) 8:int) var(eAddC)))
    (def $cse2 (neg var(x)))
    (def $cse3 (+:int var(x) var($cse2)))
    (call var($cse0) (==:bool var($cse3) 0:int))
    (call var($cse0) (==:bool (+:int (+:int var($cse1) (*:int var(x) 3:int)) (*:int var(x) 6:int)) var(eMul))))
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

```hir
;; hir -O2 fn=checkLong
fn checkLong (lx:long, eAdd0:long, eAddC:long, eMul:long) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (def $cse1 (+:long var(lx) var(lx)))
    (call var($cse0) (==:bool var($cse1) var(eAdd0)))
    (call var($cse0) (==:bool (+:long var($cse1) 8:long) var(eAddC)))
    (def $cse2 (neg var(lx)))
    (def $cse3 (+:long var(lx) var($cse2)))
    (call var($cse0) (==:bool var($cse3) 0:long))
    (call var($cse0) (==:bool (+:long (+:long var($cse1) (*:long var(lx) 3:long)) (*:long var(lx) 6:long)) var(eMul))))
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

```hir
;; hir -O2 fn=checkStrIeee
fn checkStrIeee (s:obj, d:double, expStr:obj, expNan:bool) -> bool {pure}
  (block
    (def $cse0 name(assert))
    (call var($cse0) (==:bool (+:obj var(s) "ab") var(expStr)))
    (call var($cse0) (==:bool (jcall Double.isNaN (+:double (*:double var(d) 0.0:double) 0.0:double)) var(expNan)))
    (if var(expNan) (call var($cse0) (jcall Double.isNaN (+:double (+:double var(d) 1.0:double) 2.0:double))) (call var($cse0) (==:bool (+:double (+:double var(d) 1.0:double) 2.0:double) 3.0:double))))
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

```hir
;; hir -O2 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (block
    (def $cse0 name(bump))
    (def r (+:int (+:int (+:int (*:int (call-fn bump) 0:int) (*:int 0:int (call-fn bump))) (+:int 1:int (call-fn bump))) (*:int 2:int (call-fn bump))))
    (call name(assert) (and (==:bool name(r) 12:int) (==:bool name(tick) 4:int))))
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

```hir
;; hir -O2 fn=checkNonReassoc
fn checkNonReassoc (x:int, y:int, s:obj, t:obj) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (call var($cse0) (and (and (==:bool (+:int (+:int var(x#1) var(y)) 2:int) 7:int) (==:bool (*:int (*:int var(x#1) var(y)) 3:int) 18:int)) (==:bool (+:obj (+:obj var(s) var(t)) "!") "ab!")))
    (call var($cse0) (==:bool (block
          (def x#2
            (block
              (def r (if (<:bool var(x#1) 0:int) 0:int var(x#1)))
              var(r)))
          (if (<:bool var(x#2) 0:int) 0:int var(x#2))) 2:int)))
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

```hir
;; hir -O2 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (block
    (def $cse0 name(bump))
    (def r (+:int (+:int (+:int (+:int (call-fn bump) (call-fn bump)) (call-fn bump)) (call-fn bump)) (call-fn bump)))
    (call name(assert) (and (==:bool name(r) 15:int) (==:bool name(tick) 5:int))))
```
