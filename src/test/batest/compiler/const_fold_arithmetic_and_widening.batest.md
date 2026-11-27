# Constant Folding : Arithmetic and Primitive Widening

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
```



## Case 1

```babble
def f = fn (): bool -> (
    def b1 = true ; def b2 = false
    ((b1 == b2) or (b1 != b2)) and (b1 == b1) and not (b1 != b1) and
    ('a' < 'b') and ('a' <= 'a') and ('b' > 'a') and ('b' >= 'b') and ('a' == 'a') and ('a' != 'b') and
    not (false and b1) and (true and b1) and (true or b2) and (false or b1) and (not false)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def b1 true:bool)
  (def b2 false:bool)
  (and (and (and (and (and (and (and (and (and (and (and (and (and (block (or (block (==:bool var(b1) var(b2))) (block (<>:bool var(b1) var(b2))))) (block (==:bool var(b1) var(b1)))) (not (block (<>:bool var(b1) var(b1))))) (block (<:bool 'a' 'b'))) (block (<=:bool 'a' 'a'))) (block (>:bool 'b' 'a'))) (block (>=:bool 'b' 'b'))) (block (==:bool 'a' 'a'))) (block (<>:bool 'a' 'b'))) (not (block (and false:bool var(b1))))) (block (and true:bool var(b1)))) (block (or true:bool var(b2)))) (block (or false:bool var(b1)))) (block (not false:bool)))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```hir
;; hir -O2 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Case 2

```babble
def f = fn (): bool -> (
    (-5 < 2) and (2 <= 2) and not (3 <= 2) and (3 > 2) and (3 >= 3) and (4 == 4) and (4 != 5) and not (4 != 4) and
    (-5L < 2L) and (2L <= 2L) and (3L > 2L) and (3L >= 3L) and (4L == 4L) and (4L != 5L) and
    (byte(1) < byte(2)) and (short(1) < short(2))
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -5:int 2:int)) (block (<=:bool 2:int 2:int))) (not (block (<=:bool 3:int 2:int)))) (block (>:bool 3:int 2:int))) (block (>=:bool 3:int 3:int))) (block (==:bool 4:int 4:int))) (block (<>:bool 4:int 5:int))) (not (block (<>:bool 4:int 4:int)))) (block (<:bool -5:long 2:long))) (block (<=:bool 2:long 2:long))) (block (>:bool 3:long 2:long))) (block (>=:bool 3:long 3:long))) (block (==:bool 4:long 4:long))) (block (<>:bool 4:long 5:long))) (block (<:bool (call name(byte) 1:int) (call name(byte) 2:int)))) (block (<:bool (call name(short) 1:int) (call name(short) 2:int))))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (and (<:bool (call name(byte) 1:int) (call name(byte) 2:int)) (<:bool (call name(short) 1:int) (call name(short) 2:int)))
```

```hir
;; hir -O2 fn=f
fn f () -> bool {pure unique}
  (and (<:bool (call name(byte) 1:int) (call name(byte) 2:int)) (<:bool (call name(short) 1:int) (call name(short) 2:int)))
```

## Case 3

```babble
def f = fn (): bool -> (
    (-1.5f < 2.0f) and (2.0f <= 2.0f) and (3.0f > 2.0f) and (3.0f >= 3.0f) and (4.0f == 4.0f) and (4.0f != 5.0f) and
    (-2.5 < 1.5) and (1.5 <= 1.5) and (3.5 > 1.5) and (3.5 >= 3.5) and (4.5 == 4.5) and (4.5 != 5.5)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (and (and (and (and (block (<:bool -1.5:float 2.0:float)) (block (<=:bool 2.0:float 2.0:float))) (block (>:bool 3.0:float 2.0:float))) (block (>=:bool 3.0:float 3.0:float))) (block (==:bool 4.0:float 4.0:float))) (block (<>:bool 4.0:float 5.0:float))) (block (<:bool -2.5:double 1.5:double))) (block (<=:bool 1.5:double 1.5:double))) (block (>:bool 3.5:double 1.5:double))) (block (>=:bool 3.5:double 3.5:double))) (block (==:bool 4.5:double 4.5:double))) (block (<>:bool 4.5:double 5.5:double)))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```hir
;; hir -O2 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Case 4

```babble
def f = fn (): bool -> (
    ((10 + 2 - 4) * 3 == 24) and ((10L + 2L - 4L) * 3L == 24L) and
    (10 / 2 == 5) and (10L / 2L == 5L) and
    ((1.5f + 2.5f - 1.0f) * 2.0f / 3.0f == 2.0f) and
    ((1.5 + 2.5 - 1.0) * 2.0 / 3.0 == 2.0) and
    (-(1.5f + 2.5f) == -4.0f) and (-(1L + 2L) == -3L)
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (and (block (==:bool (*:int (block (-:int (+:int 10:int 2:int) 4:int)) 3:int) 24:int)) (block (==:bool (*:long (block (-:long (+:long 10:long 2:long) 4:long)) 3:long) 24:long))) (block (==:bool (/:double (convert[double] 10:int) (convert[double] 2:int)) (convert[double] 5:int)))) (block (==:bool (/:double (convert[double] 10:long) (convert[double] 2:long)) (convert[double] 5:long)))) (block (==:bool (/:float (*:float (block (-:float (+:float 1.5:float 2.5:float) 1.0:float)) 2.0:float) 3.0:float) 2.0:float))) (block (==:bool (/:double (*:double (block (-:double (+:double 1.5:double 2.5:double) 1.0:double)) 2.0:double) 3.0:double) 2.0:double))) (block (==:bool (neg (block (+:float 1.5:float 2.5:float))) -4.0:float))) (block (==:bool (neg (block (+:long 1:long 2:long))) -3:long)))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

```hir
;; hir -O2 fn=f
fn f () -> bool {nothrow unique}
  true:bool
```

## Local constant propagation across primitive widening chains

```babble
def f = fn (): bool -> (
    def bConst: byte = byte(3)
    def sConst: short = bConst
    def iConst: int = sConst
    def lConst: long = iConst
    def fConst: float = 2.5f
    def dConst: double = fConst
    lConst == 3L and dConst == 2.5
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst (widen[short] var(bConst)))
  (def iConst (widen[int] var(sConst)))
  (def lConst (widen[long] var(iConst)))
  (def fConst 2.5:float)
  (def dConst (widen[double] var(fConst)))
  (and (==:bool var(lConst) 3:long) (==:bool var(dConst) 2.5:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst (widen[short] var(bConst)))
  (def iConst (widen[int] var(sConst)))
  (def lConst (widen[long] var(iConst)))
  (==:bool var(lConst) 3:long)
```

```hir
;; hir -O2 fn=f
fn f () -> bool {pure unique}
  (def bConst (call name(byte) 3:int))
  (def sConst (widen[short] var(bConst)))
  (def iConst (widen[int] var(sConst)))
  (def lConst (widen[long] var(iConst)))
  (==:bool var(lConst) 3:long)
```

## Direct literal widening conversions, string, and null constants

```babble
def f = fn (): bool -> (
    def bs: short = byte(3)
    def sl: long = short(4)
    def il: long = 5
    def ifl: float = 6
    def id: double = 7
    def kStr: string = "hi"
    def kNull: string? = null
    bs == short(3) and sl == 4L and il == 5L and ifl == 6.0f and id == 7.0 and kStr == "hi" and kNull == null
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def bs (widen[short] (call name(byte) 3:int)))
  (def sl (widen[long] (call name(short) 4:int)))
  (def il 5:long)
  (def ifl (widen[float] 6:int))
  (def id (widen[double] 7:int))
  (def kStr "hi")
  (def kNull null)
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) (==:bool var(sl) 4:long)) (==:bool var(il) 5:long)) (==:bool var(ifl) 6.0:float)) (==:bool var(id) 7.0:double)) (==:bool var(kStr) "hi")) (==:bool var(kNull) null))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def bs (widen[short] (call name(byte) 3:int)))
  (def sl (widen[long] (call name(short) 4:int)))
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) true:bool) true:bool) true:bool) true:bool) (==:bool var(sl) 4:long)) (==:bool null null))
```

```hir
;; hir -O2 fn=f
fn f () -> bool {pure unique}
  (def bs (widen[short] (call name(byte) 3:int)))
  (def sl (widen[long] (call name(short) 4:int)))
  (and (and (and (and (and (and (==:bool var(bs) (call name(short) 3:int)) true:bool) true:bool) true:bool) true:bool) (==:bool var(sl) 4:long)) (==:bool null null))
```
