# Primitive Unboxing : Interface Bridges and Primitive Array Loops

References : BIP011 (§7 primitive unboxing), BIP001 (RM001/RM003 lowering)


## Generic Babble interface bridge unboxing for byte and short

```babble
def Box of T = interface ( def id: fn (x: T): T )
def BB = class () implements Box of byte ( def id = fn (x: byte): byte -> ( x ) )
def SB = class () implements Box of short ( def id = fn (x: short): short -> ( x ) )
def b: Box of byte = BB() ; def s: Box of short = SB()
assert(b.id(byte(2)) == byte(2) and s.id(short(3)) == short(3))
```

```hir
;; hir -O0,-O1 class=BB
class BB
  (def id (fn-ref BB.id))
  fn BB.id (x:byte) -> byte {nothrow idempotent monotone injective unique}
    var(x)
```

```hir
;; hir -O2 class=BB
class BB
  (def id (fn-ref BB.id))
  fn BB.id (x:byte) -> byte {nothrow idempotent monotone injective unique}
    var(x)
```

## Generic Babble interface bridge unboxing for long and float

```babble
def Box of T = interface ( def id: fn (x: T): T )
def LB = class () implements Box of long ( def id = fn (x: long): long -> ( x ) )
def FB = class () implements Box of float ( def id = fn (x: float): float -> ( x ) )
def l: Box of long = LB() ; def f: Box of float = FB()
assert(l.id(4L) == 4L and f.id(1.5f) == 1.5f)
```

```hir
;; hir -O0,-O1 class=LB
class LB
  (def id (fn-ref LB.id))
  fn LB.id (x:long) -> long {nothrow idempotent monotone injective unique}
    var(x)
```

```hir
;; hir -O2 class=LB
class LB
  (def id (fn-ref LB.id))
  fn LB.id (x:long) -> long {nothrow idempotent monotone injective unique}
    var(x)
```

## Generic Babble interface bridge unboxing for bool and char

```babble
def Box of T = interface ( def id: fn (x: T): T )
def ZB = class () implements Box of bool ( def id = fn (x: bool): bool -> ( x ) )
def CB = class () implements Box of char ( def id = fn (x: char): char -> ( x ) )
def z: Box of bool = ZB() ; def c: Box of char = CB()
assert(z.id(true) and c.id('K') == 'K')
```

```hir
;; hir -O0,-O1 class=ZB
class ZB
  (def id (fn-ref ZB.id))
  fn ZB.id (x:bool) -> bool {nothrow idempotent monotone injective unique}
    var(x)
```

```hir
;; hir -O2 class=ZB
class ZB
  (def id (fn-ref ZB.id))
  fn ZB.id (x:bool) -> bool {nothrow idempotent monotone injective unique}
    var(x)
```

## Babble interface bridge primitive adaptation across int, double, and boxed unions

```babble
def W = interface ( def a: fn (x: int): any ; def b: fn (x: int): double? )
def C = class () implements W (
    def a = fn (x: int | double): int -> ( if x is int then ( x ) else ( int(x) ) )
    def b = fn (x: int | double): double -> ( if x is int then ( double(x) ) else ( x ) )
)
def w: W = C()
assert(w.a(2) == 2 and w.b(3) == 3.0)
```

```hir
;; hir -O0 class=C
class C
  (def a (fn-ref C.a))
  (def b (fn-ref C.b))
  fn C.a (x:obj) -> int {pure}
    (if (is var(x) int) (block (narrow.is[int] var(x))) (block (call name(int) var(x))))
  fn C.b (x:obj) -> double {nothrow}
    (if (is var(x) int) (block (call name(double) (narrow.is[int] var(x)))) (block var(x)))
```

```hir
;; hir -O1 class=C
class C
  (def a (fn-ref C.a))
  (def b (fn-ref C.b))
  fn C.a (x:obj) -> int {pure}
    (if (is var(x) int) (narrow.is[int] var(x)) (call name(int) var(x)))
  fn C.b (x:obj) -> double {nothrow}
    (if (is var(x) int) (call name(double) (narrow.is[int] var(x))) var(x))
```

```hir
;; hir -O2 class=C
class C
  (def a (fn-ref C.a))
  (def b (fn-ref C.b))
  fn C.a (x:obj) -> int {pure}
    (if (is var(x) int) (narrow.is[int] var(x)) (call name(int) var(x)))
  fn C.b (x:obj) -> double {nothrow}
    (if (is var(x) int) (call name(double) (narrow.is[int] var(x))) var(x))
```

## Optional union bridge null-guard adaptation into primitive double and default float

```babble
def OptU = interface ( def f: fn (x: int | double = ...): double )
def C = class () implements OptU (
    def f = fn (x: double = 10.0, fl: float = 1.5f): double -> ( x + double(fl) )
)
def u: OptU = C()
assert(u.f() == 11.5 and u.f(3) == 4.5)
```

```hir
;; hir -O0,-O1 class=C
class C
  (def f (fn-ref C.f))
  fn C.f (x:double=10.0:double, fl:float=1.5:float) -> double {nothrow monotone unique}
    (+:double var(x) (call name(double) var(fl)))
```

```hir
;; hir -O2 class=C
class C
  (def f (fn-ref C.f))
  fn C.f (x:double=10.0:double, fl:float=1.5:float) -> double {nothrow monotone unique}
    (+:double var(x) (call name(double) var(fl)))
```

## Optional union bridge null-guard adaptation and non-optional boxed widening into nullable double

```babble
def OptN = interface ( def g: fn (x: int | double = ...): bool ; def h: fn (x: int): bool )
def C = class () implements OptN (
    def g = fn (x: double? = 10.0): bool -> ( x != null and x == 10.0 )
    def h = fn (x: double?): bool -> ( x != null and x == 5.0 )
)
def u: OptN = C()
assert(u.g() and u.g(10) and u.h(5))
```

```hir
;; hir -O0,-O1 class=C
class C
  (def g (fn-ref C.g))
  (def h (fn-ref C.h))
  fn C.g (x:obj=10.0:double) -> bool {nothrow unique}
    (and (<>:bool var(x) null) (==:bool (narrow.nonnull var(x)) 10.0:double))
  fn C.h (x:obj) -> bool {nothrow unique}
    (and (<>:bool var(x) null) (==:bool (narrow.nonnull var(x)) 5.0:double))
```

```hir
;; hir -O2 class=C
class C
  (def g (fn-ref C.g))
  (def h (fn-ref C.h))
  fn C.g (x:obj=10.0:double) -> bool {nothrow unique}
    (and (<>:bool var(x) null) (==:bool (narrow.nonnull var(x)) 10.0:double))
  fn C.h (x:obj) -> bool {nothrow unique}
    (and (<>:bool var(x) null) (==:bool (narrow.nonnull var(x)) 5.0:double))
```

## Java bridge primitive and reference arguments widened into nullable numeric Babble params

```babble
def PInt = class () implements java.util.function.IntPredicate ( def test = fn (x: long?): bool -> ( x == 7L ) )
def PRef = class () implements java.util.function.Predicate of int ( def test = fn (x: long?): bool -> ( x == 7L ) )
def a: java.util.function.IntPredicate = PInt() ; def b: java.util.function.Predicate of int = PRef()
assert(a.test(7) and not a.test(2) and b.test(7))
```

```hir
;; hir -O0,-O1 class=PInt
class PInt
  (def test (fn-ref PInt.test))
  fn PInt.test (x:obj) -> bool {nothrow unique}
    (==:bool var(x) 7:long)
```

```hir
;; hir -O2 class=PInt
class PInt
  (def test (fn-ref PInt.test))
  fn PInt.test (x:obj) -> bool {nothrow unique}
    (==:bool var(x) 7:long)
```

## Array for-loops over byte, bool, short, long, and float arrays

```babble
var s = 0
for b in (array of byte).of(byte(1), byte(2)) then ( s += int(b) )
for z in (array of bool).of(true, false, true) then ( if z then ( s += 10 ) )
for sh in (array of short).of(short(3), short(4)) then ( s += int(sh) )
for l in (array of long).of(5L, 6L) then ( s += int(l) )
for f in (array of float).of(1.0f, 2.0f) then ( s += int(f) )
assert(s == 44)
```

```hir
;; hir -O0 top-level
top-level:
  (def s 0:int)
  (for b in (array-new OF (call name(byte) 1:int) (call name(byte) 2:int))
    (block
      (set-name s += (call name(int) var(b)))))
  (for z in (array-new OF true:bool false:bool true:bool)
    (block
      (if var(z) (box (block (set-name s += 10:int))))))
  (for sh in (array-new OF (call name(short) 3:int) (call name(short) 4:int))
    (block
      (set-name s += (call name(int) var(sh)))))
  (for l in (array-new OF 5:long 6:long)
    (block
      (set-name s += (call name(int) var(l)))))
  (for f in (array-new OF 1.0:float 2.0:float)
    (block
      (set-name s += (call name(int) var(f)))))
  (call name(assert) (==:bool name(s) 44:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def s 0:int)
  (for b in (array-new OF (call name(byte) 1:int) (call name(byte) 2:int))
    (block
      (set-name s += (call name(int) var(b)))))
  (for z in (array-new OF true:bool false:bool true:bool)
    (block
      (if var(z) (box (set-name s += 10:int)))))
  (for sh in (array-new OF (call name(short) 3:int) (call name(short) 4:int))
    (block
      (set-name s += (call name(int) var(sh)))))
  (for l in (array-new OF 5:long 6:long)
    (block
      (set-name s += (call name(int) var(l)))))
  (for f in (array-new OF 1.0:float 2.0:float)
    (block
      (set-name s += (call name(int) var(f)))))
  (call name(assert) (==:bool name(s) 44:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def s 0:int)
  (for b in (array-new OF (call name(byte) 1:int) (call name(byte) 2:int))
    (block
      (set-name s += (call name(int) var(b)))))
  (for z in (array-new OF true:bool false:bool true:bool)
    (block
      (if var(z) (box (set-name s += 10:int)))))
  (for sh in (array-new OF (call name(short) 3:int) (call name(short) 4:int))
    (block
      (set-name s += (call name(int) var(sh)))))
  (for l in (array-new OF 5:long 6:long)
    (block
      (set-name s += (call name(int) var(l)))))
  (for f in (array-new OF 1.0:float 2.0:float)
    (block
      (set-name s += (call name(int) var(f)))))
  (call name(assert) (==:bool name(s) 44:int))
```

## Capturing bool, byte, short, and char for-loop variables in closures

```babble
var s = 0
for z in (array of bool).of(true) then ( def g = fn (): int -> ( if z then (1) else (0) ); s += g() )
for b in (array of byte).of(byte(2)) then ( def g = fn (): int -> ( int(b) ); s += g() )
for h in (array of short).of(short(3)) then ( def g = fn (): int -> ( int(h) ); s += g() )
for c in (array of char).of('A') then ( def g = fn (): int -> ( int(c) ); s += g() )
assert(s == 71)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def s 0:int)
  (for z in (array-new OF true:bool)
    (block
      (def g#1 (fn-ref g#1))
      (set-name s += (call-fn g#1))))
  (for b in (array-new OF (call name(byte) 2:int))
    (block
      (def g#2 (fn-ref g#2))
      (set-name s += (call-fn g#2))))
  (for h in (array-new OF (call name(short) 3:int))
    (block
      (def g#3 (fn-ref g#3))
      (set-name s += (call-fn g#3))))
  (for c in (array-new OF 'A')
    (block
      (def g#4 (fn-ref g#4))
      (set-name s += (call-fn g#4))))
  (call name(assert) (==:bool name(s) 71:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def s 0:int)
  (for z in (array-new OF true:bool)
    (block
      (def g#1 (fn-ref g#1))
      (set-name s += (call-fn g#1))))
  (for b in (array-new OF (call name(byte) 2:int))
    (block
      (def g#2 (fn-ref g#2))
      (set-name s += (call-fn g#2))))
  (for h in (array-new OF (call name(short) 3:int))
    (block
      (def g#3 (fn-ref g#3))
      (set-name s += (call-fn g#3))))
  (for c in (array-new OF 'A')
    (block
      (def g#4 (fn-ref g#4))
      (set-name s += (call-fn g#4))))
  (call name(assert) (==:bool name(s) 71:int))
```

## Capturing long, float, and double for-loop variables in closures

```babble
def fs = (list of (fn (): int))()
for l in (array of long).of(4L) then ( fs.add(fn (): int -> ( int(l) )) )
for f in (array of float).of(5.0f) then ( fs.add(fn (): int -> ( int(f) )) )
for d in (array of double).of(6.0) then ( fs.add(fn (): int -> ( int(d) )) )
assert(fs.get(0)() + fs.get(1)() + fs.get(2)() == 15)
```

```hir
;; hir -O0 top-level
top-level:
  (def fs (call (block (type-apply core-col(list)))))
  (for l in (array-new OF 4:long)
    (block
      (jcall List.add name(fs) (fn-ref <anon#0>))))
  (for f in (array-new OF 5.0:float)
    (block
      (jcall List.add name(fs) (fn-ref <anon#1>))))
  (for d in (array-new OF 6.0:double)
    (block
      (jcall List.add name(fs) (fn-ref <anon#2>))))
  (call name(assert) (==:bool (+:int (+:int (call (jcall List.get name(fs) 0:int)) (call (jcall List.get name(fs) 1:int))) (call (jcall List.get name(fs) 2:int))) 15:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def fs (call (type-apply core-col(list))))
  (for l in (array-new OF 4:long)
    (block
      (jcall List.add name(fs) (fn-ref <anon#0>))))
  (for f in (array-new OF 5.0:float)
    (block
      (jcall List.add name(fs) (fn-ref <anon#1>))))
  (for d in (array-new OF 6.0:double)
    (block
      (jcall List.add name(fs) (fn-ref <anon#2>))))
  (call name(assert) (==:bool (+:int (+:int (call (jcall List.get name(fs) 0:int)) (call (jcall List.get name(fs) 1:int))) (call (jcall List.get name(fs) 2:int))) 15:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def fs (call (type-apply core-col(list))))
  (for l in (array-new OF 4:long)
    (block
      (jcall List.add name(fs) (fn-ref <anon#0>))))
  (for f in (array-new OF 5.0:float)
    (block
      (jcall List.add name(fs) (fn-ref <anon#1>))))
  (for d in (array-new OF 6.0:double)
    (block
      (jcall List.add name(fs) (fn-ref <anon#2>))))
  (call name(assert) (==:bool (+:int (+:int (call (jcall List.get name(fs) 0:int)) (call (jcall List.get name(fs) 1:int))) (call (jcall List.get name(fs) 2:int))) 15:int))
```

## Primitive array(from:) for byte, short, float, bool, and char

```babble
def ab = (array of byte)(from: list.of(byte(1), byte(2)))
def as_ = (array of short)(from: list.of(short(3), short(4)))
def af = (array of float)(from: list.of(1.5f, 2.5f))
def az = (array of bool)(from: list.of(true, false))
def ac = (array of char)(from: list.of('x', 'y'))
assert(ab.get(1) == byte(2) and as_.get(0) == short(3) and af.get(1) == 2.5f and az.get(0) and ac.get(1) == 'y')
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def ab (array-new FROM (call (select core-col(list).of) (call name(byte) 1:int) (call name(byte) 2:int))))
  (def as_ (array-new FROM (call (select core-col(list).of) (call name(short) 3:int) (call name(short) 4:int))))
  (def af (array-new FROM (call (select core-col(list).of) 1.5:float 2.5:float)))
  (def az (array-new FROM (call (select core-col(list).of) true:bool false:bool)))
  (def ac (array-new FROM (call (select core-col(list).of) 'x' 'y')))
  (call name(assert) (and (and (and (and (==:bool (array-op GET name(ab) 1:int) (call name(byte) 2:int)) (==:bool (array-op GET name(as_) 0:int) (call name(short) 3:int))) (==:bool (array-op GET name(af) 1:int) 2.5:float)) (array-op GET name(az) 0:int)) (==:bool (array-op GET name(ac) 1:int) 'y')))
```

```hir
;; hir -O2 top-level
top-level:
  (def ab (array-new FROM (call (select core-col(list).of) (call name(byte) 1:int) (call name(byte) 2:int))))
  (def as_ (array-new FROM (call (select core-col(list).of) (call name(short) 3:int) (call name(short) 4:int))))
  (def af (array-new FROM (call (select core-col(list).of) 1.5:float 2.5:float)))
  (def az (array-new FROM (call (select core-col(list).of) true:bool false:bool)))
  (def ac (array-new FROM (call (select core-col(list).of) 'x' 'y')))
  (call name(assert) (and (and (and (and (==:bool (array-op GET name(ab) 1:int) (call name(byte) 2:int)) (==:bool (array-op GET name(as_) 0:int) (call name(short) 3:int))) (==:bool (array-op GET name(af) 1:int) 2.5:float)) (array-op GET name(az) 0:int)) (==:bool (array-op GET name(ac) 1:int) 'y')))
```
