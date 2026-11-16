package com.gametrans.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findAllByOrderByUploadedAtDesc();
    List<Project> findByGameIdOrderByUploadedAtDesc(Long gameId);

    /** 게임별 파일 수와 마지막 업로드 시각. */
    @Query("select p.game.id, count(p), max(p.uploadedAt) from Project p group by p.game.id")
    List<Object[]> countAndLastUploadByGame();

    /** 파일별 검수 행 수(건너뛴 행 빼고)와 그중 확정한 행 수 — [project_id, rows, confirmed]. 행이 없는 파일은 안 나온다. */
    @Query(value = """
        select s.project_id, count(*), count(*) filter (where s.confirmed)
        from segment s where not s.skipped and s.project_id in (:ids) group by s.project_id
        """, nativeQuery = true)
    List<Object[]> reviewProgressByProject(@Param("ids") java.util.Collection<Long> ids);

    /** 게임별 검수 행 수와 확정 행 수 — [game_id, rows, confirmed]. */
    @Query(value = """
        select p.game_id, count(*), count(*) filter (where s.confirmed)
        from segment s join project p on p.id = s.project_id where not s.skipped group by p.game_id
        """, nativeQuery = true)
    List<Object[]> reviewProgressByGame();

    /** 이 파일에서 고른 번역 원문 언어(ZH | EN)를 기억한다. 행 번역이 따른다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Project p set p.translateSourceLang = :lang where p.id = :id")
    void updateTranslateSourceLang(@Param("id") Long id, @Param("lang") String lang);
}
