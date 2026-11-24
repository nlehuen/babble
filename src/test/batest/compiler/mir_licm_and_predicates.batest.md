# SSA MIR LICM and Predicate Reordering (BIP014-P3)

Tests `LicmPass` (natural-loop preheader hoisting of pure, frozen, and un-clobbered
reads, plus structural `Narrow` safety) and `PredicateReorderPass` (short-circuit pure
predicate reordering and negative narrowing-safety tests).

## LICM hoists loop-invariant frozen record read and pure arithmetic out of while loop

```babble
def Scale = record (k: double) ()
def sumScaled = fn (s: Scale, angle: double, n: int): double -> (
    def i = 0
    def acc = 0.0
    while i < n then (
        def factor = s.k * java.lang.Math.cos(angle)
        acc += factor
        i += 1
    )
    acc
)
assert(sumScaled(Scale(3.0), 0.0, 4) == 12.0)
```

```hir
;; hir -O0 fn=sumScaled
fn sumScaled (s:obj, angle:double, n:int) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def factor (*:double (get-this-field k) (jcall Math.cos var(angle))))
      (set acc += var(factor))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumScaled
fn sumScaled (s:obj, angle:double, n:int) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (get-this-field k) (jcall Math.cos var(angle))))
    (while (<:bool var(i) var(n))
      (block
        (def factor var($licm0))
        (set acc += var(factor))
        (set i += 1:int))))
  var(acc)
```

```mir
;; mir -O2 fn=sumScaled stage=before
fn sumScaled(v0: any, v1: double, v2: int) -> double:
  bb0:
    v3: int = const 0
    v4: double = const 0.0
    v5: double = load_field v0.k {read:frozen}
    v6: double = call_java Math.cos(v1) {pure}
    v7: double = mul v5, v6 {pure}
    v8: any = const null
    br bb1(v8, v3, v4)
  bb1(v9: any, v10: int, v11: double):
    v12: bool = lt v10, v2 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v11
  bb3:
    v13: double = add v11, v7 {pure}
    v14: int = const 1
    v15: int = add v10, v14 {pure}
    v16: any = convert.any v15
    br bb1(v16, v15, v13)
```

```mir
;; mir -O2 fn=sumScaled
fn sumScaled(v0: any, v1: double, v2: int) -> double:
  bb0:
    v3: int = const 0
    v4: double = const 0.0
    v5: double = load_field v0.k {read:frozen}
    v6: double = call_java Math.cos(v1) {pure}
    v7: double = mul v5, v6 {pure}
    br bb1(v3, v4)
  bb1(v8: int, v9: double):
    v10: bool = lt v8, v2 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: double = add v9, v7 {pure}
    v12: int = const 1
    v13: int = add v8, v12 {pure}
    br bb1(v13, v11)
```

## MIR LICM hoists loop-invariant while-condition computation out of loop header

```babble
def Limit = record (k: int) ()
def sumToBound = fn (lim: Limit, a: int, b: int): int -> (
    def i = 0
    def acc = 0
    while i < a * b + lim.k then (
        acc += i
        i += 1
    )
    acc
)
assert(sumToBound(Limit(1), 2, 2) == 10)
```

```hir
;; hir -O0 fn=sumToBound
fn sumToBound (lim:obj, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) (+:int (*:int var(a) var(b)) (get-this-field k)))
    (block
      (set acc += var(i))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumToBound
fn sumToBound (lim:obj, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) (+:int (*:int var(a) var(b)) (get-this-field k)))
    (block
      (set acc += var(i))
      (set i += 1:int)))
  var(acc)
```

```mir
;; mir -O2 fn=sumToBound stage=before
fn sumToBound(v0: any, v1: int, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: any = const null
    br bb1(v5, v3, v4)
  bb1(v6: any, v7: int, v8: int):
    v9: int = mul v1, v2 {pure}
    v10: int = load_field v0.k {read:frozen}
    v11: int = add v9, v10 {pure}
    v12: bool = lt v7, v11 {pure}
    br_if v12, bb3, bb2
  bb2:
    ret v8
  bb3:
    v13: int = add v8, v7 {pure}
    v14: int = const 1
    v15: int = add v7, v14 {pure}
    v16: any = convert.any v15
    br bb1(v16, v15, v13)
```

```mir
;; mir -O2 fn=sumToBound stage=after:LicmPass
fn sumToBound(v0: any, v1: int, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = mul v1, v2 {pure}
    v5: int = load_field v0.k {read:frozen}
    v6: int = add v4, v5 {pure}
    br bb1(v3, v3)
  bb1(v7: int, v8: int):
    v9: bool = lt v7, v6 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: int = add v8, v7 {pure}
    v11: int = const 1
    v12: int = add v7, v11 {pure}
    br bb1(v12, v10)
```

```mir
;; mir -O2 fn=sumToBound
fn sumToBound(v0: any, v1: int, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = mul v1, v2 {pure}
    v5: int = load_field v0.k {read:frozen}
    v6: int = add v4, v5 {pure}
    br bb1(v3, v3)
  bb1(v7: int, v8: int):
    v9: bool = lt v7, v6 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: int = add v8, v7 {pure}
    v11: int = const 1
    v12: int = add v7, v11 {pure}
    br bb1(v12, v10)
```

## LICM inserts a preheader when a loop is inside an if branch and never hoists above Narrow

```babble
def guardedLoop = fn (flag: bool, s: string?, n: int): int -> (
    def acc = 0
    if flag then (
        def i = 0
        while i < n then (
            if s <> null then (acc += s.length())
            i += 1
        )
    )
    acc
)
assert(guardedLoop(true, "abcd", 3) == 12 and guardedLoop(true, null, 3) == 0)
```

```hir
;; hir -O0 fn=guardedLoop
fn guardedLoop (flag:bool, s:obj, n:int) -> int {nothrow unique}
  (def acc 0:int)
  (if var(flag)
    (block
      (def i 0:int)
      (while (<:bool var(i) var(n))
        (block
          (if (<>:bool var(s) null) (block (set acc += (jcall String.length var(s)))))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O1 fn=guardedLoop
fn guardedLoop (flag:bool, s:obj, n:int) -> int {nothrow unique}
  (def acc 0:int)
  (if var(flag)
    (block
      (def i 0:int)
      (while (<:bool var(i) var(n))
        (block
          (if (<>:bool var(s) null) (set acc += (jcall String.length var(s))))
          (set i += 1:int)))))
  var(acc)
```

```mir
;; mir -O2 fn=guardedLoop stage=before
fn guardedLoop(v0: bool, v1: any, v2: int) -> int:
  bb0:
    v3: int = const 0
    br_if v0, bb2, bb1
  bb1:
    v4: any = const null
    br bb5(v4, v3)
  bb2:
    v5: int = const 0
    v6: any = const null
    br bb3(v6, v5, v1, v3)
  bb3(v7: any, v8: int, v9: any, v10: int):
    v11: bool = lt v8, v2 {pure}
    br_if v11, bb6, bb4
  bb4:
    br bb5(v7, v10)
  bb5(v12: any, v13: int):
    ret v13
  bb6:
    v14: any = const null
    v15: any = neq v9, v14 {pure,throws}
    br_if v15, bb8, bb7
  bb7:
    v16: any = const null
    br bb9(v16, v9, v10)
  bb8:
    v17: any = narrow.nonnull v9 <non-null>
    v18: int = call_java v17.length() {pure,throws}
    v19: int = add v10, v18 {pure}
    v20: any = convert.any v19
    br bb9(v20, v17, v19)
  bb9(v21: any, v22: any, v23: int):
    v24: int = const 1
    v25: int = add v8, v24 {pure}
    v26: any = convert.any v25
    br bb3(v26, v25, v22, v23)
```

```mir
;; mir -O2 fn=guardedLoop
fn guardedLoop(v0: bool, v1: any, v2: int) -> int:
  bb0:
    v3: int = const 0
    br_if v0, bb2, bb1
  bb1:
    br bb5(v3)
  bb2:
    v4: any = const null
    br bb3(v3, v1, v3)
  bb3(v5: int, v6: any, v7: int):
    v8: bool = lt v5, v2 {pure}
    br_if v8, bb6, bb4
  bb4:
    br bb5(v7)
  bb5(v9: int):
    ret v9
  bb6:
    v10: any = neq v6, v4 {pure,throws}
    br_if v10, bb8, bb7
  bb7:
    br bb9(v6, v7)
  bb8:
    v11: any = narrow.nonnull v6 <non-null>
    v12: int = call_java v11.length() {pure,throws}
    v13: int = add v7, v12 {pure}
    br bb9(v11, v13)
  bb9(v14: any, v15: int):
    v16: int = const 1
    v17: int = add v5, v16 {pure}
    br bb3(v17, v14, v15)
```

## PredicateReorder moves cheap scalar test ahead of expensive pure call

```babble
def expensive = fn nothrow (x: double): bool -> (
    java.lang.Math.sin(x) + java.lang.Math.cos(x) > 0.0
)
def reorderPure = fn (x: double, limit: int): int -> (
    if expensive(x) and (limit > 0) then (1) else (0)
)
assert(reorderPure(0.5, 1) == 1 and reorderPure(0.5, -1) == 0)
```

```hir
;; hir -O0 fn=reorderPure
fn reorderPure (x:double, limit:int) -> int {nothrow}
  (if (and (call-fn expensive var(x)) (block (>:bool var(limit) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=reorderPure
fn reorderPure (x:double, limit:int) -> int {nothrow}
  (if (and (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 0.0:double) (>:bool var(limit) 0:int)) 1:int 0:int)
```

```mir
;; mir -O2 fn=reorderPure stage=before
fn reorderPure(v0: double, v1: int) -> int:
  bb0:
    v2: double = call_java Math.sin(v0) {pure}
    v3: double = call_java Math.cos(v0) {pure}
    v4: double = add v2, v3 {pure}
    v5: double = const 0.0
    v6: bool = gt v4, v5 {pure}
    v7: bool = const false
    br_if v6, bb1, bb2(v7)
  bb1:
    v8: int = const 0
    v9: bool = gt v1, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    v11: int = const 0
    br bb5(v11)
  bb4:
    v12: int = const 1
    br bb5(v12)
  bb5(v13: int):
    ret v13
```

```mir
;; mir -O2 fn=reorderPure stage=after:PredicateReorderPass
fn reorderPure(v0: double, v1: int) -> int:
  bb0:
    v2: bool = const false
    v3: int = const 0
    v4: bool = gt v1, v3 {pure}
    br_if v4, bb1, bb2(v2)
  bb1:
    v5: double = call_java Math.sin(v0) {pure}
    v6: double = call_java Math.cos(v0) {pure}
    v7: double = add v5, v6 {pure}
    v8: double = const 0.0
    v9: bool = gt v7, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v11: int = const 1
    br bb5(v11)
  bb5(v12: int):
    ret v12
```

```mir
;; mir -O2 fn=reorderPure
fn reorderPure(v0: double, v1: int) -> int:
  bb0:
    v2: bool = const false
    v3: int = const 0
    v4: bool = gt v1, v3 {pure}
    br_if v4, bb1, bb2(v2)
  bb1:
    v5: double = call_java Math.sin(v0) {pure}
    v6: double = call_java Math.cos(v0) {pure}
    v7: double = add v5, v6 {pure}
    v8: double = const 0.0
    v9: bool = gt v7, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v11: int = const 1
    br bb5(v11)
  bb5(v12: int):
    ret v12
```

## PredicateReorder NEVER moves a narrowed dereference ahead of its null guard

```babble
def keepGuard = fn (s: string?, limit: int): int -> (
    if (s <> null) and (s.length() > limit) then (1) else (0)
)
assert(keepGuard("hello", 3) == 1 and keepGuard(null, 3) == 0)
```

```hir
;; hir -O0 fn=keepGuard
fn keepGuard (s:obj, limit:int) -> int {nothrow}
  (if (and (block (<>:bool var(s) null)) (block (>:bool (jcall String.length var(s)) var(limit)))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=keepGuard
fn keepGuard (s:obj, limit:int) -> int {nothrow}
  (if (and (<>:bool var(s) null) (>:bool (jcall String.length var(s)) var(limit))) 1:int 0:int)
```

```mir
;; mir -O2 fn=keepGuard stage=before,final
fn keepGuard(v0: any, v1: int) -> int:
  bb0:
    v2: any = const null
    v3: any = neq v0, v2 {pure,throws}
    v4: bool = not v3 {pure}
    v5: bool = not v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb2(v6)
  bb1:
    v7: any = narrow.nonnull v0 <non-null>
    v8: int = call_java v7.length() {pure,throws}
    v9: bool = gt v8, v1 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    v11: int = const 0
    br bb5(v11)
  bb4:
    v12: int = const 1
    br bb5(v12)
  bb5(v13: int):
    ret v13
```

## PredicateReorder moves cheap scalar test ahead of expensive pure call in short-circuit or

```babble
def slowOr = fn nothrow (x: double): bool -> (
    java.lang.Math.sin(x) + java.lang.Math.cos(x) > 1.5
)
def reorderOr = fn (x: double, limit: int): int -> (
    if slowOr(x) or (limit > 0) then (1) else (0)
)
assert(reorderOr(0.5, 1) == 1 and reorderOr(0.5, -1) == 0)
```

```hir
;; hir -O0 fn=reorderOr
fn reorderOr (x:double, limit:int) -> int {nothrow}
  (if (or (call-fn slowOr var(x)) (block (>:bool var(limit) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=reorderOr
fn reorderOr (x:double, limit:int) -> int {nothrow}
  (if (or (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 1.5:double) (>:bool var(limit) 0:int)) 1:int 0:int)
```

```mir
;; mir -O2 fn=reorderOr stage=before
fn reorderOr(v0: double, v1: int) -> int:
  bb0:
    v2: double = call_java Math.sin(v0) {pure}
    v3: double = call_java Math.cos(v0) {pure}
    v4: double = add v2, v3 {pure}
    v5: double = const 1.5
    v6: bool = gt v4, v5 {pure}
    v7: bool = const true
    br_if v6, bb2(v7), bb1
  bb1:
    v8: int = const 0
    v9: bool = gt v1, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    v11: int = const 0
    br bb5(v11)
  bb4:
    v12: int = const 1
    br bb5(v12)
  bb5(v13: int):
    ret v13
```

```mir
;; mir -O2 fn=reorderOr stage=after:PredicateReorderPass
fn reorderOr(v0: double, v1: int) -> int:
  bb0:
    v2: bool = const true
    v3: int = const 0
    v4: bool = gt v1, v3 {pure}
    br_if v4, bb2(v2), bb1
  bb1:
    v5: double = call_java Math.sin(v0) {pure}
    v6: double = call_java Math.cos(v0) {pure}
    v7: double = add v5, v6 {pure}
    v8: double = const 1.5
    v9: bool = gt v7, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v11: int = const 1
    br bb5(v11)
  bb5(v12: int):
    ret v12
```

```mir
;; mir -O2 fn=reorderOr
fn reorderOr(v0: double, v1: int) -> int:
  bb0:
    v2: bool = const true
    v3: int = const 0
    v4: bool = gt v1, v3 {pure}
    br_if v4, bb2(v2), bb1
  bb1:
    v5: double = call_java Math.sin(v0) {pure}
    v6: double = call_java Math.cos(v0) {pure}
    v7: double = add v5, v6 {pure}
    v8: double = const 1.5
    v9: bool = gt v7, v8 {pure}
    br bb2(v9)
  bb2(v10: bool):
    br_if v10, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v11: int = const 1
    br bb5(v11)
  bb5(v12: int):
    ret v12
```

## PredicateReorder ranks record load and array read heavier than scalar compare

```babble
def Pt = record (x: int) ()
def rankCost = fn (p: Pt, lim: int): int -> (
    if (p.x > 0) and (lim > 0) then (1) else (0)
)
assert(rankCost(Pt(2), 3) == 1 and rankCost(Pt(2), -1) == 0)
```

```hir
;; hir -O0 fn=rankCost
fn rankCost (p:obj, lim:int) -> int {nothrow}
  (if (and (block (>:bool (get-this-field x) 0:int)) (block (>:bool var(lim) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=rankCost
fn rankCost (p:obj, lim:int) -> int {nothrow}
  (if (and (>:bool (get-this-field x) 0:int) (>:bool var(lim) 0:int)) 1:int 0:int)
```

```mir
;; mir -O2 fn=rankCost stage=before
fn rankCost(v0: any, v1: int) -> int:
  bb0:
    v2: int = load_field v0.x {read:frozen}
    v3: int = const 0
    v4: bool = gt v2, v3 {pure}
    v5: bool = const false
    br_if v4, bb1, bb2(v5)
  bb1:
    v6: int = const 0
    v7: bool = gt v1, v6 {pure}
    br bb2(v7)
  bb2(v8: bool):
    br_if v8, bb4, bb3
  bb3:
    v9: int = const 0
    br bb5(v9)
  bb4:
    v10: int = const 1
    br bb5(v10)
  bb5(v11: int):
    ret v11
```

```mir
;; mir -O2 fn=rankCost
fn rankCost(v0: any, v1: int) -> int:
  bb0:
    v2: int = load_field v0.x {read:frozen}
    v3: int = const 0
    v4: bool = gt v2, v3 {pure}
    v5: bool = const false
    br_if v4, bb1, bb2(v5)
  bb1:
    v6: bool = gt v1, v3 {pure}
    br bb2(v6)
  bb2(v7: bool):
    br_if v7, bb4, bb3
  bb3:
    br bb5(v3)
  bb4:
    v8: int = const 1
    br bb5(v8)
  bb5(v9: int):
    ret v9
```

## LICM inserts a preheader on a BrIf entry edge to a guarded while loop

```babble
def guardedCond = fn (flag: bool, a: int, b: int): int -> (
    def i = 0
    def acc = 0
    if flag then (
        while i < a * b + 1 then (
            acc += i
            i += 1
        )
    )
    acc
)
assert(guardedCond(true, 2, 2) == 10 and guardedCond(false, 2, 2) == 0)
```

```hir
;; hir -O0 fn=guardedCond
fn guardedCond (flag:bool, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (if var(flag)
    (block
      (while (<:bool var(i) (+:int (*:int var(a) var(b)) 1:int))
        (block
          (set acc += var(i))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O1 fn=guardedCond
fn guardedCond (flag:bool, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (if var(flag)
    (while (<:bool var(i) (+:int (*:int var(a) var(b)) 1:int))
      (block
        (set acc += var(i))
        (set i += 1:int))))
  var(acc)
```

```mir
;; mir -O2 fn=guardedCond stage=before
fn guardedCond(v0: bool, v1: int, v2: int) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    br_if v0, bb2, bb1
  bb1:
    v5: any = const null
    br bb5(v5, v4)
  bb2:
    v6: any = const null
    br bb3(v6, v3, v4)
  bb3(v7: any, v8: int, v9: int):
    v10: int = mul v1, v2 {pure}
    v11: int = const 1
    v12: int = add v10, v11 {pure}
    v13: bool = lt v8, v12 {pure}
    br_if v13, bb6, bb4
  bb4:
    br bb5(v7, v9)
  bb5(v14: any, v15: int):
    ret v15
  bb6:
    v16: int = add v9, v8 {pure}
    v17: int = const 1
    v18: int = add v8, v17 {pure}
    v19: any = convert.any v18
    br bb3(v19, v18, v16)
```

```mir
;; mir -O2 fn=guardedCond
fn guardedCond(v0: bool, v1: int, v2: int) -> int:
  bb0:
    v3: int = const 0
    br_if v0, bb2, bb1
  bb1:
    br bb5(v3)
  bb2:
    v4: int = mul v1, v2 {pure}
    v5: int = const 1
    v6: int = add v4, v5 {pure}
    br bb3(v3, v3)
  bb3(v7: int, v8: int):
    v9: bool = lt v7, v6 {pure}
    br_if v9, bb6, bb4
  bb4:
    br bb5(v8)
  bb5(v10: int):
    ret v10
  bb6:
    v11: int = add v8, v7 {pure}
    v12: int = add v7, v5 {pure}
    br bb3(v12, v11)
```
