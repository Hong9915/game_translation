package com.gametrans.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 여러 행의 Fix를 한 번에 바꾸는 공통부. 동일 원문 통일과 단어장 적용이 둘 다 이걸 쓴다. */
class BulkFixWriterTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired BulkFixWriter writer;

    private List<Segment> normalizedRows() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid);
    }

    @Test
    void writesARevisionPerChangedRowAndReturnsTheCount() throws Exception {
        List<Segment> rows = normalizedRows();
        Segment first = rows.get(0); // 기존 한국어 "정의의 심판"

        int changed = writer.write(List.of(first), s -> "정의의 판결", null);

        assertThat(changed).isEqualTo(1);
        TranslationRevision cur = revisions.current(first.getId()).orElseThrow();
        assertThat(cur.getText()).isEqualTo("정의의 판결");
        assertThat(cur.getAuthor()).isEqualTo(TranslationRevision.Author.HUMAN);
    }

    @Test
    void skipsRowsWhoseTextIsAlreadyTheSame() throws Exception {
        List<Segment> rows = normalizedRows();
        Segment first = rows.get(0);
        int before = revisions.current(first.getId()).orElseThrow().getVersion();

        assertThat(writer.write(List.of(first), s -> "정의의 심판", null)).isZero();
        assertThat(revisions.current(first.getId()).orElseThrow().getVersion()).isEqualTo(before);
    }

    @Test
    void skipsBlankAndNullNewText() throws Exception {
        List<Segment> rows = normalizedRows();
        assertThat(writer.write(rows, s -> null, null)).isZero();
        assertThat(writer.write(rows, s -> "   ", null)).isZero();
    }

    @Test
    void recordsTheReviewerOnEachRevision() throws Exception {
        List<Segment> rows = normalizedRows();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"검수자\"}"))
                .andReturn().getResponse().getContentAsString();
        Long rid = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        writer.write(List.of(rows.get(0)), s -> "새 문장", rid);

        assertThat(revisions.current(rows.get(0).getId()).orElseThrow().getReviewerId()).isEqualTo(rid);
    }

    @Test
    void changesEveryRowInAMultiRowBatchAndCountsThemAll() throws Exception {
        List<Segment> rows = normalizedRows();
        assertThat(rows.size()).isGreaterThanOrEqualTo(3); // 픽스처가 이 정도는 준다고 가정

        int changed = writer.write(rows, s -> "일괄 번역 " + s.getId(), null);

        assertThat(changed).isEqualTo(rows.size());
        for (Segment s : rows)
            assertThat(revisions.current(s.getId()).orElseThrow().getText()).isEqualTo("일괄 번역 " + s.getId());
    }

    @Test
    void countsOnlyTheRowsThatActuallyChangeInAMixedBatch() throws Exception {
        List<Segment> rows = normalizedRows();
        Segment unchanged = rows.get(0); // 지금 값과 같은 값을 다시 준다
        Segment changedA = rows.get(1);
        Segment changedB = rows.get(2);

        int changed = writer.write(List.of(unchanged, changedA, changedB),
                s -> s.getId().equals(unchanged.getId()) ? "정의의 심판" : "고친 번역 " + s.getId(), null);

        assertThat(changed).isEqualTo(2);
        assertThat(revisions.current(changedA.getId()).orElseThrow().getText()).isEqualTo("고친 번역 " + changedA.getId());
        assertThat(revisions.current(changedB.getId()).orElseThrow().getText()).isEqualTo("고친 번역 " + changedB.getId());
    }

    @Test
    void recomputesTermMissOnAChangedRow() throws Exception {
        List<Segment> rows = normalizedRows();
        Segment first = rows.get(0);
        // 이 픽스처엔 확정 용어가 없다. 갱신이 실제로 일어나는지 보려고 먼저 낡은 값을 심어 둔다.
        segments.updateTermMiss(first.getId(), "낡은 표시");

        writer.write(List.of(first), s -> "정의의 판결", null);

        // 확정 용어가 없는 프로젝트라 GlossaryCheck.missing은 빈 목록이고, format은 그걸 null로 만든다.
        // 값이 그대로 "낡은 표시"가 아니라 null이라는 것이 재계산이 실제로 실행됐다는 증거다.
        assertThat(segments.findById(first.getId()).orElseThrow().getTermMiss()).isNull();
    }

    @Test
    void returnsZeroForAnEmptyList() {
        assertThat(writer.write(List.of(), s -> "x", null)).isZero();
    }
}
