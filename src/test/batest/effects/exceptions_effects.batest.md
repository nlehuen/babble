# Effect, ownership, provenance, and warning rules for try/catch/finally/throw

References : BIP028 §6 (D6-D8), EffectBodyWalker.evalTry/evalThrow, curated.ba

```babble preamble
def Box = class (def value: int) ()
def Leak = record (box: Box) ()
def Err = record (msg: string) ()

def parse = fn (s: string): int -> (
    if s == "12" then (12)
    else if s == "5" then (5)
    else (throw java.lang.NumberFormatException(s))
)
```

## Exhaustive untyped catch restores nothrow when catches and finally are nothrow (BIP028-D7)

```babble
def safeParse = fn nothrow (s: string): int -> (
    try (
        parse(s)
    ) catch then (
        0
    )
)
assert(safeParse("12") == 12 and safeParse("x") == 0)
```

## Explicit java.lang.Throwable catch also restores nothrow

```babble
def safeParse = fn nothrow (s: string): int -> (
    try (
        parse(s)
    ) catch : java.lang.Throwable then (
        -1
    )
)
assert(safeParse("5") == 5 and safeParse("x") == -1)
```

## Non-exhaustive catch does NOT restore nothrow

```babble
def bad = fn nothrow (s: string): int -> (
    try (
        parse(s)
    ) catch : java.lang.NumberFormatException then (
        0
    )
)
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Throwing catch prevents nothrow restoration

```babble
def bad = fn nothrow (s: string): int -> (
    try (
        parse(s)
    ) catch then (
        throw Err("rethrow")
    )
)
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Throwing finally prevents nothrow restoration

```babble
def bad = fn nothrow (s: string): int -> (
    try (
        parse(s)
    ) catch then (
        0
    ) finally (
        throw Err("fin")
    )
)
```

```error
Function bad declared nothrow violates nothrow guarantee
```

## Non-fatal warning when try block is statically nothrow with catch clauses (BIP028-D7)

```babble
def v = try (
    1 + 2
) catch then (
    0
)
assert(v == 3)
```

```stderr
Warning : try block cannot throw; catch clause is unreachable
```

## No dead-catch warning inside fn calling f HOF (callback may throw at some call sites)

```babble
def wrap = fn calling f (f: fn (): int): int -> (
    try (f()) catch then (-1)
)
assert(wrap(fn nothrow (): int -> (7)) == 7)
assert(wrap(fn (): int -> (parse("x"))) == -1)
```

## try (nothrowExpr) finally (...) without catch emits NO warning

```babble
def f = fn (): int -> (
    def n = 0
    def v = try (1 + 2) finally (n += 1)
    v + n
)
assert(f() == 4)
```

## Pure deterministic try/catch stays pure (no io/volatile)

```babble
def pureTry = fn (s: string): int -> (
    try (parse(s)) catch : java.lang.NumberFormatException then (0)
)
assert(pureTry("12") == 12 and pureTry("x") == 0)
```

## Throwable.getStackTrace() is volatile in curated.ba (BIP028-D8)

```babble
def bad = fn (s: string): int -> (
    try (parse(s)) catch e: java.lang.NumberFormatException then (
        e.getStackTrace().size()
    )
)
```

```error
Function bad violates deterministic guarantee : mark function as volatile (or io)
```

## finally statements are checked in discarded position (unused pure expression)

```babble
def bad = fn (s: string): int -> (
    try (parse(s)) finally (1 + 2)
)
```

```error
Unused pure expression
```

## Escaping throw of @BORROWED parameter is rejected unless marked shared (BIP028-D8)

```babble
def bad = fn (b: Box): int -> (
    throw Leak(b)
)
```

```error
Borrowed parameter b escapes via throw : mark parameter as shared b
```

## Escaping throw succeeds when parameter is marked shared

```babble
def ok = fn (shared b: Box): int -> (
    throw Leak(b)
)
def v = try (ok(Box(7))) catch e: Leak then (e.box.value)
assert(v == 7)
```

## Intra-procedural local-catch provenance allows throwing @BORROWED when guaranteed caught locally (BIP028-D8)

```babble
def localOk = fn (b: Box): int -> (
    try (
        throw Leak(b)
    ) catch e: Leak then (
        e.box.value
    )
)
assert(localOk(Box(42)) == 42)
```

## Local-catch provenance still rejects escaping the caught variable out of catch

```babble
def leaked: Leak? = null
def bad = fn io (b: Box): int -> (
    try (
        throw Leak(b)
    ) catch e: Leak then (
        leaked = e
        e.box.value
    )
)
```

```error
Borrowed parameter b escapes via outer variable leaked : mark parameter as shared b
```

## Local-catch provenance rejects rethrowing caught variable outside enclosing handler

```babble
def bad = fn (b: Box): int -> (
    try (
        throw Leak(b)
    ) catch e: Leak then (
        throw e
    )
)
```

```error
Borrowed parameter b escapes via throw : mark parameter as shared b
```

## Conservative @MOVED join : moving a unique local in try makes it @MOVED in catch

```babble
def sink = fn (unique b: Box): int -> (b.value)
def bad = fn (s: string): int -> (
    def b = Box(1)
    try (
        def v = sink(move(b))
        v + parse(s)
    ) catch then (
        b.value
    )
)
```

```error
Use of moved variable b
```

## Conservative @MOVED join : even if try reassigns after move, catch may observe the moved state

```babble
def sink = fn (unique b: Box): int -> (b.value)
def bad = fn (s: string): int -> (
    def b = Box(1)
    try (
        def v = sink(move(b))
        def n = parse(s)
        b = Box(n)
        v + n
    ) catch then (
        b.value
    )
)
```

```error
Use of moved variable b
```

## while true with throw does NOT require forever (BIP024-D8)

```babble
def run = fn (n: int): int -> (
    def i = 0
    while true then (
        i += 1
        if i == n then (throw Err("done"))
    )
    0
)
def ok = try (run(3); false) catch e: Err then (e.msg == "done")
assert(ok)
```

## while true whose body catches everything exhaustively DOES require forever

```babble
def bad = fn (): int -> (
    while true then (
        try (throw Err("x")) catch then (0)
    )
    0
)
```

```error
Loop without an exit : while true. If this is intended, wrap the expression with forever
```
