package com.gametrans.llm;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "llm_usage")
@Getter @Setter @NoArgsConstructor
public class LlmUsage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private Long jobId;
    private String stage;
    private String model;
    private int promptTokens;
    private int completionTokens;
    private int reasoningTokens;
    private Instant createdAt = Instant.now();
}
