# E-Graph Joint Saturation, Range Clamping, Monotone Early Exit & Associative Pairwise Fold

References : BIP042-P3, BIP014-P5, org.babblelang.opt.hir.PipelineRewritePass, org.babblelang.ir.egraph.PipelineRules

```babble
;; babble preamble
def encode = fn axiom pure nothrow monotone injective (x: int): double -> (
    def r = x * 10.0 + 5.0
    return r
    r
)

def stepMono = fn axiom pure nothrow monotone (x: int): int -> (
    def r = x // 2
    return r
    r
)

def combineMax = fn axiom pure nothrow associative (a: int, b: int): int -> (
    def r = if a > b then ( a ) else ( b )
    return r
    r
)

def firstNonZero = fn axiom pure nothrow associative (a: int, b: int): int -> (
    def r = if a != 0 then ( a ) else ( b )
    return r
    r
)

def combineSub = fn axiom pure nothrow (a: int, b: int): int -> (
    def r = a - b
    return r
    r
)
```

## Five-step joint saturation (R1 + R4b + AlgebraicRules + R6 + $min fold)

```babble
def jointSat = fn (): double -> (
    def scores = list of double ()
    for x in 0..5000 then ( scores.add(encode(x)) )
    var total = 0.0
    for s in scores then (
        if s <= encode(50) then ( total += s )
    )
    total
)
assert(jointSat() == 13005.0)
```

```hir
;; hir -O0 fn=jointSat
fn jointSat () -> double {pure unique}
  (def scores (core-new list))
  (for-range x .. 0:int 5000:int
    (block
      (jcall List.add var(scores) (call-fn encode var(x)))))
  (def total 0.0:double)
  (for s in var(scores)
    (block
      (if (<=:bool var(s) (call-fn encode 50:int)) (box (block (set total += var(s)))))))
  var(total)
```

```hir
;; hir -O1 fn=jointSat
fn jointSat () -> double {pure unique}
  (def scores (core-new list))
  (for-range x .. 0:int 5000:int
    (block
      (jcall List.add var(scores) (block
          (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
          var(r)))))
  (def total 0.0:double)
  (for s in var(scores)
    (block
      (if (<=:bool var(s) 505.0:double) (box (set total += var(s))))))
  var(total)
```

```hir
;; hir -O2 fn=jointSat
fn jointSat () -> double {pure unique}
  (def total 0.0:double)
  (for-range x .. 0:int 50:int
    (block
      (def s#0
        (block
          (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
          var(r)))
      (set total += var(s#0))))
  var(total)
```

## Direct range clamping: inclusive dynamic bounds (R6)

```babble
def clampRange = fn (lo: int, hi: int, ub: int): int -> (
    var acc = 0
    for x in lo..hi then (
        if x >= 2 and x <= ub then ( acc += x )
    )
    acc
)
assert(clampRange(0, 100, 5) == 14 and clampRange(10, 5, 20) == 0)
```

```hir
;; hir -O0 fn=clampRange
fn clampRange (lo:int, hi:int, ub:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. var(lo) var(hi)
    (block
      (if (and (>=:bool var(x) 2:int) (<=:bool var(x) var(ub))) (box (block (set acc += var(x)))))))
  var(acc)
```

```hir
;; hir -O1 fn=clampRange
fn clampRange (lo:int, hi:int, ub:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. var(lo) var(hi)
    (block
      (if (and (>=:bool var(x) 2:int) (<=:bool var(x) var(ub))) (box (set acc += var(x))))))
  var(acc)
```

```hir
;; hir -O2 fn=clampRange
fn clampRange (lo:int, hi:int, ub:int) -> int {nothrow unique}
  (def acc 0:int)
  (block
    (def $max#0 (if (<:bool var(lo) 2:int) 2:int var(lo)))
    (def $min#1 (if (<:bool var(hi) var(ub)) var(hi) var(ub)))
    (for-range x .. var($max#0) var($min#1)
      (block
        (set acc += var(x)))))
  var(acc)
```

## Direct range clamping: exclusive bounds (R6)

```babble
def clampExcl = fn (n: int): int -> (
    var acc = 0
    for x in 0..<n then ( if x < 5 and x > 0 then ( acc += x ) )
    acc
)
assert(clampExcl(100) == 10 and clampExcl(3) == 3)
```

```hir
;; hir -O0 fn=clampExcl
fn clampExcl (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x ..< 0:int var(n)
    (block
      (if (and (<:bool var(x) 5:int) (>:bool var(x) 0:int)) (box (block (set acc += var(x)))))))
  var(acc)
```

```hir
;; hir -O1 fn=clampExcl
fn clampExcl (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x ..< 0:int var(n)
    (block
      (if (and (<:bool var(x) 5:int) (>:bool var(x) 0:int)) (box (set acc += var(x))))))
  var(acc)
```

```hir
;; hir -O2 fn=clampExcl
fn clampExcl (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (block
    (def $min#0 (if (<:bool var(n) 5:int) var(n) 5:int))
    (for-range x ..< 1:int var($min#0)
      (block
        (set acc += var(x)))))
  var(acc)
```

## Direct range clamping: MIN/MAX boundary bailouts (R6)

```babble
def clampEdge = fn (): int -> (
    var h = 0
    for x in 2147483645..2147483647 then ( if x > 2147483647 then ( h += 1 ) )
    for x in -2147483648..-2147483646 then ( if x < -2147483648 then ( h += 1 ) )
    h
)
assert(clampEdge() == 0)
```

```hir
;; hir -O0 fn=clampEdge
fn clampEdge () -> int {nothrow unique}
  (def h 0:int)
  (for-range x#1 .. 2147483645:int 2147483647:int
    (block
      (if (>:bool var(x#1) 2147483647:int) (box (block (set h += 1:int))))))
  (for-range x#2 .. -2147483648:int -2147483646:int
    (block
      (if (<:bool var(x#2) -2147483648:int) (box (block (set h += 1:int))))))
  var(h)
```

```hir
;; hir -O1 fn=clampEdge
fn clampEdge () -> int {nothrow unique}
  (def h 0:int)
  (for-range x#1 .. 2147483645:int 2147483647:int
    (block
      (if (>:bool var(x#1) 2147483647:int) (box (set h += 1:int)))))
  (for-range x#2 .. -2147483648:int -2147483646:int
    (block
      (if (<:bool var(x#2) -2147483648:int) (box (set h += 1:int)))))
  var(h)
```

```hir
;; hir -O2 fn=clampEdge
fn clampEdge () -> int {nothrow unique}
  (def h 0:int)
  (for-range x#1 .. 2147483645:int 2147483647:int
    (block
      (if (>:bool var(x#1) 2147483647:int) (box (set h += 1:int)))))
  (for-range x#2 .. -2147483648:int -2147483646:int
    (block
      (if (<:bool var(x#2) -2147483648:int) (box (set h += 1:int)))))
  var(h)
```

## Monotone ascending and descending early exit (R7 PIPE_TAKE_WHILE)

```babble
def monoTake = fn (): int -> (
    var acc = 0
    for x in 0..5000 then ( if encode(x) <= 55.0 then ( acc += x ) )
    acc
)
def monoDesc = fn (): int -> (
    var acc = 0
    for x in (0..20).reversed() then ( if stepMono(x) >= 8 then ( acc += x ) )
    acc
)
assert(monoTake() == 15 and monoDesc() == 90)
```

```hir
;; hir -O0 fn=monoTake
fn monoTake () -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. 0:int 5000:int
    (block
      (if (<=:bool (call-fn encode var(x)) 55.0:double) (box (block (set acc += var(x)))))))
  var(acc)
```

```hir
;; hir -O1 fn=monoTake
fn monoTake () -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. 0:int 5000:int
    (block
      (if (<=:bool (block
            (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
            var(r)) 55.0:double) (box (set acc += var(x))))))
  var(acc)
```

```hir
;; hir -O2 fn=monoTake
fn monoTake () -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. 0:int 5000:int
    (block
      (if (<=:bool (block
            (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
            var(r)) 55.0:double) (box (set acc += var(x))) (break))))
  var(acc)
```

```hir
;; hir -O2 fn=monoDesc
fn monoDesc () -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. 0:int 20:int reversed
    (block
      (if (>=:bool (block
            (def r (//:int var(x) 2:int))
            var(r)) 8:int) (box (set acc += var(x))) (break))))
  var(acc)
```

## Monotone O(1) whole-range pre-check (R7 PIPE_GUARD_RANGE)

```babble
def monoGuard = fn (lo: int, hi: int, thresh: double): int -> (
    var acc = 0
    for x in lo..hi then (
        if encode(x) >= thresh then ( acc += x )
    )
    acc
)
assert(monoGuard(0, 10, 5000.0) == 0 and monoGuard(0, 10, 85.0) == 27 and monoGuard(10, 5, 0.0) == 0)
```

```hir
;; hir -O0 fn=monoGuard
fn monoGuard (lo:int, hi:int, thresh:double) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. var(lo) var(hi)
    (block
      (if (>=:bool (call-fn encode var(x)) var(thresh)) (box (block (set acc += var(x)))))))
  var(acc)
```

```hir
;; hir -O1 fn=monoGuard
fn monoGuard (lo:int, hi:int, thresh:double) -> int {nothrow unique}
  (def acc 0:int)
  (for-range x .. var(lo) var(hi)
    (block
      (if (>=:bool (block
            (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
            var(r)) var(thresh)) (box (set acc += var(x))))))
  var(acc)
```

```hir
;; hir -O2 fn=monoGuard
fn monoGuard (lo:int, hi:int, thresh:double) -> int {nothrow unique}
  (def acc 0:int)
  (if (and (<=:bool var(lo) var(hi)) (>=:bool (block
          (def r#1 (+:double (*:double (convert[double] var(hi)) 10.0:double) 5.0:double))
          var(r#1)) var(thresh)))
    (for-range x .. var(lo) var(hi)
      (block
        (if (>=:bool (block
              (def r#2 (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
              var(r#2)) var(thresh)) (box (set acc += var(x)))))))
  var(acc)
```

## Associative pairwise fold: exclusive non-commutative (R8)

```babble
def foldExcl = fn (lo: int, hi: int): int -> (
    var acc = 0
    for i in lo..<hi then ( acc = firstNonZero(acc, i) )
    acc
)
assert(foldExcl(0, 0) == 0 and foldExcl(0, 1) == 0 and foldExcl(0, 2) == 1 and foldExcl(0, 5) == 1)
assert(foldExcl(-2147483648, -2147483648) == 0 and foldExcl(2147483646, 2147483647) == 2147483646)
```

```hir
;; hir -O0 fn=foldExcl
fn foldExcl (lo:int, hi:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< var(lo) var(hi)
    (block
      (set acc (call-fn firstNonZero var(acc) var(i)))))
  var(acc)
```

```hir
;; hir -O1 fn=foldExcl
fn foldExcl (lo:int, hi:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< var(lo) var(hi)
    (block
      (set acc
        (block
          (def r (if (<>:bool var(acc) 0:int) var(acc) var(i)))
          var(r)))))
  var(acc)
```

```hir
;; hir -O2 fn=foldExcl
fn foldExcl (lo:int, hi:int) -> int {nothrow unique}
  (def acc 0:int)
  (block
    (def $i#2 var(lo))
    (block
      (def $licm0 (-:int var(hi) 1:int))
      (while (and (<:bool var($i#2) var(hi)) (<:bool var($i#2) var($licm0)))
        (block
          (set acc
            (block
              (def b#1
                (block
                  (def b#2 (+:int var($i#2) 1:int))
                  (def r#2 (if (<>:bool var($i#2) 0:int) var($i#2) var(b#2)))
                  var(r#2)))
              (def r#1 (if (<>:bool var(acc) 0:int) var(acc) var(b#1)))
              var(r#1)))
          (box (set $i#2 (+:int var($i#2) 2:int))))))
    (if (<:bool var($i#2) var(hi)) (box (set acc
          (block
            (def r#3 (if (<>:bool var(acc) 0:int) var(acc) var($i#2)))
            var(r#3))))))
  var(acc)
```

## Associative pairwise fold: inclusive and MAX_VALUE boundary (R8)

```babble
def foldIncl = fn (lo: int, hi: int): int -> (
    var acc = -2147483648
    for i in lo..hi then ( acc = combineMax(acc, i) )
    acc
)
assert(foldIncl(5, 3) == -2147483648 and foldIncl(7, 7) == 7 and foldIncl(0, 1) == 1 and foldIncl(0, 5) == 5)
assert(foldIncl(2147483646, 2147483647) == 2147483647 and foldIncl(2147483647, 2147483647) == 2147483647)
```

```hir
;; hir -O0 fn=foldIncl
fn foldIncl (lo:int, hi:int) -> int {nothrow unique}
  (def acc -2147483648:int)
  (for-range i .. var(lo) var(hi)
    (block
      (set acc (call-fn combineMax var(acc) var(i)))))
  var(acc)
```

```hir
;; hir -O1 fn=foldIncl
fn foldIncl (lo:int, hi:int) -> int {nothrow unique}
  (def acc -2147483648:int)
  (for-range i .. var(lo) var(hi)
    (block
      (set acc
        (block
          (def r (if (>:bool var(acc) var(i)) var(acc) var(i)))
          var(r)))))
  var(acc)
```

```hir
;; hir -O2 fn=foldIncl
fn foldIncl (lo:int, hi:int) -> int {nothrow unique}
  (def acc -2147483648:int)
  (block
    (def $i#2 var(lo))
    (block
      (def $licm0 (-:int var(hi) 1:int))
      (while (and (<:bool var($i#2) var(hi)) (<:bool var($i#2) var($licm0)))
        (block
          (set acc
            (block
              (def b#1
                (block
                  (def b#2 (+:int var($i#2) 1:int))
                  (def r#2 (if (>:bool var($i#2) var(b#2)) var($i#2) var(b#2)))
                  var(r#2)))
              (def r#1 (if (>:bool var(acc) var(b#1)) var(acc) var(b#1)))
              var(r#1)))
          (box (set $i#2 (+:int var($i#2) 2:int))))))
    (if (<:bool var($i#2) var(hi)) (box (set acc
          (block
            (def b#3
              (block
                (def b#4 (+:int var($i#2) 1:int))
                (def r#4 (if (>:bool var($i#2) var(b#4)) var($i#2) var(b#4)))
                var(r#4)))
            (def r#3 (if (>:bool var(acc) var(b#3)) var(acc) var(b#3)))
            var(r#3)))) (if (==:bool var($i#2) var(hi)) (box (set acc
            (block
              (def r#5 (if (>:bool var(acc) var($i#2)) var(acc) var($i#2)))
              var(r#5)))))))
  var(acc)
```

## Associative fold bailouts: non-associative and self-referencing acc

```babble
def foldBailout = fn (n: int): int -> (
    var a = 100
    for i in 0..<n then ( a = combineSub(a, i) )
    var b = 1
    for i in 0..<n then ( b = combineMax(b, b + i) )
    a + b
)
assert(foldBailout(5) == 101)
```

```hir
;; hir -O0 fn=foldBailout
fn foldBailout (n:int) -> int {nothrow unique}
  (def a 100:int)
  (for-range i#1 ..< 0:int var(n)
    (block
      (set a (call-fn combineSub var(a) var(i#1)))))
  (def b 1:int)
  (for-range i#2 ..< 0:int var(n)
    (block
      (set b (call-fn combineMax var(b) (+:int var(b) var(i#2))))))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=foldBailout
fn foldBailout (n:int) -> int {nothrow unique}
  (def a 100:int)
  (for-range i#1 ..< 0:int var(n)
    (block
      (set a
        (block
          (def r#1 (-:int var(a) var(i#1)))
          var(r#1)))))
  (def b#1 1:int)
  (for-range i#2 ..< 0:int var(n)
    (block
      (set b#1
        (block
          (def b#2 (+:int var(b#1) var(i#2)))
          (def r#2 (if (>:bool var(b#1) var(b#2)) var(b#1) var(b#2)))
          var(r#2)))))
  (+:int var(a) var(b#1))
```

```hir
;; hir -O2 fn=foldBailout
fn foldBailout (n:int) -> int {nothrow unique}
  (def a 100:int)
  (for-range i#1 ..< 0:int var(n)
    (block
      (set a
        (block
          (def r#1 (-:int var(a) var(i#1)))
          var(r#1)))))
  (def b#1 1:int)
  (for-range i#2 ..< 0:int var(n)
    (block
      (set b#1
        (block
          (def b#2 (+:int var(b#1) var(i#2)))
          (def r#2 (if (>:bool var(b#1) var(b#2)) var(b#1) var(b#2)))
          var(r#2)))))
  (+:int var(a) var(b#1))
```

## Long range clamping with reversed bounds and dynamic upper hoist (R6)

```babble
def clampLong = fn (n: long): long -> (
    var s = 0L
    for x in 0L..(n + 2L) then (
        if 2L <= x and x <= 10L and x <= 5L then ( s += x )
    )
    s
)
assert(clampLong(20L) == 14L and clampLong(1L) == 5L and clampLong(-5L) == 0L)
```

```hir
;; hir -O0 fn=clampLong
fn clampLong (n:long) -> long {nothrow unique}
  (def s 0:long)
  (for-range x .. 0:long (block (+:long var(n) 2:long))
    (block
      (if (and (and (<=:bool 2:long var(x)) (<=:bool var(x) 10:long)) (<=:bool var(x) 5:long)) (box (block (set s += var(x)))))))
  var(s)
```

```hir
;; hir -O1 fn=clampLong
fn clampLong (n:long) -> long {nothrow unique}
  (def s 0:long)
  (for-range x .. 0:long (+:long var(n) 2:long)
    (block
      (if (and (and (<=:bool 2:long var(x)) (<=:bool var(x) 10:long)) (<=:bool var(x) 5:long)) (box (set s += var(x))))))
  var(s)
```

```hir
;; hir -O2 fn=clampLong
fn clampLong (n:long) -> long {nothrow unique}
  (def s 0:long)
  (block
    (def $a#0 (+:long var(n) 2:long))
    (def $min#1 (if (<:bool var($a#0) 5:long) var($a#0) 5:long))
    (for-range x .. 2:long var($min#1)
      (block
        (set s += var(x)))))
  var(s)
```

## Exclusive <= and inclusive < range bound clamping (R6)

```babble
def clampStrict = fn (n: int): int -> (
    var a = 0
    for x in 0..<n then ( if x <= 5 then ( a += x ) )
    var b = 0
    for y in 0..n then ( if y < 5 then ( b += y ) )
    a + b
)
assert(clampStrict(20) == 25 and clampStrict(3) == 9 and clampStrict(0) == 0)
```

```hir
;; hir -O0 fn=clampStrict
fn clampStrict (n:int) -> int {nothrow unique}
  (def a 0:int)
  (for-range x ..< 0:int var(n)
    (block
      (if (<=:bool var(x) 5:int) (box (block (set a += var(x)))))))
  (def b 0:int)
  (for-range y .. 0:int var(n)
    (block
      (if (<:bool var(y) 5:int) (box (block (set b += var(y)))))))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=clampStrict
fn clampStrict (n:int) -> int {nothrow unique}
  (def a 0:int)
  (for-range x ..< 0:int var(n)
    (block
      (if (<=:bool var(x) 5:int) (box (set a += var(x))))))
  (def b 0:int)
  (for-range y .. 0:int var(n)
    (block
      (if (<:bool var(y) 5:int) (box (set b += var(y))))))
  (+:int var(a) var(b))
```

```hir
;; hir -O2 fn=clampStrict
fn clampStrict (n:int) -> int {nothrow unique}
  (def a 0:int)
  (block
    (def $min#0#1 (if (<:bool var(n) 6:int) var(n) 6:int))
    (for-range x ..< 0:int var($min#0#1)
      (block
        (set a += var(x)))))
  (def b 0:int)
  (block
    (def $min#0#2 (if (<:bool var(n) 4:int) var(n) 4:int))
    (for-range y .. 0:int var($min#0#2)
      (block
        (set b += var(y)))))
  (+:int var(a) var(b))
```

## Java static associative fold over counted range (R8)

```babble
axiom java.lang.Math.max: fn pure nothrow associative commutative (int, int): int
def javaFold = fn (n: int): int -> (
    var m = -1
    for i in 0..<n then ( m = java.lang.Math.max(m, i) )
    m
)
assert(javaFold(0) == -1 and javaFold(1) == 0 and javaFold(10) == 9)
```

```hir
;; hir -O0 fn=javaFold
fn javaFold (n:int) -> int {nothrow unique}
  (def m -1:int)
  (for-range i ..< 0:int var(n)
    (block
      (set m (jcall Math.max var(m) var(i)))))
  var(m)
```

```hir
;; hir -O1 fn=javaFold
fn javaFold (n:int) -> int {nothrow unique}
  (def m -1:int)
  (for-range i ..< 0:int var(n)
    (block
      (set m (jcall Math.max var(m) var(i)))))
  var(m)
```

```hir
;; hir -O2 fn=javaFold
fn javaFold (n:int) -> int {nothrow unique}
  (def m -1:int)
  (block
    (def $i#2 0:int)
    (block
      (def $licm0 (-:int var(n) 1:int))
      (while (and (<:bool var($i#2) var(n)) (<:bool var($i#2) var($licm0)))
        (block
          (set m (jcall Math.max var(m) (jcall Math.max var($i#2) (+:int var($i#2) 1:int))))
          (box (set $i#2 (+:int var($i#2) 2:int))))))
    (if (<:bool var($i#2) var(n)) (box (set m (jcall Math.max var(m) var($i#2))))))
  var(m)
```

