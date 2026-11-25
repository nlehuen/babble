# SSA MIR Primitive Array Loops, Custom Iterators, and Uninitialized Slots (BIP014-I3)

Tests `MirEmitter` lowering of primitive/reference array `for` loops (`BALOAD`/`SALOAD`/`LALOAD`/`FALOAD`/`CALOAD`/`AALOAD`),
custom Babble `Iterable` and `Iterator` `for` loops, parallel-copy swap cycles, and uninitialized primitive/reference locals.

## Narrow numeric array for-loops (byte, short, long, float)

```babble
def sumNum = fn (bs: array of byte, ss: array of short, ls: array of long, fs: array of float): double -> (
    var r = 0.0
    for b in bs then (r += b)
    for s in ss then (r += s)
    for l in ls then (r += l)
    for f in fs then (r += f)
    r
)
assert(sumNum((array of byte).of(byte(1)), (array of short).of(short(2)), (array of long).of(3L), (array of float).of(1.5f)) == 7.5)
```

```hir
;; hir -O0 fn=sumNum
fn sumNum (bs:obj, ss:obj, ls:obj, fs:obj) -> double {nothrow unique}
  (def r 0.0:double)
  (for b in var(bs)
    (block
      (set r += var(b))))
  (for s in var(ss)
    (block
      (set r += var(s))))
  (for l in var(ls)
    (block
      (set r += var(l))))
  (for f in var(fs)
    (block
      (set r += var(f))))
  var(r)
```

```hir
;; hir -O1 fn=sumNum
fn sumNum (bs:obj, ss:obj, ls:obj, fs:obj) -> double {nothrow unique}
  (def r 0.0:double)
  (for b in var(bs)
    (block
      (set r += var(b))))
  (for s in var(ss)
    (block
      (set r += var(s))))
  (for l in var(ls)
    (block
      (set r += var(l))))
  (for f in var(fs)
    (block
      (set r += var(f))))
  var(r)
```

```mir
;; mir -O2 fn=sumNum stage=before
fn sumNum(v0: any, v1: any, v2: any, v3: any) -> double:
  bb0:
    v4: double = const 0.0
    v5: any = for_init.array v0
    v6: any = const null
    v7: int = array_len v5 {read:frozen}
    v8: int = const 0
    br bb1(v6, v8, v4)
  bb1(v9: any, v10: int, v11: double):
    v12: bool = for_has_next v5, v10, v7
    br_if v12, bb15, bb2
  bb2:
    v13: any = for_init.array v1
    v14: any = const null
    v15: int = array_len v13 {read:frozen}
    v16: int = const 0
    br bb3(v14, v16, v11)
  bb3(v17: any, v18: int, v19: double):
    v20: bool = for_has_next v13, v18, v15
    br_if v20, bb13, bb4
  bb4:
    v21: any = for_init.array v2
    v22: any = const null
    v23: int = array_len v21 {read:frozen}
    v24: int = const 0
    br bb5(v22, v24, v19)
  bb5(v25: any, v26: int, v27: double):
    v28: bool = for_has_next v21, v26, v23
    br_if v28, bb11, bb6
  bb6:
    v29: any = for_init.array v3
    v30: any = const null
    v31: int = array_len v29 {read:frozen}
    v32: int = const 0
    br bb7(v30, v32, v27)
  bb7(v33: any, v34: int, v35: double):
    v36: bool = for_has_next v29, v34, v31
    br_if v36, bb9, bb8
  bb8:
    ret v35
  bb9:
    v37: float = for_next v29, v34
    v38: any = convert.any v35
    v39: any = convert.any v37
    v40: any = add v38, v39 {impure}
    v41: double = convert.double v40
    v42: any = convert.any v41
    br bb10
  bb10:
    v43: int = const 1
    v44: int = add v34, v43 {pure}
    br bb7(v42, v44, v41)
  bb11:
    v45: long = for_next v21, v26
    v46: any = convert.any v27
    v47: any = convert.any v45
    v48: any = add v46, v47 {impure}
    v49: double = convert.double v48
    v50: any = convert.any v49
    br bb12
  bb12:
    v51: int = const 1
    v52: int = add v26, v51 {pure}
    br bb5(v50, v52, v49)
  bb13:
    v53: short = for_next v13, v18
    v54: any = convert.any v19
    v55: any = convert.any v53
    v56: any = add v54, v55 {impure}
    v57: double = convert.double v56
    v58: any = convert.any v57
    br bb14
  bb14:
    v59: int = const 1
    v60: int = add v18, v59 {pure}
    br bb3(v58, v60, v57)
  bb15:
    v61: byte = for_next v5, v10
    v62: any = convert.any v11
    v63: any = convert.any v61
    v64: any = add v62, v63 {impure}
    v65: double = convert.double v64
    v66: any = convert.any v65
    br bb16
  bb16:
    v67: int = const 1
    v68: int = add v10, v67 {pure}
    br bb1(v66, v68, v65)
```

```mir
;; mir -O2 fn=sumNum
fn sumNum(v0: any, v1: any, v2: any, v3: any) -> double:
  bb0:
    v4: double = const 0.0
    v5: any = for_init.array v0
    v6: int = array_len v5 {read:frozen}
    v7: int = const 0
    br bb1(v7, v4)
  bb1(v8: int, v9: double):
    v10: bool = for_has_next v5, v8, v6
    br_if v10, bb15, bb2
  bb2:
    v11: any = for_init.array v1
    v12: int = array_len v11 {read:frozen}
    br bb3(v7, v9)
  bb3(v13: int, v14: double):
    v15: bool = for_has_next v11, v13, v12
    br_if v15, bb13, bb4
  bb4:
    v16: any = for_init.array v2
    v17: int = array_len v16 {read:frozen}
    br bb5(v7, v14)
  bb5(v18: int, v19: double):
    v20: bool = for_has_next v16, v18, v17
    br_if v20, bb11, bb6
  bb6:
    v21: any = for_init.array v3
    v22: int = array_len v21 {read:frozen}
    br bb7(v7, v19)
  bb7(v23: int, v24: double):
    v25: bool = for_has_next v21, v23, v22
    br_if v25, bb9, bb8
  bb8:
    ret v24
  bb9:
    v26: float = for_next v21, v23
    v27: any = convert.any v24
    v28: any = convert.any v26
    v29: any = add v27, v28 {impure}
    v30: double = convert.double v29
    br bb10
  bb10:
    v31: int = const 1
    v32: int = add v23, v31 {pure}
    br bb7(v32, v30)
  bb11:
    v33: long = for_next v16, v18
    v34: any = convert.any v19
    v35: any = convert.any v33
    v36: any = add v34, v35 {impure}
    v37: double = convert.double v36
    br bb12
  bb12:
    v38: int = const 1
    v39: int = add v18, v38 {pure}
    br bb5(v39, v37)
  bb13:
    v40: short = for_next v11, v13
    v41: any = convert.any v14
    v42: any = convert.any v40
    v43: any = add v41, v42 {impure}
    v44: double = convert.double v43
    br bb14
  bb14:
    v45: int = const 1
    v46: int = add v13, v45 {pure}
    br bb3(v46, v44)
  bb15:
    v47: byte = for_next v5, v8
    v48: any = convert.any v9
    v49: any = convert.any v47
    v50: any = add v48, v49 {impure}
    v51: double = convert.double v50
    br bb16
  bb16:
    v52: int = const 1
    v53: int = add v8, v52 {pure}
    br bb1(v53, v51)
```

## Char, bool, and reference array for-loops

```babble
def sumRef = fn (cs: array of char, ps: array of bool, ts: array of string): int -> (
    var r = 0
    for c in cs then (if c == 'a' then (r += 1))
    for p in ps then (if p then (r += 2))
    for t in ts then (r += t.length())
    r
)
assert(sumRef((array of char).of('a'), (array of bool).of(true), (array of string).of("hi")) == 5)
```

```hir
;; hir -O0 fn=sumRef
fn sumRef (cs:obj, ps:obj, ts:obj) -> int {nothrow unique}
  (def r 0:int)
  (for c in var(cs)
    (block
      (if (==:bool var(c) 'a') (block (set r += 1:int)))))
  (for p in var(ps)
    (block
      (if var(p) (block (set r += 2:int)))))
  (for t in var(ts)
    (block
      (set r += (jcall String.length var(t)))))
  var(r)
```

```hir
;; hir -O1 fn=sumRef
fn sumRef (cs:obj, ps:obj, ts:obj) -> int {nothrow unique}
  (def r 0:int)
  (for c in var(cs)
    (block
      (if (==:bool var(c) 'a') (set r += 1:int))))
  (for p in var(ps)
    (block
      (if var(p) (set r += 2:int))))
  (for t in var(ts)
    (block
      (set r += (jcall String.length var(t)))))
  var(r)
```

```mir
;; mir -O2 fn=sumRef stage=before
fn sumRef(v0: any, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: any = for_init.array v0
    v5: any = const null
    v6: int = array_len v4 {read:frozen}
    v7: int = const 0
    br bb1(v5, v7, v3)
  bb1(v8: any, v9: int, v10: int):
    v11: bool = for_has_next v4, v9, v6
    br_if v11, bb14, bb2
  bb2:
    v12: any = for_init.array v1
    v13: any = const null
    v14: int = array_len v12 {read:frozen}
    v15: int = const 0
    br bb3(v13, v15, v10)
  bb3(v16: any, v17: int, v18: int):
    v19: bool = for_has_next v12, v17, v14
    br_if v19, bb9, bb4
  bb4:
    v20: any = for_init.array v2
    v21: any = const null
    v22: int = array_len v20 {read:frozen}
    v23: int = const 0
    br bb5(v21, v23, v18)
  bb5(v24: any, v25: int, v26: int):
    v27: bool = for_has_next v20, v25, v22
    br_if v27, bb7, bb6
  bb6:
    ret v26
  bb7:
    v28: any = for_next v20, v25
    v29: any = checkcast v28 <string|string|>
    v30: int = call_java v29.length() {pure,throws}
    v31: int = add v26, v30 {pure}
    v32: any = convert.any v31
    br bb8
  bb8:
    v33: int = const 1
    v34: int = add v25, v33 {pure}
    br bb5(v32, v34, v31)
  bb9:
    v35: bool = for_next v12, v17
    br_if v35, bb11, bb10
  bb10:
    v36: any = const null
    br bb12(v36, v18)
  bb11:
    v37: int = const 2
    v38: int = add v18, v37 {pure}
    v39: any = convert.any v38
    br bb12(v39, v38)
  bb12(v40: any, v41: int):
    br bb13
  bb13:
    v42: int = const 1
    v43: int = add v17, v42 {pure}
    br bb3(v40, v43, v41)
  bb14:
    v44: char = for_next v4, v9
    v45: char = const 'a'
    v46: bool = eq v44, v45 {pure}
    br_if v46, bb16, bb15
  bb15:
    v47: any = const null
    br bb17(v47, v10)
  bb16:
    v48: int = const 1
    v49: int = add v10, v48 {pure}
    v50: any = convert.any v49
    br bb17(v50, v49)
  bb17(v51: any, v52: int):
    br bb18
  bb18:
    v53: int = const 1
    v54: int = add v9, v53 {pure}
    br bb1(v51, v54, v52)
```

```mir
;; mir -O2 fn=sumRef
fn sumRef(v0: any, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: any = for_init.array v0
    v5: int = array_len v4 {read:frozen}
    br bb1(v3, v3)
  bb1(v6: int, v7: int):
    v8: bool = for_has_next v4, v6, v5
    br_if v8, bb14, bb2
  bb2:
    v9: any = for_init.array v1
    v10: int = array_len v9 {read:frozen}
    br bb3(v3, v7)
  bb3(v11: int, v12: int):
    v13: bool = for_has_next v9, v11, v10
    br_if v13, bb9, bb4
  bb4:
    v14: any = for_init.array v2
    v15: int = array_len v14 {read:frozen}
    br bb5(v3, v12)
  bb5(v16: int, v17: int):
    v18: bool = for_has_next v14, v16, v15
    br_if v18, bb7, bb6
  bb6:
    ret v17
  bb7:
    v19: any = for_next v14, v16
    v20: any = checkcast v19 <string|string|>
    v21: int = call_java v20.length() {pure,throws}
    v22: int = add v17, v21 {pure}
    br bb8
  bb8:
    v23: int = const 1
    v24: int = add v16, v23 {pure}
    br bb5(v24, v22)
  bb9:
    v25: bool = for_next v9, v11
    br_if v25, bb11, bb10
  bb10:
    br bb12(v12)
  bb11:
    v26: int = const 2
    v27: int = add v12, v26 {pure}
    br bb12(v27)
  bb12(v28: int):
    br bb13
  bb13:
    v29: int = const 1
    v30: int = add v11, v29 {pure}
    br bb3(v30, v28)
  bb14:
    v31: char = for_next v4, v6
    v32: char = const 'a'
    v33: bool = eq v31, v32 {pure}
    br_if v33, bb16, bb15
  bb15:
    br bb17(v7)
  bb16:
    v34: int = const 1
    v35: int = add v7, v34 {pure}
    br bb17(v35)
  bb17(v36: int):
    br bb18
  bb18:
    v37: int = const 1
    v38: int = add v6, v37 {pure}
    br bb1(v38, v36)
```

## Babble custom Iterable and Iterator for-loops

```babble
def Cnt = class (k: int) (
  def n = k ; var i = 0
  def hasNext = fn (): bool -> (this.i < this.n)
  def next = fn mut (): int -> (def v = this.i ; this.i += 1 ; v)
)
def Rng = class (k: int) (def n = k ; def iterator = fn (): Cnt -> (Cnt(this.n)))
def sumCustom = fn (): int -> (
  var s = 0
  for x in Rng(3) then (s += x)
  for y in Cnt(3) then (s += y)
  s
)
assert(sumCustom() == 6)
```

```hir
;; hir -O0 fn=sumCustom
fn sumCustom () -> int {pure unique}
  (def s 0:int)
  (for x in (call name(Rng) 3:int)
    (block
      (set s += var(x))))
  (for y in (call name(Cnt) 3:int)
    (block
      (set s += var(y))))
  var(s)
```

```hir
;; hir -O1 fn=sumCustom
fn sumCustom () -> int {pure unique}
  (def s 0:int)
  (for x in (call name(Rng) 3:int)
    (block
      (set s += var(x))))
  (for y in (call name(Cnt) 3:int)
    (block
      (set s += var(y))))
  var(s)
```

```mir
;; mir -O2 fn=sumCustom stage=before
fn sumCustom() -> int:
  bb0:
    v0: int = const 0
    v1: any = name_read Rng {pure}
    v2: int = const 3
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: any = for_init.babble_iterable v3
    v5: any = const null
    br bb1(v5, v0)
  bb1(v6: any, v7: int):
    v8: bool = for_has_next v4
    br_if v8, bb6, bb2
  bb2:
    v9: any = name_read Cnt {pure}
    v10: int = const 3
    v11: any = opaque.DynamicCall(v9, v10) {impure}
    v12: any = for_init.babble_iterator v11
    v13: any = const null
    br bb3(v13, v7)
  bb3(v14: any, v15: int):
    v16: bool = for_has_next v12
    br_if v16, bb5, bb4
  bb4:
    ret v15
  bb5:
    v17: int = for_next v12
    v18: int = add v15, v17 {pure}
    v19: any = convert.any v18
    br bb3(v19, v18)
  bb6:
    v20: int = for_next v4
    v21: int = add v7, v20 {pure}
    v22: any = convert.any v21
    br bb1(v22, v21)
```

```mir
;; mir -O2 fn=sumCustom
fn sumCustom() -> int:
  bb0:
    v0: int = const 0
    v1: any = name_read Rng {pure}
    v2: int = const 3
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: any = for_init.babble_iterable v3
    br bb1(v0)
  bb1(v5: int):
    v6: bool = for_has_next v4
    br_if v6, bb6, bb2
  bb2:
    v7: any = name_read Cnt {pure}
    v8: any = opaque.DynamicCall(v7, v2) {impure}
    v9: any = for_init.babble_iterator v8
    br bb3(v5)
  bb3(v10: int):
    v11: bool = for_has_next v9
    br_if v11, bb5, bb4
  bb4:
    ret v10
  bb5:
    v12: int = for_next v9
    v13: int = add v10, v12 {pure}
    br bb3(v13)
  bb6:
    v14: int = for_next v4
    v15: int = add v5, v14 {pure}
    br bb1(v15)
```

## Uninitialized primitive and reference locals with swap-cycle loop

```babble
def initAndSwap = fn (flag: bool): int -> (
    def r = if flag then (0) else (u + t.length())
    def u: int = 2
    def t: string = "xyz"
    var a = u
    var b = t.length()
    var k = 0
    while k < 3 then (def tmp = a ; a = b ; b = tmp ; k += 1)
    a * 10 + b + r
)
assert(initAndSwap(true) == 32)
```

```hir
;; hir -O0 fn=initAndSwap
fn initAndSwap (flag:bool) -> int {nothrow unique}
  (def r (if var(flag) (block 0:int) (block (+:int var(u) (jcall String.length var(t))))))
  (def u 2:int)
  (def t "xyz")
  (def a var(u))
  (def b (jcall String.length var(t)))
  (def k 0:int)
  (while (<:bool var(k) 3:int)
    (block
      (def tmp var(a))
      (set a var(b))
      (set b var(tmp))
      (set k += 1:int)))
  (+:int (+:int (*:int var(a) 10:int) var(b)) var(r))
```

```hir
;; hir -O1 fn=initAndSwap
fn initAndSwap (flag:bool) -> int {nothrow unique}
  (def r (if var(flag) 0:int (+:int var(u) (jcall String.length var(t)))))
  (def u 2:int)
  (def t "xyz")
  (def a var(u))
  (def b (jcall String.length var(t)))
  (def k 0:int)
  (while (<:bool var(k) 3:int)
    (block
      (def tmp var(a))
      (set a var(b))
      (set b var(tmp))
      (set k += 1:int)))
  (+:int (+:int (*:int var(a) 10:int) var(b)) var(r))
```

```mir
;; mir -O2 fn=initAndSwap stage=before
fn initAndSwap(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = slot_read u {read:shared,throws}
    v2: any = slot_read t {read:shared,throws}
    v3: int = call_java v2.length() {pure,throws}
    v4: int = add v1, v3 {pure}
    br bb3(v4)
  bb2:
    v5: int = const 0
    br bb3(v5)
  bb3(v6: int):
    v7: int = const 2
    slot_init u, v7 {write:shared,throws}
    v8: any = const "xyz"
    slot_init t, v8 {write:shared,throws}
    v9: int = slot_read u {read:shared,throws}
    v10: any = slot_read t {read:shared,throws}
    v11: int = call_java v10.length() {pure,throws}
    v12: int = const 0
    v13: any = const null
    br bb4(v13, v12, v9, v11)
  bb4(v14: any, v15: int, v16: int, v17: int):
    v18: int = const 3
    v19: bool = lt v15, v18 {pure}
    br_if v19, bb6, bb5
  bb5:
    v20: int = const 10
    v21: int = mul v16, v20 {pure}
    v22: int = add v21, v17 {pure}
    v23: int = add v22, v6 {pure}
    ret v23
  bb6:
    v24: int = const 1
    v25: int = add v15, v24 {pure}
    v26: any = convert.any v25
    br bb4(v26, v25, v17, v16)
```

```mir
;; mir -O2 fn=initAndSwap
fn initAndSwap(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = slot_read u {read:shared,throws}
    v2: any = slot_read t {read:shared,throws}
    v3: int = call_java v2.length() {pure,throws}
    v4: int = add v1, v3 {pure}
    br bb3(v4)
  bb2:
    v5: int = const 0
    br bb3(v5)
  bb3(v6: int):
    v7: int = const 2
    slot_init u, v7 {write:shared,throws}
    v8: any = const "xyz"
    slot_init t, v8 {write:shared,throws}
    v9: int = slot_read u {read:shared,throws}
    v10: any = slot_read t {read:shared,throws}
    v11: int = call_java v10.length() {pure,throws}
    v12: int = const 0
    br bb4(v12, v9, v11)
  bb4(v13: int, v14: int, v15: int):
    v16: int = const 3
    v17: bool = lt v13, v16 {pure}
    br_if v17, bb6, bb5
  bb5:
    v18: int = const 10
    v19: int = mul v14, v18 {pure}
    v20: int = add v19, v15 {pure}
    v21: int = add v20, v6 {pure}
    ret v21
  bb6:
    v22: int = const 1
    v23: int = add v13, v22 {pure}
    br bb4(v23, v15, v14)
```
