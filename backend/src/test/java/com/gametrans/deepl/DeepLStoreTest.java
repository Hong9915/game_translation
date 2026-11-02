package com.gametrans.deepl;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.FakeMtClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.project.ProjectRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class DeepLStoreTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DeepLCache cache;
    @Autowired DeepLGlossaries glossaries;
    @Autowired MtClient mt;
    @Autowired ProjectRepository projects;
    FakeMtClient fake;

    @BeforeEach
    void setUp() { fake = (FakeMtClient) mt; fake.reset(); }

    private long gameId() throws Exception {
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        return projects.findById(pid).orElseThrow().getGame().getId();
    }

    @Test
    void cacheKeepsOneTranslationPerSourceAndLanguage() {
        String src = "缓存测试" + System.nanoTime();
        cache.save(Map.of(src, "첫 번역"), "ZH");
        cache.save(Map.of(src, "새 번역"), "ZH");
        cache.save(Map.of(src, "영어에서"), "EN");
        assertThat(cache.load(List.of(src, "없는 문장"), "ZH")).containsExactly(Map.entry(src, "새 번역"));
        assertThat(cache.load(List.of(src), "EN")).containsEntry(src, "영어에서");
    }

    @Test
    void reusesTheGlossaryWhileTheTermsStayTheSameAndReplacesItWhenTheyChange() throws Exception {
        long gameId = gameId();
        Map<String, String> terms = new LinkedHashMap<>(Map.of("射手", "사수"));
        String first = glossaries.idFor(gameId, terms);
        assertThat(glossaries.idFor(gameId, new LinkedHashMap<>(terms))).isEqualTo(first);
        assertThat(fake.glossaries).hasSize(1);

        terms.put("红色", "빨간색");
        String second = glossaries.idFor(gameId, terms);
        assertThat(second).isNotEqualTo(first);
        assertThat(fake.glossaries.get(second)).containsEntry("红色", "빨간색");
        assertThat(fake.deleted).isEmpty();   // 그 단어장으로 아직 번역 중인 작업이 있을 수 있다 — 한 번 더 바뀔 때 지운다

        terms.put("绿色", "녹색");
        glossaries.idFor(gameId, terms);
        assertThat(fake.deleted).containsExactly(first);
    }

    /** API Free는 단어장 하나 — 한도에 걸리면 계정을 비우고 다시 만든다(2026-10-26 456 "Too many glossaries"). */
    @Test
    void onTheFreePlanAFullAccountIsClearedAndTheGlossaryMadeAgain() throws Exception {
        fake.maxGlossaries = 1;
        fake.createGlossary("game-local-test", "ZH", Map.of("旧", "옛"));   // 로컬 미리보기가 남긴 단어장
        long gameId = gameId();
        Map<String, String> terms = new LinkedHashMap<>(Map.of("射手", "사수"));
        String first = glossaries.idFor(gameId, terms);
        assertThat(fake.glossaries.keySet()).containsExactly(first);

        terms.put("红色", "빨간색");
        String second = glossaries.idFor(gameId, terms);
        assertThat(fake.glossaries.keySet()).containsExactly(second);
        assertThat(fake.deleted).contains(first);
    }

    @Test
    void aGlossaryDeletedElsewhereIsMadeAgain() throws Exception {
        long gameId = gameId();
        Map<String, String> terms = Map.of("射手", "사수");
        String first = glossaries.idFor(gameId, terms);
        fake.deleteGlossary(first);   // 로컬 미리보기나 다른 게임이 지웠다
        String again = glossaries.idFor(gameId, terms);
        assertThat(again).isNotEqualTo(first);
        assertThat(fake.glossaries).containsKey(again);
    }

    @Test
    void noTermsMeansNoGlossary() throws Exception {
        assertThat(glossaries.idFor(gameId(), Map.of())).isNull();
        assertThat(fake.glossaries).isEmpty();
    }

    @Test
    void cleansEntriesDeepLWouldReject() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(" 射手 ", " 사수 ");
        raw.put("红\t色", "빨간색");
        raw.put("绿色", "녹\n색");
        raw.put("蓝色", " ");
        raw.put("", "빈 원문");
        raw.put("\u00a0领主\ufeff", "군주\u00a0");
        raw.put("紫\u000b色", "보라색");
        raw.put("白色", "흰\u2028색");
        assertThat(DeepLGlossaries.clean(raw)).containsExactly(Map.entry("射手", "사수"), Map.entry("领主", "군주"));
    }

    @Test
    void cacheTakesVeryLongSentences() {
        // 같은 글자를 되풀이하면 압축돼 색인 한도에 안 걸린다 — 실제 문장처럼 서로 다른 글자로
        java.util.Random rnd = new java.util.Random(System.nanoTime());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append((char) (0x4e00 + rnd.nextInt(0x5000)));
        String src = sb.toString();
        cache.save(Map.of(src, "긴 번역"), "ZH");
        assertThat(cache.load(List.of(src), "ZH")).containsEntry(src, "긴 번역");
    }
}
