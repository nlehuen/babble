# Coexistence smoke test : `.batest.md` v2 with canonical HIR goldens

References : `BIP029-P2`, `BIP014-I2`, `BIP014-I4`, `BIP027-I2`.

This `.batest.md` file runs alongside all 103 legacy `.batest` files during the `BIP029-P2`
coexistence phase to prove v1 and v2 work together with zero regressions.

## Algebraic identity elimination (-O0 vs -O1)

```babble
def simplify = fn (x: int): int -> ( (x + 0) * 1 + (x - x) )
assert(simplify(7) == 7)
assert(simplify(-3) == -3)
```

```hir -O0 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  (+:int (*:int (block (+:int var(x) 0:int)) 1:int) (block (-:int var(x) var(x))))
```

```hir -O1 fn=simplify
fn simplify (x:int) -> int {nothrow idempotent unique}
  var(x)
```

## Strict IEEE-754 double preservation (-O0,-O1 shared golden)

```babble
def add3 = fn (x: double): double -> ( x + 1.0 + 2.0 )
assert(add3(1.0) == 4.0)
assert(java.lang.Double.isNaN(add3(java.lang.Double.NaN)))
```

```hir -O0,-O1 fn=add3
fn add3 (x:double) -> double {nothrow monotone unique}
  (+:double (+:double var(x) 1.0:double) 2.0:double)
```

## Scalar replacement eliminates non-escaping record allocation

```babble
def Vec2 = record (x: double, y: double) (
    def dot = fn (other: Vec2): double -> ( this.x * other.x + this.y * other.y )
)
def sqLen = fn (a: double, b: double): double -> (
    def v = Vec2(a, b)
    v.dot(v)
)
assert(sqLen(3.0, 4.0) == 25.0)
```

```hir -O0 fn=sqLen check
contains (record-new Vec2
```

```hir -O1 fn=sqLen check
!contains record-new
!contains call-method
```
