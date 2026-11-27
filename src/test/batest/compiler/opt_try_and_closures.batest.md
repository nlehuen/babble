# Try/Catch/Finally and Closure Cell Lowering at -O2 (BIP039)

Tests `try`/`catch`/`finally`/`throw` structured lowering with pinned locals and `addSuppressed`,
plus loop-local `Cell` capture preservation across `-O2` HIR passes.

## Try/catch/finally lowering with Babble record, Java exception, and pinned local

```babble
def E = record (m: string) ()
def handle = fn (k: int): int -> (
    var o = 0
    try (
        o = 1
        if k == 1 then (throw E("bad"))
        if k == 2 then (throw java.lang.Error("x"))
        o = 2
    ) catch e: E then (o = e.m.length())
      catch e: java.lang.Error then (o = 20)
      finally (o += 100)
    o
)
assert(handle(0) == 102 and handle(1) == 103 and handle(2) == 120)
```

```hir
;; hir -O0 fn=handle
fn handle (k:int) -> int {pure unique}
  (def o 0:int)
  (try
    (block
      (set o 1:int)
      (if (==:bool var(k) 1:int) (block (throw (record-new E "bad"))))
      (if (==:bool var(k) 2:int) (block (throw (jnew Error "x"))))
      (set o 2:int))
    (catch e#1:babble|E
      (set o (jcall String.length (get-this-field m))))
    (catch e#2:java|java.lang.Error|java.lang.Error
      (set o 20:int))
    (finally
      (set o += 100:int)))
  var(o)
```

```hir
;; hir -O1 fn=handle
fn handle (k:int) -> int {pure unique}
  (def o 0:int)
  (try
    (block
      (set o 1:int)
      (if (==:bool var(k) 1:int) (throw (record-new E "bad")))
      (if (==:bool var(k) 2:int) (throw (jnew Error "x")))
      (set o 2:int))
    (catch e#1:babble|E
      (set o (jcall String.length (get-this-field m))))
    (catch e#2:java|java.lang.Error|java.lang.Error
      (set o 20:int))
    (finally
      (set o += 100:int)))
  var(o)
```

```hir
;; hir -O2 fn=handle
fn handle (k:int) -> int {pure unique}
  (def o 0:int)
  (try
    (block
      (set o 1:int)
      (if (==:bool var(k) 1:int) (throw (record-new E "bad")))
      (if (==:bool var(k) 2:int) (throw (jnew Error "x")))
      (set o 2:int))
    (catch e#1:babble|E
      (set o (jcall String.length (get-this-field m))))
    (catch e#2:java|java.lang.Error|java.lang.Error
      (set o 20:int))
    (finally
      (set o += 100:int)))
  var(o)
```

## Try-finally abnormal unwind with addSuppressed

```babble
def supTest = fn volatile (): int -> (
    var n = 0
    try (
        try (throw java.lang.RuntimeException("p"))
        finally (throw java.lang.IllegalArgumentException("s"))
    ) catch e: java.lang.RuntimeException then (
        n = e.getSuppressed().size()
    )
    n
)
assert(supTest() == 1)
```

```hir
;; hir -O0 fn=supTest
fn supTest () -> int {volatile unique}
  (def n 0:int)
  (try
    (block
      (try
        (block
          (throw (jnew RuntimeException "p")))
        (finally
          (throw (jnew IllegalArgumentException "s")))))
    (catch e:java|java.lang.RuntimeException|java.lang.RuntimeException
      (set n (array-op SIZE (jcall Throwable.getSuppressed var(e))))))
  var(n)
```

```hir
;; hir -O1 fn=supTest
fn supTest () -> int {volatile unique}
  (try
    (block
      (try
        (block
          (throw (jnew RuntimeException "p")))
        (finally
          (throw (jnew IllegalArgumentException "s")))))
    (catch e:java|java.lang.RuntimeException|java.lang.RuntimeException
      (set n (array-op SIZE (jcall Throwable.getSuppressed var(e))))))
  var(n)
```

```hir
;; hir -O2 fn=supTest
fn supTest () -> int {volatile unique}
  (try
    (block
      (try
        (block
          (throw (jnew RuntimeException "p")))
        (finally
          (throw (jnew IllegalArgumentException "s")))))
    (catch e:java|java.lang.RuntimeException|java.lang.RuntimeException
      (set n (array-op SIZE (jcall Throwable.getSuppressed var(e))))))
  var(n)
```

## Loop-local cell capture in closure

```babble
def cellCap = fn (xs: list of int): int -> (
    def fs = (list of (fn (): int))()
    for x in xs then (
        def c = x + 1
        fs.add(fn (): int -> (c))
    )
    fs.get(0)() + fs.get(1)()
)
assert(cellCap(list(10, 20)) == 32)
```

```hir
;; hir -O0 fn=cellCap
fn cellCap (xs:obj) -> int {pure unique}
  (def fs (call (block (type-apply core-col(list)))))
  (for x in var(xs)
    (block
      (def c (+:int var(x) 1:int))
      (jcall List.add var(fs) (fn-ref <anon#0>))))
  (+:int (call (jcall List.get var(fs) 0:int)) (call (jcall List.get var(fs) 1:int)))
```

```hir
;; hir -O1 fn=cellCap
fn cellCap (xs:obj) -> int {pure unique}
  (def fs (call (type-apply core-col(list))))
  (for x in var(xs)
    (block
      (def c (+:int var(x) 1:int))
      (jcall List.add var(fs) (fn-ref <anon#0>))))
  (+:int (call (jcall List.get var(fs) 0:int)) (call (jcall List.get var(fs) 1:int)))
```

```hir
;; hir -O2 fn=cellCap
fn cellCap (xs:obj) -> int {pure unique}
  (def fs (call (type-apply core-col(list))))
  (for x in var(xs)
    (block
      (def c (+:int var(x) 1:int))
      (jcall List.add var(fs) (fn-ref <anon#0>))))
  (+:int (call (jcall List.get var(fs) 0:int)) (call (jcall List.get var(fs) 1:int)))
```
