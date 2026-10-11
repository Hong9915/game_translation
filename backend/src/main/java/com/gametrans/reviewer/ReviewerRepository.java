package com.gametrans.reviewer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ReviewerRepository extends JpaRepository<Reviewer, Long> {
    Optional<Reviewer> findByName(String name);
    List<Reviewer> findAllByOrderByIdAsc();

    /**
     * 이름 칸만 바꾼다(다른 칸을 통째로 저장하지 않도록). 벌크 update는 영속성 컨텍스트를 비켜 가므로,
     * 같은 트랜잭션에서 뒤이어 읽는 Reviewer가 옛 이름을 든 managed 엔티티이거나 그걸 도로 flush하지
     * 않도록 clear/flush를 함께 켠다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Reviewer r set r.name = :name where r.id = :id")
    void updateName(@Param("id") Long id, @Param("name") String name);
}
