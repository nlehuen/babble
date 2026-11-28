# Jailbreak : `interop` (`J3`) a Java interface method can be implemented with a `unique` parameter

- **Surface:** `interop` (`RM003` Java interface bridges), amplified to `opt` `R1` by `FbipReusePass`
- **Vector:** `J3` (Java callers cannot pass a `unique` argument)
- **Severity:** `S0` (`-O1` and `-O2` reuse the Java caller's list in place, printing `[1, 9]` where `interpret` and `-O0` print `[1]`)
- **Repro:** `POST /crib?modes=all&fresh=true`
- **Observed:** a Babble interface method implemented with a `unique` parameter is rejected (`Type error : expected (xs : list(int)) : int, got (unique xs : list(int)) : int`), and so is a function with a `unique` parameter adapted to a Java SAM, but a class or object implementing a Java interface method (`Consumer.accept`, `Predicate.test`) may declare its parameter `unique`. Called through the Java interface, including from Java itself (`List.forEach`), the argument is an ordinary shared list, which `list(from: move(xs))` then mutates in place.

## A Java Consumer implementation cannot take a unique parameter

```babble
def C = class () implements java.util.function.Consumer of (list of int) (
    def accept = fn (unique xs: list of int) -> (def r = list(from: move(xs)) ; r.add(9) ; discard(r))
)
def t = fn io (): string -> (def inner = list(1) ; def outer = list(inner) ; outer.forEach(C()) ; string(inner))
```

```error
Type error
```

## A Java Predicate implementation cannot take a unique parameter

```babble
def C = class () implements java.util.function.Predicate of (list of int) (
    def test = fn (unique xs: list of int): bool -> (def r = list(from: move(xs)) ; r.add(9) ; r.size() > 0)
)
```

```error
Type error
```
