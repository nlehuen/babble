# SSA MIR Lowering, Primitives, Loops, and Tail Calls (BIP014-I3)

Tests HIR-to-MIR lowering (`MirLowerer`) and bytecode emission (`MirEmitter`) across
primitives, loops, swap cycles, narrowing anchors, tail calls, and non-lowered markers.

## All 8 primitive kinds, widening, and unary ops

```babble
def prims = fn (b: byte, s: short, i: int, l: long, f: float, d: double, c: char, p: bool): double -> (
    def r = (b + s + (-i) + (-l)) * 1.0 + (-f) + (-d)
    if (not p) and (c == 'a') then (r) else (r + 1.0)
)
assert(prims(byte(1), short(2), 3, 4L, 1.5f, 2.5, 'a', false) == -8.0)
assert(prims(byte(1), short(2), 3, 4L, 1.5f, 2.5, 'b', true) == -7.0)
```

```hir
;; hir -O0 fn=prims
fn prims (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, p:bool) -> double {nothrow}
  (def r (+:double (+:double (*:double (block (+:long (+:int (+:int var(b) var(s)) (block (neg var(i)))) (block (neg var(l))))) 1.0:double) (block (neg var(f)))) (block (neg var(d)))))
  (if (and (block (not var(p))) (block (==:bool var(c) 'a'))) (block var(r)) (block (+:double var(r) 1.0:double)))
```

```hir
;; hir -O1 fn=prims
fn prims (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, p:bool) -> double {nothrow}
  (def r (+:double (+:double (*:double (+:long (+:int (+:int var(b) var(s)) (neg var(i))) (neg var(l))) 1.0:double) (neg var(f))) (neg var(d))))
  (if (and (not var(p)) (==:bool var(c) 'a')) var(r) (+:double var(r) 1.0:double))
```

```mir
;; mir -O2 fn=prims stage=before
fn prims(v0: byte, v1: short, v2: int, v3: long, v4: float, v5: double, v6: char, v7: bool) -> double:
  bb0:
    v8: int = convert.int v0
    v9: int = convert.int v1
    v10: int = add v8, v9 {pure}
    v11: int = neg v2 {pure}
    v12: int = add v10, v11 {pure}
    v13: long = convert.long v12
    v14: long = neg v3 {pure}
    v15: long = add v13, v14 {pure}
    v16: double = convert.double v15
    v17: double = const 1.0
    v18: double = mul v16, v17 {pure}
    v19: float = neg v4 {pure}
    v20: double = convert.double v19
    v21: double = add v18, v20 {pure}
    v22: double = neg v5 {pure}
    v23: double = add v21, v22 {pure}
    v24: bool = not v7 {pure}
    v25: bool = const false
    br_if v24, bb1, bb2(v25)
  bb1:
    v26: char = const 'a'
    v27: bool = eq v6, v26 {pure}
    br bb2(v27)
  bb2(v28: bool):
    br_if v28, bb4, bb3
  bb3:
    v29: double = const 1.0
    v30: double = add v23, v29 {pure}
    br bb5(v30)
  bb4:
    br bb5(v23)
  bb5(v31: double):
    ret v31
```

```mir
;; mir -O2 fn=prims
fn prims(v0: byte, v1: short, v2: int, v3: long, v4: float, v5: double, v6: char, v7: bool) -> double:
  bb0:
    v8: int = convert.int v0
    v9: int = convert.int v1
    v10: int = add v8, v9 {pure}
    v11: int = neg v2 {pure}
    v12: int = add v10, v11 {pure}
    v13: long = convert.long v12
    v14: long = neg v3 {pure}
    v15: long = add v13, v14 {pure}
    v16: double = convert.double v15
    v17: double = const 1.0
    v18: double = mul v16, v17 {pure}
    v19: float = neg v4 {pure}
    v20: double = convert.double v19
    v21: double = add v18, v20 {pure}
    v22: double = neg v5 {pure}
    v23: double = add v21, v22 {pure}
    v24: bool = not v7 {pure}
    v25: bool = const false
    br_if v24, bb1, bb2(v25)
  bb1:
    v26: char = const 'a'
    v27: bool = eq v6, v26 {pure}
    br bb2(v27)
  bb2(v28: bool):
    br_if v28, bb4, bb3
  bb3:
    v29: double = add v23, v17 {pure}
    br bb5(v29)
  bb4:
    br bb5(v23)
  bb5(v30: double):
    ret v30
```

## Typed array for-loop with break and continue

```babble
def sumArr = fn (xs: array of int): int -> (
    var acc = 0
    for x in xs then (
        if x < 0 then (continue)
        if x == 99 then (break)
        acc += x
    )
    acc
)
assert(sumArr((array of int).of(2, -1, 3, 99, 5)) == 5)
```

```hir
;; hir -O0 fn=sumArr
fn sumArr (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (block (continue)))
      (if (==:bool var(x) 99:int) (block (break)))
      (set acc += var(x))))
  var(acc)
```

```hir
;; hir -O1 fn=sumArr
fn sumArr (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (continue))
      (if (==:bool var(x) 99:int) (break))
      (set acc += var(x))))
  var(acc)
```

```mir
;; mir -O2 fn=sumArr stage=before
fn sumArr(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.array v0
    v3: any = const null
    v4: int = array_len v2 {read:frozen}
    v5: int = const 0
    br bb1(v3, v5, v1)
  bb1(v6: any, v7: int, v8: int):
    v9: bool = for_has_next v2, v7, v4
    br_if v9, bb2, bb8(v6)
  bb2:
    v10: int = for_next v2, v7
    v11: int = const 0
    v12: bool = lt v10, v11 {pure}
    br_if v12, bb9, bb3
  bb3:
    v13: any = const null
    br bb4
  bb4:
    v14: int = const 99
    v15: bool = eq v10, v14 {pure}
    br_if v15, bb7, bb5
  bb5:
    v16: any = const null
    br bb6
  bb6:
    v17: int = add v8, v10 {pure}
    v18: any = convert.any v17
    br bb10(v18, v17)
  bb7:
    v19: any = const null
    br bb8(v19)
  bb8(v20: any):
    ret v8
  bb9:
    br bb10(v6, v8)
  bb10(v21: any, v22: int):
    v23: int = const 1
    v24: int = add v7, v23 {pure}
    br bb1(v21, v24, v22)
```

```mir
;; mir -O2 fn=sumArr
fn sumArr(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.array v0
    v3: int = array_len v2 {read:frozen}
    br bb1(v1, v1)
  bb1(v4: int, v5: int):
    v6: bool = for_has_next v2, v4, v3
    br_if v6, bb2, bb5
  bb2:
    v7: int = for_next v2, v4
    v8: bool = lt v7, v1 {pure}
    br_if v8, bb6, bb3
  bb3:
    v9: int = const 99
    v10: bool = eq v7, v9 {pure}
    br_if v10, bb5, bb4
  bb4:
    v11: int = add v5, v7 {pure}
    br bb7(v11)
  bb5:
    ret v5
  bb6:
    br bb7(v5)
  bb7(v12: int):
    v13: int = const 1
    v14: int = add v4, v13 {pure}
    br bb1(v14, v12)
```

## Dynamic-union for-loop over array or list

```babble
def sumDyn = fn (xs: array of int | list of int): int -> (
    var s = 0
    for v in xs then (s += v)
    s
)
assert(sumDyn((array of int).of(1, 2, 3)) == 6)
assert(sumDyn(list(4, 5, 6)) == 15)
```

```hir
;; hir -O0 fn=sumDyn
fn sumDyn (xs:obj) -> int {pure unique}
  (def s 0:int)
  (for v in var(xs)
    (block
      (set s += var(v))))
  var(s)
```

```hir
;; hir -O1 fn=sumDyn
fn sumDyn (xs:obj) -> int {pure unique}
  (def s 0:int)
  (for v in var(xs)
    (block
      (set s += var(v))))
  var(s)
```

```mir
;; mir -O2 fn=sumDyn stage=before
fn sumDyn(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.dynamic_union v0
    v3: any = const null
    br bb1(v3, v1)
  bb1(v4: any, v5: int):
    v6: bool = for_has_next v2
    br_if v6, bb3, bb2
  bb2:
    ret v5
  bb3:
    v7: any = for_next v2
    v8: any = checkcast v7 <int|int|>
    v9: int = convert.int v8
    v10: int = add v5, v9 {pure}
    v11: any = convert.any v10
    br bb1(v11, v10)
```

```mir
;; mir -O2 fn=sumDyn
fn sumDyn(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = for_init.dynamic_union v0
    br bb1(v1)
  bb1(v3: int):
    v4: bool = for_has_next v2
    br_if v4, bb3, bb2
  bb2:
    ret v3
  bb3:
    v5: any = for_next v2
    v6: any = checkcast v5 <int|int|>
    v7: int = convert.int v6
    v8: int = add v3, v7 {pure}
    br bb1(v8)
```

## While loop with swap parallel-copy cycle

```babble
def fib = fn (n: int): int -> (
    var a = 0
    var b = 1
    var i = 0
    while i < n then (
        def t = a + b
        a = b
        b = t
        i += 1
    )
    a
)
assert(fib(0) == 0 and fib(7) == 13)
```

```hir
;; hir -O0 fn=fib
fn fib (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def b 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def t (+:int var(a) var(b)))
      (set a var(b))
      (set b var(t))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=fib
fn fib (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def b 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def t (+:int var(a) var(b)))
      (set a var(b))
      (set b var(t))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=fib stage=before
fn fib(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v1, v2)
  bb1(v5: any, v6: int, v7: int, v8: int):
    v9: bool = lt v6, v0 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v7
  bb3:
    v10: int = add v7, v8 {pure}
    v11: int = const 1
    v12: int = add v6, v11 {pure}
    v13: any = convert.any v12
    br bb1(v13, v12, v8, v10)
```

```mir
;; mir -O2 fn=fib
fn fib(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 1
    br bb1(v1, v1, v2)
  bb1(v3: int, v4: int, v5: int):
    v6: bool = lt v3, v0 {pure}
    br_if v6, bb3, bb2
  bb2:
    ret v4
  bb3:
    v7: int = add v4, v5 {pure}
    v8: int = add v3, v2 {pure}
    br bb1(v8, v5, v7)
```

## Narrowing anchors on null check, is-check, and safe call

```babble
def narrowLen = fn (s: string?, v: int | string): int -> (
    def a = if s <> null then (s.length()) else (0)
    def b = if v is string then (v.length()) else (v)
    def c = s?.length()
    a + b + (if c <> null then (c) else (0))
)
assert(narrowLen("abc", "xy") == 8 and narrowLen(null, 5) == 5)
```

```hir
;; hir -O0 fn=narrowLen
fn narrowLen (s:obj, v:obj) -> int {nothrow unique}
  (def a (if (<>:bool var(s) null) (block (jcall String.length var(s))) (block 0:int)))
  (def b (if (is var(v) string) (block (jcall String.length var(v))) (block var(v))))
  (def c (safe-call var(s)?.length))
  (+:int (+:int var(a) var(b)) (block (if (<>:bool var(c) null) (block var(c)) (block 0:int))))
```

```hir
;; hir -O1 fn=narrowLen
fn narrowLen (s:obj, v:obj) -> int {nothrow unique}
  (def a (if (<>:bool var(s) null) (jcall String.length var(s)) 0:int))
  (def b (if (is var(v) string) (jcall String.length var(v)) var(v)))
  (def c (safe-call var(s)?.length))
  (+:int (+:int var(a) var(b)) (if (<>:bool var(c) null) var(c) 0:int))
```

```mir
;; mir -O2 fn=narrowLen stage=before
fn narrowLen(v0: any, v1: any) -> int:
  bb0:
    v2: any = const null
    v3: any = neq v0, v2 {pure,throws}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 0
    br bb3(v4, v0)
  bb2:
    v5: any = narrow.nonnull v0 <non-null>
    v6: int = call_java v5.length() {pure,throws}
    br bb3(v6, v5)
  bb3(v7: int, v8: any):
    v9: any = is v1 <string>
    br_if v9, bb5, bb4
  bb4:
    v10: int = convert.int v1
    br bb6(v10)
  bb5:
    v11: any = narrow.is v1 <string>
    v12: int = call_java v11.length() {pure,throws}
    br bb6(v12)
  bb6(v13: int):
    v14: any = const null
    v15: bool = neq v8, v14 {pure}
    br_if v15, bb7, bb8(v14)
  bb7:
    v16: any = narrow.nonnull v8 <non-null>
    v17: any = opaque.SafeCall(v16?.length) {impure}
    br bb8(v17)
  bb8(v18: any):
    v19: int = add v7, v13 {pure}
    v20: any = const null
    v21: any = neq v18, v20 {pure,throws}
    br_if v21, bb10, bb9
  bb9:
    v22: int = const 0
    br bb11(v22)
  bb10:
    v23: any = narrow.nonnull v18 <non-null>
    v24: int = convert.int v23
    br bb11(v24)
  bb11(v25: int):
    v26: int = add v19, v25 {pure}
    ret v26
```

```mir
;; mir -O2 fn=narrowLen
fn narrowLen(v0: any, v1: any) -> int:
  bb0:
    v2: any = const null
    v3: any = neq v0, v2 {pure,throws}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 0
    br bb3(v4, v0)
  bb2:
    v5: any = narrow.nonnull v0 <non-null>
    v6: int = call_java v5.length() {pure,throws}
    br bb3(v6, v5)
  bb3(v7: int, v8: any):
    v9: any = is v1 <string>
    br_if v9, bb5, bb4
  bb4:
    v10: int = convert.int v1
    br bb6(v10)
  bb5:
    v11: any = narrow.is v1 <string>
    v12: int = call_java v11.length() {pure,throws}
    br bb6(v12)
  bb6(v13: int):
    v14: bool = neq v8, v2 {pure}
    br_if v14, bb7, bb8(v2)
  bb7:
    v15: any = narrow.nonnull v8 <non-null>
    v16: any = opaque.SafeCall(v15?.length) {impure}
    br bb8(v16)
  bb8(v17: any):
    v18: int = add v7, v13 {pure}
    v19: any = neq v17, v2 {pure,throws}
    br_if v19, bb10, bb9
  bb9:
    v20: int = const 0
    br bb11(v20)
  bb10:
    v21: any = narrow.nonnull v17 <non-null>
    v22: int = convert.int v21
    br bb11(v22)
  bb11(v23: int):
    v24: int = add v18, v23 {pure}
    ret v24
```

## Self tail call and non-lowered top-level marker

```babble
def factTail = fn (n: int, acc: int): int -> (
    if n <= 1 then (acc) else (recurse(n - 1, acc * n))
)
assert(factTail(5, 1) == 120)
```

```hir
;; hir -O0 fn=factTail
fn factTail (n:int, acc:int) -> int {nothrow}
  (if (<=:bool var(n) 1:int) (block var(acc)) (block (tail-call factTail (-:int var(n) 1:int) (*:int var(acc) var(n)))))
```

```hir
;; hir -O1 fn=factTail
fn factTail (n:int, acc:int) -> int {nothrow}
  (if (<=:bool var(n) 1:int) var(acc) (tail-call factTail (-:int var(n) 1:int) (*:int var(acc) var(n))))
```

```mir
;; mir -O2 fn=factTail stage=before
fn factTail(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 1
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = mul v1, v0 {pure}
    tail_call factTail(v5, v6)
  bb2:
    br bb3
  bb3:
    ret v1
```

```mir
;; mir -O2 fn=factTail
fn factTail(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 1
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = sub v0, v2 {pure}
    v5: int = mul v1, v0 {pure}
    tail_call factTail(v4, v5)
  bb2:
    ret v1
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```
