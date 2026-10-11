package com.gametrans.translate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TranslationRevisionRepository extends JpaRepository<TranslationRevision, Long> {
    List<TranslationRevision> findBySegmentIdOrderByVersionAsc(Long segmentId);
    Optional<TranslationRevision> findTopBySegmentIdOrderByVersionDesc(Long segmentId);
    List<TranslationRevision> findBySegmentIdInOrderBySegmentIdAscVersionAsc(Collection<Long> segmentIds);
    List<TranslationRevision> findByActivityIdOrderByIdAsc(Long activityId);
    @Modifying @Transactional void deleteBySegmentIdInAndAuthor(Collection<Long> segmentIds, TranslationRevision.Author author);

    /**
     * 검토자 삭제 시 남긴 리비전의 작성자를 다른 검토자에게 옮기거나(to) 비운다(to가 null).
     * 벌크 update는 영속성 컨텍스트를 비켜 가므로, 같은 트랜잭션에서 이어지는 읽기가 옛 reviewerId를 든
     * managed 엔티티를 보지 않도록 clear/flush를 함께 켠다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update TranslationRevision r set r.reviewerId = :to where r.reviewerId = :from")
    int moveReviewer(@Param("from") Long from, @Param("to") Long to);
}
