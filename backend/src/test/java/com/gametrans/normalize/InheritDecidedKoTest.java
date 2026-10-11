package com.gametrans.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 정규화할 때 같은 게임의 결정을 이어받는다: 기존 한국어가 없는 행은 같은 게임의 다른 파일이 이미 정한
 * 번역(Fix)을 자동으로 물려받는다. 원문이 같은 다른 행들이 서로 다른 값으로 갈렸으면 이어받지 않고
 * DUP_CONFLICT 필터로 넘긴다.
 */
class InheritDecidedKoTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired ProjectRepository projects;

    private long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        return pid;
    }

    private Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void aNewFileInheritsWhatTheGameAlreadyDecided() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        // 4행(伤害减免提升至@10%@)은 기존 한국어가 N/A라 비어 있다. 여기에 게임의 결정을 만든다.
        mvc.perform(put("/api/segments/{id}/translation", row(a, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"피해 감소가 @10%@로 증가합니다.\"}"))
                .andExpect(status().isOk());

        long b = normalized(game); // 정규화 후 이어받아야 한다

        assertThat(revisions.current(row(b, 4).getId()).orElseThrow().getText()).isEqualTo("피해 감소가 @10%@로 증가합니다.");
    }

    @Test
    void anInheritedRowIsCheckedAgainstTheGlossary() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        mvc.perform(put("/api/segments/{id}/translation", row(a, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"피해 감소가 @10%@로 증가합니다.\"}"))
                .andExpect(status().isOk());
        // 게임 단어장: 伤害 → 대미지. 이어받을 문장("피해 감소가 …")에는 "대미지"가 없다.
        Long gameId = projects.findById(a).orElseThrow().getGame().getId();
        mvc.perform(post("/api/games/{id}/glossary", gameId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SRC\",\"matchMode\":\"PARTIAL\",\"find\":\"伤害\",\"ko\":\"대미지\"}"))
                .andExpect(status().isOk());

        long b = normalized(game);

        // 이어받은 순간 용어 불일치가 표시된다 — "불일치 다시 계산"을 누르기 전에도
        Segment inherited = row(b, 4);
        assertThat(revisions.current(inherited.getId()).orElseThrow().getText()).isEqualTo("피해 감소가 @10%@로 증가합니다.");
        assertThat(inherited.getTermMiss()).contains("伤害→대미지");
        mvc.perform(get("/api/review/{id}/rows", b).param("sheet", "Skill").param("filter", "TERM_MISS"))
                .andExpect(jsonPath("$.rows[?(@.rowNo==4)].termMiss[0]").value("伤害→대미지"));
    }

    @Test
    void koAlreadyInTheFileIsNotOverwritten() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        // a의 2행을 고쳐 게임의 결정을 "정의의 판결"로 만든다
        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 판결\"}"))
                .andExpect(status().isOk());

        long b = normalized(game);

        // b의 2행은 파일에 기존 한국어 "정의의 심판"이 0차 리비전으로 들어갔다.
        // 게임의 결정이 달라도 덮지 않는다 — 대신 DUP_CONFLICT 필터에 걸려 검수자가 판단한다.
        assertThat(revisions.current(row(b, 2).getId()).orElseThrow().getText()).isEqualTo("정의의 심판");
        mvc.perform(get("/api/review/{id}/rows", b).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void disagreementBetweenEarlierProjectsIsNotInherited() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        // 둘 다 먼저 확정로 전파를 막아서 서로 자동 동기화(같은 원문 통일)되지 않게 한 뒤, 각기 다른 값으로 확정한다.
        mvc.perform(put("/api/segments/{id}/marks", row(a, 4).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/marks", row(b, 4).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", row(a, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"값A\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", row(b, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"값B\"}")).andExpect(status().isOk());

        long c = normalized(game);

        // 같은 게임의 두 파일이 서로 다른 값으로 확정했으므로 새 파일은 이어받지 않는다.
        assertThat(revisions.current(row(c, 4).getId())).isEmpty();
    }

    @Test
    void inheritedRowsGetMemoryAuthorAndFileKeptRowsStayExisting() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        mvc.perform(put("/api/segments/{id}/translation", row(a, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"피해 감소가 @10%@로 증가합니다.\"}"))
                .andExpect(status().isOk());

        long b = normalized(game);

        assertThat(revisions.current(row(b, 4).getId()).orElseThrow().getAuthor()).isEqualTo(TranslationRevision.Author.MEMORY);
        // 파일에 이미 있던 한국어(2행)는 손대지 않고 EXISTING 그대로다.
        assertThat(revisions.current(row(b, 2).getId()).orElseThrow().getAuthor()).isEqualTo(TranslationRevision.Author.EXISTING);
    }

    @Test
    void renormalizingTheSameProjectDoesNotDuplicateMemoryRevisions() throws Exception {
        String game = TestProjects.freshGame();
        long other = normalized(game);
        mvc.perform(put("/api/segments/{id}/translation", row(other, 4).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"피해 감소가 @10%@로 증가합니다.\"}"))
                .andExpect(status().isOk());

        long a = normalized(game); // 이어받는다
        long firstSegmentId = row(a, 4).getId();
        assertThat(revisions.bySegmentIds(List.of(firstSegmentId)).get(firstSegmentId)).hasSize(1);

        // 같은 프로젝트를 같은 시트로 다시 정규화한다 — 세그먼트가 통째로 다시 만들어진다(정규화는 두 번 돌 수 있다).
        mvc.perform(put("/api/projects/{id}/normalize", a).contentType(MediaType.APPLICATION_JSON)
                        .content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        Long secondSegmentId = row(a, 4).getId();

        assertThat(revisions.bySegmentIds(List.of(secondSegmentId)).get(secondSegmentId)).hasSize(1);
        assertThat(revisions.current(secondSegmentId).orElseThrow().getAuthor()).isEqualTo(TranslationRevision.Author.MEMORY);
    }
}
