# LICM Move, Freeze, Unchecked Casts, and Try Modified-Vars

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirLicmPass : DirectThisMethodCall on mutable/frozen class and non-pure-static Math.sin

```babble
def PC = class (def x: int) (def pm = fn nothrow (k: int): int -> (return k + 2 ; k))
def run = fn (n: int, mc: PC, fmc: frozen PC): double -> (
    def mCls = java.lang.Math ; def a = 0.0 ; def i = 0
    while i < n then (a += double(mc.pm(n) + fmc.pm(n)) + mCls.sin(0.0) ; i += 1) ; a
)
assert(run(2, PC(1), freeze(PC(1))) == 16.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (def mCls (select (select name(java).lang).Math))
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (call name(double) (+:int (call-method var(mc).pm var(n)) (call-method var(fmc).pm var(n)))) (jcall Math.sin 0.0:double)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (def mCls (select (select name(java).lang).Math))
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (+:int var(n) 2:int))
    (block
      (def $licm1 (+:int (+:int var(n) 2:int) var($licm0)))
      (while (<:bool var(i) var(n))
        (block
          (set a += (+:double (call name(double) var($licm1)) 0.0:double))
          (set i += 1:int)))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any, v2: any) -> double:
  bb0:
    v3: any = opaque.NameRead() {impure}
    v4: any = opaque.DynamicSelect(v3) {impure}
    v5: any = opaque.DynamicSelect(v4) {impure}
    v6: double = const 0.0
    v7: int = const 0
    v8: int = const 2
    v9: int = add v0, v8 {pure}
    v10: int = const 2
    v11: int = add v0, v10 {pure}
    v12: int = add v11, v9 {pure}
    v13: any = const null
    br bb1(v13, v7, v6)
  bb1(v14: any, v15: int, v16: double):
    v17: bool = lt v15, v0 {pure}
    br_if v17, bb3, bb2
  bb2:
    ret v16
  bb3:
    v18: any = opaque.NameRead() {impure}
    v19: double = opaque.DynamicCall(v18, v12) {impure}
    v20: double = const 0.0
    v21: double = add v19, v20 {pure}
    v22: double = add v16, v21 {pure}
    v23: int = const 1
    v24: int = add v15, v23 {pure}
    v25: any = convert.any v24
    br bb1(v25, v24, v22)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any, v2: any) -> double:
  bb0:
    v3: any = opaque.NameRead() {impure}
    v4: any = opaque.DynamicSelect(v3) {impure}
    v5: any = opaque.DynamicSelect(v4) {impure}
    v6: double = const 0.0
    v7: int = const 0
    v8: int = const 2
    v9: int = add v0, v8 {pure}
    v10: int = add v9, v9 {pure}
    br bb1(v7, v6)
  bb1(v11: int, v12: double):
    v13: bool = lt v11, v0 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v12
  bb3:
    v14: any = opaque.NameRead() {impure}
    v15: double = opaque.DynamicCall(v14, v10) {impure}
    v16: double = add v15, v6 {pure}
    v17: double = add v12, v16 {pure}
    v18: int = const 1
    v19: int = add v11, v18 {pure}
    br bb1(v19, v17)
```

## HirLicmPass : InterfaceCall on mutable, frozen, and record receivers

```babble
def PI = interface (def m = fn nothrow (k: int): int -> (return k + 1 ; k))
def PC = class () implements PI ()
def PR = record (x: int) implements PI ()
def run = fn (n: int, vi: PI, fvi: frozen PI, pr: PR): int -> (
    def a = 0 ; def i = 0
    while i < n then (a += vi.m(n) + fvi.m(n) + pr.m(n) ; i += 1) ; a
)
assert(run(2, PC(), freeze(PC()), PR(1)) == 18)
```

```hir
;; hir -O0 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-iface var(vi).m var(n)) (call-iface var(fvi).m var(n))) (call (select var(pr).m) var(n))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-iface var(fvi).m var(n)))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:int (+:int (call-iface var(vi).m var(n)) var($licm0)) (call (select var(pr).m) var(n))))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any, v2: any, v3: any) -> int:
  bb0:
    v4: int = const 0
    v5: int = const 0
    v6: int = call_iface v2.m(v0) {pure}
    v7: any = const null
    br bb1(v7, v5, v4)
  bb1(v8: any, v9: int, v10: int):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: int = call_iface v1.m(v0) {pure!}
    v13: int = add v12, v6 {pure}
    v14: any = opaque.DynamicSelect(v3) {impure}
    v15: int = opaque.DynamicCall(v14, v0) {impure}
    v16: int = add v13, v15 {pure}
    v17: int = add v10, v16 {pure}
    v18: int = const 1
    v19: int = add v9, v18 {pure}
    v20: any = convert.any v19
    br bb1(v20, v19, v17)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any, v2: any, v3: any) -> int:
  bb0:
    v4: int = const 0
    v5: int = call_iface v2.m(v0) {pure}
    br bb1(v4, v4)
  bb1(v6: int, v7: int):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: int = call_iface v1.m(v0) {pure!}
    v10: int = add v9, v5 {pure}
    v11: any = opaque.DynamicSelect(v3) {impure}
    v12: int = opaque.DynamicCall(v11, v0) {impure}
    v13: int = add v10, v12 {pure}
    v14: int = add v7, v13 {pure}
    v15: int = const 1
    v16: int = add v6, v15 {pure}
    br bb1(v16, v14)
```

## HirLicmPass and HirPurity : Move, Freeze, and widened transparent Block inside while

```babble
def bm = fn nothrow (s: list of int): int -> (return s.size() ; 0)
def bf = fn nothrow (s: frozen array of int): int -> (return s.size() ; 0)
def run = fn (n: int, x: int): double -> (
  def a = 0.0 ; def i = 0
  while i < n then (
    def s = list(1) ; def r = array.of(1, 2)
    a += double(bm(move(s)) + bf(freeze(r))) + bigSink((x + 1)) ; i += 1
  ) ; a
)
assert(run(2, 3) == 14.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def s (core-new list 1:int))
      (def r (array-new OF 1:int 2:int))
      (set a += (+:double (call name(double) (+:int (call-fn bm (move var(s))) (call-fn bf (freeze var(r))))) (call-fn bigSink (block (+:int var(x) 1:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0
      (block
        (def x#2 (block (+:int var(x#1) 1:int)))
        var(x#2)))
    (while (<:bool var(i) var(n))
      (block
        (def s#1 (core-new list 1:int))
        (def r (array-new OF 1:int 2:int))
        (set a += (+:double (call name(double) (+:int (block
                  (def s#2 (move var(s#1)))
                  (jcall List.size var(s#2))) (block
                  (def s#3 (freeze var(r)))
                  (array-op SIZE var(s#3))))) var($licm0)))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: int = const 1
    v5: int = add v1, v4 {pure}
    v6: double = convert.double v5
    v7: any = const null
    br bb1(v7, v3, v2)
  bb1(v8: any, v9: int, v10: double):
    v11: bool = lt v9, v0 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v10
  bb3:
    v12: int = const 1
    v13: any = opaque.CoreCollectionCall(v12) {impure}
    v14: int = const 1
    v15: int = const 2
    v16: any = opaque.ArrayCreate(v14, v15) {impure}
    v17: any = opaque.NameRead() {impure}
    v18: any = move v13
    v19: int = call_java v18.size() {pure,throws}
    v20: any = freeze v16
    v21: int = array_len v20 {read:frozen,throws}
    v22: int = add v19, v21 {pure}
    v23: double = opaque.DynamicCall(v17, v22) {impure}
    v24: double = add v23, v6 {pure}
    v25: double = add v10, v24 {pure}
    v26: int = const 1
    v27: int = add v9, v26 {pure}
    v28: any = convert.any v27
    br bb1(v28, v27, v25)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: int) -> double:
  bb0:
    v2: double = const 0.0
    v3: int = const 0
    v4: int = const 1
    v5: int = add v1, v4 {pure}
    v6: double = convert.double v5
    br bb1(v3, v2)
  bb1(v7: int, v8: double):
    v9: bool = lt v7, v0 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: any = opaque.CoreCollectionCall(v4) {impure}
    v11: int = const 2
    v12: any = opaque.ArrayCreate(v4, v11) {impure}
    v13: any = opaque.NameRead() {impure}
    v14: any = move v10
    v15: int = call_java v14.size() {pure,throws}
    v16: any = freeze v12
    v17: int = array_len v16 {read:frozen,throws}
    v18: int = add v15, v17 {pure}
    v19: double = opaque.DynamicCall(v13, v18) {impure}
    v20: double = add v19, v6 {pure}
    v21: double = add v8, v20 {pure}
    v22: int = add v7, v4 {pure}
    br bb1(v22, v21)
```

## HirLicmPass and HirPurity : unchecked As (x as any?) hoisted out of while

```babble
def bigAny = fn nothrow (v: any?): int -> (if same(v, null) then (return 0) ; 1)
def run = fn (n: int, x: int): int -> (
  def a = 0 ; def i = 0
  while i < n then (a += bigAny(x as any?) ; i += 1) ; a
)
assert(run(3, 5) == 3)
```

```stderr
Warning : Redundant cast : any?
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigAny (as var(x) any)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-fn bigAny (as var(x) any)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 0
    v3: int = const 0
    v4: any = opaque.NameRead() {impure}
    v5: int = call bigAny(v1) {pure}
    v6: any = const null
    br bb1(v6, v3, v2)
  bb1(v7: any, v8: int, v9: int):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: int = add v9, v5 {pure}
    v12: int = const 1
    v13: int = add v8, v12 {pure}
    v14: any = convert.any v13
    br bb1(v14, v13, v11)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: int) -> int:
  bb0:
    v2: int = const 0
    v3: any = opaque.NameRead() {impure}
    v4: int = call bigAny(v1) {pure}
    br bb1(v2, v2)
  bb1(v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: int = add v6, v4 {pure}
    v9: int = const 1
    v10: int = add v5, v9 {pure}
    br bb1(v10, v8)
```

## HirPurity.collectModifiedVars : try with named/unnamed catch and finally defs inside while

```babble
def run = fn (n: int): int -> (
  def s = 0 ; def i = 0
  while i < n then (
    s += try (
      if i < 0 then (throw java.lang.ArithmeticException("x"))
      def t = i + 1 ; t
    ) catch e: java.lang.ArithmeticException then (def c1 = 1 ; c1)
      catch : exception then (def c2 = 2 ; c2)
      finally (def f = 3 ; discard(f))
    i += 1
  ) ; s
)
assert(run(3) == 6)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (block (throw (jnew ArithmeticException "x"))))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            (def c1 1:int)
            var(c1))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (def c2 2:int)
            var(c2))
          (finally
            (def f 3:int)
            (call name(discard) var(f)))))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (throw (jnew ArithmeticException "x")))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            1:int)
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)
          (finally
            null)))
      (set i += 1:int)))
  var(s)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v2, v1)
  bb1(v4: any, v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    br bb4
  bb4 [unwind: ArithmeticException -> bb12, BabbleException -> bb9, * -> bb14]:
    v8: int = const 0
    v9: bool = lt v5, v8 {pure}
    br_if v9, bb8, bb5
  bb5 [unwind: ArithmeticException -> bb12, BabbleException -> bb9, * -> bb14]:
    v10: any = const null
    br bb6
  bb6 [unwind: ArithmeticException -> bb12, BabbleException -> bb9, * -> bb14]:
    v11: int = const 1
    v12: int = add v5, v11 {pure}
    br bb7
  bb7:
    v13: any = const null
    br bb17(v12)
  bb8 [unwind: ArithmeticException -> bb12, BabbleException -> bb9, * -> bb14]:
    v14: any = const "x"
    v15: any = new_java ArithmeticException(v14) {pure!}
    rethrow v15
  bb9(v16: any) [handler]:
    br bb10
  bb10 [unwind: * -> bb14]:
    v17: int = const 2
    br bb11
  bb11:
    v18: any = const null
    br bb17(v17)
  bb12(v19: any) [handler]:
    br bb13
  bb13 [unwind: * -> bb14]:
    v20: int = const 1
    br bb16
  bb14(v21: any) [handler]:
    br bb15
  bb15:
    v22: any = const null
    rethrow v21
  bb16:
    v23: any = const null
    br bb17(v20)
  bb17(v24: int):
    v25: int = add v6, v24 {pure}
    v26: int = const 1
    v27: int = add v5, v26 {pure}
    v28: any = convert.any v27
    br bb1(v28, v27, v25)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    br bb1(v1, v1)
  bb1(v2: int, v3: int):
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb3, bb2
  bb2:
    ret v3
  bb3 [unwind: ArithmeticException -> bb10, BabbleException -> bb7, * -> bb12]:
    v5: bool = lt v2, v1 {pure}
    br_if v5, bb6, bb4
  bb4 [unwind: ArithmeticException -> bb10, BabbleException -> bb7, * -> bb12]:
    v6: int = const 1
    v7: int = add v2, v6 {pure}
    br bb5
  bb5:
    br bb15(v7)
  bb6 [unwind: ArithmeticException -> bb10, BabbleException -> bb7, * -> bb12]:
    v8: any = const "x"
    v9: any = new_java ArithmeticException(v8) {pure!}
    rethrow v9
  bb7(v10: any) [handler]:
    br bb8
  bb8 [unwind: * -> bb12]:
    v11: int = const 2
    br bb9
  bb9:
    br bb15(v11)
  bb10(v12: any) [handler]:
    br bb11
  bb11 [unwind: * -> bb12]:
    v13: int = const 1
    br bb14
  bb12(v14: any) [handler]:
    br bb13
  bb13:
    rethrow v14
  bb14:
    br bb15(v13)
  bb15(v15: int):
    v16: int = add v3, v15 {pure}
    v17: int = const 1
    v18: int = add v2, v17 {pure}
    br bb1(v18, v16)
```
