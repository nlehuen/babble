# Jailbreak : types T5 JavaMethod and ClassValue ignore signature when assigned to Function or SAM
<!-- Surface: types (T5) | Severity: S1/S0 | Repro: POST /crib?modes=all&fresh=true -->

## Bound JavaMethod must check signature when assigned to Function type

```babble
def f: fn (): string = "hello".length
def s: string = f()
println(s.length())
```

```error
Type error
```

## ClassValue constructor must check signature when assigned to Function type

```babble
def Box = class (def x: int) ()
def f: fn (x: int): string = Box
println(f(1).length())
```

```error
Type error
```

## Bound JavaMethod must check signature when assigned to Java SAM interface

```babble
def sup: java.util.function.Supplier of string = "hello".length
println(sup.get().length())
```

```error
Type error
```
