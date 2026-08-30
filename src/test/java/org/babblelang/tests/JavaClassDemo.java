package org.babblelang.tests;

// Dummy class used to test JavaClass and JavaMethod.
@SuppressWarnings("ALL")
public class JavaClassDemo {
    public static class Internal1 {
        @Override
        public String toString() {
            return "Internal1";
        }
    }

    private static class Internal2 {
        @Override
        public String toString() {
            return "Internal2";
        }
    }

    public static class Internal3 {
        @Override
        public String toString() {
            return "Internal3";
        }
    }

    private final String name;

    public JavaClassDemo() {
        this("");
    }

    public JavaClassDemo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    // Overload set used to check that the most specific applicable method wins.
    public String overloaded(Object value) {
        return "Object";
    }

    public String overloaded(String value) {
        return "String";
    }

    public String overloaded(int value) {
        return "int";
    }

    public String overloaded(double value) {
        return "double";
    }

    // Two unrelated reference types, so a null argument fits both and neither is
    // more specific : the call must be reported as ambiguous.
    public String ambiguous(String value) {
        return "String";
    }

    public String ambiguous(Integer value) {
        return "Integer";
    }

    public String nullable(Object value) {
        return value == null ? "null" : value.toString();
    }

    public static String add(int a, int b) {
        return "static:" + (a + b);
    }

    public String Method1() {
        return "Method1";
    }

    private String Method2() {
        return "Method2";
    }

    public String Method3() {
        return "Method3";
    }
}
