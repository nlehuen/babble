# Constructor (`init`) lowering : HIR `HirConstructor`, `ThisFieldInit`, body `def` immutability, and Wasm local-staged `struct.new`

References : `BIP040-D10` (unified HIR, direct JVM `<init>` with `ACC_FINAL` body `def` fields, and Wasm 3.0 local-staged immutable `struct.new`).

## Parameterless init initializes final body def field and enables dead read elimination

```babble
def Sphere = class (def r: double) (
    def rSq: double
    init (
        def tmp = this.r * this.r
        this.rSq = tmp
    )
    def getR = fn (): double -> (def dead = this.rSq; this.r)
)
def run = fn (): double -> (
    def s = Sphere(3.0)
    def unused = s.rSq
    s.getR()
)
assert(run() == 3.0)
```

```hir
;; hir -O0 fn=run
fn run () -> double {nothrow unique}
  (def s (call name(Sphere) 3.0:double))
  (def unused (get-this-field rSq))
  (call-method var(s).getR)
```

```hir
;; hir -O1 fn=run
fn run () -> double {nothrow unique}
  (def s (call name(Sphere) 3.0:double))
  (get-this-field r)
```

```hir
;; hir -O2 fn=run
fn run () -> double {nothrow unique}
  (def s (call name(Sphere) 3.0:double))
  (get-this-field r)
```

## Overloaded and delegating init constructors lower to distinct typed constructor slots

```babble
def Point = class () (
    def x: int
    def y: int

    init (x: int, y: int) -> (
        this.x = x
        this.y = y
    )

    init (v: int) -> (
        def doubled = v * 2
        this(doubled, v)
    )
)
def run = fn (a: int): int -> (
    def p1 = Point(a, a + 1)
    def p2 = Point(a)
    p1.x + p1.y + p2.x + p2.y
)
assert(run(3) == 16)
```

```hir
;; hir -O0 fn=run
fn run (a:int) -> int {nothrow unique}
  (def p1 (call name(Point) var(a) (+:int var(a) 1:int)))
  (def p2 (call name(Point) var(a)))
  (+:int (+:int (+:int (get-this-field x) (get-this-field y)) (get-this-field x)) (get-this-field y))
```

```hir
;; hir -O1 fn=run
fn run (a:int) -> int {nothrow unique}
  (def p1 (call name(Point) var(a) (+:int var(a) 1:int)))
  (def p2 (call name(Point) var(a)))
  (+:int (+:int (+:int (get-this-field x) (get-this-field y)) (get-this-field x)) (get-this-field y))
```

```hir
;; hir -O2 fn=run
fn run (a:int) -> int {nothrow unique}
  (def p1 (call name(Point) var(a) (+:int var(a) 1:int)))
  (def p2 (call name(Point) var(a)))
  (+:int (+:int (+:int (get-this-field x) (get-this-field y)) (get-this-field x)) (get-this-field y))
```
