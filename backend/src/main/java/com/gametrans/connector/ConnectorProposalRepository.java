package com.gametrans.connector;

import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorProposalRepository extends JpaRepository<ConnectorProposal, Long> {
    List<ConnectorProposal> findByReviewerIdAndGameIdAndStatusAndCreatedAtAfterOrderByCreatedAtDescIdDesc(Long reviewerId, Long gameId, String status, Instant after);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ConnectorProposal p where p.id = :id")
    Optional<ConnectorProposal> findForUpdate(@Param("id") Long id);
}
