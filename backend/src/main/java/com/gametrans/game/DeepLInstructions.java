package com.gametrans.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 게임별 DeepL 지시문(custom_instructions, 2026-10-26). DeepL은 줄 하나를 지시 하나로 받고 최대 10개, 하나에 300자까지 받는다.
 * 2026-10-26 실험: 지시문을 넣으면 헬멧→투구, 배낭→가방처럼 게임 용어로, 문장도 UI처럼 짧게 나온다. 낱말 하나를 꼭 맞추려면 단어장이 더 확실하다.
 */
public final class DeepLInstructions {
    private DeepLInstructions() {}

    public static final int MAX_LINES = 10;
    public static final int MAX_CHARS = 300;

    /** V29가 기존 게임에 넣은 것과 같은 기본 지시문. */
    public static final String DEFAULT = String.join("\n",
            "모바일 RPG 게임의 한국어 현지화 문구로 번역한다. 게이머에게 익숙한 게임 용어를 쓴다.",
            "UI 문구처럼 짧고 간결하게 쓴다. 군더더기 수식어와 불필요한 조사는 줄인다.",
            "설명·알림 문장은 '~합니다'체로, 버튼·항목·아이템 이름은 명사형으로 끝낸다.");

    /** 빈 줄을 빼고 앞뒤 공백을 다듬은 지시 목록. 저장된 글이 없으면 빈 목록. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) if (!line.isBlank()) out.add(line.strip());
        return out;
    }

    /** 저장 전 정리·검사. 한도를 넘으면 IllegalArgumentException(→ 400). */
    public static String normalize(String text) {
        List<String> lines = lines(text);
        if (lines.size() > MAX_LINES) throw new IllegalArgumentException("DeepL 지시문은 " + MAX_LINES + "줄까지입니다.");
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).length() > MAX_CHARS)
                throw new IllegalArgumentException((i + 1) + "번째 줄이 " + MAX_CHARS + "자를 넘습니다(" + lines.get(i).length() + "자).");
        return String.join("\n", lines);
    }
}
