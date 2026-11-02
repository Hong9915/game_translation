package com.gametrans.glossary;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermRequestRepository extends JpaRepository<TermRequest, Long> {
    List<TermRequest> findByGameIdOrderByCreatedAtDescIdDesc(Long gameId);
}
