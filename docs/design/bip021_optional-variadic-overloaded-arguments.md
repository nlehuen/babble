# BIP021: Optional, variable, generic and overloaded arguments

| | |
|---|---|
| **BIP** | 021 |
| **Date** | 2026-11-21 |
| **Status** | Draft (`BIP021-D1`–`D21` proposed; `BIP021-Q1`–`Q11` open, each with a recommendation; `BIP021-P1`–`P6` not started) |
| **Depends on** | argument binding and parameter defaults ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) §2.4, `BIP001-D3`), the object model and Java interface bridges ([bip002_object-model.md](bip002_object-model.md) §9.2, §9.5), static typing, unions and generics ([bip008_static-typing.md](bip008_static-typing.md) §4.1, §5.3), core types and numeric widening ([bip011_core-types.md](bip011_core-types.md) `BIP011-D4`, `BIP011-D10`), the predicate-free grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), `for` loops over arrays and iterables ([bip013_iterators.md](bip013_iterators.md)), the HIR optimizer ([bip014_optimization-passes.md](bip014_optimization-passes.md)), pure-by-default effects and callback forwarding ([bip016_consolidating-effect-annotations.md](bip016_consolidating-effect-annotations.md), [bip017_higher-order-function-effects.md](bip017_higher-order-function-effects.md)), built-in signatures as axioms ([bip019_refactoring-axioms.md](bip019_refactoring-axioms.md)) |
| **Scope** | Optional parameters in function types and their soundness, statically bound named arguments, variadic parameters (`...xs: T`) and spread arguments (`f(...xs)`), keyword-only parameters, Java varargs calls, generic parameters (generic function literals `fn of T (…)`, bounds `of (T: B)`, polymorphic function types and generic interface methods), and overload sets (`overload(f, g, …)`) : several signatures under one name, disjoint by construction, resolved statically when the types allow it and dispatched on the arguments' runtime types otherwise ; across `Babble.g4`, the four analysis passes, `runtime`, both backends, the formatter and `:type` |

---

## 1. Summary

Babble already has **optional parameters** (`fn (a: int, b: int = a + 1)`, defaults computed at call time in the callee) and **named arguments** (`f(b: 2, a: 1)`). It has no way to declare a function taking **any number of arguments**, cannot call a **Java varargs** method without building the array by hand, and has **one value per name**, so a function cannot offer several signatures : `def f` twice is `Name already defined : f`.

This proposal completes the three, for ergonomics and for Java interop :

```babble
;; optional parameters, now also in function types and interfaces
def Shape = interface (
    def scale: fn (k: double, around: Point = ...): Shape
)

;; variadic parameters, spread arguments, keyword-only parameters after the rest
def join = fn (...parts: string, sep: string = ", "): string -> (
    def sb = java.lang.StringBuilder()
    for p in parts then ( if sb.length() > 0 then ( sb.append(sep) ) ; sb.append(p) )
    sb.toString()
)
join("a", "b", "c")                     ;; "a, b, c"
join(...names, sep: " / ")             ;; any list, array or iterable of string

;; Java varargs, finally
java.lang.String.format("%s has %d items", name, n)
java.util.Arrays.asList("only")
java.nio.file.Path.of("src", "main", "java")

;; generic parameters : literals, bounds, generic functions as values
def biggest of (T: java.lang.Comparable of T) = fn (a: T, b: T): T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
def id = fn of T (t: T): T -> ( t )            ;; id: fn of T (t: T): T
def both = fn (f: fn of T (t: T): T): string -> ( "" + f(1) + f("a") )
both(id)                                       ;; "1a"

;; overload sets : one name, several disjoint signatures
def area = overload(
    fn (c: Circle): double -> ( java.lang.Math.PI * c.r * c.r ),
    fn (s: Square): double -> ( s.side * s.side )
)
area(Circle(1.0))                       ;; resolved statically : a direct call
def s: Circle | Square = pick()
area(s)                                 ;; dispatched on the runtime class, result double

;; overloaded operator methods, the ray tracer's oldest wish
def Vec3 = record (x: double, y: double, z: double) (
    def times = overload(
        fn (k: double): Vec3 -> ( Vec3(this.x * k, this.y * k, this.z * k) ),
        fn (o: Vec3): Vec3 -> ( Vec3(this.x * o.x, this.y * o.y, this.z * o.z) )
    )
)
v * 2.0 ; v * w
```

It also takes the generic functions Babble already has (`def id of T = fn (t: T): T -> (t)`, `BIP008`, `BIP012-D4`) the rest of the way : generic function literals, bounded type parameters, generic interface methods, and generic functions as first-class values with polymorphic function types (`BIP021-D17`–`D21`).

The design rests on one rule that the survey of §3 motivates : **the alternatives of an overload set are pairwise disjoint** — no argument list can fit two of them (`BIP021-D10`). Disjointness is checked once, where the set is built, and reported with a witness call. Because of it, Babble needs none of the ranking machinery of C++, Java or C# (§3.2, §3.3, §3.7) and has none of their ambiguities ; the overload a call selects from static types is always the one its runtime values select, so the interpreter dispatches at runtime, the compiler calls the right alternative directly whenever the static types single it out, and both agree by construction.

---

## 2. Motivation

### 2.1 What Babble has today

| Feature | Today | Where |
|---|---|---|
| Default values | `fn (a, b = a + 1)` ; evaluated **at each call, in the callee's new scope**, so a default sees the earlier parameters and the closure ; `return` in a default is rejected (`BIP001-D3`) | `Arguments`, `Interpreter`, `HirEmitter` (`MISSING` sentinel), `BIP001` §2.4 |
| Typing defaults | a default types its parameter (`(sep = "")` is a `string`) ; `Type.Parameter.optional` ; a function with extra optional parameters is assignable to a shorter function type | `BIP008` §4.1, `TypeAlgebra.functionRelates` |
| Named arguments | `f(a: 1, b: 2)`, any order ; **a named argument also uses up its position** (`f(1, a: 2)` against `(a, b)` is `Missing parameter : b`) ; leftovers are `No such parameter` / `Too many parameters` | `Arguments.Binder`, `CallSolver` |
| Optional before required | allowed : `fn (a: int = 1, b: int)` is called `f(b: 5)` | `Arguments` |
| Variadic functions | only built-ins, each typed by a special case : `print`/`println` (`any` number of `any?`, `to :`), `list(1, 2, 3)`, `map(entry(…), …)` and the other core collection constructors (`BIP011-D10`) | `OperatorTypes`, `CallSolver`, `CoreCollections` |
| Java overloads | resolved statically (`BIP003-D7`, `BIP008` §5.3) and at runtime by argument kinds (`Overloads`, `Overloads.Cache`) ; ambiguity is an error (`BIP002` §9.2) | `Overloads`, `JavaTypeBridge`, `CallSolver` |
| Java varargs | **not expanded** : "varargs methods are matched on their declared array parameter" (`Overloads` Javadoc) | `Overloads`, `overloads_and_varargs.batest` |
| Generic functions | `def id of T = fn (t: T): T -> (t)`, `def apply of (A, B) = …` ; type arguments inferred at calls (`pick(1, "a")` is `int \| string`), given explicitly (`(id of string)("s")`), or taken from an expected type (`def g: fn (x: int): int = id`) ; generic methods (`c.id(3)`) ; generic defs passed as arguments (`apply(id, 3)`) ; **no** bounds, generic literals or polymorphic function types | `BIP008-P6`, `BIP012-D4`, `TypeAnnotations`, `CallSolver` |
| Babble overloads | none : one value per name ; a class implementing an overloaded Java interface method writes **one method per name** accepting every overload's arity, and tells same-arity overloads apart with `is` | `BlockScopes` (`Name already defined`), `BIP002` §9.5 |

### 2.2 What goes wrong

Each of these runs today, in both modes, with the same result (checked on this branch) :

```babble
;; (a) Java varargs : no applicable overload, in a language that runs on the JVM
println(java.lang.String.format("%s-%s", "a", "b"))
;; Error : No applicable overload of java.lang.String.format for (string, string, string)
print(java.util.Arrays.asList("only"))
;; No applicable overload of java.util.Arrays.asList for (string)

;; (b) Overloads : a second def is an error, so the name has to change
def f = fn (x: int): int -> ( x )
def f = fn (x: string): string -> ( x )
;; Error : Name already defined : f
```

The type checker also accepts three programs that then fail at runtime with an argument-binding error, which the static typing of `BIP008` is meant to rule out :

```babble
;; (c) an optional parameter dropped by assignment
def f = fn (a: int, b: int = 2): int -> ( a + b )
def g = fn (a: int, b: int): int -> ( a * b )
def k = f
k = g                        ;; accepted : k is typed (a : int, b : int = …)
println(k(1))                ;; runtime : Missing parameter : b

;; (d) a named argument through a function type
def h = fn (x: int): int -> ( x + 1 )
def k2: fn (a: int): int = h
println(k2(a: 1))           ;; runtime : Missing parameter : x

;; (e) the same through an interface : the implementation renamed the parameter
def I = interface ( def scale: fn (k: double): double )
def C = class () implements I ( def scale = fn (factor: double): double -> ( factor * 2.0 ) )
def i: I = C()
println(i.scale(k: 2.0))    ;; runtime : Missing parameter : factor
```

Generic functions stop short of four things (both modes) :

```babble
;; (f) no generic function literal : only a def introduces type parameters
println((fn of T (t: T): T -> (t))(5))           ;; Syntax error : unexpected 'of'

;; (g) a generic interface method cannot be implemented
def I = interface ( def transform of R: fn (f: fn (x: int): R): R )
def C = class () implements I ( def transform of R = fn (f: fn (x: int): R): R -> ( f(2) ) )
;; Error : Type error : expected (R) (f : (x : int) : R) : R, got (R) (f : (x : int) : R) : R

;; (h) no bounds : nothing can be called on a T
def biggest of T = fn (a: T, b: T): T -> ( if a.compareTo(b) > 0 then ( a ) else ( b ) )
;; Error : Not a scope : a

;; (i) no type for a generic function as a value
def id of T = fn (t: T): T -> ( t )
def k = id                                       ;; Missing type : cannot infer type arguments for id
def twice = fn (f: fn of T (t: T): T) -> ( … )   ;; Syntax error : unexpected 'of'
```

(g) comes from `TypeAlgebra.functionRelates`, which answers `false` as soon as either function type has type parameters. (`def id of T = fn pure (t: T) -> (t)` is rejected for an unrelated reason : `pure` is the default since `BIP016-D1`, and the keyword is only for `axiom pure`.)

(c) comes from `functionRelates` checking a target's optional parameter against the source's without requiring the source's to be optional too. (d) and (e) come from named arguments being bound **at runtime, by the callee's own names**, while the checker bound them by the names of the static type. Any design that adds varargs and overloads to calls has to fix these first, since both make binding richer.

### 2.3 Why now

* **Java interop** is the first motivation. Babble's whole library is Java's, and a large part of Java's API surface is varargs (`String.format`, `List.of` beyond ten elements, `Path.of`, `Arrays.asList`, `EnumSet.of`, `Stream.of`, `MessageFormat.format`, logging façades…). Implementing an overloaded Java interface in Babble (`Appendable`, `Consumer`/`IntConsumer` pairs, `Comparator` helpers) means one hand-dispatched method today.
* **Ergonomics.** The ray tracers want `v * 2.0` and `v * w` on the same `Vec3`, so they write `v.scale(2.0)` and `v.mul(w)` ; `join`, `max`, `sum`, `printf`-like helpers want any number of arguments ; built-in functions get signatures user code cannot write.
* **Typing the built-ins.** `BIP019` moves the built-ins' signatures to `builtins.ba` ; with `...values: any?` and keyword-only parameters, `print`, `println` and the core collection constructors of `BIP011-D10` become ordinary declarations instead of cases in `OperatorTypes` and `CallSolver`.

### 2.4 Goals and non-goals

**Goals**

1. Optional parameters expressible in **function types** and interfaces, and **sound** : the three programs of §2.2 rejected statically.
2. **Variadic parameters** with a statically typed element, spread arguments, keyword-only parameters after the rest.
3. **Java varargs calls** exactly as `javac` resolves them, without changing the resolution of any call that works today.
4. **Overload sets** with several signatures per name, **unambiguous by construction**, the same results in both modes, statically resolved calls as cheap as plain calls.
5. Babble overload sets that **implement overloaded Java interface methods** with one alternative per Java overload.
6. **Generic parameters** as complete as the rest : generic function literals, bounded type parameters, implementable generic interface methods, generic functions as values, and defined interactions with optional parameters, rests and overload sets.

**Non-goals**

* **Keyword-argument dictionaries** (`**kwargs`) : a typed record already plays that role.
* **Overloading on the result type alone** (Ada, Swift) or on effects : the arguments must tell the alternatives apart.
* **Open overload sets**, extended from other modules after the fact (Julia methods, CLOS) : a set is a value, extended only by building a new one (`BIP021-D14`).
* **Several constructors per class** : a class or record keeps one header ; optional and named parameters, or `static` factories that are overload sets, cover the cases.
* **Higher-kinded type parameters**, variance annotations on type parameters, and **specialization per instantiation** (C++ or Rust monomorphization) : type arguments stay erased and values at a type variable stay boxed, as today.
* **Inferring a polymorphic type** for an unannotated lambda : polymorphic function types are checked where they are written, never guessed (`BIP021-D20`).
* **Named arguments to Java methods** : JDK classes are compiled without parameter names (§3.3).
* **Generating Java-visible overloads** for Babble functions (Kotlin's `@JvmOverloads`) : Java does not call Babble functions by name (`BIP006`).

---

## 3. State of the art

The survey follows the four questions this BIP has to answer : *when* is a default computed and what can it see ; how are named arguments bound ; what does a variadic parameter look like inside and outside the function ; and how is one signature chosen among several, *when*, and what happens when two fit. Julia, C++, Java and Python are covered in depth ; the others for one design point each.

> **Note on the links.** They point at the canonical pages (language manuals, specifications, PEPs, evolution proposals, DOIs). They could not be fetched from the environment this draft was written in, whose network policy blocks those hosts, so section anchors in particular should be checked before the BIP is decided.

### 3.1 Julia : multiple dispatch, with defaults as methods

* **Functions are open sets of methods**, selected at **runtime** on the types of **all positional arguments** ([Methods](https://docs.julialang.org/en/v1/manual/methods/)). The most specific applicable method wins ; when no method is most specific, the call raises a `MethodError` reporting an ambiguity — **at the call, not at the definition** ([Method ambiguities](https://docs.julialang.org/en/v1/manual/methods/#man-ambiguities)). Ambiguities are found by tools after the fact ([`Test.detect_ambiguities`](https://docs.julialang.org/en/v1/stdlib/Test/#Test.detect_ambiguities)).
* **Optional positional arguments are syntax for several methods** : `f(a, b = 1)` defines `f(a)` and `f(a, b)` ([Optional arguments](https://docs.julialang.org/en/v1/manual/functions/#Optional-Arguments)). Defaults are evaluated at each call and may use earlier arguments.
* **Keyword arguments**, after a `;`, **do not take part in dispatch** ([Keyword arguments](https://docs.julialang.org/en/v1/manual/functions/#Keyword-Arguments)).
* **Varargs** `f(a, rest...)` receive a tuple, and `f(xs...)` splats any iterable at any position ([Varargs functions](https://docs.julialang.org/en/v1/manual/functions/#Varargs-Functions)).
* **Performance** comes from compiling a specialization per tuple of concrete argument types, which makes most dispatch static once types are inferred ([Bezanson et al., *Julia : dynamism and performance reconciled by design*, OOPSLA 2018](https://doi.org/10.1145/3276490)). The subtype relation that decides specificity is intricate enough to have needed its own formalization ([Zappa Nardelli et al., *Julia subtyping : a rational reconstruction*, OOPSLA 2018](https://doi.org/10.1145/3276483)).
* **Openness has a cost** : any package may add a method to any function, which brings ambiguities between packages that neither author can see, and the community rule against *type piracy* ([Style guide](https://docs.julialang.org/en/v1/manual/style-guide/#Avoid-type-piracy)).

*Takeaways.* Runtime dispatch on every argument is expressive and, specialized, fast ; but specificity rules and open extension make ambiguity a runtime event. Lowering defaults to arities is a clean model of how defaults and overloads interact.

### 3.2 C++ : static overloading with ranked conversions

* **Resolution is static** : name lookup (with [argument-dependent lookup](https://en.cppreference.com/w/cpp/language/adl)) gathers candidates, arity (counting defaults and a C ellipsis) makes them *viable*, and each argument's implicit conversion sequence is **ranked** — exact match, promotion, conversion, user-defined conversion, ellipsis. The best function must be no worse on every argument and better on one ; then come tie-breakers (non-template over template, more specialized template…) ; otherwise the call is ambiguous ([Overload resolution](https://en.cppreference.com/w/cpp/language/overload_resolution)). The rules are notoriously subtle (`0` versus `nullptr`, conversions to `bool`).
* **Default arguments** belong to the declaration, not the function type, are evaluated **at the call site** on each call, and **cannot refer to other parameters** ([Default arguments](https://en.cppreference.com/w/cpp/language/default_arguments)). A virtual call takes the defaults of the **static** type, a classic pitfall : the caller's view of the defaults can disagree with the callee that runs.
* **Variadics** : the C ellipsis with `va_list` is untyped and applies the default argument promotions ([Variadic arguments](https://en.cppreference.com/w/cpp/language/variadic_arguments)) ; C++11 [parameter packs](https://en.cppreference.com/w/cpp/language/pack) are typed and expanded at compile time, one instantiation per call shape.
* **Named arguments** were proposed ([N4172, *Named arguments*, 2014](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2014/n4172.htm)) and not adopted ; designated initializers of aggregates are the usual substitute.
* **An overloaded name as a value** is resolved by the target type ([Address of an overloaded function](https://en.cppreference.com/w/cpp/language/overloaded_address)).

*Takeaways.* Static overloading costs nothing at runtime, but allowing *overlapping* candidates forces a ranking of conversions, and the ranking is where the complexity and the surprises live. Call-site defaults make a function's defaults part of every caller.

### 3.3 Java : three phases, then the most specific

* **Overloads are chosen at compile time** from static types ; only the receiver is dynamic. [JLS §15.12.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.2) runs **three phases** : *strict* invocation (no boxing, no varargs), then *loose* invocation (boxing and unboxing), then *variable arity* invocation — each phase only if the previous found nothing. The phases exist so that adding boxing and varargs in Java 5 **changed the resolution of no existing call**. Among the applicable methods, the *most specific* wins ([§15.12.2.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.2.5)), or the call is ambiguous.
* **Implicitly typed lambdas are not *pertinent to applicability*** ([§15.12.2.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.2.2)) : the overload is chosen from the other arguments, then the lambda is typed against it. This is the problem Babble met as `executor.submit(() -> ( 1 ))` (`BIP002` §9.2).
* **Static choice versus runtime type** is the classic puzzler : with `Object o = "s"`, `print(o)` calls `print(Object)`, not `print(String)`.
* **Varargs** : the last parameter `T... xs` has type `T[]` ([§8.4.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.1)) ; an array can be passed as is (phase 1), which **aliases** it ; a lone `null` is the array itself ; generic varargs create arrays of erased type, hence *heap pollution* and [`@SafeVarargs`](https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.6.4.7). In class files the method carries [`ACC_VARARGS`](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.6), which reflection reports as [`Executable.isVarArgs`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Executable.html#isVarArgs()) and method handles support through [`asVarargsCollector`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandle.html#asVarargsCollector(java.lang.Class)).
* **No default parameters and no named arguments** : telescoping overloads and builders stand in. Parameter names are not even in class files unless compiled with `-parameters` ([`Parameter.isNamePresent`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Parameter.html#isNamePresent())), which the JDK's own classes are not.
* **Erasure limits overloading** : two methods whose signatures have the same erasure cannot both be declared ([§8.4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.2)), so `m(List<String>)` and `m(List<Integer>)` clash.

*Takeaways.* For calling Java, Babble must follow these phases exactly, and adding varargs **last** is what keeps every existing call stable. Pertinence to applicability, the static-versus-runtime puzzler and the erasure clash are three problems a Babble design should rule out rather than inherit.

### 3.4 Python : flexible binding, no overloading

* **Defaults are evaluated once, when `def` runs** ([Function definitions](https://docs.python.org/3/reference/compound_stmts.html#function-definitions)), the source of the shared mutable default pitfall (`def f(xs=[])`). Late-bound defaults that see earlier parameters were proposed in [PEP 671](https://peps.python.org/pep-0671/) and are not accepted.
* **Binding is the richest of the survey** : parameters are positional-or-keyword by default, *keyword-only* after `*args` or a bare `*` ([PEP 3102](https://peps.python.org/pep-3102/)), *positional-only* before `/` ([PEP 570](https://peps.python.org/pep-0570/)) ; `*args` is a tuple and `**kwargs` a dict, typed with `Unpack[TypedDict]` since [PEP 692](https://peps.python.org/pep-0692/) ; calls unpack any number of `*` and `**` ([PEP 448](https://peps.python.org/pep-0448/)). A positional argument cannot follow a keyword argument.
* **No overloading** : a second `def` rebinds the name. [`functools.singledispatch`](https://peps.python.org/pep-0443/) dispatches at runtime on the **first** argument's class ; a general overloading proposal ([PEP 3124](https://peps.python.org/pep-3124/)) was not accepted.
* **`typing.overload` is static only** : a series of signatures for the type checker, then **one** implementation that dispatches by hand ([PEP 484](https://peps.python.org/pep-0484/#function-method-overloading)). Type checkers report overlapping signatures with incompatible results ([typing specification, Overloads](https://typing.python.org/en/latest/spec/overload.html)).

*Takeaways.* Keyword-only parameters after the rest are the right way to combine varargs with options. Definition-time defaults are the pitfall to avoid. Overloads as *signatures for one implementation* is exactly what Babble's `BIP002` §9.5 workaround is, without the signatures.

### 3.5 Kotlin and Scala : static overloading with defaults on the JVM

* **Kotlin** has defaults evaluated at call time that may use earlier parameters, named arguments, one `vararg` (an `Array<out T>`, or `IntArray`…) with the spread operator `*arr`, and parameters after a `vararg` passed by name ([Functions](https://kotlinlang.org/docs/functions.html)). Resolution is static and prefers, among equally specific candidates, the one that needs no default and is not variadic ([Overload resolution](https://kotlinlang.org/spec/overload-resolution.html)). Function types carry no parameter names, so a call through a function value cannot use named arguments. On the JVM, defaults live in the callee : a synthetic `f$default` takes the arguments plus a **bitmask** of the missing ones, and [`@JvmOverloads`](https://kotlinlang.org/docs/java-to-kotlin-interop.html#overloads-generation) generates Java-visible overloads. A reference to an overloaded function is chosen by its expected type ([Function references](https://kotlinlang.org/docs/reflection.html#function-references)).
* **Scala** added named and default arguments with the rule that **only one** overloaded alternative may define defaults ([SID-1, *Named and default arguments*](https://docs.scala-lang.org/sips/named-and-default-arguments.html)) — defaults and overloads make each other ambiguous. Repeated parameters `T*` are a `Seq`, spread with `xs*` ; [`@varargs`](https://www.scala-lang.org/api/current/scala/annotation/varargs.html) generates a Java varargs forwarder. Scala 3 has general [intersection types](https://docs.scala-lang.org/scala3/reference/new-types/intersection-types.html) `A & B`.

*Takeaways.* Callee-side defaults with a missing-argument bitmask is a proven JVM lowering. Defaults and overloads need an interaction rule : Scala forbids, Kotlin breaks ties.

### 3.6 C#, Swift, TypeScript

* **C#** : optional parameters take **constant** defaults that are **copied into each caller**, so changing a default needs callers recompiled ; named arguments bind against the chosen overload's names at compile time ([Named and optional arguments](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/named-and-optional-arguments)) ; [`params`](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/params) arrays, generalized to other collections in C# 13 ([params collections](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-13.0/params-collections)). The *better function member* rules prefer the normal form over the expanded `params` form and a candidate that fills no omitted optional parameter ([Overload resolution](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/expressions#12643-better-function-member)).
* **Swift** : argument labels are **part of the function's name** (`move(from:to:)`), so overloads may differ by labels alone, and also by result type ; variadic `T...` is an array inside, and an array cannot be splatted into it ([Functions](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/functions/)). Function **types** lost their labels in Swift 3 ([SE-0111](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0111-remove-arg-label-type-significance.md)) : labels belong to declarations, not to values. Later came several variadic parameters per function ([SE-0284](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0284-multiple-variadic-parameters.md)) and parameter packs ([SE-0393](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0393-parameter-packs.md)). Overloads combined with literal and closure inference are a known source of slow type checking.
* **TypeScript** : several overload **signatures** over one implementation whose own signature is invisible to callers ([Function overloads](https://www.typescriptlang.org/docs/handbook/2/functions.html#function-overloads)) ; a call takes **the first signature that matches, in declaration order** ([Do's and don'ts](https://www.typescriptlang.org/docs/handbook/declaration-files/do-s-and-don-ts.html)) ; a function type with several call signatures is how an overloaded value is typed. Defaults may use earlier parameters, as in JavaScript.

*Takeaways.* C# shows why defaults belong to the callee ; Swift shows names belong to declarations, not to function types, and that labels can be what tells overloads apart ; TypeScript's order-dependent first match is the opposite of what an unambiguous design wants.

### 3.7 Languages that refuse overloading : Go, Rust, Dart, OCaml

* **Go** : no overloading, by design — "matching only by name and requiring consistency in the types was a major simplifying decision" ([FAQ](https://go.dev/doc/faq#overloading)). A final `...T` parameter is a slice ; `f(s...)` passes the slice itself, **aliased** ([Passing arguments to `...` parameters](https://go.dev/ref/spec#Passing_arguments_to_..._parameters)). No defaults ; "functional options" stand in.
* **Rust** : no overloading, defaults, named arguments or variadics (except C FFI) ; traits (`impl Into<T>`) and macros (`println!`) fill the gaps. The request has stayed open for a decade ([rust-lang/rfcs#323](https://github.com/rust-lang/rfcs/issues/323)). Ad hoc polymorphism through type classes is the principled alternative ([Wadler & Blott, POPL 1989](https://doi.org/10.1145/75277.75283)).
* **Dart** : no overloading ; optional positional `[…]` *or* named `{…}` parameters, `required` named ones, constant defaults ([Parameters](https://dart.dev/language/functions#parameters)) — optional and named parameters as the replacement for overloads.
* **OCaml** : labelled `~x` and optional `?x` arguments, with the rule that an optional argument is only defaulted once a following positional argument is given, because of currying ([Labels and optional arguments](https://ocaml.org/manual/latest/lablexamples.html)). No overloading, as with Hindley–Milner inference.

*Takeaways.* Refusing overloading is a legitimate, simplifying choice, and defaults plus named arguments cover much of the need ; Java interop and operator methods are what push Babble past it.

### 3.8 The Lisp family, Groovy and JavaScript : arity, closed sets and runtime selection

* **Common Lisp** lambda lists have `&optional` (with a *supplied-p* variable), `&rest` and `&key`, defaults evaluated at call time with the earlier parameters in scope ([CLHS 3.4.1](https://www.lispworks.com/documentation/HyperSpec/Body/03_da.htm)). CLOS generic functions dispatch on required arguments and order methods by class precedence.
* **Clojure** functions overload **by arity only**, each arity at most once, with at most one variadic arity ([`fn`](https://clojure.org/reference/special_forms#fn)) : unambiguous by construction, checked when the function is compiled.
* **Scheme** `case-lambda` picks the first clause whose arity fits ([SRFI 16](https://srfi.schemers.org/srfi-16/srfi-16.html)), and Typed Racket types it with `case->`, an **intersection of function types** ([Typed Racket types](https://docs.racket-lang.org/ts-reference/type-ref.html)). The calculus behind such types is λ& ([Castagna, Ghelli & Longo, *A calculus for overloaded functions with subtyping*, LFP 1992](https://doi.org/10.1145/141471.141537)) : with late binding it needs a covariance condition (if `Ti ≤ Tj` then `Ui ≤ Uj`) for soundness. Making open multimethods modularly checkable requires restrictions on where methods may be declared ([Millstein & Chambers, *Modular statically typed multimethods*, ECOOP 1999](https://doi.org/10.1007/3-540-48743-3_13)).
* **Groovy**, also on the JVM, chooses overloads **at runtime from the arguments' runtime types** ([Method selection algorithm](https://groovy-lang.org/objectorientation.html#_method_selection_algorithm)) : the same call can select a different Java overload than `javac` would.
* **JavaScript** defaults are evaluated at call time, may use earlier parameters, and are triggered by `undefined` — an in-band "missing" value ([Default parameters](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Functions/Default_parameters)) ; the rest parameter is a real array and must be last ([Rest parameters](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Functions/rest_parameters)) ; spread takes any iterable, anywhere.

### 3.9 Generic parameters

* **Java** : generic methods such as `<T extends Comparable<? super T>> T max(Collection<? extends T>)` ([JLS §8.4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.4)), with bounds after `extends`, several joined by `&` ([§4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.4)), inferred at calls ([chapter 18](https://docs.oracle.com/javase/specs/jls/se21/html/jls-18.html)) or given as `Collections.<String>emptyList()`. **A lambda cannot be generic** : it is not compatible with a functional interface whose method has type parameters ([§15.27.3](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.27.3)). Types are erased, so `x instanceof T` is illegal.
* **C++** : function templates are instantiated per argument types ; C++20 [constraints and concepts](https://en.cppreference.com/w/cpp/language/constraints) bound them ; C++14 generic lambdas (`[](auto x) { … }`) give a lambda a template call operator ([Lambda expressions](https://en.cppreference.com/w/cpp/language/lambda)), but a generic lambda cannot travel as a still-generic function pointer.
* **Julia** : parametric methods `f(x::T) where {T<:Real}` ([Parametric methods](https://docs.julialang.org/en/v1/manual/methods/#Parametric-Methods)) ; type parameters take part in dispatch, and every function value is generic.
* **Python** : `TypeVar("T", bound=…)`, and since 3.12 `def f[T: Bound](x: T) -> T` ([PEP 695](https://peps.python.org/pep-0695/)).
* **Kotlin, Swift, Rust, C#** : `fun <T : Comparable<T>> max(…)` ([Kotlin generics](https://kotlinlang.org/docs/generics.html)), `func max<T: Comparable>(…)` ([Swift generics](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/generics/)), `fn max<T: Ord>(…)` ([Rust trait bounds](https://doc.rust-lang.org/book/ch10-02-traits.html)), `T Max<T>(…) where T : IComparable<T>` ([C# constraints](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/generics/constraints-on-type-parameters)). The bound is written `T: Bound` in three of them ; lambdas and closures are not generic in any of them. Rust monomorphizes.
* **TypeScript and Scala 3 have polymorphic function values** : `const id = <T>(x: T): T => x` has the type `<T>(x: T) => T` ([Generics](https://www.typescriptlang.org/docs/handbook/2/generics.html)) ; Scala 3 writes [polymorphic function types](https://docs.scala-lang.org/scala3/reference/new-types/polymorphic-function-types.html) `[T] => T => T`. **Haskell** allows them at any rank ([`RankNTypes`](https://ghc.gitlab.haskell.org/ghc/doc/users_guide/exts/rank_polymorphism.html)), with an inference that needs annotations exactly where a polymorphic type is expected ([Peyton Jones, Vytiniotis, Weirich & Shields, *Practical type inference for arbitrary-rank types*, JFP 2007](https://doi.org/10.1017/S0956796806006034)). OCaml writes explicitly polymorphic annotations `'a. 'a -> 'a` for polymorphic recursion and record fields ([Polymorphism and its limitations](https://ocaml.org/manual/latest/polymorphism.html)).

| Language | Generic functions | Bounds | Generic function values | Type arguments at runtime |
|---|---|---|---|---|
| Java | `<T> T f(T t)` | `T extends A & B` | no | erased |
| C++ | templates | concepts, `requires` | generic lambdas, not as values | one instantiation each |
| Julia | parametric methods | `where T<:B` | every function | specialized by the JIT |
| Python | `def f[T](…)` | `T: B` | — (dynamic) | — |
| Kotlin, Swift, Rust | `<T>` | `T: B`, `where` | no | erased / specialized / monomorphized |
| C# | `T F<T>(…)` | `where T : B` | no | reified |
| TypeScript, Scala 3 | `<T>`, `[T]` | `T extends B`, `T <: B` | yes : `<T>(x: T) => T`, `[T] => T => T` | erased |
| Haskell | implicit | type classes | yes, any rank | dictionaries |
| Babble today | `def f of T` | none | no | erased, boxed at `T` |
| **Babble (this BIP)** | **`def f of T`, `fn of T`** | **`of (T: B)`** | **yes, `fn of T (t: T): T`, annotated** | **erased, boxed at `T`** |

*Takeaways.* `T: Bound` is the common spelling, and Babble's own for a typed name. Polymorphic function values are rare on the JVM's languages but free under erasure, and checking them only against written annotations keeps the checker small. An erased type variable is never testable at runtime, which overload sets must respect.

### 3.10 Comparison

| Language | Overloading | Selection | Ambiguity reported | Defaults evaluated | Defaults see earlier params | Named args | Varargs inside | Spread |
|---|---|---|---|---|---|---|---|---|
| Julia | dynamic, all positional args, open | most specific method | at the call, at runtime | per call (as methods) | yes | keywords, not dispatched | tuple | `xs...`, anywhere |
| C++ | static | ranked conversions, tie-breaks | compile time, at the call | per call, at the call site | no | no | `va_list` / packs | pack expansion |
| Java | static (receiver dynamic) | 3 phases, most specific | compile time, at the call | — | — | no | `T[]` | pass the array (aliased) |
| Python | none (`singledispatch` : 1st arg, runtime ; `@overload` : static only) | — | type checker | once, at `def` | no | yes, keyword-only, positional-only | tuple, dict | `*`, `**`, anywhere |
| Kotlin | static | most specific, prefers no defaults, no vararg | compile time | per call, in the callee | yes | yes, not through function types | `Array` | `*arr` |
| Scala | static | most specific ; defaults on one alternative only | compile time | per call | yes | yes | `Seq` | `xs*` |
| C# | static | better function member | compile time | constants copied to callers | — | yes, bound statically | `T[]`, collections | pass the array |
| Swift | static, labels part of the name, result type too | solver ranking | compile time | per call | no | labels | `Array` | none |
| TypeScript | static signatures, one implementation | first match in declaration order | compile time | per call | yes | no | `T[]` | `...`, anywhere |
| Go, Rust, Dart, OCaml | none | — | — | none / none / constants / per call | — | no / no / yes / labels | slice / — / — / — | `s...` (aliased) / — |
| Clojure | arity only | argument count | when compiled | — | — | — | seq | `apply` |
| Common Lisp | CLOS, dynamic, required args | class precedence | none (linearized) | per call | yes, supplied-p | `&key` | list | `apply` |
| Groovy | dynamic, runtime types | distance between types | at runtime | as overloads | yes | via a `Map` | array | `*list` |
| **Babble (this BIP)** | **closed, disjoint sets** | **the only fitting alternative** | **where the set is built** | **per call, in the callee** (today) | **yes** (today) | **yes, bound statically** | **`list of T`** | **`...e`, into the rest only** |

### 3.11 Lessons for Babble

* **L1 — Defaults belong to the callee, evaluated per call.** Babble already does what Julia, Kotlin, Common Lisp and JavaScript do. Python's definition-time evaluation, C#'s constants copied into callers and C++'s static-type defaults on virtual calls are the three failure modes this avoids ; Kotlin's `$default` bitmask shows how to keep it cheap on the JVM. → `BIP021-D1`, `D3`.
* **L2 — Names belong to declarations, and are bound statically.** Swift removed labels from function types, Kotlin refuses named calls through them, C# binds names against the statically chosen signature. Babble's runtime binding by the callee's names is what makes §2.2 (d) and (e) fail. → `BIP021-D4`.
* **L3 — Varargs : typed by the element, a collection inside, keyword-only parameters after.** Java, Kotlin, Swift and Go annotate the element ; Python, Kotlin and Julia make what follows the rest keyword-only. Spread should **copy** (Python, JavaScript), not alias (Go, Java). → `BIP021-D5`–`D7`.
* **L4 — Ranking is the price of overlap.** C++'s conversion ranks, Java's phases and most-specific rule, C#'s better function member, Kotlin's and Scala's default tie-breaks all exist because candidates may overlap. Overlap is also what lets the static choice and the runtime type disagree (Java's puzzler, Groovy's divergence from `javac`). Clojure's arity rule and the disjoint branches of a `case-lambda` show the alternative : **reject overlapping alternatives where they are declared**, and nothing is left to rank. → `BIP021-D10`, `D11`.
* **L5 — Open extension brings non-local ambiguity.** Julia's type piracy and the restrictions Millstein and Chambers need for modular checking come from methods added anywhere. A **closed** set, extended by building a new value, keeps the check local. → `BIP021-D9`, `D14`.
* **L6 — An overloaded value has an intersection type.** λ&, Typed Racket's `case->`, TypeScript's multiple call signatures and Scala 3's `&` agree. Under disjointness, λ&'s covariance condition is vacuous. → `BIP021-D12`.
* **L7 — Lambdas must not choose the overload.** Java excludes implicitly typed lambdas from applicability, and Babble already met the ambiguity (`BIP002` §9.2). If function-typed parameters never tell alternatives apart, the problem cannot arise. → `BIP021-D10`, `D11`.
* **L8 — For Java, follow `javac`, and add varargs last.** Java's own three phases kept every pre-varargs call stable ; Babble can do the same for its own existing Java calls. → `BIP021-D8`.
* **L9 — Bounds are what make generic code useful.** Every surveyed statically typed language bounds type parameters, most of them as `T: Bound`. → `BIP021-D19`.
* **L10 — Generic function values cost nothing at runtime under erasure, and little in the checker if annotated.** TypeScript, Scala 3 and Haskell have them ; Java, Kotlin, Swift and Rust do not, mostly because their lambdas are typed by inference from a monomorphic target. Checking against written polymorphic types (Peyton Jones et al.) is enough. → `BIP021-D20`.
* **L11 — An erased type variable is not a runtime type.** Java forbids `instanceof T` ; an overload alternative taking a `T` can only be told apart by what `T`'s bound excludes. → `BIP021-D21`.

---

## 4. Design

### 4.1 Optional parameters

**`BIP021-D1` — Defaults stay as they are.** A default is evaluated at each call that omits the argument, in the callee's new scope, after the earlier parameters are bound, so it can use them and the closure (`fn (a: int, b: int = a * 10)`). Its effects are part of the function's effects. `return` in a default stays rejected (`BIP001-D3`). This is L1 ; nothing changes for existing programs.

**`BIP021-D2` — Optional parameters in function types : `= ...`.** A function type marks an optional parameter with `= ...` — the source form of what `:type` already displays as `= …` :

```babble
def Shape = interface (
    def scale: fn (k: double, around: Point = ...): Shape
)
def Circle = class (def c: Point, def r: double) implements Shape (
    def scale = fn (k: double, around: Point = this.c): Shape -> ( … )
)
def f: fn (a: int, b: int = ...): int = fn (a, b = a + 1) -> ( a + b )
```

A function type says *that* a parameter may be omitted, never *what* the default is : the default is the callee's (L1). So an interface states which arguments callers may leave out, and each implementation computes its own default, which is what a virtual call in C++ gets wrong.

**`BIP021-D3` — Sound subtyping and a cheap lowering.**

* `fn (…, b: T = ...)` ≤ `fn (…, b: T)` (a caller that always passes `b` can use a function that would default it), and a function with extra trailing optional parameters is assignable to a shorter type, as today.
* `fn (…, b: T)` is **not** ≤ `fn (…, b: T = ...)` : a caller allowed to omit `b` cannot use a function that requires it. This rejects §2.2 (c) statically with `Type mismatch : …`.
* A statically bound call passes omitted arguments as `Arguments.MISSING` in the boxed entry points, as today ; the `$spec` primitive entry point of a function with optional parameters takes a trailing `int` **mask of the missing arguments**, as Kotlin's `$default` does, so a call that omits an `int` argument stays unboxed. Functions with more than 32 optional parameters only have the boxed path.

### 4.2 Named arguments

**`BIP021-D4` — Named arguments are bound statically, against the static type.** The checker already binds every call's arguments to parameters (`CallSolver`) ; that binding becomes the one that runs : `ScopeAnalysis` records, per call, the parameter each argument goes to and which are missing, and both backends pass the arguments **by position**. The names a call may use are those of the callee's static type — the definition when the callee is one, the function type or interface member otherwise. The callee's own names no longer matter at runtime, so §2.2 (d) and (e) bind as written. Function types keep their parameter names (they document, and they are what named calls through them use), and assignability keeps ignoring names, as the survey's languages do (L2).

* The binding rules themselves do not change (`Arguments.Binder` remains their specification, `BIP001` §2.4) unless `BIP021-Q2` says otherwise.
* `Callable.call(…, names, …)` keeps its `names` array for hosts and for the dynamic paths that remain (a Java SAM invoking a Babble function, `BIP002` §9.1) ; compiled Babble-to-Babble calls stop building it.
* Named arguments to Java methods stay unsupported (§2.4).
* **Bonus** : a statically bound named call costs what a positional call costs, with no name scan in the callee.

### 4.3 Variadic parameters and spread arguments

**`BIP021-D5` — `...name: T` declares a rest parameter ; the parameters after it are keyword-only.**

```babble
def max = fn (first: int, ...rest: int): int -> ( … )              ;; one or more ints
def log = fn io (fmt: string, ...args: any?, level: Level = Level.INFO) -> ( … )
def temp = fn (..., celsius: double = ..., fahrenheit: double = ...) -> ( … )  ;; keyword-only, no rest
```

* `T` is the **element** type (L3) ; inside the body, `rest` is a `list of T`. A function has at most one rest parameter. It has no default (absent, it is empty) and is never passed by name.
* Parameters after the rest are **keyword-only** : they are only bound by name, so `log("%d", 1, 2, level: Level.DEBUG)` is unambiguous. A bare `...` with no name declares keyword-only parameters without accepting extra arguments (Python's bare `*`, [PEP 3102](https://peps.python.org/pep-3102/)).
* Binding (`Arguments`) : the rest collects, in order, every positional argument at or after its index ; the parameters after it are bound by name only.
* Parameter modifiers on a rest apply to its **elements** : `mut ...counters: Counter` lets the callee mutate the counters. The list itself is always fresh and owned by the callee (`BIP021-D6`).
* Function types write it the same way : `fn (fmt: string, ...args: any?): string`. A rest parameter is optional in the sense of `BIP021-D3` : `fn (a: int, ...r: int)` ≤ `fn (a: int)`, and ≤ `fn (a: int, b: int)` (the extra argument goes to the rest).

**`BIP021-D6` — Inside, a fresh `list of T` ; outside, whatever is cheapest.** The rest's value is a new `list of T` (`BIP011` core list) the callee owns (`unique`, `BIP007-P2`) : it can return it, `move` it, mutate it inside a `pure` function (fresh local mutation, `BIP007-D6`), and nothing the caller holds aliases it. That is the semantics ; the lowering is free to do better :

* when the analysis proves the list is neither mutated nor escapes (`@BORROWED`, no `mut` use), the compiled function receives an array and the body's `size()`, `get(i)` and `for x in rest` lower to `ARRAYLENGTH`, `<X>ALOAD` and the indexed loop of `BIP013` ;
* for a primitive element type, the `$spec` entry point takes the primitive array (`[I` for `...xs: int`), so `max(1, 2, 3)` boxes nothing.

(`BIP021-Q3` weighs the alternatives : a `frozen` list, or a `JavaArray of T`.)

**`BIP021-D7` — `...e` spreads an iterable into a rest parameter, and copies it.** In a call, `...e` passes the elements of `e` : a `JavaArray of T`, a `list of T` or any `java.lang.Iterable of T`, whose element type must fit the rest's.

```babble
max(0, ...scores)
log("%s : %s", ...pair, level: Level.WARN)
join(...first, "middle", ...second)          ;; several spreads and plain arguments mix
```

* A spread only feeds a **rest parameter** (a Babble rest, or a Java varargs parameter, `BIP021-D8`) : spreading into fixed parameters would make the arity of the call unknowable statically (`BIP021-Q4`). Spread and plain arguments after the fixed ones mix freely, in order.
* Spreading **copies** : the callee's list is new even when `e` is a list (L3) ; spreading reads `e` (`@BORROWED`).
* Since `Callable.call` already takes an argument array, a spread costs building that array ; a call to a statically known function with a statically known spread builds the callee's list or array directly.

### 4.4 Java varargs

**`BIP021-D8` — Java varargs as a third phase, as `javac` does.** Java calls (`Overloads` at runtime, `JavaTypeBridge`/`CallSolver` statically) resolve exactly as today first ; **only if no overload is applicable by fixed arity**, variable-arity methods (`isVarArgs`) are tried with their trailing arguments collected into the array, and the most specific is chosen by [§15.12.2.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.2.5)'s rule for variable arity. Since the new phase only runs where today's answer is `No applicable overload`, **no call that works today changes meaning** (L8).

* `String.format("%s-%s", "a", "b")`, `Arrays.asList("only")`, `Path.of("a", "b")`, `List.of(…)` with eleven elements, `IntStream.of(1, 2, 3)` (an `int[]`, unboxed), `EnumSet.of(…)` all work. Passing an array still binds it directly (phase 1), and a lone `null` is the array, as in Java.
* `...e` spreads into a Java varargs parameter, converting a list or iterable to an array of the parameter's component type (`toArray`), or passing a copy of an array.
* A generic `T...` is inferred as Java infers it, `Arrays.asList(ints)` included (a `List of JavaArray of int`, as in Java ; a warning may point at `...ints`).
* Compiled code : a statically resolved varargs call (`JavaCallTarget`) builds the array at the call site (`ANEWARRAY`/`NEWARRAY` and stores) and calls directly, as `javac`'s output does ; the runtime path collects into the array before `invoke`. `Overloads.Cache` keys by argument kinds, so a varargs call of a new arity is a new entry.
* A Babble method implementing a Java varargs method (`String format(String f, Object... args)`) may take a rest parameter (`fn (f: string, ...args: any?)`) or a `JavaArray of any?` ; the bridge (`RM003`) passes the array as a list or as is.

### 4.5 Overload sets

**`BIP021-D9` — `overload(f, g, …)` builds a closed overload set.** `overload` is a new implicit, like `import` and `same` (French alias `surcharge`) : its arguments are function values whose types are fully known — function literals with annotated parameters, or any function-typed or overload-set-typed expressions, whose sets are flattened — and its result is one value holding them, the set's **alternatives**, bound by one ordinary `def` :

```babble
def show = overload(
    fn (n: int): string -> ( "int " + n ),
    fn (s: string): string -> ( "string " + s ),
    fn (xs: list of int): string -> ( "list of " + xs.size() )
)
def describe = overload(show, fn (b: bool): string -> ( if b then ( "yes" ) else ( "no" ) ))
```

* Explicit rather than a repeated `def` (`BIP021-Q5`) : a second `def f` stays `Name already defined`, so a copy-paste mistake cannot silently become an overload, scoping and hoisting (`BlockScopes`) keep one definition per name, and every alternative is visible in one place, where disjointness is checked.
* An alternative needs every parameter type and its result type : alternatives escape into the set, so `BIP008` §4.1 call-site inference does not apply to them (`Missing type : …` otherwise). Alternatives may be generic (`fn of T (xs: list of T): T`), effectful (`fn io …`), and use `calling` (`BIP017`).
* Inside an alternative, `recurse` is that alternative ; the set calls itself by its name (`show(…)`).

**`BIP021-D10` — Alternatives are pairwise disjoint.** No argument list may fit two alternatives. Formally, two alternatives **overlap** when some call shape — a number of positional arguments and a set of names — binds to both under the rules of §4.2–§4.3, with every argument's parameter types in the two alternatives overlapping at runtime. A set with overlapping alternatives is rejected where it is built, with a witness :

```
Error : Overlapping overloads of area : area(Shape) fits fn (s : Shape) : double and fn (c : Circle) : double
```

*Runtime overlap* is decided on what a value can be at runtime — exactly what `is` and `as` can test (`Types.check`, `ScopeAnalysis.checkedType`) :

| Types | Overlap |
|---|---|
| `any`, `any?` | with everything (`any?` also with `null`) |
| `null`, `T?` | `null` overlaps every nullable type ; `T?` overlaps what `T` overlaps |
| `A \| B` | when `A` or `B` overlaps |
| the primitives `byte`..`char` | only with themselves (`int` and `long` are disjoint ; widening is a conversion, see `BIP021-D11`) |
| `string` | `java.lang.String` and its supertypes (`CharSequence`, `Comparable`, `Object`…) |
| two Babble classes or records | only the same class (Babble classes have no subclasses, `BIP002`) |
| a Babble class and an interface | when the class implements it |
| two interfaces (Babble or Java) | always (some class may implement both) |
| Java classes | when castable one to the other ([JLS §5.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html#jls-5.5)) |
| generic instantiations | by their erasure : `list of int` and `list of string` overlap ([JLS §8.4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.2)) |
| function types | always, with each other and with functional interfaces (L7) |

So `area(Circle)` and `area(Square)` coexist, `area(Shape)` and `area(Circle)` do not (§3.3's puzzler cannot be written ; `Shape`'s own method is the way to dispatch on it), `f(x: int)` and `f(y: int)` do not (`f(1)` fits both) unless the parameters are keyword-only, and `f(a: int)` and `f(a: int, b: int = ...)` do not (`f(1)` again). Arity separates : `f(a)` and `f(a, b)` coexist, a rest overlaps every arity at or above its index.

The check enumerates call shapes up to one past the largest fixed parameter count of the two alternatives (a rest makes all longer shapes alike), and names common to both ; it runs once per set, over a few small parameter lists.

**`BIP021-D11` — Resolution : the only fitting alternative ; numeric widening only when it leaves one.** For a call of a set with arguments of runtime types :

1. the alternatives that accept the call's shape and every argument's runtime type **without conversion** — by disjointness, **at most one** ;
2. if none, the alternatives that accept it with the **numeric widening** of `BIP011-D4` (`byte → short → int → long → float → double`) ; there must be exactly one, otherwise the call is ambiguous.

There is no "most specific" rule, no ranking, and declaration order does not matter (L4). Statically, the checker applies the same rule to **each combination of the runtime kinds the static argument types allow** (the components of a union, the non-null and `null` parts of a nullable, a single kind otherwise) :

* every combination selects the **same** alternative → the call is **statically resolved** : the backends call that alternative directly (a `BabbleCallTarget`, inlinable by `InlinePass`), with its result type and effects ;
* combinations select **different** alternatives → the call is **dispatched at runtime** on the arguments that tell them apart, and its type is the join of those alternatives' results ;
* some combination selects none, or two by widening → `No applicable overload of area for (Shape) : candidates are (c : Circle), (s : Square)` or `Ambiguous call to f for (byte) : candidates are (x : int), (x : double)`, worded as the Java errors already are. `any` arguments need an `as`, as for any typed call (`BIP008`).

Function-typed parameters never separate alternatives (they always overlap), so an untyped lambda argument never takes part in resolution : the alternative is chosen from the other arguments, then the lambda is typed against it (L7). If several alternatives remain, the lambda needs annotations (`Missing type : …`).

Disjointness makes this sound in both directions : a statically resolved call is the alternative runtime dispatch would have chosen, since a value's runtime type lies within its static type, and the interpreter, which may simply dispatch at runtime on every call, gets the compiled code's results (`DifferentialTestCase` checks it).

**`BIP021-D12` — An overload set is a value ; its type is an intersection of function types.** The type of `area` is `(fn (c: Circle): double) & (fn (s: Square): double)` — the λ& / `case->` reading of L6, written with `&` between function types (parentheses required, since a function type's result would otherwise absorb the `&`) :

* a set is assignable to `F` when one of its alternatives is ; a function `g` is assignable to `F1 & F2` when it is to each (`fn (x: int | string): string` is a `(fn (x: int): string) & (fn (x: string): string)`) ;
* where the expected type is a single function type that **exactly one** alternative fits, the value passed is that **alternative** (as C++ and Kotlin select an overloaded function by its target type) : `xs.sort(compare)` hands Java the right comparator and no dispatch remains ; otherwise the value is the set, whose calls dispatch at runtime ;
* `&` is only allowed between function types (`Only function types intersect : int & string`) : general intersection types are not part of this proposal (`BIP021-Q7`).

**`BIP021-D13` — Overloaded methods, interface members and operator methods.** In a class, object, record, interface or `static` body, `def m = overload(fn …, fn …)` makes `m` an overloaded **method** : each alternative is a method with `this` (compiled as its own JVM method `m$0`, `m$1`… with its own `$spec`), and `obj.m(…)` resolves as in `BIP021-D11` — statically to `INVOKEVIRTUAL`/`INVOKEINTERFACE` of the alternative, otherwise through the member's set value.

* **Interfaces** declare an overloaded member with an `&` type ; an implementation conforms when its member's type is a subtype (each interface alternative is covered by an implementation alternative), and JVM interfaces (`InterfaceGen`, `RM003`) get one `bm$m$k` per alternative.
* **Java interfaces** : each Java overload's bridge (`BIP002` §9.5, `RM003`) is resolved statically against the set, from the Java parameter types — `Appendable.append(char)` to `fn (c: char)`, `append(CharSequence)` to `fn (s: java.lang.CharSequence?)` — and calls that alternative directly. A Java overload no alternative accepts is reported where the class is defined, as the arity check is today (`Log.append has no alternative for Appendable.append(CharSequence, int, int)`). A single function remains a valid implementation of all the overloads, so `BIP002` §9.5's form keeps working.
* **Operator methods** (`BIP002`) need nothing more : `v * w` types and compiles as `v.times(w)`, which now resolves among `times`'s alternatives — `v * 2.0` and `v * w` on the same `Vec3`.
* Constructors are not overloaded (§2.4) ; `static ( def of = overload(…) )` gives a class several factories.

**`BIP021-D14` — Modules and sessions : extension by rebinding.** An exported set carries its `&` type and its alternatives' effect templates in the `TypedModuleSummary` (`BIP010-D3`) ; an importer resolves calls statically, and the compiler reaches an alternative through `Module.exportAt(i)` and the set's indexed alternative. Nothing outside the defining scope adds an alternative to a set : another module, or a later input of a session, builds a **new** set from the old one,

```babble
def shapes = import("shapes.ba")
def area = overload(shapes.area, fn (t: Triangle): double -> ( … ))
```

which shadows or rebinds the name as any `def` does (`BIP004-D1`), with disjointness checked again. That is the closed-world answer to L5 : every ambiguity is visible where a set is built.

**`BIP021-D15` — Effects follow the alternative.** A statically resolved call has its alternative's effects, `calling` forwarding (`BIP017`) and algebraic properties (`BIP007`) ; a dispatched call has the join of the effects of the alternatives it may reach, and only the properties they all have. Building a set is pure, as building a function value is. A rest list is fresh (`unique`) ; spreading reads its source.

**`BIP021-D16` — Runtime and lowering.**

* `runtime.OverloadSet implements Callable` (in the `java.base`-only boundary of `BIP006`) holds the alternatives and, per alternative, its arity range, names and the runtime type descriptors of its parameters (the descriptors `as` uses). `call` selects by `BIP021-D11` — with a small per-argument-kinds cache, as `Overloads.Cache` does — and delegates. `member(int)` gives the compiler indexed access. It prints as `overload(…)`, and `same(f, f)` holds ; an alternative selected by an expected type (`BIP021-D12`) is that alternative, not the set.
* The **interpreter** calls the statically resolved alternative when `ScopeAnalysis` records one, and `OverloadSet.call` otherwise.
* The **compiler** lowers `overload(…)` to an `OverloadSet` construction (a new `HirExpr` variant) ; statically resolved calls to direct calls ; dispatched calls to an `INSTANCEOF` chain on the distinguishing arguments only, followed by direct calls — the typecase `is` already compiles to — or, past a few alternatives, to the `invokedynamic` call site with its inline cache. HotSpot's empty-stack OSR invariant at loop heads is unaffected.

### 4.6 Generic parameters

**`BIP021-D17` — What exists stays.** `def f of T = fn (…)`, `def f of (A, B) = …`, type arguments inferred at calls (joined where arguments disagree), given with `f of int`, or taken from an expected type, and generic methods, all keep their meaning. `def id of T = fn pure (t: T) -> (t)`, the example that asked for this section, is written today and after this BIP

```babble
def id of T = fn (t: T): T -> ( t )      ;; pure by default : no keyword (BIP016-D1)
```

**`BIP021-D18` — Generic function literals : `fn of T (…)`.** Type parameters may follow `fn` directly, before any effect : `fn of T (t: T): T -> ( t )`, `fn of (K, V) io (m: map of (K, V)) -> ( … )`. When a `def`'s value is a function literal, `def f of T = fn (…)` and `def f = fn of T (…)` mean the same (the first also puts `T` in scope in the `def`'s own annotation) ; declaring the parameters in both places is an error. Literals give anonymous generic functions, generic overload alternatives (`BIP021-D9`), and generic lambdas for parameters of polymorphic type (`BIP021-D20`).

**`BIP021-D19` — Bounds : `of (T: B)`.** A type parameter may have an upper bound, written as a typed name :

```babble
def biggest of (T: java.lang.Comparable of T) = fn (a: T, b: T): T -> (
    if a.compareTo(b) > 0 then ( a ) else ( b )
)
biggest(3, 7) ; biggest("pear", "apple")
def SortedBag of (T: java.lang.Comparable of T) = class (…) ( … )
```

* Parentheses are required : in `def f of T: X`, `: X` is already the `def`'s own annotation.
* Inside, a `T` is assignable to its bound and has its members ; outside, each type argument, inferred or given, must be a subtype of the bound, or the call is rejected with `type argument string does not satisfy bound java.lang.Comparable of int`, the message Java type variables already get (`BIP008` §5.2). An unbounded parameter's bound is `any?`. F-bounds (the parameter in its own bound) are allowed, as in Java.
* Core scalars satisfy bounds through their Java classes : `int` is a `java.lang.Comparable of int` because `Integer` is a `Comparable<Integer>`, and `string` whatever `String` implements.
* The same syntax bounds the type parameters of classes, records and interfaces.
* Lowering : values at a type variable are boxed, as today ; a call of a bound's method lowers as a call on the bound (`INVOKEINTERFACE` of `Comparable.compareTo`, `RM003`).
* One bound per parameter (`BIP021-Q9`).

**`BIP021-D20` — Polymorphic function types : `fn of T (t: T): T`.** A function type may have type parameters. That is the type of a generic function used as a value with no expected type to instantiate it (`def k = id` gives `k: fn of T (t: T): T`), of a parameter that must receive a generic function, and of a generic interface member (`def transform of R: fn (…)` means `def transform: fn of R (…)`) :

```babble
def both = fn (f: fn of T (t: T): T): string -> ( "" + f(1) + f("a") )
both(id) ; both(fn of T (t: T): T -> ( t ))
```

* **Subtyping.** `fn of T (t: T): T` ≤ `fn (t: int): int` by instantiation (what `def g: fn (x: int): int = id` does today) ; between polymorphic types, by renaming type parameters of the same number and the same bounds ; a monomorphic function is never ≤ a polymorphic type (`Type error : expected fn of T (t : T) : T, got fn (t : int) : int`). This fixes §2.2 (g).
* **Calls** of a value of polymorphic type instantiate it afresh at each call, as calls of a generic `def` do.
* **Checking, not inferring.** A lambda passed where a polymorphic type is expected is checked against it, its type parameters in scope as rigid types (L10) ; a polymorphic type is never inferred for an unannotated lambda.
* **Runtime : nothing.** Type arguments are erased and values at type variables boxed, so a generic function value is an ordinary `Callable` ; its `$spec` entry point covers only its parameters of primitive, non-variable types.

**`BIP021-D21` — Generics meet the rest of this BIP.**

* **Optional parameters.** A default is checked once, valid for every instantiation : `fn of T (a: T, b: T? = null)` and `fn of T (a: T, b: T = a)` are fine, `b: T = null` is not (`Type error : expected T, got null`). A type parameter that only omitted arguments would determine comes from the expected type, else `Missing type : cannot infer type arguments for f`, as `java.util.List.of()` needs today (`BIP021-Q11`).
* **Rests.** `fn of T (...xs: T): list of T` infers `T` from every element, spread ones included, joined as calls join today (`listOf(1, "a")` is a `list of (int | string)`) ; an empty rest determines nothing (previous point). Returning the rest, or its elements, is a return through borrow (`BIP016-D10`).
* **Overload sets.** An alternative's type parameters are instantiated after it is selected. For `BIP021-D10`, a parameter of type `T` overlaps whatever `T`'s bound overlaps — an unbounded `T` overlaps everything, so `fn of T (t: T)` only coexists with alternatives of other arities or names — and generic types overlap by erasure (L11) ; `BIP021-D11` enumerates the arguments' runtime kinds, never instantiations. Type arguments cannot be applied to a set (`Cannot apply type arguments to an overload set : f`) : apply them to an alternative before building it.
* **Named arguments** bind against a generic signature as against any other (`BIP021-D4`).

### 4.7 Grammar, formatter and display

```antlr
fnParam:       paramModifier* ELLIPSIS? ID (':' type)? ('=' NL* defaultValue=expression)?
             | ELLIPSIS ;                                                  // bare ... : keyword-only after
fnTypeParam:   paramModifier* ELLIPSIS? (ID ':')? type ('=' NL* ELLIPSIS)?
             | ELLIPSIS ;
callParameter: (memberName ':')? expression
             | ELLIPSIS expression ;                                       // spread
type:          intersectionType ('|' NL* intersectionType)* ;
intersectionType: nullableType ('&' NL* nullableType)* ;
functionLiteral: FN (OF typeParameters)? effectPrefix? fnParameters (':' UNIQUE? type)? '->' NL* block ;
functionType:    FN (OF typeParameters)? effectPrefix? fnTypeParameters (':' UNIQUE? type)? ;
typeParameters:  ID
               | '(' NL* typeParam (NL* ',' NL* typeParam)* NL* ')' ;     // bounds only in parentheses
typeParam:       ID (':' type)? ;
ELLIPSIS: '...' ;   AMP: '&' ;
```

All LL(1)/LL(2), with no predicate (`ParserGrammarTestCase`). `...` cannot start an expression, and a floating-point literal needs a digit on both sides of its `.`, so `ELLIPSIS` takes no existing program's tokens. `&` is a continuation token, like `|`. `fn of` cannot be a type application (`expression OF typeArguments`), since `fn` is a keyword. `overload` is an ordinary name (the implicit), so it needs no token. The checker rejects `...` in a function literal's default, a rest with a default, a second rest, a spread outside a call to a variadic callee, and `&` between non-function types.

The formatter writes `...xs` and `...e` with no space, `= ...` spaced as any default, `&` spaced as `|`, and `overload(` as a call, one alternative per line when they do not fit. `TypeDisplay` (`:type`) renders `= ...`, `...xs : int`, `(fn …) & (fn …)`, `fn of T (…)` and bounds `of (T : B)`.

### 4.8 Diagnostics

Worded like the runtime errors they predict, with a space before the colon :

| Situation | Message |
|---|---|
| Two alternatives overlap | `Overlapping overloads of f : f(int) fits fn (x : int) : int and fn (y : int) : int` |
| No alternative fits | `No applicable overload of f for (any) : candidates are (x : int), (s : string)` |
| Two alternatives fit only by widening | `Ambiguous call to f for (byte) : candidates are (x : int), (x : double)` |
| An alternative without full types | `Missing type : x` |
| Spread into fixed parameters | `Spread outside of a variadic parameter : ...xs` |
| A second rest, or a rest with a default | `More than one variadic parameter : ys`, `Variadic parameter with a default : xs` |
| A rest passed by name | `No such parameter : xs` (as today for unknown names) |
| A required parameter where the type allows omitting it | `Type mismatch : …` (the existing message, now also for §2.2 (c)) |
| `&` between non-function types | `Only function types intersect : int & string` |
| A type argument outside its bound | `type argument string does not satisfy bound java.lang.Comparable of int` |
| A monomorphic function where a polymorphic type is expected | `Type error : expected fn of T (t : T) : T, got fn (t : int) : int` |
| Type arguments applied to a set | `Cannot apply type arguments to an overload set : f` |
| Type parameters on both the `def` and its literal | `Type parameters declared twice : f` |
| A Java overload no alternative accepts | `Log.append has no alternative for java.lang.Appendable.append(java.lang.CharSequence, int, int)` |

---

## 5. Implementation

| Stage | Changes |
|---|---|
| `Babble.g4` | `ELLIPSIS`, `AMP`, `fn of`, `typeParam`, the rules of §4.7 |
| `BlockScopes` | rest and keyword-only placement ; `return` in defaults unchanged |
| `TypeChecks` | interface conformance with `&` members ; Java interface bridges resolved against sets (`BIP021-D13`) |
| `Resolver` | nothing new : a set is a value under one name |
| `TypeChecker` (`TypeAnnotations`, `TypeAlgebra`, `CallSolver`, `JavaTypeBridge`, `LoweringAnalyzer`) | `Type.Parameter.rest`, keyword-only flag ; `Type.Overloaded` (the `&` type) ; `functionRelates` with `BIP021-D3`'s optional rule and rests ; runtime overlap (`BIP021-D10`, building on `TypeAlgebra`'s overlap) ; `overload(…)` typing in `OperatorTypes` ; binding with rests, spreads and static names recorded per call (`BIP021-D4`) ; resolution and per-kind enumeration (`BIP021-D11`) ; Java phase 3 (`BIP021-D8`) ; new lowering targets (`ArgumentBinding`, `OverloadTarget`, varargs `JavaCallTarget`) |
| Generics (`TypeAnnotations`, `TypeAlgebra`, `MemberTypes`, `CallSolver`) | `Type.TypeVar` bounds ; members of `T` through its bound ; bound checks at every instantiation ; `functionRelates` relating polymorphic types by renaming (fixing §2.2 (g)) ; generic values typed polymorphically instead of `cannot infer` ; rigid type parameters while checking a lambda against a polymorphic type ; `TypedModuleSummary` carrying bounds |
| `EffectChecker` | effects per alternative, joins for dispatched calls, rest lists fresh, spreads borrowed (`BIP021-D15`) |
| `runtime` | `Arguments` (rest, keyword-only), `OverloadSet`, `Overloads` phase 3, `CompiledFunction` (rest index, missing mask) |
| `Interpreter` | static bindings and resolved alternatives from `ScopeAnalysis` ; `OverloadSet.call` otherwise |
| HIR, `HirEmitter`, `ClassGen`, `InterfaceGen` | `Call` with a static binding and spreads ; `MakeOverloadSet` ; dispatch typecase ; `$spec` with missing mask and primitive rest arrays ; one JVM method per alternative of an overloaded method ; varargs array construction for Java |
| `Formatter`, `TypeDisplay` | §4.7 |
| Built-ins | `print`, `println` and the `BIP011-D10` constructors declared with rests and keyword-only parameters in `BIP019`'s `builtins.ba`, retiring their special cases |
| Docs | the README tour and *Implemented* list, `AGENTS.md`, `BIP001` §2.4, `BIP002` §9.5, `BIP008` §5.3 |

---

## 6. Phases

Each phase keeps `mvn package` green in all three Surefire runs and comes with its `.ba` script, `.batest` files and goldens (§8).

1. **`BIP021-P1` — Sound optional and named parameters.** `= ...` in function types and interfaces (`BIP021-D2`), the optional-parameter subtyping rule (`BIP021-D3`), statically bound named arguments lowered positionally in both backends (`BIP021-D4`), and the `$spec` missing mask. Fixes §2.2 (c)–(e). No new syntax beyond `= ...`.
2. **`BIP021-P2` — Java varargs.** Phase 3 in `Overloads` and the checker, array construction for statically resolved calls (`BIP021-D8`). `overloads_and_varargs.batest`'s "not expanded" case flips. Independent of the others ; the largest interop payoff for the smallest change.
3. **`BIP021-P3` — Generic parameters.** `fn of T` literals, bounds on functions, classes, records and interfaces, polymorphic function types, implementable generic interface methods (`BIP021-D17`–`D20`). Fixes §2.2 (f)–(i).
4. **`BIP021-P4` — Babble variadic parameters and spread.** `...xs: T`, keyword-only parameters, `...e` into Babble rests and Java varargs, array-backed and primitive lowering (`BIP021-D5`–`D7`), and the built-ins' signatures, with the generic rests of `BIP021-D21`.
5. **`BIP021-P5` — Overload sets.** `overload(…)`, disjointness, resolution, `&` types, `OverloadSet`, the static and dispatched lowerings, modules and sessions, effects (`BIP021-D9`–`D12`, `D14`–`D16`), and generic alternatives (`BIP021-D21`).
6. **`BIP021-P6` — Overloaded methods.** Methods, interface members, Java interface bridges and operator methods (`BIP021-D13`) ; `Vec3.times` in the ray tracers, which must still render `examples/raytracer-argon/scene.ppm` byte for byte.

---

## 7. Open questions

* **`BIP021-Q1` — Required parameters after optional ones.** Babble allows `fn (a: int = 1, b: int)`, called `f(b: 5)` (Kotlin does too ; Python, C++ and C# forbid it). Keep it, or make every parameter after an optional one either optional or keyword-only ? *Recommendation : keep it* ; `BIP021-D10` handles any order, and forbidding it breaks programs for no soundness gain.
* **`BIP021-Q2` — Positional arguments after named ones.** Today a named argument also uses up its position, so `f(a: 1, 2)` binds `2` to the second parameter. Every other surveyed language either forbids a positional argument after a named one (Python, Kotlin before 1.4) or allows it only in its own position (C# 7.2, Kotlin 1.4+). Keep Babble's rule, or adopt Python's ? *Recommendation : adopt Python's rule*, a warning in `BIP021-P1` and an error from `BIP021-P5` : call shapes become "positional, then named", which makes the rule easy to state and the overlap witnesses of `BIP021-D10` easy to read. To be measured on the repository's `.ba`/`.batest` corpus first.
* **`BIP021-Q3` — What a rest parameter is inside.** A fresh `list of T` (`BIP021-D6`), a `frozen list of T` (no mutation, so always array-backed), or a `JavaArray of T` (Java's choice, no conversion at the boundary, but Java-shaped in Babble code) ? *Recommendation : the fresh `list of T`*, with array-backed lowering when unmutated.
* **`BIP021-Q4` — Spreading into fixed parameters.** JavaScript and Python allow `f(...pair)` for `f(a, b)` ; statically, the length of `pair` is unknown. *Recommendation : no*, until Babble has tuples whose length is a type.
* **`BIP021-Q5` — How a set is written.** The `overload(…)` implicit (`BIP021-D9`), a reserved keyword (`overload ( … )` as syntax, which compiles member alternatives without the implicit-name check), or repeated `def`s of the same name, as Julia, Java and C++ do ? *Recommendation : the implicit* : no grammar, explicit, composable with imported sets ; a keyword can come later if methods need it.
* **`BIP021-Q6` — Numeric widening in resolution.** None (an `int` never selects a `double` alternative when the set has several), widening only when it leaves one alternative (`BIP021-D11`), or Java's nearest widening with a most-specific rule ? *Recommendation : widening when it leaves one*, which keeps `scale(v, 2)` working against `scale(v: Vec3, k: double)` and `scale(v: Vec3, m: Mat3)`, and reports `f(b)` for a `byte` against `int` and `double` alternatives instead of ranking them.
* **`BIP021-Q7` — Writable intersection types.** `&` between function types in annotations (`BIP021-D12`), or only displayed, with overloaded interface members declared some other way ? *Recommendation : writable, function types only.*
* **`BIP021-Q8` — Overlap through interfaces.** `BIP021-D10` rejects `f(s: Shape)` beside `f(c: Circle)` when `Circle implements Shape`. Julia and Java accept such pairs and pick the more specific. Allow them with a most-specific rule (and accept that a statically resolved call may differ from runtime dispatch, or always dispatch at runtime as Julia does) ? *Recommendation : keep rejecting* : it is what makes resolution order-free and both modes agree, and methods of `Shape` already dispatch on its implementations. Sealed interfaces, if Babble gets them, would let `f(Circle)` and `f(Square)` cover a `Shape` argument.
* **`BIP021-Q9` — Several bounds.** `of (T: A & B)` (Java's `extends A & B`, Rust's `T: A + B`), or one bound per parameter ? *Recommendation : one bound* for now ; `&` between interface types can follow, with `BIP021-Q7`.
* **`BIP021-Q10` — Where a literal's type parameters go.** Right after `fn` (`fn of T io (…)`, mirroring `def f of T`), or after the effects, next to the parameters (`fn io of T (…)`) ? *Recommendation : right after `fn`.*
* **`BIP021-Q11` — Type parameters nothing determines.** An error unless an expected type gives them (today's rule ; Kotlin's "not enough information"), or their bound (Java's `Object`, TypeScript's `unknown`) ? *Recommendation : keep the error* : `listOf()` with no expected type has no sensible element type, and the bound would silently widen results to `any?`.

---

## 8. Testing

* `src/test/babble/OptionalArguments.ba`, `Varargs.ba`, `Overloads.ba` : run in the three modes, type-checked and format-checked automatically.
* `.batest` : `functions/optional_and_named.batest` (function types, subtyping, static names, the §2.2 cases), `functions/varargs_and_spread.batest`, `functions/overload_sets.batest` (disjointness witnesses, static and dispatched calls, widening, unions and nullables, generics, lambdas, modules with `==========`, sessions with `----------`), `java/overloads_and_varargs.batest` (phase 3, arrays, `null`, primitives, spread, unchanged fixed-arity resolutions), `objects/overloaded_methods.batest` (methods, interfaces, Java interface bridges, operator methods), `functions/generic_functions.batest` (`fn of T` literals, bounds and F-bounds, scalars satisfying bounds, polymorphic function types and parameters, generic interface methods, `def k = id`, generics with defaults, rests and sets) ; `src/test/babble/Generics.ba` extended.
* `DifferentialTestCase` snippets for every message of §4.7 ; `TypeCheckerTestCase` goldens for them ; `FormatterTestCase` goldens for `...`, `= ...`, `&` and multi-line `overload(…)` ; `ParserGrammarTestCase` unchanged (no predicates, pure `SLL`) ; `ReplTestCase` for `:type` and sets rebuilt across inputs ; `BabbleCompilerTestCase` for the generated methods (`m$0`, `$spec` masks, primitive rest arrays) and that statically resolved calls contain no `invokedynamic` ; `OptimizerTestCase` for inlining a resolved alternative ; `EffectsTestCase` for `BIP021-D15` ; `RuntimeBoundaryTestCase` for `OverloadSet`.
* `Benchmarks` : a statically resolved overloaded call and a named call against plain positional calls (expected equal), a dispatched call, and `max(1, 2, 3)` with primitive rest arrays.

---

## 9. References

Babble : [BIP001](bip001_bytecode-compiler.md) §2.4, [BIP002](bip002_object-model.md) §9.2, §9.5, [BIP007](bip007_effects.md), [BIP008](bip008_static-typing.md) §4.1, §5.3, [BIP010](bip010_type-checker-simplification.md), [BIP011](bip011_core-types.md) `D4`, `D10`, [BIP012](bip012_grammar-improvements.md), [BIP013](bip013_iterators.md), [BIP014](bip014_optimization-passes.md), [BIP016](bip016_consolidating-effect-annotations.md), [BIP017](bip017_higher-order-function-effects.md), [BIP019](bip019_refactoring-axioms.md).

External (not fetched while drafting, see §3) :

* **Julia** : [Functions](https://docs.julialang.org/en/v1/manual/functions/) (optional, keyword and varargs arguments), [Methods](https://docs.julialang.org/en/v1/manual/methods/) (ambiguities), [`Test.detect_ambiguities`](https://docs.julialang.org/en/v1/stdlib/Test/#Test.detect_ambiguities), [Style guide](https://docs.julialang.org/en/v1/manual/style-guide/#Avoid-type-piracy) ; Bezanson et al., OOPSLA 2018, [doi:10.1145/3276490](https://doi.org/10.1145/3276490) ; Zappa Nardelli et al., OOPSLA 2018, [doi:10.1145/3276483](https://doi.org/10.1145/3276483).
* **C++** : cppreference, [Overload resolution](https://en.cppreference.com/w/cpp/language/overload_resolution), [Default arguments](https://en.cppreference.com/w/cpp/language/default_arguments), [Variadic arguments](https://en.cppreference.com/w/cpp/language/variadic_arguments), [Parameter packs](https://en.cppreference.com/w/cpp/language/pack), [ADL](https://en.cppreference.com/w/cpp/language/adl), [Address of an overloaded function](https://en.cppreference.com/w/cpp/language/overloaded_address) ; [N4172](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2014/n4172.htm).
* **Java** : JLS 21 [§15.12.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.2), [§8.4.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.1), [§8.4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.2), [§9.6.4.7](https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.6.4.7), [§5.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html#jls-5.5) ; JVMS 21 [§4.6](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.6) ; [`Executable.isVarArgs`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Executable.html#isVarArgs()), [`MethodHandle.asVarargsCollector`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandle.html#asVarargsCollector(java.lang.Class)), [`Parameter.isNamePresent`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/reflect/Parameter.html#isNamePresent()).
* **Python** : [Function definitions](https://docs.python.org/3/reference/compound_stmts.html#function-definitions) ; PEPs [443](https://peps.python.org/pep-0443/), [448](https://peps.python.org/pep-0448/), [484](https://peps.python.org/pep-0484/#function-method-overloading), [570](https://peps.python.org/pep-0570/), [671](https://peps.python.org/pep-0671/), [692](https://peps.python.org/pep-0692/), [3102](https://peps.python.org/pep-3102/), [3124](https://peps.python.org/pep-3124/) ; [typing specification, Overloads](https://typing.python.org/en/latest/spec/overload.html).
* **Kotlin** : [Functions](https://kotlinlang.org/docs/functions.html), [Overload resolution](https://kotlinlang.org/spec/overload-resolution.html), [`@JvmOverloads`](https://kotlinlang.org/docs/java-to-kotlin-interop.html#overloads-generation), [Function references](https://kotlinlang.org/docs/reflection.html#function-references). **Scala** : [SID-1](https://docs.scala-lang.org/sips/named-and-default-arguments.html), [`@varargs`](https://www.scala-lang.org/api/current/scala/annotation/varargs.html), [Intersection types](https://docs.scala-lang.org/scala3/reference/new-types/intersection-types.html).
* **C#** : [Named and optional arguments](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/named-and-optional-arguments), [`params`](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/params), [params collections](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-13.0/params-collections), [Better function member](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/expressions#12643-better-function-member). **Swift** : [Functions](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/functions/), [SE-0111](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0111-remove-arg-label-type-significance.md), [SE-0284](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0284-multiple-variadic-parameters.md), [SE-0393](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0393-parameter-packs.md). **TypeScript** : [Function overloads](https://www.typescriptlang.org/docs/handbook/2/functions.html#function-overloads), [Do's and don'ts](https://www.typescriptlang.org/docs/handbook/declaration-files/do-s-and-don-ts.html).
* **Go** : [FAQ](https://go.dev/doc/faq#overloading), [spec](https://go.dev/ref/spec#Passing_arguments_to_..._parameters). **Rust** : [rfcs#323](https://github.com/rust-lang/rfcs/issues/323). **Dart** : [Parameters](https://dart.dev/language/functions#parameters). **OCaml** : [Labels](https://ocaml.org/manual/latest/lablexamples.html).
* **Lisp family, Groovy, JavaScript** : [CLHS 3.4.1](https://www.lispworks.com/documentation/HyperSpec/Body/03_da.htm), [Clojure `fn`](https://clojure.org/reference/special_forms#fn), [SRFI 16](https://srfi.schemers.org/srfi-16/srfi-16.html), [Typed Racket types](https://docs.racket-lang.org/ts-reference/type-ref.html), [Groovy method selection](https://groovy-lang.org/objectorientation.html#_method_selection_algorithm), MDN [Default parameters](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Functions/Default_parameters) and [Rest parameters](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Functions/rest_parameters).
* **Generics** : JLS 21 [§4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.4), [§8.4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.4), [§15.27.3](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.27.3), [chapter 18](https://docs.oracle.com/javase/specs/jls/se21/html/jls-18.html) ; cppreference [Constraints and concepts](https://en.cppreference.com/w/cpp/language/constraints), [Lambda expressions](https://en.cppreference.com/w/cpp/language/lambda) ; Julia [Parametric methods](https://docs.julialang.org/en/v1/manual/methods/#Parametric-Methods) ; [PEP 695](https://peps.python.org/pep-0695/) ; [Kotlin generics](https://kotlinlang.org/docs/generics.html) ; [Swift generics](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/generics/) ; [Rust trait bounds](https://doc.rust-lang.org/book/ch10-02-traits.html) ; [C# constraints](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/generics/constraints-on-type-parameters) ; TypeScript [Generics](https://www.typescriptlang.org/docs/handbook/2/generics.html) ; Scala 3 [Polymorphic function types](https://docs.scala-lang.org/scala3/reference/new-types/polymorphic-function-types.html) ; GHC [Arbitrary-rank polymorphism](https://ghc.gitlab.haskell.org/ghc/doc/users_guide/exts/rank_polymorphism.html) ; OCaml [Polymorphism and its limitations](https://ocaml.org/manual/latest/polymorphism.html).
* **Theory** : Castagna, Ghelli & Longo, LFP 1992, [doi:10.1145/141471.141537](https://doi.org/10.1145/141471.141537) ; Millstein & Chambers, ECOOP 1999, [doi:10.1007/3-540-48743-3_13](https://doi.org/10.1007/3-540-48743-3_13) ; Peyton Jones, Vytiniotis, Weirich & Shields, JFP 2007, [doi:10.1017/S0956796806006034](https://doi.org/10.1017/S0956796806006034) ; Wadler & Blott, POPL 1989, [doi:10.1145/75277.75283](https://doi.org/10.1145/75277.75283).
