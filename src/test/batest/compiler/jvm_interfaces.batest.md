# Babble interfaces compiled to JVM interfaces with INVOKEINTERFACE, INSTANCEOF, and INVOKESPECIAL

References : BIP002, RM003-P2 (JVM interface lowering, bridges, super defaults, cross-module)

```babble
;; babble module=shapes.ba
export def Shape = interface (
    def area: fn (): double
    def scale: fn (factor: double): double = fn (factor: double): double -> (this.area() * factor)
)

export def Circle = record (r: double) implements Shape (
    def area = fn (): double -> (3.0 * this.r * this.r)
)

export def Tag = interface (
    def label = fn (): string -> ("tag")
)
export def Tagged = class (name: string) implements Tag (
    def label = fn (): string -> (super.label() + ":" + name)
)
```

```babble
;; babble preamble
def Box of T = interface (
    def get: fn (): T
    def xf: fn (x: T): T = fn (x: T): T -> (x)
)

def Unary = interface (def apply: fn (x: int): int)
def Opt = interface extends Unary (
    def apply = fn (x: int, s: int = 10): int -> (x + s)
)

def Op = interface (
    def run: fn (x: int): int
    def base = fn (x: int): int -> (x + 100)
)

def Calc = interface (
    def calc: fn (a: int, b: double, neg: bool): double
    def check = fn (x: int): double -> (this.calc(x, 2.5, false) + this.calc(x, 0.5, true))
)

def P1 = interface (def sc: fn (x: int): int = fn (x: int): int -> (x + 10))
def P2 = interface (def sc: fn (x: int): int = fn (x: int): int -> (x * 10))
```

## Case 1

```babble
def Impl = class (bias: double) implements Calc (
    def calc = fn (a: int, b: double, neg: bool): double -> (def v = a + b + bias ; if neg then (-v) else (v))
)
def c: Calc = Impl(1.0)
assert(c.calc(3, 1.5, false) == 5.5 and c.check(4) == 2.0)
assert((c is Calc) and (c is Impl) and not ("no" is Calc))
```

```hir
;; hir -O0 class=Impl
class Impl (bias:double)
  (def calc (fn-ref Impl.calc))
  fn Impl.calc (a:int, b:double, neg:bool) -> double {nothrow}
    (def v (+:double (+:double var(a) var(b)) var(bias)))
    (if var(neg) (block (neg var(v))) (block var(v)))
```

```hir
;; hir -O1 class=Impl
class Impl (bias:double)
  (def calc (fn-ref Impl.calc))
  fn Impl.calc (a:int, b:double, neg:bool) -> double {nothrow}
    (def v (+:double (+:double var(a) var(b)) var(bias)))
    (if var(neg) (neg var(v)) var(v))
```

```mir
;; mir -O2 class=Impl stage=before,final
class Impl:
  fn Impl.calc(v0: int, v1: double, v2: bool) -> double:
    bb0:
      v3: double = convert.double v0
      v4: double = add v3, v1 {pure}
      v5: double = slot_read bias {pure}
      v6: double = add v4, v5 {pure}
      br_if v2, bb2, bb1
    bb1:
      br bb3(v6)
    bb2:
      v7: double = neg v6 {pure}
      br bb3(v7)
    bb3(v8: double):
      ret v8
```

## Multi-evaluation closure-capturing interface factory : each BabbleInterface keeps its own captured cell

```babble
def mk = fn (off: int) -> (
    def I = interface (def add = fn (x: int): int -> (x + off))
    def C = class (m: int) implements I (def run = fn (x: int): int -> (super.add(x) * m))
    object (def eval = fn (m: int, x: int): int -> (def c = C(m) ; def i: I = c ; i.add(x) + c.run(x)))
)
assert(mk(10).eval(2, 5) == 45 and mk(100).eval(3, 5) == 420)
```

```hir
;; hir -O0,-O1 fn=mk
fn mk (off:int) -> obj {nothrow unique}
  (def I (iface-ref I))
  (def C (class-ref C))
  (class-ref object)
```

```hir
;; hir -O0,-O1 class=C
class C (m:int)
  (def run (fn-ref C.run))
  fn C.run (x:int) -> int {nothrow unique}
    (*:int (super.add var(x)) var(m))
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  fn C.run(v0: int) -> int:
    bb0:
      v1: any = opaque.SuperCall(add, v0) {impure}
      v2: int = convert.int v1
      v3: int = slot_read m {pure}
      v4: int = mul v2, v3 {pure}
      ret v4
```

## Diamond inheritance (JVMS 5.4.3.3 bridge conflict prevention)

```babble
def Base = interface (def id = fn (x: int): int -> (x + 1))
def Left = interface extends Base ()
def Right = interface extends Base ()
def Diamond = interface extends Left, Right ()
def DiamondImpl = class () implements Diamond ()
def d: Diamond = DiamondImpl()
assert(d.id(9) == 10 and (d is Base))
```

```hir
;; hir -O0,-O1 interface=Base
interface Base
  (def id (fn-ref id))
  fn id (x:int) -> int {pure}
    (+:int var(x) 1:int)
```

```mir
;; mir -O2 interface=Base stage=before,final
interface Base:
  fn id(v0: int) -> int:
    bb0:
      v1: int = const 1
      v2: int = add v0, v1 {pure}
      ret v2
```

## Sibling abstract + default resolution

```babble
def AbsA = interface (def f: fn (x: int): int)
def DefB = interface (def f = fn (x: int): int -> (x * 3))
def Combined = interface extends AbsA, DefB ()
def CombinedImpl = class () implements Combined ()
def cb: Combined = CombinedImpl()
def ca: AbsA = CombinedImpl()
assert(cb.f(7) == 21 and ca.f(7) == 21 and (cb is AbsA) and (cb is DefB))
```

```hir
;; hir -O0,-O1 interface=DefB
interface DefB
  (def f (fn-ref f))
  fn f (x:int) -> int {pure}
    (*:int var(x) 3:int)
```

```mir
;; mir -O2 interface=DefB stage=before,final
interface DefB:
  fn f(v0: int) -> int:
    bb0:
      v1: int = const 3
      v2: int = mul v0, v1 {pure}
      ret v2
```

## Re-abstracted default method hidden along one branch and supplied along a sibling branch

```babble
def Root = interface (def val: fn (): int = fn (): int -> (42))
def ReAbstracted = interface extends Root (def val: fn (): int)
def Kept = interface extends Root ()
def Sub = interface extends ReAbstracted, Kept ()
def Impl = class () implements Sub ()
def r: ReAbstracted = Impl() ; def s: Sub = Impl()
assert(r.val() == 42 and s.val() == 42)
```

```hir
;; hir -O0,-O1 interface=Root
interface Root
  (def val (fn-ref val))
  fn val () -> int {pure}
    42:int
```

```hir
;; hir -O0,-O1 interface=ReAbstracted
interface ReAbstracted
  (def val)
```

```mir
;; mir -O2 interface=ReAbstracted stage=before,final
interface ReAbstracted:
  (no default methods)
```

## Case 7

```babble
def IBox = class (v: int) implements Box of int (
    def get = fn (): int -> (v)
    def xf = fn (x: int): int -> (x + v)
)
def ib: Box of int = IBox(10)
assert(ib.get() == 10 and ib.xf(5) == 15)
```

```hir
;; hir -O0,-O1 class=IBox
class IBox (v:int)
  (def get (fn-ref IBox.get))
  (def xf (fn-ref IBox.xf))
  fn IBox.get () -> int {nothrow unique}
    var(v)
  fn IBox.xf (x:int) -> int {nothrow injective unique}
    (+:int var(x) var(v))
```

```mir
;; mir -O2 class=IBox stage=before,final
class IBox:
  fn IBox.get() -> int:
    bb0:
      v0: int = slot_read v {pure}
      ret v0
  fn IBox.xf(v0: int) -> int:
    bb0:
      v1: int = slot_read v {pure}
      v2: int = add v0, v1 {pure}
      ret v2
```

## Case 8

```babble
def DBox = class (v: double) implements Box of double (
    def get = fn (): double -> (v)
    def xf = fn (x: double): double -> (x + v)
)
def db: Box of double = DBox(0.5)
assert(db.get() == 0.5 and db.xf(4) == 4.5)
```

```hir
;; hir -O0,-O1 class=DBox
class DBox (v:double)
  (def get (fn-ref DBox.get))
  (def xf (fn-ref DBox.xf))
  fn DBox.get () -> double {nothrow unique}
    var(v)
  fn DBox.xf (x:double) -> double {nothrow monotone unique}
    (+:double var(x) var(v))
```

```mir
;; mir -O2 class=DBox stage=before,final
class DBox:
  fn DBox.get() -> double:
    bb0:
      v0: double = slot_read v {pure}
      ret v0
  fn DBox.xf(v0: double) -> double:
    bb0:
      v1: double = slot_read v {pure}
      v2: double = add v0, v1 {pure}
      ret v2
```

## If-expression of ints widened to double and passed to a primitive-double interface receiver

```babble
def TakeDouble = interface (def accept: fn (d: double): double)
def Sink = class () implements TakeDouble (def accept = fn (d: double): double -> (d * 2.0))
def sink: TakeDouble = Sink()
def cond = true
def boxedIntExpr = if cond then (def a = 3 ; a) else (4)
assert(sink.accept(boxedIntExpr) == 6.0)
```

```hir
;; hir -O0,-O1 class=Sink
class Sink
  (def accept (fn-ref Sink.accept))
  fn Sink.accept (d:double) -> double {nothrow monotone unique}
    (*:double var(d) 2.0:double)
```

```hir
;; hir -O0 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def TakeDouble (iface-ref TakeDouble))
  (def Sink (class-ref Sink))
  (def sink (call name(Sink)))
  (def cond true:bool)
  (def boxedIntExpr
    (if name(cond)
      (block
        (def a 3:int)
        var(a))
      (block 4:int)))
  (call name(assert) (==:bool (call-iface name(sink).accept name(boxedIntExpr)) 6.0:double))
```

```hir
;; hir -O1 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def TakeDouble (iface-ref TakeDouble))
  (def Sink (class-ref Sink))
  (def sink (call name(Sink)))
  (def cond true:bool)
  (def boxedIntExpr (if name(cond) 3:int 4:int))
  (call name(assert) (==:bool (call-iface name(sink).accept name(boxedIntExpr)) 6.0:double))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Interface override adding optional defaulted parameters and dispatch through base signature

```babble
def D = class () implements Opt ()
def C = class () implements Opt (
    def apply = fn (x: int, s: int = 25): int -> (x + s)
)
def u1: Unary = D() ; def w1: Opt = D()
def u2: Unary = C() ; def w2: Opt = C()
assert(u1.apply(5) == 15 and w1.apply(5, 2) == 7 and u2.apply(5) == 30 and w2.apply(5, 3) == 8)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def apply (fn-ref C.apply))
  fn C.apply (x:int, s:int=25:int) -> int {nothrow commutative associative unique}
    (+:int var(x) var(s))
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  fn C.apply(v0: int, v1: int) -> int:
    bb0:
      v2: int = add v0, v1 {pure}
      ret v2
```

## Field-holding-function implementation and super default call

```babble
def FieldImpl = class (func: fn (x: int): int) implements Op (def run = func)
def NsImpl = class (d: int) implements Op (def run = fn (x: int): int -> (super.base(x) + d))
def f: Op = FieldImpl(fn (x: int): int -> (x * 4))
def n: Op = NsImpl(7)
assert(f.run(6) == 24 and f.base(6) == 106 and n.run(6) == 113 and (n is NsImpl) and (n is Op))
```

```hir
;; hir -O0,-O1 class=FieldImpl
class FieldImpl (func:obj)
  (def run var(func))
```

```hir
;; hir -O0,-O1 class=NsImpl
class NsImpl (d:int)
  (def run (fn-ref NsImpl.run))
  fn NsImpl.run (x:int) -> int {nothrow unique}
    (+:int (super.base var(x)) var(d))
```

```mir
;; mir -O2 class=NsImpl stage=before,final
class NsImpl:
  fn NsImpl.run(v0: int) -> int:
    bb0:
      v1: any = opaque.SuperCall(base, v0) {impure}
      v2: int = convert.int v1
      v3: int = slot_read d {pure}
      v4: int = add v2, v3 {pure}
      ret v4
```

## Nested lambdas calling super.m() and super(Parent).m() in interface defaults and class methods

```babble
def CI = interface extends P1, P2 (
    def sc: fn (x: int): int = fn (x: int): int -> ((fn (): int -> (super(P1).sc(x) + super(P2).sc(x)))())
)
def CC = class (b: int) implements CI (
    def sc: fn (x: int): int = fn (x: int): int -> ((fn (k: int): int -> (super.sc(x) + k + b))(1))
)
def ci: CI = CC(5)
assert(ci.sc(3) == 49)
```

```hir
;; hir -O0,-O1 interface=CI
interface CI
  (def sc (fn-ref sc))
  fn sc (x:int) -> int {pure}
    (call-fn <anon#0>)
```

```hir
;; hir -O0,-O1 class=CC
class CC (b:int)
  (def sc (fn-ref CC.sc))
  fn CC.sc (x:int) -> int {pure unique}
    (call-fn <anon#0> 1:int)
```

```mir
;; mir -O2 class=CC stage=before,final
class CC:
  fn CC.sc() -> int:
    bb0:
      v0: any = opaque.FunctionRef(<anon>) {read:shared}
      v1: int = const 1
      v2: int = call <anon>(v1) {impure}
      ret v2
```

## Case 13

```babble
def sh = import("shapes.ba")
def s1: sh.Shape = sh.Circle(2.0)
def t: sh.Tag = sh.Tagged("ok")
assert(s1.area() == 12.0 and s1.scale(2.0) == 24.0 and (s1 is sh.Circle))
assert(t.label() == "tag:ok" and (t is sh.Tagged))
```

```hir
;; hir -O0 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def sh (call name(import) "shapes.ba"))
  (def s1 (record-new shapes$ba$C0$Circle 2.0:double))
  (def t (call (export name(sh).Tagged) "ok"))
  (call name(assert) (and (and (==:bool (call-iface name(s1).area) 12.0:double) (==:bool (call-iface name(s1).scale 2.0:double) 24.0:double)) (block (is name(s1) shapes$ba$C0$Circle))))
  (call name(assert) (and (==:bool (call-iface name(t).label) "tag:ok") (block (is name(t) shapes$ba$C1$Tagged))))
```

```hir
;; hir -O1 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def sh (call name(import) "shapes.ba"))
  (def s1 (record-new shapes$ba$C0$Circle 2.0:double))
  (def t (call (export name(sh).Tagged) "ok"))
  (call name(assert) (and (and (==:bool (call-iface name(s1).area) 12.0:double) (==:bool (call-iface name(s1).scale 2.0:double) 24.0:double)) (is name(s1) shapes$ba$C0$Circle)))
  (call name(assert) (and (==:bool (call-iface name(t).label) "tag:ok") (is name(t) shapes$ba$C1$Tagged)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Case 14

```babble
def sh = import("shapes.ba")
def Rect = class (w: double, h: double) implements sh.Shape (
    def area = fn (): double -> (w * h)
    def scale = fn (f: double): double -> (super.scale(f) + 1.0)
)
def s2: sh.Shape = Rect(3.0, 4.0)
assert(s2.scale(2.0) == 25.0 and (s2 is sh.Shape))
```

```hir
;; hir -O0,-O1 class=Rect
class Rect (w:double, h:double)
  (def area (fn-ref Rect.area))
  (def scale (fn-ref Rect.scale))
  fn Rect.area () -> double {nothrow unique}
    (*:double var(w) var(h))
  fn Rect.scale (f:double) -> double {pure unique}
    (+:double (super.scale var(f)) 1.0:double)
```

```mir
;; mir -O2 class=Rect stage=before,final
class Rect:
  fn Rect.area() -> double:
    bb0:
      v0: double = slot_read w {pure}
      v1: double = slot_read h {pure}
      v2: double = mul v0, v1 {pure}
      ret v2
  fn Rect.scale(v0: double) -> double:
    bb0:
      v1: any = opaque.SuperCall(scale, v0) {impure}
      v2: double = convert.double v1
      v3: double = const 1.0
      v4: double = add v2, v3 {pure}
      ret v4
```

## Forward reference on `is` target resolves as a static type (BIP012-D8)

```babble
def check = 1 is Later
def Later = class () ()
assert(not check and (Later() is Later))
```

```hir
;; hir -O0 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def check (is 1:int Later))
  (def Later (class-ref Later))
  (call name(assert) (and (not name(check)) (block (is (call name(Later)) Later))))
```

```hir
;; hir -O1 top-level
top-level:
  (def Box (iface-ref Box))
  (def Unary (iface-ref Unary))
  (def Opt (iface-ref Opt))
  (def Op (iface-ref Op))
  (def Calc (iface-ref Calc))
  (def P1 (iface-ref P1))
  (def P2 (iface-ref P2))
  (def check (is 1:int Later))
  (def Later (class-ref Later))
  (call name(assert) (and (not name(check)) (is (call name(Later)) Later)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Inherited default method with widened/adapted signature in sub-interface (emitInheritedDefaultInvoke)

```babble
def Base = interface (def calc = fn (x: int): int -> (x + 1))
def Other = interface (def calc: fn (x: int): any?)
def Sub = interface extends Other, Base ()
def Impl = class () implements Sub ()
def s: Sub = Impl() ; def b: Base = Impl()
assert(s.calc(4) == 5 and b.calc(4) == 5)
```

```hir
;; hir -O0,-O1 interface=Base
interface Base
  (def calc (fn-ref calc))
  fn calc (x:int) -> int {pure}
    (+:int var(x) 1:int)
```

```hir
;; hir -O0,-O1 interface=Other
interface Other
  (def calc)
```

```mir
;; mir -O2 interface=Other stage=before,final
interface Other:
  (no default methods)
```

## Static as cast to plain Babble class and Babble interface

```babble
def Named = interface (def label: fn (): string)
def Holder = class (def s: string) implements Named (
    def label = fn (): string -> (this.s)
)
def castBox = fn (x: any): Holder -> (x as Holder)
def castNamed = fn (x: any): Named -> (x as Named)
def v: any = Holder("hi")
assert(castBox(v).s == "hi" and castNamed(v).label() == "hi")
```

```hir
;; hir -O0,-O1 fn=castBox
fn castBox (x:obj) -> obj {pure}
  (as var(x) babble|Holder)
```

```hir
;; hir -O0,-O1 fn=castNamed
fn castNamed (x:obj) -> obj {pure}
  (as var(x) babble|Named)
```

```mir
;; mir -O2 fn=castBox stage=before,final
fn castBox(v0: any) -> any:
  bb0:
    v1: any = checkcast v0 <babble|Holder>
    ret v1
```

```mir
;; mir -O2 fn=castNamed stage=before,final
fn castNamed(v0: any) -> any:
  bb0:
    v1: any = checkcast v0 <babble|Named>
    ret v1
```

## Multi-hop cross-module interface extends propagation

```babble
;; babble module=pkg/lib.ba
export def A = interface (def x: fn (): int)
export def B = interface extends A (def y = fn (): int -> (this.x()))
```

```babble
;; babble module=pkg/mid.ba
def lib = import("lib.ba")
export def C = interface extends lib.B (def z: fn (): int)
```

```babble
def mid = import("pkg/mid.ba")
def Impl = class () implements mid.C (
    def x = fn (): int -> (2)
    def z = fn (): int -> (3)
)
def c: mid.C = Impl()
assert(c.y() + c.z() == 5 and (c is mid.C))
```

```hir
;; hir -O0,-O1 class=Impl
class Impl
  (def x (fn-ref Impl.x))
  (def z (fn-ref Impl.z))
  fn Impl.x () -> int {nothrow unique}
    2:int
  fn Impl.z () -> int {nothrow unique}
    3:int
```

```mir
;; mir -O2 class=Impl stage=before,final
class Impl:
  fn Impl.x() -> int:
    bb0:
      v0: int = const 2
      ret v0
  fn Impl.z() -> int:
    bb0:
      v0: int = const 3
      ret v0
```
