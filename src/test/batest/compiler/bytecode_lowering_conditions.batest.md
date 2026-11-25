# Bytecode Lowering : Constant Folding, Truthiness, and Comparisons

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble
;; babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
```



## Constant folding arithmetic and algebraic identities (x passed as parameter to prevent full -O1 folding)

```babble
def fold = fn (x : int) -> (
    assert(x + 0 == 5 and 0 + x == 5 and x - 0 == 5)
    assert(x * 0 == 0 and 0 * x == 0 and x * 1 == 5 and 1 * x == 5)
    assert((1 + 2) * (3 + 4) == 21 and -(10 - 3) == -7)
)
fold(5)
assert(10 - 3 == 7 and 4 * 5 == 20 and -(3) + 10 == 7 and -(2.5) == -2.5)
```

```hir
;; hir -O0 fn=fold
fn fold (x:int) -> bool {pure unique}
  (call name(assert) (and (and (==:bool (+:int var(x) 0:int) 5:int) (==:bool (+:int 0:int var(x)) 5:int)) (==:bool (-:int var(x) 0:int) 5:int)))
  (call name(assert) (and (and (and (==:bool (*:int var(x) 0:int) 0:int) (==:bool (*:int 0:int var(x)) 0:int)) (==:bool (*:int var(x) 1:int) 5:int)) (==:bool (*:int 1:int var(x)) 5:int)))
  (call name(assert) (and (==:bool (*:int (block (+:int 1:int 2:int)) (block (+:int 3:int 4:int))) 21:int) (==:bool (neg (block (-:int 10:int 3:int))) -7:int)))
```

```hir
;; hir -O1 fn=fold
fn fold (x:int) -> bool {pure unique}
  (call name(assert) (and (and (==:bool var(x) 5:int) (==:bool var(x) 5:int)) (==:bool var(x) 5:int)))
  (call name(assert) (and (==:bool var(x) 5:int) (==:bool var(x) 5:int)))
  (call name(assert) true:bool)
```

```mir
;; mir -O2 fn=fold stage=before
fn fold(v0: int) -> bool:
  bb0:
    v1: any = opaque.NameRead(assert) {read:shared,throws}
    v2: int = const 5
    v3: bool = eq v0, v2 {pure}
    v4: bool = const false
    br_if v3, bb1, bb2(v4)
  bb1:
    v5: int = const 5
    v6: bool = eq v0, v5 {pure}
    br bb2(v6)
  bb2(v7: bool):
    v8: bool = const false
    br_if v7, bb3, bb4(v8)
  bb3:
    v9: int = const 5
    v10: bool = eq v0, v9 {pure}
    br bb4(v10)
  bb4(v11: bool):
    v12: bool = opaque.DynamicCall(v1, v11) {impure}
    v13: any = opaque.NameRead(assert) {read:shared,throws}
    v14: int = const 5
    v15: bool = eq v0, v14 {pure}
    v16: bool = const false
    br_if v15, bb5, bb6(v16)
  bb5:
    v17: int = const 5
    v18: bool = eq v0, v17 {pure}
    br bb6(v18)
  bb6(v19: bool):
    v20: bool = opaque.DynamicCall(v13, v19) {impure}
    v21: any = opaque.NameRead(assert) {read:shared,throws}
    v22: bool = const true
    v23: bool = opaque.DynamicCall(v21, v22) {impure}
    ret v23
```

```mir
;; mir -O2 fn=fold
fn fold(v0: int) -> bool:
  bb0:
    v1: any = opaque.NameRead(assert) {read:shared,throws}
    v2: int = const 5
    v3: bool = eq v0, v2 {pure}
    v4: bool = const false
    br_if v3, bb1, bb2(v4)
  bb1:
    br bb2(v3)
  bb2(v5: bool):
    br_if v5, bb3, bb4(v4)
  bb3:
    br bb4(v3)
  bb4(v6: bool):
    v7: bool = opaque.DynamicCall(v1, v6) {impure}
    v8: any = opaque.NameRead(assert) {read:shared,throws}
    br_if v3, bb5, bb6(v4)
  bb5:
    br bb6(v3)
  bb6(v9: bool):
    v10: bool = opaque.DynamicCall(v8, v9) {impure}
    v11: any = opaque.NameRead(assert) {read:shared,throws}
    v12: bool = const true
    v13: bool = opaque.DynamicCall(v11, v12) {impure}
    ret v13
```

## evalIntConstant nested unary minus including 32-bit Integer.MIN_VALUE negation wrap, plus mixed widening

```babble
def x = 4
def z = (0 - x) + (10 * 2 + 1) + -(-3) + -(-2147483647 - 1) + -(0 - x)
def dm = 1 + 2.0
def dmin : double = -2147483648
assert(z == -2147483624)
assert(dm == 3.0)
assert(dmin == -2147483648)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (def z (+:int (+:int (+:int (+:int (block (-:int 0:int name(x))) (block (+:int (*:int 10:int 2:int) 1:int))) (neg (block -3:int))) (neg (block (-:int -2147483647:int 1:int)))) (neg (block (-:int 0:int name(x))))))
  (def dm (+:double 1:int 2.0:double))
  (def dmin -2147483648:int)
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) -2147483648:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (def z (+:int (+:int (+:int (+:int (neg name(x)) 21:int) 3:int) -2147483648:int) (neg (neg name(x)))))
  (def dm 3.0:double)
  (def dmin -2147483648:int)
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) -2147483648:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Boolean equality, not/or/and conditions, mixed comparisons, and parenthesized condition

```babble
def b1 = true ; def b2 = false ; def x = 8 ; def y = 9
assert((b1 == b2) == false and (b1 != b2) == true)
assert(if not (x < 5) then ( true ) else ( false ))
assert(if (x < 5) or (y < 5) then ( false ) else ( true ))
assert((1 == 1.0) and (2 != 3.0) and (b1 == true) and (b1 != false) and ((1 < 2)))
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (call name(assert) (and (==:bool (block (==:bool name(b1) name(b2))) false:bool) (==:bool (block (<>:bool name(b1) name(b2))) true:bool)))
  (call name(assert) (if (not (block (<:bool name(x) 5:int))) (block true:bool) (block false:bool)))
  (call name(assert) (if (or (block (<:bool name(x) 5:int)) (block (<:bool name(y) 5:int))) (block false:bool) (block true:bool)))
  (call name(assert) (and (and (and (and (block (==:bool 1:int 1.0:double)) (block (<>:bool 2:int 3.0:double))) (block (==:bool name(b1) true:bool))) (block (<>:bool name(b1) false:bool))) (block (block (<:bool 1:int 2:int)))))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (call name(assert) (and (==:bool (==:bool name(b1) name(b2)) false:bool) (==:bool (<>:bool name(b1) name(b2)) true:bool)))
  (call name(assert) (if (>=:bool name(x) 5:int) true:bool false:bool))
  (call name(assert) (if (or (<:bool name(x) 5:int) (<:bool name(y) 5:int)) false:bool true:bool))
  (call name(assert) (and (and (==:bool name(b1) true:bool) (<>:bool name(b1) false:bool)) true:bool))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Numeric truthiness in if conditions (int and double)

```babble
def check = fn (d1 : double, d0 : double, i1 : int, i0 : int) : string -> (
    var out = ""
    out += if d1 then ( "1" ) else ( "0" )
    out += if d0 then ( "1" ) else ( "0" )
    out += if i1 then ( "1" ) else ( "0" )
    out += if i0 then ( "1" ) else ( "0" )
    out
)
assert(check(1.5, 0.0, 2, 0) == "1010")
```

```hir
;; hir -O0 fn=check
fn check (d1:double, d0:double, i1:int, i0:int) -> obj {nothrow unique}
  (def out "")
  (set out += (if var(d1) (block "1") (block "0")))
  (set out += (if var(d0) (block "1") (block "0")))
  (set out += (if var(i1) (block "1") (block "0")))
  (set out += (if var(i0) (block "1") (block "0")))
  var(out)
```

```hir
;; hir -O1 fn=check
fn check (d1:double, d0:double, i1:int, i0:int) -> obj {nothrow unique}
  (def out "")
  (set out += (if var(d1) "1" "0"))
  (set out += (if var(d0) "1" "0"))
  (set out += (if var(i1) "1" "0"))
  (set out += (if var(i0) "1" "0"))
  var(out)
```

```mir
;; mir -O2 fn=check stage=before,final
fn check(v0: double, v1: double, v2: int, v3: int) -> any:
  bb0:
    v4: any = const ""
    br_if v0, bb2, bb1
  bb1:
    v5: any = const "0"
    br bb3(v5)
  bb2:
    v6: any = const "1"
    br bb3(v6)
  bb3(v7: any):
    v8: any = add v4, v7 {impure}
    br_if v1, bb5, bb4
  bb4:
    v9: any = const "0"
    br bb6(v9)
  bb5:
    v10: any = const "1"
    br bb6(v10)
  bb6(v11: any):
    v12: any = add v8, v11 {impure}
    br_if v2, bb8, bb7
  bb7:
    v13: any = const "0"
    br bb9(v13)
  bb8:
    v14: any = const "1"
    br bb9(v14)
  bb9(v15: any):
    v16: any = add v12, v15 {impure}
    br_if v3, bb11, bb10
  bb10:
    v17: any = const "0"
    br bb12(v17)
  bb11:
    v18: any = const "1"
    br bb12(v18)
  bb12(v19: any):
    v20: any = add v16, v19 {impure}
    ret v20
```

## Double comparisons in control flow

```babble
def checkD = fn (d : double) : int -> (
    var hits = 0
    if d < 3.0 then ( hits += 1 )
    if d <= 2.5 then ( hits += 1 )
    if d == 2.5 then ( hits += 1 )
    if d != 1.0 then ( hits += 1 )
    if d >= 2.5 then ( hits += 1 )
    if d > 2.0 then ( hits += 1 )
    hits
)
assert(checkD(2.5) == 6)
```

```hir
;; hir -O0 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (block (set hits += 1:int)))
  (if (<=:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (==:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (<>:bool var(d) 1.0:double) (block (set hits += 1:int)))
  (if (>=:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (>:bool var(d) 2.0:double) (block (set hits += 1:int)))
  var(hits)
```

```hir
;; hir -O1 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (set hits += 1:int))
  (if (<=:bool var(d) 2.5:double) (set hits += 1:int))
  (if (==:bool var(d) 2.5:double) (set hits += 1:int))
  (if (<>:bool var(d) 1.0:double) (set hits += 1:int))
  (if (>=:bool var(d) 2.5:double) (set hits += 1:int))
  (if (>:bool var(d) 2.0:double) (set hits += 1:int))
  var(hits)
```

```mir
;; mir -O2 fn=checkD stage=before
fn checkD(v0: double) -> int:
  bb0:
    v1: int = const 0
    v2: double = const 3.0
    v3: bool = lt v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: any = const null
    br bb3(v4, v1)
  bb2:
    v5: int = const 1
    v6: int = add v1, v5 {pure}
    v7: any = convert.any v6
    br bb3(v7, v6)
  bb3(v8: any, v9: int):
    v10: double = const 2.5
    v11: bool = lte v0, v10 {pure}
    br_if v11, bb5, bb4
  bb4:
    v12: any = const null
    br bb6(v12, v9)
  bb5:
    v13: int = const 1
    v14: int = add v9, v13 {pure}
    v15: any = convert.any v14
    br bb6(v15, v14)
  bb6(v16: any, v17: int):
    v18: double = const 2.5
    v19: bool = eq v0, v18 {pure}
    br_if v19, bb8, bb7
  bb7:
    v20: any = const null
    br bb9(v20, v17)
  bb8:
    v21: int = const 1
    v22: int = add v17, v21 {pure}
    v23: any = convert.any v22
    br bb9(v23, v22)
  bb9(v24: any, v25: int):
    v26: double = const 1.0
    v27: bool = neq v0, v26 {pure}
    br_if v27, bb11, bb10
  bb10:
    v28: any = const null
    br bb12(v28, v25)
  bb11:
    v29: int = const 1
    v30: int = add v25, v29 {pure}
    v31: any = convert.any v30
    br bb12(v31, v30)
  bb12(v32: any, v33: int):
    v34: double = const 2.5
    v35: bool = gte v0, v34 {pure}
    br_if v35, bb14, bb13
  bb13:
    v36: any = const null
    br bb15(v36, v33)
  bb14:
    v37: int = const 1
    v38: int = add v33, v37 {pure}
    v39: any = convert.any v38
    br bb15(v39, v38)
  bb15(v40: any, v41: int):
    v42: double = const 2.0
    v43: bool = gt v0, v42 {pure}
    br_if v43, bb17, bb16
  bb16:
    v44: any = const null
    br bb18(v44, v41)
  bb17:
    v45: int = const 1
    v46: int = add v41, v45 {pure}
    v47: any = convert.any v46
    br bb18(v47, v46)
  bb18(v48: any, v49: int):
    ret v49
```

```mir
;; mir -O2 fn=checkD
fn checkD(v0: double) -> int:
  bb0:
    v1: int = const 0
    v2: double = const 3.0
    v3: bool = lt v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    br bb3(v1)
  bb2:
    v4: int = const 1
    br bb3(v4)
  bb3(v5: int):
    v6: double = const 2.5
    v7: bool = lte v0, v6 {pure}
    br_if v7, bb5, bb4
  bb4:
    br bb6(v5)
  bb5:
    v8: int = const 1
    v9: int = add v5, v8 {pure}
    br bb6(v9)
  bb6(v10: int):
    v11: bool = eq v0, v6 {pure}
    br_if v11, bb8, bb7
  bb7:
    br bb9(v10)
  bb8:
    v12: int = const 1
    v13: int = add v10, v12 {pure}
    br bb9(v13)
  bb9(v14: int):
    v15: double = const 1.0
    v16: bool = neq v0, v15 {pure}
    br_if v16, bb11, bb10
  bb10:
    br bb12(v14)
  bb11:
    v17: int = const 1
    v18: int = add v14, v17 {pure}
    br bb12(v18)
  bb12(v19: int):
    v20: bool = gte v0, v6 {pure}
    br_if v20, bb14, bb13
  bb13:
    br bb15(v19)
  bb14:
    v21: int = const 1
    v22: int = add v19, v21 {pure}
    br bb15(v22)
  bb15(v23: int):
    v24: double = const 2.0
    v25: bool = gt v0, v24 {pure}
    br_if v25, bb17, bb16
  bb16:
    br bb18(v23)
  bb17:
    v26: int = const 1
    v27: int = add v23, v26 {pure}
    br bb18(v27)
  bb18(v28: int):
    ret v28
```

## Int condition lowering (==, !=, and, or) across true and false branches

```babble
def checkI = fn (a : int, b : int) : string -> (
    var o = ""
    o += if a == b then ( "1" ) else ( "0" )
    o += if a != b then ( "1" ) else ( "0" )
    o += if a > 0 and b > 0 then ( "1" ) else ( "0" )
    o += if a < 0 or b < 0 then ( "1" ) else ( "0" )
    o
)
assert(checkI(2, 2) == "1010" and checkI(2, 3) == "0110")
```

```hir
;; hir -O0 fn=checkI
fn checkI (a:int, b:int) -> obj {nothrow commutative unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (and (>:bool var(a) 0:int) (>:bool var(b) 0:int)) (block "1") (block "0")))
  (set o += (if (or (<:bool var(a) 0:int) (<:bool var(b) 0:int)) (block "1") (block "0")))
  var(o)
```

```hir
;; hir -O1 fn=checkI
fn checkI (a:int, b:int) -> obj {nothrow commutative unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) "1" "0"))
  (set o += (if (<>:bool var(a) var(b)) "1" "0"))
  (set o += (if (and (>:bool var(a) 0:int) (>:bool var(b) 0:int)) "1" "0"))
  (set o += (if (or (<:bool var(a) 0:int) (<:bool var(b) 0:int)) "1" "0"))
  var(o)
```

```mir
;; mir -O2 fn=checkI stage=before
fn checkI(v0: int, v1: int) -> any:
  bb0:
    v2: any = const ""
    v3: bool = eq v0, v1 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: any = const "0"
    br bb3(v4)
  bb2:
    v5: any = const "1"
    br bb3(v5)
  bb3(v6: any):
    v7: any = add v2, v6 {impure}
    v8: bool = neq v0, v1 {pure}
    br_if v8, bb5, bb4
  bb4:
    v9: any = const "0"
    br bb6(v9)
  bb5:
    v10: any = const "1"
    br bb6(v10)
  bb6(v11: any):
    v12: any = add v7, v11 {impure}
    v13: int = const 0
    v14: bool = gt v0, v13 {pure}
    v15: bool = const false
    br_if v14, bb7, bb8(v15)
  bb7:
    v16: int = const 0
    v17: bool = gt v1, v16 {pure}
    br bb8(v17)
  bb8(v18: bool):
    br_if v18, bb10, bb9
  bb9:
    v19: any = const "0"
    br bb11(v19)
  bb10:
    v20: any = const "1"
    br bb11(v20)
  bb11(v21: any):
    v22: any = add v12, v21 {impure}
    v23: int = const 0
    v24: bool = lt v0, v23 {pure}
    v25: bool = const true
    br_if v24, bb13(v25), bb12
  bb12:
    v26: int = const 0
    v27: bool = lt v1, v26 {pure}
    br bb13(v27)
  bb13(v28: bool):
    br_if v28, bb15, bb14
  bb14:
    v29: any = const "0"
    br bb16(v29)
  bb15:
    v30: any = const "1"
    br bb16(v30)
  bb16(v31: any):
    v32: any = add v22, v31 {impure}
    ret v32
```

```mir
;; mir -O2 fn=checkI
fn checkI(v0: int, v1: int) -> any:
  bb0:
    v2: any = const ""
    v3: bool = eq v0, v1 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: any = const "0"
    br bb3(v4)
  bb2:
    v5: any = const "1"
    br bb3(v5)
  bb3(v6: any):
    v7: any = add v2, v6 {impure}
    v8: bool = neq v0, v1 {pure}
    br_if v8, bb5, bb4
  bb4:
    v9: any = const "0"
    br bb6(v9)
  bb5:
    v10: any = const "1"
    br bb6(v10)
  bb6(v11: any):
    v12: any = add v7, v11 {impure}
    v13: int = const 0
    v14: bool = gt v0, v13 {pure}
    v15: bool = const false
    br_if v14, bb7, bb8(v15)
  bb7:
    v16: bool = gt v1, v13 {pure}
    br bb8(v16)
  bb8(v17: bool):
    br_if v17, bb10, bb9
  bb9:
    v18: any = const "0"
    br bb11(v18)
  bb10:
    v19: any = const "1"
    br bb11(v19)
  bb11(v20: any):
    v21: any = add v12, v20 {impure}
    v22: bool = lt v0, v13 {pure}
    v23: bool = const true
    br_if v22, bb13(v23), bb12
  bb12:
    v24: bool = lt v1, v13 {pure}
    br bb13(v24)
  bb13(v25: bool):
    br_if v25, bb15, bb14
  bb14:
    v26: any = const "0"
    br bb16(v26)
  bb15:
    v27: any = const "1"
    br bb16(v27)
  bb16(v28: any):
    v29: any = add v21, v28 {impure}
    ret v29
```

## Double condition lowering (==, !=, <, <=, >, >=) across true and false branches

```babble
def chk = fn (a: double, b: double): string -> (
    var o = ""
    o += if a == b then ("1") else ("0")
    o += if a != b then ("1") else ("0")
    o += if a < b then ("1") else ("0")
    o += if a <= b then ("1") else ("0")
    o += if a > b then ("1") else ("0")
    o += if a >= b then ("1") else ("0")
    o
)
assert(chk(1.0, 2.0) == "011100" and chk(2.0, 1.0) == "010011")
```

```hir
;; hir -O0 fn=chk
fn chk (a:double, b:double) -> obj {nothrow unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<=:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (>=:bool var(a) var(b)) (block "1") (block "0")))
  var(o)
```

```hir
;; hir -O1 fn=chk
fn chk (a:double, b:double) -> obj {nothrow unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) "1" "0"))
  (set o += (if (<>:bool var(a) var(b)) "1" "0"))
  (set o += (if (<:bool var(a) var(b)) "1" "0"))
  (set o += (if (<=:bool var(a) var(b)) "1" "0"))
  (set o += (if (>:bool var(a) var(b)) "1" "0"))
  (set o += (if (>=:bool var(a) var(b)) "1" "0"))
  var(o)
```

```mir
;; mir -O2 fn=chk stage=before,final
fn chk(v0: double, v1: double) -> any:
  bb0:
    v2: any = const ""
    v3: bool = eq v0, v1 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: any = const "0"
    br bb3(v4)
  bb2:
    v5: any = const "1"
    br bb3(v5)
  bb3(v6: any):
    v7: any = add v2, v6 {impure}
    v8: bool = neq v0, v1 {pure}
    br_if v8, bb5, bb4
  bb4:
    v9: any = const "0"
    br bb6(v9)
  bb5:
    v10: any = const "1"
    br bb6(v10)
  bb6(v11: any):
    v12: any = add v7, v11 {impure}
    v13: bool = lt v0, v1 {pure}
    br_if v13, bb8, bb7
  bb7:
    v14: any = const "0"
    br bb9(v14)
  bb8:
    v15: any = const "1"
    br bb9(v15)
  bb9(v16: any):
    v17: any = add v12, v16 {impure}
    v18: bool = lte v0, v1 {pure}
    br_if v18, bb11, bb10
  bb10:
    v19: any = const "0"
    br bb12(v19)
  bb11:
    v20: any = const "1"
    br bb12(v20)
  bb12(v21: any):
    v22: any = add v17, v21 {impure}
    v23: bool = gt v0, v1 {pure}
    br_if v23, bb14, bb13
  bb13:
    v24: any = const "0"
    br bb15(v24)
  bb14:
    v25: any = const "1"
    br bb15(v25)
  bb15(v26: any):
    v27: any = add v22, v26 {impure}
    v28: bool = gte v0, v1 {pure}
    br_if v28, bb17, bb16
  bb16:
    v29: any = const "0"
    br bb18(v29)
  bb17:
    v30: any = const "1"
    br bb18(v30)
  bb18(v31: any):
    v32: any = add v27, v31 {impure}
    ret v32
```
