-- 번역 메모리 중복 키를 원문 그대로 대신 원문 md5로(2026-10-13). 원문 그대로를 색인하면 배경 이야기처럼 긴 문장이
-- 색인 한도(2704바이트)를 넘어 정규화·Fix 저장이 500으로 실패한다. 게임 안 원문 하나에 한 줄이라는 규칙은 그대로다.
-- 번호가 V24_1인 까닭: master의 V25(DeepL)보다 먼저 프로드에 나가도 V24 → V24_1 → V25 순서가 맞게.
set lock_timeout = '5s';
alter table translation_memory drop constraint translation_memory_game_name_src_text_key;
create unique index translation_memory_game_src_md5_key on translation_memory (game_name, md5(src_text));
