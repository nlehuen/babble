package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.BabbleInstance;
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
                def Ordered = interface extends java.lang.Comparable(Ordered) (
                    def key : () : int
                    def compareTo = (o : Ordered) : int -> ( this.key() - o.key() )
                )
                def Node = class (def k : int) implements Ordered, java.util.function.IntUnaryOperator, java.util.Iterator(int) (
                    private def used = false
                    def key = () : int -> ( this.k )
                    def applyAsInt = (x : int) : int -> ( x + this.k )
                    def hasNext = () : bool -> ( not this.used )
                    def next = mut () : int -> ( this.used = true ; this.k )
                )
                Node(7)
                """);
        assertVerifies(script);

        Object result = script.eval(new SimpleBindings());
        Assertions.assertInstanceOf(BabbleInstance.class, result);
        BabbleInstance inst = (BabbleInstance) result;

        // Zero Proxy allocation: face() returns the compiled BabbleInstance itself.
        Assertions.assertSame(inst, inst.face());
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
