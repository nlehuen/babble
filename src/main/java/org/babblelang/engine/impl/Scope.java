package org.babblelang.engine.impl;

public interface Scope {
    boolean isDeclared(String key);

    Slot define(String key, boolean isFinal);

    Slot get(String key);
}
