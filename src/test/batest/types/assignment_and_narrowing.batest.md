# Casts (`as`), runtime type checks (`is`), flow narrowing (`not`/`and`/`or`, null guards),

reassignment joins, and numeric widening notes.
References : BIP008 (static typing, casts, unions, nullables), TypeChecker

```babble
;; babble preamble
def dyn = fn (v : any?) : any? -> ( v )
def I1 = interface ( def a : fn () : int )
def I2 = interface ( def b : fn () : int )
def Both = class () implements I1, I2, java.lang.Runnable (
    def a = fn () : int -> ( 1 )
    def b = fn () : int -> ( 2 )
    def run = fn () -> ( () )
)
def Only1 = class () implements I1 (
    def a = fn () : int -> ( 3 )
)
def Box = class (def n : int) ()
def takeInt = fn (n : int) : int -> ( n )
```

## Case 1

```babble
def cs : java.lang.CharSequence = java.lang.StringBuilder("abc")
def sb = cs as java.lang.StringBuilder
def ap : java.lang.Appendable = cs as java.lang.Appendable
assert(sb.toString() == "abc")
assert(ap.toString() == "abc")
assert((dyn(Only1()) as I1).a() == 3)
```

## Case 2

```babble
def obj : Both = Both()
def i1 : I1 = obj
def i2 = i1 as I2
def r = i1 as java.lang.Runnable
def back = r as I1
def nil : null = null as null
assert(i2.b() == 2)
assert(back.a() == 1)
assert(nil == null)
```

## Case 3

```babble
def u : int | string = 1
assert((u as int | string) == 1)
```

```stderr
Warning : Redundant cast : int | string
```

## Case 4

```babble
assert((1 as int) == 1)
```

```stderr
Warning : Redundant cast : int
```

## Case 5

```babble
def arr = java.util.regex.Pattern.compile(",").split("a,b")
def arrCopy = java.util.Arrays.copyOf(arr, 3)
def arrObj = arrCopy as java.lang.Object
assert(arrCopy.size() == 3)
assert(arrObj != null)
```

```stderr
Warning : Redundant cast : any
```

## Error : Type error : expected int, got double

```babble
def d = 1.5
d as int
```

```error
Type error : expected int, got double
```

## Error : Type error : expected I1, got C

```babble
def C = class () ()
def c = C()
c as I1
```

```error
Type error : expected I1, got C
```

## Error : Cannot cast to parameterized type : java.util.List of int

```babble
def cs : java.lang.CharSequence = java.lang.StringBuilder("hi")
def arr : java.util.List of int = cs as java.util.List of int
```

```error
Cannot cast to parameterized type : java.util.List of int
```

## Error : Cannot cast to parameterized type : java.util.List of int (2)

```babble
def x : any? = null
def l = x as java.util.List of int
```

```error
Cannot cast to parameterized type : java.util.List of int
```

## Passes static canCast (two Java interfaces), fails at runtime

```babble
def cs : java.lang.CharSequence = java.lang.StringBuilder("hi")
cs as java.io.Closeable
```

```error
Type error : expected java.io.Closeable, got java.lang.StringBuilder
```

## Rejected statically: final Java class does not implement Runnable

```babble
def r : java.lang.Runnable = java.lang.Runnable(fn () -> ())
r as java.lang.StringBuilder
```

```error
Type error : expected java.lang.StringBuilder, got java.lang.Runnable
```

## Passes static canCast (non-final class vs interface), fails at runtime

```babble
def mkR = fn () : java.lang.Runnable -> (
    object implements java.lang.Runnable ( def run = fn () -> ( () ) )
)
def r : java.lang.Runnable = mkR()
def n = r as java.lang.Number
```

```error
Type error : expected java.lang.Number, got object
```

## Error : Type error : expected string, got java.lang.Runnable

```babble
def f = fn (r : java.lang.Runnable) -> ( r as string )
```

```error
Type error : expected string, got java.lang.Runnable
```

## Error : Type error : expected java.lang.Thread, got string

```babble
def s = "hi"
s as java.lang.Thread
```

```error
Type error : expected java.lang.Thread, got string
```

## Error : Type error : expected java.lang.Runnable, got string

```babble
def s = "abc" as java.lang.Runnable
```

```error
Type error : expected java.lang.Runnable, got string
```

## Error : Type error : expected string, got null

```babble
def x : null = null
x as string
```

```error
Type error : expected string, got null
```

## Error : Type error : expected null, got string

```babble
def s = "hi"
s as null
```

```error
Type error : expected null, got string
```

## Error : Cannot cast to a function type

```babble
def f = fn () : int -> ( 1 )
def bad = f as (fn () : int)
```

```error
Cannot cast to a function type
```

## Error : Cannot cast to a function type (2)

```babble
def bad = fn (x : any) -> ( x as fn (a : int) : int )
```

```error
Cannot cast to a function type
```

## Error : Cannot cast to type parameter : T

```babble
def badTv of T = fn (x : any) : T -> ( x as T )
```

```error
Cannot cast to type parameter : T
```

## Case 21

```babble
def x : I1 = Both()
def r : java.lang.Runnable = Both()
assert(x is I2)
assert(x is java.lang.Runnable)
assert(r is I1)
assert(not ((null as int?) is int))
```

## Case 22

```babble
def lst : java.util.List of int = java.util.Collections.singletonList(3)
def cs : java.lang.CharSequence = "abc"
def col : java.util.Collection of string = java.util.Collections.singletonList("a")
assert(cs is string)
assert(not (lst is java.util.ArrayList))
assert(col is java.util.List)
```

## Case 23

```babble
def V1 = record (x : int) implements I1 ( def a = fn () : int -> ( this.x ) )
def a : any = Only1()
def v : any = V1(2)
def u : int | string = 5
assert((a is I1) and not (a is I2))
assert(v is V1)
assert((u is int) and not (u is string))
```

## Case 24

```babble
def sb : java.lang.CharSequence = java.lang.StringBuilder("x")
def r : java.lang.Runnable? = null
def f : any = fn (x : int) : int -> ( x )
assert((sb is java.lang.Appendable) and not (sb is java.lang.String))
assert(not (r is java.lang.Runnable))
assert(f is any)
```

## Case 25

```babble
def genTest of T = fn (x : T) : bool -> ( x is string )
def testImp = fn (s : string) : bool -> ( s is int )
def num : java.lang.Number = 42
assert(genTest("hi"))
assert(not genTest(1))
assert(not testImp("a"))
assert(num is java.lang.Comparable)
assert("hi" is java.lang.CharSequence)
```

## Case 26

```babble
def check = fn (x : I1, u : int | double | string) : string -> (
    def r = if x is I2 then ( "" + x.b() ) else ( "no2" )
    if not (u is string) then (
        r += if u is int then ( ":i" ) else ( ":d" )
    ) else (
        r += ":" + u
    )
    r
)
assert(check(Both(), 1) == "2:i")
assert(check(Only1(), 2.5) == "no2:d")
assert(check(Only1(), "s") == "no2:s")
```

## Case 27

```babble
def checkObj = fn (obj : any?) : string -> (
    def r = ""
    if obj is java.lang.Runnable then ( r += "run:" )
    if obj is Both then ( r += "both:" )
    if not (obj == null) and obj is any then ( r += "any" )
    r
)
assert(checkObj(Both()) == "run:both:any")
assert(checkObj("s") == "any")
assert(checkObj(null) == "")
```

## Case 28

```babble
def check = fn (x : int | string | null, y : int?) : string -> (
    def r1 = if null != y and y > 0 then ( y + 1 ) else ( 0 )
    def r2 = if null == y or not (x is int) then ( -1 ) else ( x + y )
    "" + r1 + ":" + r2
)
assert(check(5, 3) == "4:8")
assert(check("s", null) == "0:-1")
```

## Case 29

```babble
def check = fn (ia : I1, cs : java.lang.CharSequence) : string -> (
    def r1 = if ia is I2 then ( ia.b() ) else ( 0 )
    def r2 = if ia is java.lang.Runnable then ( 1 ) else ( 0 )
    def r3 = if cs is I1 then ( 1 ) else ( 0 )
    "" + r1 + ":" + r2 + ":" + r3
)
assert(check(Both(), java.lang.StringBuilder("z")) == "2:1:0")
assert(check(Only1(), java.lang.StringBuilder("z")) == "0:0:0")
```

## Case 30

```babble
def check = fn (a : Box?, b : Box?, c : bool?) : string -> (
    def s = if null != a then ( "" + a.n ) else ( "-" )
    s += if not (null == b) then ( "" + b.n ) else ( "-" )
    if ((a != null)) then ( s += "!" )
    if c == true then ( s += "T" )
    s
)
assert(check(Box(2), Box(3), true) == "23!T")
assert(check(null, Box(4), false) == "-4")
```

## Case 31

```babble
def check = fn (a : Box?, b : Box?) : string -> (
    def s = if (a != null and b != null) then ( "" + (a.n + b.n) ) else ( "n" )
    s += if (a == null or b == null) then ( "z" ) else ( "p" )
    s
)
assert(check(Box(2), Box(3)) == "5p")
assert(check(null, Box(4)) == "nz")
```

## Case 32

```babble
def guardNull = fn (x : int?) : int -> (
    if x == null then ( return -1 )
    x + 1
)
def guardNonNull = fn (x : int?) : int -> (
    if x != null then ( x + 2 ) else ( return -2 )
)
assert(guardNull(null) == -1 and guardNull(10) == 11)
assert(guardNonNull(null) == -2 and guardNonNull(10) == 12)
```

## Case 33

```babble
def guardElse = fn (x : int?) : int -> (
    if x != null then ( def ok = x + 3 ) else ( return -3 )
    x + 3
)
assert(guardElse(null) == -3 and guardElse(10) == 13)
```

## Case 34

```babble
def a = null ; a = 5
def b = 1 ; b = null
def c = 2 ; c = 3.5
def n1 = null ; n1 = 1 ; n1 = 2.5
def n2 = 1 ; n2 = null ; n2 = 3.5
def v : int? = null ; v = 42
def w : int | double? = 3 ; w = null ; w = 2.5
def opt : string? = "a" ; opt = null
assert(a == 5 and b == null and c == 3.5)
assert(n1 == 2.5 and n2 == 3.5)
assert(v == 42 and w == 2.5 and opt == null)
```

## Case 35

```babble
def count = 0
def func = fn (x : int) : int -> ( x + 1 )
func = fn io (x : int) : int -> ( count += 1 ; x + 2 )
def fnMut : fn (mut b : Box) : int = fn (b : Box) : int -> ( b.n )
fnMut = fn (mut b : Box) : int -> ( b.n += 1 ; b.n )
def bx = Box(10)
assert(func(1) == 3 and count == 1)
assert(fnMut(bx) == 11)
```

## Case 36

```babble
def count = 0
def pick = fn (flag : bool) -> (
    if flag then ( fn (x : int) : int -> ( x ) )
    else ( fn io (x : int) : int -> ( count += 1 ; x ) )
)
assert(pick(true)(5) == 5 and count == 0)
assert(pick(false)(5) == 5 and count == 1)
```

## Case 37

```babble
def f = fn (n : int) : int -> ( n )
f = fn (n : int) : int -> ( n + 1 )
assert(f(3) == 4)
```

## Error : Type error : expected int, got string

```babble
def x = 1
x = "bad"
```

```error
Type error : expected int, got string
```

## Error : Type error : expected int, got double (x is double because of the ass...

```babble
def x = 1
def y : int = 0
y = x
x = 1.5
```

```error
Type error : expected int, got double (x is double because of the assignment)
```

## Error : Type error : expected int, got double (x is double because of the ass... (2)

```babble
def x = 1
takeInt(x)
x = 2.5
```

```error
Type error : expected int, got double (x is double because of the assignment)
```

## Error : Type error : expected int, got double (x is double because of the ass... (3)

```babble
def Holder = class () ( def x = 1 )
def c = Holder()
def y : int = 0
y = c.x
c.x = 1.5
```

```error
Type error : expected int, got double (x is double because of the assignment)
```

## Error : Type error : expected int, got double (x is double because of the ass... (4)

```babble
def C = object ( def x = 1 )
def y : int = 0
y = C.x
C.x = 1.5
```

```error
Type error : expected int, got double (x is double because of the assignment)
```

## Error : Type error : expected int, got double (a is double because of the call)

```babble
def f = fn (a) -> (
    def y : int = 0
    y = a
    a
)
f(1)
f(1.5)
```

```error
Type error : expected int, got double (a is double because of the call)
```

## Error : Type error : expected int, got double (x is double because of the call)

```babble
def f = fn (x) -> ( x )
takeInt(f(1))
f(2.5)
```

```error
Type error : expected int, got double (x is double because of the call)
```

## Error : Type error : expected int, got double (2)

```babble
def PBox = class (def x = 1) ()
def pb = PBox(1)
def y : int = 0
y = pb.x
def pb2 = PBox(1.5)
```

```error
Type error : expected int, got double
```

## Error : Type error : expected int, got double (3)

```babble
def b = Box(1)
b.n = 2.5
```

```error
Type error : expected int, got double
```

## Error : Type error : expected int, got double (4)

```babble
def x = 1
x = 2.5
def y : int = x
```

```error
Type error : expected int, got double
```

## Error : Type error : expected int, got double (5)

```babble
def f = fn (x) -> ( x )
f(1)
f(2.5)
def y : int = f(1)
```

```error
Type error : expected int, got double
```
