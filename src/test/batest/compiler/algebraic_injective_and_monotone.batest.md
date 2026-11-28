# Injective and Monotone Comparison Cancellation

References : BIP042-I1, BIP014-I5, org.babblelang.ir.egraph.AlgebraicRules, org.babblelang.opt.hir.HirAeGraphPass

```babble
;; babble preamble
;; Non-trailing return prevents InlinePass from inlining so cancellation is directly observable.
def encode = fn axiom pure nothrow monotone injective (raw: int): double -> (
    def r = raw * 10.0 + 5.0
    return r
    r
)

def tagStr = fn axiom pure nothrow injective (s: string): string -> (
    def r = "k:" + s
    return r
    r
)

def clamp = fn axiom pure nothrow monotone (x: int): int -> (
    def r = if x < 0 then ( 0 ) else ( x )
    return r
    r
)

def negInt = fn axiom pure nothrow injective (x: int): int -> (
    def r = -x
    return r
    r
)

def mayThrow = fn axiom pure injective (x: int): int -> (
    if x < 0 then ( assert(false) )
    return x
    x
)
```

## Expression-local injective and monotone+injective cancellation (-O1 and -O2)

```babble
def cmpEnc = fn (a: int, b: int): bool -> (
    def c1 = (encode(a) == encode(b)) and (encode(a) != encode(b))
    def c2 = (encode(a) < encode(b)) or (encode(a) <= encode(b))
    def c3 = (encode(a) > encode(b)) or (encode(a) >= encode(b))
    not c1 and (c2 or c3)
)
assert(cmpEnc(1, 2) and cmpEnc(3, 3) and cmpEnc(5, -2))
```

```hir
;; hir -O0 fn=cmpEnc
fn cmpEnc (a:int, b:int) -> bool {nothrow commutative unique}
  (def c1 (and (block (==:bool (call-fn encode var(a)) (call-fn encode var(b)))) (block (<>:bool (call-fn encode var(a)) (call-fn encode var(b))))))
  (def c2 (or (block (<:bool (call-fn encode var(a)) (call-fn encode var(b)))) (block (<=:bool (call-fn encode var(a)) (call-fn encode var(b))))))
  (def c3 (or (block (>:bool (call-fn encode var(a)) (call-fn encode var(b)))) (block (>=:bool (call-fn encode var(a)) (call-fn encode var(b))))))
  (and (not var(c1)) (block (or var(c2) var(c3))))
```

```hir
;; hir -O1 fn=cmpEnc
fn cmpEnc (a:int, b:int) -> bool {nothrow commutative unique}
  (def c1 (and (==:bool var(a) var(b)) (<>:bool var(a) var(b))))
  (def c2 (or (<:bool var(a) var(b)) (<=:bool var(a) var(b))))
  (def c3 (or (>:bool var(a) var(b)) (>=:bool var(a) var(b))))
  (and (not var(c1)) (or var(c2) var(c3)))
```

```hir
;; hir -O2 fn=cmpEnc
fn cmpEnc (a:int, b:int) -> bool {nothrow commutative unique}
  (def c1 (and (==:bool var(a) var(b)) (<>:bool var(a) var(b))))
  (def c2 (or (<:bool var(a) var(b)) (<=:bool var(a) var(b))))
  (def c3 (or (>:bool var(a) var(b)) (>=:bool var(a) var(b))))
  (and (not var(c1)) (or var(c2) var(c3)))
```

## String injective equality cancellation

```babble
def cmpStr = fn (a: string, b: string): bool -> (
    tagStr(a) == tagStr(b) and not (tagStr(a) != tagStr(b))
)
assert(cmpStr("hi", "hi") and not cmpStr("a", "b"))
```

```hir
;; hir -O0 fn=cmpStr
fn cmpStr (a:obj, b:obj) -> bool {nothrow commutative unique}
  (and (==:bool (call-fn tagStr var(a)) (call-fn tagStr var(b))) (not (block (<>:bool (call-fn tagStr var(a)) (call-fn tagStr var(b))))))
```

```hir
;; hir -O1 fn=cmpStr
fn cmpStr (a:obj, b:obj) -> bool {nothrow commutative unique}
  (and (==:bool var(a) var(b)) (==:bool var(a) var(b)))
```

```hir
;; hir -O2 fn=cmpStr
fn cmpStr (a:obj, b:obj) -> bool {nothrow commutative unique}
  (and (==:bool var(a) var(b)) (==:bool var(a) var(b)))
```

## Cross-statement injective and monotone cancellation in -O2 HirAeGraphPass

```babble
def crossStmt = fn (a: int, b: int): bool -> (
    def sa = encode(a)
    def sb = encode(b)
    sa < sb
)
assert(crossStmt(1, 5) and not crossStmt(5, 5) and not crossStmt(7, 2))
```

```hir
;; hir -O0 fn=crossStmt
fn crossStmt (a:int, b:int) -> bool {nothrow unique}
  (def sa (call-fn encode var(a)))
  (def sb (call-fn encode var(b)))
  (<:bool var(sa) var(sb))
```

```hir
;; hir -O1 fn=crossStmt
fn crossStmt (a:int, b:int) -> bool {nothrow unique}
  (def sa
    (block
      (def r#1 (+:double (*:double (convert[double] var(a)) 10.0:double) 5.0:double))
      var(r#1)))
  (def sb
    (block
      (def r#2 (+:double (*:double (convert[double] var(b)) 10.0:double) 5.0:double))
      var(r#2)))
  (<:bool var(sa) var(sb))
```

```hir
;; hir -O2 fn=crossStmt
fn crossStmt (a:int, b:int) -> bool {nothrow unique}
  (<:bool var(a) var(b))
```

## Soundness bailouts: monotone-only, injective-only ordering, and mutated var

```babble
def bailouts = fn (a: int, b: int): bool -> (
    var x = a + 1
    def sx = encode(x)
    x = b + 10
    def sy = encode(b)
    def mutOk = sx < sy
    def monoOk = clamp(-1) <= clamp(-5)
    def injOrdOk = negInt(-1) < negInt(-5)
    mutOk and monoOk and injOrdOk
)
assert(bailouts(1, 5) and not bailouts(5, 5))
```

```hir
;; hir -O0 fn=bailouts
fn bailouts (a:int, b:int) -> bool {nothrow unique}
  (def x (+:int var(a) 1:int))
  (def sx (call-fn encode var(x)))
  (set x (+:int var(b) 10:int))
  (def sy (call-fn encode var(b)))
  (def mutOk (<:bool var(sx) var(sy)))
  (def monoOk (<=:bool (call-fn clamp -1:int) (call-fn clamp -5:int)))
  (def injOrdOk (<:bool (call-fn negInt -1:int) (call-fn negInt -5:int)))
  (and (and var(mutOk) var(monoOk)) var(injOrdOk))
```

```hir
;; hir -O1 fn=bailouts
fn bailouts (a:int, b:int) -> bool {nothrow unique}
  (def x (+:int var(a) 1:int))
  (def sx
    (block
      (def r#1 (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
      var(r#1)))
  (def sy
    (block
      (def r#2 (+:double (*:double (convert[double] var(b)) 10.0:double) 5.0:double))
      var(r#2)))
  (def mutOk (<:bool var(sx) var(sy)))
  (def monoOk (<=:bool 0:int 0:int))
  (def injOrdOk (<:bool 1:int 5:int))
  (and (and var(mutOk) var(monoOk)) var(injOrdOk))
```

```hir
;; hir -O2 fn=bailouts
fn bailouts (a:int, b:int) -> bool {nothrow unique}
  (def x (+:int var(a) 1:int))
  (def sx
    (block
      (def r#1 (+:double (*:double (convert[double] var(x)) 10.0:double) 5.0:double))
      var(r#1)))
  (def sy
    (block
      (def r#2 (+:double (*:double (convert[double] var(b)) 10.0:double) 5.0:double))
      var(r#2)))
  (def mutOk (<:bool var(sx) var(sy)))
  (and (and var(mutOk) true:bool) true:bool)
```

## Soundness bailout: non-nothrow injective function must still throw

```babble
def checkThrow = fn (a: int): bool -> ( mayThrow(a) == mayThrow(a) )
checkThrow(-1)
```

```error
Assertion failed : false
```

```hir
;; hir -O0 fn=checkThrow
fn checkThrow (a:int) -> bool {pure unique}
  (==:bool (call-fn mayThrow var(a)) (call-fn mayThrow var(a)))
```

```hir
;; hir -O1 fn=checkThrow
fn checkThrow (a:int) -> bool {pure unique}
  (==:bool (block
      (if (<:bool var(a) 0:int) (box (call name(assert) false:bool)))
      var(a)) (block
      (if (<:bool var(a) 0:int) (box (call name(assert) false:bool)))
      var(a)))
```

```hir
;; hir -O2 fn=checkThrow
fn checkThrow (a:int) -> bool {pure unique}
  (==:bool (block
      (if (<:bool var(a) 0:int) (box (call name(assert) false:bool)))
      var(a)) (block
      (if (<:bool var(a) 0:int) (box (call name(assert) false:bool)))
      var(a)))
```
