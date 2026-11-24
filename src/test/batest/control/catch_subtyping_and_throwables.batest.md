# Catch subtyping, throwable validation, and catch reachability

References : `BIP008` (typing), `BIP022` (intersections), `analysis.TypeChecker` (`isCatchSubtype`, `isThrowableType`, `visitTryExpression`, `isValidCatchIntersection`, `validateCatchNullableAst`, `resolveCatchTarget`, `mayBeJavaError`, `isUntypedCatchVar`, `catchSubsumedBy`).

```babble preamble
def IBase = interface (def tag : fn () : int)
def ISub = interface extends IBase ()
def IOther = interface (def code : fn () : int)
def ErrA = record (v : int) implements ISub (
    def tag = fn () : int -> ( this.v )
)
def ErrB = record (v : int) implements IOther (
    def code = fn () : int -> ( this.v )
)
def ErrAB = record (v : int) implements ISub, IOther (
    def tag = fn () : int -> ( this.v )
    def code = fn () : int -> ( this.v )
)
def FrozenErr = type frozen ErrA
def ArrAlias of T = type array of T
```

## Throw frozen, bounded generic, and union throwables

```babble
def rethrow of (T : IBase) = fn (shared e : T) : int -> (
    try ( throw e ) catch x : IBase then ( x.tag() )
)
def f1 = fn (x : frozen ErrA) : int -> (
    try ( throw x ) catch e : ErrA then ( e.v )
)
def f2 = fn (x : ErrA | ErrB) : int -> (
    try ( throw x ) catch e : ErrA | ErrB then ( 1 )
)
assert(rethrow(ErrA(3)) == 3 and f1(freeze(ErrA(4))) == 4 and f2(ErrB(5)) == 1)
```

## Throw intersection caught by supertype, intersection, and Throwable

```babble
def f1 = fn (x : ISub & IOther) : int -> (
    try ( throw x ) catch e : IBase then ( e.tag() )
)
def f2 = fn (x : ErrAB) : int -> (
    try ( throw x ) catch e : ISub & IOther then ( e.tag() + e.code() )
)
def f3 = fn (x : ISub & IOther) : int -> (
    try ( throw x ) catch e : java.lang.Throwable then ( 9 )
)
assert(f1(ErrAB(2)) == 2 and f2(ErrAB(3)) == 6 and f3(ErrAB(1)) == 9)
```

## Throw union partially caught and non-matching intersection catch fallback

```babble
def f1 = fn (x : ErrA | ErrB) : int -> (
    try ( throw x ) catch e : ErrA then ( e.v ) catch e : ErrB then ( e.v + 10 )
)
def f2 = fn (x : ErrA) : int -> (
    try ( throw x ) catch e : ISub & IOther then ( 1 ) catch e : ErrA then ( e.v )
)
assert(f1(ErrB(5)) == 15 and f2(ErrA(4)) == 4)
```

## Non-Error Java exception union caught by untyped catch-all

```babble
def f3 = fn (x : java.io.IOException | java.lang.IllegalStateException) : int -> (
    try ( throw x ) catch e then ( 7 )
)
assert(f3(java.io.IOException("a")) == 7)
```

## Throw union containing Java Error escapes untyped catch-all

```babble
def f = fn io (b : bool) : int -> (
    def e : java.lang.AssertionError | java.io.IOException =
        if b then ( java.lang.AssertionError("boom") ) else ( java.io.IOException("io") )
    try ( throw e )
    catch x then ( 1 )
    catch x : java.lang.AssertionError then ( 2 )
)
assert(f(false) == 1 and f(true) == 2)
```

## Rethrow parenthesized untyped catch variable and valid parenthesized catch type

```babble
def f = fn () : int -> (
    try (
        try ( throw ErrA(7) ) catch e then ( throw (e) )
    ) catch x : (ErrA) then ( x.v )
)
assert(f() == 7)
```

## Catch Babble interface intersected with Java interface is valid

```babble
def f = fn (b : bool) : int -> (
    try ( if b then ( throw ErrA(1) ) else ( 1 ) )
    catch e : IBase & java.io.Serializable then ( 2 )
)
assert(f(false) == 1)
```

## Throw unbounded type parameter is rejected

```babble
def bad of T = fn (shared x : T) -> ( throw x )
```

```error
Type error : cannot throw T
```

## Throw union containing non-throwable scalar is rejected

```babble
def bad = fn (shared x : ErrA | int) -> ( throw x )
```

```error
Type error : cannot throw ErrA | int
```

## Throw intersection of non-throwable Java interfaces is rejected

```babble
def bad = fn (shared x : java.lang.Runnable & java.lang.AutoCloseable) -> ( throw x )
```

```error
Type error : cannot throw java.lang.Runnable & java.lang.AutoCloseable
```

## Catch parenthesized frozen type is rejected

```babble
try ( 1 ) catch e : frozen (ErrA) then ( 2 )
```

```error
Cannot catch 'frozen' type : frozen ErrA
```

## Catch type alias expanding to frozen type is rejected

```babble
try ( 1 ) catch e : FrozenErr then ( 2 )
```

```error
Cannot catch 'frozen' type : frozen ErrA
```

## Catch void is rejected as non-exception type

```babble
try ( 1 ) catch e : void then ( 2 )
```

```error
Cannot catch non-exception type : void
```

## Catch array alias hits resolvesToArrayAlias then rejects non-exception

```babble
try ( 1 ) catch e : ArrAlias of int then ( 2 )
```

```error
Cannot catch non-exception type : array of int
```

## Catch invalid Java interface intersection is rejected

```babble
try ( 1 ) catch e : java.lang.Runnable & java.lang.AutoCloseable then ( 2 )
```

```error
Cannot catch non-exception type : java.lang.Runnable & java.lang.AutoCloseable
```

## Duplicate untyped catch-all clause is unreachable

```babble
try ( 1 ) catch e then ( 2 ) catch f then ( 3 )
```

```error
Unreachable catch clause : catch-all is already caught by earlier catch
```

## Catch clause after java.lang.Throwable is unreachable

```babble
try ( 1 ) catch e : java.lang.Throwable then ( 2 ) catch f : ErrA then ( 3 )
```

```error
Unreachable catch clause : ErrA is already caught by java.lang.Throwable
```

## Partially subsumed union catch clause reports unreachable catch type

```babble
try ( 1 ) catch e : IBase then ( 2 ) catch f : ErrA | ErrB then ( 3 )
```

```error
Unreachable catch type : ErrA is already caught by IBase
```

## Instance catch clause subsumed by earlier intersection catch

```babble
try ( 1 ) catch e : ISub & IOther then ( 2 ) catch f : ErrAB then ( 3 )
```

```error
Unreachable catch clause : ErrAB is already caught by ISub & IOther
```

## Redundant catch union with Throwable and intersection

```babble
try ( 1 ) catch e : java.lang.Throwable | (ISub & IOther) then ( 2 )
```

```error
Redundant catch type : ISub & IOther is already a subtype of java.lang.Throwable
```
