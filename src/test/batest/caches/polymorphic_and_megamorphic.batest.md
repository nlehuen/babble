# Polymorphic (2-4 shapes) and megamorphic (5+ shapes, > MAX_DEPTH) invokedynamic inline caches

References : BIP001, BIP002, BIP009 ; targets Bootstrap (SelectCache, CallCache, TypedCallCache), JavaLinker

```babble
;; babble preamble
def Sh = interface ( def v: fn (): int )
def S1 = class (x: int) implements Sh ( def v = fn (): int -> (x + 1) )
def S2 = class (x: int) implements Sh ( def v = fn (): int -> (x + 2) )
def S3 = class (x: int) implements Sh ( def v = fn (): int -> (x + 3) )
def S4 = class (x: int) implements Sh ( def v = fn (): int -> (x + 4) )
def S5 = class (x: int) implements Sh ( def v = fn (): int -> (x + 5) )
def S6 = object implements Sh ( def x = 10 ; def v = fn (): int -> (this.x + 6) )
def F = type fn (int): int

;; Test rig : invokes 4 polymorphic callables across `n` passes at a single call site
def run4 = fn (a: F, b: F, c: F, d: F, n: int): int -> (
    var t = 0 ; var p = 0
    while p < n then (
        var i = 0
        while i < 4 then (t += (if i == 0 then (a) else if i == 1 then (b) else if i == 2 then (c) else (d))(i) ; i += 1)
        p += 1
    )
    t
)
```

## Megamorphic SelectCache & CallSite across 5 Babble classes + 1 singleton object

```babble
def pick = fn volatile (i : int) : Sh -> (
    def k = i - int(i / 6) * 6
    if k == 0 then ( S1(i) ) else if k == 1 then ( S2(i) ) else if k == 2 then ( S3(i) )
    else if k == 3 then ( S4(i) ) else if k == 4 then ( S5(i) ) else ( S6 )
)
def invoke = fn (s : Sh) : int -> ( s.v() )
var total = 0 ; var i = 0
while i < 18 then ( total += invoke(pick(i)) ; i += 1 )
assert(total == 213)
```

## Megamorphic TypedCallCache across 6 distinct closures

```babble
def fs = list.of(
    fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x + 2 ),
    fn (x: int): int -> ( x + 3 ), fn (x: int): int -> ( x + 4 ),
    fn (x: int): int -> ( x + 5 ), fn (x: int): int -> ( x * 2 )
)
var sum = 0 ; var i = 0
while i < 12 then (
    def f = fs.get(i - int(i / 6) * 6)
    sum += f(i)
    i += 1
)
assert(sum == 112)
```

## Megamorphic CallCache across 6 optional-arg closures

```babble
def gs = (list of (fn (int): int)).of(
    fn (x: int, y: int = 0): int -> ( x + y + 1 ), fn (x: int, y: int = 0): int -> ( x + y + 2 ),
    fn (x: int, y: int = 0): int -> ( x + y + 3 ), fn (x: int, y: int = 0): int -> ( x + y + 4 ),
    fn (x: int, y: int = 0): int -> ( x + y + 5 ), fn (x: int, y: int = 0): int -> ( x + y + 6 )
)
var s = 0
for g in gs then ( s += g(0) )
assert(s == 21)
```

## Megamorphic Java method dispatch on 5 distinct Collection receiver classes

```babble
def items = (list of (java.util.Collection of int)).of(
    java.util.ArrayList(), java.util.LinkedList(), java.util.Vector(),
    java.util.Stack(), java.util.ArrayDeque()
)
var sizes = 0 ; var j = 0
while j < 10 then (
    sizes += items.get(j - int(j / 5) * 5).size()
    j += 1
)
assert(sizes == 0)
```

## BoundMethod(CompiledFunction) spec in loop : external fn in field (isBoundFunctionSpec, boundFunction, isSame)

```babble
def extSpec = fn (x: int): int -> ( x + 10 )
def Holder = class () ( def ms = extSpec )
def h = Holder()
def bmSpec: fn (int): int = h.ms
var s = 0 ; var i = 0
while i < 3 then ( s += bmSpec(i) ; i += 1 )
assert(s == 33)
```

## BoundMethod(CompiledFunction) spread in loop : external fn in field (isBoundFunction, boundFunction, isSame)

```babble
def extSpread = fn (x: int, y: int = 5): int -> ( x + y )
def Holder = class () ( def md = extSpread )
def h = Holder()
def bmDirect = h.md
var s = 0 ; var i = 0
while i < 3 then ( s += bmDirect(i) ; i += 1 )
assert(s == 18)
```

## Static field on Java instance in loop (SelectCache isSame)

```babble
var total = 0 ; var i = 0
while i < 3 then (
    total += java.io.StreamTokenizer(java.io.StringReader("x")).TT_EOF
    i += 1
)
assert(total == -3)
```

## TypedCallCache 0-arg constructor, 0-arg BoundJavaMethod, and union primitive field SelectCache

```babble
def Z = class () ()
def makeZ : fn (): Z = Z
assert(classof(makeZ()) == Z)
def sbLen : fn io (): int = java.lang.StringBuilder("abc").length
assert(sbLen() == 3)
def UA = class (def v: int) ()
def UB = class (def v: int) ()
def pickU = fn (f: bool): UA | UB -> ( if f then ( UA(3) ) else ( UB(7) ) )
assert(pickU(true).v == 3 and pickU(false).v == 7)
```

## Interface default methods called via bound function refs in loops (spec & spread)

```babble
def IDef = interface (
    def dmSpec = fn (x: int): int -> ( x + 10 )
    def dmSpread = fn (x: int, y: int = 5): int -> ( x + y )
)
def ci = (class () implements IDef ())()
def fSpec : fn (int): int = ci.dmSpec
def fSpread : fn (int): int = ci.dmSpread
var s1 = 0 ; var s2 = 0 ; var i = 0
while i < 3 then ( s1 += fSpec(i) ; s2 += fSpread(i) ; i += 1 )
assert(s1 == 33 and s2 == 18)
```

## Interface default method with any? optional param called via bound ref in loop

```babble
def IDef = interface (
    def dmAny = fn (x: any?, y: any? = 5): any? -> ( (x as int) + (y as int) )
)
def ci = (class () implements IDef ())()
def fAny = ci.dmAny
var s = 0 ; var i = 0
while i < 3 then ( s += fAny(i) as int ; i += 1 )
assert(s == 18)
```

## Bound interface default method with contravariant union param called through narrower primitive function type

```babble
def IfaceDef = interface (
    def defm = fn (x: int | string): any -> ( x )
)
def CDef = class () implements IfaceDef ()
def callAny: fn (x: int): any = CDef().defm
assert(callAny(42) == 42)
```

## Polymorphic TypedCallCache mixing CompiledFunction, CompiledMethod, and BoundMethod(CompiledFunction)

```babble
def I = interface ( def a = fn (x: int): int -> ( x + 100 ) )
def c = (class () implements I ( def m = fn (x: int): int -> ( x + 10 ) ))()
assert(run4(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x + 2 ), c.m, c.a, 1) == 119)
```

## Polymorphic boxed CallCache mixing CompiledFunction, CompiledMethod, and interface default

```babble
def I = interface ( def a = fn (x: int, s: string): string -> ( s + ":" + x ) )
def c = (class () implements I ( def m = fn (x: int, s: string): string -> ( s + "=" + (x + 10) ) ))()
def fs = list.of(fn (x: int, s: string): string -> ( s + "!" ), c.m, c.a, c.a)
var o = "" ; var i = 0
while i < 4 then ( o += fs.get(i)(i, "k") + ";" ; i += 1 )
assert(o == "k!;k=11;k:2;k:3;")
```

## Polymorphic CallCache predicate guards across 2 passes : 2 bound defaults, bound method, function

```babble
def I1 = interface ( def d = fn (x: int, y: int = 10): int -> ( x + y + 100 ) )
def I2 = interface ( def d = fn (x: int, y: int = 20): int -> ( x + y + 200 ) )
def c1 = (class () implements I1 ( def m = fn (x: int, y: int = 1): int -> ( x + y + 10 ) ))()
def c2 = (class () implements I2 ())()
assert(run4(c1.d, c2.d, c1.m, fn (x: int, y: int = 3): int -> ( x + y ), 2) == 700)
```

## Polymorphic CallCache predicate guards across 2 passes : 2 bound methods, 2 functions

```babble
def c1 = (class () ( def m = fn (x: int, y: int = 1): int -> ( x + y + 10 ) ))()
def c2 = (class () ( def m = fn (x: int, y: int = 2): int -> ( x + y + 20 ) ))()
assert(run4(c1.m, c2.m, fn (x: int, y: int = 3): int -> ( x + y ), fn (x: int, y: int = 4): int -> ( x + y ), 2) == 92)
```

## Polymorphic TypedCallCache spec guards across 2 passes : 2 bound defaults, bound method, function

```babble
def I1 = interface ( def d = fn (x: int): int -> ( x + 100 ) )
def I2 = interface ( def d = fn (x: int): int -> ( x + 200 ) )
def c1 = (class () implements I1 ( def m = fn (x: int): int -> ( x + 10 ) ))()
def c2 = (class () implements I2 ())()
assert(run4(c1.d, c2.d, c1.m, fn (x: int): int -> ( x + 1 ), 2) == 634)
```

## Polymorphic TypedCallCache spec guards across 2 passes : 2 bound methods, 2 functions

```babble
def c1 = (class () ( def m = fn (x: int): int -> ( x + 10 ) ))()
def c2 = (class () ( def m = fn (x: int): int -> ( x + 20 ) ))()
assert(run4(c1.m, c2.m, fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x + 2 ), 2) == 78)
```

## SelectCache javaDefaultMethods miss on BabbleInstance implementing Java interface

```babble
def Cmp = class () implements java.util.Comparator of int (
    def compare = fn (a : int, b : int) : int -> ( a - b )
)
def c = Cmp()
def rev1 = c.reversed()
def rev2 = c.reversed()
assert(rev1.compare(1, 5) > 0)
assert(rev2.compare(5, 1) > 0 == false)
```

## TypedCallCache defaultValue for omitted reference, bool, byte, and short optional params

```babble
def c1 = fn (f: fn (int): int, x: int): int -> ( f(x) )
def fR = fn (x: int, s: string = "hi"): int -> ( x + s.length() )
def fZ = fn (x: int, b: bool = true): int -> ( if b then ( x + 1 ) else ( x ) )
def fB = fn (x: int, b: byte = byte(2)): int -> ( x + int(b) )
def fS = fn (x: int, s: short = short(3)): int -> ( x + int(s) )
assert(c1(fR, 1) + c1(fZ, 1) + c1(fB, 1) + c1(fS, 1) == 12)
```

## TypedCallCache defaultValue for omitted char, long, float, and double optional params

```babble
def c2 = fn (f: fn (int): int, x: int): int -> ( f(x) )
def fC = fn (x: int, c: char = 'A'): int -> ( x + int(c) )
def fL = fn (x: int, l: long = 5L): int -> ( x + int(l) )
def fF = fn (x: int, f: float = 2.0f): int -> ( x + int(f) )
def fD = fn (x: int, d: double = 4.0): int -> ( x + int(d) )
assert(c2(fC, 0) + c2(fL, 1) + c2(fF, 1) + c2(fD, 1) == 79)
```

## TypedCallCache missingBits adaptSpec contravariant param and direct-handle fallback

```babble
def invoke = fn (f: fn (a: int = ..., b: int = ...): int): int -> ( f(b: 10) )
def gContravar = fn (a: int | string = 5, b: int = 0): int -> ( (a as int) + b )
def gFallback = fn (a: int = 7, b: int | string = 0): int -> ( a + (b as int) )
def gSame = fn (a: int = 2, b: int = 0): int -> ( a + b )
assert(invoke(gContravar) == 15 and invoke(gFallback) == 17 and invoke(gSame) == 12)
```

## TypedCallCache bound CompiledMethod and bound interface default with rest and adaptSpec miss

```babble
def I = interface (
    def dRest = fn (x: int, xs: int...): int -> ( x )
    def dWide = fn (x: int | string): int -> ( x as int )
)
def C = class () implements I (
    def mRest = fn (x: int, xs: int...): int -> ( x )
)
def run = fn (f: fn (int): int): int -> ( f(4) )
def c = C()
assert(run(c.mRest) == 4 and run(c.dRest) == 4 and run(c.dWide) == 4)
```

## Boxed CallCache callTest on 9-param and rest-param unbound functions

```babble
def callBox = fn (f: fn (any): any, x: any): int -> ( f(x) as int )
def f9 = fn (a: any, b: int = 1, c: int = 1, d: int = 1, e: int = 1, f: int = 1, g: int = 1, h: int = 1, i: int = 1): any -> ( (a as int) + i )
def fRest = fn (x: any, xs: int...): any -> ( x )
assert(callBox(f9, 4) == 5 and callBox(fRest, 5) == 5)
```

## Boxed CallCache callTest on 9-param and rest-param bound interface defaults

```babble
def I = interface (
    def d9 = fn (a: any, b: int = 1, c: int = 1, d: int = 1, e: int = 1, f: int = 1, g: int = 1, h: int = 1, i: int = 2): any -> ( (a as int) + i )
    def dR = fn (x: any, xs: int...): any -> ( x )
)
def c = (class () implements I ())()
def go = fn (f: fn (any): any): int -> ( f(3) as int )
assert(go(c.d9) == 5 and go(c.dR) == 3)
```

## Boxed CallCache callTest on 9-param and rest-param bound class methods

```babble
def C = class () (
    def m9 = fn (a: any, b: int = 1, c: int = 1, d: int = 1, e: int = 1, f: int = 1, g: int = 1, h: int = 1, i: int = 3): any -> ( (a as int) + i )
    def mR = fn (x: any, xs: int...): any -> ( x )
)
def c = C()
def go = fn (f: fn (any): any): int -> ( f(3) as int )
assert(go(c.m9) == 6 and go(c.mR) == 3)
```

## Polymorphic boxed CallCache guards mixing bound default, bound method, and unbound function

```babble
def I = interface ( def d = fn (x: any): any -> ( (x as int) + 10 ) )
def C = class () implements I ( def m = fn (x: any): any -> ( (x as int) + 20 ) )
def c = C()
def f = fn (x: any): any -> ( (x as int) + 30 )
def call = fn (g: fn (any): any, v: any): int -> ( g(v) as int )
assert(call(c.d, 1) + call(c.m, 1) + call(f, 1) + call(c.d, 2) + call(c.m, 2) + call(f, 2) == 129)
```

## SelectCache non-final static JavaClass field, isClass null miss, and isSame mismatch

```babble
def sCls = import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers
assert(sCls.mutableCounter is int)
def P1 = class (def x: int) ()
def readX = fn (o: P1?): int? -> ( o?.x )
assert(readX(P1(5)) == 5 and readX(null) == null)
assert(java.lang.Integer.MAX_VALUE == 2147483647 and java.lang.Short.MAX_VALUE == 32767)
```
