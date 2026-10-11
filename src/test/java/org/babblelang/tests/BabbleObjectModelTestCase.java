package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;

/**
 * The object model (docs/design/object-model.md) : classes, members, privacy, statics,
 * interfaces, super, value classes, is, same and classof. Java interop is in
 * {@link BabbleJavaObjectsTestCase}.
 */
public class BabbleObjectModelTestCase extends BabbleTestBase {
    private void assertFails(String script, String message) {
        assertFails(script, message, 1);
    }

    private void assertFails(String script, String message, int line) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> interpret(script));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: " + message + " in <input> at line number " + line, e.getMessage());
    }

    private static final String POINT = "def Point = class (def x, def y) (\n"
            + "    def norm2 = () -> ( this.x * this.x + this.y * this.y )\n"
            + "    def plus = (other) -> ( Point(this.x + other.x, this.y + other.y) )\n"
            + ")\n";

    // ---------------------------------------------------------------- classes

    @Test
    public void testConstructionAndMethods() throws Exception {
        Assertions.assertEquals(25, interpret(POINT + "Point(3, 4).norm2()"));
        Assertions.assertEquals(5, interpret(POINT + "Point(3, 4).plus(Point(y: 1, x: 2)).x"));
    }

    @Test
    public void testConstructorParametersAndDefaults() throws Exception {
        String account = "def Account = class (def owner, def balance = 0) (\n"
                + "    def deposit = (n) -> ( this.balance = this.balance + n )\n"
                + ")\n";
        Assertions.assertEquals(5, interpret(account + "def a = Account(\"ann\") ; a.deposit(5) ; a.balance"));
        Assertions.assertEquals(12, interpret(account + "def a = Account(\"ann\", balance: 7) ; a.deposit(5)"));
        assertFails(account + "Account()", "Missing parameter : owner", 4);
    }

    @Test
    public void testPlainParametersAreLexicalVariables() throws Exception {
        // A plain parameter is visible in the whole body, methods included, and is not a member.
        String counter = "def Counter = class (start, step = 1) (\n"
                + "    def next = () -> ( start = start + step )\n"
                + ")\n";
        Assertions.assertEquals(14, interpret(counter + "def c = Counter(10, 2) ; c.next() ; c.next()"));
        assertFails(counter + "Counter(1).start", "No such member : start", 4);
        // The long form of a header member : a body def initialised from the parameter.
        Assertions.assertEquals("ann", interpret("def A = class (owner) ( def owner = owner ) ; A(\"ann\").owner"));
    }

    @Test
    public void testTheBodyIsTheConstructor() throws Exception {
        Assertions.assertEquals(6, interpret("def R = class (def w, def h) ( def area = this.w * this.h ) ; R(2, 3).area"));
        assertFails("def C = class (n) ( if n < 0 then ( suppose(false, \"negative\") ) ) ; C(0 - 1)", "negative");
        // The construction yields the instance, not the body's last value.
        Assertions.assertEquals(true, interpret("def C = class () ( 42 ) ; C() is C"));
    }

    @Test
    public void testMembersAreReachedThroughThisOnly() {
        assertFails("def C = class () ( def size = 0 ; def get = () -> ( size ) ) ; C().get()", "No such name : size");
        // obj.x sees the object's members, and nothing of the scope that created it.
        assertFails("def hidden = 1 ; def o = object ( def v = 1 ) ; o.hidden", "No such member : hidden");
        assertFails("def o = object ( def v = 1 ) ; o.println", "No such member : println");
    }

    @Test
    public void testFixedMemberSetAndReadOnlyMethods() {
        assertFails("def o = object ( def v = 1 ) ; o.w = 2", "No such member : w");
        assertFails("def o = object ( def m = () -> ( 1 ) ) ; o.m = 2", "Cannot assign method : m");
    }

    @Test
    public void testMemberUsedBeforeDefinition() {
        assertFails("def C = class () (\n  def a = this.get()\n  def b = 1\n  def get = () -> ( this.b )\n)\nC()",
                "Member used before definition : get", 2);
    }

    @Test
    public void testBoundMethodsAndFieldsHoldingFunctions() throws Exception {
        // A method selected without a call stays bound to its receiver.
        Assertions.assertEquals(3, interpret("def C = class (def n) ( def get = () -> ( this.n ) ) ; def f = C(3).get ; f()"));
        // A field holding a function is just a value : calling it binds nothing.
        Assertions.assertEquals(7, interpret("def C = class (def cb) () ; def c = C(() -> ( 7 )) ; c.cb()"));
        assertFails("def C = class (def cb) () ; def c = C(() -> ( this )) ; c.cb()", "this outside of a class");
    }

    @Test
    public void testThisAndReturnPlacement() {
        assertFails("this", "this outside of a class");
        assertFails("def f = () -> ( this ) ; 1", "this outside of a class");
        assertFails("def C = class () ( return 1 )", "return in a class body");
        assertFails("def C = class () () static ( return 1 )", "return in a class body");
    }

    @Test
    public void testThisIsCapturedByNestedFunctions() throws Exception {
        Assertions.assertEquals(5, interpret("def C = class (def n) ( def later = () -> ( () -> ( this.n ) ) ) ; C(5).later()()"));
    }

    @Test
    public void testUnknownClassModifier() {
        assertFails("def C = foo class () ()", "Unknown class modifier : foo");
    }

    // ---------------------------------------------------------------- header members

    @Test
    public void testHeaderMembers() throws Exception {
        assertFails("def C = class (def a) ( def a = 2 )", "Member already defined : a");
        assertFails("def C = class (a, def a) ()", "Name already defined : a");
        assertFails("def f = (def a) -> ( a )", "def parameters are only allowed in a class header");
        assertFails("def f = (a) : (def b) : int -> ( a )", "def parameters are only allowed in a class header");
        // A header member binds no name.
        assertFails("def C = class (def a) ( def get = () -> ( a ) ) ; C(1).get()", "No such name : a");
    }

    @Test
    public void testRedundantDefOnAValueParameterWarns() throws Exception {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext context = new SimpleScriptContext();
        StringWriter errors = new StringWriter();
        context.setErrorWriter(errors);
        engine.eval("def P = value class (def x) ()", context);
        Assertions.assertEquals("Warning : def is redundant on a value class parameter : x in <input> at line number 1" + System.lineSeparator(), errors.toString());
    }

    // ---------------------------------------------------------------- private

    private static final String COUNTER = "def Counter = class (private def step = 1) (\n"
            + "    private def n = 0\n"
            + "    private def bump = () -> ( this.n = this.n + this.step )\n"
            + "    def next = () -> ( this.bump() ; this.n )\n"
            + "    def bumper = () -> ( this.bump )\n"
            + "    def peek = (other) -> ( other.n )\n"
            + "    def alias = () -> ( def me = this ; me.n )\n"
            + "    def nested = () -> ( (() -> ( this.n ))() )\n"
            + ")\n";

    @Test
    public void testPrivateMembers() throws Exception {
        Assertions.assertEquals(2, interpret(COUNTER + "def c = Counter() ; c.next() ; c.next()"));
        Assertions.assertEquals(0, interpret(COUNTER + "Counter().nested()"));
        // A bound private method can be handed out.
        Assertions.assertEquals(3, interpret(COUNTER + "def c = Counter(3) ; def b = c.bumper() ; b() ; c.next() - 3"));
        // Rejected before running when the receiver's class is known...
        assertFails(COUNTER + "def c = Counter() ; c.n", "n is private", 10);
        assertFails(COUNTER + "Counter().bump()", "bump is private", 10);
        // ... and otherwise at runtime, even on the same object, or another instance.
        assertFails(COUNTER + "def f = (o) -> ( o.n ) ; f(Counter())", "n is private", 10);
        assertFails(COUNTER + "Counter().alias()", "n is private", 7);
        assertFails(COUNTER + "Counter().peek(Counter())", "n is private", 6);
    }

    @Test
    public void testPrivatePlacement() {
        assertFails("private def x = 1", "private only applies to members");
        assertFails("def C = class (private a) ()", "private only applies to members");
        assertFails("def f = (private a) -> ( a )", "private only applies to members");
        assertFails("def I = interface ( private def m = () -> ( 1 ) )", "Interface members cannot be private : m");
    }

    @Test
    public void testPrivateMembersAndInterfaces() {
        assertFails("def S = interface ( def area : () : int )\ndef Sq = class () implements S ( private def area = () -> ( 1 ) )",
                "Sq.area is private and cannot implement S.area", 2);
        assertFails("def S = interface ( def d = () -> ( 1 ) )\ndef Sq = class () implements S ( private def d = () -> ( 2 ) )",
                "d is private but S provides a public d", 2);
    }

    @Test
    public void testPrivateFieldsOfValues() throws Exception {
        String money = "def M = value class (cents, private note) () ; ";
        Assertions.assertEquals(false, interpret(money + "M(1, \"a\") == M(1, \"b\")"));
        Assertions.assertEquals(true, interpret(money + "M(1, \"a\") == M(1, \"a\")"));
        Assertions.assertEquals("M(cents: 1)", interpret(money + "\"\" + M(1, \"a\")"));
    }

    // ---------------------------------------------------------------- statics

    private static final String STATIC_POINT = "def Point = value class (x, y) (\n"
            + "    def plus = (o) -> ( Point(this.x + o.x, this.y + o.y) )\n"
            + ")\n"
            + "static (\n"
            + "    def origin = this(0, 0)\n"
            + "    private def created = 0\n"
            + "    def make = (x, y) -> (\n"
            + "        this.created = this.created + 1\n"
            + "        this(x, y)\n"
            + "    )\n"
            + "    def count = () -> ( this.created )\n"
            + ")\n";

    @Test
    public void testStaticMembers() throws Exception {
        Assertions.assertEquals("Point(x: 0, y: 0)", interpret(STATIC_POINT + "\"\" + Point.origin"));
        Assertions.assertEquals(2, interpret(STATIC_POINT + "Point.make(1, 2) ; Point.make(3, 4) ; Point.count()"));
        assertFails(STATIC_POINT + "Point.created", "created is private", 13);
        assertFails(STATIC_POINT + "Point(1, 2).origin", "No such member : origin", 13);
        assertFails(STATIC_POINT + "Point.x", "No such member : x", 13);
        assertFails(STATIC_POINT + "classof(Point(1, 2)).created", "created is private", 13);
        Assertions.assertEquals("Point(x: 0, y: 0)", interpret(STATIC_POINT + "\"\" + classof(Point(1, 2)).origin"));
    }

    @Test
    public void testStaticsBelongToEachClassValue() throws Exception {
        String make = "def make = () -> ( class () () static ( def n = 0 ; def inc = () -> ( this.n = this.n + 1 ) ) ) ; ";
        Assertions.assertEquals(1, interpret(make + "def A = make() ; def B = make() ; A.inc() ; A.inc() ; B.inc()"));
        // The static block runs once, when the class expression is evaluated.
        StringWriter out = new StringWriter();
        ScriptContext context = new SimpleScriptContext();
        context.setWriter(out);
        BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval("def C = class () () static ( print(\"s\") ) ; C() ; C()", context);
        Assertions.assertEquals("s", out.toString());
    }

    @Test
    public void testInterfaceStaticsAreNotInherited() throws Exception {
        String shape = "def S = interface ( def area : () : int ) static ( def unit = 1 )\n"
                + "def Sq = class () implements S ( def area = () -> ( 1 ) )\n";
        Assertions.assertEquals(1, interpret(shape + "S.unit"));
        assertFails(shape + "Sq.unit", "No such member : unit", 3);
    }

    // ---------------------------------------------------------------- classof, is, same

    @Test
    public void testClassof() throws Exception {
        Assertions.assertEquals(true, interpret(POINT + "classof(Point(1, 2)) == Point"));
        Assertions.assertEquals(3, interpret(POINT + "classof(Point(1, 2))(3, 4).x"));
        Assertions.assertEquals(true, interpret("classof(java.util.ArrayList()) == java.util.ArrayList"));
        Assertions.assertEquals(true, interpret("classof(\"s\") == java.lang.String and classof(1) == java.lang.Integer"));
        Assertions.assertEquals(null, interpret("classof(null)"));
        Assertions.assertEquals(false, interpret("def mk = () -> ( class () () ) ; def A = mk() ; classof(A()) == mk()"));
        assertFails("def o = object ( def v = 1 ) ; classof(o)()", "object literals cannot be constructed again");
        assertFails("classof(() -> ( 1 ))", "classof : a function has no class");
        assertFails(POINT + "classof(Point)", "classof : a class has no class", 5);
    }

    @Test
    public void testIs() throws Exception {
        String shapes = "def S = interface ( def area : () : int )\n"
                + "def T = interface extends S ()\n"
                + "def Sq = class () implements T ( def area = () -> ( 1 ) )\n"
                + "def C = class () ()\n";
        Assertions.assertEquals(true, interpret(shapes + "Sq() is Sq and Sq() is T and Sq() is S"));
        Assertions.assertEquals(false, interpret(shapes + "C() is S or Sq() is C or null is C or 1 is S"));
        Assertions.assertEquals(true, interpret("\"s\" is java.lang.String and 1 is java.lang.Number and java.util.ArrayList() is java.util.List"));
        Assertions.assertEquals(false, interpret("def mk = () -> ( class () () ) ; def A = mk() ; A() is mk()"));
        assertFails("1 is 2", "Not a type : 2");
    }

    @Test
    public void testSame() throws Exception {
        Assertions.assertEquals(true, interpret(POINT + "def p = Point(1, 2) ; same(p, p) and not same(p, Point(1, 2))"));
        Assertions.assertEquals(true, interpret("same(1000, 1000) and same(1, 1.0) and same(\"a\", \"a\") and same(null, null)"));
        Assertions.assertEquals(false, interpret("def P = value class (x) () ; same(P(1), P(1))"));
    }

    // ---------------------------------------------------------------- toString

    @Test
    public void testToString() throws Exception {
        Assertions.assertEquals("<C!>", interpret("def C = class () ( def toString = () -> ( \"C!\" ) ) ; \"<\" + C() + \">\""));
        Assertions.assertEquals(true, interpret("def C = class () () ; def s = \"\" + C() ; java.lang.String(s).startsWith(\"C@\")"));
        Assertions.assertEquals("Point(x: 1, y: 2.5)", interpret("def Point = value class (x, y) () ; \"\" + Point(1, 2.5)"));
        Assertions.assertEquals("P!", interpret("def Point = value class (x) ( def toString = () -> ( \"P!\" ) ) ; \"\" + Point(1)"));
        StringWriter out = new StringWriter();
        ScriptContext context = new SimpleScriptContext();
        context.setWriter(out);
        BabbleScriptEngineFactory.INSTANCE.getScriptEngine().eval("def C = class () ( def toString = () -> ( \"C!\" ) ) ; print(C())", context);
        Assertions.assertEquals("C!", out.toString());
    }

    // ---------------------------------------------------------------- value classes

    @Test
    public void testValueEquality() throws Exception {
        String point = "def Point = value class (x, y) ( def area = () -> ( this.x * this.y ) ) ; ";
        Assertions.assertEquals(true, interpret(point + "Point(1, 2) == Point(1, 2)"));
        Assertions.assertEquals(true, interpret(point + "Point(1, 2) == Point(1.0, 2.0)"));
        Assertions.assertEquals(false, interpret(point + "Point(1, 2) == Point(2, 1)"));
        // Equality needs the same class value.
        Assertions.assertEquals(false, interpret("def mk = () -> ( value class (x) () ) ; mk()(1) == mk()(1)"));
        Assertions.assertEquals(true, interpret(point + "def s = java.util.HashSet() ; s.add(Point(1, 2)) ; s.contains(Point(1.0, 2))"));
        Assertions.assertEquals("b", interpret(point + "def m = java.util.HashMap() ; m.put(Point(1, 2), \"a\") ; m.put(Point(1, 2), \"b\") ; m.get(Point(1, 2))"));
        Assertions.assertEquals(1, interpret(point + "def m = java.util.HashMap() ; m.put(Point(1, 2), \"a\") ; m.put(Point(1, 2), \"b\") ; m.size()"));
        // Regular classes compare by identity.
        Assertions.assertEquals(false, interpret("def C = class (def x) () ; C(1) == C(1)"));
    }

    @Test
    public void testValueMembersAreReadOnly() {
        String point = "def Point = value class (x, y) ()\n";
        assertFails(point + "def p = Point(1, 2) ; p.x = 5", "Value members are read-only : x", 2);
        assertFails(point + "def f = (p) -> ( p.x = 5 ) ; f(Point(1, 2))", "Value members are read-only : x", 2);
        assertFails("def P = value class (x) ( def m = () -> ( this.x = 2 ) )", "Value members are read-only : x");
    }

    @Test
    public void testValueClassRules() {
        assertFails("def P = value class (x) ( def equals = (o) -> ( true ) )", "equals is generated for value classes");
        assertFails("def P = value class (x) ( def hashCode = () -> ( 1 ) )", "hashCode is generated for value classes");
        assertFails("def P = value class (x) ( println(this) )", "this cannot escape a value class under construction");
        assertFails("def P = value class (x) ( def m = () -> ( 1 ) ; this.m() )", "this cannot escape a value class under construction");
        Assertions.assertDoesNotThrow(() -> interpret("def P = value class (w, h) ( def area = this.w * this.h ) ; P(2, 3).area"));
    }

    @Test
    public void testEqualsInARegularClassWarns() throws Exception {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext context = new SimpleScriptContext();
        StringWriter errors = new StringWriter();
        context.setErrorWriter(errors);
        engine.eval("def C = class () (\n  def equals = (o) -> ( true )\n)\nC() == C()", context);
        Assertions.assertEquals("Warning : equals has no effect on == : use a value class in <input> at line number 2" + System.lineSeparator(), errors.toString());
    }

    // ---------------------------------------------------------------- interfaces

    private static final String SHAPE = "def Shape = interface (\n"
            + "    def area : () : double\n"
            + "    def name : string\n"
            + "    def describe = () -> ( this.name + \" of area \" + this.area() )\n"
            + ")\n";

    @Test
    public void testInterfacesAndDefaults() throws Exception {
        String square = SHAPE + "def Square = class (def side) implements Shape (\n"
                + "    def name = \"square\"\n"
                + "    def area = () -> ( this.side * this.side )\n"
                + ")\n";
        Assertions.assertEquals("square of area 4", interpret(square + "Square(2).describe()"));
        Assertions.assertEquals("o of area 1", interpret(SHAPE + "def o = object implements Shape ( def name = \"o\" ; def area = () -> ( 1 ) ) ; o.describe()"));
        assertFails(SHAPE + "def Square = class () implements Shape ( def name = \"s\" )", "Square does not implement Shape.area", 6);
        // Conformance is declared : having the members is not enough.
        assertFails(SHAPE + "def o = object ( def name = \"o\" ; def area = () -> ( 1 ) ) ; suppose(o is Shape)",
                "Assertion failed at line 6 : oisShape", 6);
    }

    @Test
    public void testInterfaceRules() {
        assertFails("def I = interface ( def size = 0 )", "An interface default must be a function literal : size");
        assertFails("def I = interface ( println(1) )", "An interface can only declare members");
        assertFails("def I = interface ( def d = () -> ( this.nothing ) )", "nothing is not a member of I");
    }

    @Test
    public void testConflictingDefaults() throws Exception {
        String two = "def A = interface ( def d = () -> ( \"a\" ) )\ndef B = interface ( def d = () -> ( \"b\" ) )\n";
        assertFails(two + "def C = class () implements A, B ()", "Conflicting defaults for d : A, B", 3);
        Assertions.assertEquals("a/b", interpret(two + "def C = class () implements A, B ( def d = () -> ( super(A).d() + \"/\" + super(B).d() ) ) ; C().d()"));
        // The same default reached twice is not a conflict.
        Assertions.assertEquals("base", interpret("def Base = interface ( def d = () -> ( \"base\" ) )\n"
                + "def A = interface extends Base ()\ndef B = interface extends Base ()\n"
                + "def C = class () implements A, B () ; C().d()"));
    }

    @Test
    public void testImplementsMustBeStatic() {
        assertFails("def mk = () -> ( interface () ) ; def C = class () implements mk ()", "implements needs a statically known interface");
        assertFails("def I = interface () ; I = 1 ; def C = class () implements I ()", "implements needs a statically known interface");
        assertFails("def D = class () () ; def C = class () implements D ()", "implements needs a statically known interface");
        assertFails("def C = class () implements Nothing ()", "implements needs a statically known interface");
        Assertions.assertDoesNotThrow(() -> interpret("package shapes ( def S = interface () ) ; def C = class () implements shapes.S () ; C() is shapes.S"));
        assertFails("def I = interface extends I ()", "Interface extends itself : I");
    }

    // ---------------------------------------------------------------- super

    @Test
    public void testSuper() throws Exception {
        String square = SHAPE + "def Square = class (def side) implements Shape (\n"
                + "    def name = \"square\"\n"
                + "    def area = () -> ( this.side * this.side )\n"
                + "    def describe = () -> ( \"[\" + super.describe() + \"]\" )\n"
                + "    def later = () -> ( () -> ( super(Shape).describe() ) )\n"
                + ")\n";
        Assertions.assertEquals("[square of area 4]", interpret(square + "Square(2).describe()"));
        Assertions.assertEquals("square of area 9", interpret(square + "Square(3).later()()"));
        // A default inherited through extends, and super in an interface default.
        Assertions.assertEquals("base+", interpret("def Base = interface ( def d = () -> ( \"base\" ) )\n"
                + "def Mid = interface extends Base ( def d = () -> ( super.d() + \"+\" ) )\n"
                + "def C = class () implements Mid () ; C().d()"));
        Assertions.assertEquals("base!", interpret("def Base = interface ( def d = () -> ( \"base\" ) )\n"
                + "def Mid = interface extends Base ()\n"
                + "def C = class () implements Mid ( def d = () -> ( super.d() + \"!\" ) ) ; C().d()"));
    }

    @Test
    public void testSuperRejections() {
        String two = "def A = interface ( def d = () -> ( \"a\" ) ; def r : () : int )\ndef B = interface ( def d = () -> ( \"b\" ) )\n";
        assertFails(two + "def C = class () implements A, B ( def r = () -> ( 1 ) ; def d = () -> ( super.d() ) )",
                "Ambiguous super.d : A, B. Write super(A).d()", 3);
        assertFails(two + "def C = class () implements A ( def r = () -> ( super.r() ) )", "A.r has no default", 3);
        assertFails(two + "def C = class () implements A ( def r = () -> ( super.x() ) )", "No default x to call", 3);
        assertFails(two + "def C = class () implements A ( def r = () -> ( super(B).d() ) )", "B is not implemented directly by C", 3);
        assertFails(two + "def C = class () implements A ( def r = () -> ( def f = super.d ; 1 ) )", "super must be called directly", 3);
        assertFails("super.x()", "super outside of a method");
        assertFails(two + "def C = class () implements A ( def r = () -> ( 1 ) ; super.d() )", "super outside of a method", 3);
    }

    @Test
    public void testEqualityOfJavaCollectionsAndBoundMethods() throws Exception {
        Assertions.assertEquals(true, interpret("def a = java.util.HashMap() ; def b = java.util.HashMap() ; a.put(1, 2) ; b.put(1, 2) ; a == b"));
        Assertions.assertEquals(true, interpret("def o = object ( def m = () -> ( 1 ) ) ; o.m == o.m"));
    }
}
