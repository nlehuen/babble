# Design: modules

| | |
|---|---|
| **Status** | Accepted : the questions of §13 were decided as recommended. Steps M1 to M3 (§12) are implemented ; M4 and M5 are not. |
| **Depends on** | [object-model.md](object-model.md) (member access, `HasMembers`), [type-system.md](type-system.md) (what a module's type is), [bytecode-compiler.md](bytecode-compiler.md) §4 (the file scope keeps a namespace) and phase 4 (ahead-of-time compilation) |
| **Unblocks** | Programs in several files, a standard library written in Babble, the AOT jar |

## 1. Summary

A Babble program is one file today : `import` only reaches Java packages. The ray tracer of
`examples/raytracer-fable` is 380 lines in a single file because of it.

This document proposes that **a file is a module** :

```
;; shapes.ba
def pi = 3.14159                         ;; not exported : private to this file
export def Circle = class (def r) (
    def area = () -> ( pi * this.r * this.r )
)
```

```
;; main.ba
def shapes = import("shapes.ba")
def Circle = shapes.Circle
assert(Circle(2).area() > 12)
```

* `export` marks the top-level definitions a file shares. Everything else is private to it.
* `import("file.ba")` runs the file once and returns a **module** : a value whose members are
  the exports. It is the `import` function that exists already ; a name ending in `.ba` is a
  file, any other is a Java package, as before.
* A module has its own top-level scope. It sees neither the importer's names nor other modules'.
* When the path is a string literal, the analysis reads the imported file at compile time, so
  missing files, missing exports and type errors across files are found before anything runs.

## 2. Goals and non-goals

Goals :

1. Split a program over files, with explicit control of what each file shares.
2. No new concept where an existing one fits : a module is used like an object or a package
   (`m.x`), and both modes share one implementation.
3. A file means the same thing whoever imports it : no dependency on the importer's names.
4. Errors as early as for a single file : the analysis and the type checker see through imports.
5. Usable from a host through `javax.script`, and compatible with compiling a program to a jar.

Non-goals, for now :

* Versions, a package manager, a registry, remote imports.
* A search path or a standard library location (§13, Q5).
* Visibility finer than exported or not (no "exported to these files only").
* Re-export syntax : `export def Circle = import("shapes.ba").Circle` does it.
* Hot reloading of a changed file within one eval.

## 3. Exporting

### 3.1 Syntax

```
export def name = value
export def name : type = value
export package name ( … )
```

`export` is a modifier of `def` and of `package`, like `private` is one of `def`. It is a new
keyword, so it can no longer be a name.

It is only valid for the names of the file scope. In a function, a block, a package body, a
class body, the analysis fails with
`export only applies to the top-level definitions of a file : x`. `export private` does not
parse.

Exporting a package exports the whole package : packages have no private names. A package
reopened by several `package p ( … )` expressions is exported if any of them says `export`.

A script that is run rather than imported may contain `export` : it has no effect.

### 3.2 Alternatives considered

| Alternative | Why not |
|---|---|
| Everything at the top level is exported, `private def` hides | Babble already uses `private` for members with a different rule (reachable through `this` only). And "public unless hidden" makes every helper part of a file's interface by accident. |
| An export list : `export (Circle, unit)` | A second place to maintain per name ; the definition no longer says whether it is shared. Could be added later as sugar. |
| The module is the file's last expression (an `object ( … )`) | No new syntax at all, and it works today inside one file. But class definitions would have to be members of an object, the type checker cannot name the types of an object's members, and a script's value is otherwise unused : it would silently change what the last line of every file means. |
| Convention on names (`_hidden`) | Not checkable, and unlike the rest of the language. |

## 4. Importing

### 4.1 The `import` function

`import(name)` keeps its one-argument form :

* `name` ends with `.ba` : the module of that file (§5 to §7) ;
* otherwise : the root Java package of that name, as today (`import("org")`).

Its French alias `importe` follows. The result is an ordinary value : it can be bound, passed
around, or used on the spot (`import("shapes.ba").unit`).

There is no selective import in this design. A file gives local names to what it uses most :

```
def shapes = import("shapes.ba")
def Circle = shapes.Circle
```

### 4.2 Static and dynamic imports

The argument may be any expression, but the analysis only understands one form, the same one
it already understands for Java packages (`def org = import("org")`) :

> **A static import** is a call of the implicit `import` with a single string literal ending
> in `.ba`.

A static import is resolved when the importing file is compiled (§8). Any other call
(`import(dir + "/plugin.ba")`) is a **dynamic import** : resolved when it runs, and typed
`dynamic`. Dynamic imports are how a program loads plugins ; they cost nothing to support, as
the runtime path has to exist anyway.

If a script shadows `import` with its own definition, calls to it are ordinary calls.

### 4.3 Alternatives considered

| Alternative | Why not |
|---|---|
| A declaration : `import shapes from "shapes.ba"`, `import (Circle, unit) from "shapes.ba"` | Clearly static, and selective. But it is a statement in a language where everything is an expression, it needs two more keywords, and it duplicates what `def` plus the function do. The literal-argument rule gives the same static knowledge with the syntax that exists. Selective import is better solved once, for every value with members (Q3). |
| A separate native, `module("shapes.ba")` | One more name for the same idea ; `import` is already the word for "bring something from outside". |
| Module names instead of paths : `import("shapes")`, found on a search path | Needs a search path, a mapping from names to files and a rule for clashes with Java packages. Paths need none of it. A search path can be added later without changing files that use relative paths (Q5). |
| Importing copies the exports into the importer's scope (`import("shapes.ba")` defines `Circle`) | Names appear in a scope without a `def` : the analysis, which hoists and checks every name, could not see them for a dynamic import, and readers could not tell where a name comes from. |

## 5. Resolving a path

1. An absolute path is used as it is.
2. A relative path is resolved against **the directory of the file containing the call**. This
   is static per call site : a function exported by `lib/loader.ba` that calls
   `import("data.ba")` reads `lib/data.ba` whoever calls it.
3. The file containing the call is known by its source name. For the main script that is the
   `ScriptEngine.FILENAME` attribute of the context ; a host that evaluates a string without
   setting it gets paths relative to the working directory.
4. The path is normalised, then made real (`toRealPath`, which follows symbolic links) : the
   real path is the module's identity. Two spellings of one file are one module.
5. The separator is `/` in source. `..` is allowed.

The extension is required. It is what tells a file from a Java package, and it keeps the door
open for importing other things by extension later.

A host that keeps its scripts elsewhere than in files (a database, the class path, a jar) needs
to supply the source of a module itself : see Q6.

## 6. What a module is at runtime

### 6.1 The module value

`import` returns a `runtime.Module`, which is a `HasMembers` like instances and class values :

* `m.x` reads the export `x`. A name that is not exported, defined or not, fails with
  `No such export : x in lib/shapes.ba`.
* `m.x = v` fails with `Cannot assign to an export : x`. Exports are **read-only from outside**.
* A read gives the **current** value : if the module reassigns an exported name, importers see
  the new value. A module that wants to let others change its state exports a function.
* Identity : importing the same file twice in one eval gives the same value (`same(a, b)`).
  `==` is identity. It prints as `module lib/shapes.ba`.
* It is not a package and not an object : `m is T` is false for every `T`, it cannot be
  reopened, and it has no `this`.

### 6.2 Scope

A module runs on a root namespace of its own, built exactly like a script's : the engine's
implicits (`print`, `assert`, `import`, `java`, …), `STDOUT`, the context's ENGINE_SCOPE
bindings, then the file's own definitions.

So a module sees what the host gave every script, and nothing of the scripts that import it.
Sharing the bindings is deliberate : they are how a host injects its services, and a module
that could not reach them would be useless in an embedded setting. They are copied into each
root, as they are for a script, so a module rebinding one does not affect the others (Q4).

Output goes to the same context writer, errors and warnings to the same error writer.

### 6.3 Lifetime

A file runs **once per eval**, the first time it is imported. The modules of an eval live in
its `ScriptRuntime` ; the runtimes of the modules share that table. A second `eval` of the main
script starts with no modules, as it starts with fresh namespaces : `CompiledScript.eval`
keeps meaning "run this from scratch".

What is cached longer is the compilation, not the state (§9).

A module that fails while loading is removed from the table. Importing it again in the same
eval runs it again.

Concurrency : loading takes a lock on the eval's module table, so two threads importing one
file get one module, and the second waits for the first to finish loading.

## 7. Cycles

Files may import each other. A module is registered **before** its file runs, so a cycle gets
the module being loaded rather than loading it again :

```
;; even.ba
def odd = import("odd.ba")
export def isEven = (n) -> ( if n == 0 then ( true ) else ( odd.isOdd(n - 1) ) )
```

```
;; odd.ba
def even = import("even.ba")
export def isOdd = (n) -> ( if n == 0 then ( false ) else ( even.isEven(n - 1) ) )
```

This works because the exports are read when the functions are called, after both files ran.
What a cycle cannot do is read an export before its definition has run :

```
;; b.ba, imported by a.ba, which is still loading
def a = import("a.ba")
export def name = "b" + a.name       ;; Name used before definition : name
```

That is the rule block scope already has for a name used before its `def`, with the same
message. No separate cycle detection is needed, and none is done.

## 8. Analysis and types

### 8.1 What the analysis does with a static import

When `BlockScopes` meets a static import (§4.2) it asks the engine for the **interface** of the
file : its exported names and, when the type checker is on, the type of each. Getting it means
parsing and analysing the imported file, which in turn may have static imports.

* A missing file is an error of the importing script, at the line of the import :
  `Cannot import shapes.ba : no such file`. So is a file that does not parse or fails its own
  analysis, reported with its own file and line.
* For `def shapes = import("shapes.ba")` where `shapes` is never reassigned, `shapes.nope` is
  reported like a missing member : `No such export : nope in shapes.ba`. It is a warning with
  the checker off or in `warn` mode, like the "bound to fail" diagnostics of type-system.md §4,
  and an error in `error` mode. The same goes for `m.x = v` : `Cannot assign to an export : x`. The receiver is known to be
  a module when it is a static import, a never-reassigned def holding one, or an export of
  such a module that holds one in turn.
* `implements`, `extends` and `super(I)` accept an interface exported by a statically imported
  file : the analysis resolves them statically (object-model.md), so it needs the imported
  interface's resolved parents and defaults. The path may go through a def holding the module
  (`implements shapes.Shape`), a def holding the export (`def Shape = shapes.Shape`), an export
  that is itself an import or a re-export, or an exported package (`implements lib.geo.Solid`).
  Only exported names are reachable. Completeness, default conflicts and `super` resolution then
  work as for a local interface. In M1 the analysis did not have the imported interface, and
  splitting the ray tracers ran into it : their interfaces had to stay in the same file as the
  classes implementing them.
* Imports run in the importing file's analysis after its pass 1 and before `TypeChecks`, so an
  import anywhere in a file, inside a function included, counts. A script that defines its own
  `import` calls that one : its calls are not imports.
* Cycles : the interface of a file being analysed is requested again. Its exported names are
  known after pass 1, so the names are answered, and `No such export` is reported inside a
  cycle too. Its interfaces are not, since its `TypeChecks` have not run : implementing one
  fails with `implements needs a statically known interface : a.ba is still being analysed (an
  import cycle)`. A cycle's interfaces go in a file outside the cycle.

The analysis of a file is cached with its compilation (§9), so a file imported by ten others
is analysed once.

### 8.2 Types

New type : `Type.Module(interface)`, with the exports as members.

* The type of an export is its declared type, or what the checker infers for a never-reassigned
  unannotated def, exactly as within a file.
* An exported class or interface is a type : `def c : shapes.Circle` and, after
  `def Circle = shapes.Circle`, `def c : Circle`. Both name the nominal type of the class
  expression in `shapes.ba`. Nominal identity is by class expression, and a file runs once per
  eval, so the static type and the runtime class agree.
* In `error` mode the runtime check of such an annotation uses the class's id, which is unique
  per class expression in the JVM (type-system.md §5). The descriptor of the importing file
  therefore needs the id that the imported file's compilation gave the class, which is one more
  reason to cache compilations per engine (§9).
* A dynamic import is `dynamic`. So is a module in an import cycle, to the files of the cycle
  analysed before it : its own types are not computed yet.

How it is done : each file keeps its own checker, and is typed by it alone. A static import's
type is `Type.Module`, and the module's interface carries the imported file's checker once it
ran. When the importing file's checker needs the type of something another file defines (an
export, a member of an imported class, a constructor's parameters, a method's signature), it
asks that file's checker, which answers from what it computed when it checked its own file.
A checker serves every file that imports its file, one question at a time. So types cross
files without any file being checked twice, and diagnostics are reported once, by the file they
are in.

One limit : a compiled script refers to the class ids of the modules it was analysed against.
If a module file changes between the compilation of a script and its eval, the eval imports the
recompiled module, whose classes have new ids, and an `error` mode check of an annotation naming
one of them fails, with `expected C, got C, from another class expression of that name (a
module that changed since the script was compiled ?)`. Compiling again fixes it ; pinning a
compiled script to the module versions it was analysed against would avoid it, if it ever
matters.

### 8.3 Compiler

Nothing in M1 : the file scope keeps a namespace in compiled code (bytecode-compiler.md §4),
exports are read from it, and `m.x` is an `invokedynamic` selection with an inline cache like
any other.

Later, for a static import bound to a never-reassigned def, `m.x` can be compiled as a
constant call site bound to the export's slot, and `m.f(…)` as a guarded direct call. This is
an optimisation with no change of semantics, worth doing when a benchmark asks for it.

One case did not wait : split into modules, the argon ray tracer rendered in 71 s instead of
49 s in compiled mode. The first guess, that module members such as `math.sqrt` took a slower
path than package members, was wrong : a micro-benchmark calls through either just as fast.
The cause was the inline cache of member selections (`obj.x`, `obj.m`) : on an instance of a
compiled class, it reads the member's field directly, but it looked the field up with the
calling class's access. The classes of each file are loaded by a class loader of their own,
and are package-private, so a file using a class another file compiled could not reach its
fields : the selection went to the generic path for good. Every `Vec3` the ray tracer used
outside `vec3.ba` paid for it. The cache now looks the field up with access to the instance's
own class (`MethodHandles.privateLookupIn`), and the split ray tracer renders as fast as the
single file did (60 s and 67 s, on the same machine).

## 9. Compilation cache

Compiling a file is much more expensive than running most of them, and a host that evals a
script repeatedly would recompile its modules every time. The engine keeps a cache :

* key : real path ; validity : the mode and typecheck setting, and the content of every file
  the analysis read : the file itself and, transitively, the files it imports statically ;
* value : the compiled script (either mode) and its interface ;
* so invalidation follows dependencies : a file implementing an interface of another is
  re-analysed when that one changes, and fails if it no longer implements it.

Content rather than last-modified time and size : a file rewritten within the same
millisecond with the same length is common in tests and editors, and these files are small.
Both static and runtime imports go through the cache, which a lock guards : an engine compiles
one module at a time.

A cached compilation carries no state : compiled classes hold none
(`CompiledBabbleScript`), and neither does a parse tree. State lives in the namespaces of an
eval.

## 10. Errors

* Loading : `Cannot import x.ba : no such file : /abs/x.ba`. For a static import, it is an error
  of the importing script at the line of the import, found before anything runs ; so is a
  module that does not parse or fails its analysis, with the module's own file and line in
  the message. A dynamic import fails when it runs.
* An error while a module runs is reported with the module's file and line, and the importing
  script then adds its own, which reads as a trace :
  `…BabbleException: Not a number : null in lib/shapes.ba at line number 12 in main.ba at line number 3`.
  The `ScriptException`'s file name and line number are those of the main script, since that is
  the script the host evaluated.
* Analysis warnings and type diagnostics of a module go to the error writer with the module's
  file name. In `error` mode a diagnostic in a module fails the compilation of the script that
  statically imports it.

## 11. Host API and ahead-of-time compilation

* `ScriptEngine.FILENAME` names the main script (§5). The launchers set it.
* **Module sources** (Q6) : an engine attribute holding a function from a resolved path to a
  `Reader`, defaulting to the file system. It is what a host needs to serve modules from a
  database, tests need to avoid temporary files, and the AOT compiler needs to follow imports.
* **AOT** (bytecode-compiler.md, phase 4) : a jar contains one script class per file. Static
  imports are known at compile time, so the jar is closed over them, and `import` of a static
  path finds the class in the jar instead of a file. Dynamic imports keep reading files at
  runtime, or fail in a jar-only deployment. This is the main practical argument for making
  static imports recognisable (§4.2).
* **Security** : `import` reads any file the process can read. Scripts can already do that
  through `java.io`, so modules add no capability ; a host that sandboxes scripts must control
  both, and the module source function is where it controls this one.

## 12. Steps

| Step | Content | State |
|---|---|---|
| **M1** | `export`, dynamic `import("file.ba")`, the module value, own root, once per eval, cycles, errors, relative paths. Modules are `dynamic` for the checker ; a type named through a module is accepted and unchecked. | Done, with tests in both modes. |
| **M2** | Static imports : the analysis reads imported files (§8.1), missing files and exports are found before running, and classes implement imported interfaces. Compilation cache (§9). | Done, with tests in both modes and in sessions. |
| **M3** | `Type.Module` : types across files, nominal types of exported classes, runtime checks in `error` mode (§8.2). | Done, with tests in both modes. |
| **M4** | Module sources for hosts (§11). Designed with M5 in [module-sources-and-aot.md](module-sources-and-aot.md). | |
| **M5** | AOT : a jar closed over static imports. Designed in [module-sources-and-aot.md](module-sources-and-aot.md). | |

Each step leaves both modes passing the whole suite. Both ray tracers, `examples/raytracer-fable`
and `examples/raytracer-argon`, were split into files after M1 as the acceptance test of the
feature, each interface sharing a file with the classes implementing it. After M2 they no
longer have to : the fable one has the files of the book (`hittable.ba`, `sphere.ba`,
`hittable_list.ba`, …), and the argon one separates its interfaces, materials and hittables.
The argon one is typed throughout : after M3 its types hold across its files, and it checks
cleanly in `error` mode, with its runtime checks. The fable one is not typed : annotating it is
left as an exercise.

## 13. Decisions

These were open questions, each with a recommendation. All were decided as recommended, in the
review of the pull request that brought this document ; the rest of the document assumes them.

**Q1. `export` modifier, or one of the alternatives of §3.2 ?**
Decided : the modifier.

**Q2. Keep the `import` function for files, or add an import declaration (§4.3) ?**
Decided : the function, with the literal-argument rule for static imports. The risk is
that "static if the argument is a literal" is a rule people have to learn ; the precedent is
that Java package imports already work this way.

**Q3. Selective import.**
`def shapes = import(…)` followed by one `def` per name is verbose for a file that uses ten
names of another. Options : (a) nothing ; (b) a destructuring `def (Circle, unit) = expr`,
which binds the named members of any value with members, modules included ; (c) an import
declaration. Decided : (a) now, (b) as its own small proposal, because it is useful beyond
modules and keeps `def` the only way to define a name. It is on the planned features of the
README.

**Q4. Do modules see the host's bindings ?**
Decided : yes (§6.2). The alternative, modules that only see the implicits, is purer but
forces hosts to thread their services through every call.

**Q5. A search path, and a standard library.**
Decided : not now. When it comes, a path that does not start with `.` or `/` and is not
found next to the importer could be looked up in a list of roots (`babble.path`), and a
`std/…` prefix reserved for modules shipped in the jar.

**Q6. Module sources : in M1 or M4 ?**
Decided : M4. It is an API to get right once the needs of AOT are clear.

**Q7. Should exports be assignable from outside ?**
Decided : no. Read-only exports are what lets the checker trust the type of
`shapes.Circle`, and the compiler bind it.

**Q8. Per-eval or per-engine module state ?**
Decided : per eval (§6.3). Per-engine state would make a second eval of a script observe
what the first did to its modules, which no other part of the engine does.

## 14. Testing

* `BabbleModulesTestCase` : files written to a temporary directory, one behaviour per test :
  visibility, read-only exports, once per eval, relative paths, imports inside functions,
  cycles, early reads in cycles, error wording, failed loads, misplaced `export`, exported
  packages and classes, the type checker's settings, Java packages still imported.
* `src/test/babble/Modules.ba` and `src/test/babble/modules/` : run by `ScriptTestCase` and
  by `DifferentialTestCase` in both modes, with and without enforced types.
* M2 adds, to `BabbleModulesTestCase` : missing and broken files found before running, dynamic
  imports still resolved at runtime, classes, objects and interfaces implementing or extending
  imported interfaces (through defs, re-exports and exported packages), `super(I)` with an
  imported qualifier, unexported and cyclic interfaces rejected, missing exports and assignments
  to exports as warnings and as errors in `error` mode, a script's own `import`, and the cache :
  one analysis per file until it or a dependency changes. `ReplTestCase` checks static imports
  across the inputs of a session.
* M3 adds, to `BabbleModulesTestCase`, golden tests of type diagnostics across files (exports'
  types, imported classes' constructors, members and methods, imported classes and interfaces as
  types, re-exports, cycles), and runtime checks of imported types in `error` mode, in both
  modes.
