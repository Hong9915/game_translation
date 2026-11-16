package com.gametrans.translate;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.llm.LlmClient.Reference;
import com.gametrans.workspace.WorkspaceDtos.RowDto;
import java.util.List;
import java.util.Map;

public final class TranslateDtos {
    private TranslateDtos() {}

    /** sheet: 이 시트만(없으면 파일 전체). sourceLang: DeepL 원문 언어 "ZH"(기본) | "EN"(영어가 있는 행만 영어로). */
    public record TranslateRequest(String basis, String instructions, Integer batchSize, String sheet, String sourceLang) {}
    /** proofPendingRows: 교정 모드에서 아직 유지/수정 판정을 받지 않은 행. doneRows에는 들어가지 않는다. */
    public record TranslateStatus(long targetRows, long doneRows, long proofPendingRows, long glossaryCount, int batchSize, JobDto job) {}

    public record QuickTranslateRequest(String text, String en, String basis, String instructions) {}
    /** 행 번역 본문(선택). basis 기본은 EN_FIRST, instructions는 고정 지시 뒤에 붙는다. */
    public record RowTranslateRequest(String basis, String instructions) {}
    /** 행 줄이기 본문(선택). maxChars가 있으면 그 글자 수, 없으면 원문 글자 수 × ratio(없으면 기본 비율)를 목표로 한다. */
    public record RowShortenRequest(Integer maxChars, Double ratio) {}
    /** ko·len: 줄인 대상(지금 Fix, 없으면 기존 한국어)과 그 글자 수. 글자 수는 LengthCheck 규칙(공백·자리표시자·태그 제외). */
    public record RowShortenResult(String ko, int len, int target, List<ShortenCandidate> candidates) {}
    public record ShortenCandidate(String ko, int len) {}
    /** source: MEMORY(메모리의 같은 원문을 그대로 씀) | LLM. glossary·references는 이 번역에 실제로 적용한 것. */
    /** source: MEMORY | LLM | DEEPL | CACHE | GLOSSARY. problem: DeepL 태그 어긋남("TAG")·중국어 남음("HAN"), 없으면 null. */
    public record QuickTranslateResult(String ko, String source, Map<String, String> glossary, List<Reference> references,
                                       List<String> termMiss, boolean placeholderOk, String problem) {}

    /** 여러 행 다시 번역에 쓸 엔진. 서버 설정(translate.engine)과 무관하게 요청이 고른다. */
    public enum Engine { DEEPL, GPT }
    /** 여러 행 다시 번역(최대 50행, 한 게임). activityIds: 프로젝트 id → 앞 묶음이 만든 RETRANSLATE 변경 내역 id(이어 쓰기). */
    public record BatchTranslateRequest(List<Long> segmentIds, Engine engine, Map<Long, Long> activityIds) {}
    /** reason: 확정 | 제외 | 번역 결과 없음. */
    public record Skip(Long id, String reason) {}
    /** rows: 새 번역을 쓴 행. activityIds: 다음 묶음에 그대로 넘길 프로젝트 id → 변경 내역 id. */
    public record BatchTranslateResult(List<RowDto> rows, List<Skip> skipped, Map<Long, Long> activityIds) {}

    /** GPT로 다듬기 제안(최대 50행, 한 게임). 저장하지 않는다. */
    public record PolishRequest(List<Long> segmentIds) {}
    /** before: 지금 Fix, after: GPT가 고친 문장, reason: GPT가 댄 짧은 사유. */
    public record Proposal(Long segmentId, String before, String after, String reason) {}
    /** items: 바뀐 행의 제안. kept: 고칠 데 없다고 본 행 수. skipped: 확정 | 제외 | Fix 없음 | 자리표시자 어긋남 | 다듬기 결과 없음. */
    public record PolishResult(List<Proposal> items, int kept, List<Skip> skipped) {}
    /** base: 제안을 받을 때의 Fix(before). 그 사이 Fix가 바뀌었으면 넣지 않는다. */
    public record SaveItem(Long segmentId, String text, String base) {}
    /** 고른 제안을 사람이 저장한 것처럼 넣는다(최대 500행, 한 게임). */
    public record SaveBatchRequest(List<SaveItem> items) {}
    /** propagated: 같은 원문이라 따라 바뀐 행 수. activityIds: 이번에 만든 POLISH 변경 내역(파일마다 하나) — Ctrl+Z가 쓴다. */
    public record SaveBatchResult(int saved, int skipped, int propagated, List<Long> activityIds) {}
}
