# Runtime Values, Operators, JavaLinker fast paths, Overloads, BabbleObjects, and interfaces

References : BIP002 (object model & interfaces), BIP008 (unions / casts), Values, Operators,
JavaLinker, Overloads, BabbleInterface, BabbleInstance, BabbleType, BoundMethod, Supers

```babble preamble
def ju = import("java.util")
def dyn = fn (v : any?) : any? -> ( v )
def VBox = record (x : any?) ()
```

## JavaLinker fast-path caches via loop calls (BoundJavaMethod with String/null/StringBuilder, static JavaMethod)

```babble
def xs : ju.ArrayList of any? = ju.ArrayList()
def addFn : fn io (shared v : any?) : bool = xs.add
def i = 0
while i < 2 then ( addFn("x") ; i += 1 )
addFn(null)
addFn(java.lang.StringBuilder("y"))
assert(xs.toString() == "[x, x, null, y]")
def valOf : fn (n : int) : string = java.lang.String.valueOf
assert(valOf(1) + ":" + valOf(2) == "1:2")
```

## Error : Ambiguous call to java.lang.StringBuilder.append for (null) : candida...

```babble
def sb = java.lang.StringBuilder()
def addFn : fn io (shared v : any?) : java.lang.StringBuilder = sb.append
addFn(null)
```

```error
Ambiguous call to java.lang.StringBuilder.append for (null) : candidates are (java.lang.StringBuffer), (java.lang.CharSequence), (char[]), (java.lang.String), (java.lang.Object)
```

## Error : No applicable overload of java.lang.StringBuilder.append for (null, j...

```babble
def sb = java.lang.StringBuilder()
def appFn : fn io (shared a : any?, shared b : any?, shared c : any?) : any? = sb.append
appFn(null, "bad", 1)
```

```error
No applicable overload of java.lang.StringBuilder.append for (null, java.lang.String, java.lang.Integer) : candidates are (java.lang.StringBuffer), (java.lang.CharSequence), (java.lang.CharSequence, int, int), (char[]), (java.lang.String), (java.lang.Object), (char), (int), (long), (float), (double), (boolean), (char[], int, int)
```

## JavaLinker.fail on fast-path exception (parseInt fails on 3rd call)

```babble
def parse : fn io (s : string) : int = java.lang.Integer.parseInt
def i = 0
while i < 3 then (
    if i == 2 then ( parse("bad") ) else ( parse("12") )
    i += 1
)
```

```error
java.lang.NumberFormatException : For input string: "bad"
```

## JavaLinker.fail via non-primitive static JavaMethod in loop

```babble
def decFn : fn io (s : string) : java.lang.Integer = java.lang.Integer.decode
def i = 0
while i < 3 then (
    if i == 2 then ( decFn("bad") ) else ( decFn("10") )
    i += 1
)
```

```error
java.lang.NumberFormatException : For input string: "bad"
```

## Error : java.lang.NumberFormatException : For input string: "notAnInt"

```babble
java.lang.Integer.parseInt("notAnInt")
```

```error
java.lang.NumberFormatException : For input string: "notAnInt"
```

## JavaLinker.select fast path via polymorphic StringBuilder instances in loop

```babble
def pickSb = fn io (i : int) : java.lang.StringBuilder -> (
    if i == 0 then ( java.lang.StringBuilder("a") )
    else if i == 1 then ( java.lang.StringBuilder("bb") )
    else ( java.lang.StringBuilder("ccc") )
)
def total = 0
def i = 0
while i < 3 then ( total += pickSb(i).length() ; i += 1 )
assert(total == 6)
```

## JavaLinker.call with non-primitive Map.put bound method in loop

```babble
def tbl : ju.Map of (any?, any?) = ju.HashMap()
def putFn : fn io (shared k : any?, shared v : any?) : any? = tbl.put
def i = 0
while i < 3 then ( putFn("k" + i, "v" + i) ; i += 1 )
putFn(null, java.lang.StringBuilder("sb"))
assert(tbl.get("k1") == "v1" and string(tbl.get(null)) == "sb")
```

## JavaLinker.call with non-primitive Objects.toString static method in loop

```babble
def toStrFn : fn io (shared o : any?) : string = ju.Objects.toString
def s = ""
def i = 0
while i < 3 then ( s += toStrFn("x") ; i += 1 )
s += toStrFn(null) + ":" + toStrFn(java.lang.StringBuilder("y"))
assert(s == "xxxnull:y")
```

## JavaLinker.select wrapped StringBuilder method reference in loop

```babble
def pickSb = fn io (k : int) : java.lang.StringBuilder -> (
    if k == 0 then ( java.lang.StringBuilder("a") ) else ( java.lang.StringBuilder("bb") )
)
def total = 0
def i = 0
while i < 3 then ( total += (pickSb(i).length)() ; i += 1 )
assert(total == 5)
```

## JavaLinker.select raw BigInteger method reference in loop

```babble
def pickBig = fn io (k : int) : java.math.BigInteger -> (
    if k == 0 then ( java.math.BigInteger.valueOf(1) ) else ( java.math.BigInteger.valueOf(2) )
)
def bigSum = java.math.BigInteger.ZERO
def i = 0
while i < 3 then ( bigSum = (pickBig(i).add)(java.math.BigInteger.ONE) ; i += 1 )
assert(bigSum.toString() == "3")
```

## JavaLinker loop with mixed null, String, StringBuilder, BabbleInstance, BabbleClass across 2 passes

```babble
def K = class (def v : int) ( def toString = fn () : string -> ( "K" ) )
def xs : ju.List of any? = ju.ArrayList()
def add : fn io (shared v : any?) : bool = xs.add
def src : ju.List of any? = ju.ArrayList()
src.add("s") ; src.add(null) ; src.add(java.lang.StringBuilder("sb")) ; src.add(K(1)) ; src.add(K)
def p = 0
while p < 2 then ( for e in src then ( add(e) ) ; p += 1 )
assert(xs.size() == 10)
```

## Case 13

```babble
def K = class (def v : int) ( def toString = fn () : string -> ( "K" ) )
def toStr : fn io (shared o : any?) : string = ju.Objects.toString
def items : ju.List of any? = ju.ArrayList()
items.add("s") ; items.add(null) ; items.add(java.lang.StringBuilder("sb")) ; items.add(K(1)) ; items.add(K)
def out = ""
for x in items then ( out = out + toStr(x) + ";" )
assert(out == "s;null;sb;K;class K;")
```

## JavaLinker isWrappedOf / isWrappedKind across 2 passes & Overloads accessible interface

```babble
def arr : ju.List of any? = ju.ArrayList()
arr.add(java.lang.StringBuilder("a"))
arr.add((ju.ArrayList of any?)())
def k = 0
while k < 2 then (
    for e in arr then ( ju.Objects.equals(e, "a") ; ju.Objects.toString(e) )
    k += 1
)
def empty : ju.List of int = ju.Collections.emptyList()
assert(empty.isEmpty() and not ju.List.of(1, 2).isEmpty())
```

## Case 15

```babble
def B = class (def x : int) ()
def items : ju.List of any? = ju.ArrayList()
items.add("hi") ; items.add(B(1)) ; items.add(B) ; items.add(fn (x : int) : int -> ( x + 1 ))
def lastStr : string? = ""
def p = 0
while p < 2 then (
    for x in items then ( lastStr = ju.Objects.toString(x) )
    p += 1
)
assert(lastStr != null)
```

## Case 16

```babble
def nums = ju.ArrayList(ju.List.of(3, 1, 2))
ju.Collections.sort(nums, fn (a, b) -> ( a - b ))
assert(nums.toString() == "[1, 2, 3]")
```

## Case 17

```babble
def R = class () implements java.lang.Runnable (
    def run = fn () -> ()
    def toString = fn () -> ( "R" )
)
def xs : ju.List of any? = ju.ArrayList()
xs.add("a") ; xs.add(null) ; xs.add(java.lang.StringBuilder("z")) ; xs.add(R())
def ts : fn io (shared o : any?, d : string) : string = ju.Objects.toString
def acc = ""
for x in xs then ( acc += ts(x, "nil") + ";" )
assert(acc == "a;nil;z;R;")
```

## Values.hash via record with null, int, 2.0, Double.MAX_VALUE

```babble
def V = record (a : any?, b : double, c : double) ()
def v1 = V(null, 2.0, java.lang.Double.MAX_VALUE)
def v2 = V(2, 2.0, java.lang.Double.MAX_VALUE)
def tbl : ju.Map of (V, string) = ju.HashMap()
tbl.put(v1, "one") ; tbl.put(v2, "two")
assert(v1 == V(null, 2.0, java.lang.Double.MAX_VALUE))
assert(tbl.get(V(null, 2.0, java.lang.Double.MAX_VALUE)) == "one" and tbl.get(v2) == "two")
```

## UnionType.convert (double | string) and AnyType cast

```babble
def dOrS : double | string = dyn(3) as double | string
def dOrS2 : double | string = dyn("ok") as double | string
def an : any = dyn(5) as any
def uNum : string | double = 3
assert(dOrS == 3.0 and dOrS2 == "ok" and an == 5 and uNum == 3.0)
```

## Case 20

```babble
def RImpl = class () implements java.lang.Runnable ( def run = fn () -> () )
assert((dyn(RImpl()) as java.lang.Runnable) != null)
assert((dyn(fn () -> ()) as java.lang.Runnable) != null)
```

## Error : Type error : expected (x : int) : int, got string

```babble
def fns : ju.List of fn (x : int) : int = ju.ArrayList()
def rawList = fns as ju.List
rawList.add("notfn")
fns.get(0)
```

```error
Type error : expected (x : int) : int, got string
```

## Supers default Java interface call

```babble
def Cmp = class () implements ju.Comparator of int (
    def compare = fn (a : int, b : int) : int -> ( a - b )
    def rev = fn io shared () -> ( super(ju.Comparator).reversed() )
)
assert(Cmp().rev().compare(1, 5) > 0)
```

## Case 23

```babble
def MyComp = class () implements ju.Comparator of int (
    def compare = fn (a : int, b : int) : int -> ( a - b )
    def rev = fn io shared () : ju.Comparator of any? -> ( super(ju.Comparator).reversed() )
)
assert(MyComp().rev().compare(1, 5) > 0)
```

## BabbleInstance record value equals/hashCode vs class reference equals & custom toString

```babble
def Val = record (x : int) ()
def Val2 = record (x : int) ()
def Ref = class (def x : int) (
    private def p = 9
    def toString = fn () : string -> ( "Ref(" + this.x + ")" )
)
def v = Val(1)
assert(v != Val2(1) and v != Val(2) and v == v and Ref(1) != Ref(1))
assert(string(Ref(5)) == "Ref(5)" and ju.Objects.equals(v, Val(1)))
assert(ju.List.of(v).hashCode() == ju.List.of(Val(1)).hashCode())
```

## BabbleInterface hierarchy and Values.fromJava round-trip

```babble
def IA = interface ( def p = fn () -> ( 10 ) )
def IB = interface extends IA, java.lang.Runnable ( def q = fn () -> ( 20 ) )
def IC = interface extends IB ()
def C = class () implements IC ( def run = fn () -> () )
def O = class () ()
def x = dyn(C())
assert(x is IC and x is IB and x is IA and not (dyn(O()) is IA))
assert(x is java.lang.Runnable and not (x is java.io.Serializable))
```

## Case 26

```babble
def IA = interface ()
def IB = interface extends IA, java.lang.Runnable ()
def Impl = class () implements IB ( def run = fn () -> () )
def im = Impl()
def jlist : ju.List of java.lang.Runnable = ju.ArrayList()
jlist.add(im)
assert(same(jlist.get(0), im))
```

## BabbleInterface 2-level extendsType

```babble
def Empty = interface ()
def SubEmpty = interface extends Empty ()
def HasDefault = interface ( def ok = fn () : int -> ( 1 ) )
def SubDefault = interface extends HasDefault, Empty ()
def Impl = class () implements SubDefault ()
assert(dyn(Impl()) is Empty and dyn(Impl()) is HasDefault and dyn(Impl()) is SubDefault)
assert(not (dyn(Impl()) is SubEmpty))
```

## Case 28

```babble
def SubOp = interface extends ju.function.UnaryOperator of int ()
def LImpl = class () implements SubOp ( def apply = fn (x : int) : int -> ( x ) )
assert(dyn(LImpl()) is ju.function.Function)
```

## Case 29

```babble
def IRun = interface extends java.lang.Runnable ()
def Runner = class () implements IRun ( def run = fn () -> ( () ) )
def obj = object ( def x = 42 )
assert(dyn(Runner()) is java.lang.Runnable and dyn(Runner()) is IRun)
assert(not (dyn(Runner()) is java.io.Serializable) and obj.x == 42)
```

## BabbleInterface findDefault multi-parent & diamond

```babble
def ILeft = interface ( def leftM = fn () : int -> ( 1 ) )
def IRight = interface ( def rightM = fn () : int -> ( 2 ) )
def IChild = interface extends ILeft, IRight ()
def Impl = class () implements IChild ( def leftM = fn () : int -> ( 10 ) )
def c : IChild = Impl()
assert(c.leftM() == 10 and c.rightM() == 2)
assert(dyn(c) is ILeft and dyn(c) is IRight)
```

## Values.is on primitives, Java types, and any

```babble
assert(not (dyn(java.lang.StringBuilder()) is int))
assert(dyn(1) is int and dyn(1.5) is double and dyn("s") is string)
assert(dyn(true) is bool and dyn(java.lang.Integer.valueOf(1)) is java.lang.Number)
assert(not (dyn(null) is string) and not (dyn(null) is any) and dyn(false) is any)
```

## Values.hash and equality across boxed numeric types & huge doubles

```babble
def huge = 1.0 * 1000000000 * 1000000000 * 100
assert(VBox(java.lang.Long.valueOf(5)) == VBox(java.lang.Integer.valueOf(5)))
assert(VBox(1.0) == VBox(1) and VBox(1.5) == VBox(1.5) and VBox(huge) == VBox(huge))
```

## Case 33

```babble
def huge = 1.0 * 1000000000 * 1000000000 * 100
def xs = ju.List.of(VBox(java.lang.Long.valueOf(5)), VBox(java.lang.Short.valueOf("1")), VBox(java.lang.Byte.valueOf("2")), VBox(1.5), VBox(huge))
assert(xs.hashCode() == xs.hashCode())
```

## Operators.isIntegral (Long, Short, Byte, Float, huge Double)

```babble
def iL = dyn(java.lang.Long.valueOf("5"))
def iS = dyn(java.lang.Short.valueOf("5"))
def iB = dyn(java.lang.Byte.valueOf("5"))
def dF = dyn(java.lang.Float.valueOf("1.5"))
def huge = 1.0 * 100000000000000000000.0
assert(iL == iS and iS == iB and dF == dyn(1.5) and dyn(huge) == dyn(huge))
```

## Values.hash and equality with Float & Infinity

```babble
assert(VBox(java.lang.Float.valueOf("1.5")) == VBox(1.5))
assert(VBox(1.0 / 0.0) == VBox(1.0 / 0.0))
def xs = ju.List.of(VBox(java.lang.Float.valueOf("1.5")), VBox(1.0 / 0.0))
assert(xs.hashCode() == xs.hashCode())
```

## Case 36

```babble
def ch1 = java.lang.Character.valueOf(java.lang.StringBuilder("a").charAt(0))
def sb1 = java.lang.StringBuilder("a")
def sb2 = java.lang.StringBuilder("a")
assert(same(ch1, ch1) and same(sb1, sb1) and not same(sb1, sb2))
def fnRename : fn (x : int) : int = fn (y = 0) : int -> ( y + 1 )
assert(fnRename(x: 5) == 6)
```

## Operators union arithmetic and comparisons (routed through a parameter to prevent -O1 folding)

```babble
def pickNum = fn (flag : bool, x : int, y : double) : int | double -> (
    if flag then ( x ) else ( y )
)
def a = pickNum(true, 2, 2.5)
def b = pickNum(false, 3, 3.5)
assert(a + b == 5.5 and a - b == -1.5 and a * b == 7.0)
assert(a < b and a <= b and b > a and b >= a and a != b)
```

## Error : Not comparable : dyn("a") and dyn(1)

```babble
dyn("a") < dyn(1)
```

```error
Not comparable : dyn("a") and dyn(1)
```

## Error : Not comparable : dyn(java.awt.Point(1,2)) and dyn(java.awt.Point(3,4))

```babble
dyn(java.awt.Point(1, 2)) < dyn(java.awt.Point(3, 4))
```

```error
Not comparable : dyn(java.awt.Point(1,2)) and dyn(java.awt.Point(3,4))
```

## Error : Not comparable : a and b

```babble
def a : any = java.lang.Object()
def b : any = java.lang.Object()
a < b
```

```error
Not comparable : a and b
```

## Error : Not a number : dyn(true)

```babble
dyn(true) + dyn(1)
```

```error
Not a number : dyn(true)
```

## Error : Not a number : dyn("a")

```babble
dyn("a") - dyn("b")
```

```error
Not a number : dyn("a")
```

## BabbleType static read/write and private static member access

```babble
def C = class (def x : int) (
    private def secret = 99
    def m = fn () : int -> ( 1 )
) static (
    private def sp = 7
    def sv = 10
    def sm = fn () : int -> ( this.sp )
)
C.sv = 25
assert(C.sv == 25 and C.sm() == 7 and C(3).m() == 1)
```

## 1-arg toString does not override 0-arg toString; Java default method on BabbleInstance

```babble
def V = record (x : int) ( def toString = fn (unused : int) : string -> ( "hi" ) )
def Comp = class () implements ju.Comparator of int ( def compare = fn (a : int, b : int) : int -> ( a - b ) )
assert(string(V(4)) == "V(x: 4)" and Comp().reversed().compare(1, 5) > 0)
```

## BoundMethod.equals across same, equal receiver+fn, different receiver, different fn, non-BoundMethod

```babble
def C = class (def n : int) (
    def m1 = fn () : int -> ( this.n )
    def m2 = fn () : int -> ( this.n + 1 )
)
def a = C(1)
def b = C(2)
def am = a.m1
assert(am == am and am == a.m1 and am != b.m1 and am != a.m2 and am != "x")
```

## JavaClass, JavaObject equals/toString, array length, Java field write

```babble
def o = java.lang.Object()
def pt = java.awt.Point(1, 2)
pt.x = 7
def arr = java.lang.String("ab").toCharArray()
assert(o == o and o != java.lang.Object() and pt.x == 7 and arr.size() == 2)
assert(java.lang.String == java.lang.String and java.lang.String != java.lang.Integer)
assert(string(java.awt.Point(3, 4)) == "java.awt.Point[x=3,y=4]")
```

## Values.hash in Java HashMap with double, NaN, Point, and Object

```babble
def o = java.lang.Object()
def pt = java.awt.Point(1, 2)
def tbl : ju.Map of (any, string) = ju.HashMap()
tbl.put(1.0, "one") ; tbl.put(0.0 / 0.0, "nan") ; tbl.put(pt, "pt") ; tbl.put(o, "obj")
assert(tbl.get(1.0) == "one" and tbl.get(pt) == "pt" and tbl.get(o) == "obj")
```

## Error : Member used before definition : late

```babble
def C = class () () static (
    def readLate = fn () : int -> ( this.late )
    def r = this.readLate()
    def late : int = 5
)
C.r
```

```error
Member used before definition : late
```

## Error : Member used before definition : late (2)

```babble
def LateRef = class () (
    def readLate = fn () : string -> ( this.late )
    def r = this.readLate()
    def late : string = "hi"
)
LateRef()
```

```error
Member used before definition : late
```

## Error : Member used before definition : b

```babble
def C = class () (
    def getB = fn () : int -> ( this.b )
    def a : int = this.getB()
    def b = 1
)
C()
```

```error
Member used before definition : b
```

## Error : Name used before definition : C

```babble
def C = class () () static (
    def getB = fn () : int -> ( C.b )
    def a : int = C.getB()
    def b = 1
)
C.a
```

```error
Name used before definition : C
```

## ArraySupport.deepEquals across int, double, long, and bool arrays

```babble
assert(dyn((array of int).of(1, 2)) == dyn((array of int).of(1, 2)))
assert(dyn((array of double).of(1.5)) == dyn((array of double).of(1.5)))
assert(dyn((array of long).of(long(5))) == dyn((array of long).of(long(5))))
assert(dyn((array of bool).of(true)) == dyn((array of bool).of(true)))
```

## ArraySupport.deepEquals across byte, short, float, and char arrays

```babble
assert(dyn((array of byte).of(byte(3))) == dyn((array of byte).of(byte(3))))
assert(dyn((array of short).of(short(4))) == dyn((array of short).of(short(4))))
assert(dyn((array of float).of(float(1.5))) == dyn((array of float).of(float(1.5))))
assert(dyn((array of char).of(char("a"))) == dyn((array of char).of(char("a"))))
```

## ArraySupport.deepEquals mismatch across distinct primitive array types and null

```babble
assert(dyn((array of int).of(1)) != dyn((array of double).of(1.0)))
assert(dyn((array of long).of(long(1))) != dyn((array of byte).of(byte(1))))
assert(dyn((array of short).of(short(1))) != dyn((array of float).of(float(1.0))))
assert(dyn((array of int).of(1)) != dyn(null) and dyn(null) != dyn((array of int).of(1)))
```

## ArraySupport.deepToString on long, bool, short, and float arrays

```babble
assert(string((array of long).of(long(1), long(2))) == "[1, 2]")
assert(string((array of bool).of(true, false)) == "[true, false]")
assert(string((array of short).of(short(3))) == "[3]")
assert(string((array of float).of(float(1.5))) == "[1.5]")
```

## Values.hash and ArraySupport.deepHashCode on char arrays in a record key

```babble
def ca = (array of char).of(char("a"), char("b"))
def tbl : ju.Map of (VBox, string) = ju.HashMap()
tbl.put(VBox(ca), "ok")
assert(tbl.get(VBox((array of char).of(char("a"), char("b")))) == "ok")
```

## ArraySupport.from primitive arraycopy fast path, widenBoxedNumeric (Long), and ArraySupport.get

```babble
def src = (array of int).of(10, 20)
def copy = (array of int)(from: src)
assert(copy == src and not same(copy, src))
def longs = (array of long)(from: list.of(1, 2))
assert(longs.get(0) == long(1) and longs.get(1) == long(2))
assert((array of double).of(2.5).get(0) == 2.5)
assert((array of byte).of(byte(7)).get(0) == byte(7))
assert((array of char).of(char("z")).get(0) == char("z"))
```

## Operators union arithmetic across byte, short, long, float, and double

```babble
def op = fn (a : byte | short | int | long | float | double, b : byte | short | int | long | float | double) : double -> (
    double((a + b) - (a - b) + (a * b) / b)
)
assert(op(byte(2), short(3)) == 8.0 and op(short(2), long(4)) == 10.0)
assert(op(long(2), float(4.0)) == 10.0 and op(float(2.0), byte(4)) == 10.0)
assert(op(float(2.0), short(4)) == 10.0 and op(float(2.0), 4.0) == 10.0)
```

## CoreCollections empty sortedset, set(from: ...), and sortedset(from: ...)

```babble
def ss : sortedset of int = sortedset()
ss.add(3) ; ss.add(1)
assert(ss.size() == 2 and ss.first() == 1)
def s : set of int = set(from: list.of(2, 1, 2))
assert(s.size() == 2 and s.contains(1) and s.contains(2))
```
