# Constant Folding : If-Pruning and Foldable JDK Calls

References : BIP014-I2, org.babblelang.opt.hir.ConstFoldPass

```babble
;; babble preamble
def idFn of T = fn (x: T): T -> (x)
def recCount = fn (n: int): int -> (if n <= 0 then (0) else (recurse(n - 1) + 1))
def widenSinkD = fn (v: double): double -> (v + 0.5)
```



## Constant if-pruning and convertConstToKind widening

```babble
def f = fn (): bool -> (
  def e = if false then (1)
  def s: short = if true then (byte(7)) else (short(8))
  def i: int = if true then (short(7)) else (8)
  def l: long = if true then (9) else (10L)
  def fl: float = if false then (1.0f) else (float(4))
  def d: double = if true then (5) else (6.5)
  e == null and s == short(7) and i == 7 and l == 9L and fl == 4.0f and d == 5.0
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (def e (if false:bool (box (block 1:int))))
  (def s (if true:bool (widen[short] (block (call name(byte) 7:int))) (block (call name(short) 8:int))))
  (def i (if true:bool (widen[int] (block (call name(short) 7:int))) (block 8:int)))
  (def l (if true:bool (block 9:long) (block 10:long)))
  (def fl (if false:bool (block 1.0:float) (block (call name(float) 4:int))))
  (def d (if true:bool (widen[double] (block 5:int)) (block 6.5:double)))
  (and (and (and (and (and (==:bool var(e) null) (==:bool var(s) (call name(short) 7:int))) (==:bool var(i) 7:int)) (==:bool var(l) 9:long)) (==:bool var(fl) 4.0:float)) (==:bool var(d) 5.0:double))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (def s (widen[short] (call name(byte) 7:int)))
  (def i (widen[int] (call name(short) 7:int)))
  (def fl (call name(float) 4:int))
  (and (and (and (and (and (==:bool null null) (==:bool var(s) (call name(short) 7:int))) true:bool) true:bool) (==:bool var(i) 7:int)) (==:bool var(fl) 4.0:float))
```

```hir
;; hir -O2 fn=f
fn f () -> bool {pure unique}
  (def s (widen[short] (call name(byte) 7:int)))
  (block
    (def $cse0 name(short))
    (def i (widen[int] (call var($cse0) 7:int)))
    (def fl (call name(float) 4:int))
    (and (and (and (and (and (==:bool null null) (==:bool var(s) (call var($cse0) 7:int))) true:bool) true:bool) (==:bool var(i) 7:int)) (==:bool var(fl) 4.0:float)))
```

## Constant if-pruning with folded arithmetic branches and multi-statement blocks

```babble
def f = fn (): bool -> (
    def wL: long = if true then (5 + 1) else (2)
    def wF: float = if true then (3 + 1) else (2)
    def wD: double = if true then (4 + 2) else (1)
    def a = if true then (def x = 2 ; x + 3) else (1)
    def b: double = if false then (1.0) else (4)
    wL == 6L and wF == 4.0f and wD == 6.0 and a == 5 and b == 4.0
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (widen[long] (block (+:int 5:int 1:int))) (block 2:long)))
  (def wF (if true:bool (widen[float] (block (+:int 3:int 1:int))) (widen[float] (block 2:int))))
  (def wD (if true:bool (widen[double] (block (+:int 4:int 2:int))) (widen[double] (block 1:int))))
  (def a
    (if true:bool
      (block
        (def x 2:int)
        (+:int var(x) 3:int))
      (block 1:int)))
  (def b (if false:bool (block 1.0:double) (widen[double] (block 4:int))))
  (and (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 4.0:float)) (==:bool var(wD) 6.0:double)) (==:bool var(a) 5:int)) (==:bool var(b) 4.0:double))
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

## Constant if-pruning with literal/folded branches and target widening

```babble
def f = fn (): bool -> (
    def wL: long = if true then (4 + 2) else (1L)
    def wF: float = if true then (3 + 2) else (1.5f)
    def wD: double = if true then (7 + 1) else (2.5)
    def wStr: string = if true then ("ok") else ("no")
    wL == 6L and wF == 5.0f and wD == 8.0 and wStr == "ok"
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (def wL (if true:bool (widen[long] (block (+:int 4:int 2:int))) (block 1:long)))
  (def wF (if true:bool (widen[float] (block (+:int 3:int 2:int))) (block 1.5:float)))
  (def wD (if true:bool (widen[double] (block (+:int 7:int 1:int))) (block 2.5:double)))
  (def wStr (if true:bool (block "ok") (block "no")))
  (and (and (and (==:bool var(wL) 6:long) (==:bool var(wF) 5.0:float)) (==:bool var(wD) 8.0:double)) (==:bool var(wStr) "ok"))
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

## Constant if-pruning with non-constant widened branches and non-constant if

```babble
def checkIf = fn (p: int): bool -> (
    def wBl: double = if true then (p + 1) else (p + 2)
    def wArg = widenSinkD(if true then (p + 1) else (p + 2))
    def nc = if p > 0 then (1) else (2)
    wBl == 3.0 and wArg == 3.5 and nc == 1
)
assert(checkIf(2))
```

```hir
;; hir -O0 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (if true:bool (widen[double] (block (+:int var(p) 1:int))) (widen[double] (block (+:int var(p) 2:int)))))
  (def wArg (call-fn widenSinkD (if true:bool (widen[double] (block (+:int var(p) 1:int))) (widen[double] (block (+:int var(p) 2:int))))))
  (def nc (if (>:bool var(p) 0:int) (block 1:int) (block 2:int)))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```hir
;; hir -O1 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (def wBl (widen[double] (+:int var(p) 1:int)))
  (def wArg
    (block
      (def v (widen[double] (+:int var(p) 1:int)))
      (+:double var(v) 0.5:double)))
  (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
  (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int))
```

```hir
;; hir -O2 fn=checkIf
fn checkIf (p:int) -> bool {nothrow unique}
  (block
    (def $cse0 (+:int var(p) 1:int))
    (def wBl (widen[double] var($cse0)))
    (def wArg (+:double var(wBl) 0.5:double))
    (def nc (if (>:bool var(p) 0:int) 1:int 2:int))
    (and (and (==:bool var(wBl) 3.0:double) (==:bool var(wArg) 3.5:double)) (==:bool var(nc) 1:int)))
```

## Whitelisted foldable String and Math calls, plus non-folding Math.floorDiv

```babble
def f = fn (): bool -> (
    "  babble  ".trim() == "babble" and "abcd".length() == 4 and "".isEmpty() and
    ("hello" + " world" == "hello world") and
    java.lang.Math.sqrt(9.0) == 3.0 and java.lang.Math.round(2.5) == 3L and
    java.lang.Math.toDegrees(0.0) == 0.0 and java.lang.Math.floorDiv(10, 3) == 3
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {pure unique}
  (and (and (and (and (and (and (and (==:bool (jcall String.trim "  babble  ") "babble") (==:bool (jcall String.length "abcd") 4:int)) (jcall String.isEmpty "")) (block (==:bool (+:obj "hello" " world") "hello world"))) (==:bool (jcall Math.sqrt 9.0:double) 3.0:double)) (==:bool (jcall Math.round 2.5:double) 3:long)) (==:bool (jcall Math.toDegrees 0.0:double) 0.0:double)) (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int))
```

```hir
;; hir -O1 fn=f
fn f () -> bool {pure unique}
  (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int)
```

```hir
;; hir -O2 fn=f
fn f () -> bool {pure unique}
  (==:bool (jcall Math.floorDiv 10:int 3:int) 3:int)
```

## Whitelisted foldable Math.abs/min/max across int, long, float, and double

```babble
def f = fn (): bool -> (
    java.lang.Math.abs(-7) == 7 and java.lang.Math.abs(-9L) == 9L and
    java.lang.Math.abs(-2.5f) == 2.5f and java.lang.Math.abs(-3.5) == 3.5 and
    java.lang.Math.min(3, java.lang.Math.max(1, 2)) == 2 and
    java.lang.Math.min(4L, 9L) == 4L and java.lang.Math.max(1.5f, 2.5f) == 2.5f
)
assert(f())
```

```hir
;; hir -O0 fn=f
fn f () -> bool {nothrow unique}
  (and (and (and (and (and (and (==:bool (jcall Math.abs -7:int) 7:int) (==:bool (jcall Math.abs -9:long) 9:long)) (==:bool (jcall Math.abs -2.5:float) 2.5:float)) (==:bool (jcall Math.abs -3.5:double) 3.5:double)) (==:bool (jcall Math.min 3:int (jcall Math.max 1:int 2:int)) 2:int)) (==:bool (jcall Math.min 4:long 9:long) 4:long)) (==:bool (jcall Math.max 1.5:float 2.5:float) 2.5:float))
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
