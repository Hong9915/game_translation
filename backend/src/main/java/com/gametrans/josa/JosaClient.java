package com.gametrans.josa;

import java.util.Collection;
import java.util.List;

/** 조사 검사 보조 컨테이너(설계 2026-10-07 §2). 실패하면 RestClientException을 던진다. */
public interface JosaClient {
    /** url이 설정돼 있어야 검사한다. */
    boolean enabled();

    /** texts와 같은 순서로 문장마다 제안 목록. protectedWords = 단어장 한국어 표기의 한글 낱말(이름 등 거르기). */
    List<List<JosaHint>> check(List<String> texts, Collection<String> protectedWords);

    /** texts와 같은 순서로 Kiwi 띄어쓰기 교정 결과. 표기 갈림 띄어쓰기 카드의 표준 꼴에 쓴다(공백을 지운 표기를 넣는다). */
    List<String> space(List<String> texts);

    /** texts와 같은 순서로, 태그가 있던 경계에 Kiwi가 띄운 자리만 공백을 더한 꼴(2026-10-08). 바꿀 곳이 없으면 그대로. */
    List<String> tagSpace(List<String> texts);
}
