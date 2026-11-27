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

var tick = 0
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

```hir
;; hir -O2 fn=reassoc
fn reassoc (x:int) -> int {nothrow injective unique}
  (+:int var(x) 3:int)
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

```hir
;; hir -O2 fn=clean
fn clean (s:obj) -> obj {nothrow idempotent unique}
  (jcall String.trim var(s))
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

```hir
;; hir -O2 fn=add3
fn add3 (x:double) -> double {nothrow monotone unique}
  (+:double (+:double var(x) 1.0:double) 2.0:double)
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
  (+:int (block (+:int (block (neg (convert[int] var(b)))) 0:int)) (block (+:int (block (neg (convert[int] var(s)))) 0:int)))
```

```hir
;; hir -O1 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (+:int (neg (convert[int] var(b))) (neg (convert[int] var(s))))
```

```hir
;; hir -O2 fn=negNarrow
fn negNarrow (b:byte, s:short) -> int {nothrow unique}
  (block
    (def $cse0 (convert[int] var(b)))
    (def $cse1 (neg var($cse0)))
    (def $cse2 (convert[int] var(s)))
    (def $cse3 (neg var($cse2)))
    (+:int var($cse1) var($cse3)))
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

```hir
;; hir -O2 fn=trimCat
fn trimCat (s:obj) -> obj {nothrow unique}
  (jcall String.trim (+:obj var(s) "ab"))
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

```hir
;; hir -O2 top-level
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

```hir
;; hir -O2 fn=f
fn f (s:obj) -> bool {pure unique}
  (and (==:bool (jcall String.toUpperCase (jcall String.toUpperCase var(s))) "AB") (==:bool (jcall String.toLowerCase var(s) (get-jfield Locale.ROOT)) "ab"))
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

```hir
;; hir -O2 fn=f
fn f () -> bool {io}
  (and (==:bool (jcall Math.abs (jcall Math.floor (call-fn bumpD))) 3.0:double) (==:bool (jcall String.trim (jcall String.toUpperCase (call-fn bumpS))) "AB"))
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
  (+:double (+:double (+:double (+:double (+:double (block (+:double (convert[double] (block (neg var(x)))) 1.0:double)) (block (+:double (convert[double] (block (+:int var(x) var(y)))) 1.0:double))) (block (+:double (convert[double] (call-fn bigClamp var(x))) 1.0:double))) (call-fn bigClampD (widen[double] var(x)))) (call-fn bigClampD (widen[double] (neg var(x))))) (call-fn bigClampD (widen[double] (+:int var(x) var(y)))))
```

```hir
;; hir -O1 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (+:double (+:double (+:double (+:double (+:double (+:double (convert[double] (neg var(x))) 1.0:double) (+:double (convert[double] (+:int var(x) var(y))) 1.0:double)) (+:double (convert[double] (block
                (def r#1 (if (<:bool var(x) 0:int) 0:int var(x)))
                var(r#1))) 1.0:double)) (block
          (def v#1 (widen[double] var(x)))
          (def r#2 (if (<:bool var(v#1) 0.0:double) 0.0:double var(v#1)))
          var(r#2))) (block
        (def v#2 (widen[double] (neg var(x))))
        (def r#3 (if (<:bool var(v#2) 0.0:double) 0.0:double var(v#2)))
        var(r#3))) (block
      (def v#3 (widen[double] (+:int var(x) var(y))))
      (def r#4 (if (<:bool var(v#3) 0.0:double) 0.0:double var(v#3)))
      var(r#4)))
```

```hir
;; hir -O2 fn=f
fn f (x:int, y:int) -> double {nothrow unique}
  (block
    (def $cse0 (neg var(x)))
    (def $cse1 (+:int var(x) var(y)))
    (+:double (+:double (+:double (+:double (+:double (+:double (convert[double] var($cse0)) 1.0:double) (+:double (convert[double] var($cse1)) 1.0:double)) (+:double (convert[double] (block
                  (def r#1 (if (<:bool var(x) 0:int) 0:int var(x)))
                  var(r#1))) 1.0:double)) (block
            (def v#1 (widen[double] var(x)))
            (def r#2 (if (<:bool var(v#1) 0.0:double) 0.0:double var(v#1)))
            var(r#2))) (block
          (def v#2 (widen[double] var($cse0)))
          (def r#3 (if (<:bool var(v#2) 0.0:double) 0.0:double var(v#2)))
          var(r#3))) (block
        (def v#3 (widen[double] var($cse1)))
        (def r#4 (if (<:bool var(v#3) 0.0:double) 0.0:double var(v#3)))
        var(r#4))))
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

```hir
;; hir -O2 fn=reflEq
fn reflEq (b:bool, x:int, d:double, f:float, s:obj) -> bool {nothrow unique}
  true:bool
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

```hir
;; hir -O2 fn=checkNoRefl
fn checkNoRefl (a:obj) -> bool {io}
  (and (and (and (==:bool var(a) var(a)) (==:bool var(a) var(a))) (<>:bool (call-fn bump) (call-fn bump))) (<>:bool (call-fn bump) (call-fn bump)))
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

```hir
;; hir -O2 fn=checkNegCmp
fn checkNegCmp (x:int, y:int, s:obj, t:obj) -> bool {nothrow unique}
  (block
    (def $cse0 (<>:bool var(x) var(y)))
    (def $cse1 (==:bool var(x) var(y)))
    (==:bool (not var($cse0)) var($cse1)))
```
