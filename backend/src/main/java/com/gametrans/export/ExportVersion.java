package com.gametrans.export;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 내보내기 한 번 = 수정본 한 버전. 1차, 2차 … 순서로 쌓이고 파일은 스토리지에 남는다. */
@Entity
@Table(name = "export_version")
@Getter @Setter @NoArgsConstructor
public class ExportVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private int version;
    private String fileName;
    private String storagePath;
    private int filledRows;
    private int flaggedRows;
    private Instant createdAt = Instant.now();
}
