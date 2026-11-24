# Java nullability alignment : unannotated returns, JDK java.base axioms, @NotNull, and boundary checks

References : BIP011 (§6.1-6.4, BIP011-D6..D7), BIP019 (curated java.base nullability axioms)

```babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
```

## Unannotated Java returns are T?; System.getProperty 2-arg overload is non-null via axioms

```babble
def maybeInt : int? = Demo.boxedInt(false)
def maybeOk : int? = Demo.boxedInt(true)
def mustInt : int = maybeOk!
def prop : string? = java.lang.System.getProperty("babble.nonexistent.p3.key")
def defProp : string = java.lang.System.getProperty("babble.nonexistent.p3.key", "fallback")
assert(maybeInt == null and mustInt == 42 and prop == null and defProp == "fallback")
```

## Error : Type error : expected int, got null

```babble
Demo.boxedInt(false)!
```

```error
Type error : expected int, got null
```

## Case 3

```babble
def xs : java.util.List of string = java.util.ArrayList()
xs.add("alpha")
def first : string = xs.get(0)
def opt : java.util.Optional of string = java.util.Optional.of("ok")
def optVal : string = opt.get()
assert(first == "alpha" and first.startsWith("al") and optVal == "ok")
```

## Case 4

```babble
def m : java.util.Map of (string, int) = java.util.HashMap()
m.put("a", 7)
def hit : int? = m.get("a")
def miss : int? = m.get("b")
def defaulted : int = m.getOrDefault("b", 99)
assert(hit == 7 and miss == null and defaulted == 99)
```

## Error : Type error : expected string, got string?

```babble
def s : string? = null
"hello".startsWith(s)
```

```error
Type error : expected string, got string?
```

## Error : Type error : expected string, got string? (2)

```babble
def s : string? = "a"
java.util.List.of(s)
```

```error
Type error : expected string, got string?
```

## Defensive runtime check catches null element in raw-polluted List of int

```babble
def xs : java.util.List of int = java.util.ArrayList()
def raw = xs as java.util.List
raw.add(null)
xs.get(0)
```

```error
Type error : expected int, got null
```

## Defensive runtime check catches wrong element type in raw-polluted List of int

```babble
def xs : java.util.List of int = java.util.ArrayList()
def raw = xs as java.util.List
raw.add("oops")
xs.get(0)
```

```error
Type error : expected int, got string
```
