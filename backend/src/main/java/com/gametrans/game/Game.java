package com.gametrans.game;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 게임 하나. 파일(프로젝트)들이 여기에 속하고, 공용 용어집·번역 메모리는 이름으로 묶인다. */
@Entity
@Table(name = "game")
@Getter @Setter @NoArgsConstructor
public class Game {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Instant createdAt = Instant.now();
}
