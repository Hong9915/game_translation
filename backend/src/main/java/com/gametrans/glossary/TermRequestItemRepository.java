package com.gametrans.glossary;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TermRequestItemRepository extends JpaRepository<TermRequestItem, Long> {
    List<TermRequestItem> findByRequestIdOrderBySeq(Long requestId);

    List<TermRequestItem> findByRequestIdIn(Collection<Long> requestIds);
}
