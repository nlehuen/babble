# Jailbreak : `opt` (`R5`) WebAssembly reports assigning an undeclared name with a different error

- **Surface:** `opt` (JVM vs WebAssembly 3.0 parity)
- **Vector:** `R5` (`wasm-O1` / `wasm-O2` behavioral parity)
- **Severity:** `S0` (the uncaught exception differs across backends)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** reading an undeclared top-level name fails with `No such name : zz` in every mode, but assigning it (`zz = 3`, also as a call argument) fails with `No such name : zz` in `interpret`, `-O0`, `-O1` and `-O2` and with `Undefined variable : zz` in `wasm-O1` and `wasm-O2`.

## Assigning an undeclared name fails as No such name

```babble
zz = 3
```

```error
No such name : zz
```

## Assigning an undeclared name inside a call argument fails as No such name

```babble
def g = fn (a : int) : int -> ( a )
println(g(zz = 3))
```

```error
No such name : zz
```
