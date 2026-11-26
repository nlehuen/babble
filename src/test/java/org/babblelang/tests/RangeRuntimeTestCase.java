package org.babblelang.tests;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CharRange;
import org.babblelang.runtime.ComparableRange;
import org.babblelang.runtime.DoubleRange;
import org.babblelang.runtime.IntRange;
import org.babblelang.runtime.LongRange;
import org.babblelang.runtime.OpSite;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.Range;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@code BIP031-I1} runtime classes ({@link Range}, {@link IntRange}, {@link
 * LongRange}, {@link CharRange}, {@link DoubleRange}, {@link ComparableRange}) and {@link
 * Operators#mod} / {@link Operators#floorDiv}.
 */
public class RangeRuntimeTestCase {

    private static <T> List<T> collect(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        for (T e : it) {
            out.add(e);
        }
        return out;
    }

    @Test
    public void testIntRangeBasicsSteppedReversedAndOverflowSafe() {
        IntRange<Integer> r = Range.ofInt(3, 10, false).step(5);
        Assertions.assertEquals(3, r.start());
        Assertions.assertEquals(10, r.end());
        Assertions.assertFalse(r.inclusive());
        Assertions.assertFalse(r.isEmpty());
        Assertions.assertTrue(r.containsInt(3));
        Assertions.assertTrue(r.containsInt(8));
        Assertions.assertFalse(r.containsInt(5));
        Assertions.assertFalse(r.containsInt(10));
        Assertions.assertFalse(r.containsInt(13));
        Assertions.assertFalse(r.contains("3"));
        Assertions.assertFalse(r.contains(null));
        Assertions.assertEquals(List.of(3, 8), collect(r));
        Assertions.assertEquals("(3..<10).step(5)", r.toString());

        IntRange<Integer> rev = r.reversed();
        Assertions.assertEquals(3, rev.start());
        Assertions.assertEquals(10, rev.end());
        Assertions.assertTrue(rev.containsInt(3));
        Assertions.assertTrue(rev.containsInt(8));
        Assertions.assertEquals(List.of(8, 3), collect(rev));
        Assertions.assertEquals("(3..<10).step(5).reversed()", rev.toString());
        Assertions.assertEquals(r, rev.reversed());
        Assertions.assertEquals(r.hashCode(), rev.reversed().hashCode());

        // Structural equality: 0..<10 != 0..9.
        Assertions.assertNotEquals(Range.ofInt(0, 10, false), Range.ofInt(0, 9, true));
        Assertions.assertEquals("0..<10", Range.ofInt(0, 10, false).toString());
        Assertions.assertEquals("1..10", Range.ofInt(1, 10, true).toString());
        Assertions.assertEquals(
                "(0..<5).reversed()", Range.ofInt(0, 5, false).reversed().toString());

        // Overflow-safe stepping near MAX_VALUE and full MIN_VALUE..MAX_VALUE containment.
        IntRange<Integer> bigStep = Range.ofInt(0, Integer.MAX_VALUE, false).step(1_000_000_000);
        Assertions.assertEquals(List.of(0, 1_000_000_000, 2_000_000_000), collect(bigStep));
        Assertions.assertEquals(
                List.of(2_000_000_000, 1_000_000_000, 0), collect(bigStep.reversed()));

        IntRange<Integer> maxEnd = Range.ofInt(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, true);
        Assertions.assertEquals(List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE), collect(maxEnd));

        IntRange<Integer> fullStep3 =
                Range.ofInt(Integer.MIN_VALUE, Integer.MAX_VALUE, true).step(3);
        Assertions.assertTrue(fullStep3.containsInt(Integer.MIN_VALUE));
        Assertions.assertTrue(fullStep3.containsInt(Integer.MAX_VALUE));
        Assertions.assertFalse(fullStep3.containsInt(Integer.MAX_VALUE - 1));

        // Empty range and byte/short boxing.
        IntRange<Integer> empty = Range.ofInt(5, 5, false);
        Assertions.assertTrue(empty.isEmpty());
        Assertions.assertEquals(List.of(), collect(empty));
        Iterator<Integer> emptyIt = empty.iterator();
        Assertions.assertFalse(emptyIt.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, emptyIt::next);

        IntRange<Byte> byteRange = Range.ofByte((byte) 1, (byte) 3, true);
        Assertions.assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), collect(byteRange));
        Assertions.assertTrue(byteRange.contains((byte) 2));
        Assertions.assertFalse(byteRange.contains(2));

        IntRange<Short> shortRange = Range.ofShort((short) 10, (short) 12, false);
        Assertions.assertEquals(List.of((short) 10, (short) 11), collect(shortRange));
        Assertions.assertTrue(shortRange.contains((short) 10));
        Assertions.assertFalse(shortRange.contains(10));

        // Non-positive step throws BabbleException.
        BabbleException ex0 =
                Assertions.assertThrows(
                        BabbleException.class, () -> Range.ofInt(0, 10, false).step(0));
        Assertions.assertEquals("Range step must be positive : 0", ex0.getMessage());
        BabbleException exNeg =
                Assertions.assertThrows(
                        BabbleException.class, () -> Range.ofInt(0, 10, false).step(-2));
        Assertions.assertEquals("Range step must be positive : -2", exNeg.getMessage());
    }

    @Test
    public void testLongRangeAndCharRange() {
        LongRange lr = Range.ofLong(Long.MIN_VALUE, Long.MAX_VALUE, true).step(3L);
        Assertions.assertTrue(lr.containsLong(Long.MIN_VALUE));
        Assertions.assertTrue(lr.containsLong(Long.MAX_VALUE));
        Assertions.assertFalse(lr.containsLong(Long.MAX_VALUE - 1L));
        Assertions.assertFalse(lr.contains(0));

        LongRange tail = Range.ofLong(Long.MAX_VALUE - 2L, Long.MAX_VALUE, true);
        Assertions.assertEquals(
                List.of(Long.MAX_VALUE - 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE), collect(tail));
        Assertions.assertEquals(
                List.of(Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MAX_VALUE - 2L),
                collect(tail.reversed()));

        LongRange stepped =
                Range.ofLong(0L, Long.MAX_VALUE, false).step(3_000_000_000_000_000_000L);
        Assertions.assertEquals(
                List.of(
                        0L,
                        3_000_000_000_000_000_000L,
                        6_000_000_000_000_000_000L,
                        9_000_000_000_000_000_000L),
                collect(stepped));

        CharRange cr = Range.ofChar('a', 'e', true).step(2);
        Assertions.assertEquals(List.of('a', 'c', 'e'), collect(cr));
        Assertions.assertEquals(List.of('e', 'c', 'a'), collect(cr.reversed()));
        Assertions.assertTrue(cr.containsChar('c'));
        Assertions.assertFalse(cr.containsChar('b'));
        Assertions.assertFalse(cr.contains("a"));

        CharRange topChars = Range.ofChar('\ufffe', '\uffff', true);
        Assertions.assertEquals(List.of('\ufffe', '\uffff'), collect(topChars));
    }

    @Test
    public void testDoubleRangeAndComparableRange() {
        DoubleRange<Double> dr = Range.ofDouble(0.0, 1.0, false);
        Assertions.assertFalse(dr.isEmpty());
        Assertions.assertTrue(dr.containsDouble(0.0));
        Assertions.assertTrue(dr.containsDouble(0.5));
        Assertions.assertFalse(dr.containsDouble(1.0));
        Assertions.assertFalse(dr.containsDouble(Double.NaN));
        Assertions.assertFalse(dr.contains(0.5f));

        Assertions.assertTrue(Range.ofDouble(Double.NaN, 1.0, true).isEmpty());
        Assertions.assertTrue(Range.ofDouble(0.0, Double.NaN, true).isEmpty());
        Assertions.assertTrue(Range.ofFloat(1.0f, 1.0f, false).isEmpty());
        Assertions.assertTrue(Range.ofFloat(1.0f, 1.0f, true).contains(1.0f));

        ComparableRange<String> sr = Range.ofComparable("alpha", "omega", true);
        Assertions.assertFalse(sr.isEmpty());
        Assertions.assertTrue(sr.contains("beta"));
        Assertions.assertTrue(sr.contains("alpha"));
        Assertions.assertTrue(sr.contains("omega"));
        Assertions.assertFalse(sr.contains("zeta"));
        Assertions.assertFalse(sr.contains(123));
        Assertions.assertFalse(sr.contains(null));
        Assertions.assertEquals("alpha..omega", sr.toString());

        ComparableRange<LocalDate> dates =
                Range.ofComparable(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false);
        Assertions.assertTrue(dates.contains(LocalDate.of(2026, 6, 15)));
        Assertions.assertFalse(dates.contains(LocalDate.of(2026, 12, 31)));
    }

    @Test
    public void testOperatorsModAndFloorDiv() {
        OpSite site = new OpSite("a", "b");
        Assertions.assertEquals(1, Operators.mod(10, 3, site));
        Assertions.assertEquals(4, Operators.mod(-1, 5, site));
        Assertions.assertEquals(-2, Operators.mod(10, -3, site));
        Assertions.assertEquals(-1, Operators.mod(-10, -3, site));
        Assertions.assertEquals(0, Operators.mod(Integer.MIN_VALUE, -1, site));

        Assertions.assertEquals(3, Operators.floorDiv(7, 2, site));
        Assertions.assertEquals(-4, Operators.floorDiv(-7, 2, site));
        Assertions.assertEquals(-4, Operators.floorDiv(7, -2, site));
        Assertions.assertEquals(3, Operators.floorDiv(-7, -2, site));
        Assertions.assertEquals(Integer.MIN_VALUE, Operators.floorDiv(Integer.MIN_VALUE, -1, site));
        Assertions.assertEquals(Long.MIN_VALUE, Operators.floorDiv(Long.MIN_VALUE, -1L, site));
        Assertions.assertEquals(0L, Operators.mod(Long.MIN_VALUE, -1L, site));

        // Byte/short promote to int; mixed with long promotes to long.
        Assertions.assertEquals(1, Operators.mod((byte) 7, (short) 3, site));
        Assertions.assertEquals(2, Operators.floorDiv((byte) 7, (short) 3, site));
        Assertions.assertEquals(4L, Operators.mod(-1, 5L, site));
        Assertions.assertEquals(-4L, Operators.floorDiv(-7L, 2, site));

        // Zero divisor throws ArithmeticException; float/double throw BabbleException.
        Assertions.assertThrows(ArithmeticException.class, () -> Operators.mod(1, 0, site));
        Assertions.assertThrows(ArithmeticException.class, () -> Operators.floorDiv(1, 0, site));
        BabbleException badDouble =
                Assertions.assertThrows(BabbleException.class, () -> Operators.mod(1.5, 2, site));
        Assertions.assertEquals("Not an integer : a", badDouble.getMessage());
        BabbleException badFloat =
                Assertions.assertThrows(
                        BabbleException.class, () -> Operators.floorDiv(1, 2.0f, site));
        Assertions.assertEquals("Not an integer : b", badFloat.getMessage());

        Assertions.assertEquals("mod", Operators.method("mod"));
        Assertions.assertEquals("floorDiv", Operators.method("//"));
        Assertions.assertEquals("//", Operators.compound("//="));
    }
}
