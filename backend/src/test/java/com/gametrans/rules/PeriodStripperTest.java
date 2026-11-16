package com.gametrans.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeriodStripperTest {
    @Test
    void stripsPeriodBeforeLineBreaksAndAtEnd() {
        assertThat(PeriodStripper.strip("공격력 증가.")).isEqualTo("공격력 증가");
        assertThat(PeriodStripper.strip("오픈됩니다.<br>2. 절세 큐브.")).isEqualTo("오픈됩니다<br>2. 절세 큐브");
        assertThat(PeriodStripper.strip("가.<br/>나.<BR />다.")).isEqualTo("가<br/>나<BR />다");
        assertThat(PeriodStripper.strip("가.\n나.")).isEqualTo("가\n나");
        assertThat(PeriodStripper.strip("가.\\n나.")).isEqualTo("가\\n나");
        assertThat(PeriodStripper.strip("가. \n나.  ")).isEqualTo("가 \n나  ");
    }

    @Test
    void stripsAcrossClosingTags() {
        assertThat(PeriodStripper.strip("<color=#139057>획득</color>.<br>다음")).isEqualTo("<color=#139057>획득</color><br>다음");
        assertThat(PeriodStripper.strip("<color=#139057>획득.</color>")).isEqualTo("<color=#139057>획득</color>");
    }

    @Test
    void keepsMidSentenceAndEllipsis() {
        assertThat(PeriodStripper.strip("샤워하려고요. 제 복근 보세요.")).isEqualTo("샤워하려고요. 제 복근 보세요");
        assertThat(PeriodStripper.strip("1. 서버 오픈 후 3.5배")).isEqualTo("1. 서버 오픈 후 3.5배");
        assertThat(PeriodStripper.strip("그런데...")).isEqualTo("그런데...");
        assertThat(PeriodStripper.strip("그런데..<br>음")).isEqualTo("그런데..<br>음");
        assertThat(PeriodStripper.strip("그런데….")).isEqualTo("그런데….");
        assertThat(PeriodStripper.strip("끝。")).isEqualTo("끝。");
        assertThat(PeriodStripper.candidate("마침표 없음")).isFalse();
        assertThat(PeriodStripper.candidate("마침표 있음.")).isTrue();
        assertThat(PeriodStripper.candidate("")).isFalse();
    }
}
