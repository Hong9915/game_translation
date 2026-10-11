-- 원문이 완전히 같은 행을 게임 전체에서 찾기 위한 인덱스와, 검수용 단어장 표.
-- src_text에 btree를 그대로 걸면 2704바이트가 넘는 긴 문장에서 삽입이 실패하므로 md5에 건다.
set lock_timeout = '5s';

create index segment_src_md5_idx on segment (md5(src_text)) where skipped = false;

-- V14가 만든 glossary는 엔티티도 API도 없이 비어 있다. 칼럼을 덧붙이는 대신 최종 모양으로 다시 만든다.
drop table if exists glossary;

create table glossary (
  id bigserial primary key,
  game_id bigint references game(id) on delete cascade,  -- null이면 공유 단어집
  kind text not null,          -- SRC: 간체 원문으로 찾는다 · KO: 기존 한국어로 찾는다
  match_mode text not null,    -- WHOLE: 통째로 같을 때 · PARTIAL: 들어 있을 때
  find text not null,          -- 찾을 문자열
  ko text,                     -- 쓸 한국어. null이면 메모만 하는 항목
  note text,
  created_by bigint references reviewer(id),
  updated_at timestamptz not null default now()
);

-- game_id가 null이면 유니크 제약이 안 걸리므로 부분 유니크 인덱스 둘로 나눈다.
create unique index glossary_game_key   on glossary (game_id, kind, find) where game_id is not null;
create unique index glossary_shared_key on glossary (kind, find)          where game_id is null;
create index        glossary_game_idx   on glossary (game_id);
