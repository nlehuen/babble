# Dynamic runtime argument binding : Arguments.Binder with named, rest, and error paths

References : `BIP021` (named, optional, and rest parameters), `Arguments`.

```babble
;; babble preamble
def coerce of T = fn io (shared x: any?): T -> (
    def l: java.util.List of T = java.util.ArrayList()
    def m = java.lang.Class.forName("java.util.List").getMethod("add", java.lang.Class.forName("java.lang.Object"))!
    m.invoke(l, x)
    l.get(0)
)
def fPlain = fn (a: int, b: int = 20): int -> ( a * 10 + b )
def fOne = fn (a: int): int -> ( a )
def fRest = fn (a: int, xs: int..., k: int = 10): int -> (
    def s = a * 100 + k
    for x in xs then ( s += x )
    s
)
def DynAB = type (fn (a: int, b: int): int) | (fn (b: int, a: int): int)
def DynOptA = type (fn (a: int = ..., z: int = ...): int) | (fn (z: int = ..., a: int = ...): int)
def DynOptB = type (fn (b: int = ..., z: int = ...): int) | (fn (z: int = ..., b: int = ...): int)
```

## Dynamic named call binds positional plus named, swapped named, and omitted default

```babble
def DynPN = type (fn (a: int, b: int): int) | (fn (a: int, b: int, c: int = ...): int)
def g0: DynPN = coerce of (fn (a: int, b: int): int)(fPlain)
def g1: DynAB = coerce of (fn (a: int, b: int): int)(fPlain)
def g2: DynOptA = coerce of (fn (a: int = ..., z: int = ...): int)(fPlain)
assert(g0(1, b: 2) == 12 and g1(b: 3, a: 4) == 43 and g2(a: 5) == 70)
```

## Dynamic named call collects multiple positional rest items and keyword-only parameter

```babble
def T1 = type (fn (a: int, x: int, y: int, k: int): int) | (fn (a: int, x: int, y: int, k: int, z: int = ...): int)
def g1: T1 = coerce of (fn (a: int, x: int, y: int, k: int): int)(fRest)
assert(g1(1, 2, 3, k: 4) == 109)
```

## Dynamic named call binds keyword-only parameter at its own parameter index

```babble
def T2 = type (fn (a: int, x: int, k: int): int) | (fn (a: int, x: int, k: int, z: int = ...): int)
def g2: T2 = coerce of (fn (a: int, x: int, k: int): int)(fRest)
assert(g2(2, 5, k: 1) == 206)
```

## Dynamic named call rejects omitted required parameter

```babble
def g: DynOptB = coerce of (fn (b: int = ..., z: int = ...): int)(fPlain)
g(b: 2)
```

```error
Missing parameter : a
```

## Dynamic named call rejects unknown parameter name

```babble
def DynZ = type (fn (z: int = ..., a: int = ...): int) | (fn (a: int = ..., z: int = ...): int)
def g: DynZ = coerce of (fn (z: int = ..., a: int = ...): int)(fPlain)
g(z: 1)
```

```error
No such parameter : z
```

## Dynamic named call rejects naming the rest parameter

```babble
def DynXs = type (fn (xs: int = ..., a: int = ...): int) | (fn (a: int = ..., xs: int = ...): int)
def g: DynXs = coerce of (fn (xs: int = ..., a: int = ...): int)(fRest)
g(xs: 1)
```

```error
No such parameter : xs
```

## Dynamic named call rejects parameter supplied both positionally and by name

```babble
def DynDup = type (fn (x: int, a: int): int) | (fn (a: int, x: int): int)
def g: DynDup = coerce of (fn (x: int, a: int): int)(fPlain)
g(1, a: 2)
```

```error
Parameter given twice : a
```

## Dynamic named call rejects positional argument after out-of-position named argument

```babble
def DynOop = type (fn (b: int, x: int): int) | (fn (b: int, x: int, y: int = ...): int)
def g: DynOop = coerce of (fn (b: int, x: int): int)(fPlain)
g(b: 1, 2)
```

```error
Named argument out of position : b
```

## Dynamic named call rejects positional argument after keyword-only named argument

```babble
def DynKwPos = type (fn (a: int, x: int, k: int, z: int): int) | (fn (a: int, x: int, k: int, z: int, w: int = ...): int)
def g: DynKwPos = coerce of (fn (a: int, x: int, k: int, z: int): int)(fRest)
g(1, 2, k: 3, 4)
```

```error
Named argument out of position : k
```

## Dynamic named call rejects extra positional arguments beyond declared parameters

```babble
def DynExtra = type (fn (a: int, x: int): int) | (fn (a: int, x: int, y: int = ...): int)
def g: DynExtra = coerce of (fn (a: int, x: int): int)(fOne)
g(a: 1, 2)
```

```error
Too many parameters : 1 declared, 1 given
```

## Dynamic positional call rejects extra arguments beyond declared parameters

```babble
coerce of (fn (x: int, y: int): int)(fOne)(1, 2)
```

```error
Too many parameters : 1 declared, 2 given
```
