# Java reflection, functional/SAM bridges, and Java interface proxy dispatch

References : BIP002 (Java interfaces), BIP008 (static typing), BIP011 (core types)
Targets : Faces, JavaTypes (checkParameters, convertResult), Values (toJava, fromJava)

```babble preamble
def Task = class (id : int) implements java.lang.Runnable (
    def run = fn () -> ( )
)
```

## Case 1

```babble
def dsum = java.util.List.of(1, 2, 3).stream().mapToDouble(fn (x : int) -> ( x + 1 )).sum()
assert(dsum == 9.0)
```

## Error : Type error : Comparator.compare must return int, got String

```babble
def xs : java.util.List of int = java.util.ArrayList()
xs.add(1)
xs.add(2)
def bad = fn (x : any?, y : any?) : any? -> ( "oops" )
java.util.Collections.sort(xs, bad)
```

```error
Type error : Comparator.compare must return int, got String
```

## Case 3

```babble
def ip : java.util.function.IntPredicate = fn (x : int) -> ( x > 0 )
def dp : java.util.function.DoublePredicate = fn (x : double) -> ( x > 1.0 )
def ff : java.io.FilenameFilter = fn (d, s) -> ( s == "pom.xml" )
def found = java.io.File(".").list(ff)!
assert(java.util.stream.IntStream.range(1, 4).allMatch(ip))
assert(java.util.stream.DoubleStream.of(1.5).allMatch(dp))
assert(found.size() >= 0)
```

## BIP022-D7 : `def ip : IntPredicate = fn ...` eagerly adapts `ip` to an `IntPredicate` instance,

```babble
;; so reflective `Method.invoke(ip, ["bad"])` reaches parameter validation rather than declaring-class check.
def ip : java.util.function.IntPredicate = fn (x : int) -> ( x > 0 )
def cls = java.lang.Class.forName("java.util.function.IntPredicate")
def im = java.util.Arrays.stream(cls.getDeclaredMethods()).filter(fn (m) -> ( m.getName() == "test" )).findFirst().get()
def badArgs : java.util.List of any = java.util.List.of of any ("bad")
im.invoke(ip, badArgs.toArray())
```

```error
java.lang.IllegalArgumentException : argument type mismatch
```

## An unadapted Babble function without a Java SAM target still fails the declaring-class check.

```babble
def rawFn = fn (x : int) -> ( x > 0 )
def cls = java.lang.Class.forName("java.util.function.IntPredicate")
def im = java.util.Arrays.stream(cls.getDeclaredMethods()).filter(fn (m) -> ( m.getName() == "test" )).findFirst().get()
def badArgs : java.util.List of any = java.util.List.of of any ("bad")
im.invoke(rawFn, badArgs.toArray())
```

```error
java.lang.IllegalArgumentException : object is not an instance of declaring class
```

## Case 6

```babble
def t1 : java.lang.Runnable = Task(1)
def t2 : java.lang.Runnable = Task(1)
def t3 : java.lang.Runnable = Task(2)
assert(java.util.Objects.equals(t1, t1))
assert(not java.util.Objects.equals(t1, t2))
assert(not java.util.Objects.equals(t1, t3))
assert(java.util.Objects.hashCode(t1) == java.util.Objects.hashCode(t1))
assert(java.util.Objects.toString(t1) != "")
```

## Case 7

```babble
def JRun = interface extends java.lang.Runnable (
    def ping = fn () : int -> ( 7 )
)
def Impl = class () implements JRun (
    def run = fn () -> ( )
)
def x = Impl()
assert(x is JRun)
assert(x is java.lang.Runnable)
assert(not (x is java.lang.AutoCloseable))
```

## Case 8

```babble
def ran = false
def R = class () implements java.lang.Runnable (
    def run = fn io () -> ( ran = true )
)
def r : java.lang.Runnable = R()
r.run()
assert(ran)
assert(java.util.Objects.equals(r, r))
assert(not java.util.Objects.equals(r, R()))
assert(java.util.Objects.hashCode(r) == java.util.Objects.hashCode(r))
assert(java.lang.String.valueOf(r) != null)
assert(java.lang.Thread(R()) != null)
```

## Case 9

```babble
def ds : java.util.function.DoubleSupplier = fn () : double -> ( 3.5 )
def bs : java.util.function.BooleanSupplier = fn () : bool -> ( true )
assert(ds.getAsDouble() == 3.5)
assert(bs.getAsBoolean())
```

## Case 10

```babble
def xs = java.util.Collections.singletonList(Task(1))
def got = xs.get(0)
def a : any = Task(2)
assert(got is java.lang.Runnable)
assert(a is java.lang.Runnable)
```

## Case 11

```babble
def P = class () implements java.util.function.IntPredicate (
    def test = fn (x : int) : bool -> ( x > 0 )
)
def p : java.util.function.IntPredicate = P()
def u : java.util.function.IntUnaryOperator = fn (x : int) : int -> ( x + 1 )
def uComp = u.compose(fn (x : int) : int -> ( x * 2 ))
assert(not p.negate().test(5))
assert(uComp.applyAsInt(3) == 7)
```

## Case 12

```babble
def func : java.util.function.IntPredicate = fn (x : int) : bool -> ( x > 0 )
assert(java.util.Objects.equals(func, func))
assert(java.util.Objects.hashCode(func) == java.util.Objects.hashCode(func))
assert(java.lang.String.valueOf(func) != null)
```
