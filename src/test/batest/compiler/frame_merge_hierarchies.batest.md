# Stack frame merge hierarchies : Babble and Java class/interface joins

References : BIP001, BIP011 ; targets `ScriptClassWriter` (`getCommonSuperClass`, `isAssignable`, `commonSuperInterface`, `ancestorInterfaces`, `directParents`, `superclassOf`)

```babble
;; babble preamble
def Base = interface ( def tag: fn (): string )
def AltBase = interface ( def alt: fn (): int )
def Mid = interface extends Base ()
def Left = interface extends Mid ()
def Right = interface extends Mid ()
def Leaf = interface extends Left, Right ()
def Fork1 = interface extends Base, AltBase ()
def Fork2 = interface extends Base, AltBase ()
def MyClose = interface extends java.io.Closeable ()
def R1 = record (x: int) implements Left ( def tag = fn (): string -> ( "r1" ) )
def R2 = record (x: int) implements Right ( def tag = fn (): string -> ( "r2" ) )
def RC = record (x: int) implements java.io.Closeable ( def close = fn (): any? -> ( null ) )
```

## Babble interface diamond merges : 2-level LCA and subtype

```babble
def joinLR = fn (f: bool): any -> ( if f then ( (array of Left?)(0) ) else ( (array of Right?)(0) ) )
def joinSub = fn (f: bool): any -> ( if f then ( (array of Leaf?)(0) ) else ( (array of Left?)(0) ) )
assert(joinLR(true) != null and joinLR(false) != null and joinSub(true) != null)
```

## Babble interface merges : supertype, ambiguous 2-root diamond, and unrelated

```babble
def joinSup = fn (f: bool): any -> ( if f then ( (array of Mid?)(0) ) else ( (array of Leaf?)(0) ) )
def joinAmbig = fn (f: bool): any -> ( if f then ( (array of Fork1?)(0) ) else ( (array of Fork2?)(0) ) )
def joinDisj = fn (f: bool): any -> ( if f then ( (array of Left?)(0) ) else ( (array of AltBase?)(0) ) )
assert(joinSup(true) != null and joinAmbig(false) != null and joinDisj(true) != null)
```

## Fast record merges : Babble-Babble and Babble-Java class hierarchies

```babble
def joinRec = fn (f: bool): any -> ( if f then ( R1(1) ) else ( R2(2) ) )
def joinRJ = fn io (f: bool): any -> ( if f then ( R1(1) ) else ( java.lang.StringBuilder("a") ) )
def joinJR = fn io (f: bool): any -> ( if f then ( java.lang.StringBuilder("b") ) else ( R2(2) ) )
assert((joinRec(true) as R1).x == 1 and joinRJ(false) != null and joinJR(true) != null)
```

## Mixed Babble record and implemented Babble interface in both orders

```babble
def implA = fn (f: bool): any -> ( if f then ( (array of R1?)(0) ) else ( (array of Base?)(0) ) )
def implB = fn (f: bool): any -> ( if f then ( (array of Base?)(0) ) else ( (array of R1?)(0) ) )
assert(implA(true) != null and implA(false) != null and implB(true) != null)
```

## Mixed Babble record and unrelated Babble interface in both orders

```babble
def unrA = fn (f: bool): any -> ( if f then ( (array of R1?)(0) ) else ( (array of AltBase?)(0) ) )
def unrB = fn (f: bool): any -> ( if f then ( (array of AltBase?)(0) ) else ( (array of R1?)(0) ) )
assert(unrA(true) != null and unrA(false) != null and unrB(true) != null)
```

## Babble Closeable record and interface merged with Java AutoCloseable

```babble
def acRec = fn (f: bool): any -> ( if f then ( (array of java.lang.AutoCloseable?)(0) ) else ( (array of RC?)(0) ) )
def acIf = fn (f: bool): any -> ( if f then ( (array of MyClose?)(0) ) else ( (array of java.lang.AutoCloseable?)(0) ) )
assert(acRec(true) != null and acIf(false) != null)
```

## Babble Closeable record merged with unrelated Java Runnable

```babble
def runA = fn (f: bool): any -> ( if f then ( (array of java.lang.Runnable?)(0) ) else ( (array of RC?)(0) ) )
def runB = fn (f: bool): any -> ( if f then ( (array of RC?)(0) ) else ( (array of java.lang.Runnable?)(0) ) )
assert(runA(true) != null and runB(false) != null)
```

## Babble interface extending Java interface merged with sibling and unrelated Java interfaces

```babble
def mixCh = fn (f: bool): any -> (
    if f then ( (array of MyClose?)(0) ) else ( (array of java.nio.channels.InterruptibleChannel?)(0) )
)
def mixDisj = fn (f: bool): any -> (
    if f then ( (array of MyClose?)(0) ) else ( (array of java.io.Flushable?)(0) )
)
assert(mixCh(true) != null and mixDisj(false) != null)
```

## Java interface diamond merges : ReadableByteChannel and WritableByteChannel

```babble
def rw = fn (f: bool): any -> (
    if f then ( (array of java.nio.channels.ReadableByteChannel?)(0) ) else ( (array of java.nio.channels.WritableByteChannel?)(0) )
)
assert(rw(true) != null and rw(false) != null)
```

## Java interface diamond merges : ByteChannel vs InterruptibleChannel and unrelated Closeable vs Flushable

```babble
def bi = fn (f: bool): any -> (
    if f then ( (array of java.nio.channels.ByteChannel?)(0) ) else ( (array of java.nio.channels.InterruptibleChannel?)(0) )
)
def cf = fn (f: bool): any -> ( if f then ( (array of java.io.Closeable?)(0) ) else ( (array of java.io.Flushable?)(0) ) )
assert(bi(true) != null and cf(false) != null)
```

## Mixed Java class and unrelated Java interface merges in both orders

```babble
def ci = fn (f: bool): any -> ( if f then ( (array of java.lang.StringBuilder?)(0) ) else ( (array of java.io.Flushable?)(0) ) )
def ic = fn (f: bool): any -> ( if f then ( (array of java.io.Flushable?)(0) ) else ( (array of java.lang.StringBuilder?)(0) ) )
assert(ci(true) != null and ic(false) != null)
```

## Babble diamond Leaf merged with AltBase and loop back-edge record merge

```babble
def leafCheck = fn (f: bool): any -> ( if f then ( (array of Leaf?)(0) ) else ( (array of AltBase?)(0) ) )
def loopJoin = fn (n: int): any -> (
    def cur: any = R1(0) ; def i = 0
    while i < n then ( cur = if i == 0 then ( R1(i) ) else ( R2(i) ) ; i += 1 )
    cur
)
assert(leafCheck(true) != null and loopJoin(2) != null)
```
