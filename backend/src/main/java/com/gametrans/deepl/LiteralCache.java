package com.gametrans.deepl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 저장해 둔 DeepL 직역 하나(V21). 원문 → 직역. */
@Entity
@Table(name = "literal_cache")
@Getter
@NoArgsConstructor
public class LiteralCache {
    @Id
    private String src;

    private String ko;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public LiteralCache(String src, String ko) { this.src = src; this.ko = ko; }
}
