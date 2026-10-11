package org.babblelang.engine.impl.natives;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaPackage;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.BabbleInstance;
import org.babblelang.runtime.BabbleInterface;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaValue;
import org.babblelang.runtime.ScriptRuntime;

/**
 * {@code classof(v)} : the class value that created a Babble instance, or the Java class
 * of a Java value. It is not privileged : private statics stay with the static block.
 */
public class ClassofFunction implements Callable {
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException("classof() takes 1 argument, " + args.length + " given");
        }
        Object value = args[0];
        if (value == null) {
            return null;
        }
        if (value instanceof BabbleInstance instance) {
            return instance.babbleClass();
        }
        if (value instanceof JavaValue wrapper) {
            return JavaClass.of(wrapper.javaValue().getClass());
        }
        // These have no class : their representation differs between modes.
        if (value instanceof BabbleClass || value instanceof JavaClass) {
            throw new BabbleException("classof : a class has no class");
        }
        if (value instanceof BabbleInterface) {
            throw new BabbleException("classof : an interface has no class");
        }
        if (value instanceof Namespace || value instanceof JavaPackage) {
            throw new BabbleException("classof : a package has no class");
        }
        if (value instanceof Callable) {
            throw new BabbleException("classof : a function has no class");
        }
        return JavaClass.of(value.getClass());
    }
}
