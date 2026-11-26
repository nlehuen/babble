# Ranges, Counted Loops, and Floored Division Lowering and Optimization

References : `BIP031`, `org.babblelang.opt.hir.{HirEGraphSimplifyPass,HirDcePass}`, `org.babblelang.opt.mir.SccpPass`, `org.babblelang.ir.mir.MirLowerer`.

## Floored mod and integer division constant folding and algebraic identities

```babble
def arith = fn (x: int): int -> (
    def c = (-7 // 3) + (-7 mod 3)
    def id = (x // 1) + (x mod 1) + ((x mod 5) mod 5) + (0 mod 5) + (5 // 5)
    c + id + (x // -1)
)
assert(arith(12) == 2 and arith(-7) == 3)
```

```hir
;; hir -O0 fn=arith
fn arith (x:int) -> int {nothrow unique}
  (def c (+:int (block (//:int -7:int 3:int)) (block (mod:int -7:int 3:int))))
  (def id (+:int (+:int (+:int (+:int (block (//:int var(x) 1:int)) (block (mod:int var(x) 1:int))) (block (mod:int (block (mod:int var(x) 5:int)) 5:int))) (block (mod:int 0:int 5:int))) (block (//:int 5:int 5:int))))
  (+:int (+:int var(c) var(id)) (block (//:int var(x) -1:int)))
```

```hir
;; hir -O1 fn=arith
fn arith (x:int) -> int {nothrow unique}
  (def id (+:int (+:int var(x) (mod:int var(x) 5:int)) 1:int))
  (+:int (+:int var(id) -1:int) (neg var(x)))
```

```mir
;; mir -O2 fn=arith stage=before
fn arith(v0: int) -> int:
  bb0:
    v1: int = const 5
    v2: int = mod v0, v1 {pure}
    v3: int = add v0, v2 {pure}
    v4: int = const 1
    v5: int = add v3, v4 {pure}
    v6: int = const -1
    v7: int = add v5, v6 {pure}
    v8: int = neg v0 {pure}
    v9: int = add v7, v8 {pure}
    ret v9
```

```mir
;; mir -O2 fn=arith
fn arith(v0: int) -> int:
  bb0:
    v1: int = const 5
    v2: int = mod v0, v1 {pure}
    v3: int = add v0, v2 {pure}
    v4: int = const 1
    v5: int = add v3, v4 {pure}
    v6: int = const -1
    v7: int = add v5, v6 {pure}
    v8: int = neg v0 {pure}
    v9: int = add v7, v8 {pure}
    ret v9
```

## Zero-divisor throw folding and dead-code pruning

```babble
def divZero = fn (x: int): int -> (
    def d = 0
    def q = x // d
    q + 999
)
divZero(7)
```

```error
java.lang.ArithmeticException : / by zero
```

```hir
;; hir -O0 fn=divZero
fn divZero (x:int) -> int {pure unique}
  (def d 0:int)
  (def q (//:int var(x) var(d)))
  (+:int var(q) 999:int)
```

```hir
;; hir -O1 fn=divZero
fn divZero (x:int) -> int {pure unique}
  (def q (throw (jnew ArithmeticException "/ by zero")))
  (+:int var(q) 999:int)
```

```mir
;; mir -O2 fn=divZero stage=before
fn divZero(v0: int) -> int:
  bb0:
    v1: any = const "/ by zero"
    v2: any = new_java ArithmeticException(v1) {impure}
    rethrow v2
```

```mir
;; mir -O2 fn=divZero
fn divZero(v0: int) -> int:
  bb0:
    v1: any = const "/ by zero"
    v2: any = new_java ArithmeticException(v1) {impure}
    rethrow v2
```

## Counted range for loop lowering without allocation

```babble
def sumTo = fn (n: int): int -> (
    var acc = 0
    for i in 0..<n then ( acc += i )
    acc
)
assert(sumTo(0) == 0 and sumTo(5) == 10)
```

```hir
;; hir -O0 fn=sumTo
fn sumTo (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
```

```hir
;; hir -O1 fn=sumTo
fn sumTo (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
```

```mir
;; mir -O2 fn=sumTo stage=before
fn sumTo(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb1, bb5(v3, v1)
  bb1:
    v5: int = const 1
    v6: int = sub v0, v5 {pure}
    br bb2(v3, v2, v1)
  bb2(v7: any, v8: int, v9: int):
    v10: int = add v9, v8 {pure}
    v11: any = convert.any v10
    br bb3
  bb3:
    v12: bool = eq v8, v6 {pure}
    br_if v12, bb5(v11, v10), bb4
  bb4:
    v13: int = const 1
    v14: int = add v8, v13 {pure}
    br bb2(v11, v14, v10)
  bb5(v15: any, v16: int):
    ret v16
```

```mir
;; mir -O2 fn=sumTo
fn sumTo(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = lt v1, v0 {pure}
    br_if v2, bb1, bb5(v1)
  bb1:
    v3: int = const 1
    v4: int = sub v0, v3 {pure}
    br bb2(v1, v1)
  bb2(v5: int, v6: int):
    v7: int = add v6, v5 {pure}
    br bb3
  bb3:
    v8: bool = eq v5, v4 {pure}
    br_if v8, bb5(v7), bb4
  bb4:
    v9: int = add v5, v3 {pure}
    br bb2(v9, v7)
  bb5(v10: int):
    ret v10
```

## Stepped and reversed counted range for loop lowering

```babble
def sumRevStep = fn (n: int): int -> (
    var acc = 0
    for i in (3..<n).step(5).reversed() then ( acc += i )
    acc
)
assert(sumRevStep(3) == 0 and sumRevStep(20) == 42)
```

```hir
;; hir -O0 fn=sumRevStep
fn sumRevStep (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 3:int var(n) step 5:int reversed
    (block
      (set acc += var(i))))
  var(acc)
```

```hir
;; hir -O1 fn=sumRevStep
fn sumRevStep (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 3:int var(n) step 5:int reversed
    (block
      (set acc += var(i))))
  var(acc)
```

```mir
;; mir -O2 fn=sumRevStep stage=before
fn sumRevStep(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 3
    v3: any = const null
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb1, bb5(v3, v1)
  bb1:
    v5: int = const 5
    v6: bool = const false
    v7: int = call_java Range.lastIntElement(v2, v0, v6, v5) {pure}
    br bb2(v3, v7, v1)
  bb2(v8: any, v9: int, v10: int):
    v11: int = add v10, v9 {pure}
    v12: any = convert.any v11
    br bb3
  bb3:
    v13: bool = eq v9, v2 {pure}
    br_if v13, bb5(v12, v11), bb4
  bb4:
    v14: int = const 5
    v15: int = sub v9, v14 {pure}
    br bb2(v12, v15, v11)
  bb5(v16: any, v17: int):
    ret v17
```

```mir
;; mir -O2 fn=sumRevStep
fn sumRevStep(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 3
    v3: bool = lt v2, v0 {pure}
    br_if v3, bb1, bb5(v1)
  bb1:
    v4: int = const 5
    v5: bool = const false
    v6: int = call_java Range.lastIntElement(v2, v0, v5, v4) {pure}
    br bb2(v6, v1)
  bb2(v7: int, v8: int):
    v9: int = add v8, v7 {pure}
    br bb3
  bb3:
    v10: bool = eq v7, v2 {pure}
    br_if v10, bb5(v9), bb4
  bb4:
    v11: int = sub v7, v4 {pure}
    br bb2(v11, v9)
  bb5(v12: int):
    ret v12
```

## Inline range contains deforestation and metadata folding

```babble
def checkRange = fn (x: int): bool -> (
    def inc = (1..10).inclusive()
    def empty = (10..<5).isEmpty()
    inc and empty and (3..<20).step(5).contains(x)
)
assert(checkRange(3) and checkRange(18) and not checkRange(19) and not checkRange(20))
```

```hir
;; hir -O0 fn=checkRange
fn checkRange (x:int) -> bool {nothrow unique}
  (def inc (jcall Range.inclusive (block (range .. 1:int 10:int))))
  (def empty (jcall Range.isEmpty (block (range ..< 10:int 5:int))))
  (and (and var(inc) var(empty)) (jcall IntRange.containsInt (jcall IntRange.step (block (range ..< 3:int 20:int)) 5:int) var(x)))
```

```hir
;; hir -O1 fn=checkRange
fn checkRange (x:int) -> bool {nothrow unique}
  (and (and (<=:bool 3:int var(x)) (<:bool var(x) 20:int)) (==:bool (mod:int (-:int var(x) 3:int) 5:int) 0:int))
```

```mir
;; mir -O2 fn=checkRange stage=before
fn checkRange(v0: int) -> bool:
  bb0:
    v1: int = const 3
    v2: bool = lte v1, v0 {pure}
    v3: bool = const false
    br_if v2, bb1, bb2(v3)
  bb1:
    v4: int = const 20
    v5: bool = lt v0, v4 {pure}
    br bb2(v5)
  bb2(v6: bool):
    v7: bool = const false
    br_if v6, bb3, bb4(v7)
  bb3:
    v8: int = const 3
    v9: int = sub v0, v8 {pure}
    v10: int = const 5
    v11: int = mod v9, v10 {pure}
    v12: int = const 0
    v13: bool = eq v11, v12 {pure}
    br bb4(v13)
  bb4(v14: bool):
    ret v14
```

```mir
;; mir -O2 fn=checkRange
fn checkRange(v0: int) -> bool:
  bb0:
    v1: int = const 3
    v2: bool = lte v1, v0 {pure}
    v3: bool = const false
    br_if v2, bb1, bb2(v3)
  bb1:
    v4: int = const 20
    v5: bool = lt v0, v4 {pure}
    br bb2(v5)
  bb2(v6: bool):
    br_if v6, bb3, bb4(v3)
  bb3:
    v7: int = sub v0, v1 {pure}
    v8: int = const 5
    v9: int = mod v7, v8 {pure}
    v10: int = const 0
    v11: bool = eq v9, v10 {pure}
    br bb4(v11)
  bb4(v12: bool):
    ret v12
```

## Dead pure range and terminating counted loop elimination

```babble
def dropPure = fn (n: int): int -> (
    def unused = 0..<n
    def unusedMod = n mod 5
    def unusedLoop = for i in 0..<n then ( def tmp = i * 2 ; tmp )
    42
)
assert(dropPure(10) == 42)
```

```hir
;; hir -O0 fn=dropPure
fn dropPure (n:int) -> int {nothrow unique}
  (def unused (range ..< 0:int var(n)))
  (def unusedMod (mod:int var(n) 5:int))
  (def unusedLoop
    (for-range i ..< 0:int var(n)
      (block
        (def tmp (*:int var(i) 2:int))
        var(tmp))))
  42:int
```

```hir
;; hir -O1 fn=dropPure
fn dropPure (n:int) -> int {nothrow unique}
  42:int
```

```mir
;; mir -O2 fn=dropPure stage=before
fn dropPure(v0: int) -> int:
  bb0:
    v1: int = const 42
    ret v1
```

```mir
;; mir -O2 fn=dropPure
fn dropPure(v0: int) -> int:
  bb0:
    v1: int = const 42
    ret v1
```
