# Module error paths : missing modules, cycles, non-string import path, export outside top level, Java packag...

References : BIP005 (modules & imports)

```babble
;; babble module=mut_b.ba
def a = import("mut_a.ba")
export def name = "b"
```

```babble
;; babble module=mut_a.ba
def b = import("mut_b.ba")
export def name = "a"
export def tag = fn () : string -> ( b.name + ":" + name )
```

```babble
;; babble module=cycle_b.ba
def a = import("cycle_a.ba")
export def y = "b" + a.x
```

```babble
;; babble module=cycle_a.ba
def b = import("cycle_b.ba")
export def x = "a"
```

## Error : Cannot import missing.ba : no such module

```babble
def x = import("missing.ba")
```

```error
Cannot import missing.ba : no such module
```

## Mutual module imports where helper reads export from non-cyclic dep chain work

```babble
def a = import("mut_a.ba")
assert(a.tag() == "b:a")
```

## Cycle reading unannotated export before definition reports cycle error

```babble
def a = import("cycle_a.ba")
```

```error
Missing type : x (import cycle with cycle_a.ba)
```

## Error : Type error : expected string, got int

```babble
def n = import(1)
```

```error
Type error : expected string, got int
```

## Error : export only applies to the top-level definitions of a file : bad

```babble
def f = fn () -> (
    export def bad = 1
)
```

```error
export only applies to the top-level definitions of a file : bad
```

## Java package import via import("java.util")

```babble
def util = import("java.util")
def xs : java.util.List of string = util.ArrayList()
xs.add("hi")
assert(xs.size() == 1)
assert(xs.get(0) == "hi")
```

## Mutual recursion across cyclic modules via shared typed holder

```babble
;; babble module=parity.ba
export def state = object (
    var even: (fn volatile (n: int): bool)? = null
    var odd: (fn volatile (n: int): bool)? = null
)
null
```

```babble
;; babble module=even.ba
def parity = import("parity.ba")
def odd = import("odd.ba")
export def isEven = fn volatile (n: int): bool -> (
    if n == 0 then ( true ) else (
        def f = parity.state.odd
        if f != null then ( f(n - 1) ) else ( false )
    )
)
parity.state.even = isEven
```

```babble
;; babble module=odd.ba
def parity = import("parity.ba")
def even = import("even.ba")
export def isOdd = fn volatile (n: int): bool -> (
    if n == 0 then ( false ) else (
        def f = parity.state.even
        if f != null then ( f(n - 1) ) else ( false )
    )
)
parity.state.odd = isOdd
```

```babble
def even = import("even.ba")
assert(even.isEven(10))
assert(not even.isEven(7))
```
