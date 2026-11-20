# BIP004: the interactive mode

| | |
|---|---|
| **BIP** | 004 |
| **Date** | 2026-11-10 |
| **Status** | Implemented : `org.babblelang.Main` (the command line), `org.babblelang.Repl`, `org.babblelang.repl` (line editing, history, completion : §9), `org.babblelang.engine.BabbleSession`, `org.babblelang.analysis.AnalysisSession`. |
| **Depends on** | Block scope and the analysis ([bip001_bytecode-compiler.md](bip001_bytecode-compiler.md) §8.1), the type checker ([bip003_type-system.md](bip003_type-system.md)) |
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
* **Both modes, and the type checker.** A session runs in the engine's execution mode, so each
  input is interpreted, or compiled to its own class. Type checking applies as for a script.
* **Nothing is weakened.** An input goes through the same analysis as a file. What an earlier
  input defined is known to it, statically.

## 2. A session nests its inputs

A script's top level is one scope: a name is defined once in it, and the analysis relies on
that. It resolves `implements Shape` to an interface literal, it knows `Point` in `p : Point`,
it infers the type of a `def` that is never assigned. A session must let the user define a name
again, without making any of that false for code already analysed.

So **`BIP004-D1` — each input that defines names is a scope nested in the previous one**, in the analysis and
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
   implementation, or a type error) rejects the input, and the session is as if it had never been
   typed. The engine writes every type diagnostic, then throws a `TypeCheckException`.
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
* **Commands** start with `:`, which starts no expression: `:help`, `:type` to show the type
  of an expression, `:load` to run a file, `:cancel` to drop an unfinished input, `:quit`. The
  end of the input (Ctrl-D) also leaves. §9 has the details.
* **Output.** `print` and the engine's warnings go where the loop prints.
* **Piped input.** Without a console there is no banner and no prompt, so
  `java -jar … < script` prints only values and output.
* **Line editing.** On a terminal, lines are edited with history and completion (§9). Piped
  input, and `-Dbabble.lineEditing=false`, read plain lines.

## 6. The command line

`Main` is the main class of the jar. The jar also names its dependencies in `lib/`, where the
build copies them, so `java -jar` needs nothing else. `-Dbabble.mode=interpret` works as for
the engine.

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
* the type checker across inputs: diagnostics only for the input being typed, classes of
  earlier inputs as types, runtime boundary enforcement, inference following assignments;
* `classify`, and the loop: values, multi-line inputs, errors, commands, prompts;
* `:type` (types as source writes them, nothing run, nothing kept, errors) and `:load` (what a
  file defines stays, its errors name it, its imports are relative to it).

`TerminalInputTestCase`, in both modes: completion (commands, names and keywords, members of
instances, classes, modules and Java classes and objects, files after `:load`, and nothing
after a call, which never runs), Enter on unfinished inputs, and the line editor end to end on
a JLine dumb terminal: multi-line inputs come whole, and are kept whole in the history.

## 8. Not done

* *(Done in §9):* Line editing, history and completion in the loop itself.
* *(Done in §9):* Loading a file from inside the loop (`:load`), and showing the type of a value (`:type`).
* A script can't import another Babble file: a program is one file. *(Done with modules in `BIP005`, keeping one `Modules` cache per `BabbleSession`.)*
* *(Done via `Bootstrap` and `RM001`):* Across inputs (whose classes live in separate `ScriptClassLoader`s), `Bootstrap.SelectCache` reads both reference and primitive (`int`/`double`/`bool`) fields directly via `MethodHandles.privateLookupIn` (honouring `BabbleInstance.isMemberDefined`), and typed `$spec` function/method calls across inputs dispatch directly via `Bootstrap.call`.

## 9. Line editing, history, completion, `:load` and `:type`

```
>> def twice = fn (f : fn (n : int) : int, v : int) : int -> (
..   f(f(v))
.. )
function twice
>> tw<Tab>                           completes twice
>> :type twice
fn (f : fn (n : int) : int, v : int) : int
>> :load shapes.ba                   runs a file into the session
>> java.lang.Math.sq<Tab>            completes java.lang.Math.sqrt
```

* **`BIP004-D2` — Lines come from a `LineSource`.** `Repl` reads through
  `org.babblelang.repl.LineSource`: `PlainInput` reads plain lines, as from a pipe or a test, and
  shows the prompts only when asked; `TerminalInput` is a line editor, on
  [JLine](https://github.com/jline/jline3). `Main` uses the editor when a person is typing at a
  terminal, unless `-Dbabble.lineEditing=false`, and falls back to plain lines when the terminal
  can't be used. Since JDK 22, `System.console()` also returns a console when the input is
  piped in: `Console.isTerminal()` tells them apart, where it exists. JLine is a dependency of
  the front end only: it is not in the runtime jar, nor in what `babblec` writes.
* **`BIP004-D3` — A multi-line input is edited as one buffer.** The editor asks
  `BabbleLineParser` whether Enter accepts the buffer; it does when `BabbleSession.classify`
  says the input is not incomplete, and always for a command. Otherwise Enter inserts a new
  line, after the `.. ` prompt. So the loop receives whole inputs, and Up recalls an input whole,
  to be edited anywhere before it runs again.
* **`BIP004-D4` — Ctrl-C drops the input, Ctrl-D leaves.** As `:cancel` and `:quit`. Inputs are
  kept in `~/.babble_history` (`-Dbabble.history=file` names another), the last 1000 of them,
  multi-line ones whole. `!` is not a history expansion: Babble has its own `!`.
* **`BIP004-D5` — Completion never runs code.** Tab offers the commands, file names after
  `:load`, and otherwise the names an input could use (what earlier inputs defined, the
  bindings, the implicits) and the keywords. After a chain of plain names, `p.` or
  `java.lang.Math.`, it offers the members of the value the chain names, found by looking names
  and members up: the members of an instance, the statics of a class or an interface, the
  exports of a module, the public methods and fields of a Java class (static ones) or object,
  never private members. A chain that would need a call, `f().`, gets nothing: completing it
  would mean running it. `org.babblelang.repl.Completions` is that logic, apart from JLine.
* **`BIP004-D6` — `:type` analyses an input without running it, nor keeping it.**
  `BabbleSession.typeOf(source)` parses and analyses the input against the session exactly as
  `eval` would, and returns the static type of its last statement, as source writes it
  (`list of int`, `map of (string, int)`, `fn io () : null`, `(list of int)?`, `Box of int`):
  `ScopeAnalysis.resultType()`, which the type checker sets after its verification walk. The
  session is left as it was: nothing is committed, no namespace is entered, nothing runs. A
  syntax or type error is reported as an input's. A name the analysis can't resolve is looked
  up at runtime, so it is `any`, as it would be for `eval`.
* **`:load file.ba`** runs a file as an input of the session, as `-keep-alive file.ba` would:
  what it defines stays for what follows, its output is printed but not its value, and its
  errors name it, `Error : … in file.ba at line number 3`. Its imports are relative to it. The
  path may be quoted.
