-- 단어장 후보(승인 대기): 바꾸기 결정(关卡 → 스테이지)이 생기면 기록하고, 아무 검수자나 승인·버림.
-- 설계: docs/superpowers/specs/2026-11-18-connector-replace-glossary-design.md §6
create table glossary_candidate (
  id bigserial primary key,
  game_id bigint not null references game(id) on delete cascade,
  src text not null,
  ko text not null,
  old_ko text,
  rows int not null,
  reason text,
  source text not null check (source in ('CONNECTOR', 'COMMAND')),
  proposal_id bigint,
  created_by bigint,
  created_at timestamptz not null default now(),
  status text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'DISMISSED')),
  decided_by bigint,
  decided_at timestamptz
);
create index glossary_candidate_pending on glossary_candidate (game_id) where status = 'PENDING';

alter table connector_proposal add column term_src text, add column term_ko text, add column term_old text;
