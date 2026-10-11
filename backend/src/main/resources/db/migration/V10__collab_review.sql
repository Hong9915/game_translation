-- 공동 검토: 이름만으로 식별하는 검토자, 세그먼트 담당·완료, 사람이 고친 리비전의 수정자.
-- segment는 검수 화면이 늘 읽는 큰 표라, 잠금을 오래 기다리는 대신 빨리 실패하고 다시 배포하게 둔다.
set lock_timeout = '5s';

create table reviewer (
  id bigserial primary key,
  name text not null unique,
  color text not null,
  created_at timestamptz not null default now()
);

alter table segment
  add column assignee_id bigint references reviewer (id),
  add column done boolean not null default false,
  add column done_by bigint references reviewer (id),
  add column done_at timestamptz;
create index segment_project_sheet_assignee_idx on segment(project_id, sheet_name, assignee_id);

alter table translation_revision add column reviewer_id bigint references reviewer (id);
