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

```mir
;; mir -O2 fn=checkBool stage=before
fn checkBool(v0: bool) -> bool:
  bb0:
    v1: any = opaque.NameRead(assert) {read:shared,throws}
    v2: bool = const true
    v3: bool = opaque.DynamicCall(v1, v2) {impure}
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: bool = const true
    v6: bool = opaque.DynamicCall(v4, v5) {impure}
    v7: any = opaque.NameRead(assert) {read:shared,throws}
    v8: bool = const true
    v9: bool = opaque.DynamicCall(v7, v8) {impure}
    v10: any = opaque.NameRead(assert) {read:shared,throws}
    v11: bool = const true
    v12: bool = opaque.DynamicCall(v10, v11) {impure}
    v13: any = opaque.NameRead(assert) {read:shared,throws}
    v14: bool = const true
    v15: bool = opaque.DynamicCall(v13, v14) {impure}
    ret v15
```

```mir
;; mir -O2 fn=checkBool
fn checkBool(v0: bool) -> bool:
  bb0:
    v1: any = opaque.NameRead(assert) {read:shared,throws}
    v2: bool = const true
    v3: bool = opaque.DynamicCall(v1, v2) {impure}
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: bool = opaque.DynamicCall(v4, v2) {impure}
    v6: any = opaque.NameRead(assert) {read:shared,throws}
    v7: bool = opaque.DynamicCall(v6, v2) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    v9: bool = opaque.DynamicCall(v8, v2) {impure}
    v10: any = opaque.NameRead(assert) {read:shared,throws}
    v11: bool = opaque.DynamicCall(v10, v2) {impure}
    ret v11
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

```mir
;; mir -O2 fn=checkInt stage=before
fn checkInt(v0: int, v1: int, v2: int, v3: int) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = add v0, v0 {pure}
    v6: bool = eq v5, v1 {pure}
    v7: bool = opaque.DynamicCall(v4, v6) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    v9: int = add v0, v0 {pure}
    v10: int = const 8
    v11: int = add v9, v10 {pure}
    v12: bool = eq v11, v2 {pure}
    v13: bool = opaque.DynamicCall(v8, v12) {impure}
    v14: any = opaque.NameRead(assert) {read:shared,throws}
    v15: int = neg v0 {pure}
    v16: int = add v0, v15 {pure}
    v17: int = const 0
    v18: bool = eq v16, v17 {pure}
    v19: bool = opaque.DynamicCall(v14, v18) {impure}
    v20: any = opaque.NameRead(assert) {read:shared,throws}
    v21: int = add v0, v0 {pure}
    v22: int = const 3
    v23: int = mul v0, v22 {pure}
    v24: int = add v21, v23 {pure}
    v25: int = const 6
    v26: int = mul v0, v25 {pure}
    v27: int = add v24, v26 {pure}
    v28: bool = eq v27, v3 {pure}
    v29: bool = opaque.DynamicCall(v20, v28) {impure}
    ret v29
```

```mir
;; mir -O2 fn=checkInt
fn checkInt(v0: int, v1: int, v2: int, v3: int) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = add v0, v0 {pure}
    v6: bool = eq v5, v1 {pure}
    v7: bool = opaque.DynamicCall(v4, v6) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    v9: int = const 8
    v10: int = add v5, v9 {pure}
    v11: bool = eq v10, v2 {pure}
    v12: bool = opaque.DynamicCall(v8, v11) {impure}
    v13: any = opaque.NameRead(assert) {read:shared,throws}
    v14: int = neg v0 {pure}
    v15: int = add v0, v14 {pure}
    v16: int = const 0
    v17: bool = eq v15, v16 {pure}
    v18: bool = opaque.DynamicCall(v13, v17) {impure}
    v19: any = opaque.NameRead(assert) {read:shared,throws}
    v20: int = const 3
    v21: int = mul v0, v20 {pure}
    v22: int = add v5, v21 {pure}
    v23: int = const 6
    v24: int = mul v0, v23 {pure}
    v25: int = add v22, v24 {pure}
    v26: bool = eq v25, v3 {pure}
    v27: bool = opaque.DynamicCall(v19, v26) {impure}
    ret v27
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

```mir
;; mir -O2 fn=checkLong stage=before
fn checkLong(v0: long, v1: long, v2: long, v3: long) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: long = add v0, v0 {pure}
    v6: bool = eq v5, v1 {pure}
    v7: bool = opaque.DynamicCall(v4, v6) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    v9: long = add v0, v0 {pure}
    v10: long = const 8L
    v11: long = add v9, v10 {pure}
    v12: bool = eq v11, v2 {pure}
    v13: bool = opaque.DynamicCall(v8, v12) {impure}
    v14: any = opaque.NameRead(assert) {read:shared,throws}
    v15: long = neg v0 {pure}
    v16: long = add v0, v15 {pure}
    v17: long = const 0L
    v18: bool = eq v16, v17 {pure}
    v19: bool = opaque.DynamicCall(v14, v18) {impure}
    v20: any = opaque.NameRead(assert) {read:shared,throws}
    v21: long = add v0, v0 {pure}
    v22: long = const 3L
    v23: long = mul v0, v22 {pure}
    v24: long = add v21, v23 {pure}
    v25: long = const 6L
    v26: long = mul v0, v25 {pure}
    v27: long = add v24, v26 {pure}
    v28: bool = eq v27, v3 {pure}
    v29: bool = opaque.DynamicCall(v20, v28) {impure}
    ret v29
```

```mir
;; mir -O2 fn=checkLong
fn checkLong(v0: long, v1: long, v2: long, v3: long) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: long = add v0, v0 {pure}
    v6: bool = eq v5, v1 {pure}
    v7: bool = opaque.DynamicCall(v4, v6) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    v9: long = const 8L
    v10: long = add v5, v9 {pure}
    v11: bool = eq v10, v2 {pure}
    v12: bool = opaque.DynamicCall(v8, v11) {impure}
    v13: any = opaque.NameRead(assert) {read:shared,throws}
    v14: long = neg v0 {pure}
    v15: long = add v0, v14 {pure}
    v16: long = const 0L
    v17: bool = eq v15, v16 {pure}
    v18: bool = opaque.DynamicCall(v13, v17) {impure}
    v19: any = opaque.NameRead(assert) {read:shared,throws}
    v20: long = const 3L
    v21: long = mul v0, v20 {pure}
    v22: long = add v5, v21 {pure}
    v23: long = const 6L
    v24: long = mul v0, v23 {pure}
    v25: long = add v22, v24 {pure}
    v26: bool = eq v25, v3 {pure}
    v27: bool = opaque.DynamicCall(v19, v26) {impure}
    ret v27
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

```mir
;; mir -O2 fn=checkStrIeee stage=before
fn checkStrIeee(v0: any, v1: double, v2: any, v3: bool) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: any = const "ab"
    v6: any = strcat v0, v5 {pure}
    v7: any = eq v6, v2 {pure}
    v8: bool = opaque.DynamicCall(v4, v7) {impure}
    v9: any = opaque.NameRead(assert) {read:shared,throws}
    v10: double = const 0.0
    v11: double = mul v1, v10 {pure}
    v12: double = const 0.0
    v13: double = add v11, v12 {pure}
    v14: bool = call_java Double.isNaN(v13) {pure}
    v15: bool = eq v14, v3 {pure}
    v16: bool = opaque.DynamicCall(v9, v15) {impure}
    br_if v3, bb2, bb1
  bb1:
    v17: any = opaque.NameRead(assert) {read:shared,throws}
    v18: double = const 1.0
    v19: double = add v1, v18 {pure}
    v20: double = const 2.0
    v21: double = add v19, v20 {pure}
    v22: double = const 3.0
    v23: bool = eq v21, v22 {pure}
    v24: bool = opaque.DynamicCall(v17, v23) {impure}
    br bb3(v24)
  bb2:
    v25: any = opaque.NameRead(assert) {read:shared,throws}
    v26: double = const 1.0
    v27: double = add v1, v26 {pure}
    v28: double = const 2.0
    v29: double = add v27, v28 {pure}
    v30: bool = call_java Double.isNaN(v29) {pure}
    v31: bool = opaque.DynamicCall(v25, v30) {impure}
    br bb3(v31)
  bb3(v32: bool):
    ret v32
```

```mir
;; mir -O2 fn=checkStrIeee
fn checkStrIeee(v0: any, v1: double, v2: any, v3: bool) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: any = const "ab"
    v6: any = strcat v0, v5 {pure}
    v7: any = eq v6, v2 {pure}
    v8: bool = opaque.DynamicCall(v4, v7) {impure}
    v9: any = opaque.NameRead(assert) {read:shared,throws}
    v10: double = const 0.0
    v11: double = mul v1, v10 {pure}
    v12: double = add v11, v10 {pure}
    v13: bool = call_java Double.isNaN(v12) {pure}
    v14: bool = eq v13, v3 {pure}
    v15: bool = opaque.DynamicCall(v9, v14) {impure}
    br_if v3, bb2, bb1
  bb1:
    v16: any = opaque.NameRead(assert) {read:shared,throws}
    v17: double = const 1.0
    v18: double = add v1, v17 {pure}
    v19: double = const 2.0
    v20: double = add v18, v19 {pure}
    v21: double = const 3.0
    v22: bool = eq v20, v21 {pure}
    v23: bool = opaque.DynamicCall(v16, v22) {impure}
    br bb3(v23)
  bb2:
    v24: any = opaque.NameRead(assert) {read:shared,throws}
    v25: double = const 1.0
    v26: double = add v1, v25 {pure}
    v27: double = const 2.0
    v28: double = add v26, v27 {pure}
    v29: bool = call_java Double.isNaN(v28) {pure}
    v30: bool = opaque.DynamicCall(v24, v29) {impure}
    br bb3(v30)
  bb3(v31: bool):
    ret v31
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

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
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

```mir
;; mir -O2 fn=checkNonReassoc stage=before
fn checkNonReassoc(v0: int, v1: int, v2: any, v3: any) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = add v0, v1 {pure}
    v6: int = const 2
    v7: int = add v5, v6 {pure}
    v8: int = const 7
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb1, bb2(v10)
  bb1:
    v11: int = mul v0, v1 {pure}
    v12: int = const 3
    v13: int = mul v11, v12 {pure}
    v14: int = const 18
    v15: bool = eq v13, v14 {pure}
    br bb2(v15)
  bb2(v16: bool):
    v17: bool = const false
    br_if v16, bb3, bb4(v17)
  bb3:
    v18: any = strcat v2, v3 {pure}
    v19: any = const "!"
    v20: any = strcat v18, v19 {pure}
    v21: any = const "ab!"
    v22: any = eq v20, v21 {pure}
    v23: bool = not v22 {pure}
    v24: bool = not v23 {pure}
    br bb4(v24)
  bb4(v25: bool):
    v26: bool = opaque.DynamicCall(v4, v25) {impure}
    v27: any = opaque.NameRead(assert) {read:shared,throws}
    v28: int = const 0
    v29: bool = lt v0, v28 {pure}
    br_if v29, bb6, bb5
  bb5:
    br bb7(v0)
  bb6:
    v30: int = const 0
    br bb7(v30)
  bb7(v31: int):
    v32: int = const 0
    v33: bool = lt v31, v32 {pure}
    br_if v33, bb9, bb8
  bb8:
    br bb10(v31)
  bb9:
    v34: int = const 0
    br bb10(v34)
  bb10(v35: int):
    v36: int = const 2
    v37: bool = eq v35, v36 {pure}
    v38: bool = opaque.DynamicCall(v27, v37) {impure}
    ret v38
```

```mir
;; mir -O2 fn=checkNonReassoc
fn checkNonReassoc(v0: int, v1: int, v2: any, v3: any) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = add v0, v1 {pure}
    v6: int = const 2
    v7: int = add v5, v6 {pure}
    v8: int = const 7
    v9: bool = eq v7, v8 {pure}
    v10: bool = const false
    br_if v9, bb1, bb2(v10)
  bb1:
    v11: int = mul v0, v1 {pure}
    v12: int = const 3
    v13: int = mul v11, v12 {pure}
    v14: int = const 18
    v15: bool = eq v13, v14 {pure}
    br bb2(v15)
  bb2(v16: bool):
    br_if v16, bb3, bb4(v10)
  bb3:
    v17: any = strcat v2, v3 {pure}
    v18: any = const "!"
    v19: any = strcat v17, v18 {pure}
    v20: any = const "ab!"
    v21: any = eq v19, v20 {pure}
    v22: bool = not v21 {pure}
    v23: bool = not v22 {pure}
    br bb4(v23)
  bb4(v24: bool):
    v25: bool = opaque.DynamicCall(v4, v24) {impure}
    v26: any = opaque.NameRead(assert) {read:shared,throws}
    v27: int = const 0
    v28: bool = lt v0, v27 {pure}
    br_if v28, bb6, bb5
  bb5:
    br bb7(v0)
  bb6:
    br bb7(v27)
  bb7(v29: int):
    v30: bool = lt v29, v27 {pure}
    br_if v30, bb9, bb8
  bb8:
    br bb10(v29)
  bb9:
    br bb10(v27)
  bb10(v31: int):
    v32: bool = eq v31, v6 {pure}
    v33: bool = opaque.DynamicCall(v26, v32) {impure}
    ret v33
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

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```
