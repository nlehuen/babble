# Classes, constructor parameters, unboxed fields, private members, static blocks, and forward reads

References : BIP002 (classes, static blocks), BIP001 (unboxed fields, $defined bitmask)

```babble
;; babble preamble
def Counter = class (def label : string, seed : int = 0) (
    private def count = seed
    def step = fn mut (delta : int = 1) : int -> (
        this.count += delta
        this.count
    )
    def describe = fn () : string -> ( this.label + "=" + this.count )
) static (
    def initial = 10
    def create = fn (name : string) : Counter -> ( this(name, this.initial) )
)

def Box = class () (
    private def secret = 99
    def get = fn () : int -> ( this.secret )
)
```

## Case 1

```babble
def c = Counter("hits")
assert(c.step() == 1)
assert(c.step(5) == 6)
assert(c.describe() == "hits=6")
```

## Case 2

```babble
def c = Counter.create("hits")
assert(Counter.initial == 10)
assert(c.step() == 11)
assert(c.step(5) == 16)
assert(c.describe() == "hits=16")
```

## Case 3

```babble
def Vec = class (def i : int, def d : double, def b : bool) (
    def sum = fn () : double -> ( this.i + this.d )
    def toggle = fn mut () : bool -> ( this.b = not this.b ; this.b )
)
def v = Vec(3, 1.5, true)
assert(v.sum() == 4.5)
assert(not v.toggle())
assert(not v.b)
```

## Forward read of instance member in constructor body ($defined bitmask)

```babble
def Bad = class () (
    def a = this.b
    def b = 1
)
def x = Bad()
```

```error
Member used before definition : b
```

## Error : secret is private

```babble
def b = Box()
print(b.secret)
```

```error
secret is private
```

## Error : No such member : missing

```babble
def b = Box()
print(b.missing)
```

```error
No such member : missing
```

## Error : Cannot assign method : get

```babble
def b = Box()
b.get = b.get
```

```error
Cannot assign method : get
```

## Error : this outside of a class

```babble
print(this)
```

```error
this outside of a class
```
