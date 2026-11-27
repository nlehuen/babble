# HIR Purity : Try/Catch/Finally, Determinism, and Loops

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble
;; babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (return this.v ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return this.v + n ; this.v)
)
```

```babble
;; babble preamble
var tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def volLen = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return n ; n)
```



## HirPurity.isTryPureNothrowTerminating : pure/impure catch and finally before DCE strips catches

```babble
def run = fn io (x: int): int -> (
    def a = (try (x + 1) catch : exception then (x + 2)) + 0
    def b = (try (x + 1) catch : exception then (bump())) + 0
    def c = (try (x + 1) catch : exception then (x + 2) finally (def z = x + 3 ; discard(z))) + 0
    def d = (try (x + 1) finally (discard(bump()))) + 0
    a + b + c + d
)
assert(run(2) == 12 and tick == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {io}
  (def a (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int)))) 0:int))
  (def b (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call-fn bump)))) 0:int))
  (def c (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int))
          (finally
            (def z (+:int var(x) 3:int))
            (call name(discard) var(z))))) 0:int))
  (def d (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (finally
            (call name(discard) (call-fn bump))))) 0:int))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {io}
  (def a (+:int var(x) 1:int))
  (def b (+:int var(x) 1:int))
  (def c
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        null)))
  (def d
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        (call name(discard) (call-fn bump)))))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
```

```hir
;; hir -O2 fn=run
fn run (x:int) -> int {io}
  (def a (+:int var(x) 1:int))
  (def c
    (try
      (block
        var(a))
      (finally
        null)))
  (def d
    (try
      (block
        var(a))
      (finally
        (call name(discard) (call-fn bump)))))
  (+:int (+:int (+:int var(a) var(a)) var(c)) var(d))
```

## HirPurity : top-level namespace-scoped block, try, catch, and finally

```babble
discard((def tb = 1 ; tb))
def r1 = (try (def ta = 1 ; ta) finally (discard(2))) + 0
def r2 = (try (1) finally (def tf = 2 ; discard(tf))) + 0
def r3 = (try (1) catch : exception then (def tc = 2 ; tc)) + 0
assert(r1 == 1 and r2 == 1 and r3 == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 top-level check
contains (def tb 1:int)
count 3 (try
contains (catch
```

```hir
;; hir -O1 top-level
top-level:
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bigInt (fn-ref bigInt))
  (def volLen (fn-ref volLen))
  (block
    (def $try0
      (try
        (block
          1:int)
        (finally
          null)))
    (def r1 var($try0)))
  (block
    (def $try1
      (try
        (block
          1:int)
        (finally
          null)))
    (def r2 var($try1)))
  (def r3 1:int)
  (call name(assert) (and (and (==:bool name(r1) 1:int) (==:bool name(r2) 1:int)) (==:bool name(r3) 1:int)))
```

```hir
;; hir -O2 top-level
top-level:
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bigInt (fn-ref bigInt))
  (def volLen (fn-ref volLen))
  (block
    (def $cse0 name(discard))
    (call var($cse0) 1:int)
    (block
      (def $try0
        (try
          (block
            1:int)
          (finally
            (call var($cse0) 2:int))))
      (def r1 var($try0)))
    (block
      (def $try1
        (try
          (block
            1:int)
          (finally
            (call var($cse0) 2:int))))
      (def r2 var($try1)))
    (def r3 1:int)
    (call name(assert) (and (and (==:bool name(r1) 1:int) (==:bool name(r2) 1:int)) (==:bool name(r3) 1:int))))
```

## HirPurity.isDeterministic : volatile nothrow record and interface methods in while

```babble
def VI = interface (def vt: fn volatile nothrow (): int)
def VR = record (k: int) implements VI (
    def vt = fn volatile nothrow (): int -> (def n = volLen() ; return this.k + n ; this.k)
)
def run = fn volatile (n: int, vr: VR, vi: VI): int -> (
    var a = 0 ; var i = 0
    while i < n then (a += vr.vt() + vi.vt() ; i += 1) ; a
)
assert(run(2, VR(1), VR(1)) >= 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (call-method var(vr).vt) (call-iface var(vi).vt)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field k))
    (while (<:bool var(i) var(n#1))
      (block
        (set a += (+:int (block
              (def n#2
                (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)))
              (+:int var($licm0) var(n#2))) (call-iface var(vi).vt)))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field k))
    (while (<:bool var(i) var(n#1))
      (block
        (set a += (+:int (block
              (def n#2
                (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)))
              (+:int var($licm0) var(n#2))) (call-iface var(vi).vt)))
        (set i += 1:int))))
  var(a)
```

## HirPurity.isDeterministic : volatile nothrow fn, Java call, and nested volatile child in while

```babble
def run = fn volatile (n: int): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += volLen() + java.lang.System.lineSeparator().length() + bigInt(volLen() + volLen())
        i += 1
    ) ; a
)
assert(run(2) >= 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-fn volLen) (jcall String.length (jcall System.lineSeparator))) (call-fn bigInt (+:int (call-fn volLen) (call-fn volLen)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n#1))
    (block
      (set a += (+:int (+:int (block
              (def n#2 (jcall String.length (jcall System.lineSeparator)))
              var(n#2)) (jcall String.length (jcall System.lineSeparator))) (block
            (def x (+:int (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)) (block
                  (def n#4 (jcall String.length (jcall System.lineSeparator)))
                  var(n#4))))
            var(x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n#1))
    (block
      (set a += (+:int (+:int (block
              (def n#2 (jcall String.length (jcall System.lineSeparator)))
              var(n#2)) (jcall String.length (jcall System.lineSeparator))) (block
            (def x (+:int (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)) (block
                  (def n#4 (jcall String.length (jcall System.lineSeparator)))
                  var(n#4))))
            var(x))))
      (set i += 1:int)))
  var(a)
```

## HirPurity : volatile and impure For loops inside blocks

```babble
def run = fn io (n: int, xs: frozen array of int): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += bigInt((for e in xs then (discard(volLen())) ; 1))
        def u = (for e in xs then (discard(bump())) ; 1)
        a += u ; i += 1
    ) ; a
)
assert(run(2, freeze(array.of(1, 2))) == 4 and tick == 4)
```

```hir
;; hir -O0 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigInt (block
            (for e#1 in var(xs)
              (block
                (call name(discard) (call-fn volLen))))
            1:int)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a +=
        (block
          (def x
            (block
              (for e#1 in var(xs)
                (block
                  null))
              1:int))
          var(x)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n#1))
    (block
      (set a +=
        (block
          (block
            (for e#1 in var(xs)
              (block
                (call name(discard) (block
                    (def n#2 (jcall String.length (jcall System.lineSeparator)))
                    var(n#2)))))
            1:int)
          1:int))
      (block
        (for e#2 in var(xs)
          (block
            (call name(discard) (call-fn bump))))
        1:int)
      (set a += 1:int)
      (set i += 1:int)))
  var(a)
```
