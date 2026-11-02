package com.gametrans.deepl;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.deepl.DeepLTags.Mode;
import com.gametrans.deepl.DeepLTags.Prepared;
import org.junit.jupiter.api.Test;

class DeepLTagsTest {
    @Test
    void smartTakesNumericColorSpansOutAndPutsThemBackAroundTheNumber() {
        Prepared p = DeepLTags.prepare("伤害提升至<color=c95050>100%</color>", Mode.SMART);
        assertThat(p.xml()).isEqualTo("伤害提升至100%");
        assertThat(DeepLTags.restore("피해가 100%로 증가", p)).isEqualTo("피해가 <color=c95050>100%</color>로 증가");
    }

    @Test
    void sameNumberTwiceIsWrappedInOrder() {
        Prepared p = DeepLTags.prepare("<color=a>5</color>到<color=b>5</color>", Mode.SMART);
        assertThat(DeepLTags.restore("5에서 5로", p)).isEqualTo("<color=a>5</color>에서 <color=b>5</color>로");
    }

    @Test
    void restoreFailsWhenTheNumberIsGone() {
        Prepared p = DeepLTags.prepare("伤害提升至<color=c95050>100%</color>", Mode.SMART);
        assertThat(DeepLTags.restore("피해 대폭 증가", p)).isNull();
    }

    @Test
    void placeholdersAndBothKindsOfNewlineBecomeMarkers() {
        Prepared p = DeepLTags.prepare("持续{0}秒\\n冷却\n结束<br/>", Mode.SMART);
        assertThat(p.xml()).isEqualTo("持续<x i=\"0\"/>秒<x i=\"1\"/>冷却<x i=\"2\"/>结束<x i=\"3\"/>");
        assertThat(DeepLTags.restore("<x i=\"0\"/>초 지속<x i=\"1\"/>쿨타임<x i=\"2\"/>끝<x i=\"3\"/>", p))
                .isEqualTo("{0}초 지속\\n쿨타임\n끝<br/>");
    }

    @Test
    void colorSpansWithChineseAreSentAsPairedElements() {
        Prepared p = DeepLTags.prepare("重建<color=#a5ff86>家园</color>吧", Mode.SMART);
        assertThat(p.xml()).isEqualTo("重建<c i=\"0\">家园</c>吧");
        assertThat(DeepLTags.restore("<c i=\"0\">고향</c>을 재건하자", p)).isEqualTo("<color=#a5ff86>고향</color>을 재건하자");
    }

    @Test
    void unbalancedColorTagsAreSentAsPlainMarkers() {
        Prepared p = DeepLTags.prepare("<color=#fff>开始", Mode.SMART);
        assertThat(p.xml()).isEqualTo("<x i=\"0\"/>开始");
        assertThat(DeepLTags.restore("<x i=\"0\"/>시작", p)).isEqualTo("<color=#fff>시작");
    }

    @Test
    void escapesStrayAngleBracketsAndAmpersands() {
        Prepared p = DeepLTags.prepare("等级<5 & 攻击>3", Mode.SMART);
        assertThat(p.xml()).isEqualTo("等级&lt;5 &amp; 攻击&gt;3");
        assertThat(DeepLTags.restore("레벨 &lt;5 &amp; 공격 &gt;3", p)).isEqualTo("레벨 <5 & 공격 >3");
    }

    @Test
    void wrapAllHidesTheWholeNumericSpanBehindOneMarker() {
        Prepared p = DeepLTags.prepare("提升至<color=438800>3</color>次", Mode.WRAP_ALL);
        assertThat(p.xml()).isEqualTo("提升至<x i=\"0\"/>次");
        assertThat(DeepLTags.restore("<x i=\"0\"/>회로 증가", p)).isEqualTo("<color=438800>3</color>회로 증가");
    }

    @Test
    void restoreFailsOnAnUnknownMarker() {
        Prepared p = DeepLTags.prepare("持续{0}秒", Mode.SMART);
        assertThat(DeepLTags.restore("<x i=\"7\"/>초", p)).isNull();
    }

    @Test
    void cleanNeedsTheSameTagsInOrderAndNoChineseLeft() {
        String src = "<color=a>A</color>{0}";
        assertThat(DeepLTags.clean(src, "<color=a>가</color>{0}")).isTrue();
        assertThat(DeepLTags.clean(src, "{0}<color=a>가</color>")).isFalse();
        assertThat(DeepLTags.clean(src, "<color=a>扇형</color>{0}")).isFalse();
        assertThat(DeepLTags.tagsMatch(src, "<color=a>扇형</color>{0}")).isTrue();
        assertThat(DeepLTags.clean("a\\nb", "가\n나")).isFalse();
    }
}
