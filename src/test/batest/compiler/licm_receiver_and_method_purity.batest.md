# LICM Receiver Purity, Frozen Fields, and Method Calls

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## LICM in for-loop (Logic, DIV, Block) and DCE of unused MakeOverloadSet

```babble
def run = fn (a: int, b: int, flag: bool, xs: list of int): double -> (
  def unusedOv = overload (fn (n: int): int -> (n + 1), fn (s: string): int -> (s.length()))
  var acc = 0.0
  for e in xs then (if flag and (a > 0) then (acc += (a / b) + (a + b) + e))
  acc
)
assert(run(10, 2, true, list(1, 2, 3)) == 57.0)
```

```hir
;; hir -O0 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def unusedOv (overload (fn-ref unusedOv#1) (fn-ref unusedOv#2)))
  (def acc 0.0:double)
  (for e in var(xs)
    (block
      (if (and var(flag) (block (>:bool var(a) 0:int))) (box (block (set acc += (+:double (+:double (block (/:double (convert[double] var(a)) (convert[double] var(b)))) (convert[double] (block (+:int var(a) var(b))))) (convert[double] var(e)))))))))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def acc 0.0:double)
  (block
    (def $licm0 (+:double (/:double (convert[double] var(a)) (convert[double] var(b))) (convert[double] (+:int var(a) var(b)))))
    (for e in var(xs)
      (block
        (if (and var(flag) (>:bool var(a) 0:int)) (box (set acc += (+:double var($licm0) (convert[double] var(e)))))))))
  var(acc)
```

```hir
;; hir -O2 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def acc 0.0:double)
  (block
    (def $licm0 (+:double (/:double (convert[double] var(a)) (convert[double] var(b))) (convert[double] (+:int var(a) var(b)))))
    (for e in var(xs)
      (block
        (if (and var(flag) (>:bool var(a) 0:int)) (box (set acc += (+:double var($licm0) (convert[double] var(e)))))))))
  var(acc)
```

## HirPurity.isDeterministic : loop-invariant non-static String.length, If, FunctionRef, CoreCollectionRef

```babble
def useRef = fn nothrow (f: fn nothrow (int): int, c: any): int -> (return f(1) ; 0)
def run = fn (s: string, flag: bool, n: int): int -> (
  var i = 0 ; var acc = 0
  while i < n then (
    acc += s.length() + (if flag then (1) else (2)) + useRef(fn nothrow (x: int): int -> (x + 1), list)
    i += 1
  )
  acc
)
assert(run("abc", true, 3) == 18)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, flag:bool, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (+:int (jcall String.length var(s)) (block (if var(flag) (block 1:int) (block 2:int)))) (call-fn useRef (fn-ref <anon#0>) core-col(list))))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (s:obj, flag:bool, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (+:int (jcall String.length var(s)) (if var(flag) 1:int 2:int)) (+:int 1:int 1:int)))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O2 fn=run
fn run (s:obj, flag:bool, n:int) -> int {nothrow unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (+:int (+:int (jcall String.length var(s)) (if var(flag) 1:int 2:int)) 2:int))
      (set i += 1:int)))
  var(acc)
```

## HirEGraphSimplifyPass : byte/short constant widening and char/bool comparisons

```babble
def run = fn (p: byte): bool -> (
  def s: short = if true then (byte(3)) else (p)
  def c1 = 'a' < 'b' and 'a' <= 'a' and 'b' > 'a' and 'b' >= 'b' and 'a' != 'b'
  def b1 = (true == true) and (true != false) and not (false == true)
  s == short(3) and c1 and b1
)
assert(run(byte(1)))
```

```hir
;; hir -O0 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (if true:bool (widen[short] (block (call name(byte) 3:int))) (widen[short] (block var(p)))))
  (def c1 (and (and (and (and (<:bool 'a' 'b') (<=:bool 'a' 'a')) (>:bool 'b' 'a')) (>=:bool 'b' 'b')) (<>:bool 'a' 'b')))
  (def b1 (and (and (block (==:bool true:bool true:bool)) (block (<>:bool true:bool false:bool))) (not (block (==:bool false:bool true:bool)))))
  (and (and (==:bool var(s) (call name(short) 3:int)) var(c1)) var(b1))
```

```hir
;; hir -O1 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (widen[short] (call name(byte) 3:int)))
  (and (and (==:bool var(s) (call name(short) 3:int)) true:bool) true:bool)
```

```hir
;; hir -O2 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (widen[short] (call name(byte) 3:int)))
  (and (and (==:bool var(s) (call name(short) 3:int)) true:bool) true:bool)
```

## Canonical HIR golden : loop-invariant Math.cos hoisted to preheader (-O0 vs -O1)

```babble
def sumScaled = fn (n: int, angle: double): double -> (
    var i = 0
    var acc = 0.0
    while i < n then (
        acc += java.lang.Math.cos(angle) * 2.0
        i += 1
    )
    acc
)
assert(sumScaled(4, 0.0) == 8.0)
```

```hir
;; hir -O0 fn=sumScaled
fn sumScaled (n:int, angle:double) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (while (<:bool var(i) var(n))
    (block
      (set acc += (*:double (jcall Math.cos var(angle)) 2.0:double))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=sumScaled
fn sumScaled (n:int, angle:double) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (jcall Math.cos var(angle)) 2.0:double))
    (while (<:bool var(i) var(n))
      (block
        (set acc += var($licm0))
        (set i += 1:int))))
  var(acc)
```

```hir
;; hir -O2 fn=sumScaled
fn sumScaled (n:int, angle:double) -> double {nothrow unique}
  (def i 0:int)
  (def acc 0.0:double)
  (block
    (def $licm0 (*:double (jcall Math.cos var(angle)) 2.0:double))
    (while (<:bool var(i) var(n))
      (block
        (set acc += var($licm0))
        (set i += 1:int))))
  var(acc)
```

## HirLicmPass : frozen class field read and unary negation

```babble
def FC = class (def v: int) ()
def run = fn (n: int, fc: frozen FC): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += fc.v + (-fc.v) + 1
        i += 1
    ) ; a
)
assert(run(3, freeze(FC(5))) == 3)
```

```hir
;; hir -O0 fn=run
fn run (n:int, fc:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (get-this-field v) (block (neg (get-this-field v)))) 1:int))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, fc:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (+:int (+:int (get-this-field v) (neg (get-this-field v))) 1:int))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (n:int, fc:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field v))
    (block
      (def $licm1 (+:int (+:int var($licm0) (neg var($licm0))) 1:int))
      (while (<:bool var(i) var(n))
        (block
          (set a += var($licm1))
          (set i += 1:int)))))
  var(a)
```

## HirLicmPass : captured this (cell ThisRef) and immediately-invoked FunctionRef callee

```babble
def CR = record (x: int) (
    def run = fn (n: int): int -> (
        def cap = fn (): int -> (this.x)
        var a = 0 ; var i = 0
        while i < n then (
            a += this.x + (fn nothrow (k: int): int -> (return k + 1 ; k))(n)
            i += 1
        )
        a + cap()
    )
)
assert(CR(3).run(2) == 15)
```

```hir
;; hir -O0 fn=CR.run
fn CR.run (n:int) -> int {nothrow unique}
  (def cap (fn-ref cap))
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (get-this-field x) (call-fn <anon#0> var(n))))
      (set i += 1:int)))
  (+:int var(a) (call-fn cap))
```

```hir
;; hir -O1 fn=CR.run
fn CR.run (n:int) -> int {nothrow unique}
  (def cap (fn-ref cap))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (+:int (get-this-field x) (+:int var(n) 1:int)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  (+:int var(a) (call-fn cap))
```

```hir
;; hir -O2 fn=CR.run
fn CR.run (n:int) -> int {nothrow unique}
  (def cap (fn-ref cap))
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (+:int (get-this-field x) (+:int var(n) 1:int)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  (+:int var(a) (call-fn cap))
```
