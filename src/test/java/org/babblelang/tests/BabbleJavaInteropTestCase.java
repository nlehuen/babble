package org.babblelang.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleJavaInteropTestCase extends BabbleTestBase {
    private static final String DEMO = "def Demo = import(\"org\").babblelang.tests.JavaClassDemo ; ";

    @Test
    public void testArgumentsAreMatchedByAssignability() throws Exception {
        // add is declared as add(Object) : an exact-type lookup would never find it.
        Assertions.assertEquals(1, interpret("def l = java.util.ArrayList() ; l.add(\"a\") ; l.size()"));
        Assertions.assertEquals("a", interpret("def l = java.util.ArrayList() ; l.add(\"a\") ; l.get(0)"));
    }

    @Test
    public void testPrimitiveParametersAreBoxedAndWidened() throws Exception {
        Assertions.assertEquals(5, interpret("java.lang.Math.abs(0 - 5)"));
        Assertions.assertEquals(3, interpret("java.lang.Math.max(2, 3)"));
        // An Integer argument widens to the double parameter of sqrt.
        Assertions.assertEquals(2.0, interpret("java.lang.Math.sqrt(4)"));
    }

    @Test
    public void testMostSpecificOverloadWins() throws Exception {
        Assertions.assertEquals("String", interpret(DEMO + "Demo().overloaded(\"a\")"));
        Assertions.assertEquals("int", interpret(DEMO + "Demo().overloaded(1)"));
        Assertions.assertEquals("double", interpret(DEMO + "Demo().overloaded(1.5)"));
        Assertions.assertEquals("Object", interpret(DEMO + "Demo().overloaded(true)"));
    }

    @Test
    public void testConstructorOverloads() throws Exception {
        Assertions.assertEquals("", interpret(DEMO + "Demo().getName()"));
        Assertions.assertEquals("named", interpret(DEMO + "Demo(\"named\").getName()"));
    }

    @Test
    public void testNullArgumentFitsAnyReferenceParameter() throws Exception {
        Assertions.assertEquals("null", interpret(DEMO + "Demo().nullable(null)"));
        // Between Object and String, String is still the more specific of the two.
        Assertions.assertEquals("String", interpret(DEMO + "Demo().overloaded(null)"));
    }

    @Test
    public void testNoApplicableOverload() {
        try {
            interpret(DEMO + "Demo().nullable(1, 2)");
            Assertions.fail("Should report that no overload applies");
        } catch (ScriptException e) {
            Assertions.assertTrue(
                    e.getMessage().startsWith("org.babblelang.engine.BabbleException: No applicable overload of "
                            + "org.babblelang.tests.JavaClassDemo.nullable for (java.lang.Integer, java.lang.Integer)"),
                    e.getMessage());
        }
    }

    @Test
    public void testNoApplicableOverloadForNullOnPrimitive() {
        try {
            interpret("java.lang.Math.abs(null)");
            Assertions.fail("Should report that no overload applies");
        } catch (ScriptException e) {
            Assertions.assertTrue(
                    e.getMessage().startsWith("org.babblelang.engine.BabbleException: No applicable overload of "
                            + "java.lang.Math.abs for (null)"),
                    e.getMessage());
        }
    }

    @Test
    public void testAmbiguousCall() {
        try {
            // null fits both ambiguous(String) and ambiguous(Integer), and neither
            // parameter type is assignable to the other.
            interpret(DEMO + "Demo().ambiguous(null)");
            Assertions.fail("Should report an ambiguous call");
        } catch (ScriptException e) {
            Assertions.assertTrue(
                    e.getMessage().startsWith("org.babblelang.engine.BabbleException: Ambiguous call to "
                            + "org.babblelang.tests.JavaClassDemo.ambiguous for (null)"),
                    e.getMessage());
        }
    }
}
