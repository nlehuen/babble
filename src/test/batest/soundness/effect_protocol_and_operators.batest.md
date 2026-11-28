# Soundness regressions : `effects` (`E1`, `E3`, `E4`), `interop` (`J2`) & `verifiers` (`A3`) protocols

Promoted from `BIP032` quarantine (`effects_e1_nothrow_untyped_catch_java_error.batest.md`, `effects_e4_compound_assign_operator_io_pwned.batest.md`, `interop_j2_string_valueof_io_tostring_pwned.batest.md`, `effects_e1_nothrow_tdz_hir_dce.batest.md`, `effects_e3_negate_union_drops_io.batest.md`, `effects_e3_for_loop_union_and_babble_iterable.batest.md`, `verifiers_a3_operator_recursion_terminating_dce.batest.md`) and sister vectors covering `TryEffectAnalyzer`, `EffectBodyWalker`, `OperatorAndLoopEffectAnalyzer`, `EffectChecker`, `EffectAstExtractors`, `JavaEffectResolver`, and `java/lang/curated.ba`.

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

## Top-level forward def TDZ throw must not be certified nothrow

```babble
def peek = fn nothrow (): int -> (s.length())
def s: string = "hello"
```

```error
Function peek declared nothrow violates nothrow guarantee
```

## Class inline field forward TDZ throw must not be certified nothrow

```babble
def C = class () (
    def peek = fn nothrow (): int -> (this.s.length())
    def a: int = this.peek()
    def s: string = "hello"
)
```

```error
Function peek declared nothrow violates nothrow guarantee
```

## Non-nothrow forward def TDZ throw is preserved and not deleted by DCE

```babble
def peek = fn (): int -> (s.length())
def run = fn (): int -> (
    def u = peek()
    42
)
println(run())
def s: string = "hello"
```

```error
Name used before definition : s
```

## Unary negate on class-numeric union must not drop io effect of neg method

```babble
def Bomb = class () (
    def neg = fn io (): int -> (print("PWNED!"); 0)
)
def leak = fn (u: Bomb | int): int -> (-u)
def _ = leak(Bomb())
```

```error
Function leak violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## For loop over union of Babble iterables must not drop io effect

```babble
def BombIter = class () (
    def hasNext = fn io (): bool -> (print("PWNED!"); false)
    def next = fn (): int -> (0)
)
def B1 = class () (def iterator = fn (): BombIter -> (BombIter()))
def B2 = class () (def iterator = fn (): BombIter -> (BombIter()))
def leak = fn (u: B1 | B2): int -> (for x in u then (def _ = x); 0)
```

```error
Function leak violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## For loop over frozen Babble iterable returning this must not mutate frozen receiver

```babble
def SelfIter = class (var v: int = 0) (
    def iterator = fn (): SelfIter -> (this)
    def hasNext = fn (): bool -> (this.v == 0)
    def next = fn mut (): int -> (this.v = 99; 99)
)
def bad = fn io (s: frozen SelfIter): int -> (
    var r = 0
    for x in s then (r = x)
    r
)
```

```error
Cannot mutate frozen value via for loop
```

## Pure for loop over union of Babble iterables runs consistently across all backends

```babble
def It = class (def v: int, var i: int = 0) (
    def hasNext = fn (): bool -> (this.i == 0)
    def next = fn mut (): int -> (this.i = 1; this.v)
)
def A = class () (def iterator = fn (): It -> (It(10)))
def B = class () (def iterator = fn (): It -> (It(20)))
def sum = fn (u: A | B): int -> (var s = 0; for x in u then (s += x); s)
assert(sum(A()) == 10 and sum(B()) == 20)
```

## Recursive unary neg operator method must not be certified terminating and deleted by DCE

```babble
def Bomb = class () (
    def neg = fn nothrow (): int -> (-this)
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.neg()
    42
)
println(run())
```

```error
Stack overflow
```

## Recursive binary plus operator method must not be certified terminating and deleted by DCE

```babble
def Bomb = class () (
    def plus = fn nothrow (n: int): int -> (
        if n <= 0 then (0) else ((this + n) + this.plus(n - 1))
    )
)
def run = fn (): int -> (
    def b = Bomb()
    def unused = b.plus(1)
    42
)
println(run())
```

```error
Stack overflow
```
