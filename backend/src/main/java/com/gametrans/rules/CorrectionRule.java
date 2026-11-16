package com.gametrans.rules;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 교정 규칙 한 줄(설계 2026-11-13 §2·§4·§7). 결정(status·decided_*·applied_count)은 다시 배워도 키로 유지된다. */
@Entity
@Table(name = "correction_rule")
@Getter @Setter @NoArgsConstructor
public class CorrectionRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    /** WORD | STYLE */
    private String type;
    private String ofText;
    private String nfText;
    @Column(columnDefinition = "char(1)")
    private Character textKind;
    private String anchor;
    /** 쉼표로 이은 문장 모양·끝 문체 집합(WORD). */
    private String shapes;
    private String styles;
    private int fixedCount;
    private int leftCount;
    private int candidateCount;
    private double ratio;
    private double sameAsHuman;
    private int converted;
    private int kept;
    /** STYLE만: RULE | KEEP | DECIDE | INSUFFICIENT */
    private String verdict;
    private Instant lastEditAt;
    private boolean flipped;
    /** SUGGESTED | APPROVED | IGNORED */
    private String status = "SUGGESTED";
    private int appliedCount;
    private Long decidedBy;
    private Instant decidedAt;
    private Instant learnedAt;
}
