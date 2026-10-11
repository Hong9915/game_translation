package com.gametrans.export;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportVersionRepository extends JpaRepository<ExportVersion, Long> {
    List<ExportVersion> findByProjectIdOrderByVersionDesc(Long projectId);
    Optional<ExportVersion> findTopByProjectIdOrderByVersionDesc(Long projectId);
    Optional<ExportVersion> findByIdAndProjectId(Long id, Long projectId);
}
