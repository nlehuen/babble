# Class and interface lowering, super/this rules, arity checks, and foldable primitives

References : `BIP001` (lowering), `BIP008` (classes & interfaces), `BIP026` (overloads), `analysis.LoweringAnalyzer`, `analysis.TypeChecks`, `analysis.ScopeAnalysis`, `analysis.BlockScopes`, `analysis.LoweringTargets`, `analysis.Resolver`, `analysis.PrimitiveKind`.

```babble
;; babble preamble
def BaseI = interface (
    def tag: fn (): string
    def greet = fn (): string -> ( "hi" )
)
def OvBase = interface (
    def pick = overload(
        fn (x: int): string -> ( "i:" + x ),
        fn (s: string): string -> ( "s:" + s )
    )
)
def OvSub = interface extends OvBase ()
```

## Fast record bailouts : captured outer var, body check, and body field

```babble
def ext = 5
def RCap = record (x: int) ( def get = fn volatile (): int -> ( this.x + ext ) )
def RBody = record (x: int) ( def y = this.x + 1 ; init ( assert(this.x >= 0) ) )
assert(RCap(2).get() == 7 and RBody(3).y == 4)
```

## Fast record bailouts : non-literal overload field

```babble
def f1 = fn (a: int): int -> ( a + 1 )
def f2 = fn (s: string): int -> ( s.length() )
def RNonLit = record (x: int) ( def m = overload(f2, f1) )
def r = RNonLit(1)
assert(r.x == 1 and r.m != null)
```

## Fast record bailouts : Java SAM field and more than eight parameters

```babble
def RSam = record (r: java.lang.Runnable) ()
def R9 = record (a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int, i: int) ()
var ran = false
RSam(fn io () -> ( ran = true )).r.run()
assert(ran and R9(1, 2, 3, 4, 5, 6, 7, 8, 9).i == 9)
```

## Overloaded class method bridging single-signature Babble interface and sub-interface overload

```babble
def SingleI = interface ( def work: fn (int): int )
def Impl = class () implements SingleI, OvSub (
    def work = overload(fn (x: int): int -> ( x * 2 ), fn (s: string): string -> ( s ))
)
def s: SingleI = Impl()
def o1: OvBase = Impl()
def o2: OvSub = Impl()
assert(s.work(4) == 8 and o1.pick(3) == "i:3" and o2.pick("a") == "s:a")
```

## Overloaded method bridging diamond Java interfaces and inherited Babble default

```babble
def JDef = interface (
    def run = overload(fn io (): void -> ( () ), fn (x: int): int -> ( x ))
)
def C = class () implements JDef, java.lang.Runnable ()
def r: java.lang.Runnable = C()
r.run()
def D = class () implements java.lang.AutoCloseable, java.io.Closeable (
    def close = overload(fn io (): void -> ( () ), fn (code: int): int -> ( code ))
)
def cl: java.io.Closeable = D()
cl.close()
```

## Java method lowering on bounded TypeVar and array toString

```babble
def lenOf of (T: java.lang.CharSequence) = fn io (shared s: T): int -> ( s.length() )
def arr: array of int = array.of(1, 2)
assert(lenOf("abc") == 3 and arr.toString().length() > 0)
```

## Java method lowering on intersection of Babble and Java interfaces

```babble
def RunImpl = class () implements BaseI, java.lang.Runnable (
    def tag = fn (): string -> ( "ok" )
    def run = fn () -> ( () )
)
def callBoth = fn io (shared x: BaseI & java.lang.Runnable): string -> ( x.run() ; x.tag() )
assert(callBoth(RunImpl()) == "ok")
```

## Union of functions with disagreeing named parameter order

```babble
def callU = fn (cond: bool): int -> (
    def f = if cond then ( fn (a: int, b: int): int -> ( a ) ) else ( fn (b: int, a: int): int -> ( b ) )
    f(a: 10, b: 20)
)
assert(callU(true) == 10 and callU(false) == 20)
```

## SAM parameter unions with Babble fn, second Java SAM, and non-SAM

```babble
def p1 = fn (x: java.lang.Runnable | fn (): any?): bool -> ( x != null )
def p2 = fn (x: java.lang.Runnable | java.util.concurrent.Callable of int): bool -> ( x != null )
def p3 = fn (x: java.lang.Runnable | int): bool -> ( x != null )
assert(p1(fn () -> (())) and p2(fn () -> (())) and p3(1))
```

## Babble interface extending Java interface calls inherited Java method via this

```babble
def CmpI = interface extends java.lang.Comparable of int (
    def isZero = fn (): bool -> ( this.compareTo(0) == 0 )
)
def Num = class (def v: int) implements CmpI (
    def compareTo = fn (other: int): int -> ( this.v - other )
)
assert(Num(0).isZero() and not Num(5).isZero())
```

## Babble interface extending Java interface rejects unknown this member

```babble
def BadI = interface extends java.lang.Runnable (
    def helper = fn () -> ( this.noSuchJavaMethod() )
)
```

```error
noSuchJavaMethod is not a member of BadI
```

## Interface alias, parenthesized implements, and javax interface

```babble
def AliasI = BaseI
def C1 = class () implements AliasI ( def tag = fn (): string -> ( "a" ) )
def C2 = class () implements (BaseI) ( def tag = fn (): string -> ( "b" ) )
def C3 = class () implements javax.security.auth.Destroyable ()
assert(C1().tag() == "a" and C2().tag() == "b" and not C3().isDestroyed())
```

## implements rejects union type

```babble
def C = class () implements BaseI | OvBase ()
```

```error
implements needs a statically known interface
```

## implements rejects nullable interface type

```babble
def C = class () implements BaseI? ()
```

```error
implements needs a statically known interface
```

## implements rejects parenthesized nullable interface type

```babble
def C = class () implements (BaseI)? ()
```

```error
implements needs a statically known interface
```

## implements rejects function type

```babble
def C = class () implements fn (): int ()
```

```error
implements needs a statically known interface
```

## implements rejects reassigned interface variable

```babble
var I = BaseI
I = OvBase
def C = class () implements I ()
```

```error
implements needs a statically known interface
```

## implements rejects parameter name

```babble
def mk = fn (x: any) -> ( class () implements x () )
```

```error
implements needs a statically known interface
```

## implements rejects unknown Java interface

```babble
def C = class () implements java.NoSuchIface ()
```

```error
implements needs a statically known interface
```

## Variadic Babble method satisfies Java interface arity

```babble
def R = class () implements java.lang.Runnable (
    def run = fn (xs: any?...) -> ( () )
)
R().run()
```

## Variadic Babble method with too many required params fails Java interface arity

```babble
def R = class () implements java.lang.Runnable (
    def run = fn (a: any?, b: any?, xs: any?...) -> ( () )
)
```

```error
R.run takes at least 2 arguments, but Runnable.run() passes 0
```

## Optional-param Babble method fails Java interface arity with range wording

```babble
def R = class () implements java.lang.Runnable (
    def run = fn (a: any?, b: any? = 1) -> ( () )
)
```

```error
R.run takes 1 to 2 arguments, but Runnable.run() passes 0
```

## Qualified super call to undeclared member reports No default

```babble
def C = class () implements BaseI (
    def tag = fn (): string -> ( super(BaseI).missing() )
)
```

```error
No default missing to call
```

## Safe navigation on super is rejected

```babble
def C = class () implements BaseI (
    def tag = fn (): string -> ( super?.greet() )
)
```

```error
super must be called directly
```

## super inside a class field initializer is rejected

```babble
def C = class () implements BaseI (
    def tag = fn (): string -> ( "ok" )
    def x = super.greet()
)
```

```error
super outside of a method
```

## super inside a static method is rejected

```babble
def C = class () implements BaseI (
    def tag = fn (): string -> ( "ok" )
) static (
    def bad = fn (): string -> ( super.greet() )
)
```

```error
super outside of a method
```

## Record body field read via this, static block this, param default shadowing, and kw-only class param

```babble
def x = 10
def R = record (x: int = x) (
    def doubled = this.x * 2
)
def C = class (def a: int = 1, ..., def b: int) () static (
    def selfCls = this
    def tag = "C"
)
assert(R().doubled == 20 and C(b: 3).a == 1 and C.selfCls.tag == "C")
```

## Calling method on this during record construction rejects escape

```babble
def R = record (x: int) (
    def helper = fn (): int -> ( this.x )
    def y = this.helper()
)
```

```error
this cannot escape a value class under construction
```

## Required class parameter after optional positional parameter is rejected

```babble
def Bad = class (def a: int = 1, def b: int) ()
```

```error
Required parameter after an optional one : b
```

## Nested overload expression in class method

```babble
def C = class () (
    def f = overload(overload(fn (x: int): int -> ( x + 1 )), fn (s: string): int -> ( s.length() ))
)
assert(C().f(2) == 3 and C().f("hi") == 2)
```

## Interface default with nested non-literal overload is rejected

```babble
def g = fn (x: int): int -> ( x )
def BadI = interface (
    def f = overload(overload(g), fn (s: string): int -> ( 0 ))
)
```

```error
An interface default must be a function literal : f
```

## Private member on immediately-invoked class literal is rejected statically

```babble
(class () ( private def secret = 1 ))().secret
```

```error
secret is private
```

## Deep alias chain beyond staticClassOf depth still enforces privacy

```babble
def C = class () ( private def secret = 1 )
def a0 = C() ; def a1 = a0 ; def a2 = a1 ; def a3 = a2 ; def a4 = a3
def a5 = a4 ; def a6 = a5 ; def a7 = a6 ; def a8 = a7 ; def a9 = a8
a9.secret
```

```error
secret is private
```

## Spread into borrowed and shared int rest parameters

```babble
def sB = fn (xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def sS = fn (shared xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
assert(sB(1, ...list(2, 3)) == 6 and sS(1, ...list(2, 3)) == 6)
```

## Spread into shared Java SAM rest parameter

```babble
def runAll = fn io (shared rs: java.lang.Runnable...) -> ( for r in rs then ( r.run() ) )
var c = 0 ; def inc = fn io () -> ( c += 1 )
runAll(...list(inc, inc))
assert(c == 2)
```

## Constant-foldable Java calls across byte, short, and bool returns

```babble
axiom java.lang.Byte.parseByte: fn pure nothrow foldable
axiom java.lang.Short.parseShort: fn pure nothrow foldable
def b: byte = java.lang.Byte.parseByte("5")
def s: short = java.lang.Short.parseShort("7")
def i: int = b
assert("".isEmpty() and i == 5 and s == 7)
```

## Constant-foldable Java calls across char and string returns

```babble
axiom java.lang.Character.toUpperCase: fn pure nothrow foldable
assert(" ok ".trim() == "ok" and java.lang.Character.toUpperCase('a') == 'A')
```
