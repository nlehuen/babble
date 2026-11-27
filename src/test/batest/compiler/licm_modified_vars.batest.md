# LICM Modified-Variable Collection Across Control Flow and Calls

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirPurity.collectModifiedVars : array.set, field/Java writes, and nested for loop

```babble
def Sub = class (var f: int) ()
def run = fn io (): int -> (
  var a = 0 ; def s = Sub(4) ; def pt = java.awt.Point(2, 3) ; var i = 0
  while i < 2 then (
    def a1 = (array of int)(2) ; a1.set(0, i + 1) ; s.f = 5 ; pt.x = 7
    for v in a1 then (a += v)
    a += pt.x + s.f ; i += 1
  ) ; a
)
assert(run() == 27)
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def s (call name(Sub) 4:int))
  (def pt (jnew Point 2:int 3:int))
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def a1 (array-new SIZE 2:int))
      (array-op SET var(a1) 0:int (+:int var(i) 1:int))
      (set-field var(s).f 5:int)
      (set-jfield x var(pt) 7:int)
      (for v in var(a1)
        (block
          (set a += var(v))))
      (set a += (+:int (get-jfield Point.x var(pt)) (get-this-field f)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run () -> int {io}
  (def a 0:int)
  (def s (call name(Sub) 4:int))
  (def pt (jnew Point 2:int 3:int))
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def a1 (array-new SIZE 2:int))
      (array-op SET var(a1) 0:int (+:int var(i) 1:int))
      (set-field var(s).f 5:int)
      (set-jfield x var(pt) 7:int)
      (for v in var(a1)
        (block
          (set a += var(v))))
      (set a += (+:int (get-jfield Point.x var(pt)) (get-this-field f)))
      (set i += 1:int)))
  var(a)
```

## HirPurity.collectModifiedVars : super, DirectThisMethodCall, and InterfaceCall inside while

```babble
def I = interface (def g = fn (x: int): int -> (x + 1))
def S = class (var f: int) implements I (
  def m = fn (x: int): int -> (this.f + x)
  def s = fn (x: int): int -> (var a = 0 ; var i = 0 ; while i < 2 then (a += super.g(x) + this.m(x) ; i += 1) ; a)
)
def r = fn (v: I): int -> (var a = 0 ; var i = 0 ; while i < 2 then (a += v.g(1) ; i += 1) ; a)
assert(S(5).s(1) == 16 and r(S(5)) == 4)
```

```hir
;; hir -O0 fn=S.s
fn S.s (x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (super.g var(x)) (call-method this.m var(x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=S.s
fn S.s (x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (super.g var(x)) (+:int (get-this-field f) var(x))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=S.s
fn S.s (x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (super.g var(x)) (+:int (get-this-field f) var(x))))
      (set i += 1:int)))
  var(a)
```

## HirPurity.collectModifiedVars : is, as, and safe-nav (?.) inside while

```babble
def S = class (var f: int) (def m = fn (x: int): int -> (this.f + x))
def run = fn (s: S, o: S?, d: any): int -> (
  var a = 0 ; var i = 0
  while i < 2 then (a += (d as S).m(1) + (if (s is S) and (o?.m(1) != null) then (1) else (0)) ; i += 1) ; a
)
assert(run(S(5), S(5), S(5)) == 14)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, o:obj, d:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (call-method (block (as var(d) babble|S)).m 1:int) (block (if (and (block (is var(s) S)) (block (<>:bool (safe-call var(o)?.m 1:int) null))) (block 1:int) (block 0:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, o:obj, d:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (block
            (def $this (as var(d) babble|S))
            (+:int (get-this-field f) 1:int)) (if (and (is var(s) S) (<>:bool (safe-call var(o)?.m 1:int) null)) 1:int 0:int)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (s:obj, o:obj, d:obj) -> int {pure unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (set a += (+:int (block
            (def $this (as var(d) babble|S))
            (+:int (get-this-field f) 1:int)) (if (and (is var(s) S) (<>:bool (safe-call var(o)?.m 1:int) null)) 1:int 0:int)))
      (set i += 1:int)))
  var(a)
```

## HirPurity.collectModifiedVars : top-level/this writes, !, generic TypeApply

```babble
var topM = 0 ; topM = 1
def bigId of T = fn nothrow (x: T): T -> (def f = fn (): int -> (0) ; discard(f) ; x)
def MC = class (var mf: int) (
  def run = fn mut io (n: int): int -> (
    var i = 0 ; var s = 0
    while i < n then (
      topM += 1 ; this.mf += 1 ; def os: string? = "ab"
      s += this.mf + os!.length() + (bigId of int)(4) ; i += 1
    ) ; s + topM
  )
)
assert(MC(1).run(2) == 20)
```

```hir
;; hir -O0 fn=MC.run
fn MC.run (n:int) -> int {io mut}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set-name topM += 1:int)
      (set-this-field mf += 1:int)
      (def os "ab")
      (set s += (+:int (+:int (get-this-field mf) (jcall String.length (nonnull var(os)))) (call (block (type-apply name(bigId))) 4:int)))
      (set i += 1:int)))
  (+:int var(s) name(topM))
```

```hir
;; hir -O1 fn=MC.run
fn MC.run (n:int) -> int {io mut}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set-name topM += 1:int)
      (set-this-field mf += 1:int)
      (set s += (+:int (+:int (get-this-field mf) (jcall String.length (nonnull "ab"))) (call (type-apply name(bigId)) 4:int)))
      (set i += 1:int)))
  (+:int var(s) name(topM))
```

```hir
;; hir -O2 fn=MC.run
fn MC.run (n:int) -> int {io mut}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set-name topM += 1:int)
      (set-this-field mf += 1:int)
      (set s += (+:int (+:int (get-this-field mf) (jcall String.length (nonnull "ab"))) (call (type-apply name(bigId)) 4:int)))
      (set i += 1:int)))
  (+:int var(s) name(topM))
```

## HirPurity.collectModifiedVars : nested fn, class, interface, StringBuilder, list, and is check inside while

```babble
def run = fn io (): int -> (
  var i = 0 ; var s = 0
  while i < 2 then (
    def sb = java.lang.StringBuilder("x") ; def fnL = fn (z: int): int -> (z + 1)
    def ClsL = class (def v: int) () ; def IfcL = interface () ; def av: any = sb
    s += sb.length() + list(1, 2).size() + fnL(1) + ClsL(3).v + (if av is IfcL then (1) else (0)) ; i += 1
  ) ; s
)
assert(run() == 16)
```

```hir
;; hir -O0 fn=run
fn run () -> int {io}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def sb (jnew StringBuilder "x"))
      (def fnL (fn-ref fnL))
      (def ClsL (class-ref ClsL))
      (def IfcL (iface-ref IfcL))
      (def av var(sb))
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) (call-fn fnL 1:int)) (get-this-field v)) (block (if (is var(av) babble|IfcL) (block 1:int) (block 0:int)))))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run () -> int {io}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def sb (jnew StringBuilder "x"))
      (def ClsL (class-ref ClsL))
      (iface-ref IfcL)
      (def av var(sb))
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) 2:int) (get-this-field v)) (if (is var(av) babble|IfcL) 1:int 0:int)))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O2 fn=run
fn run () -> int {io}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 2:int)
    (block
      (def sb (jnew StringBuilder "x"))
      (def ClsL (class-ref ClsL))
      (iface-ref IfcL)
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) 2:int) (get-this-field v)) (if (is var(sb) babble|IfcL) 1:int 0:int)))
      (set i += 1:int)))
  var(s)
```
