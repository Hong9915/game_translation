package com.gametrans.glossary;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlossaryCandidateRepository extends JpaRepository<GlossaryCandidate, Long> {
    List<GlossaryCandidate> findByGameIdAndStatusOrderByCreatedAtDescIdDesc(Long gameId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<GlossaryCandidate> findByGameIdAndSrcAndKoAndStatus(Long gameId, String src, String ko, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<GlossaryCandidate> findByGameIdAndSrcAndStatus(Long gameId, String src, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GlossaryCandidate c where c.id = :id")
    Optional<GlossaryCandidate> findForUpdate(@Param("id") Long id);
}
