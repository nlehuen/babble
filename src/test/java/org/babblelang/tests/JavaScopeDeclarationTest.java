package org.babblelang.tests;

import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.natives.java.ImportFunction;
import org.babblelang.engine.impl.natives.java.JavaPackage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * isDeclared() has to answer both ways in the Java interop scopes : it used to throw
 * for a missing class member, and to report every name in a package as declared.
 */
public class JavaScopeDeclarationTest extends BabbleTestBase {
    private final ImportFunction importer = new ImportFunction();

    private Scope<?> scope(String... path) {
        JavaPackage current = importer.getPackage(path[0]);
        Scope<?> result = current;
        for (int i = 1; i < path.length; i++) {
            result = (Scope<?>) current.get(path[i]).get();
            if (result instanceof JavaPackage javaPackage) {
                current = javaPackage;
            }
        }
        return result;
    }

    @Test
    public void testPackageDeclaresItsClassesAndSubPackages() {
        Scope<?> java = scope("java");
        Assertions.assertTrue(java.isDeclared("util"), "java.util is a package");
        Assertions.assertTrue(java.isDeclared("sql"), "java.sql is a package");
        Assertions.assertTrue(scope("java", "util").isDeclared("ArrayList"), "java.util.ArrayList is a class");

        // The class path, not the JDK : these live in the unnamed module.
        Assertions.assertTrue(scope("org").isDeclared("babblelang"));
        Assertions.assertTrue(scope("org", "babblelang").isDeclared("engine"));
        Assertions.assertTrue(scope("org", "babblelang", "engine").isDeclared("BabbleScriptEngine"));
    }

    @Test
    public void testPackageDoesNotDeclareUnknownNames() {
        // get() answers this one by inventing a package ; isDeclared() must not.
        Assertions.assertFalse(scope("java").isDeclared("nosuchpackage"));
        Assertions.assertFalse(scope("java", "util").isDeclared("NoSuchClass"));
        Assertions.assertFalse(scope("org", "babblelang").isDeclared("nosuchpackage"));
    }

    @Test
    public void testPackageStillDeclaresAResolvedName() {
        JavaPackage java = importer.getPackage("java");
        java.get("util");
        Assertions.assertTrue(java.isDeclared("util"));
    }

    @Test
    public void testClassDeclaresItsMethodsAndNestedClasses() {
        Scope<?> demo = scope("org", "babblelang", "tests", "JavaClassDemo");
        Assertions.assertTrue(demo.isDeclared("Method1"), "Method1 is a public method");
        Assertions.assertTrue(demo.isDeclared("add"), "add is a public static method");
        Assertions.assertTrue(demo.isDeclared("Internal1"), "Internal1 is a public nested class");
    }

    @Test
    public void testClassDoesNotDeclareUnknownMembers() {
        // This used to throw "No such name in ..." instead of returning false.
        Assertions.assertFalse(scope("java", "util", "ArrayList").isDeclared("nosuchmethod"));
        Assertions.assertFalse(scope("org", "babblelang", "tests", "JavaClassDemo").isDeclared("Method2"),
                "Method2 is declared by a non-public class, so it is not reachable");
    }

    @Test
    public void testInstanceDeclaresWhatItsClassDeclares() throws Exception {
        Scope<?> list = (Scope<?>) interpret("java.util.ArrayList()");
        Assertions.assertTrue(list.isDeclared("add"));
        Assertions.assertFalse(list.isDeclared("nosuchmethod"));
    }
}
