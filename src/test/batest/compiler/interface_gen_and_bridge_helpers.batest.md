# InterfaceGen Covariance, Optional Masks, and BridgeHelpers

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


## InterfaceGen : sub-interface re-abstracting covariant method and overriding with overloaded defaults

```babble
def BC = interface ( def f: fn (x: int): any? )
def ReAbs = interface extends BC ( def f: fn (x: int): int )
def SubOv = interface extends BC ( def f = overload ( fn (x: int): int -> ( x + 1 ), fn (s: string): string -> ( s ) ) )
def r: BC = (class () implements ReAbs ( def f = fn (x: int): int -> ( x ) ))()
def s: BC = (class () implements SubOv ())()
assert(r.f(3) == 3 and s.f(3) == 4)
```

```hir
;; hir -O0,-O1 interface=SubOv
interface SubOv
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int) -> int {pure}
    (+:int var(x) 1:int)
  fn f (s:obj) -> obj {pure}
    var(s)
```

```hir
;; hir -O2 interface=SubOv
interface SubOv
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int) -> int {pure}
    (+:int var(x) 1:int)
  fn f (s:obj) -> obj {pure}
    var(s)
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

```hir
;; hir -O0,-O1 interface=SO
interface SO
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int) -> int {pure}
    (+:int var(x) 2:int)
  fn f (s:obj) -> obj {pure}
    (+:obj var(s) "!")
```

```hir
;; hir -O2 interface=SO
interface SO
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int) -> int {pure}
    (+:int var(x) 2:int)
  fn f (s:obj) -> obj {pure}
    (+:obj var(s) "!")
```

## InterfaceGen : sub-interface optional-mask forwarding from required parent

```babble
def BReq = interface ( def f: fn (x: int, y: int): int )
def SReq = interface extends BReq ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def c: BReq = (class () implements SReq ())()
assert(c.f(2, 3) == 5)
```

```hir
;; hir -O0,-O1 interface=SReq
interface SReq
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

```hir
;; hir -O2 interface=SReq
interface SReq
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

## InterfaceGen : sub-interface optional-mask forwarding from optional parent

```babble
def BOpt = interface ( def f: fn (x: int, y: int = ...): any? )
def SOpt = interface extends BOpt ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def c: BOpt = (class () implements SOpt ())()
assert(c.f(2) == 12 and c.f(2, 4) == 6)
```

```hir
;; hir -O0,-O1 interface=SOpt
interface SOpt
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

```hir
;; hir -O2 interface=SOpt
interface SOpt
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

## InterfaceGen : spread-optional interface default emits ARGUMENTS.MISSING

```babble
def BS = interface ( def f: fn (x: string): string )
def SS = interface extends BS ( def f = fn (x: string, z: string = "!"): string -> ( x + z ) )
def c: BS = (class () implements SS ())()
assert(c.f("ok") == "ok!")
```

```hir
;; hir -O0,-O1 interface=SS
interface SS
  (def f (fn-ref f))
  fn f (x:obj, z:obj="!") -> obj {pure}
    (+:obj var(x) var(z))
```

```hir
;; hir -O2 interface=SS
interface SS
  (def f (fn-ref f))
  fn f (x:obj, z:obj="!") -> obj {pure}
    (+:obj var(x) var(z))
```

## InterfaceGen : rest-parameter interface default implementing fixed 2-arg parent

```babble
def BP = interface ( def f: fn (a: int, b: int): int )
def SR = interface extends BP ( def f = fn (xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s ) )
def c: BP = (class () implements SR ())()
assert(c.f(3, 4) == 7)
```

```hir
;; hir -O0,-O1 interface=SR
interface SR
  (def f (fn-ref f))
  fn f (xs:obj) -> int {pure}
    (def s 0:int)
    (for x in var(xs)
      (block
        (set s += var(x))))
    var(s)
```

```hir
;; hir -O2 interface=SR
interface SR
  (def f (fn-ref f))
  fn f (xs:obj) -> int {pure}
    (def s 0:int)
    (for x in var(xs)
      (block
        (set s += var(x))))
    var(s)
```

## BridgeHelpers : superHelper with optional mask

```babble
def POpt = interface ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def COpt = class () implements POpt ( def f = fn (x: int, y: int = 10): int -> ( super.f(x) + y ) )
assert(COpt().f(2, 3) == 15 and COpt().f(2) == 22)
```

```hir
;; hir -O0,-O1 class=COpt
class COpt
  (def f (fn-ref COpt.f))
  fn COpt.f (x:int, y:int=10:int) -> int {nothrow unique}
    (+:int (super.f var(x)) var(y))
```

```hir
;; hir -O2 class=COpt
class COpt
  (def f (fn-ref COpt.f))
  fn COpt.f (x:int, y:int=10:int) -> int {nothrow unique}
    (+:int (super.f var(x)) var(y))
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

```hir
;; hir -O0,-O1 interface=Prov
interface Prov
  (def m (fn-ref m))
  fn m () -> int {pure}
    7:int
```

```hir
;; hir -O2 interface=Prov
interface Prov
  (def m (fn-ref m))
  fn m () -> int {pure}
    7:int
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

```hir
;; hir -O0,-O1 interface=P
interface P
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

```hir
;; hir -O2 interface=P
interface P
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

## BridgeHelpers : emitInheritedDefaultInvoke dynamic fallback for arity mismatch

```babble
def B1 = interface ( def f: fn (x: int): int )
def P1 = interface ( def f = fn (x: int, y: int = 10): int -> ( x + y ) )
def S1 = interface extends B1, P1 ()
def c: B1 = (class () implements S1 ())()
assert(c.f(3) == 13)
```

```hir
;; hir -O0,-O1 interface=P1
interface P1
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

```hir
;; hir -O2 interface=P1
interface P1
  (def f (fn-ref f))
  fn f (x:int, y:int=10:int) -> int {pure}
    (+:int var(x) var(y))
```

## BridgeHelpers : emitInheritedDefaultInvoke dynamic fallback for overloaded defaults

```babble
def BO = interface ( def f: overload (fn (x: int): int, fn (s: string): string) )
def PO = interface ( def f = overload ( fn (x: int, y: int = 5): int -> ( x + y ), fn (s: string): string -> ( s + "!" ) ) )
def S2 = interface extends BO, PO ()
def c: BO = (class () implements S2 ())()
assert(c.f(3) == 8 and c.f("a") == "a!")
```

```hir
;; hir -O0,-O1 interface=PO
interface PO
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int, y:int=5:int) -> int {pure}
    (+:int var(x) var(y))
  fn f (s:obj) -> obj {pure}
    (+:obj var(s) "!")
```

```hir
;; hir -O2 interface=PO
interface PO
  (def f (overload (fn-ref f#1) (fn-ref f#2)))
  fn f (x:int, y:int=5:int) -> int {pure}
    (+:int var(x) var(y))
  fn f (s:obj) -> obj {pure}
    (+:obj var(s) "!")
```
