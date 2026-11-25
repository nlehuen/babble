# Uninitialized local, captured cell, and class member access checks

References : BIP001, BIP002 ; targets Frames.defined / checkDefined / checkAssignable, IntCell / DoubleCell / BoolCell, Bootstrap.requireMemberDefined, SelectCache

```babble
;; babble preamble
def B = class (readNow : bool) (
    def readFn = fn (b : B) : int -> ( b.x )
    def x : int = if readNow then ( this.readFn(this) ) else ( 9 )
)
def readB = fn (b : B) : int -> ( b.x )

def safeRead = fn (flag : bool) : int -> (
    def a = if flag then ( x ) else ( 0 )
    def x : int = 7
    a + x
)
```

## Member used before definition in compiled class constructor

```babble
def Box = class () (
    def peek = fn () : int -> ( this.late )
    def a = this.peek()
    def late : int = 5
)
Box()
```

```error
Member used before definition : late
```

```hir
;; hir -O0,-O1 class=Box
class Box
  (def peek (fn-ref Box.peek))
  (def a (call-method this.peek))
  (def late 5:int)
  fn Box.peek () -> int {nothrow unique}
    (get-this-field late)
```

## Case 2

```babble
def ok = B(false)
assert(readB(ok) == 9)
assert(ok.readFn(ok) == 9)
```

```hir
;; hir -O0 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (def ok (call name(B) false:bool))
  (call name(assert) (==:bool (call-fn readB name(ok)) 9:int))
  (call name(assert) (==:bool (call-method name(ok).readFn name(ok)) 9:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (def ok (call name(B) false:bool))
  (call name(assert) (==:bool (block
        (def b#1 name(ok))
        (get-this-field x)) 9:int))
  (call name(assert) (==:bool (block
        (def $this name(ok))
        (def b#2 name(ok))
        (get-this-field x)) 9:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Warm SelectCache on B.x before triggering requireMemberDefined during construction

```babble
def ok = B(false)
assert(ok.readFn(ok) == 9)
B(true)
```

```error
Member used before definition : x
```

```hir
;; hir -O0 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (def ok (call name(B) false:bool))
  (call name(assert) (==:bool (call-method name(ok).readFn name(ok)) 9:int))
  (call name(B) true:bool)
```

```hir
;; hir -O1 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (def ok (call name(B) false:bool))
  (call name(assert) (==:bool (block
        (def $this name(ok))
        (def b name(ok))
        (get-this-field x)) 9:int))
  (call name(B) true:bool)
```

## SelectCache requireMemberDefined on union primitive field on 2nd call (fast path)

```babble
def UB = class (def late : int = 7) ()
def UA = class (def a : int = 1) (
    def peek = fn (u : UA | UB) : int -> ( u.late )
    def ok = this.peek(UB())
    def v = this.peek(if this.a == 1 then ( this ) else ( UB() ))
    def late : int = 5
)
UA()
```

```error
Member used before definition : late
```

```hir
;; hir -O0 class=UA
class UA (a:int=1:int)
  (def peek (fn-ref UA.peek))
  (def ok (call-method this.peek (call name(UB))))
  (def v (call-method this.peek (if (==:bool (get-this-field a) 1:int) (block this) (block (call name(UB))))))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```

```hir
;; hir -O1 class=UA
class UA (a:int=1:int)
  (def peek (fn-ref UA.peek))
  (def ok (call-method this.peek (call name(UB))))
  (def v (call-method this.peek (if (==:bool (get-this-field a) 1:int) this (call name(UB)))))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```

## Reading captured BoolCell before definition

```babble
def f = fn () : fn () : bool -> (
    def r = fn () : bool -> ( b )
    def g = r()
    def b = true
    fn () : bool -> ( g )
)
f()
```

```error
Name used before definition : b
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> obj {nothrow unique}
  (def r (fn-ref r))
  (def g (call-fn r))
  (def b true:bool)
  (fn-ref <anon#0>)
```

## Assigning captured BoolCell before definition

```babble
def f = fn () : bool -> (
    def w = fn () : bool -> ( b = false )
    w()
    def b = true
    b
)
f()
```

```error
Name used before definition : b
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> bool {nothrow unique}
  (def w (fn-ref w))
  (call-fn w)
  (def b true:bool)
  var(b)
```

## Reading captured DoubleCell before definition

```babble
def f = fn () : fn () : double -> (
    def r = fn () : double -> ( d )
    def g = r()
    def d = 1.5
    fn () : double -> ( g )
)
f()
```

```error
Name used before definition : d
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> obj {nothrow unique}
  (def r (fn-ref r))
  (def g (call-fn r))
  (def d 1.5:double)
  (fn-ref <anon#0>)
```

## Assigning captured DoubleCell before definition

```babble
def f = fn () : double -> (
    def w = fn () : double -> ( d = 2.5 )
    w()
    def d = 1.5
    d
)
f()
```

```error
Name used before definition : d
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> double {nothrow unique}
  (def w (fn-ref w))
  (call-fn w)
  (def d 1.5:double)
  var(d)
```

## Assigning captured IntCell before definition

```babble
def f = fn () : int -> (
    def w = fn () : int -> ( i = 9 )
    w()
    def i = 1
    i
)
f()
```

```error
Name used before definition : i
```

```hir
;; hir -O0,-O1 fn=f
fn f () -> int {nothrow unique}
  (def w (fn-ref w))
  (call-fn w)
  (def i 1:int)
  var(i)
```

## Reading uncaptured reference local before definition

```babble
def readRef = fn () : string -> (
    def x = s
    def s = "hi"
    x
)
readRef()
```

```error
Name used before definition : s
```

```hir
;; hir -O0,-O1 fn=readRef
fn readRef () -> obj {nothrow unique}
  (def x var(s))
  (def s "hi")
  var(x)
```

## Assigning uncaptured reference local before definition

```babble
def assignRef = fn () : string -> (
    s = "ok"
    def s = "hi"
    s
)
assignRef()
```

```error
Name used before definition : s
```

```hir
;; hir -O0,-O1 fn=assignRef
fn assignRef () -> obj {nothrow unique}
  (set s "ok")
  (def s "hi")
  var(s)
```

## Case 12

```babble
assert(safeRead(false) == 7)
```

```hir
;; hir -O0 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (call name(assert) (==:bool (call-fn safeRead false:bool) 7:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (call name(assert) (==:bool (block
        (def x 7:int)
        var(x)) 7:int))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## Uncaptured int local read before definition

```babble
safeRead(true)
```

```error
Name used before definition : x
```

```hir
;; hir -O0 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (call-fn safeRead true:bool)
```

```hir
;; hir -O1 top-level
top-level:
  (def B (class-ref B))
  (def readB (fn-ref readB))
  (def safeRead (fn-ref safeRead))
  (block
    (def a var(x))
    (def x 7:int)
    (+:int var(a) var(x)))
```

## Uncaptured double local read before definition

```babble
def badDouble = fn (flag : bool) : double -> (
    def a = if flag then ( x ) else ( 0.0 )
    def x : double = 2.5
    a + x
)
badDouble(true)
```

```error
Name used before definition : x
```

```hir
;; hir -O0 fn=badDouble
fn badDouble (flag:bool) -> double {nothrow unique}
  (def a (if var(flag) (block var(x)) (block 0.0:double)))
  (def x 2.5:double)
  (+:double var(a) var(x))
```

```hir
;; hir -O1 fn=badDouble
fn badDouble (flag:bool) -> double {nothrow unique}
  (def a (if var(flag) var(x) 0.0:double))
  (def x 2.5:double)
  (+:double var(a) var(x))
```

## Uncaptured bool local read before definition

```babble
def badBool = fn (flag : bool) : bool -> (
    def a = if flag then ( x ) else ( false )
    def x : bool = true
    a or x
)
badBool(true)
```

```error
Name used before definition : x
```

```hir
;; hir -O0 fn=badBool
fn badBool (flag:bool) -> bool {nothrow idempotent unique}
  (def a (if var(flag) (block var(x)) (block false:bool)))
  (def x true:bool)
  (or var(a) var(x))
```

```hir
;; hir -O1 fn=badBool
fn badBool (flag:bool) -> bool {nothrow idempotent unique}
  (def a (if var(flag) var(x) false:bool))
  (def x true:bool)
  (or var(a) var(x))
```

## SelectCache requireMemberDefined passes on warmed union primitive field

```babble
def UB = class (def late: int = 7) ()
def UA = class (early: bool) (
    def peek = fn (u: UA | UB): int -> ( u.late )
    def v = if early then ( this.peek(this) ) else ( 0 )
    def late: int = 5
)
def a = UA(false)
assert(a.peek(a) == 5 and a.peek(a) == 5)
```

```hir
;; hir -O0 class=UA
class UA (early:bool)
  (def peek (fn-ref UA.peek))
  (def v (if var(early) (block (call-method this.peek this)) (block 0:int)))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```

```hir
;; hir -O1 class=UA
class UA (early:bool)
  (def peek (fn-ref UA.peek))
  (def v (if var(early) (call-method this.peek this) 0:int))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```

```mir
;; mir -O2 class=UA stage=before,final
class UA:
  fn UA.peek(v0: any) -> int:
    bb0:
      v1: any = opaque.DynamicSelect(v0.late) {impure}
      v2: int = convert.int v1
      ret v2
```

## SelectCache requireMemberDefined throws on warmed union primitive field during construction

```babble
def UB = class (def late: int = 7) ()
def UA = class (early: bool) (
    def peek = fn (u: UA | UB): int -> ( u.late )
    def v = if early then ( this.peek(this) ) else ( 0 )
    def late: int = 5
)
def warm = UA(false)
assert(warm.peek(warm) == 5)
UA(true)
```

```error
Member used before definition : late
```

```hir
;; hir -O0 class=UA
class UA (early:bool)
  (def peek (fn-ref UA.peek))
  (def v (if var(early) (block (call-method this.peek this)) (block 0:int)))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```

```hir
;; hir -O1 class=UA
class UA (early:bool)
  (def peek (fn-ref UA.peek))
  (def v (if var(early) (call-method this.peek this) 0:int))
  (def late 5:int)
  fn UA.peek (u:obj) -> int {nothrow unique}
    (select var(u).late)
```
