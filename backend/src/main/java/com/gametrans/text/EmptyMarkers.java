package com.gametrans.text;

import java.util.Locale;
import java.util.Set;

/**
 * 셀이 "비어 있음"으로 취급되는지 판별한다. 진짜 빈칸뿐 아니라 게임 데이터에서 빈칸 대신 자주 쓰는
 * 표시(N/A, None, 无, 待定 …)도 같은 뜻으로 본다.
 */
public final class EmptyMarkers {
    private EmptyMarkers() {}

    public static final Set<String> MARKERS = Set.of("n/a", "na", "none", "null", "nil", "-", "tbd", "无", "待定", "暂无");

    /** 빈칸이거나 표시 문자열이면 true. */
    public static boolean isEmpty(String s) {
        return s == null || s.isBlank() || isMarker(s);
    }

    /** 비교용 값: 빈칸·표시 문자열은 빈 문자열로, 그 밖은 trim. 기존 한국어와 최신 한국어가 "달라졌는지"는 이 값끼리 견준다. */
    public static String norm(String s) { return isEmpty(s) ? "" : s.trim(); }

    /** 빈칸은 아니지만 N/A·None 같은 표시 문자열이면 true. */
    public static boolean isMarker(String s) {
        if (s == null) return false;
        return MARKERS.contains(s.trim().toLowerCase(Locale.ROOT));
    }
}
