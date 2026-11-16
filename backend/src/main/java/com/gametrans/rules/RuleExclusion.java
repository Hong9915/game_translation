package com.gametrans.rules;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 미리 보기에서 체크를 끈 채 적용한 행 — 그 규칙의 남은 행·바꾼 비율·후보에서 빠진다(설계 §6). */
@Entity
@Table(name = "correction_rule_exclusion")
@Getter @Setter @NoArgsConstructor
public class RuleExclusion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ruleId;
    private Long segmentId;
    private Long decidedBy;
    private Instant decidedAt = Instant.now();
}
