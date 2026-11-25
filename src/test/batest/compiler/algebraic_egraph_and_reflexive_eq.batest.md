# Algebraic E-Graph Rewrites and Reflexive Equality

References : BIP014-I2, org.babblelang.opt.hir.AlgebraicSimplifyPass

```babble
;; babble preamble
def clamp = fn axiom pure nothrow idempotent (x: int): int -> (if x < 0 then (0) else (x))

;; Non-trailing return prevents InlinePass from inlining bigClamp in round 1 while staying pure/nothrow
def bigClamp = fn axiom pure nothrow idempotent (x: int): int -> (
    def r = if x < 0 then (0) else (x)
    return r
    r
)

def tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
```


## Canonical HIR golden : algebraic identities and integer reassociation (-O0 vs -O1)

```babble
def simplify = fn (x: int): int -> ( (x + 0) * 1 + (x - x) )
def reassoc = fn (x: int): int -> ( (x + 1) + 2 )
assert(simplify(7) == 7 and reassoc(5) == 8)
```

```hir
;; hir -O0 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  (+:int (*:int (block (+:int var(x) 0:int)) 1:int) (block (-:int var(x) var(x))))
```

```hir
;; hir -O1 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  var(x)
```

```hir
;; hir -O1 fn=reassoc
fn reassoc (x:int) -> int {nothrow injective unique}
  (+:int var(x) 3:int)
```

```mir
;; mir -O2 fn=reassoc stage=before,final
fn reassoc(v0: int) -> int:
  bb0:
    v1: int = const 3
    v2: int = add v0, v1 {pure}
    ret v2
```

## Canonical HIR golden : idempotent collapse and stage=after:HirEGraphSimplify

```babble
def norm = fn (x: int): int -> (
    bigClamp(bigClamp(java.lang.Math.abs(java.lang.Math.abs(x))))
)
def clean = fn (s: string): string -> ( s.trim().trim() )
assert(norm(-5) == 5 and norm(3) == 3 and clean("  ok  ") == "ok")
```

```hir
;; hir -O0 fn=norm
fn norm (x:int) -> int {nothrow unique}
  (call-fn bigClamp (call-fn bigClamp (jcall Math.abs (jcall Math.abs var(x)))))
```

```hir
;; hir -O1 fn=norm stage=after:HirEGraphSimplify
fn norm (x:int) -> int {nothrow unique}
  (block
    (def x#2 (jcall Math.abs var(x#1)))
    (def r (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
    var(r))
```

```hir
;; hir -O1 fn=norm
fn norm (x:int) -> int {nothrow unique}
  (block
    (def x#2 (jcall Math.abs var(x#1)))
    (def r (if (<:bool var(x#2) 0:int) 0:int var(x#2)))
    var(r))
```

```hir
;; hir -O0 fn=clean
fn clean (s:obj) -> obj {nothrow idempotent unique}
  (jcall String.trim (jcall String.trim var(s)))
```

```hir
;; hir -O1 fn=clean
fn clean (s:obj) -> obj {nothrow idempotent unique}
  (jcall String.trim var(s))
```

```mir
;; mir -O2 fn=clean stage=before,final
fn clean(v0: any) -> any:
  bb0:
    v1: any = call_java v0.trim() {impure}
    v2: any = checkcast v1 <string|string|>
    ret v2
```

## Canonical HIR golden : strict IEEE-754 double preservation (-O0,-O1)

```babble
def add3 = fn (x: double): double -> ( x + 1.0 + 2.0 )
assert(add3(1.0) == 4.0 and java.lang.Double.isNaN(add3(java.lang.Double.NaN)))
```

```hir
;; hir -O0,-O1 fn=add3
fn add3 (x:double) -> double {nothrow monotone unique}
  (+:double (+:double var(x) 1.0:double) 2.0:double)
```

```mir
;; mir -O2 fn=add3 stage=before,final
fn add3(v0: double) -> double:
  bb0:
    v1: double = const 1.0
    v2: double = add v0, v1 {pure}
    v3: double = const 2.0
    v4: double = add v2, v3 {pure}
    ret v4
```

## Double integer negation and byte/short unary negation in E-graph

```babble
def dblNegI = fn (x: int): int -> ( -(-x) )
def dblNegL = fn (x: long): long -> ( -(-x) )
def negNarrow = fn (b: byte, s: short): int -> ( ((-b) + 0) + ((-s) + 0) )
assert(dblNegI(7) == 7 and dblNegL(-9L) == -9L and negNarrow(byte(3), short(4)) == -7)
```

```hir
;; hir -O0 fn=dblNegI
fn dblNegI (x:int) -> int {nothrow idempotent injective unique}
  (neg (block (neg var(x))))
```

```hir
;; hir -O1 fn=dblNegI
fn dblNegI (x:int) -> int {nothrow idempotent injective unique}
  var(x)
```

```hir
;; hir -O0 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (+:int (block (+:int (block (neg var(b))) 0:int)) (block (+:int (block (neg var(s))) 0:int)))
```

```hir
;; hir -O1 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (+:int (neg var(b)) (neg var(s)))
```

```mir
;; mir -O2 fn=negNarrow stage=before,final
fn negNarrow(v0: byte, v1: short) -> int:
  bb0:
    v2: int = convert.int v0
    v3: int = neg v2 {pure}
    v4: int = convert.int v1
    v5: int = neg v4 {pure}
    v6: int = add v3, v5 {pure}
    ret v6
```

## E-graph rebuild of boolean EQ/NEQ and non-static String.trim receiver

```babble
def eqSimp = fn (a: bool, b: bool): bool -> ( (not (not a)) == b )
def neqSimp = fn (a: bool, b: bool): bool -> ( (not (not a)) != b )
def trimCat = fn (s: string): string -> ( ((s + "a") + "b").trim() )
assert(eqSimp(true, true) and neqSimp(true, false) and trimCat("  x") == "xab")
```

```hir
;; hir -O0 fn=eqSimp
fn eqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (==:bool (block (not (block (not var(a))))) var(b))
```

```hir
;; hir -O1 fn=eqSimp
fn eqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (==:bool var(a) var(b))
```

```hir
;; hir -O0 fn=neqSimp
fn neqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (<>:bool (block (not (block (not var(a))))) var(b))
```

```hir
;; hir -O1 fn=neqSimp
fn neqSimp (a:bool, b:bool) -> bool {nothrow commutative unique}
  (<>:bool var(a) var(b))
```

```hir
;; hir -O0 fn=trimCat
fn trimCat (s:obj) -> obj {nothrow unique}
  (jcall String.trim (block (+:obj (block (+:obj var(s) "a")) "b")))
```

```hir
;; hir -O1 fn=trimCat
fn trimCat (s:obj) -> obj {nothrow unique}
  (jcall String.trim (+:obj var(s) "ab"))
```

```mir
;; mir -O2 fn=trimCat stage=before,final
fn trimCat(v0: any) -> any:
  bb0:
    v1: any = const "ab"
    v2: any = strcat v0, v1 {pure}
    v3: any = call_java v2.trim() {impure}
    v4: any = checkcast v3 <string|string|>
    ret v4
```

## Non-dropping identities on impure bool double negation and long arithmetic

```babble
def bumpBool = fn io (): bool -> (tick += 1 ; true)
def bumpOpt = fn io (): int? -> (tick += 1 ; 1)
def bumpL = fn io (): long -> (tick += 1 ; 2L)
def b1 = not (not bumpBool())
def b2 = not (not bumpOpt())
def b3 = not (-bump() == 0)
def lv = (bumpL() + 0L) + (0L + bumpL()) + (bumpL() - 0L) + (0L - bumpL()) + (bumpL() * 1L) + (1L * bumpL())
assert(b1 and b2 and b3 and lv == 8L and tick == 9)
```

```hir
;; hir -O0 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpBool (fn-ref bumpBool))
  (def bumpOpt (fn-ref bumpOpt))
  (def bumpL (fn-ref bumpL))
  (def b1 (not (block (not (call-fn bumpBool)))))
  (def b2 (not (block (not (call-fn bumpOpt)))))
  (def b3 (not (block (==:bool (neg (call-fn bump)) 0:int))))
  (def lv (+:long (+:long (+:long (+:long (+:long (block (+:long (call-fn bumpL) 0:long)) (block (+:long 0:long (call-fn bumpL)))) (block (-:long (call-fn bumpL) 0:long))) (block (-:long 0:long (call-fn bumpL)))) (block (*:long (call-fn bumpL) 1:long))) (block (*:long 1:long (call-fn bumpL)))))
  (call name(assert) (and (and (and (and name(b1) name(b2)) name(b3)) (==:bool name(lv) 8:long)) (==:bool name(tick) 9:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def clamp (fn-ref clamp))
  (def bigClamp (fn-ref bigClamp))
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bumpBool (fn-ref bumpBool))
  (def bumpOpt (fn-ref bumpOpt))
  (def bumpL (fn-ref bumpL))
  (def b1 (call-fn bumpBool))
  (def b2 (not (not (call-fn bumpOpt))))
  (def b3 (<>:bool (neg (call-fn bump)) 0:int))
  (def lv (+:long (+:long (+:long (+:long (+:long (call-fn bumpL) (call-fn bumpL)) (call-fn bumpL)) (neg (call-fn bumpL))) (call-fn bumpL)) (call-fn bumpL)))
  (call name(assert) (and (and (and (and name(b1) name(b2)) name(b3)) (==:bool name(lv) 8:long)) (==:bool name(tick) 9:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Idempotent Java eligibility guards : axiom String.toUpperCase and String.toLowerCase with arg

```babble
axiom java.lang.String.toUpperCase: fn pure nothrow idempotent
axiom java.lang.String.toLowerCase: fn pure nothrow idempotent
def f = fn (s: string): bool -> (
    s.toUpperCase().toUpperCase() == "AB" and s.toLowerCase(java.util.Locale.ROOT) == "ab"
)
assert(f("Ab"))
```

```hir
;; hir -O0,-O1 fn=f
fn f (s:obj) -> bool {pure unique}
  (and (==:bool (jcall String.toUpperCase (jcall String.toUpperCase var(s))) "AB") (==:bool (jcall String.toLowerCase var(s) (get-jfield Locale.ROOT)) "ab"))
```

```mir
;; mir -O2 fn=f stage=before,final
fn f(v0: any) -> bool:
  bb0:
    v1: any = call_java v0.toUpperCase() {impure}
    v2: any = checkcast v1 <string|string|>
    v3: any = call_java v2.toUpperCase() {impure}
    v4: any = checkcast v3 <string|string|>
    v5: any = const "AB"
    v6: any = eq v4, v5 {pure}
    v7: bool = not v6 {pure}
    v8: bool = not v7 {pure}
    v9: bool = const false
    br_if v8, bb1, bb2(v9)
  bb1:
    v10: any = load_jfield Locale.ROOT {read:frozen}
    v11: any = checkcast v10 <java|java.util.Locale|java.util.Locale>
    v12: any = call_java v0.toLowerCase(v11) {impure}
    v13: any = checkcast v12 <string|string|>
    v14: any = const "ab"
    v15: any = eq v13, v14 {pure}
    v16: bool = not v15 {pure}
    v17: bool = not v16 {pure}
    br bb2(v17)
  bb2(v18: bool):
    ret v18
```

## Mismatched nested static and instance idempotent Java calls on impure args

```babble
axiom java.lang.String.toUpperCase: fn pure nothrow idempotent
def bumpD = fn io (): double -> (tick += 1 ; -2.5)
def bumpS = fn io (): string -> (tick += 1 ; "  ab ")
def f = fn io (): bool -> (
    java.lang.Math.abs(java.lang.Math.floor(bumpD())) == 3.0 and bumpS().toUpperCase().trim() == "AB"
)
assert(f() and tick == 2)
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> bool {io}
  (and (==:bool (jcall Math.abs (jcall Math.floor (call-fn bumpD))) 3.0:double) (==:bool (jcall String.trim (jcall String.toUpperCase (call-fn bumpS))) "AB"))
```

```mir
;; mir -O2 fn=f stage=before,final
fn f() -> bool:
  bb0:
    v0: any = opaque.NameRead(bumpD) {read:shared}
    v1: double = call bumpD() {impure}
    v2: double = call_java Math.floor(v1) {pure}
    v3: double = call_java Math.abs(v2) {pure}
    v4: double = const 3.0
    v5: bool = eq v3, v4 {pure}
    v6: bool = const false
    br_if v5, bb1, bb2(v6)
  bb1:
    v7: any = opaque.NameRead(bumpS) {read:shared}
    v8: any = call bumpS() {impure}
    v9: any = call_java v8.toUpperCase() {impure}
    v10: any = checkcast v9 <string|string|>
    v11: any = call_java v10.trim() {impure}
    v12: any = checkcast v11 <string|string|>
    v13: any = const "AB"
    v14: any = eq v12, v13 {pure}
    v15: bool = not v14 {pure}
    v16: bool = not v15 {pure}
    br bb2(v16)
  bb2(v17: bool):
    ret v17
```

## Widened E-graph roots and children inside idempotent Babble calls

```babble
def bigClampD = fn axiom pure nothrow idempotent (v: double): double -> (
    def r = if v < 0.0 then (0.0) else (v) ; return r ; r
)
def f = fn (x: int, y: int): double -> (
    ((-x) + 1.0) + ((x + y) + 1.0) + (bigClamp(x) + 1.0) +
    bigClampD(x) + bigClampD(-x) + bigClampD(x + y)
)
assert(f(2, 3) == 15.0)
```

```hir
;; hir -O0 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (+:double (+:double (+:double (+:double (+:double (block (+:double (block (neg var(x))) 1.0:double)) (block (+:double (block (+:int var(x) var(y))) 1.0:double))) (block (+:double (call-fn bigClamp var(x)) 1.0:double))) (call-fn bigClampD var(x))) (call-fn bigClampD (neg var(x)))) (call-fn bigClampD (+:int var(x) var(y))))
```

```hir
;; hir -O1 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (+:double (+:double (+:double (+:double (+:double (+:double (neg var(x)) 1.0:double) (+:double (+:int var(x) var(y)) 1.0:double)) (+:double (block
              (def r#1 (if (<:bool var(x) 0:int) 0:int var(x)))
              var(r#1)) 1.0:double)) (block
          (def v#1 var(x))
          (def r#2 (if (<:bool var(v#1) 0.0:double) 0.0:double var(v#1)))
          var(r#2))) (block
        (def v#2 (neg var(x)))
        (def r#3 (if (<:bool var(v#2) 0.0:double) 0.0:double var(v#2)))
        var(r#3))) (block
      (def v#3 (+:int var(x) var(y)))
      (def r#4 (if (<:bool var(v#3) 0.0:double) 0.0:double var(v#3)))
      var(r#4)))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: int, v1: int) -> double:
  bb0:
    v2: int = neg v0 {pure}
    v3: double = convert.double v2
    v4: double = const 1.0
    v5: double = add v3, v4 {pure}
    v6: int = add v0, v1 {pure}
    v7: double = convert.double v6
    v8: double = const 1.0
    v9: double = add v7, v8 {pure}
    v10: double = add v5, v9 {pure}
    v11: int = const 0
    v12: bool = lt v0, v11 {pure}
    br_if v12, bb2, bb1
  bb1:
    br bb3(v0)
  bb2:
    v13: int = const 0
    br bb3(v13)
  bb3(v14: int):
    v15: double = convert.double v14
    v16: double = const 1.0
    v17: double = add v15, v16 {pure}
    v18: double = add v10, v17 {pure}
    v19: double = convert.double v0
    v20: double = const 0.0
    v21: bool = lt v19, v20 {pure}
    br_if v21, bb5, bb4
  bb4:
    br bb6(v19)
  bb5:
    v22: double = const 0.0
    br bb6(v22)
  bb6(v23: double):
    v24: double = add v18, v23 {pure}
    v25: int = neg v0 {pure}
    v26: double = convert.double v25
    v27: double = const 0.0
    v28: bool = lt v26, v27 {pure}
    br_if v28, bb8, bb7
  bb7:
    br bb9(v26)
  bb8:
    v29: double = const 0.0
    br bb9(v29)
  bb9(v30: double):
    v31: double = add v24, v30 {pure}
    v32: int = add v0, v1 {pure}
    v33: double = convert.double v32
    v34: double = const 0.0
    v35: bool = lt v33, v34 {pure}
    br_if v35, bb11, bb10
  bb10:
    br bb12(v33)
  bb11:
    v36: double = const 0.0
    br bb12(v36)
  bb12(v37: double):
    v38: double = add v31, v37 {pure}
    ret v38
```

```mir
;; mir -O2 fn=f
fn f(v0: int, v1: int) -> double:
  bb0:
    v2: int = neg v0 {pure}
    v3: double = convert.double v2
    v4: double = const 1.0
    v5: double = add v3, v4 {pure}
    v6: int = add v0, v1 {pure}
    v7: double = convert.double v6
    v8: double = add v7, v4 {pure}
    v9: double = add v5, v8 {pure}
    v10: int = const 0
    v11: bool = lt v0, v10 {pure}
    br_if v11, bb2, bb1
  bb1:
    br bb3(v0)
  bb2:
    br bb3(v10)
  bb3(v12: int):
    v13: double = convert.double v12
    v14: double = add v13, v4 {pure}
    v15: double = add v9, v14 {pure}
    v16: double = convert.double v0
    v17: double = const 0.0
    v18: bool = lt v16, v17 {pure}
    br_if v18, bb5, bb4
  bb4:
    br bb6(v16)
  bb5:
    br bb6(v17)
  bb6(v19: double):
    v20: double = add v15, v19 {pure}
    v21: bool = lt v3, v17 {pure}
    br_if v21, bb8, bb7
  bb7:
    br bb9(v3)
  bb8:
    br bb9(v17)
  bb9(v22: double):
    v23: double = add v20, v22 {pure}
    v24: bool = lt v7, v17 {pure}
    br_if v24, bb11, bb10
  bb10:
    br bb12(v7)
  bb11:
    br bb12(v17)
  bb12(v25: double):
    v26: double = add v23, v25 {pure}
    ret v26
```

## Reflexive equality (a == a -> true, a != a -> false) on primitives (including NaN) and strings

```babble
def reflEq = fn (b: bool, x: int, d: double, f: float, s: string): bool -> (
    (b == b) and not (b != b) and ((x + 1) == (1 + x)) and not (x != x) and
    (d == d) and not (d != d) and (f == f) and not (f != f) and
    (s == s) and not (((s + "a") + "b") != (s + "ab"))
)
assert(reflEq(false, 7, java.lang.Double.NaN, java.lang.Float.NaN, "hi"))
```

```hir
;; hir -O0 fn=reflEq
fn reflEq (b:bool, x:int, d:double, f:float, s:obj) -> bool {nothrow unique}
  (and (and (and (and (and (and (and (and (and (block (==:bool var(b) var(b))) (not (block (<>:bool var(b) var(b))))) (block (==:bool (block (+:int var(x) 1:int)) (block (+:int 1:int var(x)))))) (not (block (<>:bool var(x) var(x))))) (block (==:bool var(d) var(d)))) (not (block (<>:bool var(d) var(d))))) (block (==:bool var(f) var(f)))) (not (block (<>:bool var(f) var(f))))) (block (==:bool var(s) var(s)))) (not (block (<>:bool (block (+:obj (block (+:obj var(s) "a")) "b")) (block (+:obj var(s) "ab"))))))
```

```hir
;; hir -O1 fn=reflEq
fn reflEq (b:bool, x:int, d:double, f:float, s:obj) -> bool {nothrow unique}
  true:bool
```

```mir
;; mir -O2 fn=reflEq stage=before,final
fn reflEq(v0: bool, v1: int, v2: double, v3: float, v4: any) -> bool:
  bb0:
    v5: bool = const true
    ret v5
```

## Reflexive equality must not rewrite arbitrary any or impure operands

```babble
def checkNoRefl = fn io (a: any): bool -> (
    (a == a) and not (a != a) and not (bump() == bump()) and (bump() != bump())
)
assert(checkNoRefl(java.lang.Object()) and tick == 4)
```

```hir
;; hir -O0 fn=checkNoRefl
fn checkNoRefl (a:obj) -> bool {io}
  (and (and (and (block (==:bool var(a) var(a))) (not (block (<>:bool var(a) var(a))))) (not (block (==:bool (call-fn bump) (call-fn bump))))) (block (<>:bool (call-fn bump) (call-fn bump))))
```

```hir
;; hir -O1 fn=checkNoRefl
fn checkNoRefl (a:obj) -> bool {io}
  (and (and (and (==:bool var(a) var(a)) (==:bool var(a) var(a))) (<>:bool (call-fn bump) (call-fn bump))) (<>:bool (call-fn bump) (call-fn bump)))
```

```mir
;; mir -O2 fn=checkNoRefl stage=before
fn checkNoRefl(v0: any) -> bool:
  bb0:
    v1: any = eq v0, v0 {pure,throws}
    v2: bool = not v1 {pure}
    v3: bool = not v2 {pure}
    v4: bool = const false
    br_if v3, bb1, bb2(v4)
  bb1:
    v5: any = eq v0, v0 {pure,throws}
    v6: bool = not v5 {pure}
    v7: bool = not v6 {pure}
    br bb2(v7)
  bb2(v8: bool):
    v9: bool = const false
    br_if v8, bb3, bb4(v9)
  bb3:
    v10: any = opaque.NameRead(bump) {read:shared}
    v11: int = call bump() {impure}
    v12: any = opaque.NameRead(bump) {read:shared}
    v13: int = call bump() {impure}
    v14: bool = neq v11, v13 {pure}
    br bb4(v14)
  bb4(v15: bool):
    v16: bool = const false
    br_if v15, bb5, bb6(v16)
  bb5:
    v17: any = opaque.NameRead(bump) {read:shared}
    v18: int = call bump() {impure}
    v19: any = opaque.NameRead(bump) {read:shared}
    v20: int = call bump() {impure}
    v21: bool = neq v18, v20 {pure}
    br bb6(v21)
  bb6(v22: bool):
    ret v22
```

```mir
;; mir -O2 fn=checkNoRefl
fn checkNoRefl(v0: any) -> bool:
  bb0:
    v1: any = eq v0, v0 {pure,throws}
    v2: bool = not v1 {pure}
    v3: bool = not v2 {pure}
    v4: bool = const false
    br_if v3, bb1, bb2(v4)
  bb1:
    v5: any = eq v0, v0 {pure,throws}
    v6: bool = not v5 {pure}
    v7: bool = not v6 {pure}
    br bb2(v7)
  bb2(v8: bool):
    br_if v8, bb3, bb4(v4)
  bb3:
    v9: any = opaque.NameRead(bump) {read:shared}
    v10: int = call bump() {impure}
    v11: any = opaque.NameRead(bump) {read:shared}
    v12: int = call bump() {impure}
    v13: bool = neq v10, v12 {pure}
    br bb4(v13)
  bb4(v14: bool):
    br_if v14, bb5, bb6(v4)
  bb5:
    v15: any = opaque.NameRead(bump) {read:shared}
    v16: int = call bump() {impure}
    v17: any = opaque.NameRead(bump) {read:shared}
    v18: int = call bump() {impure}
    v19: bool = neq v16, v18 {pure}
    br bb6(v19)
  bb6(v20: bool):
    ret v20
```

## Negated comparison rewrites (not (a == b) -> a != b, etc.) and E-graph equivalence

```babble
def checkNegCmp = fn (x: int, y: int, s: string, t: string): bool -> (
    (not (x == y)) == (x != y) and (not (x != y)) == (x == y) and
    (not (x < y)) == (x >= y) and (not (x <= y)) == (x > y) and
    (not (x > y)) == (x <= y) and (not (x >= y)) == (x < y) and
    (not (s == t)) == (s != t) and (not (s != t)) == (s == t)
)
assert(checkNegCmp(2, 5, "a", "b") and checkNegCmp(3, 3, "a", "a"))
```

```hir
;; hir -O0 fn=checkNegCmp
fn checkNegCmp (x:int, y:int, s:obj, t:obj) -> bool {nothrow unique}
  (and (and (and (and (and (and (and (==:bool (block (not (block (==:bool var(x) var(y))))) (block (<>:bool var(x) var(y)))) (==:bool (block (not (block (<>:bool var(x) var(y))))) (block (==:bool var(x) var(y))))) (==:bool (block (not (block (<:bool var(x) var(y))))) (block (>=:bool var(x) var(y))))) (==:bool (block (not (block (<=:bool var(x) var(y))))) (block (>:bool var(x) var(y))))) (==:bool (block (not (block (>:bool var(x) var(y))))) (block (<=:bool var(x) var(y))))) (==:bool (block (not (block (>=:bool var(x) var(y))))) (block (<:bool var(x) var(y))))) (==:bool (block (not (block (==:bool var(s) var(t))))) (block (<>:bool var(s) var(t))))) (==:bool (block (not (block (<>:bool var(s) var(t))))) (block (==:bool var(s) var(t)))))
```

```hir
;; hir -O1 fn=checkNegCmp
fn checkNegCmp (x:int, y:int, s:obj, t:obj) -> bool {nothrow unique}
  true:bool
```

```mir
;; mir -O2 fn=checkNegCmp stage=before,final
fn checkNegCmp(v0: int, v1: int, v2: any, v3: any) -> bool:
  bb0:
    v4: bool = const true
    ret v4
```
