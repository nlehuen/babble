# BIP024: Self tail calls

| | |
|---|---|
| **BIP** | 024 |
| **Date** | 2026-11-22 |
| **Status** | Implemented (`BIP024-D1`–`D9` decided and implemented, `BIP024-P1`–`P3` ; §7 lists what landed) |
| **Depends on** | the analysis passes and `LoweringAnalyzer` ([bip010_type-checker-simplification.md](bip010_type-checker-simplification.md)), static argument binding ([bip021_optional-variadic-overloaded-arguments.md](bip021_optional-variadic-overloaded-arguments.md)), the `HIR` compiler ([bip014_optimization-passes.md](bip014_optimization-passes.md)) |
| **Scope** | a call of a function to itself in tail position runs in constant stack space, in the interpreter and in compiled code at every optimization level ; across `analysis.TailCalls`, `ScopeAnalysis`, the `Interpreter`'s `Function`, `HirBuilder`, the `HIR` passes and `HirEmitter` |

---

## 1. Summary

A loop written as recursion keeps one JVM frame per iteration, so it runs out of stack after a few thousand iterations in the interpreter and a few tens of thousands in compiled code :

```babble
def sumTo = fn (n: int, acc: int): int -> (
    if n == 0 then (acc) else (recurse(n - 1, acc + n))
)
sumTo(1000000, 0)        ;; was : Error : Stack overflow
```

The recursive call is the last thing `sumTo` does : its value is `sumTo`'s value. Babble now runs such a *self tail call* as a jump back to the start of the function, with the new arguments, instead of a nested call. Nothing changes in the language : no keyword, no annotation, the same results and error messages ; only the programs that used to overflow now run.

---

## 2. State of the art

* **Scheme** requires proper tail calls of every call in tail position ([R7RS §3.5](https://small.r7rs.org/attachment/r7rs.pdf)). **Lua** and **Erlang** do the same.
* **Kotlin** `tailrec fun` ([Tail recursive functions](https://kotlinlang.org/docs/functions.html#tail-recursive-functions)) and **Scala** (`@tailrec` only checks it, [TailCalls](https://www.scala-lang.org/api/current/scala/annotation/tailrec.html)) turn self tail calls into loops, since the JVM has no tail call instruction ; Scala does it whether or not the annotation is there, and the annotation makes a non-tail recursion an error.
* **Clojure** has an explicit `recur` instead, an error out of tail position.
* General tail calls on the JVM need a trampoline : every tail call returns a thunk that a loop runs, which costs an allocation and a dispatch on every call, tail or not.

Babble already has `recurse`, a name for the running function, so it is closest to Clojure's `recur` ; but `recurse` is also an ordinary call out of tail position, and most code calls a function by its own `def` name. As in Scala, the optimization is automatic.

---

## 3. Decisions

* **`BIP024-D1` — Self tail calls only.** A call is optimized when it calls the running function itself : `recurse(...)` ; a call of the function's own `def` by name, when that `def` is never reassigned and its value is the function literal (`LoweringAnalyzer`'s static Babble call target) ; or `this.m(...)` in method `m` of a class, record or object, whose methods are never overridden. Not interface methods (`this.m(...)` in a default method may run an override), not mutual recursion, not calls of first-class values : those need a trampoline, which would slow down every call.
* **`BIP024-D2` — Semantics, not an optimization.** There is no `tailrec` keyword. Since self tail calls decide which programs run out of stack, they are part of the language, like a loop : the interpreter has them, and the compiler lowers them in `HirBuilder` and `HirEmitter`, at `-O0` already, rather than in an optimizer pass. No optimization level turns them on or off, so `interpret`, `-O0`, `-O1` and `-O2` run the same programs.
* **`BIP024-D3` — Tail position is syntactic.** In a function's block :
  * the last expression of the block is in tail position ;
  * so is the last expression of a block expression in tail position, and so are the branches of an `if` (and `else if`) in tail position ;
  * `return e` in *statement position* puts `e` in tail position. A statement of the function's block is in statement position, and so is a statement of a block in tail or statement position, including the branches of an `if` and the bodies of `while` and `for` loops. Nothing else is : not a `def`'s value, an argument, an operand, a `break` value, nor the operands of `and`, `or`, `not`, `as`, `is` or `!`.

  Statement position guarantees that compiled code has nothing on the operand stack when it jumps, the invariant loop heads already keep.
* **`BIP024-D4` — Statically bound, unchanged values.** The call binds its arguments at compile time (`BIP021-D4`), or passes as many positional arguments as there are parameters ; and nothing between the call and the function's result checks its value at runtime or widens it (no `checkedType`, no numeric widening on the call, or on any block, branch or `return` in between). The widening of the function's own result still applies, to the value of the last activation. A function with a rest parameter (`xs: T...`) and a call with a spread argument (`...e`, both `BIP021-P4`) are left out.
* **`BIP024-D5` — A fresh activation per call.** Each tail call starts a new activation as a call would : a new frame, the names the body defines declared again (`Name used before definition` until their `def` runs), new cells for captured variables, and the default values of omitted optional parameters evaluated again. A closure created by one activation keeps that activation's parameters. All arguments are evaluated before any parameter changes.
* **`BIP024-D6` — Frames are gone.** An optimized activation leaves no frame : Java stack traces show one frame for the whole loop. Babble errors give the line of the innermost frame, so their messages do not change. A tail recursion that never ends now runs, like `while true`, where it used to fail with `Stack overflow` : a test that wants that error needs a recursion out of tail position, such as `1 + recurse(n + 1)`.
* **`BIP024-D7` — Recursion without a base case.** A function whose block always ends in a self tail call (through block expressions, `return`, `forever`, and `if` branches, each `if` with an `else`), with no other `return`, and that cannot throw (`nothrow` as inferred by `BIP007`) can never end : it is an error at its tail expression, `Recursion without a base case : r. If this is intended, wrap the expression with forever`. `fn (n: int): int -> (recurse(n + 1))` is one. A function that may throw is not reported, as it may end with an error : `fn io () -> (handle(accept()) ; recurse())` is a server loop, and `println` may throw.
* **`BIP024-D8` — Loops without an exit.** A `while true` (the literal, in parentheses or not) whose body has no `break` of that loop and no `return`, and whose test and body cannot throw (as inferred by `EffectChecker` for the loop itself), never ends either : `Loop without an exit : while true. If this is intended, wrap the expression with forever`, at the `while`. A condition other than `true` is not analysed. §8 describes how this could later cover any constant condition.
* **`BIP024-D9` — `forever`.** A new keyword acknowledges an expression that never ends : `forever e` is `e`, with its type and value, and runs as `e` does in both backends (`HirBuilder` lowers it to `e`). `forever recurse(n + 1)`, `forever if … then (…) else (…)` (any expression on the tail paths of a function counts, `D7`), and `forever while true then (…)` (`D8`) are accepted. It is a prefix alternative after `return`, so `forever a + b` is `forever (a + b)` ; it keeps tail and statement position (`D3`) ; and it stays a member name after `.` and `?.`. `forever` on an expression that does end is accepted too.

---

## 4. Grammar

```antlr
expression: …
          | RETURN expression                    # returnExpression
          | FOREVER expression                   # foreverExpression
          …
memberName: … | FOREVER ;
FOREVER: 'forever' ;
```

---

## 5. Implementation

* **Analysis.** `analysis.TailCalls` runs at the end of `TypeChecker.run`, after `LoweringAnalyzer`, whose static call targets, argument bindings, runtime checks and widenings it reads. It walks the block of each function literal per `D3`/`D4` and marks the self tail calls, `ScopeAnalysis.isSelfTailCall(call)`. Both backends read the mark, so they optimize exactly the same calls. It then reports `D7` from the marks and `EffectChecker.inferredNothrow`, the function's inferred (not declared) effects, and `D8` from `EffectChecker.loopNothrow`, which `evalWhile` records for each loop by saving and restoring the running `nothrow` flag around it.
* **Interpreter.** A marked call evaluates its arguments, binds them as a call would, and returns a `TailCall` (the arguments and their names) without evaluating its callee (a name, `recurse` or `this.m`). By `D3`/`D4`, that value goes unchanged to the end of the function's block, or through `return`, to `Function.invoke`, which runs activations in a loop : a fresh frame, the parameters bound, the body evaluated, again while the body's value is a `TailCall`.
* **Compiler.** `HirBuilder` lowers a marked call to `HirExpr.SelfTailCall`. `HirEmitter` puts a loop head in each method holding a function's body (the direct entry point, the `$spec` one, or the array one of a function with more than 8 parameters), after the captured cells are loaded and before the frame is entered. A `SelfTailCall` pushes its arguments as the corresponding call would, stores them back into the method's own parameter slots, and jumps to the loop head. The passes never inline a function holding one, and never move one.

---

## 6. Phases

* **`BIP024-P1` — Analysis and interpreter.** `TailCalls`, `ScopeAnalysis.isSelfTailCall`, `TailCall` and the loop in `Function.invoke`.
* **`BIP024-P2` — Compiler.** `HirExpr.SelfTailCall`, the passes, `HirVerifier`, `HirPrinter`, and the loop heads in `HirEmitter`.
* **`BIP024-P3` — Tests and documents.** `src/test/babble/TailCalls.ba` (every mode), a `.batest` file, golden tests of the marked calls, bytecode and optimizer tests, a benchmark, the README.

---

## 7. What landed

* `BIP024-P1` : `analysis.TailCalls`, `ScopeAnalysis.isSelfTailCall`, the interpreter's `TailCall` and its loop in `Function.invoke`.
* `BIP024-P2` : `HirExpr.SelfTailCall` (`HirBuilder` lowers every marked call to it, whatever the function's entry points), `HirRewriter`, `HirPrinter` (`tail-call #N`), `HirVerifier` (only in a function), `HirPurity` (impure, so never moved), `InlinePass` (never inlines a function holding one), and `HirEmitter`'s loop heads : the jump goes back to the method's first instruction, before the frame is entered. `src/test/babble/TailCalls.ba` runs a million activations of each kind in every mode. The tests that provoked `Stack overflow` with `recurse(n + 1)` use `1 + recurse(n + 1)`.
* `BIP024-D7`–`D9` : `Recursion without a base case`, `Loop without an exit`, and the `forever` keyword (grammar, both backends, `TypeChecker`, `EffectChecker`), with golden tests in `TypeCheckerTestCase`, `DifferentialTestCase` snippets, and `forever` in `TailCalls.ba`.
* `BIP024-P3` : `TailCallsTestCase` (golden lists of the marked calls, tail positions or not, self calls only), `functions/tail_calls.batest` (defaults and declarations per activation, named arguments, objects, an error inside an optimized loop, mutual recursion still overflowing, the `forever` errors), `BabbleCompilerTestCase` (each entry point of a tail-recursive function jumps back and never calls itself), `OptimizerTestCase` (lowered at `-O0`, never inlined), the README tour, and a benchmark : a 300,000-deep tail-recursive sum takes 0.06 ms compiled, as the equivalent `while` loop, and `fib(25)` is unchanged. `examples/raytracer-argon/scene.ppm` renders byte for byte.

---

## 8. Future work

* **Constant loop conditions (`BIP024-D8`).** Only the literal `true` is recognised today. The check could run on the folded condition instead, so that `while 1 < 2`, or a `def` that is always `true`, needs `forever` too. A related approach: detect a condition that does not depend on the loop, that is, one `HirLicmPass` would hoist out of it. Such a condition is a constant, so the loop either never runs (dead code, most likely a mistake worth an error) or always runs (it then needs `forever`). Either way, the folding has to happen in the analysis, not in an `-O1` pass, so that `interpret`, `-O0` and `-O1` keep reporting the same errors (`BIP024-D2`). This can be done later.
