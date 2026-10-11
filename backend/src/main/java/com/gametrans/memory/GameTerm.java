package com.gametrans.memory;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 게임 단위 공용 용어집. 프로젝트에서 용어를 확정할 때마다 갱신된다. */
@Entity
@Table(name = "game_term")
@Getter @Setter @NoArgsConstructor
public class GameTerm {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String gameName;
    private String srcText;
    private String type;
    private String ko;
    private Instant updatedAt = Instant.now();
}
