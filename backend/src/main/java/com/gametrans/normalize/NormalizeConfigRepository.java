package com.gametrans.normalize;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NormalizeConfigRepository extends JpaRepository<NormalizeConfig, Long> {
    /** id 순 = 시트를 설정한 순서. 화면의 시트 탭 순서이자 내보내기의 첫 시트를 정하는 순서다. */
    List<NormalizeConfig> findByProjectIdOrderByIdAsc(Long projectId);
    Optional<NormalizeConfig> findByProjectIdAndSheetName(Long projectId, String sheetName);
    long countByProjectId(Long projectId);
}
