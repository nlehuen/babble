# Postfix non-null assertion (e!) : narrowing T? to T, runtime null checks, and effect checking

References : BIP011 (§6.1 non-null assertion), BIP008 (nullables), BIP016 (nothrow)

## Case 1

```babble
def s : string? = "hello"
def n : int? = 41
assert(s! + "!" == "hello!")
assert(n! + 1 == 42)
```

## Error : Type error : expected string, got null

```babble
def s : string? = null
s!
```

```error
Type error : expected string, got null
```

## Compile-time error : null literal cannot be asserted non-null

```babble
def x = null!
```

```error
Type error : expected any, got null
```

## Non-null assertion can throw at runtime and therefore violates nothrow

```babble
def f = fn nothrow (s : string?) : string -> (
    s!
)
assert(f("ok") == "ok")
```

```error
Function f declared nothrow violates nothrow guarantee
```

## Redundant non-null assertion on a non-nullable value warns and evaluates to the value

```babble
def n : int = 42
assert(n! == 42)
```

```stderr
Warning : Redundant non-null assertion : int
```
