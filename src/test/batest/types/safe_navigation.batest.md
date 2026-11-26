# Safe navigation operator (`?.`) : field/method chaining, short-circuit arguments, and non-nullable errors

References : BIP008 (nullable types and safe navigation)

```babble
;; babble preamble
def Node = class (def value: int, def next: Node? = null) (
    def add = fn (k: int): Node -> ( Node(this.value + k, this.next) )
    def read = fn (): int -> ( this.value )
)
```

## Case 1

```babble
def head: Node? = Node(10, Node(20))
def empty: Node? = null
assert(head?.value == 10)
assert(head?.next?.value == 20)
assert(head?.next?.next?.value == null)
assert(empty?.value == null)
```

## Case 2

```babble
def head: Node? = Node(10, Node(20))
def empty: Node? = null
assert(head?.add(5)?.read() == 15)
assert(empty?.add(5)?.read() == null)
assert(empty?.next?.read() == null)
```

## Short-circuit : null receiver must not evaluate method arguments

```babble
var hits = 0
def side = fn io (): int -> ( hits += 1 ; 99 )
def C = class (def x: int) (
    def calc = fn (k: int): int -> ( this.x + k )
)
def present: C? = C(1)
def absent: C? = null
assert(absent?.calc(side()) == null)
assert(hits == 0)
assert(present?.calc(side()) == 100)
assert(hits == 1)
```

## Case 4

```babble
def C = class (def v: int?) ()
def c: C? = C(null)
assert(c?.v == null)
```

## Error : Not nullable : n

```babble
def n = 5
print(n?.foo)
```

```error
Not nullable : n
```

## Multiline safe navigation chain and int to double? widening

```babble
def a1: Node? = Node(10, Node(20))
def a0: Node? = null
def chained: int? = a1
    ?.next
    ?.add(5)
    ?.read()
assert(chained == 25)
def d1: double? = a1?.read()
def d0: double? = a0?.read()
assert(d1 is double and d1 == 10.0)
assert(d0 == null)
```

## Safe navigation on a nullable union of Babble classes

```babble
def VecA = record (x: int, tag: string = "A") ()
def VecB = record (x: int, tag: string = "B") ()
def maybeVec: VecA | VecB | null = VecB(7)
def nullVec: VecA | VecB | null = null
assert(maybeVec?.tag == "B")
assert(nullVec?.tag == null)
```

## Safe navigation producing a nullable generic method reference

```babble
def Box = class () (
    def id of T = fn (x: T): T -> ( x )
)
def a1: Box? = Box()
def a0: Box? = null
def optId: (fn (x: int): int)? = a1?.id
def nullId: (fn (x: int): int)? = a0?.id
assert(optId != null and optId(7) == 7)
assert(nullId == null)
```
