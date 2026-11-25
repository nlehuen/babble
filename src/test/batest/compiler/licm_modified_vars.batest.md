# LICM Modified-Variable Collection Across Control Flow and Calls

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirPurity.collectModifiedVars : array.set, field/Java writes, and nested for loop

```babble
def Sub = class (var f: int) ()
def run = fn io (): int -> (
  var a = 0 ; def s = Sub(4) ; def pt = java.awt.Point(2, 3) ; var i = 0
  while i < 2 then (
    def a1 = (array of int)(2) ; a1.set(0, i + 1) ; s.f = 5 ; pt.x = 7
    for v in a1 then (a += v)
    a += pt.x + s.f ; i += 1
  ) ; a
)
assert(run() == 27)
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def s (call name(Sub) 4:int))
  (def pt (jnew Point 2:int 3:int))
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def a1 (array-new SIZE 2:int))
      (array-op SET var(a1) 0:int (+:int var(i) 1:int))
      (set-field var(s).f 5:int)
      (set-jfield x var(pt) 7:int)
      (for v in var(a1)
        (block
          (set a += var(v))))
      (set a += (+:int (get-jfield Point.x var(pt)) (get-this-field f)))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: int = const 0
    v1: any = opaque.NameRead(Sub) {read:shared}
    v2: int = const 4
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: int = const 2
    v5: int = const 3
    v6: any = new_java Point(v4, v5) {pure}
    v7: int = const 0
    v8: any = const null
    br bb1(v8, v7, v0)
  bb1(v9: any, v10: int, v11: int):
    v12: int = const 2
    v13: bool = lt v10, v12 {pure}
    br_if v13, bb3, bb2
  bb2:
    ret v11
  bb3:
    v14: int = const 2
    v15: any = opaque.ArrayCreate(v14) {impure}
    v16: int = const 0
    v17: int = const 1
    v18: int = add v10, v17 {pure}
    array_set v15[v16] = v18 {write:shared,throws}
    v19: any = const null
    v20: int = const 5
    v21: any = opaque.DynamicFieldAssign(v3.f, v20) {write:shared,throws}
    v22: int = const 7
    store_jfield v6.x, v22 {write:shared,throws}
    v23: any = for_init.array v15
    v24: any = const null
    v25: int = array_len v23 {read:frozen}
    v26: int = const 0
    br bb4(v24, v26, v11)
  bb4(v27: any, v28: int, v29: int):
    v30: bool = for_has_next v23, v28, v25
    br_if v30, bb6, bb5
  bb5:
    v31: int = load_jfield v6.x {read:shared,throws}
    v32: int = load_field v3.f {read:shared,throws}
    v33: int = add v31, v32 {pure}
    v34: int = add v29, v33 {pure}
    v35: int = const 1
    v36: int = add v10, v35 {pure}
    v37: any = convert.any v36
    br bb1(v37, v36, v34)
  bb6:
    v38: int = for_next v23, v28
    v39: int = add v29, v38 {pure}
    v40: any = convert.any v39
    br bb7
  bb7:
    v41: int = const 1
    v42: int = add v28, v41 {pure}
    br bb4(v40, v42, v39)
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: int = const 0
    v1: any = opaque.NameRead(Sub) {read:shared}
    v2: int = const 4
    v3: any = opaque.DynamicCall(v1, v2) {impure}
    v4: int = const 2
    v5: int = const 3
    v6: any = new_java Point(v4, v5) {pure}
    br bb1(v0, v0)
  bb1(v7: int, v8: int):
    v9: bool = lt v7, v4 {pure}
    br_if v9, bb3, bb2
  bb2:
    ret v8
  bb3:
    v10: any = opaque.ArrayCreate(v4) {impure}
    v11: int = const 1
    v12: int = add v7, v11 {pure}
    array_set v10[v0] = v12 {write:shared,throws}
    v13: int = const 5
    v14: any = opaque.DynamicFieldAssign(v3.f, v13) {write:shared,throws}
    v15: int = const 7
    store_jfield v6.x, v15 {write:shared,throws}
    v16: any = for_init.array v10
    v17: int = array_len v16 {read:frozen}
    br bb4(v0, v8)
  bb4(v18: int, v19: int):
    v20: bool = for_has_next v16, v18, v17
    br_if v20, bb6, bb5
  bb5:
    v21: int = load_jfield v6.x {read:shared,throws}
    v22: int = load_field v3.f {read:shared,throws}
    v23: int = add v21, v22 {pure}
    v24: int = add v19, v23 {pure}
    br bb1(v12, v24)
  bb6:
    v25: int = for_next v16, v18
    v26: int = add v19, v25 {pure}
    br bb7
  bb7:
    v27: int = add v18, v11 {pure}
    br bb4(v27, v26)
```

## HirPurity.collectModifiedVars : super, DirectThisMethodCall, and InterfaceCall inside while

```babble
def I = interface (def g = fn (x: int): int -> (x + 1))
def S = class (var f: int) implements I (
  def m = fn (x: int): int -> (this.f + x)
  def s = fn (x: int): int -> (var a = 0 ; var i = 0 ; while i < 2 then (a += super.g(x) + this.m(x) ; i += 1) ; a)
)
def r = fn (v: I): int -> (var a = 0 ; var i = 0 ; while i < 2 then (a += v.g(1) ; i += 1) ; a)
assert(S(5).s(1) == 16 and r(S(5)) == 4)
```

```hir
;; hir -O0 fn=S.s
fn S.s (x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (super.g var(x)) (call-method this.m var(x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=S.s
fn S.s (x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (super.g var(x)) (+:int (get-this-field f) var(x))))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=S.s stage=before
fn S.s(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v2, v1)
  bb1(v4: any, v5: int, v6: int):
    v7: int = const 2
    v8: bool = lt v5, v7 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v6
  bb3:
    v9: any = opaque.SuperCall(g, v0) {impure}
    v10: int = convert.int v9
    v11: any = slot_read this {pure}
    v12: int = load_field v11.f {read:shared,throws}
    v13: int = add v12, v0 {pure}
    v14: int = add v10, v13 {pure}
    v15: int = add v6, v14 {pure}
    v16: int = const 1
    v17: int = add v5, v16 {pure}
    v18: any = convert.any v17
    br bb1(v18, v17, v15)
```

```mir
;; mir -O2 fn=S.s
fn S.s(v0: int) -> int:
  bb0:
    v1: int = const 0
    br bb1(v1, v1)
  bb1(v2: int, v3: int):
    v4: int = const 2
    v5: bool = lt v2, v4 {pure}
    br_if v5, bb3, bb2
  bb2:
    ret v3
  bb3:
    v6: any = opaque.SuperCall(g, v0) {impure}
    v7: int = convert.int v6
    v8: any = slot_read this {pure}
    v9: int = load_field v8.f {read:shared,throws}
    v10: int = add v9, v0 {pure}
    v11: int = add v7, v10 {pure}
    v12: int = add v3, v11 {pure}
    v13: int = const 1
    v14: int = add v2, v13 {pure}
    br bb1(v14, v12)
```

## HirPurity.collectModifiedVars : is, as, and safe-nav (?.) inside while

```babble
def S = class (var f: int) (def m = fn (x: int): int -> (this.f + x))
def run = fn (s: S, o: S?, d: any): int -> (
  var a = 0 ; var i = 0
  while i < 2 then (a += (d as S).m(1) + (if (s is S) and (o?.m(1) != null) then (1) else (0)) ; i += 1) ; a
)
assert(run(S(5), S(5), S(5)) == 14)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, o:obj, d:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (call-method (block (as var(d) babble|S)).m 1:int) (block (if (and (block (is var(s) S)) (block (<>:bool (safe-call var(o)?.m 1:int) null))) (block 1:int) (block 0:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, o:obj, d:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (block
            (def $this (as var(d) babble|S))
            (+:int (get-this-field f) 1:int)) (if (and (is var(s) S) (<>:bool (safe-call var(o)?.m 1:int) null)) 1:int 0:int)))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: any, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: any = const null
    br bb1(v5, v4, v3, v0)
  bb1(v6: any, v7: int, v8: int, v9: any):
    v10: int = const 2
    v11: bool = lt v7, v10 {pure}
    br_if v11, bb3, bb2
  bb2:
    ret v8
  bb3:
    v12: any = checkcast v2 <babble|S>
    v13: int = load_field v12.f {read:shared,throws}
    v14: int = const 1
    v15: int = add v13, v14 {pure}
    v16: bool = is v9 <babble|S>
    v17: bool = const false
    br_if v16, bb4, bb7(v17, v9)
  bb4:
    v18: any = narrow.is v9 <babble|S>
    v19: any = const null
    v20: bool = neq v1, v19 {pure}
    br_if v20, bb5, bb6(v19)
  bb5:
    v21: any = narrow.nonnull v1 <non-null>
    v22: int = const 1
    v23: any = opaque.SafeCall(v21?.m, v22) {impure}
    br bb6(v23)
  bb6(v24: any):
    v25: any = const null
    v26: any = neq v24, v25 {pure,throws}
    v27: bool = not v26 {pure}
    v28: bool = not v27 {pure}
    br bb7(v28, v18)
  bb7(v29: bool, v30: any):
    br_if v29, bb9, bb8
  bb8:
    v31: int = const 0
    br bb10(v31)
  bb9:
    v32: int = const 1
    br bb10(v32)
  bb10(v33: int):
    v34: int = add v15, v33 {pure}
    v35: int = add v8, v34 {pure}
    v36: int = const 1
    v37: int = add v7, v36 {pure}
    v38: any = convert.any v37
    br bb1(v38, v37, v35, v30)
```

```mir
;; mir -O2 fn=run
fn run(v0: any, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: any = const null
    v5: bool = neq v1, v4 {pure}
    br bb1(v3, v3, v0)
  bb1(v6: int, v7: int, v8: any):
    v9: int = const 2
    v10: bool = lt v6, v9 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v7
  bb3:
    v11: any = checkcast v2 <babble|S>
    v12: int = load_field v11.f {read:shared,throws}
    v13: int = const 1
    v14: int = add v12, v13 {pure}
    v15: bool = is v8 <babble|S>
    v16: bool = const false
    br_if v15, bb4, bb7(v16, v8)
  bb4:
    v17: any = narrow.is v8 <babble|S>
    br_if v5, bb5, bb6(v4)
  bb5:
    v18: any = narrow.nonnull v1 <non-null>
    v19: any = opaque.SafeCall(v18?.m, v13) {impure}
    br bb6(v19)
  bb6(v20: any):
    v21: any = neq v20, v4 {pure,throws}
    v22: bool = not v21 {pure}
    v23: bool = not v22 {pure}
    br bb7(v23, v17)
  bb7(v24: bool, v25: any):
    br_if v24, bb9, bb8
  bb8:
    br bb10(v3)
  bb9:
    br bb10(v13)
  bb10(v26: int):
    v27: int = add v14, v26 {pure}
    v28: int = add v7, v27 {pure}
    v29: int = add v6, v13 {pure}
    br bb1(v29, v28, v25)
```

## HirPurity.collectModifiedVars : top-level/this writes, !, generic TypeApply

```babble
var topM = 0 ; topM = 1
def bigId of T = fn nothrow (x: T): T -> (def f = fn (): int -> (0) ; discard(f) ; x)
def MC = class (var mf: int) (
  def run = fn mut io (n: int): int -> (
    var i = 0 ; var s = 0
    while i < n then (
      topM += 1 ; this.mf += 1 ; def os: string? = "ab"
      s += this.mf + os!.length() + (bigId of int)(4) ; i += 1
    ) ; s + topM
  )
)
assert(MC(1).run(2) == 20)
```

```hir
;; hir -O0 fn=MC.run
fn MC.run (n:int) -> int {io mut}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set-name topM += 1:int)
      (set-this-field mf += 1:int)
      (def os "ab")
      (set s += (+:int (+:int (get-this-field mf) (jcall String.length (nonnull var(os)))) (call (block (type-apply name(bigId))) 4:int)))
      (set i += 1:int)))
  (+:int var(s) name(topM))
```

```hir
;; hir -O1 fn=MC.run
fn MC.run (n:int) -> int {io mut}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set-name topM += 1:int)
      (set-this-field mf += 1:int)
      (set s += (+:int (+:int (get-this-field mf) (jcall String.length (nonnull "ab"))) (call (type-apply name(bigId)) 4:int)))
      (set i += 1:int)))
  (+:int var(s) name(topM))
```

```mir
;; mir -O2 fn=MC.run stage=before
fn MC.run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v1, v2)
  bb1(v4: any, v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    v8: any = opaque.NameRead(topM) {read:shared,throws}
    v9: int = convert.int v8
    v10: int = add v6, v9 {pure}
    ret v10
  bb3:
    v11: int = const 1
    v12: any = opaque.NameAssign(topM +=, v11) {impure}
    v13: any = slot_read this {pure}
    v14: int = load_field v13.mf {read:shared,throws}
    v15: int = const 1
    v16: int = add v14, v15 {pure}
    store_field v13.mf, v16 {write:shared,throws}
    v17: any = slot_read this {pure}
    v18: int = load_field v17.mf {read:shared,throws}
    v19: any = const "ab"
    v20: any = nonnull v19
    v21: int = call_java v20.length() {pure,throws}
    v22: int = add v18, v21 {pure}
    v23: any = opaque.NameRead(bigId) {read:shared,throws}
    v24: int = const 4
    v25: int = opaque.DynamicCall(v23, v24) {impure}
    v26: int = add v22, v25 {pure}
    v27: int = add v6, v26 {pure}
    v28: int = const 1
    v29: int = add v5, v28 {pure}
    v30: any = convert.any v29
    br bb1(v30, v29, v27)
```

```mir
;; mir -O2 fn=MC.run
fn MC.run(v0: int) -> int:
  bb0:
    v1: int = const 0
    br bb1(v1, v1)
  bb1(v2: int, v3: int):
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb3, bb2
  bb2:
    v5: any = opaque.NameRead(topM) {read:shared,throws}
    v6: int = convert.int v5
    v7: int = add v3, v6 {pure}
    ret v7
  bb3:
    v8: int = const 1
    v9: any = opaque.NameAssign(topM +=, v8) {impure}
    v10: any = slot_read this {pure}
    v11: int = load_field v10.mf {read:shared,throws}
    v12: int = add v11, v8 {pure}
    store_field v10.mf, v12 {write:shared,throws}
    v13: any = const "ab"
    v14: any = nonnull v13
    v15: int = call_java v14.length() {pure,throws}
    v16: int = add v12, v15 {pure}
    v17: any = opaque.NameRead(bigId) {read:shared,throws}
    v18: int = const 4
    v19: int = opaque.DynamicCall(v17, v18) {impure}
    v20: int = add v16, v19 {pure}
    v21: int = add v3, v20 {pure}
    v22: int = add v2, v8 {pure}
    br bb1(v22, v21)
```

## HirPurity.collectModifiedVars : nested fn, class, interface, StringBuilder, list, and is check inside while

```babble
def run = fn io (): int -> (
  var i = 0 ; var s = 0
  while i < 2 then (
    def sb = java.lang.StringBuilder("x") ; def fnL = fn (z: int): int -> (z + 1)
    def ClsL = class (def v: int) () ; def IfcL = interface () ; def av: any = sb
    s += sb.length() + list(1, 2).size() + fnL(1) + ClsL(3).v + (if av is IfcL then (1) else (0)) ; i += 1
  ) ; s
)
assert(run() == 16)
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def sb (jnew StringBuilder "x"))
      (def fnL (fn-ref fnL))
      (def ClsL (class-ref ClsL))
      (def IfcL (iface-ref IfcL))
      (def av var(sb))
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) (call-fn fnL 1:int)) (get-this-field v)) (block (if (is var(av) babble|IfcL) (block 1:int) (block 0:int)))))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def sb (jnew StringBuilder "x"))
      (def fnL (fn-ref fnL))
      (def ClsL (class-ref ClsL))
      (def IfcL (iface-ref IfcL))
      (def av var(sb))
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) 2:int) (get-this-field v)) (if (is var(av) babble|IfcL) 1:int 0:int)))
      (set i += 1:int)))
  var(s)
```

```mir
;; mir -O2 fn=run stage=before
fn run() -> int:
  bb0:
    v0: int = const 0
    v1: int = const 0
    v2: any = const null
    br bb1(v2, v0, v1)
  bb1(v3: any, v4: int, v5: int):
    v6: int = const 2
    v7: bool = lt v4, v6 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v5
  bb3:
    v8: any = const "x"
    v9: any = new_java StringBuilder(v8) {impure}
    v10: any = opaque.FunctionRef(fnL) {pure}
    v11: any = opaque.ClassExpr(ClsL) {impure}
    v12: any = opaque.InterfaceExpr(IfcL) {impure}
    v13: int = call_java v9.length() {pure,throws}
    v14: int = const 1
    v15: int = const 2
    v16: any = opaque.CoreCollectionCall(list, v14, v15) {impure}
    v17: int = call_java v16.size() {pure,throws}
    v18: int = add v13, v17 {pure}
    v19: int = const 2
    v20: int = add v18, v19 {pure}
    v21: int = const 3
    v22: any = opaque.DynamicCall(v11, v21) {impure}
    v23: int = load_field v22.v {read:shared,throws}
    v24: int = add v20, v23 {pure}
    v25: bool = is v9 <babble|IfcL>
    br_if v25, bb5, bb4
  bb4:
    v26: int = const 0
    br bb6(v26)
  bb5:
    v27: any = narrow.is v9 <babble|IfcL>
    v28: int = const 1
    br bb6(v28)
  bb6(v29: int):
    v30: int = add v24, v29 {pure}
    v31: int = add v5, v30 {pure}
    v32: int = const 1
    v33: int = add v4, v32 {pure}
    v34: any = convert.any v33
    br bb1(v34, v33, v31)
```

```mir
;; mir -O2 fn=run
fn run() -> int:
  bb0:
    v0: int = const 0
    br bb1(v0, v0)
  bb1(v1: int, v2: int):
    v3: int = const 2
    v4: bool = lt v1, v3 {pure}
    br_if v4, bb3, bb2
  bb2:
    ret v2
  bb3:
    v5: any = const "x"
    v6: any = new_java StringBuilder(v5) {impure}
    v7: any = opaque.ClassExpr(ClsL) {impure}
    v8: any = opaque.InterfaceExpr(IfcL) {impure}
    v9: int = call_java v6.length() {pure,throws}
    v10: int = const 1
    v11: any = opaque.CoreCollectionCall(list, v10, v3) {impure}
    v12: int = call_java v11.size() {pure,throws}
    v13: int = add v9, v12 {pure}
    v14: int = add v13, v3 {pure}
    v15: int = const 3
    v16: any = opaque.DynamicCall(v7, v15) {impure}
    v17: int = load_field v16.v {read:shared,throws}
    v18: int = add v14, v17 {pure}
    v19: bool = is v6 <babble|IfcL>
    br_if v19, bb5, bb4
  bb4:
    br bb6(v0)
  bb5:
    br bb6(v10)
  bb6(v20: int):
    v21: int = add v18, v20 {pure}
    v22: int = add v2, v21 {pure}
    v23: int = add v1, v10 {pure}
    br bb1(v23, v22)
```
