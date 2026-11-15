package org.babblelang.engine;

/**
 * The text of a module, and its version : an opaque string the engine compares to tell
 * whether a compilation of the module is stale.
 */
public record ModuleText(String text, String version) {
}
