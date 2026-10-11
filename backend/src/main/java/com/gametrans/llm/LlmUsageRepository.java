package com.gametrans.llm;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {
    /** stage, model, calls, prompt, completion, reasoning */
    @Query("select u.stage, u.model, count(u), sum(u.promptTokens), sum(u.completionTokens), sum(u.reasoningTokens) from LlmUsage u where u.projectId = :projectId group by u.stage, u.model order by u.stage, u.model")
    List<Object[]> sumByStageAndModel(Long projectId);

    @Query("select u.model, count(u), sum(u.promptTokens), sum(u.completionTokens), sum(u.reasoningTokens) from LlmUsage u group by u.model order by u.model")
    List<Object[]> sumAllByModel();

    @Query("select coalesce(sum(u.promptTokens),0), coalesce(sum(u.completionTokens),0) from LlmUsage u where u.jobId = :jobId")
    List<Object[]> sumByJob(Long jobId);
}
