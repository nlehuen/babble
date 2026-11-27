# LICM Operand Invariance, Blocks, Casts, and Overloads

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```



## HirPurity.collectModifiedVars : SelfRecurseCall and nested while inside a while loop

```babble
def recWhile = fn (n: int): int -> (
  var i = 0 ; var s = 0
  while i < 1 then (
    var j = 0 ; while j < 2 then (j += 1)
    if n > 0 then (s += recurse(n - 1) + 1) ; s += j ; i += 1
  )
  s
)
assert(recWhile(2) == 8)
```

```hir
;; hir -O0 fn=recWhile
fn recWhile (n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 1:int)
    (block
      (def j 0:int)
      (while (<:bool var(j) 2:int)
        (block (set j += 1:int)))
      (if (>:bool var(n) 0:int) (box (block (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int)))))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=recWhile
fn recWhile (n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 1:int)
    (block
      (def j 0:int)
      (while (<:bool var(j) 2:int)
        (set j += 1:int))
      (if (>:bool var(n) 0:int) (box (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int))))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O2 fn=recWhile
fn recWhile (n:int) -> int {nothrow unique}
  (def i 0:int)
  (def s 0:int)
  (while (<:bool var(i) 1:int)
    (block
      (def j 0:int)
      (while (<:bool var(j) 2:int)
        (set j += 1:int))
      (if (>:bool var(n) 0:int) (box (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int))))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

## HirLicmPass.checkOperandsInvariant & HirPurity.isDeterministic : Logic, Unary.NOT, Block, and ArrayOp.SIZE

```babble
def bigBool = fn nothrow (b: bool): int -> (def r = if b then (1) else (0) ; return r ; r)
def run = fn (f: bool, i: int, arr: frozen array of int): int -> (
  var a = 0 ; var k = 0
  while k < 2 then (
    a += bigBool(f and (i > 0)) + bigBool(not f) + bigInt((discard(i + 1) ; i + 2)) + bigInt(arr.size()) ; k += 1
  ) ; a
)
assert(run(true, 3, freeze(array.of(1, 2))) == 16)
```

```hir
;; hir -O0 fn=run
fn run (f:bool, i:int, arr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (+:int (+:int (call-fn bigBool (and var(f) (block (>:bool var(i) 0:int)))) (call-fn bigBool (not var(f)))) (call-fn bigInt (block
                (call name(discard) (+:int var(i) 1:int))
                (+:int var(i) 2:int)))) (call-fn bigInt (array-op SIZE var(arr)))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (f:bool, i:int, arr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int (+:int (block
            (def b#1 (and var(f) (>:bool var(i) 0:int)))
            (def r#1 (if var(b#1) 1:int 0:int))
            var(r#1)) (block
            (def b#2 (not var(f)))
            (def r#2 (if var(b#2) 1:int 0:int))
            var(r#2))) (block
          (def x#1 (+:int var(i) 2:int))
          var(x#1))))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:int var($licm0) (block
              (def x#2 (array-op SIZE var(arr)))
              var(x#2))))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (f:bool, i:int, arr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:int (+:int (+:int (block
                (def b#1 (and var(f) (>:bool var(i) 0:int)))
                (def r#1 (if var(b#1) 1:int 0:int))
                var(r#1)) (block
                (def b#2 (not var(f)))
                (def r#2 (if var(b#2) 1:int 0:int))
                var(r#2))) (block
              (def x#1 (+:int var(i) 2:int))
              var(x#1))) (block
            (def x#2 (array-op SIZE var(arr)))
            var(x#2))))
      (set k += 1:int)))
  var(a)
```

## HirLicmPass.checkOperandsInvariant : Block with declaredVars, As (checked & unchecked), NameRead, and Const...

```babble
def topRO = 4
def run = fn (b: byte, raw: any): double -> (
  var a = 0.0 ; var k = 0
  while k < 2 then (
    a += bigSink(b as double) + double(bigInt(topRO) + bigInt((def t = int(b) ; t)) + bigInt(raw as int) + "  hi  ".trim().length()) ; k += 1
  ) ; a
)
assert(run(byte(2), 3) == 26.0)
```

```hir
;; hir -O0 fn=run
fn run (b:byte, raw:obj) -> double {pure unique}
  (def a 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (call-fn bigSink (as var(b) double|double|)) (call name(double) (+:int (+:int (+:int (call-fn bigInt name(topRO)) (call-fn bigInt (block
                      (def t (call name(int) var(b)))
                      var(t)))) (call-fn bigInt (as var(raw) int|int|))) (jcall String.length (jcall String.trim "  hi  "))))))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (b:byte, raw:obj) -> double {pure unique}
  (def a 0.0:double)
  (def k 0:int)
  (block
    (def $licm0
      (block
        (def x#1 name(topRO))
        var(x#1)))
    (while (<:bool var(k) 2:int)
      (block
        (set a += (+:double (block
              (def x#2 (as var(b) double|double|))
              var(x#2)) (call name(double) (+:int (+:int (+:int var($licm0) (block
                      (def x#3
                        (block
                          (def t (call name(int) var(b)))
                          var(t)))
                      var(x#3))) (block
                    (def x#4 (as var(raw) int|int|))
                    var(x#4))) 2:int))))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=run
fn run (b:byte, raw:obj) -> double {pure unique}
  (def a 0.0:double)
  (def k 0:int)
  (while (<:bool var(k) 2:int)
    (block
      (set a += (+:double (block
            (def x#1 (as var(b) double|double|))
            var(x#1)) (call name(double) (+:int (+:int (+:int (block
                    (def x#2 name(topRO))
                    var(x#2)) (block
                    (def x#3
                      (block
                        (def t (call name(int) var(b)))
                        var(t)))
                    var(x#3))) (block
                  (def x#4 (as var(raw) int|int|))
                  var(x#4))) 2:int))))
      (set k += 1:int)))
  var(a)
```

## HirLicmPass : local non-capturing fn (DirectBabbleCall with VarRead callee) and non-value class ThisFieldGet

```babble
def C = class (def v: int) (
  def run = fn (n: int): int -> (
    def loc = fn nothrow (x: int): int -> (return x + 1 ; x)
    var a = 0 ; var k = 0
    while k < n then (a += loc(n) + this.v ; k += 1) ; a
  )
)
assert(C(3).run(2) == 12)
```

```hir
;; hir -O0 fn=C.run
fn C.run (n:int) -> int {nothrow unique}
  (def loc (fn-ref loc))
  (def a 0:int)
  (def k 0:int)
  (while (<:bool var(k) var(n))
    (block
      (set a += (+:int (call-fn loc var(n)) (get-this-field v)))
      (set k += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=C.run
fn C.run (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int (+:int var(n) 1:int) (get-this-field v)))
    (while (<:bool var(k) var(n))
      (block
        (set a += var($licm0))
        (set k += 1:int))))
  var(a)
```

```hir
;; hir -O2 fn=C.run
fn C.run (n:int) -> int {nothrow unique}
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int (+:int var(n) 1:int) (get-this-field v)))
    (while (<:bool var(k) var(n))
      (block
        (set a += var($licm0))
        (set k += 1:int))))
  var(a)
```

## LICM / HirPurity : loop-invariant as, TypeApply, array.size(), and pure overloaded helper

```babble
def id of T = fn (x: T): int -> (1)
def ov = overload (fn (a: int): int -> (a + 1), fn (a: int, b: int): int -> (a + b))
def run = fn (x: any, arr: array of int): int -> (
  var i = 0 ; var acc = 0
  while i < 4 then (acc += (x as int) + (id of int)(x as int) + arr.size() + ov(2) + ov(1, 2) ; i += 1) ; acc
)
assert(run(5, array.of(10, 20, 30)) == 60)
```

```hir
;; hir -O0 fn=run
fn run (x:obj, arr:obj) -> int {pure unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) 4:int)
    (block
      (set acc += (+:int (+:int (+:int (+:int (block (as var(x) int|int|)) (call (block (type-apply name(id))) (as var(x) int|int|))) (array-op SIZE var(arr))) (call-fn ov#1 2:int)) (call-fn ov#2 1:int 2:int)))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (x:obj, arr:obj) -> int {pure unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) 4:int)
    (block
      (set acc += (+:int (+:int (+:int (+:int (as var(x) int|int|) (call (type-apply name(id)) (as var(x) int|int|))) (array-op SIZE var(arr))) 3:int) 3:int))
      (set i += 1:int)))
  var(acc)
```

```hir
;; hir -O2 fn=run
fn run (x:obj, arr:obj) -> int {pure unique}
  (def i 0:int)
  (def acc 0:int)
  (while (<:bool var(i) 4:int)
    (block
      (set acc += (+:int (+:int (+:int (+:int (as var(x) int|int|) (call (type-apply name(id)) (as var(x) int|int|))) (array-op SIZE var(arr))) 3:int) 3:int))
      (set i += 1:int)))
  var(acc)
```
