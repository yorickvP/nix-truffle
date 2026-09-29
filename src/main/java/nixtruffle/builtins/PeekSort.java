package nixtruffle.builtins;

/**
 * Port of CppNix's {@code peeksort} ({@code libutil/sort.hh}), which {@code builtins.sort} uses. A
 * stable merge sort that detects existing runs; with a comparator that isn't a strict weak order
 * (e.g. {@code a: b: true}) the result is some permutation of the input, and which one depends on
 * the exact sequence of comparisons, so this follows the original step by step.
 */
final class PeekSort {
    private PeekSort() {}

    interface Less {
        boolean lt(Object a, Object b);
    }

    private static final int INSERTION_SORT_THRESHOLD = 16;

    static void sort(Object[] a, Less comp) {
        int n = a.length;
        if (n < 2) return;
        if (n == 2) {
            if (comp.lt(a[1], a[0])) swap(a, 0, 1);
            return;
        }
        impl(a, new Object[n], comp, 0, n, 0, n);
    }

    private static void impl(Object[] a, Object[] buf, Less comp, int begin, int end, int leftRunEnd, int rightRunBegin) {
        if (leftRunEnd == end || rightRunBegin == begin) return;
        int length = end - begin;
        if (length <= INSERTION_SORT_THRESHOLD) {
            insertionSort(a, begin, end, comp);
            return;
        }
        int middle = begin + length / 2;
        if (middle <= leftRunEnd) {
            impl(a, buf, comp, leftRunEnd, end, leftRunEnd + 1, rightRunBegin);
            merge(a, begin, leftRunEnd, end, buf, comp);
            return;
        } else if (middle >= rightRunBegin) {
            impl(a, buf, comp, begin, rightRunBegin, leftRunEnd, rightRunBegin - 1);
            merge(a, begin, rightRunBegin, end, buf, comp);
            return;
        }
        int i;
        int j;
        if (!comp.lt(a[middle], a[middle - 1])) {
            i = weaklyIncreasingSuffix(a, leftRunEnd, middle, comp);
            j = weaklyIncreasingPrefix(a, middle - 1, rightRunBegin, comp);
        } else {
            i = strictlyDecreasingSuffix(a, leftRunEnd, middle, comp, false);
            j = strictlyDecreasingPrefix(a, middle - 1, rightRunBegin, comp, false);
            reverse(a, i, j);
        }
        if (i == begin && j == end) return; // a single run
        if (middle - i < j - middle) {
            impl(a, buf, comp, begin, i, leftRunEnd, i - 1);
            impl(a, buf, comp, i, end, j, rightRunBegin);
            merge(a, begin, i, end, buf, comp);
        } else {
            impl(a, buf, comp, begin, j, leftRunEnd, i);
            impl(a, buf, comp, j, end, j + 1, rightRunBegin);
            merge(a, begin, j, end, buf, comp);
        }
    }

    /** {@code mergeSortedRunsInPlace}: stable, takes from the left run unless the right is smaller. */
    private static void merge(Object[] a, int begin, int middle, int end, Object[] buf, Less comp) {
        int n = end - begin;
        System.arraycopy(a, begin, buf, 0, n);
        int l = 0;
        int lEnd = middle - begin;
        int r = lEnd;
        int out = begin;
        while (l < lEnd && r < n) a[out++] = !comp.lt(buf[r], buf[l]) ? buf[l++] : buf[r++];
        while (l < lEnd) a[out++] = buf[l++];
        while (r < n) a[out++] = buf[r++];
    }

    private static void insertionSort(Object[] a, int begin, int end, Less comp) {
        if (begin == end) return;
        for (int cur = begin + 1; cur != end; cur++) {
            for (int p = cur; p != begin && comp.lt(a[p], a[p - 1]); p--) swap(a, p, p - 1);
        }
    }

    /** With {@code negate}, the comparator is {@code std::not_fn(comp)}. */
    private static boolean lt(Less comp, Object x, Object y, boolean negate) {
        return comp.lt(x, y) != negate;
    }

    private static int strictlyDecreasingPrefix(Object[] a, int begin, int end, Less comp, boolean negate) {
        if (begin == end) return begin;
        while (begin + 1 != end && lt(comp, a[begin + 1], a[begin], negate)) begin++;
        return begin + 1;
    }

    private static int strictlyDecreasingSuffix(Object[] a, int begin, int end, Less comp, boolean negate) {
        if (begin == end) return end;
        while (end - 1 > begin && lt(comp, a[end - 1], a[end - 2], negate)) end--;
        return end - 1;
    }

    private static int weaklyIncreasingPrefix(Object[] a, int begin, int end, Less comp) {
        return strictlyDecreasingPrefix(a, begin, end, comp, true);
    }

    private static int weaklyIncreasingSuffix(Object[] a, int begin, int end, Less comp) {
        return strictlyDecreasingSuffix(a, begin, end, comp, true);
    }

    private static void reverse(Object[] a, int i, int j) {
        for (j--; i < j; i++, j--) swap(a, i, j);
    }

    private static void swap(Object[] a, int i, int j) {
        Object t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
