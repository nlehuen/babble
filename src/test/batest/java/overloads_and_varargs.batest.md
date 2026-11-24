# Overloaded Java methods, varargs dispatch, numeric widening, and overload errors

References : BIP008, BIP020, BIP021-D8 (Java varargs Phase 3), Overloads, JavaLinker

```babble preamble
def Demo = import("org.babblelang.tests").JavaClassDemo
```

## Case 1

```babble
def sb = java.lang.StringBuilder()
sb.append(true).append(":").append(42).append(":").append(1.5)
assert(sb.toString() == "true:42:1.5")
assert(java.lang.Math.abs(-5) == 5)
assert(java.lang.Math.abs(-2.5) == 2.5)
assert(java.lang.String.valueOf(true) == "true")
assert(java.lang.String.valueOf(99) == "99")
```

## Case 2

```babble
def empty: java.util.List of string = java.util.List.of()
def three = java.util.List.of("a", "b", "c")
def fromArr = java.util.Arrays.asList(java.util.List.of("x", "y").toArray())
assert(empty.size() == 0)
assert(three.size() == 3)
assert(three.get(1) == "b")
assert(fromArr.size() == 2)
assert(fromArr.get(0) == "x")
```

## Case 3

```babble
assert(java.util.Arrays.asList("only").toString() == "[only]")
assert(java.util.Arrays.asList("a", "b", "c").toString() == "[a, b, c]")
assert(java.util.Arrays.asList(1, 2, 3).toString() == "[1, 2, 3]")
def empty: java.util.List of string = java.util.Arrays.asList()
assert(empty.size() == 0)
```

## Error : Missing type : cannot infer type arguments for java.util.Arrays.asList

```babble
def n = java.util.Arrays.asList().size()
```

```error
Missing type : cannot infer type arguments for java.util.Arrays.asList
```

## Case 5

```babble
assert(java.lang.String.format("%s=%d", "x", 42) == "x=42")
assert(java.lang.String.format("%s:%s", null, "ok") == "null:ok")
assert(java.lang.String.format("plain") == "plain")
def big = java.util.List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
assert(big.size() == 12)
assert(big.get(11) == 12)
```

## Case 6

```babble
def p = java.nio.file.Path.of("a", "b", "c")
assert(p.getNameCount() == 3)
assert(p.getName(0).toString() == "a")
assert(p.getName(2).toString() == "c")
assert(java.nio.file.Path.of("solo").toString() == "solo")
assert(java.util.stream.IntStream.of(10, 20, 30).sum() == 60)
assert(java.util.stream.IntStream.of().sum() == 0)
```

## Case 7

```babble
def Day = java.time.DayOfWeek
def days = java.util.EnumSet.of(
    Day.MONDAY, Day.TUESDAY, Day.WEDNESDAY,
    Day.THURSDAY, Day.FRIDAY, Day.SATURDAY
)
assert(days.size() == 6)
assert(days.contains(Day.SATURDAY))
```

## JLS : primitive array passed to Object... wraps as 1 element; reference array passes directly

```babble
def ints = array.of(1, 2, 3)
def wrapped: java.util.List of array of int = java.util.Arrays.asList(ints)
assert(wrapped.size() == 1)
assert(wrapped.get(0).size() == 3)
def strs = array.of("p", "q")
def direct: java.util.List of string = java.util.Arrays.asList(strs)
assert(direct.size() == 2)
assert(direct.get(1) == "q")
```

## Case 9

```babble
assert(Demo.VarArgsCtor("empty").summary == "empty:")
assert(Demo.VarArgsCtor("nums", 1, 2, 3).summary == "nums:1,2,3")
assert(Demo.vaPick("one") == "fixed:one")
assert(Demo.vaPick("a", "b") == "String...:2")
assert(Demo.vaPick("a", 2) == "Object...:2")
assert(Demo.vaPick() == "String...:0")
```

## Case 10

```babble
assert(Demo.vaSumDoubles(1, 2.5, 3) == 6.5)
def total = Demo.vaRunAll(
    fn (x: int): int -> ( x + 1 ),
    fn (x: int): int -> ( x * 10 )
)
assert(total == 20)
```

## JLS §15.12.2.5 : vaAmbig(int, int...) vs vaAmbig(int...)

```babble
Demo.vaAmbig(1, 2)
```

```error
Ambiguous call to org.babblelang.tests.JavaClassDemo.vaAmbig for (int, int)
```

## Error : Ambiguous call to java.lang.StringBuilder.append for (null)

```babble
def sb = java.lang.StringBuilder()
sb.append(null)
```

```error
Ambiguous call to java.lang.StringBuilder.append for (null)
```

## Error : No applicable overload of java.lang.Integer.parseInt for (bool)

```babble
def n = java.lang.Integer.parseInt(true)
```

```error
No applicable overload of java.lang.Integer.parseInt for (bool)
```

## BIP008 : member access on an any-typed Java receiver is rejected statically

```babble
def dyn = fn (v: any): any -> ( v )
def obj = dyn(java.lang.StringBuilder("x"))
def ok = obj.toString()
```

```error
Not a scope : obj
```

## Case 15

```babble
def fmt2: fn io (f: string, a: string, b: int): string = java.lang.String.format
def fmt0: fn io (f: string): string = java.lang.String.format
assert(fmt2("%s=%d", "n", 7) == "n=7")
assert(fmt0("plain") == "plain")
```

## Case 16

```babble
def pickStr: fn io (a: string, b: string): string = Demo.vaPick
def pickObj: fn io (a: string, b: int): string = Demo.vaPick
def sumDbl: fn io (a: double, b: double, c: double): double = Demo.vaSumDoubles
assert(pickStr("a", "b") == "String...:2")
assert(pickObj("a", 1) == "Object...:2")
assert(sumDbl(3, 4.5, 5) == 12.5)
```

## Case 17

```babble
def fmtNull: fn io (f: string, a: any?): string = java.lang.String.format
def pick0: fn io (): string = Demo.vaPick
def pick1: fn io (a: string): string = Demo.vaPick
def sum0: fn io (): double = Demo.vaSumDoubles
assert(fmtNull("%s", null) == "null")
assert(pick0() == "String...:0")
assert(pick1("hi") == "fixed:hi")
assert(sum0() == 0.0)
```

## Case 18

```babble
def xs = list of string ()
def addAll: fn io (shared c: list of string, a: string, b: string): bool = java.util.Collections.addAll
addAll(xs, "x", "y")
assert(xs.size() == 2)
assert(xs.get(0) == "x")
assert(xs.get(1) == "y")
```

## Error : No applicable overload of java.util.Collections.addAll for () : candi...

```babble
def addBad: fn io (): bool = java.util.Collections.addAll
addBad()
```

```error
No applicable overload of java.util.Collections.addAll for () : candidates are (java.util.Collection, java.lang.Object[])
```
