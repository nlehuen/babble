# SAM adapters, >16-arg Proxy fallback (FunctionHandler / FaceHandler), and JavaTypes conversions

References : BIP002 (Java interface bridges & SAM adapters), JavaTypes, Faces, JavaDefaultCall

```babble
;; babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
def ju = import("java.util")
def juf = import("java.util.function")

def findM = fn io (shared cls : java.lang.Class of any?, name : string) : java.lang.reflect.Method -> (
    for m in cls.getMethods() then (
        if m != null and m.getName() == name then ( return m )
    )
    cls.getMethods().get(0)!
)

def args17 = fn io (shared a : any, shared b : any, shared c : any, shared d : any) -> (
    def l : ju.List of any = ju.ArrayList(ju.List.of(a, b, c, d))
    l.addAll(ju.Collections.nCopies(13, 0))
    l.toArray()
)

def makeP17 = fn io () -> (
    Demo.Arity17Sam(fn (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) -> ( a + b ))
)
```

## Case 1

```babble
def sumL = ju.stream.IntStream.range(1, 4).mapToLong(fn (x : int) : int -> ( x * 2 )).sum()
def r = java.lang.Runnable(fn () -> ( 99 ))
r.run()
def c = juf.DoubleFunction(fn (d : double) : string -> ( "d=" + d ))
def pred = juf.Predicate(fn (x : java.lang.Boolean) : bool -> ( x ))
assert(sumL == 12 and c.apply(3) == "d=3.0" and pred.test(java.lang.Boolean.TRUE))
```

## Case 2

```babble
def r = java.lang.Runnable(fn () -> ( 99 ))
def inc = juf.IntUnaryOperator(fn (x : int) : int -> ( x + 1 ))
def dbl = juf.IntUnaryOperator(fn (x : int) : int -> ( x * 2 ))
def comp = inc.andThen(dbl)
assert(comp.applyAsInt(3) == 8)
assert(r.equals(r) and r.hashCode() == r.hashCode() and ju.Objects.toString(r) != "")
```

## Case 3

```babble
def CompCls = record (n : int) implements ju.Comparator of CompCls (
    def compare = fn (a : CompCls, b : CompCls) : int -> ( a.n - b.n )
)
def xs = ju.List.of(CompCls(1), CompCls(2))
def ys = ju.List.of(CompCls(1), CompCls(2))
assert(xs.equals(ys) and xs.hashCode() == ys.hashCode())
```

## Standard SAMs use LambdaMetafactory (not Proxy); >16-arg SAMs fall back to Proxy

```babble
def fInt = juf.IntUnaryOperator(fn (x : int) : int -> ( x + 1 ))
def fDbl = juf.DoubleUnaryOperator(fn (x : double) : double -> ( x + 1.0 ))
def ff = java.io.FilenameFilter(fn (d : java.io.File, n : string) : bool -> ( true ))
assert(not Demo.isProxy(fInt) and not Demo.isProxy(fDbl) and not Demo.isProxy(ff))
```

## Case 5

```babble
def p17 = makeP17()
assert(Demo.isProxy(p17) and p17.equals(p17))
assert(p17.hashCode() == p17.hashCode() and ju.Objects.toString(p17) != "")
assert(p17.tag() == 17)
```

## JavaTypes.checkParameter widens int to double through the Proxy fallback

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
def r1 = h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, 3, java.io.File("."), "a"))
assert(r1 == 4.0)
```

## Error : Type error : expected int, got string

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17("bad", 1.0, java.io.File("."), "s"))
```

```error
Type error : expected int, got string
```

## Error : Type error : expected double, got string

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, "bad", java.io.File("."), "s"))
```

```error
Type error : expected double, got string
```

## Error : Type error : expected string, got int

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, 1.0, java.io.File("."), 123))
```

```error
Type error : expected string, got int
```

## Error : Type error : expected java.io.File, got int

```babble
def p17 = makeP17()
def h = java.lang.reflect.Proxy.getInvocationHandler(p17)
h.invoke(p17, findM(p17.getClass(), "apply"), args17(1, 3.0, 123, "a"))
```

```error
Type error : expected java.io.File, got int
```

## JavaTypes.convertResult widens Byte/Short/Integer/Float/Long in SAMs returning any?

```babble
def is1 = juf.IntSupplier(fn io () : any? -> ( java.lang.Byte.valueOf("2") ))
def is2 = juf.IntSupplier(fn io () : any? -> ( java.lang.Short.valueOf("3") ))
def ls1 = juf.LongSupplier(fn io () : any? -> ( java.lang.Integer.valueOf(5) ))
assert(is1.getAsInt() == 2 and is2.getAsInt() == 3 and ls1.getAsLong() == 5)
```

## Case 12

```babble
def ds1 = juf.DoubleSupplier(fn io () : any? -> ( java.lang.Float.valueOf("4.5") ))
def ds2 = juf.DoubleSupplier(fn io () : any? -> ( java.lang.Long.valueOf("6") ))
assert(ds1.getAsDouble() == 4.5 and ds2.getAsDouble() == 6.0)
```

## Error : Type error : IntSupplier.getAsInt must return int, got Character

```babble
def badChar = juf.IntSupplier(fn () : any? -> ( 'A' ))
badChar.getAsInt()
```

```error
Type error : IntSupplier.getAsInt must return int, got Character
```

## Case 14

```babble
def dyn = fn (v : any?) : any? -> ( v )
def cmp : ju.Comparator of any? = fn (a, b) -> ( 0 )
def func = dyn(fn (a : int) : int -> ( a + 1 )) as juf.IntUnaryOperator
assert(cmp.compare("a", "b") == 0 and func.applyAsInt(4) == 5)
```

## FaceHandler default method invocation via Spliterator + StreamSupport

```babble
def Sp = class () implements ju.Spliterator of int (
  var d = false
  def tryAdvance = fn io mut (shared a : juf.Consumer of any?) -> ( if this.d then ( false ) else ( this.d = true ; a.accept(42) ; true ) )
  def trySplit = fn () -> ( null )
  def estimateSize = fn () -> ( 1 )
  def characteristics = fn () -> ( 0 )
)
assert(ju.stream.StreamSupport.stream(Sp(), false).toList().get(0) == 42)
```

## FunctionHandler Object methods

```babble
def f1 = juf.Predicate(fn (x : any?) : bool -> ( x == 2 ))
def f2 = juf.IntUnaryOperator(fn (x : int) : int -> ( x + 10 ))
assert(ju.Objects.equals(f1, f1) and not ju.Objects.equals(f1, f2))
assert(ju.Objects.hashCode(f1) == ju.Objects.hashCode(f1))
assert(ju.Objects.toString(f2) != "")
```

## Values.fromJava round-trip of a SAM adapter through a Java List

```babble
def f2 = juf.IntUnaryOperator(fn (x : int) : int -> ( x + 10 ))
def holder : ju.List of juf.IntUnaryOperator = ju.ArrayList()
holder.add(f2)
assert(holder.get(0).applyAsInt(5) == 15)
```

## JavaDefaultCall catch block via Iterator.remove() throwing UnsupportedOperationException

```babble
def BadIter = class () implements ju.Iterator of any? (
    def hasNext = fn () : bool -> ( false )
    def next = fn () : any? -> ( null )
)
BadIter().remove()
```

```error
java.lang.UnsupportedOperationException : remove
```
