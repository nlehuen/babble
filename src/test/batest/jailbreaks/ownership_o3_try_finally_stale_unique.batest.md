# Jailbreak : ownership O3 try finally returns stale pre-finally unique origin
<!-- Surface: ownership (O3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Local stored into mut parameter in finally must not be returned as unique

```babble
def LB = class (var xs: (list of int)?) ()
def consume = fn (unique xs: list of int): list of int -> (
    def ys = list of int (from: move(xs))
    ys.add(99)
    ys
)
def sneak = fn (mut b: LB): list of int -> (
    def a = list of int (1, 2)
    try (a) finally (b.xs = a)
)
def test = fn (): string -> (
    def box = LB(null)
    def r = consume(sneak(box))
    "" + box.xs!.size() + ":" + r.size()
)
println(test())
```

```error
unique
```
