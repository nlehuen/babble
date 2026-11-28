# Jailbreak : `interop` (`J2`) a list whose iterator escaped is still returned as a fresh unique value

- **Surface:** `interop` (curated `iterator` view), amplified to `opt` `R1` by `FbipReusePass`
- **Vector:** `J2` (a live iterator is an alias of its collection), escaping the round-9 rule
- **Severity:** `S0` (`-O1` and `-O2` reuse the list in place under the escaped iterator, which throws `ConcurrentModificationException`, while `interpret`, `-O0`, `wasm-O1` and `wasm-O2` print `12[1, 2, 9]`)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** since round 9, taking an iterator over a `unique` local leaves the local fresh but not `unique`, which stops consuming it in the same frame. But a fresh local is still returned as a fresh value, so after `sink.add(a.iterator())` the function returns `a` as `unique` to its caller, which consumes it while the escaped iterator is live.

```babble
;; babble preamble
def addL = fn (unique xs: list of int): list of int -> ( def r = list(from: move(xs)) ; r.add(9) ; r )
```

## A list whose iterator escaped into a mut sink is not a unique result

```babble
def mk = fn io (mut sink: list of (java.util.Iterator of int)): list of int -> (
    def a = list(1, 2)
    sink.add(a.iterator())
    a
)
def t = fn io (): string -> (
    def its = list of (java.util.Iterator of int) ()
    def b = addL(mk(its))
    def it = its.get(0)
    var s = ""
    while it.hasNext() then (s = s + string(it.next()))
    s + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```
