# Overload sets : static ambiguity checking, most-specific resolution, numeric widening ranks,

union/nullable/intersection splits, set types, target selection, generics, lambdas, spreads,
effects, modules, and sessions.
References : BIP021-I5 (BIP021-D9..D16, D21), OverloadSolver, OverloadSet, CallSolver

```babble
;; babble module=ov_shapes.ba
export def Circle = record (r: double) ()
export def area = overload (
    fn (c: Circle): double -> ( 3.0 * c.r * c.r ),
    fn (n: int): double -> ( double(n) )
)
```

```babble
;; babble preamble
def Shape = interface ( def area: fn (): double )
def Named = interface ( def name: fn (): string )
def Circle = record (r: double) implements Shape ( def area = fn (): double -> ( 3.0 * this.r * this.r ) )
def Square = record (s: double) implements Shape ( def area = fn (): double -> ( this.s * this.s ) )
def Label = record (n: string) implements Named ( def name = fn (): string -> ( this.n ) )
def NamedCircle = record (r: double, n: string) implements Shape, Named (
    def area = fn (): double -> ( 3.0 * this.r * this.r )
    def name = fn (): string -> ( this.n )
)
```

## Case 1

```babble
def show = overload (
    fn (n: int): string -> ( "i:" + n ),
    fn (d: double): string -> ( "d:" + d ),
    fn (s: string): string -> ( "s:" + s ),
    fn (xs: list of int): string -> ( "l:" + xs.size() )
)
assert(show(42) == "i:42" and show(3.5) == "d:3.5" and show("hi") == "s:hi" and show(list(1, 2, 3)) == "l:3")
```

## Case 2

```babble
def base = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string): string -> ( "s:" + s ) )
def ext = overload ( base, fn (b: bool): string -> ( if b then ( "yes" ) else ( "no" ) ) )
assert(ext(7) == "i:7" and ext(true) == "yes" and ext("ok") == "s:ok")
```

## Case 3

```babble
def render = overload (
    fn (n: int): string -> ( if n <= 0 then ( "0" ) else ( "n=" + recurse(n - 1) ) ),
    fn (xs: list of int): string -> ( if xs.size() == 0 then ( "[]" ) else ( "[" + render(xs.get(0)) + "]" ) )
)
assert(render(2) == "n=n=0" and render(list(1)) == "[n=0]")
```

## Case 4

```babble
def pickNum = overload ( fn (x: long): string -> ( "L:" + x ), fn (x: double): string -> ( "D:" + x ) )
assert(pickNum(byte(1)) == "L:1" and pickNum(short(2)) == "L:2" and pickNum(3) == "L:3" and pickNum(4.5f) == "D:4.5")
```

## Case 5

```babble
def pickExact = overload (
    fn (x: int): string -> ( "I:" + x ),
    fn (x: long): string -> ( "L:" + x ),
    fn (x: double): string -> ( "D:" + x )
)
assert(pickExact(byte(1)) == "I:1" and pickExact(2) == "I:2" and pickExact(3L) == "L:3" and pickExact(4.5) == "D:4.5")
```

## Static dispatch picks by the expression's static type (Shape), not the runtime class (Circle)

```babble
def tag = overload ( fn (c: Circle): string -> ( "circle" ), fn (s: Shape): string -> ( "shape" ) )
def staticShape: Shape = Circle(2.0)
assert(tag(Circle(2.0)) == "circle" and tag(staticShape) == "shape")
```

## Case 7

```babble
def area = overload ( fn (c: Circle): double -> ( c.area() ), fn (sq: Square): double -> ( sq.area() ) )
def getArea = fn (s: Circle | Square): double -> ( area(s) )
assert(getArea(Circle(2.0)) == 12.0 and getArea(Square(3.0)) == 9.0)
```

## Union split over interfaces tests the intersection meet (Shape & Named) before single interfaces

```babble
def showItem = overload (
    fn (s: Shape): string -> ( "S:" + s.area() ),
    fn (n: Named): string -> ( "N:" + n.name() ),
    fn (b: Shape & Named): string -> ( "SN:" + b.name() + ":" + b.area() )
)
def go = fn (x: Shape | Named): string -> ( showItem(x) )
assert(go(Circle(2.0)) == "S:12.0" and go(Label("hi")) == "N:hi" and go(NamedCircle(2.0, "c")) == "SN:c:12.0")
```

## Case 9

```babble
def fmtOpt = overload (
    fn (n: null): string -> ( "none" ),
    fn (n: int): string -> ( "int:" + n ),
    fn (s: string): string -> ( "str:" + s )
)
def goOpt = fn (v: int?): string -> ( fmtOpt(v) )
def goU = fn (v: int | string): string -> ( fmtOpt(v) )
assert(goOpt(null) == "none" and goOpt(9) == "int:9" and goU(5) == "int:5" and goU("ok") == "str:ok")
```

## Case 10

```babble
def fmt = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string): string -> ( "s:" + s ), fn (b: bool): string -> ( "b:" + b ) )
def fmtInt: fn (x: int): string = fmt
def use = fn (f: overload (fn (x: int): string, fn (x: string): string)): string -> ( f(1) + "/" + f("a") )
assert(fmtInt(42) == "i:42" and use(fmt) == "i:1/s:a")
```

## Case 11

```babble
def use = fn (f: overload (fn (x: int): string, fn (x: string): string)): string -> ( f(1) + "/" + f("a") )
def general = fn (x: int | string): string -> ( "gen:" + x )
assert(use(general) == "gen:1/gen:a")
```

## Case 12

```babble
def cmp = overload ( fn (a: int, b: int): int -> ( b - a ), fn (a: string, b: string): int -> ( a.compareTo(b) ) )
def nums = list(3, 1, 2) ; nums.sort(cmp)
def words = list("pear", "apple", "fig") ; words.sort(cmp)
assert(nums == list(3, 2, 1) and words == list("apple", "fig", "pear"))
```

## Case 13

```babble
def fnOv = overload ( fn (s: string): int -> ( s.length() ), fn (n: int): int -> ( n * 10 ) )
def mapper: java.util.function.Function of (string, int) = fnOv
assert(mapper.apply("hello") == 5)
```

## Case 14

```babble
def show = overload ( fn (n: int): string -> ( "int:" + n ), fn of T io (t: T): string -> ( "gen:" + t ) )
assert(show(10) == "int:10" and show("hi") == "gen:hi" and show(true) == "gen:true")
```

## Case 15

```babble
def mix = overload (
    fn (a: int, b: double): string -> ( "id" ),
    fn (a: double, b: int): string -> ( "di" ),
    fn (a: int, b: int): string -> ( "ii" )
)
assert(mix(1, 2) == "ii" and mix(1, 2.0) == "id" and mix(1.0, 2) == "di")
```

## Case 16

```babble
def apply = overload (
    fn (x: int, f: fn (n: int): int): int -> ( f(x) ),
    fn (s: string, f: fn (t: string): int): int -> ( f(s) )
)
assert(apply(5, fn (n) -> ( n * 2 )) == 10 and apply("hello", fn (t) -> ( t.length() )) == 5)
```

## Case 17

```babble
def consume = overload (
    fn (xs: list of int): string -> ( "list:" + xs.size() ),
    fn (xs: array of int): string -> ( "array:" + xs.size() )
)
assert(consume(...list(1, 2)) == "list:2" and consume(...array.of(3, 4, 5)) == "array:3")
```

## Effects follow the chosen alternative : pure callers may call a pure alternative alongside io siblings

```babble
def sw = java.io.StringWriter()
def action = overload (
    fn (n: int): int -> ( n + 1 ),
    fn io (s: string): int -> ( print("io:" + s, to: sw) ; s.length() )
)
def pureCaller = fn (x: int): int -> ( action(x) )
assert(pureCaller(41) == 42 and action("hi") == 2 and sw.toString() == "io:hi")
```

## Case 19

```babble
def wide = overload (
    fn (d: double): string -> ( "d:" + d ),
    fn io (c: java.lang.CharSequence): string -> ( "cs:" + c ),
    fn (u: bool | null): string -> ( "bn:" + u )
)
assert("" + wide == "overload (fn (d : double) : string, fn io (c : java.lang.CharSequence) : string, fn (u : bool?) : string)")
```

## Case 20

```babble
def wide = overload ( fn (d: double): string -> ( "d:" + d ), fn io (c: java.lang.CharSequence): string -> ( "cs:" + c ) )
def call = fn io (g: overload (fn io (n: int): string, fn io (s: string): string)): string -> ( g(3) + "|" + g(4) + "|" + g("hi") )
assert(call(wide) == "d:3.0|d:4.0|cs:hi")
```

## Case 21

```babble
def wide = overload ( fn (u: bool | null): string -> ( "bn:" + u ), fn (n: int): string -> ( "i:" + n ) )
def call = fn io (g: overload (fn io (b: bool): string, fn io (n: null): string)): string -> ( g(true) + "|" + g(null) )
assert(call(wide) == "bn:true|bn:null")
```

## Case 22

```babble
def anyS = overload ( fn io (x: any): string -> ( "any:" + x ), fn (n: null): string -> ( "null" ) )
def callI = fn io (g: overload (fn io (n: int): string, fn io (s: string): string)): string -> ( g(3) + "|" + g("hi") )
assert(callI(anyS) == "any:3|any:hi")
```

## Case 23

```babble
def anyS = overload ( fn io (x: any): string -> ( "any:" + x ), fn (n: null): string -> ( "null" ) )
def callB = fn io (g: overload (fn io (b: bool): string, fn io (n: null): string)): string -> ( g(true) + "|" + g(null) )
assert(callB(anyS) == "any:true|null")
```

## Case 24

```babble
def ovArr = overload (
    fn (xs: array of int): string -> ( "ai:" + xs.size() ),
    fn (xs: array of double): string -> ( "ad:" + xs.size() ),
    fn (xs: array of string): string -> ( "as:" + xs.size() )
)
assert(ovArr(array.of(1, 2)) == "ai:2" and ovArr(array.of(1.0)) == "ad:1" and ovArr(array.of("a")) == "as:1")
```

## Case 25

```babble
def ovFn = overload (
    fn (f: fn (x: int): int): string -> ( "f1:" + f(5) ),
    fn (f: fn (x: int, y: int): int): string -> ( "f2:" + f(2, 3) )
)
assert(ovFn(fn (x: int): int -> ( x + 1 )) == "f1:6" and ovFn(fn (x: int, y: int): int -> ( x + y )) == "f2:5")
```

## Case 26

```babble
def ovLists = overload ( fn (xs: list of int): string -> ( "li:" + xs.size() ), fn (xs: list of string): string -> ( "ls:" + xs.size() ) )
assert(ovLists(list(1, 2)) == "li:2" and ovLists(list("a")) == "ls:1")
```

## Case 27

```babble
def ovFrozen = overload ( fn (xs: frozen list of int): string -> ( "fl:" + xs.size() ), fn (n: int): string -> ( "i:" + n ) )
assert(ovFrozen(freeze(list(1, 2, 3))) == "fl:3" and ovFrozen(9) == "i:9")
```

## Case 28

```babble
def ovEntry = overload (
    fn (e: entry of (int, string)): string -> ( "ei:" + e.getKey() ),
    fn (e: entry of (string, int)): string -> ( "es:" + e.getKey() )
)
assert(ovEntry(entry(7, "k")) == "ei:7" and ovEntry(entry("k", 7)) == "es:k")
```

## Case 29

```babble
def ovJava = overload ( fn io (shared c: java.lang.CharSequence): string -> ( "cs:" + c.length() ), fn (s: string): string -> ( "s:" + s ) )
def ovRest = overload ( fn (h: int, xs: int...): string -> ( "ri" ), fn (h: string, xs: string...): string -> ( "rs" ) )
assert(ovJava(java.lang.StringBuilder("abc")) == "cs:3" and ovJava("ok") == "s:ok" and ovRest(1, 2) == "ri" and ovRest("a", "b") == "rs")
```

## Case 30

```babble
def ovKw = overload (
    fn (a: int, ..., tag: string = "x"): string -> ( "k1:" + a + ":" + tag ),
    fn (a: string, ..., tag: string = "y"): string -> ( "k2:" + a + ":" + tag )
)
assert(ovKw(1) == "k1:1:x" and ovKw(1, tag: "z") == "k1:1:z" and ovKw("a", tag: "w") == "k2:a:w")
```

## Case 31

```babble
def ovBound = overload (
    fn (a: int, b: int): string -> ( "bi:" + (a + b) ),
    fn of (T: java.lang.Comparable of T) (a: T, b: T): string -> ( "bc:" + a.compareTo(b) )
)
assert(ovBound(2, 3) == "bi:5" and ovBound("a", "b") == "bc:-1")
```

## Case 32

```babble
def num = overload ( fn (n: int): string -> ( "i:" + n ), fn (d: double): string -> ( "d:" + d ), fn io (x: any): string -> ( "a:" + x ) )
def go = fn io (g: overload (fn io (b: byte): string, fn io (f: float): string, fn io (s: string): string)): string -> ( g(byte(1)) + "|" + g(1.5f) + "|" + g("ok") )
assert(go(num) == "i:1|d:1.5|a:ok")
```

## Case 33

```babble
def go = fn io (g: overload (fn io (b: byte): string, fn io (f: float): string, fn io (s: string): string)): string -> ( g(byte(1)) + "|" + g(1.5f) + "|" + g("ok") )
def one = fn io (x: any?): string -> ( "one:" + x )
assert(go(one) == "one:1|one:1.5|one:ok")
```

## Case 34

```babble
def ov = overload (
    fn (a: int, b: int = 10): string -> ( "ab:" + a + "," + b ),
    fn (f: fn (x: int): int): string -> ( "fn:" + f(4) )
)
assert(ov(b: 2, a: 1) == "ab:1,2" and ov(5) == "ab:5,10" and ov(fn (x) -> ( x * 3 )) == "fn:12")
```

## Case 35

```babble
def ov = overload (
    fn (n: int): string -> ( "i:" + n ),
    fn (s: string, xs: int...): string -> ( def t = 0 ; for x in xs then ( t += x ) ; s + ":" + t )
)
assert(ov("sum", 1, 2, 3) == "sum:6" and ov("sp", ...list(4, 5)) == "sp:9")
```

## Case 36

```babble
def CompCls = class (def n: int) implements java.lang.Comparable of CompCls ( def compareTo = fn (o: CompCls): int -> ( this.n - o.n ) )
def ovComp = overload ( fn (c: CompCls): string -> ( "cls:" + c.n ), fn (c: java.lang.Comparable of CompCls): string -> ( "cmp" ) )
assert(ovComp(CompCls(7)) == "cls:7")
```

## Case 37

```babble
def AL = type java.util.ArrayList of int
def ov = overload ( fn (xs: AL): string -> ( "al:" + xs.size() ), fn (xs: list of int): string -> ( "l:" + xs.size() ), fn (xs: java.lang.Iterable of int): string -> ( "it" ) )
def al: AL = java.util.ArrayList() ; al.add(1) ; def li: list of int = al ; def it: java.lang.Iterable of int = al
assert(ov(al) == "al:1" and ov(li) == "l:1" and ov(it) == "it")
```

## Case 38

```babble
def ovArrCov = overload (
    fn (xs: array of string): string -> ( "as:" + xs.size() ),
    fn (xs: array of java.lang.CharSequence): string -> ( "acs:" + xs.size() )
)
def csArr: array of java.lang.CharSequence = (array of java.lang.CharSequence).of(java.lang.StringBuilder("a"))
assert(ovArrCov(array.of("a", "b")) == "as:2" and ovArrCov(csArr) == "acs:1")
```

## Generic alternative T accepts the whole union statically without splitting

```babble
def gen = overload ( fn (n: null): string -> ( "null" ), fn (n: int): string -> ( "int:" + n ), fn of T io (x: T): string -> ( "gen:" + x ) )
def run = fn io (x: int | string | null): string -> ( gen(x) )
assert(run(null) == "gen:null" and run(5) == "gen:5" and run("hi") == "gen:hi")
```

## Case 40

```babble
def exactSetFn: overload (fn (n: int): string, fn (s: string): string) = fn (x: int | string): string -> ( "both:" + x )
assert(exactSetFn(1) == "both:1" and exactSetFn("a") == "both:a")
```

## Case 41

```babble
def uSet = overload ( fn (x: int | string): string -> ( "u:" + x ), fn (x: Shape): string -> ( "s:" + x.area() ) )
def callU = fn (g: overload (fn (x: int | string): string, fn (x: Shape & Named): string)): string -> ( g(1) + "|" + g("s") + "|" + g(NamedCircle(2.0, "c")) )
assert(callU(uSet) == "u:1|u:s|s:12.0")
```

## Case 42

```babble
def iSet = overload ( fn (x: Shape & Named): string -> ( "sn:" + x.name() ), fn (x: int): string -> ( "i:" + x ) )
def callI = fn (g: overload (fn (x: Shape & Named): string, fn (x: int): string)): string -> ( g(NamedCircle(2.0, "c")) + "|" + g(9) )
assert(callI(iSet) == "sn:c|i:9")
```

## Case 43

```babble
def shapes = import("ov_shapes.ba")
def area = overload ( shapes.area, fn (sq: Square): double -> ( sq.s * sq.s ) )
assert(shapes.area(shapes.Circle(2.0)) == 12.0)
assert(area(shapes.Circle(2.0)) == 12.0 and area(5) == 5.0 and area(Square(4.0)) == 16.0)
```

## Case 44

```babble
;; babble session
def f = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string): string -> ( "s:" + s ) )
assert(f(1) == "i:1" and f("a") == "s:a")
```

```babble
;; babble session
def f2 = overload ( f, fn (b: bool): string -> ( "b:" + b ) )
assert(f2(2) == "i:2" and f2(true) == "b:true")
```

```babble
;; babble session
def f = overload ( f2, fn (d: double): string -> ( "d:" + d ) )
assert(f(1.5) == "d:1.5" and f(false) == "b:false")
```

## Error : Function bad violates pure guarantee (performs I/O or external mutati...

```babble
def action = overload ( fn (n: int): int -> ( n + 1 ), fn io (s: string): int -> ( println(s) ; s.length() ) )
def bad = fn (s: string): int -> ( action(s) )
```

```error
Function bad violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Ambiguous overloads of f : f(int, int) fits fn (a : int, b : double) ...

```babble
def f = overload ( fn (a: int, b: double): int -> ( 1 ), fn (a: double, b: int): int -> ( 2 ) )
```

```error
Ambiguous overloads of f : f(int, int) fits fn (a : int, b : double) : int and fn (a : double, b : int) : int
```

## Error : Ambiguous overloads of f : f(int) fits fn (a : int) : int and fn (a :...

```babble
def f = overload ( fn (a: int): int -> ( 1 ), fn (a: int, b: int = 0): int -> ( 2 ) )
```

```error
Ambiguous overloads of f : f(int) fits fn (a : int) : int and fn (a : int, b : int = ...) : int
```

## Error : Ambiguous overloads of f : f(int) fits fn (a : int) : int and fn (xs ...

```babble
def f = overload ( fn (a: int): int -> ( 1 ), fn (xs: int...): int -> ( 2 ) )
```

```error
Ambiguous overloads of f : f(int) fits fn (a : int) : int and fn (xs : int...) : int
```

## Error : Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) : str...

```babble
def f = overload ( fn (s: Shape): string -> ( "s" ), fn (n: Named): string -> ( "n" ) )
```

```error
Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) : string and fn (n : Named) : string
```

## Error : Cannot dispatch f on list of int | list of string : its alternatives ...

```babble
def f = overload ( fn (xs: list of int): string -> ( "i" ), fn (xs: list of string): string -> ( "s" ) )
def g = fn (xs: list of int | list of string): string -> ( f(xs) )
```

```error
Cannot dispatch f on list of int | list of string : its alternatives are not told apart at runtime
```

## Error : Cannot apply type arguments to an overload set : f

```babble
def f = overload ( fn (n: int): int -> ( n ), fn of T (t: T): T -> ( t ) )
f of string
```

```error
Cannot apply type arguments to an overload set : f
```

## Error : Missing type : x

```babble
def f = overload ( fn (x): int -> ( 1 ), fn (s: string): int -> ( 2 ) )
```

```error
Missing type : x
```

## Error : Missing type : x (2)

```babble
def run = overload ( fn (f: fn (x: int): int): int -> ( f(1) ), fn (f: fn (x: string): string): string -> ( f("a") ) )
run(fn (x) -> ( 1 ))
```

```error
Missing type : x
```

## Error : Ambiguous call to f for (...set of int) : candidates are (xs : list o...

```babble
def f = overload ( fn (xs: list of int): int -> ( xs.size() ), fn (xs: array of int): int -> ( xs.size() ) )
f(...set(1, 2))
```

```error
Ambiguous call to f for (...set of int) : candidates are (xs : list of int), (xs : array of int)
```

## Error : No applicable overload of area for (Triangle) : candidates are (c : C...

```babble
def Triangle = record (b: double) ()
def area = overload ( fn (c: Circle): double -> ( c.r ), fn (s: Square): double -> ( s.s ) )
area(Triangle(1.0))
```

```error
No applicable overload of area for (Triangle) : candidates are (c : Circle), (s : Square)
```

## Error : Ambiguous overloads of badKw : badKw(int, b: int) fits fn (a : int, ....

```babble
def badKw = overload (
    fn (a: int, ..., b: double = 1.0, c: int = 2): double -> ( a + b + c ),
    fn (a: double, ..., b: int = 3, c: int = 4): double -> ( a + b + c )
)
badKw(1, b: 2)
```

```error
Ambiguous overloads of badKw : badKw(int, b: int) fits fn (a : int, ..., b : double = ..., c : int = ...) : double and fn (a : double, ..., b : int = ..., c : int = ...) : double
```

## Error : Ambiguous overloads of badRest : badRest(int) fits fn (xs : int...) :...

```babble
def badRest = overload ( fn (xs: int...): int -> ( 1 ), fn (a: int, xs: double...): int -> ( 2 ) )
badRest(1)
```

```error
Ambiguous overloads of badRest : badRest(int) fits fn (xs : int...) : int and fn (a : int, xs : double...) : int
```

## Error : Ambiguous overloads of badBJ : badBJ(Named & java.lang.CharSequence) ...

```babble
def badBJ = overload ( fn (x: Named): string -> ( "n" ), fn (x: java.lang.CharSequence): string -> ( "cs" ) )
badBJ
```

```error
Ambiguous overloads of badBJ : badBJ(Named & java.lang.CharSequence) fits fn (x : Named) : string and fn (x : java.lang.CharSequence) : string
```

## Error : Ambiguous overloads of badJJ : badJJ(java.lang.CharSequence & java.la...

```babble
def badJJ = overload ( fn (x: java.lang.CharSequence): string -> ( "cs" ), fn (x: java.lang.Comparable of string): string -> ( "cmp" ) )
badJJ
```

```error
Ambiguous overloads of badJJ : badJJ(java.lang.CharSequence & java.lang.Comparable of string) fits fn (x : java.lang.CharSequence) : string and fn (x : java.lang.Comparable(string)) : string
```

## Error : Ambiguous overloads of badFnOv : badFnOv(fn (x : int, y : double) : i...

```babble
def badFnOv = overload (
    fn (f: fn (x: int, y: double): int): int -> ( f(1, 2.0) ),
    fn (f: fn (x: double, y: int): int): int -> ( f(1.0, 2) )
)
badFnOv
```

```error
Ambiguous overloads of badFnOv : badFnOv(fn (x : int, y : double) : int) fits fn (f : (x : int, y : double) : int) : int and fn (f : (x : double, y : int) : int) : int
```

## Error : Ambiguous overloads of badNull : badNull(int, int) fits fn (a : int?,...

```babble
def badNull = overload ( fn (a: int?, b: int): int -> ( b ), fn (a: int, b: int?): int -> ( a ) )
badNull
```

```error
Ambiguous overloads of badNull : badNull(int, int) fits fn (a : int?, b : int) : int and fn (a : int, b : int?) : int
```

## Error : Ambiguous overloads of badAny : badAny(int, int) fits fn (a : any, b ...

```babble
def badAny = overload ( fn (a: any, b: int): int -> ( b ), fn (a: int, b: any): int -> ( a ) )
badAny
```

```error
Ambiguous overloads of badAny : badAny(int, int) fits fn (a : any, b : int) : int and fn (a : int, b : any) : int
```

## Target selection and union split on a non-exact overload-set parameter

```babble
def wide = overload ( fn (n: int): string -> ( "i:" + n ), fn (s: string): string -> ( "s:" + s ), fn (b: bool): string -> ( "b" ) )
def use = fn (g: overload (fn (int): string, fn (string): string), x: int | string): string -> (
    def fi: fn (int): string = g
    fi(1) + "|" + g(x)
)
assert(use(wide, 2) == "i:1|i:2" and use(wide, "a") == "i:1|s:a")
```

## Union split on nullable int? dispatches int vs null across disjoint siblings

```babble
def tagOpt = overload (
    fn (n: int): string -> ( "i:" + n ),
    fn (s: string?): string -> ( if s == null then ( "none" ) else ( "s:" + s ) )
)
def go = fn (v: int?): string -> ( tagOpt(v) )
assert(go(7) == "i:7" and go(null) == "none")
```

## Union split with spread adaptation alongside a non-spread union argument

```babble
def mix = overload (
    fn (n: int, xs: list of int): string -> ( "i:" + n + ":" + xs.size() ),
    fn (s: string, xs: array of int): string -> ( "s:" + s + ":" + xs.size() )
)
def go = fn (k: int | string, src: array of int): string -> ( mix(k, ...src) )
def a = array.of(10, 20)
assert(go(3, a) == "i:3:2" and go("hi", a) == "s:hi:2")
```
