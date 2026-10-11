package com.gametrans.workspace;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 원문이 같은데 번역이 다른 행만 모으는 필터와, 원문별 행 수 칩. */
class DuplicateFilterTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;

    private long normalized(String game) throws Exception {
        long pid = TestProjects.uploadExistingKo(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    /** withExistingKoSameSourceTwice() — 같은 파일 안에서도 2행·6행 원문(正义裁决)이 이미 겹친다. */
    private long normalizedSameSourceTwice(String game) throws Exception {
        long pid = TestProjects.uploadExistingKoSameSourceTwice(mvc, game);
        mvc.perform(put("/api/projects/{id}/normalize", pid).contentType(MediaType.APPLICATION_JSON)
                .content(TestProjects.EXISTING_KO_NORMALIZE));
        return pid;
    }

    private Segment row(long pid, int rowNo) {
        return segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getRowNo() == rowNo).findFirst().orElseThrow();
    }

    @Test
    void identicalFixesAreNotAConflict() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        normalized(game);
        // 두 파일 모두 기존 한국어가 같은 상태 — 불일치 없음
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void differentFixesForTheSameSourceShowUp() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        // 한쪽만 확정로 전파를 막은 뒤 다른 쪽을 고쳐 일부러 어긋나게 만든다
        mvc.perform(put("/api/segments/{id}/marks", row(b, 2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 판결\"}"));

        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].src").value("正义裁决"));
    }

    @Test
    void aConflictInAnotherGameDoesNotLeakIn() throws Exception {
        // 다른 게임에서 같은 원문(正义裁决)의 번역이 갈려 있어도
        String other = TestProjects.freshGame();
        long x = normalized(other);
        long y = normalized(other);
        mvc.perform(put("/api/segments/{id}/marks", row(y, 2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        mvc.perform(put("/api/segments/{id}/translation", row(x, 2).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 판결\"}"));
        mvc.perform(get("/api/review/{id}/rows", x).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(jsonPath("$.total").value(1));

        // 이 게임에는 불일치가 없으므로 이 게임의 DUP_CONFLICT 목록은 비어 있다 — 게임 경계를 넘지 않는다
        long a = normalized(TestProjects.freshGame());
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void conflictPagesFollowRowOrder() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalizedSameSourceTwice(game); // 2행·6행 원문이 같은 파일
        long b = normalized(game);
        mvc.perform(put("/api/segments/{id}/marks", row(b, 2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 판결\"}"));

        // a의 2행·6행 둘 다 불일치 묶음에 든다. 한 페이지에 하나씩 나누면 행 순서대로, total은 페이지와 무관하게 2.
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT").param("size", "1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows[0].rowNo").value(2));
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows[0].rowNo").value(6));
    }

    @Test
    void rowsCarryTheDuplicateCount() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        normalized(game);

        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].dupCount").value(2));
    }

    @Test
    void dupConflictComposesWithSearch() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalized(game);
        long b = normalized(game);
        mvc.perform(put("/api/segments/{id}/marks", row(b, 2).getId()).contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true}"));
        mvc.perform(put("/api/segments/{id}/translation", row(a, 2).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"정의의 판결\"}"));

        // DUP_CONFLICT와 검색어가 같이 걸려도(시트 조건·불일치 해시 조인·검색이 한 쿼리에) 맞는 한 행만 남는다
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT").param("q", "正义"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].src").value("正义裁决"));
        // 검색어가 그 행과 안 맞으면 DUP_CONFLICT 조건을 만족해도 걸러진다 — search가 실제로 함께 걸린다는 증거
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill").param("filter", "DUP_CONFLICT").param("q", "없는말"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void duplicateCountVariesAcrossThePageAndIsZeroForAUniqueSource() throws Exception {
        String game = TestProjects.freshGame();
        long a = normalizedSameSourceTwice(game); // 2행·6행 원문(正义裁决)이 같은 파일 하나
        long b = normalized(game); // 같은 게임에 표준 픽스처를 한 번 더 올린다

        // a의 한 페이지 안에 서로 다른 dupCount(3과 2)가 같이 나온다 — 상수 하나·전역 카운트 하나로는 흉내 못 낸다.
        // 正义裁决: a의 2·6행(2건) + b의 2행(1건) = 3. 伤害减免提升至@10%@·技能伤害提升至@420%@: a·b에 각 1행씩 = 2.
        mvc.perform(get("/api/review/{id}/rows", a).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.rowNo==2)].dupCount").value(3))
                .andExpect(jsonPath("$.rows[?(@.rowNo==6)].dupCount").value(3))
                .andExpect(jsonPath("$.rows[?(@.rowNo==4)].dupCount").value(2))
                .andExpect(jsonPath("$.rows[?(@.rowNo==5)].dupCount").value(2));

        // 게임 전체에서 한 번만 나오는 원문(霜语者, b의 7행)은 dupCount 0 — getOrDefault의 현재 동작(1이 아니라 0)
        mvc.perform(get("/api/review/{id}/rows", b).param("sheet", "Skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[?(@.rowNo==7)].dupCount").value(0));
    }
}
