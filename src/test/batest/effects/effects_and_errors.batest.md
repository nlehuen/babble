# EffectChecker modifier validation, module axioms, Java target errors, algebraic violations, and closure esc...

References : BIP007, BIP016, BIP017, BIP019, EffectChecker

```babble
;; babble module=eff_mod.ba
export axiom java.lang.Math.abs: fn pure nothrow
export axiom java.util.List.add: fn mut
export def abs2 = java.lang.Math.abs
```

```babble
;; babble preamble
def Box = class (var v : int) (
    def mutThis = fn mut () -> ( this.v += 1 )
)
```

## Case 1

```babble
def em = import("eff_mod.ba")
axiom java.lang.Integer.bitCount: fn io
def myAbs = java.lang.Math.abs
def ext = java.lang.Integer.bitCount
def compute = fn (x : int) : int -> ( em.abs2(x) + myAbs(-1) )
assert(compute(-5) == 6)
assert(ext(7) == 3)
```

## Error : pure is the default on functions : only use pure with axiom

```babble
def f = fn pure () : int -> ( 1 )
```

```error
pure is the default on functions : only use pure with axiom
```

## Error : axiom requires at least one effect modifier

```babble
def f = fn axiom () : int -> ( 1 )
```

```error
axiom requires at least one effect modifier
```

## Error : axiom must appear first before effect modifiers

```babble
def f = fn nothrow axiom () : int -> ( 1 )
```

```error
axiom must appear first before effect modifiers
```

## Error : axiom and io cannot be combined

```babble
def f = fn axiom io () : int -> ( 1 )
```

```error
axiom and io cannot be combined
```

## Error : io already implies volatile : use io alone

```babble
def f = fn io volatile () : int -> ( 1 )
```

```error
io already implies volatile : use io alone
```

## Error : Duplicate effect modifier : nothrow

```babble
def f = fn nothrow nothrow () : int -> ( 1 )
```

```error
Duplicate effect modifier : nothrow
```

## Error : Duplicate parameter modifier : mut

```babble
def f = fn (mut mut x : int) : int -> ( x )
```

```error
Duplicate parameter modifier : mut
```

## Error : Duplicate parameter modifier : shared

```babble
def f = fn (shared shared x : int) : int -> ( 0 )
```

```error
Duplicate parameter modifier : shared
```

## Error : mut on a function requires a receiver (this) : mark mutated parameter...

```babble
def f = fn mut () : int -> ( 1 )
```

```error
mut on a function requires a receiver (this) : mark mutated parameters with mut
```

## Error : shared on a function requires a receiver (this) : mark escaping param...

```babble
def f = fn shared () : int -> ( 1 )
```

```error
shared on a function requires a receiver (this) : mark escaping parameters with shared
```

## Error : axiom is not allowed in a function type

```babble
def f : fn axiom pure () : int = fn () : int -> ( 1 )
```

```error
axiom is not allowed in a function type
```

## Case 13

```babble
def f : fn io () : int = fn io () : int -> ( println("ok") ; 1 )
assert(f() == 1)
```

```stdout
ok
```

## pure + mut on parameter

```babble
def f = fn axiom pure (mut x : int) : int -> ( 1 )
```

```error
pure and mut are mutually exclusive : use mut (or volatile mut)
```

## pure + mut on receiver (this)

```babble
def C = class () (
    def m = fn axiom pure mut () : int -> ( 1 )
)
```

```error
pure and mut are mutually exclusive : use mut (or volatile mut)
```

## axiom inside a verified constrained function

```babble
def f = fn () : int -> (
    axiom java.lang.Math.abs: fn pure
    1
)
```

```error
axiom declaration is not allowed inside a verified constrained function
```

## Case 17

```babble
def idemp = fn nothrow idempotent (x : int) : int -> ( x )
def comm = fn nothrow commutative (a : double, b : double) : double -> ( a + b )
def assoc = fn nothrow associative (a : int, b : int) : int -> ( a + b )
assert(idemp(3) == 3)
assert(comm(1.0, 2.0) == 3.0)
assert(assoc(1, 2) == 3)
```

## Case 18

```babble
def inj = fn nothrow injective (x : int) : int -> ( -x )
def monoD = fn nothrow monotone (x : double) : double -> ( x * 2.0 )
assert(inj(4) == -4)
assert(monoD(3.0) == 6.0)
```

## Error : Function badComm declared commutative violates commutative guarantee

```babble
def badComm = fn commutative (a : int, b : int) : int -> ( a - b )
```

```error
Function badComm declared commutative violates commutative guarantee
```

## Error : Function badAssoc declared associative violates associative guarantee

```babble
def badAssoc = fn associative (a : string, b : string) : string -> ( a + b + "!" )
```

```error
Function badAssoc declared associative violates associative guarantee
```

## Error : Function badIdemp declared idempotent violates idempotent guarantee

```babble
def badIdemp = fn idempotent (x : int) : int -> ( x + 1 )
```

```error
Function badIdemp declared idempotent violates idempotent guarantee
```

## Error : Function badInj declared injective violates injective guarantee

```babble
def badInj = fn injective (x : int) : int -> ( x * 0 )
```

```error
Function badInj declared injective violates injective guarantee
```

## SAM callback propagation : io lambda inside pure-by-default function violates pure

```babble
def l : java.util.List of int = java.util.ArrayList()
l.add(1)
def p = fn (xs : java.util.List of int) -> (
    xs.forEach(fn (x) -> ( println(x) ))
)
```

```error
Function p violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Returning a closure capturing this is return-through-borrow of this (BIP017-D6)

```babble
def C = class (var v : int) (
    def escape = fn () : fn () : int -> ( fn () -> ( this.v ) )
)
assert(C(4).escape()() == 4)
```

## Error : Borrowed parameter x escapes via outer variable sink : mark parameter...

```babble
var sink : Box? = null
def leak = fn io (x : Box) -> ( sink = x )
```

```error
Borrowed parameter x escapes via outer variable sink : mark parameter as shared x
```

## Error : Inner closure mutates this : enclosing function is not declared mut

```babble
def C = class (var v : int) (
    def bad = fn () -> (
        def inner = fn mut () -> ( this.v += 1 )
        inner()
    )
)
```

```error
Inner closure mutates this : enclosing function is not declared mut
```

## Error : Cannot mutate parameter b (mut method b.mutThis) : mark parameter as ...

```babble
def g = fn (b : Box) -> ( b.mutThis() )
```

```error
Cannot mutate parameter b (mut method b.mutThis) : mark parameter as mut b
```
