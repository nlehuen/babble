# Unified HIR LICM and Predicate Reordering (BIP039)

Tests `HirLicmPass` (loop preheader hoisting of pure, frozen, and un-clobbered
reads, plus structural `Narrow` safety) and `HirPredicateReorderPass` (short-circuit pure
predicate reordering and negative narrowing-safety tests).

## LICM hoists loop-invariant frozen record read and pure arithmetic out of while loop

```babble
def Scale = record (k: double) ()
def sumScaled = fn (s: Scale, angle: double, n: int): double -> (
    var i = 0
    var acc = 0.0
    while i < n then (
        def factor = s.k * java.lang.Math.cos(angle)
        acc += factor
        i += 1
    )
    acc
)
assert(sumScaled(Scale(3.0), 0.0, 4) == 12.0)
```

```hir
;; hir -O0 fn=sumScaled
fn sumScaled (s:obj, angle:double, n:int) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def factor (*:double (get-this-field k) (jcall Math.cos var(angle))))
      (set acc += var(factor))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumScaled
fn sumScaled (s:obj, angle:double, n:int) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (get-this-field k) (jcall Math.cos var(angle))))
    (while (<:bool var(i) var(n))
      (block
        (def factor var($licm0))
        (set acc += var(factor))
        (set i += 1:int))))
  var(acc)
```

```hir
;; hir -O2 fn=sumScaled
fn sumScaled (s:obj, angle:double, n:int) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (get-this-field k) (jcall Math.cos var(angle))))
    (while (<:bool var(i) var(n))
      (block
        (set acc += var($licm0))
        (set i += 1:int))))
  var(acc)
```

## MIR LICM hoists loop-invariant while-condition computation out of loop header

```babble
def Limit = record (k: int) ()
def sumToBound = fn (lim: Limit, a: int, b: int): int -> (
    var i = 0
    var acc = 0
    while i < a * b + lim.k then (
        acc += i
        i += 1
    )
    acc
)
assert(sumToBound(Limit(1), 2, 2) == 10)
```

```hir
;; hir -O0 fn=sumToBound
fn sumToBound (lim:obj, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) (+:int (*:int var(a) var(b)) (get-this-field k)))
    (block
      (set acc += var(i))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumToBound
fn sumToBound (lim:obj, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (block
    (def $licm0 (+:int (*:int var(a) var(b)) (get-this-field k)))
    (while (<:bool var(i) var($licm0))
      (block
        (set acc += var(i))
        (set i += 1:int))))
  var(acc)
```

```hir
;; hir -O2 fn=sumToBound
fn sumToBound (lim:obj, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (block
    (def $licm0 (+:int (*:int var(a) var(b)) (get-this-field k)))
    (while (<:bool var(i) var($licm0))
      (block
        (set acc += var(i))
        (set i += 1:int))))
  var(acc)
```

## LICM inserts a preheader when a loop is inside an if branch and never hoists above Narrow

```babble
def guardedLoop = fn (flag: bool, s: string?, n: int): int -> (
    var acc = 0
    if flag then (
        var i = 0
        while i < n then (
            if s <> null then (acc += s.length())
            i += 1
        )
    )
    acc
)
assert(guardedLoop(true, "abcd", 3) == 12 and guardedLoop(true, null, 3) == 0)
```

```hir
;; hir -O0 fn=guardedLoop
fn guardedLoop (flag:bool, s:obj, n:int) -> int {nothrow unique}
  (def acc 0:int)
  (if var(flag)
    (block
      (def i 0:int)
      (while (<:bool var(i) var(n))
        (block
          (if (<>:bool var(s) null) (box (block (set acc += (jcall String.length (narrow.nonnull var(s)))))))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O1 fn=guardedLoop
fn guardedLoop (flag:bool, s:obj, n:int) -> int {nothrow unique}
  (def acc 0:int)
  (if var(flag)
    (block
      (def i 0:int)
      (while (<:bool var(i) var(n))
        (block
          (if (<>:bool var(s) null) (box (set acc += (jcall String.length (narrow.nonnull var(s))))))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O2 fn=guardedLoop
fn guardedLoop (flag:bool, s:obj, n:int) -> int {nothrow unique}
  (def acc 0:int)
  (if var(flag)
    (block
      (def i 0:int)
      (while (<:bool var(i) var(n))
        (block
          (if (<>:bool var(s) null) (box (set acc += (jcall String.length (narrow.nonnull var(s))))))
          (set i += 1:int)))))
  var(acc)
```

## PredicateReorder moves cheap scalar test ahead of expensive pure call

```babble
def expensive = fn nothrow (x: double): bool -> (
    java.lang.Math.sin(x) + java.lang.Math.cos(x) > 0.0
)
def reorderPure = fn (x: double, limit: int): int -> (
    if expensive(x) and (limit > 0) then (1) else (0)
)
assert(reorderPure(0.5, 1) == 1 and reorderPure(0.5, -1) == 0)
```

```hir
;; hir -O0 fn=reorderPure
fn reorderPure (x:double, limit:int) -> int {nothrow}
  (if (and (call-fn expensive var(x)) (block (>:bool var(limit) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=reorderPure
fn reorderPure (x:double, limit:int) -> int {nothrow}
  (if (and (>:bool var(limit) 0:int) (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 0.0:double)) 1:int 0:int)
```

```hir
;; hir -O2 fn=reorderPure
fn reorderPure (x:double, limit:int) -> int {nothrow}
  (if (and (>:bool var(limit) 0:int) (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 0.0:double)) 1:int 0:int)
```

## PredicateReorder NEVER moves a narrowed dereference ahead of its null guard

```babble
def keepGuard = fn (s: string?, limit: int): int -> (
    if (s <> null) and (s.length() > limit) then (1) else (0)
)
assert(keepGuard("hello", 3) == 1 and keepGuard(null, 3) == 0)
```

```hir
;; hir -O0 fn=keepGuard
fn keepGuard (s:obj, limit:int) -> int {nothrow}
  (if (and (block (<>:bool var(s) null)) (block (>:bool (jcall String.length (narrow.nonnull var(s))) var(limit)))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=keepGuard
fn keepGuard (s:obj, limit:int) -> int {nothrow}
  (if (and (<>:bool var(s) null) (>:bool (jcall String.length (narrow.nonnull var(s))) var(limit))) 1:int 0:int)
```

```hir
;; hir -O2 fn=keepGuard
fn keepGuard (s:obj, limit:int) -> int {nothrow}
  (if (and (<>:bool var(s) null) (>:bool (jcall String.length (narrow.nonnull var(s))) var(limit))) 1:int 0:int)
```

## PredicateReorder moves cheap scalar test ahead of expensive pure call in short-circuit or

```babble
def slowOr = fn nothrow (x: double): bool -> (
    java.lang.Math.sin(x) + java.lang.Math.cos(x) > 1.5
)
def reorderOr = fn (x: double, limit: int): int -> (
    if slowOr(x) or (limit > 0) then (1) else (0)
)
assert(reorderOr(0.5, 1) == 1 and reorderOr(0.5, -1) == 0)
```

```hir
;; hir -O0 fn=reorderOr
fn reorderOr (x:double, limit:int) -> int {nothrow}
  (if (or (call-fn slowOr var(x)) (block (>:bool var(limit) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=reorderOr
fn reorderOr (x:double, limit:int) -> int {nothrow}
  (if (or (>:bool var(limit) 0:int) (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 1.5:double)) 1:int 0:int)
```

```hir
;; hir -O2 fn=reorderOr
fn reorderOr (x:double, limit:int) -> int {nothrow}
  (if (or (>:bool var(limit) 0:int) (>:bool (+:double (jcall Math.sin var(x)) (jcall Math.cos var(x))) 1.5:double)) 1:int 0:int)
```

## PredicateReorder ranks record load and array read heavier than scalar compare

```babble
def Pt = record (x: int) ()
def rankCost = fn (p: Pt, lim: int): int -> (
    if (p.x > 0) and (lim > 0) then (1) else (0)
)
assert(rankCost(Pt(2), 3) == 1 and rankCost(Pt(2), -1) == 0)
```

```hir
;; hir -O0 fn=rankCost
fn rankCost (p:obj, lim:int) -> int {nothrow}
  (if (and (block (>:bool (get-this-field x) 0:int)) (block (>:bool var(lim) 0:int))) (block 1:int) (block 0:int))
```

```hir
;; hir -O1 fn=rankCost
fn rankCost (p:obj, lim:int) -> int {nothrow}
  (if (and (>:bool var(lim) 0:int) (>:bool (get-this-field x) 0:int)) 1:int 0:int)
```

```hir
;; hir -O2 fn=rankCost
fn rankCost (p:obj, lim:int) -> int {nothrow}
  (if (and (>:bool var(lim) 0:int) (>:bool (get-this-field x) 0:int)) 1:int 0:int)
```

## LICM inserts a preheader on a BrIf entry edge to a guarded while loop

```babble
def guardedCond = fn (flag: bool, a: int, b: int): int -> (
    var i = 0
    var acc = 0
    if flag then (
        while i < a * b + 1 then (
            acc += i
            i += 1
        )
    )
    acc
)
assert(guardedCond(true, 2, 2) == 10 and guardedCond(false, 2, 2) == 0)
```

```hir
;; hir -O0 fn=guardedCond
fn guardedCond (flag:bool, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (if var(flag)
    (block
      (while (<:bool var(i) (+:int (*:int var(a) var(b)) 1:int))
        (block
          (set acc += var(i))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O1 fn=guardedCond
fn guardedCond (flag:bool, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (if var(flag)
    (block
      (def $licm0 (+:int (*:int var(a) var(b)) 1:int))
      (while (<:bool var(i) var($licm0))
        (block
          (set acc += var(i))
          (set i += 1:int)))))
  var(acc)
```

```hir
;; hir -O2 fn=guardedCond
fn guardedCond (flag:bool, a:int, b:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (if var(flag)
    (block
      (def $licm0 (+:int (*:int var(a) var(b)) 1:int))
      (while (<:bool var(i) var($licm0))
        (block
          (set acc += var(i))
          (set i += 1:int)))))
  var(acc)
```
