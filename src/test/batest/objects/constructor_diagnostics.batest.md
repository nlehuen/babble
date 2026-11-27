# Constructors (`init`) : static diagnostics and negative specifications

References : `BIP040` §5.6 (complete diagnostic catalog).

```babble
;; babble module=priv_ctor.ba
export def Secret = class () (
    def x: int
    private init (x: int) -> (this.x = x)
)
```

## Init outside a class, record, or object body

```babble
init (1)
```

```error
init is only allowed in a class, record, or object body
```

## Bare executable statement in a class body

```babble
def C = class (x: int) (
    assert(x > 0)
)
```

```error
Executable statements in a class body must be inside an init block
```

## Bare executable statement in a record body

```babble
def R = record (x: int) (
    assert(x > 0)
)
```

```error
Executable statements in a record body must be inside an init block
```

## Bare executable statement in an object body

```babble
def O = object (
    assert(true)
)
```

```error
Executable statements in an object body must be inside an init block
```

## Uninitialized field requires a type annotation

```babble
def C = class () (
    def x
    init (this.x = 1)
)
```

```error
Uninitialized field requires a type annotation : x
```

## Uninitialized field in a class with no init

```babble
def C = class () (
    def x: int
)
```

```error
Field requires an initializer or an init constructor : x
```

## Duplicate parameterless init initializer

```babble
def C = class () (
    init (assert(true))
    init (assert(true))
)
```

```error
Duplicate init initializer
```

## Mixing parameterless init with explicit init on empty class header

```babble
def C = class () (
    def x: int
    init (this.x = 1)
    init (v: int) -> (this.x = v)
)
```

```error
Cannot mix parameterless init block with explicit init constructors on an empty class header
```

## Parameterized init in object literal

```babble
def O = object (
    init (x: int) -> (assert(x > 0))
)
```

```error
object literals cannot have parameterized init constructors
```

## Private init in object literal

```babble
def O = object (
    private init (assert(true))
)
```

```error
private is not allowed on object init
```

## Init in interface

```babble
def I = interface (
    init (assert(true))
)
```

```error
An interface cannot declare an init constructor
```

## Calling private init from outside its class

```babble
def C = class () (
    def x: int
    private init (x: int) -> (this.x = x)
)
def c = C(1)
```

```error
Constructor is private : C
```

## Calling cross-module private init from outside its class

```babble
def m = import("priv_ctor.ba")
def s = m.Secret(1)
```

```error
Constructor is private : Secret
```

## Accessing init as a member on instance or class fails

```babble
def C = class () (
    def x: int
    init (this.x = 1)
)
def c = C()
c.init
```

```error
No such member : init
```

## Using this before delegating constructor call

```babble
def C = class (def x: int) (
    init (a: int, b: int) -> (
        def prev = this.x
        this(a + b)
    )
)
```

```error
Cannot use this before delegating constructor call
```

## Secondary init constructor fails to delegate

```babble
def C = class (def x: int) (
    init (a: int, b: int) -> (
        assert(a + b > 0)
    )
)
```

```error
Secondary init constructor must delegate to this(...)
```

## Mixed delegating and non-delegating paths

```babble
def C = class () (
    def x: int
    init (a: int) -> (this.x = a)
    init (a: int, flag: bool) -> (
        if flag then (this(a)) else (this.x = a)
    )
)
```

```error
Constructor must either delegate on all paths or initialize fields directly
```

## Delegation inside a loop

```babble
def C = class (def x: int) (
    init (a: int, b: int) -> (
        while a > 0 then (this(b))
    )
)
```

```error
Delegating constructor call cannot appear inside a loop or closure
```

## Delegation inside a closure

```babble
def C = class (def x: int) (
    init (a: int, b: int) -> (
        def f = fn () -> (this(a + b))
        f()
    )
)
```

```error
Delegating constructor call cannot appear inside a loop or closure
```

## Cyclic constructor delegation

```babble
def C = class () (
    def x: int
    init (a: int) -> (this(a, 1))
    init (a: int, b: int) -> (this(a + b))
)
```

```error
Cyclic constructor delegation
```

## Field not initialized on all normal paths

```babble
def C = class (flag: bool) (
    def x: int
    init (
        if flag then (this.x = 1)
    )
)
```

```error
Field may not be initialized by init : x
```

## Reading field before initialization

```babble
def C = class () (
    def x: int
    def y: int
    init (
        this.y = this.x + 1
        this.x = 2
    )
)
```

```error
Field used before initialization : x
```

## Reassigning a def field in init

```babble
def C = class () (
    def x: int
    init (
        this.x = 1
        this.x = 2
    )
)
```

```error
Cannot assign to def field : x
```

## Assigning a def field inside a loop in init

```babble
def C = class (n: int) (
    def x: int
    init (
        var i = 0
        while i < n then (this.x = i; i += 1)
    )
)
```

```error
Cannot assign to def field in a loop : x
```

## Assigning a def field inside a closure in init

```babble
def C = class () (
    def x: int = 1
    init (
        def f = fn () -> (this.x = 2)
        f()
    )
)
```

```error
Cannot assign to def member : x
```

## Calling method on this before all fields are initialized

```babble
def C = class () (
    def x: int
    init (
        def v = this.get()
        this.x = v
    )
    def get = fn (): int -> (1)
)
```

```error
Cannot use this before all fields are initialized
```

## Escaping this in record init

```babble
def R = record (x: int) (
    def m = fn (): int -> (this.x)
    init (
        assert(this.m() > 0)
    )
)
```

```error
this cannot escape a value class under construction
```

## Post-delegation statements in record secondary init

```babble
def R = record (x: int, y: int) (
    init (v: int) -> (
        this(v, v)
        assert(this.x == v)
    )
)
```

```error
Record delegating init cannot have statements after this(...)
```

## Return inside init

```babble
def C = class () (
    init (
        return 1
    )
)
```

```error
return in an init constructor
```

## Recurse inside init

```babble
def C = class () (
    init (
        recurse()
    )
)
```

```error
recurse cannot be used in an init constructor
```

## Mut modifier on init is rejected

```babble
def C = class () (
    var x: int
    init mut (this.x = 1)
)
```

```error
mut is not allowed on init : constructing this is always allowed
```

## Record init cannot be io

```babble
def R = record (x: int) (
    init io (
        println(this.x)
    )
)
```

```error
Record init cannot be io
```

## Record init cannot be volatile

```babble
def R = record (x: int) (
    init volatile (
        assert(this.x > 0)
    )
)
```

```error
Record init cannot be volatile
```

## Cannot move this inside init

```babble
def C = class (def x: int) (
    init (
        def y = move(this)
        assert(y.x > 0)
    )
)
```

```error
Cannot move or consume this inside init
```

## Cannot freeze this inside init

```babble
def C = class (def x: int) (
    init (
        def y = freeze(this)
        assert(y.x > 0)
    )
)
```

```error
Cannot move or consume this inside init
```

## Cannot discard this inside init

```babble
def C = class (def x: int) (
    init (
        discard(this)
    )
)
```

```error
Cannot move or consume this inside init
```

## Cannot pass this to unique sink inside init

```babble
def consume = fn (unique x: any): bool -> (x != null)
def C = class (def x: int) (
    init (
        assert(consume(this))
    )
)
```

```error
Cannot move or consume this inside init
```

## Pure init calling io function fails without io modifier

```babble
def C = class (def x: int) (
    init (
        println(this.x)
    )
)
```

```error
Constructor violates pure guarantee (performs I/O or external mutation) : mark init as io
```

## Demoted non-unique constructor cannot satisfy unique parameter

```babble
def Reg = class () (
    var items: list of any = list()
    def add = fn mut (shared x: any) -> (this.items.add(x))
)
def Tracked = class (def id: int, mut reg: Reg) (
    init (reg.add(this))
)
def takeU = fn (unique t: Tracked): int -> (t.id)
def r = Reg()
takeU(Tracked(1, r))
```

```error
Argument for unique parameter t must be unique
```
