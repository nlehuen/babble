package org.babblelang.engine.impl;

public interface Resolver {
    boolean isDeclared(String key);

    Slot define(String key, boolean _final);

    Slot get(String key);
}
