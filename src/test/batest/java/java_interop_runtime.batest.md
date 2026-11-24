# Shared runtime & Java interop behaviors : JavaClass, JavaTypes, Operators, Values, Types, TypedCallCache

References : BIP002 (object model), BIP008 (static typing), BIP011 (core types)
Anti-folding helper : erases static type to `any` so runtime paths execute under -O1

```babble preamble
def dyn = fn (v : any) : any -> ( v )
```

## Case 1

```babble
def box : java.util.Map of (string, any?) = java.util.HashMap()
box.put("k", 42)
assert(box.get("k") == 42)
assert(java.lang.String("abc").charAt(0) == 'a')
```

## Error : Type error : expected int, got null

```babble
def v : int = java.lang.Integer.getInteger("babble.nonexistent.prop")!
assert(v == 0)
```

```error
Type error : expected int, got null
```

## Error : java.lang.NullPointerException

```babble
def bad = fn io () -> ( java.util.Objects.requireNonNull(null) )
bad()
```

```error
java.lang.NullPointerException
```

## Error : java.lang.NumberFormatException : For input string: "notanumber"

```babble
def bad = fn io () -> ( java.lang.Integer.parseInt("notanumber") )
bad()
```

```error
java.lang.NumberFormatException : For input string: "notanumber"
```

## Error : Not comparable : dyn(C()) and dyn(C())

```babble
def C = class () ()
dyn(C()) < dyn(C())
```

```error
Not comparable : dyn(C()) and dyn(C())
```

## Error : Not a number : dyn("abc")

```babble
-dyn("abc")
```

```error
Not a number : dyn("abc")
```

## Error : Not comparable : dyn(1) and dyn(2)

```babble
dyn(1) < dyn(2)
```

```error
Not comparable : dyn(1) and dyn(2)
```

## Case 8

```babble
def A = class () ()
def B = class () ()
def o1 = java.lang.StringBuilder("a")
def o2 = java.lang.StringBuilder("a")
assert(A == A)
assert(A != B)
assert(same(o1, o1))
assert(not same(o1, o2))
assert(java.lang.String == java.lang.String)
assert(java.lang.String != java.lang.Integer)
```

## Case 9

```babble
def sb = java.lang.StringBuilder("hello")
def getLen : fn io () : int = sb.length
def appStr : fn io (s : string) : java.lang.StringBuilder = sb.append
def appInt : fn io (n : int) : java.lang.StringBuilder = sb.append
appStr("!")
appInt(7)
assert(getLen() == 7 and sb.toString() == "hello!7")
```

## Case 10

```babble
def sb = java.lang.StringBuilder("a")
def appBool : fn io (b : bool) : java.lang.StringBuilder = sb.append
def appDbl : fn io (d : double) : java.lang.StringBuilder = sb.append
appBool(true)
appDbl(2.5)
assert(sb.toString() == "atrue2.5")
```

## Case 11

```babble
def C = class (def n : int) (
    def toString = fn () : string -> ( "C" + this.n )
)
def objList : java.util.List of any? = java.util.ArrayList()
objList.add(C(9))
objList.add(C)
objList.add(fn (x : int) -> ( x ))
assert(string(objList.get(0)) == "C9")
assert(objList.size() == 3)
```

## Error : Type error : expected string, got int

```babble
def parse : fn io (s : string) : int = java.lang.Integer.parseInt
def s = dyn(123)
parse(s as string)
```

```error
Type error : expected string, got int
```

## Case 13

```babble
def tk = java.io.StreamTokenizer(java.io.StringReader("1"))
assert(java.io.StreamTokenizer.TT_EOF == -1)
assert(tk.TT_WORD == -3)
```

## Case 14

```babble
assert(double(42) == 42.0)
assert(int("99") == 99)
assert(int(3.9) == 3)
```

## Error : boom

```babble
assert(false, "boom")
```

```error
boom
```

## Error : Assertion failed : false

```babble
assert(false)
```

```error
Assertion failed : false
```

## Case 17

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("1"))
st.nval = 3.5
st.sval = "abc"
assert(st.nval == 3.5)
assert(st.sval == "abc")
```

## Error : Not a scope : x

```babble
def x = dyn(42)
x.foo
```

```error
Not a scope : x
```

## Error : Member used before definition : v

```babble
def Esc = class () (
    def read = fn (e : Esc) : int -> ( e.v )
    def a = this.read(this)
    def v : int = 1
)
Esc()
```

```error
Member used before definition : v
```

## Megamorphic TypedCallCache fallback : >4 distinct functions at one typed call site

```babble
def fs = list.of(
    fn (x : int) : int -> ( x + 0 ),
    fn (x : int) : int -> ( x + 1 ),
    fn (x : int) : int -> ( x + 2 ),
    fn (x : int) : int -> ( x + 3 ),
    fn (x : int) : int -> ( x + 4 ),
    fn (x : int) : int -> ( x + 5 )
)
def sum = 0
for f in fs then ( sum += f(10) )
assert(sum == 75)
```

## Case 21

```babble
def A = class (x : int) ()
def B = class (x : int) ()
def a = A(1)
def b = B(1)
assert(a == a)
assert(a != b)
assert(a != "x")
assert(java.lang.String == java.lang.String)
assert(java.lang.String != java.lang.Integer)
assert(java.lang.String != "s")
```

## Error : Type error : expected string, got Foo

```babble
def Foo = class () ()
def s : java.lang.String = dyn(Foo()) as java.lang.String
```

```error
Type error : expected string, got Foo
```

## Error : Value members are read-only : x

```babble
def Pt = record (def x : int) ()
def pt = Pt(3)
pt.x = 4
```

```error
Value members are read-only : x
```

## Error : Cannot assign final field : java.lang.Integer.MAX_VALUE

```babble
java.lang.Integer.MAX_VALUE = 1
```

```error
Cannot assign final field : java.lang.Integer.MAX_VALUE
```

## Case 25

```babble
def a = dyn(7)
def b = dyn(2)
def d = dyn(2.5)
assert(not (a == b))
assert(a == 7)
assert(a != b)
assert("x" + a == "x7")
assert(d == 2.5)
```

## Error : No applicable overload of java.lang.Boolean.logicalAnd for (any, bool)

```babble
java.lang.Boolean.logicalAnd(dyn("bad"), true)
```

```error
No applicable overload of java.lang.Boolean.logicalAnd for (any, bool)
```

## Error : No applicable overload of java.lang.Integer.parseInt for (any)

```babble
java.lang.Integer.parseInt(dyn(123))
```

```error
No applicable overload of java.lang.Integer.parseInt for (any)
```

## Case 28

```babble
def V = record (d : double, n : any?) ()
def uniq : java.util.Set of V = java.util.HashSet()
uniq.add(V(3.5, null))
uniq.add(V(2.0, null))
assert(uniq.contains(V(3.5, null)))
assert(uniq.contains(V(2, null)))
def sb1 = java.lang.StringBuilder("a")
def sb2 = java.lang.StringBuilder("a")
assert(same(sb1, sb1))
assert(not same(sb1, sb2))
assert(not same(sb1, null))
assert(same(null, null))
```

## Case 29

```babble
def a : any = 3.5
def b : any = true
def n : any? = null
assert(a is double and b is bool and a is any and not (n is any))
```

## Case 30

```babble
def I = interface ( def id : fn () : int )
def C = class (def v : int) implements I, java.lang.Runnable (
    def id = fn () : int -> ( this.v )
    def run = fn () -> ( )
)
def c : any = C(9)
def jlTyped : java.util.List of any = java.util.ArrayList()
def jl : any = jlTyped
assert(c is I and c is C and c is java.lang.Runnable and jl is java.util.List)
```

## Error : Cannot cast to a function type

```babble
def fnAny : any = fn (x : int) : int -> ( x + 1 )
def fnCast = fnAny as fn (x : int) : int
```

```error
Cannot cast to a function type
```

## Error : Type error : expected P, got P (expected T#, got T#)

```babble
def make = fn () : any -> (
    def P = class (def x : int) ()
    P(1)
)
def P = class (def x : int) ()
def v : any = make()
def bad : P = v as P
```

```error
Type error : expected P, got P (expected T#, got T#)
```
