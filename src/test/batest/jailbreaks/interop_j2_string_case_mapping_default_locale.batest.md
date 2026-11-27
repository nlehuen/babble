# Jailbreak : `interop` (`J2`) `String.toUpperCase()` / `toLowerCase()` are certified deterministic though they read the default `Locale`

- **Surface:** `interop`
- **Vector:** `J2` (curated axiom drift : `java.lang.String` blanket `def *: fn pure` covers the locale-sensitive no-argument overloads)
- **Severity:** `S2` (deterministic over-claim), `S0` under `-O2` (`HirAeGraphPass` merges the two calls)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** a pure `fn (s: string): string -> (s.toUpperCase())` is accepted while `java.util.Locale.getDefault()` is rejected as volatile. Calling it on `"i"` before and after `Locale.setDefault(tr)` inside an `io` function prints `false` in `interpret`/`-O0`/`-O1` and `true` in `-O2`.

## No-argument toUpperCase reads the default locale and is not deterministic

```babble
def up = fn (s: string): string -> (s.toUpperCase())
```

```error
Function up violates deterministic guarantee : mark function as volatile (or io)
```

## No-argument toLowerCase reads the default locale and is not deterministic

```babble
def down = fn (s: string): string -> (s.toLowerCase())
```

```error
Function down violates deterministic guarantee : mark function as volatile (or io)
```
