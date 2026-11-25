package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.BabbleObjects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import javax.script.SimpleBindings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.function.IntUnaryOperator;

/**
 * Verification tests for RM003 Phase 3: Babble classes implementing Java interfaces
 * and Babble interfaces extending Java interfaces directly on the JVM without
 * {@link Proxy} wrappers.
 */
public class Rm003P3JavaImplementsTestCase {

    private static CompiledBabbleScript compile(String source) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        return (CompiledBabbleScript) engine.compile(source);
    }

    private static void assertVerifies(CompiledBabbleScript script) {
        for (byte[] classFile : script.getClasses().values()) {
            StringWriter problems = new StringWriter();
            CheckClassAdapter.verify(new ClassReader(classFile), script.getScriptClass().getClassLoader(), false, new PrintWriter(problems));
            Assertions.assertEquals("", problems.toString());
        }
    }

    @Test
    public void testCompiledInstancesImplementJavaInterfacesDirectlyWithoutProxy() throws Exception {
        CompiledBabbleScript script = compile("""
                def Ordered = interface extends java.lang.Comparable of Ordered (
                    def key : fn () : int
                    def compareTo = fn (o : Ordered) : int -> ( this.key() - o.key() )
                )
                def Node = class (def k : int) implements Ordered, java.util.function.IntUnaryOperator, java.util.Iterator of int (
                    private var used = false
                    def key = fn () : int -> ( this.k )
                    def applyAsInt = fn (x : int) : int -> ( x + this.k )
                    def hasNext = fn () : bool -> ( not this.used )
                    def next = fn mut () : int -> ( this.used = true ; this.k )
                )
                Node(7)
                """);
        assertVerifies(script);

        Object inst = script.eval(new SimpleBindings());
        Assertions.assertTrue(BabbleObjects.isCompiledInstance(inst));
        Assertions.assertSame(Object.class, inst.getClass().getSuperclass());

        // Zero Proxy allocation: face() returns the compiled instance itself.
        Assertions.assertSame(inst, BabbleObjects.face(inst));
        Assertions.assertFalse(Proxy.isProxyClass(inst.getClass()));
        Assertions.assertInstanceOf(Comparable.class, inst);
        Assertions.assertInstanceOf(IntUnaryOperator.class, inst);
        Assertions.assertInstanceOf(Iterator.class, inst);

        // Generated Babble JVM interface directly extends java.lang.Comparable.
        ClassLoader loader = script.getScriptClass().getClassLoader();
        String orderedInternal = script.interfaceJvmNames().values().iterator().next();
        Class<?> orderedCls = loader.loadClass(orderedInternal.replace('/', '.'));
        Assertions.assertTrue(orderedCls.isInterface());
        Assertions.assertTrue(Comparable.class.isAssignableFrom(orderedCls));
        Assertions.assertTrue(orderedCls.isInstance(inst));

        // Direct Java interface calls work on the instance without reflection or Proxy.
        Assertions.assertEquals(17, ((IntUnaryOperator) inst).applyAsInt(10));
        Iterator<?> it = (Iterator<?>) inst;
        Assertions.assertTrue(it.hasNext());
        Assertions.assertEquals(7, it.next());
        Assertions.assertFalse(it.hasNext());
    }
}
