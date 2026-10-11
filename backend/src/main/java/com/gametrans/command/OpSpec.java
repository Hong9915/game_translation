package com.gametrans.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gametrans.llm.LlmClient.CommandFilter;
import com.gametrans.llm.LlmClient.CommandStep;
import java.util.List;
import java.util.Set;

/** 작업 목록의 작업 하나(설계 2026-10-08 §1). filter·steps는 REPLACE만 쓴다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpSpec(String kind, CommandFilter filter, List<CommandStep> steps) {
    public static final String REPLACE = "REPLACE";
    public static final String TAG_SPACING = "TAG_SPACING";
    public static final String JOSA = "JOSA";
    public static final String UNIFY_SAME_SOURCE = "UNIFY_SAME_SOURCE";
    public static final Set<String> KINDS = Set.of(REPLACE, TAG_SPACING, JOSA, UNIFY_SAME_SOURCE);

    public static OpSpec replace(CommandFilter filter, List<CommandStep> steps) { return new OpSpec(REPLACE, filter, steps); }
    public static OpSpec of(String kind) { return new OpSpec(kind, null, null); }
}
