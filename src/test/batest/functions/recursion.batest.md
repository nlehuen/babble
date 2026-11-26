# Recursion : recurse keyword, self-recursion, mutual recursion, and return-type inference

References : BIP001 (functions), BIP008 (return-type inference)

```babble
;; babble preamble
def Node of T = class (def v: T, def next: (Node of T)?) ()
def Stack of T = class () (
    var size = 0 ; var head: (Node of T)? = null
    def push = fn mut (shared a: T) -> ( this.size += 1 ; this.head = Node(a, this.head) )
    def first = fn (): T? -> ( def h = this.head ; if h == null then ( null ) else ( h.v ) )
    def pop = fn mut (): T? -> ( def h = this.head ; if h == null then ( null ) else ( this.head = h.next ; this.size -= 1 ; h.v ) )
    def clear = fn mut () -> ( if this.head == null then ( this.size ) else ( this.pop() ; recurse() ) )
    def join = fn (sep = " ") -> (
        var c = this.head ; var s = "" ; var f = true
        while c then ( if f then ( f = false ) else ( s = s + sep ) ; s = s + c.v ; c = c.next )
        s
    )
)
```

## Case 1

```babble
def fact = fn (n : int) : int -> (
    if n <= 1 then ( 1 ) else ( n * recurse(n - 1) )
)
assert(fact(0) == 1)
assert(fact(1) == 1)
assert(fact(6) == 720)
```

## Case 2

```babble
def fib = fn (n : int) : int -> (
    if n <= 1 then ( n ) else ( fib(n - 1) + fib(n - 2) )
)
assert(fib(0) == 0)
assert(fib(1) == 1)
assert(fib(7) == 13)
```

## Case 3

```babble
def isEven = fn (n : int) : bool -> (
    if n == 0 then ( true ) else ( isOdd(n - 1) )
)
def isOdd = fn (n : int) : bool -> (
    if n == 0 then ( false ) else ( isEven(n - 1) )
)
assert(isEven(4) and not isOdd(4))
assert(not isEven(5) and isOdd(5))
```

## Unannotated recursive function infers return type from its non-recursive branch

```babble
def countDown = fn (n : int) -> (
    if n <= 0 then ( 0 ) else ( 1 + countDown(n - 1) )
)
assert(countDown(3) == 3)
```

## Self-recursive function with no non-recursive branch cannot infer return type

```babble
def spin = fn (n : int) -> ( spin(n - 1) )
print(spin(1))
```

```error
Missing type : spin (recursive)
```

## QUESTIONABLE : leaks internal synthetic name $recurse in diagnostic instead of recurse

```babble
print(recurse)
```

```error
No such name : $recurse
```

## Unannotated-parameter mutually recursive functions (forward declarations)

```babble
def fib = fn (n): int -> (
    if n < 2 then ( 1 ) else ( fib2(n - 1) + fib2(n - 2) )
)
def fib2 = fn (n): int -> (
    if n < 2 then ( 1 ) else ( fib(n - 1) + fib(n - 2) )
)
assert(fib(0) == 1 and fib(1) == 1 and fib(5) == 8 and fib(8) == 34)
```

## Generic mutable Stack push, pop, and join with truthiness while loop

```babble
def create of T = fn (): unique Stack of T -> ( Stack() )
def l: Stack of string = create()
assert(l.size == 0)
l.push("world") ; l.push("hello")
assert(l.size == 2 and l.join("/") == "hello/world" and l.join() == "hello world")
assert(l.pop() == "hello" and l.size == 1 and l.pop() == "world" and l.size == 0)
```

## Generic mutable Stack recursive clear method using recurse()

```babble
def l: Stack of string = Stack()
l.push("c") ; l.push("b") ; l.push("a")
assert(l.size == 3)
assert(l.clear() == 0)
assert(l.size == 0 and l.first() == null)
```
