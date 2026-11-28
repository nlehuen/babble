# Undeclared names & Babble value axioms

Undeclared bare names are rejected at compile time across reads, assignments,
untaken branches, and functions. Read-only built-ins and Babble axioms cannot
be assigned to. Value axioms (`axiom name: T`) declare statically typed
external bindings (resolved at runtime from the host) with full type, effect,
ownership, and scoping checks.

## Undeclared name read at top level fails at compile time

```babble
nosuch
```

```error
No such name : nosuch
```

## Undeclared name read in untaken branch fails at compile time

```babble
if false then (
    nosuch
) else (
    1
)
```

```error
No such name : nosuch
```

## Undeclared name assignment fails at compile time

```babble
zz = 3
```

```error
No such name : zz
```

## Undeclared name compound assignment fails at compile time

```babble
zz += 1
```

```error
No such name : zz
```

## Undeclared name assignment inside untaken branch fails at compile time

```babble
if false then (
    zz = 3
)
```

```error
No such name : zz
```

## Assigning to built-in function fails at compile time

```babble
println = 1
```

```error
Cannot assign to def : println
```

## Assigning to built-in args fails at compile time

```babble
args = list.of("a")
```

```error
Cannot assign to def : args
```

## Assigning to built-in STDOUT fails at compile time

```babble
STDOUT = null
```

```error
Cannot assign to def : STDOUT
```

## Assigning to Babble value axiom fails at compile time

```babble
axiom count: int
count = 2
```

```error
Cannot assign to axiom : count
```

## Assigning to Babble function axiom fails at compile time

```babble
axiom hostFn: fn (): int
hostFn = fn () -> ( 1 )
```

```error
Cannot assign to axiom : hostFn
```

## Value axiom enforces its static type at compile time

```babble
axiom start: int
if false then (
    start + "bad"
)
```

```error
Not a number : "bad"
```

## Value axiom missing at runtime fails with No such name

```babble
axiom ghostVal: int
ghostVal + 1
```

```error
No such name : ghostVal
```

## Value axiom evaluates to null and supports scalar and collection types

```babble
def r: any? = axiom n: int
assert(r == null)
axiom label: string?
axiom xs: list of int
axiom fxs: frozen list of int
axiom m: map of (string, int)
def check = fn volatile (): int -> (
    if false then (
        def l = label
        def s = if l != null then ( l ) else ( "" )
        n + xs.get(0) + fxs.get(0) + m.size() + s.length()
    ) else ( 42 )
)
assert(check() == 42)
```

## Value axiom supports unions and nullable function types

```babble
axiom u: int | string
axiom cb: (fn (int): int)?
def check = fn volatile (): int -> (
    if false then (
        def n = match u case def i: int then ( i ) case def s: string then ( s.length() )
        def f = cb
        n + (if f != null then ( f(1) ) else ( 0 ))
    ) else ( 7 )
)
assert(check() == 7)
```

## Value axiom supports intersection types

```babble
def A = interface ( def a: fn (): int )
def B = interface ( def b: fn (): string )
axiom ab: A & B
def check = fn volatile (): int -> (
    if false then ( ab.a() + ab.b().length() ) else ( 8 )
)
assert(check() == 8)
```

## Value axiom supports overload, null, any, and any?

```babble
axiom ov: overload (fn (int): int, fn (string): string)
axiom nothingVal: null
axiom anyVal: any
axiom optAny: any?
def check = fn volatile (): int -> (
    if false then (
        def z: null = nothingVal
        discard(z) ; discard(anyVal) ; discard(optAny)
        ov(1) + ov("x").length()
    ) else ( 9 )
)
assert(check() == 9)
```

## Value axiom is lexically scoped and shadowed by inner def

```babble
def inside = (
    axiom scopedVal: int
    def shadowed = (
        def scopedVal = "local"
        scopedVal.length()
    )
    if false then (
        scopedVal + 1
    ) else (
        shadowed
    )
)
assert(inside == 5)
```

## Value axiom is not visible outside its block

```babble
(
    axiom innerOnly: int
    discard(1)
)
innerOnly
```

```error
No such name : innerOnly
```

## Value axiom cannot be exported

```babble
export axiom badExport: int
```

```error
Axiom about a Babble name cannot be exported : badExport
```

## Value axiom cannot declare type parameters

```babble
axiom badGeneric of T: int
```

```error
Value axiom cannot declare type parameters : badGeneric
```

## Value axiom must target a single Babble name

```babble
axiom java.lang.System.out: java.io.PrintStream
```

```error
Value axiom must target a Babble name : java.lang.System.out
```

## Class axiom member cannot use a value type

```babble
axiom java.lang.String: class (
    def x: int
)
```

```error
Axiom member must be a function type : x
```

## Value axiom rejects unknown type name

```babble
axiom bad: NoSuchType
```

```error
Unknown type : NoSuchType
```

## Mutable value axiom cannot be read in a pure function

```babble
axiom xs: list of int
def readFirst = fn (): int -> ( xs.get(0) )
discard(readFirst)
```

```error
Function readFirst violates deterministic guarantee : mark function as volatile (or io)
```

## Mutable value axiom cannot be mutated in a pure function

```babble
axiom xs: list of int
def push = fn (): bool -> ( xs.add(1) )
discard(push)
```

```error
Function push violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Mutable value axiom cannot be frozen

```babble
axiom xs: list of int
def f = fn io () -> ( freeze(xs) )
discard(f)
```

```error
Cannot freeze expression : value is not unique or deeply immutable
```

## Deeply immutable and frozen value axioms are deterministic in pure functions

```babble
axiom n: int
axiom frozenXs: frozen list of int
def pureRead = fn (): int -> ( n + frozenXs.get(0) )
if false then (
    discard(pureRead())
)
assert(true)
```

## Value axiom read cannot satisfy nothrow due to boundary check

```babble
axiom n: int
def f = fn nothrow (): int -> ( n )
discard(f)
```

```error
Function f declared nothrow violates nothrow guarantee
```
