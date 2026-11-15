package org.babblelang.engine;

/**
 * A module's identity, as a {@link ModuleSource} gives it
 * (docs/design/module-sources-and-aot.md §4.1).
 *
 * @param key         what tells modules apart : the engine's cache, an eval's module table and
 *                    cycle detection compare keys, so two spellings of one module must resolve
 *                    to the same key
 * @param displayName what messages, stack traces and the module value call it
 */
public record ModuleId(String key, String displayName) {
}
