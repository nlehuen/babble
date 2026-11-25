# Variadic rest parameters (xs: T...), keyword-only options after rest, spread arguments (...e),

primitive rest views, generic rests, subtyping, interfaces, effects, and diagnostics.
References : BIP021-I4 (BIP021-D7, D8, D19, D21), ArgumentsView, Spreads, CallSolver

```babble
;; babble preamble
def sumInts = fn (xs: int...): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def listOf of T = fn (xs: T...): list of T -> ( list(from: xs) )
def Counter = class (var mut n: int) ( def inc = fn mut () -> ( this.n += 1 ) )
```

## Case 1

```babble
def join = fn (parts: string..., sep: string = ",", pre: string = ""): string -> (
    var r = pre ; var f = true
    for p in parts then ( if not f then ( r = r + sep ) ; f = false ; r = r + p )
    r
)
assert(join() == "" and join("a") == "a" and join("a", "b", "c") == "a,b,c")
assert(join("a", "b", sep: "/", pre: "[") == "[a/b" and join("a", "b", pre: "(", sep: ":") == "(a:b")
```

## Case 2

```babble
def sumB = fn (xs: byte...): int -> ( var s = 0 ; for x in xs then ( s += int(x) ) ; s )
def sumS = fn (xs: short...): int -> ( var s = 0 ; for x in xs then ( s += int(x) ) ; s )
assert(sumB(byte(1), byte(2), byte(3)) == 6 and sumS(short(10), short(20)) == 30)
```

## Case 3

```babble
def sumL = fn (xs: long...): long -> ( var s = 0L ; for x in xs then ( s += x ) ; s )
assert(sumInts(1, 2, 3, 4) == 10 and sumL(100L, 200L) == 300L)
```

## Case 4

```babble
def sumF = fn (xs: float...): float -> ( var s = 0.0f ; for x in xs then ( s += x ) ; s )
def sumD = fn (xs: double...): double -> ( var s = 0.0 ; for x in xs then ( s += x ) ; s )
assert(sumF(1.5f, 2.5f) == 4.0f and sumD(1, 2.5, 3) == 6.5)
```

## Case 5

```babble
def cntT = fn (xs: bool...): int -> ( var c = 0 ; for x in xs then ( if x then ( c += 1 ) ) ; c )
def catC = fn (xs: char...): string -> ( var s = "" ; for x in xs then ( s = s + java.lang.String.valueOf(x) ) ; s )
assert(cntT(true, false, true) == 2 and catC('h', 'i', '!') == "hi!")
```

## Empty primitive rest views exercise 0-element ArgumentsView factories

```babble
def cB = fn (xs: byte...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
def cS = fn (xs: short...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
def cL = fn (xs: long...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
assert(cB() == 0 and cS() == 0 and sumInts() == 0 and cL() == 0)
```

## Case 7

```babble
def cF = fn (xs: float...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
def cD = fn (xs: double...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
def cZ = fn (xs: bool...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
assert(cF() == 0 and cD() == 0 and cZ() == 0)
```

## Case 8

```babble
def cC = fn (xs: char...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
def cStr = fn (xs: string...): int -> ( var n = 0 ; for x in xs then ( n += 1 ) ; n )
assert(cC() == 0 and cStr() == 0 and cStr(...array.of("a", "b")) == 2)
```

## Case 9

```babble
def a1 = array.of(1, 2)
def l1 = list(3, 4)
def s1 = set(5)
assert(sumInts(...a1) == 3 and sumInts(...l1) == 7 and sumInts(...a1, ...l1) == 10)
assert(sumInts(0, ...a1, 10, ...l1, ...s1, 20) == 45)
```

## Case 10

```babble
def cnt = fn (xs: any?...): int -> ( var c = 0 ; for x in xs then ( c += 1 ) ; c )
def ab = (array of byte).of(byte(1), byte(2))
def ash = (array of short).of(short(3), short(4))
def al = array.of(5L, 6L)
def af = array.of(1.5f, 2.5f)
def ad = array.of(1.0, 2.0)
def az = array.of(true, false, true)
def ac = array.of('a', 'b')
assert(cnt(...ab, ...ash, ...al, ...af, ...ad, ...az, ...ac) == 15)
```

## Case 11

```babble
def iterSum = fn (xs: java.lang.Iterable of int): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
assert(iterSum(...array.of(1, 2, 3)) == 6 and iterSum(...list(4, 5)) == 9)
```

## Case 12

```babble
def listSum = fn (xs: list of int): int -> ( var s = 0 ; for x in xs then ( s += x ) ; s )
def arr = array.of(1, 2, 3)
assert(listSum(...arr) == 6 and listSum(...list(4, 5)) == 9 and listSum(...set(6, 7)) == 13 and listSum(xs: ...arr) == 6)
```

## Spreading an array or set into mut list copies into a fresh ArrayList

```babble
def mutAppend = fn (mut xs: list of int, v: int): int -> ( xs.add(v) ; xs.size() )
def arr = array.of(1, 2, 3) ; def st = set(6, 7)
assert(mutAppend(...arr, 99) == 4 and arr.size() == 3)
assert(mutAppend(...st, 88) == 3 and st.size() == 2)
```

## Case 14

```babble
def arrDblSum = fn (xs: array of double): double -> ( var s = 0.0 ; for x in xs then ( s += x ) ; s )
assert(arrDblSum(...array.of(1, 2, 3)) == 6.0 and arrDblSum(...list(4, 5)) == 9.0)
```

## Case 15

```babble
def keepL = fn (shared xs: list of int): list of int -> ( xs )
def keepA = fn (shared xs: array of int): array of int -> ( xs )
def readA = fn (xs: array of int): int -> ( xs.size() )
def l = list(1, 2) ; def a = array.of(3, 4, 5)
assert(keepL(...l).size() == 2 and keepA(...a).size() == 3 and readA(...a) == 3)
```

## Case 16

```babble
def keepL = fn (shared xs: list of int): list of int -> ( xs )
def keepA = fn (shared xs: array of int): array of int -> ( xs )
def readL = fn (xs: list of int): int -> ( xs.size() )
def readA = fn (xs: array of int): int -> ( xs.size() )
def fwd = fn (xs: int...): int -> ( keepL(...xs).size() + keepA(...xs).size() + readL(...xs) + readA(...xs) )
assert(fwd(6, 7) == 8)
```

## Case 17

```babble
def listed: list of int = java.util.Arrays.asList(...array.of(10, 20, 30))
assert(java.lang.String.format("%s-%s", ...list("hello", "world")) == "hello-world")
assert(java.lang.String.format("%s:%s", ...array.of("foo", "bar")) == "foo:bar")
assert(listed.size() == 3 and listed.get(1) == 20)
```

## Case 18

```babble
assert(java.util.stream.IntStream.of(...list(2, 3, 5)).sum() == 10)
assert(java.nio.file.Path.of("src", ...list("main", "java")).getNameCount() == 3)
assert(java.lang.String.valueOf(...list('o', 'k')) == "ok")
```

## Case 19

```babble
def addAll = fn (first: int, rest: int..., scale: int = 1): int -> ( (first + sumInts(...rest)) * scale )
def f1: fn (a: int): int = addAll
def f2: fn (a: int, b: int): int = addAll
def fOpt: fn (a: int, b: int = ...): int = addAll
assert(f1(5) == 5 and f2(5, 7) == 12 and fOpt(5) == 5 and fOpt(5, 8) == 13)
```

## Case 20

```babble
def addAll = fn (first: int, rest: int..., scale: int = 1): int -> ( (first + sumInts(...rest)) * scale )
def fShifted: fn (a: int, b: int, r: int..., scale: int = ...): int = addAll
assert(fShifted(1, 2, 3, 4) == 10 and fShifted(1, 2, 3, 4, scale: 10) == 100)
```

## Variadic (list of int)... assigned to fixed fn (xs: list of int) wraps the single list in a 1-element rest...

```babble
def countLists = fn (xss: (list of int)...): int -> ( var c = 0 ; for xs in xss then ( c += xs.size() ) ; c )
def oneList: fn (xs: list of int): int = countLists
assert(oneList(list(10, 20, 30)) == 3)
```

## Case 22

```babble
def xs = listOf(1, 2, ...list(3, 4))
def empty: list of string = listOf()
def sw = java.io.StringWriter()
println("items:", ...xs, to: sw)
assert(xs == list(1, 2, 3, 4) and empty.size() == 0 and sw.toString().trim() == "items:1234")
```

## Case 23

```babble
def countNulls = fn (items: string?...): int -> ( var c = 0 ; for s in items then ( if s == null then ( c += 1 ) ) ; c )
assert(countNulls("a", null, "b", null) == 2)
```

## Case 24

```babble
def bumpAll = fn (mut cs: Counter...) -> ( for c in cs then ( c.inc() ) )
def asIter = fn (xs: int...): java.lang.Iterable of int -> ( xs )
def c1 = Counter(1) ; def c2 = Counter(10)
bumpAll(c1, c2)
var s = 0 ; for x in asIter(4, 5, 6) then ( s += x )
assert(c1.n == 2 and c2.n == 11 and s == 15)
```

## Case 25

```babble
def Adder = interface ( def add: fn (a: int, b: int): int )
def VarAdder = interface ( def sum: fn (xs: int...): int )
def Calc = class () implements Adder, VarAdder (
    def add = fn (xs: int...): int -> ( sumInts(...xs) )
    def sum = fn (xs: int...): int -> ( sumInts(...xs) )
)
def a: Adder = Calc() ; def v: VarAdder = Calc()
assert(a.add(3, 4) == 7 and v.sum(1, 2, 3, 4) == 10)
```

## Error : More than one variadic parameter : b

```babble
def bad = fn (a: int..., b: int...): int -> ( 0 )
```

```error
More than one variadic parameter : b
```

## Error : Variadic parameter with a default : a

```babble
def bad = fn (a: int... = 1): int -> ( 0 )
```

```error
Variadic parameter with a default : a
```

## Error : No such parameter : xs

```babble
sumInts(xs: 1)
```

```error
No such parameter : xs
```

## Error : Spread into a parameter that takes no sequence : x

```babble
def f = fn (x: int): int -> ( x )
f(...list(1, 2))
```

```error
Spread into a parameter that takes no sequence : x
```

## Error : Spread into a parameter that takes no sequence : arg0

```babble
"hello".substring(...list(1, 2))
```

```error
Spread into a parameter that takes no sequence : arg0
```

## Error : Type error : expected iterable or array, got java.util.Iterator(int)

```babble
def it = list(1, 2).iterator()
sumInts(...it)
```

```error
Type error : expected iterable or array, got java.util.Iterator(int)
```

## Error : Type error : expected int elements, got string

```babble
sumInts(...list("a", "b"))
```

```error
Type error : expected int elements, got string
```

## Error : Type error : expected int elements, got string (2)

```babble
def f = fn (xs: list of int): int -> ( 0 )
f(...set("a"))
```

```error
Type error : expected int elements, got string
```

## Error : Type error : expected (a : int, r : int...) : int, got (a : int, b : ...

```babble
def f = fn (a: int, b: int): int -> ( a + b )
def g: fn (a: int, r: int...): int = f
```

```error
Type error : expected (a : int, r : int...) : int, got (a : int, b : int) : int
```

## Error : Missing type : cannot infer type arguments for listOf

```babble
listOf()
```

```error
Missing type : cannot infer type arguments for listOf
```

## Error : Cannot mutate parameter cs (mut method c.inc) : mark parameter as mut cs

```babble
def bad = fn (cs: Counter...) -> ( for c in cs then ( c.inc() ) )
```

```error
Cannot mutate parameter cs (mut method c.inc) : mark parameter as mut cs
```
