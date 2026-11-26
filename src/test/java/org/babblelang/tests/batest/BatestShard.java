package org.babblelang.tests.batest;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Parses and evaluates {@code -Dbatest.shard=<N>/<M>} expressions for deterministic CI sharding of
 * {@code .batest.md} cases.
 *
 * <p>{@code N} is a zero-based shard index ({@code 0 <= N < M}) and {@code M >= 1} is the total
 * number of shards. When active, a case with normalized file path {@code rel} and 1-based {@code
 * caseIndex} belongs to shard {@code N} iff {@code CRC32(rel + "#" + caseIndex) % M == N}.
 */
public final class BatestShard {
    private static final BatestShard ALL = new BatestShard(-1, 0);

    private final int index;
    private final int total;

    private BatestShard(int index, int total) {
        this.index = index;
        this.total = total;
    }

    public static BatestShard parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String trimmed = raw.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash != trimmed.lastIndexOf('/') || slash == trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid -Dbatest.shard (expected <N>/<M>) : " + raw);
        }
        int index;
        int total;
        try {
            index = Integer.parseInt(trimmed.substring(0, slash).trim());
            total = Integer.parseInt(trimmed.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid -Dbatest.shard (non-integer <N>/<M>) : " + raw, e);
        }
        if (total <= 0 || index < 0 || index >= total) {
            throw new IllegalArgumentException(
                    "Invalid -Dbatest.shard (require 0 <= N < M and M >= 1) : " + raw);
        }
        return new BatestShard(index, total);
    }

    public boolean isActive() {
        return total > 0;
    }

    public int index() {
        return index;
    }

    public int total() {
        return total;
    }

    public boolean matchesCase(String relativePath, int caseIndex) {
        if (!isActive()) {
            return true;
        }
        CRC32 crc = new CRC32();
        crc.update((relativePath + "#" + caseIndex).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % total) == index;
    }
}
