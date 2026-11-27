# HIR DCE Variable References and HirRewriter Coverage

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble
;; babble module=dce_mod.ba
export def val = 10
```

```babble
;; babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```


## HirDcePass.collectAllVarRefs : local defs alongside ThisRef, SuperCall, and RecurseRef

```babble
def Base = interface (def calc = fn (x: int): int -> (x + 1))
def Sub = class (def k: int) implements Base (
    def calc = fn (x: int): int -> (def tmp = this.k + 1 ; super.calc(x) + tmp)
)
def recVal = fn (n: int): int -> (
    def step = 1
    if n <= 0 then (0) else (def r = recurse ; r(n - step) + step)
)
assert(Sub(4).calc(5) == 11 and recVal(3) == 3)
```

```hir
;; hir -O0 fn=recVal
fn recVal (n:int) -> int {nothrow}
  (def step 1:int)
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) var(step))) var(step))))
```

```hir
;; hir -O1 fn=recVal
fn recVal (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```mir
;; mir -O2 fn=recVal stage=before
fn recVal(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: any = slot_read $recurse {pure}
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = opaque.DynamicCall(v3, v5) {impure}
    v7: int = const 1
    v8: int = add v6, v7 {pure}
    br bb3(v8)
  bb2:
    v9: int = const 0
    br bb3(v9)
  bb3(v10: int):
    ret v10
```

```mir
;; mir -O2 fn=recVal
fn recVal(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: any = slot_read $recurse {pure}
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = opaque.DynamicCall(v3, v5) {impure}
    v7: int = add v6, v4 {pure}
    br bb3(v7)
  bb2:
    br bb3(v1)
  bb3(v8: int):
    ret v8
```

## HirRewriter : ModuleExportGet, SpreadAdapt, and TypeApply child rewrites

```babble
def m = import("dce_mod.ba")
def idG of T = fn (x: T): T -> (x)
def sumV = fn io (xs: int...): int -> (var s = 0 ; for e in xs then (s += e) ; s)
def run = fn io (arr: array of int): int -> (
    (discard(1) ; m).val + sumV(...(discard(1) ; arr)) + ((discard(1) ; idG) of int)(3)
)
assert(run((array of int).of(1, 2)) == 16)
```

```hir
;; hir -O0 fn=run
fn run (arr:obj) -> int {io}
  (+:int (+:int (export (block
          (call name(discard) 1:int)
          name(m)).val) (call-fn sumV (block
          (call name(discard) 1:int)
          var(arr)))) (call (block (type-apply (block
            (call name(discard) 1:int)
            name(idG)))) 3:int))
```

```hir
;; hir -O1 fn=run
fn run (arr:obj) -> int {io}
  (+:int (+:int (export name(m).val) (call-fn sumV var(arr))) (call (type-apply name(idG)) 3:int))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any) -> int:
  bb0:
    v1: any = name_read m {pure}
    v2: any = export_get v1.val {pure}
    v3: int = convert.int v2
    v4: any = name_read sumV {pure}
    v5: int = call sumV(v0) {impure}
    v6: int = add v3, v5 {pure}
    v7: any = name_read idG {pure}
    v8: int = const 3
    v9: int = opaque.DynamicCall(v7, v8) {impure}
    v10: int = add v6, v9 {pure}
    ret v10
```

## HirRewriter : For iterable and SelfRecurseCall argument rewrites

```babble
def idL = fn (xs: list of int): list of int -> (xs)
def recN = fn (n: int): int -> (if n <= 0 then (0) else (1 + recurse(n - (1 + 0))))
def run = fn (xs: list of int): int -> (
    var b = 0 ; for e in idL(xs) then (b += e) ; b + recN(2)
)
assert(run(list(4, 5)) == 11)
```

```hir
;; hir -O0 fn=run
fn run (xs:obj) -> int {nothrow unique}
  (def b 0:int)
  (for e in (call-fn idL var(xs))
    (block
      (set b += var(e))))
  (+:int var(b) (call-fn recN 2:int))
```

```hir
;; hir -O1 fn=run
fn run (xs:obj) -> int {nothrow unique}
  (def b 0:int)
  (for e in var(xs)
    (block
      (set b += var(e))))
  (+:int var(b) (call-fn recN 2:int))
```

```hir
;; hir -O0 fn=recN
fn recN (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) (block 0:int) (block (+:int 1:int (self-recurse recN (-:int var(n) (block (+:int 1:int 0:int)))))))
```

```hir
;; hir -O1 fn=recN
fn recN (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int 1:int (self-recurse recN (-:int var(n) 1:int))))
```

```mir
;; mir -O2 fn=recN stage=before
fn recN(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: int = const 1
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = call_self recN(v5) {pure!}
    v7: int = add v3, v6 {pure}
    br bb3(v7)
  bb2:
    v8: int = const 0
    br bb3(v8)
  bb3(v9: int):
    ret v9
```

```mir
;; mir -O2 fn=recN
fn recN(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lte v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: int = const 1
    v4: int = sub v0, v3 {pure}
    v5: int = call_self recN(v4) {pure!}
    v6: int = add v3, v5 {pure}
    br bb3(v6)
  bb2:
    br bb3(v1)
  bb3(v7: int):
    ret v7
```

## HirRewriter : SafeCall and MakeOverloadSet child rewrites

```babble
def run = fn (s: string?, n: int): int -> (
    def l1 = (discard(1) ; s)?.length()
    def l2 = s?.substring(0 + 1)?.length()
    def ov = overload((discard(1) ; fn (x: int): int -> (x + 1)), fn (t: string): int -> (t.length()))
    (if l1 != null then (l1) else (0)) + (if l2 != null then (l2) else (0)) + ov(n)
)
assert(run("abcd", 3) == 11 and run(null, 3) == 4)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, n:int) -> int {pure unique}
  (def l1 (safe-call (block
        (call name(discard) 1:int)
        var(s))?.length))
  (def l2 (safe-call (safe-call var(s)?.substring (+:int 0:int 1:int))?.length))
  (def ov (overload (block
        (call name(discard) 1:int)
        (fn-ref <anon#0>)) (fn-ref ov)))
  (+:int (+:int (block (if (<>:bool var(l1) null) (block (narrow.nonnull var(l1))) (block 0:int))) (block (if (<>:bool var(l2) null) (block (narrow.nonnull var(l2))) (block 0:int)))) (call-fn <anon#0> var(n)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, n:int) -> int {pure unique}
  (def l1 (safe-call var(s)?.length))
  (def l2 (safe-call (safe-call var(s)?.substring 1:int)?.length))
  (+:int (+:int (if (<>:bool var(l1) null) (narrow.nonnull var(l1)) 0:int) (if (<>:bool var(l2) null) (narrow.nonnull var(l2)) 0:int)) (+:int var(n) 1:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: int) -> int:
  bb0:
    v2: any = const null
    v3: bool = neq v0, v2 {pure}
    br_if v3, bb1, bb2(v2)
  bb1:
    v4: any = narrow.nonnull v0 <non-null>
    v5: any = opaque.SafeCall(v4?.length) {impure}
    br bb2(v5)
  bb2(v6: any):
    v7: any = const null
    v8: bool = neq v0, v7 {pure}
    br_if v8, bb3, bb4(v7)
  bb3:
    v9: any = narrow.nonnull v0 <non-null>
    v10: int = const 1
    v11: any = opaque.SafeCall(v9?.substring, v10) {impure}
    br bb4(v11)
  bb4(v12: any):
    v13: any = const null
    v14: bool = neq v12, v13 {pure}
    br_if v14, bb5, bb6(v13)
  bb5:
    v15: any = narrow.nonnull v12 <non-null>
    v16: any = opaque.SafeCall(v15?.length) {impure}
    br bb6(v16)
  bb6(v17: any):
    v18: any = const null
    v19: any = neq v6, v18 {pure,throws}
    br_if v19, bb8, bb7
  bb7:
    v20: int = const 0
    br bb9(v20)
  bb8:
    v21: any = narrow.nonnull v6 <non-null>
    v22: int = convert.int v21
    br bb9(v22)
  bb9(v23: int):
    v24: any = const null
    v25: any = neq v17, v24 {pure,throws}
    br_if v25, bb11, bb10
  bb10:
    v26: int = const 0
    br bb12(v26)
  bb11:
    v27: any = narrow.nonnull v17 <non-null>
    v28: int = convert.int v27
    br bb12(v28)
  bb12(v29: int):
    v30: int = add v23, v29 {pure}
    v31: int = const 1
    v32: int = add v1, v31 {pure}
    v33: int = add v30, v32 {pure}
    ret v33
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: int) -> int:
  bb0:
    v2: any = const null
    v3: bool = neq v0, v2 {pure}
    br_if v3, bb1, bb2(v2)
  bb1:
    v4: any = narrow.nonnull v0 <non-null>
    v5: any = opaque.SafeCall(v4?.length) {impure}
    br bb2(v5)
  bb2(v6: any):
    br_if v3, bb3, bb4(v2)
  bb3:
    v7: any = narrow.nonnull v0 <non-null>
    v8: int = const 1
    v9: any = opaque.SafeCall(v7?.substring, v8) {impure}
    br bb4(v9)
  bb4(v10: any):
    v11: bool = neq v10, v2 {pure}
    br_if v11, bb5, bb6(v2)
  bb5:
    v12: any = narrow.nonnull v10 <non-null>
    v13: any = opaque.SafeCall(v12?.length) {impure}
    br bb6(v13)
  bb6(v14: any):
    v15: any = neq v6, v2 {pure,throws}
    br_if v15, bb8, bb7
  bb7:
    v16: int = const 0
    br bb9(v16)
  bb8:
    v17: any = narrow.nonnull v6 <non-null>
    v18: int = convert.int v17
    br bb9(v18)
  bb9(v19: int):
    v20: any = neq v14, v2 {pure,throws}
    br_if v20, bb11, bb10
  bb10:
    v21: int = const 0
    br bb12(v21)
  bb11:
    v22: any = narrow.nonnull v14 <non-null>
    v23: int = convert.int v22
    br bb12(v23)
  bb12(v24: int):
    v25: int = add v19, v24 {pure}
    v26: int = const 1
    v27: int = add v1, v26 {pure}
    v28: int = add v25, v27 {pure}
    ret v28
```

## HirRewriter : RecordNew classRef and Move operand rewrites

```babble
def Pair = record (a: int, b: int) ()
def run = fn (n: int): int -> (
    def p = (discard(1) ; Pair)(n, 2)
    def xs = list(n)
    def m1 = move(xs)
    p.a + p.b + m1.get(0)
)
assert(run(3) == 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def p (record-new Pair var(n) 2:int))
  (def xs (core-new list var(n)))
  (def m1 (move var(xs)))
  (+:int (+:int (get-this-field a) (get-this-field b)) (jcall List.get var(m1) 0:int))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (block
    (def $sr_p_0 var(n))
    (def $sr_p_1 2:int))
  (def xs (core-new list var(n)))
  (def m1 (move var(xs)))
  (+:int (+:int var($sr_p_0) var($sr_p_1)) (jcall List.get var(m1) 0:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: any = opaque.CoreCollectionCall(list, v0) {impure}
    v3: any = move v2
    v4: int = add v0, v1 {pure}
    v5: int = const 0
    v6: any = call_java v3.get(v5) {impure}
    v7: any = checkcast v6 <int|int|>
    v8: int = convert.int v7
    v9: int = add v4, v8 {pure}
    ret v9
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 2
    v2: any = opaque.CoreCollectionCall(list, v0) {impure}
    v3: any = move v2
    v4: int = add v0, v1 {pure}
    v5: int = const 0
    v6: any = call_java v3.get(v5) {impure}
    v7: any = checkcast v6 <int|int|>
    v8: int = convert.int v7
    v9: int = add v4, v8 {pure}
    ret v9
```
