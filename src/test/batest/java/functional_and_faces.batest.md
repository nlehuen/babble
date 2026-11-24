# Java functional interfaces (SAM lambdas) and Babble classes implementing Java interfaces

References : BIP002 §13, RM003 (JVM interface bridges & SAM adapters), Faces

## Case 1

```babble
def mapped = java.util.Optional.of(6).map(fn (x: int): int -> ( x * x )).get()
assert(mapped == 36)

def xs: java.util.List of int = java.util.ArrayList()
xs.add(3)
xs.add(1)
xs.add(4)
xs.add(2)
xs.removeIf(fn (x) -> ( x > 2 ))
xs.sort(fn (a, b) -> ( a - b ))
assert(xs.toString() == "[1, 2]")
```

## Case 2

```babble
def Greeter = class (def tag: string) implements java.util.function.Supplier of string (
    def get = fn (): string -> ( "sup:" + this.tag )
)
def sup: java.util.function.Supplier of string = Greeter("ok")
assert(sup.get() == "sup:ok")
```

## Case 3

```babble
def names: java.util.List of string = java.util.ArrayList()
names.add("pear")
names.add("fig")
names.add("apple")
java.util.Collections.sort(names, fn io (a: string, b: string): int -> (
    def la = java.lang.StringBuilder(a).length()
    def lb = java.lang.StringBuilder(b).length()
    la - lb
))
assert(names.toString() == "[fig, pear, apple]")
```
