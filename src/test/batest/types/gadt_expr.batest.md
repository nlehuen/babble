# Generalized algebraic data types : Wikipedia `Expr` example

References : [Wikipedia — Generalized algebraic data type § Overview](https://en.wikipedia.org/wiki/Generalized_algebraic_data_type#Overview), `BIP002` (interfaces and `record`s), `BIP008` (generics), `BIP012` (`of` type application).

In Babble, GADTs are encoded via a parameterized `interface` (`Expr of A`) whose `record` constructors implement specific instantiations of the interface (the object-oriented GADT encoding of Kennedy & Russo, 2005, cited on the Wikipedia page).

Quoted from [Wikipedia — Generalized algebraic data type § Overview](https://en.wikipedia.org/wiki/Generalized_algebraic_data_type#Overview) :

> ```haskell
> -- A parametric ADT that is not a GADT
> data List a = Nil | Cons a (List a)
>
> integers :: List Int
> integers = Cons 12 (Cons 107 Nil)
>
> strings :: List String
> strings = Cons "boat" (Cons "dock" Nil)
>
> -- A GADT
> data Expr a where
>     EBool  :: Bool     -> Expr Bool
>     EInt   :: Int      -> Expr Int
>     EEqual :: Expr Int -> Expr Int  -> Expr Bool
>
> eval :: Expr a -> a
> eval e = case e of
>     EBool a    -> a
>     EInt a     -> a
>     EEqual a b -> (eval a) == (eval b)
>
> expr1 :: Expr Bool
> expr1 = EEqual (EInt 2) (EInt 3)
>
> ret = eval expr1 -- False
> ```

```babble
;; babble preamble
def List of A = interface (
    def headOr: fn (d: A): A
)
def Nil of A = record () implements List of A (
    def headOr = fn (d: A): A -> (d)
)
def Cons of A = record (head: A, tail: List of A) implements List of A (
    def headOr = fn (d: A): A -> (this.head)
)

def Expr of A = interface (
    def eval: fn (): A
)
def EBool = record (a: bool) implements Expr of bool (
    def eval = fn (): bool -> (this.a)
)
def EInt = record (a: int) implements Expr of int (
    def eval = fn (): int -> (this.a)
)
def EEqual = record (a: Expr of int, b: Expr of int) implements Expr of bool (
    def eval = fn (): bool -> (this.a.eval() == this.b.eval())
)
def eval of A = fn (e: Expr of A): A -> (e.eval())
```

## Parametric List ADT (non-GADT contrast)

```babble
def integers: List of int = Cons(12, Cons(107, Nil of int ()))
def strings: List of string = Cons("boat", Cons("dock", Nil of string ()))
assert(integers.headOr(0) == 12 and strings.headOr("") == "boat")
assert((Nil of int ()).headOr(99) == 99)
```

## Expr GADT : Wikipedia expr1 and typed evaluation

```babble
def expr1: Expr of bool = EEqual(EInt(2), EInt(3))
def ret: bool = eval(expr1)
assert(not ret)
assert(eval(EEqual(EInt(7), EInt(7))))
assert(eval(EBool(true)) and not eval(EBool(false)))
def n: int = eval(EInt(42))
assert(n == 42)
```

## Expr GADT : EEqual rejects Expr of bool at compile time

```babble
def bad = EEqual(EBool(true), EInt(3))
```

```error
Type error : expected Expr(int), got EBool
```

## Expr GADT : eval return type matches Expr type argument

```babble
def expr1: Expr of bool = EEqual(EInt(2), EInt(3))
def bad: int = eval(expr1)
```

```error
Type error : expected int, got bool
```

## Expr GADT : EBool is not assignable to Expr of int

```babble
def bad: Expr of int = EBool(true)
```

```error
Type error : expected Expr(int), got EBool
```
