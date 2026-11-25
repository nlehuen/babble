# Module re-exports, interface cycles, cross-module overloads, and HOF effect specialization

References : `BIP006` (modules), `BIP007` (effects & calling), `BIP026` (overloads), `analysis.TypeChecks`, `analysis.ModuleInterface`, `analysis.TypedModuleSummary`, `analysis.LoweringAnalyzer`, `analysis.BlockScopes`, `analysis.HierarchyMetadata`, `analysis.ImportedSummaries`, `analysis.StaticRefs`, `analysis.TypedProgram`.

```babble
;; babble module=base_iface.ba
export def Shape = interface (
    def area: fn (): double
    def kind = fn (): string -> ( "shape" )
)
export def Vec = class (def x: int) (
    def scale = fn (k: int): int -> ( this.x * k )
    def plus = overload(
        fn (n: int): int -> ( this.x + n ),
        fn (s: string): string -> ( "" + this.x + s )
    )
) static (
    def origin = 0
)
export def addOne = fn (x: int): int -> ( x + 1 )
```

```babble
;; babble module=reexport.ba
def base = import("base_iface.ba")
def secretMod = import("base_iface.ba")
def HiddenI = interface ( def f: fn (): int )
export def Shape = base.Shape
export def shapes = base
export def mutBox = base.Vec(1)
export def mutableVal = 1
export def ov = overload(base.addOne, fn (s: string): int -> ( s.length() ))
export def twoLocals = fn (): int -> (
    def f1 = fn (): int -> ( def Dup = class (def a: int) () ; Dup(1).a )
    def f2 = fn (): int -> ( def Dup = class (def b: int) () ; Dup(2).b )
    f1() + f2()
)
```

```babble
;; babble module=diamond_r.ba
def base = import("base_iface.ba")
export def rightShape = interface extends base.Shape (
    def label = fn (): string -> ( "r:" + this.kind() )
)
```

```babble
;; babble module=cyc_iface_a.ba
def b = import("cyc_iface_b.ba")
export def Shape = interface ( def area: fn (): double )
```

```babble
;; babble module=cyc_iface_b.ba
def a = import("cyc_iface_a.ba")
export def Square = class () implements a.Shape ( def area = fn (): double -> ( 1.0 ) )
```

```babble
;; babble module=hof_calling.ba
export def apply = fn calling(f) (x: int, f: fn io (int): int): int -> ( f(x) )
```

## Implementing re-exported interface directly and through re-exported module

```babble
def re = import("reexport.ba")
def A = class () implements re.Shape ( def area = fn (): double -> ( 1.0 ) )
def B = class () implements re.shapes.Shape ( def area = fn (): double -> ( 2.0 ) )
assert(A().area() + B().area() == 3.0 and A().kind() == "shape")
```

## Unexported interface cannot be implemented

```babble
def re = import("reexport.ba")
def C = class () implements re.HiddenI ( def f = fn (): int -> ( 1 ) )
```

```error
implements needs a statically known interface
```

## Unexported module cannot provide interface for implements

```babble
def re = import("reexport.ba")
def C = class () implements re.secretMod.Shape ( def area = fn (): double -> ( 1.0 ) )
```

```error
implements needs a statically known interface
```

## Non-module reassigned export cannot provide interface for implements

```babble
def re = import("reexport.ba")
def C = class () implements re.mutableVal.Shape ()
```

```error
implements needs a statically known interface
```

## Implementing an interface from a module still in an import cycle fails statically

```babble
import("cyc_iface_a.ba")
```

```error
implements needs a statically known interface : cyc_iface_a.ba is still being analysed (an import cycle)
```

## Cross-module overloaded operator, direct method, static member, and re-exported overload

```babble
def b = import("base_iface.ba")
def re = import("reexport.ba")
def v = b.Vec(5)
assert(v + 3 == 8 and v + "!" == "5!" and v.scale(4) == 20 and b.Vec.origin == 0)
assert(re.ov(6) == 7 and re.ov("abc") == 3 and re.twoLocals() == 3)
```

## Diamond transitive module dependencies and hierarchy lookup

```babble
def re = import("reexport.ba")
def dr = import("diamond_r.ba")
def Box = class () implements dr.rightShape ( def area = fn (): double -> ( 4.0 ) )
def x: re.Shape = Box()
assert(x is dr.rightShape and x is re.Shape and Box().label() == "r:shape")
```

## Cross-module higher-order effect specialization via calling clause accepts pure callback

```babble
def h = import("hof_calling.ba")
def pureUse = fn (n: int): int -> ( h.apply(n, fn (v: int): int -> ( v * 3 )) )
assert(pureUse(4) == 12)
```

## Cross-module higher-order effect specialization rejects io callback in pure caller

```babble
def h = import("hof_calling.ba")
def bad = fn (n: int): int -> ( h.apply(n, fn io (v: int): int -> ( println(v) ; v )) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Reading mutable vs immutable module export inside pure and volatile functions

```babble
def re = import("reexport.ba")
def ok = fn (): int -> ( re.ov(1) )
def readMut = fn volatile (): int -> ( re.mutBox.x )
assert(ok() == 2 and readMut() == 1)
```
