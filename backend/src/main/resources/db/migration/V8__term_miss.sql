-- 번역 결과에 확정 용어의 한국어가 빠진 행 표시. "원문→한국어" 줄 단위, 없으면 null.
alter table segment add column term_miss text;
