# Cross-input inline caches in BabbleSession (new classes/callables defined in subsequent inputs)

References : BIP004 (sessions), BIP002 (interfaces), Bootstrap (CallSite / SelectCache)

```babble
;; babble preamble
def Named = interface (
    def label : fn () : string
)
def show = fn (n : Named) : string -> ( n.label() )
def First = class (def k : int) implements Named (
    def label = fn () : string -> ( "F" + this.k )
)
```

## Polymorphic interface dispatch transitions SelectCache/CallSite across ScriptClassLoaders

```babble
;; babble session
assert(show(First(1)) == "F1")
```

```babble
;; babble session
def Second = class (def s : string) implements Named (
    def label = fn () : string -> ( "S:" + this.s )
)
assert(show(First(2)) == "F2")
assert(show(Second("x")) == "S:x")
```

```babble
;; babble session
def Third = class () implements Named (
    def label = fn () : string -> ( "T" )
)
assert(show(Second("y")) == "S:y")
assert(show(Third()) == "T")
```

## First-class callables and new closures invoked across session inputs

```babble
;; babble session
def apply = fn (f : fn (Named) : string, n : Named) : string -> ( f(n) )
assert(apply(show, First(1)) == "F1")
```

```babble
;; babble session
def Second = class (def s : string) implements Named (
    def label = fn () : string -> ( "S:" + this.s )
)
def wrap = fn (n : Named) : string -> ( "[" + n.label() + "]" )
assert(apply(show, Second("a")) == "S:a")
assert(apply(wrap, Second("a")) == "[S:a]")
```
