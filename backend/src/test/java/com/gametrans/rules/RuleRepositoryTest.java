package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.IntegrationTest;
import com.gametrans.TestProjects;
import com.gametrans.game.GameService;
import com.gametrans.normalize.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class RuleRepositoryTest extends IntegrationTest {
    @Autowired CorrectionRuleRepository rules;
    @Autowired RuleExclusionRepository exclusions;
    @Autowired StylePolicyRepository policies;
    @Autowired GameService games;
    @Autowired SegmentRepository segments;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private Long newGame() { return games.findOrCreate(TestProjects.freshGame()).getId(); }

    private CorrectionRule rule(Long gameId, Character kind) {
        CorrectionRule r = new CorrectionRule();
        r.setGameId(gameId); r.setType("WORD"); r.setOfText("엘리트"); r.setNfText("에픽"); r.setTextKind(kind);
        return r;
    }

    @Test
    void sameKeyTwiceIsRejected() {
        Long g = newGame();
        rules.saveAndFlush(rule(g, null));
        assertThatThrownBy(() -> rules.saveAndFlush(rule(g, null))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exclusionsReadBackPerRule() {
        CorrectionRule r = rules.saveAndFlush(rule(newGame(), 'E'));
        RuleExclusion x = new RuleExclusion(); x.setRuleId(r.getId()); x.setSegmentId(42L);
        exclusions.saveAndFlush(x);
        assertThat(exclusions.findSegmentIdsByRuleId(r.getId())).containsExactly(42L);
        assertThat(r.getStatus()).isEqualTo("SUGGESTED");
    }

    @Test
    void stylePolicyPerGameAndKind() {
        Long g = newGame();
        StylePolicy p = new StylePolicy(); p.setGameId(g); p.setTextKind('S'); p.setPolicy("KEEP");
        policies.saveAndFlush(p);
        assertThat(policies.findByGameId(g)).singleElement().satisfies(x -> assertThat(x.getPolicy()).isEqualTo("KEEP"));
    }

    @Test
    void segmentTextKindIsStored() throws Exception {
        long pid = TestProjects.upload(mvc);
        TestProjects.normalize(mvc, pid);
        Long sid = jdbc.queryForObject("select min(id) from segment where project_id = ?", Long.class, pid);
        jdbc.update("update segment set text_kind = 'E' where id = ?", sid);
        assertThat(segments.findById(sid).orElseThrow().getTextKind()).isEqualTo('E');
    }
}
