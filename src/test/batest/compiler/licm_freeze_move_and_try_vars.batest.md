# LICM Move, Freeze, Unchecked Casts, and Try Modified-Vars

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirLicmPass : DirectThisMethodCall on mutable/frozen class and non-pure-static Math.sin

```babble
def PC = class (def x: int) (def pm = fn nothrow (k: int): int -> (return k + 2 ; k))
def run = fn (n: int, mc: PC, fmc: frozen PC): double -> (
    def mCls = java.lang.Math ; var a = 0.0 ; var i = 0
    while i < n then (a += double(mc.pm(n) + fmc.pm(n)) + mCls.sin(0.0) ; i += 1) ; a
)
assert(run(2, PC(1), freeze(PC(1))) == 16.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (def mCls (select (select name(java).lang).Math))
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (call name(double) (+:int (call-method var(mc).pm var(n)) (call-method var(fmc).pm var(n)))) (jcall Math.sin 0.0:double)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (select (select name(java).lang).Math)
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (+:int var(n) 2:int))
    (block
      (def $licm1 (+:int (+:int var(n) 2:int) var($licm0)))
      (while (<:bool var(i) var(n))
        (block
          (set a += (+:double (call name(double) var($licm1)) 0.0:double))
          (set i += 1:int)))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (select (select name(java).lang).Math)
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $cse0 (+:int var(n) 2:int))
    (def $licm0 (+:int var($cse0) var($cse0)))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:double (call name(double) var($licm0)) 0.0:double))
        (set i += 1:int))))
  var(a)
```

## HirLicmPass : InterfaceCall on mutable, frozen, and record receivers

```babble
def PI = interface (def m = fn nothrow (k: int): int -> (return k + 1 ; k))
def PC = class () implements PI ()
def PR = record (x: int) implements PI ()
def run = fn (n: int, vi: PI, fvi: frozen PI, pr: PR): int -> (
    var a = 0 ; var i = 0
    while i < n then (a += vi.m(n) + fvi.m(n) + pr.m(n) ; i += 1) ; a
)
assert(run(2, PC(), freeze(PC()), PR(1)) == 18)
```

```hir
;; hir -O0 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-iface var(vi).m var(n)) (call-iface var(fvi).m var(n))) (call (select var(pr).m) var(n))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-iface var(fvi).m var(n)))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:int (+:int (call-iface var(vi).m var(n)) var($licm0)) (call (select var(pr).m) var(n))))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-iface var(fvi).m var(n)))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:int (+:int (call-iface var(vi).m var(n)) var($licm0)) (call (select var(pr).m) var(n))))
        (set i += 1:int))))
  var(a)
```

## HirLicmPass and HirPurity : Move, Freeze, and widened transparent Block inside while

```babble
def bm = fn nothrow (s: list of int): int -> (return s.size() ; 0)
def bf = fn nothrow (s: frozen array of int): int -> (return s.size() ; 0)
def run = fn (n: int, x: int): double -> (
  var a = 0.0 ; var i = 0
  while i < n then (
    def s = list(1) ; def r = array.of(1, 2)
    a += double(bm(move(s)) + bf(freeze(r))) + bigSink((x + 1)) ; i += 1
  ) ; a
)
assert(run(2, 3) == 14.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def s (core-new list 1:int))
      (def r (array-new OF 1:int 2:int))
      (set a += (+:double (call name(double) (+:int (call-fn bm (move var(s))) (call-fn bf (freeze var(r))))) (call-fn bigSink (widen[double] (block (+:int var(x) 1:int))))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0
      (block
        (def x#2 (widen[double] (+:int var(x#1) 1:int)))
        var(x#2)))
    (while (<:bool var(i) var(n))
      (block
        (def s#1 (core-new list 1:int))
        (def r (array-new OF 1:int 2:int))
        (set a += (+:double (call name(double) (+:int (block
                  (def s#2 (move var(s#1)))
                  (jcall List.size var(s#2))) (block
                  (def s#3 (freeze var(r)))
                  (array-op SIZE var(s#3))))) var($licm0)))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def s#1 (core-new list 1:int))
      (def r (array-new OF 1:int 2:int))
      (set a += (+:double (call name(double) (+:int (block
                (def s#2 (move var(s#1)))
                (jcall List.size var(s#2))) (block
                (def s#3 (freeze var(r)))
                (array-op SIZE var(s#3))))) (block
            (def x#2 (widen[double] (+:int var(x#1) 1:int)))
            var(x#2))))
      (set i += 1:int)))
  var(a)
```

## HirLicmPass and HirPurity : unchecked As (x as any?) hoisted out of while

```babble
def bigAny = fn nothrow (v: any?): int -> (if same(v, null) then (return 0) ; 1)
def run = fn (n: int, x: int): int -> (
  var a = 0 ; var i = 0
  while i < n then (a += bigAny(x as any?) ; i += 1) ; a
)
assert(run(3, 5) == 3)
```

```stderr
Warning : Redundant cast : any?
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigAny (as var(x) any)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-fn bigAny (as var(x) any)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-fn bigAny (as var(x) any)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

## HirPurity.collectModifiedVars : try with named/unnamed catch and finally defs inside while

```babble
def run = fn (n: int): int -> (
  var s = 0 ; var i = 0
  while i < n then (
    s += try (
      if i < 0 then (throw java.lang.ArithmeticException("x"))
      def t = i + 1 ; t
    ) catch e: java.lang.ArithmeticException then (def c1 = 1 ; c1)
      catch : exception then (def c2 = 2 ; c2)
      finally (def f = 3 ; discard(f))
    i += 1
  ) ; s
)
assert(run(3) == 6)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (block (throw (jnew ArithmeticException "x"))))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            (def c1 1:int)
            var(c1))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (def c2 2:int)
            var(c2))
          (finally
            (def f 3:int)
            (call name(discard) var(f)))))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (throw (jnew ArithmeticException "x")))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            1:int)
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)
          (finally
            null)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (throw (jnew ArithmeticException "x")))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            1:int)
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)
          (finally
            null)))
      (set i += 1:int)))
  var(s)
```
