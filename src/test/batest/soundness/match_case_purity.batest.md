# Soundness regressions : `BIP038` pure `case` pattern and guard expressions

Promoted from `BIP032` quarantine (`opt_r5_o2_match_guard_var_mutation.batest.md` and `types_t3_match_guard_narrowing_null.batest.md`) and sister bypass vectors enforcing that all `match` `case` pattern expressions and `if` guards are pure (`MatchEffectAnalyzer`, `EffectBodyWalker`, `CallEffectAnalyzer`).

```babble
;; babble preamble
def Box = class () (
    var v: int = 1
    def setV = fn mut (n: int) -> (this.v = n)
)
def Point = record (x: int, y: int) ()
def takeList = fn (unique xs: list of int): int -> (xs.size())
def useStr = fn (s: string): int -> (s.length())
```

## Promoted opt R5 : var reassigned in match if-guard is rejected

```babble
var x = 1
match x
case 1 if (x = 2; false) then (println("arm1"))
else (println(x))
```

```error
Case expression must be pure : cannot assign to variable x
```

## Promoted types T3 : narrowing invalidation via var mutation in match if-guard is rejected

```babble
var x: string? = "hi"
if x != null then (
    match x
    case is string if (x = null; false) then (println("arm1"))
    else (
        def nonNull: string = x
        println(useStr(nonNull))
    )
)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Compound assignment to outer var in match guard is rejected

```babble
def f = fn (): int -> (
    var x = 1
    match x
    case 1 if (x += 1; false) then (10)
    else (x)
)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside value pattern expression is rejected

```babble
var x = 1
match x
case (x = 2; 99) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside relational, range, stepped range, and membership patterns is rejected

```babble
var x = 1
match x
case < (x = 2; 0) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside range pattern bound is rejected

```babble
var x = 1
match x
case (x = 2; 0)..10 then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside stepped range pattern step is rejected

```babble
var x = 1
match x
case (0..10).step((x = 2; 1)) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside membership pattern container is rejected

```babble
var x = 1
match x
case in (x = 2; list(9)) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside OR and destructuring patterns is rejected

```babble
var x = 1
match Point(1, 2)
case is Point((x = 2; 9), _) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside sequence destructuring pattern is rejected

```babble
var x = 1
match list(1, 2)
case is list((x = 2; 9), ...) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Var assignment inside try-finally and nested match in guard is rejected

```babble
var x = 1
match x
case 1 if (try (false) finally (x = 2)) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Nested match arm inside outer guard cannot mutate outer var

```babble
var x = 1
match x
case 1 if (match 2 case 2 then (x = 2; false) else (false)) then (1)
else (x)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Frame-local closure mutating outer var cannot be called in guard

```babble
def f = fn (): int -> (
    var x = 1
    def bump = fn (): bool -> (x = 2; false)
    match x
    case 1 if bump() then (10)
    else (x)
)
```

```error
Case expression must be pure : cannot mutate outer variable via closure
```

## Frame-local mutating closure cannot be passed to HOF in guard

```babble
def f = fn (): int -> (
    var x = 1
    def apply = fn (g: fn (): bool): bool -> (g())
    match x
    case 1 if apply(fn (): bool -> (x = 2; false)) then (10)
    else (x)
)
```

```error
Case expression must be pure : cannot mutate outer variable via closure
```

## Calling-clause callback parameter cannot be invoked in guard

```babble
def sneaky = fn calling f (f: fn (): bool): int -> (
    match 1
    case 1 if f() then (1)
    else (0)
)
```

```error
Case expression must be pure : cannot invoke calling parameter f
```

## Calling-clause callback parameter cannot be forwarded in guard

```babble
def run = fn calling g (g: fn (): bool): bool -> (g())
def sneaky = fn calling f (f: fn (): bool): int -> (
    match 1
    case 1 if run(f) then (1)
    else (0)
)
```

```error
Case expression must be pure : cannot invoke calling parameter f
```

## Mutating enclosing unique list in guard is rejected

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    match 1
    case 1 if (xs.add(3); false) then (0)
    else (xs.size())
)
```

```error
Case expression must be pure : cannot perform mut Java method xs.add
```

## Mutating enclosing array in guard is rejected

```babble
def f = fn (): int -> (
    def arr = array of int.of(1, 2)
    match arr.get(0)
    case 1 if (arr.set(0, 99); false) then (0)
    else (arr.get(0))
)
```

```error
Case expression must be pure : cannot perform mut method set
```

## Mutating enclosing object field or calling mut method in guard is rejected

```babble
def f = fn (): int -> (
    def b = Box()
    match b.v
    case 1 if (b.v = 2; false) then (0)
    else (b.v)
)
```

```error
Case expression must be pure : cannot perform field assignment .v
```

## Calling mut method on enclosing object in guard is rejected

```babble
def f = fn (): int -> (
    def b = Box()
    match b.v
    case 1 if (b.setV(2); false) then (0)
    else (b.v)
)
```

```error
Case expression must be pure : cannot perform mut method b.setV
```

## Mutating this field inside init match guard is rejected

```babble
def C = class () (
    var v: int = 1
    init () -> (
        match this.v
        case 1 if (this.v = 2; false) then (null)
        else (null)
    )
)
```

```error
Case expression must be pure : cannot perform field assignment .v
```

## IO call in guard is rejected even inside fn io

```babble
def f = fn io (x: int): int -> (
    match x
    case 1 if (println("hi"); false) then (1)
    else (2)
)
```

```error
Case expression must be pure : performs I/O or external mutation
```

## Var mutation in guard is rejected even inside fn axiom pure

```babble
def f = fn axiom pure (): int -> (
    var x = 1
    match x
    case 1 if (x = 2; false) then (1)
    else (x)
)
```

```error
Case expression must be pure : cannot assign to variable x
```

## Moving enclosing unique local inside guard is rejected

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    match 1
    case 1 if takeList(move(xs)) > 5 then (0)
    else (xs.size())
)
```

```error
Case expression must be pure : cannot move variable xs
```

## Freezing enclosing unique local inside guard is rejected

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    match 1
    case 1 if freeze(xs).size() > 5 then (0)
    else (xs.size())
)
```

```error
Case expression must be pure : cannot freeze variable xs
```

## Implicitly consuming enclosing unique local via unique param in guard is rejected

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    match 1
    case 1 if takeList(xs) > 5 then (0)
    else (xs.size())
)
```

```error
Case expression must be pure : cannot consume variable xs
```

## Moving match move(x) pattern binding inside guard is rejected

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    match move(xs)
    case def ys if takeList(move(ys)) > 5 then (0)
    else def zs then (zs.size())
)
```

```error
Case expression must be pure : cannot move variable ys
```

## Non-local return inside guard is rejected

```babble
def f = fn (x: int): int -> (
    match x
    case 1 if (return 42; false) then (1)
    else (2)
)
```

```error
Case expression must be pure : cannot return from case expression
```

## Non-local break targeting outer loop inside guard is rejected

```babble
def f = fn (): int -> (
    var r = 0
    while true then (
        match 1
        case 1 if (break; false) then (r = 1)
        else (r = 2; break)
    )
    r
)
```

```error
Case expression must be pure : cannot break out of case expression
```

## Non-local continue targeting outer loop inside guard is rejected

```babble
def f = fn (): int -> (
    var i = 0
    while i < 2 then (
        i += 1
        match i
        case 1 if (continue; false) then (null)
        else (null)
    )
    i
)
```

```error
Case expression must be pure : cannot continue out of case expression
```

## Mutating operator method plus in pattern expression is rejected

```babble
def Evil = class () (
    var n: int = 1
    def plus = fn mut (other: Evil): int -> (this.n = 99; 0)
)
def f = fn (): int -> (
    def e = Evil()
    match 1
    case e + e then (0)
    else (e.n)
)
```

```error
Case expression must be pure : cannot perform operator method plus
```

## IO operator method plus in pattern expression is rejected

```babble
def Evil = class () (
    def plus = fn io (other: Evil): int -> (println("EVIL"); 0)
)
def f = fn io (): int -> (
    def e = Evil()
    match 1
    case e + e then (0)
    else (1)
)
```

```error
Case expression must be pure : performs I/O or external mutation
```

## Mutating unary operator method neg in pattern expression is rejected

```babble
def Evil = class () (
    var n: int = 1
    def neg = fn mut (): int -> (this.n = 99; 0)
)
def f = fn (): int -> (
    def e = Evil()
    match 1
    case -e then (0)
    else (e.n)
)
```

```error
Case expression must be pure : cannot perform operator method neg
```

## Confined local var inside guard is allowed

```babble
def sumGt = fn (n: int): int -> (
    match n
    case def k if (var s = 0; for i in 0..<k then (s += i); s > 5) then (1)
    else (0)
)
assert(sumGt(4) == 1 and sumGt(3) == 0)
```

## Confined inner loop with break inside guard is allowed

```babble
def has7 = fn (xs: list of int): int -> (
    match xs
    case def ys if (var ok = false; for v in ys then (if v == 7 then (ok = true; break)); ok) then (1)
    else (0)
)
assert(has7(list(1, 7, 3)) == 1 and has7(list(1, 2)) == 0)
```

## Confined fresh container mutation inside guard is allowed at top-level and inside fn

```babble
def check = fn (n: int): int -> (
    match n
    case def k if (def t = list(1, 2); t.add(k); t.size() == 3 and t.get(2) > 5) then (1)
    else (0)
)
def top = match 9
case def k if (def t = list(1); t.add(k); t.get(1) == 9) then (1)
else (0)
assert(check(9) == 1 and check(2) == 0 and top == 1)
```

## Guard-local alias of outer unique list preserves uniqueness for later consume

```babble
def f = fn (): int -> (
    def xs = list(1, 2)
    def n = match 1
        case 1 if (def a = xs; a.size() == 2) then (10)
        else (20)
    n + takeList(move(xs))
)
assert(f() == 12)
```

## Mutating a mut parameter or mut this inside a guard is rejected

```babble
def f = fn (mut b: Box): int -> (
    match b.v
    case 1 if (b.v = 2; false) then (0)
    else (b.v)
)
```

```error
Case expression must be pure : cannot perform field assignment .v
```

## Mutating this inside fn mut guard is rejected

```babble
def C = class () (
    var v: int = 1
    def m = fn mut (): int -> (
        match this.v
        case 1 if (this.v = 2; false) then (0)
        else (this.v)
    )
)
```

```error
Case expression must be pure : cannot perform field assignment .v
```

## Deep mutation of guard-local list holding outer object is rejected

```babble
def mutFirst = fn (mut xs: list of Box) -> (xs.get(0).v = 9)
def f = fn io (): int -> (
    def b = Box()
    match b.v
    case 1 if (def t = list(b); mutFirst(t); false) then (0)
    else (b.v)
)
```

```error
Case expression must be pure : cannot perform mut parameter xs
```

## Unattached fresh container and confined object init inside guard are allowed

```babble
def f = fn (n: int): int -> (
    match n
    case <> 0 if (list(1, 2).add(n); object (var a: int = 1; init (this.a = n)).a > 5) then (1)
    else (0)
)
assert(f(9) == 1 and f(2) == 0 and f(0) == 0)
```
