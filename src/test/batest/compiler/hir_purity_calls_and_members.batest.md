# HIR Purity : Calls, Overloads, Interfaces, and Members

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble
;; babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (if this.v < 0 then (return this.v) ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; if this.v < 0 then (return this.v + n) ; this.v + n)
)
```

```babble
;; babble preamble
var tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def volLen = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return n ; n)
```



## HirPurity.allArgsPrimitiveOrImmutable : String.equals on immutable String vs mutable Point

```babble
def run = fn io (n: int): int -> (
    def pt = java.awt.Point(1, 2) ; var a = 0 ; var i = 0
    def u1 = "ab".equals("cd") ; def u2 = "ab".equals(pt)
    while i < n then (
        a += (if "ab".equals("ab") and not "ab".equals(pt) then (1) else (0)) ; i += 1
    ) ; a
)
assert(run(2) == 2)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (def u1 (jcall String.equals "ab" "cd"))
  (def u2 (jcall String.equals "ab" var(pt)))
  (while (<:bool var(i) var(n))
    (block
      (set a += (block (if (and (jcall String.equals "ab" "ab") (not (jcall String.equals "ab" var(pt)))) (block 1:int) (block 0:int))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (def u2 (jcall String.equals "ab" var(pt)))
  (block
    (def $licm0 (jcall String.equals "ab" "ab"))
    (while (<:bool var(i) var(n))
      (block
        (set a += (if (and var($licm0) (not (jcall String.equals "ab" var(pt)))) 1:int 0:int))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = const 2
    v3: any = new_java Point(v1, v2) {pure}
    v4: int = const 0
    v5: int = const 0
    v6: any = const "ab"
    v7: bool = call_java v6.equals(v3) {pure!}
    v8: any = const "ab"
    v9: any = const "ab"
    v10: bool = call_java v8.equals(v9) {pure}
    v11: any = const null
    br bb1(v11, v5, v4)
  bb1(v12: any, v13: int, v14: int):
    v15: bool = lt v13, v0 {pure}
    br_if v15, bb3, bb2
  bb2:
    ret v14
  bb3:
    v16: bool = const false
    br_if v10, bb4, bb5(v16)
  bb4:
    v17: any = const "ab"
    v18: bool = call_java v17.equals(v3) {pure!}
    v19: bool = not v18 {pure}
    br bb5(v19)
  bb5(v20: bool):
    br_if v20, bb7, bb6
  bb6:
    v21: int = const 0
    br bb8(v21)
  bb7:
    v22: int = const 1
    br bb8(v22)
  bb8(v23: int):
    v24: int = add v14, v23 {pure}
    v25: int = const 1
    v26: int = add v13, v25 {pure}
    v27: any = convert.any v26
    br bb1(v27, v26, v24)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = const 2
    v3: any = new_java Point(v1, v2) {pure}
    v4: int = const 0
    v5: any = const "ab"
    v6: bool = call_java v5.equals(v5) {pure}
    br bb1(v4, v4)
  bb1(v7: int, v8: int):
    v9: bool = lt v7, v0 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: bool = const false
    br_if v6, bb4, bb5(v10)
  bb4:
    v11: bool = call_java v5.equals(v3) {pure!}
    v12: bool = not v11 {pure}
    br bb5(v12)
  bb5(v13: bool):
    br_if v13, bb7, bb6
  bb6:
    br bb8(v4)
  bb7:
    br bb8(v1)
  bb8(v14: int):
    v15: int = add v8, v14 {pure}
    v16: int = add v7, v1 {pure}
    br bb1(v16, v15)
```

## HirPurity : CoreExceptionRef, boxed Integer arithmetic, and char-vs-nullable equality

```babble
def run = fn (ji: java.lang.Integer, chOpt: char?): int -> (
    def uE = exception ; def uC = ('a' == chOpt)
    def uU = -ji ; def uB1 = ji + 1 ; def uB2 = 1 + ji
    1
)
assert(run(java.lang.Integer.valueOf(2), 'a') == 1)
```

```hir
;; hir -O0 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (def uE core-exception)
  (def uC (block (==:bool 'a' var(chOpt))))
  (def uU (neg var(ji)))
  (def uB1 (+:int var(ji) 1:int))
  (def uB2 (+:int 1:int var(ji)))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (def uC (==:bool 'a' var(chOpt)))
  1:int
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: char = const 'a'
    v3: any = convert.any v2
    v4: any = eq v3, v1 {pure,throws}
    v5: bool = convert.bool v4
    v6: int = const 1
    ret v6
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: any = const 'a'
    v3: any = eq v2, v1 {pure,throws}
    v4: int = const 1
    ret v4
```

## HirPurity : OverloadSelect and non-pure-static JavaMethodCall receivers

```babble
def run = fn io (): int -> (
    def ov = overload (fn (x: int): int -> (x), fn (s: string): int -> (s.length()))
    def uOv: fn (int): int = ov
    def m = java.lang.Math ; def uM1 = m.abs(-5)
    def uM2 = (bump() ; java.lang.Math).abs(-5)
    1
)
assert(run() == 1 and tick == 1)
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def ov (overload (fn-ref ov#1) (fn-ref ov#2)))
  (def uOv (overload-select #0 var(ov)))
  (def m (select (select name(java).lang).Math))
  (def uM1 (jcall Math.abs -5:int))
  (def uM2 (jcall Math.abs -5:int))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def ov (overload (fn-ref ov#1) (fn-ref ov#2)))
  (def m (select (select name(java).lang).Math))
  (def uM2 (jcall Math.abs -5:int))
  1:int
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: any = opaque.FunctionRef(ov) {pure}
    v1: any = opaque.FunctionRef(ov) {pure}
    v2: any = opaque.MakeOverloadSet(v0, v1) {pure}
    v3: any = name_read java {pure}
    v4: any = opaque.DynamicSelect(v3.lang) {impure}
    v5: any = opaque.DynamicSelect(v4.Math) {impure}
    v6: any = name_read bump {pure}
    v7: int = call bump() {impure}
    v8: any = name_read java {pure}
    v9: any = opaque.DynamicSelect(v8.lang) {impure}
    v10: any = opaque.DynamicSelect(v9.Math) {impure}
    v11: int = const -5
    v12: int = call_java v10.abs(v11) {impure}
    v13: int = const 1
    ret v13
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: any = name_read java {pure}
    v1: any = opaque.DynamicSelect(v0.lang) {impure}
    v2: any = opaque.DynamicSelect(v1.Math) {impure}
    v3: any = name_read bump {pure}
    v4: int = call bump() {impure}
    v5: any = opaque.DynamicSelect(v0.lang) {impure}
    v6: any = opaque.DynamicSelect(v5.Math) {impure}
    v7: int = const -5
    v8: int = call_java v6.abs(v7) {impure}
    v9: int = const 1
    ret v9
```

## HirPurity : cross-module DirectThisMethodCall pure, throwing, and volatile paths

```babble
def ext = import("ext_rec.ba")
def run = fn volatile (er: ext.ExtRec): int -> (
    def u1 = er.getPure() ; def u2 = er.getThrow() ; def u3 = er.getVol()
    var a = 0 ; var i = 0
    while i < 2 then (a += er.getPure() + er.getVol() ; i += 1)
    a
)
assert(run(ext.ExtRec(4)) >= 18)
```

```hir
;; hir -O0 fn=run
fn run (er:obj) -> int {volatile unique}
  (def u1 (call-method var(er).getPure))
  (def u2 (call-method var(er).getThrow))
  (def u3 (call-method var(er).getVol))
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (call-method var(er).getPure) (call-method var(er).getVol)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (er:obj) -> int {volatile unique}
  (def u2 (call-method var(er).getThrow))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-method var(er).getPure))
    (while (<:bool var(i) 2:int)
      (block
        (set a += (+:int var($licm0) (call-method var(er).getVol)))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any) -> int:
  bb0:
    v1: int = call_method v0.getThrow() {pure,throws}
    v2: int = const 0
    v3: int = const 0
    v4: int = call_method v0.getPure() {pure}
    v5: any = const null
    br bb1(v5, v3, v2)
  bb1(v6: any, v7: int, v8: int):
    v9: int = const 2
    v10: bool = lt v7, v9 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v8
  bb3:
    v11: int = call_method v0.getVol() {pure!}
    v12: int = add v4, v11 {pure}
    v13: int = add v8, v12 {pure}
    v14: int = const 1
    v15: int = add v7, v14 {pure}
    v16: any = convert.any v15
    br bb1(v16, v15, v13)
```

```mir
;; mir -O2 fn=run
fn run(v0: any) -> int:
  bb0:
    v1: int = call_method v0.getThrow() {pure,throws}
    v2: int = const 0
    v3: int = call_method v0.getPure() {pure}
    br bb1(v2, v2)
  bb1(v4: int, v5: int):
    v6: int = const 2
    v7: bool = lt v4, v6 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v5
  bb3:
    v8: int = call_method v0.getVol() {pure!}
    v9: int = add v3, v8 {pure}
    v10: int = add v5, v9 {pure}
    v11: int = const 1
    v12: int = add v4, v11 {pure}
    br bb1(v12, v10)
```

## HirPurity : InterfaceCall on ThisRef, RecordNew, call result, and impure method

```babble
def I = interface (
    def a = fn nothrow (): int -> (1)
    def b = fn nothrow (): int -> (def u = this.a() ; 2)
    def imp = fn io (): int -> (bump())
)
def R = record (x: int) implements I ()
def getI = fn (): I -> (R(3))
def run = fn io (vi: I): int -> (def u1 = R(1).b() ; def u2 = getI().a() ; def u3 = vi.imp() ; 1)
assert(run(R(2)) == 1 and tick == 1)
```

```hir
;; hir -O0 fn=run
fn run (vi:obj) -> int {io}
  (def u1 (call (select (record-new R 1:int).b)))
  (def u2 (call-iface (call-fn getI).a))
  (def u3 (call-iface var(vi).imp))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (vi:obj) -> int {io}
  (def u1 (call (select (record-new R 1:int).b)))
  (def u3 (call-iface var(vi).imp))
  1:int
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any) -> int:
  bb0:
    v1: any = name_read R {pure}
    v2: int = const 1
    v3: any = record_new R[v1](v2) {pure}
    v4: any = opaque.DynamicSelect(v3.b) {impure}
    v5: int = opaque.DynamicCall(v4) {impure}
    v6: int = call_iface v0.imp() {impure}
    v7: int = const 1
    ret v7
```

```mir
;; mir -O2 fn=run
fn run(v0: any) -> int:
  bb0:
    v1: any = name_read R {pure}
    v2: int = const 1
    v3: any = record_new R[v1](v2) {pure}
    v4: any = opaque.DynamicSelect(v3.b) {impure}
    v5: int = opaque.DynamicCall(v4) {impure}
    v6: int = call_iface v0.imp() {impure}
    ret v2
```

## HirPurity : body-field ThisFieldGet, call-result record receiver, and local RecordNew

```babble
def R = record (x: int) (def y: int = this.x + 1 ; def m = fn (): int -> (def u = this.y ; this.x))
def getR = fn (): R -> (R(3))
def run = fn (): int -> (
    def LR = record (z: int) () ; def u0 = LR(1)
    def u1 = getR().x ; def u2 = getR().m() ; 1
)
assert(run() == 1)
```

```hir
;; hir -O0 fn=run
fn run () -> int {nothrow unique}
  (def LR (class-ref LR))
  (def u0 (record-new LR 1:int))
  (def u1 (get-this-field x))
  (def u2 (call-method (call-fn getR).m))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {nothrow unique}
  (def LR (class-ref LR))
  (block (def $sr_u0_0 1:int))
  (def u1 (get-this-field x))
  (def u2
    (block
      (def $this (call name(R) 3:int))
      (def u (get-this-field y))
      (get-this-field x)))
  1:int
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: any = opaque.ClassExpr(LR) {impure}
    v1: int = const 1
    v2: any = name_read R {pure}
    v3: int = const 3
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    v5: int = load_field v4.x {read:frozen,throws}
    v6: any = name_read R {pure}
    v7: int = const 3
    v8: any = opaque.DynamicCall(v6, v7) {impure}
    v9: int = load_field v8.y {read:frozen,throws}
    v10: int = load_field v8.x {read:frozen}
    v11: int = const 1
    ret v11
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: any = opaque.ClassExpr(LR) {impure}
    v1: int = const 1
    v2: any = name_read R {pure}
    v3: int = const 3
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    v5: int = load_field v4.x {read:frozen,throws}
    v6: any = opaque.DynamicCall(v2, v3) {impure}
    v7: int = load_field v6.y {read:frozen,throws}
    ret v1
```

## HirPurity : uninitialized Def, captured cell local, capturing closure, Move, and late RecordNew

```babble
tick = 1
def LateR = record (k: int) ()
def fUninit = fn (k: int): int -> (var u: int?=null; u=k+1; u!)
def run = fn io (k: int): int -> (
    def outer = k ; def cl = fn nothrow (): int -> (outer + 1)
    def u0 = LateR(1) ; def u1 = fUninit(k) ; def u2 = cl()
    def u3 = (def cap = 1 ; def c2 = fn (): int -> (cap) ; c2())
    def r = list(2) ; def u4 = move(r) ; u2
)
assert(run(3) == 4)
```

```hir
;; hir -O0 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (def u0 (record-new LateR 1:int))
  (def u1 (call-fn fUninit var(k)))
  (def u2 (call-fn cl))
  (def u3
    (block
      (def cap 1:int)
      (def c2 (fn-ref c2))
      (call-fn c2)))
  (def r (core-new list 2:int))
  (def u4 (move var(r)))
  var(u2)
```

```hir
;; hir -O1 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (block
    name(LateR)
    (def $sr_u0_0 1:int))
  (def u1
    (block
      name(fUninit)
      (def u null)
      (set u (+:int var(k) 1:int))
      (nonnull var(u))))
  (def u2 (call-fn cl))
  (def u3
    (block
      (def cap 1:int)
      (def c2 (fn-ref c2))
      (call-fn c2)))
  (def r (core-new list 2:int))
  var(u2)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    slot_init outer, v0 {write:shared}
    v1: any = opaque.FunctionRef(cl) {read:shared}
    v2: any = name_read LateR {pure,throws}
    v3: int = const 1
    v4: any = name_read fUninit {pure,throws}
    v5: any = const null
    v6: int = const 1
    v7: int = add v0, v6 {pure}
    v8: any = convert.any v7
    v9: int = nonnull v8
    v10: int = call cl() {impure}
    slot_decl cap
    v11: int = const 1
    slot_init cap, v11 {write:shared}
    v12: any = opaque.FunctionRef(c2) {read:shared}
    v13: int = call c2() {impure}
    v14: int = const 2
    v15: any = opaque.CoreCollectionCall(list, v14) {impure}
    ret v10
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    slot_init outer, v0 {write:shared}
    v1: any = opaque.FunctionRef(cl) {read:shared}
    v2: any = name_read LateR {pure,throws}
    v3: int = const 1
    v4: any = name_read fUninit {pure,throws}
    v5: int = add v0, v3 {pure}
    v6: any = convert.any v5
    v7: int = nonnull v6
    v8: int = call cl() {impure}
    slot_decl cap
    slot_init cap, v3 {write:shared}
    v9: any = opaque.FunctionRef(c2) {read:shared}
    v10: int = call c2() {impure}
    v11: int = const 2
    v12: any = opaque.CoreCollectionCall(list, v11) {impure}
    ret v8
```
