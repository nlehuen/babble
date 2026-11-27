# Known JVM / WebAssembly divergences, declared with backend-scoped outcome blocks

Each case below behaves differently on the JVM (interpret, `-O0`, `-O1`, `-O2`) and on WebAssembly 3.0 (`wasm-O1`, `wasm-O2`), and says so with a `# <kind> backend:jvm` block and a `# <kind> backend:wasm` block (`BIP029-D11`). A case fails once the backends agree again, so fixing a divergence means merging its two blocks into one.

References : BIP029 (backend-scoped outcome blocks), BIP025 (WebAssembly 3.0 backend), ConvertFunction, AssertFunction

## Builtin conversion called through a variable names its operand only on the JVM

Wasm's generic call signature carries no call-site argument text, so a conversion reached through a variable quotes its own name instead of `b+1`. Called directly, `byte(b + 1)` names its operand on both backends.

```babble
def conv = byte
def f = fn (b: byte): byte -> (conv(b + 1))
println(f(byte(127)))
```

```error
# error backend:jvm
Out of the byte range : b+1 = 128
```

```error
# error backend:wasm
Out of the byte range : byte = 128
```

## Assert called through a variable quotes its condition only on the JVM

Same cause : the failed condition's text does not reach `assert` through Wasm's generic call signature.

```babble
def check = assert
def f = fn (x: int): bool -> (check(x > 3))
f(1)
```

```error
# error backend:jvm
Assertion failed : x>3
```

```error
# error backend:wasm
Assertion failed : assert
```
