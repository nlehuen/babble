# Core collections : list, set, sortedset, map, sortedmap, entry, C of T (...), variance, and is-checks

References : BIP011 (§5, BIP011-D1, BIP011-D8), BIP012 (`of` type application)

## Case 1

```babble
def a = list of int ()
a.add(10)
a.add(20)
def b = list of int? (1, null, 3)
def c : list of int = list()
c.add(7)
def d = list(1, 2.5)
def e = set("a", null)
def f = set(1, 2, 1)
assert(a.size() == 2 and b.get(1) == null and c.get(0) == 7)
assert(d.get(0) == 1.0 and e.size() == 2 and f.size() == 2)
```

## Case 2

```babble
def g = map(entry("x", 1), entry("y", 2))
def h = map of (string, int) ()
h.put("k", 9)
def ent = entry(value: 42, key: "ans")
assert(g.get("y") == 2 and h.get("k") == 9)
assert(ent.getKey() == "ans" and ent.getValue() == 42)
```

## Error : Missing type : cannot infer type arguments for list

```babble
def xs = list()
```

```error
Missing type : cannot infer type arguments for list
```

## Case 4

```babble
def capList : list of int = list(capacity: 4)
capList.add(1)
def capSet : set of string = set(capacity: 4)
capSet.add("hi")
def capMap : map of (string, int) = map(capacity: 4)
capMap.put("a", 5)
assert(capList.size() == 1 and capSet.size() == 1 and capMap.get("a") == 5)
```

## Case 5

```babble
def ints = list(1, 2, 3)
def widenedCopy : list of int? = list(from: ints)
widenedCopy.add(null)
def m1 = map(entry("a", 1))
def m2 : map of (string, int?) = map(from: m1)
m2.put("b", null)
assert(widenedCopy.size() == 4 and m2.get("b") == null)
```

## Error : java.lang.IllegalArgumentException : Illegal Capacity: -1

```babble
def bad : list of int = list(capacity: -1)
```

```error
java.lang.IllegalArgumentException : Illegal Capacity: -1
```

## Case 7

```babble
def Item = record (id : int) implements java.lang.Comparable of Item (
    def compareTo = fn (other : Item) : int -> ( this.id - other.id )
)
def ss1 = sortedset(3, 1, 2)
def ss2 = sortedset(Item(2), Item(1))
assert(ss1.first() == 1 and ss2.first().id == 1)
```

## Case 8

```babble
def desc = fn (x : int, y : int) : int -> ( y - x )
def ssDesc = sortedset(comparator: desc)
ssDesc.add(1); ssDesc.add(3); ssDesc.add(2)
def ssFrom = sortedset(from: list(1, 3, 2), comparator: desc)
assert(ssDesc.first() == 3 and ssFrom.first() == 3)
```

## Case 9

```babble
def sm1 = sortedmap(entry("b", 2), entry("a", 1))
def smDesc = sortedmap of (string, int) (comparator: fn (x : string, y : string) : int -> ( y.compareTo(x) ))
smDesc.put("a", 1)
smDesc.put("b", 2)
assert(sm1.firstKey() == "a" and smDesc.firstKey() == "b")
```

## Static Comparable check rejects nullable keys when comparator: is omitted

```babble
def ssNull = sortedset(1, null)
```

```error
Type error : int? is not Comparable (pass comparator: ...)
```

## Static Comparable check rejects unordered class when comparator: is omitted

```babble
def Plain = class (def n : int) ()
def smBad : sortedmap of (Plain, int) = sortedmap()
```

```error
Type error : Plain is not Comparable (pass comparator: ...)
```

## Case 12

```babble
def smNullVal : sortedmap of (string, int?) = sortedmap()
smNullVal.put("k", null)
assert(smNullVal.containsKey("k") and smNullVal.get("k") == null)
```

## Case 13

```babble
def dq = java.util.ArrayDeque of string ()
dq.add("first")
def Box of T = class (def value : T) ()
def bx = Box of (int | string) ("hi")
def id of T = fn (x : T) : T -> ( x )
def d : double = id of double (1)
def emp = java.util.Collections.emptyList of string ()
assert(dq.peek() == "first" and bx.value == "hi" and d == 1.0 and emp.size() == 0)
```

## Error : Wrong number of type arguments : expected 1, got 2

```babble
def bad = list of (int, string) ()
```

```error
Wrong number of type arguments : expected 1, got 2
```

## Mutable collection invariance (BIP011-D8)

```babble
def xs : list of int = list(1, 2)
def ys : list of int? = xs
```

```error
Type error : expected list(int?), got list(int)
```

## Mutable generic class invariance (BIP011-D8)

```babble
def Box of T = class (def value : T) ()
def b1 : Box of int = Box(1)
def b2 : Box of any = b1
```

```error
Type error : expected Box(any), got Box(int)
```

## Covariance on immutable record, entry, and java.util.Optional

```babble
def Pair of (A, B) = record (first : A, second : B) ()
def p1 : Pair of (int, string) = Pair(1, "ok")
def p2 : Pair of (any, any?) = p1
def e1 : entry of (string, int) = entry("k", 7)
def e2 : entry of (any, int?) = e1
def o1 : java.util.Optional of string = java.util.Optional.of("hi")
def o2 : java.util.Optional of any = o1
assert(p2.first == 1 and e2.getValue() == 7 and o2.get() == "hi")
```

## Covariance on non-mut (borrowed) collection parameter

```babble
def checkBorrow = fn (xs : list of any?) : bool -> ( xs == xs )
def nums = list(1, 2, 3)
assert(checkBorrow(nums))
```

## Case 19

```babble
def items : list of int | string = list(10, 20)
def r1 = if items is list then ( items.get(0) ) else ( 0 )
def s : any = sortedset(1, 2)
def m : any = sortedmap(entry("a", 1))
def e : any = entry("k", 9)
assert(r1 == 10 and s is set and s is sortedset)
assert(m is map and m is sortedmap and e is entry and not ("no" is list))
```

## Case 20

```babble
def hs = (set of int)(capacity: 4) ; hs.add(1)
def hm = (map of (string, int))(capacity: 4) ; hm.put("k", 2)
def ss1 = (sortedset of int)(from: list(3, 1, 2))
def sm1 = (sortedmap of (string, int))(from: map(entry("b", 2), entry("a", 1)))
assert(hs.size() == 1 and hm.get("k") == 2 and ss1.first() == 1 and sm1.firstKey() == "a")
```

## Case 21

```babble
def Score = record (n: int) implements java.lang.Comparable of Score (
    def compareTo = fn (other: Score): int -> (this.n - other.n)
)
def ss = (sortedset of Score)(from: list(Score(20), Score(10)))
def sm = (sortedmap of (Score, string))(from: map(entry(Score(2), "two"), entry(Score(1), "one")))
assert(ss.first() == Score(10) and sm.firstKey() == Score(1))
```

## Case 22

```babble
def e1 = entry(value: 9, key: "k")
def e2 = (entry of (string, int))("m", 7)
def revInt = java.util.Comparator(fn (a: int, b: int): int -> (b - a))
def ss1 = (sortedset of int)(comparator: revInt) ; ss1.add(1) ; ss1.add(3)
def ss2 = (sortedset of int)(comparator: revInt, from: list(1, 3))
assert(e1.getKey() == "k" and e2.getValue() == 7 and ss1.first() == 3 and ss2.first() == 3)
```

## Case 23

```babble
def revStr = java.util.Comparator(fn (a: string, b: string): int -> (b.compareTo(a)))
def sm1 = (sortedmap of (string, int))(comparator: revStr) ; sm1.put("a", 1) ; sm1.put("z", 2)
def sm2 = (sortedmap of (string, int))(comparator: revStr, from: map(entry("a", 1), entry("z", 2)))
assert(sm1.firstKey() == "z" and sm2.firstKey() == "z")
```

## Error : No such parameter : capacity

```babble
sortedmap(capacity: 4)
```

```error
No such parameter : capacity
```

## Error : No such parameter : foo

```babble
entry(foo: 1, bar: 2)
```

```error
No such parameter : foo
```

## Error : No such parameter : capacity (2)

```babble
sortedset(capacity: 4)
```

```error
No such parameter : capacity
```

## Error : No such parameter : comparator

```babble
list(comparator: null)
```

```error
No such parameter : comparator
```

## Error : No such parameter : comparator (2)

```babble
map(comparator: null)
```

```error
No such parameter : comparator
```

## Error : Type error : expected map, got int

```babble
sortedmap(from: 123)
```

```error
Type error : expected map, got int
```

## Error : Type error : expected java.lang.Iterable, got int

```babble
sortedset(from: 123)
```

```error
Type error : expected java.lang.Iterable, got int
```

## Error : Missing parameter : key

```babble
entry()
```

```error
Missing parameter : key
```

## Error : Missing parameter : value

```babble
entry(1)
```

```error
Missing parameter : value
```

## Error : Too many parameters : 2 declared, 3 given

```babble
entry(1, 2, 3)
```

```error
Too many parameters : 2 declared, 3 given
```

## Error : No such parameter : foo (2)

```babble
list(foo: 1)
```

```error
No such parameter : foo
```
