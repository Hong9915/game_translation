package com.gametrans.normalize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "normalize_config")
@Getter @Setter @NoArgsConstructor
public class NormalizeConfig {
    public enum KoColMode { NEW, EXISTING, EXISTING_TO_NEW }

    /**
     * 원문 열 하나 → 한국어 열 하나. 한 시트에 이름·설명처럼 원문 열이 여러 개면 쌍도 여러 개다.
     * NEW: 기존 한국어를 읽지 않고 koHeader 이름의 새 열을 맨 끝에 붙인다.
     * EXISTING: koCol에서 읽고 같은 koCol에 쓴다.
     * EXISTING_TO_NEW: koCol에서 읽되 koHeader 이름의 새 열에 쓴다(원본 한국어 보존).
     * fixCol(최종 리뷰 버전, 2026-10-17): 이미 고친 한국어 열. 있으면 EXISTING_TO_NEW이고, 그 열을 Fix로 읽고
     * 내보낼 때도 새 열 대신 그 열에 쓴다. 예전 설정 JSON에는 없어 null로 읽힌다.
     */
    public record ColumnPair(String srcCol, String enCol, KoColMode koColMode, String koCol, String koHeader, String fixCol) {
        public ColumnPair(String srcCol, String enCol, KoColMode koColMode, String koCol, String koHeader) {
            this(srcCol, enCol, koColMode, koCol, koHeader, null);
        }
        /** JSON에는 넣지 않는다. 넣으면 Hibernate가 JSON 값을 복사하며 다시 읽을 때 모르는 속성이라 실패한다. */
        @JsonIgnore public boolean isNew() { return koColMode != KoColMode.EXISTING && fixCol == null; }
        @JsonIgnore public String headerOrDefault() { return koHeader == null || koHeader.isBlank() ? "KO" : koHeader; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 한 프로젝트에 시트마다 한 행. (projectId, sheetName)이 유일하다. */
    private Long projectId;
    private String sheetName;
    private int headerRow;
    private String keyCol;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ColumnPair> pairs = new ArrayList<>();
    /** true면 기존 한국어가 있는 행도 다시 번역한다(기존 문장을 초벌로 넣지 않음). */
    private boolean retranslateExisting = false;
    /** true면 기존 한국어를 초벌로 살리고, 번역 단계에서 GPT가 틀린 행만 골라 고친다. retranslateExisting보다 우선한다. */
    private boolean proofreadExisting = false;

    /** 열 짝 중 하나라도 수정 한국어 열(fixCol)이 있으면 최종 리뷰 시트. */
    public boolean isFinalReview() { return pairs.stream().anyMatch(p -> p.fixCol() != null); }
}
