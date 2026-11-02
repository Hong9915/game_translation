package com.gametrans.export;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportVersionRepository extends JpaRepository<ExportVersion, Long> {
    List<ExportVersion> findByProjectIdOrderByVersionDesc(Long projectId);
    Optional<ExportVersion> findTopByProjectIdAndSheetNameIsNullAndFinalOnlyFalseOrderByVersionDesc(Long projectId);
    Optional<ExportVersion> findTopByProjectIdAndSheetNameIsNullAndFinalOnlyTrueOrderByVersionDesc(Long projectId);
    Optional<ExportVersion> findTopByProjectIdAndSheetNameAndFinalOnlyTrueOrderByVersionDesc(Long projectId, String sheetName);
    Optional<ExportVersion> findTopByProjectIdAndSheetNameAndFinalOnlyFalseOrderByVersionDesc(Long projectId, String sheetName);
    Optional<ExportVersion> findByIdAndProjectId(Long id, Long projectId);
}
