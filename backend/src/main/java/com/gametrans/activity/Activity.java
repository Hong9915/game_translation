package com.gametrans.activity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 변경 내역 한 줄(설계 2026-09-30 §1). 행동 하나가 만든 리비전들은 translation_revision.activity_id로 여기 매달린다.
 * projectId가 null이면 게임 단위(단어장 편집)라 그 게임의 모든 프로젝트 피드에 나온다.
 */
@Entity
@Table(name = "activity")
@Getter @Setter @NoArgsConstructor
public class Activity {
    /** EDIT·RETRANSLATE는 detail.segmentId, FIX_TERMS·SHORTEN은 detail.sheet(없으면 전체), GLOSSARY_EDIT은 action·kind·find·ko·oldKo, REVERT는 detail.revertOf(되돌린 항목 id). */
    public enum Kind { EDIT, RETRANSLATE, FIX_TERMS, SHORTEN, GLOSSARY_APPLY, GLOSSARY_EDIT, REVERT, JOSA_FIX, TAG_SPACING, COMMAND_FIX }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    private Long projectId;
    private Long reviewerId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> detail = new HashMap<>();
    private Instant createdAt = Instant.now();
}
