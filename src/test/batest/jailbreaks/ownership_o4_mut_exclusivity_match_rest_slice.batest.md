# Jailbreak : `ownership` (`O4`) `mut` exclusivity bypassed via `match` `...def tail` rest slice on `list` or `array`

- **Surface:** `ownership`
- **Vector:** `O4` (`mut` exclusivity / pattern-match rest-slice element aliasing)
- **Severity:** `S2` (Exclusive `mut` borrow invariant violated: `same(x, y)` is `true` for two `mut` parameters)
- **Observed:** Destructuring a `list` or `array` with a rest pattern (`case is list(_, ...def tail)` or `case is array(_, ...def tail)`) creates a fresh outer container whose elements still alias the original container's elements. Passing `tail.get(0)` and `xs.get(1)` to `fn (mut x: Box, mut y: Box)` compiles cleanly and evaluates `same(x, y)` to `true` across all 6 modes (`interpret`, `-O0`, `-O1`, `-O2`, `wasm-O1`, `wasm-O2`).

## List match rest slice element must not alias original list element across two mut parameters

```babble
def Box = class (var x: int) ()
def check = fn (mut x: Box, mut y: Box): bool -> (same(x, y))
def xs = list of Box (Box(0), Box(1))
match xs
case is list(_, ...def tail) then (check(tail.get(0), xs.get(1)))
else (false)
```

```error
Cannot pass aliased mutable arguments
```

## Array match rest slice element must not alias original array element across two mut parameters

```babble
def Box = class (var x: int) ()
def check = fn (mut x: Box, mut y: Box): bool -> (same(x, y))
def arr = (array of Box).of(Box(0), Box(1))
match arr
case is array(_, ...def tail) then (check(tail.get(0), arr.get(1)))
else (false)
```

```error
Cannot pass aliased mutable arguments
```
