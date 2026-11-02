package com.gametrans.activity;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findByProjectIdOrderByIdDesc(Long projectId);
    List<Activity> findByGameIdAndProjectIdIsNullOrderByIdDesc(Long gameId);

    /** 대표 행·펼친 행 한 줄. projectId: 같은 원문 전파는 게임의 다른 파일 행도 바꾸므로 행마다 어느 파일인지 알려 준다. prevText = 같은 세그먼트의 바로 앞 판(없으면 null), ordinal = 그 시트 전체 보기 정렬(row_no, pair_index)에서의 0-기반 위치. */
    interface RevisionRowView {
        Long getActivityId(); Long getSegmentId(); Long getProjectId(); String getSheet(); Integer getRowNo(); Long getOrdinal(); String getPrevText(); String getNextText();
    }

    String ROW_SELECT = """
        select r.activity_id as activityId, r.segment_id as segmentId, s.project_id as projectId, s.sheet_name as sheet, s.row_no as rowNo, r.text as nextText,
               (select p.text from translation_revision p where p.segment_id = r.segment_id and p.version < r.version
                 order by p.version desc limit 1) as prevText,
               (select count(*) from segment o where o.project_id = s.project_id and o.sheet_name = s.sheet_name and o.skipped = false
                  and (o.row_no < s.row_no or (o.row_no = s.row_no and o.pair_index < s.pair_index))) as ordinal
          from translation_revision r join segment s on s.id = r.segment_id
        """;

    /** 프로젝트 항목과 같은 게임의 게임 단위 항목(단어장 편집)을 섞어 최신순. before보다 작은 id만(커서). */
    @Query("select a from Activity a where (a.projectId = :pid or (a.projectId is null and a.gameId = :gid)) and a.id < :before order by a.id desc")
    List<Activity> feed(@Param("pid") Long projectId, @Param("gid") Long gameId, @Param("before") long before, Pageable page);

    /** 게임 전체 리뷰 화면의 피드: 범위에 든 파일들의 항목 + 게임 단위 항목. */
    @Query("select a from Activity a where (a.projectId in :pids or (a.projectId is null and a.gameId = :gid)) and a.id < :before order by a.id desc")
    List<Activity> feedOfProjects(@Param("pids") List<Long> projectIds, @Param("gid") Long gameId, @Param("before") long before, Pageable page);

    @Query("select r.activityId, count(r) from TranslationRevision r where r.activityId in :ids group by r.activityId")
    List<Object[]> rowCounts(@Param("ids") List<Long> ids);

    /** EDIT·RETRANSLATE의 대표 행: detail.segmentId가 가리키는 세그먼트에 이 활동이 남긴 판. 없으면(같은 값 저장) 안 온다. */
    @Query(value = ROW_SELECT + """
          join activity a on a.id = r.activity_id and r.segment_id = cast(a.detail->>'segmentId' as bigint)
         where a.id in (:ids)
        """, nativeQuery = true)
    List<RevisionRowView> primaries(@Param("ids") List<Long> ids);

    /** 펼친 행 목록. exclude는 대표 행(없으면 -1). 파일이 섞이면 (시트, 행)이 같은 행이 생기므로 파일 → 시트 → 행 → 판 id로 순서를 고정해 쪽이 겹치지 않게 한다. */
    @Query(value = ROW_SELECT + """
         where r.activity_id = :aid and r.segment_id <> :exclude
         order by s.project_id, s.sheet_name, s.row_no, s.pair_index, r.id
         limit :limit offset :offset
        """, nativeQuery = true)
    List<RevisionRowView> rowsOf(@Param("aid") Long activityId, @Param("exclude") long exclude, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = "select count(*) from translation_revision r where r.activity_id = :aid and r.segment_id <> :exclude", nativeQuery = true)
    long countRowsOf(@Param("aid") Long activityId, @Param("exclude") long exclude);
}
