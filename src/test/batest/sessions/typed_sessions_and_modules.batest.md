# Typed sessions, persisted hierarchies, axioms, and cross-input modules

References : `BIP006` (modules & sessions), `analysis.AnalysisSession`, `analysis.TypedModuleSummary`, `analysis.ImportedSummaries`, `analysis.HierarchyMetadata`, `analysis.StaticRefs`, `analysis.LoweringAnalyzer`.

```babble
;; babble module=sess_base.ba
export def Tag = interface ( def id: fn (): int )
export def Box of T = class (def v: T) ()
```

```babble
;; babble module=sess_wrap.ba
def base = import("sess_base.ba")
export def SubTag = interface extends base.Tag ( def name = fn (): string -> ( "t" ) )
```

## Generic class, static members, and interface hierarchy persisted across session inputs

```babble
;; babble session
def IB = interface ( def tag = fn (): string -> ("b") )
def IS = interface extends IB ( def code: fn (): int )
def P of (A, B) = class (def a: A, def b: B) implements IS (
    def code = fn (): int -> (7)
) static ( def kind = "p" )
```

```babble
;; babble session
def p: P of (int, string) = P(1, "x")
assert(p.a == 1 and p.b == "x" and p.code() == 7 and p.tag() == "b" and P.kind == "p" and p is IB and p is IS)
```

## Aliasing classes and interfaces from earlier session input resolves session staticSingletons

```babble
;; babble session
def I = interface ( def v: fn (): int )
def C = class (def n: int) implements I ( def v = fn (): int -> ( this.n ) )
var m = 1
m = 2
```

```babble
;; babble session
def AliasI = I
def AliasC = C
def D = class () implements AliasI ( def v = fn (): int -> ( 9 ) )
def x: any = AliasC(4)
assert(x is AliasC and x is AliasI and D().v() == 9 and m == 2)
```

## Axiom declared in earlier session input merges and applies in later input

```babble
;; babble session
axiom java.lang.System.lineSeparator: fn pure nothrow
```

```babble
;; babble session
def getSep = fn nothrow (): string -> ( java.lang.System.lineSeparator() )
assert(getSep().length() > 0)
```

## Module imported in earlier session input exposes transitive interfaces and generics

```babble
;; babble session
def w = import("sess_wrap.ba")
def b = import("sess_base.ba")
```

```babble
;; babble session
def Impl = class () implements w.SubTag ( def id = fn (): int -> ( 5 ) )
def bx: b.Box of int = b.Box(9)
def x: any = Impl()
assert(x is w.SubTag and x is b.Tag and Impl().name() == "t" and bx.v == 9)
```

## Overloaded operator and method defined in earlier session input lower via cross-input signatures

```babble
;; babble session
def Num = class (def x: int) (
    def add = fn (k: int): int -> ( this.x + k )
    def plus = overload(
        fn (k: int): int -> ( this.x + k ),
        fn (s: string): string -> ( "" + this.x + s )
    )
)
```

```babble
;; babble session
def n = Num(10)
assert(n.add(5) == 15 and n + 2 == 12 and n + "!" == "10!")
```

## Expression-only session input does not open a new scope and preserves earlier definitions

```babble
;; babble session
def base = 40
```

```babble
;; babble session
assert(base + 2 == 42)
```

```babble
;; babble session
assert(base == 40)
```

## Multi-input REPL session sharing compiled JVM interfaces and classes across ScriptClassLoaders

```babble
;; babble session
def M = interface (def v: fn (): int ; def d = fn (): int -> (this.v() * 2))
def B = class (n: int) implements M (def v = fn (): int -> (n))
def ev = fn (m: M): int -> (m.v() + m.d())
assert(ev(B(4)) == 12)
```

```babble
;; babble session
def E = class (a: int) implements M (
  def v = fn (): int -> (a + 3)
  def d = fn (): int -> (super.d() + 1)
)
def e: M = E(2)
assert(ev(e) == 16 and (e is M) and (e is E) and not (e is B))
```
