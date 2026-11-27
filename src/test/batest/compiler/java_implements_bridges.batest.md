# Babble classes, records, objects, and interfaces implementing or extending Java interfaces via direct JVM b...

References : BIP002, RM003-P3 (Java interface bridges & super default dispatch)

```babble
;; babble preamble
def CS = type java.lang.CharSequence
def C = type java.util.function.Consumer of any?
def Buf = class () implements java.lang.Appendable, java.lang.CharSequence (
def b = java.lang.StringBuilder()
def append = fn io shared (shared v: any?, s: int = -1, e: int = -1): java.lang.Appendable -> (
  if v is java.lang.Character then (this.b.append("[" + v + "]"))
  else if s < 0 then (this.b.append(v as CS)) else (this.b.append(v as CS, s, e)) ; this
)
def length = fn io shared (): int -> (this.b.length())
def charAt = fn io shared (i: int): any -> (this.b.charAt(i))
def subSequence = fn io shared (s: int, e: int): CS -> (this.b.subSequence(s, e))
def toString = fn (): string -> (this.b.toString())
)
def mkC = fn io () -> (java.util.function.Consumer(fn io (x: any?) -> (print(x))))
```

## Case 1

```babble
def Counter = class (private var n: int) implements java.util.function.IntSupplier, java.lang.Runnable (
    def run = fn mut (): any? -> (this.n += 10 ; null)
    def getAsInt = fn (): int -> (this.n)
)
var c = Counter(5)
def r: java.lang.Runnable = c
r.run()
def sup: java.util.function.IntSupplier = c
assert(sup.getAsInt() == 15)
```

```hir
;; hir -O0,-O1 class=Counter
class Counter (n:int)
  (def run (fn-ref Counter.run))
  (def getAsInt (fn-ref Counter.getAsInt))
  fn Counter.run () -> obj {nothrow mut unique}
    (set-this-field n += 10:int)
    null
  fn Counter.getAsInt () -> int {nothrow unique}
    (get-this-field n)
```

```hir
;; hir -O2 class=Counter
class Counter (n:int)
  (def run (fn-ref Counter.run))
  (def getAsInt (fn-ref Counter.getAsInt))
  fn Counter.run () -> obj {nothrow mut unique}
    (set-this-field n += 10:int)
    null
  fn Counter.getAsInt () -> int {nothrow unique}
    (get-this-field n)
```

## Case 2

```babble
def W = record (g: int) implements java.lang.Comparable of W (
  def compareTo = fn (o: W): int -> (this.g - o.g)
)
def xs: java.util.List of W = java.util.ArrayList()
xs.add(W(30)) ; xs.add(W(10)) ; xs.add(W(20))
java.util.Collections.sort(xs)
assert(xs.get(0).g == 10)
def u: java.util.Set of W = java.util.HashSet()
u.add(W(10))
assert(u.contains(W(10)))
```

```hir
;; hir -O0,-O1 record=W
record W (g:int)
  (def compareTo (fn-ref W.compareTo))
  fn W.compareTo (o:obj) -> int {nothrow unique}
    (-:int (get-this-field g) (get-this-field g))
```

```hir
;; hir -O2 record=W
record W (g:int)
  (def compareTo (fn-ref W.compareTo))
  fn W.compareTo (o:obj) -> int {nothrow unique}
    (-:int (get-this-field g) (get-this-field g))
```

## Case 3

```babble
def mulObj = object implements java.util.function.DoubleBinaryOperator (
    def applyAsDouble = fn (a: double, b: double): double -> (a * b + 0.5)
)
assert(mulObj.applyAsDouble(2.0, 3.0) == 6.5)
```

```hir
;; hir -O0 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def mulObj (class-ref object))
  (call name(assert) (==:bool (call-method name(mulObj).applyAsDouble 2.0:double 3.0:double) 6.5:double))
```

```hir
;; hir -O1 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def mulObj (class-ref object))
  (call name(assert) true:bool)
```

```hir
;; hir -O2 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def mulObj (class-ref object))
  (call name(assert) true:bool)
```

## Case 4

```babble
def R = interface extends java.lang.Comparable of R (
  def rk: fn (): int
  def compareTo = fn (o: R): int -> (this.rk() - o.rk())
)
def I = class (def r: int) implements R (def rk = fn (): int -> (this.r))
def xs: list of I = java.util.ArrayList()
xs.add(I(2)) ; xs.add(I(1))
java.util.Collections.sort(xs)
assert(xs.get(0).r == 1 and (xs.get(0) is java.lang.Comparable) and (xs.get(0) is R))
```

```hir
;; hir -O0,-O1 interface=R
interface R
  (def rk)
  (def compareTo (fn-ref compareTo))
  fn compareTo (o:obj) -> int {pure}
    (-:int (call-iface this.rk) (call-iface var(o).rk))
```

```hir
;; hir -O2 interface=R
interface R
  (def rk)
  (def compareTo (fn-ref compareTo))
  fn compareTo (o:obj) -> int {pure}
    (-:int (call-iface this.rk) (call-iface var(o).rk))
```

## Overloaded Appendable/CharSequence methods served by optional params on Buf

```babble
def b = Buf()
java.util.Formatter(b).format("%s-%d", java.util.List.of("ok", 7).toArray())
b.append("abcdef", 1, 4)
def cs: java.lang.CharSequence = b
assert(cs.length() == 7)
assert(java.lang.String.valueOf(cs.subSequence(0, 4)) == "ok-7")
assert(java.lang.String.valueOf(cs) == "ok-7bcd")
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def b (call name(Buf)))
  (jcall Formatter.format (jnew Formatter name(b)) "%s-%d" (jcall List.toArray (jcall List.of "ok" 7:int)))
  (call-method name(b).append "abcdef" 1:int 4:int)
  (def cs name(b))
  (call name(assert) (==:bool (jcall CharSequence.length name(cs)) 7:int))
  (call name(assert) (==:bool (jcall String.valueOf (jcall CharSequence.subSequence name(cs) 0:int 4:int)) "ok-7"))
  (call name(assert) (==:bool (jcall String.valueOf name(cs)) "ok-7bcd"))
```

```hir
;; hir -O2 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def b (call name(Buf)))
  (block
    (def $cse0 name(b))
    (jcall Formatter.format (jnew Formatter var($cse0)) "%s-%d" (jcall List.toArray (jcall List.of "ok" 7:int)))
    (call-method var($cse0).append "abcdef" 1:int 4:int)
    (def cs var($cse0))
    (def $cse1 name(assert))
    (def $cse2 name(cs))
    (call var($cse1) (==:bool (jcall CharSequence.length var($cse2)) 7:int))
    (call var($cse1) (==:bool (jcall String.valueOf (jcall CharSequence.subSequence var($cse2) 0:int 4:int)) "ok-7"))
    (call var($cse1) (==:bool (jcall String.valueOf var($cse2)) "ok-7bcd")))
```

## Case 6

```babble
def W = interface extends java.util.Iterator of int (
  def forEachRemaining = fn io shared (shared a: C): any? -> (
    print("<") ; super(java.util.Iterator).forEachRemaining(a) ; print(">")
  )
)
def R = class (private var c: int) implements W (
  def hasNext = fn (): bool -> (this.c < 4)
  def next = fn mut (): int -> (this.c += 1 ; this.c - 1)
)
R(1).forEachRemaining(mkC())
```

```stdout
<123>
```

```hir
;; hir -O0,-O1 interface=W
interface W
  (def forEachRemaining (fn-ref forEachRemaining))
  fn forEachRemaining (a:obj) -> obj {io shared}
    (call name(print) "<")
    (super.forEachRemaining var(a))
    (call name(print) ">")
```

```hir
;; hir -O2 interface=W
interface W
  (def forEachRemaining (fn-ref forEachRemaining))
  fn forEachRemaining (a:obj) -> obj {io shared}
    (block
      (def $cse0 name(print))
      (call var($cse0) "<")
      (super.forEachRemaining var(a))
      (call var($cse0) ">"))
```

## Case 7

```babble
def AddOp = class (delta: int) implements java.util.function.IntUnaryOperator (
    def applyAsInt = fn (x: int): int -> (x + delta)
)
assert(AddOp(2).andThen(AddOp(10)).applyAsInt(5) == 17)
```

```hir
;; hir -O0,-O1 class=AddOp
class AddOp (delta:int)
  (def applyAsInt (fn-ref AddOp.applyAsInt))
  fn AddOp.applyAsInt (x:int) -> int {nothrow injective unique}
    (+:int var(x) var(delta))
```

```hir
;; hir -O2 class=AddOp
class AddOp (delta:int)
  (def applyAsInt (fn-ref AddOp.applyAsInt))
  fn AddOp.applyAsInt (x:int) -> int {nothrow injective unique}
    (+:int var(x) var(delta))
```

## Primitive return widening : int -> long via IntToLongFunction

```babble
def Widener = class (scale: int) implements java.util.function.IntToLongFunction (
    def factor = 2
    def applyAsLong = fn (x: int): int -> (x * scale * this.factor)
)
assert(java.util.stream.IntStream.range(1, 4).mapToLong(Widener(5)).sum() == 60L)
```

```hir
;; hir -O0,-O1 class=Widener
class Widener (scale:int)
  (def factor 2:int)
  (def applyAsLong (fn-ref Widener.applyAsLong))
  fn Widener.applyAsLong (x:int) -> int {nothrow unique}
    (*:int (*:int var(x) var(scale)) (get-this-field factor))
```

```hir
;; hir -O2 class=Widener
class Widener (scale:int)
  (def factor 2:int)
  (def applyAsLong (fn-ref Widener.applyAsLong))
  fn Widener.applyAsLong (x:int) -> int {nothrow unique}
    (*:int (*:int var(x) var(scale)) (get-this-field factor))
```

## Field with same name as Java default method stays accessible as a Babble field

```babble
def SingleIter = class () implements java.util.Iterator of int (
    def remove = 123
    def hasNext = fn (): bool -> (false)
    def next = fn (): int -> (0)
)
assert(SingleIter().remove == 123)
```

```hir
;; hir -O0,-O1 class=SingleIter
class SingleIter
  (def remove 123:int)
  (def hasNext (fn-ref SingleIter.hasNext))
  (def next (fn-ref SingleIter.next))
  fn SingleIter.hasNext () -> bool {nothrow unique}
    false:bool
  fn SingleIter.next () -> int {nothrow unique}
    0:int
```

```hir
;; hir -O2 class=SingleIter
class SingleIter
  (def remove 123:int)
  (def hasNext (fn-ref SingleIter.hasNext))
  (def next (fn-ref SingleIter.next))
  fn SingleIter.hasNext () -> bool {nothrow unique}
    false:bool
  fn SingleIter.next () -> int {nothrow unique}
    0:int
```

## Field with same name as Java default method does not shadow the Java default method when invoked via the Ja...

```babble
def SingleIter = class () implements java.util.Iterator of int (
    def remove = 123
    def hasNext = fn (): bool -> (false)
    def next = fn (): int -> (0)
)
def asJava: java.util.Iterator of int = SingleIter()
asJava.remove()
```

```error
java.lang.UnsupportedOperationException : remove
```

```hir
;; hir -O0,-O1 class=SingleIter
class SingleIter
  (def remove 123:int)
  (def hasNext (fn-ref SingleIter.hasNext))
  (def next (fn-ref SingleIter.next))
  fn SingleIter.hasNext () -> bool {nothrow unique}
    false:bool
  fn SingleIter.next () -> int {nothrow unique}
    0:int
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def CS null)
  (def C null)
  (def Buf (class-ref Buf))
  (def mkC (fn-ref mkC))
  (def SingleIter (class-ref SingleIter))
  (def asJava (call name(SingleIter)))
  (jcall Iterator.remove name(asJava))
```

## Case 11

```babble
def LongInc = class (d: int) implements java.util.function.LongUnaryOperator (
    def applyAsLong = fn (x: any?): int -> (int(x as long) + d)
)
def IsPos = class () implements java.util.function.DoublePredicate (
    def test = fn (d: double): bool -> (d > 0.0)
)
assert(java.util.stream.LongStream.of(10).map(LongInc(5)).sum() == 15L)
assert(IsPos().test(1.5) and not IsPos().test(-0.5))
```

```hir
;; hir -O0,-O1 class=LongInc
class LongInc (d:int)
  (def applyAsLong (fn-ref LongInc.applyAsLong))
  fn LongInc.applyAsLong (x:obj) -> int {pure unique}
    (+:int (call name(int) (as var(x) long|long|)) var(d))
```

```hir
;; hir -O2 class=LongInc
class LongInc (d:int)
  (def applyAsLong (fn-ref LongInc.applyAsLong))
  fn LongInc.applyAsLong (x:obj) -> int {pure unique}
    (+:int (call name(int) (as var(x) long|long|)) var(d))
```

## Case 12

```babble
def Flag = class (def v: bool) implements java.util.function.BooleanSupplier (
    def getAsBoolean = fn (): bool -> (this.v)
)
def b = Buf()
b.append("xyz")
assert(Flag(true).getAsBoolean())
assert(java.nio.CharBuffer.wrap(b).charAt(1) == 'y')
```

```hir
;; hir -O0,-O1 class=Flag
class Flag (v:bool)
  (def getAsBoolean (fn-ref Flag.getAsBoolean))
  fn Flag.getAsBoolean () -> bool {nothrow unique}
    (get-this-field v)
```

```hir
;; hir -O2 class=Flag
class Flag (v:bool)
  (def getAsBoolean (fn-ref Flag.getAsBoolean))
  fn Flag.getAsBoolean () -> bool {nothrow unique}
    (get-this-field v)
```
