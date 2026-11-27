# `match` HIR, MIR, and WebAssembly lowering & optimizations (`BIP038`)

References : `BIP038-D10` (`HirExpr.Match`, `MirTerminator.Switch`, Wasm `br_table`, `-O1` constant-scrutinee & constant-guard folding, `InlinePass` + `ScalarReplacePass` SROA on `match RecordNew(...)`).

## Dense integer match lowers to MIR switch and Wasm br_table

```babble
def classify = fn (n: int): int -> (
    match n
    case 0 then (10)
    case 1 then (20)
    case 2 then (30)
    case 3 then (40)
    else (0)
)
assert(classify(0) == 10 and classify(2) == 30 and classify(9) == 0)
```

```hir
;; hir -O0 fn=classify
fn classify (n:int) -> int {nothrow}
  (match var(n)
    (case 0:int (block 10:int))
    (case 1:int (block 20:int))
    (case 2:int (block 30:int))
    (case 3:int (block 40:int))
    (else (block 0:int)))
```

```hir
;; hir -O1 fn=classify
fn classify (n:int) -> int {nothrow}
  (match var(n)
    (case 0:int 10:int)
    (case 1:int 20:int)
    (case 2:int 30:int)
    (case 3:int 40:int)
    (else 0:int))
```

```hir
;; hir -O2 fn=classify
fn classify (n:int) -> int {nothrow}
  (match var(n)
    (case 0:int 10:int)
    (case 1:int 20:int)
    (case 2:int 30:int)
    (case 3:int 40:int)
    (else 0:int))
```

```wat
;; wat -O1 check fn=classify
contains br_table
```

## Constant scrutinee and constant guard fold completely at -O1

```babble
def pick = fn (): int -> (
    match 2
    case 0..1 then (10)
    case 2 if true then (42)
    else (0)
)
assert(pick() == 42)
```

```hir
;; hir -O0 fn=pick
fn pick () -> int {nothrow}
  (match 2:int
    (case (range-pat .. 0:int 1:int) (block 10:int))
    (case 2:int if true:bool (block 42:int))
    (else (block 0:int)))
```

```hir
;; hir -O1 fn=pick
fn pick () -> int {nothrow}
  42:int
```

```hir
;; hir -O2 fn=pick
fn pick () -> int {nothrow}
  42:int
```

## Constant guards drop true and prune false arms at -O1

```babble
def guarded = fn (n: int): int -> (
    match n
    case 1 if false then (99)
    case 2 if true then (20)
    else (0)
)
assert(guarded(1) == 0 and guarded(2) == 20 and guarded(5) == 0)
```

```hir
;; hir -O0 fn=guarded
fn guarded (n:int) -> int {nothrow}
  (match var(n)
    (case 1:int if false:bool (block 99:int))
    (case 2:int if true:bool (block 20:int))
    (else (block 0:int)))
```

```hir
;; hir -O1 fn=guarded
fn guarded (n:int) -> int {nothrow}
  (match var(n)
    (case 2:int 20:int)
    (else 0:int))
```

```hir
;; hir -O2 fn=guarded
fn guarded (n:int) -> int {nothrow}
  (match var(n)
    (case 2:int 20:int)
    (else 0:int))
```

## Inlined match on fresh record SROA eliminates allocation

```babble
def Circle = record (r: int) ()
def Rect = record (w: int, h: int) ()
def area = fn (s: Circle | Rect): int -> (
    match s
    case is Circle(def r) then (r * r * 3)
    case is Rect(def w, def h) then (w * h)
)
def circleArea = fn (r: int): int -> (area(Circle(r)))
assert(circleArea(4) == 48 and area(Rect(3, 5)) == 15)
```

```hir
;; hir -O0 fn=circleArea
fn circleArea (r:int) -> int {nothrow unique}
  (call-fn area (record-new Circle var(r)))
```

```hir
;; hir -O1 fn=circleArea
fn circleArea (r:int) -> int {nothrow unique}
  (block
    (block (def $sr_s_0 var(r#1)))
    (block
      (def r#2 var($sr_s_0))
      (*:int (*:int var(r#2) var(r#2)) 3:int)))
```

```hir
;; hir -O2 fn=circleArea
fn circleArea (r:int) -> int {nothrow unique}
  (block
    (def r#2 var(r#1))
    (*:int (*:int var(r#1) var(r#1)) 3:int))
```

## Direct match on RecordNew folds without allocation

```babble
def Point = record (x: int, y: int) ()
def sumPt = fn (a: int, b: int): int -> (
    match Point(a, b)
    case is Point(def x, def y) then (x + y)
)
assert(sumPt(17, 25) == 42)
```

```hir
;; hir -O0 fn=sumPt
fn sumPt (a:int, b:int) -> int {nothrow unique}
  (match (record-new Point var(a) var(b))
    (case (record-pat Point x=(bind x) y=(bind y)) (block (+:int var(x) var(y)))))
```

```hir
;; hir -O1 fn=sumPt
fn sumPt (a:int, b:int) -> int {nothrow unique}
  (block
    (def x var(a))
    (def y var(b))
    (+:int var(x) var(y)))
```

```hir
;; hir -O2 fn=sumPt
fn sumPt (a:int, b:int) -> int {nothrow unique}
  (block
    (def x var(a))
    (def y var(b))
    (+:int var(a) var(b)))
```
