package com.gametrans.connector;

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

/** Claude가 제안한 고침 묶음. status는 PENDING·APPLIED·DISMISSED만 저장하고, 만료(EXPIRED)는 조회 때 계산한다. */
@Entity
@Table(name = "connector_proposal")
@Getter @Setter @NoArgsConstructor
public class ConnectorProposal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long reviewerId;
    private Long gameId;
    private String title;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ConnectorDtos.ProposalItem> items = new ArrayList<>();
    private String status = "PENDING";
    @JdbcTypeCode(SqlTypes.JSON)
    private ConnectorDtos.ProposalResult result;
    private Instant createdAt = Instant.now();
    private Instant decidedAt;
    private String termSrc;
    private String termKo;
    private String termOld;
}
