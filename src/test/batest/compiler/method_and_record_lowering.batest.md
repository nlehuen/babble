# Method Dispatch, Recurse, and Record Fast-Class Lowering

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


## this.method direct spec, spread with missing optional arg, and named arg

```babble
def C = class () (
    def specM = fn (a : int) : int -> ( a + 1 )
    def optM = fn (a : int, b : int = 10) : int -> ( a + b )
    def run = fn () -> (
        assert(this.specM(2) == 3 and this.optM(2) == 12 and this.optM(a: 3) == 13)
    )
)
C().run()
```

```hir
;; hir -O0 fn=C.run
fn C.run () -> bool {pure unique}
  (call name(assert) (and (and (==:bool (call-method this.specM 2:int) 3:int) (==:bool (call-method this.optM 2:int) 12:int)) (==:bool (call-method this.optM a=3:int) 13:int)))
```

```hir
;; hir -O1 fn=C.run
fn C.run () -> bool {pure unique}
  (call name(assert) (and (==:bool (call-method this.optM 2:int) 12:int) (==:bool (call-method this.optM a=3:int) 13:int)))
```

```hir
;; hir -O2 fn=C.run
fn C.run () -> bool {pure unique}
  (call name(assert) (and (==:bool (call-method this.optM 2:int) 12:int) (==:bool (call-method this.optM a=3:int) 13:int)))
```

## this.field assign, compound assign, and spread-to-double field assign

```babble
def C = class (var d : double, var label : string, var n : int) (
    def optM = fn (a : int, b : int = 10) : int -> ( a + b )
    def run = fn mut () -> (
        this.label = "abc" ; this.label += "!" ; this.n += 5
        this.d = this.optM(1)
        assert(this.d == 11.0 and this.label == "abc!" and this.n == 15)
    )
)
C(0.0, "init", 10).run()
```

```hir
;; hir -O0,-O1 fn=C.run
fn C.run () -> bool {mut unique}
  (set-this-field label "abc")
  (set-this-field label += "!")
  (set-this-field n += 5:int)
  (set-this-field d (widen[double] (call-method this.optM 1:int)))
  (call name(assert) (and (and (==:bool (get-this-field d) 11.0:double) (==:bool (get-this-field label) "abc!")) (==:bool (get-this-field n) 15:int)))
```

```hir
;; hir -O2 fn=C.run
fn C.run () -> bool {mut unique}
  (set-this-field label "abc")
  (set-this-field label += "!")
  (set-this-field n += 5:int)
  (block
    (def $cse0 (widen[double] (call-method this.optM 1:int)))
    (set-this-field d var($cse0))
    (call name(assert) (and (and (==:bool var($cse0) 11.0:double) (==:bool (get-this-field label) "abc!")) (==:bool (get-this-field n) 15:int))))
```

## recurse with omitted optional arg inside class method and top-level function, plus enclosing captured cell

```babble
var enclosing = 10
def C = class () (
    def recOpt = fn (n : int, step : int = 1) : int -> ( if n <= 0 then ( 0 ) else ( step + recurse(n - 1) ) )
    def bump = fn io () : int -> ( enclosing += 1 ; this.recOpt(3) )
)
def sumDown = fn (n : int, step : int = 2) : int -> ( if n <= 0 then ( 0 ) else ( step + recurse(n - 1) ) )
assert(C().bump() == 3 and enclosing == 11 and sumDown(3) == 6)
```

```hir
;; hir -O0 fn=sumDown
fn sumDown (n:int, step:int=2:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) (block 0:int) (block (+:int var(step) (self-recurse sumDown (-:int var(n) 1:int)))))
```

```hir
;; hir -O1 fn=sumDown
fn sumDown (n:int, step:int=2:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int var(step) (self-recurse sumDown (-:int var(n) 1:int))))
```

```hir
;; hir -O2 fn=sumDown
fn sumDown (n:int, step:int=2:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int var(step) (self-recurse sumDown (-:int var(n) 1:int))))
```

## Captured outer and class-constructor double cells inside enclosing function (DoubleCell.of)

```babble
def make = fn (start : double) -> (
    var outer = start
    def C = class (p : double) (
        def bump = fn io () : double -> ( outer += 1.0 ; outer + p )
    )
    C(2.0)
)
def inst = make(10.0)
assert(inst.bump() == 13.0)
assert(inst.bump() == 14.0)
```

```hir
;; hir -O0,-O1 fn=C.bump
fn C.bump () -> double {io}
  (set outer += 1.0:double)
  (+:double var(outer) var(p))
```

```hir
;; hir -O2 fn=C.bump
fn C.bump () -> double {io}
  (set outer += 1.0:double)
  (+:double var(outer) var(p))
```

## Static block this assign, compound assign, and this reference

```babble
def C = class () (
    def getV = fn () : int -> ( 7 )
) static (
    private var x = 1
    def bump = fn io () : int -> ( this.x = 2 ; this.x += 3 ; this.x )
    def me = fn () -> ( this )
)
assert(C().getV() == 7 and C.bump() == 5 and C.me() == C)
```

```hir
;; hir -O0,-O1 fn=bump
fn bump () -> int {io}
  (set-this-field x 2:int)
  (set-this-field x += 3:int)
  (get-this-field x)
```

```hir
;; hir -O2 fn=bump
fn bump () -> int {io}
  (set-this-field x 2:int)
  (set-this-field x += 3:int)
  (get-this-field x)
```

## Paren-wrapped class expression with method recurse and interface default

```babble
def Box = ( class (def v : int) (
    def fact = fn (n : int) : int -> ( if n <= 1 then ( 1 ) else ( n * recurse(n - 1) ) )
) )
def IWithConst = interface ( def get = fn () : int -> ( 3 ) )
def Impl = class () implements IWithConst ()
assert(Box(5).fact(4) == 24)
assert(Impl().get() == 3)
```

```hir
;; hir -O0,-O1 interface=IWithConst
interface IWithConst
  (def get (fn-ref get))
  fn get () -> int {pure}
    3:int
```

```hir
;; hir -O2 interface=IWithConst
interface IWithConst
  (def get (fn-ref get))
  fn get () -> int {pure}
    3:int
```

## BabbleInstance operator methods (neg, plus, minus, times, div, compound +=) and folded unary minus

```babble
def v = Vec(10) ; var c = Vec(5) ; c += 3
assert((-v).n == -10 and -(1 + 1) == -2 and (v + 2).n == 12 and (v - 3).n == 7)
assert((v * 4).n == 40 and (v / 2).n == 5 and c.n == 8)
```

```hir
;; hir -O0 top-level
top-level:
  (def Vec (class-ref Vec))
  (def v (call name(Vec) 10:int))
  (def c (call name(Vec) 5:int))
  (set-name c += 3:int)
  (call name(assert) (and (and (and (==:bool (get-this-field n) -10:int) (==:bool (neg (block (+:int 1:int 1:int))) -2:int)) (==:bool (get-this-field n) 12:int)) (==:bool (get-this-field n) 7:int)))
  (call name(assert) (and (and (==:bool (get-this-field n) 40:int) (==:bool (get-this-field n) 5:int)) (==:bool (get-this-field n) 8:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def Vec (class-ref Vec))
  (def v (call name(Vec) 10:int))
  (def c (call name(Vec) 5:int))
  (set-name c += 3:int)
  (call name(assert) (and (and (and (==:bool (get-this-field n) -10:int) true:bool) (==:bool (get-this-field n) 12:int)) (==:bool (get-this-field n) 7:int)))
  (call name(assert) (and (and (==:bool (get-this-field n) 40:int) (==:bool (get-this-field n) 5:int)) (==:bool (get-this-field n) 8:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def Vec (class-ref Vec))
  (block
    (def $cse0 name(Vec))
    (def v (call var($cse0) 10:int))
    (def c (call var($cse0) 5:int))
    (set-name c += 3:int)
    (def $cse1 name(v))
    (def $cse2 name(assert))
    (def $cse3 (get-this-field n))
    (call var($cse2) (and (and (and (==:bool (get-this-field n) -10:int) true:bool) (==:bool (get-this-field n) 12:int)) (==:bool (get-this-field n) 7:int)))
    (call var($cse2) (and (and (==:bool (get-this-field n) 40:int) (==:bool (get-this-field n) 5:int)) (==:bool (get-this-field n) 8:int))))
```

## Non-tail recurse in reference-only function and class method

```babble
def rep = fn (s: string): string -> ( if s.length() >= 4 then ( s ) else ( "a" + recurse(s + "b") ) )
def Joiner = class (def p: string) (
    def build = fn (s: string): string -> ( if s.length() >= 3 then ( s ) else ( this.p + recurse(s + "x") ) )
)
assert(rep("x") == "aaaxbbb" and Joiner("!").build("a") == "!!axx")
```

```hir
;; hir -O0 fn=rep
fn rep (s:obj) -> obj {nothrow}
  (if (>=:bool (jcall String.length var(s)) 4:int) (block var(s)) (block (+:obj "a" (self-recurse rep (+:obj var(s) "b")))))
```

```hir
;; hir -O1 fn=rep
fn rep (s:obj) -> obj {nothrow}
  (if (>=:bool (jcall String.length var(s)) 4:int) var(s) (+:obj "a" (self-recurse rep (+:obj var(s) "b"))))
```

```hir
;; hir -O2 fn=rep
fn rep (s:obj) -> obj {nothrow}
  (if (>=:bool (jcall String.length var(s)) 4:int) var(s) (+:obj "a" (self-recurse rep (+:obj var(s) "b"))))
```

## Non-tail recurse with >8 primitive parameters

```babble
def r9 = fn (n:int, a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int): int -> (
    if n <= 0 then ( 0 ) else ( 1 + recurse(n - 1, a, b, c, d, e, f, g, h) )
)
assert(r9(3, 0, 0, 0, 0, 0, 0, 0, 0) == 3)
```

```hir
;; hir -O0 fn=r9
fn r9 (n:int, a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) (block 0:int) (block (+:int 1:int (call recurse (-:int var(n) 1:int) var(a) var(b) var(c) var(d) var(e) var(f) var(g) var(h)))))
```

```hir
;; hir -O1 fn=r9
fn r9 (n:int, a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int 1:int (call recurse (-:int var(n) 1:int) var(a) var(b) var(c) var(d) var(e) var(f) var(g) var(h))))
```

```hir
;; hir -O2 fn=r9
fn r9 (n:int, a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int 1:int (call recurse (-:int var(n) 1:int) var(a) var(b) var(c) var(d) var(e) var(f) var(g) var(h))))
```

## Direct this.method call with >8 default params (0 args, positional, and reordered named args)

```babble
def Big = class () (
    def s9 = fn (a:int=1, b:int=1, c:int=1, d:int=1, e:int=1, f:int=1, g:int=1, h:int=1, i:int=1) : int -> (
        a + b + c + d + e + f + g + h + i
    )
    def run = fn () : int -> ( this.s9() + this.s9(2, 2, 2, 2, 2, 2, 2, 2, 2) + this.s9(i: 10) )
)
assert(Big().run() == 45)
```

```hir
;; hir -O0 fn=Big.run
fn Big.run () -> int {nothrow unique}
  (+:int (+:int (call-method this.s9) (call-method this.s9 2:int 2:int 2:int 2:int 2:int 2:int 2:int 2:int 2:int)) (call-method this.s9 i=10:int))
```

```hir
;; hir -O1 fn=Big.run
fn Big.run () -> int {nothrow unique}
  (+:int (+:int (call-method this.s9) 18:int) (call-method this.s9 i=10:int))
```

```hir
;; hir -O2 fn=Big.run
fn Big.run () -> int {nothrow unique}
  (+:int (+:int (call-method this.s9) 18:int) (call-method this.s9 i=10:int))
```

## this.field callable, inherited interface default via this, and overloaded this operator

```babble
def IG = interface ( def greet = fn (): int -> ( 4 ) )
def C = class (def n: int, def cb: fn (int): int) implements IG (
    def plus = overload ( fn (k: int): int -> ( this.n + k ), fn (s: string): int -> ( s.length() ) )
    def run = fn (): int -> ( this.cb(3) + this.greet() + (this + 2) )
)
assert(C(5, fn (x: int): int -> ( x * 2 )).run() == 17)
```

```hir
;; hir -O0 fn=C.run
fn C.run () -> int {pure unique}
  (+:int (+:int (call (get-this-field cb) 3:int) (call (get-this-field greet))) (block (call-method this.plus 2:int)))
```

```hir
;; hir -O1 fn=C.run
fn C.run () -> int {pure unique}
  (+:int (+:int (call (get-this-field cb) 3:int) (call (get-this-field greet))) (+:int (get-this-field n) 2:int))
```

```hir
;; hir -O2 fn=C.run
fn C.run () -> int {pure unique}
  (+:int (+:int (call (get-this-field cb) 3:int) (call (get-this-field greet))) (+:int (get-this-field n) 2:int))
```

## Object literal this.method dispatch

```babble
def obj = object ( def m = fn (): int -> ( 7 ) ; def go = fn (): int -> ( this.m() ) )
assert(obj.go() == 7)
```

```hir
;; hir -O0 top-level
top-level:
  (def Vec (class-ref Vec))
  (def obj (class-ref object))
  (call name(assert) (==:bool (call-method name(obj).go) 7:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def Vec (class-ref Vec))
  (def obj (class-ref object))
  (call name(assert) true:bool)
```

```hir
;; hir -O2 top-level
top-level:
  (def Vec (class-ref Vec))
  (def obj (class-ref object))
  (call name(assert) true:bool)
```

## Record fast-class disqualifiers : >8 fields, captured outer cell, and Java SAM param

```babble
def R9 = record (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) ()
def k = 3 ; def CellRec = record (x: int) ( def get = fn (): int -> ( this.x + k ) )
def SamRec = record (r: java.lang.Runnable) ()
var hits = 0 ; def sr = SamRec(fn io () -> ( hits += 1 )) ; sr.r.run()
assert(R9(1, 2, 3, 4, 5, 6, 7, 8, 9).i == 9 and CellRec(4).get() == 7 and hits == 1)
```

```hir
;; hir -O0,-O1 record=R9
record R9 (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int)
```

```hir
;; hir -O0,-O1 record=CellRec
record CellRec (x:int)
  (def get (fn-ref CellRec.get))
  fn CellRec.get () -> int {nothrow unique}
    (+:int (get-this-field x) name(k))
```

```hir
;; hir -O2 record=CellRec
record CellRec (x:int)
  (def get (fn-ref CellRec.get))
  fn CellRec.get () -> int {nothrow unique}
    (+:int (get-this-field x) name(k))
```

## Record fast-class disqualifiers : body assert, body field, and nested overload

```babble
def ChkR = record (x: int) ( init ( assert(this.x >= 0) ) )
def FldR = record (x: int) ( def dbl: int = this.x * 2 )
def OvR = record (x: int) (
    def f = overload ( fn (n: int): int -> ( this.x + n ), overload ( fn (b: bool): int -> ( 1 ), fn (s: string): int -> ( s.length() ) ) )
)
assert(ChkR(5).x == 5 and FldR(3).dbl == 6 and OvR(4).f(2) == 6 and OvR(4).f("hi") == 2)
```

```hir
;; hir -O0,-O1 record=ChkR
record ChkR (x:int)
  init (x:int):
    (call name(assert) (>=:bool (get-this-field x) 0:int))
```

```hir
;; hir -O0,-O1 record=OvR
record OvR (x:int)
  (def f (overload (fn-ref OvR.f#1) (overload (fn-ref OvR.f#2) (fn-ref OvR.f#3))))
  fn OvR.f (n:int) -> int {nothrow unique}
    (+:int (get-this-field x) var(n))
  fn OvR.f (b:bool) -> int {nothrow unique}
    1:int
  fn OvR.f (s:obj) -> int {nothrow unique}
    (jcall String.length var(s))
```

```hir
;; hir -O2 record=OvR
record OvR (x:int)
  (def f (overload (fn-ref OvR.f#1) (overload (fn-ref OvR.f#2) (fn-ref OvR.f#3))))
  fn OvR.f (n:int) -> int {nothrow unique}
    (+:int (get-this-field x) var(n))
  fn OvR.f (b:bool) -> int {nothrow unique}
    1:int
  fn OvR.f (s:obj) -> int {nothrow unique}
    (jcall String.length var(s))
```

## Class constructor non-method and non-FunctionRef overload sets (isAllMethodRefsOf)

```babble
def extI = fn (x: int): int -> ( x + 1 )
def extS = fn (s: string): int -> ( s.length() )
def C = class () (
    def r1 : int = ( def loc = overload ( extI, extS ) ; loc(3) + loc("abc") )
    def r2 : int = ( def loc = overload ( fn (x : int) : int -> ( x * 2 ), fn (s : string) : int -> ( s.length() ) ) ; loc(5) + loc("hi") )
)
def c = C()
assert(c.r1 == 7 and c.r2 == 12)
```

```hir
;; hir -O0 class=C
class C
  (def r1
    (block
      (def loc#1 (overload name(extI) name(extS)))
      (+:int (call-fn extI 3:int) (call-fn extS "abc"))))
  (def r2
    (block
      (def loc#2 (overload (fn-ref loc#1) (fn-ref loc#2)))
      (+:int (call-fn loc#1 5:int) (call-fn loc#2 "hi"))))
```

```hir
;; hir -O1 class=C
class C
  (def r1 7:int)
  (def r2 12:int)
```

```hir
;; hir -O2 class=C
class C
  (def r1 7:int)
  (def r2 12:int)
```
