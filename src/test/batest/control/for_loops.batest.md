# For loops, multi-tier iterables/iterators, parameterized static factories, break/continue, narrowing, and l...

References : BIP013 (iterators and for loops), BIP016 (effect checks)
Shared custom iterable fixture for loop-variable type narrowing cases

```babble preamble
def Item = interface ( def k : fn () : int )
def Sub = class (def r : double) implements Item ( def k = fn () : int -> ( 1 ) )
def Other = class () implements Item ( def k = fn () : int -> ( 2 ) )
def ItemIter = class (private def i : int) (
    def hasNext = fn () : bool -> ( this.i < 2 )
    def next = fn mut () : Item -> ( this.i += 1 ; if this.i == 1 then ( Other() ) else ( Sub(2.5) ) )
)
def Items = class () ( def iterator = fn () : ItemIter -> ( ItemIter(0) ) )
```

## Case 1

```babble
def foo = (list of int).of(1, 2, 3)
def sum = 0
for value in foo then (
    sum += value
)
assert(sum == 6)
```

## Case 2

```babble
def a = list.of(10, 20, 30)
def b = (list of int).of()
def s = 0
for x in a then ( s += x )
def r = for x in b then ( x )
assert(s == 60)
assert(r == null)
```

## Case 3

```babble
def xs = (java.util.List of int).of(2, 4, 6)
def total = 0
for v in xs then ( total += v )
assert(total == 12)
```

## Case 4

```babble
def s = (set of string).of("hi", "there")
def len = 0
for w in s then ( len += w.length() )
def m = (map of (string, int)).of("a", 3, "b", 4)
def msum = 0
for e in m.entrySet() then ( msum += e.getValue() )
assert(len == 7)
assert(msum == 7)
```

## Case 5

```babble
def cs = "abcd".toCharArray()
def n = 0
for c in cs then (
    if c == 'b' then ( continue )
    n += 1
)
def parts = "x,y,z".split(",")
def out = ""
for p in parts then (
    if p == "z" then ( break )
    out = out + p
)
assert(n == 3)
assert(out == "xy")
```

## Case 6

```babble
def it = list(1, 2, 3, 4).iterator()
def acc = 0
for v in it then ( acc += v )
assert(acc == 10)
```

## Duck-typed custom iterable with unboxed int next()

```babble
def CounterIter = class (n : int) (
    def i : int = 0
    def hasNext = fn () : bool -> ( this.i < n )
    def next = fn mut () : int -> (
        def cur = this.i
        this.i += 1
        cur
    )
)
def Counter = record (n : int) (
    def iterator = fn () : CounterIter -> ( CounterIter(this.n) )
)
def s = 0
for v in Counter(5) then ( s += v )
assert(s == 10)
```

## Duck-typed custom iterator used directly without iterator()

```babble
def Countdown = class (n : int) (
    def cur : int = n
    def hasNext = fn () : bool -> ( this.cur > 0 )
    def next = fn mut () : int -> (
        def v = this.cur
        this.cur -= 1
        v
    )
)
def acc = 0
for v in Countdown(4) then ( acc = acc * 10 + v )
assert(acc == 4321)
```

## Per-iteration binding is fresh for closures

```babble
def fns = list of (fn () : int) ()
for v in (list of int).of(10, 20, 30) then (
    fns.add(fn () : int -> ( v ))
)
assert(fns.get(0)() == 10)
assert(fns.get(1)() == 20)
assert(fns.get(2)() == 30)
```

## Loop variable may shadow outer iterable name

```babble
def x = (list of int).of(1, 2, 3)
def sum = 0
for x in x then ( sum += x )
assert(sum == 6)
assert(x.size() == 3)
```

## Case 11

```babble
def sum = 0.0
for x : double in (list of int).of(1, 2, 3) then ( sum += x )
assert(sum == 6.0)
```

## Case 12

```babble
def r1 = for v in (list of int).of(1, 2, 3) then ( v * 2 )
def r2 = for v in (list of int).of(1, 2, 3) then (
    if v == 2 then ( break )
    v * 2
)
def r3 = for v in (list of int).of(1, 2, 3) then (
    if v == 2 then ( break 99 )
    v * 2
)
assert(r1 == 6)
assert(r2 == null)
assert(r3 == 99)
```

## Case 13

```babble
def i = 0
def sum = 0
while i < 6 then (
    i += 1
    if i == 3 then ( continue )
    if i == 5 then ( break )
    sum += i
)
def w = while true then (
    if true then ( break 2.5 )
    1
)
assert(sum == 7)
assert(w == 2.5)
```

## Case 14

```babble
def total = 0
for i in (list of int).of(1, 2, 3) then (
    for j in (list of int).of(10, 20, 30) then (
        if j == 20 then ( continue )
        if j == 30 then ( break )
        total += i * j
    )
)
assert(total == 60)
```

## Case 15

```babble
def sumFrozen = fn (xs : frozen list of int) : int -> (
    def s = 0
    for v in xs then ( s += v )
    s
)
def f = freeze(list(3, 4, 5))
assert(sumFrozen(f) == 12)
```

## memberName compatibility : `in` remains valid after `.`

```babble
assert(java.lang.System.in != null)
```

## Error : Type error : cannot iterate over nullable type list(int)?

```babble
def xs : (list of int)? = null
for v in xs then ( println(v) )
```

```error
Type error : cannot iterate over nullable type list(int)?
```

## string is not directly iterable (BIP013-D4)

```babble
for c in "hello" then ( println(c) )
```

```error
Not iterable : string
```

## map is not directly iterable (BIP013-D5)

```babble
def m = (map of (string, int)).of("a", 1)
for e in m then ( println(e) )
```

```error
Not iterable : map(string, int)
```

## Error : Cannot assign to loop variable : v

```babble
for v in (list of int).of(1, 2) then (
    v = 3
)
```

```error
Cannot assign to loop variable : v
```

## Error : Name already defined : v

```babble
for v in (list of int).of(1, 2) then (
    def v = 3
)
```

```error
Name already defined : v
```

## Error : break outside of a loop

```babble
break
```

```error
break outside of a loop
```

## Error : continue outside of a loop

```babble
continue
```

```error
continue outside of a loop
```

## break cannot cross a function boundary inside a loop

```babble
for v in (list of int).of(1, 2) then (
    def f = fn () -> ( break )
    f()
)
```

```error
break outside of a loop
```

## Mutating a collection while iterating over it in a pure/hermetic function (BIP013-D8)

```babble
def bad = fn (mut xs : list of int) -> (
    for v in xs then (
        xs.add(v)
    )
)
```

```error
Cannot mutate collection while iterating over it
```

## is narrows a for-loop variable (fresh read-only binding per iteration), also across and

```babble
def rs = list of double ()
def ks = list of int ()
for h in Items() then (
    if h is Sub and h.r > 1.0 then ( rs.add(h.r) )
    if not (h is Sub) then ( ks.add(h.k()) )
)
assert(rs == list.of(2.5))
assert(ks == list.of(2))
```

## Narrowing of a for-loop variable ends with its if

```babble
for h in Items() then (
    if h is Sub then ( assert(h.k() == 1) )
    discard(h.r)
)
```

```error
No such member : r
```

## Babble custom iterable returning a Java Iterator (BABBLE_TO_JAVA_ITERABLE) : normal, continue, and break

```babble
def Bag = class (def xs : list of int) (
    def iterator = fn () : java.util.Iterator of int -> ( this.xs.iterator() )
)
def all = 0
for v in Bag(list(1, 2, 3)) then ( all += v )
def stopped = for v in Bag(list(1, 2, 3, 4)) then (
    if v == 2 then ( continue )
    if v == 3 then ( break v * 10 )
    v
)
assert(all == 6 and stopped == 30)
```

## Custom Babble iterable supports continue and break with a value

```babble
def skipped = 0
for h in Items() then (
    if h.k() == 2 then ( continue )
    skipped += h.k()
)
def hit = for h in Items() then (
    if h.k() == 2 then ( break 77 )
    0
)
assert(skipped == 1 and hit == 77)
```

## Iterable intersection (`Iterable & Serializable`)

```babble
def sumInter = fn (xs : java.lang.Iterable of int & java.io.Serializable) : int -> (
    def s = 0 ; for v in xs then ( s += v ) ; s
)
assert(sumInter(java.util.ArrayList of int (list(2, 3))) == 5)
```

## Frozen bounded-TypeVar iterable

```babble
def sumBound of (T : java.lang.Iterable of int) = fn (xs : frozen T) : int -> (
    def s = 0 ; for v in xs then ( s += v ) ; s
)
assert(sumBound(freeze(java.util.ArrayList of int (list(4, 5)))) == 9)
```

## Union with a non-iterable branch is rejected

```babble
def bad = fn (x : list of int | int) -> ( for v in x then ( discard(v) ) )
```

```error
Not iterable : list(int) | int
```

## Babble class with wrong hasNext return type is not iterable

```babble
def BadIt = class () (
    def hasNext = fn () : int -> ( 0 )
    def next = fn () : int -> ( 0 )
)
for v in BadIt() then ( discard(v) )
```

```error
Not iterable : BadIt
```

## Babble class whose iterator() takes a required parameter is not iterable

```babble
def BadCol = class () ( def iterator = fn (k : int) : int -> ( k ) )
for v in BadCol() then ( discard(v) )
```

```error
Not iterable : BadCol
```
