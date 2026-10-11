package com.gametrans.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JosaTest {
    @Test
    void movesParticleToNewBatchim() {
        assertThat(Josa.replaceAdjusting("아이스필드가 열림", "아이스필드", "설원")).isEqualTo("설원이 열림");
        assertThat(Josa.replaceAdjusting("펫과 함께", "펫", "영수")).isEqualTo("영수와 함께");
        assertThat(Josa.replaceAdjusting("통솔자를 처치", "통솔자", "지배자")).isEqualTo("지배자를 처치");
        assertThat(Josa.replaceAdjusting("매는 날아간다", "매", "독수리")).isEqualTo("독수리는 날아간다");
        assertThat(Josa.replaceAdjusting("면역을 부여", "면역", "피해 감소")).isEqualTo("피해 감소를 부여");
    }

    @Test
    void euroAndRieulBatchim() {
        assertThat(Josa.replaceAdjusting("제련으로 획득", "제련", "재련")).isEqualTo("재련으로 획득");
        assertThat(Josa.replaceAdjusting("상점으로 이동", "상점", "가게")).isEqualTo("가게로 이동");
        assertThat(Josa.replaceAdjusting("가게로 이동", "가게", "마을")).isEqualTo("마을로 이동");      // ㄹ받침 뒤 로
        assertThat(Josa.replaceAdjusting("가게로 이동", "가게", "상점")).isEqualTo("상점으로 이동");
    }

    @Test
    void leavesNonParticlesAlone() {
        assertThat(Josa.replaceAdjusting("아이스필드이나 사막", "아이스필드", "설원")).isEqualTo("설원이나 사막");   // 조사 다음이 한글
        assertThat(Josa.replaceAdjusting("BOSS를 처치", "BOSS", "보스")).isEqualTo("보스를 처치");             // 옛 표기가 한글로 안 끝남
        assertThat(Josa.replaceAdjusting("보스를 처치", "보스", "BOSS")).isEqualTo("BOSS를 처치");             // 새 표기가 한글로 안 끝남
        assertThat(Josa.replaceAdjusting("면역를 부여", "면역", "보호")).isEqualTo("보호를 부여");               // 원래 틀린 조사는 옮기지 않는다
    }

    @Test
    void everyOccurrenceAndTagsAfter() {
        assertThat(Josa.replaceAdjusting("펫과 펫이 <color=1>펫</color>을", "펫", "영수"))
                .isEqualTo("영수와 영수가 <color=1>영수</color>을");   // 태그 뒤 조사는 치환 자리 바로 뒤가 아니다
        assertThat(Josa.replaceAdjusting("펫, 펫.", "펫", "영수")).isEqualTo("영수, 영수.");
    }

    @Test
    void sameAsPlainReplaceWhenNoParticle() {
        assertThat(Josa.replaceAdjusting("심판의 심판", "심판", "심판관")).isEqualTo("심판관의 심판관");
        assertThat(Josa.replaceAdjusting("없음", "있", "없")).isEqualTo("없음");
    }
}
