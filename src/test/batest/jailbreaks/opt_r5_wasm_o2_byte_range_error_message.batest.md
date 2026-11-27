# Jailbreak : `opt` (`R5`) `wasm-O2` reports a different `byte` range error than every other mode

- **Surface:** `opt`
- **Vector:** `R5` (WebAssembly 3.0 `-O2` parity)
- **Severity:** `S0` (observable exception message divergence across modes)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `byte(b + 1)` with `b = 127` fails with `Out of the byte range : b+1 = 128` in `interpret`, `-O0`, `-O1`, `-O2` and `wasm-O1`, but with `Out of the byte range : byte = 128` in `wasm-O2`.

## Byte conversion range error names its operand in every mode

```babble
def f = fn (b: byte): byte -> (byte(b + 1))
println(f(byte(127)))
```

```error
Out of the byte range : b+1 = 128
```
