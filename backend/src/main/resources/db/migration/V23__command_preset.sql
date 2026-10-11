-- 명령으로 고치기 저장 명령(설계 2026-10-08 §4). 게임 단위로 모두가 같이 쓴다.
create table command_preset (
  id         bigserial primary key,
  game_id    bigint not null references game(id) on delete cascade,
  name       text   not null,
  ops        jsonb  not null,
  created_by bigint,
  created_at timestamptz not null default now(),
  unique (game_id, name)
);
