# Generalized algebraic data types : Wikipedia HOAS `Lam` example

References : [Wikipedia — Generalized algebraic data type § Higher-order abstract syntax](https://en.wikipedia.org/wiki/Generalized_algebraic_data_type#Higher-order_abstract_syntax), `BIP002` (interfaces and `record`s), `BIP008` (generics), `BIP012` (`of` type application), `BIP016` (effect annotations), `BIP023` (type aliases).

Quoted from [Wikipedia — Generalized algebraic data type § Higher-order abstract syntax](https://en.wikipedia.org/wiki/Generalized_algebraic_data_type#Higher-order_abstract_syntax) :

> ```haskell
> data Lam :: * -> * where
>   Lift :: a                     -> Lam a        -- ^ lifted value
>   Pair :: Lam a -> Lam b        -> Lam (a, b)   -- ^ product
>   Lam  :: (Lam a -> Lam b)      -> Lam (a -> b) -- ^ lambda abstraction
>   App  :: Lam (a -> b) -> Lam a -> Lam b        -- ^ function application
>   Fix  :: Lam (a -> a)          -> Lam a        -- ^ fixed point
>
> eval :: Lam t -> t
> eval (Lift v)   = v
> eval (Pair l r) = (eval l, eval r)
> eval (Lam f)    = \x -> eval (f (Lift x))
> eval (App f x)  = (eval f) (eval x)
> eval (Fix f)    = (eval f) (eval (Fix f))
>
> fact = Fix (Lam (\f -> Lam (\y -> Lift (if eval y == 0 then 1 else eval y * (eval f) (eval y - 1)))))
> eval(fact)(10)
> ```
>
> Ill-formed : `App (Lam (\x -> Lam (\y -> App x y))) (Lift True)`
> Well-formed : `App (Lam (\x -> Lam (\y -> App x y))) (Lift (\z -> True))`

Because Babble is call-by-value (strict), `Fix` is typed on function types (`Lam of Fn of (Fn of (A, B), Fn of (A, B))` → `Lam of Fn of (A, B)`) and eta-expands via `recurse`, matching the strict Z-combinator form of `fact`. The `Lam` constructor is named `Abs` so it does not collide with the `Lam` interface name in the same scope.

```babble
;; babble module=hoas.ba
export def Fn of (A, B) = type fn volatile (shared A): B
export def Lam of T = interface (
    def eval: fn volatile shared (): T
)
export def Lift of A = record (v: A) implements Lam of A (
    def eval = fn shared (): A -> (this.v)
)
export def Abs of (A, B) = record (f: Fn of (Lam of A, Lam of B)) implements Lam of Fn of (A, B) (
    def eval = fn shared (): Fn of (A, B) -> (
        def f = this.f
        fn volatile (shared x: A): B -> (f(Lift(x)).eval())
    )
)
export def App of (A, B) = record (f: Lam of Fn of (A, B), x: Lam of A) implements Lam of B (
    def eval = fn volatile shared (): B -> (this.f.eval()(this.x.eval()))
)
export def eval of T = fn volatile (shared e: Lam of T): T -> (e.eval())
```

```babble
;; babble module=hoas_ext.ba
def h = import("hoas.ba")
export def Tup of (A, B) = record (a: A, b: B) ()
export def Pair of (A, B) = record (l: h.Lam of A, r: h.Lam of B) implements h.Lam of Tup of (A, B) (
    def eval = fn volatile shared (): Tup of (A, B) -> (Tup(this.l.eval(), this.r.eval()))
)
export def Fix of (A, B) = record (f: h.Lam of h.Fn of (h.Fn of (A, B), h.Fn of (A, B))) implements h.Lam of h.Fn of (A, B) (
    def eval = fn volatile shared (): h.Fn of (A, B) -> (
        def step = this.f.eval()
        fn volatile (shared x: A): B -> (step(recurse)(x))
    )
)
```

## Lift and Pair evaluation

```babble
def h = import("hoas.ba")
def x = import("hoas_ext.ba")
def res: x.Tup of (int, string) = h.eval(x.Pair(h.Lift(12), h.Lift("dock")))
assert(res == x.Tup(12, "dock"))
```

## Application of lambda abstraction

```babble
def h = import("hoas.ba")
def inc = h.Abs(fn volatile (shared y: h.Lam of int): h.Lam of int -> (h.Lift(h.eval(y) + 1)))
assert(h.eval(h.App(inc, h.Lift(41))) == 42)
```

## Wikipedia factorial of 10 via Fix

```babble
def h = import("hoas.ba")
def x = import("hoas_ext.ba")
def F = type h.Lam of h.Fn of (int, int)
def fact = x.Fix of (int, int)(h.Abs(fn volatile (shared f: F): F -> (
    h.Abs(fn volatile (shared y: h.Lam of int): h.Lam of int -> (
        def n = h.eval(y)
        h.Lift(if n == 0 then (1) else (n * h.eval(f)(n - 1)))
    ))
)))
assert(h.eval(fact)(0) == 1 and h.eval(fact)(10) == 3628800)
```

## Wikipedia well-formed higher-order application

```babble
def h = import("hoas.ba")
def F = type h.Lam of h.Fn of (int, bool)
def apply = h.Abs(fn volatile (shared x: F): F -> (
    h.Abs(fn volatile (shared y: h.Lam of int): h.Lam of bool -> (h.App(x, y)))
))
def ok = h.App(apply, h.Lift of h.Fn of (int, bool)(fn (shared z: int): bool -> (true)))
assert(h.eval(ok)(42))
```

## Wikipedia ill-formed application rejected at compile time

```babble
def h = import("hoas.ba")
def F = type h.Lam of h.Fn of (int, bool)
def apply = h.Abs(fn volatile (shared x: F): F -> (
    h.Abs(fn volatile (shared y: h.Lam of int): h.Lam of bool -> (h.App(x, y)))
))
def bad = h.App(apply, h.Lift(true))
```

```error
Type error : expected Lam(volatile (shared p0 : int) : bool), got Lift(bool)
```
