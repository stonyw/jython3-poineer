package org.python.core;

/**
 * The substring search CPython runs, ported from {@code Objects/stringlib/fastsearch.h} of 3.14.
 *
 * <p>Three algorithms sit in one dispatch, exactly as the reference dispatches them. A Bloom-filtered
 * Boyer-Moore scan ({@code default_find}) handles short haystacks and needles; Crochemore and Perrin's
 * two-way algorithm handles the cases where a needle is small next to the haystack and a linear bound
 * matters; and between them an adaptive scan watches how much of the needle it has matched before
 * giving up and promoting the search to two-way. The right-facing search has its own reversed
 * Boyer-Moore scan. The dispatch boundaries are the reference's own ({@code n < 2500},
 * {@code (m >> 2) * 3 < (n >> 2)}, {@code m < 6}), because a different split would change which of
 * the reference's pathological inputs are fast.
 *
 * <p>A {@code str} is searched in UTF-16 units rather than code points, which is what the previous
 * {@code String.indexOf} based implementation did: a lone surrogate can therefore match half of a
 * surrogate pair, a case the reference (which searches code points) does not have. Keeping the unit
 * domain is what lets this file be one port instead of two, and it leaves every index the callers
 * hand back unchanged.
 */
final class FastSearch {

    /** {@code FAST_COUNT}/{@code FAST_SEARCH}/{@code FAST_RSEARCH} of the reference. */
    private static final int COUNT = 0;
    private static final int SEARCH = 1;
    private static final int RSEARCH = 2;

    /** {@code TABLE_SIZE_BITS}/{@code TABLE_SIZE}/{@code TABLE_MASK}, with a 64-bit Bloom mask. */
    private static final int TABLE_SIZE = 64;
    private static final int TABLE_MASK = 63;

    /** {@code MAX_SHIFT}: the shift table is {@code uint8_t}. */
    private static final int MAX_SHIFT = 255;

    private FastSearch() {}

    /** The characters a search reads: UTF-16 units of a {@code String}, octets of a {@code byte[]}. */
    private interface Src {
        int length();

        int at(int index);
    }

    private static final class Chars implements Src {
        private final String value;

        Chars(String value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public int at(int index) {
            return value.charAt(index);
        }
    }

    private static final class Octets implements Src {
        private final byte[] value;

        Octets(byte[] value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length;
        }

        @Override
        public int at(int index) {
            return value[index] & 0xFF;
        }
    }

    // --- entry points ----------------------------------------------------------------------

    /**
     * Where {@code needle} is in {@code text[base, base + length)}, absolutely, or -1.
     *
     * <p>The reference works on pointers into a window; the same window here is a base offset, so
     * the answer is translated back once at the edge instead of by every internal return.
     */
    static int find(String text, int base, int length, String needle, boolean right) {
        int found = run(new Chars(text), base, length, new Chars(needle), needle.length(),
                Long.MAX_VALUE, right ? RSEARCH : SEARCH);
        return found < 0 ? -1 : base + found;
    }

    static int count(String text, int base, int length, String needle, long maxcount) {
        return run(new Chars(text), base, length, new Chars(needle), needle.length(), maxcount, COUNT);
    }

    static int find(byte[] text, int base, int length, byte[] needle, boolean right) {
        int found = run(new Octets(text), base, length, new Octets(needle), needle.length,
                Long.MAX_VALUE, right ? RSEARCH : SEARCH);
        return found < 0 ? -1 : base + found;
    }

    static int count(byte[] text, int base, int length, byte[] needle, long maxcount) {
        return run(new Octets(text), base, length, new Octets(needle), needle.length, maxcount, COUNT);
    }

    // --- dispatch --------------------------------------------------------------------------

    /** {@code FASTSEARCH}: the dispatch between the three scans. */
    private static int run(Src s, int base, int n, Src p, int m, long maxcount, int mode) {
        if (n < m || (mode == COUNT && maxcount == 0)) {
            return -1;
        }
        if (m <= 1) {
            if (m <= 0) {
                return -1;
            }
            if (mode == SEARCH) {
                return findChar(s, base, n, p.at(0));
            } else if (mode == RSEARCH) {
                return rfindChar(s, base, n, p.at(0));
            } else if (maxcount == Long.MAX_VALUE) {
                return countCharNoMax(s, base, n, p.at(0));
            } else {
                return countChar(s, base, n, p.at(0), maxcount);
            }
        }
        if (mode != RSEARCH) {
            if (n < 2500 || (m < 100 && n < 30000) || m < 6) {
                return defaultFind(s, base, n, p, m, maxcount, mode);
            } else if ((m >> 2) * 3 < (n >> 2)) {
                // The two-way startup cost is O(m); it pays for itself once the needle is a small
                // enough fraction of the haystack, which is the reference's 33% rule.
                return mode == SEARCH
                        ? twoWayFind(s, base, n, p, m)
                        : twoWayCount(s, base, n, p, m, maxcount);
            } else {
                return adaptiveFind(s, base, n, p, m, maxcount, mode);
            }
        }
        return defaultRfind(s, base, n, p, m, maxcount, mode);
    }

    private static int findChar(Src s, int base, int n, int ch) {
        for (int i = 0; i < n; i++) {
            if (s.at(base + i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private static int rfindChar(Src s, int base, int n, int ch) {
        for (int i = n - 1; i >= 0; i--) {
            if (s.at(base + i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private static int countChar(Src s, int base, int n, int ch, long maxcount) {
        long count = 0;
        for (int i = 0; i < n; i++) {
            if (s.at(base + i) == ch) {
                count++;
                if (count == maxcount) {
                    return (int) maxcount;
                }
            }
        }
        return (int) count;
    }

    private static int countCharNoMax(Src s, int base, int n, int ch) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (s.at(base + i) == ch) {
                count++;
            }
        }
        return count;
    }

    // --- Boyer-Moore with a Bloom filter ---------------------------------------------------

    private static long bloomAdd(long mask, int ch) {
        return mask | (1L << (ch & TABLE_MASK));
    }

    private static boolean bloomHas(long mask, int ch) {
        return (mask & (1L << (ch & TABLE_MASK))) != 0;
    }

    /** {@code default_find}: the compressed Boyer-Moore scan the short cases use. */
    private static int defaultFind(Src s, int base, int n, Src p, int m, long maxcount, int mode) {
        final int w = n - m;
        int mlast = m - 1;
        int count = 0;
        int gap = mlast;
        int last = p.at(mlast);
        long mask = 0;
        for (int i = 0; i < mlast; i++) {
            mask = bloomAdd(mask, p.at(i));
            if (p.at(i) == last) {
                gap = mlast - i - 1;
            }
        }
        mask = bloomAdd(mask, last);
        for (int i = 0; i <= w; i++) {
            if (s.at(base + mlast + i) == last) {
                int j = 0;
                for (; j < mlast; j++) {
                    if (s.at(base + i + j) != p.at(j)) {
                        break;
                    }
                }
                if (j == mlast) {
                    if (mode != COUNT) {
                        return i;
                    }
                    count++;
                    if (count == maxcount) {
                        return (int) maxcount;
                    }
                    i += mlast;
                    continue;
                }
                if (i + 1 <= w && !bloomHas(mask, s.at(base + mlast + i + 1))) {
                    i += m;
                } else {
                    i += gap;
                }
            } else if (i + 1 <= w && !bloomHas(mask, s.at(base + mlast + i + 1))) {
                i += m;
            }
        }
        return mode == COUNT ? count : -1;
    }

    /**
     * {@code adaptive_find}: the Boyer-Moore scan, promoting to two-way once it has matched enough
     * characters to predict that the O(m) preprocessing will pay for itself.
     */
    private static int adaptiveFind(Src s, int base, int n, Src p, int m, long maxcount, int mode) {
        final int w = n - m;
        int mlast = m - 1;
        int count = 0;
        int gap = mlast;
        int hits = 0;
        int last = p.at(mlast);
        long mask = 0;
        for (int i = 0; i < mlast; i++) {
            mask = bloomAdd(mask, p.at(i));
            if (p.at(i) == last) {
                gap = mlast - i - 1;
            }
        }
        mask = bloomAdd(mask, last);
        for (int i = 0; i <= w; i++) {
            if (s.at(base + mlast + i) == last) {
                int j = 0;
                for (; j < mlast; j++) {
                    if (s.at(base + i + j) != p.at(j)) {
                        break;
                    }
                }
                if (j == mlast) {
                    if (mode != COUNT) {
                        return i;
                    }
                    count++;
                    if (count == maxcount) {
                        return (int) maxcount;
                    }
                    i += mlast;
                    continue;
                }
                hits += j + 1;
                if (hits > m / 4 && w - i > 2000) {
                    if (mode == SEARCH) {
                        int res = twoWayFind(s, base + i, n - i, p, m);
                        return res == -1 ? -1 : res + i;
                    }
                    int res = twoWayCount(s, base + i, n - i, p, m, maxcount - count);
                    return res + count;
                }
                if (i + 1 <= w && !bloomHas(mask, s.at(base + mlast + i + 1))) {
                    i += m;
                } else {
                    i += gap;
                }
            } else if (i + 1 <= w && !bloomHas(mask, s.at(base + mlast + i + 1))) {
                i += m;
            }
        }
        return mode == COUNT ? count : -1;
    }

    /** {@code default_rfind}: the same scan looking backwards. */
    private static int defaultRfind(Src s, int base, int n, Src p, int m, long maxcount, int mode) {
        long mask = 0;
        int mlast = m - 1;
        int skip = m - 1;
        int w = n - m;
        mask = bloomAdd(mask, p.at(0));
        for (int i = mlast; i > 0; i--) {
            mask = bloomAdd(mask, p.at(i));
            if (p.at(i) == p.at(0)) {
                skip = i - 1;
            }
        }
        for (int i = w; i >= 0; i--) {
            if (s.at(base + i) == p.at(0)) {
                int j = mlast;
                for (; j > 0; j--) {
                    if (s.at(base + i + j) != p.at(j)) {
                        break;
                    }
                }
                if (j == 0) {
                    return i;
                }
                if (i > 0 && !bloomHas(mask, s.at(base + i - 1))) {
                    i -= m;
                } else {
                    i -= skip;
                }
            } else if (i > 0 && !bloomHas(mask, s.at(base + i - 1))) {
                i -= m;
            }
        }
        return -1;
    }

    // --- Crochemore-Perrin two-way ---------------------------------------------------------

    /** The needle's critical factorization and the shift table the two-way scan reads. */
    private static final class Prework {
        final Src needle;
        final int len;
        final int cut;
        final int period;
        final int gap;
        final boolean periodic;
        final int[] table;

        Prework(Src needle, int len, int cut, int period, int gap, boolean periodic, int[] table) {
            this.needle = needle;
            this.len = len;
            this.cut = cut;
            this.period = period;
            this.gap = gap;
            this.periodic = periodic;
            this.table = table;
        }
    }

    /**
     * {@code _lex_search}: the lexicographically maximal suffix and the period of the right half.
     *
     * <p>The period is written into {@code periodOut[0]}, mirroring the reference's out parameter.
     */
    private static int lexSearch(Src needle, int len, int[] periodOut, boolean invertAlphabet) {
        int maxSuffix = 0;
        int candidate = 1;
        int k = 0;
        int period = 1;
        while (candidate + k < len) {
            int a = needle.at(candidate + k);
            int b = needle.at(maxSuffix + k);
            if (invertAlphabet ? b < a : a < b) {
                // Fell short of max_suffix: the next k + 1 characters cannot start a maximal suffix.
                candidate += k + 1;
                k = 0;
                period = candidate - maxSuffix;
            } else if (a == b) {
                if (k + 1 != period) {
                    k++;
                } else {
                    candidate += period;
                    k = 0;
                }
            } else {
                maxSuffix = candidate;
                candidate++;
                k = 0;
                period = 1;
            }
        }
        periodOut[0] = period;
        return maxSuffix;
    }

    /** {@code _factorize}: the later of the two maximal-suffix cuts, and its local period. */
    private static int factorize(Src needle, int len, int[] periodOut) {
        int[] firstPeriod = new int[1];
        int[] secondPeriod = new int[1];
        int cut1 = lexSearch(needle, len, firstPeriod, false);
        int cut2 = lexSearch(needle, len, secondPeriod, true);
        if (cut1 > cut2) {
            periodOut[0] = firstPeriod[0];
            return cut1;
        }
        periodOut[0] = secondPeriod[0];
        return cut2;
    }

    /** {@code _preprocess}: the factorization, the periodicity, the gap and the shift table. */
    private static Prework preprocess(Src needle, int len) {
        int[] periodOut = new int[1];
        int cut = factorize(needle, len, periodOut);
        int period = periodOut[0];
        boolean periodic = true;
        for (int i = 0; i < cut; i++) {
            if (needle.at(i) != needle.at(i + period)) {
                periodic = false;
                break;
            }
        }
        if (!periodic) {
            // A lower bound on the period.
            period = Math.max(cut, len - cut) + 1;
        }
        // The gap between the last character and the previous occurrence of an equivalent character.
        int gap = len;
        int last = needle.at(len - 1) & TABLE_MASK;
        for (int i = len - 2; i >= 0; i--) {
            if ((needle.at(i) & TABLE_MASK) == last) {
                gap = len - 1 - i;
                break;
            }
        }
        int notFoundShift = Math.min(len, MAX_SHIFT);
        int[] table = new int[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            table[i] = notFoundShift;
        }
        for (int i = len - notFoundShift; i < len; i++) {
            table[needle.at(i) & TABLE_MASK] = len - 1 - i;
        }
        return new Prework(needle, len, cut, period, gap, periodic, table);
    }

    private static int twoWayFind(Src s, int base, int n, Src p, int m) {
        return twoWay(s, base, n, preprocess(p, m));
    }

    /** {@code _two_way_count}: find, then step past the whole match and find again. */
    private static int twoWayCount(Src s, int base, int n, Src p, int m, long maxcount) {
        Prework prework = preprocess(p, m);
        int index = 0;
        long count = 0;
        while (true) {
            int result = twoWay(s, base + index, n - index, prework);
            if (result == -1) {
                return (int) count;
            }
            count++;
            if (count == maxcount) {
                return (int) maxcount;
            }
            index += result + m;
        }
    }

    /** {@code _two_way}: Crochemore and Perrin's algorithm, with the reference's short circuits. */
    private static int twoWay(Src s, int base, int n, Prework p) {
        final int len = p.len;
        final int cut = p.cut;
        int period = p.period;
        final Src needle = p.needle;
        final int[] table = p.table;
        int windowLast = base + len - 1;
        final int end = base + n;
        int gap = p.gap;
        int gapJumpEnd = Math.min(len, cut + gap);
        if (p.periodic) {
            int memory = 0;
            while (windowLast < end) {
                boolean direct = false;
                for (;;) {
                    if (!direct) {
                        for (;;) {
                            int shift = table[s.at(windowLast) & TABLE_MASK];
                            windowLast += shift;
                            if (shift == 0) {
                                break;
                            }
                            if (windowLast >= end) {
                                return -1;
                            }
                        }
                    }
                    direct = false;
                    int window = windowLast - len + 1;
                    boolean restart = false;
                    int i = Math.max(cut, memory);
                    for (; i < len; i++) {
                        if (needle.at(i) != s.at(window + i)) {
                            if (i < gapJumpEnd) {
                                // Early right half mismatch: jump by gap.
                                windowLast += gap;
                            } else {
                                // Late right half mismatch: jump by the whole matched run.
                                windowLast += i - cut + 1;
                            }
                            memory = 0;
                            restart = true;
                            break;
                        }
                    }
                    if (restart) {
                        break;
                    }
                    for (i = memory; i < cut; i++) {
                        if (needle.at(i) != s.at(window + i)) {
                            windowLast += period;
                            memory = len - period;
                            if (windowLast >= end) {
                                return -1;
                            }
                            int shift = table[s.at(windowLast) & TABLE_MASK];
                            if (shift != 0) {
                                // A mismatch is already known to the right of where the next window
                                // starts, so jump at least as far as a first-comparison mismatch.
                                int memJump = Math.max(cut, memory) - cut + 1;
                                memory = 0;
                                windowLast += Math.max(shift, memJump);
                                restart = true;
                                break;
                            }
                            direct = true;
                            break;
                        }
                    }
                    if (restart) {
                        break;
                    }
                    if (direct) {
                        continue;
                    }
                    return window - base;
                }
            }
        } else {
            period = Math.max(gap, period);
            while (windowLast < end) {
                for (;;) {
                    int shift = table[s.at(windowLast) & TABLE_MASK];
                    windowLast += shift;
                    if (shift == 0) {
                        break;
                    }
                    if (windowLast >= end) {
                        return -1;
                    }
                }
                int window = windowLast - len + 1;
                int i = cut;
                for (; i < len; i++) {
                    if (needle.at(i) != s.at(window + i)) {
                        if (i < gapJumpEnd) {
                            windowLast += gap;
                        } else {
                            windowLast += i - cut + 1;
                        }
                        break;
                    }
                }
                if (i < len) {
                    continue;
                }
                for (i = 0; i < cut; i++) {
                    if (needle.at(i) != s.at(window + i)) {
                        windowLast += period;
                        break;
                    }
                }
                if (i < cut) {
                    continue;
                }
                return window - base;
            }
        }
        return -1;
    }
}
