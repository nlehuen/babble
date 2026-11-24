# Axiom nullability (`class notnull`, `T!`) and inferred effects (`fn ...`)

References : `BIP030` (`class notnull`, universal `T!`, enforced `!`/`?` in Java axioms, `fn ... : T!`, return-only axioms, specificity, override contracts, diagnostics).

## Universal `T!` equals `T` and strips null from nullable types and aliases

```babble
def Maybe = type string?
def a: string! = "hi"
def b: string = a
def c: (string?)! = a
def d: Maybe! = a
def f = fn (x: int!): string! -> ("" + x)
assert(b == "hi" and c == "hi" and d == "hi" and f(7) == "7")
```

## Casts `as string!`, `(x as string?)!`, and `is string!` narrowing

```babble
def v: any? = "ok"
def s1: string = v as string!
def s2: string = (v as string?)!
def s3: string = if v is string! then (v) else ("no")
assert(s1 == "ok" and s2 == "ok" and s3 == "ok")
```

## Cannot make null or void non-nullable with `!`

```babble
def x: (null)! = null
```

```error
Cannot make null non-nullable : null
```

## Cannot make void non-nullable with `!`

```babble
def x: void! = null
```

```error
Cannot make null non-nullable : null
```

## Migrated `StringWriter.getBuffer` in `fn nothrow`

```babble
def sameBuf = fn nothrow (sw: java.io.StringWriter!): bool -> (
    def b1: java.lang.StringBuffer = sw.getBuffer()
    def b2: java.lang.StringBuffer = sw.getBuffer()
    same(b1, b2)
)
assert(sameBuf(java.io.StringWriter()))
```

## Migrated `Runtime.getRuntime` and `Thread.getName` in `fn nothrow`

```babble
def rt = fn nothrow (): java.lang.Runtime! -> (java.lang.Runtime.getRuntime())
def tn = fn nothrow (t: java.lang.Thread!): string! -> (t.getName())
assert(rt().availableProcessors() > 0)
assert(tn(java.lang.Thread.currentThread()).length() > 0)
```

## Migrated `Optional.ofNullable` and `orElse` in `fn nothrow`

```babble
def pick = fn nothrow (s: string?): string? -> (
    def o: java.util.Optional of string? = java.util.Optional.ofNullable(s)
    o.orElse("def")
)
assert(pick(null) == "def" and pick("v") == "v")
```

## Migrated `Optional.of`, `get`, and `orElseThrow` in `pure` with `T!`

```babble
def unwrap = fn (o: java.util.Optional of string?): string! -> (o.get())
def make = fn (): string! -> (java.util.Optional.of("ok").orElseThrow())
assert(make() == "ok" and unwrap(java.util.Optional.ofNullable("x")) == "x")
```

## `Optional.of` rejects nullable argument via `T!` parameter

```babble
def s: string? = "maybe"
java.util.Optional.of(s)
```

```error
Type error : expected string, got string?
```

## Migrated `Collections` and `Arrays.asList` in `pure` functions

```babble
def work = fn (): int -> (
    def C = java.util.Collections
    def e: list of string = C.emptyList()
    def s = C.singletonList("a")
    def r: java.util.Comparator of string = C.reverseOrder()
    def a = java.util.Arrays.asList(array.of("x", "y"))
    e.size() + s.size() + a.size() + (if same(r, r) then (1) else (0))
)
assert(work() == 4)
```

## Frame-local `StringTokenizer.nextToken` in `pure`

```babble
def countToks = fn (csv: string!): int -> (
    def st = java.util.StringTokenizer(csv, ",")
    def n = 0
    while st.hasMoreTokens() then (
        def t: string = st.nextToken()
        n += t.length()
    )
    n
)
assert(countToks("a,bb,ccc") == 6)
```

## Concrete `TreeMap` `NavigableMap` reads in `pure`

```babble
def floorVal = fn (k: int): string! -> (
    def m: java.util.TreeMap of (int, string) = java.util.TreeMap()
    m.put(10, "ten")
    def e = m.floorEntry(k)
    if e != null then (e.getValue()) else ("none")
)
assert(floorVal(15) == "ten" and floorVal(1) == "none")
```

## Local `class notnull` with member `: string?` override

```babble
axiom java.io.File: class notnull (
    def getParent: fn ... : string?
)
def f = java.io.File("onlyname")
def name: string = f.getName()
def parent: string? = f.getParent()
assert(name == "onlyname" and parent == null)
```

## Tier-1 local `fn ... : T!` falls through to tier-6 curated and tier-7 bytecode

```babble
axiom java.lang.String.trim: fn ... : string!
axiom java.io.StringWriter.getBuffer: fn ... : java.lang.StringBuffer!
def clean = fn nothrow (s: string!): string! -> (s.trim())
def getBuf = fn nothrow (shared sw: java.io.StringWriter!): java.lang.StringBuffer! -> (sw.getBuffer())
def sw = java.io.StringWriter()
assert(clean("  hi  ") == "hi" and same(getBuf(sw), sw.getBuffer()))
```

## Specificity rank : full signature beats return-only beats wildcard

```babble
axiom java.lang.String: class notnull (
    def *: fn volatile
    def substring: fn ... : string?
    def substring: fn ... (beginIndex: int): string!
)
def tail: string = "hello".substring(1)
def mid: string? = "hello".substring(1, 4)
assert(tail == "ello" and mid == "ell")
```

## Override contract skips `inferredEffects` (`fn ... : any!`)

```babble
axiom java.util.function.Supplier.get: fn ... : any!
def n = 0
def S = class () implements java.util.function.Supplier of string (
    def get = fn io (): string! -> (
        n += 1
        "v=" + n
    )
)
def s: java.util.function.Supplier of string = S()
assert(s.get() == "v=1")
```

## Return-only `fn : any!` without `...` enforces `pure` override contract

```babble
axiom java.util.function.Supplier.get: fn : any!
def n = 0
def S = class () implements java.util.function.Supplier of string (
    def get = fn io (): string! -> (
        n += 1
        "v=" + n
    )
)
```

```error
Function get violates pure guarantee of axiom java.util.function.Supplier.get
```

## Error : `class nullmarked` removed

```babble
axiom java.io.File: class nullmarked ()
```

```error
Unknown axiom class modifier : nullmarked
```

## Error : unmarked concrete return reference type in Java axiom

```babble
axiom java.io.File.getName: fn ... : string
```

```error
Reference type in axiom must specify nullability with ! or ? : string
```

## Error : unmarked parameter reference type in Java axiom

```babble
axiom java.lang.String.valueOf: fn (obj: any): string!
```

```error
Reference type in axiom must specify nullability with ! or ? : any
```

## Error : unmarked nested generic type argument in Java axiom

```babble
axiom java.lang.System.getenv: fn io (): java.util.Map of (string, string!)!
```

```error
Reference type in axiom must specify nullability with ! or ? : string
```

## Error : unmarked array element reference type in Java axiom

```babble
axiom java.util.Arrays.toString: fn ... (a: (array of any)!): string!
```

```error
Reference type in axiom must specify nullability with ! or ? : any
```

## Error : unmarked array itself in Java axiom

```babble
axiom java.util.Arrays.toString: fn ... (a: array of int): string!
```

```error
Reference type in axiom must specify nullability with ! or ? : array
```

## Error : primitive type does not take `!` in Java axiom

```babble
axiom java.lang.Math.abs: fn nothrow (a: int!): int
```

```error
Primitive type does not take ! in axiom : int!
```

## Error : `...` cannot be combined with explicit effect words

```babble
axiom java.lang.String.trim: fn ... nothrow (): string!
```

```error
... cannot be combined with effect modifiers in axiom : java.lang.String.trim
```

## Error : `...` cannot be combined with parameter effect modifiers

```babble
axiom java.util.List.add: fn ... (shared e: any!): bool
```

```error
... cannot be combined with effect modifiers in axiom : java.util.List.add
```

## Error : `...` requires a signature or return type

```babble
axiom java.lang.String.trim: fn ...
```

```error
... requires a signature or return type in axiom : java.lang.String.trim
```

## Error : `...` is only valid on Java axioms

```babble
axiom myHostFn: fn ... (x: int): int
```

```error
... is only valid on Java axioms : myHostFn
```

## Error : constructor axiom `new` cannot declare a return type

```babble
axiom java.lang.StringBuilder: class () static (
    def new: fn ... : java.lang.StringBuilder!
)
```

```error
Constructor axiom new cannot declare a return type : java.lang.StringBuilder
```
