package com.gametrans.project;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.gametrans.game.Game;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "project")
@Getter @Setter @NoArgsConstructor
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String fileName;
    @ManyToOne(fetch = FetchType.EAGER, optional = false) @JoinColumn(name = "game_id")
    private Game game;
    private String storagePath;
    private int sheetCount;
    private int rowCount;
    private Instant uploadedAt = Instant.now();
    private int currentStep = 1;
    /** 검수 전용: 한국어 열이 이미 있는 파일. 정규화 → 검수 → 내보내기만 거치고 용어·번역 단계는 건너뛴다. */
    private boolean reviewOnly = false;

    /** 공용 용어집·번역 메모리가 묶이는 키. */
    public String getGameName() { return game.getName(); }
}
