# Java package lookups, class construction, static fields/methods, inner classes, and missing members

References : BIP001, BIP008, JavaPackage, JavaClass

## Case 1

```babble
def sb = java.lang.StringBuilder("hello")
sb.append(" world")
assert(sb.toString() == "hello world")
assert(java.lang.Math.max(3, 7) == 7)
assert(java.lang.Integer.MAX_VALUE == 2147483647)
```

## Case 2

```babble
def Math = java.lang.Math
def pair = java.util.Map.entry("k", 42)
def asEntry: java.util.Map.Entry of (string, int) = pair
assert(Math.min(10, 4) == 4)
assert(asEntry.getKey() == "k")
assert(asEntry.getValue() == 42)
```

## Error : No such name in java.lang.Math : noSuchStatic

```babble
def x = java.lang.Math.noSuchStatic
```

```error
No such name in java.lang.Math : noSuchStatic
```

## Error : No applicable overload of java.util.List for () : candidates are

```babble
def xs = java.util.List()
```

```error
No applicable overload of java.util.List for () : candidates are
```

## Unknown class name resolves as a JavaPackage, which is not callable

```babble
def x = java.lang.NoSuchClassHere()
```

```error
java.lang.NoSuchClassHere is not callable
```
