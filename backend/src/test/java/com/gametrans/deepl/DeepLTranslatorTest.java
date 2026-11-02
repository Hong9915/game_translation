package com.gametrans.deepl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.FakeMtClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.deepl.DeepLTranslator.Item;
import com.gametrans.deepl.DeepLTranslator.Result;
import com.gametrans.deepl.DeepLTranslator.Source;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryEntryRepository;
import com.gametrans.project.ProjectRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class DeepLTranslatorTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DeepLTranslator translator;
    @Autowired MtClient mt;
    @Autowired ProjectRepository projects;
    @Autowired GlossaryEntryRepository glossary;
    @Autowired com.gametrans.game.GameRepository games;
    FakeMtClient fake;
    long gameId;
    String tag;   // 테스트마다 다른 원문을 쓴다 — 캐시는 게임과 상관없이 공유된다

    @BeforeEach
    void setUp() throws Exception {
        fake = (FakeMtClient) mt; fake.reset();
        long pid = TestProjects.upload(mvc, TestProjects.freshGame());
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
        tag = Long.toString(System.nanoTime() % 100000);
    }

    private void entry(String find, String ko, GlossaryEntry.MatchMode mode) {
        GlossaryEntry g = new GlossaryEntry();
        g.setGameId(gameId); g.setKind(GlossaryEntry.Kind.SRC); g.setMatchMode(mode);
        g.setFind(find); g.setKo(ko); g.setUpdatedAt(Instant.now());
        glossary.save(g);
    }

    @Test
    void sendsTheSameSourceOnceAndGivesEveryRowTheAnswer() {
        String cn = "烈焰之剑" + tag;
        fake.answers.put(cn, "불꽃의 검" + tag);
        Result r = translator.translate(gameId, List.of(new Item("a", cn, null), new Item("b", cn, null)), "ZH");
        assertThat(fake.sentTexts()).isEqualTo(1);
        assertThat(r.byKey().get("a").ko()).isEqualTo("불꽃의 검" + tag);
        assertThat(r.byKey().get("b").source()).isEqualTo(Source.DEEPL);
    }

    @Test
    void reusesTheCacheWhileItStillFitsTheGlossary() {
        String cn = "霜语者之盾" + tag;
        fake.answers.put(cn, "서리 방패" + tag);
        translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        Result again = translator.translate(gameId, List.of(new Item("b", cn, null)), "ZH");
        assertThat(fake.sentTexts()).isEqualTo(1);
        assertThat(again.byKey().get("b").source()).isEqualTo(Source.CACHE);
        assertThat(again.cachedItems()).isEqualTo(1);
    }

    @Test
    void translatesAgainWhenTheGlossaryNoLongerFitsTheCachedSentence() {
        String cn = "霜语者之盾" + tag;
        fake.answers.put(cn, "서리 방패" + tag);
        translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        entry("霜语者", "프로스트위스퍼", GlossaryEntry.MatchMode.PARTIAL);
        fake.answers.put(cn, "프로스트위스퍼의 방패" + tag);
        Result r = translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        assertThat(fake.sentTexts()).isEqualTo(2);
        assertThat(fake.glossaryIds.get(1)).isNotNull();
        assertThat(r.byKey().get("a").ko()).isEqualTo("프로스트위스퍼의 방패" + tag);
    }

    @Test
    void aWholeRowGlossaryEntryNeedsNoDeepL() {
        entry("霜语者" + tag, "서리속삭임", GlossaryEntry.MatchMode.WHOLE);
        Result r = translator.translate(gameId, List.of(new Item("a", "霜语者" + tag, null)), "ZH");
        assertThat(fake.calls).isEmpty();
        assertThat(r.byKey().get("a").ko()).isEqualTo("서리속삭임");
        assertThat(r.byKey().get("a").source()).isEqualTo(Source.GLOSSARY);
    }

    @Test
    void retriesWithEveryTagWrappedWhenTheFirstAnswerLeavesChinese() {
        String cn = "扇形范围提升至<color=438800>1.5</color>倍" + tag;
        fake.answers.put("扇形范围提升至1.5倍" + tag, "扇형 범위가 1.5배로" + tag);
        fake.answers.put("扇形范围提升至<x i=\"0\"/>倍" + tag, "부채꼴 범위가 <x i=\"0\"/>배로" + tag);
        Result r = translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        assertThat(fake.calls).hasSize(2);
        assertThat(r.byKey().get("a").ko()).isEqualTo("부채꼴 범위가 <color=438800>1.5</color>배로" + tag);
        assertThat(r.byKey().get("a").problem()).isNull();
    }

    @Test
    void marksTheProblemWhenBothWaysFail() {
        String cn = "持续{0}秒" + tag;
        fake.answers.put("持续<x i=\"0\"/>秒" + tag, "초 동안 지속" + tag);   // 두 방식 모두 같은 xml → 두 번 다 {0}을 잃는다
        Result r = translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        assertThat(r.byKey().get("a").problem()).isEqualTo("TAG");
        assertThat(DeepLTranslator.problemNote("TAG")).isEqualTo("DeepL: 태그 어긋남");
    }

    @Test
    void sendsEnglishWithoutTheGlossaryWhenAskedTo() {
        entry("剑", "검", GlossaryEntry.MatchMode.PARTIAL);
        Result r = translator.translate(gameId, List.of(new Item("a", "长剑" + tag, "Long sword " + tag), new Item("b", "短剑" + tag, null)), "EN");
        assertThat(fake.sourceLangs).containsExactlyInAnyOrder("EN", "ZH");
        int en = fake.sourceLangs.indexOf("EN");
        assertThat(fake.calls.get(en)).containsExactly("Long sword " + tag);
        assertThat(fake.glossaryIds.get(en)).isNull();
        assertThat(r.byKey()).containsKeys("a", "b");
    }

    @Test
    void refusesWithoutAKeyOnlyWhenSomethingMustBeSent() {
        fake.configured = false;
        entry("霜语者" + tag, "서리속삭임", GlossaryEntry.MatchMode.WHOLE);
        assertThat(translator.translate(gameId, List.of(new Item("a", "霜语者" + tag, null)), "ZH").byKey()).containsKey("a");
        assertThatThrownBy(() -> translator.translate(gameId, List.of(new Item("b", "新句子" + tag, null)), "ZH"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DeepL 키가 없습니다");
    }

    @Test
    void aRejectedRequestOnlyLosesItsOwnSentencesAndTheRestIsCached() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) items.add(new Item("k" + i, "句子" + i + "号" + tag, null));
        fake.failWhen = texts -> texts.stream().anyMatch(t -> t.startsWith("句子50号"));
        Result r = translator.translate(gameId, items, "ZH");
        assertThat(r.byKey()).hasSize(50).doesNotContainKey("k50");
        assertThat(r.failedTexts()).isEqualTo(1);
        fake.failWhen = null;
        Result again = translator.translate(gameId, items, "ZH");
        assertThat(again.cachedItems()).isEqualTo(50);
        assertThat(again.byKey()).hasSize(51);
    }

    @Test
    void longSentencesGoInSmallerRequestsAndCanBeCached() {
        String big = "长".repeat(20000);
        java.util.List<Item> items = java.util.List.of(new Item("a", big + "一" + tag, null), new Item("b", big + "二" + tag, null), new Item("c", big + "三" + tag, null));
        Result r = translator.translate(gameId, items, "ZH");
        assertThat(fake.calls).hasSize(3);
        assertThat(r.byKey()).hasSize(3);
        assertThat(translator.translate(gameId, items, "ZH").cachedItems()).isEqualTo(3);
    }
    @Test
    void translatesEachLineByItselfAndKeepsTheBreaksWhereTheyWere() {
        String cn = "第一行" + tag + "<br/>第二行" + tag + "\\n第三行" + tag + "\n第四行" + tag;
        fake.answers.put("第一行" + tag, "첫 줄" + tag);
        fake.answers.put("第二行" + tag, "둘째 줄" + tag);
        fake.answers.put("第三行" + tag, "셋째 줄" + tag);
        fake.answers.put("第四行" + tag, "넷째 줄" + tag);
        Result r = translator.translate(gameId, List.of(new Item("a", cn, null)), "ZH");
        assertThat(fake.calls.get(0)).containsExactly("第一行" + tag, "第二行" + tag, "第三行" + tag, "第四行" + tag);
        assertThat(r.byKey().get("a").ko()).isEqualTo("첫 줄" + tag + "<br/>둘째 줄" + tag + "\\n셋째 줄" + tag + "\n넷째 줄" + tag);
        assertThat(r.byKey().get("a").problem()).isNull();
    }

    @Test
    void aLineSharedByTwoSentencesIsSentOnceAndEmptyLinesStayEmpty() {
        fake.answers.put("公共行" + tag, "같은 줄" + tag);
        Result r = translator.translate(gameId, List.of(
                new Item("a", "公共行" + tag + "<br/><br/>甲" + tag, null),
                new Item("b", "公共行" + tag + "\n乙" + tag, null)), "ZH");
        assertThat(fake.sentTexts()).isEqualTo(3);
        assertThat(r.byKey().get("a").ko()).isEqualTo("같은 줄" + tag + "<br/><br/>가" + tag);
        assertThat(r.byKey().get("b").ko()).startsWith("같은 줄" + tag + "\n");
    }

    @Test
    void oneBadLineMarksTheWholeSentence() {
        fake.answers.put("持续<x i=\"0\"/>秒" + tag, "초 동안 지속" + tag);
        Result r = translator.translate(gameId, List.of(new Item("a", "好行" + tag + "\n持续{0}秒" + tag, null)), "ZH");
        assertThat(r.byKey().get("a").problem()).isEqualTo("TAG");
    }

    /** 게임의 DeepL 지시문을 요청에 싣고, 지시문이 바뀌면 그 전에 받은 캐시를 쓰지 않는다(같은 지시문이면 쓴다). */
    @Test
    void sendsTheGameInstructionsAndKeepsTheCacheApartPerInstructions() {
        String src = "头盔" + tag;
        Result first = translator.translate(gameId, List.of(new Item("a", src, null)), "ZH");
        assertThat(first.byKey().get("a").source()).isEqualTo(Source.DEEPL);
        assertThat(fake.instructions.get(0)).isEqualTo(com.gametrans.game.DeepLInstructions.lines(com.gametrans.game.DeepLInstructions.DEFAULT));

        com.gametrans.game.Game g = games.findById(gameId).orElseThrow();
        g.setDeeplInstructions("짧게\n\n게임 용어로 ");
        games.save(g);
        Result second = translator.translate(gameId, List.of(new Item("a", src, null)), "ZH");
        assertThat(second.byKey().get("a").source()).isEqualTo(Source.DEEPL);
        assertThat(fake.instructions.get(1)).containsExactly("짧게", "게임 용어로");

        Result third = translator.translate(gameId, List.of(new Item("a", src, null)), "ZH");
        assertThat(third.byKey().get("a").source()).isEqualTo(Source.CACHE);
        assertThat(fake.calls).hasSize(2);
    }
}
