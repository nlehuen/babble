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
