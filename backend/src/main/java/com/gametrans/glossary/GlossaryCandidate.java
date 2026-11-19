package com.gametrans.glossary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 승인 대기 단어장 후보. status는 PENDING·APPROVED·DISMISSED. */
@Entity
@Table(name = "glossary_candidate")
@Getter @Setter @NoArgsConstructor
public class GlossaryCandidate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    private String src;
    private String ko;
    private String oldKo;
    @Column(name = "rows")
    private int rows;
    private String reason;
    private String source;
    private Long proposalId;
    private Long createdBy;
    private Instant createdAt = Instant.now();
    private String status = "PENDING";
    private Long decidedBy;
    private Instant decidedAt;
}
