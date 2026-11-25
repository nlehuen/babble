# Runtime type checking, Type.toString, Types.decodeComponent, Types.JavaClassType.accepts,

ClassofFunction, BabbleInterface/BabbleClass/BabbleInstance equality/is/super defaults,
JavaTypes.checkParameter & wrapInvocationException, Values.is/hash/isBabbleCallable,
and JavaClass/JavaObject equals/hashCode.
References : BIP002, BIP008, BIP009, Types, Values, ClassofFunction, JavaTypes

```babble
;; babble preamble
def dyn = fn (v: any): any -> ( v )
```

## Error : Type error : expected int, got array of string

```babble
def e1: int = java.util.regex.Pattern.compile(",").split("a,b")
```

```error
Type error : expected int, got array of string
```

## Error : Type error : expected int, got package java.util

```babble
def jp = import("java.util")
def e4: int = jp
```

```error
Type error : expected int, got package java.util
```

## Error : Type error : expected int, got class java.lang.String

```babble
def e5: int = java.lang.String
```

```error
Type error : expected int, got class java.lang.String
```

## Case 4

```babble
def Pair of (A, B) = class (def a: A, def b: B) ()
def optPair: Pair of (int, double)? = Pair(1, 2.5)
def optNull: Pair of (int, double)? = null
def Dup = class (def k: int = 9) ()
def runIt = java.lang.Runnable(Dup)
runIt.run()
assert(optPair?.b == 2.5)
assert(optNull == null)
```

## Case 5

```babble
def e = java.util.AbstractMap.SimpleEntry("a", 1)
def p1 = java.awt.Point(1, 2)
def p2 = java.awt.Point(1, 2)
assert(e.getKey() == "a")
assert(e.getValue() == 1)
assert(java.util.Objects.hashCode(java.lang.String) == java.util.Objects.hashCode(java.lang.String))
assert(java.util.Objects.hashCode(p1) == java.util.Objects.hashCode(p2))
```

## Case 6

```babble
def IList = interface extends java.util.Collection of any ()
def Dummy = class () ()
def d: any = Dummy()
assert(not (d is IList))
assert(java.lang.String == java.lang.String)
```

## Error : java.lang.NullPointerException

```babble
def tm: java.util.TreeMap of (java.lang.Object, int) = java.util.TreeMap()
def r = tm.get(null)
```

```error
java.lang.NullPointerException
```

## Case 8

```babble
def c = javax.swing.SwingConstants.CENTER
def s = java.lang.String.valueOf(true)
assert("" + int == "int")
assert("" + double == "double")
assert(STDOUT != null)
assert(s == "true")
assert(c == 0)
assert("" + classof("hi") == "java.lang.String")
```

## Error : classof : a package has no class

```babble
def u = import("java.util")
classof(u)
```

```error
classof : a package has no class
```

## Error : classof : a class has no class

```babble
classof(java.lang.String)
```

```error
classof : a class has no class
```

## Case 11

```babble
def fnOpt: (fn (a: int, b: int): int)? = fn (a: int, b: int): int -> ( a + b )
def optVal: (fn (a: int, b: int): int)? = null
def f = fnOpt
def rAny: any = fn () -> ( () )
def r: java.lang.Runnable = rAny as java.lang.Runnable
r.run()
assert(f != null)
assert(optVal == null)
```

## Case 12

```babble
def IP = interface ( def p = fn (): int -> ( 42 ) )
def IC = interface extends IP, java.lang.Runnable ( def c: fn (): int )
def IO = interface ( def o: fn (): int )
def Impl = class () implements IC (
    def run = fn () -> ( () )
    def c = fn (): int -> ( super.p() )
)
def a: any = Impl()
assert(a is IC and a is IP and a is java.lang.Runnable and not (a is IO))
assert(Impl().c() == 42)
```

## Case 13

```babble
def Impl = class (private def secret: int = 7, def pub: int = 3) ()
def Diff = class (def pub: int = 3) ()
def Val = record (x: int) ()
def inst = Impl()
def a: any = inst
def d: any = Diff()
assert(a == inst)
assert(not (a == d))
assert("" + Val(3) == "Val(x: 3)")
```

## Case 14

```babble
def MyCmp = class (def sign: int) implements java.util.Comparator of java.awt.Point (
    def compare = fn (a: java.awt.Point, b: java.awt.Point): int -> ( (a.x - b.x) * this.sign )
    def makeRev = fn io shared () -> ( super.reversed() )
)
def mc = MyCmp(1)
def p1 = java.awt.Point(1, 0)
def p2 = java.awt.Point(3, 0)
assert(mc.compare(p1, p2) < 0)
assert(mc.makeRev().compare(p1, p2) > 0)
```

## Case 15

```babble
def dPred = java.util.function.DoublePredicate(fn (d: double): bool -> ( d > 0.0 ))
def bPred = java.util.function.Predicate(fn (b: bool): bool -> ( b ))
def sPred = java.util.function.Predicate(fn (s: string): bool -> ( s == "ok" ))
assert(dPred.test(1.5))
assert(bPred.test(true))
assert(sPred.test("ok"))
```

## Case 16

```babble
var seen = 0
def pCons = java.util.function.Consumer(fn io (p: java.awt.Point) -> ( seen = p.x ))
def Holder = class (def x: int) ()
pCons.accept(java.awt.Point(9, 1))
assert(seen == 9)
assert(java.lang.String.valueOf(Holder) != null)
```

## Case 17

```babble
def p: any = java.awt.Point(1, 2)
def arr = java.util.regex.Pattern.compile(",").split("a,b")
def it = java.util.Collections.singletonList("a").iterator()
def i: any = 5
def d: any = 2.5
def b: any = true
def s: any = "hi"
assert(p is java.awt.Point and arr.size() == 2 and it.hasNext())
assert(i is int and not (i is double) and d is double and b is bool and s is string)
```

## Case 18

```babble
assert(java.util.Objects.hashCode(dyn(3.0)) != java.util.Objects.hashCode(dyn(3)))
assert(java.util.Objects.hashCode(dyn(java.lang.Double.MAX_VALUE)) == java.util.Objects.hashCode(dyn(java.lang.Double.MAX_VALUE)))
```

## Error : Type error : expected string, got byte

```babble
dyn(byte(1)) as string
```

```error
Type error : expected string, got byte
```

## Error : Type error : expected string, got short

```babble
dyn(short(1)) as string
```

```error
Type error : expected string, got short
```

## Error : Type error : expected string, got long

```babble
dyn(1L) as string
```

```error
Type error : expected string, got long
```

## Error : Type error : expected string, got float

```babble
dyn(1.0f) as string
```

```error
Type error : expected string, got float
```

## Error : Type error : expected string, got char

```babble
dyn('a') as string
```

```error
Type error : expected string, got char
```
