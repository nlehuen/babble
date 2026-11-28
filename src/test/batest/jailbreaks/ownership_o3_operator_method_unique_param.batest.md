# Jailbreak : ownership O3 binary operator method ignores unique parameter requirement
<!-- Surface: ownership (O3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Binary operator method with unique parameter must reject aliased argument

```babble
def Consumer = class () (
    def plus = fn (unique xs: list of int): list of int -> (
        def ys = list of int (from: move(xs))
        ys.add(99)
        ys
    )
)
def test = fn (): string -> (
    def a = list of int (1, 2)
    def b = a
    def c = Consumer() + a
    "" + b.size() + ":" + c.size()
)
println(test())
```

```error
unique
```
