# HIR Purity : Calls, Overloads, Interfaces, and Members

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble
;; babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (if this.v < 0 then (return this.v) ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; if this.v < 0 then (return this.v + n) ; this.v + n)
)
```

```babble
;; babble preamble
var tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def volLen = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return n ; n)
```



## HirPurity.allArgsPrimitiveOrImmutable : String.equals on immutable String vs mutable Point

```babble
def run = fn io (n: int): int -> (
    def pt = java.awt.Point(1, 2) ; var a = 0 ; var i = 0
    def u1 = "ab".equals("cd") ; def u2 = "ab".equals(pt)
    while i < n then (
        a += (if "ab".equals("ab") and not "ab".equals(pt) then (1) else (0)) ; i += 1
    ) ; a
)
assert(run(2) == 2)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (def u1 (jcall String.equals "ab" "cd"))
  (def u2 (jcall String.equals "ab" var(pt)))
  (while (<:bool var(i) var(n))
    (block
      (set a += (block (if (and (jcall String.equals "ab" "ab") (not (jcall String.equals "ab" var(pt)))) (block 1:int) (block 0:int))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (jcall String.equals "ab" "ab"))
    (while (<:bool var(i) var(n))
      (block
        (set a += (if (and var($licm0) (not (jcall String.equals "ab" var(pt)))) 1:int 0:int))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {io}
  (def pt (jnew Point 1:int 2:int))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (jcall String.equals "ab" "ab"))
    (while (<:bool var(i) var(n))
      (block
        (set a += (if (and var($licm0) (not (jcall String.equals "ab" var(pt)))) 1:int 0:int))
        (set i += 1:int))))
  var(a)
```

## HirPurity : CoreExceptionRef, boxed Integer arithmetic, and char-vs-nullable equality

```babble
def run = fn (ji: java.lang.Integer, chOpt: char?): int -> (
    def uE = exception ; def uC = ('a' == chOpt)
    def uU = -ji ; def uB1 = ji + 1 ; def uB2 = 1 + ji
    1
)
assert(run(java.lang.Integer.valueOf(2), 'a') == 1)
```

```hir
;; hir -O0 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (def uE core-exception)
  (def uC (block (==:bool 'a' var(chOpt))))
  (def uU (neg var(ji)))
  (def uB1 (+:int var(ji) 1:int))
  (def uB2 (+:int 1:int var(ji)))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (==:bool 'a' var(chOpt))
  1:int
```

```hir
;; hir -O2 fn=run
fn run (ji:int, chOpt:obj) -> int {nothrow unique}
  (==:bool 'a' var(chOpt))
  1:int
```

## HirPurity : OverloadSelect and non-pure-static JavaMethodCall receivers

```babble
def run = fn io (): int -> (
    def ov = overload (fn (x: int): int -> (x), fn (s: string): int -> (s.length()))
    def uOv: fn (int): int = ov
    def m = java.lang.Math ; def uM1 = m.abs(-5)
    def uM2 = (bump() ; java.lang.Math).abs(-5)
    1
)
assert(run() == 1 and tick == 1)
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def ov (overload (fn-ref ov#1) (fn-ref ov#2)))
  (def uOv (overload-select #0 var(ov)))
  (def m (select (select name(java).lang).Math))
  (def uM1 (jcall Math.abs -5:int))
  (def uM2 (jcall Math.abs -5:int))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (select (select name(java).lang).Math)
  (jcall Math.abs -5:int)
  1:int
```

```hir
;; hir -O2 fn=run
fn run () -> int {io}
  (block
    (def $cse0 name(java))
    (select (select var($cse0).lang).Math)
    (jcall Math.abs -5:int)
    1:int)
```

## HirPurity : cross-module DirectThisMethodCall pure, throwing, and volatile paths

```babble
def ext = import("ext_rec.ba")
def run = fn volatile (er: ext.ExtRec): int -> (
    def u1 = er.getPure() ; def u2 = er.getThrow() ; def u3 = er.getVol()
    var a = 0 ; var i = 0
    while i < 2 then (a += er.getPure() + er.getVol() ; i += 1)
    a
)
assert(run(ext.ExtRec(4)) >= 18)
```

```hir
;; hir -O0 fn=run
fn run (er:obj) -> int {volatile unique}
  (def u1 (call-method var(er).getPure))
  (def u2 (call-method var(er).getThrow))
  (def u3 (call-method var(er).getVol))
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (call-method var(er).getPure) (call-method var(er).getVol)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (er:obj) -> int {volatile unique}
  (call-method var(er).getThrow)
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-method var(er).getPure))
    (while (<:bool var(i) 2:int)
      (block
        (set a += (+:int var($licm0) (call-method var(er).getVol)))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (er:obj) -> int {volatile unique}
  (call-method var(er).getThrow)
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-method var(er).getPure))
    (while (<:bool var(i) 2:int)
      (block
        (set a += (+:int var($licm0) (call-method var(er).getVol)))
        (set i += 1:int))))
  var(a)
```

## HirPurity : InterfaceCall on ThisRef, RecordNew, call result, and impure method

```babble
def I = interface (
    def a = fn nothrow (): int -> (1)
    def b = fn nothrow (): int -> (def u = this.a() ; 2)
    def imp = fn io (): int -> (bump())
)
def R = record (x: int) implements I ()
def getI = fn (): I -> (R(3))
def run = fn io (vi: I): int -> (def u1 = R(1).b() ; def u2 = getI().a() ; def u3 = vi.imp() ; 1)
assert(run(R(2)) == 1 and tick == 1)
```

```hir
;; hir -O0 fn=run
fn run (vi:obj) -> int {io}
  (def u1 (call (select (record-new R 1:int).b)))
  (def u2 (call-iface (call-fn getI).a))
  (def u3 (call-iface var(vi).imp))
  1:int
```

```hir
;; hir -O1 fn=run
fn run (vi:obj) -> int {io}
  (call (select (record-new R 1:int).b))
  (call-iface var(vi).imp)
  1:int
```

```hir
;; hir -O2 fn=run
fn run (vi:obj) -> int {io}
  (call (select (record-new R 1:int).b))
  (call-iface var(vi).imp)
  1:int
```

## HirPurity : body-field ThisFieldGet, call-result record receiver, and local RecordNew

```babble
def R = record (x: int) (def y: int = this.x + 1 ; def m = fn (): int -> (def u = this.y ; this.x))
def getR = fn (): R -> (R(3))
def run = fn (): int -> (
    def LR = record (z: int) () ; def u0 = LR(1)
    def u1 = getR().x ; def u2 = getR().m() ; 1
)
assert(run() == 1)
```

```hir
;; hir -O0 fn=run
fn run () -> int {nothrow unique}
  (def LR (class-ref LR))
  (def u0 (record-new LR 1:int))
  (def u1 (get-this-field x))
  (def u2 (call-method (call-fn getR).m))
  1:int
```

```hir
;; hir -O1 fn=run
fn run () -> int {nothrow unique}
  (class-ref LR)
  (block (def $sr_u0_0 1:int))
  (get-this-field x)
  (block
    (def $this (call name(R) 3:int))
    (get-this-field y)
    (get-this-field x))
  1:int
```

```hir
;; hir -O2 fn=run
fn run () -> int {nothrow unique}
  (class-ref LR)
  (block
    (def $cse0 (call name(R) 3:int))
    (block
      (get-this-field y)
      (get-this-field x))
    1:int)
```

## HirPurity : uninitialized Def, captured cell local, capturing closure, Move, and late RecordNew

```babble
tick = 1
def LateR = record (k: int) ()
def fUninit = fn (k: int): int -> (var u: int?=null; u=k+1; u!)
def run = fn io (k: int): int -> (
    def outer = k ; def cl = fn nothrow (): int -> (outer + 1)
    def u0 = LateR(1) ; def u1 = fUninit(k) ; def u2 = cl()
    def u3 = (def cap = 1 ; def c2 = fn (): int -> (cap) ; c2())
    def r = list(2) ; def u4 = move(r) ; u2
)
assert(run(3) == 4)
```

```hir
;; hir -O0 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (def u0 (record-new LateR 1:int))
  (def u1 (call-fn fUninit var(k)))
  (def u2 (call-fn cl))
  (def u3
    (block
      (def cap 1:int)
      (def c2 (fn-ref c2))
      (call-fn c2)))
  (def r (core-new list 2:int))
  (def u4 (move var(r)))
  var(u2)
```

```hir
;; hir -O1 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (block
    name(LateR)
    (def $sr_u0_0 1:int))
  (block
    name(fUninit)
    (set u (box (+:int var(k) 1:int)))
    (nonnull var(u)))
  (def u2 (call-fn cl))
  (core-new list 2:int)
  var(u2)
```

```hir
;; hir -O2 fn=run
fn run (k:int) -> int {io}
  (def outer var(k))
  (def cl (fn-ref cl))
  (block
    name(LateR)
    (def $sr_u0_0 1:int))
  (block
    name(fUninit)
    (set u (box (+:int var(k) 1:int)))
    (nonnull var(u)))
  (def u2 (call-fn cl))
  (core-new list 2:int)
  var(u2)
```

## HirPurity : non-inlined calling method with frame-local mutating closure is not dead code

```babble
def Bag = class (private def xs: list of int = list(1, 2, 3)) (
    def visit = fn calling f (f: fn (int): any?): any? -> (if this.xs.size() == 0 then (return null) ; for x in this.xs then (f(x)))
    def countAbove = fn calling p (p: fn (int): bool): int -> (var m = 0 ; this.visit(fn (x: int) -> (if p(x) then (m += 1))) ; m)
)
assert(Bag().countAbove(fn (x: int): bool -> (x > 1)) == 2)
```

```hir
;; hir -O0,-O1 fn=Bag.countAbove
fn Bag.countAbove (p:obj) -> int {nothrow unique}
  (def m 0:int)
  (call-method this.visit (fn-ref <anon#0>))
  var(m)
```

```hir
;; hir -O2 fn=Bag.countAbove
fn Bag.countAbove (p:obj) -> int {nothrow unique}
  (def m 0:int)
  (call-method this.visit (fn-ref <anon#0>))
  var(m)
```
