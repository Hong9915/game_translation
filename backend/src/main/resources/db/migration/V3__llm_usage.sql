-- GPT 호출 한 번당 토큰 사용량. 프로젝트·작업·단계·모델별 집계에 쓴다.
create table llm_usage (
  id bigserial primary key,
  project_id bigint references project(id) on delete cascade,
  job_id bigint,
  stage text not null,
  model text not null,
  prompt_tokens int not null default 0,
  completion_tokens int not null default 0,
  reasoning_tokens int not null default 0,
  created_at timestamptz not null default now()
);
create index llm_usage_project_idx on llm_usage(project_id);
