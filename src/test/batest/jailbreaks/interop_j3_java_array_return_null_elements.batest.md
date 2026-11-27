# Jailbreak : `interop` (`J3`) Java array returns are trusted to hold non-null elements

- **Surface:** `interop`
- **Vector:** `J3` (Java generic and array return boundary nullability)
- **Severity:** `S1` (`null` inside a statically non-null `array of string`, cast-free)
- **Repro:** `POST /crib?modes=interpret,O0,O1,O2&fresh=true`
- **Observed:** `java.util.Arrays.copyOf(array.of("x"), 3)` (padding with `null`) and `l.toArray(...)` on a list of nullables are typed `array of string` / `array of any`; `def s: string = a.get(2)` then `s == null` prints `true` and `s.length()` fails with `Not a scope : s` in every JVM mode. Return-value nullness is checked for scalar Java returns, but array element nullness of Java returns is never consulted.

## Arrays.copyOf padding must not yield non-null string elements

```babble
def a = java.util.Arrays.copyOf(array.of("x"), 3)
def s: string = a.get(2)
println(s.length())
```

```error
Type error : expected string, got string?
```

## List.toArray of nullable elements must not yield non-null elements

```babble
def l = list of string? (null)
def a = l.toArray(array.of("z"))
def s: string = a.get(0)
println(s.length())
```

```error
Type error : expected string, got string?
```
