package org.babblelang.runtime;

import java.io.Writer;

/**
 * The per-eval state handed to every call : the root namespace of the running file, the
 * writer {@code print} writes to, and where imports get modules from. A module runs on a
 * runtime of its own, with its own root, that shares the writer and the modules of the eval
 * importing it.
 * <p>
 * It holds nothing of {@code javax.script} : the runtime needs {@code java.base} alone, so
 * that a compiled program runs without the engine (docs/design/module-sources-and-aot.md §3).
 *
 * @param modules null when the runtime can't import modules
 */
public record ScriptRuntime(Namespace root, Writer writer, Modules modules) {
}
