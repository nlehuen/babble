# Java intersections, multi-bound TypeVariables, SAM+markers, conflicts, and overload meets

References : BIP022 (D4, D6-D8)

```babble
;; babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
def dyn = fn (x: any): any -> ( x )
def Shape = interface ( def area: fn (): double )
def Named = interface ( def name: fn (): string )
def Tile = class () implements Shape, Named (
    def area = fn (): double -> ( 1.5 )
    def name = fn (): string -> ( "tile" )
)
def Disk = class () implements Shape ( def area = fn (): double -> ( 3.0 ) )
def Note = class () implements Named ( def name = fn (): string -> ( "note" ) )
def SubShape = interface extends Shape ()
def SubTile = class () implements SubShape, Named ( def area = fn (): double -> ( 1.0 ) ; def name = fn (): string -> ( "s" ) )
```

## Collections.max translates <T extends Object & Comparable<? super T>>

```babble
def xs: java.util.List of int = list.of(2, 9, 5)
def ss: java.util.List of string = list.of("a", "z", "m")
assert(java.util.Collections.max(xs)! + 1 == 10)
assert(java.util.Collections.max(ss) == "z")
```

## Java multi-bound TypeVariables (Number & Comparable, Comparable<? super T>, SAM+markers)

```babble
assert(Demo.sumAsDoubles(3, 4) == 7.0)
assert(Demo.maxPair("a", "b") == "b")
var count = 0
assert(Demo.runSerializable(fn io () -> ( count += 1 )))
assert(Demo.runSerializableSwapped(fn io () -> ( count += 2 )))
assert(count == 3)
```

## Multi-bound SAM+markers infers unannotated lambda parameter from Consumer<String> & Serializable

```babble
var seen = ""
def res = Demo.acceptSerializable(fn io (s) -> ( seen = s + "!" ), "hi")
assert(seen == "hi!" and res == "hi:true")
```

## Eager SAM+markers across def, Babble param, and return

```babble
def d: java.lang.Runnable & java.io.Serializable = fn () -> ()
def pass = fn (r: java.lang.Runnable & java.io.Serializable): bool -> (
    Demo.isSerializableRunnable(r)
)
def mk = fn (): java.lang.Runnable & java.io.Serializable -> ( fn () -> () )
assert(Demo.isSerializableRunnable(d) and pass(fn () -> ()) and pass(mk()))
```

## Eager SAM+markers in parameter default value

```babble
var hits = 0
def pass = fn io (shared r: java.lang.Runnable & java.io.Serializable = fn io () -> ( hits += 10 )): bool -> (
    r.run() ; Demo.isSerializableRunnable(r)
)
assert(pass() and hits == 10)
```

## Eager SAM+markers across local assign, as cast, and if join

```babble
var hits = 0
var r: java.lang.Runnable & java.io.Serializable = fn () -> ()
r = if hits == 0 then ( fn io () -> ( hits += 1 ) ) else ( fn () -> () )
def cR = (fn io () -> ( hits += 2 )) as (java.lang.Runnable & java.io.Serializable)
assert(Demo.isSerializableRunnable(r) and Demo.isSerializableRunnable(cR))
r.run() ; cR.run() ; assert(hits == 3)
```

## Eager SAM+markers in typed for-loop variable

```babble
var hits = 0
for x: java.lang.Runnable & java.io.Serializable in list(fn io () -> ( hits += 4 )) then (
    assert(Demo.isSerializableRunnable(x)) ; x.run()
)
assert(hits == 4)
```

## Contravariant function-typed parameter entry materializes SAM+markers on arrival

```babble
def check = fn (r: java.lang.Runnable & java.io.Serializable): bool -> ( Demo.isSerializableRunnable(r) )
def adapted: fn (cb: fn ()): bool = check
assert(adapted(fn () -> ()))
```

## Marker interfaces with default methods, override-equivalent SAMs, and Proxy fallback

```babble
var n = 0
def md: java.lang.Runnable & Demo.MarkerWithDefault = fn io () -> ( n += 1 )
def ss: java.lang.Runnable & Demo.SameSigRunnable = fn io () -> ( n += 2 )
md.run() ; ss.run()
assert(n == 3 and md.markerTag() == 77)
assert(Demo.invokeHiddenSerializable(fn (x: int): int -> ( x * 3 ), 7) == 21)
```

## Dynamic any cast to valid SAM+markers succeeds; multi-SAM intersection fails

```babble
def ok = dyn(fn () -> ()) as (java.lang.Runnable & java.io.Serializable)
assert(Demo.isSerializableRunnable(ok))
dyn(fn () -> ()) as (java.lang.Runnable & java.lang.AutoCloseable)
```

```error
Type error : expected java.lang.Runnable & java.lang.AutoCloseable, got function
```

## Error : Not a functional interface : java.lang.Runnable & java.lang.AutoClose...

```babble
def bad: java.lang.Runnable & java.lang.AutoCloseable = fn () -> ()
```

```error
Not a functional interface : java.lang.Runnable & java.lang.AutoCloseable
```

## Error : Not a functional interface : Shape & java.lang.Runnable

```babble
def bad: Shape & java.lang.Runnable = fn () -> ()
```

```error
Not a functional interface : Shape & java.lang.Runnable
```

## Error : Not a functional interface : java.lang.Runnable & java.lang.AutoClose... (2)

```babble
def bad = (fn () -> ()) as (java.lang.Runnable & java.lang.AutoCloseable)
```

```error
Not a functional interface : java.lang.Runnable & java.lang.AutoCloseable
```

## Java field assignment still requires explicit conversion constructor

```babble
Demo().action = fn () -> ( 1 )
```

```error
Cannot assign a function to org.babblelang.tests.JavaClassDemo.action : convert it first, as Runnable(f)
```

## Java-to-Java covariant agreement (LeftObj & RightStr -> string?)

```babble
def f1 = fn io (shared x: Demo.LeftObj & Demo.RightStr): string? -> ( x.tag() )
def f2 = fn io (shared x: Demo.RightStr & Demo.LeftObj): string? -> ( x.tag() )
assert(f1 != null and f2 != null)
```

## Java-to-Java member conflict reports lazily on x.val()

```babble
def bad = fn (x: Demo.LeftInt & Demo.RightStrVal): any? -> ( x.val() )
```

```error
Conflicting member val in org.babblelang.tests.JavaClassDemo$LeftInt & org.babblelang.tests.JavaClassDemo$RightStrVal
```

## Babble-to-Java member conflict reports lazily on x.tag()

```babble
def A = interface ( def tag: fn (): int )
def bad = fn (x: A & Demo.RightStr): any? -> ( x.tag() )
```

```error
Conflicting member tag in A & org.babblelang.tests.JavaClassDemo$RightStr
```

## Compiled `is` with Java and mixed intersections

```babble
def r: any = fn () -> () as (java.lang.Runnable & java.io.Serializable)
assert(r is java.lang.Runnable & java.io.Serializable)
assert(not (Disk() is Shape & java.io.Serializable))
```

## Overload set with Shape & Named meet and union runtime split

```babble
def f = overload (
    fn (s: Shape): string -> ( "S:" + s.area() ),
    fn (n: Named): string -> ( "N:" + n.name() ),
    fn (x: Shape & Named): string -> ( "SN:" + x.name() )
)
def pick = fn (k: int): Shape | Named -> ( if k == 0 then ( Disk() ) else if k == 1 then ( Note() ) else ( Tile() ) )
assert(f(pick(0)) == "S:3.0" and f(pick(1)) == "N:note" and f(pick(2)) == "SN:tile")
```

## Error : Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) : int...

```babble
def f = overload ( fn (s: Shape): int -> ( 1 ), fn (n: Named): int -> ( 2 ) )
def x: Shape & Named = Tile()
f(x)
```

```error
Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) : int and fn (n : Named) : int
```

## Union callable-to-SAM+markers across def, param, and as (BIP022-D7)

```babble
def x: (fn ()) | int = fn () -> ()
def y: (java.lang.Runnable & java.io.Serializable) | int = x
def take = fn (z: (java.lang.Runnable & java.io.Serializable) | int): bool -> (
    z is java.lang.Runnable & java.io.Serializable and Demo.isSerializableRunnable(z)
)
assert(Demo.isSerializableRunnable(y) and take(x) and take(42) == false)
```

## Dynamic any cast to union containing unique SAM / SAM+markers adapts callable

```babble
def a = dyn(fn () -> ()) as ((java.lang.Runnable & java.io.Serializable) | int)
def b = dyn(fn () -> ()) as (java.lang.Runnable | int)
def n = dyn(42) as (java.lang.Runnable | int)
assert(Demo.isSerializableRunnable(a) and b is java.lang.Runnable and n == 42)
```

## Error : Type error : expected java.lang.Runnable | java.lang.AutoCloseable, g...

```babble
dyn(fn () -> ()) as (java.lang.Runnable | java.lang.AutoCloseable)
```

```error
Type error : expected java.lang.Runnable | java.lang.AutoCloseable, got function
```

## Class and static SAM-typed def with direct fn RHS materialize as fields

```babble
def C = class (def k: int = 7) (
    def mr: java.util.function.IntSupplier & java.io.Serializable = fn (): int -> ( this.k )
) static (
    def sr: java.lang.Runnable & java.io.Serializable = fn () -> ()
)
assert(C().mr.getAsInt() == 7 and C().mr is java.io.Serializable and Demo.isSerializableRunnable(C.sr))
```

## Object SAM-typed def with direct fn RHS materializes as field

```babble
def o = object ( def orr: java.lang.Runnable & java.io.Serializable = fn () -> () )
assert(Demo.isSerializableRunnable(o.orr))
```

## Error : An interface default must have a function type : ir

```babble
def I = interface ( def ir: java.lang.Runnable & java.io.Serializable = fn () -> () )
```

```error
An interface default must have a function type : ir
```

## Operator overload union split on frozen Shape | frozen Named (BIP022-D8)

```babble
def R = record (n: string) implements Shape, Named (
    def area = fn (): double -> ( 1.0 ) ; def name = fn (): string -> ( this.n )
)
def B = class () ( def plus = overload ( fn (s: frozen Shape): string -> ( "S" ), fn (n: frozen Named): string -> ( "N" ), fn (x: frozen (Shape & Named)): string -> ( "SN" ) ) )
def x: frozen Shape | frozen Named = R("t")
assert(B() + x == "SN")
```

## Override-equivalent multi-SAM TypeVariable propagates io effect (BIP022-D7)

```babble
def bad = fn () -> ( Demo.runSameSigGeneric(fn io () -> ( println("io") )) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## -O1 ConstFold/DCE/Inline preserve HirExpr.For.elemSamTarget

```babble
def k = 1 + 2 ; def f1: (fn ()) | int = fn () -> ()
def run = fn (xs: list of ((fn ()) | int)): bool -> (
    var ok = false
    for x: (java.lang.Runnable & java.io.Serializable) | int in xs then (
        if k == 3 and x is java.lang.Runnable & java.io.Serializable then ( ok = Demo.isSerializableRunnable(x) )
    )
    ok
)
assert(run(list.of(f1, 42)))
```

## SAM loop variable does not wrap for-expression return value

```babble
def f1: (fn ()) | int = fn () -> ()
def res = for x: (java.lang.Runnable & java.io.Serializable) | int in list.of(f1) then (
    fn (n: int): int -> ( n + 1 )
)
assert(res!(41) == 42)
```

## For-expression result itself adapts when flowing into nullable SAM target

```babble
var ran = false
def take = fn io (shared r: java.lang.Runnable?): bool -> ( if r != null then ( r.run() ; true ) else ( false ) )
assert(take(for i in list.of(1) then ( fn io () -> ( ran = true ) )) and ran)
```

## SAM-demoted field cannot use super

```babble
def Base = interface ( def f = fn (): int -> ( 1 ) )
def Sub = class () implements Base ( def run: java.lang.Runnable = fn () -> ( super.f() ) )
```

```error
super outside of a method
```

## SAM-demoted field cannot satisfy Java interface abstract method

```babble
def C = class () implements java.lang.Runnable ( def run: java.lang.Runnable = fn () -> () )
```

```error
C does not implement java.lang.Runnable.run
```

## Operator overload union split with int between Shape and Named

```babble
def B = class () ( def plus = overload ( fn (s: Shape): string -> ( "S" ), fn (n: Named): string -> ( "N" ), fn (x: Shape & Named): string -> ( "SN" ), fn (k: int): string -> ( "I" ) ) )
def p = fn (k: int): Shape | int | Named -> ( if k == 0 then ( Disk() ) else if k == 1 then ( 7 ) else ( Tile() ) )
assert(B() + p(0) == "S" and B() + p(1) == "I" and B() + p(2) == "SN")
```

## Operator overload union split with null between Shape and Named

```babble
def B = class () ( def plus = overload ( fn (s: Shape): string -> ( "S" ), fn (n: Named): string -> ( "N" ), fn (x: Shape & Named): string -> ( "SN" ), fn (z: null): string -> ( "Z" ) ) )
def p = fn (k: int): Shape | null | Named -> ( if k == 0 then ( Disk() ) else if k == 1 then ( null ) else ( Tile() ) )
assert(B() + p(0) == "S" and B() + p(1) == "Z" and B() + p(2) == "SN")
```

## Non-operator union split orders subtype before supertype across unrelated types

```babble
def f = overload ( fn (x: Shape): int -> (1), fn (x: SubShape): int -> (2), fn (x: int): int -> (3), fn (x: string): int -> (4), fn (x: double): int -> (5), fn (x: null): int -> (6) )
def p: Shape | int | string | double | null | SubShape = SubTile() ; assert(f(p) == 2)
```

## Non-operator union split orders Sub before Shape & Named

```babble
def I = interface extends Shape, Named ()
def S = class () implements I ( def area = fn () -> (1.0) ; def name = fn () -> ("s") )
def f = overload ( fn (x: Shape): int -> (1), fn (x: Named): int -> (2), fn (x: Shape & Named): int -> (3), fn (x: I): int -> (4), fn (x: int): int -> (5), fn (x: null): int -> (6) )
def p: Shape | int | (Shape & Named) | Named | I | null = S() ; assert(f(p) == 4)
```

## Two-arg non-operator union split orders (Shape & Named, SubShape) first

```babble
def g = overload ( fn (x: Shape, y: any): int -> (1), fn (x: Named, y: any): int -> (2), fn (x: Shape & Named, y: Shape): int -> (3), fn (x: Shape & Named, y: SubShape): int -> (4), fn (x: Shape & Named, y: any): int -> (5) )
def px: Shape | Named = SubTile() ; def py: double | Shape | SubShape | int = SubTile() ; assert(g(px, py) == 4)
```

## Overloaded operator default-borrowed param accepts frozen and frozen union

```babble
def Op = class () ( def plus = overload ( fn (x: Shape): int -> (1), fn (x: Named): int -> (2), fn (x: Shape & Named): int -> (3), fn (x: int): int -> (4) ) )
def fs: frozen Shape = freeze(Disk())
def p = fn (k: int): frozen Shape | frozen Named -> ( if k == 0 then ( freeze(Disk()) ) else ( freeze(Tile()) ) )
assert(Op() + fs == 1 and Op() + p(0) == 1 and Op() + p(1) == 3)
```

## super.m() inside nested fn within early-demoted class SAM field fails

```babble
def Base = interface ( def m = fn (): int -> ( 1 ) )
def Sub = class () implements Base ( def r: java.lang.Runnable = fn () -> ( def g = fn (): int -> ( super.m() ) ; () ) )
```

```error
super outside of a method
```

## super.m() inside nested fn within early-demoted record SAM field fails

```babble
def Base = interface ( def m = fn (): int -> ( 1 ) )
def Sub = record () implements Base ( def r: java.lang.Runnable = fn () -> ( def g = fn (): int -> ( super.m() ) ; () ) )
```

```error
super outside of a method
```

## super.m() inside nested fn within safety-net-demoted SAM field fails

```babble
def Base = interface ( def m = fn (): int -> ( 1 ) )
def Sub = class () implements Base ( def r: java.lang.Runnable | (fn (int): int) = fn () -> ( def g = fn (): int -> ( super.m() ) ; () ) )
```

```error
super outside of a method
```

## Nested class inside SAM-demoted field keeps its own super.m()

```babble
def Base = interface ( def m = fn (): int -> ( 42 ) )
var out = 0
def Sub = class () implements Base ( def r: java.lang.Runnable = fn io () -> ( def Inner = class () implements Base ( def g = fn (): int -> ( super.m() ) ) ; out = Inner().g() ) )
Sub().r.run() ; assert(out == 42)
```

## Non-operator overload union split rejects frozen arg into mutable return

```babble
def Box = class (def s: Shape) ()
def f = overload ( fn (x: Shape): Box -> ( Box(x) ), fn (x: int): Box -> ( Box(Disk()) ) )
def u: frozen Shape | int = freeze(Disk())
f(u)
```

```error
No applicable overload of f for (frozen Shape | int) : candidates are (x : Shape), (x : int)
```

## Generic non-operator union split rejects frozen list into Box of T

```babble
def Box of T = class (def xs: list of T) ()
def f = overload ( fn of T (xs: list of T): Box of T -> ( Box(xs) ), fn (x: int): int -> ( x ) )
def u: frozen list of int | int = freeze(list.of(1))
f(u)
```

```error
No applicable overload of f for (frozen list of int | int) : candidates are (xs : list of T), (x : int)
```

## Spread frozen array into mutable-return overload union split is rejected

```babble
def Box = class (def s: Shape) ()
def mk = fn (xs: Shape...): Box -> ( var b = Box(Disk()) ; for s in xs then ( b = Box(s) ) ; b )
def f = overload ( fn (k: int, xs: Shape...): Box -> ( mk(...xs) ), fn (k: string, xs: Shape...): Box -> ( Box(Disk()) ) )
def u: int | string = 1 ; def fa: frozen array of Shape = freeze((array of Shape).of(Disk()))
f(u, ...fa)
```

```error
No applicable overload of f for (int | string, ...frozen array of Shape) : candidates are (k : int, xs : Shape...), (k : string, xs : Shape...)
```

## Generic operator overload accepts frozen list in direct call

```babble
def Op = class () ( def plus = overload ( fn of T (x: list of T): int -> ( x.size() ), fn (x: int): int -> ( x ) ) )
def fl: frozen list of int = freeze(list.of(4, 5))
def a: int = Op() + fl ; assert(a == 2)
```

## Generic operator overload instantiates T in union split

```babble
def OpT = class () ( def plus = overload ( fn of T (x: list of T): T -> ( x.get(0) ), fn (x: int): int -> ( x ) ) )
def p1 = fn (b: bool): frozen list of int | int -> ( if b then ( freeze(list.of(4)) ) else ( 9 ) )
def p2 = fn (b: bool): list of int | int -> ( if b then ( list.of(7) ) else ( 9 ) )
def b: int = OpT() + p1(true) ; def c: int = OpT() + p2(true)
assert(b == 4 and c == 7)
```

## Spread frozen array of int copies via TO_ARRAY without mutating source

```babble
def Box = class (def xs: array of int) ()
def f = overload ( fn (k: int, xs: array of int): Box -> ( Box(xs) ), fn (k: string, xs: array of int): int -> ( 0 ) )
def fa: frozen array of int = freeze(array.of(5, 6))
def u: int | string = 1 ; def r = f(u, ...fa)
if r is Box then ( r.xs.set(0, 99) ) ; assert(fa.get(0) == 5)
```

## Spread frozen array of string copies via TO_ARRAY without mutating source

```babble
def B = class (def xs: array of string) ()
def f = overload ( fn (k: int, xs: array of string): B -> ( B(xs) ), fn (k: string, xs: array of string): int -> ( 0 ) )
def fs: frozen array of string = freeze(array.of("ok", "b")) ; def u: int | string = 1
def r = f(u, ...fs) ; if r is B then ( r.xs.set(0, "pwned") )
assert(fs.get(0) == "ok")
```

## Spread frozen list of int copies via TO_LIST_COPY without mutating source

```babble
def Box = class (def xs: list of int) ()
def f = overload ( fn (k: int, xs: list of int): Box -> ( Box(xs) ), fn (k: string, xs: list of int): int -> ( 0 ) )
def fl: frozen list of int = freeze(list(5, 6)) ; def u: int | string = 1
def r = f(u, ...fl) ; if r is Box then ( r.xs.set(0, 99) )
assert(fl.get(0) == 5)
```

## Spread frozen list of MutA copies via TO_LIST_COPY without clearing source

```babble
def MutA = class (def v: int) ()
def f = overload ( fn of T (k: int, xs: list of T): list of T -> ( xs ), fn of T (k: string, xs: list of T): int -> ( 0 ) )
def fl: frozen list of MutA = freeze(list(MutA(5))) ; def u: int | string = 1
def r = f(u, ...fl) ; if not (r is int) then ( r.clear() )
assert(fl.size() == 1 and fl.get(0).v == 5)
```

## Direct spread of frozen array/list into mutable-returning fn copies cleanly

```babble
def id of T = fn (xs: list of T): list of T -> ( xs )
def gs = fn (xs: array of string): array of string -> ( xs )
def fa: frozen array of int = freeze(array.of(5, 6))
def fs: frozen array of string = freeze(array.of("ok", "b"))
def ra = id(...fa) ; ra.set(0, 99) ; gs(...fs).set(0, "x")
assert(fa.get(0) == 5 and ra.get(0) == 99 and fs.get(0) == "ok")
```

## Spread frozen list of string copies via TO_LIST_COPY (union split and direct)

```babble
def f = overload ( fn (k: int, xs: list of string): list of string -> ( xs ), fn (k: string, xs: list of string): int -> ( 0 ) )
def fl: frozen list of string = freeze(list("ok", "b")) ; def u: int | string = 1
def r = f(u, ...fl) ; if not (r is int) then ( r.add("pwned") )
assert(fl.size() == 2 and fl.get(0) == "ok")
```

## Spread frozen list into shared Iterable copies without mutating source

```babble
def leak = fn (shared xs: java.lang.Iterable of int): java.lang.Iterable of int -> ( xs )
def gen of T = fn (shared xs: java.lang.Iterable of T): java.lang.Iterable of T -> ( xs )
def fl: frozen list of int = freeze(list(10, 20, 30))
def i1 = leak(...fl).iterator() ; i1.next() ; i1.remove()
def i2 = gen(...fl).iterator() ; i2.next() ; i2.remove()
assert(fl.size() == 3 and fl.get(0) == 10)
```

## Spread frozen MutA list into shared Iterable of frozen MutA copies safely

```babble
def M = class (def v: int) ()
def leak = fn (shared xs: java.lang.Iterable of (frozen M)): java.lang.Iterable of (frozen M) -> ( xs )
def fl: frozen list of M = freeze(list(M(10), M(20)))
def it = leak(...fl).iterator() ; it.next() ; it.remove()
assert(fl.size() == 2 and fl.get(0).v == 10)
```

## Spread frozen list into shared Iterable of mutable M is rejected

```babble
def M = class (def v: int) ()
def leak = fn (shared xs: java.lang.Iterable of M): java.lang.Iterable of M -> ( xs )
def fl: frozen list of M = freeze(list(M(10)))
leak(...fl)
```

```error
Type error : expected M elements, got frozen M
```

## Spread frozen list into shared T... rest view is unmodifiable

```babble
def leakRest = fn (shared xs: int...): java.lang.Iterable of int -> ( xs )
def fl: frozen list of int = freeze(list(10, 20, 30))
def it = leakRest(...fl).iterator() ; it.next() ; it.remove()
```

```error
java.lang.UnsupportedOperationException
```

## First-class Callable#call rest spread from frozen list is unmodifiable

```babble
def leakRest = fn (shared xs: int...): java.lang.Iterable of int -> ( xs )
def g: fn (shared xs: int...): java.lang.Iterable of int = leakRest
def fl: frozen list of int = freeze(list(10, 20, 30))
def it = g(...fl).iterator() ; it.next() ; it.remove()
```

```error
java.lang.UnsupportedOperationException
```

## Spread Babble callable list into array of Runnable propagates ScriptRuntime

```babble
var ran = false
def take = fn io (xs: array of java.lang.Runnable): array of java.lang.Runnable -> ( xs )
def rs = list.of(fn io () -> ( ran = true ))
def ar = take(...rs) ; ar.get(0).run()
assert(ran)
```

## Spread array/list of int into array of boxed nullable double?/long? widens elements

```babble
def takeD = fn (xs: array of (double?)): array of (double?) -> ( xs )
def takeL = fn (xs: array of (long?)): array of (long?) -> ( xs )
def ai = array.of(1, 2) ; def li = list.of(3, 4)
def d1 = takeD(...ai) ; def d2 = takeD(...li) ; def l1 = takeL(...ai)
assert(d1.get(0) == 1.0 and d2.get(1) == 4.0 and l1.get(1) == 2L)
```

## 0-arg overloaded neg operator checks ownVars completeness

```babble
def Op = class () ( def neg = overload ( fn of T (): T? -> ( null ), fn (x: int): int -> ( -x ) ) )
def x = -Op()
```

```error
Missing type : cannot infer type arguments for Op().neg
```

## 0-arg overloaded neg returning list of T checks ownVars completeness

```babble
def OpL = class () ( def neg = overload ( fn of T (): list of T -> ( list.of() ), fn (x: int): int -> ( -x ) ) )
def xs = -OpL()
```

```error
Missing type : cannot infer type arguments for OpL().neg
```

## Spread frozen array/list into unique parameter succeeds via fresh copy

```babble
def takeU = fn (unique xs: list of int): list of int -> ( xs.set(0, 77) ; xs )
def fa: frozen array of int = freeze(array.of(10, 20))
def fl: frozen list of int = freeze(list(30, 40))
def u1 = takeU(...fa) ; def u2 = takeU(...fl)
assert(u1.get(0) == 77 and fa.get(0) == 10 and u2.get(0) == 77 and fl.get(0) == 30)
```

## Post-spread origin in deriveCallReturnOrigin allows mutating fresh spread copy

```babble
def idL = fn (xs: list of int): list of int -> ( xs )
def copyMut = fn (xs: int...): int -> ( def l = idL(...xs) ; l.add(99) ; l.size() )
assert(copyMut(1, 2) == 3)
```

## Custom Babble Iterable spreads into list and list(from:) in all modes

```babble
def It = class () implements java.lang.Iterable of int (
    def iterator = fn (): java.util.Iterator of int -> ( list(1, 2, 3).iterator() )
)
def sumL = fn (shared xs: list of int): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def m = It() ; assert(sumL(...m) == 6 and list(from: m).size() == 3)
```

## Frozen/mutable Iterable in rest and for

```babble
def It = class () implements java.lang.Iterable of int (
    def iterator = fn (): java.util.Iterator of int -> ( list(2, 4).iterator() )
)
def sumR = fn (shared xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def fm = freeze(It()) ; var t = 0 ; for x in fm then ( t += x )
assert(sumR(...It()) == 6 and sumR(...fm) == 6 and t == 6)
```

## Java SAM+markers varargs/array spreads and hidden SAM+markers spreads

```babble
def fns = list(fn () -> (), fn () -> ())
def hfns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x + 2 ))
assert(Demo.runSerVarargs(...fns) == 2)
assert(Demo.runSerArray(...fns) == 2)
assert(Demo.sumHiddenSer(...hfns) == 23)
```

## Babble SAM+markers array spreads adapt callables eagerly

```babble
def fns = list(fn () -> ()) ; def fa = (array of (fn ())).of(fn () -> ())
def chk = fn (shared xs: array of (java.lang.Runnable & java.io.Serializable)): bool -> (
    xs.get(0) is java.lang.Runnable and xs.get(0) is java.io.Serializable
)
assert(chk(...fns) and chk(...fa))
```

## Babble SAM and SAM+markers list spreads adapt callables eagerly

```babble
def fns = list(fn () -> ())
def chkL = fn (shared xs: list of java.lang.Runnable): bool -> ( xs.get(0) is java.lang.Runnable )
def chkB = fn (xs: list of (java.lang.Runnable & java.io.Serializable)): bool -> (
    xs.get(0) is java.io.Serializable
)
assert(chkL(...fns) and chkB(...fns))
```

## Union-split overload preserves per-branch SpreadAdapt

```babble
def f = overload (
    fn (s: Shape, xs: list of int): list of int -> ( xs ),
    fn (n: Named, unique xs: list of int): list of int -> ( xs.add(7) ; xs ),
    fn (sn: Shape & Named, unique xs: list of int): list of int -> ( xs.add(8) ; xs )
)
def u: Shape | Named = Tile() ; def fa = freeze(array.of(1, 2))
assert(f(u, ...fa).size() == 3)
```

## Union-split overload preserves localFresh

```babble
def f = overload (
    fn (s: Shape, xs: list of int): list of int -> ( xs ),
    fn (n: Named, xs: list of int): list of int -> ( xs ),
    fn (sn: Shape & Named, xs: list of int): list of int -> ( xs )
)
def go = fn (xs: int...): int -> ( def u: Shape | Named = Tile() ; def l = f(u, ...xs) ; l.add(9) ; l.size() )
assert(go(1, 2) == 3)
```

## Non-overloaded generic operator methods infer binary plus and reject uninferable 0-arg neg

```babble
def OpPlus = class () ( def plus = fn of T (x: T): T -> ( x ) )
assert((OpPlus() + 42) == 42)
```

## Non-overloaded generic 0-arg neg rejects uninferable TypeVar

```babble
def OpNeg = class () ( def neg = fn of T (): T? -> ( null ) )
def bad = -OpNeg()
```

```error
Missing type : cannot infer type arguments for OpNeg().neg
```

## Variadic SAM and SAM+markers rest spreads and forwarding

```babble
def fns = list(fn io () -> ()) ; var n = 0
def rs = fn io (shared xs: (java.lang.Runnable & java.io.Serializable)...): bool -> (
    for x in xs then ( x.run() ; n += 1 ) ; true
)
def fwd = fn io (shared xs: (java.lang.Runnable & java.io.Serializable)...): bool -> ( rs(...xs) )
assert(rs(...fns) and fwd(...fns) and n == 2)
```

## Borrowed SAM+markers rest spread accepts frozen callable list

```babble
def ffns = freeze(list(fn io () -> ()))
def chk = fn (xs: (java.lang.Runnable & java.io.Serializable)...): bool -> (
    var ok = false
    for x in xs then ( ok = x is java.lang.Runnable and x is java.io.Serializable )
    ok
)
assert(chk(...ffns))
```

## Union-split overload non-spread and mixed TO_SAM rest adaptation

```babble
def fns = list(fn io () -> ()) ; var n = 0
def ov = overload (
    fn io (k: int, shared xs: (java.lang.Runnable & java.io.Serializable)...): int -> ( 0 ),
    fn io (k: string, shared xs: (java.lang.Runnable & java.io.Serializable)...): int -> ( for x in xs then ( x.run() ; n += 1 ) ; n )
)
def u: int | string = "s"
assert(ov(u, fn io () -> (), ...fns) == 2 and ov(u, fn io () -> ()) == 3)
```

## Union-split overload non-generic non-rest TO_SAM adaptation

```babble
def ov = overload (
    fn io (k: int, shared r: java.lang.Runnable & java.io.Serializable): bool -> ( false ),
    fn io (k: string, shared r: java.lang.Runnable & java.io.Serializable): bool -> (
        r.run() ; r is java.lang.Runnable and r is java.io.Serializable
    )
)
def u: int | string = "s" ; assert(ov(u, fn io () -> ()))
```

## Generic union-split overload SAM+markers rest adaptation

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def fns = list(fn io () -> ()) ; def u: int | string = "s" ; var n = 0
def ovG = overload (
    fn of (T: RS) io (k: int, shared xs: T...): int -> ( 0 ),
    fn of (T: RS) io (k: string, shared xs: T...): int -> ( for x in xs then ( x.run() ; n += 1 ) ; n )
)
assert(ovG(u, ...fns) == 1)
```

## Generic union-split overload SAM+markers non-rest adaptation

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def u: int | string = "s" ; var n = 0
def ovN = overload (
    fn of (T: RS) io (k: int, shared r: T): int -> ( 0 ),
    fn of (T: RS) io (k: string, shared r: T): int -> ( r.run() ; n += 1 ; n )
)
assert(ovN(u, fn io () -> ()) == 1)
```

## Generic union-split overload SAM+markers list spread adaptation

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def fns = list(fn io () -> ()) ; def u: int | string = "s" ; var n = 0
def ovL = overload (
    fn of (T: RS) io (k: int, shared xs: list of T): int -> ( 0 ),
    fn of (T: RS) io (k: string, shared xs: list of T): int -> ( for x in xs then ( x.run() ; n += 1 ) ; n )
)
assert(ovL(u, ...fns) == 1)
```

## Generic overloaded operator promotes TypeVar to SAM+markers bound

```babble
def Op = class () (
    def plus = overload (
        fn of (T: java.lang.Runnable & java.io.Serializable) (r: T): bool -> (
            r is java.lang.Runnable and r is java.io.Serializable
        ),
        fn (x: int): int -> ( x + 1 )
    )
)
assert((Op() + (fn () -> ())) == true and (Op() + 41) == 42)
```

## Generic mixed RS + callable + spread and Java varargs

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def e: RS = fn io () -> () ; def fns = list(fn io () -> ())
def g = fn of (T: RS) io (shared xs: T...): int -> ( for x in xs then ( assert(Demo.runSerializable(x)) ) ; 1 )
assert(g(e, fn io () -> (), ...fns) == 1 and Demo.runSerVarargs(e, fn () -> ()) == 2)
```

## Generic union-split mixed RS + fn + spread

```babble
def R = type java.lang.Runnable & java.io.Serializable
def e: R = fn io () -> () ; def f = list(fn io () -> ()) ; def u: int | string = "s"
def o = overload ( fn of (T: R) io (k: int, xs: T...): int -> ( 0 ), fn of (T: R) io (k: string, xs: T...): int -> ( for x in xs then ( assert(x is R) ) ; 3 ) )
assert(o(u, e, fn io () -> (), ...f) == 3)
```

## TypeVar->SAM on (fn io ())|RS and (fn io ())?

```babble
def R = type java.lang.Runnable & java.io.Serializable
def f1 = fn of (T: R) io (r: T): bool -> ( Demo.isSerializableRunnable(r) )
def f2 = fn of (T: R?) io (r: T): T -> ( r )
def u: (fn io ()) | R = fn io () -> () ; def o: (fn io ())? = fn io () -> ()
def n: R = f2(fn () -> ()) ; assert(f1(u) and f2(o) != null and Demo.runSerializable(n))
```

## Mixed C and fn into T: RS, array, field

```babble
def R = type java.lang.Runnable & java.io.Serializable
def C = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> () )
def f = fn of (T: R) (a: T, b: T): R -> ( b ) ; def r: R = f(C(), fn () -> ())
def d = Demo() ; d.action = r ; d.runAction() ; assert(Demo.runSerArray(...list.of(r)) == 1)
```

## Mixed C and fn into Java varargs T...

```babble
def C = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> () )
assert(Demo.runSerVarargs(C(), fn () -> ()) == 2)
```

## Mixed C|(fn ()) in generic op and Java call

```babble
def R = type java.lang.Runnable & java.io.Serializable
def C = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> () )
def u: C | (fn ()) = fn () -> ()
def O = class () ( def plus = overload ( fn of (T: R) (x: T): bool -> ( x is R ), fn (x: int): int -> ( 1 ) ) )
assert((O() + u) and Demo.runSerializable(u))
```

## Mixed C, fn split

```babble
def R = type java.lang.Runnable & java.io.Serializable
def C = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> () )
def k: int | string = "s"
def o = overload ( fn of (T: R) (k: int, xs: T...): int -> ( 0 ), fn of (T: R) (k: string, xs: T...): int -> ( for x in xs then ( assert(x is R) ) ; 1 ) )
assert(o(k, C(), fn () -> ()) == 1)
```

## Unbounded TypeVar preserves (fn ())|int union without SAM promotion

```babble
def idT = fn of T (x: T): T -> ( x )
def u: (fn ()) | int = fn () -> () ; def r: (fn ()) | int = idT(u) ; assert(not (r is int))
```

## TypeVar with SAM+markers bound rejects any without spurious promotion

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def f = fn of (T: RS) (x: T): bool -> ( true ) ; def a: any = fn () -> () ; f(a)
```

```error
Type error : type argument any does not satisfy bound java.lang.Runnable & java.io.Serializable
```

## TypeVar with SAM+markers bound rejects (fn ())|int without spurious promotion

```babble
def RS = type java.lang.Runnable & java.io.Serializable
def f = fn of (T: RS) (x: T): bool -> ( true ) ; def u: (fn ()) | int = fn () -> () ; f(u)
```

```error
Type error : type argument (() : any?) | int does not satisfy bound java.lang.Runnable & java.io.Serializable
```
