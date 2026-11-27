# Unified HIR Lowering, Primitives, Loops, and Tail Calls at -O2 (BIP039)

Tests unified HIR optimization and `HirEmitter` bytecode emission across
primitives, loops, swap cycles, narrowing anchors, tail calls, and non-lowered markers.

## All 8 primitive kinds, widening, and unary ops

```babble
def prims = fn (b: byte, s: short, i: int, l: long, f: float, d: double, c: char, p: bool): double -> (
    def r = (b + s + (-i) + (-l)) * 1.0 + (-f) + (-d)
    if (not p) and (c == 'a') then (r) else (r + 1.0)
)
assert(prims(byte(1), short(2), 3, 4L, 1.5f, 2.5, 'a', false) == -8.0)
assert(prims(byte(1), short(2), 3, 4L, 1.5f, 2.5, 'b', true) == -7.0)
```

```hir
;; hir -O0 fn=prims
fn prims (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, p:bool) -> double {nothrow}
  (def r (+:double (+:double (*:double (convert[double] (block (+:long (convert[long] (+:int (+:int (convert[int] var(b)) (convert[int] var(s))) (block (neg var(i))))) (block (neg var(l)))))) 1.0:double) (convert[double] (block (neg var(f))))) (block (neg var(d)))))
  (if (and (block (not var(p))) (block (==:bool var(c) 'a'))) (block var(r)) (block (+:double var(r) 1.0:double)))
```

```hir
;; hir -O1 fn=prims
fn prims (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, p:bool) -> double {nothrow}
  (def r (+:double (+:double (*:double (convert[double] (+:long (convert[long] (+:int (+:int (convert[int] var(b)) (convert[int] var(s))) (neg var(i)))) (neg var(l)))) 1.0:double) (convert[double] (neg var(f)))) (neg var(d))))
  (if (and (not var(p)) (==:bool var(c) 'a')) var(r) (+:double var(r) 1.0:double))
```

```hir
;; hir -O2 fn=prims
fn prims (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, p:bool) -> double {nothrow}
  (def r (+:double (+:double (*:double (convert[double] (+:long (convert[long] (+:int (+:int (convert[int] var(b)) (convert[int] var(s))) (neg var(i)))) (neg var(l)))) 1.0:double) (convert[double] (neg var(f)))) (neg var(d))))
  (if (and (not var(p)) (==:bool var(c) 'a')) var(r) (+:double var(r) 1.0:double))
```

## Typed array for-loop with break and continue

```babble
def sumArr = fn (xs: array of int): int -> (
    var acc = 0
    for x in xs then (
        if x < 0 then (continue)
        if x == 99 then (break)
        acc += x
    )
    acc
)
assert(sumArr((array of int).of(2, -1, 3, 99, 5)) == 5)
```

```hir
;; hir -O0 fn=sumArr
fn sumArr (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (block (continue)))
      (if (==:bool var(x) 99:int) (block (break)))
      (set acc += var(x))))
  var(acc)
```

```hir
;; hir -O1 fn=sumArr
fn sumArr (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (continue))
      (if (==:bool var(x) 99:int) (break))
      (set acc += var(x))))
  var(acc)
```

```hir
;; hir -O2 fn=sumArr
fn sumArr (xs:obj) -> int {nothrow unique}
  (def acc 0:int)
  (for x in var(xs)
    (block
      (if (<:bool var(x) 0:int) (continue))
      (if (==:bool var(x) 99:int) (break))
      (set acc += var(x))))
  var(acc)
```

## Dynamic-union for-loop over array or list

```babble
def sumDyn = fn (xs: array of int | list of int): int -> (
    var s = 0
    for v in xs then (s += v)
    s
)
assert(sumDyn((array of int).of(1, 2, 3)) == 6)
assert(sumDyn(list(4, 5, 6)) == 15)
```

```hir
;; hir -O0 fn=sumDyn
fn sumDyn (xs:obj) -> int {pure unique}
  (def s 0:int)
  (for v in var(xs)
    (block
      (set s += var(v))))
  var(s)
```

```hir
;; hir -O1 fn=sumDyn
fn sumDyn (xs:obj) -> int {pure unique}
  (def s 0:int)
  (for v in var(xs)
    (block
      (set s += var(v))))
  var(s)
```

```hir
;; hir -O2 fn=sumDyn
fn sumDyn (xs:obj) -> int {pure unique}
  (def s 0:int)
  (for v in var(xs)
    (block
      (set s += var(v))))
  var(s)
```

## While loop with swap parallel-copy cycle

```babble
def fib = fn (n: int): int -> (
    var a = 0
    var b = 1
    var i = 0
    while i < n then (
        def t = a + b
        a = b
        b = t
        i += 1
    )
    a
)
assert(fib(0) == 0 and fib(7) == 13)
```

```hir
;; hir -O0 fn=fib
fn fib (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def b 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def t (+:int var(a) var(b)))
      (set a var(b))
      (set b var(t))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=fib
fn fib (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def b 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def t (+:int var(a) var(b)))
      (set a var(b))
      (set b var(t))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=fib
fn fib (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def b 1:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def t (+:int var(a) var(b)))
      (set a var(b))
      (set b var(t))
      (set i += 1:int)))
  var(a)
```

## Narrowing anchors on null check, is-check, and safe call

```babble
def narrowLen = fn (s: string?, v: int | string): int -> (
    def a = if s <> null then (s.length()) else (0)
    def b = if v is string then (v.length()) else (v)
    def c = s?.length()
    a + b + (if c <> null then (c) else (0))
)
assert(narrowLen("abc", "xy") == 8 and narrowLen(null, 5) == 5)
```

```hir
;; hir -O0 fn=narrowLen
fn narrowLen (s:obj, v:obj) -> int {nothrow unique}
  (def a (if (<>:bool var(s) null) (block (jcall String.length (narrow.nonnull var(s)))) (block 0:int)))
  (def b (if (is var(v) string) (block (jcall String.length (narrow.is[string] var(v)))) (block var(v))))
  (def c (safe-call var(s)?.length))
  (+:int (+:int var(a) var(b)) (block (if (<>:bool var(c) null) (block (narrow.nonnull var(c))) (block 0:int))))
```

```hir
;; hir -O1 fn=narrowLen
fn narrowLen (s:obj, v:obj) -> int {nothrow unique}
  (def a (if (<>:bool var(s) null) (jcall String.length (narrow.nonnull var(s))) 0:int))
  (def b (if (is var(v) string) (jcall String.length (narrow.is[string] var(v))) var(v)))
  (def c (safe-call var(s)?.length))
  (+:int (+:int var(a) var(b)) (if (<>:bool var(c) null) (narrow.nonnull var(c)) 0:int))
```

```hir
;; hir -O2 fn=narrowLen
fn narrowLen (s:obj, v:obj) -> int {nothrow unique}
  (def a (if (<>:bool var(s) null) (jcall String.length (narrow.nonnull var(s))) 0:int))
  (def b (if (is var(v) string) (jcall String.length (narrow.is[string] var(v))) var(v)))
  (def c (safe-call var(s)?.length))
  (+:int (+:int var(a) var(b)) (if (<>:bool var(c) null) (narrow.nonnull var(c)) 0:int))
```

## Self tail call and non-lowered top-level marker

```babble
def factTail = fn (n: int, acc: int): int -> (
    if n <= 1 then (acc) else (recurse(n - 1, acc * n))
)
assert(factTail(5, 1) == 120)
```

```hir
;; hir -O0 fn=factTail
fn factTail (n:int, acc:int) -> int {nothrow}
  (if (<=:bool var(n) 1:int) (block var(acc)) (block (tail-call factTail (-:int var(n) 1:int) (*:int var(acc) var(n)))))
```

```hir
;; hir -O1 fn=factTail
fn factTail (n:int, acc:int) -> int {nothrow}
  (if (<=:bool var(n) 1:int) var(acc) (tail-call factTail (-:int var(n) 1:int) (*:int var(acc) var(n))))
```

```hir
;; hir -O2 fn=factTail
fn factTail (n:int, acc:int) -> int {nothrow}
  (if (<=:bool var(n) 1:int) var(acc) (tail-call factTail (-:int var(n) 1:int) (*:int var(acc) var(n))))
```

```hir
;; hir -O2 top-level
top-level:
  (def factTail (fn-ref factTail))
  (call name(assert) (==:bool (call-fn factTail 5:int 1:int) 120:int))
```
