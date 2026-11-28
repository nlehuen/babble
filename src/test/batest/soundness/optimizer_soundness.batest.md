# Soundness regressions : `opt` (`R3`, `R4`, `R5`) const-folding, numeric parity, LICM, SCCP, AEGraph, pipeline rewrites, tail calls & Wasm parity

Promoted from `BIP032` quarantine (`opt_r4_licm_for_iterable_verify_error.batest.md`, `opt_r5_o2_sccp_compound_assign_order.batest.md`, `opt_r5_o2_sccp_loop_fixpoint_cap.batest.md`, `opt_r5_aegraph_unique_param_mut_rle.batest.md`, `opt_r5_wasm_o2_byte_range_error_message.batest.md`, `opt_r3_double_range_contains_order_divergence.batest.md`, `opt_r3_mixed_int_float_comparison_parity.batest.md`, `opt_r4_licm_early_exit_null_guard_hoist.batest.md`, `opt_r5_tail_calls_reassigned_var_method.batest.md`, `opt_r4_range_clamp_loop_variant_bound.batest.md`, `opt_r5_wasm_undeclared_assignment_error.batest.md`) and sister optimizer vectors covering `HirConstFolder`, `HirConvertMaterializer`, `HirLicmPass`, `HirSccpPass`, `HirLiveness`, `HirEffectAnalysis`, `TailCalls`, `AeGraphBuilder`, `HirPipelineLifter`, and `WasmVarAndDefEmitter`.

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

## Byte conversion range error names its operand in every mode

Called directly, `byte(...)` quotes its operand on every backend ; called through a variable it still differs on Wasm, a divergence declared in `runtime/backend_divergences.batest.md`.

```babble
def f = fn (b: byte): byte -> (byte(b + 1))
println(f(byte(127)))
```

```error
Out of the byte range : b+1 = 128
```

## Repeated builtin conversions are not hoisted into a temp at -O2

```babble
def f = fn (b: byte, c: byte): int -> (int(byte(b + 1)) + int(byte(c + 1)))
println(f(byte(1), byte(127)))
```

```error
Out of the byte range : c+1 = 128
```

## DoubleRange.contains agrees across all modes on -0.0 and NaN

```babble
def f = fn (x: double): bool -> ((0.0..1.0).contains(x))
def g = fn (hi: double): bool -> ((0.0..hi).contains(1.0))
assert(f(-0.0) and not g(0.0 / 0.0))
```

## Comparing int with float does not lossily round int to float

```babble
def cmp = fn (a: int, b: float): bool -> (a == b)
assert(not cmp(16777217, float(16777216)) and (16777217 > float(16777216)))
```

## Zero-trip while loop with early-exit null check must not hoist guarded field load

```babble
def Point = record (x: int) ()
def test = fn (p: Point?, n: int): int -> (
    var s = 0
    var i = 0
    while i < n then (
        if p == null then (return s)
        s += p.x
        i += 1
    )
    s
)
assert(test(null, 0) == 0 and test(Point(5), 3) == 15)
```

## Early-exit reversed null and negated type-test guards prevent LICM hoisting

```babble
def Pt = record (x: int) ()
def g1 = fn (p: Pt?, n: int): int -> (
    var s = 0; var i = 0
    while i < n then (if null == p then (return s); s += p.x; i += 1)
    s
)
def g2 = fn (p: any, n: int): int -> (
    var s = 0; var i = 0
    while i < n then (if not (p is Pt) then (return s); s += p.x; i += 1)
    s
)
assert(g1(null, 0) == 0 and g2(Pt(4), 2) == 8)
```

## Early-exit match guard prevents LICM hoisting

```babble
def Pt = record (x: int) ()
def g3 = fn (p: Pt?, n: int): int -> (
    var s = 0; var i = 0
    while i < n then (
        def v = match p case null then (return s) case is Pt if p.x > 0 then (p.x) else (return s)
        s += v; i += 1
    )
    s
)
assert(g3(null, 0) == 0 and g3(Pt(3), 2) == 6)
```

## Early-exit short-circuit null guard inside counted for-loop prevents LICM hoisting

```babble
def Pt = record (x: int) ()
def g4 = fn (p: Pt?, q: Pt?, n: int): int -> (
    var s = 0
    for i in 0..<n then (
        if p != null and q != null then (s += p.x + q.x) else (return s)
    )
    s
)
assert(g4(null, Pt(1), 0) == 0 and g4(Pt(2), Pt(3), 2) == 10)
```

## Early-exit null guard prevents LICM hoisting of guarded array size and compound field load

```babble
def Pt = record (x: int) ()
def g5 = fn (a: (array of int)?, p: Pt?, n: int): int -> (
    var s = 0; var i = 0
    while i < n then (
        if a == null or p == null then (return s)
        s += (a.size() + 1) + (p.x * 2)
        i += 1
    )
    s
)
assert(g5(null, null, 0) == 0 and g5(array.of(1, 2), Pt(5), 2) == 26)
```

## Reassigning var method field before tail call invokes the new function

```babble
def C = class () (
    var step: fn mut (int): int = fn mut (n: int): int -> (
        if n <= 0 then (0)
        else (
            this.step = fn mut (k: int): int -> (999)
            this.step(n - 1)
        )
    )
)
assert(C().step(2) == 999)
```

## Range clamping must not hoist an upper bound the loop body reassigns

```babble
def up = fn (n: int): int -> (
    var lim = 2; var acc = 0
    for x in 0..n then (if x <= lim then (acc += x; lim += 1))
    acc
)
def ex = fn (n: int): int -> (
    var lim = 2; var acc = 0
    for x in 0..<n then (if x < lim then (acc += x; lim += 1))
    acc
)
assert(up(10) == 55 and ex(10) == 45)
```

## Range clamping must not hoist a lower bound the loop body reassigns

```babble
def f = fn (n: int): int -> (
    var lo = 0; var acc = 0
    for x in 0..n then (if x >= lo then (acc += x; lo += 2))
    acc
)
assert(f(10) == 30)
```

## Range clamping must not hoist a long bound the loop body reassigns

```babble
def f = fn (n: long): long -> (
    var lim = 2L; var acc = 0L
    for x in 0L..n then (if lim >= x then (acc += x; lim += 1L))
    acc
)
assert(f(10L) == 55L)
```

## Range clamping must not hoist a bound reassigned by a closure, nested loop or match arm

```babble
def viaFn = fn (n: int): int -> (
    var lim = 2; var acc = 0
    def bump = fn () -> (lim += 1)
    for x in 0..n then (if x <= lim then (acc += x; bump()))
    acc
)
def viaLoop = fn (n: int): int -> (
    var lim = 2; var acc = 0
    for x in 0..n then (if x <= lim then (acc += x; for k in 0..<1 then (lim += 1)))
    acc
)
assert(viaFn(10) == 55 and viaLoop(10) == 55)
```

## Range clamping must not hoist a bound reassigned in a match arm

```babble
def f = fn (n: int): int -> (
    var lim = 2; var acc = 0
    for x in 0..n then (
        if x <= lim then (acc += x; match x case 0 then (0) else (lim += 1))
    )
    acc
)
assert(f(10) == 55)
```

## Fused producer and consumer must not hoist a bound the consumer reassigns

```babble
def f = fn (n: int): int -> (
    var lim = 1
    def tmp = list of int ()
    for x in 0..n then (tmp.add(x))
    var acc = 0
    for y in tmp then (if y <= lim then (acc += y; lim += 1))
    acc
)
assert(f(10) == 55)
```

## Loop-invariant bounds and bounds reassigned after the loop still agree

```babble
def f = fn (n: int): int -> (
    var lim = 4; var acc = 0
    for x in 0..n then (if x <= lim then (acc += x))
    lim = 0
    acc + lim
)
assert(f(10) == 10 and f(2) == 3)
```

## Assigning an undeclared name fails as No such name

```babble
zz = 3
```

```error
No such name : zz
```

## Assigning an undeclared name inside a call argument fails as No such name

```babble
def g = fn (a: int): int -> (a)
println(g(zz = 3))
```

```error
No such name : zz
```
