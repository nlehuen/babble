# Runtime collections, arrays, and print dynamic dispatch

References : `BIP001` (runtime collections & arrays), `BIP008` (strict typing & casts), `ArraySupport`, `CoreCollections`, `PrintFunction`.

```babble preamble
def dyn = fn (v: any?): any? -> ( v )
def coerce of T = fn io (shared x: any?): T -> (
    def l: java.util.List of T = java.util.ArrayList()
    def m = java.lang.Class.forName("java.util.List").getMethod("add", java.lang.Class.forName("java.lang.Object"))!
    m.invoke(l, x)
    l.get(0)
)
def Box = class (def x: int) ()
def Key = class (def k: int) implements java.lang.Comparable of Key (
    def compareTo = fn (o: Key): int -> ( this.k - o.k )
)
def DPrint = type (fn io (v: any?, to: any?): null) | (fn io (v: any?, to: any?, z: int = ...): null)
def DCmp = type (fn (comparator: any? = ..., z: int = ...): any?) | (fn (z: int = ..., comparator: any? = ...): any?)
def DFCmp = type (fn (from: any?, comparator: any?): any?) | (fn (comparator: any?, from: any?): any?)
```

## Array deepEquals mismatched primitive and object array types and null

```babble
assert(not (array of int).of(1).equals(null))
def i = dyn((array of int).of(1))
assert(dyn((array of string).of("a")) != i and dyn((array of double).of(1.0)) != i)
assert(dyn((array of bool).of(true)) != i and dyn((array of byte).of(byte(1))) != i)
assert(dyn((array of float).of(1.0f)) != i and dyn((array of char).of('a')) != i)
```

## Boxed long array widened from int list and first-class array methods

```babble
def ls = (array of long?)(from: list(1, 2))
assert(ls.get(0) == 1L and ls.get(1) == 2L)
def a = (array of int).of(10, 20)
def toStr = a.toString
def getCls = a.getClass
assert(toStr() == "[10, 20]" and getCls() != null)
```

## Storing Integer into string array throws ArrayStoreException

```babble
def sa = (array of string).of("x")
def s = coerce of (fn io (i: int, v: any?): null)(sa.set)
s(0, 123)
```

```error
java.lang.ArrayStoreException: java.lang.Integer
```

## Storing Babble instance into string array throws formatted ArrayStoreException

```babble
def sa = (array of string).of("x")
def s = coerce of (fn io (i: int, v: any?): null)(sa.set)
s(0, Box(1))
```

```error
java.lang.ArrayStoreException: Box
```

## Storing Java string into Babble class array throws ArrayStoreException

```babble
def ba = (array of Box).of(Box(1))
def s = coerce of (fn io (i: int, v: any?): null)(ba.set)
s(0, "str")
```

```error
java.lang.ArrayStoreException: java.lang.String
```

## Storing unrelated Babble class into Babble class array throws ArrayStoreException

```babble
def Other = class (def x: int) ()
def ba = (array of Box).of(Box(1))
def s = coerce of (fn io (i: int, v: any?): null)(ba.set)
s(0, Other(1))
```

```error
java.lang.ArrayStoreException: Other
```

## Storing non-array into nested Babble array throws ArrayStoreException

```babble
def ma = (array of (array of Box)).of((array of Box).of(Box(1)))
def s = coerce of (fn io (i: int, v: any?): null)(ma.set)
s(0, "str")
```

```error
java.lang.ArrayStoreException: java.lang.String
```

## Storing string array into nested Babble array throws ArrayStoreException

```babble
def ma = (array of (array of Box)).of((array of Box).of(Box(1)))
def s = coerce of (fn io (i: int, v: any?): null)(ma.set)
s(0, (array of string).of("a"))
```

```error
java.lang.ArrayStoreException: [Ljava.lang.String;
```

## Storing wrong-depth Babble array into nested Babble array throws ArrayStoreException

```babble
def ma = (array of (array of Box)).of((array of Box).of(Box(1)))
def s = coerce of (fn io (i: int, v: any?): null)(ma.set)
s(0, ma)
```

```error
java.lang.ArrayStoreException: array of array of Box
```

## Array method get rejects missing index parameter

```babble
def a = (array of int).of(1)
coerce of (fn (): any?)(a.get)()
```

```error
Missing parameter : index
```

## Array method set rejects missing index parameter

```babble
def a = (array of int).of(1)
coerce of (fn io (): any?)(a.set)()
```

```error
Missing parameter : index
```

## Array method set rejects missing value parameter

```babble
def a = (array of int).of(1)
coerce of (fn io (i: int): any?)(a.set)(0)
```

```error
Missing parameter : value
```

## Array method equals rejects missing other parameter

```babble
def a = (array of int).of(1)
coerce of (fn (): any?)(a.equals)()
```

```error
Missing parameter : other
```

## Array method size rejects extra argument

```babble
def a = (array of int).of(1)
coerce of (fn (x: int): any?)(a.size)(1)
```

```error
Too many parameters : 0 declared, 1 given
```

## Core set from non-Collection iterable and sortedset with Babble Comparable keys

```babble
def mkSet = fn (xs: int...): set of int -> ( set(from: xs) )
def s = mkSet(3, 1, 3, 2)
def ss1 = sortedset(Key(2), Key(1))
def ss2 = sortedset(from: ss1)
def ss3 = sortedset(from: sortedset(2, 1))
assert(s.size() == 3 and ss2.first().k == 1 and ss3.first() == 1)
```

## Core sortedmap with Babble Comparable keys and standard keys

```babble
def sm = sortedmap(entry(Key(2), "b"), entry(Key(1), "a"))
def sm2 = sortedmap(entry(2, "b"))
assert(sm.firstKey().k == 1 and sm2.firstKey() == 2)
```

## Dynamic core sortedset and sortedmap calls with null comparator

```babble
def mkSS: DCmp = coerce of (fn (comparator: any? = ..., z: int = ...): any?)(sortedset)
def mkSM: DCmp = coerce of (fn (comparator: any? = ..., z: int = ...): any?)(sortedmap)
assert(coerce of (sortedset of int)(mkSS(comparator: null)).isEmpty())
assert(coerce of (sortedmap of (int, int))(mkSM(comparator: null)).isEmpty())
```

## Dynamic sortedset call with swapped comparator and from named arguments

```babble
def mkS2: DFCmp = coerce of (fn (from: any?, comparator: any?): any?)(sortedset)
def desc = fn (a: int, b: int): int -> ( b - a )
assert(coerce of (sortedset of int)(mkS2(comparator: desc, from: list(1, 3))).first() == 3)
```

## Dynamic entry named arguments handle swapped value and key labels

```babble
def DynVK = type (fn (value: any?, key: any?): any?) | (fn (key: any?, value: any?): any?)
def mk1: DynVK = coerce of (fn (value: any?, key: any?): any?)(entry)
def e1 = coerce of (entry of (string, string))(mk1(value: "v", key: "k"))
assert(e1.getKey() == "k" and e1.getValue() == "v")
```

## Dynamic entry named arguments fall back to positional order on unknown label

```babble
def DynVO = type (fn (value: any?, other: any?): any?) | (fn (other: any?, value: any?): any?)
def mk2: DynVO = coerce of (fn (value: any?, other: any?): any?)(entry)
def e2 = coerce of (entry of (string, string))(mk2(value: "a", other: "b"))
assert(e2.getKey() == "a" and e2.getValue() == "b")
```

## Dynamic list with from and extra named argument falls back to element list

```babble
def DynLF = type (fn (from: any?, foo: any?): any?) | (fn (foo: any?, from: any?): any?)
def l1: DynLF = coerce of (fn (from: any?, foo: any?): any?)(list)
assert(coerce of (list of any?)(l1(from: list(1), foo: 2)).size() == 2)
```

## Dynamic list with comparator and extra named argument falls back to element list

```babble
def DynLC = type (fn (comparator: any?, foo: any?): any?) | (fn (foo: any?, comparator: any?): any?)
def l2: DynLC = coerce of (fn (comparator: any?, foo: any?): any?)(list)
assert(coerce of (list of any?)(l2(comparator: null, foo: 2)).size() == 2)
```

## Negative capacity rejected for set

```babble
def n = -1
set of int(capacity: n)
```

```error
java.lang.IllegalArgumentException : Illegal Capacity: -1
```

## Negative capacity rejected for map

```babble
def n = -1
map of (string, int)(capacity: n)
```

```error
java.lang.IllegalArgumentException : Illegal Capacity: -1
```

## Dynamic entry rejects wrong argument count

```babble
coerce of (fn (a: any?): any?)(entry)(1)
```

```error
Wrong number of arguments for entry : expected 2, got 1
```

## Dynamic sortedset rejects capacity parameter

```babble
def DCap = type (fn (capacity: int = ..., z: int = ...): any?) | (fn (z: int = ..., capacity: int = ...): any?)
def f: DCap = coerce of (fn (capacity: int = ..., z: int = ...): any?)(sortedset)
f(capacity: 4)
```

```error
Unknown parameter capacity for sortedset
```

## Dynamic list rejects comparator parameter

```babble
def f: DCmp = coerce of (fn (comparator: any? = ..., z: int = ...): any?)(list)
f(comparator: null)
```

```error
Unknown parameter comparator for list
```

## Dynamic list rejects from and comparator parameters

```babble
def f: DFCmp = coerce of (fn (from: any?, comparator: any?): any?)(list)
f(from: list(1), comparator: null)
```

```error
Unknown parameters for list
```

## Dynamic sortedset rejects non-Comparator comparator value

```babble
def f: DCmp = coerce of (fn (comparator: any? = ..., z: int = ...): any?)(sortedset)
f(comparator: "bad")
```

```error
Type error : expected java.util.Comparator, got java.lang.String
```

## Print via dynamic zero-arg, single-arg, and rest-arg signatures

```babble
def p0 = coerce of (fn io (): null)(print)
p0()
def p1 = coerce of (fn io (v: any?): null)(print)
p1("A")
def pr = coerce of (fn io (xs: any?...): null)(print)
pr("B")
```

```stdout
AB
```

## Print and println via dynamic named to parameter

```babble
def sb = java.lang.StringBuilder()
def pn: DPrint = coerce of (fn io (v: any?, to: any?): null)(print)
pn("hi", to: sb)
def pln: DPrint = coerce of (fn io (v: any?, to: any?): null)(println)
pln("!", to: sb)
assert(sb.toString() == "hi!" + java.lang.System.lineSeparator())
```

## Print named to parameter rejects non-Appendable target

```babble
def pn: DPrint = coerce of (fn io (v: any?, to: any?): null)(print)
pn("hi", to: 123)
```

```error
print to: expects a Writer or PrintStream
```

## Print propagates IOException from Appendable in packed-rest path

```babble
print("x", to: java.io.PipedWriter())
```

```error
java.io.IOException : Pipe not connected
```

## Print propagates IOException from Appendable in named-arg path

```babble
def pn: DPrint = coerce of (fn io (v: any?, to: any?): null)(print)
pn("x", to: java.io.PipedWriter())
```

```error
java.io.IOException : Pipe not connected
```
