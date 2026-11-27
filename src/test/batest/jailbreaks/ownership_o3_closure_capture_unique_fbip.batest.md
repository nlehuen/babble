# Jailbreak : ownership O3 closure capture fails to invalidate unique (FBIP S0 corruption)
<!-- Surface: ownership (O3) / opt (R1) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: parityOk=false — interpret & -O0 print 2:3 while -O1, -O2, wasm-O1, and wasm-O2 print 3:3 (FbipReusePass mutates the closure-captured list in place because closure capture did not invalidate xs's uniqueness). -->

## closure capturing unique local must invalidate uniqueness before move

```babble
def consume = fn (unique xs: list of int): list of int -> (
    def ys = list of int (from: move(xs))
    ys.add(99)
    ys
)
def test = fn volatile (): string -> (
    def xs = list of int (1, 2)
    def f = fn volatile (): int -> (xs.size())
    def ys = consume(move(xs))
    "" + f() + ":" + ys.size()
)
println(test())
```

```error
Argument for unique parameter xs must be unique
```
