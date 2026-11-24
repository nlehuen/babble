# Built-in functions, runtime conversions, LogicalPaths, and runtime cast diagnostics

References : BIP005 (modules), BIP008 (as casts / Types.describe), BIP011 (nullability !),
PrintFunction, AssertFunction, ClassofFunction, ConvertFunction, LogicalPaths

```babble
;; babble module=dir/sub/leaf.ba
export def val = import("../top.ba").val
```

```babble
;; babble module=dir/top.ba
export def val = 42
```

```babble
;; babble module=mod_box_b.ba
export def Dup = class (def x : int) ()
```

```babble
;; babble module=mod_box_a.ba
export def Dup = class (def x : int) ()
```

```babble
;; babble preamble
def dyn = fn (v : any?) : any? -> ( v )
def asAny = fn (v : any) : any -> ( v )
```

## Case 1

```babble
def t1 = import("dir/sub/leaf.ba")
def t2 = import("/dir/top.ba")
def t3 = import("./dir/top.ba")
assert(t1.val == 42)
assert(t2.val == 42)
assert(t3.val == 42)
```

## Error : Cannot import ../../missing.ba : no such module

```babble
import("../../missing.ba")
```

```error
Cannot import ../../missing.ba : no such module
```

## Case 3

```babble
assert(string(import("java.lang")) == "package java.lang")
```

## Case 4

```babble
def out = java.lang.StringBuilder()
print("ok", to: out)
assert(out.toString() == "ok")
```

## Positional parameters in pr feed print's values... rest parameter; static binding resolves to: to pr's 2nd...

```babble
def pr : fn io (s : string, to : any?) : null = print
pr("hi", to: 123)
```

```stdout
hi123
```

## Case 6

```babble
assert(true, "ok")
```

## Error : Assertion failed : false

```babble
assert(false)
```

```error
Assertion failed : false
```

## Error : boom

```babble
assert(false, "boom")
```

```error
boom
```

## Case 9

```babble
assert(not same(1, java.lang.Object()))
assert(same("a", "a"))
assert(same(1.0, 1))
assert(not same(null, 1))
assert(dyn(1) is any)
assert(not (dyn(null) is any))
```

## Case 10

```babble
def obj = object ( def k = 7 )
def Box = class (def x : int) ()
assert(classof(obj) != null)
assert(classof(Box(1)) == Box)
assert(classof("hi") == classof("yo"))
assert(classof(1) != null and classof(1.5) != null and classof(true) != null)
assert(classof(null) == null)
assert(classof(java.lang.StringBuilder()) != null)
assert(classof(java.lang.Object()) == java.lang.Object)
```

## Error : classof : a class has no class

```babble
def C = class () ()
classof(C)
```

```error
classof : a class has no class
```

## Case 12

```babble
assert(int(3.9) == 3)
assert(int(asAny(3.5)) == 3)
assert(double(asAny(3)) == 3.0)
assert(double(asAny("2.5")) == 2.5)
```

## Error : Not a number : asAny(true)

```babble
double(asAny(true))
```

```error
Not a number : asAny(true)
```

## Error : Not an int : "nope"

```babble
int(asAny("nope"))
```

```error
Not an int : "nope"
```

## Error : Not a double : "nope"

```babble
double(asAny("nope"))
```

```error
Not a double : "nope"
```

## Error : Out of the int range : 1.0*1000000000*1000000000*100 = 1.0E20

```babble
int(1.0 * 1000000000 * 1000000000 * 100)
```

```error
Out of the int range : 1.0*1000000000*1000000000*100 = 1.0E20
```

## Error : Out of the int range : d = 1.0E21

```babble
def d = 1000000000000.0 * 1000000000.0
int(d)
```

```error
Out of the int range : d = 1.0E21
```

## Error : Out of the int range : d = -1.0E21

```babble
def d = -1000000000000.0 * 1000000000.0
int(d)
```

```error
Out of the int range : d = -1.0E21
```

## Error : Out of the int range : 0.0/0.0 = NaN

```babble
int(0.0 / 0.0)
```

```error
Out of the int range : 0.0/0.0 = NaN
```

## JavaTypes.checkBoxed null return rejected by non-null assertion !

```babble
java.lang.Integer.getInteger("babble.nonexistent.prop.xyz")!
```

```error
Type error : expected int, got null
```

## Error : Type error : expected any, got null

```babble
dyn(null) as any
```

```error
Type error : expected any, got null
```

## Error : Type error : expected string, got class C

```babble
def C = class () ()
dyn(C) as string
```

```error
Type error : expected string, got class C
```

## Error : Type error : expected string, got function

```babble
def func = fn () : int -> ( 1 )
dyn(func) as string
```

```error
Type error : expected string, got function
```

## Error : Type error : expected string, got double

```babble
dyn(1.5) as string
```

```error
Type error : expected string, got double
```

## Error : Type error : expected string, got bool

```babble
dyn(true) as string
```

```error
Type error : expected string, got bool
```

## Error : Type error : expected C, got string

```babble
def C = class () ()
dyn("notC") as C
```

```error
Type error : expected C, got string
```

## Error : Type error : expected java.lang.Runnable, got C

```babble
def C = class () ()
dyn(C()) as java.lang.Runnable
```

```error
Type error : expected java.lang.Runnable, got C
```

## Error : Type error : expected java.lang.Runnable, got object

```babble
def o = object ( def x = 1 )
dyn(o) as java.lang.Runnable
```

```error
Type error : expected java.lang.Runnable, got object
```

## Nominal type collision across distinct modules reports module-qualified names

```babble
def ma = import("mod_box_a.ba")
def mb = import("mod_box_b.ba")
dyn(ma.Dup(1)) as mb.Dup
```

```error
Type error : expected Dup, got Dup (expected mod_box_b.ba#Dup, got mod_box_a.ba#Dup)
```

## ConvertFunction happy paths via union parameter (prevents -O1 folding)

```babble
def num = fn (v : byte | short | int | long) : byte | short | int | long -> ( v )
assert(char(num(byte(65))) == char("A") and char(num(short(66))) == char("B"))
assert(char(num(67)) == char("C") and char(num(long(68))) == char("D"))
assert(short(num(byte(7))) == short(7) and short(num(long(8))) == short(8))
assert(not bool(if dyn(true) != null then ( "false" ) else ( "true" )))
```

## Error : Out of the byte range : "200"

```babble
def s : string = "200"
byte(s)
```

```error
Out of the byte range : "200"
```

## Error : Not a byte : "nope"

```babble
def s : string = "nope"
byte(s)
```

```error
Not a byte : "nope"
```

## Error : Out of the byte range : n = 200

```babble
def n : int = 200
byte(n)
```

```error
Out of the byte range : n = 200
```

## Error : Out of the byte range : d = 200.0

```babble
def d : double = 200.0
byte(d)
```

```error
Out of the byte range : d = 200.0
```

## Error : Out of the byte range : c = È

```babble
def c : char = char(200)
byte(c)
```

```error
Out of the byte range : c = È
```

## Error : Out of the short range : "40000"

```babble
def s : string = "40000"
short(s)
```

```error
Out of the short range : "40000"
```

## Error : Not a short : "nope"

```babble
def s : string = "nope"
short(s)
```

```error
Not a short : "nope"
```

## Error : Out of the short range : n = 40000

```babble
def n : int = 40000
short(n)
```

```error
Out of the short range : n = 40000
```

## Error : Out of the short range : d = 40000.0

```babble
def d : double = 40000.0
short(d)
```

```error
Out of the short range : d = 40000.0
```

## Error : Out of the long range : "99999999999999999999"

```babble
def s : string = "99999999999999999999"
long(s)
```

```error
Out of the long range : "99999999999999999999"
```

## Error : Not a long : "nope"

```babble
def s : string = "nope"
long(s)
```

```error
Not a long : "nope"
```

## Error : Out of the long range : d = Infinity

```babble
def d : double = 1.0 / 0.0
long(d)
```

```error
Out of the long range : d = Infinity
```

## Error : Not a float : "nope"

```babble
def s : string = "nope"
float(s)
```

```error
Not a float : "nope"
```

## Error : Not a boolean : "maybe"

```babble
def s : string = "maybe"
bool(s)
```

```error
Not a boolean : "maybe"
```

## Error : Expected single-character string : "ab"

```babble
def s : string = "ab"
char(s)
```

```error
Expected single-character string : "ab"
```

## Error : Out of the char range : n = -1

```babble
def n : int = -1
char(n)
```

```error
Out of the char range : n = -1
```

## Error : Out of the int range : n = 3000000000

```babble
def n : long = long("3000000000")
int(n)
```

```error
Out of the int range : n = 3000000000
```

## Error : Type error : expected string, got array of (array of int)

```babble
dyn((array of (array of int)).of((array of int).of(1))) as string
```

```error
Type error : expected string, got array of (array of int)
```

## Error : Type error : expected string, got array of java.awt.Point

```babble
dyn((array of java.awt.Point).of(java.awt.Point(1, 2))) as string
```

```error
Type error : expected string, got array of java.awt.Point
```
