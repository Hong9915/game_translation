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

/** 단어장 요청양식을 한 번 올린 것. 올린 파일은 storageKey에 그대로 남긴다. */
@Entity
@Table(name = "glossary_request")
@Getter @Setter @NoArgsConstructor
public class TermRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "game_id")
    private Long gameId;
    @Column(name = "file_name")
    private String fileName;
    @Column(name = "storage_key")
    private String storageKey;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
