# Callable assignability, function intersection, inferrability, interface conformance, and member-before-def

References : BIP002 (interfaces, super), BIP008 (inference), BIP016 (effects/mut/shared), BIP021 (defaults)
Target classes : TypeChecker (nonFunctionCallableAssignable, intersectCompatibleFunctions, isInferrableCallable,
checkMissingTypes), TypeChecks (interface conformance, super defaults)

```babble
;; babble preamble
def Box = class (var v : int) ()
def I1 = interface ( def m : fn () : int )
```

## Error : implements needs a statically known interface

```babble
def NotIface = class () ()
def Bad = class () implements NotIface ()
```

```error
implements needs a statically known interface
```

## Case 2

```babble
def Entry = class (def k : any, var v : any) implements java.util.Map.Entry of (any, any) (
    def getKey = fn () -> ( this.k )
    def getValue = fn () -> ( this.v )
    def setValue = fn mut (shared x : any) -> ( def o = this.v ; this.v = x ; o )
)
def e = Entry("k", 42)
assert(e.getKey() == "k" and e.setValue(99) == 42 and e.getValue() == 99)
```

## Case 3

```babble
def I2 = interface ( def b : fn () : int )
def C1 = class () implements I1, I2 (
    def m = fn () : int -> ( 1 )
    def b = fn () : int -> ( 2 )
)
var c = C1()
assert(c.m() == 1 and c.b() == 2)
```

## Error : Babble classes can only implement Java interfaces

```babble
def BadImpl = class () implements java.lang.Object ()
```

```error
Babble classes can only implement Java interfaces
```

## Error : Babble interfaces can only extend Java interfaces

```babble
def BadExt = interface extends java.lang.Object ()
```

```error
Babble interfaces can only extend Java interfaces
```

## Error : BadBiFn.apply takes 1 arguments, but BiFunction.apply(Object, Object)...

```babble
def BadBiFn = class () implements java.util.function.BiFunction of (any, any, any) (
    def apply = fn (a : any) -> ( a )
)
```

```error
BadBiFn.apply takes 1 arguments, but BiFunction.apply(Object, Object) passes 2
```

## Error : I1.m has no default

```babble
def C = class () implements I1 (
    def m = fn () : int -> ( super.m() )
)
```

```error
I1.m has no default
```

## Error : No default missing to call

```babble
def C = class () implements I1 (
    def m = fn () : int -> ( super.missing() )
)
```

```error
No default missing to call
```

## Error : ID2 is not implemented directly by C

```babble
def ID1 = interface ( def m = fn () : int -> ( 1 ) )
def ID2 = interface ( def m = fn () : int -> ( 2 ) )
def C = class () implements ID1 (
    def m = fn () : int -> ( super(ID2).m() )
)
```

```error
ID2 is not implemented directly by C
```

## Error : missing is not a member of I

```babble
def I = interface (
    def foo = fn () -> ( this.missing )
)
```

```error
missing is not a member of I
```

## Error : Type error : expected () : int, got () : string

```babble
def Container of T = interface ( def get : fn () : T )
def BadContainer = class (var v : string) implements Container of int (
    def get = fn () : string -> ( this.v )
)
```

```error
Type error : expected () : int, got () : string
```

## Error : Type error : expected () : int, got () : string (2)

```babble
def GetInt = interface ( def get : fn () : int )
def DefStr = interface ( def get = fn () : string -> ( "hi" ) )
def Bad = class () implements GetInt, DefStr ()
```

```error
Type error : expected () : int, got () : string
```

## Error : C.m is private and cannot implement I1.m

```babble
def C = class () implements I1 (
    private def m = fn () : int -> ( 1 )
)
```

```error
C.m is private and cannot implement I1.m
```

## Error : C does not implement I1.m

```babble
def I2 = interface extends I1 ()
def C = class () implements I2 ()
```

```error
C does not implement I1.m
```

## Error : Type error : expected (a : Box, b : Box) : int, got (mut a : Box, b :...

```babble
def mutFn = fn (mut a : Box, b : Box) : int -> ( a.v = 1 ; b.v )
def expectedFn : fn (a : Box, b : Box) : int = mutFn
```

```error
Type error : expected (a : Box, b : Box) : int, got (mut a : Box, b : Box) : int
```

## Error : Type error : expected () : null, got io (values : (any | frozen any |...

```babble
def p1 : fn () : null = println
```

```error
Type error : expected () : null, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Error : Type error : expected (x : int) : null, got io (values : (any | froze...

```babble
def p2 : fn (x : int) : null = println
```

```error
Type error : expected (x : int) : null, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Error : Type error : expected (s : string) : null, got io (values : (any | fr...

```babble
def p3 : fn (s : string) : null = println
```

```error
Type error : expected (s : string) : null, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Error : Type error : expected (x : int) : any?, got io (values : (any | froze...

```babble
def p4 : fn (x : int) : any? = println
```

```error
Type error : expected (x : int) : any?, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Error : Type error : expected (v : string) : null, got io (values : (any | fr...

```babble
def bad : fn (v : string) : null = print
```

```error
Type error : expected (v : string) : null, got io (values : (any | frozen any | null)..., to : java.lang.Appendable = ...) : null
```

## Case 21

```babble
axiom java.lang.Integer.bitCount: fn pure
def fBit : fn (x : int) : int = java.lang.Integer.bitCount
assert(fBit(7) == 3)
```

## Error : Type error : expected () : double, got java.lang.Math.random

```babble
def badRand : fn () : double = java.lang.Math.random
```

```error
Type error : expected () : double, got java.lang.Math.random
```

## Error : Type error : expected (x : any) : Holder, got class Holder

```babble
def Holder = class (def shared x : any) ()
def badBorrow : fn (x : any) : Holder = Holder
```

```error
Type error : expected (x : any) : Holder, got class Holder
```

## Case 24

```babble
def ctor : fn (v : int) : Box = Box
def parse : fn io (s : string) : int = java.lang.Integer.parseInt
def pr : fn io (v : string) : null = print
assert(ctor(3).v == 3 and parse("4") == 4)
pr("")
```

## Error : Type error : expected (p : java.util.ArrayList(int)) : MutBox, got cl...

```babble
def MutBox = class (mut p : java.util.ArrayList of int) (def ok : bool = p.add(1))
def f : fn (p : java.util.ArrayList of int) : MutBox = MutBox
```

```error
Type error : expected (p : java.util.ArrayList(int)) : MutBox, got class MutBox
```

## Case 26

```babble
def tests = import("org.babblelang.tests")
def HF = tests.EffectsTestCase.HostFixtures
def mkBox : fn (v : int) : Box = Box
def fnPure : fn (x : int) : int = HF.pureCalc
def pr : fn io (x : int) : any? = println
assert(mkBox(3).v == 3 and fnPure(4) == 9)
```

## Error : Type error : expected (x : int) : int, got org.babblelang.tests.Effec...

```babble
def tests = import("org.babblelang.tests")
def fnBad : fn (x : int) : int = tests.EffectsTestCase.HostFixtures.annotatedIO
```

```error
Type error : expected (x : int) : int, got org.babblelang.tests.EffectsTestCase$HostFixtures.annotatedIO
```

## Case 28

```babble
def fnPrint : fn io (s : string) : null = println
def fnAppend : fn io (shared c : java.lang.CharSequence) : java.lang.StringBuilder = java.lang.StringBuilder().append
def samFn : java.util.function.Function of (int, double) = java.util.function.Function(fn (x) -> ( x + 1 ))
assert(samFn.apply(4) == 5.0)
```

## Error : No applicable overload of java.lang.Runnable for (java.lang.Integer) ...

```babble
java.lang.Runnable(42)
```

```error
No applicable overload of java.lang.Runnable for (java.lang.Integer) : candidates are
```

## Case 31

```babble
def pickFn = fn (flag : bool) -> (
    if flag then ( fn (mut b : Box) : int -> ( b.v = 1 ; b.v ) )
    else ( fn (b : Box) : int -> ( b.v ) )
)
var rf = fn (b : Box) : int -> ( b.v )
rf = fn (mut b : Box) : int -> ( b.v = 2 ; b.v )
def bx = Box(5)
assert(pickFn(false)(bx) == 5 and rf(bx) == 2)
```

## Case 32

```babble
def pickDiff = fn (flag : bool) -> (
    if flag then ( fn (a : int) : int -> ( a ) ) else ( fn (b : int) : int -> ( b + 1 ) )
)
assert(pickDiff(true)(3) == 3 and pickDiff(false)(3) == 4)
```

## Escaping this from a class constructor disables call-site parameter inference

```babble
def holder = object ( var c : any? = null )
def Esc = class (x) (
    def _ = ( holder.c = this )
    def r : int = x + 1
)
```

```error
Missing type : x
```

## Case 34

```babble
def Wrap of T = class (var v : T) (
    def inner = fn (x) -> (
        def keep : T = this.v
        x
    )
)
assert(Wrap("ok").inner(42) == 42)
```

## Case 35

```babble
def fact = fn (n : int) -> ( if n <= 1 then ( 1 ) else ( n * recurse(n - 1) ) )
def early = fn (n : int) -> ( if n < 0 then ( return 0.5 ) ; n )
def nullDef = fn (x = null) -> ( x )
assert(fact(5) == 120)
assert(early(-1) == 0.5 and early(3) == 3.0)
assert(nullDef() == null and nullDef(9) == 9)
```

## Case 36

```babble
def apply = fn (f : fn (x : int) : int, v : int) : int -> ( f(v) )
def o = object ( def func = fn (x : int) -> ( x + 1 ) )
assert(apply(fn (x) -> ( x * 2 ), 4) == 8 and o.func(5) == 6)
```

## Case 37

```babble
def f : fn (c : bool) : double | string = fn (c) -> ( if c then ( 1 ) else ( "a" ) )
assert(f(true) == 1.0 and f(false) == "a")
```

## Case 38

```babble
def f = fn (x = 1) -> ( x + 1 )
assert(f() == 2 and f(4) == 5)
```

## Error : Missing type : x

```babble
def g = fn (x = null) -> ( x )
```

```error
Missing type : x
```

## Error : Missing type : x (2)

```babble
var x = null
```

```error
Missing type : x
```

## Error : Missing type : x (3)

```babble
def f = fn (x) -> ( 1 )
```

```error
Missing type : x
```

## Error : Missing type : recNoType (recursive)

```babble
def recNoType = fn (n : int) -> ( recNoType(n - 1) )
```

```error
Missing type : recNoType (recursive)
```

## Error : Missing type : function (recursive)

```babble
def bad = (fn (n : int) -> ( recurse(n - 1) ))(3)
```

```error
Missing type : function (recursive)
```

## Error : Missing type : a (recursive)

```babble
def a = fn () -> ( b() )
def b = fn () -> ( a() )
a()
```

```error
Missing type : a (recursive)
```

## Error : Missing type : rec (recursive)

```babble
def rec = rec
```

```error
Missing type : rec (recursive)
```

## Error : Missing type : x (recursive)

```babble
var x = x + 1
```

```error
Missing type : x (recursive)
```

## Error : Member used before definition : x

```babble
def Runner = class () (
    def go = fn (mut b : InitBox) -> ( b.x = 1 )
)
def InitBox = class () (
    def trigger = Runner().go(this)
    var x = 0
)
InitBox()
```

```error
Member used before definition : x
```

## Error : Member used before definition : b

```babble
def C = class () () static (
    def readB = fn () : int -> ( this.b )
    def a = this.readB()
    def b = 1
)
```

```error
Member used before definition : b
```

## Polymorphic-to-polymorphic and polymorphic-to-monomorphic function subtyping

```babble
def IA = interface (def x : int)
def IB = interface extends IA ()
def Impl = record (x : int) implements IB ()
def polyA : fn of (T : IA) (T) : T = fn of (T : IA) (v : T) : T -> ( v )
def polyB : fn of (U : IB) (U) : U = polyA
def mono : fn (Impl) : Impl = polyA
assert(polyB(Impl(3)).x == 3 and mono(Impl(4)).x == 4)
```

## Non-polymorphic function cannot satisfy polymorphic function type

```babble
def mono = fn (x : int) : int -> ( x )
def bad : fn of T (T) : T = mono
```

```error
Type error : expected fn of T (p0 : T) : T, got fn (x : int) : int
```

## Polymorphic function type parameter arity mismatch

```babble
def f1 = fn of T (x : T) : T -> ( x )
def bad : fn of (A, B) (A) : A = f1
```

```error
Type error : expected fn of (A, B) (p0 : A) : A, got fn of T (x : T) : T
```

## Polymorphic-to-monomorphic bound failure and primitive array substitution rejection

```babble
def fArr = fn of T (xs : array of T) : int -> ( xs.size() )
def bad : fn (array of int) : int = fArr
```

```error
Type error : expected (p0 : array of int) : int, got fn of T (xs : array of T) : int
```

## Rest-parameter function subtyping with trailing keyword-only optionals

```babble
def varFn = fn (a : int, xs : int..., k : int = 0) : int -> ( a + k )
def sub1 : fn (a : int, b : int, xs : int..., k : int = ...) : int = varFn
def sub2 : fn (a : int, xs : int...) : int = varFn
assert(sub1(1, 2, 3, k: 4) == 5 and sub2(2, 3) == 2)
```

## Rest-parameter subtyping rejects non-variadic source or missing keyword-only param

```babble
def fixed = fn (a : int, b : int) : int -> ( a + b )
def bad : fn (a : int, xs : int...) : int = fixed
```

```error
Type error : expected (a : int, xs : int...) : int, got (a : int, b : int) : int
```
