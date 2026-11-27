# HIR DCE Variable References and HirRewriter Coverage

References : BIP014-I2, org.babblelang.opt.hir.InlinePass, org.babblelang.opt.hir.HirDcePass

```babble
;; babble module=dce_mod.ba
export def val = 10
```

```babble
;; babble preamble
def addMul = fn (a: int, b: int): int -> (
    def s = a + b
    def d = a - b
    s * d
)
def fib = fn (n: int): int -> (if n < 2 then (n) else (recurse(n - 1) + recurse(n - 2)))
def pick = fn (x: int): int -> (if x > 0 then (addMul(x, 1)) else (0))
```


## HirDcePass.collectAllVarRefs : local defs alongside ThisRef, SuperCall, and RecurseRef

```babble
def Base = interface (def calc = fn (x: int): int -> (x + 1))
def Sub = class (def k: int) implements Base (
    def calc = fn (x: int): int -> (def tmp = this.k + 1 ; super.calc(x) + tmp)
)
def recVal = fn (n: int): int -> (
    def step = 1
    if n <= 0 then (0) else (def r = recurse ; r(n - step) + step)
)
assert(Sub(4).calc(5) == 11 and recVal(3) == 3)
```

```hir
;; hir -O0 fn=recVal
fn recVal (n:int) -> int {nothrow}
  (def step 1:int)
  (if (<=:bool var(n) 0:int)
    (block 0:int)
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) var(step))) var(step))))
```

```hir
;; hir -O1 fn=recVal
fn recVal (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

```hir
;; hir -O2 fn=recVal
fn recVal (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int)
    0:int
    (block
      (def r recurse)
      (+:int (call var(r) (-:int var(n) 1:int)) 1:int)))
```

## HirRewriter : ModuleExportGet, SpreadAdapt, and TypeApply child rewrites

```babble
def m = import("dce_mod.ba")
def idG of T = fn (x: T): T -> (x)
def sumV = fn io (xs: int...): int -> (var s = 0 ; for e in xs then (s += e) ; s)
def run = fn io (arr: array of int): int -> (
    (discard(1) ; m).val + sumV(...(discard(1) ; arr)) + ((discard(1) ; idG) of int)(3)
)
assert(run((array of int).of(1, 2)) == 16)
```

```hir
;; hir -O0 fn=run
fn run (arr:obj) -> int {io}
  (+:int (+:int (export (block
          (call name(discard) 1:int)
          name(m)).val) (call-fn sumV (block
          (call name(discard) 1:int)
          var(arr)))) (call (block (type-apply (block
            (call name(discard) 1:int)
            name(idG)))) 3:int))
```

```hir
;; hir -O1 fn=run
fn run (arr:obj) -> int {io}
  (+:int (+:int (export name(m).val) (call-fn sumV var(arr))) (call (type-apply name(idG)) 3:int))
```

```hir
;; hir -O2 fn=run
fn run (arr:obj) -> int {io}
  (+:int (+:int (export name(m).val) (call-fn sumV var(arr))) (call (type-apply name(idG)) 3:int))
```

## HirRewriter : For iterable and SelfRecurseCall argument rewrites

```babble
def idL = fn (xs: list of int): list of int -> (xs)
def recN = fn (n: int): int -> (if n <= 0 then (0) else (1 + recurse(n - (1 + 0))))
def run = fn (xs: list of int): int -> (
    var b = 0 ; for e in idL(xs) then (b += e) ; b + recN(2)
)
assert(run(list(4, 5)) == 11)
```

```hir
;; hir -O0 fn=run
fn run (xs:obj) -> int {nothrow unique}
  (def b 0:int)
  (for e in (call-fn idL var(xs))
    (block
      (set b += var(e))))
  (+:int var(b) (call-fn recN 2:int))
```

```hir
;; hir -O1 fn=run
fn run (xs:obj) -> int {nothrow unique}
  (def b 0:int)
  (for e in var(xs)
    (block
      (set b += var(e))))
  (+:int var(b) (call-fn recN 2:int))
```

```hir
;; hir -O0 fn=recN
fn recN (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) (block 0:int) (block (+:int 1:int (self-recurse recN (-:int var(n) (block (+:int 1:int 0:int)))))))
```

```hir
;; hir -O1 fn=recN
fn recN (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int 1:int (self-recurse recN (-:int var(n) 1:int))))
```

```hir
;; hir -O2 fn=recN
fn recN (n:int) -> int {nothrow}
  (if (<=:bool var(n) 0:int) 0:int (+:int 1:int (self-recurse recN (-:int var(n) 1:int))))
```

## HirRewriter : SafeCall and MakeOverloadSet child rewrites

```babble
def run = fn (s: string?, n: int): int -> (
    def l1 = (discard(1) ; s)?.length()
    def l2 = s?.substring(0 + 1)?.length()
    def ov = overload((discard(1) ; fn (x: int): int -> (x + 1)), fn (t: string): int -> (t.length()))
    (if l1 != null then (l1) else (0)) + (if l2 != null then (l2) else (0)) + ov(n)
)
assert(run("abcd", 3) == 11 and run(null, 3) == 4)
```

```hir
;; hir -O0 fn=run
fn run (s:obj, n:int) -> int {pure unique}
  (def l1 (safe-call (block
        (call name(discard) 1:int)
        var(s))?.length))
  (def l2 (safe-call (safe-call var(s)?.substring (+:int 0:int 1:int))?.length))
  (def ov (overload (block
        (call name(discard) 1:int)
        (fn-ref <anon#0>)) (fn-ref ov)))
  (+:int (+:int (block (if (<>:bool var(l1) null) (block (narrow.nonnull var(l1))) (block 0:int))) (block (if (<>:bool var(l2) null) (block (narrow.nonnull var(l2))) (block 0:int)))) (call-fn <anon#0> var(n)))
```

```hir
;; hir -O1 fn=run
fn run (s:obj, n:int) -> int {pure unique}
  (def l1 (safe-call var(s)?.length))
  (def l2 (safe-call (safe-call var(s)?.substring 1:int)?.length))
  (+:int (+:int (if (<>:bool var(l1) null) (narrow.nonnull var(l1)) 0:int) (if (<>:bool var(l2) null) (narrow.nonnull var(l2)) 0:int)) (+:int var(n) 1:int))
```

```hir
;; hir -O2 fn=run
fn run (s:obj, n:int) -> int {pure unique}
  (def l1 (safe-call var(s)?.length))
  (def l2 (safe-call (safe-call var(s)?.substring 1:int)?.length))
  (+:int (+:int (if (<>:bool var(l1) null) (narrow.nonnull var(l1)) 0:int) (if (<>:bool var(l2) null) (narrow.nonnull var(l2)) 0:int)) (+:int var(n) 1:int))
```

## HirRewriter : RecordNew classRef and Move operand rewrites

```babble
def Pair = record (a: int, b: int) ()
def run = fn (n: int): int -> (
    def p = (discard(1) ; Pair)(n, 2)
    def xs = list(n)
    def m1 = move(xs)
    p.a + p.b + m1.get(0)
)
assert(run(3) == 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {pure unique}
  (def p (record-new Pair var(n) 2:int))
  (def xs (core-new list var(n)))
  (def m1 (move var(xs)))
  (+:int (+:int (get-this-field a) (get-this-field b)) (jcall List.get var(m1) 0:int))
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {pure unique}
  (block
    (def $sr_p_0 var(n))
    (def $sr_p_1 2:int))
  (def xs (core-new list var(n)))
  (def m1 (move var(xs)))
  (+:int (+:int var($sr_p_0) var($sr_p_1)) (jcall List.get var(m1) 0:int))
```

```hir
;; hir -O2 fn=run
fn run (n:int) -> int {pure unique}
  (block
    (def $sr_p_0 var(n))
    (def $sr_p_1 2:int))
  (def xs (core-new list var(n)))
  (def m1 (move var(xs)))
  (+:int (+:int var($sr_p_0) 2:int) (jcall List.get var(m1) 0:int))
```
