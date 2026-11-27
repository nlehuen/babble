# Primitive Unboxing : Scalar Parameters, Arithmetic, Truthiness, and Comparisons

References : BIP011 (§7 primitive unboxing), BIP001 (RM001/RM003 lowering)



## 8-primitive parameter lowering including 2-slot long/double and branch selection

```babble
def mix = fn (b: byte, s: short, i: int, l: long, f: float, d: double, c: char, ok: bool): double -> (
    def sl = l + long(i) + long(b) + long(s)
    def sf = f + float(i)
    if ok and c == 'A' then ( double(sl) + double(sf) + d ) else ( 0.0 )
)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'A', true) == 116.5)
assert(mix(byte(2), short(3), 4, 100L, 2.5f, 1.0, 'B', true) == 0.0)
```

```hir
;; hir -O0 fn=mix
fn mix (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, ok:bool) -> double {nothrow}
  (def sl (+:long (+:long (+:long var(l) (call name(long) var(i))) (call name(long) var(b))) (call name(long) var(s))))
  (def sf (+:float var(f) (call name(float) var(i))))
  (if (and var(ok) (==:bool var(c) 'A')) (block (+:double (+:double (call name(double) var(sl)) (call name(double) var(sf))) var(d))) (block 0.0:double))
```

```hir
;; hir -O1 fn=mix
fn mix (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, ok:bool) -> double {nothrow}
  (def sl (+:long (+:long (+:long var(l) (call name(long) var(i))) (call name(long) var(b))) (call name(long) var(s))))
  (def sf (+:float var(f) (call name(float) var(i))))
  (if (and var(ok) (==:bool var(c) 'A')) (+:double (+:double (call name(double) var(sl)) (call name(double) var(sf))) var(d)) 0.0:double)
```

```hir
;; hir -O2 fn=mix
fn mix (b:byte, s:short, i:int, l:long, f:float, d:double, c:char, ok:bool) -> double {nothrow}
  (block
    (def $cse0 name(long))
    (def sl (+:long (+:long (+:long var(l) (call var($cse0) var(i))) (call var($cse0) var(b))) (call var($cse0) var(s))))
    (def sf (+:float var(f) (call name(float) var(i))))
    (if (and var(ok) (==:bool var(c) 'A'))
      (block
        (def $cse1 name(double))
        (+:double (+:double (call var($cse1) var(sl)) (call var($cse1) var(sf))) var(d)))
      0.0:double))
```

## Unboxed unary negation and arithmetic on narrow/wide primitives (parameterized against -O1 folding)

```babble
def negMix = fn (b : byte, s : short, l : long, f : float) : double -> (
    def nb : int = -b
    def ns : int = -s
    def nl : long = -l + 10L
    def nf : float = -f + 1.5f
    double(nb + ns) + double(l + nl) + double(f + nf)
)
assert(negMix(byte(2), short(3), 100L, 2.5f) == 6.5)
```

```hir
;; hir -O0,-O1 fn=negMix
fn negMix (b:byte, s:short, l:long, f:float) -> double {nothrow unique}
  (def nb (neg (convert[int] var(b))))
  (def ns (neg (convert[int] var(s))))
  (def nl (+:long (neg var(l)) 10:long))
  (def nf (+:float (neg var(f)) 1.5:float))
  (+:double (+:double (call name(double) (+:int var(nb) var(ns))) (call name(double) (+:long var(l) var(nl)))) (call name(double) (+:float var(f) var(nf))))
```

```hir
;; hir -O2 fn=negMix
fn negMix (b:byte, s:short, l:long, f:float) -> double {nothrow unique}
  (def nb (neg (convert[int] var(b))))
  (def ns (neg (convert[int] var(s))))
  (def nl (+:long (neg var(l)) 10:long))
  (def nf (+:float (neg var(f)) 1.5:float))
  (block
    (def $cse0 name(double))
    (+:double (+:double (call var($cse0) (+:int var(nb) var(ns))) (call var($cse0) (+:long var(l) var(nl)))) (call var($cse0) (+:float var(f) var(nf)))))
```

## Parameterized against -O1 folding to exercise unboxed byte/short truthiness

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short) -> (
    assert((if b0 then ( 1 ) else ( 0 )) == 0 and (if b1 then ( 1 ) else ( 0 )) == 1)
    assert((if s0 then ( 1 ) else ( 0 )) == 0 and (if s1 then ( 1 ) else ( 0 )) == 1)
)
check(byte(0), byte(5), short(0), short(7))
```

```hir
;; hir -O0 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short) -> bool {pure unique}
  (call name(assert) (and (==:bool (block (if var(b0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(b1) (block 1:int) (block 0:int))) 1:int)))
  (call name(assert) (and (==:bool (block (if var(s0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(s1) (block 1:int) (block 0:int))) 1:int)))
```

```hir
;; hir -O1 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short) -> bool {pure unique}
  (call name(assert) (and (==:bool (if var(b0) 1:int 0:int) 0:int) (==:bool (if var(b1) 1:int 0:int) 1:int)))
  (call name(assert) (and (==:bool (if var(s0) 1:int 0:int) 0:int) (==:bool (if var(s1) 1:int 0:int) 1:int)))
```

```hir
;; hir -O2 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (call var($cse0) (and (==:bool (if var(b0) 1:int 0:int) 0:int) (==:bool (if var(b1) 1:int 0:int) 1:int)))
    (call var($cse0) (and (==:bool (if var(s0) 1:int 0:int) 0:int) (==:bool (if var(s1) 1:int 0:int) 1:int))))
```

## Parameterized against -O1 folding to exercise unboxed long/float truthiness

```babble
def check = fn (l0 : long, l1 : long, f0 : float, f1 : float) -> (
    assert((if l0 then ( 1 ) else ( 0 )) == 0 and (if l1 then ( 1 ) else ( 0 )) == 1)
    assert((if f0 then ( 1 ) else ( 0 )) == 0 and (if f1 then ( 1 ) else ( 0 )) == 1)
)
check(0L, 10000000000L, 0.0f, 1.5f)
```

```hir
;; hir -O0 fn=check
fn check (l0:long, l1:long, f0:float, f1:float) -> bool {pure unique}
  (call name(assert) (and (==:bool (block (if var(l0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(l1) (block 1:int) (block 0:int))) 1:int)))
  (call name(assert) (and (==:bool (block (if var(f0) (block 1:int) (block 0:int))) 0:int) (==:bool (block (if var(f1) (block 1:int) (block 0:int))) 1:int)))
```

```hir
;; hir -O1 fn=check
fn check (l0:long, l1:long, f0:float, f1:float) -> bool {pure unique}
  (call name(assert) (and (==:bool (if var(l0) 1:int 0:int) 0:int) (==:bool (if var(l1) 1:int 0:int) 1:int)))
  (call name(assert) (and (==:bool (if var(f0) 1:int 0:int) 0:int) (==:bool (if var(f1) 1:int 0:int) 1:int)))
```

```hir
;; hir -O2 fn=check
fn check (l0:long, l1:long, f0:float, f1:float) -> bool {pure unique}
  (block
    (def $cse0 name(assert))
    (call var($cse0) (and (==:bool (if var(l0) 1:int 0:int) 0:int) (==:bool (if var(l1) 1:int 0:int) 1:int)))
    (call var($cse0) (and (==:bool (if var(f0) 1:int 0:int) 0:int) (==:bool (if var(f1) 1:int 0:int) 1:int))))
```

## Parameterized against -O1 folding to exercise unboxed primitive comparisons

```babble
def check = fn (b0 : byte, b1 : byte, s0 : short, s1 : short, l1 : long, f1 : float, c0 : char, c1 : char) -> (
    assert(b0 < b1 and s1 > s0 and l1 > 5000000000L and f1 >= 1.5f and c0 < c1 and c0 == 'a')
)
check(byte(0), byte(5), short(0), short(7), 10000000000L, 1.5f, 'a', 'z')
```

```hir
;; hir -O0,-O1 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short, l1:long, f1:float, c0:char, c1:char) -> bool {pure unique}
  (call name(assert) (and (and (and (and (and (<:bool var(b0) var(b1)) (>:bool var(s1) var(s0))) (>:bool var(l1) 5000000000:long)) (>=:bool var(f1) 1.5:float)) (<:bool var(c0) var(c1))) (==:bool var(c0) 'a')))
```

```hir
;; hir -O2 fn=check
fn check (b0:byte, b1:byte, s0:short, s1:short, l1:long, f1:float, c0:char, c1:char) -> bool {pure unique}
  (call name(assert) (and (and (and (and (and (<:bool var(b0) var(b1)) (>:bool var(s1) var(s0))) (>:bool var(l1) 5000000000:long)) (>=:bool var(f1) 1.5:float)) (<:bool var(c0) var(c1))) (==:bool var(c0) 'a')))
```

## Case 8

```babble
def H = class (var b: byte, var s: short, var l: long, var f: float, var c: char) (
    def sum = fn (x: long): long -> ( this.l + x + long(this.b) + long(this.s) )
)
def h = H(byte(3), short(9), 1000L, 2.25f, 'K')
assert(h.sum(50L) == 1062L and h.f == 2.25f and h.c == 'K')
h.b = byte(4); h.s = short(12); h.l = 2000L; h.f = 3.5f; h.c = 'Z'
assert(h.sum(1L) == 2017L and h.f == 3.5f and h.c == 'Z')
```

```hir
;; hir -O0,-O1 class=H
class H (b:byte, s:short, l:long, f:float, c:char)
  (def sum (fn-ref H.sum))
  fn H.sum (x:long) -> long {nothrow unique}
    (+:long (+:long (+:long (get-this-field l) var(x)) (call name(long) (get-this-field b))) (call name(long) (get-this-field s)))
```

```hir
;; hir -O2 class=H
class H (b:byte, s:short, l:long, f:float, c:char)
  (def sum (fn-ref H.sum))
  fn H.sum (x:long) -> long {nothrow unique}
    (block
      (def $cse0 name(long))
      (+:long (+:long (+:long (get-this-field l) var(x)) (call var($cse0) (get-this-field b))) (call var($cse0) (get-this-field s))))
```

## Case 9

```babble
def Scaler = interface (
    def scale : fn (x : long, factor : float) : double
)
def MyScaler = class (def base : long) implements Scaler (
    def scale = fn (x : long, factor : float) : double -> (
        double(this.base + x) * double(factor)
    )
)
def runScaler = fn (sc : Scaler) : double -> ( sc.scale(5L, 2.5f) )
assert(runScaler(MyScaler(3L)) == 20.0)
```

```hir
;; hir -O0,-O1 fn=runScaler
fn runScaler (sc:obj) -> double {pure unique}
  (call-iface var(sc).scale 5:long 2.5:float)
```

```hir
;; hir -O2 fn=runScaler
fn runScaler (sc:obj) -> double {pure unique}
  (call-iface var(sc).scale 5:long 2.5:float)
```

## Case 10

```babble
def op : java.util.function.LongUnaryOperator = fn (x : long) : long -> ( x * 3L + 1L )
assert(op.applyAsLong(10L) == 31L)
def func : java.util.function.ToLongFunction of string = fn (s : string) : long -> ( long(s.length()) * 100L )
assert(func.applyAsLong("hello") == 500L)
```

```hir
;; hir -O0,-O1 fn=op
fn op (x:long) -> long {nothrow injective unique}
  (+:long (*:long var(x) 3:long) 1:long)
```

```hir
;; hir -O2 fn=op
fn op (x:long) -> long {nothrow injective unique}
  (+:long (*:long var(x) 3:long) 1:long)
```

## Case 11

```babble
def unwrapAll = fn (b : byte?, s : short?, l : long?, f : float?, c : char?) : string -> (
    def ub : byte = b!; def us : short = s!; def ul : long = l!; def uf : float = f!; def uc : char = c!
    string(ub) + ":" + string(us) + ":" + string(ul) + ":" + string(uf) + ":" + string(uc)
)
assert(unwrapAll(byte(1), short(2), 3L, 4.5f, 'Q') == "1:2:3:4.5:Q")
```

```hir
;; hir -O0,-O1 fn=unwrapAll
fn unwrapAll (b:obj, s:obj, l:obj, f:obj, c:obj) -> obj {pure unique}
  (def ub (nonnull var(b)))
  (def us (nonnull var(s)))
  (def ul (nonnull var(l)))
  (def uf (nonnull var(f)))
  (def uc (nonnull var(c)))
  (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (call name(string) var(ub)) ":") (call name(string) var(us))) ":") (call name(string) var(ul))) ":") (call name(string) var(uf))) ":") (call name(string) var(uc)))
```

```hir
;; hir -O2 fn=unwrapAll
fn unwrapAll (b:obj, s:obj, l:obj, f:obj, c:obj) -> obj {pure unique}
  (def ub (nonnull var(b)))
  (def us (nonnull var(s)))
  (def ul (nonnull var(l)))
  (def uf (nonnull var(f)))
  (def uc (nonnull var(c)))
  (block
    (def $cse0 name(string))
    (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (+:obj (call var($cse0) var(ub)) ":") (call var($cse0) var(us))) ":") (call var($cse0) var(ul))) ":") (call var($cse0) var(uf))) ":") (call var($cse0) var(uc))))
```

## Case 12

```babble
def capParams = fn (b : byte, s : short, l : long, f : float, c : char) -> (
    fn () : string -> ( string(b) + ":" + string(s) + ":" + string(l) + ":" + string(f) + ":" + string(c) )
)
assert(capParams(byte(1), short(2), 3L, 4.5f, 'Z')() == "1:2:3:4.5:Z")
```

```hir
;; hir -O0,-O1 fn=capParams
fn capParams (b:byte, s:short, l:long, f:float, c:char) -> obj {nothrow}
  (fn-ref <anon#0>)
```

```hir
;; hir -O2 fn=capParams
fn capParams (b:byte, s:short, l:long, f:float, c:char) -> obj {nothrow}
  (fn-ref <anon#0>)
```
