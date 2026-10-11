-- SRC+PARTIAL 단어장 항목이 "지금 번역에서 find를 옮긴 한국어 표기"를 기억한다
-- (설계: docs/superpowers/specs/2026-09-29-glossary-old-ko-suggest-design.md §1.1).
-- 값이 있으면 원문에 find가 들어 있고 한국어에 old_ko가 들어 있는 행에서 old_ko를 ko로 바꾼다. 다른 조합은 항상 null.
set lock_timeout = '5s';

alter table glossary add column old_ko text;
