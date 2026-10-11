package com.gametrans.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findAllByOrderByUploadedAtDesc();
    List<Project> findByGameIdOrderByUploadedAtDesc(Long gameId);

    /** 게임별 파일 수와 마지막 업로드 시각. */
    @Query("select p.game.id, count(p), max(p.uploadedAt) from Project p group by p.game.id")
    List<Object[]> countAndLastUploadByGame();
}
