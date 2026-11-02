package com.gametrans.deepl;

/** DeepL 요청 실패(네트워크, 4xx/5xx, 응답 개수 어긋남). 번역 작업은 그 묶음만 건너뛴다. */
public class MtException extends RuntimeException {
    public MtException(String message) { super(message); }
    public MtException(String message, Throwable cause) { super(message, cause); }

    /** 계정의 단어장 수가 한도에 찼다(DeepL 456 "Too many glossaries"). API Free는 하나만 둘 수 있다. */
    public static class GlossaryLimit extends MtException {
        public GlossaryLimit(String message, Throwable cause) { super(message, cause); }
    }
}
