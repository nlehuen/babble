# Jailbreak : ownership O4 mut exclusivity bypassed by container+element and recurse(this)
<!-- Surface: ownership (O4) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->

## Passing container holding mutable object alongside that object must fail mut exclusivity

```babble
def Box = class (var n: int) ()
def Holder = class (var b: Box) ()
def collide = fn (mut h: Holder, mut b: Box): int -> (
    h.b.n = 99
    b.n
)
def b = Box(1)
def h = Holder(b)
println(collide(h, b))
```

```error
Exclusivity error
```

## Self-recursive mut method calling recurse(this, ...) must fail mut exclusivity

```babble
def Box = class (var n: int) (
    def aliasSelf = fn mut (mut other: Box, depth: int): int -> (
        this.n = this.n + 1
        other.n = other.n + 10
        if depth > 0 then (recurse(this, depth - 1)) else (this.n)
    )
)
def b = Box(0)
println(b.aliasSelf(Box(0), 1))
```

```error
Exclusivity error
```
