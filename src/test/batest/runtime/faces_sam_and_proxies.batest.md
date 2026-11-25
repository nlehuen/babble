# SAM adapters, proxy fallback, and OverloadSet Java-interface dispatch

References : `BIP001` (JVM interfaces & SAM metafactory), `BIP008` (strict typing & overloads), `Faces`, `OverloadSet`, `JavaTypes`.

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def Demo = tests.JavaClassDemo
def dyn = fn (v: any): any -> ( v )

def findM = fn io (shared cls: java.lang.Class of any?, name: string): java.lang.reflect.Method -> (
    for m in cls.getMethods() then (
        if m != null and m.getName() == name then ( return m )
    )
    cls.getMethods().get(0)!
)

def makeP17 = fn io () -> (
    Demo.Arity17Sam(fn (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) -> ( a + b ))
)

def args17 = fn io (shared a: any?, shared b: any?, shared c: any?, shared d: any?) -> (
    def l: java.util.List of any? = java.util.ArrayList()
    l.addAll(java.util.Collections.nCopies(17, 0))
    l.set(0, a) ; l.set(1, b) ; l.set(2, c) ; l.set(3, d)
    l.toArray()
)
```

## Multi-abstract Compilable SAM hits bridge collection and falls back to Proxy

```babble
def comp = javax.script.Compilable(fn (x: any?): javax.script.CompiledScript? -> ( null ))
assert(Demo.isProxy(comp) and comp.equals(comp) and not comp.equals(null))
```

## SAM adapters across arities 3 and 4

```babble
def ih = java.lang.reflect.InvocationHandler(fn (p: any?, m: any?, a: any?): any? -> ( 42 ))
assert(ih.invoke(null, null, null) == 42)
def fmt = java.util.Formattable(fn (f: any?, fl: int, w: int, p: int) -> ())
fmt.formatTo(null, 1, 2, 3)
```

## SAM adapters across arities 5 and 6

```babble
def jl = (javax.swing.JList of any?)()
def lcr: javax.swing.ListCellRenderer of any? = fn (l, v, i, s, f) -> ( null )
assert(lcr.getListCellRendererComponent(jl, "v", 0, true, false) == null)
def obs = java.awt.image.ImageObserver(fn (i: any?, f: int, x: int, y: int, w: int, h: int): bool -> ( true ))
assert(obs.imageUpdate(null, 1, 2, 3, 4, 5))
```

## SAM adapter at arity 7

```babble
def jt = javax.swing.JTree()
def tcr: javax.swing.tree.TreeCellRenderer = fn (t, v, s, e, l, r, f) -> ( null )
assert(tcr.getTreeCellRendererComponent(jt, "v", true, false, true, 0, false) == null)
```

## Varargs SAM adapter packs rest arguments and passes raw array

```babble
def s1 = Demo.VarArgsSink(fn (xs: int...): int -> ( var t = 0 ; for x in xs then ( t += x ) ; t ))
def s2 = Demo.VarArgsSink(fn (xs: array of int): int -> ( xs.size() ))
assert(Demo.invokeVarArgsSink(s1, 1, 2, 3) == 6 and Demo.invokeVarArgsSink(s2, 4, 5) == 2)
```

## Varargs SAM adapter dispatches bound method callable

```babble
def Acc = class (def b: int) ( def add = fn (xs: array of int): int -> ( this.b + xs.size() ) )
def s3 = Demo.VarArgsSink(Acc(10).add)
assert(Demo.invokeVarArgsSink(s3, 7, 8) == 12)
```

## Babble classes implementing varargs Java interface via single rest and array methods

```babble
def PlainSink = class () implements tests.JavaClassDemo.VarArgsSink (
    def sum = fn (xs: int...): int -> ( var t = 0 ; for x in xs then ( t += x ) ; t )
)
def ArrSink = class () implements tests.JavaClassDemo.VarArgsSink (
    def sum = fn (xs: array of int): int -> ( xs.size() )
)
assert(Demo.invokeVarArgsSink(PlainSink(), 4, 5) == 9)
assert(Demo.invokeVarArgsSink(ArrSink(), 7, 8, 9) == 3)
```

## Babble class implementing varargs Java interface via overloaded rest method

```babble
def OvSink = class () implements tests.JavaClassDemo.VarArgsSink (
    def sum = overload (
        fn (s: string): int -> ( s.length() ),
        fn (xs: int...): int -> ( var t = 0 ; for x in xs then ( t += x ) ; t )
    )
)
assert(Demo.invokeVarArgsSink(OvSink(), 1, 2, 3) == 6)
```

## Babble class implementing varargs Java interface via overloaded array method

```babble
def OvArr = class () implements tests.JavaClassDemo.VarArgsSink (
    def sum = overload (
        fn (s: string): int -> ( s.length() ),
        fn (xs: array of int): int -> ( xs.size() )
    )
)
assert(Demo.invokeVarArgsSink(OvArr(), 4, 5) == 2)
```

## Overloaded Java interface method picks most-specific alternative when widest is first

```babble
def C1 = class () implements tests.JavaClassDemo.StringCriterion (
    def test = overload (
        fn (x: any?): bool -> ( false ),
        fn (s: java.lang.CharSequence): bool -> ( s != null ),
        fn (s: string): bool -> ( s == "ok" )
    )
)
def s1: tests.JavaClassDemo.StringCriterion = C1()
assert(s1.test("ok") and not s1.test("no"))
```

## Overloaded Java interface method picks most-specific alternative when narrowest is first

```babble
def C2 = class () implements tests.JavaClassDemo.StringCriterion (
    def test = overload (
        fn (s: string): bool -> ( s == "ok" ),
        fn (x: any?): bool -> ( false )
    )
)
def s2: tests.JavaClassDemo.StringCriterion = C2()
assert(s2.test("ok") and not s2.test("no"))
```

## Overloaded method with non-matching Java default falls back to interface default

```babble
def C = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x: int): int -> ( x + 1 )
    def andThen = overload ( fn (x: int): int -> ( x ), fn (s: string): string -> ( s ) )
)
def op: java.util.function.IntUnaryOperator = C()
def dbl = java.util.function.IntUnaryOperator(fn (x: int): int -> ( x * 2 ))
assert(op.andThen(dbl).applyAsInt(3) == 8)
```

## OverloadSet binding preserves non-BabbleFunction bound method alternatives

```babble
def H = class (def k: int) ( def f = fn (n: int): int -> ( this.k + n ) )
def h = H(10)
def C = class () implements java.util.function.IntUnaryOperator (
    def applyAsInt = overload ( h.f, fn (s: string): int -> ( s.length() ) )
)
def op: java.util.function.IntUnaryOperator = C()
def b = C().applyAsInt
assert(op.applyAsInt(5) == 15 and b(3) == 13 and b("ab") == 2)
```

## SAM arity mismatch with optional parameter reports arity range

```babble
def opt = fn (a: int = 0): int -> ( a )
java.util.function.IntBinaryOperator(opt)
```

```error
IntBinaryOperator.applyAsInt takes 2 arguments, function takes 0 to 1
```

## Proxy FunctionHandler rejects non-default method matching SAM name with wrong arity

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
def fnCls = java.lang.Class.forName("java.util.function.Function")
def apply1 = fnCls.getMethod("apply", java.lang.Class.forName("java.lang.Object"))
h.invoke(p17, apply1, java.util.List.of("x").toArray())
```

```error
No implementation of Function.apply(Object)
```

## Proxy FunctionHandler rejects Objects.equals overload via isObjectMethod arity guard

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
def objCls = java.lang.Class.forName("java.util.Objects")
def o = java.lang.Class.forName("java.lang.Object")
def eq2 = objCls.getMethod("equals", o, o)
h.invoke(p17, eq2, java.util.List.of("a", "b").toArray())
```

```error
No implementation of Objects.equals(Object, Object)
```

## Proxy checkParameter rejects null for primitive int

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(null, 1.0, java.io.File("."), "s"))
```

```error
Type error : expected int, got null
```

## Proxy checkParameter rejects null for primitive double

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, null, java.io.File("."), "s"))
```

```error
Type error : expected double, got null
```

## Proxy checkParameter rejects non-conforming Babble class for File

```babble
def Foo = class () ()
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, 1.0, Foo(), "s"))
```

```error
Type error : expected java.io.File, got Foo
```

## Proxy checkParameter rejects Babble function for non-SAM File

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, 1.0, fn () -> (), "s"))
```

```error
Type error : expected java.io.File, got function
```

## OverloadSet dynamic call via Comparator rejects mismatched null and bool args

```babble
def ov = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string): string -> ( "s:" + s ) )
def cmp = dyn(ov) as java.util.Comparator
cmp.compare(null, true)
```

```error
No applicable overload for (null, bool) : candidates are fn (n : int) : string, fn (s : string) : string
```

## OverloadSet dynamic call packs unpacked single rest argument through BiFunction

```babble
def ov = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string, xs: int...): string -> ( var t = 0 ; for x in xs then ( t += x ) ; s + ":" + t ) )
def bf = java.util.function.BiFunction of (string, int, string)(ov)
assert(bf.apply("k", 7) == "k:7")
```

## OverloadSet super dispatch selects int over double dynamically

```babble
def S = interface (
    def f = overload ( fn (d: double): string -> ( "d:" + d ), fn (n: int): string -> ( "i:" + n ) )
)
def C = class () implements S (
    def f = overload ( fn (d: double): string -> ( super.f(d) ), fn (n: int): string -> ( super.f(n) ) )
    def run = fn (): string -> ( super.f(3) )
)
assert(C().run() == "i:3")
```

## OverloadSet super dispatch packs multi-arg unpacked rest arguments

```babble
def S = interface (
    def f = overload ( fn (n: int): int -> ( n ), fn (s: string, xs: int...): int -> ( var t = 0 ; for x in xs then ( t += x ) ; t ) )
)
def C = class () implements S (
    def f = overload ( fn (n: int): int -> ( super.f(n) ), fn (s: string, xs: int...): int -> ( super.f(s, ...xs) ) )
    def run = fn (): int -> ( super.f("k", 5, 6) )
)
assert(C().run() == 11)
```

## OverloadSet signature selection matches 0-arg call against optional parameter

```babble
def ovOpt = overload ( fn (n: int = 0): string -> ( "n:" + n ), fn (s: string): string -> ( "s:" + s ) )
def useOpt = fn (f: overload (fn (): string, fn (s: string): string)): string -> ( f() + "|" + f("a") )
assert(useOpt(ovOpt) == "n:0|s:a")
```

## OverloadSet signature selection matches union and nullable arguments against any

```babble
def ovQ = overload ( fn io (x: any): string -> ( "nn:" + x ), fn io (x: any?): string -> ( "q:" + x ) )
def useU = fn io (f: overload (fn io (x: int | string): string, fn io (x: any?): string)): string -> ( f(1) + "|" + f("x") + "|" + f(null) )
assert(useU(ovQ) == "nn:1|nn:x|q:null")
```

## OverloadSet signature selection handles intersection parameter descriptors

```babble
def ovI = overload (
    fn io (shared x: java.lang.CharSequence & java.lang.Comparable of string): string -> ( "cc:" + x ),
    fn io (x: any): string -> ( "a:" + x )
)
def useI = fn io (f: overload (fn io (s: string): string, fn io (x: int): string)): string -> ( f("hi") + "|" + f(3) )
assert(useI(ovI) == "cc:hi|a:3")
```

## OverloadSet signature selection ranks Java subtype hierarchies

```babble
def ovJ = overload (
    fn io (shared x: java.util.Collection of string): string -> ( "col" ),
    fn io (shared x: java.util.List of string): string -> ( "lst" ),
    fn (n: int): string -> ( "n" )
)
def useJ = fn io (f: overload (fn io (shared x: java.util.ArrayList of string): string, fn io (n: int): string)): string -> ( f((java.util.ArrayList of string)()) )
assert(useJ(ovJ) == "lst")
```
