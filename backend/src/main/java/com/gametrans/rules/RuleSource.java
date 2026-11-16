package com.gametrans.rules;

import com.gametrans.rules.RuleLearner.Edit;
import com.gametrans.rules.RuleLearner.Row;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 학습 재료를 읽는다(설계 2026-11-13 §1). 예시 = EDIT 활동의 주 행(detail.segmentId)의 HUMAN 리비전, 직전 판과 다를 때.
 * 같은 원문 복사본(같은 활동의 다른 행)은 빠진다. 게임 행은 latest_ko만 — 리비전 이력은 손 수정 쌍에만 쓴다.
 */
@Component
public class RuleSource {
    private final JdbcTemplate jdbc;

    public RuleSource(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Edit> edits(Long gameId) {
        return jdbc.query("""
                select r.segment_id, s.src_text, prev.text as before, r.text as after, r.created_at
                  from translation_revision r
                  join activity a on a.id = r.activity_id and a.kind = 'EDIT'
                                 and (a.detail->>'segmentId')::bigint = r.segment_id
                  join segment s on s.id = r.segment_id
                  join project p on p.id = s.project_id
                  join lateral (select q.text from translation_revision q
                                 where q.segment_id = r.segment_id and q.version < r.version
                                 order by q.version desc limit 1) prev on true
                 where p.game_id = ? and r.author = 'HUMAN' and prev.text is distinct from r.text
                 order by r.created_at
                """, (rs, i) -> new Edit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()), gameId);
    }

    public List<Row> rows(Long gameId) {
        return jdbc.query("""
                select s.id, s.project_id, s.src_text, coalesce(s.latest_ko, ''), s.text_kind
                  from segment s join project p on p.id = s.project_id
                 where p.game_id = ? and s.skipped = false and s.confirmed = false
                """, (rs, i) -> {
            String k = rs.getString(5);
            return new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), k == null || k.isEmpty() ? null : k.charAt(0));
        }, gameId);
    }
}
