package com.gametrans.rules;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 게임 × 글 종류의 문체 기준(설계 §4 "정해 주세요"). CONVERT = 개조식으로, KEEP = 그대로 두기. */
@Entity
@Table(name = "style_policy")
@IdClass(StylePolicy.Key.class)
@Getter @Setter @NoArgsConstructor
public class StylePolicy {
    @Id private Long gameId;
    @Id @Column(columnDefinition = "char(1)") private Character textKind;
    private String policy;
    private Long decidedBy;
    private Instant decidedAt = Instant.now();

    @Getter @Setter @NoArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long gameId;
        private Character textKind;
    }
}
