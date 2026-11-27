# Constructors (`init`) : primary, explicit, overloaded, delegating, records, objects, generics, and effects

References : `BIP040` (`BIP040-D1`–`BIP040-D10`), `BIP021` (overloads/named/default/rest), `BIP016`/`BIP017` (effects & ownership), `BIP038` (record destructuring).

```babble
;; babble module=shapes.ba
export def Circle = class () (
    def r: double
    def area: double
    private init (radius: double) -> (this.r = radius; this.area = 3.0 * radius * radius)
    init (diameter: int) -> (this(double(diameter) / 2.0))
) static (
    def fromRadius = fn (r: double): Circle -> (this(r))
)
export def Point = record (x: int, y: int) (
    init (assert(this.x >= 0 and this.y >= 0))
    init (both: int) -> (this(both, both))
)
```

## Parameterless init initializes uninitialized def and var fields with local temporaries

```babble
def Sphere = class (def radius: double) (
    def area: double
    var tag: string
    init (
        def r2 = this.radius * this.radius
        this.area = 12.0 * r2
        this.tag = "r=" + this.radius
    )
)
def s = Sphere(2.0)
assert(s.area == 48.0 and s.tag == "r=2.0")
s.tag = "ok"
assert(s.tag == "ok")
```

## Explicit overloaded init constructors on empty class header

```babble
def Vec2 = class () (
    def x: double
    def y: double
    init (x: double, y: double) -> (this.x = x; this.y = y)
    init (s: double) -> (this(s, s))
    init () -> (this(0.0, 0.0))
)
def a = Vec2(1.0, 2.0)
def b = Vec2(3.0)
def c = Vec2()
assert(a.x == 1.0 and a.y == 2.0 and b.x == 3.0 and b.y == 3.0 and c.x == 0.0)
```

## Constructor overload dispatch with named args, defaults, and rest params

```babble
def Bag = class () (
    def label: string
    def total: int
    init (label: string, scale: int = 1, nums: int...) -> (
        var s = 0
        for n in nums then (s += n)
        this.label = label
        this.total = s * scale
    )
    init (v: int) -> (this("one", 1, v))
)
assert(Bag("s", 2, 3, 4).total == 14 and Bag(label: "x").total == 0 and Bag(7).total == 7)
```

## Chained delegation with pre-delegation locals and post-delegation statements

```babble
def Rect = class (def w: int, def h: int) (
    var note: string = "p"
    init (s: int) -> (
        def c = if s < 1 then (1) else (s)
        this(c, c)
        this.note = "sq:" + this.w
    )
    init () -> (this(5); this.note = this.note + ":d")
)
assert(Rect(2, 3).note == "p" and Rect(-4).note == "sq:1" and Rect().note == "sq:5:d")
```

## Private init accessible from static factory and sibling delegating init

```babble
def Token = class () (
    def raw: string
    private init (raw: string) -> (this.raw = raw)
    init (code: int) -> (this("#" + code))
) static (
    def fromRaw = fn (s: string): Token -> (this(s))
    def byName = fn (s: string): Token -> (Token(s))
)
assert(Token(42).raw == "#42" and Token.fromRaw("a").raw == "a" and Token.byName("b").raw == "b")
```

## Record parameterless init, secondary delegating init, equality, and destructuring

```babble
def Range2 = record (lo: int, hi: int) (
    def span: int
    init (assert(this.lo <= this.hi); this.span = this.hi - this.lo)
    init (s: int) -> (def v = if s < 0 then (0) else (s); this(v, v))
)
def r1 = Range2(2, 5)
assert(r1 == Range2(2, 5) and r1.span == 3 and Range2(-3) == Range2(0, 0))
assert((match r1 case is Range2(def a, def b) then (a * 10 + b)) == 25)
```

## Object literal with parameterless init block

```babble
def Config = object (
    def port: int
    var host: string
    init (
        def base = 8000
        this.port = base + 80
        this.host = "localhost"
    )
)
assert(Config.port == 8080 and Config.host == "localhost")
Config.host = "127.0.0.1"
assert(Config.host == "127.0.0.1")
```

## Definite assignment through if/else, throwing branch, and match

```babble
def Br = class (k: int) (
    def a: int
    def b: int
    init (
        if k > 0 then (this.a = k) else if k == 0 then (this.a = 10) else (throw exception("neg"))
        match k case 0 then (this.b = 1) else (this.b = k * 2)
    )
)
def x = Br(3)
def y = Br(0)
assert(x.a == 3 and x.b == 6 and y.a == 10 and y.b == 1)
```

## Definite assignment through try/catch/finally and compound assignment

```babble
def TryInit = class (fail: bool) (
    var c: int
    init (
        try (if fail then (throw exception("boom")); this.c = 1)
        catch e then (this.c = 2)
        finally (this.c += 10)
    )
)
assert(TryInit(false).c == 11 and TryInit(true).c == 12)
```

## Post-initialization method call and compound assignment on initialized var in init

```babble
def Verified = class (n: int) (
    def doubled: int
    var score: int
    init (
        this.doubled = n * 2
        this.score = n
        this.score += 5
        assert(this.sum() == n * 3 + 5)
    )
    def sum = fn (): int -> (this.doubled + this.score)
)
def v = Verified(4)
assert(v.doubled == 8 and v.score == 9 and v.sum() == 17)
```

## Generic class with overloaded init constructors and type argument inference

```babble
def Pair of (A, B) = class () (
    def first: A
    def second: B
    init (a: A, b: B) -> (this.first = a; this.second = b)
    init (a: A, b: B, tag: bool) -> (this(a, b))
)
def p1 = Pair(1, "one")
def p2 = Pair of (int, string)(2, "two", false)
assert(p1.first == 1 and p1.second == "one" and p2.first == 2 and p2.second == "two")
```

## Capturing local class with overloaded init and first-class constructor calls

```babble
def mk = fn (off: int): int -> (
    def S = class () (
        def v: int
        init (x: int) -> (this.v = x + off)
        init (s: string) -> (this.v = s.length() + off)
    )
    def vi = fn (f: fn (int): S, x: int): S -> (f(x))
    def vs = fn (f: fn (string): S, s: string): S -> (f(s))
    S(5).v + S("abc").v + vi(S, 20).v + vs(S, "hello").v
)
assert(mk(10) == 73)
```

## Cross-module exported classes and records with overloaded and private init

```babble
def shapes = import("shapes.ba")
def c1 = shapes.Circle(10)
def c2 = shapes.Circle.fromRadius(2.0)
assert(c1.r == 5.0 and c1.area == 75.0 and c2.r == 2.0 and c2.area == 12.0)
assert(shapes.Point(3, 4).x == 3 and shapes.Point(5).y == 5)
```

## Init effect annotations : nothrow, calling f, and mut parameter

```babble
def Ctr = class () (
    var n: int = 0
    def bump = fn mut (): int -> (this.n += 1; this.n)
)
def Aud = class () (
    def id: int
    init nothrow (v: int) -> (this.id = v)
    init (mut c: Ctr) -> (this.id = c.bump())
    init calling f (f: fn (): int) -> (this.id = f())
)
def ctr = Ctr()
assert(Aud(7).id == 7 and Aud(ctr).id == 1 and Aud(fn (): int -> (99)).id == 99)
```

## Init ownership : unique return preserved for pure init

```babble
def Box = class (var v: int) (
    init (this.v = this.v + 1)
)
def takeUnique = fn (unique b: Box): int -> (b.v)
assert(takeUnique(Box(4)) == 5)
```

## Init ownership : post-init escape into mut parameter succeeds and demotes unique

```babble
def Reg = class () (
    var items: list of any = list()
    def add = fn mut (shared x: any) -> (this.items.add(x))
)
def Tracked = class (def id: int, mut reg: Reg) (
    init (reg.add(this))
)
def r = Reg()
def t = Tracked(9, r)
assert(r.items.size() == 1 and t.id == 9)
```

## Init io and volatile modifiers with static counter

```babble
def Log = class () (
    def n: int
    init io (
        this.n = Log.next()
    )
) static (
    private var count: int = 0
    def next = fn io (): int -> (this.count += 1; this.count)
    def total = fn volatile (): int -> (this.count)
)
def a = Log()
def b = Log()
assert(a.n == 1 and b.n == 2 and Log.total() == 2)
```
