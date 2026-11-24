# Generic bounds, Java wildcards, accessibleType, generic unification, and type-argument validation

References : BIP008 (generics), BIP011 (core types & variance), BIP021 (generic callables)
Target classes : TypeChecker (satisfiesJavaBound, accessibleType, unify, unifyReturnSingle,
canWidenTypeVarToDouble, genericBabbleCall)

```babble
;; babble preamble
def Box of T = class (def v : T) ()
def wrap of T = fn (x : T) : Box of T -> ( Box(x) )
def unwrap of T = fn (b : Box of T) : T -> ( b.v )
def id of T = fn (x : T) : T -> ( x )
def apply of (A, B) = fn (x : A, f : fn (a : A) : B) : B -> ( f(x) )
```

## Case 1

```babble
def xs : java.util.ArrayList of string = java.util.ArrayList()
xs.add("c") ; xs.add("a") ; xs.add("b")
assert(java.util.Collections.max(xs) == "c")
assert(java.util.List.copyOf(xs).size() == 3)
```

## Case 2

```babble
def xs : java.util.ArrayList of string = java.util.ArrayList()
xs.add("bbb") ; xs.add("a") ; xs.add("cc")
def cmp = fn io (shared a : java.lang.CharSequence, shared b : java.lang.CharSequence) : int -> (
    java.lang.StringBuilder(a).length() - java.lang.StringBuilder(b).length()
)
java.util.Collections.sort(xs, cmp)
assert(xs.get(0) == "a" and xs.get(2) == "bbb")
```

## Error : Type error : type argument Bad does not satisfy bound T of java.util....

```babble
def Bad = class () ()
def xs : java.util.List of Bad = java.util.List.of(Bad())
java.util.Collections.max(xs)
```

```error
Type error : type argument Bad does not satisfy bound T of java.util.Collections.max
```

## Error : Type error : type argument java.awt.Point does not satisfy bound T of...

```babble
def ptList : java.util.List of java.awt.Point = java.util.Collections.singletonList(java.awt.Point(1, 2))
java.util.Collections.max(ptList)
```

```error
Type error : type argument java.awt.Point does not satisfy bound T of java.util.Collections.max
```

## Case 5

```babble
def intList : java.util.ArrayList of java.lang.Integer = java.util.ArrayList()
intList.add(3)
intList.add(7)
def it : java.util.Iterator of int = java.util.Collections.emptyIterator()
def lst : java.util.List of int = java.util.List.of(1, 2)
assert(java.util.Collections.max(intList) == 7 and not it.hasNext() and lst.size() == 2)
```

## Case 6

```babble
def em : java.util.EnumMap of (java.nio.file.AccessMode, int) = java.util.EnumMap(java.util.Collections.singletonMap(java.nio.file.AccessMode.READ, 5))
assert(em.get(java.nio.file.AccessMode.READ) == 5)
```

## Case 7

```babble
def modes : java.util.EnumSet of java.nio.file.AccessMode = java.util.EnumSet.of(java.nio.file.AccessMode.READ)
assert(modes.size() == 1)
```

## Error : Type error : type argument any does not satisfy bound E of java.util....

```babble
def badObjEnum : (java.util.EnumSet of java.lang.Object)? = null
```

```error
Type error : type argument any does not satisfy bound E of java.util.EnumSet
```

## Error : Type error : type argument string does not satisfy bound E of java.ut...

```babble
def badEnum : (java.util.EnumSet of java.lang.String)? = null
```

```error
Type error : type argument string does not satisfy bound E of java.util.EnumSet
```

## Case 10

```babble
def sub = java.util.List.of(10, 20, 30).subList(1, 3)
def emptyIt : java.util.Iterator of string = java.util.Collections.emptyIterator()
def setView = java.util.Collections.singleton("hi")
assert(sub.get(0) == 20 and not emptyIt.hasNext() and setView.contains("hi"))
```

## Case 11

```babble
def parts = java.util.regex.Pattern.compile(",").split("a,b,c")
def takeArr = fn (arr : list of string) : int -> ( arr.size() )
def asList = java.util.Arrays.asList(parts)
assert(takeArr(asList) == 3 and parts.size() == 3)
```

## Case 12

```babble
def listCtor = java.util.ArrayList(java.util.List.of(4, 5))
def mapCtor = java.util.HashMap(java.util.Map.of("k", 9))
def rev : java.util.Comparator of int = java.util.Collections.reverseOrder()
def cDyn : java.lang.CharSequence = java.lang.StringBuilder("hi")
assert(listCtor.size() == 2 and mapCtor.get("k") == 9)
assert(cDyn is java.lang.Appendable and not (rev is java.lang.Comparable))
```

## Error : Type error : expected list(string), got java.util.ArrayList(int)

```babble
def badList : java.util.List of string = java.util.ArrayList(java.util.List.of(1, 2))
```

```error
Type error : expected list(string), got java.util.ArrayList(int)
```

## Case 14

```babble
def toAny of T = fn (x : T) : any? -> (
    def y : any? = x
    def z : T | int = x
    z
)
def mapped = java.util.List.of(1, 2, 3).stream().map(fn (x) -> ( x * 10 )).toList()
assert(toAny(7) == 7 and mapped.toString() == "[10, 20, 30]")
```

## Case 15

```babble
def b1 : Box of int = wrap(7)
def b2 : Box of Box of int = wrap(wrap(9))
assert(b1.v == 7 and unwrap(b2).v == 9)
```

## Case 16

```babble
def fSam : java.util.function.Function of (int, int) = id
def opt : java.util.Optional of int = java.util.Optional.of(5)
def emptyL : java.util.List of string = java.util.Collections.emptyList()
assert(java.util.Optional.of(4).map(fSam).get() == 4)
assert(opt.orElse(0) == 5 and emptyL.size() == 0)
```

## Case 17

```babble
def wrapOpt of T = fn (x : T?) : T? -> ( x )
def firstElem of T = fn (xs : java.util.List of T) : T? -> ( if xs.isEmpty() then ( null ) else ( xs.get(0) ) )
def bd : Box of double = wrap(1)
assert(bd.v == 1.0 and apply(4, fn (n) -> ( n * 2 + 1 )) == 9)
assert(wrapOpt(1) == 1 and firstElem(java.util.List.of("k")) == "k")
```

## Case 18

```babble
def first of T = fn (xs : java.util.List of T) : T? -> ( xs.get(0) )
def orDefault of T = fn (x : T?, d : T) : any? -> ( if x != null then ( x ) else ( d ) )
def xs = java.util.Collections.singletonList("hi")
assert(first(xs) == "hi" and orDefault(null, 9) == 9 and orDefault(4, 9) == 4)
```

## Case 19

```babble
def u : int | string = 7
assert(unwrap(Box(3)) == 3 and id(u) == 7 and apply(5, fn (n : int) : int -> ( n + 1 )) == 6)
```

## Case 20

```babble
def runSam of T = fn io (shared f : java.util.function.Function of (T, int), shared x : T) : int? -> ( f.apply(x) )
def opt = java.util.Optional.of(5).map(fn (x : int) -> ( x + 1 ))
assert(apply(10, fn (n) -> ( n + 1 )) == 11)
assert(runSam(fn (s : string) : int -> ( java.lang.String(s).length() ), "hello") == 5)
assert(opt.orElse(0) == 6)
```

## Case 21

```babble
def wrapGen of T = fn (func : fn () : T) : T -> ( func() )
def dWid : double = wrapGen(fn () -> ( 1 ))
assert(dWid == 1.0)
```

## Case 22

```babble
def r = java.util.Objects.requireNonNullElse(1, 2.5)
assert(r == 1.0)
```

## Error : Missing type : cannot infer type arguments for uninf

```babble
def uninf of T = fn () : T? -> ( null )
uninf()
```

```error
Missing type : cannot infer type arguments for uninf
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
def b : Box of (int, string)? = null
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Wrong number of type arguments : expected 1, got 2 (2)

```babble
def badJava : java.util.ArrayList of (int, string)? = null
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Error : Wrong number of type arguments : expected 0, got 1

```babble
def Plain = class () ()
def x : Plain of int = Plain()
```

```error
Wrong number of type arguments : expected 0, got 1
```

## Error : Wrong number of type arguments : expected 0, got 1 (2)

```babble
def bad : int of int = 1
```

```error
Wrong number of type arguments : expected 0, got 1
```
