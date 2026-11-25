# Try-Expression Stack-Empty Normalization : Operators and Field Writes

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def addIO = fn io (a: int, b: int): int -> (a + b)
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
def Box = class (var v: int) (
    def addIO = fn io (a: int, b: int): int -> (this.v + a + b)
    def bumpCompound = fn mut io (s: string): int -> (
        this.v += try (parse(s)) catch : exception then (5)
        this.v
    )
)
def Calc = interface (def calc = fn io (a: int, b: int): int -> (a + b))
def SubCalc = class () implements Calc (
    def calc = fn io (a: int, b: int): int -> (
        super.calc(a, try (parse("" + b)) catch : exception then (0)) + 1
    )
)
```


## Binary LHS try vs RHS try spilling

```babble
def lhsTry = fn io (s: string): int -> (
    (try (parse(s)) catch : exception then (10)) + 1
)
def rhsTry = fn io (s: string): int -> (
    10 + (try (parse(s)) catch : exception then (2))
)
assert(lhsTry("4") == 5 and lhsTry("x") == 11 and rhsTry("3") == 13 and rhsTry("x") == 12)
```

```hir
;; hir -O0 fn=lhsTry
fn lhsTry (s:obj) -> int {io}
  (+:int (block
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          10:int))) 1:int)
```

```hir
;; hir -O1 fn=lhsTry
fn lhsTry (s:obj) -> int {io}
  (+:int (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        10:int)) 1:int)
```

```hir
;; hir -O0 fn=rhsTry
fn rhsTry (s:obj) -> int {io}
  (block
    (def $try0 10:int)
    (def $try1
      (block
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int))))
    (+:int var($try0) var($try1)))
```

```hir
;; hir -O1 fn=rhsTry
fn rhsTry (s:obj) -> int {io}
  (block
    (def $try0 10:int)
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          2:int)))
    (+:int var($try0) var($try1)))
```

```mir
;; mir -O2 fn=rhsTry stage=before,final
fn rhsTry(v0: any) -> int:
  bb0:
    v1: int = const 10
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead(parse) {read:shared}
    v3: int = call parse(v0) {impure}
    br bb4(v3)
  bb2(v4: any) [handler]:
    br bb3
  bb3:
    v5: int = const 2
    br bb4(v5)
  bb4(v6: int):
    v7: int = add v1, v6 {pure}
    ret v7
```

## Logic short-circuit containsTry on left and right operands

```babble
def logicTry = fn io (f: bool, s: string): bool -> (
    def a = (try (parse(s) > 0) catch : exception then (false)) and f
    def b = f and (try (parse(s) > 0) catch : exception then (false))
    a and b
)
assert(logicTry(true, "1") and not logicTry(true, "x") and not logicTry(false, "x"))
```

```hir
;; hir -O0 fn=logicTry
fn logicTry (f:bool, s:obj) -> bool {io}
  (def a (and (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool))) var(f)))
  (def b (and var(f) (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool)))))
  (and var(a) var(b))
```

```hir
;; hir -O1 fn=logicTry
fn logicTry (f:bool, s:obj) -> bool {io}
  (def a (and (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)) var(f)))
  (def b (and var(f) (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool))))
  (and var(a) var(b))
```

```mir
;; mir -O2 fn=logicTry stage=before
fn logicTry(v0: bool, v1: any) -> bool:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead(parse) {read:shared}
    v3: int = call parse(v1) {impure}
    v4: int = const 0
    v5: bool = gt v3, v4 {pure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: bool = const false
    br bb4(v7)
  bb4(v8: bool):
    v9: bool = const false
    br_if v8, bb5, bb6(v9)
  bb5:
    br bb6(v0)
  bb6(v10: bool):
    v11: bool = const false
    br_if v0, bb7, bb12(v11)
  bb7:
    br bb8
  bb8 [unwind: BabbleException -> bb9]:
    v12: any = opaque.NameRead(parse) {read:shared}
    v13: int = call parse(v1) {impure}
    v14: int = const 0
    v15: bool = gt v13, v14 {pure}
    br bb11(v15)
  bb9(v16: any) [handler]:
    br bb10
  bb10:
    v17: bool = const false
    br bb11(v17)
  bb11(v18: bool):
    br bb12(v18)
  bb12(v19: bool):
    v20: bool = const false
    br_if v10, bb13, bb14(v20)
  bb13:
    br bb14(v19)
  bb14(v21: bool):
    ret v21
```

```mir
;; mir -O2 fn=logicTry
fn logicTry(v0: bool, v1: any) -> bool:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead(parse) {read:shared}
    v3: int = call parse(v1) {impure}
    v4: int = const 0
    v5: bool = gt v3, v4 {pure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: bool = const false
    br bb4(v7)
  bb4(v8: bool):
    v9: bool = const false
    br_if v8, bb5, bb6(v9)
  bb5:
    br bb6(v0)
  bb6(v10: bool):
    br_if v0, bb7, bb11(v9)
  bb7 [unwind: BabbleException -> bb8]:
    v11: any = opaque.NameRead(parse) {read:shared}
    v12: int = call parse(v1) {impure}
    v13: int = const 0
    v14: bool = gt v12, v13 {pure}
    br bb10(v14)
  bb8(v15: any) [handler]:
    br bb9
  bb9:
    br bb10(v9)
  bb10(v16: bool):
    br bb11(v16)
  bb11(v17: bool):
    br_if v10, bb12, bb13(v9)
  bb12:
    br bb13(v17)
  bb13(v18: bool):
    ret v18
```

## Compound NameAssign and ThisFieldAssign with try RHS

```babble
var top = 10
def bumpTop = fn io (s: string): int -> (
    top += try (parse(s)) catch : exception then (5)
    top
)
def b = Box(10)
assert(bumpTop("3") == 13 and bumpTop("x") == 18)
assert(b.bumpCompound("4") == 14 and b.bumpCompound("x") == 19)
```

```hir
;; hir -O0,-O1 fn=bumpTop
fn bumpTop (s:obj) -> int {io}
  (set-name top += (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        5:int)))
  name(top)
```

```mir
;; mir -O2 fn=bumpTop stage=before,final
fn bumpTop(v0: any) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v1: any = opaque.NameRead(parse) {read:shared}
    v2: int = call parse(v0) {impure}
    br bb4(v2)
  bb2(v3: any) [handler]:
    br bb3
  bb3:
    v4: int = const 5
    br bb4(v4)
  bb4(v5: int):
    v6: any = opaque.NameAssign(top +=, v5) {impure}
    v7: any = opaque.NameRead(top) {read:shared,throws}
    v8: int = convert.int v7
    ret v8
```

## JavaFieldAssign : instance simple, compound, and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def pt = java.awt.Point(1, 2)
    pt.x = try (parse(s)) catch : exception then (7)
    pt.y += try (parse(s)) catch : exception then (3)
    (try (fail(f, pt)) catch : exception then (pt)).x = 9
    pt.x + pt.y
)
assert(run("5", false) == 16 and run("bad", true) == 14)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (block
    (def $try0 var(pt))
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          7:int)))
    (set-jfield x var($try0) var($try1)))
  (set-jfield y var(pt) (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        3:int)))
  (set-jfield x (block
      (try
        (block
          (call name(fail) var(f) var(pt)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(pt)))) 9:int)
  (+:int (get-jfield Point.x var(pt)) (get-jfield Point.y var(pt)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (block
    (def $try0 var(pt))
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          7:int)))
    (set-jfield x var($try0) var($try1)))
  (set-jfield y var(pt) (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        3:int)))
  (set-jfield x (try
      (block
        (call name(fail) var(f) var(pt)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(pt))) 9:int)
  (+:int (get-jfield Point.x var(pt)) (get-jfield Point.y var(pt)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 1
    v3: int = const 2
    v4: any = new_java Point(v2, v3) {pure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v5: any = opaque.NameRead(parse) {read:shared}
    v6: int = call parse(v0) {impure}
    br bb4(v6)
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: int = const 7
    br bb4(v8)
  bb4(v9: int):
    store_jfield v4.x, v9 {write:shared,throws}
    v10: int = load_jfield v4.y {read:shared,throws}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v11: any = opaque.NameRead(parse) {read:shared}
    v12: int = call parse(v0) {impure}
    br bb8(v12)
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    v14: int = const 3
    br bb8(v14)
  bb8(v15: int):
    v16: int = add v10, v15 {pure}
    store_jfield v4.y, v16 {write:shared,throws}
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v17: any = opaque.NameRead(fail) {read:shared}
    v18: any = opaque.DynamicCall(v17, v1, v4) {impure}
    br bb12(v18)
  bb10(v19: any) [handler]:
    br bb11
  bb11:
    br bb12(v4)
  bb12(v20: any):
    v21: int = const 9
    store_jfield v20.x, v21 {write:shared,throws}
    v22: int = load_jfield v4.x {read:shared,throws}
    v23: int = load_jfield v4.y {read:shared,throws}
    v24: int = add v22, v23 {pure}
    ret v24
```

## JavaFieldAssign : static pure receiver chain with try RHS

```babble
def run = fn io (s: string): int -> (
    def Demo = import("org.babblelang.tests").JavaClassDemo
    Demo.counter = try (parse(s)) catch : exception then (4)
)
assert(run("5") == 5 and run("bad") == 4)
```

```hir
;; hir -O0,-O1 fn=run
fn run (s:obj) -> int {io}
  (def Demo (select (call name(import) "org.babblelang.tests").JavaClassDemo))
  (block
    (def $try0
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (set-jfield counter var(Demo) var($try0)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any) -> int:
  bb0:
    v1: any = opaque.NameRead(import) {read:shared,throws}
    v2: any = const "org.babblelang.tests"
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: any = opaque.DynamicSelect(v3.JavaClassDemo) {impure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v5: any = opaque.NameRead(parse) {read:shared}
    v6: int = call parse(v0) {impure}
    br bb4(v6)
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: int = const 4
    br bb4(v8)
  bb4(v9: int):
    store_jfield JavaClassDemo.counter, v9 {write:shared,throws}
    ret v9
```

## DynamicFieldAssign with compound operator and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    b.v += try (parse(s)) catch : exception then (2)
    (try (fail(f, b)) catch : exception then (b)).v = b.v + 1
    b.v
)
assert(run("5", false) == 16 and run("x", true) == 13)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def b (call name(Box) 10:int))
  (set-field var(b).v (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        2:int)))
  (set-field (block
      (try
        (block
          (call name(fail) var(f) var(b)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(b)))).v (+:int (get-this-field v) 1:int))
  (get-this-field v)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def b (call name(Box) 10:int))
  (set-field var(b).v (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        2:int)))
  (set-field (try
      (block
        (call name(fail) var(f) var(b)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(b))).v (+:int (get-this-field v) 1:int))
  (get-this-field v)
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(Box) {read:shared}
    v3: int = const 10
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v5: any = opaque.NameRead(parse) {read:shared}
    v6: int = call parse(v0) {impure}
    br bb4(v6)
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: int = const 2
    br bb4(v8)
  bb4(v9: int):
    v10: any = opaque.DynamicFieldAssign(v4.v +=, v9) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v11: any = opaque.NameRead(fail) {read:shared}
    v12: any = opaque.DynamicCall(v11, v1, v4) {impure}
    br bb8(v12)
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    br bb8(v4)
  bb8(v14: any):
    v15: int = load_field v4.v {read:shared,throws}
    v16: int = const 1
    v17: int = add v15, v16 {pure}
    v18: any = opaque.DynamicFieldAssign(v14.v, v17) {write:shared,throws}
    v19: int = load_field v4.v {read:shared,throws}
    ret v19
```
