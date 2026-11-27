# Try-Expression Stack-Empty Normalization : Arrays, Collections, and Calls

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def addIO = fn io (a: int, b: int): int -> (a + b)
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
def Box = class (var v: int) (
    def addIO = fn io (a: int, b: int): int -> (this.v + a + b)
    def bumpCompound = fn mut io (s: string): int -> (
        this.v += try (parse(s)) catch : exception then (5)
        this.v
    )
)
def Calc = interface (def calc = fn io (a: int, b: int): int -> (a + b))
def SubCalc = class () implements Calc (
    def calc = fn io (a: int, b: int): int -> (
        super.calc(a, try (parse("" + b)) catch : exception then (0)) + 1
    )
)
```


## ArrayCreate and ArrayOp with try in args, trailing arg, and try in receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def a = (array of int).of(1, try (parse(s)) catch : exception then (2), 9)
    a.set(0, try (parse(s)) catch : exception then (4))
    var v = (try (fail(f, a)) catch : exception then (a)).get(0)
    v + a.get(1) + a.get(2)
)
assert(run("3", false) == 15 and run("x", true) == 15)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def a
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (array-new OF var($try0) var($try1) 9:int)))
  (block
    (def $try2 var(a))
    (def $try3 0:int)
    (def $try4
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (array-op SET var($try2) var($try3) var($try4)))
  (def v (array-op GET (block
        (try
          (block
            (call name(fail) var(f) var(a)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(a)))) 0:int))
  (+:int (+:int var(v) (array-op GET var(a) 1:int)) (array-op GET var(a) 2:int))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def a
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (array-new OF var($try0) var($try1) 9:int)))
  (block
    (def $try2 var(a))
    (def $try3 0:int)
    (def $try4
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (array-op SET var($try2) var($try3) var($try4)))
  (def v (array-op GET (try
        (block
          (call name(fail) var(f) var(a)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(a))) 0:int))
  (+:int (+:int var(v) (array-op GET var(a) 1:int)) (array-op GET var(a) 2:int))
```

```hir
;; hir -O2 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def a
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (array-new OF var($try0) var($try1) 9:int)))
  (block
    (def $try2 var(a))
    (def $try3 0:int)
    (def $try4
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (array-op SET var($try2) var($try3) var($try4)))
  (def v (array-op GET (try
        (block
          (call name(fail) var(f) var(a)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(a))) 0:int))
  (+:int (+:int var(v) (array-op GET var(a) 1:int)) (array-op GET var(a) 2:int))
```

## CoreCollectionCall with try arg and trailing arg after try

```babble
def run = fn io (s: string): int -> (
    def xs = list(1, try (parse(s)) catch : exception then (5), 10)
    xs.get(0) + xs.get(1) + xs.get(2)
)
assert(run("4") == 15 and run("bad") == 16)
```

```hir
;; hir -O0,-O1 fn=run
fn run (s:obj) -> int {io}
  (def xs
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            5:int)))
      (core-new list var($try0) var($try1) 10:int)))
  (+:int (+:int (jcall List.get var(xs) 0:int) (jcall List.get var(xs) 1:int)) (jcall List.get var(xs) 2:int))
```

```hir
;; hir -O2 fn=run
fn run (s:obj) -> int {io}
  (def xs
    (block
      (def $try0 1:int)
      (def $try1
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            5:int)))
      (core-new list var($try0) var($try1) 10:int)))
  (+:int (+:int (jcall List.get var(xs) 0:int) (jcall List.get var(xs) 1:int)) (jcall List.get var(xs) 2:int))
```

## SuperCall and InterfaceCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def c: Calc = SubCalc()
    def a = c.calc(1, try (parse(s)) catch : exception then (2))
    def b = (try (fail(f, c)) catch : exception then (c)).calc(3, 4)
    a + b
)
assert(run("5", false) == 15 and run("bad", true) == 12)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def c (call name(SubCalc)))
  (def a
    (block
      (def $try0 var(c))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-iface var($try0).calc var($try1) var($try2))))
  (def b (call-iface (block
        (try
          (block
            (call name(fail) var(f) var(c)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            var(c)))).calc 3:int 4:int))
  (+:int var(a) var(b))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def c (call name(SubCalc)))
  (def a
    (block
      (def $try0 var(c))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-iface var($try0).calc var($try1) var($try2))))
  (def b (call-iface (try
        (block
          (call name(fail) var(f) var(c)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(c))).calc 3:int 4:int))
  (+:int var(a) var(b))
```

```hir
;; hir -O2 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def c (call name(SubCalc)))
  (def a
    (block
      (def $try0 var(c))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-iface var($try0).calc var($try1) var($try2))))
  (def b (call-iface (try
        (block
          (call name(fail) var(f) var(c)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(c))).calc 3:int 4:int))
  (+:int var(a) var(b))
```

## SelfRecurseCall (non-tail) and SelfTailCall with try arguments

```babble
def nonT = fn io (n: int, s: string): int -> (
  if n <= 0 then (0)
  else (1 + recurse(n - 1, try (fail(s == "x", s)) catch : exception then ("1")))
)
def tailR = fn io (n: int, a: int, s: string): int -> (
  if n <= 0 then (a)
  else (tailR(n - 1, a + try (parse(s)) catch : exception then (2), s))
)
assert(nonT(3, "x") == 3 and tailR(3, 0, "4") == 12 and tailR(3, 0, "b") == 6)
```

```hir
;; hir -O0 fn=nonT
fn nonT (n:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (block
        (def $try0 1:int)
        (def $try1
          (block
            (def $try2 (-:int var(n) 1:int))
            (def $try3
              (try
                (block
                  (call name(fail) (==:bool var(s) "x") var(s)))
                (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                  "1")))
            (self-recurse nonT var($try2) var($try3))))
        (+:int var($try0) var($try1)))))
```

```hir
;; hir -O1 fn=nonT
fn nonT (n:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def $try0 1:int)
      (def $try1
        (block
          (def $try2 (-:int var(n) 1:int))
          (def $try3
            (try
              (block
                (call name(fail) (==:bool var(s) "x") var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                "1")))
          (self-recurse nonT var($try2) var($try3))))
      (+:int var($try0) var($try1))))
```

```hir
;; hir -O0 fn=tailR
fn tailR (n:int, a:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    (block var(a))
    (block
      (block
        (def $try0 (-:int var(n) 1:int))
        (def $try1
          (block
            (def $try2 var(a))
            (def $try3
              (try
                (block
                  (call-fn parse var(s)))
                (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                  2:int)))
            (+:int var($try2) var($try3))))
        (tail-call tailR var($try0) var($try1) var(s)))))
```

```hir
;; hir -O1 fn=tailR
fn tailR (n:int, a:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    var(a)
    (block
      (def $try0 (-:int var(n) 1:int))
      (def $try1
        (block
          (def $try2 var(a))
          (def $try3
            (try
              (block
                (call-fn parse var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                2:int)))
          (+:int var($try2) var($try3))))
      (tail-call tailR var($try0) var($try1) var(s))))
```

```hir
;; hir -O2 fn=tailR
fn tailR (n:int, a:int, s:obj) -> int {io}
  (if (<=:bool var(n) 0:int)
    var(a)
    (block
      (def $try0 (-:int var(n) 1:int))
      (def $try1
        (block
          (def $try2 var(a))
          (def $try3
            (try
              (block
                (call-fn parse var(s)))
              (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
                2:int)))
          (+:int var($try2) var($try3))))
      (tail-call tailR var($try0) var($try1) var(s))))
```

## DirectBabbleCall and DirectThisMethodCall with try in args and receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    def r1 = addIO(1, try (parse(s)) catch : exception then (2))
    def r2 = b.addIO(1, try (parse(s)) catch : exception then (3))
    def r3 = (try (fail(f, b)) catch : exception then (b)).addIO(2, 3)
    r1 + r2 + r3
)
assert(run("5", false) == 37 and run("bad", true) == 32)
```

```hir
;; hir -O0 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (block
```

```hir
;; hir -O1 fn=run check
contains (call-fn addIO var($try1) var($try2))
contains (call-method var($try3).addIO var($try4) var($try5))
contains (def r3 (call-method (try
```

```hir
;; hir -O2 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def b (call name(Box) 10:int))
  (def r1
    (block
      (def $try0 name(addIO))
      (def $try1 1:int)
      (def $try2
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)))
      (call-fn addIO var($try1) var($try2))))
  (def r2
    (block
      (def $try3 var(b))
      (def $try4 1:int)
      (def $try5
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            3:int)))
      (call-method var($try3).addIO var($try4) var($try5))))
  (def r3 (call-method (try
        (block
          (call name(fail) var(f) var(b)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(b))).addIO 2:int 3:int))
  (+:int (+:int var(r1) var(r2)) var(r3))
```
