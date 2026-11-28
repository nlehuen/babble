# Jailbreak : ownership O2/O4 match binding pattern keeps outer binding unique alongside sub-bindings
<!-- Surface: ownership (O2/O4) | Severity: S0/S2 | Repro: POST /crib?modes=all&fresh=true -->

## Destructuring binding pattern must not keep outer binding unique alongside sub-binding

```babble
def Box = class (var x: int) ()
def Pair = record (a: Box, b: Box) ()
def test = fn io (): string -> (
    def p = Pair(Box(1), Box(2))
    match move(p)
    case def all : Pair(def first, _) then (
        def fp = freeze(move(all))
        def before = fp.a.x
        first.x = 99
        "" + before + ":" + fp.a.x
    )
    else ("")
)
println(test())
```

```error
freeze
```

## Destructuring binding pattern must link sub-binding paths for mut exclusivity

```babble
def Box = class (var x: int) ()
def swap = fn (mut a: Box, mut b: Box): bool -> (a.x = 1; b.x = 2; same(a, b))
def test = fn io (): bool -> (
    def xs = list of Box (Box(0), Box(1))
    match move(xs)
    case def all : list(def first, ...def rest) then (
        swap(all.get(0), first)
    )
    else (false)
)
println(test())
```

```error
alias
```
