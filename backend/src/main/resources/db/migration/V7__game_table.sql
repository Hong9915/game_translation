-- 게임을 독립된 기록으로. 파일 없이도 만들 수 있고, 모든 프로젝트는 게임에 속한다.
create table game (
  id bigserial primary key,
  name text not null unique,
  created_at timestamptz not null default now()
);

insert into game(name) select distinct game_name from project where game_name is not null and game_name <> '';
insert into game(name)
  select '미분류' where exists (select 1 from project where game_name is null or game_name = '')
    and not exists (select 1 from game where name = '미분류');

alter table project add column game_id bigint references game(id);
update project p set game_id = g.id from game g where g.name = coalesce(nullif(p.game_name, ''), '미분류');
alter table project alter column game_id set not null;
alter table project drop column game_name;
create index project_game_idx on project(game_id);
