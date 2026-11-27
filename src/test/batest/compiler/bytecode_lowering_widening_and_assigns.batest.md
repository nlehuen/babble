# Bytecode Lowering : Negation, Widening, and Compound Assigns

References : BIP001, BIP014 ; targets CodeGenerator, HirBuilder, HirEmitter

```babble
;; babble preamble
def dynNum = fn (v : int | double) : int | double -> ( v )
```



## Unary negation on int, double, and int | double union

```babble
def neg = fn (a : int, b : double, c : int | double) -> (
    assert(-a == -4)
    assert(-b == -1.5)
    assert(-c == -9)
)
neg(4, 1.5, 9)
```

```hir
;; hir -O0,-O1 fn=neg
fn neg (a:int, b:double, c:obj) -> bool {pure unique}
  (call name(assert) (==:bool (neg var(a)) -4:int))
  (call name(assert) (==:bool (neg var(b)) -1.5:double))
  (call name(assert) (==:bool (neg var(c)) -9:int))
```

```hir
;; hir -O2 fn=neg
fn neg (a:int, b:double, c:obj) -> bool {pure unique}
  (call name(assert) (==:bool (neg var(a)) -4:int))
  (call name(assert) (==:bool (neg var(b)) -1.5:double))
  (call name(assert) (==:bool (neg var(c)) -9:int))
```

## Widening return, as-cast, block/while double widening, and methodless object

```babble
def wf1 = fn () : double -> ( 1 )
def wf2 = fn (flag : bool) : double -> ( if flag then ( 1 ) else ( 2.5 ) )
var k = 0
def wd : double? = while k < 2 then ( k += 1 ; 42 )
def bd : double = ( def t = 3 ; t )
def obj = object ( def k : int = 7 )
assert(wf1() == 1.0 and wf2(true) == 1.0 and (1 as double) == 1.0 and ((1 + 2) as double) == 3.0)
assert(wd == 42.0 and bd == 3.0 and obj.k == 7)
```

```hir
;; hir -O0 fn=wf1
fn wf1 () -> double {nothrow unique}
  (widen[double] 1:int)
```

```hir
;; hir -O1 fn=wf1
fn wf1 () -> double {nothrow unique}
  1.0:double
```

```hir
;; hir -O0 fn=wf2
fn wf2 (flag:bool) -> double {nothrow}
  (if var(flag) (widen[double] (block 1:int)) (block 2.5:double))
```

```hir
;; hir -O1 fn=wf2
fn wf2 (flag:bool) -> double {nothrow}
  (if var(flag) 1.0:double 2.5:double)
```

```hir
;; hir -O2 fn=wf2
fn wf2 (flag:bool) -> double {nothrow}
  (if var(flag) 1.0:double 2.5:double)
```

## Widening assignments on local and Babble int field

```babble
var x = 1
def b = (class () ( var v : int = 2 ))()
def d1 : double = (x = 5) ; def d2 : double = (x += 3)
def d3 : double = (b.v = 7) ; def d4 : double = (b.v += 2)
def d5 : double = (b.v -= 1) ; def d6 : double = (b.v *= 2)
assert(d1 == 5.0 and d2 == 8.0 and d3 == 7.0 and d4 == 9.0 and d5 == 8.0 and d6 == 16.0)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 1:int)
  (def b (call (block (class-ref class))))
  (def d1 (widen[double] (block (set-name x 5:int))))
  (def d2 (widen[double] (block (set-name x += 3:int))))
  (def d3 (widen[double] (block (set-field name(b).v 7:int))))
  (def d4 (widen[double] (block (set-field name(b).v 2:int))))
  (def d5 (widen[double] (block (set-field name(b).v 1:int))))
  (def d6 (widen[double] (block (set-field name(b).v 2:int))))
  (call name(assert) (and (and (and (and (and (==:bool name(d1) 5.0:double) (==:bool name(d2) 8.0:double)) (==:bool name(d3) 7.0:double)) (==:bool name(d4) 9.0:double)) (==:bool name(d5) 8.0:double)) (==:bool name(d6) 16.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 1:int)
  (def b (call (class-ref class)))
  (def d1 (widen[double] (set-name x 5:int)))
  (def d2 (widen[double] (set-name x += 3:int)))
  (def d3 (widen[double] (set-field name(b).v 7:int)))
  (def d4 (widen[double] (set-field name(b).v 2:int)))
  (def d5 (widen[double] (set-field name(b).v 1:int)))
  (def d6 (widen[double] (set-field name(b).v 2:int)))
  (call name(assert) (and (and (and (and (and (==:bool name(d1) 5.0:double) (==:bool name(d2) 8.0:double)) (==:bool name(d3) 7.0:double)) (==:bool name(d4) 9.0:double)) (==:bool name(d5) 8.0:double)) (==:bool name(d6) 16.0:double)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def x 1:int)
  (def b (call (class-ref class)))
  (def d1 (widen[double] (set-name x 5:int)))
  (def d2 (widen[double] (set-name x += 3:int)))
  (def d3 (widen[double] (set-field name(b).v 7:int)))
  (def d4 (widen[double] (set-field name(b).v 2:int)))
  (def d5 (widen[double] (set-field name(b).v 1:int)))
  (def d6 (widen[double] (set-field name(b).v 2:int)))
  (call name(assert) (and (and (and (and (and (==:bool name(d1) 5.0:double) (==:bool name(d2) 8.0:double)) (==:bool name(d3) 7.0:double)) (==:bool name(d4) 9.0:double)) (==:bool name(d5) 8.0:double)) (==:bool name(d6) 16.0:double)))
```

## Widening assignments on Java int field

```babble
def st = java.io.StreamTokenizer(java.io.StringReader("a"))
def d7 : double = (st.ttype = 4)
def d8 : double = (st.ttype += 2)
assert(d7 == 4.0 and d8 == 6.0)
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "a")))
  (def d7 (widen[double] (block (set-jfield ttype name(st) 4:int))))
  (def d8 (widen[double] (block (set-jfield ttype name(st) 2:int))))
  (call name(assert) (and (==:bool name(d7) 4.0:double) (==:bool name(d8) 6.0:double)))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "a")))
  (def d7 (widen[double] (set-jfield ttype name(st) 4:int)))
  (def d8 (widen[double] (set-jfield ttype name(st) 2:int)))
  (call name(assert) (and (==:bool name(d7) 4.0:double) (==:bool name(d8) 6.0:double)))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def st (jnew StreamTokenizer (jnew StringReader "a")))
  (def d7 (widen[double] (set-jfield ttype name(st) 4:int)))
  (def d8 (widen[double] (set-jfield ttype name(st) 2:int)))
  (call name(assert) (and (==:bool name(d7) 4.0:double) (==:bool name(d8) 6.0:double)))
```

## Boxed/fallback compound assignments on union local and instance fields

```babble
def C = class (var n : int, var d : double) (
    def run = fn mut () -> (
        var loc : int | double = 9
        loc /= 2 ; loc += dynNum(2)
        this.n *= 2 ; this.n -= 1
        this.d /= 2 ; this.d += dynNum(2)
        assert(loc == 6.5 and this.n == 17 and this.d == 5.0)
    )
)
C(9, 6.0).run()
```

```hir
;; hir -O0 fn=C.run
fn C.run () -> bool {mut unique}
  (def loc (box 9:int))
  (set loc /= 2:int)
  (set loc += (call-fn dynNum (box 2:int)))
  (set-this-field n *= 2:int)
  (set-this-field n -= 1:int)
  (set-this-field d /= 2:int)
  (set-this-field d += (call-fn dynNum (box 2:int)))
  (call name(assert) (and (and (==:bool var(loc) 6.5:double) (==:bool (get-this-field n) 17:int)) (==:bool (get-this-field d) 5.0:double)))
```

```hir
;; hir -O1 fn=C.run
fn C.run () -> bool {mut unique}
  (def loc (box 9:int))
  (set loc /= 2:int)
  (set loc +=
    (block
      (def v#1 (box 2:int))
      var(v#1)))
  (set-this-field n *= 2:int)
  (set-this-field n -= 1:int)
  (set-this-field d /= 2:int)
  (set-this-field d += (block
      (def v#2 (box 2:int))
      var(v#2)))
  (call name(assert) (and (and (==:bool var(loc) 6.5:double) (==:bool (get-this-field n) 17:int)) (==:bool (get-this-field d) 5.0:double)))
```

```hir
;; hir -O2 fn=C.run
fn C.run () -> bool {mut unique}
  (def loc (box 9:int))
  (set loc /= 2:int)
  (block
    (def $cse0 (box 2:int))
    (set loc += var($cse0))
    (set-this-field n *= 2:int)
    (set-this-field n -= 1:int)
    (set-this-field d /= 2:int)
    (set-this-field d += var($cse0)))
  (call name(assert) (and (and (==:bool var(loc) 6.5:double) (==:bool (get-this-field n) 17:int)) (==:bool (get-this-field d) 5.0:double)))
```

## Boxed/fallback compound assignment on static union field

```babble
def StaticBox = class () () static (
    private var sx : int | double = 9
    def go = fn io () : int | double -> ( this.sx /= 2 ; this.sx )
)
assert(StaticBox.go() == 4.5)
```

```hir
;; hir -O0,-O1 fn=go
fn go () -> obj {io}
  (set-this-field sx /= 2:int)
  (get-this-field sx)
```

```hir
;; hir -O2 fn=go
fn go () -> obj {io}
  (set-this-field sx /= 2:int)
  (get-this-field sx)
```

## Java long/float arg lowering, paren-wrapped static chain, and static field on non-pure Java receiver

```babble
def mkSt = fn io () : java.io.StreamTokenizer -> ( java.io.StreamTokenizer(java.io.StringReader("hi")) )
assert(java.lang.Long.sum(3, 4) == 7)
assert(java.lang.Float.sum(3, 4) == 7.0)
assert((java.lang.Math).abs(-5) == 5)
assert((java.lang.Math).abs(-9) == 9)
assert(mkSt().TT_EOF == -1)
```

```hir
;; hir -O0,-O1 fn=mkSt
fn mkSt () -> obj {io}
  (jnew StreamTokenizer (jnew StringReader "hi"))
```

```hir
;; hir -O0 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def mkSt (fn-ref mkSt))
  (call name(assert) (==:bool (jcall Long.sum 3:long 4:long) (convert[long] 7:int)))
  (call name(assert) (==:bool (convert[double] (jcall Float.sum (widen[float] 3:int) (widen[float] 4:int))) 7.0:double))
  (call name(assert) (==:bool (jcall Math.abs -5:int) 5:int))
  (call name(assert) (==:bool (jcall Math.abs -9:int) 9:int))
  (call name(assert) (==:bool (get-jfield StreamTokenizer.TT_EOF (call-fn mkSt)) -1:int))
```

```hir
;; hir -O1 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def mkSt (fn-ref mkSt))
  (call name(assert) (==:bool (jcall Long.sum 3:long 4:long) 7:long))
  (call name(assert) (==:bool (convert[double] (jcall Float.sum 3.0:float 4.0:float)) 7.0:double))
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) (==:bool (get-jfield StreamTokenizer.TT_EOF (call-fn mkSt)) -1:int))
```

```hir
;; hir -O2 top-level
top-level:
  (def dynNum (fn-ref dynNum))
  (def mkSt (fn-ref mkSt))
  (call name(assert) (==:bool (jcall Long.sum 3:long 4:long) 7:long))
  (call name(assert) (==:bool (convert[double] (jcall Float.sum 3.0:float 4.0:float)) 7.0:double))
  (call name(assert) true:bool)
  (call name(assert) true:bool)
  (call name(assert) (==:bool (get-jfield StreamTokenizer.TT_EOF (call-fn mkSt)) -1:int))
```
