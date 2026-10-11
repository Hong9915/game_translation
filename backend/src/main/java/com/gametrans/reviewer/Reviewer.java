package com.gametrans.reviewer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 공동 검토자. 로그인 없이 이름만으로 식별한다(사내 도구, 보안 경계 아님). */
@Entity
@Table(name = "reviewer")
@Getter @Setter @NoArgsConstructor
public class Reviewer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String color;
    private Instant createdAt = Instant.now();
}
