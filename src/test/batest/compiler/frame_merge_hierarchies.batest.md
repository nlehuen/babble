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

```hir
;; hir -O0 fn=joinLR
fn joinLR (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=joinLR
fn joinLR (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=joinLR
fn joinLR (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Babble interface merges : supertype, ambiguous 2-root diamond, and unrelated

```babble
def joinSup = fn (f: bool): any -> ( if f then ( (array of Mid?)(0) ) else ( (array of Leaf?)(0) ) )
def joinAmbig = fn (f: bool): any -> ( if f then ( (array of Fork1?)(0) ) else ( (array of Fork2?)(0) ) )
def joinDisj = fn (f: bool): any -> ( if f then ( (array of Left?)(0) ) else ( (array of AltBase?)(0) ) )
assert(joinSup(true) != null and joinAmbig(false) != null and joinDisj(true) != null)
```

```hir
;; hir -O0 fn=joinAmbig
fn joinAmbig (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=joinAmbig
fn joinAmbig (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=joinAmbig
fn joinAmbig (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Fast record merges : Babble-Babble and Babble-Java class hierarchies

```babble
def joinRec = fn (f: bool): any -> ( if f then ( R1(1) ) else ( R2(2) ) )
def joinRJ = fn io (f: bool): any -> ( if f then ( R1(1) ) else ( java.lang.StringBuilder("a") ) )
def joinJR = fn io (f: bool): any -> ( if f then ( java.lang.StringBuilder("b") ) else ( R2(2) ) )
assert((joinRec(true) as R1).x == 1 and joinRJ(false) != null and joinJR(true) != null)
```

```hir
;; hir -O0 fn=joinRec
fn joinRec (f:bool) -> obj {nothrow}
  (if var(f) (block (record-new R1 1:int)) (block (record-new R2 2:int)))
```

```hir
;; hir -O1 fn=joinRec
fn joinRec (f:bool) -> obj {nothrow}
  (if var(f) (record-new R1 1:int) (record-new R2 2:int))
```

```hir
;; hir -O2 fn=joinRec
fn joinRec (f:bool) -> obj {nothrow}
  (if var(f) (record-new R1 1:int) (record-new R2 2:int))
```

## Mixed Babble record and implemented Babble interface in both orders

```babble
def implA = fn (f: bool): any -> ( if f then ( (array of R1?)(0) ) else ( (array of Base?)(0) ) )
def implB = fn (f: bool): any -> ( if f then ( (array of Base?)(0) ) else ( (array of R1?)(0) ) )
assert(implA(true) != null and implA(false) != null and implB(true) != null)
```

```hir
;; hir -O0 fn=implA
fn implA (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=implA
fn implA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=implA
fn implA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Mixed Babble record and unrelated Babble interface in both orders

```babble
def unrA = fn (f: bool): any -> ( if f then ( (array of R1?)(0) ) else ( (array of AltBase?)(0) ) )
def unrB = fn (f: bool): any -> ( if f then ( (array of AltBase?)(0) ) else ( (array of R1?)(0) ) )
assert(unrA(true) != null and unrA(false) != null and unrB(true) != null)
```

```hir
;; hir -O0 fn=unrA
fn unrA (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=unrA
fn unrA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=unrA
fn unrA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Babble Closeable record and interface merged with Java AutoCloseable

```babble
def acRec = fn (f: bool): any -> ( if f then ( (array of java.lang.AutoCloseable?)(0) ) else ( (array of RC?)(0) ) )
def acIf = fn (f: bool): any -> ( if f then ( (array of MyClose?)(0) ) else ( (array of java.lang.AutoCloseable?)(0) ) )
assert(acRec(true) != null and acIf(false) != null)
```

```hir
;; hir -O0 fn=acRec
fn acRec (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=acRec
fn acRec (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=acRec
fn acRec (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Babble Closeable record merged with unrelated Java Runnable

```babble
def runA = fn (f: bool): any -> ( if f then ( (array of java.lang.Runnable?)(0) ) else ( (array of RC?)(0) ) )
def runB = fn (f: bool): any -> ( if f then ( (array of RC?)(0) ) else ( (array of java.lang.Runnable?)(0) ) )
assert(runA(true) != null and runB(false) != null)
```

```hir
;; hir -O0 fn=runA
fn runA (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=runA
fn runA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=runA
fn runA (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
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

```hir
;; hir -O0 fn=mixCh
fn mixCh (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=mixCh
fn mixCh (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=mixCh
fn mixCh (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Java interface diamond merges : ReadableByteChannel and WritableByteChannel

```babble
def rw = fn (f: bool): any -> (
    if f then ( (array of java.nio.channels.ReadableByteChannel?)(0) ) else ( (array of java.nio.channels.WritableByteChannel?)(0) )
)
assert(rw(true) != null and rw(false) != null)
```

```hir
;; hir -O0 fn=rw
fn rw (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=rw
fn rw (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=rw
fn rw (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Java interface diamond merges : ByteChannel vs InterruptibleChannel and unrelated Closeable vs Flushable

```babble
def bi = fn (f: bool): any -> (
    if f then ( (array of java.nio.channels.ByteChannel?)(0) ) else ( (array of java.nio.channels.InterruptibleChannel?)(0) )
)
def cf = fn (f: bool): any -> ( if f then ( (array of java.io.Closeable?)(0) ) else ( (array of java.io.Flushable?)(0) ) )
assert(bi(true) != null and cf(false) != null)
```

```hir
;; hir -O0 fn=bi
fn bi (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=bi
fn bi (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=bi
fn bi (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Mixed Java class and unrelated Java interface merges in both orders

```babble
def ci = fn (f: bool): any -> ( if f then ( (array of java.lang.StringBuilder?)(0) ) else ( (array of java.io.Flushable?)(0) ) )
def ic = fn (f: bool): any -> ( if f then ( (array of java.io.Flushable?)(0) ) else ( (array of java.lang.StringBuilder?)(0) ) )
assert(ci(true) != null and ic(false) != null)
```

```hir
;; hir -O0 fn=ci
fn ci (f:bool) -> obj {pure unique}
  (if var(f) (block (array-new SIZE 0:int)) (block (array-new SIZE 0:int)))
```

```hir
;; hir -O1 fn=ci
fn ci (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

```hir
;; hir -O2 fn=ci
fn ci (f:bool) -> obj {pure unique}
  (if var(f) (array-new SIZE 0:int) (array-new SIZE 0:int))
```

## Babble diamond Leaf merged with AltBase and loop back-edge record merge

```babble
def leafCheck = fn (f: bool): any -> ( if f then ( (array of Leaf?)(0) ) else ( (array of AltBase?)(0) ) )
def loopJoin = fn (n: int): any -> (
    var cur: any = R1(0) ; var i = 0
    while i < n then ( cur = if i == 0 then ( R1(i) ) else ( R2(i) ) ; i += 1 )
    cur
)
assert(leafCheck(true) != null and loopJoin(2) != null)
```

```hir
;; hir -O0 fn=loopJoin
fn loopJoin (n:int) -> obj {nothrow}
  (def cur (record-new R1 0:int))
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set cur (if (==:bool var(i) 0:int) (block (record-new R1 var(i))) (block (record-new R2 var(i)))))
      (set i += 1:int)))
  var(cur)
```

```hir
;; hir -O1 fn=loopJoin
fn loopJoin (n:int) -> obj {nothrow}
  (def cur (record-new R1 0:int))
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set cur (if (==:bool var(i) 0:int) (record-new R1 var(i)) (record-new R2 var(i))))
      (set i += 1:int)))
  var(cur)
```

```hir
;; hir -O2 fn=loopJoin
fn loopJoin (n:int) -> obj {nothrow}
  (def cur (record-new R1 0:int))
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set cur (if (==:bool var(i) 0:int) (record-new R1 var(i)) (record-new R2 var(i))))
      (set i += 1:int)))
  var(cur)
```
