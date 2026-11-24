# Interfaces, multiple inheritance, default methods, super / super(Interface), and interface errors

References : BIP002 (interfaces, default methods, super dispatch)

## Case 1

```babble
def Shape = interface (
    def area : fn () : double
    def describe = fn () : string -> ( "area=" + this.area() )
)
def Rect = class (def w : double, def h : double) implements Shape (
    def area = fn () : double -> ( this.w * this.h )
)
def s : Shape = Rect(3.0, 4.0)
assert(s.area() == 12.0)
assert(s.describe() == "area=12.0")
assert(s is Shape)
assert(s is Rect)
```

## Case 2

```babble
def Named = interface (
    def tag = fn () : string -> ( "Named" )
)
def Tagged = interface extends Named (
    def tag = fn () : string -> ( super.tag() + "+Tagged" )
)
def Both = class () implements Tagged (
    def tag = fn () : string -> ( super.tag() + "+Both" )
)
assert(Both().tag() == "Named+Tagged+Both")
```

## Case 3

```babble
def Left = interface (
    def side = fn () : string -> ( "L" )
)
def Right = interface (
    def side = fn () : string -> ( "R" )
)
def Both = class () implements Left, Right (
    def side = fn () : string -> ( super(Left).side() + ":" + super(Right).side() )
)
assert(Both().side() == "L:R")
```

## Case 4

```babble
def Greeter = interface (
    def hello = fn () : string -> ( "hi" )
)
def loud = object implements Greeter (
    def hello = fn () : string -> ( super.hello() + "!" )
)
assert(loud.hello() == "hi!")
```

## Case 5

```babble
def Printable = interface (
    def show : fn () : string
) static (
    def prefix = "[P]"
)
def Item = class (def s : string) implements Printable (
    def show = fn () : string -> ( Printable.prefix + this.s )
)
assert(Item("ok").show() == "[P]ok")
```

## Error : Bad does not implement I.run

```babble
def I = interface ( def run : fn () : int )
def Bad = class () implements I ()
```

```error
Bad does not implement I.run
```

## Error : Ambiguous super.m : A, B. Write super(A).m()

```babble
def A = interface ( def m = fn () : int -> ( 1 ) )
def B = interface ( def m = fn () : int -> ( 2 ) )
def C = class () implements A, B (
    def m = fn () : int -> ( super.m() )
)
```

```error
Ambiguous super.m : A, B. Write super(A).m()
```

## Error : I is not callable

```babble
def I = interface ( def m = fn () : int -> ( 1 ) )
print(I())
```

```error
I is not callable
```
