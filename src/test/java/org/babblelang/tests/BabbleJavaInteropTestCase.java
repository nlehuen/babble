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
                    e.getMessage().startsWith("org.babblelang.runtime.BabbleException: No applicable overload of "
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
                    e.getMessage().startsWith("org.babblelang.runtime.BabbleException: No applicable overload of "
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
                    e.getMessage().startsWith("org.babblelang.runtime.BabbleException: Ambiguous call to "
                            + "org.babblelang.tests.JavaClassDemo.ambiguous for (null)"),
                    e.getMessage());
        }
    }

    @Test
    public void testMembersOfReturnedJavaObjects() throws Exception {
        Assertions.assertEquals(true, interpret("java.lang.Runtime.getRuntime().availableProcessors() > 0"));
        Assertions.assertEquals(1, interpret("def l = java.util.ArrayList() ; def m = java.util.ArrayList() ; m.add(1) ; l.add(m) ; l.get(0).size()"));
        Assertions.assertEquals("ab", interpret("java.lang.StringBuilder().append(\"a\").append(\"b\").toString()"));
        // Of a class Babble can't reach : called through the public interface, List.
        Assertions.assertEquals(2, interpret("java.util.List.of(1, 2, 3).get(1)"));
        // Java numbers other than Babble's own have their members.
        Assertions.assertEquals(101, interpret("java.math.BigInteger.valueOf(2).pow(100).bitLength()"));
        // A callback gets Java objects it can call too.
        Assertions.assertEquals(1, interpret("def l = java.util.ArrayList() ; l.add(java.util.ArrayList()) ; l.forEach((x) -> ( x.add(1) )) ; l.get(0).size()"));
        // A returned object is the Java object itself, unwrapped : equal, printed and compared as Java does.
        Assertions.assertEquals("[1, 2]", interpret("\"\" + java.util.List.of(1, 2)"));
        Assertions.assertEquals(true, interpret("java.math.BigInteger.ONE.add(java.math.BigInteger.ONE) == java.math.BigInteger.TWO"));
        Assertions.assertEquals(true, interpret("java.time.LocalDate.of(2000, 1, 1) < java.time.LocalDate.of(2001, 1, 1)"));
    }

    @Test
    public void testBabbleValuesHaveNoJavaMembers() {
        // Unknown to the type checker : found when it runs.
        for (String value : new String[]{"\"abc\"", "1", "1.5", "true", "null", "(x) -> ( x )"}) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret("def f = (v) -> ( v.toString() ) ; f(" + value + ")"));
            Assertions.assertTrue(e.getMessage().contains("Not a scope : v"), value + " : " + e.getMessage());
        }
    }

    @Test
    public void testStaticFields() throws Exception {
        Assertions.assertEquals(Math.PI, interpret("java.lang.Math.PI"));
        Assertions.assertEquals(Double.POSITIVE_INFINITY, interpret("java.lang.Double.POSITIVE_INFINITY"));
        Assertions.assertEquals(1, interpret("java.awt.image.BufferedImage.TYPE_INT_RGB"));
        Assertions.assertEquals("hello", interpret(DEMO + "Demo.GREETING"));
        // Through an instance, as Java allows, and a field's value has its own members.
        Assertions.assertEquals("hello", interpret(DEMO + "Demo().GREETING"));
        Assertions.assertEquals(true, interpret("java.math.BigInteger.ONE.signum() == 1"));
        // A static field that isn't final is read each time, from the same place in the code too.
        Assertions.assertEquals("[0, 1, 2]", interpret(DEMO + "Demo.reset()\n"
                + "def seen = java.util.ArrayList()\n"
                + "def i = 0\n"
                + "while i < 3 then ( seen.add(Demo.counter) ; Demo.bump() ; i = i + 1 )\n"
                + "seen.toString()"));
    }

    @Test
    public void testConstructedJavaObjectsPrintAsThemselves() throws Exception {
        Assertions.assertEquals("x", interpret("\"\" + java.lang.StringBuilder(\"x\")"));
    }
}
