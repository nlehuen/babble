package org.babblelang.axioms.java.lang.reflect;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.lang.reflect.Proxy}.
 */
@Axioms(value = java.lang.reflect.Proxy.class,
        inherited = false)
interface ProxyAxioms {
    @NonNull
    java.lang.Object getInvocationHandler(java.lang.Object p0);

    @NonNull
    java.lang.Object getProxyClass(java.lang.ClassLoader p0, java.lang.Class[] p1);

    @NonNull
    java.lang.Object newProxyInstance(java.lang.ClassLoader p0, java.lang.Class[] p1, java.lang.reflect.InvocationHandler p2);
}
