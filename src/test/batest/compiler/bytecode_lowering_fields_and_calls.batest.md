# Bytecode Lowering : Java Fields, Catch Handlers, and High-Arity Calls

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble
;; babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
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

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def pf (jnew Float))
  (set-jfield x name(pf) (jcall Float.valueOf (widen[float] 3:int)))
  (set-jfield y name(pf) (jcall Float.valueOf (widen[float] 4:int)))
  (def pt (jnew Point 1:int 2:int))
  (set-jfield x name(pt) 5:int)
  (set-jfield x name(pt) 3:int)
  (set-jfield x name(pt) 2:int)
  (set-jfield x name(pt) 4:int)
  (set-jfield y name(pt) 2:int)
  (set-jfield y name(pt) 3:int)
  (def rect (jnew Rectangle))
  (set-jfield width name(rect) 10:int)
  (set-jfield width name(rect) 5:int)
  (call name(assert) (and (and (and (and (==:bool (convert[double] (get-jfield Float.x name(pf))) 3.0:double) (==:bool (convert[double] (get-jfield Float.y name(pf))) 4.0:double)) (==:bool (get-jfield Point.x name(pt)) 12:int)) (==:bool (get-jfield Point.y name(pt)) 12:int)) (==:bool (get-jfield Rectangle.width name(rect)) 15:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def pf (jnew Float))
  (set-jfield x name(pf) (jcall Float.valueOf 3.0:float))
  (set-jfield y name(pf) (jcall Float.valueOf 4.0:float))
  (def pt (jnew Point 1:int 2:int))
  (set-jfield x name(pt) 5:int)
  (set-jfield x name(pt) 3:int)
  (set-jfield x name(pt) 2:int)
  (set-jfield x name(pt) 4:int)
  (set-jfield y name(pt) 2:int)
  (set-jfield y name(pt) 3:int)
  (def rect (jnew Rectangle))
  (set-jfield width name(rect) 10:int)
  (set-jfield width name(rect) 5:int)
  (call name(assert) (and (and (and (and (==:bool (convert[double] (get-jfield Float.x name(pf))) 3.0:double) (==:bool (convert[double] (get-jfield Float.y name(pf))) 4.0:double)) (==:bool (get-jfield Point.x name(pt)) 12:int)) (==:bool (get-jfield Point.y name(pt)) 12:int)) (==:bool (get-jfield Rectangle.width name(rect)) 15:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def pf (jnew Float))
  (set-jfield x name(pf) (jcall Float.valueOf 3.0:float))
  (set-jfield y name(pf) (jcall Float.valueOf 4.0:float))
  (def pt (jnew Point 1:int 2:int))
  (set-jfield x name(pt) 5:int)
  (set-jfield x name(pt) 3:int)
  (set-jfield x name(pt) 2:int)
  (set-jfield x name(pt) 4:int)
  (set-jfield y name(pt) 2:int)
  (set-jfield y name(pt) 3:int)
  (def rect (jnew Rectangle))
  (set-jfield width name(rect) 10:int)
  (set-jfield width name(rect) 5:int)
  (call name(assert) (and (and (and (and (==:bool (convert[double] (get-jfield Float.x name(pf))) 3.0:double) (==:bool (convert[double] (get-jfield Float.y name(pf))) 4.0:double)) (==:bool (get-jfield Point.x name(pt)) 12:int)) (==:bool (get-jfield Point.y name(pt)) 12:int)) (==:bool (get-jfield Rectangle.width name(rect)) 15:int)))
```

## Java double instance field assign and compound assign (int, double, and union RHS)

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("x"))
st.nval = 3 ; st.nval += 2 ; st.nval -= 1.5 ; st.nval *= 2.0 ; st.nval /= 7 ; st.nval += dynNum(2)
st.ttype = 10 ; st.ttype += 5 ; st.ttype -= 3 ; st.ttype *= 2
assert(st.nval == 3.0 and st.ttype == 24)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "x")))
  (set-jfield nval name(st) (widen[double] 3:int))
  (set-jfield nval name(st) 2:int)
  (set-jfield nval name(st) 1.5:double)
  (set-jfield nval name(st) 2.0:double)
  (set-jfield nval name(st) 7:int)
  (set-jfield nval name(st) (call-fn dynNum (box 2:int)))
  (set-jfield ttype name(st) 10:int)
  (set-jfield ttype name(st) 5:int)
  (set-jfield ttype name(st) 3:int)
  (set-jfield ttype name(st) 2:int)
  (call name(assert) (and (==:bool (get-jfield StreamTokenizer.nval name(st)) 3.0:double) (==:bool (get-jfield StreamTokenizer.ttype name(st)) 24:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "x")))
  (set-jfield nval name(st) 3.0:double)
  (set-jfield nval name(st) 2:int)
  (set-jfield nval name(st) 1.5:double)
  (set-jfield nval name(st) 2.0:double)
  (set-jfield nval name(st) 7:int)
  (set-jfield nval name(st) (block
      (def v (box 2:int))
      var(v)))
  (set-jfield ttype name(st) 10:int)
  (set-jfield ttype name(st) 5:int)
  (set-jfield ttype name(st) 3:int)
  (set-jfield ttype name(st) 2:int)
  (call name(assert) (and (==:bool (get-jfield StreamTokenizer.nval name(st)) 3.0:double) (==:bool (get-jfield StreamTokenizer.ttype name(st)) 24:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "x")))
  (set-jfield nval name(st) 3.0:double)
  (set-jfield nval name(st) 2:int)
  (set-jfield nval name(st) 1.5:double)
  (set-jfield nval name(st) 2.0:double)
  (set-jfield nval name(st) 7:int)
  (set-jfield nval name(st) (block
      (def v (box 2:int))
      var(v)))
  (set-jfield ttype name(st) 10:int)
  (set-jfield ttype name(st) 5:int)
  (set-jfield ttype name(st) 3:int)
  (set-jfield ttype name(st) 2:int)
  (call name(assert) (and (==:bool (get-jfield StreamTokenizer.nval name(st)) 3.0:double) (==:bool (get-jfield StreamTokenizer.ttype name(st)) 24:int)))
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

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def r (jnew Double 1.0:double 2.0:double 3.0:double 4.0:double))
  (set-jfield x name(r) 2.0:double)
  (set-jfield y name(r) 0.5:double)
  (set-jfield width name(r) 2.0:double)
  (set-jfield height name(r) 2.0:double)
  (def pd (jnew Double 10.0:double 20.0:double))
  (set-jfield x name(pd) 2.0:double)
  (set-jfield x name(pd) 4.0:double)
  (set-jfield x name(pd) 3.0:double)
  (set-jfield x name(pd) 2.0:double)
  (set-jfield y name(pd) (widen[double] 5:int))
  (call name(assert) (and (and (and (==:bool (get-jfield Double.x name(r)) 3.0:double) (==:bool (get-jfield Double.y name(r)) 1.5:double)) (==:bool (get-jfield Double.width name(r)) 6.0:double)) (==:bool (get-jfield Double.height name(r)) 2.0:double)))
  (call name(assert) (and (==:bool (get-jfield Double.x name(pd)) 12.0:double) (==:bool (get-jfield Double.y name(pd)) 5.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def r (jnew Double 1.0:double 2.0:double 3.0:double 4.0:double))
  (set-jfield x name(r) 2.0:double)
  (set-jfield y name(r) 0.5:double)
  (set-jfield width name(r) 2.0:double)
  (set-jfield height name(r) 2.0:double)
  (def pd (jnew Double 10.0:double 20.0:double))
  (set-jfield x name(pd) 2.0:double)
  (set-jfield x name(pd) 4.0:double)
  (set-jfield x name(pd) 3.0:double)
  (set-jfield x name(pd) 2.0:double)
  (set-jfield y name(pd) 5.0:double)
  (call name(assert) (and (and (and (==:bool (get-jfield Double.x name(r)) 3.0:double) (==:bool (get-jfield Double.y name(r)) 1.5:double)) (==:bool (get-jfield Double.width name(r)) 6.0:double)) (==:bool (get-jfield Double.height name(r)) 2.0:double)))
  (call name(assert) (and (==:bool (get-jfield Double.x name(pd)) 12.0:double) (==:bool (get-jfield Double.y name(pd)) 5.0:double)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def r (jnew Double 1.0:double 2.0:double 3.0:double 4.0:double))
  (set-jfield x name(r) 2.0:double)
  (set-jfield y name(r) 0.5:double)
  (set-jfield width name(r) 2.0:double)
  (set-jfield height name(r) 2.0:double)
  (def pd (jnew Double 10.0:double 20.0:double))
  (set-jfield x name(pd) 2.0:double)
  (set-jfield x name(pd) 4.0:double)
  (set-jfield x name(pd) 3.0:double)
  (set-jfield x name(pd) 2.0:double)
  (set-jfield y name(pd) 5.0:double)
  (call name(assert) (and (and (and (==:bool (get-jfield Double.x name(r)) 3.0:double) (==:bool (get-jfield Double.y name(r)) 1.5:double)) (==:bool (get-jfield Double.width name(r)) 6.0:double)) (==:bool (get-jfield Double.height name(r)) 2.0:double)))
  (call name(assert) (and (==:bool (get-jfield Double.x name(pd)) 12.0:double) (==:bool (get-jfield Double.y name(pd)) 5.0:double)))
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

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def demo (jnew JavaClassDemo))
  (set-jfield nonNullLabel name(demo) "a")
  (set-jfield nonNullLabel name(demo) "b")
  (def gbc (jnew GridBagConstraints))
  (set-jfield insets name(gbc) (jnew Insets 1:int 2:int 3:int 4:int))
  (set-jfield weightx name(gbc) 2.0:double)
  (set-jfield weightx name(gbc) 1:int)
  (call name(assert) (and (and (==:bool (get-jfield JavaClassDemo.nonNullLabel name(demo)) "ab") (==:bool (get-jfield Insets.top (nonnull (get-jfield GridBagConstraints.insets name(gbc)))) 1:int)) (==:bool (get-jfield GridBagConstraints.weightx name(gbc)) 3.0:double)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def demo (jnew JavaClassDemo))
  (set-jfield nonNullLabel name(demo) "a")
  (set-jfield nonNullLabel name(demo) "b")
  (def gbc (jnew GridBagConstraints))
  (set-jfield insets name(gbc) (jnew Insets 1:int 2:int 3:int 4:int))
  (set-jfield weightx name(gbc) 2.0:double)
  (set-jfield weightx name(gbc) 1:int)
  (call name(assert) (and (and (==:bool (get-jfield JavaClassDemo.nonNullLabel name(demo)) "ab") (==:bool (get-jfield Insets.top (nonnull (get-jfield GridBagConstraints.insets name(gbc)))) 1:int)) (==:bool (get-jfield GridBagConstraints.weightx name(gbc)) 3.0:double)))
```

## Java untyped Object field assign (String and boxed int)

```babble
def ev = java.awt.Event(null, 0, null)
ev.arg = "ok"
def s = ev.arg
ev.arg = 42
assert(s == "ok" and ev.arg == 42)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def ev (jnew Event null 0:int null))
  (set-jfield arg name(ev) "ok")
  (def s (get-jfield Event.arg name(ev)))
  (set-jfield arg name(ev) 42:int)
  (call name(assert) (and (==:bool name(s) "ok") (==:bool (get-jfield Event.arg name(ev)) 42:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def ev (jnew Event null 0:int null))
  (set-jfield arg name(ev) "ok")
  (def s "ok")
  (set-jfield arg name(ev) 42:int)
  (call name(assert) (and (==:bool name(s) "ok") (==:bool (get-jfield Event.arg name(ev)) 42:int)))
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

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def HF (select (select (call name(import) "org.babblelang.tests").EffectsTestCase).HostFixtures))
  (def saved (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) 10:int)
  (set-jfield mutableStatic name(HF) 5:int)
  (set-jfield mutableStatic name(HF) 3:int)
  (set-jfield mutableStatic name(HF) 2:int)
  (def after (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) name(saved))
  (call name(assert) (==:bool name(after) 24:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def HF (select (select (call name(import) "org.babblelang.tests").EffectsTestCase).HostFixtures))
  (def saved (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) 10:int)
  (set-jfield mutableStatic name(HF) 5:int)
  (set-jfield mutableStatic name(HF) 3:int)
  (set-jfield mutableStatic name(HF) 2:int)
  (def after (get-jfield HostFixtures.mutableStatic name(HF)))
  (set-jfield mutableStatic name(HF) name(saved))
  (call name(assert) (==:bool name(after) 24:int))
```

## emitJavaMethodCall exception catch handler

```babble
java.lang.Integer.parseInt("bad_num")
```

```error
java.lang.NumberFormatException : For input string: "bad_num"
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (jcall Integer.parseInt "bad_num")
```

## emitJavaConstructorCall exception catch handler

```babble
java.io.FileReader("nonexistent_batest_file_xyz.txt")
```

```error
java.io.FileNotFoundException : nonexistent_batest_file_xyz.txt (<OS error>)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (jnew FileReader "nonexistent_batest_file_xyz.txt")
```

## isPureStaticJavaChain : multi-expr block, declaring block, javax root, forward capture, and non-pure static field assign

```babble
def SH = import("org.babblelang.tests").BytecodeEffectAnalyzerTestCase.SampleHelpers
def fwd = fn (): int -> ( def g = fn (): int -> ( M.abs(-5) ); def M = java.lang.Math; g() )
(discard(1); SH).mutableCounter = 0
assert((discard(1); java.lang.Math).abs(-3) == 3 and (def m = java.lang.Math).abs(-4) == 4)
assert(javax.xml.XMLConstants.NULL_NS_URI == "" and fwd() == 5 and SH.mutableCounter == 0)
```

```hir
;; hir -O0,-O1 fn=fwd
fn fwd () -> int {nothrow unique}
  (def g (fn-ref g))
  (def M (select (select name(java).lang).Math))
  (call-fn g)
```

```hir
;; hir -O2 fn=fwd
fn fwd () -> int {nothrow unique}
  (def g (fn-ref g))
  (def M (select (select name(java).lang).Math))
  (call-fn g)
```

## Empty for, try, and catch blocks plus local move expression

```babble
def Box = class (def v : int) ()
for x in list(1, 2) then ()
def t: any? = try () catch e then ()
def run = fn (): int -> ( def a = Box(4) ; def b = move(a) ; b.v )
assert(t == null and run() == 4)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> int {nothrow unique}
  (def a (call name(Box) 4:int))
  (def b (move var(a)))
  (get-this-field v)
```

```hir
;; hir -O2 fn=run
fn run () -> int {nothrow unique}
  (def a (call name(Box) 4:int))
  (def b (move var(a)))
  (get-this-field v)
```

## resolveBabbleJvmName for Babble interface array, function-local class array, and union array

```babble
def I = interface ( def k : fn () : int )
def ia = (array of I?)(2)
def ua = (array of (int | string)).of(1, "a")
def mkLocal = fn () : int -> (
    def L = class (def v : int) ()
    def la = (array of L).of(L(3), L(4))
    la.get(0).v + la.get(1).v
)
assert(ia.size() == 2 and ua.get(1) == "a" and mkLocal() == 7)
```

```hir
;; hir -O0,-O1 fn=mkLocal
fn mkLocal () -> int {pure unique}
  (def L (class-ref L))
  (def la (array-new OF (call var(L) 3:int) (call var(L) 4:int)))
  (+:int (get-this-field v) (get-this-field v))
```

```hir
;; hir -O2 fn=mkLocal
fn mkLocal () -> int {pure unique}
  (def L (class-ref L))
  (def la (array-new OF (call var(L) 3:int) (call var(L) 4:int)))
  (+:int (get-this-field v) (get-this-field v))
```

## Nested else-if widening and typed rest without reorder plus reordered named args

```babble
def pick = fn (a : bool, b : bool) : double -> ( if a then ( 1 ) else if b then ( 2 ) else ( 3.5 ) )
def sumR = fn (a : int, xs : int...) : int -> ( var s = a ; for x in xs then ( s += x ) ; s )
def sub = fn (a : int, b : int) : int -> ( a - b )
assert(pick(false, true) == 2.0 and pick(false, false) == 3.5 and sumR(1, 2, 3) == 6 and sub(b: 2, a: 10) == 8)
```

```hir
;; hir -O0 fn=pick
fn pick (a:bool, b:bool) -> double {nothrow}
  (if var(a) (widen[double] (block 1:int)) (if var(b) (widen[double] (block 2:int)) (block 3.5:double)))
```

```hir
;; hir -O1 fn=pick
fn pick (a:bool, b:bool) -> double {nothrow}
  (if var(a) 1.0:double (if var(b) 2.0:double 3.5:double))
```

```hir
;; hir -O2 fn=pick
fn pick (a:bool, b:bool) -> double {nothrow}
  (if var(a) 1.0:double (if var(b) 2.0:double 3.5:double))
```

## Contextual long and float literals widened to double

```babble
def toD = fn (a : double, b : double) : double -> ( a + b )
def d1 : double = 10L
def d2 : double = 1.5f
assert(d1 == 10.0 and d2 == 1.5 and toD(2L, 0.5f) == 2.5)
```

```hir
;; hir -O0,-O1 fn=toD
fn toD (a:double, b:double) -> double {nothrow commutative monotone unique}
  (+:double var(a) var(b))
```

```hir
;; hir -O2 fn=toD
fn toD (a:double, b:double) -> double {nothrow commutative monotone unique}
  (+:double var(a) var(b))
```

## Non-spread 9-parameter class method lowers via METHOD_DESCRIPTOR

```babble
def Big = class () (
    def sum9 = fn (a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int, i: int): int -> (
        a + b + c + d + e + f + g + h + i
    )
)
assert(Big().sum9(1, 2, 3, 4, 5, 6, 7, 8, 9) == 45)
```

```hir
;; hir -O0,-O1 class=Big
class Big
  (def sum9 (fn-ref Big.sum9))
  fn Big.sum9 (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) -> int {nothrow unique}
    (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)) var(i))
```

```hir
;; hir -O2 class=Big
class Big
  (def sum9 (fn-ref Big.sum9))
  fn Big.sum9 (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) -> int {nothrow unique}
    (+:int (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)) var(i))
```

## Non-spread 9-parameter function with rest parameter uses Arguments.binder with restIndex

```babble
def sumRest = fn (a: int, b: int, c: int, d: int, e: int, f: int, g: int, h: int, xs: int...): int -> (
    var t = a + b + c + d + e + f + g + h
    for x in xs then ( t += x )
    t
)
assert(sumRest(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55)
```

```hir
;; hir -O0,-O1 fn=sumRest
fn sumRest (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, xs:obj) -> int {nothrow unique}
  (def t (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)))
  (for x in var(xs)
    (block
      (set t += var(x))))
  var(t)
```

```hir
;; hir -O2 fn=sumRest
fn sumRest (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, xs:obj) -> int {nothrow unique}
  (def t (+:int (+:int (+:int (+:int (+:int (+:int (+:int var(a) var(b)) var(c)) var(d)) var(e)) var(f)) var(g)) var(h)))
  (for x in var(xs)
    (block
      (set t += var(x))))
  var(t)
```

## Specialized function with widened boxed optional parameter default

```babble
def f = fn (x: int, d: double? = 1): double -> (
    double(x) + (if d is double then ( d ) else ( 0.0 ))
)
assert(f(2) == 3.0 and f(2, 4.5) == 6.5 and f(2, null) == 2.0)
```

```hir
;; hir -O0 fn=f
fn f (x:int, d:obj=1:int) -> double {nothrow unique}
  (+:double (call name(double) var(x)) (block (if (is var(d) double) (block (narrow.is[double] var(d))) (block 0.0:double))))
```

```hir
;; hir -O1 fn=f
fn f (x:int, d:obj=1:int) -> double {nothrow unique}
  (+:double (call name(double) var(x)) (if (is var(d) double) (narrow.is[double] var(d)) 0.0:double))
```

```hir
;; hir -O2 fn=f
fn f (x:int, d:obj=1:int) -> double {nothrow unique}
  (+:double (call name(double) var(x)) (if (is var(d) double) (narrow.is[double] var(d)) 0.0:double))
```

## Variadic function and bound method adapted to Java SAM exercise maxArity restIndex branch

```babble
def C = class () ( def m = fn (x: int, xs: int...): int -> ( x + 10 ) )
def op1: java.util.function.IntUnaryOperator = fn (x: int, xs: int...): int -> ( x + 1 )
def op2: java.util.function.IntUnaryOperator = C().m
assert(op1.applyAsInt(5) == 6 and op2.applyAsInt(5) == 15)
```

```hir
;; hir -O0,-O1 fn=op1
fn op1 (x:int, xs:obj) -> int {nothrow unique}
  (+:int var(x) 1:int)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def m (fn-ref C.m))
  fn C.m (x:int, xs:obj) -> int {nothrow unique}
    (+:int var(x) 10:int)
```

```hir
;; hir -O2 class=C
class C
  (def m (fn-ref C.m))
  fn C.m (x:int, xs:obj) -> int {nothrow unique}
    (+:int var(x) 10:int)
```

## CompiledBabbleScript.lineOf falls back when thrown exception has empty stack trace

```babble
def e = java.lang.ArrayIndexOutOfBoundsException("no-frames")
e.setStackTrace((array of java.lang.StackTraceElement).of())
throw e
```

```error
java.lang.ArrayIndexOutOfBoundsException: no-frames
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def e (jnew ArrayIndexOutOfBoundsException "no-frames"))
  (jcall Throwable.setStackTrace name(e) (array-new OF))
  (throw name(e))
```
