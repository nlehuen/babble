# Jailbreak : `interop` (`J2`) container `toString` / `hashCode` ignore their elements' volatile identity methods

- **Surface:** `interop` (effects of the curated container protocol methods)
- **Vector:** `J2` (curated axiom drift : `Collection` / `Map` / `Map.Entry` / array `toString` and `hashCode` are deterministic whatever their elements)
- **Severity:** `S2` (deterministic over-claim)
- **Repro:** `POST /crib?modes=interpret,O0,O1,O2&fresh=true`
- **Observed:** `string(java.lang.Object())` and `list(o).toString()` are rightly rejected as volatile (identity-based `Object.toString`), but `string(list(o))`, `string(set(o1, o2))` (whose iteration order also follows identity hashes), `string(Map.entry("k", o))`, `list(o).hashCode()` and `array.of(o).hashCode()` are certified deterministic. A pure `fn (): int -> (list(java.lang.Object()).hashCode())` then makes `f() == f()` print `false` in every mode.

## String of a list of identity-printed objects is not deterministic

```babble
def f = fn (): string -> (string(list(java.lang.Object())))
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## Hash code of a list of identity-hashed objects is not deterministic

```babble
def f = fn (): int -> (list(java.lang.Object()).hashCode())
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## Hash code of an array of identity-hashed objects is not deterministic

```babble
def f = fn (): int -> (array.of(java.lang.Object()).hashCode())
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## String of a map entry with an identity-printed value is not deterministic

```babble
def f = fn (): string -> (string(java.util.Map.entry("k", java.lang.Object())))
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```
