package com.gametrans.josa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JosaHintsTest {
    static final JosaHint LIFE = new JosaHint(4, "생명를", "생명을", "HANGUL", null);
    static final JosaHint IMMUNE = new JosaHint(11, "면역가", "면역이", "HANGUL", null);

    @Test
    void roundTripDropsNulls() {
        String json = JosaHints.format(List.of(new JosaHint(4, "생명를", "생명을", "HANGUL", true).stored()));
        assertThat(json).doesNotContain("dictNoun");
        assertThat(JosaHints.parse(json)).containsExactly(LIFE);
        assertThat(JosaHints.parse(null)).isEmpty();
    }

    @Test
    void appliesRightToLeftOnlyChosenKinds() {
        String text = "아군의 생명를 주고 면역가 오름";
        assertThat(JosaHints.applyTo(text, List.of(LIFE, IMMUNE), Set.of("HANGUL"))).isEqualTo("아군의 생명을 주고 면역이 오름");
        assertThat(JosaHints.applyTo(text, List.of(LIFE, IMMUNE), Set.of("NUMBER"))).isEqualTo(text);
    }

    @Test
    void skipsHintThatNoLongerMatches() {
        assertThat(JosaHints.applyTo("다른 문장", List.of(LIFE), Set.of("HANGUL"))).isEqualTo("다른 문장");
    }

    @Test
    void labelShowsVisibleContextForMarkedKinds() {
        String text = "횟수가 <color=438800>3</color>로 증가";
        JosaHint h = new JosaHint(text.indexOf("로"), "로", "으로", "NUMBER", null);
        assertThat(JosaHints.label(text, h)).isEqualTo("3로 → 3으로");
        assertThat(JosaHints.label("아군의 생명를 회복", LIFE)).isEqualTo("생명를 → 생명을");
    }
}
