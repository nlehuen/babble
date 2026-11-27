# HIR DCE : Dead Pure Bindings, Unreachable Code, and Discard

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble
;; babble module=dce_mod.ba
export def val = 10
```

```babble
;; babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```



## HirDcePass : dead pure bindings, discard(...), and unreachable code after return

```babble
def log = java.lang.StringBuilder()
def step = fn io (t: string): int -> (log.append(t) ; 1)
def run = fn io (): int -> (
    def deadPure = addMul(9, 4)
    discard(deadPure + fib(4))
    step("A")
    return pick(3)
    step("Z")
)
assert(run() == 8 and log.toString() == "A")
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def deadPure (call-fn addMul 9:int 4:int))
  (call name(discard) (+:int var(deadPure) (call-fn fib 4:int)))
  (call-fn step "A")
  (return (call-fn pick 3:int))
  (call-fn step "Z")
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (call-fn step "A")
  (return 8:int)
```

```hir
;; hir -O2 fn=run
fn run () -> int {io}
  (call-fn step "A")
  (return 8:int)
```

## HirDcePass : unreachable code after continue and break in a for loop

```babble
def log = java.lang.StringBuilder()
def step = fn io (t: string): int -> (log.append(t) ; 1)
def run = fn io (xs: list of int): int -> (
    var total = 0
    for x in xs then (
        if x < 0 then (continue ; step("C"))
        if x > 10 then (break ; step("B"))
        total += x
    )
    total
)
assert(run(list(-1, 2, 5, 20, 99)) == 7 and log.toString() == "")
```

```hir
;; hir -O0 fn=run
fn run (xs:obj) -> int {io}
  (def total 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (box (block
            (continue)
            (call-fn step "C"))))
      (if (>:bool var(x) 10:int) (box (block
            (break)
            (call-fn step "B"))))
      (set total += var(x))))
  var(total)
```

```hir
;; hir -O1 fn=run
fn run (xs:obj) -> int {io}
  (def total 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (box (block (continue))))
      (if (>:bool var(x) 10:int) (box (block (break))))
      (set total += var(x))))
  var(total)
```

```hir
;; hir -O2 fn=run
fn run (xs:obj) -> int {io}
  (def total 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (box (continue)))
      (if (>:bool var(x) 10:int) (box (break)))
      (set total += var(x))))
  var(total)
```

## HirDcePass & HirPurity : dead CoreCollectionRef, FunctionRef, TypeApply, As, and recursive-by-name bindings

```babble
def idG of T = fn (x: T): T -> (x)
def sRec = fn (n: int): int -> (if n <= 0 then (0) else (sRec(n - 1) + 1))
def run = fn (x: int): int -> (
  def d1 = list ; def d2 = fn (k: int): int -> (k + 1)
  def d3 = idG of int ; def d4 = x as double ; def d5 = sRec(2)
  x + 1
)
assert(run(4) == 5)
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {pure unique}
  (def d1 core-col(list))
  (def d2 (fn-ref d2))
  (def d3 (type-apply name(idG)))
  (def d4 (as var(x) double|double|))
  (def d5 (call-fn sRec 2:int))
  (+:int var(x) 1:int)
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {pure unique}
  (as var(x) double|double|)
  (+:int var(x) 1:int)
```

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {pure unique}
  (as var(x) double|double|)
  (+:int var(x) 1:int)
```

## HirDcePass & HirPurity : dead pure-local Block with nested Block + finite-collection for loop

```babble
def sumL = fn (xs: list of int): int -> (var s = 0 ; for e in xs then (s += e) ; s)
def run = fn (xs: list of int, p: int): int -> (
  def deadSum = sumL(xs)
  def deadBlk = (def a = p + 1 ; (def b = a + 2 ; b))
  xs.size()
)
assert(run(list(1, 2, 3), 4) == 3)
```

```hir
;; hir -O0 fn=run
fn run (xs:obj, p:int) -> int {nothrow unique}
  (def deadSum (call-fn sumL var(xs)))
  (def deadBlk
    (block
      (def a (+:int var(p) 1:int))
      (block
        (def b (+:int var(a) 2:int))
        var(b))))
  (jcall List.size var(xs))
```

```hir
;; hir -O1 fn=run
fn run (xs:obj, p:int) -> int {nothrow unique}
  (jcall List.size var(xs))
```

```hir
;; hir -O2 fn=run
fn run (xs:obj, p:int) -> int {nothrow unique}
  (jcall List.size var(xs))
```

## HirPurity : dead mutable-arg call and throwing/non-terminating for loop stay preserved

```babble
def Box = class (var v: int) ()
def touch = fn (mut b: Box): int -> (b.v += 1 ; b.v)
def run = fn (mut b: Box, xs: list of int): int -> (
  def d1 = touch(b)
  def d2 = for x in xs then (assert(x > 0) ; x)
  b.v
)
assert(run(Box(0), list(1, 2)) == 1)
```

```hir
;; hir -O0 fn=run
fn run (b:obj, xs:obj) -> int {unique}
  (def d1 (call-fn touch var(b)))
  (def d2
    (for x in var(xs)
      (block
        (call name(assert) (>:bool var(x) 0:int))
        var(x))))
  (get-this-field v)
```

```hir
;; hir -O1 fn=run
fn run (b:obj, xs:obj) -> int {unique}
  (block
    (set-field var(b).v 1:int)
    (get-this-field v))
  (for x in var(xs)
    (block
      (call name(assert) (>:bool var(x) 0:int))
      var(x)))
  (get-this-field v)
```

```hir
;; hir -O2 fn=run
fn run (b:obj, xs:obj) -> int {unique}
  (block
    (set-field var(b).v 1:int)
    (get-this-field v))
  (for x in var(xs)
    (block
      (call name(assert) (>:bool var(x) 0:int))
      var(x)))
  (get-this-field v)
```

## HirDcePass : unused uninitialized def and fn/class param defaults with dead code

```babble
def fnDef = fn (a: int = (def d = 1 ; 1 + 2), b: int = (def e = 2 ; 3 + 4)): int -> (
    def unused: int? = null
    a + b
)
def ClsDef = class (def x: int = (def d = 1 ; 2 + 3)) ()
assert(fnDef() == 10 and ClsDef().x == 5)
```

```hir
;; hir -O0 fn=fnDef
fn fnDef (a:int=(block
    (def d 1:int)
    (+:int 1:int 2:int)), b:int=(block
    (def e 2:int)
    (+:int 3:int 4:int))) -> int {nothrow commutative associative unique}
  (def unused null)
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=fnDef
fn fnDef (a:int=3:int, b:int=7:int) -> int {nothrow commutative associative unique}
  (+:int var(a) var(b))
```

```hir
;; hir -O2 fn=fnDef
fn fnDef (a:int=3:int, b:int=7:int) -> int {nothrow commutative associative unique}
  (+:int var(a) var(b))
```

## HirDcePass : unreachable code after throw is eliminated

```babble
def log = java.lang.StringBuilder()
def afterThrow = fn io (b: bool): int -> (
    if b then (throw exception("boom") ; log.append("DEAD"))
    1
)
assert(afterThrow(false) == 1 and (try (afterThrow(true)) catch : exception then (9)) == 9 and log.toString() == "")
```

```hir
;; hir -O0 fn=afterThrow
fn afterThrow (b:bool) -> int {io}
  (if var(b)
    (block
      (throw (jnew BabbleException "boom"))
      (jcall StringBuilder.append name(log) "DEAD")))
  1:int
```

```hir
;; hir -O1 fn=afterThrow
fn afterThrow (b:bool) -> int {io}
  (if var(b) (throw (jnew BabbleException "boom")))
  1:int
```

```hir
;; hir -O2 fn=afterThrow
fn afterThrow (b:bool) -> int {io}
  (if var(b) (throw (jnew BabbleException "boom")))
  1:int
```

## HirDcePass : named discard argument stays preserved

```babble
def runNamed = fn (x: int): int -> (discard(value: x + 1) ; x)
assert(runNamed(5) == 5)
```

```hir
;; hir -O0,-O1 fn=runNamed
fn runNamed (x:int) -> int {nothrow unique}
  (call name(discard) value=(+:int var(x) 1:int))
  var(x)
```

```hir
;; hir -O2 fn=runNamed
fn runNamed (x:int) -> int {nothrow unique}
  (call name(discard) value=(+:int var(x) 1:int))
  var(x)
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (1-arg)

```babble
var discard = fn (x: int): int -> (x * 2)
discard = fn (x: int): int -> (x * 3)
def run = fn io (x: int): int -> (discard(x))
assert(run(4) == 12)
```

```hir
;; hir -O0,-O1 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x))
```

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x))
```

## HirDcePass : top-level reassigned discard shadowing via DynamicCall (2-arg)

```babble
var discard = fn (a: int, b: int): int -> (a + b)
discard = fn (a: int, b: int): int -> (a + b + 1)
def run = fn io (x: int): int -> (discard(x, 3))
assert(run(4) == 8)
```

```hir
;; hir -O0,-O1 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x) 3:int)
```

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {io}
  (call name(discard) var(x) 3:int)
```
