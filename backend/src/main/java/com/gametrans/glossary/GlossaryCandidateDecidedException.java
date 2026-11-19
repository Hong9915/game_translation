package com.gametrans.glossary;

/** 이미 승인·버림 처리된 후보에 approve·dismiss가 왔다. 컨트롤러가 409로 바꾸고 지금 상태(candidate)를 함께 돌려준다. */
public class GlossaryCandidateDecidedException extends RuntimeException {
    private final transient GlossaryDtos.CandidateDto candidate;

    public GlossaryCandidateDecidedException(String message, GlossaryDtos.CandidateDto candidate) {
        super(message);
        this.candidate = candidate;
    }

    public GlossaryDtos.CandidateDto getCandidate() { return candidate; }
}
