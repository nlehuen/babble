# Inherited axiom contracts, closure escape classification, callableSatisfies, and effect modifiers

References : `BIP007` (effects & modifiers), `BIP027` (`EffectChecker`, `EffectModifiers`, `AxiomData`, `JavaEffectResolver`).

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def HF = tests.EffectsTestCase.HostFixtures
def MH = tests.EffectsTestCase.MutableHolder
def MHT = type tests.EffectsTestCase.MutableHolder
def Box = class (var v: int) ()
def MutCtor = class (mut b: Box) ( init ( b.v += 1 ) )
def RetainCtor = class (shared b: Box) ()
```

## Inherited io axiom overrides built-in contract cleanly

```babble
axiom java.lang.Comparable.compareTo: fn io
def C = class (var v: int) implements java.lang.Comparable of C (
    def compareTo = fn io (o: C): int -> ( println("cmp") ; this.v - o.v )
)
assert(C(3).compareTo(C(1)) == 2)
```

```stdout
cmp
```

## Inherited hermetic mut axiom satisfied by mut implementation

```babble
axiom java.lang.Runnable.run: fn mut (): void
def R = class (var v: int) implements java.lang.Runnable (
    def run = fn mut () -> ( this.v += 1 )
)
def r = R(1) ; r.run()
assert(r.v == 2)
```

## Inherited hermetic mut axiom violated by io implementation

```babble
axiom java.lang.Runnable.run: fn mut (): void
def R = class (var v: int) implements java.lang.Runnable (
    def run = fn io () -> ( println("x") )
)
```

```error
Function run violates hermetic guarantee of axiom java.lang.Runnable.run
```

## Inherited commutative and associative axiom satisfied by addition

```babble
axiom java.util.function.IntBinaryOperator.applyAsInt: fn pure commutative associative (a: int, b: int): int
def Add = class () implements java.util.function.IntBinaryOperator (
    def applyAsInt = fn (a: int, b: int): int -> ( a + b )
)
assert(Add().applyAsInt(2, 3) == 5)
```

## Inherited commutative and associative axiom violated by subtraction

```babble
axiom java.util.function.IntBinaryOperator.applyAsInt: fn pure commutative associative (a: int, b: int): int
def Sub = class () implements java.util.function.IntBinaryOperator (
    def applyAsInt = fn (a: int, b: int): int -> ( a - b )
)
```

```error
Function applyAsInt violates associative guarantee of axiom java.util.function.IntBinaryOperator.applyAsInt
```

## Inherited monotone and injective axiom satisfied by identity

```babble
axiom java.util.function.IntUnaryOperator.applyAsInt: fn pure monotone injective (x: int): int
def Id = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x: int): int -> ( x )
)
assert(Id().applyAsInt(3) == 3)
```

## Inherited monotone and injective axiom violated by squaring

```babble
axiom java.util.function.DoubleUnaryOperator.applyAsDouble: fn pure monotone injective (x: double): double
def Sq = class () implements java.util.function.DoubleUnaryOperator (
    def applyAsDouble = fn (x: double): double -> ( x * x )
)
```

```error
Function applyAsDouble violates injective guarantee of axiom java.util.function.DoubleUnaryOperator.applyAsDouble
```

## Inherited unique return axiom satisfied by fresh allocation

```babble
axiom java.util.function.Supplier.get: fn pure (): unique any?
def Mk = class () implements java.util.function.Supplier of Box (
    def get = fn (): unique Box -> ( Box(4) )
)
assert(Mk().get().v == 4)
```

## Inherited borrow receiver axiom violated by shared method

```babble
def C = class (var v: int) implements java.lang.Comparable of C (
    def compareTo = fn shared (other: C): int -> ( this.v - other.v )
)
```

```error
Function compareTo violates borrow guarantee of axiom java.lang.Comparable.compareTo
```

## Inherited signatureless pure axiom rejects shared parameter

```babble
axiom java.util.function.Consumer.accept: fn pure
def sink: any? = null
def C = class () implements java.util.function.Consumer of any (
    def accept = fn (shared x: any) -> ( discard(x) )
)
```

```error
Function accept violates borrow guarantee of axiom java.util.function.Consumer.accept
```

## Inherited signatureless shared axiom allows shared parameter

```babble
axiom java.util.function.Consumer.accept: fn shared
def C = class () implements java.util.function.Consumer of any (
    def accept = fn shared (shared x: any) -> ( discard(x) )
)
C().accept(1)
assert(true)
```

## Inherited partial mut parameter axiom rejects mutating unannotated parameter

```babble
axiom java.util.function.BiConsumer.accept: fn (mut a: any!, b: any!): void
def C = class () implements java.util.function.BiConsumer of (Box, Box) (
    def accept = fn (mut a: Box, mut b: Box) -> ( a.v += 1 ; b.v += 1 )
)
```

```error
Function accept violates pure guarantee of axiom java.util.function.BiConsumer.accept
```

## Inherited axiom arity matching checks protected Object.clone and skips wrong-arity equals

```babble
axiom java.lang.Object.clone: fn pure
axiom java.lang.Object.equals: fn pure
def C = class (var v: int) (
    def clone = fn (): any -> ( this )
    def equals = fn io (a: any, b: any): bool -> ( println("eq") ; a == b )
)
assert(C(1).equals(1, 1))
```

```stdout
eq
```

```stderr
Warning : equals has no effect on == : use a value class
```

## Inherited contract lookup skips exact, wildcard, static, and constructor axioms

```babble
axiom java.lang.Comparable of T: class exact ( def compareTo: fn io )
axiom java.lang.Object: class ( def *: fn io ) static ( def new: fn pure ; def foo: fn pure )
def C = class (var v: int) implements java.lang.Comparable of C (
    def compareTo = fn (o: C): int -> ( this.v - o.v )
)
assert(C(2).compareTo(C(1)) == 1)
```

## Inherited contract lookup ranks multi-interface axioms by intra-class specificity

```babble
axiom java.lang.AutoCloseable.close: fn pure (): void
axiom java.io.Closeable.close: fn pure
def C = class () implements java.io.Closeable, java.lang.AutoCloseable (
    def close = fn () -> ()
)
def c = C() ; c.close()
assert(c is java.io.Closeable)
```

## Frame-local closure passed to shared parameter that only invokes callback stays local

```babble
def run = fn (shared cb: fn (): int): int -> ( cb() )
def outer = fn (): int -> (
    var x = 0
    run(fn () -> ( x += 1 ; x )) + x
)
assert(outer() == 2)
```

## Closure passed to shared parameter that returns callback escapes cleanly

```babble
def leak = fn (shared cb: fn (): int): fn (): int -> ( cb )
def outer = fn (): fn (): int -> ( def x = 5 ; leak(fn () -> ( x )) )
assert(outer()() == 5)
```

## Parenthesized immediate closure and reassigned local closure classification

```babble
def outer = fn (): int -> (
    def x = 1
    def a = (fn () -> ( x + 1 ))()
    var h = fn () -> ( 1 )
    h = fn () -> ( 2 )
    a + h()
)
assert(outer() == 4)
```

## callableSatisfies accepts matching pure, mut, and retaining Babble constructors

```babble
def mk: fn (int): Box = Box
def fm: fn (mut b: Box): MutCtor = MutCtor
def fr: fn (shared b: Box): RetainCtor = RetainCtor
def b = Box(2)
assert(mk(1).v == 1 and fm(b) != null and b.v == 3 and fr(b) != null)
```

## callableSatisfies rejects mut Babble constructor assigned to borrow function type

```babble
def badMut: fn (b: Box): MutCtor = MutCtor
```

```error
Type error : expected (b : Box) : MutCtor, got class MutCtor
```

## callableSatisfies rejects shared Babble constructor assigned to borrow function type

```babble
def badRet: fn (b: Box): RetainCtor = RetainCtor
```

```error
Type error : expected (b : Box) : RetainCtor, got class RetainCtor
```

## callableSatisfies accepts bound mut Java method assigned to io function type

```babble
def sb = java.lang.StringBuilder()
def app: fn io (string): java.lang.StringBuilder = sb.append
app("hi")
assert(sb.toString() == "hi")
```

## callableSatisfies rejects bound mut Java method assigned to pure nothrow function type

```babble
def sb = java.lang.StringBuilder()
def app: fn nothrow (string): java.lang.StringBuilder = sb.append
```

```error
Type error : expected nothrow (p0 : string) : java.lang.StringBuilder, got java.lang.StringBuilder.append
```

## callableSatisfies rejects shared Java method assigned to borrow function type

```babble
def f: fn (h: MHT): MHT = HF.echoShared
```

```error
# error regex
Type error : expected \(h : .*MutableHolder\) : .*MutableHolder, got .*echoShared
```

## callableSatisfies rejects mut-parameter Java method assigned to borrow function type

```babble
def f: fn (h: MHT, s: MHT): null = HF.mutateParam
```

```error
# error regex
Type error : expected \(h : .*MutableHolder, s : .*MutableHolder\) : null, got .*mutateParam
```

## Parameter modifier conflict : duplicate unique

```babble
def f = fn (unique unique x: Box): Box -> ( x )
```

```error
Duplicate parameter modifier : unique
```

## Parameter modifier conflict : unique and shared are mutually exclusive

```babble
def f = fn (unique shared x: Box): Box -> ( x )
```

```error
unique and shared are mutually exclusive
```

## Parameter modifier conflict : unique and mut are mutually exclusive

```babble
def f = fn (unique mut x: Box): Box -> ( x )
```

```error
unique and mut are mutually exclusive : consuming unique parameters already allow private mutation
```

## Function prefix conflict : pure and volatile cannot be combined

```babble
def f = fn axiom pure volatile (): int -> ( 1 )
```

```error
pure and volatile cannot be combined
```

## Function prefix conflict : axiom, io, and pure cannot be combined

```babble
def f = fn axiom io pure (): int -> ( 1 )
```

```error
axiom and io cannot be combined
```

## Function prefix axiom combinations : volatile, unique return, shared param, and shared method

```babble
def f1 = fn axiom volatile (): int -> ( 1 )
def f2 = fn axiom volatile (mut x: Box): int -> ( x.v )
def f3 = fn axiom (): unique Box -> ( Box(1) )
def f4 = fn axiom (shared x: Box): int -> ( x.v )
def C = class (var v: int) ( def m = fn axiom shared (): int -> ( this.v ) )
assert(f1() + f2(Box(2)) + f3().v + f4(Box(3)) + C(4).m() == 11)
```

## Visible summaries for io nothrow, volatile mut param, and volatile mut method

```babble
def C = class (var v: int) (
    def vm = fn volatile mut (): int -> ( this.v += 1 ; this.v )
)
def ioNt = fn io nothrow (): int -> ( 1 )
def volMut = fn volatile (mut b: Box): int -> ( b.v += 1 ; b.v )
assert(C(5).vm() + ioNt() + volMut(Box(6)) == 14)
```

## Declared unique return violated by returning borrowed parameter

```babble
def bad = fn (x: Box): unique Box -> ( x )
```

```error
Function bad declared unique return violates unique guarantee
```

## Static block reading volatile Java method inside pure function violates deterministic guarantee

```babble
def f = fn (): int -> ( def C = class () () static ( def y = HF.volatileRead(1) ) ; C.y )
```

```error
static block inside deterministic function violates deterministic guarantee
```

## Java axiom placement allowed inside io, axiom, and non-file block

```babble
def fIo = fn io (): int -> ( axiom java.lang.Math.abs: fn pure (a: int): int ; java.lang.Math.abs(-2) )
def fAx = fn axiom pure (): int -> ( axiom java.lang.Math.min: fn pure (a: int, b: int): int ; java.lang.Math.min(3, 5) )
def blk = ( axiom java.lang.StringBuilder: class () ; 1 )
assert(fIo() + fAx() + blk == 6)
```

## Java axiom placement rejected inside volatile mut method

```babble
def C = class (var v: int) (
    def bad = fn volatile mut (): int -> ( axiom java.lang.Math.abs: fn pure (a: int): int ; this.v )
)
```

```error
axiom declaration is not allowed inside a verified constrained function
```

## Parenthesized function def, overloaded def, and interface default

```babble
def f = (fn () -> ( 1 ))
def ov = overload(fn (x: int): int -> ( x + 1 ), fn (x: string): int -> ( x.length() ))
def I = interface ( def m = fn (): int -> ( 2 ) )
def C = class () implements I ()
assert(f() + ov(3) + ov("hi") + C().m() == 9)
```

## Calling clause capturing enclosing function-type parameter and unique param in function type

```babble
def ok: fn (f: fn (): int): fn calling f (): int = fn (f: fn (): int): fn calling f (): int -> (
    fn calling f (): int -> ( f() )
)
def u: fn (unique b: Box): Box = fn (unique b: Box): Box -> ( b )
assert(ok(fn (): int -> ( 4 ))() + u(Box(5)).v == 9)
```

## Calling clause capturing non-function enclosing parameter is rejected

```babble
def bad: fn (x: int): fn calling x (): int = fn (x: int): fn calling x (): int -> ( fn (): int -> ( x ) )
```

```error
Parameter in calling clause must be a function : x
```

## For-loop over Babble-to-Java Iterable with mut iterator requires mut parameter

```babble
axiom java.lang.Iterable.iterator: fn mut (): (java.util.Iterator of any!)!
def I = class () implements java.util.Iterator of int ( def hasNext = fn (): bool -> ( false ) ; def next = fn mut (): int -> ( 0 ) )
def S = class () implements java.lang.Iterable of int ( def iterator = fn mut (): java.util.Iterator of int -> ( I() ) )
def f = fn (s: S) -> ( for x in s then ( discard(x) ) )
```

```error
Cannot mutate parameter s (iterator) : mark parameter as mut s
```

