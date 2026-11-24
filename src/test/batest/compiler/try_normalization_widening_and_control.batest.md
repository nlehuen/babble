# Try-Expression Stack-Empty Normalization : Widening Ranks and Control Flow

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
```


## Effective widening ranks 1..2 (byte->short, short->int) during try spilling

```babble
def addSh = fn io (a: short, b: short): int -> (a + b)
def run = fn io (b: byte, sh: short, s: string): int -> (
  def r1 = addSh(b, try (short(parse(s))) catch : exception then (short(1)))
  def r2 = sh + (try (parse(s)) catch : exception then (1))
  r1 + r2
)
assert(run(byte(2), short(3), "5") == 15 and run(byte(2), short(3), "x") == 7)
```

```hir
;; hir -O0 fn=run
fn run (b:byte, sh:short, s:obj) -> int {io}
  (def r1
    (block
      (def $try0 name(addSh))
      (def $try1 var(b))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 var(sh))
      (def $try4
        (block
          (try
            (block
              (call-fn parse var(s)))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1:int))))
      (+:int var($try3) var($try4))))
  (+:int var(r1) var(r2))
```

```hir
;; hir -O1 fn=run
fn run (b:byte, sh:short, s:obj) -> int {io}
  (def r1
    (block
      (def $try0 name(addSh))
      (def $try1 var(b))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 var(sh))
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:int)))
      (+:int var($try3) var($try4))))
  (+:int var(r1) var(r2))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: byte, v1: short, v2: any) -> int:
  bb0:
    v3: any = opaque.NameRead() {impure}
    v4: short = convert.short v0
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v5: any = opaque.NameRead() {impure}
    v6: any = opaque.NameRead() {impure}
    v7: int = call parse(v2) {impure}
    v8: any = opaque.DynamicCall(v5, v7) {impure}
    v9: short = convert.short v8
    br bb4(v9)
  bb2(v10: any) [handler]:
    br bb3
  bb3:
    v11: any = opaque.NameRead() {impure}
    v12: int = const 1
    v13: any = opaque.DynamicCall(v11, v12) {impure}
    v14: short = convert.short v13
    br bb4(v14)
  bb4(v15: short):
    v16: int = call addSh(v4, v15) {impure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v17: any = opaque.NameRead() {impure}
    v18: int = call parse(v2) {impure}
    br bb8(v18)
  bb6(v19: any) [handler]:
    br bb7
  bb7:
    v20: int = const 1
    br bb8(v20)
  bb8(v21: int):
    v22: int = convert.int v1
    v23: int = add v22, v21 {pure}
    v24: int = add v16, v23 {pure}
    ret v24
```

## Effective widening rank 3 (int->long) during try spilling

```babble
def run = fn io (n: int, s: string): long -> (
  n + (try (long(parse(s))) catch : exception then (1L))
)
assert(run(4, "5") == 9L and run(4, "x") == 5L)
```

```hir
;; hir -O0 fn=run
fn run (n:int, s:obj) -> long {io}
  (block
    (def $try0 var(n))
    (def $try1
      (block
        (try
          (block
            (call name(long) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:long))))
    (+:long var($try0) var($try1)))
```

```hir
;; hir -O1 fn=run
fn run (n:int, s:obj) -> long {io}
  (block
    (def $try0 var(n))
    (def $try1
      (try
        (block
          (call name(long) (call-fn parse var(s))))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          1:long)))
    (+:long var($try0) var($try1)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int, v1: any) -> long:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead() {impure}
    v3: any = opaque.NameRead() {impure}
    v4: int = call parse(v1) {impure}
    v5: any = opaque.DynamicCall(v2, v4) {impure}
    v6: long = convert.long v5
    br bb4(v6)
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: long = const 1L
    br bb4(v8)
  bb4(v9: long):
    v10: long = convert.long v0
    v11: long = add v10, v9 {pure}
    ret v11
```

## Effective widening ranks 4..5 (int->float, int->double) during try spilling

```babble
def run = fn io (n: int, s: string): double -> (
    def r4 = n + (try (float(parse(s))) catch : exception then (1.0f))
    def r5 = n + (try (double(parse(s))) catch : exception then (1.0))
    double(r4) + r5
)
assert(run(4, "5") == 18.0 and run(4, "x") == 10.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, s:obj) -> double {io}
  (def r4
    (block
      (def $try0 var(n))
      (def $try1
        (block
          (try
            (block
              (call name(float) (call-fn parse var(s))))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1.0:float))))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 var(n))
      (def $try3
        (block
          (try
            (block
              (call name(double) (call-fn parse var(s))))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1.0:double))))
      (+:double var($try2) var($try3))))
  (+:double (call name(double) var(r4)) var(r5))
```

```hir
;; hir -O1 fn=run
fn run (n:int, s:obj) -> double {io}
  (def r4
    (block
      (def $try0 var(n))
      (def $try1
        (try
          (block
            (call name(float) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:float)))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 var(n))
      (def $try3
        (try
          (block
            (call name(double) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:double)))
      (+:double var($try2) var($try3))))
  (+:double (call name(double) var(r4)) var(r5))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int, v1: any) -> double:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead() {impure}
    v3: any = opaque.NameRead() {impure}
    v4: int = call parse(v1) {impure}
    v5: any = opaque.DynamicCall(v2, v4) {impure}
    v6: float = convert.float v5
    br bb4(v6)
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: float = const 1.0f
    br bb4(v8)
  bb4(v9: float):
    v10: float = convert.float v0
    v11: float = add v10, v9 {pure}
    br bb5
  bb5 [unwind: BabbleException -> bb6]:
    v12: any = opaque.NameRead() {impure}
    v13: any = opaque.NameRead() {impure}
    v14: int = call parse(v1) {impure}
    v15: any = opaque.DynamicCall(v12, v14) {impure}
    v16: double = convert.double v15
    br bb8(v16)
  bb6(v17: any) [handler]:
    br bb7
  bb7:
    v18: double = const 1.0
    br bb8(v18)
  bb8(v19: double):
    v20: double = convert.double v0
    v21: double = add v20, v19 {pure}
    v22: any = opaque.NameRead() {impure}
    v23: any = opaque.DynamicCall(v22, v11) {impure}
    v24: double = convert.double v23
    v25: double = add v24, v21 {pure}
    ret v25
```

## Control-flow containsTry : If (cond, then, else)

```babble
def run = fn io (s: string, f: bool): int -> (
    def i1 = if (try (parse(s) > 0) catch : exception then (false)) then (1) else (2)
    def i2 = if f then (try (parse(s)) catch : exception then (3)) else (try (parse(s) + 1) catch : exception then (4))
    i1 + i2
)
assert(run("2", false) == 4 and run("bad", true) == 5)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def i1 (if (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool))) (block 1:int) (block 2:int)))
  (def i2
    (if var(f)
      (block
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            3:int)))
      (block
        (try
          (block
            (+:int (call-fn parse var(s)) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))))
  (+:int var(i1) var(i2))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def i1 (if (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)) 1:int 2:int))
  (def i2
    (if var(f)
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          3:int))
      (try
        (block
          (+:int (call-fn parse var(s)) 1:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int))))
  (+:int var(i1) var(i2))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead() {impure}
    v3: int = call parse(v0) {impure}
    v4: int = const 0
    v5: bool = gt v3, v4 {pure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: bool = const false
    br bb4(v7)
  bb4(v8: bool):
    br_if v8, bb6, bb5
  bb5:
    v9: int = const 2
    br bb7(v9)
  bb6:
    v10: int = const 1
    br bb7(v10)
  bb7(v11: int):
    br_if v1, bb13, bb8
  bb8:
    br bb9
  bb9 [unwind: BabbleException -> bb10]:
    v12: any = opaque.NameRead() {impure}
    v13: int = call parse(v0) {impure}
    v14: int = const 1
    v15: int = add v13, v14 {pure}
    br bb12(v15)
  bb10(v16: any) [handler]:
    br bb11
  bb11:
    v17: int = const 4
    br bb12(v17)
  bb12(v18: int):
    br bb18(v18)
  bb13:
    br bb14
  bb14 [unwind: BabbleException -> bb15]:
    v19: any = opaque.NameRead() {impure}
    v20: int = call parse(v0) {impure}
    br bb17(v20)
  bb15(v21: any) [handler]:
    br bb16
  bb16:
    v22: int = const 3
    br bb17(v22)
  bb17(v23: int):
    br bb18(v23)
  bb18(v24: int):
    v25: int = add v11, v24 {pure}
    ret v25
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v2: any = opaque.NameRead() {impure}
    v3: int = call parse(v0) {impure}
    v4: int = const 0
    v5: bool = gt v3, v4 {pure}
    br bb4(v5)
  bb2(v6: any) [handler]:
    br bb3
  bb3:
    v7: bool = const false
    br bb4(v7)
  bb4(v8: bool):
    br_if v8, bb6, bb5
  bb5:
    v9: int = const 2
    br bb7(v9)
  bb6:
    v10: int = const 1
    br bb7(v10)
  bb7(v11: int):
    br_if v1, bb12, bb8
  bb8 [unwind: BabbleException -> bb9]:
    v12: any = opaque.NameRead() {impure}
    v13: int = call parse(v0) {impure}
    v14: int = const 1
    v15: int = add v13, v14 {pure}
    br bb11(v15)
  bb9(v16: any) [handler]:
    br bb10
  bb10:
    v17: int = const 4
    br bb11(v17)
  bb11(v18: int):
    br bb16(v18)
  bb12 [unwind: BabbleException -> bb13]:
    v19: any = opaque.NameRead() {impure}
    v20: int = call parse(v0) {impure}
    br bb15(v20)
  bb13(v21: any) [handler]:
    br bb14
  bb14:
    v22: int = const 3
    br bb15(v22)
  bb15(v23: int):
    br bb16(v23)
  bb16(v24: int):
    v25: int = add v11, v24 {pure}
    ret v25
```

## Control-flow containsTry : While (cond, body)

```babble
def run = fn io (s: string): int -> (
    def w = 0 ; while (try (parse(s) > w) catch : exception then (false)) then (w += 1)
    def wb = 0 ; while wb < 1 then (wb += try (parse(s)) catch : exception then (1))
    w + wb
)
assert(run("2") == 4 and run("bad") == 1)
```

```hir
;; hir -O0 fn=run
fn run (s:obj) -> int {io}
  (def w 0:int)
  (while (block
      (try
        (block
          (>:bool (call-fn parse var(s)) var(w)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)))
    (block (set w += 1:int)))
  (def wb 0:int)
  (while (<:bool var(wb) 1:int)
    (block (set wb +=
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:int)))))
  (+:int var(w) var(wb))
```

```hir
;; hir -O1 fn=run
fn run (s:obj) -> int {io}
  (def w 0:int)
  (while (try
      (block
        (>:bool (call-fn parse var(s)) var(w)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        false:bool))
    (set w += 1:int))
  (def wb 0:int)
  (while (<:bool var(wb) 1:int)
    (set wb +=
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          1:int))))
  (+:int var(w) var(wb))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any) -> int:
  bb0:
    v1: int = const 0
    v2: any = const null
    br bb1(v2, v1)
  bb1(v3: any, v4: int):
    br bb2
  bb2 [unwind: BabbleException -> bb3]:
    v5: any = opaque.NameRead() {impure}
    v6: int = call parse(v0) {impure}
    v7: bool = gt v6, v4 {pure}
    br bb5(v7)
  bb3(v8: any) [handler]:
    br bb4
  bb4:
    v9: bool = const false
    br bb5(v9)
  bb5(v10: bool):
    br_if v10, bb14, bb6
  bb6:
    v11: int = const 0
    v12: any = const null
    br bb7(v12, v11)
  bb7(v13: any, v14: int):
    v15: int = const 1
    v16: bool = lt v14, v15 {pure}
    br_if v16, bb9, bb8
  bb8:
    v17: int = add v4, v14 {pure}
    ret v17
  bb9:
    br bb10
  bb10 [unwind: BabbleException -> bb11]:
    v18: any = opaque.NameRead() {impure}
    v19: int = call parse(v0) {impure}
    br bb13(v19)
  bb11(v20: any) [handler]:
    br bb12
  bb12:
    v21: int = const 1
    br bb13(v21)
  bb13(v22: int):
    v23: int = add v14, v22 {pure}
    v24: any = convert.any v23
    br bb7(v24, v23)
  bb14:
    v25: int = const 1
    v26: int = add v4, v25 {pure}
    v27: any = convert.any v26
    br bb1(v27, v26)
```

```mir
;; mir -O2 fn=run
fn run(v0: any) -> int:
  bb0:
    v1: int = const 0
    br bb1(v1)
  bb1(v2: int):
    br bb2
  bb2 [unwind: BabbleException -> bb3]:
    v3: any = opaque.NameRead() {impure}
    v4: int = call parse(v0) {impure}
    v5: bool = gt v4, v2 {pure}
    br bb5(v5)
  bb3(v6: any) [handler]:
    br bb4
  bb4:
    v7: bool = const false
    br bb5(v7)
  bb5(v8: bool):
    br_if v8, bb13, bb6
  bb6:
    br bb7(v1)
  bb7(v9: int):
    v10: int = const 1
    v11: bool = lt v9, v10 {pure}
    br_if v11, bb9, bb8
  bb8:
    v12: int = add v2, v9 {pure}
    ret v12
  bb9 [unwind: BabbleException -> bb10]:
    v13: any = opaque.NameRead() {impure}
    v14: int = call parse(v0) {impure}
    br bb12(v14)
  bb10(v15: any) [handler]:
    br bb11
  bb11:
    br bb12(v10)
  bb12(v16: int):
    v17: int = add v9, v16 {pure}
    br bb7(v17)
  bb13:
    v18: int = const 1
    v19: int = add v2, v18 {pure}
    br bb1(v19)
```

## Control-flow containsTry : For (iterable, body) and RecordNew classRef

```babble
def Pair = record (a: int, b: int) ()
def run = fn io (s: string, f: bool): int -> (
    def fs = 0
    for e in (try (fail(f, list(1, 2))) catch : exception then (list(3))) then (
        fs += e + (try (parse(s)) catch : exception then (0))
    )
    def p = (try (fail(f, Pair)) catch : exception then (Pair))(1, 2)
    fs + p.a + p.b
)
assert(run("2", false) == 10 and run("bad", true) == 6)
```

```hir
;; hir -O0 fn=run check
contains (def $try0 var(e))
contains (def $try2
contains (record-new Pair 1:int 2:int)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def fs 0:int)
  (for e in (try
      (block
        (call name(fail) var(f) (core-new list 1:int 2:int)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        (core-new list 3:int)))
    (block
      (set fs +=
        (block
          (def $try0 var(e))
          (def $try1
            (try
              (block
                (call-fn parse var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                0:int)))
          (+:int var($try0) var($try1))))))
  (block
    (try
      (block
        (call name(fail) var(f) name(Pair)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        name(Pair)))
    (def $sr_p_0 1:int)
    (def $sr_p_1 2:int))
  (+:int (+:int var(fs) var($sr_p_0)) var($sr_p_1))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 0
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = opaque.NameRead() {impure}
    v4: int = const 1
    v5: int = const 2
    v6: any = opaque.CoreCollectionCall(v4, v5) {impure}
    v7: any = opaque.DynamicCall(v3, v1, v6) {impure}
    br bb4(v7)
  bb2(v8: any) [handler]:
    br bb3
  bb3:
    v9: int = const 3
    v10: any = opaque.CoreCollectionCall(v9) {impure}
    br bb4(v10)
  bb4(v11: any):
    v12: any = for_init.java_iterable v11
    v13: any = const null
    br bb5(v13, v2)
  bb5(v14: any, v15: int):
    v16: bool = for_has_next v12
    br_if v16, bb11, bb6
  bb6:
    br bb7
  bb7 [unwind: BabbleException -> bb8]:
    v17: any = opaque.NameRead() {impure}
    v18: any = opaque.NameRead() {impure}
    v19: any = opaque.DynamicCall(v17, v1, v18) {impure}
    br bb10(v19)
  bb8(v20: any) [handler]:
    br bb9
  bb9:
    v21: any = opaque.NameRead() {impure}
    br bb10(v21)
  bb10(v22: any):
    v23: int = const 1
    v24: int = const 2
    v25: int = add v15, v23 {pure}
    v26: int = add v25, v24 {pure}
    ret v26
  bb11:
    v27: any = for_next v12
    v28: any = checkcast v27 <int|int|>
    v29: int = convert.int v28
    br bb12
  bb12 [unwind: BabbleException -> bb13]:
    v30: any = opaque.NameRead() {impure}
    v31: int = call parse(v0) {impure}
    br bb15(v31)
  bb13(v32: any) [handler]:
    br bb14
  bb14:
    v33: int = const 0
    br bb15(v33)
  bb15(v34: int):
    v35: int = add v29, v34 {pure}
    v36: int = add v15, v35 {pure}
    v37: any = convert.any v36
    br bb5(v37, v36)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: bool) -> int:
  bb0:
    v2: int = const 0
    br bb1
  bb1 [unwind: BabbleException -> bb2]:
    v3: any = opaque.NameRead() {impure}
    v4: int = const 1
    v5: int = const 2
    v6: any = opaque.CoreCollectionCall(v4, v5) {impure}
    v7: any = opaque.DynamicCall(v3, v1, v6) {impure}
    br bb4(v7)
  bb2(v8: any) [handler]:
    br bb3
  bb3:
    v9: int = const 3
    v10: any = opaque.CoreCollectionCall(v9) {impure}
    br bb4(v10)
  bb4(v11: any):
    v12: any = for_init.java_iterable v11
    br bb5(v2)
  bb5(v13: int):
    v14: bool = for_has_next v12
    br_if v14, bb10, bb6
  bb6 [unwind: BabbleException -> bb7]:
    v15: any = opaque.NameRead() {impure}
    v16: any = opaque.NameRead() {impure}
    v17: any = opaque.DynamicCall(v15, v1, v16) {impure}
    br bb9
  bb7(v18: any) [handler]:
    br bb8
  bb8:
    v19: any = opaque.NameRead() {impure}
    br bb9
  bb9:
    v20: int = const 1
    v21: int = const 2
    v22: int = add v13, v20 {pure}
    v23: int = add v22, v21 {pure}
    ret v23
  bb10:
    v24: any = for_next v12
    v25: any = checkcast v24 <int|int|>
    v26: int = convert.int v25
    br bb11
  bb11 [unwind: BabbleException -> bb12]:
    v27: any = opaque.NameRead() {impure}
    v28: int = call parse(v0) {impure}
    br bb14(v28)
  bb12(v29: any) [handler]:
    br bb13
  bb13:
    br bb14(v2)
  bb14(v30: int):
    v31: int = add v26, v30 {pure}
    v32: int = add v13, v31 {pure}
    br bb5(v32)
```
