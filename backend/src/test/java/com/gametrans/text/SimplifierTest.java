package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimplifierTest {

    private final Simplifier s = new Simplifier();

    @Test
    void convertsCharactersWithoutRegionalWordSwap() {
        assertThat(s.toSimplified("開啟")).isEqualTo("开启");
        assertThat(s.toSimplified("軟體")).isEqualTo("软体");
        assertThat(s.toSimplified("儲值")).isEqualTo("储值");
        assertThat(s.toSimplified("裡面")).isEqualTo("里面");
        assertThat(s.toSimplified("看著我們")).isEqualTo("看着我们");
        assertThat(s.toSimplified("騎士大人")).isEqualTo("骑士大人");
    }

    @Test
    void leavesOtherTextAlone() {
        String same = "充值 <color=#8f34>10</color>\n안녕 ABC";
        assertThat(s.toSimplified(same)).isEqualTo(same);
        assertThat(s.toSimplified(null)).isNull();
        assertThat(s.toSimplified("")).isEmpty();
    }

    @Test
    void leavesNonChineseTextAlone() {
        for (String t : new String[] {"안녕하세요, 용사님!", "Hello world", "<color=#fff>{0}</color>\\n", "{0}/{1}\n다음", "  "}) {
            assertThat(s.toSimplified(t)).isEqualTo(t);
            assertThat(s.hasTraditional(t)).isFalse();
        }
    }

    @Test
    void detectsTraditional() {
        assertThat(s.hasTraditional("騎士")).isTrue();
        assertThat(s.hasTraditional("骑士")).isFalse();
        assertThat(s.hasTraditional(null)).isFalse();
        assertThat(s.hasTraditional("")).isFalse();
    }

    @Test
    void dictionaryCommentLinesAreNotEntries() {
        String en = "stand by the key and spaces (values separated)";
        assertThat(s.toSimplified(en)).isEqualTo(en);
        assertThat(s.hasTraditional(en)).isFalse();
        // 사전 머리말 줄("# Format: key<TAB>value(s) ...")이 항목으로 읽히면 이 글이 바뀐다.
        String header = "# Format: key";
        assertThat(s.toSimplified(header)).isEqualTo(header);
        assertThat(s.hasTraditional(header)).isFalse();
    }

    /** 이미 간체인 글: 바뀌면 안 되고 번체로 잡혀도 안 된다(么→幺, 著→着 같은 오변환). */
    @ParameterizedTest
    @ValueSource(strings = {"什么", "为什么", "怎么办", "这么", "那么", "多么", "著名", "名著", "著作", "是么", "土著",
        "看著我", "为什么这么着急", "显著提升"})
    void simplifiedTextStaysByteIdentical(String text) {
        assertThat(s.toSimplified(text)).isEqualTo(text);
        assertThat(s.hasTraditional(text)).isFalse();
    }

    @Test
    void phraseVariantsWinOverSingleCharacterReverse() {
        // 1단계 TWVariantsRevPhrases(顯著→顯著)가 TWVariantsRev(著→着)보다 먼저 맞아야 한다.
        assertThat(s.toSimplified("顯著提升")).isEqualTo("显著提升");
        assertThat(s.hasTraditional("顯著提升")).isTrue();
    }

    @Test
    void mixedSentenceKeepsPhraseExceptions() {
        assertThat(s.toSimplified("這是什麼著名的地方")).isEqualTo("这是什么著名的地方");
        assertThat(s.toSimplified("我們看著他")).isEqualTo("我们看着他");
        assertThat(s.toSimplified("為什麼")).isEqualTo("为什么");
    }

    @Test
    void taiwanVariantStageReallyRuns() {
        // 鯰·顎는 TSCharacters만 쓰면 鲶·颚가 되지만 tw2s는 TWVariantsRev로 먼저 鮎·齶로 돌려 鲇·腭가 된다.
        assertThat(s.toSimplified("鯰")).isEqualTo("鲇");
        assertThat(s.toSimplified("顎")).isEqualTo("腭");
        assertThat(s.toSimplified("鯰魚")).isEqualTo("鲇鱼");
    }

    @Test
    void keepsNonBmpCodePointsIntact() {
        String s2 = "𠮷 😀";
        assertThat(s.toSimplified(s2)).isEqualTo(s2);
        assertThat(s.toSimplified("騎𠮷士😀")).isEqualTo("骑𠮷士😀");
    }
}
