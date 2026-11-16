package com.gametrans.rules;

import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RuleExclusionRepository extends JpaRepository<RuleExclusion, Long> {
    @Query("select x.segmentId from RuleExclusion x where x.ruleId = :ruleId")
    Set<Long> findSegmentIdsByRuleId(Long ruleId);
    List<RuleExclusion> findByRuleIdIn(List<Long> ruleIds);
    boolean existsByRuleIdAndSegmentId(Long ruleId, Long segmentId);
}
