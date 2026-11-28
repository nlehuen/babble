# Jailbreak : interop J3 Java interface bridge skips inbound null check for inherited Babble default method
<!-- Surface: interop (J3) | Severity: S0/S1 | Repro: POST /crib?modes=all&fresh=true -->

## Inherited Babble interface default method implementing Java generic interface must check inbound null

```babble
def MyBF = interface (
    def apply = fn (k: string, v: string): string -> ("" + v.length())
)
def C = class () implements MyBF, java.util.function.BiFunction of (string, string, string) ()
def m = java.util.HashMap of (string, string) ()
println(m.compute("missing", C()))
```

```error
Type error : expected string, got null
```
