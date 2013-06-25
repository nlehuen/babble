package org.babblelang.engine.impl;

public interface Resolver {
    boolean isDeclared(String key);

    Object get(String key);
}
