package com.gametrans.game;

/** 게임별 GPT 번역·다듬기 지시문. 기본 지시문은 코드에 두고, 게임마다 덧붙이는 글(extra)만 저장한다. */
public final class GptInstructions {
    private GptInstructions() {}

    public static final int MAX_EXTRA = 2000;

    public static final String TRANSLATE_BASE = """
너는 중국어 모바일 게임의 글을 한국어로 옮기는 현지화 담당자다.
- 중국어 어순을 따르지 말고 자연스러운 한국어 어순으로 쓴다.
- {0}, %s, <color=…> 같은 자리표시자와 태그는 원문 그대로 둔다.
- 단어장에 있는 용어는 단어장 표기를 따른다.
- 이 게임이 이미 쓰는 말투와 이름 형식을 따른다.
- 버튼·메뉴 글은 짧게 쓰고, 뜻이 흐린 꾸밈말은 뺀다.
""".stripTrailing();

    public static final String POLISH_BASE = """
너는 중국어 게임의 한국어 번역을 다듬는 검수자다. 원문과 지금 한국어를 함께 보고 고친다.
- 뜻이 틀렸거나 빠진 곳, 번역투, 조사 오류만 고친다.
- 맞는 부분은 단어 하나도 바꾸지 않는다.
- 단어장 표기, 태그, 자리표시자는 그대로 둔다.
- 고칠 데가 없으면 지금 한국어를 그대로 낸다.
""".stripTrailing();

    /** 기본 지시문 뒤에 덧붙임을 붙인다. 덧붙임이 비면 기본 지시문 그대로. */
    public static String full(String base, String extra) {
        if (extra == null || extra.isBlank()) return base;
        return base + "\n\n추가 지시:\n" + extra.trim();
    }

    /** 저장 전 정리·검사. 빈 글은 null(덧붙임 없음), 한도를 넘으면 IllegalArgumentException(→ 400). */
    public static String normalize(String extra) {
        if (extra == null || extra.isBlank()) return null;
        String t = extra.trim();
        if (t.length() > MAX_EXTRA) throw new IllegalArgumentException("덧붙인 지시는 " + MAX_EXTRA + "자까지 됩니다.");
        return t;
    }
}
