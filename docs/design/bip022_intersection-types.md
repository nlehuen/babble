# BIP022: Intersection types

| | |
|---|---|
| **BIP** | 022 |
| **Date** | 2026-11-21 |
| **Status** | Implemented (`BIP022-D1`–`D8` decided, resolving `BIP022-Q1`–`Q4` (§9) ; `BIP022-I1`–`I2` implemented, §12–§13 record what landed and how it departs from the design) |
| **Depends on** | the object model, interfaces and Java interface bridges ([bip002_object-model.md](bip002_object-model.md)), static typing, unions, narrowing and generics ([bip008_static-typing.md](bip008_static-typing.md)), the type checker's collaborators ([bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), Java nullability and generic invariance ([bip011_core-types.md](bip011_core-types.md)), the predicate-free grammar ([bip012_grammar-improvements.md](bip012_grammar-improvements.md)), bounds and overload sets ([bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md)) |
| **Scope** | `A & B` for interface types : the values that are both. Covers normalization, subtyping, members, `is` narrowing, runtime checks and JVM erasure, Java type variables with several bounds and intersection casts, bounds of type parameters, and the meets of overload sets — across `Babble.g4`, `TypeAlgebra`, `MemberTypes`, `Narrowing`, `JavaTypeBridge`, `LoweringAnalyzer`, `runtime.Types`, `Faces`, both backends, the formatter and `:type` |

---

## 1. Summary

Babble has unions (`A | B`, `BIP008`) but no intersections. This proposal adds them for interface types :

```babble
def Shape = interface ( def area: fn (): double )
def Named = interface ( def name: fn (): string )

def save = fn (x: java.io.Serializable & java.lang.Comparable of int) -> ( … )

def s: Shape = pick()
if s is Named then ( println(s.name() + " " + s.area()) )     ;; s : Shape & Named

def describe = overload (                                       ;; BIP021
    fn (s: Shape): string -> ( "a shape of area " + s.area() ),
    fn (n: Named): string -> ( "something called " + n.name() ),
    fn (x: Shape & Named): string -> ( x.name() + ", of area " + x.area() )
)

def biggest of (T: java.lang.Comparable of T & java.io.Serializable) = fn (a: T, b: T): T -> ( … )
```

`A & B` holds the values that are an `A` and a `B` : instances of classes implementing both, `object`s implementing both, Java objects whose class implements both. It has the members of both. It is what `is` narrows to, what a Java type variable with several bounds means, what several bounds of a Babble type parameter are, and what lets an overload set (`BIP021`) mention two interfaces. At runtime, a check tests both interfaces, and a value is stored as its first operand, as Java erases a type variable to its leftmost bound.

Only interfaces intersect : a Babble class has no subclasses, so `Circle & Shape` is `Circle` or nothing. Intersections of classes, primitives and function types are not proposed (`BIP022-Q1`, §9).

---

## 2. Motivation

### 2.1 What goes wrong today

Run on `master` in both modes :

```babble
;; (a) narrowing forgets the type it narrows
def Shape = interface ( def area: fn (): double )
def Named = interface ( def name: fn (): string )
def Tile = class () implements Shape, Named (
    def area = fn (): double -> ( 1.0 )
    def name = fn (): string -> ( "tile" )
)
def s: Shape = Tile()
if s is Named then ( println(s.name() + " " + s.area()) )
;; Error : No such member : area

```

(a) : `Narrowing` replaces `Shape` by `Named` when `s is Named` succeeds, where the value is both.

An earlier draft gave a second example, `java.util.Collections.max(xs) + 1` failing with `Not a number`, and blamed `max`'s `T extends Object & Comparable<? super T>`. Checking it while implementing showed otherwise : `T` is inferred as `int`, and all of a Java type variable's bounds are already checked (`JavaTypeBridge.satisfiesJavaBounds`) ; the result is `int?` because `Collections` carries no nullness annotations (`BIP011`), so the failure is a nullness one, not an intersection one.

### 2.2 What else needs a name for "both"

* **Overload sets** (`BIP021-D10`). A set is rejected if a call could be ambiguous. `f(s: Shape)` beside `f(n: Named)` is ambiguous for a value implementing both, and the set can only be accepted with an alternative for that meet — which needs a type to write.
* **Several bounds** (`BIP021-D19`). `of (T: java.lang.Comparable of T & java.io.Serializable)` needs `&` ; with intersection types, it is a bound that happens to be one, instead of a special form.
* **Java intersection casts.** Java writes `(Runnable & Serializable) () -> …` to make a lambda serializable ; a Babble function converted to a functional interface has no way to add marker interfaces.

### 2.3 Goals and non-goals

**Goals** : intersection types of interface types, written anywhere a type is ; sound subtyping and members ; narrowing that keeps what it knew ; Java type variables with several bounds and intersection casts ; the meets `BIP021`'s overload sets need.

**Non-goals** : intersections with classes, primitives or function types (`BIP022-Q1`) ; negation and difference types ; structural object types (the README's *Planned features*) ; changing how unions behave.

---

## 3. State of the art

> As for `BIP021`, the links could not be fetched from the environment this draft was written in ; anchors should be checked.

* **Java** has intersection types in three places only : bounds of type variables (`T extends Number & Comparable<T>`, one class then interfaces), casts (`(Runnable & Serializable) () -> …`, which makes the lambda implement both), and the types inference computes ([JLS §4.9](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.9), [§4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.4)). A variable cannot be declared with one, except through `var`. A type variable erases to its leftmost bound ([§4.6](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.6)), and calls on the other bounds compile to casts. An intersection cast on a lambda goes through `LambdaMetafactory.altMetafactory` with marker interfaces ([`altMetafactory`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/LambdaMetafactory.html#altMetafactory(java.lang.invoke.MethodHandles.Lookup,java.lang.String,java.lang.invoke.MethodType,java.lang.Object...))).
* **Scala 3** writes `A & B` anywhere, with `|` its dual ; `&` is commutative, and the members of `A & B` are those of both, with the types of a member both declare intersected ([Intersection types](https://docs.scala-lang.org/scala3/reference/new-types/intersection-types.html)). It replaced Scala 2's non-commutative compound types `A with B`.
* **TypeScript** writes `A & B` for object types, mostly to combine records of members, beside unions ([Intersection types](https://www.typescriptlang.org/docs/handbook/2/objects.html#intersection-types)) ; overloaded call signatures behave as an intersection of function types.
* **Ceylon** made unions and intersections the core of its type system, with every type normalized to a union of intersections and narrowing producing intersections (`if (is Named s)` on a `Shape` gives `Shape & Named`) ([Types](https://ceylon-lang.org/documentation/1.3/tour/types/)).
* **Kotlin** has only `T & Any`, "definitely non-nullable" ([Generics](https://kotlinlang.org/docs/generics.html)), and several bounds in a `where` clause ; its smart casts narrow to an intersection internally, without letting one be written.
* **Theory** : the set-theoretic reading — an intersection is the set of values in both — with decidable subtyping between unions, intersections and negations, is *semantic subtyping* ([Frisch, Castagna & Benzaken, JACM 2008](https://doi.org/10.1145/1391289.1391293)) ; intersections of function types are the types of overloaded functions in λ& ([Castagna, Ghelli & Longo, LFP 1992](https://doi.org/10.1145/141471.141537)).

| Language | Where `&` can be written | Operands | Narrowing gives an intersection | Runtime |
|---|---|---|---|---|
| Java | bounds, casts | one class, then interfaces | no (inference only) | leftmost bound, casts for the others |
| Scala 3 | anywhere | any types | yes | erased to a chosen operand |
| TypeScript | anywhere | any types | yes | none (erased entirely) |
| Ceylon | anywhere | any types | yes | reified |
| Kotlin | `T & Any`, `where` | type variable and `Any` | internally | erased |
| **Babble (this BIP)** | **anywhere** | **interfaces** | **yes** | **first operand, casts for the others** |

**Lessons.** (1) Java's erasure to the leftmost operand, with casts for the others, is the JVM-proven lowering. (2) Narrowing should intersect, as Ceylon and Scala 3 do, so that a test only adds knowledge. (3) A member both operands declare needs a rule : Scala 3 intersects its types ; Babble, whose overloads are explicit values (`BIP021`), can ask for one type to be a subtype of the other. (4) Restricting operands to interfaces keeps the feature where Babble needs it — interfaces are the only open types Babble has — and keeps the runtime check two `instanceof`.

---

## 4. Design

**`BIP022-D1` — `A & B` for interface types.** The operands of an intersection are interface types : Babble interfaces, Java interfaces, and their generic instances (`java.lang.Comparable of int`). Any other operand is `Only interfaces intersect : Circle & Shape` — Java classes included, even where Java bounds start with one (`BIP022-Q1`) — with one exception : `any` and Java's `Object` are dropped, so the bounds Java writes `Object & Comparable<…>` translate (`BIP022-D7`).

**`BIP022-D2` — Normal form and syntax.**

* Nested intersections flatten, duplicates go, and an operand implied by another goes (`Shape & Base` is `Shape` when `Shape extends Base`), so `A & B` and `B & A` are one type, and `:type` shows it in source order with implied operands removed.
* An intersection of one operand is that operand ; `BIP022` adds no empty intersection.
* `&` binds tighter than `|` : `A & B | C` is `(A & B) | C`, as in Scala 3 and TypeScript. A union inside an intersection distributes only when relations need it (`(A | B) & C` relates as `(A & C) | (B & C)`) ; types are kept as written otherwise, so diagnostics and `:type` show what the program says (`BIP022-Q3`).
* A nullable intersection is `(A & B)?` ; a nullable operand (`A & B?`) is rejected : `Nullable inside an intersection : B? (write (A & B)?)`.
* A function type's result is a whole type, so `fn (): A & B` returns an intersection.

**`BIP022-D3` — Subtyping.** `X ≤ A & B` when `X ≤ A` and `X ≤ B` ; `A & B ≤ Y` when `A ≤ Y` or `B ≤ Y`. So a class implementing both interfaces, an `object implements A, B (…)`, and a Java object whose class implements both are each an `A & B`. Generic invariance and `borrow` covariance (`BIP011`) apply operand by operand.

**`BIP022-D4` — Members.** An `A & B` has the members of `A` and of `B`. A name both declare must have the same type, or one type must be a subtype of the other, which is then the member's type ; otherwise the conflict is reported lazily where the member is accessed (`x.m()`) : `Conflicting member area in Shape & Named`. Checking lazily avoids circularity when type annotations reference interfaces whose members are not typed yet, and lets `A & B` (including via `is` narrowing) be formed without failing on unrelated unused methods. Effects (`BIP016`) follow the member chosen.

**`BIP022-D5` — Narrowing intersects.** `x is T` narrows `x` to `x`'s type intersected with `T`, instead of replacing it by `T` : with `s: Shape`, `if s is Named then ( … )` sees a `Shape & Named`, which fixes §2.1 (a). A union distributes : with `x: Shape | Label`, `if x is Named then ( … )` sees `(Shape & Named) | (Label & Named)` (`BIP022-Q2`). When the intersection is not a valid type (`T` a class), narrowing keeps today's behavior : a class `C` that implements the interface narrows to `C`. The `else` branch subtracts as today (`BIP008`).

**`BIP022-D6` — Runtime checks and lowering.**

* `x is A & B` and `x as A & B` test both operands : `Types.check` gets a conjunction of descriptors, and compiled code emits chained short-circuiting `INSTANCEOF` instructions when all operands are statically known interfaces (falling back to `Types.matches` for local or dynamic ones), kept structured in `HIR` so non-JVM targets (`BIP025`) can lower it to a single bitset mask test.
* The JVM has no intersection types. A local, field, parameter or result of type `A & B` is **erased to its first operand** after normalization, as Java erases a type variable to its leftmost bound (`BIP022-Q4`) : descriptors stay predictable, at the price of a cast before the other operands' members. Using a member of another operand compiles to `CHECKCAST` to that operand, then `INVOKEINTERFACE` (or `bm$m` for a Babble interface, `RM003`) ; the cast cannot fail, since the checker proved the value implements it.
* `$spec` descriptors (`RM001`) and JVM interface bridges (`RM003`) use the erasure.

**`BIP022-D7` — Java.**

* A Java type variable with several bounds translates to the intersection of its bounds (`JavaTypeBridge`), with `Object` dropped, a leading class other than `Object` kept alone as the bound (`BIP022-Q1` : intersections stay interfaces only ; `T extends Number & Comparable<T>` is read as `Number`), and a cycle guard for recursive F-bounds (`T extends Comparable<? super T>`). So `Collections.max(xs)`, with `xs: java.util.List of int`, infers `T = int` against `java.lang.Comparable of int`, and `max(xs) + 1` types (§2.1 (b)).
* A Babble function converted to a Java functional interface or an intersection of one functional interface and marker interfaces — `java.lang.Runnable` or `java.lang.Runnable & java.io.Serializable`, by an expected type (`def`, parameter, return, assignment), an explicit `as` cast, or a Java call — eagerly materializes the JVM adapter at that Java-interop boundary (isolated to Java-interop `HIR` and runtime nodes so pure Babble / non-JVM targets never see it). `Faces` uses `LambdaMetafactory.altMetafactory` with `FLAG_MARKERS` (and `Proxy` fallback), implementing `java.io.Serializable` as a marker interface without tying Babble closures to JDK lambda serialization. Two operands with distinct abstract methods are not a functional interface : `Not a functional interface : A & B`.

**`BIP022-D8` — Bounds and overload sets (`BIP021`).**

* A bound is any type, so `of (T: java.lang.Comparable of T & java.io.Serializable)` is a bound that is an intersection (`BIP021-D19`) ; F-bounds work as they do for one interface.
* An intersection is a type argument like any other : `list of (Shape & Named)`.
* In an overload set, the meet of two interface parameters is their intersection, so `BIP021-D10` accepts `f(s: Shape)` beside `f(n: Named)` once the set has `f(x: Shape & Named)`, and names that meet in its witness otherwise. Resolution stays static (`BIP021-D11`) : a value typed `Shape` calls `f(s: Shape)` whatever it implements. A union argument split at runtime tests the most specific alternative first, so a value implementing both reaches `f(x: Shape & Named)`.

---

## 5. Grammar, formatter and display

```antlr
type:             nullableType (('|' | '&') NL* nullableType)* ;   // as implemented : see §12
AMP: '&' ;
```

LL(1), with no predicate (`ParserGrammarTestCase`) : `&` is not otherwise a token, and as a continuation token it allows a line break on either side, as `|` does. The formatter spaces `&` as `|`. `TypeDisplay` prints the normal form, parenthesizing an intersection inside a nullable or a union only when needed.

---

## 6. Diagnostics

| Situation | Message |
|---|---|
| A class, primitive or function type in an intersection | `Only interfaces intersect : Circle & Shape` |
| Two operands that declare a member with unrelated types | `Conflicting member area in Shape & Named` |
| A nullable operand | `Nullable inside an intersection : B? (write (A & B)?)` |
| Converting a function to an intersection with two abstract methods | `Not a functional interface : A & B` |
| An overload set missing the meet of two interfaces (`BIP021-D10`) | `Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) and fn (n : Named)` |

---

## 7. Implementation

| Stage | Changes |
|---|---|
| `Babble.g4` | `AMP`, `intersectionType` (§5) |
| `TypeAnnotations` | annotations to `Type.Intersection`, operand checks (`BIP022-D1`) |
| `TypeAlgebra` | `Type.Intersection`, its normal form, relations, overlap, joins and meets, distribution over unions (`BIP022-D2`, `D3`) |
| `MemberTypes` | members of an intersection and conflicts (`BIP022-D4`) |
| `Narrowing` | `is` intersects (`BIP022-D5`) |
| `JavaTypeBridge` | type variables with several bounds (`BIP022-D7`) |
| `CallSolver`, `TypedModuleSummary` | inference through intersection bounds ; intersections in exported types |
| `LoweringAnalyzer`, `HirEmitter`, `Interpreter` | erasure to the first operand, `CHECKCAST` before other operands' members, two `INSTANCEOF` for `is` (`BIP022-D6`) |
| `runtime.Types` | conjunctive descriptors for `as`, `is` and boundary checks |
| `Faces` | `altMetafactory` with marker interfaces (`BIP022-D7`) |
| `Formatter`, `TypeDisplay` | §5 |
| Docs | the README's tour and checklists, `AGENTS.md`, `BIP008` (narrowing), `BIP021` (bounds and meets) |

---

## 8. Phases

1. **`BIP022-I1` — The type.** *(Implemented, §12.)* Syntax, normal form, subtyping, members, narrowing, runtime checks (`BIP022-D1`–`D6`). Fixes §2.1 (a). `BIP021-I3`'s bounds with `&` need it.
2. **`BIP022-I2` — Java, dispatch and overload sets.** *(Implemented, §13.)* Java type variables with several bounds, eager Java SAM and SAM+markers conversion (`BIP022-D7`), covariant and conflicting member resolution (`BIP022-D4`) with static dispatch on intersection receivers, compiled chained `INSTANCEOF` lowering for `is A & B` (`BIP022-D6`), and bounds / overload-set meets (`BIP022-D8`).

---

## 9. Resolved questions

All decided on 2026-11-21, each as recommended. Each answer is now part of the decision it names.

| Question | Decided | Answer |
|---|---|---|
| **`BIP022-Q1`** — a class among the operands | **`BIP022-D1`**, **`BIP022-D7`** | **interfaces only** ; a Java bound's leading class other than `Object` is kept alone as the bound, to revisit if Java APIs need more |
| **`BIP022-Q2`** — how far narrowing intersects | **`BIP022-D5`** | **unions distribute** : `(Shape \| Label) is Named` gives `(Shape & Named) \| (Label & Named)` |
| **`BIP022-Q3`** — normalizing to a union of intersections | **`BIP022-D2`** | **distribute only when relating** ; types keep the form they were written in |
| **`BIP022-Q4`** — which operand a value is erased to | **`BIP022-D6`** | **the first operand** after normalization, Java's leftmost-bound rule |

---

## 10. Testing

* `src/test/babble/Intersections.ba`, run in the three modes, type-checked and format-checked automatically.
* `types/intersections.batest` : normal form and display, subtyping, members and conflicts, narrowing (§2.1 (a)), `is` and `as`, erasure and the casts it needs, generic arguments and bounds, `Collections.max` and other Java multi-bound methods (§2.1 (b)), marker interfaces on SAM conversion, overload sets with meets (with `BIP021`).
* `TypeCheckerTestCase` goldens for §6, `DifferentialTestCase` snippets for the runtime checks, `FormatterTestCase` goldens for `&`, `BabbleCompilerTestCase` for the `CHECKCAST`s and descriptors, `ParserGrammarTestCase` unchanged.

---

## 11. References

Babble : [BIP002](bip002_object-model.md), [BIP008](bip008_static-typing.md), [BIP010](bip010_type-checker-simplification.md), [BIP011](bip011_core-types.md), [BIP012](bip012_grammar-improvements.md), [BIP021](bip021_optional-variadic-overloaded-arguments.md).

External (not fetched while drafting) :

* **Java** : JLS 21 [§4.4](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.4), [§4.6](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.6), [§4.9](https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html#jls-4.9) ; [`LambdaMetafactory.altMetafactory`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/LambdaMetafactory.html#altMetafactory(java.lang.invoke.MethodHandles.Lookup,java.lang.String,java.lang.invoke.MethodType,java.lang.Object...)).
* **Scala 3** : [Intersection types](https://docs.scala-lang.org/scala3/reference/new-types/intersection-types.html). **TypeScript** : [Intersection types](https://www.typescriptlang.org/docs/handbook/2/objects.html#intersection-types). **Ceylon** : [Types](https://ceylon-lang.org/documentation/1.3/tour/types/). **Kotlin** : [Generics](https://kotlinlang.org/docs/generics.html).
* **Theory** : Frisch, Castagna & Benzaken, *Semantic subtyping*, JACM 2008, [doi:10.1145/1391289.1391293](https://doi.org/10.1145/1391289.1391293) ; Castagna, Ghelli & Longo, *A calculus for overloaded functions with subtyping*, LFP 1992, [doi:10.1145/141471.141537](https://doi.org/10.1145/141471.141537).

---

## 12. What landed in `BIP022-I1`

* **Grammar.** `type` stays one flat list of `nullableType`s, now separated by `|` or `&` (`AMP`), and `TypeAnnotations` groups the operands between two `|` into an intersection. That gives `&` its higher precedence without a new parser rule, so every caller of `type.nullableType()` is unchanged. `ParserGrammarTestCase` still finds no predicate.
* **The type.** `Type.Intersection` and `Type.intersection(…)`, the normal form of `BIP022-D2` : nested intersections flattened, `any` and `java.lang.Object` dropped, duplicates removed, and an interface another operand extends removed, first by Babble nodes (`TypeAlgebra.isSubtype`) and then by Java classes, when it has no type arguments. Operands keep their source order.
* **Operands** (`BIP022-D1`) : Babble and Java interfaces, generic or not, and unions of them ; anything else is `Only interfaces intersect : …`, and a nullable operand is `Nullable inside an intersection : B? (write (A & B)?)`.
* **Relations** (`BIP022-D3`) : `TypeAlgebra.relate` first distributes an intersection with a union operand into a union of intersections (`TypeAlgebra.distribute`), so a `Shape & (Named | Label)` is a `Named | Label` ; then it requires every operand on the right, and one on the left ; `overlapsErased` and `canCast` require every operand to overlap.
* **Members** (`BIP022-D4`) : `MemberTypes` asks each operand that declares the name and reports `Conflicting member m in A & B` lazily on `x.m()` (adopted as the settled `BIP022-D4` rule).
* **Narrowing** (`BIP022-D5`) : `TypeAlgebra.narrowTarget` intersects an interface member with an interface target, over the distributed alternatives of the current type (`TypeAlgebra.alternatives`) : with `x: Shape & (Named | Label)`, `x is Named` gives `Shape & Named` and its `else` branch `Shape & Label`.
* **Runtime** (`BIP022-D6`) : an `inter` descriptor (`LoweringAnalyzer.descriptor`) and `Types.IntersectionType`, checking every member for `is`, `as` and declared types, with the same messages in both modes. Direct static dispatch on intersection receivers (`deriveInterfaceCall` / `deriveJavaMethodCall`) and boxed-primitive dynamic lookup (`Members.select`) landed next, and chained `INSTANCEOF` lowering landed in `BIP022-I2` (§13).
* **Display.** `TypeDisplay` and `Type.toString` print `A & B` and `(A & B)?`.
* **Subsequent phase** : `BIP022-D7` (Java type variables with several bounds, marker interfaces on function conversions), `BIP022-D8` (bounds, overload meets), covariant/conflicting member dispatch fixes, and `is A & B` bytecode lowering landed in `BIP022-I2` (§13).
* **Tests** : `src/test/babble/Intersections.ba` ; `types/intersections.batest` (18 cases) ; goldens in `TypeCheckerTestCase` (`testIntersectionTypes`), `FormatterTestCase` (`testSpaces`) and `ReplTestCase` (`testTypeCommandShowsIntersections`) ; a `DifferentialTestCase` snippet for the runtime checks.

Intersections are long to write : `BIP023` added type aliases to name them, `def ShapeWithText = type Shape & (Named | Label)`.

---

## 13. What landed in `BIP022-I2`

* **Java multi-bound `TypeVariable`s (`BIP022-D7`).** `JavaTypeBridge.fromJavaType` (plus `populateSuperJavaEnv`) threads `activeTypeVars` and delegates unbound type variables to `translateTypeVarBounds` : bounds are translated as `NON_NULL`, `Object.class` is dropped, a leading non-`Object` class is kept alone per `BIP022-D1`, remaining interfaces are intersected, and recursive F-bound cycles return `Type.NULLABLE_ANY`. `TypeAlgebra.projectToJava` projects `Type.Intersection` across operands (`<T extends Consumer<String> & Serializable>`), and `CallSolver` (`solveJava`, `genericBabbleCall`, `javaMethodResult`) and `EffectChecker` (`samInterfaceForJavaType`) infer, promote, and verify SAM+markers bounds (so `Collections.max(xs)! + 1` in §2.1 works with typed elements).
* **Eager Java SAM and SAM+markers conversion (`BIP022-D7`).** `JavaTypeBridge.resolveJavaSam` (`Valid` / `NotFunctional` / `NONE`) recognizes single functional Java interfaces plus zero or more zero-abstract-method Java marker interfaces (plus `Proxy` fallback for hidden/non-accessible interfaces or override-equivalent signatures), rejecting intersections containing Babble interfaces or multiple abstract methods with `"Not a functional interface : …"`. `Faces.function(Callable, Class<?>, Class<?>[], ScriptRuntime)` caches `MARKER_SAM_FACTORIES` using `LambdaMetafactory.altMetafactory` with `FLAG_MARKERS` before `FLAG_BRIDGES` (never `FLAG_SERIALIZABLE`) and `[type, markers...]` `Proxy` fallback across `canLoadAll`. `TypeAlgebra`, `TypeChecker`, `CallSolver`, `TailCalls`, and `LoweringAnalyzer` eagerly mark contextual SAM/SAM+markers targets (`def`, `for` variables after `elemCheck`, parameter defaults, local assignment, `as`, Babble/Java arguments, returns, and function-typed contravariant parameter arrival, while preserving explicit conversion errors on Java fields like `JavaClassDemo.action = fn ...`). `HirExpr.JavaSamAdapt`, `As.samTarget`, `For.elemSamTarget`, and `HirParam.samTarget` keep pure Babble HIR free of Java interop nodes, matched in `Interpreter`, `Function`, `HirEmitter` (`Values.toJavaSam` / `Values.toJavaSamByName`), `JavaClass.call`, and `Types.IntersectionType.accepts`.
* **Member conflicts & covariant dispatch (`BIP022-D4`).** `MemberTypes.intersectionMember` compares `memberSignatureType` across Java-to-Java and Babble-to-Java members symmetrically, choosing the covariant subtype commutatively and lazily reporting `Conflicting member m in A & B` on incompatibility. `LoweringAnalyzer.findDeclaringInterfaceOnType` (`findBestInterfaceMember`) picks the most-specific declaring interface matching `intersectionMember` for both `deriveInterfaceCall` and `deriveOverloadCall`, and `fieldReadTarget` handles Java interface fields on intersection receivers.
* **Compiled `is A & B` lowering (`BIP022-D6`).** `ScopeAnalysis.isIntersectionTargets` and `HirExpr.Is.intersectionTargets` lower `x is A & B` when every operand is a statically accessible top-level Babble or Java interface : `HirEmitter.emitIs` emits at most one `Values.unwrap` (only if any Java interface is present) followed by chained short-circuiting `DUP; INSTANCEOF T_k; IFEQ` checks ending with `INSTANCEOF T_{n-1}`, falling back to `Types.matches` for local interfaces, unions, or inaccessible classes.
* **Overload meets & bounds (`BIP022-D8`).** `OverloadSolver.staticMeet` and `CallSolver.canonicalInterfaceMeet` unwrap `Frozen` wrappers symmetrically so `frozen` modifiers do not obscure interface overload meets.
* **Tests (§10).** `JavaClassDemo`, `src/test/babble/Intersections.ba`, `types/intersections.batest`, `types/intersection_java_and_overloads.batest`, `JavaTypeBridgeTestCase`, `TypeCheckerTestCase`, `DifferentialTestCase`, `BabbleCompilerTestCase` (`testBip022IntersectionLoweringAndSamMarkers`), `FormatterTestCase`, and `ParserGrammarTestCase`.
* **Deferred edge cases.** The following out-of-scope or pre-existing edge cases remain open for follow-up work:
  * Pre-existing `BIP016`/`BIP017` `EffectChecker` HOF and closure-capture soundness gaps (non-`mut` bound methods or annotated/frame-local closures capturing `@BORROWED` parameters/`this` passed through HOFs, forwarded `mutThis` callbacks in multi-target counts, `frameWriteOwners` on frame-local closures, and multi-`mut` / internal-allocation return demotion).
  * Union-split generic overloaded operators where an operand is a union containing a Babble callable requiring per-branch SAM promotion (`OpOverloaded() + u` with `u: (fn ()) | int`), because operator split branches carry no `ArgumentBinding`.
  * Generic borrowed rest spreads from `frozen` callable sequences (`fn of (T: java.lang.Runnable & java.io.Serializable) (xs: T...)` called with `...freeze(list.of(fn ...))`), where element inference preserves `frozen` before bound checking (non-generic `(java.lang.Runnable & java.io.Serializable)...` accepts `frozen` callable spreads).
