# Exception handling : try, catch, finally, throw, and core exception type

References : BIP028 (D1-D6, D9), ControlAndOpEmitter.emitTry, HirTryNormalize, Interpreter

```babble
;; babble preamble
def AppError = interface (
    def code: fn (): int
)
def ParseError = record (line: int, msg: string) implements AppError (
    def code = fn (): int -> (400)
)
def NotFound = record (id: string) implements AppError (
    def code = fn (): int -> (404)
)

def parse = fn io (s: string): int -> (
    java.lang.Integer.parseInt(s)
)

def throwIO = fn (msg: string): int -> (
    throw java.io.IOException(msg)
)
```

## Case 1

```babble
def a = try (parse("42")) catch : java.lang.NumberFormatException then (-1)
def b = try (parse("bad")) catch : java.lang.NumberFormatException then (-1)
assert(a == 42)
assert(b == -1)
```

## Checked Java exception propagates directly without checked-exception ceremony

```babble
def msg = try (
    throwIO("disk full")
    "ok"
) catch e: java.io.IOException then (
    e.getMessage()
)
assert(msg == "disk full")
```

## JVM array and null exceptions caught by their real Java types

```babble
def a = (array of int).of(1, 2)
def oob = try (a.get(5)) catch : java.lang.ArrayIndexOutOfBoundsException then (-1)
assert(oob == -1)
def s: string? = null
def npe = try (s!.length()) catch e: exception then (e.getMessage())
assert(npe == "Type error : expected string, got null")
```

## Core exception type : construction, catching, is check, and first-class reference

```babble
def E = exception
def ex = E("boom")
assert(ex is exception)
assert(ex is java.lang.RuntimeException)
def caught = try (
    throw exception("bad port", java.io.IOException("root"))
) catch e: exception then (
    "" + e.getMessage() + "|" + e.getCause()!.getMessage()
)
assert(caught == "bad port|root")
```

## assert(false, ...) throws core exception

```babble
def msg = try (
    assert(false, "broken")
    "ok"
) catch e: exception then (
    e.getMessage()
)
assert(msg == "broken")
```

## Checked cast failure throws core exception

```babble
def x: any = "hello"
def caught = try (
    x as int
    false
) catch : exception then (
    true
)
assert(caught)
```

## PrintFunction rethrows IOException directly (BIP028-D3)

```babble
def w = java.io.Writer.nullWriter()
w.close()
assert(try (println("hi", to: w); false) catch : java.io.IOException then (true))
```

## Custom Babble record and interface catching

```babble
def r1 = try (throw ParseError(3, "bad")) catch e: ParseError then (e.line * 10 + e.code())
def r2 = try (throw NotFound("u1")) catch e: AppError then (e.code())
assert(r1 == 430)
assert(r2 == 404)
```

## ThrownException isolation : catch java.lang.Exception skips Babble nominal values (BIP028-D2)

```babble
def r = try (
    throw ParseError(7, "x")
) catch : java.lang.Exception then (
    1
) catch e: ParseError then (
    e.line
)
assert(r == 7)
```

## catch java.lang.RuntimeException also skips Babble values in any order

```babble
def r = try (
    throw ParseError(9, "x")
) catch : java.lang.RuntimeException then (
    1
) catch e: ParseError then (
    e.line
)
assert(r == 9)
```

## Unmatched Babble value escapes past Java Exception catch while running finally

```babble
var ran = false
def outer = try (
    try (
        throw ParseError(5, "esc")
    ) catch : java.lang.Exception then (
        0
    ) finally (
        ran = true
    )
) catch e: ParseError then (
    e.line
)
assert(ran)
assert(outer == 5)
```

## Explicit java.lang.Throwable catches ThrownException as a Throwable (not unwrapped)

```babble
def msg = try (
    throw ParseError(2, "hi")
) catch e: java.lang.Throwable then (
    e.getMessage()
)
assert(msg == "ParseError(line: 2, msg: hi)")
```

## Uncaught Babble thrown value formats cleanly at script boundary

```babble
throw ParseError(4, "nope")
```

```error
ParseError(line: 4, msg: nope)
```

## Multi-catch union with is narrowing

```babble
def f = fn (w: int): string -> (
  try (
    if w == 0 then (throw ParseError(1, "p"))
    else if w == 1 then (throw NotFound("k"))
    else (throwIO("x"))
    ""
  ) catch e: ParseError | NotFound | java.io.IOException then (
    if e is ParseError then ("P" + e.line)
    else if e is NotFound then ("N" + e.id)
    else ("I" + e.getMessage())
  )
)
assert(f(0) + f(1) + f(2) == "P1NkIx")
```

## Untyped catch catches Java Exception, core exception, and Babble values, and allows rethrow

```babble
def f = fn io (k: int): int -> (
  try (
    try (
      if k == 0 then (parse("x"))
      else if k == 1 then (throw exception("e"))
      else (throw NotFound("z"))
    ) catch e then (throw e)
  ) catch : java.lang.NumberFormatException then (10)
  catch : exception then (20)
  catch e: NotFound then (e.code())
)
assert(f(0) == 10 and f(1) == 20 and f(2) == 404)
```

## Untyped catch excludes java.lang.Error (BIP028-D4)

```babble
def r = try (
    try (
        throw java.lang.AssertionError("fatal")
    ) catch then (
        1
    )
) catch e: java.lang.Error then (
    2
)
assert(r == 2)
```

## Explicit Error catch after untyped catch is reachable and matches Error

```babble
def r = try (
    throw java.lang.AssertionError("boom")
) catch then (
    1
) catch : java.lang.Error then (
    2
)
assert(r == 2)
```

## Primitive unboxing and numeric widening across try and catch branches

```babble
def d: double = try (parse("3")) catch : java.lang.NumberFormatException then (1.5)
def d2: double = try (parse("x")) catch : java.lang.NumberFormatException then (1.5)
assert(d == 3.0 and d2 == 1.5)
```

## HirTryNormalize : try inside binary RHS, call args, and constructor args

```babble
def add = fn (a: int, b: int, c: int): int -> (a + b + c)
def x = 10 + try (parse("x")) catch then (5)
def y = add(1, try (parse("2")) catch then (0), try (parse("x")) catch then (3))
def p = ParseError(1, try (throwIO("x"); "a") catch : java.io.IOException then ("b"))
assert(x == 15 and y == 6 and p.msg == "b")
```

## HirTryNormalize : try inside field assignment, compound assignment, and safe call

```babble
def Box = class (var v: int) ()
def b = Box(10)
b.v = try (parse("bad")) catch : java.lang.NumberFormatException then (20)
b.v += try (parse("5")) catch : java.lang.NumberFormatException then (0)
def s: string? = null
def z = s?.substring(try (throwIO("never")) catch : java.io.IOException then (0))
assert(b.v == 25 and z == null)
```

## Closure capturing catch parameter promotes e to Cell

```babble
def makeReader = fn io (s: string): fn volatile (): string -> (
    try (
        parse(s)
        fn (): string -> ("ok")
    ) catch e: java.lang.NumberFormatException then (
        fn volatile (): string -> ("err:" + e.getClass().getSimpleName())
    )
)
assert(makeReader("5")() == "ok")
assert(makeReader("x")() == "err:NumberFormatException")
```

## finally runs on normal exit and discards its value

```babble
def log = list of string()
var v = try (
    log.add("try")
    99
) finally (
    log.add("finally")
)
assert(v == 99 and log == list("try", "finally"))
```

## return evaluates its expression BEFORE finally runs (BIP028-D6)

```babble
def f = fn (): int -> (
    var x = 10
    try (
        return x
    ) finally (
        x = 99
    )
)
assert(f() == 10)
```

## Nested finally blocks run innermost to outermost on return

```babble
def log = list of string()
def f = fn io (): int -> (
    try (
        try (
            log.add("body")
            return 7
        ) finally (
            log.add("inner")
        )
    ) finally (
        log.add("outer")
    )
)
assert(f() == 7 and log == list("body", "inner", "outer"))
```

## If inner finally throws during return, outer finally STILL runs (range splitting)

```babble
def log = list of string()
def f = fn io (): int -> (
    try (
        try (
            return 7
        ) finally (
            log.add("inner")
            throw exception("inner-fail")
        )
    ) finally (
        log.add("outer")
    )
)
def msg = try (f(); "none") catch e: exception then (e.getMessage())
assert(msg == "inner-fail" and log == list("inner", "outer"))
```

## break and continue unwind inner finally blocks without running outer finally

```babble
def log = list of string()
def res = try (
    var i = 0
    while i < 3 then (
        i += 1
        try (
            if i == 1 then (continue)
            if i == 2 then (break 42)
        ) finally (
            log.add("fin" + i)
        )
    )
) finally (
    log.add("outer")
)
assert(res == 42 and log == list("fin1", "fin2", "outer"))
```

## Automatic addSuppressed when finally throws while primary exception is in flight (BIP028-D6)

```babble
def info = try (
    try (
        throw java.io.IOException("primary")
    ) finally (
        throw java.lang.IllegalStateException("secondary")
    )
) catch e: java.io.IOException then (
    def sup = e.getSuppressed()
    "" + e.getMessage() + "|" + sup.size() + "|" + sup.get(0).getMessage()
)
assert(info == "primary|1|secondary")
```

## Self-suppression guard : rethrowing the exact same Throwable in finally does not crash

```babble
def err = java.io.IOException("same")
def count = try (
    try (
        throw err
    ) finally (
        throw err
    )
) catch e: java.io.IOException then (
    e.getSuppressed().size()
)
assert(count == 0)
```

## Finally throwing on normal exit propagates normally (no primary in flight)

```babble
def msg = try (
    try (
        42
    ) finally (
        throw exception("cleanup-fail")
    )
    "ok"
) catch e: exception then (
    e.getMessage()
)
assert(msg == "cleanup-fail")
```

## Nested fn or inner loop inside finally may return / break locally

```babble
def f = fn (): int -> (
    var s = 0
    try (
        1
    ) finally (
        def h = fn (): int -> (return 5)
        s += h()
        var k = 0
        while k < 10 then (
            k += 1
            if k == 3 then (break)
        )
        s += k
    )
    s
)
assert(f() == 8)
```

## BIP024 : tail call in catch without finally runs in O(1) stack

```babble
def loop = fn (n: int): int -> (
    if n <= 0 then (0)
    else (
        try (
            throw NotFound("step")
        ) catch : NotFound then (
            recurse(n - 1)
        )
    )
)
assert(loop(5000) == 0)
```

## Catch clause throwing while finally also throws attaches secondary to catch's primary

```babble
def info = try (
    try (
        throw ParseError(1, "p")
    ) catch : ParseError then (
        throw java.io.IOException("from-catch")
    ) finally (
        throw java.lang.IllegalStateException("from-fin")
    )
) catch e: java.io.IOException then (
    "" + e.getMessage() + "|" + e.getSuppressed().get(0).getMessage()
)
assert(info == "from-catch|from-fin")
```

## Error : Cannot return from a finally block

```babble
def f = fn (): int -> (
    try (1) finally (return 2)
)
```

```error
Cannot return from a finally block
```

## Error : Cannot break out of a finally block

```babble
while true then (
    try (1) finally (break)
)
```

```error
Cannot break out of a finally block
```

## Error : Cannot continue out of a finally block

```babble
while true then (
    try (1) finally (continue)
)
```

```error
Cannot continue out of a finally block
```

## Error : Cannot assign to catch parameter : e

```babble
try (parse("x")) catch e: java.lang.NumberFormatException then (
    e = java.lang.NumberFormatException("y")
)
```

```error
Cannot assign to catch parameter : e
```

## Error : Type error : cannot throw int

```babble
throw 42
```

```error
Type error : cannot throw int
```

## Error : Type error : cannot throw string

```babble
throw "boom"
```

```error
Type error : cannot throw string
```

## Error : Type error : cannot throw ParseError?

```babble
def x: ParseError? = null
throw x
```

```error
Type error : cannot throw ParseError?
```

## Error : Type error : cannot throw any

```babble
def x: any = 42
throw x
```

```error
Type error : cannot throw any
```

## Error : Type error : java.lang.StringBuilder is not a Throwable

```babble
try (parse("x")) catch e: java.lang.StringBuilder then (0)
```

```error
Type error : java.lang.StringBuilder is not a Throwable
```

## Error : Cannot catch non-exception type : int

```babble
try (parse("x")) catch e: int then (0)
```

```error
Cannot catch non-exception type : int
```

## Error : Cannot catch nullable type : java.lang.Exception?

```babble
try (parse("x")) catch e: java.lang.Exception? then (0)
```

```error
Cannot catch nullable type : java.lang.Exception?
```

## Error : Cannot catch 'frozen' type : frozen ParseError

```babble
try (parse("x")) catch e: frozen ParseError then (0)
```

```error
Cannot catch 'frozen' type : frozen ParseError
```

## Error : Cannot catch erased type arguments : use Box

```babble
def Box of T = record (v: T) ()
try (parse("x")) catch e: Box of int then (0)
```

```error
Cannot catch erased type arguments : use Box
```

## Error : Cannot catch type parameter : T

```babble
def f of T = fn io (x: T): int -> (
    try (parse("x")) catch e: T then (0)
)
```

```error
Cannot catch type parameter : T
```

## Error : Redundant catch type : java.io.IOException is already a subtype of ja...

```babble
try (parse("x")) catch e: java.lang.Exception | java.io.IOException then (0)
```

```error
Redundant catch type : java.io.IOException is already a subtype of java.lang.Exception
```

## Error : Unreachable catch clause : java.lang.NumberFormatException is already...

```babble
try (parse("x")) catch : java.lang.Exception then (1) catch : java.lang.NumberFormatException then (2)
```

```error
Unreachable catch clause : java.lang.NumberFormatException is already caught by java.lang.Exception
```

## Error : Unreachable catch clause : ParseError is already caught by AppError

```babble
try (parse("x")) catch : AppError then (1) catch : ParseError then (2)
```

```error
Unreachable catch clause : ParseError is already caught by AppError
```

## Error : Unreachable catch clause : ParseError is already caught by earlier catch

```babble
try (parse("x")) catch then (1) catch : ParseError then (2)
```

```error
Unreachable catch clause : ParseError is already caught by earlier catch
```

## Error : Unreachable catch clause : catch-all is already caught by java.lang.T...

```babble
try (parse("x")) catch : java.lang.Throwable then (1) catch then (2)
```

```error
Unreachable catch clause : catch-all is already caught by java.lang.Throwable
```

## Error : Type error : expected int, got string

```babble
def x: int = try (parse("1")) catch then ("bad")
```

```error
Type error : expected int, got string
```
