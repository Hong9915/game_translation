package com.gametrans.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.GlossaryEntry.MatchMode;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GlossaryApplierTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired ProjectRepository projects;
    @Autowired RevisionService revisions;
    @Autowired GlossaryApplier applier;

    private long pid;
    private Long gameId;

    private void setUpProject() throws Exception {
        pid = TestProjects.uploadExistingKo(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
    }

    private Segment row(int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    private String fix(int rowNo) { return revisions.current(row(rowNo).getId()).map(r -> r.getText()).orElse(null); }

    private Segment rowIn(long projectId, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(projectId).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    private String fixIn(long projectId, int rowNo) { return revisions.current(rowIn(projectId, rowNo).getId()).map(r -> r.getText()).orElse(null); }

    private GlossaryEntry e(Kind kind, MatchMode mode, String find, String ko) {
        GlossaryEntry x = new GlossaryEntry();
        x.setGameId(gameId); x.setKind(kind); x.setMatchMode(mode); x.setFind(find); x.setKo(ko);
        return x;
    }

    private void setUpUpgradeProject() throws Exception {
        pid = TestProjects.uploadUpgrade(mvc, TestProjects.freshGame());
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        gameId = projects.findById(pid).orElseThrow().getGame().getId();
    }

    private GlossaryEntry srcPartial(String find, String oldKo, String ko) {
        GlossaryEntry x = e(Kind.SRC, MatchMode.PARTIAL, find, ko);
        x.setOldKo(oldKo);
        return x;
    }

    @Test
    void srcWholeReplacesTheEntireFix() throws Exception {
        setUpProject();
        assertThat(applier.apply(gameId, e(Kind.SRC, MatchMode.WHOLE, "正义裁决", "정의의 판결"), null)).isEqualTo(1);
        assertThat(fix(2)).isEqualTo("정의의 판결");
    }

    @Test
    void srcPartialWithoutOldKoDoesNotTouchTheFix() throws Exception {
        setUpProject();
        // 기존 표기(old_ko)가 없으면 중국어 조각이 한국어 문장 어디에 들어갈지 몰라 자동 치환하지 않는다
        assertThat(applier.apply(gameId, e(Kind.SRC, MatchMode.PARTIAL, "裁决", "판결"), null)).isZero();
        assertThat(fix(2)).isEqualTo("정의의 심판");
    }

    @Test
    void koWholeReplacesAnExactlyMatchingFix() throws Exception {
        setUpProject();
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.WHOLE, "정의의 심판", "정의의 판결"), null)).isEqualTo(1);
        assertThat(fix(2)).isEqualTo("정의의 판결");
    }

    @Test
    void koPartialReplacesJustThatPiece() throws Exception {
        setUpProject();
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.PARTIAL, "심판", "판결"), null)).isEqualTo(1);
        assertThat(fix(2)).isEqualTo("정의의 판결");
    }

    @Test
    void confirmedRowsAreSkipped() throws Exception {
        setUpProject();
        mvc.perform(put("/api/segments/{id}/marks", row(2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.PARTIAL, "심판", "판결"), null)).isZero();
        assertThat(fix(2)).isEqualTo("정의의 심판");
    }

    @Test
    void entriesWithoutKoAreSkipped() throws Exception {
        setUpProject();
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.PARTIAL, "심판", null), null)).isZero();
    }

    @Test
    void countMatchesWhatApplyActuallyChanges() throws Exception {
        setUpProject();
        int expected = applier.count(gameId, Kind.KO, MatchMode.PARTIAL, "심판", "판결", null);
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.PARTIAL, "심판", "판결"), null)).isEqualTo(expected);
    }

    @Test
    void reapplyingAKoThatContainsFindIsIdempotent() throws Exception {
        // Ruling 22: find가 ko 안에 그대로 들어 있으면(심판 ⊂ 심판관) 같은 항목을 두 번 적용해도
        // 겹쳐 늘어나면 안 된다(심판관관이 되지 않아야 한다).
        setUpProject();
        GlossaryEntry entry = e(Kind.KO, MatchMode.PARTIAL, "심판", "심판관");
        assertThat(applier.apply(gameId, entry, null)).isEqualTo(1);
        assertThat(fix(2)).isEqualTo("정의의 심판관");

        assertThat(applier.apply(gameId, entry, null)).isZero();
        assertThat(fix(2)).isEqualTo("정의의 심판관");
    }

    @Test
    void countIsZeroAfterAKoContainingFindWasAlreadyApplied() throws Exception {
        setUpProject();
        GlossaryEntry entry = e(Kind.KO, MatchMode.PARTIAL, "심판", "심판관");
        applier.apply(gameId, entry, null);
        assertThat(applier.count(gameId, Kind.KO, MatchMode.PARTIAL, "심판", "심판관", null)).isZero();
    }

    @Test
    void countAgreesWithApplyWhenKoHasTrailingWhitespace() throws Exception {
        // Important finding: count()이 next를 다듬지 않고 cur와 비교하면 BulkFixWriter(trim 후 비교)와
        // 어긋난다. find "심판" / ko "심판 "(끝 공백)를 Fix "정의의 심판"에 적용하면 next는
        // "정의의 심판 "(끝 공백 그대로)이지만 다듬으면 cur와 같아 실제로는 바뀌지 않는다 —
        // 다듬지 않고 세면 count=1인데 apply=0이 되어 어긋난다. 다듬어 세면 둘 다 0이어야 한다.
        setUpProject();
        int expected = applier.count(gameId, Kind.KO, MatchMode.PARTIAL, "심판", "심판 ", null);
        int changed = applier.apply(gameId, e(Kind.KO, MatchMode.PARTIAL, "심판", "심판 "), null);
        assertThat(expected).isZero();
        assertThat(changed).isZero();
    }

    @Test
    void koWholeMatchesAFixWithATrailingNewline() throws Exception {
        // Ruling 23: postgres btrim은 공백만 지우고 자바 trim()은 탭·개행도 지운다. LLM 리비전 경로가
        // 남긴 "정의의 심판\n" 같은 값을 KO+WHOLE 후보 쿼리가 놓치면 안 된다. RevisionService.add는
        // 텍스트를 다듬지 않으므로(TranslationRevision.of가 원문 그대로 저장) 개행이 그대로 latest_ko에 남는다.
        setUpProject();
        revisions.add(row(2).getId(), "정의의 심판\n", TranslationRevision.Author.HUMAN, null);
        assertThat(applier.apply(gameId, e(Kind.KO, MatchMode.WHOLE, "정의의 심판", "정의의 판결"), null)).isEqualTo(1);
        assertThat(fix(2)).isEqualTo("정의의 판결");
    }

    @Test
    void appliesAcrossAllProjectsInTheSameGame() throws Exception {
        // Ruling 24: 항목은 한 프로젝트가 아니라 게임 전체에 적용된다.
        String game = TestProjects.freshGame();
        long pid1 = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid1).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        long pid2 = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid2).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));

        Long gid = projects.findById(pid1).orElseThrow().getGame().getId();
        assertThat(projects.findById(pid2).orElseThrow().getGame().getId()).isEqualTo(gid);

        GlossaryEntry entry = new GlossaryEntry();
        entry.setGameId(gid); entry.setKind(Kind.KO); entry.setMatchMode(MatchMode.PARTIAL);
        entry.setFind("심판"); entry.setKo("판결");

        int expected = applier.count(gid, Kind.KO, MatchMode.PARTIAL, "심판", "판결", null);
        int changed = applier.apply(gid, entry, null);
        assertThat(changed).isEqualTo(expected);
        assertThat(changed).isEqualTo(2);

        assertThat(fixIn(pid1, 2)).isEqualTo("정의의 판결");
        assertThat(fixIn(pid2, 2)).isEqualTo("정의의 판결");
    }

    @Test
    void srcPartialWithOldKoReplacesOnlyRowsWhoseSourceHasTheTerm() throws Exception {
        setUpUpgradeProject();
        assertThat(applier.apply(gameId, srcPartial("升星", "승급", "성급"), null)).isEqualTo(3);
        assertThat(fix(2)).isEqualTo("성급 계획");
        assertThat(fix(3)).isEqualTo("영웅 성급");
        assertThat(fix(4)).isEqualTo("성급 재료");
        assertThat(fix(5)).isEqualTo("승급 보상");   // 晋升 → 승급: 원문에 升星이 없으니 그대로
        assertThat(fix(6)).isEqualTo("일일 계획");
    }

    @Test
    void srcPartialWithOldKoIsIdempotent() throws Exception {
        setUpUpgradeProject();
        GlossaryEntry entry = srcPartial("升星", "승급", "성급");
        applier.apply(gameId, entry, null);
        assertThat(applier.apply(gameId, entry, null)).isZero();
        assertThat(fix(2)).isEqualTo("성급 계획");
    }

    @Test
    void srcPartialReapplyIsIdempotentWhenKoContainsOldKo() throws Exception {
        // Review Focus 3: ko가 old_ko를 품어도("승급" → "승급 완료") 두 번 적용해 겹쳐 늘지 않는다
        setUpUpgradeProject();
        GlossaryEntry entry = srcPartial("升星", "승급", "승급 완료");
        assertThat(applier.apply(gameId, entry, null)).isEqualTo(3);
        assertThat(fix(2)).isEqualTo("승급 완료 계획");
        assertThat(applier.apply(gameId, entry, null)).isZero();
        assertThat(fix(2)).isEqualTo("승급 완료 계획");
    }

    @Test
    void srcPartialCountMatchesApply() throws Exception {
        setUpUpgradeProject();
        int expected = applier.count(gameId, Kind.SRC, MatchMode.PARTIAL, "升星", "성급", "승급");
        assertThat(expected).isEqualTo(3);
        assertThat(applier.apply(gameId, srcPartial("升星", "승급", "성급"), null)).isEqualTo(expected);
        assertThat(applier.count(gameId, Kind.SRC, MatchMode.PARTIAL, "升星", "성급", "승급")).isZero();
    }

    @Test
    void srcPartialCountIsZeroWithoutOldKo() throws Exception {
        setUpUpgradeProject();
        assertThat(applier.count(gameId, Kind.SRC, MatchMode.PARTIAL, "升星", "성급", null)).isZero();
        assertThat(applier.count(gameId, Kind.SRC, MatchMode.PARTIAL, "升星", "성급", "  ")).isZero();
    }

    @Test
    void srcPartialSkipsConfirmedRows() throws Exception {
        setUpUpgradeProject();
        mvc.perform(put("/api/segments/{id}/marks", row(2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        assertThat(applier.apply(gameId, srcPartial("升星", "승급", "성급"), null)).isEqualTo(2);
        assertThat(fix(2)).isEqualTo("승급 계획");
    }
}
