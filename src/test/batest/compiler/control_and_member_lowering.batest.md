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

```hir
;; hir -O2 fn=check
fn check (x:int) -> int {nothrow unique}
  (def a (if (>:bool var(x) 0:int) 1:int 0:int))
  (def c (if (==:bool var(x) 0:int) 4:int 0:int))
  (+:int (+:int var(a) 2:int) var(c))
```

## Unary minus, arithmetic binary, and dynamic string comparison directly in conditions

```babble
def check = fn (x: int, y: int, s1: string, s2: string): int -> (
    var r = 0
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
  (if (neg var(x)) (box (block (set r += 1:int))))
  (if (+:int var(x) var(y)) (box (block (set r += 2:int))))
  (if (==:bool var(s1) var(s2)) (box (block (set r += 4:int))))
  var(r)
```

```hir
;; hir -O1 fn=check
fn check (x:int, y:int, s1:obj, s2:obj) -> int {nothrow unique}
  (def r 0:int)
  (if (neg var(x)) (box (set r += 1:int)))
  (if (+:int var(x) var(y)) (box (set r += 2:int)))
  (if (==:bool var(s1) var(s2)) (box (set r += 4:int)))
  var(r)
```

```hir
;; hir -O2 fn=check
fn check (x:int, y:int, s1:obj, s2:obj) -> int {nothrow unique}
  (def r 0:int)
  (if (neg var(x)) (box (set r 1:int)))
  (if (+:int var(x) var(y)) (box (set r += 2:int)))
  (if (==:bool var(s1) var(s2)) (box (set r += 4:int)))
  var(r)
```

## Negated long, float, and double truthiness exercises branchIfTrue in emitCondition

```babble
def check = fn (l0: long, l1: long, f0: float, f1: float, d0: double, d1: double): int -> (
    var r = 0
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
  (if (not var(l0)) (box (block (set r += 1:int))))
  (if (not var(l1)) (box (block (set r += 2:int))))
  (if (not var(f0)) (box (block (set r += 4:int))))
  (if (not var(f1)) (box (block (set r += 8:int))))
  (if (not var(d0)) (box (block (set r += 16:int))))
  (if (not var(d1)) (box (block (set r += 32:int))))
  var(r)
```

```hir
;; hir -O1 fn=check
fn check (l0:long, l1:long, f0:float, f1:float, d0:double, d1:double) -> int {nothrow unique}
  (def r 0:int)
  (if (not var(l0)) (box (set r += 1:int)))
  (if (not var(l1)) (box (set r += 2:int)))
  (if (not var(f0)) (box (set r += 4:int)))
  (if (not var(f1)) (box (set r += 8:int)))
  (if (not var(d0)) (box (set r += 16:int)))
  (if (not var(d1)) (box (set r += 32:int)))
  var(r)
```

```hir
;; hir -O2 fn=check
fn check (l0:long, l1:long, f0:float, f1:float, d0:double, d1:double) -> int {nothrow unique}
  (def r 0:int)
  (if (not var(l0)) (box (set r 1:int)))
  (if (not var(l1)) (box (set r += 2:int)))
  (if (not var(f0)) (box (set r += 4:int)))
  (if (not var(f1)) (box (set r += 8:int)))
  (if (not var(d0)) (box (set r += 16:int)))
  (if (not var(d1)) (box (set r += 32:int)))
  var(r)
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

```hir
;; hir -O2 fn=eq
fn eq (c:char, a:obj) -> bool {pure unique}
  (==:bool var(c) var(a))
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

```hir
;; hir -O2 fn=check
fn check (x:obj) -> bool {nothrow unique}
  (is var(x) any)
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

```hir
;; hir -O2 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def x 7:int)
  (call name(assert) (==:bool (as name(x) any) 7:int))
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

```hir
;; hir -O2 fn=f
fn f (flag:bool) -> int {nothrow unique}
  (try
    (block
      (if var(flag) (return 10:int))
      20:int)
    (catch _:java|java.lang.RuntimeException|java.lang.RuntimeException
      30:int))
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

```hir
;; hir -O2 fn=run
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

```hir
;; hir -O2 fn=run
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
  (set-jfield when name(ev) (widen[long] name(n)))
  (def pf (jnew Float))
  (set-jfield x name(pf) (widen[float] name(n)))
  (call name(assert) (and (and (get-jfield DriverPropertyInfo.required name(dpi)) (==:bool (get-jfield Event.when name(ev)) 42:long)) (==:bool (get-jfield Float.x name(pf)) 42.0:float)))
```

```hir
;; hir -O2 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (block
    (def $cse0 name(java))
    (def dpi (jnew DriverPropertyInfo "k" "v"))
    (def $cse2 name(dpi))
    (set-jfield required var($cse2) true:bool)
    (def n 42:int)
    (def ev (jnew Event null 0:int null))
    (def $cse1 name(n))
    (def $cse3 name(ev))
    (set-jfield when var($cse3) (widen[long] var($cse1)))
    (def pf (jnew Float))
    (def $cse4 name(pf))
    (def $cse5 (widen[float] var($cse1)))
    (set-jfield x var($cse4) var($cse5))
    (call name(assert) (and (and (get-jfield DriverPropertyInfo.required var($cse2)) (==:bool (get-jfield Event.when var($cse3)) 42:long)) (==:bool var($cse5) 42.0:float))))
```

## Static Java field non-pure receiver and fallback compound assign with byte RHS

```babble
def getS = fn io () -> ( import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers )
def before = getS().mutableCounter
getS().mutableCounter += byte(0)
var loc: int = 10
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

```hir
;; hir -O2 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def getS (fn-ref getS))
  (block
    (def $cse0 name(getS))
    (def before (get-jfield SampleHelpers.mutableCounter (call-fn getS)))
    (def $cse1 name(byte))
    (set-jfield mutableCounter (call-fn getS) (call var($cse1) 0:int))
    (def loc 10:int)
    (set-name loc += (call var($cse1) 5:int))
    (call name(assert) (and (==:bool (get-jfield SampleHelpers.mutableCounter (call-fn getS)) name(before)) (==:bool name(loc) 15:int))))
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

```hir
;; hir -O2 top-level
top-level:
  (def TopErrA (class-ref TopErrA))
  (def TopErrB (class-ref TopErrB))
  (def m (call name(import) "ext_rec.ba"))
  (def p (record-new ext_rec$ba$C0$Pt 3:int 4:int))
  (block
    (def $cse0 name(p))
    (def $cse1 name(assert))
    (call var($cse1) (and (==:bool (get-this-field x) 3:int) (==:bool (get-this-field y) 4:int)))
    (call var($cse1) (==:bool (array-op TO_STRING (array-new OF 1:int 2:int 3:int)) "[1, 2, 3]"))
    (def ops (array-new OF (java-sam IntUnaryOperator (fn-ref <anon#0>))))
    (def $cse2 name(ops))
    (array-op SET var($cse2) 0:int (java-sam IntUnaryOperator (fn-ref <anon#1>)))
    (call var($cse1) (==:bool (jcall IntUnaryOperator.applyAsInt (array-op GET var($cse2) 0:int) 5:int) 10:int)))
```
