-- 기존 번역 교정 모드: 기존 한국어를 살린 채 GPT가 틀린 행만 고친다.
alter table normalize_config add column proofread_existing boolean not null default false;
-- 교정 판정을 아직 받지 않은 행.
alter table segment add column proof_pending boolean not null default false;
-- 교정에서 고친 행의 사유(오역, 비문 등). 고치지 않았으면 null.
alter table segment add column proof_note text;
