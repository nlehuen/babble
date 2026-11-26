/**
 * Built-in assumptions about Java and Babble functions, methods, classes, and third-party
 * nullability annotations ({@code docs/design/bip019_refactoring-axioms.md}).
 *
 * <p>Every assumption the interpreter, compiler, analysis, and optimizer make about specific Java
 * classes or Babble built-ins lives in this package: curated {@code curated.ba} files (one per Java
 * package), {@code builtins.ba} for the Babble prelude, and Java stubs only for what Babble cannot
 * state (the meaning of third-party nullability annotations). Everything else about Java classes is
 * inferred from their bytecode by the compiler, with curated axioms overriding inference.
 */
package org.babblelang.axioms;
