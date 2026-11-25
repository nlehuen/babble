# Try-Expression Stack-Empty Normalization : Arrays, Collections, and Calls

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


## ArrayCreate and ArrayOp with try in args, trailing arg, and try in receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def a = (array of int).of(1, try (parse(s)) catch : exception then (2), 9)
    a.set(0, try (parse(s)) catch : exception then (4))
    var v = (try (fail(f, a)) catch : exception then (a)).get(0)
    v + a.get(1) + a.get(2)
)
assert(run("3", false) == 15 and run("x", true) == 15)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def a
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (array-new OF var($try0) var($try1) 9:int)))
  (block
    (def $try2 var(a))
    (def $try3 0:int)
    (def $try4
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (array-op SET var($try2) var($try3) var($try4)))
  (def v (array-op GET (block
        (try
          (block
            (call name(fail) var(f) var(a)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(a)))) 0:int))
  (+:int (+:int var(v) (array-op GET var(a) 1:int)) (array-op GET var(a) 2:int))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def a
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (array-new OF var($try0) var($try1) 9:int)))
  (block
    (def $try2 var(a))
    (def $try3 0:int)
    (def $try4
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (array-op SET var($try2) var($try3) var($try4)))
  (def v (array-op GET (try
        (block
          (call name(fail) var(f) var(a)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(a))) 0:int))
  (+:int (+:int var(v) (array-op GET var(a) 1:int)) (array-op GET var(a) 2:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = opaque.NameRead(parse) {read:shared}
    v4: int = call parse(v0) {impure}
    br bb4(v4)
  bb2(v5: any) [handler]:
    br bb3
  bb3:
    v6: int = const 2
    br bb4(v6)
  bb4(v7: int):
    v8: int = const 9
    v9: any = opaque.ArrayCreate(v2, v7, v8) {impure}
    v10: int = const 0
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v11: any = opaque.NameRead(parse) {read:shared}
    v12: int = call parse(v0) {impure}
    br bb8(v12)
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    v14: int = const 4
    br bb8(v14)
  bb8(v15: int):
    array_set v9[v10] = v15 {write:shared,throws}
    v16: any = const null
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v17: any = opaque.NameRead(fail) {read:shared}
    v18: any = opaque.DynamicCall(v17, v1, v9) {impure}
    br bb12(v18)
  bb10(v19: any) [handler]:
    br bb11
  bb11:
    br bb12(v9)
  bb12(v20: any):
    v21: int = const 0
    v22: int = array_get v20[v21] {read:shared,throws}
    v23: int = const 1
    v24: int = array_get v9[v23] {read:shared,throws}
    v25: int = add v22, v24 {pure}
    v26: int = const 2
    v27: int = array_get v9[v26] {read:shared,throws}
    v28: int = add v25, v27 {pure}
    ret v28
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = opaque.NameRead(parse) {read:shared}
    v4: int = call parse(v0) {impure}
    br bb4(v4)
  bb2(v5: any) [handler]:
    br bb3
  bb3:
    v6: int = const 2
    br bb4(v6)
  bb4(v7: int):
    v8: int = const 9
    v9: any = opaque.ArrayCreate(v2, v7, v8) {impure}
    v10: int = const 0
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v11: any = opaque.NameRead(parse) {read:shared}
    v12: int = call parse(v0) {impure}
    br bb8(v12)
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    v14: int = const 4
    br bb8(v14)
  bb8(v15: int):
    array_set v9[v10] = v15 {write:shared,throws}
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v16: any = opaque.NameRead(fail) {read:shared}
    v17: any = opaque.DynamicCall(v16, v1, v9) {impure}
    br bb12(v17)
  bb10(v18: any) [handler]:
    br bb11
  bb11:
    br bb12(v9)
  bb12(v19: any):
    v20: int = array_get v19[v10] {read:shared,throws}
    v21: int = array_get v9[v2] {read:shared,throws}
    v22: int = add v20, v21 {pure}
    v23: int = const 2
    v24: int = array_get v9[v23] {read:shared,throws}
    v25: int = add v22, v24 {pure}
    ret v25
```

## CoreCollectionCall with try arg and trailing arg after try

```babble
def run = fn io (s: string): int -> (
    def xs = list(1, try (parse(s)) catch : exception then (5), 10)
    xs.get(0) + xs.get(1) + xs.get(2)
)
assert(run("4") == 15 and run("bad") == 16)
```

```hir
;; hir -O0,-O1 fn=run
fn run (s:obj) -> int {io}
  (def xs
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            5:int)))
      (core-new list var($try0) var($try1) 10:int)))
  (+:int (+:int (jcall List.get var(xs) 0:int) (jcall List.get var(xs) 1:int)) (jcall List.get var(xs) 2:int))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any) -> int:
  bb0:
    v1: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead(parse) {read:shared}
    v3: int = call parse(v0) {impure}
    br bb4(v3)
  bb2(v4: any) [handler]:
    br bb3
  bb3:
    v5: int = const 5
    br bb4(v5)
  bb4(v6: int):
    v7: int = const 10
    v8: any = opaque.CoreCollectionCall(list, v1, v6, v7) {impure}
    v9: int = const 0
    v10: any = call_java v8.get(v9) {impure}
    v11: any = checkcast v10 <int|int|>
    v12: int = convert.int v11
    v13: int = const 1
    v14: any = call_java v8.get(v13) {impure}
    v15: any = checkcast v14 <int|int|>
    v16: int = convert.int v15
    v17: int = add v12, v16 {pure}
    v18: int = const 2
    v19: any = call_java v8.get(v18) {impure}
    v20: any = checkcast v19 <int|int|>
    v21: int = convert.int v20
    v22: int = add v17, v21 {pure}
    ret v22
```

```mir
;; mir -O2 fn=run
fn run(v0: any) -> int:
  bb0:
    v1: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead(parse) {read:shared}
    v3: int = call parse(v0) {impure}
    br bb4(v3)
  bb2(v4: any) [handler]:
    br bb3
  bb3:
    v5: int = const 5
    br bb4(v5)
  bb4(v6: int):
    v7: int = const 10
    v8: any = opaque.CoreCollectionCall(list, v1, v6, v7) {impure}
    v9: int = const 0
    v10: any = call_java v8.get(v9) {impure}
    v11: any = checkcast v10 <int|int|>
    v12: int = convert.int v11
    v13: any = call_java v8.get(v1) {impure}
    v14: any = checkcast v13 <int|int|>
    v15: int = convert.int v14
    v16: int = add v12, v15 {pure}
    v17: int = const 2
    v18: any = call_java v8.get(v17) {impure}
    v19: any = checkcast v18 <int|int|>
    v20: int = convert.int v19
    v21: int = add v16, v20 {pure}
    ret v21
```

## SuperCall and InterfaceCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def c: Calc = SubCalc()
    def a = c.calc(1, try (parse(s)) catch : exception then (2))
    def b = (try (fail(f, c)) catch : exception then (c)).calc(3, 4)
    a + b
)
assert(run("5", false) == 15 and run("bad", true) == 12)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def c (call name(SubCalc)))
  (def a
    (block
      (def $try0 var(c))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-iface var($try0).calc var($try1) var($try2))))
  (def b (call-iface (block
        (try
          (block
            (call name(fail) var(f) var(c)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(c)))).calc 3:int 4:int))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def c (call name(SubCalc)))
  (def a
    (block
      (def $try0 var(c))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-iface var($try0).calc var($try1) var($try2))))
  (def b (call-iface (try
        (block
          (call name(fail) var(f) var(c)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(c))).calc 3:int 4:int))
  (+:int var(a) var(b))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(SubCalc) {read:shared}
    v3: any = opaque.DynamicCall(v2) {impure}
    v4: int = const 1
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
    v10: int = call_iface v3.calc(v4, v9) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v11: any = opaque.NameRead(fail) {read:shared}
    v12: any = opaque.DynamicCall(v11, v1, v3) {impure}
    br bb8(v12)
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    br bb8(v3)
  bb8(v14: any):
    v15: int = const 3
    v16: int = const 4
    v17: int = call_iface v14.calc(v15, v16) {impure}
    v18: int = add v10, v17 {pure}
    ret v18
```

## SelfRecurseCall (non-tail) and SelfTailCall with try arguments

```babble
def nonT = fn io (n: int, s: string): int -> (
  if n <= 0 then (0)
  else (1 + recurse(n - 1, try (fail(s == "x", s)) catch : exception then ("1")))
)
def tailR = fn io (n: int, a: int, s: string): int -> (
  if n <= 0 then (a)
  else (tailR(n - 1, a + try (parse(s)) catch : exception then (2), s))
)
assert(nonT(3, "x") == 3 and tailR(3, 0, "4") == 12 and tailR(3, 0, "b") == 6)
```

```hir
;; hir -O0 fn=nonT
fn nonT (n:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (block
        (def $try0 1:int)
        (def $try1
          (block
            (def $try2 (-:int var(n) 1:int))
            (def $try3
              (try
                (block
                  (call name(fail) (==:bool var(s) "x") var(s)))
                (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                  "1")))
            (self-recurse nonT var($try2) var($try3))))
        (+:int var($try0) var($try1)))))
```

```hir
;; hir -O1 fn=nonT
fn nonT (n:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def $try0 1:int)
      (def $try1
        (block
          (def $try2 (-:int var(n) 1:int))
          (def $try3
            (try
              (block
                (call name(fail) (==:bool var(s) "x") var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                "1")))
          (self-recurse nonT var($try2) var($try3))))
      (+:int var($try0) var($try1))))
```

```hir
;; hir -O0 fn=tailR
fn tailR (n:int, a:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    (block var(a))
    (block
      (block
        (def $try0 (-:int var(n) 1:int))
        (def $try1
          (block
            (def $try2 var(a))
            (def $try3
              (try
                (block
                  (call-fn parse var(s)))
                (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                  2:int)))
            (+:int var($try2) var($try3))))
        (tail-call tailR var($try0) var($try1) var(s)))))
```

```hir
;; hir -O1 fn=tailR
fn tailR (n:int, a:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    var(a)
    (block
      (def $try0 (-:int var(n) 1:int))
      (def $try1
        (block
          (def $try2 var(a))
          (def $try3
            (try
              (block
                (call-fn parse var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                2:int)))
          (+:int var($try2) var($try3))))
      (tail-call tailR var($try0) var($try1) var(s))))
```

```mir
;; mir -O2 fn=tailR stage=before
fn tailR(v0: int, v1: int, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: bool = lte v0, v3 {pure}
    br_if v4, bb6, bb1
  bb1:
    v5: int = const 1
    v6: int = sub v0, v5 {pure}
    br bb2
  bb2 [unwind: BabbleException -> bb3]:
    v7: any = opaque.NameRead(parse) {read:shared}
    v8: int = call parse(v2) {impure}
    br bb5(v8)
  bb3(v9: any) [handler]:
    br bb4
  bb4:
    v10: int = const 2
    br bb5(v10)
  bb5(v11: int):
    v12: int = add v1, v11 {pure}
    tail_call tailR(v6, v12, v2)
  bb6:
    br bb7
  bb7:
    ret v1
```

```mir
;; mir -O2 fn=tailR
fn tailR(v0: int, v1: int, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: bool = lte v0, v3 {pure}
    br_if v4, bb6, bb1
  bb1:
    v5: int = const 1
    v6: int = sub v0, v5 {pure}
    br bb2
  bb2 [unwind: BabbleException -> bb3]:
    v7: any = opaque.NameRead(parse) {read:shared}
    v8: int = call parse(v2) {impure}
    br bb5(v8)
  bb3(v9: any) [handler]:
    br bb4
  bb4:
    v10: int = const 2
    br bb5(v10)
  bb5(v11: int):
    v12: int = add v1, v11 {pure}
    tail_call tailR(v6, v12, v2)
  bb6:
    ret v1
```

## DirectBabbleCall and DirectThisMethodCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    def r1 = addIO(1, try (parse(s)) catch : exception then (2))
    def r2 = b.addIO(1, try (parse(s)) catch : exception then (3))
    def r3 = (try (fail(f, b)) catch : exception then (b)).addIO(2, 3)
    r1 + r2 + r3
)
assert(run("5", false) == 37 and run("bad", true) == 32)
```

```hir
;; hir -O0 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (block
```

```hir
;; hir -O1 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (try
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(Box) {read:shared}
    v3: int = const 10
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    v5: any = opaque.NameRead(addIO) {read:shared}
    v6: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v7: any = opaque.NameRead(parse) {read:shared}
    v8: int = call parse(v0) {impure}
    br bb4(v8)
  bb2(v9: any) [handler]:
    br bb3
  bb3:
    v10: int = const 2
    br bb4(v10)
  bb4(v11: int):
    v12: int = call addIO(v6, v11) {impure}
    v13: int = const 1
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v14: any = opaque.NameRead(parse) {read:shared}
    v15: int = call parse(v0) {impure}
    br bb8(v15)
  bb6(v16: any) [handler]:
    br bb7
  bb7:
    v17: int = const 3
    br bb8(v17)
  bb8(v18: int):
    v19: int = call_method v4.addIO(v13, v18) {impure}
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v20: any = opaque.NameRead(fail) {read:shared}
    v21: any = opaque.DynamicCall(v20, v1, v4) {impure}
    br bb12(v21)
  bb10(v22: any) [handler]:
    br bb11
  bb11:
    br bb12(v4)
  bb12(v23: any):
    v24: int = const 2
    v25: int = const 3
    v26: int = call_method v23.addIO(v24, v25) {impure}
    v27: int = add v12, v19 {pure}
    v28: int = add v27, v26 {pure}
    ret v28
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: any = opaque.NameRead(Box) {read:shared}
    v3: int = const 10
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    v5: any = opaque.NameRead(addIO) {read:shared}
    v6: int = const 1
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v7: any = opaque.NameRead(parse) {read:shared}
    v8: int = call parse(v0) {impure}
    br bb4(v8)
  bb2(v9: any) [handler]:
    br bb3
  bb3:
    v10: int = const 2
    br bb4(v10)
  bb4(v11: int):
    v12: int = call addIO(v6, v11) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v13: any = opaque.NameRead(parse) {read:shared}
    v14: int = call parse(v0) {impure}
    br bb8(v14)
  bb6(v15: any) [handler]:
    br bb7
  bb7:
    v16: int = const 3
    br bb8(v16)
  bb8(v17: int):
    v18: int = call_method v4.addIO(v6, v17) {impure}
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v19: any = opaque.NameRead(fail) {read:shared}
    v20: any = opaque.DynamicCall(v19, v1, v4) {impure}
    br bb12(v20)
  bb10(v21: any) [handler]:
    br bb11
  bb11:
    br bb12(v4)
  bb12(v22: any):
    v23: int = const 2
    v24: int = const 3
    v25: int = call_method v22.addIO(v23, v24) {impure}
    v26: int = add v12, v18 {pure}
    v27: int = add v26, v25 {pure}
    ret v27
```
