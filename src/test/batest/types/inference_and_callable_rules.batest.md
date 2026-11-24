# Call-site inference rules, callable escapes, SAM demotion, overloads, and literal ranges

References : `BIP008` (typing), `BIP014` (inference), `BIP026` (overloads), `analysis.TypeChecker` (`isInferrableCallable`, `implementsInterfaceMethod`, `isTypeVarInScope`, `recordInferredParam`, `visitOverloadExpression`, `resolveSamTargetForFlow`, `resolveSamTargetForAs`, `demoteMemberToField`, `hasBabbleDefault`, `applyContextualType`, `widenNumericType`, `visitForExpression`, numeric range checks).

```babble preamble
def ITop = interface (def greet : fn (int) : int)
def IMid = interface extends ITop ()
def IDefRun = interface (
    def run = fn () -> ( null )
)
def IDefRunChild = interface extends IDefRun ()
def Box = class (v : int) ()
```

## Call-site inference works for private methods of exported classes

```babble
export def Exp = class (def x : int) (
    private def helper = fn (y) -> ( this.x + y )
    def run = fn () : int -> ( this.helper(5) )
)
assert(Exp(3).run() == 8)
```

## Call-site inference works for static methods and non-interface helpers

```babble
def Local = class () implements IMid, java.lang.Runnable (
    def greet = fn (n : int) : int -> ( this.extra(n) )
    def run = fn () -> ( null )
    def extra = fn (k) -> ( k + 1 )
) static (
    def stat = fn (z) -> ( z * 2 )
)
assert(Local().greet(4) == 5 and Local.stat(6) == 12)
```

## Exported class unannotated constructor parameter is rejected

```babble
export def C = class (x) ()
```

```error
Missing type : x
```

## Escaping class value with unannotated constructor parameter is rejected

```babble
def C = class (x) ()
def ref = C
C(1)
```

```error
Missing type : x
```

## Exported class public unannotated method parameter is rejected

```babble
export def C = class () ( def m = fn (x) -> ( x ) )
```

```error
Missing type : x
```

## Transitive Babble interface method requires parameter annotation

```babble
def C = class () implements IMid ( def greet = fn (x) -> ( x ) )
C().greet(1)
```

```error
Missing type : x
```

## Java interface method requires parameter annotation

```babble
def C = class () implements java.util.function.Function of (int, int) (
    def apply = fn (x) -> ( x )
)
C().apply(1)
```

```error
Missing type : x
```

## Unannotated inner helper infers enclosing function type parameter

```babble
def outer of T = fn (x : T) : T -> (
    def inner = fn (y) -> ( y )
    inner(x)
)
assert(outer(42) == 42 and outer("hi") == "hi")
```

## Foreign type parameter does not poison top-level unannotated helper

```babble
def helper = fn (y) -> ( y )
def ok = helper(1)
def bad of T = fn (x : T) -> ( helper(x) )
```

```error
Type error : expected int, got T
```

## Class type parameter is out of scope inside class static block helper

```babble
def C of T = class () (
    def m = fn (x : T) -> ( C.s(x) )
) static (
    def s = fn (y) -> ( y )
)
def ok = C.s(1)
```

```error
Type error : expected int, got T
```

## Overload expressions with non-literal functions, parenthesized selection, assignment, return, and anonymous call

```babble
def g = fn (x : int) : int -> ( x + 1 )
def ov = overload (g, fn (s : string) : string -> ( s + "!" ))
def f : fn (int) : int = (ov)
f = ov
def ret = fn () : fn (string) : string -> ( return ov )
def anon = (overload (fn (x : int) : int -> ( x * 2 ), fn (s : string) : string -> ( s )))(5)
assert(f(3) == 4 and ret()("a") == "a!" and anon == 10)
```

## Overload alternative missing return type is rejected

```babble
def bad = overload (fn (x : int) -> ( x ), fn (s : string) : string -> ( s ))
```

```error
Missing type : return type of overload alternative
```

## Non-function operand in overload expression is rejected

```babble
def bad = overload (42, fn (s : string) : string -> ( s ))
```

```error
Type error : expected function, got int
```

## SAM resolution in union targets for flow and as casts

```babble
def f = fn () -> ( null )
def u1 : java.lang.Runnable | int = f
def u2 : java.lang.Runnable | (fn () : null) = f
def u3 = f as (java.lang.Runnable | int)
def u4 = f as (java.lang.Runnable | any)
assert(u1 is java.lang.Runnable and u2 != null and u3 is java.lang.Runnable and u4 != null)
```

```stderr
Warning : Redundant cast : any
```

## Demoted SAM field satisfied by transitive Babble default still checks Babble interface type

```babble
def C = class () implements java.lang.Runnable, IDefRunChild (
    def run : java.lang.Runnable | int = fn () -> ( null )
)
```

```error
Type error : expected () : null, got java.lang.Runnable | int
```

## Demoted SAM field calling super reports super outside of a method

```babble
def C = class () implements IDefRunChild (
    def cb : java.lang.Runnable = fn () -> ( super.run() )
)
```

```error
super outside of a method
```

## Contextual typing for rest-parameter lambda and union numeric return widening

```babble
def f : fn (int, int) : int = fn (a, rest : int...) -> ( a )
def g : fn (bool) : double? = fn (b) -> ( if b then ( 1 ) else ( null ) )
assert(f(7, 8) == 7 and g(true) == 1.0 and g(false) == null)
```

## For-loop element annotation adapts to Java SAM

```babble
def fns = list of (fn () : null)(fn () -> ( null ))
def n = 0
for r : java.lang.Runnable in fns then ( r.run() ; n += 1 )
assert(n == 1)
```

## For-loop element annotation mismatch is rejected

```babble
for x : string in list of int(1) then ( x )
```

```error
Type error : expected string, got int
```

## Unsigned integer literal exceeding long range is rejected

```babble
def x : long = 99999999999999999999
```

```error
Out of long range : 99999999999999999999
```

## Negated long literal exceeding long range is rejected

```babble
def x = -99999999999999999999L
```

```error
Out of long range : -99999999999999999999L
```

## Negated float literal exceeding float range is rejected

```babble
def x = -1e40f
```

```error
Out of float range : -1e40f
```

## Double literal assigned to float exceeding float range is rejected

```babble
def x : float = 1e40
```

```error
Out of float range : 1e40
```

## Double literal exceeding double range is rejected

```babble
def x = 1e400
```

```error
Out of double range : 1e400
```

## Double literal with D suffix parses and evaluates

```babble
assert(1.5D + 2D == 3.5)
```
