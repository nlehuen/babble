# Try-Expression Stack-Empty Normalization : Java Calls, Dynamic Calls, and Overloads

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
```


## JavaConstructorCall : pure static callee and dynamic callee with try arg

```babble
def run = fn io (s: string, f: bool): int -> (
    def p1 = java.awt.Point(1, try (parse(s)) catch : exception then (2))
    def p2 = (if f then (java.awt.Point) else (java.awt.Point))(3, try (parse(s)) catch : exception then (4))
    p1.y + p2.y
)
assert(run("8", false) == 16 and run("bad", true) == 6)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def p1
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jnew Point var($try0) var($try1))))
  (def p2
    (block
      (def $try2 (block (if var(f) (block (select (select name(java).awt).Point)) (block (select (select name(java).awt).Point)))))
      (def $try3 3:int)
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))
      (jnew Point var($try3) var($try4))))
  (+:int (get-jfield Point.y var(p1)) (get-jfield Point.y var(p2)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def p1
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jnew Point var($try0) var($try1))))
  (def p2
    (block
      (def $try2 (if var(f) (select (select name(java).awt).Point) (select (select name(java).awt).Point)))
      (def $try3 3:int)
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))
      (jnew Point var($try3) var($try4))))
  (+:int (get-jfield Point.y var(p1)) (get-jfield Point.y var(p2)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = name_read parse {pure}
    v4: int = call parse(v0) {impure}
    br bb4(v4)
  bb2(v5: any) [handler]:
    br bb3
  bb3:
    v6: int = const 2
    br bb4(v6)
  bb4(v7: int):
    v8: any = new_java Point(v2, v7) {impure}
    br_if v1, bb6, bb5
  bb5:
    v9: any = name_read java {pure}
    v10: any = opaque.DynamicSelect(v9.awt) {impure}
    v11: any = opaque.DynamicSelect(v10.Point) {impure}
    br bb7(v11)
  bb6:
    v12: any = name_read java {pure}
    v13: any = opaque.DynamicSelect(v12.awt) {impure}
    v14: any = opaque.DynamicSelect(v13.Point) {impure}
    br bb7(v14)
  bb7(v15: any):
    v16: int = const 3
    br bb8
  bb8 [unwind: BabbleException -> bb9]:
    v17: any = name_read parse {pure}
    v18: int = call parse(v0) {impure}
    br bb11(v18)
  bb9(v19: any) [handler]:
    br bb10
  bb10:
    v20: int = const 4
    br bb11(v20)
  bb11(v21: int):
    v22: any = new_java Point(v16, v21) {impure}
    v23: int = load_jfield v8.y {read:shared,throws}
    v24: int = load_jfield v22.y {read:shared,throws}
    v25: int = add v23, v24 {pure}
    ret v25
```

## JavaConstructorCall : dynamic callee containing try (lastTry < 0)

```babble
def run = fn io (f: bool): int -> (
    def p = (try (fail(f, java.awt.Point)) catch : exception then (java.awt.Point))(5, 6)
    p.x + p.y
)
assert(run(false) == 11 and run(true) == 11)
```

```hir
;; hir -O0,-O1 fn=run
fn run (f:bool) -> int {io}
  (def p (jnew Point 5:int 6:int))
  (+:int (get-jfield Point.x var(p)) (get-jfield Point.y var(p)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v1: any = name_read fail {pure}
    v2: any = name_read java {pure}
    v3: any = opaque.DynamicSelect(v2.awt) {impure}
    v4: any = opaque.DynamicSelect(v3.Point) {impure}
    v5: any = opaque.DynamicCall(v1, v0, v4) {impure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: any = name_read java {pure}
    v8: any = opaque.DynamicSelect(v7.awt) {impure}
    v9: any = opaque.DynamicSelect(v8.Point) {impure}
    br bb4(v9)
  bb4(v10: any):
    v11: int = const 5
    v12: int = const 6
    v13: any = new_java Point(v11, v12) {impure}
    v14: int = load_jfield v13.x {read:shared,throws}
    v15: int = load_jfield v13.y {read:shared,throws}
    v16: int = add v14, v15 {pure}
    ret v16
```

## JavaMethodCall : static method, instance method with try arg, and receiver try

```babble
def run = fn io (s: string, f: bool): string -> (
    def m = java.lang.Math.max(1, try (parse(s)) catch : exception then (2))
    def sb = java.lang.StringBuilder()
    sb.append(try (parse(s)) catch : exception then (9))
    (try (fail(f, sb)) catch : exception then (sb)).append(":" + m)
    sb.toString()
)
assert(run("5", false) == "5:5" and run("bad", true) == "9:2")
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> obj {io}
  (def m
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jcall Math.max var($try0) var($try1))))
  (def sb (jnew StringBuilder))
  (block
    (def $try2 var(sb))
    (def $try3
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          9:int)))
    (jcall StringBuilder.append var($try2) var($try3)))
  (jcall StringBuilder.append (block
      (try
        (block
          (call name(fail) var(f) var(sb)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(sb)))) (+:obj ":" var(m)))
  (jcall StringBuilder.toString var(sb))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> obj {io}
  (def m
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jcall Math.max var($try0) var($try1))))
  (def sb (jnew StringBuilder))
  (block
    (def $try2 var(sb))
    (def $try3
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          9:int)))
    (jcall StringBuilder.append var($try2) var($try3)))
  (jcall StringBuilder.append (try
      (block
        (call name(fail) var(f) var(sb)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(sb))) (+:obj ":" var(m)))
  (jcall StringBuilder.toString var(sb))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: bool) -> any:
  bb0:
    v2: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = name_read parse {pure}
    v4: int = call parse(v0) {impure}
    br bb4(v4)
  bb2(v5: any) [handler]:
    br bb3
  bb3:
    v6: int = const 2
    br bb4(v6)
  bb4(v7: int):
    v8: int = call_java Math.max(v2, v7) {pure}
    v9: any = new_java StringBuilder() {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v10: any = name_read parse {pure}
    v11: int = call parse(v0) {impure}
    br bb8(v11)
  bb6(v12: any) [handler]:
    br bb7
  bb7:
    v13: int = const 9
    br bb8(v13)
  bb8(v14: int):
    v15: any = call_java v9.append(v14) {impure}
    v16: any = checkcast v15 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v17: any = name_read fail {pure}
    v18: any = opaque.DynamicCall(v17, v1, v9) {impure}
    br bb12(v18)
  bb10(v19: any) [handler]:
    br bb11
  bb11:
    br bb12(v9)
  bb12(v20: any):
    v21: any = const ":"
    v22: any = convert.any v8
    v23: any = add v21, v22 {impure}
    v24: any = call_java v20.append(v23) {impure}
    v25: any = checkcast v24 <java|java.lang.StringBuilder|java.lang.StringBuilder>
    v26: any = call_java v9.toString() {impure}
    v27: any = checkcast v26 <string|string|>
    ret v27
```

## DynamicCall and SafeCall with try in callee or receiver only

```babble
def getOpt = fn io (f: bool): string? -> (if f then (throw exception("e")) else ("abc"))
def run = fn io (f: bool): int -> (
    def g = fn (x: int): int -> (x + 1)
    def a = (try (fail(f, g)) catch : exception then (g))(4)
    def b = (try (getOpt(f)) catch : exception then (null))?.length()
    a + (if b != null then (b) else (0))
)
assert(run(false) == 8 and run(true) == 5)
```

```hir
;; hir -O0 fn=run
fn run (f:bool) -> int {io}
  (def g (fn-ref g))
  (def a (call (block
        (try
          (block
            (call name(fail) var(f) var(g)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(g)))) 4:int))
  (def b (safe-call (block
        (try
          (block
            (call-fn getOpt var(f)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            null)))?.length))
  (+:int var(a) (block (if (<>:bool var(b) null) (block (narrow.nonnull var(b))) (block 0:int))))
```

```hir
;; hir -O1 fn=run
fn run (f:bool) -> int {io}
  (def g (fn-ref g))
  (def a (call (try
        (block
          (call name(fail) var(f) var(g)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(g))) 4:int))
  (def b (safe-call (try
        (block
          (call-fn getOpt var(f)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          null))?.length))
  (+:int var(a) (if (<>:bool var(b) null) (narrow.nonnull var(b)) 0:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: bool) -> int:
  bb0:
    v1: any = opaque.FunctionRef(g) {pure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = name_read fail {pure}
    v3: any = opaque.DynamicCall(v2, v0, v1) {impure}
    br bb4(v3)
  bb2(v4: any) [handler]:
    br bb3
  bb3:
    br bb4(v1)
  bb4(v5: any):
    v6: int = const 4
    v7: int = opaque.DynamicCall(v5, v6) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v8: any = name_read getOpt {pure}
    v9: any = call getOpt(v0) {impure}
    br bb8(v9)
  bb6(v10: any) [handler]:
    br bb7
  bb7:
    v11: any = const null
    br bb8(v11)
  bb8(v12: any):
    v13: any = const null
    v14: bool = neq v12, v13 {pure}
    br_if v14, bb9, bb10(v13)
  bb9:
    v15: any = narrow.nonnull v12 <non-null>
    v16: any = opaque.SafeCall(v15?.length) {impure}
    br bb10(v16)
  bb10(v17: any):
    v18: any = const null
    v19: any = neq v17, v18 {pure,throws}
    br_if v19, bb12, bb11
  bb11:
    v20: int = const 0
    br bb13(v20)
  bb12:
    v21: any = narrow.nonnull v17 <non-null>
    v22: int = convert.int v21
    br bb13(v22)
  bb13(v23: int):
    v24: int = add v7, v23 {pure}
    ret v24
```

```mir
;; mir -O2 fn=run
fn run(v0: bool) -> int:
  bb0:
    v1: any = opaque.FunctionRef(g) {pure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = name_read fail {pure}
    v3: any = opaque.DynamicCall(v2, v0, v1) {impure}
    br bb4(v3)
  bb2(v4: any) [handler]:
    br bb3
  bb3:
    br bb4(v1)
  bb4(v5: any):
    v6: int = const 4
    v7: int = opaque.DynamicCall(v5, v6) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v8: any = name_read getOpt {pure}
    v9: any = call getOpt(v0) {impure}
    br bb8(v9)
  bb6(v10: any) [handler]:
    br bb7
  bb7:
    v11: any = const null
    br bb8(v11)
  bb8(v12: any):
    v13: any = const null
    v14: bool = neq v12, v13 {pure}
    br_if v14, bb9, bb10(v13)
  bb9:
    v15: any = narrow.nonnull v12 <non-null>
    v16: any = opaque.SafeCall(v15?.length) {impure}
    br bb10(v16)
  bb10(v17: any):
    v18: any = neq v17, v13 {pure,throws}
    br_if v18, bb12, bb11
  bb11:
    v19: int = const 0
    br bb13(v19)
  bb12:
    v20: any = narrow.nonnull v17 <non-null>
    v21: int = convert.int v20
    br bb13(v21)
  bb13(v22: int):
    v23: int = add v7, v22 {pure}
    ret v23
```

## MakeOverloadSet and OverloadDispatch with try in operands, args, and callee

```babble
def fi = fn io (x: int): int -> (x + 1)
def fs = fn io (s: string): int -> (s.length())
def run = fn io (f: bool, u: int | string): int -> (
  def ov = overload(fi, try (fail(f, fs)) catch : exception then (fs))
  def a = ov(try (fail(f, u)) catch : exception then ("ab"))
  def b = (try (fail(f, ov)) catch : exception then (ov))(u)
  a + b
)
assert(run(false, 4) == 10 and run(true, "xyz") == 5)
```

```hir
;; hir -O0 fn=run
fn run (f:bool, u:obj) -> int {io}
  (def ov
    (block
      (def $try0 name(fi))
      (def $try1
        (try
          (block
            (call name(fail) var(f) name(fs)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            name(fs))))
      (overload var($try0) var($try1))))
  (def a
    (block
      (def $try2 var(ov))
      (def $try3
        (try
          (block
            (call name(fail) var(f) var(u)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            "ab")))
      (overload-dispatch var($try2) var($try3))))
  (def b (overload-dispatch (block
        (try
          (block
            (call name(fail) var(f) var(ov)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(ov)))) var(u)))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run (f:bool, u:obj) -> int {io}
  (def ov
    (block
      (def $try0 name(fi))
      (def $try1
        (try
          (block
            (call name(fail) var(f) name(fs)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            name(fs))))
      (overload var($try0) var($try1))))
  (def a
    (block
      (def $try2 var(ov))
      (def $try3
        (try
          (block
            (call name(fail) var(f) var(u)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            "ab")))
      (overload-dispatch var($try2) var($try3))))
  (def b (overload-dispatch (try
        (block
          (call name(fail) var(f) var(ov)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(ov))) var(u)))
  (+:int var(a) var(b))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: bool, v1: any) -> int:
  bb0:
    v2: any = name_read fi {pure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = name_read fail {pure}
    v4: any = name_read fs {pure}
    v5: any = opaque.DynamicCall(v3, v0, v4) {impure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: any = name_read fs {pure}
    br bb4(v7)
  bb4(v8: any):
    v9: any = opaque.MakeOverloadSet(v2, v8) {pure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v10: any = name_read fail {pure}
    v11: any = opaque.DynamicCall(v10, v0, v1) {impure}
    br bb8(v11)
  bb6(v12: any) [handler]:
    br bb7
  bb7:
    v13: any = const "ab"
    br bb8(v13)
  bb8(v14: any):
    v15: int = opaque.OverloadDispatch(v9, v14) {impure}
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v16: any = name_read fail {pure}
    v17: any = opaque.DynamicCall(v16, v0, v9) {impure}
    br bb12(v17)
  bb10(v18: any) [handler]:
    br bb11
  bb11:
    br bb12(v9)
  bb12(v19: any):
    v20: int = opaque.OverloadDispatch(v19, v1) {impure}
    v21: int = add v15, v20 {pure}
    ret v21
```
