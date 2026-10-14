package io.hyperiot.common;

import java.util.List;

public record PageResult<T>(long total, List<T> items) {

    public static final int MAX_SIZE = 200;

    public static int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    public static long offset(int page, int size) {
        return (long) (normalizePage(page) - 1) * normalizeSize(size);
    }
}
