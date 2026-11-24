# 9-scalar type system : contextual literals, conversions, JLS promotion, widening, char, string, is/as

References : BIP011 (§4.1-4.5, BIP011-D2..D5)

## Case 1

```babble
def b : byte = 127
def bMin : byte = -128
def s : short = 32767
def sMin : short = -32768
assert(classof(b) == java.lang.Byte and b == 127)
assert(classof(bMin) == java.lang.Byte and bMin == -128)
assert(classof(s) == java.lang.Short and s == 32767)
assert(classof(sMin) == java.lang.Short and sMin == -32768)
```

## Case 2

```babble
def i : int = 2147483647
def iMin : int = -2147483648
assert(classof(i) == java.lang.Integer and i == 2147483647)
assert(classof(iMin) == java.lang.Integer and iMin == -2147483648)
```

## Case 3

```babble
def l : long = 9223372036854775807L
def lMin : long = -9223372036854775808L
def lCtx : long = 9223372036854775807
def lCtxMin : long = -9223372036854775808
assert(classof(l) == java.lang.Long and lCtx == l)
assert(classof(lMin) == java.lang.Long and lCtxMin == lMin)
```

## Case 4

```babble
def f : float = 1.5f
def fCtx : float = 1.5
def d : double = 2.5
assert(classof(f) == java.lang.Float and classof(fCtx) == java.lang.Float and f == fCtx)
assert(classof(d) == java.lang.Double and d == 2.5)
```

## Case 5

```babble
def flag : bool = true
def ch : char = 'Z'
def str : string = "hello"
assert(classof(flag) == java.lang.Boolean and flag)
assert(classof(ch) == java.lang.Character and ch == 'Z')
assert(classof(str) == java.lang.String and str == "hello")
```

## Case 6

```babble
def b : byte = 127
def s : short = 32767
def i : int = 42
def l : long = 99L
def f : float = 1.5f
def d : double = 2.5
def jb : java.lang.Byte = b
def js : java.lang.Short = s
def ji : java.lang.Integer = i
def jl : java.lang.Long = l
def jf : java.lang.Float = f
def jd : java.lang.Double = d
assert(jb == b and js == s and ji == i and jl == l and jf == f and jd == d)
```

## Case 7

```babble
def flag : bool = true
def ch : char = 'Z'
def str : string = "hello"
def jbool : java.lang.Boolean = flag
def jc : java.lang.Character = ch
def jstr : java.lang.String = str
assert(jbool == flag and jc == ch and jstr == str)
```

## Error : Out of byte range : 128

```babble
def b : byte = 128
```

```error
Out of byte range : 128
```

## Error : Out of byte range : -129

```babble
def b : byte = -129
```

```error
Out of byte range : -129
```

## Error : Out of short range : 32768

```babble
def s : short = 32768
```

```error
Out of short range : 32768
```

## Explicit double suffix cannot contextually narrow to float

```babble
def f : float = 1.5d
```

```error
Type error : expected float, got double
```

## Case 12

```babble
def b : byte = byte(42)
def s : short = short(1000)
def i : int = int(3.9)
def l : long = long("9000000000")
assert(classof(b) == java.lang.Byte and b == 42)
assert(classof(s) == java.lang.Short and s == 1000)
assert(classof(i) == java.lang.Integer and i == 3 and int('A') == 65)
assert(classof(l) == java.lang.Long and l == 9000000000L)
```

## Case 13

```babble
def f : float = float("2.5")
def d : double = double(7L)
assert(classof(f) == java.lang.Float and f == 2.5f)
assert(classof(d) == java.lang.Double and d == 7.0)
```

## Case 14

```babble
def t : bool = bool("true")
def f : bool = bool("false")
def c1 : char = char(65)
def c2 : char = char("Z")
def s1 : string = string(123L)
def s2 : string = string('!')
assert(t and not f)
assert(c1 == 'A' and c2 == 'Z')
assert(s1 == "123" and s2 == "!")
```

## Inputs flow through parameters to prevent -O1 constant folding of runtime promotion

```babble
def check = fn (b1 : byte, b2 : byte, s1 : short, s2 : short) -> (
    def rBB = b1 + b2; def rSS = s1 + s2
    assert(classof(rBB) == java.lang.Integer and rBB == 30)
    assert(classof(rSS) == java.lang.Integer and rSS == 300)
)
check(byte(10), byte(20), short(100), short(200))
```

## Inputs flow through parameters to prevent -O1 constant folding of runtime promotion (2)

```babble
def check = fn (i1 : int, l1 : long, f1 : float, d1 : double) -> (
    def rIL = i1 + l1; def rLF = l1 + f1; def rFD = f1 + d1
    assert(classof(rIL) == java.lang.Long and rIL == 15L)
    assert(classof(rLF) == java.lang.Float and rLF == 15.0f)
    assert(classof(rFD) == java.lang.Double and rFD == 7.0)
)
check(5, 10L, 5.0f, 2.0)
```

## Subtlety (BIP011-D4) : / preserves float only when neither operand is long or double

```babble
def check = fn (i1 : int, l1 : long, f1 : float) -> (
    def rFI = f1 / 2; def rFF = f1 / 2.0f; def rII = i1 / 2; def rLF = l1 / 2.0f
    assert(classof(rFI) == java.lang.Float and rFI == 2.5f)
    assert(classof(rFF) == java.lang.Float and rFF == 2.5f)
    assert(classof(rII) == java.lang.Double and rII == 2.5)
    assert(classof(rLF) == java.lang.Double and rLF == 5.0)
)
check(5, 10L, 5.0f)
```

## Inputs flow through parameters to prevent -O1 constant folding of unary minus promotion

```babble
def check = fn (b1 : byte, l1 : long, f1 : float) -> (
    def nb = -b1; def nl = -l1; def nf = -f1
    assert(classof(nb) == java.lang.Integer and nb == -10)
    assert(classof(nl) == java.lang.Long and nl == -10L)
    assert(classof(nf) == java.lang.Float and nf == -5.0f)
)
check(byte(10), 10L, 5.0f)
```

## Case 19

```babble
def b : byte = 7
def s : short = b
def i : int = s
def l : long = i
def f : float = l
def d : double = f
assert(classof(b) == java.lang.Byte and classof(s) == java.lang.Short)
assert(classof(i) == java.lang.Integer and classof(l) == java.lang.Long)
assert(classof(f) == java.lang.Float and classof(d) == java.lang.Double and d == 7.0)
```

## Case 20

```babble
def b : byte = 7
def pick = fn (c : bool) -> ( if c then ( 1 ) else ( 2L ) )
def picked = pick(true)
assert(classof(picked) == java.lang.Long and picked == 1L)
def takeLong = fn (x : long) : long -> ( x + 1L )
assert(classof(takeLong(b)) == java.lang.Long and takeLong(b) == 8L)
```

## Case 21

```babble
def a : char = 'a'
def z : char = 'z'
assert(a < z and a == 'a' and z > a and a <= a and z >= a)
```

## char is strictly non-numeric

```babble
def x = 'a' + 1
```

```error
Not a number : 'a'
```

## Case 23

```babble
def s : string = "hello"
def opt : string? = "world"
def empty : string? = null
def cs : java.lang.CharSequence = s
def cmp : java.lang.Comparable of string = s
assert(s.length() == 5 and s.toUpperCase() == "HELLO")
assert(opt!.toUpperCase() == "WORLD" and empty?.toUpperCase() == null)
assert(cs.length() == 5 and cmp.compareTo("hello") == 0)
```

## Memberless primitives : long has no members

```babble
def x = (1L).toString()
```

```error
Not a scope : (1L)
```

## Memberless primitives : char has no members

```babble
def x = 'a'.toString()
```

```error
Not a scope : 'a'
```

## Case 26

```babble
def dyn = fn (v : any?) : any? -> ( v )
assert(dyn(byte(1)) is byte and dyn(short(2)) is short and dyn(3L) is long)
assert(dyn(1.5f) is float and dyn('x') is char)
def l : long = dyn(5) as long
def f : float = dyn(5) as float
assert(classof(l) == java.lang.Long and l == 5L)
assert(classof(f) == java.lang.Float and f == 5.0f)
```

## Case 27

```babble
def u12: byte | char | string = "12"
def uA: byte | char | string = 'A'
def uB4: byte | char | string = byte(4)
def u300: short | char | string = "300"
def uB: short | char | string = 'B'
def uS5: short | char | string = short(5)
assert(byte(u12) == byte(12) and byte(uA) == byte(65) and byte(uB4) == byte(4))
assert(short(u300) == short(300) and short(uB) == short(66) and short(uS5) == short(5))
```

## Case 28

```babble
def u9000: long | char | string = "9000"
def uC: long | char | string = 'C'
def uL6: long | char | string = 6L
assert(long(u9000) == 9000L and long(uC) == 67L and long(uL6) == 6L)
```

## Case 29

```babble
def u15: float | string = "1.5"
def uF25: float | string = 2.5f
def uTrue: bool | string = "true"
def uFalse: bool | string = false
def u68: int | char = 68
def uZ: int | char = 'Z'
assert(float(u15) == 1.5f and float(uF25) == 2.5f)
assert(bool(uTrue) and not bool(uFalse))
assert(char(u68) == 'D' and char(uZ) == 'Z')
```

## Case 30

```babble
def n10d: double | string = 10.0
def n10l: long | string = 10L
def n10s: short | string = short(10)
def n10i: int | string = 10
assert(byte(n10d) == byte(10) and byte(n10l) == byte(10) and byte(n10s) == byte(10))
assert(short(n10d) == short(10) and short(n10l) == short(10))
assert(long(n10d) == 10L and float(n10i) == 10.0f)
```
