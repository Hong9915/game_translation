package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.activity.ActivityService;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewService;
import com.gametrans.rules.RuleDtos.ApplyRequest;
import com.gametrans.rules.RuleDtos.ApplyResult;
import com.gametrans.rules.RuleDtos.Item;
import com.gametrans.rules.RuleDtos.Preview;
import com.gametrans.rules.RuleDtos.PreviewRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RuleApplyTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ReviewService reviews;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired CorrectionRuleRepository rules;
    @Autowired RuleApplyService apply;
    @Autowired ActivityService activities;
    @Autowired JdbcTemplate jdbc;

    long pid;
    Long gameId;

    /** 2행에 글을 쓰면 같은 원문인 6행에도 퍼진다. 둘 다 시스템 알림(S). */
    void setup(String text) throws Exception {
        pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
        reviews.saveHuman(row(2).getId(), text, null, null);
        jdbc.update("update segment set text_kind = 'S' where id in (?, ?)", row(2).getId(), row(6).getId());
    }

    Segment row(int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    CorrectionRule styleRule() {
        CorrectionRule r = new CorrectionRule();
        r.setGameId(gameId); r.setType("STYLE"); r.setOfText("서술식"); r.setNfText("개조식"); r.setTextKind('S');
        return rules.save(r);
    }

    PreviewRow pr(Preview p, long sid) { return p.rows().stream().filter(x -> x.segmentId() == sid).findFirst().orElseThrow(); }
    Item item(PreviewRow x) { return new Item(x.segmentId(), x.before(), x.after()); }

    @Test
    void periodRuleStripsLineEndPeriodsInAnyKind() throws Exception {
        setup("공격력 증가.<br>방어력. 감소.");
        jdbc.update("update segment set text_kind = null where id in (?, ?)", row(2).getId(), row(6).getId());
        CorrectionRule r = new CorrectionRule();
        r.setGameId(gameId); r.setType("PERIOD"); r.setOfText("줄 끝 마침표"); r.setNfText("없음");
        r = rules.save(r);
        Preview p = apply.preview(r.getId(), null);
        PreviewRow x = pr(p, row(2).getId());
        assertThat(x.after()).isEqualTo("공격력 증가<br>방어력. 감소");
        assertThat(x.complete()).isTrue();
        assertThat(x.checked()).isTrue();
        ApplyResult res = apply.apply(r.getId(), new ApplyRequest(List.of(item(x)), List.of()), null);
        assertThat(res.applied()).isEqualTo(1);
        assertThat(row(2).getLatestKo()).isEqualTo("공격력 증가<br>방어력. 감소");
    }

    @Test
    void previewConvertsEveryClause() throws Exception {
        setup("공격력이 증가합니다. 방어력이 감소합니다.");
        Preview p = apply.preview(styleRule().getId(), null);
        assertThat(p.rows()).hasSize(2);
        assertThat(pr(p, row(2).getId()).after()).isEqualTo("공격력이 증가. 방어력이 감소.");
    }

    @Test
    void applyWritesRevisionsAndActivity() throws Exception {
        setup("공격력이 증가합니다.");
        CorrectionRule r = styleRule();
        Preview p = apply.preview(r.getId(), null);
        ApplyResult res = apply.apply(r.getId(), new ApplyRequest(p.rows().stream().map(this::item).toList(), List.of()), null);
        assertThat(res.applied()).isEqualTo(2);
        assertThat(row(2).getLatestKo()).isEqualTo("공격력이 증가.");
        assertThat(rules.findById(r.getId()).orElseThrow().getStatus()).isEqualTo("APPROVED");
        Long act = jdbc.queryForObject("select max(id) from activity where kind = 'RULE_APPLY' and project_id = ?", Long.class, pid);
        activities.revert(act, null);
        assertThat(row(2).getLatestKo()).isEqualTo("공격력이 증가합니다.");
    }

    @Test
    void uncheckedRowsBecomeExclusions() throws Exception {
        setup("공격력이 증가합니다.");
        CorrectionRule r = styleRule();
        PreviewRow a = pr(apply.preview(r.getId(), null), row(2).getId());
        apply.apply(r.getId(), new ApplyRequest(List.of(item(a)), List.of(row(6).getId())), null);
        Preview next = apply.preview(r.getId(), null);
        assertThat(next.rows()).isEmpty();
        assertThat(next.excluded()).extracting(PreviewRow::segmentId).containsExactly(row(6).getId());
    }

    @Test
    void changedSincePreviewSkipped() throws Exception {
        setup("공격력이 증가합니다.");
        CorrectionRule r = styleRule();
        PreviewRow a = pr(apply.preview(r.getId(), null), row(2).getId());
        reviews.saveHuman(row(2).getId(), "공격력이 크게 증가합니다.", null, null);
        ApplyResult res = apply.apply(r.getId(), new ApplyRequest(List.of(item(a)), List.of()), null);
        assertThat(res.applied()).isZero();
        assertThat(res.skippedChanged()).isEqualTo(1);
        assertThat(row(2).getLatestKo()).isEqualTo("공격력이 크게 증가합니다.");
    }

    @Test
    void incompleteRowsStartUnchecked() throws Exception {
        setup("버튼을 누르십시오");
        PreviewRow x = pr(apply.preview(styleRule().getId(), null), row(2).getId());
        assertThat(x.complete()).isFalse();
        assertThat(x.checked()).isFalse();
    }
}
