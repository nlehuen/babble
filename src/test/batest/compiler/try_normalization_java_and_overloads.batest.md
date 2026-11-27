# Try-Expression Stack-Empty Normalization : Java Calls, Dynamic Calls, and Overloads

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
```


## JavaConstructorCall : pure static callee and dynamic callee with try arg

```babble
def run = fn io (s: string, f: bool): int -> (
    def p1 = java.awt.Point(1, try (parse(s)) catch : exception then (2))
    def p2 = (if f then (java.awt.Point) else (java.awt.Point))(3, try (parse(s)) catch : exception then (4))
    p1.y + p2.y
)
assert(run("8", false) == 16 and run("bad", true) == 6)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def p1
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jnew Point var($try0) var($try1))))
  (def p2
    (block
      (def $try2 (block (if var(f) (block (select (select name(java).awt).Point)) (block (select (select name(java).awt).Point)))))
      (def $try3 3:int)
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))
      (jnew Point var($try3) var($try4))))
  (+:int (get-jfield Point.y var(p1)) (get-jfield Point.y var(p2)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def p1
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jnew Point var($try0) var($try1))))
  (def p2
    (block
      (def $try2 (if var(f) (select (select name(java).awt).Point) (select (select name(java).awt).Point)))
      (def $try3 3:int)
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))
      (jnew Point var($try3) var($try4))))
  (+:int (get-jfield Point.y var(p1)) (get-jfield Point.y var(p2)))
```

```hir
;; hir -O2 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def p1
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jnew Point var($try0) var($try1))))
  (def p2
    (block
      (def $try2 (if var(f) (select (select name(java).awt).Point) (select (select name(java).awt).Point)))
      (def $try3 3:int)
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))
      (jnew Point var($try3) var($try4))))
  (+:int (get-jfield Point.y var(p1)) (get-jfield Point.y var(p2)))
```

## JavaConstructorCall : dynamic callee containing try (lastTry < 0)

```babble
def run = fn io (f: bool): int -> (
    def p = (try (fail(f, java.awt.Point)) catch : exception then (java.awt.Point))(5, 6)
    p.x + p.y
)
assert(run(false) == 11 and run(true) == 11)
```

```hir
;; hir -O0,-O1 fn=run
fn run (f:bool) -> int {io}
  (def p (jnew Point 5:int 6:int))
  (+:int (get-jfield Point.x var(p)) (get-jfield Point.y var(p)))
```

```hir
;; hir -O2 fn=run
fn run (f:bool) -> int {io}
  (def p (jnew Point 5:int 6:int))
  (+:int (get-jfield Point.x var(p)) (get-jfield Point.y var(p)))
```

## JavaMethodCall : static method, instance method with try arg, and receiver try

```babble
def run = fn io (s: string, f: bool): string -> (
    def m = java.lang.Math.max(1, try (parse(s)) catch : exception then (2))
    def sb = java.lang.StringBuilder()
    sb.append(try (parse(s)) catch : exception then (9))
    (try (fail(f, sb)) catch : exception then (sb)).append(":" + m)
    sb.toString()
)
assert(run("5", false) == "5:5" and run("bad", true) == "9:2")
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> obj {io}
  (def m
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jcall Math.max var($try0) var($try1))))
  (def sb (jnew StringBuilder))
  (block
    (def $try2 var(sb))
    (def $try3
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          9:int)))
    (jcall StringBuilder.append var($try2) var($try3)))
  (jcall StringBuilder.append (block
      (try
        (block
          (call name(fail) var(f) var(sb)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(sb)))) (+:obj ":" var(m)))
  (jcall StringBuilder.toString var(sb))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> obj {io}
  (def m
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jcall Math.max var($try0) var($try1))))
  (def sb (jnew StringBuilder))
  (block
    (def $try2 var(sb))
    (def $try3
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          9:int)))
    (jcall StringBuilder.append var($try2) var($try3)))
  (jcall StringBuilder.append (try
      (block
        (call name(fail) var(f) var(sb)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(sb))) (+:obj ":" var(m)))
  (jcall StringBuilder.toString var(sb))
```

```hir
;; hir -O2 fn=run
fn run (s:obj, f:bool) -> obj {io}
  (def m
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (jcall Math.max var($try0) var($try1))))
  (def sb (jnew StringBuilder))
  (block
    (def $try2 var(sb))
    (def $try3
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          9:int)))
    (jcall StringBuilder.append var($try2) var($try3)))
  (jcall StringBuilder.append (try
      (block
        (call name(fail) var(f) var(sb)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(sb))) (+:obj ":" var(m)))
  (jcall StringBuilder.toString var(sb))
```

## DynamicCall and SafeCall with try in callee or receiver only

```babble
def getOpt = fn io (f: bool): string? -> (if f then (throw exception("e")) else ("abc"))
def run = fn io (f: bool): int -> (
    def g = fn (x: int): int -> (x + 1)
    def a = (try (fail(f, g)) catch : exception then (g))(4)
    def b = (try (getOpt(f)) catch : exception then (null))?.length()
    a + (if b != null then (b) else (0))
)
assert(run(false) == 8 and run(true) == 5)
```

```hir
;; hir -O0 fn=run
fn run (f:bool) -> int {io}
  (def g (fn-ref g))
  (def a (call (block
        (try
          (block
            (call name(fail) var(f) var(g)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(g)))) 4:int))
  (def b (safe-call (block
        (try
          (block
            (call-fn getOpt var(f)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            null)))?.length))
  (+:int var(a) (block (if (<>:bool var(b) null) (block (narrow.nonnull var(b))) (block 0:int))))
```

```hir
;; hir -O1 fn=run
fn run (f:bool) -> int {io}
  (def g (fn-ref g))
  (def a (call (try
        (block
          (call name(fail) var(f) var(g)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(g))) 4:int))
  (def b (safe-call (try
        (block
          (call-fn getOpt var(f)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          null))?.length))
  (+:int var(a) (if (<>:bool var(b) null) (narrow.nonnull var(b)) 0:int))
```

```hir
;; hir -O2 fn=run
fn run (f:bool) -> int {io}
  (def g (fn-ref g))
  (def a (call (try
        (block
          (call name(fail) var(f) var(g)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(g))) 4:int))
  (def b (safe-call (try
        (block
          (call-fn getOpt var(f)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          null))?.length))
  (+:int var(a) (if (<>:bool var(b) null) (narrow.nonnull var(b)) 0:int))
```

## MakeOverloadSet and OverloadDispatch with try in operands, args, and callee

```babble
def fi = fn io (x: int): int -> (x + 1)
def fs = fn io (s: string): int -> (s.length())
def run = fn io (f: bool, u: int | string): int -> (
  def ov = overload(fi, try (fail(f, fs)) catch : exception then (fs))
  def a = ov(try (fail(f, u)) catch : exception then ("ab"))
  def b = (try (fail(f, ov)) catch : exception then (ov))(u)
  a + b
)
assert(run(false, 4) == 10 and run(true, "xyz") == 5)
```

```hir
;; hir -O0 fn=run
fn run (f:bool, u:obj) -> int {io}
  (def ov
    (block
      (def $try0 name(fi))
      (def $try1
        (try
          (block
            (call name(fail) var(f) name(fs)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            name(fs))))
      (overload var($try0) var($try1))))
  (def a
    (block
      (def $try2 var(ov))
      (def $try3
        (try
          (block
            (call name(fail) var(f) var(u)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            "ab")))
      (overload-dispatch var($try2) var($try3))))
  (def b (overload-dispatch (block
        (try
          (block
            (call name(fail) var(f) var(ov)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(ov)))) var(u)))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run (f:bool, u:obj) -> int {io}
  (def ov
    (block
      (def $try0 name(fi))
      (def $try1
        (try
          (block
            (call name(fail) var(f) name(fs)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            name(fs))))
      (overload var($try0) var($try1))))
  (def a
    (block
      (def $try2 var(ov))
      (def $try3
        (try
          (block
            (call name(fail) var(f) var(u)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            "ab")))
      (overload-dispatch var($try2) var($try3))))
  (def b (overload-dispatch (try
        (block
          (call name(fail) var(f) var(ov)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(ov))) var(u)))
  (+:int var(a) var(b))
```

```hir
;; hir -O2 fn=run
fn run (f:bool, u:obj) -> int {io}
  (def ov
    (block
      (def $try0 name(fi))
      (def $try1
        (try
          (block
            (call name(fail) var(f) name(fs)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            name(fs))))
      (overload var($try0) var($try1))))
  (def a
    (block
      (def $try2 var(ov))
      (def $try3
        (try
          (block
            (call name(fail) var(f) var(u)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            "ab")))
      (overload-dispatch var($try2) var($try3))))
  (def b (overload-dispatch (try
        (block
          (call name(fail) var(f) var(ov)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(ov))) var(u)))
  (+:int var(a) var(b))
```
