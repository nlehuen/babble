# Booleans, short-circuit and/or, not, truthiness, same(), and assert/suppose

References : BIP011 (core scalar types), BIP008 (strict bool in assert), Values.same

## Case 1

```babble
def calls = 0
def bump = fn io (r : bool) : bool -> ( calls += 1 ; r )
assert(not (false and bump(true)))
assert(calls == 0)
assert(true or bump(true))
assert(calls == 0)
assert(true and bump(true))
assert(calls == 1)
assert(not (false or bump(false)))
assert(calls == 2)
```

## Only null, false, 0, 0.0 are falsy; "" is truthy (not binds below ==)

```babble
assert((not true) == false)
assert(not false)
assert(not null)
assert(not 0)
assert((not 1) == false)
assert((not "") == false)
assert((not "a") == false)
assert(not 0.0)
assert((not 0.5) == false)
```

## Case 3

```babble
assert(true == true)
assert(not (true == 1))
assert(not (false == null))
assert(null == null)
assert(null != 1)
def a = "ab"
def b = "a" + "b"
assert(a == b)
assert(same(null, null))
;; same() uses value equality for numbers, booleans, and strings
assert(same(1, 1.0))
```

## Truthiness with any? values in if: only null, false, 0, 0.0 are falsy; "" is truthy

```babble
def dyn = fn (v : any?) : any? -> ( v )
def truthy = fn (v : any?) : bool -> ( if v then ( true ) else ( false ) )
assert(not truthy(dyn(null)))
assert(not truthy(dyn(false)))
assert(truthy(dyn(true)))
assert(not truthy(dyn(0)))
assert(truthy(dyn(2)))
assert(not truthy(dyn(0.0)))
assert(truthy(dyn(0.1)))
assert(truthy(dyn("")))
assert(truthy(dyn("hi")))
```

## Error : Assertion failed : 1==2

```babble
assert(1 == 2)
```

```error
Assertion failed : 1==2
```

## Error : custom suppose

```babble
suppose(1 == 2, "custom suppose")
```

```error
custom suppose
```

## Error : Type error : expected bool, got int

```babble
assert(1)
```

```error
Type error : expected bool, got int
```

## Error : Type error : expected bool, got any

```babble
def dyn = fn (v : any) : any -> ( v )
assert(dyn(1))
```

```error
Type error : expected bool, got any
```
