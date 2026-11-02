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

    /** 이 파일에서 고른 번역 원문 언어(ZH | EN)를 기억한다. 행 번역이 따른다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Project p set p.translateSourceLang = :lang where p.id = :id")
    void updateTranslateSourceLang(@Param("id") Long id, @Param("lang") String lang);
}
