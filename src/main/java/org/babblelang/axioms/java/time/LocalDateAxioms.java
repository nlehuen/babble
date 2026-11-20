package org.babblelang.axioms.java.time;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.NonNull;

/**
 * What the analysis assumes about {@code java.time.LocalDate}.
 */
@Axioms(value = java.time.LocalDate.class,
        inherited = false)
interface LocalDateAxioms {
    @NonNull
    java.lang.Object minus(java.time.temporal.TemporalAmount p0);

    @NonNull
    java.lang.Object minus(long p0, java.time.temporal.TemporalUnit p1);

    @NonNull
    java.lang.Object minusDays(long p0);

    @NonNull
    java.lang.Object minusMonths(long p0);

    @NonNull
    java.lang.Object minusWeeks(long p0);

    @NonNull
    java.lang.Object minusYears(long p0);

    @NonNull
    java.lang.Object now();

    @NonNull
    java.lang.Object now(java.time.Clock p0);

    @NonNull
    java.lang.Object now(java.time.ZoneId p0);

    @NonNull
    java.lang.Object of(int p0, java.time.Month p1, int p2);

    @NonNull
    java.lang.Object of(int p0, int p1, int p2);

    @NonNull
    java.lang.Object parse(java.lang.CharSequence p0, java.time.format.DateTimeFormatter p1);

    @NonNull
    java.lang.Object parse(java.lang.CharSequence p0);

    @NonNull
    java.lang.Object plus(java.time.temporal.TemporalAmount p0);

    @NonNull
    java.lang.Object plus(long p0, java.time.temporal.TemporalUnit p1);

    @NonNull
    java.lang.Object plusDays(long p0);

    @NonNull
    java.lang.Object plusMonths(long p0);

    @NonNull
    java.lang.Object plusWeeks(long p0);

    @NonNull
    java.lang.Object plusYears(long p0);

    @NonNull
    java.lang.Object with(java.time.temporal.TemporalAdjuster p0);

    @NonNull
    java.lang.Object with(java.time.temporal.TemporalField p0, long p1);

    @NonNull
    java.lang.Object withDayOfMonth(int p0);

    @NonNull
    java.lang.Object withDayOfYear(int p0);

    @NonNull
    java.lang.Object withMonth(int p0);

    @NonNull
    java.lang.Object withYear(int p0);
}
