# Try-Expression Stack-Empty Normalization : Operators and Field Writes

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

```hir
;; hir -O2 fn=rhsTry
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

```hir
;; hir -O2 fn=logicTry
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
var top = 10
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

```hir
;; hir -O2 fn=bumpTop
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

```hir
;; hir -O2 fn=run
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

```hir
;; hir -O2 fn=run
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

```hir
;; hir -O2 fn=run
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
