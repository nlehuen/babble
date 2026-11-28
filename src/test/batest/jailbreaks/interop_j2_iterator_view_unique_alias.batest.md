# Jailbreak : `interop` (`J2`) an iterator over a `unique` collection does not demote it

- **Surface:** `interop` (curated `view` axioms of `Iterable.iterator` / `List.listIterator`), amplified to `opt` `R1` by `FbipReusePass`
- **Vector:** `J2` (curated axiom drift : a live iterator is an alias of its collection)
- **Severity:** `S0` (`-O1` and `-O2` reuse the list or map in place and the live iterator throws `ConcurrentModificationException`, while `interpret`, `-O0`, `wasm-O1` and `wasm-O2` finish normally)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** `a.subList(0, 1)`, `a.reversed()`, `m.keySet()` and `m.values()` make a later `consume(a)` fail with `Argument for unique parameter xs must be unique`, but `a.iterator()`, `a.listIterator()` and `m.entrySet().iterator()` do not : the iterator stays live over the collection `list(from: move(xs))` then mutates in place.

```babble
;; babble preamble
def addL = fn (unique xs: list of int): list of int -> ( def r = list(from: move(xs)) ; r.add(9) ; r )
def addM = fn (unique m: map of (string, int)): map of (string, int) -> ( def r = map(from: move(m)) ; r.put("z", 9) ; r )
```

## A live list iterator aliases the unique list

```babble
def t = fn (): string -> (
    def a = list(1, 2)
    def it = a.iterator()
    def b = addL(a)
    var s = ""
    while it.hasNext() then (s = s + string(it.next()))
    s + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## A live list iterator from listIterator aliases the unique list

```babble
def t = fn (): string -> (
    def a = list(1)
    def it = a.listIterator()
    def b = addL(a)
    string(it.next()) + string(b)
)
```

```error
Argument for unique parameter xs must be unique
```

## A live entry set iterator aliases the unique map

```babble
def t = fn (): string -> (
    def m = map of (string, int) ()
    m.put("a", 1)
    def it = m.entrySet().iterator()
    def b = addM(m)
    string(it.next()) + string(b)
)
```

```error
Argument for unique parameter m must be unique
```
