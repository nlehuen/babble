# HIR purity, modified-variable analysis, and LICM invariance checks

References : BIP014-I2, org.babblelang.opt.hir.HirLicmPass, org.babblelang.opt.HirPurity
Non-trailing return prevents InlinePass from inlining in round 1 while HirPurity still sees pure/nothrow bodies

```babble
;; babble preamble
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def bigSink = fn nothrow (x: double): double -> (return x ; x)
```

## HirPurity.collectModifiedVars : array.set, field/Java writes, and nested for loop

```babble
def Sub = class (def f: int) ()
def run = fn io (): int -> (
  def a = 0 ; def s = Sub(4) ; def pt = java.awt.Point(2, 3) ; def i = 0
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

## HirPurity.collectModifiedVars : super, DirectThisMethodCall, and InterfaceCall inside while

```babble
def I = interface (def g = fn (x: int): int -> (x + 1))
def S = class (def f: int) implements I (
  def m = fn (x: int): int -> (this.f + x)
  def s = fn (x: int): int -> (def a = 0 ; def i = 0 ; while i < 2 then (a += super.g(x) + this.m(x) ; i += 1) ; a)
)
def r = fn (v: I): int -> (def a = 0 ; def i = 0 ; while i < 2 then (a += v.g(1) ; i += 1) ; a)
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

## HirPurity.collectModifiedVars : is, as, and safe-nav (?.) inside while

```babble
def S = class (def f: int) (def m = fn (x: int): int -> (this.f + x))
def run = fn (s: S, o: S?, d: any): int -> (
  def a = 0 ; def i = 0
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

## HirPurity.collectModifiedVars : top-level/this writes, !, generic TypeApply

```babble
def topM = 0 ; topM = 1
def bigId of T = fn nothrow (x: T): T -> (def f = fn (): int -> (0) ; discard(f) ; x)
def MC = class (def mf: int) (
  def run = fn mut io (n: int): int -> (
    def i = 0 ; def s = 0
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

## HirPurity.collectModifiedVars : nested fn, class, interface, StringBuilder, list, and is check inside while

```babble
def run = fn io (): int -> (
  def i = 0 ; def s = 0
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
      (def fnL (fn-ref fnL))
      (def ClsL (class-ref ClsL))
      (def IfcL (iface-ref IfcL))
      (def av var(sb))
      (set s += (+:int (+:int (+:int (+:int (jcall StringBuilder.length var(sb)) (jcall List.size (core-new list 1:int 2:int))) 2:int) (get-this-field v)) (if (is var(av) babble|IfcL) 1:int 0:int)))
      (set i += 1:int)))
  var(s)
```

## HirPurity.collectModifiedVars : SelfRecurseCall and nested while inside a while loop

```babble
def recWhile = fn (n: int): int -> (
  def i = 0 ; def s = 0
  while i < 1 then (
    def j = 0 ; while j < 2 then (j += 1)
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
      (if (>:bool var(n) 0:int) (block (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int))))
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
      (if (>:bool var(n) 0:int) (set s += (+:int (self-recurse recWhile (-:int var(n) 1:int)) 1:int)))
      (set s += var(j))
      (set i += 1:int)))
  var(s)
```

## HirLicmPass.checkOperandsInvariant & HirPurity.isDeterministic : Logic, Unary.NOT, Block, and ArrayOp.SIZE

```babble
def bigBool = fn nothrow (b: bool): int -> (def r = if b then (1) else (0) ; return r ; r)
def run = fn (f: bool, i: int, arr: frozen array of int): int -> (
  def a = 0 ; def k = 0
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

## HirLicmPass.checkOperandsInvariant : Block with declaredVars, As (checked & unchecked), NameRead, and Const...

```babble
def topRO = 4
def run = fn (b: byte, raw: any): double -> (
  def a = 0.0 ; def k = 0
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
    def a = 0 ; def k = 0
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
  (def loc (fn-ref loc))
  (def a 0:int)
  (def k 0:int)
  (block
    (def $licm0 (+:int var(n) 1:int))
    (while (<:bool var(k) var(n))
      (block
        (set a += (+:int var($licm0) (get-this-field v)))
        (set k += 1:int))))
  var(a)
```

## LICM / HirPurity : loop-invariant as, TypeApply, array.size(), and pure overloaded helper

```babble
def id of T = fn (x: T): int -> (1)
def ov = overload (fn (a: int): int -> (a + 1), fn (a: int, b: int): int -> (a + b))
def run = fn (x: any, arr: array of int): int -> (
  def i = 0 ; def acc = 0
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
      (set acc += (+:int (+:int (+:int (+:int (as var(x) int|int|) (call (type-apply name(id)) (as var(x) int|int|))) (array-op SIZE var(arr))) (block
              (overload-select #0 name(ov))
              3:int)) (block
            (overload-select #1 name(ov))
            3:int)))
      (set i += 1:int)))
  var(acc)
```

## LICM in for-loop (Logic, DIV, Block) and DCE of unused MakeOverloadSet

```babble
def run = fn (a: int, b: int, flag: bool, xs: list of int): double -> (
  def unusedOv = overload (fn (n: int): int -> (n + 1), fn (s: string): int -> (s.length()))
  def acc = 0.0
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
      (if (and var(flag) (block (>:bool var(a) 0:int))) (block (set acc += (+:double (+:double (block (/:double var(a) var(b))) (block (+:int var(a) var(b)))) var(e)))))))
  var(acc)
```

```hir
;; hir -O1 fn=run
fn run (a:int, b:int, flag:bool, xs:obj) -> double {nothrow unique}
  (def unusedOv (overload (fn-ref unusedOv#1) (fn-ref unusedOv#2)))
  (def acc 0.0:double)
  (block
    (def $licm0 (+:double (/:double var(a) var(b)) (+:int var(a) var(b))))
    (for e in var(xs)
      (block
        (if (and var(flag) (>:bool var(a) 0:int)) (set acc += (+:double var($licm0) var(e)))))))
  var(acc)
```

## HirPurity.isDeterministic : loop-invariant non-static String.length, If, FunctionRef, CoreCollectionRef

```babble
def useRef = fn nothrow (f: fn nothrow (int): int, c: any): int -> (return f(1) ; 0)
def run = fn (s: string, flag: bool, n: int): int -> (
  def i = 0 ; def acc = 0
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
  (def s (if true:bool (block (call name(byte) 3:int)) (block var(p))))
  (def c1 (and (and (and (and (<:bool 'a' 'b') (<=:bool 'a' 'a')) (>:bool 'b' 'a')) (>=:bool 'b' 'b')) (<>:bool 'a' 'b')))
  (def b1 (and (and (block (==:bool true:bool true:bool)) (block (<>:bool true:bool false:bool))) (not (block (==:bool false:bool true:bool)))))
  (and (and (==:bool var(s) (call name(short) 3:int)) var(c1)) var(b1))
```

```hir
;; hir -O1 fn=run
fn run (p:byte) -> bool {pure unique}
  (def s (if true:bool (block (call name(byte) 3:int)) (block var(p))))
  (and (and (==:bool var(s) (call name(short) 3:int)) true:bool) true:bool)
```

## Canonical HIR golden : loop-invariant Math.cos hoisted to preheader (-O0 vs -O1)

```babble
def sumScaled = fn (n: int, angle: double): double -> (
    def i = 0
    def acc = 0.0
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

## HirLicmPass : frozen class field read and unary negation

```babble
def FC = class (def v: int) ()
def run = fn (n: int, fc: frozen FC): int -> (
    def a = 0 ; def i = 0
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
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (get-this-field v) (neg (get-this-field v))) 1:int))
      (set i += 1:int)))
  var(a)
```

## HirLicmPass : captured this (cell ThisRef) and immediately-invoked FunctionRef callee

```babble
def CR = record (x: int) (
    def run = fn (n: int): int -> (
        def cap = fn (): int -> (this.x)
        def a = 0 ; def i = 0
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
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (get-this-field x) (+:int var(n) 1:int)))
      (set i += 1:int)))
  (+:int var(a) (call-fn cap))
```

## HirLicmPass : DirectThisMethodCall on mutable/frozen class and non-pure-static Math.sin

```babble
def PC = class (def x: int) (def pm = fn nothrow (k: int): int -> (return k + 2 ; k))
def run = fn (n: int, mc: PC, fmc: frozen PC): double -> (
    def mCls = java.lang.Math ; def a = 0.0 ; def i = 0
    while i < n then (a += double(mc.pm(n) + fmc.pm(n)) + mCls.sin(0.0) ; i += 1) ; a
)
assert(run(2, PC(1), freeze(PC(1))) == 16.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (def mCls (select (select name(java).lang).Math))
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:double (call name(double) (+:int (call-method var(mc).pm var(n)) (call-method var(fmc).pm var(n)))) (jcall Math.sin 0.0:double)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, mc:obj, fmc:obj) -> double {nothrow unique}
  (def mCls (select (select name(java).lang).Math))
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0 (+:int var(n) 2:int))
    (block
      (def $licm1 (+:int (+:int var(n) 2:int) var($licm0)))
      (while (<:bool var(i) var(n))
        (block
          (set a += (+:double (call name(double) var($licm1)) 0.0:double))
          (set i += 1:int)))))
  var(a)
```

## HirLicmPass : InterfaceCall on mutable, frozen, and record receivers

```babble
def PI = interface (def m = fn nothrow (k: int): int -> (return k + 1 ; k))
def PC = class () implements PI ()
def PR = record (x: int) implements PI ()
def run = fn (n: int, vi: PI, fvi: frozen PI, pr: PR): int -> (
    def a = 0 ; def i = 0
    while i < n then (a += vi.m(n) + fvi.m(n) + pr.m(n) ; i += 1) ; a
)
assert(run(2, PC(), freeze(PC()), PR(1)) == 18)
```

```hir
;; hir -O0 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-iface var(vi).m var(n)) (call-iface var(fvi).m var(n))) (call (select var(pr).m) var(n))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vi:obj, fvi:obj, pr:obj) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-iface var(fvi).m var(n)))
    (while (<:bool var(i) var(n))
      (block
        (set a += (+:int (+:int (call-iface var(vi).m var(n)) var($licm0)) (call (select var(pr).m) var(n))))
        (set i += 1:int))))
  var(a)
```

## HirLicmPass and HirPurity : Move, Freeze, and widened transparent Block inside while

```babble
def bm = fn nothrow (s: list of int): int -> (return s.size() ; 0)
def bf = fn nothrow (s: frozen array of int): int -> (return s.size() ; 0)
def run = fn (n: int, x: int): double -> (
  def a = 0.0 ; def i = 0
  while i < n then (
    def s = list(1) ; def r = array.of(1, 2)
    a += double(bm(move(s)) + bf(freeze(r))) + bigSink((x + 1)) ; i += 1
  ) ; a
)
assert(run(2, 3) == 14.0)
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (def s (core-new list 1:int))
      (def r (array-new OF 1:int 2:int))
      (set a += (+:double (call name(double) (+:int (call-fn bm (move var(s))) (call-fn bf (freeze var(r))))) (call-fn bigSink (block (+:int var(x) 1:int)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> double {nothrow unique}
  (def a 0.0:double)
  (def i 0:int)
  (block
    (def $licm0
      (block
        (def x#2 (block (+:int var(x#1) 1:int)))
        var(x#2)))
    (while (<:bool var(i) var(n))
      (block
        (def s#1 (core-new list 1:int))
        (def r (array-new OF 1:int 2:int))
        (set a += (+:double (call name(double) (+:int (block
                  (def s#2 (move var(s#1)))
                  (jcall List.size var(s#2))) (block
                  (def s#3 (freeze var(r)))
                  (array-op SIZE var(s#3))))) var($licm0)))
        (set i += 1:int))))
  var(a)
```

## HirLicmPass and HirPurity : unchecked As (x as any?) hoisted out of while

```babble
def bigAny = fn nothrow (v: any?): int -> (if same(v, null) then (return 0) ; 1)
def run = fn (n: int, x: int): int -> (
  def a = 0 ; def i = 0
  while i < n then (a += bigAny(x as any?) ; i += 1) ; a
)
assert(run(3, 5) == 3)
```

```stderr
Warning : Redundant cast : any?
```

```hir
;; hir -O0 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigAny (as var(x) any)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, x:int) -> int {nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (call-fn bigAny (as var(x) any)))
    (while (<:bool var(i) var(n))
      (block
        (set a += var($licm0))
        (set i += 1:int))))
  var(a)
```

## HirPurity.collectModifiedVars : try with named/unnamed catch and finally defs inside while

```babble
def run = fn (n: int): int -> (
  def s = 0 ; def i = 0
  while i < n then (
    s += try (
      if i < 0 then (throw java.lang.ArithmeticException("x"))
      def t = i + 1 ; t
    ) catch e: java.lang.ArithmeticException then (def c1 = 1 ; c1)
      catch : exception then (def c2 = 2 ; c2)
      finally (def f = 3 ; discard(f))
    i += 1
  ) ; s
)
assert(run(3) == 6)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (block (throw (jnew ArithmeticException "x"))))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            (def c1 1:int)
            var(c1))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (def c2 2:int)
            var(c2))
          (finally
            (def f 3:int)
            (call name(discard) var(f)))))
      (set i += 1:int)))
  var(s)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (def s 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set s +=
        (try
          (block
            (if (<:bool var(i) 0:int) (throw (jnew ArithmeticException "x")))
            (def t (+:int var(i) 1:int))
            var(t))
          (catch e:java|java.lang.ArithmeticException|java.lang.ArithmeticException
            1:int)
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            2:int)
          (finally
            null)))
      (set i += 1:int)))
  var(s)
```
