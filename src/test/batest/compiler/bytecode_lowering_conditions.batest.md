# Bytecode Lowering : Constant Folding, Truthiness, and Comparisons

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

```hir
;; hir -O2 fn=fold
fn fold (x:int) -> bool {pure unique}
  (block
    (def $cse0 (==:bool var(x) 5:int))
    (def $cse1 name(assert))
    (call var($cse1) (and (and var($cse0) var($cse0)) var($cse0)))
    (call var($cse1) (and var($cse0) var($cse0)))
    (call var($cse1) true:bool))
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
  (def dm (+:double (convert[double] 1:int) 2.0:double))
  (def dmin (widen[double] -2147483648:int))
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) (convert[double] -2147483648:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (def z (+:int (+:int (neg name(x)) -2147483624:int) name(x)))
  (def dm 3.0:double)
  (def dmin -2.147483648E9:double)
  (call name(assert) (==:bool name(z) -2147483624:int))
  (call name(assert) (==:bool name(dm) 3.0:double))
  (call name(assert) (==:bool name(dmin) -2.147483648E9:double))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 4:int)
  (block
    (def $cse0 name(x))
    (def $cse1 (neg var($cse0)))
    (def z (+:int (+:int var($cse1) -2147483624:int) (neg var($cse1))))
    (def dm 3.0:double)
    (def dmin -2.147483648E9:double)
    (def $cse2 name(assert))
    (call var($cse2) (==:bool name(z) -2147483624:int))
    (call var($cse2) (==:bool name(dm) 3.0:double))
    (call var($cse2) (==:bool name(dmin) -2.147483648E9:double)))
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
  (call name(assert) (and (and (and (and (block (==:bool (convert[double] 1:int) 1.0:double)) (block (<>:bool (convert[double] 2:int) 3.0:double))) (block (==:bool name(b1) true:bool))) (block (<>:bool name(b1) false:bool))) (block (block (<:bool 1:int 2:int)))))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (call name(assert) (and (<>:bool name(b1) name(b2)) (<>:bool name(b1) name(b2))))
  (call name(assert) (if (>=:bool name(x) 5:int) true:bool false:bool))
  (call name(assert) (if (or (<:bool name(x) 5:int) (<:bool name(y) 5:int)) false:bool true:bool))
  (call name(assert) (and name(b1) name(b1)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def b1 true:bool)
  (def b2 false:bool)
  (def x 8:int)
  (def y 9:int)
  (block
    (def $cse0 name(b1))
    (def $cse1 name(b2))
    (def $cse2 (<>:bool var($cse0) var($cse1)))
    (def $cse3 name(assert))
    (call var($cse3) (and var($cse2) var($cse2)))
    (def $cse4 name(x))
    (def $cse5 (<:bool var($cse4) 5:int))
    (call var($cse3) (if (>=:bool var($cse4) 5:int) true:bool false:bool))
    (call var($cse3) (if (or var($cse5) (<:bool name(y) 5:int)) false:bool true:bool))
    (call var($cse3) (and (and var($cse0) var($cse0)) true:bool)))
```

## Numeric truthiness in if conditions (int and double)

```babble
def check = fn (d1 : double, d0 : double, i1 : int, i0 : int) : string -> (
    var out = ""
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

```hir
;; hir -O2 fn=check
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
    var hits = 0
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
  (if (<:bool var(d) 3.0:double) (box (block (set hits += 1:int))))
  (if (<=:bool var(d) 2.5:double) (box (block (set hits += 1:int))))
  (if (==:bool var(d) 2.5:double) (box (block (set hits += 1:int))))
  (if (<>:bool var(d) 1.0:double) (box (block (set hits += 1:int))))
  (if (>=:bool var(d) 2.5:double) (box (block (set hits += 1:int))))
  (if (>:bool var(d) 2.0:double) (box (block (set hits += 1:int))))
  var(hits)
```

```hir
;; hir -O1 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (box (set hits += 1:int)))
  (if (<=:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (==:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (<>:bool var(d) 1.0:double) (box (set hits += 1:int)))
  (if (>=:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (>:bool var(d) 2.0:double) (box (set hits += 1:int)))
  var(hits)
```

```hir
;; hir -O2 fn=checkD
fn checkD (d:double) -> int {nothrow unique}
  (def hits 0:int)
  (if (<:bool var(d) 3.0:double) (box (set hits 1:int)))
  (if (<=:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (==:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (<>:bool var(d) 1.0:double) (box (set hits += 1:int)))
  (if (>=:bool var(d) 2.5:double) (box (set hits += 1:int)))
  (if (>:bool var(d) 2.0:double) (box (set hits += 1:int)))
  var(hits)
```

## Int condition lowering (==, !=, and, or) across true and false branches

```babble
def checkI = fn (a : int, b : int) : string -> (
    var o = ""
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

```hir
;; hir -O2 fn=checkI
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
    var o = ""
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

```hir
;; hir -O2 fn=chk
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
