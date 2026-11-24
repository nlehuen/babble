# Deep immutability by mode crossing : one cached modal kind per record, shared by the type

checker and the effect checker. A record crosses what its fields' types cross ; a generic
record, or a Java class the axioms call immutable (Map.Entry), what its type arguments cross.
References : BIP027-I5 (ModalKindTable, docs/design/bip027_tackling-complexity-in-the-compiler.md §3.3), BIP016, BIP019

```babble
;; babble preamble
def Box = class (def v : int) ()
def Pair of (A, B) = record (a : A, b : B) ()
def Node = record (v : int, next : Node?) ()
def Holder = class (def x : any?) ()
```

## Case 1

```babble
def keep = fn (mut h : Holder, p : Pair of (int, string)) -> ( h.x = p )
def hh = Holder(null)
keep(hh, Pair(1, "a"))
assert(hh.x is Pair)
```

## Error : Borrowed parameter p escapes via field .x : mark parameter as shared p

```babble
def keep = fn (mut h : Holder, p : Pair of (int, Box)) -> ( h.x = p )
```

```error
Borrowed parameter p escapes via field .x : mark parameter as shared p
```

## A recursive record crosses : its kind assumes itself while it is computed.

```babble
def keep = fn (mut h : Holder, n : Node) -> ( h.x = n )
def hh = Holder(null)
keep(hh, Node(1, Node(2, null)))
assert((hh.x as Node).next!.v == 2)
```

## The effect checker reads an immutable Java class's type arguments too.

```babble
def f = fn (e : java.util.Map.Entry of (string, Box)) : int -> (
    e.getValue().v = 1
    1
)
```

```error
Cannot mutate parameter e (field assignment .v) : mark parameter as mut e
```

## Case 5

```babble
def keep = fn (mut h : Holder, e : java.util.Map.Entry of (string, int)) -> ( h.x = e )
def hh = Holder(null)
keep(hh, java.util.Map.entry("k", 1))
assert(hh.x != null)
```
