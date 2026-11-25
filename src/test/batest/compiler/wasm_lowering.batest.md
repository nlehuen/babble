# WebAssembly 3.0 (WasmGC) lowering invariants

References : BIP025-P2, BIP025-P5, BIP025-D7, BIP025-D11, BIP025-D13, BIP024, BIP028-D5

## Immutable WasmGC record fields (BIP025-D11)

```babble
def Vec3 = record (x: double, y: double, z: double) (
    def lenSq = fn (): double -> (this.x * this.x + this.y * this.y + this.z * this.z)
)
def v = Vec3(3.0, 4.0, 0.0)
assert(v.lenSq() == 25.0)
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

```wat
;; wat -O1 check fn=runSole
contains ref.cast (ref $C0_OnlyImpl)
contains _compute
!contains call_ref
```

## Nothrow finally elides inner suppression handler (BIP025-D13, BIP028-D5)

```babble
def cleanup = 0
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

```wat
;; wat -O1 check fn=safeFinally
contains try_table
!contains $add_suppressed
```
