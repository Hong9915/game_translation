package com.gametrans;

import com.gametrans.deepl.MtClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DeepL 대신. answers에 없는 문장은 한자를 "가"로 바꿔 돌려준다(태그는 그대로 — 깨끗한 번역으로 통과). */
public class FakeMtClient implements MtClient {
    public boolean configured = true;
    public final Map<String, String> answers = new HashMap<>();
    public final List<List<String>> calls = new ArrayList<>();
    public final List<String> glossaryIds = new ArrayList<>();
    public final List<String> sourceLangs = new ArrayList<>();
    /** 요청마다 넘어온 DeepL 지시문. */
    public final List<List<String>> instructions = new ArrayList<>();
    public final Map<String, Map<String, String>> glossaries = new LinkedHashMap<>();
    public final List<String> deleted = new ArrayList<>();
    public RuntimeException failNext;
    public Runnable beforeTranslate;
    /** 이 조건에 맞는 요청은 매번 실패한다(한 요청만 계속 거절되는 경우). */
    public java.util.function.Predicate<List<String>> failWhen;
    /** null이 아니면 단어장 만들기가 이 예외를 던진다. */
    public RuntimeException failGlossary;
    /** null이 아니면 계정에 둘 수 있는 단어장 수(API Free = 1). 넘으면 456처럼 GlossaryLimit. */
    public Integer maxGlossaries;
    private int nextId = 1;

    public void reset() {
        configured = true; answers.clear(); calls.clear(); glossaryIds.clear(); sourceLangs.clear(); instructions.clear();
        glossaries.clear(); deleted.clear(); failNext = null; beforeTranslate = null; failWhen = null; failGlossary = null; maxGlossaries = null;
    }

    public int sentTexts() { return calls.stream().mapToInt(List::size).sum(); }

    @Override public boolean configured() { return configured; }

    @Override public List<String> translate(List<String> xmlTexts, String sourceLang, String glossaryId, List<String> instructions) {
        if (beforeTranslate != null) { Runnable r = beforeTranslate; beforeTranslate = null; r.run(); }
        if (failNext != null) { RuntimeException e = failNext; failNext = null; throw e; }
        if (failWhen != null && failWhen.test(xmlTexts)) throw new com.gametrans.deepl.MtException("DeepL 413");
        calls.add(new ArrayList<>(xmlTexts)); glossaryIds.add(glossaryId); sourceLangs.add(sourceLang); this.instructions.add(instructions);
        List<String> out = new ArrayList<>();
        for (String t : xmlTexts) out.add(answers.containsKey(t) ? answers.get(t) : t.replaceAll("[\\u4e00-\\u9fff]", "가"));
        return out;
    }

    @Override public String createGlossary(String name, String sourceLang, Map<String, String> entries) {
        if (failGlossary != null) throw failGlossary;
        if (maxGlossaries != null && glossaries.size() >= maxGlossaries)
            throw new com.gametrans.deepl.MtException.GlossaryLimit("456 Too many glossaries", null);
        String id = "g" + nextId++;
        glossaries.put(id, new LinkedHashMap<>(entries));
        return id;
    }

    @Override public void deleteGlossary(String glossaryId) { deleted.add(glossaryId); glossaries.remove(glossaryId); }

    @Override public List<String> glossaryIds() { return new ArrayList<>(glossaries.keySet()); }
}
