# Module error paths : missing modules, cycles, non-string import path, export outside top level, Java packag...

References : BIP005 (modules & imports)

```babble module=mut_b.ba
def a = import("mut_a.ba")
export def name = "b"
```

```babble module=mut_a.ba
def b = import("mut_b.ba")
export def name = "a"
export def tag = fn () : string -> ( b.name + ":" + name )
```

```babble module=cycle_b.ba
def a = import("cycle_a.ba")
export def y = "b" + a.x
```

```babble module=cycle_a.ba
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
