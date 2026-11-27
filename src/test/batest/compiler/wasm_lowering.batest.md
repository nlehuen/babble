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

```hir
;; hir -O2 fn=Vec3.lenSq
fn Vec3.lenSq () -> double {nothrow unique}
  (block
    (def $cse0 (get-this-field x))
    (def $cse1 (get-this-field y))
    (def $cse2 (get-this-field z))
    (+:double (+:double (*:double var($cse0) var($cse0)) (*:double var($cse1) var($cse1))) (*:double var($cse2) var($cse2))))
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
  (if (<=:bool var(n) 0:int) (block var(acc)) (block (tail-call sumTail (-:int var(n) 1:int) (+:long var(acc) (convert[long] var(n))))))
```

```hir
;; hir -O1 fn=sumTail
fn sumTail (n:int, acc:long) -> long {nothrow}
  (if (<=:bool var(n) 0:int) var(acc) (tail-call sumTail (-:int var(n) 1:int) (+:long var(acc) (convert[long] var(n)))))
```

```hir
;; hir -O2 fn=sumTail
fn sumTail (n:int, acc:long) -> long {nothrow}
  (if (<=:bool var(n) 0:int) var(acc) (tail-call sumTail (-:int var(n) 1:int) (+:long var(acc) (convert[long] var(n)))))
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

```hir
;; hir -O2 fn=isBoth
fn isBoth (x:obj) -> bool {nothrow unique}
  (is var(x) A&B)
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

```hir
;; hir -O2 fn=runSole
fn runSole (s:obj, k:int) -> int {pure unique}
  (call-iface var(s).compute var(k))
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

```hir
;; hir -O2 fn=safeFinally
fn safeFinally (boom:bool) -> int {io}
  (try
    (block
      (if var(boom) (throw (jnew BabbleException "boom")))
      10:int)
    (finally
      (set-name cleanup (+:int name(cleanup) 1:int))))
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

```hir
;; hir -O2 fn=pow2DivMod
fn pow2DivMod (x:int) -> int {nothrow unique}
  (+:int (//:int var(x) 8:int) (mod:int var(x) 8:int))
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

```hir
;; hir -O2 fn=sumCounted
fn sumCounted (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
```

```wat
;; wat -O1 check fn=sumCounted
contains loop
contains i32.ne
contains br 1
!contains $range_int
!contains $range_iter
```

