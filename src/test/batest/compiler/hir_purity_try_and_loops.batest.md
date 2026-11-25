# HIR Purity : Try/Catch/Finally, Determinism, and Loops

References : `BIP027-D1`, `BIP027-I6`, `org.babblelang.ir.egraph.{EGraph,ENode,AlgebraicRules}`, `org.babblelang.opt.HirPurity`.

```babble
;; babble module=ext_rec.ba
export def ExtRec = record (v: int) (
    def getPure = fn nothrow (): int -> (return this.v ; this.v)
    def getThrow = fn (): int -> (if this.v < 0 then (throw exception("neg")) ; return this.v ; this.v)
    def getVol = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return this.v + n ; this.v)
)
```

```babble
;; babble preamble
var tick = 0
def bump = fn io (): int -> (tick += 1 ; tick)
def bigInt = fn nothrow (x: int): int -> (return x ; x)
def volLen = fn volatile nothrow (): int -> (def n = java.lang.System.lineSeparator().length() ; return n ; n)
```



## HirPurity.isTryPureNothrowTerminating : pure/impure catch and finally before DCE strips catches

```babble
def run = fn io (x: int): int -> (
    def a = (try (x + 1) catch : exception then (x + 2)) + 0
    def b = (try (x + 1) catch : exception then (bump())) + 0
    def c = (try (x + 1) catch : exception then (x + 2) finally (def z = x + 3 ; discard(z))) + 0
    def d = (try (x + 1) finally (discard(bump()))) + 0
    a + b + c + d
)
assert(run(2) == 12 and tick == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 fn=run
fn run (x:int) -> int {io}
  (def a (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int)))) 0:int))
  (def b (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (call-fn bump)))) 0:int))
  (def c (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (catch _:java|org.babblelang.runtime.BabbleException|org.babblelang.runtime.BabbleException
            (+:int var(x) 2:int))
          (finally
            (def z (+:int var(x) 3:int))
            (call name(discard) var(z))))) 0:int))
  (def d (+:int (block
        (try
          (block
            (+:int var(x) 1:int))
          (finally
            (call name(discard) (call-fn bump))))) 0:int))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
```

```hir
;; hir -O1 fn=run
fn run (x:int) -> int {io}
  (def a (+:int var(x) 1:int))
  (def b (+:int var(x) 1:int))
  (def c
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        null)))
  (def d
    (try
      (block
        (+:int var(x) 1:int))
      (finally
        (call name(discard) (call-fn bump)))))
  (+:int (+:int (+:int var(a) var(b)) var(c)) var(d))
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    v3: int = const 1
    v4: int = add v0, v3 {pure}
    br bb1
  bb1 [unwind: * -> bb2]:
    v5: int = const 1
    v6: int = add v0, v5 {pure}
    br bb4
  bb2(v7: any) [handler]:
    br bb3
  bb3:
    v8: any = const null
    rethrow v7
  bb4:
    v9: any = const null
    br bb5
  bb5:
    br bb6
  bb6 [unwind: * -> bb7]:
    v10: int = const 1
    v11: int = add v0, v10 {pure}
    br bb12
  bb7(v12: any) [handler]:
    br bb8
  bb8:
    br bb9
  bb9 [unwind: Throwable -> bb10]:
    v13: any = opaque.NameRead(discard) {read:shared,throws}
    v14: any = opaque.NameRead(bump) {read:shared}
    v15: int = call bump() {impure}
    v16: any = opaque.DynamicCall(v13, v15) {impure}
    br bb11
  bb10(v17: any) [handler]:
    add_suppressed v12, v17
    br bb11
  bb11:
    rethrow v12
  bb12:
    v18: any = opaque.NameRead(discard) {read:shared,throws}
    v19: any = opaque.NameRead(bump) {read:shared}
    v20: int = call bump() {impure}
    v21: any = opaque.DynamicCall(v18, v20) {impure}
    br bb13
  bb13:
    v22: int = add v2, v4 {pure}
    v23: int = add v22, v6 {pure}
    v24: int = add v23, v11 {pure}
    ret v24
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 1
    v2: int = add v0, v1 {pure}
    br bb1
  bb1:
    v3: any = opaque.NameRead(discard) {read:shared,throws}
    v4: any = opaque.NameRead(bump) {read:shared}
    v5: int = call bump() {impure}
    v6: any = opaque.DynamicCall(v3, v5) {impure}
    br bb2
  bb2:
    v7: int = add v2, v2 {pure}
    v8: int = add v7, v2 {pure}
    v9: int = add v8, v2 {pure}
    ret v9
```

## HirPurity : top-level namespace-scoped block, try, catch, and finally

```babble
discard((def tb = 1 ; tb))
def r1 = (try (def ta = 1 ; ta) finally (discard(2))) + 0
def r2 = (try (1) finally (def tf = 2 ; discard(tf))) + 0
def r3 = (try (1) catch : exception then (def tc = 2 ; tc)) + 0
assert(r1 == 1 and r2 == 1 and r3 == 1)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

```hir
;; hir -O0 top-level check
contains (def tb 1:int)
count 3 (try
contains (catch
```

```hir
;; hir -O1 top-level
top-level:
  (def tick 0:int)
  (def bump (fn-ref bump))
  (def bigInt (fn-ref bigInt))
  (def volLen (fn-ref volLen))
  (block
    (def $try0
      (try
        (block
          1:int)
        (finally
          null)))
    (def r1 var($try0)))
  (block
    (def $try1
      (try
        (block
          1:int)
        (finally
          null)))
    (def r2 var($try1)))
  (def r3 1:int)
  (call name(assert) (and (and (==:bool name(r1) 1:int) (==:bool name(r2) 1:int)) (==:bool name(r3) 1:int)))
```

```mir
;; mir -O2 top-level stage=before,final
top-level ; [not-lowered: top-level]
```

## HirPurity.isDeterministic : volatile nothrow record and interface methods in while

```babble
def VI = interface (def vt: fn volatile nothrow (): int)
def VR = record (k: int) implements VI (
    def vt = fn volatile nothrow (): int -> (def n = volLen() ; return this.k + n ; this.k)
)
def run = fn volatile (n: int, vr: VR, vi: VI): int -> (
    var a = 0 ; var i = 0
    while i < n then (a += vr.vt() + vi.vt() ; i += 1) ; a
)
assert(run(2, VR(1), VR(1)) >= 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (call-method var(vr).vt) (call-iface var(vi).vt)))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, vr:obj, vi:obj) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (block
    (def $licm0 (get-this-field k))
    (while (<:bool var(i) var(n#1))
      (block
        (set a += (+:int (block
              (def n#2
                (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)))
              (+:int var($licm0) var(n#2))) (call-iface var(vi).vt)))
        (set i += 1:int))))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = const 0
    v5: int = load_field v1.k {read:frozen}
    v6: any = const null
    br bb1(v6, v4, v3)
  bb1(v7: any, v8: int, v9: int):
    v10: bool = lt v8, v0 {pure}
    br_if v10, bb3, bb2
  bb2:
    ret v9
  bb3:
    v11: any = call_java System.lineSeparator() {impure}
    v12: any = checkcast v11 <string|string|>
    v13: int = call_java v12.length() {pure,throws}
    v14: int = add v5, v13 {pure}
    v15: int = call_iface v2.vt() {pure!}
    v16: int = add v14, v15 {pure}
    v17: int = add v9, v16 {pure}
    v18: int = const 1
    v19: int = add v8, v18 {pure}
    v20: any = convert.any v19
    br bb1(v20, v19, v17)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any, v2: any) -> int:
  bb0:
    v3: int = const 0
    v4: int = load_field v1.k {read:frozen}
    br bb1(v3, v3)
  bb1(v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = call_java System.lineSeparator() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: int = call_java v9.length() {pure,throws}
    v11: int = add v4, v10 {pure}
    v12: int = call_iface v2.vt() {pure!}
    v13: int = add v11, v12 {pure}
    v14: int = add v6, v13 {pure}
    v15: int = const 1
    v16: int = add v5, v15 {pure}
    br bb1(v16, v14)
```

## HirPurity.isDeterministic : volatile nothrow fn, Java call, and nested volatile child in while

```babble
def run = fn volatile (n: int): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += volLen() + java.lang.System.lineSeparator().length() + bigInt(volLen() + volLen())
        i += 1
    ) ; a
)
assert(run(2) >= 8)
```

```hir
;; hir -O0 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (+:int (+:int (call-fn volLen) (jcall String.length (jcall System.lineSeparator))) (call-fn bigInt (+:int (call-fn volLen) (call-fn volLen)))))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int) -> int {volatile nothrow unique}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n#1))
    (block
      (set a += (+:int (+:int (block
              (def n#2 (jcall String.length (jcall System.lineSeparator)))
              var(n#2)) (jcall String.length (jcall System.lineSeparator))) (block
            (def x (+:int (block
                  (def n#3 (jcall String.length (jcall System.lineSeparator)))
                  var(n#3)) (block
                  (def n#4 (jcall String.length (jcall System.lineSeparator)))
                  var(n#4))))
            var(x))))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    v2: int = const 0
    v3: any = const null
    br bb1(v3, v2, v1)
  bb1(v4: any, v5: int, v6: int):
    v7: bool = lt v5, v0 {pure}
    br_if v7, bb3, bb2
  bb2:
    ret v6
  bb3:
    v8: any = call_java System.lineSeparator() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: int = call_java v9.length() {pure,throws}
    v11: any = call_java System.lineSeparator() {impure}
    v12: any = checkcast v11 <string|string|>
    v13: int = call_java v12.length() {pure,throws}
    v14: int = add v10, v13 {pure}
    v15: any = call_java System.lineSeparator() {impure}
    v16: any = checkcast v15 <string|string|>
    v17: int = call_java v16.length() {pure,throws}
    v18: any = call_java System.lineSeparator() {impure}
    v19: any = checkcast v18 <string|string|>
    v20: int = call_java v19.length() {pure,throws}
    v21: int = add v17, v20 {pure}
    v22: int = add v14, v21 {pure}
    v23: int = add v6, v22 {pure}
    v24: int = const 1
    v25: int = add v5, v24 {pure}
    v26: any = convert.any v25
    br bb1(v26, v25, v23)
```

```mir
;; mir -O2 fn=run
fn run(v0: int) -> int:
  bb0:
    v1: int = const 0
    br bb1(v1, v1)
  bb1(v2: int, v3: int):
    v4: bool = lt v2, v0 {pure}
    br_if v4, bb3, bb2
  bb2:
    ret v3
  bb3:
    v5: any = call_java System.lineSeparator() {impure}
    v6: any = checkcast v5 <string|string|>
    v7: int = call_java v6.length() {pure,throws}
    v8: any = call_java System.lineSeparator() {impure}
    v9: any = checkcast v8 <string|string|>
    v10: int = call_java v9.length() {pure,throws}
    v11: int = add v7, v10 {pure}
    v12: any = call_java System.lineSeparator() {impure}
    v13: any = checkcast v12 <string|string|>
    v14: int = call_java v13.length() {pure,throws}
    v15: any = call_java System.lineSeparator() {impure}
    v16: any = checkcast v15 <string|string|>
    v17: int = call_java v16.length() {pure,throws}
    v18: int = add v14, v17 {pure}
    v19: int = add v11, v18 {pure}
    v20: int = add v3, v19 {pure}
    v21: int = const 1
    v22: int = add v2, v21 {pure}
    br bb1(v22, v20)
```

## HirPurity : volatile and impure For loops inside blocks

```babble
def run = fn io (n: int, xs: frozen array of int): int -> (
    var a = 0 ; var i = 0
    while i < n then (
        a += bigInt((for e in xs then (discard(volLen())) ; 1))
        def u = (for e in xs then (discard(bump())) ; 1)
        a += u ; i += 1
    ) ; a
)
assert(run(2, freeze(array.of(1, 2))) == 4 and tick == 4)
```

```hir
;; hir -O0 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a += (call-fn bigInt (block
            (for e#1 in var(xs)
              (block
                (call name(discard) (call-fn volLen))))
            1:int)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
```

```hir
;; hir -O1 fn=run
fn run (n:int, xs:obj) -> int {io}
  (def a 0:int)
  (def i 0:int)
  (while (<:bool var(i) var(n))
    (block
      (set a +=
        (block
          (def x
            (block
              (for e#1 in var(xs)
                (block
                  null))
              1:int))
          var(x)))
      (def u
        (block
          (for e#2 in var(xs)
            (block
              (call name(discard) (call-fn bump))))
          1:int))
      (set a += var(u))
      (set i += 1:int)))
  var(a)
```

```mir
;; mir -O2 fn=run stage=before
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: int = const 0
    v3: int = const 0
    v4: any = const null
    br bb1(v4, v3, v2)
  bb1(v5: any, v6: int, v7: int):
    v8: bool = lt v6, v0 {pure}
    br_if v8, bb3, bb2
  bb2:
    ret v7
  bb3:
    v9: any = for_init.array v1
    v10: any = const null
    v11: int = array_len v9 {read:frozen}
    v12: int = const 0
    br bb4(v10, v12)
  bb4(v13: any, v14: int):
    v15: bool = for_has_next v9, v14, v11
    br_if v15, bb10, bb5
  bb5:
    v16: int = const 1
    v17: int = add v7, v16 {pure}
    v18: any = for_init.array v1
    v19: any = const null
    v20: int = array_len v18 {read:frozen}
    v21: int = const 0
    br bb6(v19, v21)
  bb6(v22: any, v23: int):
    v24: bool = for_has_next v18, v23, v20
    br_if v24, bb8, bb7
  bb7:
    v25: int = const 1
    v26: int = add v17, v25 {pure}
    v27: int = const 1
    v28: int = add v6, v27 {pure}
    v29: any = convert.any v28
    br bb1(v29, v28, v26)
  bb8:
    v30: int = for_next v18, v23
    v31: any = opaque.NameRead(discard) {read:shared,throws}
    v32: any = opaque.NameRead(bump) {read:shared}
    v33: int = call bump() {impure}
    v34: any = opaque.DynamicCall(v31, v33) {impure}
    br bb9
  bb9:
    v35: int = const 1
    v36: int = add v23, v35 {pure}
    br bb6(v34, v36)
  bb10:
    v37: int = for_next v9, v14
    v38: any = const null
    br bb11
  bb11:
    v39: int = const 1
    v40: int = add v14, v39 {pure}
    br bb4(v38, v40)
```

```mir
;; mir -O2 fn=run
fn run(v0: int, v1: any) -> int:
  bb0:
    v2: int = const 0
    br bb1(v2, v2)
  bb1(v3: int, v4: int):
    v5: bool = lt v3, v0 {pure}
    br_if v5, bb3, bb2
  bb2:
    ret v4
  bb3:
    v6: any = for_init.array v1
    v7: int = array_len v6 {read:frozen}
    br bb4(v2)
  bb4(v8: int):
    v9: bool = for_has_next v6, v8, v7
    br_if v9, bb10, bb5
  bb5:
    v10: int = const 1
    v11: int = add v4, v10 {pure}
    v12: any = for_init.array v1
    v13: int = array_len v12 {read:frozen}
    br bb6(v2)
  bb6(v14: int):
    v15: bool = for_has_next v12, v14, v13
    br_if v15, bb8, bb7
  bb7:
    v16: int = add v11, v10 {pure}
    v17: int = add v3, v10 {pure}
    br bb1(v17, v16)
  bb8:
    v18: int = for_next v12, v14
    v19: any = opaque.NameRead(discard) {read:shared,throws}
    v20: any = opaque.NameRead(bump) {read:shared}
    v21: int = call bump() {impure}
    v22: any = opaque.DynamicCall(v19, v21) {impure}
    br bb9
  bb9:
    v23: int = add v14, v10 {pure}
    br bb6(v23)
  bb10:
    v24: int = for_next v6, v8
    br bb11
  bb11:
    v25: int = const 1
    v26: int = add v8, v25 {pure}
    br bb4(v26)
```
