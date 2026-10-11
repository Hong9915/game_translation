package com.gametrans.config;

import java.util.List;

/** 목록 API 공통 페이지 자르기. page는 0부터, size는 1~1000으로 제한한다. */
public final class Paging {
    public static final int DEFAULT_SIZE = 50;
    private Paging() {}

    public static int size(Integer size) { return size == null ? DEFAULT_SIZE : Math.max(1, Math.min(1000, size)); }
    public static int page(Integer page) { return page == null ? 0 : Math.max(0, page); }

    public static <T> List<T> slice(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        return all.subList(from, Math.min(from + size, all.size()));
    }
}
