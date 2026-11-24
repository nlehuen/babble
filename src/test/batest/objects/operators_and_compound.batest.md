# Operator overloading (plus, minus, times, div, neg), compound assignments, and operator errors

References : BIP002 (operator methods)

```babble
;; babble preamble
def Vec = record (x : double, y : double) (
    def plus = fn (o : Vec) : Vec -> ( Vec(this.x + o.x, this.y + o.y) )
    def minus = fn (o : Vec) : Vec -> ( Vec(this.x - o.x, this.y - o.y) )
    def times = fn (k : double) : Vec -> ( Vec(this.x * k, this.y * k) )
    def div = fn (k : double) : Vec -> ( Vec(this.x / k, this.y / k) )
    def neg = fn () : Vec -> ( Vec(-this.x, -this.y) )
)

def Empty = class () ()
```

## Case 1

```babble
def a = Vec(2.0, 3.0)
def b = Vec(1.0, 1.5)
assert(a + b == Vec(3.0, 4.5))
assert(a - b == Vec(1.0, 1.5))
assert(a * 2.0 == Vec(4.0, 6.0))
assert(a / 2.0 == Vec(1.0, 1.5))
assert(-a == Vec(-2.0, -3.0))
```

## Case 2

```babble
def v = Vec(1.0, 2.0)
v += Vec(2.0, 1.0)
v -= Vec(0.5, 0.5)
v *= 2.0
v /= 5.0
assert(v == Vec(1.0, 1.0))
```

## Division produces double, so compound /= on an int field fails type checking

```babble
def Box = class (def x : int) ()
def calls = 0
def getBox = fn io (b : Box) : Box -> ( calls += 1 ; b )
def b = Box(10)
getBox(b).x += 5
getBox(b).x -= 3
getBox(b).x *= 2
getBox(b).x /= 4
```

```error
Type error : expected int, got double
```

## Compound assignment on a member field evaluates the receiver expression only once

```babble
def Box = class (def x : double) ()
def calls = 0
def getBox = fn io (b : Box) : Box -> ( calls += 1 ; b )
def b = Box(10.0)
getBox(b).x += 5
getBox(b).x -= 3
getBox(b).x *= 2
getBox(b).x /= 4
assert(b.x == 6.0)
assert(calls == 4)
```

## Error : No such member : plus

```babble
def e = Empty()
print(e + 1)
```

```error
No such member : plus
```

## Error : No such member : neg

```babble
def e = Empty()
print(-e)
```

```error
No such member : neg
```

## Error : Not a number : x

```babble
def dyn = fn (v : any) : any -> ( v )
def x : any = dyn(true)
x += 1
```

```error
Not a number : x
```
