package com.gametrans.text;

public final class LengthCheck {
    private LengthCheck() {}

    public static int count(String s) {
        if (s == null) return 0;
        String stripped = PlaceholderCheck.TOKEN.matcher(s).replaceAll("").replaceAll("\\s+", "");
        return stripped.codePointCount(0, stripped.length());
    }

    public static boolean flagged(String src, String ko, double ratio) {
        int srcLen = count(src);
        if (srcLen == 0) return false;
        return count(ko) > srcLen * ratio;
    }
}
