# Ranges, Counted Loops, and Floored Division Lowering and Optimization

References : `BIP031`, `BIP039`, `org.babblelang.opt.hir.{HirEGraphSimplifyPass,HirConstFolder,HirDcePass,HirSccpPass,HirAeGraphPass}`.

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

```hir
;; hir -O2 fn=arith
fn arith (x:int) -> int {nothrow unique}
  (block
    (def $cse0 (mod:int var(x) 5:int))
    (def id (+:int (+:int var(x) var($cse0)) 1:int))
    (+:int (+:int var(id) -1:int) (neg var(x))))
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

```hir
;; hir -O2 fn=divZero
fn divZero (x:int) -> int {pure unique}
  (def q (throw (jnew ArithmeticException "/ by zero")))
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

```hir
;; hir -O2 fn=sumTo
fn sumTo (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 0:int var(n)
    (block
      (set acc += var(i))))
  var(acc)
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

```hir
;; hir -O2 fn=sumRevStep
fn sumRevStep (n:int) -> int {nothrow unique}
  (def acc 0:int)
  (for-range i ..< 3:int var(n) step 5:int reversed
    (block
      (set acc += var(i))))
  var(acc)
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

```hir
;; hir -O2 fn=checkRange
fn checkRange (x:int) -> bool {nothrow unique}
  (and (and (<=:bool 3:int var(x)) (<:bool var(x) 20:int)) (==:bool (mod:int (-:int var(x) 3:int) 5:int) 0:int))
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

```hir
;; hir -O2 fn=dropPure
fn dropPure (n:int) -> int {nothrow unique}
  42:int
```

## Range start and end constant folding across scalar kinds

```babble
def bounds = fn (): bool -> (
    def a = (1..10).start() + (1..10).end() == 11
    def b = (10l..20l).start() + (10l..20l).end() == 30l
    def c = ('a'..'z').start() == 'a' and ('a'..'z').end() == 'z'
    def d = (1.5..4.5).start() + (1.5..4.5).end() == 6.0
    a and b and c and d
)
assert(bounds())
```

```hir
;; hir -O0 fn=bounds
fn bounds () -> bool {nothrow unique}
  (def a (==:bool (+:int (jcall IntRange.startInt (block (range .. 1:int 10:int))) (jcall IntRange.endInt (block (range .. 1:int 10:int)))) 11:int))
  (def b (==:bool (+:long (jcall LongRange.startLong (block (range .. 10:long 20:long))) (jcall LongRange.endLong (block (range .. 10:long 20:long)))) 30:long))
  (def c (and (==:bool (jcall CharRange.startChar (block (range .. 'a' 'z'))) 'a') (==:bool (jcall CharRange.endChar (block (range .. 'a' 'z'))) 'z')))
  (def d (==:bool (+:double (jcall DoubleRange.startDouble (block (range .. 1.5:double 4.5:double))) (jcall DoubleRange.endDouble (block (range .. 1.5:double 4.5:double)))) 6.0:double))
  (and (and (and var(a) var(b)) var(c)) var(d))
```

```hir
;; hir -O1 fn=bounds
fn bounds () -> bool {nothrow unique}
  true:bool
```

```hir
;; hir -O2 fn=bounds
fn bounds () -> bool {nothrow unique}
  true:bool
```

## Range isEmpty and stepped long contains folding

```babble
def checkEmpty = fn (x: long): bool -> (
    def a = not (1..5).isEmpty() and (5..<5).isEmpty() and not (1l..5l).isEmpty() and (5l..<5l).isEmpty()
    def b = not ('a'..'z').isEmpty() and ('z'..'a').isEmpty() and ('a'..<'a').isEmpty() and (2.0..1.0).isEmpty()
    a and b and (0l..<100l).step(5l).contains(x)
)
assert(checkEmpty(15l) and not checkEmpty(14l))
```

```hir
;; hir -O0 fn=checkEmpty
fn checkEmpty (x:long) -> bool {nothrow unique}
  (def a (and (and (and (not (jcall Range.isEmpty (block (range .. 1:int 5:int)))) (jcall Range.isEmpty (block (range ..< 5:int 5:int)))) (not (jcall Range.isEmpty (block (range .. 1:long 5:long))))) (jcall Range.isEmpty (block (range ..< 5:long 5:long)))))
  (def b (and (and (and (not (jcall Range.isEmpty (block (range .. 'a' 'z')))) (jcall Range.isEmpty (block (range .. 'z' 'a')))) (jcall Range.isEmpty (block (range ..< 'a' 'a')))) (jcall Range.isEmpty (block (range .. 2.0:double 1.0:double)))))
  (and (and var(a) var(b)) (jcall LongRange.containsLong (jcall LongRange.step (block (range ..< 0:long 100:long)) 5:long) var(x)))
```

```hir
;; hir -O1 fn=checkEmpty
fn checkEmpty (x:long) -> bool {nothrow unique}
  (and (and (<=:bool 0:long var(x)) (<:bool var(x) 100:long)) (==:bool (mod:long var(x) 5:long) 0:long))
```

```hir
;; hir -O2 fn=checkEmpty
fn checkEmpty (x:long) -> bool {nothrow unique}
  (and (and (<=:bool 0:long var(x)) (<:bool var(x) 100:long)) (==:bool (mod:long var(x) 5:long) 0:long))
```

## Long floored division folding and same-leaf if collapse

```babble
def longOps = fn (flag: bool, x: int): long -> (
    def c = (-7l // 3l) + (-7l mod 3l) + (10l // 3l) + (10l mod 3l)
    def same1 = if flag then (x) else (x)
    def same2 = if flag then (4) else (4)
    c + same1 + same2
)
assert(longOps(true, 10) == 17l and longOps(false, 10) == 17l)
```

```hir
;; hir -O0 fn=longOps
fn longOps (flag:bool, x:int) -> long {nothrow unique}
  (def c (+:long (+:long (+:long (block (//:long -7:long 3:long)) (block (mod:long -7:long 3:long))) (block (//:long 10:long 3:long))) (block (mod:long 10:long 3:long))))
  (def same1 (if var(flag) (block var(x)) (block var(x))))
  (def same2 (if var(flag) (block 4:int) (block 4:int)))
  (+:long (+:long var(c) (convert[long] var(same1))) (convert[long] var(same2)))
```

```hir
;; hir -O1 fn=longOps
fn longOps (flag:bool, x:int) -> long {nothrow unique}
  (def same1 (if var(flag) var(x) var(x)))
  (def same2 (if var(flag) 4:int 4:int))
  (+:long (+:long (convert[long] var(same1)) 3:long) (convert[long] var(same2)))
```

```hir
;; hir -O2 fn=longOps
fn longOps (flag:bool, x:int) -> long {nothrow unique}
  (def same1 (if var(flag) var(x) var(x)))
  (block
    (def $cse0 (convert[long] var(same1)))
    (+:long var($cse0) 7:long))
```

## Impure zero-divisor preserves side effect before throwing

```babble
var ticks = 0
def bump = fn io (): int -> (ticks += 1 ; ticks)
def runDiv = fn io (): int -> (
    def z = 0
    try (bump() // z) catch : java.lang.ArithmeticException then (-1)
)
assert(runDiv() == -1 and ticks == 1)
```

```hir
;; hir -O0 fn=runDiv
fn runDiv () -> int {io}
  (def z 0:int)
  (try
    (block
      (//:int (call-fn bump) var(z)))
    (catch _:java|java.lang.ArithmeticException|java.lang.ArithmeticException
      -1:int))
```

```hir
;; hir -O1 fn=runDiv
fn runDiv () -> int {io}
  (try
    (block
      (block
        (call-fn bump)
        (throw (jnew ArithmeticException "/ by zero"))))
    (catch _:java|java.lang.ArithmeticException|java.lang.ArithmeticException
      -1:int))
```

```hir
;; hir -O2 fn=runDiv
fn runDiv () -> int {io}
  (try
    (block
      (block
        (call-fn bump)
        (throw (jnew ArithmeticException "/ by zero"))))
    (catch _:java|java.lang.ArithmeticException|java.lang.ArithmeticException
      -1:int))
```
