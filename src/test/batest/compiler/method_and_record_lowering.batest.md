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

```mir
;; mir -O2 fn=C.run stage=before
fn C.run() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: any = slot_read this {pure}
    v2: int = const 2
    v3: int = call_method v1.optM(v2) {pure}
    v4: int = const 12
    v5: bool = eq v3, v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb2(v6)
  bb1:
    v7: any = slot_read this {pure}
    v8: int = const 3
    v9: int = call_method v7.optM(v8) {pure}
    v10: int = const 13
    v11: bool = eq v9, v10 {pure}
    br bb2(v11)
  bb2(v12: bool):
    v13: bool = opaque.DynamicCall(v0, v12) {impure}
    ret v13
```

```mir
;; mir -O2 fn=C.run
fn C.run() -> bool:
  bb0:
    v0: any = opaque.NameRead() {impure}
    v1: any = slot_read this {pure}
    v2: int = const 2
    v3: int = call_method v1.optM(v2) {pure}
    v4: int = const 12
    v5: bool = eq v3, v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb2(v6)
  bb1:
    v7: int = const 3
    v8: int = call_method v1.optM(v7) {pure}
    v9: int = const 13
    v10: bool = eq v8, v9 {pure}
    br bb2(v10)
  bb2(v11: bool):
    v12: bool = opaque.DynamicCall(v0, v11) {impure}
    ret v12
```

## this.field assign, compound assign, and spread-to-double field assign

```babble
def C = class (def d : double, def label : string, def n : int) (
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
  (set-this-field d (call-method this.optM 1:int))
  (call name(assert) (and (and (==:bool (get-this-field d) 11.0:double) (==:bool (get-this-field label) "abc!")) (==:bool (get-this-field n) 15:int)))
```

```mir
;; mir -O2 fn=C.run stage=before
fn C.run() -> bool:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = const "abc"
    store_field v0.label, v1 {write:shared,throws}
    v2: any = slot_read this {pure}
    v3: any = load_field v2.label {read:shared,throws}
    v4: any = const "!"
    v5: any = add v3, v4 {impure}
    store_field v2.label, v5 {write:shared,throws}
    v6: any = slot_read this {pure}
    v7: int = load_field v6.n {read:shared,throws}
    v8: int = const 5
    v9: int = add v7, v8 {pure}
    store_field v6.n, v9 {write:shared,throws}
    v10: any = slot_read this {pure}
    v11: any = slot_read this {pure}
    v12: int = const 1
    v13: int = call_method v11.optM(v12) {pure}
    v14: double = convert.double v13
    store_field v10.d, v14 {write:shared,throws}
    v15: any = opaque.NameRead() {impure}
    v16: any = slot_read this {pure}
    v17: double = load_field v16.d {read:shared,throws}
    v18: double = const 11.0
    v19: bool = eq v17, v18 {pure}
    v20: bool = const false
    br_if v19, bb1, bb2(v20)
  bb1:
    v21: any = slot_read this {pure}
    v22: any = load_field v21.label {read:shared,throws}
    v23: any = const "abc!"
    v24: any = eq v22, v23 {pure}
    v25: bool = not v24 {pure}
    v26: bool = not v25 {pure}
    br bb2(v26)
  bb2(v27: bool):
    v28: bool = const false
    br_if v27, bb3, bb4(v28)
  bb3:
    v29: any = slot_read this {pure}
    v30: int = load_field v29.n {read:shared,throws}
    v31: int = const 15
    v32: bool = eq v30, v31 {pure}
    br bb4(v32)
  bb4(v33: bool):
    v34: bool = opaque.DynamicCall(v15, v33) {impure}
    ret v34
```

```mir
;; mir -O2 fn=C.run
fn C.run() -> bool:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = const "abc"
    store_field v0.label, v1 {write:shared,throws}
    v2: any = const "!"
    v3: any = add v1, v2 {impure}
    store_field v0.label, v3 {write:shared,throws}
    v4: int = load_field v0.n {read:shared,throws}
    v5: int = const 5
    v6: int = add v4, v5 {pure}
    store_field v0.n, v6 {write:shared,throws}
    v7: int = const 1
    v8: int = call_method v0.optM(v7) {pure}
    v9: double = convert.double v8
    store_field v0.d, v9 {write:shared,throws}
    v10: any = opaque.NameRead() {impure}
    v11: double = load_field v0.d {read:shared,throws}
    v12: double = const 11.0
    v13: bool = eq v11, v12 {pure}
    v14: bool = const false
    br_if v13, bb1, bb2(v14)
  bb1:
    v15: any = load_field v0.label {read:shared,throws}
    v16: any = const "abc!"
    v17: any = eq v15, v16 {pure}
    v18: bool = not v17 {pure}
    v19: bool = not v18 {pure}
    br bb2(v19)
  bb2(v20: bool):
    br_if v20, bb3, bb4(v14)
  bb3:
    v21: int = load_field v0.n {read:shared,throws}
    v22: int = const 15
    v23: bool = eq v21, v22 {pure}
    br bb4(v23)
  bb4(v24: bool):
    v25: bool = opaque.DynamicCall(v10, v24) {impure}
    ret v25
```

## recurse with omitted optional arg inside class method and top-level function, plus enclosing captured cell

```babble
def enclosing = 10
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

```mir
;; mir -O2 fn=sumDown stage=before
fn sumDown(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 0
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = call_self sumDown(v5) {pure}
    v7: int = add v1, v6 {pure}
    br bb3(v7)
  bb2:
    v8: int = const 0
    br bb3(v8)
  bb3(v9: int):
    ret v9
```

```mir
;; mir -O2 fn=sumDown
fn sumDown(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 0
    v3: bool = lte v0, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: int = const 1
    v5: int = sub v0, v4 {pure}
    v6: int = call_self sumDown(v5) {pure}
    v7: int = add v1, v6 {pure}
    br bb3(v7)
  bb2:
    br bb3(v2)
  bb3(v8: int):
    ret v8
```

## Captured outer and class-constructor double cells inside enclosing function (DoubleCell.of)

```babble
def make = fn (start : double) -> (
    def outer = start
    def C = class (p : double) (
        def bump = fn io mut () : double -> ( outer += 1.0 ; p += 0.5 ; outer + p )
    )
    C(2.0)
)
def inst = make(10.0)
assert(inst.bump() == 13.5)
assert(inst.bump() == 15.0)
```

```hir
;; hir -O0,-O1 fn=C.bump
fn C.bump () -> double {io mut}
  (set outer += 1.0:double)
  (set p += 0.5:double)
  (+:double var(outer) var(p))
```

```mir
;; mir -O2 fn=C.bump stage=before,final
fn C.bump() -> double:
  bb0:
    v0: double = slot_read outer {read:shared}
    v1: double = const 1.0
    v2: double = add v0, v1 {pure}
    slot_write outer, v2 {write:shared}
    v3: double = slot_read p {read:shared,throws}
    v4: double = const 0.5
    v5: double = add v3, v4 {pure}
    slot_write p, v5 {write:shared,throws}
    v6: double = slot_read outer {read:shared}
    v7: double = slot_read p {read:shared,throws}
    v8: double = add v6, v7 {pure}
    ret v8
```

## Static block this assign, compound assign, and this reference

```babble
def C = class () (
    def getV = fn () : int -> ( 7 )
) static (
    def x = 1
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

```mir
;; mir -O2 fn=bump stage=before
fn bump() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: int = const 2
    v2: any = convert.any v1
    store_field v0.x, v2 {write:shared,throws}
    v3: any = slot_read this {pure}
    v4: any = load_field v3.x {read:shared,throws}
    v5: int = const 3
    v6: any = convert.any v5
    v7: any = add v4, v6 {impure}
    store_field v3.x, v7 {write:shared,throws}
    v8: any = slot_read this {pure}
    v9: any = load_field v8.x {read:shared,throws}
    v10: int = convert.int v9
    ret v10
```

```mir
;; mir -O2 fn=bump
fn bump() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = const 2
    store_field v0.x, v1 {write:shared,throws}
    v2: any = load_field v0.x {read:shared,throws}
    v3: any = const 3
    v4: any = add v2, v3 {impure}
    store_field v0.x, v4 {write:shared,throws}
    v5: any = load_field v0.x {read:shared,throws}
    v6: int = convert.int v5
    ret v6
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

```mir
;; mir -O2 interface=IWithConst stage=before,final
interface IWithConst:
  fn get() -> int:
    bb0:
      v0: int = const 3
      ret v0
```

## BabbleInstance operator methods (neg, plus, minus, times, div, compound +=) and folded unary minus

```babble
def v = Vec(10) ; def c = Vec(5) ; c += 3
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

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
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

```mir
;; mir -O2 fn=rep stage=before,final
fn rep(v0: any) -> any:
  bb0:
    v1: int = call_java v0.length() {pure,throws}
    v2: int = const 4
    v3: bool = gte v1, v2 {pure}
    br_if v3, bb2, bb1
  bb1:
    v4: any = const "a"
    v5: any = const "b"
    v6: any = strcat v0, v5 {pure}
    v7: any = call_self rep(v6) {pure!}
    v8: any = strcat v4, v7 {pure}
    br bb3(v8)
  bb2:
    br bb3(v0)
  bb3(v9: any):
    ret v9
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

```mir
;; mir -O2 fn=r9 stage=before
fn r9(v0: int, v1: int, v2: int, v3: int, v4: int, v5: int, v6: int, v7: int, v8: int) -> int:
  bb0:
    v9: int = const 0
    v10: bool = lte v0, v9 {pure}
    br_if v10, bb2, bb1
  bb1:
    v11: int = const 1
    v12: any = slot_read $recurse {pure}
    v13: int = const 1
    v14: int = sub v0, v13 {pure}
    v15: int = opaque.DynamicCall(v12, v14, v1, v2, v3, v4, v5, v6, v7, v8) {impure}
    v16: int = add v11, v15 {pure}
    br bb3(v16)
  bb2:
    v17: int = const 0
    br bb3(v17)
  bb3(v18: int):
    ret v18
```

```mir
;; mir -O2 fn=r9
fn r9(v0: int, v1: int, v2: int, v3: int, v4: int, v5: int, v6: int, v7: int, v8: int) -> int:
  bb0:
    v9: int = const 0
    v10: bool = lte v0, v9 {pure}
    br_if v10, bb2, bb1
  bb1:
    v11: int = const 1
    v12: any = slot_read $recurse {pure}
    v13: int = sub v0, v11 {pure}
    v14: int = opaque.DynamicCall(v12, v13, v1, v2, v3, v4, v5, v6, v7, v8) {impure}
    v15: int = add v11, v14 {pure}
    br bb3(v15)
  bb2:
    br bb3(v9)
  bb3(v16: int):
    ret v16
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

```mir
;; mir -O2 fn=Big.run stage=before
fn Big.run() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: int = call_method v0.s9() {pure}
    v2: int = const 18
    v3: int = add v1, v2 {pure}
    v4: any = slot_read this {pure}
    v5: int = const 10
    v6: int = call_method v4.s9(v5) {pure}
    v7: int = add v3, v6 {pure}
    ret v7
```

```mir
;; mir -O2 fn=Big.run
fn Big.run() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: int = call_method v0.s9() {pure}
    v2: int = const 18
    v3: int = add v1, v2 {pure}
    v4: int = const 10
    v5: int = call_method v0.s9(v4) {pure}
    v6: int = add v3, v5 {pure}
    ret v6
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

```mir
;; mir -O2 fn=C.run stage=before
fn C.run() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = load_field v0.cb {read:shared,throws}
    v2: int = const 3
    v3: int = opaque.DynamicCall(v1, v2) {impure}
    v4: any = slot_read this {pure}
    v5: any = load_field v4.greet {read:shared,throws}
    v6: int = opaque.DynamicCall(v5) {impure}
    v7: int = add v3, v6 {pure}
    v8: any = slot_read this {pure}
    v9: int = load_field v8.n {read:shared,throws}
    v10: int = const 2
    v11: int = add v9, v10 {pure}
    v12: int = add v7, v11 {pure}
    ret v12
```

```mir
;; mir -O2 fn=C.run
fn C.run() -> int:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = load_field v0.cb {read:shared,throws}
    v2: int = const 3
    v3: int = opaque.DynamicCall(v1, v2) {impure}
    v4: any = load_field v0.greet {read:shared,throws}
    v5: int = opaque.DynamicCall(v4) {impure}
    v6: int = add v3, v5 {pure}
    v7: int = load_field v0.n {read:shared,throws}
    v8: int = const 2
    v9: int = add v7, v8 {pure}
    v10: int = add v6, v9 {pure}
    ret v10
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
  (call name(assert) (==:bool (block
        (def $this name(obj))
        7:int) 7:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Record fast-class disqualifiers : >8 fields, captured outer cell, and Java SAM param

```babble
def R9 = record (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) ()
def k = 3 ; def CellRec = record (x: int) ( def get = fn (): int -> ( this.x + k ) )
def SamRec = record (r: java.lang.Runnable) ()
def hits = 0 ; def sr = SamRec(fn io () -> ( hits += 1 )) ; sr.r.run()
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

```mir
;; mir -O2 record=CellRec stage=before,final
record CellRec:
  fn CellRec.get() -> int:
    bb0:
      v0: any = slot_read this {pure}
      v1: int = load_field v0.x {read:frozen}
      v2: any = opaque.NameRead() {impure}
      v3: int = convert.int v2
      v4: int = add v1, v3 {pure}
      ret v4
```

## Record fast-class disqualifiers : body assert, body field, and nested overload

```babble
def ChkR = record (x: int) ( assert(this.x >= 0) )
def FldR = record (x: int) ( def dbl: int = this.x * 2 )
def OvR = record (x: int) (
    def f = overload ( fn (n: int): int -> ( this.x + n ), overload ( fn (b: bool): int -> ( 1 ), fn (s: string): int -> ( s.length() ) ) )
)
assert(ChkR(5).x == 5 and FldR(3).dbl == 6 and OvR(4).f(2) == 6 and OvR(4).f("hi") == 2)
```

```hir
;; hir -O0,-O1 record=ChkR
record ChkR (x:int)
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

```mir
;; mir -O2 record=OvR stage=before,final
record OvR:
  fn OvR.f(v0: int) -> int:
    bb0:
      v1: any = slot_read this {pure}
      v2: int = load_field v1.x {read:frozen}
      v3: int = add v2, v0 {pure}
      ret v3
  fn OvR.f(v0: bool) -> int:
    bb0:
      v1: int = const 1
      ret v1
  fn OvR.f(v0: any) -> int:
    bb0:
      v1: int = call_java v0.length() {pure,throws}
      ret v1
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
  (def r1
    (block
      (def loc#1 (overload name(extI) name(extS)))
      7:int))
  (def r2
    (block
      (def loc#2 (overload (fn-ref loc#1) (fn-ref loc#2)))
      12:int))
```

```mir
;; mir -O2 class=C stage=before,final
class C:
  (no methods)
```
