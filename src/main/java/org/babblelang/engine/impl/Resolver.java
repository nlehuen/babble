package org.babblelang.engine.impl;

/**
 * Created with IntelliJ IDEA.
 * User: Nico
 * Date: 24/06/13
 * Time: 19:41
 * To change this template use File | Settings | File Templates.
 */
public interface Resolver {
    boolean isDeclared(String key);

    Object get(String key);
}
