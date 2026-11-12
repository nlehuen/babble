# Design: the interactive mode

| | |
|---|---|
| **Status** | Implemented : `org.babblelang.Main` (the command line), `org.babblelang.Repl`, `org.babblelang.engine.BabbleSession`, `org.babblelang.analysis.AnalysisSession`. |
| **Depends on** | Block scope and the analysis ([bytecode-compiler.md](bytecode-compiler.md) §8.1), the type checker ([type-system.md](type-system.md)) |
| **Tracks** | README, *Planned features → interactive mode* |

## 1. Summary

`java -jar target/babble-1.0-SNAPSHOT.jar` starts a read-eval-print loop: it reads an input,
evaluates it, prints its value, and starts again.

```
>> def twice = (f, v) -> (
..   f(f(v))
.. )
function twice
>> twice((n) -> ( n + 1 ), 5)
7
```

* **A session, under the loop.** `BabbleSession` evaluates successive inputs, each seeing the
  definitions of the previous ones. The loop only reads lines and prints. A host can use a
  session without it: `engine.newSession(context).eval("…")`.
* **Both modes, and the type checker.** A session runs in the engine's mode, so each input is
  interpreted, or compiled to its own class. `babble.typecheck` applies as for a script.
* **Nothing is weakened.** An input goes through the same analysis as a file. What an earlier
  input defined is known to it, statically.

## 2. A session nests its inputs

A script's top level is one scope: a name is defined once in it, and the analysis relies on
that. It resolves `implements Shape` to an interface literal, it knows `Point` in `p : Point`,
it infers the type of a `def` that is never assigned. A session must let the user define a name
again, without making any of that false for code already analysed.

So **each input that defines names is a scope nested in the previous one**, in the analysis and
at runtime alike:

* **Analysis.** `AnalysisSession` keeps the file scope of the last input that defined names. The
  next input's file scope is its child. Names resolve outward through the chain, so
  `TypeChecks`, the `Resolver` and the `TypeChecker` find the earlier definitions as they find
  those of an enclosing block. The session also keeps what those passes look up by parse tree
  node: class and interface descriptions, the scope of each definition and of each name's use,
  and the definitions some input assigned.
* **Runtime.** `BabbleSession` runs such an input in a namespace that is a child of the previous
  one's. The top level of a script was already resolved at runtime through namespaces, in both
  modes, so nothing changes in either backend: a script can simply be run in a given
  namespace (`BabbleScript.eval(ScriptRuntime)`).
* **Only when needed.** An input that defines no name and no package (`f(3)`, `x = x + 1`) runs
  in the current namespace, and adds no scope. The chain grows with definitions, not with
  inputs.

What follows from the nesting:

* **Defining a name again shadows it.** `def x = 1` then `def x = 2`: the inputs that follow see
  the new `x`. A function defined in between keeps the `x` it was written against. This is what
  OCaml's and Scala's toplevels do.
* **Assignment changes a variable for everyone.** `x = 5` assigns the nearest `x`, which every
  function that sees it shares.
* **A function doesn't see later definitions.** `def even = … odd(n - 1) …`, then `def odd` in
  another input: `even` fails with `No such name : odd`. Functions that call each other go in
  one input, which can span lines.
* **Old code stays consistent.** A class that implemented `Shape` still implements that
  `Shape`, even if the name now means something else. With types enforced, a function declared
  with `(p : Point)` still takes the `Point` it named: after `Point` is defined again, passing
  it a new one fails with `Type error : expected Point, got Point`, since they are two types of
  one name.
* **Packages shadow too.** `package p ( … )` in a later input is a new `p`, not the earlier one
  reopened, as for a `package p` in a nested block of a file.

The alternative is one flat top-level scope where a definition replaces the previous one, as in
Python. Then functions see later definitions, and redefinitions reach old code. But old code was
analysed against the old definitions: a class checked against one `Shape` would be built with
another, and with types enforced, old functions would create instances their own annotations
reject. Nesting keeps every static fact true, at the price of mutual recursion across inputs.

## 3. What an input goes through

`BabbleSession.eval(source)`:

1. **Parse.** A syntax error is reported as `Syntax error : unexpected '='`, with its line and
   column. Scripts get the same message: before, a syntax error was a bare
   `ParseCancellationException`.
2. **Analyse,** as an input of the session. An error (`Name already defined`, an incomplete
   implementation, a type error in `error` mode) rejects the input, and the session is as if it
   had never been typed. In `error` mode the engine writes every type diagnostic, then throws a
   `TypeCheckException`.
3. **Commit** the analysis to the session, and nest a namespace if the input defines names.
4. **Run.** An input that fails while running keeps what it defined until then. A name whose
   `def` didn't run is declared but unset, and using it is `Name used before definition`, as in
   a script.

Errors inside a function of an earlier input are reported at the line of the current input's
call: each input is its own source.

## 4. Reading an input

An input is complete when it parses, or when its error is not at its end.
`BabbleSession.classify(text)` gives:

* `INCOMPLETE` when the parser fails on the end of the text: inside parentheses, or after a
  token that calls for a continuation (an operator, `,`, `=`, `->`, `then`, `else`). The grammar
  already says where a newline doesn't end a statement, so no other rule is needed. A string
  still open at the end is incomplete too, since strings can span lines.
* `EMPTY` for blank lines and comments, which a file allows but which are no expression.
* `COMPLETE` otherwise, syntax errors included: `eval` reports them.

One consequence: `if a then ( 1 )` is complete, so an `else` can't start the next line, as it
can in a file. It goes after the closing parenthesis, or the `if` goes inside parentheses.

## 5. The loop

`Repl` reads lines, and accumulates them while the input is incomplete. The prompts are `>> `,
then `.. ` for a continuation.

* **Values.** The value of an input is printed, unless it is `null`. A string is shown between
  quotes, to tell `"1"` from `1`. Anything else is shown as `"" + value` gives it. A definition
  has its value, like any expression, so `def x = 5` prints `5`.
* **Errors** are printed as `Error : message`, with the line when the input has several, and the
  loop goes on. A stack overflow is one of them.
* **Commands** start with `:`, which starts no expression: `:help`, `:cancel` to drop an
  unfinished input, `:quit`. The end of the input (Ctrl-D) also leaves.
* **Output.** `print` and the engine's warnings go where the loop prints.
* **Piped input.** Without a console there is no banner and no prompt, so
  `java -jar … < script` prints only values and output.
* **No line editing.** The loop reads plain lines, with no history or completion. `rlwrap`
  provides both.

## 6. The command line

`Main` is the main class of the jar. The jar also names its dependencies in `lib/`, where the
build copies them, so `java -jar` needs nothing else. `-Dbabble.mode=interpret` and
`-Dbabble.typecheck=warn|error` work as for the engine.

```
java -jar babble.jar                              the interactive mode
java -jar babble.jar script.ba [arguments...]     runs a script
java -jar babble.jar -keep-alive script.ba ...    runs it, then continues in the interactive mode
```

* **A script is the first input of a session.** That is all `-keep-alive` needs: the loop then
  starts on the same session, and sees what the script defined. Without the flag, nothing is
  read from the standard input, and the script's value is not shown.
* **Arguments.** What follows the script on the command line is the script's, options
  included; Babble's own options go before it. The script finds its arguments in `args`, a
  `java.util.ArrayList` of strings (`args.size()`, `args.get(0)`). It is always there, empty
  without arguments, and in the interactive mode too. It is a binding, so a script can define
  the name for itself.
* **Errors.** A script's output goes to the standard output. Its errors, warnings and type
  diagnostics go to the error stream, with the file's name and the line:
  `Error : No such name : x in script.ba at line number 3`. The exit status is 1 when the script
  fails, and 2 when the command line can't be used: an unknown option, a file that can't be
  read. On success `main` just returns, so threads the script started can finish.
* **A failing script with `-keep-alive`** still starts the interactive mode, with what the
  script defined until it failed, like `python -i`. Errors then go where the loop prints.
* **An empty script,** or one with only comments, does nothing.

## 7. Testing

`MainTestCase` covers the command line, in both modes: a script with and without `-keep-alive`,
its arguments, failing and empty scripts, the type checker's settings, and command line errors.

`ReplTestCase`, in both modes:

* sessions: earlier definitions, shadowing and assignment, functions not seeing later
  definitions, inputs rejected or failing midway;
* classes and interfaces across inputs, with the static checks;
* the type checker across inputs: warnings only for the input being typed, error mode with
  classes of earlier inputs as types, inference following assignments;
* `classify`, and the loop: values, multi-line inputs, errors, commands, prompts.

## 8. Not done

* Line editing, history and completion in the loop itself.
* Loading a file from inside the loop (`:load`), and showing the type of a value.
* A script can't import another Babble file: a program is one file.
* Compiled code reads the fields of an instance directly only within one input: across
  inputs it goes through the generic path, since each input's classes have their own class
  loader. It is correct, just not as fast.
