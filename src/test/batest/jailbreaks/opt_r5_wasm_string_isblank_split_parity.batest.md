# Jailbreak : opt R5 WebAssembly 3.0 String.isBlank and String.split parity divergence
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## String.isBlank matches Java Character.isWhitespace across JVM and Wasm

```babble
def nul = "" + char(0)
def emSpace = "" + char(8195)
assert(not nul.isBlank())
assert(emSpace.isBlank())
```

## String.split matches Java regex semantics across JVM and Wasm

```babble
assert("a.b".split(".").size() == 0)
```
