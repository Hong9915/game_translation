package com.gametrans.rules;

import com.gametrans.common.NotFoundException;
import com.gametrans.rules.RuleDtos.RuleDto;
import com.gametrans.rules.RuleDtos.RuleList;
import com.gametrans.rules.RuleLearner.Edit;
import com.gametrans.rules.RuleLearner.Learned;
import com.gametrans.rules.RuleLearner.RuleKey;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 다시 배우기·규칙 목록·무시·게임 문체 기준(설계 2026-11-13 §5·§7). 결정은 규칙 키로 유지된다. */
@Service
public class CorrectionRuleService {
    private final CorrectionRuleRepository rules;
    private final RuleExclusionRepository exclusions;
    private final StylePolicyRepository policies;
    private final RuleSource source;
    private final TextKindService kinds;
    private final RuleLearner learner = new RuleLearner();

    public CorrectionRuleService(CorrectionRuleRepository rules, RuleExclusionRepository exclusions,
                                 StylePolicyRepository policies, RuleSource source, TextKindService kinds) {
        this.rules = rules; this.exclusions = exclusions; this.policies = policies; this.source = source; this.kinds = kinds;
    }

    static RuleKey key(CorrectionRule r) { return new RuleKey(r.getType(), r.getOfText(), r.getNfText(), r.getTextKind()); }

    /** 지금 규칙의 뺀 행(규칙 키 → 행 id). */
    Map<RuleKey, Set<Long>> exclusionsOf(List<CorrectionRule> existing) {
        Map<Long, RuleKey> byId = new HashMap<>();
        for (CorrectionRule r : existing) byId.put(r.getId(), key(r));
        Map<RuleKey, Set<Long>> out = new HashMap<>();
        if (byId.isEmpty()) return out;
        for (RuleExclusion x : exclusions.findByRuleIdIn(new ArrayList<>(byId.keySet())))
            out.computeIfAbsent(byId.get(x.getRuleId()), k -> new HashSet<>()).add(x.getSegmentId());
        return out;
    }

    Map<Character, String> policiesOf(Long gameId) {
        Map<Character, String> out = new HashMap<>();
        for (StylePolicy p : policies.findByGameId(gameId)) out.put(p.getTextKind(), p.getPolicy());
        return out;
    }

    @Transactional
    public RuleList relearn(Long gameId) {
        List<Edit> edits = source.edits(gameId);
        List<CorrectionRule> existing = rules.findByGameIdOrderByRatioDesc(gameId);
        List<Learned> learned = learner.learn(edits, source.rows(gameId), policiesOf(gameId), exclusionsOf(existing),
                RuleLearner.dropHaPreferred(edits));
        Map<RuleKey, CorrectionRule> byKey = new HashMap<>();
        for (CorrectionRule r : existing) byKey.put(key(r), r);
        Instant now = Instant.now();
        Set<RuleKey> seen = new HashSet<>();
        for (Learned l : learned) {
            seen.add(l.key());
            CorrectionRule r = byKey.computeIfAbsent(l.key(), k -> {
                CorrectionRule n = new CorrectionRule();
                n.setGameId(gameId); n.setType(k.type()); n.setOfText(k.of()); n.setNfText(k.nf()); n.setTextKind(k.textKind());
                return n;
            });
            r.setAnchor(l.anchors().isEmpty() ? null : String.join(",", l.anchors()));
            r.setShapes(String.join(",", l.shapes()));
            r.setStyles(String.join(",", l.styles()));
            r.setFixedCount(l.fixed()); r.setLeftCount(l.left()); r.setCandidateCount(l.candidates().size());
            r.setRatio(l.ratio()); r.setSameAsHuman(l.sameAsHuman()); r.setLastEditAt(l.lastEdit()); r.setFlipped(l.flipped());
            r.setVerdict(l.verdict()); r.setConverted(l.converted()); r.setKept(l.kept());
            r.setLearnedAt(now);
            rules.save(r);
        }
        for (CorrectionRule r : existing) {
            if ("WORD".equals(r.getType())) { rules.delete(r); continue; }   // 낱말 규칙은 뺐다(2026-11-13)
            if (r.getTextKind() != null && RuleLearner.EXCLUDED_KINDS.indexOf(r.getTextKind()) >= 0) { rules.delete(r); continue; }   // 대사·공지는 늘 뺀다
            if (seen.contains(key(r))) continue;
            if ("SUGGESTED".equals(r.getStatus())) rules.delete(r);
            else { r.setLeftCount(0); r.setCandidateCount(0); r.setLearnedAt(now); rules.save(r); }
        }
        rules.flush();
        return list(gameId);
    }

    @Transactional(readOnly = true)
    public RuleList list(Long gameId) {
        List<CorrectionRule> rs = rules.findByGameIdOrderByRatioDesc(gameId);
        Instant learnedAt = rs.stream().map(CorrectionRule::getLearnedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        int unjudged = kinds.countTargets(gameId);
        return new RuleList(learnedAt, unjudged, kinds.estimateUsd(unjudged), rs.stream().map(CorrectionRuleService::dto).toList());
    }

    static RuleDto dto(CorrectionRule r) {
        List<String> anchors = r.getAnchor() == null ? List.of() : List.of(r.getAnchor().split(","));
        return new RuleDto(r.getId(), r.getType(), r.getOfText(), r.getNfText(), r.getTextKind(),
                anchors.isEmpty() ? null : anchors.get(0), anchors, r.getFixedCount(), r.getLeftCount(), r.getCandidateCount(),
                r.getRatio(), r.getSameAsHuman(), r.isFlipped(), r.getStatus(), r.getVerdict(), r.getConverted(), r.getKept(),
                r.getAppliedCount(), r.getLastEditAt());
    }

    @Transactional
    public RuleDto setIgnored(Long ruleId, boolean ignored, Long reviewerId) {
        CorrectionRule r = rules.findById(ruleId).orElseThrow(() -> new NotFoundException("규칙을 찾을 수 없습니다: " + ruleId));
        r.setStatus(ignored ? "IGNORED" : r.getAppliedCount() > 0 ? "APPROVED" : "SUGGESTED");
        r.setDecidedBy(reviewerId); r.setDecidedAt(Instant.now());
        return dto(rules.save(r));
    }

    @Transactional
    public RuleList setPolicy(Long gameId, Character textKind, String policy, Long reviewerId) {
        if (!"CONVERT".equals(policy) && !"KEEP".equals(policy)) throw new IllegalArgumentException("policy는 CONVERT 또는 KEEP: " + policy);
        StylePolicy p = new StylePolicy();
        p.setGameId(gameId); p.setTextKind(textKind); p.setPolicy(policy); p.setDecidedBy(reviewerId); p.setDecidedAt(Instant.now());
        policies.save(p);
        return relearn(gameId);
    }
}
