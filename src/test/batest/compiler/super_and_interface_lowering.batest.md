# Super Dispatch, Interface Bridges, and Safe-Navigation Lowering

References : BIP001, BIP002, BIP021 ; targets CodeGenerator, InterfaceGen, HirBuilder, HirEmitter

```babble
;; babble preamble
def Vec = class (def n : int) (
    def neg = fn () : Vec -> ( Vec(-this.n) )
    def plus = fn (o : any) : Vec -> ( Vec(this.n + (o as int)) )
    def minus = fn (o : any) : Vec -> ( Vec(this.n - (o as int)) )
    def times = fn (o : any) : Vec -> ( Vec(this.n * (o as int)) )
    def div = fn (o : any) : Vec -> ( Vec(int(this.n / (o as int))) )
)
```


## 2-level interface inheritance super(G2) and ancestor is/as checks

```babble
def G0 = interface ( def deep = fn () : string -> ( "g0" ) )
def G1 = interface extends G0 ()
def G2 = interface extends G1 ()
def GG = class () implements G2 ( def deep = fn () : string -> ( super(G2).deep() + ":gg" ) )
def dyn = fn (v : any) : any -> ( v )
def gVal : G0 = dyn(GG()) as G0
assert(gVal.deep() == "g0:gg" and (GG() is G0) and (GG() is G1))
```

```hir
;; hir -O0,-O1 class=GG
class GG
  (def deep (fn-ref GG.deep))
  fn GG.deep () -> obj {nothrow unique}
    (+:obj (super.deep) ":gg")
```

```mir
;; mir -O2 class=GG stage=before,final
class GG:
  fn GG.deep() -> any:
    bb0:
      v0: any = opaque.SuperCall() {impure}
      v1: any = const ":gg"
      v2: any = strcat v0, v1 {pure}
      ret v2
```

## Sub-interface extending Java IntPredicate with default method and is check

```babble
def SubP = interface extends java.util.function.IntPredicate (
    def test = fn (x : int) : bool -> ( x > 0 )
)
def sp = (class () implements SubP ())()
assert(sp is java.util.function.IntPredicate and sp.test(3))
```

```hir
;; hir -O0,-O1 interface=SubP
interface SubP
  (def test (fn-ref test))
  fn test (x:int) -> bool {pure}
    (>:bool var(x) 0:int)
```

```mir
;; mir -O2 interface=SubP stage=before,final
interface SubP:
  fn test(v0: int) -> bool:
    bb0:
      v1: int = const 0
      v2: bool = gt v0, v1 {pure}
      ret v2
```

## InterfaceGen default method calling own default with int->double widening

```babble
def CalcIface = interface (
    def base = fn () : int -> ( 10 )
    def plusBase = fn (k : int) : double -> ( def mul = 2 ; this.base() * mul + k )
)
def ci : CalcIface = (class () implements CalcIface ())()
assert(ci.plusBase(5) == 25.0)
```

```hir
;; hir -O0 fn=plusBase
fn plusBase (k:int) -> double {pure}
  (def mul 2:int)
  (+:int (*:int (call-iface this.base) var(mul)) var(k))
```

```hir
;; hir -O1 fn=plusBase
fn plusBase (k:int) -> double {pure}
  (+:int (*:int (call-iface this.base) 2:int) var(k))
```

```mir
;; mir -O2 fn=plusBase stage=before,final
fn plusBase(v0: int) -> double:
  bb0:
    v1: any = slot_read this {pure}
    v2: int = call_iface v1.base() {pure,throws}
    v3: int = const 2
    v4: int = mul v2, v3 {pure}
    v5: int = add v4, v0 {pure}
    v6: double = convert.double v5
    ret v6
```

## InterfaceGen signature adaptation bridge inherited through intermediate interface

```babble
def IBase = interface ( def calc = fn (x : int) : any -> ( x * 0 ) )
def IMid = interface extends IBase ( def calc = fn (x : any) : int -> ( 5 ) )
def ILeaf = interface extends IMid ()
def C1 = class () implements ILeaf ()
def c1 : IBase = C1()
assert(c1.calc(4) == 5)
assert(C1().calc(4) == 5)
```

```hir
;; hir -O0 interface=IBase
interface IBase
  (def calc (fn-ref calc))
  fn calc (x:int) -> obj {pure}
    (*:int var(x) 0:int)
```

```hir
;; hir -O1 interface=IBase
interface IBase
  (def calc (fn-ref calc))
  fn calc (x:int) -> obj {pure}
    0:int
```

```mir
;; mir -O2 interface=IBase stage=before
interface IBase:
  fn calc(v0: int) -> any:
    bb0:
      v1: long = const 0L
      v2: any = convert.any v1
      ret v2
```

```mir
;; mir -O2 interface=IBase
interface IBase:
  fn calc(v0: int) -> any:
    bb0:
      v1: any = const 0L
      ret v1
```

## super call inside Babble interface default method, inside nested lambda in class, and with positional assig...

```babble
def I1 = interface ( def calc = fn (x : int) : int -> ( x + 10 ) )
def I2 = interface extends I1 ( def calc = fn (x : int) : int -> ( super.calc(x) + 2 ) )
def C2 = class () implements I2 (
    def run = fn (x : int) : int -> (
        def inner = fn () : int -> ( super.calc(x) + 1 )
        inner() + super.calc(x = x + 1)
    )
)
assert(C2().run(5) == 36)
```

```hir
;; hir -O0,-O1 fn=C2.run
fn C2.run (x:int) -> int {nothrow unique}
  (def inner (fn-ref inner))
  (+:int (call-fn inner) (super.calc (set x (+:int var(x) 1:int))))
```

```mir
;; mir -O2 fn=C2.run stage=before,final
fn C2.run() -> int:
  bb0:
    v0: any = opaque.FunctionRef() {read:shared}
    v1: int = call inner() {impure}
    v2: int = slot_read x {read:shared,throws}
    v3: int = const 1
    v4: int = add v2, v3 {pure}
    slot_write x, v4 {write:shared,throws}
    v5: any = opaque.SuperCall(v4) {impure}
    v6: int = convert.int v5
    v7: int = add v1, v6 {pure}
    ret v7
```

## Safe-navigation call on nullable Babble receiver with super default call through intermediate interface

```babble
def I1 = interface ( def greet = fn (x : int) : int -> ( x + 10 ) )
def I2 = interface extends I1 ()
def C = class (def v : int) implements I2 ( def greet = fn (x : int) : int -> ( super.greet(x) + this.v ) )
def opt : C? = C(5) ; def nil : C? = null
assert(opt?.greet(2) == 17 and nil?.greet(2) == null)
```

```hir
;; hir -O0,-O1 class=C
class C (v:int)
  (def greet (fn-ref C.greet))
  fn C.greet (x:int) -> int {nothrow unique}
    (+:int (super.greet var(x)) (get-this-field v))
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  fn C.greet(v0: int) -> int:
    bb0:
      v1: any = opaque.SuperCall(v0) {impure}
      v2: int = convert.int v1
      v3: any = slot_read this {pure}
      v4: int = load_field v3.v {read:shared,throws}
      v5: int = add v2, v4 {pure}
      ret v5
```

## Safe-navigation call on nullable Java receiver

```babble
def s1 : java.lang.StringBuilder? = java.lang.StringBuilder("abc")
def s2 : java.lang.StringBuilder? = null
assert(s1?.length() == 3 and s2?.length() == null)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def Vec (class-ref Vec))
  (def s1 (jnew StringBuilder "abc"))
  (def s2 null)
  (call name(assert) (and (==:bool (safe-call name(s1)?.length) 3:int) (==:bool (safe-call name(s2)?.length) null)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## emitReturnToJava double->void POP2 on Runnable and Object->double on DoubleSupplier

```babble
def dyn = fn (v : any) : any -> ( v )
def DS = class () implements java.util.function.DoubleSupplier (
    def getAsDouble = fn () : any -> ( dyn(3.5) )
)
def f2 : java.util.function.DoubleSupplier = DS()
def r : java.lang.Runnable = fn () -> ( 1.5 + 2.5 )
r.run()
assert(f2.getAsDouble() == 3.5)
```

```hir
;; hir -O0 fn=r
fn r () -> double {nothrow unique}
  (+:double 1.5:double 2.5:double)
```

```hir
;; hir -O1 fn=r
fn r () -> double {nothrow unique}
  4.0:double
```

```hir
;; hir -O0 fn=DS.getAsDouble
fn DS.getAsDouble () -> obj {nothrow unique}
  (call-fn dyn 3.5:double)
```

```hir
;; hir -O1 fn=DS.getAsDouble
fn DS.getAsDouble () -> obj {nothrow unique}
  (block
    (def v 3.5:double)
    var(v))
```

```mir
;; mir -O2 fn=DS.getAsDouble stage=before
fn DS.getAsDouble() -> any:
  bb0:
    v0: double = const 3.5
    v1: any = convert.any v0
    ret v1
```

```mir
;; mir -O2 fn=DS.getAsDouble
fn DS.getAsDouble() -> any:
  bb0:
    v0: any = const 3.5
    ret v0
```

## Java primitive bridge args : DoublePredicate and IntPredicate

```babble
def DP = class () implements java.util.function.DoublePredicate ( def test = fn (d : double) : bool -> ( d > 0.0 ) )
def IP = class () implements java.util.function.IntPredicate ( def test = fn (i : int) : bool -> ( i > 0 ) )
def dp : java.util.function.DoublePredicate = DP()
def ip : java.util.function.IntPredicate = IP()
assert(dp.test(1.5) and ip.test(3))
```

```hir
;; hir -O0,-O1 class=DP
class DP
  (def test (fn-ref DP.test))
  fn DP.test (d:double) -> bool {nothrow unique}
    (>:bool var(d) 0.0:double)
```

```mir
;; mir -O2 class=DP stage=before,final
class DP:
  fn DP.test(v0: double) -> bool:
    bb0:
      v1: double = const 0.0
      v2: bool = gt v0, v1 {pure}
      ret v2
```

## Java primitive bridge args : IntToDoubleFunction

```babble
def I2D = class () implements java.util.function.IntToDoubleFunction (
    def applyAsDouble = fn (i : int) : double -> ( i + 0.5 )
)
def i2d : java.util.function.IntToDoubleFunction = I2D()
assert(i2d.applyAsDouble(2) == 2.5)
```

```hir
;; hir -O0,-O1 class=I2D
class I2D
  (def applyAsDouble (fn-ref I2D.applyAsDouble))
  fn I2D.applyAsDouble (i:int) -> double {nothrow monotone unique}
    (+:double var(i) 0.5:double)
```

```mir
;; mir -O2 class=I2D stage=before,final
class I2D:
  fn I2D.applyAsDouble(v0: int) -> double:
    bb0:
      v1: double = convert.double v0
      v2: double = const 0.5
      v3: double = add v1, v2 {pure}
      ret v3
```

## super call to interface default with optional primitive param (class method, sub-interface, nested lambda)

```babble
def IOpt = interface ( def add = fn (a: int, b: int = 10): int -> ( a + b ) )
def ISub = interface extends IOpt ( def add = fn (a: int, b: int = 20): int -> ( super.add(a) + b ) )
def C = class () implements ISub (
    def dir = fn (x: int): int -> ( super.add(x) )
    def lam = fn (x: int): int -> ( (fn (): int -> ( super.add(x) ))() )
)
assert(C().dir(5) == 35 and C().lam(2) == 32)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def dir (fn-ref C.dir))
  (def lam (fn-ref C.lam))
  fn C.dir (x:int) -> int {nothrow unique}
    (super.add var(x))
  fn C.lam (x:int) -> int {nothrow unique}
    (call-fn <anon#0>)
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  fn C.dir(v0: int) -> int:
    bb0:
      v1: any = opaque.SuperCall(v0) {impure}
      v2: int = convert.int v1
      ret v2
  fn C.lam() -> int:
    bb0:
      v0: any = opaque.FunctionRef() {read:shared}
      v1: int = call <anon>() {impure}
      ret v1
```

## super call to overloaded interface default with union-typed positional and named arguments

```babble
def IOv = interface (
    def calc = overload ( fn (x: int): int -> ( x + 10 ), fn (x: string): int -> ( x.length() ) )
)
def C = class () implements IOv (
    def pos = fn (u: int | string): int -> ( super.calc(u) )
    def nam = fn (u: int | string): int -> ( super.calc(x: u) )
)
assert(C().pos(5) == 15 and C().pos("abcd") == 4 and C().nam(3) == 13 and C().nam("xy") == 2)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def pos (fn-ref C.pos))
  (def nam (fn-ref C.nam))
  fn C.pos (u:obj) -> int {nothrow unique}
    (super.calc var(u))
  fn C.nam (u:obj) -> int {nothrow unique}
    (super.calc x=var(u))
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  fn C.pos(v0: any) -> int:
    bb0:
      v1: any = opaque.SuperCall(v0) {impure}
      v2: int = convert.int v1
      ret v2
  fn C.nam(v0: any) -> int:
    bb0:
      v1: any = opaque.SuperCall(v0) {impure}
      v2: int = convert.int v1
      ret v2
```

## Diamond Java interfaces (Closeable + AutoCloseable) and non-top-level block class expressions

```babble
def Res = class () implements java.io.Closeable, java.lang.AutoCloseable ( def close = fn () -> () )
def r = Res() ; r.close()
def C1 = ( def tag = 1 ; class (def v : int) ( def get = fn () : int -> ( this.v + tag ) ) )
def r2 = ( def C2 = class (def v : int) () ; C2(9).v )
assert(C1(4).get() == 5 and r2 == 9)
```

```hir
;; hir -O0,-O1 class=Res
class Res
  (def close (fn-ref Res.close))
  fn Res.close () -> obj {nothrow unique}
```

```mir
;; mir -O2 class=Res stage=before,final
class Res:
  fn Res.close() -> any:
    bb0:
      v0: any = const null
      ret v0
```
