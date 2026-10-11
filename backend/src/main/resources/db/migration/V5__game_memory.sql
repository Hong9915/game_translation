-- 같은 게임 이름의 프로젝트끼리 공유하는 공용 용어집과 문장 번역 메모리.
create table game_term (
  id bigserial primary key,
  game_name text not null,
  src_text text not null,
  type text not null,
  ko text not null,
  updated_at timestamptz not null default now(),
  unique (game_name, src_text)
);

create table translation_memory (
  id bigserial primary key,
  game_name text not null,
  src_text text not null,
  ko text not null,
  source text not null,
  updated_at timestamptz not null default now(),
  unique (game_name, src_text)
);
create index translation_memory_game_idx on translation_memory(game_name);
