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

/** 게임 하나. 파일(프로젝트)들이 여기에 속하고, 번역 메모리는 이름으로 묶인다. */
@Entity
@Table(name = "game")
@Getter @Setter @NoArgsConstructor
public class Game {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    /** DeepL 지시문(custom_instructions). 한 줄이 지시 하나. 비면 보내지 않는다. 새 게임은 기본 지시문으로 시작한다. */
    private String deeplInstructions = DeepLInstructions.DEFAULT;
    /** GPT 번역·다듬기 지시문에 덧붙이는 글. 기본 지시문은 GptInstructions에 있고, 비면 덧붙임 없음. */
    private String gptTranslateExtra;
    private String gptPolishExtra;
    private Instant createdAt = Instant.now();
}
