package com.gametrans.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gametrans.llm.LlmClient.CommandFilter;
import com.gametrans.llm.LlmClient.CommandStep;
import java.util.List;
import java.util.Set;

/** 작업 목록의 작업 하나(설계 2026-10-08 §1). filter·steps는 REPLACE만, src·ko는 TERM만 쓴다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpSpec(String kind, CommandFilter filter, List<CommandStep> steps, String src, String ko) {
    public static final String REPLACE = "REPLACE";
    public static final String TAG_SPACING = "TAG_SPACING";
    public static final String JOSA = "JOSA";
    public static final String UNIFY_SAME_SOURCE = "UNIFY_SAME_SOURCE";
    /** 길이 줄이기(2026-10-13). GPT로 줄이므로 Pipeline.run이 아니라 BulkShorten이 돌리고, 다른 작업과 함께 쓰지 않는다. */
    public static final String SHORTEN = "SHORTEN";
    /**
     * 원문 용어 바꾸기(2026-10-26): 원문에 src가 든 행의 한국어를 GPT가 행마다 고쳐 그 용어를 ko로 쓰게 한다. 지금 번역이 행마다
     * 다른 말(레벨·단계·영어·빠짐)이라 찾아 바꾸기 규칙 하나로 못 잡을 때 쓴다. SHORTEN처럼 TermSwap이 돌리고 혼자만 쓴다.
     */
    public static final String TERM = "TERM";
    public static final Set<String> KINDS = Set.of(REPLACE, TAG_SPACING, JOSA, UNIFY_SAME_SOURCE, SHORTEN, TERM);

    public OpSpec(String kind, CommandFilter filter, List<CommandStep> steps) { this(kind, filter, steps, null, null); }

    public static OpSpec replace(CommandFilter filter, List<CommandStep> steps) { return new OpSpec(REPLACE, filter, steps); }
    public static OpSpec of(String kind) { return new OpSpec(kind, null, null); }
    public static OpSpec term(String src, String ko) { return new OpSpec(TERM, null, null, src, ko); }
}
