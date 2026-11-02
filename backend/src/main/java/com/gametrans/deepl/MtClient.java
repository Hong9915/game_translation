package com.gametrans.deepl;

import java.util.List;
import java.util.Map;

/** 기계 번역(DeepL) 호출. 문장은 DeepLTags가 감싼 XML이다. 테스트는 FakeMtClient로 바꾼다. */
public interface MtClient {
    boolean configured();
    /** xmlTexts 순서대로 번역문을 돌려준다. sourceLang: "ZH" | "EN". glossaryId는 없으면 null. instructions: DeepL 지시문(빈 목록이면 보내지 않음). */
    List<String> translate(List<String> xmlTexts, String sourceLang, String glossaryId, List<String> instructions);
    /** 원문 → 번역어 항목으로 단어장을 만들고 id를 돌려준다(대상 언어는 한국어). */
    String createGlossary(String name, String sourceLang, Map<String, String> entries);
    void deleteGlossary(String glossaryId);
    /** 이 계정에 있는 단어장 id 전부. 다른 곳(로컬 미리보기·다른 게임)에서 지웠는지 보려고 쓴다. */
    List<String> glossaryIds();
}
