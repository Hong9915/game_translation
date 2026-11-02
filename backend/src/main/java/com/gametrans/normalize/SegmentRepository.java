package com.gametrans.normalize;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;

public interface SegmentRepository extends JpaRepository<Segment, Long>, JpaSpecificationExecutor<Segment> {
    List<Segment> findByProjectIdOrderBySheetNameAscRowNoAscPairIndexAsc(Long projectId);
    List<Segment> findByProjectIdAndSkippedFalseOrderBySheetNameAscRowNoAscPairIndexAsc(Long projectId);
    List<Segment> findByProjectIdAndSheetNameAndSkippedFalseOrderByRowNoAscPairIndexAsc(Long projectId, String sheetName);
    /** 시트 하나만 다시 만들 때. 다른 시트의 세그먼트(와 그 수정본·담당·완료)는 건드리지 않는다. */
    @Modifying @Transactional void deleteByProjectIdAndSheetName(Long projectId, String sheetName);
    long countByProjectIdAndSkippedFalse(Long projectId);

    /** 정규화 통계 한 줄. sheet가 null이면 프로젝트 전체, 주면 그 시트만. */
    interface Counts {
        long getTotalRows();
        long getNormalized();
        long getSkipped();
        long getWithEn();
        long getIntentional();
    }

    @Query("""
        select count(s) as totalRows,
               coalesce(sum(case when s.skipped = false then 1L else 0L end), 0L) as normalized,
               coalesce(sum(case when s.skipped = true then 1L else 0L end), 0L) as skipped,
               coalesce(sum(case when s.skipped = false and s.enText is not null then 1L else 0L end), 0L) as withEn,
               coalesce(sum(case when s.skipReason = com.gametrans.normalize.Segment.SkipReason.INTENTIONAL then 1L else 0L end), 0L) as intentional
        from Segment s
        where s.projectId = :pid and coalesce(:sheet, s.sheetName) = s.sheetName
        """)
    Counts counts(@Param("pid") Long projectId, @Param("sheet") String sheet);

    /** 정규화 때 기존 한국어를 0차로 넣어 둔 행 수. sheet가 null이면 프로젝트 전체. */
    @Query("""
        select count(s) from Segment s
        where s.projectId = :pid and coalesce(:sheet, s.sheetName) = s.sheetName and s.skipped = false
          and exists (select r from TranslationRevision r where r.segmentId = s.id and r.author = com.gametrans.translate.TranslationRevision.Author.EXISTING)
        """)
    long countKept(@Param("pid") Long projectId, @Param("sheet") String sheet);

    @Query("""
        select s from Segment s
        where s.projectId = :pid and s.skipped = false
          and not exists (select r from TranslationRevision r where r.segmentId = s.id)
        order by s.sheetName, s.rowNo, s.pairIndex
        """)
    List<Segment> findUntranslated(@Param("pid") Long projectId);

    /** 교정 판정을 기다리는 행. */
    @Query("""
        select s from Segment s
        where s.projectId = :pid and s.skipped = false and s.proofPending = true
        order by s.sheetName, s.rowNo, s.pairIndex
        """)
    List<Segment> findProofPending(@Param("pid") Long projectId);

    @Query("select count(s) from Segment s where s.projectId = :pid and s.skipped = false and s.proofPending = true")
    long countProofPending(@Param("pid") Long projectId);

    List<Segment> findByProjectIdAndSkippedFalseAndSheetNameAndRowNoBetween(Long projectId, String sheetName, int fromRow, int toRow);
    List<Segment> findByProjectIdAndSkippedFalseAndIdIn(Long projectId, java.util.Collection<Long> ids);
    List<Segment> findByProjectIdInAndSkippedFalseAndIdIn(java.util.Collection<Long> projectIds, java.util.Collection<Long> ids);

    @Query("select s from Segment s where s.projectId = :pid and s.termMiss is not null")
    List<Segment> findTermMissed(@Param("pid") Long projectId);

    @Query("""
        select count(s) from Segment s
        where s.projectId = :pid and s.skipped = false
          and not exists (select r from TranslationRevision r where r.segmentId = s.id)
        """)
    long countUntranslated(@Param("pid") Long projectId);

    /** 진행률 집계 한 줄. assigneeId가 null인 줄이 미배정. */
    interface AssigneeCount {
        Long getAssigneeId();
        long getAssigned();
        long getDone();
        /** 2차 체크 수. 검수 워크스페이스의 검토자별 진행에 쓴다(옛 진행률 화면은 done). */
        long getReviewed();
    }

    /** 검토자별 배정·완료 수. 행을 다 읽어 세지 않도록 DB에서 집계한다. sheet가 null이면 프로젝트 전체. */
    @Query("""
        select s.assigneeId as assigneeId, count(s) as assigned, sum(case when s.done = true then 1L else 0L end) as done,
               sum(case when s.review2 = true then 1L else 0L end) as reviewed
        from Segment s
        where s.projectId = :pid and s.skipped = false and coalesce(:sheet, s.sheetName) = s.sheetName
        group by s.assigneeId
        order by s.assigneeId
        """)
    List<AssigneeCount> countByAssignee(@Param("pid") Long projectId, @Param("sheet") String sheet);

    /** 진행률 화면의 시트 선택지. 건너뛴 세그먼트는 화면에 안 보이므로 뺀다. */
    @Query("select distinct s.sheetName from Segment s where s.projectId = :pid and s.skipped = false order by s.sheetName")
    List<String> sheetNames(@Param("pid") Long projectId);

    /**
     * 검수 워크스페이스 시트 탭용 집계(총·1차·2차·확인 필요·확정). changed는 여기 넣지 않는다 —
     * "N/A" 같은 표시 문자열을 빈 값으로 보는 기준(EmptyMarkers)이 SegmentSpecs.filter(CHANGED)에 있고,
     * 이 JPQL에서 같은 기준을 다시 표현하면 두 쿼리가 서로 다른 답을 낼 위험이 있다.
     * 시트별 changed는 WorkspaceService에서 그 Specification으로 따로 센다.
     */
    interface SheetCount {
        String getSheet();
        long getTotal();
        long getReview1();
        long getReview2();
        long getNeedsCheck();
        long getConfirmed();
        long getJosa();
        long getJosaPending();
        long getFinalMark();
    }

    @Query("""
        select s.sheetName as sheet, count(s) as total,
               coalesce(sum(case when s.review1 = true then 1L else 0L end), 0L) as review1,
               coalesce(sum(case when s.review2 = true then 1L else 0L end), 0L) as review2,
               coalesce(sum(case when s.needsCheck = true then 1L else 0L end), 0L) as needsCheck,
               coalesce(sum(case when s.confirmed = true then 1L else 0L end), 0L) as confirmed,
               coalesce(sum(case when s.finalMark = true then 1L else 0L end), 0L) as finalMark,
               coalesce(sum(case when s.josaHint is not null and s.josaIgnored = false then 1L else 0L end), 0L) as josa,
               coalesce(sum(case when s.josaChecked = false and trim(coalesce(s.latestKo, '')) <> '' then 1L else 0L end), 0L) as josaPending
        from Segment s
        where s.projectId = :pid and s.skipped = false
        group by s.sheetName
        """)
    List<SheetCount> countBySheet(@Param("pid") Long projectId);

    /**
     * 아래 세 update는 엔티티를 통째로 저장하지 않고 바꿀 칸만 쓴다. 번역 저장·GPT 작업은 세그먼트를
     * 읽은 지 한참 뒤에 결과를 쓰는데(긴 작업은 시작할 때 읽은 사본을 계속 들고 있다), 통째로 저장하면
     * 그 사이 다른 사람이 바꾼 담당·완료가 예전 값으로 되돌아간다.
     */
    @Modifying @Transactional
    @Query("update Segment s set s.termMiss = :termMiss where s.id = :id")
    void updateTermMiss(@Param("id") Long id, @Param("termMiss") String termMiss);

    /** 비고가 있는 칸(내보내기의 비고 열). 행 번호는 시트마다 겹치므로 시트 이름과 함께 본다. */
    interface SegmentNote {
        String getSheet();
        int getRowNo();
        int getPairIndex();
        String getNote();
    }

    @Query("""
        select s.sheetName as sheet, s.rowNo as rowNo, s.pairIndex as pairIndex, s.note as note
        from Segment s where s.projectId = :pid and s.skipped = false and s.note is not null
        order by s.sheetName, s.rowNo, s.pairIndex
        """)
    List<SegmentNote> notesOf(@Param("pid") Long projectId);

    /** 주어진 id 가운데 그 프로젝트·시트의 건너뛰지 않은 행만(행 순서). 일괄 체크가 남의 프로젝트 행을 건드리지 않게 한다. */
    @Query("select s.id from Segment s where s.projectId = :pid and s.sheetName = :sheet and s.skipped = false and s.id in :ids order by s.rowNo, s.pairIndex")
    List<Long> idsInSheet(@Param("pid") Long projectId, @Param("sheet") String sheet, @Param("ids") List<Long> ids);

    /** 내보내기: 최종 수정 표시를 켠 행 id. 최종 리뷰 시트의 칸만 연두로 채운다. */
    @Query("select s.id from Segment s where s.projectId = :pid and s.finalMark = true")
    java.util.Set<Long> finalMarkedIds(@Param("pid") Long projectId);

    /** 설정 없이 행만 남은 시트의 레인 — 행이 최종 리뷰 레인이면 true. */
    boolean existsByProjectIdAndSheetNameAndFinalReviewTrue(Long projectId, String sheetName);

    /** 검수 체크 칸만 쓴다. null인 값은 그대로 둔다(엔티티 통째 저장 금지 — 위 update들과 같은 이유). */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query(value = """
        update segment set review1 = coalesce(cast(:r1 as boolean), review1),
                           review2 = coalesce(cast(:r2 as boolean), review2),
                           needs_check = coalesce(cast(:nc as boolean), needs_check),
                           confirmed = coalesce(cast(:cf as boolean), confirmed),
                           final_mark = coalesce(cast(:fm as boolean), final_mark)
         where id in (:ids)
        """, nativeQuery = true)
    int updateMarks(@Param("ids") List<Long> ids, @Param("r1") Boolean r1, @Param("r2") Boolean r2, @Param("nc") Boolean nc,
                    @Param("cf") Boolean cf, @Param("fm") Boolean fm);

    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Segment s set s.note = :note where s.id = :id")
    void updateNote(@Param("id") Long id, @Param("note") String note);

    /** 교정에서 기존 문장을 유지한 행. 사유(proofNote)는 건드리지 않는다. */
    @Modifying @Transactional
    @Query("update Segment s set s.proofPending = false, s.termMiss = :termMiss where s.id = :id")
    void markProofKept(@Param("id") Long id, @Param("termMiss") String termMiss);

    /** 용어 고치기에서 고친 행. 교정 판정 대기(proofPending)는 건드리지 않는다. */
    @Modifying @Transactional
    @Query("update Segment s set s.termMiss = :termMiss, s.proofNote = :note where s.id = :id")
    void markTermsFixed(@Param("id") Long id, @Param("termMiss") String termMiss, @Param("note") String note);

    /** 교정에서 고친 행. 사유를 함께 남긴다. */
    @Modifying @Transactional
    @Query("update Segment s set s.proofPending = false, s.termMiss = :termMiss, s.proofNote = :note where s.id = :id")
    void markProofFixed(@Param("id") Long id, @Param("termMiss") String termMiss, @Param("note") String note);

    /**
     * 그 검토자의 흔적(담당·완료 표시·수정 기록)이 남아 있는 프로젝트들. 검토자 이름 변경·삭제를
     * 열려 있는 다른 화면에 알릴 때, 어느 프로젝트 스트림으로 보낼지 정하는 데 쓴다.
     */
    @Query("""
        select distinct s.projectId from Segment s
        where s.assigneeId = :rid or s.doneBy = :rid
           or exists (select r from TranslationRevision r where r.segmentId = s.id and r.reviewerId = :rid)
        """)
    List<Long> projectIdsTouchedByReviewer(@Param("rid") Long reviewerId);

    /**
     * 검토자 삭제 시 담당·완료자를 다른 검토자에게 옮기거나(to) 비운다(to가 null).
     * clear/flush를 켜 두는 이유는 updateTermMiss 무리와 같다 — 벌크 update는 영속성 컨텍스트를 비켜 가므로,
     * 같은 트랜잭션에서 이어지는 읽기가 옮기기 전 값을 든 managed 엔티티를 보거나 그걸 다시 써 버리면 안 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Segment s set s.assigneeId = :to where s.assigneeId = :from")
    int moveAssignee(@Param("from") Long from, @Param("to") Long to);

    /** 조사 검사 대기 행(설계 2026-10-07 §3). 빈 번역은 markBlankJosaChecked가 먼저 치운다. */
    interface JosaPending {
        Long getId();
        Long getGameId();
        String getLatestKo();
    }

    @Query(value = """
        select s.id as id, p.game_id as gameId, s.latest_ko as latestKo
          from segment s join project p on p.id = s.project_id
         where s.josa_checked = false and s.skipped = false and coalesce(btrim(s.latest_ko), '') <> ''
         order by s.id limit :limit
        """, nativeQuery = true)
    List<JosaPending> josaPending(@Param("limit") int limit);

    @Modifying @Transactional
    @Query(value = "update segment set josa_checked = true where josa_checked = false and skipped = false and coalesce(btrim(latest_ko), '') = ''", nativeQuery = true)
    int markBlankJosaChecked();

    /** 읽은 뒤 latest_ko가 바뀌었으면 0건 — 다음 차례에 다시 검사된다. */
    @Modifying @Transactional
    @Query(value = "update segment set josa_checked = true, josa_hint = cast(:hint as jsonb) where id = :id and latest_ko = :read", nativeQuery = true)
    int saveJosa(@Param("id") Long id, @Param("read") String read, @Param("hint") String hint);

    @Modifying @Transactional
    @Query(value = "update segment set josa_checked = true, josa_hint = null where id = :id and latest_ko = :read", nativeQuery = true)
    int saveJosaNone(@Param("id") Long id, @Param("read") String read);

    /** 누군가 파일을 열면 그 파일에서 아직 검사 대상이 아니던 행을 검사 대기로 올린다. */
    @Modifying @Transactional
    @Query(value = "update segment set josa_checked = false where project_id = :pid and josa_checked is null and skipped = false", nativeQuery = true)
    int requestJosa(@Param("pid") Long projectId);

    @Modifying @Transactional
    @Query(value = "update segment set josa_ignored = true where id = :id and project_id = :pid", nativeQuery = true)
    int ignoreJosa(@Param("pid") Long projectId, @Param("id") Long id);

    /** 조사 거르기용 말뭉치(JosaCorpus). */
    @Query(value = """
        select s.latest_ko from segment s join project p on p.id = s.project_id
         where p.game_id = :gameId and s.skipped = false and s.latest_ko is not null
        """, nativeQuery = true)
    List<String> latestKoInGame(@Param("gameId") Long gameId);

    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update Segment s set s.doneBy = :to where s.doneBy = :from")
    int moveDoneBy(@Param("from") Long from, @Param("to") Long to);

    /**
     * 같은 게임·같은 레인(최종 리뷰/일반) 안에서 원문이 글자까지 같은 다른 행. 확정·제외 행은 뺀다(확정한 행을 다른 행 수정이 몰래 바꾸지 않게).
     * md5 조건이 segment_src_md5_idx를 타게 하고, src_text 비교로 해시 충돌을 거른다.
     */
    @Query(value = """
        select s.* from segment s join project p on p.id = s.project_id
         where p.game_id = :gameId and s.final_review = :finalReview
           and md5(s.src_text) = md5(:src) and s.src_text = :src
           and s.skipped = false and s.confirmed = false
           and s.id <> :excludeId
        """, nativeQuery = true)
    List<Segment> findSameSourceInGame(@Param("gameId") Long gameId, @Param("finalReview") boolean finalReview, @Param("src") String src, @Param("excludeId") Long excludeId);

    /**
     * 단어장 항목 하나가 걸릴 수 있는 후보 행. 확정·제외 행은 뺀다.
     * kind·matchMode 조합별로 좁히는 조건만 다르다 — SRC+WHOLE은 원문이 통째로 같은 행, KO는 WHOLE·PARTIAL
     * 가리지 않고 최신 한국어에 find가 부분이라도 들어 있는 행(strpos)까지로 넓게 잡는다. KO+WHOLE을
     * btrim(latest_ko) = :find로 좁히지 않는 이유(Ruling 23): postgres btrim은 공백만 지우는데 자바
     * cur.trim()은 탭·개행도 지운다. GPT 리비전이 남긴 "정의의 심판\n" 같은 값은 btrim으로는 안 걸리지만
     * 실제로는(자바 trim 기준) 통째 일치이므로 후보에서 빠지면 안 된다. 최종 판정(정말 통째로 같은지,
     * 대치 결과가 실제로 달라지는지)은 GlossaryApplier.next()가 자바에서 한다 — 여기서는 후보만 넓게 좁힌다.
     * SRC+PARTIAL은 old_ko가 있을 때만 후보가 있다: 원문에 find가 들어 있고 최신 한국어에 old_ko가 들어 있는 행.
     * old_ko가 없는 호출은 ""를 넘기는데 그 경우 GlossaryApplier가 이미 0행으로 돌려보내므로 여기까지 오지 않는다.
     * (null을 native 쿼리에 바인딩하면 드라이버가 타입을 정하지 못해 "could not determine data type"이 나므로 ""로 통일한다.)
     */
    @Query(value = """
        select s.* from segment s join project p on p.id = s.project_id
         where p.game_id = :gameId and s.skipped = false and s.confirmed = false
           and (
             (:kind = 'SRC' and :mode = 'WHOLE' and s.src_text = :find)
             or (:kind = 'SRC' and :mode = 'PARTIAL' and :oldKo <> '' and strpos(s.src_text, :find) > 0 and strpos(s.latest_ko, :oldKo) > 0)
             or (:kind = 'KO' and strpos(s.latest_ko, :find) > 0)
           )
        """, nativeQuery = true)
    List<Segment> findEditableMatching(@Param("gameId") Long gameId, @Param("kind") String kind,
                                        @Param("mode") String mode, @Param("find") String find, @Param("oldKo") String oldKo);

    /**
     * 게임 안에서 원문이 같은 행이 둘 이상인데 최신 한국어가 서로 다른 묶음 — 그 묶음에 든 범위(SheetScope) 행 한 페이지.
     * conflict CTE는 게임 전체를 한 번만 묶어 해시 목록을 만들고, hit CTE는 그 목록과 범위 행을 해시 조인한다.
     * 둘 다 MATERIALIZED로 막아 두는 이유: 막지 않으면 플래너가 LIMIT을 보고 (project, sheet, row) 인덱스 순서대로
     * 행을 하나씩 꺼내 해시 목록 전체와 md5를 매번 다시 비교하는 Nested Loop를 고른다 — 실데이터 4.9만 행 시트에서
     * 한 페이지에 12초~수백 초가 걸렸다. 막으면 한 페이지가 OFFSET과 무관하게 0.15초 안팎이다.
     * keys는 SheetScope.joinedKeys() — (프로젝트 id, 시트) 쌍을 chr(30)으로 잇고 쌍끼리는 chr(31)로 이은 한 문자열. 배열 바인딩을
     * 피하려고 문자열 하나로 받는다. 정렬은 그 목록의 순서(게임 순서) → 행 → 열 쌍. pids는 인덱스를 타게 하는 앞 조건이다.
     * pattern은 SegmentSpecs.likePattern — 검색어가 없으면 '%'라 모든 행이 걸린다(search와 같은 뜻). qIn은 SearchIn 이름(DUP_CONFLICT_SEARCH 참고).
     */
    @Query(value = DUP_CONFLICT_CTE + """
        , hit as materialized (
          select s.* from segment s join conflict k on k.hash = md5(s.src_text)
           where s.project_id in (:pids) and (s.project_id || chr(30) || s.sheet_name) = any(string_to_array(:keys, chr(31))) and s.skipped = false
        """ + DUP_CONFLICT_SEARCH + """
        )
        select * from hit
         order by array_position(string_to_array(:keys, chr(31)), project_id || chr(30) || sheet_name), row_no, pair_index
        offset :offset limit :limit
        """, nativeQuery = true)
    List<Segment> dupConflictPage(@Param("gameId") Long gameId, @Param("finalReview") boolean finalReview, @Param("pids") List<Long> projectIds, @Param("keys") String keys,
                                  @Param("pattern") String pattern, @Param("qIn") String qIn, @Param("q") String q, @Param("offset") long offset, @Param("limit") int limit);

    /** dupConflictPage와 같은 조건의 전체 행 수. conflict의 해시는 group by로 서로 달라 조인이 행을 불리지 않는다. */
    @Query(value = DUP_CONFLICT_CTE + """
        select count(*) from segment s join conflict k on k.hash = md5(s.src_text)
         where s.project_id in (:pids) and (s.project_id || chr(30) || s.sheet_name) = any(string_to_array(:keys, chr(31))) and s.skipped = false
        """ + DUP_CONFLICT_SEARCH, nativeQuery = true)
    long dupConflictCount(@Param("gameId") Long gameId, @Param("finalReview") boolean finalReview, @Param("pids") List<Long> projectIds, @Param("keys") String keys,
                          @Param("pattern") String pattern, @Param("qIn") String qIn, @Param("q") String q);

    /** 불일치 원문 해시의 유일한 정의. 제외 행은 빼고, 빈칸·공백뿐인 한국어는 모두 같은 "없음"(∅)으로 본다. */
    String DUP_CONFLICT_CTE = """
        with conflict as materialized (
          select md5(c.src_text) as hash
            from segment c join project p on p.id = c.project_id
           where p.game_id = :gameId and c.final_review = :finalReview and c.skipped = false
           group by md5(c.src_text)
          having count(*) > 1
             and count(distinct coalesce(nullif(btrim(c.latest_ko), ''), '∅')) > 1
        )
        """;

    /**
     * SegmentSpecs.search와 같은 조건 — 원문·Fix·비고에서(qIn이 EXISTING_KO면 기존 한국어에서만) 대소문자 무시 부분 일치,
     * 이스케이프 문자는 역슬래시. SRC_CONTAINS면 원문에서만 부분 일치, SRC_EXACT면 원문이 q와 글자 그대로 같은 행만.
     */
    String DUP_CONFLICT_SEARCH = """
           and (case when :qIn = 'EXISTING_KO' then lower(coalesce(s.existing_ko_text, '')) like :pattern escape '\\'
                when :qIn = 'SRC_EXACT' then s.src_text = :q
                when :qIn = 'SRC_CONTAINS' then lower(coalesce(s.src_text, '')) like :pattern escape '\\'
                else (lower(coalesce(s.src_text, '')) like :pattern escape '\\'
                      or lower(coalesce(s.latest_ko, '')) like :pattern escape '\\'
                      or lower(coalesce(s.note, '')) like :pattern escape '\\') end)
        """;

    /** 태그 경계 띄어쓰기 후보 한 행(2026-10-08). */
    interface TaggedRow { Long getId(); Integer getRowNo(); String getSrc(); String getKo(); }

    /** 시트에서 고칠 수 있는(제외·확정이 아닌) 행 가운데 한국어에 태그가 든 것. 행 순. */
    @Query(value = """
        select s.id as id, s.row_no as rowNo, s.src_text as src, s.latest_ko as ko from segment s
         where s.project_id = :projectId and s.sheet_name = :sheet and s.skipped = false and s.confirmed = false
           and s.latest_ko like '%<%>%'
         order by s.row_no, s.pair_index
        """, nativeQuery = true)
    List<TaggedRow> findEditableTaggedInSheet(@Param("projectId") Long projectId, @Param("sheet") String sheet);

    /** 명령으로 고치기(2026-10-08): 시트에서 고칠 수 있는(제외·확정·용어가 아닌) 행 가운데 번역이 있는 것. 행 순. */
    @Query(value = """
        select s.id as id, s.row_no as rowNo, s.src_text as src, s.latest_ko as ko from segment s
         where s.project_id = :projectId and s.sheet_name = :sheet and s.skipped = false and s.confirmed = false
           and s.latest_ko <> ''
         order by s.row_no, s.pair_index
        """, nativeQuery = true)
    List<TaggedRow> findEditableTranslatedInSheet(@Param("projectId") Long projectId, @Param("sheet") String sheet);

    /** 명령으로 고치기 작업 목록 입력 행(2026-10-08). */
    interface CommandRow { Long getId(); Integer getRowNo(); String getSrc(); String getKo(); boolean isConfirmed();
                           Boolean getJosaChecked(); String getJosaHint(); boolean isJosaIgnored(); }

    /** 시트에서 제외가 아니고 번역이 있는 행. 확정 행도 든다(같은 원문 통일의 목표). 행 순. */
    @Query(value = """
        select s.id as id, s.row_no as rowNo, s.src_text as src, s.latest_ko as ko, s.confirmed as confirmed,
               s.josa_checked as josaChecked, cast(s.josa_hint as text) as josaHint, s.josa_ignored as josaIgnored
          from segment s
         where s.project_id = :projectId and s.sheet_name = :sheet and s.skipped = false
           and s.latest_ko <> ''
         order by s.row_no, s.pair_index
        """, nativeQuery = true)
    List<CommandRow> findCommandRowsInSheet(@Param("projectId") Long projectId, @Param("sheet") String sheet);

    /** ids 가운데 이 프로젝트에서 고칠 수 있는(제외·확정·용어가 아닌) 행. */
    @Query(value = """
        select s.* from segment s
         where s.project_id = :projectId and s.id in (:ids) and s.skipped = false and s.confirmed = false
        """, nativeQuery = true)
    List<Segment> findEditableInProject(@Param("projectId") Long projectId, @Param("ids") java.util.Collection<Long> ids);

    /** findEditableInProject의 여러 프로젝트판(게임 화면). 시트까지 좁히는 건 부르는 쪽이 범위로 한다. */
    @Query(value = """
        select s.* from segment s
         where s.project_id in (:pids) and s.id in (:ids) and s.skipped = false and s.confirmed = false
        """, nativeQuery = true)
    List<Segment> findEditableInProjects(@Param("pids") java.util.Collection<Long> projectIds, @Param("ids") java.util.Collection<Long> ids);

    interface SrcCount { String getHash(); long getRows(); }

    /** 단어장 기존 표기 후보 계산용 (원문, 최신 한국어) 한 쌍. */
    interface SrcKo { String getSrc(); String getKo(); }

    /**
     * 게임의 통계용 행: 제외 행을 빼고 한국어가 있는 행의 두 칼럼만. 2차 체크 행도 넣는다 — 번역으로는 유효하다
     * (적용 후보 findEditableMatching과 달리 확정을 거르지 않는다). 5만 행에서 10MB 안팎.
     */
    @Query(value = """
        select s.src_text as src, s.latest_ko as ko from segment s join project p on p.id = s.project_id
         where p.game_id = :gameId and s.skipped = false and s.latest_ko is not null
         order by s.id
        """, nativeQuery = true)
    List<SrcKo> findSrcKoInGame(@Param("gameId") Long gameId);


    /**
     * 이 게임에서 원문별 행 수. 그리드의 ×N 칩에 쓴다. hashes는 호출한 쪽(페이지)에 실제로 나온 행의 해시로
     * 좁힌 목록 — group by 자체는 게임 전체를 대상으로 그대로 돌므로 집계값(rows)은 좁히기 전과 같고,
     * 결과 행 수만 페이지 크기만큼(최대 500개)으로 줄어든다.
     */
    @Query(value = """
        select md5(s.src_text) as hash, count(*) as rows from segment s join project p on p.id = s.project_id
         where p.game_id = :gameId and s.final_review = :finalReview and s.skipped = false
           and md5(s.src_text) in (:hashes)
         group by md5(s.src_text) having count(*) > 1
        """, nativeQuery = true)
    List<SrcCount> countBySourceInGame(@Param("gameId") Long gameId, @Param("finalReview") boolean finalReview, @Param("hashes") List<String> hashes);

    /** 이어받기 후보 한 쌍(세그먼트 id·이어받을 한국어). */
    interface InheritedKo {
        Long getSegmentId();
        String getKo();
    }

    /**
     * 새로 정규화한 시트에서 기존 한국어가 없는 행에, 같은 게임의 다른 프로젝트가 이미 정한 번역을 이어받게 한다.
     * 후보(이 프로젝트·이 시트, 제외 아님, 최신 한국어 없음)를 원문 md5로 다른 프로젝트의 확정 행과
     * 한 번에 묶어 읽는다 — 행마다 쿼리하거나 원문 목록을 IN 바인드로 만들지 않는다(수만 행에서 못 쓴다).
     * 후보·소스 행 모두 완료(done) 여부를 가리지 않는다 — 후보는 방금 정규화로 새로 만든 행이라 done이 항상
     * false라 걸러도 의미가 없고, 소스는 완료로 잠근 결정도 그대로 이어받아야 한다.
     * 원문이 같은 다른 프로젝트 행들의 최신 한국어가 하나로 모일 때만(having count=1) 이어받는다. 서로 다르면
     * 이어받지 않고 그 행은 DUP_CONFLICT 필터로 사람이 본다.
     * sample_src로 md5 해시 충돌을 한 번 더 걸러 findSameSourceInGame과 같은 안전장치를 둔다.
     */
    @Query(value = """
        select cand.id as segmentId, agg.only_ko as ko
          from segment cand
          join (
                select md5(s.src_text) as hash, min(s.src_text) as sample_src, min(btrim(s.latest_ko)) as only_ko
                  from segment s join project p on p.id = s.project_id
                 where p.game_id = :gameId and s.project_id <> :projectId and s.final_review = :finalReview
                   and s.skipped = false
                   and s.latest_ko is not null and btrim(s.latest_ko) <> ''
                 group by md5(s.src_text)
                having count(distinct btrim(s.latest_ko)) = 1
               ) agg on md5(cand.src_text) = agg.hash and cand.src_text = agg.sample_src
         where cand.project_id = :projectId and cand.sheet_name = :sheetName
           and cand.skipped = false
           and (cand.latest_ko is null or btrim(cand.latest_ko) = '')
        """, nativeQuery = true)
    List<InheritedKo> findInheritableKo(@Param("gameId") Long gameId, @Param("finalReview") boolean finalReview, @Param("projectId") Long projectId, @Param("sheetName") String sheetName);
}
