# WebAssembly 3.0 (WasmGC) lowering invariants

References : BIP025-I2, BIP025-I5, BIP025-D7, BIP025-D11, BIP025-D13, BIP024, BIP028-D5

## Immutable WasmGC record fields (BIP025-D11)

```babble
def Vec3 = record (x: double, y: double, z: double) (
    def lenSq = fn (): double -> (this.x * this.x + this.y * this.y + this.z * this.z)
)
def v = Vec3(3.0, 4.0, 0.0)
assert(v.lenSq() == 25.0)
```

```hir
;; hir -O0,-O1 record=Vec3
record Vec3 (x:double, y:double, z:double)
  (def lenSq (fn-ref Vec3.lenSq))
  fn Vec3.lenSq () -> double {nothrow unique}
    (+:double (+:double (*:double (get-this-field x) (get-this-field x)) (*:double (get-this-field y) (get-this-field y))) (*:double (get-this-field z) (get-this-field z)))
```

```mir
;; mir -O2 fn=Vec3.lenSq stage=before
fn Vec3.lenSq() -> double:
  bb0:
    v0: any = slot_read this {pure}
    v1: double = load_field v0.x {read:frozen}
    v2: any = slot_read this {pure}
    v3: double = load_field v2.x {read:frozen}
    v4: double = mul v1, v3 {pure}
    v5: any = slot_read this {pure}
    v6: double = load_field v5.y {read:frozen}
    v7: any = slot_read this {pure}
    v8: double = load_field v7.y {read:frozen}
    v9: double = mul v6, v8 {pure}
    v10: double = add v4, v9 {pure}
    v11: any = slot_read this {pure}
    v12: double = load_field v11.z {read:frozen}
    v13: any = slot_read this {pure}
    v14: double = load_field v13.z {read:frozen}
    v15: double = mul v12, v14 {pure}
    v16: double = add v10, v15 {pure}
    ret v16
```

```mir
;; mir -O2 fn=Vec3.lenSq
fn Vec3.lenSq() -> double:
  bb0:
    v0: any = slot_read this {pure}
    v1: double = load_field v0.x {read:frozen}
    v2: double = mul v1, v1 {pure}
    v3: double = load_field v0.y {read:frozen}
    v4: double = mul v3, v3 {pure}
    v5: double = add v2, v4 {pure}
    v6: double = load_field v0.z {read:frozen}
    v7: double = mul v6, v6 {pure}
    v8: double = add v5, v7 {pure}
    ret v8
```

```wat
;; wat -O1 check record=Vec3
contains (field $f_x f64)
contains (field $f_y f64)
contains (field $f_z f64)
!contains (field $f_x (mut
!contains (field $f_y (mut
!contains (field $f_z (mut
```

## Constant-stack self tail call lowers to return_call (BIP024)

```babble
def sumTail = fn (n: int, acc: long): long -> (
    if n <= 0 then (acc) else (recurse(n - 1, acc + n))
)
assert(sumTail(1000, 0L) == 500500L)
```

```hir
;; hir -O0 fn=sumTail
fn sumTail (n:int, acc:long) -> long {nothrow}
  (if (<=:bool var(n) 0:int) (block var(acc)) (block (tail-call sumTail (-:int var(n) 1:int) (+:long var(acc) var(n)))))
```

```hir
;; hir -O1 fn=sumTail
fn sumTail (n:int, acc:long) -> long {nothrow}
  (if (<=:bool var(n) 0:int) var(acc) (tail-call sumTail (-:int var(n) 1:int) (+:long var(acc) var(n))))
```

```mir
;; mir -O2 fn=sumTail stage=before
fn sumTail(v0: int, v1: long) -> long:
  bb0:
    v2: int = const 0
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: long = convert.long v0
    v7: long = add v1, v6 {pure}
    tail_call sumTail(v5, v7)
  bb2:
    br bb3
  bb3:
    ret v1
```

```mir
;; mir -O2 fn=sumTail
fn sumTail(v0: int, v1: long) -> long:
  bb0:
    v2: int = const 0
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: long = convert.long v0
    v7: long = add v1, v6 {pure}
    tail_call sumTail(v5, v7)
  bb2:
    ret v1
```

```wat
;; wat -O1 check fn=sumTail
contains return_call
```

## Closed-world intersection bitset check uses single i64.and + i64.eq (BIP025-D7)

```babble
def A = interface (
    def a: fn (): int
)
def B = interface (
    def b: fn (): int
)
def Both = record (v: int) implements A, B (
    def a = fn (): int -> (this.v)
    def b = fn (): int -> (this.v * 2)
)
def OnlyA = record (v: int) implements A (
    def a = fn (): int -> (this.v)
)
def isBoth = fn (x: any): bool -> (x is A & B)
assert(isBoth(Both(5)))
assert(not isBoth(OnlyA(5)))
```

```hir
;; hir -O0,-O1 fn=isBoth
fn isBoth (x:obj) -> bool {nothrow unique}
  (is var(x) A&B)
```

```mir
;; mir -O2 fn=isBoth stage=before,final
fn isBoth(v0: any) -> bool:
  bb0:
    v1: bool = is v0 <inter|A & B|babble%7CA,babble%7CB>
    ret v1
```

```wat
;; wat -O1 check fn=isBoth
contains i64.and
contains i64.eq
```

## Single-implementer interface devirtualization avoids ITable lookup (BIP025-D7)

```babble
def Sole = interface (
    def compute: fn (n: int): int
)
def OnlyImpl = record (base: int) implements Sole (
    def compute = fn (n: int): int -> (this.base + n)
)
def runSole = fn (s: Sole, k: int): int -> (s.compute(k))
assert(runSole(OnlyImpl(40), 2) == 42)
```

```hir
;; hir -O0,-O1 fn=runSole
fn runSole (s:obj, k:int) -> int {pure unique}
  (call-iface var(s).compute var(k))
```

```mir
;; mir -O2 fn=runSole stage=before,final
fn runSole(v0: any, v1: int) -> int:
  bb0:
    v2: int = call_iface v0.compute(v1) {pure,throws}
    ret v2
```

```wat
;; wat -O1 check fn=runSole
contains ref.cast (ref $C0_OnlyImpl)
contains _compute
!contains call_ref
```

## Nothrow finally elides inner suppression handler (BIP025-D13, BIP028-D5)

```babble
var cleanup = 0
def safeFinally = fn io (boom: bool): int -> (
    try (
        if boom then (throw exception("boom"))
        10
    ) finally (
        cleanup = cleanup + 1
    )
)
assert(safeFinally(false) == 10)
assert(cleanup == 1)
def caught = try (
    safeFinally(true)
    false
) catch e: exception then (
    true
)
assert(caught)
assert(cleanup == 2)
```

```hir
;; hir -O0 fn=safeFinally
fn safeFinally (boom:bool) -> int {io}
  (try
    (block
      (if var(boom) (block (throw (jnew BabbleException "boom"))))
      10:int)
    (finally
      (set-name cleanup (+:int name(cleanup) 1:int))))
```

```hir
;; hir -O1 fn=safeFinally
fn safeFinally (boom:bool) -> int {io}
  (try
    (block
      (if var(boom) (throw (jnew BabbleException "boom")))
      10:int)
    (finally
      (set-name cleanup (+:int name(cleanup) 1:int))))
```

```mir
;; mir -O2 fn=safeFinally stage=before
fn safeFinally(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: * -> bb7]:
    br_if v0, bb6, bb2
  bb2 [unwind: * -> bb7]:
    v1: any = const null
    br bb3
  bb3 [unwind: * -> bb7]:
    v2: int = const 10
    br bb4
  bb4:
    v3: any = name_read cleanup {read:shared}
    v4: int = convert.int v3
    v5: int = const 1
    v6: int = add v4, v5 {pure}
    v7: any = convert.any v6
    v8: any = name_assign cleanup, v7 {write:shared,throws}
    v9: any = checkcast v8 <int|int|>
    br bb5
  bb5:
    ret v2
  bb6 [unwind: * -> bb7]:
    v10: any = const "boom"
    v11: any = new_java BabbleException(v10) {pure!}
    rethrow v11
  bb7(v12: any) [handler]:
    br bb8
  bb8:
    v13: any = name_read cleanup {read:shared}
    v14: int = convert.int v13
    v15: int = const 1
    v16: int = add v14, v15 {pure}
    v17: any = convert.any v16
    v18: any = name_assign cleanup, v17 {write:shared,throws}
    v19: any = checkcast v18 <int|int|>
    rethrow v12
```

```mir
;; mir -O2 fn=safeFinally
fn safeFinally(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: * -> bb6]:
    br_if v0, bb5, bb2
  bb2 [unwind: * -> bb6]:
    v1: int = const 10
    br bb3
  bb3:
    v2: any = name_read cleanup {read:shared}
    v3: int = convert.int v2
    v4: int = const 1
    v5: int = add v3, v4 {pure}
    v6: any = convert.any v5
    v7: any = name_assign cleanup, v6 {write:shared,throws}
    v8: any = checkcast v7 <int|int|>
    br bb4
  bb4:
    ret v1
  bb5 [unwind: * -> bb6]:
    v9: any = const "boom"
    v10: any = new_java BabbleException(v9) {pure!}
    rethrow v10
  bb6(v11: any) [handler]:
    br bb7
  bb7:
    v12: any = name_read cleanup {read:shared}
    v13: int = convert.int v12
    v14: int = const 1
    v15: int = add v13, v14 {pure}
    v16: any = convert.any v15
    v17: any = name_assign cleanup, v16 {write:shared,throws}
    v18: any = checkcast v17 <int|int|>
    rethrow v11
```

```wat
;; wat -O1 check fn=safeFinally
contains try_table
!contains $add_suppressed
```

## Power-of-two mod and floorDiv lower to bitmask and shift (BIP031)

```babble
def pow2DivMod = fn (x: int): int -> ( (x // 8) + (x mod 8) )
assert(pow2DivMod(-7) == 0 and pow2DivMod(19) == 5)
```

```hir
;; hir -O0 fn=pow2DivMod
fn pow2DivMod (x:int) -> int {nothrow unique}
  (+:int (block (//:int var(x) 8:int)) (block (mod:int var(x) 8:int)))
```

```hir
;; hir -O1 fn=pow2DivMod
fn pow2DivMod (x:int) -> int {nothrow unique}
  (+:int (//:int var(x) 8:int) (mod:int var(x) 8:int))
```

```mir
;; mir -O2 fn=pow2DivMod stage=before
fn pow2DivMod(v0: int) -> int:
  bb0:
    v1: int = const 8
    v2: int = floordiv v0, v1 {pure}
    v3: int = const 8
    v4: int = mod v0, v3 {pure}
    v5: int = add v2, v4 {pure}
    ret v5
```

```mir
;; mir -O2 fn=pow2DivMod
fn pow2DivMod(v0: int) -> int:
  bb0:
    v1: int = const 8
    v2: int = floordiv v0, v1 {pure}
    v3: int = mod v0, v1 {pure}
    v4: int = add v2, v3 {pure}
    ret v4
```

```wat
;; wat -O1 check fn=pow2DivMod
contains i32.shr_s
contains i32.and
!contains $floor_div_i32
!contains $floor_mod_i32
```

## Counted range loop lowers to unboxed Wasm loop without range allocation (BIP031)

```babble
def sumCounted = fn (n: int): int -> (
    var acc = 0
    for i in 0..<n then ( acc += i )
    acc
)
assert(sumCounted(6) == 15 and sumCounted(0) == 0)
```

```hir
;; hir -O0 fn=sumCounted
fn sumCounted (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
```

```hir
;; hir -O1 fn=sumCounted
fn sumCounted (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
```

```mir
;; mir -O2 fn=sumCounted stage=before
fn sumCounted(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb1, bb5(v3, v1)
  bb1:
    v5: int = const 1
    v6: int = sub v0, v5 {pure}
    br bb2(v3, v2, v1)
  bb2(v7: any, v8: int, v9: int):
    v10: int = add v9, v8 {pure}
    v11: any = convert.any v10
    br bb3
  bb3:
    v12: bool = eq v8, v6 {pure}
    br_if v12, bb5(v11, v10), bb4
  bb4:
    v13: int = const 1
    v14: int = add v8, v13 {pure}
    br bb2(v11, v14, v10)
  bb5(v15: any, v16: int):
    ret v16
```

```mir
;; mir -O2 fn=sumCounted
fn sumCounted(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lt v1, v0 {pure}
    br_if v2, bb1, bb5(v1)
  bb1:
    v3: int = const 1
    v4: int = sub v0, v3 {pure}
    br bb2(v1, v1)
  bb2(v5: int, v6: int):
    v7: int = add v6, v5 {pure}
    br bb3
  bb3:
    v8: bool = eq v5, v4 {pure}
    br_if v8, bb5(v7), bb4
  bb4:
    v9: int = add v5, v3 {pure}
    br bb2(v9, v7)
  bb5(v10: int):
    ret v10
```

```wat
;; wat -O1 check fn=sumCounted
contains loop
contains i32.ne
contains br 1
!contains $range_int
!contains $range_iter
```

