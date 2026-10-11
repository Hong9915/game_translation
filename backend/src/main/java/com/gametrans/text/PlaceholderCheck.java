package com.gametrans.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderCheck {
    static final Pattern TOKEN = Pattern.compile("\\{\\d+\\}|%[sd]|</?[A-Za-z][^<>]*>");

    private PlaceholderCheck() {}

    public static boolean matches(String src, String ko) {
        return tokens(src).equals(tokens(ko));
    }

    public static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        Collections.sort(out);
        return out;
    }
}
