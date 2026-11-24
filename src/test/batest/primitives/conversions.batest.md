# Built-in conversions : int(), double(), classof(), and boundary/arity errors

References : BIP011 (core scalar types), BIP008 (strict typing & any), Values

```babble
;; babble preamble
def dyn = fn (v : any) : any -> ( v )
def dynOpt = fn (v : any?) : any? -> ( v )
def coerce of T = fn io (shared x: any?): T -> (
    def l: java.util.List of T = java.util.ArrayList()
    def m = java.lang.Class.forName("java.util.List").getMethod("add", java.lang.Class.forName("java.lang.Object"))!
    m.invoke(l, x)
    l.get(0)
)
def call1 = fn io (shared f: any, v: any): any? -> ( coerce of (fn (x: any): any?)(f)(v) )
```

## Case 1

```babble
assert(int(3) == 3 and int(2.9) == 2 and int(-2.9) == -2)
assert(int("42") == 42 and int("-7") == -7)
assert(double(3) == 3.0 and double(1.5) == 1.5 and double("3.25") == 3.25)
```

## Case 2

```babble
assert("" + classof(1) == "java.lang.Integer")
assert("" + classof(1.0) == "java.lang.Double")
assert("" + classof(true) == "java.lang.Boolean")
assert("" + classof("x") == "java.lang.String")
assert(classof(null) == null)
```

## Error : Not an int : "nope"

```babble
print(int("nope"))
```

```error
Not an int : "nope"
```

## Error : Not a double : "nope"

```babble
print(double("nope"))
```

```error
Not a double : "nope"
```

## Error : Out of the int range : 1.0E20

```babble
print(int(1.0E20))
```

```error
Out of the int range : 1.0E20
```

## Error : Out of the int range : 0.0/0.0 = NaN

```babble
print(int(0.0 / 0.0))
```

```error
Out of the int range : 0.0/0.0 = NaN
```

## Error : Not a number : dyn(true)

```babble
print(int(dyn(true)))
```

```error
Not a number : dyn(true)
```

## Error : Not a number : bad

```babble
def bad : any = true
print(int(bad))
```

```error
Not a number : bad
```

## Error : Not a double : "xyz"

```babble
def bad : any = "xyz"
print(double(bad))
```

```error
Not a double : "xyz"
```

## Error : Type error : expected any, got any?

```babble
print(double(dynOpt(null)))
```

```error
Type error : expected any, got any?
```

## Error : f is not callable

```babble
def f = dyn(int)
print(f())
```

```error
f is not callable
```

## Error : Missing parameter : value

```babble
print(int())
```

```error
Missing parameter : value
```

## Error : f is not callable (2)

```babble
def f = dyn(classof)
print(f())
```

```error
f is not callable
```

## Error : Missing parameter : v

```babble
print(classof())
```

```error
Missing parameter : v
```

## Error : classof : a class has no class

```babble
def C = class () ()
print(classof(C))
```

```error
classof : a class has no class
```

## Error : classof : an interface has no class

```babble
def I = interface ()
print(classof(I))
```

```error
classof : an interface has no class
```

## Error : classof : a function has no class

```babble
def f = fn () -> ( 1 )
print(classof(f))
```

```error
classof : a function has no class
```

## Error : Out of the byte range : 200

```babble
byte(200)
```

```error
Out of the byte range : 200
```

## Error : Out of the byte range : 0.0/0.0 = NaN

```babble
byte(0.0 / 0.0)
```

```error
Out of the byte range : 0.0/0.0 = NaN
```

## Error : Out of the short range : 40000

```babble
short(40000)
```

```error
Out of the short range : 40000
```

## Error : Out of the short range : 32768.0

```babble
short(32768.0)
```

```error
Out of the short range : 32768.0
```

## Error : Out of the int range : 3000000000L = 3000000000

```babble
int(3000000000L)
```

```error
Out of the int range : 3000000000L = 3000000000
```

## Dynamic int conversion rejects zero arguments

```babble
coerce of (fn (): any?)(int)()
```

```error
int() takes 1 argument, 0 given
```

## Byte conversion rejects negative int below range

```babble
call1(byte, -200)
```

```error
Out of the byte range : v = -200
```

## Byte conversion rejects negative double below range

```babble
call1(byte, -200.0)
```

```error
Out of the byte range : v = -200.0
```

## Byte conversion rejects boolean

```babble
call1(byte, true)
```

```error
Not a number : v
```

## Short conversion rejects high char above range

```babble
call1(short, char(40000))
```

```error
Out of the short range : v = 鱀
```

## Short conversion rejects negative int below range

```babble
call1(short, -40000)
```

```error
Out of the short range : v = -40000
```

## Short conversion rejects NaN double

```babble
call1(short, 0.0 / 0.0)
```

```error
Out of the short range : v = NaN
```

## Short conversion rejects negative double below range

```babble
call1(short, -40000.0)
```

```error
Out of the short range : v = -40000.0
```

## Short conversion rejects boolean

```babble
call1(short, true)
```

```error
Not a number : v
```

## Int conversion rejects negative long below range

```babble
call1(int, -3000000000L)
```

```error
Out of the int range : v = -3000000000
```

## Long conversion rejects NaN double

```babble
call1(long, 0.0 / 0.0)
```

```error
Out of the long range : v = NaN
```

## Long conversion rejects large positive finite double

```babble
call1(long, 1.0E20)
```

```error
Out of the long range : v = 1.0E20
```

## Long conversion rejects large negative finite double

```babble
call1(long, -1.0E20)
```

```error
Out of the long range : v = -1.0E20
```

## Long conversion rejects boolean

```babble
call1(long, true)
```

```error
Not a number : v
```

## Float conversion rejects boolean

```babble
call1(float, true)
```

```error
Not a number : v
```

## Bool conversion rejects int

```babble
call1(bool, 42)
```

```error
Not a boolean : v
```

## Char conversion rejects int above range

```babble
call1(char, 70000)
```

```error
Out of the char range : v = 70000
```

## Char conversion rejects boolean

```babble
call1(char, true)
```

```error
Not a char : v
```
