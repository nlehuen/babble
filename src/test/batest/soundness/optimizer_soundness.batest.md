# Soundness regressions : `opt` (`R4`, `R5`) LICM, SCCP, AEGraph, and liveness

Promoted from `BIP032` quarantine (`opt_r4_licm_for_iterable_verify_error.batest.md`, `opt_r5_o2_sccp_compound_assign_order.batest.md`, `opt_r5_o2_sccp_loop_fixpoint_cap.batest.md`, `opt_r5_aegraph_unique_param_mut_rle.batest.md`) and sister optimizer vectors covering `HirLicmPass`, `HirSccpPass`, `HirLiveness`, `HirEffectAnalysis`, and `AeGraphBuilder`.

## For-loop with mutating iterable and loop-invariant body arithmetic verifies and matches interpret

```babble
def test = fn (n: int): int -> (
    var acc = 0
    var k = n
    for i in (k = n + 10; 0..3) then (
        acc += (k * 7 + 1) + i
    )
    acc
)
assert(test(1) == 318 and test(5) == 430)
```

## For-loop with mutating counted-range bound does not hoist body arithmetic before bound eval

```babble
def test = fn (n: int): int -> (
    var acc = 0
    var k = 0
    for i in 0..(k = n + 4; 3) then (
        acc += (k * 3) + i
    )
    acc
)
assert(test(2) == 78 and test(10) == 174)
```

## While-loop condition guard prevents hoisting guarded body division before loop

```babble
def safeLoop = fn (d: int): int -> (
    var i = 0
    var acc = 0
    while d != 0 and i < 3 then (
        acc += 12 // d
        i += 1
    )
    acc
)
assert(safeLoop(0) == 0 and safeLoop(3) == 12)
```

## Compound assignment reads LHS before RHS side effects at -O2

```babble
def test = fn (): int -> (
    var x = 10
    x += (x = 50; 1)
    x
)
assert(test() == 11)
```

## For-loop shift register longer than 32 must not constant-fold v0 to 0 at -O2

```babble
;; babble large="shift register exceeding MAX_LOOP_FIXPOINT_ITERS (32)"
def shift34 = fn (): int -> (
    var v0 = 0
    var v1 = 0
    var v2 = 0
    var v3 = 0
    var v4 = 0
    var v5 = 0
    var v6 = 0
    var v7 = 0
    var v8 = 0
    var v9 = 0
    var v10 = 0
    var v11 = 0
    var v12 = 0
    var v13 = 0
    var v14 = 0
    var v15 = 0
    var v16 = 0
    var v17 = 0
    var v18 = 0
    var v19 = 0
    var v20 = 0
    var v21 = 0
    var v22 = 0
    var v23 = 0
    var v24 = 0
    var v25 = 0
    var v26 = 0
    var v27 = 0
    var v28 = 0
    var v29 = 0
    var v30 = 0
    var v31 = 0
    var v32 = 0
    var v33 = 1
    for i in 0..40 then (
        v0 = v1
        v1 = v2
        v2 = v3
        v3 = v4
        v4 = v5
        v5 = v6
        v6 = v7
        v7 = v8
        v8 = v9
        v9 = v10
        v10 = v11
        v11 = v12
        v12 = v13
        v13 = v14
        v14 = v15
        v15 = v16
        v16 = v17
        v17 = v18
        v18 = v19
        v19 = v20
        v20 = v21
        v21 = v22
        v22 = v23
        v23 = v24
        v24 = v25
        v25 = v26
        v26 = v27
        v27 = v28
        v28 = v29
        v29 = v30
        v30 = v31
        v31 = v32
        v32 = v33
        v33 = 1
    )
    v0
)
assert(shift34() == 1)
```

## While-loop shift register longer than 32 preserves liveness and values across -O1 and -O2

```babble
;; babble large="while-loop shift register exceeding MAX_LOOP_FIXPOINT_ITERS (32)"
def whileShift34 = fn (): int -> (
    var i = 0
    var v0 = 0
    var v1 = 0
    var v2 = 0
    var v3 = 0
    var v4 = 0
    var v5 = 0
    var v6 = 0
    var v7 = 0
    var v8 = 0
    var v9 = 0
    var v10 = 0
    var v11 = 0
    var v12 = 0
    var v13 = 0
    var v14 = 0
    var v15 = 0
    var v16 = 0
    var v17 = 0
    var v18 = 0
    var v19 = 0
    var v20 = 0
    var v21 = 0
    var v22 = 0
    var v23 = 0
    var v24 = 0
    var v25 = 0
    var v26 = 0
    var v27 = 0
    var v28 = 0
    var v29 = 0
    var v30 = 0
    var v31 = 0
    var v32 = 0
    var v33 = 7
    while i < 40 then (
        v0 = v1
        v1 = v2
        v2 = v3
        v3 = v4
        v4 = v5
        v5 = v6
        v6 = v7
        v7 = v8
        v8 = v9
        v9 = v10
        v10 = v11
        v11 = v12
        v12 = v13
        v13 = v14
        v14 = v15
        v15 = v16
        v16 = v17
        v17 = v18
        v18 = v19
        v19 = v20
        v20 = v21
        v21 = v22
        v22 = v23
        v23 = v24
        v24 = v25
        v25 = v26
        v26 = v27
        v27 = v28
        v28 = v29
        v29 = v30
        v30 = v31
        v31 = v32
        v32 = v33
        v33 = 7
        i += 1
    )
    v0
)
assert(whileShift34() == 7)
```

## Unique parameter field load must not be forwarded across mut call at -O2

```babble
def Box = class (var v: int) ()
def bump = fn (mut b: Box) -> (b.v += 10)
def test = fn (unique b: Box): int -> (
    def a = b.v
    bump(b)
    def c = b.v
    a * 100 + c
)
assert(test(Box(3)) == 313)
```

## Unique parameter field load must not be forwarded across mut method call at -O2

```babble
def Box = class (var v: int) (
    def inc = fn mut () -> (this.v += 10)
)
def test = fn (unique b: Box): int -> (
    def a = b.v
    b.inc()
    def c = b.v
    a * 100 + c
)
assert(test(Box(3)) == 313)
```

## Unique parameter aliased through local record field must not reuse stale load at -O2

```babble
def Box = class (var v: int) ()
def Wrap = record (b: Box) ()
def test = fn io (unique b: Box): int -> (
    def a = b.v
    def w = Wrap(b)
    w.b.v = 99
    def c = b.v
    a * 100 + c
)
assert(test(Box(3)) == 399)
```

## Nested branch scope must preserve prior impure memory barrier at -O2

```babble
def Box = class (var v: int) ()
def swapWrite = fn (mut a: Box, mut b: Box) -> (
    a.v = 77
    b.v = 88
)
def test = fn (unique a: Box, mut b: Box, flag: bool): int -> (
    def first = a.v
    swapWrite(a, b)
    def second = if flag then (a.v) else (a.v + 1)
    first * 100 + second
)
assert(test(Box(3), Box(4), true) == 377)
```

