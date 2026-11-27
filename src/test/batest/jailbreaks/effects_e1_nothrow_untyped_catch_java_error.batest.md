# Jailbreak : effects E1 untyped catch certifies throwing java.lang.Error as nothrow
<!-- Surface: effects (E1) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: fn nothrow compiles without error across interpret, -O0, -O1, and -O2 despite throwing java.lang.Error("boom") which bypasses untyped catch at runtime, printing ESCAPE:NOTHROW:boom. -->

## untyped catch must not certify throwing java.lang.Error as nothrow

```babble
def sneaky = fn nothrow (): int -> (
    try (throw java.lang.Error("boom"))
    catch e then (0)
)
try (sneaky(); println("OK"))
catch t: java.lang.Throwable then (println("ESCAPE:NOTHROW:" + t.getMessage()))
```

```error
nothrow
```
