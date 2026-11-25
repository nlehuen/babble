# Arrays with generics, intersections, modules, effects/ownership, Java bridges, for-loops, and java.base ove...

References : BIP020 (arrays & reifiable generics), BIP016 (effects/unique/frozen), BIP019 (java.base axioms), BIP022 (intersections), BIP023 (type aliases)

```babble
;; babble module=arr_mod.ba
export def Ints = type array of int
export def Grid of T = type array of (array of T)
export def ModBag of T = class (def xs: array of T) ()
export def sumInts = fn (xs: Ints): int -> (
    var acc = 0
    for x in xs then (acc += x)
    acc
)
```

```babble
;; babble preamble
def arrMod = import("arr_mod.ba")
def tests = import("org.babblelang.tests")
```

## Case 1

```babble
def R = record (tag: string, xs: frozen array of int) ()
def ints = array(from: list(1, 2, 3))
def r1 = R("k", freeze(array.of(1, 2)))
def r2 = R("k", freeze(array.of(1, 2)))
assert(r1 == r2)
assert(arrMod.sumInts(ints) == 6)
```

## Case 2

```babble
def Named = interface (def name: fn (): string)
def Sized = interface (def len: fn (): int)
def Item = class (def s: string) implements Named, Sized (
    def name = fn (): string -> (this.s)
    def len = fn (): int -> (this.s.length())
)
def both: array of (Named & Sized) = array.of(Item("abc"), Item("de"))
assert(both.get(0).name() == "abc" and both.get(1).len() == 2)
```

## Case 3

```babble
def grid: arrMod.Grid of int = array.of(array.of(1, 2), array.of(3))
assert(string(grid) == "[[1, 2], [3]]")
```

## Case 4

```babble
def buildSquares = fn (n: int): unique array of int -> (
    def out = (array of int)(n)
    var i = 0
    while i < n then (
        out.set(i, (i + 1) * (i + 1))
        i += 1
    )
    move(out)
)
def sq = freeze(buildSquares(4))
assert(string(sq) == "[1, 4, 9, 16]")
assert(sq.get(2) == 9 and sq.size() == 4)
```

## Case 5

```babble
def first of T = fn io (a: array of T): T -> (a.get(0))
def Box of T = class (def items: array of T) (
    def head = fn io shared (): T -> (this.items.get(0))
)
def parts = "ab,cd".split(",")
assert((first of string)(parts) == "ab")
assert((Box of string)(parts).head() == "ab")
```

## Case 6

```babble
assert(tests.JavaClassDemo.echoArrayWithSample("fallback", null) == "fallback")
assert(string(java.util.Arrays.asList(array.of("m", "n"))) == "[m, n]")
```

## Case 7

```babble
def Upper = class () implements tests.JavaClassDemo.ArrayTransformer of string (
    def transform = fn io (a: array of string): array of string -> (
        array.of(a.get(0).toUpperCase(), a.get(1).toUpperCase())
    )
)
def parts = "ab,cd".split(",")
assert(string(tests.JavaClassDemo.invokeArrayTransformer(Upper(), parts)) == "[AB, CD]")
```

## Case 8

```babble
def Exp = class () implements tests.JavaClassDemo.ArrayExportable of string (
    def toArray = fn io (mut dst: array of string): array of string -> (
        if dst.size() >= 2 then ( dst.set(0, "p") ; dst.set(1, "q") ; dst )
        else ( array.of("p", "q") )
    )
)
assert(string(tests.JavaClassDemo.invokeExportableStrings(Exp())) == "[p, q]")
```

## Case 9

```babble
def ExpAny = class () implements tests.JavaClassDemo.ArrayExportable of string (
    def toArray = fn io (mut dst: array of any?): array of any? -> ( dst.set(0, "p") ; dst )
)
def expJava: tests.JavaClassDemo.ArrayExportable of string = ExpAny()
assert(expJava.toArray() == 2)
```

## Error : Type error : expected array of string, got array of int?

```babble
def Upper = class () implements tests.JavaClassDemo.ArrayTransformer of string (
    def transform = fn io (a: array of string): array of string -> (a)
)
def badRaw: any = (array of int?).of(1, 2)
tests.JavaClassDemo.invokeArrayTransformerRaw(Upper(), badRaw)
```

```error
Type error : expected array of string, got array of int?
```

## Error : Type error : expected array of T, got array of char

```babble
def first of T = fn io (a: array of T): T -> (a.get(0))
first("abc".toCharArray())
```

```error
Type error : expected array of T, got array of char
```

## Error : Type error : type argument int cannot be a primitive in array of T

```babble
def first of T = fn io (a: array of T): T -> (a.get(0))
def f = first of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : Type error : type argument int cannot be a primitive in array of T (2)

```babble
def Bag of T = class (def data: array of T) ()
def B = Bag of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : Type error : type argument int cannot be a primitive in array of E

```babble
def Bad = class () implements tests.JavaClassDemo.ArrayTransformer of int (
    def transform = fn (a: array of int): array of int -> (a)
)
```

```error
Type error : type argument int cannot be a primitive in array of E
```

## Error : Type error : type argument int cannot be a primitive in array of T (3)

```babble
def xs = list(1, 2)
xs.toArray of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : Generic array creation : array of T

```babble
def make of T = fn (x: T): array of T -> (array.of(x))
```

```error
Generic array creation : array of T
```

## Error : Cannot test type parameter with 'is' : array of T

```babble
def check of T = fn (x: any): bool -> (x is array of T)
```

```error
Cannot test type parameter with 'is' : array of T
```

## Error : Type error : type argument int cannot be a primitive in array of T (4)

```babble
def B = arrMod.ModBag of int
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Error : Type error : type argument int cannot be a primitive in array of T (5)

```babble
def IArr of T = interface (def take: fn (xs: array of T): int)
def IChild of U = interface extends IArr of U ()
def Impl = class () implements IChild of int (
    def take = fn (xs: array of int): int -> (xs.size())
)
```

```error
Type error : type argument int cannot be a primitive in array of T
```

## Case 20

```babble
def sumUnion = fn (seq: list of int | array of int): int -> (
    var s = 0 ; for x in seq then (s += x) ; s
)
def sumJavaUnion = fn (seq: list of int | set of int): int -> (
    var s = 0 ; for x in seq then (s += x) ; s
)
assert(sumUnion(array.of(1, 2)) == 3 and sumUnion(list(3, 4)) == 7)
assert(sumJavaUnion(list(5, 6)) == 11)
```

## Case 21

```babble
def frArr: frozen array of int = freeze(array.of(1, 2, 3))
def frList: frozen list of int = freeze(list(4, 5))
var frSum = 0
for x in frArr then (frSum += x)
for x in frList then (frSum += x)
assert(frSum == 15)
```

## Case 22

```babble
var total = 0
def rawIt = list(10, 20).iterator()
for x in rawIt then (total += x)
def MyIter = class (var i: int = 0) (
    def hasNext = fn shared (): bool -> (this.i < 2)
    def next = fn mut (): int -> (this.i += 1 ; this.i)
)
def MySeq = class () (
    def iterator = fn (): MyIter -> (MyIter())
)
for x in MySeq() then (total += x)
assert(total == 33)
```

## Case 23

```babble
def bi = java.math.BigInteger.valueOf(12L)
def bi2 = java.math.BigInteger.valueOf(5L)
def biSum = bi.add(bi2).subtract(bi2).multiply(bi2).divide(bi2).remainder(bi2).mod(bi2).pow(2).abs().negate().negate()
def biBits = bi.min(bi2).max(bi).gcd(bi2).xor(bi2).shiftLeft(1).shiftRight(1)
assert(string(biSum) == "4" and string(biBits) == "4")
```

## Case 24

```babble
def stList = java.util.stream.Stream.of(array.of("b", "a", "b", "c")).distinct().sorted().skip(1L).limit(2L).toList()
def stArr = java.util.stream.IntStream.range(1, 4).boxed().toArray()
assert(string(stList) == "[b, c]" and stArr.size() == 3)
```

## Case 25

```babble
def opt1 = java.util.Optional.of("ok")
def opt2: java.util.Optional of string? = java.util.Optional.ofNullable(null)
def opt3: java.util.Optional of string = java.util.Optional.empty()
assert(opt1.get() == "ok" and opt2.orElse("d") == "d" and opt3.isEmpty())
```

## Case 26

```babble
def tm = (java.util.TreeMap of (string, int))()
tm.put("b", 2) ; tm.putIfAbsent("a", 1) ; tm.replace("b", 20)
tm.putAll(map(entry("c", 3)))
def tmSub = tm.headMap("c").tailMap("a").subMap("a", "c")
assert(tm.firstKey() == "a" and tm.lastKey() == "c" and tm.getOrDefault("z", 9) == 9 and tmSub.size() == 2)
```

## Case 27

```babble
def tm = (java.util.TreeMap of (string, int))()
tm.putAll(map(entry("a", 1), entry("b", 2), entry("c", 3)))
assert(tm.lowerKey("b") == "a" and tm.floorKey("b") == "b")
assert(tm.ceilingKey("b") == "b" and tm.higherKey("b") == "c")
```

## Case 28

```babble
def ts = (java.util.TreeSet of string)()
ts.addAll(list("a", "b", "c"))
ts.retainAll(list("a", "b", "c"))
ts.removeAll(list("z"))
def tsSub = ts.headSet("c").tailSet("a").subSet("a", "c")
assert(ts.first() == "a" and ts.last() == "c")
assert(ts.lower("b") == "a" and ts.floor("b") == "b")
assert(ts.ceiling("b") == "b" and ts.higher("b") == "c" and tsSub.size() == 2)
```

## Case 29

```babble
def C = java.util.Collections
def c1: java.util.List of string = C.emptyList()
def c2 = C.singletonList("x")
def c3 = C.unmodifiableList(c2)
def c4 = C.synchronizedList(c2)
def c5 = C.nCopies(2, "y")
def c6: java.util.Comparator of string = C.reverseOrder()
assert(c1.size() + c3.size() + c4.size() + c5.size() == 4 and c6 != null)
```

## Case 30

```babble
def arrCopy = java.util.Arrays.copyOf(array.of(1, 2, 3), 2)
def arrRange = java.util.Arrays.copyOfRange(array.of(1, 2, 3), 1, 3)
def arrStr = java.util.Arrays.toString(arrCopy)
def arrCnt = java.util.Arrays.stream(arrCopy).count()
assert(arrStr == "[1, 2]" and string(arrRange) == "[2, 3]" and arrCnt == 2L)
```

## Case 31

```babble
def s = "hello-world"
assert(s.startsWith("he") and s.endsWith("ld") and s.contains("-") and s.matches("hello.*") and s.equalsIgnoreCase("HELLO-WORLD"))
assert(s.concat("!").replace("!", "?").replaceAll("\\?", "").replaceFirst("-", "_") == "hello_world")
assert(s.compareTo("hello") > 0 and s.compareToIgnoreCase("HELLO") > 0 and s.indexOf("-") == 5 and s.lastIndexOf("o") == 7)
```

## Case 32

```babble
def ld = java.time.LocalDate.parse("2025-01-10").plusDays(2L).minusDays(1L).withDayOfMonth(15)
def sb = java.lang.StringBuilder("hi").append("!").toString()
def cb = java.nio.CharBuffer.wrap("ab").length()
def tok = java.util.StringTokenizer("first second").nextToken()
assert(string(ld) == "2025-01-15" and sb == "hi!" and cb == 2 and tok == "first")
```

## Case 33

```babble
def I = java.lang.Integer
assert(I.toHexString(15) == "f" and I.toBinaryString(2) == "10")
assert(I.toOctalString(8) == "10" and I.toUnsignedString(9) == "9")
```

## Case 34

```babble
def S = java.util.stream.Stream
def sEmpty: java.util.stream.Stream of string = S.empty()
def sGen = S.generate(fn (): string -> ("g")).limit(1L)
def sIter = S.iterate("i", fn (x: string): string -> (x)).limit(1L)
def sCat = S.concat(sGen, sIter).unordered().sequential()
assert(sEmpty.count() == 0L and sCat.count() == 2L)
```

## Case 35

```babble
def S = java.util.stream.Stream
def sFlat = S.of("g", "i")
    .flatMap(fn io (x: string): java.util.stream.Stream of string -> (S.of(x)))
    .takeWhile(fn (x: string): bool -> (x != ""))
    .dropWhile(fn (x: string): bool -> (false))
    .findFirst()
assert(sFlat.orElse("?") == "g")
```

## Case 36

```babble
def IS = java.util.stream.IntStream
def iStats = IS.rangeClosed(1, 3).asLongStream().asDoubleStream().summaryStatistics()
assert(iStats.getCount() == 3L and IS.range(1, 3).average().isPresent())
assert(IS.range(2, 5).min().getAsInt() == 2 and IS.range(2, 5).max().getAsInt() == 4)
```

## Case 37

```babble
def ssport = java.util.stream.StreamSupport.stream(list("s").spliterator(), false).findAny()
assert(ssport.orElse("?") == "s")
```

## Case 38

```babble
def natCmp: java.util.Comparator of string = java.util.Comparator.naturalOrder()
def nfCmp = java.util.Comparator.nullsFirst(natCmp)
def nlCmp = java.util.Comparator.nullsLast(natCmp)
assert(nfCmp.compare("a", "b") < 0 and nlCmp.compare("a", "b") < 0)
```

## Case 39

```babble
def pBase = java.util.function.Predicate(fn (x: string): bool -> (x.length() > 1))
def pEq: java.util.function.Predicate of string = java.util.function.Predicate.isEqual("ab")
assert(pBase.negate().negate().test("ab") and pEq.test("ab"))
```

## Case 40

```babble
def fId: java.util.function.Function of (string, string) = java.util.function.Function.identity()
assert(fId.andThen(fId).compose(fId).apply("ok") == "ok")
```

## Case 41

```babble
def natCmp: java.util.Comparator of string = java.util.Comparator.naturalOrder()
def minOp = java.util.function.BinaryOperator.minBy(natCmp)
def maxOp = java.util.function.BinaryOperator.maxBy(natCmp)
assert(minOp.apply("a", "b") == "a" and maxOp.apply("a", "b") == "b")
```

## Case 42

```babble
assert(java.lang.System.lineSeparator().length() > 0)
assert(java.lang.System.getProperty("babble.nonexistent.key", "fallback") == "fallback")
assert(java.lang.Runtime.getRuntime() != null)
assert(java.io.StringWriter().getBuffer().length() == 0)
```

## Case 43

```babble
assert(java.lang.Thread.currentThread().getName().length() > 0)
assert(java.lang.Class.forName("java.lang.String").getSimpleName() == "String")
assert(java.util.regex.Pattern.compile("a+").pattern() == "a+")
```

## Case 44

```babble
def cs: java.lang.CharSequence = "abcdef"
assert(cs.subSequence(1, 4).toString() == "bcd")
assert(java.lang.System.getenv() != null)
assert(java.lang.System.getenv("BABBLE_NONEXISTENT_ENV") == null)
assert(java.time.LocalDate.of(2025, 3, 4).toString() == "2025-03-04")
```

## Case 45

```babble
assert(java.lang.Integer.getInteger("babble.nonexistent.int") == null)
assert(java.lang.Integer.getInteger("babble.nonexistent.int", 7) == 7)
assert(java.lang.Long.getLong("babble.nonexistent.long") == null)
assert(java.lang.Long.getLong("babble.nonexistent.long", 9L) == 9L)
```
