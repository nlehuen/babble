# Design: module sources and ahead-of-time compilation

| | |
|---|---|
| **Status** | Accepted : the questions of §10 were decided as recommended. Covers steps M4 and M5 of [modules.md](modules.md) (§11, §12), designed together because M5 is the main user of M4's API. R1 (§3) is implemented ; M4 and M5 are not. |
| **Depends on** | [modules.md](modules.md) (static imports, the module cache, module types), [bytecode-compiler.md](bytecode-compiler.md) (phase 4, the class layout, `Bootstrap`) |
| **Unblocks** | Babble programs shipped as a jar, hosts serving modules from anywhere, module tests without temporary files |

## 1. Summary

Two features, one seam :

* **M4, module sources.** Where an imported module's source text comes from becomes a host's
  choice : a `ModuleSource` resolves `import("x.ba")` to a module identity, and reads that
  module's text and version. The file system is the default ; a map in memory, the class path
  or a database are others.
* **M5, ahead-of-time compilation.** `babblec` compiles a program and the modules it imports
  statically into a jar : one class per file, an index, a launcher, and the Babble runtime.
  `java -jar app.jar` runs it without parsing, analysing or generating anything.

Both rest on a split the code does not make yet, between what **compiles** Babble (the parser,
the analysis, the compiler, the interpreter, ANTLR, ASM) and what **runs** compiled Babble (the
runtime). §2 measures that split ; §3 makes it explicit ; §4 and §5 build on it.

## 2. What the Babble runtime contains

The question that sizes M5 : once a program is compiled, what does it need that the JRE does
not have ?

### 2.1 Method

1. Compile every script of `src/test/babble/`, both ray tracers, and everything the test suite
   compiles, with `-Dbabble.dumpClasses` : 1 104 generated classes in all, which exercise the
   whole language (closures, classes, interfaces, value classes, modules, Java interop, typed
   code in error mode).
2. `jdeps -filter:none -verbose:class` on those classes : the Babble and JDK classes they
   reference directly. (`-filter:none` matters : by default `jdeps` hides dependencies within a
   package, and a first count without it missed half the runtime.)
3. The transitive closure of those, over the class graph of `target/classes` (also `jdeps`),
   plus the built-ins every script gets (`print`, `println`, `assert`, `import`, `same`,
   `classof`, the `java` and `javax` packages). Counts include nested classes.

### 2.2 Findings

* **Generated code references 32 Babble classes**, all but two in `org.babblelang.runtime`
  (`Operators`, `Members`, `Arguments`, `Calls`, `Bootstrap`, `Types`, `ClassShape`,
  `BabbleClass`, `BabbleInstance`, `CompiledFunction`, `Cell`, …), plus `BabbleException` and
  `Namespace`. From the JDK it uses `java.base` only : `java.lang`, `java.lang.invoke`
  (`invokedynamic` bootstraps, `LambdaMetafactory`).
* **The runtime, closed over, is 66 classes and 192 KB** : 52 classes of the `runtime`
  package, the 30 generated code names and those they use (`Faces`, `Overloads`, `JavaTypes`,
  `BoundMethod`, `Module`, …) ; `Namespace`, `Scope`, `Slot` and `BabbleException` ; the four
  native functions ; and Java interop's `ImportFunction`, `JavaPackage`, `JavaClass`,
  `JavaMethod`, `BoundJavaMethod`, `JavaObject`. For comparison, all of Babble's classes are
  778 KB, and its compile-time libraries 632 KB more (ANTLR 319 KB, ASM and its extras 313 KB).
* **No third-party library is needed to run.** ANTLR and ASM are compile-time only.
* **Beyond `java.base`, only `java.scripting`** : `ScriptRuntime` holds a `ScriptContext`, whose
  writer `print` uses, and a `ScriptEngine`, which `import` uses. `java.scripting` is part of the
  standard JDK, but a program linked with `jlink` would have to add it, for those two fields.
* **One reference drags in everything else.** `ImportFunction` calls
  `BabbleScriptEngine.importModule`, and the engine reaches the parser, the four analysis
  passes, the interpreter, the compiler, ANTLR and ASM. Generated code reaches it even without
  calling `import` : `Bootstrap` knows `JavaPackage`, which knows its `ImportFunction`. With
  that edge, the closure is 178 Babble classes (760 KB) and both libraries ; without it, 66
  classes. It is the only edge from the runtime to the rest of Babble : cutting it, and the two
  `javax.script` fields, is the whole difference.

So the Babble runtime is small, and what it adds to the JRE is the language's semantics, not
infrastructure :

| What | Classes | Why the JRE doesn't have it |
|---|---|---|
| Operators and truth | `Operators`, `Truth`, `Values` | Babble's arithmetic (int wrap-around, int/double mixing), `==` across number types, what is true |
| Calls | `Arguments`, `Calls`, `Callable`, `CallSite`, `OpSite`, `Frames`, `Undefined` | named and optional parameters, the one binding algorithm both backends share, the source texts errors quote |
| Inline caches | `Bootstrap` and its call sites | the `invokedynamic` bootstraps generated code links against |
| Functions | `BabbleFunction`, `CompiledFunction`, `FunctionBody`, `BoundMethod`, `Cell` | function values, closures' captured cells, methods bound to receivers |
| Object model | `BabbleClass`, `BabbleInstance`, `BabbleInterface`, `BabbleType`, `ClassShape`, `Classes`, `Supers`, `Members`, `HasMembers`, `CompiledMethod`, `ClassBody`, `MethodBody`, `InstanceFactory` | classes as values, interfaces with defaults, value classes, privacy, `super` |
| Types | `Types` | the runtime checks of error mode, from string descriptors |
| Java interop | `JavaPackage`, `JavaClass`, `JavaMethod`, `BoundJavaMethod`, `JavaObject`, `JavaType`, `JavaValue`, `JavaDefaultCall`, `Overloads`, `JavaTypes`, `Faces` | Babble's overload resolution, functions as functional interfaces, instances as Java interfaces |
| Scopes and errors | `Namespace`, `Scope`, `Slot`, `BabbleException` | the file scope and packages, which compiled code keeps as namespaces ; the language's errors |
| Modules | `Module`, `ScriptRuntime` | the module value, and the per-eval state |
| Built-ins | `PrintFunction`, `AssertFunction`, `SameFunction`, `ClassofFunction`, `ImportFunction` | `print`, `assert`, `same`, `classof`, `import` |

Two runtime features matter beyond packaging. `Faces` uses `java.lang.reflect.Proxy`, and Java
interop uses reflection ; both are in `java.base`, but both need configuration for a GraalVM
native image (§9).

## 3. The runtime boundary

Before either feature, the split of §2 is made explicit, with no change of behaviour.

* **Built-ins move out of the engine.** The engine's constructor builds the implicits
  (`print`, `assert`, `import`, `java`, …). That moves to a runtime class, `Implicits`, which
  the engine and the AOT launcher both call.
* **`import` stops calling the engine.** `ScriptRuntime` gets a `Modules` field, a runtime
  interface with one method : the module an import names, from the module that imports it.

  ```java
  public interface Modules {
      Module load(String name, String importer, ScriptRuntime rt);
  }
  ```

  The importer is the importing file's source name, as `import` resolves relative names
  today ; M4 turns it into a `ModuleId` (§4.1).

  `ImportFunction` calls it for names ending in `.ba`. The engine implements it by compiling
  (today's `importModule`, through its cache) ; the AOT launcher implements it from the jar's
  index (§5.4). Neither the runtime nor the jar sees how the other does it.
* **`javax.script` leaves the runtime.** `ScriptRuntime` holds the writer `print` uses, not a
  `ScriptContext`, and the `Modules`, not a `ScriptEngine`. The engine adapts its context when it
  creates a runtime. The runtime then needs `java.base` alone.
* **`Namespace`, `Scope` and `Slot` move to the runtime package** : their package,
  `engine.impl`, also holds the interpreter. The built-ins keep their packages,
  `engine.impl.natives` and `engine.impl.natives.java`, which hold nothing else, and
  `BabbleException` stays in `engine` : its name is part of every error message
  (`org.babblelang.engine.BabbleException: …`), which hosts and tests read. The runtime is
  those packages and that class.
* **A test keeps the boundary.** `RuntimeBoundaryTestCase` reads the constant pool of every
  runtime class file and fails on a reference to the parser, the analysis, the compiler, the
  interpreter, the engine, a library, or a JDK package outside `java.base`. Run against the code
  before R1, it reports exactly the edges R1 removes. Without it,
  the next convenience import puts the compiler back into every jar.
* **A runtime jar.** The build also produces `babble-<version>-runtime.jar`, the runtime
  alone (68 classes, 100 KB), with a classifier : a second artifact of the same module, rather
  than a Maven multi-module build (Q1). A script compiled by the engine runs with that jar alone
  on the class path.
* **Sessions keep their modules.** An interactive session keeps one `Modules` for its
  lifetime, as one long eval : a module imported by several inputs is loaded once. Before R1,
  each input loaded its modules afresh.

## 4. M4 : module sources

### 4.1 The API

```java
public interface ModuleSource {
    /** The module an import names, from the module importing it, or null when there is none. */
    ModuleId resolve(String name, ModuleId importer) throws IOException;

    /** The text of a module, and its version. */
    Source read(ModuleId module) throws IOException;

    /** The current version of a module, to tell whether a compilation of it is stale. */
    default String version(ModuleId module) throws IOException {
        return read(module).version();
    }
}

public record ModuleId(String key, String displayName) { }
public record Source(String text, String version) { }
```

* The engine attribute `babble.moduleSource` holds one ; the default is the file system.
* **The source resolves names** (Q7). It gets the name as written and the importer's identity,
  not a path already resolved against a directory : a database has no directories, and a
  host may want its own rules (a search path, a `std/` prefix for modules it ships).
* **A module is its key.** `ModuleId.key` is what the engine's cache, the per-eval module table
  and cycle detection compare : two spellings of one module must resolve to the same key. The
  display name is what messages show.
* **A version is opaque.** The cache compares the versions of every module an analysis read,
  as it compares file contents today (modules.md §9) : a content hash for files, a row version
  or timestamp for a database. `version` may be cheaper than `read`.
* **The main script has an identity too.** Today it is its `ScriptEngine.FILENAME`. The engine
  asks the source to resolve that name with no importer, and a source that does not know it
  gives none : the script then imports as today, relative to the working directory for files.

### 4.2 Sources provided

* `FileModuleSource`, the default : today's behaviour. Keys are real paths (`file:/abs/a.ba`),
  display names the path as it was resolved, versions content hashes, and relative names
  resolve against the importer's directory.
* `FileModuleSource(root)` : keys and display names relative to a root directory
  (`lib/vec3.ba`), and imports cannot leave it. It is what `babblec` uses (§5.2), and what a host
  sandboxing imports would use.
* `MapModuleSource` : modules given as a map from logical path to text, `/`-separated, relative
  names resolved against the importer's directory within the map. Tests use it instead of
  temporary files ; hosts use it for modules they generate or keep in memory.
* `ClassPathModuleSource(loader, prefix)` : modules shipped as resources, for libraries in jars.

### 4.3 What changes in the engine

The four places the engine assumes files (resolving against a directory, `toRealPath` as the
identity, `Files.readString`, freshness by re-reading) go through the source. `CallSite`'s
source name stays a display name ; the importer's identity is a `ModuleId` the compiled module
carries, so `import` in a function resolves against the module the function is in, as now.
Errors : `Cannot import x.ba : no such module` when `resolve` returns null, with the file
source keeping `no such file : /abs/x.ba`.

## 5. M5 : ahead-of-time compilation

### 5.1 What it produces

```
java -jar babble.jar -compile examples/raytracer-argon/raytracer.ba -o argon.jar
java -jar argon.jar > scene.ppm
```

`argon.jar` holds :

* one class (and its nested classes) per module of the program : the main script and every
  module it imports statically, transitively ;
* `babble/Modules`, an index from module keys to classes and exports (§5.4) ;
* the runtime classes of §3, copied in (Q3), so the jar runs on a plain JRE ;
* a manifest with `Main-Class: org.babblelang.runtime.Launcher` and the main module's key.

Nothing in it parses, analyses or generates code. ANTLR and ASM are not in it.

### 5.2 Stable names

The compiler names things from counters that restart with each JVM : that is fine when the
classes live and die with one run, not in a jar.

* **Class names** come from the module key : `lib/vec3.ba` becomes `babble/lib/vec3$ba`, its
  class expressions `babble/lib/vec3$ba$k$Vec3` as today. Two modules never share a name, and
  compiling twice gives the same names.
* **Type ids** (`T17`, from `TypeNode`'s JVM-wide counter) become the module key and the class
  expression's index in its file : `lib/vec3.ba#0`. They are embedded in class shapes and in the
  descriptors of error mode, so classes compiled separately must agree on them. The engine can
  use the same scheme, which also makes the stale-module message of modules.md §8.2 rarer.
* **Source names** in `CallSite`s and the `SourceFile` attribute are display names relative to
  the program root (`FileModuleSource(root)`), not the build machine's absolute paths : error
  messages and stack traces read the same on every machine.

### 5.3 The launcher

`org.babblelang.runtime.Launcher`, in the runtime : reads the main module from the manifest,
builds the implicits (§3), binds `args` as `Main` does, creates a `ScriptRuntime` whose writer is
`System.out` and whose `Modules` is the jar's index, runs the main class, and exits like `Main` :
1 when the script fails, with the message on standard error.

### 5.4 Imports in a compiled program

* **Static imports** were resolved by `babblec` : the index maps each module key to its class,
  exports and the keys its own imports resolved to. `import` looks the key up : nothing is read.
* **Dynamic imports** (`import(name + ".ba")`) cannot be known before running (Q4). In the jar,
  `Modules` looks for the name in the index first, so a dynamic import of a module the program
  also imports statically works. Otherwise it asks `ServiceLoader` for a module compiler, which
  the full Babble jar provides : with Babble on the class path, the module is compiled from the
  file system as today ; without it, the import fails with
  `Cannot import x.ba : a compiled program only has the modules it imports statically`.
* **Once per eval, cycles, read-only exports** : unchanged, they are the runtime's `Module` and
  the per-eval table, which do not care where a module's class came from.

### 5.5 Types

`babblec` analyses with the engine's `babble.typecheck` setting (`off` by default ; Q6). In error
mode, the runtime checks are already in the classes, as descriptors : nothing of the analysis is
needed at runtime. A type error found by the analysis fails the compilation, as `compile()` does.

### 5.6 What is not compiled ahead of time

* The interpreter : it walks parse trees, which a jar no longer has.
* Interactive sessions : each input is compiled when it is typed.
* Modules imported only dynamically, unless a compiler is present (§5.4).

## 6. How M4 and M5 fit

* The `Modules` interface (§3) is the runtime's side ; `ModuleSource` (§4) is the compiler's.
  The engine joins them : its `Modules` compiles what its `ModuleSource` reads. The launcher's
  `Modules` reads compiled classes, and needs no source at all.
* `babblec` is the engine with a `FileModuleSource(root)` and a class writer instead of a class
  loader : it compiles the main script, and through static imports every module, as the engine
  does today, then writes what it compiled instead of loading it.
* Logical module keys (§4.2) are what make class names, type ids and messages portable (§5.2).

## 7. Steps

| Step | Content | State |
|---|---|---|
| **R1** | The runtime boundary (§3) : `Implicits`, `Modules`, `ScriptRuntime` without `javax.script`, the moves, the boundary test, the runtime jar. No change of behaviour but sessions loading a module once. | Done. |
| **M4a** | `ModuleSource`, `ModuleId`, `FileModuleSource` as the default ; the engine's cache keyed by `ModuleId` and versions. | |
| **M4b** | `MapModuleSource`, `ClassPathModuleSource`, `FileModuleSource(root)` ; module tests moved to `MapModuleSource` where files are incidental. | |
| **M5a** | Stable class names, type ids and source names (§5.2), in the engine too. | |
| **M5b** | `babblec` (`-compile … -o …`), the index, the launcher, the runtime copied in. | |
| **M5c** | Dynamic imports in a compiled program : the index, then a module compiler through `ServiceLoader`, else the error of §5.4. | |

Each step leaves both modes passing the whole suite. The acceptance test of M5 is both ray
tracers compiled into jars and run on a JRE without Babble on the class path : argon's
`scene.ppm` byte for byte, and the start-up time with and without `babblec`.

## 8. Testing

* The boundary test of §3, on every build.
* `MapModuleSource` makes module tests self-contained : `BabbleModulesTestCase` keeps a few
  file tests (real paths, symbolic links, `..`) and moves the rest to maps.
* A `babblec` test compiles a small program with modules into a jar in a temporary directory,
  runs it in a fresh JVM whose class path is the jar alone, and compares its output and exit
  status ; and checks that the jar contains no `org.antlr` or `org.objectweb` class.
* Determinism : compiling the same program twice gives byte-identical classes.

## 9. Non-goals

* A GraalVM native image. A compiled jar removes the main obstacle, defining classes at
  runtime, but Java interop needs reflection configuration, and `Faces` dynamic proxies : a
  later design.
* Incremental compilation in `babblec`, or a Maven plugin : the CLI first.
* Changing what a module is, or how imports bind (modules.md).

## 10. Decisions

These were open questions, each with a recommendation. All were decided as recommended ; the
rest of the document assumes them.

**Q1. One Maven module with a runtime classifier jar, or a multi-module build ?**
Decided : the classifier jar and the boundary test. A multi-module build enforces the
boundary by construction, but moves every file and every path in the docs for a guarantee the
test already gives.

**Q2. Remove `javax.script` from the runtime ?**
Decided : yes, in R1. It is two fields, and `java.base` alone is what makes a `jlink`ed or
native runtime simplest.

**Q3. Copy the runtime into each compiled jar, or reference the runtime jar from the manifest ?**
Decided : copy it. It is under 200 KB, and `java -jar app.jar` with nothing else is the point of
M5.

**Q4. Dynamic imports in a compiled program : fail, or compile when Babble is present ?**
Decided : the index first, then compile when a module compiler is on the class path, else
fail with a message that says why.

**Q5. Stable type ids in the engine too, or only in `babblec` ?**
Decided : in both. One scheme is simpler to test, and the engine's stale-module case gets
rarer.

**Q6. `babblec`'s type checking : the engine's setting, or error mode by default ?**
Decided : the engine's setting, off by default, like every other entry point. A program
built for distribution can pass `-Dbabble.typecheck=error`.

**Q7. What `ModuleSource` gets : the name as written, or a path already resolved ?**
Decided : the name as written, and the importer's identity. Resolution is part of where
modules come from.

**Q8. A `babblec` Maven goal ?**
Decided : not now ; later. A goal has to be an artifact of its own (packaging `maven-plugin`),
so it cannot be a classifier of the single module Q1 keeps. When it comes, it is a separate
`babble-maven-plugin` build depending on the babble artifact, or the reason to revisit Q1.
