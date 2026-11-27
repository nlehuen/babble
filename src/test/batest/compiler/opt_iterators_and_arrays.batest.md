# Primitive Array Loops, Custom Iterators, and Uninitialized Slots at -O2 (BIP039)

Tests `HirEmitter` and `HirSlotAllocator` lowering of primitive/reference array `for` loops (`BALOAD`/`SALOAD`/`LALOAD`/`FALOAD`/`CALOAD`/`AALOAD`),
custom Babble `Iterable` and `Iterator` `for` loops, swap cycles, and uninitialized primitive/reference locals.

## Narrow numeric array for-loops (byte, short, long, float)

```babble
def sumNum = fn (bs: array of byte, ss: array of short, ls: array of long, fs: array of float): double -> (
    var r = 0.0
    for b in bs then (r += b)
    for s in ss then (r += s)
    for l in ls then (r += l)
    for f in fs then (r += f)
    r
)
assert(sumNum((array of byte).of(byte(1)), (array of short).of(short(2)), (array of long).of(3L), (array of float).of(1.5f)) == 7.5)
```

```hir
;; hir -O0 fn=sumNum
fn sumNum (bs:obj, ss:obj, ls:obj, fs:obj) -> double {nothrow unique}
  (def r 0.0:double)
  (for b in var(bs)
    (block
      (set r += var(b))))
  (for s in var(ss)
    (block
      (set r += var(s))))
  (for l in var(ls)
    (block
      (set r += var(l))))
  (for f in var(fs)
    (block
      (set r += var(f))))
  var(r)
```

```hir
;; hir -O1 fn=sumNum
fn sumNum (bs:obj, ss:obj, ls:obj, fs:obj) -> double {nothrow unique}
  (def r 0.0:double)
  (for b in var(bs)
    (block
      (set r += var(b))))
  (for s in var(ss)
    (block
      (set r += var(s))))
  (for l in var(ls)
    (block
      (set r += var(l))))
  (for f in var(fs)
    (block
      (set r += var(f))))
  var(r)
```

```hir
;; hir -O2 fn=sumNum
fn sumNum (bs:obj, ss:obj, ls:obj, fs:obj) -> double {nothrow unique}
  (def r 0.0:double)
  (for b in var(bs)
    (block
      (set r += var(b))))
  (for s in var(ss)
    (block
      (set r += var(s))))
  (for l in var(ls)
    (block
      (set r += var(l))))
  (for f in var(fs)
    (block
      (set r += var(f))))
  var(r)
```

## Char, bool, and reference array for-loops

```babble
def sumRef = fn (cs: array of char, ps: array of bool, ts: array of string): int -> (
    var r = 0
    for c in cs then (if c == 'a' then (r += 1))
    for p in ps then (if p then (r += 2))
    for t in ts then (r += t.length())
    r
)
assert(sumRef((array of char).of('a'), (array of bool).of(true), (array of string).of("hi")) == 5)
```

```hir
;; hir -O0 fn=sumRef
fn sumRef (cs:obj, ps:obj, ts:obj) -> int {nothrow unique}
  (def r 0:int)
  (for c in var(cs)
    (block
      (if (==:bool var(c) 'a') (box (block (set r += 1:int))))))
  (for p in var(ps)
    (block
      (if var(p) (box (block (set r += 2:int))))))
  (for t in var(ts)
    (block
      (set r += (jcall String.length var(t)))))
  var(r)
```

```hir
;; hir -O1 fn=sumRef
fn sumRef (cs:obj, ps:obj, ts:obj) -> int {nothrow unique}
  (def r 0:int)
  (for c in var(cs)
    (block
      (if (==:bool var(c) 'a') (box (set r += 1:int)))))
  (for p in var(ps)
    (block
      (if var(p) (box (set r += 2:int)))))
  (for t in var(ts)
    (block
      (set r += (jcall String.length var(t)))))
  var(r)
```

```hir
;; hir -O2 fn=sumRef
fn sumRef (cs:obj, ps:obj, ts:obj) -> int {nothrow unique}
  (def r 0:int)
  (for c in var(cs)
    (block
      (if (==:bool var(c) 'a') (box (set r += 1:int)))))
  (for p in var(ps)
    (block
      (if var(p) (box (set r += 2:int)))))
  (for t in var(ts)
    (block
      (set r += (jcall String.length var(t)))))
  var(r)
```

## Babble custom Iterable and Iterator for-loops

```babble
def Cnt = class (k: int) (
  def n = k ; var i = 0
  def hasNext = fn (): bool -> (this.i < this.n)
  def next = fn mut (): int -> (def v = this.i ; this.i += 1 ; v)
)
def Rng = class (k: int) (def n = k ; def iterator = fn (): Cnt -> (Cnt(this.n)))
def sumCustom = fn (): int -> (
  var s = 0
  for x in Rng(3) then (s += x)
  for y in Cnt(3) then (s += y)
  s
)
assert(sumCustom() == 6)
```

```hir
;; hir -O0 fn=sumCustom
fn sumCustom () -> int {pure unique}
  (def s 0:int)
  (for x in (call name(Rng) 3:int)
    (block
      (set s += var(x))))
  (for y in (call name(Cnt) 3:int)
    (block
      (set s += var(y))))
  var(s)
```

```hir
;; hir -O1 fn=sumCustom
fn sumCustom () -> int {pure unique}
  (def s 0:int)
  (for x in (call name(Rng) 3:int)
    (block
      (set s += var(x))))
  (for y in (call name(Cnt) 3:int)
    (block
      (set s += var(y))))
  var(s)
```

```hir
;; hir -O2 fn=sumCustom
fn sumCustom () -> int {pure unique}
  (def s 0:int)
  (for x in (call name(Rng) 3:int)
    (block
      (set s += var(x))))
  (for y in (call name(Cnt) 3:int)
    (block
      (set s += var(y))))
  var(s)
```

## Uninitialized primitive and reference locals with swap-cycle loop

```babble
def initAndSwap = fn (flag: bool): int -> (
    def r = if flag then (0) else (u + t.length())
    def u: int = 2
    def t: string = "xyz"
    var a = u
    var b = t.length()
    var k = 0
    while k < 3 then (def tmp = a ; a = b ; b = tmp ; k += 1)
    a * 10 + b + r
)
assert(initAndSwap(true) == 32)
```

```hir
;; hir -O0 fn=initAndSwap
fn initAndSwap (flag:bool) -> int {nothrow unique}
  (def r (if var(flag) (block 0:int) (block (+:int var(u) (jcall String.length var(t))))))
  (def u 2:int)
  (def t "xyz")
  (def a var(u))
  (def b (jcall String.length var(t)))
  (def k 0:int)
  (while (<:bool var(k) 3:int)
    (block
      (def tmp var(a))
      (set a var(b))
      (set b var(tmp))
      (set k += 1:int)))
  (+:int (+:int (*:int var(a) 10:int) var(b)) var(r))
```

```hir
;; hir -O1 fn=initAndSwap
fn initAndSwap (flag:bool) -> int {nothrow unique}
  (def r (if var(flag) 0:int (+:int var(u) (jcall String.length var(t)))))
  (def u 2:int)
  (def t "xyz")
  (def a var(u))
  (def b (jcall String.length var(t)))
  (def k 0:int)
  (while (<:bool var(k) 3:int)
    (block
      (def tmp var(a))
      (set a var(b))
      (set b var(tmp))
      (set k += 1:int)))
  (+:int (+:int (*:int var(a) 10:int) var(b)) var(r))
```

```hir
;; hir -O2 fn=initAndSwap
fn initAndSwap (flag:bool) -> int {nothrow unique}
  (def r (if var(flag) 0:int (+:int var(u) (jcall String.length var(t)))))
  (def t "xyz")
  (def a 2:int)
  (def b (jcall String.length var(t)))
  (def k 0:int)
  (while (<:bool var(k) 3:int)
    (block
      (def tmp var(a))
      (set a var(b))
      (set b var(tmp))
      (set k += 1:int)))
  (+:int (+:int (*:int var(a) 10:int) var(b)) var(r))
```
