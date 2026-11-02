-- 게임별 DeepL 지시문(custom_instructions, 2026-10-26). 한 줄이 지시 하나 — 최대 10줄, 줄당 300자(DeepL 한도).
-- 기존 게임에는 기본 지시문을 넣는다. 화면에서 고치거나 비울 수 있다.
alter table game add column deepl_instructions text;
update game set deepl_instructions =
'모바일 RPG 게임의 한국어 현지화 문구로 번역한다. 게이머에게 익숙한 게임 용어를 쓴다.
UI 문구처럼 짧고 간결하게 쓴다. 군더더기 수식어와 불필요한 조사는 줄인다.
설명·알림 문장은 ''~합니다''체로, 버튼·항목·아이템 이름은 명사형으로 끝낸다.';
