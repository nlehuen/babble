# Call lowering : core collections, cross-module dispatch, overloads, and SAM spreads

References : BIP001, BIP002, BIP006, BIP011 ; targets `CallEmitter`, `HirBuilder`

```babble
;; babble module=ext_ops.ba
export def Vec = record (x : int) (
    def plus = overload (
        fn (n : int) : Vec -> ( Vec(this.x + n) ),
        fn (o : Vec) : Vec -> ( Vec(this.x + o.x) )
    )
    def inc = fn (n : int, step : int = 5) : int -> ( this.x + n + step )
    def tag = fn (s : string) : string -> ( s + ":" + this.x )
    def sum9 = fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> (
        this.x + a + b + c + d + e + f + g + h + i
    )
)
export def LateBox = class () (
    def peek = fn () : int -> ( 1 )
    def first = this.peek()
    def late : int = 42
)
```

```babble
;; babble module=hier_base.ba
export def A0 = interface ( def a0 = fn () : int -> ( 1 ) )
```

```babble
;; babble module=hier_mid.ba
def b = import("hier_base.ba")
def A1 = interface extends b.A0 ( def a1 = fn () : int -> ( 2 ) )
export def A2 = interface extends A1 ( def a2 = fn () : int -> ( this.a0() + this.a1() ) )
```

```babble
;; babble preamble
def tests = import("org.babblelang.tests")
def revInt = fn (a : int, b : int) : int -> ( b - a )
def revStr = fn (a : string, b : string) : int -> ( b.compareTo(a) )
```

## Core sortedset and sortedmap from-copy factories

```babble
def ss = sortedset(from: list(3, 1, 2))
def sm = sortedmap(from: map(entry("b", 2), entry("a", 1)))
assert(ss.size() == 3 and ss.first() == 1 and ss.last() == 3)
assert(sm.size() == 2 and sm.firstKey() == "a" and sm.get("b") == 2)
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def ss (core-new sortedset from=(core-new list 3:int 1:int 2:int)))
  (def sm (core-new sortedmap from=(core-new map (core-new entry "b" 2:int) (core-new entry "a" 1:int))))
  (call name(assert) (and (and (==:bool (jcall Set.size name(ss)) 3:int) (==:bool (jcall SortedSet.first name(ss)) 1:int)) (==:bool (jcall SortedSet.last name(ss)) 3:int)))
  (call name(assert) (and (and (==:bool (jcall Map.size name(sm)) 2:int) (==:bool (jcall SortedMap.firstKey name(sm)) "a")) (==:bool (jcall Map.get name(sm) "b") 2:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def ss (core-new sortedset from=(core-new list 3:int 1:int 2:int)))
  (def sm (core-new sortedmap from=(core-new map (core-new entry "b" 2:int) (core-new entry "a" 1:int))))
  (block
    (def $cse0 name(ss))
    (def $cse1 name(assert))
    (call var($cse1) (and (and (==:bool (jcall Set.size var($cse0)) 3:int) (==:bool (jcall SortedSet.first var($cse0)) 1:int)) (==:bool (jcall SortedSet.last var($cse0)) 3:int)))
    (def $cse2 name(sm))
    (call var($cse1) (and (and (==:bool (jcall Map.size var($cse2)) 2:int) (==:bool (jcall SortedMap.firstKey var($cse2)) "a")) (==:bool (jcall Map.get var($cse2) "b") 2:int))))
```

## Core sortedset and sortedmap from-plus-comparator in both argument orders

```babble
def xs = list(1, 3, 2)
def m = map(entry("a", 1), entry("c", 3), entry("b", 2))
def ss1 = sortedset(from: xs, comparator: revInt)
def ss2 = sortedset(comparator: revInt, from: xs)
def sm1 = sortedmap(from: m, comparator: revStr)
def sm2 = sortedmap(comparator: revStr, from: m)
assert(ss1.first() == 3 and ss2.first() == 3 and sm1.firstKey() == "c" and sm2.firstKey() == "c")
```

```hir
;; hir -O0,-O1 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def xs (core-new list 1:int 3:int 2:int))
  (def m (core-new map (core-new entry "a" 1:int) (core-new entry "c" 3:int) (core-new entry "b" 2:int)))
  (def ss1 (core-new sortedset from=name(xs) comparator=(java-sam Comparator name(revInt))))
  (def ss2 (core-new sortedset comparator=(java-sam Comparator name(revInt)) from=name(xs)))
  (def sm1 (core-new sortedmap from=name(m) comparator=(java-sam Comparator name(revStr))))
  (def sm2 (core-new sortedmap comparator=(java-sam Comparator name(revStr)) from=name(m)))
  (call name(assert) (and (and (and (==:bool (jcall SortedSet.first name(ss1)) 3:int) (==:bool (jcall SortedSet.first name(ss2)) 3:int)) (==:bool (jcall SortedMap.firstKey name(sm1)) "c")) (==:bool (jcall SortedMap.firstKey name(sm2)) "c")))
```

```hir
;; hir -O2 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def xs (core-new list 1:int 3:int 2:int))
  (def m (core-new map (core-new entry "a" 1:int) (core-new entry "c" 3:int) (core-new entry "b" 2:int)))
  (block
    (def $cse0 name(revInt))
    (def $cse1 name(xs))
    (def ss1 (core-new sortedset from=var($cse1) comparator=(java-sam Comparator var($cse0))))
    (def ss2 (core-new sortedset comparator=(java-sam Comparator var($cse0)) from=var($cse1)))
    (def $cse2 name(revStr))
    (def $cse3 name(m))
    (def sm1 (core-new sortedmap from=var($cse3) comparator=(java-sam Comparator var($cse2))))
    (def sm2 (core-new sortedmap comparator=(java-sam Comparator var($cse2)) from=var($cse3)))
    (call name(assert) (and (and (and (==:bool (jcall SortedSet.first name(ss1)) 3:int) (==:bool (jcall SortedSet.first name(ss2)) 3:int)) (==:bool (jcall SortedMap.firstKey name(sm1)) "c")) (==:bool (jcall SortedMap.firstKey name(sm2)) "c"))))
```

## Cross-module direct overloaded operator, optional spec method, and >8-param fallback

```babble
def m = import("ext_ops.ba")
def v = m.Vec(10)
assert((v + 5).x == 15)
assert((v + m.Vec(3)).x == 13)
assert(v.inc(2) == 17 and v.inc(2, 1) == 13)
assert(v.tag("v") == "v:10")
assert(v.sum9(1, 2, 3, 4, 5, 6, 7, 8, 9) == 55)
```

```hir
;; hir -O0 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def m (call name(import) "ext_ops.ba"))
  (def v (record-new ext_ops$ba$C0$Vec 10:int))
  (call name(assert) (==:bool (get-this-field x) 15:int))
  (call name(assert) (==:bool (get-this-field x) 13:int))
  (call name(assert) (and (==:bool (call-method name(v).inc 2:int) 17:int) (==:bool (call-method name(v).inc 2:int 1:int) 13:int)))
  (call name(assert) (==:bool (call-method name(v).tag "v") "v:10"))
  (call name(assert) (==:bool (call (select name(v).sum9) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 55:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def m (call name(import) "ext_ops.ba"))
  (def v (record-new ext_ops$ba$C0$Vec 10:int))
  (call name(assert) (==:bool (block
        (def $this#1 name(v))
        (+:int (get-this-field x) 5:int)) 15:int))
  (call name(assert) (==:bool (block
        (def $this#2 name(v))
        (def o (record-new ext_ops$ba$C0$Vec 3:int))
        (+:int (get-this-field x) (get-this-field x))) 13:int))
  (call name(assert) (and (==:bool (block
          (def $this#3 name(v))
          (+:int (get-this-field x) 3:int)) 13:int) (==:bool (call-method name(v).inc 2:int) 17:int)))
  (call name(assert) (==:bool (block
        (def $this#4 name(v))
        (+:obj "v:" (get-this-field x))) "v:10"))
  (call name(assert) (==:bool (call (select name(v).sum9) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 55:int))
```

```hir
;; hir -O2 top-level
top-level:
  (block
    (def $cse0 name(import))
    (def tests (call var($cse0) "org.babblelang.tests"))
    (def revInt (fn-ref revInt))
    (def revStr (fn-ref revStr))
    (def m (call var($cse0) "ext_ops.ba"))
    (def $cse3 name(m))
    (def $cse4 (export var($cse3).Vec))
    (def v (record-new ext_ops$ba$C0$Vec 10:int))
    (def $cse1 name(assert))
    (def $cse2 name(v))
    (def $cse5 (get-this-field x))
    (call var($cse1) (==:bool (+:int var($cse5) 5:int) 15:int))
    (call var($cse1) (==:bool (block
          (def o (record-new ext_ops$ba$C0$Vec 3:int))
          (+:int var($cse5) (get-this-field x))) 13:int))
    (call var($cse1) (and (==:bool (+:int var($cse5) 3:int) 13:int) (==:bool (call-method var($cse2).inc 2:int) 17:int)))
    (call var($cse1) (==:bool (+:obj "v:" var($cse5)) "v:10"))
    (call var($cse1) (==:bool (call (select var($cse2).sum9) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 55:int)))
```

## Cross-module definite and non-definite field reads plus transitive interface hierarchy

```babble
def eo = import("ext_ops.ba")
def hm = import("hier_mid.ba")
def lb = eo.LateBox()
def Impl = class () implements hm.A2 ()
assert(eo.Vec(7).x == 7 and lb.first == 1 and lb.late == 42 and Impl().a2() == 3)
```

```hir
;; hir -O0 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def eo (call name(import) "ext_ops.ba"))
  (def hm (call name(import) "hier_mid.ba"))
  (def lb (call (export name(eo).LateBox)))
  (def Impl (class-ref Impl))
  (call name(assert) (and (and (and (==:bool (get-this-field x) 7:int) (==:bool (select name(lb).first) 1:int)) (==:bool (select name(lb).late) 42:int)) (==:bool (call (select (call name(Impl)).a2)) 3:int)))
```

```hir
;; hir -O1 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def eo (call name(import) "ext_ops.ba"))
  (def hm (call name(import) "hier_mid.ba"))
  (def lb (call (export name(eo).LateBox)))
  (def Impl (class-ref Impl))
  (call name(assert) (and (and (==:bool (select name(lb).first) 1:int) (==:bool (select name(lb).late) 42:int)) (==:bool (call (select (call name(Impl)).a2)) 3:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (block
    (def $cse0 name(import))
    (def tests (call var($cse0) "org.babblelang.tests"))
    (def revInt (fn-ref revInt))
    (def revStr (fn-ref revStr))
    (def eo (call var($cse0) "ext_ops.ba"))
    (def hm (call var($cse0) "hier_mid.ba"))
    (def $cse1 name(eo))
    (def lb (call (export var($cse1).LateBox)))
    (def Impl (class-ref Impl))
    (def $cse2 name(lb))
    (call name(assert) (and (and (==:bool (select var($cse2).first) 1:int) (==:bool (select var($cse2).late) 42:int)) (==:bool (call (select (call name(Impl)).a2)) 3:int))))
```

## Dynamic function-typed call with >8 arguments (positional and named)

```babble
def sum9 = fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> (
    a + b + c + d + e + f + g + h + i
)
def call9 = fn (g: fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int): int): int -> (
    g(1, 2, 3, 4, 5, 6, 7, 8, 9) + g(1, 2, 3, 4, 5, 6, 7, 8, i: 10)
)
assert(call9(sum9) == 91)
```

```hir
;; hir -O0,-O1 fn=call9
fn call9 (g:obj) -> int {pure unique}
  (+:int (call var(g) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) (call var(g) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int i=10:int))
```

```hir
;; hir -O2 fn=call9
fn call9 (g:obj) -> int {pure unique}
  (+:int (call var(g) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) (call var(g) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int i=10:int))
```

## Overload direct >8-param alternative and anonymous inline overload call

```babble
def big = overload (
    fn (a:int, b:int, c:int, d:int, e:int, f:int, g:int, h:int, i:int) : int -> ( a + i ),
    fn (s : string) : int -> ( s.length() )
)
assert(big(1, 2, 3, 4, 5, 6, 7, 8, 9) == 10 and big("abc") == 3)
assert((overload ( fn (x : int) : int -> ( x + 1 ), fn (s : string) : int -> ( s.length() ) ))(5) == 6)
```

```hir
;; hir -O0 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def big (overload (fn-ref big#1) (fn-ref big#2)))
  (call name(assert) (and (==:bool (call (overload-select #0 name(big)) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 10:int) (==:bool (call-fn big#2 "abc") 3:int)))
  (call name(assert) (==:bool (call-fn <anon#0> 5:int) 6:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def big (overload (fn-ref big#1) (fn-ref big#2)))
  (call name(assert) (and (==:bool (call (overload-select #0 name(big)) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 10:int) true:bool))
  (call name(assert) (==:bool (block
        (overload-select #0 (overload (fn-ref <anon#0>) (fn-ref <anon#1>)))
        6:int) 6:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def tests (call name(import) "org.babblelang.tests"))
  (def revInt (fn-ref revInt))
  (def revStr (fn-ref revStr))
  (def big (overload (fn-ref big#1) (fn-ref big#2)))
  (block
    (def $cse0 name(big))
    (def $cse1 name(assert))
    (call var($cse1) (and (==:bool (call (overload-select #0 var($cse0)) 1:int 2:int 3:int 4:int 5:int 6:int 7:int 8:int 9:int) 10:int) true:bool))
    (call var($cse1) (==:bool (block
          (overload-select #0 (overload (fn-ref <anon#0>) (fn-ref <anon#1>)))
          6:int) 6:int)))
```

## Union-split overload dispatch with optional primitive and reference defaults

```babble
def f = overload (
    fn (x : int, step : int = 10) : int -> ( x + step ),
    fn (s : string, tag : string = "!") : string -> ( s + tag )
)
def run = fn (u : int | string) : int | string -> ( f(u) )
assert(run(5) == 15 and run("hi") == "hi!")
```

```hir
;; hir -O0,-O1 fn=run
fn run (u:obj) -> obj {nothrow unique}
  (overload-dispatch name(f) var(u))
```

```hir
;; hir -O2 fn=run
fn run (u:obj) -> obj {nothrow unique}
  (overload-dispatch name(f) var(u))
```

## Union-split overload dispatch with rest parameters on reference and primitive branches

```babble
def g = overload (
    fn (x : int, r : int...) : int -> ( var s = x ; for v in r then ( s += v ) ; s ),
    fn (s : string, r : string...) : string -> ( var o = s ; for v in r then ( o += v ) ; o )
)
def runG = fn (u : int | string) : int | string -> ( g(u) )
assert(runG(3) == 3 and runG("a") == "a")
```

```hir
;; hir -O0,-O1 fn=runG
fn runG (u:obj) -> obj {nothrow unique}
  (overload-dispatch name(g) var(u))
```

```hir
;; hir -O2 fn=runG
fn runG (u:obj) -> obj {nothrow unique}
  (overload-dispatch name(g) var(u))
```

## Union-split overloaded class method exercising rest and trailing optional params

```babble
def Box = class () (
    def m = overload (
        fn (x : int, a : int = 1, b : int = 2) : int -> ( x + a + b ),
        fn (s : string, r : string...) : string -> ( var o = s ; for v in r then ( o += v ) ; o )
    )
)
def run = fn (bx : Box, u : int | string) : int | string -> ( bx.m(u) )
assert(run(Box(), 4) == 7 and run(Box(), "hi") == "hi")
```

```hir
;; hir -O0,-O1 fn=run
fn run (bx:obj, u:obj) -> obj {nothrow unique}
  (overload-dispatch (select var(bx).m) var(u))
```

```hir
;; hir -O2 fn=run
fn run (bx:obj, u:obj) -> obj {nothrow unique}
  (overload-dispatch (select var(bx).m) var(u))
```

## Union-split overloaded class method with middle omitted optional param

```babble
def Box = class () (
    def m = overload (
        fn (x : int, a : int = 1, b : int = 2) : int -> ( x + a + b ),
        fn (s : string, a : int = 1, b : int = 2) : string -> ( s + ":" + (a + b) )
    )
)
def run = fn (bx : Box, u : int | string) : int | string -> ( bx.m(u, b: 10) )
assert(run(Box(), 4) == 15 and run(Box(), "hi") == "hi:11")
```

```hir
;; hir -O0,-O1 fn=run
fn run (bx:obj, u:obj) -> obj {nothrow unique}
  (overload-dispatch (select var(bx).m) var(u) 10:int)
```

```hir
;; hir -O2 fn=run
fn run (bx:obj, u:obj) -> obj {nothrow unique}
  (overload-dispatch (select var(bx).m) var(u) 10:int)
```

## Overloaded interface method with named arg, omitted optional arg, and safe call

```babble
def IOv = interface (
    def calc = overload (
        fn (x: int, step: int = 10): int -> ( x + step ),
        fn (s: string): int -> ( s.length() )
    )
)
def C = class () implements IOv ()
def iv: IOv = C() ; def opt: C? = C()
assert(iv.calc(x: 5) == 15 and iv.calc(5) == 15 and opt?.calc(x: 2) == 12)
```

```hir
;; hir -O0,-O1 interface=IOv
interface IOv
  (def calc (overload (fn-ref calc#1) (fn-ref calc#2)))
  fn calc (x:int, step:int=10:int) -> int {pure}
    (+:int var(x) var(step))
  fn calc (s:obj) -> int {pure}
    (jcall String.length var(s))
```

```hir
;; hir -O2 interface=IOv
interface IOv
  (def calc (overload (fn-ref calc#1) (fn-ref calc#2)))
  fn calc (x:int, step:int=10:int) -> int {pure}
    (+:int var(x) var(step))
  fn calc (s:obj) -> int {pure}
    (jcall String.length var(s))
```

## Overloaded interface operator method

```babble
def IOv = interface (
    def plus = overload ( fn (n: int): int -> ( n + 1 ), fn (s: string): int -> ( s.length() ) )
)
def C = class () implements IOv ()
def iv: IOv = C()
assert((iv + 4) == 5 and (iv + "hi") == 2)
```

```hir
;; hir -O0,-O1 interface=IOv
interface IOv
  (def plus (overload (fn-ref plus#1) (fn-ref plus#2)))
  fn plus (n:int) -> int {pure}
    (+:int var(n) 1:int)
  fn plus (s:obj) -> int {pure}
    (jcall String.length var(s))
```

```hir
;; hir -O2 interface=IOv
interface IOv
  (def plus (overload (fn-ref plus#1) (fn-ref plus#2)))
  fn plus (n:int) -> int {pure}
    (+:int var(n) 1:int)
  fn plus (s:obj) -> int {pure}
    (jcall String.length var(s))
```

## Non-accessible Java SAM spread into Iterable (asIterableByName)

```babble
def takeIt = fn io (shared xs: java.lang.Iterable of tests.JavaClassDemo.HiddenIntSam): int -> (
    var n = 0 ; for x in xs then ( n += tests.JavaClassDemo.invokeHiddenSam(x, 4) ) ; n
)
def fns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x * 2 ))
assert(takeIt(...fns) == 13)
```

```hir
;; hir -O0,-O1 fn=takeIt
fn takeIt (xs:obj) -> int {io}
  (def n 0:int)
  (for x in var(xs)
    (block
      (set n += (call (select (select name(tests).JavaClassDemo).invokeHiddenSam) var(x) 4:int))))
  var(n)
```

```hir
;; hir -O2 fn=takeIt
fn takeIt (xs:obj) -> int {io}
  (def n 0:int)
  (for x in var(xs)
    (block
      (set n += (call (select (select name(tests).JavaClassDemo).invokeHiddenSam) var(x) 4:int))))
  var(n)
```

## Non-accessible Java SAM spread into List (asListByName)

```babble
def takeL = fn io (shared xs: list of tests.JavaClassDemo.HiddenIntSam): int -> (
    tests.JavaClassDemo.invokeHiddenSam(xs.get(0), 5)
)
def fns = list(fn (x: int): int -> ( x + 1 ), fn (x: int): int -> ( x * 2 ))
assert(takeL(...fns) == 6)
```

```hir
;; hir -O0,-O1 fn=takeL
fn takeL (xs:obj) -> int {io}
  (call (select (select name(tests).JavaClassDemo).invokeHiddenSam) (jcall List.get var(xs) 0:int) 5:int)
```

```hir
;; hir -O2 fn=takeL
fn takeL (xs:obj) -> int {io}
  (call (select (select name(tests).JavaClassDemo).invokeHiddenSam) (jcall List.get var(xs) 0:int) 5:int)
```

## Non-accessible Java SAM spread into Object-erased array

```babble
def takeArr = fn io (shared xs: array of (tests.JavaClassDemo.HiddenIntSam & java.io.Serializable)): int -> (
    tests.JavaClassDemo.invokeHiddenSerializable(xs.get(0), 6)
)
def fns = list(fn (x: int): int -> ( x + 3 ))
assert(takeArr(...fns) == 9)
```

```hir
;; hir -O0,-O1 fn=takeArr
fn takeArr (xs:obj) -> int {io}
  (call (select (select name(tests).JavaClassDemo).invokeHiddenSerializable) (array-op GET var(xs) 0:int) 6:int)
```

```hir
;; hir -O2 fn=takeArr
fn takeArr (xs:obj) -> int {io}
  (call (select (select name(tests).JavaClassDemo).invokeHiddenSerializable) (array-op GET var(xs) 0:int) 6:int)
```

## Non-accessible Java SAM spread into hidden-marker list

```babble
def takeMk = fn (xs: list of (tests.JavaClassDemo.SameSigRunnable & tests.JavaClassDemo.HiddenMarkerSam)): int -> (
    xs.size()
)
def runs = list(fn () -> ())
assert(takeMk(...runs) == 1)
```

```hir
;; hir -O0,-O1 fn=takeMk
fn takeMk (xs:obj) -> int {nothrow unique}
  (jcall List.size var(xs))
```

```hir
;; hir -O2 fn=takeMk
fn takeMk (xs:obj) -> int {nothrow unique}
  (jcall List.size var(xs))
```
