# Higher-order calling clauses naming callbacks captured from an enclosing function (compose, currying, escap...

References : BIP017 (BIP017-D5, BIP017-D6), BIP016 (BIP016-D10)

```babble
;; babble module=combinators.ba
export def compose of (A, B, C) = fn (f: fn (A): B, g: fn (B): C): fn calling (f, g) (A): C -> (fn calling (f, g) (a: A) -> (g(f(a))))
```

```babble
;; babble preamble
def compose of (A, B, C) = fn (f: fn (A): B, g: fn (B): C): fn calling (f, g) (A): C -> (fn calling (f, g) (a: A) -> (g(f(a))))

def inc = fn (x: int): int -> (x + 1)
def dbl = fn (x: int): int -> (x * 2)
def ioId = fn io (x: int): int -> (println("in " + x); x)

def Holder = class () (
    def cb: (fn (x: int): int)? = null
    def put = fn mut (shared f: fn (x: int): int) -> (this.cb = f)
)
```

## Case 1

```babble
def show: fn (x: int): string = compose(inc, fn (x: int): string -> ("v" + x))
def p = fn (x: int): string -> (show(x))
assert(p(41) == "v42")
```

## Case 2

```babble
def loud: fn io (x: int): int = compose(fn io (x: int): int -> (println("in " + x); x + 1), dbl)
assert(loud(3) == 8)
```

```stdout
in 3
```

## Error : Type error : expected (x : int) : int, got io (p0 : int) : int

```babble
def bad: fn (x: int): int = compose(ioId, dbl)
```

```error
Type error : expected (x : int) : int, got io (p0 : int) : int
```

## Error : Function p violates pure guarantee (performs I/O or external mutation...

```babble
def p = fn (x: int): int -> (compose(ioId, inc)(x))
```

```error
Function p violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Inferred result type : the closure's own summary takes f and g as pure, yet io still reaches p

```babble
def composeInf = fn (f: fn (int): int, g: fn (int): int) -> (fn calling (f, g) (a: int): int -> (g(f(a))))
def p = fn (x: int): int -> (composeInf(ioId, inc)(x))
```

```error
Function p violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## A closure forwarding captured callbacks is not a subtype of a plain function type

```babble
def badCompose of (A, B, C) = fn (f: fn (A): B, g: fn (B): C): fn (A): C -> (fn calling (f, g) (a: A) -> (g(f(a))))
```

```error
Type error : expected (p0 : A) : C, got calling (f, g) (a : A) : C
```

## Error : Parameter in calling clause must be a function : n

```babble
def k = fn (f: fn (int): int, n: int): fn calling (f, n) (int): int -> (fn calling (f, n) (a: int): int -> (f(a) + n))
```

```error
Parameter in calling clause must be a function : n
```

## A parameter type cannot name a sibling parameter in its calling clause

```babble
def k = fn (f: fn (int): int, h: fn calling f (int): int): int -> (h(f(1)))
```

```error
Unknown parameter in calling clause : f
```

## Currying : captured callbacks of nested function types are instantiated one call at a time (pure)

```babble
def curry = fn (f: fn (int): int): fn (g: fn (int): int): fn calling (f, g) (int): int -> (
    fn (g: fn (int): int): fn calling (f, g) (int): int -> (fn calling (f, g) (a: int): int -> (g(f(a))))
)
def p = fn (x: int): int -> (curry(inc)(fn (y: int): int -> (y * 10))(x))
assert(p(1) == 20)
```

## Currying : captured callbacks of nested function types are instantiated one call at a time (io)

```babble
def curry = fn (f: fn (int): int): fn (g: fn (int): int): fn calling (f, g) (int): int -> (
    fn (g: fn (int): int): fn calling (f, g) (int): int -> (fn calling (f, g) (a: int): int -> (g(f(a))))
)
def loud: fn io (x: int): int = curry(ioId)(fn (y: int): int -> (y))
assert(loud(7) == 7)
```

```stdout
in 7
```

## Calling a forwarding closure inside the capturing function inherits the callbacks' declared effects (pure)

```babble
def ok = fn (f: fn (int): int): int -> (
    def h = fn calling f (a: int): int -> (f(a) + 1)
    h(1)
)
assert(ok(fn (x: int): int -> (x * 5)) == 6)
```

## Calling a forwarding closure inside the capturing function inherits the callbacks' declared effects (io rej...

```babble
def bad = fn (f: fn io (int): int): int -> (
    def h = fn calling f (a: int): int -> (f(a) + 1)
    h(1)
)
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Case 13

```babble
def combinators = import("combinators.ba")
def show = fn (x: int): string -> (combinators.compose(inc, fn (y: int): string -> ("v" + y))(x))
assert(show(41) == "v42")
def loud: fn io (x: int): int = combinators.compose(ioId, inc)
assert(loud(1) == 2)
```

```stdout
in 1
```

## Error : Function p violates pure guarantee (performs I/O or external mutation... (2)

```babble
def combinators = import("combinators.ba")
def p = fn (x: int): int -> (combinators.compose(ioId, inc)(x))
```

```error
Function p violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Across session inputs (compose comes from preamble input 0)

```babble
;; babble session
def loud: fn io (x: int): int = compose(ioId, fn (y: int): int -> (y * 3))
```

```babble
;; babble session
assert(loud(2) == 6)
```

```stdout
in 2
```

## Case 16

```babble
def composeShared of (A, B, C) = fn (shared f: fn (A): B, shared g: fn (B): C): fn calling (f, g) (A): C -> (fn calling (f, g) (a: A) -> (g(f(a))))
assert(composeShared(inc, fn (x: int): string -> ("v" + x))(41) == "v42")
```

## A calling callback may be passed to compose, whose result aliases it (BIP017-D6) and is only called

```babble
def twice = fn calling f (f: fn (x: int): int, x: int): int -> (compose(f, f)(x))
def p = fn (x: int): int -> (twice(fn (y: int): int -> (y * 3), x))
assert(p(2) == 18)
```

## Error : Function p violates pure guarantee (performs I/O or external mutation... (3)

```babble
def twice = fn calling f (f: fn (x: int): int, x: int): int -> (compose(f, f)(x))
def p = fn (x: int): int -> (twice(ioId, x))
```

```error
Function p violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Returning the composition of a calling callback lets it escape by return

```babble
def both = fn calling f (f: fn (x: int): int): fn (x: int): int -> (compose(f, f))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Error : Parameter in calling clause escapes via return : remove f from calling

```babble
def back = fn (f: fn (x: int): int): fn calling f (x: int): fn (y: int): int -> (fn calling f (x: int) -> (f))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Error : Parameter in calling clause escapes via call to h.put : remove f from...

```babble
def keep = fn (mut h: Holder, f: fn (x: int): int): fn io calling f (x: int): int -> (fn io calling f (x: int): int -> (h.put(f); f(x)))
```

```error
Parameter in calling clause escapes via call to h.put : remove f from calling and mark it as shared
```

## A closure capturing a borrowed parameter may be returned (BIP017-D6)

```babble
def wrap = fn (f: fn (x: int): int): fn (x: int): int -> (fn (x: int): int -> (f(x) + 1))
assert(wrap(dbl)(20) == 41)
```

## A closure capturing a borrowed parameter may not be stored in a field

```babble
def wrap = fn (f: fn (x: int): int): fn (x: int): int -> (fn (x: int): int -> (f(x) + 1))
def stash = fn (mut h: Holder, f: fn (x: int): int) -> (h.put(wrap(f)))
```

```error
Borrowed parameter f escapes via call to h.put : mark parameter as shared f
```
