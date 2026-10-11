package com.gametrans.josa;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 조사 제안 하나(설계 2026-10-07 §1). at = latest_ko에서 from이 시작하는 Java 문자열 인덱스. kind = HANGUL·NUMBER·TAG.
 * dictNoun은 보조 컨테이너가 붙여 보내는 거르기 표시라 저장하지 않는다(stored()).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JosaHint(int at, String from, String to, String kind, Boolean dictNoun) {
    public JosaHint stored() { return new JosaHint(at, from, to, kind, null); }
}
