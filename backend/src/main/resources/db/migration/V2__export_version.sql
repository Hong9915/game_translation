-- 내보낸 파일을 N차 수정본으로 보관한다. 원본은 project.storage_path.
create table export_version (
  id bigserial primary key,
  project_id bigint not null references project(id) on delete cascade,
  version int not null,
  file_name text not null,
  storage_path text not null,
  filled_rows int not null default 0,
  flagged_rows int not null default 0,
  created_at timestamptz not null default now(),
  unique (project_id, version)
);
