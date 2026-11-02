-- 옛 용어집을 없앤다(2026-10-26). 용어는 단어장(glossary_entry)이 맡는다.
-- 파일별 용어집(term)과 그 확정으로 잠긴 "용어 행"(segment.is_term·term_id), 게임별 구 용어(game_term)를 지운다.
-- 행의 번역(translation_revision·latest_ko)은 그대로 둔다 — 잠금만 풀려 일반 행이 된다.
alter table segment drop column term_id;
alter table segment drop column is_term;
drop table term;
drop table game_term;
