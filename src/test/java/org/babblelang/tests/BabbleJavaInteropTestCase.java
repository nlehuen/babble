package org.babblelang.tests;

import org.babblelang.engine.impl.natives.java.ImportFunction;
import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaObject;
import org.babblelang.engine.impl.natives.java.JavaPackage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptException;

public class BabbleJavaInteropTestCase extends BabbleTestBase {
    private static final String DEMO = "def Demo = import(\"org\").babblelang.tests.JavaClassDemo ; ";

    @Test
    public void testArgumentsAreMatchedByAssignability() throws Exception {
        // add is declared as add(Object) : an exact-type lookup would never find it.
        Assertions.assertEquals(1, interpret("def l : java.util.List of string = java.util.ArrayList() ; l.add(\"a\") ; l.size()"));
        Assertions.assertEquals("a", interpret("def l : java.util.List of string = java.util.ArrayList() ; l.add(\"a\") ; l.get(0)"));
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
                            + "org.babblelang.tests.JavaClassDemo.nullable for (int, int)"),
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
        Assertions.assertEquals(1, interpret("def l : java.util.List of java.util.List of int = java.util.ArrayList() ; def m : java.util.List of int = java.util.ArrayList() ; m.add(1) ; l.add(m) ; (l.get(0) as java.util.List).size()"));
        Assertions.assertEquals("ab", interpret("java.lang.StringBuilder().append(\"a\").append(\"b\").toString()"));
        // Of a class Babble can't reach : called through the public interface, List.
        Assertions.assertEquals(2, interpret("java.util.List.of(1, 2, 3).get(1)"));
        // Java numbers other than Babble's own have their members.
        Assertions.assertEquals(101, interpret("java.math.BigInteger.valueOf(2).pow(100).bitLength()"));
        // A callback gets Java objects it can call too.
        Assertions.assertEquals("1", interpret("def l : java.util.List of any = java.util.ArrayList() ; def m : java.util.List of int = java.util.ArrayList() ; m.add(42) ; l.add(m) ; def sb = java.lang.StringBuilder() ; l.forEach(fn (x : any) -> ( sb.append((x as java.util.List).size()) )) ; sb.toString()"));
        // A returned object is the Java object itself, unwrapped : equal, printed and compared as Java does.
        Assertions.assertEquals("[1, 2]", interpret("\"\" + java.util.List.of(1, 2)"));
        Assertions.assertEquals(true, interpret("java.math.BigInteger.ONE.add(java.math.BigInteger.ONE) == java.math.BigInteger.TWO"));
        Assertions.assertEquals(true, interpret("java.time.LocalDate.of(2000, 1, 1) < java.time.LocalDate.of(2001, 1, 1)"));
    }

    @Test
    public void testBabbleValuesHaveNoJavaMembers() {
        for (String value : new String[]{"\"abc\"", "1", "1.5", "true", "null", "fn (x : any) -> ( x )"}) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret("def f = fn (v : any) -> ( v.toString() ) ; f(" + value + ")"));
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
                + "def seen : java.util.List of int = java.util.ArrayList()\n"
                + "def i = 0\n"
                + "while i < 3 then ( seen.add(Demo.counter) ; Demo.bump() ; i = i + 1 )\n"
                + "seen.toString()"));
    }

    @Test
    public void testConstructedJavaObjectsPrintAsThemselves() throws Exception {
        Assertions.assertEquals("x", interpret("\"\" + java.lang.StringBuilder(\"x\")"));
    }

    @Test
    public void testInstanceFields() throws Exception {
        Assertions.assertEquals("java.awt.Point[x=11,y=2]", interpret("def p = java.awt.Point(1, 2) ; p.x = p.x + 10 ; \"\" + p"));
        Assertions.assertEquals(28, interpret("def d = java.awt.Dimension(3, 4) ; d.width = 7 ; d.width * d.height"));
        Assertions.assertEquals(3, interpret(DEMO + "def d = Demo() ; d.count = 3 ; d.count"));
        Assertions.assertEquals("demo", interpret(DEMO + "Demo().label"));
        // A static field that isn't final can be assigned too.
        Assertions.assertEquals(5, interpret(DEMO + "Demo.counter = 5 ; Demo.counter"));
        // A function goes to a functional interface's field once converted.
        Assertions.assertEquals(1, interpret(DEMO + "def d = Demo() ; def n = 0 ; d.action = java.lang.Runnable(fn () -> ( n = n + 1 )) ; d.runAction() ; n"));
    }

    @Test
    public void testFieldErrors() {
        String[][] staticCases = {
                {"def o = java.awt.Point(1, 2) ; o.x = 2.5", "Type error : expected int, got double"},
                {"java.lang.Math.PI = 3", "Cannot assign final field : java.lang.Math.PI"},
                {"java.awt.Point.x", "Not a static field : java.awt.Point.x"},
                {"java.awt.Point.x = 1", "Not a static field : java.awt.Point.x"},
                {"def l : java.util.ArrayList of int = java.util.ArrayList() ; l.size = 1", "No such field in java.util.ArrayList : size"},
                {"def l : java.util.ArrayList of int = java.util.ArrayList() ; l.nope", "No such name in java.util.ArrayList : nope"},
                {"Demo().action = fn () -> ( 1 )", "Cannot assign a function to org.babblelang.tests.JavaClassDemo.action : convert it first, as Runnable(f)"},
        };
        for (String[] c : staticCases) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(DEMO + c[0]));
            Assertions.assertTrue(e.getMessage().contains(c[1]), c[0] + " : " + e.getMessage());
        }
    }

    @Test
    public void testMethodsInheritedFromANonPublicSuperclass() throws Exception {
        // StringBuilder makes AbstractStringBuilder's length() public through a bridge method.
        Assertions.assertEquals(3, interpret("java.lang.StringBuilder(\"abc\").length()"));
        Assertions.assertEquals('b', interpret("java.lang.StringBuilder(\"abc\").charAt(1)"));
        // Generic bridges still add no ambiguity : compareTo(Object) beside compareTo(String).
        Assertions.assertEquals(-1, interpret("java.math.BigInteger.ONE.compareTo(java.math.BigInteger.TWO)"));
    }

    @Test
    public void testJavaPackagesDeclareOnlyWhatExists() {
        JavaPackage java = new ImportFunction().getPackage("java");
        Assertions.assertTrue(java.isDeclared("util"));
        Assertions.assertFalse(java.isDeclared("nosuchpackage"));
        JavaPackage util = new ImportFunction().getPackage("java.util");
        Assertions.assertTrue(util.isDeclared("ArrayList"));
        Assertions.assertTrue(util.isDeclared("concurrent"));
        Assertions.assertFalse(util.isDeclared("NoSuchClass"));
        // Asking doesn't make the name exist.
        Assertions.assertFalse(util.isDeclared("NoSuchClass"));
        // Packages of the class path, not only the JDK's.
        JavaPackage babble = new ImportFunction().getPackage("org.babblelang");
        Assertions.assertTrue(babble.isDeclared("tests"));
        Assertions.assertFalse(babble.isDeclared("nosuchpackage"));
    }

    @Test
    public void testJavaClassesDeclareOnlyTheirMembers() {
        JavaClass list = JavaClass.of(java.util.ArrayList.class);
        Assertions.assertTrue(list.isDeclared("add"));
        Assertions.assertFalse(list.isDeclared("nosuchmethod"));
        Assertions.assertTrue(JavaClass.of(Integer.class).isDeclared("MAX_VALUE"));
        Assertions.assertTrue(JavaClass.of(java.util.Map.class).isDeclared("Entry"));
        JavaObject instance = JavaObject.of(new java.util.ArrayList<>());
        Assertions.assertTrue(instance.isDeclared("size"));
        Assertions.assertFalse(instance.isDeclared("nosuchmethod"));
    }

    @Test
    public void testBoxedPrimitiveReturnsAndFieldsRejectNull() throws Exception {
        Assertions.assertEquals(42, interpret(DEMO + "Demo.boxedInt(true)"));
        Assertions.assertEquals(1.5, interpret(DEMO + "Demo.boxedDouble(true)"));
        Assertions.assertEquals(true, interpret(DEMO + "Demo.boxedBool(true)"));

        String[][] failures = {
                {"Demo.boxedInt(false)!", "Type error : expected int, got null"},
                {"Demo.boxedDouble(false)!", "Type error : expected double, got null"},
                {"Demo.boxedBool(false)!", "Type error : expected bool, got null"},
                {"def i = 0 ; while i < 5 then ( Demo.boxedInt(i < 4)! ; i = i + 1 )", "Type error : expected int, got null"},
                {"Demo.setStaticBoxedInt(null) ; Demo.staticBoxedInt!", "Type error : expected int, got null"},
                {"Demo.STATIC_FINAL_NULL_INT!", "Type error : expected int, got null"},
                {"Demo().instanceBoxedInt!", "Type error : expected int, got null"},
        };
        for (String[] c : failures) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(DEMO + c[0]), c[0]);
            Assertions.assertTrue(e.getMessage().contains(c[1]), c[0] + " : " + e.getMessage());
        }
        Assertions.assertEquals(9, interpret(DEMO + "Demo.setStaticBoxedInt(9) ; def v = Demo.staticBoxedInt! ; Demo.setStaticBoxedInt(null) ; v"));
    }
}
