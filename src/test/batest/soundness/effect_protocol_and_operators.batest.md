# Soundness regressions : `effects` (`E1`, `E4`) & `interop` (`J2`) protocols

Promoted from `BIP032` quarantine (`effects_e1_nothrow_untyped_catch_java_error.batest.md`, `effects_e4_compound_assign_operator_io_pwned.batest.md`, `interop_j2_string_valueof_io_tostring_pwned.batest.md`) and sister vectors covering `TryEffectAnalyzer`, `EffectBodyWalker`, `JavaEffectResolver`, and `java/lang/curated.ba`.

```babble
;; babble preamble
def Err = record (msg: string) ()
def OtherErr = record (msg: string) ()
def BoomOp = class (var v: int) (
    def plus = fn io (x: int): BoomOp -> (
        print("PWNED!")
        BoomOp(this.v + x)
    )
    def minus = fn io (x: int): BoomOp -> (
        print("PWNED!")
        BoomOp(this.v - x)
    )
)
def Holder = class (var b: BoomOp) ()
```

## Untyped catch must not certify direct throw of java.lang.AssertionError as nothrow

```babble
def sneaky = fn nothrow (): int -> (
    try (
        throw java.lang.AssertionError("escape")
    ) catch then (
        0
    )
)
sneaky()
```

```error
Function sneaky declared nothrow violates nothrow guarantee
```

## Untyped catch must not certify indirect helper throwing java.lang.Error as nothrow

```babble
def helper = fn (): int -> (throw java.lang.Error("indirect"))
def sneaky = fn nothrow (): int -> (
    try (helper()) catch then (0)
)
sneaky()
```

```error
Function sneaky declared nothrow violates nothrow guarantee
```

## Untyped catch must not certify opaque throwing callback as nothrow

```babble
def sneaky = fn nothrow (f: fn (): int): int -> (
    try (f()) catch then (0)
)
```

```error
Function sneaky declared nothrow violates nothrow guarantee
```

## Explicit Throwable or Error plus untyped catch restores nothrow

```babble
def helper = fn (): int -> (throw java.lang.Error("caught"))
def catchAll = fn nothrow (): int -> (
    try (helper()) catch : java.lang.Throwable then (1)
)
def catchBoth = fn nothrow (): int -> (
    try (helper()) catch : java.lang.Error then (2) catch then (3)
)
assert(catchAll() == 1 and catchBoth() == 2)
```

## Non-Error throw escaping inner typed catch still restores nothrow in outer untyped catch

```babble
def nestedNonError = fn nothrow (): int -> (
    try (
        try (throw Err("inner")) catch : OtherErr then (0)
    ) catch then (4)
)
assert(nestedNonError() == 4)
```

## Pure function must not invoke io plus operator method via local compound assignment

```babble
def pureFn = fn (): int -> (
    var b = BoomOp(1)
    b += 2
    b.v
)
pureFn()
```

```error
Function pureFn violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Pure function must not invoke io minus operator method via member compound assignment

```babble
def pureFn = fn (mut h: Holder): int -> (
    h.b -= 1
    h.b.v
)
```

```error
Function pureFn violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Pure function must not invoke mut plus operator method on borrowed param via compound assignment

```babble
def MutOp = class (var v: int) (
    def plus = fn mut (x: int): MutOp -> (
        this.v += x
        this
    )
)
def bad = fn (a: MutOp): int -> (
    var x = a
    x += 1
    x.v
)
```

```error
Cannot mutate parameter a (operator method plus) : mark parameter as mut a
```

## Nothrow function must not divide by zero via compound floor-division assignment

```babble
def bad = fn nothrow (d: int): int -> (
    var x = 10
    x //= d
    x
)
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Class overriding Object.toString with io is rejected by inherited Object.toString axiom

```babble
def Evil = class () (
    def toString = fn io (): string -> (
        print("PWNED!")
        "evil"
    )
)
```

```error
Function toString violates pure guarantee of axiom java.lang.Object.toString
```

## Class overriding Object.hashCode with io is rejected by inherited Object.hashCode axiom

```babble
def Evil = class () (
    def hashCode = fn io (): int -> (
        print("PWNED!")
        1
    )
)
```

```error
Function hashCode violates pure guarantee of axiom java.lang.Object.hashCode
```

## Interface default toString with io is rejected by inherited Object.toString axiom

```babble
def EvilIface = interface (
    def toString = fn io (): string -> (
        print("PWNED!")
        "evil"
    )
)
```

```error
Function toString violates pure guarantee of axiom java.lang.Object.toString
```

## String.valueOf on any is volatile and rejected in deterministic pure function

```babble
def bad = fn (x: any): string -> (java.lang.String.valueOf(x))
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## Pure toString override and StringBuilder.toString remain deterministic

```babble
def Good = class (def tag: string) (
    def toString = fn (): string -> (this.tag)
)
def readBuf = fn (sb: java.lang.StringBuilder): string -> (sb.toString())
def sb = java.lang.StringBuilder()
sb.append(Good("ok").toString())
assert(readBuf(sb) == "ok")
```
