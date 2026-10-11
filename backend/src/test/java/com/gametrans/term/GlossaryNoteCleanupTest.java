package com.gametrans.term;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.translate.RevisionService;
import com.gametrans.translate.TranslationRevision;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 확정 번역 뒤의 메모(의역: …)가 번역어로 들어가 있던 데이터를 고친다. */
class GlossaryNoteCleanupTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TermRepository termRepo;
    @Autowired SegmentRepository segments;
    @Autowired RevisionService revisions;
    @Autowired GlossaryNoteCleanup cleanup;
    @Autowired com.gametrans.glossary.GlossaryEntryRepository glossaryRepo;
    @Autowired com.gametrans.project.ProjectRepository projects;

    @Test
    void stripsTheNoteFromTermRowsAndRechecksStaleTermMisses() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        Term t = new Term();
        t.setProjectId(pid); t.setSrcText("火焰"); t.setType(Term.Type.OTHER);
        t.setConfirmedKo("화염 (의역: 불꽃 속성)"); t.setConfirmed(true);
        termRepo.save(t);

        List<Segment> rows = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid);
        Segment sentence = rows.stream().filter(s -> s.getSrcText().contains("火焰")).findFirst().orElseThrow();
        List<Segment> others = rows.stream().filter(s -> !s.getId().equals(sentence.getId())).toList();
        Segment termRow = others.get(0), editedTermRow = others.get(1);
        for (Segment s : List.of(termRow, editedTermRow)) {
            s.setTerm(true); s.setTermId(t.getId()); segments.save(s);
            revisions.add(s.getId(), "화염 (의역: 불꽃 속성)", TranslationRevision.Author.GLOSSARY);
        }
        revisions.add(editedTermRow.getId(), "화염 (불꽃)", TranslationRevision.Author.HUMAN);
        revisions.add(sentence.getId(), "화염의 힘이 깃든 장검", TranslationRevision.Author.LLM);
        segments.updateTermMiss(sentence.getId(), "火焰→화염 (의역: 불꽃 속성)");

        cleanup.run();
        cleanup.run();   // 두 번 돌아도 판이 더 쌓이지 않는다

        assertThat(revisions.bySegmentIds(List.of(termRow.getId())).get(termRow.getId()))
                .extracting(TranslationRevision::getText).containsExactly("화염 (의역: 불꽃 속성)", "화염");
        assertThat(revisions.current(editedTermRow.getId()).orElseThrow().getText()).isEqualTo("화염 (불꽃)");
        assertThat(segments.findById(sentence.getId()).orElseThrow().getTermMiss()).isNull();
    }

    /** 메모째 찾느라 붙은 옛 표시를 다시 검사할 때는 게임의 유효 단어장을 기준으로 본다. */
    @Test
    void staleTermMissesAreRecheckedAgainstTheGlossary() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        Term t = new Term();
        t.setProjectId(pid); t.setSrcText("火焰"); t.setType(Term.Type.OTHER);
        t.setConfirmedKo("화염 (의역: 불꽃 속성)"); t.setConfirmed(true);
        termRepo.save(t);
        com.gametrans.glossary.GlossaryEntry g = new com.gametrans.glossary.GlossaryEntry();
        g.setGameId(projects.findById(pid).orElseThrow().getGame().getId());
        g.setKind(com.gametrans.glossary.GlossaryEntry.Kind.SRC); g.setMatchMode(com.gametrans.glossary.GlossaryEntry.MatchMode.PARTIAL);
        g.setFind("火焰"); g.setKo("불길"); g.setUpdatedAt(java.time.Instant.now());
        glossaryRepo.save(g);

        Segment sentence = segments.findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(pid).stream()
                .filter(s -> s.getSrcText().contains("火焰")).findFirst().orElseThrow();
        revisions.add(sentence.getId(), "화염의 힘이 깃든 장검", TranslationRevision.Author.LLM);
        segments.updateTermMiss(sentence.getId(), "火焰→화염 (의역: 불꽃 속성)");

        cleanup.run();

        assertThat(segments.findById(sentence.getId()).orElseThrow().getTermMiss()).isEqualTo("火焰→불길");
    }
}
