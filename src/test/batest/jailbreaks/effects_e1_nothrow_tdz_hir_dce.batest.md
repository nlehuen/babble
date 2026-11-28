# Jailbreak : effects E1 nothrow vs TDZ throws deleted by HirDcePass
<!-- Surface: effects (E1) | Severity: S0/S2 | Repro: POST /crib?modes=all&fresh=true -->

## Top-level forward def TDZ throw must not be certified nothrow or deleted by DCE

```babble
def peek = fn nothrow (): int -> (s.length())
def run = fn (): int -> (
    def u = peek()
    42
)
println(run())
def s: string = "hello"
```

```error
Name used before definition : s
```

## Class inline field forward TDZ throw must not be certified nothrow or deleted by DCE

```babble
def C = class () (
    def peek = fn nothrow (): int -> (this.s.length())
    def a: int = this.peek()
    def s: string = "hello"
)
def safe = fn nothrow (): int -> (C().a)
def run = fn (): int -> (
    def u = safe()
    42
)
println(run())
```

```error
Member used before definition : s
```
