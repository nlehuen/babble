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

```mir
;; mir -O2 fn=checkIntIdem stage=before
fn checkIntIdem(v0: int, v1: int, v2: int) -> bool:
  bb0:
    v3: any = opaque.NameRead(bigClamp) {read:shared}
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = const 0
    v6: bool = lt v0, v5 {pure}
    br_if v6, bb2, bb1
  bb1:
    br bb3(v0)
  bb2:
    v7: int = const 0
    br bb3(v7)
  bb3(v8: int):
    v9: bool = eq v8, v1 {pure}
    v10: bool = const false
    br_if v9, bb4, bb8(v10)
  bb4:
    v11: int = const 0
    v12: bool = lt v0, v11 {pure}
    br_if v12, bb6, bb5
  bb5:
    br bb7(v0)
  bb6:
    v13: int = const 0
    br bb7(v13)
  bb7(v14: int):
    v15: bool = eq v14, v1 {pure}
    br bb8(v15)
  bb8(v16: bool):
    v17: bool = const false
    br_if v16, bb9, bb10(v17)
  bb9:
    v18: int = opaque.DynamicCall(v3, v0) {impure}
    v19: int = opaque.DynamicCall(v3, v18) {impure}
    v20: bool = eq v19, v1 {pure}
    br bb10(v20)
  bb10(v21: bool):
    v22: bool = opaque.DynamicCall(v4, v21) {impure}
    v23: any = opaque.NameRead(assert) {read:shared,throws}
    v24: int = call_java Math.abs(v0) {pure}
    v25: bool = eq v24, v2 {pure}
    v26: bool = opaque.DynamicCall(v23, v25) {impure}
    ret v26
```

```mir
;; mir -O2 fn=checkIntIdem
fn checkIntIdem(v0: int, v1: int, v2: int) -> bool:
  bb0:
    v3: any = opaque.NameRead(bigClamp) {read:shared}
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: int = const 0
    v6: bool = lt v0, v5 {pure}
    br_if v6, bb2, bb1
  bb1:
    br bb3(v0)
  bb2:
    br bb3(v5)
  bb3(v7: int):
    v8: bool = eq v7, v1 {pure}
    v9: bool = const false
    br_if v8, bb4, bb8(v9)
  bb4:
    br_if v6, bb6, bb5
  bb5:
    br bb7(v0)
  bb6:
    br bb7(v5)
  bb7(v10: int):
    v11: bool = eq v10, v1 {pure}
    br bb8(v11)
  bb8(v12: bool):
    br_if v12, bb9, bb10(v9)
  bb9:
    v13: int = opaque.DynamicCall(v3, v0) {impure}
    v14: int = opaque.DynamicCall(v3, v13) {impure}
    v15: bool = eq v14, v1 {pure}
    br bb10(v15)
  bb10(v16: bool):
    v17: bool = opaque.DynamicCall(v4, v16) {impure}
    v18: any = opaque.NameRead(assert) {read:shared,throws}
    v19: int = call_java Math.abs(v0) {pure}
    v20: bool = eq v19, v2 {pure}
    v21: bool = opaque.DynamicCall(v18, v20) {impure}
    ret v21
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

```mir
;; mir -O2 fn=checkFloor stage=before,final
fn checkFloor(v0: double) -> bool:
  bb0:
    v1: double = call_java Math.floor(v0) {pure}
    v2: any = opaque.NameRead(assert) {read:shared,throws}
    v3: bool = call_java Double.isNaN(v0) {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: long = call_java Double.doubleToRawLongBits(v1) {pure}
    v5: long = call_java Double.doubleToRawLongBits(v0) {pure}
    v6: bool = eq v4, v5 {pure}
    br bb3(v6)
  bb2:
    v7: bool = call_java Double.isNaN(v1) {pure}
    br bb3(v7)
  bb3(v8: bool):
    v9: bool = opaque.DynamicCall(v2, v8) {impure}
    ret v9
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

```mir
;; mir -O2 fn=checkStrIdem stage=before,final
fn checkStrIdem(v0: any, v1: char, v2: any, v3: char) -> bool:
  bb0:
    v4: any = opaque.NameRead(assert) {read:shared,throws}
    v5: any = call_java v0.trim() {impure}
    v6: any = checkcast v5 <string|string|>
    v7: any = eq v6, v2 {pure}
    v8: bool = opaque.DynamicCall(v4, v7) {impure}
    v9: any = opaque.NameRead(assert) {read:shared,throws}
    v10: any = opaque.NameRead(java) {read:shared,throws}
    v11: any = opaque.DynamicSelect(v10.lang) {impure}
    v12: any = opaque.DynamicSelect(v11.Character) {impure}
    v13: any = opaque.DynamicSelect(v12.toLowerCase) {impure}
    v14: any = opaque.NameRead(java) {read:shared,throws}
    v15: any = opaque.DynamicSelect(v14.lang) {impure}
    v16: any = opaque.DynamicSelect(v15.Character) {impure}
    v17: any = opaque.DynamicSelect(v16.toLowerCase) {impure}
    v18: any = opaque.DynamicCall(v17, v1) {impure}
    v19: any = opaque.DynamicCall(v13, v18) {impure}
    v20: char = convert.char v19
    v21: bool = eq v20, v3 {pure}
    v22: bool = opaque.DynamicCall(v9, v21) {impure}
    ret v22
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

```mir
;; mir -O2 fn=checkGuard stage=before
fn checkGuard(v0: int) -> bool:
  bb0:
    v1: int = call_java Math.abs(v0) {pure}
    v2: double = convert.double v1
    v3: any = opaque.NameRead(assert) {read:shared,throws}
    v4: double = const 5.0
    v5: bool = eq v2, v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb8(v6)
  bb1:
    v7: any = opaque.NameRead(lateClamp) {read:shared,throws}
    v8: any = opaque.NameRead(lateClamp) {read:shared,throws}
    v9: int = const 0
    v10: bool = lt v0, v9 {pure}
    br_if v10, bb3, bb2
  bb2:
    br bb4(v0)
  bb3:
    v11: int = const 0
    br bb4(v11)
  bb4(v12: int):
    v13: int = const 0
    v14: bool = lt v12, v13 {pure}
    br_if v14, bb6, bb5
  bb5:
    br bb7(v12)
  bb6:
    v15: int = const 0
    br bb7(v15)
  bb7(v16: int):
    v17: int = const 0
    v18: bool = eq v16, v17 {pure}
    br bb8(v18)
  bb8(v19: bool):
    v20: bool = opaque.DynamicCall(v3, v19) {impure}
    ret v20
```

```mir
;; mir -O2 fn=checkGuard
fn checkGuard(v0: int) -> bool:
  bb0:
    v1: int = call_java Math.abs(v0) {pure}
    v2: double = convert.double v1
    v3: any = opaque.NameRead(assert) {read:shared,throws}
    v4: double = const 5.0
    v5: bool = eq v2, v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb8(v6)
  bb1:
    v7: any = opaque.NameRead(lateClamp) {read:shared,throws}
    v8: any = opaque.NameRead(lateClamp) {read:shared,throws}
    v9: int = const 0
    v10: bool = lt v0, v9 {pure}
    br_if v10, bb3, bb2
  bb2:
    br bb4(v0)
  bb3:
    br bb4(v9)
  bb4(v11: int):
    v12: bool = lt v11, v9 {pure}
    br_if v12, bb6, bb5
  bb5:
    br bb7(v11)
  bb6:
    br bb7(v9)
  bb7(v13: int):
    v14: bool = eq v13, v9 {pure}
    br bb8(v14)
  bb8(v15: bool):
    v16: bool = opaque.DynamicCall(v3, v15) {impure}
    ret v16
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

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
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

```mir
;; mir -O2 fn=checkDet stage=before
fn checkDet(v0: any, v1: any, v2: bool) -> int:
  bb0:
    v3: bool = const false
    br_if v2, bb1, bb2(v3)
  bb1:
    v4: bool = const true
    br bb2(v4)
  bb2(v5: bool):
    br_if v5, bb4, bb3
  bb3:
    v6: any = opaque.NameRead(idT) {read:shared}
    v7: int = const 1
    v8: int = opaque.DynamicCall(v6, v7) {impure}
    br bb5(v8)
  bb4:
    v9: int = array_len v0 {read:frozen,throws}
    v10: int = call_java v1.length() {pure,throws}
    v11: int = add v9, v10 {pure}
    br bb5(v11)
  bb5(v12: int):
    v13: int = const 0
    v14: bool = lt v12, v13 {pure}
    br_if v14, bb7, bb6
  bb6:
    br bb8(v12)
  bb7:
    v15: int = const 0
    br bb8(v15)
  bb8(v16: int):
    v17: any = opaque.NameRead(int) {read:shared,throws}
    v18: int = array_len v0 {read:frozen,throws}
    v19: any = convert.any v18
    v20: any = checkcast v19 <double|double|>
    v21: int = opaque.DynamicCall(v17, v20) {impure}
    v22: int = call_java Math.abs(v21) {pure}
    v23: int = add v16, v22 {pure}
    ret v23
```

```mir
;; mir -O2 fn=checkDet
fn checkDet(v0: any, v1: any, v2: bool) -> int:
  bb0:
    v3: bool = const false
    br_if v2, bb1, bb2(v3)
  bb1:
    v4: bool = const true
    br bb2(v4)
  bb2(v5: bool):
    br_if v5, bb4, bb3
  bb3:
    v6: any = opaque.NameRead(idT) {read:shared}
    v7: int = const 1
    v8: int = opaque.DynamicCall(v6, v7) {impure}
    br bb5(v8)
  bb4:
    v9: int = array_len v0 {read:frozen,throws}
    v10: int = call_java v1.length() {pure,throws}
    v11: int = add v9, v10 {pure}
    br bb5(v11)
  bb5(v12: int):
    v13: int = const 0
    v14: bool = lt v12, v13 {pure}
    br_if v14, bb7, bb6
  bb6:
    br bb8(v12)
  bb7:
    br bb8(v13)
  bb8(v15: int):
    v16: any = opaque.NameRead(int) {read:shared,throws}
    v17: int = array_len v0 {read:frozen,throws}
    v18: any = convert.any v17
    v19: any = checkcast v18 <double|double|>
    v20: int = opaque.DynamicCall(v16, v19) {impure}
    v21: int = call_java Math.abs(v20) {pure}
    v22: int = add v15, v21 {pure}
    ret v22
```
