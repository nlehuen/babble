# Java runtime boundaries : unboxing, convertResult, checkParameter, JavaClass, and JavaLinker guards

References : `BIP001` (Java interop), `BIP008` (nullability & unboxing), `JavaTypes`, `JavaClass`, `JavaLinker`.

```babble preamble
def tests = import("org.babblelang.tests")
def Demo = tests.JavaClassDemo
def dyn = fn (v: any): any -> ( v )

def Cb = class () implements tests.JavaClassDemo.TypedCallback (
    def acceptInt = fn (v: int?): int -> ( if v == null then ( -1 ) else ( v + 1 ) )
    def acceptDouble = fn (v: double?): double -> ( if v == null then ( -1.0 ) else ( v + 0.5 ) )
    def acceptBool = fn (v: bool?): bool -> ( if v == null then ( false ) else ( not v ) )
    def acceptString = fn (v: string?): string? -> ( v )
    def acceptRunnable = fn (v: java.lang.Runnable): int -> ( 1 )
)
```

## Unboxing null Java Double fails with expected double error

```babble
Demo.boxedDouble(false)!
```

```error
Type error : expected double, got null
```

## Unboxing null Java Boolean fails with expected bool error

```babble
Demo.boxedBool(false)!
```

```error
Type error : expected bool, got null
```

## Unboxing null Java Byte from Map fails with expected byte error

```babble
def m: java.util.Map of (string, byte) = java.util.HashMap()
def x: byte = m.get("k")!
```

```error
Type error : expected byte, got null
```

## Unboxing null Java Short from Map fails with expected short error

```babble
def m: java.util.Map of (string, short) = java.util.HashMap()
def x: short = m.get("k")!
```

```error
Type error : expected short, got null
```

## Unboxing null Java Long from Map fails with expected long error

```babble
def m: java.util.Map of (string, long) = java.util.HashMap()
def x: long = m.get("k")!
```

```error
Type error : expected long, got null
```

## Unboxing null Java Float from Map fails with expected float error

```babble
def m: java.util.Map of (string, float) = java.util.HashMap()
def x: float = m.get("k")!
```

```error
Type error : expected float, got null
```

## Unboxing null Java Character from Map fails with expected char error

```babble
def m: java.util.Map of (string, char) = java.util.HashMap()
def x: char = m.get("k")!
```

```error
Type error : expected char, got null
```

## JavaTypes.convertResult accepts null reference return from Callable

```babble
def c = java.util.concurrent.Callable(fn (): any? -> ( null ))
assert(c.call() == null)
```

## JavaTypes.convertResult rejects null return for primitive IntSupplier

```babble
def is0 = java.util.function.IntSupplier(fn (): any? -> ( null ))
is0.getAsInt()
```

```error
Type error : IntSupplier.getAsInt must return int, got null
```

## JavaTypes.convertResult rejects wrong reference type for CovariantSupplier

```babble
def bad = Demo.CovariantSupplier(fn (): any? -> ( 42 ))
bad.get()
```

```error
Type error : CovariantSupplier.get must return String, got Integer
```

## JavaTypes.checkParameter accepts boxed nullables via TypedCallback

```babble
def c = Cb()
assert(Demo.invokeAcceptInt(c, 5) == 6 and Demo.invokeAcceptInt(c, null) == -1)
assert(Demo.invokeAcceptDouble(c, 3.0) == 3.5 and Demo.invokeAcceptDouble(c, null) == -1.0)
assert(not Demo.invokeAcceptBool(c, true) and not Demo.invokeAcceptBool(c, null))
assert(Demo.invokeAcceptString(c, null) == null and Demo.invokeAcceptString(c, "ok") == "ok")
```

## JavaTypes.checkParameter accepts Babble Runnable face and null File

```babble
def R = class () implements java.lang.Runnable ( def run = fn () -> () )
def tf = java.util.concurrent.ThreadFactory(fn io (shared r: java.lang.Runnable): java.lang.Thread -> ( java.lang.Thread(r) ))
assert(tf.newThread(R()) != null)
def ff = java.io.FilenameFilter(fn (d: java.io.File?, n: string): bool -> ( d == null ))
assert(ff.accept(null, "a") and not ff.accept(java.io.File("."), "a"))
```

## JavaClass converts any-typed function to Runnable and assigns callable to Object field

```babble
def r = java.lang.Runnable(dyn(fn () -> ()))
r.run()
def ev = java.awt.Event("src", 10L, 1, 2, 3, 4, 5, "init")
ev.arg = fn () -> ()
assert(ev.arg != null)
```

## JavaClass.call unwraps exception thrown by Java constructor

```babble
(java.util.ArrayList of int)(-1)
```

```error
java.lang.IllegalArgumentException : Illegal Capacity: -1
```

## JavaClass.call reports Babble class name via JavaTypes.describe for non-callable

```babble
def Foo = class () ()
java.lang.Runnable(dyn(Foo()))
```

```error
No applicable overload of java.lang.Runnable for (Foo) : candidates are
```

## JavaClass.call unwraps JavaObject via JavaTypes.describe for non-callable

```babble
java.lang.Runnable(dyn(java.lang.StringBuilder("y")))
```

```error
No applicable overload of java.lang.Runnable for (java.lang.StringBuilder) : candidates are
```

## JavaLinker guards exercise isMethod and isBound hit and miss paths

```babble
def call1 = fn io (f: fn io (n: int): int, x: int): int -> ( f(x) )
assert(call1(java.lang.Math.abs, -5) == 5 and call1(java.lang.Math.abs, -3) == 3 and call1(java.lang.Integer.signum, -9) == -1)
def sb = java.lang.StringBuilder("abc")
def call0 = fn io (f: fn io (): int): int -> ( f() )
assert(call0(sb.length) == 3 and call0(sb.length) == 3 and call0(sb.capacity) >= 3)
```

## JavaLinker isNull guard and BabbleClass and BabbleFunction bailout

```babble
def eq = fn io (f: fn io (a: any?, b: any?): bool, x: any?, y: any?): bool -> ( f(x, y) )
def oeq = java.util.Objects.equals
assert(eq(oeq, null, null) and eq(oeq, null, null) and not eq(oeq, "a", null))
def Box = class () ()
def xs: java.util.List of any = java.util.ArrayList()
def add = xs.add
add(Box) ; add(fn (x: int): int -> ( x ))
assert(xs.size() == 2)
```

## JavaLinker isWrappedKind guard across StringBuilder, StringBuffer, and String

```babble
def sb1 = java.lang.StringBuilder("a")
def sb2 = java.lang.StringBuilder("b")
def buf = java.lang.StringBuffer("c")
def xs: java.util.List of any = java.util.ArrayList()
def add = xs.add
def push = fn io (f: fn io (shared v: any): bool, shared v: any): bool -> ( f(v) )
push(add, sb1) ; push(add, sb2) ; push(add, buf) ; push(add, "s")
assert(xs.size() == 4)
```

## JavaLinker isWrappedOf guard across StringBuilder, StringBuffer, and String

```babble
def sb1 = java.lang.StringBuilder("a")
def sb2 = java.lang.StringBuilder("b")
def buf = java.lang.StringBuffer("c")
def toStr = fn io (shared o: java.lang.CharSequence): string -> ( def m = o.toString ; m() )
assert(toStr(sb1) == "a" and toStr(sb2) == "b" and toStr(buf) == "c" and toStr("d") == "d")
```
