# Algebraic simplification : boolean/int/long identities, constant reassociation, idempotence, IEEE-754 & imp...

References : BIP014-I2, org.babblelang.opt.hir.AlgebraicSimplifyPass

```babble preamble
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

## Parameterized inputs prevent -O1 ConstFoldPass from folding away the algebraic identities

```babble
def checkBool = fn (b: bool) -> (
    assert((not (not b)) == b)
    assert((b == true) == b and (b != true) == (not b))
    assert((b == false) == (not b) and (b != false) == b)
    assert((true == b) == b and (false == b) == (not b))
    assert((true != b) == (not b) and (false != b) == b)
)
checkBool(true)
checkBool(false)
```

## Case 2

```babble
def checkInt = fn (x: int, eAdd0: int, eAddC: int, eMul: int) -> (
    assert((x + 0) + (0 + x) == eAdd0)
    assert((5 + x) + ((x + 1) + 2) == eAddC)
    assert((x - 0) + (0 - x) + (x - x) == 0)
    assert((x * 1) + (1 * x) + (x * 0) + (0 * x) + (3 * x) + ((x * 2) * 3) == eMul)
)
checkInt(-5, -10, -2, -55)
checkInt(3, 6, 14, 33)
```

## Case 3

```babble
def checkLong = fn (lx: long, eAdd0: long, eAddC: long, eMul: long) -> (
    assert((lx + 0L) + (0L + lx) == eAdd0)
    assert((5L + lx) + ((lx + 1L) + 2L) == eAddC)
    assert((lx - 0L) + (0L - lx) + (lx - lx) == 0L)
    assert((lx * 1L) + (1L * lx) + (lx * 0L) + (0L * lx) + (3L * lx) + ((lx * 2L) * 3L) == eMul)
)
checkLong(4L, 8L, 16L, 44L)
checkLong(-2L, -4L, 4L, -22L)
```

## String reassociation and IEEE-754 preservation (NaN * 0.0 != 0.0, no float/double reassociation)

```babble
def checkStrIeee = fn (s: string, d: double, expStr: string, expNan: bool) -> (
    assert((s + "a") + "b" == expStr)
    assert(java.lang.Double.isNaN((d * 0.0) + 0.0) == expNan)
    if expNan then (assert(java.lang.Double.isNaN((d + 1.0) + 2.0)))
    else (assert((d + 1.0) + 2.0 == 3.0))
)
checkStrIeee("  ok ", java.lang.Double.NaN, "  ok ab", true)
checkStrIeee("hi", -0.0, "hiab", false)
```

## Idempotent Babble (inlined and non-inlined) and Math.abs calls

```babble
def checkIntIdem = fn (x: int, ec: int, ea: int) -> (
    def lc = bigClamp
    assert(clamp(clamp(x)) == ec and bigClamp(bigClamp(x)) == ec and lc(lc(x)) == ec)
    assert(java.lang.Math.abs(java.lang.Math.abs(x)) == ea)
)
checkIntIdem(-5, 0, 5)
checkIntIdem(3, 3, 3)
```

## Idempotent Math.floor preserving NaN and IEEE-754 -0.0 (checked by raw bits since +0.0 == -0.0)

```babble
def checkFloor = fn (d: double) -> (
    def fl = java.lang.Math.floor(java.lang.Math.floor(d))
    assert(if java.lang.Double.isNaN(d) then (java.lang.Double.isNaN(fl))
        else (java.lang.Double.doubleToRawLongBits(fl) == java.lang.Double.doubleToRawLongBits(d)))
)
checkFloor(java.lang.Double.NaN)
checkFloor(-0.0)
```

## Idempotent String.trim and Character.toLowerCase calls

```babble
axiom java.lang.Character.toLowerCase: fn pure nothrow idempotent
def checkStrIdem = fn (s: string, ch: char, es: string, ech: char) -> (
    assert(s.trim().trim() == es)
    assert(java.lang.Character.toLowerCase(java.lang.Character.toLowerCase(ch)) == ech)
)
checkStrIdem("  ok ", 'B', "ok", 'b')
checkStrIdem("hi", 'Z', "hi", 'z')
```

## Impure operands must not be dropped by * 0 or reordered

```babble
def r = (bump() * 0) + (0 * bump()) + (1 + bump()) + (2 * bump())
assert(r == 12 and tick == 4)
```

## Non-constant inner RHS does not reassociate; non-matching idempotent calls stay intact

```babble
def checkNonReassoc = fn (x: int, y: int, s: string, t: string) -> (
    assert((x + y) + 2 == 7 and (x * y) * 3 == 18 and (s + t) + "!" == "ab!")
    assert(clamp(bigClamp(x)) == 2)
)
checkNonReassoc(2, 3, "a", "b")
```

## Widened idempotent Java call (wideningRank >= 0) and non-prefix Babble idempotent call stay intact

```babble
tick = 1
def lateClamp = fn axiom pure nothrow idempotent (x: int): int -> (if x < 0 then (0) else (x))
def checkGuard = fn (x: int) -> (
    def wd: double = java.lang.Math.abs(java.lang.Math.abs(x))
    assert(wd == 5.0 and lateClamp(lateClamp(x)) == 0)
)
checkGuard(-5)
```

## Non-dropping idempotent collapse on impure arguments (`f(f(impure())) -> f(impure())`)

```babble
def bumpStr = fn io (): string -> (tick += 1 ; "  ok  ")
def a = bigClamp(bigClamp(bump() - 10))
def b = java.lang.Math.abs(java.lang.Math.abs(bump() - 10))
def s = bumpStr().trim().trim()
assert(a == 0 and b == 8 and s == "ok" and tick == 3)
```

## Non-dropping arithmetic identities with impure operands (`e + 0`, `0 + e`, `e - 0`, `e * 1`, `1 * e`)

```babble
def r = (bump() + 0) + (0 + bump()) + (bump() - 0) + (bump() * 1) + (1 * bump())
assert(r == 15 and tick == 5)
```

## Idempotent collapse on complex deterministic expressions (HirPurity.isDeterministic)

```babble
def idT of T = fn (x: T): T -> (x)
def checkDet = fn (arr: array of int, s: string, flag: bool): int -> (
    def a = bigClamp(bigClamp(if flag and true then (arr.size() + s.length()) else (idT of int(1))))
    def b = java.lang.Math.abs(java.lang.Math.abs(int(arr.size() as double)))
    a + b
)
assert(checkDet((array of int).of(1, 2), "abc", true) == 7)
```

## Canonical HIR golden : algebraic identities and integer reassociation (-O0 vs -O1)

```babble
def simplify = fn (x: int): int -> ( (x + 0) * 1 + (x - x) )
def reassoc = fn (x: int): int -> ( (x + 1) + 2 )
assert(simplify(7) == 7 and reassoc(5) == 8)
```

```hir -O0 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  (+:int (*:int (block (+:int var(x) 0:int)) 1:int) (block (-:int var(x) var(x))))
```

```hir -O1 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  var(x)
```

```hir -O1 fn=reassoc
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

```hir -O0 fn=norm check
count 2 (call-fn bigClamp
count 2 (jcall Math.abs
```

```hir -O1 fn=norm stage=after:HirEGraphSimplify check
count 1 (jcall Math.abs
```

```hir -O1 fn=norm check
count 1 (jcall Math.abs
```

```hir -O0 fn=clean check
count 2 (jcall String.trim
```

```hir -O1 fn=clean check
count 1 (jcall String.trim
```

## Canonical HIR golden : strict IEEE-754 double preservation (-O0,-O1)

```babble
def add3 = fn (x: double): double -> ( x + 1.0 + 2.0 )
assert(add3(1.0) == 4.0 and java.lang.Double.isNaN(add3(java.lang.Double.NaN)))
```

```hir -O0,-O1 fn=add3
fn add3 (x:double) -> double {nothrow monotone unique}
  (+:double (+:double var(x) 1.0:double) 2.0:double)
```

