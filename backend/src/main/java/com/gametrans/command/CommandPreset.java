package com.gametrans.command;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 저장 명령 하나(2026-10-08). */
@Entity
@Table(name = "command_preset")
@Getter @Setter @NoArgsConstructor
public class CommandPreset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long gameId;
    private String name;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<OpSpec> ops = new ArrayList<>();
    private Long createdBy;
    private Instant createdAt = Instant.now();
}
