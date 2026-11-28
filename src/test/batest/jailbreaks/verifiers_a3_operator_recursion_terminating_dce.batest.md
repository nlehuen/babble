# Jailbreak : verifiers A3 operator method recursion hidden from call graph and SCP
<!-- Surface: verifiers (A3) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Recursive unary neg operator method must not be certified terminating and deleted by HirDcePass

```babble
def Bomb = class () (
    def neg = fn nothrow (): int -> (-this)
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.neg()
    42
)
println(run())
```

```error
Stack overflow
```

## Recursive binary plus operator method must not be certified terminating and deleted by HirDcePass

```babble
def Bomb = class () (
    def plus = fn nothrow (n: int): int -> (
        if n <= 0 then (0)
        else (
            def x = this + n
            x + this.plus(n - 1)
        )
    )
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.plus(1)
    42
)
println(run())
```

```error
Stack overflow
```
