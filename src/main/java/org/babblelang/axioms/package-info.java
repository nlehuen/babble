/**
 * Babble's axioms (BIP019) : everything the interpreter, the compiler, the analysis and the
 * optimizer assume about specific Java or Babble functions and methods, and nowhere else.
 *
 * <p>Java axioms are stubs annotated {@link org.babblelang.axioms.Axioms}, in
 * {@code org.babblelang.axioms.<the target's package>} and named {@code <Target>Axioms} ; the
 * analysis finds them by listing this package on the class path. {@code README.md}, next to this
 * file, lists them, with the axioms known to be doubtful (QUESTIONABLE).
 */
package org.babblelang.axioms;
