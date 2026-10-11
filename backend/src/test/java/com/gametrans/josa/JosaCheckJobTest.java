package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.FakeJosaClient;
import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 주기 검사(설계 2026-10-07 §3). 공유 DB라 다른 테스트 행도 같이 돈다 — 단언은 이 테스트가 만든 행만 본다. */
class JosaCheckJobTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired FakeJosaClient fake;
    @Autowired JosaCheckJob job;

    static final String TEXT = "아군의 생명를 회복 " + java.util.UUID.randomUUID();

    @BeforeEach
    void clean() {
        fake.reset();
        drain();   // 다른 테스트가 남긴 대기 행을 먼저 비워, 이 테스트 행이 첫 묶음(500행)에 들어가게 한다
    }

    Long row() throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON).content(TestProjects.EXISTING_KO_NORMALIZE))
                .andExpect(status().isOk());
        Long id = segments.findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(pid, "Skill").get(0).getId();
        revisions.add(id, TEXT, TranslationRevision.Author.HUMAN);
        return id;
    }

    void drain() { while (job.runOnce() > 0) { /* 대기 행이 다 빠질 때까지 */ } }

    @Test
    void storesHints() throws Exception {
        Long id = row();
        fake.answers.put(TEXT, List.of(new JosaHint(4, "생명를", "생명을", "HANGUL", null)));
        drain();
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getJosaChecked()).isTrue();
        assertThat(JosaHints.parse(s.getJosaHint())).containsExactly(new JosaHint(4, "생명를", "생명을", "HANGUL", null));
    }

    @Test
    void noHintsMeansCheckedWithNull() throws Exception {
        Long id = row();
        drain();
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getJosaChecked()).isTrue();
        assertThat(s.getJosaHint()).isNull();
    }

    @Test
    void dictNounDroppedWhenFixedFormIsRarer() throws Exception {
        Long id = row();
        // "생명을"은 이 게임 말뭉치에 없고 "생명를"은 한 번 있다 → 사전 명사 표시가 붙은 제안은 버린다
        fake.answers.put(TEXT, List.of(new JosaHint(4, "생명를", "생명을", "HANGUL", true)));
        drain();
        assertThat(segments.findById(id).orElseThrow().getJosaHint()).isNull();
    }

    @Test
    void rowEditedDuringCheckIsNotOverwritten() throws Exception {
        Long id = row();
        fake.answers.put(TEXT, List.of(new JosaHint(4, "생명를", "생명을", "HANGUL", null)));
        fake.duringCheck = () -> { fake.duringCheck = () -> {}; revisions.add(id, "고친 문장", TranslationRevision.Author.HUMAN); };
        job.runOnce();
        Segment s = segments.findById(id).orElseThrow();
        assertThat(s.getLatestKo()).isEqualTo("고친 문장");
        assertThat(s.getJosaChecked()).isFalse();
        drain();
        assertThat(segments.findById(id).orElseThrow().getJosaChecked()).isTrue();
    }

    @Test
    void sidecarDownChangesNothing() throws Exception {
        Long id = row();
        fake.failing = true;
        assertThat(job.safeRun()).isZero();
        assertThat(segments.findById(id).orElseThrow().getJosaChecked()).isFalse();
    }

    @Test
    void sendsGlossaryKoWordsAsProtected() throws Exception {
        row();
        drain();
        assertThat(fake.protectedSeen).isNotEmpty();
    }
}
