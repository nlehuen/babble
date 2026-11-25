# BIP001: compiling Babble to JVM bytecode

| | |
|---|---|
| **BIP** | 001 |
| **Status** | Draft (implemented through `RM001`; JVM interfaces in §17 remain for `RM003`). Decided: block scope (§8.1), a `return` outside a function body is a compile-time error (`BIP001-D2`, `BIP001-D3`), `print` writes to the `ScriptContext` writer (`BIP001-D6`), annotations checked at boundaries (§10.3), and the object model ([bip002_object-model.md](bip002_object-model.md), summarised in §8.2). |
| **Implemented** | §15 steps 1–11: the shared runtime (`BIP001-I0`), block scope and the `return` checks, `BIP001-D6`, `BIP001-D8`, the object model in both modes, the `BIP001-I1` compiler for the whole grammar, `BIP001-I2` (§16, §17) apart from JVM interfaces, `BIP001-I3` (§18), the static type checker with external boundary checks ([bip003_type-system.md](bip003_type-system.md), [bip008_static-typing.md](bip008_static-typing.md)), and `RM001` typed primitive lowering & static dispatch (§10.5, §19). `BIP001-I4`, ahead-of-time compilation, was designed and done as `BIP005-I5` (`BIP006-I4`–`BIP006-I6`) of [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md) (`-compile`). JVM interfaces (§17) remain. |
| **Date** | 2026-10-11 |
| **Scope** | `org.babblelang.engine` and a new `org.babblelang.runtime` / `org.babblelang.compiler` |
| **Tracks** | README, *Planned features → compiled mode with compilation to bytecode using ASM* |

## 1. Summary

Babble currently runs as a tree-walking interpreter over the ANTLR parse tree. This
document proposes a compiler that turns a parsed Babble file into a JVM class, loads it
in-process, and runs it behind the existing JSR-223 `Compilable` API. The interpreter stays
in place as the reference implementation and as a fallback.

The work is split into four phases. Each one leaves the build green and can ship on its own:

0. **`BIP001-I0` — Shared runtime.** Pull operators, truthiness, member selection and the calling
   convention out of `Interpreter` and the `Callable` natives into a runtime library that does
   not depend on the parse tree. The interpreter is moved onto it. Behaviour does not change.
1. **`BIP001-I1` — Namespace-backed compiler.** Generate bytecode that keeps today's runtime data model
   (`Namespace`, `Slot`, `BabbleObject`). Every name lookup is still a hash lookup at runtime,
   but tree walking, visitor dispatch and the `ReturnException` unwinding are gone. The goal
   here is full parity with the interpreter: the same test suite passes in both modes.
2. **`BIP001-I2` — Static scope resolution.** A resolver pass binds each identifier to its declaration at
   compile time. Function locals then become JVM locals, or heap cells when closures capture
   them. Classes and objects become JVM classes (§7.3). Namespaces are only kept where the
   language needs them to be reflective: packages, the script root, and globals.
3. **`BIP001-I3` — invokedynamic.** Calls, member selection, Java interop and arithmetic go through
   `invokedynamic` call sites with inline caches. Type specialisation driven by the optional
   type annotations becomes possible.

Type checking is a separate front-end pass that both modes run. It reuses the `BIP001-I2`
`Resolver`, should land alongside `BIP001-I2` code generation, and should come before `BIP001-I3`,
which is where enforced types pay off (§10).

Decisions so far. Babble gets **real block scope** (§8.1). It lands in the interpreter after
`BIP001-I0` and before `BIP001-I1`, so the compiler targets the new rules from the start. The **object
model** has its own design ([bip002_object-model.md](bip002_object-model.md)): class expressions, members reached only through `this` or a
receiver, interfaces with default methods instead of inheritance, and Babble functions usable
as Java functional interfaces (§8.2). **Type annotations are checked at runtime boundaries**
(§10.3).

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
  Leftover arguments are rejected (`No such parameter`, `Too many parameters`). *(Updated in
  [BIP021-I1](bip021_optional-variadic-overloaded-arguments.md) : required parameters may not
  follow optional ones, positional arguments after named ones follow C#'s rule, duplicate
  arguments fail with `Parameter given twice`, and statically bound calls are lowered
  positionally with a `$spec` missing-argument mask.)*
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
  `print` looks up `STDOUT` **in the scope where the call is written**: its call namespace is a
  child of the call site's. A `def STDOUT = buf` inside `f` therefore redirects a `println`
  written in `f`, but not one inside a function that `f` calls. `STDOUT` itself is the
  `System.out` captured when the engine was created, and the `ScriptContext` writer is ignored.
  `print(…, to: stream)` redirects a single call.
* Java interop resolves overloads through reflection on **every** call (`Overloads`).

### 2.7 Errors

`Interpreter` records the last visited context in `last`. `BabbleCompiledScript` turns any
exception into `ScriptException(e.toString(), "<input>", last.getStart().getLine())`. Tests
assert on that exact message shape, for example
`org.babblelang.runtime.BabbleException: Not a scope : s in <input> at line number 1`.

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
* G5. Make Babble programs measurably faster than the interpreter (§11). `BIP001-I1` should already
  win; `BIP001-I2` and `BIP001-I3` should win by a lot.

**Non-goals (for this design)**

* Ahead-of-time `.class`/`.jar` output and a `babblec` CLI. The class layout is chosen so that
  this is easy later (§9, `BIP001-I4` ; since done, as `BIP005-I5` / `BIP006-I4`–`BIP006-I6` of
  [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md)).
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
          BlockScopes ─► Resolver (BIP001-I2) ─► type checker (§10) ─► scopes, bindings, types
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

## 5. `BIP001-I0`: a shared runtime

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
  (its writer is where `print` sends output, §8.4), and the engine. It replaces natives digging
  through the caller's namespace.

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
support yet, by emitting `new InterpretedFunction(ctx, ns)` for it. `BIP001-I1` can land one
construct at a time and still run every test. While any fallback remains, the compiled
script keeps the parse tree and an `Interpreter` in its `ScriptRuntime`.

## 6. `BIP001-I1`: a compiler on top of the existing data model

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
body or the file. In `BIP001-I1` the current namespace is always held in one JVM local, `ns`.

| Construct | `BIP001-I1` emission |
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
| `object ( … )` | Today's semantics: `obj = new BabbleObject(ns)`, compile the body with `ns := obj`, `POP`, push `obj`. Once the object model lands, `class`/`object` compile against `BabbleInstance` and its member table instead (§8.2). |
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

* `return` with no enclosing function. **Decided (`BIP001-D2`):** it is a compile-time error in both
  modes, reported by the analysis pass. The code generator never sees one.
* `return` inside a parameter default. In the interpreter it escapes `bindParameters`, which is
  outside `Function.call`'s `catch`, so it returns **from the caller's** function. Compiled
  inline, it would return from the callee. **Decided (`BIP001-D3`):** it is a compile-time error too, so
  the prologue never contains a `return`.

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
splits the top-level statement list into `run$0`, `run$1`, …. In `BIP001-I1` that works without
any extra machinery, because all state sits in `root`, which is passed to each part.

### 6.9 Exit criteria

* Every test in `src/test/java` and every `.ba` script passes with `-Dbabble.mode=compile`,
  apart from the accepted divergences in §8. Those get explicit mode-specific tests.
* Every generated class passes ASM's `CheckClassAdapter` in tests.
* No interpreter fallback is left for any construct in the grammar.

## 7. `BIP001-I2`: static scope resolution

`BIP001-I1` still does a `HashMap` lookup and a parent-chain walk for every variable access. `BIP001-I2`
resolves names at compile time.

### 7.1 Resolver

`Resolver` is a visitor that runs before code generation. It lives in `org.babblelang.analysis`
because the type checker uses it too (§10.1). It builds a tree of lexical scopes
(file, package, class, function, block) and records a `Binding` for each identifier occurrence in a
`ParseTreeProperty<Binding>`:

| Binding kind | When | Generated code |
|---|---|---|
| `LOCAL(i)` | a parameter or `def` of the enclosing function or block that no nested function literal captures | `ALOAD i` / `ASTORE i` |
| `CELL(i)` | a parameter or `def` of a function or block that a nested function literal captures | `Cell` in JVM local `i`; the closure gets the `Cell` |
| `CAPTURED(k)` | a reference to an enclosing function's variable from inside a closure | `self.captured(k)` then `Cell.value` |
| `MEMBER(scope, "x")` | a `def` in a package or file body | `Slot` of that runtime namespace, looked up once per activation and cached in a local |
| `GLOBAL("x")` | a name not declared lexically anywhere | `root.get("x")`: implicits plus bindings, dynamic, because bindings are only known at `eval` |
| `THIS` / `RECURSE` | the `this` / `recurse` keywords | §7.4 |

Every block is a scope (§8.1). The resolver starts from the per-scope `def` sets that the
block-scope pass already computes. A function's parameters and the top level of its body share
one scope. Class and object members never appear in this table: an unqualified name is never a
member, and members are only reached as `this.x` or `obj.x` (§7.3).

### 7.2 Block-local variables

By `BIP001-I2`, block scope (§8.1) already behaves the same in both modes. `BIP001-I2` only changes
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

Scopes that are not functions or blocks (file, package) keep their dynamic `Namespace` and
`Slot`s. External code reads them through `pkg.x`, the engine injects bindings
into the root, and top-level forward references (`Forward.ba`) must keep working. The resolver
still knows their `def` sets statically, so for those it only skips the chain walk.

### 7.3 Classes and objects

The object model ([bip002_object-model.md](bip002_object-model.md) §7) gives the details. In summary:

* Each `class` and `object` expression compiles to a JVM class whose fields are its members.
  Its methods are JVM instance methods, with the receiver in local 0. Each `interface`
  compiles to a JVM interface with `default` methods.
* `this.x`, and `obj.x` where the type checker knows `obj`'s class, compile to
  `GETFIELD`/`PUTFIELD`, and a method call on them to `INVOKEVIRTUAL`/`INVOKEINTERFACE`. Any
  other receiver goes through `Members.select`/`Members.invoke` and the class's member table.
* Constructor parameters that methods use become hidden fields. Outer variables captured by a
  class are stored once per class value, not per instance.

Members are never reached through the lexical scope chain, so the `FrameScope` bridge an
earlier draft needed for today's fall-through (`BIP001-D5`) is gone.

### 7.4 `this` and `recurse`

* `recurse` becomes the `self` argument, with no lookup. Inside a method it is the method bound
  to the current receiver.
* `this` is a keyword, and it is valid only in a class or object body, in a method, or in a
  function literal nested in a method (object model §5.2). Anywhere else it is a compile-time
  error. Inside a method, `this` is local 0. A nested function literal captures it like any
  other variable. No function needs a runtime "unbound" state or a lexical fallback any more.

### 7.5 Closures

`CompiledFunction` holds `FunctionBody body`, `Object[] captured` (cells, or member
namespaces/slots), the parameter table and a display name. `BIP001-I2` drops the `Namespace
closure` field, except for functions whose enclosing scope really is a namespace (file and
package bodies). Those capture that namespace as one entry of `captured`. Methods are not
`CompiledFunction`s: they are instance methods of their class (§7.3).

One optimisation is optional: when a captured variable is never assigned after its `def`, the
closure can capture the **value** instead of a `Cell`. With block scope, a `def` inside a loop
body creates a new variable on every iteration, so that case needs no special handling.

### 7.6 Exit criteria

* Both modes pass the full suite.
* On the benchmarks in §11, `BIP001-I2` is clearly ahead of `BIP001-I1`. The numbers go in the PR.

## 8. Semantic decisions and divergences

Each item gets a test that pins the agreed behaviour **in both modes**.

| # | Topic | Today | Decision or recommendation |
|---|---|---|---|
| `BIP001-D1` | What a name refers to before its `def` runs | resolves to an outer binding | **Decided: real block scope with hoisting (§8.1).** Reading a name before its `def` is an error. |
| `BIP001-D2` | `return` with no enclosing function: at file level, or in a top-level `package`/`object` body | runtime error, only if it runs | **Decided: compile-time error (§8.3).** |
| `BIP001-D3` | `return` in a parameter default | returns from the *caller* when the default is used | **Decided: compile-time error (§8.3).** |
| `BIP001-D4` | `def` inside a loop body | fails on the second iteration | **Decided: fixed by `BIP001-D1`.** Each iteration gets a fresh scope. |
| `BIP001-D5` | `obj.x` falling through to lexical scope (`o.println`, `mk().hidden`) | allowed | **Decided: no.** `obj.x` sees only the object's members (§8.2). |
| `BIP001-D6` | Where `print` writes by default | `STDOUT` looked up in the scope where the call is written; the `ScriptContext` writer is ignored | **Decided: the eval's `ScriptContext` writer (§8.4).** |
| `BIP001-D7` | An unqualified name inside an object body after `obj.m` has cached a `BoundMethod` in the object | can resolve to the bound method | **Obsolete.** Unqualified names never reach members, and nothing is bound on the fly any more (§8.2). |
| `BIP001-D8` | `==` on non-numbers | `compareTo` for two `Comparable`s of the same class, identity otherwise, so equal Java lists are not `==` | **Decided: Java `equals()`**, with numbers still compared across types, value classes generating their own equality, and `same(a, b)` for identity ([bip002_object-model.md](bip002_object-model.md) §4.7). It changes `Operators.eq` only, so it can land with `BIP001-I0`. |

Every item is decided.

### 8.1 Block scope

This is a language change, made in the interpreter after `BIP001-I0` and before `BIP001-I1`. Its rules:

1. **Which blocks are scopes.** A block expression, an `if` branch and a `while` body each open
   a new scope, a child of the enclosing one. Function, package and object bodies do not add a
   second scope: the top level of the body *is* the function's scope (shared with its
   parameters), the package's namespace, or the class or object. The top level of the file is
   the script root. One exception: a class or object body's top-level `def`s declare *members*,
   which bind no unqualified name (object model §4.2). Constructor parameters are lexical, so
   `def x = x` there declares the member `x` from the parameter `x`.
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
  before anything runs in both modes. This pass is the seed of the `BIP001-I2` `Resolver`, and it
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

### 8.2 Object model

The object model has its own design document, [bip002_object-model.md](bip002_object-model.md). Its four
foundational decisions:

1. **Class expressions,** `class (params) implements I ( body )`. The header is the
   constructor's parameter list, the body runs once per instance, and its top-level `def`s are
   the members. `object ( … )` stays, as an instance of an anonymous class.
2. **Members only, explicit `this`.** `obj.x` sees only the object's members (this closes `BIP001-D5`).
   Inside a class, members are written `this.x`, and an unqualified name never means a member.
   `this` is valid only in class bodies and methods.
3. **No class inheritance.** Classes implement **interfaces** that declare required members and
   may provide default methods. `implements` must name statically known interfaces.
4. **Java callbacks.** A Babble function is converted automatically where Java expects a
   functional interface (`Comparator`, `Runnable`, …). Classes can implement Java interfaces,
   and Babble interfaces can extend them (object model §9.5, §9.6).

What this means for the compiler:

* **`BIP001-I1`** compiles classes and objects backed by `BabbleInstance`, an array of member
  values with a member table. The object model is implemented in the interpreter first, ideally
  before §15 step 7, so `BIP001-I1` never compiles the old object semantics.
* **`BIP001-I2`** turns classes into JVM classes and interfaces into JVM interfaces (§7.3), and
  `this` becomes local 0 (§7.4).
* **`BIP001-I3`** adds call sites cached per receiver class, and `LambdaMetafactory` adapters for
  the Java conversions.
* **Types:** classes and interfaces are nominal types, which settles `BIP001-Q5` (`BIP003-D5`).

`Stack.ba`, `counter2` in `Test1.ba` and the README, and
`testRecursionInAMethodKeepsTheReceiver` are migrated in the same change (object model §10).

### 8.3 `return` outside a function body

A `return` is accepted only inside the **body** of a function literal. To check it, walk up from
the `return` to the nearest enclosing function literal:

* If the `return` is in that literal's body, it is accepted and returns from that function.
  `package` and `object` bodies are not functions, so the walk goes through them: a `return`
  inside one returns from whatever function encloses it.
* If the `return` is in one of that literal's parameter defaults, it is rejected with
  `return in a parameter default` (`BIP001-D3`). A default value is an expression to compute, not a place
  to leave a function. A function literal nested *inside* a default is a function of its own, so
  a `return` in its body is fine.
* If there is no enclosing function literal, it is rejected with `return outside of a
  function` (`BIP001-D2`).

The check happens before the script runs, in both modes:

```
return 1                         ;; rejected (BIP001-D2)
if false then ( return 1 )       ;; rejected too, though it never runs
package p ( return 1 )           ;; rejected
def f = () -> ( return 1 )       ;; fine
def g = () -> ( object ( def v = ( return 2 ) ) )   ;; fine: returns from g
def h = (a, b = ( return 99 )) -> ( a )             ;; rejected (BIP001-D3)
def k = (a, cb = () -> ( return 1 )) -> ( cb() )    ;; fine: the return belongs to the nested literal
```

Today, `h(1)` called from inside a function `g` makes `g` return 99, and called from the top
level it fails at runtime. No test covers either case.

* The check lives in the `BlockScopes` pass (§8.1), which already walks every scope, so it runs
  at `compile()` time in both modes. The interpreter's runtime check in `visitFile` stays only
  as a defensive assertion, because no `return` can reach it any more.
* All errors found before the script runs, from this check and from rule 4 of §8.1, are raised
  as `ScriptException(message, fileName, line)`. They read exactly like runtime errors
  (`… in <input> at line number 1`). The existing `testReturnOutsideOfFunction` therefore passes
  unchanged. It gains a case for a `return` that never runs (`if false then ( return 1 )`),
  which is accepted today and rejected from now on. A new test covers `BIP001-D3`: a `return` in a
  default is rejected, and one in a function literal nested inside a default is accepted.
* Because the code generator never meets such a `return`, neither `run` nor a function
  prologue (§6.3) needs a special case: every `ARETURN` emitted for a `return` is in a
  function body.

### 8.4 Output of `print`

`print` and `println` write to the eval's `ScriptContext.getWriter()`, which they reach through
the `ScriptRuntime` argument of the calling convention (§5.2). That is the standard JSR-223 way
for a host to capture a script's output, and it works the same in both modes. Natives no
longer need to see the caller's scope.

```
// host
StringWriter out = new StringWriter();
ScriptContext ctx = new SimpleScriptContext();
ctx.setWriter(out);
engine.eval("println(\"hi\")", ctx);      // out now holds "hi\n"
```

* **Default behaviour is unchanged for hosts that don't care.** `SimpleScriptContext` starts
  with a writer on `System.out`, so plain `engine.eval(script)` still prints to the console.
* **`STDOUT` stays readable,** but it is no longer an engine-wide implicit holding the
  `System.out` from engine creation. Each eval binds it to the context writer, wrapped as a
  `PrintWriter` if needed, in a per-eval namespace between the implicits and the script root.
  A host binding named `STDOUT` shadows it rather than clashing. `BabbleNativesTestCase`
  (`if STDOUT then …`) keeps passing.
* **`def STDOUT = …` no longer redirects anything.** It is an ordinary variable. No script or
  test in the repo relies on the old behaviour.
* **`to:` still redirects one call.** It now accepts a `Writer`, a `PrintStream` or any
  `Appendable`. Anything else is a `BabbleException` (`print to: expects a Writer or
  PrintStream`), where today it is a raw `ClassCastException`.
* **Flushing.** `CompiledScript.eval` flushes the context writer in a `finally`, so output
  written through a buffered writer is not lost when the script fails.
* **Tests.** A new test captures output through `ctx.setWriter`. The differential `.ba` tests
  (§11) capture output the same way instead of swapping `System.out`.

## 9. `BIP001-I3` and `BIP001-I4` (outline)

**`BIP001-I3`: invokedynamic and specialisation.** Each of these needs its own design note when we
get there (§18 has what landed):

* **Calls.** `INVOKEDYNAMIC call(Object callee, Object… args)`, bootstrapped by
  `org.babblelang.runtime.Bootstrap`, with a monomorphic inline cache. If the callee is a
  `CompiledFunction` with the same `body` as last time, and the call is positional and complete,
  the site jumps straight to the body's `MethodHandle` with the arguments spread. That skips
  `Arguments` binding and the `Object[]`.
* **Java interop.** Cache `Overloads` resolution per (receiver class, argument classes) at each
  site. Today every call to a Java method re-runs reflection over all overloads. That is
  probably the single largest cost in interop-heavy scripts.
* **Member selection.** Cache by receiver class. For a Babble instance it is a field access, and for
  `JavaPackage`/`JavaClass` the result is constant, so it can be bound once.
* **Arithmetic.** Guard on `(Integer, Integer)` and inline `Math`-style wrapping `int`
  arithmetic, falling back to `Operators`. Where the type checker has established a static
  type (§10.5), no guard is needed.

**`BIP001-I4`: ahead-of-time compilation (optional).** A `babblec` Maven goal or CLI writes the
script class plus a `main` that builds the implicits and calls `run`. Hidden runtime state
(class loader, `ScriptRuntime`) is already explicit, so this is mostly packaging. The jar
would depend on the Babble runtime and on ANTLR only if interpreter fallback is still used.
*Done* as `BIP005-I5` (`BIP006-I4`–`BIP006-I6`) of [bip006_module-sources-and-aot.md](bip006_module-sources-and-aot.md), with modules : a CLI,
`-compile`, writes a jar of the program's modules, an index, the runtime and a launcher ; it
depends on `java.base` only, and compiles imports it doesn't hold only when Babble is on its
class path. The Maven goal is left for later.

## 10. Type checking

The README lists "type checking" and "type inference" as planned features. Today the
annotations (`a:int`, `(n:int):string`) are parsed and then ignored: nothing in the interpreter
reads them. This section covers where a type checker fits relative to the compiler phases, and
what has to be decided before one can be built. The type system itself has its own design
document, [bip003_type-system.md](bip003_type-system.md), which settles `BIP001-Q1`–`BIP001-Q8` (`BIP003-D1`–`BIP003-D8`); the list in §10.4 is the agenda
it answers. The checker and runtime boundary enforcement are implemented.

### 10.1 Where it sits

The type checker is a **front-end pass**. It runs on the parse tree after parsing, before
either backend, and in both modes. If it ran only before code generation, whether a program
is accepted would depend on `babble.mode`, which goes against G1.

* It needs to know which declaration each identifier refers to. That is exactly what the
  `BIP001-I2` `Resolver` (§7.1) computes, so the checker consumes `ParseTreeProperty<Binding>`
  rather than repeating the analysis. The `Resolver` therefore lives in a shared
  `org.babblelang.analysis` package, not in the compiler.
* It records a `Type` per expression in a `ParseTreeProperty<Type>`, which code generation and
  the optimizers can read later (§10.5).
* It runs **before** the optimizers. They rewrite the tree, and diagnostics should point at what
  the user wrote.

### 10.2 Ordering relative to the compiler phases

**Not before `BIP001-I0` and `BIP001-I1`.** Those phases reproduce the interpreter's dynamic behaviour, and
their only success criterion is that the same tests pass in both modes. Type information gives
them nothing. A checker also changes which programs the language accepts, and doing that while
parity is being established makes every failure ambiguous: is it the compiler, or the new rules?

**Not before block scope and the `Resolver`.** Block scope (§8.1) fixes what each name refers
to, and the `Resolver` computes it.

**Before `BIP001-I3`.** `BIP001-I3` adds guarded call sites to cope with not knowing types: `int`
arithmetic with fallbacks, overload caches keyed on runtime classes. Where types are known and
trustworthy (§10.3), the compiler can emit direct code instead, so the mix of the two strategies
should be designed knowing what types will provide.

So the order is: `BIP001-I0`, block scope, `BIP001-I1`, then the `Resolver`, then **the type checker alongside
`BIP001-I2` code generation**, then the runtime checks (`BIP001-Q9` / `BIP003-D9`), then `BIP001-I3`. The type system
design (§10.4) depends on none of this and can start now.

### 10.3 Rollout: gradual, warnings first

* **Gradual typing.** Anything without an annotation has the type `dynamic`, which is compatible
  in both directions with every other type. Programs without annotations are therefore accepted
  as before, and diagnostics come from code that contradicts its own annotations. The exception
  to watch is inference (`BIP001-Q8`). A type inferred from an initialiser must not be stricter than what
  runs today: an inferred object type that leaves out the `BIP001-D5` fall-through members would reject
  `o.println`, for instance. The in-repo scripts are the test for this.
* **Always enforced.** (Originally gated behind a `babble.typecheck=off|warn|error` switch; once
  the type system settled and all in-repo scripts checked cleanly, optional modes were removed.)
* **Diagnostics** are collected with their source line and column, written to the
  `ScriptContext`'s error writer as `Error : ...`, and raised as a `TypeCheckException` (a
  `ScriptException`) from `compile()`, before anything runs.
* **Enforcement (`BIP001-Q9` → `BIP003-D9`, decided: checked at boundaries).** A checker alone cannot make types
  trustworthy, because `dynamic` values flow into annotated code. So a runtime check runs
  wherever a `dynamic` value meets a declared type: arguments of annotated parameters, results
  of annotated functions, writes to annotated variables and members. The check lives in the
  shared runtime (`Types.check(value, type)`), so both modes fail the same way, with
  `Type error : expected int, got string` and the line of the boundary. The checks are always
  active, and typed code generation can rely on checked types. Implemented as
  described in bip003_type-system.md §5.

### 10.4 Type system questions to settle first (`BIP001-Q1`–`BIP001-Q9`, resolved by `BIP003-D1`–`BIP003-D9`)

| # | Question | Why it matters | Starting point |
|---|---|---|---|
| `BIP001-Q1` | Type names | `Test1.ba` uses `int` and `string`, which nothing defines | Built-ins `int`, `double`, `bool`, `string` mapped to their boxed runtime classes. Dotted names (`java.util.List`) resolve to Java classes. (Resolved by `BIP003-D1`.) |
| `BIP001-Q2` | `null` | `def head = null` is everywhere, and so are `if` without `else` and `def x` without a value | `int`, `double` and `bool` reject `null`, which is what allows unboxing in `BIP001-I3`. Every other type is nullable, as the runtime already is. A `T?` syntax can come later. (Resolved by `BIP003-D2`.) |
| `BIP001-Q3` | Operator typing | §2.2 is the specification | `int op int` is `int` (except `/`, which is `double`). Any mix with `double` is `double`. `string + any` is `string`. Comparisons and `and`/`or`/`not` are `bool`. `if`/`while` tests accept any type, because truthiness is defined for all values. (Resolved by `BIP003-D3`.) |
| `BIP001-Q4` | Function types | Calls bind by name and by position, and parameters can have defaults | Parameter names are part of the type, and each parameter is marked optional or not. The grammar's `functionType` reuses `parametersDeclaration`, which allows default *expressions* in a type. That needs tightening. (Resolved by `BIP003-D4`.) |
| `BIP001-Q5` | Class, object and package types | Objects are a new kind of value with the object model (§8.2) | **Decided by the object model (§8.1 there; `BIP003-D5`):** classes and interfaces are nominal types, an object literal has an anonymous class type, and a package has a structural type. `x is T` tests at runtime. |
| `BIP001-Q6` | `this` | Only valid in class bodies and methods (§7.4) | The enclosing class's type. (Resolved by `BIP003-D6`.) |
| `BIP001-Q7` | Java interop | Reflection gives static types, but generics are erased | Constructor and method result types come from reflection, and erased type parameters become `dynamic`. With the argument types known, overload resolution can happen at check time (§10.5). (Resolved by `BIP003-D7`.) |
| `BIP001-Q8` | Inference for unannotated `def`s | If `def f = (a:int):int -> …` leaves `f` as `dynamic`, calls to `f` are never checked and the checker is close to useless | An unannotated `def` takes its initialiser's type when the `Resolver` sees no other assignment to it. Members that can be written through `obj.x = …` stay `dynamic` unless annotated. Fuller inference is the README's separate "type inference" item. (Resolved by `BIP003-D8`.) |
| `BIP001-Q9` | Enforcement | Decides whether `BIP001-I3` can trust types (§10.3) | **Decided (`BIP003-D9`): runtime checks at boundaries, always active, in both modes.** |

### 10.5 What the compiler gains

`BIP001-I1`, `BIP001-I2`, and `BIP001-I3` used no type information beyond runtime `Types.check` boundary guards (`as`, `setMember`, and `JavaClass` / `Faces`). With mandatory static typing ([bip008_static-typing.md](bip008_static-typing.md)) enforced in both modes (`RM001`), the compiler lowers trustworthy primitive and static-target facts directly via `ScopeAnalysis` (§19):

* Non-nullable `int`, `double`, and `bool` locals, closure cells (`IntCell`/`DoubleCell`/`BoolCell`), and compiled class fields (`I`/`D`/`Z`) are stored unboxed. Numeric arithmetic (`+`, `-`, `*`, `/`, unary `-`, compound `op=`) and comparisons compile directly to JVM primitive instructions (`IADD`, `DADD`, `IF_ICMPxx`, `Double.compare`), bypassing `Operators` and `instanceof BabbleInstance` guards whenever the operand is statically numeric.
* Functions and class methods with primitive parameters or results get specialized `$spec` entry points (`fn$N$name$spec`, `m$name$spec`) alongside the existing `$direct` (`Object`-signature `MethodHandle` invariant) and array `$call` adapters. Direct intra-file calls, `recurse(...)`, and `this.m(...)` call `$spec` directly without boxing; cross-module and dynamic typed sites can use typed `invokedynamic` descriptors `(Callable, ScriptRuntime, CallSite, <prim...>)<prim>`.
* Statically resolved Java method calls, constructors, array `.length`, and public field accesses emit direct `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE`/`NEW`/`ARRAYLENGTH`/`GETFIELD`/`PUTFIELD`/`GETSTATIC`/`PUTSTATIC`, with no reflection or `JavaLinker` guards at the call site.
* Static module imports bound to never-reassigned `def`s (`m.x`, `m.f`) compile to indexed `Module.exportAt(index)` slot reads (`BIP005` §8.3), and cross-classloader primitive field inline caches inspect the JVM field descriptor via `MethodHandles.privateLookupIn` (`BIP004` §8).
* `ScopeAnalysis` publishes converged `TypeChecker` facts (`PrimitiveKind`, definite-initialization flags, resolved Java/Babble/Module targets) directly into code generation, superseding a standalone AST `Unboxing` pass (`BIP008` §7.1). Algebraic constant folding / identity simplifications (`x + 0`, `x - 0`, `x * 1`, `x * 0`, literal `int` arithmetic) are restricted strictly to provable `int` operands so `double` signed-zero/NaN semantics (`x * 0.0` preserving `-0.0`/`NaN`) and runtime type errors on non-numeric types stay exact.

### 10.6 Testing

* Golden tests in the style of the `Resolver` ones: a snippet, and the diagnostics expected at
  each line and column.
* Every `.ba` script in `src/test/babble` must check cleanly, using the
  annotations it already has. Gradual typing makes that the baseline, and it also catches
  annotations that are wrong today but go unnoticed because nothing reads them.
* With `BIP003-D9` in place, both modes must fail the same way when a `dynamic` value reaches an
  annotated parameter with the wrong type. This joins the differential tests in §11.

## 11. Testing, debugging and benchmarking

* **Dual-mode suite.** `BabbleTestBase` reads `babble.mode`. Surefire gets a second execution,
  `compiled-tests`, that runs the same classes with `-Dbabble.mode=compile`, so `mvn package` runs
  both. While `BIP001-I1` is in progress, the compiled execution may exclude tests through a short,
  shrinking list in the POM.
* **Differential tests.** A parameterised test runs every `src/test/babble/*.ba` in both modes
  and compares the result value, the output captured through the `ScriptContext` writer, and
  the exception class and message.
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
  of `BIP001-I1`'s speed-up for less work. It would be the right call if speed were the only goal,
  but the project's stated aim is learning to compile to bytecode.
* **Truffle/GraalVM.** Partial evaluation of a self-specialising AST interpreter would probably
  beat everything here, but it is a heavyweight framework that sidesteps the learning goal.
  It is noted for completeness.

## 13. Risks

| Risk | Mitigation |
|---|---|
| Stack-map frames break on unusual expression nesting (`return`/`while` inside arguments) | `COMPUTE_FRAMES`, `CheckClassAdapter` in every test, codegen tests for those shapes |
| Semantics drift between the two modes | One shared runtime (`BIP001-I0`), the dual-mode suite and differential `.ba` tests |
| Block scope breaks user scripts | Only in-repo scripts exist today, and none relies on a `def` escaping its block (§8.1). It lands in the interpreter before the compiler starts. |
| The object model breaks existing scripts | Only three in-repo users rely on today's objects, and they are migrated in the same change (object model §10) |
| `getCommonSuperClass` loading classes during generation | Override it (§6.5) |
| Method or constant-pool size limits on large scripts | Split `run` and `<clinit>` (§6.6, §6.8) |
| Class-loader leaks in long-lived engines | A loader per compile, no static caches that point at generated classes from runtime code |
| The type checker rejects programs that run today | Gradual typing (unannotated is `dynamic`), phased rollout (`warn` before always-on enforcement), and every in-repo script must check cleanly (§10.3) |

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

**`BIP001-I1`** (Java-equivalent of the generated class):

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

**`BIP001-I2`.** `c` inside `counter` is captured and assigned by the inner literal, so it becomes a
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

1. `BIP001-I0a`: `org.babblelang.runtime` with `Operators`, `Truth`, `Members`. The interpreter
   delegates to them.
2. `BIP001-I0b`: the new `Callable`, `Arguments`, `CallSite`, `ScriptRuntime`. Port the natives,
   the interop and the test `AssertFunction`. `print` writes to the `ScriptContext` writer
   (`BIP001-D6`, §8.4).
3. Block scope in the interpreter (§8.1): the `BlockScopes` pass, pre-declared slots, the new
   error message. Enable `testBlockScope`. The same pass rejects a `return` outside a function
   body (`BIP001-D2`, `BIP001-D3`, §8.3).
4. Add ASM. Add `BabbleCompiler`, `ScriptClassLoader` and the mode switch. Compile literals,
   operators, `def`, names, blocks (as scopes), `if` and `while`, with interpreter fallback for
   function literals. Add the dual-mode surefire execution.
5. Compile function literals and calls (prologue, defaults, `return`). Remove the fallback.
6. The object model in the interpreter (object model §12, `BIP002-I1`–`BIP002-I5`): grammar, runtime,
   analysis checks, Java conversions, migrations. This can run in parallel with steps 4–5.
7. Compile `package`, `class`, `object`, `interface` and member access, backed by
   `BabbleInstance`. `BIP001-I1` is complete: the full suite passes in both modes.
8. `BIP001-I2`: grow `BlockScopes` into the `Resolver`, then `LOCAL`/`CELL`/`CAPTURED`, then
   JVM classes and interfaces (§7.3), then `this` as local 0.
9. In parallel with step 8, once the `Resolver` exists: the type checker (`warn` mode initially,
   now always-on errors). The type system design (`BIP001-Q1`–`BIP001-Q8` / `BIP003-D1`–`BIP003-D8`) can start at any time before that.
10. Runtime boundary checks (`BIP001-Q9` / `BIP003-D9`) in both modes, and full error enforcement (done; optional modes removed).
11. Benchmarks, then `BIP001-I3` design notes, using enforced types where §10.5 allows.

## 16. Implementation notes (`BIP001-I0` and `BIP001-I1`)

What landed, and where it departs from the text above.

* **Mode.** `INTERPRET` stays the default. `-Dbabble.mode=compile` or
  `engine.put(BabbleScriptEngine.MODE, "compile")` selects the compiler. (Later, `COMPILE`
  became the default ; `-Dbabble.mode=interpret` selects the interpreter, and the first Surefire
  execution sets it.) Surefire runs the whole
  suite twice; the `compiled-tests` execution sets `babble.mode=compile` and `babble.verify=true`,
  so every class compiled by a test also passes `CheckClassAdapter`. `DifferentialTestCase`
  runs every `.ba` script and a list of tricky snippets in both modes and compares results,
  output and errors.
* **Objects.** The object model is implemented (`BIP002-I1`–`BIP002-I6`, [bip002_object-model.md](bip002_object-model.md) §13),
  and `BIP001-I1` compiles it against `BabbleInstance` (§15 step 7). Each class or object expression's
  constructor is a static method `cls$N$Name(BabbleClass, BabbleInstance, ScriptRuntime, args,
  names, site)`, adapted to `ClassBody` with `LambdaMetafactory` like function bodies, and its
  `ClassShape` is a constant. Interface bodies and static blocks run inline. Since an object
  body is now a constructor, a `return` in it is `return in a class body` rather than a return
  from the enclosing function (§8.3).
* **Constants (§6.6).** The JVM lets only `<clinit>` itself write a `final` static field, so
  splitting the initialisation into helpers means the constant fields are `private static`
  without `final`. They only feed error messages and argument binding, so nothing is lost.
* **Line numbers (§6.7).** A line entry is emitted at each statement and before every operation
  that can fail, with the line of the construct the interpreter would blame. A function prologue
  has no line entry, and default values are compiled out of line after the body, so a binding
  error is reported at the line of the call. The interpreter was aligned with that: after
  evaluating a default it reports later binding errors at the call too.
* **`BIP001-D8`** landed with `BIP001-I0`: `Operators.eq` uses `equals()` past null and numbers, `JavaObject`
  compares the wrapped values, and `BoundMethod` compares receiver and function. `same` and
  `Values.hash` belong to the object model.
* **Natives.** `print to:` accepts any `Appendable`. Raw Java objects are still not scopes, so
  `STDOUT.flush()` is not callable from Babble (pre-existing).
* **Debugging.** `-Dbabble.dumpClasses=<dir>`, `-Dbabble.trace=true` and `-Dbabble.verify=true`
  work as in §11; `asm-util` is an optional dependency, only needed by the last two.

**Benchmarks** (`org.babblelang.bench.Benchmarks` in the test sources, median of 10 evals after
10 warm-ups, JDK 21):

| Benchmark | interpret (ms) | compile (ms) | speed-up |
|---|---:|---:|---:|
| fib(25) : calls and arithmetic | 129.7 | 16.6 | 7.8x |
| while loop : locals and while | 101.9 | 121.1 | 0.8x |
| Counter.ba closure : captured variables | 104.9 | 73.5 | 1.4x |
| Stack.ba push/pop : objects and methods | 86.0 | 23.2 | 3.7x |
| Stack.ba push/pop, with the object model | 69.8 | 26.6 | 2.6x |
| ArrayList add/get : Java interop | 98.0 | 90.7 | 1.1x |

Calls win most, because the visitor frames and `ReturnException` are gone. Loops were even,
but not for the reason first assumed (namespace lookups) : the compiled loop never left the
bytecode interpreter. §17 explains, and gives the numbers after `BIP001-I2`. Java interop is
dominated by the per-call reflection that `BIP001-I3` caches.

## 17. Implementation notes (`BIP001-I2`)

`BIP001-I2` (§7) is implemented, apart from compiling interfaces to JVM interfaces (below).

* **Resolver** (`org.babblelang.analysis.Resolver`, result in `Resolution`). It runs after
  `BlockScopes` and `TypeChecks` on the scope tree they build, and only the compiler uses it.
  Every use of a name (a plain name, an unqualified assignment, `this`, `recurse`, a lexical
  `def`, a parameter, the first name of a type in an implements list or `super` call) is bound
  to a `Variable`, or to nothing, meaning a runtime lookup.
* **Frames and namespaces.** The variables of functions, blocks, class bodies (constructor
  parameters and `this`) and static blocks are JVM locals of the method they are compiled into
  (`LOCAL`), or `Cell`s when a nested method captures them (`CELL`); a nested method reaches
  them through the cells it captured (`CAPTURED`). `this` and `recurse` are variables like any
  other: a method's `this` comes from its receiver argument, `recurse` from the function value.
  The file, packages and interface bodies keep their namespace (`MEMBER`/`GLOBAL` of §7.1 both
  compile to the `BIP001-I1` lookup), and so does any function, class body or static block that
  declares a `package` somewhere in it, which the design didn't consider: a package needs a
  namespace to live in, and keeping the `BIP001-I1` representation for that whole scope keeps its
  semantics exact. `ResolverTestCase` has the golden tests of §11.
* **Undefined.** A hoisted definition starts as `Undefined.UNDEFINED`, and reading or assigning
  it before its `def` runs is `Name used before definition`, as in the interpreter.
* **Parameter defaults.** A default can't see the parameters from its own onwards (they are
  not bound yet when it runs, and in the interpreter the name resolves outward), so the resolver
  binds such a name outward too. A captured parameter gets its cell before any default runs,
  so that a function literal in an earlier default can capture it. One divergence remains:
  calling, *while the defaults are still being evaluated*, a function from an earlier default
  that reads a later parameter fails with `Name used before definition` where the interpreter
  looks the name up outward. Nothing written that way can be meaningful.
* **Classes and objects as JVM classes** (§7.3). A class or object expression whose body keeps
  no namespace compiles to its own class, `Script$N$k$Point extends BabbleInstance`:
  - one field per member, and a `field(int)`/`setField(int, Object)` switch for everything that
    goes through `Members` (the interpreter's `BabbleInstance` keeps its array);
  - methods are JVM instance methods, the receiver in local 0, behind one shared
    `CompiledMethod` per method literal; a `fastRecord`'s methods have no field at all : their
    value is that per-class constant, read through the class's `$m$<name>()` accessor by other
    modules and by `SelectCache` (`BIP014-I3` follow-up, §9.4 of the optimization BIP);
  - a constructor parameter captured by a method lives in a hidden field holding its cell
    (`$v$start`), and outer variables are captured once per class value;
  - `this.x` compiles to `GETFIELD`/`PUTFIELD`, with the undefined check, and `this.m(args)` to
    a direct `INVOKEVIRTUAL` of the method.
* **Not done in `BIP001-I2` (landed later in `RM003`, §20): interfaces as JVM interfaces, and Java
  interfaces implemented by the generated class itself.** Without the type checker, the compiler
  never knows a receiver's static type,
  so it could emit neither `INVOKEINTERFACE` nor `INSTANCEOF`: `obj.m()` and `is` go through the
  runtime either way. The cost would be default methods reaching the captured variables of the
  right interface value from inside a JVM default method, and exact-signature bridges for each
  Java overload. Instances keep their `Proxy` face for Java. `BIP001-I3` caches selections by
  receiver class instead (§18), which gets the speed without them; they wait for the type
  checker (`RM003`, §20).
* **Loops and on-stack replacement.** `BIP001-I1` kept a `while` loop's running result on the
  operand stack. HotSpot only compiles a running loop (on-stack replacement) when the stack is
  empty at its head, so a function running one long loop, called only a few times, stayed in
  the bytecode interpreter: that, not namespace lookups, is why `BIP001-I1` loops were no faster than
  the tree-walking interpreter. The result now lives in a local. With that fix alone, the `BIP001-I1`
  representation runs the loop benchmark about 15x faster than the interpreter.

**Benchmarks after `BIP001-I2`** (same harness and machine, JDK 21):

| Benchmark | interpret (ms) | compile (ms) | speed-up |
|---|---:|---:|---:|
| fib(25) : calls and arithmetic | 100.1 | 3.3 | 30.2x |
| while loop : locals and while | 98.4 | 2.3 | 42.5x |
| Counter.ba closure : captured variables | 109.7 | 5.3 | 20.6x |
| Stack.ba push/pop : objects and methods | 63.5 | 4.6 | 13.8x |
| ArrayList add/get : Java interop | 93.0 | 67.7 | 1.4x |

Measured along the way: the `BIP001-I1` representation with the loop fix gives 8.4x, 14.7x, 9.3x,
7.2x and 1.0x; `BIP001-I2` without JVM classes for class expressions gives the same as above except
Stack.ba at 12.1x. Java interop stays bound by reflection, which `BIP001-I3` caches.

## 18. Implementation notes (`BIP001-I3`)

`BIP001-I3` (§9) is implemented for calls, member selection and Java overloads. Arithmetic guards
were measured and left out (below), and so was type specialisation, which needs the type
checker (§10.5).

* **Direct entry points.** A function literal compiles to `fn$N$name(CompiledFunction self,
  Object thiz, ScriptRuntime rt, Arguments.Binder binder, Object p0, …)`, one JVM parameter per
  Babble parameter, and a method of a compiled class to the instance method `m$name(CompiledMethod
  self, ScriptRuntime rt, Arguments.Binder binder, Object p0, …)`. A parameter the caller didn't
  give is `Arguments.MISSING`, and gets its default or fails with `Missing parameter`, as
  before. The array convention survives as an adapter, `fn$N$name$call` / `m$name$call`: it
  builds the binder, takes every parameter by name or position, and calls the direct entry point
  with the binder, which rejects the leftovers once the defaults have run, so errors keep their
  order. A direct call passes a null binder: it has no names and no more arguments than
  parameters. `FunctionBody` and `MethodBody` wrap the adapters, and `CompiledFunction` and
  `CompiledMethod` carry the direct entry point as a `MethodHandle` (an `LDC` constant, so every
  value of the same literal shares it). Functions with more than 200 parameters keep only the
  array form, to stay within the JVM's 255 parameter slots.
* **Call sites.** A call evaluates the callee, checks it callable (still before the arguments),
  then runs `INVOKEDYNAMIC call(Callable, ScriptRuntime, String[] names, CallSite, Object a0,
  …)`, bootstrapped by `org.babblelang.runtime.Bootstrap`. Each site is a `MutableCallSite`
  holding a chain of guarded fast paths, the newest first, and its fallback adds one each time a
  call misses:
  - a `CompiledFunction` whose direct entry point is the cached one, called by position with no
    more arguments than parameters: a jump to the direct entry point, padded with `MISSING`;
  - a `BoundMethod` over such a `CompiledFunction` (interface defaults, methods of classes that
    keep a namespace), the receiver as `thiz`, or over a `CompiledMethod` (methods of compiled
    classes), the receiver as the JVM receiver;
  - any other callee: its own class's `call`, guarded on that class. This one matters more than
    it looks: when every generic site went through one shared `callee.call(...)`, its type
    profile was megamorphic and HotSpot inlined nothing, which made class construction 2x slower
    than `BIP001-I2`'s plain `INVOKEINTERFACE`, whose profile is per site.

  After 4 misses a site goes generic for good. `this.m(args)` in a compiled class doesn't need a
  site: it was already an `INVOKEVIRTUAL`, now of the direct entry point when the call is
  positional. Calls with more than 200 arguments keep `BIP001-I2`'s array path.
* **Member selection.** `obj.x` is `INVOKEDYNAMIC select(Object)Object`, the member's name and
  the target's text (for errors) as static arguments. A miss first runs `Members.select`, so an
  error links nothing, then caches by receiver: for an instance of a compiled class, a guard on
  its JVM class and a read of its field, checked defined, and bound to the receiver for a method;
  for a `JavaPackage` or `JavaClass`, whose members never change, a guard on the receiver itself
  and a constant. Private and inherited members, namespaces, class values, interpreted instances
  and Java objects go generic. So does an instance whose class another script compiled: its
  fields are package-private to another class loader. `this.x` keeps `BIP001-I2`'s `GETFIELD`.
* **Java overloads** (`Overloads.Cache`, used by both modes). Every Java call used to run
  reflection over all the overloads of the method. Resolution only depends on the *kinds* of the
  arguments: their class, the Babble class of an instance (whose Java interfaces decide where it
  applies), null, and whether a class value can be called. So each `JavaMethod` and `JavaClass`
  keeps up to 8 resolved overloads, keyed by kinds and found without allocating; errors are not
  cached. The interpreter gets the same 4x on the ArrayList benchmark. Overload errors now name
  an instance's argument kind by its Babble class, the same in both modes, rather than by its JVM
  class.
* **Java calls** (`JavaLinker`, later). A call site whose callee is a Java method, called with
  positional arguments, links a guard on what resolution depends on (the same `JavaMethod`, so a
  receiver of the class that selected it, and arguments of the same kinds) and the resolved
  method's `MethodHandle`, with each argument converted as `Values.toJava` does (unboxed and
  widened for a primitive parameter), the result as `Values.fromJava` does, and anything thrown
  reported as reflection reports it. An argument whose kind isn't its JVM class (a Babble
  instance or class) or that may need the runtime to convert (a Babble function) gets no fast
  path. A select site whose member is a Java method binds it to receivers of the same class,
  wrapped or not. On a loop calling `list.get(n)`, a Java call went from 25 to 40 ns over the
  loop's own cost to about 5 ns, and the fable ray tracer, which walks a `java.util.ArrayList`
  of about 480 spheres for every ray, renders as fast as with a Babble linked list.
* **Arithmetic: measured, not kept.** An inline `(Integer, Integer)` guard for `+`, `-` and `<`,
  falling back to `Operators`, made fib 2.45 → 2.7 ms and the loop 1.77 → 1.9 ms. HotSpot
  already inlines the `Integer` fast paths at the top of `Operators.add` and friends, and boxing
  the result dominates either way. Unboxing needs types the compiler can trust (§10.5).
* **Warm-up.** Until the JIT compiles them, `invokedynamic` sites run through interpreted
  method handle code, which is slower than a plain `INVOKEINTERFACE`. A top-level loop that runs
  only a few times shows it: Stack.ba took 10 ms instead of `BIP001-I2`'s 6 ms for each of its first
  10 runs, then 1.2 ms instead of 2.6 ms. The harness now warms up for 50 runs and times 20,
  for both columns below.

**Benchmarks after `BIP001-I3`** (same machine, JDK 21; the `BIP001-I2` column is master before `BIP001-I3`,
under the same harness):

| Benchmark | interpret (ms) | `BIP001-I2` (ms) | `BIP001-I3` (ms) | `BIP001-I3` speed-up |
|---|---:|---:|---:|---:|
| fib(25) : calls and arithmetic | 104.7 | 3.5 | 2.8 | 38.0x |
| while loop : locals and while | 98.6 | 1.7 | 1.8 | 55.8x |
| Counter.ba closure : captured variables | 106.1 | 5.0 | 4.4 | 24.0x |
| Stack.ba push/pop : objects and methods | 61.7 | 3.6 | 1.7 | 35.4x |
| ArrayList add/get : Java interop | 25.3 | 63.9 | 4.8 | 5.2x |

The interpreter column includes the overload cache, which took the ArrayList benchmark from
97 ms to 25 ms in that mode too.

## 19. Design & implementation notes (`RM001` : Typed primitive lowering and static dispatch)

`RM001` connects `BIP008`'s mandatory static type checker directly to bytecode generation and runtime inline caches across `BIP001` (§10.5, §18), `BIP002` (§4.11), `BIP003` (§6), `BIP004` (§8), `BIP005` (§8.3), and `BIP008` (§7.1).

* **Design choice : `ScopeAnalysis` publication instead of an AST `Unboxing` pass.** `TypeChecker` already runs a fixed-point loop (`up to 64` passes) resolving every expression's narrowed type, declaration type, widening conversions (`widensToDouble`), runtime boundary descriptors (`checkedTypes`), and unambiguous Babble/Java/module targets. Rather than duplicating type logic in a separate AST rewrite (`BIP008` §7.1), `TypeChecker` publishes converged static facts once into `ScopeAnalysis` after the fixed-point loop and `checkInterfaces()` complete (and freezes declaration/member types into `AnalysisSession` across interactive REPL inputs):
  - `ScopeAnalysis.PrimitiveKind` (`NONE`, `INT`, `DOUBLE`, `BOOL`), returned only for exact non-nullable `Type.INT`, `Type.DOUBLE`, and `Type.BOOL` (nullable `int?`, unions such as `int | double`, and `any` remain `NONE` at storage boundaries, while flow-narrowed reads inside e.g. `if x != null` get an expression `PrimitiveKind` of `INT` and unbox at use).
  - Definite-initialization flags distinguishing declarations that may be observed before their initializer executes (recursive/forward references or closure capture before init) from lexically initialized definitions, omitting redundant `def$N` guards when safe.
  - Resolved static targets : `JavaCallTarget`, `JavaConstructorTarget`, `JavaFieldTarget`, `isArrayLength`, `BabbleCallTarget` (local functions and class methods), and `ModuleExportTarget(index, name)`.
* **Storage representation & `$spec` entry points.**
  - *Locals & `while` results :* `LOCAL` variables with `declarationKind` `INT`/`DOUBLE`/`BOOL` live directly in `I`/`D`/`Z` JVM local slots (allocating 2 local slots per `double` across locals, parameters, and `maxLocals`).
  - *Closure cells :* `CELL`/`CAPTURED` variables of `PrimitiveKind != NONE` use dedicated `org.babblelang.runtime.IntCell`, `DoubleCell`, and `BoolCell` holders in `babble-runtime`, avoiding boxed `Integer`/`Double`/`Boolean` allocation on every closure mutation (e.g., `Counter.ba`).
  - *Compiled class fields :* Members of `BabbleInstance` subclasses with `PrimitiveKind != NONE` become primitive fields (`int x;`, `double y;`, `boolean b;`), keeping `ClassShape` `fieldNames` exact (`Point` -> `x, y, norm`; `C` -> `next, $v$start`). Header parameters and methods are initialized before body code runs and carry no `$defined` bit. Only classes that have unboxed primitive body `def` fields emit a compact `int $defined` bitmask and override `BabbleInstance.isMemberDefined(int index)` so `Member used before definition` stays exact even if `this` escapes during construction. `field(int)` and `setField(int, Object)` box/unbox at the boundary.
  - *Specialized entry points (`$spec`) :* Functions and class methods that have at least one primitive parameter or primitive return type emit a specialized entry point `fn$N$name$spec` / `m$name$spec` alongside the existing `$direct` (`Object`-parameter `MethodHandle` invariant tested by `BabbleCompilerTestCase`) and `$call` adapters. `$direct` handles `Arguments.MISSING`, parameter defaults, and `binder.rejectLeftovers()`, then delegates directly to `$spec` with zero body duplication. `CompiledFunction` and `CompiledMethod` optionally carry a `MethodHandle spec` handle so cross-module and polymorphic typed `invokedynamic` call sites (`Bootstrap.call` with primitive `MethodType` `(Callable, ScriptRuntime, CallSite, <prim...>)<prim>`) also stay unboxed end-to-end when arity and types match. Intra-file direct calls, `recurse(...)`, and `this.m(...)` emit direct `INVOKESTATIC`/`INVOKEVIRTUAL` to `$spec`.
* **Expressions, operators, control flow & edge-case parity.**
  - Expressions emit onto the operand stack in their natural `PrimitiveKind` (`I`, `D`, `Z`, or `L` for reference `NONE`). Adapters `emitAs(expr, expectedKind)` box (`Integer.valueOf`/`Double.valueOf`/`Boolean.valueOf`), unbox (`((Integer) v).intValue()`), and widen (`I2D`) only at representation transitions.
  - Numeric `+`, `-`, `*`, `/`, unary `-`, compound `op=`, `<`, `<=`, `>`, `>=` compile directly to `IADD`/`DADD`/..., `ISUB`/`DSUB`, `IMUL`/`DMUL`, `I2D` + `DDIV`, `INEG`/`DNEG`, `IF_ICMPxx`, and `Double.compare` + branch (matching `Operators.compare` exactly for `NaN` and `-0.0` vs `0.0`).
  - `==` / `!=` / `<>` on matching unboxed `int`/`bool`, unboxed `int` vs `double` (using `I2D`), or unboxed `double` (using `Double.compare(a, b) == 0`, preserving `-0.0 == 0.0` and `NaN != NaN` as in `Operators.equal`), compile inline; mixed/reference operands delegate to `Operators.equal`.
  - Boolean `not`, short-circuit `and`/`or` (including narrowed operands), and `if`/`while` conditions branch directly on `Z` (`IFEQ`/`IFNE`) without calling `Truth.test`. `classof(expr)` preserves `BIP002` §4.11 behavior by boxing primitive arguments cleanly to `Object`.
  - Constant folding / identity rewrites (`x + 0`, `x - 0`, `x * 1`, `x * 0`, literal `int` arithmetic) trigger strictly when both operands are provably `INT` (preserving side effects on evaluated sub-expressions and never rewriting `double` `-0.0`/`NaN` or erroneous dynamic values).
* **Static Java dispatch.** When `TypeChecker` resolves an unambiguous public, accessible Java method, constructor, array `.length`, or public field (with arguments statically guaranteed applicable so wildcard `any` values still hit `Overloads` error messages), `CodeGenerator` emits direct `INVOKEVIRTUAL`/`INVOKESTATIC`/`INVOKEINTERFACE`, `NEW` + `INVOKESPECIAL` (+ `JavaObject.of(...)`), `ARRAYLENGTH`, or `GETFIELD`/`PUTFIELD`/`GETSTATIC`/`PUTSTATIC` using `JavaTypes.accessible(method)`, unwrapping `JavaValue` wrappers on receivers where needed (`Values.unwrap`), converting arguments via `Values.toJava`/`unboxed`, checking boxed wrapper returns (`JavaTypes.checkBoxed`) and generic `Types.check` boundaries, and catching non-`BabbleException` `Throwable`s via `JavaTypes.wrapInvocationException` so checked/unchecked exception behavior matches reflection (`JavaMethod`/`JavaClass`) identically.
* **Indexed module imports & cross-classloader fields.** `Module` indexes exported slots by declaration order (`Module.exportAt(int index)`), letting static module member reads (`m.x`, `m.f`) on non-cyclic exports bypass `Set.contains` + `HashMap.get`. Across separate module or REPL session `ScriptClassLoader`s, `Bootstrap.SelectCache.instanceMember` inspects each field's runtime JVM type (`int`, `double`, `boolean`, or `Object`) via `MethodHandles.privateLookupIn`, checks `BabbleInstance.isMemberDefined(index)` for primitives when needed, and adapts the getter handle to `Object`.

**Benchmarks after `RM001`** (same `Benchmarks` harness, JDK 21, uncontended; baseline is `c5a17bb` before `RM001`):

| Benchmark | interpret (ms) | baseline compile (ms) | `RM001` compile (ms) | `RM001` vs interpret |
|---|---:|---:|---:|---:|
| fib(25) : calls and arithmetic | 122.4 | 2.3 | 1.7 | 71.4x |
| while loop : locals and while | 113.2 | 1.9 | 0.1 | 769.4x |
| Counter.ba closure : captured variables | 113.1 | 3.4 | 2.8 | 40.1x |
| Stack.ba push/pop : objects and methods | 54.9 | 2.7 | 2.8 | 19.5x |
| ArrayList add/get : Java interop | 27.2 | 3.0 | 2.0 | 13.3x |

End-to-end ray tracers (same machine, JDK 21):
* `examples/raytracer-argon/raytracer.ba`: **46.1 s** wall-clock (`50.3 s` user), down from `59.3 s` (~22% faster), rendering `examples/raytracer-argon/scene.ppm` byte-for-byte identically.
* `examples/raytracer-fable/raytracer.ba`: **65.9 s** render (`67.9 s` total wall-clock on 16 threads), down from `72.9 s` (~10% faster without yet unboxed cross-object `vec3` fields/`obj.x` call-site unboxing).

## 20. Design & implementation notes (`RM003` : JVM interfaces, Java interface bridges, and `LambdaMetafactory` SAM adapters)

`RM003` completes the JVM interface and proxy-free interop design deferred in §17 across `BIP001` (§17), `BIP002` (§7.3, §9.3, §9.5–§9.6, `BIP002-I6`, §13), and `BIP006` (§9).

* **Cross-module classloader hierarchy & frame computation (`RM003-P1`).**
  - When a compiled class or interface in module `B` implements or extends a JVM interface emitted by module `A` (or a prior REPL input), `B`'s `ScriptClassLoader` must resolve `A`'s bytecode. `BabbleScriptEngine` and `BabbleSession` chain parent `ClassLoader`s across static module imports (`CompositeClassLoader` when a script imports multiple compiled modules) and sequential session inputs, while `babblec` jars share one flat classpath at runtime. Cyclic module imports degrade gracefully to runtime `BabbleInterface` checks where mutual parent delegation is impossible.
  - ASM's `ClassWriter.COMPUTE_FRAMES` calls `getCommonSuperClass(type1, type2)` during code generation, before the current batch of generated classes and interfaces is loaded. `CodeGenerator` overrides `getCommonSuperClass` with an in-memory hierarchy table covering every generated class/interface in the compilation unit plus already-loaded parent classes, avoiding premature `Class.forName` failures and resolving shared Babble/Java superinterfaces cleanly.
  - `TypeChecker` publishes deterministic JVM internal names (`Script$N$iface$k$Name`, or stable `babble/<module>$iface$k$Name` in modules) together with `InterfaceMethodInfo`, `InterfaceCallTarget`, `InterfaceCheckTarget`, `InterfaceSuperTarget`, and `JavaInterfaceImpl` records into `ScopeAnalysis`, `ModuleInterface`, and `AnalysisSession`.
* **Babble interfaces as real JVM interfaces (`RM003-P2`, `BIP001` §17, `BIP002` §7.3).**
  - Each Babble `interface` expression (whose default bodies declare no `package` namespace) compiles via `InterfaceGen` to a public JVM `interface` (`ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT`) extending its parent Babble JVM interfaces.
  - Every declared member gets an `Object` entry point `bm$<name>(ScriptRuntime, CallSite, Object...)` (`0`-arg `(ScriptRuntime, CallSite)Object` for value members), plus a primitive-specialized `bm$<name>$spec(ScriptRuntime, CallSite, <prim...>)<prim>` entry point whenever `RM001` assigns any parameter or return an unboxed `PrimitiveKind` (`INT`, `DOUBLE`, `BOOL`) and all inherited declarations agree on that signature.
  - Interface default methods compile directly to JVM `default` methods on the generated interface, alongside retaining their `CompiledFunction` table on `BabbleInterface` for first-class `I.m`, `Bootstrap` fallback, and interpreted subclasses. When a default method captures outer lexical cells (`Cell`, `IntCell`, `DoubleCell`, `BoolCell`), the interface declares an abstract `$iface$<Id>() : BabbleInterface` accessor implemented on each compiled `BabbleClass` subclass (reading `BabbleClass.capturedFor(BabbleInterface)`), so separate evaluations of the same lexical `interface` expression remain isolated.
  - Compiled classes (`ClassGen`) declare their Babble JVM interfaces in the class header's `implements` list and emit `bm$<name>` / `bm$<name>$spec` bridges forwarding to the class's fields or `m$<name>` / `m$<name>$spec` methods (`Classes.requireDefined` for value members that may be uninitialized).
  - Statically resolved calls (including operator methods `+ - * /` and unary `-`) on interface-typed receivers emit direct `INVOKEINTERFACE` (`bm$<name>$spec` when specialized, else `bm$<name>`); `x is I` emits `INSTANCEOF <JvmInterface>` when no interpreted or namespace-keeping class can implement `I` (`Classes.isInstance` otherwise); and `super(I).m(...)` emits `INVOKESPECIAL I.bm$<name>(...)` (hoisting `super(I)` calls inside nested lambdas into synthetic `$super$k` instance/default methods on the enclosing class or interface because JVM `INVOKESPECIAL` requires the direct subtype as caller).
* **Direct Java-interface `implements` bridges (`RM003-P3`, `BIP002` §9.5–§9.6).**
  - Compiled Babble classes directly list every accessible implemented Java interface (`JavaTypes.isAccessible`) in their JVM `implements` clause and emit one exact-signature public bridge method per abstract or overridden Java method overload (deduplicated by `(name, MethodType)` across diamond hierarchies, including erased generic superinterface bridges).
  - Each bridge binds `ScriptRuntime` (`ScriptRuntime.BOUND` when active on the calling thread, else the class's creation-time `ScriptRuntime`, matching `Faces` behavior when Java threads invoke callbacks), converts arguments with `Values.fromJava` + `JavaTypes.checkParameters` (when boxed-primitive null checks or generic checks apply), invokes the Babble method, and converts the result back with `JavaTypes.convertResult` + `Values.toJava`.
  - Generated classes that implement Java interfaces override `BabbleInstance.javaDefaultLookup()` to return `MethodHandles.lookup()`, allowing `JavaDefaultCall` (`Lookup.findSpecial`) to invoke Java `default` methods directly on `this` (`super(JavaI).m(...)` and inherited Java defaults) without `Proxy.getInvocationHandler`.
  - `BabbleInstance.face()` returns `this` whenever `javaInterfaces` are all implemented directly by `this.getClass()`, eliminating per-instance `java.lang.reflect.Proxy` allocation in compiled mode while keeping `Faces.create` for the interpreter (and any hypothetical inaccessible Java interface).
* **Cached `LambdaMetafactory` SAM adapters (`RM003-P4`, `BIP002` §9.3).**
  - `Faces.function(callable, samClass, rt)` caches a per-interface `SamFactory` via `ClassValue` that spins a hidden JVM adapter class once per accessible `0..16`-argument SAM visible from `Faces.class.getClassLoader()`, using `LambdaMetafactory.metafactory` (or `LambdaMetafactory.altMetafactory` with `FLAG_BRIDGES` when generic superinterfaces introduce additional abstract erased `MethodType`s).
  - Each adapter captures a lightweight `SamInvoker(callable, sam, rt)` bound to `SamInvoker.invoke0`..`invoke16`, preserving upfront arity validation, `JavaTypes.checkParameter` boxed-null diagnostics, `Values.fromJava`, `JavaTypes.convertResult` widening/type-error checks, and `Values.toJava` return conversion without `Proxy` or `Method.invoke` reflection, and staying strictly inside `java.base` (`RuntimeBoundaryTestCase`).
  - `Proxy.newProxyInstance` (`FunctionHandler`) is retained strictly as a fallback for SAMs with `> 16` parameters, non-public/package-private SAMs (`TypeChecker` also routes inaccessible-SAM Java calls through `Bootstrap.call` / `JavaLinker` rather than emitting `CHECKCAST` to an inaccessible type), or SAMs defined in isolated child `ClassLoader`s invisible to `Faces`.
* **GraalVM native image (`BIP006` §9).** Eliminating `Proxy` faces on compiled classes and `Proxy` wrappers on standard SAM callbacks removes the dynamic-proxy obstacle noted in `BIP006` §9; generating `META-INF/native-image` reflection metadata for `Overloads` and standalone `native-image` packaging remains deferred.
