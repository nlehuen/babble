# Bytecode frame-merge common superclass and common superinterface joins

References : BIP001, BIP002 ; targets ScriptClassWriter.getCommonSuperClass / commonSuperInterface / ancestorInterfaces / isAssignable

```babble
;; babble preamble
def Root = interface ( def tag : fn () : string )
def I1 = interface extends Root ( def tag = fn () : string -> ( "i1" ) )
def I2 = interface extends Root ( def tag = fn () : string -> ( "i2" ) )
def C1 = class () implements I1 ( def tag = fn () : string -> ( "c1" ) )
def C2 = class () implements I2 ( def tag = fn () : string -> ( "c2" ) )
def D1 = class () implements I1 ()
def D2 = class () implements I2 ()
def A = class () ()
def B = class () ()
```

## Annotated Root joins : class-class and interface-class

```babble
def pickRoot = fn (flag : bool) : Root -> ( if flag then ( C1() ) else ( C2() ) )
def pickMixed = fn (flag : bool, a : I1, b : C2) : Root -> ( if flag then ( a ) else ( b ) )
assert(pickRoot(true).tag() == "c1")
assert(pickRoot(false).tag() == "c2")
assert(pickMixed(true, C1(), C2()).tag() == "c1")
```

```hir
;; hir -O0 fn=pickRoot
fn pickRoot (flag:bool) -> obj {nothrow unique}
  (if var(flag) (block (call name(C1))) (block (call name(C2))))
```

```hir
;; hir -O1 fn=pickRoot
fn pickRoot (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(C1)) (call name(C2)))
```

```hir
;; hir -O2 fn=pickRoot
fn pickRoot (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(C1)) (call name(C2)))
```

## Annotated Root join : interface-interface and inherited-default class-class

```babble
def pickIfaces = fn (flag : bool, a : I1, b : I2) : Root -> ( if flag then ( a ) else ( b ) )
def joinD = fn (flag : bool) : Root -> ( if flag then ( D1() ) else ( D2() ) )
assert(pickIfaces(false, C1(), C2()).tag() == "c2")
assert(joinD(true).tag() == "i1")
assert(joinD(false).tag() == "i2")
```

```hir
;; hir -O0 fn=joinD
fn joinD (flag:bool) -> obj {nothrow unique}
  (if var(flag) (block (call name(D1))) (block (call name(D2))))
```

```hir
;; hir -O1 fn=joinD
fn joinD (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(D1)) (call name(D2)))
```

```hir
;; hir -O2 fn=joinD
fn joinD (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(D1)) (call name(D2)))
```

## Unannotated if/else interface join

```babble
def joinIface = fn (flag : bool, a : I1, b : I2) -> (
    def r = if flag then ( a ) else ( b )
    r.tag()
)
assert(joinIface(true, C1(), C2()) == "c1")
assert(joinIface(false, C1(), C2()) == "c2")
```

```hir
;; hir -O0 fn=joinIface
fn joinIface (flag:bool, a:obj, b:obj) -> obj {nothrow unique}
  (def r (if var(flag) (block var(a)) (block var(b))))
  (call (select var(r).tag))
```

```hir
;; hir -O1 fn=joinIface
fn joinIface (flag:bool, a:obj, b:obj) -> obj {nothrow unique}
  (def r (if var(flag) var(a) var(b)))
  (call (select var(r).tag))
```

```hir
;; hir -O2 fn=joinIface
fn joinIface (flag:bool, a:obj, b:obj) -> obj {nothrow unique}
  (def r (if var(flag) var(a) var(b)))
  (call (select var(r).tag))
```

## Java class hierarchy join : StringBuilder/StringBuffer -> any

```babble
def pickBuf = fn io (flag : bool) : any -> (
    if flag then ( java.lang.StringBuilder("a") ) else ( java.lang.StringBuffer("bc") )
)
assert(java.util.Objects.toString(pickBuf(true)) == "a")
assert(java.util.Objects.toString(pickBuf(false)) == "bc")
```

```hir
;; hir -O0 fn=pickBuf
fn pickBuf (flag:bool) -> obj {io}
  (if var(flag) (block (jnew StringBuilder "a")) (block (jnew StringBuffer "bc")))
```

```hir
;; hir -O1 fn=pickBuf
fn pickBuf (flag:bool) -> obj {io}
  (if var(flag) (jnew StringBuilder "a") (jnew StringBuffer "bc"))
```

```hir
;; hir -O2 fn=pickBuf
fn pickBuf (flag:bool) -> obj {io}
  (if var(flag) (jnew StringBuilder "a") (jnew StringBuffer "bc"))
```

## Java class hierarchy join : unannotated StringBuilder/StringBuffer -> AbstractStringBuilder

```babble
def joinBuf = fn io (flag : bool) : int -> (
    def xs = if flag then ( java.lang.StringBuilder("a") ) else ( java.lang.StringBuffer("bc") )
    xs.length()
)
assert(joinBuf(true) == 1)
assert(joinBuf(false) == 2)
```

```hir
;; hir -O0 fn=joinBuf
fn joinBuf (flag:bool) -> int {io}
  (def xs (if var(flag) (block (jnew StringBuilder "a")) (block (jnew StringBuffer "bc"))))
  (call (select var(xs).length))
```

```hir
;; hir -O1 fn=joinBuf
fn joinBuf (flag:bool) -> int {io}
  (def xs (if var(flag) (jnew StringBuilder "a") (jnew StringBuffer "bc")))
  (call (select var(xs).length))
```

```hir
;; hir -O2 fn=joinBuf
fn joinBuf (flag:bool) -> int {io}
  (def xs (if var(flag) (jnew StringBuilder "a") (jnew StringBuffer "bc")))
  (call (select var(xs).length))
```

## Java class hierarchy join : ArrayList/LinkedList -> AbstractList

```babble
def pickList = fn (flag : bool) : any -> (
    def a : java.util.ArrayList of int = java.util.ArrayList()
    def b : java.util.LinkedList of int = java.util.LinkedList()
    if flag then ( a ) else ( b )
)
assert(classof(pickList(true)) != null)
assert(classof(pickList(false)) != null)
```

```hir
;; hir -O0 fn=pickList
fn pickList (flag:bool) -> obj {pure unique}
  (def a (jnew ArrayList))
  (def b (jnew LinkedList))
  (if var(flag) (block var(a)) (block var(b)))
```

```hir
;; hir -O1 fn=pickList
fn pickList (flag:bool) -> obj {pure unique}
  (def a (jnew ArrayList))
  (def b (jnew LinkedList))
  (if var(flag) var(a) var(b))
```

```hir
;; hir -O2 fn=pickList
fn pickList (flag:bool) -> obj {pure unique}
  (block
    (def $cse0 name(java))
    (def a (jnew ArrayList))
    (def b (jnew LinkedList))
    (if var(flag) var(a) var(b)))
```

## Java interface join : List/Set -> Collection

```babble
def joinCol = fn io (c : bool) : java.util.Collection of string -> (
    if c then ( java.util.Collections.singletonList("a") ) else ( java.util.Collections.singleton("b") )
)
assert(joinCol(true).size() == 1)
assert(joinCol(false).size() == 1)
```

```hir
;; hir -O0 fn=joinCol
fn joinCol (c:bool) -> obj {io}
  (if var(c) (block (jcall Collections.singletonList "a")) (block (jcall Collections.singleton "b")))
```

```hir
;; hir -O1 fn=joinCol
fn joinCol (c:bool) -> obj {io}
  (if var(c) (jcall Collections.singletonList "a") (jcall Collections.singleton "b"))
```

```hir
;; hir -O2 fn=joinCol
fn joinCol (c:bool) -> obj {io}
  (if var(c) (jcall Collections.singletonList "a") (jcall Collections.singleton "b"))
```

## Java interface join : List/Set -> any

```babble
def pickColl = fn io (c : bool) : any -> (
    if c then ( java.util.List.of(1) ) else ( java.util.Set.of(2) )
)
assert(string(pickColl(true)) == "[1]")
assert(string(pickColl(false)) == "[2]")
```

```hir
;; hir -O0 fn=pickColl
fn pickColl (c:bool) -> obj {io}
  (if var(c) (block (jcall List.of 1:int)) (block (jcall Set.of 2:int)))
```

```hir
;; hir -O1 fn=pickColl
fn pickColl (c:bool) -> obj {io}
  (if var(c) (jcall List.of 1:int) (jcall Set.of 2:int))
```

```hir
;; hir -O2 fn=pickColl
fn pickColl (c:bool) -> obj {io}
  (if var(c) (jcall List.of 1:int) (jcall Set.of 2:int))
```

## Unrelated Babble classes and object/string merges -> Object

```babble
def pickCls = fn (flag : bool) : any -> ( if flag then ( A() ) else ( B() ) )
def mixed = fn (flag : bool) : any -> ( if flag then ( object ( def k = 1 ) ) else ( "s" ) )
assert(classof(pickCls(true)) == A)
assert(mixed(false) == "s")
```

```hir
;; hir -O0 fn=pickCls
fn pickCls (flag:bool) -> obj {nothrow unique}
  (if var(flag) (block (call name(A))) (block (call name(B))))
```

```hir
;; hir -O1 fn=pickCls
fn pickCls (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(A)) (call name(B)))
```

```hir
;; hir -O2 fn=pickCls
fn pickCls (flag:bool) -> obj {nothrow unique}
  (if var(flag) (call name(A)) (call name(B)))
```

## Two distinct lambda closures merged as fn () : int

```babble
def pickFn = fn (flag : bool) : fn () : int -> (
    if flag then ( fn () : int -> ( 1 ) ) else ( fn () : int -> ( 2 ) )
)
assert(pickFn(true)() == 1)
assert(pickFn(false)() == 2)
```

```hir
;; hir -O0 fn=pickFn
fn pickFn (flag:bool) -> obj {nothrow}
  (if var(flag) (block (fn-ref <anon#0>)) (block (fn-ref <anon#1>)))
```

```hir
;; hir -O1 fn=pickFn
fn pickFn (flag:bool) -> obj {nothrow}
  (if var(flag) (fn-ref <anon#0>) (fn-ref <anon#1>))
```

```hir
;; hir -O2 fn=pickFn
fn pickFn (flag:bool) -> obj {nothrow}
  (if var(flag) (fn-ref <anon#0>) (fn-ref <anon#1>))
```
