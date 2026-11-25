# Bytecode Lowering : Negation, Widening, and Compound Assigns

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble
;; babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
```



## Unary negation on int, double, and int | double union

```babble
def neg = fn (a : int, b : double, c : int | double) -> (
    assert(-a == -4)
    assert(-b == -1.5)
    assert(-c == -9)
)
neg(4, 1.5, 9)
```

```hir
;; hir -O0,-O1 fn=neg
fn neg (a:int, b:double, c:obj) -> bool {pure unique}
  (call name(assert) (==:bool (neg var(a)) -4:int))
  (call name(assert) (==:bool (neg var(b)) -1.5:double))
  (call name(assert) (==:bool (neg var(c)) -9:int))
```

```mir
;; mir -O2 fn=neg stage=before
fn neg(v0: int, v1: double, v2: any) -> bool:
  bb0:
    v3: any = opaque.NameRead() {impure}
    v4: int = neg v0 {pure}
    v5: int = const -4
    v6: bool = eq v4, v5 {pure}
    v7: bool = opaque.DynamicCall(v3, v6) {impure}
    v8: any = opaque.NameRead() {impure}
    v9: double = neg v1 {pure}
    v10: double = const -1.5
    v11: bool = eq v9, v10 {pure}
    v12: bool = opaque.DynamicCall(v8, v11) {impure}
    v13: any = opaque.NameRead() {impure}
    v14: any = neg v2 {impure}
    v15: int = const -9
    v16: any = convert.any v15
    v17: any = eq v14, v16 {pure,throws}
    v18: bool = opaque.DynamicCall(v13, v17) {impure}
    ret v18
```

```mir
;; mir -O2 fn=neg
fn neg(v0: int, v1: double, v2: any) -> bool:
  bb0:
    v3: any = opaque.NameRead() {impure}
    v4: int = neg v0 {pure}
    v5: int = const -4
    v6: bool = eq v4, v5 {pure}
    v7: bool = opaque.DynamicCall(v3, v6) {impure}
    v8: any = opaque.NameRead() {impure}
    v9: double = neg v1 {pure}
    v10: double = const -1.5
    v11: bool = eq v9, v10 {pure}
    v12: bool = opaque.DynamicCall(v8, v11) {impure}
    v13: any = opaque.NameRead() {impure}
    v14: any = neg v2 {impure}
    v15: any = const -9
    v16: any = eq v14, v15 {pure,throws}
    v17: bool = opaque.DynamicCall(v13, v16) {impure}
    ret v17
```

## Widening return, as-cast, block/while double widening, and methodless object

```babble
def wf1 = fn () : double -> ( 1 )
def wf2 = fn (flag : bool) : double -> ( if flag then ( 1 ) else ( 2.5 ) )
def k = 0
def wd : double? = while k < 2 then ( k += 1 ; 42 )
def bd : double = ( def t = 3 ; t )
def obj = object ( def k : int = 7 )
assert(wf1() == 1.0 and wf2(true) == 1.0 and (1 as double) == 1.0 and ((1 + 2) as double) == 3.0)
assert(wd == 42.0 and bd == 3.0 and obj.k == 7)
```

```hir
;; hir -O0,-O1 fn=wf1
fn wf1 () -> double {nothrow unique}
  1:int
```

```hir
;; hir -O0 fn=wf2
fn wf2 (flag:bool) -> double {nothrow}
  (if var(flag) (block 1:int) (block 2.5:double))
```

```hir
;; hir -O1 fn=wf2
fn wf2 (flag:bool) -> double {nothrow}
  (if var(flag) (block 1:int) 2.5:double)
```

```mir
;; mir -O2 fn=wf2 stage=before
fn wf2(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: double = const 2.5
    br bb3(v1)
  bb2:
    v2: int = const 1
    v3: double = convert.double v2
    br bb3(v3)
  bb3(v4: double):
    ret v4
```

```mir
;; mir -O2 fn=wf2
fn wf2(v0: bool) -> double:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: double = const 2.5
    br bb3(v1)
  bb2:
    v2: double = const 1.0
    br bb3(v2)
  bb3(v3: double):
    ret v3
```

## Widening assignments on local and Babble int field

```babble
def x = 1
def b = (class () ( def v : int = 2 ))()
def d1 : double = (x = 5) ; def d2 : double = (x += 3)
def d3 : double = (b.v = 7) ; def d4 : double = (b.v += 2)
def d5 : double = (b.v -= 1) ; def d6 : double = (b.v *= 2)
assert(d1 == 5.0 and d2 == 8.0 and d3 == 7.0 and d4 == 9.0 and d5 == 8.0 and d6 == 16.0)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 1:int)
  (def b (call (block (class-ref class))))
  (def d1 (block (set-name x 5:int)))
  (def d2 (block (set-name x += 3:int)))
  (def d3 (block (set-field name(b).v 7:int)))
  (def d4 (block (set-field name(b).v 2:int)))
  (def d5 (block (set-field name(b).v 1:int)))
  (def d6 (block (set-field name(b).v 2:int)))
  (call name(assert) (and (and (and (and (and (==:bool name(d1) 5.0:double) (==:bool name(d2) 8.0:double)) (==:bool name(d3) 7.0:double)) (==:bool name(d4) 9.0:double)) (==:bool name(d5) 8.0:double)) (==:bool name(d6) 16.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 1:int)
  (def b (call (class-ref class)))
  (def d1 (set-name x 5:int))
  (def d2 (set-name x += 3:int))
  (def d3 (set-field name(b).v 7:int))
  (def d4 (set-field name(b).v 2:int))
  (def d5 (set-field name(b).v 1:int))
  (def d6 (set-field name(b).v 2:int))
  (call name(assert) (and (and (and (and (and (==:bool name(d1) 5.0:double) (==:bool name(d2) 8.0:double)) (==:bool name(d3) 7.0:double)) (==:bool name(d4) 9.0:double)) (==:bool name(d5) 8.0:double)) (==:bool name(d6) 16.0:double)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Widening assignments on Java int field

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("a"))
def d7 : double = (st.ttype = 4)
def d8 : double = (st.ttype += 2)
assert(d7 == 4.0 and d8 == 6.0)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "a")))
  (def d7 (block (set-jfield ttype name(st) 4:int)))
  (def d8 (block (set-jfield ttype name(st) 2:int)))
  (call name(assert) (and (==:bool name(d7) 4.0:double) (==:bool name(d8) 6.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "a")))
  (def d7 (set-jfield ttype name(st) 4:int))
  (def d8 (set-jfield ttype name(st) 2:int))
  (call name(assert) (and (==:bool name(d7) 4.0:double) (==:bool name(d8) 6.0:double)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Boxed/fallback compound assignments on union local and instance fields

```babble
def C = class (def n : int, def d : double) (
    def run = fn mut () -> (
        def loc : int | double = 9
        loc /= 2 ; loc += dynNum(2)
        this.n *= 2 ; this.n -= 1
        this.d /= 2 ; this.d += dynNum(2)
        assert(loc == 6.5 and this.n == 17 and this.d == 5.0)
    )
)
C(9, 6.0).run()
```

```hir
;; hir -O0 fn=C.run
fn C.run () -> bool {mut unique}
  (def loc 9:int)
  (set loc /= 2:int)
  (set loc += (call-fn dynNum 2:int))
  (set-this-field n *= 2:int)
  (set-this-field n -= 1:int)
  (set-this-field d /= 2:int)
  (set-this-field d += (call-fn dynNum 2:int))
  (call name(assert) (and (and (==:bool var(loc) 6.5:double) (==:bool (get-this-field n) 17:int)) (==:bool (get-this-field d) 5.0:double)))
```

```hir
;; hir -O1 fn=C.run
fn C.run () -> bool {mut unique}
  (def loc 9:int)
  (set loc /= 2:int)
  (set loc +=
    (block
      (def v#1 2:int)
      var(v#1)))
  (set-this-field n *= 2:int)
  (set-this-field n -= 1:int)
  (set-this-field d /= 2:int)
  (set-this-field d += (block
      (def v#2 2:int)
      var(v#2)))
  (call name(assert) (and (and (==:bool var(loc) 6.5:double) (==:bool (get-this-field n) 17:int)) (==:bool (get-this-field d) 5.0:double)))
```

```mir
;; mir -O2 fn=C.run stage=before
fn C.run() -> bool:
  bb0:
    v0: int = const 9
    v1: any = convert.any v0
    v2: int = const 2
    v3: any = convert.any v2
    v4: any = div v1, v3 {impure}
    v5: int = const 2
    v6: any = convert.any v5
    v7: any = add v4, v6 {impure}
    v8: any = slot_read this {pure}
    v9: int = load_field v8.n {read:shared,throws}
    v10: int = const 2
    v11: int = mul v9, v10 {pure}
    store_field v8.n, v11 {write:shared,throws}
    v12: any = slot_read this {pure}
    v13: int = load_field v12.n {read:shared,throws}
    v14: int = const 1
    v15: int = sub v13, v14 {pure}
    store_field v12.n, v15 {write:shared,throws}
    v16: any = slot_read this {pure}
    v17: double = load_field v16.d {read:shared,throws}
    v18: int = const 2
    v19: double = convert.double v18
    v20: double = div v17, v19 {pure}
    store_field v16.d, v20 {write:shared,throws}
    v21: any = slot_read this {pure}
    v22: double = load_field v21.d {read:shared,throws}
    v23: any = convert.any v22
    v24: int = const 2
    v25: any = convert.any v24
    v26: any = add v23, v25 {impure}
    v27: double = convert.double v26
    store_field v21.d, v27 {write:shared,throws}
    v28: any = opaque.NameRead() {impure}
    v29: double = const 6.5
    v30: any = convert.any v29
    v31: any = eq v7, v30 {pure,throws}
    v32: bool = not v31 {pure}
    v33: bool = not v32 {pure}
    v34: bool = const false
    br_if v33, bb1, bb2(v34)
  bb1:
    v35: any = slot_read this {pure}
    v36: int = load_field v35.n {read:shared,throws}
    v37: int = const 17
    v38: bool = eq v36, v37 {pure}
    br bb2(v38)
  bb2(v39: bool):
    v40: bool = const false
    br_if v39, bb3, bb4(v40)
  bb3:
    v41: any = slot_read this {pure}
    v42: double = load_field v41.d {read:shared,throws}
    v43: double = const 5.0
    v44: bool = eq v42, v43 {pure}
    br bb4(v44)
  bb4(v45: bool):
    v46: bool = opaque.DynamicCall(v28, v45) {impure}
    ret v46
```

```mir
;; mir -O2 fn=C.run
fn C.run() -> bool:
  bb0:
    v0: any = const 9
    v1: int = const 2
    v2: any = const 2
    v3: any = div v0, v2 {impure}
    v4: any = add v3, v2 {impure}
    v5: any = slot_read this {pure}
    v6: int = load_field v5.n {read:shared,throws}
    v7: int = mul v6, v1 {pure}
    store_field v5.n, v7 {write:shared,throws}
    v8: int = const 1
    v9: int = sub v7, v8 {pure}
    store_field v5.n, v9 {write:shared,throws}
    v10: double = load_field v5.d {read:shared,throws}
    v11: double = const 2.0
    v12: double = div v10, v11 {pure}
    store_field v5.d, v12 {write:shared,throws}
    v13: any = convert.any v12
    v14: any = add v13, v2 {impure}
    v15: double = convert.double v14
    store_field v5.d, v15 {write:shared,throws}
    v16: any = opaque.NameRead() {impure}
    v17: any = const 6.5
    v18: any = eq v4, v17 {pure,throws}
    v19: bool = not v18 {pure}
    v20: bool = not v19 {pure}
    v21: bool = const false
    br_if v20, bb1, bb2(v21)
  bb1:
    v22: int = load_field v5.n {read:shared,throws}
    v23: int = const 17
    v24: bool = eq v22, v23 {pure}
    br bb2(v24)
  bb2(v25: bool):
    br_if v25, bb3, bb4(v21)
  bb3:
    v26: double = load_field v5.d {read:shared,throws}
    v27: double = const 5.0
    v28: bool = eq v26, v27 {pure}
    br bb4(v28)
  bb4(v29: bool):
    v30: bool = opaque.DynamicCall(v16, v29) {impure}
    ret v30
```

## Boxed/fallback compound assignment on static union field

```babble
def StaticBox = class () () static (
    def sx : int | double = 9
    def go = fn io () : int | double -> ( this.sx /= 2 ; this.sx )
)
assert(StaticBox.go() == 4.5)
```

```hir
;; hir -O0,-O1 fn=go
fn go () -> obj {io}
  (set-this-field sx /= 2:int)
  (get-this-field sx)
```

```mir
;; mir -O2 fn=go stage=before
fn go() -> any:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = load_field v0.sx {read:shared,throws}
    v2: int = const 2
    v3: any = convert.any v2
    v4: any = div v1, v3 {impure}
    store_field v0.sx, v4 {write:shared,throws}
    v5: any = slot_read this {pure}
    v6: any = load_field v5.sx {read:shared,throws}
    ret v6
```

```mir
;; mir -O2 fn=go
fn go() -> any:
  bb0:
    v0: any = slot_read this {pure}
    v1: any = load_field v0.sx {read:shared,throws}
    v2: any = const 2
    v3: any = div v1, v2 {impure}
    store_field v0.sx, v3 {write:shared,throws}
    v4: any = load_field v0.sx {read:shared,throws}
    ret v4
```

## Java long/float arg lowering, paren-wrapped static chain, and static field on non-pure Java receiver

```babble
def mkSt = fn io () : java.io.StreamTokenizer -> ( java.io.StreamTokenizer(java.io.StringReader("hi")) )
assert(java.lang.Long.sum(3, 4) == 7)
assert(java.lang.Float.sum(3, 4) == 7.0)
assert((java.lang.Math).abs(-5) == 5)
assert((java.lang.Math).abs(-9) == 9)
assert(mkSt().TT_EOF == -1)
```

```hir
;; hir -O0,-O1 fn=mkSt
fn mkSt () -> obj {io}
  (jnew StreamTokenizer (jnew StringReader "hi"))
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def mkSt (fn-ref mkSt))
  (call name(assert) (==:bool (jcall Long.sum 3:long 4:long) 7:int))
  (call name(assert) (==:bool (jcall Float.sum 3:int 4:int) 7.0:double))
  (call name(assert) (==:bool (jcall Math.abs -5:int) 5:int))
  (call name(assert) (==:bool (jcall Math.abs -9:int) 9:int))
  (call name(assert) (==:bool (get-jfield StreamTokenizer.TT_EOF (call-fn mkSt)) -1:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def mkSt (fn-ref mkSt))
  (call name(assert) (==:bool (jcall Long.sum 3:long 4:long) 7:int))
  (call name(assert) (==:bool (jcall Float.sum 3.0:float 4.0:float) 7.0:double))
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) (==:bool (get-jfield StreamTokenizer.TT_EOF (call-fn mkSt)) -1:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```
