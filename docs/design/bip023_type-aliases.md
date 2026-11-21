# BIP023: Type aliases

| | |
|---|---|
| **BIP** | 023 |
| **Date** | 2026-11-21 |
| **Status** | Implemented (`BIP023-D1`–`D7` decided and implemented ; §5 lists what landed) |
| **Depends on** | static typing, unions, nullables and generics ([bip008_static-typing.md](bip008_static-typing.md)), modules and types across them ([bip005_modules.md](bip005_modules.md)), the predicate-free grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), intersection types ([bip022_intersection-types.md](bip022_intersection-types.md)) |
| **Scope** | `def Name = type T` : a name for a type, generic over the def's type parameters, usable wherever a type is written ; across `Babble.g4`, `BlockScopes`, `TypeAnnotations`, `TypeChecker`, both backends and the formatter |

---

## 1. Summary

Unions and intersections (`BIP008`, `BIP022`) make types long, and a long type repeated across signatures is hard to read and to change. A type alias names one :

```babble
def ShapeWithText = type Shape & (Named | Label)
def describe = fn (x: ShapeWithText): string -> ( if x is Named then ( x.name() ) else ( x.text() ) )

def Pair of (A, B) = type entry of (A, B)
def p: Pair of (string, int) = entry("a", 1)

export def Num = type int | double         ;; and, in another module, shapes.Num
```

An alias is transparent : `ShapeWithText` and `Shape & (Named | Label)` are the same type everywhere, including `is`, `as`, `T?` and type arguments.

---

## 2. State of the art

> As for `BIP021` and `BIP022`, the links could not be fetched from the environment this was written in.

* **TypeScript** `type T = …` ([Type aliases](https://www.typescriptlang.org/docs/handbook/2/everyday-types.html#type-aliases)), **Scala** `type T = …` ([Type members](https://docs.scala-lang.org/tour/abstract-type-members.html)), **Rust** `type T = …;` ([Type aliases](https://doc.rust-lang.org/reference/items/type-aliases.html)), **Haskell** `type T = …` : a declaration of its own, transparent, generic with the language's parameters. TypeScript allows some recursive aliases ; Rust and Haskell do not.
* **Kotlin** and **Swift** spell it `typealias` ([Kotlin](https://kotlinlang.org/docs/type-aliases.html), [Swift](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/declarations/#Type-Alias-Declaration)), also transparent : an alias never makes a new, incompatible type (Haskell's `newtype`, Kotlin's value classes do).
* Babble already binds its types with `def` : `def Shape = interface (…)`, `def Box of T = class (…)`. So an alias is a `def` too.

---

## 3. Decisions

* **`BIP023-D1` — `def Name = type T`.** Rather than `type Name = T` (TypeScript, Scala, Rust), an alias is a `def` whose value is a `type` expression, as classes and interfaces are `def`s whose values are `class` and `interface` expressions. `export def`, `private def`, `of` type parameters, hoisting, block scoping and shadowing across session inputs (`BIP004-D1`) then come from `def` unchanged.
* **`BIP023-D2` — `type` is a reserved keyword**, still a member name after `.` and `?.` (`memberName`), like the other keywords : `Redirect.INHERIT.type()` reads a Java method, but no program can define a name `type`. No `.ba` or `.batest` file of the repository used one.
* **`BIP023-D3` — Only a def's value.** `type T` anywhere else is `A type alias is the value of a def : type int` ; the def takes no annotation of its own (`A type alias takes no annotation : T`).
* **`BIP023-D4` — Transparent.** An annotation naming an alias is the alias's type : relations, members, narrowing, `is` and `as` (whose runtime checks use the aliased type's descriptor), `T?`, type arguments, and unions or intersections of aliases all see through it. Diagnostics and `:type` show the type the alias stands for.
* **`BIP023-D5` — Generic aliases** take the def's type parameters : `def Pair of (A, B) = type entry of (A, B)`, then `Pair of (string, int)`. A generic alias without its arguments is `Missing type arguments : Pair`, and the wrong number is `Wrong number of type arguments : expected 2, got 1`, as for classes.
* **`BIP023-D6` — Not a value.** An alias names a type, not something a program computes with : reading its name in an expression is `A type alias is not a value : T`. The def's runtime value is `null` in both backends, which no program can observe. An exported alias is reached through its module in annotations (`shapes.Num`), from the exporter's `TypedModuleSummary`.
* **`BIP023-D7` — No recursion.** An alias that names itself, directly or through other aliases, is `Recursive type alias : L` : recursive types need a class or an interface.

---

## 4. Grammar

```antlr
expression: …
          | TYPE NL* aliased=type          # typeAliasExpression
memberName: … | TYPE ;
TYPE: 'type' ;
```

`type` starts no other expression, so the grammar stays LL(1) and free of predicates (`ParserGrammarTestCase`). As in any type, a line break may follow `type` or an operator (`|`, `&`), not precede one. The formatter spaces `type` as any keyword.

---

## 5. What landed

* **`Babble.g4`** : `TYPE`, `# typeAliasExpression`, `TYPE` in `memberName`.
* **`BlockScopes`** : `of` type parameters accepted on an alias def (`Type parameters only apply to functions, classes, interfaces, and type aliases : x`).
* **`Type.TypeAlias`** (name, type parameters, aliased type) : the def's static type, which `TypeChecker.visitTypeAlias` computes and `visitSelector` refuses as a value (`BIP023-D6`).
* **`TypeAnnotations`** : `aliasOf` resolves an alias once per walk (the cache is cleared with the annotations, so its diagnostics reach the final walk), detects recursion, and `instantiateAlias` binds the alias's type parameters ; `resolveTypeVar` sees them inside the alias ; `typeThroughModule` returns exported aliases.
* **Backends** : the `type` expression evaluates to `null`, in the `Interpreter` and as a `HirExpr.Const` in `HirBuilder`.
* **Tests** : `src/test/babble/TypeAliases.ba` ; `types/type_aliases.batest` (15 cases, with an exported alias through a module, and `is` against nullables) ; goldens in `TypeCheckerTestCase#testTypeAliases`, `FormatterTestCase#testSpaces`, `ReplTestCase#testTypeAliasesAcrossInputs` ; a `DifferentialTestCase` snippet.
