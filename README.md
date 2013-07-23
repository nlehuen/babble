The Hodor Language [![build status](https://secure.travis-ci.org/nlehuen/babble.png)](http://travis-ci.org/nlehuen/babble)
===================

The Hodor language is derived from the [Babble Language](https://github.com/nlehuen/babble). Its main objective is to
teach programming to [Hodor](http://en.wikipedia.org/wiki/Hodor_(A_Song_of_Ice_and_Fire)#Hodor).

What does it looks like ?
-------------------------

```
;; hodor...

;;(
    hodor ho-
    DOR !
;;)

;; hodor hoDOR
hoDOR: test1 (
    hodor: add = (a, b) -> ( ;;
        a + b                ;; hodor hodor
    )                        ;;

    ;; hodor hodor, ho-dor.
    hodor: add2 = (a:int, b:int):int -> (
        a + b
    )

    ;; ho-dor
    ;; ho-ho-dor !
    hodor: adder = (a:int):(b:int):int -> (
        hodor! (b:int):int -> (
            a + b
        )
    )

    ;; hodor....
    hodor: fac2 = (n:int):int -> (
        hodor: result = 1
        hodor... n > 1 hodor; (
            ;; the semicolon is optional
            result = result * n ; n = n - 1
        )
        hodor! result
    )

    ;; hodor.
    hodor: fac = (n:int):int -> (
        hodor? n < 2 hodor; (
            1
         ) ,hodor; (
            ;;(
                hodor HOOOODOR !
            ;;)

            n * HOOOODOR(n - 1)
         )
    )

    ;; hodor ho-dor ho. dor.
    hodor: HO_DOR = (a, f=(v) -> (v)) -> ( f(a) )

    ;; ho-dor ? hodor !
    hodor: counter = () -> (
        hodor: c = 0

        () -> (
            c = c + 1
        )
    )

    ;; hodor !
    hodor: counter2 = () -> (
        hodor! HO-DOR (
            hodor: v = 0
            hodor: inc = () -> (
                v = v + 1
            )
        )
    )
)

hodor: main = () -> (
    hodor: local = "hodor !"

    ;; hodor
    hodOOOR ("HODOR, ", local)

    ;; hodor, hodor, hodor !
    hodOOOR("0+1=", test1.add(0,1))
    ;; hodor !
    hodOOOR("1+2=", test1.add(a:1,b:2))
    ;; ho-dor, hodor.
    hodOOOR("2+3=", test1.add2(b:3,a:2))

    hodor: result = test1.adder(2)(2)

    hodor! "ok:" + result + ":" + test1.fac(5) + ":" + test1.fac2(6)
)

;; HO-dor
HODORRRR? (1 < 2, "Hodor hodor?")

HODORRRR? (test1.HO_DOR(1) == 1, "hodor hodor hodor !")
HODORRRR? (test1.HO_DOR(1, (v) -> (v+1)) == 2, "hodor hodor hodor !")

HODORRRR?("ok:4:120:720" == main(), "hodor, hodor hodor !")

;; hodor
hodor: c = test1.counter()
HODORRRR?(1 == c())
hodor: c2 = test1.counter()
HODORRRR?(2 == c())
HODORRRR?(1 == c2())
HODORRRR?(3 == c())

;; hodor hodor, hodor. HODOR !
c = test1.counter2()
HODORRRR?(1 == c.inc())
HODORRRR?(2 == c.inc())
HODORRRR?(3 == c.inc())

;; HO-DOR ! Ho-DOR !
hodor: list = java.util.ArrayList()
HODORRRR?(list.size() == 0)
```

Implemented Features
--------------------

Those are inherited from the [Babble Language](https://github.com/nlehuen/babble) :

Language features :

- [x] no C-style brackets ! Those are nasty to type on most keyboards, especially on Apple keyboards. Plus, Hodor cannot easily use combination keys.
- [x] in fact, there is only one type of brackets in Babble : parentheses `( )`. The grammar is simple enough, and the parser clever enough (thanks to [ANTLR4](http://www.antlr.org/wiki/display/ANTLR4/Home)) to get away with it. Teaching basic arithmetics to Hodor is complicated enough, so imagine showing him the subtleties of brackets.
- [x] no mandatory semicolons, even for multiple statements on the same line. They are supported to facilitate code reading, though (just like punctuation in human languages !). Hodor doesn't use semicolons in any case. As a matter of fact, he can't read, at all.
- [x] functions, supporting recursion and Hodor's closures. Hodor's closures are like poor man's closures, except they are made by Hodor.
- [x] first-class function objects, functions are stored in variables and can be passed around as parameters.
- [x] anonymous function literals. Hodor !
- [x] positional & named parameters in function calls.
- [x] default values for function parameters (including closure support).
- [x] basic types : boolean, int, double, functions. Plus, Hodor of course.
- [x] object literals & namespace
- [x] optional type declaration for variables and parameters, not enforced (for documentation only).
- [x] "native" functions : `hodooor`/`hodOOOR` (for printing), `HODORRRR?` (for assertions).
- [x] directly use Java classes from within Hodor. If you ever want to do that, of course.

Implementation features :

- [x] interpreted mode running on the AST, like 1.8.x Ruby, A.K.A. SLOW MODE.
- [x] less than 100 lines of ANTL4 grammar (see [Hodor.g4](src/main/antlr4/org/hodor/parser/Hodor.g4)).
- [x] clean separation between grammar and implementation thanks to ANTLR4's AST & Visitor support. This allows the grammar to be reused in other contexts (IDEs ?) or by other languages (C# implementation ?).
- [x] [JSR 223](http://www.jcp.org/en/jsr/detail?id=223) support : `javax.script` compatible API.

Planned features
----------------

Language features :

- [ ] interactive mode (since we're interpreted, why not make the most of it ?).
- [ ] better, more user-friendly error reports. Right now parsing & runtime errors are VERY messy.
- [ ] type checking.
- [ ] type inference.
- [ ] object model (class, method, inheritance).
- [ ] collection classes (mapped from Java).
- [ ] first-class iterator support (`for` loop).
- [ ] `yield` keyword : transform functions/method into iterators.
- [ ] functional-style programming : `map`.
- [ ] Hodor !

Implementation features :

- [ ] compiled mode with compilation to bytecode using [ASM](http://asm.ow2.org/) (big one !).
