# Class and interface bytecode generation : bridges, defaults, records, and Java contracts

References : BIP001, BIP011, BIP020 ; targets `ClassGen`, `InterfaceGen`, `BridgeHelpers`, `JvmPrimitives`

```babble
;; babble module=ext_calc.ba
export def ExtCalc = class (def base: int) (
    def add = fn (x: int, y: int = 10): int -> ( this.base + x + y )
)
```

```babble
;; babble module=java_fixtures.ba
def AT = type java.lang.reflect.AnnotatedType
def AAT = type java.lang.reflect.AnnotatedArrayType
def Ann = type java.lang.annotation.Annotation
export def MyAAT = class () implements java.lang.reflect.AnnotatedType, java.lang.reflect.AnnotatedArrayType (
    def getAnnotatedGenericComponentType = fn (): AT -> ( this )
    def getAnnotatedOwnerType = fn (): AT? -> ( null )
    def getType = fn (): java.lang.reflect.Type -> ( java.lang.Object().getClass() )
    def getAnnotation = fn (c: any?): any? -> ( null )
    def getAnnotations = fn (): array of Ann -> ( (array of Ann).of() )
    def getDeclaredAnnotations = fn (): array of Ann -> ( (array of Ann).of() )
)
```

```babble
;; babble module=java_typed_cb.ba
def t = import("org.babblelang.tests")
export def Cb = class () implements t.JavaClassDemo.TypedCallback (
    def acceptInt = fn (v: int?): int -> ( (v!) + 1 )
    def acceptDouble = fn (v: double?): double -> ( (v!) * 2.0 )
    def acceptBool = fn (v: bool?): bool -> ( not (v!) )
    def acceptString = fn (v: string?): string -> ( (v!) + "!" )
    def acceptRunnable = fn io (shared r: java.lang.Runnable?): int -> ( (r!).run() ; 1 )
)
```

```babble
;; babble preamble
def t = import("org.babblelang.tests")
def extCalc = import("ext_calc.ba")
def jFix = import("java_fixtures.ba")
def jCb = import("java_typed_cb.ba")
def App = type java.lang.Appendable
def CS = type java.lang.CharSequence?
def Fmt = interface ( def f: overload (fn (x: int): string, fn (s: string): string) )
def addOne = fn (x: int): int -> ( x + 1 )
def fmtInt = fn (x: int): string -> ( "i:" + x )
def fmtStr = fn (s: string): string -> ( "s:" + s )
```

## Cross-module direct $spec method call with optional mask

```babble
def c = extCalc.ExtCalc(5)
assert(c.add(3) == 18 and c.add(3, 2) == 10)
```

## Record with all 8 primitives plus reference and body-declared derived primitive field

```babble
def R = record (b: byte, s: short, i: int, l: long, f: float, d: double, z: bool, c: char, u: string) (
    def k: int = this.i * 2
)
def a = R(1, 2, 3, 4L, 1.5f, 2.5, true, 'A', "x")
def b = R(1, 2, 3, 4L, 1.5f, 2.5, true, 'A', "x")
assert(a == b and a != R(1, 2, 4, 4L, 1.5f, 2.5, true, 'A', "x"))
assert(java.util.Objects.hashCode(a) == java.util.Objects.hashCode(b) and a.k == 6)
```

## Babble interface bridge fallback : non-literal overload alt and overload-holding field

```babble
def NonLit = class () implements Fmt ( def f = overload ( fmtInt, fn (s: string): string -> ( "l:" + s ) ) )
def FieldOv = class (ov: overload (fn (x: int): string, fn (s: string): string)) implements Fmt ( def f = ov )
def a: Fmt = NonLit() ; def b: Fmt = FieldOv(overload ( fmtInt, fmtStr ))
assert(a.f(7) == "i:7" and a.f("x") == "l:x" and b.f(8) == "i:8" and b.f("y") == "s:y")
```

## Babble interface bridge : 0-arg field fallback and ref-only spread with extra optional param

```babble
def Zero = interface ( def get: fn (): int )
def StrOp = interface ( def op: fn (x: string): string )
def ZC = class (g: fn (): int) implements Zero ( def get = g )
def SC = class () implements StrOp ( def op = fn (x: string, suf: string = "!"): string -> ( x + suf ) )
def z: Zero = ZC(fn (): int -> ( 9 )) ; def s: StrOp = SC()
assert(z.get() == 9 and s.op("hi") == "hi!")
```

## Babble interface bridge : variadic rest parameter packing on mismatched restIndex

```babble
def VarI = interface ( def sum: fn (a: int, xs: int...): int )
def VarC = class () implements VarI (
    def sum = fn (xs: int...): int -> (
        def acc = 0
        for v in xs then ( acc += v )
        acc
    )
)
def v: VarI = VarC()
assert(v.sum(10, 20, 30) == 60 and v.sum(5) == 5)
```

## Babble interface bridge : rest method implementing fixed 2-arg interface

```babble
def Pair = interface ( def join: fn (a: int, b: int): int )
def RestC = class () implements Pair (
    def join = fn (xs: int...): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
)
def p: Pair = RestC()
assert(p.join(4, 9) == 13)
```

## Java varargs interface bridge : Babble class implementing VarArgsSink with rest parameter

```babble
def SumSink = class () implements t.JavaClassDemo.VarArgsSink (
    def sum = fn (xs: int...): int -> (
        def acc = 0
        for x in xs then ( acc += x )
        acc
    )
)
assert(t.JavaClassDemo.invokeVarArgsSink(SumSink(), 2, 3, 5) == 10)
assert(t.JavaClassDemo.invokeVarArgsSink(SumSink()) == 0)
```

## Java covariant and generic bridge methods : StringCriterion and CovariantSupplier

```babble
def NonEmpty = class () implements t.JavaClassDemo.StringCriterion (
    def test = fn (s: string): bool -> ( s.length() > 0 )
)
def HiSup = class () implements t.JavaClassDemo.CovariantSupplier (
    def get = fn (): string -> ( "hi" )
)
assert(t.JavaClassDemo.invokeErasedPredicate(NonEmpty(), "a"))
assert(t.JavaClassDemo.invokeErasedSupplier(HiSup()) == "hi")
```

## Java interface with boxed wrapper parameters : TypedCallback

```babble
def c = jCb.Cb()
assert(t.JavaClassDemo.invokeAcceptInt(c, 4) == 5)
assert(t.JavaClassDemo.invokeAcceptDouble(c, 1.5) == 3.0)
assert(not t.JavaClassDemo.invokeAcceptBool(c, true))
assert(t.JavaClassDemo.invokeAcceptString(c, "ok") == "ok!")
```

## Java interface bridge : ref-only spread with extra optional param

```babble
def Tag = class () implements java.util.function.UnaryOperator of string (
    def apply = fn (s: string, suf: string = "!"): string -> ( s + suf )
)
def u: java.util.function.UnaryOperator of string = Tag()
assert(u.apply("ok") == "ok!")
```

## Java interface void bridge discarding 2-slot double return with POP2

```babble
def WideVoid = class (def n: int) implements java.lang.Runnable (
    def run = fn mut (): double -> ( this.n += 1 ; 2.5 )
)
def w = WideVoid(0) ; def r: java.lang.Runnable = w
r.run()
assert(w.n == 1)
```

## Java interface inherited Babble defaults returning byte and short

```babble
def IB = interface extends t.JavaClassDemo.ByteUnary ( def apply = fn (x: byte): byte -> ( x ) )
def IS = interface extends t.JavaClassDemo.ShortUnary ( def apply = fn (x: short): short -> ( x ) )
def cb: t.JavaClassDemo.ByteUnary = (class () implements IB ())()
def cs: t.JavaClassDemo.ShortUnary = (class () implements IS ())()
assert(cb.apply(3) == byte(3) and cs.apply(4) == short(4))
```

## Java interface inherited Babble defaults returning float and 0-arg long

```babble
def IF = interface extends t.JavaClassDemo.FloatUnary ( def apply = fn (x: float): float -> ( x ) )
def IL = interface extends java.util.function.LongSupplier ( def getAsLong = fn (): long -> ( 42L ) )
def cf: t.JavaClassDemo.FloatUnary = (class () implements IF ())()
def cl: java.util.function.LongSupplier = (class () implements IL ())()
assert(cf.apply(1.5f) == 1.5f and cl.getAsLong() == 42L)
```

## Java re-abstracted default method : AnnotatedType and AnnotatedArrayType

```babble
def a: java.lang.reflect.AnnotatedArrayType = jFix.MyAAT()
assert(a.getAnnotatedOwnerType() == null and (a.getAnnotations()!).size() == 0)
```

## Java default methods with non-matching Babble overload set on Iterator

```babble
def OvIter = class () implements java.util.Iterator of int (
    def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 )
    def remove = overload ( fn (n: int): int -> ( n ), fn (s: string): string -> ( s ) )
)
assert(OvIter().remove(5) == 5 and OvIter().remove("a") == "a")
```

## Java default methods with single-method arity mismatch on Iterator

```babble
def ArityIter = class () implements java.util.Iterator of int (
    def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 )
    def remove = fn (n: int): int -> ( n )
    def forEachRemaining = fn (): int -> ( 1 )
)
assert(ArityIter().remove(2) == 2 and ArityIter().forEachRemaining() == 1)
```

## Inherited overloaded Babble default on Java Iterator skips unmatched Java default

```babble
def MixIter = interface extends java.util.Iterator of int (
    def remove = overload ( fn (n: int): int -> ( n ), fn (s: string): string -> ( s ) )
)
def C1 = class () implements MixIter ( def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 ) )
assert(C1().remove(3) == 3 and C1().remove("x") == "x")
```

## Inherited overloaded Babble default on Java Appendable invokes OVERLOAD_SET.memberOf

```babble
def OvApp = interface extends java.lang.Appendable (
    def append = overload (
        fn (c: char): App -> ( this ),
        fn (s: CS): App -> ( this ),
        fn (s: CS, i: int, j: int): App -> ( this )
    )
)
def a: App = (class () implements OvApp ())()
assert(a.append('x') != null and a.append("ab", 0, 1) != null)
```

## InterfaceGen : sub-interface re-abstracting covariant method and overriding with overloaded defaults

```babble
def BC = interface ( def f: fn (x: int): any? )
def ReAbs = interface extends BC ( def f: fn (x: int): int )
def SubOv = interface extends BC ( def f = overload ( fn (x: int): int -> ( x + 1 ), fn (s: string): string -> ( s ) ) )
def r: BC = (class () implements ReAbs ( def f = fn (x: int): int -> ( x ) ))()
def s: BC = (class () implements SubOv ())()
assert(r.f(3) == 3 and s.f(3) == 4)
```

## InterfaceGen : sub-interface overriding overloaded parent with covariant overloaded defaults

```babble
def BO = interface ( def f: overload (fn (x: int): any?, fn (s: string): any?) )
def SO = interface extends BO (
    def f = overload ( fn (x: int): int -> ( x + 2 ), fn (s: string): string -> ( s + "!" ) )
)
def s: BO = (class () implements SO ())()
assert(s.f(3) == 5 and s.f("a") == "a!")
```

## InterfaceGen : sub-interface optional-mask forwarding from required parent

```babble
def BReq = interface ( def f: fn (x: int, y: int): int )
def SReq = interface extends BReq ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def c: BReq = (class () implements SReq ())()
assert(c.f(2, 3) == 5)
```

## InterfaceGen : sub-interface optional-mask forwarding from optional parent

```babble
def BOpt = interface ( def f: fn (x: int, y: int = ...): any? )
def SOpt = interface extends BOpt ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def c: BOpt = (class () implements SOpt ())()
assert(c.f(2) == 12 and c.f(2, 4) == 6)
```

## InterfaceGen : non-literal interface default is rejected

```babble
def INonLit = interface ( def f: fn (x: int): int = addOne )
```

```error
An interface default must be a function literal : f
```

## InterfaceGen : spread-optional interface default emits ARGUMENTS.MISSING

```babble
def BS = interface ( def f: fn (x: string): string )
def SS = interface extends BS ( def f = fn (x: string, z: string = "!"): string -> ( x + z ) )
def c: BS = (class () implements SS ())()
assert(c.f("ok") == "ok!")
```

## InterfaceGen : rest-parameter interface default implementing fixed 2-arg parent

```babble
def BP = interface ( def f: fn (a: int, b: int): int )
def SR = interface extends BP ( def f = fn (xs: int...): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s ) )
def c: BP = (class () implements SR ())()
assert(c.f(3, 4) == 7)
```

## BridgeHelpers : superHelper with optional mask

```babble
def POpt = interface ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def COpt = class () implements POpt ( def f = fn (x: int, y: int = 10): int -> ( super.f(x) + y ) )
assert(COpt().f(2, 3) == 15 and COpt().f(2) == 22)
```

## BridgeHelpers : diamond findDefaultInInterface revisit skips visited base

```babble
def R0 = interface ( def m: fn (): int )
def L0 = interface extends R0 () ; def R1 = interface extends R0 ()
def D0 = interface extends L0, R1 () ; def Prov = interface ( def m = fn (): int -> ( 7 ) )
def Join = interface extends D0, Prov ()
def j: Join = (class () implements Join ())()
assert(j.m() == 7)
```

## BridgeHelpers : emitInheritedDefaultInvoke optional mask forwarding

```babble
def R = interface ( def f: fn (x: int, y: int): int )
def O = interface ( def f: fn (x: int, y: int = ...): any? )
def P = interface ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def S1 = interface extends R, P () ; def S2 = interface extends O, P ()
def c1: R = (class () implements S1 ())() ; def c2: O = (class () implements S2 ())()
assert(c1.f(2, 3) == 5 and c2.f(2) == 12)
```

## BridgeHelpers : emitInheritedDefaultInvoke dynamic fallback for arity mismatch

```babble
def B1 = interface ( def f: fn (x: int): int )
def P1 = interface ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def S1 = interface extends B1, P1 ()
def c: B1 = (class () implements S1 ())()
assert(c.f(3) == 13)
```

## BridgeHelpers : emitInheritedDefaultInvoke dynamic fallback for overloaded defaults

```babble
def BO = interface ( def f: overload (fn (x: int): int, fn (s: string): string) )
def PO = interface ( def f = overload ( fn (x: int, y: int = 5): int -> ( x + y ), fn (s: string): string -> ( s + "!" ) ) )
def S2 = interface extends BO, PO ()
def c: BO = (class () implements S2 ())()
assert(c.f(3) == 8 and c.f("a") == "a!")
```
