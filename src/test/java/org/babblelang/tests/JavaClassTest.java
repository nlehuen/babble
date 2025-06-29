package org.babblelang.tests;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Slot;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.impl.*;
import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaMethod;
import org.babblelang.engine.impl.natives.java.JavaObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

public class JavaClassTest extends BabbleTestBase {
    private JavaClass javaClassDemoClass;
    private JavaClass arrayListClass;
    private Interpreter interpreter;
    private Namespace globalNamespace;

    @Before
    public void setUp() {
        javaClassDemoClass = new JavaClass(JavaClassDemo.class);
        arrayListClass = new JavaClass(java.util.ArrayList.class);
        globalNamespace = new Namespace(); // Root namespace for the interpreter
        interpreter = new Interpreter(globalNamespace); // Corrected Interpreter instantiation
    }

    @Test
    public void testGetPublicStaticNestedClass() {
        Slot<Callable> internal1Slot = javaClassDemoClass.get("Internal1");
        Assert.assertTrue("Internal1 should be found", internal1Slot.isSet());
        Callable callable = internal1Slot.get();
        Assert.assertTrue("Internal1 should be a JavaClass instance", callable instanceof JavaClass);
        Assert.assertEquals(JavaClassDemo.Internal1.class, ((JavaClass) callable).getWrappedClass());
    }

    @Test
    public void testGetPublicMethod() {
        Slot<Callable> method1Slot = javaClassDemoClass.get("Method1");
        Assert.assertTrue("Method1 should be found", method1Slot.isSet());
        Assert.assertTrue("Method1 should be a JavaMethod instance", method1Slot.get() instanceof JavaMethod);
    }

    @Test(expected = BabbleException.class)
    public void testGetNonExistentMember() {
        javaClassDemoClass.get("NonExistentMember");
    }

    @Test(expected = BabbleException.class)
    public void testGetPrivateStaticNestedClass() {
        // Internal2 is private in JavaClassDemo
        javaClassDemoClass.get("Internal2");
    }

    @Test(expected = BabbleException.class)
    public void testGetPrivateMethod() {
        // Method2 is private in JavaClassDemo
        javaClassDemoClass.get("Method2");
    }

    @Test
    public void testIsDeclaredPublicMembers() {
        Assert.assertTrue("isDeclared should be true for public nested class Internal1", javaClassDemoClass.isDeclared("Internal1"));
        Assert.assertTrue("isDeclared should be true for public method Method1", javaClassDemoClass.isDeclared("Method1"));
        Assert.assertTrue("isDeclared should be true for ArrayList.size()", arrayListClass.isDeclared("size"));
    }

    @Test
    public void testIsDeclaredNonPublicOrNonExistentMembers() {
        Assert.assertFalse("isDeclared should be false for private nested class Internal2", javaClassDemoClass.isDeclared("Internal2"));
        Assert.assertFalse("isDeclared should be false for private method Method2", javaClassDemoClass.isDeclared("Method2"));
        Assert.assertFalse("isDeclared should be false for non-existent member", javaClassDemoClass.isDeclared("NonExistentMember"));
    }

    @Test(expected = BabbleException.class)
    public void testDefineNotAllowed() {
        javaClassDemoClass.define("newMember", false);
    }

    @Test
    public void testCallDefaultConstructor() {
        // Test ArrayList() constructor
        Parameters emptyParams = new Parameters(); // Use default constructor
        // bindParameters takes parent Namespace, not BabbleScriptEngineFactory
        Namespace constructorScope = arrayListClass.bindParameters(interpreter, null, globalNamespace, emptyParams);
        Assert.assertNotNull(constructorScope.get("constructor").get());

        Object result = arrayListClass.call(interpreter, null, constructorScope);
        Assert.assertTrue(result instanceof JavaObject);
        JavaObject javaObject = (JavaObject) result;
        Assert.assertTrue(javaObject.getValue() instanceof java.util.ArrayList); // Use getValue()
        Assert.assertTrue(((java.util.ArrayList<?>)javaObject.getValue()).isEmpty());  // Use getValue()
    }

    @Test
    public void testCallConstructorWithStringParameter() { // Renamed and changed
        JavaClass stringClass = new JavaClass(String.class);
        Parameters params = new Parameters();
        params.put("arg0", "hello");

        Namespace constructorScope = stringClass.bindParameters(interpreter, null, globalNamespace, params);
        Assert.assertNotNull(constructorScope.get("constructor").get());

        Object result = stringClass.call(interpreter, null, constructorScope);
        Assert.assertTrue(result instanceof JavaObject);
        JavaObject javaObject = (JavaObject) result;
        Assert.assertTrue(javaObject.getValue() instanceof String);
        Assert.assertEquals("hello", javaObject.getValue());
    }

    @Test(expected = BabbleException.class)
    public void testCallNonExistentConstructor() {
        // Try to call ArrayList(String) which doesn't exist
        Parameters paramsWithString = new Parameters(); // Use default constructor
        paramsWithString.put("arg0", "test");
        arrayListClass.bindParameters(interpreter, null, globalNamespace, paramsWithString);
        // The BabbleException should be thrown by bindParameters due to NoSuchMethodException
    }

    // Define TestClassWithFailingConstructor as a public static nested class of JavaClassTest
    public static class TestClassWithFailingConstructor {
        public TestClassWithFailingConstructor() {
            throw new RuntimeException("Constructor failed");
        }
    }

    @Test
    public void testConstructorThrowsException() {
        // Now TestClassWithFailingConstructor is a well-defined static nested class
        JavaClass failingClass = new JavaClass(TestClassWithFailingConstructor.class);
        Parameters emptyParams = new Parameters();
        Namespace constructorScope = failingClass.bindParameters(interpreter, null, globalNamespace, emptyParams);

        try {
            failingClass.call(interpreter, null, constructorScope);
            Assert.fail("Should have thrown BabbleException wrapping InvocationTargetException");
        } catch (BabbleException e) {
            Assert.assertTrue("Cause should be InvocationTargetException", e.getCause() instanceof java.lang.reflect.InvocationTargetException);
            Assert.assertTrue("Root cause should be RuntimeException", e.getCause().getCause() instanceof RuntimeException);
            Assert.assertEquals("Constructor failed", e.getCause().getCause().getMessage());
        }
    }
}
