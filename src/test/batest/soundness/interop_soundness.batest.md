# Soundness regressions : `interop` (`J2`, `J3`) curated determinism, Java varargs parity & array element nullness

Promoted from `BIP032` quarantine (`interop_j2_string_format_volatile_tostring.batest.md`, `interop_j3_varargs_array_double_wrap_parity.batest.md`, `interop_j2_string_case_mapping_default_locale.batest.md`, `interop_j3_java_array_return_null_elements.batest.md`) and sister vectors covering `java/lang/curated.ba`, `java/util/curated.ba`, `AxiomData`, `JavaNullability`, and `JavaTypeBridge`.

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

## Case mapping with an explicit locale stays pure

```babble
def up = fn (s: string, l: java.util.Locale): string -> (s.toUpperCase(l))
def down = fn volatile (s: string): string -> (s.toLowerCase())
assert(up("abc", java.util.Locale.ROOT) == "ABC" and down("ABC") == "abc")
```

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

## Collection.toArray() elements are nullable and curated non-null array returns stay non-null

```babble
def a = list of string? ("p", null).toArray()
assert(a.get(1) == null and a.get(0) == "p")
def parts = "a,b".split(",")
def e = java.lang.Exception("x")
assert(parts.get(1).length() == 1 and e.getSuppressed().size() == 0)
```

## Stream.toArray of nullable elements must not yield non-null elements

```babble
def a = list of string? (null).stream().toArray()
def s: any = a.get(0)
```

```error
Type error : expected any, got any?
```
