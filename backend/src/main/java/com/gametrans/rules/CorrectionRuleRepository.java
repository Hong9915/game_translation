package com.gametrans.rules;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorrectionRuleRepository extends JpaRepository<CorrectionRule, Long> {
    List<CorrectionRule> findByGameIdOrderByRatioDesc(Long gameId);
    Optional<CorrectionRule> findByGameIdAndTypeAndOfTextAndNfTextAndTextKind(Long gameId, String type, String ofText, String nfText, Character textKind);
}
