-- 변경 내역(설계 2026-09-30). 행동 하나 = activity 한 줄, 그 행동이 만든 리비전은 activity_id로 매달린다.
-- 행 수·전후 본문은 리비전에서 파생하므로 여기엔 없다. 기존 리비전은 activity_id가 null이고 피드에 안 나온다.
set lock_timeout = '5s';

create table activity (
  id bigserial primary key,
  game_id bigint not null references game(id) on delete cascade,
  project_id bigint references project(id) on delete cascade,   -- 단어장 편집은 게임 단위라 null
  reviewer_id bigint references reviewer(id) on delete set null,
  kind text not null,
  detail jsonb not null default '{}',
  created_at timestamptz not null default now()
);
create index activity_project_idx on activity (project_id, id desc);
create index activity_game_idx on activity (game_id, id desc) where project_id is null;

-- default 없는 add column이라 표를 다시 쓰지 않는다. 부분 인덱스라 새 행만 들어간다.
alter table translation_revision add column activity_id bigint references activity(id) on delete set null;
create index translation_revision_activity_idx on translation_revision (activity_id) where activity_id is not null;
