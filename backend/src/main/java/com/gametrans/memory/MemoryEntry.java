package com.gametrans.memory;

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

/** 게임 단위 문장 번역 메모리. 원문이 완전히 같으면 GPT 없이 그대로 쓰고, 비슷하면 프롬프트 참고 번역으로 넣는다. */
@Entity
@Table(name = "translation_memory")
@Getter @Setter @NoArgsConstructor
public class MemoryEntry {
    /** HUMAN: 검수 화면에서 사람이 고침. EXPORT: 내보낸 파일의 최종 문장. EXISTING: 올린 파일에 이미 있던 한국어. */
    public enum Source { HUMAN, EXPORT, EXISTING }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String gameName;
    private String srcText;
    private String ko;
    @Enumerated(EnumType.STRING)
    private Source source;
    private Instant updatedAt = Instant.now();
}
