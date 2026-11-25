# String concatenation, comparisons, Java String methods, and errors

References : BIP011 (transparent string type)

## Case 1

```babble
assert("a" + "b" == "ab")
assert("a" + 1 == "a1")
assert("a" + null == "anull")
assert("a" + true == "atrue")
var s = "hi"
s += 42
s += "!"
assert(s == "hi42!")
```

## Error : Not a number : "a"

```babble
print(1 + "a")
```

```error
Not a number : "a"
```

## Error : Not a number : null

```babble
print(null + "a")
```

```error
Not a number : null
```

## Case 4

```babble
assert("abc" == "abc")
assert("abc" < "abd")
assert("b" > "a")
assert("a" <= "a")
assert(not ("b" >= "c"))
```

## Case 5

```babble
assert("hello".length() == 5)
```

## Error : Not a number : "a" (2)

```babble
print(1 - "a")
```

```error
Not a number : "a"
```

## Error : Not a number : "a" (3)

```babble
print("a" * 2)
```

```error
Not a number : "a"
```

## Error : Not comparable : s and 1

```babble
def s = "abc"
print(s < 1)
```

```error
Not comparable : s and 1
```
