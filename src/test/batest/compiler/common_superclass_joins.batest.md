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

```mir
;; mir -O2 fn=pickRoot stage=before,final
fn pickRoot(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(C2) {read:shared}
    v2: any = opaque.DynamicCall(v1) {impure}
    br bb3(v2)
  bb2:
    v3: any = opaque.NameRead(C1) {read:shared}
    v4: any = opaque.DynamicCall(v3) {impure}
    br bb3(v4)
  bb3(v5: any):
    ret v5
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

```mir
;; mir -O2 fn=joinD stage=before,final
fn joinD(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(D2) {read:shared}
    v2: any = opaque.DynamicCall(v1) {impure}
    br bb3(v2)
  bb2:
    v3: any = opaque.NameRead(D1) {read:shared}
    v4: any = opaque.DynamicCall(v3) {impure}
    br bb3(v4)
  bb3(v5: any):
    ret v5
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

```mir
;; mir -O2 fn=joinIface stage=before,final
fn joinIface(v0: bool, v1: any, v2: any) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    br bb3(v2)
  bb2:
    br bb3(v1)
  bb3(v3: any):
    v4: any = opaque.DynamicSelect(v3.tag) {impure}
    v5: any = opaque.DynamicCall(v4) {impure}
    ret v5
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

```mir
;; mir -O2 fn=pickBuf stage=before,final
fn pickBuf(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = const "bc"
    v2: any = new_java StringBuffer(v1) {impure}
    br bb3(v2)
  bb2:
    v3: any = const "a"
    v4: any = new_java StringBuilder(v3) {impure}
    br bb3(v4)
  bb3(v5: any):
    ret v5
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

```mir
;; mir -O2 fn=joinBuf stage=before,final
fn joinBuf(v0: bool) -> int:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = const "bc"
    v2: any = new_java StringBuffer(v1) {impure}
    br bb3(v2)
  bb2:
    v3: any = const "a"
    v4: any = new_java StringBuilder(v3) {impure}
    br bb3(v4)
  bb3(v5: any):
    v6: any = opaque.DynamicSelect(v5.length) {impure}
    v7: any = opaque.DynamicCall(v6) {impure}
    v8: int = convert.int v7
    ret v8
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

```mir
;; mir -O2 fn=pickList stage=before,final
fn pickList(v0: bool) -> any:
  bb0:
    v1: any = new_java ArrayList() {pure,throws}
    v2: any = new_java LinkedList() {pure,throws}
    br_if v0, bb2, bb1
  bb1:
    br bb3(v2)
  bb2:
    br bb3(v1)
  bb3(v3: any):
    ret v3
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

```mir
;; mir -O2 fn=joinCol stage=before,final
fn joinCol(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = const "b"
    v2: any = call_java Collections.singleton(v1) {impure}
    v3: any = checkcast v2 <java|java.util.Set|java.util.Set>
    br bb3(v3)
  bb2:
    v4: any = const "a"
    v5: any = call_java Collections.singletonList(v4) {impure}
    v6: any = checkcast v5 <java|java.util.List|java.util.List>
    br bb3(v6)
  bb3(v7: any):
    ret v7
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

```mir
;; mir -O2 fn=pickColl stage=before,final
fn pickColl(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: int = const 2
    v2: any = call_java Set.of(v1) {impure}
    v3: any = checkcast v2 <java|java.util.Set|java.util.Set>
    br bb3(v3)
  bb2:
    v4: int = const 1
    v5: any = call_java List.of(v4) {impure}
    v6: any = checkcast v5 <java|java.util.List|java.util.List>
    br bb3(v6)
  bb3(v7: any):
    ret v7
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

```mir
;; mir -O2 fn=pickCls stage=before,final
fn pickCls(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.NameRead(B) {read:shared}
    v2: any = opaque.DynamicCall(v1) {impure}
    br bb3(v2)
  bb2:
    v3: any = opaque.NameRead(A) {read:shared}
    v4: any = opaque.DynamicCall(v3) {impure}
    br bb3(v4)
  bb3(v5: any):
    ret v5
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

```mir
;; mir -O2 fn=pickFn stage=before,final
fn pickFn(v0: bool) -> any:
  bb0:
    br_if v0, bb2, bb1
  bb1:
    v1: any = opaque.FunctionRef(<anon>) {pure}
    br bb3(v1)
  bb2:
    v2: any = opaque.FunctionRef(<anon>) {pure}
    br bb3(v2)
  bb3(v3: any):
    ret v3
```
