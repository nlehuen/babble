# For-loop interface lowering and exception throw/catch lowering

References : `BIP001` (lowering), `BIP008` (iterators & exceptions), `analysis.LoweringAnalyzer` (`deriveZeroArgInterfaceTarget`, `findDeclaringInterfaceMember`, `publishForLoopTarget`, `isGuaranteedJavaThrowable`, `buildCatchTarget`).

```babble
;; babble preamble
def HasNextI = interface (
    def hasNext: fn (): bool
)
def IntIterI = interface extends HasNextI (
    def next: fn mut (): int
)
def IntSeqI = interface (
    def iterator: fn (): IntIterI
)
def GenIterI of T = interface (
    def hasNext: fn (): bool
    def next: fn mut (): T
)
def TagI = interface (
    def code: fn (): int
)
def AppErr = class (def msg: string) ()
```

## For loop over Babble interface-typed iterable and inherited-method iterator

```babble
def RIter = class (def c: int, def n: int) implements IntIterI (
    def hasNext = fn (): bool -> ( this.c < this.n )
    def next = fn mut (): int -> ( def v = this.c ; this.c += 1 ; v )
)
def RSeq = class (def n: int) implements IntSeqI (
    def iterator = fn (): IntIterI -> ( RIter(0, this.n) )
)
def seq: IntSeqI = RSeq(4)
def s = 0 ; for v in seq then ( s += v )
assert(s == 6)
```

## For loop directly over Babble generic interface iterator

```babble
def OneIter = class (def done: bool) implements GenIterI of string (
    def hasNext = fn (): bool -> ( not this.done )
    def next = fn mut (): string -> ( this.done = true ; "ok" )
)
def it: GenIterI of string = OneIter(false)
def out = ""
for s in it then ( out = out + s )
assert(out == "ok")
```

## For loop over reference array

```babble
def arr: array of string = array.of("a", "b")
def out = ""
for s in arr then ( out = out + s )
assert(out == "ab")
```

## Throw lowering for frozen Java throwable

```babble
def fex: frozen java.lang.RuntimeException = freeze(java.lang.IllegalStateException("fz"))
def c1 = try ( throw fex ) catch e: java.lang.IllegalStateException then ( e.getMessage() )
assert(c1 == "fz")
```

## Throw lowering for Java-bounded and Babble-bounded TypeVars

```babble
def rethrowJava of (E: java.lang.RuntimeException) = fn (shared e: E) -> ( throw e )
def rethrowTag of (T: TagI) = fn (shared t: T) -> ( throw t )
def c2 = try ( rethrowJava(java.lang.IllegalArgumentException("tv")) )
    catch e: java.lang.IllegalArgumentException then ( e.getMessage() )
assert(c2 == "tv" and rethrowTag != null)
```

## Throw lowering for Java-only throwable union

```babble
def throwJavaU = fn (flag: bool) -> (
    def e: java.lang.IllegalArgumentException | java.lang.IllegalStateException =
        if flag then ( java.lang.IllegalArgumentException("a") ) else ( java.lang.IllegalStateException("b") )
    throw e
)
def r1 = try ( throwJavaU(true) ) catch e: java.lang.RuntimeException then ( e.getMessage() )
assert(r1 == "a")
```

## Throw lowering for mixed Java and Babble throwable union

```babble
def throwMixU = fn (flag: bool) -> (
    def e: java.lang.IllegalArgumentException | AppErr =
        if flag then ( java.lang.IllegalArgumentException("a") ) else ( AppErr("m") )
    throw e
)
def r2 = try ( throwMixU(false) ) catch e: AppErr then ( e.msg )
assert(r2 == "m")
```

## Throw lowering for intersection with Babble interfaces and nothing

```babble
def I2 = interface ( def id: fn (): int )
def throwInterB = fn (shared e: TagI & I2) -> ( throw e )
def throwNothing = fn () -> ( throw (throw AppErr("inner")) )
def r = try ( throwNothing() ) catch e: AppErr then ( e.msg )
assert(r == "inner")
```

## Catch lowering for local non-top-level Babble class

```babble
def run = fn (): string -> (
    def LocalErr = class (def m: string) ()
    try ( throw LocalErr("loc") ) catch e: LocalErr then ( e.m )
)
assert(run() == "loc")
```

## Catch lowering for union mixing top-level and local Babble classes

```babble
def run = fn (flag: bool): string -> (
    def LocalErr = class (def m: string) ()
    try ( if flag then ( throw AppErr("top") ) else ( throw LocalErr("loc") ) )
    catch e: AppErr | LocalErr then ( if e is AppErr then ( e.msg ) else ( e.m ) )
)
assert(run(true) == "top" and run(false) == "loc")
```

## Catch lowering for Babble interface intersection

```babble
def I2 = interface ( def id: fn (): int )
def BothErr = class (def v: int) implements TagI, I2 (
    def code = fn (): int -> ( this.v )
    def id = fn (): int -> ( this.v + 1 )
)
def run = fn (): int -> (
    try ( throw BothErr(5) )
    catch e: TagI & I2 then ( e.code() + e.id() )
)
assert(run() == 11)
```

## Catch lowering for java.lang.Throwable catching Java Error and Babble value

```babble
def r1 = try ( throw java.lang.Error("err") ) catch e: java.lang.Throwable then ( e.getMessage() )
def r2 = try ( throw AppErr("wrap") ) catch e: java.lang.Throwable then ( "caught" )
assert(r1 == "err" and r2 == "caught")
```
