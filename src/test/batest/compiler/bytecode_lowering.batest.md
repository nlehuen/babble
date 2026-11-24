# Compiler bytecode lowering edge cases : constant folding, conditions, widening, Java fields, and exception...

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
```

## Constant folding arithmetic and algebraic identities (x passed as parameter to prevent full -O1 folding)

```babble
def fold = fn (x : int) -> (
    assert(x + 0 == 5 and 0 + x == 5 and x - 0 == 5)
    assert(x * 0 == 0 and 0 * x == 0 and x * 1 == 5 and 1 * x == 5)
    assert((1 + 2) * (3 + 4) == 21 and -(10 - 3) == -7)
)
fold(5)
assert(10 - 3 == 7 and 4 * 5 == 20 and -(3) + 10 == 7 and -(2.5) == -2.5)
```

## evalIntConstant nested unary minus including 32-bit Integer.MIN_VALUE negation wrap, plus mixed widening

```babble
def x = 4
def z = (0 - x) + (10 * 2 + 1) + -(-3) + -(-2147483647 - 1) + -(0 - x)
def dm = 1 + 2.0
def dmin : double = -2147483648
assert(z == -2147483624)
assert(dm == 3.0)
assert(dmin == -2147483648)
```

## Out-of-range integer literal rejected cleanly

```babble
def n = 2147483648
```

```error
Integer out of range : 2147483648
```

## Boolean equality, not/or/and conditions, mixed comparisons, and parenthesized condition

```babble
def b1 = true ; def b2 = false ; def x = 8 ; def y = 9
assert((b1 == b2) == false and (b1 != b2) == true)
assert(if not (x < 5) then ( true ) else ( false ))
assert(if (x < 5) or (y < 5) then ( false ) else ( true ))
assert((1 == 1.0) and (2 != 3.0) and (b1 == true) and (b1 != false) and ((1 < 2)))
```

## Numeric truthiness in if conditions (int and double)

```babble
def check = fn (d1 : double, d0 : double, i1 : int, i0 : int) : string -> (
    def out = ""
    out += if d1 then ( "1" ) else ( "0" )
    out += if d0 then ( "1" ) else ( "0" )
    out += if i1 then ( "1" ) else ( "0" )
    out += if i0 then ( "1" ) else ( "0" )
    out
)
assert(check(1.5, 0.0, 2, 0) == "1010")
```

## Double comparisons in control flow

```babble
def checkD = fn (d : double) : int -> (
    def hits = 0
    if d < 3.0 then ( hits += 1 )
    if d <= 2.5 then ( hits += 1 )
    if d == 2.5 then ( hits += 1 )
    if d != 1.0 then ( hits += 1 )
    if d >= 2.5 then ( hits += 1 )
    if d > 2.0 then ( hits += 1 )
    hits
)
assert(checkD(2.5) == 6)
```

## Int condition lowering (==, !=, and, or) across true and false branches

```babble
def checkI = fn (a : int, b : int) : string -> (
    def o = ""
    o += if a == b then ( "1" ) else ( "0" )
    o += if a != b then ( "1" ) else ( "0" )
    o += if a > 0 and b > 0 then ( "1" ) else ( "0" )
    o += if a < 0 or b < 0 then ( "1" ) else ( "0" )
    o
)
assert(checkI(2, 2) == "1010" and checkI(2, 3) == "0110")
```

## Double condition lowering (==, !=, <, <=, >, >=) across true and false branches

```babble
def chk = fn (a: double, b: double): string -> (
    def o = ""
    o += if a == b then ("1") else ("0")
    o += if a != b then ("1") else ("0")
    o += if a < b then ("1") else ("0")
    o += if a <= b then ("1") else ("0")
    o += if a > b then ("1") else ("0")
    o += if a >= b then ("1") else ("0")
    o
)
assert(chk(1.0, 2.0) == "011100" and chk(2.0, 1.0) == "010011")
```

## Unary negation on int, double, and int | double union

```babble
def neg = fn (a : int, b : double, c : int | double) -> (
    assert(-a == -4)
    assert(-b == -1.5)
    assert(-c == -9)
)
neg(4, 1.5, 9)
```

## Widening return, as-cast, block/while double widening, and methodless object

```babble
def wf1 = fn () : double -> ( 1 )
def wf2 = fn (flag : bool) : double -> ( if flag then ( 1 ) else ( 2.5 ) )
def k = 0
def wd : double? = while k < 2 then ( k += 1 ; 42 )
def bd : double = ( def t = 3 ; t )
def obj = object ( def k : int = 7 )
assert(wf1() == 1.0 and wf2(true) == 1.0 and (1 as double) == 1.0 and ((1 + 2) as double) == 3.0)
assert(wd == 42.0 and bd == 3.0 and obj.k == 7)
```

## Widening assignments on local and Babble int field

```babble
def x = 1
def b = (class () ( def v : int = 2 ))()
def d1 : double = (x = 5) ; def d2 : double = (x += 3)
def d3 : double = (b.v = 7) ; def d4 : double = (b.v += 2)
def d5 : double = (b.v -= 1) ; def d6 : double = (b.v *= 2)
assert(d1 == 5.0 and d2 == 8.0 and d3 == 7.0 and d4 == 9.0 and d5 == 8.0 and d6 == 16.0)
```

## Widening assignments on Java int field

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("a"))
def d7 : double = (st.ttype = 4)
def d8 : double = (st.ttype += 2)
assert(d7 == 4.0 and d8 == 6.0)
```

## Boxed/fallback compound assignments on union local and instance fields

```babble
def C = class (def n : int, def d : double) (
    def run = fn mut () -> (
        def loc : int | double = 9
        loc /= 2 ; loc += dynNum(2)
        this.n *= 2 ; this.n -= 1
        this.d /= 2 ; this.d += dynNum(2)
        assert(loc == 6.5 and this.n == 17 and this.d == 5.0)
    )
)
C(9, 6.0).run()
```

## Boxed/fallback compound assignment on static union field

```babble
def StaticBox = class () () static (
    def sx : int | double = 9
    def go = fn io () : int | double -> ( this.sx /= 2 ; this.sx )
)
assert(StaticBox.go() == 4.5)
```

## Java long/float arg lowering, paren-wrapped static chain, and static field on non-pure Java receiver

```babble
def mkSt = fn io () : java.io.StreamTokenizer -> ( java.io.StreamTokenizer(java.io.StringReader("hi")) )
assert(java.lang.Long.sum(3, 4) == 7)
assert(java.lang.Float.sum(3, 4) == 7.0)
assert((java.lang.Math).abs(-5) == 5)
assert((java.lang.Math).abs(-9) == 9)
assert(mkSt().TT_EOF == -1)
```

## Java int and float instance field assign and compound assign

```babble
def pf = java.awt.geom.Point2D.Float()
pf.x = java.lang.Float.valueOf(3)
pf.y = java.lang.Float.valueOf(4)
def pt = java.awt.Point(1, 2)
pt.x = 5 ; pt.x += 3 ; pt.x *= 2 ; pt.x -= 4 ; pt.y += 2 ; pt.y *= 3
def rect = java.awt.Rectangle()
rect.width = 10 ; rect.width += 5
assert(pf.x == 3.0 and pf.y == 4.0 and pt.x == 12 and pt.y == 12 and rect.width == 15)
```

## Java double instance field assign and compound assign (int, double, and union RHS)

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("x"))
st.nval = 3 ; st.nval += 2 ; st.nval -= 1.5 ; st.nval *= 2.0 ; st.nval /= 7 ; st.nval += dynNum(2)
st.ttype = 10 ; st.ttype += 5 ; st.ttype -= 3 ; st.ttype *= 2
assert(st.nval == 3.0 and st.ttype == 24)
```

## Java Point2D.Double and Rectangle2D.Double compound ops

```babble
def r = java.awt.geom.Rectangle2D.Double(1.0, 2.0, 3.0, 4.0)
r.x += 2.0 ; r.y -= 0.5 ; r.width *= 2.0 ; r.height /= 2.0
def pd = java.awt.geom.Point2D.Double(10.0, 20.0)
pd.x += 2.0 ; pd.x -= 4.0 ; pd.x *= 3.0 ; pd.x /= 2.0
pd.y = 5
assert(r.x == 3.0 and r.y == 1.5 and r.width == 6.0 and r.height == 2.0)
assert(pd.x == 12.0 and pd.y == 5.0)
```

## Java String and typed Object field assign and compound assign

```babble
def demo = import("org.babblelang.tests").JavaClassDemo()
demo.nonNullLabel = "a" ; demo.nonNullLabel += "b"
def gbc = java.awt.GridBagConstraints()
gbc.insets = java.awt.Insets(1, 2, 3, 4)
gbc.weightx = 2.0 ; gbc.weightx += 1
assert(demo.nonNullLabel == "ab" and gbc.insets!.top == 1 and gbc.weightx == 3.0)
```

## Java untyped Object field assign (String and boxed int)

```babble
def ev = java.awt.Event(null, 0, null)
ev.arg = "ok"
def s = ev.arg
ev.arg = 42
assert(s == "ok" and ev.arg == 42)
```

## Static Java field assign and compound assign

```babble
def HF = import("org.babblelang.tests").EffectsTestCase.HostFixtures
def saved = HF.mutableStatic
HF.mutableStatic = 10
HF.mutableStatic += 5
HF.mutableStatic -= 3
HF.mutableStatic *= 2
def after = HF.mutableStatic
HF.mutableStatic = saved
assert(after == 24)
```

## emitJavaMethodCall exception catch handler

```babble
java.lang.Integer.parseInt("bad_num")
```

```error
java.lang.NumberFormatException : For input string: "bad_num"
```

## emitJavaConstructorCall exception catch handler

```babble
java.io.FileReader("nonexistent_batest_file_xyz.txt")
```

```error
java.io.FileNotFoundException : nonexistent_batest_file_xyz.txt (<OS error>)
```
