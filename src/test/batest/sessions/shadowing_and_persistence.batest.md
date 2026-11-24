# Sequential BabbleSession inputs (----------): state persistence, shadowing, functions, and errors across in...

References : BIP004 (sessions & REPL)

## Case 1

```babble session
def x = 10
def acc = 0
```

```babble session
def inc = fn io (k : int) : int -> ( acc += k ; acc )
assert(x == 10)
assert(inc(5) == 5)
```

```babble session
def x = "shadowed"
assert(x == "shadowed")
assert(inc(3) == 8)
```

```babble session
acc = 100
assert(inc(1) == 101)
```

## Cross-input assignment cannot widen type already committed in previous input

```babble session
def v = 1
```

```babble session
v = 2.5
```

```error
Type error : expected int, got double
```

## Shadowing with def in subsequent input changes type cleanly for later code

```babble session
def v = 1
def readOld = fn () : int -> ( v + 10 )
```

```babble session
def v = 2.5
assert(v == 2.5)
assert(string(classof(v)) == "java.lang.Double")
assert(readOld() == 11)
```

## Unknown name referenced in later session input fails statically

```babble session
def x = 1
```

```babble session
print(missingName)
```

```error
No such name : missingName
```

## Runtime error in a later session input aborts the session with that input's diagnostic

```babble session
def keep = 42
```

```babble session
assert(keep == 42)
assert(keep == 0, "later input failed")
```

```error
later input failed
```
