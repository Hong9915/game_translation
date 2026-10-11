package com.gametrans.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.TranslationRevision;
import com.gametrans.translate.TranslationRevisionRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ReviewerControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segmentRepo;
    @Autowired TranslationRevisionRepository revisionRepo;
    /** 사전 확인(findByName)과 실제 UNIQUE 제약 위반 사이의 레이스를 스레드 없이 재현하기 위해서만 감싼다. */
    @SpyBean ReviewerRepository reviewerRepo;

    private String add(String name) throws Exception {
        return mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 등록 후 그 id만 돌려준다. */
    private long reviewer(String prefix) throws Exception {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        return ((Number) JsonPath.parse(add(name)).read("$.id")).longValue();
    }

    /** 프로젝트를 만들어 정규화까지 마친다 — 검토 배정·완료·수정 기록을 남길 세그먼트가 필요하다. */
    private long project() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        return pid;
    }

    private long segmentId(long pid, int index) throws Exception {
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.parse(list).read("$.rows[" + index + "].id")).longValue();
    }

    @Test
    void addingANameRegistersItImmediatelyAndIsIdempotent() throws Exception {
        String name = "지민-" + UUID.randomUUID().toString().substring(0, 6);
        String first = add("  " + name + " ");
        String second = add(name);
        Number id1 = JsonPath.parse(first).read("$.id"), id2 = JsonPath.parse(second).read("$.id");
        assertThat(id1.longValue()).isEqualTo(id2.longValue());
        assertThat(JsonPath.parse(first).<String>read("$.name")).isEqualTo(name);
        assertThat(JsonPath.parse(first).<String>read("$.color")).startsWith("#");

        mvc.perform(get("/api/reviewers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='" + name + "')]").isNotEmpty());
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mvc.perform(post("/api/reviewers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renamingUpdatesTheNameAndShowsUpInTheList() throws Exception {
        long id = reviewer("지민");
        String newName = "지민(수정)-" + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(patch("/api/reviewers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  " + newName + " \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(newName));

        mvc.perform(get("/api/reviewers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")].name").value(newName));
    }

    @Test
    void renamingToAnotherReviewersNameIsRejected() throws Exception {
        long a = reviewer("지민");
        String bName = "서연-" + UUID.randomUUID().toString().substring(0, 6);
        add(bName);

        mvc.perform(patch("/api/reviewers/{id}", a).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + bName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 있는 이름입니다: " + bName));
    }

    @Test
    void renamingToABlankNameIsRejected() throws Exception {
        long id = reviewer("지민");
        mvc.perform(patch("/api/reviewers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renamingAnUnknownReviewerIs404() throws Exception {
        mvc.perform(patch("/api/reviewers/{id}", 999999999L).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"아무개\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamingToMyOwnCurrentNameSucceedsEvenWithSurroundingSpaces() throws Exception {
        String name = "지민-" + UUID.randomUUID().toString().substring(0, 6);
        long id = ((Number) JsonPath.parse(add(name)).read("$.id")).longValue();

        mvc.perform(patch("/api/reviewers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
        mvc.perform(patch("/api/reviewers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  " + name + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));

        assertThat(reviewerRepo.findById(id).orElseThrow().getName()).isEqualTo(name);
    }

    /**
     * findByName의 사전 확인은 "비어 있다"고 답하게 만들고, 실제로는 이미 다른 검토자가 그 이름을 쓰는 채로 둔다 —
     * 사전 확인 통과 직후 다른 요청이 먼저 같은 이름을 차지한 진짜 레이스를 스레드 없이 재현한다.
     * DB의 UNIQUE 제약이 그 순간 막아야 하고, 그 예외가 500이 아니라 400으로 나와야 한다(수정 전에는 500).
     */
    @Test
    void renamingRaceWithAConcurrentClaimOfTheSameNameIsRejectedNotAServerError() throws Exception {
        String aName = "지민-" + UUID.randomUUID().toString().substring(0, 6);
        long a = ((Number) JsonPath.parse(add(aName)).read("$.id")).longValue();
        String bName = "서연-" + UUID.randomUUID().toString().substring(0, 6);
        add(bName);   // b는 실제로 이 이름을 이미 쓰고 있다

        Mockito.when(reviewerRepo.findByName(bName)).thenReturn(Optional.empty());

        mvc.perform(patch("/api/reviewers/{id}", a).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + bName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 있는 이름입니다: " + bName));

        assertThat(reviewerRepo.findById(a).orElseThrow().getName()).isEqualTo(aName);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingWithTransferMovesAssigneeDoneByAndRevisionReviewerThenRemovesFromList() throws Exception {
        long pid = project();
        long jimin = reviewer("지민"), seoyeon = reviewer("서연");
        long sid = segmentId(pid, 2);

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":" + jimin + "}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/done", sid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/reviewers/{id}", jimin).param("transferTo", String.valueOf(seoyeon)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/reviewers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + jimin + ")]").isEmpty());

        Segment after = segmentRepo.findById(sid).orElseThrow();
        assertThat(after.getAssigneeId()).isEqualTo(seoyeon);
        assertThat(after.getDoneBy()).isEqualTo(seoyeon);
        TranslationRevision last = revisionRepo.findTopBySegmentIdOrderByVersionDesc(sid).orElseThrow();
        assertThat(last.getReviewerId()).isEqualTo(seoyeon);

        java.util.Map<String, Object> row = rowFor(pid, sid);
        assertThat(((Number) ((java.util.Map<String, Object>) row.get("assignee")).get("id")).longValue()).isEqualTo(seoyeon);
    }

    @Test
    void deletingWithoutTransferClearsAssigneeDoneByAndRevisionReviewer() throws Exception {
        long pid = project();
        long jimin = reviewer("지민");
        long sid = segmentId(pid, 1);

        mvc.perform(post("/api/projects/{id}/assign", pid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIds\":[" + sid + "],\"assigneeId\":" + jimin + "}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/done", sid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/segments/{id}/translation", sid).header("X-Reviewer-Id", jimin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"서리\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/reviewers/{id}", jimin)).andExpect(status().isNoContent());

        Segment after = segmentRepo.findById(sid).orElseThrow();
        assertThat(after.getAssigneeId()).isNull();
        assertThat(after.getDoneBy()).isNull();
        TranslationRevision last = revisionRepo.findTopBySegmentIdOrderByVersionDesc(sid).orElseThrow();
        assertThat(last.getReviewerId()).isNull();

        java.util.Map<String, Object> row = rowFor(pid, sid);
        assertThat(row.get("assignee")).isNull();
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> history = (java.util.List<java.util.Map<String, Object>>) row.get("history");
        assertThat(history.get(history.size() - 1).get("reviewerName")).isNull();
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> rowFor(long pid, long sid) throws Exception {
        String list = mvc.perform(get("/api/projects/{id}/segments", pid)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.List<java.util.Map<String, Object>> rows = JsonPath.parse(list).read("$.rows");
        return rows.stream().filter(r -> ((Number) r.get("id")).longValue() == sid).findFirst().orElseThrow();
    }

    @Test
    void transferringToSelfIsRejected() throws Exception {
        long jimin = reviewer("지민");
        mvc.perform(delete("/api/reviewers/{id}", jimin).param("transferTo", String.valueOf(jimin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAnUnknownReviewerIs404() throws Exception {
        mvc.perform(delete("/api/reviewers/{id}", 999999999L)).andExpect(status().isNotFound());
    }

    @Test
    void deletingWithAnUnknownTransferTargetIs404() throws Exception {
        long jimin = reviewer("지민");
        mvc.perform(delete("/api/reviewers/{id}", jimin).param("transferTo", "999999999"))
                .andExpect(status().isNotFound());
    }

    /** id 자체가 모르는 검토자면, transferTo가 그 id와 같더라도(=자기 자신 이관처럼 보여도) 404가 먼저다. */
    @Test
    void deletingAnUnknownReviewerWithTransferToItselfIs404NotBadRequest() throws Exception {
        mvc.perform(delete("/api/reviewers/{id}", 999999999L).param("transferTo", "999999999"))
                .andExpect(status().isNotFound());
    }
}
