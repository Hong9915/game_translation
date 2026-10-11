package com.gametrans.normalize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 한 칸(시트·행·열 쌍)의 번역 대상. 같은 행을 여러 사람이 동시에 만지므로(담당 배정, 완료 체크, 번역 저장)
 * 바뀐 칸만 update 하도록 {@link DynamicUpdate}를 건다 — 한 요청이 안 건드린 칸을 예전 값으로 되돌리지 않게.
 */
@Entity
@Table(name = "segment")
@DynamicUpdate
@Getter @Setter @NoArgsConstructor
public class Segment {
    /** 건너뛴 이유. EMPTY_SRC: 원문이 비었거나 N/A 같은 표시. INTENTIONAL: 원문은 있지만 영어·한국어 모두 N/A 표시라 일부러 비워 둔 행. */
    public enum SkipReason { EMPTY_SRC, INTENTIONAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private String sheetName;
    private int rowNo;
    /** 몇 번째 열 쌍(NormalizeConfig.pairs 인덱스)에서 온 칸인지. */
    private int pairIndex;
    private String indexLabel;
    private String keyText;
    private String srcText;
    private String enText;
    private boolean isTerm;
    private Long termId;
    private boolean skipped;
    @Enumerated(EnumType.STRING)
    private SkipReason skipReason;
    /** 기존 한국어 열(EXISTING 모드)의 원래 셀 값. 표시 문자열(N/A 등)도 그대로 둔다. */
    private String existingKoText;
    /** 확정 용어의 한국어가 번역에 빠진 경우 "원문→한국어" 줄 단위. 없으면 null. */
    private String termMiss;
    /** 교정 모드에서 아직 GPT 판정(유지/수정)을 받지 않은 행. */
    private boolean proofPending;
    /** 교정에서 고친 사유. 유지했거나 교정 대상이 아니면 null. */
    private String proofNote;
    /** 공동 검토 담당자(reviewer.id). 없으면 미배정. 담당은 표시·필터용이고 수정을 막지 않는다. */
    private Long assigneeId;
    /** 검토 완료 체크. */
    private boolean done;
    private Long doneBy;
    private Instant doneAt;
    /** 검수 체크(설계 2026-09-28). 1차·2차·확인 필요는 표시일 뿐 행을 잠그지 않는다. */
    private boolean review1;
    private boolean review2;
    private boolean needsCheck;
    /** 확정(2026-10-06). 같은 원문 전파·단어장 일괄 적용·되돌리기는 확정 행을 건너뛴다. 손 편집은 막지 않는다. */
    private boolean confirmed;
    /** 검수자가 적는 비고. 내보내기 파일의 비고 열로 나간다. 없으면 null. */
    private String note;
    /** 최신 리비전의 한국어(Fix). DB 트리거(V14)가 유지하므로 여기서는 읽기만 한다. 리비전이 없으면 null. */
    @Column(insertable = false, updatable = false)
    private String latestKo;
    /** 조사 검사(V20). 셋 다 네이티브 update로만 쓴다 — 엔티티를 통째로 저장하는 경로가 낡은 값으로 덮지 않게.
     *  null = 검사 대상 아님(아무도 안 열었고 번역도 안 바뀜), false = 검사 대기, true = 검사함. */
    @Column(insertable = false, updatable = false)
    private Boolean josaChecked;
    /** 제안 목록 JSON([{at,from,to,kind}]). 없으면 null. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(insertable = false, updatable = false)
    private String josaHint;
    @Column(insertable = false, updatable = false)
    private boolean josaIgnored;
}
