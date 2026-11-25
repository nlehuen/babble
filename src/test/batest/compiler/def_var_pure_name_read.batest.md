# BIP034-P3 pure name_read, exception-guarded CSE, and immutable class fields

## Header defs after pure static block and Java import lower to pure name_read

```babble
def jl = import("java.lang")
def Vec2 = record (x: double, y: double) () static (
    def zero = this(0.0, 0.0)
)
def origin = Vec2.zero
def scale = 2.0
var bias = 1.0
def makeScaled = fn volatile (a: double): Vec2 -> (
    Vec2(a * scale + bias + origin.x, jl.Math.abs(a))
)
assert(makeScaled(-3.0).x == -5.0 and makeScaled(-3.0).y == 3.0)
```

```hir
;; hir -O0,-O1 fn=makeScaled
fn makeScaled (a:double) -> obj {volatile nothrow unique}
  (record-new Vec2 (+:double (+:double (*:double var(a) name(scale)) name(bias)) (get-this-field x)) (jcall Math.abs var(a)))
```

```mir
;; mir -O2 fn=makeScaled stage=before
fn makeScaled(v0: double) -> any:
  bb0:
    v1: any = name_read Vec2 {pure}
    v2: any = name_read scale {pure}
    v3: double = convert.double v2
    v4: double = mul v0, v3 {pure}
    v5: any = name_read bias {read:shared}
    v6: double = convert.double v5
    v7: double = add v4, v6 {pure}
    v8: any = name_read origin {pure}
    v9: double = load_field v8.x {read:frozen,throws}
    v10: double = add v7, v9 {pure}
    v11: any = name_read jl {pure}
    v12: any = opaque.DynamicSelect(v11.Math) {impure}
    v13: double = call_java v12.abs(v0) {pure}
    v14: any = record_new Vec2[v1](v10, v13) {pure}
    ret v14
```

```mir
;; mir -O2 fn=makeScaled
fn makeScaled(v0: double) -> any:
  bb0:
    v1: any = name_read Vec2 {pure}
    v2: any = name_read scale {pure}
    v3: double = convert.double v2
    v4: double = mul v0, v3 {pure}
    v5: any = name_read bias {read:shared}
    v6: double = convert.double v5
    v7: double = add v4, v6 {pure}
    v8: any = name_read origin {pure}
    v9: double = load_field v8.x {read:frozen,throws}
    v10: double = add v7, v9 {pure}
    v11: any = name_read jl {pure}
    v12: any = opaque.DynamicSelect(v11.Math) {impure}
    v13: double = call_java v12.abs(v0) {pure}
    v14: any = record_new Vec2[v1](v10, v13) {pure}
    ret v14
```

## Pure name_read CSEs across io calls and hoists out of io loops

```babble
def Box = record (v: int) ()
var calls = 0
def touch = fn io (b: Box): int -> ( calls += 1 ; b.v )
def buildMany = fn io (n: int): int -> (
    var acc = 0 ; var i = 0
    while i < n then (
        acc += touch(Box(i)) + touch(Box(i + 1))
        i += 1
    )
    acc
)
assert(buildMany(3) == 9 and calls == 6)
```

```hir
;; hir -O0 fn=buildMany
fn buildMany (n:int) -> int {io}
  (def acc 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (call-fn touch (record-new Box var(i))) (call-fn touch (record-new Box (+:int var(i) 1:int)))))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=buildMany
fn buildMany (n:int) -> int {io}
  (def acc 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (call-fn touch$unboxed var(i)) (call-fn touch$unboxed (+:int var(i) 1:int))))
      (set i += 1:int)))
  var(acc)
```

```mir
;; mir -O2 fn=buildMany stage=before
fn buildMany(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v2, v1)
  bb1(v4: any, v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = name_read touch {pure}
    v9: int = call touch$unboxed(v5) {impure}
    v10: any = name_read touch {pure}
    v11: int = const 1
    v12: int = add v5, v11 {pure}
    v13: int = call touch$unboxed(v12) {impure}
    v14: int = add v9, v13 {pure}
    v15: int = add v6, v14 {pure}
    v16: int = const 1
    v17: int = add v5, v16 {pure}
    v18: any = convert.any v17
    br bb1(v18, v17, v15)
```

```mir
;; mir -O2 fn=buildMany
fn buildMany(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: any = name_read touch {pure}
    br bb1(v1, v1)
  bb1(v3: int, v4: int):
    v5: bool = lt v3, v0 {pure}
    br_if v5, bb3, bb2
  bb2:
    ret v4
  bb3:
    v6: int = call touch$unboxed(v3) {impure}
    v7: int = const 1
    v8: int = add v3, v7 {pure}
    v9: int = call touch$unboxed(v8) {impure}
    v10: int = add v6, v9 {pure}
    v11: int = add v4, v10 {pure}
    br bb1(v8, v11)
```

## Late def after arbitrary top-level call is pure-may-throw with dominated CSE and try-catch guard

```babble
def s = fn io (): int -> ( 1 )
def _i = s()
def lv = 10
def sumLate = fn (): int -> ( lv + lv )
def guardedLate = fn (f: bool): int -> (
    var o = 0
    try ( if f then ( o = lv ) ; if not f then ( throw exception("x") ) ; o += lv ) catch e then ( o = -1 )
    o
)
assert(sumLate() == 20 and guardedLate(true) == 20 and guardedLate(false) == -1)
```

```hir
;; hir -O0,-O1 fn=sumLate
fn sumLate () -> int {nothrow unique}
  (+:int name(lv) name(lv))
```

```mir
;; mir -O2 fn=sumLate stage=before
fn sumLate() -> int:
  bb0:
    v0: any = name_read lv {pure,throws}
    v1: int = convert.int v0
    v2: any = name_read lv {pure,throws}
    v3: int = convert.int v2
    v4: int = add v1, v3 {pure}
    ret v4
```

```mir
;; mir -O2 fn=sumLate
fn sumLate() -> int:
  bb0:
    v0: any = name_read lv {pure,throws}
    v1: int = convert.int v0
    v2: int = add v1, v1 {pure}
    ret v2
```

```hir
;; hir -O0 fn=guardedLate
fn guardedLate (f:bool) -> int {nothrow unique}
  (def o 0:int)
  (try
    (block
      (if var(f) (block (set o name(lv))))
      (if (not var(f)) (block (throw (jnew BabbleException "x"))))
      (set o += name(lv)))
    (catch e
      (set o -1:int)))
  var(o)
```

```hir
;; hir -O1 fn=guardedLate
fn guardedLate (f:bool) -> int {nothrow unique}
  (def o 0:int)
  (try
    (block
      (if var(f) (set o name(lv)))
      (if (not var(f)) (throw (jnew BabbleException "x")))
      (set o += name(lv)))
    (catch e
      (set o -1:int)))
  var(o)
```

```mir
;; mir -O2 fn=guardedLate stage=before
fn guardedLate(v0: bool) -> int:
  bb0:
    v1: int = const 0
    slot_init o, v1 {write:shared}
    br bb1
  bb1 [unwind: ThrownException -> bb9, Exception -> bb8]:
    br_if v0, bb3, bb2
  bb2 [unwind: ThrownException -> bb9, Exception -> bb8]:
    v2: any = const null
    br bb4(v2)
  bb3 [unwind: ThrownException -> bb9, Exception -> bb8]:
    v3: any = name_read lv {pure,throws}
    v4: int = convert.int v3
    slot_write o, v4 {write:shared}
    v5: any = convert.any v4
    br bb4(v5)
  bb4(v6: any) [unwind: ThrownException -> bb9, Exception -> bb8]:
    v7: bool = not v0 {pure}
    br_if v7, bb7, bb5
  bb5 [unwind: ThrownException -> bb9, Exception -> bb8]:
    v8: any = const null
    br bb6
  bb6 [unwind: ThrownException -> bb9, Exception -> bb8]:
    v9: int = slot_read o {read:shared}
    v10: any = name_read lv {pure,throws}
    v11: int = convert.int v10
    v12: int = add v9, v11 {pure}
    slot_write o, v12 {write:shared}
    br bb11(v12)
  bb7 [unwind: ThrownException -> bb9, Exception -> bb8]:
    v13: any = const "x"
    v14: any = new_java BabbleException(v13) {pure!}
    rethrow v14
  bb8(v15: any) [handler]:
    br bb10(v15)
  bb9(v16: any) [handler]:
    v17: any = unwrap_thrown v16
    br bb10(v17)
  bb10(v18: any):
    v19: int = const -1
    slot_write o, v19 {write:shared}
    br bb11(v19)
  bb11(v20: int):
    v21: int = slot_read o {read:shared}
    ret v21
```

```mir
;; mir -O2 fn=guardedLate
fn guardedLate(v0: bool) -> int:
  bb0:
    v1: int = const 0
    slot_init o, v1 {write:shared}
    br bb1
  bb1 [unwind: ThrownException -> bb7, Exception -> bb6]:
    br_if v0, bb2, bb3
  bb2 [unwind: ThrownException -> bb7, Exception -> bb6]:
    v2: any = name_read lv {pure,throws}
    v3: int = convert.int v2
    slot_write o, v3 {write:shared}
    br bb3
  bb3 [unwind: ThrownException -> bb7, Exception -> bb6]:
    v4: bool = not v0 {pure}
    br_if v4, bb5, bb4
  bb4 [unwind: ThrownException -> bb7, Exception -> bb6]:
    v5: int = slot_read o {read:shared}
    v6: any = name_read lv {pure,throws}
    v7: int = convert.int v6
    v8: int = add v5, v7 {pure}
    slot_write o, v8 {write:shared}
    br bb9
  bb5 [unwind: ThrownException -> bb7, Exception -> bb6]:
    v9: any = const "x"
    v10: any = new_java BabbleException(v9) {pure!}
    rethrow v10
  bb6(v11: any) [handler]:
    br bb8
  bb7(v12: any) [handler]:
    br bb8
  bb8:
    v13: int = const -1
    slot_write o, v13 {write:shared}
    br bb9
  bb9:
    v14: int = slot_read o {read:shared}
    ret v14
```

## Premature read of late def during active static block still throws TDZ error

```babble
def boom = fn (): int -> ( afterStatic )
def Trigger = class () () static (
    def ran = boom()
)
def afterStatic = 42
```

```error
Name used before definition : afterStatic
```

```hir
;; hir -O0,-O1 fn=boom
fn boom () -> int {nothrow unique}
  name(afterStatic)
```

```mir
;; mir -O2 fn=boom stage=before,final
fn boom() -> int:
  bb0:
    v0: any = name_read afterStatic {pure,throws}
    v1: int = convert.int v0
    ret v1
```

## Regular class def header field is read:frozen and hoisted while var field stays read:shared

```babble
def Counter = class (def step: int, var bonus: int) (
    def run = fn mut (n: int): int -> (
        var i = 0
        var acc = 0
        while i < n then (
            acc += this.step * 2 + this.bonus
            this.bonus += 1
            i += 1
        )
        acc
    )
)
def c = Counter(5, 1)
assert(c.run(3) == 36 and c.bonus == 4)
```

```hir
;; hir -O0 fn=Counter.run
fn Counter.run (n:int) -> int {nothrow mut unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (*:int (get-this-field step) 2:int) (get-this-field bonus)))
      (set-this-field bonus += 1:int)
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=Counter.run
fn Counter.run (n:int) -> int {nothrow mut unique}
  (def i 0:int)
  (def acc 0:int)
  (block
    (def $licm0 (*:int (get-this-field step) 2:int))
    (while (<:bool var(i) var(n))
      (block
        (set acc += (+:int var($licm0) (get-this-field bonus)))
        (set-this-field bonus += 1:int)
        (set i += 1:int))))
  var(acc)
```

```mir
;; mir -O2 fn=Counter.run stage=before
fn Counter.run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = slot_read this {pure}
    v4: int = load_field v3.step {read:frozen}
    v5: int = const 2
    v6: int = mul v4, v5 {pure}
    v7: any = const null
    br bb1(v7, v1, v2)
  bb1(v8: any, v9: int, v10: int):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: any = slot_read this {pure}
    v13: int = load_field v12.bonus {read:shared,throws}
    v14: int = add v6, v13 {pure}
    v15: int = add v10, v14 {pure}
    v16: any = slot_read this {pure}
    v17: int = load_field v16.bonus {read:shared,throws}
    v18: int = const 1
    v19: int = add v17, v18 {pure}
    store_field v16.bonus, v19 {write:shared,throws}
    v20: int = const 1
    v21: int = add v9, v20 {pure}
    v22: any = convert.any v21
    br bb1(v22, v21, v15)
```

```mir
;; mir -O2 fn=Counter.run
fn Counter.run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: any = slot_read this {pure}
    v3: int = load_field v2.step {read:frozen}
    v4: int = const 2
    v5: int = mul v3, v4 {pure}
    br bb1(v1, v1)
  bb1(v6: int, v7: int):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: int = load_field v2.bonus {read:shared,throws}
    v10: int = add v5, v9 {pure}
    v11: int = add v7, v10 {pure}
    v12: int = const 1
    v13: int = add v9, v12 {pure}
    store_field v2.bonus, v13 {write:shared,throws}
    v14: int = add v6, v12 {pure}
    br bb1(v14, v11)
```

## Self-class definite initialization makes methods of late classes read self purely

```babble
def log = java.lang.StringBuilder()
def kick = fn io (): int -> ( log.append("k") ; 1 )
def _ran = kick()
def Node = class (def v: int) (
    def next = fn (): Node -> ( Node(this.v + 1) )
)
assert(Node(4).next().v == 5)
```

```hir
;; hir -O0,-O1 fn=Node.next
fn Node.next () -> obj {nothrow unique}
  (call name(Node) (+:int (get-this-field v) 1:int))
```

```mir
;; mir -O2 fn=Node.next stage=before,final
fn Node.next() -> any:
  bb0:
    v0: any = name_read Node {pure}
    v1: any = slot_read this {pure}
    v2: int = load_field v1.v {read:frozen}
    v3: int = const 1
    v4: int = add v2, v3 {pure}
    v5: any = opaque.DynamicCall(v0, v4) {impure}
    ret v5
```
