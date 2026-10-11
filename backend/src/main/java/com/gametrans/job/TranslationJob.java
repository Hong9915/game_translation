package com.gametrans.job;

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

@Entity
@Table(name = "translation_job")
@Getter @Setter @NoArgsConstructor
public class TranslationJob {
    public enum Kind { TRANSLATE, SHORTEN, EXTRACT_TERMS, SUGGEST_CANDIDATES, FIX_TERMS, COLLAPSE_TERMS }
    public enum Status { RUNNING, STOPPED, DONE, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    /** 게임 단위 작업(용어집 정리)이면 projectId 대신 이것이 채워진다. */
    private Long gameId;
    @Enumerated(EnumType.STRING) private Kind kind;
    @Enumerated(EnumType.STRING) private Status status = Status.RUNNING;
    private int totalBatches;
    private int doneBatches;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;

    public boolean isTerminal() { return status != Status.RUNNING; }
}
