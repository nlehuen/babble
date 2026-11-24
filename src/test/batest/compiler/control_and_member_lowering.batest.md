# Control-flow, exception, member-field, and array bytecode lowering

References : BIP001, BIP011, BIP020, BIP028 ; targets `ControlAndOpEmitter`, `AssignAndMemberEmitter`, `HirExpr$Block`

```babble
;; babble module=ext_rec.ba
export def Pt = record (x: int, y: int) ()
```

```babble
;; babble preamble
def TopErrA = class (def code: int) ()
def TopErrB = class (def code: int) ()
```

## Non-transparent block expressions in conditions (multi-statement and local-def blocks)

```babble
def check = fn (x: int): int -> (
    def a = if ( def t = x; t > 0 ) then ( 1 ) else ( 0 )
    def b = if ( def t = true ) then ( 2 ) else ( 0 )
    def c = if ( discard(1); x == 0 ) then ( 4 ) else ( 0 )
    a + b + c
)
assert(check(3) == 3 and check(0) == 6)
```

```hir
;; hir -O0 fn=check
fn check (x:int) -> int {nothrow unique}
  (def a (if (block
        (def t#1 var(x))
        (>:bool var(t#1) 0:int)) (block 1:int) (block 0:int)))
  (def b (if (block (def t#2 true:bool)) (block 2:int) (block 0:int)))
  (def c (if (block
        (call name(discard) 1:int)
        (==:bool var(x) 0:int)) (block 4:int) (block 0:int)))
  (+:int (+:int var(a) var(b)) var(c))
```

```hir
;; hir -O1 fn=check
fn check (x:int) -> int {nothrow unique}
  (def a (if (block
        (def t#1 var(x))
        (>:bool var(t#1) 0:int)) 1:int 0:int))
  (def b (if (block (def t#2 true:bool)) 2:int 0:int))
  (def c (if (==:bool var(x) 0:int) 4:int 0:int))
  (+:int (+:int var(a) var(b)) var(c))
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = gt v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    v3: int = const 0
    br bb3(v3)
  bb2:
    v4: int = const 1
    br bb3(v4)
  bb3(v5: int):
    v6: bool = const true
    br_if v6, bb5, bb4
  bb4:
    v7: int = const 0
    br bb6(v7)
  bb5:
    v8: int = const 2
    br bb6(v8)
  bb6(v9: int):
    v10: int = const 0
    v11: bool = eq v0, v10 {pure}
    br_if v11, bb8, bb7
  bb7:
    v12: int = const 0
    br bb9(v12)
  bb8:
    v13: int = const 4
    br bb9(v13)
  bb9(v14: int):
    v15: int = add v5, v9 {pure}
    v16: int = add v15, v14 {pure}
    ret v16
```

```mir
;; mir -O2 fn=check
fn check(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: bool = gt v0, v1 {pure}
    br_if v2, bb2, bb1
  bb1:
    br bb3(v1)
  bb2:
    v3: int = const 1
    br bb3(v3)
  bb3(v4: int):
    br bb4
  bb4:
    v5: int = const 2
    br bb5(v5)
  bb5(v6: int):
    v7: bool = eq v0, v1 {pure}
    br_if v7, bb7, bb6
  bb6:
    br bb8(v1)
  bb7:
    v8: int = const 4
    br bb8(v8)
  bb8(v9: int):
    v10: int = add v4, v6 {pure}
    v11: int = add v10, v9 {pure}
    ret v11
```

## Unary minus, arithmetic binary, and dynamic string comparison directly in conditions

```babble
def check = fn (x: int, y: int, s1: string, s2: string): int -> (
    def r = 0
    if -x then ( r += 1 )
    if x + y then ( r += 2 )
    if s1 == s2 then ( r += 4 )
    r
)
assert(check(2, -2, "a", "a") == 5 and check(0, 1, "a", "b") == 2)
```

```hir
;; hir -O0 fn=check
fn check (x:int, y:int, s1:obj, s2:obj) -> int {nothrow unique}
  (def r 0:int)
  (if (neg var(x)) (block (set r += 1:int)))
  (if (+:int var(x) var(y)) (block (set r += 2:int)))
  (if (==:bool var(s1) var(s2)) (block (set r += 4:int)))
  var(r)
```

```hir
;; hir -O1 fn=check
fn check (x:int, y:int, s1:obj, s2:obj) -> int {nothrow unique}
  (def r 0:int)
  (if (neg var(x)) (set r += 1:int))
  (if (+:int var(x) var(y)) (set r += 2:int))
  (if (==:bool var(s1) var(s2)) (set r += 4:int))
  var(r)
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: int, v1: int, v2: any, v3: any) -> int:
  bb0:
    v4: int = const 0
    v5: int = neg v0 {pure}
    br_if v5, bb2, bb1
  bb1:
    v6: any = const null
    br bb3(v6, v4)
  bb2:
    v7: int = const 1
    v8: int = add v4, v7 {pure}
    v9: any = convert.any v8
    br bb3(v9, v8)
  bb3(v10: any, v11: int):
    v12: int = add v0, v1 {pure}
    br_if v12, bb5, bb4
  bb4:
    v13: any = const null
    br bb6(v13, v11)
  bb5:
    v14: int = const 2
    v15: int = add v11, v14 {pure}
    v16: any = convert.any v15
    br bb6(v16, v15)
  bb6(v17: any, v18: int):
    v19: any = eq v2, v3 {pure}
    br_if v19, bb8, bb7
  bb7:
    v20: any = const null
    br bb9(v20, v18)
  bb8:
    v21: int = const 4
    v22: int = add v18, v21 {pure}
    v23: any = convert.any v22
    br bb9(v23, v22)
  bb9(v24: any, v25: int):
    ret v25
```

```mir
;; mir -O2 fn=check
fn check(v0: int, v1: int, v2: any, v3: any) -> int:
  bb0:
    v4: int = const 0
    v5: int = neg v0 {pure}
    br_if v5, bb2, bb1
  bb1:
    br bb3(v4)
  bb2:
    v6: int = const 1
    br bb3(v6)
  bb3(v7: int):
    v8: int = add v0, v1 {pure}
    br_if v8, bb5, bb4
  bb4:
    br bb6(v7)
  bb5:
    v9: int = const 2
    v10: int = add v7, v9 {pure}
    br bb6(v10)
  bb6(v11: int):
    v12: any = eq v2, v3 {pure}
    br_if v12, bb8, bb7
  bb7:
    br bb9(v11)
  bb8:
    v13: int = const 4
    v14: int = add v11, v13 {pure}
    br bb9(v14)
  bb9(v15: int):
    ret v15
```

## Negated long, float, and double truthiness exercises branchIfTrue in emitCondition

```babble
def check = fn (l0: long, l1: long, f0: float, f1: float, d0: double, d1: double): int -> (
    def r = 0
    if not l0 then ( r += 1 )
    if not l1 then ( r += 2 )
    if not f0 then ( r += 4 )
    if not f1 then ( r += 8 )
    if not d0 then ( r += 16 )
    if not d1 then ( r += 32 )
    r
)
assert(check(0L, 5L, 0.0f, 1.5f, 0.0, 2.5) == 21)
```

```hir
;; hir -O0 fn=check
fn check (l0:long, l1:long, f0:float, f1:float, d0:double, d1:double) -> int {nothrow unique}
  (def r 0:int)
  (if (not var(l0)) (block (set r += 1:int)))
  (if (not var(l1)) (block (set r += 2:int)))
  (if (not var(f0)) (block (set r += 4:int)))
  (if (not var(f1)) (block (set r += 8:int)))
  (if (not var(d0)) (block (set r += 16:int)))
  (if (not var(d1)) (block (set r += 32:int)))
  var(r)
```

```hir
;; hir -O1 fn=check
fn check (l0:long, l1:long, f0:float, f1:float, d0:double, d1:double) -> int {nothrow unique}
  (def r 0:int)
  (if (not var(l0)) (set r += 1:int))
  (if (not var(l1)) (set r += 2:int))
  (if (not var(f0)) (set r += 4:int))
  (if (not var(f1)) (set r += 8:int))
  (if (not var(d0)) (set r += 16:int))
  (if (not var(d1)) (set r += 32:int))
  var(r)
```

```mir
;; mir -O2 fn=check stage=before
fn check(v0: long, v1: long, v2: float, v3: float, v4: double, v5: double) -> int:
  bb0:
    v6: int = const 0
    v7: bool = not v0 {pure}
    br_if v7, bb2, bb1
  bb1:
    v8: any = const null
    br bb3(v8, v6)
  bb2:
    v9: int = const 1
    v10: int = add v6, v9 {pure}
    v11: any = convert.any v10
    br bb3(v11, v10)
  bb3(v12: any, v13: int):
    v14: bool = not v1 {pure}
    br_if v14, bb5, bb4
  bb4:
    v15: any = const null
    br bb6(v15, v13)
  bb5:
    v16: int = const 2
    v17: int = add v13, v16 {pure}
    v18: any = convert.any v17
    br bb6(v18, v17)
  bb6(v19: any, v20: int):
    v21: bool = not v2 {pure}
    br_if v21, bb8, bb7
  bb7:
    v22: any = const null
    br bb9(v22, v20)
  bb8:
    v23: int = const 4
    v24: int = add v20, v23 {pure}
    v25: any = convert.any v24
    br bb9(v25, v24)
  bb9(v26: any, v27: int):
    v28: bool = not v3 {pure}
    br_if v28, bb11, bb10
  bb10:
    v29: any = const null
    br bb12(v29, v27)
  bb11:
    v30: int = const 8
    v31: int = add v27, v30 {pure}
    v32: any = convert.any v31
    br bb12(v32, v31)
  bb12(v33: any, v34: int):
    v35: bool = not v4 {pure}
    br_if v35, bb14, bb13
  bb13:
    v36: any = const null
    br bb15(v36, v34)
  bb14:
    v37: int = const 16
    v38: int = add v34, v37 {pure}
    v39: any = convert.any v38
    br bb15(v39, v38)
  bb15(v40: any, v41: int):
    v42: bool = not v5 {pure}
    br_if v42, bb17, bb16
  bb16:
    v43: any = const null
    br bb18(v43, v41)
  bb17:
    v44: int = const 32
    v45: int = add v41, v44 {pure}
    v46: any = convert.any v45
    br bb18(v46, v45)
  bb18(v47: any, v48: int):
    ret v48
```

```mir
;; mir -O2 fn=check
fn check(v0: long, v1: long, v2: float, v3: float, v4: double, v5: double) -> int:
  bb0:
    v6: int = const 0
    v7: bool = not v0 {pure}
    br_if v7, bb2, bb1
  bb1:
    br bb3(v6)
  bb2:
    v8: int = const 1
    br bb3(v8)
  bb3(v9: int):
    v10: bool = not v1 {pure}
    br_if v10, bb5, bb4
  bb4:
    br bb6(v9)
  bb5:
    v11: int = const 2
    v12: int = add v9, v11 {pure}
    br bb6(v12)
  bb6(v13: int):
    v14: bool = not v2 {pure}
    br_if v14, bb8, bb7
  bb7:
    br bb9(v13)
  bb8:
    v15: int = const 4
    v16: int = add v13, v15 {pure}
    br bb9(v16)
  bb9(v17: int):
    v18: bool = not v3 {pure}
    br_if v18, bb11, bb10
  bb10:
    br bb12(v17)
  bb11:
    v19: int = const 8
    v20: int = add v17, v19 {pure}
    br bb12(v20)
  bb12(v21: int):
    v22: bool = not v4 {pure}
    br_if v22, bb14, bb13
  bb13:
    br bb15(v21)
  bb14:
    v23: int = const 16
    v24: int = add v21, v23 {pure}
    br bb15(v24)
  bb15(v25: int):
    v26: bool = not v5 {pure}
    br_if v26, bb17, bb16
  bb16:
    br bb18(v25)
  bb17:
    v27: int = const 32
    v28: int = add v25, v27 {pure}
    br bb18(v28)
  bb18(v29: int):
    ret v29
```

## Char compared with any exercises canLowerComparison non-char right operand

```babble
def eq = fn (c: char, a: any): bool -> ( c == a )
assert(eq('a', 'a') and not eq('a', "a"))
```

```hir
;; hir -O0,-O1 fn=eq
fn eq (c:char, a:obj) -> bool {pure unique}
  (==:bool var(c) var(a))
```

```mir
;; mir -O2 fn=eq stage=before,final
fn eq(v0: char, v1: any) -> bool:
  bb0:
    v2: any = convert.any v0
    v3: any = eq v2, v1 {pure,throws}
    v4: bool = convert.bool v3
    ret v4
```

## Runtime is any? lowers with null descriptor to constant true

```babble
def check = fn (x: int?): bool -> ( x is any? )
assert(check(1) and check(null))
```

```hir
;; hir -O0,-O1 fn=check
fn check (x:obj) -> bool {nothrow unique}
  (is var(x) any)
```

```mir
;; mir -O2 fn=check stage=before,final
fn check(v0: any) -> bool:
  bb0:
    v1: bool = is v0 <any>
    ret v1
```

## Redundant cast to any? lowers with null targetDescriptor and no SAM target

```babble
def x: int = 7
assert((x as any?) == 7)
```

```stderr
Warning : Redundant cast : any?
```

```hir
;; hir -O0 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def x 7:int)
  (call name(assert) (==:bool (block (as name(x) any)) 7:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def x 7:int)
  (call name(assert) (==:bool (as name(x) any) 7:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Empty finally block and early return inside try-catch without finally

```babble
def f = fn (flag: bool): int -> (
    try (
        if flag then ( return 10 )
        20
    ) catch : java.lang.RuntimeException then ( 30 )
)
def g = try ( 42 ) finally ()
assert(f(true) == 10 and f(false) == 20 and g == 42)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=f
fn f (flag:bool) -> int {nothrow unique}
  (try
    (block
      (if var(flag) (block (return 10:int)))
      20:int)
    (catch _:java|java.lang.RuntimeException|java.lang.RuntimeException
      30:int))
```

```hir
;; hir -O1 fn=f
fn f (flag:bool) -> int {nothrow unique}
  (try
    (block
      (if var(flag) (return 10:int))
      20:int)
    (catch _:java|java.lang.RuntimeException|java.lang.RuntimeException
      30:int))
```

```mir
;; mir -O2 fn=f stage=before
fn f(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: ThrownException -> bb8, RuntimeException -> bb5]:
    br_if v0, bb4, bb2
  bb2 [unwind: ThrownException -> bb8, RuntimeException -> bb5]:
    v1: any = const null
    br bb3
  bb3 [unwind: ThrownException -> bb8, RuntimeException -> bb5]:
    v2: int = const 20
    br bb7(v2)
  bb4 [unwind: ThrownException -> bb8, RuntimeException -> bb5]:
    v3: int = const 10
    ret v3
  bb5(v4: any) [handler]:
    br bb6
  bb6:
    v5: int = const 30
    br bb7(v5)
  bb7(v6: int):
    ret v6
  bb8(v7: any) [handler]:
    rethrow v7
```

```mir
;; mir -O2 fn=f
fn f(v0: bool) -> int:
  bb0:
    br bb1
  bb1 [unwind: ThrownException -> bb7, RuntimeException -> bb4]:
    br_if v0, bb3, bb2
  bb2 [unwind: ThrownException -> bb7, RuntimeException -> bb4]:
    v1: int = const 20
    br bb6(v1)
  bb3 [unwind: ThrownException -> bb7, RuntimeException -> bb4]:
    v2: int = const 10
    ret v2
  bb4(v3: any) [handler]:
    br bb5
  bb5:
    v4: int = const 30
    br bb6(v4)
  bb6(v5: int):
    ret v5
  bb7(v6: any) [handler]:
    rethrow v6
```

## Multiple consecutive top-level Babble catch clauses and nameless catch with local def

```babble
def run = fn (k: int): int -> (
    try (
        if k == 1 then ( throw TopErrA(11) )
        else if k == 2 then ( throw TopErrB(22) )
        else ( throw java.io.IOException("x") )
    ) catch a: TopErrA then ( a.code )
      catch b: TopErrB then ( b.code )
      catch : java.io.IOException then ( def r = 33; r )
)
assert(run(1) == 11 and run(2) == 22 and run(3) == 33)
```

```hir
;; hir -O0 fn=run
fn run (k:int) -> int {pure}
  (try
    (block
      (if (==:bool var(k) 1:int) (block (throw (call name(TopErrA) 11:int))) (if (==:bool var(k) 2:int) (block (throw (call name(TopErrB) 22:int))) (block (throw (jnew IOException "x"))))))
    (catch a:babble|TopErrA
      (get-this-field code))
    (catch b:babble|TopErrB
      (get-this-field code))
    (catch _:java|java.io.IOException|java.io.IOException
      (def r 33:int)
      var(r)))
```

```hir
;; hir -O1 fn=run
fn run (k:int) -> int {pure}
  (try
    (block
      (if (==:bool var(k) 1:int) (throw (call name(TopErrA) 11:int)) (if (==:bool var(k) 2:int) (throw (call name(TopErrB) 22:int)) (throw (jnew IOException "x")))))
    (catch a:babble|TopErrA
      (get-this-field code))
    (catch b:babble|TopErrB
      (get-this-field code))
    (catch _:java|java.io.IOException|java.io.IOException
      33:int))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: int) -> int:
  bb0:
    br bb1
  bb1 [unwind: ThrownException -> bb8, IOException -> bb6]:
    v1: int = const 1
    v2: bool = eq v0, v1 {pure}
    br_if v2, bb5, bb2
  bb2 [unwind: ThrownException -> bb8, IOException -> bb6]:
    v3: int = const 2
    v4: bool = eq v0, v3 {pure}
    br_if v4, bb4, bb3
  bb3 [unwind: ThrownException -> bb8, IOException -> bb6]:
    v5: any = const "x"
    v6: any = new_java IOException(v5) {pure!}
    rethrow v6
  bb4 [unwind: ThrownException -> bb8, IOException -> bb6]:
    v7: any = opaque.NameRead() {impure}
    v8: int = const 22
    v9: any = opaque.DynamicCall(v7, v8) {impure}
    throw v9
  bb5 [unwind: ThrownException -> bb8, IOException -> bb6]:
    v10: any = opaque.NameRead() {impure}
    v11: int = const 11
    v12: any = opaque.DynamicCall(v10, v11) {impure}
    throw v12
  bb6(v13: any) [handler]:
    br bb7
  bb7:
    v14: int = const 33
    br bb13(v14)
  bb8(v15: any) [handler]:
    v16: any = unwrap_thrown v15
    v17: bool = catch_match v16 <babble|TopErrA>
    br_if v17, bb12, bb9
  bb9:
    v18: bool = catch_match v16 <babble|TopErrB>
    br_if v18, bb11, bb10
  bb10:
    rethrow v15
  bb11:
    v19: int = load_field v16.code {read:shared,throws}
    br bb13(v19)
  bb12:
    v20: int = load_field v16.code {read:shared,throws}
    br bb13(v20)
  bb13(v21: int):
    ret v21
```

## Function-local Babble exception catch falls back to Types.matches descriptor check

```babble
def run = fn (flag: bool): string -> (
    def LocalA = class (def msg: string) ()
    def LocalB = class (def msg: string) ()
    try (
        if flag then ( throw LocalA("a") ) else ( throw LocalB("b") )
    ) catch e: LocalA then ( e.msg )
      catch e: LocalB then ( e.msg )
)
assert(run(true) == "a" and run(false) == "b")
```

```hir
;; hir -O0 fn=run
fn run (flag:bool) -> obj {pure}
  (def LocalA (class-ref LocalA))
  (def LocalB (class-ref LocalB))
  (try
    (block
      (if var(flag) (block (throw (call var(LocalA) "a"))) (block (throw (call var(LocalB) "b")))))
    (catch e#1:babble|LocalA
      (get-this-field msg))
    (catch e#2:babble|LocalB
      (get-this-field msg)))
```

```hir
;; hir -O1 fn=run
fn run (flag:bool) -> obj {pure}
  (def LocalA (class-ref LocalA))
  (def LocalB (class-ref LocalB))
  (try
    (block
      (if var(flag) (throw (call var(LocalA) "a")) (throw (call var(LocalB) "b"))))
    (catch e#1:babble|LocalA
      (get-this-field msg))
    (catch e#2:babble|LocalB
      (get-this-field msg)))
```

```mir
;; mir -O2 fn=run stage=before,final
fn run(v0: bool) -> any:
  bb0:
    v1: any = opaque.ClassExpr() {impure}
    v2: any = opaque.ClassExpr() {impure}
    br bb1
  bb1 [unwind: ThrownException -> bb4]:
    br_if v0, bb3, bb2
  bb2 [unwind: ThrownException -> bb4]:
    v3: any = const "b"
    v4: any = opaque.DynamicCall(v2, v3) {impure}
    throw v4
  bb3 [unwind: ThrownException -> bb4]:
    v5: any = const "a"
    v6: any = opaque.DynamicCall(v1, v5) {impure}
    throw v6
  bb4(v7: any) [handler]:
    v8: any = unwrap_thrown v7
    v9: bool = catch_match v8 <babble|LocalA>
    br_if v9, bb8, bb5
  bb5:
    v10: bool = catch_match v8 <babble|LocalB>
    br_if v10, bb7, bb6
  bb6:
    rethrow v7
  bb7:
    v11: any = load_field v8.msg {read:shared,throws}
    br bb9(v11)
  bb8:
    v12: any = load_field v8.msg {read:shared,throws}
    br bb9(v12)
  bb9(v13: any):
    ret v13
```

## Java boolean, long-from-int, and float-from-int instance field assignments

```babble
def dpi = java.sql.DriverPropertyInfo("k", "v")
dpi.required = true
def n: int = 42
def ev = java.awt.Event(null, 0, null)
ev.when = n
def pf = java.awt.geom.Point2D.Float()
pf.x = n
assert(dpi.required and ev.when == 42L and pf.x == 42.0f)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def dpi (jnew DriverPropertyInfo "k" "v"))
  (set-jfield required name(dpi) true:bool)
  (def n 42:int)
  (def ev (jnew Event null 0:int null))
  (set-jfield when name(ev) name(n))
  (def pf (jnew Float))
  (set-jfield x name(pf) name(n))
  (call name(assert) (and (and (get-jfield DriverPropertyInfo.required name(dpi)) (==:bool (get-jfield Event.when name(ev)) 42:long)) (==:bool (get-jfield Float.x name(pf)) 42.0:float)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Static Java field non-pure receiver and fallback compound assign with byte RHS

```babble
def getS = fn io () -> ( import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers )
def before = getS().mutableCounter
getS().mutableCounter += byte(0)
def loc: int = 10
loc += byte(5)
assert(getS().mutableCounter == before and loc == 15)
```

```hir
;; hir -O0,-O1 fn=getS
fn getS () -> obj {io}
  (select (select (call name(import) "org.babblelang.tests").BytecodeEffectAnalyzerTestCase).SampleHelpers)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def getS (fn-ref getS))
  (def before (get-jfield SampleHelpers.mutableCounter (call-fn getS)))
  (set-jfield mutableCounter (call-fn getS) (call name(byte) 0:int))
  (def loc 10:int)
  (set-name loc += (call name(byte) 5:int))
  (call name(assert) (and (==:bool (get-jfield SampleHelpers.mutableCounter (call-fn getS)) name(before)) (==:bool name(loc) 15:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## External module primitive record field get, array.toString(), and Java interface array set

```babble
def m = import("ext_rec.ba")
def p = m.Pt(3, 4)
assert(p.x == 3 and p.y == 4)
assert((array of int).of(1, 2, 3).toString() == "[1, 2, 3]")
def ops = (array of java.util.function.IntUnaryOperator).of(fn (x: int): int -> ( x + 1 ))
ops.set(0, fn (x: int): int -> ( x * 2 ))
assert(ops.get(0).applyAsInt(5) == 10)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def m (call name(import) "ext_rec.ba"))
  (def p (record-new ext_rec$ba$C0$Pt 3:int 4:int))
  (call name(assert) (and (==:bool (get-this-field x) 3:int) (==:bool (get-this-field y) 4:int)))
  (call name(assert) (==:bool (array-op TO_STRING (array-new OF 1:int 2:int 3:int)) "[1, 2, 3]"))
  (def ops (array-new OF (java-sam IntUnaryOperator (fn-ref <anon#0>))))
  (array-op SET name(ops) 0:int (java-sam IntUnaryOperator (fn-ref <anon#1>)))
  (call name(assert) (==:bool (jcall IntUnaryOperator.applyAsInt (array-op GET name(ops) 0:int) 5:int) 10:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```
