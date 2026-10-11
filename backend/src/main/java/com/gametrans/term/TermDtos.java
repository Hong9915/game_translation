package com.gametrans.term;

import com.gametrans.llm.LlmClient.Candidate;
import java.util.List;

public final class TermDtos {
    private TermDtos() {}

    public record TermDto(Long id, String srcText, String type, int occurrences, List<Candidate> candidates, String confirmedKo, boolean confirmed) {
        public static TermDto of(Term t) {
            return new TermDto(t.getId(), t.getSrcText(), t.getType().name(), t.getOccurrences(), t.getCandidates(), t.getConfirmedKo(), t.isConfirmed());
        }
    }
    public record TermStats(long totalRows, long extracted, long termRows, long remainingRows, long confirmed) {}
    public record TermsResponse(TermStats stats, List<TermDto> terms, int page, int size, long total) {}
    public record TermUpdate(String confirmedKo, Boolean confirmed) {}

    public record GameTermUpdate(String ko) {}
    /** 게임 용어집 일괄 치환: 원문에 src가 든(비우면 전체) 용어 가운데 번역에 from이 든 것을 to로 바꾼다. */
    public record GameTermReplace(String src, String from, String to) {}
    /** 일괄 치환 결과: 고친 용어 수와 그 여파로 바뀐 파일별 용어 · 번역 문장 · 번역 메모리 수. */
    public record GameTermReplaceResult(int matched, int terms, int sentences, int memory) {}

    /** 게임 용어집 수정 결과: 고친 용어와, 같이 바뀐 파일별 용어 수 · 번역 문장 수 · 번역 메모리 문장 수. */
    public record GameTermUpdateResult(com.gametrans.game.GameDtos.GameTermDto term, int terms, int sentences, int memory) {}
    /** 사람이 직접 넣는 용어. confirmedKo가 있으면 바로 확정한다. */
    public record TermCreate(String srcText, String type, String confirmedKo) {}
}
