# BIP024: Self tail calls

| | |
|---|---|
| **BIP** | 024 |
| **Date** | 2026-11-22 |
| **Status** | Partially implemented (`BIP024-D1`–`D6` decided; `BIP024-P1` implemented, `BIP024-P2`–`P3` in progress ; §6 lists what landed) |
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
* **`BIP024-D2` — Automatic, at every optimization level.** There is no `tailrec` keyword. Since the optimization decides which programs run out of stack, it is a lowering of the compiler, not an `-O1` pass, so that `interpret`, `-O0` and `-O1` keep running the same programs.
* **`BIP024-D3` — Tail position is syntactic.** In a function's block :
  * the last expression of the block is in tail position ;
  * so is the last expression of a block expression in tail position, and so are the branches of an `if` (and `else if`) in tail position ;
  * `return e` in *statement position* puts `e` in tail position. A statement of the function's block is in statement position, and so is a statement of a block in tail or statement position, including the branches of an `if` and the bodies of `while` and `for` loops. Nothing else is : not a `def`'s value, an argument, an operand, a `break` value, nor the operands of `and`, `or`, `not`, `as`, `is` or `!`.

  Statement position guarantees that compiled code has nothing on the operand stack when it jumps, the invariant loop heads already keep.
* **`BIP024-D4` — Statically bound, unchanged values.** The call binds its arguments at compile time (`BIP021-D4`), or passes as many positional arguments as there are parameters ; and nothing between the call and the function's result checks its value at runtime or widens it (no `checkedType`, no numeric widening on the call, or on any block, branch or `return` in between). The widening of the function's own result still applies, to the value of the last activation.
* **`BIP024-D5` — A fresh activation per call.** Each tail call starts a new activation as a call would : a new frame, the names the body defines declared again (`Name used before definition` until their `def` runs), new cells for captured variables, and the default values of omitted optional parameters evaluated again. A closure created by one activation keeps that activation's parameters. All arguments are evaluated before any parameter changes.
* **`BIP024-D6` — Frames are gone.** An optimized activation leaves no frame : Java stack traces show one frame for the whole loop. Babble errors give the line of the innermost frame, so their messages do not change.

---

## 4. Implementation

* **Analysis.** `analysis.TailCalls` runs at the end of `TypeChecker.run`, after `LoweringAnalyzer`, whose static call targets, argument bindings, runtime checks and widenings it reads. It walks the block of each function literal per `D3`/`D4` and marks the self tail calls, `ScopeAnalysis.isSelfTailCall(call)`. Both backends read the mark, so they optimize exactly the same calls.
* **Interpreter.** A marked call evaluates its arguments, binds them as a call would, and returns a `TailCall` (the arguments and their names) without evaluating its callee (a name, `recurse` or `this.m`). By `D3`/`D4`, that value goes unchanged to the end of the function's block, or through `return`, to `Function.invoke`, which runs activations in a loop : a fresh frame, the parameters bound, the body evaluated, again while the body's value is a `TailCall`.
* **Compiler.** `HirBuilder` lowers a marked call to `HirExpr.SelfTailCall`. `HirEmitter` puts a loop head in each method holding a function's body (the direct entry point, the `$spec` one, or the array one of a function with more than 8 parameters), after the captured cells are loaded and before the frame is entered. A `SelfTailCall` pushes its arguments as the corresponding call would, stores them back into the method's own parameter slots, and jumps to the loop head. The passes never inline a function holding one, and never move one.

---

## 5. Phases

* **`BIP024-P1` — Analysis and interpreter.** `TailCalls`, `ScopeAnalysis.isSelfTailCall`, `TailCall` and the loop in `Function.invoke`.
* **`BIP024-P2` — Compiler.** `HirExpr.SelfTailCall`, the passes, `HirVerifier`, `HirPrinter`, and the loop heads in `HirEmitter`.
* **`BIP024-P3` — Tests and documents.** `src/test/babble/TailCalls.ba` (every mode), a `.batest` file, golden tests of the marked calls, bytecode and optimizer tests, a benchmark, the README.

---

## 6. What landed

* `BIP024-P1` : `analysis.TailCalls`, `ScopeAnalysis.isSelfTailCall`, the interpreter's `TailCall` and its loop in `Function.invoke`.
