# Soundness regressions : `interop` (`J2`, `J3`) curated determinism, iterator views, Java varargs parity & array element nullness

Promoted from `BIP032` quarantine (`interop_j2_container_element_tostring_hashcode.batest.md`, `interop_j2_string_format_volatile_tostring.batest.md`, `interop_j3_varargs_array_double_wrap_parity.batest.md`, `interop_j2_string_case_mapping_default_locale.batest.md`, `interop_j3_java_array_return_null_elements.batest.md`, `interop_j2_curated_nullability_and_map_entry_immutability.batest.md`, `interop_j2_java_iterable_next_io.batest.md`, `interop_j3_sam_adapter_missing_inbound_check.batest.md`, `interop_j2_iterator_view_unique_alias.batest.md`, `interop_j2_escaped_iterator_fresh_return.batest.md`, `interop_j3_java_interface_unique_parameter.batest.md`) and sister vectors covering `java/lang/curated.ba`, `java/util/curated.ba`, `AxiomData`, `JavaNullability`, `JavaTypeBridge`, `JavaCallResultOrigins`, `StoredValueEscapes`, `MemberTypes`, `ControlAndOpEmitter`, and `Faces`.

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

## ArrayDeque.poll must be typed nullable E?

```babble
def q = java.util.ArrayDeque of string ()
def s: string = q.poll()
println(s.length())
```

```error
Type error : expected string, got string?
```

## Integer.getInteger with nullable fallback must return nullable int?

```babble
def n: int = java.lang.Integer.getInteger("babble.no.such.prop", null)
println(n)
```

```error
Type error : expected int, got int?
```

## Map.Entry must not be immutable when live map mutations update its value

```babble
def m = map of (string, int) (entry("a", 1))
def e = m.entrySet().iterator().next()
def fr = freeze(e)
m.put("a", 99)
println(fr.getValue())
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Java Iterator implementation with io next() is rejected by Iterator.next axiom

```babble
def BombIter = class (var done: bool = false) implements java.util.Iterator of int (
    def hasNext = fn nothrow (): bool -> (not this.done)
    def next = fn io mut (): int -> (this.done = true; print("PWNED!"); 42)
)
```

```error
Function next violates hermetic guarantee of axiom java.util.Iterator.next
```

## Cast-free HashMap.compute passing null to BiFunction SAM enforces inbound parameter check

```babble
def m = java.util.HashMap of (string, string) ()
def f: java.util.function.BiFunction of (string, string, string) = fn (k: string, v: string): string -> ("" + v.length())
println(m.compute("missing", f))
```

```error
Type error : expected string, got null
```

## String of a list of identity-printed objects is not deterministic

```babble
def f = fn (): string -> (string(list(java.lang.Object())))
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## Hash code of a list of identity-hashed objects is not deterministic

```babble
def f = fn (): int -> (list(java.lang.Object()).hashCode())
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## Hash code of an array of identity-hashed objects is not deterministic

```babble
def f = fn (): int -> (array.of(java.lang.Object()).hashCode())
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## String of a map entry with an identity-printed value is not deterministic

```babble
def f = fn (): string -> (string(java.util.Map.entry("k", java.lang.Object())))
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## String of a set of identity-hashed objects follows identity order and is not deterministic

```babble
def f = fn (): string -> (string(set(java.lang.Object(), java.lang.Object())))
```

```error
Function f violates deterministic guarantee : mark function as volatile (or io)
```

## Containers of scalars, strings and records of scalars stay deterministic

```babble
def R = record (x: int) ()
def f = fn (xs: list of int): int -> (
    xs.hashCode() + string(xs).length() + array.of("a").hashCode() + set(R(2)).hashCode()
)
assert(f(list(1, 2)) == f(list(1, 2)) and string(java.util.Map.entry("a", 2)) == "a=2")
```

## A live iterator over a unique list keeps it from being consumed

```babble
def take = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def t = fn (): string -> (
    def a = list(1, 2)
    def it = a.iterator()
    def b = take(a)
    var s = ""
    while it.hasNext() then (s = s + string(it.next()))
    s + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## A live listIterator over a unique list keeps it from being consumed

```babble
def take = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def t = fn (): string -> (
    def a = list(1)
    def it = a.listIterator()
    string(take(a)) + string(it.next())
)
```

```error
Argument for unique parameter xs must be unique
```

## A live entry set iterator over a unique map keeps it from being consumed

```babble
def take = fn (unique m: map of (string, int)): int -> (def r = map(from: move(m)) ; r.size())
def t = fn (): string -> (
    def m = map of (string, int) ()
    m.put("a", 1)
    def it = m.entrySet().iterator()
    string(take(m)) + string(it.next())
)
```

```error
Argument for unique parameter m must be unique
```

## Live iterators over a view, a key set or a set keep their unique owner from being consumed

```babble
def takeL = fn (unique xs: list of int): int -> (def r = list(from: move(xs)) ; r.size())
def takeS = fn (unique s: set of int): int -> (def r = set(from: move(s)) ; r.size())
def t = fn (): int -> (
    def a = list(1, 2)
    def s = set(1)
    def i1 = a.subList(0, 2).iterator()
    def i2 = s.iterator()
    takeL(a) + takeS(s) + i1.next() + i2.next()
)
```

```error
Argument for unique parameter xs must be unique
```

## A live key set iterator over a unique map keeps it from being consumed

```babble
def take = fn (unique m: map of (string, int)): int -> (def r = map(from: move(m)) ; r.size())
def t = fn (): string -> (
    def m = map of (string, int) ()
    m.put("a", 1)
    def it = m.keySet().iterator()
    string(take(m)) + it.next()
)
```

```error
Argument for unique parameter m must be unique
```

## A list whose iterator escaped into a mut sink is not a unique result

```babble
def take = fn (unique xs: list of int): list of int -> (def r = list(from: move(xs)) ; r.add(9) ; r)
def mk = fn (mut sink: list of (java.util.Iterator of int)): list of int -> (
    def a = list(1, 2)
    sink.add(a.iterator())
    a
)
def t = fn io (): string -> (
    def its = list of (java.util.Iterator of int) ()
    def b = take(mk(its))
    string(its.get(0).next()) + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## Iterating a unique local or a parameter through an iterator stays pure

```babble
def sumIt = fn (xs: list of int): int -> (
    def it = xs.iterator()
    var s = 0
    while it.hasNext() then (s += it.next())
    s
)
def local = fn (): int -> (
    def a = list(1, 2, 3)
    def it = a.iterator()
    var s = 0
    while it.hasNext() then (s += it.next())
    s
)
println(sumIt(list(4, 5)) + local())
```

```stdout
15
```

## A Java Consumer implementation cannot take a unique parameter

```babble
def C = class () implements java.util.function.Consumer of (list of int) (
    def accept = fn (unique xs: list of int) -> (def r = list(from: move(xs)) ; r.add(9) ; discard(r))
)
```

```error
Type error : expected (p0 : list(int)) : null, got (unique xs : list(int)) : null
```

## A Java Predicate implementation cannot take a unique parameter

```babble
def C = class () implements java.util.function.Predicate of (list of int) (
    def test = fn (unique xs: list of int): bool -> (def r = list(from: move(xs)) ; r.add(9) ; r.size() > 0)
)
```

```error
Type error : expected (p0 : list(int)) : bool, got (unique xs : list(int)) : bool
```

## A Java BiConsumer implementation cannot take a unique parameter

```babble
def o = object implements java.util.function.BiConsumer of (int, list of int) (
    def accept = fn (k: int, unique xs: list of int) -> (def r = list(from: move(xs)) ; r.add(k) ; discard(r))
)
```

```error
Type error : expected (p0 : int, p1 : list(int)) : null, got (k : int, unique xs : list(int)) : null
```

## Java interface implementations with plain parameters still run through Java

```babble
def C = class () implements java.util.function.Consumer of (list of int) (
    def accept = fn io (xs: list of int) -> (println(string(xs.size())))
)
def t = fn io (): string -> (def inner = list(1) ; list(inner).forEach(C()) ; string(inner))
println(t())
```

```stdout
1
[1]
```
