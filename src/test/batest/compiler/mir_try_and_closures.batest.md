# SSA MIR Try/Catch/Finally and Closure Cell Lowering (BIP014-I3)

Tests `try`/`catch`/`finally`/`throw` CFG lowering with pinned locals and `addSuppressed`,
plus loop-local `Cell` capture preservation across MIR passes.

## Try/catch/finally lowering with Babble record, Java exception, and pinned local

```babble
def E = record (m: string) ()
def handle = fn (k: int): int -> (
    var o = 0
    try (
        o = 1
        if k == 1 then (throw E("bad"))
        if k == 2 then (throw java.lang.Error("x"))
        o = 2
    ) catch e: E then (o = e.m.length())
      catch e: java.lang.Error then (o = 20)
      finally (o += 100)
    o
)
assert(handle(0) == 102 and handle(1) == 103 and handle(2) == 120)
```

```hir
;; hir -O0 fn=handle
fn handle (k:int) -> int {pure unique}
  (def o 0:int)
  (try
    (block
      (set o 1:int)
      (if (==:bool var(k) 1:int) (block (throw (record-new E "bad"))))
      (if (==:bool var(k) 2:int) (block (throw (jnew Error "x"))))
      (set o 2:int))
    (catch e#1:babble|E
      (set o (jcall String.length (get-this-field m))))
    (catch e#2:java|java.lang.Error|java.lang.Error
      (set o 20:int))
    (finally
      (set o += 100:int)))
  var(o)
```

```hir
;; hir -O1 fn=handle
fn handle (k:int) -> int {pure unique}
  (def o 0:int)
  (try
    (block
      (set o 1:int)
      (if (==:bool var(k) 1:int) (throw (record-new E "bad")))
      (if (==:bool var(k) 2:int) (throw (jnew Error "x")))
      (set o 2:int))
    (catch e#1:babble|E
      (set o (jcall String.length (get-this-field m))))
    (catch e#2:java|java.lang.Error|java.lang.Error
      (set o 20:int))
    (finally
      (set o += 100:int)))
  var(o)
```

```mir
;; mir -O2 fn=handle stage=before
fn handle(v0: int) -> int:
  bb0:
    v1: int = const 0
    slot_init o, v1 {write:shared}
    br bb1
  bb1 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v2: int = const 1
    slot_write o, v2 {write:shared}
    v3: int = const 1
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb8, bb2
  bb2 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v5: any = const null
    br bb3
  bb3 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v6: int = const 2
    v7: bool = eq v0, v6 {pure}
    br_if v7, bb7, bb4
  bb4 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v8: any = const null
    br bb5
  bb5 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v9: int = const 2
    slot_write o, v9 {write:shared}
    br bb6
  bb6:
    v10: int = slot_read o {read:shared}
    v11: int = const 100
    v12: int = add v10, v11 {pure}
    slot_write o, v12 {write:shared}
    br bb18(v9)
  bb7 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v13: any = const "x"
    v14: any = new_java Error(v13) {pure!}
    rethrow v14
  bb8 [unwind: ThrownException -> bb12, Error -> bb9, * -> bb15]:
    v15: any = name_read E {pure}
    v16: any = const "bad"
    v17: any = record_new E[v15](v16) {pure}
    throw v17
  bb9(v18: any) [handler]:
    br bb10
  bb10 [unwind: * -> bb15]:
    v19: int = const 20
    slot_write o, v19 {write:shared}
    br bb11
  bb11:
    v20: int = slot_read o {read:shared}
    v21: int = const 100
    v22: int = add v20, v21 {pure}
    slot_write o, v22 {write:shared}
    br bb18(v19)
  bb12(v23: any) [handler]:
    v24: any = unwrap_thrown v23
    v25: bool = catch_match v24 <babble|E>
    br_if v25, bb14, bb13
  bb13:
    br bb16(v23)
  bb14 [unwind: * -> bb15]:
    v26: any = load_field v24.m {read:frozen}
    v27: int = call_java v26.length() {pure,throws}
    slot_write o, v27 {write:shared}
    br bb17
  bb15(v28: any) [handler]:
    br bb16(v28)
  bb16(v29: any):
    v30: int = slot_read o {read:shared}
    v31: int = const 100
    v32: int = add v30, v31 {pure}
    slot_write o, v32 {write:shared}
    rethrow v29
  bb17:
    v33: int = slot_read o {read:shared}
    v34: int = const 100
    v35: int = add v33, v34 {pure}
    slot_write o, v35 {write:shared}
    br bb18(v27)
  bb18(v36: int):
    v37: int = slot_read o {read:shared}
    ret v37
```

```mir
;; mir -O2 fn=handle
fn handle(v0: int) -> int:
  bb0:
    v1: int = const 0
    slot_init o, v1 {write:shared}
    br bb1
  bb1 [unwind: ThrownException -> bb10, Error -> bb7, * -> bb13]:
    v2: int = const 1
    slot_write o, v2 {write:shared}
    v3: bool = eq v0, v2 {pure}
    br_if v3, bb6, bb2
  bb2 [unwind: ThrownException -> bb10, Error -> bb7, * -> bb13]:
    v4: int = const 2
    v5: bool = eq v0, v4 {pure}
    br_if v5, bb5, bb3
  bb3 [unwind: ThrownException -> bb10, Error -> bb7, * -> bb13]:
    slot_write o, v4 {write:shared}
    br bb4
  bb4:
    v6: int = slot_read o {read:shared}
    v7: int = const 100
    v8: int = add v6, v7 {pure}
    slot_write o, v8 {write:shared}
    br bb16
  bb5 [unwind: ThrownException -> bb10, Error -> bb7, * -> bb13]:
    v9: any = const "x"
    v10: any = new_java Error(v9) {pure!}
    rethrow v10
  bb6 [unwind: ThrownException -> bb10, Error -> bb7, * -> bb13]:
    v11: any = name_read E {pure}
    v12: any = const "bad"
    v13: any = record_new E[v11](v12) {pure}
    throw v13
  bb7(v14: any) [handler]:
    br bb8
  bb8 [unwind: * -> bb13]:
    v15: int = const 20
    slot_write o, v15 {write:shared}
    br bb9
  bb9:
    v16: int = slot_read o {read:shared}
    v17: int = const 100
    v18: int = add v16, v17 {pure}
    slot_write o, v18 {write:shared}
    br bb16
  bb10(v19: any) [handler]:
    v20: any = unwrap_thrown v19
    v21: bool = catch_match v20 <babble|E>
    br_if v21, bb12, bb11
  bb11:
    br bb14(v19)
  bb12 [unwind: * -> bb13]:
    v22: any = load_field v20.m {read:frozen}
    v23: int = call_java v22.length() {pure,throws}
    slot_write o, v23 {write:shared}
    br bb15
  bb13(v24: any) [handler]:
    br bb14(v24)
  bb14(v25: any):
    v26: int = slot_read o {read:shared}
    v27: int = const 100
    v28: int = add v26, v27 {pure}
    slot_write o, v28 {write:shared}
    rethrow v25
  bb15:
    v29: int = slot_read o {read:shared}
    v30: int = const 100
    v31: int = add v29, v30 {pure}
    slot_write o, v31 {write:shared}
    br bb16
  bb16:
    v32: int = slot_read o {read:shared}
    ret v32
```

## Try-finally abnormal unwind with addSuppressed

```babble
def supTest = fn volatile (): int -> (
    var n = 0
    try (
        try (throw java.lang.RuntimeException("p"))
        finally (throw java.lang.IllegalArgumentException("s"))
    ) catch e: java.lang.RuntimeException then (
        n = e.getSuppressed().size()
    )
    n
)
assert(supTest() == 1)
```

```hir
;; hir -O0 fn=supTest
fn supTest () -> int {volatile unique}
  (def n 0:int)
  (try
    (block
      (try
        (block
          (throw (jnew RuntimeException "p")))
        (finally
          (throw (jnew IllegalArgumentException "s")))))
    (catch e:java|java.lang.RuntimeException|java.lang.RuntimeException
      (set n (array-op SIZE (jcall Throwable.getSuppressed var(e))))))
  var(n)
```

```hir
;; hir -O1 fn=supTest
fn supTest () -> int {volatile unique}
  (def n 0:int)
  (try
    (block
      (try
        (block
          (throw (jnew RuntimeException "p")))
        (finally
          (throw (jnew IllegalArgumentException "s")))))
    (catch e:java|java.lang.RuntimeException|java.lang.RuntimeException
      (set n (array-op SIZE (jcall Throwable.getSuppressed var(e))))))
  var(n)
```

```mir
;; mir -O2 fn=supTest stage=before
fn supTest() -> int:
  bb0:
    v0: int = const 0
    br bb1
  bb1 [unwind: ThrownException -> bb11, RuntimeException -> bb8]:
    br bb2
  bb2 [unwind: * -> bb3, ThrownException -> bb11, RuntimeException -> bb8]:
    v1: any = const "p"
    v2: any = new_java RuntimeException(v1) {pure!}
    rethrow v2
  bb3(v3: any) [handler] [unwind: ThrownException -> bb11, RuntimeException -> bb8]:
    br bb4
  bb4 [unwind: ThrownException -> bb11, RuntimeException -> bb8]:
    br bb5
  bb5 [unwind: Throwable -> bb6, ThrownException -> bb11, RuntimeException -> bb8]:
    v4: any = const "s"
    v5: any = new_java IllegalArgumentException(v4) {pure!}
    rethrow v5
  bb6(v6: any) [handler] [unwind: ThrownException -> bb11, RuntimeException -> bb8]:
    add_suppressed v3, v6
    br bb7
  bb7 [unwind: ThrownException -> bb11, RuntimeException -> bb8]:
    rethrow v3
  bb8(v7: any) [handler]:
    br bb9
  bb9:
    v8: any = call_java v7.getSuppressed() {impure}
    v9: any = checkcast v8 <java|array of java.lang.Throwable?|[Ljava.lang.Throwable;>
    v10: int = array_len v9 {read:frozen,throws}
    br bb10
  bb10:
    ret v10
  bb11(v11: any) [handler]:
    rethrow v11
```

```mir
;; mir -O2 fn=supTest
fn supTest() -> int:
  bb0:
    br bb1
  bb1 [unwind: * -> bb2, ThrownException -> bb9, RuntimeException -> bb6]:
    v0: any = const "p"
    v1: any = new_java RuntimeException(v0) {pure!}
    rethrow v1
  bb2(v2: any) [handler] [unwind: ThrownException -> bb9, RuntimeException -> bb6]:
    br bb3
  bb3 [unwind: Throwable -> bb4, ThrownException -> bb9, RuntimeException -> bb6]:
    v3: any = const "s"
    v4: any = new_java IllegalArgumentException(v3) {pure!}
    rethrow v4
  bb4(v5: any) [handler] [unwind: ThrownException -> bb9, RuntimeException -> bb6]:
    add_suppressed v2, v5
    br bb5
  bb5 [unwind: ThrownException -> bb9, RuntimeException -> bb6]:
    rethrow v2
  bb6(v6: any) [handler]:
    br bb7
  bb7:
    v7: any = call_java v6.getSuppressed() {impure}
    v8: any = checkcast v7 <java|array of java.lang.Throwable?|[Ljava.lang.Throwable;>
    v9: int = array_len v8 {read:frozen,throws}
    br bb8
  bb8:
    ret v9
  bb9(v10: any) [handler]:
    rethrow v10
```

## Loop-local cell capture in closure

```babble
def cellCap = fn (xs: list of int): int -> (
    def fs = (list of (fn (): int))()
    for x in xs then (
        def c = x + 1
        fs.add(fn (): int -> (c))
    )
    fs.get(0)() + fs.get(1)()
)
assert(cellCap(list(10, 20)) == 32)
```

```hir
;; hir -O0 fn=cellCap
fn cellCap (xs:obj) -> int {pure unique}
  (def fs (call (block (type-apply core-col(list)))))
  (for x in var(xs)
    (block
      (def c (+:int var(x) 1:int))
      (jcall List.add var(fs) (fn-ref <anon#0>))))
  (+:int (call (jcall List.get var(fs) 0:int)) (call (jcall List.get var(fs) 1:int)))
```

```hir
;; hir -O1 fn=cellCap
fn cellCap (xs:obj) -> int {pure unique}
  (def fs (call (type-apply core-col(list))))
  (for x in var(xs)
    (block
      (def c (+:int var(x) 1:int))
      (jcall List.add var(fs) (fn-ref <anon#0>))))
  (+:int (call (jcall List.get var(fs) 0:int)) (call (jcall List.get var(fs) 1:int)))
```

```mir
;; mir -O2 fn=cellCap stage=before
fn cellCap(v0: any) -> int:
  bb0:
    v1: any = opaque.CoreCollectionRef(list) {pure}
    v2: any = opaque.DynamicCall(v1) {impure}
    v3: any = for_init.java_iterable v0
    v4: any = const null
    br bb1(v4)
  bb1(v5: any):
    v6: bool = for_has_next v3
    br_if v6, bb3, bb2
  bb2:
    v7: int = const 0
    v8: any = call_java v2.get(v7) {impure}
    v9: any = checkcast v8 <fn|() : int|>
    v10: int = opaque.DynamicCall(v9) {impure}
    v11: int = const 1
    v12: any = call_java v2.get(v11) {impure}
    v13: any = checkcast v12 <fn|() : int|>
    v14: int = opaque.DynamicCall(v13) {impure}
    v15: int = add v10, v14 {pure}
    ret v15
  bb3:
    v16: any = for_next v3
    v17: any = checkcast v16 <int|int|>
    v18: int = convert.int v17
    slot_decl c
    v19: int = const 1
    v20: int = add v18, v19 {pure}
    slot_init c, v20 {write:shared}
    v21: any = opaque.FunctionRef(<anon>) {read:shared}
    v22: bool = call_java v2.add(v21) {impure}
    v23: any = convert.any v22
    br bb1(v23)
```

```mir
;; mir -O2 fn=cellCap
fn cellCap(v0: any) -> int:
  bb0:
    v1: any = opaque.CoreCollectionRef(list) {pure}
    v2: any = opaque.DynamicCall(v1) {impure}
    v3: any = for_init.java_iterable v0
    br bb1
  bb1:
    v4: bool = for_has_next v3
    br_if v4, bb3, bb2
  bb2:
    v5: int = const 0
    v6: any = call_java v2.get(v5) {impure}
    v7: any = checkcast v6 <fn|() : int|>
    v8: int = opaque.DynamicCall(v7) {impure}
    v9: int = const 1
    v10: any = call_java v2.get(v9) {impure}
    v11: any = checkcast v10 <fn|() : int|>
    v12: int = opaque.DynamicCall(v11) {impure}
    v13: int = add v8, v12 {pure}
    ret v13
  bb3:
    v14: any = for_next v3
    v15: any = checkcast v14 <int|int|>
    v16: int = convert.int v15
    slot_decl c
    v17: int = const 1
    v18: int = add v16, v17 {pure}
    slot_init c, v18 {write:shared}
    v19: any = opaque.FunctionRef(<anon>) {read:shared}
    v20: bool = call_java v2.add(v19) {impure}
    br bb1
```
