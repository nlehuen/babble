The Babble Language [![build status](https://secure.travis-ci.org/nlehuen/babble.png)](http://travis-ci.org/nlehuen/babble)
===================

Babble is a toy language running on top of the Java Virtual Machine (in its current implementation). Its objectives are :

* to teach myself about parsing, interpreting and later on compiling a simple programming language.
* to build a simple programming language that in turn can be used to teach programming to beginners.

What does it looks like ?
-------------------------

English keywords:

```
;; comments

;;(
    multi-line
    comments
;;)

;; a package is just a namespace
package test1 (
    def add = (a, b) -> (    ;;
        a + b                ;; function literal
    )                        ;;

    ;; optional types
    def add2 = (a:int, b:int):int -> (
        a + b
    )

    ;; function as first-type objects
    ;; closure support
    def adder = (a:int):(b:int):int -> (
        return (b:int):int -> (
            a + b
        )
    )

    ;; loops
    def fac2 = (n:int):int -> (
        def result = 1
        while n > 1 then (
            ;; the semicolon is optional
            result = result * n ; n = n - 1
        )
        return result
    )

    ;; recursion
    def fac = (n:int):int -> (
        if n < 2 then (
            1
         ) else (
            n * recurse(n - 1)
         )
    )

    ;; default values, including function literals
    def apply = (a, f=(v) -> (v)) -> ( f(a) )

    ;; using function scopes as poor man's objects
    ;; like in Javascript
    def counter = () -> (
        def c = 0

        () -> (
            c = c + 1
        )
    )

    ;; object literals (class-less for the moment)
    def counter2 = () -> (
        return object (
            def v = 0
            def inc = () -> (
                v = v + 1
            )
        )
    )
)

def main = () -> (
    def local = "world !"

    ;; println is a native function
    println ("Hello, ", local)

    ;; positional parameters
    println("0+1=", test1.add(0,1))
    ;; named parameters
    println("1+2=", test1.add(a:1,b:2))
    ;; named parameters can have their order changed
    println("2+3=", test1.add2(b:3,a:2))

    def result = test1.adder(2)(2)

    return "ok:" + result + ":" + test1.fac(5) + ":" + test1.fac2(6)
)

;; Assertions
assert (1 < 2, "Something is wrong")

assert (test1.apply(1) == 1, "apply is broken")
assert (test1.apply(1, (v) -> (v+1)) == 2, "apply is broken")

assert("ok:4:120:720" == main(), "Test failed")

;; testing counter
def c = test1.counter()
assert(1 == c())
def c2 = test1.counter()
assert(2 == c())
assert(1 == c2())
assert(3 == c())

;; testing counter2
c = test1.counter2()
assert(1 == c.inc())
assert(2 == c.inc())
assert(3 == c.inc())

;; importing Java classes and using them in Babble
def list = java.util.ArrayList()
;; TODO : find proper methods
;; list.add(1) ; list.add(2)
assert(list.size() == 0)
```

French keywords :

```
paquet test1 (
    soit additionneur = (a:int):(b:int):int -> (
        retourne (b:int):int -> (
            retourne a + b
        )
    )

    soit additionne = (a:int, b:int):int -> (
        retourne a + b
    )

    soit factorielle = (n:int):int -> (
        si n < 2 alors (
            retourne 1
        ) sinon (
            retourne n * recurrence(n - 1)
        )
    )

    soit factorielle2 = (n:int):int -> (
        soit resultat = 1
        tant que n > 1 alors (
            resultat = resultat * n ; n = n - 1
        )
        resultat
    )

    soit applique = (a, f=(v) -> (v)) -> ( f(a) )

    def compteur = () -> (
        def c = 0

        () -> (
            c = c + 1
        )
    )

    def compteur2 = () -> (
        object (
            def v = 0
            def inc = () -> (
                v = v + 1
            )
        )
    )
)

soit fonction_principale = () -> (
    (
        soit qui = "tout le monde !"
        afficherc ("Bonjour ", qui)
    )

    afficherc ("1+2=", test1.additionne(a:1,b:2))

    soit ajoute2 = test1.additionneur(2)
    soit resultat = ajoute2(2)

    retourne "ok:" + resultat + ":" + test1.factorielle(5) + ":" + test1.factorielle2(6)
)

suppose (1<2, "Quelque chose ne va pas")

suppose (test1.applique(1) == 1, "applique ne fonctionne pas")
suppose (test1.applique(1, (v) -> (v+1)) == 2, "applique ne fonctionne pas")

suppose (fonction_principale() == "ok:4:120:720", "Test echoué")

def c = test1.compteur()
suppose(c() == 1)
suppose(c() == 2)
suppose(c() == 3)

def c2 = test1.compteur2()
suppose(c2.inc() == 1)
suppose(c2.inc() == 2)
suppose(c2.inc() == 3)

def liste = java.util.ArrayList()
suppose(liste.size() == 0)
```


Implemented Features
--------------------

Language features :

- [x] no C-style brackets ! Those are nasty to type on most keyboards, especially on Apple keyboards.
- [x] in fact, there is only one type of brackets in Babble : parentheses `( )`. The grammar is simple enough, and the parser clever enough (thanks to [ANTLR4](http://www.antlr.org/wiki/display/ANTLR4/Home)) to get away with it.
- [x] no mandatory semicolons, even for multiple statements on the same line. They are supported to facilitate code reading, though (just like punctuation in human languages !).
- [x] dual English / French keywords, for easier teaching to french-speaking children (see the same script in [English](src/test/babble/Test1.ba) and [French](src/test/babble/Test1-fr.ba)).
- [x] functions, supporting recursion and poor man's closures.
- [x] first-class function objects, functions are stored in variables and can be passed around as parameters.
- [x] anonymous function literals.
- [x] positional & named parameters in function calls.
- [x] default values for function parameters (including closure support).
- [x] basic types : boolean, int, double, functions.
- [x] object literals & scope
- [x] optional type declaration for variables and parameters, not enforced (for documentation only).
- [x] "native" functions : `print`/`println` (or `affiche`/`afficherc` in French !), `assert` (or `suppose`).
- [x] directly use Java classes from within Babble.

Implementation features :

- [x] interpreted mode running on the AST, like 1.8.x Ruby, A.K.A. SLOW MODE.
- [x] less than 100 lines of ANTL4 grammar (see [Babble.g4](src/main/antlr4/org/babblelang/parser/Babble.g4)).
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

Implementation features :

- [ ] compiled mode with compilation to bytecode using [ASM](http://asm.ow2.org/) (big one !).
