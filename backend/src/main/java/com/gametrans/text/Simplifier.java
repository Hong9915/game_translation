package com.gametrans.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 번체 → 간체 글자 변환기. OpenCC {@code tw2s} 설정(지역 낱말 변환 없음)을 그대로 옮겼다:
 * TSPhrases로 mmseg 나누기 → 조각마다 [TWVariantsRevPhrases, TWVariantsRev] → [TSPhrases, TSCharacters].
 * 다만 번체 글자가 없는 칸은 손대지 않는다({@link #toSimplified}). 사전은 처음 쓸 때 한 번 읽는다.
 */
@Component
public class Simplifier {

    /** 사전 하나: 키 → 첫째 값, 가장 긴 키의 길이(char)를 같이 들고 있다. */
    private record Dict(Map<String, String> map, int maxLen) {}

    private record Dicts(Dict phrases, List<Dict> stage1, List<Dict> stage2) {}

    private static final class Holder {
        static final Dicts DICTS = load();
    }

    /**
     * 번체가 섞인 글({@link #hasTraditional})만 tw2s 두 단계로 바꾼다. 번체 글자가 하나도 없는 글은 그대로 돌려준다 —
     * tw2s는 이미 간체인 글의 {@code 么}를 {@code 幺}로, {@code 土著}를 {@code 土着}로 바꾸기 때문이다.
     */
    public String toSimplified(String text) {
        if (text == null) return null;
        if (!hasTraditional(text)) return text;
        Dicts d = Holder.DICTS;
        StringBuilder out = new StringBuilder(text.length());
        for (String segment : segments(d.phrases, text)) {
            out.append(convert(convert(segment, d.stage1), d.stage2));
        }
        return out.toString();
    }

    /** 2단계(TSPhrases·TSCharacters)만 돌렸을 때 글이 바뀌면 번체가 있다고 본다. 나누기는 {@link #toSimplified}와 같다. */
    public boolean hasTraditional(String text) {
        if (text == null || text.isEmpty()) return false;
        Dicts d = Holder.DICTS;
        for (String segment : segments(d.phrases, text)) {
            if (!convert(segment, d.stage2).equals(segment)) return true;
        }
        return false;
    }

    /**
     * OpenCC mmseg: 자리마다 TSPhrases의 가장 긴 키를 찾고, 있으면 그때까지 모인 안 맞은 글자들을 한 조각으로 내보낸 뒤
     * 맞은 키를 한 조각으로 낸다. 없으면 글자를 모음에 더한다. 끝에 남은 모음도 한 조각.
     */
    private static List<String> segments(Dict dict, String text) {
        List<String> out = new ArrayList<>();
        int bufStart = 0;
        int i = 0;
        while (i < text.length()) {
            int n = longest(dict, text, i);
            if (n > 0) {
                if (bufStart < i) out.add(text.substring(bufStart, i));
                out.add(text.substring(i, i + n));
                i += n;
                bufStart = i;
            } else {
                i += Character.charCount(text.codePointAt(i));
            }
        }
        if (bufStart < text.length()) out.add(text.substring(bufStart));
        return out;
    }

    /** text[pos..]에서 사전의 가장 긴 접두 일치 길이(char). 없으면 0. */
    private static int longest(Dict dict, String text, int pos) {
        int max = Math.min(dict.maxLen, text.length() - pos);
        for (int n = max; n > 0; n--) {
            if (dict.map.containsKey(text.substring(pos, pos + n))) return n;
        }
        return 0;
    }

    /** 묶음 변환(OpenCC DictGroup): 현재 자리에서 사전을 순서대로 보고, 접두가 맞는 첫 사전의 가장 긴 일치를 쓴다. */
    private static String convert(String text, List<Dict> group) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            boolean hit = false;
            for (Dict dict : group) {
                int n = longest(dict, text, i);
                if (n > 0) {
                    out.append(dict.map.get(text.substring(i, i + n)));
                    i += n;
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                int n = Character.charCount(text.codePointAt(i));
                out.append(text, i, i + n);
                i += n;
            }
        }
        return out.toString();
    }

    private static Dicts load() {
        Dict phrases = read("TSPhrases.txt", false);
        Dict characters = read("TSCharacters.txt", false);
        Dict revPhrases = read("TWVariantsRevPhrases.txt", false);
        Dict rev = read("TWVariants.txt", true);
        return new Dicts(phrases, List.of(revPhrases, rev), List.of(phrases, characters));
    }

    /** 줄 형식 {@code 키<TAB>값1 값2 ...}. reverse면 값마다 {@code 값 → 키}로 뒤집고 먼저 나온 것을 남긴다. */
    private static Dict read(String name, boolean reverse) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = Simplifier.class.getResourceAsStream("/opencc/" + name)) {
            if (in == null) throw new IllegalStateException("missing resource: /opencc/" + name);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String[] values = line.substring(tab + 1).trim().split("\\s+");
                if (values.length == 0 || values[0].isEmpty()) continue;
                if (reverse) {
                    for (String v : values) map.putIfAbsent(v, key);
                } else {
                    map.putIfAbsent(key, values[0]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int max = map.keySet().stream().mapToInt(String::length).max().orElse(0);
        return new Dict(Map.copyOf(map), max);
    }
}
