-- 게임 단위 작업(용어집 정리): 프로젝트 없이 게임에 붙는다. translation_job은 작은 표라 관례대로 빨리 실패하게 둔다.
set lock_timeout = '5s';

alter table translation_job alter column project_id drop not null;
alter table translation_job add column game_id bigint references game(id) on delete cascade;
create index translation_job_game_idx on translation_job(game_id) where game_id is not null;
