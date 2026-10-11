create table project (
  id bigserial primary key,
  file_name text not null,
  game_name text,
  storage_path text not null,
  sheet_count int not null,
  row_count int not null,
  uploaded_at timestamptz not null default now(),
  current_step int not null default 1
);

create table normalize_config (
  project_id bigint primary key references project(id) on delete cascade,
  sheet_name text not null,
  header_row int not null,
  src_col text not null,
  en_col text,
  key_col text,
  ko_col_mode text not null,
  ko_col text,
  ko_header text not null default 'KO'
);

create table term (
  id bigserial primary key,
  project_id bigint not null references project(id) on delete cascade,
  src_text text not null,
  type text not null,
  occurrences int not null default 0,
  candidates jsonb not null default '[]'::jsonb,
  confirmed_ko text,
  confirmed boolean not null default false,
  unique (project_id, src_text)
);

create table segment (
  id bigserial primary key,
  project_id bigint not null references project(id) on delete cascade,
  sheet_name text not null,
  row_no int not null,
  index_label text not null,
  key_text text,
  src_text text,
  en_text text,
  is_term boolean not null default false,
  term_id bigint references term(id) on delete set null,
  skipped boolean not null default false,
  unique (project_id, sheet_name, row_no)
);
create index segment_project_idx on segment(project_id, sheet_name, row_no);

create table translation_revision (
  id bigserial primary key,
  segment_id bigint not null references segment(id) on delete cascade,
  version int not null,
  text text not null,
  author text not null,
  created_at timestamptz not null default now(),
  unique (segment_id, version)
);

create table translation_job (
  id bigserial primary key,
  project_id bigint not null references project(id) on delete cascade,
  kind text not null,
  status text not null,
  total_batches int not null default 0,
  done_batches int not null default 0,
  started_at timestamptz not null default now(),
  finished_at timestamptz
);

create table job_log (
  id bigserial primary key,
  job_id bigint not null references translation_job(id) on delete cascade,
  seq int not null,
  line text not null,
  created_at timestamptz not null default now()
);
