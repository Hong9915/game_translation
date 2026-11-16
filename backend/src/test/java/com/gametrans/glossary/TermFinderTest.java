package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gametrans.FakeLlmClient;
import com.gametrans.glossary.GlossaryDtos.Rendering;
import com.gametrans.glossary.GlossarySuggester.Row;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.AssistantTurn;
import com.gametrans.llm.LlmClient.ToolCall;
import com.gametrans.llm.LlmClient.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TermFinder(2026-11-02): GPT가 행을 열어 보고 용어의 표기만 고른다. 행 수·예문은 서버가 센다. */
class TermFinderTest {
    final FakeLlmClient llm = new FakeLlmClient();
    final TermFinder finder = new TermFinder(llm, new ObjectMapper());
    final GlossaryVariants.Pairs pairs = GlossaryVariants.pairs(List.of(
            new Row("公会战排行榜", "부족전 랭킹"), new Row("公会战", "부족전"), new Row("公会战", "부족전"),
            new Row("公会战简单难度buff", "부족전 쉬움 난이도 버프"), new Row("公会战困难难度buff", "부족전 어려움 난이도 버프"),
            new Row("兑换商店", "교환소"), new Row("兑换商店", "교환 상점"), new Row("兑换商店", "교환 상점")));

    static ToolCall call(String id, String name, String args) { return new ToolCall(id, name, args); }

    void script(String name, String args) {
        llm.toolTurns.add(new AssistantTurn(null, List.of(call("c" + llm.toolTurns.size(), name, args))));
    }

    /** n번째 GPT 호출에 실린 마지막 메시지(직전 도구 결과)의 내용. */
    String lastMessageOfCall(int n) {
        List<Map<String, Object>> m = llm.toolMessages.get(n);
        return (String) m.get(m.size() - 1).get("content");
    }

    @Test
    void findsRenderingAndServerCountsRows() {
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"}],\"reason\":\"버프는 buff의 번역\"}");
        TermFinder.Found f = finder.find("公会战", "OTHER", pairs);
        assertThat(f.none()).isFalse();
        assertThat(f.reason()).isEqualTo("버프는 buff의 번역");
        assertThat(f.renderings()).containsExactly(new Rendering("부족전", 5, "公会战排行榜", "부족전 랭킹"));
    }

    @Test
    void rejectsRenderingNotInTranslation() {
        script("finish", "{\"renderings\":[{\"ko\":\"길드전\"}]}");
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"}]}");
        TermFinder.Found f = finder.find("公会战", "OTHER", pairs);
        assertThat(f.renderings()).extracting(Rendering::ko).containsExactly("부족전");
        assertThat(lastMessageOfCall(1)).contains("이 표기는 번역본에 없습니다: 길드전");
    }

    @Test
    void ordersByRowsDescending() {
        script("finish", "{\"renderings\":[{\"ko\":\"교환소\"},{\"ko\":\"교환 상점\"}]}");
        TermFinder.Found f = finder.find("兑换商店", "OTHER", pairs);
        assertThat(f.renderings()).extracting(Rendering::ko, Rendering::rows)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("교환 상점", 2), org.assertj.core.groups.Tuple.tuple("교환소", 1));
    }

    @Test
    void trimsAndDedupesRenderings() {
        script("finish", "{\"renderings\":[{\"ko\":\" 교환소 \"},{\"ko\":\"교환소\"}]}");
        TermFinder.Found f = finder.find("兑换商店", "OTHER", pairs);
        assertThat(f.renderings()).extracting(Rendering::ko, Rendering::rows)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("교환소", 1));
    }

    @Test
    void noneReturnsEmpty() {
        script("finish", "{\"none\":true,\"reason\":\"다른 말로 옮겨 찾을 수 없음\"}");
        TermFinder.Found f = finder.find("公会战", "OTHER", pairs);
        assertThat(f.none()).isTrue();
        assertThat(f.renderings()).isEmpty();
    }

    @Test
    void unfinishedReturnsNull() {
        assertThat(finder.find("公会战", "OTHER", pairs)).isNull();
    }

    @Test
    void llmExceptionReturnsNull() {
        LlmClient broken = new FakeLlmClient() {
            @Override public AssistantTurn chatTools(String stage, List<Map<String, Object>> messages, List<ToolSpec> tools) {
                throw new RuntimeException("timeout");
            }
        };
        assertThat(new TermFinder(broken, new ObjectMapper()).find("公会战", "OTHER", pairs)).isNull();
    }

    @Test
    void findRowsCapsPairsButCountsAllRows() {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) rows.add(new Row("公会战" + i, "부족전 " + i));
        GlossaryVariants.Pairs many = GlossaryVariants.pairs(rows);
        script("find_rows", "{\"contains\":\"公会战\",\"limit\":20}");
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"}]}");
        TermFinder.Found f = finder.find("公会战", "OTHER", many);
        assertThat(f.renderings().get(0).rows()).isEqualTo(12);
        String result = lastMessageOfCall(1);
        assertThat(result).contains("\"count\":12");
        assertThat(result.split("\"src\"", -1).length - 1).isEqualTo(8);
        String user = (String) llm.toolMessages.get(0).get(1).get("content");
        assertThat(user.lines().filter(l -> l.startsWith("- ")).count()).isLessThanOrEqualTo(12);
    }

    @Test
    void offersOnlyFindRowsAndFinish() {
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"}]}");
        finder.find("公会战", "OTHER", pairs);
        assertThat(llm.toolSpecsSeen.get(0)).extracting(ToolSpec::name).containsExactly("find_rows", "finish");
    }

    @Test
    void noneNeedsReasonWhenRowsContainTheTerm() {
        script("finish", "{\"none\":true}");
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"}]}");
        TermFinder.Found f = finder.find("公会战", "OTHER", pairs);
        assertThat(f.none()).isFalse();
        assertThat(lastMessageOfCall(1)).contains("원문에 이 용어가 든 행이 5개");
    }

    @Test
    void dropsRenderingThatIsAnotherPlusParticle() {
        GlossaryVariants.Pairs withParticle = GlossaryVariants.pairs(List.of(
                new Row("公会战", "부족전"), new Row("公会战开始", "부족전이 시작"), new Row("公会战奖励", "부족전은 보상")));
        script("finish", "{\"renderings\":[{\"ko\":\"부족전\"},{\"ko\":\"부족전은\"},{\"ko\":\"부족전이\"}]}");
        TermFinder.Found f = finder.find("公会战", "OTHER", withParticle);
        assertThat(f.renderings()).extracting(Rendering::ko).containsExactly("부족전");
        assertThat(f.renderings().get(0).rows()).isEqualTo(3);
    }
}
