package com.gametrans.glossary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 한 게임에서 원문 구절 하나의 분류(V22). 표기 갈림 탭의 분류 필터가 쓴다. */
@Entity
@Table(name = "glossary_category")
@IdClass(GlossaryCategory.Key.class)
@Getter
@NoArgsConstructor
public class GlossaryCategory {
    @Id
    @Column(name = "game_id")
    private Long gameId;

    @Id
    private String src;

    private String category;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public GlossaryCategory(Long gameId, String src, String category) { this.gameId = gameId; this.src = src; this.category = category; }

    @lombok.EqualsAndHashCode
    @NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Key implements Serializable {
        private Long gameId;
        private String src;
    }
}
