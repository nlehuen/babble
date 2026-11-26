# First-class ranges, discrete progressions, and counted `for` loops

References : `BIP031` (`range of T`, `lo..hi`, `lo..<hi`, `.step(k)`, `.reversed()`, covariance, counted `for` lowering).

```babble
;; babble preamble
def expectEq of T = fn io (actual: T, expected: T) -> (
    assert(actual == expected, "expected " + expected + ", got " + actual)
)

def collectInt = fn io (r: range of int): list of int -> (
    def out = list of int()
    for i in r then ( out.add(i) )
    out
)

def collectLong = fn io (r: range of long): list of long -> (
    def out = list of long()
    for i in r then ( out.add(i) )
    out
)
```

## Inclusive int range bounds, contains, and isEmpty

```babble
def a = 1..5
assert(a.start() == 1 and a.end() == 5 and a.inclusive() and not a.isEmpty())
assert(a.contains(1) and a.contains(3) and a.contains(5))
assert(not a.contains(0) and not a.contains(6))
assert(not (5..5).isEmpty() and (6..5).isEmpty())
```

## Half-open int range bounds, contains, and isEmpty

```babble
def b = 1..<5
assert(b.start() == 1 and b.end() == 5 and not b.inclusive() and not b.isEmpty())
assert(b.contains(1) and b.contains(4) and not b.contains(5))
assert((5..<5).isEmpty() and (6..<5).isEmpty())
```

## Stepped int progressions

```babble
def s = (3..<10).step(5)
expectEq(collectInt(s), list of int(3, 8))
assert(s.contains(3) and s.contains(8) and not s.contains(4) and not s.contains(10))
expectEq(collectInt((3..<10).step(2).step(5)), list of int(3, 8))
```

## Reversed int progressions

```babble
expectEq(collectInt((3..<10).step(5).reversed()), list of int(8, 3))
expectEq(collectInt((1..6).step(2).reversed().reversed()), list of int(1, 3, 5))
expectEq(collectInt((5..<5).step(3).reversed()), list of int())
```

## Direct counted for loops with inclusive, exclusive, step, and reversed

```babble
def a = list of int()
for i in 1..4 then ( a.add(i) )
expectEq(a, list of int(1, 2, 3, 4))

def b = list of int()
for i in (3..<10).step(5) then ( b.add(i) )
expectEq(b, list of int(3, 8))

def c = list of int()
for i in (1..7).step(2).reversed() then ( c.add(i) )
expectEq(c, list of int(7, 5, 3, 1))
```

## Direct counted for loops with dynamic step, break, continue, and loop value

```babble
var k = 3
def out = list of int()
def res = for i in (0..<12).step(k) then (
    if i == 3 then ( continue )
    if i == 9 then ( break i * 10 )
    out.add(i)
    i
)
expectEq(out, list of int(0, 6))
expectEq(res, 90)
```

## Overflow-safe counted for loops at INT MAX_VALUE

```babble
def hi = 2147483647
def a = list of int()
for i in (hi - 2)..hi then ( a.add(i) )
expectEq(a, list of int(hi - 2, hi - 1, hi))

def b = list of int()
for i in ((hi - 4)..hi).step(2).reversed() then ( b.add(i) )
expectEq(b, list of int(hi, hi - 2, hi - 4))
```

## Overflow-safe int range contains across full INT span

```babble
def lo = -2147483648
def hi = 2147483647
assert((lo..hi).contains(lo) and (lo..hi).contains(hi))
assert(((lo..hi).step(2)).contains(lo) and not ((lo..hi).step(2)).contains(hi))
```

## Byte and short ranges and counted loops

```babble
def bs = list of byte()
for b in byte(1)..byte(3) then ( bs.add(b) )
expectEq(bs, list of byte(byte(1), byte(2), byte(3)))

def ss = list of short()
for s in (short(10)..<short(16)).step(2) then ( ss.add(s) )
expectEq(ss, list of short(short(10), short(12), short(14)))
```

## Char ranges and counted loops

```babble
def cs = list of char()
for ch in ('a'..'e').step(2).reversed() then ( cs.add(ch) )
expectEq(cs, list of char('e', 'c', 'a'))
assert(('a'..'z').contains('m') and not ('a'..'z').contains('A'))
```

## Long ranges including Long MAX_VALUE and MIN_VALUE

```babble
def hi = 9223372036854775807l
def a = list of long()
for x in (hi - 2l)..hi then ( a.add(x) )
expectEq(a, list of long(hi - 2l, hi - 1l, hi))

def r = (0l..<10l).step(3l).reversed()
expectEq(collectLong(r), list of long(9l, 6l, 3l, 0l))
assert(r.contains(6l) and not r.contains(7l))
assert(((-9223372036854775807l - 1l)..hi).contains(hi))
```

## Non-discrete float and double interval ranges

```babble
def df = 0.0f..1.0f
assert(df.contains(0.5f) and df.contains(1.0f) and not df.isEmpty())

def dd = 0.0..<1.0
assert(dd.contains(0.0) and dd.contains(0.999) and not dd.contains(1.0))
assert(not dd.contains(0.0 / 0.0))
assert(((0.0 / 0.0)..1.0).isEmpty())
```

## Non-discrete string interval ranges

```babble
def ds = "apple".."cherry"
assert(ds.contains("banana") and ds.contains("cherry") and not ds.contains("date"))
assert(not ("apple"..<"cherry").contains("cherry"))
assert(("z".."a").isEmpty())
```

## Range equality, hashCode, and toString

```babble
def r1 = 1..5
def r2 = 1..5
assert(r1 == r2 and r1.hashCode() == r2.hashCode())
assert(r1 != (1..<5) and r1 != (1..5).step(2) and r1 != (1..5).reversed())
expectEq("" + r1, "1..5")
expectEq("" + (3..<10).step(5).reversed(), "(3..<10).step(5).reversed()")
```

## Range covariance, is range, and as range

```babble
def r1 = 1..5
def wide: range of any = r1
def asNum: range of java.lang.Number = r1
assert(wide is range and asNum is range and wide.contains(3) and not wide.contains("3"))
def boxed: any = r1
def back = boxed as range
expectEq(back.end(), 5)
```

## Range as Iterable and explicit iterator()

```babble
def sumIter = fn io (shared xs: java.lang.Iterable of int): int -> (
    var total = 0
    def it = xs.iterator()
    while it.hasNext() then ( total += it.next() )
    total
)
expectEq(sumIter(1..5), 15)
expectEq(sumIter((1..10).step(3)), 22)
expectEq(sumIter((1..4).reversed()), 10)
```

## Bound precedence with arithmetic and closures capturing loop variable

```babble
def fns = list of fn (): int()
for i in 1..(1 + 3) then (
    fns.add(fn (): int -> ( i ))
)
def vals = list of int()
for f in fns then ( vals.add(f()) )
expectEq(vals, list of int(1, 2, 3, 4))
```

## Non-positive step throws at runtime

```babble
def r = (1..10).step(0)
```

```error
Range step must be positive : 0
```

## Dynamic non-positive step in counted for throws at runtime

```babble
var k = -2
for i in (1..10).step(k) then (
    assert(false)
)
```

```error
Range step must be positive : -2
```

## Negative constant step throws at runtime

```babble
def r = (1..10).step(-2)
```

```error
Range step must be positive : -2
```

## Dynamic zero step in counted for throws at runtime

```babble
var s = 0
for i in (1..10).step(s) then (
    assert(false)
)
```

```error
Range step must be positive : 0
```

## Unparenthesized range with .step hints to parenthesize the range

```babble
def r = 1..10.step(5)
```

```error
No such member : step. Parenthesize the range : (1..10).step(5)
```

## Iterating over a non-discrete double range is a static error

```babble
for x in 0.0..1.0 then ( assert(x >= 0.0) )
```

```error
Not iterable : range of double is not discrete
```

## Calling .step on a non-discrete double range is a static error

```babble
def r = (0.0..1.0).step(2)
```

```error
Not iterable : range of double is not discrete
```

## Calling .reversed on a non-discrete string range is a static error

```babble
def r = ("a".."z").reversed()
```

```error
Not iterable : range of string is not discrete
```

## Function-local counted int loops with non-negative and signed starts

```babble
def sumMod = fn (n: int, k: int): int -> (
    var s = 0
    for i in 0..<n then ( s += (i mod 3) + (i // 3) )
    for i in 0..n then ( s += i mod 5 )
    for i in (0..<n).step(3) then ( s += i // 3 )
    for i in (0..n).step(k) then ( s += i mod 3 )
    for i in (-2)..n then ( s += i mod 3 )
    s
)
assert(sumMod(6, 2) == 33)
```

## Function-local counted long loops with constant and dynamic steps

```babble
def sumLong = fn (n: long, k: long): long -> (
    var s = 0l
    for i in 0l..<n then ( s += (i mod 3l) + (i // 3l) + (i mod 8l) + (i // 8l) )
    for i in 0l..n then ( s += i mod 5l )
    for i in (0l..<n).step(3l) then ( s += i // 3l )
    for i in (0l..n).step(k) then ( s += i mod 3l )
    s
)
assert(sumLong(6l, 2l) == 39l)
```

## Function-local counted char loops with constant and dynamic steps

```babble
def sumChar = fn (hi: char, k: int): int -> (
    var s = 0
    for ch in ('a'..hi).step(2) then ( s += int(ch) mod 5 )
    for ch in ('a'..<hi).step(k) then ( s += int(ch) // 5 )
    s
)
assert(sumChar('f', 2) == 65)
```

## Function-local reversed counted loops with non-negative mod and floor division

```babble
;; QUESTIONABLE : non-inlined -O2 fn with stepped char counted loop throws ClassCastException (Integer to Character) in MirLowerer/MirEmitter
def sumRev = fn (n: int, m: long): long -> (
    var s = 0l
    for i in (0..n).step(2).reversed() then ( s += long(i mod 3) )
    for j in (0l..m).step(2l).reversed() then ( s += j mod 3l )
    s
)
var f = sumRev
assert(f(6, 6l) == 6l)
```
