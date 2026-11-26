# Virtual modules : export def, nested imports, caching of module init, and export axioms

References : BIP005 (modules & exports), BIP016/BIP017 (cross-module effects), BIP019 (exported axioms)

```babble
;; babble module=math.ba
var loads = 0
def bump = fn io () : int -> ( loads += 1 ; loads )
def secret = 99
export def pi = 3.0
export def add = fn (a : int, b : int) : int -> ( a + b )
export def Box = class (var x : int) (
    def get = fn () : int -> ( this.x )
)
export def mul = fn (a : int, b : int) : int -> ( a * b )
export def hits = fn volatile () : int -> ( loads )
bump()
```

```babble
;; babble module=sub/deep.ba
def w = import("../wrap.ba")
export def calc = fn (a : int, b : int) : int -> ( w.sumAndMul(a, b) )
```

```babble
;; babble module=hof.ba
export def Box = class (var x : int) ()
export def apply = fn io (n : int, f : fn io (v : int) : int) : int -> ( f(n) )
export def applyOpt = fn io (n : int, f : fn io (v : int) : int = fn (v : int) : int -> ( v + 1 )) : int -> ( f(n) )
export def mutateWith = fn (mut b : Box, f : fn (mut m : Box) : int) : int -> ( f(b) )
export def pureWrap = fn (n : int, f : fn (v : int) : int) : int -> ( f(n) )
```

```babble
;; babble module=wrap.ba
def m = import("math.ba")
export def sumAndMul = fn (a : int, b : int) : int -> ( m.add(a, b) + m.mul(a, b) )
export axiom java.lang.Math.max: fn pure nothrow
```

```babble
;; babble module=math2.ba
export def pi = 4.0
export def add = fn (a : int, b : int) : int -> ( a + b + 1 )
```

## Case 1

```babble
def m = import("math.ba")
def m2 = import("math.ba")
def w = import("wrap.ba")
def b = m.Box(7)
assert(m.add(2, 3) == 5)
assert(m.mul(2, 3) == 6)
assert(b.get() == 7)
assert(m.pi == 3.0)
assert(w.sumAndMul(3, 4) == 19)
assert(m.hits() == 1)
assert(same(m, m2))
```

## Relative path normalization ("sub/deep.ba" importing "../wrap.ba") and exported axiom

```babble
def d = import("sub/deep.ba")
def w = import("wrap.ba")
def check = fn () : int -> ( java.lang.Math.max(d.calc(2, 5), 10) )
assert(check() == 17)
```

## Non-exported binding is hidden from importers

```babble
def m = import("math.ba")
print(m.secret)
```

```error
No such export : secret in math.ba
```

## Dynamic Module receiver exercises Bootstrap.SelectCache Module path and Module.getMember

```babble
def flag = true
def dm = if flag then ( import("math.ba") ) else ( import("math2.ba") )
assert(string(dm) == "module math.ba")
assert(dm.add(4, 5) == 9)
assert(dm.pi == 3.0)
```

## Runtime assign to export on conditionally-typed Module hits Module.setMember

```babble
def m = if true then ( import("math.ba") ) else ( import("math.ba") )
m.add = 1
```

```error
Cannot assign to an export : add
```

## Runtime assign to nonexistent export on conditionally-typed Module hits Module.setMember

```babble
def m = if true then ( import("math.ba") ) else ( import("math.ba") )
m.missing = 1
```

```error
No such export : missing in math.ba
```

## Cross-module higher-order effect specialization (pure callback, optional default, and mut forwarding)

```babble
def h = import("hof.ba")
def pureCaller = fn (x : int) : int -> (
    h.apply(x, fn (v : int) : int -> ( v * 2 ))
)
def callMutate = fn (x : int) : int -> (
    def box = h.Box(x)
    h.mutateWith(box, fn (mut m : h.Box) : int -> ( m.x += 1 ; m.x ))
)
assert(pureCaller(5) + h.applyOpt(5) == 16)
assert(callMutate(16) == 17)
assert(h.pureWrap(4, fn (v : int) : int -> ( v + 3 )) == 7)
```

## Cross-module higher-order effect specialization rejects io callback in pure caller

```babble
def h = import("hof.ba")
def bad = fn (x : int) : int -> (
    h.apply(x, fn io (v : int) : int -> ( println(v) ; v ))
)
println(bad(1))
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Transitive module class identity and local class alias

```babble
;; babble module=vectors.ba
def square = fn (n) -> ( n * n )
export def Vec = record (x: int, y: int) (
    def plus = fn (o: Vec): Vec -> ( Vec(this.x + o.x, this.y + o.y) )
    def lengthSquared = fn () -> ( square(this.x) + square(this.y) )
)
export def origin = Vec(0, 0)
assert(Vec(3, 4).lengthSquared() == 25)
```

```babble
;; babble module=shapes.ba
def vectors = import("vectors.ba")
def Vec = vectors.Vec
export def Shape = interface (
    def area: fn (): double
    def describe = fn () -> ( "shape of area " + this.area() )
)
export def Square = class (def corner: Vec, def side: int) implements Shape (
    def area = fn (): double -> ( this.side * this.side )
    def opposite = fn () -> ( this.corner.plus(Vec(this.side, this.side)) )
)
var created = 0
export def unitSquare = fn io () -> ( created += 1 ; Square(vectors.origin, 1) )
export def count = fn volatile () -> ( created )
```

```babble
def shapes = import("shapes.ba")
def vectors = import("vectors.ba")
def Vec = vectors.Vec
def s = shapes.Square(Vec(1, 2), 3)
assert(s.area() == 9 and s.opposite() == Vec(4, 5) and s is shapes.Shape)
assert(s.describe() == "shape of area 9.0")
assert(Vec(1, 1).plus(Vec(2, 2)) == Vec(3, 3))
assert(shapes.unitSquare().corner is Vec and shapes.count() == 1)
```
