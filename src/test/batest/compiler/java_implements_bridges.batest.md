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
def toString = fn io shared (): string -> (this.b.toString())
)
def mkC = fn io () -> (java.util.function.Consumer(fn io (x: any?) -> (print(x))))
```

## Case 1

```babble
def Counter = class (private def n: int) implements java.util.function.IntSupplier, java.lang.Runnable (
    def run = fn mut (): any? -> (this.n += 10 ; null)
    def getAsInt = fn (): int -> (this.n)
)
def c = Counter(5)
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

```mir
;; mir -O2 class=Counter stage=before,final
class Counter:
  fn Counter.run() -> any:
    bb0:
      v0: any = slot_read this {pure}
      v1: int = load_field v0.n {read:shared,throws}
      v2: int = const 10
      v3: int = add v1, v2 {pure}
      store_field v0.n, v3 {write:shared,throws}
      v4: any = const null
      ret v4
  fn Counter.getAsInt() -> int:
    bb0:
      v0: any = slot_read this {pure}
      v1: int = load_field v0.n {read:shared,throws}
      ret v1
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

```mir
;; mir -O2 record=W stage=before,final
record W:
  fn W.compareTo(v0: any) -> int:
    bb0:
      v1: any = slot_read this {pure}
      v2: int = load_field v1.g {read:frozen}
      v3: int = load_field v0.g {read:frozen}
      v4: int = sub v2, v3 {pure}
      ret v4
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
  (call name(assert) (==:bool (block
        (def $this name(mulObj))
        6.5:double) 6.5:double))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
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

```mir
;; mir -O2 interface=R stage=before,final
interface R:
  fn compareTo(v0: any) -> int:
    bb0:
      v1: any = slot_read this {pure}
      v2: int = call_iface v1.rk() {pure,throws}
      v3: int = call_iface v0.rk() {pure,throws}
      v4: int = sub v2, v3 {pure}
      ret v4
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

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Case 6

```babble
def W = interface extends java.util.Iterator of int (
  def forEachRemaining = fn io shared (shared a: C): any? -> (
    print("<") ; super(java.util.Iterator).forEachRemaining(a) ; print(">")
  )
)
def R = class (private def c: int) implements W (
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

```mir
;; mir -O2 interface=W stage=before,final
interface W:
  fn forEachRemaining(v0: any) -> any:
    bb0:
      v1: any = opaque.NameRead() {impure}
      v2: any = const "<"
      v3: any = opaque.DynamicCall(v1, v2) {impure}
      v4: any = opaque.SuperCall(v0) {impure}
      v5: any = opaque.NameRead() {impure}
      v6: any = const ">"
      v7: any = opaque.DynamicCall(v5, v6) {impure}
      ret v7
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

```mir
;; mir -O2 class=AddOp stage=before,final
class AddOp:
  fn AddOp.applyAsInt(v0: int) -> int:
    bb0:
      v1: int = slot_read delta {read:shared,throws}
      v2: int = add v0, v1 {pure}
      ret v2
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

```mir
;; mir -O2 class=Widener stage=before,final
class Widener:
  fn Widener.applyAsLong(v0: int) -> int:
    bb0:
      v1: int = slot_read scale {read:shared,throws}
      v2: int = mul v0, v1 {pure}
      v3: any = slot_read this {pure}
      v4: int = load_field v3.factor {read:shared,throws}
      v5: int = mul v2, v4 {pure}
      ret v5
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

```mir
;; mir -O2 class=SingleIter stage=before,final
class SingleIter:
  fn SingleIter.hasNext() -> bool:
    bb0:
      v0: bool = const false
      ret v0
  fn SingleIter.next() -> int:
    bb0:
      v0: int = const 0
      ret v0
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

```mir
;; mir -O2 class=LongInc stage=before,final
class LongInc:
  fn LongInc.applyAsLong(v0: any) -> int:
    bb0:
      v1: any = opaque.NameRead() {impure}
      v2: any = checkcast v0 <long|long|>
      v3: int = opaque.DynamicCall(v1, v2) {impure}
      v4: int = slot_read d {read:shared,throws}
      v5: int = add v3, v4 {pure}
      ret v5
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

```mir
;; mir -O2 class=Flag stage=before,final
class Flag:
  fn Flag.getAsBoolean() -> bool:
    bb0:
      v0: any = slot_read this {pure}
      v1: bool = load_field v0.v {read:shared,throws}
      ret v1
```
