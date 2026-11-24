# Compiler bytecode lowering edge cases : constant folding, conditions, widening, Java fields, and exception...

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble
;; babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
```

## Constant folding arithmetic and algebraic identities (x passed as parameter to prevent full -O1 folding)

```babble
def fold = fn (x : int) -> (
    assert(x + 0 == 5 and 0 + x == 5 and x - 0 == 5)
    assert(x * 0 == 0 and 0 * x == 0 and x * 1 == 5 and 1 * x == 5)
    assert((1 + 2) * (3 + 4) == 21 and -(10 - 3) == -7)
)
fold(5)
assert(10 - 3 == 7 and 4 * 5 == 20 and -(3) + 10 == 7 and -(2.5) == -2.5)
```

```hir
;; hir -O0 fn=fold
fn fold (x:int) -> bool {pure unique}
  (call name(assert) (and (and (==:bool (+:int var(x) 0:int) 5:int) (==:bool (+:int 0:int var(x)) 5:int)) (==:bool (-:int var(x) 0:int) 5:int)))
  (call name(assert) (and (and (and (==:bool (*:int var(x) 0:int) 0:int) (==:bool (*:int 0:int var(x)) 0:int)) (==:bool (*:int var(x) 1:int) 5:int)) (==:bool (*:int 1:int var(x)) 5:int)))
  (call name(assert) (and (==:bool (*:int (block (+:int 1:int 2:int)) (block (+:int 3:int 4:int))) 21:int) (==:bool (neg (block (-:int 10:int 3:int))) -7:int)))
```

```hir
;; hir -O1 fn=fold
fn fold (x:int) -> bool {pure unique}
  (call name(assert) (and (and (==:bool var(x) 5:int) (==:bool var(x) 5:int)) (==:bool var(x) 5:int)))
  (call name(assert) (and (==:bool var(x) 5:int) (==:bool var(x) 5:int)))
  (call name(assert) true:bool)
```

## evalIntConstant nested unary minus including 32-bit Integer.MIN_VALUE negation wrap, plus mixed widening

```babble
def x = 4
def z = (0 - x) + (10 * 2 + 1) + -(-3) + -(-2147483647 - 1) + -(0 - x)
def dm = 1 + 2.0
def dmin : double = -2147483648
assert(z == -2147483624)
assert(dm == 3.0)
assert(dmin == -2147483648)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (def z (+:int (+:int (+:int (+:int (block (-:int 0:int name(x))) (block (+:int (*:int 10:int 2:int) 1:int))) (neg (block -3:int))) (neg (block (-:int -2147483647:int 1:int)))) (neg (block (-:int 0:int name(x))))))
  (def dm (+:double 1:int 2.0:double))
  (def dmin -2147483648:int)
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) -2147483648:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (def z (+:int (+:int (+:int (+:int (neg name(x)) 21:int) 3:int) -2147483648:int) (neg (neg name(x)))))
  (def dm 3.0:double)
  (def dmin -2147483648:int)
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) -2147483648:int))
```

## Boolean equality, not/or/and conditions, mixed comparisons, and parenthesized condition

```babble
def b1 = true ; def b2 = false ; def x = 8 ; def y = 9
assert((b1 == b2) == false and (b1 != b2) == true)
assert(if not (x < 5) then ( true ) else ( false ))
assert(if (x < 5) or (y < 5) then ( false ) else ( true ))
assert((1 == 1.0) and (2 != 3.0) and (b1 == true) and (b1 != false) and ((1 < 2)))
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (call name(assert) (and (==:bool (block (==:bool name(b1) name(b2))) false:bool) (==:bool (block (<>:bool name(b1) name(b2))) true:bool)))
  (call name(assert) (if (not (block (<:bool name(x) 5:int))) (block true:bool) (block false:bool)))
  (call name(assert) (if (or (block (<:bool name(x) 5:int)) (block (<:bool name(y) 5:int))) (block false:bool) (block true:bool)))
  (call name(assert) (and (and (and (and (block (==:bool 1:int 1.0:double)) (block (<>:bool 2:int 3.0:double))) (block (==:bool name(b1) true:bool))) (block (<>:bool name(b1) false:bool))) (block (block (<:bool 1:int 2:int)))))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (call name(assert) (and (==:bool (==:bool name(b1) name(b2)) false:bool) (==:bool (<>:bool name(b1) name(b2)) true:bool)))
  (call name(assert) (if (not (<:bool name(x) 5:int)) true:bool false:bool))
  (call name(assert) (if (or (<:bool name(x) 5:int) (<:bool name(y) 5:int)) false:bool true:bool))
  (call name(assert) (and (and (==:bool name(b1) true:bool) (<>:bool name(b1) false:bool)) true:bool))
```

## Numeric truthiness in if conditions (int and double)

```babble
def check = fn (d1 : double, d0 : double, i1 : int, i0 : int) : string -> (
    def out = ""
    out += if d1 then ( "1" ) else ( "0" )
    out += if d0 then ( "1" ) else ( "0" )
    out += if i1 then ( "1" ) else ( "0" )
    out += if i0 then ( "1" ) else ( "0" )
    out
)
assert(check(1.5, 0.0, 2, 0) == "1010")
```

```hir
;; hir -O0 fn=check
fn check (d1:double, d0:double, i1:int, i0:int) -> obj {nothrow unique}
  (def out "")
  (set out += (if var(d1) (block "1") (block "0")))
  (set out += (if var(d0) (block "1") (block "0")))
  (set out += (if var(i1) (block "1") (block "0")))
  (set out += (if var(i0) (block "1") (block "0")))
  var(out)
```

```hir
;; hir -O1 fn=check
fn check (d1:double, d0:double, i1:int, i0:int) -> obj {nothrow unique}
  (def out "")
  (set out += (if var(d1) "1" "0"))
  (set out += (if var(d0) "1" "0"))
  (set out += (if var(i1) "1" "0"))
  (set out += (if var(i0) "1" "0"))
  var(out)
```

## Double comparisons in control flow

```babble
def checkD = fn (d : double) : int -> (
    def hits = 0
    if d < 3.0 then ( hits += 1 )
    if d <= 2.5 then ( hits += 1 )
    if d == 2.5 then ( hits += 1 )
    if d != 1.0 then ( hits += 1 )
    if d >= 2.5 then ( hits += 1 )
    if d > 2.0 then ( hits += 1 )
    hits
)
assert(checkD(2.5) == 6)
```

```hir
;; hir -O0 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (block (set hits += 1:int)))
  (if (<=:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (==:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (<>:bool var(d) 1.0:double) (block (set hits += 1:int)))
  (if (>=:bool var(d) 2.5:double) (block (set hits += 1:int)))
  (if (>:bool var(d) 2.0:double) (block (set hits += 1:int)))
  var(hits)
```

```hir
;; hir -O1 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (set hits += 1:int))
  (if (<=:bool var(d) 2.5:double) (set hits += 1:int))
  (if (==:bool var(d) 2.5:double) (set hits += 1:int))
  (if (<>:bool var(d) 1.0:double) (set hits += 1:int))
  (if (>=:bool var(d) 2.5:double) (set hits += 1:int))
  (if (>:bool var(d) 2.0:double) (set hits += 1:int))
  var(hits)
```

## Int condition lowering (==, !=, and, or) across true and false branches

```babble
def checkI = fn (a : int, b : int) : string -> (
    def o = ""
    o += if a == b then ( "1" ) else ( "0" )
    o += if a != b then ( "1" ) else ( "0" )
    o += if a > 0 and b > 0 then ( "1" ) else ( "0" )
    o += if a < 0 or b < 0 then ( "1" ) else ( "0" )
    o
)
assert(checkI(2, 2) == "1010" and checkI(2, 3) == "0110")
```

```hir
;; hir -O0 fn=checkI
fn checkI (a:int, b:int) -> obj {nothrow commutative unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (and (>:bool var(a) 0:int) (>:bool var(b) 0:int)) (block "1") (block "0")))
  (set o += (if (or (<:bool var(a) 0:int) (<:bool var(b) 0:int)) (block "1") (block "0")))
  var(o)
```

```hir
;; hir -O1 fn=checkI
fn checkI (a:int, b:int) -> obj {nothrow commutative unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) "1" "0"))
  (set o += (if (<>:bool var(a) var(b)) "1" "0"))
  (set o += (if (and (>:bool var(a) 0:int) (>:bool var(b) 0:int)) "1" "0"))
  (set o += (if (or (<:bool var(a) 0:int) (<:bool var(b) 0:int)) "1" "0"))
  var(o)
```

## Double condition lowering (==, !=, <, <=, >, >=) across true and false branches

```babble
def chk = fn (a: double, b: double): string -> (
    def o = ""
    o += if a == b then ("1") else ("0")
    o += if a != b then ("1") else ("0")
    o += if a < b then ("1") else ("0")
    o += if a <= b then ("1") else ("0")
    o += if a > b then ("1") else ("0")
    o += if a >= b then ("1") else ("0")
    o
)
assert(chk(1.0, 2.0) == "011100" and chk(2.0, 1.0) == "010011")
```

```hir
;; hir -O0 fn=chk
fn chk (a:double, b:double) -> obj {nothrow unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (<=:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (>:bool var(a) var(b)) (block "1") (block "0")))
  (set o += (if (>=:bool var(a) var(b)) (block "1") (block "0")))
  var(o)
```

```hir
;; hir -O1 fn=chk
fn chk (a:double, b:double) -> obj {nothrow unique}
  (def o "")
  (set o += (if (==:bool var(a) var(b)) "1" "0"))
  (set o += (if (<>:bool var(a) var(b)) "1" "0"))
  (set o += (if (<:bool var(a) var(b)) "1" "0"))
  (set o += (if (<=:bool var(a) var(b)) "1" "0"))
  (set o += (if (>:bool var(a) var(b)) "1" "0"))
  (set o += (if (>=:bool var(a) var(b)) "1" "0"))
  var(o)
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

## Java int and float instance field assign and compound assign

```babble
def pf = java.awt.geom.Point2D.Float()
pf.x = java.lang.Float.valueOf(3)
pf.y = java.lang.Float.valueOf(4)
def pt = java.awt.Point(1, 2)
pt.x = 5 ; pt.x += 3 ; pt.x *= 2 ; pt.x -= 4 ; pt.y += 2 ; pt.y *= 3
def rect = java.awt.Rectangle()
rect.width = 10 ; rect.width += 5
assert(pf.x == 3.0 and pf.y == 4.0 and pt.x == 12 and pt.y == 12 and rect.width == 15)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def pf (jnew Float))
  (set-jfield x name(pf) (jcall Float.valueOf 3:int))
  (set-jfield y name(pf) (jcall Float.valueOf 4:int))
  (def pt (jnew Point 1:int 2:int))
  (set-jfield x name(pt) 5:int)
  (set-jfield x name(pt) 3:int)
  (set-jfield x name(pt) 2:int)
  (set-jfield x name(pt) 4:int)
  (set-jfield y name(pt) 2:int)
  (set-jfield y name(pt) 3:int)
  (def rect (jnew Rectangle))
  (set-jfield width name(rect) 10:int)
  (set-jfield width name(rect) 5:int)
  (call name(assert) (and (and (and (and (==:bool (get-jfield Float.x name(pf)) 3.0:double) (==:bool (get-jfield Float.y name(pf)) 4.0:double)) (==:bool (get-jfield Point.x name(pt)) 12:int)) (==:bool (get-jfield Point.y name(pt)) 12:int)) (==:bool (get-jfield Rectangle.width name(rect)) 15:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def pf (jnew Float))
  (set-jfield x name(pf) (jcall Float.valueOf 3.0:float))
  (set-jfield y name(pf) (jcall Float.valueOf 4.0:float))
  (def pt (jnew Point 1:int 2:int))
  (set-jfield x name(pt) 5:int)
  (set-jfield x name(pt) 3:int)
  (set-jfield x name(pt) 2:int)
  (set-jfield x name(pt) 4:int)
  (set-jfield y name(pt) 2:int)
  (set-jfield y name(pt) 3:int)
  (def rect (jnew Rectangle))
  (set-jfield width name(rect) 10:int)
  (set-jfield width name(rect) 5:int)
  (call name(assert) (and (and (and (and (==:bool (get-jfield Float.x name(pf)) 3.0:double) (==:bool (get-jfield Float.y name(pf)) 4.0:double)) (==:bool (get-jfield Point.x name(pt)) 12:int)) (==:bool (get-jfield Point.y name(pt)) 12:int)) (==:bool (get-jfield Rectangle.width name(rect)) 15:int)))
```

## Java double instance field assign and compound assign (int, double, and union RHS)

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("x"))
st.nval = 3 ; st.nval += 2 ; st.nval -= 1.5 ; st.nval *= 2.0 ; st.nval /= 7 ; st.nval += dynNum(2)
st.ttype = 10 ; st.ttype += 5 ; st.ttype -= 3 ; st.ttype *= 2
assert(st.nval == 3.0 and st.ttype == 24)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "x")))
  (set-jfield nval name(st) 3:int)
  (set-jfield nval name(st) 2:int)
  (set-jfield nval name(st) 1.5:double)
  (set-jfield nval name(st) 2.0:double)
  (set-jfield nval name(st) 7:int)
  (set-jfield nval name(st) (call-fn dynNum 2:int))
  (set-jfield ttype name(st) 10:int)
  (set-jfield ttype name(st) 5:int)
  (set-jfield ttype name(st) 3:int)
  (set-jfield ttype name(st) 2:int)
  (call name(assert) (and (==:bool (get-jfield StreamTokenizer.nval name(st)) 3.0:double) (==:bool (get-jfield StreamTokenizer.ttype name(st)) 24:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "x")))
  (set-jfield nval name(st) 3:int)
  (set-jfield nval name(st) 2:int)
  (set-jfield nval name(st) 1.5:double)
  (set-jfield nval name(st) 2.0:double)
  (set-jfield nval name(st) 7:int)
  (set-jfield nval name(st) (block
      (def v 2:int)
      var(v)))
  (set-jfield ttype name(st) 10:int)
  (set-jfield ttype name(st) 5:int)
  (set-jfield ttype name(st) 3:int)
  (set-jfield ttype name(st) 2:int)
  (call name(assert) (and (==:bool (get-jfield StreamTokenizer.nval name(st)) 3.0:double) (==:bool (get-jfield StreamTokenizer.ttype name(st)) 24:int)))
```

## Java Point2D.Double and Rectangle2D.Double compound ops

```babble
def r = java.awt.geom.Rectangle2D.Double(1.0, 2.0, 3.0, 4.0)
r.x += 2.0 ; r.y -= 0.5 ; r.width *= 2.0 ; r.height /= 2.0
def pd = java.awt.geom.Point2D.Double(10.0, 20.0)
pd.x += 2.0 ; pd.x -= 4.0 ; pd.x *= 3.0 ; pd.x /= 2.0
pd.y = 5
assert(r.x == 3.0 and r.y == 1.5 and r.width == 6.0 and r.height == 2.0)
assert(pd.x == 12.0 and pd.y == 5.0)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def r (jnew Double 1.0:double 2.0:double 3.0:double 4.0:double))
  (set-jfield x name(r) 2.0:double)
  (set-jfield y name(r) 0.5:double)
  (set-jfield width name(r) 2.0:double)
  (set-jfield height name(r) 2.0:double)
  (def pd (jnew Double 10.0:double 20.0:double))
  (set-jfield x name(pd) 2.0:double)
  (set-jfield x name(pd) 4.0:double)
  (set-jfield x name(pd) 3.0:double)
  (set-jfield x name(pd) 2.0:double)
  (set-jfield y name(pd) 5:int)
  (call name(assert) (and (and (and (==:bool (get-jfield Double.x name(r)) 3.0:double) (==:bool (get-jfield Double.y name(r)) 1.5:double)) (==:bool (get-jfield Double.width name(r)) 6.0:double)) (==:bool (get-jfield Double.height name(r)) 2.0:double)))
  (call name(assert) (and (==:bool (get-jfield Double.x name(pd)) 12.0:double) (==:bool (get-jfield Double.y name(pd)) 5.0:double)))
```

## Java String and typed Object field assign and compound assign

```babble
def demo = import("org.babblelang.tests").JavaClassDemo()
demo.nonNullLabel = "a" ; demo.nonNullLabel += "b"
def gbc = java.awt.GridBagConstraints()
gbc.insets = java.awt.Insets(1, 2, 3, 4)
gbc.weightx = 2.0 ; gbc.weightx += 1
assert(demo.nonNullLabel == "ab" and gbc.insets!.top == 1 and gbc.weightx == 3.0)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def demo (jnew JavaClassDemo))
  (set-jfield nonNullLabel name(demo) "a")
  (set-jfield nonNullLabel name(demo) "b")
  (def gbc (jnew GridBagConstraints))
  (set-jfield insets name(gbc) (jnew Insets 1:int 2:int 3:int 4:int))
  (set-jfield weightx name(gbc) 2.0:double)
  (set-jfield weightx name(gbc) 1:int)
  (call name(assert) (and (and (==:bool (get-jfield JavaClassDemo.nonNullLabel name(demo)) "ab") (==:bool (get-jfield Insets.top (nonnull (get-jfield GridBagConstraints.insets name(gbc)))) 1:int)) (==:bool (get-jfield GridBagConstraints.weightx name(gbc)) 3.0:double)))
```

## Java untyped Object field assign (String and boxed int)

```babble
def ev = java.awt.Event(null, 0, null)
ev.arg = "ok"
def s = ev.arg
ev.arg = 42
assert(s == "ok" and ev.arg == 42)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def ev (jnew Event null 0:int null))
  (set-jfield arg name(ev) "ok")
  (def s (get-jfield Event.arg name(ev)))
  (set-jfield arg name(ev) 42:int)
  (call name(assert) (and (==:bool name(s) "ok") (==:bool (get-jfield Event.arg name(ev)) 42:int)))
```

## Static Java field assign and compound assign

```babble
def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
def saved = HF.mutableStatic
HF.mutableStatic = 10
HF.mutableStatic += 5
HF.mutableStatic -= 3
HF.mutableStatic *= 2
def after = HF.mutableStatic
HF.mutableStatic = saved
assert(after == 24)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def HF (select (select (call name(import) "org.babblelang.tests").EffectsTestCase).HostFixtures))
  (def saved (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) 10:int)
  (set-jfield mutableStatic name(HF) 5:int)
  (set-jfield mutableStatic name(HF) 3:int)
  (set-jfield mutableStatic name(HF) 2:int)
  (def after (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) name(saved))
  (call name(assert) (==:bool name(after) 24:int))
```

## emitJavaMethodCall exception catch handler

```babble
java.lang.Integer.parseInt("bad_num")
```

```error
java.lang.NumberFormatException : For input string: "bad_num"
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (jcall Integer.parseInt "bad_num")
```

## emitJavaConstructorCall exception catch handler

```babble
java.io.FileReader("nonexistent_batest_file_xyz.txt")
```

```error
java.io.FileNotFoundException : nonexistent_batest_file_xyz.txt (<OS error>)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (jnew FileReader "nonexistent_batest_file_xyz.txt")
```

## isPureStaticJavaChain : multi-expr block, declaring block, javax root, forward capture, and non-pure static field assign

```babble
def SH = import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers
def fwd = fn (): int -> ( def g = fn (): int -> ( M.abs(-5) ); def M = java.lang.Math; g() )
(discard(1); SH).mutableCounter = 0
assert((discard(1); java.lang.Math).abs(-3) == 3 and (def m = java.lang.Math).abs(-4) == 4)
assert(javax.xml.XMLConstants.NULL_NS_URI == "" and fwd() == 5 and SH.mutableCounter == 0)
```

```hir
;; hir -O0,-O1 fn=fwd
fn fwd () -> int {nothrow unique}
  (def g (fn-ref g))
  (def M (select (select name(java).lang).Math))
  (call-fn g)
```

## Empty for, try, and catch blocks plus local move expression

```babble
def Box = class (def v : int) ()
for x in list(1, 2) then ()
def t: any? = try () catch e then ()
def run = fn (): int -> ( def a = Box(4) ; def b = move(a) ; b.v )
assert(t == null and run() == 4)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> int {nothrow unique}
  (def a (call name(Box) 4:int))
  (def b (move var(a)))
  (get-this-field v)
```

## resolveBabbleJvmName for Babble interface array, function-local class array, and union array

```babble
def I = interface ( def k : fn () : int )
def ia = (array of I?)(2)
def ua = (array of (int | string)).of(1, "a")
def mkLocal = fn () : int -> (
    def L = class (def v : int) ()
    def la = (array of L).of(L(3), L(4))
    la.get(0).v + la.get(1).v
)
assert(ia.size() == 2 and ua.get(1) == "a" and mkLocal() == 7)
```

```hir
;; hir -O0,-O1 fn=mkLocal
fn mkLocal () -> int {pure unique}
  (def L (class-ref L))
  (def la (array-new OF (call var(L) 3:int) (call var(L) 4:int)))
  (+:int (get-this-field v) (get-this-field v))
```

## Nested else-if widening and typed rest without reorder plus reordered named args

```babble
def pick = fn (a : bool, b : bool) : double -> ( if a then ( 1 ) else if b then ( 2 ) else ( 3.5 ) )
def sumR = fn (a : int, xs : int...) : int -> ( def s = a ; for x in xs then ( s += x ) ; s )
def sub = fn (a : int, b : int) : int -> ( a - b )
assert(pick(false, true) == 2.0 and pick(false, false) == 3.5 and sumR(1, 2, 3) == 6 and sub(b: 2, a: 10) == 8)
```

```hir
;; hir -O0 fn=pick
fn pick (a:bool, b:bool) -> double {nothrow}
  (if var(a) (block 1:int) (if var(b) (block 2:int) (block 3.5:double)))
```

```hir
;; hir -O1 fn=pick
fn pick (a:bool, b:bool) -> double {nothrow}
  (if var(a) (block 1:int) (if var(b) (block 2:int) 3.5:double))
```

## Contextual long and float literals widened to double

```babble
def toD = fn (a : double, b : double) : double -> ( a + b )
def d1 : double = 10L
def d2 : double = 1.5f
assert(d1 == 10.0 and d2 == 1.5 and toD(2L, 0.5f) == 2.5)
```

```hir
;; hir -O0,-O1 fn=toD
fn toD (a:double, b:double) -> double {nothrow commutative monotone unique}
  (+:double var(a) var(b))
```

## Non-spread 9-parameter class method lowers via METHOD_DESCRIPTOR

```babble
def Big = class () (
    def sum9 = fn (a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int, i: int): int -> (
        a + b + c + d + e + f + g + h + i
    )
)
assert(Big().sum9(1, 2, 3, 4, 5, 6, 7, 8, 9) == 45)
```

```hir
;; hir -O0,-O1 class=Big
class Big
  (def sum9 (fn-ref Big.sum9))
  fn Big.sum9 (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) -> int {nothrow unique}
    (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)) var(i))
```

## Non-spread 9-parameter function with rest parameter uses Arguments.binder with restIndex

```babble
def sumRest = fn (a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int, xs: int...): int -> (
    def t = a + b + c + d + e + f + g + h
    for x in xs then ( t += x )
    t
)
assert(sumRest(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55)
```

```hir
;; hir -O0,-O1 fn=sumRest
fn sumRest (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, xs:obj) -> int {nothrow unique}
  (def t (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)))
  (for x in var(xs)
    (block
      (set t += var(x))))
  var(t)
```

## Specialized function with widened boxed optional parameter default

```babble
def f = fn (x: int, d: double? = 1): double -> (
    double(x) + (if d is double then ( d ) else ( 0.0 ))
)
assert(f(2) == 3.0 and f(2, 4.5) == 6.5 and f(2, null) == 2.0)
```

```hir
;; hir -O0 fn=f
fn f (x:int, d:obj=1:int) -> double {nothrow unique}
  (+:double (call name(double) var(x)) (block (if (is var(d) double) (block var(d)) (block 0.0:double))))
```

```hir
;; hir -O1 fn=f
fn f (x:int, d:obj=1:int) -> double {nothrow unique}
  (+:double (call name(double) var(x)) (if (is var(d) double) var(d) 0.0:double))
```

## Variadic function and bound method adapted to Java SAM exercise maxArity restIndex branch

```babble
def C = class () ( def m = fn (x: int, xs: int...): int -> ( x + 10 ) )
def op1: java.util.function.IntUnaryOperator = fn (x: int, xs: int...): int -> ( x + 1 )
def op2: java.util.function.IntUnaryOperator = C().m
assert(op1.applyAsInt(5) == 6 and op2.applyAsInt(5) == 15)
```

```hir
;; hir -O0,-O1 fn=op1
fn op1 (x:int, xs:obj) -> int {nothrow unique}
  (+:int var(x) 1:int)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def m (fn-ref C.m))
  fn C.m (x:int, xs:obj) -> int {nothrow unique}
    (+:int var(x) 10:int)
```

## CompiledBabbleScript.lineOf falls back when thrown exception has empty stack trace

```babble
def e = java.lang.ArrayIndexOutOfBoundsException("no-frames")
e.setStackTrace((array of java.lang.StackTraceElement).of())
throw e
```

```error
java.lang.ArrayIndexOutOfBoundsException: no-frames
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def e (jnew ArrayIndexOutOfBoundsException "no-frames"))
  (jcall Throwable.setStackTrace name(e) (array-new OF))
  (throw name(e))
```
