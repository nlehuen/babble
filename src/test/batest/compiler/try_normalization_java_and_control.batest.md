# HIR try-expression normalization : Java calls, overloads, widening ranks, and control-flow containsTry

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
  (+:int var(a) (block (if (<>:bool var(b) null) (block var(b)) (block 0:int))))
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
  (+:int var(a) (if (<>:bool var(b) null) var(b) 0:int))
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

## Effective widening ranks 1..2 (byte->short, short->int) during try spilling

```babble
def addSh = fn io (a: short, b: short): int -> (a + b)
def run = fn io (b: byte, sh: short, s: string): int -> (
  def r1 = addSh(b, try (short(parse(s))) catch : exception then (short(1)))
  def r2 = sh + (try (parse(s)) catch : exception then (1))
  r1 + r2
)
assert(run(byte(2), short(3), "5") == 15 and run(byte(2), short(3), "x") == 7)
```

```hir
;; hir -O0 fn=run
fn run (b:byte, sh:short, s:obj) -> int {io}
  (def r1
    (block
      (def $try0 name(addSh))
      (def $try1 var(b))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 var(sh))
      (def $try4
        (block
          (try
            (block
              (call-fn parse var(s)))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1:int))))
      (+:int var($try3) var($try4))))
  (+:int var(r1) var(r2))
```

```hir
;; hir -O1 fn=run
fn run (b:byte, sh:short, s:obj) -> int {io}
  (def r1
    (block
      (def $try0 name(addSh))
      (def $try1 var(b))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 var(sh))
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:int)))
      (+:int var($try3) var($try4))))
  (+:int var(r1) var(r2))
```

## Effective widening rank 3 (int->long) during try spilling

```babble
def run = fn io (n: int, s: string): long -> (
  n + (try (long(parse(s))) catch : exception then (1L))
)
assert(run(4, "5") == 9L and run(4, "x") == 5L)
```

```hir
;; hir -O0 fn=run
fn run (n:int, s:obj) -> long {io}
  (block
    (def $try0 var(n))
    (def $try1
      (block
        (try
          (block
            (call name(long) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:long))))
    (+:long var($try0) var($try1)))
```

```hir
;; hir -O1 fn=run
fn run (n:int, s:obj) -> long {io}
  (block
    (def $try0 var(n))
    (def $try1
      (try
        (block
          (call name(long) (call-fn parse var(s))))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          1:long)))
    (+:long var($try0) var($try1)))
```

## Effective widening ranks 4..5 (int->float, int->double) during try spilling

```babble
def run = fn io (n: int, s: string): double -> (
    def r4 = n + (try (float(parse(s))) catch : exception then (1.0f))
    def r5 = n + (try (double(parse(s))) catch : exception then (1.0))
    double(r4) + r5
)
assert(run(4, "5") == 18.0 and run(4, "x") == 10.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, s:obj) -> double {io}
  (def r4
    (block
      (def $try0 var(n))
      (def $try1
        (block
          (try
            (block
              (call name(float) (call-fn parse var(s))))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1.0:float))))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 var(n))
      (def $try3
        (block
          (try
            (block
              (call name(double) (call-fn parse var(s))))
            (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
              1.0:double))))
      (+:double var($try2) var($try3))))
  (+:double (call name(double) var(r4)) var(r5))
```

```hir
;; hir -O1 fn=run
fn run (n:int, s:obj) -> double {io}
  (def r4
    (block
      (def $try0 var(n))
      (def $try1
        (try
          (block
            (call name(float) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:float)))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 var(n))
      (def $try3
        (try
          (block
            (call name(double) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:double)))
      (+:double var($try2) var($try3))))
  (+:double (call name(double) var(r4)) var(r5))
```

## Control-flow containsTry : If (cond, then, else)

```babble
def run = fn io (s: string, f: bool): int -> (
    def i1 = if (try (parse(s) > 0) catch : exception then (false)) then (1) else (2)
    def i2 = if f then (try (parse(s)) catch : exception then (3)) else (try (parse(s) + 1) catch : exception then (4))
    i1 + i2
)
assert(run("2", false) == 4 and run("bad", true) == 5)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def i1 (if (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool))) (block 1:int) (block 2:int)))
  (def i2
    (if var(f)
      (block
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            3:int)))
      (block
        (try
          (block
            (+:int (call-fn parse var(s)) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            4:int)))))
  (+:int var(i1) var(i2))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def i1 (if (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)) 1:int 2:int))
  (def i2
    (if var(f)
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          3:int))
      (try
        (block
          (+:int (call-fn parse var(s)) 1:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int))))
  (+:int var(i1) var(i2))
```

## Control-flow containsTry : While (cond, body)

```babble
def run = fn io (s: string): int -> (
    def w = 0 ; while (try (parse(s) > w) catch : exception then (false)) then (w += 1)
    def wb = 0 ; while wb < 1 then (wb += try (parse(s)) catch : exception then (1))
    w + wb
)
assert(run("2") == 4 and run("bad") == 1)
```

```hir
;; hir -O0 fn=run
fn run (s:obj) -> int {io}
  (def w 0:int)
  (while (block
      (try
        (block
          (>:bool (call-fn parse var(s)) var(w)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)))
    (block (set w += 1:int)))
  (def wb 0:int)
  (while (<:bool var(wb) 1:int)
    (block (set wb +=
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:int)))))
  (+:int var(w) var(wb))
```

```hir
;; hir -O1 fn=run
fn run (s:obj) -> int {io}
  (def w 0:int)
  (while (try
      (block
        (>:bool (call-fn parse var(s)) var(w)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        false:bool))
    (set w += 1:int))
  (def wb 0:int)
  (while (<:bool var(wb) 1:int)
    (set wb +=
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          1:int))))
  (+:int var(w) var(wb))
```

## Control-flow containsTry : For (iterable, body) and RecordNew classRef

```babble
def Pair = record (a: int, b: int) ()
def run = fn io (s: string, f: bool): int -> (
    def fs = 0
    for e in (try (fail(f, list(1, 2))) catch : exception then (list(3))) then (
        fs += e + (try (parse(s)) catch : exception then (0))
    )
    def p = (try (fail(f, Pair)) catch : exception then (Pair))(1, 2)
    fs + p.a + p.b
)
assert(run("2", false) == 10 and run("bad", true) == 6)
```

```hir
;; hir -O0 fn=run check
contains (def $try0 var(e))
contains (def $try2
contains (record-new Pair 1:int 2:int)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def fs 0:int)
  (for e in (try
      (block
        (call name(fail) var(f) (core-new list 1:int 2:int)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        (core-new list 3:int)))
    (block
      (set fs +=
        (block
          (def $try0 var(e))
          (def $try1
            (try
              (block
                (call-fn parse var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                0:int)))
          (+:int var($try0) var($try1))))))
  (block
    (try
      (block
        (call name(fail) var(f) name(Pair)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        name(Pair)))
    (def $sr_p_0 1:int)
    (def $sr_p_1 2:int))
  (+:int (+:int var(fs) var($sr_p_0)) var($sr_p_1))
```
