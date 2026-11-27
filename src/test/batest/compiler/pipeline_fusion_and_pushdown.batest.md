# E-Graph Pipeline Fusion, Filter Reordering, Map Push-Down & Distinct Push-Down

References : BIP042-P3, BIP014-P5, org.babblelang.opt.hir.PipelineRewritePass, org.babblelang.ir.egraph.PipelineRules

```babble
;; babble preamble
def encode = fn axiom pure nothrow monotone injective (x: int): double -> (
    def r = x * 10.0 + 5.0
    return r
    r
)

def clampPos = fn axiom pure nothrow monotone (x: int): int -> (
    def r = if x < 0 then ( 0 ) else ( x )
    return r
    r
)

def slowCheck = fn axiom pure nothrow (x: int): bool -> (
    def r = (x * 7 + 3) mod 5 == 0
    return r
    r
)
```

## Multi-stage producer-consumer list fusion (R1)

```babble
def sumPipeline = fn (n: int): int -> (
    def evens = list of int ()
    for x in 0..<n then ( if x mod 2 == 0 then ( evens.add(x) ) )
    def mapped = list of int ()
    for y in evens then ( mapped.add(y * 3 + 1) )
    var total = 0
    for z in mapped then ( total += z )
    total
)
assert(sumPipeline(0) == 0 and sumPipeline(1) == 1 and sumPipeline(10) == 65)
```

```hir
;; hir -O0 fn=sumPipeline
fn sumPipeline (n:int) -> int {pure unique}
  (def evens (core-new list))
  (for-range x ..< 0:int var(n)
    (block
      (if (==:bool (mod:int var(x) 2:int) 0:int) (box (block (jcall List.add var(evens) var(x)))))))
  (def mapped (core-new list))
  (for y in var(evens)
    (block
      (jcall List.add var(mapped) (+:int (*:int var(y) 3:int) 1:int))))
  (def total 0:int)
  (for z in var(mapped)
    (block
      (set total += var(z))))
  var(total)
```

```hir
;; hir -O1 fn=sumPipeline
fn sumPipeline (n:int) -> int {pure unique}
  (def evens (core-new list))
  (for-range x ..< 0:int var(n)
    (block
      (if (==:bool (mod:int var(x) 2:int) 0:int) (box (jcall List.add var(evens) var(x))))))
  (def mapped (core-new list))
  (for y in var(evens)
    (block
      (jcall List.add var(mapped) (+:int (*:int var(y) 3:int) 1:int))))
  (def total 0:int)
  (for z in var(mapped)
    (block
      (set total += var(z))))
  var(total)
```

```hir
;; hir -O2 fn=sumPipeline
fn sumPipeline (n:int) -> int {pure unique}
  (def total 0:int)
  (for-range x ..< 0:int var(n)
    (block
      (if (==:bool (mod:int var(x) 2:int) 0:int)
        (block
          (def z#0 (+:int (*:int var(x) 3:int) 1:int))
          (box (set total += var(z#0)))))))
  var(total)
```

## Nested filter fusion and guard-safe cost reordering (R2, R3)

```babble
def reorderFilters = fn (xs: list of int): int -> (
    var acc = 0
    for x in xs then (
        if slowCheck(x) then ( if x > 0 then ( acc += x ) )
    )
    acc
)
assert(reorderFilters(list of int (-11, -6, 0, 1, 6, 11, 16)) == 34)
```

```hir
;; hir -O0 fn=reorderFilters
fn reorderFilters (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (call-fn slowCheck var(x)) (block (if (>:bool var(x) 0:int) (box (block (set acc += var(x)))))))))
  var(acc)
```

```hir
;; hir -O1 fn=reorderFilters
fn reorderFilters (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (>:bool var(x) 0:int) (if (block
            (def r (==:bool (mod:int (+:int (*:int var(x) 7:int) 3:int) 5:int) 0:int))
            var(r)) (box (set acc += var(x)))))))
  var(acc)
```

```hir
;; hir -O2 fn=reorderFilters
fn reorderFilters (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (and (>:bool var(x) 0:int) (block
            (def r (==:bool (mod:int (+:int (*:int var(x) 7:int) 3:int) 5:int) 0:int))
            var(r))) (box (set acc += var(x))))))
  var(acc)
```

## Guarded and throwing filter bailout (R3 preserves guard order)

```babble
def guardedFilters = fn (xs: list of int, ss: list of string?): int -> (
    var acc = 0
    for x in xs then ( if x != 0 then ( if 100 // x > 5 then ( acc += x ) ) )
    for s in ss then ( if s != null then ( if s.length() > 1 then ( acc += s.length() ) ) )
    acc
)
assert(guardedFilters(list of int (0, 5, 10, 20), list of string? (null, "a", "hi", "abc")) == 20)
```

```hir
;; hir -O0 fn=guardedFilters
fn guardedFilters (xs:obj, ss:obj) -> int {pure unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<>:bool var(x) 0:int) (block (if (>:bool (//:int 100:int var(x)) 5:int) (box (block (set acc += var(x)))))))))
  (for s in var(ss)
    (block
      (if (<>:bool var(s) null) (block (if (>:bool (jcall String.length (narrow.nonnull var(s))) 1:int) (box (block (set acc += (jcall String.length (narrow.nonnull var(s)))))))))))
  var(acc)
```

```hir
;; hir -O1 fn=guardedFilters
fn guardedFilters (xs:obj, ss:obj) -> int {pure unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<>:bool var(x) 0:int) (if (>:bool (//:int 100:int var(x)) 5:int) (box (set acc += var(x)))))))
  (for s in var(ss)
    (block
      (if (<>:bool var(s) null) (if (>:bool (jcall String.length (narrow.nonnull var(s))) 1:int) (box (set acc += (jcall String.length (narrow.nonnull var(s)))))))))
  var(acc)
```

```hir
;; hir -O2 fn=guardedFilters
fn guardedFilters (xs:obj, ss:obj) -> int {pure unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (and (<>:bool var(x) 0:int) (>:bool (//:int 100:int var(x)) 5:int)) (box (set acc += var(x))))))
  (for s in var(ss)
    (block
      (if (and (<>:bool var(s) null) (>:bool (jcall String.length (narrow.nonnull var(s))) 1:int)) (box (set acc += (jcall String.length (narrow.nonnull var(s))))))))
  var(acc)
```

## Map-filter push-down (R4 Case 4a: binder-independent predicate)

```babble
def pushDownMap = fn (xs: list of int): double -> (
    var total = 0.0
    for x in xs then (
        def y = encode(x)
        if x > 0 then ( total += y )
    )
    total
)
assert(pushDownMap(list of int (-3, 0, 1, 2)) == 40.0)
```

```hir
;; hir -O0 fn=pushDownMap
fn pushDownMap (xs:obj) -> double {nothrow unique}
  (def total 0.0:double)
  (for x in var(xs)
    (block
      (def y (call-fn encode var(x)))
      (if (>:bool var(x) 0:int) (box (block (set total += var(y)))))))
  var(total)
```

```hir
;; hir -O1 fn=pushDownMap
fn pushDownMap (xs:obj) -> double {nothrow unique}
  (def total 0.0:double)
  (for x in var(xs)
    (block
      (def y
        (block
          (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
          var(r)))
      (if (>:bool var(x) 0:int) (box (set total += var(y))))))
  var(total)
```

```hir
;; hir -O2 fn=pushDownMap
fn pushDownMap (xs:obj) -> double {nothrow unique}
  (def total 0.0:double)
  (for x in var(xs)
    (block
      (if (>:bool var(x) 0:int)
        (block
          (def y
            (block
              (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
              var(r)))
          (box (set total += var(y)))))))
  var(total)
```

## Injective distinct push-down (R5)

```babble
def distinctPush = fn (xs: list of int): int -> (
    def seen = set of double ()
    def out = list of double ()
    for x in xs then (
        def y = encode(x)
        if seen.add(y) then ( out.add(y) )
    )
    out.size()
)
assert(distinctPush(list of int (1, 2, 1, 3, 2)) == 3)
```

```hir
;; hir -O0 fn=distinctPush
fn distinctPush (xs:obj) -> int {pure unique}
  (def seen (core-new set))
  (def out (core-new list))
  (for x in var(xs)
    (block
      (def y (call-fn encode var(x)))
      (if (jcall Set.add var(seen) var(y)) (box (block (jcall List.add var(out) var(y)))))))
  (jcall List.size var(out))
```

```hir
;; hir -O1 fn=distinctPush
fn distinctPush (xs:obj) -> int {pure unique}
  (def seen (core-new set))
  (def out (core-new list))
  (for x in var(xs)
    (block
      (def y
        (block
          (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
          var(r)))
      (if (jcall Set.add var(seen) var(y)) (box (jcall List.add var(out) var(y))))))
  (jcall List.size var(out))
```

```hir
;; hir -O2 fn=distinctPush
fn distinctPush (xs:obj) -> int {pure unique}
  (def seen (core-new set))
  (def out (core-new list))
  (for x in var(xs)
    (block
      (if (jcall Set.add var(seen) (box var(x)))
        (block
          (def y
            (block
              (def r (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
              var(r)))
          (box (jcall List.add var(out) var(y)))))))
  (jcall List.size var(out))
```

## Distinct bailouts: non-injective map and escaping seen set

```babble
def distinctBailout = fn (xs: list of int): int -> (
    def s1 = set of int ()
    var c1 = 0
    for x in xs then ( def y = clampPos(x) ; if s1.add(y) then ( c1 += 1 ) )
    def s2 = set of double ()
    var c2 = 0
    for x in xs then ( def y = encode(x) ; if s2.add(y) then ( c2 += 1 ) )
    c1 + c2 + s2.size()
)
assert(distinctBailout(list of int (-5, -2, 0, 1, 1)) == 10)
```

```hir
;; hir -O0 fn=distinctBailout
fn distinctBailout (xs:obj) -> int {pure unique}
  (def s1 (core-new set))
  (def c1 0:int)
  (for x#1 in var(xs)
    (block
      (def y#1 (call-fn clampPos var(x#1)))
      (if (jcall Set.add var(s1) var(y#1)) (box (block (set c1 += 1:int))))))
  (def s2 (core-new set))
  (def c2 0:int)
  (for x#2 in var(xs)
    (block
      (def y#2 (call-fn encode var(x#2)))
      (if (jcall Set.add var(s2) var(y#2)) (box (block (set c2 += 1:int))))))
  (+:int (+:int var(c1) var(c2)) (jcall Set.size var(s2)))
```

```hir
;; hir -O1 fn=distinctBailout
fn distinctBailout (xs:obj) -> int {pure unique}
  (def s1 (core-new set))
  (def c1 0:int)
  (for x#1 in var(xs)
    (block
      (def y#1
        (block
          (def r#1 (if (<:bool var(x#1) 0:int) 0:int var(x#1)))
          var(r#1)))
      (if (jcall Set.add var(s1) var(y#1)) (box (set c1 += 1:int)))))
  (def s2 (core-new set))
  (def c2 0:int)
  (for x#2 in var(xs)
    (block
      (def y#2
        (block
          (def r#2 (+:double (*:double (convert[double] var(x#2)) 10.0:double) 5.0:double))
          var(r#2)))
      (if (jcall Set.add var(s2) var(y#2)) (box (set c2 += 1:int)))))
  (+:int (+:int var(c1) var(c2)) (jcall Set.size var(s2)))
```

```hir
;; hir -O2 fn=distinctBailout
fn distinctBailout (xs:obj) -> int {pure unique}
  (def s1 (core-new set))
  (def c1 0:int)
  (for x#1 in var(xs)
    (block
      (def y#1
        (block
          (def r#1 (if (<:bool var(x#1) 0:int) 0:int var(x#1)))
          var(r#1)))
      (if (jcall Set.add var(s1) var(y#1)) (box (set c1 += 1:int)))))
  (def s2 (core-new set))
  (def c2 0:int)
  (for x#2 in var(xs)
    (block
      (def y#2
        (block
          (def r#2 (+:double (*:double (convert[double] var(x#2)) 10.0:double) 5.0:double))
          var(r#2)))
      (if (jcall Set.add var(s2) var(y#2)) (box (set c2 += 1:int)))))
  (+:int (+:int var(c1) var(c2)) (jcall Set.size var(s2)))
```

## Fusion soundness bailouts: escaping list and mutated iterable

```babble
def fusionEscAndMut = fn (mut xs: list of int): int -> (
    def esc = list of int ()
    for x in xs then ( esc.add(x + 1) )
    var s1 = 0
    for y in esc then ( s1 += y )
    def copy = list of int ()
    for x in xs then ( copy.add(x) )
    for y in copy then ( xs.add(y * 10) )
    s1 + esc.size() + xs.size()
)
assert(fusionEscAndMut(list of int (1, 2)) == 11)
```

```hir
;; hir -O0 fn=fusionEscAndMut
fn fusionEscAndMut (xs:obj) -> int {unique}
  (def esc (core-new list))
  (for x#1 in var(xs)
    (block
      (jcall List.add var(esc) (+:int var(x#1) 1:int))))
  (def s1 0:int)
  (for y#1 in var(esc)
    (block
      (set s1 += var(y#1))))
  (def copy (core-new list))
  (for x#2 in var(xs)
    (block
      (jcall List.add var(copy) var(x#2))))
  (for y#2 in var(copy)
    (block
      (jcall List.add var(xs) (*:int var(y#2) 10:int))))
  (+:int (+:int var(s1) (jcall List.size var(esc))) (jcall List.size var(xs)))
```

```hir
;; hir -O1 fn=fusionEscAndMut
fn fusionEscAndMut (xs:obj) -> int {unique}
  (def esc (core-new list))
  (for x#1 in var(xs)
    (block
      (jcall List.add var(esc) (+:int var(x#1) 1:int))))
  (def s1 0:int)
  (for y#1 in var(esc)
    (block
      (set s1 += var(y#1))))
  (def copy (core-new list))
  (for x#2 in var(xs)
    (block
      (jcall List.add var(copy) var(x#2))))
  (for y#2 in var(copy)
    (block
      (jcall List.add var(xs) (*:int var(y#2) 10:int))))
  (+:int (+:int var(s1) (jcall List.size var(esc))) (jcall List.size var(xs)))
```

```hir
;; hir -O2 fn=fusionEscAndMut
fn fusionEscAndMut (xs:obj) -> int {unique}
  (def esc (core-new list))
  (for x#1 in var(xs)
    (block
      (jcall List.add var(esc) (+:int var(x#1) 1:int))))
  (def s1 0:int)
  (for y#1 in var(esc)
    (block
      (set s1 += var(y#1))))
  (def copy (core-new list))
  (for x#2 in var(xs)
    (block
      (jcall List.add var(copy) var(x#2))))
  (for y#2 in var(copy)
    (block
      (jcall List.add var(xs) (*:int var(y#2) 10:int))))
  (+:int (+:int var(s1) (jcall List.size var(esc))) (jcall List.size var(xs)))
```

## Fusion soundness bailout: consumer mutates variable read by producer

```babble
def fusionMutVar = fn (initBias: int): int -> (
    var bias = initBias
    if initBias > 0 then ( bias += 1 )
    def shifted = list of int ()
    for x in 0..<3 then ( shifted.add(x + bias) )
    var s = 0
    for y in shifted then (
        bias += 100
        s += y
    )
    s + bias
)
assert(fusionMutVar(1) == 311)
```

```hir
;; hir -O0 fn=fusionMutVar
fn fusionMutVar (initBias:int) -> int {pure unique}
  (def bias var(initBias))
  (if (>:bool var(initBias) 0:int) (box (block (set bias += 1:int))))
  (def shifted (core-new list))
  (for-range x ..< 0:int 3:int
    (block
      (jcall List.add var(shifted) (+:int var(x) var(bias)))))
  (def s 0:int)
  (for y in var(shifted)
    (block
      (set bias += 100:int)
      (set s += var(y))))
  (+:int var(s) var(bias))
```

```hir
;; hir -O1 fn=fusionMutVar
fn fusionMutVar (initBias:int) -> int {pure unique}
  (def bias var(initBias))
  (if (>:bool var(initBias) 0:int) (box (set bias += 1:int)))
  (def shifted (core-new list))
  (for-range x ..< 0:int 3:int
    (block
      (jcall List.add var(shifted) (+:int var(x) var(bias)))))
  (def s 0:int)
  (for y in var(shifted)
    (block
      (set bias += 100:int)
      (set s += var(y))))
  (+:int var(s) var(bias))
```

```hir
;; hir -O2 fn=fusionMutVar
fn fusionMutVar (initBias:int) -> int {pure unique}
  (def bias var(initBias))
  (if (>:bool var(initBias) 0:int) (box (set bias += 1:int)))
  (def shifted (core-new list))
  (for-range x ..< 0:int 3:int
    (block
      (jcall List.add var(shifted) (+:int var(x) var(bias)))))
  (def s 0:int)
  (for y in var(shifted)
    (block
      (set bias += 100:int)
      (set s += var(y))))
  (+:int var(s) var(bias))
```
