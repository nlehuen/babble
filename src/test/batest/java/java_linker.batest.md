# JavaLinker inline caches, bound/unbound Java methods, safe navigation, fields, and primitive/boxed returns

References : BIP001 (compiler & Bootstrap), BIP011 (core types)
Targets : Bootstrap, JavaLinker, JavaClass, Overloads

## Case 1

```babble
def absFn = java.lang.Math.abs
def parseFn = java.lang.Integer.parseInt
def eqFn = java.util.Objects.equals
assert(absFn(-7) == 7)
assert(absFn(-2.5) == 2.5)
assert(parseFn("42") == 42)
assert(eqFn(null, null))
assert(not eqFn("a", null))
```

## Case 2

```babble
def sb = java.lang.StringBuilder("hi")
def app = sb.append
app("!")
assert(sb.toString() == "hi!")
def rawList = java.util.List.of("a", "b", "c")
def rawGet = rawList.get
assert(rawGet(1) == "b")
def xs : java.util.List of any = java.util.ArrayList()
def add = xs.add
add(sb)
assert(xs.size() == 1)
```

## Case 3

```babble
def intVal = java.lang.Integer.valueOf
def dblVal = java.lang.Double.valueOf
def boolVal = java.lang.Boolean.valueOf
assert(intVal(99) == 99)
assert(dblVal(1.5) == 1.5)
assert(boolVal(true))
```

## Case 4

```babble
def sb : java.lang.StringBuilder? = java.lang.StringBuilder("ab")
def noneSb : java.lang.StringBuilder? = null
def raw : (java.util.List of int)? = java.util.List.of(10, 20)
def noneRaw : (java.util.List of int)? = null
assert(sb?.append("c")?.toString() == "abc")
assert(noneSb?.toString() == null)
assert(raw?.get(1) == 20)
assert(noneRaw?.size() == null)
```

## Case 5

```babble
def Box = class (def n : int) ()
def xs : java.util.List of any = java.util.ArrayList()
def add = xs.add
add(Box(1))
assert(xs.size() == 1)
```

## Megamorphic JavaLinker select & call across 6 distinct List implementations

```babble
def L = type java.util.List of int
def cols : list of L = list.of(
    (java.util.ArrayList of int)(), (java.util.LinkedList of int)(),
    (java.util.Vector of int)(), (java.util.Stack of int)(),
    java.util.List.of(1, 2), java.util.Collections.singletonList(9)
)
def sum = 0
for p in list.of(0, 1) then (
    for c in cols then ( def o : L? = c; sum += o?.size()! )
)
assert(sum == 6)
```

## Error : java.lang.NumberFormatException : For input string: "bad"

```babble
def parseFn = java.lang.Integer.parseInt
parseFn("bad")
```

```error
java.lang.NumberFormatException : For input string: "bad"
```

## Error : No applicable overload of java.lang.StringBuilder.append for ()

```babble
def sb = java.lang.StringBuilder("x")
def app = sb.append
app()
```

```error
No applicable overload of java.lang.StringBuilder.append for ()
```

## Case 9

```babble
def frames = java.lang.Thread.currentThread().getStackTrace()
assert(frames.size() > 0)
assert(java.lang.Byte.parseByte("7") == 7)
assert(java.lang.Short.parseShort("12") == 12)
assert(java.lang.Character.valueOf(java.lang.StringBuilder("abc").charAt(1)) == 'b')
assert(java.lang.Long.parseLong("9000000000") == 9000000000L)
assert(java.lang.Float.parseFloat("2.5") == 2.5f)
```

## Case 10

```babble
def arr : java.util.List of int = java.util.ArrayList()
arr.add(1)
arr.clear()
assert(arr.isEmpty())
assert(java.lang.Integer.valueOf(5) + 1 == 6)
assert(java.lang.Double.valueOf(2.5) + 0.5 == 3.0)
assert(java.lang.Boolean.valueOf(true))
```

## Error : Type error : expected int, got null

```babble
def x : int = java.lang.Integer.getInteger("babble.nonexistent.prop")!
```

```error
Type error : expected int, got null
```

## Case 12

```babble
def p2d = java.awt.geom.Point2D.Double(1.0, 2.0)
p2d.x = 3
p2d.x += 1.5
p2d.y *= 2.0
p2d.y /= 2.0
p2d.x -= 0.5
assert(p2d.x == 4.0 and p2d.y == 2.0)
def p2f = java.awt.geom.Point2D.Float(java.lang.Float.parseFloat("1"), java.lang.Float.parseFloat("2"))
p2f.x = java.lang.Float.parseFloat("5")
assert(p2f.x == 5.0f)
```

## Case 13

```babble
def ev = java.awt.Event("src", java.lang.Long.parseLong("10"), 1, 2, 3, 4, 5, "init")
ev.when = java.lang.Long.parseLong("200")
ev.arg = "updated"
ev.x += 10
ev.x -= 3
ev.x *= 2
assert(ev.when == 200L and ev.arg == "updated" and ev.x == 18)
def st = java.io.StreamTokenizer(java.io.StringReader("hi"))
st.sval = "custom"
st.nval = 7.5
st.nval += 2.5
assert(st.sval == "custom" and st.nval == 10.0)
```

## Case 14

```babble
def sw = java.io.StringWriter()
print("hello", " world", to: sw)
assert(sw.toString() == "hello world")
def sw2 = java.io.StringWriter()
println("line1", to: sw2)
print("line2", to: sw2)
assert(sw2.getBuffer().length() > 5)
```

## Case 15

```babble
def C = class (def x : int) (
    def m = fn () : int -> ( this.x )
)
def c = C(5)
def m1 = c.m
def m2 = c.m
def uniq : java.util.Set of any = java.util.HashSet()
uniq.add(m1)
assert(m1 == m2 and uniq.contains(m2))
assert(string(m1) == "bound function m")
assert(classof(c) == C)
assert(classof(java.lang.StringBuilder()) == java.lang.StringBuilder)
```

## Case 16

```babble
def pf = java.awt.geom.Point2D.Float(3, 4)
pf.x = java.lang.Float.parseFloat("5")
def ev = java.awt.Event("s", java.lang.Long.numberOfLeadingZeros(8), 1, 2, 3, 4, 5, "a")
def demo = import("org.babblelang.tests").JavaClassDemo()
demo.nonNullLabel = "go"
demo.nonNullLabel += "!"
assert(pf.x == 5.0f and ev.when > 0 and demo.nonNullLabel == "go!")
```
