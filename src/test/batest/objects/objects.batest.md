# Singleton object expressions : member access, mutation, privacy, and scope errors

References : BIP002 (singleton objects)

## Case 1

```babble
def counter = object (
    private def n = 0
    def inc = fn mut (k : int = 1) : int -> ( this.n += k ; this.n )
    def read = fn () : int -> ( this.n )
)
assert(counter.inc() == 1)
assert(counter.inc(4) == 5)
assert(counter.read() == 5)
```

## Error : hidden is private

```babble
def o = object (
    private def hidden = 1
)
print(o.hidden)
```

```error
hidden is private
```

## Error : Not a scope : dyn(1)

```babble
def dyn = fn (v : any) : any -> ( v )
dyn(1).x = 2
```

```error
Not a scope : dyn(1)
```

## 'package' is a regular identifier, not a reserved keyword

```babble
def package = 42
def box = object (
    def package = "ok"
)
assert(package == 42)
assert(box.package == "ok")
```
