# Try-Expression Stack-Empty Normalization : Widening Ranks and Control Flow

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
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
      (def $try1 (widen[short] var(b)))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 (convert[int] var(sh)))
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
      (def $try1 (widen[short] var(b)))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 (convert[int] var(sh)))
      (def $try4
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1:int)))
      (+:int var($try3) var($try4))))
  (+:int var(r1) var(r2))
```

```hir
;; hir -O2 fn=run
fn run (b:byte, sh:short, s:obj) -> int {io}
  (def r1
    (block
      (def $try0 name(addSh))
      (def $try1 (widen[short] var(b)))
      (def $try2
        (try
          (block
            (call name(short) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call name(short) 1:int))))
      (call-fn addSh var($try1) var($try2))))
  (def r2
    (block
      (def $try3 (convert[int] var(sh)))
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
    (def $try0 (convert[long] var(n)))
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
    (def $try0 (convert[long] var(n)))
    (def $try1
      (try
        (block
          (call name(long) (call-fn parse var(s))))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          1:long)))
    (+:long var($try0) var($try1)))
```

```hir
;; hir -O2 fn=run
fn run (n:int, s:obj) -> long {io}
  (block
    (def $try0 (convert[long] var(n)))
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
      (def $try0 (convert[float] var(n)))
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
      (def $try2 (convert[double] var(n)))
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
      (def $try0 (convert[float] var(n)))
      (def $try1
        (try
          (block
            (call name(float) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:float)))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 (convert[double] var(n)))
      (def $try3
        (try
          (block
            (call name(double) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:double)))
      (+:double var($try2) var($try3))))
  (+:double (call name(double) var(r4)) var(r5))
```

```hir
;; hir -O2 fn=run
fn run (n:int, s:obj) -> double {io}
  (def r4
    (block
      (def $try0 (convert[float] var(n)))
      (def $try1
        (try
          (block
            (call name(float) (call-fn parse var(s))))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            1.0:float)))
      (+:float var($try0) var($try1))))
  (def r5
    (block
      (def $try2 (convert[double] var(n)))
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

```hir
;; hir -O2 fn=run
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
    var w = 0 ; while (try (parse(s) > w) catch : exception then (false)) then (w += 1)
    var wb = 0 ; while wb < 1 then (wb += try (parse(s)) catch : exception then (1))
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

```hir
;; hir -O2 fn=run
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
    var fs = 0
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

```hir
;; hir -O2 fn=run
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
    (def $sr_p_1 2:int))
  (+:int var(fs) 3:int)
```
