# Primitive Unboxing : Captured Primitive Cells and Uninitialized Errors

References : BIP011 (§7 primitive unboxing), BIP001 (RM001/RM003 lowering)



## Captured mutable ByteCell, ShortCell, and CharCell

```babble
def make = fn () -> (
    def b : byte = 1; def s : short = 10; def c : char = 'a'
    fn io () : string -> (
        b = byte(int(b) + 1); s = short(int(s) + 2); c = char(int(c) + 1)
        string(b) + ":" + string(s) + ":" + string(c)
    )
)
def step = make()
assert(step() == "2:12:b" and step() == "3:14:c")
```

```hir
;; hir -O0,-O1 fn=make
fn make () -> obj {nothrow unique}
  (def b 1:byte)
  (def s 10:short)
  (def c 'a')
  (fn-ref <anon#0>)
```

```mir
;; mir -O2 fn=make stage=before,final
fn make() -> any:
  bb0:
    v0: byte = const 1
    slot_init b, v0 {write:shared}
    v1: short = const 10
    slot_init s, v1 {write:shared}
    v2: char = const 'a'
    slot_init c, v2 {write:shared}
    v3: any = opaque.FunctionRef(<anon>) {read:shared}
    ret v3
```

## Captured mutable LongCell and FloatCell

```babble
def make = fn () -> (
    def l : long = 100L; def f : float = 1.5f
    fn io () : string -> (
        l = l + 10L; f = f + 0.5f
        string(l) + ":" + string(f)
    )
)
def step = make()
assert(step() == "110:2.0" and step() == "120:2.5")
```

```hir
;; hir -O0,-O1 fn=make
fn make () -> obj {nothrow unique}
  (def l 100:long)
  (def f 1.5:float)
  (fn-ref <anon#0>)
```

```mir
;; mir -O2 fn=make stage=before,final
fn make() -> any:
  bb0:
    v0: long = const 100L
    slot_init l, v0 {write:shared}
    v1: float = const 1.5f
    slot_init f, v1 {write:shared}
    v2: any = opaque.FunctionRef(<anon>) {read:shared}
    ret v2
```

## Error : Name used before definition : x

```babble
def run = fn () : byte -> (
    def f = fn () : byte -> ( x )
    def r = f()
    def x : byte = 1
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> byte {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:byte)
  var(r)
```

## Error : Name used before definition : x (2)

```babble
def run = fn () : byte -> (
    def f = fn () : byte -> ( x = byte(2) )
    def r = f()
    def x : byte = 1
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> byte {pure unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:byte)
  var(r)
```

## Error : Name used before definition : x (3)

```babble
def run = fn () : short -> (
    def f = fn () : short -> ( x )
    def r = f()
    def x : short = 1
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> short {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:short)
  var(r)
```

## Error : Name used before definition : x (4)

```babble
def run = fn () : short -> (
    def f = fn () : short -> ( x = short(2) )
    def r = f()
    def x : short = 1
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> short {pure unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:short)
  var(r)
```

## Error : Name used before definition : x (5)

```babble
def run = fn () : long -> (
    def f = fn () : long -> ( x )
    def r = f()
    def x : long = 1L
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> long {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:long)
  var(r)
```

## Error : Name used before definition : x (6)

```babble
def run = fn () : long -> (
    def f = fn () : long -> ( x = 2L )
    def r = f()
    def x : long = 1L
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> long {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1:long)
  var(r)
```

## Error : Name used before definition : x (7)

```babble
def run = fn () : float -> (
    def f = fn () : float -> ( x )
    def r = f()
    def x : float = 1.0f
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> float {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1.0:float)
  var(r)
```

## Error : Name used before definition : x (8)

```babble
def run = fn () : float -> (
    def f = fn () : float -> ( x = 2.0f )
    def r = f()
    def x : float = 1.0f
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> float {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 1.0:float)
  var(r)
```

## Error : Name used before definition : x (9)

```babble
def run = fn () : char -> (
    def f = fn () : char -> ( x )
    def r = f()
    def x : char = 'a'
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> char {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 'a')
  var(r)
```

## Error : Name used before definition : x (10)

```babble
def run = fn () : char -> (
    def f = fn () : char -> ( x = 'b' )
    def r = f()
    def x : char = 'a'
    r
)
run()
```

```error
Name used before definition : x
```

```hir
;; hir -O0,-O1 fn=run
fn run () -> char {nothrow unique}
  (def f (fn-ref f))
  (def r (call-fn f))
  (def x 'a')
  var(r)
```
