package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;

/**
 * Java interop of the object model (object-model.md §9) : Babble functions converted to
 * functional interfaces, Babble classes implementing Java interfaces, and Babble
 * interfaces extending them.
 */
public class BabbleJavaObjectsTestCase extends BabbleTestBase {
    private static final String DEMO = "def Demo = import(\"org\").babblelang.tests.JavaClassDemo\n";

    private void assertFails(String script, String message, int line) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(script));
        Assertions.assertEquals("org.babblelang.runtime.BabbleException: " + message + " in <input> at line number " + line, e.getMessage());
    }

    private static String output(String script) throws ScriptException {
        StringWriter out = new StringWriter();
        ScriptContext context = new SimpleScriptContext();
        context.setWriter(out);
        BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval(script, context);
        return out.toString();
    }

    // ---------------------------------------------------------------- functions as functional interfaces

    @Test
    public void testFunctionsConvertToFunctionalInterfaces() throws Exception {
        String list = "def l = java.util.ArrayList() ; l.add(3) ; l.add(1) ; l.add(2)\n";
        Assertions.assertEquals("[1, 2, 3]", interpret(list + "l.sort((a : any, b : any) : int -> ( (a as int) - (b as int) )) ; l.toString()"));
        Assertions.assertEquals("[1, 2]", interpret(list + "l.removeIf((v : any) : bool -> ( (v as int) > 2 )) ; l.toString()"));
        Assertions.assertEquals("hi" + System.lineSeparator(),
                output("def t = java.lang.Thread(() -> ( println(\"hi\") )) ; t.start() ; t.join()"));
        // Bound methods and class values convert too.
        Assertions.assertEquals("[3, 2, 1]", interpret(list + "def o = object ( def cmp = (a : any, b : any) : int -> ( (b as int) - (a as int) ) ) ; l.sort(o.cmp) ; l.toString()"));
    }

    @Test
    public void testAmbiguousConversionNeedsAnExplicitOne() throws Exception {
        String pool = "def pool = java.util.concurrent.ForkJoinPool()\n";
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(pool + "pool.submit(() -> ( 1 ))"));
        Assertions.assertTrue(e.getMessage().contains("Ambiguous call to java.util.concurrent.ForkJoinPool.submit"), e.getMessage());
        Assertions.assertDoesNotThrow(() -> interpret(pool + "pool.submit(java.util.concurrent.Callable(() -> ( 1 ))) ; pool.shutdown()"));
    }

    @Test
    public void testConversionChecksResultAndArity() {
        String list = "def l = java.util.ArrayList() ; l.add(3) ; l.add(1)\n";
        assertFails(list + "l.sort((a : any, b : any) -> ( 0.5 ))", "Type error : Comparator.compare must return int, got Double", 2);
        assertFails(list + "l.sort((a : any) -> ( 0 ))", "Comparator.compare takes 2 arguments, function takes 1", 2);
    }

    // ---------------------------------------------------------------- classes implementing Java interfaces

    private static final String COUNTDOWN = "def Countdown = class (private def n : int) implements java.util.Iterator (\n"
            + "    def hasNext = () : bool -> ( this.n > 0 )\n"
            + "    def next = () : int -> ( this.n = this.n - 1 ; this.n + 1 )\n"
            + ")\n";

    @Test
    public void testIteratorWithAJavaDefault() throws Exception {
        Assertions.assertEquals("321", output(COUNTDOWN + "Countdown(3).forEachRemaining((v : any) -> ( print(v) ))"));
        Assertions.assertEquals(true, interpret(COUNTDOWN + "Countdown(1) is java.util.Iterator"));
    }

    private static final String MONEY = "def Money = value class (cents : any) implements java.lang.Comparable (\n"
            + "    def compareTo = (o : any) : int -> ( int(double(this.cents) - double((o as Money).cents)) )\n"
            + ")\n"
            + "def wallet = java.util.ArrayList() ; wallet.add(Money(500)) ; wallet.add(Money(20))\n";

    @Test
    public void testComparableUsedByJava() throws Exception {
        Assertions.assertEquals(20, interpret(MONEY + "java.util.Collections.sort(wallet) ; (wallet.get(0) as Money).cents"));
        // Babble code never sees the face : the instance comes back as itself.
        Assertions.assertEquals(true, interpret(MONEY + "def m = Money(1) ; wallet.add(m) ; same(wallet.get(2), m)"));
        Assertions.assertEquals(true, interpret(DEMO + MONEY + "Demo.isProxy(wallet) == false and same(Demo.first(wallet), wallet.get(0))"));
        Assertions.assertEquals("Money(cents: 500)", interpret(DEMO + MONEY + "Demo.describe(Demo.first(wallet))"));
        // Value equality and hashing are what Java sees too.
        Assertions.assertEquals(true, interpret(MONEY + "def s = java.util.HashSet() ; s.add(Money(5)) ; s.contains(Money(5.0))"));
    }

    @Test
    public void testCallsFromJavaPrintAndFail() throws Exception {
        String noisy = "def Noisy = class (def v : int) implements java.lang.Comparable (\n"
                + "    def compareTo = (o : any) : int -> ( print(\"<\") ; this.v - (o as Noisy).v )\n"
                + ")\n"
                + "def l = java.util.ArrayList() ; l.add(Noisy(2)) ; l.add(Noisy(1)) ; java.util.Collections.sort(l)";
        Assertions.assertTrue(output(noisy).startsWith("<"));
        String broken = "def Broken = class () implements java.lang.Comparable (\n"
                + "    def compareTo = (o : any) : int -> ( nosuchname ; 0 )\n"
                + ")\n"
                + "def l = java.util.ArrayList() ; l.add(Broken()) ; l.add(Broken()) ; java.util.Collections.sort(l)";
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(broken));
        Assertions.assertTrue(e.getMessage().startsWith("org.babblelang.runtime.BabbleException: No such name : nosuchname"), e.getMessage());
    }

    private static final String LOG = "def Log = class () implements java.lang.Appendable (\n"
            + "    private def sb = java.lang.StringBuilder()\n"
            + "    def append = (s : any?, start : any? = null, end : any? = null) : java.lang.Appendable -> (\n"
            + "        if s is java.lang.Character then ( this.sb.append(\"'\" + s + \"'\") )\n"
            + "        else ( if start == null then ( this.sb.append(s as java.lang.CharSequence) ) else ( this.sb.append(s as java.lang.CharSequence, start as int, end as int) ) )\n"
            + "        this\n"
            + "    )\n"
            + "    def text = () : string -> ( this.sb.toString() )\n"
            + ")\n";

    @Test
    public void testOneMethodServesEveryOverload() throws Exception {
        Assertions.assertEquals("abc", interpret(LOG + "def log = Log() ; java.util.Formatter(log).format(\"abc\", null) ; log.text()"));
        // Same-arity overloads are told apart with "is" : here append(char).
        Assertions.assertEquals("'x'", interpret(DEMO + LOG + "def log = Log() ; Demo.appendChar(log, java.lang.String(\"x\").charAt(0)) ; log.text()"));
        assertFails("def Log = class () implements java.lang.Appendable (\n    def append = (s : any, start : any? = null) -> ( this )\n)",
                "Log.append takes 1 to 2 arguments, but Appendable.append(CharSequence, int, int) passes 3", 2);
    }

    @Test
    public void testJavaInterfaceCompleteness() {
        assertFails("def C = class () implements java.util.Iterator ( def hasNext = () -> ( false ) )",
                "C does not implement java.util.Iterator.next", 1);
        assertFails("def C = class () implements java.lang.Runnable ( def run = 1 )",
                "C does not implement java.lang.Runnable.run", 1);
        assertFails("def C = class () implements java.util.ArrayList ()", "Babble classes can only implement Java interfaces", 1);
    }

    @Test
    public void testJavaInterfacesThroughImport() throws Exception {
        Assertions.assertEquals(true, interpret("def util = import(\"java.util\")\n"
                + "def E = class () implements util.Iterator ( def hasNext = () : bool -> ( false ) ; def next = () : any? -> ( null ) )\n"
                + "E() is java.util.Iterator"));
    }

    @Test
    public void testOverridingAJavaDefault() throws Exception {
        Assertions.assertEquals("[321]", output("def Countdown = class (private def n : int) implements java.util.Iterator (\n"
                + "    def hasNext = () : bool -> ( this.n > 0 )\n"
                + "    def next = () : int -> ( this.n = this.n - 1 ; this.n + 1 )\n"
                + "    def forEachRemaining = (f : java.util.function.Consumer) : any? -> ( print(\"[\") ; super(java.util.Iterator).forEachRemaining(f) ; print(\"]\") )\n"
                + ")\n"
                + "Countdown(3).forEachRemaining(java.util.function.Consumer((v : any) -> ( print(v) )))"));
    }

    // ---------------------------------------------------------------- Babble interfaces extending Java ones

    private static final String ORDERED = "def Ordered = interface extends java.lang.Comparable (\n"
            + "    def key : () : int\n"
            + "    def compareTo = (o : any) : int -> ( this.key() - (o as Ordered).key() )\n"
            + ")\n"
            + "def Task = class (def name : string, def priority : int) implements Ordered (\n"
            + "    def key = () : int -> ( this.priority )\n"
            + ")\n";

    @Test
    public void testATraitImplementingAJavaInterface() throws Exception {
        Assertions.assertEquals("plan", interpret(ORDERED + "def tasks = java.util.ArrayList() ; tasks.add(Task(\"write\", 2)) ; tasks.add(Task(\"plan\", 1))\n"
                + "java.util.Collections.sort(tasks) ; (tasks.get(0) as Task).name"));
        Assertions.assertEquals(true, interpret(ORDERED + "Task(\"x\", 1) is java.lang.Comparable"));
        assertFails("def Ordered = interface extends java.lang.Comparable (\n    def compareTo = () -> ( 0 )\n)",
                "Ordered.compareTo takes 0 arguments, but Comparable.compareTo(Object) passes 1", 2);
        // Completeness through a default, or through a class method.
        assertFails("def O = interface extends java.lang.Comparable ()\ndef C = class () implements O ()",
                "C does not implement java.lang.Comparable.compareTo", 2);
        Assertions.assertDoesNotThrow(() -> interpret("def O = interface extends java.lang.Comparable ()\ndef C = class () implements O ( def compareTo = (o : any) : int -> ( 0 ) )"));
    }

    @Test
    public void testJavaAndBabbleDefaultsConflict() {
        assertFails("def R = interface ( def remove = () -> ( 1 ) )\n"
                        + "def C = class () implements java.util.Iterator, R ( def hasNext = () -> ( false ) ; def next = () -> ( 1 ) )",
                "Conflicting defaults for remove : java.util.Iterator, R", 2);
    }

    @Test
    public void testAnInterfaceDefaultOverridingAJavaDefault() throws Exception {
        Assertions.assertEquals("[1]", output("def Loud = interface extends java.util.Iterator (\n"
                + "    def forEachRemaining = (f : java.util.function.Consumer) : any? -> ( print(\"[\") ; super(java.util.Iterator).forEachRemaining(f) ; print(\"]\") )\n"
                + ")\n"
                + "def One = class () implements Loud ( def done = false ; def hasNext = () : bool -> ( not this.done ) ; def next = () : int -> ( this.done = true ; 1 ) )\n"
                + "One().forEachRemaining(java.util.function.Consumer((v : any) -> ( print(v) )))"));
    }

    @Test
    public void testJavaDefaultBoxedReturnChecksNull() throws Exception {
        String base = "def tests = import(\"org.babblelang.tests\")\n"
                + "def C = class () implements tests.JavaClassDemo.BoxedDefault ()\n";
        Assertions.assertEquals(7, interpret(base + "C().getBoxedDefault(true)"));
        assertFails(base + "C().getBoxedDefault(false)", "Type error : expected int, got null", 3);
    }

    @Test
    public void testFacesChecksParametersEnteringBabble() throws Exception {
        String cb = "def tests = import(\"org.babblelang.tests\")\n"
                + "def Demo = tests.JavaClassDemo\n"
                + "def Cb = class () implements tests.JavaClassDemo.TypedCallback (\n"
                + "    def acceptInt = (v : int) : int -> ( v + 1 )\n"
                + "    def acceptDouble = (v : double) : double -> ( v + 0.5 )\n"
                + "    def acceptBool = (v : bool) : bool -> ( not v )\n"
                + "    def acceptString = (v : string) : string -> ( v )\n"
                + "    def acceptRunnable = (v : java.lang.Runnable) : int -> ( 1 )\n"
                + ")\n"
                + "def c = Cb()\n";
        Assertions.assertEquals(6, interpret(cb + "Demo.invokeAcceptInt(c, 5)"));
        Assertions.assertEquals(3.5, interpret(cb + "Demo.invokeAcceptDouble(c, 3.0)"));
        Assertions.assertEquals(3.5, interpret(cb + "Demo.invokeAcceptDoubleRaw(c, 3)"));
        Assertions.assertEquals(false, interpret(cb + "Demo.invokeAcceptBool(c, true)"));
        Assertions.assertNull(interpret(cb + "Demo.invokeAcceptString(c, null)"));
        Assertions.assertEquals(1, interpret(cb + "Demo.invokeAcceptRunnableRaw(c, () -> ( null ))"));

        String[][] bad = {
                {cb + "Demo.invokeAcceptInt(c, null)", "Type error : expected int, got null"},
                {cb + "Demo.invokeAcceptDouble(c, null)", "Type error : expected double, got null"},
                {cb + "Demo.invokeAcceptDoubleRaw(c, \"x\")", "Type error : expected double, got string"},
                {cb + "Demo.invokeAcceptBool(c, null)", "Type error : expected bool, got null"},
                {cb + "Demo.invokeAcceptStringRaw(c, 1)", "Type error : expected string, got int"},
                {cb + "Demo.invokeAcceptRunnableRaw(c, \"nope\")", "Type error : expected java.lang.Runnable, got string"},
                {DEMO + "Demo.invokeUnaryBoxed((x : int) -> ( x + 1 ), null)", "Type error : expected int, got null"},
        };
        for (String[] test : bad) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(test[0]), test[0]);
            Assertions.assertTrue(e.getMessage().contains(test[1]), test[0] + " : " + e.getMessage());
        }
        Assertions.assertEquals(5, interpret(DEMO + "Demo.invokeUnaryBoxed((x : int) -> ( x + 1 ), 4)"));
    }

    @Test
    public void testSetMemberFromJavaStillChecksDeclaredTypes() throws Exception {
        String cls = DEMO + "def C = class (def x : double = 0.0) () static ( def s : int = 1 )\n";
        Assertions.assertEquals(2.0, interpret(cls + "def c = C() ; Demo.setMemberRaw(c, \"x\", 2) ; c.x"));
        assertFails(cls + "def c = C() ; Demo.setMemberRaw(c, \"x\", \"bad\")", "Type error : expected double, got string", 3);
        assertFails(cls + "Demo.setMemberRaw(C, \"s\", \"bad\")", "Type error : expected int, got string", 3);
    }
}
