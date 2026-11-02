package com.gametrans.translate;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.llm.LlmClient.Reference;
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
}
