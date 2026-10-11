package com.gametrans.term;

import com.gametrans.llm.LlmClient.Candidate;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "term")
@Getter @Setter @NoArgsConstructor
public class Term {
    /** COMMON: 고유명사는 아니지만 게임 전체에서 같은 말로 옮겨야 하는 반복 어휘(상점, 패키지, 충전, 재화 이름 등). */
    public enum Type { ITEM, CHARACTER, SKILL, PLACE, COMMON, OTHER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private String srcText;
    @Enumerated(EnumType.STRING)
    private Type type = Type.OTHER;
    private int occurrences;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Candidate> candidates = new ArrayList<>();
    private String confirmedKo;
    private boolean confirmed;
}
