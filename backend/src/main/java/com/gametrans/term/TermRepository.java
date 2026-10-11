package com.gametrans.term;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface TermRepository extends JpaRepository<Term, Long> {
    List<Term> findByProjectIdOrderByOccurrencesDescSrcTextAsc(Long projectId);
    List<Term> findByProjectIdAndConfirmedFalseOrderByOccurrencesDescSrcTextAsc(Long projectId);
    List<Term> findByProjectIdAndConfirmedTrue(Long projectId);
    Optional<Term> findByProjectIdAndSrcText(Long projectId, String srcText);
    List<Term> findByProjectIdAndSrcTextIn(Long projectId, java.util.Collection<String> srcTexts);
    long countByProjectId(Long projectId);
    long countByProjectIdAndConfirmedTrue(Long projectId);
    @Modifying @Transactional void deleteByProjectId(Long projectId);
    @Modifying @Transactional void deleteByProjectIdAndConfirmedFalse(Long projectId);
}
