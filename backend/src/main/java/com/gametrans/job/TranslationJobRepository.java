package com.gametrans.job;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranslationJobRepository extends JpaRepository<TranslationJob, Long> {
    Optional<TranslationJob> findFirstByProjectIdAndStatus(Long projectId, TranslationJob.Status status);
    Optional<TranslationJob> findFirstByProjectIdAndKindOrderByIdDesc(Long projectId, TranslationJob.Kind kind);
    List<TranslationJob> findByStatus(TranslationJob.Status status);
    List<TranslationJob> findTop5ByProjectIdOrderByIdDesc(Long projectId);
    Optional<TranslationJob> findFirstByGameIdAndStatus(Long gameId, TranslationJob.Status status);
}
