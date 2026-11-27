# Soundness regressions : `interop` (`J2`, `J3`) curated formatting determinism & Java varargs parity

Promoted from `BIP032` quarantine (`interop_j2_string_format_volatile_tostring.batest.md`, `interop_j3_varargs_array_double_wrap_parity.batest.md`) and sister vectors covering `java/lang/curated.ba` and `JavaTypeBridge`.

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
Function pureDet violates deterministic guarantee : mark function as volatile (or io)
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
Function pureDet violates deterministic guarantee : mark function as volatile (or io)
```

## String.format and String.formatted remain allowed inside volatile functions

```babble
def fmt = fn volatile (shared x: any): string -> (
    java.lang.String.format("%s", x) + "|" + "%s".formatted(x)
)
assert(fmt("ok") == "ok|ok")
```

## Passing array of any? to Java Object... varargs agrees across interpret and compiled modes

```babble
println(java.lang.String.format("%s", (array of any?).of("hello")))
println(java.lang.String.format("%s-%s", (array of any?).of("a", 42)))
println("%s".formatted((array of any?).of("world")))
```

```stdout
hello
a-42
world
```
