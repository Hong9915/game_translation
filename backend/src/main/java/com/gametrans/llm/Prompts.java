package com.gametrans.llm;

import com.gametrans.llm.LlmClient.ShortenRow;
import com.gametrans.llm.LlmClient.TranslateBatchRequest;
import com.gametrans.llm.LlmClient.TranslateRow;
import com.gametrans.text.GlossaryCheck;
import java.util.List;
import java.util.Map;

public final class Prompts {
    private Prompts() {}

    static final String CLASSIFY_SYSTEM = """
        You classify Simplified Chinese phrases from one mobile game so a Korean reviewer can filter them.
        Each input line gives the phrase, its current Korean translation in this game, one source string that contains it,
        and sometimes an earlier glossary type as a hint. Pick exactly one category per phrase:
        - CHARACTER: a named hero, monster, boss, NPC or enemy type (蝎子王, 野猪王, 树妖射手).
        - ITEM: a named item, equipment piece, currency or resource (生命果实, 能量石, 钻石, 绿裤子).
        - SKILL: a named skill, talent, buff or status effect (灵魂收割, 治疗之雨, 石化).
        - PLACE: a named place, map, dungeon or game mode (角斗场, 浮空岛, 森林探险).
        - NAME_OTHER: another proper name: event, title, system or feature name (冬日庆典, 每日商城).
        - STAT: a stat or numeric game attribute (攻击, 生命, 暴击率, 持续时间, 冷却时间, 叠加次数).
        - NOUN: an ordinary common noun (奖励, 任务, 英雄, 装备).
        - VERB: a verb, verb phrase or predicate (获得, 提升, 释放技能, 击杀小怪, 无法).
        - COLOR: a color word, or a phrase whose point is a color (红色, 蓝, 紫色品质).
        - MODIFIER: an adjective, grade, rarity or other modifier (稀有, 史诗, 高级, 额外).
        - FUNCTION: a function word, pronoun, adverb, conjunction or quantity word (用于, 自己, 以下, 这里, 更加).
        - PHRASE: a fragment that crosses word boundaries and is none of the above (属性额外, 范围内目标).
        The hint is only a hint: an earlier COMMON may be NOUN, STAT, VERB or FUNCTION.
        Return ONLY JSON: {"items":[{"src":"<phrase exactly as given>","category":"<one category>"}]} with one item per input phrase.
        """;

    /** 교정 규칙 문체 다시 쓰기(설계 2026-11-13 §4). */
    static final String STYLE_FIX_SYSTEM = """
        You edit Korean game text. Change ONLY sentence-final verb endings (~습니다, ~ㅂ니다, ~십시오, ~하세요, ~한다, ~된다) into the terse
        noun style used in Korean game UI (개조식): e.g. 증가합니다 → 증가, 실패했습니다 → 실패, 사용하십시오 → 사용, 선택하세요 → 선택,
        지속된다 → 지속, 얻습니다 → 얻음, 없다 → 없음.
        Keep every other word, particle, tag (<...>), number, placeholder and punctuation mark exactly as it is.
        Leave questions (~까?) unchanged. If a row cannot be changed that way, return its ko unchanged.
        Return ONLY JSON: {"items":[{"index":"<index>","ko":"<edited korean>"}]} with one item per input row.
        """;

    static String styleFixUser(List<LlmClient.FixTermsRow> rows) {
        StringBuilder sb = new StringBuilder("Rows:\n");
        for (LlmClient.FixTermsRow r : rows)
            sb.append("index: ").append(r.index()).append("\ncn: ").append(r.cn()).append("\nko: ").append(r.ko()).append("\n\n");
        return sb.toString();
    }

    /** 교정 규칙 글 종류(설계 2026-11-13 §3). 시험판(gpt_kind.py)의 문구 그대로, 답 모양만 items로. */
    static final String TEXT_KIND_SYSTEM = """
        게임 번역 문자열의 '글 종류'를 고른다. 각 행은 중국어 원문(zh)과 한국어 번역(ko)이다. 코드 하나만 고른다.
        E 스킬·버프·장비·능력치 효과 설명
        I 아이템·상품 설명, 획득처·사용처 안내
        Q 퀘스트·업적·해금 조건, 목표
        S 시스템 알림·팝업·오류·확인 메시지
        H 규칙·플레이 방법·도움말 설명
        T 이야기·배경·캐릭터 소개 서술
        D 캐릭터 대사(누군가 말하는 말)
        N 공지·메일·이벤트 안내문
        U 이름·버튼·짧은 UI 문구
        JSON으로만 답한다: {"items":[{"id":"행id","code":"코드"}, ...]} 입력 행마다 하나.
        """;

    static String textKindUser(List<LlmClient.KindInput> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            LlmClient.KindInput r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(quote(r.id())).append(",\"zh\":").append(quote(cut(r.zh()))).append(",\"ko\":").append(quote(cut(r.ko()))).append('}');
        }
        return sb.append(']').toString();
    }

    private static String cut(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s; }

    private static String quote(String s) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s == null ? "" : s); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    static final String DRAFT_TERMS_SYSTEM = """
        You draft Korean glossary entries for terms from one Chinese mobile game, requested by the game's developer.
        Each term will become the fixed Korean name used everywhere in the game, so give one natural Korean rendering per term.
        Follow the category rule at the top of the message. Keep naming consistent with the terms already decided in this game, and follow the game style notes.
        Do not add explanations, quotes or alternatives to the Korean.
        For each term give a reason: one short sentence in Korean saying why you chose that rendering.
        Return ONLY JSON: {"items":[{"src":"<term exactly as given>","ko":"<korean>","reason":"<한국어 한 문장>"}]} with one item per input term.
        """;

    /** 양식 분류별 지시. 모르는 분류는 OTHER처럼 둔다. */
    static String draftTermsRule(String category) {
        return switch (category == null ? "" : category) {
            case "CHARACTER" -> "Decide first whether each term is a personal name or a title/epithet. Transliterate names into natural Korean (Chinese reading, or the setting's spelling); translate titles by meaning (森之召唤师 → 숲의 소환사).";
            case "MONSTER", "ITEM", "SKILL", "PLACE" -> "Translate by meaning, short and natural, the way Korean mobile games name them.";
            default -> "Prefer the established Korean game term (钻石 → 다이아, 公会 → 길드, 体力 → 스태미나).";
        };
    }

    static String draftTermsUser(LlmClient.TermDraftRequest req) {
        StringBuilder sb = new StringBuilder(draftTermsRule(req.category())).append("\n\n");
        if (req.known() != null && !req.known().isEmpty()) {
            sb.append("Already decided in this game (keep naming consistent with these):\n");
            for (LlmClient.Reference r : req.known()) sb.append("- ").append(r.cn()).append(" → ").append(r.ko()).append('\n');
            sb.append('\n');
        }
        if (req.instructions() != null && !req.instructions().isEmpty()) {
            sb.append("Game style notes:\n");
            for (String line : req.instructions()) sb.append("- ").append(line).append('\n');
            sb.append('\n');
        }
        sb.append("Terms:\n");
        for (String t : req.terms()) sb.append("- ").append(t).append('\n');
        return sb.toString();
    }

    public static final String COMMAND_SYSTEM = """
        You help a Korean reviewer of a Chinese→Korean mobile game localization. The reviewer types a command in Korean about
        the Korean translations of ONE sheet. You have tools that look at the sheet and try edits; the server applies nothing
        until the reviewer sees a before/after preview and picks rows.
        Build a pipeline of ops applied in order to each Korean translation (confirmed rows are never changed), 1 to 6 ops; UNIFY_SAME_SOURCE only as the first op:
        - {"kind":"REPLACE","filter":{"in":"ko"|"src","contains":"..."} or null,"steps":[{"find","replace","regex"}]}
          filter limits rows ("구미호 검색했을 때" → {"in":"ko","contains":"구미호"}; "원문에 X 있는 것만" → "in":"src").
          regex false swaps text exactly as written — prefer it for plain swaps ("A을 B로 바꿔줘").
          regex true only when needed (spacing, positions); then find is a Java java.util.regex pattern and replace may use $1 (a literal $ in replace is \\$).
          Use only syntax java.util.regex accepts: Chinese characters are \\p{IsHan}, Hangul is \\p{IsHangul}
          (there is no \\p{IsHanja}); lookbehind must have a bounded length. Write each backslash once: \\S, not \\\\S.
        - {"kind":"TAG_SPACING"}: spaces between a word and an adjacent <tag> where Korean spacing needs it (Kiwi).
        - {"kind":"JOSA"}: fixes particles that do not fit the previous syllable's batchim (생명를→생명을). Add it after a REPLACE
          that changes the last syllable of a word that particles attach to. After another op it fixes only rows that op changed; alone it fixes the whole sheet.
        - {"kind":"UNIFY_SAME_SOURCE"}: rows with the same source text get one translation (confirmed one, else the most used).
          Only as the first op.
        - {"kind":"TERM","src":"<Chinese term>","ko":"<Korean>"}: every row whose source contains src gets its Korean rewritten
          by GPT so that term is rendered as ko (whatever word, English or omission it uses now). Alone only — no other ops.
        Rules for REPLACE:
        - Change only what the command asks.
        - <...> tags and {...} placeholders may be inside a match only if the replacement keeps them unchanged and in order —
          capture them with ( ) and put them back with $n. The server skips matches that would change, drop or reorder them.
        - Sentences that differ only in numbers: capture the numbers (and any tags around them) with ( ) and reuse them, e.g.
          find 피해 계수가(<color=[^>]*>)([0-9.]+%)(</color>)로 증가했습니다 replace 피해 계수 $1$2$3 증가.
        - Line breaks appear as real newlines, as a literal backslash-n (two characters \\ and n) or as <br>.
          For spacing rules use [ \\t] instead of \\s. Leave list markers at the start of a line alone ("- 항목").
        - Read the direction carefully: "A을 B로 바꿔줘" and "A 이런거를 B 이걸로" mean find A and write B.
        - 이거, 이것, 이런 거, 저거, 그거 point back to the term the reviewer just named — never search for them as text.
          "子弹 이거 번역을 다 탄환으로 바꿔줘" means the source term 子弹, not the word 이거.
        - Chinese text in the command is a source term. When the command wants that term translated as some Korean
          ("关卡 이거를 스테이지로 바꿔줘", "子弹 이거 번역을 다 탄환으로"), use the single op {"kind":"TERM","src":"关卡","ko":"스테이지"}:
          its current renderings differ row by row, so REPLACE rules miss rows. Check it with try_ops, then finish.
          Use REPLACE with "in":"src" only when the command says exactly which Korean word to swap.
        - When one punctuation mark replaces another, make the spacing around the new mark look natural in Korean.
        - "대시" or "-" means any of - – — unless the command names just one; keep each dash as it is;
          a dash glued to a following number is a minus sign — leave it alone.
          Example "- 앞뒤 띄어쓰기 없애줘": find (?<=\\S)(?<!\\\\n)(?<!<br>)[ \\t]*([-–—]+)(?![0-9])[ \\t]*(?=\\S) replace $1 regex true.
        How to work:
        - Every reply is a tool call. Never ask the reviewer anything: when the command is unclear, look with find_rows
          (Chinese text → "in":"src") and take the broadest reasonable reading — the reviewer unticks rows in the preview.
        - When the command gives an example sentence, call find_rows with a distinctive part of it first to see the real text
          (tags, numbers) before writing the rule.
        - Use find_rows when you need to see how the text is really written. Use try_ops to test a pipeline: it returns how many
          rows change, examples and warnings. A JOSA warning means the edit leaves a wrong particle — add {"kind":"JOSA"}.
          A TAG warning means tags or placeholders break — change the steps. Use check_josa to check a few sentences.
        - For "태그 깨진/빠진 행 찾아줘" call finish with tag_mismatch true (that is a check, not an edit).
        - Finish with finish(summary, ops). summary: one short Korean sentence saying which rows and what changes.
          Never ask the reviewer to confirm or to apply anything: the server shows a before/after preview and the reviewer
          picks rows there. Once try_ops shows the change you want, call finish right away.
        - If the command needs meaning, retranslation or judgement row by row, call cannot with one short Korean sentence
          why it cannot be done with these ops.
        """;

    /** 단어장 요청 GPT 표기 확인(2026-11-02): 용어 하나가 번역본에서 어떻게 옮겨졌는지 행을 열어 보고 고른다. */
    public static final String TERM_FIND_SYSTEM = """
        You help a Korean reviewer of a Chinese→Korean mobile game localization. Find how ONE source term is rendered
        in the existing Korean translation of this game. Use find_rows to look at rows (source and Korean pairs), then call finish.
        Rules:
        - A rendering is only the Korean fragment that translates the term itself. Never include translations of other words
          in the same row. Example: 公会战简单难度buff → 부족전 쉬움 난이도 버프 — the rendering of 公会战 is 부족전, not 버프.
        - If the term is a whole name, the rendering is the whole matching Korean name, not its pieces.
          Example: 爱神·丘比特 → 홍실의 연인·큐피트 (one rendering), not 홍실 / 연인 / 큐피트.
        - Different renderings of the same term are separate entries (e.g. 교환소 and 교환 상점). Spacing variants are separate too.
          Most used first.
        - Every rendering must appear verbatim in the Korean of a row whose source contains the term. Never invent one.
        - Give the bare form without trailing particles or endings (은/는/이/가/을/를/의/에/에서/으로 …): 부족전, not 부족전은.
        - If the term is not translated anywhere, call finish with none: true and a reason.
        - reason: one short Korean sentence (e.g. why a word in the same row was left out). Optional.
        """;

    public static String termFindUser(String src, String category, List<String> samples, int total) {
        StringBuilder sb = new StringBuilder("Term: ").append(src).append("\nCategory: ").append(category)
                .append("\nRows whose source contains the term: ").append(total)
                .append("\n\nSample pairs (source ⇒ Korean (×times), a real newline is shown as ⏎):\n");
        for (String s : samples) sb.append("- ").append(s.replace("\n", "⏎")).append('\n');
        return sb.toString();
    }

    public static String commandUser(String command, List<String> samples) {
        StringBuilder sb = new StringBuilder("Command: ").append(command).append("\n\nSample Korean translations from this sheet (a real newline is shown as ⏎):\n");
        for (String s : samples) sb.append("- ").append(s.replace("\n", "⏎")).append('\n');
        return sb.toString();
    }

    static String classifyUser(List<LlmClient.PhraseInput> phrases) {
        StringBuilder sb = new StringBuilder("Phrases (phrase | Korean | example | hint):\n");
        for (LlmClient.PhraseInput p : phrases)
            sb.append("- ").append(p.src()).append(" | ").append(p.ko() == null ? "" : p.ko()).append(" | ")
              .append(p.example() == null ? "" : p.example()).append(" | ").append(p.hint() == null ? "" : p.hint()).append('\n');
        return sb.toString();
    }

    static String translateSystem(TranslateBatchRequest req) {
        String basis = "EN_FIRST".equals(req.basis())
                ? "When an English text is given, translate from the English text and use the Chinese only to resolve ambiguity. When no English text is given, translate from the Chinese."
                : "Translate from the Simplified Chinese text. The English text, if present, is only a hint.";
        StringBuilder sb = new StringBuilder("""
            You are a professional game localizer producing Korean UI and item text.
            Rules:
            1. Keep placeholders such as {0}, {1}, %s, %d and markup tags such as <color=#FFD700>...</color> exactly as in the source, in the same order.
            2. Use the glossary EXACTLY as given whenever a glossary source term appears. Never re-translate a glossary term.
            3. Keep the Korean concise: UI space is limited. Prefer the same length or shorter than the Chinese.
            4. Return ONLY JSON: {"items":[{"index":"<same index as input>","ko":"<korean>"}]} with one item per input row.
            """);
        sb.append(basis).append('\n');
        if (req.instructions() != null && !req.instructions().isBlank()) sb.append("Additional instructions: ").append(req.instructions()).append('\n');
        appendGlossary(sb, req.glossary());
        if (req.references() != null && !req.references().isEmpty()) {
            sb.append("Reference translations from earlier work on this game. Match their wording, tone and terminology; when a row differs from a reference only in numbers or a few words, reuse the reference and change only those parts:\n");
            for (LlmClient.Reference r : req.references()) sb.append("- ").append(r.cn()).append(" → ").append(r.ko()).append('\n');
        }
        return sb.toString();
    }

    /** 확정 번역 뒤의 메모(의역: …)는 번역어와 떼어 참고로만 보여 준다 — 붙여 두면 모델이 메모까지 문장에 넣거나, 검사가 메모째 찾는다. */
    static void appendGlossary(StringBuilder sb, Map<String, String> glossary) {
        if (glossary == null || glossary.isEmpty()) return;
        sb.append("Glossary (source → Korean). A [note: …] explains the term; never put the note itself into the translation:\n");
        for (Map.Entry<String, String> e : glossary.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" → ").append(GlossaryCheck.koOf(e.getValue()));
            String note = GlossaryCheck.noteOf(e.getValue());
            if (note != null) sb.append(" [note: ").append(note).append(']');
            sb.append('\n');
        }
    }

    static String translateUser(List<TranslateRow> rows) {
        StringBuilder sb = new StringBuilder("Rows (consecutive lines from the same sheet, keep context):\n");
        for (TranslateRow r : rows) {
            sb.append("index: ").append(r.index()).append("\ncn: ").append(r.cn()).append('\n');
            if (r.en() != null && !r.en().isBlank()) sb.append("en: ").append(r.en()).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    static String proofreadSystem(LlmClient.ProofreadBatchRequest req) {
        StringBuilder sb = new StringBuilder("""
            You are a senior Korean game localization proofreader. Each row has the Simplified Chinese source (cn), an
            optional English text (en) and the EXISTING Korean translation (ko) that is already in the game.
            Your default verdict is KEEP. The existing Korean was written for this game and players already know it, so a
            different but equally valid wording is NOT a reason to change it. Choose FIX only for a real defect:
            - MISTRANSLATION: the Korean means something different from the source, or a game term is rendered with its
              everyday meaning (属性 → 재산 instead of 속성).
            - BROKEN: ungrammatical or machine-garbled Korean, missing spaces between words (저장의key불가로빈), leftover
              Chinese, or a particle left as "이(가)" with nothing before it.
            - PLACEHOLDER: a placeholder such as {0}, %s, ${id} or a markup tag is missing, changed or reordered.
            - GLOSSARY: the source contains a glossary term and the Korean uses a different word for it.
            Never FIX for tone, politeness level, sentence ending style (~습니다 vs ~함), word order, punctuation
            width or spacing taste, or to make the text shorter.
            When you FIX, change as little as possible: keep the existing sentence structure, ending style and every part
            that was already right. Insert a glossary term as a natural part of the sentence with correct particles; never
            produce an awkward or redundant sentence just to include it. Keep placeholders and tags exactly as in the source.
            """ + VERDICT_FORMAT);
        if (req.instructions() != null && !req.instructions().isBlank()) sb.append("Additional instructions: ").append(req.instructions()).append('\n');
        appendGlossary(sb, req.glossary());
        return sb.toString();
    }

    /** 교정과 다듬기가 같이 쓰는 응답 형식 줄. */
    static final String VERDICT_FORMAT = """
            Return ONLY JSON: {"items":[{"index":"<same index>","verdict":"KEEP"} or
            {"index":"<same index>","verdict":"FIX","ko":"<corrected korean>","reason":"<MISTRANSLATION|BROKEN|PLACEHOLDER|GLOSSARY>: <짧은 한국어 설명>"}]}
            with one item per input row.
            """;

    /**
     * GPT로 다듬기(2026-11-13). 게임의 다듬기 지시문(req.instructions = 기본 + 덧붙임)을 맨 앞에 두고, 단어장, 응답 형식은 교정과 같다.
     * 행 입력(cn·en·지금 한국어 ko)도 교정과 같은 proofreadUser를 쓴다.
     */
    static String polishSystem(LlmClient.ProofreadBatchRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.instructions() != null && !req.instructions().isBlank()) sb.append(req.instructions().strip()).append("\n\n");
        sb.append("Each row has the Simplified Chinese source (cn), an optional English text (en) and the current Korean (ko).\n");
        appendGlossary(sb, req.glossary());
        sb.append(VERDICT_FORMAT);
        return sb.toString();
    }

    static String proofreadUser(List<LlmClient.ProofreadRow> rows) {
        StringBuilder sb = new StringBuilder("Rows (consecutive lines from the same sheet, keep context):\n");
        for (LlmClient.ProofreadRow r : rows) {
            sb.append("index: ").append(r.index()).append("\ncn: ").append(r.cn()).append('\n');
            if (r.en() != null && !r.en().isBlank()) sb.append("en: ").append(r.en()).append('\n');
            sb.append("ko: ").append(r.ko()).append("\n\n");
        }
        return sb.toString();
    }

    static final String SHORTEN_SYSTEM = """
        You are a Korean game localizer fixing UI overflow. For each row, rewrite the Korean so that its length (excluding
        placeholders, tags and spaces) is at most maxChars, while preserving meaning, placeholders and tags exactly.
        Return ONLY JSON: {"items":[{"index":"<same index>","ko":"<shorter korean>"}]}.
        """;

    static String shortenUser(List<ShortenRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (ShortenRow r : rows) {
            sb.append("index: ").append(r.index()).append("\ncn: ").append(r.cn()).append("\nko: ").append(r.ko())
              .append("\nmaxChars: ").append(r.maxChars()).append("\n\n");
        }
        return sb.toString();
    }

    static String shortenOptionsSystem(LlmClient.ShortenOptionsRequest req) {
        StringBuilder sb = new StringBuilder("""
            You are a Korean game localizer fixing UI text that is too long for its box. You get the Simplified Chinese
            source (cn), an optional English text (en), the current Korean (ko) and a target length (maxChars). Length is
            counted without spaces, placeholders and markup tags.
            Propose exactly three shorter Korean versions of ko, from least to most shortened:
            1. slightly shorter, keeping almost all of ko;
            2. at most maxChars;
            3. as short as possible while a player still understands it.
            Rules:
            - Keep the meaning of the source. Never drop information a player needs (numbers, targets, conditions).
            - Keep every placeholder such as {0}, %s, %d, @10%@ and every markup tag exactly as in ko, in the same order.
            - Use the glossary Korean exactly whenever its source term appears. Never shorten or replace a glossary term.
            - Shorten by dropping particles and redundant words, using noun endings (획득했습니다 → 획득), and shorter
              established game words. Keep the ending style of ko unless a noun ending is needed to reach the target.
            - Each "ko" is the text itself: no explanation, no alternatives, no parentheses that ko does not have.
            Return ONLY JSON: {"items":[{"ko":"<slightly shorter>"},{"ko":"<at most maxChars>"},{"ko":"<shortest>"}]}.
            """);
        appendGlossary(sb, req.glossary());
        if (req.notes() != null && !req.notes().isBlank()) sb.append("Notes: ").append(req.notes().trim()).append('\n');
        return sb.toString();
    }

    static String shortenOptionsUser(LlmClient.ShortenOptionsRequest req) {
        StringBuilder sb = new StringBuilder("cn: ").append(req.cn()).append('\n');
        if (req.en() != null && !req.en().isBlank()) sb.append("en: ").append(req.en()).append('\n');
        sb.append("ko: ").append(req.ko()).append("\nmaxChars: ").append(req.maxChars()).append('\n');
        return sb.toString();
    }

    static final String FIX_TERMS_SYSTEM = """
        You are a Korean game localization editor. Each row has the Simplified Chinese source (cn), an optional English
        text (en), the current Korean (ko) and the glossary terms the Korean is missing (need: source → required Korean).
        Rewrite ko so that every required Korean term appears exactly as written, replacing the word that currently
        renders that source term. Change as little as possible: keep the sentence structure, the ending style and every
        other part. Adjust particles (은/는, 이/가, 을/를, 와/과, 으로/로) to fit the inserted term. Keep placeholders such as
        {0}, %s, ${id} and markup tags exactly as in the source. A [note: …] only explains a term; never put it into the text.
        Return ONLY JSON: {"items":[{"index":"<same index>","ko":"<corrected korean>"}]} with one item per input row.
        """;

    static final String TERM_SWAP_SYSTEM = """
        You are a Korean game localization editor. The reviewer decided that a Chinese source term must be translated as one
        given Korean word everywhere. Each row has the Simplified Chinese source (cn), an optional English text (en), the
        current Korean (ko) and the rule (need: source term → required Korean).
        In ko, find how that source term is rendered now and rewrite only that part so the required Korean appears exactly
        as written. The rendering is usually another Korean word with the same meaning — a synonym (任务 → 임무, 미션, 의뢰,
        과제), possibly inside a compound (cn 悬赏任务, ko 현상금 의뢰 → 현상금 퀘스트) — or an English word, Chinese left
        untranslated, or a misspelling of the required word. Replace that word in place; do not keep it and add the required
        word somewhere else (wrong: 현상금 의뢰 완료 속도 퀘스트 가속).
        Only when ko has no word for the term at all, insert the required word where the source puts it, attached to the
        word it modifies (cn 累充任务活动, ko 누적 충전 이벤트 → 누적 충전 퀘스트 이벤트; wrong: 누적 충전 이벤트 퀘스트).
        If you cannot tell where it belongs, return ko unchanged. Numbers and codes attached to the term are written the way
        the source writes them, with a space between the Korean word and the number (cn 关卡 54-14, ko Level 54·14 →
        스테이지 54-14; cn 通关关卡2-26解锁, ko 2-26 클리어 후 해제 → 스테이지 2-26 클리어 후 해제).
        If ko is still the untranslated Chinese source, translate the whole text into natural Korean using the required word
        (cn 通关关卡, ko 通关关卡 → 스테이지 클리어). Never leave Chinese characters glued to the Korean word.
        Change nothing else: keep the sentence structure, the ending style, spacing and line breaks. Adjust particles
        (은/는, 이/가, 을/를, 와/과, 으로/로) to fit the new word. Keep placeholders such as {0}, %s, %d and markup tags exactly.
        If the source term is only part of a longer word with a different meaning, return ko unchanged.
        Return ONLY JSON: {"items":[{"index":"<same index>","ko":"<corrected korean>"}]} with one item per input row.
        """;

    static String fixTermsUser(List<LlmClient.FixTermsRow> rows) {
        StringBuilder sb = new StringBuilder("Rows (consecutive lines from the same sheet, keep context):\n");
        for (LlmClient.FixTermsRow r : rows) {
            sb.append("index: ").append(r.index()).append("\ncn: ").append(r.cn()).append('\n');
            if (r.en() != null && !r.en().isBlank()) sb.append("en: ").append(r.en()).append('\n');
            sb.append("ko: ").append(r.ko()).append("\nneed:\n");
            for (Map.Entry<String, String> e : r.missing().entrySet()) {
                sb.append("- ").append(e.getKey()).append(" → ").append(GlossaryCheck.koOf(e.getValue()));
                String note = GlossaryCheck.noteOf(e.getValue());
                if (note != null) sb.append(" [note: ").append(note).append(']');
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
