# HIR DCE : Dead-Catch and Pure Try/Finally Elimination

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



## HirLicmPass & HirPurity.isDeterministic : loop-invariant ArrayOp.SIZE, As, TypeApply, Logic, and If

```babble
def idT of T = fn (x: T): T -> (x)
def run = fn (arr: array of int, flag: bool, n: int): double -> (
  var i = 0 ; var acc = 0.0
  while i < n then (
    def k = double(arr.size()) + (if flag and true then (idT of double(1.0)) else (0.0)) + (1 as double)
    acc += k ; i += 1
  )
  acc
)
assert(run((array of int).of(10, 20), true, 3) == 12.0)
```

```hir
;; hir -O0 fn=run
fn run (arr:obj, flag:bool, n:int) -> double {pure unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def k (+:double (+:double (call name(double) (array-op SIZE var(arr))) (block (if (and var(flag) true:bool) (block (call (type-apply name(idT)) 1.0:double)) (block 0.0:double)))) (block (as 1:int double|double|))))
      (set acc += var(k))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (arr:obj, flag:bool, n:int) -> double {pure unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def k (+:double (+:double (call name(double) (array-op SIZE var(arr))) (if (and var(flag) true:bool) (call (type-apply name(idT)) 1.0:double) 0.0:double)) (as 1:int double|double|)))
      (set acc += var(k))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O2 fn=run
fn run (arr:obj, flag:bool, n:int) -> double {pure unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (def k (+:double (+:double (call name(double) (array-op SIZE var(arr))) (if (and var(flag) true:bool) (call (type-apply name(idT)) 1.0:double) 0.0:double)) (as 1:int double|double|)))
      (set acc += var(k))
      (set i += 1:int)))
  var(acc)
```

## Canonical HIR golden : dead-catch elimination on pure nothrow try body

```babble
def safeAdd = fn (x: int): int -> (
    try (x + 1) catch : exception then (0)
)
assert(safeAdd(41) == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (try
    (block
      (+:int var(x) 1:int))
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      0:int))
```

```hir
;; hir -O1 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (+:int var(x) 1:int)
```

```hir
;; hir -O2 fn=safeAdd
fn safeAdd (x:int) -> int {nothrow unique}
  (+:int var(x) 1:int)
```

## HirDcePass : pure try with dead catch and non-empty finally strips catch only

```babble
def log = java.lang.StringBuilder()
def keepFinally = fn io (x: int): int -> (
    try (x + 1) catch : exception then (0) finally (log.append("F"))
)
assert(keepFinally(41) == 42 and log.toString() == "F")
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=keepFinally
fn keepFinally (x:int) -> int {io}
  (try
    (block
      (+:int var(x) 1:int))
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      0:int)
    (finally
      (jcall StringBuilder.append name(log) "F")))
```

```hir
;; hir -O1 fn=keepFinally
fn keepFinally (x:int) -> int {io}
  (try
    (block
      (+:int var(x) 1:int))
    (finally
      (jcall StringBuilder.append name(log) "F")))
```

```hir
;; hir -O2 fn=keepFinally
fn keepFinally (x:int) -> int {io}
  (try
    (block
      (+:int var(x) 1:int))
    (finally
      (jcall StringBuilder.append name(log) "F")))
```

## HirDcePass : top-level namespace try with dead catch

```babble
def topTry = try (def t = 20 ; t + 22) catch : exception then (0)
assert(topTry == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (block
    (def $try0
      (try
        (block
          (def t 20:int)
          (+:int var(t) 22:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          0:int)))
    (def topTry var($try0)))
  (call name(assert) (==:bool name(topTry) 42:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def topTry 42:int)
  (call name(assert) (==:bool name(topTry) 42:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def addMul (fn-ref addMul))
  (def fib (fn-ref fib))
  (def pick (fn-ref pick))
  (def topTry 42:int)
  (call name(assert) (==:bool name(topTry) 42:int))
```

## HirDcePass : two catches with DCE and finally-only DCE

```babble
def log = java.lang.StringBuilder()
def twoCatches = fn io (s: string): int -> (
    try (java.lang.Integer.parseInt(s))
    catch : java.lang.NumberFormatException then (def d1 = 1 + 2 ; -1)
    catch : exception then (def d2 = 3 + 4 ; -2)
    finally (def df = 5 + 6 ; log.append("X"))
)
assert(twoCatches("bad") == -1 and log.toString() == "X")
```

```hir
;; hir -O0 fn=twoCatches
fn twoCatches (s:obj) -> int {io}
  (try
    (block
      (jcall Integer.parseInt var(s)))
    (catch _:java|java.lang.NumberFormatException|java.lang.NumberFormatException
      (def d1 (+:int 1:int 2:int))
      -1:int)
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      (def d2 (+:int 3:int 4:int))
      -2:int)
    (finally
      (def df (+:int 5:int 6:int))
      (jcall StringBuilder.append name(log) "X")))
```

```hir
;; hir -O1 fn=twoCatches
fn twoCatches (s:obj) -> int {io}
  (try
    (block
      (jcall Integer.parseInt var(s)))
    (catch _:java|java.lang.NumberFormatException|java.lang.NumberFormatException
      -1:int)
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      -2:int)
    (finally
      (jcall StringBuilder.append name(log) "X")))
```

```hir
;; hir -O2 fn=twoCatches
fn twoCatches (s:obj) -> int {io}
  (try
    (block
      (jcall Integer.parseInt var(s)))
    (catch _:java|java.lang.NumberFormatException|java.lang.NumberFormatException
      -1:int)
    (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
      -2:int)
    (finally
      (jcall StringBuilder.append name(log) "X")))
```
