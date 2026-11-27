# BIP034-I3 pure name_read, exception-guarded CSE, and immutable class fields

## Header defs after pure static block and Java import lower to pure name_read

```babble
def jl = import("java.lang")
def Vec2 = record (x: double, y: double) () static (
    def zero = this(0.0, 0.0)
)
def origin = Vec2.zero
def scale = 2.0
var bias = 1.0
def makeScaled = fn volatile (a: double): Vec2 -> (
    Vec2(a * scale + bias + origin.x, jl.Math.abs(a))
)
assert(makeScaled(-3.0).x == -5.0 and makeScaled(-3.0).y == 3.0)
```

```hir
;; hir -O0,-O1 fn=makeScaled
fn makeScaled (a:double) -> obj {volatile nothrow unique}
  (record-new Vec2 (+:double (+:double (*:double var(a) name(scale)) name(bias)) (get-this-field x)) (jcall Math.abs var(a)))
```

```hir
;; hir -O2 fn=makeScaled
fn makeScaled (a:double) -> obj {volatile nothrow unique}
  (record-new Vec2 (+:double (+:double (*:double var(a) name(scale)) name(bias)) (get-this-field x)) (jcall Math.abs var(a)))
```

## Pure name_read CSEs across io calls and hoists out of io loops

```babble
def Box = record (v: int) ()
var calls = 0
def touch = fn io (b: Box): int -> ( calls += 1 ; b.v )
def buildMany = fn io (n: int): int -> (
    var acc = 0 ; var i = 0
    while i < n then (
        acc += touch(Box(i)) + touch(Box(i + 1))
        i += 1
    )
    acc
)
assert(buildMany(3) == 9 and calls == 6)
```

```hir
;; hir -O0 fn=buildMany
fn buildMany (n:int) -> int {io}
  (def acc 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (call-fn touch (record-new Box var(i))) (call-fn touch (record-new Box (+:int var(i) 1:int)))))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=buildMany
fn buildMany (n:int) -> int {io}
  (def acc 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (call-fn touch$unboxed var(i)) (call-fn touch$unboxed (+:int var(i) 1:int))))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O2 fn=buildMany
fn buildMany (n:int) -> int {io}
  (def acc 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def $cse0 name(touch))
      (set acc += (+:int (call-fn touch$unboxed var(i)) (call-fn touch$unboxed (+:int var(i) 1:int))))
      (set i += 1:int)))
  var(acc)
```

## Late def after arbitrary top-level call is pure-may-throw with dominated CSE and try-catch guard

```babble
def s = fn io (): int -> ( 1 )
def _i = s()
def lv = 10
def sumLate = fn (): int -> ( lv + lv )
def guardedLate = fn (f: bool): int -> (
    var o = 0
    try ( if f then ( o = lv ) ; if not f then ( throw exception("x") ) ; o += lv ) catch e then ( o = -1 )
    o
)
assert(sumLate() == 20 and guardedLate(true) == 20 and guardedLate(false) == -1)
```

```hir
;; hir -O0,-O1 fn=sumLate
fn sumLate () -> int {nothrow unique}
  (+:int name(lv) name(lv))
```

```hir
;; hir -O2 fn=sumLate
fn sumLate () -> int {nothrow unique}
  (block
    (def $cse0 name(lv))
    (+:int var($cse0) var($cse0)))
```

```hir
;; hir -O0 fn=guardedLate
fn guardedLate (f:bool) -> int {nothrow unique}
  (def o 0:int)
  (try
    (block
      (if var(f) (box (block (set o name(lv)))))
      (if (not var(f)) (block (throw (jnew BabbleException "x"))))
      (set o += name(lv)))
    (catch e
      (set o -1:int)))
  var(o)
```

```hir
;; hir -O1 fn=guardedLate
fn guardedLate (f:bool) -> int {nothrow unique}
  (def o 0:int)
  (try
    (block
      (if var(f) (box (set o name(lv))))
      (if (not var(f)) (throw (jnew BabbleException "x")))
      (set o += name(lv)))
    (catch e
      (set o -1:int)))
  var(o)
```

```hir
;; hir -O2 fn=guardedLate
fn guardedLate (f:bool) -> int {nothrow unique}
  (def o 0:int)
  (try
    (block
      (if var(f) (box (set o name(lv))))
      (if (not var(f)) (throw (jnew BabbleException "x")))
      (set o += name(lv)))
    (catch e
      (set o -1:int)))
  var(o)
```

## Premature read of late def during active static block still throws TDZ error

```babble
def boom = fn (): int -> ( afterStatic )
def Trigger = class () () static (
    def ran = boom()
)
def afterStatic = 42
```

```error
Name used before definition : afterStatic
```

```hir
;; hir -O0,-O1 fn=boom
fn boom () -> int {nothrow unique}
  name(afterStatic)
```

```hir
;; hir -O2 fn=boom
fn boom () -> int {nothrow unique}
  name(afterStatic)
```

## Regular class def header field is read:frozen and hoisted while var field stays read:shared

```babble
def Counter = class (def step: int, var bonus: int) (
    def run = fn mut (n: int): int -> (
        var i = 0
        var acc = 0
        while i < n then (
            acc += this.step * 2 + this.bonus
            this.bonus += 1
            i += 1
        )
        acc
    )
)
def c = Counter(5, 1)
assert(c.run(3) == 36 and c.bonus == 4)
```

```hir
;; hir -O0 fn=Counter.run
fn Counter.run (n:int) -> int {nothrow mut unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (*:int (get-this-field step) 2:int) (get-this-field bonus)))
      (set-this-field bonus += 1:int)
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=Counter.run
fn Counter.run (n:int) -> int {nothrow mut unique}
  (def i 0:int)
  (def acc 0:int)
  (block
    (def $licm0 (*:int (get-this-field step) 2:int))
    (while (<:bool var(i) var(n))
      (block
        (set acc += (+:int var($licm0) (get-this-field bonus)))
        (set-this-field bonus += 1:int)
        (set i += 1:int))))
  var(acc)
```

```hir
;; hir -O2 fn=Counter.run
fn Counter.run (n:int) -> int {nothrow mut unique}
  (def i 0:int)
  (def acc 0:int)
  (block
    (def $licm0 (*:int (get-this-field step) 2:int))
    (while (<:bool var(i) var(n))
      (block
        (set acc += (+:int var($licm0) (get-this-field bonus)))
        (set-this-field bonus += 1:int)
        (set i += 1:int))))
  var(acc)
```

## Self-class definite initialization makes methods of late classes read self purely

```babble
def log = java.lang.StringBuilder()
def kick = fn io (): int -> ( log.append("k") ; 1 )
def _ran = kick()
def Node = class (def v: int) (
    def next = fn (): Node -> ( Node(this.v + 1) )
)
assert(Node(4).next().v == 5)
```

```hir
;; hir -O0,-O1 fn=Node.next
fn Node.next () -> obj {nothrow unique}
  (call name(Node) (+:int (get-this-field v) 1:int))
```

```hir
;; hir -O2 fn=Node.next
fn Node.next () -> obj {nothrow unique}
  (call name(Node) (+:int (get-this-field v) 1:int))
```
