# Higher-order function callback-effect forwarding (`fn calling f`), subtyping, bound-method mut, and escape...

References : BIP017 (BIP017-D1..D6), BIP016

```babble preamble
def apply = fn calling f (f: fn (x: int): int, v: int): int -> (f(v))
def pipe2 = fn calling (f, g) (v: int, f: fn (x: int): int, g: fn (x: int): int): int -> (g(f(v)))
def mapList of (T, R) = fn calling f (shared xs: list of T, f: fn (x: T): R): list of R -> (
    def out = list of R ()
    for x in xs then ( out.add(f(x)) )
    out
)
def IntSeq = interface ( def forEach: fn calling f (f: fn (x: int): any?) )
def RangeSeq = class (def n: int) implements IntSeq (
    def forEach = fn calling f (f: fn (x: int): any?) -> (
        def i: int = 0
        while i < this.n then ( f(i); i += 1 )
    )
)
def IntBuf = class () (
    def total: int = 0
    def append = fn mut (x: int) -> ( this.total += x )
)
```

## Case 1

```babble
def pureUse = fn (x: int): int -> (apply(fn (n: int): int -> (n * 2), x))
def g: int = 10
g = 20
def volUse = fn volatile (x: int): int -> (apply(fn volatile (n: int): int -> (n + g), x))
assert(pureUse(5) == 10)
assert(volUse(5) == 25)
```

## Case 2

```babble
def ioUse = fn io (x: int): int -> (apply(fn io (n: int): int -> (println("io:", n); n + 1), x))
assert(ioUse(1) == 2)
```

```stdout
io:1
```

## Error : Function bad violates pure guarantee (performs I/O or external mutati...

```babble
def bad = fn (x: int): int -> (apply(fn io (n: int): int -> (println(n); n), x))
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Function bad violates deterministic guarantee : mark function as vola...

```babble
def g: int = 1
g = 2
def bad = fn (x: int): int -> (apply(fn volatile (n: int): int -> (n + g), x))
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Case 5

```babble
def purePipe = fn (x: int): int -> (pipe2(x, fn (a: int): int -> (a + 1), fn (b: int): int -> (b * 3)))
assert(purePipe(4) == 15)
```

## Case 6

```babble
def logPipe2 = fn io calling (f, g) (v: int, f: fn (x: int): int, g: fn (x: int): int): int -> (
    println("pipe")
    g(f(v))
)
assert(logPipe2(4, fn (a: int): int -> (a + 1), fn (b: int): int -> (b * 2)) == 10)
```

```stdout
pipe
```

## Error : Function bad violates pure guarantee (performs I/O or external mutati... (2)

```babble
def bad = fn (x: int): int -> (pipe2(x, fn (a: int): int -> (a + 1), fn io (b: int): int -> (println(b); b)))
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Case 8

```babble
def doubled = fn (shared xs: list of int): list of int -> (
    mapList(xs, fn (x: int): int -> (x * 2))
)
def res = doubled((list of int).of(1, 2, 3))
assert(res.get(0) == 2 and res.get(1) == 4 and res.get(2) == 6)
```

## Error : Function bad violates pure guarantee (performs I/O or external mutati... (3)

```babble
def bad = fn (shared xs: list of int): list of int -> (
    mapList(xs, fn io (x: int): int -> (println(x); x))
)
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Frame-local mutating closure through interface fn calling f

```babble
def sumSeq = fn (seq: IntSeq): int -> (
    def total: int = 0
    seq.forEach(fn (x: int) -> ( total += x ))
    total
)
assert(sumSeq(RangeSeq(5)) == 10)
```

## Bound mutating method buf.append forwarded through interface fn calling f in pure callers

```babble
def collect = fn (seq: IntSeq): int -> (
    def buf = IntBuf()
    seq.forEach(buf.append)
    buf.total
)
def collectInto = fn (seq: IntSeq, mut out: IntBuf) -> ( seq.forEach(out.append) )
def b = IntBuf()
collectInto(RangeSeq(4), b)
assert(collect(RangeSeq(5)) == 10)
assert(b.total == 6)
```

## Error : Cannot mutate parameter out (forwarded mut callback out.append) : mar...

```babble
def applyToOne = fn calling f (f: fn (x: int): any?) -> (f(1))
def bad = fn (out: IntBuf) -> ( applyToOne(out.append) )
```

```error
Cannot mutate parameter out (forwarded mut callback out.append) : mark parameter as mut out
```

## Bound mutating method enforces static exclusivity with receiver

```babble
def SelfSeq = class () (
    def total: int = 0
    def append = fn mut (x: int) -> ( this.total += x )
    def forEach = fn calling f (f: fn (x: int): any?) -> ( f(1) )
)
def bad = fn (mut s: SelfSeq) -> ( s.forEach(s.append) )
```

```error
Exclusivity error : mut target s aliases argument s
```

## Nested forwarding through direct pass-through and capturing closure

```babble
def wrapDirect = fn calling g (g: fn (x: int): int, v: int): int -> (apply(g, v))
def wrapClosure = fn calling g (g: fn (x: int): int, v: int): int -> (
    def acc: int = 0
    acc = apply(fn (x: int): int -> (g(x) + 1), v)
    acc
)
def pureCaller = fn (v: int): int -> (
    wrapDirect(fn (x: int): int -> (x * 2), v) + wrapClosure(fn (x: int): int -> (x * 3), v)
)
assert(pureCaller(4) == 21)
```

## First-class fn calling f widens to monomorphic pure, volatile, and io HOF types

```babble
def asPure: fn (f: fn (x: int): int, v: int): int = apply
def asVol: fn volatile (f: fn volatile (x: int): int, v: int): int = apply
def asIo: fn io (f: fn io (x: int): int, v: int): int = apply
assert(asPure(fn (x: int): int -> (x + 1), 10) == 11)
assert(asVol(fn volatile (x: int): int -> (x + 2), 10) == 12)
assert(asIo(fn io (x: int): int -> (x + 3), 10) == 13)
```

## First-class fn calling f value preserves polymorphic forwarding when passed as a parameter

```babble
def asPoly: fn calling f (f: fn (x: int): int, v: int): int = apply
def run = fn (h: fn calling f (f: fn (x: int): int, v: int): int): int -> (
    def sum: int = 0
    h(fn (x: int): int -> (sum += x; sum), 7)
)
assert(run(asPoly) == 7)
```

## Error : Type error : expected calling f (f : (x : int) : int, v : int) : int,...

```babble
def mono = fn (f: fn (x: int): int, v: int): int -> (f(v))
def bad: fn calling f (f: fn (x: int): int, v: int): int = mono
```

```error
Type error : expected calling f (f : (x : int) : int, v : int) : int, got (f : (x : int) : int, v : int) : int
```

## Error : Type error : expected (f : io (x : int) : int, v : int) : int, got ca...

```babble
def bad: fn (f: fn io (x: int): int, v: int): int = apply
```

```error
Type error : expected (f : io (x : int) : int, v : int) : int, got calling f (f : (x : int) : int, v : int) : int
```

## `with` is a normal identifier (BIP017 uses `calling`), and LocalDate.with works freely

```babble
def with = 30
def d = java.time.LocalDate.of(2026, 1, 15).with(java.time.DayOfWeek.MONDAY)
assert(with + d.getDayOfMonth() == 42)
```

## Error : Unknown parameter in calling clause : g

```babble
def bad = fn calling g (f: fn (x: int): int): int -> (f(1))
```

```error
Unknown parameter in calling clause : g
```

## Error : Duplicate parameter in calling clause : f

```babble
def bad = fn calling (f, f) (f: fn (x: int): int): int -> (f(1))
```

```error
Duplicate parameter in calling clause : f
```

## Error : Parameter in calling clause must be a function : n

```babble
def bad = fn calling n (n: int): int -> (n)
```

```error
Parameter in calling clause must be a function : n
```

## Error : Parameter in calling clause must be borrowed : f

```babble
def bad = fn calling f (shared f: fn (x: int): int): int -> (f(1))
```

```error
Parameter in calling clause must be borrowed : f
```

## Error : Parameter in calling clause must be borrowed : f (2)

```babble
def bad = fn calling f (unique f: fn (x: int): int): int -> (f(1))
```

```error
Parameter in calling clause must be borrowed : f
```

## A callback in calling captured by a returned plain closure escapes by return

```babble
def compose of (A, B, C) = fn calling (f, g) (f: fn (A): B, g: fn (B): C): fn (A): C -> (fn (a: A) -> (g(f(a))))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Returning a calling callback directly would let it run unaccounted

```babble
def leak = fn calling f (f: fn (x: int): int): fn (x: int): int -> (f)
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## compose capturing its callbacks without calling uses shared

```babble
def compose of (A, B, C) = fn (shared f: fn (A): B, shared g: fn (B): C): fn (A): C -> (fn (a: A) -> (g(f(a))))
assert(compose(fn (x: int): int -> (x + 1), fn (x: int): string -> ("v" + x))(41) == "v42")
```

## BIP017-D4 : fn io HOF is not implicitly specialized intra-file when given a pure callback

```babble
def applyIo = fn io (f: fn io (x: int): int, v: int): int -> (f(v))
def bad = fn (x: int): int -> (applyIo(fn (n: int): int -> (n + 1), x))
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## SymbolicEffectSummary : thisFlow (mutThis propagation) and extFlow (external mutation -> io)

```babble
def Cell = class (def v: int) (
    def applySelf = fn mut calling f (f: fn (mut Cell): int): int -> (f(this))
)
def g = Cell(1)
def applyGlobal = fn io calling f (f: fn (mut Cell): int): int -> (f(g))
def inc = fn (mut c: Cell): int -> (c.v += 1 ; c.v)
def c = Cell(10)
assert(c.applySelf(inc) == 11 and applyGlobal(inc) == 2)
```

## SymbolicEffectSummary : non-borrowed (`shared`) callback parameter propagates shared to HOF arg

```babble
def sink = (list of (java.lang.StringBuilder))()
def passTo = fn calling f (shared b: java.lang.StringBuilder, f: fn io (shared java.lang.StringBuilder)) -> (f(b))
def keep = fn io (shared b: java.lang.StringBuilder) -> (sink.add(b) ; null)
passTo(java.lang.StringBuilder("hi"), keep)
assert(sink.size() == 1)
```
