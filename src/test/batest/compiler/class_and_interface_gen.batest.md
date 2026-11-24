# Class and interface bytecode generation : bridges, defaults, records, and Java contracts

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

## Cross-module direct $spec method call with optional mask

```babble
def c = extCalc.ExtCalc(5)
assert(c.add(3) == 18 and c.add(3, 2) == 10)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def t (call name(import) "org.babblelang.tests"))
  (def extCalc (call name(import) "ext_calc.ba"))
  (def jFix (call name(import) "java_fixtures.ba"))
  (def jCb (call name(import) "java_typed_cb.ba"))
  (def App null)
  (def CS null)
  (def Fmt (iface-ref Fmt))
  (def addOne (fn-ref addOne))
  (def fmtInt (fn-ref fmtInt))
  (def fmtStr (fn-ref fmtStr))
  (def c (call (export name(extCalc).ExtCalc) 5:int))
  (call name(assert) (and (==:bool (call-method name(c).add 3:int) 18:int) (==:bool (call-method name(c).add 3:int 2:int) 10:int)))
```

## Record with all 8 primitives plus reference and body-declared derived primitive field

```babble
def R = record (b: byte, s: short, i: int, l: long, f: float, d: double, z: bool, c: char, u: string) (
    def k: int = this.i * 2
)
def a = R(1, 2, 3, 4L, 1.5f, 2.5, true, 'A', "x")
def b = R(1, 2, 3, 4L, 1.5f, 2.5, true, 'A', "x")
assert(a == b and a != R(1, 2, 4, 4L, 1.5f, 2.5, true, 'A', "x"))
assert(java.util.Objects.hashCode(a) == java.util.Objects.hashCode(b) and a.k == 6)
```

```hir
;; hir -O0,-O1 record=R
record R (b:byte, s:short, i:int, l:long, f:float, d:double, z:bool, c:char, u:obj)
  (def k (*:int (get-this-field i) 2:int))
```

## Babble interface bridge fallback : non-literal overload alt and overload-holding field

```babble
def NonLit = class () implements Fmt ( def f = overload ( fmtInt, fn (s: string): string -> ( "l:" + s ) ) )
def FieldOv = class (ov: overload (fn (x: int): string, fn (s: string): string)) implements Fmt ( def f = ov )
def a: Fmt = NonLit() ; def b: Fmt = FieldOv(overload ( fmtInt, fmtStr ))
assert(a.f(7) == "i:7" and a.f("x") == "l:x" and b.f(8) == "i:8" and b.f("y") == "s:y")
```

```hir
;; hir -O0,-O1 class=NonLit
class NonLit
  (def f (overload name(fmtInt) (fn-ref NonLit.f)))
  fn NonLit.f (s:obj) -> obj {nothrow injective unique}
    (+:obj "l:" var(s))
```

```hir
;; hir -O0,-O1 class=FieldOv
class FieldOv (ov:obj)
  (def f var(ov))
```

## Babble interface bridge : 0-arg field fallback and ref-only spread with extra optional param

```babble
def Zero = interface ( def get: fn (): int )
def StrOp = interface ( def op: fn (x: string): string )
def ZC = class (g: fn (): int) implements Zero ( def get = g )
def SC = class () implements StrOp ( def op = fn (x: string, suf: string = "!"): string -> ( x + suf ) )
def z: Zero = ZC(fn (): int -> ( 9 )) ; def s: StrOp = SC()
assert(z.get() == 9 and s.op("hi") == "hi!")
```

```hir
;; hir -O0,-O1 class=ZC
class ZC (g:obj)
  (def get var(g))
```

```hir
;; hir -O0,-O1 class=SC
class SC
  (def op (fn-ref SC.op))
  fn SC.op (x:obj, suf:obj="!") -> obj {nothrow associative unique}
    (+:obj var(x) var(suf))
```

## Babble interface bridge : variadic rest parameter packing on mismatched restIndex

```babble
def VarI = interface ( def sum: fn (a: int, xs: int...): int )
def VarC = class () implements VarI (
    def sum = fn (xs: int...): int -> (
        def acc = 0
        for v in xs then ( acc += v )
        acc
    )
)
def v: VarI = VarC()
assert(v.sum(10, 20, 30) == 60 and v.sum(5) == 5)
```

```hir
;; hir -O0,-O1 class=VarC
class VarC
  (def sum (fn-ref VarC.sum))
  fn VarC.sum (xs:obj) -> int {nothrow unique}
    (def acc 0:int)
    (for v in var(xs)
      (block
        (set acc += var(v))))
    var(acc)
```

## Babble interface bridge : rest method implementing fixed 2-arg interface

```babble
def Pair = interface ( def join: fn (a: int, b: int): int )
def RestC = class () implements Pair (
    def join = fn (xs: int...): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s )
)
def p: Pair = RestC()
assert(p.join(4, 9) == 13)
```

```hir
;; hir -O0,-O1 class=RestC
class RestC
  (def join (fn-ref RestC.join))
  fn RestC.join (xs:obj) -> int {nothrow unique}
    (def s 0:int)
    (for x in var(xs)
      (block
        (set s += var(x))))
    var(s)
```

## Java varargs interface bridge : Babble class implementing VarArgsSink with rest parameter

```babble
def SumSink = class () implements t.JavaClassDemo.VarArgsSink (
    def sum = fn (xs: int...): int -> (
        def acc = 0
        for x in xs then ( acc += x )
        acc
    )
)
assert(t.JavaClassDemo.invokeVarArgsSink(SumSink(), 2, 3, 5) == 10)
assert(t.JavaClassDemo.invokeVarArgsSink(SumSink()) == 0)
```

```hir
;; hir -O0,-O1 class=SumSink
class SumSink
  (def sum (fn-ref SumSink.sum))
  fn SumSink.sum (xs:obj) -> int {nothrow unique}
    (def acc 0:int)
    (for x in var(xs)
      (block
        (set acc += var(x))))
    var(acc)
```

## Java covariant and generic bridge methods : StringCriterion and CovariantSupplier

```babble
def NonEmpty = class () implements t.JavaClassDemo.StringCriterion (
    def test = fn (s: string): bool -> ( s.length() > 0 )
)
def HiSup = class () implements t.JavaClassDemo.CovariantSupplier (
    def get = fn (): string -> ( "hi" )
)
assert(t.JavaClassDemo.invokeErasedPredicate(NonEmpty(), "a"))
assert(t.JavaClassDemo.invokeErasedSupplier(HiSup()) == "hi")
```

```hir
;; hir -O0,-O1 class=NonEmpty
class NonEmpty
  (def test (fn-ref NonEmpty.test))
  fn NonEmpty.test (s:obj) -> bool {nothrow unique}
    (>:bool (jcall String.length var(s)) 0:int)
```

```hir
;; hir -O0,-O1 class=HiSup
class HiSup
  (def get (fn-ref HiSup.get))
  fn HiSup.get () -> obj {nothrow unique}
    "hi"
```

## Java interface with boxed wrapper parameters : TypedCallback

```babble
def c = jCb.Cb()
assert(t.JavaClassDemo.invokeAcceptInt(c, 4) == 5)
assert(t.JavaClassDemo.invokeAcceptDouble(c, 1.5) == 3.0)
assert(not t.JavaClassDemo.invokeAcceptBool(c, true))
assert(t.JavaClassDemo.invokeAcceptString(c, "ok") == "ok!")
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def t (call name(import) "org.babblelang.tests"))
  (def extCalc (call name(import) "ext_calc.ba"))
  (def jFix (call name(import) "java_fixtures.ba"))
  (def jCb (call name(import) "java_typed_cb.ba"))
  (def App null)
  (def CS null)
  (def Fmt (iface-ref Fmt))
  (def addOne (fn-ref addOne))
  (def fmtInt (fn-ref fmtInt))
  (def fmtStr (fn-ref fmtStr))
  (def c (call (export name(jCb).Cb)))
  (call name(assert) (==:bool (jcall JavaClassDemo.invokeAcceptInt name(c) 4:int) 5:int))
  (call name(assert) (==:bool (jcall JavaClassDemo.invokeAcceptDouble name(c) 1.5:double) 3.0:double))
  (call name(assert) (not (jcall JavaClassDemo.invokeAcceptBool name(c) true:bool)))
  (call name(assert) (==:bool (jcall JavaClassDemo.invokeAcceptString name(c) "ok") "ok!"))
```

## Java interface bridge : ref-only spread with extra optional param

```babble
def Tag = class () implements java.util.function.UnaryOperator of string (
    def apply = fn (s: string, suf: string = "!"): string -> ( s + suf )
)
def u: java.util.function.UnaryOperator of string = Tag()
assert(u.apply("ok") == "ok!")
```

```hir
;; hir -O0,-O1 class=Tag
class Tag
  (def apply (fn-ref Tag.apply))
  fn Tag.apply (s:obj, suf:obj="!") -> obj {nothrow associative unique}
    (+:obj var(s) var(suf))
```

## Java interface void bridge discarding 2-slot double return with POP2

```babble
def WideVoid = class (def n: int) implements java.lang.Runnable (
    def run = fn mut (): double -> ( this.n += 1 ; 2.5 )
)
def w = WideVoid(0) ; def r: java.lang.Runnable = w
r.run()
assert(w.n == 1)
```

```hir
;; hir -O0,-O1 class=WideVoid
class WideVoid (n:int)
  (def run (fn-ref WideVoid.run))
  fn WideVoid.run () -> double {nothrow mut unique}
    (set-this-field n += 1:int)
    2.5:double
```

## Java interface inherited Babble defaults returning byte and short

```babble
def IB = interface extends t.JavaClassDemo.ByteUnary ( def apply = fn (x: byte): byte -> ( x ) )
def IS = interface extends t.JavaClassDemo.ShortUnary ( def apply = fn (x: short): short -> ( x ) )
def cb: t.JavaClassDemo.ByteUnary = (class () implements IB ())()
def cs: t.JavaClassDemo.ShortUnary = (class () implements IS ())()
assert(cb.apply(3) == byte(3) and cs.apply(4) == short(4))
```

```hir
;; hir -O0,-O1 interface=IB
interface IB
  (def apply (fn-ref apply))
  fn apply (x:byte) -> byte {pure}
    var(x)
```

```hir
;; hir -O0,-O1 interface=IS
interface IS
  (def apply (fn-ref apply))
  fn apply (x:short) -> short {pure}
    var(x)
```

## Java interface inherited Babble defaults returning float and 0-arg long

```babble
def IF = interface extends t.JavaClassDemo.FloatUnary ( def apply = fn (x: float): float -> ( x ) )
def IL = interface extends java.util.function.LongSupplier ( def getAsLong = fn (): long -> ( 42L ) )
def cf: t.JavaClassDemo.FloatUnary = (class () implements IF ())()
def cl: java.util.function.LongSupplier = (class () implements IL ())()
assert(cf.apply(1.5f) == 1.5f and cl.getAsLong() == 42L)
```

```hir
;; hir -O0,-O1 interface=IF
interface IF
  (def apply (fn-ref apply))
  fn apply (x:float) -> float {pure}
    var(x)
```

```hir
;; hir -O0,-O1 interface=IL
interface IL
  (def getAsLong (fn-ref getAsLong))
  fn getAsLong () -> long {pure}
    42:long
```

## Java re-abstracted default method : AnnotatedType and AnnotatedArrayType

```babble
def a: java.lang.reflect.AnnotatedArrayType = jFix.MyAAT()
assert(a.getAnnotatedOwnerType() == null and (a.getAnnotations()!).size() == 0)
```

```hir
;; hir -O0 top-level
top-level:
  (def t (call name(import) "org.babblelang.tests"))
  (def extCalc (call name(import) "ext_calc.ba"))
  (def jFix (call name(import) "java_fixtures.ba"))
  (def jCb (call name(import) "java_typed_cb.ba"))
  (def App null)
  (def CS null)
  (def Fmt (iface-ref Fmt))
  (def addOne (fn-ref addOne))
  (def fmtInt (fn-ref fmtInt))
  (def fmtStr (fn-ref fmtStr))
  (def a (call (export name(jFix).MyAAT)))
  (call name(assert) (and (==:bool (jcall AnnotatedArrayType.getAnnotatedOwnerType name(a)) null) (==:bool (array-op SIZE (block (nonnull (jcall AnnotatedType.getAnnotations name(a))))) 0:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def t (call name(import) "org.babblelang.tests"))
  (def extCalc (call name(import) "ext_calc.ba"))
  (def jFix (call name(import) "java_fixtures.ba"))
  (def jCb (call name(import) "java_typed_cb.ba"))
  (def App null)
  (def CS null)
  (def Fmt (iface-ref Fmt))
  (def addOne (fn-ref addOne))
  (def fmtInt (fn-ref fmtInt))
  (def fmtStr (fn-ref fmtStr))
  (def a (call (export name(jFix).MyAAT)))
  (call name(assert) (and (==:bool (jcall AnnotatedArrayType.getAnnotatedOwnerType name(a)) null) (==:bool (array-op SIZE (nonnull (jcall AnnotatedType.getAnnotations name(a)))) 0:int)))
```

## Java default methods with non-matching Babble overload set on Iterator

```babble
def OvIter = class () implements java.util.Iterator of int (
    def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 )
    def remove = overload ( fn (n: int): int -> ( n ), fn (s: string): string -> ( s ) )
)
assert(OvIter().remove(5) == 5 and OvIter().remove("a") == "a")
```

```hir
;; hir -O0,-O1 class=OvIter
class OvIter
  (def hasNext (fn-ref OvIter.hasNext))
  (def next (fn-ref OvIter.next))
  (def remove (overload (fn-ref OvIter.remove#1) (fn-ref OvIter.remove#2)))
  fn OvIter.hasNext () -> bool {nothrow unique}
    false:bool
  fn OvIter.next () -> int {nothrow unique}
    0:int
  fn OvIter.remove (n:int) -> int {nothrow idempotent monotone injective unique}
    var(n)
  fn OvIter.remove (s:obj) -> obj {nothrow idempotent monotone injective unique}
    var(s)
```

## Java default methods with single-method arity mismatch on Iterator

```babble
def ArityIter = class () implements java.util.Iterator of int (
    def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 )
    def remove = fn (n: int): int -> ( n )
    def forEachRemaining = fn (): int -> ( 1 )
)
assert(ArityIter().remove(2) == 2 and ArityIter().forEachRemaining() == 1)
```

```hir
;; hir -O0,-O1 class=ArityIter
class ArityIter
  (def hasNext (fn-ref ArityIter.hasNext))
  (def next (fn-ref ArityIter.next))
  (def remove (fn-ref ArityIter.remove))
  (def forEachRemaining (fn-ref ArityIter.forEachRemaining))
  fn ArityIter.hasNext () -> bool {nothrow unique}
    false:bool
  fn ArityIter.next () -> int {nothrow unique}
    0:int
  fn ArityIter.remove (n:int) -> int {nothrow idempotent monotone injective unique}
    var(n)
  fn ArityIter.forEachRemaining () -> int {nothrow unique}
    1:int
```

## Inherited overloaded Babble default on Java Iterator skips unmatched Java default

```babble
def MixIter = interface extends java.util.Iterator of int (
    def remove = overload ( fn (n: int): int -> ( n ), fn (s: string): string -> ( s ) )
)
def C1 = class () implements MixIter ( def hasNext = fn (): bool -> ( false ) ; def next = fn (): int -> ( 0 ) )
assert(C1().remove(3) == 3 and C1().remove("x") == "x")
```

```hir
;; hir -O0,-O1 interface=MixIter
interface MixIter
  (def remove (overload (fn-ref remove#1) (fn-ref remove#2)))
  fn remove (n:int) -> int {pure}
    var(n)
  fn remove (s:obj) -> obj {pure}
    var(s)
```

## Inherited overloaded Babble default on Java Appendable invokes OVERLOAD_SET.memberOf

```babble
def OvApp = interface extends java.lang.Appendable (
    def append = overload (
        fn (c: char): App -> ( this ),
        fn (s: CS): App -> ( this ),
        fn (s: CS, i: int, j: int): App -> ( this )
    )
)
def a: App = (class () implements OvApp ())()
assert(a.append('x') != null and a.append("ab", 0, 1) != null)
```

```hir
;; hir -O0,-O1 interface=OvApp
interface OvApp
  (def append (overload (fn-ref append#1) (fn-ref append#2) (fn-ref append#3)))
  fn append (c:char) -> obj {pure}
    this
  fn append (s:obj) -> obj {pure}
    this
  fn append (s:obj, i:int, j:int) -> obj {pure}
    this
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

## InterfaceGen : rest-parameter interface default implementing fixed 2-arg parent

```babble
def BP = interface ( def f: fn (a: int, b: int): int )
def SR = interface extends BP ( def f = fn (xs: int...): int -> ( def s = 0 ; for x in xs then ( s += x ) ; s ) )
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
