package com.gametrans.translate;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class RevisionServiceTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;

    @Test
    void addAssignsIncrementingVersionsAndCurrentIsLatest() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        Segment s = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).get(0);
        revisions.add(s.getId(), "초벌", TranslationRevision.Author.LLM);
        revisions.add(s.getId(), "1차", TranslationRevision.Author.HUMAN);
        assertThat(revisions.current(s.getId())).get().extracting(TranslationRevision::getText).isEqualTo("1차");
        List<TranslationRevision> all = revisions.bySegmentIds(List.of(s.getId())).get(s.getId());
        assertThat(all).extracting(TranslationRevision::getVersion).containsExactly(0, 1);
    }
}
