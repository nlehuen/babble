# Jailbreak : `types` (`T2`) covariant `array of (list of string)` to `array of any` pollutes the heap

- **Surface:** `types`
- **Vector:** `T2` (array covariance over a non-reifiable element type)
- **Severity:** `S1` (cast-free heap pollution), `S0` on WebAssembly (`wasm-O1`/`wasm-O2` trap `illegal cast` where the JVM modes report `Type error : expected string, got int` at the later read)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `array of (list of string)` is assignable to `array of any`; the JVM array store check only sees `java.util.List`, so storing a `list of int` succeeds and `a.get(0)` is a `list of string` holding an `int`. The JVM modes fail late at the element read, Wasm traps with a different error (parity divergence).

## Array of a parameterized element type must not be covariant

```babble
def a = array.of(list of string ("x"))
def b: array of any = a
b.set(0, list of int (42))
def s: string = a.get(0).get(0)
println(s.length())
```

```error
Type error : expected array of any, got array of (list(string))
```
