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

    @org.babblelang.effects.NotNull public String nonNullLabel = "";

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

    // Helpers through which Java calls into Babble objects.

    public static void appendChar(Appendable appendable, char c) throws java.io.IOException {
        appendable.append(c);
    }

    public static Object first(java.util.List<?> list) {
        return list.get(0);
    }

    public static String describe(Object value) {
        return value.toString();
    }

    public static boolean isProxy(Object value) {
        return java.lang.reflect.Proxy.isProxyClass(value.getClass());
    }

    /** Public instance fields : read and assigned on an instance. */
    public int count;

    public Runnable action;
    public final String label = "demo";

    public void runAction() {
        action.run();
    }

    /** A static field that isn't final : read each time it is selected. */
    public static int counter;

    public static final String GREETING = "hello";

    public static void bump() {
        counter++;
    }

    public static void reset() {
        counter = 0;
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

    // P5 runtime boundary helpers : boxed primitive returns/fields, Java defaults, and Faces
    // callbacks.

    public static Integer boxedInt(boolean present) {
        return present ? 42 : null;
    }

    public static Double boxedDouble(boolean present) {
        return present ? 1.5 : null;
    }

    public static Boolean boxedBool(boolean present) {
        return present ? Boolean.TRUE : null;
    }

    public static Integer staticBoxedInt = null;
    public static final Integer STATIC_FINAL_NULL_INT = null;
    public Integer instanceBoxedInt = null;

    public interface BoxedDefault {
        default Integer getBoxedDefault(boolean present) {
            return present ? 7 : null;
        }
    }

    public interface TypedCallback {
        int acceptInt(Integer value);

        double acceptDouble(Double value);

        boolean acceptBool(Boolean value);

        String acceptString(String value);

        int acceptRunnable(Runnable action);
    }

    public static int invokeAcceptInt(TypedCallback cb, Integer value) {
        return cb.acceptInt(value);
    }

    public static double invokeAcceptDouble(TypedCallback cb, Double value) {
        return cb.acceptDouble(value);
    }

    public static double invokeAcceptDoubleRaw(TypedCallback cb, Object rawValue) throws Throwable {
        java.lang.reflect.Method m = TypedCallback.class.getMethod("acceptDouble", Double.class);
        return (double)
                java.lang.reflect.Proxy.getInvocationHandler(cb)
                        .invoke(cb, m, new Object[] {rawValue});
    }

    public static boolean invokeAcceptBool(TypedCallback cb, Boolean value) {
        return cb.acceptBool(value);
    }

    public static String invokeAcceptString(TypedCallback cb, String value) {
        return cb.acceptString(value);
    }

    public static String invokeAcceptStringRaw(TypedCallback cb, Object rawValue) throws Throwable {
        java.lang.reflect.Method m = TypedCallback.class.getMethod("acceptString", String.class);
        return (String)
                java.lang.reflect.Proxy.getInvocationHandler(cb)
                        .invoke(cb, m, new Object[] {rawValue});
    }

    public static int invokeAcceptRunnableRaw(TypedCallback cb, Object rawValue) throws Throwable {
        java.lang.reflect.Method m =
                TypedCallback.class.getMethod("acceptRunnable", Runnable.class);
        return (int)
                java.lang.reflect.Proxy.getInvocationHandler(cb)
                        .invoke(cb, m, new Object[] {rawValue});
    }

    @FunctionalInterface
    public interface BoxedIntUnaryOperator {
        int apply(Integer x);
    }

    public static int invokeUnaryBoxed(BoxedIntUnaryOperator op, Integer x) {
        return op.apply(x);
    }

    public static void setStaticBoxedInt(Integer value) {
        staticBoxedInt = value;
    }

    public static void setMemberRaw(Object target, String name, Object value) {
        if (target instanceof org.babblelang.runtime.HasMembers members) {
            members.setMember(name, value, false);
        } else {
            org.babblelang.runtime.BabbleObjects.setMember(target, name, value, false);
        }
    }

    // RM003-P4 SAM metafactory, bridge, arity, and Proxy-fallback helpers.

    @FunctionalInterface
    public interface StringCriterion extends java.util.function.Predicate<String> {
        @Override
        boolean test(String value);
    }

    @FunctionalInterface
    public interface CovariantSupplier extends java.util.function.Supplier<CharSequence> {
        @Override
        String get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean invokeErasedPredicate(java.util.function.Predicate<?> pred, Object raw) {
        return ((java.util.function.Predicate) pred).test(raw);
    }

    public static CharSequence invokeErasedSupplier(
            java.util.function.Supplier<? extends CharSequence> sup) {
        return sup.get();
    }

    @FunctionalInterface
    public interface Arity16Sam {
        int apply(
                int a0,
                int a1,
                int a2,
                int a3,
                int a4,
                int a5,
                int a6,
                int a7,
                int a8,
                int a9,
                int a10,
                int a11,
                int a12,
                int a13,
                int a14,
                int a15);
    }

    @FunctionalInterface
    public interface Arity17Sam {
        double apply(
                int a0,
                double a1,
                java.io.File a2,
                String a3,
                int a4,
                int a5,
                int a6,
                int a7,
                int a8,
                int a9,
                int a10,
                int a11,
                int a12,
                int a13,
                int a14,
                int a15,
                int a16);

        default int tag() {
            return 17;
        }
    }

    interface HiddenIntSam {
        int apply(int x);
    }

    public static int invokeHiddenSam(HiddenIntSam sam, int x) {
        if (!isProxy(sam)) {
            throw new IllegalStateException("Expected Proxy fallback for package-private SAM");
        }
        return sam.apply(x);
    }

    @FunctionalInterface
    public interface CharSupplier {
        char getAsChar();
    }

    @FunctionalInterface
    public interface ByteUnary {
        byte apply(byte x);
    }

    @FunctionalInterface
    public interface ShortUnary {
        short apply(short x);
    }

    @FunctionalInterface
    public interface FloatUnary {
        float apply(float x);
    }

    // BIP020-I2 array interop helpers (unannotated, @Pure borrow, @Mut).
    @org.babblelang.effects.Pure
    public static int readObjectArray(Object[] xs) {
        return xs.length;
    }

    public static void mutateObjectArray(@org.babblelang.effects.Mut Object[] xs) {
        if (xs.length > 0) {
            xs[0] = null;
        }
    }

    public static Integer @org.babblelang.effects.NotNull [] boxedIntArray() {
        return new Integer[] {1, null, 3};
    }

    public static int @org.babblelang.effects.NotNull [] @org.babblelang.effects.NotNull []
            jaggedIntArray() {
        return new int[][] {new int[] {1, 2}, new int[] {3}};
    }

    // BIP020-I4 generic array helpers & RM003 Java array bridge interfaces.
    public interface ArrayTransformer<E> {
        @org.babblelang.effects.NotNull
        E @org.babblelang.effects.NotNull [] transform(
                @org.babblelang.effects.NotNull E @org.babblelang.effects.NotNull [] input);
    }

    public interface ArrayExportable<E> {
        <T> T @org.babblelang.effects.NotNull [] toArray(T @org.babblelang.effects.NotNull [] a);

        default <T> T @org.babblelang.effects.NotNull [] toArray(
                java.util.function.IntFunction<T[]> generator) {
            return toArray(generator.apply(2));
        }

        @SuppressWarnings("unchecked")
        default int toArray() {
            return toArray((E[]) new Object[2]).length;
        }
    }

    public static <T> T echoArrayWithSample(@org.babblelang.effects.NotNull T sample, T[] arr) {
        return arr != null && arr.length > 0 && arr[0] != null ? arr[0] : sample;
    }

    public static <E> E[] invokeArrayTransformer(ArrayTransformer<E> transformer, E[] input) {
        return transformer.transform(input);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object invokeArrayTransformerRaw(ArrayTransformer<?> transformer, Object raw) {
        return ((ArrayTransformer) transformer).transform((Object[]) raw);
    }

    public static String[] invokeExportableStrings(
            ArrayExportable<@org.babblelang.effects.NotNull String> exp) {
        return exp.toArray(String[]::new);
    }

    // BIP021-I2 Java varargs helpers.
    public static class VarArgsCtor {
        public final String summary;

        public VarArgsCtor(String prefix, int... values) {
            StringBuilder sb = new StringBuilder(prefix).append(":");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(values[i]);
            }
            this.summary = sb.toString();
        }
    }

    public static String vaPick(Object... xs) {
        return "Object...:" + xs.length;
    }

    public static String vaPick(String... xs) {
        return "String...:" + xs.length;
    }

    public static String vaPick(String single) {
        return "fixed:" + single;
    }

    public static String vaAmbig(int a, int... rest) {
        return "a:" + rest.length;
    }

    public static String vaAmbig(int... all) {
        return "all:" + all.length;
    }

    public static double vaSumDoubles(double... xs) {
        double sum = 0.0;
        for (double x : xs) {
            sum += x;
        }
        return sum;
    }

    public static int vaRunAll(java.util.function.IntUnaryOperator... ops) {
        int v = 1;
        for (java.util.function.IntUnaryOperator op : ops) {
            v = op.applyAsInt(v);
        }
        return v;
    }

    // BIP021-I4 Java varargs interface bridge helper.
    @FunctionalInterface
    public interface VarArgsSink {
        int sum(int... xs);
    }

    public static int invokeVarArgsSink(VarArgsSink sink, int... xs) {
        return sink.sum(xs);
    }

    // BIP022-I2 multi-bound TypeVariables, SAM+markers, and Java interface conflicts/covariance.
    public static <T extends Number & Comparable<T>> double sumAsDoubles(T a, T b) {
        return a.doubleValue() + b.doubleValue();
    }

    public static <T extends Runnable & java.io.Serializable> boolean runSerializable(T r) {
        r.run();
        return r instanceof Runnable && r instanceof java.io.Serializable && !isProxy(r);
    }

    public static <T extends java.io.Serializable & Runnable> boolean runSerializableSwapped(T r) {
        r.run();
        return r instanceof Runnable && r instanceof java.io.Serializable && !isProxy(r);
    }

    public static <T extends Comparable<? super T>> T maxPair(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static <T extends java.util.function.Consumer<String> & java.io.Serializable>
            String acceptSerializable(T c, String s) {
        c.accept(s);
        return s + ":" + !isProxy(c);
    }

    public interface LeftObj {
        Object tag();
    }

    public interface RightStr {
        String tag();
    }

    public interface LeftInt {
        int val();
    }

    public interface RightStrVal {
        String val();
    }

    public interface MarkerWithDefault {
        default int markerTag() {
            return 77;
        }
    }

    public interface SameSigRunnable {
        void run();
    }

    public static <T extends Runnable & SameSigRunnable> void runSameSigGeneric(T r) {
        r.run();
    }

    interface HiddenMarkerSam extends Runnable {}

    @org.babblelang.effects.Pure
    public static boolean isSerializableRunnable(Object x) {
        return x instanceof Runnable && x instanceof java.io.Serializable && !isProxy(x);
    }

    public static <T extends HiddenIntSam & java.io.Serializable> int invokeHiddenSerializable(
            T sam, int x) {
        if (!(sam instanceof java.io.Serializable) || !isProxy(sam)) {
            throw new IllegalStateException(
                    "Expected Serializable Proxy fallback for package-private SAM");
        }
        return sam.apply(x);
    }

    public static boolean runHiddenMarker(HiddenMarkerSam r) {
        r.run();
        return isProxy(r);
    }

    @SafeVarargs
    public static <T extends Runnable & java.io.Serializable> int runSerVarargs(T... rs) {
        for (T r : rs) {
            if (!(r instanceof java.io.Serializable)) {
                throw new ClassCastException("Not Serializable");
            }
            r.run();
        }
        return rs.length;
    }

    public static <T extends Runnable & java.io.Serializable> int runSerArray(T[] rs) {
        return runSerVarargs(rs);
    }

    @SafeVarargs
    public static <T extends HiddenIntSam & java.io.Serializable> int sumHiddenSer(T... xs) {
        int s = 0;
        for (T x : xs) {
            if (!(x instanceof java.io.Serializable)) {
                throw new ClassCastException("Not Serializable");
            }
            s += x.apply(10);
        }
        return s;
    }
}
