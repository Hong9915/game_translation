package com.gametrans.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ctrl+Z/Ctrl+Y용 되돌리기: 여러 항목을 순서대로 되돌린다. 첫 판 행(원래 빈 칸)은 판을 지워 빈 칸으로 돌리고,
 * 그 되돌리기를 다시 되돌리면(Ctrl+Y) 지운 글을 다시 쓴다.
 */
class ActivityUndoTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired TranslationRevisionRepository revisionRepo;
    @Autowired ActivityRepository repo;

    /** withExistingKoSameSourceTwice(): Skill 시트, 2행과 6행의 원문이 같고 기존 한국어는 "정의의 심판". 5행은 기존 한국어가 비어 리비전이 없다. */
    long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE)).andExpect(status().isOk());
        return pid;
    }

    Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    String text(Segment s) { return revisions.current(s.getId()).map(TranslationRevision::getText).orElse(null); }

    String latestKo(Segment s) { return segments.findById(s.getId()).orElseThrow().getLatestKo(); }

    void save(long sid, String text) throws Exception {
        mvc.perform(put("/api/segments/{id}/translation", sid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isOk());
    }

    /** SaveResult에 activityId가 생기기 전이라 그 프로젝트의 가장 최근 항목(EDIT)을 쓴다. */
    long latestActivity(long pid) { return repo.findByProjectIdOrderByIdDesc(pid).get(0).getId(); }

    DocumentContext undo(List<Long> ids) throws Exception {
        String json = ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{\"activityIds\":[", "]}"));
        String body = mvc.perform(post("/api/activity/undo").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body);
    }

    List<Long> ids(DocumentContext r) {
        List<Number> raw = r.read("$.activityIds");
        return raw.stream().map(Number::longValue).toList();
    }

    @Test
    void restoresPreviousText() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        save(r2.getId(), "A");
        assertThat(text(r6)).isEqualTo("A");

        DocumentContext r = undo(List.of(latestActivity(pid)));

        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(text(r6)).isEqualTo("정의의 심판");
        List<Long> made = ids(r);
        assertThat(made).hasSize(1);
        Activity rev = repo.findById(made.get(0)).orElseThrow();
        assertThat(rev.getKind()).isEqualTo(Activity.Kind.REVERT);
        assertThat(rev.getDetail().get("undo")).isEqualTo(true);
    }

    @Test
    void clearsFirstRevisionRow() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        save(r5.getId(), "새 글");

        DocumentContext r = undo(List.of(latestActivity(pid)));

        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(1);
        assertThat(latestKo(r5)).isNull();
        assertThat(revisionRepo.findBySegmentIdOrderByVersionAsc(r5.getId())).isEmpty();
        assertThat(ids(r)).hasSize(1);
    }

    @Test
    void redoRestoresClearedRow() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        save(r5.getId(), "새 글");
        DocumentContext first = undo(List.of(latestActivity(pid)));

        DocumentContext redo = undo(ids(first));

        assertThat(redo.read("$.reverted", Integer.class)).isEqualTo(1);
        assertThat(text(r5)).isEqualTo("새 글");
        assertThat(latestKo(r5)).isEqualTo("새 글");
        assertThat(revisions.current(r5.getId()).orElseThrow().getAuthor()).isEqualTo(TranslationRevision.Author.HUMAN);
        // 다시 실행도 되돌릴 수 있다 — 또 빈 칸으로.
        undo(ids(redo));
        assertThat(latestKo(r5)).isNull();
    }

    @Test
    void redoRestoresText() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2), r6 = row(pid, 6);
        save(r2.getId(), "A");
        DocumentContext first = undo(List.of(latestActivity(pid)));

        DocumentContext redo = undo(ids(first));

        assertThat(redo.read("$.reverted", Integer.class)).isEqualTo(2);
        assertThat(text(r2)).isEqualTo("A");
        assertThat(text(r6)).isEqualTo("A");
    }

    @Test
    void skipsRowChangedSince() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2);
        save(r2.getId(), "A");
        long id1 = latestActivity(pid);
        save(r2.getId(), "B");

        DocumentContext r = undo(List.of(id1));

        assertThat(text(r2)).isEqualTo("B");
        assertThat(r.read("$.skipped", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void skipsConfirmed() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2);
        save(r2.getId(), "A");
        long id1 = latestActivity(pid);
        mvc.perform(put("/api/segments/{id}/marks", r2.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"))
                .andExpect(status().isOk());

        DocumentContext r = undo(List.of(id1));

        assertThat(text(r2)).isEqualTo("A");
        assertThat(r.read("$.skipped", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void undoesSeveralInOrder() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2), r5 = row(pid, 5);
        save(r2.getId(), "A");
        long id1 = latestActivity(pid);
        save(r5.getId(), "새 글");
        long id2 = latestActivity(pid);

        DocumentContext r = undo(List.of(id2, id1));

        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(3);
        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(latestKo(r5)).isNull();
    }

    @Test
    void undoesNewestFirstRegardlessOfInputOrderAndRedoes() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r2 = row(pid, 2);
        save(r2.getId(), "A");
        long id1 = latestActivity(pid);
        save(r2.getId(), "B");
        long id2 = latestActivity(pid);

        DocumentContext r = undo(List.of(id1, id2, id1));

        assertThat(text(r2)).isEqualTo("정의의 심판");
        assertThat(r.read("$.skipped", Integer.class)).isEqualTo(0);
        DocumentContext redo = undo(ids(r));
        assertThat(text(r2)).isEqualTo("B");
        assertThat(redo.read("$.skipped", Integer.class)).isEqualTo(0);
    }

    @Test
    void revertEndpointStillSkipsFirstRevision() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        save(r5.getId(), "새 글");

        mvc.perform(post("/api/activity/{id}/revert", latestActivity(pid))).andExpect(status().isOk());

        assertThat(text(r5)).isEqualTo("새 글");
    }
    /** 여러 행 다시 번역(translate-batch)도 Ctrl+Z로 되돌린다 — 빈 칸이던 행은 다시 빈 칸. */
    @Test
    void undoesBatchTranslate() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        String body = mvc.perform(post("/api/segments/translate-batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + r5.getId() + "],\"engine\":\"GPT\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(latestKo(r5)).isNotNull();
        java.util.Map<String, Number> made = JsonPath.parse(body).read("$.activityIds");
        List<Long> activityIds = made.values().stream().map(Number::longValue).toList();
        assertThat(activityIds).hasSize(1);

        DocumentContext r = undo(activityIds);

        assertThat(r.read("$.reverted", Integer.class)).isEqualTo(1);
        assertThat(latestKo(r5)).isNull();
        assertThat(revisionRepo.findBySegmentIdOrderByVersionAsc(r5.getId())).isEmpty();
    }
    /** 빈 칸 → X → Y를 두 번 되돌려 빈 칸으로 만든 뒤 두 번 다시 실행하면 Y로 돌아온다 — 빈 칸으로 돌릴 때 지운 뒤 항목 판도 다시 살아나야 한다. */
    @Test
    void redoTwiceAfterClearingRestoresLaterEdit() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        save(r5.getId(), "X");
        long a1 = latestActivity(pid);
        save(r5.getId(), "Y");
        long a2 = latestActivity(pid);

        DocumentContext u1 = undo(List.of(a2));
        assertThat(latestKo(r5)).isEqualTo("X");
        DocumentContext u2 = undo(List.of(a1));
        assertThat(latestKo(r5)).isNull();

        DocumentContext redo1 = undo(ids(u2));
        assertThat(latestKo(r5)).isEqualTo("X");
        // 원래 EDIT 항목이 다시 자기 행을 가진다(변경 내역 피드에서 0행이 아니다).
        assertThat(repo.countRowsOf(a1, -1L)).isEqualTo(1);
        assertThat(repo.countRowsOf(a2, -1L)).isEqualTo(1);
        DocumentContext redo2 = undo(ids(u1));
        assertThat(redo2.read("$.reverted", Integer.class)).isEqualTo(1);
        assertThat(latestKo(r5)).isEqualTo("Y");
        assertThat(text(r5)).isEqualTo("Y");

        // 다시 실행한 것도 거꾸로 되돌릴 수 있다.
        undo(ids(redo2));
        assertThat(latestKo(r5)).isEqualTo("X");
        undo(ids(redo1));
        assertThat(latestKo(r5)).isNull();
    }

    /** 빈 칸에 여러 행 번역 → GPT 다듬기 저장 → 둘 다 되돌린 뒤 둘 다 다시 실행하면 다듬은 글이 돌아온다. */
    @Test
    void redoTwiceRestoresPolishAfterBatchTranslateIntoEmptyRow() throws Exception {
        long pid = normalized(TestProjects.freshGame());
        Segment r5 = row(pid, 5);
        String body = mvc.perform(post("/api/segments/translate-batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + r5.getId() + "],\"engine\":\"GPT\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.Map<String, Number> made = JsonPath.parse(body).read("$.activityIds");
        List<Long> translated = made.values().stream().map(Number::longValue).toList();
        String gpt = latestKo(r5);
        assertThat(gpt).isNotNull();
        String saved = mvc.perform(post("/api/segments/save-batch").contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("items",
                                List.of(java.util.Map.of("segmentId", r5.getId(), "text", "다듬은 글", "base", gpt))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> rawPolish = JsonPath.parse(saved).read("$.activityIds");
        List<Long> polished = rawPolish.stream().map(Number::longValue).toList();
        assertThat(latestKo(r5)).isEqualTo("다듬은 글");

        DocumentContext u1 = undo(polished);
        assertThat(latestKo(r5)).isEqualTo(gpt);
        DocumentContext u2 = undo(translated);
        assertThat(latestKo(r5)).isNull();

        undo(ids(u2));
        assertThat(latestKo(r5)).isEqualTo(gpt);
        undo(ids(u1));
        assertThat(latestKo(r5)).isEqualTo("다듬은 글");
        assertThat(repo.countRowsOf(polished.get(0), -1L)).isEqualTo(1);
    }
}
