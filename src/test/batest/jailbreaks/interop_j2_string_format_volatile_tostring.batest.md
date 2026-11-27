# Jailbreak : `interop` (`J2`) `String.format` and `String.formatted` curated deterministic despite calling `volatile` `toString()`

- **Surface:** `interop`
- **Vector:** `J2` (Curated JDK axiom soundness)
- **Severity:** `S2` (Determinism guarantee bypass: pure deterministic function executes `volatile` `toString()` and returns non-deterministic output across runs/modes)
- **Observed:** `java.lang.String.format` and `java.lang.String.formatted` are curated as deterministic (`fn`) rather than `fn volatile` in `curated.ba`, even though `%s` dispatches to `Object.toString()` on arbitrary arguments (which may be `fn volatile`). Calling `String.format("%s", Vol())` or `"%s".formatted(Vol())` inside a pure deterministic function compiles with zero errors and prints distinct identity hash codes across modes.

## String.format with volatile toString must not be accepted in deterministic function

```babble
def Vol = class () (
    def toString = fn volatile (): string -> ("" + java.lang.Object().hashCode())
)
def pureDet = fn (shared x: any): string -> (
    java.lang.String.format("%s", x)
)
pureDet(Vol())
```

```error
Function pureDet violates deterministic guarantee
```

## String.formatted with volatile toString must not be accepted in deterministic function

```babble
def Vol = class () (
    def toString = fn volatile (): string -> ("" + java.lang.Object().hashCode())
)
def pureDet = fn (shared x: any): string -> (
    "%s".formatted(x)
)
pureDet(Vol())
```

```error
Function pureDet violates deterministic guarantee
```
