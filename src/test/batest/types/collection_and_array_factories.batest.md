# Collection and array factory calls, named arguments, and natural comparables

References : `BIP008` (typing & collections), `BIP012` (`of` syntax), `analysis.CollectionCallChecker`.

```babble preamble
def Tag = class (def n: int) ()
```

## Empty array.of inferred from expected array type

```babble
def a: array of int = array.of()
def b: array of string = array.of()
assert(a.size() == 0 and b.size() == 0)
```

## array.of with named argument fails

```babble
array.of(size: 1)
```

```error
No such parameter : size
```

## Empty array.of without expected type fails

```babble
def a = array.of()
```

```error
Missing type : cannot infer type arguments for array
```

## Empty array.of with generic expected element fails

```babble
def empty of T = fn (): array of T -> ( array.of() )
```

```error
Generic array creation : array of T
```

## array.of on frozen type variable fails generic array creation

```babble
def wrap of T = fn (x: frozen T) -> ( array.of(x) )
```

```error
Generic array creation : array of (frozen T)
```

## array.of falls back to join when expected element is incompatible

```babble
def a: array of string = array.of(1, 2)
```

```error
Type error : expected array of string, got array of int
```

## array() with no arguments reports missing size

```babble
(array of int)()
```

```error
Missing parameter : size
```

## array() with too many arguments reports arity error

```babble
(array of int)(1, 2)
```

```error
Too many parameters : 1 declared, 2 given
```

## array(from: non-iterable) reports type error

```babble
array(from: 42)
```

```error
Type error : expected iterable or array, got int
```

## array of int(from: list of string) reports element type mismatch

```babble
(array of int)(from: list("a", "b"))
```

```error
Type error : expected int elements, got string
```

## array(from: generic list) rejects generic array creation

```babble
def copy of T = fn (xs: list of T) -> ( array(from: xs) )
```

```error
Generic array creation : array of T
```

## array(n) with generic expected element fails

```babble
def alloc of T = fn (n: int): array of T -> ( array(n) )
```

```error
Generic array creation : array of T
```

## array(n) without type hint reports missing type

```babble
def a = array(3)
```

```error
Missing type : cannot infer type arguments for array
```

## array with unknown named parameter fails

```babble
(array of int)(size: 3)
```

```error
No such parameter : size
```

## Expected Iterable, Collection, Set, and Map hints infer empty factories

```babble
def it: java.lang.Iterable of int = list()
def col: java.util.Collection of string = set()
def ss: java.util.Set of int = sortedset()
def sm: java.util.Map of (string, int) = sortedmap()
assert(not it.iterator().hasNext() and col.isEmpty() and ss.isEmpty() and sm.isEmpty())
```

## entry accepts key: with positional or named value:

```babble
def e1 = entry(key: "a", value: 1)
def e2 = entry(key: "b", 2)
assert(e1.getKey() == "a" and e1.getValue() == 1 and e2.getKey() == "b" and e2.getValue() == 2)
```

## entry rejects duplicate key: parameter

```babble
entry(key: "a", key: "b")
```

```error
No such parameter : key
```

## entry rejects unknown second parameter name

```babble
entry(key: "a", bad: 1)
```

```error
No such parameter : bad
```

## list(capacity:) without type hint fails

```babble
list(capacity: 8)
```

```error
Missing type : cannot infer type arguments for list
```

## map(capacity:) without type hint fails

```babble
map(capacity: 8)
```

```error
Missing type : cannot infer type arguments for map
```

## sortedset(comparator: null) without type hint fails

```babble
sortedset(comparator: null)
```

```error
Missing type : cannot infer type arguments for sortedset
```

## sortedmap(comparator:) without value hint fails

```babble
sortedmap(comparator: fn (a: string, b: string): int -> ( a.compareTo(b) ))
```

```error
Missing type : cannot infer type arguments for sortedmap
```

## sortedset with null comparator uses natural ordering and accepts swapped from:

```babble
def s1 = (sortedset of int)(from: list(2, 1), comparator: null)
def s2 = sortedset(comparator: null, from: list(3, 1, 2))
def s3 = sortedset(comparator: fn (a: int, b: int): int -> ( b - a ), from: list(1, 3, 2))
assert(s1.first() == 1 and s2.first() == 1 and s3.first() == 3)
```

## sortedset with null comparator rejects non-Comparable type

```babble
(sortedset of Tag)(comparator: null)
```

```error
Type error : Tag is not Comparable (pass comparator: ...)
```

## sortedset with bad from: and comparator: reports from type error

```babble
sortedset(from: 42, comparator: null)
```

```error
Type error : expected java.lang.Iterable, got int
```

## Explicit map and sortedmap of (K, V) check positional entries

```babble
def m = (map of (string, int))(entry("a", 1), entry("b", 2))
def sm = (sortedmap of (string, int))(entry("b", 2), entry("a", 1))
assert(m.get("b") == 2 and sm.firstKey() == "a")
```

## Positional non-entry argument to map reports expected entry

```babble
map(42)
```

```error
Type error : expected entry, got int
```

## Integer and floating-point primitive scalars are naturally Comparable

```babble
def sb = sortedset(byte(2), byte(1))
def ss = sortedset(short(2), short(1))
def sl = sortedset(2L, 1L)
def sf = sortedset(2.0f, 1.0f)
def sd = sortedset(2.0, 1.0)
assert(sb.first() == byte(1) and ss.first() == short(1) and sl.first() == 1L and sf.first() == 1.0f and sd.first() == 1.0)
```

## Bool, char, and Comparable Java BigInteger are naturally Comparable

```babble
def sbo = sortedset(true, false)
def sc = sortedset('b', 'a')
def sbi = sortedset(java.math.BigInteger.TEN, java.math.BigInteger.ONE)
assert(not sbo.first() and sc.first() == 'a' and sbi.first() == java.math.BigInteger.ONE)
```

## Non-Comparable Java class rejected by sortedset

```babble
sortedset(java.lang.Object())
```

```error
Type error : java.lang.Object is not Comparable (pass comparator: ...)
```

## Array element type rejected by sortedset

```babble
sortedset(array.of(1, 2))
```

```error
Type error : array of int is not Comparable (pass comparator: ...)
```
