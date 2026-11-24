# HIR try-expression normalization : binary, assignment, array, collection, and Babble call spilling

References : `BIP001-I2`, `BIP014-I2`, `org.babblelang.opt.hir.HirTryNormalize`.

```babble
;; babble preamble
def parse = fn io (s: string): int -> (try (java.lang.Integer.parseInt(s)) catch then (throw exception("bad")))
def addIO = fn io (a: int, b: int): int -> (a + b)
def fail of T = fn io (f: bool, v: T): T -> (if f then (throw exception("e")) else (v))
def Box = class (def v: int) (
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

## Binary LHS try vs RHS try spilling

```babble
def lhsTry = fn io (s: string): int -> (
    (try (parse(s)) catch : exception then (10)) + 1
)
def rhsTry = fn io (s: string): int -> (
    10 + (try (parse(s)) catch : exception then (2))
)
assert(lhsTry("4") == 5 and lhsTry("x") == 11 and rhsTry("3") == 13 and rhsTry("x") == 12)
```

```hir
;; hir -O0 fn=lhsTry
fn lhsTry (s:obj) -> int {io}
  (+:int (block
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          10:int))) 1:int)
```

```hir
;; hir -O1 fn=lhsTry
fn lhsTry (s:obj) -> int {io}
  (+:int (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        10:int)) 1:int)
```

```hir
;; hir -O0 fn=rhsTry
fn rhsTry (s:obj) -> int {io}
  (block
    (def $try0 10:int)
    (def $try1
      (block
        (try
          (block
            (call-fn parse var(s)))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int))))
    (+:int var($try0) var($try1)))
```

```hir
;; hir -O1 fn=rhsTry
fn rhsTry (s:obj) -> int {io}
  (block
    (def $try0 10:int)
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          2:int)))
    (+:int var($try0) var($try1)))
```

## Logic short-circuit containsTry on left and right operands

```babble
def logicTry = fn io (f: bool, s: string): bool -> (
    def a = (try (parse(s) > 0) catch : exception then (false)) and f
    def b = f and (try (parse(s) > 0) catch : exception then (false))
    a and b
)
assert(logicTry(true, "1") and not logicTry(true, "x") and not logicTry(false, "x"))
```

```hir
;; hir -O0 fn=logicTry
fn logicTry (f:bool, s:obj) -> bool {io}
  (def a (and (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool))) var(f)))
  (def b (and var(f) (block
        (try
          (block
            (>:bool (call-fn parse var(s)) 0:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            false:bool)))))
  (and var(a) var(b))
```

```hir
;; hir -O1 fn=logicTry
fn logicTry (f:bool, s:obj) -> bool {io}
  (def a (and (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool)) var(f)))
  (def b (and var(f) (try
        (block
          (>:bool (call-fn parse var(s)) 0:int))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          false:bool))))
  (and var(a) var(b))
```

## Compound NameAssign and ThisFieldAssign with try RHS

```babble
def top = 10
def bumpTop = fn io (s: string): int -> (
    top += try (parse(s)) catch : exception then (5)
    top
)
def b = Box(10)
assert(bumpTop("3") == 13 and bumpTop("x") == 18)
assert(b.bumpCompound("4") == 14 and b.bumpCompound("x") == 19)
```

```hir
;; hir -O0,-O1 fn=bumpTop
fn bumpTop (s:obj) -> int {io}
  (set-name top += (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        5:int)))
  name(top)
```

## JavaFieldAssign : instance simple, compound, and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def pt = java.awt.Point(1, 2)
    pt.x = try (parse(s)) catch : exception then (7)
    pt.y += try (parse(s)) catch : exception then (3)
    (try (fail(f, pt)) catch : exception then (pt)).x = 9
    pt.x + pt.y
)
assert(run("5", false) == 16 and run("bad", true) == 14)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (block
    (def $try0 var(pt))
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          7:int)))
    (set-jfield x var($try0) var($try1)))
  (set-jfield y var(pt) (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        3:int)))
  (set-jfield x (block
      (try
        (block
          (call name(fail) var(f) var(pt)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(pt)))) 9:int)
  (+:int (get-jfield Point.x var(pt)) (get-jfield Point.y var(pt)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (block
    (def $try0 var(pt))
    (def $try1
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          7:int)))
    (set-jfield x var($try0) var($try1)))
  (set-jfield y var(pt) (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        3:int)))
  (set-jfield x (try
      (block
        (call name(fail) var(f) var(pt)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(pt))) 9:int)
  (+:int (get-jfield Point.x var(pt)) (get-jfield Point.y var(pt)))
```

## JavaFieldAssign : static pure receiver chain with try RHS

```babble
def run = fn io (s: string): int -> (
    def Demo = import("org.babblelang.tests").JavaClassDemo
    Demo.counter = try (parse(s)) catch : exception then (4)
)
assert(run("5") == 5 and run("bad") == 4)
```

```hir
;; hir -O0,-O1 fn=run
fn run (s:obj) -> int {io}
  (def Demo (select (call name(import) "org.babblelang.tests").JavaClassDemo))
  (block
    (def $try0
      (try
        (block
          (call-fn parse var(s)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          4:int)))
    (set-jfield counter var(Demo) var($try0)))
```

## DynamicFieldAssign with compound operator and receiver try

```babble
def run = fn io (s: string, f: bool): int -> (
    def b = Box(10)
    b.v += try (parse(s)) catch : exception then (2)
    (try (fail(f, b)) catch : exception then (b)).v = b.v + 1
    b.v
)
assert(run("5", false) == 16 and run("x", true) == 13)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def b (call name(Box) 10:int))
  (set-field var(b).v (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        2:int)))
  (set-field (block
      (try
        (block
          (call name(fail) var(f) var(b)))
        (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
          var(b)))).v (+:int (get-this-field v) 1:int))
  (get-this-field v)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, f:bool) -> int {io}
  (def b (call name(Box) 10:int))
  (set-field var(b).v (try
      (block
        (call-fn parse var(s)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        2:int)))
  (set-field (try
      (block
        (call name(fail) var(f) var(b)))
      (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
        var(b))).v (+:int (get-this-field v) 1:int))
  (get-this-field v)
```

## ArrayCreate and ArrayOp with try in args, trailing arg, and try in receiver

```babble
def run = fn io (s: string, f: bool): int -> (
    def a = (array of int).of(1, try (parse(s)) catch : exception then (2), 9)
    a.set(0, try (parse(s)) catch : exception then (4))
    def v = (try (fail(f, a)) catch : exception then (a)).get(0)
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
