# Generalized algebraic data types : Wikipedia `Expr` example

References : [Wikipedia — Generalized algebraic data type § Overview](https://en.wikipedia.org/wiki/Generalized_algebraic_data_type#Overview), `BIP002` (`record`s), `BIP008` (unions and generics), `BIP021` (`overload`), `BIP023` (`type` aliases), `BIP038` (exhaustive `match`).

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
def Nil = record () ()
def Cons of A = record (head: A, tail: Nil | Cons of A) ()
def List of A = type Nil | Cons of A

def EBool = record (a: bool) ()
def EInt = record (a: int) ()
def EEqual = record (a: EInt, b: EInt) ()

def ExprBool = type EBool | EEqual
def ExprInt = type EInt
def Expr = type ExprBool | ExprInt

def evalInt = fn (e: ExprInt): int -> (match e case is EInt(def a) then (a))
def evalBool = fn (e: ExprBool): bool -> (
    match e
    case is EBool(def a) then (a)
    case is EEqual(def a, def b) then (evalInt(a) == evalInt(b))
)
def evalExpr = fn (e: Expr): bool | int -> (
    match e
    case def b: ExprBool then (evalBool(b))
    case def n: ExprInt then (evalInt(n))
)
def eval = overload (evalBool, evalInt, evalExpr)
```

## Parametric List ADT (non-GADT contrast)

```babble
def headOr of A = fn (xs: List of A, d: A): A -> (
    match xs
    case is Nil() then (d)
    case is Cons(def h, _) then (h)
)
def integers: List of int = Cons(12, Cons(107, Nil()))
def strings: List of string = Cons("boat", Cons("dock", Nil()))
assert(headOr(integers, 0) == 12 and headOr(strings, "") == "boat")
assert(headOr(Nil(), 99) == 99)
```

## Expr GADT : Wikipedia expr1 and typed evaluation

```babble
def expr1: ExprBool = EEqual(EInt(2), EInt(3))
def ret: bool = eval(expr1)
assert(not ret)
assert(eval(EEqual(EInt(7), EInt(7))))
assert(eval(EBool(true)) and not eval(EBool(false)))
def n: int = eval(EInt(42))
assert(n == 42)
```

## Expr union : heterogeneous evaluation returns bool | int

```babble
def pick = fn (flag: bool): Expr -> (if flag then (EInt(2)) else (EEqual(EInt(2), EInt(3))))
def a: bool | int = eval(pick(true))
def b: bool | int = eval(pick(false))
assert(a == 2 and b == false)
```

## Expr GADT : EEqual rejects ExprBool at compile time

```babble
def bad = EEqual(EBool(true), EInt(3))
```

```error
Type error : expected EInt, got EBool
```

## Expr GADT : eval return type matches ExprBool

```babble
def expr1: ExprBool = EEqual(EInt(2), EInt(3))
def bad: int = eval(expr1)
```

```error
Type error : expected int, got bool
```

## Expr GADT : EBool is not assignable to ExprInt

```babble
def bad: ExprInt = EBool(true)
```

```error
Type error : expected EInt, got EBool
```
