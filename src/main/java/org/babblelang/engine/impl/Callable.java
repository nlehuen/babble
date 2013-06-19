package org.babblelang.engine.impl;

/**
 * Created with IntelliJ IDEA.
 * User: Nico
 * Date: 19/06/13
 * Time: 10:31
 * To change this template use File | Settings | File Templates.
 */
public interface Callable {
    Scope bindParameters(Parameters parameters);

    Object call(Interpreter interpreter, Scope scope);
}
