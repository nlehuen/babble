package org.babblelang.engine.impl;

public interface Scope<T> {
    boolean isDeclared(String key);

    Slot<T> define(String key, boolean isFinal);

    Slot<T> get(String key);
}
