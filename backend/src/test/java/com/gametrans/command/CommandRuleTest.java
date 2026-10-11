package com.gametrans.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gametrans.llm.LlmClient.CommandFilter;
import com.gametrans.llm.LlmClient.CommandStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 명령으로 고치기(2026-10-08)의 규칙: 조건에 드는 행에 단계들을 차례로 돌리되 태그·자리표시자는 그대로 둔다(그대로 옮기는 찾음만 그 위에 걸친다). */
class CommandRuleTest {
    /** Prompts.COMMAND_SYSTEM이 예로 드는 대시 규칙. */
    static final String DASH = "(?<=\\S)(?<!\\\\n)(?<!<br>)[ \\t]*([-–—]+)(?![0-9])[ \\t]*(?=\\S)";

    static CommandRule regex(String pattern, String replacement) {
        return CommandRule.compile(null, List.of(new CommandStep(pattern, replacement, true)));
    }

    static CommandRule literal(String find, String replace) {
        return CommandRule.compile(null, List.of(new CommandStep(find, replace, false)));
    }

    @Test
    void removesSpacesAroundDashesButKeepsListMarkersAndMinusSigns() {
        CommandRule dash = regex(DASH, "$1");
        assertThat(dash.apply("공격력 - 10")).isEqualTo("공격력-10");
        assertThat(dash.apply("A – B — C")).isEqualTo("A–B—C");
        assertThat(dash.apply("- 항목")).isEqualTo("- 항목");
        assertThat(dash.apply("제목\\n- 항목<br>- 둘\n- 셋")).isEqualTo("제목\\n- 항목<br>- 둘\n- 셋");
        assertThat(dash.apply("A -- B")).isEqualTo("A--B");
        assertThat(dash.apply("대상에게 -4% 피해감소, 공격력 - 10")).isEqualTo("대상에게 -4% 피해감소, 공격력-10");
    }

    @Test
    void literalStepsSwapTextAsIs() {
        assertThat(literal(":", " ·").apply("매혹의 구미호: 유화린")).isEqualTo("매혹의 구미호 · 유화린");
        assertThat(literal("$1.", "\\").apply("값 $1.")).isEqualTo("값 \\");   // 정규식 기호를 그대로 본다
    }

    @Test
    void stepsRunInOrder() {
        CommandRule rule = CommandRule.compile(null, List.of(new CommandStep("...", "…", false), new CommandStep("[ \\t]+…", "…", true)));
        assertThat(rule.apply("저건 ... 그럴 리가...")).isEqualTo("저건… 그럴 리가…");
    }

    @Test
    void filterPicksRowsByTranslationOrSource() {
        CommandRule ko = CommandRule.compile(new CommandFilter("ko", "구미호"), List.of(new CommandStep(":", "·", false)));
        assertThat(ko.matches("九尾狐", "매혹의 구미호: 유화린")).isTrue();
        assertThat(ko.matches("九尾狐", "늑대: 유화린")).isFalse();
        CommandRule src = CommandRule.compile(new CommandFilter("src", "狐"), List.of(new CommandStep(":", "·", false)));
        assertThat(src.matches("九尾狐", "늑대")).isTrue();
        assertThat(literal(":", "·").matches("x", "y")).isTrue();
    }

    @Test
    void leavesTagsAndPlaceholdersAlone() {
        assertThat(literal(" ", "").apply("<color=#ff0000 a> 빨강 </color> {0 } 끝")).isEqualTo("<color=#ff0000 a>빨강</color>{0 }끝");
        assertThat(regex(DASH, "$1").apply("<size = -2>A</size> - B")).isEqualTo("<size = -2>A</size>-B");
    }

    @Test
    void carriesTagsThroughWhenKeptInOrder() {
        // 숫자만 다른 문장 묶음: 태그와 숫자를 그룹으로 잡아 그대로 옮기면 태그에 걸쳐 찾아도 바꾼다
        CommandRule rule = regex("피해 계수가(<color=[^>]*>)([0-9.]+%)(</color>)로 증가했습니다", "피해 계수 $1$2$3 증가");
        assertThat(rule.apply("피해 계수가<color=#ff0000>75%</color>로 증가했습니다")).isEqualTo("피해 계수 <color=#ff0000>75%</color> 증가");
    }

    @Test
    void skipsMatchesThatDropReorderOrEditTokens() {
        assertThat(regex("<color=[^>]*>", "").apply("<color=#ff0000>a</color>")).isEqualTo("<color=#ff0000>a</color>");
        assertThat(regex("(<b>)(<i>)", "$2$1").apply("<b><i>x</i></b>")).isEqualTo("<b><i>x</i></b>");
        assertThat(regex("#ff0000", "#00ff00").apply("<color=#ff0000>a</color>")).isEqualTo("<color=#ff0000>a</color>");
        assertThat(regex("\\{0\\}점", "점").apply("{0}점")).isEqualTo("{0}점");
    }

    @Test
    void literalStepThatKeepsTheTagIsAllowed() {
        assertThat(literal("</color>로", "</color>으로").apply("<color=red>3</color>로")).isEqualTo("<color=red>3</color>으로");
    }

    @Test
    void undoesDoubleEscapedCharacterClasses() {
        // 모델이 \\S처럼 한 번 더 이스케이프하면 "역슬래시+S"를 찾게 된다. 번역문에 그런 글자는 없으니 \S로 푼다. \\n(글자 그대로의 줄바꿈 표시)은 둔다.
        CommandRule over = regex("(?<=\\\\S)(?<!\\\\n)[ \\t]*([-–—]+)[ \\t]*(?=\\\\S)", "$1");
        assertThat(over.apply("일등선비 - 황금꽃\\n- 항목")).isEqualTo("일등선비-황금꽃\\n- 항목");
        assertThat(regex("\\\\d+ %", "%").apply("10 % 증가")).isEqualTo("% 증가");
    }

    @Test
    void acceptsBackslashGroupReferences() {
        assertThat(regex("(\\d+) %", "\\1%").apply("10 % 증가")).isEqualTo("10% 증가");
    }

    @Test
    void rejectsBadRules() {
        assertThatThrownBy(() -> regex("(", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("정규식이 올바르지 않습니다");
        assertThatThrownBy(() -> regex("", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> literal("", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandRule.compile(null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> regex("a", "$2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("바꿀 꼴이 올바르지 않습니다");
    }

    @Test
    void stopsRunawayPatterns() {
        CommandRule evil = regex("(a+)+\\1b", "x");   // 역참조라 Java의 되풀이 기억 최적화가 듣지 않는다
        assertThatThrownBy(() -> evil.apply("a".repeat(40) + "c")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("정규식이 너무 오래 걸립니다");
    }
}
