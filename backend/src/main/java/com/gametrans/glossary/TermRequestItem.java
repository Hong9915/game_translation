package com.gametrans.glossary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 요청양식의 용어 하나. source가 CORPUS면 renderings(번역본에서 찾은 지금 표기)로 초안이 있어 READY,
 * GPT면 번역본에 없거나 1글자라 GPT 초안을 기다린다(PENDING).
 */
@Entity
@Table(name = "glossary_request_item")
@Getter @Setter @NoArgsConstructor
public class TermRequestItem {
    public static final String CORPUS = "CORPUS", GPT = "GPT";
    public static final String READY = "READY", PENDING = "PENDING", FAILED = "FAILED", APPROVED = "APPROVED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id")
    private Long requestId;
    private int seq;
    /** 양식 분류(CHARACTER, ITEM, SKILL, PLACE, MONSTER, CURRENCY, OTHER). */
    private String category;
    private String src;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<GlossaryDtos.Rendering> renderings = new ArrayList<>();
    private String source;
    @Column(name = "gpt_ko")
    private String gptKo;
    @Column(name = "gpt_reason")
    private String gptReason;
    private String status;
    @Column(name = "final_ko")
    private String finalKo;
    @Column(name = "approved_by")
    private Long approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
}
