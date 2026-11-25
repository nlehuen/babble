# Type annotations, runtime is/array checks, structural traversal, and type display

References : `BIP008` (typing), `BIP012` (`of` syntax), `BIP022` (intersections), `analysis.TypeAnnotations`, `analysis.TypeChecker` (`resolveIsTarget`, `isArrayTypeErasedAtRuntime`), `analysis.Type`, `analysis.TypeDisplay`.

```babble
;; babble module=arr_mod.ba
export def ArrAlias of T = type array of T
export def BoxArr of T = class (items : array of T) ()
export def notAType = 42
```

```babble
;; babble module=cyc_a.ba
def b = import("cyc_b.ba")
export def A = class (x : b.B) ()
```

```babble
;; babble module=cyc_b.ba
def a = import("cyc_a.ba")
export def B = class (x : a.A) ()
```

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def IA = interface (def x : fn () : int)
def IB = interface (def y : fn () : int)
def IC = interface (def z : fn () : int)
def Box of T = class (def v : T) ()
def ArrT of T = type array of T
def BoxT of T = type Box of T
def FrozenBox = type frozen (Box of int)
def IAB = type IA & IB
```

## Generic Babble interface extending Java array interface rejects primitive type argument

```babble
def MyArr of T = interface extends tests.JavaClassDemo.ArrayTransformer of T ()
def fail : (MyArr of int)? = null
```

```error
Type error : type argument int cannot be a primitive in array of E
```

## Generic Babble sub-interface inheriting Babble interface array method rejects primitive argument

```babble
def BaseArr of T = interface ( def data : fn () : array of T )
def SubArr of T = interface extends BaseArr of T ()
def bad : (SubArr of int)? = null
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Imported generic class with array field rejects primitive type argument

```babble
def m = import("arr_mod.ba")
def ok : (m.BoxArr of int?)? = null
def fail : (m.BoxArr of int)? = null
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Local class alias via def, void, javax package, and raw generic alias is test

```babble
def C = class (def v : int) ()
def C2 = C
def x : C2 = C(5)
def v : void = null
def ds : javax.sql.DataSource? = null
def b : any = Box(1)
assert(x.v == 5 and v == null and ds == null and (b is BoxT))
```

## Intersection with java.lang.Object and intersection type alias

```babble
def m = object implements IA, IB, IC (
    def x = fn () : int -> ( 1 )
    def y = fn () : int -> ( 2 )
    def z = fn () : int -> ( 3 )
)
def i1 : IA & java.lang.Object = m
def i2 : IAB & IC = m
assert(i1.x() == 1 and i2.z() == 3)
```

## Type argument on type variable is rejected

```babble
def bad of T = fn (x : T of int) -> ( x )
```

```error
Wrong number of type arguments : expected 0, got 1
```

## Type argument on scalar type is rejected

```babble
def x : int of string = 1
```

```error
Wrong number of type arguments : expected 0, got 1
```

## Bare array annotation without type argument is rejected

```babble
def x : array = null
```

```error
Missing type arguments : array
```

## Array annotation with two type arguments is rejected

```babble
def x : array of (int, string) = null
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Unknown local type annotation is rejected

```babble
def x : NoSuchType = 1
```

```error
Unknown type : NoSuchType
```

## Unknown type through imported Java package is rejected

```babble
def u = import("java.util")
def x : u.NoSuchClass = null
```

```error
Unknown type : u.NoSuchClass
```

## Unknown type through fully qualified java path is rejected

```babble
def x : java.lang.NoSuchClass = null
```

```error
Unknown type : java.lang.NoSuchClass
```

## Non-type module export used as type annotation is rejected

```babble
def m = import("arr_mod.ba")
def x : m.notAType = 1
```

```error
Unknown type : m.notAType
```

## Cyclic type parameter bound is rejected

```babble
def bad of (T : T) = fn (x : T) : T -> ( x )
```

```error
Type error : cyclic bound for type parameter T
```

## Cyclic module import type reference reports missing type

```babble
def a = import("cyc_a.ba")
assert(a != null)
```

```error
Missing type : A (import cycle with cyc_a.ba)
```

## Nested overload type annotation flattens cleanly

```babble
def f : overload (overload (fn (int) : int, fn (string) : string), fn (bool) : bool) =
    overload (fn (x : int) : int -> ( x ), fn (s : string) : string -> ( s ), fn (b : bool) : bool -> ( b ))
assert(f(1) == 1 and f("a") == "a" and f(true))
```

## Non-function operand in overload type annotation is rejected

```babble
def f : overload (int, fn (string) : string) = null
```

```error
Type error : expected function type, got int
```

## Ambiguous overload type annotation in class parameter names the parameter

```babble
def C = class (cb : overload (fn (int) : int, fn (int) : string)) ()
```

```error
Ambiguous overloads of cb : cb(int) fits fn (p0 : int) : int and fn (p0 : int) : string
```

## Ambiguous overload type annotation in function type parameter names the parameter

```babble
def f : fn (cb : overload (fn (int) : int, fn (int) : string)) : int = null
```

```error
Ambiguous overloads of cb : cb(int) fits fn (p0 : int) : int and fn (p0 : int) : string
```

## Ambiguous overload type annotation in return type uses overload owner name

```babble
def f : fn () : overload (fn (int) : int, fn (int) : string) = null
```

```error
Ambiguous overloads of overload : overload(int) fits fn (p0 : int) : int and fn (p0 : int) : string
```

## Array aliases in is tests and reified nullable primitive and string arrays

```babble
def m = import("arr_mod.ba")
def a : any = array.of(1)
def aIntN : any = (array of int?).of(1, null)
def aStrN : any = (array of string?).of("a", null)
def aAnyN : any = (array of any?).of(1, null)
assert((a is ArrT of int) and (a is m.ArrAlias of int) and (aIntN is array of int?) and
    (aStrN is array of string?) and (aAnyN is array of any?))
```

## Reified nullable Java List and nested nullable string array is checks

```babble
def L = type (java.util.List of any?)?
def S = type (array of string?)?
def aListN : any = (array of L).of(list of any?(1))
def a2 : any = (array of S).of((array of string?).of("x"))
assert((aListN is array of L) and (a2 is array of S))
```

## Unchecked array warnings for erased Java generic, nested non-null, and frozen element arrays

```babble
def f = fn (x : any) : bool -> (
    def a = x is array of (java.util.List of string)?
    def b = x is array of (array of string)?
    def c = if false then ( (x as array of (frozen (Box of int))).size() ) else ( 0 )
    a or b or c > 0
)
assert(not f(1))
```

```stderr
Warning : Unchecked cast : (array of (list(string)))?
Warning : Unchecked cast : (array of (array of string))?
Warning : Unchecked cast : array of (frozen Box(int))
```

## Disallowed is void target

```babble
def f = fn (x : any) -> ( x is void )
```

```error
Type error : cannot test 'is void'
```

## Disallowed is with erased generic type alias arguments

```babble
def f = fn (x : any) -> ( x is BoxT of int )
```

```error
Cannot test erased type arguments with 'is' : use 'is BoxT'
```

## Disallowed is with frozen type

```babble
def f = fn (x : any) -> ( x is frozen IA )
```

```error
Cannot test 'frozen' with 'is'
```

## Disallowed is with type alias to frozen type

```babble
def f = fn (x : any) -> ( x is FrozenBox )
```

```error
Cannot test 'frozen' with 'is'
```

## Disallowed is with function type

```babble
def f = fn (x : any) -> ( x is (fn (int) : int) )
```

```error
Cannot test a function type with 'is'
```

## Structural type traversal across overload, collection, Java method, and array.of variants

```babble
def ov = overload (fn (x : int) : int -> ( x ), fn (s : string) : string -> ( s ))
def s1 = fn (x) -> ( 1 ) ; def s2 = fn (x) -> ( 1 ) ; def s3 = fn (x) -> ( 1 )
def s4 = fn (x) -> ( 1 ) ; def s5 = fn (x) -> ( 1 )
def r = if false then ( s4(array.of) + s5((array of int).of) ) else ( 0 )
assert(s1(ov) + s2(list of int) + s3(java.lang.Math.abs) + r == 3)
```

## Structural type traversal across class value, module, and Java class variants

```babble
def m = import("arr_mod.ba")
def s6 = fn (x) -> ( 1 ) ; def s7 = fn (x) -> ( 1 ) ; def s8 = fn (x) -> ( 1 )
assert(s6(Box) + s7(m) + s8(java.lang.String) == 3)
```

## Generic substitution maps over Frozen, Map, and Overloaded members

```babble
def Gen of (T: IA) = class (def f: frozen (Box of T), def mp: map of (string, T)) (
    def ov = overload (fn (x: T): T -> (x), fn (b: bool): bool -> (b))
)
def I = record (v: int) implements IA ( def x = fn (): int -> (this.v) )
def g = Gen of I(freeze(Box(I(1))), map of (string, I)(entry("k", I(2))))
assert(g.ov(I(3)).v == 3 and g.ov(true) and g.mp.get("k")!.v == 2 and g.f.v.v == 1)
```

## Generic substitution maps over ArrayOfFactory and JavaMethod members

```babble
def Gen of T = class (def al : java.util.ArrayList of T) (
    def mkArr = fn () -> ( (array of T).of )
    def mkRaw = fn () -> ( array.of )
    def adder = fn () -> ( this.al.add )
)
def g = Gen of string(java.util.ArrayList of string())
assert(g.adder()("y") and g.al.size() == 1)
```

## Type formatting for sortedset mismatch

```babble
def x : int = sortedset of int(1)
```

```error
Type error : expected int, got sortedset(int)
```

## Type formatting for sortedmap mismatch

```babble
def x : int = sortedmap of (string, int)("a", 1)
```

```error
Type error : expected int, got sortedmap(string, int)
```

## Type formatting for exception mismatch

```babble
def x : int = exception("e")
```

```error
Type error : expected int, got exception
```

## Type formatting for typed ArrayOfFactory mismatch

```babble
def x : int = (array of int).of
```

```error
Type error : expected int, got (array of int).of
```

## Type formatting for CoreCollection constructor mismatch

```babble
def x : int = list of int
```

```error
Type error : expected int, got list(int)
```

## Type formatting for Babble ClassValue mismatch

```babble
def x : int = Box of int
```

```error
Type error : expected int, got class Box(int)
```

## Type formatting for JavaClassValue mismatch

```babble
def x : int = java.util.ArrayList of int
```

```error
Type error : expected int, got class java.util.ArrayList(int)
```

## TypeDisplay renders typed array.of result

```babble
assert((array of int).of(1).size() == 1)
if false then ( (array of int).of ) else ( 1 )
```

## TypeDisplay renders untyped array.of result

```babble
assert(array.of(1).size() == 1)
if false then ( array.of ) else ( 1 )
```

## TypeDisplay renders bare list constructor result

```babble
assert(list(1).size() == 1)
list
```

## TypeDisplay renders typed list constructor result

```babble
assert((list of int)(1).size() == 1)
list of int
```

## TypeDisplay renders union inside intersection result

```babble
def m = object implements IA, IC (
    def x = fn () : int -> ( 1 )
    def z = fn () : int -> ( 2 )
)
def v : (IA | IB) & IC = m
assert(v.z() == 2)
v
```

## TypeDisplay renders frozen intersection result

```babble
def m = object implements IA, IB (
    def x = fn () : int -> ( 1 )
    def y = fn () : int -> ( 2 )
)
def v : frozen (IA & IB) = freeze(m)
assert(v.x() == 1)
v
```

## TypeDisplay renders nullable generic list result

```babble
def v : (list of int)? = null
assert(v == null)
v
```

## TypeDisplay renders function inside union result

```babble
def v : (fn (int) : int) | string = "a"
assert(v == "a")
v
```

## TypeDisplay renders keyword-only unique return function result

```babble
def f = fn (..., k : int = 1) : unique Box of int -> ( Box(k) )
assert(f(k: 2).v == 2)
f
```

## TypeDisplay renders nullable rest parameter function result

```babble
def f = fn (a : int?...) : int -> ( var n = 0 ; for x in a then ( n += 1 ) ; n )
assert(f(1, null) == 2)
f
```

## TypeDisplay renders union rest parameter function result

```babble
def f = fn (a : (int | string)...) : int -> ( var n = 0 ; for x in a then ( n += 1 ) ; n )
assert(f(1, "a") == 2)
f
```

## TypeDisplay renders intersection rest parameter function result

```babble
def f = fn (a : (IA & IB)...) : int -> ( var n = 0 ; for x in a then ( n += 1 ) ; n )
assert(f() == 0)
f
```

## TypeDisplay renders function rest parameter function result

```babble
def f = fn (a : (fn (int) : int)...) : int -> ( var n = 0 ; for x in a then ( n += 1 ) ; n )
assert(f() == 0)
f
```

## TypeDisplay renders frozen rest parameter function result

```babble
def f = fn (a : (frozen (Box of int))...) : int -> ( var n = 0 ; for x in a then ( n += 1 ) ; n )
assert(f() == 0)
f
```

## TypeDisplay renders single-arg simple Java class list result

```babble
def v = list of java.lang.StringBuilder()
assert(v.size() == 0)
v
```

## TypeDisplay renders nested generic list result

```babble
def v = list of (list of int)()
assert(v.size() == 0)
v
```

## TypeDisplay renders nullable scalar list result

```babble
def v = list of int?()
assert(v.size() == 0)
v
```

## TypeDisplay renders array element list result

```babble
def v = list of (array of int)()
assert(v.size() == 0)
v
```
