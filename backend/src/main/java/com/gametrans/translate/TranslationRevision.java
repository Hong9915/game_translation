package com.gametrans.translate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "translation_revision")
@Getter @Setter @NoArgsConstructor
public class TranslationRevision {
    /** EXISTING: 올린 파일의 한국어 열에 이미 있던 문장(정규화 때 0차로 들어감). */
    /** MEMORY: 같은 게임의 번역 메모리에서 원문이 완전히 같은 문장을 가져옴(GPT 호출 없음). */
    /** LLM_PROOFREAD: 교정 모드에서 GPT가 기존 한국어의 틀린 곳을 고친 판. */
    /** IMPORTED: 최종 리뷰 버전으로 정규화할 때 파일의 수정 한국어 열에서 읽은 문장(사람이 이미 고친 것으로 본다). */
    public enum Author { LLM, HUMAN, LLM_SHORTEN, GLOSSARY, EXISTING, MEMORY, LLM_PROOFREAD, DEEPL, IMPORTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long segmentId;
    private int version;
    private String text;
    @Enumerated(EnumType.STRING)
    private Author author;
    private Instant createdAt = Instant.now();
    /** HUMAN 판을 남긴 검토자(reviewer.id). 익명이거나 사람이 아니면 null. */
    private Long reviewerId;
    /** 이 판을 만든 변경 내역(activity.id). 사람 저장·일괄 작업이 아닌 판(초기 번역·정규화 등)은 null. */
    private Long activityId;

    public static TranslationRevision of(Long segmentId, int version, String text, Author author) {
        TranslationRevision r = new TranslationRevision();
        r.segmentId = segmentId;
        r.version = version;
        r.text = text;
        r.author = author;
        return r;
    }
}
