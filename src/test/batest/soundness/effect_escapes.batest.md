# Soundness regressions : `effects` (`E2`, `E3`, `E5`) & `ownership` (`O1`, `O2`) capture-escape, operators & bound methods

Promoted from `BIP032` quarantine (`effects_e2_object_calling_io_exit.batest.md`, `effects_e2_operator_method_launders_calling_and_borrow.batest.md`, `effects_e3_bound_method_bypasses_mut_and_frozen.batest.md`) and sister capture-escape variants covering `InitEffectAnalyzer`, `EffectBodyWalker`, `OperatorAndLoopEffectAnalyzer`, `EffectModifiers`, `CallEffectAnalyzer`, `MemberTypes`, and `ModalKindTable`.

```babble
;; babble preamble
def Runner = interface (
    def run: fn (): int
)
def Holder = class () (
    var item: any? = null
    def put = fn mut (shared v: any) -> (this.item = v)
)
def smuggle = fn (r: Runner): int -> (r.run())
def boom = fn io (): int -> (println("ESCAPE:IO"); 0)
```

## Non-io function must not invoke System.exit(42) via object method capturing calling param

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    object implements Runner (
        def run = fn (): int -> (f())
    )
)
def exitBoom = fn io (): int -> (java.lang.System.exit(42); 0)
def r = capture(exitBoom)
smuggle(r)
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Object capturing calling param bound to local def must not escape via return

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    def r = object implements Runner (
        def run = fn (): int -> (f())
    )
    r
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Object init storing calling param into field must not escape via return

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    object implements Runner (
        var cb: fn (): int = fn (): int -> (0)
        init (this.cb = f)
        def run = fn (): int -> (this.cb())
    )
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Object method with 2-hop nested closure capturing calling param must not escape

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    object implements Runner (
        def run = fn (): int -> ((fn (): int -> (f()))())
    )
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Object method capturing pseudo-forwarded calling param must not escape

```babble
def outer = fn (f: fn (): int) -> (
    fn calling f (): Runner -> (
        object implements Runner (
            def run = fn (): int -> (f())
        )
    )
)
smuggle(outer(boom)())
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Non-escaping object method calling enclosing calling param must not satisfy pure interface

```babble
def trigger = fn calling f (f: fn (): int): int -> (
    def r: Runner = object implements Runner (
        def run = fn (): int -> (f())
    )
    smuggle(r)
)
trigger(boom)
```

```error
Type error : expected () : int, got io () : int
```

## Local class method capturing calling param must not escape via instance return

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    def Local = class () implements Runner (
        def run = fn (): int -> (f())
    )
    Local()
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Local record method capturing calling param must not escape via instance return

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    def Local = record () implements Runner (
        def run = fn (): int -> (f())
    )
    Local()
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Local interface default method capturing calling param is rejected

```babble
def capture = fn calling f (f: fn (): int): Runner -> (
    def LocalIface = interface extends Runner (
        def run = fn (): int -> (f())
    )
    def Local = class () implements LocalIface ()
    Local()
)
smuggle(capture(boom))
```

```error
Parameter in calling clause escapes via interface method run : remove f from calling and mark it as shared
```

## Overload arm capturing calling param must not escape via return

```babble
def capture = fn calling f (f: fn (): int) -> (
    overload(fn (): int -> (f()), fn (x: int): int -> (x))
)
def ov = capture(boom)
def pureCall = fn (): int -> (ov())
pureCall()
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Object method capturing borrowed param must not escape into mut sink

```babble
def stash = fn (mut h: Holder, p: list of int) -> (
    h.put(object (
        def get = fn volatile (): list of int -> (p)
    ))
)
```

```error
Borrowed parameter p escapes via call to h.put : mark parameter as shared p
```

## Object capturing borrowed param propagates return-through-borrow provenance

```babble
def wrapObj = fn (p: list of int) -> (
    object (
        def get = fn volatile (): list of int -> (p)
    )
)
def stash = fn (mut h: Holder, p: list of int) -> (
    h.put(wrapObj(p))
)
```

```error
Borrowed parameter p escapes via call to h.put : mark parameter as shared p
```

## Local class method capturing borrowed param must not escape into mut sink

```babble
def stash = fn (mut h: Holder, p: list of int) -> (
    def Local = class () (
        def get = fn volatile (): list of int -> (p)
    )
    h.put(Local())
)
```

```error
Borrowed parameter p escapes via call to h.put : mark parameter as shared p
```

## Overload arm capturing borrowed param must not escape into mut sink

```babble
def stash = fn (mut h: Holder, p: list of int) -> (
    h.put(overload(fn volatile (): list of int -> (p), fn (x: int): int -> (x)))
)
```

```error
Borrowed parameter p escapes via call to h.put : mark parameter as shared p
```

## Synchronous object inline field call to calling param remains allowed

```babble
def makeBox = fn calling f (f: fn (): int) -> (
    object (def v: int = f())
)
assert(makeBox(fn (): int -> (42)).v == 42)
```

## Object capturing pure callback or borrowed list for local read remains allowed

```babble
def wrapPure = fn (f: fn (): int): Runner -> (
    object implements Runner (def run = fn (): int -> (f()))
)
def wrapList = fn (xs: list of int) -> (
    object (def first = fn volatile (): int -> (xs.get(0)))
)
assert(wrapPure(fn (): int -> (7)).run() == 7)
assert(wrapList(list(99)).first() == 99)
```

## Binary operator plus must not launder io callback out of calling clause

```babble
def Wrap = class () (
    def plus = fn (f: fn (): int): fn (): int -> (f)
)
def launder = fn calling f (f: fn (): int): fn (): int -> (Wrap() + f)
def sneaky: fn (): int = launder(fn io (): int -> (print("PWNED!"); 0))
def _ = sneaky()
```

```error
Parameter in calling clause escapes via return : remove f from calling
```

## Unary operator neg must not escape borrowed parameter

```babble
def Box = class (var n: int) (
    def neg = fn (): Box -> (this)
)
var leaked: Box? = null
def steal = fn io (b: Box) -> (leaked = -b)
steal(Box(1))
```

```error
Borrowed parameter b escapes via outer variable leaked : mark parameter as shared b
```

## Parenthesized bound mut method must not mutate frozen receiver

```babble
def Box = class (var n: int) (
    def inc = fn mut (): int -> (this.n = this.n + 1; this.n)
)
def bad = fn io (fb: frozen Box): int -> ((fb.inc)())
def _ = bad(freeze(Box(1)))
```

```error
Cannot call mut method on frozen value
```

## Calling first-class bound mut method on global must be rejected in pure function

```babble
def Box = class (var n: int) (
    def inc = fn mut (): int -> (this.n = this.n + 1; this.n)
)
def globalBox = Box(0)
def bumpGlobal = globalBox.inc
def pureMut = fn (): int -> (bumpGlobal())
def _ = pureMut()
```

```error
Function pureMut violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Bound getter on frozen receiver must preserve frozen return type

```babble
def Box = class (var n: int) ()
def Outer = class (def inner: Box) (
    def getInner = fn (): Box -> (this.inner)
)
def fo: frozen Outer = freeze(Outer(Box(1)))
def leaked: Box = (fo.getInner)()
leaked.n = 99
```

```error
Type error : expected Box, got frozen Box
```
