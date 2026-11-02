-- 단어장 요청(2026-10-30): 개발사가 채운 요청양식 한 번 올린 것과 그 용어들. 승인하면 게임 단어장에 들어간다.
create table glossary_request (
  id bigserial primary key,
  game_id bigint not null references game(id) on delete cascade,
  file_name text not null, storage_key text not null,
  created_by bigint, created_at timestamptz not null default now());
create table glossary_request_item (
  id bigserial primary key,
  request_id bigint not null references glossary_request(id) on delete cascade,
  seq int not null, category text not null, src text not null,
  renderings jsonb not null default '[]', source text not null,     -- CORPUS | GPT
  gpt_ko text, gpt_reason text,
  status text not null,                                             -- READY | PENDING | FAILED | APPROVED
  final_ko text, approved_by bigint, approved_at timestamptz,
  unique (request_id, src));
create index on glossary_request (game_id);
