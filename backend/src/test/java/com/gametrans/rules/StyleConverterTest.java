package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.gametrans.rules.StyleConverter.Converted;
import org.junit.jupiter.api.Test;

class StyleConverterTest {
    private static Converted c(String s) { return StyleConverter.convert(s, true); }

    @Test void dropsHaKeepsPeriod() { assertThat(c("공격력이 10% 증가합니다.")).isEqualTo(new Converted("공격력이 10% 증가.", true)); }
    @Test void pastHaeBecomesHam() { assertThat(c("보상을 받지 못했습니다").text()).isEqualTo("보상을 받지 못함"); }
    @Test void keepsTags() { assertThat(c("<color=#d2730f>2턴</color> 동안 지속됩니다").text()).isEqualTo("<color=#d2730f>2턴</color> 동안 지속됨"); }
    @Test void canBecomesGaneung() { assertThat(c("상점에서 획득할 수 있습니다").text()).isEqualTo("상점에서 획득 가능"); }
    @Test void bieupToMieum() { assertThat(c("피해를 줍니다").text()).isEqualTo("피해를 줌"); }
    @Test void questionUntouched() { assertThat(c("계속하시겠습니까?")).isEqualTo(new Converted("계속하시겠습니까?", true)); }
    @Test void imperativeIncomplete() { assertThat(c("버튼을 누르십시오")).isEqualTo(new Converted("버튼을 누르십시오", false)); }
    @Test void keepHaWhenGamePrefers() { assertThat(StyleConverter.convert("증가합니다", false).text()).isEqualTo("증가함"); }
    // ② ~했습니다/~합니다 → 명사만 (사람이 1,583번)
    @Test void pastNounDropsEnding() { assertThat(c("목표에 도달했습니다.").text()).isEqualTo("목표에 도달."); }
    // ③ ~하세요/~하십시오 → 명사만
    @Test void imperativeHaseyoToNoun() { assertThat(c("버튼을 선택하세요")).isEqualTo(new Converted("버튼을 선택", true)); }
    @Test void imperativeHasipsioToNoun() { assertThat(c("아이템을 사용하십시오")).isEqualTo(new Converted("아이템을 사용", true)); }
    @Test void pleaseLeftAlone() { assertThat(c("잠시 후 다시 시도해 주세요")).isEqualTo(new Converted("잠시 후 다시 시도해 주세요", true)); }
    // ④ ~한다/~된다/있다/없다 → 개조식
    @Test void handaToNoun() { assertThat(c("3턴 동안 지속된다. 피해를 흡수한다").text()).isEqualTo("3턴 동안 지속. 피해를 흡수"); }
    @Test void itdaToEum() { assertThat(c("효과가 없다").text()).isEqualTo("효과가 없음"); }
}
