-- 교정 규칙(설계 2026-11-13): 손 수정에서 배운 낱말·문체 규칙, 미리 보기에서 뺀 행, 게임별 문체 기준, 행의 글 종류.
alter table segment add column text_kind char(1);

create table correction_rule (
  id bigserial primary key,
  game_id bigint not null,
  type text not null,                 -- WORD | STYLE
  of_text text not null,              -- STYLE은 '서술식'
  nf_text text not null,              -- STYLE은 '개조식'
  text_kind char(1),
  anchor text,
  shapes text,
  styles text,
  fixed_count int not null default 0,
  left_count int not null default 0,
  candidate_count int not null default 0,
  ratio double precision not null default 0,
  same_as_human double precision not null default 0,
  converted int not null default 0,
  kept int not null default 0,
  verdict text,
  last_edit_at timestamptz,
  flipped boolean not null default false,
  status text not null default 'SUGGESTED',   -- SUGGESTED | APPROVED | IGNORED
  applied_count int not null default 0,
  decided_by bigint,
  decided_at timestamptz,
  learned_at timestamptz
);
create unique index correction_rule_key on correction_rule (game_id, type, of_text, nf_text, coalesce(text_kind, '-'));

create table correction_rule_exclusion (
  id bigserial primary key,
  rule_id bigint not null references correction_rule(id) on delete cascade,
  segment_id bigint not null,
  decided_by bigint,
  decided_at timestamptz not null default now(),
  unique (rule_id, segment_id)
);

create table style_policy (
  game_id bigint not null,
  text_kind char(1) not null,
  policy text not null,               -- CONVERT | KEEP
  decided_by bigint,
  decided_at timestamptz not null default now(),
  primary key (game_id, text_kind)
);
