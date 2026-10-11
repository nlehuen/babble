# Design: compiling Babble to JVM bytecode

| | |
|---|---|
| **Status** | Draft. Decided: block scope (§8.1), stray `return` is a compile-time error (D2), annotations checked at boundaries (§10.3). Deferred: the object model, which needs its own design before phase 2 (§8.2). |
| **Date** | 2026-10-11 |
| **Scope** | `org.babblelang.engine` and a new `org.babblelang.runtime` / `org.babblelang.compiler` |
| **Tracks** | README, *Planned features → compiled mode with compilation to bytecode using ASM* |

## 1. Summary

Babble currently runs as a tree-walking interpreter over the ANTLR parse tree. This
document proposes a compiler that turns a parsed Babble file into a JVM class, loads it
in-process, and runs it behind the existing JSR-223 `Compilable` API. The interpreter stays
in place as the reference implementation and as a fallback.

The work is split into four phases. Each one leaves the build green and can ship on its own:

0. **Shared runtime.** Pull operators, truthiness, member selection and the calling
   convention out of `Interpreter` and the `Callable` natives into a runtime library that does
   not depend on the parse tree. The interpreter is moved onto it. Behaviour does not change.
1. **Namespace-backed compiler.** Generate bytecode that keeps today's runtime data model
   (`Namespace`, `Slot`, `BabbleObject`). Every name lookup is still a hash lookup at runtime,
   but tree walking, visitor dispatch and the `ReturnException` unwinding are gone. The goal
   here is full parity with the interpreter: the same test suite passes in both modes.
2. **Static scope resolution.** A resolver pass binds each identifier to its declaration at
   compile time. Function locals then become JVM locals, or heap cells when closures capture
   them. Namespaces are only kept where the language needs them to be reflective: packages,
   object literals, the script root, and globals.
3. **invokedynamic.** Calls, member selection, Java interop and arithmetic go through
   `invokedynamic` call sites with inline caches. Type specialisation driven by the optional
   type annotations becomes possible.

Type checking is a separate front-end pass that both modes run. It reuses the phase 2
`Resolver`, should land alongside phase 2 code generation, and should come before phase 3,
which is where enforced types pay off (§10).

Decisions so far. Babble gets **real block scope** (§8.1). It lands in the interpreter after
phase 0 and before phase 1, so the compiler targets the new rules from the start. The **object
model** gets its own design before phase 2 (§8.2). **Type annotations are checked at runtime
boundaries** (§10.3).

The bytecode is generated with **ASM**, as the README already plans. The `java.lang.classfile`
API would need a JDK 24+ baseline (see §12).

## 2. Background: what the interpreter does today

The pipeline is: source → `BabbleLexer`/`BabbleParser` (bail on error) → `FileContext` →
`BabbleCompiledScript.eval()` → `new Interpreter(root).visitFile(file)`.

The compiler has to reproduce the following runtime model, so it is listed in full.

### 2.1 Values

* Values are plain Java objects: `Integer` (int literals), `Double` (float literals),
  `Boolean`, `String`, `null`, `Function`, `BoundMethod`, `Namespace`/`BabbleObject`, and the
  Java interop wrappers (`JavaPackage`, `JavaClass`, `JavaObject`, `JavaMethod`, `BoundJavaMethod`).
  Results of Java calls are raw Java objects. Note that `JavaClass` returns a `JavaObject` from a
  constructor, but method results are not wrapped.
* Every construct is an expression and yields one value. `def` yields the assigned value,
  `package` yields `null`, `object` yields the object, `if` without `else` yields `null` when
  the test fails, `while` yields the last body value (or `null`), and a block yields its last
  expression.

### 2.2 Operators (`Interpreter.visitBinaryOp` and friends)

* `+ - *` on two `Integer`s give an `Integer` with wrap-around (`2147483647 + 1` is
  `-2147483648`). Otherwise both operands must be `Number`s and the result is a `double`.
  `/` always gives a `double` (`4 / 2` is `2.0`).
* `+` with a `String` on the left concatenates whatever is on the right, so `"a" + null`
  gives `"anull"`. A `String` on the right only (`1 + "a"`) is an error: `Not a number : "a"`.
* `< <= > >=` compare across numeric types, and otherwise compare `Comparable`s of the same
  class. Anything else fails with `Not comparable : <left text> and <right text>`.
* `== !=` never fail: incomparable values are just unequal, and `1 == 1.0` holds.
* `and`/`or`/`not` short-circuit and always return a `Boolean`, never an operand: `1 and 2`
  is `true`. Truthiness: a `Boolean` is itself, a `Number` is `!= 0.0`, anything else is
  `!= null`.

Error messages quote the **source text** of the operands (`ctx.getText()`). The compiler has
to carry that text into the generated code.

### 2.3 Scopes and names

* A `Namespace` is a `HashMap<String, Slot>` plus a parent pointer. `define` fails on
  redefinition in the same namespace. `get` walks the parent chain and fails with
  `No such name : x`.
* Namespaces are created by: the engine's implicits (`print`, `assert`, `java`, …, which are
  final slots), the per-eval script root (`implicits.enter(null)` plus the `ENGINE_SCOPE`
  bindings), each `package` (re-entering a package with the same name reopens it), each
  `object` literal (`BabbleObject`, whose parent is the enclosing namespace), and each function
  call (a child of the closure).
* **Blocks, `if` and `while` do not create scopes.** A `def` inside a loop body therefore fails
  on the second iteration with `Name already defined : x`.
* Name resolution is **dynamic in time**. A read resolves against whatever is defined *when
  it runs*. So `def a = 1 ; def f = () -> ( def b = a ; def a = 2 ; b + a )` returns `3`: the
  first `a` is the outer one and the second is the local. This is also what makes forward
  references work at top level (`Forward.ba`).
* Assignment `x = v` is `get(x).set(v)`, so it assigns the nearest visible slot. Assigning to
  an implicit (a final slot) throws `IllegalStateException: Slot already set`.

### 2.4 Functions and calls

* A function literal evaluates to `Function(definition, closure)`. A call does: evaluate the
  callee, check that it is `Callable` (`… is not callable`), evaluate the arguments left to
  right into an ordered `Parameters` map, then `bindParameters` and `call`.
* Argument keys: a named argument is keyed by its name. **Every** argument also uses up a
  position, `$0`, `$1`, …, whether it is named or not. So `f(1, a: 2)` against `(a, b)` binds
  `a = 2` and then fails with `Missing parameter : b`, because `$1` was the named argument.
  Leftover arguments are rejected (`No such parameter`, `Too many parameters`).
* Binding happens in a fresh child of the closure. `$recurse` is defined first, then the
  parameters in order. A default value is evaluated **in that new namespace**, so it can see
  earlier parameters: `(a, b = a + 1)`.
* `return` throws `ReturnException`, and `Function.call` catches it. A `return` that reaches the
  file level becomes `return outside of a function`, but only if it actually runs.
  `if false then ( return 1 ) ; 2` is fine.
* `recurse` reads `$recurse` through the namespace chain, which gives the innermost function.

### 2.5 Objects, methods and `this`

* `obj.name` requires a `Scope` (`Not a scope : <text>`). On a `BabbleObject`, `get` looks
  up **the whole parent chain**, not just the object's own members. If what it finds is a
  `Function` that is not one of the object's own locals, it wraps it in a `BoundMethod` and
  caches that in the object.
* That fall-through is how `Stack.ba` builds classes. `create` defines `push`/`pop`/… as
  locals of the function, then returns `object ( def size = 0 ; def head = null )`. `l.push` is
  found in `create`'s call namespace and bound to `l`. It reaches plain data too:
  `mk().hidden` reads a local of `mk`, and `o.println` returns the global `println`.
* `BoundMethod` defines `this` in the call namespace, and makes `$recurse` resolve to the bound
  method. **`this` is lexical:** a nested lambda inside a method sees the method's `this`
  through its closure. A function that is merely *called from* a method does not.
  Functions defined *inside* the object literal are the object's own locals, so they are
  **not** bound. They reach the object's fields through their closure (`counter2` in
  `Test1.ba`).

### 2.6 Natives and Java interop

* `Callable` is two-phase and tied to the interpreter:
  `bindParameters(Interpreter, CallContext, Namespace parent, Parameters)` and then
  `call(Interpreter, CallContext, Scope)`. Natives pass their arguments to themselves through
  a throwaway namespace.
* Natives use the parse tree. `assert` builds its message from
  `callSite.callParameters().callParameter(0).expression().getText()` and the call line.
  `print` looks up `STDOUT` **through the caller's namespace**, which means it is dynamically
  scoped.
* Java interop resolves overloads through reflection on **every** call (`Overloads`).

### 2.7 Errors

`Interpreter` records the last visited context in `last`. `BabbleCompiledScript` turns any
exception into `ScriptException(e.toString(), "<input>", last.getStart().getLine())`. Tests
assert on that exact message shape, for example
`org.babblelang.engine.BabbleException: Not a scope : s in <input> at line number 1`.

## 3. Goals and non-goals

**Goals**

* G1. Compile any program the interpreter accepts and give the same observable result:
  values, printed output, error class, error message and reported line. Divergences are allowed
  only where §8 lists them and they have been accepted.
* G2. Keep JSR-223 as the only entry point: `engine.compile(...)` returns a `CompiledScript`
  that can be evaluated many times with different bindings. The generated class holds no
  mutable state, so it adds no obstacle to concurrent evals. Engine-level caches, such as
  `ImportFunction`'s package map, are a separate and pre-existing concern.
* G3. Have JVM stack traces and `javap` output that a person can read: Babble line numbers,
  method names taken from `def` names, and the script name as the `SourceFile`.
* G4. Support learning and debugging: dump generated classes, verify them in tests, and run
  any test in either mode.
* G5. Make Babble programs measurably faster than the interpreter (§11). Phase 1 should already
  win; phases 2 and 3 should win by a lot.

**Non-goals (for this design)**

* Ahead-of-time `.class`/`.jar` output and a `babblec` CLI. The class layout is chosen so that
  this is easy later (§9, phase 4).
* Type checking. It is a separate front-end pass with its own design. §10 explains where it
  fits relative to the compiler phases, and what the compiler gains from it.
* New language features (classes, `for`, `yield`). The compiler must not make them harder,
  though. §8 says where they interact. Block scope is the exception: static resolution needs a
  fixed scoping rule anyway, so it is specified here (§8.1).
* Removing the interpreter.

## 4. Architecture

```
            ┌─────────────────── BabbleScriptEngine.compile(reader) ────────────────────┐
source ──►  │  lex/parse ─► FileContext ─► [analysis] ─► [optimizers] ─┬─► interpreter  │──► CompiledScript
            │                                                          └─► compiler     │
            └───────────────────────────────────────────────────────────────────────────┘
                         org.babblelang.analysis (both modes)
          BlockScopes ─► Resolver (phase 2) ─► type checker (§10) ─► scopes, bindings, types
                                                              │
                         org.babblelang.compiler              ▼
                    CodeGenerator (ASM) ─► byte[] ─► ScriptClassLoader
                                                              │
                         org.babblelang.runtime               ▼
          Operators · Truth · Members · Callable · Arguments · CallSite · CompiledFunction · Cell
          (shared by the interpreter and by the generated code)
```

### 4.1 Packages

| Package | Contents |
|---|---|
| `org.babblelang.runtime` (new) | Everything generated code calls into: `Operators`, `Truth`, `Members`, `Callable`, `Calls`, `Arguments`, `CallSite`, `CompiledFunction`, `Cell`, `Undefined`, `ScriptRuntime`. It must not depend on ANTLR or the parse tree. |
| `org.babblelang.analysis` (new) | Front-end passes that both modes run: `BlockScopes` (§8.1), which grows into the `Resolver` (§7.1), and later the type checker (§10). |
| `org.babblelang.compiler` (new) | `BabbleCompiler` (facade), `CodeGenerator`, `ScriptClassLoader`, `CompiledBabbleScript`. |
| `org.babblelang.engine.impl` | The interpreter, `Namespace`/`Slot`/`BabbleObject`, and the natives, moved onto the runtime interfaces. |

### 4.2 Choosing the mode

`BabbleScriptEngine` gets a mode, `INTERPRET` (the default until parity is reached) or `COMPILE`.
It is taken from the system property `babble.mode` and can be overridden through
`engine.put("babble.mode", ...)` before `compile`. The factory stays a singleton. Both modes
return a `CompiledScript`, and nothing outside the engine knows which one it got.

## 5. Phase 0: a shared runtime

Generated code cannot call `Interpreter` and cannot hold `ParserRuleContext`s. Before any
bytecode exists, the following moves out of the interpreter:

### 5.1 Operators and truthiness

`Operators.add/sub/mul/div/lt/le/gt/ge/eq/ne(Object a, Object b, OpSite site)` and
`Truth.test(Object)`. `OpSite` is a small immutable record `(String leftText, String
rightText)`. It is only read when an operator fails, so that messages stay identical. The
interpreter builds `OpSite`s lazily from the context, and the compiler emits them as constants
(§6.6).

### 5.2 A new calling convention

```java
package org.babblelang.runtime;

public interface Callable {
    /**
     * @param args  argument values in call order
     * @param names parallel to args: the argument's name, or null if positional.
     *              Shared and immutable per call site; null when no argument is named.
     * @param site  static description of the call site, for error messages
     */
    Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site);
}
```

* The two phases become one. Arguments travel as an array plus a parallel names array, as in
  CPython's vectorcall, so most calls allocate nothing beyond the `Object[]`.
* `Arguments.bind(...)` is the single implementation of the binding rules in §2.4: name first,
  then position *i*, then default, else `Missing parameter`. Leftover arguments are rejected
  as before. The interpreter's `Parameters.bind` and the compiled prologues both use it, so the
  rules cannot drift apart.
* `CallSite` is an immutable record: `(String sourceName, int line, String calleeText,
  String[] argTexts)`. That is exactly what `assert` and `… is not callable` need from the
  parse tree today.
* `ScriptRuntime` is the per-eval context: the script root `Namespace`, the `ScriptContext`
  (its writer is the natural home for `print`'s output later), and the engine. It replaces
  natives digging through the caller's namespace (§8, D6).

`Function`, `BoundMethod`, `PrintFunction`, `AssertFunction`, `ImportFunction` and the
`natives.java` classes all implement the new `Callable`. The test-only `AssertFunction` moves
too. In the interpreter, `Function.call` becomes "enter the closure, bind via `Arguments`,
`interpreter.evaluateIn(ns, body)`, catch `ReturnException`". `evaluateIn` already exists for
this.

### 5.3 Member selection

`Members.select(Object target, String name, CallSite/OpSite site)` and `Members.assign(...)`
take over the `Scope` checks and the `Not a scope` / `Not an assignable scope` errors from
`visitSelector` and `visitAssignExpression`.

**Exit criterion:** the existing test suite passes unchanged. The only tests that may change
are the ones that construct `Callable`s by hand.

One consequence matters a lot: **interpreted and compiled functions can call each other.**
The compiler can then fall back to the interpreter for any function literal it does not
support yet, by emitting `new InterpretedFunction(ctx, ns)` for it. Phase 1 can land one
construct at a time and still run every test. While any fallback remains, the compiled
script keeps the parse tree and an `Interpreter` in its `ScriptRuntime`.

## 6. Phase 1: a compiler on top of the existing data model

### 6.1 Class layout

There is one class per compiled script: `org/babblelang/generated/Script$<n>`, final and
package-private, `V21` class file version, with `SourceFile` set to the script name
(`ScriptEngine.FILENAME` from the context, else `<input>`).

```java
final class Script$7 {
    // Constants: call sites, operator sites, parameter tables (§6.6)
    static final CallSite CS0 = ...;   // initialised in <clinit>

    // The file body. root = implicits.enter(null) with bindings already defined.
    static Object run(ScriptRuntime rt, Namespace root) { ... }

    // One static method per function literal, named after its def when there is one
    static Object fn$0$add(CompiledFunction self, Object thiz, ScriptRuntime rt,
                           Object[] args, String[] names, CallSite site) { ... }
}
```

The generated class has no instance state. Everything that varies per eval is in `rt` and
`root`, so a single loaded class serves every `eval()` of the `CompiledScript`.

### 6.2 Code generation scheme

`CodeGenerator` is a `BabbleBaseVisitor<Void>` that writes into an ASM `MethodVisitor`. Its
one invariant is that **every expression leaves exactly one `Object` on the operand stack.**
A statement in a sequence is followed by `POP`, except the last one in a block, the function
body or the file. In phase 1 the current namespace is always held in one JVM local, `ns`.

| Construct | Phase 1 emission |
|---|---|
| `1`, `1.5`, `"s"`, `true`, `null` | `Integer.valueOf`/`Double.valueOf` of an `LDC`, `LDC` string (unescaped at compile time), `GETSTATIC Boolean.TRUE`, `ACONST_NULL` |
| `x` | `ns.get("x").get()` |
| `x = e` | `ns.get("x").set(e)`, keeping a copy of `e`'s value on the stack as the result |
| `def x = e` / `def x` | `ns.define("x", false).set(e)` (or `null`), keeping the value as the result |
| `e.x` | `Members.select(e, "x", site)` |
| `e.x = v` | `Members.assign(e, "x", v, site)`, which returns `v` |
| `a op b` | `a`, `b`, `GETSTATIC opSite`, `INVOKESTATIC Operators.op` |
| `a and b`, `a or b`, `not a` | `Truth.test` plus conditional jumps. Short-circuits and produces `Boolean.TRUE/FALSE`. |
| `if t then A else B` | `Truth.test(t)`, then `IFEQ` to the else branch. A missing `else` pushes `null`. |
| `while t then B` | `ACONST_NULL` as the initial result, then a loop: test, `IFEQ` exit, `POP`, body, `GOTO` the head |
| `( e1 ; e2 ; … )` | each expression, with a `POP` between them. `()` pushes `null`. A block with `def`s of its own first switches `ns` to `ns.enter(null)` with those names pre-declared (§8.1). |
| `package p ( … )` | `ns2 = ns.enter("p")`, compile the body with `ns := ns2` (a fresh JVM local), `POP`, `ACONST_NULL` |
| `object ( … )` | `obj = new BabbleObject(ns)`, compile the body with `ns := obj`, `POP`, push `obj` |
| `(params) -> body` | `INVOKEDYNAMIC` with `LambdaMetafactory` to make a `FunctionBody` from `fn$N`, then `new CompiledFunction(body, ns, paramTable)` |
| `f(args)` | `f`, then a check that it is callable, then `rt`, the `Object[]` of the arguments, `GETSTATIC names`, `GETSTATIC site`, `INVOKEINTERFACE Callable.call` |
| `recurse` | `ns.get("$recurse").get()` |
| `return e` | `e`, `ARETURN` (§6.4) |

Because `package` and `object` bodies are not functions, they are compiled **inline** in the
enclosing method, with the namespace local switched. That keeps `return` inside them a plain
`ARETURN` from the enclosing function, which matches the interpreter.

The "is it callable" check is an `INVOKESTATIC Calls.callable(Object, CallSite)`. It must run
**before** the arguments are evaluated, because the interpreter reports `… is not callable`
before evaluating any of them.

### 6.3 Function prologue

The literal `(a, b = a + 1) -> body` compiles to:

```java
static Object fn$3(CompiledFunction self, Object thiz, ScriptRuntime rt,
                   Object[] args, String[] names, CallSite site) {
    Namespace ns = self.closure().enter(null);
    ns.define("$recurse", true).set(self.recurseTarget(thiz));   // BoundMethod when called bound
    if (thiz != Undefined.UNBOUND) ns.define("this", true).set(thiz);
    Arguments.Binder b = Arguments.binder(self.params(), args, names);  // static table: ["a","b"]
    Object a = b.take(0);                       // by name, else by position, else MISSING
    if (a == Arguments.MISSING) throw Arguments.missing("a");
    ns.define("a", false).set(a);
    Object bv = b.take(1);
    if (bv == Arguments.MISSING) { bv = <compiled: a + 1 evaluated with ns>; }   // default, inline
    ns.define("b", false).set(bv);
    b.rejectLeftovers();
    return <compiled body with ns>;
}
```

`BoundMethod` stays a runtime class. Its `call` invokes the function's body with
`thiz = receiver` and makes itself the `recurse` target. That reproduces `5dcc5a9` ("Keep
`recurse` bound to the receiver inside a method").

### 6.4 `return` without exceptions

The body of a function is a single JVM method, and the `package`/`object` bodies inside it are
inlined, so every `return` the compiler sees is lexically inside exactly one method. It
compiles to `ARETURN`, and `ReturnException` is not used in compiled code.

The JVM needs a well-formed operand stack at every instruction. `return` can sit in the middle
of an expression, as in `f(1, return 2)`. Then `ARETURN` runs with other values still on the
stack below it. That is legal, since the JVM throws the rest of the frame's stack away on
return. After the `ARETURN`, the generator emits a dead `ACONST_NULL` so that the code around
it can be generated as if `return` were an ordinary expression. ASM's `COMPUTE_FRAMES` replaces
unreachable code with `NOP`s and an `ATHROW`, so the dead code costs nothing.

Two cases are handled before code generation (§8):

* `return` with no enclosing function. **Decided (D2):** it is a compile-time error in both
  modes, reported by the analysis pass. The code generator never sees one.
* `return` inside a parameter default. In the interpreter it escapes `bindParameters`, which is
  outside `Function.call`'s `catch`, so it returns **from the caller's** function. Compiled
  inline, it would return from the callee. The proposal is a compile-time error.

### 6.5 Loading classes and LambdaMetafactory

* `ScriptClassLoader extends ClassLoader`. Its parent is the engine's class loader, and there is
  one per `compile()`. The class and its loader can be garbage collected once the
  `CompiledScript` is unreachable. Loading goes through `defineClass(name, bytes, 0, len)`.
* Function bodies get their functional interface (`FunctionBody`) from `INVOKEDYNAMIC` with
  `LambdaMetafactory.metafactory`, the same scheme javac uses for lambdas. The JVM hands the
  generated class's own full-privilege `Lookup` to the bootstrap, so this works from a custom
  loader without extra plumbing. It produces one tiny hidden class per literal, and no
  reflection happens on the call path.
* Hidden classes (`Lookup.defineHiddenClass`) were considered as the script class itself. A
  named class in a dedicated loader is easier to dump, to `javap`, and to read in stack traces,
  which counts most for G3/G4. We can revisit if class-loader churn ever shows up in profiles.
* `ClassWriter.COMPUTE_FRAMES` calls `getCommonSuperClass`, which **loads classes** through
  ASM's own loader. The generator overrides it to answer from what it knows (runtime types and
  `java/lang/Object`), so ASM never tries to load the class it is still writing.

### 6.6 Constants

The static metadata of each call site, operator site and parameter table (a `CallSite`
record, an `OpSite`, a `String[]` of names, the argument source texts) is built once in
`<clinit>` and stored in `static final` fields. A large script can have many sites, and the
JVM limits a method to 64 KB, so `<clinit>` is split into helpers of a bounded size.
`ConstantDynamic` (condy) would make each site lazy, at the cost of a bootstrap per constant.
It is a reasonable later refinement, but it is not needed.

### 6.7 Line numbers and errors

* The generator calls `visitLineNumber(ctx.getStart().getLine(), label)` before each
  expression that starts a new line, so every frame in a stack trace carries Babble line
  numbers.
* `CompiledBabbleScript.eval` catches exceptions as before. It gets the line from **the first
  stack-trace element whose class is the script class**, not from a `last` field. Runtime
  helpers in `org.babblelang.runtime` are skipped, so a failure inside `Operators.add`
  reports the line of the `+`.
* Messages come from the same runtime code as in the interpreter (§5), so the
  `ScriptException` texts that tests check stay identical.
* Babble calls cost one or two JVM frames each in compiled code, against roughly ten visitor
  frames in the interpreter. Deep Babble recursion therefore reaches much further before a
  `StackOverflowError`. That is a welcome divergence, and no test should depend on it.

### 6.8 Method size

The JVM caps a method at 64 KB of bytecode. A function body that large is unlikely, but a
generated top-level script can get there. If `run` would exceed a threshold, the generator
splits the top-level statement list into `run$0`, `run$1`, …. In phase 1 that works without
any extra machinery, because all state sits in `root`, which is passed to each part.

### 6.9 Exit criteria

* Every test in `src/test/java` and every `.ba` script passes with `-Dbabble.mode=compile`,
  apart from the accepted divergences in §8. Those get explicit mode-specific tests.
* Every generated class passes ASM's `CheckClassAdapter` in tests.
* No interpreter fallback is left for any construct in the grammar.

## 7. Phase 2: static scope resolution

Phase 1 still does a `HashMap` lookup and a parent-chain walk for every variable access. Phase 2
resolves names at compile time.

### 7.1 Resolver

`Resolver` is a visitor that runs before code generation. It lives in `org.babblelang.analysis`
because the type checker uses it too (§10.1). It builds a tree of lexical scopes
(file, package, object, function) and records a `Binding` for each identifier occurrence in a
`ParseTreeProperty<Binding>`:

| Binding kind | When | Generated code |
|---|---|---|
| `LOCAL(i)` | a parameter or `def` of the enclosing function that no nested function or object literal captures | `ALOAD i` / `ASTORE i` |
| `CELL(i)` | a parameter or `def` of a function that a nested function literal captures, or that a nested object literal can reach (§7.3) | `Cell` in JVM local `i`; the closure gets the `Cell` |
| `CAPTURED(k)` | a reference to an enclosing function's variable from inside a closure | `self.captured(k)` then `Cell.value` |
| `MEMBER(scope, "x")` | a `def` in a package, object or file body | `Slot` of that runtime namespace, looked up once per activation and cached in a local |
| `GLOBAL("x")` | a name not declared lexically anywhere | `root.get("x")`: implicits plus bindings, dynamic, because bindings are only known at `eval` |
| `THIS` / `RECURSE` | the `this` / `recurse` keywords | §7.4 |

Every block is a scope (§8.1). The resolver starts from the per-scope `def` sets that the
block-scope pass already computes. A function's parameters and the top level of its body share
one scope.

### 7.2 Block-local variables

By phase 2, block scope (§8.1) already behaves the same in both modes. Phase 2 only changes
how it is represented:

* A block's variables are JVM locals that are live only inside the block. Their slots can be
  reused once the block ends.
* At block entry each local is set to `Undefined.UNDEFINED`. A read or assignment while it is
  still undefined throws `Name used before definition : x`. That check is a reference
  comparison and a branch, and the JIT usually removes it. A second `def` of the same name in a
  scope cannot happen, because it is rejected before the program runs.
* A captured block variable gets its `Cell` at block entry. A `while` body therefore gets a
  fresh cell on every iteration, and closures created in different iterations do not share
  the variable.

Scopes that are not functions or blocks (file, package, object) keep their dynamic `Namespace`
and `Slot`s. External code reads them through `pkg.x` and `obj.x`, the engine injects bindings
into the root, and top-level forward references (`Forward.ba`) must keep working. The resolver
still knows their `def` sets statically, so for those it only skips the chain walk.

### 7.3 Object literals that reach into function scope

**This subsection depends on the object model design (§8.2).** It is only needed if `obj.x`
keeps falling through to the creating function's variables. If objects only see their own
members, it goes away.

`Stack.ba` depends on `obj.name` falling through to the locals of the function that created the
object (§2.5). Once those locals are JVM locals, a `BabbleObject` has no parent namespace to
walk. The compiler bridges the gap:

* If a function body contains an `object` literal (directly or through nested blocks), the
  resolver turns **every variable visible at that point** into a `CELL`. That means the
  function's own variables and any captured ones.
* At the literal, the generator builds a `FrameScope(String[] names, Cell[] cells, Scope
  parent)` and makes it the `BabbleObject`'s parent. `FrameScope` implements `Scope<Object>`,
  so `get` hands back a `Slot` view of a cell. `BabbleObject.get` keeps working as it does
  today: lookup through the parent chain, wrapping found `Function`s in `BoundMethod`. Assigning
  through it updates the cell, so `x.inc()` followed by `x.n` sees the new value.
* Functions without object literals pay nothing.

The alternative is to drop the fall-through and require methods to be members of the object.
That breaks `Stack.ba` and the README's idiom, and it is one of the questions the object model
design has to answer (§8.2).

### 7.4 `this` and `recurse`

* `recurse` becomes the `self` argument (or `self.recurseTarget(thiz)` for bound calls), with no
  lookup.
* Whether a function runs as a method is only known at call time, and `this` falls back
  **lexically** (§2.5). So every function has an implicit `this` local, initialised from `thiz`.
  If `thiz` is `UNBOUND`, the local takes the *enclosing* function's `this`, captured like any
  other variable. If there is no enclosing function, it becomes `GLOBAL("this")`, which
  normally fails with `No such name : this`, as today. A function that never mentions `this`
  (and contains no nested function that does) skips all of this.

### 7.5 Closures

`CompiledFunction` holds `FunctionBody body`, `Object[] captured` (cells, or member
namespaces/slots), the parameter table and a display name. Phase 2 drops the `Namespace
closure` field, except for functions whose enclosing scope really is a namespace (file,
package, object bodies). Those capture that namespace as one entry of `captured`.

One optimisation is optional: when a captured variable is never assigned after its `def`, the
closure can capture the **value** instead of a `Cell`. With block scope, a `def` inside a loop
body creates a new variable on every iteration, so that case needs no special handling.

### 7.6 Exit criteria

* Phase 2 starts only once the object model design (§8.2) is settled.
* Both modes pass the full suite.
* On the benchmarks in §11, phase 2 is clearly ahead of phase 1. The numbers go in the PR.

## 8. Semantic decisions and divergences

Each item gets a test that pins the agreed behaviour **in both modes**.

| # | Topic | Today | Decision or recommendation |
|---|---|---|---|
| D1 | What a name refers to before its `def` runs | resolves to an outer binding | **Decided: real block scope with hoisting (§8.1).** Reading a name before its `def` is an error. |
| D2 | `return` with no enclosing function: at file level, or in a top-level `package`/`object` body | runtime error, only if it runs | **Decided: compile-time error (§8.3).** |
| D3 | `return` in a parameter default | returns from the *caller* | compile-time error |
| D4 | `def` inside a loop body | fails on the second iteration | **Decided: fixed by D1.** Each iteration gets a fresh scope. |
| D5 | `obj.x` falling through to lexical scope (`o.println`, `mk().hidden`) | allowed | **Deferred to the object model design (§8.2).** Phase 1 keeps today's behaviour. |
| D6 | `print` finds `STDOUT` through the **caller's** scope | dynamic scoping | Read `STDOUT` from the script root via `ScriptRuntime`. A script that does `def STDOUT` *inside a function* to redirect output stops doing so. Nothing in the repo does this. |
| D7 | An unqualified name inside an object body after `obj.m` has cached a `BoundMethod` in the object | can resolve to the bound method | In compiled code the name resolves lexically to the raw function. This only matters for code that both calls `obj.m` from outside and refers to plain `m` from inside, and it is not worth replicating. May be overtaken by §8.2. |

D3 is still open. D6 and D7 are edge cases written down for completeness.

### 8.1 Block scope

This is a language change, made in the interpreter after phase 0 and before phase 1. Its rules:

1. **Which blocks are scopes.** A block expression, an `if` branch and a `while` body each open
   a new scope, a child of the enclosing one. Function, package and object bodies do not add a
   second scope: the top level of the body *is* the function's scope (shared with its
   parameters), the package's namespace or the object. The top level of the file is the script
   root.
2. **Where a `def` belongs.** A `def` belongs to the nearest enclosing scope. That includes a
   `def` nested inside an expression (`f(def x = 1)`), but not one inside a nested block or
   function literal.
3. **Hoisting.** From the moment a scope is entered, every name `def`'d in it is declared but
   undefined, and it shadows any outer name of the same name throughout the scope. Reading or
   assigning it before its `def` has run throws `Name used before definition : x`.
4. **No redefinition.** Two `def`s of the same name in one scope are rejected before the
   program runs, with the existing message `Name already defined : x`. That includes
   redefining a parameter at the top level of a function body, which is rejected today too.
   A `def` inside a `while` *test* is also rejected: it belongs to the enclosing scope but
   would run on every iteration.
5. **Loops.** A `while` body is a fresh scope on each iteration. Closures created in different
   iterations capture different variables.
6. **Assignment** `x = v` still assigns the nearest declared `x`.

```
def a = 1
def f = () -> (
    def b = a          ;; error: a used before definition (the local a is hoisted)
    def a = 2
)

def i = 0
while i < 3 then (
    def x = i * 2      ;; fine: a new x on every iteration
    i = i + 1
)
x                      ;; error: No such name : x

if i > 0 then ( def y = 1 )
y                      ;; error: No such name : y
```

**Effect on existing code.** I checked: no current test or `.ba` script relies on a `def`
escaping its block or on reading a name before its `def`. The `@Disabled` test
`BabbleScopesTestCase.testBlockScope` already describes this behaviour. It is enabled, with its
expected exceptions updated to the current `ScriptException`/`BabbleException` messages.

**Interpreter implementation.**

* A `BlockScopes` pass computes the `def` set of every scope once, at `compile()` time, and
  stores it in a `ParseTreeProperty`. It also reports the errors of rule 4, so they surface
  before anything runs in both modes. This pass is the seed of the phase 2 `Resolver`, and it
  lives in `org.babblelang.analysis`.
* On entering a block that has `def`s of its own, the interpreter creates a child namespace
  and pre-declares those names as unset slots. A block with no `def`s behaves identically
  without a namespace of its own, so it does not get one, which is the common case for `if`
  and `while` bodies. Function, package and object bodies pre-declare their names into their
  own namespace, and the root pre-declares the top-level `def`s.
* `Slot` already tells "declared" apart from "set". Reading an unset slot changes from
  `IllegalStateException: Slot not set` to a `BabbleException` with the new message.
* The namespace is restored in a `finally`, as `visitPackageExpression` already does, so a
  `return` or an error inside a block cannot leave the interpreter in the wrong scope.

**At the top level**, pre-declaring means a script's `def` of a name that is also an
`ENGINE_SCOPE` binding fails at the start rather than when the `def` runs. It fails either way
today. A top-level name read before its `def` no longer falls back to an implicit of the same
name (`println(1) ; def println = …` is now an error).

### 8.2 Object model, designed before phase 2

The object model gets its own design (`docs/design/object-model.md`), and it covers the
README's planned classes, methods and inheritance. Phase 0, block scope and phase 1 do not
depend on it: phase 1 compiles today's object semantics, which costs nothing because phase 1 is
backed by namespaces. Phase 2 (§7.3, §7.4) and the object and package types of the type checker
(T5) wait for it.

The compiler needs these questions answered:

1. **Lookup.** Does `obj.x` see only the object's members (own and inherited), or does it still
   fall through to the creating scope (D5)? With members only, §7.3 disappears.
2. **`this`.** Is `this` bound only by a member call (`obj.m()`), or does it also fall back
   lexically as today (§7.4)? Does `def m = obj.m` stay bound?
3. **Representation.** Do classes compile to JVM classes with fields and virtual methods, or do
   they stay slot maps? Member sets are already static today: only `def` adds a member, and
   `obj.x = v` on an undeclared `x` fails. So fixed layouts look feasible, and they are the
   largest performance lever for object-heavy code.
4. **Classes or prototypes,** inheritance and `super`, and what `recurse` means inside a method.
5. **Types.** Are object types nominal or structural (T5)? Does an object literal become an
   instance of an anonymous class?
6. **Migration.** What do `Stack.ba` and the README's "function scope as a class" idiom become?

### 8.3 `return` outside a function

A `return` is accepted only if a function literal encloses it. `package` and `object` bodies do
not count, because they are not functions: a `return` inside one belongs to whatever function
encloses *that*. Anything else is rejected before the script runs, in both modes:

```
return 1                         ;; rejected
if false then ( return 1 )       ;; rejected too, though it never runs
package p ( return 1 )           ;; rejected
def f = () -> ( return 1 )       ;; fine
def g = () -> ( object ( def v = ( return 2 ) ) )   ;; fine: returns from g
```

* The check lives in the `BlockScopes` pass (§8.1), which already walks every scope, so it runs
  at `compile()` time in both modes. The interpreter's runtime check in `visitFile` stays only
  as a defensive assertion, because no `return` can reach it any more.
* All errors found before the script runs, from this check and from rule 4 of §8.1, are raised
  as `ScriptException(message, fileName, line)`. They read exactly like runtime errors
  (`… in <input> at line number 1`). The existing `testReturnOutsideOfFunction` therefore passes
  unchanged. It gains a case for a `return` that never runs (`if false then ( return 1 )`),
  which is accepted today and rejected from now on.
* Because the code generator never meets such a `return`, `run` needs no special case: every
  `ARETURN` it emits for a `return` is inside a function method.

## 9. Phases 3 and 4 (outline)

**Phase 3: invokedynamic and specialisation.** Each of these needs its own design note when we
get there:

* **Calls.** `INVOKEDYNAMIC call(Object callee, Object… args)`, bootstrapped by
  `org.babblelang.runtime.Bootstrap`, with a monomorphic inline cache. If the callee is a
  `CompiledFunction` with the same `body` as last time, and the call is positional and complete,
  the site jumps straight to the body's `MethodHandle` with the arguments spread. That skips
  `Arguments` binding and the `Object[]`.
* **Java interop.** Cache `Overloads` resolution per (receiver class, argument classes) at each
  site. Today every call to a Java method re-runs reflection over all overloads. That is
  probably the single largest cost in interop-heavy scripts.
* **Member selection.** Cache by receiver kind. For `BabbleObject` it is a slot lookup, and for
  `JavaPackage`/`JavaClass` the result is constant, so it can be bound once.
* **Arithmetic.** Guard on `(Integer, Integer)` and inline `Math`-style wrapping `int`
  arithmetic, falling back to `Operators`. Where the type checker has established an enforced
  type (§10.5), no guard is needed. Unenforced annotations can only bias the initial guard.

**Phase 4: ahead-of-time compilation (optional).** A `babblec` Maven goal or CLI writes the
script class plus a `main` that builds the implicits and calls `run`. Hidden runtime state
(class loader, `ScriptRuntime`) is already explicit, so this is mostly packaging. The jar
would depend on the Babble runtime and on ANTLR only if interpreter fallback is still used.

## 10. Type checking

The README lists "type checking" and "type inference" as planned features. Today the
annotations (`a:int`, `(n:int):string`) are parsed and then ignored: nothing in the interpreter
reads them. This section covers where a type checker fits relative to the compiler phases, and
what has to be decided before one can be built. The type system itself needs its own design
document. The list in §10.4 is the agenda for it, not its content.

### 10.1 Where it sits

The type checker is a **front-end pass**. It runs on the parse tree after parsing, before
either backend, and in both modes. If it ran only before code generation, whether a program
is accepted would depend on `babble.mode`, which goes against G1.

* It needs to know which declaration each identifier refers to. That is exactly what the
  phase 2 `Resolver` (§7.1) computes, so the checker consumes `ParseTreeProperty<Binding>`
  rather than repeating the analysis. The `Resolver` therefore lives in a shared
  `org.babblelang.analysis` package, not in the compiler.
* It records a `Type` per expression in a `ParseTreeProperty<Type>`, which code generation and
  the optimizers can read later (§10.5).
* It runs **before** the optimizers. They rewrite the tree, and diagnostics should point at what
  the user wrote.

### 10.2 Ordering relative to the compiler phases

**Not before phases 0 and 1.** Those phases reproduce the interpreter's dynamic behaviour, and
their only success criterion is that the same tests pass in both modes. Type information gives
them nothing. A checker also changes which programs the language accepts, and doing that while
parity is being established makes every failure ambiguous: is it the compiler, or the new rules?

**Not before block scope and the `Resolver`.** Block scope (§8.1) fixes what each name refers
to, and the `Resolver` computes it. Object and package types (T5) also wait for the object model
(§8.2).

**Before phase 3.** Phase 3 adds guarded call sites to cope with not knowing types: `int`
arithmetic with fallbacks, overload caches keyed on runtime classes. Where types are known and
trustworthy (§10.3), the compiler can emit direct code instead, so the mix of the two strategies
should be designed knowing what types will provide.

So the order is: phase 0, block scope, phase 1, then the `Resolver`, then **the type checker alongside
phase 2 code generation**, then the runtime checks (T9), then phase 3. The type system
design (§10.4) depends on none of this, apart from T5, and can start now.

### 10.3 Rollout: gradual, warnings first

* **Gradual typing.** Anything without an annotation has the type `dynamic`, which is compatible
  in both directions with every other type. Programs without annotations are therefore accepted
  as before, and diagnostics come from code that contradicts its own annotations. The exception
  to watch is inference (T8). A type inferred from an initialiser must not be stricter than what
  runs today: an inferred object type that leaves out the D5 fall-through members would reject
  `o.println`, for instance. The in-repo scripts are the test for this.
* **A switch, `babble.typecheck=off|warn|error`.** The default starts at `off`. The test suite
  runs with `warn`, and both modes report the same diagnostics. `error` becomes the default once
  the type system has settled and the in-repo scripts check cleanly.
* **Diagnostics** are collected with their source line and column. In `error` mode they are
  raised as a `ScriptException` from `compile()`, before anything runs. In `warn` mode they go
  to the `ScriptContext`'s error writer.
* **Enforcement (T9, decided: checked at boundaries).** A checker alone cannot make types
  trustworthy, because `dynamic` values flow into annotated code. So a runtime check runs
  wherever a `dynamic` value meets a declared type: arguments of annotated parameters, results
  of annotated functions, writes to annotated variables and members. The check lives in the
  shared runtime (`Types.check(value, type, site)`), so both modes fail the same way, with
  `Type error : expected int, got String` and the line of the boundary. The checks are active in
  `error` mode. In `off` and `warn` modes annotations have no effect at runtime, and phase 3
  only relies on types when the script was compiled in `error` mode.

### 10.4 Type system questions to settle first

| # | Question | Why it matters | Starting point |
|---|---|---|---|
| T1 | Type names | `Test1.ba` uses `int` and `string`, which nothing defines | Built-ins `int`, `double`, `bool`, `string` mapped to their boxed runtime classes. Dotted names (`java.util.List`) resolve to Java classes. |
| T2 | `null` | `def head = null` is everywhere, and so are `if` without `else` and `def x` without a value | `int`, `double` and `bool` reject `null`, which is what allows unboxing in phase 3. Every other type is nullable, as the runtime already is. A `T?` syntax can come later. |
| T3 | Operator typing | §2.2 is the specification | `int op int` is `int` (except `/`, which is `double`). Any mix with `double` is `double`. `string + any` is `string`. Comparisons and `and`/`or`/`not` are `bool`. `if`/`while` tests accept any type, because truthiness is defined for all values. |
| T4 | Function types | Calls bind by name and by position, and parameters can have defaults | Parameter names are part of the type, and each parameter is marked optional or not. The grammar's `functionType` reuses `parametersDeclaration`, which allows default *expressions* in a type. That needs tightening. |
| T5 | Object and package types | There is no syntax for them, and D5 lets `obj.x` reach the enclosing scope | **Waits for the object model (§8.2).** Until then, objects and packages are `dynamic`. |
| T6 | `this` | Whether a function runs as a method is only known at call time (§7.4) | `dynamic`. |
| T7 | Java interop | Reflection gives static types, but generics are erased | Constructor and method result types come from reflection, and erased type parameters become `dynamic`. With the argument types known, overload resolution can happen at check time (§10.5). |
| T8 | Inference for unannotated `def`s | If `def f = (a:int):int -> …` leaves `f` as `dynamic`, calls to `f` are never checked and the checker is close to useless | An unannotated `def` takes its initialiser's type when the `Resolver` sees no other assignment to it. Members that can be written through `obj.x = …` stay `dynamic` unless annotated. Fuller inference is the README's separate "type inference" item. |
| T9 | Enforcement | Decides whether phase 3 can trust types (§10.3) | **Decided: runtime checks at boundaries, in `error` mode, in both modes.** |

### 10.5 What the compiler gains

Phases 1 and 2 use no type information. In phase 3, for scripts compiled in `error` mode, where
types are enforced (T9):

* `int`, `double` and `bool` locals and parameters become unboxed JVM locals. Arithmetic
  compiles to `IADD`/`IMUL`/…, which wraps around exactly like today's `Integer` arithmetic,
  and comparisons compile to `IF_ICMPxx`/`DCMPx`.
* A typed function gets a specialised static entry point with a primitive signature, next to
  the generic boxed entry. The boxed entry checks and unboxes its arguments, then calls the
  specialised one. A call site whose callee is statically known (a `def` that the `Resolver`
  sees is never reassigned) calls the specialised entry directly.
* A Java call whose receiver and argument types are known is resolved once, at compile time,
  and emitted as `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE`, with no reflection at all.
* Code typed `dynamic` keeps the guarded `invokedynamic` sites from §9.
* `SimpleBinaryOpsOptimizer` folds `x * 0` to `0` without checking types, which is wrong when
  `x` is a `double` (`0.0`) or a `string` (an error at runtime). With types available, it can
  restrict those rewrites to `int`.

### 10.6 Testing

* Golden tests in the style of the `Resolver` ones: a snippet, and the diagnostics expected at
  each line and column.
* Every `.ba` script in `src/test/babble` must check cleanly in `error` mode, using the
  annotations it already has. Gradual typing makes that the baseline, and it also catches
  annotations that are wrong today but go unnoticed because nothing reads them.
* With T9 in place, both modes must fail the same way when a `dynamic` value reaches an
  annotated parameter with the wrong type. This joins the differential tests in §11.

## 11. Testing, debugging and benchmarking

* **Dual-mode suite.** `BabbleTestBase` reads `babble.mode`. Surefire gets a second execution,
  `compiled-tests`, that runs the same classes with `-Dbabble.mode=compile`, so `mvn package` runs
  both. While phase 1 is in progress, the compiled execution may exclude tests through a short,
  shrinking list in the POM.
* **Differential tests.** A parameterised test runs every `src/test/babble/*.ba` in both modes
  and compares the result value, the captured stdout, and the exception class and message.
* **Bytecode checks.** Each class compiled in a test passes through `CheckClassAdapter.verify`.
  `-Dbabble.dumpClasses=target/babble-classes` writes the `.class` files for `javap -c -p`.
  A `-Dbabble.trace=true` switch prints an ASM `Textifier` listing.
* **Compiler unit tests.** Golden-file tests for the `Resolver`: a source snippet and the
  expected binding kinds, which are easy to review. Codegen tests that pin the shape of tricky
  constructs: `return` mid-expression, `while` result, short-circuit booleans.
* **Benchmarks.** A small JMH module, or failing that a plain warm-up and timing harness, run in
  both modes: `fib(27)` (calls and arithmetic), `fac2` loops (locals and `while`), the
  `Counter.ba` closure (cells), the `Stack.ba` push/pop loop (objects and methods), and an
  `ArrayList` add/get loop (Java interop). Each phase's PR includes the before/after table.
  This document does not predict numbers.

## 12. Alternatives considered

* **`java.lang.classfile` (JEP 484) instead of ASM.** It ships with the JDK (final in 24), needs
  no dependency and always supports the newest class file version. But the build targets
  `release 21`, and CI runs JDK 21. Switching means moving the baseline to JDK 25 (LTS). That
  is reasonable, but it should be decided on its own and not slipped in with the compiler.
  ASM (`org.ow2.asm:asm`, plus `asm-util` for tests; 9.11 is current) is the conservative choice and
  is what the README promises. The generator keeps ASM behind a few emit helpers, so that a
  later port stays mechanical.
* **Generating Java source and compiling it with `javax.tools`.** Easy to read, but it needs a
  full JDK at runtime and compiles slowly. It also fits an expression language badly
  (`while` and `if` as values, `return` mid-expression), and Babble line numbers get lost.
* **Closure compilation** (turning the parse tree into a tree of Java lambdas). It gives most
  of phase 1's speed-up for less work. It would be the right call if speed were the only goal,
  but the project's stated aim is learning to compile to bytecode.
* **Truffle/GraalVM.** Partial evaluation of a self-specialising AST interpreter would probably
  beat everything here, but it is a heavyweight framework that sidesteps the learning goal.
  It is noted for completeness.

## 13. Risks

| Risk | Mitigation |
|---|---|
| Stack-map frames break on unusual expression nesting (`return`/`while` inside arguments) | `COMPUTE_FRAMES`, `CheckClassAdapter` in every test, codegen tests for those shapes |
| Semantics drift between the two modes | One shared runtime (phase 0), the dual-mode suite and differential `.ba` tests |
| Block scope breaks user scripts | Only in-repo scripts exist today, and none relies on a `def` escaping its block (§8.1). It lands in the interpreter before the compiler starts. |
| The object model design stalls phase 2 | Phases 0 and 1 and block scope do not depend on it, and they already remove the tree walking |
| `getCommonSuperClass` loading classes during generation | Override it (§6.5) |
| Method or constant-pool size limits on large scripts | Split `run` and `<clinit>` (§6.6, §6.8) |
| Class-loader leaks in long-lived engines | A loader per compile, no static caches that point at generated classes from runtime code |
| The type checker rejects programs that run today | Gradual typing (unannotated is `dynamic`), `warn` before `error`, and every in-repo script must check cleanly (§10.3) |

## 14. Appendix: worked example

Babble (`Counter.ba`):

```
def counter = () -> (
    def c = 0
    () -> (
        c = c + 1
    )
)
def c = counter()
assert(c() == 1)
```

**Phase 1** (Java-equivalent of the generated class):

```java
final class Script$1 {
    static final OpSite OP0 = new OpSite("c", "1");
    static final OpSite OP1 = new OpSite("c()", "1");
    static final CallSite CS0 = new CallSite("Counter.ba", 7, "counter", new String[0]);
    static final CallSite CS1 = new CallSite("Counter.ba", 8, "assert", new String[] {"c()==1"});
    static final CallSite CS2 = new CallSite("Counter.ba", 8, "c", new String[0]);
    // ...

    static Object run(ScriptRuntime rt, Namespace ns) {
        Object v = new CompiledFunction(Script$1::fn$0$counter, ns, PARAMS_NONE, "counter");
        ns.define("counter", false).set(v);
        v = Calls.callable(ns.get("counter").get(), CS0).call(rt, new Object[0], null, CS0);
        ns.define("c", false).set(v);
        Callable f = Calls.callable(ns.get("assert").get(), CS1);
        Object c1 = Calls.callable(ns.get("c").get(), CS2).call(rt, new Object[0], null, CS2);
        return f.call(rt, new Object[] { Operators.eq(c1, Integer.valueOf(1), OP1) }, null, CS1);
    }

    static Object fn$0$counter(CompiledFunction self, Object thiz, ScriptRuntime rt,
                               Object[] args, String[] names, CallSite site) {
        Namespace ns = self.closure().enter(null);
        ns.define("$recurse", true).set(self.recurseTarget(thiz));
        Arguments.binder(self.params(), args, names).rejectLeftovers();
        Object v = Integer.valueOf(0);
        ns.define("c", false).set(v);
        return new CompiledFunction(Script$1::fn$1, ns, PARAMS_NONE, null);
    }

    static Object fn$1(CompiledFunction self, Object thiz, ScriptRuntime rt,
                       Object[] args, String[] names, CallSite site) {
        Namespace ns = self.closure().enter(null);
        ns.define("$recurse", true).set(self.recurseTarget(thiz));
        Arguments.binder(self.params(), args, names).rejectLeftovers();
        Object v = Operators.add(ns.get("c").get(), Integer.valueOf(1), OP0);
        ns.get("c").set(v);
        return v;
    }
}
```

**Phase 2.** `c` inside `counter` is captured and assigned by the inner literal, so it becomes a
`CELL`. Neither function mentions `this` or `recurse`, so neither sets them up:

```java
    static Object fn$0$counter(CompiledFunction self, Object thiz, ScriptRuntime rt,
                               Object[] args, String[] names, CallSite site) {
        Arguments.checkNone(args, site);
        Cell c = new Cell(Undefined.UNDEFINED);        // allocated at scope entry (§7.2)
        c.value = Integer.valueOf(0);                  // the def; redefinition is rejected statically
        return new CompiledFunction(Script$1::fn$1, new Object[] { c }, PARAMS_NONE, null);
    }

    static Object fn$1(CompiledFunction self, Object thiz, ScriptRuntime rt,
                       Object[] args, String[] names, CallSite site) {
        Arguments.checkNone(args, site);
        Cell c = (Cell) self.captured(0);
        Object v = Operators.add(c.read("c"), Integer.valueOf(1), OP0);   // read() checks UNDEFINED
        c.value = v;
        return v;
    }
```

Top-level `counter`, `c` and the global `assert` stay namespace accesses, which keeps the
behaviour for bindings and forward references.

## 15. Proposed sequence of changes

1. Phase 0a: `org.babblelang.runtime` with `Operators`, `Truth`, `Members`. The interpreter
   delegates to them.
2. Phase 0b: the new `Callable`, `Arguments`, `CallSite`, `ScriptRuntime`. Port the natives,
   the interop and the test `AssertFunction`. Decide D6.
3. Block scope in the interpreter (§8.1): the `BlockScopes` pass, pre-declared slots, the new
   error message. Enable `testBlockScope`. The same pass rejects a `return` outside a function
   (D2, §8.3).
4. Add ASM. Add `BabbleCompiler`, `ScriptClassLoader` and the mode switch. Compile literals,
   operators, `def`, names, blocks (as scopes), `if` and `while`, with interpreter fallback for
   function literals. Add the dual-mode surefire execution.
5. Compile function literals and calls (prologue, defaults, `return`). Decide D3. Remove
   the fallback.
6. Compile `package`, `object` and member access with today's object semantics. Phase 1 is
   complete: the full suite passes in both modes.
7. Object model design (§8.2), then its implementation in the interpreter. This can run in
   parallel with steps 4–6.
8. Phase 2: grow `BlockScopes` into the `Resolver`, then `LOCAL`/`CELL`/`CAPTURED`, then
   objects as §8.2 decides, then `this`.
9. In parallel with step 8, once the `Resolver` exists: the type checker, in `warn` mode only.
   The type system design (T1–T8) can start at any time before that, apart from T5.
10. Runtime boundary checks (T9) in both modes, and the `error` mode.
11. Benchmarks, then phase 3 design notes, using enforced types where §10.5 allows.
