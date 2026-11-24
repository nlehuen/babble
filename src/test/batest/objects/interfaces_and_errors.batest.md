# Interfaces, inheritance, Java interface bridges, and scope/type validation errors

References : BIP002 (interfaces, classes, records, super), BIP005 (cross-module interfaces)
Targets : BlockScopes, TypeChecks, BabbleInterface, BabbleInstance, BabbleType, Supers

```babble
;; babble module=iface_mod.ba
export def MI = interface (
    def tag : fn () : string
    def sharedTag = fn () : string -> ( "mod:" + this.tag() )
)
export def PI = interface (
    def code : fn () : int
)
```

## Module-exported and aliased interfaces on one class

```babble
def m = import("iface_mod.ba")
def AliasMI = m.MI
def C = class (def n : int) implements AliasMI, m.PI (
    def tag = fn () : string -> ( "m" + this.n )
    def code = fn () : int -> ( this.n * 10 )
)
def c = C(3)
def mi : m.MI = c
def pi : m.PI = c
assert(mi.sharedTag() == "mod:m3" and pi.code() == 30)
assert(c is m.MI and c is AliasMI)
```

## Local interface combined with a module-exported interface

```babble
def m = import("iface_mod.ba")
def LocalI = interface ( def ping = fn () : int -> ( 7 ) )
def C = class (def n : int) implements m.PI, LocalI (
    def code = fn () : int -> ( this.n * 10 )
)
def c = C(3)
def li : LocalI = c
assert(li.ping() == 7 and c.code() == 30 and c is LocalI)
```

## Java interface implementation, imported Java package, and nested Java Map.Entry resolution

```babble
def jutil = import("java.util")
def Entry = java.util.Map.Entry
def Runner = class (def n : int) implements java.lang.Runnable (
    def ran = ""
    def run = fn mut () -> ( this.ran = "run:m" + this.n )
)
def runner = Runner(3)
assert(runner is java.lang.Runnable)
def r : java.lang.Runnable = runner
r.run()
assert(runner.ran == "run:m3")
```

## Babble interface extending a Java interface and calling a Java default via Supers

```babble
def SubI = interface extends java.util.function.IntPredicate (
    def test = fn (x : int) : bool -> ( x > 0 )
    def alt = fn io shared (x : int) : bool -> ( super(java.util.function.IntPredicate).negate().test(x) )
)
def SubC = class () implements SubI ()
def sub = SubC()
assert(sub is SubI and sub is java.util.function.IntPredicate)
assert(sub.test(5) and not sub.alt(5))
```

## Inherited interface default lookup across two levels (BabbleInterface.findDefault)

```babble
def G0 = interface ( def deep = fn () : string -> ( "deep" ) )
def G1 = interface extends G0 ()
def G2 = interface extends G1 ()
def GC = class () implements G2 ()
assert(GC().deep() == "deep")
```

## Parameter modifier syntax errors in function literals (split fnParameters)

```babble
def f = (def x : int) -> ( x )
```

```error
Syntax error : unexpected '->'
```

## Error : Syntax error : unexpected 'x'

```babble
def f = (private x : int) -> ( x )
```

```error
Syntax error : unexpected 'x'
```

## Error : Name already defined : x

```babble
def C = class (x : int, x : int) ()
```

```error
Name already defined : x
```

## Error : private only applies to members

```babble
def C = class (private x : int) ()
```

```error
private only applies to members
```

## Case 10

```babble
def V = record (def x : int) ()
assert(V(3).x == 3)
```

```stderr
Warning : def is redundant on a value class parameter : x
```

## Error : equals is generated for value classes

```babble
def V = record (def x : int) (
    def equals = fn () : bool -> ( true )
)
```

```error
equals is generated for value classes
```

## Error : hashCode is generated for value classes

```babble
def V = record (def x : int) (
    def hashCode = fn () : int -> ( 1 )
)
```

```error
hashCode is generated for value classes
```

## Case 13

```babble
def C = class (def x : int) (
    def equals = fn () : bool -> ( true )
)
assert(C(1).equals())
```

```stderr
Warning : equals has no effect on == : use a value class
```

## Error : An interface can only declare members

```babble
def I = interface (
    def x : fn () : int
    println(1)
)
```

```error
An interface can only declare members
```

## Error : An interface can only declare members (2)

```babble
def I = interface (
    axiom java.lang.Math.abs: fn pure
)
```

```error
An interface can only declare members
```

## Error : Interface members cannot be private : x

```babble
def I = interface (
    private def x : fn () : int
)
```

```error
Interface members cannot be private : x
```

## Error : An interface default must be a function literal : x

```babble
def I = interface (
    def x = 1
)
```

```error
An interface default must be a function literal : x
```

## Error : private only applies to members (2)

```babble
def f = fn () -> (
    private def x = 1
    x
)
```

```error
private only applies to members
```

## Error : Name already defined : T

```babble
def f of (T, T) = fn (x : T) : T -> ( x )
```

```error
Name already defined : T
```

## Error : Type parameters only apply to functions, classes, interfaces, and typ...

```babble
def x of T = 1
```

```error
Type parameters only apply to functions, classes, interfaces, and type aliases : x
```

## Error : export only applies to the top-level definitions of a file : x

```babble
def f = fn () -> (
    export def x = 1
)
```

```error
export only applies to the top-level definitions of a file : x
```

## Error : Definition in a while test : x

```babble
while def x = 1 then ( )
```

```error
Definition in a while test : x
```

## Error : return in a parameter default

```babble
def f = fn (x : int = ( return 1 )) : int -> ( x )
```

```error
return in a parameter default
```

## Error : return in a class body

```babble
def C = class () (
    def x = ( return 1 )
)
```

```error
return in a class body
```

## Error : this cannot escape a value class under construction

```babble
def V = record (x : int) (
    def self = this
)
```

```error
this cannot escape a value class under construction
```

## Error : super must be called directly

```babble
def I = interface ( def m = fn () : int -> ( 1 ) )
def C = class () implements I (
    def m = fn () : int -> (
        def s = super
        1
    )
)
```

```error
super must be called directly
```

## Error : super outside of a method

```babble
def I = interface ( def m = fn () : int -> ( 1 ) )
def f = fn () : int -> ( super(I).m() )
```

```error
super outside of a method
```

## Error : super outside of a method (2)

```babble
def I = interface ( def m = fn () : int -> ( 1 ) )
def C = class () (
) static (
    def f = fn () : int -> ( super(I).m() )
)
```

```error
super outside of a method
```

## Error : Member already defined : a

```babble
def C = class () (
    def a = 1
    def a = 2
)
```

```error
Member already defined : a
```

## TypeChecks inheritance & interface validation errors

```babble
def I = interface extends java.lang.String ()
```

```error
Babble interfaces can only extend Java interfaces
```

## Error : Babble classes can only implement Java interfaces

```babble
def C = class () implements java.lang.String ()
```

```error
Babble classes can only implement Java interfaces
```

## Error : implements needs a statically known interface

```babble
def C = class () implements int ()
```

```error
implements needs a statically known interface
```

## Error : implements needs a statically known interface (2)

```babble
def NotI = class () ()
def C = class () implements NotI ()
```

```error
implements needs a statically known interface
```

## Error : Interface extends itself : I

```babble
def I = interface extends I ()
```

```error
Interface extends itself : I
```

## Error : Conflicting defaults for m : I1, I2

```babble
def I1 = interface ( def m = fn () : int -> ( 1 ) )
def I2 = interface ( def m = fn () : int -> ( 2 ) )
def C = class () implements I1, I2 ()
```

```error
Conflicting defaults for m : I1, I2
```

## Error : m is private but I1 provides a public m

```babble
def I1 = interface ( def m = fn () : int -> ( 1 ) )
def C = class () implements I1 (
    private def m = fn () : int -> ( 3 )
)
```

```error
m is private but I1 provides a public m
```

## Error : C.m is private and cannot implement Req.m

```babble
def Req = interface ( def m : fn () : int )
def C = class () implements Req (
    private def m = fn () : int -> ( 3 )
)
```

```error
C.m is private and cannot implement Req.m
```

## Error : C.run takes 1 arguments, but Runnable.run() passes 0

```babble
def C = class () implements java.lang.Runnable (
    def run = fn (x : int) -> ( )
)
```

```error
C.run takes 1 arguments, but Runnable.run() passes 0
```

## Error : I.run takes 1 arguments, but Runnable.run() passes 0

```babble
def I = interface extends java.lang.Runnable (
    def run = fn (x : int) -> ( )
)
```

```error
I.run takes 1 arguments, but Runnable.run() passes 0
```

## Error : I2 is not implemented directly by C

```babble
def I1 = interface ( def m = fn () : int -> ( 1 ) )
def I2 = interface ( def m = fn () : int -> ( 2 ) )
def C = class () implements I1 (
    def m = fn () : int -> ( super(I2).m() )
)
```

```error
I2 is not implemented directly by C
```

## Error : missing is not a member of I

```babble
def I = interface (
    def req : fn () : int
    def callMissing = fn () : int -> ( this.missing() )
)
```

```error
missing is not a member of I
```

## Error : Value members are read-only : x

```babble
def V = record (x : int) (
    def bump = fn () -> ( this.x = 2 )
)
```

```error
Value members are read-only : x
```

## Error : Value members are read-only : x (2)

```babble
def V = record (x : int) ()
def v = V(1)
v.x = 2
```

```error
Value members are read-only : x
```

## Private member and method-assignment checks on BabbleInstance / BabbleType + Member used before definition

```babble
def C = class (private def s : int = 1, def v : int = 2) (
    def m = fn () : int -> ( this.v )
) static (
    private def ps = 9
    def sm = fn () : int -> ( 1 )
)
C().s
```

```error
s is private
```

## Error : s is private

```babble
def C = class (private def s : int = 1, def v : int = 2) ()
C().s = 5
```

```error
s is private
```

## Error : Cannot assign method : m

```babble
def C = class (def v : int = 2) (
    def m = fn () : int -> ( 1 )
)
def c = C()
c.m = c.m
```

```error
Cannot assign method : m
```

## Error : ps is private

```babble
def C = class () () static (
    private def ps = 9
)
C.ps = 1
```

```error
ps is private
```

## Error : Cannot assign method : sm

```babble
def C = class () () static (
    def sm = fn () : int -> ( 1 )
)
C.sm = fn () : int -> ( 2 )
```

```error
Cannot assign method : sm
```

## Error : Member used before definition : late

```babble
def C = class () () static (
    def a = this.late
    def late = 5
)
```

```error
Member used before definition : late
```
