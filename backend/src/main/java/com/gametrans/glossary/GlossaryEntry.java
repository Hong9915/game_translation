package com.gametrans.glossary;

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

/**
 * 검수용 단어장 항목. gameId가 null이면 게임을 넘나드는 공유 단어집이다.
 * kind는 무엇으로 찾는지(간체 원문 / 기존 한국어), matchMode는 통째로 같아야 하는지 들어 있기만 하면 되는지.
 */
@Entity
@Table(name = "glossary")
@Getter @Setter @NoArgsConstructor
public class GlossaryEntry {
    public enum Kind { SRC, KO }

    public enum MatchMode { WHOLE, PARTIAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "game_id")
    private Long gameId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_mode")
    private MatchMode matchMode;
    @Column(name = "find")
    private String find;
    private String ko;
    private String note;
    /** SRC+PARTIAL 전용: 지금 번역에서 find를 옮긴 한국어 표기. 있으면 원문 조건부로 old_ko → ko 치환한다. 다른 조합은 null. */
    @Column(name = "old_ko")
    private String oldKo;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
