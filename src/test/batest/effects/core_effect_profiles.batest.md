# Built-in core effect profiles : pure/nothrow scalars, string, entry, collections, mut/borrow, and views

References : BIP011 (§8 core effect profiles), BIP016 (pure-by-default), BIP019 (builtin axioms)

## Case 1

```babble
def fScalar = fn nothrow (b : byte, c : char) : double -> (
    def i : int = int(b) + int(c)
    double(i) + 1.5
)
def fEntry = fn nothrow (k : string, v : int) : int -> (
    def e = entry(k, v)
    e.getKey().length() + e.getValue()
)
assert(fScalar(byte(2), 'A') == 68.5 and fEntry("hi", 5) == 7)
```

## Case 2

```babble
def fStr = fn nothrow (s : string) : int -> (
    def t = s.trim().toUpperCase()
    if t.isEmpty() or t.isBlank() then ( 0 )
    else if t.startsWith("A") then ( t.length() + string(42).length() )
    else ( t.length() )
)
assert(fStr("  abc ") == 5)
```

## Error : Function badParse declared nothrow violates nothrow guarantee

```babble
def badParse = fn nothrow (s : string) : int -> ( int(s) )
```

```error
Function badParse declared nothrow violates nothrow guarantee
```

## Error : Function badCharAt declared nothrow violates nothrow guarantee

```babble
def badCharAt = fn nothrow (s : string) : char -> ( s.charAt(0) )
```

```error
Function badCharAt declared nothrow violates nothrow guarantee
```

## Error : Function badBang declared nothrow violates nothrow guarantee

```babble
def badBang = fn nothrow (x : int?) : int -> ( x! )
```

```error
Function badBang declared nothrow violates nothrow guarantee
```

## Case 6

```babble
def okParse = fn (s : string) : int -> ( int(s) )
def okCharAt = fn (s : string) : char -> ( s.charAt(0) )
def okBang = fn (x : int?) : int -> ( x! )
assert(okParse("42") == 42 and okCharAt("xy") == 'x' and okBang(7) == 7)
```

## Case 7

```babble
def buildAndRead = fn () : int -> (
    def xs = list(10, 20, 30)
    def s = set("a", "b")
    def m = map(entry("k", 7))
    xs.size() + xs.get(1) + s.size() + (m.get("k")!)
)
assert(buildAndRead() == 32)
```

## Case 8

```babble
def emptyNothrow = fn nothrow () : int -> (
    def xs = list of int ()
    def s = set of string ()
    def m = map of (string, int) ()
    xs.size() + s.size() + m.size()
)
assert(emptyNothrow() == 0)
```

## Case 9

```babble
def sumSize = fn (xs : list of any) : int -> ( xs.size() )
def cloneList = fn (xs : list of int) : list of int -> ( list of int (from: xs) )
def nums = list(1, 2, 3)
def cloned = cloneList(nums)
assert(sumSize(nums) == 3 and cloned == list(1, 2, 3))
```

## Case 10

```babble
def readMap = fn (m : map of (string, int), k : string) : int -> (
    if m.containsKey(k) then ( m.getOrDefault(k, 0) ) else ( 0 )
)
def m = map(entry("a", 9))
assert(readMap(m, "a") == 9 and readMap(m, "b") == 0)
```

## Case 11

```babble
def appendAndSet = fn (mut xs : list of int, v : int) : int -> (
    xs.add(v); xs.set(0, 99); xs.size()
)
def putEntry = fn (mut m : map of (string, int), k : string, v : int) : int? -> ( m.put(k, v) )
def xs = list(1, 2)
def m = map of (string, int) ()
assert(appendAndSet(xs, 3) == 3 and xs == list(99, 2, 3))
assert(putEntry(m, "x", 42) == null and m.get("x") == 42)
```

## Error : Function badPureList violates pure guarantee (performs I/O or externa...

```babble
def g = list(1, 2)
def badPureList = fn () : list of int -> (
    g.add(3)
    g
)
```

```error
Function badPureList violates pure guarantee (performs I/O or external mutation) : mark function as io
```

## Error : Cannot mutate parameter xs (mut Java method xs.add) : mark parameter ...

```babble
def badMut = fn (xs : list of int) -> ( xs.add(1) )
```

```error
Cannot mutate parameter xs (mut Java method xs.add) : mark parameter as mut xs
```

## Case 14

```babble
def mutSub = fn (mut xs : list of int) -> (
    def sub = xs.subList(0, 1)
    sub.add(99)
)
def mutKeySet = fn (mut m : map of (string, int)) -> (
    def ks = m.keySet()
    ks.remove("a")
)
def xs = list(1, 2); mutSub(xs)
def m = map(entry("a", 1), entry("b", 2)); mutKeySet(m)
assert(xs == list(1, 99, 2) and m.size() == 1 and m.get("b") == 2)
```

## Error : Cannot mutate parameter xs (mut Java method sub.add) : mark parameter...

```babble
def badSubMut = fn (xs : list of int) -> (
    def sub = xs.subList(0, 1)
    sub.add(99)
)
```

```error
Cannot mutate parameter xs (mut Java method sub.add) : mark parameter as mut xs
```

## Error : Borrowed parameter xs escapes via outer variable sink : mark paramete...

```babble
var sink : (list of int)? = null
def badSubEscape = fn io (xs : list of int) -> (
    sink = xs.subList(0, 1)
)
```

```error
Borrowed parameter xs escapes via outer variable sink : mark parameter as shared xs
```

## Error : Cannot mutate parameter m (mut Java method ks.remove) : mark paramete...

```babble
def badKeySetMut = fn (m : map of (string, int)) -> (
    def ks = m.keySet()
    ks.remove("a")
)
```

```error
Cannot mutate parameter m (mut Java method ks.remove) : mark parameter as mut m
```

## Error : Function badGet declared nothrow violates nothrow guarantee

```babble
def badGet = fn nothrow (xs : list of int) : int -> ( xs.get(0) )
```

```error
Function badGet declared nothrow violates nothrow guarantee
```

## Error : Function badCap declared nothrow violates nothrow guarantee

```babble
def badCap = fn nothrow () : list of int -> ( list of int (capacity: 4) )
```

```error
Function badCap declared nothrow violates nothrow guarantee
```

## Mutable entry with fresh unique vs borrowed element

```babble
def Box = class (def v : int) ()
def mkEntry = fn (shared b : Box) : entry of (string, Box) -> (
    def e1 = entry("a", Box(1))
    def e2 = entry("b", b)
    discard(e1)
    e2
)
assert(mkEntry(Box(7)).getValue().v == 7)
```

## array(from:) across list of Box and frozen array of Box

```babble
def Box = class (def v : int) ()
def copyArr = fn (xs : list of Box, fa : frozen array of Box) : int -> (
    def a1 = (array of Box)(from: xs)
    def a2 = (array of Box)(from: fa)
    a1.size() + a2.size()
)
def b = Box(7)
assert(copyArr(list(b), freeze((array of Box).of(Box(7)))) == 2)
```

## list(from:) with frozen and mutable sources, plus sortedset(comparator:)

```babble
def Box = class (def v : int) ()
def run = fn (xs : list of Box, fxs : frozen list of int) : int -> (
    def c1 = (list of Box)(from: xs)
    def c2 = (list of int)(from: fxs)
    def s = (sortedset of int)(comparator: fn (a : int, b : int) : int -> ( a - b ), from: fxs)
    c1.size() + c2.size() + s.size()
)
assert(run(list(Box(1)), freeze(list(3, 1, 2))) == 7)
```
